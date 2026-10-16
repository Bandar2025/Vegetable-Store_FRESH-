package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.local.AppDatabase
import com.example.data.repository.CartRepository
import com.example.data.repository.ProductRepository

object Injection {
    private var database: AppDatabase? = null

    private fun getDatabase(context: Context): AppDatabase {
        return database ?: synchronized(this) {
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java, "fresh-database"
            ).build().also { database = it }
        }
    }

    fun provideProductRepository(context: Context): ProductRepository {
        return ProductRepository(getDatabase(context).productDao())
    }

    fun provideCartRepository(context: Context): CartRepository {
        return CartRepository(getDatabase(context).cartDao())
    }
}
