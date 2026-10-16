package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.model.Product

@Composable
fun ProductDetailScreen(product: Product, onAddToCart: (Product) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = product.name, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = product.description, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "${product.priceHalalas / 100} SAR", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { onAddToCart(product) }, modifier = Modifier.fillMaxWidth()) {
            Text("إضافة إلى السلة")
        }
    }
}
