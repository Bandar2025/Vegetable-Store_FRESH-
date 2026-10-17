package com.example.data.auth

import java.security.MessageDigest

/**
 * User roles supported by the FRESH application.
 * Only [STORE_OWNER] and [STORE_ADMIN] have access to store management routes.
 * Self-registration is strictly restricted to [CUSTOMER].
 */
enum class UserRole(
    val arabicTitle: String,
    val arabicDescription: String,
    val canAccessStoreManagement: Boolean
) {
    CUSTOMER(
        arabicTitle = "عميل",
        arabicDescription = "تسوق المنتجات الطازجة وإدارة السلة والطلبات",
        canAccessStoreManagement = false
    ),
    STORE_OWNER(
        arabicTitle = "مالك المتجر",
        arabicDescription = "إدارة المتجر والمنتجات والمخزون والتسعير",
        canAccessStoreManagement = true
    ),
    STORE_ADMIN(
        arabicTitle = "مشرف المتجر",
        arabicDescription = "إدارة عمليات المتجر والمنتجات وصلاحيات التشغيل",
        canAccessStoreManagement = true
    )
}

/**
 * Represents an authenticated user session.
 * Note: Passwords or password hashes are never included in the session model.
 */
data class AuthenticatedUser(
    val id: String,
    val fullName: String,
    val email: String,
    val phoneNumber: String,
    val role: UserRole,
    val isLocalDemoAccount: Boolean
)

/**
 * Canonical post-login navigation targets decided by [AccessControlPolicy].
 */
enum class PostLoginDestination {
    CUSTOMER_HOME,
    STORE_MANAGEMENT
}

/**
 * Result of evaluating whether the current session can open a route.
 */
sealed class AccessControlDecision {
    data object Allowed : AccessControlDecision()
    data class Denied(
        val reasonArabic: String,
        val fallbackRoute: String
    ) : AccessControlDecision()
}

/**
 * Single source of truth for role-based navigation and route access control.
 */
object AccessControlPolicy {

    /**
     * Single navigation decision point after login:
     * - Customers enter the shopping experience ([PostLoginDestination.CUSTOMER_HOME]).
     * - Authorized store owners and administrators enter the store-management experience ([PostLoginDestination.STORE_MANAGEMENT]).
     */
    fun resolvePostLoginDestination(user: AuthenticatedUser): PostLoginDestination {
        return if (user.role.canAccessStoreManagement) {
            PostLoginDestination.STORE_MANAGEMENT
        } else {
            PostLoginDestination.CUSTOMER_HOME
        }
    }

    /**
     * Evaluates whether [user] is permitted to view a screen.
     */
    fun checkAccess(
        user: AuthenticatedUser?,
        requiresAuth: Boolean,
        requiresStoreManagement: Boolean
    ): AccessControlDecision {
        if (requiresStoreManagement) {
            if (user == null) {
                return AccessControlDecision.Denied(
                    reasonArabic = "يجب تسجيل الدخول بحساب مالك أو مشرف المتجر للوصول إلى لوحة إدارة المتجر.",
                    fallbackRoute = "login"
                )
            }
            if (!user.role.canAccessStoreManagement) {
                return AccessControlDecision.Denied(
                    reasonArabic = "غير مصرح بالوصول: حسابك الحالي (${user.role.arabicTitle}) مخصص للتسوق فقط ولا يملك صلاحيات إدارة المتجر.",
                    fallbackRoute = "home"
                )
            }
            return AccessControlDecision.Allowed
        }

        if (requiresAuth && user == null) {
            return AccessControlDecision.Denied(
                reasonArabic = "يرجى تسجيل الدخول للمتابعة.",
                fallbackRoute = "login"
            )
        }

        return AccessControlDecision.Allowed
    }
}

/**
 * Validation result for authentication forms.
 */
data class ValidationResult(
    val isValid: Boolean,
    val errorMessageArabic: String? = null
)

/**
 * Input validation rules for Login, Registration, and Forgot Password screens.
 */
