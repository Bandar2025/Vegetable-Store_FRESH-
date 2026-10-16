package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.data.model.Order
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders ORDER BY timestamp DESC")
    fun getOrders(): Flow<List<Order>>

    @Insert
    suspend fun insert(order: Order)
}
