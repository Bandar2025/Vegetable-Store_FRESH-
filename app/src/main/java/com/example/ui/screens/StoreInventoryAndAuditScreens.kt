package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.model.Product
import com.example.data.local.model.ProductExpiryStatus
import com.example.data.local.model.StockAdjustment
import com.example.data.repository.ProductRepository
import com.example.ui.components.ProductVisual
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.AccentRed
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardSurface
import com.example.ui.theme.FreshBackground
import com.example.ui.theme.FreshGreen
import com.example.ui.theme.FreshGreenDark
import com.example.ui.theme.FreshGreenLight
import com.example.ui.theme.TextDarkGreen
import com.example.ui.theme.TextSecondary
import androidx.compose.runtime.collectAsState
import com.example.ui.theme.White
import com.example.ui.viewmodel.InventoryViewModel
import com.example.ui.viewmodel.StoreDashboardMetrics
import com.example.ui.viewmodel.StoreProductStatusFilter

/**
 * Stateful Inventory Dashboard wrapper that observes [InventoryViewModel] Flows directly
 * (`filteredProducts`, `searchQuery`, `metrics`, `selectedCategory`, `statusFilter`, `expiryWarningDays`)
 * for real-time Arabic and English product name filtering.
 */
@Composable
fun InventoryDashboardScreen(
    inventoryViewModel: InventoryViewModel,
    onAdjustStockQuick: (Product, Int) -> Unit = { _, _ -> },
    onOpenStockAdjustDialog: (Product) -> Unit = {},
    onViewDetails: (Product) -> Unit = {},
    onEditProduct: (Product) -> Unit = {},
    onToggleActive: (Product) -> Unit = {},
    onRequestArchiveConfirmation: (Product) -> Unit = {},
    onBackToDashboard: () -> Unit = {}
) {
    val filteredProducts by inventoryViewModel.filteredProducts.collectAsState()
    val metrics by inventoryViewModel.metrics.collectAsState()
    val referenceDateIso by inventoryViewModel.referenceDateIsoFlow.collectAsState()
    val expiryWarningDays by inventoryViewModel.expiryWarningDays.collectAsState()
    val searchQuery by inventoryViewModel.searchQuery.collectAsState()
    val selectedCategory by inventoryViewModel.selectedCategory.collectAsState()
    val statusFilter by inventoryViewModel.statusFilter.collectAsState()
    val stockAdjustments by inventoryViewModel.stockAdjustments.collectAsState()

    StoreInventoryDashboardContent(
        filteredProducts = filteredProducts,
        metrics = metrics,
        referenceDateIso = referenceDateIso,
        expiryWarningDays = expiryWarningDays,
        searchQuery = searchQuery,
        selectedCategory = selectedCategory,
        statusFilter = statusFilter,
        stockAdjustments = stockAdjustments,
        onSearchQueryChange = inventoryViewModel::onSearchQueryChange,
        onCategorySelect = inventoryViewModel::onCategorySelect,
        onStatusFilterSelect = inventoryViewModel::onStatusFilterSelect,
        onExpiryWarningDaysChange = inventoryViewModel::setExpiryWarningDays,
        onAdjustStockQuick = onAdjustStockQuick,
        onOpenStockAdjustDialog = onOpenStockAdjustDialog,
        onViewDetails = onViewDetails,
        onEditProduct = onEditProduct,
        onToggleActive = onToggleActive,
        onRequestArchiveConfirmation = onRequestArchiveConfirmation,
        onBackToDashboard = onBackToDashboard
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoreInventoryDashboardContent(
    filteredProducts: List<Product>,
    metrics: StoreDashboardMetrics,
    referenceDateIso: String,
    expiryWarningDays: Int,
    searchQuery: String,
    selectedCategory: String,
    statusFilter: StoreProductStatusFilter,
    stockAdjustments: List<StockAdjustment>,
    onSearchQueryChange: (String) -> Unit,
    onCategorySelect: (String) -> Unit,
    onStatusFilterSelect: (StoreProductStatusFilter) -> Unit,
    onExpiryWarningDaysChange: (Int) -> Unit,
    onAdjustStockQuick: (Product, Int) -> Unit,
    onOpenStockAdjustDialog: (Product) -> Unit,
    onViewDetails: (Product) -> Unit,
    onEditProduct: (Product) -> Unit,
    onToggleActive: (Product) -> Unit,
    onRequestArchiveConfirmation: (Product) -> Unit,
    onBackToDashboard: () -> Unit
) {
    val warningWindowOptions = listOf(3, 7, 14, 30)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("store_inventory_screen")
    ) {
        Text(
            text = "إدارة المخزون ومراقبة الصلاحية",
            style = MaterialTheme.typography.titleLarge,
            color = TextDarkGreen,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "بيانات حقيقية مباشرة من قاعدة البيانات المحلية (Room) • التاريخ المرجعي: $referenceDateIso",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Real-time Search Bar at the top of the Inventory Dashboard (Arabic or English product name)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text("ابحث بالاسم العربي أو الإنجليزي (مثال: تفاح / Apple)...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = FreshGreen) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.testTag("inventory_search_clear_button")
                    ) {
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
                .testTag("inventory_search_input")
        )

        if (searchQuery.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "نتائج البحث الفوري: ${filteredProducts.size} صنف مطابق لـ \"${searchQuery.trim()}\"",
                style = MaterialTheme.typography.labelMedium,
                color = FreshGreenDark,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("inventory_search_results_count")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Row 1 of Inventory Summary Cards: Active Products & Total Available Stock Units
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InventoryMetricSmallCard(
                title = "المنتجات النشطة",
                value = "${metrics.activeProductsCount}",
                subtitle = "معروضة في المتجر",
                accentColor = FreshGreen,
                cardTag = "inventory_metric_active_products",
                valueTag = "inventory_value_active_products",
                onClick = { onStatusFilterSelect(StoreProductStatusFilter.ACTIVE_ONLY) },
                modifier = Modifier.weight(1f)
            )
            InventoryMetricSmallCard(
                title = "إجمالي الوحدات المتوفرة",
                value = "${metrics.totalAvailableStockUnits}",
                subtitle = "وحدة مخزون نشطة",
                accentColor = FreshGreenDark,
                cardTag = "inventory_metric_available_stock",
                valueTag = "inventory_value_available_stock",
                onClick = { onStatusFilterSelect(StoreProductStatusFilter.ALL) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Row 2: Low-stock & Out-of-stock
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InventoryMetricSmallCard(
                title = "منخفض المخزون",
                value = "${metrics.lowStockCount}",
                subtitle = "وصل للحد الأدنى",
                accentColor = AccentOrange,
                cardTag = "inventory_metric_low_stock",
                valueTag = "inventory_value_low_stock",
                onClick = { onStatusFilterSelect(StoreProductStatusFilter.LOW_STOCK) },
                modifier = Modifier.weight(1f)
            )
            InventoryMetricSmallCard(
                title = "نفد المخزون",
                value = "${metrics.outOfStockCount}",
                subtitle = "الكمية تساوي ٠",
                accentColor = AccentRed,
                cardTag = "inventory_metric_out_of_stock",
                valueTag = "inventory_value_out_of_stock",
                onClick = { onStatusFilterSelect(StoreProductStatusFilter.OUT_OF_STOCK) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Row 3: Expiring Soon vs Expired (Clearly Distinguished)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InventoryMetricSmallCard(
                title = "يقترب من الانتهاء",
                value = "${metrics.expiringSoonCount}",
                subtitle = "خلال $expiryWarningDays أيام",
                accentColor = AccentOrange,
                cardTag = "inventory_metric_expiring_soon",
                valueTag = "inventory_value_expiring_soon",
                onClick = { onStatusFilterSelect(StoreProductStatusFilter.EXPIRING_SOON) },
                modifier = Modifier.weight(1f)
            )
            InventoryMetricSmallCard(
                title = "منتهي الصلاحية",
                value = "${metrics.expiredCount}",
                subtitle = "تجاوز تاريخ الانتهاء",
                accentColor = AccentRed,
                cardTag = "inventory_metric_expired",
                valueTag = "inventory_value_expired",
                onClick = { onStatusFilterSelect(StoreProductStatusFilter.EXPIRED) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Row 4: Hidden & Archived Summary Bar
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = CardSurface,
            border = BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("inventory_metric_hidden_archived")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "غير نشط (مخفي): ${metrics.inactiveProductsCount}",
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentOrange,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onStatusFilterSelect(StoreProductStatusFilter.INACTIVE_ONLY) }
                        .testTag("inventory_hidden_count_text")
                )
                Text(
                    text = "•",
                    color = TextSecondary
                )
                Text(
                    text = "مؤرشف: ${metrics.archivedProductsCount}",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onStatusFilterSelect(StoreProductStatusFilter.ARCHIVED_ONLY) }
                        .testTag("inventory_archived_count_text")
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Configurable Expiry Warning Window Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = FreshGreenLight.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("inventory_expiry_window_selector")
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "نافذة التنبيه المبكر لقرب انتهاء الصلاحية: $expiryWarningDays أيام",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextDarkGreen,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "لا يتم حذف أو أرشفة أو إخفاء المنتجات منتهية الصلاحية تلقائياً؛ يبقى القرار بيد مدير المتجر.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    warningWindowOptions.forEach { days ->
                        FilterChip(
                            selected = expiryWarningDays == days,
                            onClick = { onExpiryWarningDaysChange(days) },
                            label = { Text("$days أيام") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = FreshGreen,
                                selectedLabelColor = White
                            ),
                            modifier = Modifier.testTag("expiry_window_chip_$days")
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Category Filter Chips
        val categories = listOf("الكل") + ProductRepository.SUPPORTED_CATEGORIES
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { onCategorySelect(category) },
                    label = { Text(category, fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FreshGreen,
                        selectedLabelColor = White
                    ),
                    modifier = Modifier.testTag("inventory_category_chip_$category")
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Inventory & Expiry Status Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StoreProductStatusFilter.values().forEach { filter ->
                FilterChip(
                    selected = statusFilter == filter,
                    onClick = { onStatusFilterSelect(filter) },
                    label = { Text(filter.arabicLabel) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FreshGreenDark,
                        selectedLabelColor = White
                    ),
                    modifier = Modifier.testTag("inventory_status_chip_${filter.name}")
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredProducts.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardSurface,
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("inventory_empty_filtered_state")
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "لا توجد أصناف مطابقة لفلتر المخزون أو الصلاحية المحدد",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "جرب اختيار فئة أخرى أو عرض كل الأصناف.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filteredProducts.forEach { product ->
                    InventoryProductOperationCard(
                        product = product,
                        referenceDateIso = referenceDateIso,
                        expiryWarningDays = expiryWarningDays,
                        onAdjustStockQuick = { delta -> onAdjustStockQuick(product, delta) },
                        onOpenStockAdjustDialog = { onOpenStockAdjustDialog(product) },
                        onViewDetails = { onViewDetails(product) },
                        onEditProduct = { onEditProduct(product) },
                        onToggleActive = { onToggleActive(product) },
                        onRequestArchiveConfirmation = { onRequestArchiveConfirmation(product) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Persisted Stock Adjustment Audit Trail Section
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CardSurface,
            border = BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("inventory_adjustment_history_section")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = FreshGreenDark,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "سجل عمليات تعديل المخزون (${stockAdjustments.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "سجل تدقيق فعلي محفوظ في قاعدة بيانات Room لكل عملية تعديل مخزون موثقة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (stockAdjustments.isEmpty()) {
                    Text(
                        text = "لا توجد عمليات تعديل مخزون مسجلة حتى الآن.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.testTag("inventory_history_empty_text")
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        stockAdjustments.take(8).forEach { entry ->
                            val deltaPrefix = if (entry.quantityDelta >= 0) "+${entry.quantityDelta}" else "${entry.quantityDelta}"
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = FreshBackground,
                                border = BorderStroke(1.dp, BorderSubtle),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("stock_adjustment_history_item_${entry.id}")
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = entry.productName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextDarkGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${entry.previousQuantity} ← ${entry.newQuantity} ($deltaPrefix)",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (entry.quantityDelta >= 0) FreshGreen else AccentOrange,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "السبب: ${entry.reason} • بواسطة: ${entry.actorName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = "الوقت: ${entry.formattedTimestamp()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        modifier = Modifier.testTag("stock_adjustment_history_timestamp_${entry.id}")
                                    )
                                }
                            }
                        }
                    }
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
                .testTag("inventory_back_dashboard_button")
        ) {
            Text("العودة إلى لوحة التحكم", color = FreshGreen, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InventoryMetricSmallCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    cardTag: String,
    valueTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = modifier
            .clickable(onClick = onClick)
            .testTag(cardTag)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = accentColor,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.testTag(valueTag)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun InventoryProductOperationCard(
    product: Product,
    referenceDateIso: String,
    expiryWarningDays: Int,
    onAdjustStockQuick: (Int) -> Unit,
    onOpenStockAdjustDialog: () -> Unit,
    onViewDetails: () -> Unit,
    onEditProduct: () -> Unit,
    onToggleActive: () -> Unit,
    onRequestArchiveConfirmation: () -> Unit
) {
    val expiryStatus = product.evaluateExpiryStatus(referenceDateIso, expiryWarningDays)
    val daysRemaining = product.daysUntilExpiry(referenceDateIso)

    val (expiryBadgeText, expiryBadgeColor) = when (expiryStatus) {
        ProductExpiryStatus.EXPIRED -> {
            val overdue = if (daysRemaining != null) (-daysRemaining) else 0L
            "منتهي الصلاحية (${product.expiryDate} • منذ $overdue يوم)" to AccentRed
        }
        ProductExpiryStatus.EXPIRING_SOON -> {
            val remText = when (daysRemaining) {
                0L -> "ينتهي اليوم (${product.expiryDate})"
                1L -> "يقترب من الانتهاء غداً (${product.expiryDate})"
                else -> "يقترب من الانتهاء خلال ${daysRemaining ?: 0} أيام (${product.expiryDate})"
            }
            remText to AccentOrange
        }
        ProductExpiryStatus.VALID -> "صلاحية سارية (${product.expiryDate})" to FreshGreen
        ProductExpiryStatus.NO_EXPIRY_DATE -> "غير قابل للتلف (بدون تاريخ صلاحية)" to TextSecondary
        ProductExpiryStatus.INVALID_EXPIRY_DATE -> "تاريخ صلاحية غير صالح (${product.expiryDate})" to AccentRed
    }

    val (stockBadgeText, stockBadgeColor) = when {
        product.isOutOfStock -> "نفد المخزون (٠ ${product.unit} • الحد الأدنى: ${product.lowStockThreshold})" to AccentRed
        product.isLowStock -> "منخفض المخزون (${product.stockQuantity} ${product.unit} • الحد الأدنى: ${product.lowStockThreshold})" to AccentOrange
        else -> "متوفر: ${product.stockQuantity} ${product.unit} (الحد الأدنى: ${product.lowStockThreshold})" to FreshGreenDark
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardSurface,
        border = BorderStroke(
            width = 1.dp,
            color = when {
                expiryStatus == ProductExpiryStatus.EXPIRED || product.isOutOfStock -> AccentRed.copy(alpha = 0.55f)
                expiryStatus == ProductExpiryStatus.EXPIRING_SOON || product.isLowStock -> AccentOrange.copy(alpha = 0.55f)
                else -> BorderSubtle
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("inventory_item_card_${product.id}")
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
                    size = 60.dp,
                    contentDescription = product.name
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                    if (product.nameEn.isNotBlank()) {
                        Text(
                            text = product.nameEn,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "${product.category} • ${product.formattedPriceSar()} ر.س / ${product.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // Active / Hidden / Archived Pill
                val (visText, visColor) = when {
                    product.isArchived -> "مؤرشف" to TextSecondary
                    product.isActive -> "نشط" to FreshGreen
                    else -> "مخفي" to AccentOrange
                }
                Surface(
                    color = visColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = visText,
                        style = MaterialTheme.typography.labelSmall,
                        color = visColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stock & Expiry Badges (clearly distinguished)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (product.stockQuantity <= product.lowStockThreshold) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = stockBadgeColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, stockBadgeColor.copy(alpha = 0.5f)),
                        modifier = Modifier.testTag("inventory_stock_badge_${product.id}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "تحذير: المخزون دون الحد الأدنى",
                                tint = stockBadgeColor,
                                modifier = Modifier
                                    .size(15.dp)
                                    .testTag("inventory_low_stock_warning_icon_${product.id}")
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stockBadgeText,
                                style = MaterialTheme.typography.labelMedium,
                                color = stockBadgeColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Text(
                        text = stockBadgeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = stockBadgeColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("inventory_stock_badge_${product.id}")
                    )
                }
                Surface(
                    color = expiryBadgeColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("inventory_expiry_badge_${product.id}")
                ) {
                    Text(
                        text = expiryBadgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = expiryBadgeColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(modifier = Modifier.height(8.dp))

            // Row 1: Stock Adjustment Controls (-1, +1, +5, Custom Adjust with Reason)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { onAdjustStockQuick(-1) },
                        enabled = product.stockQuantity > 0,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("inventory_decrease_1_${product.id}")
                    ) {
                        Text("-١", fontWeight = FontWeight.Bold, color = if (product.stockQuantity > 0) TextDarkGreen else TextSecondary)
                    }
                    OutlinedButton(
                        onClick = { onAdjustStockQuick(+1) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("inventory_increase_1_${product.id}")
                    ) {
                        Text("+١", fontWeight = FontWeight.Bold, color = FreshGreen)
                    }
                    OutlinedButton(
                        onClick = { onAdjustStockQuick(+5) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("inventory_restock_5_${product.id}")
                    ) {
                        Text("+٥", fontWeight = FontWeight.Bold, color = FreshGreen)
                    }
                }

                Button(
                    onClick = onOpenStockAdjustDialog,
                    colors = ButtonDefaults.buttonColors(containerColor = FreshGreenDark),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("inventory_open_adjust_dialog_${product.id}")
                ) {
                    Text("جرد / تعديل", color = White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Row 2: Product Management Actions (Details, Edit, Activate/Hide, Archive with Confirmation)
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
                        .testTag("inventory_details_button_${product.id}")
                ) {
                    Text("التفاصيل", style = MaterialTheme.typography.labelSmall, color = TextDarkGreen)
                }
                OutlinedButton(
                    onClick = onEditProduct,
                    border = BorderStroke(1.dp, FreshGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("inventory_edit_button_${product.id}")
                ) {
                    Text("تعديل", style = MaterialTheme.typography.labelSmall, color = FreshGreen)
                }
                OutlinedButton(
                    onClick = onToggleActive,
                    border = BorderStroke(1.dp, if (product.isActive) AccentOrange else FreshGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("inventory_toggle_active_${product.id}")
                ) {
                    Text(
                        text = if (product.isActive) "إخفاء" else "تفعيل",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (product.isActive) AccentOrange else FreshGreen
                    )
                }
                OutlinedButton(
                    onClick = onRequestArchiveConfirmation,
                    border = BorderStroke(1.dp, if (product.isArchived) FreshGreen else AccentRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("inventory_archive_button_${product.id}")
                ) {
                    Text(
                        text = if (product.isArchived) "استعادة" else "أرشفة",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (product.isArchived) FreshGreen else AccentRed
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StockAdjustmentDialog(
    product: Product,
    errorMessage: String? = null,
    onConfirm: (newQuantityInput: String, reasonInput: String) -> Unit,
    onDismiss: () -> Unit
) {
    var quantityInput by remember(product.id, product.stockQuantity) {
        mutableStateOf(product.stockQuantity.toString())
    }
    var reasonInput by remember(product.id) {
        mutableStateOf(ProductRepository.SUPPORTED_ADJUSTMENT_REASONS.first())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        modifier = Modifier.testTag("stock_adjustment_dialog"),
        title = {
            Text(
                text = "تعديل مخزون «${product.name}»",
                style = MaterialTheme.typography.titleMedium,
                color = TextDarkGreen,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "الكمية الحالية المسجلة: ${product.stockQuantity} ${product.unit} • الحد الأدنى: ${product.lowStockThreshold} ${product.unit}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentRed,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("stock_adjustment_dialog_error")
                    )
                }

                OutlinedTextField(
                    value = quantityInput,
                    onValueChange = { quantityInput = it },
                    label = { Text("الكمية الجديدة (يدعم الأرقام العربية واللاتينية) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stock_adjustment_quantity_input")
                )

                Text(
                    text = "سبب تعديل المخزون *",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextDarkGreen,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ProductRepository.SUPPORTED_ADJUSTMENT_REASONS.forEach { preset ->
                        FilterChip(
                            selected = reasonInput == preset,
                            onClick = { reasonInput = preset },
                            label = { Text(preset, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = FreshGreen,
                                selectedLabelColor = White
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = reasonInput,
                    onValueChange = { reasonInput = it },
                    label = { Text("سبب التعديل (لأغراض سجل التدقيق) *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stock_adjustment_reason_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(quantityInput, reasonInput) },
                colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                modifier = Modifier.testTag("stock_adjustment_confirm_button")
            ) {
                Text("حفظ التعديل وتوثيق السجل", color = White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("stock_adjustment_cancel_button")
            ) {
                Text("إلغاء", color = TextDarkGreen)
            }
        }
    )
}

@Composable
fun ArchiveProductConfirmationDialog(
    product: Product,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isArchiving = !product.isArchived
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        modifier = Modifier.testTag("archive_confirmation_dialog"),
        title = {
            Text(
                text = if (isArchiving) "تأكيد أرشفة المنتج" else "تأكيد استعادة المنتج من الأرشيف",
                style = MaterialTheme.typography.titleMedium,
                color = TextDarkGreen,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = if (isArchiving) {
                    "هل أنت متأكد من أرشفة المنتج «${product.name}»؟ سيتم إخفاؤه فوراً من واجهة متجر العملاء ونقله إلى قسم الأرشيف مع الاحتفاظ بسجلاته."
                } else {
                    "هل ترغب في استعادة المنتج «${product.name}» من الأرشيف وتفعيله مجدداً في المتجر؟"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isArchiving) AccentRed else FreshGreen
                ),
                modifier = Modifier.testTag("archive_confirm_button")
            ) {
                Text(
                    text = if (isArchiving) "تأكيد الأرشفة" else "تأكيد الاستعادة",
                    color = White,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("archive_cancel_button")
            ) {
                Text("تراجع", color = TextDarkGreen)
            }
        }
    )
}
