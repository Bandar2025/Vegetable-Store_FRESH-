package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.auth.AuthenticatedUser
import com.example.data.auth.UserRole
import com.example.data.local.model.CartItem
import com.example.data.local.model.CurrencyCalculator
import com.example.data.local.model.Product
import com.example.data.local.model.QuantityFormatter
import com.example.data.repository.CartRepository
import com.example.data.repository.CheckoutFailureReason
import com.example.data.repository.CheckoutOutcome
import com.example.data.repository.OrderRepository
import com.example.data.repository.OrderSyncOutcome
import com.example.data.repository.OrderWithItems
import com.example.data.repository.ProductRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class CartLineItemUiModel(
    val cartItem: CartItem,
    val product: Product,
    val quantityMillis: Int,
    val unitPriceHalalas: Int,
    val lineTotalHalalas: Long,
    val isAvailable: Boolean,
    val availabilityWarningArabic: String?
) {
    val quantityUnits: Double
        get() = quantityMillis / 1000.0

    val formattedQuantity: String
        get() = QuantityFormatter.formatMillis(quantityMillis)

    fun formattedUnitPriceSar(): String = CurrencyCalculator.formatHalalasToSar(unitPriceHalalas.toLong())

    fun formattedLineTotalSar(): String = CurrencyCalculator.formatHalalasToSar(lineTotalHalalas)
}

