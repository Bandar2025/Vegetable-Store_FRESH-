package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.model.StockAdjustment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

@Dao
interface StockAdjustmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAdjustment(adjustment: StockAdjustment): Long

    @Query("SELECT * FROM stock_adjustments ORDER BY timestampMillis DESC, id DESC")
    fun getAllAdjustments(): Flow<List<StockAdjustment>>

    @Query("SELECT * FROM stock_adjustments ORDER BY timestampMillis DESC, id DESC")
    suspend fun getAllAdjustmentsOnce(): List<StockAdjustment> = getAllAdjustments().first()

    @Query("SELECT * FROM stock_adjustments WHERE productId = :productId ORDER BY timestampMillis DESC, id DESC")
    fun getAdjustmentsForProduct(productId: Int): Flow<List<StockAdjustment>>

    @Query("SELECT COUNT(*) FROM stock_adjustments WHERE reason = :exactReason OR reason LIKE :reasonPrefix || ' - %'")
    suspend fun countAdjustmentsForOrderAction(exactReason: String, reasonPrefix: String = exactReason): Int =
        getAllAdjustments().first().count { it.reason == exactReason || it.reason.startsWith("$reasonPrefix - ") }
}
