package com.example

import android.content.SharedPreferences
import com.example.data.api.FreshApiService
import com.example.data.api.FreshAuthInterceptor
import com.example.data.api.FreshRetrofitClient
import com.example.data.api.model.AuthMeResponseDto
import com.example.data.api.model.AuthRefreshRequestDto
import com.example.data.auth.AuthOperationResult
import com.example.data.auth.JvmAesGcmCipher
import com.example.data.auth.JwtSessionTokens
import com.example.data.auth.LocalAuthRepository
import com.example.data.auth.PostLoginDestination
import com.example.data.auth.SecureTokenStorage
import com.example.data.auth.UserRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MicroTask1CAuthAndJwtIntegrationTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(data) { data.toMutableMap() }
        override fun getString(key: String?, defValue: String?): String? =
            synchronized(data) { (data[key] as? String) ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            synchronized(data) { (data[key] as? Set<String>)?.toMutableSet() ?: defValues }

        override fun getInt(key: String?, defValue: Int): Int =
            synchronized(data) { (data[key] as? Int) ?: defValue }

        override fun getLong(key: String?, defValue: Long): Long =
            synchronized(data) { (data[key] as? Long) ?: defValue }

        override fun getFloat(key: String?, defValue: Float): Float =
            synchronized(data) { (data[key] as? Float) ?: defValue }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            synchronized(data) { (data[key] as? Boolean) ?: defValue }

        override fun contains(key: String?): Boolean =
            synchronized(data) { data.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = values?.toSet()
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) removals.add(key)
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearAll = true
            }

            override fun commit(): Boolean {
                synchronized(data) {
                    if (clearAll) data.clear()
                    for (k in removals) data.remove(k)
                    for ((k, v) in staged) {
                        if (v == null) data.remove(k) else data[k] = v
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    private var serverProcess: Process? = null
    private var baseUrl: String = ""

    @Before
    fun setUp() {
        LocalAuthRepository.resetInstanceForTesting()

        val repoRoot = findRepoRoot()
        val serverScript = File(repoRoot, "backend/apps/orders/test_http_server.py")
        assertTrue(
            "Expected backend/apps/orders/test_http_server.py to exist at ${serverScript.absolutePath}",
            serverScript.exists()
        )

        val processBuilder = ProcessBuilder("python3", "-m", "apps.orders.test_http_server")
        processBuilder.directory(repoRoot)
        processBuilder.environment()["PYTHONPATH"] = File(repoRoot, "backend").absolutePath
        val proc = processBuilder.start()
        serverProcess = proc

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val portLine = reader.readLine()
            ?: throw IllegalStateException(
                "Failed to read bound port from test_http_server.py: " +
                    proc.errorStream.bufferedReader().readText()
            )
        val port = portLine.removePrefix("READY").trim().toInt()
        baseUrl = "http://127.0.0.1:$port/api/v1/"
    }

    @After
    fun tearDown() {
        serverProcess?.let { proc ->
            proc.destroy()
            proc.waitFor(3, TimeUnit.SECONDS)
            if (proc.isAlive) {
                proc.destroyForcibly()
            }
        }
        serverProcess = null
        LocalAuthRepository.resetInstanceForTesting()
    }

    private fun findRepoRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            if (File(current, "backend/apps/orders/test_http_server.py").exists()) {
                return current
            }
            current = current.parentFile
        }
        return File("/app/applet")
    }

    private fun createRepoWithRealServer(
        prefs: SharedPreferences = InMemorySharedPreferences(),
        allowOfflineLocalFallback: Boolean = false,
        isDebugBuild: Boolean = true
    ): Triple<LocalAuthRepository, FreshApiService, FreshAuthInterceptor> {
        val tokenStorage = SecureTokenStorage(
            encryptedPrefs = prefs,
            cipher = JvmAesGcmCipher()
        )
        var repoRef: LocalAuthRepository? = null
        val (service, interceptor) = FreshRetrofitClient.createAuthenticatedService(
            tokenStorage = tokenStorage,
            baseUrl = baseUrl,
            onSessionInvalidated = {
                repoRef?.handleRemoteSessionInvalidated()
            }
        )
        val repo = LocalAuthRepository(
            sharedPreferences = prefs,
            secureTokenStorage = tokenStorage,
            apiService = service,
            allowOfflineLocalFallback = allowOfflineLocalFallback,
            isDebugBuild = isDebugBuild
        )
        repoRef = repo
        return Triple(repo, service, interceptor)
    }

    @Test
    fun test_01_customer_registration_succeeds_and_encrypts_jwt_tokens_in_keystore_storage() {
        val prefs = InMemorySharedPreferences()
        val (repo, _, _) = createRepoWithRealServer(prefs = prefs)

        val rawPassword = "FreshCustomer99"
        val result = repo.registerCustomer(
            fullName = "سارة العتيبي",
            email = "sara.otaibi@fresh.sa",
            phoneNumber = "0551234567",
            password = rawPassword,
            confirmPassword = rawPassword
        )

        assertTrue("Expected registration to succeed, got $result", result is AuthOperationResult.Success)
        val success = result as AuthOperationResult.Success
        assertEquals(UserRole.CUSTOMER, success.user.role)
        assertEquals(PostLoginDestination.CUSTOMER_HOME, success.destination)
        assertTrue(success.user.hasActiveRemoteJwtSession)
        assertFalse(success.user.isLocalDemoAccount)
        assertNotNull(success.user.customerId)
        assertTrue(success.user.customerId!!.startsWith("cust-"))

        // Verify tokens are stored encrypted with AES-GCM envelope and never in plaintext
        assertTrue(repo.secureTokenStorage.isStoredValueEncrypted())
        val rawStoredCiphertext = repo.secureTokenStorage.getRawStoredValueForTesting()
        assertNotNull(rawStoredCiphertext)
        assertTrue(rawStoredCiphertext!!.startsWith(SecureTokenStorage.ENCRYPTED_VALUE_PREFIX))

        val decryptedTokens = repo.secureTokenStorage.readTokens()
        assertNotNull(decryptedTokens)
        assertTrue(decryptedTokens!!.accessToken.isNotBlank())
        assertTrue(decryptedTokens.refreshToken.isNotBlank())
        assertEquals("CUSTOMER", decryptedTokens.role)

        // Ensure neither the raw access token, refresh token, nor password appears anywhere in SharedPreferences
        val allPrefsDump = prefs.all.entries.joinToString(" ") { "${it.key}=${it.value}" }
        assertFalse(allPrefsDump.contains(decryptedTokens.accessToken))
        assertFalse(allPrefsDump.contains(decryptedTokens.refreshToken))
        assertFalse(allPrefsDump.contains(rawPassword))

        // Verify DTO toString() redacts tokens so accidental logging cannot leak JWTs
        val tokenStr = decryptedTokens.toString()
        assertFalse(tokenStr.contains(decryptedTokens.accessToken))
        assertFalse(tokenStr.contains(decryptedTokens.refreshToken))
        assertTrue(tokenStr.contains("[REDACTED]"))
    }

    @Test
    fun test_02_login_succeeds_and_resolves_role_from_server_response() {
        val (repo, _, _) = createRepoWithRealServer()

        // 1. Register a customer and log out
        val regResult = repo.registerCustomer(
            fullName = "عبدالله الحربي",
            email = "abdullah.harbi@fresh.sa",
            phoneNumber = "0509988776",
            password = "CustomerPass88",
            confirmPassword = "CustomerPass88"
        )
        assertTrue(regResult is AuthOperationResult.Success)
        repo.logout()
        assertNull(repo.currentUser.value)

        // 2. Log back in as CUSTOMER -> routes to CUSTOMER_HOME
        val customerLogin = repo.loginWithCredentials("abdullah.harbi@fresh.sa", "CustomerPass88")
        assertTrue(customerLogin is AuthOperationResult.Success)
        val customerSuccess = customerLogin as AuthOperationResult.Success
        assertEquals(UserRole.CUSTOMER, customerSuccess.user.role)
        assertEquals(PostLoginDestination.CUSTOMER_HOME, customerSuccess.destination)
        assertTrue(customerSuccess.user.hasActiveRemoteJwtSession)

        // 3. Log in as server-provisioned STORE_OWNER (seeded in test_http_server.py) -> routes to STORE_MANAGEMENT
        val ownerLogin = repo.loginWithCredentials("owner@fresh.sa", "OwnerPass123")
        assertTrue("Expected owner login to succeed, got $ownerLogin", ownerLogin is AuthOperationResult.Success)
        val ownerSuccess = ownerLogin as AuthOperationResult.Success
        assertEquals(UserRole.STORE_OWNER, ownerSuccess.user.role)
        assertEquals(PostLoginDestination.STORE_MANAGEMENT, ownerSuccess.destination)
        assertTrue(ownerSuccess.user.hasActiveRemoteJwtSession)

        // 4. Log in as server-provisioned BRANCH_MANAGER (seeded in test_http_server.py) -> routes to STORE_MANAGEMENT
        val managerLogin = repo.loginWithCredentials("manager@fresh.sa", "ManagerPass123")
        assertTrue("Expected manager login to succeed, got $managerLogin", managerLogin is AuthOperationResult.Success)
        val managerSuccess = managerLogin as AuthOperationResult.Success
        assertEquals(UserRole.STORE_ADMIN, managerSuccess.user.role)
        assertEquals(PostLoginDestination.STORE_MANAGEMENT, managerSuccess.destination)
        assertEquals(listOf(1), managerSuccess.user.authorizedBranchIds)
    }

    @Test
    fun test_03_authorization_bearer_header_sent_only_to_trusted_backend_and_protected_endpoints() {
        val prefs = InMemorySharedPreferences()
        val tokenStorage = SecureTokenStorage(prefs, JvmAesGcmCipher())
        val nowMs = System.currentTimeMillis()
        tokenStorage.saveTokens(
            JwtSessionTokens(
                accessToken = "secret_access_jwt_token_value",
                refreshToken = "secret_refresh_jwt_token_value",
                expiresAt = "2030-01-01T00:00:00Z",
                refreshExpiresAt = "2030-01-07T00:00:00Z",
                expiresAtEpochMillis = nowMs + 900_000L,
                refreshExpiresAtEpochMillis = nowMs + 86_400_000L,
                actorId = "customer:cust-101",
                username = "cust101@fresh.sa",
                customerId = "cust-101",
                role = "CUSTOMER"
            )
        )

        val capturedHeadersByUrl = mutableMapOf<String, String?>()
        val authInterceptor = FreshAuthInterceptor(
            tokenStorage = tokenStorage,
            baseUrl = "https://api.fresh.sa/api/v1/"
        )
        val terminalInterceptor = Interceptor { chain ->
            val req = chain.request()
            capturedHeadersByUrl[req.url.toString()] = req.header("Authorization")
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(terminalInterceptor)
            .build()

        // 1. Protected endpoint on trusted host -> MUST include Authorization: Bearer
        client.newCall(Request.Builder().url("https://api.fresh.sa/api/v1/auth/me/").build()).execute().close()
        assertEquals(
            "Bearer secret_access_jwt_token_value",
            capturedHeadersByUrl["https://api.fresh.sa/api/v1/auth/me/"]
        )

        // 2. Public login/register/refresh endpoints on trusted host -> MUST NOT include Authorization header
        client.newCall(Request.Builder().url("https://api.fresh.sa/api/v1/auth/login/").build()).execute().close()
        client.newCall(Request.Builder().url("https://api.fresh.sa/api/v1/auth/register/").build()).execute().close()
        client.newCall(Request.Builder().url("https://api.fresh.sa/api/v1/auth/refresh/").build()).execute().close()
        assertNull(capturedHeadersByUrl["https://api.fresh.sa/api/v1/auth/login/"])
        assertNull(capturedHeadersByUrl["https://api.fresh.sa/api/v1/auth/register/"])
        assertNull(capturedHeadersByUrl["https://api.fresh.sa/api/v1/auth/refresh/"])

        // 3. Untrusted external host -> MUST NEVER leak Authorization header
        client.newCall(Request.Builder().url("https://evil-analytics.example.com/api/v1/auth/me/").build()).execute().close()
        assertNull(capturedHeadersByUrl["https://evil-analytics.example.com/api/v1/auth/me/"])
    }

    @Test
    fun test_04_automatic_token_refresh_on_401_persists_new_access_and_rotated_refresh_tokens() = runBlocking {
        val (repo, service, interceptor) = createRepoWithRealServer()

        val regResult = repo.registerCustomer(
            fullName = "فهد الدوسري",
            email = "fahad.dosari@fresh.sa",
            phoneNumber = "0561122334",
            password = "SecurePass123",
            confirmPassword = "SecurePass123"
        )
        assertTrue(regResult is AuthOperationResult.Success)

        val initialTokens = repo.secureTokenStorage.readTokens()!!
        // Corrupt/expire the stored access token while keeping the valid refresh token
        repo.secureTokenStorage.saveTokens(
            initialTokens.copy(
                accessToken = "expired_or_invalid_access_token",
                expiresAtEpochMillis = System.currentTimeMillis() - 120_000L
            )
        )

        // Calling getCurrentUserMe() should transparently refresh via POST /api/v1/auth/refresh/ and succeed
        val meResponse = service.getCurrentUserMe()
        assertEquals("fahad.dosari@fresh.sa", meResponse.email)
        assertEquals("CUSTOMER", meResponse.role)
        assertEquals(1, interceptor.totalRefreshCallsExecuted)

        val rotatedTokens = repo.secureTokenStorage.readTokens()!!
        assertNotEquals("expired_or_invalid_access_token", rotatedTokens.accessToken)
        assertNotEquals(initialTokens.accessToken, rotatedTokens.accessToken)
        assertNotEquals(initialTokens.refreshToken, rotatedTokens.refreshToken)
    }

    @Test
    fun test_05_concurrent_401_requests_trigger_only_one_single_flight_refresh_call() = runBlocking {
        val (repo, service, interceptor) = createRepoWithRealServer()

        val regResult = repo.registerCustomer(
            fullName = "نورة القحطاني",
            email = "noura.qahtani@fresh.sa",
            phoneNumber = "0543322110",
            password = "ConcurrentPass123",
            confirmPassword = "ConcurrentPass123"
        )
        assertTrue(regResult is AuthOperationResult.Success)

        val initialTokens = repo.secureTokenStorage.readTokens()!!
        // Keep expiresAtEpochMillis in the future so all 6 requests hit 401 concurrently and enter refreshTokensSingleFlight
        repo.secureTokenStorage.saveTokens(
            initialTokens.copy(
                accessToken = "stale_access_token_triggering_401",
                expiresAtEpochMillis = System.currentTimeMillis() + 600_000L
            )
        )

        val deferredResults = (1..6).map {
            async(Dispatchers.IO) {
                service.getCurrentUserMe()
            }
        }
        val responses: List<AuthMeResponseDto> = deferredResults.awaitAll()

        assertEquals(6, responses.size)
        responses.forEach { dto ->
            assertEquals("noura.qahtani@fresh.sa", dto.email)
            assertEquals("CUSTOMER", dto.role)
        }
        // Single-flight synchronization MUST ensure only 1 refresh request was sent!
        assertEquals(1, interceptor.totalRefreshCallsExecuted)

        val finalTokens = repo.secureTokenStorage.readTokens()!!
        assertNotEquals("stale_access_token_triggering_401", finalTokens.accessToken)
        assertNotEquals(initialTokens.refreshToken, finalTokens.refreshToken)
    }

    @Test
    fun test_06_terminal_refresh_failure_clears_tokens_while_network_io_error_preserves_local_session() {
        val prefs = InMemorySharedPreferences()
        val tokenStorage = SecureTokenStorage(prefs, JvmAesGcmCipher())
        val simulateNetworkIoFailure = AtomicBoolean(true)
        val simulateServer5xxFailure = AtomicBoolean(false)
        val refreshAttempts = AtomicInteger(0)
        var repoRef: LocalAuthRepository? = null

        val authInterceptor = FreshAuthInterceptor(
            tokenStorage = tokenStorage,
            baseUrl = "https://api.fresh.sa/api/v1/",
            onSessionInvalidated = {
                repoRef?.handleRemoteSessionInvalidated()
            }
        )

        val mockBackendInterceptor = Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            if (path.endsWith("/auth/refresh/")) {
                refreshAttempts.incrementAndGet()
                if (simulateNetworkIoFailure.get()) {
                    throw IOException("Simulated transient offline network outage during token refresh")
                }
                if (simulateServer5xxFailure.get()) {
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("""{"detail":"Temporary upstream maintenance."}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                return@Interceptor Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("""{"detail":"Refresh token has been revoked or expired."}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            // Protected endpoint returns 401 to trigger refresh
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("""{"detail":"Access token expired."}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(mockBackendInterceptor)
            .build()
        val service = FreshRetrofitClient.createService("https://api.fresh.sa/api/v1/", client)
        val repo = LocalAuthRepository(
            sharedPreferences = prefs,
            secureTokenStorage = tokenStorage,
            apiService = service
        )
        repoRef = repo

        // Seed an active authenticated session + valid refresh token
        val nowMs = System.currentTimeMillis()
        // Also register a local user session state
        val localOnlyRepo = LocalAuthRepository(sharedPreferences = prefs, secureTokenStorage = tokenStorage, apiService = null)
        localOnlyRepo.registerCustomer(
            fullName = "خالد الشهري",
            email = "khalid@fresh.sa",
            phoneNumber = "0531122334",
            password = "KhalidPass123",
            confirmPassword = "KhalidPass123"
        )
        localOnlyRepo.loginWithCredentials("khalid@fresh.sa", "KhalidPass123")
        // Re-create repo so it restores the active session
        val activeRepo = LocalAuthRepository(
            sharedPreferences = prefs,
            secureTokenStorage = tokenStorage,
            apiService = service
        )
        repoRef = activeRepo

        tokenStorage.saveTokens(
            JwtSessionTokens(
                accessToken = "expired_access_1",
                refreshToken = "valid_refresh_1",
                expiresAt = "2030-01-01T00:00:00Z",
                refreshExpiresAt = "2030-01-07T00:00:00Z",
                expiresAtEpochMillis = nowMs + 300_000L,
                refreshExpiresAtEpochMillis = nowMs + 86_400_000L,
                actorId = "customer:cust-500",
                username = "khalid@fresh.sa",
                customerId = "cust-500",
                role = "CUSTOMER"
            )
        )

        // Part A: Transient IOException during refresh MUST NOT wipe tokens or active session
        runCatching { runBlocking { service.getCurrentUserMe() } }
        assertEquals(1, refreshAttempts.get())
        assertNotNull("Tokens must be preserved on transient IOException", tokenStorage.readTokens())
        assertNotNull("Local user session must be preserved on transient IOException", activeRepo.currentUser.value)

        // Part B: Temporary 503 Server Error during refresh MUST NOT wipe tokens or active session
        simulateNetworkIoFailure.set(false)
        simulateServer5xxFailure.set(true)
        runCatching { runBlocking { service.getCurrentUserMe() } }
        assertEquals(2, refreshAttempts.get())
        assertNotNull("Tokens must be preserved on temporary 5xx server failure", tokenStorage.readTokens())
        assertNotNull("Local user session must be preserved on temporary 5xx server failure", activeRepo.currentUser.value)

        // Part C: Terminal 401 from /auth/refresh/ MUST wipe tokens and invalidate session
        simulateServer5xxFailure.set(false)
        runCatching { runBlocking { service.getCurrentUserMe() } }
        assertEquals(3, refreshAttempts.get())
        assertNull("Tokens must be wiped on 401 from /auth/refresh/", tokenStorage.readTokens())
        assertNull("Active session must be cleared on terminal refresh failure", activeRepo.currentUser.value)
    }

    @Test
    fun test_07_logout_revokes_server_session_and_clears_local_encrypted_tokens() = runBlocking {
        val (repo, service, _) = createRepoWithRealServer()

        val regResult = repo.registerCustomer(
            fullName = "ريم الشمري",
            email = "reem.shammari@fresh.sa",
            phoneNumber = "0581122334",
            password = "ReemSecure123",
            confirmPassword = "ReemSecure123"
        )
        assertTrue(regResult is AuthOperationResult.Success)

        val tokensBeforeLogout = repo.secureTokenStorage.readTokens()
        assertNotNull(tokensBeforeLogout)

        // Perform logout -> revokes on server and wipes local encrypted tokens
        repo.logout()
        assertNull(repo.currentUser.value)
        assertNull(repo.secureTokenStorage.readTokens())

        // Verify that attempting to use the old refresh token directly against the server is rejected with 401
        try {
            service.refreshToken(AuthRefreshRequestDto(refreshToken = tokensBeforeLogout!!.refreshToken))
            fail("Expected refresh with logged-out token to throw HttpException 401")
        } catch (httpEx: HttpException) {
            assertEquals(401, httpEx.code())
        }
    }

    @Test
    fun test_08_unencrypted_or_tampered_tokens_in_shared_preferences_are_rejected_and_wiped() {
        val prefs = InMemorySharedPreferences()
        val storage = SecureTokenStorage(prefs, JvmAesGcmCipher())

        // 1. Plaintext JSON in SharedPreferences must be rejected and wiped
        prefs.edit()
            .putString(
                SecureTokenStorage.KEY_ENCRYPTED_TOKEN_PAYLOAD,
                """{"accessToken":"plain_access","refreshToken":"plain_refresh"}"""
            )
            .commit()
        assertFalse(storage.isStoredValueEncrypted())
        assertNull(storage.readTokens())
        assertNull(storage.getRawStoredValueForTesting())

        // 2. Raw Base64 (unencrypted) payload must be rejected and wiped
        val base64Unencrypted = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"accessToken":"b64_access","refreshToken":"b64_refresh"}""".toByteArray()
        )
        prefs.edit()
            .putString(SecureTokenStorage.KEY_ENCRYPTED_TOKEN_PAYLOAD, base64Unencrypted)
            .commit()
        assertFalse(storage.isStoredValueEncrypted())
        assertNull(storage.readTokens())
        assertNull(storage.getRawStoredValueForTesting())

        // 3. Valid AES-GCM ciphertext that is then tampered with (last hex byte flipped) must fail GCM auth tag verification and be wiped
        val nowMs = System.currentTimeMillis()
        storage.saveTokens(
            JwtSessionTokens(
                accessToken = "valid_access",
                refreshToken = "valid_refresh",
                expiresAt = "2030-01-01T00:00:00Z",
                refreshExpiresAt = "2030-01-07T00:00:00Z",
                expiresAtEpochMillis = nowMs + 900_000L,
                refreshExpiresAtEpochMillis = nowMs + 86_400_000L,
                actorId = "customer:cust-9",
                username = "cust9@fresh.sa",
                customerId = "cust-9",
                role = "CUSTOMER"
            )
        )
        val validEnvelope = storage.getRawStoredValueForTesting()!!
        val tamperedEnvelope = validEnvelope.dropLast(2) + (if (validEnvelope.endsWith("0a")) "0b" else "0a")
        prefs.edit()
            .putString(SecureTokenStorage.KEY_ENCRYPTED_TOKEN_PAYLOAD, tamperedEnvelope)
            .commit()

        assertNull("GCM authentication tag mismatch must reject tampered ciphertext", storage.readTokens())
        assertNull("Tampered ciphertext must be removed from SharedPreferences", storage.getRawStoredValueForTesting())
    }

    @Test
    fun test_09_demo_accounts_blocked_when_is_debug_build_is_false_and_existing_local_accounts_preserved() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().clear().commit()

        // 1. Pre-existing local account registered before remote auth connection
        val legacyLocalRepo = LocalAuthRepository(
            sharedPreferences = prefs,
            apiService = null,
            isDebugBuild = true
        )
        val localReg = legacyLocalRepo.registerCustomer(
            fullName = "عميل محلي سابق",
            email = "existing.local@fresh.sa",
            phoneNumber = "0501239876",
            password = "LocalUserPass11",
            confirmPassword = "LocalUserPass11"
        )
        assertTrue(localReg is AuthOperationResult.Success)

        // Activate a demo owner session in debug mode
        val debugDemo = legacyLocalRepo.signInWithDemoRole(UserRole.STORE_OWNER)
        assertTrue(debugDemo is AuthOperationResult.Success)
        assertEquals(UserRole.STORE_OWNER, legacyLocalRepo.currentUser.value?.role)

        // 2. Re-initialize repository with isDebugBuild = false (simulating a production/release build)
        val releaseRepo = LocalAuthRepository(
            sharedPreferences = prefs,
            apiService = null,
            allowOfflineLocalFallback = true,
            isDebugBuild = false
        )
        // Stored demo session MUST NOT be restored in release builds!
        assertNull("Demo session must be rejected and cleared on startup in non-debug builds", releaseRepo.currentUser.value)

        // Attempting signInWithDemoRole for any role in release builds MUST fail
        for (role in UserRole.values()) {
            val attempt = releaseRepo.signInWithDemoRole(role)
            assertTrue("Demo sign-in for $role must be blocked when isDebugBuild=false", attempt is AuthOperationResult.Error)
            assertNull(releaseRepo.currentUser.value)
        }

        // 3. Verify that the pre-existing local customer account was NOT deleted and can still log in offline
        val offlineLogin = releaseRepo.loginWithCredentials("existing.local@fresh.sa", "LocalUserPass11")
        assertTrue("Pre-existing local customer account must remain usable offline", offlineLogin is AuthOperationResult.Success)
        assertEquals("existing.local@fresh.sa", releaseRepo.currentUser.value?.email)
        assertEquals(UserRole.CUSTOMER, releaseRepo.currentUser.value?.role)
    }

    @Test
    fun test_10_end_to_end_real_http_django_auth_lifecycle_register_login_me_refresh_logout_and_guest_catalog() = runBlocking {
        val (repo, service, _) = createRepoWithRealServer()

        // Step 1: Unauthenticated guest catalog browsing works without any token
        val guestCategories = service.getCategories()
        val guestProducts = service.getProducts()
        assertEquals(2, guestCategories.size)
        assertEquals(2, guestProducts.size)

        // Step 2: Register a new customer via POST /api/v1/auth/register/
        val regResult = repo.registerCustomer(
            fullName = "بندر المالكي",
            email = "bandar.malki@fresh.sa",
            phoneNumber = "0559911223",
            password = "BandarStrongPass99",
            confirmPassword = "BandarStrongPass99"
        )
        assertTrue(regResult is AuthOperationResult.Success)
        val registeredTokens = repo.secureTokenStorage.readTokens()!!

        // Step 3: Duplicate registration is rejected by the server with 400
        val duplicateResult = repo.registerCustomer(
            fullName = "بندر المالكي",
            email = "bandar.malki@fresh.sa",
            phoneNumber = "0559911224",
            password = "BandarStrongPass99",
            confirmPassword = "BandarStrongPass99"
        )
        assertTrue(duplicateResult is AuthOperationResult.Error)

        // Step 4: Fetch authenticated identity via GET /api/v1/auth/me/
        val meProfile = repo.fetchRemoteCurrentUserMe()
        assertNotNull(meProfile)
        assertEquals("bandar.malki@fresh.sa", meProfile!!.email)
        assertEquals("CUSTOMER", meProfile.role)
        assertEquals("بندر المالكي", meProfile.fullName)

        // Step 5: Rotate session via POST /api/v1/auth/refresh/
        assertTrue(repo.refreshRemoteSession())
        val rotatedTokens = repo.secureTokenStorage.readTokens()!!
        assertNotEquals(registeredTokens.accessToken, rotatedTokens.accessToken)
        assertNotEquals(registeredTokens.refreshToken, rotatedTokens.refreshToken)

        // Step 6: Logout via POST /api/v1/auth/logout/ and verify immediate revocation
        repo.logout()
        assertNull(repo.currentUser.value)
        assertNull(repo.secureTokenStorage.readTokens())

        // Re-seeding the revoked access token and calling /api/v1/auth/me/ must return 401
        repo.secureTokenStorage.saveTokens(rotatedTokens)
        try {
            service.getCurrentUserMe()
            fail("Expected revoked access token to be rejected with 401 Unauthorized")
        } catch (httpEx: HttpException) {
            assertEquals(401, httpEx.code())
        }
    }
}
