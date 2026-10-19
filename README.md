# E-commerce Backend

A production-shaped REST API for a small online shop: catalogue, cart, checkout,
inventory and simulated payments. Java 21, Spring Boot 3.5, PostgreSQL 16, Redis 7,
built with Maven.

**No real money moves anywhere in this application.** The payment provider is a
deterministic simulator and no card details exist in the codebase. Everything
*around* the provider — the idempotency key and the unique index behind it, the
stock reservation that must be released when a charge fails, the order state
machine — is real, and that is where the interesting problems are.

---

## What this project is actually about

Three problems, each solved in a way that would survive a second application
instance and a thousand concurrent customers:

**1. Overselling is impossible.** Five units in stock, twenty simultaneous
checkouts: exactly five orders are created and fifteen customers get a clean 409.
Not by locking in Java — that protects one JVM, and production has several — but by
a single conditional SQL statement:

```sql
UPDATE inventory
   SET available = available - :qty
 WHERE product_id = :id
   AND available >= :qty
```

The check and the decrement are one statement, so there is no window between them.
PostgreSQL re-evaluates the `WHERE` clause against the freshly committed row, the
loser's statement affects zero rows, and the service turns that zero into
`INSUFFICIENT_STOCK`. A `CHECK (available >= 0)` constraint sits underneath as a
backstop. [Proved with real threads against real PostgreSQL.](src/test/java/com/bhavesh/ecommerce/inventory/InventoryConcurrencyIT.java)

**2. A retried payment charges once.** The client sends an `Idempotency-Key`; a
unique index on `(user_id, idempotency_key)` decides the race, and the original
outcome is replayed. A "have I seen this key?" check in Java is a read followed by a
write, and two concurrent retries both pass it — the index is the guarantee, the
check is just an optimisation.

**3. A receipt does not change when the catalogue does.** `order_items` snapshots
the SKU, name and unit price at purchase time. Repricing a product afterwards
rewrites nothing. This looks like a normalisation error and is the opposite: an
order is a financial record of an event that already happened.

---

## Quick start

### Prerequisites

| | Version | Notes |
|---|---|---|
| Java | 21 | `java -version` |
| PostgreSQL | 16 | Running locally, or via `compose.yaml` |
| Redis | 7 | Optional — the app degrades to no caching without it |
| Maven | — | Not needed; use the bundled `./mvnw` wrapper |

### 1. Provision the database

```bash
./scripts/provision-db.sh
```

Creates the `ecommerce_app` role (`NOSUPERUSER NOCREATEDB NOCREATEROLE`), the
`ecommerce_db` and `ecommerce_test_db` databases, generates a password and a JWT
signing key, and writes them to `.env` with mode `0600`.

The script refuses to touch anything outside those three names, and passes SQL on
stdin rather than as command-line arguments — anything on a command line is visible
to every user on the machine via `ps`.

`.env` is git-ignored and contains real secrets. `.env.example` documents every
variable and is safe to read.

### 2. Apply the migrations

```bash
./scripts/migrate.sh         # ecommerce_db
./scripts/migrate.sh test    # ecommerce_test_db — needed for the integration tests
```

### 3. Run

```bash
./mvnw spring-boot:run
```

Then:

- **API docs** — <http://localhost:8080/swagger-ui.html>
- **Health** — <http://localhost:8080/actuator/health>
- **Catalogue** — <http://localhost:8080/api/v1/products> (public, no token needed)

> **Running from an IDE?** `application.yml` imports `./.env`, and that path
> resolves against the **working directory**, not the jar. If the run
> configuration's working directory is not the project root, nothing resolves and
> startup fails on the JWT key-length check — with a message that says exactly
> this, because it is the most common way to trip over it.

### 4. Or bring the whole stack up

```bash
docker compose up --build
```

> `docker compose down` stops the stack and keeps your data.
> `docker compose down -v` **deletes the database volume.**

---

## Commands

```bash
./mvnw clean test                 # 365 unit tests, no external services needed
./mvnw verify -Pintegration       # + 141 integration/security/concurrency tests
                                  #   (needs PostgreSQL and Redis)
./mvnw verify -Pcontainers        # + Testcontainers-backed tests (needs Docker)
./mvnw clean package              # build the jar
./mvnw spring-boot:run            # run
```

Coverage lands in `target/site/jacoco/index.html` after `verify`.

---

## API

38 endpoints. Full detail in [docs/API-TESTING.md](docs/API-TESTING.md), which is a
copy-pasteable `curl` walkthrough of the whole customer journey.

