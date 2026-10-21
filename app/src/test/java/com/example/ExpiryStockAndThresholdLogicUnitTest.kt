package com.example

import com.example.data.local.model.Product
import com.example.data.local.model.ProductExpiryStatus
import com.example.ui.viewmodel.ProductFormInput
import com.example.ui.viewmodel.ProductFormValidator
import com.example.ui.viewmodel.StockAdjustmentValidator
import com.example.ui.viewmodel.StoreDashboardMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JUnit test suite verifying the domain logic for:
 * 1. Expiry date parsing and comparisons (LocalDate ISO-8601 boundaries, cross-month/year, leap years, missing/invalid dates).
 * 2. Stock quantity and delta validation (Arabic/Latin numerals, negative quantity rejection, reason requirements).
 * 3. Inventory threshold flagging (low-stock vs out-of-stock boundaries, archive suppression, storefront purchasability, and dashboard metrics aggregation).
 */
class ExpiryStockAndThresholdLogicUnitTest {

    private fun sampleProduct(
        id: Int = 1,
        stockQuantity: Int = 25,
        lowStockThreshold: Int = 5,
        expiryDate: String = "2026-12-31",
        isActive: Boolean = true,
        isArchived: Boolean = false
    ): Product {
        return Product(
            id = id,
            name = "منتج اختباري $id",
            nameEn = "Test Product $id",
            category = "فواكه",
            priceHalalas = 1000,
            description = "وصف منتج اختباري للتحقق من المنطق",
            imageUrl = "apple",
            unit = "كيلوجرام",
            stockQuantity = stockQuantity,
            lowStockThreshold = lowStockThreshold,
            expiryDate = expiryDate,
            isActive = isActive,
            isArchived = isArchived
        )
    }

    // =========================================================================
    // 1. EXPIRY DATE COMPARISONS & STRICT ISO PARSING
    // =========================================================================

    @Test
    fun `strict ISO date parser accepts valid dates including leap year and rejects impossible calendar dates`() {
        assertTrue(Product.isValidIsoDate("2026-10-17"))
        assertTrue(Product.isValidIsoDate("2028-02-29")) // 2028 is a leap year

        // Impossible calendar dates
        assertFalse(Product.isValidIsoDate("2026-02-29")) // 2026 is not a leap year
        assertFalse(Product.isValidIsoDate("2026-02-30"))
        assertFalse(Product.isValidIsoDate("2026-04-31"))
        assertFalse(Product.isValidIsoDate("2026-13-01"))
        assertFalse(Product.isValidIsoDate("2026-00-15"))
        assertFalse(Product.isValidIsoDate("2026-10-00"))

        // Non-ISO formats
        assertFalse(Product.isValidIsoDate("17-10-2026"))
        assertFalse(Product.isValidIsoDate("2026/10/17"))
        assertFalse(Product.isValidIsoDate("2026-1-5"))
        assertFalse(Product.isValidIsoDate(""))
    }

