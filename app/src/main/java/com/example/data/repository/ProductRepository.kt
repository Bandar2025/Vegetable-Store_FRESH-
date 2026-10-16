package com.example.data.repository

import com.example.data.local.dao.ProductDao
import com.example.data.local.model.Product
import com.example.data.api.FreshApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProductRepository(
    private val productDao: ProductDao,
    private val apiService: FreshApiService
) {
    fun getAllProducts(): Flow<List<Product>> = productDao.getAllProducts()

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

    companion object {
        val initialProducts = listOf(
            Product(
                id = 1,
                name = "تفاح أحمر جالا طازج",
                category = "فواكه",
                priceHalalas = 650, // 6.50 SAR
                description = "تفاح أحمر إيطالي طازج ومقرمش غني بالفيتامينات ومثالي للوجبات الخفيفة اليومية.",
                imageUrl = "apple",
                unit = "كيلوجرام"
            ),
            Product(
                id = 2,
                name = "موز إكوادوري فاخر",
                category = "فواكه",
                priceHalalas = 450, // 4.50 SAR
                description = "موز أصفر ناضج بدرجة حلاوة طبيعية ممتازة ومصدر رائع للبوتاسيوم والطاقة.",
                imageUrl = "banana",
                unit = "كيلوجرام"
            ),
            Product(
                id = 3,
                name = "طماطم محمية بلدي",
                category = "خضروات",
                priceHalalas = 375, // 3.75 SAR
                description = "طماطم طازجة محلية منتجة في بيوت محمية بالمملكة، قوام متماسك وطعم غني.",
                imageUrl = "tomato",
                unit = "كيلوجرام"
            ),
            Product(
                id = 4,
                name = "خس روماني عضوي",
                category = "ورقيات",
                priceHalalas = 275, // 2.75 SAR
                description = "خس طازج ومقرمش مقطوف يومياً للسلطات والوجبات الصحية.",
                imageUrl = "lettuce",
                unit = "حبة"
            ),
            Product(
                id = 5,
                name = "جزر طازج سكري",
                category = "خضروات",
                priceHalalas = 350, // 3.50 SAR
                description = "جزر نظيف طازج حلو المذاق، رائع للطهي أو العصير الطازج.",
                imageUrl = "carrot",
                unit = "كيس ١ كجم"
            ),
            Product(
                id = 6,
                name = "برتقال أبو صرة للعصير",
                category = "فواكه",
                priceHalalas = 525, // 5.25 SAR
                description = "برتقال غني بالعصير الطبيعي وفيتامين C، مثالي للإفطار الصباحي.",
                imageUrl = "orange",
                unit = "كيلوجرام"
            ),
            Product(
                id = 7,
                name = "تمر سكري القصيم فاخر",
                category = "تمور",
                priceHalalas = 2400, // 24.00 SAR
                description = "تمر سكري مفتل من مزارع القصيم، رطب ومميز بلونه الذهبي ونكهته التراثية.",
                imageUrl = "dates",
                unit = "علبة ٥٠٠ جم"
            ),
            Product(
                id = 8,
                name = "خيار بلدي طازج",
                category = "خضروات",
                priceHalalas = 320, // 3.20 SAR
                description = "خيار طازج وصغير الحجم، مقرمش ومنعش للسلطات والمقبلات.",
                imageUrl = "cucumber",
                unit = "كيلوجرام"
            ),
            Product(
                id = 9,
                name = "نعناع حساوي طازج",
                category = "ورقيات",
                priceHalalas = 150, // 1.50 SAR
                description = "نعناع حساوي عطري فواح، مقطوف بعناية للشاي والمشروبات المنعشة.",
                imageUrl = "mint",
                unit = "ربطة"
            ),
            Product(
                id = 10,
                name = "أفوكادو هاس ناضج",
                category = "فواكه",
                priceHalalas = 1200, // 12.00 SAR
                description = "أفوكادو كريمي جاهز للأكل، غني بالدهون الصحية ومثالي للتوست والسلطات.",
                imageUrl = "avocado",
                unit = "سلة حبتين"
            )
        )
    }
}
