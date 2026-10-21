package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.auth.AuthenticatedUser
import com.example.data.local.model.CurrencyCalculator
import com.example.data.repository.OrderRepository
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
import com.example.ui.viewmodel.CartViewModel

@Composable
fun CheckoutScreen(
    viewModel: CartViewModel,
    onConfirm: () -> Unit,
    currentUser: AuthenticatedUser? = null,
    onNavigateToOrderHistory: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val detailedItems by viewModel.detailedCartItems.collectAsState()
    val subtotal by viewModel.subtotal.collectAsState()
    val discount by viewModel.discountHalalas.collectAsState()
    val total by viewModel.total.collectAsState()
    val deliveryAddress by viewModel.deliveryAddress.collectAsState()
    val errorMessage by viewModel.cartErrorMessage.collectAsState()
    val isSubmitting by viewModel.isSubmittingCheckout.collectAsState()
    val completedOrder by viewModel.lastCompletedOrder.collectAsState()
    val deliveryFee = viewModel.deliveryFee
    val scrollState = rememberScrollState()

    LaunchedEffect(completedOrder) {
        if (completedOrder != null) {
            scrollState.scrollTo(0)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            containerColor = FreshBackground,
            contentWindowInsets = WindowInsets.statusBars
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("checkout_screen")
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(CardSurface)
                            .testTag("checkout_back_button")
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
                            text = "مراجعة وتأكيد الطلب المحلي",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextDarkGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "حفظ الطلب محلياً في قاعدة بيانات الجهاز وتحديث المخزون فوراً",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Honest Payment & Local Checkout Notice (Never pretends to be a cloud payment gateway)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFFFFBEB),
                    border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("checkout_local_payment_notice")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = AccentOrange,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "إتمام طلب محلي — بوابة الدفع الإلكتروني غير متصلة حالياً",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = OrderRepository.LOCAL_CHECKOUT_PAYMENT_NOTE,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val successOrder = completedOrder
                if (successOrder != null) {
                    // Local Checkout Confirmation State
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = FreshGreenLight,
                        border = BorderStroke(1.5.dp, FreshGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("checkout_confirmation_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = FreshGreen,
                                modifier = Modifier.size(52.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "تم تأكيد وحفظ طلبك المحلي بنجاح",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "رقم الطلب: ${successOrder.order.displayOrderNumber}",
                                style = MaterialTheme.typography.titleMedium,
                                color = FreshGreenDark,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("checkout_saved_order_number")
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "الحالة: ${successOrder.order.orderStatus.arabicLabel}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("checkout_order_status")
                            )

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = BorderSubtle)
                            Spacer(modifier = Modifier.height(12.dp))

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "ملخص الأصناف المؤكدة (تم خصمها من المخزون):",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = TextDarkGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                successOrder.items.forEach { item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${item.productName} (${item.formattedQuantity()} ${item.unit})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextDarkGreen,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${item.formattedLineTotalSar()} ر.س",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = FreshGreenDark,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = BorderSubtle)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("عنوان التوصيل:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    Text(
                                        text = successOrder.order.deliveryAddressSnapshot,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextDarkGreen,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("الإجمالي النهائي:", style = MaterialTheme.typography.titleSmall, color = TextDarkGreen, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "${successOrder.order.formattedTotalSar()} ر.س",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = FreshGreenDark,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.testTag("checkout_confirmed_total")
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            Button(
                                onClick = {
                                    viewModel.dismissOrderConfirmation()
                                    onNavigateToOrderHistory()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = FreshGreenDark),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("checkout_view_orders_button")
                            ) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("عرض سجل طلباتي المحلية", color = White, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = {
                                    viewModel.dismissOrderConfirmation()
                                    onConfirm()
                                },
                                border = BorderStroke(1.dp, FreshGreen),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("checkout_back_to_home_button")
                            ) {
                                Icon(Icons.Default.ShoppingBag, contentDescription = null, tint = FreshGreen)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("العودة إلى المتجر الرئيسي", color = FreshGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    // Error Banner on Checkout Failure (cart remains intact)
                    if (!errorMessage.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFFFEF2F2),
                            border = BorderStroke(1.dp, AccentRed),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                                .testTag("checkout_error_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = AccentRed)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = errorMessage!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AccentRed,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("checkout_error_text")
                                )
                            }
                        }
                    }

                    // 1. Delivery Address Snapshot Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CardSurface,
                        border = BorderStroke(1.dp, BorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("checkout_delivery_address_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = FreshGreen)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "عنوان التوصيل",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextDarkGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = deliveryAddress,
                                onValueChange = { viewModel.updateDeliveryAddress(it) },
                                label = { Text("المدينة، الحي، وتفاصيل الموقع") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = FreshGreen,
                                    unfocusedBorderColor = BorderSubtle
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("checkout_address_input")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. Order Items & Quantities Confirmation Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CardSurface,
                        border = BorderStroke(1.dp, BorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("checkout_items_summary_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "المنتجات والكميات المطلوبة (${detailedItems.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            if (detailedItems.isEmpty()) {
                                Text(
                                    text = "لا توجد منتجات في السلة حالياً.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    modifier = Modifier.testTag("checkout_empty_cart_notice")
                                )
                            } else {
                                detailedItems.forEach { item ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                            .testTag("checkout_line_item_${item.product.id}")
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.product.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = TextDarkGreen,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "الكمية: ${item.formattedQuantity} ${item.product.unit} × ${item.formattedUnitPriceSar()} ر.س",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextSecondary
                                                )
                                            }
                                            Text(
                                                text = "${item.formattedLineTotalSar()} ر.س",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = FreshGreenDark,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        if (item.availabilityWarningArabic != null) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = item.availabilityWarningArabic,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AccentRed,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = BorderSubtle)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Monetary Totals Breakdown Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CardSurface,
                        border = BorderStroke(1.dp, BorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("checkout_totals_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "تفاصيل الحساب (بالريال السعودي)",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(10.dp))

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
                                    modifier = Modifier.testTag("checkout_subtotal_text")
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("رسوم التوصيل:", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                                Text(
                                    text = "${CurrencyCalculator.formatHalalasToSar(if (subtotal > 0L) deliveryFee else 0L)} ر.س",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextDarkGreen,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("checkout_delivery_fee_text")
                                )
                            }

                            if (discount > 0L) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("الخصم:", style = MaterialTheme.typography.bodyMedium, color = FreshGreen)
                                    Text(
                                        text = "-${CurrencyCalculator.formatHalalasToSar(discount)} ر.س",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = FreshGreen,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.testTag("checkout_discount_text")
                                    )
                                }
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
                                    style = MaterialTheme.typography.titleLarge,
                                    color = FreshGreenDark,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("checkout_final_total_text")
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            viewModel.submitLocalCheckout(customer = currentUser)
                        },
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("checkout_confirm_button")
                    ) {
                        Text(
                            text = if (isSubmitting) "جاري حفظ الطلب وتحديث المخزون..." else "تأكيد الطلب المحلي (الدفع عند الاستلام)",
                            color = White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
