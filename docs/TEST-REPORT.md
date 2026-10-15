# Test Report

What was executed, what it found, and what is deliberately not covered.

Every number here was produced by running the suite. Nothing is estimated.

---

## Summary

| | Tests | Failures | Errors | Skipped | Duration |
|---|---:|---:|---:|---:|---:|
| Unit (Surefire) | 365 | 0 | 0 | 0 | 8.8 s |
| Integration, security, concurrency (Failsafe) | 141 | 0 | 0 | 0 | 150.7 s |
| **Total** | **506** | **0** | **0** | **0** | **~160 s** |

Reproduce with:

```bash
./mvnw clean test                 # the 365 unit tests — no external services
./mvnw verify -Pintegration       # all 506 — needs PostgreSQL and Redis
```

### Coverage (JaCoCo)

| Metric | Covered / Total | % |
|---|---|---:|
| Instruction | 6 569 / 7 885 | **83.3%** |
| **Line** | 1 428 / 1 679 | **85.1%** |
| Branch | 297 / 451 | 65.9% |
| Method | 430 / 535 | 80.4% |
| Class | 64 / 64 | **100%** |

Targets were 70% overall and 80% for core business services. Both are met.

DTOs are excluded — they are records whose accessors, `equals` and `hashCode` are
compiler-generated, and counting them inflates the number without saying anything
about the tests. **Entities are deliberately not excluded**: `Order` carries the
status machine and the total arithmetic and `OrderStatus` carries the transition
table, which is the most logic-dense code in the project. Excluding them would be
hiding exactly what the number is meant to report.

---

## Per-class results

### Unit tests

| Class | Tests | Duration |
|---|---:|---:|
| `exception.ProblemDetailFactoryTest` | 73 | 0.1 s |
| `order.entity.OrderStatusTest` | 37 | 0.6 s |
| `order.service.OrderServiceTest` | 24 | 3.6 s |
| `auth.service.AuthServiceTest` | 23 | 0.7 s |
| `catalog.service.CategoryServiceTest` | 22 | 0.3 s |
| `cart.service.CartServiceTest` | 21 | 0.2 s |
| `catalog.service.ProductServiceTest` | 21 | 0.2 s |
| `security.JwtServiceTest` | 20 | 1.1 s |
| `common.util.MoneyTest` | 18 | 0.1 s |
| `inventory.service.InventoryServiceTest` | 18 | 0.5 s |
| `order.entity.OrderTest` | 17 | 0.2 s |
| `payment.service.PaymentServiceTest` | 17 | 0.4 s |
| `idempotency.service.IdempotencyServiceTest` | 13 | 0.3 s |
| `user.entity.UserTest` | 13 | 0.1 s |
| `common.web.TraceIdFilterTest` | 11 | 0.1 s |
| `payment.provider.SimulatedPaymentProviderTest` | 10 | 0.3 s |
| `common.util.ReferenceGeneratorTest` | 7 | 0.0 s |
| **Total** | **365** | **8.8 s** |

### Integration tests

| Class | Tests | Duration | What it exercises |
|---|---:|---:|---|
| `security.SecurityIT` | 49 | 61.5 s | 401/403/404, token forgery, injection, data isolation |
| `order.CheckoutFlowIT` | 31 | 42.0 s | Cart → checkout → payment → fulfilment, with DB assertions |
| `catalog.CatalogIT` | 28 | 16.5 s | Search, filters, pagination, catalogue and stock management |
| `auth.AuthFlowIT` | 17 | 6.6 s | Register, login, refresh rotation, logout |
| `inventory.InventoryConcurrencyIT` | 8 | 20.7 s | Overselling, deadlock, concurrent payments |
| `catalog.CatalogCacheIT` | 8 | 3.6 s | Redis round-trip, eviction, stored-JSON shape |
| **Total** | **141** | **150.7 s** | |

---

## Coverage by package

| Package | Line coverage |
|---|---:|
| `payment.provider` | 100% |
| `order.controller` | 100% |
| `common.web` | 100% |
| `cart.service` | 100% |
| `auth.controller` | 100% |
| `inventory.service` | 96.7% |
| `catalog.service` | 96.2% |
| `catalog.controller` | 95.5% |
| `security` | 94.4% |
| `common.util` | 93.9% |
| `order.service` | 92.9% |
| `admin.controller` | 89.5% |
| `cart.controller` | 88.9% |
| `payment.service` | 84.0% |
| `idempotency.service` | 83.6% |
| `config` | 83.7% |
| `order.entity` | 83.1% |
| `exception` | 74.7% |
| `payment.controller` | 60.0% |

**Core business services** — the figure the 80% target applies to:

