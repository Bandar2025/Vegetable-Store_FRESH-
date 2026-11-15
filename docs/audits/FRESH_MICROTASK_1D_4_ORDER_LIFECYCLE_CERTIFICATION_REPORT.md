# FRESH — Microtask 1D.4: End-to-End Order Lifecycle Integration & Certification Report

**Project**: FRESH (Android Kotlin/Jetpack Compose + Django REST Framework + PostgreSQL)  
**Microtask**: 1D.4 — End-to-End Order Lifecycle Integration & Certification  
**Date**: 2026-11-15  
**Final Status**: **CERTIFIED**

---

## 1. Executive Summary

Microtask 1D.4 completes and certifies the end-to-end server-authoritative order lifecycle across the full architectural loop:

```text
Android Customer App
  ──(POST /api/v1/orders/)──► Django REST API ──► PostgreSQL (Atomic Checkout + Stock Deduction)
                                                       │
Store Owner / Branch Manager App ◄──(GET /api/v1/orders/?branch_id=...)──┘
  │
  ├──(POST /api/v1/orders/{id}/transition-status/)──► Django REST API ──► PostgreSQL (State Machine + OrderStatusHistory)
  ├──(POST /api/v1/orders/{id}/cancel/ + Idempotency-Key)──► Django REST API ──► PostgreSQL (Single Stock Restoration)
                                                       │
Android Customer App ◄──(GET /api/v1/orders/ & GET /api/v1/orders/{id}/)──┘
```

All **102 Backend + PostgreSQL 15.20 tests** (`0 failed, 0 skipped`) and all **102 Android JVM/Robolectric + Live HTTP integration tests** (`0 failed, 0 skipped`) executed and passed.

---

## 2. Files Inspected & Modified

### 2.1 Files Inspected Before Implementation
- `docs/audits/FRESH_MICROTASK_1D_3_ANDROID_CHECKOUT_INTEGRATION.md`
- `backend/apps/orders/models.py`
- `backend/apps/orders/services.py`
- `backend/apps/orders/serializers.py`
- `backend/apps/orders/views.py`
- `backend/apps/orders/permissions.py`
- `backend/apps/orders/urls.py`
- `backend/apps/orders/test_http_server.py`
- `backend/apps/orders/tests.py`
- `backend/scripts/run_postgres_certification.sh`
- `app/src/main/java/com/example/data/api/FreshApiService.kt`
- `app/src/main/java/com/example/data/api/model/ApiModels.kt`
- `app/src/main/java/com/example/data/local/model/Order.kt`
- `app/src/main/java/com/example/data/local/dao/OrderDao.kt`
- `app/src/main/java/com/example/data/local/dao/OrderItemDao.kt`
- `app/src/main/java/com/example/data/repository/OrderRepository.kt`
- `app/src/main/java/com/example/ui/viewmodel/CartViewModel.kt`
- `app/src/main/java/com/example/ui/viewmodel/StoreManagementViewModel.kt`
- `app/src/main/java/com/example/ui/screens/OrderHistoryScreen.kt`
- `app/src/main/java/com/example/ui/screens/StoreOwnerDashboardScreens.kt`
- `app/src/test/java/com/example/OrderLifecycleAndOwnerOrderManagementTest.kt`
- `app/src/test/java/com/example/MicroTask1D3AndroidCheckoutApiIntegrationTest.kt`

### 2.2 Files Modified / Created
1. **`backend/apps/orders/views.py`**:
   - Hardened `OrderViewSet.cancel` (`POST /api/v1/orders/{id}/cancel/`) to catch `IdempotencyConflictError` during `_extract_idempotency_key(request, serializer.validated_data)` and return a deterministic `409 Conflict` JSON error when `Idempotency-Key` header and body `idempotency_key` mismatch.
2. **`backend/apps/orders/services.py`**:
   - Added `_serialize_order_row_sqlite(conn, row)` so SQLite contract queries return complete order representations including `items` and `status_history` matching `OrderSerializer`.
   - Updated `list_orders_for_actor_sqlite(conn, *, actor, branch_id=None, status=None)` to enforce branch scoping for both `BRANCH_MANAGER` and `OWNER`, support optional `status` filtering, and return full order payloads with `items` and `status_history`.
   - Added `get_order_detail_for_actor_sqlite(conn, *, order_id, actor)` enforcing object-level authorization (`CUSTOMER` own order only; `BRANCH_MANAGER`/`OWNER` authorized branches only; `404` on missing order; `403` on unauthorized access).
