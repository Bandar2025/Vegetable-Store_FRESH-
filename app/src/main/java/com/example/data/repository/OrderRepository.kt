package com.example.data.repository

import androidx.room.withTransaction
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class CheckoutFailureReason {
    EMPTY_CART,
    PRODUCT_NOT_FOUND,
    PRODUCT_INACTIVE_OR_ARCHIVED,
    OUT_OF_STOCK,
    INSUFFICIENT_STOCK,
    INVALID_QUANTITY,
    INVALID_OR_STALE_PRICE,
    DUPLICATE_SUBMISSION,
    TRANSACTION_ERROR;

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
    TRANSACTION_ERROR
}

sealed class OrderTransitionOutcome {
    data class Success(
        val updatedOrder: Order,
        val items: List<OrderItem>,
        val previousStatus: OrderStatus,
        val newStatus: OrderStatus,
        val restoredStockAdjustments: List<StockAdjustment> = emptyList(),
        val messageArabic: String
    ) : OrderTransitionOutcome()

    data class Failure(
        val reason: OrderTransitionFailureReason,
        val messageArabic: String,
        val currentOrder: Order? = null
    ) : OrderTransitionOutcome()
}

private class OrderTransitionRollbackException(
    val reason: OrderTransitionFailureReason,
    val messageArabic: String,
    val currentOrder: Order? = null
) : RuntimeException(messageArabic)

data class OrderWithItems(
    val order: Order,
    val items: List<OrderItem>
)

sealed class CheckoutOutcome {
    data class Success(
        val order: Order,
        val items: List<OrderItem>,
        val stockAdjustments: List<StockAdjustment>
    ) : CheckoutOutcome()

    data class Failure(
        val reason: CheckoutFailureReason,
        val messageArabic: String,
        val failedProductId: Int? = null
    ) : CheckoutOutcome()
}

private class CheckoutRollbackException(
    val reason: CheckoutFailureReason,
    val messageArabic: String,
    val failedProductId: Int? = null
) : RuntimeException(messageArabic)

/**
 * Repository managing atomic local checkout transactions, persisted orders, order-item snapshots,
 * order lifecycle status transitions, atomic cancellation with single-execution stock restoration,
 * stock movement audit trails, and role/customer-isolated order history queries.
 */
