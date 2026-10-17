package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.auth.PostLoginDestination
import com.example.data.local.model.Product
import com.example.di.Injection
import com.example.ui.screens.AccountProfileScreen
import com.example.ui.screens.CartScreen
import com.example.ui.screens.CheckoutScreen
import com.example.ui.screens.CreateAccountScreen
import com.example.ui.screens.ForgotPasswordScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.ProductDetailScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StoreOwnerFoundationScreen
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.AuthViewModelFactory
import com.example.ui.viewmodel.CartViewModel
import com.example.ui.viewmodel.CartViewModelFactory
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.HomeViewModelFactory
import kotlinx.serialization.Serializable

@Serializable object Home
@Serializable data class ProductDetail(val productId: Int)
@Serializable object Cart
@Serializable object Checkout
@Serializable object Login
@Serializable object CreateAccount
@Serializable object ForgotPassword
@Serializable object AccountProfile
@Serializable object Settings
@Serializable object StoreManagement

/**
 * Executes the single post-login navigation decision while clearing auth screens from the backstack
 * so pressing Back never loops into Login or CreateAccount.
 */
fun NavHostController.navigateAfterAuthentication(destination: PostLoginDestination) {
    when (destination) {
        PostLoginDestination.CUSTOMER_HOME -> {
            navigate(Home) {
                popUpTo(graph.findStartDestination().id) {
                    inclusive = false
                }
                launchSingleTop = true
            }
        }
        PostLoginDestination.STORE_MANAGEMENT -> {
            navigate(StoreManagement) {
                popUpTo(graph.findStartDestination().id) {
                    inclusive = false
                }
                launchSingleTop = true
            }
        }
    }
}

/**
 * Clears any protected screens (such as [StoreManagement]) from the backstack upon logout
 * and returns cleanly to [Home] without navigation loops.
 */
fun NavHostController.navigateAfterLogout() {
    navigate(Home) {
        popUpTo(graph.findStartDestination().id) {
            inclusive = true
        }
        launchSingleTop = true
    }
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    authViewModelOverride: AuthViewModel? = null
) {
    val context = LocalContext.current
    val productRepository = remember(context) { Injection.provideProductRepository(context) }
    val cartRepository = remember(context) { Injection.provideCartRepository(context) }
    val authRepository = remember(context) { Injection.provideAuthRepository(context) }

    val authViewModel: AuthViewModel = authViewModelOverride ?: viewModel(
        factory = AuthViewModelFactory(authRepository)
    )

    NavHost(navController = navController, startDestination = Home) {
        composable<Home> {
            val viewModel: HomeViewModel = viewModel(
                factory = HomeViewModelFactory(productRepository)
            )
            val cartVm: CartViewModel = viewModel(
                factory = CartViewModelFactory(cartRepository, productRepository)
            )
            HomeScreen(
                viewModel = viewModel,
                onProductClick = { navController.navigate(ProductDetail(it.id)) },
                onCartClick = { navController.navigate(Cart) },
                onAddToCart = { product -> cartVm.addToCart(product.id, 1) },
                onAccountClick = {
                    navController.navigate(AccountProfile) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable<ProductDetail> { backStackEntry ->
            val args = backStackEntry.toRoute<ProductDetail>()
            val cartVm: CartViewModel = viewModel(
                factory = CartViewModelFactory(cartRepository, productRepository)
            )
            val product = Product(args.productId, "منتج تجريبي", "فئة", 1000, "وصف", "img", "حبة")
            ProductDetailScreen(
                product = product,
                onAddToCart = {
                    cartVm.addToCart(product.id, 1)
                    navController.navigate(Cart)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable<Cart> {
            val viewModel: CartViewModel = viewModel(
                factory = CartViewModelFactory(cartRepository, productRepository)
            )
            CartScreen(
                viewModel = viewModel,
                onCheckout = { navController.navigate(Checkout) },
                onBack = { navController.popBackStack() }
            )
        }

        composable<Checkout> {
            val viewModel: CartViewModel = viewModel(
                factory = CartViewModelFactory(cartRepository, productRepository)
            )
            CheckoutScreen(
                viewModel = viewModel,
                onConfirm = {
                    navController.navigate(Home) {
                        popUpTo(Home) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable<Login> {
            LoginScreen(
                viewModel = authViewModel,
                onAuthenticated = { destination ->
                    navController.navigateAfterAuthentication(destination)
                },
                onNavigateToRegister = {
                    navController.navigate(CreateAccount) {
                        launchSingleTop = true
                    }
                },
                onNavigateToForgotPassword = {
                    navController.navigate(ForgotPassword) {
                        launchSingleTop = true
                    }
                },
                onBackToShopping = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Home) {
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        composable<CreateAccount> {
            CreateAccountScreen(
                viewModel = authViewModel,
                onRegistered = { destination ->
                    navController.navigateAfterAuthentication(destination)
                },
                onNavigateToLogin = {
                    navController.navigate(Login) {
                        popUpTo(Login) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Home) {
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        composable<ForgotPassword> {
            ForgotPasswordScreen(
                viewModel = authViewModel,
                onBackToLogin = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Login) {
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        composable<AccountProfile> {
            AccountProfileScreen(
                viewModel = authViewModel,
                onNavigateToLogin = {
                    navController.navigate(Login) {
                        launchSingleTop = true
                    }
                },
                onNavigateToRegister = {
                    navController.navigate(CreateAccount) {
                        launchSingleTop = true
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(Settings) {
                        launchSingleTop = true
                    }
                },
                onNavigateToStoreManagement = {
                    val decision = authViewModel.checkRouteAccess(
                        requiresAuth = true,
                        requiresStoreManagement = true
                    )
                    if (decision is com.example.data.auth.AccessControlDecision.Allowed) {
                        navController.navigate(StoreManagement) {
                            launchSingleTop = true
                        }
                    } else {
                        navController.navigate(Login) {
                            launchSingleTop = true
                        }
                    }
                },
                onBackToShopping = {
                    if (!navController.popBackStack(Home, inclusive = false)) {
                        navController.navigate(Home) {
                            launchSingleTop = true
                        }
                    }
                },
                onLoggedOut = {
                    navController.navigateAfterLogout()
                }
            )
        }

        composable<Settings> {
            SettingsScreen(
                viewModel = authViewModel,
                onNavigateToAccount = {
                    navController.navigate(AccountProfile) {
                        launchSingleTop = true
                    }
                },
                onNavigateToLogin = {
                    navController.navigate(Login) {
                        launchSingleTop = true
                    }
                },
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Home) {
                            launchSingleTop = true
                        }
                    }
                },
                onLoggedOut = {
                    navController.navigateAfterLogout()
                }
            )
        }

        composable<StoreManagement> {
            StoreOwnerFoundationScreen(
                viewModel = authViewModel,
                onSwitchToCustomerStorefront = {
                    navController.navigate(Home) {
                        launchSingleTop = true
                    }
                },
                onNavigateToAccount = {
                    navController.navigate(AccountProfile) {
                        launchSingleTop = true
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(Settings) {
                        launchSingleTop = true
                    }
                },
                onNavigateToLogin = {
                    navController.navigate(Login) {
                        launchSingleTop = true
                    }
                },
                onLoggedOut = {
                    navController.navigateAfterLogout()
                }
            )
        }
    }
}