    @Test
    fun `expiry evaluation distinguishes expired, expiring today, warning window boundary, and valid future dates`() {
        val referenceDate = "2026-10-17"

        // 1 day before reference date -> EXPIRED
        val expiredYesterday = sampleProduct(expiryDate = "2026-10-16")
        assertEquals(ProductExpiryStatus.EXPIRED, expiredYesterday.evaluateExpiryStatus(referenceDate, warningWindowDays = 7))
        assertTrue(expiredYesterday.isExpired(referenceDate))
        assertFalse(expiredYesterday.isExpiringSoon(referenceDate, warningWindowDays = 7))
        assertEquals(-1L, expiredYesterday.daysUntilExpiry(referenceDate))

        // Same day as reference date -> EXPIRING_SOON (0 days remaining)
        val expiringToday = sampleProduct(expiryDate = "2026-10-17")
        assertEquals(ProductExpiryStatus.EXPIRING_SOON, expiringToday.evaluateExpiryStatus(referenceDate, warningWindowDays = 7))
        assertFalse(expiringToday.isExpired(referenceDate))
        assertTrue(expiringToday.isExpiringSoon(referenceDate, warningWindowDays = 7))
        assertEquals(0L, expiringToday.daysUntilExpiry(referenceDate))

        // Exact upper bound of 7-day warning window (2026-10-24) -> EXPIRING_SOON
        val onBoundary = sampleProduct(expiryDate = "2026-10-24")
        assertEquals(ProductExpiryStatus.EXPIRING_SOON, onBoundary.evaluateExpiryStatus(referenceDate, warningWindowDays = 7))
        assertTrue(onBoundary.isExpiringSoon(referenceDate, warningWindowDays = 7))
        assertEquals(7L, onBoundary.daysUntilExpiry(referenceDate))

        // 1 day past 7-day warning window (2026-10-25) -> VALID
        val beyondBoundary = sampleProduct(expiryDate = "2026-10-25")
        assertEquals(ProductExpiryStatus.VALID, beyondBoundary.evaluateExpiryStatus(referenceDate, warningWindowDays = 7))
        assertFalse(beyondBoundary.isExpired(referenceDate))
        assertFalse(beyondBoundary.isExpiringSoon(referenceDate, warningWindowDays = 7))
        assertEquals(8L, beyondBoundary.daysUntilExpiry(referenceDate))
    }

    @Test
    fun `expiry comparison accurately spans month and year boundaries`() {
        val decReference = "2026-12-28"
        val janExpiry = sampleProduct(expiryDate = "2027-01-02")

        // 2026-12-28 to 2027-01-02 is exactly 5 days
        assertEquals(5L, janExpiry.daysUntilExpiry(decReference))
        assertEquals(ProductExpiryStatus.EXPIRING_SOON, janExpiry.evaluateExpiryStatus(decReference, warningWindowDays = 7))
        assertEquals(ProductExpiryStatus.VALID, janExpiry.evaluateExpiryStatus(decReference, warningWindowDays = 3))
    }

    @Test
    fun `missing and invalid expiry dates are handled safely without false expired flags`() {
        val referenceDate = "2026-10-17"

        val nonPerishable = sampleProduct(expiryDate = "   ")
        assertEquals(ProductExpiryStatus.NO_EXPIRY_DATE, nonPerishable.evaluateExpiryStatus(referenceDate))
        assertFalse(nonPerishable.isExpired(referenceDate))
        assertFalse(nonPerishable.isExpiringSoon(referenceDate))
        assertNull(nonPerishable.daysUntilExpiry(referenceDate))

        val invalidCalendar = sampleProduct(expiryDate = "2026-02-30")
        assertEquals(ProductExpiryStatus.INVALID_EXPIRY_DATE, invalidCalendar.evaluateExpiryStatus(referenceDate))
        assertFalse(invalidCalendar.isExpired(referenceDate))
        assertFalse(invalidCalendar.isExpiringSoon(referenceDate))
        assertNull(invalidCalendar.daysUntilExpiry(referenceDate))
    }

    // =========================================================================
    // 2. STOCK QUANTITY & ADJUSTMENT VALIDATION
    // =========================================================================

