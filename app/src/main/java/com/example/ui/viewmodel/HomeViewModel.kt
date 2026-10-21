package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.repository.ProductRepository
import com.example.data.local.model.Product
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

enum class ProductSortOrder(val title: String) {
    POPULARITY("الأكثر شعبية"),
    PRICE_LOW_TO_HIGH("السعر: الأقل أولاً"),
    PRICE_HIGH_TO_LOW("السعر: الأعلى أولاً")
}

class HomeViewModel(private val repository: ProductRepository) : ViewModel() {

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow("الكل")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<Int>>(setOf(1, 3))
    val favoriteIds: StateFlow<Set<Int>> = _favoriteIds.asStateFlow()

    private val _priceRange = MutableStateFlow(0f..30f)
    val priceRange: StateFlow<ClosedFloatingPointRange<Float>> = _priceRange.asStateFlow()

    private val _sortOrder = MutableStateFlow(ProductSortOrder.POPULARITY)
    val sortOrder: StateFlow<ProductSortOrder> = _sortOrder.asStateFlow()

    init {
        loadProducts()
    }

    private fun loadProducts() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.ensureInitialData()
            repository.getAllProducts().collectLatest { items ->
                // Only display active, non-archived products in the customer storefront
                val activeStorefrontItems = items.filter { it.isVisibleInStorefront }
                _products.value = activeStorefrontItems
                // Keep price range ceiling wide enough for newly added higher-priced products
                val maxPriceSar = activeStorefrontItems.maxOfOrNull { it.priceSar.toFloat() } ?: 30f
                val targetCeiling = maxOf(30f, maxPriceSar)
                if (_priceRange.value.start == 0f && _priceRange.value.endInclusive == 30f && targetCeiling > 30f) {
                    _priceRange.value = 0f..targetCeiling
                }
                _isLoading.value = false
            }
        }
    }

    fun refreshProducts() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.refreshProducts()
            _isLoading.value = false
        }
    }

    fun setLoadingState(loading: Boolean) {
        _isLoading.value = loading
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onCategorySelect(category: String) {
        _selectedCategory.value = category
    }

    fun onPriceRangeChange(range: ClosedFloatingPointRange<Float>) {
        _priceRange.value = range
    }

    fun resetPriceRange() {
        val maxPriceSar = _products.value.maxOfOrNull { it.priceSar.toFloat() } ?: 30f
        _priceRange.value = 0f..maxOf(30f, maxPriceSar)
    }

    fun onSortOrderChange(order: ProductSortOrder) {
        _sortOrder.value = order
    }

    fun toggleFavorite(productId: Int) {
        val current = _favoriteIds.value
        _favoriteIds.value = if (current.contains(productId)) {
            current - productId
        } else {
            current + productId
        }
    }
}
