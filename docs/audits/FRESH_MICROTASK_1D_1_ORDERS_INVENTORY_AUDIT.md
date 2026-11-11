# FRESH — MICRO TASK 1D.1
## Server-Side Orders & Inventory — Existing Backend Audit and API Contract Design

**Document ID:** `FRESH_MICROTASK_1D_1_ORDERS_INVENTORY_AUDIT`  
**Phase:** `1D.1 — Existing Backend Audit and API Contract Design (Read-Only Gate)`  
**Prerequisite Certification:** `FRESH_MICROTASK_1C_1_AUTH_CERTIFICATION` (`CONDITIONALLY CERTIFIED`)  
**Target Architecture:** Django REST Framework + PostgreSQL (Authoritative Server) + Android Room (Local Read Cache)  

---

## Classification Legend

Every finding in this audit is tagged with one or more of the following mandatory verification classifications:

- **`VERIFIED IN CODE`** — Implementation confirmed directly by inspecting the repository source files with exact file and line citations.
- **`TESTED AND PASSED`** — Verified by automated tests actually executed in this container (`PYTHONPATH=backend python3 -m unittest` SQLite Reference Engine or `./gradlew :app:testDebugUnitTest`).
- **`NOT TESTED`** — Code exists in the repository, but live runtime execution in that specific engine (e.g., Django ORM / PostgreSQL `select_for_update()`) was skipped or not executed in this build container.
- **`MISSING`** — Not yet implemented in the repository; must be built or wired in Microtask 1D.2.
- **`RISK`** — Architectural, concurrency, contract, or operational vulnerability that must be addressed or mitigated during Microtask 1D.2.

---

## 1. Executive Summary

1. **Backend Orders & Inventory Core is Already Substantially Built (`VERIFIED IN CODE`, `TESTED AND PASSED` on SQLite Reference Engine / `NOT TESTED` on Live PostgreSQL):**
   - Unlike typical greenfield scaffolds, the Django backend (`backend/apps/products/` and `backend/apps/orders/`) already implements a production-grade multi-tenant retail domain model (`Store` → `Branch` → `Warehouse`), integer currency in **Halalas** (`1 SAR = 100 halalas`), integer quantities in **Milli-units** (`1000 = 1.000 unit`), append-only stock movement ledgers (`StockMovement`), immutable order item snapshots (`OrderItem`), order status history (`OrderStatusHistory`), and payload-hashed idempotency (`IdempotencyKey`).
   - All atomic checkout, cancellation, status transition, and inventory ledger operations are implemented with dual-engine parity in `backend/apps/orders/services.py`:
     - **Django ORM / PostgreSQL path** (`_checkout_order_django`, `_cancel_order_django`, `_transition_order_status_django`) using `transaction.atomic()` and deterministic `select_for_update().order_by('warehouse_id', 'product_id')`.
     - **SQLite Reference Engine path** (`_checkout_order_sqlite`, `_cancel_order_sqlite`, `_transition_order_status_sqlite`) using `SAVEPOINT` transactions and SQLite `BEFORE UPDATE` / `BEFORE DELETE` immutability triggers.
2. **Android Client Still Executes Checkout & Inventory Exclusively Locally in Room (`VERIFIED IN CODE`, `MISSING` Server Wiring, `RISK`):**
   - Although `FreshApiService.kt` defines `@POST("orders/") suspend fun createOrder(@Body request: OrderRequest): OrderResponse` and `ApiModels.kt` defines `OrderRequest`, `OrderResponse`, `OrderItemDto`, and `OrderStatusHistoryDto`, **`OrderRepository.kt` does not inject or call `FreshApiService` at all**.
   - `Injection.provideOrderRepository` (`app/src/main/java/com/example/di/Injection.kt:98-108`) only provides `AppDatabase` and `sharedInventoryMutex`. Consequently, Android checkout (`OrderRepository.executeLocalCheckout`), order cancellation (`OrderRepository.cancelOrderAndRestoreStock`), and stock deduction operate purely against local Room tables (`products`, `orders`, `order_items`, `inventory_Adjustment_logs`) rather than calling Django.
   - `FreshApiService.kt` is also **`MISSING`** endpoints for `GET /api/v1/orders/`, `GET /api/v1/orders/{id}/`, `POST /api/v1/orders/{id}/cancel/`, and `POST /api/v1/orders/{id}/transition-status/`.
3. **Catalog Stock Visibility & Order Breakdown Alignment Gaps (`MISSING`, `RISK`):**
   - `ProductSerializer` (`backend/apps/products/serializers.py`) and `ProductDto` (`ApiModels.kt`) expose catalog metadata and `effective_price_halalas`, but do **not** currently expose branch/warehouse available stock (`available_quantity_milli` / `in_stock`). As a result, `ProductRepository.refreshProducts()` preserves local Room seed stock (`stockQuantity = existing?.stockQuantity ?: 50`) rather than reflecting server-authoritative stock.
   - Android's local Room `Order` entity tracks `subtotalHalalas`, `deliveryFeeHalalas` (`1500` halalas = 15.00 SAR for orders `< 100 SAR`), `discountHalalas`, `deliveryAddressSnapshot`, and `paymentModeNote`, whereas Django's `Order` model (`backend/apps/orders/models.py:54-162`) currently stores only `total_halalas` (the sum of `OrderItem.line_total_halalas`).
4. **Gate Verdict:** **`READY FOR 1D.2`** — The backend domain and transactional services are well-structured and ready to be wired to Android in Microtask 1D.2 without breaking existing Room cache tables or JWT authentication.

---

## 2. Existing Backend Architecture

| Component | File Path | Status & Classification | Responsibilities Verified in Code |
| :--- | :--- | :--- | :--- |
| **Products & Inventory Models** | `backend/apps/products/models.py` (526 lines) | `VERIFIED IN CODE` | Defines `Store`, `Branch`, `Warehouse`, `Category`, `Product`, `BranchProductOverride`, `WarehouseStockBalance`, `StockMovement`, and `AppendOnlyManager` / `ImmutableRecordError`. *(Note: There is no separate `backend/apps/inventory/` directory; all inventory models live inside `backend/apps/products/models.py`.)* |
| **Products Views & Serializers** | `backend/apps/products/views.py`, `backend/apps/products/serializers.py` | `VERIFIED IN CODE`, `TESTED AND PASSED` | Public guest `GET /api/v1/products/` and `GET /api/v1/categories/` (`AllowAny` on `list`/`retrieve`), with branch-scoped override resolution (`?branch_id=`) and RBAC-protected mutations. |
| **Orders, Ledger & Auth Models** | `backend/apps/orders/models.py` (723 lines) | `VERIFIED IN CODE` | Defines `Order`, `OrderItem`, `OrderStatusHistory`, `IdempotencyKey`, `UserIdentityProfile`, `BranchRoleAssignment`, `AuthSessionToken`, and `AuthThrottleEvent`. |
| **Transactional Order & Stock Services** | `backend/apps/orders/services.py` (1,739 lines) | `VERIFIED IN CODE`, `TESTED AND PASSED` (SQLite) / `NOT TESTED` (PostgreSQL) | Implements `checkout_order`, `cancel_order`, `transition_order_status`, `record_stock_movement`, `seed_initial_stock`, `resolve_effective_price_halalas`, `calculate_line_total_halalas`, and `validate_milli_quantity`. |
| **RBAC & Object Permissions** | `backend/apps/orders/permissions.py` (250 lines) | `VERIFIED IN CODE`, `TESTED AND PASSED` | Implements `AuthenticatedActorPermission`, `CatalogOrInventoryPermission`, `StockMovementPermission`, `OrderPermission`, and `OrderObjectPermission` with strict tenant/branch/customer scoping. |
| **DRF ViewSets & Endpoints** | `backend/apps/orders/views.py` (497 lines), `backend/apps/orders/urls.py` (28 lines) | `VERIFIED IN CODE`, `TESTED AND PASSED` (SQLite) | Exposes `OrderViewSet` (`/api/v1/orders/`, `/api/v1/orders/checkout/`, `/api/v1/orders/{id}/cancel/`, `/api/v1/orders/{id}/transition-status/`), `StockMovementViewSet` (`/api/v1/stock-movements/`, `/api/v1/stock-movements/seed/`), and Auth endpoints (`/api/v1/auth/*`). |
| **Order & Stock Serializers** | `backend/apps/orders/serializers.py` (186 lines) | `VERIFIED IN CODE` | Defines `OrderItemInputSerializer`, `OrderCheckoutSerializer`, `OrderCancelSerializer`, `OrderStatusTransitionSerializer`, `StockMovementCreateSerializer`, `StockInitialSeedSerializer`, `OrderItemReadSerializer`, `OrderStatusHistoryReadSerializer`, and `OrderReadSerializer`. |
| **Live Test HTTP Server** | `backend/apps/orders/test_http_server.py` | `VERIFIED IN CODE`, `MISSING` Order Routes | Bridges Android Retrofit tests over localhost HTTP to the Python SQLite Reference Engine for `/api/v1/auth/*`, `/api/v1/products`, and `/api/v1/categories`, but **does not yet route `/api/v1/orders/*`**. |