    @Test
    fun `target stock quantity validator accepts Latin and Arabic numerals including zero and rejects negatives`() {
        // Valid Latin digits
        val validLatin = StockAdjustmentValidator.validateTargetQuantity(
            currentQuantity = 20,
            newQuantityInput = "45",
            reasonInput = "توريد دفعة جديدة"
        )
        assertTrue(validLatin.isValid)
        assertEquals(45, validLatin.parsedTargetQuantity)
        assertEquals(25, validLatin.parsedDelta)

        // Valid Arabic-Indic digits
        val validArabic = StockAdjustmentValidator.validateTargetQuantity(
            currentQuantity = 20,
            newQuantityInput = "١٢",
            reasonInput = "جرد يدوي"
        )
        assertTrue(validArabic.isValid)
        assertEquals(12, validArabic.parsedTargetQuantity)
        assertEquals(-8, validArabic.parsedDelta)

        // Valid zero quantity (out-of-stock transition)
        val validZero = StockAdjustmentValidator.validateTargetQuantity(
            currentQuantity = 5,
            newQuantityInput = "٠",
            reasonInput = "نفاد الكمية بالكامل"
        )
        assertTrue(validZero.isValid)
        assertEquals(0, validZero.parsedTargetQuantity)
        assertEquals(-5, validZero.parsedDelta)

        // Reject negative target quantities
        assertFalse(
            StockAdjustmentValidator.validateTargetQuantity(
                currentQuantity = 10,
                newQuantityInput = "-1",
                reasonInput = "جرد يدوي"
            ).isValid
        )
        assertFalse(
            StockAdjustmentValidator.validateTargetQuantity(
                currentQuantity = 10,
                newQuantityInput = "−٥",
                reasonInput = "جرد يدوي"
            ).isValid
        )

        // Reject blank reason
        val missingReason = StockAdjustmentValidator.validateTargetQuantity(
            currentQuantity = 10,
            newQuantityInput = "15",
            reasonInput = " "
        )
        assertFalse(missingReason.isValid)
        assertNotNull(missingReason.errorArabic)
    }

    @Test
    fun `delta stock adjustment validator enforces non-negative resulting stock and non-zero delta`() {
        // Positive Arabic delta
        val increase = StockAdjustmentValidator.validateDeltaAdjustment(
            currentQuantity = 10,
            deltaInput = "+١٥",
            reasonInput = "توريد مخزون"
        )
        assertTrue(increase.isValid)
        assertEquals(25, increase.parsedTargetQuantity)
        assertEquals(15, increase.parsedDelta)

        // Exact depletion to 0 is allowed
        val depleteToZero = StockAdjustmentValidator.validateDeltaAdjustment(
            currentQuantity = 6,
            deltaInput = "-6",
            reasonInput = "إتلاف منتجات تالفة"
        )
        assertTrue(depleteToZero.isValid)
        assertEquals(0, depleteToZero.parsedTargetQuantity)
        assertEquals(-6, depleteToZero.parsedDelta)

        // Decrease below 0 is rejected
        val belowZero = StockAdjustmentValidator.validateDeltaAdjustment(
            currentQuantity = 6,
            deltaInput = "-7",
            reasonInput = "إتلاف منتجات تالفة"
        )
        assertFalse(belowZero.isValid)
        assertNotNull(belowZero.errorArabic)

        // Zero delta is rejected
        val zeroDelta = StockAdjustmentValidator.validateDeltaAdjustment(
            currentQuantity = 6,
            deltaInput = "0",
            reasonInput = "تعديل"
        )
        assertFalse(zeroDelta.isValid)
    }

    @Test
    fun `product form validator validates stock, threshold, price, and strict ISO expiry dates`() {
        val baseInput = ProductFormInput(
            name = "خوخ محلي طازج",
            nameEn = "Fresh Local Peach",
            category = "فواكه",
            priceSarInput = "١٤.٧٥",
            unit = "كيلوجرام",
            description = "خوخ طازج عالي الجودة من المزارع المحلية",
            stockQuantityInput = "٣٠",
            lowStockThresholdInput = "٦",
            expiryDateInput = "2026-11-10"
        )
        val valid = ProductFormValidator.validate(baseInput)
        assertTrue(valid.isValid)
        assertEquals(1475, valid.parsedPriceHalalas)
        assertEquals(30, valid.parsedStockQuantity)
        assertEquals(6, valid.parsedLowStockThreshold)

        // Negative stock rejected
        assertFalse(ProductFormValidator.validate(baseInput.copy(stockQuantityInput = "-2")).isValid)
        // Negative threshold rejected
        assertFalse(ProductFormValidator.validate(baseInput.copy(lowStockThresholdInput = "-1")).isValid)
        // Impossible calendar date rejected
        assertFalse(ProductFormValidator.validate(baseInput.copy(expiryDateInput = "2026-11-31")).isValid)
    }

