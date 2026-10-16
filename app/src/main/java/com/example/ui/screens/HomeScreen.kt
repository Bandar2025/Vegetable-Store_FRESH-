package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
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

    var selectedNavIndex by remember { mutableIntStateOf(0) }
    var recentlyAddedProductName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Filter products based on search query and selected category
    val filteredProducts = remember(products, searchQuery, selectedCategory) {
        products.filter { product ->
            val matchesCategory = selectedCategory == "الكل" || product.category.contains(selectedCategory)
            val matchesQuery = searchQuery.isBlank() || product.name.contains(searchQuery, ignoreCase = true) ||
                    product.description.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesQuery
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

                    // 2. Rounded Search Bar
                    item {
                        HomeSearchBar(
                            query = searchQuery,
                            onQueryChange = { viewModel.onSearchQueryChange(it) }
                        )
                    }

                    // 3. Premium Hero Promotional Banner
                    item {
                        HomePromoBanner(
                            onShopNowClick = {
                                viewModel.onCategorySelect("الكل")
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

                    // 5. Section Header ("عروض اليوم")
                    item {
                        HomeSectionHeader(
                            title = "عروض اليوم الطازجة",
                            badgeText = "خصومات حصرية",
                            onSeeAllClick = {
                                viewModel.onCategorySelect("الكل")
                                viewModel.onSearchQueryChange("")
                            }
                        )
                    }

                    // 6. Products Grid (Rendered in pairs for smooth single-scroll experience)
                    if (filteredProducts.isEmpty()) {
                        item {
                            EmptyProductsState(searchQuery = searchQuery)
                        }
                    } else {
                        val productPairs = filteredProducts.chunked(2)
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
    onQueryChange: (String) -> Unit
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
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "بحث",
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
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
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextDarkGreen),
                    modifier = Modifier.weight(1f)
                )
                // Filter badge icon
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(FreshGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "عروض مميزة",
                        tint = White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 3. Hero Promotional Banner with Fruits & Vegetables Aesthetic
 */
@Composable
private fun HomePromoBanner(
    onShopNowClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(22.dp),
                ambientColor = FreshGreen.copy(alpha = 0.2f),
                spotColor = FreshGreen.copy(alpha = 0.25f)
            )
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(FreshGreen, FreshGreenDark, Color(0xFF04341B))
                )
            )
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .align(Alignment.CenterStart)
        ) {
            // Tag Pill
            Surface(
                color = AccentOrange,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "عروض الموسم 🌿",
                    color = White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "خصم حتى 30% على الخضار والفواكه",
                style = MaterialTheme.typography.titleLarge,
                color = White,
                fontWeight = FontWeight.Bold,
                lineHeight = 26.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "محاصيل محلية طازجة تقطف يومياً من مزارع المملكة",
                style = MaterialTheme.typography.bodyMedium,
                color = White.copy(alpha = 0.85f),
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            // Shop Now CTA Pill
            Button(
                onClick = onShopNowClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = White,
                    contentColor = FreshGreenDark
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
                .size(96.dp)
                .align(Alignment.CenterEnd)
        ) {
            ProductVisual(
                imageUrl = "apple",
                size = 96.dp
            )
        }
    }
}

/**
 * 4. Attractive Category Icons and Arabic Labels
 */
@Composable
private fun HomeCategorySection(
    selectedCategory: String,
    onCategorySelected: (String) -> Unit
) {
    val categories = remember {
        listOf(
            "الكل" to "apple",
            "خضروات" to "tomato",
            "فواكه" to "orange",
            "ورقيات" to "lettuce",
            "تمور" to "dates"
        )
    }

    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(categories) { (categoryName, sampleKey) ->
                val isSelected = selectedCategory == categoryName
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) ActionGreen else CardSurface,
                    animationSpec = spring(),
                    label = "categoryBg"
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) White else TextDarkGreen,
                    label = "categoryText"
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onCategorySelected(categoryName) }
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .shadow(
                                elevation = if (isSelected) 4.dp else 1.dp,
                                shape = RoundedCornerShape(18.dp),
                                ambientColor = ActionGreen.copy(alpha = 0.2f)
                            )
                            .clip(RoundedCornerShape(18.dp))
                            .background(bgColor)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) ActionGreen else BorderSubtle,
                                shape = RoundedCornerShape(18.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        ProductVisual(
                            imageUrl = sampleKey,
                            size = 38.dp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = categoryName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = textColor
                    )
                }
            }
        }
    }
}

/**
 * 5. Section Header ("عروض اليوم الطازجة")
 */
@Composable
private fun HomeSectionHeader(
    title: String,
    badgeText: String,
    onSeeAllClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
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

        Text(
            text = "عرض الكل",
            style = MaterialTheme.typography.labelLarge,
            color = ActionGreen,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onSeeAllClick() }
                .padding(vertical = 4.dp, horizontal = 6.dp)
        )
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

                IconButton(
                    onClick = onFavoriteClick,
                    modifier = Modifier
                        .size(32.dp)
                        .semantics { contentDescription = "المفضلة" }
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isFavorite) FavoriteRed else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Centered Product Illustration
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                contentAlignment = Alignment.Center
            ) {
                ProductVisual(
                    imageUrl = product.imageUrl,
                    size = 100.dp,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Weight/Unit Label
            Text(
                text = product.unit,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Row: Price in SAR and Circular (+) Add-To-Cart Button
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

                // Fresh Green Add-to-Cart (+) Action Button
                FilledIconButton(
                    onClick = onAddToCart,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = ActionGreen,
                        contentColor = White
                    ),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(38.dp)
                        .shadow(elevation = 2.dp, shape = CircleShape)
                        .semantics { contentDescription = "إضافة إلى السلة" }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = White,
                        modifier = Modifier.size(20.dp)
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
private fun EmptyProductsState(searchQuery: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ProductVisual(imageUrl = "apple", size = 80.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (searchQuery.isNotBlank()) "لا توجد نتائج بحث مطابقة لـ \"$searchQuery\"" else "لا توجد منتجات متوفرة حالياً",
            style = MaterialTheme.typography.titleMedium,
            color = TextDarkGreen,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "يرجى تجربة تصنيف آخر أو التأكد من كتابة الكلمات بشكل صحيح",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
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
