package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.local.model.CurrencyCalculator
import com.example.data.local.model.Product
import com.example.data.repository.ProductRepository
import com.example.ui.components.ProductVisual
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Represents a configured promotional offer for a catalog product.
 * All monetary amounts are stored in exact integer Halalas (1 SAR = 100 Halalas).
 */
data class ProductOffer(
    val productId: Int,
    val originalPriceHalalas: Int,
    val promoTagArabic: String = "عرض خاص",
    val validUntilIso: String = "2026-12-31",
    val isActiveOffer: Boolean = true
)

/**
 * Calculated UI model for a product with a verified active discount.
 */
data class DiscountedProduct(
    val product: Product,
    val currentPriceHalalas: Int,
    val originalPriceHalalas: Int,
    val savingsHalalas: Int,
    val discountPercent: Int,
    val promoTagArabic: String
) {
    fun formattedCurrentPriceSar(): String =
        CurrencyCalculator.formatHalalasToSar(currentPriceHalalas.toLong())

    fun formattedOriginalPriceSar(): String =
        CurrencyCalculator.formatHalalasToSar(originalPriceHalalas.toLong())

    fun formattedSavingsSar(): String =
        CurrencyCalculator.formatHalalasToSar(savingsHalalas.toLong())
}

enum class OfferSortOption(val labelArabic: String) {
    HIGHEST_DISCOUNT("الأعلى خصماً"),
    LOWEST_PRICE("الأقل سعراً"),
    HIGHEST_SAVINGS("الأعلى توفيراً")
}

/**
 * Pure price calculation and offer eligibility engine for the Offers screen.
 * Ensures discounts are never invented for non-discounted or expired items and
 * accurately calculates current vs. original price, SAR savings, and discount percentage.
 */
object OfferPriceCalculator {

    val DEFAULT_CATALOG_OFFERS: Map<Int, ProductOffer> = mapOf(
        1 to ProductOffer(
            productId = 1,
            originalPriceHalalas = 800, // 8.00 SAR -> 6.50 SAR (Save 1.50 SAR, 19%)
            promoTagArabic = "عرض الموسم",
            validUntilIso = "2026-11-20"
        ),
        3 to ProductOffer(
            productId = 3,
            originalPriceHalalas = 500, // 5.00 SAR -> 3.75 SAR (Save 1.25 SAR, 25%)
            promoTagArabic = "خصم المزرعة",
            validUntilIso = "2026-10-30"
        ),
        4 to ProductOffer(
            productId = 4,
            originalPriceHalalas = 350, // 3.50 SAR -> 2.75 SAR (Save 0.75 SAR, 21%)
            promoTagArabic = "قطف اليوم",
            validUntilIso = "2026-10-24"
        ),
        6 to ProductOffer(
            productId = 6,
            originalPriceHalalas = 700, // 7.00 SAR -> 5.25 SAR (Save 1.75 SAR, 25%)
            promoTagArabic = "عرض العصير",
            validUntilIso = "2026-11-25"
        ),
        7 to ProductOffer(
            productId = 7,
            originalPriceHalalas = 3000, // 30.00 SAR -> 24.00 SAR (Save 6.00 SAR, 20%)
            promoTagArabic = "عرض الضيافة",
            validUntilIso = "2027-03-15"
        ),
        9 to ProductOffer(
            productId = 9,
            originalPriceHalalas = 200, // 2.00 SAR -> 1.50 SAR (Save 0.50 SAR, 25%)
            promoTagArabic = "عرض طازج",
            validUntilIso = "2026-10-22"
        ),
        10 to ProductOffer(
            productId = 10,
            originalPriceHalalas = 1500, // 15.00 SAR -> 12.00 SAR (Save 3.00 SAR, 20%)
            promoTagArabic = "عرض خاص",
            validUntilIso = "2026-11-05"
        )
    )

