package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.auth.AuthenticatedUser
import com.example.data.auth.PasswordResetOutcome
import com.example.data.auth.PostLoginDestination
import com.example.data.auth.UserRole
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

@Composable
fun LoginScreen(
    viewModel: com.example.ui.viewmodel.AuthViewModel,
    onAuthenticated: (PostLoginDestination) -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: () -> Unit,
    onBackToShopping: () -> Unit
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val accessDeniedBanner by viewModel.accessDeniedBannerMessage.collectAsState()

    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }

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
                    .testTag("login_screen")
            ) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = {
                            viewModel.clearFeedback()
                            onBackToShopping()
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(CardSurface)
                            .testTag("login_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "العودة للتسوق",
                            tint = TextDarkGreen
                        )
                    }

                    Surface(
                        color = FreshGreenLight,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "FRESH • فريش السعودية",
                            style = MaterialTheme.typography.labelMedium,
                            color = FreshGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "تسجيل الدخول",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextDarkGreen,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "أهلاً بك في متجر فريش للمنتجات الطازجة. سجل دخولك لإدارة حسابك وطلباتك.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (!accessDeniedBanner.isNullOrBlank()) {
                    AuthFeedbackBanner(
                        message = accessDeniedBanner!!,
                        isError = true,
                        testTag = "access_denied_banner"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (!errorMessage.isNullOrBlank()) {
                    AuthFeedbackBanner(
                        message = errorMessage!!,
                        isError = true,
                        testTag = "auth_error_banner"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Credentials Card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = CardSurface,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = {
                                email = it
                                viewModel.clearFeedback()
                            },
                            label = { Text("البريد الإلكتروني") },
                            placeholder = { Text("name@example.com") },
                            leadingIcon = {
                                Icon(Icons.Default.Email, contentDescription = null, tint = FreshGreen)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            colors = freshTextFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_email_input")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                viewModel.clearFeedback()
                            },
                            label = { Text("كلمة المرور") },
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = FreshGreen)
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { isPasswordVisible = !isPasswordVisible },
                                    modifier = Modifier.testTag("login_password_visibility_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (isPasswordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور",
                                        tint = TextSecondary
                                    )
                                }
                            },
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            colors = freshTextFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_password_input")
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    viewModel.clearFeedback()
                                    onNavigateToForgotPassword()
                                },
                                modifier = Modifier.testTag("forgot_password_button")
                            ) {
                                Text(
                                    text = "نسيت كلمة المرور؟",
                                    color = FreshGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                viewModel.loginWithCredentials(
                                    email = email,
                                    password = password,
                                    onAuthenticated = onAuthenticated
                                )
                            },
                            enabled = !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("login_submit_button")
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp)
                                )
                            } else {
                                Text(
                                    text = "تسجيل الدخول",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                viewModel.clearFeedback()
                                onNavigateToRegister()
                            },
                            border = BorderStroke(1.dp, FreshGreen),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("navigate_create_account_button")
                        ) {
                            Text(
                                text = "إنشاء حساب عميل جديد",
                                color = FreshGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Explicitly Labeled Local Development / Demo Accounts Card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFFFFFBEB),
                    border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("local_dev_demo_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = AccentOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "وضع التطوير المحلي (Local Dev / Demo Only)",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "حسابات محاكاة محلية مخصصة لاختبار صلاحيات الأدوار (العميل، مالك المتجر، مشرف المتجر) بدون خادم إنتاج خلفي.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                viewModel.loginWithDemoRole(UserRole.CUSTOMER, onAuthenticated)
                            },
                            border = BorderStroke(1.dp, FreshGreen),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("demo_login_customer_button")
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = FreshGreen, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("دخول تجريبي: عميل (Customer Demo)", color = FreshGreen, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                viewModel.loginWithDemoRole(UserRole.STORE_OWNER, onAuthenticated)
                            },
                            border = BorderStroke(1.dp, FreshGreenDark),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("demo_login_owner_button")
                        ) {
                            Icon(Icons.Default.Storefront, contentDescription = null, tint = FreshGreenDark, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("دخول تجريبي: مالك المتجر (Owner Dev Demo)", color = FreshGreenDark, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                viewModel.loginWithDemoRole(UserRole.STORE_ADMIN, onAuthenticated)
                            },
                            border = BorderStroke(1.dp, FreshGreenDark),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("demo_login_admin_button")
                        ) {
                            Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = FreshGreenDark, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("دخول تجريبي: مشرف المتجر (Admin Dev Demo)", color = FreshGreenDark, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateAccountScreen(
    viewModel: com.example.ui.viewmodel.AuthViewModel,
    onRegistered: (PostLoginDestination) -> Unit,
    onNavigateToLogin: () -> Unit,
    onBack: () -> Unit
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var fullName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phoneNumber by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }

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
                    .testTag("create_account_screen")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            viewModel.clearFeedback()
                            onBack()
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(CardSurface)
                            .testTag("register_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = TextDarkGreen
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "إنشاء حساب عميل جديد",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Role security notice
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = FreshGreenLight,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = FreshGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ينشئ هذا النموذج حساب «عميل» للتسوق فقط. لا تُمنح صلاحيات مالك المتجر عبر التسجيل العام، ويتم حفظ كلمة المرور محلياً بتشفير SHA-256 المملح.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FreshGreenDark
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (!errorMessage.isNullOrBlank()) {
                    AuthFeedbackBanner(
                        message = errorMessage!!,
                        isError = true,
                        testTag = "register_error_banner"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = CardSurface,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = fullName,
                            onValueChange = {
                                fullName = it
                                viewModel.clearFeedback()
                            },
                            label = { Text("الاسم الكامل") },
                            placeholder = { Text("مثال: عبدالله القحطاني") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = FreshGreen) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next
                            ),
                            colors = freshTextFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("register_name_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = phoneNumber,
                            onValueChange = {
                                phoneNumber = it
                                viewModel.clearFeedback()
                            },
                            label = { Text("رقم الجوال السعودي") },
                            placeholder = { Text("05XXXXXXXX") },
                            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = FreshGreen) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone,
                                imeAction = ImeAction.Next
                            ),
                            colors = freshTextFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("register_phone_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = email,
                            onValueChange = {
                                email = it
                                viewModel.clearFeedback()
                            },
                            label = { Text("البريد الإلكتروني") },
                            placeholder = { Text("name@example.com") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = FreshGreen) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            colors = freshTextFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("register_email_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                viewModel.clearFeedback()
                            },
                            label = { Text("كلمة المرور (٨ أحرف وأرقام على الأقل)") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = FreshGreen) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { isPasswordVisible = !isPasswordVisible },
                                    modifier = Modifier.testTag("register_password_visibility_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (isPasswordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور",
                                        tint = TextSecondary
                                    )
                                }
                            },
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Next
                            ),
                            colors = freshTextFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("register_password_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = {
                                confirmPassword = it
                                viewModel.clearFeedback()
                            },
                            label = { Text("تأكيد كلمة المرور") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = FreshGreen) },
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            colors = freshTextFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("register_confirm_password_input")
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                viewModel.registerCustomer(
                                    fullName = fullName,
                                    email = email,
                                    phoneNumber = phoneNumber,
                                    password = password,
                                    confirmPassword = confirmPassword,
                                    onRegistered = onRegistered
                                )
                            },
                            enabled = !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("register_submit_button")
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(color = White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            } else {
                                Text("إنشاء الحساب والمتابعة للتسوق", color = White, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        TextButton(
                            onClick = {
                                viewModel.clearFeedback()
                                onNavigateToLogin()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("navigate_login_button")
                        ) {
                            Text("لديك حساب بالفعل؟ تسجيل الدخول", color = FreshGreen, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ForgotPasswordScreen(
    viewModel: com.example.ui.viewmodel.AuthViewModel,
    onBackToLogin: () -> Unit
) {
    val errorMessage by viewModel.errorMessage.collectAsState()
    val resetOutcome by viewModel.passwordResetOutcome.collectAsState()

    var email by rememberSaveable { mutableStateOf("") }

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
                    .testTag("forgot_password_screen")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            viewModel.clearFeedback()
                            onBackToLogin()
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(CardSurface)
                            .testTag("forgot_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "العودة لتسجيل الدخول",
                            tint = TextDarkGreen
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "استعادة كلمة المرور",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "أدخل بريدك الإلكتروني للتحقق من حالة استعادة كلمة المرور.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (!errorMessage.isNullOrBlank()) {
                    AuthFeedbackBanner(
                        message = errorMessage!!,
                        isError = true,
                        testTag = "forgot_error_banner"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                val currentOutcome = resetOutcome
                if (currentOutcome is PasswordResetOutcome.BackendNotConnectedNotice) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFFFFBEB),
                        border = BorderStroke(1.dp, AccentOrange),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("forgot_backend_notice_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = AccentOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "لم يتم إرسال بريد إلكتروني (وضع التطوير المحلي)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextDarkGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = currentOutcome.messageArabic,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextDarkGreen
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (currentOutcome.isKnownLocalAccount) {
                                    "حالة الحساب المحلي (${currentOutcome.email}): مسجل على هذا الجهاز، لكن إرسال رسائل البريد يتطلب ربط خادم خلفي فعلي."
                                } else {
                                    "حالة الحساب المحلي (${currentOutcome.email}): غير مسجل محلياً على هذا الجهاز."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = CardSurface,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = {
                                email = it
                                viewModel.clearFeedback()
                            },
                            label = { Text("البريد الإلكتروني") },
                            placeholder = { Text("name@example.com") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = FreshGreen) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Done
                            ),
                            colors = freshTextFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("forgot_email_input")
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { viewModel.submitForgotPassword(email) },
                            colors = ButtonDefaults.buttonColors(containerColor = FreshGreen),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("forgot_submit_button")
                        ) {
                            Text("التحقق من حالة استعادة كلمة المرور", color = White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogoutConfirmationDialog(
    user: AuthenticatedUser?,
    onConfirmLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = CardSurface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.testTag("logout_confirmation_dialog"),
            title = {
                Text(
                    text = "تأكيد تسجيل الخروج",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextDarkGreen,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "هل أنت متأكد من رغبتك في تسجيل الخروج من حسابك الحالي؟",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDarkGreen
                    )
                    if (user != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${user.fullName} (${user.role.arabicTitle})",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmLogout,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("logout_confirm_button")
                ) {
                    Text("تأكيد الخروج", color = White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.dp, BorderSubtle),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("logout_cancel_button")
                ) {
                    Text("إلغاء", color = TextDarkGreen)
                }
            }
        )
    }
}

@Composable
internal fun AuthFeedbackBanner(
    message: String,
    isError: Boolean,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isError) Color(0xFFFEF2F2) else FreshGreenLight,
        border = BorderStroke(1.dp, if (isError) AccentRed.copy(alpha = 0.4f) else FreshGreen.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.Warning else Icons.Default.Info,
                contentDescription = null,
                tint = if (isError) AccentRed else FreshGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) Color(0xFF991B1B) else FreshGreenDark,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
internal fun freshTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = FreshGreen,
    unfocusedBorderColor = BorderSubtle,
    focusedLabelColor = FreshGreen,
    unfocusedLabelColor = TextSecondary,
    cursorColor = FreshGreen
)
