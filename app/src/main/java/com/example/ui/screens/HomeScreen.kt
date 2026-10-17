package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Sort
import androidx.compose.ui.text.input.ImeAction
import com.example.ui.viewmodel.ProductSortOrder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.model.Product
import com.example.ui.components.ProductVisual
import com.example.ui.theme.*
import com.example.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onProductClick: (Product) -> Unit,
    onCartClick: () -> Unit = {},
    onAddToCart: (Product) -> Unit = {}
) {
    val products by viewModel.products.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val priceRange by viewModel.priceRange.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()

    var isPriceFilterVisible by remember { mutableStateOf(false) }
    var selectedNavIndex by remember { mutableIntStateOf(0) }
    var recentlyAddedProductName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Real-time filtering: when typing in search bar, selecting category (Fruits, Vegetables, Organic, etc.), and adjusting price range slider
    val filteredProducts = remember(products, searchQuery, selectedCategory, priceRange) {
        val q = searchQuery.trim()
        products.filter { product ->
            val productPriceSar = product.priceHalalas / 100f
            val matchesPrice = productPriceSar >= priceRange.start && productPriceSar <= priceRange.endInclusive

            val matchesCategory = when (selectedCategory.lowercase(Locale.ROOT)) {
                "الكل", "all" -> true
                "فواكه", "fruits", "fruit" -> product.category.contains("فواكه", ignoreCase = true)
                "خضروات", "vegetables", "veg" -> product.category.contains("خضروات", ignoreCase = true)
                "عضوي", "organic" -> product.name.contains("عضوي", ignoreCase = true) ||
                                   product.description.contains("عضوي", ignoreCase = true) ||
                                   product.category.contains("عضوي", ignoreCase = true)
                "ورقيات", "greens", "leafy" -> product.category.contains("ورقيات", ignoreCase = true)
                "تمور", "dates" -> product.category.contains("تمور", ignoreCase = true)
                else -> product.category.contains(selectedCategory, ignoreCase = true) ||
                        product.name.contains(selectedCategory, ignoreCase = true)
            }

            val matchesSearch = if (q.isNotBlank()) {
                product.name.contains(q, ignoreCase = true) ||
                product.category.contains(q, ignoreCase = true) ||
                product.description.contains(q, ignoreCase = true)
            } else {
                true
            }

            matchesPrice && matchesCategory && matchesSearch
        }
    }

    // Sort filtered products in real time based on user's selected sort order (Popularity, Price Low->High, Price High->Low)
    val sortedProducts = remember(filteredProducts, sortOrder) {
        when (sortOrder) {
            ProductSortOrder.POPULARITY -> filteredProducts
            ProductSortOrder.PRICE_LOW_TO_HIGH -> filteredProducts.sortedBy { it.priceHalalas }
            ProductSortOrder.PRICE_HIGH_TO_LOW -> filteredProducts.sortedByDescending { it.priceHalalas }
        }
    }

    // Force RTL layout for Saudi market and Arabic language
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            containerColor = FreshBackground,
            contentWindowInsets = WindowInsets.statusBars,
            bottomBar = {
                FreshBottomNavigationBar(
                    selectedIndex = selectedNavIndex,
                    onItemSelected = { index ->
                        selectedNavIndex = index
                        if (index == 3) {
                            onCartClick()
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // 1. Top Header with Location, Brand & Actions
                    item {
                        HomeTopBar(
                            onCartClick = onCartClick,
                            onNotificationClick = {}
                        )
                    }

                    // 2. Search Section: Real-time Search Bar & Range Slider Price Filter
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            HomeSearchBar(
                                query = searchQuery,
                                onQueryChange = { viewModel.onSearchQueryChange(it) },
                                isFilterActive = isPriceFilterVisible || priceRange.start > 0f || priceRange.endInclusive < 30f,
                                onToggleFilter = { isPriceFilterVisible = !isPriceFilterVisible }
                            )

                            AnimatedVisibility(
                                visible = isPriceFilterVisible,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                PriceRangeFilterCard(
                                    priceRange = priceRange,
                                    onPriceRangeChange = { viewModel.onPriceRangeChange(it) },
                                    onResetClick = { viewModel.resetPriceRange() }
                                )
                            }
                        }
                    }

                    if (searchQuery.isBlank()) {
                        // 3. Horizontal Scrolling Promotional Banner Section
                        item {
                            HomePromoBannerSection(
                                onBannerClick = { categoryTarget ->
                                    viewModel.onCategorySelect(categoryTarget)
                                }
                            )
                        }

                        // 4. Category Filter Chips
                        item {
                            HomeCategorySection(
                                selectedCategory = selectedCategory,
                                onCategorySelected = { viewModel.onCategorySelect(it) }
                            )
                        }

                        // 5. Section Header with Sort Dropdown Menu
                        item {
                            val sectionTitle = when (selectedCategory) {
                                "الكل" -> "عروض اليوم الطازجة"
                                "فواكه" -> "فواكه طازجة • Fresh Fruits"
                                "خضروات" -> "خضروات طازجة • Fresh Vegetables"
                                "عضوي" -> "منتجات عضوية • Organic Produce"
                                "ورقيات" -> "ورقيات وأعشاب • Leafy Greens"
                                "تمور" -> "تمور فاخرة • Premium Dates"
                                else -> "منتجات $selectedCategory"
                            }
                            HomeSectionHeader(
                                title = sectionTitle,
                                badgeText = if (selectedCategory == "الكل") "خصومات حصرية" else "${sortedProducts.size} منتجات",
                                sortOrder = sortOrder,
                                onSortOrderChange = { viewModel.onSortOrderChange(it) },
                                onSeeAllClick = {
                                    viewModel.onCategorySelect("الكل")
                                }
                            )
                        }

                        // Quick Sort Toggle Buttons Row
                        item {
                            ProductSortToggleRow(
                                sortOrder = sortOrder,
                                onSortOrderChange = { viewModel.onSortOrderChange(it) }
                            )
                        }
                    } else {
                        // Real-time search results header with Sort Options
                        item {
                            HomeSectionHeader(
                                title = "نتائج البحث",
                                badgeText = "${sortedProducts.size} منتج",
                                sortOrder = sortOrder,
                                onSortOrderChange = { viewModel.onSortOrderChange(it) },
                                onSeeAllClick = {
                                    viewModel.onSearchQueryChange("")
                                }
                            )
                        }

                        // Quick Sort Toggle Buttons Row
                        item {
                            ProductSortToggleRow(
                                sortOrder = sortOrder,
                                onSortOrderChange = { viewModel.onSortOrderChange(it) }
                            )
                        }
                    }

                    // 6. Products Grid (Rendered in pairs for smooth single-scroll experience)
                    if (sortedProducts.isEmpty()) {
                        item {
                            EmptyProductsState(
                                searchQuery = searchQuery,
                                onResetClick = {
                                    viewModel.onSearchQueryChange("")
                                    viewModel.onCategorySelect("الكل")
                                    viewModel.resetPriceRange()
                                }
                            )
                        }
                    } else {
                        val productPairs = sortedProducts.chunked(2)
                        items(productPairs) { pair ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                for (product in pair) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        ProductCard(
                                            product = product,
                                            isFavorite = favoriteIds.contains(product.id),
                                            onFavoriteClick = { viewModel.toggleFavorite(product.id) },
                                            onClick = { onProductClick(product) },
                                            onAddToCart = {
                                                onAddToCart(product)
                                                recentlyAddedProductName = product.name
                                                scope.launch {
                                                    delay(2000)
                                                    if (recentlyAddedProductName == product.name) {
                                                        recentlyAddedProductName = null
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                                if (pair.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // Temporary Add-To-Cart Feedback Floating Pill
                AnimatedVisibility(
                    visible = recentlyAddedProductName != null,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                ) {
                    Surface(
                        color = FreshGreenDark,
                        shape = RoundedCornerShape(24.dp),
                        shadowElevation = 6.dp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = ActionGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "تمت إضافة $recentlyAddedProductName إلى السلة",
                                color = White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 1. Clean Top Bar with Brand Identity, Location Selector & Actions
 */
@Composable
private fun HomeTopBar(
    onCartClick: () -> Unit,
    onNotificationClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Location & Delivery Information
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { /* Address selection */ }
                .padding(vertical = 4.dp, horizontal = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(FreshGreenLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "الموقع",
                    tint = FreshGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "التوصيل إلى",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "الرياض، حي النخيل",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "تغيير الموقع",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = "خلال ٣٠ دقيقة ⚡ طازج ومضمون",
                    style = MaterialTheme.typography.labelSmall,
                    color = ActionGreen,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Action Icons (Cart & Notifications)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onNotificationClick,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(CardSurface)
                    .border(1.dp, BorderSubtle, CircleShape)
            ) {
                Box {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "الإشعارات",
                        tint = TextDarkGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    // Unread badge dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(AccentOrange)
                    )
                }
            }

            IconButton(
                onClick = onCartClick,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(CardSurface)
                    .border(1.dp, BorderSubtle, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ShoppingCart,
                    contentDescription = "السلة",
                    tint = FreshGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 2. Rounded Search Field with Arabic Placeholder
 */
@Composable
private fun HomeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isFilterActive: Boolean = false,
    onToggleFilter: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = SurfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "بحث",
                    tint = if (query.isNotBlank()) FreshGreen else TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            text = "ابحث عن خضار، فواكه، تمور، ورقيات...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextDarkGreen),
                    modifier = Modifier.weight(1f)
                )
                if (query.isNotBlank()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "مسح البحث",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                // Filter button to toggle price range slider
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isFilterActive) ActionGreen else FreshGreen)
                        .clickable { onToggleFilter() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "تصفية حسب السعر",
                        tint = White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Range slider price filter component inside the search section
 */
@Composable
private fun PriceRangeFilterCard(
    priceRange: ClosedFloatingPointRange<Float>,
    onPriceRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onResetClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = CardSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: Title, formatted range pill, and Reset button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(FreshGreenLight),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = FreshGreen,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "نطاق السعر",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Range Display Pill
                Surface(
                    color = FreshGreenLight,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f - %.1f ر.س", priceRange.start, priceRange.endInclusive),
                        style = MaterialTheme.typography.labelSmall,
                        color = FreshGreenDark,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Material 3 Range Slider
            RangeSlider(
                value = priceRange,
                onValueChange = onPriceRangeChange,
                valueRange = 0f..30f,
                steps = 29, // 1 SAR intervals
                colors = SliderDefaults.colors(
                    thumbColor = FreshGreen,
                    activeTrackColor = ActionGreen,
                    inactiveTrackColor = Color(0xFFE2EBE2),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Slider Range Bounds (0 to 30 SAR)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "٠ ر.س",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = "٣٠ ر.س فأكثر",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Preset Chips and Reset Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = listOf(
                        "الكل" to (0f..30f),
                        "أقل من ٥" to (0f..5f),
                        "٥ - ١٥" to (5f..15f),
                        "أكثر من ١٥" to (15f..30f)
                    )
                    presets.forEach { (label, range) ->
                        val isPresetActive = priceRange.start == range.start && priceRange.endInclusive == range.endInclusive
                        Surface(
                            onClick = { onPriceRangeChange(range) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isPresetActive) ActionGreen else Color(0xFFF1F5F0),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isPresetActive) ActionGreen else BorderSubtle
                            )
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isPresetActive) White else TextDarkGreen,
                                fontWeight = if (isPresetActive) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (priceRange.start > 0f || priceRange.endInclusive < 30f) {
                    Text(
                        text = "إعادة ضبط",
                        style = MaterialTheme.typography.labelSmall,
                        color = FavoriteRed,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onResetClick() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * 3. Hero Promotional Banner with Fruits & Vegetables Aesthetic
 */
/**
 * Data class representing a seasonal promo card in the horizontal scrolling banner
 */
private data class PromoBannerData(
    val id: Int,
    val badge: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val gradientColors: List<Color>,
    val badgeColor: Color = AccentOrange,
    val targetCategory: String = "الكل"
)

/**
 * 3. Horizontal Scrolling Promotional Banner Section featuring seasonal produce & special offers
 */
@Composable
private fun HomePromoBannerSection(
    onBannerClick: (String) -> Unit
) {
    val banners = remember {
        listOf(
            PromoBannerData(
                id = 1,
                badge = "عروض الموسم 🌿",
                title = "خصم حتى 30% على الخضار والفواكه",
                subtitle = "محاصيل محلية طازجة تقطف يومياً من مزارع المملكة",
                imageUrl = "apple",
                gradientColors = listOf(FreshGreen, FreshGreenDark, Color(0xFF04341B)),
                badgeColor = AccentOrange,
                targetCategory = "الكل"
            ),
            PromoBannerData(
                id = 2,
                badge = "تمور القصيم الفاخرة ✨",
                title = "سكري وعجوة ملكية قطفة أولى",
                subtitle = "أصناف سكرية مختارة بعناية للضيافة السعودية الفاخرة",
                imageUrl = "dates",
                gradientColors = listOf(Color(0xFF854D0E), Color(0xFF5A2E07), Color(0xFF291002)),
                badgeColor = Color(0xFFEAB308),
                targetCategory = "تمور"
            ),
            PromoBannerData(
                id = 3,
                badge = "حمضيات منعشة 🍊",
                title = "برتقال أبو صرة وعصائر طبيعية",
                subtitle = "غني بفيتامين C لتعزيز المناعة والانتعاش اليومي",
                imageUrl = "orange",
                gradientColors = listOf(Color(0xFFEA580C), Color(0xFF9A3412), Color(0xFF431407)),
                badgeColor = Color(0xFFF97316),
                targetCategory = "فواكه"
            ),
            PromoBannerData(
                id = 4,
                badge = "ورقيات المزرعة 🌱",
                title = "نعناع حساوي وخس مقرمش",
                subtitle = "مقطوف بعناية ومحفوظ مبرداً حتى باب بيتك خلال دقائق",
                imageUrl = "mint",
                gradientColors = listOf(Color(0xFF15803D), Color(0xFF14532D), Color(0xFF052E16)),
                badgeColor = Color(0xFF22C55E),
                targetCategory = "ورقيات"
            )
        )
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(banners, key = { it.id }) { banner ->
            HomePromoBannerCard(
                banner = banner,
                onShopNowClick = { onBannerClick(banner.targetCategory) }
            )
        }
    }
}

@Composable
private fun HomePromoBannerCard(
    banner: PromoBannerData,
    onShopNowClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(310.dp)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(22.dp),
                ambientColor = banner.gradientColors.first().copy(alpha = 0.2f),
                spotColor = banner.gradientColors.first().copy(alpha = 0.25f)
            )
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(colors = banner.gradientColors))
            .padding(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .align(Alignment.CenterStart)
        ) {
            // Tag Pill
            Surface(
                color = banner.badgeColor,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = banner.badge,
                    color = White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = banner.title,
                style = MaterialTheme.typography.titleLarge,
                color = White,
                fontWeight = FontWeight.Bold,
                lineHeight = 24.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = banner.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = White.copy(alpha = 0.85f),
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(14.dp))
            // Shop Now CTA Pill
            Button(
                onClick = onShopNowClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = White,
                    contentColor = banner.gradientColors[1]
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.height(38.dp)
            ) {
                Text(
                    text = "تسوق الآن",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Decorative Visual on right side of banner
        Box(
            modifier = Modifier
                .size(92.dp)
                .align(Alignment.CenterEnd)
        ) {
            ProductVisual(
                imageUrl = banner.imageUrl,
                size = 92.dp,
                backgroundColor = Color.Transparent,
                contentDescription = banner.title
            )
        }
    }
}

/**
 * 4. Horizontal Scrolling Category Navigation Bar
 * Allows users to filter the product feed by categories like 'Fruits', 'Vegetables', and 'Organic'.
 */
private data class CategoryNavItem(
    val key: String,
    val nameAr: String,
    val nameEn: String,
    val sampleImageKey: String,
    val isOrganic: Boolean = false,
    val badge: String? = null
)

@Composable
private fun HomeCategorySection(
    selectedCategory: String,
    onCategorySelected: (String) -> Unit
) {
    val categories = remember {
        listOf(
            CategoryNavItem(
                key = "الكل",
                nameAr = "الكل",
                nameEn = "All",
                sampleImageKey = "apple"
            ),
            CategoryNavItem(
                key = "فواكه",
                nameAr = "فواكه",
                nameEn = "Fruits",
                sampleImageKey = "orange",
                badge = "طازجة"
            ),
            CategoryNavItem(
                key = "خضروات",
                nameAr = "خضروات",
                nameEn = "Vegetables",
                sampleImageKey = "tomato",
                badge = "محلية"
            ),
            CategoryNavItem(
                key = "عضوي",
                nameAr = "عضوي",
                nameEn = "Organic",
                sampleImageKey = "lettuce",
                isOrganic = true,
                badge = "عضوي 🌱"
            ),
            CategoryNavItem(
                key = "ورقيات",
                nameAr = "ورقيات",
                nameEn = "Greens",
                sampleImageKey = "mint",
                badge = "يومي"
            ),
            CategoryNavItem(
                key = "تمور",
                nameAr = "تمور",
                nameEn = "Dates",
                sampleImageKey = "dates",
                badge = "فاخر ✨"
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // Section Header Row with Category Title and Clear Action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "الأقسام",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkGreen
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "• Categories",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            if (selectedCategory != "الكل") {
                Surface(
                    onClick = { onCategorySelected("الكل") },
                    shape = RoundedCornerShape(8.dp),
                    color = FreshGreenLight,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) {
                    Text(
                        text = "عرض الكل",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = FreshGreen,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Horizontal Scrolling Category Navigation Bar
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("category_navigation_bar"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(categories, key = { it.key }) { category ->
                val isSelected = selectedCategory == category.key
                CategoryNavCard(
                    category = category,
                    isSelected = isSelected,
                    onClick = {
                        if (isSelected && category.key != "الكل") {
                            onCategorySelected("الكل")
                        } else {
                            onCategorySelected(category.key)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CategoryNavCard(
    category: CategoryNavItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFFE8F5E9) else CardSurface,
        animationSpec = spring(),
        label = "catBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) ActionGreen else BorderSubtle,
        animationSpec = spring(),
        label = "catBorder"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(86.dp)
            .height(112.dp)
            .testTag("category_chip_${category.key}"),
        shape = RoundedCornerShape(18.dp),
        color = bgColor,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = borderColor
        ),
        shadowElevation = if (isSelected) 3.dp else 1.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            // Optional Badge on Top (e.g. Organic indicator)
            if (category.isOrganic) {
                Surface(
                    color = ActionGreen,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        text = "عضوي",
                        color = White,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            } else if (category.badge != null && isSelected) {
                Surface(
                    color = FreshGreenLight,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        text = category.badge,
                        color = FreshGreen,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Circular Image Container
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) ActionGreen.copy(alpha = 0.16f) else Color(0xFFF3F5F3)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    ProductVisual(
                        imageUrl = category.sampleImageKey,
                        size = 32.dp,
                        backgroundColor = Color.Transparent,
                        contentDescription = "${category.nameAr} ${category.nameEn}"
                    )
                }

                // Bilingual Typography: Arabic Name + English Category Label
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = category.nameAr,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isSelected) FreshGreenDark else TextDarkGreen,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = category.nameEn,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) ActionGreen else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Selection Pill Indicator at Bottom
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(ActionGreen)
                    )
                } else {
                    Spacer(modifier = Modifier.height(3.dp))
                }
            }
        }
    }
}

/**
 * 5. Section Header with Sort Dropdown Menu
 */
@Composable
private fun HomeSectionHeader(
    title: String,
    badgeText: String,
    sortOrder: ProductSortOrder,
    onSortOrderChange: (ProductSortOrder) -> Unit,
    onSeeAllClick: () -> Unit
) {
    var isSortMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = TextDarkGreen,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = FreshGreenLight,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = badgeText,
                    color = FreshGreen,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "عرض الكل",
                style = MaterialTheme.typography.labelMedium,
                color = FreshGreen,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSeeAllClick() }
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            )

            // Sort Dropdown Menu Button
            Box {
                Surface(
                    onClick = { isSortMenuExpanded = true },
                    shape = RoundedCornerShape(10.dp),
                    color = CardSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                    shadowElevation = 1.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = "ترتيب المنتجات",
                            tint = FreshGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = sortOrder.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextDarkGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = isSortMenuExpanded,
                    onDismissRequest = { isSortMenuExpanded = false },
                    modifier = Modifier.background(CardSurface)
                ) {
                    ProductSortOrder.values().forEach { order ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = order.title,
                                    color = if (sortOrder == order) FreshGreen else TextDarkGreen,
                                    fontWeight = if (sortOrder == order) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = when (order) {
                                        ProductSortOrder.POPULARITY -> Icons.Default.Star
                                        ProductSortOrder.PRICE_LOW_TO_HIGH -> Icons.Default.ArrowDownward
                                        ProductSortOrder.PRICE_HIGH_TO_LOW -> Icons.Default.ArrowUpward
                                    },
                                    contentDescription = null,
                                    tint = if (sortOrder == order) FreshGreen else TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                onSortOrderChange(order)
                                isSortMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Quick sort toggle buttons at the top of the product list
 */
@Composable
private fun ProductSortToggleRow(
    sortOrder: ProductSortOrder,
    onSortOrderChange: (ProductSortOrder) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(ProductSortOrder.values()) { order ->
            val isSelected = sortOrder == order
            Surface(
                onClick = { onSortOrderChange(order) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) FreshGreen else CardSurface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isSelected) FreshGreen else BorderSubtle
                ),
                shadowElevation = if (isSelected) 2.dp else 0.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = when (order) {
                            ProductSortOrder.POPULARITY -> Icons.Default.Star
                            ProductSortOrder.PRICE_LOW_TO_HIGH -> Icons.Default.ArrowDownward
                            ProductSortOrder.PRICE_HIGH_TO_LOW -> Icons.Default.ArrowUpward
                        },
                        contentDescription = null,
                        tint = if (isSelected) White else TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = order.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) White else TextDarkGreen
                    )
                }
            }
        }
    }
}

/**
 * 6. Product Card matching Reference Design:
 * Rounded card, soft shadow, photo/visual, Arabic name, SAR price, unit, favorite & green (+) cart button
 */
@Composable
fun ProductCard(
    product: Product,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit,
    onAddToCart: () -> Unit
) {
    var isAddPressed by remember { mutableStateOf(false) }
    val buttonScope = rememberCoroutineScope()

    val scale by animateFloatAsState(
        targetValue = if (isAddPressed) 0.82f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cartButtonScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isAddPressed) FreshGreenDark else ActionGreen,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "cartButtonColor"
    )

    val iconRotation by animateFloatAsState(
        targetValue = if (isAddPressed) 45f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cartIconRotation"
    )

    val formattedPrice = remember(product.priceHalalas) {
        String.format(Locale.US, "%.2f", product.priceHalalas / 100.0)
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = Color(0x10000000),
                spotColor = Color(0x18000000)
            ),
        shape = RoundedCornerShape(18.dp),
        color = CardSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Card Top Row: Category tag and Favorite Heart
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = FreshGreenLight,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = product.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = FreshGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                // Heart-shaped favorites toggle button with scale animation and active color shift
                val heartScale by animateFloatAsState(
                    targetValue = if (isFavorite) 1.18f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "heartScale"
                )

                IconButton(
                    onClick = onFavoriteClick,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isFavorite) Color(0xFFFFEEF0) else Color(0xFFF8FAF7))
                        .border(
                            width = 1.dp,
                            color = if (isFavorite) Color(0xFFFFCDD2) else BorderSubtle,
                            shape = CircleShape
                        )
                        .graphicsLayer {
                            scaleX = heartScale
                            scaleY = heartScale
                        }
                        .semantics {
                            contentDescription = if (isFavorite) "إزالة ${product.name} من المفضلة" else "إضافة ${product.name} إلى المفضلة"
                        }
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isFavorite) FavoriteRed else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Centered Product Photography Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFFAFCFA)),
                contentAlignment = Alignment.Center
            ) {
                ProductVisual(
                    imageUrl = product.imageUrl,
                    size = 105.dp,
                    backgroundColor = Color.Transparent,
                    contentDescription = product.name
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Product Name
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleMedium,
                color = TextDarkGreen,
                fontWeight = FontWeight.Bold,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )

            // Weight/Unit Label
            Text(
                text = product.unit,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Row: Price in SAR and Circular (+) Add-To-Cart Button with haptic-like animation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = formattedPrice,
                            style = MaterialTheme.typography.titleLarge,
                            color = FreshGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "ر.س",
                            style = MaterialTheme.typography.labelSmall,
                            color = FreshGreenDark,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }

                // Fresh Green Add-to-Cart (+) Action Button with subtle scaling, elevation drop, and color-shifting feedback
                FilledIconButton(
                    onClick = {
                        buttonScope.launch {
                            isAddPressed = true
                            delay(140)
                            isAddPressed = false
                        }
                        onAddToCart()
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = buttonColor,
                        contentColor = White
                    ),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(38.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .shadow(elevation = if (isAddPressed) 0.dp else 2.dp, shape = CircleShape)
                        .semantics { contentDescription = "إضافة إلى السلة" }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = White,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer {
                                rotationZ = iconRotation
                            }
                    )
                }
            }
        }
    }
}

/**
 * Empty Products State
 */
@Composable
private fun EmptyProductsState(
    searchQuery: String,
    onResetClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ProductVisual(
            imageUrl = null,
            size = 80.dp,
            backgroundColor = Color.Transparent,
            contentDescription = "لا توجد نتائج"
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (searchQuery.isNotBlank()) "لا توجد نتائج مطابقة لـ \"$searchQuery\"" else "لا توجد منتجات متوفرة حالياً",
            style = MaterialTheme.typography.titleMedium,
            color = TextDarkGreen,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "يرجى تجربة البحث عن منتج آخر مثل \"تفاح\"، \"طماطم\"، أو \"تمور\"",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        if (searchQuery.isNotBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onResetClick,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FreshGreen),
                border = androidx.compose.foundation.BorderStroke(1.dp, FreshGreen),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "مسح البحث وعرض كل المنتجات",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 7. Polished Bottom Navigation Bar with Icons and Arabic Labels
 */
@Composable
private fun FreshBottomNavigationBar(
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit
) {
    val navItems = remember {
        listOf(
            Triple("الرئيسية", Icons.Default.Home, 0),
            Triple("التصنيفات", Icons.Default.Menu, 1),
            Triple("العروض", Icons.Default.Star, 2),
            Triple("السلة", Icons.Default.ShoppingCart, 3),
            Triple("حسابي", Icons.Default.Person, 4)
        )
    }

    Surface(
        color = CardSurface,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        NavigationBar(
            containerColor = CardSurface,
            tonalElevation = 0.dp,
            modifier = Modifier.height(68.dp)
        ) {
            navItems.forEach { (title, icon, index) ->
                val isSelected = selectedIndex == index
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onItemSelected(index) },
                    icon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = FreshGreen,
                        selectedTextColor = FreshGreen,
                        indicatorColor = FreshGreenLight,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextSecondary
                    )
                )
            }
        }
    }
}
