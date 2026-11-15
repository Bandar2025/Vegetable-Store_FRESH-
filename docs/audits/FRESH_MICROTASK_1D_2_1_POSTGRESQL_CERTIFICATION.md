# FRESH — Microtask 1D.2.1: Checkout Hardening, API Contract Audit & PostgreSQL Certification

- **Date:** 2026-11-11
- **Task:** Microtask 1D.2.1 — Checkout Hardening, API Contract Audit & PostgreSQL Certification
- **Prerequisite Audited Report:** `docs/audits/FRESH_MICROTASK_1D_2_CHECKOUT_IMPLEMENTATION.md`
- **Target Endpoint:** `POST /api/v1/orders/` (and `POST /api/v1/orders/checkout/`)
- **Android / Room / UI Files Modified:** `0`
- **Django ORM & Live PostgreSQL Runtime Execution Status:** `NOT EXECUTED` (Django, DRF, and PostgreSQL are not installed in this Android build container; reproducible Docker Compose + certification runner provided)
- **Final Certification Status:** **`CONDITIONALLY CERTIFIED`**

---

## 1. Repository Audit vs. Microtask 1D.2 Implementation Report

### 1.1 Files Inspected

| File Path | Role Audited |
|---|---|
| `docs/audits/FRESH_MICROTASK_1D_2_CHECKOUT_IMPLEMENTATION.md` | Microtask 1D.2 report compared against actual repository state |
| `backend/apps/orders/models.py` | `Order`, `OrderItem`, `OrderStatusHistory`, `IdempotencyKey` models and constraints |
| `backend/apps/orders/migrations/0005_scope_order_idempotency_key_per_customer.py` | Migration scoping `Order.idempotency_key` uniqueness per `(customer_id, idempotency_key)` |
| `backend/apps/orders/auth.py` | `SessionTokenAuthentication`, `resolve_service_actor_from_user`, `_resolve_actor_sqlite`, `enforce_no_client_identity_spoofing` |
| `backend/apps/orders/permissions.py` | `get_or_resolve_request_actor`, `IsAuthenticatedActiveProfile`, `IsCustomerPermission`, `OrderObjectPermission` |
| `backend/apps/orders/services.py` | `checkout_order`, `_checkout_order_django`, `_checkout_order_sqlite`, `FORBIDDEN_ORDER_CHECKOUT_FIELDS`, `FORBIDDEN_ITEM_FIELDS` |
| `backend/apps/orders/serializers.py` | `CheckoutRequestSerializer`, `CheckoutItemRequestSerializer`, `OrderSerializer`, `OrderItemSerializer` |
| `backend/apps/orders/views.py` | `OrderViewSet` (`create`, `checkout`, `get_permissions`, `_handle_checkout`) |
| `backend/apps/orders/test_http_server.py` | Standalone HTTP contract server for `POST /api/v1/orders/` and `GET /api/v1/orders/` |
| `backend/apps/orders/tests.py` | Backend unit, HTTP contract, concurrency, and migration verification suite |

### 1.2 Defects & Gaps Discovered During Audit and Resolved in 1D.2.1

1. **Syntax Error in `backend/apps/orders/views.py` (Line 41):**
   - **Finding:** `from .serializers import (` on line 34 of `backend/apps/orders/views.py` was missing its closing parenthesis `)` on line 41. Because `views.py` was not imported during standalone unit tests when `rest_framework` was absent, this syntax defect went undetected in 1D.2.
   - **Fix:** Restored the closing `)` in `backend/apps/orders/views.py` and added an automated `ast.parse()` verification test (`test_00_repository_ast_and_source_audit_verification`) over all audited Python files.
2. **Missing Explicit `IsCustomerPermission` in `OrderViewSet.get_permissions` (`backend/apps/orders/views.py`):**
   - **Finding:** While `OrderViewSet._handle_checkout` and `checkout_order` checked `actor.role == CUSTOMER`, `OrderViewSet` did not include `IsCustomerPermission` in `get_permissions()` for `create` and `checkout` actions.
   - **Fix:** Imported `IsCustomerPermission` in `backend/apps/orders/views.py` and implemented `OrderViewSet.get_permissions(self)` so `create` and `checkout` explicitly enforce `[IsAuthenticated(), IsAuthenticatedActiveProfile(), IsCustomerPermission(), OrderObjectPermission()]`.
