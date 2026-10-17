package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.model.Product
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.ActionGreen
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardSurface
import com.example.ui.theme.FreshBackground
import com.example.ui.theme.FreshGreen
import com.example.ui.theme.FreshGreenDark
import com.example.ui.theme.FreshGreenLight
import com.example.ui.theme.TextDarkGreen
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.White

/**
 * Caloric, macronutrient, and vitamin profile for a specific fresh produce item (per 100g serving).
 */
data class ProduceNutritionInfo(
    val caloriesKcal: Int,
    val servingSize: String = "لكل 100 جرام",
    val carbsGrams: String,
    val fiberGrams: String,
    val proteinGrams: String,
    val hydrationPercent: String,
    val vitamins: List<Pair<String, String>>,
    val healthBenefit: String
)

/**
 * Maps each produce item to accurate caloric and vitamin nutrition facts.
 */
fun resolveProduceNutritionInfo(product: Product): ProduceNutritionInfo {
    val key = "${product.imageUrl} ${product.name}".lowercase()
    return when {
        key.contains("apple") || key.contains("تفاح") -> ProduceNutritionInfo(
            caloriesKcal = 52,
            carbsGrams = "13.8 جم",
            fiberGrams = "2.4 جم",
            proteinGrams = "0.3 جم",
            hydrationPercent = "86%",
            vitamins = listOf(
                "فيتامين C" to "14% من الاحتياج اليومي",
                "فيتامين K" to "3% من الاحتياج اليومي",
                "البوتاسيوم" to "107 ملجم",
                "مضادات الأكسدة" to "كيرسيتين وبوليفينول طبيعي"
            ),
            healthBenefit = "غني بالألياف القابلة للذوبان (البكتين) التي تدعم صحة الجهاز الهضمي وتمنح شعوراً بالشبع."
        )
        key.contains("banana") || key.contains("موز") -> ProduceNutritionInfo(
            caloriesKcal = 89,
            carbsGrams = "22.8 جم",
            fiberGrams = "2.6 جم",
            proteinGrams = "1.1 جم",
            hydrationPercent = "75%",
            vitamins = listOf(
                "فيتامين B6" to "33% من الاحتياج اليومي",
                "البوتاسيوم" to "358 ملجم (10% يومياً)",
                "فيتامين C" to "15% من الاحتياج اليومي",
                "المغنيسيوم" to "27 ملجم"
            ),
            healthBenefit = "مصدر مثالي للطاقة السريعة والبوتاسيوم الضروري لصحة العضلات وتوازن ضغط الدم."
        )
        key.contains("tomato") || key.contains("طماطم") -> ProduceNutritionInfo(
            caloriesKcal = 18,
            carbsGrams = "3.9 جم",
            fiberGrams = "1.2 جم",
            proteinGrams = "0.9 جم",
            hydrationPercent = "95%",
            vitamins = listOf(
                "فيتامين C" to "28% من الاحتياج اليومي",
                "فيتامين K" to "10% من الاحتياج اليومي",
                "الليكوبين" to "مضاد أكسدة عالي التركيز",
                "حمض الفوليك (B9)" to "15 ميكروجرام"
            ),
            healthBenefit = "غنية بالليكوبين الطبيعي وفيتامين C لتعزيز صحة القلب ونضارة البشرة والمناعة."
        )
        key.contains("lettuce") || key.contains("خس") -> ProduceNutritionInfo(
            caloriesKcal = 17,
            carbsGrams = "3.3 جم",
            fiberGrams = "2.1 جم",
            proteinGrams = "1.2 جم",
            hydrationPercent = "95%",
            vitamins = listOf(
                "فيتامين A" to "148% من الاحتياج اليومي",
                "فيتامين K" to "85% من الاحتياج اليومي",
                "حمض الفوليك" to "34% من الاحتياج اليومي",
                "الحديد والكالسيوم" to "معادن عضوية أساسية"
            ),
            healthBenefit = "منخفض السعرات الحرارية وغني بفيتامينات A و K لدعم صحة العظام والترطيب اليومي."
        )
        key.contains("carrot") || key.contains("جزر") -> ProduceNutritionInfo(
            caloriesKcal = 41,
            carbsGrams = "9.6 جم",
            fiberGrams = "2.8 جم",
            proteinGrams = "0.9 جم",
            hydrationPercent = "88%",
            vitamins = listOf(
                "فيتامين A (بيتا كاروتين)" to "184% من الاحتياج اليومي",
                "فيتامين K1" to "13% من الاحتياج اليومي",
                "فيتامين B6" to "8% من الاحتياج اليومي",
                "البوتاسيوم" to "320 ملجم"
            ),
            healthBenefit = "غني جداً بالبيتا كاروتين وفيتامين A لتعزيز صحة النظر والمناعة."
        )
        key.contains("orange") || key.contains("برتقال") -> ProduceNutritionInfo(
            caloriesKcal = 47,
            carbsGrams = "11.8 جم",
            fiberGrams = "2.4 جم",
            proteinGrams = "0.9 جم",
            hydrationPercent = "87%",
            vitamins = listOf(
                "فيتامين C" to "88% من الاحتياج اليومي",
                "حمض الفوليك" to "8% من الاحتياج اليومي",
                "الثيامين (B1)" to "7% من الاحتياج اليومي",
                "البوتاسيوم" to "181 ملجم"
            ),
            healthBenefit = "تركيز استثنائي من فيتامين C الطبيعي ومضادات الأكسدة لتقوية المناعة وامتصاص الحديد."
        )
        key.contains("date") || key.contains("تمر") -> ProduceNutritionInfo(
            caloriesKcal = 277,
            carbsGrams = "75.0 جم",
            fiberGrams = "6.7 جم",
            proteinGrams = "1.8 جم",
            hydrationPercent = "21%",
            vitamins = listOf(
                "البوتاسيوم" to "696 ملجم (20% يومياً)",
                "المغنيسيوم" to "54 ملجم (14% يومياً)",
                "فيتامين B6" to "15% من الاحتياج اليومي",
                "النحاس والحديد" to "18% من الاحتياج اليومي"
            ),
            healthBenefit = "غذاء متكامل غني بالألياف والمعادن الأساسية والسكريات الطبيعية سريعة الامتصاص."
        )
        key.contains("cucumber") || key.contains("خيار") -> ProduceNutritionInfo(
            caloriesKcal = 15,
            carbsGrams = "3.6 جم",
            fiberGrams = "0.5 جم",
            proteinGrams = "0.7 جم",
            hydrationPercent = "96%",
            vitamins = listOf(
                "فيتامين K" to "16% من الاحتياج اليومي",
                "فيتامين C" to "4% من الاحتياج اليومي",
                "البوتاسيوم" to "147 ملجم",
                "المغنيسيوم" to "13 ملجم"
            ),
            healthBenefit = "يحتوي على 96% ماء طبيعي مع أملاح معدنية منعشة للترطيب الفوري وتخفيف الحرارة."
        )
        key.contains("mint") || key.contains("نعناع") -> ProduceNutritionInfo(
            caloriesKcal = 44,
            carbsGrams = "8.4 جم",
            fiberGrams = "6.8 جم",
            proteinGrams = "3.3 جم",
            hydrationPercent = "85%",
            vitamins = listOf(
                "فيتامين A" to "84% من الاحتياج اليومي",
                "الحديد" to "66% من الاحتياج اليومي",
                "المنغنيز" to "55% من الاحتياج اليومي",
                "فيتامين C" to "22% من الاحتياج اليومي"
            ),
            healthBenefit = "غني بالزيوت العطرية الطبيعية والمنثول المهدئ للجهاز الهضمي والمنعش للتنفس."
        )
        key.contains("avocado") || key.contains("أفوكادو") || key.contains("افوكادو") -> ProduceNutritionInfo(
            caloriesKcal = 160,
            carbsGrams = "8.5 جم",
            fiberGrams = "6.7 جم",
            proteinGrams = "2.0 جم",
            hydrationPercent = "73%",
            vitamins = listOf(
                "فيتامين K" to "26% من الاحتياج اليومي",
                "حمض الفوليك (B9)" to "20% من الاحتياج اليومي",
                "فيتامين E و C" to "14% من الاحتياج اليومي",
                "البوتاسيوم" to "485 ملجم (أعلى من الموز)"
            ),
            healthBenefit = "غني بالدهون الأحادية الصحية للقلب والألياف التي تساعد على امتصاص الفيتامينات الذائبة."
        )
        else -> ProduceNutritionInfo(
            caloriesKcal = 45,
            carbsGrams = "10.0 جم",
            fiberGrams = "2.5 جم",
            proteinGrams = "1.0 جم",
            hydrationPercent = "88%",
            vitamins = listOf(
                "فيتامين C" to "25% من الاحتياج اليومي",
                "فيتامين A" to "20% من الاحتياج اليومي",
                "البوتاسيوم" to "210 ملجم"
            ),
            healthBenefit = "محصول طبيعي طازج غني بالفيتامينات والألياف الغذائية المفيدة للصحة اليومية."
        )
    }
}