class OrderRepository(
    private val database: AppDatabase,
    private val productDao: ProductDao = database.productDao(),
    private val cartDao: CartDao = database.cartDao(),
    private val orderDao: OrderDao = database.orderDao(),
    private val orderItemDao: OrderItemDao = database.orderItemDao(),
    private val stockAdjustmentDao: StockAdjustmentDao = database.stockAdjustmentDao()
) {
    private val checkoutMutex = Mutex()
    private val completedIdempotencyKeys = mutableSetOf<String>()
    private val restoredOrderIds = mutableSetOf<Int>()

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
        if (actor != null && actor.id.trim() != cleanCustomerId) {
            return flowOf(emptyList())
        }
        return combine(
            orderDao.getOrdersForCustomer(cleanCustomerId),
            orderItemDao.getAllOrderItems()
        ) { orders, allItems ->
            val itemsByOrderId = allItems.groupBy { it.orderId }
            orders.filter { it.customerId == cleanCustomerId }.map { order ->
                OrderWithItems(
                    order = order,
                    items = itemsByOrderId[order.id].orEmpty()
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
        val effectiveActorCustomerId = actor?.id?.trim()?.ifBlank { GUEST_CUSTOMER_ID } ?: GUEST_CUSTOMER_ID
        if (requestedCustomerId != null &&
            requestedCustomerId.trim().isNotEmpty() &&
            requestedCustomerId.trim() != effectiveActorCustomerId
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
        val effectiveActorCustomerId = actor?.id?.trim()?.ifBlank { GUEST_CUSTOMER_ID } ?: GUEST_CUSTOMER_ID
        if (requestedCustomerId != null &&
            requestedCustomerId.trim().isNotEmpty() &&
            requestedCustomerId.trim() != effectiveActorCustomerId
        ) {
            return@withContext null
        }
        val order = orderDao.getOrderById(orderId) ?: return@withContext null
        if (order.customerId != effectiveActorCustomerId) {
            return@withContext null
        }
        val items = orderItemDao.getOrderItemsOnce(orderId)
        OrderWithItems(order = order, items = items)
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
        val items = orderItemDao.getOrderItemsOnce(orderId)
        OrderWithItems(order = order, items = items)
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
        return combine(
            orderDao.getOrders(),
            orderItemDao.getAllOrderItems()
        ) { orders, allItems ->
            val itemsByOrderId = allItems.groupBy { it.orderId }
            orders.map { order ->
                OrderWithItems(
                    order = order,
                    items = itemsByOrderId[order.id].orEmpty()
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
                    items = itemsByOrderId[order.id].orEmpty()
                )
            }
        }
    }

    /**
     * Executes a complete, atomic local checkout inside a single Room SQLite transaction.
     *
     * Transaction steps:
     * 1. Validates idempotency key and verifies the cart is not empty.
     * 2. Reloads the latest product and stock values directly from Room.
     * 3. Validates product availability (`isActive && !isArchived`) and current prices (including stale-price check when [expectedUnitPricesHalalas] is provided).
     * 4. Validates all quantities (whole or fractional milli-units) and exact integer halalas calculations.
     * 5. Rejects checkout if any item is inactive, unavailable, or insufficiently stocked.
     * 6. Deducts the required stock (supporting fractional produce quantities) for all order items.
     * 7. Inserts the [Order] and its immutable [OrderItem] snapshots.
     * 8. Inserts a [StockAdjustment] audit record for each deduction (`"Order checkout: {orderId}"`).
     * 9. Clears only the purchased cart items and commits all changes together.
     *
     * Any validation or database failure throws [CheckoutRollbackException] inside [withTransaction],
     * rolling back all product stock updates, order inserts, and audit records while keeping the cart intact.
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
     * Validates and executes an atomic order status transition for authorized store management actors.
     *
     * - Enforces store-management authorization ([UserRole.STORE_OWNER] or [UserRole.STORE_ADMIN]).
     * - Validates the requested transition against [OrderStatus.canTransitionTo].
     * - Rejects repeated cancellation ([OrderTransitionFailureReason.ALREADY_CANCELLED]) and
     *   cancellation of completed orders ([OrderTransitionFailureReason.COMPLETED_ORDER_CANNOT_BE_CANCELLED]).
     * - When [targetStatus] is [OrderStatus.CANCELLED], restores each item's exact quantity (including fractional
     *   milli-units) and writes a [StockAdjustment] audit record (`"Order cancellation: {orderId}"`) inside the
     *   same Room transaction as the order status update, guaranteeing stock is restored at most once.
     * - Preserves all historical order totals and [OrderItem] prices unchanged.
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
        timestampMillis: Long = System.currentTimeMillis()
    ): OrderTransitionOutcome {
        return transitionOrderStatus(
            orderId = orderId,
            targetStatus = OrderStatus.CANCELLED,
            actor = actor,
            reasonNote = reasonNote,
            timestampMillis = timestampMillis
        )
    }

    companion object {
        const val GUEST_CUSTOMER_ID = "guest_local"
        const val DEFAULT_DELIVERY_ADDRESS = "الرياض، حي النخيل"
        const val DEFAULT_DELIVERY_FEE_HALALAS = 1500 // 15.00 SAR
        const val LOCAL_CHECKOUT_PAYMENT_NOTE =
            "إتمام طلب محلي (الدفع عند الاستلام — بوابة الدفع الإلكتروني غير متصلة حالياً)"
        const val ORDER_CANCELLATION_REASON_PREFIX = "Order cancellation: "
    }
}