    /**
     * Calculates the integer discount percentage between [currentPriceHalalas] and [originalPriceHalalas].
     * Returns 0 if there is no valid discount (e.g. current >= original or non-positive values).
     */
    fun calculateDiscountPercent(currentPriceHalalas: Int, originalPriceHalalas: Int): Int {
        if (currentPriceHalalas <= 0 || originalPriceHalalas <= 0) return 0
        if (currentPriceHalalas >= originalPriceHalalas) return 0
        val rawPercent = ((originalPriceHalalas - currentPriceHalalas) * 100.0) / originalPriceHalalas.toDouble()
        return rawPercent.roundToInt().coerceIn(1, 99)
    }

    /**
     * Calculates exact savings in Halalas (`originalPriceHalalas - currentPriceHalalas`), floored at 0.
     */
    fun calculateSavingsHalalas(currentPriceHalalas: Int, originalPriceHalalas: Int): Int {
        if (currentPriceHalalas <= 0 || originalPriceHalalas <= currentPriceHalalas) return 0
        return originalPriceHalalas - currentPriceHalalas
    }

    /**
     * Calculates the discounted current price in Halalas given an [originalPriceHalalas] and [discountPercent].
     */
    fun calculateDiscountedPriceHalalas(originalPriceHalalas: Int, discountPercent: Int): Int {
        if (originalPriceHalalas <= 0) return 0
        if (discountPercent <= 0) return originalPriceHalalas
        if (discountPercent >= 100) return 0
        return ((originalPriceHalalas.toLong() * (100 - discountPercent) + 50L) / 100L).toInt()
    }

    /**
     * Verifies whether [product] has a valid, non-expired active discount under [offer].
     */
    fun hasValidActiveDiscount(
        product: Product,
        offer: ProductOffer?,
        referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO
    ): Boolean {
        if (!product.isVisibleInStorefront) return false
        if (product.isExpired(referenceDateIso)) return false
        if (offer == null || !offer.isActiveOffer) return false
        if (product.priceHalalas <= 0 || offer.originalPriceHalalas <= product.priceHalalas) return false

        val cleanOfferExpiry = offer.validUntilIso.trim()
        if (cleanOfferExpiry.isNotEmpty()) {
            val offerExpiryDate = Product.parseStrictIsoDate(cleanOfferExpiry) ?: return false
            val referenceDate = Product.parseStrictIsoDate(referenceDateIso.trim()) ?: return false
            if (ChronoUnit.DAYS.between(referenceDate, offerExpiryDate) < 0L) {
                return false
            }
        }
        return true
    }