/**
 * Modal Bottom Sheet displaying caloric, macronutrient, and vitamin details for a selected produce item.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionInfoBottomSheet(
    product: Product,
    onDismiss: () -> Unit,
    onAddToCart: (Product) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val nutrition = remember(product) { resolveProduceNutritionInfo(product) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.testTag("nutrition_bottom_sheet")
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                // Header Row: Product Visual, Title, Category & Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        ProductVisual(
                            imageUrl = product.imageUrl,
                            size = 64.dp,
                            backgroundColor = FreshBackground,
                            contentDescription = product.name
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = FreshGreenLight,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "المعلومات الغذائية",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = FreshGreen,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Text(
                                    text = nutrition.servingSize,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = product.name,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(FreshBackground)
                            .testTag("nutrition_sheet_close_button")
                            .semantics { contentDescription = "إغلاق نافذة المعلومات الغذائية" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = TextDarkGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Highlighted Caloric & Macronutrient Grid
                Surface(
                    color = FreshBackground,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = AccentOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "السعرات الحرارية",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextDarkGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Surface(
                                color = AccentOrange.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "${nutrition.caloriesKcal} سعرة حرارية (kcal)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AccentOrange,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 5.dp)
                                        .testTag("nutrition_calories_value")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = BorderSubtle)
                        Spacer(modifier = Modifier.height(12.dp))

                        // 4 Macro Pillars
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MacroNutrientPill(
                                label = "الكربوهيدرات",
                                value = nutrition.carbsGrams,
                                modifier = Modifier.weight(1f)
                            )
                            MacroNutrientPill(
                                label = "الألياف",
                                value = nutrition.fiberGrams,
                                modifier = Modifier.weight(1f)
                            )
                            MacroNutrientPill(
                                label = "البروتين",
                                value = nutrition.proteinGrams,
                                modifier = Modifier.weight(1f)
                            )
                            MacroNutrientPill(
                                label = "الترطيب",
                                value = nutrition.hydrationPercent,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Vitamins & Minerals Section
                Text(
                    text = "الفيتامينات والمعادن الأساسية",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextDarkGreen,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    nutrition.vitamins.forEach { (vitaminName, dailyValue) ->
                        Surface(
                            color = CardSurface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = ActionGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = vitaminName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextDarkGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Surface(
                                    color = FreshGreenLight,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = dailyValue,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = FreshGreenDark,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Health Benefit Summary Box
                Surface(
                    color = FreshGreenLight.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "💡 الفائدة الصحية: ${nutrition.healthBenefit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FreshGreenDark,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Bottom CTA Button to Add Directly to Cart
                Button(
                    onClick = {
                        onAddToCart(product)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActionGreen,
                        contentColor = White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("nutrition_sheet_add_to_cart")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "إضافة ${product.name} إلى السلة",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun MacroNutrientPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = CardSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = FreshGreen,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}
