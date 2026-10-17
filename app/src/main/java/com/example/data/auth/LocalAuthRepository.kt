package com.example.data.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Result of an authentication operation (login or registration).
 */
sealed class AuthOperationResult {
    data class Success(
        val user: AuthenticatedUser,
        val destination: PostLoginDestination
    ) : AuthOperationResult()

    data class Error(val messageArabic: String) : AuthOperationResult()
}

/**
 * Local Development Authentication & Settings Repository.
 *
 * IMPORTANT ARCHITECTURAL LIMITATION:
 * - This repository provides a clearly separated local development/demo authentication flow
 *   because no remote authentication backend is currently connected.
 * - Passwords for locally registered accounts are hashed with a per-account salt using SHA-256
 *   via [PasswordHasher]; plaintext passwords are NEVER stored.
 * - Self-registration ([registerCustomer]) strictly assigns [UserRole.CUSTOMER] and can never
 *   grant [UserRole.STORE_OWNER] or [UserRole.STORE_ADMIN] privileges.
 * - Store owner and administrator sessions can only be activated via the explicit
 *   development/demo role simulator ([signInWithDemoRole]), which marks the session with
 *   `isLocalDemoAccount = true`.
 */
class LocalAuthRepository(
    private val sharedPreferences: SharedPreferences? = null
) {
    private data class StoredAccountRecord(
        val id: String,
        val fullName: String,
        val email: String,
        val phoneNumber: String,
        val role: UserRole,
        val salt: String,
        val passwordHash: String,
        val isLocalDemoAccount: Boolean
    )

    private val accountsByEmail = mutableMapOf<String, StoredAccountRecord>()

    private val _currentUser = MutableStateFlow<AuthenticatedUser?>(null)
    val currentUser: StateFlow<AuthenticatedUser?> = _currentUser.asStateFlow()

    private val _settings = MutableStateFlow(loadInitialSettings())
    val settings: StateFlow<UserSettingsPreferences> = _settings.asStateFlow()

    init {
        loadPersistedAccounts()
        restoreActiveSession()
    }

    /**
     * Registers a new customer account locally.
     *
     * Security rule: Registration ALWAYS assigns [UserRole.CUSTOMER].
     * Store owner or admin privileges are never granted via registration.
     */
    fun registerCustomer(
        fullName: String,
        email: String,
        phoneNumber: String,
        password: String,
        confirmPassword: String
    ): AuthOperationResult {
        val nameValidation = AuthValidation.validateFullName(fullName)
        if (!nameValidation.isValid) {
            return AuthOperationResult.Error(nameValidation.errorMessageArabic!!)
        }

        val emailValidation = AuthValidation.validateEmail(email)
        if (!emailValidation.isValid) {
            return AuthOperationResult.Error(emailValidation.errorMessageArabic!!)
        }

        val phoneValidation = AuthValidation.validateSaudiPhone(phoneNumber)
        if (!phoneValidation.isValid) {
            return AuthOperationResult.Error(phoneValidation.errorMessageArabic!!)
        }

        val passwordValidation = AuthValidation.validateRegistrationPassword(password)
        if (!passwordValidation.isValid) {
            return AuthOperationResult.Error(passwordValidation.errorMessageArabic!!)
        }

        val confirmValidation = AuthValidation.validateConfirmPassword(password, confirmPassword)
        if (!confirmValidation.isValid) {
            return AuthOperationResult.Error(confirmValidation.errorMessageArabic!!)
        }

        val normalizedEmail = email.trim().lowercase()
        if (accountsByEmail.containsKey(normalizedEmail) || isReservedDemoEmail(normalizedEmail)) {
            return AuthOperationResult.Error("البريد الإلكتروني مسجل مسبقاً. يرجى تسجيل الدخول أو استخدام بريد آخر.")
        }

        val accountId = "cust_${UUID.randomUUID().toString().take(8)}"
        val salt = UUID.randomUUID().toString()
        val passwordHash = PasswordHasher.hash(password, salt)

        // Strictly enforce CUSTOMER role on registration — never grant owner/admin privileges
        val record = StoredAccountRecord(
            id = accountId,
            fullName = fullName.trim(),
            email = normalizedEmail,
            phoneNumber = phoneNumber.trim().replace(" ", ""),
            role = UserRole.CUSTOMER,
            salt = salt,
            passwordHash = passwordHash,
            isLocalDemoAccount = false
        )

        accountsByEmail[normalizedEmail] = record
        persistAccountRecord(record)

        val authenticatedUser = record.toAuthenticatedUser()
        setActiveSession(authenticatedUser)

        val destination = AccessControlPolicy.resolvePostLoginDestination(authenticatedUser)
        return AuthOperationResult.Success(authenticatedUser, destination)
    }

    /**
     * Authenticates a locally registered user by verifying their salted SHA-256 password hash.
     */
    fun loginWithCredentials(email: String, password: String): AuthOperationResult {
        val emailValidation = AuthValidation.validateEmail(email)
        if (!emailValidation.isValid) {
            return AuthOperationResult.Error(emailValidation.errorMessageArabic!!)
        }

        val passwordValidation = AuthValidation.validateLoginPassword(password)
        if (!passwordValidation.isValid) {
            return AuthOperationResult.Error(passwordValidation.errorMessageArabic!!)
        }

        val normalizedEmail = email.trim().lowercase()
        val record = accountsByEmail[normalizedEmail]
            ?: return AuthOperationResult.Error(
                "بيانات الدخول غير صحيحة أو الحساب غير مسجل محلياً على هذا الجهاز."
            )

        val candidateHash = PasswordHasher.hash(password, record.salt)
        if (candidateHash != record.passwordHash) {
            return AuthOperationResult.Error("كلمة المرور غير صحيحة. يرجى المحاولة مرة أخرى.")
        }

        val authenticatedUser = record.toAuthenticatedUser()
        setActiveSession(authenticatedUser)

        val destination = AccessControlPolicy.resolvePostLoginDestination(authenticatedUser)
        return AuthOperationResult.Success(authenticatedUser, destination)
    }

    /**
     * Explicitly enters a local development/demo session for testing role-based workflows
     * ([UserRole.CUSTOMER], [UserRole.STORE_OWNER], or [UserRole.STORE_ADMIN]).
     * Clearly marks `isLocalDemoAccount = true`.
     */
    fun signInWithDemoRole(role: UserRole): AuthOperationResult {
        val demoUser = when (role) {
            UserRole.CUSTOMER -> AuthenticatedUser(
                id = "demo_customer_local",
                fullName = "عميل تجريبي (وضع التطوير)",
                email = DEMO_CUSTOMER_EMAIL,
                phoneNumber = "0501112233",
                role = UserRole.CUSTOMER,
                isLocalDemoAccount = true
            )
            UserRole.STORE_OWNER -> AuthenticatedUser(
                id = "demo_owner_local",
                fullName = "مالك المتجر (حساب تجريبي للتطوير)",
                email = DEMO_OWNER_EMAIL,
                phoneNumber = "0504445566",
                role = UserRole.STORE_OWNER,
                isLocalDemoAccount = true
            )
            UserRole.STORE_ADMIN -> AuthenticatedUser(
                id = "demo_admin_local",
                fullName = "مشرف المتجر (حساب تجريبي للتطوير)",
                email = DEMO_ADMIN_EMAIL,
                phoneNumber = "0507778899",
                role = UserRole.STORE_ADMIN,
                isLocalDemoAccount = true
            )
        }

        setActiveSession(demoUser)
        val destination = AccessControlPolicy.resolvePostLoginDestination(demoUser)
        return AuthOperationResult.Success(demoUser, destination)
    }

    /**
     * Handles password reset requests honestly without pretending an email was sent.
     */
    fun requestPasswordReset(email: String): PasswordResetOutcome {
        val emailValidation = AuthValidation.validateEmail(email)
        if (!emailValidation.isValid) {
            return PasswordResetOutcome.ValidationError(emailValidation.errorMessageArabic!!)
        }

        val normalizedEmail = email.trim().lowercase()
        val existsLocally = accountsByEmail.containsKey(normalizedEmail) || isReservedDemoEmail(normalizedEmail)

        return PasswordResetOutcome.BackendNotConnectedNotice(
            email = normalizedEmail,
            isKnownLocalAccount = existsLocally,
            messageArabic = "تنبيه شفافية النظام: لم يتم إرسال أي بريد إلكتروني لاستعادة كلمة المرور لأن التطبيق يعمل حالياً في وضع التطوير المحلي دون اتصال بخادم مصادقة خلفي أو خدمة بريد إلكتروني."
        )
    }

    /**
     * Logs out the current user and clears the persisted session.
     */
    fun logout() {
        _currentUser.value = null
        sharedPreferences?.edit()
            ?.remove(KEY_ACTIVE_USER_ID)
            ?.remove(KEY_ACTIVE_USER_NAME)
            ?.remove(KEY_ACTIVE_USER_EMAIL)
            ?.remove(KEY_ACTIVE_USER_PHONE)
            ?.remove(KEY_ACTIVE_USER_ROLE)
            ?.remove(KEY_ACTIVE_USER_DEMO)
            ?.apply()
    }

    /**
     * Updates notification preferences and persists them to [SharedPreferences].
     */
    fun updateNotificationPreferences(
        orderNotificationsEnabled: Boolean,
        promoNotificationsEnabled: Boolean
    ) {
        val updated = _settings.value.copy(
            orderNotificationsEnabled = orderNotificationsEnabled,
            promoNotificationsEnabled = promoNotificationsEnabled
        )
        _settings.value = updated
        sharedPreferences?.edit()
            ?.putBoolean(KEY_PREF_ORDER_NOTIFICATIONS, orderNotificationsEnabled)
            ?.putBoolean(KEY_PREF_PROMO_NOTIFICATIONS, promoNotificationsEnabled)
            ?.apply()
    }

    fun isPasswordStoredPlaintext(email: String, rawPassword: String): Boolean {
        val normalized = email.trim().lowercase()
        val record = accountsByEmail[normalized] ?: return false
        return record.passwordHash == rawPassword
    }

    fun getStoredPasswordHashForTesting(email: String): String? {
        return accountsByEmail[email.trim().lowercase()]?.passwordHash
    }

    private fun isReservedDemoEmail(email: String): Boolean {
        return email == DEMO_CUSTOMER_EMAIL ||
            email == DEMO_OWNER_EMAIL ||
            email == DEMO_ADMIN_EMAIL
    }

    private fun StoredAccountRecord.toAuthenticatedUser(): AuthenticatedUser {
        return AuthenticatedUser(
            id = id,
            fullName = fullName,
            email = email,
            phoneNumber = phoneNumber,
            role = role,
            isLocalDemoAccount = isLocalDemoAccount
        )
    }

    private fun setActiveSession(user: AuthenticatedUser) {
        _currentUser.value = user
        sharedPreferences?.edit()
            ?.putString(KEY_ACTIVE_USER_ID, user.id)
            ?.putString(KEY_ACTIVE_USER_NAME, user.fullName)
            ?.putString(KEY_ACTIVE_USER_EMAIL, user.email)
            ?.putString(KEY_ACTIVE_USER_PHONE, user.phoneNumber)
            ?.putString(KEY_ACTIVE_USER_ROLE, user.role.name)
            ?.putBoolean(KEY_ACTIVE_USER_DEMO, user.isLocalDemoAccount)
            ?.apply()
    }

    private fun restoreActiveSession() {
        val prefs = sharedPreferences ?: return
        val id = prefs.getString(KEY_ACTIVE_USER_ID, null) ?: return
        val name = prefs.getString(KEY_ACTIVE_USER_NAME, null) ?: return
        val email = prefs.getString(KEY_ACTIVE_USER_EMAIL, null) ?: return
        val phone = prefs.getString(KEY_ACTIVE_USER_PHONE, "") ?: ""
        val roleName = prefs.getString(KEY_ACTIVE_USER_ROLE, UserRole.CUSTOMER.name) ?: UserRole.CUSTOMER.name
        val isDemo = prefs.getBoolean(KEY_ACTIVE_USER_DEMO, false)

        val role = runCatching { UserRole.valueOf(roleName) }.getOrDefault(UserRole.CUSTOMER)
        _currentUser.value = AuthenticatedUser(
            id = id,
            fullName = name,
            email = email,
            phoneNumber = phone,
            role = role,
            isLocalDemoAccount = isDemo
        )
    }

    private fun persistAccountRecord(record: StoredAccountRecord) {
        val prefs = sharedPreferences ?: return
        val emails = prefs.getStringSet(KEY_REGISTERED_EMAILS, emptySet())?.toMutableSet() ?: mutableSetOf()
        emails.add(record.email)
        prefs.edit()
            .putStringSet(KEY_REGISTERED_EMAILS, emails)
            .putString(accountKey(record.email, "id"), record.id)
            .putString(accountKey(record.email, "name"), record.fullName)
            .putString(accountKey(record.email, "phone"), record.phoneNumber)
            .putString(accountKey(record.email, "role"), UserRole.CUSTOMER.name)
            .putString(accountKey(record.email, "salt"), record.salt)
            .putString(accountKey(record.email, "hash"), record.passwordHash)
            .apply()
    }

    private fun loadPersistedAccounts() {
        val prefs = sharedPreferences ?: return
        val emails = prefs.getStringSet(KEY_REGISTERED_EMAILS, emptySet()) ?: emptySet()
        for (email in emails) {
            val id = prefs.getString(accountKey(email, "id"), null) ?: continue
            val name = prefs.getString(accountKey(email, "name"), null) ?: continue
            val phone = prefs.getString(accountKey(email, "phone"), "") ?: ""
            val salt = prefs.getString(accountKey(email, "salt"), null) ?: continue
            val hash = prefs.getString(accountKey(email, "hash"), null) ?: continue
            accountsByEmail[email] = StoredAccountRecord(
                id = id,
                fullName = name,
                email = email,
                phoneNumber = phone,
                role = UserRole.CUSTOMER,
                salt = salt,
                passwordHash = hash,
                isLocalDemoAccount = false
            )
        }
    }

    private fun loadInitialSettings(): UserSettingsPreferences {
        val prefs = sharedPreferences ?: return UserSettingsPreferences()
        return UserSettingsPreferences(
            orderNotificationsEnabled = prefs.getBoolean(KEY_PREF_ORDER_NOTIFICATIONS, true),
            promoNotificationsEnabled = prefs.getBoolean(KEY_PREF_PROMO_NOTIFICATIONS, false),
            selectedLanguageCode = "ar"
        )
    }

    private fun accountKey(email: String, field: String): String = "acct_${email}_$field"

    companion object {
        const val PREFS_NAME = "fresh_local_auth_and_settings"
        const val DEMO_CUSTOMER_EMAIL = "customer.demo@fresh.sa"
        const val DEMO_OWNER_EMAIL = "owner.demo@fresh.sa"
        const val DEMO_ADMIN_EMAIL = "admin.demo@fresh.sa"

        private const val KEY_REGISTERED_EMAILS = "registered_emails"
        private const val KEY_ACTIVE_USER_ID = "active_user_id"
        private const val KEY_ACTIVE_USER_NAME = "active_user_name"
        private const val KEY_ACTIVE_USER_EMAIL = "active_user_email"
        private const val KEY_ACTIVE_USER_PHONE = "active_user_phone"
        private const val KEY_ACTIVE_USER_ROLE = "active_user_role"
        private const val KEY_ACTIVE_USER_DEMO = "active_user_demo"
        private const val KEY_PREF_ORDER_NOTIFICATIONS = "pref_order_notifications"
        private const val KEY_PREF_PROMO_NOTIFICATIONS = "pref_promo_notifications"

        @Volatile
        private var instance: LocalAuthRepository? = null

        fun getInstance(context: Context): LocalAuthRepository {
            return instance ?: synchronized(this) {
                instance ?: LocalAuthRepository(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }
    }
}
