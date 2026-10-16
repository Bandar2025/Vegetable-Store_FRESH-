package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.repository.ProductRepository
import com.example.data.model.Product
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: ProductRepository) : ViewModel() {

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    init {
        loadProducts()
    }

    private fun loadProducts() {
        viewModelScope.launch {
            repository.getAllProducts().collectLatest {
                _products.value = it
            }
        }
    }
}