3. **Stale Authentication Warning in `backend/apps/orders/services.py` (Lines 4–8):**
   - **Finding:** The module docstring at the top of `backend/apps/orders/services.py` still contained a stale Phase 0/1A notice stating *"A real authentication system is NOT yet implemented in this codebase"*.
   - **Fix:** Updated the module docstring in `backend/apps/orders/services.py` to accurately document the active `SessionTokenAuthentication` + RBAC permission architecture.
4. **Serializer Validation Bypass in Standalone Python & `test_http_server.py`:**
   - **Finding:** `CheckoutRequestSerializer` and `CheckoutItemRequestSerializer` in `backend/apps/orders/serializers.py` could not be imported when `rest_framework` was not installed, and `FreshContractHttpServer` validated payloads manually rather than executing `CheckoutRequestSerializer`. Additionally, `price_halalas` was accepted on `CheckoutItemRequestSerializer` instead of being rejected via `FORBIDDEN_ITEM_FIELDS`, and `branch_id` at the item level was not listed in `FORBIDDEN_ITEM_FIELDS`.
   - **Fix:** Added a DRF-compatible `serializers` fallback shim in `backend/apps/orders/serializers.py` (active only when `rest_framework` is not installed), unified `FORBIDDEN_ORDER_CHECKOUT_FIELDS` and `FORBIDDEN_ITEM_FIELDS` across `services.py` and `serializers.py` (including rejecting item-level `price_halalas` and `branch_id`), and wired `CheckoutRequestSerializer` directly into `FreshContractHttpServer` in `backend/apps/orders/test_http_server.py`.
5. **Client-Supplied Identity Fields Matching Caller Identity on Write Requests (`backend/apps/orders/auth.py`):**
   - **Finding:** `enforce_no_client_identity_spoofing` previously only raised `AuthenticationBoundaryError` when `customer_id`, `user_id`, `actor_id`, `role`, or `store_id` *differed* from the authenticated caller's identity, and `_resolve_actor_sqlite` did not populate `store_id` on `ServiceActor`.
   - **Fix:** Hardened `enforce_no_client_identity_spoofing` in `backend/apps/orders/auth.py` so that on write requests (`POST`, `PUT`, `PATCH`, `DELETE`), any client-supplied `customer_id`, `user_id`, `actor_id`, `role`, `actor_role`, or `store_id` in the request body is strictly rejected with `AuthenticationBoundaryError` (`403 Forbidden`) even when it matches the caller's own identity. Updated `_resolve_actor_sqlite` to populate `store_id` on `ServiceActor`.
6. **Idempotency Race Savepoint & Committed-Order Recovery (`backend/apps/orders/services.py`):**
   - **Finding:** In `_checkout_order_sqlite`, a fixed outer savepoint name (`sp_checkout_order`) wrapped the entire function, and if an `Order` row already existed for `(customer_id, idempotency_key)` when `orders_idempotencykey` was missing or during an `IntegrityError` race, it unconditionally raised `IdempotencyConflictError` instead of checking whether the committed `Order` matched the request payload.
   - **Fix:** Added `_reconstruct_or_conflict_existing_order_django` and `_reconstruct_or_conflict_existing_order_sqlite` in `backend/apps/orders/services.py` and isolated the write savepoint (`sp_checkout_<uuid>`) in `_checkout_order_sqlite` so concurrent identical requests hitting `IntegrityError` recover cleanly (`replayed=True`) while concurrent conflicting payloads raise `IdempotencyConflictError` (`409 Conflict`) after rolling back tentative writes.
7. **Legacy Blank Idempotency Key Normalization in Migration `0005` (`backend/apps/orders/migrations/0005_scope_order_idempotency_key_per_customer.py`):**
   - **Finding:** Migration `0005` added `CheckConstraint(..., name='order_idempotency_key_non_empty')` without first normalizing any legacy empty-string (`''`) or whitespace `idempotency_key` values to `NULL`.
   - **Fix:** Added `normalize_blank_order_idempotency_keys` via `migrations.RunPython` before `AddConstraint` in `0005_scope_order_idempotency_key_per_customer.py`, plus `apply_migration_0005_sqlite` and `reverse_migration_0005_sqlite` for disposable-DB forward and reverse migration testing.

### 1.3 Files Changed / Created in Microtask 1D.2.1

