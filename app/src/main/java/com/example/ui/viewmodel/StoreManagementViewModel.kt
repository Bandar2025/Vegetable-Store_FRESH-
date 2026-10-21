package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.auth.AccessControlDecision
import com.example.data.auth.AccessControlPolicy
import com.example.data.auth.AuthenticatedUser
import com.example.data.local.model.OrderStatus
import com.example.data.local.model.Product
import com.example.data.local.model.ProductExpiryStatus
import com.example.data.local.model.StockAdjustment
import com.example.data.repository.OrderRepository
import com.example.data.repository.OrderTransitionFailureReason
import com.example.data.repository.OrderTransitionOutcome
import com.example.data.repository.OrderWithItems
import com.example.data.repository.ProductRepository
import com.example.data.repository.StockAdjustmentOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class StoreManagementSection(val arabicTitle: String) {
    DASHBOARD("لوحة تحكم المتجر"),
    PRODUCT_LIST("إدارة المنتجات"),
    ADD_PRODUCT("إضافة منتج جديد"),
    EDIT_PRODUCT("تعديل بيانات المنتج"),
    PRODUCT_DETAILS("تفاصيل المنتج والمخزون"),
    INVENTORY("متابعة المخزون والصلاحية"),
    ORDERS_NOTICE("الطلبات والمبيعات")
}

enum class StoreProductStatusFilter(val arabicLabel: String) {
    ALL("الكل"),
    ACTIVE_ONLY("نشط"),
    INACTIVE_ONLY("غير نشط"),
    ARCHIVED_ONLY("مؤرشف"),
    LOW_STOCK("منخفض المخزون"),
    OUT_OF_STOCK("نفد المخزون"),
    EXPIRING_SOON("يقترب من انتهاء الصلاحية"),
    EXPIRED("منتهي الصلاحية")
}

data class StoreDashboardMetrics(
    val totalProductsCount: Int = 0,
    val activeProductsCount: Int = 0,
    val inactiveProductsCount: Int = 0,
    val archivedProductsCount: Int = 0,
    val totalAvailableStockUnits: Int = 0,
    val lowStockCount: Int = 0,
    val expiredCount: Int = 0,
    val expiringSoonCount: Int = 0,
    val outOfStockCount: Int = 0,
    val expiryWarningDays: Int = Product.DEFAULT_EXPIRY_WARNING_DAYS,
    val ordersStatisticsAvailable: Boolean = false,
    val unavailableStatsNoteArabic: String = "الطلبات والإيرادات: غير متوفرة حالياً (لا توجد بيانات طلبات أو مبيعات فعلية مسجلة محلياً بعد)"
) {
    companion object {
        fun fromProducts(
            products: List<Product>,
            referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO,
            expiryWarningDays: Int = Product.DEFAULT_EXPIRY_WARNING_DAYS
        ): StoreDashboardMetrics {
            val nonArchived = products.filter { !it.isArchived }
            val activeProducts = nonArchived.filter { it.isActive }
            return StoreDashboardMetrics(
                totalProductsCount = nonArchived.size,
                activeProductsCount = activeProducts.size,
                inactiveProductsCount = nonArchived.count { !it.isActive },
                archivedProductsCount = products.count { it.isArchived },
                totalAvailableStockUnits = activeProducts.sumOf { it.stockQuantity.coerceAtLeast(0) },
                lowStockCount = nonArchived.count { it.isLowStock },
                expiredCount = nonArchived.count { it.isExpired(referenceDateIso) },
                expiringSoonCount = nonArchived.count {
                    it.isExpiringSoon(referenceDateIso, expiryWarningDays)
                },
                outOfStockCount = nonArchived.count { it.isOutOfStock },
                expiryWarningDays = expiryWarningDays.coerceAtLeast(0),
                ordersStatisticsAvailable = false
            )
        }
    }
}

data class ProductFormInput(
    val id: Int? = null,
    val name: String = "",
    val nameEn: String = "",
    val category: String = "فواكه",
    val priceSarInput: String = "",
    val unit: String = "كيلوجرام",
    val description: String = "",
    val imageUrl: String = "apple",
    val stockQuantityInput: String = "25",
    val lowStockThresholdInput: String = "5",
    val expiryDateInput: String = "2026-12-31",
    val isActive: Boolean = true,
    val isArchived: Boolean = false
) {
    companion object {
        fun fromProduct(product: Product): ProductFormInput {
            return ProductFormInput(
                id = product.id,
                name = product.name,
                nameEn = product.nameEn,
                category = product.category,
                priceSarInput = product.formattedPriceSar(),
                unit = product.unit,
                description = product.description,
                imageUrl = product.imageUrl,
                stockQuantityInput = product.stockQuantity.toString(),
                lowStockThresholdInput = product.lowStockThreshold.toString(),
                expiryDateInput = product.expiryDate,
                isActive = product.isActive,
                isArchived = product.isArchived
            )
        }
    }
}

