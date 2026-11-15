# FRESH — Microtask 1D.3: Android ↔ Django Checkout API Integration Report

- **Date**: 2026-11-13
- **Project**: `FRESH` (Android Kotlin + Jetpack Compose + MVVM + Room + Retrofit / Django 5.1 REST Framework + PostgreSQL)
- **Microtask**: `1D.3 — Android ↔ Django Checkout API Integration (Server-Authoritative Order Creation)`
- **Target Endpoint**: `POST /api/v1/orders/`
- **Final Status**: **`CERTIFIED`**

---

## 1. Files Inspected and Modified

### 1.1 Inspected Files
- **Android Network & DTO Layer**:
  - `app/src/main/java/com/example/data/api/FreshApiService.kt`
  - `app/src/main/java/com/example/data/api/model/ApiModels.kt`
- **Android Auth, Room & Repository Layer**:
  - `app/src/main/java/com/example/data/auth/AuthModels.kt`
  - `app/src/main/java/com/example/data/auth/LocalAuthRepository.kt`
  - `app/src/main/java/com/example/data/auth/SecureTokenStorage.kt`
  - `app/src/main/java/com/example/data/local/AppDatabase.kt`
  - `app/src/main/java/com/example/data/local/dao/CartDao.kt`
  - `app/src/main/java/com/example/data/local/dao/OrderDao.kt`
  - `app/src/main/java/com/example/data/local/dao/OrderItemDao.kt`
  - `app/src/main/java/com/example/data/local/model/Order.kt`
  - `app/src/main/java/com/example/data/local/model/OrderItem.kt`
  - `app/src/main/java/com/example/data/repository/CartRepository.kt`
  - `app/src/main/java/com/example/data/repository/OrderRepository.kt`
  - `app/src/main/java/com/example/data/repository/ProductRepository.kt`
  - `app/src/main/java/com/example/di/Injection.kt`
- **Android ViewModel & UI Layer**:
  - `app/src/main/java/com/example/ui/viewmodel/CartViewModel.kt`
  - `app/src/main/java/com/example/ui/viewmodel/CartViewModelFactory.kt`
  - `app/src/main/java/com/example/ui/screens/CheckoutScreen.kt`
  - `app/src/main/java/com/example/ui/navigation/AppNavigation.kt`
- **Django Backend & Contract Server**:
  - `backend/apps/orders/serializers.py`
  - `backend/apps/orders/views.py`
  - `backend/apps/orders/services.py`
  - `backend/apps/orders/test_http_server.py`
  - `docs/audits/FRESH_MICROTASK_1D_2_2_POSTGRESQL_EXECUTION_REPORT.md`

