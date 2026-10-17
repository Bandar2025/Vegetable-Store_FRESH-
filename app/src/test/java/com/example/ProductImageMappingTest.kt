package com.example

import com.example.data.repository.ProductRepository
import com.example.ui.components.resolveProduceDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductImageMappingTest {

    @Test
    fun testInitialProductsMapping() {
        val expectedMappings = mapOf(
            "apple" to R.drawable.img_product_apple,
            "banana" to R.drawable.img_product_banana,
            "tomato" to R.drawable.img_product_tomato,
            "lettuce" to R.drawable.img_product_lettuce,
            "carrot" to R.drawable.img_product_carrot,
            "orange" to R.drawable.img_product_orange,
            "dates" to R.drawable.img_product_dates,
            "cucumber" to R.drawable.img_product_cucumber,
            "mint" to R.drawable.img_product_mint,
            "avocado" to R.drawable.img_product_avocado
        )

        for (product in ProductRepository.initialProducts) {
            val expectedRes = expectedMappings[product.imageUrl]
            val resolvedRes = resolveProduceDrawable(product.imageUrl)
            assertEquals("Product ${product.name} with key '${product.imageUrl}' must resolve correctly", expectedRes, resolvedRes)
            assertNotEquals("Product ${product.name} must not resolve to placeholder", R.drawable.ic_produce_placeholder, resolvedRes)
        }
    }

    @Test
    fun testNullEmptyAndBlankFallback() {
        assertEquals(R.drawable.ic_produce_placeholder, resolveProduceDrawable(null))
        assertEquals(R.drawable.ic_produce_placeholder, resolveProduceDrawable(""))
        assertEquals(R.drawable.ic_produce_placeholder, resolveProduceDrawable("   "))
    }

    @Test
    fun testUnknownProduceFallback() {
        assertEquals(R.drawable.ic_produce_placeholder, resolveProduceDrawable("dragonfruit"))
        assertEquals(R.drawable.ic_produce_placeholder, resolveProduceDrawable("unknown_item_99"))
        assertEquals(R.drawable.ic_produce_placeholder, resolveProduceDrawable("random_text"))
    }

    @Test
    fun testCaseAndWhitespaceInsensitivity() {
        assertEquals(R.drawable.img_product_apple, resolveProduceDrawable("  APPLE  "))
        assertEquals(R.drawable.img_product_banana, resolveProduceDrawable("Banana"))
        assertEquals(R.drawable.img_product_tomato, resolveProduceDrawable("ToMaTo"))
    }

    @Test
    fun testFilenameAndExtensionVariations() {
        assertEquals(R.drawable.img_product_apple, resolveProduceDrawable("img_product_apple.webp"))
        assertEquals(R.drawable.img_product_carrot, resolveProduceDrawable("carrot.jpg"))
        assertEquals(R.drawable.img_product_cucumber, resolveProduceDrawable("cucumber.png"))
        assertEquals(R.drawable.img_product_lettuce, resolveProduceDrawable("lettuce_head.webp"))
    }

    @Test
    fun testArabicKeywordMapping() {
        assertEquals(R.drawable.img_product_apple, resolveProduceDrawable("تفاح أحمر"))
        assertEquals(R.drawable.img_product_banana, resolveProduceDrawable("موز إكوادوري"))
        assertEquals(R.drawable.img_product_tomato, resolveProduceDrawable("طماطم محمية"))
        assertEquals(R.drawable.img_product_lettuce, resolveProduceDrawable("خس روماني"))
        assertEquals(R.drawable.img_product_carrot, resolveProduceDrawable("جزر سكري"))
        assertEquals(R.drawable.img_product_orange, resolveProduceDrawable("برتقال عصير"))
        assertEquals(R.drawable.img_product_dates, resolveProduceDrawable("تمر سكري"))
        assertEquals(R.drawable.img_product_cucumber, resolveProduceDrawable("خيار بلدي"))
        assertEquals(R.drawable.img_product_mint, resolveProduceDrawable("نعناع حساوي"))
        assertEquals(R.drawable.img_product_avocado, resolveProduceDrawable("أفوكادو هاس"))
    }

    @Test
    fun testDistinctDrawableResources() {
        val drawables = setOf(
            R.drawable.img_product_apple,
            R.drawable.img_product_banana,
            R.drawable.img_product_tomato,
            R.drawable.img_product_lettuce,
            R.drawable.img_product_carrot,
            R.drawable.img_product_orange,
            R.drawable.img_product_dates,
            R.drawable.img_product_cucumber,
            R.drawable.img_product_mint,
            R.drawable.img_product_avocado,
            R.drawable.ic_produce_placeholder
        )
        // All 10 product images plus 1 placeholder must have unique non-zero resource IDs
        assertEquals(11, drawables.size)
        drawables.forEach {
            assertTrue("Drawable resource ID must be valid positive int", it > 0)
        }
    }

    @Test
    fun testRemoteUrlMapping() {
        // When a remote URL contains a known produce keyword, fallback resolves to that produce
        assertEquals(R.drawable.img_product_apple, resolveProduceDrawable("https://example.com/fresh/apple.jpg"))
        assertEquals(R.drawable.img_product_banana, resolveProduceDrawable("http://api.fresh-market.sa/media/banana.png"))
        // When a remote URL has no known produce keyword, it resolves to placeholder
        assertEquals(R.drawable.ic_produce_placeholder, resolveProduceDrawable("https://example.com/unknown/item123.jpg"))
    }
}
