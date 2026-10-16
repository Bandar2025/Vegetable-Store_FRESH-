package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.Product
import com.example.ui.viewmodel.HomeViewModel

@Composable
fun HomeScreen(viewModel: HomeViewModel, onProductClick: (Product) -> Unit) {
    val products by viewModel.products.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "FRESH", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(products) { product ->
                ProductCard(product = product, onClick = { onProductClick(product) })
            }
        }
    }
}

@Composable
fun ProductCard(product: Product, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(4.dp), onClick = onClick) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(text = product.name, style = MaterialTheme.typography.titleMedium)
            Text(text = "${product.priceHalalas / 100} SAR / ${product.unit}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
