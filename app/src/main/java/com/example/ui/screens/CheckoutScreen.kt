package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.CartViewModel

@Composable
fun CheckoutScreen(viewModel: CartViewModel, onConfirm: () -> Unit) {
    val total by viewModel.total.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "إتمام الطلب", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "الإجمالي: ${total / 100} SAR")
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = {
            // Demo order: just confirm locally
            onConfirm()
        }, modifier = Modifier.fillMaxWidth()) {
            Text("تأكيد الطلب (محاكاة)")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "ملاحظة: هذا الطلب محاكاة ولا يتم إرساله إلى المتجر.", style = MaterialTheme.typography.bodySmall)
    }
}