class CartViewModel(
    private val cartRepository: CartRepository,
    private val productRepository: ProductRepository,
    private val orderRepository: OrderRepository? = cartRepository.getOrderRepository()
) : ViewModel() {

    private val _cartErrorMessage = MutableStateFlow<String?>(null)
    val cartErrorMessage: StateFlow<String?> = _cartErrorMessage.asStateFlow()

    private val _deliveryAddress = MutableStateFlow(OrderRepository.DEFAULT_DELIVERY_ADDRESS)
    val deliveryAddress: StateFlow<String> = _deliveryAddress.asStateFlow()

    private val _discountHalalas = MutableStateFlow(0L)
    val discountHalalas: StateFlow<Long> = _discountHalalas.asStateFlow()

    private val _isSubmittingCheckout = MutableStateFlow(false)
    val isSubmittingCheckout: StateFlow<Boolean> = _isSubmittingCheckout.asStateFlow()

    private val _lastCompletedOrder = MutableStateFlow<CheckoutOutcome.Success?>(null)
    val lastCompletedOrder: StateFlow<CheckoutOutcome.Success?> = _lastCompletedOrder.asStateFlow()

    private val _capturedPriceSnapshot = MutableStateFlow<Map<Int, Int>?>(null)
    val capturedPriceSnapshot: StateFlow<Map<Int, Int>?> = _capturedPriceSnapshot.asStateFlow()

    private var currentCheckoutIdempotencyKey: String = generateIdempotencyKey()
    private var boundCartFingerprintForKey: String? = null

    /**
     * Exposes the active `Idempotency-Key` for the current logical checkout attempt.
     *
     * Invariant (Microtask 1D.3):
     * - Remains constant across retries of the same logical order (when the cart items/quantities
     *   have not been modified).
     * - Rotates only when the cart contents change (`addToCartMillis`, `removeFromCart`), when a
     *   completed order confirmation is dismissed (`dismissOrderConfirmation`), or when the server
     *   reports an `IDEMPOTENCY_CONFLICT` (`409`).
     */
    val activeCheckoutIdempotencyKey: String
        get() = currentCheckoutIdempotencyKey

    /**
     * Detailed cart line items combining Room cart items with latest product states.
     * Keeps unavailable/insufficient items visible in the cart with an explicit Arabic warning
     * rather than silently dropping the user's cart items.
     */
    val detailedCartItems: StateFlow<List<CartLineItemUiModel>> = combine(
        cartRepository.getCartItems(),
        productRepository.getAllProducts()
    ) { rawCartItems, products ->
        rawCartItems.mapNotNull { cartItem ->
            val product = products.find { it.id == cartItem.productId } ?: return@mapNotNull null
            val qtyMillis = cartItem.effectiveQuantityMillis
            val lineTotal = CurrencyCalculator.calculateLineTotalHalalasLong(
                unitPriceHalalas = product.priceHalalas,
                quantityMillis = qtyMillis
            )
            val warning = when {
                !product.isVisibleInStorefront -> "هذا المنتج غير متاح حالياً في المتجر."
                product.isOutOfStock -> "نفد المخزون حالياً لهذا المنتج."
                qtyMillis > product.effectiveStockMillis ->
                    "المتوفر حالياً ${product.formattedStockQuantity()} ${product.unit} فقط."
                product.priceHalalas <= 0 -> "سعر المنتج غير صالح."
                else -> null
            }
            CartLineItemUiModel(
                cartItem = cartItem,
                product = product,
                quantityMillis = qtyMillis,
                unitPriceHalalas = product.priceHalalas,
                lineTotalHalalas = lineTotal,
                isAvailable = warning == null,
                availabilityWarningArabic = warning
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Preserved legacy flow of (Product, Int) pairs for full backward compatibility
    val cartItems: StateFlow<List<Pair<Product, Int>>> = detailedCartItems.map { items ->
        items.map { it.product to it.cartItem.quantity }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Exact integer halalas subtotal (never uses floating-point arithmetic)
    val subtotal: StateFlow<Long> = detailedCartItems.map { items ->
        items.sumOf { it.lineTotalHalalas }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val deliveryFee: Long = OrderRepository.DEFAULT_DELIVERY_FEE_HALALAS.toLong() // 1500 halalas = 15.00 SAR

    val total: StateFlow<Long> = combine(subtotal, _discountHalalas) { sub, discount ->
        if (sub <= 0L) {
            0L
        } else {
            val effectiveDiscount = discount.coerceIn(0L, sub + deliveryFee)
            sub + deliveryFee - effectiveDiscount
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun updateDeliveryAddress(address: String) {
        _deliveryAddress.value = address
    }

    fun clearErrorMessage() {
        _cartErrorMessage.value = null
    }

    fun dismissOrderConfirmation() {
        _lastCompletedOrder.value = null
        _cartErrorMessage.value = null
        currentCheckoutIdempotencyKey = generateIdempotencyKey()
        boundCartFingerprintForKey = null
    }

    /**
     * Captures the current unit prices (in halalas) of items in the cart when entering the checkout confirmation step
     * so stale prices can be detected if a product's price changes before order submission.
     * Preserves [currentCheckoutIdempotencyKey] if the cart contents have not changed since the key was issued.
     */
    fun captureCheckoutPriceSnapshot() {
        val currentItems = detailedCartItems.value
        val snapshot = currentItems.associate { it.product.id to it.product.priceHalalas }
        if (snapshot.isNotEmpty()) {
            _capturedPriceSnapshot.value = snapshot
        }
        val fingerprint = computeCartFingerprint(currentItems.map { it.cartItem })
        if (boundCartFingerprintForKey != null && boundCartFingerprintForKey != fingerprint) {
            currentCheckoutIdempotencyKey = generateIdempotencyKey()
        }
        boundCartFingerprintForKey = fingerprint
    }

    fun addToCart(productId: Int, quantity: Int) {
        if (quantity <= 0) {
            _cartErrorMessage.value = "الكمية المطلوبة يجب أن تكون أكبر من صفر."
            return
        }
        addToCartMillis(productId = productId, quantityMillis = quantity * 1000)
    }

    fun addToCartFractional(productId: Int, quantityUnits: Double) {
        val millis = kotlin.math.round(quantityUnits * 1000.0).toInt()
        if (millis <= 0) {
            _cartErrorMessage.value = "الكمية المطلوبة يجب أن تكون أكبر من صفر."
            return
        }
        addToCartMillis(productId = productId, quantityMillis = millis)
    }

    fun addToCartMillis(productId: Int, quantityMillis: Int) {
        if (quantityMillis <= 0) {
            _cartErrorMessage.value = "الكمية المطلوبة يجب أن تكون أكبر من صفر."
            return
        }
        viewModelScope.launch {
            val allProducts = productRepository.getAllProducts().firstOrNull().orEmpty()
            val targetProduct = allProducts.find { it.id == productId }
            if (targetProduct != null) {
                if (!targetProduct.isVisibleInStorefront) {
                    _cartErrorMessage.value = "هذا المنتج غير متاح حالياً في المتجر."
                    return@launch
                }
                if (targetProduct.isOutOfStock) {
                    _cartErrorMessage.value = "عذراً، نفد مخزون «${targetProduct.name}» ولا يمكن إضافته إلى السلة."
                    return@launch
                }
                if (quantityMillis > targetProduct.effectiveStockMillis) {
                    val requestedLabel = QuantityFormatter.formatMillis(quantityMillis)
                    val availableLabel = targetProduct.formattedStockQuantity()
                    _cartErrorMessage.value =
                        "الكمية المطلوبة ($requestedLabel) تتجاوز المخزون المتوفر ($availableLabel ${targetProduct.unit})."
                    return@launch
                }
            }
            _cartErrorMessage.value = null
            _lastCompletedOrder.value = null
            val cartItem = if (quantityMillis % 1000 == 0) {
                CartItem(productId = productId, quantity = quantityMillis / 1000, quantityMillis = quantityMillis)
            } else {
                CartItem.fromMillis(productId = productId, quantityMillis = quantityMillis)
            }
            cartRepository.addToCart(cartItem)
            currentCheckoutIdempotencyKey = generateIdempotencyKey()
            boundCartFingerprintForKey = null
        }
    }

    fun updateCartItemQuantityMillis(productId: Int, newQuantityMillis: Int) {
        if (newQuantityMillis <= 0) {
            removeFromCart(productId)
            return
        }
        addToCartMillis(productId = productId, quantityMillis = newQuantityMillis)
    }

    fun removeFromCart(productId: Int) {
        viewModelScope.launch {
            cartRepository.removeFromCart(productId)
            _cartErrorMessage.value = null
            currentCheckoutIdempotencyKey = generateIdempotencyKey()
            boundCartFingerprintForKey = null
        }
    }

    /**
     * Executes checkout via [OrderRepository] and updates UI state ONLY after confirmation.
     *
     * - When [OrderRepository.hasRemoteCheckoutApi] is `true`, executes server-authoritative checkout
     *   against `POST /api/v1/orders/` ([OrderRepository.executeServerCheckout]).
     * - Reuses the exact same [currentCheckoutIdempotencyKey] across retries of the same logical order
     *   (never generating a new key on retry unless the cart items changed or an idempotency conflict occurred).
     * - Never sets [lastCompletedOrder] before receiving a confirmed `201 Created` or `200 OK` response.
     */
    suspend fun executeCheckout(
        customer: AuthenticatedUser? = null,
        idempotencyKeyOverride: String? = null,
        expectedPricesOverride: Map<Int, Int>? = _capturedPriceSnapshot.value,
        branchId: Int = OrderRepository.DEFAULT_BRANCH_ID,
        fulfillmentWarehouseId: Int? = null
    ): CheckoutOutcome {
        val repo = orderRepository ?: return CheckoutOutcome.Failure(
            reason = CheckoutFailureReason.TRANSACTION_ERROR,
            messageArabic = "خدمة إتمام الطلب غير مهيأة."
        )

        if (_isSubmittingCheckout.value && idempotencyKeyOverride == null) {
            return CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.DUPLICATE_SUBMISSION,
                messageArabic = "جاري معالجة الطلب الحالي بالفعل، يرجى الانتظار."
            )
        }

        // Rotate idempotency key ONLY if the cart items have changed since the current key was bound
        val currentRawCartItems = cartRepository.getCartItemsOnce()
        if (currentRawCartItems.isNotEmpty() && idempotencyKeyOverride == null) {
            val fingerprint = computeCartFingerprint(currentRawCartItems)
            if (boundCartFingerprintForKey != null && boundCartFingerprintForKey != fingerprint) {
                currentCheckoutIdempotencyKey = generateIdempotencyKey()
            }
            boundCartFingerprintForKey = fingerprint
        }

        _isSubmittingCheckout.value = true
        return try {
            val effectiveKey = idempotencyKeyOverride ?: currentCheckoutIdempotencyKey
            val outcome = if (repo.hasRemoteCheckoutApi) {
                repo.executeServerCheckout(
                    branchId = branchId,
                    fulfillmentWarehouseId = fulfillmentWarehouseId,
                    idempotencyKey = effectiveKey,
                    customerName = customer?.fullName ?: "عميل فريش",
                    deliveryAddress = _deliveryAddress.value,
                    fallbackCustomerId = customer?.customerId ?: customer?.id ?: OrderRepository.GUEST_CUSTOMER_ID
                )
            } else {
                repo.executeLocalCheckout(
                    customerId = customer?.id ?: OrderRepository.GUEST_CUSTOMER_ID,
                    customerName = customer?.fullName ?: "متسوق محلي",
                    actorRole = customer?.role?.name ?: UserRole.CUSTOMER.name,
                    deliveryAddress = _deliveryAddress.value,
                    deliveryFeeHalalas = deliveryFee.toInt(),
                    discountHalalas = _discountHalalas.value.toInt(),
                    expectedUnitPricesHalalas = expectedPricesOverride,
                    idempotencyKey = effectiveKey
                )
            }

            when (outcome) {
                is CheckoutOutcome.Success -> {
                    _cartErrorMessage.value = null
                    _lastCompletedOrder.value = outcome
                    _capturedPriceSnapshot.value = null
                }
                is CheckoutOutcome.Failure -> {
                    _cartErrorMessage.value = outcome.messageArabic
                    val refreshedSnapshot = detailedCartItems.value.associate {
                        it.product.id to it.product.priceHalalas
                    }
                    if (refreshedSnapshot.isNotEmpty()) {
                        _capturedPriceSnapshot.value = refreshedSnapshot
                    }
                    // Preserve currentCheckoutIdempotencyKey for retries of the same logical order!
                    // Only rotate the key if the server explicitly rejected the key as an IDEMPOTENCY_CONFLICT
                    // (or in legacy local mode when a non-duplicate validation failure occurred).
                    if (outcome.reason == CheckoutFailureReason.IDEMPOTENCY_CONFLICT ||
                        (!repo.hasRemoteCheckoutApi && outcome.reason != CheckoutFailureReason.DUPLICATE_SUBMISSION)
                    ) {
                        currentCheckoutIdempotencyKey = generateIdempotencyKey()
                        boundCartFingerprintForKey = null
                    }
                }
            }
            outcome
        } finally {
            _isSubmittingCheckout.value = false
        }
    }

    fun submitLocalCheckout(
        customer: AuthenticatedUser? = null,
        onSuccess: (CheckoutOutcome.Success) -> Unit = {}
    ) {
        viewModelScope.launch {
            val outcome = executeCheckout(customer = customer)
            if (outcome is CheckoutOutcome.Success) {
                onSuccess(outcome)
            }
        }
    }

    private val _selectedCustomerOrderId = MutableStateFlow<Int?>(null)
    val selectedCustomerOrderId: StateFlow<Int?> = _selectedCustomerOrderId.asStateFlow()

    private val _isOrderSyncInProgress = MutableStateFlow(false)
    val isOrderSyncInProgress: StateFlow<Boolean> = _isOrderSyncInProgress.asStateFlow()

    private val _orderSyncError = MutableStateFlow<String?>(null)
    val orderSyncError: StateFlow<String?> = _orderSyncError.asStateFlow()

    fun selectCustomerOrderForDetails(
        orderId: Int?,
        actor: AuthenticatedUser? = null
    ) {
        _selectedCustomerOrderId.value = orderId
        if (orderId != null && actor != null && orderRepository?.hasRemoteCheckoutApi == true) {
            viewModelScope.launch {
                syncCustomerOrderDetailsFromServer(orderId = orderId, actor = actor)
            }
        }
    }

    /**
     * Synchronizes the authenticated customer's orders from `GET /api/v1/orders/` into Room.
     * Preserves existing Room cache on network outages and surfaces an Arabic error message.
     */
    suspend fun syncCustomerOrdersFromServer(
        actor: AuthenticatedUser?,
        statusFilter: String? = null
    ): OrderSyncOutcome {
        val repo = orderRepository ?: return OrderSyncOutcome.Failure(
            reason = com.example.data.repository.OrderTransitionFailureReason.NETWORK_ERROR,
            messageArabic = "خدمة مزامنة الطلبات غير مهيأة."
        )
        _isOrderSyncInProgress.value = true
        return try {
            val outcome = repo.syncCustomerOrdersFromServer(actor = actor, statusFilter = statusFilter)
            when (outcome) {
                is OrderSyncOutcome.Success -> {
                    _orderSyncError.value = null
                }
                is OrderSyncOutcome.Failure -> {
                    _orderSyncError.value = outcome.messageArabic
                }
            }
            outcome
        } finally {
            _isOrderSyncInProgress.value = false
        }
    }

    /**
     * Synchronizes a single customer order (`GET /api/v1/orders/{id}/`) from the server into Room,
     * including the latest server status (`PENDING`, `CONFIRMED`, `PROCESSING`, `COMPLETED`, `CANCELLED`)
     * and `status_history`.
     */
    suspend fun syncCustomerOrderDetailsFromServer(
        orderId: Int,
        actor: AuthenticatedUser?
    ): OrderSyncOutcome {
        val repo = orderRepository ?: return OrderSyncOutcome.Failure(
            reason = com.example.data.repository.OrderTransitionFailureReason.NETWORK_ERROR,
            messageArabic = "خدمة مزامنة الطلبات غير مهيأة."
        )
        _isOrderSyncInProgress.value = true
        return try {
            val outcome = repo.syncOrderDetailsFromServer(orderId = orderId, actor = actor)
            when (outcome) {
                is OrderSyncOutcome.Success -> {
                    _orderSyncError.value = null
                }
                is OrderSyncOutcome.Failure -> {
                    _orderSyncError.value = outcome.messageArabic
                }
            }
            outcome
        } finally {
            _isOrderSyncInProgress.value = false
        }
    }

    fun refreshCustomerOrdersFromServer(
        actor: AuthenticatedUser?,
        statusFilter: String? = null
    ) {
        viewModelScope.launch {
            syncCustomerOrdersFromServer(actor = actor, statusFilter = statusFilter)
        }
    }

    fun observeOrdersForCustomer(
        customerId: String,
        actor: AuthenticatedUser? = null
    ): Flow<List<OrderWithItems>> {
        return orderRepository?.getOrdersWithItemsForCustomer(customerId, actor) ?: flowOf(emptyList())
    }

    fun observeOrdersForAuthenticatedCustomer(
        actor: AuthenticatedUser?,
        requestedCustomerId: String? = null
    ): Flow<List<OrderWithItems>> {
        return orderRepository?.getOrdersForAuthenticatedCustomer(actor, requestedCustomerId)
            ?: flowOf(emptyList())
    }

    suspend fun getCustomerOrderDetailsOnce(
        orderId: Int,
        actor: AuthenticatedUser?,
        requestedCustomerId: String? = null
    ): OrderWithItems? {
        return orderRepository?.getOrderDetailsForCustomerOnce(
            orderId = orderId,
            actor = actor,
            requestedCustomerId = requestedCustomerId
        )
    }

    fun observeAllOrdersForStoreManagement(actor: AuthenticatedUser?): Flow<List<OrderWithItems>> {
        return orderRepository?.getAllOrdersWithItemsForStoreManagement(actor) ?: flowOf(emptyList())
    }

    private fun computeCartFingerprint(items: List<CartItem>): String {
        return items
            .sortedBy { it.productId }
            .joinToString("|") { "${it.productId}:${it.effectiveQuantityMillis}" }
    }

    private fun generateIdempotencyKey(): String = "chk_${UUID.randomUUID()}"
}
