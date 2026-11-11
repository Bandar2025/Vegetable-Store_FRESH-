package com.example.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CategoryDto(
    @SerialName("id")
    val id: Int,
    @SerialName("name")
    val name: String,
    @SerialName("display_order")
    val displayOrder: Int = 0,
    @SerialName("is_active")
    val isActive: Boolean = true
)

@Serializable
data class ProductDto(
    @SerialName("id")
    val id: Int,
    @SerialName("sku")
    val sku: String = "",
    @SerialName("barcode")
    val barcode: String? = null,
    @SerialName("name")
    val name: String,
    @SerialName("category")
    val category: CategoryDto,
    @SerialName("price_halalas")
    val priceHalalas: Long,
    @SerialName("effective_price_halalas")
    val effectivePriceHalalas: Long? = null,
    @SerialName("is_available_in_branch")
    val isAvailableInBranch: Boolean = true,
    @SerialName("description")
    val description: String = "",
    @SerialName("image_url")
    val imageUrl: String = "",
    @SerialName("unit")
    val unit: String,
    @SerialName("is_fractional_allowed")
    val isFractionalAllowed: Boolean = false,
    @SerialName("is_active")
    val isActive: Boolean = true
) {
    /**
     * Authoritative price in integer halalas (`effective_price_halalas` when provided by the server,
     * falling back to base `price_halalas`). Never uses floating-point arithmetic.
     */
    val resolvedPriceHalalas: Long
        get() = effectivePriceHalalas ?: priceHalalas
}

@Serializable
data class OrderItemRequest(
    @SerialName("product_id")
    val productId: Int,
    @SerialName("quantity_milli")
    val quantityMilli: Long
) {
    init {
        require(productId >= 1) { "product_id must be >= 1 (got $productId)" }
        require(quantityMilli >= 1L) { "quantity_milli must be >= 1 (got $quantityMilli)" }
    }

    /**
     * Backward-compatible constructor converting whole-unit integer quantities into milli-units (`1000 = 1.000 unit`).
     */
    constructor(productId: Int, quantity: Int) : this(
        productId = productId,
        quantityMilli = quantity.toLong() * 1000L
    )
}

@Serializable
data class OrderRequest(
    @SerialName("items")
    val items: List<OrderItemRequest>,
    @SerialName("branch_id")
    val branchId: Int = 1,
    @SerialName("fulfillment_warehouse_id")
    val fulfillmentWarehouseId: Int? = null,
    @SerialName("idempotency_key")
    val idempotencyKey: String? = null
)

@Serializable
data class OrderItemDto(
    @SerialName("id")
    val id: Int,
    @SerialName("product")
    val productId: Int,
    @SerialName("product_name_snapshot")
    val productNameSnapshot: String,
    @SerialName("unit_snapshot")
    val unitSnapshot: String,
    @SerialName("quantity_milli")
    val quantityMilli: Long,
    @SerialName("unit_price_halalas")
    val unitPriceHalalas: Long,
    @SerialName("line_total_halalas")
    val lineTotalHalalas: Long
)

@Serializable
data class OrderStatusHistoryDto(
    @SerialName("id")
    val id: Int,
    @SerialName("from_status")
    val fromStatus: String? = null,
    @SerialName("to_status")
    val toStatus: String,
    @SerialName("changed_by_actor_id")
    val changedByActorId: String = "",
    @SerialName("reason")
    val reason: String = "",
    @SerialName("changed_at")
    val changedAt: String
)

@Serializable
data class OrderResponse(
    @SerialName("id")
    val id: Int,
    @SerialName("status")
    val status: String,
    @SerialName("total_halalas")
    val totalHalalas: Long,
    @SerialName("timestamp")
    val timestamp: String,
    @SerialName("branch")
    val branchId: Int? = null,
    @SerialName("fulfillment_warehouse")
    val fulfillmentWarehouseId: Int? = null,
    @SerialName("customer_id")
    val customerId: String? = null,
    @SerialName("items")
    val items: List<OrderItemDto> = emptyList(),
    @SerialName("status_history")
    val statusHistory: List<OrderStatusHistoryDto> = emptyList(),
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("idempotency_key")
    val idempotencyKey: String? = null,
    @SerialName("cancellation_restored")
    val cancellationRestored: Boolean = false,
    @SerialName("replayed")
    val replayed: Boolean? = null
)

@Serializable
data class ApiErrorDto(
    @SerialName("code")
    val code: String = "",
    @SerialName("message")
    val message: String = "",
    @SerialName("detail")
    val detail: String? = null
) {
    val resolvedMessage: String
        get() = detail?.takeIf { it.isNotBlank() } ?: message
}

