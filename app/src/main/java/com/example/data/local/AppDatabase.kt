package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.data.local.model.Product
import com.example.data.local.model.CartItem
import com.example.data.local.model.Order
import com.example.data.local.model.OrderItem
import com.example.data.local.dao.ProductDao
import com.example.data.local.dao.CartDao
import com.example.data.local.dao.OrderDao
import com.example.data.local.dao.OrderItemDao

@Database(entities = [Product::class, CartItem::class, Order::class, OrderItem::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun cartDao(): CartDao
    abstract fun orderDao(): OrderDao
    abstract fun orderItemDao(): OrderItemDao
}
