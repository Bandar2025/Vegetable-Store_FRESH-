# FRESH — Microtask 1D.2: Atomic Server-Side Checkout Engine Implementation Report

- **Project:** FRESH (`Vegetable-Store_FRESH-`) — Saudi Grocery & Fresh Produce Platform
- **Microtask:** `1D.2` — Atomic Server-Side Checkout Engine Implementation
- **Prerequisite Gate:** `1D.1` (`docs/audits/FRESH_MICROTASK_1D_1_ORDERS_INVENTORY_AUDIT.md` — `READY FOR 1D.2`)
- **Scope:** Backend Only (`backend/apps/orders/`) — Zero Android / Room / UI Modifications
- **Target Endpoint:** `POST /api/v1/orders/` (with alias `POST /api/v1/orders/checkout/`)

---

## 1. Executive Summary

Microtask 1D.2 implements and hardens the server-authoritative, single-transaction atomic checkout engine for `POST /api/v1/orders/` in the Django backend (`backend/apps/orders/`).

Key accomplishments:
1. **Strict Customer Identity & Spoofing Protection:** `POST /api/v1/orders/` requires an authenticated user whose server-resolved `UserIdentityProfile.role` is `CUSTOMER`. Staff/admin roles (`OWNER`, `BRANCH_MANAGER`) and unauthenticated callers are rejected (`403 Forbidden` / `401 Unauthorized`). Any client attempt to inject or spoof `customer_id`, `user_id`, `actor_id`, `role`, `store_id`, `authorized_branch_ids`, `is_staff`, `is_superuser`, `status`, or stock/financial fields is rejected.
2. **Server-Authoritative Halala Pricing:** All item prices and order totals are calculated exclusively on the server in integer Halalas (`1 SAR = 100 Halalas`) and integer milli-units (`1000 = 1.000 unit`) using deterministic half-up integer rounding:
   $$\text{line\_total\_halalas} = \lfloor (\text{quantity\_milli} \times \text{unit\_price\_halalas} + 500) \div 1000 \rfloor$$
   Branch-specific overrides (`BranchProductOverride.override_price_halalas`) take precedence when non-null; otherwise `Product.price_halalas` is used.
3. **13-Step Single-Transaction Atomic Execution:** Checkout executes inside a single `transaction.atomic()` block (Django ORM / PostgreSQL) or `SAVEPOINT sp_checkout_order` (SQLite Reference Engine) with deterministic row locking (`select_for_update().order_by('warehouse_id', 'product_id')`) to prevent race conditions, deadlocks, and negative inventory.
4. **Customer-Scoped Idempotency & Race Condition Safety:** `IdempotencyKey` (`UniqueConstraint(fields=['actor_id', 'operation', 'idempotency_key'])`) and `Order` (`UniqueConstraint(fields=['customer_id', 'idempotency_key'])`) enforce per-customer idempotency at both the application and database constraint levels, catching concurrent `IntegrityError` races and returning `200 OK` (`replayed=True`) for identical payloads or `409 Conflict` for mismatched payloads.
5. **Verification:** All 81 backend tests were executed (`59 passed`, `22 skipped` due to Django/PostgreSQL runtime absence in the Android build container), including all 16 mandatory Microtask 1D.2 checkout, pricing, spoofing, concurrency, rollback, ledger, isolation, and HTTP contract scenarios.

---

## 2. Pre-Implementation Audit Findings

During Stage 1 (Pre-Implementation Audit), inspecting `models.py`, `services.py`, `views.py`, `serializers.py`, `permissions.py`, `urls.py`, and `auth.py` against the `1D.1` specification revealed four specific gaps that were resolved in `1D.2`:

| # | Area | Pre-1D.2 State | 1D.2 Resolution |
|---|---|---|---|
| 1 | `Order.idempotency_key` Uniqueness Scope | `Order.idempotency_key` had a global `unique=True` constraint (`models.py:39`), allowing a collision if two different customers happened to send the same client-generated idempotency key string. | Replaced global `unique=True` with `models.UniqueConstraint(fields=['customer_id', 'idempotency_key'], name='uniq_order_customer_idempotency_key')` and non-empty `CheckConstraint` in `models.py` and migration `0005_scope_order_idempotency_key_per_customer.py`. |
| 2 | Role & Store Scope Enforcement in `checkout_order` | `checkout_order` checked `actor.customer_id` matching `customer_id`, but did not explicitly enforce `actor.role == ActorRole.CUSTOMER.value` or validate `actor.store_id == branch.store_id` inside the service layer. | Enforced `role == ActorRole.CUSTOMER.value` and store isolation (`actor.store_id` vs `branch.store_id`) across `checkout_order`, `_checkout_order_django`, and `_checkout_order_sqlite`, plus populated `store_id` on `ServiceActor` in `auth.py`. |
| 3 | Concurrent Idempotency Race Handling | Pre-transaction `IdempotencyKey` lookup handled sequential replays, but two simultaneous requests with the same `(actor_id, operation, idempotency_key)` could hit a database `IntegrityError` on commit. | Added `IntegrityError` recovery in both `_checkout_order_django` and `_checkout_order_sqlite` that re-queries `IdempotencyKey` upon unique constraint collision, returning `replayed=True` (`200 OK`) if `request_hash` matches or raising `IdempotencyConflictError` (`409 Conflict`) if `request_hash` differs. |
| 4 | HTTP Error Mapping & Forbidden Field Validation | Missing branch/product raised `CheckoutValidationError` (`400`) instead of `404 Not Found`; spoofed `user_id` or item stock/price fields needed explicit rejection. | Added `CheckoutResourceNotFoundError` (`404 Not Found`), `SpoofedFinancialDataError` (`400 Bad Request`), `FORBIDDEN_ORDER_CHECKOUT_FIELDS`, and `FORBIDDEN_ITEM_FIELDS` across `services.py`, `serializers.py`, `views.py`, and `auth.py`. |

---

## 3. Files Modified and Created

All changes in Microtask 1D.2 were strictly confined to `backend/apps/orders/` and the audit report in `docs/audits/`. Zero Android or Room files were touched.

| File Path | Action | Summary of Changes |
|---|---|---|
| `backend/apps/orders/models.py` | Modified | Scoped `Order.idempotency_key` uniqueness per customer via `UniqueConstraint(fields=['customer_id', 'idempotency_key'], name='uniq_order_customer_idempotency_key')` and added `order_idempotency_key_non_empty` check constraint. |
| `backend/apps/orders/migrations/0005_scope_order_idempotency_key_per_customer.py` | Created | Non-destructive migration altering `Order.idempotency_key` to remove global `unique=True` and adding `uniq_order_customer_idempotency_key` and `order_idempotency_key_non_empty`. |
| `backend/apps/orders/auth.py` | Modified | Populated `store_id` on `ServiceActor` in `resolve_service_actor_from_user`; hardened `enforce_no_client_identity_spoofing` to reject spoofed `user_id` (`X-User-Id`, query, or body) and privilege/state fields (`is_staff`, `is_superuser`, `authorized_branch_ids`, `cancellation_restored`, `replayed`). |
| `backend/apps/orders/services.py` | Modified | Added `CheckoutResourceNotFoundError`, `SpoofedFinancialDataError`, `FORBIDDEN_ORDER_CHECKOUT_FIELDS`, and `FORBIDDEN_ITEM_FIELDS`; enforced `CUSTOMER` role and `store_id` isolation; added concurrent `IntegrityError` idempotency race resolution; added `list_orders_for_actor_sqlite`. |
| `backend/apps/orders/serializers.py` | Modified | Added `FORBIDDEN_ORDER_CHECKOUT_FIELDS` and `FORBIDDEN_ITEM_FIELDS` validation on `CheckoutRequestSerializer` and `CheckoutItemRequestSerializer`; exposed `product_id`, `branch_id`, and `fulfillment_warehouse_id` alongside existing fields in `OrderItemSerializer` and `OrderSerializer`. |
| `backend/apps/orders/views.py` | Modified | Updated `_extract_idempotency_key` to detect conflicting header vs. body keys (`409`); mapped `CheckoutResourceNotFoundError` to `404`, `InsufficientStockError` and `IdempotencyConflictError` to `409`, `AuthenticationBoundaryError` to `403`, `SpoofedFinancialDataError` and `CheckoutValidationError` to `400`, and unexpected exceptions to generic `500`. |
| `backend/apps/orders/test_http_server.py` | Modified | Added full order/inventory tables and triggers to `create_seeded_sqlite_connection()` and wired `POST /api/v1/orders/`, `POST /api/v1/orders/checkout/`, and `GET /api/v1/orders/`. |
| `backend/apps/orders/tests.py` | Modified | Updated `orders_order` constraint in `create_full_sqlite_schema` and added `MicroTask1D2AtomicCheckoutEngineTests` (16 comprehensive test methods covering all Stage 9 requirements + live HTTP status contract). |
| `docs/audits/FRESH_MICROTASK_1D_2_CHECKOUT_IMPLEMENTATION.md` | Created | This engineering implementation and verification report. |

