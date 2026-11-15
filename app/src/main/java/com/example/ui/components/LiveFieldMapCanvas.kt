package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.domain.DetectedRadarPoint
import com.example.domain.GeoPoint
import com.example.domain.RadarMathEngine
import com.example.sensors.LiveGpsFix
import com.example.ui.theme.MonoTelemetryStyle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

enum class MapTileLayer(val labelFa: String) {
    SATELLITE("ماهواره‌ای"),
    STREET("نقشه معابر")
}

@Composable
fun LiveFieldMapCanvas(
    liveGps: LiveGpsFix?,
    headingDeg: Double,
    targetPointZero: GeoPoint?,
    scanCenterLat: Double?,
    scanCenterLon: Double?,
    scanRadiusMeters: Double,
    cleanRadarPoints: List<DetectedRadarPoint>,
    mahalanobisHotspots: List<DetectedRadarPoint>,
    priorityTargets: List<DetectedRadarPoint>,
    fetchTile: suspend (String) -> Bitmap?,
    onDetectedTargetSelected: (DetectedRadarPoint, String) -> Unit,
    onMapLongOrCustomSelect: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier
) {
    var mapLayer by remember { mutableStateOf(MapTileLayer.SATELLITE) }
    var zoomLevel by remember { mutableIntStateOf(16) }
    var followUser by remember { mutableStateOf(true) }

    val initialLat = liveGps?.latitude ?: targetPointZero?.latitude ?: scanCenterLat ?: 35.1709667
    val initialLon = liveGps?.longitude ?: targetPointZero?.longitude ?: scanCenterLon ?: 49.6665633

    var viewCenterLat by remember { mutableDoubleStateOf(initialLat) }
    var viewCenterLon by remember { mutableDoubleStateOf(initialLon) }

    LaunchedEffect(liveGps?.latitude, liveGps?.longitude, followUser) {
        if (followUser && liveGps != null) {
            viewCenterLat = liveGps.latitude
            viewCenterLon = liveGps.longitude
        }
    }

    LaunchedEffect(scanCenterLat, scanCenterLon) {
        if (liveGps == null && scanCenterLat != null && scanCenterLon != null) {
            viewCenterLat = scanCenterLat
            viewCenterLon = scanCenterLon
        }
    }

    val loadedTiles = remember { mutableStateMapOf<String, Bitmap>() }
    val requestedKeys = remember { mutableStateMapOf<String, Boolean>() }

    val tileScreenPx = 320f
    val worldCount = (1 shl zoomLevel)

    val centerTileX = lonToTileXFrac(viewCenterLon, zoomLevel)
    val centerTileY = latToTileYFrac(viewCenterLat, zoomLevel)

    // Pre-fetch 5x5 tile neighborhood around viewport center
    val centerIx = floor(centerTileX).toInt()
    val centerIy = floor(centerTileY).toInt()

    LaunchedEffect(centerIx, centerIy, zoomLevel, mapLayer) {
        val jobs = mutableListOf<Pair<String, String>>()
        for (dy in -2..2) {
            for (dx in -2..2) {
                val tx = ((centerIx + dx) % worldCount + worldCount) % worldCount
                val ty = (centerIy + dy).coerceIn(0, worldCount - 1)
                val key = "${mapLayer.name}/$zoomLevel/$tx/$ty"
                if (!loadedTiles.containsKey(key) && !requestedKeys.containsKey(key)) {
                    requestedKeys[key] = true
                    val url = when (mapLayer) {
                        MapTileLayer.SATELLITE ->
                            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$zoomLevel/$ty/$tx"
                        MapTileLayer.STREET ->
                            "https://tile.openstreetmap.org/$zoomLevel/$tx/$ty.png"
                    }
                    jobs.add(key to url)
                }
            }
        }
        if (jobs.isNotEmpty()) {
            coroutineScope {
                jobs.map { (key, url) ->
                    async {
                        val bmp = fetchTile(url)
                        if (bmp != null) {
                            loadedTiles[key] = bmp
                        }
                    }
                }.awaitAll()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(430.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0A1019))
            .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("live_field_map_canvas")
                .pointerInput(zoomLevel) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        followUser = false
                        val dTileX = -dragAmount.x / tileScreenPx.toDouble()
                        val dTileY = -dragAmount.y / tileScreenPx.toDouble()
                        val curX = lonToTileXFrac(viewCenterLon, zoomLevel) + dTileX
                        val curY = latToTileYFrac(viewCenterLat, zoomLevel) + dTileY
                        val (newLat, newLon) = tileXYFracToLatLon(curX, curY, zoomLevel)
                        viewCenterLat = newLat.coerceIn(-85.0, 85.0)
                        viewCenterLon = ((newLon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                    }
                }
                .pointerInput(zoomLevel, priorityTargets, mahalanobisHotspots, cleanRadarPoints) {
                    detectTapGestures(
                        onTap = { tap ->
                            val w = size.width.toDouble()
                            val h = size.height.toDouble()
                            val cTx = lonToTileXFrac(viewCenterLon, zoomLevel)
                            val cTy = latToTileYFrac(viewCenterLat, zoomLevel)

                            // Check if user tapped near an existing target marker (within 36px)
                            var bestDist = 42.0
                            var matchedPoint: DetectedRadarPoint? = null
                            var matchedLabel = ""

                            val candidates = buildList {
                                priorityTargets.forEachIndexed { i, p -> add(p to "قطعی ${i + 1} (تلاقی رادار+کربنات)") }
                                mahalanobisHotspots.forEachIndexed { i, p -> add(p to "ماهالانوبیس ۵بعدی #${i + 1}") }
                                cleanRadarPoints.forEachIndexed { i, p -> add(p to "رادار CFAR #${i + 1}") }
                            }
                            candidates.forEach { (pt, lbl) ->
                                val px = (w / 2.0) + (lonToTileXFrac(pt.longitude, zoomLevel) - cTx) * tileScreenPx
                                val py = (h / 2.0) + (latToTileYFrac(pt.latitude, zoomLevel) - cTy) * tileScreenPx
                                val d = hypot(tap.x - px, tap.y - py)
                                if (d < bestDist) {
                                    bestDist = d
                                    matchedPoint = pt
                                    matchedLabel = lbl
                                }
                            }
                            matchedPoint?.let { onDetectedTargetSelected(it, matchedLabel) }
                        },
                        onLongPress = { tap ->
                            val w = size.width.toDouble()
                            val h = size.height.toDouble()
                            val cTx = lonToTileXFrac(viewCenterLon, zoomLevel)
                            val cTy = latToTileYFrac(viewCenterLat, zoomLevel)
                            val tapTx = cTx + (tap.x - w / 2.0) / tileScreenPx
                            val tapTy = cTy + (tap.y - h / 2.0) / tileScreenPx
                            val (tLat, tLon) = tileXYFracToLatLon(tapTx, tapTy, zoomLevel)
                            onMapLongOrCustomSelect(GeoPoint(tLat, tLon))
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f

            fun latLonToScreen(lat: Double, lon: Double): Offset {
                val tx = lonToTileXFrac(lon, zoomLevel)
                val ty = latToTileYFrac(lat, zoomLevel)
                return Offset(
                    x = (cx + (tx - centerTileX) * tileScreenPx).toFloat(),
                    y = (cy + (ty - centerTileY) * tileScreenPx).toFloat()
                )
            }

            // 1. Render satellite / OSM tiles covering viewport
            val halfTilesX = ceil((w / 2f) / tileScreenPx).toInt() + 1
            val halfTilesY = ceil((h / 2f) / tileScreenPx).toInt() + 1
            for (dy in -halfTilesY..halfTilesY) {
                for (dx in -halfTilesX..halfTilesX) {
                    val txRaw = centerIx + dx
                    val tyRaw = centerIy + dy
                    if (tyRaw !in 0 until worldCount) continue
                    val tx = ((txRaw % worldCount) + worldCount) % worldCount
                    val left = (cx + (txRaw - centerTileX) * tileScreenPx).toFloat()
                    val top = (cy + (tyRaw - centerTileY) * tileScreenPx).toFloat()

                    val key = "${mapLayer.name}/$zoomLevel/$tx/$tyRaw"
                    val bmp = loadedTiles[key]
                    if (bmp != null) {
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(ceil(tileScreenPx).toInt() + 1, ceil(tileScreenPx).toInt() + 1)
                        )
                    } else {
                        drawRect(
                            color = Color(0xFF101B29),
                            topLeft = Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(tileScreenPx, tileScreenPx)
                        )
                        drawRect(
                            color = Color(0xFF1E3248),
                            topLeft = Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(tileScreenPx, tileScreenPx),
                            style = Stroke(width = 1f)
                        )
                    }
                }
            }

            // 2. Scan radius boundary circle
            val metersPerPx = (156543.03392 * cos(Math.toRadians(viewCenterLat)) / 2.0.pow(zoomLevel)) * (256.0 / tileScreenPx)
            if (scanCenterLat != null && scanCenterLon != null && metersPerPx > 0.01) {
                val centerScr = latLonToScreen(scanCenterLat, scanCenterLon)
                val radiusPx = (scanRadiusMeters / metersPerPx).toFloat()
                drawCircle(
                    color = Color(0x5500E5FF),
                    radius = radiusPx,
                    center = centerScr,
                    style = Stroke(width = 2.5f)
                )
            }

            // 3. Plot detected targets (CFAR = Cyan, 5D Mahalanobis = Magenta, Priority 1 = Amber)
            cleanRadarPoints.forEach { pt ->
                val p = latLonToScreen(pt.latitude, pt.longitude)
                drawCircle(color = Color(0xFF00E5FF), radius = 8f, center = p)
                drawCircle(color = Color(0xFF08121B), radius = 3.5f, center = p)
            }

            mahalanobisHotspots.forEach { pt ->
                val p = latLonToScreen(pt.latitude, pt.longitude)
                drawCircle(color = Color(0xFFD500F9), radius = 11f, center = p, style = Stroke(width = 3f))
                drawCircle(color = Color(0xFFD500F9), radius = 4.5f, center = p)
            }

            priorityTargets.forEach { pt ->
                val p = latLonToScreen(pt.latitude, pt.longitude)
                val diamond = Path().apply {
                    moveTo(p.x, p.y - 13f)
                    lineTo(p.x + 11f, p.y)
                    lineTo(p.x, p.y + 13f)
                    lineTo(p.x - 11f, p.y)
                    close()
                }
                drawPath(diamond, color = Color(0xFFFFB300))
                drawPath(diamond, color = Color(0xFF141820), style = Stroke(width = 2f))
            }

            // 4. Live Dashed Navigation Path from Live GPS to Target Point Zero
            if (liveGps != null && targetPointZero != null) {
                val userPt = latLonToScreen(liveGps.latitude, liveGps.longitude)
                val tgtPt = latLonToScreen(targetPointZero.latitude, targetPointZero.longitude)
                drawLine(
                    color = Color(0xCC00FF88),
                    start = userPt,
                    end = tgtPt,
                    strokeWidth = 5.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f)
                )
            }

            // 5. Target Point Zero Red Crosshair Pin
            targetPointZero?.let { tgt ->
                val tp = latLonToScreen(tgt.latitude, tgt.longitude)
                drawCircle(color = Color(0x44FF3B5C), radius = 26f, center = tp)
                drawCircle(color = Color(0xFFFF3B5C), radius = 15f, center = tp, style = Stroke(width = 3.5f))
                drawLine(Color(0xFFFF3B5C), Offset(tp.x - 22f, tp.y), Offset(tp.x + 22f, tp.y), 3f)
                drawLine(Color(0xFFFF3B5C), Offset(tp.x, tp.y - 22f), Offset(tp.x, tp.y + 22f), 3f)
                drawCircle(color = Color.White, radius = 5f, center = tp)
            }

            // 6. User Live GPS Position + Accuracy Halo + Compass Heading Cone
            liveGps?.let { gps ->
                val up = latLonToScreen(gps.latitude, gps.longitude)
                if (metersPerPx > 0.01) {
                    val accPx = (gps.accuracyM / metersPerPx).toFloat().coerceIn(14f, 120f)
                    drawCircle(color = Color(0x2838BDF8), radius = accPx, center = up)
                    drawCircle(color = Color(0x7738BDF8), radius = accPx, center = up, style = Stroke(width = 1.5f))
                }
                // Compass heading cone
                val rad = Math.toRadians(headingDeg - 90.0)
                val coneLen = 44f
                val spread = 0.42
                val p1 = Offset(up.x + (coneLen * cos(rad - spread)).toFloat(), up.y + (coneLen * kotlin.math.sin(rad - spread)).toFloat())
                val p2 = Offset(up.x + (coneLen * cos(rad + spread)).toFloat(), up.y + (coneLen * kotlin.math.sin(rad + spread)).toFloat())
                val cone = Path().apply {
                    moveTo(up.x, up.y)
                    lineTo(p1.x, p1.y)
                    lineTo(p2.x, p2.y)
                    close()
                }
                drawPath(cone, color = Color(0x6600E5FF))

                drawCircle(color = Color.White, radius = 11f, center = up)
                drawCircle(color = Color(0xFF007BFF), radius = 7.5f, center = up)
            }
        }

        // Top-Left Layer & Zoom Badge
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = "Z$zoomLevel • ${mapLayer.labelFa}  |  Long-press = Custom Target",
                style = MonoTelemetryStyle,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(Color(0xAA0A1019), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // Right-Side Map Controls (Layer Switch, Follow Me, Fit Route, Zoom +/-)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SmallFloatingActionButton(
                onClick = {
                    mapLayer = if (mapLayer == MapTileLayer.SATELLITE) MapTileLayer.STREET else MapTileLayer.SATELLITE
                },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                modifier = Modifier.testTag("map_layer_toggle_button")
            ) {
                Icon(Icons.Default.Layers, contentDescription = "تغییر لایه نقشه")
            }

            SmallFloatingActionButton(
                onClick = {
                    followUser = true
                    liveGps?.let {
                        viewCenterLat = it.latitude
                        viewCenterLon = it.longitude
                    }
                },
                containerColor = if (followUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = if (followUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("map_follow_me_button")
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "مرکز روی موقعیت من")
            }

            SmallFloatingActionButton(
                onClick = {
                    if (liveGps != null && targetPointZero != null) {
                        followUser = false
                        viewCenterLat = (liveGps.latitude + targetPointZero.latitude) / 2.0
                        viewCenterLon = (liveGps.longitude + targetPointZero.longitude) / 2.0
                        val distM = RadarMathEngine.computeGeodeticOffset(
                            liveGps.latitude, liveGps.longitude,
                            targetPointZero.latitude, targetPointZero.longitude
                        ).distanceMeters
                        zoomLevel = when {
                            distM < 120 -> 18
                            distM < 350 -> 17
                            distM < 850 -> 16
                            distM < 2000 -> 15
                            distM < 4500 -> 14
                            else -> 13
                        }
                    } else if (targetPointZero != null) {
                        followUser = false
                        viewCenterLat = targetPointZero.latitude
                        viewCenterLon = targetPointZero.longitude
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                modifier = Modifier.testTag("map_fit_route_button")
            ) {
                Icon(Icons.Default.Route, contentDescription = "نمایش مسیر کامل تا هدف")
            }

            FilledIconButton(
                onClick = { zoomLevel = (zoomLevel + 1).coerceAtMost(19) },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                ),
                modifier = Modifier
                    .size(40.dp)
                    .testTag("map_zoom_in_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = "بزرگنمایی")
            }

            FilledIconButton(
                onClick = { zoomLevel = (zoomLevel - 1).coerceAtLeast(11) },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                ),
                modifier = Modifier
                    .size(40.dp)
                    .testTag("map_zoom_out_button")
            ) {
                Icon(Icons.Default.Remove, contentDescription = "کوچکنمایی")
            }
        }

        // Bottom Legend
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .background(Color(0xBB0A1019), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MapLegendChip(Color(0xFF007BFF), "من")
            MapLegendChip(Color(0xFFFF3B5C), "هدف")
            MapLegendChip(Color(0xFFFFB300), "قطعی")
            MapLegendChip(Color(0xFFD500F9), "ماهالانوبیس")
        }
    }
}

@Composable
private fun MapLegendChip(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}

private fun lonToTileXFrac(lon: Double, zoom: Int): Double {
    return ((lon + 180.0) / 360.0) * (1 shl zoom)
}

private fun latToTileYFrac(lat: Double, zoom: Int): Double {
    val clamped = lat.coerceIn(-85.0511, 85.0511)
    val rad = Math.toRadians(clamped)
    return ((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0) * (1 shl zoom)
}

private fun tileXYFracToLatLon(tx: Double, ty: Double, zoom: Int): Pair<Double, Double> {
    val n = (1 shl zoom).toDouble()
    val lon = (tx / n) * 360.0 - 180.0
    val latRad = atan(sinh(PI * (1.0 - 2.0 * ty / n)))
    return Pair(Math.toDegrees(latRad), lon)
}
