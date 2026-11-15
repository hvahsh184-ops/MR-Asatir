package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.PassiveAudioViewModel
import com.example.ui.RadarViewModel
import com.example.ui.screens.ArchaeologyAiTabContent
import com.example.ui.screens.AudioPassiveDetectionTabContent
import com.example.ui.screens.FieldAiCompanionOverlay
import com.example.ui.screens.FieldReceiverTabContent
import com.example.ui.screens.LiveMapTabContent
import com.example.ui.screens.MatrixSatelliteTabContent
import com.example.ui.screens.OffsetTabContent
import com.example.ui.screens.RadarTabContent
import com.example.ui.theme.RadarSurfaceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RadarSurfaceTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    RadarAppRoot()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarAppRoot(
    viewModel: RadarViewModel = viewModel(),
    passiveAudioViewModel: PassiveAudioViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onLocationPermissionResult(fine || coarse)
    }

    LaunchedEffect(Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            viewModel.onLocationPermissionResult(true)
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    if (uiState.showMethodologyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowMethodologyDialog(false) },
            title = { Text(stringResource(R.string.methodology_title)) },
            text = { Text(stringResource(R.string.methodology_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.setShowMethodologyDialog(false) }) {
                    Text("متوجه شدم")
                }
            }
        )
    }

    LaunchedEffect(selectedTab) {
        viewModel.setFieldReceiverActive(selectedTab == 2)
    }

    var matrixSubTab by rememberSaveable { mutableIntStateOf(0) }

    val tabs = listOf(
        Pair(stringResource(R.string.tab_radar), Icons.Default.Radar),
        Pair(stringResource(R.string.tab_map), Icons.Default.Map),
        Pair(stringResource(R.string.tab_receiver), Icons.Default.Sensors),
        Pair(stringResource(R.string.tab_ai), Icons.Default.RecordVoiceOver),
        Pair(stringResource(R.string.tab_matrix), Icons.Default.SatelliteAlt),
        Pair(stringResource(R.string.tab_audio_passive), Icons.Default.GraphicEq)
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 700.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = stringResource(R.string.scope_badge),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.setShowMethodologyDialog(true) },
                            modifier = Modifier.testTag("methodology_info_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "اطلاعات روش‌شناسی علمی",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        tabs.forEachIndexed { idx, (label, icon) ->
                            NavigationBarItem(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label, maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("nav_tab_$idx")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.weight(0.1f))
                        tabs.forEachIndexed { idx, (label, icon) ->
                            NavigationRailItem(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("rail_tab_$idx")
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 680.dp)
                    ) {
                        when (selectedTab) {
                            0 -> RadarTabContent(
                                state = uiState,
                                onRequestLocationPermission = {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                onToggleStaticGpsAveraging = viewModel::toggleStaticGpsAveraging,
                                onApplyAveragedToCenter = viewModel::applyAveragedGpsToScanCenter,
                                onApplyAveragedToGcpTrue = viewModel::applyAveragedGpsToGcpTrue,
                                onUseCurrentGps = viewModel::fillInputsFromLiveGps,
                                onLatChanged = viewModel::onLatChanged,
                                onLonChanged = viewModel::onLonChanged,
                                onRadiusChanged = viewModel::onRadiusChanged,
                                onRunScan = viewModel::runLiveScan,
                                onToggleAudio = viewModel::toggleAudioGuidance,
                                onToggleTtsGuidance = viewModel::toggleTtsGuidance,
                                onSpeakCueNow = viewModel::speakCurrentNavigationCueNow,
                                onCellSelected = viewModel::selectGridCell,
                                onDetectedTargetSelected = viewModel::selectDetectedTarget
                            )
                            1 -> LiveMapTabContent(
                                state = uiState,
                                fetchTile = viewModel::fetchMapTile,
                                onLatChanged = viewModel::onLatChanged,
                                onLonChanged = viewModel::onLonChanged,
                                onRadiusChanged = viewModel::onRadiusChanged,
                                onRunScan = viewModel::runLiveScan,
                                onDetectedTargetSelected = viewModel::selectDetectedTarget,
                                onCustomTargetSelected = viewModel::selectCustomTarget,
                                onToggleAudio = viewModel::toggleAudioGuidance,
                                onToggleTtsGuidance = viewModel::toggleTtsGuidance,
                                onSpeakCueNow = viewModel::speakCurrentNavigationCueNow,
                                onCaptureTargetImagery = viewModel::captureTargetSatelliteImagery,
                                onSendImageToAi = { bmp, prompt ->
                                    viewModel.stageImageAndPromptForAi(bmp, prompt)
                                    selectedTab = 3
                                }
                            )
                            2 -> FieldReceiverTabContent(
                                state = uiState,
                                onCalibrateBaseline = viewModel::calibrateMagnetometerBaseline,
                                onSensitivityChanged = viewModel::onMagSensitivityChanged,
                                onSweepSpeedChanged = viewModel::onMagSweepSpeedChanged,
                                onDepthFilterModeChanged = viewModel::onDepthFilterModeChanged,
                                onToggleMagAudio = viewModel::toggleMagAudio,
                                onToggleAutoDetectionSound = viewModel::toggleAutoDetectionSound,
                                onOpticalFrameAnalyzed = viewModel::onOpticalFrameAnalyzed,
                                onLiveFrameCaptured = viewModel::updateLiveReceiverFrame,
                                onManualTrimRam = viewModel::manualTrimRam,
                                onCycleOverlayMode = viewModel::cycleOpticalOverlayMode,
                                onSelectCaptureAngle = viewModel::selectReceiverCaptureAngle,
                                onRecordPin = viewModel::recordFieldAnomalyPin,
                                onClearPins = viewModel::clearFieldAnomalyPins,
                                onLockPinOnMap = { pt ->
                                     viewModel.selectCustomTarget(pt)
                                     selectedTab = 1
                                 },
                                onSendPinToAi = { bmp, prompt ->
                                     viewModel.stageImageAndPromptForAi(bmp, prompt)
                                     selectedTab = 3
                                 }
                            )
                            3 -> ArchaeologyAiTabContent(
                                state = uiState,
                                onAiConfigUpdated = viewModel::updateAiConfig,
                                onTrainingNotesSaved = viewModel::updateAiTrainingNotes,
                                onRecordExperience = viewModel::recordAiExperience,
                                onDeleteExperience = viewModel::deleteAiExperience,
                                onAttachImage = viewModel::attachAiImage,
                                onPromptChanged = viewModel::onAiPromptChanged,
                                onSendAiMessage = viewModel::sendAiMessage,
                                onToggleAiVoiceReply = viewModel::toggleAiVoiceReply
                            )
                            4 -> Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = matrixSubTab == 0,
                                        onClick = { matrixSubTab = 0 },
                                        label = { Text("نقطه صفر و کالیبراسیون GCP") },
                                        leadingIcon = { Icon(Icons.Default.CompareArrows, contentDescription = null) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = matrixSubTab == 1,
                                        onClick = { matrixSubTab = 1 },
                                        label = { Text("ماتریس و ماهواره") },
                                        leadingIcon = { Icon(Icons.Default.SatelliteAlt, contentDescription = null) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (matrixSubTab == 0) {
                                    OffsetTabContent(
                                        state = uiState,
                                        onModeChanged = viewModel::onOffsetModeChanged,
                                        onGroundLatChanged = viewModel::onGroundLatChanged,
                                        onGroundLonChanged = viewModel::onGroundLonChanged,
                                        onDeltaEastChanged = viewModel::onDeltaEastChanged,
                                        onDeltaNorthChanged = viewModel::onDeltaNorthChanged,
                                        onRecalculate = viewModel::recalculateCalculatorOffset,
                                        onGcpEnabledChanged = viewModel::setGcpEnabled,
                                        onGcpImageLatChanged = viewModel::onGcpImageLatChanged,
                                        onGcpImageLonChanged = viewModel::onGcpImageLonChanged,
                                        onGcpTrueLatChanged = viewModel::onGcpTrueLatChanged,
                                        onGcpTrueLonChanged = viewModel::onGcpTrueLonChanged,
                                        onFillGcpImageFromTarget = viewModel::fillGcpImageFromCurrentTarget,
                                        onFillGcpTrueFromGps = viewModel::applyAveragedGpsToGcpTrue
                                    )
                                } else {
                                    MatrixSatelliteTabContent(state = uiState)
                                }
                            }
                            5 -> AudioPassiveDetectionTabContent(
                                viewModel = passiveAudioViewModel
                            )
                        }
                    }

                    FieldAiCompanionOverlay(
                        state = uiState,
                        visible = selectedTab != 3,
                        onSetSheetOpen = viewModel::setAiCompanionSheetOpen,
                        onToggleWatchdog = viewModel::toggleAiWatchdog,
                        onDismissWatchdogBanner = viewModel::dismissWatchdogBanner,
                        onAttachQuickPhoto = viewModel::setCompanionAttachedImage,
                        onAskCompanion = viewModel::askLiveCompanion,
                        onQuickConnectCloudAi = viewModel::connectQuickKeylessCloudAi,
                        onOpenAiSettingsTab = { selectedTab = 3 }
                    )
                }
            }
        }
    }
}
