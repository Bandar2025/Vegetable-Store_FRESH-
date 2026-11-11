package com.example

import android.content.SharedPreferences
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.api.FreshApiService
import com.example.data.api.FreshRetrofitClient
import com.example.data.api.model.CategoryDto
import com.example.data.api.model.OrderRequest
import com.example.data.api.model.OrderResponse
import com.example.data.api.model.ProductDto
import com.example.data.auth.AuthOperationResult
import com.example.data.auth.LocalAuthRepository
import com.example.data.local.AppDatabase
import com.example.data.local.dao.ProductDao
import com.example.data.local.dao.StockAdjustmentDao
import com.example.data.local.model.Product
import com.example.data.local.model.StockAdjustment
import com.example.data.repository.ProductRepository
import com.example.data.repository.StockAdjustmentOutcome
import com.example.di.Injection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Automated verification suite for Microtask 1A:
 * 1. Non-destructive product refresh (preserves local stock, fractional milli-units, lowStockThreshold, expiryDate, isActive, isArchived, nameEn).
 * 2. Shared synchronization Mutex across repositories and concurrent stock/refresh serialization.
 * 3. Unified Retrofit configuration without fake or unpublished production URLs.
 * 4. Unified LocalAuthRepository preferences and non-destructive migration from legacy DI preferences.
 * 5. Room schema migrations (1->2, 2->3, 3->4) preserving all existing user, order, cart, product, and stock movement data without dropping tables.
 */
class MicroTask1ADataIntegrityAndDiTest {

    private class InMemoryProductDao(initial: List<Product> = emptyList()) : ProductDao {
        private val state = MutableStateFlow(initial)
        override fun getAllProducts(): Flow<List<Product>> = state
        override suspend fun getAllProductsOnce(): List<Product> = state.value
        override suspend fun getProductById(productId: Int): Product? =
            state.value.find { it.id == productId }

        override suspend fun insert(product: Product) {
            val current = state.value.toMutableList()
            val idx = current.indexOfFirst { it.id == product.id }
            if (idx >= 0) {
                current[idx] = product
            } else {
                current.add(product)
            }
            state.value = current
        }

        override suspend fun insertAll(products: List<Product>) {
            val byId = state.value.associateBy { it.id }.toMutableMap()
            for (p in products) {
                byId[p.id] = p
            }
            state.value = byId.values.toList()
        }

        override suspend fun count(): Int = state.value.size
    }

    private class InMemoryStockAdjustmentDao : StockAdjustmentDao {
        private val state = MutableStateFlow<List<StockAdjustment>>(emptyList())
        private var nextId = 1L

        override suspend fun insertAdjustment(adjustment: StockAdjustment): Long {
            val assignedId = nextId++
            state.value = listOf(adjustment.copy(id = assignedId)) + state.value
            return assignedId
        }

        override fun getAllAdjustments(): Flow<List<StockAdjustment>> = state

        override fun getAdjustmentsForProduct(productId: Int): Flow<List<StockAdjustment>> =
            MutableStateFlow(state.value.filter { it.productId == productId })

        fun getAdjustmentsForProductOnce(productId: Int): List<StockAdjustment> =
            state.value.filter { it.productId == productId }

