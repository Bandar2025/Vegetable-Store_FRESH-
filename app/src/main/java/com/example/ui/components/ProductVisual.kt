package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R

/**
 * Resolves produce image identifiers to locally bundled high-quality photography assets.
 * Guarantees that every known product type receives its matching photo.
 */
@DrawableRes
fun resolveProduceDrawable(imageUrl: String?): Int {
    return when (imageUrl?.lowercase()?.trim()) {
        "apple" -> R.drawable.img_product_apple
        "banana" -> R.drawable.img_product_banana
        "tomato" -> R.drawable.img_product_tomato
        "lettuce" -> R.drawable.img_product_lettuce
        "carrot" -> R.drawable.img_product_carrot
        "orange" -> R.drawable.img_product_orange
        "dates" -> R.drawable.img_product_dates
        "cucumber" -> R.drawable.img_product_cucumber
        "mint" -> R.drawable.img_product_mint
        "avocado" -> R.drawable.img_product_avocado
        else -> R.drawable.ic_produce_placeholder
    }
}

/**
 * Displays clean, professional product photography on a light neutral background
 * with consistent aspect ratio, smooth corner clipping, and a graceful fallback placeholder.
 */
@Composable
fun ProductVisual(
    imageUrl: String,
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val isRemoteUrl = imageUrl.startsWith("http://") || imageUrl.startsWith("https://")
    val localResId = if (!isRemoteUrl) resolveProduceDrawable(imageUrl) else R.drawable.ic_produce_placeholder
    val imageModel = if (isRemoteUrl) imageUrl else localResId

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFFAFCFA)),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageModel)
                .crossfade(true)
                .placeholder(R.drawable.ic_produce_placeholder)
                .error(R.drawable.ic_produce_placeholder)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        )
    }
}
