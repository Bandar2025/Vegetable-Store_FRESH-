package com.example.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Explicit local order lifecycle state machine:
 * - [CONFIRMED_LOCAL]: Newly created local order persisted at checkout (stock deducted).
 *   Allowed next states: [PREPARING], [CANCELLED].
 * - [PREPARING]: Order is being prepared/processed by the store.
 *   Allowed next states: [OUT_FOR_DELIVERY], [DELIVERED], [CANCELLED].
 * - [OUT_FOR_DELIVERY]: Order is dispatched for local delivery.
 *   Allowed next states: [DELIVERED], [CANCELLED].
 * - [DELIVERED]: Completed terminal state. Cannot transition to any other state and cannot be cancelled.
 * - [CANCELLED]: Cancelled terminal state (stock restored once). Cannot be cancelled again or reopened.
 */
enum class OrderStatus(
    val arabicLabel: String,
    val actionButtonArabic: String
) {
    CONFIRMED_LOCAL(
        arabicLabel = "مؤكد محلياً (جديد)",
        actionButtonArabic = "تأكيد الطلب المحلي"
    ),
    PREPARING(
        arabicLabel = "قيد التجهيز",
        actionButtonArabic = "بدء تجهيز الطلب (قيد التجهيز)"
    ),
    OUT_FOR_DELIVERY(
        arabicLabel = "في الطريق للتوصيل",
        actionButtonArabic = "إرسال للتوصيل (في الطريق للتوصيل)"
    ),
    DELIVERED(
        arabicLabel = "مكتمل (تم التوصيل)",
        actionButtonArabic = "تأكيد التسليم وإكمال الطلب"
    ),
    CANCELLED(
        arabicLabel = "ملغي",
        actionButtonArabic = "إلغاء الطلب واسترجاع المخزون"
    );

    val isTerminal: Boolean
        get() = this == DELIVERED || this == CANCELLED

    val canBeCancelled: Boolean
        get() = this == CONFIRMED_LOCAL || this == PREPARING || this == OUT_FOR_DELIVERY

    fun allowedNextStatuses(): Set<OrderStatus> = when (this) {
        CONFIRMED_LOCAL -> setOf(PREPARING, CANCELLED)
        PREPARING -> setOf(OUT_FOR_DELIVERY, DELIVERED, CANCELLED)
        OUT_FOR_DELIVERY -> setOf(DELIVERED, CANCELLED)
        DELIVERED -> emptySet()
        CANCELLED -> emptySet()
    }

    fun canTransitionTo(target: OrderStatus): Boolean {
        return target in allowedNextStatuses()
    }

    companion object {
        val CREATED: OrderStatus = CONFIRMED_LOCAL
        val CONFIRMED: OrderStatus = CONFIRMED_LOCAL
        val PROCESSING: OrderStatus = PREPARING
        val COMPLETED: OrderStatus = DELIVERED

        fun fromNameOrDefault(raw: String): OrderStatus {
            return entries.find { it.name.equals(raw.trim(), ignoreCase = true) } ?: CONFIRMED_LOCAL
        }

        fun fromNameOrNull(raw: String): OrderStatus? {
            return entries.find { it.name.equals(raw.trim(), ignoreCase = true) }
        }
    }
}

/**
 * Exact integer monetary calculator using Halalas (1 SAR = 100 Halalas) and
 * milli-units (1 unit = 1000 milli-units). Never uses floating-point arithmetic for money.
 */
object CurrencyCalculator {
    const val HALALAS_PER_SAR = 100L
    const val MILLIS_PER_UNIT = 1000L

    /**
     * Computes exact line total in halalas for [unitPriceHalalas] and [quantityMillis]
     * using integer arithmetic with half-up rounding: `(price * qtyMillis + 500) / 1000`.
     */
    fun calculateLineTotalHalalasLong(unitPriceHalalas: Int, quantityMillis: Int): Long {
        if (unitPriceHalalas <= 0 || quantityMillis <= 0) return 0L
        val numerator = unitPriceHalalas.toLong() * quantityMillis.toLong()
        return (numerator + (MILLIS_PER_UNIT / 2L)) / MILLIS_PER_UNIT
    }

    fun calculateLineTotalHalalas(unitPriceHalalas: Int, quantityMillis: Int): Int {
        return calculateLineTotalHalalasLong(unitPriceHalalas, quantityMillis).toInt()
    }

