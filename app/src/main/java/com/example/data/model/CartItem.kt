package com.example.data.model
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.example.data.model.Product

@Entity(
    tableName = "cart_items",
    foreignKeys = [
        ForeignKey(
            entity = Product::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class CartItem(
    @PrimaryKey val productId: Int,
    val quantity: Int
)