| File Path | Action | Summary of Changes |
|---|---|---|
| `backend/apps/orders/views.py` | Modified | Fixed missing `)` on serializer import (line 41); added `IsCustomerPermission` and `OrderViewSet.get_permissions()` for `create`/`checkout`. |
| `backend/apps/orders/services.py` | Modified | Updated module auth docstring; expanded `FORBIDDEN_ORDER_CHECKOUT_FIELDS` and `FORBIDDEN_ITEM_FIELDS`; added `_on_before_order_insert_hook`, `_reconstruct_or_conflict_existing_order_django`, `_reconstruct_or_conflict_existing_order_sqlite`, and isolated write savepoints. |
| `backend/apps/orders/serializers.py` | Modified | Added DRF fallback shim for standalone testing; unified forbidden field sets with `services.py`; removed item-level `price_halalas` acceptance; added `warehouse_id` and `reject_client_pricing` to `CheckoutRequestSerializer`. |
| `backend/apps/orders/auth.py` | Modified | Hardened `enforce_no_client_identity_spoofing` to reject client-supplied identity fields on write requests even when matching caller identity; propagated `store_id` in `_resolve_actor_sqlite`; added `user.identity_profile` fallback in `resolve_service_actor_from_user`. |
| `backend/apps/orders/permissions.py` | Modified | Decoupled `apps.orders.auth` import from `apps.orders.models` import so permission classes operate in standalone Python tests. |
| `backend/apps/orders/migrations/0005_scope_order_idempotency_key_per_customer.py` | Modified | Added `RunPython(normalize_blank_order_idempotency_keys)` prior to constraint creation and disposable SQLite forward/reverse migration helpers (`apply_migration_0005_sqlite`, `reverse_migration_0005_sqlite`). |
| `backend/apps/orders/test_http_server.py` | Modified | Wired `CheckoutRequestSerializer` and `before_order_insert_hook` into `POST /api/v1/orders/` and `POST /api/v1/orders/checkout/`. |
| `backend/apps/orders/tests.py` | Modified | Added `MicroTask1D21HardeningAndCertificationTests` and `MicroTask1D21PostgresORMTransactionTests`. |
| `backend/docker-compose.postgres-test.yml` | Created | Reproducible Docker Compose stack (`postgres:16-alpine` + `python:3.11-slim`) for live PostgreSQL 16 + Django ORM certification. |
| `backend/scripts/run_postgres_certification.sh` | Created | Automated PostgreSQL certification script running `check`, `makemigrations --check`, forward/reverse `0004 <-> 0005` migration checks, and full `manage.py test`. |
| `docs/audits/FRESH_MICROTASK_1D_2_1_POSTGRESQL_CERTIFICATION.md` | Created | This certification report. |

---

## 2. Serializer & HTTP Contract Hardening — 18-Field Validation Matrix

Every client-controlled field specified in Section 2 was explicitly audited and tested across both `CheckoutRequestSerializer` / `CheckoutItemRequestSerializer` (`test_12_actual_serializer_validation_contract_matrix`) and live HTTP responses on `POST /api/v1/orders/` (`test_01_http_financial_stock_identity_field_spoofing_matrix`):

