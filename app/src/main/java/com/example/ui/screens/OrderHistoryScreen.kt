package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.auth.AuthenticatedUser
import com.example.data.repository.OrderRepository
import com.example.data.repository.OrderWithItems
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
fun OrderHistoryScreen(
    cartViewModel: CartViewModel,
    currentUser: AuthenticatedUser?,
    onBack: () -> Unit,
    requestedCustomerIdOverride: String? = null
) {
    val effectiveCustomerId = currentUser?.id ?: OrderRepository.GUEST_CUSTOMER_ID
    val targetCustomerId = requestedCustomerIdOverride ?: effectiveCustomerId
    val ordersFlow = remember(cartViewModel, currentUser, targetCustomerId) {
        cartViewModel.observeOrdersForAuthenticatedCustomer(
            actor = currentUser,
            requestedCustomerId = targetCustomerId
        )
    }
    val customerOrders by ordersFlow.collectAsState(initial = emptyList())
    val selectedOrderId by cartViewModel.selectedCustomerOrderId.collectAsState()
    val selectedOrderWithItems = customerOrders.find { it.order.id == selectedOrderId }

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
                    .testTag("order_history_screen")
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (selectedOrderWithItems != null) {
                                cartViewModel.selectCustomerOrderForDetails(null)
                            } else {
                                onBack()
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(CardSurface)
                            .testTag("order_history_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = TextDarkGreen
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "سجل طلباتي المحلية",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextDarkGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (currentUser != null) {
                                "الطلبات المحفوظة لحساب: ${currentUser.fullName} (${customerOrders.size})"
                            } else {
                                "الطلبات المحفوظة للمتسوق المحلي الحالي (${customerOrders.size})"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.testTag("order_history_customer_subtitle")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedOrderWithItems != null) {
                    val selectedOrder = selectedOrderWithItems.order
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = FreshGreenLight,
                        border = BorderStroke(1.dp, FreshGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("customer_order_details_card_${selectedOrder.id}")
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "تفاصيل الطلب ${selectedOrder.displayOrderNumber}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextDarkGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                Button(
                                    onClick = { cartViewModel.selectCustomerOrderForDetails(null) },
                                    colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("customer_order_close_details_${selectedOrder.id}")
                                ) {
                                    Text("إغلاق التفاصيل", color = White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "حالة الطلب الحالية: ${selectedOrder.orderStatus.arabicLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = FreshGreenDark,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("customer_order_detail_status_${selectedOrder.id}")
                            )
                            Text(
                                text = "تاريخ الإنشاء: ${selectedOrder.formattedTimestamp()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                            Text(
                                text = "طريقة الدفع: ${selectedOrder.paymentModeNote}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                if (customerOrders.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = CardSurface,
                        border = BorderStroke(1.dp, BorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("order_history_empty_state")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ReceiptLong,
                                contentDescription = null,
                                tint = FreshGreen,
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "لا توجد طلبات مسجلة لحسابك حتى الآن",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "عند إتمام أي طلب من السلة، سيتم حفظ نسخته وتفاصيل أسعاره هنا تلقائياً.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onBack,
                                colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("order_history_empty_back_button")
                            ) {
                                Icon(Icons.Default.ShoppingBag, contentDescription = null, tint = White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("العودة للتسوق", color = White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("order_history_list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(customerOrders, key = { it.order.id }) { orderWithItems ->
                            OrderHistoryCard(
                                orderWithItems = orderWithItems,
                                onInspectOrder = {
                                    cartViewModel.selectCustomerOrderForDetails(orderWithItems.order.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OrderHistoryCard(
    orderWithItems: OrderWithItems,
    showCustomerName: Boolean = false,
    onInspectOrder: (() -> Unit)? = null
) {
    val order = orderWithItems.order
    val items = orderWithItems.items

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("order_history_card_${order.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "رقم الطلب: ${order.displayOrderNumber}",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("order_history_number_${order.id}")
                    )
                    Text(
                        text = order.formattedTimestamp(),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    if (showCustomerName) {
                        Text(
                            text = "العميل: ${order.customerNameSnapshot}",
                            style = MaterialTheme.typography.labelSmall,
                            color = FreshGreenDark,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = FreshGreenLight,
                    border = BorderStroke(1.dp, FreshGreen.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = order.orderStatus.arabicLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = FreshGreenDark,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("order_history_status_${order.id}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(modifier = Modifier.height(10.dp))

            // Items Snapshot List
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("order_history_item_${order.id}_${item.productId}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.productName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextDarkGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${item.formattedQuantity()} ${item.unit} × ${item.formattedUnitPriceSar()} ر.س",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "${item.formattedLineTotalSar()} ر.س",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FreshGreenDark,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(modifier = Modifier.height(8.dp))

            // Address Snapshot
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = FreshGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "عنوان التوصيل: ${order.deliveryAddressSnapshot}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Totals Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "المجموع الفرعي: ${order.formattedSubtotalSar()} ر.س • التوصيل: ${order.formattedDeliveryFeeSar()} ر.س",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = "الإجمالي: ${order.formattedTotalSar()} ر.س",
                    style = MaterialTheme.typography.titleSmall,
                    color = FreshGreenDark,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("order_history_total_${order.id}")
                )
            }

            if (onInspectOrder != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onInspectOrder,
                    colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_order_inspect_button_${order.id}")
                ) {
                    Text(
                        text = "عرض تفاصيل الطلب وحالته",
                        color = White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
