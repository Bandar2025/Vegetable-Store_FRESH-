package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.local.model.Product
import com.example.di.Injection
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ProductDetailScreen
import com.example.ui.screens.CartScreen
import com.example.ui.screens.CheckoutScreen
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.HomeViewModelFactory
import com.example.ui.viewmodel.CartViewModel
import com.example.ui.viewmodel.CartViewModelFactory
import kotlinx.serialization.Serializable

@Serializable object Home
@Serializable data class ProductDetail(val productId: Int)
@Serializable object Cart
@Serializable object Checkout

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    NavHost(navController = navController, startDestination = Home) {
        composable<Home> {
            val viewModel: HomeViewModel = viewModel(
                factory = HomeViewModelFactory(Injection.provideProductRepository(context))
            )
            HomeScreen(viewModel, onProductClick = { navController.navigate(ProductDetail(it.id)) })
        }
        composable<ProductDetail> { backStackEntry ->
            val args = backStackEntry.toRoute<ProductDetail>()
            val cartVm: CartViewModel = viewModel(
                factory = CartViewModelFactory(Injection.provideCartRepository(context), Injection.provideProductRepository(context))
            )
            val product = Product(args.productId, "منتج تجريبي", "فئة", 1000, "وصف", "img", "حبة")
            ProductDetailScreen(product = product, onAddToCart = {
                cartVm.addToCart(product.id, 1)
                navController.navigate(Cart)
            }, onBack = { navController.popBackStack() })
        }
        composable<Cart> {
            val viewModel: CartViewModel = viewModel(
                factory = CartViewModelFactory(Injection.provideCartRepository(context), Injection.provideProductRepository(context))
            )
            CartScreen(viewModel, onCheckout = { navController.navigate(Checkout) }, onBack = { navController.popBackStack() })
        }
        composable<Checkout> {
            val viewModel: CartViewModel = viewModel(
                factory = CartViewModelFactory(Injection.provideCartRepository(context), Injection.provideProductRepository(context))
            )
            CheckoutScreen(viewModel, onConfirm = { navController.navigate(Home) })
        }
    }
}
