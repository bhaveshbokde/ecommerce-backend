# API Testing Guide

A copy-pasteable walkthrough of the whole customer journey, including the failure
paths — which are the interesting half.

**Every response in this document was captured from a running instance.** Nothing
here is illustrative; the order numbers, payment references, trace ids and
timestamps are real ones from the session that produced this file. Your ids will
differ.

---

## Contents

1. [Setup](#1-setup)
2. [Browsing the catalogue (no token needed)](#2-browsing-the-catalogue-no-token-needed)
3. [Registration and login](#3-registration-and-login)
4. [The cart](#4-the-cart)
5. [Checkout](#5-checkout)
6. [Payment — the failure path first](#6-payment--the-failure-path-first)
7. [Payment — success](#7-payment--success)
8. [Insufficient stock](#8-insufficient-stock)
9. [Administration](#9-administration)
10. [Security probes](#10-security-probes)
11. [Proving the concurrency guarantee by hand](#11-proving-the-concurrency-guarantee-by-hand)
12. [Error reference](#12-error-reference)

---

## 1. Setup

```bash
./scripts/provision-db.sh      # once
./scripts/migrate.sh           # once
./mvnw spring-boot:run
```

Then, in another shell:

```bash
export B=http://localhost:8080/api/v1
```

Everything below pipes through `python3 -m json.tool` for readability. Drop it if
you prefer raw output, or use `jq` if you have it.

> **Prefer a browser?** <http://localhost:8080/swagger-ui.html> has an *Authorize*
> button — paste the `accessToken` from step 3 and every request carries it.

---

## 2. Browsing the catalogue (no token needed)

```bash
curl -s "$B/products?size=2&sort=price&direction=ASC" | python3 -m json.tool
```

```json
{
  "content": [
    {
      "id": 6,
      "sku": "BOOK-FICT-001",
      "name": "The Silent Harbour",
      "description": "Literary fiction, 384 pages",
      "price": 599.00,
      "categoryId": 2,
      "categoryName": "Books",
      "active": true,
      "inStock": true
    },
    {
      "id": 18,
      "sku": "CLTH-TSHIRT-001",
      "name": "Everyday Cotton T-Shirt",
      "price": 899.00,
      "categoryId": 5,
      "categoryName": "Clothing",
      "active": true,
      "inStock": true
    }
  ],
  "page": 0, "size": 2,
  "totalElements": 18, "totalPages": 9,
  "first": true, "last": false, "empty": false
}
```

Note `inStock` is a **boolean**, not a count. An exact figure on a public endpoint is
a free inventory feed for a competitor, and it changes between the response and the
click anyway. Administrators get the number from `/admin/inventory/{id}`.

### Filtering

```bash
curl -s "$B/products?search=ultra"                        # substring, case-insensitive
curl -s "$B/products?minPrice=1000&maxPrice=5000"         # inclusive both ends
curl -s "$B/products?categoryId=1&size=50"
curl -s "$B/products?sort=price&direction=DESC"
curl -s "$B/categories"
```

All filters are optional and combine. The search term is a bound parameter with
`LIKE` wildcards escaped, so `%` means a literal percent sign:

```bash
curl -s "$B/products?search=%25" | python3 -c "import sys,json;print(json.load(sys.stdin)['totalElements'])"
# 0  — not 18
```

An inverted range is rejected rather than silently returning nothing:

```bash
curl -s "$B/products?minPrice=5000&maxPrice=1000" | python3 -m json.tool
# 400  "code": "INVALID_PARAMETER"
```

---

## 3. Registration and login

```bash
curl -s -X POST "$B/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"email":"priya@example.com","password":"correct-horse-battery","fullName":"Priya Sharma"}' \
  | python3 -m json.tool
```

```json
{
  "accessToken":  "eyJhbGciOiJIUzI1NiJ9.eyJzdWI...",
  "refreshToken": "uFLL1C93P4P8...",
  "tokenType": "Bearer",
  "expiresInSeconds": 1800,
  "user": {
    "id": 25,
    "email": "priya@example.com",
    "fullName": "Priya Sharma",
    "role": "ROLE_CUSTOMER",
    "enabled": true,
    "createdAt": "2026-10-15T04:57:08.669116690Z"
  }
}
```

Capture the token for the rest of the walkthrough:

```bash
export TOKEN=$(curl -s -X POST "$B/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"priya@example.com","password":"correct-horse-battery"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

export AUTH="Authorization: Bearer $TOKEN"
```

### Things worth trying here

**You cannot register yourself as an administrator.** There is no `role` field on
the request at all, so the value is simply ignored during binding:

```bash
curl -s -X POST "$B/auth/register" -H 'Content-Type: application/json' \
  -d '{"email":"sneaky@example.com","password":"password-abcdef","fullName":"Sneaky",
       "role":"ROLE_ADMIN","authorities":["ROLE_ADMIN"]}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['user']['role'])"
# ROLE_CUSTOMER
```

**A wrong password and an unknown address are indistinguishable** — same status,
same body, and the same time taken, because an unknown email still performs a
password comparison against a dummy hash:

```bash
curl -s -X POST "$B/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"priya@example.com","password":"wrong"}' | python3 -m json.tool
curl -s -X POST "$B/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"nobody@example.com","password":"wrong"}' | python3 -m json.tool
# both: 401  "code": "INVALID_CREDENTIALS"
```

**Rotation.** Refresh once and the old token is dead:

```bash
export REFRESH=$(curl -s -X POST "$B/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"priya@example.com","password":"correct-horse-battery"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['refreshToken'])")

curl -s -X POST "$B/auth/refresh" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}" -o /dev/null -w "first use:  %{http_code}\n"

curl -s -X POST "$B/auth/refresh" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}" -o /dev/null -w "reuse:      %{http_code}\n"
# first use:  200
# reuse:      401   ← this is how a stolen token surfaces
```

---

## 4. The cart

```bash
curl -s -X POST "$B/cart/items" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"productId":5,"quantity":2}' > /dev/null

curl -s -X POST "$B/cart/items" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"productId":1,"quantity":1}' | python3 -m json.tool
```

```json
{
  "cartId": 22,
  "items": [
    {
      "itemId": 24, "productId": 5,
      "sku": "ELEC-LAPTOP-001", "name": "UltraBook 14 Pro",
      "unitPrice": 89999.00, "quantity": 2, "lineTotal": 179998.00,
      "available": 5, "purchasable": true
    },
    {
      "itemId": 25, "productId": 1,
      "sku": "ELEC-KBD-001", "name": "TactileOne Keyboard",
      "unitPrice": 6499.00, "quantity": 1, "lineTotal": 6499.00,
      "available": 50, "purchasable": true
    }
  ],
  "totalQuantity": 3,
  "subtotal": 186497.00,
  "currency": "INR",
  "checkoutBlocked": false
}
```

Three things to notice:

- **Prices are resolved at read time.** `cart_items` stores no price, so a cart left
  open for a week shows this week's prices. The snapshot happens once, at checkout.
- **Adding the same product again merges the line** rather than creating a second
  one — `uq_cart_items_cart_product` would reject that.
- **`available` is advisory.** Nothing is reserved yet. Reserving at cart time would
  let a handful of abandoned carts make a product unbuyable; the authoritative check
  is the atomic UPDATE at checkout.

```bash
curl -s -X PATCH "$B/cart/items/5" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"quantity":3}'                        # absolute, so a retry is safe
curl -s -X DELETE "$B/cart/items/1" -H "$AUTH"
curl -s -X DELETE "$B/cart" -H "$AUTH"       # empty it; idempotent
```

---

## 5. Checkout

```bash
curl -s -X POST "$B/orders" -H "$AUTH" -H 'Idempotency-Key: demo-checkout-1' \
  | python3 -m json.tool
```

```json
{
  "id": 8,
  "orderNumber": "ORD-20261015-8F7FCF",
  "status": "CREATED",
  "paymentStatus": "PENDING",
  "subtotal": 186497.00,
  "total": 186497.00,
  "currency": "INR",
  "items": [
    { "productId": 1, "sku": "ELEC-KBD-001",    "name": "TactileOne Keyboard",
      "unitPrice": 6499.00,  "quantity": 1, "lineTotal": 6499.00 },
    { "productId": 5, "sku": "ELEC-LAPTOP-001", "name": "UltraBook 14 Pro",
      "unitPrice": 89999.00, "quantity": 2, "lineTotal": 179998.00 }
  ],
  "cancellable": true,
  "createdAt": "2026-10-15T05:05:31.452068383Z"
}
```

> The lines come back in ascending `productId` — 1 before 5, although the laptop was
> added to the cart first. That is the deterministic lock ordering that prevents
> deadlock between two customers buying the same two products in opposite orders.

### Confirm the stock actually moved

```bash
psql -h 127.0.0.1 -U ecommerce_app -d ecommerce_db -c \
  "SELECT p.sku, i.available, i.reserved
     FROM inventory i JOIN products p ON p.id = i.product_id
    WHERE p.id IN (1,5) ORDER BY p.id;"
```

```
      sku        | available | reserved
-----------------+-----------+----------
 ELEC-KBD-001    |        49 |        1
 ELEC-LAPTOP-001 |         3 |        2
```

The cart is now empty, and the units are held — not sold.

### The idempotency key

Send the same request again:

```bash
curl -s -X POST "$B/orders" -H "$AUTH" -H 'Idempotency-Key: demo-checkout-1' \
  -o /dev/null -w "%{http_code}\n"
# 200   ← not 201. Nothing was created this time.
```

The body is byte-identical to the first response, and `SELECT COUNT(*) FROM orders`
is still 1. Omit the header and a retry creates a second order — that is the
client's choice to make, and it is explicit.

---

## 6. Payment — the failure path first

The compensation is the interesting half, so start there.

```bash
curl -s -X POST "$B/payments" -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-pay-1' \
  -d '{"orderId":8,"simulateOutcome":"DECLINE"}' | python3 -m json.tool
```

```json
{
  "id": 3,
  "paymentReference": "PAY-20261015-48CE42",
  "orderId": 8,
  "status": "FAILED",
  "amount": 186497.00,
  "currency": "INR",
  "failureReason": "Card declined by issuer",
  "completedAt": "2026-10-15T05:05:46.478078742Z"
}
```

**HTTP 201 with `"status": "FAILED"`** — and that is deliberate. The HTTP status
describes the API call: an attempt was created and processed. The `status` field
describes the money. A declined card is not an API error; the request was valid and
the answer was no. It also could not be implemented honestly any other way —
throwing to produce a 402 would roll back the very transaction that records the
failure and releases the stock.

### The stock came back

```
      sku        | available | reserved
-----------------+-----------+----------
 ELEC-KBD-001    |        50 |        0
 ELEC-LAPTOP-001 |         5 |        0
```

```
order ORD-20261015-8F7FCF  status=CREATED  payment=FAILED
```

The order stays `CREATED`, so the customer can try another instrument. The release
and the `FAILED` payment row are written in the same transaction — there is no state
where one happened and the other did not.

### Retrying the same key does not charge again

```bash
curl -s -X POST "$B/payments" -H "$AUTH" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-pay-1' \
  -d '{"orderId":8,"simulateOutcome":"SUCCESS"}' -w "\nHTTP %{http_code}\n"
```

```
  status: FAILED   reference: PAY-20261015-48CE42
HTTP 200
```

It asked for `SUCCESS`; the stored `FAILED` won, the same reference came back, and
the response is 200 rather than 201. `SELECT COUNT(*) FROM payments` is still 1, and
the stock was not released a second time.

### Other outcomes

```bash
# Simulates an unreachable provider: the answer is unknown.
-d '{"orderId":8,"simulateOutcome":"PROVIDER_ERROR"}'
```

Treated as a failure and the stock is released — which risks releasing stock for a
charge that actually landed. The correct fix is reconciliation against the provider,
which needs a provider to reconcile against; the choice is recorded in
`payments.failure_reason` rather than resolved silently.

> `simulateOutcome` is a **test hook** and is ignored under the `prod` profile, so no
> request can talk a production deployment into declining or succeeding on demand.

---

## 7. Payment — success

The decline released the stock, so the order needs re-checking-out:

```bash
curl -s -X POST "$B/cart/items" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"productId":5,"quantity":1}' > /dev/null
export OID=$(curl -s -X POST "$B/orders" -H "$AUTH" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

curl -s -X POST "$B/payments" -H "$AUTH" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-pay-2' \
  -d "{\"orderId\":$OID,\"simulateOutcome\":\"SUCCESS\"}" | python3 -m json.tool
```

```json
{
  "id": 4,
  "paymentReference": "PAY-20261015-033A08",
  "orderId": 9,
  "status": "SUCCEEDED",
  "amount": 89999.00,
  "currency": "INR",
  "providerReference": "SIM-2D1DF463",
  "completedAt": "2026-10-15T05:06:17.739940880Z"
}
```

```bash
curl -s "$B/orders/$OID" -H "$AUTH" | python3 -m json.tool
# "status": "CONFIRMED", "paymentStatus": "SUCCEEDED",
# "confirmedAt": "2026-10-15T05:06:17.739954Z"
```

The stock stays **reserved**, not returned — it is consumed when the order ships.

### Double charging is refused

```bash
curl -s -X POST "$B/payments" -H "$AUTH" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: a-totally-different-key' \
  -d "{\"orderId\":$OID}" | python3 -m json.tool
# 409  "code": "PAYMENT_ALREADY_EXISTS"
```

A different key, so idempotency does not apply — this is the partial index
`uq_payments_order_active` doing its job.

### There is no amount field

```bash
curl -s -X POST "$B/payments" -H "$AUTH" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: tamper-1' \
  -d "{\"orderId\":$OID,\"amount\":0.01,\"total\":0.01}"
```

The `amount` is read from the order. Price tampering has nowhere to enter.

---

## 8. Insufficient stock

Four laptops remain. Ask for 99:

```bash
curl -s -X POST "$B/cart/items" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"productId":5,"quantity":99}' > /dev/null
curl -s -X POST "$B/orders" -H "$AUTH" | python3 -m json.tool
```

```json
{
  "type":      "https://api.ecommerce.local/problems/insufficient-stock",
  "title":     "Insufficient Stock",
  "status":    409,
  "detail":    "Only 4 unit(s) of 'UltraBook 14 Pro' are available; 99 were requested",
  "instance":  "/api/v1/orders",
  "code":      "INSUFFICIENT_STOCK",
  "traceId":   "d114258653c145ba",
  "timestamp": "2026-10-15T05:06:17.852781568Z",
  "productId": 5,
  "requested": 99,
  "available": 4
}
```

The machine-readable extensions let a client show "only 4 left" without a second
round trip. The cart survives the failed checkout, and no stock moved.

---

## 9. Administration

The migrations seed **zero users**, so there is no committed credential. The
development administrator is bootstrapped at startup from `.env`:

```bash
export ADMIN_EMAIL=$(grep '^ECOMMERCE_ADMIN_EMAIL=' .env | cut -d= -f2-)
export ADMIN_PASS=$(grep '^ECOMMERCE_ADMIN_PASSWORD=' .env | cut -d= -f2-)

export ADMIN=$(curl -s -X POST "$B/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASS\"}" \
  | python3 -c "import sys,json;print('Authorization: Bearer '+json.load(sys.stdin)['accessToken'])")
```

> Reading the values out of `.env` keeps them off your shell history. They never
> appear in a command line, where `ps` would show them to every user on the machine.

### Stock

```bash
curl -s "$B/admin/inventory/5" -H "$ADMIN" | python3 -m json.tool
# exact available / reserved / total

curl -s -X POST "$B/admin/inventory/5/adjust" -H "$ADMIN" \
  -H 'Content-Type: application/json' \
  -d '{"quantity":20,"reason":"Supplier delivery #4471"}' | python3 -m json.tool
```

The quantity is a **delta**, not an absolute figure, so two deliveries recorded
concurrently both count. A negative adjustment larger than stock is refused with 409
rather than clamped to zero — silently losing a correction is worse than rejecting
it.

### Fulfilment

```bash
for S in PROCESSING SHIPPED DELIVERED; do
  curl -s -X PATCH "$B/admin/orders/$OID/status" -H "$ADMIN" \
    -H 'Content-Type: application/json' -d "{\"status\":\"$S\"}" \
    -o /dev/null -w "$S: %{http_code}\n"
done
```

An illegal jump names the legal targets:

```bash
curl -s -X PATCH "$B/admin/orders/$OID/status" -H "$ADMIN" \
  -H 'Content-Type: application/json' -d '{"status":"DELIVERED"}' | python3 -m json.tool
# 422  "detail": "Cannot move an order from CONFIRMED to DELIVERED; allowed: [PROCESSING, CANCELLED]"
```

Moving to `SHIPPED` **consumes** the reservation (`reserved` drops, `available` is
untouched — the units have physically left). Cancelling **releases** it.

### Catalogue

```bash
curl -s -X POST "$B/products" -H "$ADMIN" -H 'Content-Type: application/json' \
  -d '{"sku":"TEST-WIDGET-001","name":"Test Widget","price":1234.56,
       "categoryId":1,"initialStock":42}' | python3 -m json.tool

curl -s -X PATCH "$B/products/5/active?active=false" -H "$ADMIN"   # soft-disable
curl -s "$B/admin/products?size=50" -H "$ADMIN"                    # includes inactive
```

Deactivating a **category** hides its products too — otherwise "retire this
category" would leave its items purchasable through a direct link.

---

## 10. Security probes

Worth running; each returns the standard Problem Details shape.

```bash
# No token → 401 AUTHENTICATION_REQUIRED, with a WWW-Authenticate header
curl -si "$B/cart" | head -5

# Customer reaching an admin endpoint → 403 ACCESS_DENIED (not 401)
curl -s "$B/admin/orders" -H "$AUTH" | python3 -m json.tool

# Another customer's order → 404, indistinguishable from one that does not exist
curl -s "$B/orders/999999" -H "$AUTH" | python3 -m json.tool
```

```
  no token:        HTTP 401   code = AUTHENTICATION_REQUIRED
  customer→admin:  HTTP 403   code = ACCESS_DENIED
  foreign/missing: HTTP 404   code = RESOURCE_NOT_FOUND
```

The 404 is deliberate. A 403 would confirm the id is real, turning every endpoint
into an existence oracle.

```bash
# A tampered token
curl -s "$B/cart" -H "Authorization: Bearer ${TOKEN%?}X" | python3 -m json.tool
# 401  "code": "TOKEN_INVALID"

# A refresh token presented as a bearer credential
curl -s "$B/cart" -H "Authorization: Bearer $REFRESH" | python3 -m json.tool
# 401  "code": "TOKEN_INVALID" — the `typ` claim stops a 7-day token becoming an API key

# An alg:none forgery claiming ROLE_ADMIN
HDR=$(printf '{"alg":"none","typ":"JWT"}' | base64 | tr -d '=' | tr '/+' '_-')
PLD=$(printf '{"sub":"1","rol":"ROLE_ADMIN","typ":"ACCESS","iss":"ecommerce-backend","exp":9999999999}' \
      | base64 | tr -d '=\n' | tr '/+' '_-')
curl -s "$B/admin/orders" -H "Authorization: Bearer $HDR.$PLD." | python3 -m json.tool
# 401  "code": "TOKEN_INVALID"

# SQL injection in the search parameter
curl -s --get "$B/products" --data-urlencode "search=' OR 1=1 --" \
  | python3 -c "import sys,json;print('matches:',json.load(sys.stdin)['totalElements'])"
curl -s "$B/products" | python3 -c "import sys,json;print('catalogue:',json.load(sys.stdin)['totalElements'])"
# matches:   0     ← compared as a literal string
# catalogue: 18    ← intact
```

---

## 11. Proving the concurrency guarantee by hand

The automated version is
[`InventoryConcurrencyIT`](../src/test/java/com/bhavesh/ecommerce/inventory/InventoryConcurrencyIT.java).
To see it yourself, the shortest honest demonstration is at the SQL level, in two
`psql` sessions:

```sql
-- Session A                              -- Session B
BEGIN;
                                          BEGIN;
UPDATE inventory
   SET available = available - 5
 WHERE product_id = 5
   AND available >= 5;
-- UPDATE 1
                                          UPDATE inventory
                                             SET available = available - 5
                                           WHERE product_id = 5
                                             AND available >= 5;
                                          -- ⏸ blocks, waiting for A's row lock
COMMIT;
                                          -- UPDATE 0   ← re-evaluated the WHERE
                                          --               against A's committed row
                                          COMMIT;
```

Session B **blocks**, then re-evaluates its predicate against the newly committed
row under `READ COMMITTED` and matches nothing. The application turns that zero into
a 409. No application code participated in the decision.

The backstop beneath it:

```sql
UPDATE inventory SET available = -1 WHERE product_id = 5;
-- ERROR: new row for relation "inventory" violates check constraint
--        "ck_inventory_available_non_negative"
```

For the HTTP-level version, the test harness used during development is a small
Python script that registers N buyers, puts one unit in each cart, and releases all
N checkouts through a `threading.Barrier`. With the seeded 5 units and 20 buyers it
produces exactly 5 × 201 and 15 × 409, every time.

---

## 12. Error reference

Every error body has the same shape. Branch on `code`, which is stable; `detail` may
be reworded.

| Code | Status | Means |
|---|---|---|
| `VALIDATION_FAILED` | 400 | One or more fields invalid; see the `errors` array |
| `MALFORMED_REQUEST` | 400 | Body could not be parsed |
| `INVALID_PARAMETER` | 400 | A query or path parameter is wrong |
| `AUTHENTICATION_REQUIRED` | 401 | No usable credential |
| `INVALID_CREDENTIALS` | 401 | Wrong email **or** password — deliberately the same |
| `TOKEN_EXPIRED` | 401 | Access token expired — refresh |
| `TOKEN_INVALID` | 401 | Signature, issuer or type wrong — re-authenticate |
| `REFRESH_TOKEN_INVALID` | 401 | Refresh token unknown, expired **or** revoked |
| `ACCOUNT_DISABLED` | 401 | The account is disabled |
| `ACCESS_DENIED` | 403 | Authenticated, but not permitted |
| `RESOURCE_NOT_FOUND` | 404 | Does not exist, **or** is not yours |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Wrong `Content-Type` |
| `EMAIL_ALREADY_REGISTERED` | 409 | Address taken (case-insensitive) |
| `DUPLICATE_SKU` / `DUPLICATE_CATEGORY` | 409 | Identifier taken |
| `CATEGORY_IN_USE` | 409 | Category still holds products — deactivate instead |
| `INSUFFICIENT_STOCK` | 409 | Carries `productId`, `requested`, `available` |
| `CONCURRENT_MODIFICATION` | 409 | Lost a race, or a duplicate is still in flight — retry |
| `PAYMENT_ALREADY_EXISTS` | 409 | A payment is already live or complete for this order |
| `ORDER_NOT_CANCELLABLE` | 409 | Too late to cancel — it has shipped |
| `BUSINESS_RULE_VIOLATION` | 422 | Well-formed, but a domain rule says no |
| `INVALID_STATUS_TRANSITION` | 422 | Illegal order transition; `detail` lists the legal ones |
| `EMPTY_CART` | 422 | Nothing to check out |
| `PRODUCT_UNAVAILABLE` | 422 | Product or its category is deactivated |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Same key, different request |
| `INTERNAL_ERROR` | 500 | A bug. Quote the `traceId`. |
| `PAYMENT_PROVIDER_ERROR` | 502 | Provider unreachable |
| `DEPENDENCY_UNAVAILABLE` | 503 | A downstream service is down |

Every response — success or failure — carries an `X-Trace-Id` header matching the
`traceId` in the body and in the server log:

```bash
curl -si "$B/products/999999" | grep -i x-trace-id
# X-Trace-Id: d114258653c145ba
```

Quote that id and the relevant log line can be found exactly, without a stack trace
ever reaching the client.
