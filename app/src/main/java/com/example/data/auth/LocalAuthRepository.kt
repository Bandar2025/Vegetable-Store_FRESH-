package com.example.data.auth

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import com.example.data.api.FreshApiService
import com.example.data.api.FreshRetrofitClient
import com.example.data.api.model.AuthLoginRequestDto
import com.example.data.api.model.AuthLogoutRequestDto
import com.example.data.api.model.AuthMeResponseDto
import com.example.data.api.model.AuthRefreshRequestDto
import com.example.data.api.model.AuthRegisterRequestDto
import com.example.data.api.model.AuthTokenResponseDto
import com.example.data.api.toJwtSessionTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.IOException
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
 * Authentication & Settings Repository integrating:
 * 1. Django REST Framework JWT authentication (`/api/v1/auth/register/`, `/api/v1/auth/login/`,
 *    `/api/v1/auth/refresh/`, `/api/v1/auth/me/`, `/api/v1/auth/logout/`).
 * 2. Encrypted token persistence via [SecureTokenStorage] (Android Keystore AES-256-GCM).
 * 3. Non-destructive preservation of existing local accounts and settings during offline/local usage.
 * 4. Strict restriction of demo accounts (`DEMO_CUSTOMER`, `DEMO_OWNER`, `DEMO_ADMIN`) to
 *    `BuildConfig.DEBUG` builds only.
 */
