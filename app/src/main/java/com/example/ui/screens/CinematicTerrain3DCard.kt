package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.domain.GridCellAnalysis
import com.example.ui.theme.MonoTelemetryStyle
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * High-performance, pure native Jetpack Compose 3D Digital Elevation Model (DEM)
 * Surface & Topography visualizer.
 *
 * Provides cinematic interactive 360° rotation, adjustable vertical exaggeration,
 * elevation color ramping, contour mesh lines, and a glowing 3D beacon pillar at
 * the target Point Zero / peak anomaly cell.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CinematicTerrain3DCard(
    cells: List<GridCellAnalysis>,
    gridSize: Int,
    selectedCell: GridCellAnalysis?,
    onCellSelected: ((GridCellAnalysis) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var yawDeg by remember { mutableFloatStateOf(35f) }
    var pitchDeg by remember { mutableFloatStateOf(42f) }
    var verticalExaggeration by remember { mutableFloatStateOf(3.2f) }
    var isAutoSpinning by remember { mutableStateOf(false) }
    var renderMode by remember { mutableStateOf(0) } // 0: Shaded Surface + Mesh, 1: Wireframe Mesh, 2: Surface Only
    var isExpandedHeight by remember { mutableStateOf(false) }

    LaunchedEffect(isAutoSpinning) {
        if (isAutoSpinning) {
            while (isActive && isAutoSpinning) {
                yawDeg = (yawDeg + 0.65f) % 360f
                kotlinx.coroutines.delay(24L)
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .testTag("cinematic_terrain_3d_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.ViewInAr,
                        contentDescription = "مدل سه بعدی توپوگرافی",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "مدل سه‌بعدی توپوگرافی زمین (DEM سینمایی)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "چرخش ۳۶۰ درجه، برجستگی چینه‌شناسی و ستون نشانه‌گر نقطه صفر",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { isAutoSpinning = !isAutoSpinning },
                        modifier = Modifier.testTag("terrain_3d_autospin_toggle")
                    ) {
                        Icon(
                            imageVector = if (isAutoSpinning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isAutoSpinning) "توقف چرخش خودکار" else "شروع چرخش سینمایی",
                            tint = if (isAutoSpinning) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            yawDeg = 35f
                            pitchDeg = 42f
                            verticalExaggeration = 3.2f
                        },
                        modifier = Modifier.testTag("terrain_3d_reset_camera")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "بازنشانی زاویه دید",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { isExpandedHeight = !isExpandedHeight },
                        modifier = Modifier.testTag("terrain_3d_expand_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FitScreen,
                            contentDescription = "تغییر اندازه نمایش",
                            tint = if (isExpandedHeight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (cells.isEmpty() || gridSize < 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0C141D)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "برای رندر سه‌بعدی توپوگرافی، ابتدا اسکن منطقه را در تب رادار اجرا کنید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                return@Column
            }

            val elevations = cells.map { it.rawElevationM }
            val minElev = elevations.minOrNull() ?: 0.0
            val maxElev = elevations.maxOrNull() ?: 1.0
            val elevSpan = (maxElev - minElev).coerceAtLeast(0.5)

            // 3D Canvas Box
            val canvasHeight = if (isExpandedHeight) 340.dp else 230.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF090F16))
                    .border(1.dp, Color(0xFF1F2F3F), RoundedCornerShape(14.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            isAutoSpinning = false
                            yawDeg = (yawDeg + dragAmount.x * 0.45f) % 360f
                            if (yawDeg < 0f) yawDeg += 360f
                            pitchDeg = (pitchDeg - dragAmount.y * 0.35f).coerceIn(12f, 85f)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCinematicTerrain(
                        cells = cells,
                        gridSize = gridSize,
                        minElev = minElev,
                        elevSpan = elevSpan,
                        yawDeg = yawDeg,
                        pitchDeg = pitchDeg,
                        verticalExaggeration = verticalExaggeration,
                        renderMode = renderMode,
                        selectedCell = selectedCell
                    )
                }

                // HUD overlay chips on canvas
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC081018))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Elev: %.1f .. %.1f m (Δ %.1f m)",
                                    minElev,
                                    maxElev,
                                    elevSpan
                                ),
                                style = MonoTelemetryStyle,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Yaw: %.0f° | Pitch: %.0f° | Exag: %.1fx",
                                    yawDeg,
                                    pitchDeg,
                                    verticalExaggeration
                                ),
                                style = MonoTelemetryStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    selectedCell?.let { sc ->
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xCC2A1504))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = "نقطه هدف [R${sc.row}:C${sc.col}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFD54F)
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Alt: %.1f m (Res %+.1f m)",
                                        sc.rawElevationM,
                                        sc.detrendedResidualM
                                    ),
                                    style = MonoTelemetryStyle,
                                    color = Color(0xFFFFCA28)
                                )
                            }
                        }
                    }
                }
            }

            // Controls: Render mode filter chips + Exaggeration Slider
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    0 to "سطح سایه‌دار + شبکه",
                    1 to "فقط شبکه هندسی (Wireframe)",
                    2 to "فقط پوسته رنگی"
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = renderMode == mode,
                        onClick = { renderMode = mode },
                        label = { Text(label) },
                        modifier = Modifier.testTag("terrain_render_mode_$mode")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ضریب برجستگی عمق:",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(130.dp)
                )
                Slider(
                    value = verticalExaggeration,
                    onValueChange = { verticalExaggeration = it },
                    valueRange = 1.0f..7.5f,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("terrain_exaggeration_slider")
                )
                Text(
                    text = String.format(Locale.US, "%.1fx", verticalExaggeration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(36.dp)
                )
            }
        }
    }
}