### 1.2 Modified & Created Files
| File Path | Summary of Change |
|---|---|
| `app/src/main/java/com/example/data/api/model/ApiModels.kt` | Added `CheckoutItemRequest` (`OrderItemRequest`) and `CheckoutRequest` (`OrderRequest`) validation and typealiases, `CheckoutResponse` (`OrderResponse`) typealias, and `ApiErrorDto.parseFromErrorBody(...)` to parse DRF error payloads (`{"detail": "...", "code": "..."}` and field error maps). |
| `app/src/main/java/com/example/data/api/FreshApiService.kt` | Added `@POST("orders/") suspend fun submitCheckout(@Header("Idempotency-Key") idempotencyKey: String, @Body request: CheckoutRequest): retrofit2.Response<CheckoutResponse>` to `FreshApiService`, `FreshRemoteEndpoints`, and `FreshRetrofitClient`. |
| `app/src/main/java/com/example/data/local/model/Order.kt` | Updated `OrderStatus.fromNameOrDefault` and `OrderStatus.fromNameOrNull` to map Django server statuses (`PENDING`, `CONFIRMED`, `PROCESSING`, `COMPLETED`, `CANCELLED`) cleanly to local display states while preserving the raw server status string in `Order.status`. |
| `app/src/main/java/com/example/data/local/dao/OrderItemDao.kt` | Added `deleteOrderItemsForOrder(orderId: Int)` so caching/replaying a server order replaces cached item rows atomically without creating duplicates. |
| `app/src/main/java/com/example/data/repository/OrderRepository.kt` | Injected `apiService: FreshApiService?`, implemented `executeServerCheckout(...)` against `POST /api/v1/orders/`, mapped HTTP `200`/`201`/`400`/`401`/`403`/`404`/`409`/`422`/`5xx` and `IOException`, and updated Room ONLY after server confirmation. Documented the transition plan for `executeLocalCheckout`. |
| `app/src/main/java/com/example/di/Injection.kt` | Updated `provideOrderRepository(context)` to inject `apiService = provideApiService(context)`. |
| `app/src/main/java/com/example/ui/viewmodel/CartViewModel.kt` | Routed `executeCheckout` through `orderRepository.executeServerCheckout(...)` when `hasRemoteCheckoutApi` is true, and enforced `Idempotency-Key` stability across retries of the same logical order (`activeCheckoutIdempotencyKey`). |
| `backend/apps/orders/test_http_server.py` | Initialized Django settings when standalone server starts with Django installed and aligned error JSON `'code'` fields (`insufficient_stock`, `idempotency_conflict`, `authorization_denied`, `validation_error`, `not_found`, `internal_server_error`) with `OrderViewSet` in `backend/apps/orders/views.py`. |
| `backend/apps/orders/auth.py` | Guarded SQLite helper functions (`authenticate_access_token_sqlite`, `record_failed_login_attempt`, `clear_failed_login_attempts`) against unintended default Django ORM calls when running with an explicit SQLite connection or `_skip_orm_throttle_audit=True`. |
| `backend/scripts/run_postgres_certification.sh` | Aligned PostgreSQL connection checks and targeted test class names with `backend/.env.example` and the Microtask 1D.2.1/1D.2.2 test suite. |
| `docker-compose.yml` | Configured root PostgreSQL service with `DB_NAME`, `DB_USER`, `DB_PASSWORD` from `backend/.env.example`, persistent volume `postgres_data`, and port `5432`. |
| `app/src/test/java/com/example/MicroTask1D3AndroidCheckoutApiIntegrationTest.kt` | Created 8 end-to-end Android ↔ Backend integration tests covering DTO contracts, `201 Created`, `200 OK` replay, `401`, `403`, `409` insufficient stock, `409` idempotency conflict, `422` price mismatch, `5xx` server errors, cart preservation, and pre-response confirmation blocking. |

---

## 2. Actual API Contract (`POST /api/v1/orders/`)

### 2.1 Request Headers
- `Authorization: Bearer <access_token>` (automatically attached by `FreshAuthInterceptor` for the trusted backend host; triggers single-flight `POST /api/v1/auth/refresh/` on `401` when a valid refresh token is available).
- `Idempotency-Key: <stable_logical_attempt_key>` (also included in the JSON body as `idempotency_key`).

### 2.2 Request Payload (`CheckoutRequest` / `OrderRequest`)
```json
{
  "branch_id": 1,
  "fulfillment_warehouse_id": null,
  "idempotency_key": "chk_550e8400-e29b-41d4-a716-446655440000",
  "items": [
    {
      "product_id": 1,
      "quantity_milli": 1500
    }
  ]
}
```
- **Forbidden Client Fields (Never Sent by Android & Explicitly Rejected by Django)**:
  - Identity/Role: `customer_id`, `user_id`, `actor_id`, `role`, `actor_role`, `store_id`, `authorized_branch_ids`, `is_staff`, `is_superuser`.
  - Financial: `total_halalas`, `subtotal_halalas`, `unit_price_halalas`, `price_halalas`, `line_total_halalas`, `discount_halalas`, `delivery_fee_halalas`, `effective_price_halalas`, `override_price_halalas`.
  - Stock: `stock_quantity`, `on_hand_milli`, `reserved_milli`, `available_milli`, `stock_milli`, `balance_after_milli`.

