package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.api.FreshApiService
import com.example.data.api.model.ApiErrorDto
import com.example.data.api.model.CancelOrderRequestDto
import com.example.data.api.model.CheckoutItemRequest
import com.example.data.api.model.CheckoutRequest
import com.example.data.api.model.CheckoutResponse
import com.example.data.api.model.OrderResponse
import com.example.data.api.model.OrderStatusHistoryDto
import com.example.data.api.model.OrderStatusTransitionRequestDto
import com.example.data.auth.AccessControlDecision
import com.example.data.auth.AccessControlPolicy
import com.example.data.auth.AuthenticatedUser
import com.example.data.auth.UserRole
import com.example.data.local.AppDatabase
import com.example.data.local.dao.CartDao
import com.example.data.local.dao.OrderDao
import com.example.data.local.dao.OrderItemDao
import com.example.data.local.dao.ProductDao
import com.example.data.local.dao.StockAdjustmentDao
import com.example.data.local.model.CurrencyCalculator
import com.example.data.local.model.Order
import com.example.data.local.model.OrderItem
import com.example.data.local.model.OrderStatus
import com.example.data.local.model.QuantityFormatter
import com.example.data.local.model.StockAdjustment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

enum class CheckoutFailureReason {
    EMPTY_CART,
    PRODUCT_NOT_FOUND,
    PRODUCT_INACTIVE_OR_ARCHIVED,
    OUT_OF_STOCK,
    INSUFFICIENT_STOCK,
    INVALID_QUANTITY,
    INVALID_OR_STALE_PRICE,
    DUPLICATE_SUBMISSION,
    TRANSACTION_ERROR,
    VALIDATION_ERROR,
    UNAUTHENTICATED_SESSION,
    FORBIDDEN_OR_UNAUTHORIZED,
    IDEMPOTENCY_CONFLICT,
    PRICE_MISMATCH_OR_UNPROCESSABLE,
    SERVER_ERROR,
    NETWORK_ERROR;

    companion object {
        val STALE_OR_INVALID_PRICE: CheckoutFailureReason = INVALID_OR_STALE_PRICE
    }
}

enum class OrderTransitionFailureReason {
    UNAUTHORIZED,
    ORDER_NOT_FOUND,
    INVALID_STATUS_TRANSITION,
    ALREADY_CANCELLED,
    COMPLETED_ORDER_CANNOT_BE_CANCELLED,
    PRODUCT_NOT_FOUND_FOR_RESTORATION,
    TRANSACTION_ERROR,
    NETWORK_ERROR,
    SERVER_ERROR
}

sealed class OrderTransitionOutcome {
    data class Success(
        val updatedOrder: Order,
        val items: List<OrderItem>,
        val previousStatus: OrderStatus,
        val newStatus: OrderStatus,
        val restoredStockAdjustments: List<StockAdjustment> = emptyList(),
        val messageArabic: String,
        val statusHistory: List<OrderStatusHistoryDto> = emptyList(),
        val httpStatusCode: Int = 200,
        val isReplayed: Boolean = false
    ) : OrderTransitionOutcome()

    data class Failure(
        val reason: OrderTransitionFailureReason,
        val messageArabic: String,
        val currentOrder: Order? = null,
        val httpStatusCode: Int? = null
    ) : OrderTransitionOutcome()
}

sealed class OrderSyncOutcome {
    data class Success(
        val orders: List<OrderWithItems>,
        val httpStatusCode: Int = 200
    ) : OrderSyncOutcome()

    data class Failure(
        val reason: OrderTransitionFailureReason,
        val messageArabic: String,
        val httpStatusCode: Int? = null,
        val cachedOrders: List<OrderWithItems> = emptyList()
    ) : OrderSyncOutcome()
}

private class OrderTransitionRollbackException(
    val reason: OrderTransitionFailureReason,
    val messageArabic: String,
    val currentOrder: Order? = null
) : RuntimeException(messageArabic)

data class OrderWithItems(
    val order: Order,
    val items: List<OrderItem>,
    val statusHistory: List<OrderStatusHistoryDto> = emptyList(),
    val branchId: Int? = null
)

sealed class CheckoutOutcome {
    data class Success(
        val order: Order,
        val items: List<OrderItem>,
        val stockAdjustments: List<StockAdjustment> = emptyList(),
        val httpStatusCode: Int = 201,
        val isReplayed: Boolean = false
    ) : CheckoutOutcome()

    data class Failure(
        val reason: CheckoutFailureReason,
        val messageArabic: String,
        val failedProductId: Int? = null,
        val httpStatusCode: Int? = null,
        val serverErrorCode: String? = null
    ) : CheckoutOutcome()
}

private class CheckoutRollbackException(
    val reason: CheckoutFailureReason,
    val messageArabic: String,
    val failedProductId: Int? = null
) : RuntimeException(messageArabic)

/**
 * Repository managing server-authoritative checkout (`POST /api/v1/orders/`), Room local cache
 * synchronization after server confirmation, and legacy local order flows.
 *
 * Architectural Authority Rules (Microtask 1D.3):
 * 1. Django + PostgreSQL is the single source of truth for prices, order totals, inventory
 *    availability, stock deductions, order creation, order IDs, and order statuses.
 * 2. [executeServerCheckout] sends ONLY `branch_id`, optional `fulfillment_warehouse_id`,
 *    `idempotency_key`, and `items[{product_id, quantity_milli}]`. It never sends client prices,
 *    totals, stock quantities, `customer_id`, `actor_id`, or `role`.
 * 3. Room is updated ONLY after receiving a confirmed `201 Created` or `200 OK` response from
 *    the server. On any error (`400`, `401`, `403`, `404`, `409`, `422`, `5xx`, or `IOException`),
 *    the local cart and Room tables remain untouched.
 * 4. Transition Plan for [executeLocalCheckout]: retained strictly for legacy local-only unit tests
 *    where [apiService] is not provided (`apiService == null`). Production DI ([com.example.di.Injection])
 *    always wires [apiService], routing checkout exclusively through [executeServerCheckout].
 */
