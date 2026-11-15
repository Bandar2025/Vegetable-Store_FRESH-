package com.example

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.FreshApiService
import com.example.data.api.FreshAuthInterceptor
import com.example.data.api.FreshRetrofitClient
import com.example.data.api.model.CancelOrderRequestDto
import com.example.data.api.model.OrderResponse
import com.example.data.api.model.OrderStatusTransitionRequestDto
import com.example.data.auth.AuthOperationResult
import com.example.data.auth.AuthenticatedUser
import com.example.data.auth.JvmAesGcmCipher
import com.example.data.auth.LocalAuthRepository
import com.example.data.auth.SecureTokenStorage
import com.example.data.local.AppDatabase
import com.example.data.local.model.CartItem
import com.example.data.local.model.OrderStatus
import com.example.data.repository.CartRepository
import com.example.data.repository.CheckoutOutcome
import com.example.data.repository.OrderRepository
import com.example.data.repository.OrderSyncOutcome
import com.example.data.repository.OrderTransitionFailureReason
import com.example.data.repository.OrderTransitionOutcome
import com.example.data.repository.ProductRepository
import com.example.ui.viewmodel.CartViewModel
import com.example.ui.viewmodel.StoreManagementViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automated End-to-End Verification Suite for Microtask 1D.4:
 * Order Lifecycle Integration & Certification:
 * Android Customer App -> Django REST API -> Store Owner / Branch Manager App -> Django REST API -> Android Customer App.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MicroTask1D4OrderLifecycleIntegrationTest {

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

    private val openedDatabases = mutableListOf<AppDatabase>()
    private var serverProcess: Process? = null
    private var baseUrl: String = ""

    @Before
    fun setUp() {
        LocalAuthRepository.resetInstanceForTesting()
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
        runCatching { Shadows.shadowOf(Looper.getMainLooper()).idle() }
        for (db in openedDatabases) {
            runCatching { db.close() }
        }
        openedDatabases.clear()
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

    private data class DeviceRig(
        val database: AppDatabase,
        val authRepo: LocalAuthRepository,
        val tokenStorage: SecureTokenStorage,
        val apiService: FreshApiService,
        val authInterceptor: FreshAuthInterceptor,
        val productRepository: ProductRepository,
        val orderRepository: OrderRepository,
        val cartRepository: CartRepository,
        val cartViewModel: CartViewModel,
        val storeManagementViewModel: StoreManagementViewModel,
        val networkOutageFlag: AtomicBoolean
    )

    /**
     * Creates an isolated Android client rig with its own in-memory Room [AppDatabase],
     * encrypted [SecureTokenStorage], [FreshApiService], [OrderRepository], [CartViewModel],
     * and [StoreManagementViewModel] talking to the shared live HTTP backend.
     */
    private fun createDeviceRig(): DeviceRig {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        openedDatabases.add(db)

        val prefs = InMemorySharedPreferences()
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

        val outageFlag = AtomicBoolean(false)
        val toggleableService = object : FreshApiService by realService {
            private fun checkNetwork() {
                if (outageFlag.get()) {
                    throw IOException("Simulated network outage")
                }
            }

            override suspend fun getOrders(
                branchId: Int?,
                status: String?
            ): retrofit2.Response<List<OrderResponse>> {
                checkNetwork()
                return realService.getOrders(branchId = branchId, status = status)
            }

            override suspend fun getOrderById(orderId: Int): retrofit2.Response<OrderResponse> {
                checkNetwork()
                return realService.getOrderById(orderId = orderId)
            }

            override suspend fun transitionOrderStatus(
                orderId: Int,
                request: OrderStatusTransitionRequestDto
            ): retrofit2.Response<OrderResponse> {
                checkNetwork()
                return realService.transitionOrderStatus(orderId = orderId, request = request)
            }

            override suspend fun cancelOrder(
                orderId: Int,
                idempotencyKey: String,
                request: CancelOrderRequestDto
            ): retrofit2.Response<OrderResponse> {
                checkNetwork()
                return realService.cancelOrder(
                    orderId = orderId,
                    idempotencyKey = idempotencyKey,
                    request = request
                )
            }
        }

        val authRepo = LocalAuthRepository(
            sharedPreferences = prefs,
            secureTokenStorage = tokenStorage,
            apiService = toggleableService,
            allowOfflineLocalFallback = false,
            isDebugBuild = true
        )
        authRepoRef = authRepo

        val productRepo = ProductRepository(
            productDao = db.productDao(),
            apiService = toggleableService,
            stockAdjustmentDao = db.stockAdjustmentDao()
        )
        val orderRepo = OrderRepository(
            database = db,
            productDao = db.productDao(),
            cartDao = db.cartDao(),
            orderDao = db.orderDao(),
            orderItemDao = db.orderItemDao(),
            stockAdjustmentDao = db.stockAdjustmentDao(),
            apiService = toggleableService
        )
        val cartRepo = CartRepository(
            cartDao = db.cartDao(),
            orderRepository = orderRepo
        )
        val cartVm = CartViewModel(
            cartRepository = cartRepo,
            productRepository = productRepo,
            orderRepository = orderRepo
        )
        val storeVm = StoreManagementViewModel(
            repository = productRepo,
            orderRepository = orderRepo
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return DeviceRig(
            database = db,
            authRepo = authRepo,
            tokenStorage = tokenStorage,
            apiService = toggleableService,
            authInterceptor = interceptor,
            productRepository = productRepo,
            orderRepository = orderRepo,
            cartRepository = cartRepo,
            cartViewModel = cartVm,
            storeManagementViewModel = storeVm,
            networkOutageFlag = outageFlag
        )
    }

    private suspend fun registerCustomerOnRig(
        rig: DeviceRig,
        fullName: String,
        email: String,
        phone: String,
        password: String = "FreshCust#2026"
    ): AuthenticatedUser {
        val res = rig.authRepo.registerCustomer(
            fullName = fullName,
            email = email,
            phoneNumber = phone,
            password = password,
            confirmPassword = password
        )
        assertTrue("Expected registration success but got $res", res is AuthOperationResult.Success)
        return (res as AuthOperationResult.Success).user
    }

    private suspend fun loginUserOnRig(
        rig: DeviceRig,
        identifier: String,
        password: String
    ): AuthenticatedUser {
        val res = rig.authRepo.loginWithCredentials(identifier, password)
        assertTrue("Expected login success for $identifier but got $res", res is AuthOperationResult.Success)
        return (res as AuthOperationResult.Success).user
    }

    @Test
    fun test_01_customer_checkout_creates_server_order_and_store_owner_and_branch_manager_sync_it() = runBlocking {
        val customerRig = createDeviceRig()
        val ownerRig = createDeviceRig()
        val branch1ManagerRig = createDeviceRig()

        val customer = registerCustomerOnRig(
            rig = customerRig,
            fullName = "عبدالرحمن العميل",
            email = "abdulrahman_1d4@fresh.sa",
            phone = "0556001101"
        )
        customerRig.productRepository.refreshProducts()

        // Customer adds 2 kg of Product 1 (795 halalas/kg -> 1590) and 1 unit of Product 2 (1150 halalas -> 1150)
        customerRig.cartRepository.addToCart(CartItem.fromMillis(productId = 1, quantityMillis = 2000))
        customerRig.cartRepository.addToCart(CartItem.fromMillis(productId = 2, quantityMillis = 1000))
        val cartItems = customerRig.database.cartDao().getCartItemsOnce()
        assertEquals(2, cartItems.size)

        val checkoutOutcome = customerRig.cartViewModel.executeCheckout(
            customer = customer,
            branchId = 1
        )
        assertTrue("Expected server checkout success but got $checkoutOutcome", checkoutOutcome is CheckoutOutcome.Success)
        val checkoutSuccess = checkoutOutcome as CheckoutOutcome.Success
        val createdOrder = checkoutSuccess.order
        val createdItems = checkoutSuccess.items
        val statusHistory = customerRig.orderRepository.getCachedStatusHistoryForOrder(createdOrder.id)

        // Verify server-authoritative order fields on Customer side
        assertEquals("PENDING", createdOrder.status)
        assertTrue(createdOrder.isServerBacked)
        assertEquals("قيد الانتظار (PENDING)", createdOrder.displayStatusArabic)
        assertFalse(
            "Server order must never be labeled as local-only confirmed",
            createdOrder.displayStatusArabic.contains("محلياً")
        )
        assertEquals(2740, createdOrder.totalHalalas)
        assertEquals(2, createdItems.size)
        assertEquals(Integer.valueOf(1), customerRig.orderRepository.getCachedBranchIdForOrder(createdOrder.id))
        assertEquals(1, statusHistory.size)
        assertEquals("PENDING", statusHistory.first().toStatus)

        // Store 1 Owner logs in on a separate device rig (empty Room DB initially) and syncs Branch 1 orders
        val ownerActor = loginUserOnRig(ownerRig, "owner@fresh.sa", "OwnerPass123")
        assertEquals(0, ownerRig.database.orderDao().getAllOrdersOnce().size)

        val ownerSync = ownerRig.storeManagementViewModel.syncStoreOrdersFromServer(
            actor = ownerActor,
            branchId = 1
        )
        assertTrue("Expected owner order sync success but got $ownerSync", ownerSync is OrderSyncOutcome.Success)
        val ownerOrders = ownerRig.orderRepository.getAllOrdersWithItemsForStoreManagement(ownerActor).first()
        val syncedOwnerOrder = ownerOrders.find { it.order.id == createdOrder.id }
        assertNotNull("Expected customer's order to appear in Store Owner's Room & ViewModel", syncedOwnerOrder)
        assertEquals("PENDING", syncedOwnerOrder!!.order.status)
        assertEquals(2740, syncedOwnerOrder.order.totalHalalas)
        assertEquals(2, syncedOwnerOrder.items.size)
        assertEquals(listOf(795, 1150), syncedOwnerOrder.items.map { it.unitPriceHalalas }.sorted())

        // Branch 1 Manager logs in on a separate device rig and syncs orders
        val mgr1Actor = loginUserOnRig(branch1ManagerRig, "manager@fresh.sa", "ManagerPass123")
        val mgr1Sync = branch1ManagerRig.storeManagementViewModel.syncStoreOrdersFromServer(actor = mgr1Actor)
        assertTrue("Expected branch manager sync success but got $mgr1Sync", mgr1Sync is OrderSyncOutcome.Success)
        val mgr1Orders = branch1ManagerRig.orderRepository.getAllOrdersWithItemsForStoreManagement(mgr1Actor).first()
        assertTrue(mgr1Orders.any { it.order.id == createdOrder.id })
    }

    @Test
    fun test_02_cross_branch_cross_store_and_cross_customer_isolation_over_live_http() = runBlocking {
        val customerARig = createDeviceRig()
        val customerBRig = createDeviceRig()
        val branch2ManagerRig = createDeviceRig()
        val store2OwnerRig = createDeviceRig()

        val customerA = registerCustomerOnRig(
            rig = customerARig,
            fullName = "عميل الفرع الأول",
            email = "cust_a_iso@fresh.sa",
            phone = "0556001102"
        )
        customerARig.productRepository.refreshProducts()
        customerARig.cartRepository.addToCart(CartItem.fromMillis(productId = 1, quantityMillis = 1000))
        val checkout = customerARig.cartViewModel.executeCheckout(customer = customerA, branchId = 1)
        assertTrue(checkout is CheckoutOutcome.Success)
        val orderId = (checkout as CheckoutOutcome.Success).order.id

        // 1. Branch 2 Manager (`manager_jed@fresh.sa`) cannot see or mutate Branch 1's order
        val mgr2Actor = loginUserOnRig(branch2ManagerRig, "manager_jed@fresh.sa", "ManagerJedPass123")
        val mgr2DefaultSync = branch2ManagerRig.storeManagementViewModel.syncStoreOrdersFromServer(actor = mgr2Actor)
        assertTrue(mgr2DefaultSync is OrderSyncOutcome.Success)
        assertEquals(0, (mgr2DefaultSync as OrderSyncOutcome.Success).orders.size)
        assertEquals(0, branch2ManagerRig.database.orderDao().getAllOrdersOnce().size)

        val mgr2Branch1Sync = branch2ManagerRig.storeManagementViewModel.syncStoreOrdersFromServer(
            actor = mgr2Actor,
            branchId = 1
        )
        assertTrue(mgr2Branch1Sync is OrderSyncOutcome.Failure)
        assertEquals(Integer.valueOf(403), (mgr2Branch1Sync as OrderSyncOutcome.Failure).httpStatusCode)

        val mgr2Transition = branch2ManagerRig.storeManagementViewModel.executeServerOrderStatusTransition(
            actor = mgr2Actor,
            orderId = orderId,
            targetServerStatus = "CONFIRMED"
        )
        assertTrue(mgr2Transition is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.UNAUTHORIZED,
            (mgr2Transition as OrderTransitionOutcome.Failure).reason
        )

        val mgr2Cancel = branch2ManagerRig.storeManagementViewModel.executeServerOrderStatusTransition(
            actor = mgr2Actor,
            orderId = orderId,
            targetServerStatus = "CANCELLED",
            reasonNote = "Cross-branch cancel attempt"
        )
        assertTrue(mgr2Cancel is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.UNAUTHORIZED,
            (mgr2Cancel as OrderTransitionOutcome.Failure).reason
        )

        // 2. Store 2 Owner (`owner2@other.sa`) cannot see or mutate Store 1 / Branch 1's order
        val owner2Actor = loginUserOnRig(store2OwnerRig, "owner2@other.sa", "Owner2Pass123")
        val owner2Sync = store2OwnerRig.storeManagementViewModel.syncStoreOrdersFromServer(
            actor = owner2Actor,
            branchId = 1
        )
        assertTrue(owner2Sync is OrderSyncOutcome.Failure)
        assertEquals(Integer.valueOf(403), (owner2Sync as OrderSyncOutcome.Failure).httpStatusCode)

        val owner2Transition = store2OwnerRig.storeManagementViewModel.executeServerOrderStatusTransition(
            actor = owner2Actor,
            orderId = orderId,
            targetServerStatus = "CONFIRMED"
        )
        assertTrue(owner2Transition is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.UNAUTHORIZED,
            (owner2Transition as OrderTransitionOutcome.Failure).reason
        )

        // 3. Customer B cannot see Customer A's order
        val customerB = registerCustomerOnRig(
            rig = customerBRig,
            fullName = "عميل آخر مستقل",
            email = "cust_b_iso@fresh.sa",
            phone = "0556001103"
        )
        val custBSync = customerBRig.cartViewModel.syncCustomerOrdersFromServer(actor = customerB)
        assertTrue(custBSync is OrderSyncOutcome.Success)
        assertEquals(0, (custBSync as OrderSyncOutcome.Success).orders.size)

        val custBDetailSync = customerBRig.cartViewModel.syncCustomerOrderDetailsFromServer(
            orderId = orderId,
            actor = customerB
        )
        assertTrue(custBDetailSync is OrderSyncOutcome.Failure)
        assertEquals(Integer.valueOf(403), (custBDetailSync as OrderSyncOutcome.Failure).httpStatusCode)

        // 4. Customer A cannot transition or cancel own order
        val custATransition = customerARig.orderRepository.transitionOrderStatusToServerStatus(
            orderId = orderId,
            toServerStatus = "CONFIRMED",
            actor = customerA
        )
        assertTrue(custATransition is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.UNAUTHORIZED,
            (custATransition as OrderTransitionOutcome.Failure).reason
        )
    }

    @Test
    fun test_03_full_valid_order_lifecycle_and_invalid_transitions_reflected_in_customer_app_and_room() = runBlocking {
        val customerRig = createDeviceRig()
        val ownerRig = createDeviceRig()
        val mgr1Rig = createDeviceRig()

        val customer = registerCustomerOnRig(
            rig = customerRig,
            fullName = "سلمان العميل",
            email = "salman_1d4@fresh.sa",
            phone = "0556001104"
        )
        customerRig.productRepository.refreshProducts()
        customerRig.cartRepository.addToCart(CartItem.fromMillis(productId = 1, quantityMillis = 2000))
        val checkout = customerRig.cartViewModel.executeCheckout(customer = customer, branchId = 1)
        assertTrue(checkout is CheckoutOutcome.Success)
        val orderId = (checkout as CheckoutOutcome.Success).order.id

        val ownerActor = loginUserOnRig(ownerRig, "owner@fresh.sa", "OwnerPass123")
        val mgr1Actor = loginUserOnRig(mgr1Rig, "manager@fresh.sa", "ManagerPass123")
        ownerRig.storeManagementViewModel.syncStoreOrdersFromServer(actor = ownerActor, branchId = 1)
        mgr1Rig.storeManagementViewModel.syncStoreOrdersFromServer(actor = mgr1Actor)

        // 1. Invalid transitions from PENDING (`PROCESSING`, `COMPLETED`) are rejected by server & keep Room at PENDING
        for (invalidTarget in listOf("PROCESSING", "COMPLETED")) {
            val badOutcome = ownerRig.storeManagementViewModel.executeServerOrderStatusTransition(
                actor = ownerActor,
                orderId = orderId,
                targetServerStatus = invalidTarget
            )
            assertTrue("Expected failure for PENDING -> $invalidTarget", badOutcome is OrderTransitionOutcome.Failure)
            assertEquals(
                OrderTransitionFailureReason.INVALID_STATUS_TRANSITION,
                (badOutcome as OrderTransitionOutcome.Failure).reason
            )
            assertEquals("PENDING", ownerRig.database.orderDao().getOrderById(orderId)?.status)
        }

        // 2. Step 1: Branch 1 Manager transitions PENDING -> CONFIRMED
        val confirmOutcome = mgr1Rig.storeManagementViewModel.executeOrderStatusTransition(
            actor = mgr1Actor,
            orderId = orderId,
            targetStatus = OrderStatus.CONFIRMED,
            reasonNote = "تم تأكيد الطلب من مدير الفرع"
        )
        assertTrue("Expected PENDING -> CONFIRMED success but got $confirmOutcome", confirmOutcome is OrderTransitionOutcome.Success)
        assertEquals("CONFIRMED", mgr1Rig.database.orderDao().getOrderById(orderId)?.status)

        // Customer syncs order details and observes CONFIRMED + updated status_history in Room
        val custSync1 = customerRig.cartViewModel.syncCustomerOrderDetailsFromServer(
            orderId = orderId,
            actor = customer
        )
        assertTrue(custSync1 is OrderSyncOutcome.Success)
        val custOrderAfterConfirm = customerRig.cartViewModel.getCustomerOrderDetailsOnce(
            orderId = orderId,
            actor = customer
        )
        assertNotNull(custOrderAfterConfirm)
        assertEquals("CONFIRMED", custOrderAfterConfirm!!.order.status)
        assertEquals("تم تأكيد الطلب (CONFIRMED)", custOrderAfterConfirm.order.displayStatusArabic)
        assertEquals(2, custOrderAfterConfirm.statusHistory.size)
        assertEquals("CONFIRMED", custOrderAfterConfirm.statusHistory.last().toStatus)

        // 3. Step 2: Store Owner transitions CONFIRMED -> PROCESSING
        ownerRig.storeManagementViewModel.syncStoreOrdersFromServer(actor = ownerActor, branchId = 1)
        val processingOutcome = ownerRig.storeManagementViewModel.executeOrderStatusTransition(
            actor = ownerActor,
            orderId = orderId,
            targetStatus = OrderStatus.PROCESSING,
            reasonNote = "بدء تجهيز الطلب في المستودع"
        )
        assertTrue("Expected CONFIRMED -> PROCESSING success but got $processingOutcome", processingOutcome is OrderTransitionOutcome.Success)
        assertEquals("PROCESSING", ownerRig.database.orderDao().getOrderById(orderId)?.status)

        // Customer syncs order list (`GET /api/v1/orders/`) and observes PROCESSING in Room
        val custSync2 = customerRig.cartViewModel.syncCustomerOrdersFromServer(actor = customer)
        assertTrue(custSync2 is OrderSyncOutcome.Success)
        val custOrderAfterProcessing = customerRig.cartViewModel.getCustomerOrderDetailsOnce(
            orderId = orderId,
            actor = customer
        )
        assertEquals("PROCESSING", custOrderAfterProcessing!!.order.status)
        assertEquals("قيد التجهيز (PROCESSING)", custOrderAfterProcessing.order.displayStatusArabic)
        assertEquals(3, custOrderAfterProcessing.statusHistory.size)

        // 4. Step 3: Store Owner transitions PROCESSING -> COMPLETED (mapped via OrderStatus.DELIVERED -> "COMPLETED")
        val completedOutcome = ownerRig.storeManagementViewModel.executeOrderStatusTransition(
            actor = ownerActor,
            orderId = orderId,
            targetStatus = OrderStatus.DELIVERED,
            reasonNote = "تم تسليم الطلب للعميل"
        )
        assertTrue("Expected PROCESSING -> COMPLETED success but got $completedOutcome", completedOutcome is OrderTransitionOutcome.Success)
        assertEquals("COMPLETED", ownerRig.database.orderDao().getOrderById(orderId)?.status)

        // Customer syncs order details and observes COMPLETED + full 4-step history
        val custSync3 = customerRig.cartViewModel.syncCustomerOrderDetailsFromServer(
            orderId = orderId,
            actor = customer
        )
        assertTrue(custSync3 is OrderSyncOutcome.Success)
        val custOrderAfterCompleted = customerRig.cartViewModel.getCustomerOrderDetailsOnce(
            orderId = orderId,
            actor = customer
        )
        assertEquals("COMPLETED", custOrderAfterCompleted!!.order.status)
        assertEquals("مكتمل (COMPLETED)", custOrderAfterCompleted.order.displayStatusArabic)
        assertTrue(custOrderAfterCompleted.order.allowedNextOrderStatuses().isEmpty())
        assertFalse(custOrderAfterCompleted.order.canOrderBeCancelled)
        assertEquals(
            listOf(
                null to "PENDING",
                "PENDING" to "CONFIRMED",
                "CONFIRMED" to "PROCESSING",
                "PROCESSING" to "COMPLETED"
            ),
            custOrderAfterCompleted.statusHistory.map { it.fromStatus to it.toStatus }
        )

        // 5. Attempting to cancel a COMPLETED order is rejected with COMPLETED_ORDER_CANNOT_BE_CANCELLED
        val cancelCompleted = ownerRig.storeManagementViewModel.executeOrderStatusTransition(
            actor = ownerActor,
            orderId = orderId,
            targetStatus = OrderStatus.CANCELLED,
            reasonNote = "محاولة إلغاء بعد التسليم"
        )
        assertTrue(cancelCompleted is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.COMPLETED_ORDER_CANNOT_BE_CANCELLED,
            (cancelCompleted as OrderTransitionOutcome.Failure).reason
        )
    }

    @Test
    fun test_04_store_owner_cancellation_restores_stock_once_and_syncs_cancelled_status_to_customer() = runBlocking {
        val customerRig = createDeviceRig()
        val ownerRig = createDeviceRig()

        val customer = registerCustomerOnRig(
            rig = customerRig,
            fullName = "فاطمة العميلة",
            email = "fatima_1d4@fresh.sa",
            phone = "0556001105"
        )
        customerRig.productRepository.refreshProducts()
        val initialStockMillis = customerRig.database.productDao().getProductById(1)!!.effectiveStockMillis
        assertTrue("Expected positive initial stock", initialStockMillis >= 25000)

        // Customer creates an order for 3000 milli (3 kg) of Product 1 -> cached stock decreases by 3000 milli
        customerRig.cartRepository.addToCart(CartItem.fromMillis(productId = 1, quantityMillis = 3000))
        val checkout = customerRig.cartViewModel.executeCheckout(customer = customer, branchId = 1)
        assertTrue(checkout is CheckoutOutcome.Success)
        val orderId = (checkout as CheckoutOutcome.Success).order.id
        val postCheckoutStockMillis = initialStockMillis - 3000
        assertEquals(
            Integer.valueOf(postCheckoutStockMillis),
            customerRig.database.productDao().getProductById(1)?.effectiveStockMillis
        )

        // Store Owner syncs products and orders (setting initial local cached stock to postCheckoutStockMillis to mirror post-checkout state), then cancels the order
        val ownerActor = loginUserOnRig(ownerRig, "owner@fresh.sa", "OwnerPass123")
        ownerRig.productRepository.refreshProducts()
        val ownerProd1 = ownerRig.database.productDao().getProductById(1)!!
        ownerRig.database.productDao().insert(ownerProd1.withUpdatedStockMillis(postCheckoutStockMillis))
        ownerRig.storeManagementViewModel.syncStoreOrdersFromServer(actor = ownerActor, branchId = 1)
        assertEquals(
            Integer.valueOf(postCheckoutStockMillis),
            ownerRig.database.productDao().getProductById(1)?.effectiveStockMillis
        )

        val cancelOutcome = ownerRig.storeManagementViewModel.executeOrderStatusTransition(
            actor = ownerActor,
            orderId = orderId,
            targetStatus = OrderStatus.CANCELLED,
            reasonNote = "إلغاء الطلب وإرجاع المخزون"
        )
        assertTrue("Expected cancellation success but got $cancelOutcome", cancelOutcome is OrderTransitionOutcome.Success)
        val cancelSuccess = cancelOutcome as OrderTransitionOutcome.Success
        val cancelledOrder = cancelSuccess.updatedOrder
        assertEquals("CANCELLED", cancelledOrder.status)
        assertEquals(1, cancelSuccess.restoredStockAdjustments.size)
        assertEquals(3000, cancelSuccess.restoredStockAdjustments.first().quantityDeltaMillis)

        // Verify Product 1 stock restored once in Store Owner Room (postCheckoutStockMillis -> initialStockMillis)
        assertEquals(
            Integer.valueOf(initialStockMillis),
            ownerRig.database.productDao().getProductById(1)?.effectiveStockMillis
        )

        // Attempting a second cancellation is rejected with ALREADY_CANCELLED and never restores stock twice
        val secondCancel = ownerRig.storeManagementViewModel.executeServerOrderStatusTransition(
            actor = ownerActor,
            orderId = orderId,
            targetServerStatus = "CANCELLED",
            reasonNote = "محاولة إلغاء مكررة",
            idempotencyKey = "cancel-second-attempt-key"
        )
        assertTrue(secondCancel is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.ALREADY_CANCELLED,
            (secondCancel as OrderTransitionOutcome.Failure).reason
        )
        assertEquals(
            Integer.valueOf(initialStockMillis),
            ownerRig.database.productDao().getProductById(1)?.effectiveStockMillis
        )

        // Customer syncs order details -> observes CANCELLED status and terminal state
        val custSync = customerRig.cartViewModel.syncCustomerOrderDetailsFromServer(
            orderId = orderId,
            actor = customer
        )
        assertTrue(custSync is OrderSyncOutcome.Success)

        val custOrder = customerRig.cartViewModel.getCustomerOrderDetailsOnce(orderId = orderId, actor = customer)
        assertNotNull(custOrder)
        assertEquals("CANCELLED", custOrder!!.order.status)
        assertEquals("ملغي (CANCELLED)", custOrder.order.displayStatusArabic)
        assertFalse(custOrder.order.canOrderBeCancelled)
        assertEquals(
            listOf(null to "PENDING", "PENDING" to "CANCELLED"),
            custOrder.statusHistory.map { it.fromStatus to it.toStatus }
        )
    }

    @Test
    fun test_05_network_outage_during_sync_and_transition_preserves_room_cache_and_recovers_on_retry() = runBlocking {
        val customerRig = createDeviceRig()
        val ownerRig = createDeviceRig()

        val customer = registerCustomerOnRig(
            rig = customerRig,
            fullName = "نواف العميل",
            email = "nawaf_1d4@fresh.sa",
            phone = "0556001106"
        )
        customerRig.productRepository.refreshProducts()
        customerRig.cartRepository.addToCart(CartItem.fromMillis(productId = 1, quantityMillis = 1000))
        val checkout = customerRig.cartViewModel.executeCheckout(customer = customer, branchId = 1)
        assertTrue(checkout is CheckoutOutcome.Success)
        val orderId = (checkout as CheckoutOutcome.Success).order.id

        val ownerActor = loginUserOnRig(ownerRig, "owner@fresh.sa", "OwnerPass123")
        ownerRig.storeManagementViewModel.syncStoreOrdersFromServer(actor = ownerActor, branchId = 1)
        assertEquals("PENDING", ownerRig.database.orderDao().getOrderById(orderId)?.status)

        // Simulate network drop on both rigs
        ownerRig.networkOutageFlag.set(true)
        customerRig.networkOutageFlag.set(true)

        // 1. Store Owner attempts status transition during network outage -> fails with NETWORK_ERROR, Room remains PENDING
        val offlineTransition = ownerRig.storeManagementViewModel.executeOrderStatusTransition(
            actor = ownerActor,
            orderId = orderId,
            targetStatus = OrderStatus.CONFIRMED,
            reasonNote = "محاولة تأكيد أثناء انقطاع الشبكة"
        )
        assertTrue(offlineTransition is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.NETWORK_ERROR,
            (offlineTransition as OrderTransitionOutcome.Failure).reason
        )
        assertEquals(
            "Room must never optimistically mark an order CONFIRMED when the server transition failed",
            "PENDING",
            ownerRig.database.orderDao().getOrderById(orderId)?.status
        )

        // 2. Customer attempts order sync during network outage -> fails with NETWORK_ERROR, cached Room order preserved
        val offlineCustSync = customerRig.cartViewModel.syncCustomerOrdersFromServer(actor = customer)
        assertTrue(offlineCustSync is OrderSyncOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.NETWORK_ERROR,
            (offlineCustSync as OrderSyncOutcome.Failure).reason
        )
        assertNotNull(customerRig.cartViewModel.orderSyncError.value)
        assertEquals("PENDING", customerRig.database.orderDao().getOrderById(orderId)?.status)

        // 3. Restore network connectivity -> Store Owner retries transition and Customer retries sync
        ownerRig.networkOutageFlag.set(false)
        customerRig.networkOutageFlag.set(false)

        val retryTransition = ownerRig.storeManagementViewModel.executeOrderStatusTransition(
            actor = ownerActor,
            orderId = orderId,
            targetStatus = OrderStatus.CONFIRMED,
            reasonNote = "تأكيد بعد عودة الاتصال"
        )
        assertTrue(retryTransition is OrderTransitionOutcome.Success)
        assertEquals("CONFIRMED", ownerRig.database.orderDao().getOrderById(orderId)?.status)

        val retryCustSync = customerRig.cartViewModel.syncCustomerOrdersFromServer(actor = customer)
        assertTrue(retryCustSync is OrderSyncOutcome.Success)
        assertEquals("CONFIRMED", customerRig.database.orderDao().getOrderById(orderId)?.status)
    }
}