/**
 * Pure 3D Canvas Projection & Shaded Relief Renderer
 */
private fun DrawScope.drawCinematicTerrain(
    cells: List<GridCellAnalysis>,
    gridSize: Int,
    minElev: Double,
    elevSpan: Double,
    yawDeg: Float,
    pitchDeg: Float,
    verticalExaggeration: Float,
    renderMode: Int,
    selectedCell: GridCellAnalysis?
) {
    val cx = size.width / 2f
    val cy = size.height / 2f + 16f

    val radYaw = Math.toRadians(yawDeg.toDouble())
    val radPitch = Math.toRadians(pitchDeg.toDouble())
    val cosYaw = cos(radYaw)
    val sinYaw = sin(radYaw)
    val cosPitch = cos(radPitch)
    val sinPitch = sin(radPitch)

    val half = (gridSize - 1) / 2.0
    val scale = min(size.width, size.height) * 0.44f

    fun project3D(col: Int, row: Int, elevM: Double): Offset {
        val normX = ((col - half) / half).coerceIn(-1.0, 1.0)
        val normY = ((row - half) / half).coerceIn(-1.0, 1.0)
        val normZ = (((elevM - minElev) / elevSpan).coerceIn(0.0, 1.0) - 0.5) * 0.65 * verticalExaggeration

        val rx = normX * cosYaw - normY * sinYaw
        val ry = normX * sinYaw + normY * cosYaw
        val rz = normZ

        val px = rx
        val py = ry * cosPitch - rz * sinPitch
        val pz = ry * sinPitch + rz * cosPitch

        // Slight perspective foreshortening
        val perspective = 1.0 / (1.0 + pz * 0.35).coerceAtLeast(0.4)
        val screenX = cx + (px * scale * perspective).toFloat()
        val screenY = cy + (py * scale * perspective).toFloat()
        return Offset(screenX, screenY)
    }

    val cellMap = HashMap<Pair<Int, Int>, GridCellAnalysis>(cells.size)
    for (cell in cells) {
        cellMap[Pair(cell.row, cell.col)] = cell
    }

    // 1. Shaded Terrain Surface Quads
    if (renderMode != 1) {
        for (r in 0 until (gridSize - 1)) {
            for (c in 0 until (gridSize - 1)) {
                val c00 = cellMap[Pair(r, c)] ?: continue
                val c10 = cellMap[Pair(r + 1, c)] ?: continue
                val c11 = cellMap[Pair(r + 1, c + 1)] ?: continue
                val c01 = cellMap[Pair(r, c + 1)] ?: continue

                val p00 = project3D(c, r, c00.rawElevationM)
                val p10 = project3D(c, r + 1, c10.rawElevationM)
                val p11 = project3D(c + 1, r + 1, c11.rawElevationM)
                val p01 = project3D(c + 1, r, c01.rawElevationM)

                val avgElev = (c00.rawElevationM + c10.rawElevationM + c11.rawElevationM + c01.rawElevationM) / 4.0
                val ratio = ((avgElev - minElev) / elevSpan).toFloat().coerceIn(0f, 1f)

                val quadColor = elevationColorRamp(ratio)
                val path = Path().apply {
                    moveTo(p00.x, p00.y)
                    lineTo(p01.x, p01.y)
                    lineTo(p11.x, p11.y)
                    lineTo(p10.x, p10.y)
                    close()
                }
                drawPath(path, color = quadColor)
            }
        }
    }

    // 2. Wireframe Mesh & Contour Lines
    if (renderMode != 2) {
        val lineColor = if (renderMode == 1) Color(0xFF00E5FF) else Color(0x55E0F7FA)
        val strokeWidth = if (renderMode == 1) 1.5f else 1.0f

        for (r in 0 until gridSize) {
            for (c in 0 until gridSize) {
                val cur = cellMap[Pair(r, c)] ?: continue
                val pCur = project3D(c, r, cur.rawElevationM)

                if (c + 1 < gridSize) {
                    val right = cellMap[Pair(r, c + 1)]
                    if (right != null) {
                        val pRight = project3D(c + 1, r, right.rawElevationM)
                        drawLine(lineColor, pCur, pRight, strokeWidth = strokeWidth)
                    }
                }
                if (r + 1 < gridSize) {
                    val down = cellMap[Pair(r + 1, c)]
                    if (down != null) {
                        val pDown = project3D(c, r + 1, down.rawElevationM)
                        drawLine(lineColor, pCur, pDown, strokeWidth = strokeWidth)
                    }
                }
            }
        }
    }

    // 3. Highlight Beacon Pillar on Selected / Target Point Zero Cell
    val targetCell = selectedCell ?: cells.maxByOrNull { it.detrendedResidualM }
    if (targetCell != null) {
        val pBase = project3D(targetCell.col, targetCell.row, targetCell.rawElevationM)
        val pPillarTop = project3D(targetCell.col, targetCell.row, targetCell.rawElevationM + elevSpan * 0.85)

        // Vertical glowing beacon laser pillar
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFFFD54F), Color(0xFFFF5252), Color(0x00FFB300)),
                startY = pPillarTop.y,
                endY = pBase.y
            ),
            start = pBase,
            end = pPillarTop,
            strokeWidth = 3.5f
        )

        // Pulsing rings at base
        drawCircle(
            color = Color(0xFFFFD54F),
            radius = 6.5f,
            center = pBase
        )
        drawCircle(
            color = Color(0xFFFFCA28),
            radius = 12f,
            center = pBase,
            style = Stroke(width = 1.8f)
        )
        // Beacon star at top
        drawCircle(
            color = Color(0xFFFFFFFF),
            radius = 4.5f,
            center = pPillarTop
        )
    }
}

/**
 * Natural Geological Color Ramp from Deep Valleys to High Mounds
 */
private fun elevationColorRamp(ratio: Float): Color {
    return when {
        ratio < 0.25f -> {
            val t = ratio / 0.25f
            lerpColor(Color(0xFF0D2538), Color(0xFF1B4D3E), t)
        }
        ratio < 0.55f -> {
            val t = (ratio - 0.25f) / 0.30f
            lerpColor(Color(0xFF1B4D3E), Color(0xFF7A6843), t)
        }
        ratio < 0.82f -> {
            val t = (ratio - 0.55f) / 0.27f
            lerpColor(Color(0xFF7A6843), Color(0xFFB8863A), t)
        }
        else -> {
            val t = (ratio - 0.82f) / 0.18f
            lerpColor(Color(0xFFB8863A), Color(0xFFFFE082), t)
        }
    }
}

private fun lerpColor(c1: Color, c2: Color, t: Float): Color {
    val clamped = t.coerceIn(0f, 1f)
    return Color(
        red = c1.red + (c2.red - c1.red) * clamped,
        green = c1.green + (c2.green - c1.green) * clamped,
        blue = c1.blue + (c2.blue - c1.blue) * clamped,
        alpha = 0.90f
    )
}