data class ProductFormValidationResult(
    val isValid: Boolean,
    val errorArabic: String? = null,
    val parsedPriceHalalas: Int = 0,
    val parsedStockQuantity: Int = 0,
    val parsedLowStockThreshold: Int = 5,
    val normalizedExpiryDate: String = ""
)

data class StockAdjustmentValidationResult(
    val isValid: Boolean,
    val errorArabic: String? = null,
    val parsedTargetQuantity: Int = 0,
    val parsedDelta: Int = 0,
    val cleanReason: String = ""
)

object StockAdjustmentValidator {
    /**
     * Validates a target stock quantity input (supporting Arabic `٠-٩` and Latin `0-9` digits)
     * and an adjustment reason before persisting.
     */
    fun validateTargetQuantity(
        currentQuantity: Int,
        newQuantityInput: String,
        reasonInput: String
    ): StockAdjustmentValidationResult {
        val normalizedQty = ProductFormValidator.normalizeArabicDigits(newQuantityInput.trim())
        val parsedTarget = normalizedQty.toIntOrNull()
        if (parsedTarget == null) {
            return StockAdjustmentValidationResult(
                isValid = false,
                errorArabic = "يرجى إدخال كمية مخزون رقمية صحيحة."
            )
        }
        if (parsedTarget < 0) {
            return StockAdjustmentValidationResult(
                isValid = false,
                errorArabic = "يرجى إدخال كمية مخزون غير سالبة (٠ أو أكثر)."
            )
        }
        val cleanReason = reasonInput.trim()
        if (cleanReason.length < 2) {
            return StockAdjustmentValidationResult(
                isValid = false,
                errorArabic = "يرجى تحديد سبب تعديل المخزون."
            )
        }
        return StockAdjustmentValidationResult(
            isValid = true,
            parsedTargetQuantity = parsedTarget,
            parsedDelta = parsedTarget - currentQuantity,
            cleanReason = cleanReason
        )
    }

    /**
     * Validates a signed stock delta (e.g. `+5`, `-2`, `+٥`, `-٢`) against [currentQuantity].
     * Rejects adjustments that would result in a negative stock quantity.
     */
    fun validateDeltaAdjustment(
        currentQuantity: Int,
        deltaInput: String,
        reasonInput: String
    ): StockAdjustmentValidationResult {
        val normalizedDelta = ProductFormValidator.normalizeArabicDigits(deltaInput.trim())
            .replace("+", "")
        val parsedDelta = normalizedDelta.toIntOrNull()
        if (parsedDelta == null || parsedDelta == 0) {
            return StockAdjustmentValidationResult(
                isValid = false,
                errorArabic = "يرجى إدخال مقدار تعديل رقمي غير صفري."
            )
        }
        val resultingQuantity = currentQuantity + parsedDelta
        if (resultingQuantity < 0) {
            return StockAdjustmentValidationResult(
                isValid = false,
                errorArabic = "لا يمكن إنقاص المخزون بأكثر من الكمية المتوفرة ($currentQuantity)."
            )
        }
        val cleanReason = reasonInput.trim()
        if (cleanReason.length < 2) {
            return StockAdjustmentValidationResult(
                isValid = false,
                errorArabic = "يرجى تحديد سبب تعديل المخزون."
            )
        }
        return StockAdjustmentValidationResult(
            isValid = true,
            parsedTargetQuantity = resultingQuantity,
            parsedDelta = parsedDelta,
            cleanReason = cleanReason
        )
    }
}

