package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.auth.AccessControlDecision
import com.example.data.auth.AccessControlPolicy
import com.example.data.auth.AuthOperationResult
import com.example.data.auth.AuthenticatedUser
import com.example.data.auth.LocalAuthRepository
import com.example.data.auth.PasswordResetOutcome
import com.example.data.auth.PostLoginDestination
import com.example.data.auth.UserRole
import com.example.data.auth.UserSettingsPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel managing authentication state, user roles, access control checks,
 * and user settings preferences across the FRESH app.
 */
class AuthViewModel(
    private val authRepository: LocalAuthRepository
) : ViewModel() {

    val currentUser: StateFlow<AuthenticatedUser?> = authRepository.currentUser
    val settings: StateFlow<UserSettingsPreferences> = authRepository.settings

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _passwordResetOutcome = MutableStateFlow<PasswordResetOutcome?>(null)
    val passwordResetOutcome: StateFlow<PasswordResetOutcome?> = _passwordResetOutcome.asStateFlow()

    private val _showLogoutConfirmation = MutableStateFlow(false)
    val showLogoutConfirmation: StateFlow<Boolean> = _showLogoutConfirmation.asStateFlow()

    private val _accessDeniedBannerMessage = MutableStateFlow<String?>(null)
    val accessDeniedBannerMessage: StateFlow<String?> = _accessDeniedBannerMessage.asStateFlow()

    fun clearFeedback() {
        _errorMessage.value = null
        _passwordResetOutcome.value = null
        _accessDeniedBannerMessage.value = null
    }

    fun loginWithCredentials(
        email: String,
        password: String,
        onAuthenticated: (PostLoginDestination) -> Unit = {}
    ): AuthOperationResult {
        _isLoading.value = true
        _errorMessage.value = null
        val result = authRepository.loginWithCredentials(email, password)
        _isLoading.value = false

        when (result) {
            is AuthOperationResult.Success -> {
                onAuthenticated(result.destination)
            }
            is AuthOperationResult.Error -> {
                _errorMessage.value = result.messageArabic
            }
        }
        return result
    }

    fun loginWithDemoRole(
        role: UserRole,
        onAuthenticated: (PostLoginDestination) -> Unit = {}
    ): AuthOperationResult {
        _isLoading.value = true
        _errorMessage.value = null
        val result = authRepository.signInWithDemoRole(role)
        _isLoading.value = false

        if (result is AuthOperationResult.Success) {
            onAuthenticated(result.destination)
        }
        return result
    }

    fun registerCustomer(
        fullName: String,
        email: String,
        phoneNumber: String,
        password: String,
        confirmPassword: String,
        onRegistered: (PostLoginDestination) -> Unit = {}
    ): AuthOperationResult {
        _isLoading.value = true
        _errorMessage.value = null
        val result = authRepository.registerCustomer(
            fullName = fullName,
            email = email,
            phoneNumber = phoneNumber,
            password = password,
            confirmPassword = confirmPassword
        )
        _isLoading.value = false

        when (result) {
            is AuthOperationResult.Success -> {
                onRegistered(result.destination)
            }
            is AuthOperationResult.Error -> {
                _errorMessage.value = result.messageArabic
            }
        }
        return result
    }

    fun submitForgotPassword(email: String): PasswordResetOutcome {
        _isLoading.value = true
        _errorMessage.value = null
        val outcome = authRepository.requestPasswordReset(email)
        _isLoading.value = false
        _passwordResetOutcome.value = outcome
        if (outcome is PasswordResetOutcome.ValidationError) {
            _errorMessage.value = outcome.messageArabic
        }
        return outcome
    }

    fun requestLogoutConfirmation() {
        _showLogoutConfirmation.value = true
    }

    fun dismissLogoutConfirmation() {
        _showLogoutConfirmation.value = false
    }

    fun confirmLogout(onLoggedOut: () -> Unit = {}) {
        _showLogoutConfirmation.value = false
        _errorMessage.value = null
        _passwordResetOutcome.value = null
        authRepository.logout()
        onLoggedOut()
    }

    fun updateNotificationPreferences(
        orderNotificationsEnabled: Boolean,
        promoNotificationsEnabled: Boolean
    ) {
        authRepository.updateNotificationPreferences(
            orderNotificationsEnabled = orderNotificationsEnabled,
            promoNotificationsEnabled = promoNotificationsEnabled
        )
    }

    fun checkRouteAccess(
        requiresAuth: Boolean,
        requiresStoreManagement: Boolean
    ): AccessControlDecision {
        val decision = AccessControlPolicy.checkAccess(
            user = currentUser.value,
            requiresAuth = requiresAuth,
            requiresStoreManagement = requiresStoreManagement
        )
        if (decision is AccessControlDecision.Denied) {
            _accessDeniedBannerMessage.value = decision.reasonArabic
        }
        return decision
    }
}
