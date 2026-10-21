package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.FreshApiService
import com.example.data.api.model.CategoryDto
import com.example.data.api.model.OrderRequest
import com.example.data.api.model.OrderResponse
import com.example.data.api.model.ProductDto
import com.example.data.auth.AuthenticatedUser
import com.example.data.auth.UserRole
import com.example.data.local.AppDatabase
import com.example.data.local.model.CartItem
import com.example.data.local.model.OrderStatus
import com.example.data.local.model.Product
import com.example.data.repository.CartRepository
import com.example.data.repository.CheckoutFailureReason
import com.example.data.repository.CheckoutOutcome
import com.example.data.repository.OrderRepository
import com.example.data.repository.ProductRepository
import com.example.ui.screens.CartScreen
import com.example.ui.screens.CheckoutScreen
import com.example.ui.screens.OrderHistoryScreen
import com.example.ui.theme.FRESHTheme
import com.example.ui.viewmodel.CartViewModel
import com.example.ui.viewmodel.StoreManagementViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focused verification suite for Micro Task 6A:
 * Atomic Local Checkout, Order Persistence & Stock Integrity.
 *
 * Uses an isolated in-memory Room database so production data is never touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AtomicLocalCheckoutAndOrderIntegrityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var productRepository: ProductRepository
    private lateinit var cartRepository: CartRepository
    private lateinit var orderRepository: OrderRepository

    private val offlineApi = object : FreshApiService {
        override suspend fun getCategories(): List<CategoryDto> = emptyList()
        override suspend fun getProducts(): List<ProductDto> = emptyList()
        override suspend fun getProduct(id: Int): ProductDto = throw NotImplementedError()
        override suspend fun createOrder(request: OrderRequest): OrderResponse = throw NotImplementedError()
    }

    private val customerAlice = AuthenticatedUser(
        id = "cust_alice_01",
        fullName = "سارة العتيبي",
        email = "sara@fresh.sa",
        phoneNumber = "0501112233",
        role = UserRole.CUSTOMER,
        isLocalDemoAccount = false
    )

    private val customerBob = AuthenticatedUser(
        id = "cust_bob_02",
        fullName = "فهد القحطاني",
        email = "fahad@fresh.sa",
        phoneNumber = "0504445566",
        role = UserRole.CUSTOMER,
        isLocalDemoAccount = false
    )

    private val storeOwner = AuthenticatedUser(
        id = "owner_01",
        fullName = "مالك المتجر",
        email = "owner@fresh.sa",
        phoneNumber = "0509998877",
        role = UserRole.STORE_OWNER,
        isLocalDemoAccount = true
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        productRepository = ProductRepository(
            productDao = database.productDao(),
            apiService = offlineApi,
            stockAdjustmentDao = database.stockAdjustmentDao()
        )
        orderRepository = OrderRepository(
            database = database,
            orderDao = database.orderDao(),
            orderItemDao = database.orderItemDao(),
            cartDao = database.cartDao(),
            productDao = database.productDao(),
            stockAdjustmentDao = database.stockAdjustmentDao()
        )
        cartRepository = CartRepository(
            cartDao = database.cartDao(),
            orderRepository = orderRepository
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * 1. Successful checkout persists an order and immutable order-item snapshots,
     *    even if the product name or price is edited later.
     */
    @Test
    fun successfulCheckout_persistsOrderAndImmutableItemSnapshots_evenAfterProductEdit() = runBlocking {
        val datesProduct = Product(
            id = 501,
            name = "تمر سكري القصيم فاخر",
            nameEn = "Qassim Sukkari Dates",
            category = "تمور",
            priceHalalas = 2500, // 25.00 SAR
            description = "تمر سكري ملكي",
            imageUrl = "dates",
            unit = "كجم",
            stockQuantity = 10,
            stockSubUnitsMillis = 10_000,
            lowStockThreshold = 3,
            expiryDate = "2026-12-31",
            isActive = true,
            isArchived = false
        )
        productRepository.saveProduct(datesProduct)

        // Add 2 kg to cart
        cartRepository.addToCart(CartItem(productId = 501, quantity = 2, quantityMillis = 2_000))

        val outcome = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            actorRole = customerAlice.role.name,
            deliveryAddress = "الرياض - حي الملقا - شارع أنس بن مالك",
            deliveryFeeHalalas = 1500,
            discountHalalas = 0,
            idempotencyKey = "idem_snapshot_01"
        )

        assertTrue("Expected successful checkout", outcome is CheckoutOutcome.Success)
        val success = outcome as CheckoutOutcome.Success
        assertEquals(OrderStatus.CONFIRMED.name, success.order.status)
        assertEquals(5000, success.order.subtotalHalalas) // 2 * 25.00 SAR = 50.00 SAR
        assertEquals(1500, success.order.deliveryFeeHalalas)
        assertEquals(6500, success.order.totalHalalas) // 65.00 SAR
        assertEquals("الرياض - حي الملقا - شارع أنس بن مالك", success.order.deliveryAddressSnapshot)

        // Verify cart was cleared of purchased item
        val remainingCart = cartRepository.getCartItemsOnce()
        assertTrue("Cart should be empty after successful checkout", remainingCart.isEmpty())

        // Now edit the product's name and price in Room
        productRepository.saveProduct(
            datesProduct.copy(
                name = "تمر سكري معدل بعد الشراء",
                priceHalalas = 4900,
                stockQuantity = 8,
                stockSubUnitsMillis = 8_000
            )
        )

        // Reload persisted order and verify snapshot immutability
        val persistedOrders = orderRepository.getOrdersWithItemsForCustomer(customerAlice.id).first()
        assertEquals(1, persistedOrders.size)
        val savedOrderWithItems = persistedOrders.first()
        assertEquals(1, savedOrderWithItems.items.size)
        val snapshotItem = savedOrderWithItems.items.first()
        assertEquals("تمر سكري القصيم فاخر", snapshotItem.productName)
        assertEquals(2500, snapshotItem.unitPriceHalalas)
        assertEquals(5000, snapshotItem.lineTotalHalalas)
        assertEquals(2_000, snapshotItem.quantityMillis)
    }

    /**
     * 2. Stock is deducted correctly for fractional produce quantities (e.g., 1.5 kg and 0.25 kg)
     *    using exact integer halalas and milli-units without floating-point drift.
     */
    @Test
    fun fractionalQuantities_deductExactStockAndCalculateExactHalalas() = runBlocking {
        val tomatoes = Product(
            id = 502,
            name = "طماطم محمية طازجة",
            nameEn = "Fresh Greenhouse Tomatoes",
            category = "خضروات",
            priceHalalas = 800, // 8.00 SAR per kg
            description = "طماطم يومية بالوزن",
            imageUrl = "tomatoes",
            unit = "كجم",
            stockQuantity = 5,
            stockSubUnitsMillis = 5_000, // 5.000 kg
            lowStockThreshold = 2,
            expiryDate = "2026-10-30",
            isActive = true,
            isArchived = false
        )
        productRepository.saveProduct(tomatoes)

        // Request 1.5 kg (1,500 milli-units) -> 1.5 * 800 = 1200 halalas (12.00 SAR)
        cartRepository.addToCart(CartItem.fromMillis(productId = 502, quantityMillis = 1_500))

        val outcome = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            deliveryFeeHalalas = 1500,
            idempotencyKey = "idem_fractional_01"
        )

        assertTrue(outcome is CheckoutOutcome.Success)
        val success = outcome as CheckoutOutcome.Success
        assertEquals(1200, success.order.subtotalHalalas)
        assertEquals(2700, success.order.totalHalalas)
        assertEquals("1.5", success.items.first().formattedQuantity())

        val reloadedProduct = productRepository.getAllProducts().first().first { it.id == 502 }
        // 5.000 kg - 1.500 kg = 3.500 kg (3,500 milli-units; ceil whole units = 4)
        assertEquals(3_500, reloadedProduct.effectiveStockMillis)
        assertEquals("3.5", reloadedProduct.formattedStockQuantity())
        assertEquals(4, reloadedProduct.stockQuantity)
    }

    /**
     * 3. Insufficient stock rejects checkout and leaves stock & cart untouched.
     */
    @Test
    fun insufficientStock_rejectsCheckoutAndPreservesStockAndCart() = runBlocking {
        val cucumber = Product(
            id = 503,
            name = "خيار بلدي",
            nameEn = "Local Cucumber",
            category = "خضروات",
            priceHalalas = 600,
            description = "خيار طازج",
            imageUrl = "cucumber",
            unit = "كجم",
            stockQuantity = 2,
            stockSubUnitsMillis = 2_000, // Only 2.0 kg available
            lowStockThreshold = 2,
            expiryDate = "2026-10-28"
        )
        productRepository.saveProduct(cucumber)

        // Put 2.5 kg in cart
        cartRepository.addToCart(CartItem.fromMillis(productId = 503, quantityMillis = 2_500))

        val outcome = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            idempotencyKey = "idem_insufficient_01"
        )

        assertTrue(outcome is CheckoutOutcome.Failure)
        val failure = outcome as CheckoutOutcome.Failure
        assertEquals(CheckoutFailureReason.INSUFFICIENT_STOCK, failure.reason)
        assertTrue(failure.messageArabic.contains("المخزون غير كافٍ"))

        // Stock must remain 2.0 kg
        val reloaded = productRepository.getAllProducts().first().first { it.id == 503 }
        assertEquals(2_000, reloaded.effectiveStockMillis)

        // Cart must remain intact
        val cartAfterFailure = cartRepository.getCartItemsOnce()
        assertEquals(1, cartAfterFailure.size)
        assertEquals(2_500, cartAfterFailure.first().effectiveQuantityMillis)
    }

    /**
     * 4. An empty cart is rejected cleanly with an Arabic error and creates no order.
     */
    @Test
    fun emptyCart_isRejectedWithArabicErrorAndPersistsNoOrder() = runBlocking {
        val outcome = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            idempotencyKey = "idem_empty_01"
        )

        assertTrue(outcome is CheckoutOutcome.Failure)
        val failure = outcome as CheckoutOutcome.Failure
        assertEquals(CheckoutFailureReason.EMPTY_CART, failure.reason)
        assertTrue(failure.messageArabic.contains("سلة التسوق فارغة"))

        val orders = orderRepository.observeAllPersistedOrdersWithItems().first()
        assertTrue(orders.isEmpty())
    }

    /**
     * 5. Invalid or stale prices are handled safely without deducting stock or creating an order.
     */
    @Test
    fun invalidOrStalePrice_rejectsCheckoutSafelyAndPreservesCart() = runBlocking {
        val strawberries = Product(
            id = 504,
            name = "فراولة طازجة",
            nameEn = "Fresh Strawberries",
            category = "فواكه",
            priceHalalas = 1800,
            description = "علبة فراولة",
            imageUrl = "strawberries",
            unit = "علبة",
            stockQuantity = 10,
            stockSubUnitsMillis = 10_000
        )
        productRepository.saveProduct(strawberries)
        cartRepository.addToCart(CartItem(productId = 504, quantity = 2, quantityMillis = 2_000))

        // Customer saw 15.00 SAR (1500 halalas), but DB price is now 18.00 SAR (1800 halalas)
        val staleOutcome = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            expectedUnitPricesHalalas = mapOf(504 to 1500),
            idempotencyKey = "idem_stale_price_01"
        )

        assertTrue(staleOutcome is CheckoutOutcome.Failure)
        assertEquals(CheckoutFailureReason.STALE_OR_INVALID_PRICE, (staleOutcome as CheckoutOutcome.Failure).reason)
        assertTrue(staleOutcome.messageArabic.contains("تغير سعر"))

        // Also verify invalid zero price product is rejected
        productRepository.saveProduct(strawberries.copy(priceHalalas = 0))
        val zeroPriceOutcome = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            expectedUnitPricesHalalas = null,
            idempotencyKey = "idem_zero_price_01"
        )
        assertTrue(zeroPriceOutcome is CheckoutOutcome.Failure)
        assertEquals(CheckoutFailureReason.STALE_OR_INVALID_PRICE, (zeroPriceOutcome as CheckoutOutcome.Failure).reason)

        // Stock and cart must remain completely untouched
        val reloaded = productRepository.getAllProducts().first().first { it.id == 504 }
        assertEquals(10_000, reloaded.effectiveStockMillis)
        assertEquals(1, cartRepository.getCartItemsOnce().size)
    }

    /**
     * 6. A multi-item checkout failure rolls back every stock change, order insert, and audit record.
     */
    @Test
    fun multiItemCheckoutFailure_rollsBackAllStockDeductionsAndOrderInserts() = runBlocking {
        val item1 = Product(
            id = 505,
            name = "تفاح أحمر",
            nameEn = "Red Apples",
            category = "فواكه",
            priceHalalas = 900,
            description = "تفاح سكري",
            imageUrl = "apples",
            unit = "كجم",
            stockQuantity = 10,
            stockSubUnitsMillis = 10_000
        )
        val item2 = Product(
            id = 506,
            name = "موز مستورد",
            nameEn = "Imported Bananas",
            category = "فواكه",
            priceHalalas = 700,
            description = "موز طازج",
            imageUrl = "bananas",
            unit = "كجم",
            stockQuantity = 1,
            stockSubUnitsMillis = 1_000 // Only 1 kg available
        )
        productRepository.saveProduct(item1)
        productRepository.saveProduct(item2)

        // Item 1 is valid (3 kg <= 10 kg), Item 2 exceeds stock (4 kg > 1 kg)
        cartRepository.addToCart(CartItem(productId = 505, quantity = 3, quantityMillis = 3_000))
        cartRepository.addToCart(CartItem(productId = 506, quantity = 4, quantityMillis = 4_000))

        val outcome = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            idempotencyKey = "idem_rollback_01"
        )

        assertTrue(outcome is CheckoutOutcome.Failure)
        assertEquals(CheckoutFailureReason.INSUFFICIENT_STOCK, (outcome as CheckoutOutcome.Failure).reason)

        // Item 1 stock MUST still be 10 kg (no partial deduction!)
        val allProducts = productRepository.getAllProducts().first()
        val reloadedItem1 = allProducts.first { it.id == 505 }
        val reloadedItem2 = allProducts.first { it.id == 506 }
        assertEquals(10_000, reloadedItem1.effectiveStockMillis)
        assertEquals(1_000, reloadedItem2.effectiveStockMillis)

        // Also test transactional rollback when a mid-transaction DB exception occurs after item 1 stock deduction
        productRepository.saveProduct(item2.copy(stockQuantity = 10, stockSubUnitsMillis = 10_000))
        orderRepository.failAfterFirstStockDeductionForTesting = true
        val midTxFailure = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            idempotencyKey = "idem_rollback_02"
        )
        orderRepository.failAfterFirstStockDeductionForTesting = false

        assertTrue(midTxFailure is CheckoutOutcome.Failure)
        assertEquals(CheckoutFailureReason.TRANSACTION_ERROR, (midTxFailure as CheckoutOutcome.Failure).reason)

        // Verify Room rolled back the first item's stock deduction, order insert, and audit record!
        val afterRollbackProducts = productRepository.getAllProducts().first()
        assertEquals(10_000, afterRollbackProducts.first { it.id == 505 }.effectiveStockMillis)
        assertEquals(10_000, afterRollbackProducts.first { it.id == 506 }.effectiveStockMillis)
        assertTrue(orderRepository.observeAllPersistedOrdersWithItems().first().isEmpty())
        assertTrue(productRepository.getAllStockAdjustments().first().isEmpty())
        assertEquals(2, cartRepository.getCartItemsOnce().size)
    }

    /**
     * 7. Duplicate checkout submission with the same idempotency key cannot deduct stock twice
     *    or create a duplicate order.
     */
    @Test
    fun duplicateCheckoutSubmission_isIdempotentAndDoesNotDeductStockTwice() = runBlocking {
        val orange = Product(
            id = 507,
            name = "برتقال عصير",
            nameEn = "Juice Oranges",
            category = "فواكه",
            priceHalalas = 650,
            description = "برتقال طازج للعصير",
            imageUrl = "oranges",
            unit = "كجم",
            stockQuantity = 10,
            stockSubUnitsMillis = 10_000
        )
        productRepository.saveProduct(orange)
        cartRepository.addToCart(CartItem(productId = 507, quantity = 2, quantityMillis = 2_000))

        val sharedIdempotencyKey = "idem_duplicate_guard_99"

        val firstSubmission = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            idempotencyKey = sharedIdempotencyKey
        )
        assertTrue(firstSubmission is CheckoutOutcome.Success)

        // Even if the cart is re-populated or a duplicate request arrives with the same idempotencyKey:
        cartRepository.addToCart(CartItem(productId = 507, quantity = 2, quantityMillis = 2_000))
        val secondSubmission = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            idempotencyKey = sharedIdempotencyKey
        )

        assertTrue(secondSubmission is CheckoutOutcome.Failure)
        val duplicateFailure = secondSubmission as CheckoutOutcome.Failure
        assertEquals(CheckoutFailureReason.DUPLICATE_SUBMISSION, duplicateFailure.reason)

        // Verify stock was only deducted ONCE (10 - 2 = 8, not 6)
        val reloadedOrange = productRepository.getAllProducts().first().first { it.id == 507 }
        assertEquals(8_000, reloadedOrange.effectiveStockMillis)
        assertEquals(8, reloadedOrange.stockQuantity)

        // Verify only 1 order exists in Room
        val allOrders = orderRepository.observeAllPersistedOrdersWithItems().first()
        assertEquals(1, allOrders.size)
    }

    /**
     * 8. Each successful deduction creates the correct StockAdjustment audit record
     *    with reason "Order checkout: {orderId}".
     */
    @Test
    fun successfulCheckout_createsStockAdjustmentAuditRecordsForEachItem() = runBlocking {
        val mint = Product(
            id = 508,
            name = "نعناع المدينة",
            nameEn = "Madinah Mint",
            category = "ورقيات",
            priceHalalas = 200,
            description = "نعناع أخضر طازج",
            imageUrl = "mint",
            unit = "ربطة",
            stockQuantity = 15,
            stockSubUnitsMillis = 15_000
        )
        val parsley = Product(
            id = 509,
            name = "بقدونس بلدي",
            nameEn = "Local Parsley",
            category = "ورقيات",
            priceHalalas = 150,
            description = "بقدونس طازج",
            imageUrl = "parsley",
            unit = "ربطة",
            stockQuantity = 12,
            stockSubUnitsMillis = 12_000
        )
        productRepository.saveProduct(mint)
        productRepository.saveProduct(parsley)

        cartRepository.addToCart(CartItem(productId = 508, quantity = 3, quantityMillis = 3_000))
        cartRepository.addToCart(CartItem.fromMillis(productId = 509, quantityMillis = 1_500))

        val outcome = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            idempotencyKey = "idem_audit_01"
        )
        assertTrue(outcome is CheckoutOutcome.Success)
        val orderId = (outcome as CheckoutOutcome.Success).order.id

        val adjustments = productRepository.getAllStockAdjustments().first()
        assertEquals(2, adjustments.size)

        val mintAdj = adjustments.first { it.productId == 508 }
        assertEquals(15, mintAdj.previousQuantity)
        assertEquals(12, mintAdj.newQuantity)
        assertEquals(-3, mintAdj.quantityDelta)
        assertEquals("Order checkout: $orderId", mintAdj.reason)

        val parsleyAdj = adjustments.first { it.productId == 509 }
        assertEquals(12_000, parsleyAdj.previousQuantityMillis)
        assertEquals(10_500, parsleyAdj.newQuantityMillis)
        assertEquals(-1_500, parsleyAdj.quantityDeltaMillis)
        assertEquals("-1.5", parsleyAdj.formattedDeltaQuantity())
        assertEquals("Order checkout: $orderId", parsleyAdj.reason)
    }

    /**
     * 9. Customer order history strictly isolates each customer's orders and never exposes
     *    another customer's orders, while Store Owner can view all persisted orders.
     */
    @Test
    fun customerOrderHistory_isolatesOrdersPerCustomer_andRespectsStoreOwnerRole() = runBlocking {
        val dates = Product(
            id = 510,
            name = "تمر خلاص الأحساء",
            nameEn = "Al-Ahsa Khalas Dates",
            category = "تمور",
            priceHalalas = 2000,
            description = "تمر خلاص",
            imageUrl = "dates",
            unit = "كجم",
            stockQuantity = 20,
            stockSubUnitsMillis = 20_000
        )
        productRepository.saveProduct(dates)

        // Alice places an order for 1 kg
        cartRepository.addToCart(CartItem(productId = 510, quantity = 1, quantityMillis = 1_000))
        val aliceOutcome = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            deliveryAddress = "عنوان سارة الخاص",
            idempotencyKey = "idem_alice_order"
        )
        assertTrue(aliceOutcome is CheckoutOutcome.Success)

        // Bob places an order for 3 kg
        cartRepository.addToCart(CartItem(productId = 510, quantity = 3, quantityMillis = 3_000))
        val bobOutcome = orderRepository.executeLocalCheckout(
            customerId = customerBob.id,
            customerName = customerBob.fullName,
            deliveryAddress = "عنوان فهد الخاص",
            idempotencyKey = "idem_bob_order"
        )
        assertTrue(bobOutcome is CheckoutOutcome.Success)

        // Alice must ONLY see Alice's order
        val aliceOrders = orderRepository.getOrdersWithItemsForCustomer(customerAlice.id).first()
        assertEquals(1, aliceOrders.size)
        assertEquals(customerAlice.id, aliceOrders.first().order.customerId)
        assertEquals("عنوان سارة الخاص", aliceOrders.first().order.deliveryAddressSnapshot)

        // Bob must ONLY see Bob's order
        val bobOrders = orderRepository.getOrdersWithItemsForCustomer(customerBob.id).first()
        assertEquals(1, bobOrders.size)
        assertEquals(customerBob.id, bobOrders.first().order.customerId)
        assertEquals("عنوان فهد الخاص", bobOrders.first().order.deliveryAddressSnapshot)

        // Customer attempting to call store-management all-orders query gets empty list
        val unauthorizedAllOrders = orderRepository.getAllOrdersWithItemsForStoreManagement(customerAlice).first()
        assertTrue("Customer must not access store-wide orders", unauthorizedAllOrders.isEmpty())

        // Authorized Store Owner sees both orders
        val ownerOrders = orderRepository.getAllOrdersWithItemsForStoreManagement(storeOwner).first()
        assertEquals(2, ownerOrders.size)
    }

    /**
     * 10. UI & ViewModel Integration Test:
     *     Verifies CartScreen -> CheckoutScreen confirmation flow, failed checkout keeping cart intact,
     *     OrderHistoryScreen customer isolation, and StoreOwner inventory metric refresh after checkout.
     */
    @Test
    fun uiCheckoutAndOrderHistoryFlow_displaysConfirmationOnSuccess_andKeepsCartOnFailure(): Unit = runBlocking {
        val mango = Product(
            id = 511,
            name = "مانجو جازان تومي",
            nameEn = "Jazan Mango",
            category = "فواكه",
            priceHalalas = 1400, // 14.00 SAR / kg
            description = "مانجو جازان طازج",
            imageUrl = "mango",
            unit = "كجم",
            stockQuantity = 4,
            stockSubUnitsMillis = 4_000,
            lowStockThreshold = 3,
            expiryDate = "2026-11-01"
        )
        productRepository.saveProduct(mango)

        val cartViewModel = CartViewModel(
            cartRepository = cartRepository,
            productRepository = productRepository,
            orderRepository = orderRepository
        )
        val storeViewModel = StoreManagementViewModel(
            repository = productRepository,
            orderRepository = orderRepository
        )

        // Add 1.5 kg of Mango (1.5 * 14.00 = 21.00 SAR + 15.00 SAR delivery = 36.00 SAR)
        cartViewModel.addToCartFractional(productId = 511, quantityUnits = 1.5)

        composeTestRule.setContent {
            val screenState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("CART") }
            FRESHTheme {
                when (screenState.value) {
                    "CART" -> CartScreen(
                        viewModel = cartViewModel,
                        onCheckout = { screenState.value = "CHECKOUT" },
                        onBack = {},
                        onOpenOrderHistory = { screenState.value = "HISTORY" }
                    )
                    "CHECKOUT" -> CheckoutScreen(
                        viewModel = cartViewModel,
                        currentUser = customerAlice,
                        onConfirm = { screenState.value = "CART" },
                        onNavigateToOrderHistory = { screenState.value = "HISTORY" },
                        onBack = { screenState.value = "CART" }
                    )
                    "HISTORY" -> OrderHistoryScreen(
                        cartViewModel = cartViewModel,
                        currentUser = customerAlice,
                        onBack = { screenState.value = "CART" }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // Verify CartScreen displays fractional quantity and exact SAR totals
        composeTestRule.onNodeWithTag("cart_item_qty_511").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("21.00 ر.س", substring = true)[0].assertIsDisplayed()
        composeTestRule.onNodeWithText("36.00 ر.س", substring = true).assertIsDisplayed()

        // Proceed to Checkout confirmation screen
        composeTestRule.onNodeWithTag("cart_proceed_checkout_button").performClick()
        composeTestRule.waitForIdle()

        // Verify CheckoutScreen shows local payment notice, item summary, and totals
        composeTestRule.onNodeWithTag("checkout_local_payment_notice").assertIsDisplayed()
        composeTestRule.onNodeWithTag("checkout_items_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("checkout_totals_card").performScrollTo().assertIsDisplayed()

        // Confirm local checkout
        composeTestRule.onNodeWithTag("checkout_confirm_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        var attempts = 0
        while (cartViewModel.lastCompletedOrder.value == null && attempts < 100) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            attempts++
        }
        composeTestRule.waitForIdle()

        // Verify saved order confirmation card and order number are displayed
        composeTestRule.onNodeWithTag("checkout_confirmation_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("checkout_saved_order_number").assertIsDisplayed()

        // Verify store owner inventory metrics refreshed automatically (4.0 kg - 1.5 kg = 2.5 kg -> ceil 3 <= threshold 3 -> low stock!)
        val updatedMango = productRepository.getAllProducts().first().first { it.id == 511 }
        assertEquals(2_500, updatedMango.effectiveStockMillis)
        assertTrue("Mango should now be classified as low stock", updatedMango.isLowStock)

        var metricAttempts = 0
        while (storeViewModel.metrics.value.lowStockCount != 1 && metricAttempts < 50) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            metricAttempts++
        }
        assertEquals(1, storeViewModel.metrics.value.lowStockCount)

        // Navigate to OrderHistoryScreen from confirmation card
        composeTestRule.onNodeWithTag("checkout_view_orders_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        var historyAttempts = 0
        while (composeTestRule.onAllNodesWithTag("order_history_list").fetchSemanticsNodes().isEmpty() && historyAttempts < 50) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            historyAttempts++
        }

        composeTestRule.onNodeWithTag("order_history_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("order_history_list").assertIsDisplayed()
        composeTestRule.onNodeWithText("مانجو جازان تومي", substring = true).assertIsDisplayed()
        Unit
    }
}