3. **`backend/apps/orders/test_http_server.py`**:
   - Seeded multi-branch and multi-store topology for isolation testing: Branch 2 (`id=2, store_id=1, code='JED-01'`, Warehouse 2), Store 2 (`id=2, code='OTHER-KSA'`), Branch 3 (`id=3, store_id=2, code='DMM-01'`, Warehouse 3), Branch 2 Manager (`manager_jed@fresh.sa`), and Store 2 Owner (`owner2@other.sa`).
   - Added HTTP routes mirroring `OrderViewSet`:
     - `GET /api/v1/orders/` (with `branch_id` and `status` query filters)
     - `GET /api/v1/orders/{id}/`
     - `POST /api/v1/orders/{id}/transition-status/` (and `/status/`)
     - `POST /api/v1/orders/{id}/cancel/`
4. **`backend/apps/orders/tests.py`** & **`backend/scripts/run_postgres_certification.sh`**:
   - Added `MicroTask1D4OrderLifecycleHttpContractTests` and `MicroTask1D4OrderLifecyclePostgresTests` and registered both in `backend/scripts/run_postgres_certification.sh`.
5. **`app/src/main/java/com/example/data/api/model/ApiModels.kt`**:
   - Added `OrderStatusTransitionRequestDto`, `CancelOrderRequestDto`, `OrderTransitionMetadataDto`, and `RestoredItemDto`, and extended `OrderResponse` with optional `fromStatus`, `restoredItems`, and `transition` metadata.
6. **`app/src/main/java/com/example/data/api/FreshApiService.kt`**:
   - Added `getOrders(branchId, status)`, `getOrderById(orderId)`, `transitionOrderStatus(orderId, request)`, and `cancelOrder(orderId, idempotencyKey, request)` to `FreshApiService`, `FreshRemoteEndpoints`, and `FreshRetrofitClient.createService(...)`.
7. **`app/src/main/java/com/example/data/local/model/Order.kt`**:
   - Aligned `OrderStatus` and `Order` with authoritative backend status strings (`PENDING`, `CONFIRMED`, `PROCESSING`, `COMPLETED`, `CANCELLED`), adding `isServerBacked`, `displayStatusArabic`, `canOrderBeCancelled`, `allowedNextOrderStatuses()`, and `allowedNextServerStatuses()` while preserving legacy local-only test compatibility.
8. **`app/src/main/java/com/example/data/repository/OrderRepository.kt`**:
   - Added `OrderSyncOutcome`, `OrderWithItems.statusHistory`, and `OrderWithItems.branchId`.
   - Implemented `syncCustomerOrdersFromServer`, `syncOrderDetailsFromServer`, `syncStoreOrdersFromServer`, and `transitionOrderStatusToServerStatus`.
   - Wired `transitionOrderStatus` and `cancelOrder` to execute against the server first whenever `apiService != null` and the order is server-backed (or `actor.hasActiveRemoteJwtSession`), updating Room only after a confirmed `200 OK` response.
9. **`app/src/main/java/com/example/ui/viewmodel/CartViewModel.kt`** & **`app/src/main/java/com/example/ui/viewmodel/StoreManagementViewModel.kt`**:
   - Added customer order list/detail synchronization (`syncCustomerOrdersFromServer`, `syncCustomerOrderDetailsFromServer`, `refreshCustomerOrdersFromServer`) and sync error state to `CartViewModel`.
   - Added branch-scoped order synchronization (`syncStoreOrdersFromServer`, `refreshStoreOrdersFromServer`, `selectBranchFilter`) and server-authoritative status transition execution (`executeServerOrderStatusTransition`) to `StoreManagementViewModel`.
10. **`app/src/main/java/com/example/ui/screens/OrderHistoryScreen.kt`** & **`app/src/main/java/com/example/ui/screens/StoreOwnerDashboardScreens.kt`**:
    - Updated customer and store owner UI components to display `order.displayStatusArabic`, `order.allowedNextOrderStatuses()`, and `order.canOrderBeCancelled`, ensuring unconfirmed local states are never presented as server-confirmed statuses.
11. **`app/src/test/java/com/example/MicroTask1D4OrderLifecycleIntegrationTest.kt`**:
    - Created 5 end-to-end integration tests with isolated Room databases per device rig (`customerRig`, `ownerRig`, `branch1ManagerRig`, `branch2ManagerRig`, `store2OwnerRig`) communicating over live HTTP with `test_http_server.py`.

---

## 3. Authoritative Order Lifecycle & State Machine Contract

Per the Microtask 1D.4 specification (*"إذا كانت أسماء الحالات الفعلية مختلفة، استخدم عقد Backend الحقيقي ولا تضف حالات جديدة لمجرد مطابقة هذه القائمة"*), the system uses the exact status codes defined in `backend/apps/orders/models.py` (`OrderStatus`) and enforced by `ALLOWED_ORDER_STATUS_TRANSITIONS` in `backend/apps/orders/services.py`:

| Current Server Status | Allowed Next Server Statuses | Terminal? | Can Be Cancelled? | Arabic Display Label (`Order.displayStatusArabic`) |
| :--- | :--- | :---: | :---: | :--- |
| `PENDING` | `CONFIRMED`, `CANCELLED` | No | Yes | `قيد الانتظار (PENDING)` |
| `CONFIRMED` | `PROCESSING`, `CANCELLED` | No | Yes | `تم تأكيد الطلب (CONFIRMED)` |
| `PROCESSING` | `COMPLETED`, `CANCELLED` | No | Yes | `قيد التجهيز (PROCESSING)` |
| `COMPLETED` | *(none)* | Yes | No (`400`) | `مكتمل (COMPLETED)` |
| `CANCELLED` | *(none)* | Yes | No (`409` / `400`) | `ملغي (CANCELLED)` |

### 3.1 Role & Branch Authorization Rules
- **`CUSTOMER`**:
  - Can create orders via `POST /api/v1/orders/` (`initial status = PENDING`).
  - Can list and view only their own orders (`GET /api/v1/orders/`, `GET /api/v1/orders/{id}/`); accessing another customer's order returns `403 Forbidden`.
  - Cannot transition or cancel orders (`POST /api/v1/orders/{id}/transition-status/` and `POST /api/v1/orders/{id}/cancel/` return `403 Forbidden`).
- **`BRANCH_MANAGER` (`UserRole.STORE_ADMIN`)**:
  - Can list, view, transition, and cancel orders strictly within their `authorized_branch_ids`.
  - Filtering or accessing another branch's order returns `403 Forbidden`.
- **`OWNER` (`UserRole.STORE_OWNER`)**:
  - Can list, view, transition, and cancel orders across all branches belonging to their store (`authorized_branch_ids`).
  - Filtering or accessing another store's branch/order returns `403 Forbidden`.

### 3.2 Cancellation & Stock Restoration Policy
- Cancelling an order from `PENDING`, `CONFIRMED`, or `PROCESSING` via `POST /api/v1/orders/{id}/cancel/` (with `Idempotency-Key` or via `transition-status` to `CANCELLED`) executes atomically inside `transaction.atomic()` with `select_for_update()` on the `Order` and `WarehouseStock` rows:
  1. Restores `OrderItem.quantity_milli` to `WarehouseStock.on_hand_milli` in the order's `fulfillment_warehouse`.
  2. Appends an immutable `StockMovement` record (`movement_type = CANCELLATION_RESTORATION`, `reference_type = ORDER_CANCEL`, `reference_id = str(order.id)`).
  3. Sets `order.status = CANCELLED` and `order.cancellation_restored = True`.
  4. Appends an `OrderStatusHistory` record (`from_status -> CANCELLED`, actor ID, reason, timestamp).
- Replaying the same `Idempotency-Key` on `POST /api/v1/orders/{id}/cancel/` returns `200 OK` (`replayed = True`) without restoring stock a second time.
- Attempting to cancel an already-cancelled order with a new key or via `transition-status` is rejected (`409 Conflict` / `400 Bad Request`) and never restores stock twice.
- Attempting to cancel a `COMPLETED` order is rejected (`400 Bad Request`).

---

## 4. Verification & Test Execution Results

### 4.1 Real vs. Simulated Infrastructure Matrix

| Test Suite | Transport / Infrastructure | Database Engine | Executed Count | Passed | Failed | Skipped |
| :--- | :--- | :--- | :---: | :---: | :---: | :---: |
| `MicroTask1D4OrderLifecyclePostgresTests` (`apps.orders.tests`) | Django DRF `APIClient` + Real ORM Transactions | **Real PostgreSQL 15.20** (`127.0.0.1:5432`) | 5 | 5 | 0 | 0 |
| `MicroTask1D4OrderLifecycleHttpContractTests` (`apps.orders.tests`) | Live TCP HTTP (`FreshContractHttpServer`) | SQLite In-Memory Reference Engine | 1 | 1 | 0 | 0 |
| Full Backend Certification (`run_postgres_certification.sh`) | Django ORM + DRF + Concurrency Threads + Live HTTP | **Real PostgreSQL 15.20** (`test_fresh_db`) | 102 | 102 | 0 | 0 |
| `MicroTask1D4OrderLifecycleIntegrationTest` (Android) | **Real OkHttp + Retrofit HTTP** (`test_http_server.py` subprocess) | Isolated Room `AppDatabase` per device rig + Backend SQLite | 5 | 5 | 0 | 0 |
| `MicroTask1D3AndroidCheckoutApiIntegrationTest` (Android) | **Real OkHttp + Retrofit HTTP** (`test_http_server.py` subprocess) | Room `AppDatabase` + Backend SQLite | 8 | 8 | 0 | 0 |
| `MicroTask1CAuthAndJwtIntegrationTest` (Android) | **Real OkHttp + Retrofit HTTP** + Encrypted `SecureTokenStorage` | Room `AppDatabase` + Backend SQLite | 10 | 10 | 0 | 0 |
| Full Android Test Suite (`gradle :app:testDebugUnitTest`) | Robolectric JVM + Live HTTP Subprocesses + Room | Room In-Memory + Live HTTP Backend | 102 | 102 | 0 | 0 |