object ProductFormValidator {
    fun validate(input: ProductFormInput): ProductFormValidationResult {
        val cleanName = input.name.trim()
        if (cleanName.length < 2) {
            return ProductFormValidationResult(
                isValid = false,
                errorArabic = "يرجى إدخال اسم المنتج (حرفين على الأقل)."
            )
        }

        val cleanCategory = input.category.trim()
        if (cleanCategory.isBlank()) {
            return ProductFormValidationResult(
                isValid = false,
                errorArabic = "يرجى اختيار فئة المنتج."
            )
        }

        val normalizedPrice = normalizeArabicDigits(input.priceSarInput.trim())
        val priceSar = normalizedPrice.toDoubleOrNull()
        if (priceSar == null || priceSar <= 0.0 || priceSar > 10000.0) {
            return ProductFormValidationResult(
                isValid = false,
                errorArabic = "يرجى إدخال سعر صحيح أكبر من صفر بالريال السعودي (ر.س)."
            )
        }
        val priceHalalas = (priceSar * 100.0).roundToInt()
        if (priceHalalas <= 0) {
            return ProductFormValidationResult(
                isValid = false,
                errorArabic = "السعر بالهللات يجب أن يكون أكبر من صفر."
            )
        }

        val cleanUnit = input.unit.trim()
        if (cleanUnit.isBlank()) {
            return ProductFormValidationResult(
                isValid = false,
                errorArabic = "يرجى تحديد وحدة القياس (مثل: كيلوجرام، حبة، ربطة)."
            )
        }

        val cleanDescription = input.description.trim()
        if (cleanDescription.length < 4) {
            return ProductFormValidationResult(
                isValid = false,
                errorArabic = "يرجى إدخال وصف واضح للمنتج (٤ أحرف على الأقل)."
            )
        }

        val normalizedStock = normalizeArabicDigits(input.stockQuantityInput.trim())
        val stockQuantity = normalizedStock.toIntOrNull()
        if (stockQuantity == null || stockQuantity < 0) {
            return ProductFormValidationResult(
                isValid = false,
                errorArabic = "يرجى إدخال كمية مخزون صحيحة (٠ أو أكثر)."
            )
        }

        val normalizedThreshold = normalizeArabicDigits(input.lowStockThresholdInput.trim())
        val lowStockThreshold = normalizedThreshold.toIntOrNull()
        if (lowStockThreshold == null || lowStockThreshold < 0) {
            return ProductFormValidationResult(
                isValid = false,
                errorArabic = "يرجى إدخال حد تنبيه انخفاض المخزون بشكل صحيح (٠ أو أكثر)."
            )
        }

        val cleanExpiry = normalizeArabicDigits(input.expiryDateInput.trim())
        if (cleanExpiry.isNotEmpty() && !Product.isValidIsoDate(cleanExpiry)) {
            return ProductFormValidationResult(
                isValid = false,
                errorArabic = "يرجى إدخال تاريخ صلاحية فعلي بصيغة YYYY-MM-DD (مثال: 2026-12-31) أو تركه فارغاً للمنتجات غير القابلة للتلف."
            )
        }

        return ProductFormValidationResult(
            isValid = true,
            parsedPriceHalalas = priceHalalas,
            parsedStockQuantity = stockQuantity,
            parsedLowStockThreshold = lowStockThreshold,
            normalizedExpiryDate = cleanExpiry
        )
    }

    fun normalizeArabicDigits(value: String): String {
        val builder = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                in '٠'..'٩' -> builder.append((ch - '٠'))
                in '۰'..'۹' -> builder.append((ch - '۰'))
                '٫', ',' -> builder.append('.')
                '−', '–' -> builder.append('-')
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }
}

/**
 * ViewModel for Store Owner and Store Administrator operations, including dashboard metrics,
 * product management, inventory monitoring, configurable expiry warning thresholds, and
 * persisted stock-adjustment audit trails.
 *
 * Security Note: Client-side role checks enforced via [AccessControlPolicy] represent a
 * local development-stage boundary and must be backed by server-side authorization in production.
 */
