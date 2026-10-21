package com.example.data.repository

import com.example.data.api.FreshApiService
import com.example.data.local.dao.ProductDao
import com.example.data.local.dao.StockAdjustmentDao
import com.example.data.local.model.Product
import com.example.data.local.model.StockAdjustment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class StockAdjustmentOutcome {
    data class Success(
        val updatedProduct: Product,
        val adjustment: StockAdjustment
    ) : StockAdjustmentOutcome()

    data class Failure(
        val errorArabic: String
    ) : StockAdjustmentOutcome()
}

class ProductRepository(
    private val productDao: ProductDao,
    private val apiService: FreshApiService,
    private val stockAdjustmentDao: StockAdjustmentDao? = null
) {
    private val stockMutex = Mutex()
    private val fallbackAdjustmentsFlow = MutableStateFlow<List<StockAdjustment>>(emptyList())
    private var nextFallbackAdjustmentId = 1L

    fun getAllProducts(): Flow<List<Product>> = productDao.getAllProducts()

    fun getAllStockAdjustments(): Flow<List<StockAdjustment>> {
        return stockAdjustmentDao?.getAllAdjustments() ?: fallbackAdjustmentsFlow.asStateFlow()
    }

    fun getStockAdjustmentsForProduct(productId: Int): Flow<List<StockAdjustment>> {
        return stockAdjustmentDao?.getAdjustmentsForProduct(productId)
            ?: fallbackAdjustmentsFlow.map { list -> list.filter { it.productId == productId } }
    }

    suspend fun ensureInitialData() {
        withContext(Dispatchers.IO) {
            try {
                if (productDao.count() == 0) {
                    productDao.insertAll(initialProducts)
                }
            } catch (e: Exception) {
                // Ignore DB error
            }
        }
    }

    suspend fun refreshProducts() {
        withContext(Dispatchers.IO) {
            try {
                val dtos = apiService.getProducts()
                val entities = dtos.map { dto ->
                    Product(
                        id = dto.id,
                        name = dto.name,
                        category = dto.category.name,
                        priceHalalas = dto.priceHalalas.toInt(),
                        description = dto.description,
                        imageUrl = dto.imageUrl,
                        unit = dto.unit
                    )
                }
                productDao.insertAll(entities)
            } catch (e: Exception) {
                // Network unavailable; fallback to initial products if local DB is empty
                ensureInitialData()
            }
        }
    }

    suspend fun saveProduct(product: Product) {
        withContext(Dispatchers.IO) {
            stockMutex.withLock {
                productDao.insert(product)
            }
        }
    }

    suspend fun setProductActiveStatus(product: Product, isActive: Boolean): Product {
        return withContext(Dispatchers.IO) {
            stockMutex.withLock {
                val latest = productDao.getAllProducts().first().find { it.id == product.id } ?: product
                val updated = latest.copy(
                    isActive = isActive,
                    isArchived = if (isActive) false else latest.isArchived,
                    updatedAtMillis = System.currentTimeMillis()
                )
                productDao.insert(updated)
                updated
            }
        }
    }

    suspend fun setProductArchivedStatus(product: Product, isArchived: Boolean): Product {
        return withContext(Dispatchers.IO) {
            stockMutex.withLock {
                val latest = productDao.getAllProducts().first().find { it.id == product.id } ?: product
                val updated = latest.copy(
                    isArchived = isArchived,
                    isActive = if (isArchived) false else true,
                    updatedAtMillis = System.currentTimeMillis()
                )
                productDao.insert(updated)
                updated
            }
        }
    }

    /**
     * Performs an atomic, mutex-serialized stock adjustment against the latest stored product state.
     *
     * - Rejects negative target quantities (`newStockQuantity < 0`).
     * - Prevents stale snapshots from overwriting newer concurrent quantities when [expectedPreviousQuantity] is provided.
     * - Persists an audit record in [StockAdjustmentDao] (or in-memory fallback when DAO is not wired in unit tests).
     */
    suspend fun applyValidatedStockAdjustment(
        productId: Int,
        newStockQuantity: Int? = null,
        quantityDelta: Int? = null,
        expectedPreviousQuantity: Int? = null,
        reason: String,
        actorId: String,
        actorName: String,
        actorRole: String,
        timestampMillis: Long = System.currentTimeMillis()
    ): StockAdjustmentOutcome {
        val cleanReason = reason.trim()
        if (cleanReason.length < 2) {
            return StockAdjustmentOutcome.Failure("يرجى تحديد سبب تعديل المخزون.")
        }

        return withContext(Dispatchers.IO) {
            stockMutex.withLock {
                val currentProduct = productDao.getAllProducts().first().find { it.id == productId }
                    ?: return@withLock StockAdjustmentOutcome.Failure("لم يتم العثور على المنتج المطلوب لتعديل مخزونه.")

                if (expectedPreviousQuantity != null && currentProduct.stockQuantity != expectedPreviousQuantity) {
                    return@withLock StockAdjustmentOutcome.Failure(
                        "تعارض في تحديث المخزون: تغيرت الكمية الحالية إلى ${currentProduct.stockQuantity} قبل حفظ التعديل. يرجى المحاولة مجدداً."
                    )
                }

                val targetQuantity = when {
                    newStockQuantity != null -> newStockQuantity
                    quantityDelta != null -> currentProduct.stockQuantity + quantityDelta
                    else -> return@withLock StockAdjustmentOutcome.Failure("يرجى تحديد الكمية الجديدة أو مقدار التغيير.")
                }

                if (targetQuantity < 0) {
                    return@withLock StockAdjustmentOutcome.Failure(
                        "لا يمكن أن تكون كمية المخزون سالبة ($targetQuantity)."
                    )
                }

                val previousMillis = currentProduct.effectiveStockMillis
                val targetMillis = when {
                    newStockQuantity != null -> newStockQuantity * 1000
                    quantityDelta != null -> previousMillis + (quantityDelta * 1000)
                    else -> return@withLock StockAdjustmentOutcome.Failure("يرجى تحديد الكمية الجديدة أو مقدار التغيير.")
                }

                val delta = targetQuantity - currentProduct.stockQuantity
                val ceilWhole = if (targetMillis > 0) kotlin.math.ceil(targetMillis / 1000.0).toInt() else 0
                val updatedProduct = currentProduct.copy(
                    stockQuantity = ceilWhole,
                    stockSubUnitsMillis = targetMillis,
                    updatedAtMillis = timestampMillis
                )
                productDao.insert(updatedProduct)

                val adjustment = StockAdjustment(
                    productId = updatedProduct.id,
                    productName = updatedProduct.name,
                    previousQuantity = currentProduct.stockQuantity,
                    newQuantity = targetQuantity,
                    quantityDelta = delta,
                    reason = cleanReason,
                    actorId = actorId,
                    actorName = actorName,
                    actorRole = actorRole,
                    timestampMillis = timestampMillis,
                    previousQuantityMillis = previousMillis,
                    newQuantityMillis = targetMillis,
                    quantityDeltaMillis = targetMillis - previousMillis
                )

                if (stockAdjustmentDao != null) {
                    val insertedId = stockAdjustmentDao.insertAdjustment(adjustment)
                    StockAdjustmentOutcome.Success(
                        updatedProduct = updatedProduct,
                        adjustment = adjustment.copy(id = insertedId)
                    )
                } else {
                    val assigned = adjustment.copy(id = nextFallbackAdjustmentId++)
                    fallbackAdjustmentsFlow.value = listOf(assigned) + fallbackAdjustmentsFlow.value
                    StockAdjustmentOutcome.Success(
                        updatedProduct = updatedProduct,
                        adjustment = assigned
                    )
                }
            }
        }
    }

    suspend fun updateStockQuantity(product: Product, newStockQuantity: Int): Product {
        if (newStockQuantity < 0) {
            throw IllegalArgumentException("Stock quantity cannot be negative: $newStockQuantity")
        }
        val outcome = applyValidatedStockAdjustment(
            productId = product.id,
            newStockQuantity = newStockQuantity,
            reason = "تحديث مباشر للمخزون",
            actorId = "system-or-manager",
            actorName = "إدارة المتجر",
            actorRole = "STORE_OWNER"
        )
        return when (outcome) {
            is StockAdjustmentOutcome.Success -> outcome.updatedProduct
            is StockAdjustmentOutcome.Failure -> {
                val fallback = product.copy(
                    stockQuantity = newStockQuantity,
                    updatedAtMillis = System.currentTimeMillis()
                )
                saveProduct(fallback)
                fallback
            }
        }
    }

    companion object {
        val SUPPORTED_CATEGORIES = listOf("فواكه", "خضروات", "ورقيات", "تمور")

        val SUPPORTED_UNITS = listOf(
            "كيلوجرام",
            "حبة",
            "علبة ٥٠٠ جم",
            "ربطة",
            "كيس ١ كجم",
            "سلة حبتين"
        )

        val SUPPORTED_ADJUSTMENT_REASONS = listOf(
            "توريد دفعة جديدة للمخزون",
            "جرد يدوي وتصحيح الرصيد",
            "استبعاد أصناف تالفة أو منتهية",
            "تعديل تشغيلي سريع"
        )

        val SUPPORTED_IMAGE_KEYS = listOf(
            "apple" to "تفاح أحمر",
            "banana" to "موز إكوادوري",
            "tomato" to "طماطم محمية",
            "lettuce" to "خس روماني",
            "carrot" to "جزر طازج",
            "orange" to "برتقال أبو صرة",
            "dates" to "تمر سكري",
            "cucumber" to "خيار بلدي",
            "mint" to "نعناع حساوي",
            "avocado" to "أفوكادو هاس"
        )

        val initialProducts = listOf(
            Product(
                id = 1,
                name = "تفاح أحمر جالا طازج",
                nameEn = "Fresh Gala Red Apple",
                category = "فواكه",
                priceHalalas = 650, // 6.50 SAR
                description = "تفاح أحمر إيطالي طازج ومقرمش غني بالفيتامينات ومثالي للوجبات الخفيفة اليومية.",
                imageUrl = "apple",
                unit = "كيلوجرام",
                stockQuantity = 42,
                lowStockThreshold = 8,
                expiryDate = "2026-11-20",
                isActive = true,
                isArchived = false,
                updatedAtMillis = 1760691000000L
            ),
            Product(
                id = 2,
                name = "موز إكوادوري فاخر",
                nameEn = "Premium Ecuadorian Banana",
                category = "فواكه",
                priceHalalas = 450, // 4.50 SAR
                description = "موز أصفر ناضج بدرجة حلاوة طبيعية ممتازة ومصدر رائع للبوتاسيوم والطاقة.",
                imageUrl = "banana",
                unit = "كيلوجرام",
                stockQuantity = 30,
                lowStockThreshold = 6,
                expiryDate = "2026-10-28",
                isActive = true,
                isArchived = false,
                updatedAtMillis = 1760690900000L
            ),
            Product(
                id = 3,
                name = "طماطم محمية بلدي",
                nameEn = "Local Greenhouse Tomato",
                category = "خضروات",
                priceHalalas = 375, // 3.75 SAR
                description = "طماطم طازجة محلية منتجة في بيوت محمية بالمملكة، قوام متماسك وطعم غني.",
                imageUrl = "tomato",
                unit = "كيلوجرام",
                stockQuantity = 50,
                lowStockThreshold = 10,
                expiryDate = "2026-10-30",
                isActive = true,
                isArchived = false,
                updatedAtMillis = 1760690800000L
            ),
            Product(
                id = 4,
                name = "خس روماني عضوي",
                nameEn = "Organic Romaine Lettuce",
                category = "ورقيات",
                priceHalalas = 275, // 2.75 SAR
                description = "خس طازج ومقرمش مقطوف يومياً للسلطات والوجبات الصحية.",
                imageUrl = "lettuce",
                unit = "حبة",
                stockQuantity = 3, // Low stock (<= 5)
                lowStockThreshold = 5,
                expiryDate = "2026-10-24", // Expiring soon within 7 days of 2026-10-17
                isActive = true,
                isArchived = false,
                updatedAtMillis = 1760690700000L
            ),
            Product(
                id = 5,
                name = "جزر طازج سكري",
                nameEn = "Fresh Sweet Carrot",
                category = "خضروات",
                priceHalalas = 350, // 3.50 SAR
                description = "جزر نظيف طازج حلو المذاق، رائع للطهي أو العصير الطازج.",
                imageUrl = "carrot",
                unit = "كيس ١ كجم",
                stockQuantity = 28,
                lowStockThreshold = 5,
                expiryDate = "2026-11-15",
                isActive = true,
                isArchived = false,
                updatedAtMillis = 1760690600000L
            ),
            Product(
                id = 6,
                name = "برتقال أبو صرة للعصير",
                nameEn = "Fresh Navel Orange",
                category = "فواكه",
                priceHalalas = 525, // 5.25 SAR
                description = "برتقال غني بالعصير الطبيعي وفيتامين C، مثالي للإفطار الصباحي.",
                imageUrl = "orange",
                unit = "كيلوجرام",
                stockQuantity = 35,
                lowStockThreshold = 7,
                expiryDate = "2026-11-25",
                isActive = true,
                isArchived = false,
                updatedAtMillis = 1760690500000L
            ),
            Product(
                id = 7,
                name = "تمر سكري القصيم فاخر",
                nameEn = "Premium Qassim Sukkari Dates",
                category = "تمور",
                priceHalalas = 2400, // 24.00 SAR
                description = "تمر سكري مفتل من مزارع القصيم، رطب ومميز بلونه الذهبي ونكهته التراثية.",
                imageUrl = "dates",
                unit = "علبة ٥٠٠ جم",
                stockQuantity = 60,
                lowStockThreshold = 10,
                expiryDate = "2027-03-15",
                isActive = true,
                isArchived = false,
                updatedAtMillis = 1760690400000L
            ),
            Product(
                id = 8,
                name = "خيار بلدي طازج",
                nameEn = "Fresh Local Cucumber",
                category = "خضروات",
                priceHalalas = 320, // 3.20 SAR
                description = "خيار طازج وصغير الحجم، مقرمش ومنعش للسلطات والمقبلات.",
                imageUrl = "cucumber",
                unit = "كيلوجرام",
                stockQuantity = 14,
                lowStockThreshold = 5,
                expiryDate = "2026-10-10", // Expired (< 2026-10-17)
                isActive = true,
                isArchived = false,
                updatedAtMillis = 1760690300000L
            ),
            Product(
                id = 9,
                name = "نعناع حساوي طازج",
                nameEn = "Fresh Hassawi Mint",
                category = "ورقيات",
                priceHalalas = 150, // 1.50 SAR
                description = "نعناع حساوي عطري فواح، مقطوف بعناية للشاي والمشروبات المنعشة.",
                imageUrl = "mint",
                unit = "ربطة",
                stockQuantity = 4, // Low stock (<= 5)
                lowStockThreshold = 5,
                expiryDate = "2026-10-22", // Expiring soon within 7 days of 2026-10-17
                isActive = true,
                isArchived = false,
                updatedAtMillis = 1760690200000L
            ),
            Product(
                id = 10,
                name = "أفوكادو هاس ناضج",
                nameEn = "Ripe Hass Avocado",
                category = "فواكه",
                priceHalalas = 1200, // 12.00 SAR
                description = "أفوكادو كريمي جاهز للأكل، غني بالدهون الصحية ومثالي للتوست والسلطات.",
                imageUrl = "avocado",
                unit = "سلة حبتين",
                stockQuantity = 0, // Out of stock (== 0)
                lowStockThreshold = 4,
                expiryDate = "2026-11-05",
                isActive = true,
                isArchived = false,
                updatedAtMillis = 1760690100000L
            )
        )
    }
}
