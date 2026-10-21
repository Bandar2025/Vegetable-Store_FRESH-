package com.example.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class ProductExpiryStatus(val arabicLabel: String) {
    VALID("صلاحية سارية"),
    EXPIRING_SOON("يقترب من انتهاء الصلاحية"),
    EXPIRED("منتهي الصلاحية"),
    NO_EXPIRY_DATE("غير قابل للتلف / بدون تاريخ"),
    INVALID_EXPIRY_DATE("تاريخ صلاحية غير صالح")
}

@Serializable
@Entity(tableName = "products")
data class Product(
    @PrimaryKey val id: Int,
    val name: String,
    val category: String,
    val priceHalalas: Int,
    val description: String,
    val imageUrl: String,
    val unit: String,
    val stockQuantity: Int = 25,
    val lowStockThreshold: Int = 5,
    val expiryDate: String = "2026-12-31",
    val isActive: Boolean = true,
    val isArchived: Boolean = false,
    val updatedAtMillis: Long = 1760688000000L,
    val nameEn: String = "",
    val stockSubUnitsMillis: Int = 0
) {
    val priceSar: Double
        get() = priceHalalas / 100.0

    /**
     * Exact available stock in milli-units (1000 = 1.0 unit), supporting fractional produce deductions
     * (e.g. 0.25 kg = 250, 0.5 kg = 500, 1.5 kg = 1500) while remaining 100% compatible with [stockQuantity].
     */
    val effectiveStockMillis: Int
        get() = if (stockSubUnitsMillis > 0) stockSubUnitsMillis else stockQuantity * 1000

    /**
     * Available stock expressed in decimal units (e.g., 8.5 for 8500 milli-units).
     */
    val stockQuantityUnits: Double
        get() = effectiveStockMillis / 1000.0

    /**
     * Alias for [expiryDate] in strict ISO format (`YYYY-MM-DD`).
     */
    val expiryDateIso: String
        get() = expiryDate

    fun formattedPriceSar(): String = CurrencyCalculator.formatHalalasToSar(priceHalalas.toLong())

    fun formattedStockQuantity(): String = QuantityFormatter.formatMillis(effectiveStockMillis)

    /**
     * Returns a copy of this [Product] with its stock updated to [newStockMillis] (in milli-units).
     */
    fun withUpdatedStockMillis(
        newStockMillis: Int,
        updatedTimestampMillis: Long = System.currentTimeMillis()
    ): Product {
        val safeMillis = newStockMillis.coerceAtLeast(0)
        val ceilWhole = if (safeMillis > 0) kotlin.math.ceil(safeMillis / 1000.0).toInt() else 0
        return copy(
            stockQuantity = ceilWhole,
            stockSubUnitsMillis = safeMillis,
            updatedAtMillis = updatedTimestampMillis
        )
    }

    val isOutOfStock: Boolean
        get() = !isArchived && effectiveStockMillis <= 0

    val isLowStock: Boolean
        get() = !isArchived && effectiveStockMillis > 0 && effectiveStockMillis <= (lowStockThreshold * 1000)

    val isVisibleInStorefront: Boolean
        get() = isActive && !isArchived

    val isPurchasableInStorefront: Boolean
        get() = isVisibleInStorefront && effectiveStockMillis > 0

    /**
     * Evaluates the product's expiry state using strict [LocalDate] parsing (`YYYY-MM-DD`).
     * Missing/blank expiry dates are treated as [ProductExpiryStatus.NO_EXPIRY_DATE] (non-perishable)
     * and are never marked as expired.
     */
    fun evaluateExpiryStatus(
        referenceDateIso: String = DEFAULT_REFERENCE_DATE_ISO,
        warningWindowDays: Int = DEFAULT_EXPIRY_WARNING_DAYS
    ): ProductExpiryStatus {
        val cleanExpiry = expiryDate.trim()
        if (cleanExpiry.isEmpty()) {
            return ProductExpiryStatus.NO_EXPIRY_DATE
        }
        val expiryLocalDate = parseStrictIsoDate(cleanExpiry)
            ?: return ProductExpiryStatus.INVALID_EXPIRY_DATE
        val referenceLocalDate = parseStrictIsoDate(referenceDateIso.trim())
            ?: LocalDate.of(2026, 10, 17)

        val effectiveWindow = warningWindowDays.coerceAtLeast(0).toLong()
        val daysRemaining = ChronoUnit.DAYS.between(referenceLocalDate, expiryLocalDate)

        return when {
            daysRemaining < 0L -> ProductExpiryStatus.EXPIRED
            daysRemaining <= effectiveWindow -> ProductExpiryStatus.EXPIRING_SOON
            else -> ProductExpiryStatus.VALID
        }
    }

    /**
     * Returns the signed number of days between [referenceDateIso] and [expiryDate],
     * or `null` if the product has no expiry date or an invalid expiry date.
     */
    fun daysUntilExpiry(referenceDateIso: String = DEFAULT_REFERENCE_DATE_ISO): Long? {
        val expiryLocalDate = parseStrictIsoDate(expiryDate.trim()) ?: return null
        val referenceLocalDate = parseStrictIsoDate(referenceDateIso.trim()) ?: return null
        return ChronoUnit.DAYS.between(referenceLocalDate, expiryLocalDate)
    }

    /**
     * Checks whether the product's ISO expiry date (`YYYY-MM-DD`) is strictly earlier than [referenceDateIso].
     */
    fun isExpired(referenceDateIso: String = DEFAULT_REFERENCE_DATE_ISO): Boolean {
        if (isArchived) return false
        return evaluateExpiryStatus(referenceDateIso) == ProductExpiryStatus.EXPIRED
    }

    /**
     * Checks whether the product is not expired yet, but will expire within [warningWindowDays] days
     * (inclusive of today `0..warningWindowDays`).
     */
    fun isExpiringSoon(
        referenceDateIso: String = DEFAULT_REFERENCE_DATE_ISO,
        warningWindowDays: Int = DEFAULT_EXPIRY_WARNING_DAYS
    ): Boolean {
        if (isArchived) return false
        return evaluateExpiryStatus(referenceDateIso, warningWindowDays) == ProductExpiryStatus.EXPIRING_SOON
    }

    companion object {
        const val DEFAULT_REFERENCE_DATE_ISO = "2026-10-17"
        const val DEFAULT_EXPIRY_WARNING_DAYS = 7
        val ISO_DATE_SHAPE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /**
         * Strictly validates and parses an ISO-8601 calendar date (`YYYY-MM-DD`) into [LocalDate].
         * Rejects impossible calendar dates such as `2026-02-30` or non-ISO formats.
         */
        fun parseStrictIsoDate(raw: String): LocalDate? {
            val trimmed = raw.trim()
            if (!ISO_DATE_SHAPE_REGEX.matches(trimmed)) return null
            return try {
                LocalDate.parse(trimmed)
            } catch (e: DateTimeParseException) {
                null
            }
        }

        fun isValidIsoDate(raw: String): Boolean = parseStrictIsoDate(raw) != null
    }
}