class StoreManagementViewModel(
    repository: ProductRepository,
    referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO,
    initialExpiryWarningDays: Int = Product.DEFAULT_EXPIRY_WARNING_DAYS,
    private val orderRepository: OrderRepository? = null
) : InventoryViewModel(
    repository = repository,
    referenceDateIso = referenceDateIso,
    initialExpiryWarningDays = initialExpiryWarningDays
) {
    private val _storeOrders = MutableStateFlow<List<OrderWithItems>>(emptyList())
    val storeOrders: StateFlow<List<OrderWithItems>> = _storeOrders.asStateFlow()

    init {
        if (orderRepository != null) {
            viewModelScope.launch {
                orderRepository.observeAllPersistedOrdersWithItems().collectLatest { orders ->
                    _storeOrders.value = orders
                }
            }
        }
    }

    private val _selectedOrderStatusFilter = MutableStateFlow<OrderStatus?>(null)
    val selectedOrderStatusFilter: StateFlow<OrderStatus?> = _selectedOrderStatusFilter.asStateFlow()

    private val _selectedOrderId = MutableStateFlow<Int?>(null)
    val selectedOrderId: StateFlow<Int?> = _selectedOrderId.asStateFlow()

    /**
     * Returns persisted orders only when [actor] has verified store-management privileges ([AccessControlPolicy]).
     * Optionally filters by [statusFilter] (when non-null).
     */
    fun getVisibleOrdersForActor(
        actor: AuthenticatedUser?,
        statusFilter: OrderStatus? = null
    ): List<OrderWithItems> {
        val decision = AccessControlPolicy.checkAccess(
            user = actor,
            requiresAuth = true,
            requiresStoreManagement = true
        )
        if (decision !is AccessControlDecision.Allowed) {
            return emptyList()
        }
        val allVisible = _storeOrders.value
        return if (statusFilter == null) {
            allVisible
        } else {
            allVisible.filter { it.order.orderStatus == statusFilter }
        }
    }

    /**
     * Returns orders filtered by the currently selected [selectedOrderStatusFilter] for an authorized store actor.
     */
    fun getFilteredOrdersForActor(actor: AuthenticatedUser?): List<OrderWithItems> {
        return getVisibleOrdersForActor(actor = actor, statusFilter = _selectedOrderStatusFilter.value)
    }

    /**
     * Returns the currently selected order with its item snapshots for an authorized store actor, or `null`.
     */
    fun getSelectedOrderForActor(actor: AuthenticatedUser?): OrderWithItems? {
        val targetOrderId = _selectedOrderId.value ?: return null
        return getVisibleOrdersForActor(actor = actor, statusFilter = null)
            .find { it.order.id == targetOrderId }
    }

    fun selectOrderStatusFilter(status: OrderStatus?) {
        _errorMessage.value = null
        _selectedOrderStatusFilter.value = status
    }

    fun openOrderDetails(orderId: Int) {
        _errorMessage.value = null
        _selectedOrderId.value = orderId
    }

    fun closeOrderDetails() {
        _errorMessage.value = null
        _selectedOrderId.value = null
    }

    /**
     * Executes a validated order status transition (or cancellation with single stock restoration)
     * directly via [OrderRepository] and updates Arabic UI feedback banners.
     */
    suspend fun executeOrderStatusTransition(
        actor: AuthenticatedUser?,
        orderId: Int,
        targetStatus: OrderStatus,
        reasonNote: String = ""
    ): OrderTransitionOutcome {
        val repo = orderRepository
        if (repo == null) {
            val msg = "خدمة إدارة الطلبات المحلية غير مهيأة."
            _errorMessage.value = msg
            return OrderTransitionOutcome.Failure(
                reason = OrderTransitionFailureReason.TRANSACTION_ERROR,
                messageArabic = msg
            )
        }
        val outcome = repo.transitionOrderStatus(
            orderId = orderId,
            targetStatus = targetStatus,
            actor = actor,
            reasonNote = reasonNote
        )
        when (outcome) {
            is OrderTransitionOutcome.Success -> {
                _errorMessage.value = null
                _statusBannerMessage.value = outcome.messageArabic
            }
            is OrderTransitionOutcome.Failure -> {
                _errorMessage.value = outcome.messageArabic
            }
        }
        return outcome
    }

    /**
     * Initiates a validated order status transition from the UI/ViewModel layer.
     * Rejects unauthorized callers or invalid transitions immediately with an Arabic error message.
     */
    fun transitionOrderStatus(
        actor: AuthenticatedUser?,
        orderId: Int,
        targetStatus: OrderStatus,
        reasonNote: String = ""
    ): Boolean {
        if (!verifyAuthorizedUser(actor)) return false
        val repo = orderRepository
        if (repo == null) {
            _errorMessage.value = "خدمة إدارة الطلبات المحلية غير مهيأة."
            return false
        }

        val currentSnapshot = _storeOrders.value.find { it.order.id == orderId }?.order
        if (currentSnapshot != null) {
            val currentStatus = currentSnapshot.orderStatus
            if (currentStatus == OrderStatus.CANCELLED) {
                _errorMessage.value =
                    "الطلب ${currentSnapshot.displayOrderNumber} ملغي مسبقاً؛ لا يمكن إلغاؤه مرة أخرى أو تعديل حالته."
                return false
            }
            if (currentStatus == OrderStatus.DELIVERED && targetStatus == OrderStatus.CANCELLED) {
                _errorMessage.value =
                    "لا يمكن إلغاء الطلب ${currentSnapshot.displayOrderNumber} بعد اكتماله وتوصيله."
                return false
            }
            if (!currentStatus.canTransitionTo(targetStatus)) {
                _errorMessage.value =
                    "انتقال حالة غير صالح للطلب ${currentSnapshot.displayOrderNumber}: لا يمكن الانتقال من «${currentStatus.arabicLabel}» إلى «${targetStatus.arabicLabel}»."
                return false
            }
        }

        viewModelScope.launch {
            executeOrderStatusTransition(
                actor = actor,
                orderId = orderId,
                targetStatus = targetStatus,
                reasonNote = reasonNote
            )
        }
        return true
    }

    /**
     * Cancels an active order and restores its stock atomically at most once.
     */
    fun cancelOrder(
        actor: AuthenticatedUser?,
        orderId: Int,
        reasonNote: String = ""
    ): Boolean {
        return transitionOrderStatus(
            actor = actor,
            orderId = orderId,
            targetStatus = OrderStatus.CANCELLED,
            reasonNote = reasonNote
        )
    }

    private val _currentSection = MutableStateFlow(StoreManagementSection.DASHBOARD)
    val currentSection: StateFlow<StoreManagementSection> = _currentSection.asStateFlow()

    private val _selectedProductId = MutableStateFlow<Int?>(null)
    val selectedProductId: StateFlow<Int?> = _selectedProductId.asStateFlow()

    private val _productPendingArchive = MutableStateFlow<Product?>(null)
    val productPendingArchive: StateFlow<Product?> = _productPendingArchive.asStateFlow()

    private val _productForStockDialog = MutableStateFlow<Product?>(null)
    val productForStockDialog: StateFlow<Product?> = _productForStockDialog.asStateFlow()

    private val _formState = MutableStateFlow(ProductFormInput())
    val formState: StateFlow<ProductFormInput> = _formState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    fun getAdjustmentsForProduct(productId: Int): List<StockAdjustment> {
        return _stockAdjustments.value.filter { it.productId == productId }
    }

    fun getRecentUpdatedProducts(limit: Int = 5): List<Product> {
        return _allProducts.value
            .sortedWith(compareByDescending<Product> { it.updatedAtMillis }.thenByDescending { it.id })
            .take(limit)
    }

    fun getSelectedProduct(): Product? {
        val targetId = _selectedProductId.value ?: return null
        return _allProducts.value.find { it.id == targetId }
    }

    fun navigateToSection(
        section: StoreManagementSection,
        statusFilterOverride: StoreProductStatusFilter? = null
    ) {
        _errorMessage.value = null
        if (statusFilterOverride != null) {
            onStatusFilterSelect(statusFilterOverride)
        }
        _currentSection.value = section
    }

    fun openAddProductForm() {
        _errorMessage.value = null
        _statusBannerMessage.value = null
        _selectedProductId.value = null
        _formState.value = ProductFormInput()
        _currentSection.value = StoreManagementSection.ADD_PRODUCT
    }

    fun openEditProductForm(product: Product) {
        _errorMessage.value = null
        _statusBannerMessage.value = null
        _selectedProductId.value = product.id
        _formState.value = ProductFormInput.fromProduct(product)
        _currentSection.value = StoreManagementSection.EDIT_PRODUCT
    }

    fun openProductDetails(productId: Int) {
        _errorMessage.value = null
        _selectedProductId.value = productId
        _currentSection.value = StoreManagementSection.PRODUCT_DETAILS
    }

    fun openStockAdjustmentDialog(product: Product) {
        _errorMessage.value = null
        _productForStockDialog.value = product
    }

    fun dismissStockAdjustmentDialog() {
        _productForStockDialog.value = null
    }

    fun requestArchiveConfirmation(product: Product) {
        _errorMessage.value = null
        _productPendingArchive.value = product
    }

    fun dismissArchiveConfirmation() {
        _productPendingArchive.value = null
    }

    fun confirmArchiveOrRestore(actor: AuthenticatedUser?): Boolean {
        val target = _productPendingArchive.value ?: return false
        _productPendingArchive.value = null
        return toggleProductArchiveStatus(actor, target)
    }

    fun updateFormState(transform: (ProductFormInput) -> ProductFormInput) {
        _errorMessage.value = null
        _formState.value = transform(_formState.value)
    }

    fun clearFeedbackMessages() {
        _errorMessage.value = null
        _statusBannerMessage.value = null
    }

    private fun verifyAuthorizedUser(user: AuthenticatedUser?): Boolean {
        val decision = AccessControlPolicy.checkAccess(
            user = user,
            requiresAuth = true,
            requiresStoreManagement = true
        )
        if (decision is AccessControlDecision.Denied) {
            _errorMessage.value = "غير مصرح: هذه العملية متاحة لمالك المتجر أو مدير المتجر فقط."
            return false
        }
        return true
    }

    fun submitProductForm(
        actor: AuthenticatedUser?,
        onSuccess: (Product) -> Unit = {}
    ): Boolean {
        if (!verifyAuthorizedUser(actor)) return false

        val currentForm = _formState.value
        val validation = ProductFormValidator.validate(currentForm)
        if (!validation.isValid) {
            _errorMessage.value = validation.errorArabic
            return false
        }

        val isEditing = currentForm.id != null
        val existingProduct = currentForm.id?.let { id -> _allProducts.value.find { it.id == id } }
        val targetId = currentForm.id ?: ((_allProducts.value.maxOfOrNull { it.id } ?: 10) + 1)
        val cleanImageKey = currentForm.imageUrl.trim().ifBlank { "placeholder" }
        val nowMillis = System.currentTimeMillis()

        val savedProduct = Product(
            id = targetId,
            name = currentForm.name.trim(),
            nameEn = currentForm.nameEn.trim(),
            category = currentForm.category.trim(),
            priceHalalas = validation.parsedPriceHalalas,
            description = currentForm.description.trim(),
            imageUrl = cleanImageKey,
            unit = currentForm.unit.trim(),
            stockQuantity = validation.parsedStockQuantity,
            lowStockThreshold = validation.parsedLowStockThreshold,
            expiryDate = validation.normalizedExpiryDate,
            isActive = if (currentForm.isArchived) false else currentForm.isActive,
            isArchived = currentForm.isArchived,
            updatedAtMillis = nowMillis
        )

        viewModelScope.launch {
            repository.saveProduct(savedProduct)
            if (isEditing && existingProduct != null && existingProduct.stockQuantity != savedProduct.stockQuantity && actor != null) {
                repository.applyValidatedStockAdjustment(
                    productId = savedProduct.id,
                    newStockQuantity = savedProduct.stockQuantity,
                    reason = "تحديث الكمية من نموذج تعديل المنتج",
                    actorId = actor.id,
                    actorName = actor.fullName,
                    actorRole = actor.role.name,
                    timestampMillis = nowMillis
                )
            }
            _selectedProductId.value = savedProduct.id
            _errorMessage.value = null
            _statusBannerMessage.value = if (isEditing) {
                "تم حفظ تعديلات المنتج «${savedProduct.name}» بنجاح."
            } else {
                "تمت إضافة المنتج «${savedProduct.name}» إلى المتجر بنجاح."
            }
            _currentSection.value = StoreManagementSection.PRODUCT_DETAILS
            onSuccess(savedProduct)
        }
        return true
    }

    fun toggleProductActiveStatus(actor: AuthenticatedUser?, product: Product): Boolean {
        if (!verifyAuthorizedUser(actor)) return false
        val nextActive = !product.isActive
        viewModelScope.launch {
            val updated = repository.setProductActiveStatus(product, nextActive)
            _statusBannerMessage.value = if (updated.isActive) {
                "تم تفعيل المنتج «${updated.name}» وإظهاره في متجر العملاء."
            } else {
                "تم إيقاف تفعيل المنتج «${updated.name}» وإخفاؤه عن متجر العملاء."
            }
        }
        return true
    }

    fun toggleProductArchiveStatus(actor: AuthenticatedUser?, product: Product): Boolean {
        if (!verifyAuthorizedUser(actor)) return false
        val nextArchived = !product.isArchived
        viewModelScope.launch {
            val updated = repository.setProductArchivedStatus(product, nextArchived)
            _statusBannerMessage.value = if (updated.isArchived) {
                "تمت أرشفة المنتج «${updated.name}» وإخفاؤه عن واجهة التسوق."
            } else {
                "تمت استعادة المنتج «${updated.name}» من الأرشيف وتفعيله."
            }
        }
        return true
    }

    /**
     * Adjusts a product's stock by [delta] after verifying authorization and ensuring
     * the resulting quantity is non-negative. Records a [StockAdjustment] entry.
     */
    fun adjustProductStock(
        actor: AuthenticatedUser?,
        product: Product,
        delta: Int,
        reason: String = "تعديل تشغيلي سريع"
    ): Boolean {
        if (!verifyAuthorizedUser(actor)) return false
        val authedUser = actor ?: return false

        val latestProduct = _allProducts.value.find { it.id == product.id } ?: product
        val validation = StockAdjustmentValidator.validateDeltaAdjustment(
            currentQuantity = latestProduct.stockQuantity,
            deltaInput = delta.toString(),
            reasonInput = reason
        )
        if (!validation.isValid) {
            _errorMessage.value = validation.errorArabic
            return false
        }

        viewModelScope.launch {
            when (
                val outcome = repository.applyValidatedStockAdjustment(
                    productId = latestProduct.id,
                    quantityDelta = delta,
                    expectedPreviousQuantity = latestProduct.stockQuantity,
                    reason = validation.cleanReason,
                    actorId = authedUser.id,
                    actorName = authedUser.fullName,
                    actorRole = authedUser.role.name
                )
            ) {
                is StockAdjustmentOutcome.Success -> {
                    _errorMessage.value = null
                    _statusBannerMessage.value =
                        "تم تحديث مخزون «${outcome.updatedProduct.name}» إلى ${outcome.updatedProduct.stockQuantity} (${outcome.updatedProduct.unit})."
                }
                is StockAdjustmentOutcome.Failure -> {
                    _errorMessage.value = outcome.errorArabic
                }
            }
        }
        return true
    }

    /**
     * Validates and applies a target stock quantity (supporting Arabic and Latin numerals)
     * with an explicit adjustment reason and optional stale-quantity check.
     */
    fun submitStockAdjustmentWithReason(
        actor: AuthenticatedUser?,
        product: Product,
        newQuantityInput: String,
        reasonInput: String,
        expectedPreviousQuantity: Int? = product.stockQuantity
    ): Boolean {
        if (!verifyAuthorizedUser(actor)) return false
        val authedUser = actor ?: return false

        val latestProduct = _allProducts.value.find { it.id == product.id } ?: product
        if (expectedPreviousQuantity != null && latestProduct.stockQuantity != expectedPreviousQuantity) {
            _errorMessage.value =
                "تعارض في تحديث المخزون: تغيرت الكمية الحالية إلى ${latestProduct.stockQuantity} قبل حفظ التعديل."
            return false
        }

        val validation = StockAdjustmentValidator.validateTargetQuantity(
            currentQuantity = latestProduct.stockQuantity,
            newQuantityInput = newQuantityInput,
            reasonInput = reasonInput
        )
        if (!validation.isValid) {
            _errorMessage.value = validation.errorArabic
            return false
        }

        viewModelScope.launch {
            when (
                val outcome = repository.applyValidatedStockAdjustment(
                    productId = latestProduct.id,
                    newStockQuantity = validation.parsedTargetQuantity,
                    expectedPreviousQuantity = expectedPreviousQuantity,
                    reason = validation.cleanReason,
                    actorId = authedUser.id,
                    actorName = authedUser.fullName,
                    actorRole = authedUser.role.name
                )
            ) {
                is StockAdjustmentOutcome.Success -> {
                    _errorMessage.value = null
                    _productForStockDialog.value = null
                    _statusBannerMessage.value =
                        "تم تسجيل تعديل المخزون لـ «${outcome.updatedProduct.name}»: ${outcome.adjustment.previousQuantity} ← ${outcome.adjustment.newQuantity} (${outcome.adjustment.reason})."
                }
                is StockAdjustmentOutcome.Failure -> {
                    _errorMessage.value = outcome.errorArabic
                }
            }
        }
        return true
    }
}

class StoreManagementViewModelFactory(
    private val repository: ProductRepository,
    private val orderRepository: OrderRepository? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StoreManagementViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StoreManagementViewModel(
                repository = repository,
                orderRepository = orderRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