    /**
     * Formats exact integer [halalas] as `X.XX` SAR using integer division and remainder only.
     */
    fun formatHalalasToSar(halalas: Long): String {
        val sign = if (halalas < 0L) "-" else ""
        val absHalalas = abs(halalas)
        val riyals = absHalalas / HALALAS_PER_SAR
        val remainder = absHalalas % HALALAS_PER_SAR
        return String.format(Locale.US, "%s%d.%02d", sign, riyals, remainder)
    }
}

/**
 * Exact formatter and parser for whole and fractional produce quantities in milli-units (1000 = 1 unit).
 */
object QuantityFormatter {
    fun formatMillis(quantityMillis: Int): String {
        val sign = if (quantityMillis < 0) "-" else ""
        val absMillis = abs(quantityMillis)
        val whole = absMillis / 1000
        val rem = absMillis % 1000
        return when {
            rem == 0 -> "$sign$whole"
            rem % 100 == 0 -> "$sign$whole.${rem / 100}"
            rem % 10 == 0 -> String.format(Locale.US, "%s%d.%02d", sign, whole, rem / 10)
            else -> String.format(Locale.US, "%s%d.%03d", sign, whole, rem)
        }
    }

    /**
     * Parses a decimal or integer quantity string (supporting Arabic `٠-٩` and Latin `0-9` digits)
     * into exact milli-units without floating-point rounding drift.
     */
    fun parseToMillis(rawInput: String): Int? {
        val normalized = normalizeDigits(rawInput.trim())
        if (normalized.isEmpty()) return null
        val negative = normalized.startsWith("-")
        val unsigned = normalized.removePrefix("-").removePrefix("+")
        if (unsigned.isEmpty()) return null

        val parts = unsigned.split(".")
        if (parts.size > 2) return null

        val wholePartStr = parts[0].ifEmpty { "0" }
        if (!wholePartStr.all { it.isDigit() }) return null
        val whole = wholePartStr.toIntOrNull() ?: return null

        val fracPartStr = if (parts.size == 2) parts[1] else ""
        if (!fracPartStr.all { it.isDigit() }) return null
        val paddedFrac = fracPartStr.take(3).padEnd(3, '0')
        val fracMillis = if (paddedFrac.isEmpty()) 0 else (paddedFrac.toIntOrNull() ?: return null)

        val totalMillis = whole * 1000 + fracMillis
        return if (negative) -totalMillis else totalMillis
    }

    private fun normalizeDigits(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                in '٠'..'٩' -> sb.append(ch - '٠')
                in '۰'..'۹' -> sb.append(ch - '۰')
                '٫', ',' -> sb.append('.')
                '−', '–' -> sb.append('-')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}

@Serializable
@Entity(tableName = "orders")
data class Order(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val totalHalalas: Int,
    val timestamp: Long,
    val orderNumber: String = "",
    val customerId: String = "guest_local",
    val customerName: String = "متسوق محلي",
    val status: String = OrderStatus.CONFIRMED_LOCAL.name,
    val subtotalHalalas: Int = totalHalalas,
    val deliveryFeeHalalas: Int = 0,
    val discountHalalas: Int = 0,
    val deliveryAddressSnapshot: String = "الرياض، حي النخيل",
    val paymentModeNote: String = "إتمام طلب محلي (الدفع عند الاستلام — بوابة الدفع الإلكتروني غير متصلة حالياً)",
    val idempotencyKey: String = ""
) {
    val displayOrderNumber: String
        get() = orderNumber.ifBlank { "FRESH-${1000 + id}" }

    val customerNameSnapshot: String
        get() = customerName

    val orderStatus: OrderStatus
        get() = OrderStatus.fromNameOrDefault(status)

    fun formattedSubtotalSar(): String = CurrencyCalculator.formatHalalasToSar(subtotalHalalas.toLong())

    fun formattedDeliveryFeeSar(): String = CurrencyCalculator.formatHalalasToSar(deliveryFeeHalalas.toLong())

    fun formattedDiscountSar(): String = CurrencyCalculator.formatHalalasToSar(discountHalalas.toLong())

    fun formattedTotalSar(): String = CurrencyCalculator.formatHalalasToSar(totalHalalas.toLong())

    fun formattedTimestamp(): String {
        return try {
            Instant.ofEpochMilli(timestamp.coerceAtLeast(0L))
                .atZone(ZoneOffset.UTC)
                .format(ORDER_TIMESTAMP_FORMATTER)
        } catch (_: Exception) {
            timestamp.toString()
        }
    }

    companion object {
        private val ORDER_TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
