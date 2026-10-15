# Architecture

How this application is put together, and — more usefully — why each piece is the
way it is rather than one of the alternatives.

---

## Contents

1. [Shape of the system](#1-shape-of-the-system)
2. [Layering](#2-layering)
3. [Database schema](#3-database-schema)
4. [Concurrency: the overselling problem](#4-concurrency-the-overselling-problem)
5. [Idempotency](#5-idempotency)
6. [The order lifecycle](#6-the-order-lifecycle)
7. [Money](#7-money)
8. [Security](#8-security)
9. [Caching](#9-caching)
10. [Transactions](#10-transactions)
11. [Error handling](#11-error-handling)
12. [Observability](#12-observability)
13. [Trade-offs and what is missing](#13-trade-offs-and-what-is-missing)

---

## 1. Shape of the system

```
                    ┌──────────────┐
   HTTP ──────────► │  TraceIdFilter│  correlation id → MDC + X-Trace-Id
                    └──────┬───────┘
                           ▼
                    ┌──────────────────────┐
                    │ JwtAuthenticationFilter│  Bearer token → SecurityContext
                    └──────┬───────────────┘
                           ▼
                    ┌──────────────┐
                    │ Authorization│  URL rules + @PreAuthorize
                    └──────┬───────┘
                           ▼
        ┌──────────────────────────────────────┐
        │           Controllers                │  bind, validate, choose a status
        └──────────────────┬───────────────────┘
                           ▼
        ┌──────────────────────────────────────┐
        │            Services                  │  business rules, transactions
        └────────┬─────────────────────┬───────┘
                 ▼                     ▼
        ┌─────────────────┐   ┌─────────────────┐
        │   Repositories  │   │ PaymentProvider │  (simulated)
        └────────┬────────┘   └─────────────────┘
                 ▼
        ┌─────────────────┐   ┌─────────────────┐
        │   PostgreSQL    │   │      Redis      │  catalogue cache (optional)
        └─────────────────┘   └─────────────────┘
```

Single service, single database. There is no message broker and no second service,
because this domain does not need one — and adding one would be architecture for its
own sake in a project whose point is to demonstrate judgement.

---

## 2. Layering

Packages are organised **by feature, not by layer**: `order/` contains the order
controller, service, repository, entity and DTOs together. The alternative —
`controller/`, `service/`, `repository/` at the top — means every change to one
feature touches four distant directories, and nothing stops the order service from
reaching into the cart repository because they are neighbours.

| Layer | Responsibility | Never does |
|---|---|---|
| Controller | HTTP: bind, validate, delegate, pick a status code | business rules |
| Service | business rules, transaction boundaries | HTTP concerns |
| Repository | data access | business rules |
| Entity | invariants that are always true of the data | orchestration |

Two rules this project actually enforces:

**Entities are never serialised to the API.** `User` carries `passwordHash`, and one
forgotten `@JsonIgnore` would put a hash in a response body. A separate
`UserResponse` makes exposure opt-in: a new entity field is invisible until someone
adds it on purpose.

**Invariants live in entities, not services.** `Order` has no `setStatus`; every
transition goes through a method that consults `OrderStatus.canTransitionTo`. An
illegal jump such as `CREATED → DELIVERED` is impossible to express rather than
merely discouraged by a service that might be bypassed.

---

## 3. Database schema

Twelve tables, defined by Flyway migrations V1–V9. `spring.jpa.hibernate.ddl-auto`
is `validate`: the migrations are the schema's source of truth, and the application
refuses to start if an entity mapping has drifted from them. `update` would let the
two diverge silently and generate different DDL in production than in development.

```
users ──┬── refresh_tokens          (hash only, revocable)
        ├── carts ── cart_items ─────┐
        ├── orders ── order_items ───┤
        ├── payments ────────────────┤
        └── idempotency_keys         │
                                     │
categories ── products ── inventory ─┘
```

### Constraints that carry weight

| Constraint | Prevents |
|---|---|
| `CHECK (available >= 0)` on `inventory` | Overselling, at the storage layer, whatever any application does |
| `uq_users_email_lower` (functional index) | Two accounts differing only in case |
| `uq_cart_items_cart_product` | A cart holding the same product on two lines |
| `uq_payments_user_idempotency` | A retried charge becoming a second charge |
| `uq_payments_order_active` (**partial**) | Two live payments for one order, while still allowing retry after a failure |
| `uq_idempotency_user_endpoint_key` | A replayed POST doing the work twice |
| `ON DELETE RESTRICT` on `products.category_id` | Deleting a category orphaning its products |
| `ON DELETE SET NULL` on `order_items.product_id` | A deleted product destroying the receipt that references it |

The partial index is the one worth understanding:

```sql
CREATE UNIQUE INDEX uq_payments_order_active
    ON payments (order_id)
    WHERE status IN ('PENDING', 'SUCCEEDED');
```

A plain unique index on `order_id` would forbid retrying a declined card. No index
at all would permit a double charge. The partial index allows any number of `FAILED`
attempts and at most one live one — which is exactly the business rule.

### Deliberate denormalisation

`order_items` duplicates `product_sku`, `product_name` and `unit_price` from the
catalogue. This is not an oversight. An order is a financial record of an event that
already happened; if the line held only `product_id`, then repricing an item would
silently rewrite every past invoice, renaming it would make old receipts describe
goods nobody ordered, and deleting it would leave an order that cannot say what it
was for.

`product_id` is kept as a nullable `ON DELETE SET NULL` reference purely for
analytics. The order stays fully readable once it is gone.

### Deliberate omissions

- **No `@OneToMany` from `User` to orders.** A customer may accumulate thousands; a
  mapped collection invites someone to call `user.getOrders()` and load all of them.
  Navigation goes the other way, through repositories that always take a `userId` and
  a `Pageable`.
- **No price on `cart_items`.** A cart is an intention, not a contract. Prices are
  resolved from the catalogue at read time, so a cart left open for a week shows this
  week's prices. The snapshot happens exactly once, at checkout.
- **No `@OneToOne` from `Product` to `Inventory`.** Stock is reached through
  `InventoryRepository`, never through `product.getInventory()` — routing a
  reservation through a loaded entity would invite the read-modify-write this design
  exists to prevent.

---

## 4. Concurrency: the overselling problem

This is the centre of the project.

Five units in stock. Twenty customers check out simultaneously. Exactly five orders
must be created.

### Why the obvious code is wrong

```java
Inventory inv = repo.findById(productId).orElseThrow();   // T1 and T2 both read 1
if (inv.getAvailable() >= qty) {                          // both pass
    inv.setAvailable(inv.getAvailable() - qty);           // both write 0
}
```

Both transactions see one unit and both sell it. The window between the read and the
write is where the bug lives, and no amount of care in Java closes it — the state
being checked lives in the database and can change between the two statements.

### The solution

```sql
UPDATE inventory
   SET available = available - :quantity,
       reserved  = reserved  + :quantity
 WHERE product_id = :productId
   AND available >= :quantity
```

PostgreSQL takes a row-level lock before applying this statement. A second
transaction attempting the same row **blocks** until the first commits, then — under
`READ COMMITTED`, PostgreSQL's default — re-evaluates the `WHERE` clause against the
newly committed row rather than its original snapshot. The second transaction
therefore sees the decremented value, the predicate fails, and the statement reports
**zero rows affected**.

The check and the decrement are one statement. There is no window.

```java
int rowsAffected = inventoryRepository.reserve(productId, quantity);
if (rowsAffected == 0) {
    throw new InsufficientStockException(...);   // the database said no
}
```

`CHECK (available >= 0)` sits underneath as a backstop: even a direct `psql` session
cannot drive the figure negative.

### Why not the alternatives

| Approach | Correct? | Why not chosen |
|---|---|---|
| Optimistic locking (`@Version`) | Yes | The loser gets an exception and must retry. On a popular product under flash-sale traffic that is a retry storm. The conditional UPDATE lets contenders queue briefly and then succeed. |
| Pessimistic (`SELECT … FOR UPDATE`) | Yes | It is what the conditional UPDATE does implicitly, but as two statements, two round trips, and a lock held across application code in between. |
| `synchronized` / `ReentrantLock` | **No** | Protects one JVM. Two instances behind a load balancer share a database, not a monitor. |
| `SERIALIZABLE` isolation | Yes | Slows every other query in the application to solve a problem localised to one table. |
| Redis `DECR` | Partly | Fast, but stock then lives in two places and a cache eviction loses the truth. |

`@Version` **is** still mapped on `Inventory` — it guards ordinary administrative
edits such as a restock, where last-write-wins would silently discard a concurrent
correction. Two mechanisms for two different problems.

### Deterministic lock ordering

Two customers buying `{A, B}` and `{B, A}` would take the row locks in opposite
orders and deadlock. PostgreSQL detects the cycle and kills one transaction with
SQLSTATE `40P01` — turning a perfectly valid pair of orders into a 500.

`OrderService.checkout` sorts cart lines by ascending product id before reserving,
so the cycle cannot form. Asserted by
`InventoryConcurrencyIT.deterministicLockOrderPreventsDeadlock`, which buys the same
two products from opposite directions on twelve concurrent threads.

### Why the reservation lives inside the checkout transaction

If line three is out of stock, the exception rolls the transaction back and the
database undoes the reservations taken for lines one and two. There is no
compensating logic to write, and therefore none to get wrong.

`InventoryService.reserve` is declared `@Transactional(propagation = MANDATORY)` —
it *must* run inside a caller's transaction. A reservation that committed separately
from the order it is for could leave stock held against an order that was never
created, and `MANDATORY` turns that future mistake into an immediate, obvious
failure instead of a silent leak.

---

## 5. Idempotency

A client sends `POST /orders`, the server creates the order, and the response is
lost to a dropped connection. The client cannot tell "it never arrived" from "it
worked and I missed the answer", so it retries — and gets a second order. Retrying
is correct client behaviour; producing a duplicate is a server defect.

### Two mechanisms, for two shapes of problem

**Payments** use the `payments` table's own unique index on
`(user_id, idempotency_key)`. The payment row *is* the record of the attempt, so no
second table is needed. The header is **required**: a duplicate charge is not
something a caller gets to opt into.

**Checkout** uses the generic `idempotency_keys` table, which stores the response
body for verbatim replay. The header is **optional**: a client that omits it accepts
that a retry may create a second order — their choice, made explicit.

### Three outcomes, not two

| Situation | Response |
|---|---|
| Same key, same body, stored response exists | Replay it verbatim. 200, not 201 — nothing was created this time. |
| Same key, **different** body | 422 `IDEMPOTENCY_KEY_REUSED`. The key says "same operation" and the body says otherwise; silently replaying would swallow a genuine second request. |
| Key claimed, no response yet | 409 with a retry hint. The original is still running. Waiting would tie up a thread; proceeding would duplicate the work. |

The second row is why a SHA-256 of the request body is stored alongside the key.

### Why the claim needs its own transaction

`IdempotencyStore.claim` is `@Transactional(REQUIRES_NEW)`. Claiming a key must
become visible to *other* transactions immediately, before the work it guards has
finished — uncommitted rows are invisible under `READ COMMITTED`, so a claim inside
the caller's transaction would let a concurrent duplicate proceed.

It lives in a **separate class** from `IdempotencyService` on purpose.
`@Transactional` is implemented with a proxy, so a call from one method of a bean to
another method of the *same* bean goes straight to `this` and bypasses the proxy
entirely — the annotation would be silently ignored. Spring cannot warn about it;
the code simply would not do what it says.

### A bug this project actually had

`PaymentService` originally caught the constraint violation and then re-read the
payment to work out *which* index had fired:

```java
catch (DataIntegrityViolationException e) {
    var byKey = paymentRepository.findByUserIdAndIdempotencyKey(userId, key);  // ← 500
    ...
}
```

Once a flush fails, the Hibernate persistence context is **unusable**: the entity it
tried to insert is still in the session with a null identifier, so the next
operation triggers another flush and dies with
`org.hibernate.AssertionFailure: Entry for instance ... has a null identifier`.
Every loser of a concurrent race got a 500 instead of a 409.

The fix identifies the constraint by inspecting the driver's message — pure string
handling, touching nothing. Found by `InventoryConcurrencyIT`; every single-threaded
test passed throughout.

---

## 6. The order lifecycle

Two **independent** lifecycles, deliberately not collapsed into one enum:

```
status:          CREATED ─► CONFIRMED ─► PROCESSING ─► SHIPPED ─► DELIVERED
                    │           │             │
                    └───────────┴─────────────┴────────► CANCELLED

payment_status:  PENDING ─► SUCCEEDED | FAILED | REFUNDED
```

`CONFIRMED` + `REFUNDED` is a real and meaningful state. One enum covering both
would need a constant for every combination, and "refund a delivered order" would
become inexpressible.

The transition table lives **in the enum**, not in a service:

```java
transitions.put(CREATED,    EnumSet.of(CONFIRMED, CANCELLED));
transitions.put(CONFIRMED,  EnumSet.of(PROCESSING, CANCELLED));
transitions.put(PROCESSING, EnumSet.of(SHIPPED, CANCELLED));
transitions.put(SHIPPED,    EnumSet.of(DELIVERED));
```

Adding a state is a change to this map; no service needs auditing for a missing
branch. Cancellation stops being legal at `SHIPPED` — once the parcel has left, the
remedy is a return, not a cancellation, and they have different financial
consequences.

> A subtlety worth recording: wrapping only the outer map in `unmodifiableMap`
> leaves the `EnumSet` values mutable, so a caller could do
> `SHIPPED.allowedTransitions().add(CANCELLED)` and permanently grant themselves a
> transition. Each set is wrapped individually. Caught by a test, not by review.

### Stock across the lifecycle

| Event | `available` | `reserved` |
|---|---|---|
| Add to cart | — | — (carts reserve nothing) |
| Checkout | −qty | +qty |
| Payment succeeds | — | — (still held) |
| Payment fails | +qty | −qty (released) |
| Cancelled before shipping | +qty | −qty (released) |
| Shipped | — | −qty (consumed — the units have physically left) |

Reservations are consumed at `SHIPPED` rather than at payment, so `reserved` keeps
meaning "allocated but still on the shelf" — which is what a warehouse needs it to
mean.

---

## 7. Money

`BigDecimal` throughout, stored as `NUMERIC(19,4)`. Never `double`.

```java
double withDouble = 0.1 + 0.2;            // 0.30000000000000004
```

Across a basket that error compounds: a hundred items at 19.99 does not sum to
1999.00 in binary floating point. Both facts are asserted in `MoneyTest` rather than
claimed in a comment.

- **Storage scale 4** — so intermediate arithmetic does not lose precision.
- **Display scale 2** — rendered in responses; `74999.0000` in a body is noise.
- **`HALF_UP`** — what a person expects when rounding a half.

`line_total` is stored rather than recomputed, so the arithmetic that was actually
applied is preserved. If the rounding policy ever changes, historical totals must not
change with it.

---

## 8. Security

### Passwords

BCrypt at strength 12 — roughly 250 ms per verification, about four times the cost
of the default 10. Paid once per login; makes offline cracking of a leaked table
four times more expensive. Salted per hash automatically, so identical passwords
produce different hashes and one rainbow table cannot cover the table.

Input is capped at 72 bytes because BCrypt silently ignores anything past that: two
different 100-character passwords sharing a prefix would authenticate each other.
Rejecting is honest; silently truncating is not.

No composition rules (one upper, one digit, one symbol). NIST SP 800-63B advises
against them — they push people toward `Password1!` and add far less entropy than
length does.

### Tokens

| | Access | Refresh |
|---|---|---|
| Format | JWT (HS256) | opaque random, 256 bits |
| Lifetime | 30 minutes | 7 days |
| Storage | nowhere | SHA-256 hash in `refresh_tokens` |
| Revocable | **no** | yes |
| Verification | signature only, no DB round trip | row lookup |

Two tokens because one could not be both. The access token is short-lived *because*
it cannot be revoked; the refresh token is stored *so that* it can be.

Refresh tokens are hashed with SHA-256, not BCrypt — the opposite reasoning from
passwords, and for a good reason: a refresh token is 256 bits of CSPRNG output, so
there is no dictionary to attack and BCrypt's deliberate slowness would only add
latency to every refresh. The input distribution is different, so the right hash is
different.

Every refresh **rotates** the token. A stolen token used once makes the legitimate
client's next refresh fail, which surfaces the theft.

### Claims

```
sub  user id       iss  issuer (verified)    jti  unique id
iat  issued at     exp  expiry               typ  ACCESS | REFRESH
eml  email         rol  role
```

`typ` prevents token confusion: without it, a 7-day refresh token presented in an
`Authorization` header would be accepted as a bearer credential.

Verification is built with `verifyWith(SecretKey)`, so JJWT accepts only a MAC
algorithm. A token claiming `"alg":"none"` — or an RS256 token where the attacker
supplies the "public key" as the HMAC secret — fails signature verification rather
than being honoured. The header's `alg` never selects the strategy.

A JWT is signed, not encrypted: anyone holding it can read the payload. Nothing goes
in it that the holder should not already know, and certainly no password hash.

### Authorization

URL rules in `SecurityConfig` plus `@PreAuthorize` on `AdminController` — two
independent mechanisms, so one mistake is not enough. Admin-only reads live under
`/api/v1/admin/**` rather than alongside the public catalogue, because `GET` there
is `permitAll` and a matcher placed after it would serve an admin view to anyone.

### Data isolation

Every customer-facing repository method takes a `userId` and puts it in the `WHERE`
clause:

```java
Optional<Order> findByIdAndUserIdWithItems(Long id, Long userId);
```

A miss means "not found **or** not yours", and both answer **404**. A 403 would
confirm the id exists, turning every endpoint into an enumeration oracle. Because
the scoping is in the query rather than a check on an already-loaded row, the two
cases are genuinely indistinguishable in the code too — this is structural, not
cosmetic.

### Privilege escalation

`RegisterRequest` has no `role` field, and `User.registerCustomer` hard-codes
`ROLE_CUSTOMER`. There is nowhere for a request to put a role. A `@Null`-annotated
field would be a weaker version of the same idea, because someone would eventually
"fix" the annotation.

---

## 9. Caching

Redis, via Spring Cache. Only the catalogue is cached: read constantly, written
rarely.

| Cache | Key | TTL |
|---|---|---|
| `products` | product id | 10 min |
| `categories` | category id | 30 min |
| `categoryList` | `"active"` | 30 min |

Listings are **not** cached — parameterised by page, size, sort and four filters, so
the key space is effectively unbounded and the hit rate would be poor. Caching them
would consume memory to serve misses.

### Why each cache binds a concrete serializer

This is the subtle part, and the failure mode is invisible in testing.

One `GenericJackson2JsonRedisSerializer` for everything writes plain JSON with no
record of the type. On the way back, Jackson has a `String` and an `Object` target,
so it produces a `LinkedHashMap` — which Spring then hands to a method declared to
return `ProductResponse`, and the cast fails.

The usual answer is Jackson's default typing, which embeds a `@class` field. **It
does not work here:** the response types are records, records are implicitly final,
and `DefaultTyping.NON_FINAL` skips exactly the classes that needed it.
`EVERYTHING` would work and would also write a `@class` field into every nested
string — and embedding class names in a cache is its own hazard, since a
deserialiser that instantiates arbitrary named types has been the root of a long
line of CVEs.

So each cache binds a `Jackson2JsonRedisSerializer` to a concrete `JavaType` taken
from the configuration. Nothing in Redis can influence what class is instantiated,
and the stored JSON stays clean.

A cache **miss** silently works, because the value is returned straight from the
method. Only a **hit** goes through deserialisation — so a test that calls an
endpoint once passes while the application fails on the second request in
production. `CatalogCacheIT` calls everything twice.

### Degradation

`CacheErrorHandler` logs and continues on a Redis failure, so the request falls
through to the database. The default handler rethrows, which would turn a Redis blip
into a site-wide 500. Evict failures are logged at ERROR rather than WARN, because a
failed eviction means stale data may be served — a correctness problem, not a
performance one.

`@CacheEvict` runs **after** the method returns normally. If the transaction later
rolls back, the cache has been cleared for a change that did not happen — an extra
database read, not a wrong answer. The opposite ordering would serve stale data, so
this is the right way round. `transactionAware()` on the manager ties evictions to
commit.

---

## 10. Transactions

`open-in-view: false`. The Boot default pins a database connection to the request
thread for the entire request, **including JSON serialisation** — it exhausts the
pool under load and hides N+1 queries behind lazy loads that silently succeed in a
controller. With it off, a lazy load outside a transaction throws, which is the
correct and loud behaviour.

| Boundary | Propagation | Why |
|---|---|---|
| `OrderService.checkout` | `REQUIRED` | Reservation, order creation and cart clearing are one unit |
| `InventoryService.reserve` / `releaseAll` | **`MANDATORY`** | Must join a caller's transaction; a standalone reservation could orphan stock |
| `IdempotencyStore.claim` | **`REQUIRES_NEW`** | The claim must be visible to other transactions before the guarded work finishes |
| Reads | `readOnly = true` | Lets Hibernate skip dirty checking and the driver flag the transaction read-only |

`@Transactional` is proxy-based: a self-invocation inside the same bean bypasses it
entirely. That is why `IdempotencyStore` is a separate class from
`IdempotencyService` — the annotation there would otherwise be silently inert.

---

## 11. Error handling

Every error is RFC 9457 Problem Details, from one `@RestControllerAdvice`. Clients
branch on the stable `code`; `detail` may be reworded freely.

Exceptions thrown inside the **security filter chain** never reach a
`@RestControllerAdvice` — filters run before the `DispatcherServlet`. So
`RestAuthenticationEntryPoint` and `RestAccessDeniedHandler` emit the identical
shape by hand, and `JwtAuthenticationFilter` stashes the rejection reason on a
request attribute so the entry point can distinguish `TOKEN_EXPIRED` (refresh) from
`TOKEN_INVALID` (re-authenticate), which a bare 401 cannot.

Logging policy:

- 4xx caused by the client: WARN, **no** stack trace. Ten thousand bad logins should
  not produce ten thousand stack traces.
- 5xx: ERROR **with** the trace, keyed by the same `traceId` returned to the caller.
- The stack trace never enters the response. The caller gets an id to quote.

Rejected values in validation errors are **redacted** for any field whose name
suggests a secret, because validation bodies end up in logs, bug reports and
screenshots.

---

## 12. Observability

- **`X-Trace-Id`** on every response, pushed to the SLF4J MDC and printed in every
  log line. An inbound value is honoured so a trace can span services, but it is
  length-capped and stripped of anything outside `[A-Za-z0-9_-]` — an
  attacker-supplied value lands in log files, and a newline in it would let them
  forge entire log entries.
- The MDC is cleared in a `finally` block. It is a `ThreadLocal` and servlet
  containers pool threads; a value left behind mislabels the next request on that
  thread, which is worse than having none because it looks authoritative.
- **Actuator** — `/actuator/health/liveness` and `/readiness`. Readiness includes the
  database and Redis, so a process that is up but cannot reach its database does not
  receive traffic. Everything beyond health and info requires `ROLE_ADMIN`.
- **Micrometer/Prometheus** at `/actuator/prometheus`.

---

## 13. Trade-offs and what is missing

### Accepted costs

| Decision | What it costs |
|---|---|
| Stateless access tokens | A role change or disablement takes up to 30 minutes to take effect. Closing the window means a DB read per request — the exact thing the JWT avoids. |
| No reservation at cart time | A customer can hold items that are gone by checkout. Reserving instead would let abandoned carts make a product unbuyable. |
| `PROVIDER_ERROR` treated as failure | The charge may have landed; releasing stock risks releasing it for a successful charge. Correct fix is reconciliation, which needs a real provider. |
| Catalogue cached for 10 min | A price change can take up to 10 minutes to appear if an eviction is lost. Writes evict explicitly; the TTL is the net. |
| Single database | No horizontal write scaling. Correct at this size; sharding would be premature. |

### Deliberately not built

- **A real payment gateway** — needs merchant credentials, a public webhook endpoint
  and PCI considerations, and teaches nothing the simulation does not.
- **Shipping, tax, discounts** — `subtotal` and `total` are separate columns so there
  is somewhere for them to live, but the arithmetic is not modelled.
- **A category tree** — flat by choice. Recursive queries, cycle prevention and
  breadcrumb assembly would obscure the parts that matter.
- **A message broker** — one service, one database. Kafka here would be architecture
  for its own sake.
- **RS256 / asymmetric JWTs** — correct for a multi-service estate where verifiers
  should hold only a public key. With one service there is no third party that needs
  to verify without also being able to mint.
- **Rate limiting** — belongs at the gateway or load balancer in a real deployment,
  not in application code where it protects one instance.

### What would change first at scale

1. **Read replicas** for the catalogue, with `@Transactional(readOnly = true)`
   routed to them — the annotation is already in place.
2. **A reservation TTL** with a sweeper, so abandoned `CREATED` orders release their
   stock instead of holding it indefinitely.
3. **Reconciliation** against the payment provider for unknown outcomes.
4. **Outbox + events** if a second service ever needs to know an order was placed.
