package com.example

import com.example.data.local.model.Product
import com.example.data.repository.ProductRepository
import com.example.ui.screens.DiscountedProduct
import com.example.ui.screens.OfferPriceCalculator
import com.example.ui.screens.ProductOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JUnit test suite verifying the Offers screen catalog filtering,
 * active discount eligibility, and current vs. original price calculation logic.
 */
class OffersScreenAndDiscountCalculationTest {

    @Test
    fun offerPriceCalculator_computesAccurateDiscountPercentAndSavings() {
        // 1. Exact percentage & savings math for catalog offers
        // Tomato: 375 halalas (3.75 SAR) vs 500 halalas (5.00 SAR) -> 25% off, 125 halalas (1.25 SAR) saved
        assertEquals(25, OfferPriceCalculator.calculateDiscountPercent(375, 500))
        assertEquals(125, OfferPriceCalculator.calculateSavingsHalalas(375, 500))

        // Qassim Dates: 2400 halalas (24.00 SAR) vs 3000 halalas (30.00 SAR) -> 20% off, 600 halalas (6.00 SAR) saved
        assertEquals(20, OfferPriceCalculator.calculateDiscountPercent(2400, 3000))
        assertEquals(600, OfferPriceCalculator.calculateSavingsHalalas(2400, 3000))

        // Gala Apple: 650 halalas (6.50 SAR) vs 800 halalas (8.00 SAR) -> 19% off, 150 halalas (1.50 SAR) saved
        assertEquals(19, OfferPriceCalculator.calculateDiscountPercent(650, 800))
        assertEquals(150, OfferPriceCalculator.calculateSavingsHalalas(650, 800))

        // Discounted price calculation from original price + percentage
        assertEquals(375, OfferPriceCalculator.calculateDiscountedPriceHalalas(500, 25))
        assertEquals(2400, OfferPriceCalculator.calculateDiscountedPriceHalalas(3000, 20))

        // 2. Rejects non-discounted or invalid price pairs
        assertEquals(0, OfferPriceCalculator.calculateDiscountPercent(500, 500))
        assertEquals(0, OfferPriceCalculator.calculateDiscountPercent(600, 500))
        assertEquals(0, OfferPriceCalculator.calculateSavingsHalalas(600, 500))
        assertEquals(0, OfferPriceCalculator.calculateDiscountPercent(0, 500))
        assertEquals(0, OfferPriceCalculator.calculateDiscountPercent(-100, 500))
    }

    @Test
    fun filterActiveOfferProducts_includesOnlyActiveDiscountedItemsAndFormatsPrices() {
        val activeOffers = OfferPriceCalculator.filterActiveOfferProducts(
            products = ProductRepository.initialProducts
        )
        val activeById: Map<Int, DiscountedProduct> = activeOffers.associateBy { it.product.id }

        // Products 1, 3, 4, 6, 7, 9, 10 have active valid offers
        assertEquals(setOf(1, 3, 4, 6, 7, 9, 10), activeById.keys)

        // Product 2 (Banana) and 5 (Carrot) have no discount; Product 8 (Cucumber) is expired
        assertFalse(activeById.containsKey(2))
        assertFalse(activeById.containsKey(5))
        assertFalse(activeById.containsKey(8))

        // Verify formatted current price vs original price and savings for Tomato (id = 3)
        val tomatoOffer = activeById.getValue(3)
        assertEquals("3.75", tomatoOffer.formattedCurrentPriceSar())
        assertEquals("5.00", tomatoOffer.formattedOriginalPriceSar())
        assertEquals("1.25", tomatoOffer.formattedSavingsSar())
        assertEquals(25, tomatoOffer.discountPercent)

        // Verify formatted current price vs original price and savings for Dates (id = 7)
        val datesOffer = activeById.getValue(7)
        assertEquals("24.00", datesOffer.formattedCurrentPriceSar())
        assertEquals("30.00", datesOffer.formattedOriginalPriceSar())
        assertEquals("6.00", datesOffer.formattedSavingsSar())
        assertEquals(20, datesOffer.discountPercent)
    }

    @Test
    fun hasValidActiveDiscount_rejectsExpiredOffersInactiveProductsAndInvalidPrices() {
        val validApple = ProductRepository.initialProducts.first { it.id == 1 }

        // 1. Expired offer date is rejected
        val expiredOffer = ProductOffer(
            productId = 1,
            originalPriceHalalas = 900,
            validUntilIso = "2026-10-01", // Before default reference date 2026-10-17
            isActiveOffer = true
        )
        assertFalse(OfferPriceCalculator.hasValidActiveDiscount(validApple, expiredOffer))

        // 2. Disabled offer flag is rejected
        val disabledOffer = ProductOffer(
            productId = 1,
            originalPriceHalalas = 900,
            validUntilIso = "2026-12-31",
            isActiveOffer = false
        )
        assertFalse(OfferPriceCalculator.hasValidActiveDiscount(validApple, disabledOffer))

        // 3. Offer with originalPriceHalalas <= product.priceHalalas is rejected
        val nonDiscountOffer = ProductOffer(
            productId = 1,
            originalPriceHalalas = validApple.priceHalalas,
            validUntilIso = "2026-12-31",
            isActiveOffer = true
        )
        assertFalse(OfferPriceCalculator.hasValidActiveDiscount(validApple, nonDiscountOffer))

        // 4. Archived or inactive product is rejected even with a valid offer
        val validOffer = OfferPriceCalculator.DEFAULT_CATALOG_OFFERS.getValue(1)
        assertFalse(
            OfferPriceCalculator.hasValidActiveDiscount(
                validApple.copy(isActive = false),
                validOffer
            )
        )
        assertFalse(
            OfferPriceCalculator.hasValidActiveDiscount(
                validApple.copy(isArchived = true),
                validOffer
            )
        )
    }
}