| # | Field Name | Scope Tested | Classification | Serializer Behavior (`CheckoutRequestSerializer` / `CheckoutItemRequestSerializer`) | HTTP Behavior (`POST /api/v1/orders/`) | Server Authority Guarantee |
|---|---|---|---|---|---|---|
| 1 | `total_halalas` | Order & Item | **Ignored** (Order, default) / **Rejected** (Order when `reject_client_pricing=True`, or Item level) | Accepted as optional non-negative int at Order level (`min_value=0`), ignored in price calculation; **Rejected** (`ValidationError`) at Item level via `FORBIDDEN_ITEM_FIELDS`. | Order level: **Ignored** by default (`201 Created` with server-computed `total_halalas`), or **Rejected** (`400 Bad Request`) when `reject_client_pricing=True` and mismatched, or when `< 0`. Item level: **Rejected** (`400 Bad Request`). | `Order.total_halalas` is always `SUM(OrderItem.line_total_halalas)` computed on the server. |
| 2 | `subtotal_halalas` | Order & Item | **Rejected** | **Rejected** (`ValidationError`) at both Order (`FORBIDDEN_ORDER_CHECKOUT_FIELDS`) and Item (`FORBIDDEN_ITEM_FIELDS`) levels. | **Rejected** (`400 Bad Request`) at both Order and Item levels. | Client cannot inject a subtotal field. |
| 3 | `expected_total_halalas` | Order & Item | **Accepted only as a validation hint** (Order) / **Rejected** (Item) | Accepted as optional non-negative int at Order level (`min_value=0`); **Rejected** (`ValidationError`) at Item level via `FORBIDDEN_ITEM_FIELDS`. | Order level: if provided, compared against server-computed `authoritative_total_halalas` — **Rejected** (`400 Bad Request`) on mismatch, **Accepted** (`201 Created`) on exact match. Item level: **Rejected** (`400 Bad Request`). | Never overrides server-computed `total_halalas`; only aborts checkout if the catalog price changed from what the client displayed. |
| 4 | `unit_price_halalas` | Order & Item | **Rejected** (Order) / **Ignored** (Item, default) or **Rejected** (Item when `reject_client_pricing=True`) | **Rejected** (`ValidationError`) at Order level (`FORBIDDEN_ORDER_CHECKOUT_FIELDS`). Accepted as optional non-negative int at Item level (`min_value=0`). | Order level: **Rejected** (`400 Bad Request`). Item level: **Ignored** by default (`201 Created` using server catalog/override price), or **Rejected** (`400 Bad Request`) when `reject_client_pricing=True` and mismatched, or when `< 0`. | `OrderItem.unit_price_halalas` is always resolved from `BranchProductOverride.override_price_halalas ?? Product.price_halalas`. |
| 5 | `price_halalas` | Order & Item | **Rejected** | **Rejected** (`ValidationError`) at both Order (`FORBIDDEN_ORDER_CHECKOUT_FIELDS`) and Item (`FORBIDDEN_ITEM_FIELDS`) levels. | **Rejected** (`400 Bad Request`) at both Order and Item levels. | Catalog base price field `price_halalas` is strictly rejected in checkout payloads. |
| 6 | `line_total_halalas` | Order & Item | **Rejected** (Order) / **Ignored** (Item, default) or **Rejected** (Item when `reject_client_pricing=True`) | **Rejected** (`ValidationError`) at Order level (`FORBIDDEN_ORDER_CHECKOUT_FIELDS`). Accepted as optional non-negative int at Item level (`min_value=0`). | Order level: **Rejected** (`400 Bad Request`). Item level: **Ignored** by default (`201 Created` using server-computed `(quantity_milli * unit_price_halalas + 500) // 1000`), or **Rejected** (`400 Bad Request`) when `reject_client_pricing=True` and mismatched, or when `< 0`. | `OrderItem.line_total_halalas` is always computed on the server. |
| 7 | `expected_unit_price_halalas` | Order & Item | **Rejected** (Order) / **Accepted only as a validation hint** (Item) | **Rejected** (`ValidationError`) at Order level (`FORBIDDEN_ORDER_CHECKOUT_FIELDS`). Accepted as optional non-negative int at Item level (`min_value=0`). | Order level: **Rejected** (`400 Bad Request`). Item level: if provided, compared against server-resolved `unit_price_halalas` — **Rejected** (`400 Bad Request`) on mismatch, **Accepted** (`201 Created`) on exact match. | Never overrides server `unit_price_halalas`; only protects client against mid-browse price changes. |
| 8 | `discount_halalas` | Order & Item | **Rejected** | **Rejected** (`ValidationError`) at both Order and Item levels. | **Rejected** (`400 Bad Request`) at both Order and Item levels. | Client cannot inject arbitrary discounts. |
| 9 | `delivery_fee_halalas` | Order & Item | **Rejected** | **Rejected** (`ValidationError`) at both Order and Item levels. | **Rejected** (`400 Bad Request`) at both Order and Item levels. | Client cannot manipulate delivery fees. |
| 10 | `stock_quantity` | Order & Item | **Rejected** | **Rejected** (`ValidationError`) at both Order and Item levels. | **Rejected** (`400 Bad Request`) at both Order and Item levels. | Stock balances are read exclusively from locked `WarehouseStockBalance` rows. |
| 11 | `on_hand_milli` | Order & Item | **Rejected** | **Rejected** (`ValidationError`) at both Order and Item levels. | **Rejected** (`400 Bad Request`) at both Order and Item levels. | `WarehouseStockBalance.on_hand_milli` is strictly server-controlled. |
| 12 | `reserved_milli` | Order & Item | **Rejected** | **Rejected** (`ValidationError`) at both Order and Item levels. | **Rejected** (`400 Bad Request`) at both Order and Item levels. | `WarehouseStockBalance.reserved_milli` is strictly server-controlled. |
| 13 | `customer_id` | Order & Item | **Server-controlled** & **Rejected** from client | **Rejected** (`ValidationError`) at both Order and Item levels. | Order level: **Rejected** (`403 Forbidden` via `enforce_no_client_identity_spoofing`, both when spoofed and when matching caller's own `customer_id`). Item level: **Rejected** (`400 Bad Request`). | Derived exclusively from `actor.customer_id` (`UserIdentityProfile.customer_id`). |
| 14 | `user_id` | Order & Item | **Server-controlled** & **Rejected** from client | **Rejected** (`ValidationError`) at both Order and Item levels. | Order level: **Rejected** (`403 Forbidden` via `enforce_no_client_identity_spoofing`, both when spoofed and when matching caller's own `user_id`). Item level: **Rejected** (`400 Bad Request`). | Derived exclusively from authenticated JWT session user (`request.user.pk`). |
| 15 | `actor_id` | Order & Item | **Server-controlled** & **Rejected** from client | **Rejected** (`ValidationError`) at both Order and Item levels. | Order level: **Rejected** (`403 Forbidden` via `enforce_no_client_identity_spoofing`, both when spoofed and when matching caller's own `actor_id`). Item level: **Rejected** (`400 Bad Request`). | Derived exclusively from `ServiceActor.actor_id` (`f"user:{user.pk}"`). |
| 16 | `role` | Order & Item | **Server-controlled** & **Rejected** from client | **Rejected** (`ValidationError`) at both Order and Item levels. | Order level: **Rejected** (`403 Forbidden` via `enforce_no_client_identity_spoofing`, both when spoofed and when matching caller's own `role`). Item level: **Rejected** (`400 Bad Request`). | Derived exclusively from `UserIdentityProfile.role`. |
| 17 | `store_id` | Order & Item | **Server-controlled** & **Rejected** from client | **Rejected** (`ValidationError`) at both Order and Item levels. | Order level: **Rejected** (`403 Forbidden` via `enforce_no_client_identity_spoofing`, both when spoofed and when matching caller's own `store_id`). Item level: **Rejected** (`400 Bad Request`). | Derived exclusively from `UserIdentityProfile.store_id` and `Branch.store_id`. |
| 18 | `branch_id` | Order & Item | **Accepted (Required Target Branch Selector at Order Level)** / **Rejected (Item Level)** | Required positive integer (`min_value=1`) at Order level; **Rejected** (`ValidationError`) at Item level via `FORBIDDEN_ITEM_FIELDS`. | Order level: validated against active `Branch`, active `Store`, and `actor.store_id` isolation (`404 Not Found` if branch does not exist, `403 Forbidden` on cross-store violation, `400 Bad Request` if inactive). Item level: **Rejected** (`400 Bad Request`). | Validated against server database and actor store scope before warehouse resolution. |

---

## 3. Authentication & Authorization Audit Findings

1. **Permission Chain on `OrderViewSet` (`backend/apps/orders/views.py`):**
   - `OrderViewSet.authentication_classes = [SessionTokenAuthentication]`
   - `OrderViewSet.get_permissions(self)`:
     - On `create` (`POST /api/v1/orders/`) and `checkout` (`POST /api/v1/orders/checkout/`): enforces `[IsAuthenticated(), IsAuthenticatedActiveProfile(), IsCustomerPermission(), OrderObjectPermission()]`.
     - On other actions (`list`, `retrieve`, `transition_status`, `cancel`): enforces `[IsAuthenticated(), IsAuthenticatedActiveProfile(), OrderObjectPermission()]` plus action-level role checks.
2. **Actor Resolution & Identity Spoofing Protection (`backend/apps/orders/auth.py` & `backend/apps/orders/permissions.py`):**
   - `get_or_resolve_request_actor(request)` resolves `ServiceActor` via `resolve_service_actor_from_user(request.user)` and immediately invokes `enforce_no_client_identity_spoofing(request, actor)`.
   - `resolve_service_actor_from_user` and `_resolve_actor_sqlite` verify:
     - `user.is_authenticated` and `user.is_active` are `True`.
     - `user.password` uses a valid cryptographic password hasher (`assert_password_hashed`).
     - `UserIdentityProfile` exists and `profile.is_active` is `True`.
     - For `CUSTOMER`: `profile.customer_id` is non-empty, and if `profile.store_id` is set, the associated `Store` is active.
     - Non-`CUSTOMER` roles (`OWNER`, `BRANCH_MANAGER`) are blocked from checkout by both `IsCustomerPermission` and `checkout_order` (`403 Forbidden`).
3. **Stale Authentication Notice Resolved:**
   - The outdated docstring notice in `backend/apps/orders/services.py` has been replaced with accurate documentation of the `SessionTokenAuthentication` + RBAC permission architecture.

---

## 4. Idempotency Race Audit Findings

All 7 scenarios required in Section 4 were audited and verified (`test_05_same_key_replay_and_06_same_key_payload_conflict` and `test_07_concurrent_same_key_requests_and_retry_after_timeout_or_lost_response`):

| # | Scenario | Expected Behavior | Verified Result |
|---|---|---|---|
| 1 | **Same customer, same key, same payload** | Returns `200 OK` with `replayed=True` and the original `order_id`; stock is deducted only once. | **PASSED** (`test_05`, `test_17`) |
| 2 | **Same customer, same key, different payload** | Raises `IdempotencyConflictError` (`409 Conflict`); no second order or stock movement is created. | **PASSED** (`test_05`, `test_11`, `test_17`) |
| 3 | **Different customers, same key** | Scoped by `(actor.actor_id, 'CHECKOUT_ORDER', idempotency_key)` and `UniqueConstraint(fields=['customer_id', 'idempotency_key'])`; both customers succeed (`201 Created`) with independent orders. | **PASSED** (`test_05`, `test_10`) |
| 4 | **Two simultaneous identical requests** | When Request B passes the initial `IdempotencyKey` lookup before Request A commits and hits `IntegrityError` on `Order` / `IdempotencyKey` insert, Request B rolls back its savepoint, reads Request A's committed record, verifies matching `request_hash`, and returns `replayed=True` (`200 OK`). Only **1** `Order` and **1** set of `StockMovement` rows exist. | **PASSED** (`test_07` Scenario 1) |
| 5 | **Two simultaneous different payloads with the same key** | Winner commits (`201 Created`); loser hits `IntegrityError`, rolls back its savepoint (undoing any tentative writes), detects `request_hash` / line mismatch against the winner's committed record, and raises `IdempotencyConflictError` (`409 Conflict`). Only **1** `Order` and **1** set of `StockMovement` rows exist. | **PASSED** (`test_07` Scenario 2) |
| 6 | **Retry after client timeout** | Client socket times out (`socket.timeout`) while server completes the checkout commit; client retries `POST /api/v1/orders/` with the same `idempotency_key` and receives `200 OK` (`replayed=True`) with the original order and single stock deduction. | **PASSED** (`test_07` Scenario 4) |
| 7 | **Retry after first transaction commits in DB (`orders_order`) but HTTP response was lost / `IdempotencyKey` cache entry missing** | `_reconstruct_or_conflict_existing_order_django` / `_reconstruct_or_conflict_existing_order_sqlite` locates the committed `Order` by `(customer_id, idempotency_key)`, verifies that `branch_id`, `fulfillment_warehouse_id`, and `(product_id, quantity_milli)` lines match, and returns `replayed=True` (`200 OK`) without duplicate order creation or stock deduction. | **PASSED** (`test_07` Scenario 3) |

---

## 5. Migration `0005` Safety & Reversibility Verification

Migration `backend/apps/orders/migrations/0005_scope_order_idempotency_key_per_customer.py` was audited and tested on a disposable pre-`0005` database (`test_11_migration_0005_preservation_null_blank_keys_and_reversibility`):

1. **Existing Order Data Preservation:** All pre-existing orders (`id`, `branch_id`, `fulfillment_warehouse_id`, `customer_id`, `status`, `total_halalas`, `timestamp`, `updated_at`) remain 100% intact across `0004 -> 0005`.
2. **Legacy Blank / Whitespace Key Normalization:** `migrations.RunPython(normalize_blank_order_idempotency_keys, reverse_code=migrations.RunPython.noop)` runs *before* `AddConstraint`, converting any legacy `''` or whitespace-only `idempotency_key` values to `NULL` so `order_idempotency_key_non_empty` never fails on pre-existing rows.
3. **Removal of Global Uniqueness:** Under `0004`, two different customers (`cust-A` and `cust-B`) could not use the same `idempotency_key`. After `0005`, `AlterField` removes `unique=True` on `idempotency_key`, allowing `cust-A` and `cust-B` to each create orders with `'legacy-key-1'`.
4. **Customer-Scoped Uniqueness:** `UniqueConstraint(fields=('customer_id', 'idempotency_key'), name='uniq_order_customer_idempotency_key')` blocks duplicate `(customer_id, idempotency_key)` for the same customer with `IntegrityError`.
5. **Deliberate `NULL` and Blank Key Behavior:** Multiple orders with `idempotency_key = NULL` for the same customer are permitted (standard SQL `NULL` distinctness in `UNIQUE`), while empty string `''` is rejected by `CheckConstraint(..., name='order_idempotency_key_non_empty')`.
6. **Reversibility (`0005 -> 0004`):** Tested on a disposable database:
   - When no cross-customer duplicate non-null keys exist, reversing `0005 -> 0004` succeeds cleanly and preserves all order rows.
   - If cross-customer duplicate non-null keys exist at reversal time, reversing to global uniqueness (`0004`) fails closed with `IntegrityError` and leaves the `0005` table intact.

---

## 6. PostgreSQL Runtime Certification Status & Reproducible Docker Environment

### 6.1 Current Container Environment Verification

The active build container was inspected and does **not** have Django, Django REST Framework, `psycopg2`/`psycopg`, PostgreSQL (`psql`/`postgres`), or `docker` installed:
- `python3 --version` -> `Python 3.11.2`
- `django`, `rest_framework`, `psycopg2`, `psycopg` -> `NOT INSTALLED`

In strict compliance with Section 5:
- **PostgreSQL runtime tests are marked `NOT EXECUTED` (never labeled as passed).**
- A reproducible PostgreSQL 16 + Django ORM certification environment has been created in:
  - `backend/docker-compose.postgres-test.yml`
  - `backend/scripts/run_postgres_certification.sh`

### 6.2 How to Execute Full PostgreSQL 16 Runtime Certification in CI / Docker Host

From the repository root on any machine or CI runner with Docker installed:

```bash
docker compose -f backend/docker-compose.postgres-test.yml up --build --abort-on-container-exit --exit-code-from django-certification
```

Or against an existing PostgreSQL 16 instance:

```bash
DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=fresh_cert_db DB_USER=fresh_cert_user DB_PASSWORD=fresh_cert_password_2026 \
  bash backend/scripts/run_postgres_certification.sh
```

This script executes:
1. `python3 manage.py check`
2. `python3 manage.py makemigrations --check --dry-run`
3. `python3 manage.py migrate products --noinput && python3 manage.py migrate orders 0004 --noinput`
4. `python3 manage.py migrate orders 0005 --noinput && python3 manage.py migrate orders 0004 --noinput && python3 manage.py migrate --noinput`
5. `python3 manage.py test apps.orders apps.products --verbosity 2`

---

## 7. Required 12-Point Test Matrix & Execution Results

### 7.1 Command Executed

```bash
PYTHONPATH=backend python3 -m unittest -v apps.orders.tests apps.products.tests
```

### 7.2 Aggregate Test Counts

- **Total Discovered Tests:** `90`
- **Passed:** `67`
- **Failed / Errors:** `0`
- **Skipped (Requires Django ORM / PostgreSQL Runtime):** `23`

### 7.3 12-Point Test Matrix Mapping

| # | Required Test Category | Test Method(s) in `backend/apps/orders/tests.py` | Status |
|---|---|---|---|
| 1 | **HTTP financial-field spoofing** (all 18 fields) | `MicroTask1D21HardeningAndCertificationTests.test_01_http_financial_stock_identity_field_spoofing_matrix` | **PASSED** |
| 2 | **Customer role enforcement** (`IsAuthenticated`, `IsAuthenticatedActiveProfile`, `IsCustomerPermission`, HTTP `401`/`403`) | `MicroTask1D21HardeningAndCertificationTests.test_02_customer_role_enforcement_and_permission_chain`, `MicroTask1D2AtomicCheckoutEngineTests.test_17_http_contract_status_codes_on_post_api_v1_orders` | **PASSED** |
| 3 | **Cross-store isolation** | `MicroTask1D21HardeningAndCertificationTests.test_03_cross_store_isolation_and_04_cross_branch_isolation`, `MicroTask1D2AtomicCheckoutEngineTests.test_15_cross_branch_warehouse_and_cross_store_isolation_and_spoofing_protection` | **PASSED** |
| 4 | **Cross-branch isolation** | `MicroTask1D21HardeningAndCertificationTests.test_03_cross_store_isolation_and_04_cross_branch_isolation`, `MicroTask1D2AtomicCheckoutEngineTests.test_15_cross_branch_warehouse_and_cross_store_isolation_and_spoofing_protection` | **PASSED** |
| 5 | **Same-key replay** | `MicroTask1D21HardeningAndCertificationTests.test_05_same_key_replay_and_06_same_key_payload_conflict`, `MicroTask1D2AtomicCheckoutEngineTests.test_10_idempotency_replay_prevents_duplicate_order_and_isolates_customers` | **PASSED** |
| 6 | **Same-key payload conflict** | `MicroTask1D21HardeningAndCertificationTests.test_05_same_key_replay_and_06_same_key_payload_conflict`, `MicroTask1D2AtomicCheckoutEngineTests.test_11_idempotency_key_reused_with_different_payload_rejected_with_conflict` | **PASSED** |
| 7 | **Concurrent same-key requests** (identical & conflicting payloads, client timeout retry, lost response retry) | `MicroTask1D21HardeningAndCertificationTests.test_07_concurrent_same_key_requests_and_retry_after_timeout_or_lost_response` | **PASSED** |
| 8 | **Concurrent last-stock requests** | `MicroTask1D2AtomicCheckoutEngineTests.test_12_two_concurrent_orders_competing_for_last_stock_unit` | **PASSED** |
| 9 | **Atomic rollback** | `MicroTask1D2AtomicCheckoutEngineTests.test_13_full_atomic_rollback_if_stock_movement_creation_fails` | **PASSED** |
| 10 | **PostgreSQL row-lock behavior** (`select_for_update()`, `transaction.atomic()`, PostgreSQL constraints) | `MicroTask1D21PostgresORMTransactionTests.test_10_postgresql_row_lock_select_for_update_and_constraints` | **`NOT EXECUTED` (Skipped in container; ready via `run_postgres_certification.sh`)** |
| 11 | **Migration preservation & reversibility (`0004 <-> 0005`)** | `MicroTask1D21HardeningAndCertificationTests.test_11_migration_0005_preservation_null_blank_keys_and_reversibility` | **PASSED (SQLite disposable DB) / `NOT EXECUTED` on live PostgreSQL** |
| 12 | **Actual Serializer validation** (`CheckoutRequestSerializer`, `CheckoutItemRequestSerializer`) | `MicroTask1D21HardeningAndCertificationTests.test_12_actual_serializer_validation_contract_matrix` | **PASSED** |

### 7.4 Complete Inventory of 23 Skipped Tests and Reasons

| Test Class | Skipped Count | Reason |
|---|---|---|
| `MicroTask1D21PostgresORMTransactionTests` (`apps.orders.tests`) | `1` | Requires live Django ORM + PostgreSQL runtime (`run_postgres_certification.sh`). |
| `MicroTask1C1DjangoORMAndPostgresVerificationTests` (`apps.orders.tests`) | `6` | Requires live Django ORM + PostgreSQL runtime. |
| `CustomerRegistrationDRFTests` (`apps.orders.tests`) | `4` | Requires Django ORM & DRF runtime. |
| `TokenLifecycleAndRateLimitingDRFTests` (`apps.orders.tests`) | `9` | Requires Django ORM & DRF runtime. |
| `ProductsDjangoModelTests` (`apps.products.tests`) | `2` | Requires Django ORM runtime. |
| `ProductsGuestCatalogAndPermissionDRFTests` (`apps.products.tests`) | `1` | Requires Django ORM & DRF runtime. |

---

## 8. Remaining Risks & Final Certification Status

1. **Remaining Risk:** Live PostgreSQL 16 execution of `select_for_update()` row-level locking and `manage.py migrate orders 0004 <-> 0005` could not be run inside the Android build container because PostgreSQL and Django are not installed in this container image. Mitigation: `backend/docker-compose.postgres-test.yml` and `backend/scripts/run_postgres_certification.sh` are provided and ready to run in CI or any Docker host prior to production deployment.
2. **Final Status:** **`CONDITIONALLY CERTIFIED`**
