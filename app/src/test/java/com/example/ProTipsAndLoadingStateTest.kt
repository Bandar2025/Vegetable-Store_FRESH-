package com.example

import com.example.data.api.FreshApiService
import com.example.data.api.model.CategoryDto
import com.example.data.api.model.OrderRequest
import com.example.data.api.model.OrderResponse
import com.example.data.api.model.ProductDto
import com.example.data.local.dao.ProductDao
import com.example.data.local.model.Product
import com.example.data.repository.ProductRepository
import com.example.ui.components.DefaultSeasonalProTips
import com.example.ui.components.resolveProduceDrawable
import com.example.ui.components.resolveProduceNutritionInfo
import com.example.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProTipsAndLoadingStateTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeProductDao : ProductDao {
        val flow = MutableStateFlow<List<Product>>(emptyList())
        override fun getAllProducts(): Flow<List<Product>> = flow
        override suspend fun insert(product: Product) {}
        override suspend fun insertAll(products: List<Product>) {
            flow.value = products
        }
        override suspend fun count(): Int = flow.value.size
    }

    private class FakeApiService : FreshApiService {
        override suspend fun getCategories(): List<CategoryDto> = emptyList()
        override suspend fun getProducts(): List<ProductDto> = emptyList()
        override suspend fun getProduct(id: Int): ProductDto = throw NotImplementedError()
        override suspend fun createOrder(request: OrderRequest): OrderResponse = throw NotImplementedError()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSeasonalProTipsHaveValidContentAndDrawables() {
        assertTrue(DefaultSeasonalProTips.isNotEmpty())
        DefaultSeasonalProTips.forEach { tip ->
            assertTrue(tip.title.isNotBlank())
            assertTrue(tip.tipDescription.isNotBlank())
            assertTrue(tip.storageZone.isNotBlank())
            assertTrue(tip.shelfLifeGain.isNotBlank())
            val resId = resolveProduceDrawable(tip.imageKey)
            assertNotEquals(R.drawable.ic_produce_placeholder, resId)
        }
    }

    @Test
    fun testProduceNutritionInfoForAllInitialProducts() {
        ProductRepository.initialProducts.forEach { product ->
            val nutrition = resolveProduceNutritionInfo(product)
            assertTrue("Calories should be > 0 for ${product.name}", nutrition.caloriesKcal > 0)
            assertTrue("Vitamins should not be empty for ${product.name}", nutrition.vitamins.isNotEmpty())
            assertTrue("Health benefit should not be blank for ${product.name}", nutrition.healthBenefit.isNotBlank())
        }
    }

    @Test
    fun testLoadingStateTransitionsFromTrueToFalseWhenProductsLoaded() = runTest(testDispatcher) {
        val dao = FakeProductDao()
        val repository = ProductRepository(dao, FakeApiService())
        val viewModel = HomeViewModel(repository)

        // Initially true before coroutine finishes loading
        assertTrue(viewModel.isLoading.value)

        // Start the init coroutine up to the Dispatchers.IO suspension point
        testDispatcher.scheduler.runCurrent()

        // Wait for Dispatchers.IO inside repository.ensureInitialData() to complete
        var attempts = 0
        while (dao.flow.value.isEmpty() && attempts < 50) {
            Thread.sleep(20)
            attempts++
        }
        advanceUntilIdle()

        // After data is loaded, isLoading becomes false and products are populated
        assertFalse(viewModel.isLoading.value)
        assertTrue(viewModel.products.value.isNotEmpty())
    }
}