object AuthValidation {
    private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val SAUDI_PHONE_REGEX = Regex("^(05\\d{8}|\\+?9665\\d{8})$")

    fun validateEmail(email: String): ValidationResult {
        val clean = email.trim()
        if (clean.isEmpty()) {
            return ValidationResult(false, "يرجى إدخال البريد الإلكتروني.")
        }
        if (!EMAIL_REGEX.matches(clean)) {
            return ValidationResult(false, "صيغة البريد الإلكتروني غير صحيحة (مثال: name@domain.com).")
        }
        return ValidationResult(true)
    }

    fun validateLoginPassword(password: String): ValidationResult {
        if (password.isEmpty()) {
            return ValidationResult(false, "يرجى إدخال كلمة المرور.")
        }
        if (password.length < 6) {
            return ValidationResult(false, "كلمة المرور يجب أن تتكون من ٦ أحرف على الأقل.")
        }
        return ValidationResult(true)
    }

    fun validateRegistrationPassword(password: String): ValidationResult {
        if (password.isEmpty()) {
            return ValidationResult(false, "يرجى إدخال كلمة المرور.")
        }
        if (password.length < 8) {
            return ValidationResult(false, "كلمة المرور الجديدة يجب أن تتكون من ٨ أحرف على الأقل.")
        }
        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }
        if (!hasLetter || !hasDigit) {
            return ValidationResult(false, "كلمة المرور يجب أن تحتوي على أحرف وأرقام معاً لزيادة الأمان.")
        }
        return ValidationResult(true)
    }

    fun validateConfirmPassword(password: String, confirmPassword: String): ValidationResult {
        if (confirmPassword.isEmpty()) {
            return ValidationResult(false, "يرجى تأكيد كلمة المرور.")
        }
        if (password != confirmPassword) {
            return ValidationResult(false, "كلمتا المرور غير متطابقتين.")
        }
        return ValidationResult(true)
    }

    fun validateFullName(fullName: String): ValidationResult {
        val clean = fullName.trim()
        if (clean.isEmpty()) {
            return ValidationResult(false, "يرجى إدخال الاسم الكامل.")
        }
        if (clean.length < 3) {
            return ValidationResult(false, "الاسم الكامل يجب أن يتكون من ٣ أحرف على الأقل.")
        }
        return ValidationResult(true)
    }

    fun validateSaudiPhone(phone: String): ValidationResult {
        val clean = phone.trim().replace(" ", "")
        if (clean.isEmpty()) {
            return ValidationResult(false, "يرجى إدخال رقم الجوال السعودي.")
        }
        if (!SAUDI_PHONE_REGEX.matches(clean)) {
            return ValidationResult(false, "يرجى إدخال رقم جوال سعودي صحيح يبدأ بـ 05 ويتكون من ١٠ أرقام.")
        }
        return ValidationResult(true)
    }
}

/**
 * Outcome of a password-reset request.
 * Never claims an email was sent unless a real backend confirms it.
 */
sealed class PasswordResetOutcome {
    data class ValidationError(val messageArabic: String) : PasswordResetOutcome()
    data class BackendNotConnectedNotice(
        val email: String,
        val isKnownLocalAccount: Boolean,
        val messageArabic: String
    ) : PasswordResetOutcome()
}

/**
 * User settings preferences persisted locally.
 */
data class UserSettingsPreferences(
    val orderNotificationsEnabled: Boolean = true,
    val promoNotificationsEnabled: Boolean = false,
    val selectedLanguageCode: String = "ar"
)

/**
 * One-way salted SHA-256 hashing utility for local development credential verification.
 * Prevents storing plaintext passwords on disk or in memory.
 */
object PasswordHasher {
    fun hash(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val inputBytes = "$salt:$password:fresh-local-dev-v1".toByteArray(Charsets.UTF_8)
        val hashedBytes = digest.digest(inputBytes)
        return hashedBytes.joinToString("") { "%02x".format(it) }
    }
}
