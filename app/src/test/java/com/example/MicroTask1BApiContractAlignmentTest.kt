package com.example

import com.example.data.api.FreshRetrofitClient
import com.example.data.api.model.ApiErrorDto
import com.example.data.api.model.CategoryDto
import com.example.data.api.model.OrderItemDto
import com.example.data.api.model.OrderItemRequest
import com.example.data.api.model.OrderRequest
import com.example.data.api.model.OrderResponse
import com.example.data.api.model.OrderStatusHistoryDto
import com.example.data.api.model.ProductDto
import com.example.data.local.model.Product
import com.example.data.repository.ProductRepository
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Automated verification suite for Microtask 1B:
 * 1. Deserializes ProductDto from real Django ProductSerializer JSON output.
 * 2. Deserializes CategoryDto from real Django CategorySerializer JSON output.
 * 3. Handles optional fields and null values safely (barcode = null, effective_price_halalas = null, etc.).
 * 4. Preserves exact integer precision for `price_halalas`, `effective_price_halalas`, `total_halalas`,
 *    and `quantity_milli` (never converting monetary halalas or milli-quantities to Float/Double).
 * 5. Verifies exact field-name alignment against actual Django `serializers.py` source files on disk.
 */
class MicroTask1BApiContractAlignmentTest {

    private val json = FreshRetrofitClient.networkJson

    private fun resolveRepoFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("..", relativePath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate repository file: $relativePath")
    }

    @Test
    fun deserializeCategoryDto_fromDjangoCategorySerializerJson() {
        val djangoCategoryJson = """
            {
                "id": 1,
                "name": "فواكه وخضروات",
                "display_order": 2,
                "is_active": true
            }
        """.trimIndent()

        val dto = json.decodeFromString<CategoryDto>(djangoCategoryJson)
        assertEquals(1, dto.id)
        assertEquals("فواكه وخضروات", dto.name)
        assertEquals(2, dto.displayOrder)
        assertTrue(dto.isActive)
    }

    @Test
    fun deserializeProductDto_fromDjangoProductSerializerJson_withBranchOverride() {
        val djangoProductJson = """
            {
                "id": 10,
                "sku": "SKU-TOMATO-KG",
                "barcode": "6281000000011",
                "name": "طماطم محمية بلدي",
                "category": {
                    "id": 2,
                    "name": "خضروات",
                    "display_order": 1,
                    "is_active": true
                },
                "price_halalas": 795,
                "effective_price_halalas": 695,
                "is_available_in_branch": true,
                "description": "طماطم طازجة محلية منتجة في بيوت محمية بالمملكة",
                "image_url": "tomato",
                "unit": "كيلوجرام",
                "is_fractional_allowed": true,
                "is_active": true
            }
        """.trimIndent()

        val dto = json.decodeFromString<ProductDto>(djangoProductJson)
        assertEquals(10, dto.id)
        assertEquals("SKU-TOMATO-KG", dto.sku)
        assertEquals("6281000000011", dto.barcode)
        assertEquals("طماطم محمية بلدي", dto.name)
        assertEquals(2, dto.category.id)
        assertEquals("خضروات", dto.category.name)
        assertEquals(795L, dto.priceHalalas)
        assertEquals(695L, dto.effectivePriceHalalas)
        assertEquals(695L, dto.resolvedPriceHalalas)
        assertTrue(dto.isAvailableInBranch)
        assertEquals("طماطم طازجة محلية منتجة في بيوت محمية بالمملكة", dto.description)
        assertEquals("tomato", dto.imageUrl)
        assertEquals("كيلوجرام", dto.unit)
        assertTrue(dto.isFractionalAllowed)
        assertTrue(dto.isActive)
    }