| Service | Line coverage |
|---|---:|
| `AuthService` | 98.5% |
| `CartService` | 100% |
| `InventoryService` | 96.7% |
| `ProductService` / `CategoryService` | 96.2% |
| `OrderService` | 92.9% |
| `PaymentService` | 84.0% |
| `IdempotencyService` | 83.6% |

The lower figures are mostly logging branches and defensive paths that would require
contriving a broken database to reach. `exception` at 74.7% is the
`GlobalExceptionHandler`'s less common handlers — media-type mismatches and the like
— several of which *are* covered by `SecurityIT`; the rest are one-line delegations.

---

## Bugs the tests found

The honest value of a test suite is what it catches. These are real defects in this
codebase, each found by a specific test rather than by review.

### 1. `function lower(bytea) does not exist` — catalogue search, 500 on every call

**Found by:** running the application. Not by any test — the code had not been
exercised against PostgreSQL yet.

The product search was written the obvious way:

```sql
AND (:search IS NULL OR lower(p.name) LIKE lower(concat('%', :search, '%')))
```

Against PostgreSQL this fails with `ERROR: function lower(bytea) does not exist`
whenever `search` is null — which is the default case, so **every unfiltered
catalogue request returned 500**.

A bind parameter passed to a function has no column to take its type from, so the
server must infer one, and it resolves an untyped null inside `lower()` to `bytea`.
The numeric filters escape this because `p.price >= ?` anchors the parameter against
a typed column; `lower(?)` anchors it against nothing. Type resolution happens when
the statement is parsed, so `OR` short-circuiting at execution time does not save it.

**Fix:** build the `LIKE` pattern in Java, so the parameter sits directly beside
`LIKE` where `lower(p.name)` gives it a type. A blank search becomes `"%"` rather
than a null the server has to guess about — which also removed the per-row `concat`
and `lower`. Wildcards in user input are escaped so `50%` means a literal percent.

**Now covered by:** `ProductServiceTest` (4 tests on pattern construction and
escaping) and `CatalogIT.blankSearchReturnsEverything`.

### 2. `AssertionFailure: ... has a null identifier` — concurrent payments, 500 instead of 409

**Found by:** `InventoryConcurrencyIT.concurrentPaymentsChargeOnce`. Every
single-threaded test passed throughout.

Ten simultaneous charges against one order produced **one 201 and nine 500s**. The
unique index was working — exactly one payment row existed, so no double charge —
but every loser got a server error instead of a clean conflict.

`PaymentService` caught the constraint violation and then re-read the payment to
work out which index had fired:

```java
catch (DataIntegrityViolationException e) {
    var byKey = paymentRepository.findByUserIdAndIdempotencyKey(userId, key);  // ← dies here
    ...
}
```

Once a flush fails, the Hibernate persistence context is unusable. The entity it
tried to insert is still in the session with a null identifier, so the next
operation triggers another flush and dies with
`org.hibernate.AssertionFailure: Entry for instance of 'Payment' has a null
identifier (this can happen if the session is flushed after an exception occurs)`.

**Fix:** identify the constraint from the driver's message instead — pure string
handling that touches nothing. `uq_payments_user_idempotency` → 409
`CONCURRENT_MODIFICATION` ("retry shortly"); `uq_payments_order_active` → 409
`PAYMENT_ALREADY_EXISTS`.

**Now covered by:** the test that found it, plus
`InventoryConcurrencyIT.concurrentRetriesOfOneKey` and two `PaymentServiceTest`
cases.

### 3. The order transition table was mutable

**Found by:** `OrderStatusTest.transitionSetIsImmutable`, written as a routine
immutability check.

`OrderStatus` wrapped its transition map in `Collections.unmodifiableMap` — which
stops `put` and `remove` on the map, and hands out the *same mutable `EnumSet`
instances*. A caller could do:

```java
OrderStatus.SHIPPED.allowedTransitions().add(OrderStatus.CANCELLED);
```

and permanently grant themselves a transition for the lifetime of the JVM. The map
being read-only said nothing about what was inside it.

**Fix:** `transitions.replaceAll((s, allowed) -> Collections.unmodifiableSet(allowed))`
before wrapping the map.

### 4. HEAD returned 401 on public endpoints

**Found by:** `curl -I` during manual verification.

A Spring Security matcher for `HttpMethod.GET` does not match `HEAD`, so
`HEAD /api/v1/products` fell through to `anyRequest().authenticated()` and returned
401 — for a resource that is deliberately public. A caching proxy, an uptime monitor
or a link checker would all have seen that.

**Fix:** an explicit `HttpMethod.HEAD` rule alongside the `GET` one.
**Now covered by:** `SecurityIT.headIsAllowed`.

### 5. The integration suite poisoned its own fixture

**Found by:** running `CheckoutFlowIT` a second time.

