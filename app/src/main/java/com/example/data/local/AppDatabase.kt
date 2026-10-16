package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.data.model.Product
import com.example.data.model.CartItem
import com.example.data.model.Order
import com.example.data.model.OrderItem

@Database(entities = [Product::class, CartItem::class, Order::class, OrderItem::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun cartDao(): CartDao
    abstract fun orderDao(): OrderDao
    abstract fun orderItemDao(): OrderItemDao
}