    /**
     * Resolves and filters the catalog to only products with valid active discounts.
     */
    fun filterActiveOfferProducts(
        products: List<Product>,
        offersMap: Map<Int, ProductOffer> = DEFAULT_CATALOG_OFFERS,
        referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO
    ): List<DiscountedProduct> {
        return products.mapNotNull { product ->
            val offer = offersMap[product.id]
            if (!hasValidActiveDiscount(product, offer, referenceDateIso)) {
                null
            } else {
                val validOffer = offer!!
                val currentPrice = product.priceHalalas
                val originalPrice = validOffer.originalPriceHalalas
                val savings = calculateSavingsHalalas(currentPrice, originalPrice)
                val discountPct = calculateDiscountPercent(currentPrice, originalPrice)
                if (discountPct <= 0 || savings <= 0) {
                    null
                } else {
                    DiscountedProduct(
                        product = product,
                        currentPriceHalalas = currentPrice,
                        originalPriceHalalas = originalPrice,
                        savingsHalalas = savings,
                        discountPercent = discountPct,
                        promoTagArabic = validOffer.promoTagArabic
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffersScreen(
    products: List<Product>,
    isLoading: Boolean,
    onProductClick: (Product) -> Unit,
    onAddToCart: (Product) -> Unit,
    onBack: () -> Unit,
    onCartClick: () -> Unit,
    activeOffersMap: Map<Int, ProductOffer> = OfferPriceCalculator.DEFAULT_CATALOG_OFFERS,
    referenceDateIso: String = Product.DEFAULT_REFERENCE_DATE_ISO
) {
    BackHandler(onBack = onBack)

    var selectedCategory by remember { mutableStateOf("الكل") }
    var selectedSort by remember { mutableStateOf(OfferSortOption.HIGHEST_DISCOUNT) }
    var recentlyAddedProductName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val categories = remember { listOf("الكل") + ProductRepository.SUPPORTED_CATEGORIES }

    val allDiscountedProducts = remember(products, activeOffersMap, referenceDateIso) {
        OfferPriceCalculator.filterActiveOfferProducts(
            products = products,
            offersMap = activeOffersMap,
            referenceDateIso = referenceDateIso
        )
    }

    val filteredAndSortedOffers = remember(allDiscountedProducts, selectedCategory, selectedSort) {
        val categoryFiltered = if (selectedCategory == "الكل") {
            allDiscountedProducts
        } else {
            allDiscountedProducts.filter { it.product.category == selectedCategory }
        }
        when (selectedSort) {
            OfferSortOption.HIGHEST_DISCOUNT ->
                categoryFiltered.sortedWith(
                    compareByDescending<DiscountedProduct> { it.discountPercent }
                        .thenByDescending { it.savingsHalalas }
                )
            OfferSortOption.LOWEST_PRICE ->
                categoryFiltered.sortedBy { it.currentPriceHalalas }
            OfferSortOption.HIGHEST_SAVINGS ->
                categoryFiltered.sortedByDescending { it.savingsHalalas }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            containerColor = FreshBackground,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "عروض الخصم الطازجة",
                                fontWeight = FontWeight.Bold,
                                color = FreshGreenDark,
                                style = MaterialTheme.typography.titleLarge
                            )
                            if (allDiscountedProducts.isNotEmpty()) {
                                Text(
                                    text = "${allDiscountedProducts.size} عروض نشطة ومعتمدة",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("offers_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = FreshGreenDark
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onCartClick,
                            modifier = Modifier.testTag("offers_cart_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ShoppingCart,
                                contentDescription = "السلة",
                                tint = FreshGreenDark
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CardSurface)
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Category Filter Row
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 6.dp)
                            .testTag("offers_category_filter_row"),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(categories, key = { it }) { category ->
                            val isSelected = selectedCategory == category
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedCategory = category },
                                label = {
                                    Text(
                                        text = category,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = FreshGreen,
                                    selectedLabelColor = Color.White,
                                    containerColor = CardSurface,
                                    labelColor = TextSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = if (isSelected) FreshGreen else BorderSubtle
                                ),
                                modifier = Modifier.testTag("offers_category_chip_$category")
                            )
                        }
                    }

                    // Sort Options Row
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .testTag("offers_sort_row"),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(OfferSortOption.entries, key = { it.name }) { option ->
                            val isSelected = selectedSort == option
                            Surface(
                                onClick = { selectedSort = option },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) FreshGreenLight else CardSurface,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) FreshGreen else BorderSubtle
                                ),
                                modifier = Modifier.testTag("offers_sort_chip_${option.name}")
                            ) {
                                Text(
                                    text = option.labelArabic,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) FreshGreenDark else TextSecondary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    if (isLoading && products.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("offers_loading_state"),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = FreshGreen)
                        }
                    } else if (filteredAndSortedOffers.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp)
                                .testTag("offers_empty_state"),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.LocalOffer,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = TextMuted
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (selectedCategory == "الكل") {
                                        "لا توجد عروض ترويجية نشطة حالياً. ترقبوا عروضنا القادمة!"
                                    } else {
                                        "لا توجد عروض نشطة في قسم $selectedCategory حالياً."
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextSecondary,
                                    textAlign = TextAlign.Center
                                )
                                if (selectedCategory != "الكل" && allDiscountedProducts.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = { selectedCategory = "الكل" },
                                        border = BorderStroke(1.dp, FreshGreen),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.testTag("offers_reset_category_button")
                                    ) {
                                        Text(
                                            text = "عرض جميع العروض النشطة",
                                            color = FreshGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val maxDiscountPercent = remember(filteredAndSortedOffers) {
                            filteredAndSortedOffers.maxOfOrNull { it.discountPercent } ?: 0
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("offers_products_list"),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item(key = "offers_summary_banner") {
                                Surface(
                                    color = FreshGreenLight.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, FreshGreen.copy(alpha = 0.25f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("offers_summary_banner")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocalOffer,
                                            contentDescription = null,
                                            tint = FreshGreenDark
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "تخفيضات حصرية تصل إلى $maxDiscountPercent%",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = FreshGreenDark,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "تخفيضات حصرية لفترة محدودة على المنتجات المختارة طازجة من المزرعة إليك.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextDarkGreen
                                            )
                                        }
                                    }
                                }
                            }

                            items(filteredAndSortedOffers, key = { it.product.id }) { discountedItem ->
                                OfferProductCard(
                                    product = discountedItem.product,
                                    originalPriceHalalas = discountedItem.originalPriceHalalas,
                                    discountPercent = discountedItem.discountPercent,
                                    promoTagArabic = discountedItem.promoTagArabic,
                                    onClick = { onProductClick(discountedItem.product) },
                                    onAddToCart = {
                                        if (discountedItem.product.isPurchasableInStorefront) {
                                            onAddToCart(discountedItem.product)
                                            recentlyAddedProductName = discountedItem.product.name
                                            scope.launch {
                                                delay(2000)
                                                if (recentlyAddedProductName == discountedItem.product.name) {
                                                    recentlyAddedProductName = null
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Add-To-Cart Confirmation Pill
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
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .testTag("offers_added_to_cart_snackbar")
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
                                text = "تمت إضافة $recentlyAddedProductName بالسعر المخفض إلى السلة",
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

@Composable
fun OfferProductCard(
    product: Product,
    originalPriceHalalas: Int,
    discountPercent: Int = OfferPriceCalculator.calculateDiscountPercent(
        currentPriceHalalas = product.priceHalalas,
        originalPriceHalalas = originalPriceHalalas
    ),
    promoTagArabic: String = "عرض خاص",
    onClick: () -> Unit,
    onAddToCart: () -> Unit
) {
    val currentPriceFormatted = remember(product.priceHalalas) {
        CurrencyCalculator.formatHalalasToSar(product.priceHalalas.toLong())
    }
    val originalPriceFormatted = remember(originalPriceHalalas) {
        CurrencyCalculator.formatHalalasToSar(originalPriceHalalas.toLong())
    }
    val savingsHalalas = remember(product.priceHalalas, originalPriceHalalas) {
        OfferPriceCalculator.calculateSavingsHalalas(product.priceHalalas, originalPriceHalalas)
    }
    val savingsFormatted = remember(savingsHalalas) {
        CurrencyCalculator.formatHalalasToSar(savingsHalalas.toLong())
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("offer_product_card_${product.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, BorderSubtle),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FreshGreenLight.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                ProductVisual(
                    imageUrl = product.imageUrl,
                    contentDescription = product.name,
                    modifier = Modifier.size(76.dp)
                )
                Surface(
                    color = AccentRed,
                    shape = RoundedCornerShape(bottomStart = 8.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .testTag("offer_discount_badge_${product.id}")
                ) {
                    Text(
                        text = "-$discountPercent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = FreshGreenLight,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = product.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = FreshGreenDark,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        color = AccentOrange.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = promoTagArabic,
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentOrange,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "${product.unit} • ${if (product.isOutOfStock) "نفد المخزون" else "متوفر (${product.formattedStockQuantity()})" }",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (product.isOutOfStock) AccentRed else TextSecondary
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Current Price vs Original Price Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "$currentPriceFormatted ر.س",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = FreshGreenDark,
                        modifier = Modifier.testTag("offer_current_price_${product.id}")
                    )
                    Text(
                        text = "$originalPriceFormatted ر.س",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        textDecoration = TextDecoration.LineThrough,
                        modifier = Modifier.testTag("offer_original_price_${product.id}")
                    )
                }

                if (savingsHalalas > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "وفر $savingsFormatted ر.س",
                        style = MaterialTheme.typography.labelSmall,
                        color = FreshGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("offer_savings_badge_${product.id}")
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onAddToCart,
                enabled = product.isPurchasableInStorefront,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FreshGreen,
                    disabledContainerColor = TextMuted
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                    .testTag("offer_add_to_cart_${product.id}")
            ) {
                Text(
                    text = if (product.isOutOfStock) "نفد" else "إضافة",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}