    // =========================================================================
    // 3. INVENTORY THRESHOLD FLAGGING & METRICS CONSISTENCY
    // =========================================================================

    @Test
    fun `inventory threshold flags accurately distinguish normal, low-stock, out-of-stock, and archived items`() {
        // Above threshold (stock = 6, threshold = 5) -> neither low-stock nor out-of-stock
        val aboveThreshold = sampleProduct(stockQuantity = 6, lowStockThreshold = 5)
        assertFalse(aboveThreshold.isLowStock)
        assertFalse(aboveThreshold.isOutOfStock)
        assertTrue(aboveThreshold.isPurchasableInStorefront)

        // Exactly at threshold (stock = 5, threshold = 5) -> flagged as low-stock, still purchasable
        val atThreshold = sampleProduct(stockQuantity = 5, lowStockThreshold = 5)
        assertTrue(atThreshold.isLowStock)
        assertFalse(atThreshold.isOutOfStock)
        assertTrue(atThreshold.isPurchasableInStorefront)

        // Minimum positive stock (stock = 1, threshold = 5) -> flagged as low-stock
        val singleUnitLeft = sampleProduct(stockQuantity = 1, lowStockThreshold = 5)
        assertTrue(singleUnitLeft.isLowStock)
        assertFalse(singleUnitLeft.isOutOfStock)
        assertTrue(singleUnitLeft.isPurchasableInStorefront)

        // Zero stock (stock = 0, threshold = 5) -> flagged as out-of-stock, NOT low-stock, NOT purchasable
        val zeroStock = sampleProduct(stockQuantity = 0, lowStockThreshold = 5)
        assertTrue(zeroStock.isOutOfStock)
        assertFalse(zeroStock.isLowStock)
        assertTrue(zeroStock.isVisibleInStorefront)
        assertFalse(zeroStock.isPurchasableInStorefront)

        // Inactive product with stock > 0 -> not visible and not purchasable in storefront
        val hiddenProduct = sampleProduct(stockQuantity = 20, isActive = false, isArchived = false)
        assertFalse(hiddenProduct.isVisibleInStorefront)
        assertFalse(hiddenProduct.isPurchasableInStorefront)

        // Archived product -> never flagged as low-stock, out-of-stock, or expired in operational metrics
        val archivedOut = sampleProduct(
            stockQuantity = 0,
            lowStockThreshold = 5,
            expiryDate = "2020-01-01",
            isActive = false,
            isArchived = true
        )
        assertFalse(archivedOut.isOutOfStock)
        assertFalse(archivedOut.isLowStock)
        assertFalse(archivedOut.isExpired("2026-10-17"))
        assertFalse(archivedOut.isExpiringSoon("2026-10-17", 7))
        assertFalse(archivedOut.isVisibleInStorefront)
        assertFalse(archivedOut.isPurchasableInStorefront)
    }