        override suspend fun countAdjustmentsForOrderAction(
            exactReason: String,
            reasonPrefix: String
        ): Int = state.value.count {
            it.reason == exactReason || it.reason.startsWith(reasonPrefix)
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = values?.toSet()
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) removals.add(key)
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearAll = true
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) data.clear()
                for (k in removals) data.remove(k)
                for ((k, v) in staged) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun refreshProducts_preservesLocalStockFractionalMillisExpiryThresholdsAndStatus() = runBlocking {
        // Seed local DB with customized product state (simulating store owner stock adjustment & custom expiry)
        val customizedApple = Product(
            id = 1,
            name = "تفاح أحمر قديم",
            nameEn = "Custom Gala Apple",
            category = "فواكه",
            priceHalalas = 650,
            description = "وصف قديم",
            imageUrl = "apple",
            unit = "كيلوجرام",
            stockQuantity = 13,
            stockSubUnitsMillis = 12_500, // 12.5 kg remaining after fractional checkout
            lowStockThreshold = 9,
            expiryDate = "2026-10-21",
            isActive = false,
            isArchived = false,
            updatedAtMillis = 1760691000000L
        )
        val productDao = InMemoryProductDao(listOf(customizedApple))
        val adjustmentDao = InMemoryStockAdjustmentDao()

        // Record a stock movement before refresh
        adjustmentDao.insertAdjustment(
            StockAdjustment(
                productId = 1,
                productName = customizedApple.name,
                previousQuantity = 20,
                newQuantity = 13,
                quantityDelta = -7,
                reason = "Order checkout: 1001",
                actorId = "cust_1",
                actorName = "عميل",
                actorRole = "CUSTOMER",
                timestampMillis = 1760691000000L,
                previousQuantityMillis = 20_000,
                newQuantityMillis = 12_500,
                quantityDeltaMillis = -7_500
            )
        )

        val remoteApi = object : FreshApiService {
            override suspend fun getCategories(): List<CategoryDto> = emptyList()
            override suspend fun getProducts(): List<ProductDto> = listOf(
                ProductDto(
                    id = 1,
                    name = "تفاح أحمر جالا محدث",
                    category = CategoryDto(1, "فواكه"),
                    priceHalalas = 725L,
                    description = "وصف محدث من الخادم",
                    imageUrl = "apple",
                    unit = "كيلوجرام"
                ),
                ProductDto(
                    id = 99,
                    name = "مانجو جازان طازج",
                    category = CategoryDto(1, "فواكه"),
                    priceHalalas = 1500L,
                    description = "مانجو طازج من مزارع جازان",
                    imageUrl = "orange",
                    unit = "كيلوجرام"
                )
            )
            override suspend fun getProduct(id: Int): ProductDto = throw NotImplementedError()
            override suspend fun createOrder(request: OrderRequest): OrderResponse = throw NotImplementedError()
        }

        val repo = ProductRepository(
            productDao = productDao,
            apiService = remoteApi,
            stockAdjustmentDao = adjustmentDao
        )

        repo.refreshProducts()

        val afterRefresh = productDao.getProductById(1)
        assertNotNull(afterRefresh)
        // Remote catalog fields are updated
        assertEquals("تفاح أحمر جالا محدث", afterRefresh!!.name)
        assertEquals(725, afterRefresh.priceHalalas)
        assertEquals("وصف محدث من الخادم", afterRefresh.description)

        // Local inventory, fractional milli-units, threshold, expiry date, active/archived status, and English name are strictly preserved!
        assertEquals(13, afterRefresh.stockQuantity)
        assertEquals(12_500, afterRefresh.stockSubUnitsMillis)
        assertEquals(12_500, afterRefresh.effectiveStockMillis)
        assertEquals(9, afterRefresh.lowStockThreshold)
        assertEquals("2026-10-21", afterRefresh.expiryDate)
        assertFalse(afterRefresh.isActive)
        assertFalse(afterRefresh.isArchived)
        assertEquals("Custom Gala Apple", afterRefresh.nameEn)

        // Newly discovered remote product (id = 99) is inserted cleanly
        val newMango = productDao.getProductById(99)
        assertNotNull(newMango)
        assertEquals("مانجو جازان طازج", newMango!!.name)
        assertEquals(1500, newMango.priceHalalas)

        // Existing stock adjustment audit trail is 100% preserved
        val adjustments = adjustmentDao.getAdjustmentsForProductOnce(1)
        assertEquals(1, adjustments.size)
        assertEquals(-7_500, adjustments.first().quantityDeltaMillis)
    }

    @Test
    fun sharedInventoryMutex_isUnifiedAndSerializesConcurrentStockAndRefreshOperations() = runBlocking {
        val sharedMutex: Mutex = Injection.provideSharedInventoryMutex()
        val productDao = InMemoryProductDao(ProductRepository.initialProducts)
        val adjustmentDao = InMemoryStockAdjustmentDao()

        val remoteApi = object : FreshApiService {
            override suspend fun getCategories(): List<CategoryDto> = emptyList()
            override suspend fun getProducts(): List<ProductDto> = listOf(
                ProductDto(
                    id = 1,
                    name = "تفاح أحمر جالا طازج",
                    category = CategoryDto(1, "فواكه"),
                    priceHalalas = 650L,
                    description = "تفاح طازج",
                    imageUrl = "apple",
                    unit = "كيلوجرام"
                )
            )
            override suspend fun getProduct(id: Int): ProductDto = throw NotImplementedError()
            override suspend fun createOrder(request: OrderRequest): OrderResponse = throw NotImplementedError()
        }

        val productRepo = ProductRepository(
            productDao = productDao,
            apiService = remoteApi,
            stockAdjustmentDao = adjustmentDao,
            stockMutex = sharedMutex
        )

        // Verify the exact same Mutex instance is wired
        assertSame(sharedMutex, productRepo.synchronizationMutex)

        // Execute 10 concurrent +1 stock adjustments alongside concurrent refreshProducts() calls
        val jobs = (1..10).flatMap { step ->
            listOf(
                async {
                    productRepo.applyValidatedStockAdjustment(
                        productId = 1,
                        quantityDelta = 1,
                        reason = "توريد متزامن $step",
                        actorId = "owner_1",
                        actorName = "مالك المتجر",
                        actorRole = "STORE_OWNER"
                    )
                },
                async {
                    productRepo.refreshProducts()
                }
            )
        }
        val results = jobs.awaitAll()
        val adjustmentSuccesses = results.filterIsInstance<StockAdjustmentOutcome.Success>()
        assertEquals(10, adjustmentSuccesses.size)

        // Initial stock of product 1 is 42; after 10 serialized +1 adjustments interleaved with refreshes, stock must be exactly 52!
        val finalApple = productDao.getProductById(1)!!
        assertEquals(52, finalApple.stockQuantity)
        assertEquals(52_000, finalApple.effectiveStockMillis)
        assertEquals(10, adjustmentDao.getAdjustmentsForProductOnce(1).size)
    }

