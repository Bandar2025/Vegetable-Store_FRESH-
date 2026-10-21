package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.FreshApiService
import com.example.data.api.model.CategoryDto
import com.example.data.api.model.OrderRequest
import com.example.data.api.model.OrderResponse
import com.example.data.api.model.ProductDto
import com.example.data.auth.AuthenticatedUser
import com.example.data.auth.LocalAuthRepository
import com.example.data.auth.UserRole
import com.example.data.local.dao.ProductDao
import com.example.data.local.model.Product
import com.example.data.repository.ProductRepository
import com.example.ui.screens.StoreOwnerFoundationScreen
import com.example.ui.theme.FRESHTheme
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.ProductFormInput
import com.example.ui.viewmodel.ProductFormValidator
import com.example.ui.viewmodel.StoreDashboardMetrics
import com.example.ui.viewmodel.StoreManagementSection
import com.example.ui.viewmodel.StoreManagementViewModel
import com.example.ui.viewmodel.StoreProductStatusFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StoreOwnerProductManagementTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var productDao: InMemoryReactiveProductDao
    private lateinit var productRepository: ProductRepository
    private lateinit var authRepository: LocalAuthRepository
    private lateinit var authViewModel: AuthViewModel
    private lateinit var storeViewModel: StoreManagementViewModel
    private lateinit var homeViewModel: HomeViewModel

    private class InMemoryReactiveProductDao : ProductDao {
        private val productsFlow = MutableStateFlow(ProductRepository.initialProducts)

        override fun getAllProducts(): Flow<List<Product>> = productsFlow

        override suspend fun insert(product: Product) {
            val current = productsFlow.value.toMutableList()
            val index = current.indexOfFirst { it.id == product.id }
            if (index >= 0) {
                current[index] = product
            } else {
                current.add(product)
            }
            productsFlow.value = current
        }

        override suspend fun insertAll(products: List<Product>) {
            productsFlow.value = products
        }

        override suspend fun count(): Int = productsFlow.value.size
    }

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
        val prefs = context.getSharedPreferences("store_owner_test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        productDao = InMemoryReactiveProductDao()
        productRepository = ProductRepository(productDao, OfflineFreshApiService())
        authRepository = LocalAuthRepository(prefs)
        authViewModel = AuthViewModel(authRepository)
        storeViewModel = StoreManagementViewModel(
            repository = productRepository,
            referenceDateIso = "2026-10-17"
        )
        homeViewModel = HomeViewModel(productRepository)
    }

    private fun waitForProductsLoaded() {
        var attempts = 0
        while ((storeViewModel.isLoading.value || homeViewModel.isLoading.value) && attempts < 50) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            attempts++
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun testDashboardSummaryMetricsDerivedFromLocalDataAndHonestUnavailableStats() {
        waitForProductsLoaded()

        val metrics: StoreDashboardMetrics = storeViewModel.metrics.value
        assertEquals(10, metrics.totalProductsCount)
        assertEquals(10, metrics.activeProductsCount)
        assertEquals(0, metrics.inactiveProductsCount)
        assertEquals(0, metrics.archivedProductsCount)
        // Product 4 (lettuce: stock 3 <= 5) and Product 9 (mint: stock 4 <= 5)
        assertEquals(2, metrics.lowStockCount)
        // Product 8 (cucumber: expiry 2026-10-10 < 2026-10-17)
        assertEquals(1, metrics.expiredCount)
        // Product 10 (avocado: stock 0)
        assertEquals(1, metrics.outOfStockCount)
        // Sales/Revenue/Orders statistics must NOT be fabricated
        assertFalse(metrics.ordersStatisticsAvailable)
        assertTrue(metrics.unavailableStatsNoteArabic.contains("غير متوفرة"))
    }

    @Test
    fun testProductFormValidatorRulesAndArabicDigitsSupport() {
        // Invalid name
        assertFalse(
            ProductFormValidator.validate(
                ProductFormInput(name = "أ", priceSarInput = "10.0", description = "وصف منتج صالح")
            ).isValid
        )

        // Invalid zero or negative price
        assertFalse(
            ProductFormValidator.validate(
                ProductFormInput(name = "فراولة طازجة", priceSarInput = "0", description = "وصف منتج صالح")
            ).isValid
        )
        assertFalse(
            ProductFormValidator.validate(
                ProductFormInput(name = "فراولة طازجة", priceSarInput = "-5", description = "وصف منتج صالح")
            ).isValid
        )

        // Invalid negative stock
        assertFalse(
            ProductFormValidator.validate(
                ProductFormInput(
                    name = "فراولة طازجة",
                    priceSarInput = "12.50",
                    stockQuantityInput = "-1",
                    description = "وصف منتج صالح"
                )
            ).isValid
        )

        // Invalid expiry date format
        assertFalse(
            ProductFormValidator.validate(
                ProductFormInput(
                    name = "فراولة طازجة",
                    priceSarInput = "12.50",
                    expiryDateInput = "31/12/2026",
                    description = "وصف منتج صالح"
                )
            ).isValid
        )

        // Valid input with Arabic-Indic digits
        val validResult = ProductFormValidator.validate(
            ProductFormInput(
                name = "رمان طائفي فاخر",
                category = "فواكه",
                priceSarInput = "١٨.٥٠",
                unit = "كيلوجرام",
                description = "رمان طائفي أحمر حلو المذاق ومقطوف حديثاً.",
                stockQuantityInput = "٢٠",
                lowStockThresholdInput = "٥",
                expiryDateInput = "2026-12-15"
            )
        )
        assertTrue(validResult.isValid)
        assertEquals(1850, validResult.parsedPriceHalalas)
        assertEquals(20, validResult.parsedStockQuantity)
        assertEquals(5, validResult.parsedLowStockThreshold)
    }

    @Test
    fun testAddEditDeactivateArchiveAndStorefrontSynchronization() {
        waitForProductsLoaded()
        assertEquals(10, homeViewModel.products.value.size)

        // 1. Add a new active product as Store Owner
        storeViewModel.openAddProductForm()
        storeViewModel.updateFormState {
            it.copy(
                name = "مانجو جازان تومي",
                category = "فواكه",
                priceSarInput = "19.75",
                unit = "كيلوجرام",
                description = "مانجو جازان طازج ذو نكهة استوائية غنية.",
                imageUrl = "orange",
                stockQuantityInput = "15",
                lowStockThresholdInput = "4",
                expiryDateInput = "2026-11-30",
                isActive = true
            )
        }
        val added = storeViewModel.submitProductForm(actor = ownerUser)
        assertTrue(added)

        var attempts = 0
        while (storeViewModel.allProducts.value.size < 11 && attempts < 50) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            attempts++
        }
        composeTestRule.waitForIdle()

        assertEquals(11, storeViewModel.metrics.value.totalProductsCount)
        assertEquals(11, homeViewModel.products.value.size)
        val newProduct = storeViewModel.allProducts.value.find { it.name == "مانجو جازان تومي" }
        assertNotNull(newProduct)
        assertEquals(1975, newProduct!!.priceHalalas)

        // 2. Edit the product as Store Admin
        storeViewModel.openEditProductForm(newProduct)
        storeViewModel.updateFormState {
            it.copy(
                name = "مانجو جازان ملكي فاخر",
                priceSarInput = "22.00",
                stockQuantityInput = "2" // Low stock (<= 4)
            )
        }
        val edited = storeViewModel.submitProductForm(actor = adminUser)
        assertTrue(edited)

        attempts = 0
        while (storeViewModel.allProducts.value.find { it.id == newProduct.id }?.priceHalalas != 2200 && attempts < 50) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            attempts++
        }
        composeTestRule.waitForIdle()

        val updatedProduct = storeViewModel.allProducts.value.find { it.id == newProduct.id }!!
        assertEquals("مانجو جازان ملكي فاخر", updatedProduct.name)
        assertEquals(2200, updatedProduct.priceHalalas)
        assertTrue(updatedProduct.isLowStock)
        assertTrue(homeViewModel.products.value.any { it.name == "مانجو جازان ملكي فاخر" })

        // 3. Deactivate the product -> hidden from customer HomeViewModel, still visible to Store Owner
        storeViewModel.toggleProductActiveStatus(actor = ownerUser, product = updatedProduct)
        attempts = 0
        while (homeViewModel.products.value.any { it.id == updatedProduct.id } && attempts < 50) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            attempts++
        }
        composeTestRule.waitForIdle()

        assertFalse(homeViewModel.products.value.any { it.id == updatedProduct.id })
        assertEquals(10, homeViewModel.products.value.size)
        assertEquals(1, storeViewModel.metrics.value.inactiveProductsCount)

        // 4. Archive the product -> moved to archived filter
        val deactivatedProduct = storeViewModel.allProducts.value.find { it.id == updatedProduct.id }!!
        storeViewModel.toggleProductArchiveStatus(actor = ownerUser, product = deactivatedProduct)
        attempts = 0
        while (storeViewModel.metrics.value.archivedProductsCount != 1 && attempts < 50) {
            Thread.sleep(20)
            composeTestRule.waitForIdle()
            attempts++
        }
        composeTestRule.waitForIdle()

        assertEquals(1, storeViewModel.metrics.value.archivedProductsCount)
        assertEquals(10, storeViewModel.metrics.value.totalProductsCount)
        storeViewModel.onStatusFilterSelect(StoreProductStatusFilter.ARCHIVED_ONLY)
        val archivedList = storeViewModel.getFilteredProducts()
        assertEquals(1, archivedList.size)
        assertEquals(updatedProduct.id, archivedList.first().id)
    }

    @Test
    fun testUnauthorizedCustomerAndGuestCannotMutateProducts() {
        waitForProductsLoaded()
        val targetProduct = storeViewModel.allProducts.value.first()

        // Guest attempt
        storeViewModel.openAddProductForm()
        storeViewModel.updateFormState {
            it.copy(name = "منتج غير مصرح", priceSarInput = "10.0", description = "وصف صالح للاختبار")
        }
        assertFalse(storeViewModel.submitProductForm(actor = null))
        assertNotNull(storeViewModel.errorMessage.value)

        // Customer attempt to add, deactivate, archive, or adjust stock
        assertFalse(storeViewModel.submitProductForm(actor = customerUser))
        assertFalse(storeViewModel.toggleProductActiveStatus(actor = customerUser, product = targetProduct))
        assertFalse(storeViewModel.toggleProductArchiveStatus(actor = customerUser, product = targetProduct))
        assertFalse(storeViewModel.adjustProductStock(actor = customerUser, product = targetProduct, delta = 10))
        assertEquals(10, storeViewModel.allProducts.value.size)
    }

    @Test
    fun testStoreOwnerDashboardAndProductManagementComposeUi() {
        waitForProductsLoaded()
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

        // Verify Dashboard header, role badge, and summary cards
        composeTestRule.onNodeWithTag("store_management_authorized_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("store_owner_role_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("summary_card_total_products").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("summary_card_active_products").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("summary_card_low_stock").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("summary_card_expiring_soon").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("summary_card_expired_products").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("summary_card_out_of_stock").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("unavailable_orders_revenue_card").performScrollTo().assertIsDisplayed()

        // Navigate to Manage Products screen via Quick Action
        composeTestRule.onNodeWithTag("quick_action_manage_products").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(StoreManagementSection.PRODUCT_LIST, storeViewModel.currentSection.value)
        composeTestRule.onNodeWithTag("store_product_list_screen").assertIsDisplayed()

        // Filter by category "تمور" -> shows تمر سكري القصيم فاخر
        composeTestRule.onNodeWithTag("store_category_chip_تمور").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("تمر سكري القصيم فاخر").performScrollTo().assertIsDisplayed()

        // Open Add Product form from Product List
        composeTestRule.onNodeWithTag("product_list_add_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(StoreManagementSection.ADD_PRODUCT, storeViewModel.currentSection.value)
        composeTestRule.onNodeWithTag("store_product_form_screen").assertIsDisplayed()
    }
}