class OrderRepository(
    private val database: AppDatabase,
    private val productDao: ProductDao = database.productDao(),
    private val cartDao: CartDao = database.cartDao(),
    private val orderDao: OrderDao = database.orderDao(),
    private val orderItemDao: OrderItemDao = database.orderItemDao(),
    private val stockAdjustmentDao: StockAdjustmentDao = database.stockAdjustmentDao(),
    private val checkoutMutex: Mutex = Mutex(),
    private val apiService: FreshApiService? = null
) {
    internal val synchronizationMutex: Mutex
        get() = checkoutMutex

    val hasRemoteCheckoutApi: Boolean
        get() = apiService != null

    private val completedIdempotencyKeys = mutableSetOf<String>()
    private val restoredOrderIds = mutableSetOf<Int>()
    private val requestPayloadByIdempotencyKey = mutableMapOf<String, CheckoutRequest>()
    private val syncedServerOrderIds = mutableSetOf<Int>()
    private val statusHistoryByOrderId = ConcurrentHashMap<Int, List<OrderStatusHistoryDto>>()
    private val branchIdByOrderId = ConcurrentHashMap<Int, Int>()

    fun getCachedStatusHistoryForOrder(orderId: Int): List<OrderStatusHistoryDto> {
        return statusHistoryByOrderId[orderId].orEmpty()
    }

    fun getCachedBranchIdForOrder(orderId: Int): Int? {
        return branchIdByOrderId[orderId]
    }

    /**
     * Test-only hook to simulate an unexpected database exception mid-transaction after the first item's stock
     * has already been deducted, proving Room rolls back all changes atomically.
     */
    var failAfterFirstStockDeductionForTesting: Boolean = false

    /**
     * Test-only hook to simulate an unexpected database exception mid-cancellation after the first item's stock
     * has been restored, proving Room rolls back all stock restorations and status changes atomically.
     */
    var failAfterFirstStockRestorationForTesting: Boolean = false

    /**
     * Reactive stream of orders with their item snapshots strictly filtered for [customerId].
     * When [actor] is provided, enforces that [customerId] matches [actor]'s ID so a customer
     * cannot read another customer's orders by supplying a different customer ID.
     */
    fun getOrdersWithItemsForCustomer(
        customerId: String,
        actor: AuthenticatedUser? = null
    ): Flow<List<OrderWithItems>> {
        val cleanCustomerId = customerId.trim().ifBlank { GUEST_CUSTOMER_ID }
        val actorCustomerId = actor?.customerId?.trim()?.takeIf { it.isNotEmpty() } ?: actor?.id?.trim()
        if (actor != null && actorCustomerId != cleanCustomerId && actor.id.trim() != cleanCustomerId) {
            return flowOf(emptyList())
        }
        val allowedCustomerIds = buildSet {
            add(cleanCustomerId)
            if (actor != null) {
                actor.id.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                actor.customerId?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        return combine(
            orderDao.getOrders(),
            orderItemDao.getAllOrderItems()
        ) { orders, allItems ->
            val itemsByOrderId = allItems.groupBy { it.orderId }
            orders.filter { it.customerId in allowedCustomerIds }.map { order ->
                OrderWithItems(
                    order = order,
                    items = itemsByOrderId[order.id].orEmpty(),
                    statusHistory = statusHistoryByOrderId[order.id].orEmpty(),
                    branchId = branchIdByOrderId[order.id]
                )
            }
        }
    }

    /**
     * Reactive stream of orders for the currently authenticated customer (or local guest).
     * Rejects any attempt where [requestedCustomerId] does not match the caller's actual identity.
     */
    fun getOrdersForAuthenticatedCustomer(
        actor: AuthenticatedUser?,
        requestedCustomerId: String? = null
    ): Flow<List<OrderWithItems>> {
        val effectiveActorCustomerId = actor?.customerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: actor?.id?.trim()?.ifBlank { GUEST_CUSTOMER_ID }
            ?: GUEST_CUSTOMER_ID
        if (requestedCustomerId != null &&
            requestedCustomerId.trim().isNotEmpty() &&
            requestedCustomerId.trim() != effectiveActorCustomerId &&
            requestedCustomerId.trim() != actor?.id?.trim()
        ) {
            return flowOf(emptyList())
        }
        return getOrdersWithItemsForCustomer(
            customerId = effectiveActorCustomerId,
            actor = actor
        )
    }

    /**
     * Loads a single order with its item snapshots for a customer, strictly verifying that
     * the order belongs to [actor] (or [GUEST_CUSTOMER_ID] when unauthenticated) and that
     * [requestedCustomerId] was not manipulated to another customer's ID.
     */
    suspend fun getOrderDetailsForCustomerOnce(
        orderId: Int,
        actor: AuthenticatedUser?,
        requestedCustomerId: String? = null
    ): OrderWithItems? = withContext(Dispatchers.IO) {
        val effectiveActorCustomerId = actor?.customerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: actor?.id?.trim()?.ifBlank { GUEST_CUSTOMER_ID }
            ?: GUEST_CUSTOMER_ID
        if (requestedCustomerId != null &&
            requestedCustomerId.trim().isNotEmpty() &&
            requestedCustomerId.trim() != effectiveActorCustomerId &&
            requestedCustomerId.trim() != actor?.id?.trim()
        ) {
            return@withContext null
        }
        val order = orderDao.getOrderById(orderId) ?: return@withContext null
        if (order.customerId != effectiveActorCustomerId && order.customerId != actor?.id?.trim()) {
            return@withContext null
        }
        val items = orderItemDao.getOrderItemsOnce(orderId)
        OrderWithItems(
            order = order,
            items = items,
            statusHistory = statusHistoryByOrderId[orderId].orEmpty(),
            branchId = branchIdByOrderId[orderId]
        )
    }

    /**
     * Loads a single order with its item snapshots for authorized Store Owners / Store Admins only.
     * Customers and unauthenticated users receive `null`.
     */
    suspend fun getOrderDetailsForStoreManagementOnce(
        orderId: Int,
        actor: AuthenticatedUser?
    ): OrderWithItems? = withContext(Dispatchers.IO) {
        val decision = AccessControlPolicy.checkAccess(
            user = actor,
            requiresAuth = true,
            requiresStoreManagement = true
        )
        if (decision !is AccessControlDecision.Allowed) {
            return@withContext null
        }
        val order = orderDao.getOrderById(orderId) ?: return@withContext null
        val orderBranch = branchIdByOrderId[orderId]
        val allowedBranches = actor?.authorizedBranchIds
        if (orderBranch != null && !allowedBranches.isNullOrEmpty() && orderBranch !in allowedBranches) {
            return@withContext null
        }
        val items = orderItemDao.getOrderItemsOnce(orderId)
        OrderWithItems(
            order = order,
            items = items,
            statusHistory = statusHistoryByOrderId[orderId].orEmpty(),
            branchId = orderBranch
        )
    }

    /**
     * Reactive stream of all persisted orders with item snapshots for authorized Store Owners
     * and Store Administrators ([AccessControlPolicy]).
     * Returns an empty list if [actor] is unauthenticated or is a [UserRole.CUSTOMER].
     */
    fun getAllOrdersWithItemsForStoreManagement(actor: AuthenticatedUser?): Flow<List<OrderWithItems>> {
        val decision = AccessControlPolicy.checkAccess(
            user = actor,
            requiresAuth = true,
            requiresStoreManagement = true
        )
        if (decision !is AccessControlDecision.Allowed) {
            return flowOf(emptyList())
        }
        val allowedBranches = actor?.authorizedBranchIds
        return combine(
            orderDao.getOrders(),
            orderItemDao.getAllOrderItems()
        ) { orders, allItems ->
            val itemsByOrderId = allItems.groupBy { it.orderId }
            orders.filter { order ->
                val orderBranch = branchIdByOrderId[order.id]
                orderBranch == null || allowedBranches.isNullOrEmpty() || orderBranch in allowedBranches
            }.map { order ->
                OrderWithItems(
                    order = order,
                    items = itemsByOrderId[order.id].orEmpty(),
                    statusHistory = statusHistoryByOrderId[order.id].orEmpty(),
                    branchId = branchIdByOrderId[order.id]
                )
            }
        }
    }

    /**
     * Reactive stream of all persisted local orders for internal store dashboard observation.
     */
    fun observeAllPersistedOrdersWithItems(): Flow<List<OrderWithItems>> {
        return combine(
            orderDao.getOrders(),
            orderItemDao.getAllOrderItems()
        ) { orders, allItems ->
            val itemsByOrderId = allItems.groupBy { it.orderId }
            orders.map { order ->
                OrderWithItems(
                    order = order,
                    items = itemsByOrderId[order.id].orEmpty(),
                    statusHistory = statusHistoryByOrderId[order.id].orEmpty(),
                    branchId = branchIdByOrderId[order.id]
                )
            }
        }
    }

    /**
     * Executes a server-authoritative checkout via `POST /api/v1/orders/` and updates the Room
     * local cache ONLY after the server responds with `201 Created` or `200 OK` (idempotent replay).
     *
     * Safety & Authority Guarantees:
     * - Never sends client-calculated prices, totals, stock quantities, `customer_id`, `actor_id`, or `role`.
     * - Sends `Idempotency-Key` in both the HTTP header and request body (`idempotency_key`).
     * - Never modifies Room (`orders`, `order_items`, `products`, `cart_items`) before server confirmation.
     * - Maps HTTP status codes explicitly:
     *   - `201 Created`: New server order confirmed (`isReplayed = false`).
     *   - `200 OK`: Previous order replayed idempotently (`isReplayed = true`); does not duplicate
     *     local Room orders or deduct cached display stock a second time.
     *   - `400 Bad Request`: [CheckoutFailureReason.VALIDATION_ERROR] (or [CheckoutFailureReason.PRICE_MISMATCH_OR_UNPROCESSABLE]).
     *   - `401 Unauthorized`: [CheckoutFailureReason.UNAUTHENTICATED_SESSION].
     *   - `403 Forbidden`: [CheckoutFailureReason.FORBIDDEN_OR_UNAUTHORIZED].
     *   - `404 Not Found`: [CheckoutFailureReason.PRODUCT_NOT_FOUND].
     *   - `409 Conflict`: [CheckoutFailureReason.INSUFFICIENT_STOCK] when `code == "insufficient_stock"`
     *     or detail indicates insufficient stock; otherwise [CheckoutFailureReason.IDEMPOTENCY_CONFLICT].
     *   - `422 Unprocessable Entity`: [CheckoutFailureReason.PRICE_MISMATCH_OR_UNPROCESSABLE].
     *   - `5xx`: [CheckoutFailureReason.SERVER_ERROR].
     *   - [IOException]: [CheckoutFailureReason.NETWORK_ERROR].
     */
    suspend fun executeServerCheckout(
        branchId: Int = DEFAULT_BRANCH_ID,
        fulfillmentWarehouseId: Int? = null,
        idempotencyKey: String,
        customerName: String = "عميل فريش",
        deliveryAddress: String = DEFAULT_DELIVERY_ADDRESS,
        fallbackCustomerId: String = GUEST_CUSTOMER_ID,
        timestampMillis: Long = System.currentTimeMillis(),
        apiServiceOverride: FreshApiService? = null
    ): CheckoutOutcome = withContext(Dispatchers.IO) {
        val activeApi = apiServiceOverride ?: apiService
            ?: return@withContext CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.NETWORK_ERROR,
                messageArabic = "خدمة الاتصال بالخادم غير مهيأة لإتمام الطلب."
            )

        val cleanIdempotencyKey = idempotencyKey.trim()
        if (cleanIdempotencyKey.isEmpty() || cleanIdempotencyKey.length > 128) {
            return@withContext CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.VALIDATION_ERROR,
                messageArabic = "مفتاح عدم التكرار (Idempotency-Key) مطلوب وصالح لإتمام الطلب."
            )
        }

        if (branchId < 1) {
            return@withContext CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.VALIDATION_ERROR,
                messageArabic = "رقم الفرع غير صالح."
            )
        }

        val cleanCustomerName = customerName.trim().ifBlank { "عميل فريش" }
        val cleanAddress = deliveryAddress.trim().ifBlank { DEFAULT_DELIVERY_ADDRESS }

        // 1. Build CheckoutRequest from current Room cart items (or reuse the cached request if this
        //    is an idempotent retry of an already-cleared cart with the exact same idempotencyKey).
        val cartItems = cartDao.getCartItemsOnce()
        val requestDto: CheckoutRequest = if (cartItems.isNotEmpty()) {
            val combinedMillisByProductId = linkedMapOf<Int, Long>()
            for (cartItem in cartItems) {
                val pid = cartItem.productId
                val qtyMillis = cartItem.effectiveQuantityMillis.toLong()
                if (pid < 1 || qtyMillis <= 0L || cartItem.quantity < 0) {
                    return@withContext CheckoutOutcome.Failure(
                        reason = CheckoutFailureReason.INVALID_QUANTITY,
                        messageArabic = "الكمية المطلوبة للمنتج رقم ($pid) غير صالحة.",
                        failedProductId = pid
                    )
                }
                combinedMillisByProductId[pid] = (combinedMillisByProductId[pid] ?: 0L) + qtyMillis
            }
            val built = CheckoutRequest(
                items = combinedMillisByProductId.map { (pid, millis) ->
                    CheckoutItemRequest(productId = pid, quantityMilli = millis)
                },
                branchId = branchId,
                fulfillmentWarehouseId = fulfillmentWarehouseId,
                idempotencyKey = cleanIdempotencyKey
            )
            synchronized(requestPayloadByIdempotencyKey) {
                requestPayloadByIdempotencyKey.putIfAbsent(cleanIdempotencyKey, built)
            }
            built
        } else {
            val previousRequest = synchronized(requestPayloadByIdempotencyKey) {
                requestPayloadByIdempotencyKey[cleanIdempotencyKey]
            }
            if (previousRequest == null) {
                return@withContext CheckoutOutcome.Failure(
                    reason = CheckoutFailureReason.EMPTY_CART,
                    messageArabic = "سلة التسوق فارغة حالياً. يرجى إضافة منتجات قبل إتمام الطلب."
                )
            }
            previousRequest
        }

        // 2. Execute network call to POST /api/v1/orders/ BEFORE touching Room orders/stock/cart
        val response = try {
            activeApi.submitCheckout(
                idempotencyKey = cleanIdempotencyKey,
                request = requestDto
            )
        } catch (httpEx: HttpException) {
            val rawError = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            return@withContext mapHttpErrorToCheckoutFailure(
                statusCode = httpEx.code(),
                rawErrorBody = rawError
            )
        } catch (_: IOException) {
            return@withContext CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.NETWORK_ERROR,
                messageArabic = "تعذر الاتصال بالخادم لإتمام الطلب. لم يتم حذف السلة ويمكنك إعادة المحاولة بأمان."
            )
        } catch (e: Exception) {
            return@withContext CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.SERVER_ERROR,
                messageArabic = "حدث خطأ غير متوقع أثناء الاتصال بالخادم لإتمام الطلب."
            )
        }

        val statusCode = response.code()
        if ((statusCode == 200 || statusCode == 201) && response.body() != null) {
            val serverOrder = response.body()!!
            val isReplayed = (statusCode == 200) || (serverOrder.replayed == true)
            return@withContext cacheConfirmedServerOrderInRoom(
                serverOrder = serverOrder,
                httpStatusCode = statusCode,
                isReplayed = isReplayed,
                cleanIdempotencyKey = cleanIdempotencyKey,
                cleanCustomerName = cleanCustomerName,
                cleanAddress = cleanAddress,
                fallbackCustomerId = fallbackCustomerId,
                requestedProductIds = requestDto.items.map { it.productId },
                fallbackTimestampMillis = timestampMillis
            )
        }

        val rawErrorBody = runCatching { response.errorBody()?.string() }.getOrNull()
        mapHttpErrorToCheckoutFailure(
            statusCode = statusCode,
            rawErrorBody = rawErrorBody
        )
    }

    private suspend fun cacheConfirmedServerOrderInRoom(
        serverOrder: CheckoutResponse,
        httpStatusCode: Int,
        isReplayed: Boolean,
        cleanIdempotencyKey: String,
        cleanCustomerName: String,
        cleanAddress: String,
        fallbackCustomerId: String,
        requestedProductIds: List<Int>,
        fallbackTimestampMillis: Long
    ): CheckoutOutcome = checkoutMutex.withLock {
        database.withTransaction {
            val existingById = orderDao.getOrderById(serverOrder.id)
            val existingByKey = if (cleanIdempotencyKey.isNotEmpty()) {
                orderDao.getOrderByIdempotencyKey(cleanIdempotencyKey)
            } else {
                null
            }
            val alreadySyncedInRoom = existingById != null ||
                existingByKey != null ||
                syncedServerOrderIds.contains(serverOrder.id)

            val parsedTimestampMillis = parseIsoTimestampToEpochMillis(
                rawTimestamp = serverOrder.timestamp,
                fallbackMillis = fallbackTimestampMillis
            )
            val authoritativeCustomerId = serverOrder.customerId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: fallbackCustomerId.trim().ifBlank { GUEST_CUSTOMER_ID }
            val authoritativeTotalHalalas = serverOrder.totalHalalas.toInt()
            val authoritativeSubtotalHalalas = if (serverOrder.items.isNotEmpty()) {
                serverOrder.items.sumOf { it.lineTotalHalalas }.toInt()
            } else {
                authoritativeTotalHalalas
            }
            val formattedOrderNumber = "FRESH-${1000 + serverOrder.id}"
            val effectiveIdempotencyKey = serverOrder.idempotencyKey
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: cleanIdempotencyKey

            val cachedOrder = Order(
                id = serverOrder.id,
                totalHalalas = authoritativeTotalHalalas,
                timestamp = parsedTimestampMillis,
                orderNumber = formattedOrderNumber,
                customerId = authoritativeCustomerId,
                customerName = cleanCustomerName,
                status = serverOrder.status.trim().ifBlank { "PENDING" },
                subtotalHalalas = authoritativeSubtotalHalalas,
                deliveryFeeHalalas = 0,
                discountHalalas = 0,
                deliveryAddressSnapshot = cleanAddress,
                paymentModeNote = SERVER_CHECKOUT_PAYMENT_NOTE,
                idempotencyKey = effectiveIdempotencyKey
            )
            orderDao.insert(cachedOrder)

            // Replace cached OrderItem snapshots for this orderId so replays never duplicate items
            orderItemDao.deleteOrderItemsForOrder(serverOrder.id)
            val cachedItems = mutableListOf<OrderItem>()
            for (itemDto in serverOrder.items) {
                val qtyMillis = itemDto.quantityMilli.toInt()
                val wholeQty = if (qtyMillis > 0) ceil(qtyMillis / 1000.0).toInt() else 0
                val entity = OrderItem(
                    id = if (itemDto.id > 0) itemDto.id else 0,
                    orderId = serverOrder.id,
                    productId = itemDto.productId,
                    productName = itemDto.productNameSnapshot,
                    unit = itemDto.unitSnapshot,
                    quantity = wholeQty,
                    quantityMillis = qtyMillis,
                    priceHalalas = itemDto.unitPriceHalalas.toInt(),
                    lineTotalHalalas = itemDto.lineTotalHalalas.toInt()
                )
                val insertedId = orderItemDao.insert(entity).toInt()
                cachedItems.add(entity.copy(id = insertedId))

                // Update local Room Product cache only on the first confirmation of this server order
                if (!alreadySyncedInRoom && !isReplayed) {
                    val localProduct = productDao.getProductById(itemDto.productId)
                    if (localProduct != null) {
                        val updatedStockMillis = (localProduct.effectiveStockMillis - qtyMillis).coerceAtLeast(0)
                        val syncedProduct = localProduct
                            .withUpdatedStockMillis(
                                newStockMillis = updatedStockMillis,
                                updatedTimestampMillis = parsedTimestampMillis
                            )
                            .copy(priceHalalas = itemDto.unitPriceHalalas.toInt())
                        productDao.insert(syncedProduct)
                    }
                }
            }

            val productIdsToClear = (serverOrder.items.map { it.productId } + requestedProductIds).distinct()
            if (productIdsToClear.isNotEmpty()) {
                cartDao.deleteByProductIds(productIdsToClear)
            }

            syncedServerOrderIds.add(serverOrder.id)
            if (serverOrder.statusHistory.isNotEmpty()) {
                statusHistoryByOrderId[serverOrder.id] = serverOrder.statusHistory
            }
            if (serverOrder.branchId != null) {
                branchIdByOrderId[serverOrder.id] = serverOrder.branchId
            }
            if (effectiveIdempotencyKey.isNotEmpty()) {
                completedIdempotencyKeys.add(effectiveIdempotencyKey)
            }

            CheckoutOutcome.Success(
                order = cachedOrder,
                items = cachedItems,
                stockAdjustments = emptyList(),
                httpStatusCode = httpStatusCode,
                isReplayed = isReplayed
            )
        }
    }

    /**
     * Upserts a single [OrderResponse] and its [OrderItemDto] snapshots from the Django backend
     * into the local Room cache inside a transaction, preserving any locally stored customer name
     * or delivery address snapshot for that order ID.
     */
    private suspend fun upsertServerOrderDtoInRoom(
        serverOrder: OrderResponse,
        fallbackCustomerId: String = GUEST_CUSTOMER_ID,
        fallbackCustomerName: String = "عميل فريش",
        fallbackAddress: String = DEFAULT_DELIVERY_ADDRESS,
        fallbackTimestampMillis: Long = System.currentTimeMillis()
    ): OrderWithItems = database.withTransaction {
        val existingOrder = orderDao.getOrderById(serverOrder.id)
        val parsedTimestampMillis = parseIsoTimestampToEpochMillis(
            rawTimestamp = serverOrder.timestamp,
            fallbackMillis = existingOrder?.timestamp ?: fallbackTimestampMillis
        )
        val authoritativeCustomerId = serverOrder.customerId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: existingOrder?.customerId
            ?: fallbackCustomerId.trim().ifBlank { GUEST_CUSTOMER_ID }
        val authoritativeTotalHalalas = serverOrder.totalHalalas.toInt()
        val authoritativeSubtotalHalalas = if (serverOrder.items.isNotEmpty()) {
            serverOrder.items.sumOf { it.lineTotalHalalas }.toInt()
        } else {
            existingOrder?.subtotalHalalas ?: authoritativeTotalHalalas
        }
        val formattedOrderNumber = existingOrder?.orderNumber?.takeIf { it.isNotBlank() }
            ?: "FRESH-${1000 + serverOrder.id}"
        val effectiveIdempotencyKey = serverOrder.idempotencyKey
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: existingOrder?.idempotencyKey.orEmpty()

        val cachedOrder = Order(
            id = serverOrder.id,
            totalHalalas = authoritativeTotalHalalas,
            timestamp = parsedTimestampMillis,
            orderNumber = formattedOrderNumber,
            customerId = authoritativeCustomerId,
            customerName = existingOrder?.customerName?.takeIf { it.isNotBlank() } ?: fallbackCustomerName,
            status = serverOrder.status.trim().ifBlank { "PENDING" },
            subtotalHalalas = authoritativeSubtotalHalalas,
            deliveryFeeHalalas = existingOrder?.deliveryFeeHalalas ?: 0,
            discountHalalas = existingOrder?.discountHalalas ?: 0,
            deliveryAddressSnapshot = existingOrder?.deliveryAddressSnapshot?.takeIf { it.isNotBlank() } ?: fallbackAddress,
            paymentModeNote = SERVER_CHECKOUT_PAYMENT_NOTE,
            idempotencyKey = effectiveIdempotencyKey
        )
        orderDao.insert(cachedOrder)

        val cachedItems = if (serverOrder.items.isNotEmpty()) {
            orderItemDao.deleteOrderItemsForOrder(serverOrder.id)
            val list = mutableListOf<OrderItem>()
            for (itemDto in serverOrder.items) {
                val qtyMillis = itemDto.quantityMilli.toInt()
                val wholeQty = if (qtyMillis > 0) ceil(qtyMillis / 1000.0).toInt() else 0
                val entity = OrderItem(
                    id = if (itemDto.id > 0) itemDto.id else 0,
                    orderId = serverOrder.id,
                    productId = itemDto.productId,
                    productName = itemDto.productNameSnapshot,
                    unit = itemDto.unitSnapshot,
                    quantity = wholeQty,
                    quantityMillis = qtyMillis,
                    priceHalalas = itemDto.unitPriceHalalas.toInt(),
                    lineTotalHalalas = itemDto.lineTotalHalalas.toInt()
                )
                val insertedId = orderItemDao.insert(entity).toInt()
                list.add(entity.copy(id = insertedId))
            }
            list
        } else {
            orderItemDao.getOrderItemsOnce(serverOrder.id)
        }

        syncedServerOrderIds.add(serverOrder.id)
        if (serverOrder.statusHistory.isNotEmpty()) {
            statusHistoryByOrderId[serverOrder.id] = serverOrder.statusHistory
        }
        if (serverOrder.branchId != null) {
            branchIdByOrderId[serverOrder.id] = serverOrder.branchId
        }
        if (serverOrder.cancellationRestored || serverOrder.status.equals("CANCELLED", ignoreCase = true)) {
            restoredOrderIds.add(serverOrder.id)
        }

        OrderWithItems(
            order = cachedOrder,
            items = cachedItems,
            statusHistory = statusHistoryByOrderId[serverOrder.id].orEmpty(),
            branchId = branchIdByOrderId[serverOrder.id]
        )
    }

    /**
     * Synchronizes the authenticated customer's orders from `GET /api/v1/orders/` into Room.
     * On transient network outage ([IOException]), preserves existing cached orders in Room and
     * returns them in [OrderSyncOutcome.Failure.cachedOrders].
     */
    suspend fun syncCustomerOrdersFromServer(
        actor: AuthenticatedUser?,
        statusFilter: String? = null,
        apiServiceOverride: FreshApiService? = null
    ): OrderSyncOutcome = withContext(Dispatchers.IO) {
        if (actor == null || actor.role != UserRole.CUSTOMER) {
            return@withContext OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.UNAUTHORIZED,
                messageArabic = "يجب تسجيل الدخول بحساب عميل لعرض ومزامنة الطلبات من الخادم."
            )
        }
        val activeApi = apiServiceOverride ?: apiService
            ?: return@withContext OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.NETWORK_ERROR,
                messageArabic = "خدمة الاتصال بالخادم غير مهيأة لمزامنة الطلبات."
            )
        val fallbackCustomerId = actor.customerId?.trim()?.takeIf { it.isNotEmpty() } ?: actor.id

        val response = try {
            activeApi.getOrders(status = statusFilter)
        } catch (httpEx: HttpException) {
            val rawError = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            return@withContext mapHttpErrorToSyncFailure(httpEx.code(), rawError, fallbackCustomerId)
        } catch (_: IOException) {
            val cached = loadCachedOrdersForCustomerOnce(actor)
            return@withContext OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.NETWORK_ERROR,
                messageArabic = "تعذر الاتصال بالخادم لتحديث حالة الطلبات؛ يتم عرض النسخة المحفوظة محلياً.",
                cachedOrders = cached
            )
        } catch (_: Exception) {
            val cached = loadCachedOrdersForCustomerOnce(actor)
            return@withContext OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.SERVER_ERROR,
                messageArabic = "حدث خطأ غير متوقع أثناء مزامنة الطلبات من الخادم.",
                cachedOrders = cached
            )
        }

        if (response.isSuccessful && response.body() != null) {
            val serverOrders = response.body()!!
            val synced = checkoutMutex.withLock {
                serverOrders.map { dto ->
                    upsertServerOrderDtoInRoom(
                        serverOrder = dto,
                        fallbackCustomerId = fallbackCustomerId,
                        fallbackCustomerName = actor.fullName.ifBlank { "عميل فريش" }
                    )
                }
            }
            return@withContext OrderSyncOutcome.Success(
                orders = synced,
                httpStatusCode = response.code()
            )
        }

        val rawError = runCatching { response.errorBody()?.string() }.getOrNull()
        mapHttpErrorToSyncFailure(response.code(), rawError, fallbackCustomerId)
    }

    /**
     * Synchronizes a single order's details and status history from `GET /api/v1/orders/{id}/` into Room.
     */
    suspend fun syncOrderDetailsFromServer(
        orderId: Int,
        actor: AuthenticatedUser?,
        apiServiceOverride: FreshApiService? = null
    ): OrderSyncOutcome = withContext(Dispatchers.IO) {
        if (actor == null) {
            return@withContext OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.UNAUTHORIZED,
                messageArabic = "يجب تسجيل الدخول لعرض تفاصيل الطلب من الخادم.",
                httpStatusCode = 401
            )
        }
        val activeApi = apiServiceOverride ?: apiService
            ?: return@withContext OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.NETWORK_ERROR,
                messageArabic = "خدمة الاتصال بالخادم غير مهيأة."
            )
        val fallbackCustomerId = actor.customerId?.trim()?.takeIf { it.isNotEmpty() } ?: actor.id

        val response = try {
            activeApi.getOrderById(orderId)
        } catch (httpEx: HttpException) {
            val rawError = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            return@withContext mapHttpErrorToSyncFailure(httpEx.code(), rawError, fallbackCustomerId)
        } catch (_: IOException) {
            val cachedSingle = if (actor.role == UserRole.CUSTOMER) {
                getOrderDetailsForCustomerOnce(orderId, actor)
            } else {
                getOrderDetailsForStoreManagementOnce(orderId, actor)
            }
            return@withContext OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.NETWORK_ERROR,
                messageArabic = "تعذر الاتصال بالخادم لتحديث تفاصيل الطلب؛ يتم عرض البيانات المحفوظة محلياً.",
                cachedOrders = listOfNotNull(cachedSingle)
            )
        } catch (_: Exception) {
            return@withContext OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.SERVER_ERROR,
                messageArabic = "حدث خطأ غير متوقع أثناء جلب تفاصيل الطلب من الخادم."
            )
        }

        if (response.isSuccessful && response.body() != null) {
            val dto = response.body()!!
            val syncedItem = checkoutMutex.withLock {
                upsertServerOrderDtoInRoom(
                    serverOrder = dto,
                    fallbackCustomerId = fallbackCustomerId,
                    fallbackCustomerName = if (actor.role == UserRole.CUSTOMER) {
                        actor.fullName.ifBlank { "عميل فريش" }
                    } else {
                        "عميل فريش"
                    }
                )
            }
            return@withContext OrderSyncOutcome.Success(
                orders = listOf(syncedItem),
                httpStatusCode = response.code()
            )
        }

        val rawError = runCatching { response.errorBody()?.string() }.getOrNull()
        mapHttpErrorToSyncFailure(response.code(), rawError, fallbackCustomerId)
    }

    /**
     * Synchronizes branch-scoped store orders from `GET /api/v1/orders/` for an authorized
     * Store Owner ([UserRole.STORE_OWNER]) or Branch Manager ([UserRole.STORE_ADMIN]).
     */
    suspend fun syncStoreOrdersFromServer(
        actor: AuthenticatedUser?,
        branchId: Int? = null,
        status: String? = null,
        apiServiceOverride: FreshApiService? = null
    ): OrderSyncOutcome = withContext(Dispatchers.IO) {
        val accessDecision = AccessControlPolicy.checkAccess(
            user = actor,
            requiresAuth = true,
            requiresStoreManagement = true
        )
        if (accessDecision !is AccessControlDecision.Allowed || actor == null) {
            return@withContext OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.UNAUTHORIZED,
                messageArabic = "غير مصرح لك بعرض طلبات المتجر؛ هذه الصلاحية مخصصة لمالك المتجر أو مشرف الفرع فقط.",
                httpStatusCode = 403
            )
        }
        val activeApi = apiServiceOverride ?: apiService
            ?: return@withContext OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.NETWORK_ERROR,
                messageArabic = "خدمة الاتصال بالخادم غير مهيأة لمزامنة طلبات المتجر."
            )

        val response = try {
            activeApi.getOrders(branchId = branchId, status = status)
        } catch (httpEx: HttpException) {
            val rawError = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            return@withContext mapHttpErrorToSyncFailure(httpEx.code(), rawError, GUEST_CUSTOMER_ID)
        } catch (_: IOException) {
            val cached = loadCachedOrdersForStoreOnce(actor, branchId)
            return@withContext OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.NETWORK_ERROR,
                messageArabic = "تعذر الاتصال بالخادم لمزامنة طلبات الفرع؛ يتم عرض النسخة المحفوظة محلياً.",
                cachedOrders = cached
            )
        } catch (_: Exception) {
            val cached = loadCachedOrdersForStoreOnce(actor, branchId)
            return@withContext OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.SERVER_ERROR,
                messageArabic = "حدث خطأ غير متوقع أثناء مزامنة طلبات الفرع.",
                cachedOrders = cached
            )
        }

        if (response.isSuccessful && response.body() != null) {
            val serverOrders = response.body()!!
            val synced = checkoutMutex.withLock {
                serverOrders.map { dto ->
                    upsertServerOrderDtoInRoom(
                        serverOrder = dto,
                        fallbackCustomerId = dto.customerId ?: GUEST_CUSTOMER_ID,
                        fallbackCustomerName = "عميل فريش"
                    )
                }
            }
            return@withContext OrderSyncOutcome.Success(
                orders = synced,
                httpStatusCode = response.code()
            )
        }

        val rawError = runCatching { response.errorBody()?.string() }.getOrNull()
        mapHttpErrorToSyncFailure(response.code(), rawError, GUEST_CUSTOMER_ID)
    }

    private suspend fun loadCachedOrdersForCustomerOnce(actor: AuthenticatedUser): List<OrderWithItems> {
        val allowedCustomerIds = buildSet {
            actor.id.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            actor.customerId?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        val orders = orderDao.getAllOrdersOnce().filter { it.customerId in allowedCustomerIds }
        return orders.map { order ->
            OrderWithItems(
                order = order,
                items = orderItemDao.getOrderItemsOnce(order.id),
                statusHistory = statusHistoryByOrderId[order.id].orEmpty(),
                branchId = branchIdByOrderId[order.id]
            )
        }
    }

    private suspend fun loadCachedOrdersForStoreOnce(
        actor: AuthenticatedUser,
        branchIdFilter: Int?
    ): List<OrderWithItems> {
        val allowedBranches = actor.authorizedBranchIds
        val orders = orderDao.getAllOrdersOnce().filter { order ->
            val ob = branchIdByOrderId[order.id]
            val branchMatches = branchIdFilter == null || ob == branchIdFilter
            val allowedMatches = ob == null || allowedBranches.isNullOrEmpty() || ob in allowedBranches
            branchMatches && allowedMatches
        }
        return orders.map { order ->
            OrderWithItems(
                order = order,
                items = orderItemDao.getOrderItemsOnce(order.id),
                statusHistory = statusHistoryByOrderId[order.id].orEmpty(),
                branchId = branchIdByOrderId[order.id]
            )
        }
    }

    private fun mapHttpErrorToSyncFailure(
        statusCode: Int,
        rawErrorBody: String?,
        @Suppress("UNUSED_PARAMETER") fallbackCustomerId: String
    ): OrderSyncOutcome.Failure {
        val errorDto = ApiErrorDto.parseFromErrorBody(rawErrorBody)
        val detail = errorDto.resolvedMessage
        return when (statusCode) {
            401, 403 -> OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.UNAUTHORIZED,
                messageArabic = if (detail.isNotBlank()) {
                    "غير مصرح بالوصول إلى هذه الطلبات ($statusCode): $detail"
                } else {
                    "غير مصرح بالوصول إلى هذه الطلبات ($statusCode)."
                },
                httpStatusCode = statusCode
            )
            404 -> OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.ORDER_NOT_FOUND,
                messageArabic = "لم يتم العثور على الطلب المطلوب على الخادم (404).",
                httpStatusCode = 404
            )
            else -> OrderSyncOutcome.Failure(
                reason = OrderTransitionFailureReason.SERVER_ERROR,
                messageArabic = "تعذر جلب الطلبات من الخادم (رمز الاستجابة: $statusCode).",
                httpStatusCode = statusCode
            )
        }
    }

    private fun mapHttpErrorToCheckoutFailure(
        statusCode: Int,
        rawErrorBody: String?
    ): CheckoutOutcome.Failure {
        val errorDto = ApiErrorDto.parseFromErrorBody(rawErrorBody)
        val detail = errorDto.resolvedMessage
        val code = errorDto.code.trim()

        return when (statusCode) {
            400 -> {
                val isPriceMismatch = code.equals("price_mismatch", ignoreCase = true) ||
                    detail.contains("expected unit price", ignoreCase = true) ||
                    detail.contains("expected order total", ignoreCase = true)
                if (isPriceMismatch) {
                    CheckoutOutcome.Failure(
                        reason = CheckoutFailureReason.PRICE_MISMATCH_OR_UNPROCESSABLE,
                        messageArabic = "تغيرت أسعار بعض المنتجات على الخادم قبل إتمام الطلب. يرجى مراجعة السلة والمحاولة مجدداً.",
                        httpStatusCode = 400,
                        serverErrorCode = code.ifBlank { "price_mismatch" }
                    )
                } else {
                    CheckoutOutcome.Failure(
                        reason = CheckoutFailureReason.VALIDATION_ERROR,
                        messageArabic = if (detail.isNotBlank()) {
                            "بيانات الطلب غير صالحة (400): $detail"
                        } else {
                            "بيانات الطلب غير صالحة. يرجى التحقق من الأصناف والكميات والمحاولة مجدداً."
                        },
                        httpStatusCode = 400,
                        serverErrorCode = code.ifBlank { "validation_error" }
                    )
                }
            }
            401 -> CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.UNAUTHENTICATED_SESSION,
                messageArabic = "انتهت صلاحية الجلسة أو لم يتم تسجيل الدخول (401). يرجى تسجيل الدخول لإتمام الطلب.",
                httpStatusCode = 401,
                serverErrorCode = code.ifBlank { "unauthenticated" }
            )
            403 -> CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.FORBIDDEN_OR_UNAUTHORIZED,
                messageArabic = "صلاحيات غير كافية لإتمام الطلب (403): يجب تسجيل الدخول بحساب عميل (CUSTOMER) نشط.",
                httpStatusCode = 403,
                serverErrorCode = code.ifBlank { "authorization_denied" }
            )
            404 -> CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.PRODUCT_NOT_FOUND,
                messageArabic = "المنتج أو الفرع المطلوب غير متوفر على الخادم (404).",
                httpStatusCode = 404,
                serverErrorCode = code.ifBlank { "not_found" }
            )
            409 -> {
                val isInsufficientStock = code.equals("insufficient_stock", ignoreCase = true) ||
                    detail.contains("insufficient stock", ignoreCase = true)
                if (isInsufficientStock) {
                    CheckoutOutcome.Failure(
                        reason = CheckoutFailureReason.INSUFFICIENT_STOCK,
                        messageArabic = "المخزون المتوفر على الخادم غير كافٍ للكمية المطلوبة (409). لم يتم حذف السلة.",
                        httpStatusCode = 409,
                        serverErrorCode = "insufficient_stock"
                    )
                } else {
                    CheckoutOutcome.Failure(
                        reason = CheckoutFailureReason.IDEMPOTENCY_CONFLICT,
                        messageArabic = "تعارض في مفتاح عدم التكرار (409 Idempotency Conflict): تم استخدام نفس المفتاح مع بيانات طلب مختلفة.",
                        httpStatusCode = 409,
                        serverErrorCode = code.ifBlank { "idempotency_conflict" }
                    )
                }
            }
            422 -> CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.PRICE_MISMATCH_OR_UNPROCESSABLE,
                messageArabic = "اختلاف في السعر أو فشل التحقق من بيانات الطلب على الخادم (422). يرجى تحديث السلة.",
                httpStatusCode = 422,
                serverErrorCode = code.ifBlank { "unprocessable_entity" }
            )
            in 500..599 -> CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.SERVER_ERROR,
                messageArabic = "حدث خطأ في الخادم ($statusCode) أثناء معالجة الطلب. لم يتم خصم المخزون أو حذف السلة، يمكنك إعادة المحاولة.",
                httpStatusCode = statusCode,
                serverErrorCode = code.ifBlank { "internal_server_error" }
            )
            else -> CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.SERVER_ERROR,
                messageArabic = "تعذر إتمام الطلب على الخادم (رمز الاستجابة: $statusCode).",
                httpStatusCode = statusCode,
                serverErrorCode = code.ifBlank { "http_$statusCode" }
            )
        }
    }

    private fun parseIsoTimestampToEpochMillis(rawTimestamp: String, fallbackMillis: Long): Long {
        val cleaned = rawTimestamp.trim()
        if (cleaned.isEmpty()) return fallbackMillis
        return try {
            Instant.parse(cleaned).toEpochMilli()
        } catch (_: Exception) {
            fallbackMillis
        }
    }

    /**
     * Executes a complete, atomic local checkout inside a single Room SQLite transaction.
     *
     * Transition Plan Note (Microtask 1D.3):
     * - Production checkout in [com.example.ui.viewmodel.CartViewModel] routes through [executeServerCheckout]
     *   whenever [apiService] is configured (which [com.example.di.Injection.provideOrderRepository] always provides).
     * - [executeLocalCheckout] is preserved so existing local-only Room integrity tests
     *   (`AtomicLocalCheckoutAndOrderIntegrityTest`) continue to run without regression.
     */
    suspend fun executeLocalCheckout(
        customerId: String = GUEST_CUSTOMER_ID,
        customerName: String = "متسوق محلي",
        actorRole: String = UserRole.CUSTOMER.name,
        deliveryAddress: String = DEFAULT_DELIVERY_ADDRESS,
        deliveryFeeHalalas: Int = DEFAULT_DELIVERY_FEE_HALALAS,
        discountHalalas: Int = 0,
        expectedUnitPricesHalalas: Map<Int, Int>? = null,
        idempotencyKey: String? = null,
        timestampMillis: Long = System.currentTimeMillis()
    ): CheckoutOutcome {
        val cleanCustomerId = customerId.trim().ifBlank { GUEST_CUSTOMER_ID }
        val cleanCustomerName = customerName.trim().ifBlank { "متسوق محلي" }
        val cleanAddress = deliveryAddress.trim().ifBlank { DEFAULT_DELIVERY_ADDRESS }
        val cleanIdempotencyKey = idempotencyKey?.trim().orEmpty()

        if (deliveryFeeHalalas < 0 || discountHalalas < 0) {
            return CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.INVALID_OR_STALE_PRICE,
                messageArabic = "رسوم التوصيل أو قيمة الخصم غير صالحة."
            )
        }

        return withContext(Dispatchers.IO) {
            checkoutMutex.withLock {
                // Idempotency check before entering transaction
                if (cleanIdempotencyKey.isNotEmpty()) {
                    if (completedIdempotencyKeys.contains(cleanIdempotencyKey) ||
                        orderDao.getOrderByIdempotencyKey(cleanIdempotencyKey) != null
                    ) {
                        return@withLock CheckoutOutcome.Failure(
                            reason = CheckoutFailureReason.DUPLICATE_SUBMISSION,
                            messageArabic = "تم إرسال هذا الطلب وتأكيده مسبقاً؛ تم منع تكرار خصم المخزون."
                        )
                    }
                }

                try {
                    database.withTransaction {
                        // Double-check idempotency inside transaction
                        if (cleanIdempotencyKey.isNotEmpty() &&
                            orderDao.getOrderByIdempotencyKey(cleanIdempotencyKey) != null
                        ) {
                            throw CheckoutRollbackException(
                                reason = CheckoutFailureReason.DUPLICATE_SUBMISSION,
                                messageArabic = "تم إرسال هذا الطلب وتأكيده مسبقاً؛ تم منع تكرار خصم المخزون."
                            )
                        }

                        // 1. Validate cart is not empty
                        val cartItems = cartDao.getCartItemsOnce()
                        if (cartItems.isEmpty()) {
                            throw CheckoutRollbackException(
                                reason = CheckoutFailureReason.EMPTY_CART,
                                messageArabic = "سلة التسوق فارغة حالياً. يرجى إضافة منتجات قبل إتمام الطلب."
                            )
                        }

                        var runningSubtotalHalalas = 0L
                        data class ValidatedItem(
                            val cartItem: com.example.data.local.model.CartItem,
                            val product: com.example.data.local.model.Product,
                            val requestedMillis: Int,
                            val availableMillis: Int,
                            val lineTotalHalalas: Int,
                            val lineTotalLong: Long
                        )
                        val validatedItems = mutableListOf<ValidatedItem>()

                        for (cartItem in cartItems) {
                            // 2. Reload latest product and stock values from Room inside the active transaction
                            val product = productDao.getProductById(cartItem.productId)
                                ?: throw CheckoutRollbackException(
                                    reason = CheckoutFailureReason.PRODUCT_NOT_FOUND,
                                    messageArabic = "المنتج رقم (${cartItem.productId}) غير موجود في المخزون حالياً.",
                                    failedProductId = cartItem.productId
                                )

                            // 3. Validate product availability and current prices
                            if (!product.isActive || product.isArchived) {
                                throw CheckoutRollbackException(
                                    reason = CheckoutFailureReason.PRODUCT_INACTIVE_OR_ARCHIVED,
                                    messageArabic = "عذراً، المنتج «${product.name}» غير متاح للشراء حالياً.",
                                    failedProductId = product.id
                                )
                            }

                            if (product.priceHalalas <= 0) {
                                throw CheckoutRollbackException(
                                    reason = CheckoutFailureReason.INVALID_OR_STALE_PRICE,
                                    messageArabic = "سعر المنتج «${product.name}» غير صالح في قاعدة البيانات.",
                                    failedProductId = product.id
                                )
                            }

                            if (expectedUnitPricesHalalas != null && expectedUnitPricesHalalas.containsKey(product.id)) {
                                val expectedPrice = expectedUnitPricesHalalas[product.id] ?: -1
                                if (expectedPrice <= 0 || expectedPrice != product.priceHalalas) {
                                    throw CheckoutRollbackException(
                                        reason = CheckoutFailureReason.INVALID_OR_STALE_PRICE,
                                        messageArabic = "تغير سعر المنتج «${product.name}» إلى ${product.formattedPriceSar()} ر.س قبل إتمام الطلب. يرجى مراجعة السلة والمحاولة مجدداً.",
                                        failedProductId = product.id
                                    )
                                }
                            }

                            // 4. Validate quantity (whole or fractional milli-units) and exact currency calculation
                            val requestedMillis = cartItem.effectiveQuantityMillis
                            if (requestedMillis <= 0 || cartItem.quantity < 0) {
                                throw CheckoutRollbackException(
                                    reason = CheckoutFailureReason.INVALID_QUANTITY,
                                    messageArabic = "الكمية المطلوبة للمنتج «${product.name}» غير صالحة.",
                                    failedProductId = product.id
                                )
                            }

                            val lineTotalLong = CurrencyCalculator.calculateLineTotalHalalasLong(
                                unitPriceHalalas = product.priceHalalas,
                                quantityMillis = requestedMillis
                            )
                            if (lineTotalLong <= 0L || lineTotalLong > Int.MAX_VALUE) {
                                throw CheckoutRollbackException(
                                    reason = CheckoutFailureReason.INVALID_OR_STALE_PRICE,
                                    messageArabic = "تعذر حساب إجمالي السعر للمنتج «${product.name}» بشكل صحيح.",
                                    failedProductId = product.id
                                )
                            }
                            val lineTotalHalalas = lineTotalLong.toInt()

                            // 5. Reject checkout if out of stock or insufficiently stocked
                            val availableMillis = product.effectiveStockMillis
                            if (availableMillis <= 0) {
                                throw CheckoutRollbackException(
                                    reason = CheckoutFailureReason.OUT_OF_STOCK,
                                    messageArabic = "عذراً، نفد مخزون «${product.name}» ولا يمكن إتمام الطلب.",
                                    failedProductId = product.id
                                )
                            }

                            if (requestedMillis > availableMillis) {
                                val requestedFormatted = QuantityFormatter.formatMillis(requestedMillis)
                                val availableFormatted = product.formattedStockQuantity()
                                throw CheckoutRollbackException(
                                    reason = CheckoutFailureReason.INSUFFICIENT_STOCK,
                                    messageArabic = "المخزون غير كافٍ: الكمية المطلوبة من «${product.name}» ($requestedFormatted ${product.unit}) تتجاوز المخزون المتوفر ($availableFormatted ${product.unit}).",
                                    failedProductId = product.id
                                )
                            }

                            runningSubtotalHalalas += lineTotalLong
                            validatedItems.add(
                                ValidatedItem(
                                    cartItem = cartItem,
                                    product = product,
                                    requestedMillis = requestedMillis,
                                    availableMillis = availableMillis,
                                    lineTotalHalalas = lineTotalHalalas,
                                    lineTotalLong = lineTotalLong
                                )
                            )
                        }

                        if (runningSubtotalHalalas <= 0L || runningSubtotalHalalas > Int.MAX_VALUE) {
                            throw CheckoutRollbackException(
                                reason = CheckoutFailureReason.INVALID_OR_STALE_PRICE,
                                messageArabic = "إجمالي الطلب غير صالح."
                            )
                        }

                        val subtotalHalalas = runningSubtotalHalalas.toInt()
                        val effectiveDiscountHalalas = discountHalalas.coerceIn(0, subtotalHalalas + deliveryFeeHalalas)
                        val finalTotalHalalas = subtotalHalalas + deliveryFeeHalalas - effectiveDiscountHalalas

                        // Insert initial order with final totals
                        val initialOrder = Order(
                            totalHalalas = finalTotalHalalas,
                            timestamp = timestampMillis,
                            orderNumber = "",
                            customerId = cleanCustomerId,
                            customerName = cleanCustomerName,
                            status = OrderStatus.CONFIRMED_LOCAL.name,
                            subtotalHalalas = subtotalHalalas,
                            deliveryFeeHalalas = deliveryFeeHalalas,
                            discountHalalas = effectiveDiscountHalalas,
                            deliveryAddressSnapshot = cleanAddress,
                            paymentModeNote = LOCAL_CHECKOUT_PAYMENT_NOTE,
                            idempotencyKey = cleanIdempotencyKey
                        )
                        val orderId = orderDao.insert(initialOrder).toInt()
                        val formattedOrderNumber = "FRESH-${1000 + orderId}"
                        val finalizedOrder = initialOrder.copy(
                            id = orderId,
                            orderNumber = formattedOrderNumber
                        )
                        orderDao.update(finalizedOrder)

                        val persistedItems = mutableListOf<OrderItem>()
                        val persistedAdjustments = mutableListOf<StockAdjustment>()
                        val purchasedProductIds = mutableListOf<Int>()

                        for (validated in validatedItems) {
                            val product = validated.product
                            val cartItem = validated.cartItem
                            val requestedMillis = validated.requestedMillis
                            val availableMillis = validated.availableMillis
                            val lineTotalHalalas = validated.lineTotalHalalas

                            // 6. Deduct required stock (supporting fractional produce quantities)
                            val newStockMillis = availableMillis - requestedMillis
                            val updatedProduct = product.withUpdatedStockMillis(
                                newStockMillis = newStockMillis,
                                updatedTimestampMillis = timestampMillis
                            )
                            productDao.insert(updatedProduct)

                            // 7. Insert immutable OrderItem snapshot
                            val itemSnapshot = OrderItem(
                                orderId = orderId,
                                productId = product.id,
                                productName = product.name,
                                unit = product.unit,
                                quantity = cartItem.quantity,
                                quantityMillis = requestedMillis,
                                priceHalalas = product.priceHalalas,
                                lineTotalHalalas = lineTotalHalalas
                            )
                            val insertedItemId = orderItemDao.insert(itemSnapshot).toInt()
                            persistedItems.add(itemSnapshot.copy(id = insertedItemId))

                            // 8. Insert StockAdjustment audit record for this deduction
                            val auditReason = "Order checkout: $orderId"
                            val prevWhole = availableMillis / 1000
                            val newWhole = newStockMillis / 1000
                            val wholeDelta = newWhole - prevWhole
                            val adjustment = StockAdjustment(
                                productId = product.id,
                                productName = product.name,
                                previousQuantity = prevWhole,
                                newQuantity = newWhole,
                                quantityDelta = wholeDelta,
                                reason = auditReason,
                                actorId = cleanCustomerId,
                                actorName = cleanCustomerName,
                                actorRole = actorRole,
                                timestampMillis = timestampMillis,
                                previousQuantityMillis = availableMillis,
                                newQuantityMillis = newStockMillis,
                                quantityDeltaMillis = -requestedMillis
                            )
                            val adjustmentId = stockAdjustmentDao.insertAdjustment(adjustment)
                            persistedAdjustments.add(adjustment.copy(id = adjustmentId))

                            purchasedProductIds.add(product.id)

                            if (failAfterFirstStockDeductionForTesting && purchasedProductIds.size == 1) {
                                throw IllegalStateException("Simulated mid-transaction failure after first item deduction")
                            }
                        }

                        // Clear only the cart items that were successfully purchased
                        cartDao.deleteByProductIds(purchasedProductIds)

                        if (cleanIdempotencyKey.isNotEmpty()) {
                            completedIdempotencyKeys.add(cleanIdempotencyKey)
                        }

                        CheckoutOutcome.Success(
                            order = finalizedOrder,
                            items = persistedItems,
                            stockAdjustments = persistedAdjustments
                        )
                    }
                } catch (rollback: CheckoutRollbackException) {
                    CheckoutOutcome.Failure(
                        reason = rollback.reason,
                        messageArabic = rollback.messageArabic,
                        failedProductId = rollback.failedProductId
                    )
                } catch (e: Exception) {
                    CheckoutOutcome.Failure(
                        reason = CheckoutFailureReason.TRANSACTION_ERROR,
                        messageArabic = "تعذر إتمام الطلب محلياً بسبب خطأ في قاعدة البيانات. لم يتم خصم أي مخزون."
                    )
                }
            }
        }
    }

    /**
     * Executes a server-authoritative order status transition (`POST /api/v1/orders/{id}/transition-status/`)
     * or cancellation (`POST /api/v1/orders/{id}/cancel/`) and updates Room ONLY after the server
     * responds with `200 OK`.
     */
    suspend fun transitionOrderStatusToServerStatus(
        orderId: Int,
        toServerStatus: String,
        actor: AuthenticatedUser?,
        reasonNote: String = "",
        idempotencyKey: String? = null,
        timestampMillis: Long = System.currentTimeMillis(),
        apiServiceOverride: FreshApiService? = null
    ): OrderTransitionOutcome = withContext(Dispatchers.IO) {
        val accessDecision = AccessControlPolicy.checkAccess(
            user = actor,
            requiresAuth = true,
            requiresStoreManagement = true
        )
        if (accessDecision !is AccessControlDecision.Allowed || actor == null) {
            return@withContext OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.UNAUTHORIZED,
                messageArabic = "غير مصرح لك بتحديث حالة الطلبات؛ هذه الصلاحية مخصصة لمالك المتجر أو مدير النظام فقط.",
                httpStatusCode = 403
            )
        }

        val normalizedTarget = toServerStatus.trim().uppercase(Locale.US)
        if (normalizedTarget == "CANCELLED") {
            return@withContext executeServerOrderCancellation(
                orderId = orderId,
                actor = actor,
                reasonNote = reasonNote,
                idempotencyKey = idempotencyKey,
                timestampMillis = timestampMillis,
                apiServiceOverride = apiServiceOverride
            )
        }

        val activeApi = apiServiceOverride ?: apiService
            ?: return@withContext OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.NETWORK_ERROR,
                messageArabic = "خدمة الاتصال بالخادم غير مهيأة لتحديث حالة الطلب."
            )

        val priorOrder = orderDao.getOrderById(orderId)
        val priorOrderStatus = priorOrder?.orderStatus ?: OrderStatus.CONFIRMED_LOCAL
        val effectiveReason = reasonNote.trim().ifBlank { "Status changed to $normalizedTarget" }

        val response = try {
            activeApi.transitionOrderStatus(
                orderId = orderId,
                request = OrderStatusTransitionRequestDto(
                    toStatus = normalizedTarget,
                    reason = effectiveReason
                )
            )
        } catch (httpEx: HttpException) {
            val rawError = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            return@withContext mapHttpErrorToTransitionFailure(
                statusCode = httpEx.code(),
                rawErrorBody = rawError,
                currentOrder = priorOrder
            )
        } catch (_: IOException) {
            return@withContext OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.NETWORK_ERROR,
                messageArabic = "تعذر الاتصال بالخادم لتحديث حالة الطلب. لم يتم تعديل الحالة محلياً.",
                currentOrder = priorOrder
            )
        } catch (_: Exception) {
            return@withContext OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.SERVER_ERROR,
                messageArabic = "حدث خطأ غير متوقع أثناء تحديث حالة الطلب على الخادم.",
                currentOrder = priorOrder
            )
        }

        if (response.isSuccessful && response.body() != null) {
            val serverOrder = response.body()!!
            val synced = checkoutMutex.withLock {
                upsertServerOrderDtoInRoom(
                    serverOrder = serverOrder,
                    fallbackCustomerId = serverOrder.customerId ?: priorOrder?.customerId ?: GUEST_CUSTOMER_ID,
                    fallbackCustomerName = priorOrder?.customerName ?: "عميل فريش",
                    fallbackAddress = priorOrder?.deliveryAddressSnapshot ?: DEFAULT_DELIVERY_ADDRESS,
                    fallbackTimestampMillis = timestampMillis
                )
            }
            val labelArabic = OrderStatus.serverStatusArabicLabel(synced.order.status)
            return@withContext OrderTransitionOutcome.Success(
                updatedOrder = synced.order,
                items = synced.items,
                previousStatus = priorOrderStatus,
                newStatus = synced.order.orderStatus,
                restoredStockAdjustments = emptyList(),
                messageArabic = "تم تحديث حالة الطلب ${synced.order.displayOrderNumber} على الخادم إلى «$labelArabic» بنجاح.",
                statusHistory = synced.statusHistory,
                httpStatusCode = response.code(),
                isReplayed = false
            )
        }

        val rawError = runCatching { response.errorBody()?.string() }.getOrNull()
        mapHttpErrorToTransitionFailure(
            statusCode = response.code(),
            rawErrorBody = rawError,
            currentOrder = priorOrder
        )
    }

    private suspend fun executeServerOrderCancellation(
        orderId: Int,
        actor: AuthenticatedUser,
        reasonNote: String,
        idempotencyKey: String?,
        timestampMillis: Long,
        apiServiceOverride: FreshApiService?
    ): OrderTransitionOutcome = withContext(Dispatchers.IO) {
        val activeApi = apiServiceOverride ?: apiService
            ?: return@withContext OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.NETWORK_ERROR,
                messageArabic = "خدمة الاتصال بالخادم غير مهيأة لإلغاء الطلب."
            )

        val priorOrder = orderDao.getOrderById(orderId)
        val priorOrderStatus = priorOrder?.orderStatus ?: OrderStatus.CONFIRMED_LOCAL
        val effectiveReason = reasonNote.trim().ifBlank { "Order cancelled and stock restored" }
        val effectiveIdempotencyKey = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: "cancel-order-$orderId-${UUID.randomUUID()}"

        val response = try {
            activeApi.cancelOrder(
                orderId = orderId,
                idempotencyKey = effectiveIdempotencyKey,
                request = CancelOrderRequestDto(
                    idempotencyKey = effectiveIdempotencyKey,
                    reason = effectiveReason
                )
            )
        } catch (httpEx: HttpException) {
            val rawError = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            return@withContext mapHttpErrorToTransitionFailure(
                statusCode = httpEx.code(),
                rawErrorBody = rawError,
                currentOrder = priorOrder,
                isCancellationAttempt = true
            )
        } catch (_: IOException) {
            return@withContext OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.NETWORK_ERROR,
                messageArabic = "تعذر الاتصال بالخادم لإلغاء الطلب. لم يتم تعديل حالة الطلب أو المخزون محلياً.",
                currentOrder = priorOrder
            )
        } catch (_: Exception) {
            return@withContext OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.SERVER_ERROR,
                messageArabic = "حدث خطأ غير متوقع أثناء إلغاء الطلب على الخادم.",
                currentOrder = priorOrder
            )
        }

        if (response.isSuccessful && response.body() != null) {
            val serverOrder = response.body()!!
            val isReplayed = serverOrder.replayed == true
            val wasAlreadyRestoredLocally = restoredOrderIds.contains(orderId)

            val syncedPair = checkoutMutex.withLock {
                val synced = upsertServerOrderDtoInRoom(
                    serverOrder = serverOrder,
                    fallbackCustomerId = serverOrder.customerId ?: priorOrder?.customerId ?: GUEST_CUSTOMER_ID,
                    fallbackCustomerName = priorOrder?.customerName ?: "عميل فريش",
                    fallbackAddress = priorOrder?.deliveryAddressSnapshot ?: DEFAULT_DELIVERY_ADDRESS,
                    fallbackTimestampMillis = timestampMillis
                )
                val restoredAdjustments = mutableListOf<StockAdjustment>()
                if (!isReplayed && !wasAlreadyRestoredLocally) {
                    val baseCancelReason = "$ORDER_CANCELLATION_REASON_PREFIX$orderId"
                    val auditReason = if (reasonNote.trim().isNotEmpty()) {
                        "$baseCancelReason - ${reasonNote.trim()}"
                    } else {
                        baseCancelReason
                    }
                    database.withTransaction {
                        for (item in synced.items) {
                            val localProduct = productDao.getProductById(item.productId) ?: continue
                            val restoreMillis = item.effectiveQuantityMillis
                            if (restoreMillis <= 0) continue
                            val previousMillis = localProduct.effectiveStockMillis
                            val newStockMillis = previousMillis + restoreMillis
                            val updatedProduct = localProduct.withUpdatedStockMillis(
                                newStockMillis = newStockMillis,
                                updatedTimestampMillis = timestampMillis
                            )
                            productDao.insert(updatedProduct)

                            val prevWhole = previousMillis / 1000
                            val newWhole = newStockMillis / 1000
                            val adjustment = StockAdjustment(
                                productId = localProduct.id,
                                productName = localProduct.name,
                                previousQuantity = prevWhole,
                                newQuantity = newWhole,
                                quantityDelta = newWhole - prevWhole,
                                reason = auditReason,
                                actorId = actor.id,
                                actorName = actor.fullName,
                                actorRole = actor.role.name,
                                timestampMillis = timestampMillis,
                                previousQuantityMillis = previousMillis,
                                newQuantityMillis = newStockMillis,
                                quantityDeltaMillis = restoreMillis
                            )
                            val adjId = stockAdjustmentDao.insertAdjustment(adjustment)
                            restoredAdjustments.add(adjustment.copy(id = adjId))
                        }
                    }
                }
                restoredOrderIds.add(orderId)
                synced to restoredAdjustments
            }

            val synced = syncedPair.first
            val restoredAdjustments = syncedPair.second
            return@withContext OrderTransitionOutcome.Success(
                updatedOrder = synced.order,
                items = synced.items,
                previousStatus = priorOrderStatus,
                newStatus = OrderStatus.CANCELLED,
                restoredStockAdjustments = restoredAdjustments,
                messageArabic = "تم إلغاء الطلب ${synced.order.displayOrderNumber} على الخادم واسترجاع كميات المخزون بنجاح.",
                statusHistory = synced.statusHistory,
                httpStatusCode = response.code(),
                isReplayed = isReplayed
            )
        }

        val rawError = runCatching { response.errorBody()?.string() }.getOrNull()
        mapHttpErrorToTransitionFailure(
            statusCode = response.code(),
            rawErrorBody = rawError,
            currentOrder = priorOrder,
            isCancellationAttempt = true
        )
    }

    private fun mapHttpErrorToTransitionFailure(
        statusCode: Int,
        rawErrorBody: String?,
        currentOrder: Order?,
        isCancellationAttempt: Boolean = false
    ): OrderTransitionOutcome.Failure {
        val errorDto = ApiErrorDto.parseFromErrorBody(rawErrorBody)
        val detail = errorDto.resolvedMessage
        val lower = detail.lowercase(Locale.US)

        return when (statusCode) {
            401, 403 -> OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.UNAUTHORIZED,
                messageArabic = if (detail.isNotBlank()) {
                    "غير مصرح بتنفيذ هذا الإجراء على الطلب ($statusCode): $detail"
                } else {
                    "غير مصرح لك بتحديث أو إلغاء هذا الطلب ($statusCode)."
                },
                currentOrder = currentOrder,
                httpStatusCode = statusCode
            )
            404 -> OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.ORDER_NOT_FOUND,
                messageArabic = "لم يتم العثور على الطلب المطلوب على الخادم (404).",
                currentOrder = currentOrder,
                httpStatusCode = 404
            )
            409 -> {
                if (lower.contains("already cancelled") || lower.contains("restoration has already been recorded")) {
                    OrderTransitionOutcome.Failure(
                        reason = OrderTransitionFailureReason.ALREADY_CANCELLED,
                        messageArabic = "الطلب ملغي مسبقاً على الخادم؛ تم منع تكرار الإلغاء أو استرجاع المخزون مرة ثانية.",
                        currentOrder = currentOrder,
                        httpStatusCode = 409
                    )
                } else {
                    OrderTransitionOutcome.Failure(
                        reason = OrderTransitionFailureReason.INVALID_STATUS_TRANSITION,
                        messageArabic = "تعارض في عملية الطلب على الخادم (409): $detail",
                        currentOrder = currentOrder,
                        httpStatusCode = 409
                    )
                }
            }
            400 -> {
                when {
                    lower.contains("already completed") ||
                        lower.contains("cannot be cancelled") ||
                        (isCancellationAttempt && currentOrder?.status?.equals("COMPLETED", ignoreCase = true) == true) -> {
                        OrderTransitionOutcome.Failure(
                            reason = OrderTransitionFailureReason.COMPLETED_ORDER_CANNOT_BE_CANCELLED,
                            messageArabic = "لا يمكن إلغاء الطلب بعد اكتماله على الخادم.",
                            currentOrder = currentOrder,
                            httpStatusCode = 400
                        )
                    }
                    lower.contains("already cancelled") -> {
                        OrderTransitionOutcome.Failure(
                            reason = OrderTransitionFailureReason.ALREADY_CANCELLED,
                            messageArabic = "الطلب ملغي مسبقاً؛ لا يمكن تعديل حالته أو إلغاؤه مجدداً.",
                            currentOrder = currentOrder,
                            httpStatusCode = 400
                        )
                    }
                    else -> {
                        OrderTransitionOutcome.Failure(
                            reason = OrderTransitionFailureReason.INVALID_STATUS_TRANSITION,
                            messageArabic = if (detail.isNotBlank()) {
                                "انتقال حالة غير صالح على الخادم (400): $detail"
                            } else {
                                "انتقال حالة غير صالح للطلب."
                            },
                            currentOrder = currentOrder,
                            httpStatusCode = 400
                        )
                    }
                }
            }
            else -> OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.SERVER_ERROR,
                messageArabic = "تعذر تحديث حالة الطلب على الخادم (رمز الاستجابة: $statusCode).",
                currentOrder = currentOrder,
                httpStatusCode = statusCode
            )
        }
    }

    /**
     * Validates and executes an atomic order status transition for authorized store management actors.
     *
     * - When [apiService] is configured and the target order is server-backed (or [actor] holds an active
     *   remote JWT session), routes through [transitionOrderStatusToServerStatus] so Django/PostgreSQL
     *   remains the single source of truth and Room is updated ONLY after `200 OK`.
     * - When running in local-only mode (`apiService == null`), executes inside a single Room transaction.
     */
    suspend fun transitionOrderStatus(
        orderId: Int,
        targetStatus: OrderStatus,
        actor: AuthenticatedUser?,
        reasonNote: String = "",
        timestampMillis: Long = System.currentTimeMillis()
    ): OrderTransitionOutcome {
        val accessDecision = AccessControlPolicy.checkAccess(
            user = actor,
            requiresAuth = true,
            requiresStoreManagement = true
        )
        if (accessDecision !is AccessControlDecision.Allowed || actor == null) {
            return OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.UNAUTHORIZED,
                messageArabic = "غير مصرح لك بتحديث حالة الطلبات؛ هذه الصلاحية مخصصة لمالك المتجر أو مدير النظام فقط."
            )
        }

        val checkExisting = withContext(Dispatchers.IO) { orderDao.getOrderById(orderId) }
        if (apiService != null && (checkExisting == null || checkExisting.isServerBacked || actor.hasActiveRemoteJwtSession)) {
            return transitionOrderStatusToServerStatus(
                orderId = orderId,
                toServerStatus = targetStatus.toServerStatusString(),
                actor = actor,
                reasonNote = reasonNote,
                timestampMillis = timestampMillis
            )
        }

        return withContext(Dispatchers.IO) {
            checkoutMutex.withLock {
                try {
                    database.withTransaction {
                        val existingOrder = orderDao.getOrderById(orderId)
                            ?: throw OrderTransitionRollbackException(
                                reason = OrderTransitionFailureReason.ORDER_NOT_FOUND,
                                messageArabic = "لم يتم العثور على الطلب رقم ($orderId) في قاعدة البيانات."
                            )

                        val currentStatus = existingOrder.orderStatus

                        if (currentStatus == OrderStatus.CANCELLED) {
                            throw OrderTransitionRollbackException(
                                reason = OrderTransitionFailureReason.ALREADY_CANCELLED,
                                messageArabic = "الطلب ${existingOrder.displayOrderNumber} ملغي مسبقاً؛ لا يمكن إلغاؤه مرة أخرى أو تعديل حالته.",
                                currentOrder = existingOrder
                            )
                        }

                        if (currentStatus == OrderStatus.DELIVERED && targetStatus == OrderStatus.CANCELLED) {
                            throw OrderTransitionRollbackException(
                                reason = OrderTransitionFailureReason.COMPLETED_ORDER_CANNOT_BE_CANCELLED,
                                messageArabic = "لا يمكن إلغاء الطلب ${existingOrder.displayOrderNumber} بعد اكتماله وتوصيله.",
                                currentOrder = existingOrder
                            )
                        }

                        if (!currentStatus.canTransitionTo(targetStatus)) {
                            throw OrderTransitionRollbackException(
                                reason = OrderTransitionFailureReason.INVALID_STATUS_TRANSITION,
                                messageArabic = "انتقال حالة غير صالح للطلب ${existingOrder.displayOrderNumber}: لا يمكن الانتقال من «${currentStatus.arabicLabel}» إلى «${targetStatus.arabicLabel}».",
                                currentOrder = existingOrder
                            )
                        }

                        val orderItems = orderItemDao.getOrderItemsOnce(orderId)
                        val restoredAdjustments = mutableListOf<StockAdjustment>()

                        if (targetStatus == OrderStatus.CANCELLED) {
                            val baseCancelReason = "$ORDER_CANCELLATION_REASON_PREFIX$orderId"
                            val existingCancelAuditCount = stockAdjustmentDao.countAdjustmentsForOrderAction(
                                exactReason = baseCancelReason,
                                reasonPrefix = baseCancelReason
                            )
                            if (restoredOrderIds.contains(orderId) || existingCancelAuditCount > 0) {
                                throw OrderTransitionRollbackException(
                                    reason = OrderTransitionFailureReason.ALREADY_CANCELLED,
                                    messageArabic = "تم إلغاء الطلب ${existingOrder.displayOrderNumber} واسترجاع مخزونه مسبقاً؛ تم منع تكرار استرجاع المخزون.",
                                    currentOrder = existingOrder
                                )
                            }

                            if (orderItems.isEmpty()) {
                                throw OrderTransitionRollbackException(
                                    reason = OrderTransitionFailureReason.TRANSACTION_ERROR,
                                    messageArabic = "لا توجد بنود محفوظة للطلب ${existingOrder.displayOrderNumber} لاسترجاع مخزونها.",
                                    currentOrder = existingOrder
                                )
                            }

                            val cleanReasonNote = reasonNote.trim()
                            val auditReason = if (cleanReasonNote.isNotEmpty()) {
                                "$baseCancelReason - $cleanReasonNote"
                            } else {
                                baseCancelReason
                            }

                            var restoredItemsCount = 0
                            for (item in orderItems) {
                                val product = productDao.getProductById(item.productId)
                                    ?: throw OrderTransitionRollbackException(
                                        reason = OrderTransitionFailureReason.PRODUCT_NOT_FOUND_FOR_RESTORATION,
                                        messageArabic = "تعذر العثور على المنتج «${item.productName}» لاسترجاع مخزونه؛ تم التراجع عن الإلغاء.",
                                        currentOrder = existingOrder
                                    )

                                val restoreMillis = item.effectiveQuantityMillis
                                if (restoreMillis <= 0) {
                                    throw OrderTransitionRollbackException(
                                        reason = OrderTransitionFailureReason.TRANSACTION_ERROR,
                                        messageArabic = "كمية البند «${item.productName}» غير صالحة للاسترجاع.",
                                        currentOrder = existingOrder
                                    )
                                }

                                val previousMillis = product.effectiveStockMillis
                                val newStockMillis = previousMillis + restoreMillis
                                val updatedProduct = product.withUpdatedStockMillis(
                                    newStockMillis = newStockMillis,
                                    updatedTimestampMillis = timestampMillis
                                )
                                productDao.insert(updatedProduct)

                                val prevWhole = previousMillis / 1000
                                val newWhole = newStockMillis / 1000
                                val wholeDelta = newWhole - prevWhole
                                val adjustment = StockAdjustment(
                                    productId = product.id,
                                    productName = product.name,
                                    previousQuantity = prevWhole,
                                    newQuantity = newWhole,
                                    quantityDelta = wholeDelta,
                                    reason = auditReason,
                                    actorId = actor.id,
                                    actorName = actor.fullName,
                                    actorRole = actor.role.name,
                                    timestampMillis = timestampMillis,
                                    previousQuantityMillis = previousMillis,
                                    newQuantityMillis = newStockMillis,
                                    quantityDeltaMillis = restoreMillis
                                )
                                val adjustmentId = stockAdjustmentDao.insertAdjustment(adjustment)
                                restoredAdjustments.add(adjustment.copy(id = adjustmentId))
                                restoredItemsCount++

                                if (failAfterFirstStockRestorationForTesting && restoredItemsCount == 1) {
                                    throw IllegalStateException("Simulated mid-cancellation failure after first item restoration")
                                }
                            }

                            restoredOrderIds.add(orderId)
                        }

                        // Update only the status field; totals and historical item snapshots remain untouched
                        val updatedOrder = existingOrder.copy(status = targetStatus.name)
                        orderDao.update(updatedOrder)

                        val successMessage = if (targetStatus == OrderStatus.CANCELLED) {
                            "تم إلغاء الطلب ${updatedOrder.displayOrderNumber} واسترجاع كميات المخزون بنجاح."
                        } else {
                            "تم تحديث حالة الطلب ${updatedOrder.displayOrderNumber} إلى «${targetStatus.arabicLabel}» بنجاح."
                        }

                        OrderTransitionOutcome.Success(
                            updatedOrder = updatedOrder,
                            items = orderItems,
                            previousStatus = currentStatus,
                            newStatus = targetStatus,
                            restoredStockAdjustments = restoredAdjustments,
                            messageArabic = successMessage
                        )
                    }
                } catch (rollback: OrderTransitionRollbackException) {
                    OrderTransitionOutcome.Failure(
                        reason = rollback.reason,
                        messageArabic = rollback.messageArabic,
                        currentOrder = rollback.currentOrder
                    )
                } catch (e: Exception) {
                    OrderTransitionOutcome.Failure(
                        reason = OrderTransitionFailureReason.TRANSACTION_ERROR,
                        messageArabic = "تعذر تحديث حالة الطلب محلياً بسبب خطأ في قاعدة البيانات؛ لم يتم تطبيق أي تغيير."
                    )
                }
            }
        }
    }

    /**
     * Cancels an active order and restores its item quantities to inventory atomically at most once.
     */
    suspend fun cancelOrder(
        orderId: Int,
        actor: AuthenticatedUser?,
        reasonNote: String = "",
        timestampMillis: Long = System.currentTimeMillis(),
        idempotencyKey: String? = null,
        apiServiceOverride: FreshApiService? = null
    ): OrderTransitionOutcome {
        val accessDecision = AccessControlPolicy.checkAccess(
            user = actor,
            requiresAuth = true,
            requiresStoreManagement = true
        )
        if (accessDecision !is AccessControlDecision.Allowed || actor == null) {
            return OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.UNAUTHORIZED,
                messageArabic = "غير مصرح لك بتحديث حالة الطلبات؛ هذه الصلاحية مخصصة لمالك المتجر أو مدير النظام فقط."
            )
        }
        val activeApi = apiServiceOverride ?: apiService
        val checkExisting = withContext(Dispatchers.IO) { orderDao.getOrderById(orderId) }
        if (activeApi != null && (checkExisting == null || checkExisting.isServerBacked || actor.hasActiveRemoteJwtSession)) {
            return executeServerOrderCancellation(
                orderId = orderId,
                actor = actor,
                reasonNote = reasonNote,
                idempotencyKey = idempotencyKey,
                timestampMillis = timestampMillis,
                apiServiceOverride = apiServiceOverride
            )
        }
        return transitionOrderStatus(
            orderId = orderId,
            targetStatus = OrderStatus.CANCELLED,
            actor = actor,
            reasonNote = reasonNote,
            timestampMillis = timestampMillis
        )
    }

    companion object {
        const val DEFAULT_BRANCH_ID = 1
        const val GUEST_CUSTOMER_ID = "guest_local"
        const val DEFAULT_DELIVERY_ADDRESS = "الرياض، حي النخيل"
        const val DEFAULT_DELIVERY_FEE_HALALAS = 1500 // 15.00 SAR
        const val LOCAL_CHECKOUT_PAYMENT_NOTE =
            "إتمام طلب محلي (الدفع عند الاستلام — بوابة الدفع الإلكتروني غير متصلة حالياً)"
        const val SERVER_CHECKOUT_PAYMENT_NOTE =
            "طلب مؤكد من الخادم (الدفع عند الاستلام)"
        const val ORDER_CANCELLATION_REASON_PREFIX = "Order cancellation: "
    }
}
