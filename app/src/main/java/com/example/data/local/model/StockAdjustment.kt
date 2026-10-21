package com.example.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Persisted audit record for every validated inventory stock adjustment or order checkout deduction.
 */
@Serializable
@Entity(tableName = "stock_adjustments")
data class StockAdjustment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val productId: Int,
    val productName: String,
    val previousQuantity: Int,
    val newQuantity: Int,
    val quantityDelta: Int,
    val reason: String,
    val actorId: String,
    val actorName: String,
    val actorRole: String,
    val timestampMillis: Long,
    val previousQuantityMillis: Int = previousQuantity * 1000,
    val newQuantityMillis: Int = newQuantity * 1000,
    val quantityDeltaMillis: Int = quantityDelta * 1000
) {
    val previousQuantityUnits: Double
        get() = previousQuantityMillis / 1000.0

    val newQuantityUnits: Double
        get() = newQuantityMillis / 1000.0

    val quantityDeltaUnits: Double
        get() = quantityDeltaMillis / 1000.0

    fun formattedPreviousQuantity(): String = QuantityFormatter.formatMillis(previousQuantityMillis)

    fun formattedNewQuantity(): String = QuantityFormatter.formatMillis(newQuantityMillis)

    fun formattedDelta(): String {
        val formatted = QuantityFormatter.formatMillis(quantityDeltaMillis)
        return if (quantityDeltaMillis > 0) "+$formatted" else formatted
    }

    fun formattedDeltaQuantity(): String = formattedDelta()

    /**
     * Formats [timestampMillis] deterministically in UTC as `yyyy-MM-dd HH:mm` for audit display.
     */
    fun formattedTimestamp(): String {
        return try {
            Instant.ofEpochMilli(timestampMillis.coerceAtLeast(0L))
                .atZone(ZoneOffset.UTC)
                .format(AUDIT_TIMESTAMP_FORMATTER)
        } catch (_: Exception) {
            timestampMillis.toString()
        }
    }

    companion object {
        private val AUDIT_TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
