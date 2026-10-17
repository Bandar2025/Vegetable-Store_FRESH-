package com.example

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.FreshApiService
import com.example.data.api.model.CategoryDto
import com.example.data.api.model.OrderRequest
import com.example.data.api.model.OrderResponse
import com.example.data.api.model.ProductDto
import com.example.data.local.AppDatabase
import com.example.data.repository.ProductRepository
import com.example.ui.components.ProductVisual
import com.example.ui.components.resolveProduceDrawable
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.FRESHTheme
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.ProductSortOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class HomeScreenRuntimeValidationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var repository: ProductRepository

    private class DeterministicProductDao : com.example.data.local.dao.ProductDao {
        private val flow = kotlinx.coroutines.flow.MutableStateFlow(ProductRepository.initialProducts)
        override fun getAllProducts(): kotlinx.coroutines.flow.Flow<List<com.example.data.local.model.Product>> = flow
        override suspend fun insert(product: com.example.data.local.model.Product) {
            flow.value = flow.value + product
        }
        override suspend fun insertAll(products: List<com.example.data.local.model.Product>) {
            flow.value = products
        }
        override suspend fun count(): Int = flow.value.size
    }

    private class OfflineApiService : FreshApiService {
        override suspend fun getCategories(): List<CategoryDto> = emptyList()
        override suspend fun getProducts(): List<ProductDto> = emptyList()
        override suspend fun getProduct(id: Int): ProductDto = throw NotImplementedError()
        override suspend fun createOrder(request: OrderRequest): OrderResponse = throw NotImplementedError()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = ProductRepository(DeterministicProductDao(), OfflineApiService())
    }

    @After
    fun tearDown() {
    }

    @Test
    fun verifyAllTenProduceWebpAssetsAndFallbackDecodeValidlyAtRuntime() {
        val produceKeys = listOf(
            "apple" to R.drawable.img_product_apple,
            "banana" to R.drawable.img_product_banana,
            "tomato" to R.drawable.img_product_tomato,
            "lettuce" to R.drawable.img_product_lettuce,
            "carrot" to R.drawable.img_product_carrot,
            "orange" to R.drawable.img_product_orange,
            "dates" to R.drawable.img_product_dates,
            "cucumber" to R.drawable.img_product_cucumber,
            "mint" to R.drawable.img_product_mint,
            "avocado" to R.drawable.img_product_avocado
        )

        for ((key, expectedResId) in produceKeys) {
            val resolvedId = resolveProduceDrawable(key)
            assertEquals("Key '$key' should map to expected drawable resource", expectedResId, resolvedId)

            val bitmap = BitmapFactory.decodeResource(context.resources, resolvedId)
            assertNotNull("WebP asset for '$key' must decode to a non-null Bitmap", bitmap)
            assertTrue("WebP asset for '$key' must have positive width", bitmap.width > 0)
            assertTrue("WebP asset for '$key' must have positive height", bitmap.height > 0)
            assertEquals(
                "WebP asset for '$key' should maintain 1:1 square aspect ratio without distortion",
                bitmap.width,
                bitmap.height
            )
        }

        // Verify fallback placeholder vector drawable loads cleanly
        val fallbackId = resolveProduceDrawable("unavailable_produce_item_xyz")
        assertEquals(R.drawable.ic_produce_placeholder, fallbackId)
        val placeholderDrawable = context.getDrawable(fallbackId)
        assertNotNull("Fallback vector drawable must load non-null", placeholderDrawable)
        assertTrue("Fallback vector drawable must have positive intrinsic width", placeholderDrawable!!.intrinsicWidth > 0)
    }

    @Test
    fun verifyHomeScreenLaunchesDisplaysProductsWithoutShimmerAndSupportsScrollingAndSorting() {
        val viewModel = HomeViewModel(repository)
        var attempts = 0
        while ((viewModel.isLoading.value || viewModel.products.value.isEmpty()) && attempts < 100) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            attempts++
        }

        composeTestRule.setContent {
            FRESHTheme {
                HomeScreen(
                    viewModel = viewModel,
                    onProductClick = {},
                    onCartClick = {},
                    onAddToCart = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Confirm shimmer skeleton is NOT displayed once local products are loaded
        composeTestRule.onNodeWithTag("home_shimmer_skeleton").assertDoesNotExist()
        composeTestRule.onNodeWithTag("product_card_skeleton").assertDoesNotExist()

        // 2. Confirm top bar and initial visible content render properly
        composeTestRule.onNodeWithText("الرياض، حي النخيل").assertIsDisplayed()

        // 3. Scroll down to Pro Tips section and verify mint & avocado tips can be selected
        composeTestRule.onNodeWithTag("home_lazy_column")
            .performScrollToNode(hasTestTag("pro_tips_section"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pro_tips_section").assertIsDisplayed()

        // Click avocado tip tab (id = 3) and verify its card renders
        composeTestRule.onNodeWithTag("pro_tip_tab_3").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pro_tip_card_3").assertIsDisplayed()

        // 4. Scroll to product rows and verify all 10 produce items render and maintain identity across sorting
        val allProductNames = listOf(
            "تفاح أحمر جالا طازج",
            "موز إكوادوري فاخر",
            "طماطم محمية بلدي",
            "خس روماني عضوي",
            "جزر طازج سكري",
            "برتقال أبو صرة للعصير",
            "تمر سكري القصيم فاخر",
            "خيار بلدي طازج",
            "نعناع حساوي طازج",
            "أفوكادو هاس ناضج"
        )
        for (name in allProductNames) {
            composeTestRule.onNodeWithTag("home_lazy_column")
                .performScrollToNode(hasText(name))
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText(name).assertIsDisplayed()
        }

        // Scroll back to product 2 ("موز إكوادوري فاخر", initially not favorite) and toggle favorite
        composeTestRule.onNodeWithTag("home_lazy_column")
            .performScrollToNode(hasText("موز إكوادوري فاخر"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("إضافة موز إكوادوري فاخر إلى المفضلة").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("إزالة موز إكوادوري فاخر من المفضلة").assertIsDisplayed()

        // Change sort order to PRICE_HIGH_TO_LOW and then PRICE_LOW_TO_HIGH
        viewModel.onSortOrderChange(ProductSortOrder.PRICE_HIGH_TO_LOW)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("home_lazy_column")
            .performScrollToNode(hasText("تمر سكري القصيم فاخر"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("تمر سكري القصيم فاخر").assertIsDisplayed()

        composeTestRule.onNodeWithTag("home_lazy_column")
            .performScrollToNode(hasText("أفوكادو هاس ناضج"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("أفوكادو هاس ناضج").assertIsDisplayed()

        // Scroll back to "موز إكوادوري فاخر" and verify its favorite state was preserved without identity corruption
        composeTestRule.onNodeWithTag("home_lazy_column")
            .performScrollToNode(hasText("موز إكوادوري فاخر"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("إزالة موز إكوادوري فاخر من المفضلة").assertIsDisplayed()

        // Sort low to high and scroll back and forth
        viewModel.onSortOrderChange(ProductSortOrder.PRICE_LOW_TO_HIGH)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_lazy_column")
            .performScrollToNode(hasText("نعناع حساوي طازج"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("نعناع حساوي طازج").assertIsDisplayed()
    }

    @Test
    fun verifyProductVisualHandlesAllTenItemsAndUnavailableFallbackWithoutShimmerForLocalAssets() {
        val testKeys = listOf(
            "apple", "banana", "tomato", "lettuce", "carrot",
            "orange", "dates", "cucumber", "mint", "avocado",
            "unavailable_item_fallback", null, ""
        )

        composeTestRule.setContent {
            FRESHTheme {
                for ((index, key) in testKeys.withIndex()) {
                    ProductVisual(
                        imageUrl = key,
                        contentDescription = "visual_$index"
                    )
                }
            }
        }

        composeTestRule.waitForIdle()

        for (index in testKeys.indices) {
            composeTestRule.onNodeWithContentDescription("visual_$index").assertExists()
        }
    }

    private fun hasScrollToNodeAction() = androidx.compose.ui.test.SemanticsMatcher(
        "has ScrollToIndexAction"
    ) { node ->
        androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex in node.config
    }
}