---

## 4. Checkout Request & Response Contract

### Endpoint
- **Primary Endpoint:** `POST /api/v1/orders/`
- **Supported Alias:** `POST /api/v1/orders/checkout/`
- **Authentication:** Required (`Authorization: Bearer <access_token>` or `Token <access_token>`). Caller must resolve to an active `UserIdentityProfile` with `role == 'CUSTOMER'`.
- **Idempotency Header / Field:** `Idempotency-Key: <non-empty string up to 128 chars>` (or `idempotency_key` in JSON body; if both are provided and differ, `409 Conflict` is returned).

### Request Payload (`application/json`)
```json
{
  "branch_id": 1,
  "warehouse_id": 1,
  "idempotency_key": "550e8400-e29b-41d4-a716-446655440000",
  "items": [
    {
      "product_id": 1,
      "quantity_milli": 1500
    },
    {
      "product_id": 3,
      "quantity_milli": 1000
    }
  ]
}
```
- `warehouse_id` (or `fulfillment_warehouse_id`) is optional; if omitted, the server resolves the branch's active primary fulfillment warehouse (`is_primary_fulfillment=True, is_active=True`).
- Forbidden client fields in top-level body (`customer_id`, `user_id`, `actor_id`, `role`, `actor_role`, `store_id`, `authorized_branch_ids`, `is_staff`, `is_superuser`, `status`, `cancellation_restored`, `replayed`, `subtotal_halalas`, `subtotal`, `total`, `discount_halalas`, `delivery_fee_halalas`) and item-level body (`effective_price_halalas`, `override_price_halalas`, `stock_quantity`, `on_hand_milli`, `reserved_milli`, `available_milli`, `stock_milli`, `balance_after_milli`, `product_name_snapshot`, `unit_snapshot`, `price`, `unit_price`, `line_total`, `subtotal`, `subtotal_halalas`, `discount_halalas`) are strictly rejected.

### Response Payload (`201 Created` on initial creation, `200 OK` on idempotent replay)
```json
{
  "id": 101,
  "branch": 1,
  "branch_id": 1,
  "fulfillment_warehouse": 1,
  "fulfillment_warehouse_id": 1,
  "customer_id": "cust-user-101",
  "status": "PENDING",
  "total_halalas": 1695,
  "idempotency_key": "550e8400-e29b-41d4-a716-446655440000",
  "cancellation_restored": false,
  "timestamp": "2026-11-11T21:30:00Z",
  "updated_at": "2026-11-11T21:30:00Z",
  "replayed": false,
  "items": [
    {
      "id": 201,
      "product": 1,
      "product_id": 1,
      "product_name_snapshot": "خيار بلدي طازج",
      "unit_snapshot": "كجم",
      "quantity_milli": 1500,
      "unit_price_halalas": 650,
      "line_total_halalas": 975,
      "created_at": "2026-11-11T21:30:00Z"
    },
    {
      "id": 202,
      "product": 3,
      "product_id": 3,
      "product_name_snapshot": "طماطم محمية",
      "unit_snapshot": "كجم",
      "quantity_milli": 1000,
      "unit_price_halalas": 720,
      "line_total_halalas": 720,
      "created_at": "2026-11-11T21:30:00Z"
    }
  ],
  "status_history": [
    {
      "id": 301,
      "from_status": null,
      "to_status": "PENDING",
      "changed_by_actor_id": "user:101",
      "reason": "Order created via atomic checkout",
      "changed_at": "2026-11-11T21:30:00Z"
    }
  ]
}
```

