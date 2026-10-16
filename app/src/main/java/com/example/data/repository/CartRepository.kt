package com.example.data.repository

import com.example.data.local.CartDao
import com.example.data.model.CartItem
import kotlinx.coroutines.flow.Flow

class CartRepository(private val cartDao: CartDao) {
    fun getCartItems(): Flow<List<CartItem>> = cartDao.getCartItems()
    suspend fun addToCart(cartItem: CartItem) = cartDao.insert(cartItem)
    suspend fun removeFromCart(productId: Int) = cartDao.delete(productId)
    suspend fun clearCart() = cartDao.clearCart()
}
