package com.example.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.math.ceil
import kotlin.math.roundToInt

@Entity(tableName = "cart_items")
data class CartItem(
    @PrimaryKey val productId: Int,
    val quantity: Int,
    val quantityMillis: Int = quantity * 1000
) {
    /**
     * Exact requested quantity in milli-units (1000 = 1.0 unit).
     */
    val effectiveQuantityMillis: Int
        get() = if (quantityMillis != quantity * 1000) quantityMillis else quantity * 1000

    /**
     * Requested quantity in decimal units (e.g., 1.5 for 1500 milli-units).
     */
    val quantityUnits: Double
        get() = effectiveQuantityMillis / 1000.0

    fun formattedQuantity(): String = QuantityFormatter.formatMillis(effectiveQuantityMillis)

    companion object {
        /**
         * Creates a [CartItem] from a fractional unit quantity (e.g. `0.25`, `0.5`, `1.5` kg).
         */
        fun fromFractionalUnits(productId: Int, quantityUnits: Double): CartItem {
            val millis = (quantityUnits * 1000.0).roundToInt()
            val wholeDisplay = if (millis > 0) ceil(millis / 1000.0).toInt() else 0
            return CartItem(
                productId = productId,
                quantity = wholeDisplay,
                quantityMillis = millis
            )
        }

        /**
         * Creates a [CartItem] directly from milli-units (`1000` = `1.0` unit).
         */
        fun fromMillis(productId: Int, quantityMillis: Int): CartItem {
            val wholeDisplay = if (quantityMillis > 0) ceil(quantityMillis / 1000.0).toInt() else 0
            return CartItem(
                productId = productId,
                quantity = wholeDisplay,
                quantityMillis = quantityMillis
            )
        }
    }
}