### HTTP Status Code Mapping
| Status Code | Condition |
|---|---|
| `201 Created` | New order atomically created, inventory deducted, and ledger/history entries committed. |
| `200 OK` | Idempotent replay of a previously committed request with the same `(actor_id, 'CHECKOUT_ORDER', idempotency_key)` and identical canonical `request_hash` (`replayed: true`). |
| `400 Bad Request` | Missing/invalid fields, empty items, `quantity_milli <= 0`, non-fractional product ordered with fractional quantity, inactive product/branch/warehouse, product unavailable in branch, `BranchWarehouseMismatchError`, `MissingStockBalanceError`, or `SpoofedFinancialDataError`. |
| `401 Unauthorized` | Unauthenticated request or invalid/expired/revoked JWT access token. |
| `403 Forbidden` | Authenticated user is not a `CUSTOMER` (`OWNER` / `BRANCH_MANAGER`), attempts cross-store access, or attempts client identity/role/tenant spoofing (`customer_id`, `user_id`, `role`, `store_id`). |
| `404 Not Found` | Referenced `branch_id` or `product_id` does not exist (`CheckoutResourceNotFoundError`). |
| `409 Conflict` | `InsufficientStockError` (`available_milli < quantity_milli`) or `IdempotencyConflictError` (same `idempotency_key` reused with a different request payload, or conflicting header vs. body key). |
| `500 Internal Server Error` | Unexpected server error after full transaction rollback, returning a generic message without leaking stack traces or SQL internals. |

---

## 5. Server-Side Pricing Rules

1. **Integer-Only Monetary Representation:** All prices are stored and calculated in integer **Halalas** (`BigIntegerField`, `1 SAR = 100 Halalas`). Floating-point (`float`) and `Decimal` types are strictly prohibited in checkout arithmetic.
2. **Branch Price Resolution (`resolve_effective_price_halalas`):**
   - For the target `(branch_id, product_id)`, if a `BranchProductOverride` row exists and `override_price_halalas IS NOT NULL`, `unit_price_halalas = override_price_halalas`.
   - Otherwise, `unit_price_halalas = Product.price_halalas`.
3. **Line Total Calculation (`calculate_line_total_halalas`):**
   - Quantities are expressed in integer milli-units (`1000 = 1.000`).
   - Each line total is computed via exact integer half-up rounding:
     ```python
     line_total_halalas = (quantity_milli * unit_price_halalas + 500) // 1000
     ```
4. **Order Total Calculation (`calculate_order_total_halalas`):**
   - `total_halalas = sum(item.line_total_halalas for item in order_items)`
5. **Immutable `OrderItem` Snapshots:**
   - Every `OrderItem` persists `product_name_snapshot`, `unit_snapshot`, `quantity_milli`, `unit_price_halalas`, and `line_total_halalas` at checkout time. Subsequent changes to `Product.name`, `Product.unit`, `Product.price_halalas`, or `BranchProductOverride.override_price_halalas` never alter historical orders, and database-level triggers (`trg_orderitem_no_update`, `trg_orderitem_no_delete`) block any `UPDATE` or `DELETE` on `orders_orderitem`.

---

## 6. Atomic Transaction Flow

Every checkout executes inside a single atomic transaction (`with transaction.atomic():` in Django ORM / PostgreSQL; `SAVEPOINT sp_checkout_order` in SQLite Reference Engine) following the exact 13-step sequence:

1. **Check `IdempotencyKey`:** Query `IdempotencyKey` for `(actor_id, 'CHECKOUT_ORDER', idempotency_key)` (with `select_for_update()` in Django ORM). If found with matching `request_hash`, return the stored `CheckoutResult` with `replayed=True` (`200 OK`); if `request_hash` differs, raise `IdempotencyConflictError` (`409 Conflict`). Also verify `(customer_id, idempotency_key)` on `Order`.
2. **Validate User, Store, Branch, and Warehouse:** Verify `actor.role == 'CUSTOMER'`, load `Branch` (raising `CheckoutResourceNotFoundError` -> `404` if missing), verify `actor.store_id` matches `branch.store_id` when `actor.store_id` is set, verify `Branch` and `Store` are active, and resolve/validate the `Warehouse` (`warehouse.branch_id == branch.id` and `warehouse.is_active == True`).
3. **Load Products & Branch Overrides:** Canonicalize and sort `product_id`s in ascending order. Verify all requested products exist (`404` if missing) and `is_active == True` (`400` if inactive). Load `BranchProductOverride` for `(branch_id, product_id)` and verify `is_available == True` and `min_order_quantity_milli` / `max_order_quantity_milli` bounds.
4. **Deterministic Row Locking of `WarehouseStockBalance`:** Lock all required `WarehouseStockBalance` rows via:
   ```python
   WarehouseStockBalance.objects.select_for_update().filter(
       warehouse_id=warehouse.id,
       product_id__in=sorted_product_ids,
   ).order_by('warehouse_id', 'product_id')
   ```
   Raise `MissingStockBalanceError` (`400`) if any product lacks a stock balance row in the warehouse (never silently auto-creating zero balances).
