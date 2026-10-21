package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.auth.AccessControlDecision
import com.example.data.auth.AccessControlPolicy
import com.example.data.auth.AuthenticatedUser
import com.example.data.auth.UserRole
import com.example.data.local.model.OrderStatus
import com.example.data.local.model.Product
import com.example.data.local.model.ProductExpiryStatus
import com.example.data.repository.OrderWithItems
import com.example.data.local.model.StockAdjustment
import com.example.data.repository.ProductRepository
import com.example.ui.components.ProductVisual
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.AccentRed
import com.example.ui.theme.ActionGreen
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardSurface
import com.example.ui.theme.FreshBackground
import com.example.ui.theme.FreshGreen
import com.example.ui.theme.FreshGreenDark
import com.example.ui.theme.FreshGreenLight
import com.example.ui.theme.TextDarkGreen
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.White
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.ProductFormInput
import com.example.ui.viewmodel.StoreDashboardMetrics
import com.example.ui.viewmodel.StoreManagementSection
import com.example.ui.viewmodel.StoreManagementViewModel
import com.example.ui.viewmodel.StoreProductStatusFilter

@Composable
fun StoreOwnerPortalScreen(
    authViewModel: AuthViewModel,
    storeViewModel: StoreManagementViewModel?,
    onSwitchToCustomerStorefront: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val currentUser by authViewModel.currentUser.collectAsState()
    val showLogoutDialog by authViewModel.showLogoutConfirmation.collectAsState()

    val accessDecision = AccessControlPolicy.checkAccess(
        user = currentUser,
        requiresAuth = true,
        requiresStoreManagement = true
    )

    if (showLogoutDialog) {
        LogoutConfirmationDialog(
            user = currentUser,
            onConfirmLogout = {
                authViewModel.confirmLogout(onLoggedOut = onLoggedOut)
            },
            onDismiss = {
                authViewModel.dismissLogoutConfirmation()
            }
        )
    }

    val currentSection = storeViewModel?.currentSection?.collectAsState()?.value
        ?: StoreManagementSection.DASHBOARD

    BackHandler(enabled = currentSection != StoreManagementSection.DASHBOARD) {
        storeViewModel?.navigateToSection(StoreManagementSection.DASHBOARD)
    }

    val scrollState = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(currentSection) {
        scrollState.scrollTo(0)
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            containerColor = FreshBackground,
            contentWindowInsets = WindowInsets.statusBars
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .testTag("store_owner_foundation_screen")
            ) {
                if (accessDecision is AccessControlDecision.Denied) {
                    StoreManagementUnauthorizedGuard(
                        reasonArabic = accessDecision.reasonArabic,
                        isUnauthenticated = currentUser == null,
                        onSwitchToCustomerStorefront = onSwitchToCustomerStorefront,
                        onNavigateToLogin = onNavigateToLogin
                    )
                } else {
                    val user = currentUser!!
                    StoreOwnerAuthorizedHeader(
                        user = user,
                        currentSection = currentSection,
                        onBackToDashboard = {
                            storeViewModel?.navigateToSection(StoreManagementSection.DASHBOARD)
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (storeViewModel == null) {
                        // Fallback when StoreOwnerFoundationScreen is rendered in isolation without StoreManagementViewModel
                        StorePortalMinimalFallbackActions(
                            onSwitchToCustomerStorefront = onSwitchToCustomerStorefront,
                            onNavigateToAccount = onNavigateToAccount,
                            onNavigateToSettings = onNavigateToSettings,
                            onRequestLogout = { authViewModel.requestLogoutConfirmation() }
                        )
                    } else {
                        val allProducts by storeViewModel.allProducts.collectAsState()
                        val filteredProducts by storeViewModel.filteredProducts.collectAsState()
                        val metrics by storeViewModel.metrics.collectAsState()
                        val isLoading by storeViewModel.isLoading.collectAsState()
                        val searchQuery by storeViewModel.searchQuery.collectAsState()
                        val selectedCategory by storeViewModel.selectedCategory.collectAsState()
                        val statusFilter by storeViewModel.statusFilter.collectAsState()
                        val formState by storeViewModel.formState.collectAsState()
                        val errorMessage by storeViewModel.errorMessage.collectAsState()
                        val statusBannerMessage by storeViewModel.statusBannerMessage.collectAsState()
                        val expiryWarningDays by storeViewModel.expiryWarningDays.collectAsState()
                        val referenceDateIso by storeViewModel.referenceDateIsoFlow.collectAsState()
                        val stockAdjustments by storeViewModel.stockAdjustments.collectAsState()
                        val productPendingArchive by storeViewModel.productPendingArchive.collectAsState()
                        val productForStockDialog by storeViewModel.productForStockDialog.collectAsState()
                        val storeOrders by storeViewModel.storeOrders.collectAsState()
                        val selectedOrderStatusFilter by storeViewModel.selectedOrderStatusFilter.collectAsState()
                        val selectedOrderId by storeViewModel.selectedOrderId.collectAsState()

                        productForStockDialog?.let { dialogProduct ->
                            StockAdjustmentDialog(
                                product = dialogProduct,
                                errorMessage = errorMessage,
                                onConfirm = { newQtyInput, reasonInput ->
                                    storeViewModel.submitStockAdjustmentWithReason(
                                        actor = user,
                                        product = dialogProduct,
                                        newQuantityInput = newQtyInput,
                                        reasonInput = reasonInput,
                                        expectedPreviousQuantity = dialogProduct.stockQuantity
                                    )
                                },
                                onDismiss = { storeViewModel.dismissStockAdjustmentDialog() }
                            )
                        }

                        productPendingArchive?.let { pendingProduct ->
                            ArchiveProductConfirmationDialog(
                                product = pendingProduct,
                                onConfirm = { storeViewModel.confirmArchiveOrRestore(user) },
                                onDismiss = { storeViewModel.dismissArchiveConfirmation() }
                            )
                        }

                        // Feedback banners
                        if (!errorMessage.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFFFEF2F2),
                                border = BorderStroke(1.dp, AccentRed),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .testTag("store_error_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = AccentRed)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = errorMessage!!,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AccentRed,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { storeViewModel.clearFeedbackMessages() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Clear, contentDescription = "إغلاق التنبيه", tint = AccentRed)
                                    }
                                }
                            }
                        }

                        if (!statusBannerMessage.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = FreshGreenLight,
                                border = BorderStroke(1.dp, FreshGreen),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .testTag("store_status_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = FreshGreen)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = statusBannerMessage!!,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextDarkGreen,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { storeViewModel.clearFeedbackMessages() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Clear, contentDescription = "إغلاق الرسالة", tint = TextDarkGreen)
                                    }
                                }
                            }
                        }

                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 36.dp)
                                    .testTag("store_dashboard_loading"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = FreshGreen)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "جاري تحميل بيانات المتجر والمنتجات...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary
                                    )
                                }
                            }
                        } else {
                            when (currentSection) {
                                StoreManagementSection.DASHBOARD -> {
                                    StoreDashboardMainContent(
                                        metrics = metrics,
                                        referenceDateIso = referenceDateIso,
                                        expiryWarningDays = expiryWarningDays,
                                        recentProducts = storeViewModel.getRecentUpdatedProducts(5),
                                        onAddProduct = { storeViewModel.openAddProductForm() },
                                        onManageProducts = {
                                            storeViewModel.navigateToSection(
                                                StoreManagementSection.PRODUCT_LIST,
                                                StoreProductStatusFilter.ALL
                                            )
                                        },
                                        onOpenInventory = {
                                            storeViewModel.navigateToSection(StoreManagementSection.INVENTORY)
                                        },
                                        onOpenOrders = {
                                            storeViewModel.navigateToSection(StoreManagementSection.ORDERS_NOTICE)
                                        },
                                        onFilterByActive = {
                                            storeViewModel.navigateToSection(
                                                StoreManagementSection.PRODUCT_LIST,
                                                StoreProductStatusFilter.ACTIVE_ONLY
                                            )
                                        },
                                        onFilterByLowStock = {
                                            storeViewModel.navigateToSection(
                                                StoreManagementSection.PRODUCT_LIST,
                                                StoreProductStatusFilter.LOW_STOCK
                                            )
                                        },
                                        onFilterByExpiringSoon = {
                                            storeViewModel.navigateToSection(
                                                StoreManagementSection.PRODUCT_LIST,
                                                StoreProductStatusFilter.EXPIRING_SOON
                                            )
                                        },
                                        onFilterByExpired = {
                                            storeViewModel.navigateToSection(
                                                StoreManagementSection.PRODUCT_LIST,
                                                StoreProductStatusFilter.EXPIRED
                                            )
                                        },
                                        onFilterByOutOfStock = {
                                            storeViewModel.navigateToSection(
                                                StoreManagementSection.PRODUCT_LIST,
                                                StoreProductStatusFilter.OUT_OF_STOCK
                                            )
                                        },
                                        onProductClick = { product ->
                                            storeViewModel.openProductDetails(product.id)
                                        },
                                        onSwitchToCustomerStorefront = onSwitchToCustomerStorefront,
                                        onNavigateToAccount = onNavigateToAccount,
                                        onNavigateToSettings = onNavigateToSettings,
                                        onRequestLogout = { authViewModel.requestLogoutConfirmation() }
                                    )
                                }

                                StoreManagementSection.PRODUCT_LIST -> {
                                    StoreProductListContent(
                                        products = filteredProducts,
                                        totalCount = allProducts.size,
                                        referenceDateIso = referenceDateIso,
                                        expiryWarningDays = expiryWarningDays,
                                        searchQuery = searchQuery,
                                        selectedCategory = selectedCategory,
                                        statusFilter = statusFilter,
                                        onSearchQueryChange = storeViewModel::onSearchQueryChange,
                                        onCategorySelect = storeViewModel::onCategorySelect,
                                        onStatusFilterSelect = storeViewModel::onStatusFilterSelect,
                                        onAddProduct = { storeViewModel.openAddProductForm() },
                                        onViewDetails = { storeViewModel.openProductDetails(it.id) },
                                        onEditProduct = { storeViewModel.openEditProductForm(it) },
                                        onToggleActive = { storeViewModel.toggleProductActiveStatus(user, it) },
                                        onToggleArchive = { storeViewModel.requestArchiveConfirmation(it) },
                                        onBackToDashboard = {
                                            storeViewModel.navigateToSection(StoreManagementSection.DASHBOARD)
                                        }
                                    )
                                }

                                StoreManagementSection.ADD_PRODUCT,
                                StoreManagementSection.EDIT_PRODUCT -> {
                                    val isEditing = currentSection == StoreManagementSection.EDIT_PRODUCT
                                    StoreProductFormContent(
                                        isEditing = isEditing,
                                        formState = formState,
                                        errorMessage = errorMessage,
                                        onFormChange = storeViewModel::updateFormState,
                                        onSave = {
                                            storeViewModel.submitProductForm(actor = user)
                                        },
                                        onCancel = {
                                            storeViewModel.navigateToSection(StoreManagementSection.PRODUCT_LIST)
                                        }
                                    )
                                }

                                StoreManagementSection.PRODUCT_DETAILS -> {
                                    val selectedProduct = storeViewModel.getSelectedProduct()
                                    val productAdjustments = selectedProduct?.let { prod ->
                                        stockAdjustments.filter { it.productId == prod.id }
                                    }.orEmpty()
                                    StoreProductDetailsContent(
                                        product = selectedProduct,
                                        referenceDateIso = referenceDateIso,
                                        expiryWarningDays = expiryWarningDays,
                                        productAdjustments = productAdjustments,
                                        onEdit = { product -> storeViewModel.openEditProductForm(product) },
                                        onAdjustStock = { product, delta ->
                                            storeViewModel.adjustProductStock(user, product, delta)
                                        },
                                        onOpenStockDialog = { product ->
                                            storeViewModel.openStockAdjustmentDialog(product)
                                        },
                                        onToggleActive = { product ->
                                            storeViewModel.toggleProductActiveStatus(user, product)
                                        },
                                        onToggleArchive = { product ->
                                            storeViewModel.requestArchiveConfirmation(product)
                                        },
                                        onBackToList = {
                                            storeViewModel.navigateToSection(StoreManagementSection.PRODUCT_LIST)
                                        }
                                    )
                                }

                                StoreManagementSection.INVENTORY -> {
                                    StoreInventoryDashboardContent(
                                        filteredProducts = filteredProducts,
                                        metrics = metrics,
                                        referenceDateIso = referenceDateIso,
                                        expiryWarningDays = expiryWarningDays,
                                        searchQuery = searchQuery,
                                        selectedCategory = selectedCategory,
                                        statusFilter = statusFilter,
                                        stockAdjustments = stockAdjustments,
                                        onSearchQueryChange = storeViewModel::onSearchQueryChange,
                                        onCategorySelect = storeViewModel::onCategorySelect,
                                        onStatusFilterSelect = storeViewModel::onStatusFilterSelect,
                                        onExpiryWarningDaysChange = storeViewModel::setExpiryWarningDays,
                                        onAdjustStockQuick = { product, delta ->
                                            storeViewModel.adjustProductStock(user, product, delta)
                                        },
                                        onOpenStockAdjustDialog = { product ->
                                            storeViewModel.openStockAdjustmentDialog(product)
                                        },
                                        onViewDetails = { product ->
                                            storeViewModel.openProductDetails(product.id)
                                        },
                                        onEditProduct = { storeViewModel.openEditProductForm(it) },
                                        onToggleActive = { product ->
                                            storeViewModel.toggleProductActiveStatus(user, product)
                                        },
                                        onRequestArchiveConfirmation = { product ->
                                            storeViewModel.requestArchiveConfirmation(product)
                                        },
                                        onBackToDashboard = {
                                            storeViewModel.navigateToSection(StoreManagementSection.DASHBOARD)
                                        }
                                    )
                                }

                                StoreManagementSection.ORDERS_NOTICE -> {
                                    val allVisibleOrders = if (storeOrders.isNotEmpty()) {
                                        storeViewModel.getVisibleOrdersForActor(user, statusFilter = null)
                                    } else {
                                        emptyList()
                                    }
                                    val filteredOrders = if (storeOrders.isNotEmpty()) {
                                        storeViewModel.getVisibleOrdersForActor(user, statusFilter = selectedOrderStatusFilter)
                                    } else {
                                        emptyList()
                                    }
                                    val selectedOrder = allVisibleOrders.find { it.order.id == selectedOrderId }
                                    StoreOrdersHonestNoticeContent(
                                        unavailableNote = metrics.unavailableStatsNoteArabic,
                                        persistedOrders = filteredOrders,
                                        totalOrdersCount = allVisibleOrders.size,
                                        selectedStatusFilter = selectedOrderStatusFilter,
                                        selectedOrderWithItems = selectedOrder,
                                        onSelectStatusFilter = { filter ->
                                            storeViewModel.selectOrderStatusFilter(filter)
                                        },
                                        onOpenOrderDetails = { orderId ->
                                            storeViewModel.openOrderDetails(orderId)
                                        },
                                        onCloseOrderDetails = {
                                            storeViewModel.closeOrderDetails()
                                        },
                                        onTransitionStatus = { orderId, targetStatus ->
                                            storeViewModel.transitionOrderStatus(
                                                actor = user,
                                                orderId = orderId,
                                                targetStatus = targetStatus
                                            )
                                        },
                                        onCancelOrder = { orderId ->
                                            storeViewModel.cancelOrder(
                                                actor = user,
                                                orderId = orderId
                                            )
                                        },
                                        onBackToDashboard = {
                                            storeViewModel.closeOrderDetails()
                                            storeViewModel.navigateToSection(StoreManagementSection.DASHBOARD)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreManagementUnauthorizedGuard(
    reasonArabic: String,
    isUnauthenticated: Boolean,
    onSwitchToCustomerStorefront: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFEF2F2),
        border = BorderStroke(1.dp, AccentRed),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("store_management_unauthorized_card")
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = AccentRed,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "غير مصرح بالوصول إلى إدارة المتجر",
                style = MaterialTheme.typography.titleLarge,
                color = TextDarkGreen,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = reasonArabic,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onSwitchToCustomerStorefront,
                colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("unauthorized_return_home_button")
            ) {
                Text("العودة إلى متجر التسوق (العميل)", color = White, fontWeight = FontWeight.Bold)
            }
            if (isUnauthenticated) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onNavigateToLogin,
                    border = BorderStroke(1.dp, FreshGreen),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("unauthorized_login_button")
                ) {
                    Text("تسجيل الدخول", color = FreshGreen, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StoreOwnerAuthorizedHeader(
    user: AuthenticatedUser,
    currentSection: StoreManagementSection,
    onBackToDashboard: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = FreshGreenDark,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("store_management_authorized_header")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (currentSection != StoreManagementSection.DASHBOARD) {
                        IconButton(
                            onClick = onBackToDashboard,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("header_back_to_dashboard_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "العودة للوحة التحكم",
                                tint = White
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    } else {
                        Icon(
                            imageVector = if (user.role == UserRole.STORE_OWNER) {
                                Icons.Default.Storefront
                            } else {
                                Icons.Default.AdminPanelSettings
                            },
                            contentDescription = null,
                            tint = ActionGreen,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Column {
                        Text(
                            text = "متجر فريش للأغذية الطازجة",
                            style = MaterialTheme.typography.titleMedium,
                            color = White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = currentSection.arabicTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = ActionGreen
                        )
                    }
                }

                Surface(
                    color = AccentOrange,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = user.role.arabicTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("store_owner_role_badge")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "المسؤول: ${user.fullName} • ${user.email}",
                style = MaterialTheme.typography.bodyMedium,
                color = White.copy(alpha = 0.92f)
            )
            if (user.isLocalDemoAccount) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "⚠️ جلسة تطوير محلية تجريبية (Local Dev / Demo Session Only)",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentOrange,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StoreDashboardMainContent(
    metrics: StoreDashboardMetrics,
    referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO,
    expiryWarningDays: Int = Product.DEFAULT_EXPIRY_WARNING_DAYS,
    recentProducts: List<Product>,
    onAddProduct: () -> Unit,
    onManageProducts: () -> Unit,
    onOpenInventory: () -> Unit,
    onOpenOrders: () -> Unit,
    onFilterByActive: () -> Unit = {},
    onFilterByLowStock: () -> Unit,
    onFilterByExpiringSoon: () -> Unit = {},
    onFilterByExpired: () -> Unit,
    onFilterByOutOfStock: () -> Unit,
    onProductClick: (Product) -> Unit,
    onSwitchToCustomerStorefront: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRequestLogout: () -> Unit
) {
    // 1. Summary Cards Grid (3x2)
    Text(
        text = "ملخص المخزون والمنتجات (بيانات محلية فعلية)",
        style = MaterialTheme.typography.titleMedium,
        color = TextDarkGreen,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DashboardSummaryCard(
            title = "إجمالي المنتجات",
            value = metrics.totalProductsCount.toString(),
            subtitle = "نشط: ${metrics.activeProductsCount} • مخفي: ${metrics.inactiveProductsCount}",
            accentColor = FreshGreen,
            icon = Icons.Default.Inventory2,
            cardTag = "summary_card_total_products",
            valueTag = "metric_total_products_value",
            onClick = onManageProducts,
            modifier = Modifier.weight(1f)
        )
        DashboardSummaryCard(
            title = "المنتجات النشطة",
            value = metrics.activeProductsCount.toString(),
            subtitle = "المخزون النشط: ${metrics.totalAvailableStockUnits} وحدة",
            accentColor = FreshGreenDark,
            icon = Icons.Default.Storefront,
            cardTag = "summary_card_active_products",
            valueTag = "metric_active_products_value",
            onClick = onFilterByActive,
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DashboardSummaryCard(
            title = "منخفض المخزون",
            value = metrics.lowStockCount.toString(),
            subtitle = "يحتاج إعادة تزويد قريباً",
            accentColor = AccentOrange,
            icon = Icons.Default.WarningAmber,
            cardTag = "summary_card_low_stock",
            valueTag = "metric_low_stock_value",
            onClick = onFilterByLowStock,
            modifier = Modifier.weight(1f)
        )
        DashboardSummaryCard(
            title = "يقترب من الانتهاء",
            value = metrics.expiringSoonCount.toString(),
            subtitle = "خلال $expiryWarningDays أيام",
            accentColor = AccentOrange,
            icon = Icons.Default.WarningAmber,
            cardTag = "summary_card_expiring_soon",
            valueTag = "metric_expiring_soon_value",
            onClick = onFilterByExpiringSoon,
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DashboardSummaryCard(
            title = "منتهي الصلاحية",
            value = metrics.expiredCount.toString(),
            subtitle = "تجاوز تاريخ الصلاحية",
            accentColor = AccentRed,
            icon = Icons.Default.EventBusy,
            cardTag = "summary_card_expired_products",
            valueTag = "metric_expired_products_value",
            onClick = onFilterByExpired,
            modifier = Modifier.weight(1f)
        )
        DashboardSummaryCard(
            title = "نفد من المخزون",
            value = metrics.outOfStockCount.toString(),
            subtitle = "الكمية المتوفرة صفر",
            accentColor = AccentRed,
            icon = Icons.Default.ErrorOutline,
            cardTag = "summary_card_out_of_stock",
            valueTag = "metric_out_of_stock_value",
            onClick = onFilterByOutOfStock,
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Honest Unavailable Sales/Orders Statistics Card
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFFFBEB),
        border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("unavailable_orders_revenue_card")
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = AccentOrange,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "إحصاءات المبيعات والإيرادات والطلبات: غير متوفرة حالياً",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextDarkGreen,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = metrics.unavailableStatsNoteArabic,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }

    // Prominent Low-Stock & Below-Threshold Warning Alert Banner on Store Owner Dashboard
    if (metrics.lowStockCount > 0 || metrics.outOfStockCount > 0) {
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFFFFF7ED),
            border = BorderStroke(1.5.dp, AccentOrange),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onFilterByLowStock)
                .testTag("dashboard_low_stock_warning_banner")
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(AccentOrange.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "تحذير: منتجات دون الحد الأدنى للمخزون",
                        tint = AccentOrange,
                        modifier = Modifier
                            .size(22.dp)
                            .testTag("dashboard_low_stock_warning_icon")
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "تنبيه انخفاض المخزون: ${metrics.lowStockCount + metrics.outOfStockCount} منتج دون الحد الأدنى",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("dashboard_low_stock_warning_title")
                    )
                    Text(
                        text = "المخزون المتوفر وصل أو انخفض عن الحد المخصص. اضغط لعرض المنتجات وإعادة التزويد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(18.dp))

    // 2. Quick Actions
    Text(
        text = "الإجراءات السريعة لإدارة المتجر",
        style = MaterialTheme.typography.titleMedium,
        color = TextDarkGreen,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        QuickActionTile(
            label = "إضافة منتج",
            subtitle = "إدراج صنف جديد",
            icon = Icons.Default.Add,
            containerColor = FreshGreen,
            contentColor = White,
            testTag = "quick_action_add_product",
            onClick = onAddProduct,
            modifier = Modifier.weight(1f)
        )
        QuickActionTile(
            label = "إدارة المنتجات",
            subtitle = "بحث وتعديل وأرشفة",
            icon = Icons.Default.Storefront,
            containerColor = CardSurface,
            contentColor = TextDarkGreen,
            testTag = "quick_action_manage_products",
            onClick = onManageProducts,
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        QuickActionTile(
            label = "المخزون",
            subtitle = "الكميات والصلاحية",
            icon = Icons.Default.Inventory2,
            containerColor = CardSurface,
            contentColor = TextDarkGreen,
            testTag = "quick_action_inventory",
            onClick = onOpenInventory,
            modifier = Modifier.weight(1f)
        )
        QuickActionTile(
            label = "الطلبات",
            subtitle = "حالة سجل الطلبات",
            icon = Icons.Default.ReceiptLong,
            containerColor = CardSurface,
            contentColor = TextDarkGreen,
            testTag = "quick_action_orders",
            onClick = onOpenOrders,
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(18.dp))

    // 3. Recent Product Updates Section
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "أحدث تحديثات المنتجات",
            style = MaterialTheme.typography.titleMedium,
            color = TextDarkGreen,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "عرض الكل (${metrics.totalProductsCount})",
            style = MaterialTheme.typography.labelLarge,
            color = FreshGreen,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable(onClick = onManageProducts)
                .padding(4.dp)
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recent_updates_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (recentProducts.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardSurface,
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_empty_products_state")
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "لا توجد منتجات مسجلة حالياً",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "ابدأ بإضافة أول منتج طازج إلى قاعدة بيانات المتجر المحلية.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        } else {
            recentProducts.forEach { product ->
                RecentProductUpdateRow(
                    product = product,
                    referenceDateIso = referenceDateIso,
                    expiryWarningDays = expiryWarningDays,
                    onClick = { onProductClick(product) }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // 4. Footer Navigation & Session Actions
    StorePortalMinimalFallbackActions(
        onSwitchToCustomerStorefront = onSwitchToCustomerStorefront,
        onNavigateToAccount = onNavigateToAccount,
        onNavigateToSettings = onNavigateToSettings,
        onRequestLogout = onRequestLogout
    )
}

@Composable
private fun DashboardSummaryCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    icon: ImageVector,
    cardTag: String,
    valueTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = modifier
            .clickable(onClick = onClick)
            .testTag(cardTag)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = accentColor,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.testTag(valueTag)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QuickActionTile(
    label: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = if (containerColor == CardSurface) BorderStroke(1.dp, BorderSubtle) else null,
        modifier = modifier
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (containerColor == CardSurface) FreshGreenLight else White.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (containerColor == CardSurface) FreshGreen else White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.78f)
                )
            }
        }
    }
}

@Composable
private fun RecentProductUpdateRow(
    product: Product,
    referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO,
    expiryWarningDays: Int = Product.DEFAULT_EXPIRY_WARNING_DAYS,
    onClick: () -> Unit
) {
    val isBelowThreshold = product.stockQuantity <= product.lowStockThreshold
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardSurface,
        border = BorderStroke(
            width = if (isBelowThreshold) 1.5.dp else 1.dp,
            color = when {
                product.isOutOfStock -> AccentRed.copy(alpha = 0.65f)
                isBelowThreshold -> AccentOrange.copy(alpha = 0.65f)
                else -> BorderSubtle
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("recent_product_item_${product.id}")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProductVisual(
                imageUrl = product.imageUrl,
                size = 52.dp,
                contentDescription = product.name
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                    if (isBelowThreshold) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "تحذير: المخزون أقل من الحد الأدنى",
                            tint = if (product.isOutOfStock) AccentRed else AccentOrange,
                            modifier = Modifier
                                .size(16.dp)
                                .testTag("recent_product_warning_icon_${product.id}")
                        )
                    }
                }
                Text(
                    text = "${product.category} • ${product.formattedPriceSar()} ر.س / ${product.unit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            ProductStatusBadgesColumn(
                product = product,
                referenceDateIso = referenceDateIso,
                expiryWarningDays = expiryWarningDays
            )
        }
    }
}

@Composable
private fun ProductStatusBadgesColumn(
    product: Product,
    referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO,
    expiryWarningDays: Int = Product.DEFAULT_EXPIRY_WARNING_DAYS
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val (statusText, statusColor) = when {
            product.isArchived -> "مؤرشف" to TextSecondary
            product.isActive -> "نشط" to FreshGreen
            else -> "غير نشط" to AccentOrange
        }
        Surface(
            color = statusColor.copy(alpha = 0.14f),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        val (stockText, stockColor) = when {
            product.isOutOfStock -> "نفد (٠ • الحد: ${product.lowStockThreshold})" to AccentRed
            product.isLowStock -> "منخفض (${product.stockQuantity} • الحد: ${product.lowStockThreshold})" to AccentOrange
            else -> "المخزون: ${product.stockQuantity} (الحد: ${product.lowStockThreshold})" to TextDarkGreen
        }
        val isBelowThreshold = product.stockQuantity <= product.lowStockThreshold
        if (isBelowThreshold) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = stockColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, stockColor.copy(alpha = 0.5f)),
                modifier = Modifier.testTag("low_stock_warning_badge_${product.id}")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "تحذير: المخزون دون الحد الأدنى",
                        tint = stockColor,
                        modifier = Modifier
                            .size(13.dp)
                            .testTag("low_stock_warning_icon_${product.id}")
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stockText,
                        style = MaterialTheme.typography.labelSmall,
                        color = stockColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Text(
                text = stockText,
                style = MaterialTheme.typography.labelSmall,
                color = stockColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun StoreProductListContent(
    products: List<Product>,
    totalCount: Int,
    referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO,
    expiryWarningDays: Int = Product.DEFAULT_EXPIRY_WARNING_DAYS,
    searchQuery: String,
    selectedCategory: String,
    statusFilter: StoreProductStatusFilter,
    onSearchQueryChange: (String) -> Unit,
    onCategorySelect: (String) -> Unit,
    onStatusFilterSelect: (StoreProductStatusFilter) -> Unit,
    onAddProduct: () -> Unit,
    onViewDetails: (Product) -> Unit,
    onEditProduct: (Product) -> Unit,
    onToggleActive: (Product) -> Unit,
    onToggleArchive: (Product) -> Unit,
    onBackToDashboard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("store_product_list_screen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "قائمة منتجات المتجر (${products.size} من $totalCount)",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextDarkGreen,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "ابحث، صنف، أو عدّل حالة المنتجات والمخزون",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Button(
                onClick = onAddProduct,
                colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("product_list_add_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("إضافة منتج", color = White, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text("ابحث باسم المنتج أو الوصف...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = FreshGreen) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "مسح البحث")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = FreshGreen,
                unfocusedBorderColor = BorderSubtle,
                focusedContainerColor = CardSurface,
                unfocusedContainerColor = CardSurface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("store_product_search_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Category Filter Row
        val categories = listOf("الكل") + ProductRepository.SUPPORTED_CATEGORIES
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { category ->
                val isSelected = selectedCategory == category
                FilterChip(
                    selected = isSelected,
                    onClick = { onCategorySelect(category) },
                    label = { Text(category, fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FreshGreen,
                        selectedLabelColor = White
                    ),
                    modifier = Modifier.testTag("store_category_chip_$category")
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Status / Inventory Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StoreProductStatusFilter.values().forEach { filter ->
                val isSelected = statusFilter == filter
                FilterChip(
                    selected = isSelected,
                    onClick = { onStatusFilterSelect(filter) },
                    label = { Text(filter.arabicLabel) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FreshGreenDark,
                        selectedLabelColor = White
                    ),
                    modifier = Modifier.testTag("store_status_chip_${filter.name}")
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (products.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardSurface,
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("store_product_list_empty")
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "لا توجد منتجات مطابقة لمعايير البحث أو التصفية",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "جرب تغيير الفئة أو مسح نص البحث أو إضافة منتج جديد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                products.forEach { product ->
                    StoreProductManagementCard(
                        product = product,
                        referenceDateIso = referenceDateIso,
                        expiryWarningDays = expiryWarningDays,
                        onViewDetails = { onViewDetails(product) },
                        onEdit = { onEditProduct(product) },
                        onToggleActive = { onToggleActive(product) },
                        onToggleArchive = { onToggleArchive(product) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onBackToDashboard,
            border = BorderStroke(1.dp, FreshGreen),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("product_list_back_dashboard_button")
        ) {
            Text("العودة إلى لوحة التحكم الرئيسية", color = FreshGreen, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StoreProductManagementCard(
    product: Product,
    referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO,
    expiryWarningDays: Int = Product.DEFAULT_EXPIRY_WARNING_DAYS,
    onViewDetails: () -> Unit,
    onEdit: () -> Unit,
    onToggleActive: () -> Unit,
    onToggleArchive: () -> Unit
) {
    val expiryStatus = product.evaluateExpiryStatus(referenceDateIso, expiryWarningDays)
    val isExpired = expiryStatus == ProductExpiryStatus.EXPIRED
    val isExpiringSoon = expiryStatus == ProductExpiryStatus.EXPIRING_SOON

    val (expiryStatusLabel, expiryStatusColor) = when (expiryStatus) {
        ProductExpiryStatus.EXPIRED -> "(منتهي الصلاحية)" to AccentRed
        ProductExpiryStatus.EXPIRING_SOON -> "(يقترب من الانتهاء)" to AccentOrange
        ProductExpiryStatus.VALID -> "(صلاحية سارية)" to FreshGreen
        ProductExpiryStatus.NO_EXPIRY_DATE -> "(غير قابل للتلف)" to TextSecondary
        ProductExpiryStatus.INVALID_EXPIRY_DATE -> "(تاريخ غير صالح)" to AccentRed
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardSurface,
        border = BorderStroke(
            width = 1.dp,
            color = when {
                isExpired || product.isOutOfStock -> AccentRed.copy(alpha = 0.5f)
                isExpiringSoon || product.isLowStock -> AccentOrange.copy(alpha = 0.5f)
                else -> BorderSubtle
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("store_product_card_${product.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onViewDetails)
            ) {
                ProductVisual(
                    imageUrl = product.imageUrl,
                    size = 64.dp,
                    contentDescription = product.name
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val isBelowThreshold = product.stockQuantity <= product.lowStockThreshold
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = product.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextDarkGreen,
                            fontWeight = FontWeight.Bold
                        )
                        if (isBelowThreshold) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "تحذير: المخزون أقل من الحد الأدنى",
                                tint = if (product.isOutOfStock) AccentRed else AccentOrange,
                                modifier = Modifier
                                    .size(16.dp)
                                    .testTag("product_management_warning_icon_${product.id}")
                            )
                        }
                    }
                    Text(
                        text = "${product.category} • ${product.formattedPriceSar()} ر.س / ${product.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "الصلاحية: ${product.expiryDate.ifBlank { "غير محدد" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = expiryStatusColor,
                            fontWeight = if (isExpired || isExpiringSoon) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = expiryStatusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = expiryStatusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                ProductStatusBadgesColumn(
                    product = product,
                    referenceDateIso = referenceDateIso,
                    expiryWarningDays = expiryWarningDays
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onViewDetails,
                    border = BorderStroke(1.dp, BorderSubtle),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("store_product_details_button_${product.id}")
                ) {
                    Text("التفاصيل", style = MaterialTheme.typography.labelMedium, color = TextDarkGreen)
                }

                OutlinedButton(
                    onClick = onEdit,
                    border = BorderStroke(1.dp, FreshGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("store_product_edit_button_${product.id}")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = FreshGreen, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تعديل", style = MaterialTheme.typography.labelMedium, color = FreshGreen)
                }

                OutlinedButton(
                    onClick = onToggleActive,
                    border = BorderStroke(1.dp, if (product.isActive) AccentOrange else FreshGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("store_product_toggle_active_${product.id}")
                ) {
                    Text(
                        text = if (product.isActive) "إخفاء" else "تفعيل",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (product.isActive) AccentOrange else FreshGreen
                    )
                }

                OutlinedButton(
                    onClick = onToggleArchive,
                    border = BorderStroke(1.dp, if (product.isArchived) FreshGreen else AccentRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("store_product_toggle_archive_${product.id}")
                ) {
                    Text(
                        text = if (product.isArchived) "استعادة" else "أرشفة",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (product.isArchived) FreshGreen else AccentRed
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreProductFormContent(
    isEditing: Boolean,
    formState: ProductFormInput,
    errorMessage: String?,
    onFormChange: ((ProductFormInput) -> ProductFormInput) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("store_product_form_screen")
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = if (isEditing) "تعديل بيانات المنتج (#${formState.id})" else "إضافة منتج جديد إلى المتجر",
                style = MaterialTheme.typography.titleLarge,
                color = TextDarkGreen,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "جميع الحقول تُحفظ في قاعدة البيانات المحلية (Room) وتنعكس مباشرة في المتجر.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Live Image Preview + Key Selector
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                ProductVisual(
                    imageUrl = formState.imageUrl,
                    size = 76.dp,
                    contentDescription = formState.name.ifBlank { "معاينة صورة المنتج" }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "معاينة صورة المنتج",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "اختر من الصور المحلية المعتمدة أو أدخل رمز الصورة:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ProductRepository.SUPPORTED_IMAGE_KEYS.forEach { (key, arabicLabel) ->
                    val selected = formState.imageUrl.equals(key, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        onClick = { onFormChange { it.copy(imageUrl = key) } },
                        label = { Text(arabicLabel) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FreshGreen,
                            selectedLabelColor = White
                        ),
                        modifier = Modifier.testTag("product_form_image_chip_$key")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = formState.imageUrl,
                onValueChange = { value -> onFormChange { it.copy(imageUrl = value) } },
                label = { Text("رمز الصورة المحلية أو الرابط (Image Key / URL)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("product_form_image_key_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = formState.name,
                onValueChange = { value -> onFormChange { it.copy(name = value) } },
                label = { Text("اسم المنتج ( بالعربية ) *") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("product_form_name_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = formState.nameEn,
                onValueChange = { value -> onFormChange { it.copy(nameEn = value) } },
                label = { Text("الاسم بالإنجليزية (اختياري - English Name)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("product_form_name_en_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "فئة المنتج *",
                style = MaterialTheme.typography.labelLarge,
                color = TextDarkGreen,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProductRepository.SUPPORTED_CATEGORIES.forEach { category ->
                    val selected = formState.category == category
                    FilterChip(
                        selected = selected,
                        onClick = { onFormChange { it.copy(category = category) } },
                        label = { Text(category) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FreshGreen,
                            selectedLabelColor = White
                        ),
                        modifier = Modifier.testTag("product_form_category_chip_$category")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = formState.priceSarInput,
                    onValueChange = { value -> onFormChange { it.copy(priceSarInput = value) } },
                    label = { Text("السعر (ر.س) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("product_form_price_input")
                )

                OutlinedTextField(
                    value = formState.unit,
                    onValueChange = { value -> onFormChange { it.copy(unit = value) } },
                    label = { Text("الوحدة *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("product_form_unit_input")
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ProductRepository.SUPPORTED_UNITS.forEach { unitOption ->
                    FilterChip(
                        selected = formState.unit == unitOption,
                        onClick = { onFormChange { it.copy(unit = unitOption) } },
                        label = { Text(unitOption, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = formState.stockQuantityInput,
                    onValueChange = { value -> onFormChange { it.copy(stockQuantityInput = value) } },
                    label = { Text("كمية المخزون *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("product_form_stock_input")
                )

                OutlinedTextField(
                    value = formState.lowStockThresholdInput,
                    onValueChange = { value -> onFormChange { it.copy(lowStockThresholdInput = value) } },
                    label = { Text("حد تنبيه النقص *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("product_form_threshold_input")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = formState.expiryDateInput,
                onValueChange = { value -> onFormChange { it.copy(expiryDateInput = value) } },
                label = { Text("تاريخ الصلاحية (YYYY-MM-DD)") },
                placeholder = { Text("2026-12-31") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("product_form_expiry_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = formState.description,
                onValueChange = { value -> onFormChange { it.copy(description = value) } },
                label = { Text("وصف المنتج *") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("product_form_description_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "عرض المنتج في متجر العملاء (نشط)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (formState.isActive) "المنتج مرئي حالياً للعملاء في الصفحة الرئيسية" else "المنتج مخفي مؤقتاً عن العملاء",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = formState.isActive,
                    onCheckedChange = { checked -> onFormChange { it.copy(isActive = checked) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = White, checkedTrackColor = FreshGreen),
                    modifier = Modifier.testTag("product_form_active_switch")
                )
            }

            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentRed,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("product_form_error_banner")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("product_form_save_button")
                ) {
                    Text(
                        text = if (isEditing) "حفظ التعديلات" else "إضافة المنتج",
                        color = White,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onCancel,
                    border = BorderStroke(1.dp, BorderSubtle),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("product_form_cancel_button")
                ) {
                    Text("إلغاء", color = TextDarkGreen, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun StoreProductDetailsContent(
    product: Product?,
    referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO,
    expiryWarningDays: Int = Product.DEFAULT_EXPIRY_WARNING_DAYS,
    productAdjustments: List<StockAdjustment> = emptyList(),
    onEdit: (Product) -> Unit,
    onAdjustStock: (Product, Int) -> Unit,
    onOpenStockDialog: (Product) -> Unit = {},
    onToggleActive: (Product) -> Unit,
    onToggleArchive: (Product) -> Unit,
    onBackToList: () -> Unit
) {
    if (product == null) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CardSurface,
            border = BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("المنتج غير موجود", style = MaterialTheme.typography.titleMedium, color = TextDarkGreen)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onBackToList) {
                    Text("العودة لقائمة المنتجات")
                }
            }
        }
        return
    }

    val isExpired = product.isExpired(referenceDateIso)
    val isExpiringSoon = product.isExpiringSoon(referenceDateIso, expiryWarningDays)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("store_product_details_screen")
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductVisual(
                    imageUrl = product.imageUrl,
                    size = 92.dp,
                    contentDescription = product.name
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                    if (product.nameEn.isNotBlank()) {
                        Text(
                            text = product.nameEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "الفئة: ${product.category} • الوحدة: ${product.unit}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${product.formattedPriceSar()} ر.س (${product.priceHalalas} هللة)",
                        style = MaterialTheme.typography.titleMedium,
                        color = FreshGreen,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "الوصف:",
                style = MaterialTheme.typography.labelLarge,
                color = TextDarkGreen,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = product.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Inventory & Status Metadata Grid
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = FreshBackground,
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("الكمية المتوفرة بالمخزون:", color = TextSecondary)
                        Text(
                            text = "${product.stockQuantity} ${product.unit}",
                            color = if (product.isOutOfStock) AccentRed else if (product.isLowStock) AccentOrange else TextDarkGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("details_stock_quantity_text")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("حد تنبيه انخفاض المخزون:", color = TextSecondary)
                        Text(
                            text = "${product.lowStockThreshold} ${product.unit}",
                            color = TextDarkGreen,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("details_low_stock_threshold_text")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("تاريخ الصلاحية:", color = TextSecondary)
                        Text(
                            text = when {
                                isExpired -> "${product.expiryDate} (منتهي الصلاحية)"
                                isExpiringSoon -> "${product.expiryDate} (يقترب من الانتهاء)"
                                product.expiryDate.isBlank() -> "غير قابل للتلف / غير محدد"
                                else -> "${product.expiryDate} (صلاحية سارية)"
                            },
                            color = when {
                                isExpired -> AccentRed
                                isExpiringSoon -> AccentOrange
                                else -> TextDarkGreen
                            },
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("details_expiry_status_text")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("حالة العرض في متجر العملاء:", color = TextSecondary)
                        Text(
                            text = when {
                                product.isArchived -> "مؤرشف (مخفي)"
                                product.isActive -> "نشط (معروض للعملاء)"
                                else -> "غير نشط (مخفي مؤقتاً)"
                            },
                            color = if (product.isVisibleInStorefront) FreshGreen else AccentOrange,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("details_visibility_status_text")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Quick Stock Stepper & Reason Dialog Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "تعديل سريع للمخزون:",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextDarkGreen,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onAdjustStock(product, -1) },
                        enabled = product.stockQuantity > 0,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("details_stock_decrease_button")
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "إنقاص المخزون", modifier = Modifier.size(16.dp))
                    }
                    Text(
                        text = "${product.stockQuantity}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkGreen
                    )
                    OutlinedButton(
                        onClick = { onAdjustStock(product, +1) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("details_stock_increase_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "زيادة المخزون", modifier = Modifier.size(16.dp))
                    }
                    OutlinedButton(
                        onClick = { onOpenStockDialog(product) },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, FreshGreenDark),
                        modifier = Modifier.testTag("details_open_stock_dialog_button")
                    ) {
                        Text("جرد بسبب", color = FreshGreenDark, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Per-product Stock Adjustment History Section
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = FreshBackground,
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("details_stock_adjustment_history_section")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "سجل تعديلات مخزون هذا المنتج (${productAdjustments.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (productAdjustments.isEmpty()) {
                        Text(
                            text = "لا توجد تعديلات مخزون مسجلة لهذا المنتج حتى الآن.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.testTag("details_history_empty_text")
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            productAdjustments.take(5).forEach { entry ->
                                val deltaFormatted = entry.formattedDeltaQuantity()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("details_history_item_${entry.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${entry.formattedPreviousQuantity()} ← ${entry.formattedNewQuantity()} ($deltaFormatted)",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (entry.quantityDeltaMillis >= 0) FreshGreen else AccentOrange,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = entry.formattedTimestamp(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                    Text(
                                        text = "السبب: ${entry.reason} • ${entry.actorName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = { onEdit(product) },
                colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("details_edit_product_button")
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("تعديل بيانات المنتج", color = White, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onToggleActive(product) },
                    border = BorderStroke(1.dp, if (product.isActive) AccentOrange else FreshGreen),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .testTag("details_toggle_active_button")
                ) {
                    Icon(
                        imageVector = if (product.isActive) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = if (product.isActive) AccentOrange else FreshGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (product.isActive) "إيقاف التفعيل" else "تفعيل المنتج",
                        color = if (product.isActive) AccentOrange else FreshGreen,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = { onToggleArchive(product) },
                    border = BorderStroke(1.dp, if (product.isArchived) FreshGreen else AccentRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .testTag("details_toggle_archive_button")
                ) {
                    Icon(
                        imageVector = if (product.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                        contentDescription = null,
                        tint = if (product.isArchived) FreshGreen else AccentRed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (product.isArchived) "استعادة من الأرشيف" else "أرشفة المنتج",
                        color = if (product.isArchived) FreshGreen else AccentRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onBackToList,
                border = BorderStroke(1.dp, BorderSubtle),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("details_back_button")
            ) {
                Text("العودة إلى قائمة المنتجات", color = TextDarkGreen)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreOrdersHonestNoticeContent(
    unavailableNote: String,
    persistedOrders: List<OrderWithItems> = emptyList(),
    totalOrdersCount: Int = persistedOrders.size,
    selectedStatusFilter: OrderStatus? = null,
    selectedOrderWithItems: OrderWithItems? = null,
    onSelectStatusFilter: (OrderStatus?) -> Unit = {},
    onOpenOrderDetails: (Int) -> Unit = {},
    onCloseOrderDetails: () -> Unit = {},
    onTransitionStatus: (Int, OrderStatus) -> Unit = { _, _ -> },
    onCancelOrder: (Int) -> Unit = {},
    onBackToDashboard: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("store_orders_notice_screen")
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.ReceiptLong,
                contentDescription = null,
                tint = if (totalOrdersCount == 0) AccentOrange else FreshGreen,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (totalOrdersCount == 0) {
                    "سجل الطلبات والإيرادات غير متوفر حالياً"
                } else {
                    "إدارة الطلبات المحلية المسجلة ($totalOrdersCount)"
                },
                style = MaterialTheme.typography.titleLarge,
                color = TextDarkGreen,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = unavailableNote,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "التزاماً بمعايير الدقة والشفافية، لا يعرض النظام أي أرقام مبيعات أو إيرادات أو طلبات افتراضية غير حقيقية.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Status filter chips for owner order management
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("owner_order_status_filter_row")
            ) {
                Text(
                    text = "تصفية الطلبات حسب الحالة:",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextDarkGreen,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedStatusFilter == null,
                        onClick = { onSelectStatusFilter(null) },
                        label = { Text("الكل ($totalOrdersCount)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FreshGreen,
                            selectedLabelColor = White
                        ),
                        modifier = Modifier.testTag("owner_order_filter_ALL")
                    )
                    OrderStatus.entries.forEach { status ->
                        FilterChip(
                            selected = selectedStatusFilter == status,
                            onClick = { onSelectStatusFilter(status) },
                            label = { Text(status.arabicLabel) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = FreshGreen,
                                selectedLabelColor = White
                            ),
                            modifier = Modifier.testTag("owner_order_filter_${status.name}")
                        )
                    }
                }
            }

            // Focused Owner Order Details Panel when an order is opened
            if (selectedOrderWithItems != null) {
                Spacer(modifier = Modifier.height(14.dp))
                StoreOwnerOrderDetailsPanel(
                    orderWithItems = selectedOrderWithItems,
                    onCloseDetails = onCloseOrderDetails,
                    onTransitionStatus = { nextStatus ->
                        onTransitionStatus(selectedOrderWithItems.order.id, nextStatus)
                    },
                    onCancelOrder = {
                        onCancelOrder(selectedOrderWithItems.order.id)
                    }
                )
            }

            if (persistedOrders.isEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = FreshBackground,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("owner_orders_empty_filter_state")
                ) {
                    Text(
                        text = if (selectedStatusFilter == null) {
                            "لا توجد طلبات محلية مسجلة حالياً."
                        } else {
                            "لا توجد طلبات مطابقة لحالة «${selectedStatusFilter.arabicLabel}»."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("store_owner_orders_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    persistedOrders.forEach { orderWithItems ->
                        StoreOwnerOrderManagementCard(
                            orderWithItems = orderWithItems,
                            onOpenDetails = { onOpenOrderDetails(orderWithItems.order.id) },
                            onTransitionStatus = { nextStatus ->
                                onTransitionStatus(orderWithItems.order.id, nextStatus)
                            },
                            onCancelOrder = {
                                onCancelOrder(orderWithItems.order.id)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onBackToDashboard,
                colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("orders_back_dashboard_button")
            ) {
                Text("العودة إلى لوحة التحكم", color = White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StoreOwnerOrderManagementCard(
    orderWithItems: OrderWithItems,
    onOpenDetails: () -> Unit,
    onTransitionStatus: (OrderStatus) -> Unit,
    onCancelOrder: () -> Unit
) {
    val order = orderWithItems.order
    val status = order.orderStatus
    val nonCancelTransitions = status.allowedNextStatuses().filter { it != OrderStatus.CANCELLED }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FreshBackground,
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails)
            .testTag("owner_order_card_${order.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            OrderHistoryCard(
                orderWithItems = orderWithItems,
                showCustomerName = true
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "مرجع العميل: ${order.customerNameSnapshot} (${order.customerId}) • الإنشاء: ${order.formattedTimestamp()}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.testTag("owner_order_customer_ref_${order.id}")
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onOpenDetails,
                border = BorderStroke(1.dp, FreshGreenDark),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("owner_order_open_details_${order.id}")
            ) {
                Text(
                    text = "فتح تفاصيل الطلب وإدارة الحالة",
                    color = FreshGreenDark,
                    fontWeight = FontWeight.Bold
                )
            }

            if (nonCancelTransitions.isNotEmpty() || status.canBeCancelled) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    nonCancelTransitions.forEach { nextStatus ->
                        Button(
                            onClick = { onTransitionStatus(nextStatus) },
                            colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("owner_order_transition_${order.id}_${nextStatus.name}")
                        ) {
                            Text(
                                text = nextStatus.actionButtonArabic,
                                color = White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (status.canBeCancelled) {
                        OutlinedButton(
                            onClick = onCancelOrder,
                            border = BorderStroke(1.dp, AccentRed),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("owner_order_cancel_${order.id}")
                        ) {
                            Text(
                                text = OrderStatus.CANCELLED.actionButtonArabic,
                                color = AccentRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "حالة نهائية: ${status.arabicLabel} (لا تتوفر انتقالات إضافية)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("owner_order_terminal_notice_${order.id}")
                )
            }
        }
    }
}

@Composable
private fun StoreOwnerOrderDetailsPanel(
    orderWithItems: OrderWithItems,
    onCloseDetails: () -> Unit,
    onTransitionStatus: (OrderStatus) -> Unit,
    onCancelOrder: () -> Unit
) {
    val order = orderWithItems.order
    val items = orderWithItems.items
    val status = order.orderStatus
    val nonCancelTransitions = status.allowedNextStatuses().filter { it != OrderStatus.CANCELLED }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FreshGreenLight,
        border = BorderStroke(1.5.dp, FreshGreen),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("owner_order_details_panel_${order.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "تفاصيل الطلب المحلي: ${order.displayOrderNumber}",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "وقت الإنشاء: ${order.formattedTimestamp()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.testTag("owner_order_detail_timestamp_${order.id}")
                    )
                }
                OutlinedButton(
                    onClick = onCloseDetails,
                    border = BorderStroke(1.dp, FreshGreenDark),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("owner_order_close_details_button")
                ) {
                    Text("إغلاق", color = FreshGreenDark, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "الحالة الحالية: ${status.arabicLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = FreshGreenDark,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("owner_order_detail_status_${order.id}")
            )
            Text(
                text = "العميل: ${order.customerNameSnapshot} (معرف العميل: ${order.customerId})",
                style = MaterialTheme.typography.bodySmall,
                color = TextDarkGreen,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("owner_order_detail_customer_${order.id}")
            )
            Text(
                text = "عنوان التوصيل المحفوظ: ${order.deliveryAddressSnapshot}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = "وضع الدفع: ${order.paymentModeNote}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "نسخة الأصناف والكميات والأسعار وقت الشراء (${items.size}):",
                style = MaterialTheme.typography.labelLarge,
                color = TextDarkGreen,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("owner_order_detail_item_${order.id}_${item.productId}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.productName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextDarkGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "الكمية: ${item.formattedQuantity()} ${item.unit} × سعر الوحدة: ${item.formattedUnitPriceSar()} ر.س",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "${item.formattedLineTotalSar()} ر.س",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FreshGreenDark,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "الفرعي: ${order.formattedSubtotalSar()} ر.س • التوصيل: ${order.formattedDeliveryFeeSar()} ر.س",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = "الإجمالي: ${order.formattedTotalSar()} ر.س",
                    style = MaterialTheme.typography.titleSmall,
                    color = FreshGreenDark,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("owner_order_detail_total_${order.id}")
                )
            }

            if (nonCancelTransitions.isNotEmpty() || status.canBeCancelled) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "تحديث حالة الطلب (الانتقالات المسموحة فقط):",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextDarkGreen,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    nonCancelTransitions.forEach { nextStatus ->
                        Button(
                            onClick = { onTransitionStatus(nextStatus) },
                            colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("owner_order_detail_transition_${order.id}_${nextStatus.name}")
                        ) {
                            Text(
                                text = nextStatus.actionButtonArabic,
                                color = White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (status.canBeCancelled) {
                        OutlinedButton(
                            onClick = onCancelOrder,
                            border = BorderStroke(1.dp, AccentRed),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("owner_order_detail_cancel_${order.id}")
                        ) {
                            Text(
                                text = OrderStatus.CANCELLED.actionButtonArabic,
                                color = AccentRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorePortalMinimalFallbackActions(
    onSwitchToCustomerStorefront: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRequestLogout: () -> Unit
) {
    Button(
        onClick = onSwitchToCustomerStorefront,
        colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("owner_preview_storefront_button")
    ) {
        Icon(Icons.Default.ShoppingBag, contentDescription = null, tint = White)
        Spacer(modifier = Modifier.width(8.dp))
        Text("معاينة واجهة التسوق (متجر العملاء)", color = White, fontWeight = FontWeight.Bold)
    }

    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onNavigateToAccount,
            border = BorderStroke(1.dp, FreshGreen),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("owner_account_button")
        ) {
            Text("الملف الشخصي", color = FreshGreen, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onNavigateToSettings,
            border = BorderStroke(1.dp, FreshGreen),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("owner_settings_button")
        ) {
            Text("الإعدادات", color = FreshGreen, fontWeight = FontWeight.Bold)
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    OutlinedButton(
        onClick = onRequestLogout,
        border = BorderStroke(1.dp, AccentRed),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("owner_logout_button")
    ) {
        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = AccentRed)
        Spacer(modifier = Modifier.width(8.dp))
        Text("تسجيل الخروج", color = AccentRed, fontWeight = FontWeight.Bold)
    }
}
