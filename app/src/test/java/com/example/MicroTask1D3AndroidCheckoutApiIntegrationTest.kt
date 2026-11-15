package com.example

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.FreshApiService
import com.example.data.api.FreshAuthInterceptor
import com.example.data.api.FreshRetrofitClient
import com.example.data.api.model.CheckoutItemRequest
import com.example.data.api.model.CheckoutRequest
import com.example.data.api.model.CheckoutResponse
import com.example.data.auth.AuthOperationResult
import com.example.data.auth.JvmAesGcmCipher
import com.example.data.auth.LocalAuthRepository
import com.example.data.auth.SecureTokenStorage
import com.example.data.local.AppDatabase
import com.example.data.local.model.CartItem
import com.example.data.local.model.Product
import com.example.data.repository.CartRepository
import com.example.data.repository.CheckoutFailureReason
import com.example.data.repository.CheckoutOutcome
import com.example.data.repository.OrderRepository
import com.example.data.repository.ProductRepository
import com.example.ui.viewmodel.CartViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Automated verification suite for Microtask 1D.3:
 * Android <-> Django Checkout API Integration (Server-Authoritative Order Creation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MicroTask1D3AndroidCheckoutApiIntegrationTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(data) { data.toMutableMap() }
        override fun getString(key: String?, defValue: String?): String? =
            synchronized(data) { (data[key] as? String) ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            synchronized(data) { (data[key] as? Set<String>)?.toMutableSet() ?: defValues }

        override fun getInt(key: String?, defValue: Int): Int =
            synchronized(data) { (data[key] as? Int) ?: defValue }

        override fun getLong(key: String?, defValue: Long): Long =
            synchronized(data) { (data[key] as? Long) ?: defValue }

        override fun getFloat(key: String?, defValue: Float): Float =
            synchronized(data) { (data[key] as? Float) ?: defValue }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            synchronized(data) { (data[key] as? Boolean) ?: defValue }

        override fun contains(key: String?): Boolean =
            synchronized(data) { data.containsKey(key) }

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
                synchronized(data) {
                    if (clearAll) data.clear()
                    for (k in removals) data.remove(k)
                    for ((k, v) in staged) {
                        if (v == null) data.remove(k) else data[k] = v
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    private lateinit var database: AppDatabase
    private var serverProcess: Process? = null
    private var baseUrl: String = ""

    @Before
    fun setUp() {
        LocalAuthRepository.resetInstanceForTesting()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val repoRoot = findRepoRoot()
        val serverScript = File(repoRoot, "backend/apps/orders/test_http_server.py")
        assertTrue(
            "Expected backend/apps/orders/test_http_server.py to exist at ${serverScript.absolutePath}",
            serverScript.exists()
        )

        val processBuilder = ProcessBuilder("python3", "-m", "apps.orders.test_http_server")
        processBuilder.directory(repoRoot)
        processBuilder.environment()["PYTHONPATH"] = File(repoRoot, "backend").absolutePath
        val proc = processBuilder.start()
        serverProcess = proc

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val portLine = reader.readLine()
            ?: throw IllegalStateException(
                "Failed to read bound port from test_http_server.py: " +
                    proc.errorStream.bufferedReader().readText()
            )
        val port = portLine.removePrefix("READY").trim().toInt()
        baseUrl = "http://127.0.0.1:$port/api/v1/"
    }

    @After
    fun tearDown() {
        database.close()
        serverProcess?.let { proc ->
            proc.destroy()
            proc.waitFor(3, TimeUnit.SECONDS)
            if (proc.isAlive) {
                proc.destroyForcibly()
            }
        }
        serverProcess = null
        LocalAuthRepository.resetInstanceForTesting()
    }

    private fun findRepoRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            if (File(current, "backend/apps/orders/test_http_server.py").exists()) {
                return current
            }
            current = current.parentFile
        }
        return File("/app/applet")
    }

    private data class CheckoutTestRig(
        val authRepo: LocalAuthRepository,
        val tokenStorage: SecureTokenStorage,
        val apiService: FreshApiService,
        val authInterceptor: FreshAuthInterceptor,
        val productRepository: ProductRepository,
        val orderRepository: OrderRepository,
        val cartRepository: CartRepository,
        val cartViewModel: CartViewModel
    )

    private fun createRig(
        prefs: SharedPreferences = InMemorySharedPreferences(),
        customApiService: FreshApiService? = null
    ): CheckoutTestRig {
        val tokenStorage = SecureTokenStorage(
            encryptedPrefs = prefs,
            cipher = JvmAesGcmCipher()
        )
        var authRepoRef: LocalAuthRepository? = null
        val (realService, interceptor) = FreshRetrofitClient.createAuthenticatedService(
            tokenStorage = tokenStorage,
            baseUrl = baseUrl,
            onSessionInvalidated = {
                authRepoRef?.handleRemoteSessionInvalidated()
            }
        )
        val effectiveService = customApiService ?: realService
        val authRepo = LocalAuthRepository(
            sharedPreferences = prefs,
            secureTokenStorage = tokenStorage,
            apiService = effectiveService,
            allowOfflineLocalFallback = false,
            isDebugBuild = true
        )
        authRepoRef = authRepo

        val productRepo = ProductRepository(
            productDao = database.productDao(),
            apiService = effectiveService,
            stockAdjustmentDao = database.stockAdjustmentDao()
        )
        val orderRepo = OrderRepository(
            database = database,
            productDao = database.productDao(),
            cartDao = database.cartDao(),
            orderDao = database.orderDao(),
            orderItemDao = database.orderItemDao(),
            stockAdjustmentDao = database.stockAdjustmentDao(),
            apiService = effectiveService
        )
        val cartRepo = CartRepository(
            cartDao = database.cartDao(),
            orderRepository = orderRepo
        )
        val cartVm = CartViewModel(
            cartRepository = cartRepo,
            productRepository = productRepo,
            orderRepository = orderRepo
        )
        return CheckoutTestRig(
            authRepo = authRepo,
            tokenStorage = tokenStorage,
            apiService = effectiveService,
            authInterceptor = interceptor,
            productRepository = productRepo,
            orderRepository = orderRepo,
            cartRepository = cartRepo,
            cartViewModel = cartVm
        )
    }

    @Test
    fun test_01_checkout_dto_serialization_and_deserialization_matches_django_contract_and_omits_forbidden_fields() {
        val json = FreshRetrofitClient.networkJson
        val request = CheckoutRequest(
            branchId = 1,
            fulfillmentWarehouseId = 1,
            idempotencyKey = "chk-contract-001",
            items = listOf(
                CheckoutItemRequest(productId = 1, quantityMilli = 1500L),
                CheckoutItemRequest(productId = 2, quantityMilli = 2000L)
            )
        )

        val encoded = json.encodeToString(request)
        val jsonObj = json.decodeFromString<JsonObject>(encoded)

        // Verify allowed fields
        assertEquals(setOf("items", "branch_id", "fulfillment_warehouse_id", "idempotency_key"), jsonObj.keys)
        assertEquals(1, jsonObj["branch_id"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1, jsonObj["fulfillment_warehouse_id"]?.jsonPrimitive?.content?.toInt())
        assertEquals("chk-contract-001", jsonObj["idempotency_key"]?.jsonPrimitive?.content)

        val itemsArr = jsonObj["items"]!!.jsonArray
        assertEquals(2, itemsArr.size)
        assertEquals(setOf("product_id", "quantity_milli"), itemsArr[0].jsonObject.keys)
        assertEquals(1500L, itemsArr[0].jsonObject["quantity_milli"]?.jsonPrimitive?.long)

        // Verify NO forbidden financial, stock, or identity fields are ever serialized by Android
        val forbiddenFields = listOf(
            "total_halalas",
            "subtotal_halalas",
            "expected_total_halalas",
            "unit_price_halalas",
            "price_halalas",
            "line_total_halalas",
            "discount_halalas",
            "delivery_fee_halalas",
            "stock_quantity",
            "on_hand_milli",
            "reserved_milli",
            "customer_id",
            "user_id",
            "actor_id",
            "role",
            "store_id"
        )
        for (forbidden in forbiddenFields) {
            assertFalse("Request JSON must never contain '$forbidden'", encoded.contains("\"$forbidden\""))
        }

        // Verify CheckoutResponse deserialization for both 201 Created and 200 OK replay
        val serverResponseJson = """
            {
                "id": 42,
                "branch": 1,
                "branch_id": 1,
                "fulfillment_warehouse": 1,
                "fulfillment_warehouse_id": 1,
                "customer_id": "cust-10",
                "status": "PENDING",
                "total_halalas": 3493,
                "idempotency_key": "chk-contract-001",
                "cancellation_restored": false,
                "timestamp": "2026-11-13T15:00:00Z",
                "updated_at": "2026-11-13T15:00:00Z",
                "replayed": true,
                "items": [
                    {
                        "id": 101,
                        "product": 1,
                        "product_id": 1,
                        "product_name_snapshot": "طماطم محمية طازجة",
                        "unit_snapshot": "كيلو",
                        "quantity_milli": 1500,
                        "unit_price_halalas": 795,
                        "line_total_halalas": 1193
                    }
                ],
                "status_history": [
                    {
                        "id": 1,
                        "from_status": null,
                        "to_status": "PENDING",
                        "changed_by_actor_id": "customer:cust-10",
                        "reason": "Order created via atomic checkout",
                        "changed_at": "2026-11-13T15:00:00Z"
                    }
                ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString<CheckoutResponse>(serverResponseJson)
        assertEquals(42, decoded.id)
        assertEquals("PENDING", decoded.status)
        assertEquals(3493L, decoded.totalHalalas)
        assertEquals("cust-10", decoded.customerId)
        assertEquals(true, decoded.replayed)
        assertEquals(1, decoded.items.size)
        assertEquals(1193L, decoded.items[0].lineTotalHalalas)
    }

    @Test
    fun test_02_successful_server_checkout_201_creates_order_with_server_prices_and_caches_in_room() = runBlocking {
        val rig = createRig()

        // 1. Register a real customer account on the server
        val regResult = rig.authRepo.registerCustomer(
            fullName = "نواف السبيعي",
            email = "nawaf.subaei@fresh.sa",
            phoneNumber = "0551122334",
            password = "NawafStrongPass99",
            confirmPassword = "NawafStrongPass99"
        )
        assertTrue(regResult is AuthOperationResult.Success)
        val customer = (regResult as AuthOperationResult.Success).user

        // 2. Seed Room with intentionally stale/different local prices (100 halalas instead of server's 795 & 1150)
        //    to prove that the confirmed order uses Django's authoritative prices and total!
        rig.productRepository.saveProduct(
            Product(
                id = 1,
                name = "طماطم محمية طازجة",
                category = "خضروات طازجة",
                priceHalalas = 100, // Stale local price; server authoritative price is 795
                description = "طماطم",
                imageUrl = "tomato",
                unit = "كيلو",
                stockQuantity = 50,
                stockSubUnitsMillis = 50_000
            )
        )
        rig.productRepository.saveProduct(
            Product(
                id = 2,
                name = "تفاح أحمر سكري",
                category = "فواكه موسمية",
                priceHalalas = 100, // Stale local price; server authoritative price is 1150
                description = "تفاح",
                imageUrl = "apple",
                unit = "كيلو",
                stockQuantity = 50,
                stockSubUnitsMillis = 50_000
            )
        )

        // 3. Add 1.5 kg (1500 milli) of product 1 and 2.0 kg (2000 milli) of product 2 to cart
        rig.cartRepository.addToCart(CartItem.fromMillis(productId = 1, quantityMillis = 1_500))
        rig.cartRepository.addToCart(CartItem.fromMillis(productId = 2, quantityMillis = 2_000))

        // Server calculation:
        //   Product 1: (1500 * 795 + 500) // 1000 = 1193 halalas
        //   Product 2: (2000 * 1150 + 500) // 1000 = 2300 halalas
        //   Authoritative Server Total = 3493 halalas
        val outcome = rig.cartViewModel.executeCheckout(customer = customer)
        assertTrue("Expected server checkout to succeed, got $outcome", outcome is CheckoutOutcome.Success)
        val success = outcome as CheckoutOutcome.Success

        assertEquals(201, success.httpStatusCode)
        assertFalse(success.isReplayed)
        assertEquals(3493, success.order.totalHalalas)
        assertEquals(3493, success.order.subtotalHalalas)
        assertEquals("PENDING", success.order.status)
        assertEquals(customer.customerId, success.order.customerId)

        // 4. Verify Room cache was updated ONLY after server response with server-authoritative values
        val cachedOrders = database.orderDao().getAllOrdersOnce()
        assertEquals(1, cachedOrders.size)
        assertEquals(success.order.id, cachedOrders[0].id)
        assertEquals(3493, cachedOrders[0].totalHalalas)
        assertEquals("PENDING", cachedOrders[0].status)

        val cachedItems = database.orderItemDao().getOrderItemsOnce(success.order.id)
        assertEquals(2, cachedItems.size)
        val item1 = cachedItems.first { it.productId == 1 }
        val item2 = cachedItems.first { it.productId == 2 }
        assertEquals(795, item1.priceHalalas)
        assertEquals(1193, item1.lineTotalHalalas)
        assertEquals(1_500, item1.quantityMillis)
        assertEquals(1150, item2.priceHalalas)
        assertEquals(2300, item2.lineTotalHalalas)
        assertEquals(2_000, item2.quantityMillis)

        // 5. Verify cart was cleared after server confirmation and local cached stock deducted once
        assertTrue(rig.cartRepository.getCartItemsOnce().isEmpty())
        val updatedProd1 = database.productDao().getProductById(1)!!
        val updatedProd2 = database.productDao().getProductById(2)!!
        assertEquals(48_500, updatedProd1.effectiveStockMillis)
        assertEquals(795, updatedProd1.priceHalalas)
        assertEquals(48_000, updatedProd2.effectiveStockMillis)
        assertEquals(1150, updatedProd2.priceHalalas)
    }

    @Test
    fun test_03_retry_with_same_idempotency_key_returns_200_replayed_without_duplicate_order_or_double_stock_deduction() = runBlocking {
        val rig = createRig()

        val regResult = rig.authRepo.registerCustomer(
            fullName = "ماجد الحربي",
            email = "majed.harbi@fresh.sa",
            phoneNumber = "0503344556",
            password = "MajedStrongPass99",
            confirmPassword = "MajedStrongPass99"
        )
        val customer = (regResult as AuthOperationResult.Success).user

        rig.productRepository.saveProduct(
            Product(
                id = 1,
                name = "طماطم محمية طازجة",
                category = "خضروات طازجة",
                priceHalalas = 795,
                description = "طماطم",
                imageUrl = "tomato",
                unit = "كيلو",
                stockQuantity = 50,
                stockSubUnitsMillis = 50_000
            )
        )
        rig.cartRepository.addToCart(CartItem.fromMillis(productId = 1, quantityMillis = 2_000))

        val stableKey = rig.cartViewModel.activeCheckoutIdempotencyKey
        assertTrue(stableKey.isNotBlank())

        // First call -> 201 Created
        val firstOutcome = rig.cartViewModel.executeCheckout(customer = customer)
        assertTrue(firstOutcome is CheckoutOutcome.Success)
        val firstSuccess = firstOutcome as CheckoutOutcome.Success
        assertEquals(201, firstSuccess.httpStatusCode)
        assertFalse(firstSuccess.isReplayed)
        assertEquals(stableKey, firstSuccess.order.idempotencyKey)

        // Retry with the exact same Idempotency-Key -> 200 OK (replayed = true)
        val replayOutcome = rig.cartViewModel.executeCheckout(
            customer = customer,
            idempotencyKeyOverride = stableKey
        )
        assertTrue("Expected idempotent replay to succeed with 200 OK, got $replayOutcome", replayOutcome is CheckoutOutcome.Success)
        val replaySuccess = replayOutcome as CheckoutOutcome.Success
        assertEquals(200, replaySuccess.httpStatusCode)
        assertTrue(replaySuccess.isReplayed)
        assertEquals(firstSuccess.order.id, replaySuccess.order.id)
        assertEquals(firstSuccess.order.totalHalalas, replaySuccess.order.totalHalalas)

        // Verify Room has only 1 order, 1 order item, and stock was deducted ONLY ONCE (50_000 - 2_000 = 48_000)
        assertEquals(1, database.orderDao().getAllOrdersOnce().size)
        assertEquals(1, database.orderItemDao().getOrderItemsOnce(firstSuccess.order.id).size)
        assertEquals(48_000, database.productDao().getProductById(1)!!.effectiveStockMillis)
    }

    @Test
    fun test_04_expired_or_unauthenticated_session_401_rejects_checkout_and_preserves_cart() = runBlocking {
        val rig = createRig()

        rig.productRepository.saveProduct(
            Product(
                id = 1,
                name = "طماطم محمية طازجة",
                category = "خضروات طازجة",
                priceHalalas = 795,
                description = "طماطم",
                imageUrl = "tomato",
                unit = "كيلو",
                stockQuantity = 50,
                stockSubUnitsMillis = 50_000
            )
        )
        rig.cartRepository.addToCart(CartItem.fromMillis(productId = 1, quantityMillis = 1_000))
        val keyBeforeAttempt = rig.cartViewModel.activeCheckoutIdempotencyKey

        // Execute checkout without logging in (no JWT tokens stored) -> Server returns 401 Unauthorized
        val outcome = rig.cartViewModel.executeCheckout(customer = null)
        assertTrue(outcome is CheckoutOutcome.Failure)
        val failure = outcome as CheckoutOutcome.Failure
        assertEquals(CheckoutFailureReason.UNAUTHENTICATED_SESSION, failure.reason)
        assertEquals(401, failure.httpStatusCode)

        // Cart and Idempotency-Key must be preserved; no order confirmed in UI or Room
        assertEquals(1, rig.cartRepository.getCartItemsOnce().size)
        assertEquals(keyBeforeAttempt, rig.cartViewModel.activeCheckoutIdempotencyKey)
        assertNull(rig.cartViewModel.lastCompletedOrder.value)
        assertTrue(database.orderDao().getAllOrdersOnce().isEmpty())
    }

    @Test
    fun test_05_non_customer_role_403_rejects_checkout_and_preserves_cart() = runBlocking {
        val rig = createRig()

        // Log in as STORE_OWNER (seeded on the server as owner@fresh.sa)
        val loginRes = rig.authRepo.loginWithCredentials("owner@fresh.sa", "OwnerPass123")
        assertTrue(loginRes is AuthOperationResult.Success)
        val ownerUser = (loginRes as AuthOperationResult.Success).user

        rig.productRepository.saveProduct(
            Product(
                id = 1,
                name = "طماطم محمية طازجة",
                category = "خضروات طازجة",
                priceHalalas = 795,
                description = "طماطم",
                imageUrl = "tomato",
                unit = "كيلو",
                stockQuantity = 50,
                stockSubUnitsMillis = 50_000
            )
        )
        rig.cartRepository.addToCart(CartItem.fromMillis(productId = 1, quantityMillis = 1_000))

        val outcome = rig.cartViewModel.executeCheckout(customer = ownerUser)
        assertTrue(outcome is CheckoutOutcome.Failure)
        val failure = outcome as CheckoutOutcome.Failure
        assertEquals(CheckoutFailureReason.FORBIDDEN_OR_UNAUTHORIZED, failure.reason)
        assertEquals(403, failure.httpStatusCode)

        // Cart and stock must remain untouched
        assertEquals(1, rig.cartRepository.getCartItemsOnce().size)
        assertNull(rig.cartViewModel.lastCompletedOrder.value)
        assertTrue(database.orderDao().getAllOrdersOnce().isEmpty())
    }

    @Test
    fun test_06_insufficient_server_stock_409_rejects_checkout_and_preserves_cart() = runBlocking {
        val rig = createRig()

        val regResult = rig.authRepo.registerCustomer(
            fullName = "تركي العتيبي",
            email = "turki.otaibi@fresh.sa",
            phoneNumber = "0567788990",
            password = "TurkiStrongPass99",
            confirmPassword = "TurkiStrongPass99"
        )
        val customer = (regResult as AuthOperationResult.Success).user

        // Server has 50,000 milli-units (50 kg) of Product 1. Put 60,000 milli-units (60 kg) in cart.
        rig.productRepository.saveProduct(
            Product(
                id = 1,
                name = "طماطم محمية طازجة",
                category = "خضروات طازجة",
                priceHalalas = 795,
                description = "طماطم",
                imageUrl = "tomato",
                unit = "كيلو",
                stockQuantity = 100, // Local cache says 100 kg, but server only has 50 kg!
                stockSubUnitsMillis = 100_000
            )
        )
        rig.cartRepository.addToCart(CartItem.fromMillis(productId = 1, quantityMillis = 60_000))

        val outcome = rig.cartViewModel.executeCheckout(customer = customer)
        assertTrue(outcome is CheckoutOutcome.Failure)
        val failure = outcome as CheckoutOutcome.Failure
        assertEquals(CheckoutFailureReason.INSUFFICIENT_STOCK, failure.reason)
        assertEquals(409, failure.httpStatusCode)
        assertEquals("insufficient_stock", failure.serverErrorCode)

        // Cart must not be cleared and Room must have 0 orders
        assertEquals(1, rig.cartRepository.getCartItemsOnce().size)
        assertEquals(60_000, rig.cartRepository.getCartItemsOnce()[0].effectiveQuantityMillis)
        assertNull(rig.cartViewModel.lastCompletedOrder.value)
        assertTrue(database.orderDao().getAllOrdersOnce().isEmpty())
    }

    @Test
    fun test_07_handling_409_idempotency_conflict_422_price_mismatch_and_5xx_retry_with_same_key() = runBlocking {
        val rig = createRig()

        val regResult = rig.authRepo.registerCustomer(
            fullName = "سلطان الشهري",
            email = "sultan.shehri@fresh.sa",
            phoneNumber = "0541122998",
            password = "SultanStrongPass99",
            confirmPassword = "SultanStrongPass99"
        )
        val customer = (regResult as AuthOperationResult.Success).user

        rig.productRepository.saveProduct(
            Product(
                id = 1,
                name = "طماطم محمية طازجة",
                category = "خضروات طازجة",
                priceHalalas = 795,
                description = "طماطم",
                imageUrl = "tomato",
                unit = "كيلو",
                stockQuantity = 50,
                stockSubUnitsMillis = 50_000
            )
        )
        rig.productRepository.saveProduct(
            Product(
                id = 2,
                name = "تفاح أحمر سكري",
                category = "فواكه موسمية",
                priceHalalas = 1150,
                description = "تفاح",
                imageUrl = "apple",
                unit = "كيلو",
                stockQuantity = 50,
                stockSubUnitsMillis = 50_000
            )
        )

        // Part A: 409 Idempotency Conflict when reusing an already-used key with a DIFFERENT cart payload
        rig.cartRepository.addToCart(CartItem.fromMillis(productId = 1, quantityMillis = 1_000))
        val usedKey = "chk_conflict_key_001"
        val firstOrder = rig.cartViewModel.executeCheckout(
            customer = customer,
            idempotencyKeyOverride = usedKey
        )
        assertTrue(firstOrder is CheckoutOutcome.Success)

        // Now add Product 2 (different payload) and force the same `usedKey`
        rig.cartRepository.addToCart(CartItem.fromMillis(productId = 2, quantityMillis = 2_000))
        val conflictOutcome = rig.cartViewModel.executeCheckout(
            customer = customer,
            idempotencyKeyOverride = usedKey
        )
        assertTrue(conflictOutcome is CheckoutOutcome.Failure)
        val conflictFailure = conflictOutcome as CheckoutOutcome.Failure
        assertEquals(CheckoutFailureReason.IDEMPOTENCY_CONFLICT, conflictFailure.reason)
        assertEquals(409, conflictFailure.httpStatusCode)
        assertEquals("idempotency_conflict", conflictFailure.serverErrorCode)
        assertEquals(1, rig.cartRepository.getCartItemsOnce().size)

        // Part B: 422 Unprocessable Entity (Price mismatch / validation failure) & 503 Server Error followed by retry with same key
        val simulatedStatus = AtomicInteger(422)
        val capturedIdempotencyHeaders = mutableListOf<String>()
        val statusSimulatingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                req.header("Idempotency-Key")?.let { capturedIdempotencyHeaders.add(it) }
                val codeToReturn = simulatedStatus.get()
                if (codeToReturn == 422) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(422)
                        .message("Unprocessable Entity")
                        .body(
                            """{"detail":"Price changed on server before checkout confirmation.","code":"price_mismatch"}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                } else if (codeToReturn == 503) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body(
                            """{"detail":"Temporary database maintenance.","code":"internal_server_error"}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                } else {
                    chain.proceed(req)
                }
            }
            .addInterceptor(rig.authInterceptor)
            .build()

        val controlledService = FreshRetrofitClient.createService(
            baseUrl = baseUrl,
            client = statusSimulatingClient
        )
        val controlledRig = createRig(customApiService = controlledService)

        val keyForRetrySequence = controlledRig.cartViewModel.activeCheckoutIdempotencyKey

        // 1) 422 Unprocessable Entity
        val outcome422 = controlledRig.cartViewModel.executeCheckout(customer = customer)
        assertTrue(outcome422 is CheckoutOutcome.Failure)
        assertEquals(CheckoutFailureReason.PRICE_MISMATCH_OR_UNPROCESSABLE, (outcome422 as CheckoutOutcome.Failure).reason)
        assertEquals(422, outcome422.httpStatusCode)
        assertEquals(1, controlledRig.cartRepository.getCartItemsOnce().size)
        // Key must remain stable when cart is unchanged!
        assertEquals(keyForRetrySequence, controlledRig.cartViewModel.activeCheckoutIdempotencyKey)

        // 2) 503 Server Error
        simulatedStatus.set(503)
        val outcome503 = controlledRig.cartViewModel.executeCheckout(customer = customer)
        assertTrue(outcome503 is CheckoutOutcome.Failure)
        assertEquals(CheckoutFailureReason.SERVER_ERROR, (outcome503 as CheckoutOutcome.Failure).reason)
        assertEquals(503, outcome503.httpStatusCode)
        assertEquals(1, controlledRig.cartRepository.getCartItemsOnce().size)
        assertEquals(keyForRetrySequence, controlledRig.cartViewModel.activeCheckoutIdempotencyKey)

        // 3) Retry when server recovers (201 Created) -> MUST send the exact same Idempotency-Key!
        simulatedStatus.set(201)
        val retrySuccess = controlledRig.cartViewModel.executeCheckout(customer = customer)
        assertTrue("Expected retry to succeed, got $retrySuccess", retrySuccess is CheckoutOutcome.Success)
        assertEquals(201, (retrySuccess as CheckoutOutcome.Success).httpStatusCode)
        assertEquals(3, capturedIdempotencyHeaders.size)
        assertEquals(
            "All 3 attempts for the same logical order must send the exact same Idempotency-Key",
            setOf(keyForRetrySequence),
            capturedIdempotencyHeaders.toSet()
        )
        assertTrue(controlledRig.cartRepository.getCartItemsOnce().isEmpty())
    }

    @Test
    fun test_08_order_never_confirmed_before_server_responds_and_backend_rejects_forbidden_client_fields() = runBlocking {
        val requestArrivedLatch = CountDownLatch(1)
        val releaseResponseLatch = CountDownLatch(1)

        val baseRig = createRig()
        val regResult = baseRig.authRepo.registerCustomer(
            fullName = "عبدالرحمن القحطاني",
            email = "abdulrahman.q@fresh.sa",
            phoneNumber = "0598877665",
            password = "AbdulrahmanPass99",
            confirmPassword = "AbdulrahmanPass99"
        )
        val customer = (regResult as AuthOperationResult.Success).user

        val gatedClient = OkHttpClient.Builder()
            .addInterceptor(baseRig.authInterceptor)
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                if (req.url.encodedPath.endsWith("/orders/") && req.method == "POST") {
                    requestArrivedLatch.countDown()
                    releaseResponseLatch.await(5, TimeUnit.SECONDS)
                }
                chain.proceed(req)
            })
            .build()

        val gatedService = FreshRetrofitClient.createService(baseUrl = baseUrl, client = gatedClient)
        val gatedRig = createRig(customApiService = gatedService)
        // Copy valid tokens into gatedRig's storage
        gatedRig.tokenStorage.saveTokens(baseRig.tokenStorage.readTokens()!!)

        gatedRig.productRepository.saveProduct(
            Product(
                id = 1,
                name = "طماطم محمية طازجة",
                category = "خضروات طازجة",
                priceHalalas = 795,
                description = "طماطم",
                imageUrl = "tomato",
                unit = "كيلو",
                stockQuantity = 50,
                stockSubUnitsMillis = 50_000
            )
        )
        gatedRig.cartRepository.addToCart(CartItem.fromMillis(productId = 1, quantityMillis = 1_000))

        // Launch checkout asynchronously while server response is held by releaseResponseLatch
        val deferredOutcome = async(Dispatchers.IO) {
            gatedRig.cartViewModel.executeCheckout(customer = customer)
        }

        assertTrue("Checkout request should reach network interceptor", requestArrivedLatch.await(5, TimeUnit.SECONDS))

        // While waiting for server response:
        // 1. Order MUST NOT be marked completed in ViewModel
        assertNull("lastCompletedOrder must be null before server responds", gatedRig.cartViewModel.lastCompletedOrder.value)
        assertTrue("isSubmittingCheckout must be true while awaiting server", gatedRig.cartViewModel.isSubmittingCheckout.value)
        // 2. Room orders table MUST still be empty and cart MUST still hold the item
        assertTrue("Room orders table must be empty before server confirmation", database.orderDao().getAllOrdersOnce().isEmpty())
        assertEquals("Cart must not be cleared before server confirmation", 1, gatedRig.cartRepository.getCartItemsOnce().size)

        // Release server response
        releaseResponseLatch.countDown()
        val finalOutcome = deferredOutcome.await()
        assertTrue(finalOutcome is CheckoutOutcome.Success)
        assertNotNull("lastCompletedOrder must be populated after server responds", gatedRig.cartViewModel.lastCompletedOrder.value)
        assertEquals(1, database.orderDao().getAllOrdersOnce().size)
        assertTrue(gatedRig.cartRepository.getCartItemsOnce().isEmpty())

        // Finally, verify the backend rejects raw client payloads that attempt to spoof forbidden fields
        val rawHttpClient = OkHttpClient.Builder().addInterceptor(baseRig.authInterceptor).build()
        val spoofedPayloads = listOf(
            """{"branch_id":1,"idempotency_key":"spoof-1","customer_id":"cust-999","items":[{"product_id":1,"quantity_milli":1000}]}""",
            """{"branch_id":1,"idempotency_key":"spoof-2","role":"OWNER","items":[{"product_id":1,"quantity_milli":1000}]}""",
            """{"branch_id":1,"idempotency_key":"spoof-3","subtotal_halalas":10,"items":[{"product_id":1,"quantity_milli":1000}]}""",
            """{"branch_id":1,"idempotency_key":"spoof-4","items":[{"product_id":1,"quantity_milli":1000,"price_halalas":1}]}""",
            """{"branch_id":1,"idempotency_key":"spoof-5","items":[{"product_id":1,"quantity_milli":1000,"stock_quantity":99999}]}"""
        )
        for (rawPayload in spoofedPayloads) {
            val req = Request.Builder()
                .url("${baseUrl}orders/")
                .post(rawPayload.toRequestBody("application/json".toMediaType()))
                .build()
            rawHttpClient.newCall(req).execute().use { resp ->
                assertTrue(
                    "Server must reject spoofed payload ($rawPayload) with 400 or 403, got ${resp.code}",
                    resp.code == 400 || resp.code == 403
                )
            }
        }
    }
}
