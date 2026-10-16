package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.repository.CartRepository
import com.example.data.repository.ProductRepository
import com.example.data.model.CartItem
import kotlinx.coroutines.flow.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class CartViewModel(
    private val cartRepository: CartRepository,
    private val productRepository: ProductRepository
) : ViewModel() {

    // Combining data flows to get cart with product details
    val cartItems = combine(
        cartRepository.getCartItems(),
        productRepository.getAllProducts()
    ) { cartItems, products ->
        cartItems.mapNotNull { cartItem ->
            val product = products.find { it.id == cartItem.productId }
            if (product != null) Pair(product, cartItem.quantity) else null
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val subtotal = cartItems.map { items ->
        items.sumOf { (product, quantity) -> product.priceHalalas * quantity }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    val deliveryFee = 1500L // 15 SAR
    val total = subtotal.map { it + deliveryFee }.stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    fun addToCart(productId: Int, quantity: Int) {
        viewModelScope.launch {
            cartRepository.addToCart(CartItem(productId, quantity))
        }
    }

    fun removeFromCart(productId: Int) {
        viewModelScope.launch {
            cartRepository.removeFromCart(productId)
        }
    }
}