5. **Validate Quantities:** Call `validate_milli_quantity` for each item to enforce positive integer milli-units, `is_fractional_allowed` divisibility by `1000`, and branch min/max quantity bounds.
6. **Validate Available Stock:** Compute `available_milli = on_hand_milli - reserved_milli`. If `available_milli < quantity_milli` or `on_hand_milli - quantity_milli < 0`, raise `InsufficientStockError` (`409 Conflict`), aborting the entire transaction before any mutation.
7. **Calculate Server-Side Prices:** Compute `unit_price_halalas`, `line_total_halalas`, and `authoritative_total_halalas`. Validate against any `expected_unit_price_halalas` / `expected_total_halalas` / `reject_client_pricing` guards.
8. **Create `Order`:** Insert `Order` with `status='PENDING'`, `total_halalas=authoritative_total_halalas`, `customer_id`, `branch_id`, `fulfillment_warehouse_id`, and `idempotency_key`.
9. **Create `OrderItem` Snapshots:** Insert immutable `OrderItem` rows in deterministic `product_id` order.
10. **Deduct `WarehouseStockBalance`:** Update `on_hand_milli = on_hand_milli - quantity_milli` and increment `version = version + 1`.
11. **Create `StockMovement` Ledger Entries:** Insert append-only `StockMovement` rows with `movement_type='CHECKOUT_DEDUCTION'`, `quantity_delta_milli=-quantity_milli`, `balance_after_milli=new_on_hand_milli`, `reference_order_id=order.id`, `actor_id=actor.actor_id`, and deterministic `idempotency_key=f"checkout:order-{order.id}:item-{order_item.id}"`.
12. **Create `OrderStatusHistory`:** Insert initial append-only `OrderStatusHistory` row (`from_status=None`, `to_status='PENDING'`, `changed_by_actor_id=actor.actor_id`).
13. **Persist `IdempotencyKey` Result:** Insert `IdempotencyKey` with `response_status_code=201`, canonical `request_hash`, serialized response snapshot, and `reference_order_id=order.id`.

If any step raises an exception, the entire transaction / savepoint rolls back atomically, leaving zero partial rows and zero stock mutations.

---

## 7. Concurrency & Locking Strategy

1. **Deadlock-Free Lock Ordering:** Multi-item checkouts sort all item `product_id`s in ascending order and acquire row-level locks via `.select_for_update().order_by('warehouse_id', 'product_id')`. Two concurrent transactions ordering overlapping sets of products (e.g., `[Product 2, Product 1]` and `[Product 1, Product 2]`) always lock `(warehouse_id, 1)` before `(warehouse_id, 2)`, eliminating lock-order inversion deadlocks in PostgreSQL.
2. **Last-Unit Race Protection:** When two concurrent checkouts compete for the last `1000` milli-units of stock:
   - Transaction A acquires the row lock on `WarehouseStockBalance`, deducts `on_hand_milli` from `1000` to `0`, increments `version`, and commits (`201 Created`).
   - Transaction B unblocks, reads the updated `on_hand_milli == 0`, sees `available_milli (0) < requested (1000)`, raises `InsufficientStockError`, rolls back completely, and returns `409 Conflict`.
3. **Database Check Constraints as Final Safety Net:** `WarehouseStockBalance` enforces `stock_balance_on_hand_milli_gte_0` (`on_hand_milli >= 0`) and `stock_balance_reserved_lte_on_hand` (`reserved_milli <= on_hand_milli`) at the database level.

---

## 8. Idempotency Strategy

1. **Dual-Table Scoping:**
   - `IdempotencyKey`: `UniqueConstraint(fields=['actor_id', 'operation', 'idempotency_key'], name='uniq_actor_operation_idempotency_key')` where `operation = 'CHECKOUT_ORDER'`.
   - `Order`: `UniqueConstraint(fields=['customer_id', 'idempotency_key'], name='uniq_order_customer_idempotency_key')`.
   - Scoping by `actor_id` / `customer_id` ensures two different customers can independently use the same client-side idempotency key string without cross-customer interference or order leakage.