### 4.2 Backend & PostgreSQL Certification (`bash backend/scripts/run_postgres_certification.sh`)
- **Command**: `bash backend/scripts/run_postgres_certification.sh`
- **PostgreSQL Readiness**: `127.0.0.1:5432 - accepting connections` (PostgreSQL 15.20)
- **System Checks & Migrations**:
  - `python3 manage.py check` -> `0 silenced`
  - `python3 manage.py makemigrations --check --dry-run` -> `No changes detected`
  - `python3 manage.py migrate --noinput` -> All migrations applied cleanly
- **Total Backend Tests Executed**: **102 tests in 48.588s — `OK` (102 passed, 0 failed, 0 skipped)**
  - Includes `MicroTask1D4OrderLifecyclePostgresTests`:
    1. `test_01_customer_checkout_order_appears_in_store_owner_and_branch_manager_lists`
    2. `test_02_cross_branch_cross_store_and_cross_customer_isolation`
    3. `test_03_valid_and_invalid_transitions_and_status_history_persistence`
    4. `test_04_cancellation_restores_stock_once_and_prevents_double_restoration_or_completed_cancel`
    5. `test_05_concurrent_cancellation_and_status_transitions_on_postgresql`
  - Includes `MicroTask1D4OrderLifecycleHttpContractTests`:
    1. `test_01_full_http_order_lifecycle_isolation_transitions_history_and_cancellation`

### 4.3 Android End-to-End Integration & Full Unit Test Suite (`gradle :app:testDebugUnitTest`)
- **Command**: `gradle :app:testDebugUnitTest`
- **Applet Compilation (`compile_applet`)**: `Build succeeded - the applet is compiled`
- **Total Android Tests Executed across 20 Test Suites**: **102 tests — `BUILD SUCCESSFUL` (102 passed, 0 failed, 0 skipped)**
  - `MicroTask1D4OrderLifecycleIntegrationTest` (5/5 passed):
    1. `test_01_customer_checkout_creates_server_order_and_store_owner_and_branch_manager_sync_it`
    2. `test_02_cross_branch_cross_store_and_cross_customer_isolation_over_live_http`
    3. `test_03_full_valid_order_lifecycle_and_invalid_transitions_reflected_in_customer_app_and_room`
    4. `test_04_store_owner_cancellation_restores_stock_once_and_syncs_cancelled_status_to_customer`
    5. `test_05_network_outage_during_sync_and_transition_preserves_room_cache_and_recovers_on_retry`

---

## 5. Remaining Risks & Architectural Notes

1. **Pull-Based Synchronization vs. Push Notifications**:
   - Customer and Store Owner screens synchronize order state from Django via pull-based HTTP endpoints (`GET /api/v1/orders/` and `GET /api/v1/orders/{id}/`) when screens open, when an order detail sheet is selected, or when the user triggers a manual refresh. Real-time push notifications (FCM / WebSockets) are outside the scope of Phase 1D and can be layered in a future phase without changing the REST contract.
2. **Catalog Stock vs. Warehouse Stock Endpoint**:
   - Public catalog reads (`GET /api/v1/products/`) return global catalog metadata and prices, whereas authoritative warehouse stock balances live in `WarehouseStock` on PostgreSQL and are enforced atomically at checkout (`POST /api/v1/orders/`) and cancellation (`POST /api/v1/orders/{id}/cancel/`). Local Room product stock is updated after confirmed server checkout and confirmed server cancellation.
3. **Electronic Payment Gateways**:
   - Per Phase 1D scope constraints, electronic payment processing is not included; server-backed orders record server-confirmed Cash-on-Delivery / server checkout metadata.

---

## 6. Final Certification Decision

Based on full code inspection, contract alignment across Django DRF and Android Room/Retrofit/ViewModels/Compose UI, 102 passing Backend + PostgreSQL 15.20 tests (0 skipped), and 102 passing Android unit and live HTTP integration tests (0 skipped):

**Microtask 1D.4 Status**: **`CERTIFIED`**
