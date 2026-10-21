package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.model.Order
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders ORDER BY timestamp DESC, id DESC")
    fun getOrders(): Flow<List<Order>>

    @Query("SELECT * FROM orders ORDER BY timestamp DESC, id DESC")
    suspend fun getAllOrdersOnce(): List<Order>

    @Query("SELECT * FROM orders WHERE customerId = :customerId ORDER BY timestamp DESC, id DESC")
    fun getOrdersForCustomer(customerId: String): Flow<List<Order>>

    @Query("SELECT * FROM orders WHERE customerId = :customerId ORDER BY timestamp DESC, id DESC")
    suspend fun getOrdersForCustomerOnce(customerId: String): List<Order>

    @Query("SELECT * FROM orders WHERE id = :orderId LIMIT 1")
    suspend fun getOrderById(orderId: Int): Order?

    @Query("SELECT * FROM orders WHERE idempotencyKey = :idempotencyKey AND idempotencyKey != '' LIMIT 1")
    suspend fun getOrderByIdempotencyKey(idempotencyKey: String): Order?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(order: Order): Long

    @androidx.room.Update
    suspend fun update(order: Order)
}
