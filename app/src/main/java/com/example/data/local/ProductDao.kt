package com.example.data.local

import androidx.room.Dao
import androidx.room.Query
import com.example.data.model.Product
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products")
    fun getAllProducts(): Flow<List<Product>>
}
