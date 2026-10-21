package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.local.model.CurrencyCalculator
import com.example.ui.components.ProductVisual
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.AccentRed
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardSurface
import com.example.ui.theme.FreshBackground
import com.example.ui.theme.FreshGreen
import com.example.ui.theme.FreshGreenDark
import com.example.ui.theme.FreshGreenLight
import com.example.ui.theme.TextDarkGreen
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.White
import com.example.ui.viewmodel.CartLineItemUiModel
import com.example.ui.viewmodel.CartViewModel

@Composable
fun CartScreen(
    viewModel: CartViewModel,
    onCheckout: () -> Unit,
    onBack: () -> Unit,
    onOpenOrderHistory: () -> Unit = {}
) {
    val detailedItems by viewModel.detailedCartItems.collectAsState()
    val subtotal by viewModel.subtotal.collectAsState()
    val total by viewModel.total.collectAsState()
    val errorMessage by viewModel.cartErrorMessage.collectAsState()
    val deliveryFee = viewModel.deliveryFee

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            containerColor = FreshBackground,
            contentWindowInsets = WindowInsets.statusBars
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("cart_screen")
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(CardSurface)
                                .testTag("cart_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = TextDarkGreen
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "سلة التسوق",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${detailedItems.size} أصناف في السلة",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenOrderHistory,
                        border = BorderStroke(1.dp, FreshGreen),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("cart_order_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ReceiptLong,
                            contentDescription = null,
                            tint = FreshGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "طلباتي",
                            style = MaterialTheme.typography.labelMedium,
                            color = FreshGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Error Banner
                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFEF2F2),
                        border = BorderStroke(1.dp, AccentRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("cart_error_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = AccentRed)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentRed,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.clearErrorMessage() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "إغلاق", tint = AccentRed)
                            }
                        }
                    }
                }

                if (detailedItems.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = CardSurface,
                        border = BorderStroke(1.dp, BorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("cart_empty_state")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ShoppingBag,
                                contentDescription = null,
                                tint = FreshGreen,
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "سلة التسوق فارغة حالياً",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "أضف الفواكه والخضروات الطازجة من المتجر للبدء في إتمام طلبك المحلي.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onBack,
                                colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("cart_empty_shop_button")
                            ) {
                                Text("تصفح المنتجات الطازجة", color = White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("cart_items_list"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        items(detailedItems, key = { it.product.id }) { item ->
                            CartItemCard(
                                item = item,
                                onUpdateQuantityMillis = { newMillis ->
                                    viewModel.updateCartItemQuantityMillis(item.product.id, newMillis)
                                },
                                onRemove = {
                                    viewModel.removeFromCart(item.product.id)
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Totals & Checkout Summary Footer
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = CardSurface,
                        border = BorderStroke(1.dp, BorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cart_summary_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("المجموع الفرعي:", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                                Text(
                                    text = "${CurrencyCalculator.formatHalalasToSar(subtotal)} ر.س",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextDarkGreen,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("cart_subtotal_text")
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("رسوم التوصيل:", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                                Text(
                                    text = "${CurrencyCalculator.formatHalalasToSar(deliveryFee)} ر.س",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextDarkGreen,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("cart_delivery_fee_text")
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = BorderSubtle)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "الإجمالي النهائي:",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextDarkGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${CurrencyCalculator.formatHalalasToSar(total)} ر.س",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = FreshGreenDark,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("cart_total_text")
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    viewModel.captureCheckoutPriceSnapshot()
                                    onCheckout()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("cart_proceed_checkout_button")
                            ) {
                                Text(
                                    text = "إتمام الطلب ومراجعة التأكيد",
                                    color = White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CartItemCard(
    item: CartLineItemUiModel,
    onUpdateQuantityMillis: (Int) -> Unit,
    onRemove: () -> Unit
) {
    val product = item.product
    val stepMillis = if (product.unit.contains("كيلو") || product.unit.contains("كجم")) 500 else 1000

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (item.isAvailable) BorderSubtle else AccentRed.copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cart_item_card_${product.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProductVisual(
                    imageUrl = product.imageUrl,
                    size = 54.dp,
                    backgroundColor = FreshGreenLight,
                    contentDescription = product.name
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${item.formattedUnitPriceSar()} ر.س / ${product.unit} • المتوفر: ${product.formattedStockQuantity()} ${product.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("cart_remove_item_${product.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "حذف من السلة",
                        tint = AccentRed
                    )
                }
            }

            if (item.availabilityWarningArabic != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFEF2F2),
                    border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cart_item_warning_${product.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AccentRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.availabilityWarningArabic,
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Quantity Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { onUpdateQuantityMillis(item.quantityMillis - stepMillis) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("cart_decrease_qty_${product.id}")
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "إنقاص الكمية", modifier = Modifier.size(16.dp), tint = TextDarkGreen)
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = FreshGreenLight
                    ) {
                        Text(
                            text = "${item.formattedQuantity} ${product.unit}",
                            style = MaterialTheme.typography.labelLarge,
                            color = FreshGreenDark,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("cart_item_qty_${product.id}")
                        )
                    }

                    OutlinedButton(
                        onClick = { onUpdateQuantityMillis(item.quantityMillis + stepMillis) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("cart_increase_qty_${product.id}")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "زيادة الكمية", modifier = Modifier.size(16.dp), tint = FreshGreen)
                    }
                }

                // Line Total
                Text(
                    text = "${item.formattedLineTotalSar()} ر.س",
                    style = MaterialTheme.typography.titleMedium,
                    color = FreshGreenDark,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("cart_item_line_total_${product.id}")
                )
            }
        }
    }
}