---

## 3. Existing Models and Relationships

### 3.1 Store, Branch, Warehouse, and Product Hierarchy (`backend/apps/products/models.py`)

| Model | DB Table | Key Fields & Types | Constraints & Relationships | Classification |
| :--- | :--- | :--- | :--- | :--- |
| **`Store`** | `products_store` (lines 51–79) | `id`, `code` (`CharField(64)`, unique), `name` (`CharField(255)`), `is_active` (`BooleanField`), `created_at`, `updated_at` | Top-level tenant boundary. `code` cannot be empty (`store_code_not_empty`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **`Branch`** | `products_branch` (lines 81–119) | `id`, `store` (`FK -> Store`, `PROTECT`), `code` (`CharField(64)`), `name`, `city`, `address`, `is_active`, `created_at`, `updated_at` | `UniqueConstraint(fields=['store', 'code'])`. Deletion of `Store` is blocked (`on_delete=PROTECT`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **`Warehouse`** | `products_warehouse` (lines 121–159) | `id`, `branch` (`FK -> Branch`, `PROTECT`), `code` (`CharField(64)`), `name`, `is_primary_fulfillment` (`BooleanField`), `is_active`, `created_at`, `updated_at` | `UniqueConstraint(fields=['branch', 'code'])`. Each branch has one primary fulfillment warehouse used by default at checkout. | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **`Category`** | `products_category` (lines 161–189) | `id`, `name` (unique), `display_order` (`PositiveIntegerField >= 0`), `is_active`, `created_at`, `updated_at` | Non-empty `name`, `display_order >= 0`. | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **`Product`** | `products_product` (lines 191–268) | `id`, `sku` (`CharField(64)`, unique), `barcode` (`CharField(64)`, nullable unique), `name`, `description`, `category` (`FK -> Category`, `PROTECT`), `price_halalas` (`BigIntegerField >= 0`), `unit` (`kg`, `g`, `piece`, `box`, `bundle`, `pack`, `liter`), `is_fractional_allowed` (`BooleanField`), `is_active` | **Does NOT store global stock on `Product`**. Stock is strictly tracked per `(warehouse, product)` in `WarehouseStockBalance`. | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **`BranchProductOverride`** | `products_branchproductoverride` (lines 270–341) | `id`, `branch` (`FK -> Branch`, `PROTECT`), `product` (`FK -> Product`, `PROTECT`), `override_price_halalas` (`BigIntegerField >= 0`, nullable), `is_available` (`BooleanField`), `min_order_quantity_milli` (`BigIntegerField > 0`, default `1000`), `max_order_quantity_milli` (`BigIntegerField`, nullable) | `UniqueConstraint(fields=['branch', 'product'])`; check constraint `max_order_quantity_milli IS NULL OR max_order_quantity_milli >= min_order_quantity_milli`. | `VERIFIED IN CODE`, `TESTED AND PASSED` |

### 3.2 Inventory Balance & Immutable Stock Ledger (`backend/apps/products/models.py`)

| Model | DB Table | Key Fields & Types | Constraints & Immutability Guarantees | Classification |
| :--- | :--- | :--- | :--- | :--- |
| **`WarehouseStockBalance`** | `products_warehousestockbalance` (lines 343–410) | `id`, `warehouse` (`FK -> Warehouse`, `PROTECT`), `product` (`FK -> Product`, `PROTECT`), `on_hand_milli` (`BigIntegerField >= 0`), `reserved_milli` (`BigIntegerField >= 0`), `version` (`BigIntegerField >= 1`), `updated_at` | `UniqueConstraint(fields=['warehouse', 'product'])`. Check constraints: `on_hand_milli >= 0`, `reserved_milli >= 0`, `reserved_milli <= on_hand_milli`, `version >= 1`. Computed property `available_milli = on_hand_milli - reserved_milli`. | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **`StockMovement`** | `products_stockmovement` (lines 435–526) | `id`, `warehouse` (`FK -> Warehouse`, `PROTECT`), `product` (`FK -> Product`, `PROTECT`), `movement_type` (`INITIAL_SEED`, `RESTOCK`, `CHECKOUT_DEDUCTION`, `CANCELLATION_RESTORATION`, `MANUAL_ADJUSTMENT`, `WASTAGE`, `TRANSFER_IN`, `TRANSFER_OUT`), `quantity_delta_milli` (`BigIntegerField != 0`), `balance_after_milli` (`BigIntegerField >= 0`), `reference_order` (`FK -> orders.Order`, `PROTECT`, nullable), `actor_id` (`CharField(128)`), `reason` (`TextField`), `idempotency_key` (`CharField(128)`, unique, nullable), `created_at` | **Append-Only Ledger**: Uses `AppendOnlyManager` (blocks `.update()` and `.delete()`), overrides `save()` and `delete()` to raise `ImmutableRecordError` when `pk is not None`, and installs DB-level `BEFORE UPDATE` / `BEFORE DELETE` triggers in SQLite (`services.py:254-267`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |

### 3.3 Order, OrderItem, Status History, and Idempotency (`backend/apps/orders/models.py`)

| Model | DB Table | Key Fields & Types | Constraints & Immutability Guarantees | Classification |
| :--- | :--- | :--- | :--- | :--- |
| **`Order`** | `orders_order` (lines 54–162) | `id`, `branch` (`FK -> Branch`, `PROTECT`), `fulfillment_warehouse` (`FK -> Warehouse`, `PROTECT`, nullable), `customer_id` (`CharField(128)`, indexed), `status` (`PENDING`, `CONFIRMED`, `PROCESSING`, `COMPLETED`, `CANCELLED`), `total_halalas` (`BigIntegerField >= 0`), `idempotency_key` (`CharField(128)`, unique, nullable), `cancellation_restored` (`BooleanField`, default `False`), `timestamp`, `updated_at` | Enforces `VALID_TRANSITIONS`: `PENDING -> {CONFIRMED, CANCELLED}`, `CONFIRMED -> {PROCESSING, CANCELLED}`, `PROCESSING -> {COMPLETED, CANCELLED}`, `COMPLETED -> ∅`, `CANCELLED -> ∅`. Blocks direct `delete()` (`ImmutableRecordError`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **`OrderItem`** | `orders_orderitem` (lines 164–298) | `id`, `order` (`FK -> Order`, `PROTECT`, `related_name='items'`), `product` (`FK -> Product`, `PROTECT`), `product_name_snapshot` (`CharField(255)`), `unit_snapshot` (`CharField(16)`), `quantity_milli` (`BigIntegerField > 0`), `unit_price_halalas` (`BigIntegerField >= 0`), `line_total_halalas` (`BigIntegerField >= 0`) | **Immutable Snapshot**: `AppendOnlyManager` + `save()`/`delete()` guards + DB triggers (`services.py:269-282`). Validates in `clean()` that `line_total_halalas == (quantity_milli * unit_price_halalas + 500) // 1000`. | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **`OrderStatusHistory`** | `orders_orderstatushistory` (lines 300–371) | `id`, `order` (`FK -> Order`, `PROTECT`, `related_name='status_history'`), `from_status` (`CharField(20)`, nullable), `to_status` (`CharField(20)`), `changed_by_actor_id` (`CharField(128)`), `reason` (`TextField`), `changed_at` | **Append-Only Audit Trail**: `AppendOnlyManager` + `save()`/`delete()` guards + DB triggers (`services.py:284-297`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **`IdempotencyKey`** | `orders_idempotencykey` (lines 373–436) | `id`, `actor_id` (`CharField(128)`), `operation` (`CHECKOUT_ORDER`, `CANCEL_ORDER`), `idempotency_key` (`CharField(128)`), `request_hash` (`CharField(64)` SHA-256), `response_status_code` (`PositiveSmallIntegerField`), `response_payload` (`JSONField`), `reference_order` (`FK -> Order`, `SET_NULL`, nullable), `created_at` | `UniqueConstraint(fields=['actor_id', 'operation', 'idempotency_key'])`. Prevents duplicate order creation or duplicate cancellation on client network retries. | `VERIFIED IN CODE`, `TESTED AND PASSED` |

---

## 4. Checkout Logic Findings

We audited the 14 mandatory server-side checkout checkpoints across `backend/apps/orders/services.py` (`checkout_order`, `_checkout_order_django`, `_checkout_order_sqlite`, lines 498–1142), `backend/apps/orders/views.py` (`OrderViewSet.create` & `OrderViewSet.checkout`, lines 349–412), and `backend/apps/orders/serializers.py` (`OrderCheckoutSerializer`, lines 26–52):

| # | Checkout Checkpoint | Implementation Details & File/Line Evidence | Status & Classification |
| :-: | :--- | :--- | :--- |
| **1** | **Receive order from authenticated `CUSTOMER`** | `OrderViewSet._handle_checkout` (`views.py:349-388`) requires a valid JWT access token, resolves `actor = get_request_actor(request)`, enforces `enforce_no_client_identity_spoofing(actor, request.data)` (`auth.py:1805-1867`), and `checkout_order` (`services.py:534-544`) verifies `actor.role == 'CUSTOMER'` and `customer_id == actor.customer_id`. | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **2** | **Validate branch & fulfillment warehouse** | `_checkout_order_django` (`services.py:621-657`) and `_checkout_order_sqlite` (`services.py:867-915`) verify `Branch` exists, `branch.is_active == True`, `branch.store.is_active == True`, and resolve either the explicit `fulfillment_warehouse_id` (verifying `warehouse.branch_id == branch.id` and `warehouse.is_active == True`) or the branch's active primary fulfillment warehouse (`is_primary_fulfillment=True, is_active=True`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **3** | **Load products from database** | Loads products in deterministic primary-key order (`Product.objects.filter(id__in=sorted_product_ids)` at `services.py:663-668`, or SQLite `SELECT ... WHERE id IN (...) ORDER BY id ASC` at `services.py:917-931`). Any missing product ID raises `ProductNotAvailableError` (`400 Bad Request`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **4** | **Verify product active status & branch availability** | Checks `if not product.is_active` (`services.py:694-695, 963-964`) and checks `BranchProductOverride`: if `override is not None and not override.is_available`, raises `ProductNotAvailableError("Product X is not available in branch Y")` (`services.py:697-701, 966-967`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **5** | **Validate quantities (`quantity_milli`)** | Rejects empty items list (`services.py:553-554`) and duplicate `product_id` lines (`services.py:568-571`). Calls `validate_milli_quantity` (`services.py:170-217`) to verify `quantity_milli` is an integer `> 0`, is a multiple of `1000` when `product.is_fractional_allowed == False`, and respects `min_order_quantity_milli` and `max_order_quantity_milli`. | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **6** | **Calculate prices from server database (never client)** | `OrderItemInputSerializer` (`serializers.py:16-23`) only accepts `product_id` and `quantity_milli`. Even if a client sends `unit_price_halalas`, `price_halalas`, `line_total_halalas`, or `total_halalas` in raw JSON, `checkout_order` (`services.py:709-728, 976-995`) ignores them completely and computes `unit_price_halalas = resolve_effective_price_halalas(product.price_halalas, override.override_price_halalas)`. | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **7** | **Calculate discounts, delivery fees, and delivery address if present** | **Gap Identified (`MISSING` / `RISK`):** Currently, `Order` (`orders/models.py:54-162`) only stores `total_halalas` (computed as the sum of line totals in `services.py:739-741`). It does **not** store or calculate `subtotal_halalas`, `delivery_fee_halalas`, `discount_halalas`, `delivery_address`, or `payment_mode_note`. Meanwhile, Android's local `OrderRepository.kt` (lines 410–416, 466–479) computes `deliveryFeeHalalas = if (subtotalHalalas >= 10000L) 0L else 1500L` and stores `deliveryAddressSnapshot`. | `MISSING`, `RISK` |
| **8** | **Calculate `total_halalas` on the server** | Line totals are calculated using exact integer half-up rounding: `calculate_line_total_halalas(qty_milli, unit_price_halalas) = (qty_milli * unit_price_halalas + 500) // 1000` (`services.py:143-167`), and summed into `total_halalas` (`services.py:739-741, 1006`). No floating-point math is used anywhere. | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **9** | **Create `Order` and `OrderItem` rows** | Inserts `Order(branch=..., fulfillment_warehouse=..., customer_id=..., status='PENDING', total_halalas=..., idempotency_key=...)` and iterates through each item to create `OrderItem` rows (`services.py:743-765, 1008-1043`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **10** | **Save immutable product & price snapshot in `OrderItem`** | Each `OrderItem` stores `product_name_snapshot=product.name`, `unit_snapshot=product.unit`, `quantity_milli`, `unit_price_halalas`, and `line_total_halalas` (`services.py:755-764, 1030-1042`). Subsequent changes to `Product.name`, `Product.unit`, or `Product.price_halalas` do not alter historical orders. | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **11** | **Deduct stock from `WarehouseStockBalance`** | Verifies `balance.available_milli >= qty_milli` (where `available_milli = on_hand_milli - reserved_milli`), otherwise raises `InsufficientStockError` (`409 Conflict`). Deducts `balance.on_hand_milli -= qty_milli` and increments `balance.version += 1` (`services.py:712-719, 767-770, 978-986, 1046-1055`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **12** | **Create `StockMovement` record** | Creates an append-only `StockMovement` with `movement_type='CHECKOUT_DEDUCTION'`, `quantity_delta_milli=-qty_milli`, `balance_after_milli=new_on_hand`, `reference_order=order`, `actor_id=actor.actor_id`, `reason=f'Order #{order.id} checkout deduction'`, and `idempotency_key=f'checkout:order-{order.id}:item-{order_item.id}'` (`services.py:772-783, 1057-1075`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **13** | **Create `OrderStatusHistory` record** | Inserts initial `OrderStatusHistory(order=order, from_status=None, to_status='PENDING', changed_by_actor_id=actor.actor_id, reason='Order created via atomic checkout')` (`services.py:798-805, 1091-1102`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |
| **14** | **Save `IdempotencyKey` record** | Computes SHA-256 `request_hash` over normalized `{branch_id, customer_id, fulfillment_warehouse_id, items}`, and inserts `IdempotencyKey(actor_id=actor.actor_id, operation='CHECKOUT_ORDER', idempotency_key=..., request_hash=..., response_status_code=201, response_payload=payload, reference_order=order)` inside the same transaction (`services.py:831-840, 1126-1139`). | `VERIFIED IN CODE`, `TESTED AND PASSED` |

### Atomicity Verification (`transaction.atomic()`)

- **Does the entire checkout run inside a single atomic transaction?**  
  **Yes (`VERIFIED IN CODE`, `TESTED AND PASSED` on SQLite / `NOT TESTED` on live PostgreSQL):**
  - In `_checkout_order_django` (`backend/apps/orders/services.py:598`), steps 1 through 14 (including `IdempotencyKey` lookup/creation, `WarehouseStockBalance` locking and deduction, `Order` / `OrderItem` creation, `StockMovement` creation, and `OrderStatusHistory` creation) execute inside a single `with transaction.atomic():` block.
  - In `_checkout_order_sqlite` (`backend/apps/orders/services.py:846-1142`), the exact same sequence executes inside `SAVEPOINT sp_checkout_order`, rolling back to `sp_checkout_order` on any domain or database exception (`services.py:1140-1142`).
  - **Verified Rollback Behavior:** Automated test `test_multi_item_checkout_partial_insufficient_stock_rolls_back_all_items` (`backend/apps/orders/tests.py:1149-1187`) proves that when item #2 in a multi-item order has insufficient stock, item #1's stock deduction, the `Order`, `OrderItem`, `StockMovement`, and `OrderStatusHistory` rows are completely rolled back with zero partial state left behind (`TESTED AND PASSED`).

---

## 5. Inventory Integrity Findings

We evaluated all 5 critical inventory integrity scenarios against `backend/apps/orders/services.py` and `backend/apps/orders/tests.py`:

### Scenario 1: Concurrent Order Placement for Last Remaining Stock
- **How the Backend Handles It:**
  - **PostgreSQL / Django ORM (`VERIFIED IN CODE`, `NOT TESTED` on live PostgreSQL):** `_checkout_order_django` (`services.py:676-680`) locks all required `WarehouseStockBalance` rows in deterministic ascending order:
    ```python
    balances_qs = (
        WarehouseStockBalance.objects.select_for_update()
        .filter(warehouse=warehouse, product_id__in=sorted_product_ids)
        .order_by('warehouse_id', 'product_id')
    )
    ```
    When two concurrent transactions attempt to purchase the last `1000` milli-units of a product, PostgreSQL row-level locking serializes the second transaction until the first commits; the second transaction then reads the updated `on_hand_milli = 0` and raises `InsufficientStockError` (`409 Conflict`), rolling back cleanly. Deterministic ordering by `('warehouse_id', 'product_id')` prevents AB-BA deadlocks when two carts contain overlapping products in different orders (`VERIFIED IN CODE`, `TESTED AND PASSED` via deterministic lock-order spy test `test_django_orm_checkout_locks_balances_in_sorted_product_order` in `orders/tests.py:1686-1755`).
  - **SQLite Reference Engine (`VERIFIED IN CODE`, `TESTED AND PASSED`):** Uses `SAVEPOINT` + DB `CHECK (on_hand_milli >= 0)` constraint + `version = version + 1`, tested in `test_concurrent_checkout_last_unit_allows_only_one_winner` (`orders/tests.py:1239-1305`).

### Scenario 2: Order Cancellation After Stock Deduction
- **How the Backend Handles It (`VERIFIED IN CODE`, `TESTED AND PASSED` on SQLite / `NOT TESTED` on live PostgreSQL):**
  - `cancel_order` (`services.py:1244-1616`) locks the `Order` row (`Order.objects.select_for_update().get(id=order_id)` at `services.py:1303`), verifies the order is in a cancellable state (`PENDING`, `CONFIRMED`, or `PROCESSING`), locks the `WarehouseStockBalance` rows in deterministic `('warehouse_id', 'product_id')` order (`services.py:1444-1451`), restores `balance.on_hand_milli += item.quantity_milli`, increments `balance.version += 1`, inserts a `StockMovement` with `movement_type='CANCELLATION_RESTORATION'` and `quantity_delta_milli=+item.quantity_milli`, updates `order.status = 'CANCELLED'` and `order.cancellation_restored = True`, and writes an `OrderStatusHistory` entry.

### Scenario 3: Repeated Order Cancellation (Double-Restoration Prevention)
- **How the Backend Handles It (`VERIFIED IN CODE`, `TESTED AND PASSED`):**
  - Protected by **four independent defense-in-depth layers** (`services.py:1317-1321, 1465-1474`):
    1. **Idempotency Replay:** If the same `(actor_id, 'CANCEL_ORDER', idempotency_key)` and payload hash are re-submitted, returns the cached `200 OK` response without touching stock (`services.py:1281-1299`).
    2. **State Machine Check:** If a different `idempotency_key` (or no key) is used on an already-cancelled order, `order.can_transition_to('CANCELLED')` is `False` and `order.cancellation_restored` is `True`, raising `DuplicateCancellationError` (`409 Conflict`, code `order_already_cancelled`).
    3. **Ledger Existence Check:** Queries `StockMovement` for any existing `CANCELLATION_RESTORATION` row referencing `order.id`; if found, raises `DuplicateCancellationError`.
    4. **Database Unique Constraint:** Every restoration movement sets `idempotency_key = f"cancel-restore:order-{order.id}:item-{item.id}"`, which has a `UNIQUE` index on `products_stockmovement.idempotency_key`.
  - Verified by `test_duplicate_cancellation_rejected_and_stock_restored_once` (`orders/tests.py:1307-1356`).

### Scenario 4: Product Price Change While Product Is in Customer's Cart
- **How the Backend Handles It (`VERIFIED IN CODE`, `TESTED AND PASSED`, plus UX `RISK` Identified):**
  - **Backend Behavior:** `checkout_order` (`services.py:721-728`) always resolves the current effective price from `Product.price_halalas` / `BranchProductOverride.override_price_halalas` at the moment of checkout and ignores any client-submitted price (`test_client_price_tampering_is_ignored_in_favor_of_server_price`, `orders/tests.py:1105-1147`).
  - **UX / Contract Gap (`RISK`):** In Android's local `OrderRepository.executeLocalCheckout` (`OrderRepository.kt:387-396`), the app passes `expectedUnitPricesHalalas: Map<Int, Long>` from the cart UI and aborts checkout if a product's price changed while in the cart so the customer is not silently charged a higher price without confirmation.
  - **Recommendation for 1D.2:** Allow optional `expected_unit_price_halalas` per item in `POST /api/v1/orders/`. While the server **never** trusts `expected_unit_price_halalas` to calculate the bill, if `expected_unit_price_halalas` is provided and differs from the server's authoritative `effective_price_halalas`, the server can return `409 Conflict` (`code: "price_changed"`, with the updated server price) so Android can refresh the cart and prompt the customer to confirm the new price.

### Scenario 5: Product Deactivation (`is_active=False` or `is_available=False`) Before Checkout
- **How the Backend Handles It (`VERIFIED IN CODE`, `TESTED AND PASSED`):**
  - Inside the atomic checkout transaction (`services.py:694-701, 963-967`), if `product.is_active == False` or `BranchProductOverride.is_available == False`, `checkout_order` immediately raises `ProductNotAvailableError` (`400 Bad Request`, code `product_not_available`) and rolls back the entire order. Verified by `test_inactive_product_and_branch_unavailable_product_rejected` (`orders/tests.py:1189-1237`).

---

## 6. Idempotency and Concurrency Findings

| Dimension | Backend Mechanism (`backend/apps/orders/models.py` & `services.py`) | Classification | Findings & Edge Cases |
| :--- | :--- | :--- | :--- |
| **Unique Scope** | `UniqueConstraint(fields=['actor_id', 'operation', 'idempotency_key'], name='unique_actor_operation_idempotency_key')` (`orders/models.py:424-429`). | `VERIFIED IN CODE`, `TESTED AND PASSED` | Scoped per authenticated `actor_id` and `operation` (`CHECKOUT_ORDER` or `CANCEL_ORDER`). |
| **Header vs Body Support** | `OrderViewSet._resolve_idempotency_key` (`views.py:276-285`) extracts `Idempotency-Key` or `X-Idempotency-Key` HTTP header first, falling back to `request.data['idempotency_key']`. If both header and body are provided and differ, raises `IdempotencyConflictError` (`409 Conflict`). | `VERIFIED IN CODE`, `TESTED AND PASSED` | Cleanly supports both standard HTTP `Idempotency-Key` headers and JSON body `idempotency_key` (which `OrderRequest` in Android `ApiModels.kt:60` already includes). |
| **Payload Tampering Detection** | `_compute_request_hash` (`services.py:422-431`) hashes canonical JSON (`sort_keys=True`) with SHA-256. Reusing the same `idempotency_key` with different items, quantities, or branch raises `IdempotencyConflictError` (`409 Conflict`, code `idempotency_conflict`). | `VERIFIED IN CODE`, `TESTED AND PASSED` | Tested in `test_idempotency_key_reuse_with_different_payload_rejected` (`orders/tests.py:1399-1438`). |
| **Order Table Unique Guard** | `Order.idempotency_key` (`orders/models.py:87-92`) also has `unique=True` on `orders_order`. | `VERIFIED IN CODE`, `RISK` (Minor) | Because `Order.idempotency_key` is globally unique across `orders_order` while `IdempotencyKey` is unique per `(actor_id, operation, idempotency_key)`, clients **must** generate globally unique UUIDv4 keys (`UUID.randomUUID().toString()`). Android already uses `UUID.randomUUID()` locally. |
| **Stock Movement Idempotency** | Every `StockMovement` row created during checkout or cancellation gets a deterministic unique key (`checkout:order-{id}:item-{item_id}` or `cancel-restore:order-{id}:item-{item_id}`). | `VERIFIED IN CODE`, `TESTED AND PASSED` | Guarantees at the database constraint level that stock cannot be deducted twice or restored twice for the same `OrderItem`. |

---

## 7. Authorization and Tenant Isolation Findings

We audited `backend/apps/orders/permissions.py` (lines 1–250), `backend/apps/orders/views.py` (lines 256–497), `backend/apps/orders/auth.py` (lines 1710–1867), and `backend/apps/orders/services.py`:

| Question | Answer | Evidence & Classification |
| :--- | :--- | :--- |
| **1. Can a `CUSTOMER` view another customer's orders?** | **No.** |- **List (`GET /api/v1/orders/`):** `OrderViewSet.get_queryset` (`views.py:303-305`) filters `queryset.filter(customer_id=actor.customer_id)` (`VERIFIED IN CODE`, `TESTED AND PASSED`).<br>- **Detail (`GET /api/v1/orders/{id}/`):** Both `get_queryset` and `OrderObjectPermission.has_object_permission` (`permissions.py:239-240`) require `obj.customer_id == actor.customer_id`. Accessing another customer's order returns `403`/`404` (`TESTED AND PASSED`). |
| **2. Can a `CUSTOMER` spoof `customer_id`, `actor_id`, `role`, or `store_id` in request bodies/queries?** | **No.** | `enforce_no_client_identity_spoofing` (`auth.py:1805-1867`) inspects request payloads and query params before service execution. If a client supplies a mismatched `customer_id`, `actor_id`, `role`, or `store_id`, it raises `AuthorizationError` (`403 Forbidden`, code `identity_spoofing_forbidden`) (`VERIFIED IN CODE`, `TESTED AND PASSED`). |
| **3. Can a `CUSTOMER` change order status (`transition-status`)?** | **No.** | `OrderPermission.has_permission` (`permissions.py:209-214`) only allows `CUSTOMER` on `list`, `retrieve`, `create`, `checkout`, and `cancel`. Calling `POST /api/v1/orders/{id}/transition-status/` as a `CUSTOMER` is rejected at both the permission layer (`permissions.py:213`) and service layer (`services.py:1164-1165`) with `403 Forbidden` (`VERIFIED IN CODE`, `TESTED AND PASSED`). |
| **4. Can a `CUSTOMER` cancel their own order? Can they cancel a `COMPLETED` order?** | **Own non-completed order: Yes.<br>Completed or other customer's order: No.** | `cancel_order` (`services.py:1307-1321`) verifies `order.customer_id == actor.customer_id` when `actor.role == 'CUSTOMER'`, and checks `order.can_transition_to('CANCELLED')`. Since `VALID_TRANSITIONS['COMPLETED'] == set()`, cancelling a `COMPLETED` order raises `InvalidOrderTransitionError` (`409 Conflict`). *(Note: If business rules in 1D.2 require restricting customer self-cancellation to `PENDING` only rather than `PENDING`/`CONFIRMED`/`PROCESSING`, that rule can be tightened in `cancel_order`—see Section 12.)* |
| **5. Can a `BRANCH_MANAGER` access orders or stock movements from another branch?** | **No.** | `OrderViewSet.get_queryset` (`views.py:306-309`), `OrderObjectPermission` (`permissions.py:242-246`), `StockMovementViewSet.get_queryset` (`views.py:157-159`), and `assert_branch_access` (`services.py:338-347`) restrict `BRANCH_MANAGER` strictly to `branch_id in actor.authorized_branch_ids`. Cross-branch requests return `403 Forbidden` (`VERIFIED IN CODE`, `TESTED AND PASSED`). |
| **6. Can an `OWNER` access orders or branches belonging to another `Store`?** | **No.** | In `get_request_actor` (`auth.py:1750-1765`), an `OWNER`'s `authorized_branch_ids` is populated strictly from active branches where `branch.store_id == profile.store_id`. Cross-store access is blocked with `403 Forbidden` (`VERIFIED IN CODE`, `TESTED AND PASSED`). |

---

## 8. Android Integration Gap Analysis

We audited `ApiModels.kt`, `FreshApiService.kt`, `OrderRepository.kt`, `ProductRepository.kt`, and `Injection.kt`:

### 8.1 Does Android Currently Send Orders to Django?
- **No (`VERIFIED IN CODE`, `MISSING`).**
  - `OrderRepository` (`app/src/main/java/com/example/data/repository/OrderRepository.kt`, lines 28–31) has constructor signature:
    ```kotlin
    class OrderRepository(
        private val database: AppDatabase,
        private val checkoutMutex: Mutex = Mutex()
    )
    ```
  - `Injection.provideOrderRepository` (`app/src/main/java/com/example/di/Injection.kt`, lines 98–108) instantiates `OrderRepository(database = provideDatabase(context), checkoutMutex = sharedInventoryMutex)` **without passing `FreshApiService`**.
  - When a user completes checkout in the Android app, `OrderRepository.executeLocalCheckout` (lines 329–525) runs inside `database.withTransaction { ... }`, deducts stock from the local Room `products` table (`productDao.deductStockBatchAtomic`), creates a local Room `Order` with `status = OrderStatus.CONFIRMED_LOCAL`, and writes local `InventoryAdjustmentLog` entries. **Zero HTTP requests are sent to Django during checkout, order history viewing, or order cancellation.**

### 8.2 Detailed File-by-File Android Gap Matrix

| Android File | Current State (`VERIFIED IN CODE`) | Gaps vs Server-Authoritative Architecture (`MISSING` / `RISK`) |
| :--- | :--- | :--- |
| **`FreshApiService.kt`** (`lines 25-60`) | Defines:<br>- `GET("products/")`<br>- `GET("categories/")`<br>- `POST("orders/") createOrder(@Body request: OrderRequest): OrderResponse`<br>- `POST("auth/register/")`, `POST("auth/token/")`, `POST("auth/token/refresh/")`, `POST("auth/logout/")`, `GET("auth/me/")` | **`MISSING` Order Endpoints:**<br>1. `@GET("orders/") suspend fun getOrders(@Query("branch_id") branchId: Int? = null): List<OrderResponse>`<br>2. `@GET("orders/{id}/") suspend fun getOrderById(@Path("id") orderId: Int): OrderResponse`<br>3. `@POST("orders/{id}/cancel/") suspend fun cancelOrder(@Path("id") orderId: Int, @Body request: OrderCancelRequest, @Header("Idempotency-Key") idempotencyKey: String? = null): OrderResponse`<br>4. `@POST("orders/{id}/transition-status/") suspend fun transitionOrderStatus(@Path("id") orderId: Int, @Body request: OrderStatusTransitionRequest): OrderResponse` |
| **`ApiModels.kt`** (`lines 19-151`) | Defines `ProductDto`, `CategoryDto`, `OrderRequest`, `OrderItemRequest`, `OrderResponse`, `OrderItemDto`, `OrderStatusHistoryDto`. | 1. **`OrderRequest` (`lines 55-62`):** Includes `customerId: String? = null` (`@SerializedName("customer_id")`). While Django allows `customer_id` if it matches the JWT token, sending a stale/local `customer_id` risks triggering `identity_spoofing_forbidden` (`403`). Server resolves `customer_id` from the JWT token automatically.<br>2. **`MISSING` Request DTOs:** `OrderCancelRequest` (`idempotency_key`, `reason`) and `OrderStatusTransitionRequest` (`to_status`, `reason`).<br>3. **`ProductDto` (`lines 19-53`):** Missing `availableQuantityMilli: Long?` (`@SerializedName("available_quantity_milli")`) and `inStock: Boolean?` (`@SerializedName("in_stock")`). |
| **`OrderRepository.kt`** (`lines 1-797`) | 100% local Room implementation (`executeLocalCheckout`, `cancelOrderAndRestoreStock`, `transitionOrderStatus`). Uses local `OrderStatus` enum (`CONFIRMED_LOCAL`, `PREPARING`, `OUT_FOR_DELIVERY`, `DELIVERED`, `CANCELLED`). | 1. **`MISSING` Remote-First Checkout (`createRemoteOrder`):** Needs to call `FreshApiService.createOrder(...)` with an `Idempotency-Key` UUID, persist the returned authoritative `OrderResponse` and `OrderItemDto`s into Room as a read cache, and update cached product stock.<br>2. **`MISSING` Remote Order Sync & Cancellation:** Needs `refreshMyOrders()` (`GET /api/v1/orders/`), `getOrderDetail(orderId)` (`GET /api/v1/orders/{id}/`), and server-authoritative `cancelOrder(orderId, reason)` (`POST /api/v1/orders/{id}/cancel/`).<br>3. **Status Mapping Gap (`RISK`):** Django statuses (`PENDING`, `CONFIRMED`, `PROCESSING`, `COMPLETED`, `CANCELLED`) must map cleanly to/from Android's `OrderStatus` enum and Room cache. |
| **`ProductRepository.kt`** (`lines 240-310`) | `refreshProducts()` fetches `GET /api/v1/products/` and updates Room `Product` entities, preserving local `stockQuantity = existing?.stockQuantity ?: 50`. | **`MISSING` Server Stock Cache Sync:** Once `GET /api/v1/products/` includes branch stock availability (`available_quantity_milli`), `ProductRepository.refreshProducts()` should update the cached Room `stockSubUnitsMillis` / `stockQuantity` from the server response rather than hardcoding `50`. |
| **`Injection.kt`** (`lines 98-108`) | `provideOrderRepository(context)` only injects `AppDatabase` and `sharedInventoryMutex`. | **`MISSING` DI Wiring:** Must inject `FreshApiService` (via `provideApiService(context)`) and `LocalAuthRepository` / `SecureTokenStorage` into `OrderRepository`. |

---

## 9. Proposed API Contracts

All endpoints below are prefixed with `/api/v1/` and align with the existing Django implementation in `backend/apps/orders/views.py`, `backend/apps/orders/serializers.py`, and `backend/apps/orders/services.py`, with explicit contract specifications for Microtask 1D.2.

### 9.1 `POST /api/v1/orders/` (and alias `POST /api/v1/orders/checkout/`) — Create Customer Order

- **Authentication & Authorization:**
  - Requires `Authorization: Bearer <access_token>`.
  - Allowed Role: `CUSTOMER` only (`401 Unauthorized` if unauthenticated; `403 Forbidden` if `BRANCH_MANAGER` or `OWNER`).
- **Headers:**
  - `Authorization: Bearer <jwt_access_token>` (Required)
  - `Idempotency-Key: <uuid-v4>` (Optional in header if provided in body `idempotency_key`; **Required** in at least one location for safe Android retries)
- **Request JSON Body:**
  ```json
  {
    "branch_id": 1,
    "fulfillment_warehouse_id": null,
    "idempotency_key": "550e8400-e29b-41d4-a716-446655440000",
    "delivery_address": "حي العليا، طريق الملك فهد، الرياض",
    "payment_mode_note": "الدفع عند الاستلام (COD)",
    "items": [
      {
        "product_id": 101,
        "quantity_milli": 1500,
        "expected_unit_price_halalas": 800
      },
      {
        "product_id": 102,
        "quantity_milli": 2000
      }
    ]
  }
  ```
  - **Field Notes:**
    - `branch_id` (`int`, required): Target active branch ID. (Default primary branch in FRESH is `1`).
    - `fulfillment_warehouse_id` (`int | null`, optional): If omitted/`null`, server automatically selects the branch's active primary fulfillment warehouse (`is_primary_fulfillment=True`).
    - `idempotency_key` (`string`, required, max 128 chars): Client-generated UUIDv4.
    - `delivery_address` (`string`, optional, default `""`): Customer delivery address snapshot.
    - `payment_mode_note` (`string`, optional, default `"COD"`): Payment mode note (`COD` in Phase 1D).
    - `items` (`array`, non-empty, unique `product_id`s):
      - `product_id` (`int`, required)
      - `quantity_milli` (`int > 0`, required): `1000` = 1 unit/kg. Must be a multiple of `1000` if `product.is_fractional_allowed == false`.
      - `expected_unit_price_halalas` (`int >= 0`, optional): Used **only** to detect stale cart prices (`409 price_changed`); **never** used to compute the order price.
- **Success Response:**
  - **`201 Created`** (first execution) or **`200 OK`** (idempotent replay with `replayed: true`):
  ```json
  {
    "id": 42,
    "branch_id": 1,
    "fulfillment_warehouse_id": 1,
    "customer_id": "cust-user-15",
    "status": "PENDING",
    "subtotal_halalas": 2800,
    "delivery_fee_halalas": 0,
    "discount_halalas": 0,
    "total_halalas": 2800,
    "delivery_address": "حي العليا، طريق الملك فهد، الرياض",
    "payment_mode_note": "الدفع عند الاستلام (COD)",
    "idempotency_key": "550e8400-e29b-41d4-a716-446655440000",
    "cancellation_restored": false,
    "timestamp": "2026-11-11T21:00:00+00:00",
    "updated_at": "2026-11-11T21:00:00+00:00",
    "replayed": false,
    "items": [
      {
        "id": 1001,
        "product_id": 101,
        "product_name_snapshot": "طماطم بلدي طازج",
        "unit_snapshot": "kg",
        "quantity_milli": 1500,
        "unit_price_halalas": 800,
        "line_total_halalas": 1200
      },
      {
        "id": 1002,
        "product_id": 102,
        "product_name_snapshot": "خيار محلي",
        "unit_snapshot": "kg",
        "quantity_milli": 2000,
        "unit_price_halalas": 800,
        "line_total_halalas": 1600
      }
    ],
    "status_history": [
      {
        "id": 501,
        "from_status": null,
        "to_status": "PENDING",
        "changed_by_actor_id": "cust-user-15",
        "reason": "Order created via atomic checkout",
        "changed_at": "2026-11-11T21:00:00+00:00"
      }
    ]
  }
  ```
- **Error Responses:**
  - **`400 Bad Request`**:
    - `{"detail": "...", "code": "validation_error", "errors": {...}}` — Missing fields, empty `items`, duplicate `product_id`, invalid `quantity_milli` (e.g., fractional quantity for unit-only product, or outside `min`/`max` bounds).
    - `{"detail": "Product 101 is not available in branch 1.", "code": "product_not_available"}` — Inactive branch, inactive warehouse, inactive product, or `BranchProductOverride.is_available == False`.
  - **`401 Unauthorized`**:
    - `{"detail": "Authentication credentials were not provided.", "code": "authentication_required"}` — Missing, expired, or revoked JWT token.
  - **`403 Forbidden`**:
    - `{"detail": "Only CUSTOMER actors may place checkout orders.", "code": "authorization_denied"}` — Staff role attempting customer checkout, or `identity_spoofing_forbidden`.
  - **`409 Conflict`**:
    - `{"detail": "Insufficient stock for product 101: requested 1500 milli-units, available 1000 milli-units.", "code": "insufficient_stock"}` — Insufficient available stock in the branch's fulfillment warehouse.
    - `{"detail": "Idempotency key '...' was already used with a different request payload.", "code": "idempotency_conflict"}` — Same `idempotency_key` sent with modified cart items/branch.
    - `{"detail": "Price for product 101 changed from 800 to 950 halalas.", "code": "price_changed"}` — Optional stale-cart price check when `expected_unit_price_halalas` is supplied.

---

### 9.2 `GET /api/v1/orders/` — List Orders (Role-Scoped)

- **Authentication & Authorization:**
  - Requires `Authorization: Bearer <access_token>`.
  - Scoping by Role (`VERIFIED IN CODE`, `views.py:293-318`):
    - `CUSTOMER`: Returns **only** orders where `order.customer_id == actor.customer_id`, ordered by `-timestamp, -id`.
    - `BRANCH_MANAGER`: Returns orders where `order.branch_id in actor.authorized_branch_ids`.
    - `OWNER`: Returns orders across active branches of the owner's `store_id`.
- **Optional Query Parameters:**
  - `?branch_id=<int>` (Optional filter; if a `BRANCH_MANAGER` or `OWNER` passes an unauthorized `branch_id`, returns `403 Forbidden`).
- **Success Response (`200 OK`):**
  - JSON Array `[ OrderResponse, ... ]` (each item has the exact same schema as the `OrderResponse` in Section 9.1, including nested `items` and `status_history`).
- **Error Responses:**
  - `401 Unauthorized` (`authentication_required`)
  - `403 Forbidden` (`authorization_denied` if unauthorized `branch_id` filter is requested)

---

### 9.3 `GET /api/v1/orders/{id}/` — Retrieve Single Order Detail

- **Authentication & Authorization:**
  - Requires `Authorization: Bearer <access_token>`.
  - Enforced by both `get_queryset()` and `OrderObjectPermission` (`permissions.py:224-249`):
    - `CUSTOMER`: Must match `order.customer_id == actor.customer_id`.
    - `BRANCH_MANAGER` / `OWNER`: Must match `order.branch_id in actor.authorized_branch_ids`.
- **Success Response (`200 OK`):**
  - Single `OrderResponse` JSON object with `items` and `status_history`.
- **Error Responses:**
  - `401 Unauthorized` (`authentication_required`)
  - `403 Forbidden` / `404 Not Found` (`order_not_found` or `authorization_denied` if order does not exist or belongs to another customer/tenant)

---

### 9.4 `POST /api/v1/orders/{id}/cancel/` — Cancel Order & Restore Inventory Atomically

- **Authentication & Authorization:**
  - Requires `Authorization: Bearer <access_token>`.
  - Allowed Roles:
    - `CUSTOMER`: Can cancel **only** their own order (`order.customer_id == actor.customer_id`).
    - `BRANCH_MANAGER` / `OWNER`: Can cancel orders within their `authorized_branch_ids`.
- **Headers:**
  - `Authorization: Bearer <jwt_access_token>` (Required)
  - `Idempotency-Key: <uuid-v4>` (Optional in header or body `idempotency_key`)
- **Request JSON Body:**
  ```json
  {
    "idempotency_key": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "reason": "طلب العميل إلغاء الطلب قبل التجهيز"
  }
  ```
- **Success Response (`200 OK`):**
  - Returns the updated `OrderResponse` with:
    - `"status": "CANCELLED"`
    - `"cancellation_restored": true`
    - `"replayed": false` (or `true` if replayed with the same `(actor_id, 'CANCEL_ORDER', idempotency_key)` and payload hash)
    - Updated `status_history` containing the transition to `CANCELLED`.
- **Error Responses:**
  - `400 Bad Request` (`validation_error` if `reason` is empty/whitespace when provided)
  - `401 Unauthorized` (`authentication_required`)
  - `403 Forbidden` (`authorization_denied` if customer tries to cancel another customer's order or staff tries to cancel another branch's order)
  - `404 Not Found` (`order_not_found`)
  - `409 Conflict`:
    - `{"detail": "Order 42 has already been cancelled and stock restored.", "code": "order_already_cancelled"}` — Duplicate cancellation with a new/missing idempotency key.
    - `{"detail": "Order 42 cannot be cancelled from status 'COMPLETED'.", "code": "invalid_order_transition"}` — Attempting to cancel a completed/delivered order.
    - `{"detail": "Idempotency key '...' was already used with a different cancellation payload.", "code": "idempotency_conflict"}`

---

### 9.5 `POST /api/v1/orders/{id}/transition-status/` — Staff Order Status Transition

- **Authentication & Authorization:**
  - Requires `Authorization: Bearer <access_token>`.
  - Allowed Roles: `BRANCH_MANAGER` and `OWNER` only (`CUSTOMER` is strictly rejected with `403 Forbidden`).
  - Note: Transitioning to `CANCELLED` via this endpoint is rejected with `400 Bad Request` (`InvalidOrderTransitionError`); cancellations must use `POST /api/v1/orders/{id}/cancel/` so inventory restoration is guaranteed (`services.py:1158-1161`).
- **Request JSON Body:**
  ```json
  {
    "to_status": "CONFIRMED",
    "reason": "تم تأكيد الطلب في الفرع"
  }
  ```
- **Success Response (`200 OK`):**
  - Updated `OrderResponse` with new `status` and appended `status_history` item.
- **Error Responses:**
  - `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `409 Conflict` (`invalid_order_transition`).

---

## 10. Required Database Changes

### 10.1 Assessment of Existing Schema
- **Core Order & Inventory Tables (`VERIFIED IN CODE`):**
  - `products_store`, `products_branch`, `products_warehouse`, `products_category`, `products_product`, `products_branchproductoverride`, `products_warehousestockbalance`, `products_stockmovement`, `orders_order`, `orders_orderitem`, `orders_orderstatushistory`, and `orders_idempotencykey` **already exist** in `backend/apps/products/migrations/0001_initial.py` and `backend/apps/orders/migrations/0001_initial.py` (`0002` / `0003` for auth).
- **Optional Additive Columns vs Zero-Migration Option for Order Breakdown:**
  - As noted in Section 4 (Checkpoint 7), Android's local Room `Order` entity stores `subtotalHalalas`, `deliveryFeeHalalas`, `discountHalalas`, `deliveryAddressSnapshot`, and `paymentModeNote`, while Django's `orders_order` table currently stores `total_halalas`.
  - **Two Safe Options for Microtask 1D.2:**
    1. **Option A (Zero Backend Schema Migration — Default Safe Path):** Keep `orders_order` schema unchanged (`total_halalas` = authoritative sum of `OrderItem.line_total_halalas`), and return computed `subtotal_halalas = total_halalas`, `delivery_fee_halalas = 0`, `discount_halalas = 0` in `OrderReadSerializer` while storing `delivery_address` / `payment_mode_note` in the initial `OrderStatusHistory.reason` or response payload.
    2. **Option B (Additive Non-Breaking Fields with Defaults):** Add nullable/defaulted fields to `Order` (`subtotal_halalas`, `delivery_fee_halalas`, `discount_halalas`, `delivery_address`, `payment_mode_note`) with safe defaults (`0` and `""`) in a new migration `0004_order_checkout_metadata.py` and matching `ALTER TABLE` guard in `ensure_sqlite_reference_schema()`.
  - **Android Room Database (`AppDatabase`):**
    - **No destructive migration or schema reset is needed.** Room's existing `Order` entity (`app/src/main/java/com/example/data/model/Order.kt`) already has `id`, `userId`, `totalAmount`, `totalHalalas`, `subtotalHalalas`, `deliveryFeeHalalas`, `discountHalalas`, `status`, `timestamp`, `deliveryAddressSnapshot`, `paymentModeNote`, and `cancellationRestored`. We can map Django's `OrderResponse` directly into Room's `orders` and `order_items` tables as a local read cache.

---

## 11. Test Coverage Matrix

We audited `backend/apps/orders/tests.py` (3,499 lines) and `backend/apps/products/tests.py` and verified which of the 13 required scenarios are already covered and passing vs which require new tests in Microtask 1D.2:

| # | Required Test Scenario | Backend Test Status (`orders/tests.py`) | Android Test Status (`app/src/test/...`) | Classification |
| :-: | :--- | :--- | :--- | :--- |
| **1** | **Customer successful checkout** | Covered by `test_atomic_checkout_creates_order_snapshots_deducts_stock_and_logs_movement` (`orders/tests.py:1049-1103`). | Only local Room checkout tested; **no** Android → Django HTTP checkout test yet. | Backend: `TESTED AND PASSED` (SQLite) / `NOT TESTED` (PostgreSQL)<br>Android HTTP: `MISSING` |
| **2** | **Guest checkout rejected (`401`)** | Covered by `test_unauthenticated_requests_rejected_401` (`orders/tests.py:2175-2201`). | Not yet tested on `OrderRepository` remote checkout. | Backend: `TESTED AND PASSED`<br>Android: `MISSING` |
| **3** | **Client price tampering ignored** | Covered by `test_client_price_tampering_is_ignored_in_favor_of_server_price` (`orders/tests.py:1105-1147`). | Not yet tested via Android HTTP client. | Backend: `TESTED AND PASSED`<br>Android: `MISSING` |
| **4** | **Insufficient stock rejected (`409`)** | Covered by `test_multi_item_checkout_partial_insufficient_stock_rolls_back_all_items` (`orders/tests.py:1149-1187`). | Tested locally in Room; not yet tested against `409 Conflict` from Django. | Backend: `TESTED AND PASSED`<br>Android HTTP: `MISSING` |
| **5** | **Partial cart failure rolls back entire order** | Covered by `test_multi_item_checkout_partial_insufficient_stock_rolls_back_all_items` (`orders/tests.py:1149-1187`). | Tested locally in Room; not yet tested over HTTP. | Backend: `TESTED AND PASSED`<br>Android HTTP: `MISSING` |
| **6** | **Duplicate idempotency key returns same order (`200`)** | Covered by `test_idempotency_key_replay_returns_same_order_without_double_deduction` (`orders/tests.py:1358-1397`). | Not yet tested via Android `OrderRepository`. | Backend: `TESTED AND PASSED`<br>Android HTTP: `MISSING` |
| **7** | **Same idempotency key with different payload rejected (`409`)** | Covered by `test_idempotency_key_reuse_with_different_payload_rejected` (`orders/tests.py:1399-1438`). | Not yet tested via Android `OrderRepository`. | Backend: `TESTED AND PASSED`<br>Android HTTP: `MISSING` |
| **8** | **Order cancellation restores stock once** | Covered by `test_duplicate_cancellation_rejected_and_stock_restored_once` (`orders/tests.py:1307-1356`). | Tested locally in Room; not yet tested against `POST /api/v1/orders/{id}/cancel/`. | Backend: `TESTED AND PASSED`<br>Android HTTP: `MISSING` |
| **9** | **Second cancellation rejected (`409`)** | Covered by `test_duplicate_cancellation_rejected_and_stock_restored_once` (`orders/tests.py:1307-1356`). | Tested locally in Room; not yet tested over HTTP. | Backend: `TESTED AND PASSED`<br>Android HTTP: `MISSING` |
| **10** | **Customer cannot view another customer's order (`403`/`404`)** | Covered by `test_customer_cannot_read_cancel_or_spoof_other_customer_orders` (`orders/tests.py:2203-2268`). | Not yet tested in Android integration suite. | Backend: `TESTED AND PASSED`<br>Android HTTP: `MISSING` |
| **11** | **Customer cannot transition order status (`403`)** | Covered by `test_customer_cannot_transition_order_status_or_mutate_catalog` (`orders/tests.py:2270-2312`). | Not yet tested in Android integration suite. | Backend: `TESTED AND PASSED`<br>Android HTTP: `MISSING` |
| **12** | **Branch manager cannot access another branch's order (`403`)** | Covered by `test_branch_manager_isolated_to_assigned_branch` (`orders/tests.py:2314-2382`). | N/A (Backend RBAC test). | Backend: `TESTED AND PASSED` |
| **13** | **Concurrent checkout for last unit allows only one winner** | Covered by `test_concurrent_checkout_last_unit_allows_only_one_winner` (`orders/tests.py:1239-1305`) and `test_django_orm_checkout_locks_balances_in_sorted_product_order` (`orders/tests.py:1686-1755`). | Tested locally in Room; live PostgreSQL `select_for_update()` not executed in container. | SQLite & ORM Lock Order Spy: `TESTED AND PASSED`<br>Live PostgreSQL: `NOT TESTED` |

---

## 12. Critical Risks

1. **`RISK-01` — Dual Source of Truth Between Android Room and Django Backend (`HIGH`):**
   - Currently, Android (`OrderRepository.kt`) mutates local Room stock and creates local orders (`CONFIRMED_LOCAL`) without contacting Django. If not switched to server-authoritative API calls in Microtask 1D.2, customers on different devices will see divergent stock, local prices can drift from server prices, and orders will never reach the store's backend database.
2. **`RISK-02` — Missing Stock Availability in Public Catalog Endpoint (`MEDIUM`):**
   - `GET /api/v1/products/` (`backend/apps/products/serializers.py` and `views.py`) returns `price_halalas`, `effective_price_halalas`, and `is_available_in_branch`, but does **not** return `available_quantity_milli` from `WarehouseStockBalance`. Consequently, Android's product list and cart cannot know the true server stock quantity before the user taps "Checkout", falling back to local seed stock (`50`).
   - **Mitigation in 1D.2:** Include branch primary warehouse `available_quantity_milli` (and `in_stock`) in `GET /api/v1/products/?branch_id=1` and map it into `ProductDto` and Room's cached `Product.stockSubUnitsMillis` / `stockQuantity`.
3. **`RISK-03` — Order Status Enum Mismatch Between Django and Android (`MEDIUM`):**
   - Django uses `PENDING`, `CONFIRMED`, `PROCESSING`, `COMPLETED`, `CANCELLED` (`orders/models.py:58-68`), whereas Android's `OrderStatus` enum (`app/src/main/java/com/example/data/model/OrderStatus.kt`) uses `CONFIRMED_LOCAL`, `PREPARING`, `OUT_FOR_DELIVERY`, `DELIVERED`, `CANCELLED`.
   - **Mitigation in 1D.2:** Add deterministic mapping between Django statuses and Android `OrderStatus` (or extend `OrderStatus` with server states while preserving Arabic UI labels) so cached orders render accurately in `OrdersScreen`.
4. **`RISK-04` — Customer Self-Cancellation Window (`LOW-MEDIUM`):**
   - In `backend/apps/orders/services.py` (`cancel_order`, lines 1317–1321), any order where `order.can_transition_to('CANCELLED')` is `True` (`PENDING`, `CONFIRMED`, `PROCESSING`) can be cancelled by either staff or the `CUSTOMER`. If business policy requires customers to only self-cancel while the order is `PENDING` (or `PENDING`/`CONFIRMED` before preparation/dispatch), that role-specific guard should be explicitly confirmed in 1D.2.
5. **`RISK-05` — Unexecuted Live PostgreSQL Runtime in Container (`ENVIRONMENT LIMITATION`):**
   - Just as in Microtask 1C.1, Python `django` and `PostgreSQL` binaries are not installed in this Android build container, so the Django ORM / PostgreSQL `select_for_update()` code path is verified via unit-mocked lock-order spies and the SQLite Reference Engine (`NOT TESTED` against a live PostgreSQL server).

---

## 13. Recommended Microtask 1D.2

Based on the findings above, **Microtask 1D.2 (Server-Authoritative Checkout, Order Sync & Inventory Cache Integration)** should execute the following tightly scoped steps:

1. **Backend & Test Server Readiness (`backend/apps/products/` & `backend/apps/orders/`):**
   - Expose branch `available_quantity_milli` and `in_stock` in `GET /api/v1/products/` (`products/views.py`, `products/serializers.py`) so Android's Room catalog cache reflects real server warehouse stock.
   - Add `/api/v1/orders/` (`POST`, `GET`), `/api/v1/orders/{id}/` (`GET`), `/api/v1/orders/{id}/cancel/` (`POST`), and `/api/v1/orders/{id}/transition-status/` (`POST`) routing to `backend/apps/orders/test_http_server.py` (delegating directly to `services.py`: `checkout_order`, `list_orders`, `get_order_detail`, `cancel_order`, `transition_order_status`) so Android JVM/Robolectric tests can run real end-to-end HTTP order & inventory tests against the Python backend.
2. **Android Retrofit & DTO Alignment (`FreshApiService.kt` & `ApiModels.kt`):**
   - Add `getOrders`, `getOrderById`, `cancelOrder`, and `transitionOrderStatus` to `FreshApiService.kt`.
   - Add `OrderCancelRequest` and `OrderStatusTransitionRequest` to `ApiModels.kt`, and add `availableQuantityMilli` / `inStock` to `ProductDto`.
   - Omit client-side `customer_id` from `OrderRequest` (let the server resolve `customer_id` strictly from the authenticated JWT token).
3. **Android `OrderRepository`, `ProductRepository`, and `Injection` Wiring:**
   - Inject `FreshApiService` into `OrderRepository` via `Injection.provideOrderRepository(context)`.
   - Implement server-authoritative checkout (`POST /api/v1/orders/` with `Idempotency-Key` UUID), caching the returned `OrderResponse` and `OrderItemDto`s into Room and updating cached Room product stock.
   - Implement server-authoritative order history sync (`GET /api/v1/orders/`), order detail fetch (`GET /api/v1/orders/{id}/`), and order cancellation (`POST /api/v1/orders/{id}/cancel/`).
   - Update `ProductRepository.refreshProducts()` to sync `available_quantity_milli` from the server into Room's cached product stock when provided by the API.
4. **End-to-End Verification Suite (`MicroTask1DOrdersAndInventoryIntegrationTest.kt`):**
   - Write and execute live Android-to-Python HTTP integration tests covering all 13 matrix scenarios (authenticated customer checkout, guest `401` rejection, price tampering ignored, `409` insufficient stock, atomic multi-item rollback, idempotency replay `200`, idempotency payload conflict `409`, single stock restoration on cancel, duplicate cancel rejection `409`, cross-customer isolation `403`, customer status transition rejection `403`, and Room cache synchronization).

---

## Final Audit Verdict

**`READY FOR 1D.2`**