### 2.3 Response Payload (`CheckoutResponse` / `OrderResponse`)
- **`201 Created`** (newly created order, `replayed: false`) or **`200 OK`** (idempotent replay of existing order, `replayed: true`):
```json
{
  "id": 1,
  "branch": 1,
  "branch_id": 1,
  "fulfillment_warehouse": 1,
  "fulfillment_warehouse_id": 1,
  "customer_id": "cust-1",
  "status": "PENDING",
  "total_halalas": 3493,
  "idempotency_key": "chk_550e8400-e29b-41d4-a716-446655440000",
  "cancellation_restored": false,
  "timestamp": "2026-11-13T15:00:00Z",
  "updated_at": "2026-11-13T15:00:00Z",
  "replayed": false,
  "items": [
    {
      "id": 1,
      "product": 1,
      "product_id": 1,
      "product_name_snapshot": "طماطم محمية طازجة",
      "unit_snapshot": "كيلو",
      "quantity_milli": 1500,
      "unit_price_halalas": 795,
      "line_total_halalas": 1193
    }
  ],
  "status_history": [
    {
      "id": 1,
      "from_status": null,
      "to_status": "PENDING",
      "changed_by_actor_id": "customer:cust-1",
      "reason": "Order created via atomic checkout",
      "changed_at": "2026-11-13T15:00:00Z"
    }
  ]
}
```

### 2.4 HTTP Status Code Mapping in Android (`OrderRepository`)
| HTTP Status | Server Condition | Android `CheckoutOutcome` / `CheckoutFailureReason` | Cart & Room Behavior |
|---|---|---|---|
| `201 Created` | New order created atomically on server | `CheckoutOutcome.Success(httpStatusCode = 201, isReplayed = false)` | Caches server `Order` & `OrderItem`s in Room, updates local cached product stock once, clears purchased cart items. |
| `200 OK` | Idempotent replay of existing order (`replayed = true`) | `CheckoutOutcome.Success(httpStatusCode = 200, isReplayed = true)` | Upserts server `Order` & `OrderItem`s in Room without duplicating rows or deducting local cached stock a second time; clears purchased cart items. |
| `400 Bad Request` | Invalid payload or rejected client field | `CheckoutFailureReason.VALIDATION_ERROR` (or `PRICE_MISMATCH_OR_UNPROCESSABLE`) | Cart preserved, Room untouched, `lastCompletedOrder = null`. |
| `401 Unauthorized` | Unauthenticated or expired/revoked session | `CheckoutFailureReason.UNAUTHENTICATED_SESSION` | Cart preserved, Room untouched, `Idempotency-Key` preserved. |
| `403 Forbidden` | Non-customer role (`OWNER` / `BRANCH_MANAGER`) or inactive profile | `CheckoutFailureReason.FORBIDDEN_OR_UNAUTHORIZED` | Cart preserved, Room untouched. |
| `409 Conflict` (`insufficient_stock`) | Requested `quantity_milli` exceeds warehouse stock | `CheckoutFailureReason.INSUFFICIENT_STOCK` | Cart preserved, Room untouched. |
| `409 Conflict` (`idempotency_conflict`) | Same `Idempotency-Key` reused with different payload | `CheckoutFailureReason.IDEMPOTENCY_CONFLICT` | Cart preserved, Room untouched; rotates `Idempotency-Key` for next attempt. |
| `422 Unprocessable Entity` | Price mismatch or semantic validation failure | `CheckoutFailureReason.PRICE_MISMATCH_OR_UNPROCESSABLE` | Cart preserved, Room untouched, `Idempotency-Key` preserved. |
| `5xx` (`500..599`) | Server / database error | `CheckoutFailureReason.SERVER_ERROR` | Cart preserved, Room untouched, `Idempotency-Key` preserved for safe retry. |

---

## 3. Checkout Flow Before and After Modification

### 3.1 Before Microtask 1D.3
1. `Injection.provideOrderRepository(context)` constructed `OrderRepository` without `FreshApiService`.
2. `CartViewModel.executeCheckout` called `orderRepository.executeLocalCheckout(...)`, which calculated prices and totals locally in Room, deducted stock locally in SQLite, generated a local `CONFIRMED_LOCAL` order, and never called `POST /api/v1/orders/`.
3. On any checkout failure, `CartViewModel.executeCheckout` immediately generated a brand-new `currentCheckoutIdempotencyKey`, breaking idempotency across retries of the same order.

