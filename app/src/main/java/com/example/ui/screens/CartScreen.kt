package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.CartViewModel

@Composable
fun CartScreen(viewModel: CartViewModel, onCheckout: () -> Unit, onBack: () -> Unit) {
    val cartItems by viewModel.cartItems.collectAsState()
    val subtotal by viewModel.subtotal.collectAsState()
    val total by viewModel.total.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "السلة", style = MaterialTheme.typography.headlineMedium)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(cartItems) { (product, quantity) ->
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "${product.name} x $quantity")
                    Text(text = "${(product.priceHalalas * quantity) / 100} SAR")
                }
            }
        }
        Text(text = "المجموع الفرعي: ${subtotal / 100} SAR")
        Text(text = "رسوم التوصيل: 15 SAR")
        Text(text = "الإجمالي: ${total / 100} SAR")
        Button(onClick = onCheckout, modifier = Modifier.fillMaxWidth()) {
            Text("إتمام الطلب")
        }
    }
}
