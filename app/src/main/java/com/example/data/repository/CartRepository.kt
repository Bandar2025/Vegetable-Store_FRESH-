package com.example.data.repository

import com.example.data.local.dao.CartDao
import com.example.data.local.model.CartItem
import kotlinx.coroutines.flow.Flow

class CartRepository(
    private val cartDao: CartDao,
    private val orderRepository: OrderRepository? = null
) {
    fun getCartItems(): Flow<List<CartItem>> = cartDao.getCartItems()
    suspend fun getCartItemsOnce(): List<CartItem> = cartDao.getCartItemsOnce()
    suspend fun addToCart(cartItem: CartItem) = cartDao.insert(cartItem)
    suspend fun addToCartFractional(productId: Int, quantityUnits: Double) {
        cartDao.insert(CartItem.fromFractionalUnits(productId, quantityUnits))
    }
    suspend fun addToCartMillis(productId: Int, quantityMillis: Int) {
        cartDao.insert(CartItem.fromMillis(productId, quantityMillis))
    }
    suspend fun removeFromCart(productId: Int) = cartDao.delete(productId)
    suspend fun removePurchasedItems(productIds: List<Int>) = cartDao.deleteByProductIds(productIds)
    suspend fun clearCart() = cartDao.clearCart()
    fun getOrderRepository(): OrderRepository? = orderRepository
}