### 3.2 After Microtask 1D.3
1. `Injection.provideOrderRepository(context)` injects the authenticated `FreshApiService` into `OrderRepository`.
2. `CartViewModel.executeCheckout` binds `currentCheckoutIdempotencyKey` to the cart's item/quantity fingerprint (`productId:quantityMillis`) and keeps the **exact same `Idempotency-Key`** across retries of the same logical order (rotating only when the cart contents are modified, when a completed order confirmation is dismissed, or on `409 IDEMPOTENCY_CONFLICT`).
3. `OrderRepository.executeServerCheckout` sends `POST /api/v1/orders/` with `Authorization: Bearer <access_token>` and `Idempotency-Key: <key>`, containing only `branch_id`, `fulfillment_warehouse_id`, `idempotency_key`, and `items[{product_id, quantity_milli}]`.
4. While the request is in flight (`isSubmittingCheckout == true`), `lastCompletedOrder` remains `null`, no order is inserted into Room, and the cart is not cleared.
5. Only after receiving `201 Created` or `200 OK` from Django does `OrderRepository` cache the server-authoritative `Order` (`id`, `status`, `total_halalas`, `customer_id`, `timestamp`) and `OrderItem` snapshots (`unit_price_halalas`, `line_total_halalas`, `product_name_snapshot`, `unit_snapshot`) into Room and remove the purchased items from the local cart.
6. **Transition Plan for `executeLocalCheckout`**: Retained on `OrderRepository` strictly for legacy offline/local unit tests that construct `OrderRepository(database)` without an `apiService` (`AtomicLocalCheckoutAndOrderIntegrityTest`). Production code wired via `Injection` always sets `apiService` and routes exclusively through `executeServerCheckout`.

---

## 4. Executed Tests and Results

### 4.1 Android Unit & Robolectric Integration Tests
Executed via Gradle (`gradle :app:testDebugUnitTest`):
- **`com.example.MicroTask1D3AndroidCheckoutApiIntegrationTest`** (`8` tests — **8 PASSED, 0 FAILED, 0 SKIPPED**):
  1. `test_01_checkout_dto_serialization_and_deserialization_matches_django_contract_and_omits_forbidden_fields` — **PASSED**
  2. `test_02_successful_server_checkout_201_creates_order_with_server_prices_and_caches_in_room` — **PASSED**
  3. `test_03_retry_with_same_idempotency_key_returns_200_replayed_without_duplicate_order_or_double_stock_deduction` — **PASSED**
  4. `test_04_expired_or_unauthenticated_session_401_rejects_checkout_and_preserves_cart` — **PASSED**
  5. `test_05_non_customer_role_403_rejects_checkout_and_preserves_cart` — **PASSED**
  6. `test_06_insufficient_server_stock_409_rejects_checkout_and_preserves_cart` — **PASSED**
  7. `test_07_handling_409_idempotency_conflict_422_price_mismatch_and_5xx_retry_with_same_key` — **PASSED**
  8. `test_08_order_never_confirmed_before_server_responds_and_backend_rejects_forbidden_client_fields` — **PASSED**
- **`com.example.MicroTask1BApiContractAlignmentTest`** (`5` tests — **5 PASSED, 0 FAILED**)
- **`com.example.MicroTask1CAuthAndJwtIntegrationTest`** (`10` tests — **10 PASSED, 0 FAILED**)
- **`com.example.AtomicLocalCheckoutAndOrderIntegrityTest`** (`10` tests — **10 PASSED, 0 FAILED**)

### 4.2 Backend Django + PostgreSQL Certification Suite
- Executed `python3 manage.py test apps.orders apps.products --verbosity 2` against live PostgreSQL:
  - **96 tests ran, 96 PASSED, 0 FAILED, 0 SKIPPED**.

---

## 5. Unexecuted Tests
- **None** (`0` skipped or unexecuted tests across both the Android Checkout API integration suite and the Django/PostgreSQL backend test suite).

---

## 6. Remaining Risks & Architectural Notes
1. **Remote Order History Synchronization (Future Scope)**: `executeServerCheckout` caches newly created/replayed server orders into Room immediately upon `201`/`200` confirmation. Full background synchronization of historical orders (`GET /api/v1/orders/`) and staff status transitions across devices belongs to subsequent order-sync microtasks.
2. **Electronic Payment Gateway**: Out of scope for Microtask 1D.3; orders are created with server status `PENDING` under cash-on-delivery (`SERVER_CHECKOUT_PAYMENT_NOTE`).

---

## 7. Final Task Status
**`CERTIFIED`**
