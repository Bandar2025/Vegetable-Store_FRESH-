package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.R

/**
 * Resolves produce image identifiers, file names, or keywords to locally bundled high-quality photography assets.
 * Handles null, empty, unknown, uppercase, file extensions, and keywords safely.
 */
@DrawableRes
fun resolveProduceDrawable(imageUrl: String?): Int {
    val clean = imageUrl?.lowercase()?.trim() ?: return R.drawable.ic_produce_placeholder
    if (clean.isBlank()) return R.drawable.ic_produce_placeholder

    return when {
        clean.contains("apple") || clean.contains("تفاح") -> R.drawable.img_product_apple
        clean.contains("banana") || clean.contains("موز") -> R.drawable.img_product_banana
        clean.contains("tomato") || clean.contains("طماطم") -> R.drawable.img_product_tomato
        clean.contains("lettuce") || clean.contains("خس") -> R.drawable.img_product_lettuce
        clean.contains("carrot") || clean.contains("جزر") -> R.drawable.img_product_carrot
        clean.contains("orange") || clean.contains("برتقال") -> R.drawable.img_product_orange
        clean.contains("date") || clean.contains("تمر") -> R.drawable.img_product_dates
        clean.contains("cucumber") || clean.contains("خيار") -> R.drawable.img_product_cucumber
        clean.contains("mint") || clean.contains("نعناع") -> R.drawable.img_product_mint
        clean.contains("avocado") || clean.contains("أفوكادو") || clean.contains("افوكادو") -> R.drawable.img_product_avocado
        else -> R.drawable.ic_produce_placeholder
    }
}

/**
 * Displays clean, professional product photography on a light neutral background
 * with consistent aspect ratio, smooth corner clipping, shimmer skeleton while loading remote images,
 * and a graceful fallback placeholder.
 *
 * Performance optimizations:
 * - Remembers [ImageRequest] keyed by model and target pixel size to avoid rebuilding requests on recomposition.
 * - Avoids setting `.placeholder(fallbackResId)` when `imageModel` is already the local `fallbackResId`,
 *   preventing synchronous main-thread duplicate WebP decoding.
 * - Allocates [rememberShimmerBrush] only while a remote image is actively loading (`isImageLoading == true`),
 *   preventing continuous 60fps infinite-transition recompositions across idle product cards.
 */
@Composable
fun ProductVisual(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    backgroundColor: Color = Color(0xFFFAFCFA),
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val targetSizePx = remember(size, density) {
        with(density) { size.roundToPx().coerceAtLeast(1) }
    }

    val cleanUrl = remember(imageUrl) { imageUrl?.trim().orEmpty() }
    val isRemoteUrl = remember(cleanUrl) {
        cleanUrl.startsWith("http://", ignoreCase = true) ||
            cleanUrl.startsWith("https://", ignoreCase = true)
    }
    val fallbackResId = remember(cleanUrl) { resolveProduceDrawable(cleanUrl) }
    val imageModel: Any = if (isRemoteUrl) cleanUrl else fallbackResId

    var isImageLoading by remember(imageModel) { mutableStateOf(isRemoteUrl) }

    val imageRequest = remember(context, imageModel, fallbackResId, isRemoteUrl, targetSizePx) {
        val cacheKey = "${imageModel}_$targetSizePx"
        ImageRequest.Builder(context)
            .data(imageModel)
            .size(targetSizePx)
            .memoryCacheKey(cacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .apply {
                if (isRemoteUrl) {
                    crossfade(true)
                    error(fallbackResId)
                    fallback(fallbackResId)
                } else {
                    crossfade(false)
                }
            }
            .build()
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isImageLoading) Color.Transparent else backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (isImageLoading) {
            val shimmerBrush = rememberShimmerBrush()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(shimmerBrush)
            )
        }

        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            onLoading = {
                if (isRemoteUrl) {
                    isImageLoading = true
                }
            },
            onSuccess = {
                if (isImageLoading) {
                    isImageLoading = false
                }
            },
            onError = {
                if (isImageLoading) {
                    isImageLoading = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        )
    }
}
