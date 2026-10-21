package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.example.data.auth.LocalAuthRepository
import com.example.data.auth.UserRole
import com.example.data.local.AppDatabase
import com.example.data.local.model.CartItem
import com.example.data.local.model.OrderStatus
import com.example.data.local.model.Product
import com.example.data.repository.CartRepository
import com.example.data.repository.CheckoutOutcome
import com.example.data.repository.OrderRepository
import com.example.data.repository.OrderTransitionFailureReason
import com.example.data.repository.OrderTransitionOutcome
import com.example.data.repository.ProductRepository
import com.example.ui.screens.OrderHistoryScreen
import com.example.ui.screens.StoreOwnerFoundationScreen
import com.example.ui.theme.FRESHTheme
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.CartViewModel
import com.example.ui.viewmodel.StoreManagementSection
import com.example.ui.viewmodel.StoreManagementViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Focused verification suite for Micro Task 6B:
 * Order Lifecycle, Owner Order Management & Inventory Consistency.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OrderLifecycleAndOwnerOrderManagementTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var productRepository: ProductRepository
    private lateinit var cartRepository: CartRepository
    private lateinit var orderRepository: OrderRepository
    private lateinit var authRepository: LocalAuthRepository

    private val offlineApi = object : FreshApiService {
        override suspend fun getCategories(): List<CategoryDto> = emptyList()
        override suspend fun getProducts(): List<ProductDto> = emptyList()
        override suspend fun getProduct(id: Int): ProductDto = throw NotImplementedError()
        override suspend fun createOrder(request: OrderRequest): OrderResponse = throw NotImplementedError()
    }

    private val customerAlice = AuthenticatedUser(
        id = "cust_alice_6b",
        fullName = "نورة السبيعي",
        email = "noura@fresh.sa",
        phoneNumber = "0501112233",
        role = UserRole.CUSTOMER,
        isLocalDemoAccount = false
    )

    private val customerBob = AuthenticatedUser(
        id = "cust_bob_6b",
        fullName = "خالد الدوسري",
        email = "khaled@fresh.sa",
        phoneNumber = "0504445566",
        role = UserRole.CUSTOMER,
        isLocalDemoAccount = false
    )

    private val storeOwner = AuthenticatedUser(
        id = "owner_6b",
        fullName = "عبدالله الراجحي",
        email = "owner@fresh-market.sa",
        phoneNumber = "0509998877",
        role = UserRole.STORE_OWNER,
        isLocalDemoAccount = true
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("order_lifecycle_6b_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        authRepository = LocalAuthRepository(prefs)

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
            productDao = database.productDao(),
            cartDao = database.cartDao(),
            orderDao = database.orderDao(),
            orderItemDao = database.orderItemDao(),
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
     * 1. Valid and invalid status transitions:
     *    Verifies explicit state machine rules (CONFIRMED_LOCAL -> PREPARING -> OUT_FOR_DELIVERY -> DELIVERED),
     *    rejects invalid transitions (e.g. CONFIRMED_LOCAL -> DELIVERED, OUT_FOR_DELIVERY -> PREPARING,
     *    DELIVERED -> PREPARING), and blocks unauthorized customer/guest transitions.
     */
    @Test
    fun orderLifecycle_enforcesValidTransitions_andRejectsInvalidOrUnauthorizedTransitions() = runBlocking {
        val dates = Product(
            id = 601,
            name = "تمر خلاص ملكي",
            nameEn = "Royal Khalas Dates",
            category = "تمور",
            priceHalalas = 2000,
            description = "تمر خلاص",
            imageUrl = "dates",
            unit = "علبة",
            stockQuantity = 10,
            stockSubUnitsMillis = 10_000
        )
        productRepository.saveProduct(dates)
        cartRepository.addToCart(CartItem(productId = 601, quantity = 2, quantityMillis = 2_000))

        val checkout = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            idempotencyKey = "idem_lifecycle_601"
        ) as CheckoutOutcome.Success
        val orderId = checkout.order.id
        assertEquals(OrderStatus.CONFIRMED_LOCAL, checkout.order.orderStatus)

        // Customer or unauthenticated user cannot transition order status
        val unauthorizedCustomerAttempt = orderRepository.transitionOrderStatus(
            orderId = orderId,
            targetStatus = OrderStatus.PREPARING,
            actor = customerAlice
        )
        assertTrue(unauthorizedCustomerAttempt is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.UNAUTHORIZED,
            (unauthorizedCustomerAttempt as OrderTransitionOutcome.Failure).reason
        )

        val unauthorizedGuestAttempt = orderRepository.transitionOrderStatus(
            orderId = orderId,
            targetStatus = OrderStatus.PREPARING,
            actor = null
        )
        assertTrue(unauthorizedGuestAttempt is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.UNAUTHORIZED,
            (unauthorizedGuestAttempt as OrderTransitionOutcome.Failure).reason
        )

        // Invalid jump directly from CONFIRMED_LOCAL to DELIVERED is rejected
        val invalidSkipAttempt = orderRepository.transitionOrderStatus(
            orderId = orderId,
            targetStatus = OrderStatus.DELIVERED,
            actor = storeOwner
        )
        assertTrue(invalidSkipAttempt is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.INVALID_STATUS_TRANSITION,
            (invalidSkipAttempt as OrderTransitionOutcome.Failure).reason
        )

        // Valid transition: CONFIRMED_LOCAL -> PREPARING
        val toPreparing = orderRepository.transitionOrderStatus(
            orderId = orderId,
            targetStatus = OrderStatus.PREPARING,
            actor = storeOwner
        )
        assertTrue(toPreparing is OrderTransitionOutcome.Success)
        assertEquals(OrderStatus.PREPARING, (toPreparing as OrderTransitionOutcome.Success).newStatus)

        // Invalid backward transition: PREPARING -> CONFIRMED_LOCAL
        val backwardAttempt = orderRepository.transitionOrderStatus(
            orderId = orderId,
            targetStatus = OrderStatus.CONFIRMED_LOCAL,
            actor = storeOwner
        )
        assertTrue(backwardAttempt is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.INVALID_STATUS_TRANSITION,
            (backwardAttempt as OrderTransitionOutcome.Failure).reason
        )

        // Valid transition: PREPARING -> OUT_FOR_DELIVERY
        val toOutForDelivery = orderRepository.transitionOrderStatus(
            orderId = orderId,
            targetStatus = OrderStatus.OUT_FOR_DELIVERY,
            actor = storeOwner
        )
        assertTrue(toOutForDelivery is OrderTransitionOutcome.Success)
        assertEquals(OrderStatus.OUT_FOR_DELIVERY, (toOutForDelivery as OrderTransitionOutcome.Success).newStatus)

        // Valid transition: OUT_FOR_DELIVERY -> DELIVERED (completed)
        val toDelivered = orderRepository.transitionOrderStatus(
            orderId = orderId,
            targetStatus = OrderStatus.DELIVERED,
            actor = storeOwner
        )
        assertTrue(toDelivered is OrderTransitionOutcome.Success)
        assertEquals(OrderStatus.DELIVERED, (toDelivered as OrderTransitionOutcome.Success).newStatus)

        // Terminal DELIVERED order cannot transition to PREPARING
        val reopenDeliveredAttempt = orderRepository.transitionOrderStatus(
            orderId = orderId,
            targetStatus = OrderStatus.PREPARING,
            actor = storeOwner
        )
        assertTrue(reopenDeliveredAttempt is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.INVALID_STATUS_TRANSITION,
            (reopenDeliveredAttempt as OrderTransitionOutcome.Failure).reason
        )
    }

    /**
     * 2. Cancellation, single-execution stock restoration, repeated cancellation guard,
     *    completed order cancellation prevention, and stock audit trail accuracy:
     *    - Cancelling an active order restores exact fractional & whole stock once in a single Room transaction.
     *    - Records StockAdjustment with "Order cancellation: {orderId}".
     *    - Keeps historical order totals and item prices unchanged even if product prices changed.
     *    - Repeated cancellation is rejected (ALREADY_CANCELLED) and cannot restore stock twice.
     *    - Completed (DELIVERED) orders cannot be cancelled (COMPLETED_ORDER_CANNOT_BE_CANCELLED).
     *    - Mid-cancellation transaction error rolls back both stock restoration and status change.
     */
    @Test
    fun cancellation_restoresStockOnceAtomically_preventsRepeatedOrCompletedCancellation_andPreservesAuditHistory() = runBlocking {
        val tomatoes = Product(
            id = 602,
            name = "طماطم محمية",
            nameEn = "Greenhouse Tomatoes",
            category = "خضروات",
            priceHalalas = 800, // 8.00 SAR/kg
            description = "طماطم طازجة",
            imageUrl = "tomato",
            unit = "كيلوجرام",
            stockQuantity = 10,
            stockSubUnitsMillis = 10_000
        )
        val apples = Product(
            id = 603,
            name = "تفاح أحمر",
            nameEn = "Red Apple",
            category = "فواكه",
            priceHalalas = 1200, // 12.00 SAR/kg
            description = "تفاح طازج",
            imageUrl = "apple",
            unit = "كيلوجرام",
            stockQuantity = 6,
            stockSubUnitsMillis = 6_000
        )
        productRepository.saveProduct(tomatoes)
        productRepository.saveProduct(apples)

        // Checkout Order #1 with 1.5 kg tomatoes (1200 halalas) + 2 kg apples (2400 halalas)
        cartRepository.addToCart(CartItem.fromMillis(productId = 602, quantityMillis = 1_500))
        cartRepository.addToCart(CartItem(productId = 603, quantity = 2, quantityMillis = 2_000))

        val checkout1 = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            deliveryFeeHalalas = 1500,
            idempotencyKey = "idem_cancel_602"
        ) as CheckoutOutcome.Success
        val orderId1 = checkout1.order.id

        // Stock after checkout: tomatoes = 8.5 kg (8,500 millis), apples = 4.0 kg (4,000 millis)
        val afterCheckoutProducts = productRepository.getAllProducts().first()
        assertEquals(8_500, afterCheckoutProducts.first { it.id == 602 }.effectiveStockMillis)
        assertEquals(4_000, afterCheckoutProducts.first { it.id == 603 }.effectiveStockMillis)

        // Change product price in catalog before cancellation to verify historical order prices stay unchanged
        productRepository.saveProduct(
            afterCheckoutProducts.first { it.id == 602 }.copy(priceHalalas = 1500)
        )

        // Simulate mid-cancellation failure after first item restoration -> must roll back atomically!
        orderRepository.failAfterFirstStockRestorationForTesting = true
        val rolledBackCancel = orderRepository.cancelOrder(
            orderId = orderId1,
            actor = storeOwner,
            reasonNote = "اختبار التراجع"
        )
        orderRepository.failAfterFirstStockRestorationForTesting = false
        assertTrue(rolledBackCancel is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.TRANSACTION_ERROR,
            (rolledBackCancel as OrderTransitionOutcome.Failure).reason
        )

        // Verify neither stock nor order status changed after rollback
        val afterRollbackProducts = productRepository.getAllProducts().first()
        assertEquals(8_500, afterRollbackProducts.first { it.id == 602 }.effectiveStockMillis)
        assertEquals(4_000, afterRollbackProducts.first { it.id == 603 }.effectiveStockMillis)
        assertEquals(
            OrderStatus.CONFIRMED_LOCAL,
            orderRepository.getOrderDetailsForStoreManagementOnce(orderId1, storeOwner)!!.order.orderStatus
        )

        // Now execute valid cancellation
        val cancelOutcome = orderRepository.cancelOrder(
            orderId = orderId1,
            actor = storeOwner,
            reasonNote = "طلب العميل إلغاء الطلب قبل التجهيز"
        )
        assertTrue("Expected cancellation to succeed", cancelOutcome is OrderTransitionOutcome.Success)
        val cancelSuccess = cancelOutcome as OrderTransitionOutcome.Success
        assertEquals(OrderStatus.CANCELLED, cancelSuccess.newStatus)
        assertEquals(2, cancelSuccess.restoredStockAdjustments.size)

        // Verify stock restored to exact pre-checkout values: 10,000 millis and 6,000 millis
        val afterCancelProducts = productRepository.getAllProducts().first()
        assertEquals(10_000, afterCancelProducts.first { it.id == 602 }.effectiveStockMillis)
        assertEquals(6_000, afterCancelProducts.first { it.id == 603 }.effectiveStockMillis)

        // Verify historical order totals and item prices are completely unchanged
        val cancelledOrderDetails = orderRepository.getOrderDetailsForStoreManagementOnce(orderId1, storeOwner)!!
        assertEquals(OrderStatus.CANCELLED, cancelledOrderDetails.order.orderStatus)
        assertEquals(3600, cancelledOrderDetails.order.subtotalHalalas)
        assertEquals(5100, cancelledOrderDetails.order.totalHalalas)
        val tomatoSnapshot = cancelledOrderDetails.items.first { it.productId == 602 }
        assertEquals(800, tomatoSnapshot.priceHalalas)
        assertEquals(1200, tomatoSnapshot.lineTotalHalalas)

        // Repeated cancellation request MUST fail and MUST NOT restore stock a second time
        val repeatedCancel = orderRepository.cancelOrder(
            orderId = orderId1,
            actor = storeOwner
        )
        assertTrue(repeatedCancel is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.ALREADY_CANCELLED,
            (repeatedCancel as OrderTransitionOutcome.Failure).reason
        )
        val afterRepeatedCancelProducts = productRepository.getAllProducts().first()
        assertEquals(10_000, afterRepeatedCancelProducts.first { it.id == 602 }.effectiveStockMillis)
        assertEquals(6_000, afterRepeatedCancelProducts.first { it.id == 603 }.effectiveStockMillis)

        // Verify StockAdjustment history matches actual deductions and single restorations
        val adjustments = productRepository.getAllStockAdjustments().first()
        // 2 checkout deductions + 2 cancellation restorations = 4 total records
        assertEquals(4, adjustments.size)
        val tomatoCancelAdj = adjustments.first {
            it.productId == 602 && it.reason.startsWith("Order cancellation: $orderId1")
        }
        assertEquals(8_500, tomatoCancelAdj.previousQuantityMillis)
        assertEquals(10_000, tomatoCancelAdj.newQuantityMillis)
        assertEquals(1_500, tomatoCancelAdj.quantityDeltaMillis)
        assertEquals("+1.5", tomatoCancelAdj.formattedDeltaQuantity())

        // Now create Order #2, complete it (DELIVERED), and verify it CANNOT be cancelled
        cartRepository.addToCart(CartItem(productId = 603, quantity = 1, quantityMillis = 1_000))
        val checkout2 = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            idempotencyKey = "idem_completed_no_cancel_603"
        ) as CheckoutOutcome.Success
        val orderId2 = checkout2.order.id

        assertTrue(
            orderRepository.transitionOrderStatus(orderId2, OrderStatus.PREPARING, storeOwner) is OrderTransitionOutcome.Success
        )
        assertTrue(
            orderRepository.transitionOrderStatus(orderId2, OrderStatus.DELIVERED, storeOwner) is OrderTransitionOutcome.Success
        )

        val cancelCompletedAttempt = orderRepository.cancelOrder(
            orderId = orderId2,
            actor = storeOwner
        )
        assertTrue(cancelCompletedAttempt is OrderTransitionOutcome.Failure)
        assertEquals(
            OrderTransitionFailureReason.COMPLETED_ORDER_CANNOT_BE_CANCELLED,
            (cancelCompletedAttempt as OrderTransitionOutcome.Failure).reason
        )
        // Apples stock remains 5.0 kg (6.0 - 1.0)
        val applesAfterCompletedCancelAttempt = productRepository.getAllProducts().first().first { it.id == 603 }
        assertEquals(5_000, applesAfterCompletedCancelAttempt.effectiveStockMillis)
    }

    /**
     * 3. Customer isolation & separate access boundaries:
     *    - Customer can inspect their own persisted orders and status.
     *    - Customer cannot read another customer's orders by supplying a different customer ID or manipulating UI state.
     *    - Customer cannot access store-owner order detail queries.
     */
    @Test
    fun customerOrderIsolation_preventsCrossCustomerReadsByParameterOrUiStateManipulation(): Unit = runBlocking {
        val orange = Product(
            id = 604,
            name = "برتقال أبو صرة",
            nameEn = "Navel Orange",
            category = "فواكه",
            priceHalalas = 600,
            description = "برتقال طازج",
            imageUrl = "orange",
            unit = "كيلوجرام",
            stockQuantity = 20,
            stockSubUnitsMillis = 20_000
        )
        productRepository.saveProduct(orange)

        // Alice creates Order A
        cartRepository.addToCart(CartItem(productId = 604, quantity = 2, quantityMillis = 2_000))
        val aliceCheckout = orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            deliveryAddress = "الرياض - حي الياسمين - منزل نورة",
            idempotencyKey = "idem_alice_isolation"
        ) as CheckoutOutcome.Success
        val aliceOrderId = aliceCheckout.order.id

        // Bob creates Order B
        cartRepository.addToCart(CartItem(productId = 604, quantity = 3, quantityMillis = 3_000))
        val bobCheckout = orderRepository.executeLocalCheckout(
            customerId = customerBob.id,
            customerName = customerBob.fullName,
            deliveryAddress = "الرياض - حي النرجس - منزل خالد",
            idempotencyKey = "idem_bob_isolation"
        ) as CheckoutOutcome.Success
        val bobOrderId = bobCheckout.order.id

        // 1. Alice can read her own order details
        val aliceOwnDetails = orderRepository.getOrderDetailsForCustomerOnce(
            orderId = aliceOrderId,
            actor = customerAlice
        )
        assertNotNull(aliceOwnDetails)
        assertEquals(aliceOrderId, aliceOwnDetails!!.order.id)

        // 2. Alice CANNOT read Bob's order by passing Bob's orderId
        val aliceReadBobOrder = orderRepository.getOrderDetailsForCustomerOnce(
            orderId = bobOrderId,
            actor = customerAlice
        )
        assertNull("Customer Alice must not read Bob's order by ID", aliceReadBobOrder)

        // 3. Alice CANNOT read Bob's orders by supplying Bob's customerId while authenticated as Alice
        val spoofedStream = orderRepository.getOrdersForAuthenticatedCustomer(
            actor = customerAlice,
            requestedCustomerId = customerBob.id
        ).first()
        assertTrue("Spoofed customerId must return empty list", spoofedStream.isEmpty())

        val spoofedDirectStream = orderRepository.getOrdersWithItemsForCustomer(
            customerId = customerBob.id,
            actor = customerAlice
        ).first()
        assertTrue("Direct stream with mismatched actor must return empty list", spoofedDirectStream.isEmpty())

        // 4. Alice CANNOT use Store Management order details endpoint
        assertNull(orderRepository.getOrderDetailsForStoreManagementOnce(bobOrderId, customerAlice))

        // 5. UI Verification: Rendering OrderHistoryScreen for Alice even if UI state attempts to select Bob's order ID
        val cartViewModel = CartViewModel(
            cartRepository = cartRepository,
            productRepository = productRepository,
            orderRepository = orderRepository
        )
        // Attempt to manipulate UI state to select Bob's order ID
        cartViewModel.selectCustomerOrderForDetails(bobOrderId)

        composeTestRule.setContent {
            FRESHTheme {
                OrderHistoryScreen(
                    cartViewModel = cartViewModel,
                    currentUser = customerAlice,
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        var waitLoops = 0
        while (composeTestRule.onAllNodesWithTag("order_history_card_$aliceOrderId").fetchSemanticsNodes().isEmpty() && waitLoops < 50) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            waitLoops++
        }

        // Alice's order card is displayed, Bob's order card and details are NEVER displayed
        composeTestRule.onNodeWithTag("order_history_card_$aliceOrderId").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithTag("order_history_card_$bobOrderId").fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodesWithTag("customer_order_details_card_$bobOrderId").fetchSemanticsNodes().isEmpty()
        )

        // Alice can open her own order details and inspect status
        composeTestRule.onNodeWithTag("customer_order_inspect_button_$aliceOrderId").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("customer_order_details_card_$aliceOrderId").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customer_order_detail_status_$aliceOrderId").assertIsDisplayed()
        Unit
    }

    /**
     * 4. Owner Order-Management Screen UI & ViewModel Integration Test:
     *    - Store Owner can view persisted orders, filter by status, open order details,
     *      inspect item snapshots/quantities/totals/customer reference/timestamp,
     *      perform valid status transitions, and cancel an order with Arabic feedback.
     */
    @Test
    fun ownerOrderManagementScreen_listsFiltersInspectsAndTransitionsOrdersWithArabicFeedback(): Unit = runBlocking {
        val carrot = Product(
            id = 605,
            name = "جزر بلدي طازج",
            nameEn = "Fresh Local Carrots",
            category = "خضروات",
            priceHalalas = 500, // 5.00 SAR
            description = "جزر سكري",
            imageUrl = "carrot",
            unit = "كيس ١ كجم",
            stockQuantity = 12,
            stockSubUnitsMillis = 12_000
        )
        productRepository.saveProduct(carrot)

        // Create two orders: Order 1 (Alice) and Order 2 (Bob)
        cartRepository.addToCart(CartItem(productId = 605, quantity = 2, quantityMillis = 2_000))
        val order1 = (orderRepository.executeLocalCheckout(
            customerId = customerAlice.id,
            customerName = customerAlice.fullName,
            idempotencyKey = "idem_owner_ui_1"
        ) as CheckoutOutcome.Success).order

        cartRepository.addToCart(CartItem.fromMillis(productId = 605, quantityMillis = 1_500))
        val order2 = (orderRepository.executeLocalCheckout(
            customerId = customerBob.id,
            customerName = customerBob.fullName,
            idempotencyKey = "idem_owner_ui_2"
        ) as CheckoutOutcome.Success).order

        val authViewModel = AuthViewModel(authRepository)
        authViewModel.loginWithDemoRole(UserRole.STORE_OWNER)

        val storeViewModel = StoreManagementViewModel(
            repository = productRepository,
            orderRepository = orderRepository
        )
        storeViewModel.navigateToSection(StoreManagementSection.ORDERS_NOTICE)

        composeTestRule.setContent {
            FRESHTheme {
                StoreOwnerFoundationScreen(
                    viewModel = authViewModel,
                    storeViewModel = storeViewModel,
                    onSwitchToCustomerStorefront = {},
                    onNavigateToAccount = {},
                    onNavigateToSettings = {},
                    onNavigateToLogin = {},
                    onLoggedOut = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        var attempts = 0
        while (storeViewModel.storeOrders.value.size < 2 && attempts < 50) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            attempts++
        }
        composeTestRule.waitForIdle()

        // Verify owner orders screen and both orders are listed
        composeTestRule.onNodeWithTag("store_orders_notice_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("owner_order_card_${order1.id}").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("owner_order_card_${order2.id}").performScrollTo().assertIsDisplayed()

        // Open Order 1 details and verify item snapshots, customer reference, timestamp, and total
        composeTestRule.onNodeWithTag("owner_order_open_details_${order1.id}").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("owner_order_details_panel_${order1.id}").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("owner_order_detail_customer_${order1.id}").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("owner_order_detail_timestamp_${order1.id}").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("owner_order_detail_item_${order1.id}_605").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("owner_order_detail_total_${order1.id}").performScrollTo().assertIsDisplayed()

        // Transition Order 1 from CONFIRMED_LOCAL -> PREPARING via detail panel button
        composeTestRule.onNodeWithTag("owner_order_detail_transition_${order1.id}_PREPARING")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        var transitionWait = 0
        while (storeViewModel.storeOrders.value.find { it.order.id == order1.id }?.order?.orderStatus != OrderStatus.PREPARING && transitionWait < 50) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            transitionWait++
        }
        composeTestRule.waitForIdle()

        // Verify Arabic success banner is shown
        composeTestRule.onNodeWithTag("store_status_banner").performScrollTo().assertIsDisplayed()
        assertEquals(
            OrderStatus.PREPARING,
            storeViewModel.storeOrders.value.first { it.order.id == order1.id }.order.orderStatus
        )

        // Filter orders by PREPARING -> only Order 1 should be in the filtered list
        composeTestRule.onNodeWithTag("owner_order_filter_PREPARING").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, storeViewModel.getFilteredOrdersForActor(storeOwner).size)
        assertEquals(order1.id, storeViewModel.getFilteredOrdersForActor(storeOwner).first().order.id)

        // Switch filter back to ALL and cancel Order 2 -> verifies stock of 1.5 kg is restored
        composeTestRule.onNodeWithTag("owner_order_filter_ALL").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("owner_order_cancel_${order2.id}").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        var cancelWait = 0
        while (storeViewModel.storeOrders.value.find { it.order.id == order2.id }?.order?.orderStatus != OrderStatus.CANCELLED && cancelWait < 50) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            cancelWait++
        }
        composeTestRule.waitForIdle()

        assertEquals(
            OrderStatus.CANCELLED,
            storeViewModel.storeOrders.value.first { it.order.id == order2.id }.order.orderStatus
        )
        // Stock should now be 12.0 - 2.0 (Order 1) - 1.5 (Order 2) + 1.5 (Order 2 cancelled) = 10.0 kg (10,000 millis)
        val updatedCarrot = productRepository.getAllProducts().first().first { it.id == 605 }
        assertEquals(10_000, updatedCarrot.effectiveStockMillis)
    }
}