    @Test
    fun retrofitAndDiConfiguration_usesUnifiedServiceWithoutFakeExampleComUrl() {
        Injection.resetForTesting()
        val service1 = Injection.provideApiService()
        val service2 = Injection.provideApiService()

        assertSame(service1, service2)
        assertSame(FreshRetrofitClient.service, service1)
        assertTrue(FreshRetrofitClient.DEFAULT_LOCAL_DEV_BASE_URL.startsWith("http://10.0.2.2:8000/api/v1/"))
        assertNotEquals("https://api.example.com/", FreshRetrofitClient.DEFAULT_LOCAL_DEV_BASE_URL)
        assertNotEquals("https://api.fresh-market.sa/api/v1/", FreshRetrofitClient.DEFAULT_LOCAL_DEV_BASE_URL)
    }

    @Test
    fun localAuthRepository_migratesLegacyDiPreferencesWithoutLosingAccountsOrSessions() {
        val legacyPrefs = InMemorySharedPreferences()
        val canonicalPrefs = InMemorySharedPreferences()

        // 1. Register a customer account and update notification settings in legacy DI prefs ("fresh_local_auth_prefs")
        val legacyRepo = LocalAuthRepository(legacyPrefs)
        val regResult = legacyRepo.registerCustomer(
            fullName = "عبدالرحمن الشهري",
            email = "abdulrahman@fresh.sa",
            phoneNumber = "0551234567",
            password = "SecurePass123",
            confirmPassword = "SecurePass123"
        )
        assertTrue(regResult is AuthOperationResult.Success)
        legacyRepo.updateNotificationPreferences(
            orderNotificationsEnabled = false,
            promoNotificationsEnabled = true
        )

        // 2. Run the non-destructive migration into canonicalPrefs ("fresh_local_auth_and_settings")
        LocalAuthRepository.migrateLegacyPrefsIfNeeded(
            canonicalPrefs = canonicalPrefs,
            legacyPrefs = legacyPrefs
        )

        // 3. Instantiate LocalAuthRepository with canonicalPrefs and verify session, account login, and settings survived intact
        val unifiedRepo = LocalAuthRepository(canonicalPrefs)
        assertNotNull(unifiedRepo.currentUser.value)
        assertEquals("abdulrahman@fresh.sa", unifiedRepo.currentUser.value?.email)
        assertEquals("عبدالرحمن الشهري", unifiedRepo.currentUser.value?.fullName)
        assertFalse(unifiedRepo.settings.value.orderNotificationsEnabled)
        assertTrue(unifiedRepo.settings.value.promoNotificationsEnabled)

        unifiedRepo.logout()
        val relogin = unifiedRepo.loginWithCredentials("abdulrahman@fresh.sa", "SecurePass123")
        assertTrue(relogin is AuthOperationResult.Success)
    }

    @Test
    fun roomMigrations_1to2_2to3_3to4_preserveAllExistingTablesAndDataWithoutDropping() {
        val executedSql = mutableListOf<String>()
        val fakeDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSql.add(args[0].toString().trim())
            }
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_1_2.migrate(fakeDb)
        AppDatabase.MIGRATION_2_3.migrate(fakeDb)
        AppDatabase.MIGRATION_3_4.migrate(fakeDb)

        // 1. Verify zero DROP TABLE or DELETE statements exist in any Room migration
        assertTrue(
            "Room migrations must never drop tables or delete existing user data",
            executedSql.none {
                it.contains("DROP TABLE", ignoreCase = true) ||
                    it.contains("DELETE FROM", ignoreCase = true) ||
                    it.contains("TRUNCATE", ignoreCase = true)
            }
        )

        // 2. Verify MIGRATION_1_2 adds stock & expiry columns to products
        assertTrue(executedSql.any { it.contains("ALTER TABLE products ADD COLUMN stockQuantity") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE products ADD COLUMN expiryDate") })

        // 3. Verify MIGRATION_2_3 adds nameEn and creates stock_adjustments table non-destructively
        assertTrue(executedSql.any { it.contains("ALTER TABLE products ADD COLUMN nameEn") })
        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS `stock_adjustments`") })

        // 4. Verify MIGRATION_3_4 preserves existing integer quantities by converting them to milli-units
        assertTrue(executedSql.any { it.contains("UPDATE cart_items SET quantityMillis = quantity * 1000") })
        assertTrue(
            executedSql.any {
                it.contains("UPDATE stock_adjustments SET previousQuantityMillis = previousQuantity * 1000")
            }
        )
        assertTrue(
            executedSql.any {
                it.contains("UPDATE order_items SET quantityMillis = quantity * 1000, lineTotalHalalas = priceHalalas * quantity")
            }
        )
    }
}