| Area | Endpoints | Access |
|---|---|---|
| Authentication | `/api/v1/auth/*` | Public (register, login, refresh, logout) |
| Catalogue | `GET /api/v1/products`, `/api/v1/categories` | Public |
| Catalogue management | `POST`/`PUT`/`PATCH`/`DELETE` on the same paths | `ROLE_ADMIN` |
| Cart | `/api/v1/cart/**` | Authenticated customer |
| Orders | `/api/v1/orders/**` | Authenticated customer, scoped to the caller |
| Payments | `/api/v1/payments/**` | Authenticated customer |
| Administration | `/api/v1/admin/**` | `ROLE_ADMIN` |

### Authentication

`POST /api/v1/auth/login` returns two tokens with deliberately different jobs:

- an **access token** — a short-lived (30 min) JWT, self-contained so the filter
  chain verifies it with no database round trip, and short-lived *because* it cannot
  be revoked;
- a **refresh token** — long-lived (7 days) but opaque and stored as a SHA-256 hash,
  so it *can* be revoked. That is what makes logout real rather than advisory.

Every refresh rotates the refresh token. If one is stolen and used, the legitimate
client's next refresh presents a token that is already revoked and fails — so the
theft surfaces instead of going unnoticed for a week.

The honest cost, stated plainly: **disabling an account does not take effect until
the current access token expires.** Closing that window would mean loading the user
on every request, which is exactly the round trip the JWT exists to avoid. Refresh
re-reads the user, so the window is bounded by the access-token TTL.

### Errors

Every error is [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457):

```json
{
  "type":      "https://api.ecommerce.local/problems/insufficient-stock",
  "title":     "Insufficient Stock",
  "status":    409,
  "detail":    "Only 3 unit(s) of 'UltraBook 14 Pro' are available; 10 were requested",
  "instance":  "/api/v1/orders",
  "code":      "INSUFFICIENT_STOCK",
  "traceId":   "4ca5b243493b4a3a",
  "timestamp": "2026-10-14T16:43:41.440Z",
  "productId": 5, "requested": 10, "available": 3
}
```

Branch on `code`, which is stable; `detail` may be reworded freely. `traceId` is
also returned in the `X-Trace-Id` header and appears in the server log, so a
user-reported failure can be found without exposing a stack trace.

---

## Design decisions worth defending

Each of these was a choice between workable options. The reasoning lives next to the
code; [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) collects it.

| Decision | Instead of | Why |
|---|---|---|
| Conditional atomic `UPDATE` for stock | optimistic locking, `SELECT … FOR UPDATE`, `synchronized` | The loser queues briefly and succeeds, rather than retrying or deadlocking. A JVM lock protects one process; production has several. |
| Unique indexes for idempotency | application-level "have I seen this?" | A read-then-write is a race. The index is evaluated by the database at insert time. |
| Price snapshots on order lines | joining back to `products` | An order is a record of a past event. Repricing must not rewrite history. |
| Flyway with `ddl-auto=validate` | `ddl-auto=update` | Migrations are the schema's source of truth; `validate` makes the app refuse to start if an entity drifts from them. |
| `BigDecimal` / `NUMERIC(19,4)` | `double` | `0.1 + 0.2 != 0.3` in binary floating point, and the error compounds across a basket. [Demonstrated in a test.](src/test/java/com/bhavesh/ecommerce/common/util/MoneyTest.java) |
| 404 for another customer's order | 403 | A 403 confirms the id exists, turning every endpoint into an enumeration oracle. |
| Per-cache concrete serializers | Jackson default typing | Records are implicitly final, so `DefaultTyping.NON_FINAL` skips exactly the classes that needed it. |
| `open-in-view: false` | the Boot default | OSIV pins a connection to the request thread for the whole request and hides N+1 queries behind lazy loads that silently succeed. |

---

## Security

- **Passwords** — BCrypt at strength 12 (~250 ms per verification), salted per hash.
  Input capped at 72 bytes because BCrypt silently ignores anything past that, and
  silently truncating a credential is worse than rejecting it.
- **Login timing** — an unknown email still performs a password comparison against a
  dummy hash, so a miss and a hit cost the same. Returning early would be a timing
  oracle for enumerating registered addresses.
- **Privilege escalation** — `RegisterRequest` has no `role` field and
  `User.registerCustomer` hard-codes `ROLE_CUSTOMER`. There is nowhere for a request
  to put one; this is structural, not a validation rule someone could relax.