2. **Canonical Payload Fingerprint (`compute_checkout_request_hash`):**
   - Computes SHA-256 over canonical JSON `{operation, customer_id, branch_id, fulfillment_warehouse_id, items: [{product_id, quantity_milli}, ...]}` sorted by `product_id`.
3. **Replay vs. Conflict Behavior:**
   - **Same key + same `request_hash`:** Returns `200 OK` with the original order payload and `replayed: true`. Stock is never deducted a second time, and no duplicate `Order` or `StockMovement` is created.
   - **Same key + different `request_hash`:** Raises `IdempotencyConflictError` -> `409 Conflict`.
4. **Concurrent Duplicate Race Handling:**
   - If two identical requests from the same customer arrive concurrently and race past the initial lookup, the second transaction catches the database `IntegrityError` on `uniq_actor_operation_idempotency_key` / `uniq_order_customer_idempotency_key`, rolls back its savepoint, reads the winner's committed `IdempotencyKey` row, verifies `request_hash`, and returns `replayed=True` (`200 OK`) — or raises `IdempotencyConflictError` (`409 Conflict`) if the payload differed.

---

## 9. Inventory Ledger & Audit Trail

1. **`StockMovement` Ledger (`products_stockmovement`):**
   - Every checkout item deduction creates an immutable `StockMovement` record with:
     - `movement_type = 'CHECKOUT_DEDUCTION'`
     - `quantity_delta_milli = -quantity_milli` (strictly negative for checkout deductions)
     - `balance_after_milli = new_on_hand_milli` (matches `WarehouseStockBalance.on_hand_milli` after deduction)
     - `reference_order_id = order.id`
     - `actor_id = actor.actor_id`
     - `idempotency_key = f"checkout:order-{order.id}:item-{order_item.id}"`
2. **`OrderStatusHistory` Trail (`orders_orderstatushistory`):**
   - Every created order records an initial transition `from_status = None -> to_status = 'PENDING'` with `changed_by_actor_id = actor.actor_id`.
3. **Database-Level Immutability:**
   - Both `AppendOnlyManager` / `AppendOnlyModel` at the ORM layer and `BEFORE UPDATE` / `BEFORE DELETE` SQL triggers (`0002_audit_ledger_append_only_triggers.py` and `install_sqlite_append_only_triggers`) prevent modifying or deleting `StockMovement`, `OrderItem`, or `OrderStatusHistory` rows.

---

## 10. Test Results

### Executed Test Commands
```bash
PYTHONPATH=backend python3 -m unittest -v apps.orders.tests apps.products.tests
PYTHONPATH=backend python3 -m unittest -v apps.orders.tests.MicroTask1D2AtomicCheckoutEngineTests
```

### Summary of Execution
- **Total Backend Tests Discovered:** `81`
- **Executed & Passed (`OK`):** `59`
- **Failed / Errors:** `0`
- **Skipped (Django ORM / PostgreSQL runtime not installed in Android build container):** `22`

### Microtask 1D.2 Mandatory 16-Scenario Verification Matrix (`MicroTask1D2AtomicCheckoutEngineTests`)

