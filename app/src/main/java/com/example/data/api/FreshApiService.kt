package com.example.data.api

import com.example.data.api.model.AuthLoginRequestDto
import com.example.data.api.model.AuthLogoutRequestDto
import com.example.data.api.model.AuthMeResponseDto
import com.example.data.api.model.AuthRefreshRequestDto
import com.example.data.api.model.AuthRegisterRequestDto
import com.example.data.api.model.AuthTokenResponseDto
import com.example.data.api.model.CategoryDto
import com.example.data.api.model.OrderRequest
import com.example.data.api.model.OrderResponse
import com.example.data.api.model.ProductDto
import com.example.data.auth.JwtSessionTokens
import com.example.data.auth.SecureTokenStorage
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

interface FreshApiService {
    @GET("categories/")
    suspend fun getCategories(): List<CategoryDto>

    @GET("products/")
    suspend fun getProducts(): List<ProductDto>

    @GET("products/{id}/")
    suspend fun getProduct(@Path("id") id: Int): ProductDto

    @POST("orders/")
    suspend fun createOrder(@Body request: OrderRequest): OrderResponse

    @POST("auth/register/")
    suspend fun registerCustomer(@Body request: AuthRegisterRequestDto): AuthTokenResponseDto {
        throw UnsupportedOperationException("registerCustomer not implemented by test double")
    }

    @POST("auth/login/")
    suspend fun login(@Body request: AuthLoginRequestDto): AuthTokenResponseDto {
        throw UnsupportedOperationException("login not implemented by test double")
    }

    @POST("auth/refresh/")
    suspend fun refreshToken(@Body request: AuthRefreshRequestDto): AuthTokenResponseDto {
        throw UnsupportedOperationException("refreshToken not implemented by test double")
    }

    @GET("auth/me/")
    suspend fun getCurrentUserMe(): AuthMeResponseDto {
        throw UnsupportedOperationException("getCurrentUserMe not implemented by test double")
    }

    @POST("auth/logout/")
    suspend fun logout(
        @Body request: AuthLogoutRequestDto = AuthLogoutRequestDto()
    ): Map<String, String> {
        throw UnsupportedOperationException("logout not implemented by test double")
    }
}

/**
 * OkHttp [Interceptor] that attaches `Authorization: Bearer <access_token>` only to the
 * configured trusted backend host and implements single-flight synchronized token rotation:
 *
 * Security & Reliability Rules:
 * 1. Never attaches `Authorization` to untrusted external hosts or to public authentication
 *    endpoints (`/auth/login/`, `/auth/register/`, `/auth/refresh/`, `/auth/token/`).
 * 2. Never logs tokens or `Authorization` headers.
 * 3. Synchronizes token refresh with a single lock ([refreshLock]) so concurrent 401 responses
 *    or expired-token checks trigger only ONE `POST /api/v1/auth/refresh/` network call.
 * 4. Persists both the new `access_token` AND the rotated `refresh_token` in [SecureTokenStorage].
 * 5. Retries a failed request at most once (guarded by [HEADER_INTERNAL_RETRY_MARKER]) to
 *    prevent infinite retry loops.
 * 6. Clears tokens and invokes [onSessionInvalidated] only when the server explicitly rejects
 *    the refresh token with HTTP 401 or 403; preserves tokens and local session on transient
 *    network errors ([IOException]) or 5xx server errors.
 */