The price-snapshot test repriced the seeded laptop to 1.00 to prove that order lines
keep their purchase-time price. `resetDatabase()` restored stock and `active` flags
but not prices, so the product stayed at 1.00 — and the test, which asserts the
snapshot differs from the new price, failed on every run after the first.

This is the "green on CI, red on my machine" failure mode in miniature: the suite
passed once and was broken thereafter.

**Fix:** the seeded catalogue is now read-only. A test that needs to mutate a product
creates its own with a `TEST-` SKU prefix, which `resetDatabase()` deletes.
`assertSeedCatalogueIsPristine()` runs before every test so a future violation fails
loudly at the start of the *next* test rather than silently poisoning the run.

Restoring seeded values instead would have meant duplicating V9's data in a second
place that could drift from the migration.

### 6. Startup broke after editing a comment in an applied migration

**Found by:** restarting the application after a documentation tidy-up.

A `sed` repointing a stale doc reference changed a **comment** in
`V3__create_categories_and_products.sql`. Flyway checksums the whole file, so the
next startup failed:

```
FlywayValidateException: Migration checksum mismatch for migration version 3
-> Applied to database : 1245547696
-> Resolved locally    : -801750002
```

An applied migration is immutable, comments included. Resolved with
`./mvnw flyway:repair` against both local databases — the documented remedy, and
appropriate here because the migration had only ever been applied to two throwaway
local databases and the change was semantically nil. In a released system the change
would have gone in a new migration instead.

Recorded in the README's operational notes, because it is the kind of thing worth
knowing before it happens to you.

---

## What the tests actually assert

### The concurrency guarantee

`InventoryConcurrencyIT` is the test that matters most, and it is the one a mock
cannot stand in for. `InventoryServiceTest` asserts that the service treats a
zero-row UPDATE as insufficient stock — correct for a unit test, and silent on
whether the UPDATE is actually atomic. Atomicity is a property of PostgreSQL's row
locking under `READ COMMITTED`, so demonstrating it needs PostgreSQL and needs
genuine contention.

Threads are released simultaneously by a `CyclicBarrier`. Threads started in a loop
do not run at the same time — the first may finish before the last begins, and the
test would pass without ever creating the race it claims to test.

| Test | Setup | Asserts |
|---|---|---|
| `conditionalUpdateNeverOversells` | 20 threads, 1 unit each, 5 in stock | Exactly 5 reservations granted, 15 refused, `available = 0`, `reserved = 5` |
| `neverGoesNegative` | 20 threads, 2 units each, 5 in stock | Exactly 2 succeed; 1 unit left over, never negative |
| `concurrentReleasesDoNotInflateStock` | 5 reserved, 20 threads releasing 1 each | Stock returns to exactly 5, not above |
| `concurrentCheckoutsNeverOversell` | 20 HTTP checkouts through the full stack | 5 × 201, 15 × 409, **zero 500s**, order lines sum to 5 |
| `deterministicLockOrderPreventsDeadlock` | 12 customers buying {A,B} and {B,A} | All 201 — a deadlock would appear as a 500 |
| `concurrentPaymentsChargeOnce` | 10 charges, distinct keys, one order | Exactly one 201, rest clean 409s, one payment row |
| `concurrentRetriesOfOneKey` | 10 charges, *same* key | Exactly one 201, no 500s, one payment row |
| `checkConstraintIsTheBackstop` | Direct `UPDATE ... SET available = -1` | Refused by `ck_inventory_available_non_negative` |

The "zero 500s" assertions are the sharpest ones. A deadlock, a constraint violation
escaping as a server error, or a poisoned persistence context all surface as a 500 —
which is exactly how bug #2 was caught.

### Security

`SecurityIT` — 49 tests probing what an attacker would:

- 401 for every protected endpoint without a token, in the standard Problem Details
  shape, with `WWW-Authenticate` per RFC 6750.
- 403 (not 401) for an authenticated customer reaching an admin endpoint — and the
  two are asserted to be distinguishable by both status and `code`.
- **404 for another customer's order**, byte-comparable with the 404 for an id that
  does not exist, so the API cannot be used as an existence oracle.
- A tampered token, an `alg:none` forgery claiming `ROLE_ADMIN`, and a refresh token
  presented as a bearer credential — all rejected.
- SQL metacharacters in the search parameter matched as literal text, with the
  catalogue asserted intact afterwards and no password hash in any response.
- Carts resolved from the verified principal, with no cart id in any path to tamper
  with.
- No stack trace, SQL, JDBC URL or internal package name in any error body.

### Money

`MoneyTest` demonstrates the bug `BigDecimal` exists to prevent, rather than
asserting it in a comment:

```java
double withDouble = 0.1 + 0.2;
assertThat(withDouble).isEqualTo(0.30000000000000004);   // not 0.3
```

and shows the error compounding: a hundred items at 19.99 does not sum to 1999.00 in
binary floating point, but does with `BigDecimal`.

Every assertion uses `isEqualByComparingTo` rather than `isEqualTo`, because
`BigDecimal.equals` compares scale as well as value — `10.00` and `10.0000` are not
equal, a distinction that matters for storage and never for arithmetic.

### Caching

Every `CatalogCacheIT` test calls each endpoint **twice**. A cache miss returns the
value straight from the method, so nothing is deserialised and everything appears to
work; only the second call reads from Redis. A suite that called each endpoint once
would have passed while the application failed on the second request in production.

The tests also assert that the stored JSON contains no `@class` markers and no
package names — embedding class names in a cache is its own hazard, since a
deserialiser that instantiates arbitrary named types has been the root of a long line
of CVEs.

---

## Test design decisions

**Integration tests are not `@Transactional`.** The usual trick — annotate the test
class and let Spring roll back — is wrong here, because the behaviour under test *is*
transactional. An outer transaction would hide the services' own boundaries, swallow
commit-time constraint violations, make `REQUIRES_NEW` in `IdempotencyStore`
silently inert, and make the concurrency tests meaningless, since a second thread
cannot see an uncommitted row. These tests commit for real and clean up explicitly.

**MockMvc rather than a real port.** It exercises the whole web layer — filter chain,
dispatcher, argument resolution, validation, exception handling, JSON serialisation
— without binding a socket. A random port adds a client, an HTTP connection and a
source of flakiness for nothing.

> One consequence worth knowing: `springSecurity()` registers only the security
> filter chain. Ordinary `Filter` beans are registered by Boot's servlet container
> setup, which MockMvc does not run — so `TraceIdFilter` has to be added by hand or
> `X-Trace-Id` is missing in tests while working perfectly in production. That
> discrepancy surfaced as a failing assertion and was verified against the running
> application before being "fixed" in the right place: the test setup, not the filter.

**Expiry is tested by moving an injected `Clock`, never by sleeping.** A test that
slept for the token lifetime would take thirty minutes; one that slept for a
shortened lifetime is a race waiting to flake on a loaded CI machine.

**BCrypt runs at strength 4 in tests, 12 in production.** The cost is the point in
production and only slows a suite down.

**Timing assertions are structural, not wall-clock.** The login timing-attack
defence is verified by asserting that `PasswordEncoder.matches` is *called* on the
unknown-email path, not by measuring elapsed time — a stopwatch assertion flakes on
a loaded CI machine.

---

## What is not covered, and why

Stated so the gaps read as decisions.

| Gap | Reason |
|---|---|
| **Testcontainers tests** | Tagged `testcontainers` and excluded by default. The development machine has no usable Docker daemon (socket root-owned, user not in the `docker` group, rootless blocked by missing `uidmap`), so they have **never been executed here**. They run in CI, which has Docker. This is stated plainly rather than implied by an absent tag. |
| **Load / soak testing** | Needs a representative dataset and sustained traffic. The concurrency tests prove correctness under contention, not throughput under load. |
| **A real payment gateway** | There isn't one. The provider is simulated by design. |
| **Branch coverage below 70%** | 65.9%. The uncovered branches are mostly logging guards, null-defence on paths the type system already constrains, and `CacheErrorHandler` callbacks that need a Redis outage mid-request to reach. Chasing them would mean tests that assert on log output. |
| **`payment.controller` at 60%** | The controller is six lines of delegation plus status selection; both branches of the replay/created decision *are* covered by `CheckoutFlowIT`. The missing lines are OpenAPI annotation attributes. |
| **Multi-instance behaviour** | Every guarantee here is enforced by the database rather than by JVM state, which is what makes it survive multiple instances — but no test runs two application processes. |
| **Flyway rollback** | Flyway Community has no `undo`. The forward-only discipline is the design; rolling back means a new migration. |

---

## Running it yourself

```bash
# Unit only — no external services
./mvnw clean test

# Everything — needs PostgreSQL and Redis
./scripts/migrate.sh test        # once, to prepare ecommerce_test_db
./mvnw verify -Pintegration

# A single class
./mvnw verify -Pintegration -Dit.test=InventoryConcurrencyIT -Dtest=MoneyTest

# With Testcontainers, if Docker is available
./mvnw verify -Pcontainers
```

Reports land in `target/surefire-reports/`, `target/failsafe-reports/` and
`target/site/jacoco/index.html`.

The integration tests need `ecommerce_test_db` to exist and be migrated. They
`DELETE` between test classes, which is exactly why they point at a separate
database — running them against `ecommerce_db` would destroy work in progress.
