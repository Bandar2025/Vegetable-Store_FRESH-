package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardSurface

private val DefaultShimmerColors = listOf(
    Color(0xFFE7EFEA),
    Color(0xFFF6FBF8),
    Color(0xFFDCE8E0),
    Color(0xFFF6FBF8),
    Color(0xFFE7EFEA)
)

/**
 * Reusable animated shimmer brush for skeleton loading placeholders.
 * Uses soft sage/cream tones aligned with the FRESH grocery palette.
 */
@Composable
fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "freshShimmerTransition")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1250,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "freshShimmerTranslate"
    )

    return Brush.linearGradient(
        colors = DefaultShimmerColors,
        start = Offset(translateAnim - 400f, translateAnim - 400f),
        end = Offset(translateAnim, translateAnim)
    )
}

/**
 * Modifier extension that applies a smooth animated shimmer effect to any placeholder box.
 */
fun Modifier.shimmerBackground(shape: RoundedCornerShape = RoundedCornerShape(12.dp)): Modifier = composed {
    val brush = rememberShimmerBrush()
    this
        .clip(shape)
        .background(brush)
}

/**
 * Shimmer-based skeleton loading state for a single Product Card.
 * Mirrors the exact dimensions, padding, and hierarchy of [com.example.ui.screens.ProductCard]
 * to prevent layout shift when real product data and images finish loading.
 */
@Composable
fun ProductCardSkeleton(
    modifier: Modifier = Modifier,
    shimmerBrush: Brush = rememberShimmerBrush()
) {

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardSurface)
            .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
            .padding(12.dp)
            .testTag("product_card_skeleton")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top badges row placeholder (Category badge + Favorite button)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(54.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(shimmerBrush)
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(shimmerBrush)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Product Photography Container Placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(shimmerBrush)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Product Title Placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmerBrush)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Unit Subtitle Placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmerBrush)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Price and Add-to-Cart Button Row Placeholder
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(68.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(shimmerBrush)
                )

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(shimmerBrush)
                )
            }
        }
    }
}

/**
 * Full Home screen shimmer skeleton state shown while initial products and visuals are loading.
 */
@Composable
fun HomeShimmerSkeletonSection(
    modifier: Modifier = Modifier
) {
    val shimmerBrush = rememberShimmerBrush()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_shimmer_skeleton")
            .semantics { contentDescription = "جاري تحميل المنتجات الطازجة" }
    ) {
        // 1. Promotional Banner Skeleton
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false
        ) {
            items(count = 2, key = { "promo_skeleton_$it" }) {
                Box(
                    modifier = Modifier
                        .width(310.dp)
                        .height(168.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(shimmerBrush)
                )
            }
        }

        // 2. Category Chips Skeleton
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            userScrollEnabled = false
        ) {
            items(count = 5, key = { "category_skeleton_$it" }) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(shimmerBrush)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush)
                    )
                }
            }
        }

        // 3. Pro Tips Card Skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(148.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(shimmerBrush)
        )

        // 4. Section Header Skeleton
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(shimmerBrush)
            )
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(shimmerBrush)
            )
        }

        // 5. Product Grid Skeleton (3 rows of 2 cards)
        repeat(3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ProductCardSkeleton(shimmerBrush = shimmerBrush)
                }
                Box(modifier = Modifier.weight(1f)) {
                    ProductCardSkeleton(shimmerBrush = shimmerBrush)
                }
            }
        }
    }
}
