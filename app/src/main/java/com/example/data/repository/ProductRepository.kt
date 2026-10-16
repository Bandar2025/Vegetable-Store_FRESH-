package com.example.data.repository

import com.example.data.local.ProductDao
import com.example.data.model.Product
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ProductRepository(private val productDao: ProductDao) {

    // For demo purposes, we return a hardcoded list of products,
    // combined with any products in the DB (for expansion later)
    fun getAllProducts(): Flow<List<Product>> {
        return flowOf(
            listOf(
                Product(1, "تفاح أحمر", "فواكه", 500, "تفاح طازج ومقرمش", "apple", "كيلوجرام"),
                Product(2, "موز", "فواكه", 300, "موز ناضج", "banana", "كيلوجرام"),
                Product(3, "طماطم", "خضروات", 400, "طماطم حمراء طازجة", "tomato", "كيلوجرام"),
                Product(4, "خس", "خضروات ورقية", 250, "خس طازج", "lettuce", "حبة"),
                Product(5, "جزر", "خضروات", 350, "جزر طازج", "carrot", "كيلوجرام")
            )
        )
    }
}
