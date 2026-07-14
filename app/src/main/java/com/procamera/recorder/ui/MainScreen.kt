package com.procamera.recorder.ui

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.procamera.recorder.ui.components.AudioMeterBar
import com.procamera.recorder.ui.components.FocusSlider
import com.procamera.recorder.ui.components.IsoSlider
import com.procamera.recorder.ui.components.ManualControlSlider
import com.procamera.recorder.ui.components.PreviewSurfaceView
import com.procamera.recorder.ui.components.ShutterSlider
import com.procamera.recorder.ui.components.WhiteBalanceSlider
import com.procamera.recorder.ui.theme.Amber
import com.procamera.recorder.ui.theme.OnSurfacePrimary
import com.procamera.recorder.ui.theme.OnSurfaceSecondary
import com.procamera.recorder.ui.theme.RecRed
import com.procamera.recorder.ui.theme.SurfaceBlack
import com.procamera.recorder.ui.theme.SurfaceDark
import com.procamera.recorder.ui.viewmodel.CameraControlViewModel
import com.procamera.recorder.ui.viewmodel.CameraUiState
import com.procamera.recorder.ui.viewmodel.ControlPanel
import com.procamera.recorder.ui.viewmodel.EqBandState
import com.procamera.recorder.ui.viewmodel.RecordingUiState
import com.procamera.recorder.ui.components.SettingsBottomSheet
import kotlin.math.roundToInt

/**
 * Root composable for the ProCamera recording screen (§4.5).
 *
 * Layout (portrait):
 * ```
 * ┌─────────────────────────────┐ ← statusBarsPadding
 * │ [● REC 00:12]  [SEG 1]  [▽]│  Status overlay (top)
 * │─────────────────────────────│
 * │                             │
 * │      CAMERA PREVIEW         │  SurfaceView (16:9 portion)
 * │                             │
 * │  [▌] Audio meter (side)     │
 * │─────────────────────────────│
 * │ [Camera] [Audio]  tabs      │  Control panel (bottom)
 * │  ISO ────────●──  400       │
 * │  SHUTTER ●────── 1/60       │
 * │  FOCUS ──────●──  5m        │
 * │  WB ─────●───── 5500K       │
 * │─────────────────────────────│
 * │         [●] REC             │  Record button
 * └─────────────────────────────┘ ← navigationBarsPadding
 * ```
 */
