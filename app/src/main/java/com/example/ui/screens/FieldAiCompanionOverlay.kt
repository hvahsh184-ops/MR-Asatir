package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ui.RadarUiState
import com.example.ui.theme.MonoTelemetryStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldAiCompanionOverlay(
    state: RadarUiState,
    visible: Boolean,
    onSetSheetOpen: (Boolean) -> Unit,
    onToggleWatchdog: () -> Unit,
    onDismissWatchdogBanner: () -> Unit,
    onAttachQuickPhoto: (Bitmap?) -> Unit,
    onAskCompanion: (String) -> Unit,
    onQuickConnectCloudAi: () -> Unit,
    onOpenAiSettingsTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val context = LocalContext.current
    var draftQuestion by remember { mutableStateOf("") }
    var voiceLaunchRequested by remember { mutableStateOf(false) }

    if (state.isAiCompanionSheetOpen) {
        BackHandler { onSetSheetOpen(false) }
    }

    val quickCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp ->
        if (bmp != null) {
            onAttachQuickPhoto(bmp)
        }
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            runCatching { quickCameraLauncher.launch(null) }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim()
            if (!spoken.isNullOrBlank()) {
                draftQuestion = spoken
                onAskCompanion(spoken)
            }
        }
    }

    val launchPersianVoiceInput = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "سؤال یا وضعیت زمین را بگویید (صوت + تصویر همزمان)...")
        }
        runCatching { speechLauncher.launch(intent) }
    }

    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && voiceLaunchRequested) {
            voiceLaunchRequested = false
            launchPersianVoiceInput()
        }
    }

    val triggerVoiceAsk: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchPersianVoiceInput()
        } else {
            voiceLaunchRequested = true
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Permission-Gated Watchdog Overshoot / Error-Correction Banner (shown on any screen only when permitted & triggered)
        val bannerTitle = state.watchdogAlertTitle
        val bannerMsg = state.watchdogAlertMessage
        if (bannerTitle != null && bannerMsg != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xEE2B1606)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .widthIn(max = 580.dp)
                    .fillMaxWidth()
                    .border(2.dp, Color(0xFFFFB300), RoundedCornerShape(16.dp))
                    .testTag("watchdog_alert_banner")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = "هشدار ناظر هوشمند",
                                tint = Color(0xFFFFCA28)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = bannerTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFFFFE082)
                            )
                        }
                        IconButton(
                            onClick = onDismissWatchdogBanner,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "بستن هشدار ناظر",
                                tint = Color.White
                            )
                        }
                    }
                    Text(
                        text = bannerMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFF8E1)
                    )
                    state.watchdogCloudVerdict?.let { verdict ->
                        Text(
                            text = "نظر همزمان هوش مصنوعی: $verdict",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF80D8FF)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                onSetSheetOpen(true)
                                triggerVoiceAsk()
                            },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("پرسش صوتی + بازبینی تصویر")
                        }
                    }
                }
            }
        }

        // 2. Expanded In-Place Live Companion Panel (works over ANY tab without losing search view)
        AnimatedVisibility(
            visible = state.isAiCompanionSheetOpen,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 72.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier
                    .widthIn(max = 580.dp)
                    .fillMaxWidth()
                    .border(1.6.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                    .testTag("ai_companion_overlay_panel")
            ) {
                Column(
                    modifier = Modifier
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = "همراه زنده میدان",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "همراه زنده میدان (دید + صوت همزمان)",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = if (state.aiConfig.endpointUrl.isBlank()) {
                                        "حالت تله‌متری آفلاین — برای بینایی ابری «اتصال سریع» را بزنید"
                                    } else {
                                        "متصل به: ${state.aiConfig.modelName}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        IconButton(
                            onClick = { onSetSheetOpen(false) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("close_ai_companion_panel_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "بستن پنجره همراه")
                        }
                    }

                    // Watchdog explicit permission toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (state.aiWatchdogEnabled) Color(0x28FFB300) else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "مجوز ناظر خودکار (اگر رد شدم بگو برگرد)",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.aiWatchdogEnabled) Color(0xFFFFD54F) else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (state.aiWatchdogEnabled) {
                                    "فعال: عبور از اوج هدف/ناهنجاری و تداخل فریبنده فوری اعلام می‌شود."
                                } else {
                                    "خاموش: تا خودم نپرسم کاملاً ساکت می‌ماند و حواس را پرت نمی‌کند."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.aiWatchdogEnabled,
                            onCheckedChange = { onToggleWatchdog() },
                            modifier = Modifier.testTag("companion_watchdog_permission_switch")
                        )
                    }

                    // Live telemetry + vision status strip
                    val activePreviewBmp = state.companionAttachedImage ?: state.liveReceiverFrameBitmap
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (activePreviewBmp != null) {
                            Image(
                                bitmap = activePreviewBmp.asImageBitmap(),
                                contentDescription = "دید زنده ضمیمه‌شده",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF14202B)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = "بدون تصویر",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = when {
                                    state.companionAttachedImage != null -> "عکس جدید آماده ارسال همزمان است"
                                    state.liveReceiverFrameBitmap != null -> "دید زنده دوربین گیرنده خودکار متصل است"
                                    else -> "برای دید همزمان، «دوربین سریع» یا دوربین تب گیرنده را بزنید"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (activePreviewBmp != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                val distTxt = state.liveGuidanceOffset?.distanceMeters?.let {
                                    String.format(Locale.US, "Dist %.1fm | ", it)
                                }.orEmpty()
                                val envTxt = state.adaptiveDepthFilter?.let {
                                    String.format(Locale.US, "Deep %+.1fµT", it.deepEnvelopeUt)
                                } ?: String.format(Locale.US, "ΔB %+.1fµT", state.magDeltaUt)
                                val matTxt = state.liveMaterialSignature?.rankedMatches?.firstOrNull()?.let {
                                    " | ${it.id.uppercase(Locale.US)} ${it.similarityPercent}%"
                                }.orEmpty()
                                Text(
                                    text = "$distTxt$envTxt$matTxt",
                                    style = MonoTelemetryStyle,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }

                    // Action buttons row: Quick Camera + Voice Ask + Quick "Did I miss / misread?"
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    runCatching { quickCameraLauncher.launch(null) }
                                } else {
                                    cameraPermLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("companion_quick_camera_button")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("دوربین سریع")
                        }

                        Button(
                            onClick = triggerVoiceAsk,
                            enabled = !state.isCompanionLoading,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("companion_voice_ask_button")
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("بپرس (صوت + دید)")
                        }

                        FilledTonalButton(
                            onClick = {
                                onAskCompanion(
                                    "همین نقطه و تصویر روبه‌رو را با داده‌های مگنتومتر، فیلتر عمق و امضای مواد بررسی کن: آیا اشتباه زدم، نویز سطحی است یا هدف مهمی را رد کردم؟ کوتاه و بی‌سانسور بگو."
                                )
                            },
                            enabled = !state.isCompanionLoading,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("companion_check_spot_button")
                        ) {
                            Text("آیا اشتباه زدم / رد کردم؟")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = draftQuestion,
                            onValueChange = { draftQuestion = it },
                            label = { Text("یا پرسش کوتاه بنویس...") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val q = draftQuestion
                                draftQuestion = ""
                                onAskCompanion(q)
                            },
                            enabled = !state.isCompanionLoading && (draftQuestion.isNotBlank() || activePreviewBmp != null),
                            modifier = Modifier.size(48.dp)
                        ) {
                            if (state.isCompanionLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال به همراه")
                            }
                        }
                    }

                    if (state.aiConfig.endpointUrl.isBlank()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    onSetSheetOpen(false)
                                    onOpenAiSettingsTab()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text("⚡ اتصال به ابر گوگل کولب (GPU اختصاصی)")
                            }
                            OutlinedButton(
                                onClick = onQuickConnectCloudAi,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text("🛡️ اتصال زاپاس رایگان و بدون سانسور (موقع قطعی کولب)")
                            }
                        }
                    }

                    state.companionError?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    state.companionLastReply?.let { reply ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x2200E5FF))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "پاسخ همراه:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = reply,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 3. Compact Floating Companion Dock (always on-screen on search tabs, unobtrusive)
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallFloatingActionButton(
                onClick = onToggleWatchdog,
                containerColor = if (state.aiWatchdogEnabled) Color(0xFFFFB300) else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (state.aiWatchdogEnabled) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("floating_watchdog_permission_button")
            ) {
                Icon(
                    imageVector = if (state.aiWatchdogEnabled) {
                        Icons.Default.NotificationsActive
                    } else {
                        Icons.Default.NotificationsPaused
                    },
                    contentDescription = if (state.aiWatchdogEnabled) {
                        "ناظر هوشمند فعال است — کلیک برای خاموش کردن"
                    } else {
                        "مجوز ناظر هوشمند خاموش است"
                    }
                )
            }

            FloatingActionButton(
                onClick = { onSetSheetOpen(!state.isAiCompanionSheetOpen) },
                containerColor = if (state.isAiCompanionSheetOpen) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                modifier = Modifier.testTag("floating_ai_companion_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = "باز کردن همراه زنده میدان"
                    )
                    Text(
                        text = "همراه AI",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
