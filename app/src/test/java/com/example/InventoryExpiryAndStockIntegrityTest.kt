package com.example

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
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
import com.example.data.local.model.Product
import com.example.data.local.model.ProductExpiryStatus
import com.example.data.repository.CartRepository
import com.example.data.repository.ProductRepository
import com.example.data.repository.StockAdjustmentOutcome
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ProductCard
import com.example.ui.screens.StoreOwnerFoundationScreen
import com.example.ui.theme.FRESHTheme
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.CartViewModel
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.ProductFormInput
import com.example.ui.viewmodel.ProductFormValidator
import com.example.ui.viewmodel.StockAdjustmentValidator
import com.example.ui.viewmodel.StoreManagementSection
import com.example.ui.viewmodel.StoreManagementViewModel
import com.example.ui.viewmodel.StoreProductStatusFilter
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InventoryExpiryAndStockIntegrityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var productRepository: ProductRepository
    private lateinit var cartRepository: CartRepository
    private lateinit var authRepository: LocalAuthRepository
    private lateinit var authViewModel: AuthViewModel
    private lateinit var storeViewModel: StoreManagementViewModel
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var cartViewModel: CartViewModel

    private class OfflineFreshApiService : FreshApiService {
        override suspend fun getCategories(): List<CategoryDto> = emptyList()
        override suspend fun getProducts(): List<ProductDto> = emptyList()
        override suspend fun getProduct(id: Int): ProductDto = throw NotImplementedError()
        override suspend fun createOrder(request: OrderRequest): OrderResponse = throw NotImplementedError()
    }

    private val ownerUser = AuthenticatedUser(
        id = "owner-1",
        fullName = "عبدالله الراجحي",
        email = "owner@fresh-market.sa",
        phoneNumber = "0501112233",
        role = UserRole.STORE_OWNER,
        isLocalDemoAccount = true
    )

    private val adminUser = AuthenticatedUser(
        id = "admin-1",
        fullName = "سلمان القحطاني",
        email = "admin@fresh-market.sa",
        phoneNumber = "0504445566",
        role = UserRole.STORE_ADMIN,
        isLocalDemoAccount = true
    )

    private val customerUser = AuthenticatedUser(
        id = "cust-1",
        fullName = "نورة العتيبي",
        email = "noura@fresh-market.sa",
        phoneNumber = "0551234567",
        role = UserRole.CUSTOMER,
        isLocalDemoAccount = false
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("inventory_5c_test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        productRepository = ProductRepository(
            productDao = database.productDao(),
            apiService = OfflineFreshApiService(),
            stockAdjustmentDao = database.stockAdjustmentDao()
        )
        cartRepository = CartRepository(database.cartDao())
        authRepository = LocalAuthRepository(prefs)
        authViewModel = AuthViewModel(authRepository)
        storeViewModel = StoreManagementViewModel(
            repository = productRepository,
            referenceDateIso = "2026-10-17",
            initialExpiryWarningDays = 7
        )
        homeViewModel = HomeViewModel(productRepository)
        cartViewModel = CartViewModel(cartRepository, productRepository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun waitForLoaded() {
        var attempts = 0
        while ((storeViewModel.isLoading.value || homeViewModel.isLoading.value) && attempts < 60) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            attempts++
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun testExpiryTodayYesterdayFutureAndConfigurableWarningBoundary() {
        val referenceDate = "2026-10-17"
        val baseProduct = ProductRepository.initialProducts.first()

        // 1. Yesterday (2026-10-16) -> EXPIRED
        val yesterdayProduct = baseProduct.copy(expiryDate = "2026-10-16")
        assertEquals(
            ProductExpiryStatus.EXPIRED,
            yesterdayProduct.evaluateExpiryStatus(referenceDate, warningWindowDays = 7)
        )
        assertTrue(yesterdayProduct.isExpired(referenceDate))
        assertFalse(yesterdayProduct.isExpiringSoon(referenceDate, warningWindowDays = 7))
        assertEquals(-1L, yesterdayProduct.daysUntilExpiry(referenceDate))

        // 2. Today (2026-10-17) -> EXPIRING_SOON (0 days remaining, not expired yet)
        val todayProduct = baseProduct.copy(expiryDate = "2026-10-17")
        assertEquals(
            ProductExpiryStatus.EXPIRING_SOON,
            todayProduct.evaluateExpiryStatus(referenceDate, warningWindowDays = 7)
        )
        assertFalse(todayProduct.isExpired(referenceDate))
        assertTrue(todayProduct.isExpiringSoon(referenceDate, warningWindowDays = 7))
        assertEquals(0L, todayProduct.daysUntilExpiry(referenceDate))

        // 3. Exact boundary of 7-day warning window (2026-10-24 = +7 days) -> EXPIRING_SOON
        val boundaryDay7Product = baseProduct.copy(expiryDate = "2026-10-24")
        assertEquals(
            ProductExpiryStatus.EXPIRING_SOON,
            boundaryDay7Product.evaluateExpiryStatus(referenceDate, warningWindowDays = 7)
        )
        assertTrue(boundaryDay7Product.isExpiringSoon(referenceDate, warningWindowDays = 7))
        assertFalse(boundaryDay7Product.isExpired(referenceDate))

        // Shrink warning window to 3 days -> 2026-10-24 (+7 days) becomes VALID
        assertEquals(
            ProductExpiryStatus.VALID,
            boundaryDay7Product.evaluateExpiryStatus(referenceDate, warningWindowDays = 3)
        )
        assertFalse(boundaryDay7Product.isExpiringSoon(referenceDate, warningWindowDays = 3))

        // 4. Future date beyond 7-day boundary (2026-10-25 = +8 days) -> VALID
        val day8Product = baseProduct.copy(expiryDate = "2026-10-25")
        assertEquals(
            ProductExpiryStatus.VALID,
            day8Product.evaluateExpiryStatus(referenceDate, warningWindowDays = 7)
        )
        assertFalse(day8Product.isExpired(referenceDate))
        assertFalse(day8Product.isExpiringSoon(referenceDate, warningWindowDays = 7))
    }

    @Test
    fun testMissingAndInvalidExpiryDatesNeverFalsePositiveAsExpired() {
        val referenceDate = "2026-10-17"
        val baseProduct = ProductRepository.initialProducts.first()

        // Non-perishable product with missing/blank expiry date
        val nonPerishable = baseProduct.copy(expiryDate = "")
        assertEquals(
            ProductExpiryStatus.NO_EXPIRY_DATE,
            nonPerishable.evaluateExpiryStatus(referenceDate, warningWindowDays = 7)
        )
        assertFalse(nonPerishable.isExpired(referenceDate))
        assertFalse(nonPerishable.isExpiringSoon(referenceDate, warningWindowDays = 7))
        assertNull(nonPerishable.daysUntilExpiry(referenceDate))

        // ProductFormValidator allows blank expiry date for non-perishable items
        val blankExpiryForm = ProductFormInput.fromProduct(baseProduct).copy(expiryDateInput = "   ")
        assertTrue(ProductFormValidator.validate(blankExpiryForm).isValid)

        // Invalid calendar date (February 30) and non-ISO format
        val impossibleCalendarDate = baseProduct.copy(expiryDate = "2026-02-30")
        assertEquals(
            ProductExpiryStatus.INVALID_EXPIRY_DATE,
            impossibleCalendarDate.evaluateExpiryStatus(referenceDate, warningWindowDays = 7)
        )
        assertFalse(impossibleCalendarDate.isExpired(referenceDate))
        assertFalse(impossibleCalendarDate.isExpiringSoon(referenceDate, warningWindowDays = 7))
        assertNull(impossibleCalendarDate.daysUntilExpiry(referenceDate))

        val malformedDate = baseProduct.copy(expiryDate = "17/10/2026")
        assertEquals(
            ProductExpiryStatus.INVALID_EXPIRY_DATE,
            malformedDate.evaluateExpiryStatus(referenceDate, warningWindowDays = 7)
        )

        // ProductFormValidator rejects impossible calendar dates like 2026-02-30
        val invalidCalendarForm = ProductFormInput.fromProduct(baseProduct).copy(expiryDateInput = "2026-02-30")
        assertFalse(ProductFormValidator.validate(invalidCalendarForm).isValid)
    }

    @Test
    fun testArabicAndLatinStockInputAndNegativeQuantityRejection() {
        // 1. Arabic digits target quantity
        val arabicTarget = StockAdjustmentValidator.validateTargetQuantity(
            currentQuantity = 10,
            newQuantityInput = "٣٥",
            reasonInput = "توريد دفعة جديدة للمخزون"
        )
        assertTrue(arabicTarget.isValid)
        assertEquals(35, arabicTarget.parsedTargetQuantity)
        assertEquals(25, arabicTarget.parsedDelta)

        // 2. Latin digits target quantity = 0 (out of stock is allowed as >= 0)
        val zeroTarget = StockAdjustmentValidator.validateTargetQuantity(
            currentQuantity = 8,
            newQuantityInput = "0",
            reasonInput = "جرد يدوي وتصحيح الرصيد"
        )
        assertTrue(zeroTarget.isValid)
        assertEquals(0, zeroTarget.parsedTargetQuantity)
        assertEquals(-8, zeroTarget.parsedDelta)

        // 3. Negative target quantity rejected (both Latin and Arabic minus)
        val negativeLatin = StockAdjustmentValidator.validateTargetQuantity(
            currentQuantity = 10,
            newQuantityInput = "-5",
            reasonInput = "جرد يدوي"
        )
        assertFalse(negativeLatin.isValid)
        assertNotNull(negativeLatin.errorArabic)

        val negativeArabic = StockAdjustmentValidator.validateTargetQuantity(
            currentQuantity = 10,
            newQuantityInput = "-٣",
            reasonInput = "جرد يدوي"
        )
        assertFalse(negativeArabic.isValid)

        // 4. Delta adjustment resulting in negative quantity rejected
        val excessiveDecrease = StockAdjustmentValidator.validateDeltaAdjustment(
            currentQuantity = 4,
            deltaInput = "-5",
            reasonInput = "استبعاد أصناف تالفة"
        )
        assertFalse(excessiveDecrease.isValid)
    }

    @Test
    fun testPersistedStockAdjustmentHistoryStaleProtectionAndUnauthorizedRejection() = runBlocking {
        waitForLoaded()

        // Initially no stock adjustments are fabricated
        val initialHistory = productRepository.getAllStockAdjustments().first()
        assertTrue(initialHistory.isEmpty())

        val apple = storeViewModel.allProducts.value.first { it.id == 1 }
        assertEquals(42, apple.stockQuantity)

        // 1. Unauthorized guest and customer cannot adjust stock
        assertFalse(storeViewModel.adjustProductStock(actor = null, product = apple, delta = 5))
        assertFalse(storeViewModel.adjustProductStock(actor = customerUser, product = apple, delta = 5))
        assertFalse(
            storeViewModel.submitStockAdjustmentWithReason(
                actor = customerUser,
                product = apple,
                newQuantityInput = "60",
                reasonInput = "محاولة غير مصرحة"
            )
        )
        assertTrue(productRepository.getAllStockAdjustments().first().isEmpty())

        // 2. Authorized Store Owner adjusts stock with Arabic numerals and reason
        val submitted = storeViewModel.submitStockAdjustmentWithReason(
            actor = ownerUser,
            product = apple,
            newQuantityInput = "٥٠",
            reasonInput = "توريد دفعة جديدة للمخزون",
            expectedPreviousQuantity = 42
        )
        assertTrue(submitted)

        var attempts = 0
        while (storeViewModel.stockAdjustments.value.isEmpty() && attempts < 60) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            attempts++
        }
        composeTestRule.waitForIdle()

        val history = productRepository.getAllStockAdjustments().first()
        assertEquals(1, history.size)
        val record = history.first()
        assertEquals(1, record.productId)
        assertEquals(42, record.previousQuantity)
        assertEquals(50, record.newQuantity)
        assertEquals(8, record.quantityDelta)
        assertEquals("توريد دفعة جديدة للمخزون", record.reason)
        assertEquals(ownerUser.id, record.actorId)

        // 3. Stale update protection: attempting an adjustment expecting old quantity 42 fails
        val staleOutcome = productRepository.applyValidatedStockAdjustment(
            productId = 1,
            newStockQuantity = 30,
            expectedPreviousQuantity = 42, // stale! current is 50
            reason = "تحديث قديم متعارض",
            actorId = adminUser.id,
            actorName = adminUser.fullName,
            actorRole = adminUser.role.name
        )
        assertTrue(staleOutcome is StockAdjustmentOutcome.Failure)
        val currentApple = productRepository.getAllProducts().first().first { it.id == 1 }
        assertEquals(50, currentApple.stockQuantity)
    }

    @Test
    fun testConsistencyBetweenInventoryMetricsAndProductData() {
        waitForLoaded()

        val metrics7Days = storeViewModel.metrics.value
        assertEquals(10, metrics7Days.totalProductsCount)
        assertEquals(10, metrics7Days.activeProductsCount)
        // Sum of initial active stock quantities: 42 + 30 + 50 + 3 + 28 + 35 + 60 + 14 + 4 + 0 = 266
        assertEquals(266, metrics7Days.totalAvailableStockUnits)
        assertEquals(2, metrics7Days.lowStockCount) // Lettuce (3), Mint (4)
        assertEquals(1, metrics7Days.outOfStockCount) // Avocado (0)
        assertEquals(1, metrics7Days.expiredCount) // Cucumber (2026-10-10 < 2026-10-17)
        assertEquals(2, metrics7Days.expiringSoonCount) // Mint (2026-10-22 = +5d), Lettuce (2026-10-24 = +7d)

        // Change configurable expiry warning window to 14 days -> Banana (2026-10-28 = +11d) and Tomato (2026-10-30 = +13d) also become expiring soon
        storeViewModel.setExpiryWarningDays(14)
        val metrics14Days = storeViewModel.metrics.value
        assertEquals(14, metrics14Days.expiryWarningDays)
        assertEquals(4, metrics14Days.expiringSoonCount)
        assertEquals(1, metrics14Days.expiredCount) // Expired count remains strictly distinct from expiring soon

        // Search by English name ("Avocado") in Inventory filter
        storeViewModel.onSearchQueryChange("Avocado")
        val englishMatches = storeViewModel.getFilteredProducts()
        assertEquals(1, englishMatches.size)
        assertEquals(10, englishMatches.first().id)
        storeViewModel.onSearchQueryChange("")
    }

    @Test
    fun testOutOfStockProductBehaviorInCustomerStorefrontAndCart() {
        waitForLoaded()

        val avocadoOutOfStock = homeViewModel.products.value.first { it.id == 10 }
        assertTrue(avocadoOutOfStock.isOutOfStock)
        assertFalse(avocadoOutOfStock.isPurchasableInStorefront)

        val appleInStock = homeViewModel.products.value.first { it.id == 1 }
        assertFalse(appleInStock.isOutOfStock)
        assertTrue(appleInStock.isPurchasableInStorefront)

        var addCartClickedCount = 0

        composeTestRule.setContent {
            FRESHTheme {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    ProductCard(
                        product = avocadoOutOfStock,
                        isFavorite = false,
                        onFavoriteClick = {},
                        onClick = {},
                        onAddToCart = { addCartClickedCount++ }
                    )
                    ProductCard(
                        product = appleInStock,
                        isFavorite = false,
                        onFavoriteClick = {},
                        onClick = {},
                        onAddToCart = { addCartClickedCount++ }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // Out-of-stock card displays "نفد المخزون" indicator and its add-to-cart button is disabled
        composeTestRule.onNodeWithTag("product_stock_status_10", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("نفد المخزون", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("add_to_cart_button_10").assertIsNotEnabled()

        // In-stock card displays "متوفر" indicator and its add-to-cart button is enabled
        composeTestRule.onNodeWithTag("add_to_cart_button_1").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("product_stock_status_1", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("add_to_cart_button_1").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, addCartClickedCount)

        // CartViewModel also rejects adding an out-of-stock product or exceeding available stock
        cartViewModel.addToCart(productId = 10, quantity = 1)
        Thread.sleep(60)
        composeTestRule.waitForIdle()
        assertNotNull(cartViewModel.cartErrorMessage.value)

        cartViewModel.addToCart(productId = 1, quantity = 999)
        Thread.sleep(60)
        composeTestRule.waitForIdle()
        assertNotNull(cartViewModel.cartErrorMessage.value)
    }

    @Test
    fun testInventoryDashboardTopSearchBarFiltersProductsInRealTimeByArabicAndEnglishFlow(): Unit = runBlocking {
        waitForLoaded()

        val inventoryVm = com.example.ui.viewmodel.InventoryViewModel(
            repository = productRepository,
            referenceDateIso = "2026-10-17",
            initialExpiryWarningDays = 7
        )
        var attempts = 0
        while (inventoryVm.isLoading.value && attempts < 60) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            attempts++
        }

        composeTestRule.setContent {
            FRESHTheme {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    com.example.ui.screens.InventoryDashboardScreen(
                        inventoryViewModel = inventoryVm
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // 1. Search bar is displayed right at the top of the Inventory Dashboard
        composeTestRule.onNodeWithTag("inventory_search_input").assertIsDisplayed()
        assertEquals(10, inventoryVm.filteredProducts.value.size)

        // 2. Typing an English product name ("Avocado") filters the Flow in real-time
        composeTestRule.onNodeWithTag("inventory_search_input").performTextReplacement("Avocado")
        composeTestRule.waitForIdle()

        assertEquals("Avocado", inventoryVm.searchQuery.value)
        assertEquals(1, inventoryVm.filteredProducts.value.size)
        assertEquals(10, inventoryVm.filteredProducts.value.first().id)
        assertEquals(1, inventoryVm.filteredProductsFlow.first().size)
        composeTestRule.onNodeWithTag("inventory_search_results_count").assertIsDisplayed()
        composeTestRule.onNodeWithTag("inventory_item_card_10").assertExists()

        // 3. Typing an Arabic product name ("تفاح") filters the Flow in real-time
        composeTestRule.onNodeWithTag("inventory_search_input").performTextReplacement("تفاح")
        composeTestRule.waitForIdle()

        assertEquals("تفاح", inventoryVm.searchQuery.value)
        assertEquals(1, inventoryVm.filteredProducts.value.size)
        assertEquals(1, inventoryVm.filteredProducts.value.first().id)
        composeTestRule.onNodeWithTag("inventory_item_card_1").assertExists()

        // 4. Clearing the search bar restores the full inventory list
        composeTestRule.onNodeWithTag("inventory_search_clear_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("", inventoryVm.searchQuery.value)
        assertEquals(10, inventoryVm.filteredProducts.value.size)
    }

    @Test
    fun testStoreOwnerUiIntegrationAuditForStockDialogHistoryTimestampAndDashboardMetrics(): Unit = runBlocking {
        waitForLoaded()
        authViewModel.loginWithDemoRole(UserRole.STORE_OWNER)

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

        // 1. Verify Dashboard summary cards are connected to StoreManagementViewModel metrics
        composeTestRule.onNodeWithTag("summary_card_total_products").assertExists()
        composeTestRule.onNodeWithTag("summary_card_active_products").assertExists()
        composeTestRule.onNodeWithTag("summary_card_low_stock").assertExists()
        composeTestRule.onNodeWithTag("summary_card_expiring_soon").assertExists()
        composeTestRule.onNodeWithTag("summary_card_expired_products").assertExists()
        composeTestRule.onNodeWithTag("summary_card_out_of_stock").assertExists()

        // 2. Navigate to Inventory Dashboard and open StockAdjustmentDialog for product 1
        storeViewModel.navigateToSection(StoreManagementSection.INVENTORY)
        composeTestRule.waitForIdle()

        val apple = storeViewModel.allProducts.value.first { it.id == 1 }
        storeViewModel.openStockAdjustmentDialog(apple)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("stock_adjustment_dialog").assertIsDisplayed()

        // 3. Verify negative quantity input shows inline validation error inside dialog
        composeTestRule.onNodeWithTag("stock_adjustment_quantity_input").performTextReplacement("-5")
        composeTestRule.onNodeWithTag("stock_adjustment_confirm_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("stock_adjustment_dialog_error").assertIsDisplayed()

        // 4. Submit valid stock adjustment with required reason and verify Room reload + timestamp display
        composeTestRule.onNodeWithTag("stock_adjustment_quantity_input").performTextReplacement("55")
        composeTestRule.onNodeWithTag("stock_adjustment_reason_input").performTextReplacement("توريد دفعة تدقيق 5D")
        composeTestRule.onNodeWithTag("stock_adjustment_confirm_button").performClick()
        composeTestRule.waitForIdle()

        var attempts = 0
        while (storeViewModel.stockAdjustments.value.isEmpty() && attempts < 60) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            attempts++
        }
        composeTestRule.waitForIdle()

        val reloadedProduct = database.productDao().getAllProducts().first().first { it.id == 1 }
        assertEquals(55, reloadedProduct.stockQuantity)

        val persistedHistory = database.stockAdjustmentDao().getAdjustmentsForProduct(1).first()
        assertEquals(1, persistedHistory.size)
        val historyRecord = persistedHistory.first()
        assertEquals(42, historyRecord.previousQuantity)
        assertEquals(55, historyRecord.newQuantity)
        assertEquals("توريد دفعة تدقيق 5D", historyRecord.reason)
        assertTrue(historyRecord.formattedTimestamp().isNotBlank())

        composeTestRule.onNodeWithTag("stock_adjustment_history_item_${historyRecord.id}").assertExists()
        composeTestRule.onNodeWithTag(
            "stock_adjustment_history_timestamp_${historyRecord.id}",
            useUnmergedTree = true
        ).assertExists()

        // 5. Open Product Details and verify per-product stock adjustment history and low-stock threshold are connected
        storeViewModel.openProductDetails(1)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("details_low_stock_threshold_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("details_stock_adjustment_history_section").assertExists()
        composeTestRule.onNodeWithTag("details_history_item_${historyRecord.id}").assertExists()
        Unit
    }

    @Test
    fun testStoreOwnerDashboardHighlightsProductsBelowThresholdWithWarningIcon(): Unit = runBlocking {
        waitForLoaded()
        authViewModel.loginWithDemoRole(UserRole.STORE_OWNER)

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

        // 1. Prominent warning banner and warning icon on the Store Owner Dashboard
        composeTestRule.onNodeWithTag("dashboard_low_stock_warning_banner").assertExists()
        composeTestRule.onNodeWithTag("dashboard_low_stock_warning_icon", useUnmergedTree = true).assertExists()

        // 2. Low-stock product 4 (Romaine Lettuce, stock 3 <= threshold 5) in recent products displays distinct warning badge & icon
        composeTestRule.onNodeWithTag("low_stock_warning_badge_4", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("low_stock_warning_icon_4", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("recent_product_warning_icon_4", useUnmergedTree = true).assertExists()

        // 3. Normal in-stock product 1 (Apple, stock 42 > threshold 8) does NOT have the low stock warning badge
        composeTestRule.onNodeWithTag("low_stock_warning_badge_1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("low_stock_warning_icon_1").assertDoesNotExist()

        // 4. Navigate to Inventory Dashboard and verify warning icon appears on operation card for low-stock and out-of-stock items
        storeViewModel.navigateToSection(StoreManagementSection.INVENTORY)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("inventory_low_stock_warning_icon_10", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("inventory_low_stock_warning_icon_4", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("inventory_low_stock_warning_icon_1").assertDoesNotExist()
    }
}


