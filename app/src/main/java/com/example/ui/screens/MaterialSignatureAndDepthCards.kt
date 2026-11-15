package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.domain.AdaptiveDepthFilterResult
import com.example.domain.MaterialSignatureAnalysis
import com.example.ui.theme.MonoTelemetryStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdaptiveDepthFilterCard(
    depthFilterMode: Int,
    adaptiveFilter: AdaptiveDepthFilterResult?,
    onDepthFilterModeChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val suppressed = adaptiveFilter?.surfaceClutterSuppressed == true
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (suppressed) 1.6.dp else 1.dp,
                color = if (suppressed) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("adaptive_depth_filter_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = "فیلتر تطبیقی عمق",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "فیلترینگ تطبیقی عمق و حذف تداخل سطحی",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                if (suppressed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x33FFB300))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "تداخل سطحی حذف شد",
                            tint = Color(0xFFFFCA28),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "تداخل سطحی مسدود شد",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFD54F)
                        )
                    }
                }
            }

            Text(
                text = "حساسیت مگنتومتر را بر اساس عرض ردپای محاسبه‌شده روی خاک و الگوی فرکانسی تنظیم می‌کند تا خرده‌آهن و پالس‌های تیز سطحی، هاله اهداف عمیق‌تر را نپوشانند.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    0 to "خودکار (تطبیقی)",
                    1 to "عمق بالا (ضد سطح)",
                    2 to "میانی",
                    3 to "سطحی کامل"
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = depthFilterMode == mode,
                        onClick = { onDepthFilterModeChanged(mode) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("depth_filter_mode_chip_$mode")
                    )
                }
            }

            adaptiveFilter?.let { df ->
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Footprint: %.2f m | Deep Envelope: %+.2f µT | Shallow Clutter: %.2f µT\n" +
                                "Adapted Floor: %.1f µT (Gain %.2fx) | Mode: %s",
                            df.soilFootprintWidthM,
                            df.deepEnvelopeUt,
                            df.shallowClutterUt,
                            df.adaptiveThresholdUt,
                            df.sensitivityGainFactor,
                            df.filterModeLabel
                        ),
                        style = MonoTelemetryStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun MaterialSignatureAnalysisCard(
    analysis: MaterialSignatureAnalysis?,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .testTag("material_signature_analysis_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = "تحلیل امضای مواد",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تحلیل امضای طیفی مواد (تفکیک غیرآهنی از آهن و نویز)",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }

            if (analysis == null || analysis.rankedMatches.isEmpty()) {
                Text(
                    text = analysis?.primaryClassLabel ?: "در حال گردآوری طیف حرکتی STFT برای تطبیق امضای مواد...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                analysis?.physicalCaveat?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else {
                val badgeBg = if (analysis.isFerrousOrNoiseDominant) {
                    Color(0x28FF5252)
                } else {
                    Color(0x2800E5FF)
                }
                val badgeTextCol = if (analysis.isFerrousOrNoiseDominant) {
                    Color(0xFFFF8A80)
                } else {
                    Color(0xFF80D8FF)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeBg)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "تطبیق غالب: ${analysis.primaryClassLabel} (${analysis.topSimilarityPercent}٪)",
                        style = MaterialTheme.typography.labelLarge,
                        color = badgeTextCol
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = "Polarity: ${analysis.signalPolarityLabel}",
                            style = MonoTelemetryStyle,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                analysis.rankedMatches.forEach { item ->
                    val barColor = when (item.id) {
                        "gold" -> Color(0xFFFFD54F)
                        "silver" -> Color(0xFFB2EBF2)
                        "copper" -> Color(0xFFFFAB91)
                        "iron" -> Color(0xFFFF5252)
                        else -> Color(0xFF90A4AE)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%d%% (SAM %.1f°)",
                                        item.similarityPercent,
                                        item.spectralAngleDeg
                                    ),
                                    style = MonoTelemetryStyle,
                                    color = barColor
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF15202B))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction = (item.similarityPercent / 100f).coerceIn(0.02f, 1f))
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(barColor)
                            )
                        }
                    }
                }

                Text(
                    text = analysis.physicalCaveat,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}