    @Test
    fun deserializeProductAndCategory_handlesNullAndOptionalFieldsSafely() {
        // In Django Product model: barcode is nullable (`null=True`), description & image_url can be blank,
        // and effective_price_halalas may be null or equal to price_halalas.
        val nullableAndMinimalProductJson = """
            {
                "id": 25,
                "sku": "SKU-MINT-BUNDLE",
                "barcode": null,
                "name": "نعناع حساوي طازج",
                "category": {
                    "id": 3,
                    "name": "ورقيات"
                },
                "price_halalas": 150,
                "effective_price_halalas": null,
                "description": null,
                "image_url": null,
                "unit": "ربطة",
                "extra_unrecognized_server_field": "ignored_safely"
            }
        """.trimIndent()

        val dto = json.decodeFromString<ProductDto>(nullableAndMinimalProductJson)
        assertEquals(25, dto.id)
        assertEquals("SKU-MINT-BUNDLE", dto.sku)
        assertNull(dto.barcode)
        assertEquals(3, dto.category.id)
        assertEquals("ورقيات", dto.category.name)
        assertEquals(0, dto.category.displayOrder)
        assertTrue(dto.category.isActive)
        assertEquals(150L, dto.priceHalalas)
        assertNull(dto.effectivePriceHalalas)
        assertEquals(150L, dto.resolvedPriceHalalas)
        assertEquals("", dto.description)
        assertEquals("", dto.imageUrl)
        assertEquals("ربطة", dto.unit)
        assertFalse(dto.isFractionalAllowed)
        assertTrue(dto.isActive)
    }

