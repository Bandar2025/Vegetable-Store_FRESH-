package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.model.CartItem
import kotlinx.coroutines.flow.Flow

@Dao
interface CartDao {
    @Query("SELECT * FROM cart_items ORDER BY productId ASC")
    fun getCartItems(): Flow<List<CartItem>>

    @Query("SELECT * FROM cart_items ORDER BY productId ASC")
    suspend fun getCartItemsOnce(): List<CartItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cartItem: CartItem)

    @Query("DELETE FROM cart_items WHERE productId = :productId")
    suspend fun delete(productId: Int)

    @Query("DELETE FROM cart_items WHERE productId IN (:productIds)")
    suspend fun deleteByProductIds(productIds: List<Int>)

    @Query("DELETE FROM cart_items")
    suspend fun clearCart()
}