class LocalAuthRepository(
    private val sharedPreferences: SharedPreferences? = null,
    val secureTokenStorage: SecureTokenStorage = SecureTokenStorage(sharedPreferences),
    private val apiService: FreshApiService? = null,
    private val allowOfflineLocalFallback: Boolean = true,
    private val isDebugBuild: Boolean = BuildConfig.DEBUG,
    private val nowEpochSecondsProvider: () -> Long = { System.currentTimeMillis() / 1000L }
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
     * Registers a new customer account via `POST /api/v1/auth/register/` when [apiService] is
     * connected, or locally when offline fallback is enabled.
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
        val cleanPhone = phoneNumber.trim().replace(" ", "")

        if (isReservedDemoEmail(normalizedEmail)) {
            return AuthOperationResult.Error("البريد الإلكتروني مسجل مسبقاً. يرجى تسجيل الدخول أو استخدام بريد آخر.")
        }

        // 1. Attempt server-authoritative customer registration when apiService is configured
        val service = apiService
        if (service != null) {
            try {
                val response = runBlocking {
                    service.registerCustomer(
                        AuthRegisterRequestDto(
                            username = normalizedEmail,
                            email = normalizedEmail,
                            fullName = fullName.trim(),
                            phoneNumber = cleanPhone,
                            password = password,
                            confirmPassword = confirmPassword
                        )
                    )
                }
                return completeRemoteAuthentication(
                    tokenResponse = response,
                    fallbackEmail = normalizedEmail,
                    fallbackFullName = fullName.trim(),
                    fallbackPhone = cleanPhone,
                    rawPasswordForLocalBackup = password,
                    enforceCustomerRoleOnly = true
                )
            } catch (httpEx: HttpException) {
                return AuthOperationResult.Error(parseServerAuthErrorArabic(httpEx, isRegistration = true))
            } catch (_: UnsupportedOperationException) {
                if (!allowOfflineLocalFallback) {
                    return AuthOperationResult.Error("خدمة إنشاء الحساب عن بُعد غير مفعلة حالياً.")
                }
            } catch (_: IOException) {
                if (!allowOfflineLocalFallback) {
                    return AuthOperationResult.Error("تعذر الاتصال بخادم المصادقة لإنشاء الحساب. يرجى التحقق من الاتصال بالشبكة.")
                }
            }
        }

        // 2. Local registration fallback (when offline or in pure local unit tests)
        if (accountsByEmail.containsKey(normalizedEmail)) {
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
            phoneNumber = cleanPhone,
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
     * Authenticates a user via `POST /api/v1/auth/login/` when [apiService] is configured,
     * persisting the returned JWT token pair encrypted in [SecureTokenStorage].
     * Falls back to verifying locally stored accounts when offline if [allowOfflineLocalFallback] is true.
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

        // 1. Attempt server-authoritative JWT login when apiService is configured
        val service = apiService
        if (service != null) {
            try {
                val response = runBlocking {
                    service.login(
                        AuthLoginRequestDto(
                            username = normalizedEmail,
                            email = normalizedEmail,
                            password = password
                        )
                    )
                }
                val existingLocal = accountsByEmail[normalizedEmail]
                return completeRemoteAuthentication(
                    tokenResponse = response,
                    fallbackEmail = normalizedEmail,
                    fallbackFullName = response.fullName?.takeIf { it.isNotBlank() }
                        ?: existingLocal?.fullName
                        ?: normalizedEmail.substringBefore("@"),
                    fallbackPhone = response.phoneNumber?.takeIf { it.isNotBlank() }
                        ?: existingLocal?.phoneNumber
                        ?: "",
                    rawPasswordForLocalBackup = password,
                    enforceCustomerRoleOnly = false
                )
            } catch (httpEx: HttpException) {
                if (httpEx.code() == 401 || httpEx.code() == 403) {
                    secureTokenStorage.clearTokens()
                }
                return AuthOperationResult.Error(parseServerAuthErrorArabic(httpEx, isRegistration = false))
            } catch (_: UnsupportedOperationException) {
                if (!allowOfflineLocalFallback) {
                    return AuthOperationResult.Error("خدمة تسجيل الدخول عن بُعد غير مفعلة حالياً.")
                }
            } catch (_: IOException) {
                if (!allowOfflineLocalFallback) {
                    return AuthOperationResult.Error("تعذر الاتصال بخادم المصادقة لتسجيل الدخول. يرجى التحقق من الاتصال بالشبكة.")
                }
            }
        }

        // 2. Local account fallback (offline or local-only unit tests)
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
     * Fetches the authenticated user's identity profile from `GET /api/v1/auth/me/` and synchronizes
     * the active session role and branch permissions.
     */
    fun fetchRemoteCurrentUserMe(): AuthMeResponseDto? {
        val service = apiService ?: return null
        return try {
            val meDto = runBlocking { service.getCurrentUserMe() }
            val current = _currentUser.value
            val resolvedRole = UserRole.fromServerRole(meDto.role)
            val updatedUser = AuthenticatedUser(
                id = meDto.actorId.ifBlank { current?.id ?: meDto.username },
                fullName = meDto.fullName.takeIf { it.isNotBlank() }
                    ?: current?.fullName
                    ?: meDto.username,
                email = meDto.email.takeIf { it.isNotBlank() }
                    ?: current?.email
                    ?: meDto.username,
                phoneNumber = meDto.phoneNumber.takeIf { it.isNotBlank() }
                    ?: current?.phoneNumber
                    ?: "",
                role = resolvedRole,
                isLocalDemoAccount = false,
                customerId = meDto.customerId ?: current?.customerId,
                authorizedBranchIds = meDto.authorizedBranchIds ?: current?.authorizedBranchIds,
                hasActiveRemoteJwtSession = true
            )
            setActiveSession(updatedUser)
            meDto
        } catch (httpEx: HttpException) {
            if (httpEx.code() == 401 || httpEx.code() == 403) {
                handleRemoteSessionInvalidated()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Explicitly rotates the current JWT session via `POST /api/v1/auth/refresh/` and persists
     * both the new `access_token` and rotated `refresh_token` in [SecureTokenStorage].
     */
    fun refreshRemoteSession(): Boolean {
        val service = apiService ?: return false
        val currentTokens = secureTokenStorage.readTokens() ?: return false
        if (currentTokens.isRefreshTokenExpired(nowEpochSecondsProvider() * 1000L)) {
            handleRemoteSessionInvalidated()
            return false
        }
        return try {
            val response = runBlocking {
                service.refreshToken(AuthRefreshRequestDto(refreshToken = currentTokens.refreshToken))
            }
            val updatedTokens = response.toJwtSessionTokens(fallbackPrevious = currentTokens)
            secureTokenStorage.saveTokens(updatedTokens)
            true
        } catch (httpEx: HttpException) {
            if (httpEx.code() == 401 || httpEx.code() == 403) {
                handleRemoteSessionInvalidated()
            }
            false
        } catch (_: IOException) {
            // Preserve local session on transient network failure
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Explicitly enters a local development/demo session for testing role-based workflows
     * ([UserRole.CUSTOMER], [UserRole.STORE_OWNER], or [UserRole.STORE_ADMIN]).
     *
     * Security rule: Demo sessions are strictly restricted to `BuildConfig.DEBUG` (`isDebugBuild = true`).
     * In release/production builds (`isDebugBuild = false`), demo login is rejected immediately.
     */
    fun signInWithDemoRole(role: UserRole): AuthOperationResult {
        if (!isDebugBuild) {
            return AuthOperationResult.Error(
                "حسابات التجربة المحلية متاحة فقط في بيئة التطوير (Debug) وغير مسموح بها في نسخ الإنتاج."
            )
        }

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

        secureTokenStorage.clearTokens()
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
     * Logs out the current user:
     * 1. Invokes `POST /api/v1/auth/logout/` with the active refresh token when connected.
     * 2. Always wipes encrypted tokens from [SecureTokenStorage] and clears the active session
     *    even if the backend network call fails.
     */
    fun logout() {
        val storedTokens = secureTokenStorage.readTokens()
        val service = apiService
        if (service != null && storedTokens != null) {
            runCatching {
                runBlocking {
                    service.logout(AuthLogoutRequestDto(refreshToken = storedTokens.refreshToken))
                }
            }
        }
        secureTokenStorage.clearTokens()
        clearActiveSessionState()
    }

    /**
     * Called when the remote JWT session is terminally invalidated (e.g., refresh token expired,
     * revoked, or replay-detected with HTTP 401/403).
     */
    fun handleRemoteSessionInvalidated() {
        secureTokenStorage.clearTokens()
        clearActiveSessionState()
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

    private fun completeRemoteAuthentication(
        tokenResponse: AuthTokenResponseDto,
        fallbackEmail: String,
        fallbackFullName: String,
        fallbackPhone: String,
        rawPasswordForLocalBackup: String,
        enforceCustomerRoleOnly: Boolean
    ): AuthOperationResult {
        val tokens = tokenResponse.toJwtSessionTokens()
        if (!secureTokenStorage.saveTokens(tokens)) {
            return AuthOperationResult.Error("تعذر تأمين رمز الجلسة المشفر على الجهاز.")
        }

        val resolvedRole = if (enforceCustomerRoleOnly) {
            UserRole.CUSTOMER
        } else {
            UserRole.fromServerRole(tokenResponse.role)
        }

        val resolvedEmail = (tokenResponse.email.takeIf { it.isNotBlank() } ?: fallbackEmail)
            .trim()
            .lowercase()
        val resolvedName = (tokenResponse.fullName.takeIf { it.isNotBlank() } ?: fallbackFullName)
            .trim()
        val resolvedPhone = (tokenResponse.phoneNumber.takeIf { it.isNotBlank() } ?: fallbackPhone)
            .trim()
            .replace(" ", "")
        val resolvedId = tokenResponse.actorId.takeIf { it.isNotBlank() }
            ?: tokenResponse.customerId?.takeIf { it.isNotBlank() }
            ?: "user_${tokenResponse.username.ifBlank { UUID.randomUUID().toString().take(8) }}"

        // Preserve a salted SHA-256 local account record non-destructively for offline continuity
        if (resolvedRole == UserRole.CUSTOMER && rawPasswordForLocalBackup.isNotEmpty()) {
            val existing = accountsByEmail[resolvedEmail]
            val salt = existing?.salt ?: UUID.randomUUID().toString()
            val passwordHash = PasswordHasher.hash(rawPasswordForLocalBackup, salt)
            val record = StoredAccountRecord(
                id = resolvedId,
                fullName = resolvedName,
                email = resolvedEmail,
                phoneNumber = resolvedPhone,
                role = UserRole.CUSTOMER,
                salt = salt,
                passwordHash = passwordHash,
                isLocalDemoAccount = false
            )
            accountsByEmail[resolvedEmail] = record
            persistAccountRecord(record)
        }

        val authenticatedUser = AuthenticatedUser(
            id = resolvedId,
            fullName = resolvedName,
            email = resolvedEmail,
            phoneNumber = resolvedPhone,
            role = resolvedRole,
            isLocalDemoAccount = false,
            customerId = tokenResponse.customerId,
            authorizedBranchIds = tokenResponse.authorizedBranchIds,
            hasActiveRemoteJwtSession = true
        )
        setActiveSession(authenticatedUser)

        val destination = AccessControlPolicy.resolvePostLoginDestination(authenticatedUser)
        return AuthOperationResult.Success(authenticatedUser, destination)
    }

    private fun parseServerAuthErrorArabic(httpEx: HttpException, isRegistration: Boolean): String {
        val code = httpEx.code()
        if (code == 429) {
            return "تم تجاوز الحد المسموح لمحاولات المصادقة. يرجى الانتظار قليلاً ثم المحاولة مرة أخرى."
        }
        val errorBody = runCatching { httpEx.response()?.errorBody()?.string().orEmpty() }.getOrDefault("")
        if (errorBody.isNotBlank()) {
            val extracted = extractErrorMessageFromJson(errorBody)
            if (!extracted.isNullOrBlank()) {
                return translateServerErrorToArabic(extracted, code, isRegistration)
            }
        }
        return when (code) {
            400 -> if (isRegistration) {
                "بيانات التسجيل غير صالحة أو مسجلة مسبقاً. يرجى مراجعة الحقول والمحاولة مرة أخرى."
            } else {
                "يرجى التحقق من البريد الإلكتروني وكلمة المرور."
            }
            401 -> "بيانات الدخول غير صحيحة. يرجى التحقق من البريد الإلكتروني وكلمة المرور."
            403 -> "غير مصرح بتنفيذ هذه العملية."
            else -> "حدث خطأ في خادم المصادقة ($code). يرجى المحاولة لاحقاً."
        }
    }

    private fun extractErrorMessageFromJson(rawJson: String): String? {
        return runCatching {
            val obj = FreshRetrofitClient.networkJson.parseToJsonElement(rawJson).jsonObject
            obj["detail"]?.let { detailEl ->
                if (detailEl is JsonPrimitive) {
                    return@runCatching detailEl.content
                }
            }
            for ((field, value) in obj.entries) {
                when {
                    value is JsonPrimitive -> return@runCatching "$field: ${value.content}"
                    value is JsonObject -> return@runCatching "$field: $value"
                    else -> {
                        val arr = runCatching { value.jsonArray }.getOrNull()
                        if (!arr.isNullOrEmpty()) {
                            val firstMsg = runCatching { arr.first().jsonPrimitive.content }.getOrNull()
                            if (!firstMsg.isNullOrBlank()) {
                                return@runCatching "$field: $firstMsg"
                            }
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun translateServerErrorToArabic(
        serverMessage: String,
        httpCode: Int,
        isRegistration: Boolean
    ): String {
        val lower = serverMessage.lowercase()
        return when {
            lower.contains("email") && lower.contains("already registered") ->
                "البريد الإلكتروني مسجل مسبقاً. يرجى تسجيل الدخول أو استخدام بريد آخر."
            lower.contains("username") && lower.contains("already registered") ->
                "اسم المستخدم أو البريد الإلكتروني مسجل مسبقاً."
            lower.contains("phone") && lower.contains("already registered") ->
                "رقم الجوال مسجل مسبقاً لحساب آخر."
            lower.contains("role") || lower.contains("privilege") || lower.contains("forbidden") ->
                "غير مصرح بتمرير حقول الصلاحيات أو الأدوار أثناء تسجيل حساب العميل."
            lower.contains("confirm_password") || lower.contains("do not match") ->
                "كلمتا المرور غير متطابقتين."
            lower.contains("password") ->
                "كلمة المرور ضعيفة أو لا تستوفي شروط الأمان (٨ أحرف على الأقل تشمل أحرفاً وأرقاماً)."
            lower.contains("too many") || httpCode == 429 ->
                "تم تجاوز الحد المسموح لمحاولات المصادقة. يرجى الانتظار قليلاً ثم المحاولة مرة أخرى."
            httpCode == 401 ->
                "بيانات الدخول غير صحيحة. يرجى التحقق من البريد الإلكتروني وكلمة المرور."
            isRegistration ->
                "تعذر إكمال التسجيل: $serverMessage"
            else ->
                "تعذر تسجيل الدخول: $serverMessage"
        }
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
            isLocalDemoAccount = isLocalDemoAccount,
            hasActiveRemoteJwtSession = false
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
            ?.putBoolean(KEY_ACTIVE_USER_HAS_REMOTE_JWT, user.hasActiveRemoteJwtSession)
            ?.putString(KEY_ACTIVE_USER_CUSTOMER_ID, user.customerId)
            ?.putString(
                KEY_ACTIVE_USER_BRANCH_IDS,
                user.authorizedBranchIds?.joinToString(",")
            )
            ?.apply()
    }

    private fun clearActiveSessionState() {
        _currentUser.value = null
        sharedPreferences?.edit()
            ?.remove(KEY_ACTIVE_USER_ID)
            ?.remove(KEY_ACTIVE_USER_NAME)
            ?.remove(KEY_ACTIVE_USER_EMAIL)
            ?.remove(KEY_ACTIVE_USER_PHONE)
            ?.remove(KEY_ACTIVE_USER_ROLE)
            ?.remove(KEY_ACTIVE_USER_DEMO)
            ?.remove(KEY_ACTIVE_USER_HAS_REMOTE_JWT)
            ?.remove(KEY_ACTIVE_USER_CUSTOMER_ID)
            ?.remove(KEY_ACTIVE_USER_BRANCH_IDS)
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
        val hasRemoteJwt = prefs.getBoolean(KEY_ACTIVE_USER_HAS_REMOTE_JWT, false)

        // Never restore a demo session in non-debug builds
        if (isDemo && !isDebugBuild) {
            clearActiveSessionState()
            return
        }

        // If the session was backed by remote JWT tokens, verify that the encrypted token bundle
        // is still readable and that the refresh token has not expired.
        if (hasRemoteJwt) {
            val tokens = secureTokenStorage.readTokens()
            if (tokens == null || tokens.isRefreshTokenExpired(nowEpochSecondsProvider() * 1000L)) {
                secureTokenStorage.clearTokens()
                clearActiveSessionState()
                return
            }
        }

        val customerId = prefs.getString(KEY_ACTIVE_USER_CUSTOMER_ID, null)
        val branchIds = prefs.getString(KEY_ACTIVE_USER_BRANCH_IDS, null)
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }

        val role = runCatching { UserRole.valueOf(roleName) }.getOrDefault(UserRole.CUSTOMER)
        _currentUser.value = AuthenticatedUser(
            id = id,
            fullName = name,
            email = email,
            phoneNumber = phone,
            role = role,
            isLocalDemoAccount = isDemo,
            customerId = customerId,
            authorizedBranchIds = branchIds,
            hasActiveRemoteJwtSession = hasRemoteJwt
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
        const val LEGACY_DI_PREFS_NAME = "fresh_local_auth_prefs"
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
        private const val KEY_ACTIVE_USER_HAS_REMOTE_JWT = "active_user_has_remote_jwt"
        private const val KEY_ACTIVE_USER_CUSTOMER_ID = "active_user_customer_id"
        private const val KEY_ACTIVE_USER_BRANCH_IDS = "active_user_branch_ids"
        private const val KEY_PREF_ORDER_NOTIFICATIONS = "pref_order_notifications"
        private const val KEY_PREF_PROMO_NOTIFICATIONS = "pref_promo_notifications"

        @Volatile
        private var instance: LocalAuthRepository? = null

        fun getInstance(
            context: Context,
            apiService: FreshApiService? = null
        ): LocalAuthRepository {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val canonicalPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val legacyPrefs = appContext.getSharedPreferences(LEGACY_DI_PREFS_NAME, Context.MODE_PRIVATE)
                    migrateLegacyPrefsIfNeeded(canonicalPrefs, legacyPrefs)
                    val tokenStorage = SecureTokenStorage(canonicalPrefs)
                    LocalAuthRepository(
                        sharedPreferences = canonicalPrefs,
                        secureTokenStorage = tokenStorage,
                        apiService = apiService
                    ).also { instance = it }
                }
            }
        }

        /**
         * Copies any accounts, active session, or settings stored in [legacyPrefs]
         * (`"fresh_local_auth_prefs"`) into [canonicalPrefs] (`"fresh_local_auth_and_settings"`)
         * without overwriting existing entries in [canonicalPrefs].
         */
        fun migrateLegacyPrefsIfNeeded(
            canonicalPrefs: SharedPreferences,
            legacyPrefs: SharedPreferences
        ) {
            val legacyAll = legacyPrefs.all
            if (legacyAll.isEmpty()) return

            val editor = canonicalPrefs.edit()
            val canonicalEmails = canonicalPrefs.getStringSet(KEY_REGISTERED_EMAILS, emptySet())
                ?.toMutableSet() ?: mutableSetOf()
            val legacyEmails = legacyPrefs.getStringSet(KEY_REGISTERED_EMAILS, emptySet()).orEmpty()

            if (legacyEmails.isNotEmpty()) {
                canonicalEmails.addAll(legacyEmails)
                editor.putStringSet(KEY_REGISTERED_EMAILS, canonicalEmails)
            }

            for ((key, value) in legacyAll) {
                if (key == KEY_REGISTERED_EMAILS || canonicalPrefs.contains(key)) continue
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            editor.commit()
        }

        internal fun resetInstanceForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}

