package com.example.data.local.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Immutable snapshot of an item purchased within an [Order].
 * Preserves [productName], [unit], [priceHalalas], [quantityMillis], and [lineTotalHalalas]
 * at the moment of purchase even if the [Product] record is later edited or archived.
 */
@Serializable
@Entity(
    tableName = "order_items",
    foreignKeys = [
        ForeignKey(
            entity = Order::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["orderId"])]
)
data class OrderItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val orderId: Int,
    val productId: Int,
    val quantity: Int,
    val priceHalalas: Int,
    val productName: String = "",
    val unit: String = "",
    val quantityMillis: Int = quantity * 1000,
    val lineTotalHalalas: Int = CurrencyCalculator.calculateLineTotalHalalas(priceHalalas, quantityMillis)
) {
    val unitPriceHalalas: Int
        get() = priceHalalas

    val effectiveQuantityMillis: Int
        get() = if (quantityMillis != quantity * 1000) quantityMillis else quantity * 1000

    val quantityUnits: Double
        get() = effectiveQuantityMillis / 1000.0

    fun formattedQuantity(): String = QuantityFormatter.formatMillis(effectiveQuantityMillis)

    fun formattedUnitPriceSar(): String = CurrencyCalculator.formatHalalasToSar(priceHalalas.toLong())

    fun formattedLineTotalSar(): String = CurrencyCalculator.formatHalalasToSar(lineTotalHalalas.toLong())
}
