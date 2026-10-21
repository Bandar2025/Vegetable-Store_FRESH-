package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.model.Product
import com.example.data.local.model.ProductExpiryStatus
import com.example.data.local.model.StockAdjustment
import com.example.data.repository.ProductRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * ViewModel providing real-time reactive Flow-based filtering of inventory products by
 * Arabic name ([Product.name]) or English name ([Product.nameEn]), category, and stock/expiry status.
 */
open class InventoryViewModel(
    protected val repository: ProductRepository,
    referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO,
    initialExpiryWarningDays: Int = Product.DEFAULT_EXPIRY_WARNING_DAYS
) : ViewModel() {

    protected val _referenceDateIso = MutableStateFlow(referenceDateIso)
    val referenceDateIsoFlow: StateFlow<String> = _referenceDateIso.asStateFlow()

    val referenceDateIso: String
        get() = _referenceDateIso.value

    protected val _expiryWarningDays = MutableStateFlow(initialExpiryWarningDays.coerceAtLeast(0))
    val expiryWarningDays: StateFlow<Int> = _expiryWarningDays.asStateFlow()

    protected val _allProducts = MutableStateFlow<List<Product>>(emptyList())
    val allProducts: StateFlow<List<Product>> = _allProducts.asStateFlow()

    protected val _stockAdjustments = MutableStateFlow<List<StockAdjustment>>(emptyList())
    val stockAdjustments: StateFlow<List<StockAdjustment>> = _stockAdjustments.asStateFlow()

    protected val _metrics = MutableStateFlow(StoreDashboardMetrics())
    val metrics: StateFlow<StoreDashboardMetrics> = _metrics.asStateFlow()

    protected val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    protected val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    protected val _selectedCategory = MutableStateFlow("الكل")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    protected val _statusFilter = MutableStateFlow(StoreProductStatusFilter.ALL)
    val statusFilter: StateFlow<StoreProductStatusFilter> = _statusFilter.asStateFlow()

    protected val _filteredProducts = MutableStateFlow<List<Product>>(emptyList())

    /**
     * Real-time reactive [StateFlow] of products filtered by [searchQuery] (Arabic or English name),
     * [selectedCategory], and [statusFilter].
     */
    val filteredProducts: StateFlow<List<Product>> = _filteredProducts.asStateFlow()

    /**
     * Cold/reactive [Flow] combining product data, search query, category, status filter, and expiry configuration.
     */
    val filteredProductsFlow: Flow<List<Product>> = combine(
        _allProducts,
        _searchQuery,
        _selectedCategory,
        _statusFilter,
        combine(_referenceDateIso, _expiryWarningDays) { refDate, warningDays -> refDate to warningDays }
    ) { products, query, category, status, (refDate, warningDays) ->
        filterInventoryProducts(
            products = products,
            rawQuery = query,
            category = category,
            status = status,
            referenceDateIso = refDate,
            expiryWarningDays = warningDays
        )
    }

    init {
        observeInventoryProducts()
        observeInventoryAdjustments()
        observeFilteredProductsFlow()
    }

    private fun observeInventoryProducts() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.ensureInitialData()
            repository.getAllProducts().collectLatest { items ->
                val sorted = items.sortedBy { it.id }
                _allProducts.value = sorted
                recalculateMetricsAndFilter()
                _isLoading.value = false
            }
        }
    }

    private fun observeInventoryAdjustments() {
        viewModelScope.launch {
            repository.getAllStockAdjustments().collectLatest { adjustments ->
                _stockAdjustments.value = adjustments
            }
        }
    }

    private fun observeFilteredProductsFlow() {
        viewModelScope.launch {
            filteredProductsFlow.collectLatest { filtered ->
                _filteredProducts.value = filtered
            }
        }
    }

    protected fun recalculateMetricsAndFilter() {
        _metrics.value = StoreDashboardMetrics.fromProducts(
            products = _allProducts.value,
            referenceDateIso = _referenceDateIso.value,
            expiryWarningDays = _expiryWarningDays.value
        )
        _filteredProducts.value = filterInventoryProducts(
            products = _allProducts.value,
            rawQuery = _searchQuery.value,
            category = _selectedCategory.value,
            status = _statusFilter.value,
            referenceDateIso = _referenceDateIso.value,
            expiryWarningDays = _expiryWarningDays.value
        )
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        recalculateMetricsAndFilter()
    }

    fun clearSearchQuery() {
        _searchQuery.value = ""
        recalculateMetricsAndFilter()
    }

    fun onCategorySelect(category: String) {
        _selectedCategory.value = category
        recalculateMetricsAndFilter()
    }

    fun onStatusFilterSelect(filter: StoreProductStatusFilter) {
        _statusFilter.value = filter
        recalculateMetricsAndFilter()
    }

    fun setExpiryWarningDays(days: Int) {
        val clamped = days.coerceIn(0, 365)
        _expiryWarningDays.value = clamped
        recalculateMetricsAndFilter()
    }

    fun setReferenceDateIso(dateIso: String) {
        if (Product.isValidIsoDate(dateIso)) {
            _referenceDateIso.value = dateIso.trim()
            recalculateMetricsAndFilter()
        }
    }

    fun evaluateProductExpiry(product: Product): ProductExpiryStatus {
        return product.evaluateExpiryStatus(
            referenceDateIso = _referenceDateIso.value,
            warningWindowDays = _expiryWarningDays.value
        )
    }

    fun getDaysUntilExpiry(product: Product): Long? {
        return product.daysUntilExpiry(_referenceDateIso.value)
    }

    fun getFilteredProducts(): List<Product> = _filteredProducts.value

    companion object {
        /**
         * Normalizes Arabic text (unifying Alef variants `أ/إ/آ -> ا` and Teh Marbuta `ة -> ه`)
         * and lowercases Latin characters for resilient real-time bilingual search.
         */
        fun normalizeSearchText(input: String): String {
            val trimmed = input.trim().lowercase()
            val sb = StringBuilder(trimmed.length)
            for (ch in trimmed) {
                when (ch) {
                    'أ', 'إ', 'آ' -> sb.append('ا')
                    'ة' -> sb.append('ه')
                    'ى' -> sb.append('ي')
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun matchesBilingualSearchQuery(product: Product, rawQuery: String): Boolean {
            val cleanQuery = rawQuery.trim()
            if (cleanQuery.isEmpty()) return true

            val normalizedQuery = normalizeSearchText(cleanQuery)
            val normalizedArabicName = normalizeSearchText(product.name)
            val normalizedEnglishName = normalizeSearchText(product.nameEn)
            val normalizedCategory = normalizeSearchText(product.category)
            val normalizedDescription = normalizeSearchText(product.description)

            return normalizedArabicName.contains(normalizedQuery) ||
                normalizedEnglishName.contains(normalizedQuery) ||
                product.name.contains(cleanQuery, ignoreCase = true) ||
                product.nameEn.contains(cleanQuery, ignoreCase = true) ||
                normalizedCategory.contains(normalizedQuery) ||
                normalizedDescription.contains(normalizedQuery)
        }

        fun filterInventoryProducts(
            products: List<Product>,
            rawQuery: String,
            category: String,
            status: StoreProductStatusFilter,
            referenceDateIso: String,
            expiryWarningDays: Int
        ): List<Product> {
            return products.filter { product ->
                val matchesQuery = matchesBilingualSearchQuery(product, rawQuery)
                val matchesCategory = category == "الكل" || product.category == category
                val matchesStatus = when (status) {
                    StoreProductStatusFilter.ALL -> !product.isArchived
                    StoreProductStatusFilter.ACTIVE_ONLY -> product.isActive && !product.isArchived
                    StoreProductStatusFilter.INACTIVE_ONLY -> !product.isActive && !product.isArchived
                    StoreProductStatusFilter.ARCHIVED_ONLY -> product.isArchived
                    StoreProductStatusFilter.LOW_STOCK -> product.isLowStock
                    StoreProductStatusFilter.OUT_OF_STOCK -> product.isOutOfStock
                    StoreProductStatusFilter.EXPIRING_SOON -> product.isExpiringSoon(referenceDateIso, expiryWarningDays)
                    StoreProductStatusFilter.EXPIRED -> product.isExpired(referenceDateIso)
                }
                matchesQuery && matchesCategory && matchesStatus
            }
        }
    }
}

class InventoryViewModelFactory(
    private val repository: ProductRepository,
    private val referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO,
    private val initialExpiryWarningDays: Int = Product.DEFAULT_EXPIRY_WARNING_DAYS
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(InventoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return InventoryViewModel(repository, referenceDateIso, initialExpiryWarningDays) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
