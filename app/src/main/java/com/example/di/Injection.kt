package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.auth.LocalAuthRepository
import com.example.data.local.AppDatabase
import com.example.data.repository.CartRepository
import com.example.data.repository.ProductRepository
import com.example.data.api.FreshApiService
import com.example.data.api.FreshRetrofitClient

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
        return ProductRepository(getDatabase(context).productDao(), provideApiService())
    }

    fun provideCartRepository(context: Context): CartRepository {
        return CartRepository(getDatabase(context).cartDao())
    }

    fun provideAuthRepository(context: Context): LocalAuthRepository {
        return LocalAuthRepository.getInstance(context)
    }

    fun provideApiService(): FreshApiService {
        return FreshRetrofitClient.service
    }
}
