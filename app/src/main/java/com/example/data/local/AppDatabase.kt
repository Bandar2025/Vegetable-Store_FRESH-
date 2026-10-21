package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.model.Product
import com.example.data.local.model.CartItem
import com.example.data.local.model.Order
import com.example.data.local.model.OrderItem
import com.example.data.local.model.StockAdjustment
import com.example.data.local.dao.ProductDao
import com.example.data.local.dao.CartDao
import com.example.data.local.dao.OrderDao
import com.example.data.local.dao.OrderItemDao
import com.example.data.local.dao.StockAdjustmentDao

@Database(
    entities = [
        Product::class,
        CartItem::class,
        Order::class,
        OrderItem::class,
        StockAdjustment::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun cartDao(): CartDao
    abstract fun orderDao(): OrderDao
    abstract fun orderItemDao(): OrderItemDao
    abstract fun stockAdjustmentDao(): StockAdjustmentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN stockQuantity INTEGER NOT NULL DEFAULT 25")
                db.execSQL("ALTER TABLE products ADD COLUMN lowStockThreshold INTEGER NOT NULL DEFAULT 5")
                db.execSQL("ALTER TABLE products ADD COLUMN expiryDate TEXT NOT NULL DEFAULT '2026-12-31'")
                db.execSQL("ALTER TABLE products ADD COLUMN isActive INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE products ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE products ADD COLUMN updatedAtMillis INTEGER NOT NULL DEFAULT 1760688000000")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN nameEn TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stock_adjustments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `productId` INTEGER NOT NULL,
                        `productName` TEXT NOT NULL,
                        `previousQuantity` INTEGER NOT NULL,
                        `newQuantity` INTEGER NOT NULL,
                        `quantityDelta` INTEGER NOT NULL,
                        `reason` TEXT NOT NULL,
                        `actorId` TEXT NOT NULL,
                        `actorName` TEXT NOT NULL,
                        `actorRole` TEXT NOT NULL,
                        `timestampMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN stockSubUnitsMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cart_items ADD COLUMN quantityMillis INTEGER NOT NULL DEFAULT 1000")
                db.execSQL("UPDATE cart_items SET quantityMillis = quantity * 1000")

                db.execSQL("ALTER TABLE stock_adjustments ADD COLUMN previousQuantityMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stock_adjustments ADD COLUMN newQuantityMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stock_adjustments ADD COLUMN quantityDeltaMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE stock_adjustments SET previousQuantityMillis = previousQuantity * 1000, newQuantityMillis = newQuantity * 1000, quantityDeltaMillis = quantityDelta * 1000")

                db.execSQL("ALTER TABLE orders ADD COLUMN orderNumber TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE orders ADD COLUMN customerId TEXT NOT NULL DEFAULT 'guest_local'")
                db.execSQL("ALTER TABLE orders ADD COLUMN customerName TEXT NOT NULL DEFAULT 'متسوق محلي'")
                db.execSQL("ALTER TABLE orders ADD COLUMN status TEXT NOT NULL DEFAULT 'CONFIRMED_LOCAL'")
                db.execSQL("ALTER TABLE orders ADD COLUMN subtotalHalalas INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE orders ADD COLUMN deliveryFeeHalalas INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE orders ADD COLUMN discountHalalas INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE orders ADD COLUMN deliveryAddressSnapshot TEXT NOT NULL DEFAULT 'الرياض، حي النخيل'")
                db.execSQL("ALTER TABLE orders ADD COLUMN paymentModeNote TEXT NOT NULL DEFAULT 'إتمام طلب محلي (الدفع عند الاستلام — بوابة الدفع الإلكتروني غير متصلة حالياً)'")
                db.execSQL("ALTER TABLE orders ADD COLUMN idempotencyKey TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE order_items ADD COLUMN productName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE order_items ADD COLUMN unit TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE order_items ADD COLUMN quantityMillis INTEGER NOT NULL DEFAULT 1000")
                db.execSQL("ALTER TABLE order_items ADD COLUMN lineTotalHalalas INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE order_items SET quantityMillis = quantity * 1000, lineTotalHalalas = priceHalalas * quantity")
            }
        }
    }
}