/**
 * Customer self-registration request DTO (`POST /api/v1/auth/register/`).
 *
 * Security invariant:
 * - Never includes `role`, `is_staff`, `is_superuser`, `branch_id`, or `store_id`.
 * - Redacts `password` and `confirmPassword` in [toString] so credentials never appear in logs.
 */
@Serializable
data class AuthRegisterRequestDto(
    @SerialName("username")
    val username: String,
    @SerialName("email")
    val email: String,
    @SerialName("full_name")
    val fullName: String,
    @SerialName("phone_number")
    val phoneNumber: String,
    @SerialName("password")
    val password: String,
    @SerialName("confirm_password")
    val confirmPassword: String
) {
    override fun toString(): String {
        return "AuthRegisterRequestDto(username=$username, email=$email, fullName=$fullName, phoneNumber=$phoneNumber, password=[REDACTED], confirmPassword=[REDACTED])"
    }
}

/**
 * Login request DTO (`POST /api/v1/auth/login/`).
 * Redacts `password` in [toString].
 */
@Serializable
data class AuthLoginRequestDto(
    @SerialName("username")
    val username: String,
    @SerialName("password")
    val password: String,
    @SerialName("email")
    val email: String? = null
) {
    override fun toString(): String {
        return "AuthLoginRequestDto(username=$username, email=$email, password=[REDACTED])"
    }
}

/**
 * Refresh token rotation request DTO (`POST /api/v1/auth/refresh/`).
 * Redacts `refreshToken` in [toString].
 */
@Serializable
data class AuthRefreshRequestDto(
    @SerialName("refresh_token")
    val refreshToken: String
) {
    override fun toString(): String {
        return "AuthRefreshRequestDto(refreshToken=[REDACTED])"
    }
}

/**
 * Logout request DTO (`POST /api/v1/auth/logout/`).
 */
@Serializable
data class AuthLogoutRequestDto(
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("all_devices")
    val allDevices: Boolean = false
) {
    override fun toString(): String {
        return "AuthLogoutRequestDto(allDevices=$allDevices, refreshToken=[REDACTED])"
    }
}

/**
 * Server-issued JWT token pair & identity response DTO returned by
 * `POST /api/v1/auth/register/`, `POST /api/v1/auth/login/`, and `POST /api/v1/auth/refresh/`.
 * Redacts `token`, `accessToken`, and `refreshToken` in [toString].
 */
@Serializable
data class AuthTokenResponseDto(
    @SerialName("token")
    val token: String = "",
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("token_type")
    val tokenType: String = "Token",
    @SerialName("expires_in")
    val expiresIn: Long = 900L,
    @SerialName("expires_at")
    val expiresAt: String = "",
    @SerialName("refresh_expires_in")
    val refreshExpiresIn: Long = 604800L,
    @SerialName("refresh_expires_at")
    val refreshExpiresAt: String = "",
    @SerialName("actor_id")
    val actorId: String = "",
    @SerialName("username")
    val username: String = "",
    @SerialName("email")
    val email: String = "",
    @SerialName("full_name")
    val fullName: String = "",
    @SerialName("phone_number")
    val phoneNumber: String = "",
    @SerialName("role")
    val role: String,
    @SerialName("customer_id")
    val customerId: String? = null,
    @SerialName("store_id")
    val storeId: Int? = null,
    @SerialName("authorized_branch_ids")
    val authorizedBranchIds: List<Int>? = null
) {
    override fun toString(): String {
        return "AuthTokenResponseDto(actorId=$actorId, username=$username, role=$role, customerId=$customerId, storeId=$storeId, token=[REDACTED], accessToken=[REDACTED], refreshToken=[REDACTED])"
    }
}

/**
 * Server-resolved authenticated user identity DTO (`GET /api/v1/auth/me/`).
 */
@Serializable
data class AuthMeResponseDto(
    @SerialName("actor_id")
    val actorId: String,
    @SerialName("username")
    val username: String,
    @SerialName("email")
    val email: String = "",
    @SerialName("full_name")
    val fullName: String = "",
    @SerialName("phone_number")
    val phoneNumber: String = "",
    @SerialName("role")
    val role: String,
    @SerialName("customer_id")
    val customerId: String? = null,
    @SerialName("store_id")
    val storeId: Int? = null,
    @SerialName("authorized_branch_ids")
    val authorizedBranchIds: List<Int>? = null
)


