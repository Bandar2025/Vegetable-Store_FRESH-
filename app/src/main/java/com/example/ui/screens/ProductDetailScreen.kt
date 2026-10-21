package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.model.Product

@Composable
fun ProductDetailScreen(product: Product, onAddToCart: (Product) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = product.name, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = product.description, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "${product.priceHalalas / 100} SAR",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (product.isOutOfStock) "الحالة: نفد المخزون حالياً" else "الحالة: متوفر (${product.stockQuantity} ${product.unit})",
            style = MaterialTheme.typography.bodyMedium,
            color = if (product.isOutOfStock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("product_detail_stock_status")
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                if (product.isPurchasableInStorefront) {
                    onAddToCart(product)
                }
            },
            enabled = product.isPurchasableInStorefront,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("product_detail_add_to_cart_button")
        ) {
            Text(if (product.isOutOfStock) "نفد المخزون" else "إضافة إلى السلة")
        }
    }
}