@Suppress("MissingPermission") // Permissions are checked in MainActivity before this is shown.
@Composable
fun MainScreen(
    viewModel: CameraControlViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBlack),
    ) {
        // ── Camera preview (fills entire screen behind everything) ───────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {
                    viewModel.toggleControls()
                },
            contentAlignment = Alignment.Center,
        ) {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val config = state.selectedVideoConfig ?: state.capabilities?.videoConfig
            val previewAspectRatio = if (config != null) {
                if (isLandscape) {
                    config.width.toFloat() / config.height.toFloat()
                } else {
                    config.height.toFloat() / config.width.toFloat()
                }
            } else {
                if (isLandscape) 16f / 9f else 9f / 16f
            }

            PreviewSurfaceView(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
                aspectRatio = previewAspectRatio,
            )

            // Audio meter — right side overlay on the preview.
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .height(200.dp),
            ) {
                AudioMeterBar(
                    peakDb = state.peakDb,
                    rmsDb = state.rmsDb,
                    isClippingHeld = state.isClippingHeld,
                )
            }

            // Top status overlay on the preview.
            androidx.compose.animation.AnimatedVisibility(
                visible = state.showControls,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            ) {
                StatusOverlay(
                    state = state,
                    viewModel = viewModel,
                )
            }
            
            // Bottom controls overlay
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.showControls,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                ) {
                    ControlPanel(
                        state = state,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                
                RecordButtonBar(
                    state = state,
                    onRecordToggle = {
                        if (state.isRecording) viewModel.stopRecording()
                        else viewModel.startRecording()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                )
            }
        }

        // ── Error banner ──────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = state.errorMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        ) {
            state.errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RecRed.copy(alpha = 0.92f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { viewModel.dismissError() },
                )
            }
        }
        
        if (state.settings.showSettingsSheet) {
            SettingsBottomSheet(
                state = state,
                viewModel = viewModel,
                onDismiss = { viewModel.closeSettings() }
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Status overlay (top of preview)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatusOverlay(
    state: CameraUiState,
    viewModel: CameraControlViewModel,
    modifier: Modifier = Modifier,
) {
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(SurfaceBlack.copy(alpha = 0.75f), Color.Transparent),
        startY = 0f,
        endY = Float.POSITIVE_INFINITY,
    )

    Row(
        modifier = modifier
            .background(gradientBrush)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Left: REC indicator + elapsed time ──────────────────────────────
        RecIndicator(state = state)

        // ── Center: video config ─────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            val configText = state.capabilities?.let {
                val w = state.selectedVideoConfig?.width ?: it.videoConfig.width
                val h = state.selectedVideoConfig?.height ?: it.videoConfig.height
                val fps = state.selectedVideoConfig?.frameRate ?: it.videoConfig.frameRate
                val mbps = (state.selectedVideoConfig?.bitrate ?: it.videoConfig.bitrate) / 1_000_000
                "${w}×${h} ${fps}fps ${mbps}Mbps"
            } ?: "—"
            Text(
                text = configText,
                color = OnSurfaceSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            // Settings Icon
            Text(
                text = "⚙",
                color = Amber,
                fontSize = 16.sp,
                modifier = Modifier
                    .clickable { viewModel.openSettings() }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        // ── Right: segment + storage ─────────────────────────────────────────
        Column(horizontalAlignment = Alignment.End) {
            if (state.isRecording) {
                Text(
                    text = "SEG ${state.currentSegment + 1}",
                    color = OnSurfaceSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = state.storageRemainingFormatted,
                color = OnSurfaceSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun RecIndicator(state: CameraUiState) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rec_blink_alpha",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Red/grey dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (state.isRecording) RecRed.copy(alpha = alpha) else OnSurfaceSecondary.copy(alpha = 0.4f),
                ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (state.isRecording) state.elapsedFormatted else "STANDBY",
            color = if (state.isRecording) Color.White else OnSurfaceSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Control panel
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun ControlPanel(
    state: CameraUiState,
    viewModel: CameraControlViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(SurfaceDark.copy(alpha = 0.85f))
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
    ) {
        // Tab row
        val tabs = listOf("CAMERA", "AUDIO")
        val selectedIndex = if (state.activePanel == ControlPanel.Camera) 0 else 1

        SecondaryTabRow(
            selectedTabIndex = selectedIndex,
            containerColor = SurfaceDark,
            contentColor = Amber,
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedIndex == index,
                    onClick = {
                        viewModel.setActivePanel(if (index == 0) ControlPanel.Camera else ControlPanel.Audio)
                    },
                    text = {
                        Text(
                            text = title,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                            color = if (selectedIndex == index) Amber else OnSurfaceSecondary,
                        )
                    },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

        when (state.activePanel) {
            ControlPanel.Camera -> CameraControlsPanel(
                state = state,
                viewModel = viewModel,
            )

            ControlPanel.Audio -> AudioControlsPanel(
                state = state,
                onEqGainChange = viewModel::setEqBandGain,
                onEqFreqChange = viewModel::setEqBandFreq,
                onEqQChange = viewModel::setEqBandQ,
                onMonitorToggle = viewModel::setMonitoringEnabled,
            )
        }
    }
}

// ── Camera controls tab ───────────────────────────────────────────────────────

@Composable
private fun CameraControlsPanel(
    state: CameraUiState,
    viewModel: CameraControlViewModel,
) {
    val caps = state.capabilities

    // switchLens() requires CAMERA — safe here because this whole screen only composes
    // after MainActivity's PermissionGate has already verified CAMERA is granted; lint
    // can't see across that composable boundary, so it's suppressed at this call site.
    @Suppress("MissingPermission")
    fun onLensSelected(lens: com.procamera.recorder.camera.CameraCapabilityInspector.AvailableLens) {
        viewModel.switchLens(lens)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        // Lens selector
        if (state.availableLenses.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.availableLenses.forEach { lens ->
                    val isSelected = state.selectedLensCameraId == lens.cameraId
                    val bg by animateColorAsState(if (isSelected) Amber else SurfaceDark, label = "lens_bg")
                    val tc by animateColorAsState(if (isSelected) Color.Black else OnSurfaceSecondary, label = "lens_tc")
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(bg)
                            .border(1.dp, if (isSelected) Amber else MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                            .clickable { onLensSelected(lens) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = lens.zoomLabel, color = tc, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        // Zoom
        if (state.maxZoomRatio > 1.0f) {
            val zoomNorm = ((state.zoomRatio - 1f) / (state.maxZoomRatio - 1f)).coerceIn(0f, 1f)
            ManualControlSlider(
                label = "ZOOM",
                value = zoomNorm,
                valueText = state.zoomDisplayText,
                onValueChange = { norm ->
                    val zoom = 1f + norm * (state.maxZoomRatio - 1f)
                    viewModel.setZoom(zoom)
                },
            )
        }

        // ISO
        if (caps != null) {
            IsoSlider(
                iso = state.iso,
                isoRange = caps.isoRange,
                onIsoChange = viewModel::setIso,
            )
        } else {
            ManualControlSlider(
                label = "ISO",
                value = 0.4f,
                valueText = state.iso.toString(),
                onValueChange = {},
                enabled = false,
            )
        }

        // Shutter
        if (caps != null) {
            ShutterSlider(
                exposureTimeNanos = state.exposureTimeNanos,
                rangeNanos = caps.exposureTimeRangeNanos,
                onValueChange = viewModel::setExposureTime,
            )
        } else {
            ManualControlSlider(
                label = "SHUTTER",
                value = 0.5f,
                valueText = state.shutterDisplayText,
                onValueChange = {},
                enabled = false,
            )
        }

        // Shutter presets (LED-PWM avoidance — §4.1)
        ShutterPresetRow(
            currentExposureNanos = state.exposureTimeNanos,
            onPresetSelect = { preset -> viewModel.setExposureTime(preset.exposureTimeNanos()) },
        )

        // Focus
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "FOCUS", color = OnSurfacePrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Switch(
                checked = state.afAuto,
                onCheckedChange = { viewModel.setAfAuto(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Amber, checkedTrackColor = Amber.copy(alpha = 0.3f))
            )
        }
        FocusSlider(
            focusDiopters = state.focusDistanceDiopters,
            minFocusDistance = caps?.minFocusDistanceDiopters ?: 10f,
            onFocusChange = viewModel::setFocusDistance,
        )

        // White balance
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "WB", color = OnSurfacePrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Switch(
                checked = state.wbAuto,
                onCheckedChange = { viewModel.setWbAuto(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Amber, checkedTrackColor = Amber.copy(alpha = 0.3f))
            )
        }
        WhiteBalanceSlider(
            kelvin = state.kelvin,
            enabled = caps?.supportsManualWb ?: false,
            onKelvinChange = viewModel::setKelvin,
        )
        
        if (caps?.supportsManualWb == false) {
            Text(
                text = "Manual WB: この機種ではサポートされていません",
                color = OnSurfaceSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        } else {
            WbPresetRow(
                currentKelvin = state.kelvin,
                onPresetSelect = { presetKelvin -> viewModel.setKelvin(presetKelvin) }
            )
        }
    }
}

@Composable
private fun WbPresetRow(
    currentKelvin: Double,
    onPresetSelect: (Double) -> Unit,
) {
    val presets = listOf(
        "☀️" to 5500.0,
        "☁️" to 6500.0,
        "蛍光灯" to 4000.0,
        "電球" to 3200.0,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { (label, kelvin) ->
            // Use a simple tolerance for selection highlighting
            val isSelected = kotlin.math.abs(currentKelvin - kelvin) < 100.0
            val bg by animateColorAsState(if (isSelected) Amber else SurfaceDark, label = "preset_bg")
            val textColor by animateColorAsState(if (isSelected) Color.Black else OnSurfaceSecondary, label = "preset_text")
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(bg)
                    .border(1.dp, if (isSelected) Amber else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .clickable { onPresetSelect(kelvin) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ShutterPresetRow(
    currentExposureNanos: Long,
    onPresetSelect: (com.procamera.recorder.camera.CaptureRangeClamper.ShutterPreset) -> Unit,
) {
    val presets = com.procamera.recorder.camera.CaptureRangeClamper.ShutterPreset.entries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            val isSelected = currentExposureNanos == preset.exposureTimeNanos()
            val bg by animateColorAsState(
                if (isSelected) Amber else SurfaceDark,
                label = "preset_bg_${preset.name}",
            )
            val textColor by animateColorAsState(
                if (isSelected) Color.Black else OnSurfaceSecondary,
                label = "preset_text_${preset.name}",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(bg)
                    .border(1.dp, if (isSelected) Amber else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .clickable { onPresetSelect(preset) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "1/${preset.fractionDenominator}",
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Text(
            text = "フリッカー",
            color = OnSurfaceSecondary,
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

// ── Audio controls tab ────────────────────────────────────────────────────────

@Composable
private fun AudioControlsPanel(
    state: CameraUiState,
    onEqGainChange: (Int, Float) -> Unit,
    onEqFreqChange: (Int, Float) -> Unit,
    onEqQChange: (Int, Float) -> Unit,
    onMonitorToggle: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        // xRun / overrun stats
        AudioStatsRow(state = state)

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )

        // EQ bands
        Text(
            text = "3-BAND EQ",
            color = OnSurfaceSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        state.eqBands.forEachIndexed { index, band ->
            EqBandRow(
                band = band,
                bandIndex = index,
                onGainChange = { gain -> onEqGainChange(index, gain) },
                onFreqChange = { freq -> onEqFreqChange(index, freq) },
                onQChange = { q -> onEqQChange(index, q) },
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )

        // Monitor toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "MONITOR",
                    color = OnSurfacePrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "有線/USB ヘッドホン接続時のみ有効",
                    color = OnSurfaceSecondary,
                    fontSize = 10.sp,
                )
            }
            Switch(
                checked = state.monitoringEnabled,
                onCheckedChange = onMonitorToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Amber,
                    checkedTrackColor = Amber.copy(alpha = 0.3f),
                ),
            )
        }
    }
}

@Composable
private fun AudioStatsRow(state: CameraUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatChip(label = "PEAK", value = "%.1fdB".format(state.peakDb))
        StatChip(label = "RMS", value = "%.1fdB".format(state.rmsDb))
        StatChip(label = "XRUN", value = state.xrunCount.toString(), warn = state.xrunCount > 0)
        StatChip(label = "OVRN", value = state.ringBufferOverrunCount.toString(), warn = state.ringBufferOverrunCount > 0)
    }
}

@Composable
private fun StatChip(label: String, value: String, warn: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = OnSurfaceSecondary, fontSize = 9.sp, letterSpacing = 0.8.sp)
        Text(
            text = value,
            color = if (warn) RecRed else OnSurfacePrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun EqBandRow(
    band: EqBandState,
    bandIndex: Int,
    onGainChange: (Float) -> Unit,
    onFreqChange: (Float) -> Unit,
    onQChange: (Float) -> Unit,
) {
    val gainNorm = ((band.gainDb - band.gainRange.start) / (band.gainRange.endInclusive - band.gainRange.start)).coerceIn(0f, 1f)
    val freqNorm = ((band.freqHz - band.freqRange.start) / (band.freqRange.endInclusive - band.freqRange.start)).coerceIn(0f, 1f)
    val qNorm = ((band.q - band.qRange.start) / (band.qRange.endInclusive - band.qRange.start)).coerceIn(0f, 1f)

    val gainSign = if (band.gainDb >= 0) "+" else ""
    val gainText = "$gainSign${band.gainDb.roundToInt()}dB"
    val freqText = if (band.freqHz >= 1000f) "%.1fkHz".format(band.freqHz / 1000f) else "${band.freqHz.roundToInt()}Hz"
    val qText = "Q%.1f".format(band.q)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = band.label,
                color = Amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(40.dp),
            )
            Text(text = freqText, color = OnSurfaceSecondary, fontSize = 10.sp, modifier = Modifier.width(55.dp))
            Text(text = qText, color = OnSurfaceSecondary, fontSize = 10.sp, modifier = Modifier.width(45.dp))
            Text(text = gainText, color = OnSurfacePrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        }
        // Gain slider (the most frequently adjusted)
        ManualControlSlider(
            label = "GAIN",
            value = gainNorm,
            valueText = gainText,
            onValueChange = { norm ->
                val gain = band.gainRange.start + norm * (band.gainRange.endInclusive - band.gainRange.start)
                onGainChange(gain)
            },
            modifier = Modifier.padding(0.dp),
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Record button bar
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecordButtonBar(
    state: CameraUiState,
    onRecordToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonColor by animateColorAsState(
        targetValue = when {
            state.isRecording -> RecRed
            state.canStartRecording -> RecRed.copy(alpha = 0.85f)
            else -> OnSurfaceSecondary.copy(alpha = 0.3f)
        },
        animationSpec = tween(200),
        label = "rec_button_color",
    )

    Box(
        modifier = modifier
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Big record button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.recButtonEnabled) RecRed.copy(alpha = 0.15f) else Color.Transparent,
                    )
                    .border(2.dp, if (state.recButtonEnabled) RecRed else OnSurfaceSecondary.copy(alpha = 0.3f), CircleShape)
                    .clickable(enabled = state.recButtonEnabled, onClick = onRecordToggle),
            ) {
                Box(
                    modifier = Modifier
                        .size(if (state.isRecording) 28.dp else 48.dp)
                        .clip(if (state.isRecording) RoundedCornerShape(6.dp) else CircleShape)
                        .background(buttonColor),
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = when (state.recordingState) {
                    RecordingUiState.Idle -> "準備中"
                    RecordingUiState.StartingPreview -> "カメラ起動中…"
                    RecordingUiState.Previewing -> "● REC"
                    RecordingUiState.StartingRecording -> "録画開始中…"
                    RecordingUiState.Recording -> "■ STOP"
                    RecordingUiState.Stopping -> "停止中…"
                },
                color = if (state.recButtonEnabled) OnSurfacePrimary else OnSurfaceSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
