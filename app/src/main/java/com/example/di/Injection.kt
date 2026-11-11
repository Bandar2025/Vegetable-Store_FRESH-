package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.api.FreshApiService
import com.example.data.api.FreshRetrofitClient
import com.example.data.auth.LocalAuthRepository
import com.example.data.auth.SecureTokenStorage
import com.example.data.local.AppDatabase
import com.example.data.repository.CartRepository
import com.example.data.repository.OrderRepository
import com.example.data.repository.ProductRepository
import kotlinx.coroutines.sync.Mutex

object Injection {
    @Volatile
    private var database: AppDatabase? = null

    @Volatile
    private var apiService: FreshApiService? = null

    @Volatile
    private var productRepository: ProductRepository? = null

    @Volatile
    private var orderRepository: OrderRepository? = null

    @Volatile
    private var cartRepository: CartRepository? = null

    @Volatile
    private var authRepository: LocalAuthRepository? = null

    /**
     * Single shared synchronization lock across [ProductRepository] and [OrderRepository]
     * so concurrent stock adjustments, network catalog refreshes, local checkouts, and
     * order cancellations never race against one another.
     */
    private val sharedInventoryMutex = Mutex()

    internal fun provideSharedInventoryMutex(): Mutex = sharedInventoryMutex

    fun provideDatabase(context: Context): AppDatabase {
        return database ?: synchronized(this) {
            database ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "fresh_db"
            )
                .addMigrations(
                    AppDatabase.MIGRATION_1_2,
                    AppDatabase.MIGRATION_2_3,
                    AppDatabase.MIGRATION_3_4
                )
                .build()
                .also { database = it }
        }
    }

    fun provideApiService(context: Context? = null): FreshApiService {
        return apiService ?: synchronized(this) {
            apiService ?: run {
                if (context != null) {
                    val appContext = context.applicationContext
                    val canonicalPrefs = appContext.getSharedPreferences(
                        LocalAuthRepository.PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                    val tokenStorage = SecureTokenStorage(canonicalPrefs)
                    val (authenticatedService, _) = FreshRetrofitClient.createAuthenticatedService(
                        tokenStorage = tokenStorage,
                        onSessionInvalidated = {
                            authRepository?.handleRemoteSessionInvalidated()
                        }
                    )
                    authenticatedService.also { apiService = it }
                } else {
                    FreshRetrofitClient.service.also { apiService = it }
                }
            }
        }
    }

    fun provideProductRepository(context: Context): ProductRepository {
        return productRepository ?: synchronized(this) {
            productRepository ?: run {
                val db = provideDatabase(context)
                ProductRepository(
                    productDao = db.productDao(),
                    apiService = provideApiService(context),
                    stockAdjustmentDao = db.stockAdjustmentDao(),
                    stockMutex = sharedInventoryMutex
                ).also { productRepository = it }
            }
        }
    }

    fun provideOrderRepository(context: Context): OrderRepository {
        return orderRepository ?: synchronized(this) {
            orderRepository ?: run {
                val db = provideDatabase(context)
                OrderRepository(
                    database = db,
                    checkoutMutex = sharedInventoryMutex
                ).also { orderRepository = it }
            }
        }
    }

    fun provideCartRepository(context: Context): CartRepository {
        return cartRepository ?: synchronized(this) {
            cartRepository ?: run {
                val db = provideDatabase(context)
                CartRepository(
                    cartDao = db.cartDao(),
                    orderRepository = provideOrderRepository(context)
                ).also { cartRepository = it }
            }
        }
    }

    fun provideAuthRepository(context: Context): LocalAuthRepository {
        return authRepository ?: synchronized(this) {
            authRepository ?: LocalAuthRepository.getInstance(context).also { authRepository = it }
        }
    }

    internal fun resetForTesting(closeDatabase: Boolean = false) {
        synchronized(this) {
            if (closeDatabase) {
                database?.close()
            }
            database = null
            apiService = null
            productRepository = null
            orderRepository = null
            cartRepository = null
            authRepository = null
            LocalAuthRepository.resetInstanceForTesting()
        }
    }
}

