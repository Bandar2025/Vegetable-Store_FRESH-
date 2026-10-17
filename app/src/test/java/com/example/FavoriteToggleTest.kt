package com.example

import com.example.data.api.FreshApiService
import com.example.data.api.model.CategoryDto
import com.example.data.api.model.OrderRequest
import com.example.data.api.model.OrderResponse
import com.example.data.api.model.ProductDto
import com.example.data.local.dao.ProductDao
import com.example.data.local.model.Product
import com.example.data.repository.ProductRepository
import com.example.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteToggleTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeProductDao : ProductDao {
        val flow = MutableStateFlow<List<Product>>(emptyList())
        override fun getAllProducts(): Flow<List<Product>> = flow
        override suspend fun insert(product: Product) {}
        override suspend fun insertAll(products: List<Product>) {}
        override suspend fun count(): Int = 0
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
    fun testToggleFavoriteAddsAndRemoves() {
        val repository = ProductRepository(FakeProductDao(), FakeApiService())
        val viewModel = HomeViewModel(repository)

        val targetProductId = 2 // Product not initially in default set {1, 3}

        // Initially not in favorites
        assertFalse(viewModel.favoriteIds.value.contains(targetProductId))

        // Toggle to add
        viewModel.toggleFavorite(targetProductId)
        assertTrue(viewModel.favoriteIds.value.contains(targetProductId))

        // Toggle again to remove
        viewModel.toggleFavorite(targetProductId)
        assertFalse(viewModel.favoriteIds.value.contains(targetProductId))
    }

    @Test
    fun testInitialFavoriteCanBeRemoved() {
        val repository = ProductRepository(FakeProductDao(), FakeApiService())
        val viewModel = HomeViewModel(repository)

        // Initially product 1 is in favorites
        assertTrue(viewModel.favoriteIds.value.contains(1))

        // Toggle to remove
        viewModel.toggleFavorite(1)
        assertFalse(viewModel.favoriteIds.value.contains(1))
    }
}
