package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.api.FreshApiService
import com.example.data.auth.LocalAuthRepository
import com.example.data.local.AppDatabase
import com.example.data.repository.CartRepository
import com.example.data.repository.OrderRepository
import com.example.data.repository.ProductRepository
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object Injection {
    private var database: AppDatabase? = null
    private var apiService: FreshApiService? = null
    private var authRepository: LocalAuthRepository? = null
    private var orderRepository: OrderRepository? = null

    private val networkJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun provideDatabase(context: Context): AppDatabase {
        return database ?: synchronized(this) {
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "fresh_db"
            )
                .addMigrations(
                    AppDatabase.MIGRATION_1_2,
                    AppDatabase.MIGRATION_2_3,
                    AppDatabase.MIGRATION_3_4
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { database = it }
        }
    }

    fun provideApiService(): FreshApiService {
        return apiService ?: synchronized(this) {
            Retrofit.Builder()
                .baseUrl("https://api.example.com/")
                .addConverterFactory(networkJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(FreshApiService::class.java)
                .also { apiService = it }
        }
    }

    fun provideProductRepository(context: Context): ProductRepository {
        val db = provideDatabase(context)
        return ProductRepository(
            productDao = db.productDao(),
            apiService = provideApiService(),
            stockAdjustmentDao = db.stockAdjustmentDao()
        )
    }

    fun provideOrderRepository(context: Context): OrderRepository {
        return orderRepository ?: synchronized(this) {
            val db = provideDatabase(context)
            OrderRepository(database = db).also { orderRepository = it }
        }
    }

    fun provideCartRepository(context: Context): CartRepository {
        val db = provideDatabase(context)
        return CartRepository(
            cartDao = db.cartDao(),
            orderRepository = provideOrderRepository(context)
        )
    }

    fun provideAuthRepository(context: Context): LocalAuthRepository {
        return authRepository ?: synchronized(this) {
            val prefs = context.applicationContext.getSharedPreferences(
                "fresh_local_auth_prefs",
                Context.MODE_PRIVATE
            )
            LocalAuthRepository(prefs).also { authRepository = it }
        }
    }
}