class FreshAuthInterceptor(
    private val tokenStorage: SecureTokenStorage,
    baseUrl: String = FreshRetrofitClient.DEFAULT_LOCAL_DEV_BASE_URL,
    private val nowEpochSecondsProvider: () -> Long = { System.currentTimeMillis() / 1000L },
    private val onSessionInvalidated: () -> Unit = {}
) : Interceptor {

    private val normalizedBaseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val parsedBaseHttpUrl = normalizedBaseUrl.toHttpUrlOrNull()
    private val trustedHost: String = parsedBaseHttpUrl?.host ?: "10.0.2.2"
    private val trustedPort: Int? = parsedBaseHttpUrl?.port
    private val refreshEndpointUrl: String = "${normalizedBaseUrl}auth/refresh/"
    private val refreshLock = Any()

    private val refreshCallCounter = AtomicInteger(0)
    val totalRefreshCallsExecuted: Int
        get() = refreshCallCounter.get()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val isAlreadyRetried = originalRequest.header(HEADER_INTERNAL_RETRY_MARKER) == "1"
        val cleanRequestBuilder = originalRequest.newBuilder()
            .removeHeader(HEADER_INTERNAL_RETRY_MARKER)

        // 1. Verify host trust boundary and endpoint eligibility
        if (!isTrustedBackendRequest(originalRequest) || isUnauthenticatedAuthEndpoint(originalRequest)) {
            return chain.proceed(cleanRequestBuilder.build())
        }

        // 2. Read current encrypted tokens (if any)
        var currentTokens = tokenStorage.readTokens()
        if (currentTokens != null && currentTokens.isAccessTokenExpired(nowEpochSecondsProvider() * 1000L)) {
            currentTokens = refreshTokensSingleFlight(
                chain = chain,
                failedAccessToken = currentTokens.accessToken
            )
        }

        val usedAccessToken = currentTokens?.accessToken?.takeIf { it.isNotBlank() }
        if (usedAccessToken != null) {
            cleanRequestBuilder.header(HEADER_AUTHORIZATION, "Bearer $usedAccessToken")
        }

        val initialResponse = chain.proceed(cleanRequestBuilder.build())

        // 3. Do not attempt refresh on 401 if already retried once, or if endpoint is logout, or if no token was used
        if (
            initialResponse.code != 401 ||
            isAlreadyRetried ||
            isLogoutEndpoint(originalRequest) ||
            usedAccessToken == null
        ) {
            return initialResponse
        }

        // Snapshot and close the prior 401 response body BEFORE issuing any nested chain.proceed() call,
        // because OkHttp RealCall forbids starting a new network exchange while the previous response body is open.
        val cachedErrorBodyBytes = runCatching { initialResponse.body?.bytes() ?: ByteArray(0) }
            .getOrDefault(ByteArray(0))
        val cachedErrorContentType = initialResponse.body?.contentType()
        runCatching { initialResponse.close() }
        val fallback401Response = initialResponse.newBuilder()
            .body(cachedErrorBodyBytes.toResponseBody(cachedErrorContentType))
            .build()

        // 4. Single-flight synchronized token refresh on 401 Unauthorized
        val refreshedTokens = refreshTokensSingleFlight(
            chain = chain,
            failedAccessToken = usedAccessToken
        ) ?: return fallback401Response

        val newAccessToken = refreshedTokens.accessToken
        if (newAccessToken.isBlank()) {
            return fallback401Response
        }

        val retryRequest = originalRequest.newBuilder()
            .header(HEADER_INTERNAL_RETRY_MARKER, "1")
            .header(HEADER_AUTHORIZATION, "Bearer $newAccessToken")
            .build()

        // Strip internal retry marker inside the recursive/proceed chain while preventing >1 retry
        return chain.proceed(
            retryRequest.newBuilder()
                .removeHeader(HEADER_INTERNAL_RETRY_MARKER)
                .build()
        )
    }

    private fun refreshTokensSingleFlight(
        chain: Interceptor.Chain,
        failedAccessToken: String
    ): JwtSessionTokens? {
        synchronized(refreshLock) {
            val latestStored = tokenStorage.readTokens() ?: return null
            val nowMillis = nowEpochSecondsProvider() * 1000L

            // If another thread already rotated the token pair while we waited on refreshLock,
            // reuse the newly stored tokens immediately without making a second refresh HTTP call!
            if (
                latestStored.accessToken != failedAccessToken &&
                !latestStored.isAccessTokenExpired(nowMillis)
            ) {
                return latestStored
            }

            if (latestStored.isRefreshTokenExpired(nowMillis)) {
                tokenStorage.clearTokens()
                onSessionInvalidated()
                return null
            }

            val refreshBodyJson = FreshRetrofitClient.networkJson.encodeToString(
                AuthRefreshRequestDto.serializer(),
                AuthRefreshRequestDto(refreshToken = latestStored.refreshToken)
            )
            val refreshRequest = Request.Builder()
                .url(refreshEndpointUrl)
                .post(refreshBodyJson.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .build()

            return try {
                refreshCallCounter.incrementAndGet()
                val refreshResponse = chain.proceed(refreshRequest)
                refreshResponse.use { resp ->
                    when {
                        resp.isSuccessful -> {
                            val bodyStr = resp.body?.string().orEmpty()
                            if (bodyStr.isBlank()) return null
                            val dto = FreshRetrofitClient.networkJson.decodeFromString(
                                AuthTokenResponseDto.serializer(),
                                bodyStr
                            )
                            val updatedTokens = dto.toJwtSessionTokens(fallbackPrevious = latestStored)
                            tokenStorage.saveTokens(updatedTokens)
                            updatedTokens
                        }
                        resp.code == 401 || resp.code == 403 -> {
                            // Terminal refresh failure (revoked, expired, or replay-detected refresh token)
                            tokenStorage.clearTokens()
                            onSessionInvalidated()
                            null
                        }
                        else -> {
                            // Server 5xx error: preserve local session & tokens for later retry
                            null
                        }
                    }
                }
            } catch (_: IOException) {
                // Transient network outage during refresh: never destroy the user's local session
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    internal fun isTrustedBackendRequest(request: Request): Boolean {
        val url = request.url
        if (!url.host.equals(trustedHost, ignoreCase = true)) {
            return false
        }
        if (trustedPort != null && url.port != trustedPort) {
            return false
        }
        return true
    }

    private fun isUnauthenticatedAuthEndpoint(request: Request): Boolean {
        val path = request.url.encodedPath.lowercase()
        return path.endsWith("/auth/login/") ||
            path.endsWith("/auth/login") ||
            path.endsWith("/auth/register/") ||
            path.endsWith("/auth/register") ||
            path.endsWith("/auth/refresh/") ||
            path.endsWith("/auth/refresh") ||
            path.endsWith("/auth/token/") ||
            path.endsWith("/auth/token")
    }

    private fun isLogoutEndpoint(request: Request): Boolean {
        val path = request.url.encodedPath.lowercase()
        return path.endsWith("/auth/logout/") || path.endsWith("/auth/logout")
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        internal const val HEADER_INTERNAL_RETRY_MARKER = "X-Fresh-Refresh-Retry"
    }
}

/**
 * Converts an [AuthTokenResponseDto] into [JwtSessionTokens] with parsed timestamps.
 */
fun AuthTokenResponseDto.toJwtSessionTokens(
    fallbackPrevious: JwtSessionTokens? = null
): JwtSessionTokens {
    val base = JwtSessionTokens.fromResponse(this)
    return base.copy(
        actorId = base.actorId.ifBlank { fallbackPrevious?.actorId ?: "" },
        username = base.username.ifBlank { fallbackPrevious?.username ?: base.email },
        email = base.email.ifBlank { fallbackPrevious?.email ?: "" },
        fullName = base.fullName.ifBlank { fallbackPrevious?.fullName ?: "" },
        phoneNumber = base.phoneNumber.ifBlank { fallbackPrevious?.phoneNumber ?: "" },
        role = base.role.ifBlank { fallbackPrevious?.role ?: "CUSTOMER" },
        customerId = base.customerId ?: fallbackPrevious?.customerId,
        storeId = base.storeId ?: fallbackPrevious?.storeId,
        authorizedBranchIds = base.authorizedBranchIds ?: fallbackPrevious?.authorizedBranchIds
    )
}

internal interface FreshRemoteEndpoints {
    @GET("categories/")
    suspend fun getCategories(): List<CategoryDto>

    @GET("products/")
    suspend fun getProducts(): List<ProductDto>

    @GET("products/{id}/")
    suspend fun getProduct(@Path("id") id: Int): ProductDto

    @POST("orders/")
    suspend fun createOrder(@Body request: OrderRequest): OrderResponse

    @POST("auth/register/")
    suspend fun registerCustomer(@Body request: AuthRegisterRequestDto): AuthTokenResponseDto

    @POST("auth/login/")
    suspend fun login(@Body request: AuthLoginRequestDto): AuthTokenResponseDto

    @POST("auth/refresh/")
    suspend fun refreshToken(@Body request: AuthRefreshRequestDto): AuthTokenResponseDto

    @GET("auth/me/")
    suspend fun getCurrentUserMe(): AuthMeResponseDto

    @POST("auth/logout/")
    suspend fun logout(@Body request: AuthLogoutRequestDto): Map<String, String>
}

/**
 * Centralized Retrofit & OkHttp factory for the FRESH Android client.
 *
 * Architectural note:
 * - No live production backend URL is published yet. [DEFAULT_LOCAL_DEV_BASE_URL] points to the
 *   standard Android emulator host loopback (`http://10.0.2.2:8000/api/v1/`) for local Django
 *   development (`python manage.py runserver`) rather than a fake placeholder domain.
 * - Callers and tests can supply an explicit [baseUrl] via [createService] or
 *   [createAuthenticatedService] when needed.
 */
object FreshRetrofitClient {
    const val DEFAULT_LOCAL_DEV_BASE_URL = "http://10.0.2.2:8000/api/v1/"

    val networkJson: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val service: FreshApiService by lazy {
        createService(DEFAULT_LOCAL_DEV_BASE_URL)
    }

    fun createService(
        baseUrl: String = DEFAULT_LOCAL_DEV_BASE_URL,
        client: OkHttpClient = okHttpClient
    ): FreshApiService {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val remote = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(client)
            .addConverterFactory(networkJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FreshRemoteEndpoints::class.java)

        return object : FreshApiService {
            override suspend fun getCategories(): List<CategoryDto> = remote.getCategories()
            override suspend fun getProducts(): List<ProductDto> = remote.getProducts()
            override suspend fun getProduct(id: Int): ProductDto = remote.getProduct(id)
            override suspend fun createOrder(request: OrderRequest): OrderResponse = remote.createOrder(request)
            override suspend fun registerCustomer(request: AuthRegisterRequestDto): AuthTokenResponseDto =
                remote.registerCustomer(request)
            override suspend fun login(request: AuthLoginRequestDto): AuthTokenResponseDto =
                remote.login(request)
            override suspend fun refreshToken(request: AuthRefreshRequestDto): AuthTokenResponseDto =
                remote.refreshToken(request)
            override suspend fun getCurrentUserMe(): AuthMeResponseDto =
                remote.getCurrentUserMe()
            override suspend fun logout(request: AuthLogoutRequestDto): Map<String, String> =
                remote.logout(request)
        }
    }

    fun createAuthenticatedService(
        tokenStorage: SecureTokenStorage,
        baseUrl: String = DEFAULT_LOCAL_DEV_BASE_URL,
        connectTimeoutMs: Long = 10_000L,
        readTimeoutMs: Long = 15_000L,
        nowEpochSecondsProvider: () -> Long = { System.currentTimeMillis() / 1000L },
        onSessionInvalidated: () -> Unit = {}
    ): Pair<FreshApiService, FreshAuthInterceptor> {
        val interceptor = FreshAuthInterceptor(
            tokenStorage = tokenStorage,
            baseUrl = baseUrl,
            nowEpochSecondsProvider = nowEpochSecondsProvider,
            onSessionInvalidated = onSessionInvalidated
        )
        val client = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor(interceptor)
            .build()
        return createService(baseUrl = baseUrl, client = client) to interceptor
    }
}