    @Test
    fun halalasAndQuantityMilli_preserveExactIntegerPrecisionWithoutFloatConversion() {
        // Test large 64-bit halala & milli-unit integers and fractional milli-units (e.g., 1333 milli = 1.333 kg)
        val checkoutRequest = OrderRequest(
            branchId = 1,
            fulfillmentWarehouseId = 2,
            idempotencyKey = "idem-2026-11-10-001",
            items = listOf(
                OrderItemRequest(productId = 1, quantityMilli = 1333L),
                OrderItemRequest(productId = 2, quantity = 3) // 3 whole units -> 3000L milli-units
            )
        )

        val serializedRequest = json.encodeToString(checkoutRequest)
        val requestJsonObj = json.decodeFromString<JsonObject>(serializedRequest)

        assertEquals(1, requestJsonObj["branch_id"]?.jsonPrimitive?.content?.toInt())
        assertEquals(2, requestJsonObj["fulfillment_warehouse_id"]?.jsonPrimitive?.content?.toInt())
        assertEquals("idem-2026-11-10-001", requestJsonObj["idempotency_key"]?.jsonPrimitive?.content)

        val itemsArray = requestJsonObj["items"]!!.jsonArray
        assertEquals(2, itemsArray.size)
        val firstItem = itemsArray[0].jsonObject
        val secondItem = itemsArray[1].jsonObject
        assertEquals(1, firstItem["product_id"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1333L, firstItem["quantity_milli"]?.jsonPrimitive?.long)
        assertEquals(2, secondItem["product_id"]?.jsonPrimitive?.content?.toInt())
        assertEquals(3000L, secondItem["quantity_milli"]?.jsonPrimitive?.long)
        // Verify serialized JSON contains no decimal points for monetary or quantity fields
        assertFalse(serializedRequest.contains("1.333"))
        assertFalse(serializedRequest.contains("3.0"))

        // Deserialize full Django OrderSerializer response with large integer halalas
        val djangoOrderResponseJson = """
            {
                "id": 501,
                "branch": 1,
                "fulfillment_warehouse": 2,
                "customer_id": "cust-101",
                "items": [
                    {
                        "id": 9001,
                        "product": 1,
                        "product_name_snapshot": "طماطم محميات",
                        "unit_snapshot": "كجم",
                        "quantity_milli": 1333,
                        "unit_price_halalas": 795,
                        "line_total_halalas": 1060
                    },
                    {
                        "id": 9002,
                        "product": 2,
                        "product_name_snapshot": "تمر سكري فاخر",
                        "unit_snapshot": "علبة",
                        "quantity_milli": 3000,
                        "unit_price_halalas": 2500,
                        "line_total_halalas": 7500
                    }
                ],
                "status_history": [
                    {
                        "id": 301,
                        "from_status": null,
                        "to_status": "PENDING",
                        "changed_by_actor_id": "user:101",
                        "reason": "إنشاء الطلب",
                        "changed_at": "2026-11-10T12:00:00Z"
                    }
                ],
                "total_halalas": 8560,
                "timestamp": "2026-11-10T12:00:00Z",
                "updated_at": "2026-11-10T12:00:00Z",
                "status": "PENDING",
                "idempotency_key": "idem-2026-11-10-001",
                "cancellation_restored": false,
                "replayed": false
            }
        """.trimIndent()

        val orderResponse = json.decodeFromString<OrderResponse>(djangoOrderResponseJson)
        assertEquals(501, orderResponse.id)
        assertEquals(1, orderResponse.branchId)
        assertEquals(2, orderResponse.fulfillmentWarehouseId)
        assertEquals("cust-101", orderResponse.customerId)
        assertEquals(8560L, orderResponse.totalHalalas)
        assertEquals("PENDING", orderResponse.status)
        assertEquals("idem-2026-11-10-001", orderResponse.idempotencyKey)
        assertFalse(orderResponse.cancellationRestored)
        assertEquals(false, orderResponse.replayed)
        assertEquals(2, orderResponse.items.size)
        assertEquals(1, orderResponse.items[0].productId)
        assertEquals(1333L, orderResponse.items[0].quantityMilli)
        assertEquals(795L, orderResponse.items[0].unitPriceHalalas)
        assertEquals(1060L, orderResponse.items[0].lineTotalHalalas)
        assertEquals(1, orderResponse.statusHistory.size)
        assertNull(orderResponse.statusHistory[0].fromStatus)
        assertEquals("PENDING", orderResponse.statusHistory[0].toStatus)

        // Verify mergeRemoteProductsPreservingLocalState applies effective_price_halalas while preserving local stock
        val existingLocal = listOf(
            Product(
                id = 1,
                name = "طماطم",
                category = "خضروات",
                priceHalalas = 800,
                description = "وصف",
                imageUrl = "tomato",
                unit = "كجم",
                stockQuantity = 19,
                stockSubUnitsMillis = 18_750,
                lowStockThreshold = 6,
                expiryDate = "2026-12-01"
            )
        )
        val merged = ProductRepository.mergeRemoteProductsPreservingLocalState(
            existingProducts = existingLocal,
            remoteDtos = listOf(
                ProductDto(
                    id = 1,
                    sku = "SKU-TOMATO-KG",
                    name = "طماطم محمية",
                    category = CategoryDto(1, "خضروات"),
                    priceHalalas = 800L,
                    effectivePriceHalalas = 675L,
                    description = "وصف محدث",
                    imageUrl = "tomato",
                    unit = "كجم"
                )
            )
        )
        assertEquals(675, merged.first().priceHalalas)
        assertEquals(19, merged.first().stockQuantity)
        assertEquals(18_750, merged.first().stockSubUnitsMillis)
        assertEquals("2026-12-01", merged.first().expiryDate)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun androidDtoSerialNames_matchActualDjangoSerializersSourceFiles() {
        val productsSerializersSource = resolveRepoFile("backend/apps/products/serializers.py").readText()
        val ordersSerializersSource = resolveRepoFile("backend/apps/orders/serializers.py").readText()

        fun extractSerialNames(descriptor: kotlinx.serialization.descriptors.SerialDescriptor): Set<String> {
            return (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
        }

        // 1. CategoryDto <-> CategorySerializer
        val categorySerialNames = extractSerialNames(CategoryDto.serializer().descriptor)
        val expectedCategoryFields = setOf("id", "name", "display_order", "is_active")
        assertEquals(expectedCategoryFields, categorySerialNames)
        expectedCategoryFields.forEach { field ->
            assertTrue(
                "Django CategorySerializer must define '$field'",
                productsSerializersSource.contains("'$field'")
            )
        }

        // 2. ProductDto <-> ProductSerializer
        val productSerialNames = extractSerialNames(ProductDto.serializer().descriptor)
        val expectedProductFields = setOf(
            "id",
            "sku",
            "barcode",
            "name",
            "category",
            "price_halalas",
            "effective_price_halalas",
            "is_available_in_branch",
            "description",
            "image_url",
            "unit",
            "is_fractional_allowed",
            "is_active"
        )
        assertEquals(expectedProductFields, productSerialNames)
        expectedProductFields.forEach { field ->
            assertTrue(
                "Django ProductSerializer must define '$field'",
                productsSerializersSource.contains("'$field'")
            )
        }

        // 3. OrderItemRequest <-> CheckoutItemRequestSerializer
        val orderItemReqSerialNames = extractSerialNames(OrderItemRequest.serializer().descriptor)
        assertEquals(setOf("product_id", "quantity_milli"), orderItemReqSerialNames)
        orderItemReqSerialNames.forEach { field ->
            assertTrue(
                "Django CheckoutItemRequestSerializer must define '$field'",
                ordersSerializersSource.contains(field)
            )
        }

        // 4. OrderRequest <-> CheckoutRequestSerializer
        val orderReqSerialNames = extractSerialNames(OrderRequest.serializer().descriptor)
        val expectedOrderReqFields = setOf(
            "items",
            "branch_id",
            "fulfillment_warehouse_id",
            "idempotency_key"
        )
        assertEquals(expectedOrderReqFields, orderReqSerialNames)
        expectedOrderReqFields.forEach { field ->
            assertTrue(
                "Django CheckoutRequestSerializer must define '$field'",
                ordersSerializersSource.contains(field)
            )
        }

        // 5. OrderItemDto <-> OrderItemSerializer
        val orderItemDtoSerialNames = extractSerialNames(OrderItemDto.serializer().descriptor)
        val expectedOrderItemDtoFields = setOf(
            "id",
            "product",
            "product_name_snapshot",
            "unit_snapshot",
            "quantity_milli",
            "unit_price_halalas",
            "line_total_halalas"
        )
        assertEquals(expectedOrderItemDtoFields, orderItemDtoSerialNames)
        expectedOrderItemDtoFields.forEach { field ->
            assertTrue(
                "Django OrderItemSerializer must define '$field'",
                ordersSerializersSource.contains("'$field'")
            )
        }

        // 6. OrderStatusHistoryDto <-> OrderStatusHistorySerializer
        val statusHistorySerialNames = extractSerialNames(OrderStatusHistoryDto.serializer().descriptor)
        val expectedHistoryFields = setOf(
            "id",
            "from_status",
            "to_status",
            "changed_by_actor_id",
            "reason",
            "changed_at"
        )
        assertEquals(expectedHistoryFields, statusHistorySerialNames)
        expectedHistoryFields.forEach { field ->
            assertTrue(
                "Django OrderStatusHistorySerializer must define '$field'",
                ordersSerializersSource.contains("'$field'")
            )
        }

        // 7. OrderResponse <-> OrderSerializer
        val orderResponseSerialNames = extractSerialNames(OrderResponse.serializer().descriptor)
        val expectedOrderSerializerFields = setOf(
            "id",
            "branch",
            "fulfillment_warehouse",
            "customer_id",
            "items",
            "status_history",
            "total_halalas",
            "timestamp",
            "updated_at",
            "status",
            "idempotency_key",
            "cancellation_restored"
        )
        expectedOrderSerializerFields.forEach { field ->
            assertTrue(
                "OrderResponse must include '$field'",
                orderResponseSerialNames.contains(field)
            )
            assertTrue(
                "Django OrderSerializer must define '$field'",
                ordersSerializersSource.contains("'$field'")
            )
        }

        // 8. ApiErrorDto supports DRF {"detail": "..."}
        val drfError = json.decodeFromString<ApiErrorDto>("""{"detail": "Authentication credentials were not provided."}""")
        assertEquals("Authentication credentials were not provided.", drfError.resolvedMessage)
    }
}
