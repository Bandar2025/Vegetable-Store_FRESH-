package com.example

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.auth.AccessControlDecision
import com.example.data.auth.AccessControlPolicy
import com.example.data.auth.AuthOperationResult
import com.example.data.auth.AuthValidation
import com.example.data.auth.LocalAuthRepository
import com.example.data.auth.PasswordResetOutcome
import com.example.data.auth.PostLoginDestination
import com.example.data.auth.UserRole
import com.example.ui.screens.AccountProfileScreen
import com.example.ui.screens.ForgotPasswordScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StoreOwnerFoundationScreen
import com.example.ui.theme.FRESHTheme
import com.example.ui.viewmodel.AuthViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthRolesAndNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var repository: LocalAuthRepository
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("test_auth_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repository = LocalAuthRepository(prefs)
        viewModel = AuthViewModel(repository)
    }

    @Test
    fun testInputValidationRulesForEmailPasswordPhoneAndName() {
        // Email validation
        assertFalse(AuthValidation.validateEmail("").isValid)
        assertFalse(AuthValidation.validateEmail("invalid-email").isValid)
        assertTrue(AuthValidation.validateEmail("customer@fresh.sa").isValid)

        // Login password validation
        assertFalse(AuthValidation.validateLoginPassword("").isValid)
        assertFalse(AuthValidation.validateLoginPassword("12345").isValid)
        assertTrue(AuthValidation.validateLoginPassword("123456").isValid)

        // Registration password validation (requires >= 8 chars, letters + digits)
        assertFalse(AuthValidation.validateRegistrationPassword("short1").isValid)
        assertFalse(AuthValidation.validateRegistrationPassword("onlyletters").isValid)
        assertFalse(AuthValidation.validateRegistrationPassword("12345678").isValid)
        assertTrue(AuthValidation.validateRegistrationPassword("Fresh2026").isValid)

        // Confirm password validation
        assertFalse(AuthValidation.validateConfirmPassword("Fresh2026", "Mismatch1").isValid)
        assertTrue(AuthValidation.validateConfirmPassword("Fresh2026", "Fresh2026").isValid)

        // Full name validation
        assertFalse(AuthValidation.validateFullName("أ").isValid)
        assertTrue(AuthValidation.validateFullName("فهد العتيبي").isValid)

        // Saudi phone validation
        assertFalse(AuthValidation.validateSaudiPhone("12345").isValid)
        assertTrue(AuthValidation.validateSaudiPhone("0501234567").isValid)
        assertTrue(AuthValidation.validateSaudiPhone("+966501234567").isValid)
    }

    @Test
    fun testRegistrationAlwaysAssignsCustomerRoleAndHashesPassword() {
        val rawPassword = "SecurePassword99"
        val result = viewModel.registerCustomer(
            fullName = "سارة الدوسري",
            email = "sara@example.com",
            phoneNumber = "0551234567",
            password = rawPassword,
            confirmPassword = rawPassword
        )

        assertTrue(result is AuthOperationResult.Success)
        val success = result as AuthOperationResult.Success
        assertEquals(UserRole.CUSTOMER, success.user.role)
        assertFalse(success.user.isLocalDemoAccount)
        assertEquals(PostLoginDestination.CUSTOMER_HOME, success.destination)

        // Ensure plaintext password is NEVER stored
        assertFalse(repository.isPasswordStoredPlaintext("sara@example.com", rawPassword))
        val storedHash = repository.getStoredPasswordHashForTesting("sara@example.com")
        assertNotNull(storedHash)
        assertNotEquals(rawPassword, storedHash)

        // Attempting to register with reserved demo owner email must be rejected
        val reservedAttempt = viewModel.registerCustomer(
            fullName = "محاولة مالك",
            email = LocalAuthRepository.DEMO_OWNER_EMAIL,
            phoneNumber = "0559998877",
            password = rawPassword,
            confirmPassword = rawPassword
        )
        assertTrue(reservedAttempt is AuthOperationResult.Error)
    }

    @Test
    fun testRoleBasedPostLoginNavigationAndAccessControlPolicy() {
        // 1. Customer demo login -> CUSTOMER_HOME, denied store management
        var navigatedDestination: PostLoginDestination? = null
        viewModel.loginWithDemoRole(UserRole.CUSTOMER) { destination ->
            navigatedDestination = destination
        }
        assertEquals(PostLoginDestination.CUSTOMER_HOME, navigatedDestination)
        val customerDecision = viewModel.checkRouteAccess(
            requiresAuth = true,
            requiresStoreManagement = true
        )
        assertTrue(customerDecision is AccessControlDecision.Denied)

        // 2. Store Owner demo login -> STORE_MANAGEMENT, allowed store management
        viewModel.loginWithDemoRole(UserRole.STORE_OWNER) { destination ->
            navigatedDestination = destination
        }
        assertEquals(PostLoginDestination.STORE_MANAGEMENT, navigatedDestination)
        assertEquals(UserRole.STORE_OWNER, viewModel.currentUser.value?.role)
        assertTrue(viewModel.currentUser.value?.isLocalDemoAccount == true)

        val ownerDecision = viewModel.checkRouteAccess(
            requiresAuth = true,
            requiresStoreManagement = true
        )
        assertTrue(ownerDecision is AccessControlDecision.Allowed)

        // 3. Store Admin demo login -> STORE_MANAGEMENT, allowed store management
        viewModel.loginWithDemoRole(UserRole.STORE_ADMIN) { destination ->
            navigatedDestination = destination
        }
        assertEquals(PostLoginDestination.STORE_MANAGEMENT, navigatedDestination)
        val adminDecision = viewModel.checkRouteAccess(
            requiresAuth = true,
            requiresStoreManagement = true
        )
        assertTrue(adminDecision is AccessControlDecision.Allowed)
    }

    @Test
    fun testForgotPasswordDoesNotPretendEmailWasSent() {
        val invalidOutcome = viewModel.submitForgotPassword("not-an-email")
        assertTrue(invalidOutcome is PasswordResetOutcome.ValidationError)

        val validOutcome = viewModel.submitForgotPassword("user@fresh.sa")
        assertTrue(validOutcome is PasswordResetOutcome.BackendNotConnectedNotice)
        val notice = validOutcome as PasswordResetOutcome.BackendNotConnectedNotice
        assertTrue(notice.messageArabic.contains("لم يتم إرسال أي بريد إلكتروني"))
    }

    @Test
    fun testLogoutAndSettingsPersistence() {
        val sharedPrefs = context.getSharedPreferences("persistence_test_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().commit()

        val repo1 = LocalAuthRepository(sharedPrefs)
        val vm1 = AuthViewModel(repo1)

        vm1.loginWithDemoRole(UserRole.STORE_OWNER)
        assertNotNull(vm1.currentUser.value)

        vm1.updateNotificationPreferences(orderNotificationsEnabled = false, promoNotificationsEnabled = true)
        assertFalse(vm1.settings.value.orderNotificationsEnabled)
        assertTrue(vm1.settings.value.promoNotificationsEnabled)

        // Re-instantiate repository from same SharedPreferences to verify persistence
        val repo2 = LocalAuthRepository(sharedPrefs)
        assertEquals(UserRole.STORE_OWNER, repo2.currentUser.value?.role)
        assertFalse(repo2.settings.value.orderNotificationsEnabled)
        assertTrue(repo2.settings.value.promoNotificationsEnabled)

        // Request and confirm logout
        vm1.requestLogoutConfirmation()
        assertTrue(vm1.showLogoutConfirmation.value)

        var logoutCallbackInvoked = false
        vm1.confirmLogout { logoutCallbackInvoked = true }
        assertTrue(logoutCallbackInvoked)
        assertFalse(vm1.showLogoutConfirmation.value)
        assertNull(vm1.currentUser.value)

        // Verify session cleared in SharedPreferences
        val repo3 = LocalAuthRepository(sharedPrefs)
        assertNull(repo3.currentUser.value)
    }

    @Test
    fun testComposeLoginForgotAndUnauthorizedStoreManagementUi() {
        var lastDestination: PostLoginDestination? = null

        composeTestRule.setContent {
            FRESHTheme {
                val user = viewModel.currentUser.collectAsState().value
                if (user == null) {
                    LoginScreen(
                        viewModel = viewModel,
                        onAuthenticated = { lastDestination = it },
                        onNavigateToRegister = {},
                        onNavigateToForgotPassword = {},
                        onBackToShopping = {}
                    )
                } else {
                    StoreOwnerFoundationScreen(
                        viewModel = viewModel,
                        onSwitchToCustomerStorefront = {},
                        onNavigateToAccount = {},
                        onNavigateToSettings = {},
                        onNavigateToLogin = {},
                        onLoggedOut = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("login_screen").assertIsDisplayed()

        // Submit empty login form -> validation error banner appears
        composeTestRule.onNodeWithTag("login_submit_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("auth_error_banner").assertIsDisplayed()

        // Log in as Customer demo -> StoreOwnerFoundationScreen shows unauthorized guard
        composeTestRule.onNodeWithTag("demo_login_customer_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(PostLoginDestination.CUSTOMER_HOME, lastDestination)
        composeTestRule.onNodeWithTag("store_management_unauthorized_card").assertIsDisplayed()

        // Switch to Store Owner demo -> StoreOwnerFoundationScreen shows authorized header
        viewModel.loginWithDemoRole(UserRole.STORE_OWNER) { lastDestination = it }
        composeTestRule.waitForIdle()
        assertEquals(PostLoginDestination.STORE_MANAGEMENT, lastDestination)
        composeTestRule.onNodeWithTag("store_management_authorized_header").assertIsDisplayed()
        composeTestRule.onNodeWithText("مالك المتجر").assertIsDisplayed()

        // Trigger logout confirmation dialog and confirm logout
        composeTestRule.onNodeWithTag("owner_logout_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("logout_confirmation_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("logout_confirm_button").performClick()
        composeTestRule.waitForIdle()

        assertNull(viewModel.currentUser.value)
        composeTestRule.onNodeWithTag("login_screen").assertIsDisplayed()
    }
}
