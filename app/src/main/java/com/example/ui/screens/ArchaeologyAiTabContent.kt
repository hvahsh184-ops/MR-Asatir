package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.AiChatMessage
import com.example.data.AiEndpointConfig
import com.example.ui.RadarUiState
import java.util.Locale

private const val COLAB_ONE_CLICK_SCRIPT = """!curl -fsSL https://ollama.com/install.sh | sh
!wget -q https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -O /usr/local/bin/cloudflared && chmod +x /usr/local/bin/cloudflared
import subprocess, time, re
subprocess.Popen(["ollama", "serve"])
time.sleep(3)
!ollama pull qwen2.5vl:7b
tunnel = subprocess.Popen(["cloudflared", "tunnel", "--url", "http://localhost:11434"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
for line in tunnel.stdout:
    m = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", line)
    if m:
        print("ENDPOINT_URL:", m.group(0) + "/v1/chat/completions")
        break"""

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArchaeologyAiTabContent(
    state: RadarUiState,
    onAiConfigUpdated: (AiEndpointConfig) -> Unit,
    onTrainingNotesSaved: (String) -> Unit,
    onRecordExperience: (String, String, String) -> Unit,
    onDeleteExperience: (Long) -> Unit,
    onAttachImage: (Bitmap?) -> Unit,
    onPromptChanged: (String) -> Unit,
    onSendAiMessage: (String?) -> Unit,
    onToggleAiVoiceReply: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current

    var showEndpointSettings by remember(state.aiConfig.endpointUrl) {
        mutableStateOf(state.aiConfig.endpointUrl.isBlank())
    }
    var showTrainingStudio by remember { mutableStateOf(false) }
    var showColabGuide by remember { mutableStateOf(false) }
    var isCameraOpen by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    var draftUrl by remember(state.aiConfig) { mutableStateOf(state.aiConfig.endpointUrl) }
    var draftModel by remember(state.aiConfig) { mutableStateOf(state.aiConfig.modelName) }
    var draftKey by remember(state.aiConfig) { mutableStateOf(state.aiConfig.apiKey) }
    var draftSysPrompt by remember(state.aiConfig) { mutableStateOf(state.aiConfig.systemPrompt) }

    var draftTrainingNotes by remember(state.aiTrainingNotes) { mutableStateOf(state.aiTrainingNotes) }
    var expTitle by remember { mutableStateOf("") }
    var expCategory by remember { mutableStateOf("سازه/ساروج واقعی") }
    var expOutcome by remember { mutableStateOf("") }

    val imageCapture = remember {
        runCatching {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
        }.getOrNull()
    }

    // TextToSpeech for hands-free field voice output
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        var instance: TextToSpeech? = null
        runCatching {
            instance = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val faLocale = Locale("fa", "IR")
                    val res = instance?.setLanguage(faLocale)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        instance?.language = Locale.getDefault()
                    }
                    ttsReady = true
                }
            }
        }
        instance
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                tts?.stop()
                tts?.shutdown()
            }
        }
    }

    val lastAssistantId = state.aiMessages.lastOrNull { !it.isUser }?.id
    var lastSpokenId by remember { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(lastAssistantId, ttsReady, state.aiVoiceReplyEnabled) {
        if (state.aiVoiceReplyEnabled && ttsReady && lastAssistantId != null && lastAssistantId != lastSpokenId) {
            lastSpokenId = lastAssistantId
            val textToRead = state.aiMessages.lastOrNull { !it.isUser }?.text.orEmpty()
            if (textToRead.isNotBlank()) {
                runCatching {
                    tts?.speak(textToRead.take(600), TextToSpeech.QUEUE_FLUSH, null, "ai_$lastAssistantId")
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isCameraOpen = true
            cameraError = null
        } else {
            cameraError = "برای مشاهده زنده دوربین توسط دستیار، مجوز دوربین لازم است."
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                onAttachImage(bmp)
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim()
            if (!spoken.isNullOrBlank()) {
                onPromptChanged(spoken)
                onSendAiMessage(spoken)
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "سؤال باستان‌شناسی یا میدانی خود را بگویید...")
            }
            runCatching { speechLauncher.launch(intent) }
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Header & Endpoint Status Banner (Non-Gemini Custom Open-Endpoint)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "دستیار تخصصی باستان‌شناسی (صوت + دوربین زنده)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (state.aiConfig.endpointUrl.isBlank()) {
                                "حالت آفلاین فعال است. برای هوش ابری رایگان بدون کلید، دکمه زیر را بزنید یا Google Colab / Ollama را وصل کنید."
                            } else {
                                "متصل به: ${state.aiConfig.modelName}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.aiConfig.endpointUrl.isBlank()) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    IconButton(
                        onClick = { showEndpointSettings = !showEndpointSettings },
                        modifier = Modifier.testTag("ai_settings_toggle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "تنظیمات سرور هوش مصنوعی"
                        )
                    }
                }

                if (state.aiConfig.endpointUrl.isBlank()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                showColabGuide = true
                                showEndpointSettings = true
                                Toast.makeText(context, "راهنمای اتصال به ابر گوگل کولب (GPU) باز شد. لینک تانل را وارد کنید.", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("colab_gpu_connect_button")
                        ) {
                            Text("⚡ اتصال اصلی: ابر گوگل کولب (GPU اختصاصی - سبک‌سازی تبلت)")
                        }
                        OutlinedButton(
                            onClick = {
                                val zapasConfig = AiEndpointConfig(
                                    endpointUrl = "https://text.pollinations.ai/openai",
                                    modelName = "openai",
                                    apiKey = "",
                                    systemPrompt = draftSysPrompt
                                )
                                draftUrl = zapasConfig.endpointUrl
                                draftModel = zapasConfig.modelName
                                draftKey = ""
                                onAiConfigUpdated(zapasConfig)
                                showEndpointSettings = false
                                Toast.makeText(context, "هوش مصنوعی زاپاس (رایگان و بدون سانسور باستان‌شناسی) فعال شد!", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("zapas_uncensored_backup_button")
                        ) {
                            Text("🛡️ اتصال زاپاس: هوش رایگان و بدون سانسور (موقع قطعی کولب)")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (state.aiVoiceReplyEnabled) {
                                Icons.AutoMirrored.Filled.VolumeUp
                            } else {
                                Icons.AutoMirrored.Filled.VolumeOff
                            },
                            contentDescription = "پخش صوتی پاسخ",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("پخش صوتی خودکار پاسخ‌ها", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = state.aiVoiceReplyEnabled,
                        onCheckedChange = { onToggleAiVoiceReply() },
                        modifier = Modifier.testTag("ai_tts_switch")
                    )
                }

                AnimatedVisibility(visible = showEndpointSettings) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "تنظیمات مدل دلخواه OpenAI-Compatible (بدون جمینای و بدون سانسور):",
                            style = MaterialTheme.typography.labelLarge
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = draftUrl.contains("pollinations.ai"),
                                onClick = {
                                    showColabGuide = false
                                    draftUrl = "https://text.pollinations.ai/openai"
                                    draftModel = "openai"
                                    draftKey = ""
                                },
                                label = { Text("ابری فوری (بدون کلید)") },
                                modifier = Modifier.testTag("preset_pollinations_chip")
                            )
                            FilterChip(
                                selected = showColabGuide || draftUrl.contains("trycloudflare.com"),
                                onClick = {
                                    showColabGuide = !showColabGuide
                                    draftModel = "qwen2.5vl:7b"
                                    draftKey = ""
                                    if (draftUrl.isBlank()) {
                                        draftUrl = "https://YOUR-TUNNEL.trycloudflare.com/v1/chat/completions"
                                    }
                                },
                                label = { Text("Google Colab رایگان (GPU)") },
                                modifier = Modifier.testTag("preset_colab_chip")
                            )
                            FilterChip(
                                selected = draftUrl.contains("huggingface.co"),
                                onClick = {
                                    showColabGuide = false
                                    draftUrl = "https://router.huggingface.co/v1/chat/completions"
                                    draftModel = "Qwen/Qwen2.5-VL-7B-Instruct"
                                },
                                label = { Text("Hugging Face") },
                                modifier = Modifier.testTag("preset_huggingface_chip")
                            )
                            FilterChip(
                                selected = draftUrl.contains("11434"),
                                onClick = {
                                    showColabGuide = false
                                    draftUrl = "http://192.168.1.100:11434/v1/chat/completions"
                                    draftModel = "qwen2.5vl:7b"
                                },
                                label = { Text("Ollama محلی") },
                                modifier = Modifier.testTag("preset_ollama_chip")
                            )
                            FilterChip(
                                selected = draftUrl.contains("openrouter.ai"),
                                onClick = {
                                    showColabGuide = false
                                    draftUrl = "https://openrouter.ai/api/v1/chat/completions"
                                    draftModel = "qwen/qwen2.5-vl-72b-instruct"
                                },
                                label = { Text("OpenRouter") },
                                modifier = Modifier.testTag("preset_openrouter_chip")
                            )
                            FilterChip(
                                selected = draftUrl.contains("api.groq.com"),
                                onClick = {
                                    showColabGuide = false
                                    draftUrl = "https://api.groq.com/openai/v1/chat/completions"
                                    draftModel = "meta-llama/llama-4-scout-17b-16e-instruct"
                                },
                                label = { Text("Groq") },
                                modifier = Modifier.testTag("preset_groq_chip")
                            )
                        }
                        if (showColabGuide || draftUrl.contains("trycloudflare.com")) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = "اتصال رایگان ابری با GPU گوگل کولب (۱۰۰٪ بدون سانسور و بدون نیاز به API Key):",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "۱) کد زیر را کپی و در colab.research.google.com با Runtime -> T4 GPU اجرا کنید.\n۲) لینک trycloudflare.com چاپ‌شده را در فیلد Endpoint URL جایگذاری کنید و API Key را خالی بگذارید.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(COLAB_ONE_CLICK_SCRIPT))
                                        Toast.makeText(context, "اسکریپت Google Colab کپی شد!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("copy_colab_script_button")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "کپی اسکریپت کولب", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("کپی اسکریپت ۱-کلیک Google Colab (Ollama + Tunnel)")
                                }
                            }
                        }
                        if (draftUrl.contains("huggingface.co")) {
                            Text(
                                text = "نکته Hugging Face: توکن `hf_...` باید تیک «Make calls to Inference Providers» داشته باشد. می‌توانید مدل را روی Qwen/Qwen2.5-VL-7B-Instruct یا Qwen/Qwen2.5-VL-72B-Instruct بگذارید.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        OutlinedTextField(
                            value = draftUrl,
                            onValueChange = { draftUrl = it },
                            label = { Text("Endpoint URL (/v1/chat/completions)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_endpoint_url_input")
                        )
                        OutlinedTextField(
                            value = draftModel,
                            onValueChange = { draftModel = it },
                            label = { Text("نام مدل (مثلاً Qwen/Qwen2.5-VL-7B-Instruct یا qwen2.5vl:7b)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_model_name_input")
                        )
                        OutlinedTextField(
                            value = draftKey,
                            onValueChange = { draftKey = it },
                            label = { Text("API Key / Token (برای Colab و Ollama خالی بگذارید)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_api_key_input")
                        )
                        OutlinedTextField(
                            value = draftSysPrompt,
                            onValueChange = { draftSysPrompt = it },
                            label = { Text("پرامپت تخصصی باستان‌شناسی (محدود به علم باستان‌شناسی)") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                onAiConfigUpdated(
                                    AiEndpointConfig(
                                        endpointUrl = draftUrl,
                                        modelName = draftModel,
                                        apiKey = draftKey,
                                        systemPrompt = draftSysPrompt
                                    )
                                )
                                showEndpointSettings = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("save_ai_config_button")
                        ) {
                            Text("ذخیره اتصال مدل")
                        }
                    }
                }
            }
        }

        // 1B. AI Training Notes & Confirmed Field Experience Learning Bank
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
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
                            imageVector = Icons.Default.Psychology,
                            contentDescription = "حافظه آموزش و تجربه هوش مصنوعی",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "آموزش و بانک تجربه میدانی (${state.aiExperiences.size} تجربه)",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "نکات منطقه و نتیجه‌های واقعی که اینجا ثبت کنید، خودکار به هر تحلیل بعدی هوش مصنوعی تزریق می‌شود تا دقیق‌تر یاد بگیرد.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { showTrainingStudio = !showTrainingStudio },
                        modifier = Modifier.testTag("toggle_ai_training_studio")
                    ) {
                        Text(if (showTrainingStudio) "بستن" else "آموزش / ثبت")
                    }
                }

                AnimatedVisibility(visible = showTrainingStudio) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "۱) آموزش قواعد بومی و زمین‌شناسی منطقه به هوش مصنوعی:",
                            style = MaterialTheme.typography.labelLarge
                        )
                        OutlinedTextField(
                            value = draftTrainingNotes,
                            onValueChange = { draftTrainingNotes = it },
                            label = { Text("مثلاً: در این دره شوره گچ سطحی کربنات کاذب می‌دهد؛ ساروج‌ها خاکستری و همراه با قلوه‌سنگ هستند...") },
                            minLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_training_notes_input")
                        )
                        OutlinedButton(
                            onClick = {
                                onTrainingNotesSaved(draftTrainingNotes)
                                Toast.makeText(context, "قواعد آموزشی در حافظه مدل ذخیره شد", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("save_ai_training_notes_button")
                        ) {
                            Text("ذخیره قواعد آموزشی")
                        }

                        Text(
                            text = "۲) ثبت تجربه تاییدشده میدانی (همراه با مختصات، ΔB و STFT فعلی):",
                            style = MaterialTheme.typography.labelLarge
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(
                                "سازه/ساروج واقعی",
                                "سفال/آثار فلزی",
                                "طبیعی/شوره کاذب",
                                "رطوبت/کانی طبیعی"
                            ).forEach { cat ->
                                FilterChip(
                                    selected = expCategory == cat,
                                    onClick = { expCategory = cat },
                                    label = { Text(cat) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = expTitle,
                            onValueChange = { expTitle = it },
                            label = { Text("عنوان نقطه / یافته (مثلاً: ترانشه تپه غربی)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_experience_title_input")
                        )
                        OutlinedTextField(
                            value = expOutcome,
                            onValueChange = { expOutcome = it },
                            label = { Text("نتیجه واقعی مشاهده‌شده و درسی که مدل باید بگیرد") },
                            minLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_experience_outcome_input")
                        )
                        Button(
                            onClick = {
                                onRecordExperience(expTitle, expCategory, expOutcome)
                                expTitle = ""
                                expOutcome = ""
                                Toast.makeText(context, "تجربه جدید به حافظه هوش مصنوعی اضافه شد!", Toast.LENGTH_SHORT).show()
                            },
                            enabled = expTitle.isNotBlank() || expOutcome.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("record_ai_experience_button")
                        ) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ثبت تجربه تاییدشده در حافظه یادگیری")
                        }

                        if (state.aiExperiences.isNotEmpty()) {
                            Text(
                                text = "تجربه‌های ثبت‌شده (${state.aiExperiences.size}):",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            state.aiExperiences.forEach { exp ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "[${exp.category}] ${exp.title}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = exp.learnedRuleOrOutcome,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    IconButton(onClick = { onDeleteExperience(exp.id) }) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "حذف تجربه",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Live CameraX Vision + Photo Picker + Capture Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isCameraOpen) {
                                isCameraOpen = false
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    isCameraOpen = true
                                    cameraError = null
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("toggle_camera_button")
                    ) {
                        Icon(
                            imageVector = if (isCameraOpen) Icons.Default.VideocamOff else Icons.Default.Videocam,
                            contentDescription = "دوربین زنده",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isCameraOpen) "بستن دوربین" else "اتصال دوربین زنده")
                    }

                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pick_site_photo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "انتخاب عکس",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("انتخاب عکس میدان")
                    }
                }

                cameraError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (isCameraOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    runCatching {
                                        val provider = cameraProviderFuture.get()
                                        val preview = Preview.Builder().build().also {
                                            it.surfaceProvider = previewView.surfaceProvider
                                        }
                                        provider.unbindAll()
                                        if (imageCapture != null) {
                                            provider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                imageCapture
                                            )
                                        } else {
                                            provider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview
                                            )
                                        }
                                    }.onFailure { err ->
                                        cameraError = "خطای دوربین: ${err.localizedMessage}"
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                            modifier = Modifier.matchParentSize()
                        )
                        Button(
                            onClick = {
                                val capture = imageCapture ?: run {
                                    cameraError = "ماژول ثبت تصویر روی این دستگاه در دسترس نیست."
                                    return@Button
                                }
                                capture.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            val raw = image.toBitmap()
                                            val rot = image.imageInfo.rotationDegrees
                                            val finalBmp = if (rot != 0) {
                                                val m = Matrix().apply { postRotate(rot.toFloat()) }
                                                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                                            } else raw
                                            image.close()
                                            onAttachImage(finalBmp)
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            cameraError = "ثبت فریم ناموفق بود: ${exception.localizedMessage}"
                                        }
                                    }
                                )
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(10.dp)
                                .testTag("snap_camera_frame_button")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "گرفتن فریم برای تحلیل")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ثبت فریم برای تحلیل زمین/سنگ/خاک")
                        }
                    }
                }

                state.aiAttachedImage?.let { bmp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "تصویر پیوست",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Text(
                            text = "تصویر آماده ارسال و تحلیل (${bmp.width}×${bmp.height})",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onAttachImage(null) }) {
                            Icon(Icons.Default.Close, contentDescription = "حذف تصویر پیوست")
                        }
                    }
                }
            }
        }

        // 3. Voice + Text Input Bar
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = state.aiPromptInput,
                    onValueChange = onPromptChanged,
                    label = { Text("پرسش میدانی (بافت خاک، ساروج، سفال، چینه‌شناسی یا تفسیر مختصات)") },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_prompt_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "سؤال خود را بیان کنید...")
                                }
                                runCatching { speechLauncher.launch(intent) }
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("ai_voice_mic_button")
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "صحبت صوتی")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("صحبت صوتی")
                    }

                    Button(
                        onClick = { onSendAiMessage(null) },
                        enabled = !state.isAiLoading && (state.aiPromptInput.isNotBlank() || state.aiAttachedImage != null),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("ai_send_button")
                    ) {
                        if (state.isAiLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("در حال تحلیل...")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ارسال تحلیل")
                        }
                    }
                }

                state.aiError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // 4. Conversation Feed
        state.aiMessages.forEach { msg ->
            AiMessageCard(
                message = msg,
                onSpeak = {
                    if (ttsReady) {
                        tts?.speak(msg.text.take(600), TextToSpeech.QUEUE_FLUSH, null, "msg_${msg.id}")
                    }
                },
                onSaveAsExperience = {
                    expTitle = "نتیجه بررسی میدانی"
                    expOutcome = msg.text.take(220)
                    showTrainingStudio = true
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun AiMessageCard(
    message: AiChatMessage,
    onSpeak: () -> Unit,
    onSaveAsExperience: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (message.isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
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
                Text(
                    text = if (message.isUser) "شما (میدان)" else if (message.isOfflineTelemetryMode) "آنالیز محلی تله‌متری" else "دستیار باستان‌شناسی",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (message.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
                if (!message.isUser) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onSaveAsExperience,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BookmarkAdd,
                                contentDescription = "ذخیره به عنوان تجربه یادگیری",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                        IconButton(
                            onClick = onSpeak,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "پخش صوتی این پاسخ",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            message.attachedBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "تصویر پیوست پیام",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
