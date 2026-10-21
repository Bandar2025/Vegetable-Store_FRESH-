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
    }

    /**
     * Captures the current unit prices (in halalas) of items in the cart when entering the checkout confirmation step
     * so stale prices can be detected if a product's price changes before order submission.
     */
    fun captureCheckoutPriceSnapshot() {
        val snapshot = detailedCartItems.value.associate { it.product.id to it.product.priceHalalas }
        if (snapshot.isNotEmpty()) {
            _capturedPriceSnapshot.value = snapshot
        }
        currentCheckoutIdempotencyKey = generateIdempotencyKey()
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
        }
    }

    /**
     * Executes atomic local checkout via [OrderRepository] and updates UI state.
     */
    suspend fun executeCheckout(
        customer: AuthenticatedUser? = null,
        idempotencyKeyOverride: String? = null,
        expectedPricesOverride: Map<Int, Int>? = _capturedPriceSnapshot.value
    ): CheckoutOutcome {
        val repo = orderRepository ?: return CheckoutOutcome.Failure(
            reason = CheckoutFailureReason.TRANSACTION_ERROR,
            messageArabic = "خدمة إتمام الطلب المحلي غير مهيأة."
        )

        if (_isSubmittingCheckout.value && idempotencyKeyOverride == null) {
            return CheckoutOutcome.Failure(
                reason = CheckoutFailureReason.DUPLICATE_SUBMISSION,
                messageArabic = "جاري معالجة الطلب الحالي بالفعل، يرجى الانتظار."
            )
        }

        _isSubmittingCheckout.value = true
        return try {
            val effectiveKey = idempotencyKeyOverride ?: currentCheckoutIdempotencyKey
            val outcome = repo.executeLocalCheckout(
                customerId = customer?.id ?: OrderRepository.GUEST_CUSTOMER_ID,
                customerName = customer?.fullName ?: "متسوق محلي",
                actorRole = customer?.role?.name ?: UserRole.CUSTOMER.name,
                deliveryAddress = _deliveryAddress.value,
                deliveryFeeHalalas = deliveryFee.toInt(),
                discountHalalas = _discountHalalas.value.toInt(),
                expectedUnitPricesHalalas = expectedPricesOverride,
                idempotencyKey = effectiveKey
            )

            when (outcome) {
                is CheckoutOutcome.Success -> {
                    _cartErrorMessage.value = null
                    _lastCompletedOrder.value = outcome
                    _capturedPriceSnapshot.value = null
                }
                is CheckoutOutcome.Failure -> {
                    _cartErrorMessage.value = outcome.messageArabic
                    // Refresh captured price snapshot from latest DB state so user sees updated prices/stock
                    val refreshedSnapshot = detailedCartItems.value.associate {
                        it.product.id to it.product.priceHalalas
                    }
                    if (refreshedSnapshot.isNotEmpty()) {
                        _capturedPriceSnapshot.value = refreshedSnapshot
                    }
                    // Allow retrying with a new idempotency key after fixing the cart or reviewing updated prices
                    if (outcome.reason != CheckoutFailureReason.DUPLICATE_SUBMISSION) {
                        currentCheckoutIdempotencyKey = generateIdempotencyKey()
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

    fun selectCustomerOrderForDetails(orderId: Int?) {
        _selectedCustomerOrderId.value = orderId
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

    private fun generateIdempotencyKey(): String = "chk_${UUID.randomUUID()}"
}