- **Token confusion** — a `typ` claim distinguishes access from refresh tokens, so a
  7-day refresh token presented as a bearer credential is rejected rather than
  becoming a 7-day API key.
- **Algorithm confusion** — verification is built with `verifyWith(SecretKey)`, so
  JJWT accepts only a MAC algorithm; `"alg":"none"` fails signature verification
  rather than being honoured.
- **SQL injection** — every query is a bound parameter; the one user-supplied `LIKE`
  pattern is escaped, and sort fields are whitelisted.
- **Secrets** — nothing is committed. Migrations seed **zero users**. The
  development administrator is bootstrapped from `.env` and is disabled
  unconditionally under the `prod` profile.

All of the above is asserted in
[`SecurityIT`](src/test/java/com/bhavesh/ecommerce/security/SecurityIT.java) — 49
tests that probe what an attacker would.

---

## Testing

| Suite | Count | Needs |
|---|---:|---|
| Unit | 365 | nothing |
| Integration, security, concurrency | 141 | PostgreSQL, Redis |
| **Total** | **506** | |

Coverage: **84.9% line**, 82.9% instruction, 100% of classes. Every core business
service is at or above 83.6%; most are above 92%.

The concurrency tests are the ones that matter. A mock cannot demonstrate
atomicity — that is a property of PostgreSQL's row locking — so those tests use real
threads released simultaneously by a `CyclicBarrier` against a real database.

Full breakdown, including the bugs the tests found, in
[docs/TEST-REPORT.md](docs/TEST-REPORT.md).

---

## Documentation

| Document | What it covers |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layering, schema, concurrency strategy, caching, trade-offs and what was left out |
| [API-TESTING.md](docs/API-TESTING.md) | A copy-pasteable `curl` walkthrough, including the failure paths |
| [TEST-REPORT.md](docs/TEST-REPORT.md) | What was executed, what it found, and what is deliberately not covered |

---

## Project layout

```
src/main/java/com/bhavesh/ecommerce/
├── admin/          Administrator-only endpoints (inventory, fulfilment)
├── auth/           Registration, login, refresh, logout
├── cart/           Cart aggregate
├── catalog/        Products and categories
├── common/         Money, paging, auditing, the trace-id filter
├── config/         Security, caching, OpenAPI, properties, admin bootstrap
├── exception/      Error codes and the RFC 9457 handler
├── idempotency/    Replay protection for non-idempotent POSTs
├── inventory/      Stock — the concurrency-critical package
├── order/          Checkout and the order lifecycle
├── payment/        Simulated provider and payment orchestration
├── security/       JWT issuing/verification, filter, principal
└── user/           User entity and repository

src/main/resources/db/migration/   V1–V9, the schema's source of truth
scripts/                           provision-db.sh, migrate.sh
```

---

## Operational notes

**Migrations are immutable once applied.** Flyway checksums every file, including
its comments — editing an applied migration makes the application refuse to start
with a checksum mismatch. During development this project hit exactly that by
editing a comment; the remedy is `./mvnw flyway:repair`, and the lesson is that an
applied migration is frozen. Changes go in a new file.

**Redis is optional.** A cache failure is logged and the request falls through to
the database; a cache is an optimisation and an outage in it should cost latency,
not availability. Set `ECOMMERCE_CACHE_ENABLED=false` to turn it off entirely.

**Health probes** — `/actuator/health/liveness` and `/actuator/health/readiness`.
Readiness includes the database and Redis, so a process that is up but cannot reach
its database does not receive traffic.

---

## What this project deliberately does not do

Stated so the gaps read as decisions rather than oversights:

- **No real payment gateway.** That needs merchant credentials, a public webhook
  endpoint and PCI considerations, and would teach nothing the simulation does not.
- **No shipping, tax or discounts.** `subtotal` and `total` are separate columns so
  there is somewhere for them to go, but the arithmetic is not modelled.
- **No category tree.** Flat by choice; recursion, cycle prevention and breadcrumbs
  would obscure the parts that matter here.
- **No distributed tracing or message broker.** One service, one database — adding
  Kafka would be architecture for its own sake.
- **No reconciliation for an unknown payment outcome.** When the simulated provider
  reports `PROVIDER_ERROR` the charge may or may not have landed; this application
  treats it as a failure and releases the stock, and records that choice in
  `payments.failure_reason` rather than resolving it silently. A real system would
  reconcile against the provider.

---

**Author** — Bhavesh Bokde
**Licence** — MIT