|Req #|Required Scenario|Test Method in `apps.orders.tests.MicroTask1D2AtomicCheckoutEngineTests`|Status|
|---|---|---|---|
|1|إنشاء طلب صحيح (`201 Created`, `PENDING` status & history)|`test_01_valid_order_creation_and_pending_status_history` & `test_17_http_contract_status_codes_on_post_api_v1_orders`|`PASS`|
|2|حساب السعر من الخادم (`Product.price_halalas` + `BranchProductOverride` + half-up rounding)|`test_02_server_side_price_calculation_with_branch_override_and_half_up_rounding`|`PASS`|
|3|رفض وتجاهل سعر مزور من العميل (`effective_price_halalas`, negative/non-int price, `expected_unit_price_halalas` mismatch)|`test_03_rejection_and_ignoring_of_spoofed_client_unit_price`|`PASS`|
|4|رفض وتجاهل إجمالي مزور من العميل (`subtotal_halalas`, negative `total_halalas`, `expected_total_halalas` mismatch)|`test_04_rejection_and_ignoring_of_spoofed_client_total`|`PASS`|
|5|رفض كمية صفرية أو سالبة أو كسرية غير مسموحة أو مكررة|`test_05_rejection_of_zero_negative_fractional_or_duplicate_quantities`|`PASS`|
|6|رفض منتج غير نشط (`Product.is_active=False`)|`test_06_rejection_of_inactive_product`|`PASS`|
|7|رفض منتج غير متاح في الفرع (`BranchProductOverride.is_available=False` / min-max bounds)|`test_07_rejection_of_product_unavailable_in_branch_or_violating_min_max_bounds`|`PASS`|
|8|رفض مخزون غير كافٍ (`InsufficientStockError` / HTTP `409 Conflict`)|`test_08_rejection_of_insufficient_stock_and_09_prevention_of_negative_stock`|`PASS`|
|9|منع المخزون السالب (`on_hand_milli >= reserved_milli >= 0` invariant)|`test_08_rejection_of_insufficient_stock_and_09_prevention_of_negative_stock`|`PASS`|
|10|منع تكرار الطلب بنفس `idempotency_key` (`200 OK`, `replayed=True`) وعزل مفاتيح العملاء|`test_10_idempotency_replay_prevents_duplicate_order_and_isolates_customers`|`PASS`|
|11|رفض استخدام نفس `idempotency_key` مع بيانات مختلفة (`409 Conflict`)|`test_11_idempotency_key_reused_with_different_payload_rejected_with_conflict`|`PASS`|
|12|طلبان متزامنان على آخر قطعة مخزون (واحد ينجح `201` والآخر يُرفض `409` والرصيد النهائي `0`)|`test_12_two_concurrent_orders_competing_for_last_stock_unit`|`PASS`|
|13|Rollback كامل إذا فشل إنشاء `StockMovement` في منتصف العملية|`test_13_full_atomic_rollback_if_stock_movement_creation_fails`|`PASS`|
|14|تطابق `StockMovement` (`quantity_delta_milli`, `balance_after_milli`, `reference_order_id`) مع خصم المخزون|`test_14_stock_movement_ledger_matches_stock_deduction_and_reference_order`|`PASS`|
|15|منع الوصول لمخزون متجر أو فرع غير مصرح به ورفض تزوير الهوية أو الدور (`403 Forbidden`)|`test_15_cross_branch_warehouse_and_cross_store_isolation_and_spoofing_protection` & `test_17_http_contract_status_codes_on_post_api_v1_orders`|`PASS`|
|16|التحقق من أن `OrderItem` يحفظ السعر والاسم والوحدة التاريخية كـ Snapshot غير قابل للتغيير|`test_16_order_item_preserves_immutable_snapshots_after_catalog_mutation`|`PASS`|

---

## 11. Risks & Remaining Limitations

1. **Django ORM & PostgreSQL Live Container Execution Not Available in Android Build Container:**
   - As established in Microtask 1C.1 and 1D.1, this cloud workspace is an Android build container with Python `3.11.2` and `sqlite3`, without `django`, `djangorestframework`, or a live PostgreSQL server installed.
   - All 59 standalone SQLite Reference Engine, AST model/migration structure, and multithreaded HTTP contract tests were executed and passed (`0` failures), while the 22 `@unittest.skipUnless(DJANGO_AVAILABLE, ...)` tests must be executed in a provisioned Django + PostgreSQL CI/staging environment (`python manage.py migrate && python manage.py test apps.orders apps.products`).
2. **Android Checkout Client Integration Deferred to Subsequent Microtask:**
   - Per Microtask 1D.2 scope rules, Android client checkout integration and electronic payment gateway integration were intentionally not touched in this task.

---

## 12. Final Verdict

**`CONDITIONALLY VERIFIED`**

- The atomic server-side checkout engine for `POST /api/v1/orders/` is **fully implemented** and verified across all 16 mandatory domain, pricing, spoofing, idempotency, concurrency, rollback, ledger, isolation, and live HTTP contract test scenarios in the Python + SQLite Reference Engine (`59/59` executable tests passing).
- Full `IMPLEMENTED AND VERIFIED` certification requires executing the Django ORM + PostgreSQL test suite (`python manage.py test apps.orders apps.products`) in a live PostgreSQL environment where row-level `SELECT ... FOR UPDATE` locks run against PostgreSQL.