    @Test
    fun `dashboard metrics aggregate active stock, low-stock, out-of-stock, expired, and expiring-soon counts accurately`() {
        val referenceDate = "2026-10-17"
        val products = listOf(
            // 1. Healthy active product (stock = 40, valid expiry)
            sampleProduct(id = 1, stockQuantity = 40, lowStockThreshold = 5, expiryDate = "2026-12-01", isActive = true),
            // 2. Low-stock & expiring soon within 7 days (stock = 3 <= 5, expiry = 2026-10-20)
            sampleProduct(id = 2, stockQuantity = 3, lowStockThreshold = 5, expiryDate = "2026-10-20", isActive = true),
            // 3. Out-of-stock active product (stock = 0, valid expiry)
            sampleProduct(id = 3, stockQuantity = 0, lowStockThreshold = 4, expiryDate = "2026-11-15", isActive = true),
            // 4. Expired active product (stock = 12, expiry = 2026-10-10)
            sampleProduct(id = 4, stockQuantity = 12, lowStockThreshold = 5, expiryDate = "2026-10-10", isActive = true),
            // 5. Hidden/inactive product (stock = 20, excluded from active stock sum)
            sampleProduct(id = 5, stockQuantity = 20, lowStockThreshold = 5, expiryDate = "2026-12-01", isActive = false),
            // 6. Archived product (excluded from non-archived operational counts)
            sampleProduct(id = 6, stockQuantity = 0, lowStockThreshold = 5, expiryDate = "2026-01-01", isActive = false, isArchived = true)
        )

        val metrics = StoreDashboardMetrics.fromProducts(
            products = products,
            referenceDateIso = referenceDate,
            expiryWarningDays = 7
        )

        assertEquals(5, metrics.totalProductsCount) // IDs 1..5
        assertEquals(4, metrics.activeProductsCount) // IDs 1..4
        assertEquals(1, metrics.inactiveProductsCount) // ID 5
        assertEquals(1, metrics.archivedProductsCount) // ID 6
        assertEquals(55, metrics.totalAvailableStockUnits) // Active stock: 40 + 3 + 0 + 12 = 55
        assertEquals(1, metrics.lowStockCount) // ID 2
        assertEquals(1, metrics.outOfStockCount) // ID 3
        assertEquals(1, metrics.expiredCount) // ID 4
        assertEquals(1, metrics.expiringSoonCount) // ID 2
    }

    @Test
    fun `bilingual inventory search matches Arabic and English product names with Alef and case normalization`() {
        val products = listOf(
            sampleProduct(id = 1).copy(name = "تفاح أحمر عضوي", nameEn = "Organic Red Apple", category = "فواكه"),
            sampleProduct(id = 2).copy(name = "أفوكادو هاس ناضج", nameEn = "Ripe Hass Avocado", category = "فواكه"),
            sampleProduct(id = 3).copy(name = "فراولة القصيم", nameEn = "Qassim Strawberries", category = "فواكه")
        )

        // 1. Arabic query with normalized Alef ('افوكادو' matches 'أفوكادو هاس ناضج')
        val arabicMatches = com.example.ui.viewmodel.InventoryViewModel.filterInventoryProducts(
            products = products,
            rawQuery = "افوكادو",
            category = "الكل",
            status = com.example.ui.viewmodel.StoreProductStatusFilter.ALL,
            referenceDateIso = "2026-10-17",
            expiryWarningDays = 7
        )
        assertEquals(1, arabicMatches.size)
        assertEquals(2, arabicMatches.first().id)

        // 2. Arabic query with Teh Marbuta vs Heh ('فراوله' matches 'فراولة القصيم')
        val tehMarbutaMatches = com.example.ui.viewmodel.InventoryViewModel.filterInventoryProducts(
            products = products,
            rawQuery = "فراوله",
            category = "الكل",
            status = com.example.ui.viewmodel.StoreProductStatusFilter.ALL,
            referenceDateIso = "2026-10-17",
            expiryWarningDays = 7
        )
        assertEquals(1, tehMarbutaMatches.size)
        assertEquals(3, tehMarbutaMatches.first().id)

        // 3. Case-insensitive English query ('apple' matches 'Organic Red Apple')
        val englishMatches = com.example.ui.viewmodel.InventoryViewModel.filterInventoryProducts(
            products = products,
            rawQuery = "  apple ",
            category = "الكل",
            status = com.example.ui.viewmodel.StoreProductStatusFilter.ALL,
            referenceDateIso = "2026-10-17",
            expiryWarningDays = 7
        )
        assertEquals(1, englishMatches.size)
        assertEquals(1, englishMatches.first().id)
    }
}

