package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardSurface
import com.example.ui.theme.FreshGreen
import com.example.ui.theme.FreshGreenDark
import com.example.ui.theme.FreshGreenLight
import com.example.ui.theme.TextDarkGreen
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.White

/**
 * Data model representing a seasonal produce storage tip ("Pro Tip").
 */
data class SeasonalProduceTip(
    val id: Int,
    val produceName: String,
    val seasonBadge: String,
    val title: String,
    val tipDescription: String,
    val storageZone: String,
    val shelfLifeGain: String,
    val imageKey: String,
    val targetCategory: String
)

/**
 * Curated seasonal storage tips tailored to Saudi households and local fresh produce.
 */
val DefaultSeasonalProTips = listOf(
    SeasonalProduceTip(
        id = 1,
        produceName = "الورقيات والنعناع الحساوي",
        seasonBadge = "نصيحة الموسم • الورقيات",
        title = "لفّ الورقيات بمنشفة ورقية لحمايتها من الذبول",
        tipDescription = "ضع منشفة ورقية جافة مع الخس والنعناع داخل حافظة مغلقة في درج الخضار؛ لامتصاص الرطوبة الزائدة ومنع اسوداد الأوراق.",
        storageZone = "درج الثلاجة (4° مئوية)",
        shelfLifeGain = "+10 أيام قرمشة",
        imageKey = "mint",
        targetCategory = "ورقيات"
    ),
    SeasonalProduceTip(
        id = 2,
        produceName = "تمور القصيم السكرية",
        seasonBadge = "نصيحة الخبراء • التمور",
        title = "حافظ على ليونة التمر السكري ونكهته الملكية",
        tipDescription = "احفظ التمور في عبوة زجاجية أو بلاستيكية محكمة الإغلاق بعيداً عن الأطعمة ذات الروائح النفاذة، وفي التبريد المعتدل للأجواء الحارة.",
        storageZone = "عبوة محكمة • تبريد خفيف",
        shelfLifeGain = "+6 أشهر طراوة",
        imageKey = "dates",
        targetCategory = "تمور"
    ),
    SeasonalProduceTip(
        id = 3,
        produceName = "الطماطم والأفوكادو",
        seasonBadge = "سر النكهة • الفواكه والخضار",
        title = "لا تبرّد الطماطم والأفوكادو قبل اكتمال النضج",
        tipDescription = "التبريد المبكر يوقف إنزيمات النضج الطبيعية ويقلل الطعم؛ اتركها في حرارة الغرفة بعيداً عن الشمس حتى تلين قليلاً ثم انقلها للثلاجة.",
        storageZone = "حرارة الغرفة ثم التبريد",
        shelfLifeGain = "+5 أيام نكهة غنية",
        imageKey = "avocado",
        targetCategory = "فواكه"
    ),
    SeasonalProduceTip(
        id = 4,
        produceName = "التفاح والموز والحمضيات",
        seasonBadge = "إرشاد التخزين الذكي",
        title = "افصل التفاح والموز عن الخضروات الحساسة",
        tipDescription = "يطلق التفاح والموز غاز الإيثيلين الطبيعي الذي يسرّع نضج الخيار والورقيات المجاورة؛ خزّن كل صنف في سلة مستقلة جيداً.",
        storageZone = "سلة منفصلة جيدة التهوية",
        shelfLifeGain = "+14 يوم طزاجة",
        imageKey = "apple",
        targetCategory = "فواكه"
    )
)

/**
 * Interactive 'Pro Tips' card component on the Home screen providing seasonal storage tips
 * for fresh produce so users can keep their groceries fresh longer.
 */
@Composable
fun ProTipsCardSection(
    onCategorySelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    tips: List<SeasonalProduceTip> = DefaultSeasonalProTips
) {
    var selectedTipIndex by remember { mutableIntStateOf(0) }
    val activeTip = tips.getOrElse(selectedTipIndex) { tips.first() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp)
            .testTag("pro_tips_section")
    ) {
        // Section Header with Pro Tips Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(AccentOrange.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = AccentOrange,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "نصائح الخبراء لحفظ الطزاجة",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextDarkGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Surface(
                color = FreshGreenLight,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "Pro Tips • ${selectedTipIndex + 1}/${tips.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = FreshGreenDark,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // Quick Seasonal Produce Selector Pills
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(tips, key = { _, tip -> tip.id }) { index, tip ->
                val isSelected = index == selectedTipIndex
                Surface(
                    onClick = { selectedTipIndex = index },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) FreshGreenDark else CardSurface,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) FreshGreenDark else BorderSubtle
                    ),
                    modifier = Modifier.testTag("pro_tip_tab_${tip.id}")
                ) {
                    Text(
                        text = tip.produceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) White else TextDarkGreen,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Main Interactive Pro Tip Card
        AnimatedContent(
            targetState = activeTip,
            transitionSpec = {
                fadeIn() togetherWith fadeOut() using SizeTransform(clip = false)
            },
            label = "proTipAnimatedCard"
        ) { tip ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("pro_tip_card_${tip.id}")
                    .semantics { contentDescription = "نصيحة حفظ ${tip.produceName}" },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, FreshGreen.copy(alpha = 0.22f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    FreshGreenLight.copy(alpha = 0.45f),
                                    CardSurface
                                )
                            )
                        )
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Top Season & Gain Badges
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = AccentOrange.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = tip.seasonBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AccentOrange,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }

                                Surface(
                                    color = FreshGreenLight,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = FreshGreen,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = tip.shelfLifeGain,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = FreshGreenDark,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Tip Headline
                            Text(
                                text = tip.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextDarkGreen,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 22.sp
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Tip Body Description
                            Text(
                                text = tip.tipDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                lineHeight = 19.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Produce Photography Visual
                        ProductVisual(
                            imageUrl = tip.imageKey,
                            size = 76.dp,
                            backgroundColor = White,
                            contentDescription = tip.produceName
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Footer Row: Storage Zone Info + Next Tip / Shop Category Action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "بيئة الحفظ المثالية: ${tip.storageZone}",
                            style = MaterialTheme.typography.labelSmall,
                            color = FreshGreenDark,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "تسوق ${tip.targetCategory}",
                                style = MaterialTheme.typography.labelSmall,
                                color = FreshGreen,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onCategorySelect(tip.targetCategory) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("pro_tip_shop_category")
                            )

                            Surface(
                                onClick = {
                                    selectedTipIndex = (selectedTipIndex + 1) % tips.size
                                },
                                color = FreshGreen,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("pro_tip_next_button")
                            ) {
                                Text(
                                    text = "النصيحة التالية",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
