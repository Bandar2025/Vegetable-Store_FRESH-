package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "products")
data class Product(
    @PrimaryKey val id: Int,
    val name: String,
    val category: String,
    val priceHalalas: Long,
    val description: String,
    val imageUrl: String,
    val unit: String
)
