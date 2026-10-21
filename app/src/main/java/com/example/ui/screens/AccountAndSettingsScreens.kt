package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storefront
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.example.data.auth.AccessControlDecision
import com.example.data.auth.AccessControlPolicy
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.AccentRed
import com.example.ui.theme.ActionGreen
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardSurface
import com.example.ui.theme.FreshBackground
import com.example.ui.theme.FreshGreen
import com.example.ui.theme.FreshGreenDark
import com.example.ui.theme.FreshGreenLight
import com.example.ui.theme.TextDarkGreen
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.White
import com.example.ui.viewmodel.AuthViewModel

@Composable
fun AccountProfileScreen(
    viewModel: AuthViewModel,
    onNavigateToLogin: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStoreManagement: () -> Unit,
    onBackToShopping: () -> Unit,
    onLoggedOut: () -> Unit,
    onNavigateToOrderHistory: () -> Unit = {}
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val showLogoutDialog by viewModel.showLogoutConfirmation.collectAsState()

    if (showLogoutDialog) {
        LogoutConfirmationDialog(
            user = currentUser,
            onConfirmLogout = {
                viewModel.confirmLogout(onLoggedOut = onLoggedOut)
            },
            onDismiss = { viewModel.dismissLogoutConfirmation() }
        )
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .testTag("account_profile_screen")
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onBackToShopping,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(CardSurface)
                                .testTag("profile_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "العودة للتسوق",
                                tint = TextDarkGreen
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "حسابي والملف الشخصي",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextDarkGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(CardSurface)
                            .testTag("profile_top_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "الإعدادات",
                            tint = FreshGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                val user = currentUser
                if (user == null) {
                    // Guest State Card
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = CardSurface,
                        border = BorderStroke(1.dp, BorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_guest_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(FreshGreenLight),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = FreshGreen,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "أنت تتصفح كمتسوق زائر",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "سجّل دخولك أو أنشئ حساب عميل لحفظ بياناتك، أو استخدم وضع التطوير المحلي لاختبار صلاحيات مالك المتجر.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onNavigateToLogin,
                                colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("profile_login_button")
                            ) {
                                Text("تسجيل الدخول", color = White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = onNavigateToRegister,
                                border = BorderStroke(1.dp, FreshGreen),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("profile_register_button")
                            ) {
                                Text("إنشاء حساب عميل جديد", color = FreshGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    // Authenticated Profile Card
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = CardSurface,
                        border = BorderStroke(1.dp, BorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_authenticated_card")
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(58.dp)
                                        .clip(CircleShape)
                                        .background(FreshGreenLight),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (user.role.canAccessStoreManagement) Icons.Default.Storefront else Icons.Default.Person,
                                        contentDescription = null,
                                        tint = FreshGreen,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = user.fullName,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = TextDarkGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Surface(
                                            color = FreshGreenLight,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = "الدور: ${user.role.arabicTitle}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = FreshGreenDark,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                                    .testTag("profile_role_badge")
                                            )
                                        }
                                        if (user.isLocalDemoAccount) {
                                            Surface(
                                                color = Color(0xFFFFFBEB),
                                                border = BorderStroke(1.dp, AccentOrange),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = "تجريبي للتطوير فقط",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextDarkGreen,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                        .testTag("profile_demo_badge")
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = BorderSubtle)
                            Spacer(modifier = Modifier.height(12.dp))

                            ProfileDetailRow(label = "البريد الإلكتروني", value = user.email)
                            Spacer(modifier = Modifier.height(8.dp))
                            ProfileDetailRow(label = "رقم الجوال", value = user.phoneNumber.ifBlank { "غير مضاف" })
                            Spacer(modifier = Modifier.height(8.dp))
                            ProfileDetailRow(label = "صلاحيات الحساب", value = user.role.arabicDescription)
                        }
                    }

                    if (user.role.canAccessStoreManagement) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToStoreManagement,
                            colors = ButtonDefaults.buttonColors(containerColor = FreshGreenDark),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("profile_store_management_button")
                        ) {
                            Icon(Icons.Default.Storefront, contentDescription = null, tint = White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("فتح لوحة إدارة المتجر (${user.role.arabicTitle})", color = White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Navigation Actions Card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = CardSurface,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        OutlinedButton(
                            onClick = onNavigateToOrderHistory,
                            border = BorderStroke(1.dp, BorderSubtle),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("profile_order_history_button")
                        ) {
                            Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = FreshGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("سجل طلباتي المحلية", color = TextDarkGreen, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = onNavigateToSettings,
                            border = BorderStroke(1.dp, BorderSubtle),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("profile_settings_button")
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = FreshGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("الإعدادات والتفضيلات", color = TextDarkGreen, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = onBackToShopping,
                            border = BorderStroke(1.dp, BorderSubtle),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("profile_back_to_shopping_button")
                        ) {
                            Icon(Icons.Default.ShoppingBag, contentDescription = null, tint = FreshGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("العودة لتسوق المنتجات الطازجة", color = TextDarkGreen, fontWeight = FontWeight.SemiBold)
                        }

                        if (user != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.requestLogoutConfirmation() },
                                border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("profile_logout_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = AccentRed)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("تسجيل الخروج", color = AccentRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextDarkGreen,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SettingsScreen(
    viewModel: AuthViewModel,
    onNavigateToAccount: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val showLogoutDialog by viewModel.showLogoutConfirmation.collectAsState()

    if (showLogoutDialog) {
        LogoutConfirmationDialog(
            user = currentUser,
            onConfirmLogout = {
                viewModel.confirmLogout(onLoggedOut = onLoggedOut)
            },
            onDismiss = { viewModel.dismissLogoutConfirmation() }
        )
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .testTag("settings_screen")
            ) {
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
                            .testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = TextDarkGreen
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "الإعدادات",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 1. Account / Profile Entry Card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = CardSurface,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (currentUser != null) onNavigateToAccount() else onNavigateToLogin()
                        }
                        .testTag("settings_account_card")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(FreshGreenLight),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = FreshGreen)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = currentUser?.fullName ?: "متسوق زائر (غير مسجل الدخول)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextDarkGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (currentUser != null) {
                                        "${currentUser!!.email} • ${currentUser!!.role.arabicTitle}"
                                    } else {
                                        "اضغط هنا لتسجيل الدخول أو إدارة الحساب"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Notification Preferences (Persisted via SharedPreferences)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = CardSurface,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_notifications_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Notifications, contentDescription = null, tint = FreshGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "تفضيلات الإشعارات (محفوظة محلياً)",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "يتم حفظ تفضيلاتك على الجهاز. إرسال الإشعارات الفورية الخارجية يتطلب ربط خدمة إشعارات خادم.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "تحديثات حالة الطلبات والتوصيل",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextDarkGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "تنبيهات تجهيز وتوصيل طلبات الخضار والفواكه",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = settings.orderNotificationsEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.updateNotificationPreferences(
                                        orderNotificationsEnabled = enabled,
                                        promoNotificationsEnabled = settings.promoNotificationsEnabled
                                    )
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = White, checkedTrackColor = FreshGreen),
                                modifier = Modifier.testTag("settings_order_notifications_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = BorderSubtle)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "العروض الموسمية والتخفيضات",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextDarkGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "عروض التمور والفواكه الموسمية اليومية",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = settings.promoNotificationsEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.updateNotificationPreferences(
                                        orderNotificationsEnabled = settings.orderNotificationsEnabled,
                                        promoNotificationsEnabled = enabled
                                    )
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = White, checkedTrackColor = FreshGreen),
                                modifier = Modifier.testTag("settings_promo_notifications_switch")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. Language Preference UI (Clearly distinguishing implemented Arabic RTL from future options)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = CardSurface,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_language_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = FreshGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "اللغة واتجاه الواجهة",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = FreshGreenLight,
                            border = BorderStroke(1.dp, FreshGreen),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "العربية (المملكة العربية السعودية - RTL)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = FreshGreenDark,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "مفعّلة بالكامل ومعتمدة للتسوق والأسعار بالريال السعودي (ر.س)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                Icon(Icons.Default.CheckCircle, contentDescription = "مفعلة", tint = FreshGreen)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = FreshBackground,
                            border = BorderStroke(1.dp, BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "English (خيار مستقبلي — غير مفعّل حالياً)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "سيتم توفير واجهة اللغة الإنجليزية في تحديث لاحق.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 4. Privacy & About Information
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = CardSurface,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_privacy_about_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = FreshGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "الخصوصية وحول تطبيق FRESH",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• الإصدار: 1.0 (تطبيق أندرويد الأصلي - Jetpack Compose)\n" +
                                "• الأمان والخصوصية: لا يتم حفظ أي كلمات مرور نصية صريحة؛ تُستخدم خوارزمية SHA-256 المملحة محلياً.\n" +
                                "• بيئة العمل: جلسات المصادقة الحالية تعمل في وضع التطوير المحلي على الجهاز لحين ربط خادم المصادقة السحابي.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                if (currentUser != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { viewModel.requestLogoutConfirmation() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("settings_logout_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("تسجيل الخروج من الحساب", color = White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Store-Owner / Store-Admin Foundation Entry Screen (Micro Task 5A).
 *
 * Enforces role-based access control via [AccessControlPolicy]:
 * - If accessed by an unauthenticated user or a [UserRole.CUSTOMER], displays an explicit
 *   unauthorized access screen and allows returning safely to the customer storefront or login.
 * - If accessed by an authorized [UserRole.STORE_OWNER] or [UserRole.STORE_ADMIN], displays the
 *   verified store-management entry foundation ready for Micro Task 5B.
 */
@Composable
fun StoreOwnerFoundationScreen(
    viewModel: AuthViewModel,
    onSwitchToCustomerStorefront: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onLoggedOut: () -> Unit,
    storeViewModel: com.example.ui.viewmodel.StoreManagementViewModel? = null
) {
    StoreOwnerPortalScreen(
        authViewModel = viewModel,
        storeViewModel = storeViewModel,
        onSwitchToCustomerStorefront = onSwitchToCustomerStorefront,
        onNavigateToAccount = onNavigateToAccount,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToLogin = onNavigateToLogin,
        onLoggedOut = onLoggedOut
    )
}

