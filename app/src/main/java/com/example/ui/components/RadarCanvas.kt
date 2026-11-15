package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.DetectedRadarPoint
import com.example.domain.GeoPoint
import com.example.domain.GridCellAnalysis
import com.example.domain.MatrixAnalysisSummary
import com.example.domain.RadarMathEngine
import com.example.sensors.LiveGpsFix
import com.example.ui.theme.MonoTelemetryStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun TacticalRadarCanvas(
    summary: MatrixAnalysisSummary?,
    selectedCell: GridCellAnalysis?,
    targetPointZero: GeoPoint?,
    cleanRadarPoints: List<DetectedRadarPoint>,
    priorityTargets: List<DetectedRadarPoint>,
    centerLat: Double?,
    centerLon: Double?,
    radiusMeters: Double,
    liveGps: LiveGpsFix?,
    headingDeg: Double,
    onCellSelected: (GridCellAnalysis) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.outline

    val infiniteTransition = rememberInfiniteTransition(label = "radar_sweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_angle"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        surfaceColor,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .border(1.dp, outlineColor, RoundedCornerShape(20.dp))
            .testTag("tactical_radar_canvas")
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(summary) {
                    detectTapGestures { tapOffset ->
                        val data = summary ?: return@detectTapGestures
                        val dim = min(size.width.toFloat(), size.height.toFloat())
                        val outerRadius = dim * 0.43f
                        val half = (data.gridSize / 2).coerceAtLeast(1)
                        val cellSize = (outerRadius * 2f) / (half * 2f)
                        val gridSpan = cellSize * data.gridSize
                        val ox = size.width / 2f - gridSpan / 2f
                        val oy = size.height / 2f - gridSpan / 2f
                        val relX = (tapOffset.x - ox) / gridSpan
                        val relY = (tapOffset.y - oy) / gridSpan
                        if (relX in 0f..1f && relY in 0f..1f) {
                            val col = (relX * data.gridSize).toInt().coerceIn(0, data.gridSize - 1)
                            val row = (relY * data.gridSize).toInt().coerceIn(0, data.gridSize - 1)
                            data.cells.firstOrNull { it.row == row && it.col == col }?.let(onCellSelected)
                        }
                    }
                }
        ) {
            val minDim = min(size.width, size.height)
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = minDim * 0.43f

            // 1. Draw 7x7 Mahalanobis Distance heatmap cells if scan exists
            if (summary != null) {
                val maxMahal = summary.cells.maxOfOrNull { it.mahalanobisDistance }?.coerceAtLeast(1.0) ?: 1.0
                val half = (summary.gridSize / 2).coerceAtLeast(1)
                val cellSize = (outerRadius * 2f) / (half * 2f)
                val gridSpan = cellSize * summary.gridSize
                val originX = center.x - gridSpan / 2f
                val originY = center.y - gridSpan / 2f

                summary.cells.forEach { cell ->
                    val norm = (cell.mahalanobisDistance / maxMahal).toFloat().coerceIn(0f, 1f)
                    val cellColor = when {
                        norm < 0.5f -> lerp(
                            surfaceColor.copy(alpha = 0.45f),
                            secondaryColor.copy(alpha = 0.5f),
                            norm * 2f
                        )
                        else -> lerp(
                            secondaryColor.copy(alpha = 0.5f),
                            tertiaryColor.copy(alpha = 0.72f),
                            (norm - 0.5f) * 2f
                        )
                    }
                    val topLeft = Offset(
                        x = originX + cell.col * cellSize + 1.5f,
                        y = originY + cell.row * cellSize + 1.5f
                    )
                    drawRect(
                        color = cellColor,
                        topLeft = topLeft,
                        size = Size(cellSize - 3f, cellSize - 3f)
                    )
                    if (selectedCell != null &&
                        selectedCell.row == cell.row &&
                        selectedCell.col == cell.col
                    ) {
                        drawRect(
                            color = secondaryColor,
                            topLeft = topLeft,
                            size = Size(cellSize - 3f, cellSize - 3f),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }
            }

            // 2. Concentric radar range rings
            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { factor ->
                drawCircle(
                    color = primaryColor.copy(alpha = if (factor == 1f) 0.48f else 0.22f),
                    radius = outerRadius * factor,
                    center = center,
                    style = Stroke(
                        width = if (factor == 1f) 1.5.dp.toPx() else 1.dp.toPx(),
                        pathEffect = if (factor < 1f) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null
                    )
                )
            }

            // 3. Crosshair axes
            drawLine(
                color = outlineColor.copy(alpha = 0.8f),
                start = Offset(center.x - outerRadius, center.y),
                end = Offset(center.x + outerRadius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = outlineColor.copy(alpha = 0.8f),
                start = Offset(center.x, center.y - outerRadius),
                end = Offset(center.x, center.y + outerRadius),
                strokeWidth = 1.dp.toPx()
            )

            // 4. Sweeping radar beam
            val sweepRad = (sweepAngle - 90f) * (PI / 180.0)
            val sweepEnd = Offset(
                x = center.x + (outerRadius * cos(sweepRad)).toFloat(),
                y = center.y + (outerRadius * sin(sweepRad)).toFloat()
            )
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.15f), primaryColor),
                    start = center,
                    end = sweepEnd
                ),
                start = center,
                end = sweepEnd,
                strokeWidth = 2.dp.toPx()
            )

            val scale = if (radiusMeters > 0.0) outerRadius / radiusMeters.toFloat() else 1f

            // 5. Clean Radar CFAR points (Cyan dots)
            cleanRadarPoints.take(25).forEach { pt ->
                val px = (center.x + pt.eastOffsetM.toFloat() * scale)
                    .coerceIn(center.x - outerRadius, center.x + outerRadius)
                val py = (center.y - pt.northOffsetM.toFloat() * scale)
                    .coerceIn(center.y - outerRadius, center.y + outerRadius)
                drawCircle(
                    color = secondaryColor,
                    radius = 4.dp.toPx(),
                    center = Offset(px, py)
                )
            }

            // 6. Priority 1 Fusion targets (Amber markers)
            priorityTargets.take(20).forEach { pt ->
                val px = (center.x + pt.eastOffsetM.toFloat() * scale)
                    .coerceIn(center.x - outerRadius, center.x + outerRadius)
                val py = (center.y - pt.northOffsetM.toFloat() * scale)
                    .coerceIn(center.y - outerRadius, center.y + outerRadius)
                drawCircle(
                    color = tertiaryColor,
                    radius = 7.dp.toPx(),
                    center = Offset(px, py),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = tertiaryColor,
                    radius = 3.5.dp.toPx(),
                    center = Offset(px, py)
                )
            }

            // 7. Selected Target Point Zero crosshair
            if (targetPointZero != null && centerLat != null && centerLon != null && radiusMeters > 0.0) {
                val tOffset = RadarMathEngine.computeGeodeticOffset(
                    fromLat = centerLat,
                    fromLon = centerLon,
                    toLat = targetPointZero.latitude,
                    toLon = targetPointZero.longitude
                )
                val tx = (center.x + tOffset.deltaEastMeters.toFloat() * scale)
                    .coerceIn(center.x - outerRadius, center.x + outerRadius)
                val ty = (center.y - tOffset.deltaNorthMeters.toFloat() * scale)
                    .coerceIn(center.y - outerRadius, center.y + outerRadius)
                val targetPt = Offset(tx, ty)

                drawCircle(
                    color = errorColor.copy(alpha = 0.28f),
                    radius = 16.dp.toPx(),
                    center = targetPt
                )
                drawCircle(
                    color = errorColor,
                    radius = 5.5.dp.toPx(),
                    center = targetPt
                )
                drawLine(
                    color = errorColor,
                    start = Offset(tx - 13.dp.toPx(), ty),
                    end = Offset(tx + 13.dp.toPx(), ty),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = errorColor,
                    start = Offset(tx, ty - 13.dp.toPx()),
                    end = Offset(tx, ty + 13.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx()
                )
            }

            // 8. Live user GPS position + Quaternion heading cone
            if (liveGps != null && centerLat != null && centerLon != null && radiusMeters > 0.0) {
                val offsetFromCenter = RadarMathEngine.computeGeodeticOffset(
                    fromLat = centerLat,
                    fromLon = centerLon,
                    toLat = liveGps.latitude,
                    toLon = liveGps.longitude
                )
                val ux = (center.x + offsetFromCenter.deltaEastMeters.toFloat() * scale)
                    .coerceIn(center.x - outerRadius, center.x + outerRadius)
                val uy = (center.y - offsetFromCenter.deltaNorthMeters.toFloat() * scale)
                    .coerceIn(center.y - outerRadius, center.y + outerRadius)
                val userPt = Offset(ux, uy)

                val headRad = (headingDeg - 90.0) * (PI / 180.0)
                val coneEnd = Offset(
                    x = ux + (24.dp.toPx() * cos(headRad)).toFloat(),
                    y = uy + (24.dp.toPx() * sin(headRad)).toFloat()
                )
                drawLine(
                    color = primaryColor,
                    start = userPt,
                    end = coneEnd,
                    strokeWidth = 3.dp.toPx()
                )
                drawCircle(
                    color = primaryColor,
                    radius = 6.dp.toPx(),
                    center = userPt
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.5.dp.toPx(),
                    center = userPt
                )
            }
        }

        Text(
            text = "N",
            style = MonoTelemetryStyle,
            color = primaryColor,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
        )
        Text(
            text = "D² + CFAR + ساروج",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
        )
        Text(
            text = if (summary != null) "لمس سلول = انتخاب نقطه" else "آماده اسکن",
            style = MaterialTheme.typography.labelSmall,
            color = secondaryColor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
        )
    }
}
