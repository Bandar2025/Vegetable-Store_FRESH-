package com.example.data.api.model

import kotlinx.serialization.Serializable

@Serializable
data class CategoryDto(val id: Int, val name: String)

@Serializable
data class ProductDto(
    val id: Int,
    val name: String,
    val category: CategoryDto,
    val priceHalalas: Long,
    val description: String,
    val imageUrl: String,
    val unit: String
)

@Serializable
data class OrderItemRequest(val productId: Int, val quantity: Int)

@Serializable
data class OrderRequest(val items: List<OrderItemRequest>, val addressId: Int)

@Serializable
data class OrderResponse(val id: Int, val status: String, val totalHalalas: Long, val timestamp: String)

@Serializable
data class ApiErrorDto(val code: String, val message: String)
