package com.procamera.recorder.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.procamera.recorder.ui.components.FocusReticleOverlay
import com.procamera.recorder.ui.components.FrameLineOverlay
import com.procamera.recorder.ui.components.HistogramOverlay
import com.procamera.recorder.ui.components.LevelGaugeOverlay
import com.procamera.recorder.ui.components.StereoAudioMeter
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
import com.procamera.recorder.ui.viewmodel.CaptureMode
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
        // ── Preview area (left) + control sidebar (right), side-by-side rather than
        // sidebar-over-preview — real-device feedback: the sidebar visually covering part
        // of the frame (even semi-transparent) made it hard to judge the actual shot while
        // adjusting a slider. weight(1f) on the preview Box means the sidebar's
        // AnimatedVisibility genuinely reflows/shrinks the preview's available width as it
        // slides in/out, rather than just drawing on top of it.
        Row(modifier = Modifier.fillMaxSize()) {
        // ── Camera preview ─────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            // Fixed 16:9 preview buffer, deliberately NOT derived from
            // state.selectedVideoConfig/capabilities.videoConfig: RecordingPipeline.
            // startPreview()'s own doc already establishes that the preview session is
            // independent of the eventual recording resolution (picking e.g. a 4:3 or 1:1
            // recording config does not restart the preview session — see FrameLineOverlay
            // for how non-16:9 targets are instead shown as a guide overlay on top of this
            // same 16:9 preview). Deriving this from state.capabilities used to create a
            // race: capabilities is only populated *after* the first attachPreviewSurface()
            // call that this composable itself triggers, so the first composition pinned
            // the SurfaceHolder (and thus the already-negotiated Camera2 stream size) to a
            // fallback value, then a later recomposition changed the *Compose layout's*
            // aspect ratio once capabilities arrived — but SurfaceHolder.setFixedSize() on a
            // live holder cannot retroactively resize an already-configured capture session,
            // so the box and the actual delivered buffer silently diverged again (confirmed
            // via `dumpsys SurfaceFlinger --layers` on SO-51C: box settled at a stale
            // 4:3-shaped size while activeBuffer stayed 1920x1080, non-uniform transform
            // tr=[0.76,0][0,1.01]). A fixed, state-independent size removes the race
            // entirely — see [PreviewSurfaceView]'s doc for the original 1080x1080-default
            // finding this whole mechanism fixes.
            val (previewWidth, previewHeight) = if (isLandscape) 1920 to 1080 else 1080 to 1920
            val previewAspectRatio = previewWidth.toFloat() / previewHeight.toFloat()

            PreviewSurfaceView(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
                aspectRatio = previewAspectRatio,
                bufferWidth = previewWidth,
                bufferHeight = previewHeight,
                onTap = { viewModel.toggleControls() },
                // §AF/MFモード, 長押しでピント合わせ — a plain tap already toggles the
                // control sidebar (onTap above), so this app uses long-press instead of
                // Sony Photo Pro's own tap-to-focus gesture to avoid the two conflicting.
                onLongPressToFocus = { nx, ny -> viewModel.onPreviewLongPressToFocus(nx, ny) },
            )

            // Composition guide (§FrameLineAspectRatio's doc) — preview-only, does not
            // affect the recorded file's aspect ratio. Must resolve to the exact same
            // rect as PreviewSurfaceView above, so this uses the identical modifier chain
            // (fillMaxSize().aspectRatio(...), not aspectRatio(...).fillMaxSize() or
            // aspectRatio(...) alone) rather than relying on those being equivalent.
            state.settings.frameLineAspectRatio.ratio?.let { ratio ->
                FrameLineOverlay(
                    targetAspectRatio = ratio,
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(previewAspectRatio, matchHeightConstraintsFirst = isLandscape),
                )
            }

            // Focus reticle (§フォーカス位置表示) — same coordinate contract as
            // FrameLineOverlay above (must resolve to the exact same rect as
            // PreviewSurfaceView, hence the identical modifier chain), since
            // state.focusIndicator's normalizedX/Y are relative to that same surface.
            state.focusIndicator?.let { indicator ->
                FocusReticleOverlay(
                    indicator = indicator,
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(previewAspectRatio, matchHeightConstraintsFirst = isLandscape),
                )
            }

            // Level gauge (水準器) — screen-centered like a traditional artificial horizon,
            // always visible (not gated on state.showControls) for the same reason as the
            // audio meter: framing/leveling should stay checkable while adjusting controls.
            LevelGaugeOverlay(modifier = Modifier.align(Alignment.Center))

            // Audio meter — top-LEFT overlay on the preview: the right side is now reserved
            // for the docked control sidebar (see ControlPanel's doc for why it moved off a
            // full-width bottom sheet), so the meter moved to the opposite corner to stay
            // clear of it in both its shown and hidden states. Always visible (not gated on
            // state.showControls) so levels stay readable while adjusting camera controls,
            // matching a field production monitor's audio bridge. Two independent bars
            // (L/R) since the mic input is stereo and one side can clip while the other
            // doesn't (see AudioMeterUiState's per-channel fields / dsp/PeakRmsMeter.h).
            //
            // Collects viewModel.meterState itself (rather than receiving values read from
            // `state` here) so the ~30Hz meter churn only recomposes this one small host,
            // not this whole Box's content lambda — see AudioMeterUiState's doc.
            AudioMeterHost(
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 56.dp)
                    .height(150.dp),
            )

            // Histogram + gallery thumbnail — bottom-left: the one remaining corner clear
            // of the audio meter (top-left), level gauge (center), control sidebar
            // (right), and REC status (bottom-center). Always visible (not gated on
            // state.showControls) to match the other measurement overlays, though the
            // histogram itself stops updating once a recording starts (self-hides via
            // the null check — see HistogramOverlay's doc).
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Collects viewModel.histogramBins itself for the same
                // recomposition-isolation reason as AudioMeterHost above.
                HistogramHost(viewModel = viewModel)
                Spacer(Modifier.width(8.dp))
                // §ギャラリー連携 — Sony Photo Pro/Video Pro both show a thumbnail of the
                // last capture in a corner as a shortcut into the system gallery; this
                // mirrors that (real-device comparison against Photo Pro).
                GalleryThumbnailButton(
                    uri = state.lastCapturedUri,
                    isVideo = state.lastCapturedIsVideo,
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

            // Shutter row — small, always-visible (not gated on state.showControls, unlike
            // the rest of StatusOverlay it used to live inside): this must stay reachable
            // regardless of the sidebar's visibility, since it's the one thing with no other
            // always-visible confirmation (REC status) or hardware-key-only path (photo).
            //
            // §静止画/動画モード切り替え (Photo Pro/Video Pro方式): which action this row
            // shows follows [state.captureMode], set via the top-left mode toggle (see
            // StatusOverlay — placed there to match Photo Pro's own AUTO/mode pill
            // position, per real-device screenshot comparison). The hardware key
            // (MainActivity.dispatchKeyEvent → CameraControlViewModel.onShutterPressed)
            // follows the same captureMode.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state.captureMode) {
                    CaptureMode.Photo -> {
                        // One-shot still capture. Disabled while RECORDING (canCapturePhoto
                        // is PREVIEWING-only) — see CameraControlViewModel.capturePhoto's
                        // doc for the real-device crash this avoids. RECORDING can't
                        // actually happen while in Photo mode (the toggle above is disabled
                        // mid-recording), but this mirrors the ViewModel's own guard as a
                        // second line of defense rather than assuming that invariant here.
                        //
                        // §AF/MFモード, シャッター半押し相当: a touchscreen button has no
                        // real two-stage press, so ACTION_DOWN triggers AF (mirroring a
                        // physical half-press) and release fires the actual capture —
                        // Modifier.clickable can't express "do X on down, Y on up", hence
                        // the raw pointerInput gesture instead of clickable here.
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .border(1.dp, OnSurfaceSecondary.copy(alpha = 0.5f), CircleShape)
                                .pointerInput(state.canCapturePhoto) {
                                    if (!state.canCapturePhoto) return@pointerInput
                                    awaitEachGesture {
                                        awaitFirstDown()
                                        viewModel.onShutterHalfPress()
                                        val up = waitForUpOrCancellation()
                                        if (up != null) viewModel.capturePhoto()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "📷",
                                fontSize = 16.sp,
                                color = if (state.canCapturePhoto) OnSurfacePrimary else OnSurfaceSecondary.copy(alpha = 0.4f),
                            )
                        }
                    }
                    CaptureMode.Video -> {
                        RecIndicator(state = state)
                    }
                }
            }
        }

        // Control sidebar — right-docked vertical strip (Sony Video Pro style). A Row
        // sibling of the preview Box above (not an overlay inside it — see this Row's own
        // doc for why), so its AnimatedVisibility genuinely reflows the preview's available
        // width rather than just drawing on top of it. Still slides in from the right edge.
        androidx.compose.animation.AnimatedVisibility(
            visible = state.showControls,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut(),
            modifier = Modifier.fillMaxHeight(),
        ) {
            ControlPanel(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp),
            )
        }
        } // end Row(preview + sidebar)

        // ── Error / thermal banners (stacked, error on top) ──────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        ) {
            AnimatedVisibility(
                visible = state.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
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

            // §4.6: persists as long as thermalStatus stays >= SEVERE (no dismiss — this
            // isn't a transient error, the condition has to actually clear). Recording
            // quality is not auto-reduced (spec decision — left to the user); this banner
            // is the entire user-facing response implemented so far, see ThermalMonitor's
            // doc for the deferred preview resolution/fps step-down.
            AnimatedVisibility(
                visible = state.isThermalWarning,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    text = "端末が高温になっています。録画の中断を検討してください。",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Amber.copy(alpha = 0.92f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
        // No left-side REC indicator here anymore — it moved to its own always-visible
        // bottom-center overlay (MainScreen's Box) since this whole StatusOverlay hides
        // with state.showControls, and REC status must never be hideable (see that call
        // site's doc).

        // ── Left: capture mode toggle (§静止画/動画モード切り替え) ────────────────
        // Real-device feedback: Sony Photo Pro puts its own AUTO/video-mode pill in this
        // same top corner (screenshot comparison against the real app) — matching that
        // placement rather than the bottom-center spot this used to live in.
        CaptureModeToggle(
            mode = state.captureMode,
            enabled = !state.isRecording,
            onSelect = { viewModel.setCaptureMode(it) },
        )

        // ── Center: video config ─────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            val configText = state.capabilities?.let {
                val w = state.selectedVideoConfig?.width ?: it.videoConfig.width
                val h = state.selectedVideoConfig?.height ?: it.videoConfig.height
                val fps = state.selectedVideoConfig?.frameRate ?: it.videoConfig.frameRate
                val mbps = (state.selectedVideoConfig?.bitrate ?: it.videoConfig.bitrate) / 1_000_000
                // Display-only aperture readout (phone lenses are fixed-aperture — see
                // CameraCapabilities.apertureFNumber's doc) appended alongside the other
                // fixed specs of the current lens/format, not the adjustable ones (those —
                // ISO/shutter — live in the sidebar sliders, not this summary line).
                val fStop = it.apertureFNumber?.let { f -> " F%.1f".format(f) } ?: ""
                "${w}×${h} ${fps}fps ${mbps}Mbps$fStop"
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

/**
 * Collects [CameraControlViewModel.meterState] itself and feeds [StereoAudioMeter] — see
 * that flow's doc (AudioMeterUiState) for why this state read must happen *inside* this
 * small leaf composable rather than in the caller (which would just move the ~30Hz
 * recomposition cost up into the caller's own scope, defeating the point).
 */
@Composable
private fun AudioMeterHost(viewModel: CameraControlViewModel, modifier: Modifier = Modifier) {
    val meter by viewModel.meterState.collectAsStateWithLifecycle()
    Row(modifier = modifier) {
        StereoAudioMeter(
            peakDbL = meter.peakDbL,
            peakDbR = meter.peakDbR,
            rmsDbL = meter.rmsDbL,
            rmsDbR = meter.rmsDbR,
            isClippingHeldL = meter.isClippingHeldL,
            isClippingHeldR = meter.isClippingHeldR,
        )
    }
}

/** Same reasoning as [AudioMeterHost], for [CameraControlViewModel.histogramBins]. */
@Composable
private fun HistogramHost(viewModel: CameraControlViewModel, modifier: Modifier = Modifier) {
    val bins by viewModel.histogramBins.collectAsStateWithLifecycle()
    HistogramOverlay(bins = bins, modifier = modifier)
}

/**
 * §ギャラリー連携 — small last-capture thumbnail, tap to open it (or the gallery app in
 * general, if nothing's been captured yet this process) in the system Photos app. Loads
 * the bitmap via [android.content.ContentResolver.loadThumbnail] (API 29+, matches this
 * app's minSdk) rather than a full-image decode — cheap enough to redo on every
 * [uri] change without a caching layer.
 */
@Composable
private fun GalleryThumbnailButton(uri: Uri?, isVideo: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = if (uri == null) {
            null
        } else {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.loadThumbnail(uri, android.util.Size(160, 160), null)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, OnSurfaceSecondary.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .background(SurfaceDark)
            .clickable {
                val intent = if (uri != null) {
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (isVideo) "video/*" else "image/*")
                } else {
                    // Nothing captured yet this process — open the gallery app's own grid
                    // rather than a specific item (real camera apps fall back the same way
                    // when their own "last shot" thumbnail has nothing to point at).
                    Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                }
                // 実機で発見: this device has both Google Photos and "Photos Go" installed
                // (confirmed via `dumpsys package resolver-table` — both register for
                // `vnd.android.cursor.dir/image` VIEW), so the plain implicit Intent shows
                // a chooser on *every* tap. Setting just the *package* (an earlier attempt)
                // wasn't enough either — real-device feedback confirmed the dialog kept
                // appearing — because Google Photos alone registers *multiple* matching
                // activities for this same intent shape (HostPhotoPagerActivity,
                // ExternalPickerActivity, ...; also seen in that same dumpsys output), so
                // even a package-scoped Intent was still ambiguous. Resolving with
                // MATCH_DEFAULT_ONLY (the flag that filters to components declaring
                // `<category android:name="android.intent.category.DEFAULT"/>`, i.e. "the
                // one meant to be launched implicitly like this") and then targeting that
                // *exact component* — not just the package — is what actually collapses it
                // to zero ambiguity.
                val defaultActivity = context.packageManager.resolveActivity(
                    intent,
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
                )?.activityInfo
                // A genuinely ambiguous resolution (no persisted system default, multiple
                // apps still tied) comes back as the system's own chooser stub
                // ("android" package) rather than a real target — falling through to it
                // would just show the same dialog under a different code path. Detect
                // that and fall back to explicitly picking Google Photos' own
                // DEFAULT-category activity instead of trusting this resolution.
                val targetComponent = if (defaultActivity != null && defaultActivity.packageName != "android") {
                    android.content.ComponentName(defaultActivity.packageName, defaultActivity.name)
                } else {
                    context.packageManager.queryIntentActivities(
                        Intent(intent).setPackage("com.google.android.apps.photos"),
                        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
                    ).firstOrNull()?.activityInfo?.let {
                        android.content.ComponentName(it.packageName, it.name)
                    }
                }
                if (targetComponent != null) intent.component = targetComponent
                context.startActivity(intent)
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(text = "🖼", fontSize = 16.sp)
        }
    }
}

/**
 * PHOTO ⇄ VIDEO segmented toggle (§静止画/動画モード切り替え, Photo Pro/Video Pro方式) —
 * see the call site's doc for why this replaces always showing both a still-capture
 * button and a REC indicator.
 */
@Composable
private fun CaptureModeToggle(
    mode: CaptureMode,
    enabled: Boolean,
    onSelect: (CaptureMode) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(CaptureMode.Photo to "📷", CaptureMode.Video to "🎥").forEach { (candidate, icon) ->
            val isSelected = mode == candidate
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Amber else Color.Transparent)
                    .border(1.dp, if (isSelected) Amber else OnSurfaceSecondary.copy(alpha = 0.5f), CircleShape)
                    .clickable(enabled = enabled) { onSelect(candidate) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = icon,
                    fontSize = 12.sp,
                    color = if (isSelected) SurfaceBlack else OnSurfaceSecondary.copy(alpha = if (enabled) 1f else 0.4f),
                )
            }
            Spacer(Modifier.width(6.dp))
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

/**
 * Right-docked vertical control strip (CAMERA/AUDIO tabs + sliders), styled after Sony's
 * Video Pro/Photo Pro control layout (Sony__________.pdf: 露出コントロール・ホワイトバランス
 * ・UIアシスト機能が撮影画面の脇に常設される設計). Previously this was a full-width bottom
 * sheet that, once expanded, covered most of the frame below the top status readout —
 * real-device feedback flagged this as actively hard to use (you can't judge framing/focus
 * while adjusting a slider). Docking it to a fixed-width strip on one edge instead leaves
 * the rest of the preview visible throughout. The tab row stays pinned at the top; the
 * slider content below scrolls, since the full CAMERA/AUDIO control set is taller than the
 * strip at any reasonable width.
 */
@Composable
private fun ControlPanel(
    state: CameraUiState,
    viewModel: CameraControlViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(SurfaceDark.copy(alpha = 0.90f))
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Tab row (pinned — does not scroll with the content below)
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

        Column(
            modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            when (state.activePanel) {
                ControlPanel.Camera -> CameraControlsPanel(
                    state = state,
                    viewModel = viewModel,
                )

                ControlPanel.Audio -> AudioControlsPanel(
                    state = state,
                    viewModel = viewModel,
                    onInputGainChange = viewModel::setInputGainDb,
                    onMakeupGainChange = viewModel::setMakeupGainDb,
                    onHighPassEnabledChange = viewModel::setHighPassEnabled,
                    onHighPassCutoffChange = viewModel::setHighPassCutoffHz,
                    onEqGainChange = viewModel::setEqBandGain,
                    onEqFreqChange = viewModel::setEqBandFreq,
                    onEqQChange = viewModel::setEqBandQ,
                    onMonitorToggle = viewModel::setMonitoringEnabled,
                )
            }
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
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // maxLines/softWrap=false: mm labels ("135mm") run wider than the old
                        // "×" ratio labels ("3x") did, and this Box has no fixed width — a
                        // real-device check showed the longest label wrapping to two lines
                        // ("88m"/"m") inside its pill instead of just fitting on one.
                        Text(
                            text = lens.zoomLabel,
                            color = tc,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                        )
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

        // FPS — display-only (not adjustable here; it's derived from the selected
        // recording resolution in Settings), placed alongside ISO/SHUTTER per real-device
        // feedback that it should be visible without checking the top-status readout.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "FPS", color = OnSurfacePrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(text = "${state.fps}fps", color = OnSurfaceSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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

        // Focus — AF/MF mode switch (§AF/MFモード). Long-press on the preview also drops
        // into MF at the tapped point (PreviewSurfaceView's onLongPressToFocus), so this
        // switch's state can change without the user touching it directly here.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.afAuto) "AF" else "MF",
                color = Amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
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

// See CameraUiState.inputGainDb's doc for why this range is biased toward attenuation.
private const val INPUT_GAIN_MIN_DB = -24f
private const val INPUT_GAIN_MAX_DB = 12f

// Boost-only, unlike GAIN above: see CameraUiState.makeupGainDb's doc — this is for
// pushing a too-quiet source louder than INPUT_GAIN_MAX_DB alone can reach, not for
// gain-staging the input. 0dB (bottom of the range) is the default/off position.
private const val MAKEUP_GAIN_MIN_DB = 0f
private const val MAKEUP_GAIN_MAX_DB = 18f

// Covers the standard wind/rumble-cut range field recorders expose (roughly 40-240Hz);
// see CameraUiState.highPassCutoffHz's doc.
private const val HIGH_PASS_CUTOFF_MIN_HZ = 40f
private const val HIGH_PASS_CUTOFF_MAX_HZ = 240f

@Composable
private fun AudioControlsPanel(
    state: CameraUiState,
    viewModel: CameraControlViewModel,
    onInputGainChange: (Float) -> Unit,
    onMakeupGainChange: (Float) -> Unit,
    onHighPassEnabledChange: (Boolean) -> Unit,
    onHighPassCutoffChange: (Float) -> Unit,
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
        // USB Audio > 有線 > 内蔵 優先ルーティング(§4.2)の実際の着地先 — see
        // AudioDeviceRouter's doc for why this shows what was actually opened, not just
        // what was requested.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "INPUT",
                color = OnSurfaceSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Text(
                text = state.audioInputDeviceLabel,
                color = OnSurfacePrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // xRun / overrun stats — collects viewModel.meterState itself (see AudioStatsRow's
        // doc) so this ~30Hz churn only recomposes this one row, not the EQ sliders below.
        AudioStatsRow(viewModel = viewModel)

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )

        // Input gain (record level) — first in the chain, so it comes first in the UI too
        // (audio.pdf調査: set the level before shaping it). Asymmetric range biased toward
        // attenuation — see CameraUiState.inputGainDb's doc for why.
        ManualControlSlider(
            label = "GAIN",
            value = ((state.inputGainDb - INPUT_GAIN_MIN_DB) / (INPUT_GAIN_MAX_DB - INPUT_GAIN_MIN_DB))
                .coerceIn(0f, 1f),
            valueText = state.inputGainDisplayText,
            onValueChange = { norm ->
                onInputGainChange(INPUT_GAIN_MIN_DB + norm * (INPUT_GAIN_MAX_DB - INPUT_GAIN_MIN_DB))
            },
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )

        // High-pass filter (風切り音/ハンドリングノイズ対策のローカット) — before the EQ
        // both in the DSP chain and here in the UI (see dsp/HighPassFilter.h's doc for why
        // this order matters: a boosted EQ Low band must never re-amplify what this is
        // meant to remove). Off by default; the cutoff slider only has an audible effect
        // while the switch is on, so it's dimmed to match.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "HIGH-PASS FILTER",
                color = OnSurfaceSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Switch(
                checked = state.highPassEnabled,
                onCheckedChange = onHighPassEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Amber,
                    checkedTrackColor = Amber.copy(alpha = 0.3f),
                ),
            )
        }
        ManualControlSlider(
            label = "CUTOFF",
            value = ((state.highPassCutoffHz - HIGH_PASS_CUTOFF_MIN_HZ) / (HIGH_PASS_CUTOFF_MAX_HZ - HIGH_PASS_CUTOFF_MIN_HZ))
                .coerceIn(0f, 1f),
            valueText = state.highPassCutoffDisplayText,
            enabled = state.highPassEnabled,
            onValueChange = { norm ->
                onHighPassCutoffChange(HIGH_PASS_CUTOFF_MIN_HZ + norm * (HIGH_PASS_CUTOFF_MAX_HZ - HIGH_PASS_CUTOFF_MIN_HZ))
            },
        )

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

        // Makeup gain — after EQ, before the limiter in the actual DSP chain (see
        // dsp/MakeupGain.h), so it's placed here in the UI too, after the EQ bands above.
        // Boost-only and defaults to 0 (off): raising this also raises the noise floor by
        // the same ratio, so the caption below states that trade-off up front rather than
        // leaving it to be discovered by ear.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "MAKEUP GAIN",
                color = OnSurfaceSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Text(
                text = "オフ推奨: ノイズも一緒に持ち上がります",
                color = OnSurfaceSecondary,
                fontSize = 9.sp,
            )
        }
        ManualControlSlider(
            label = "BOOST",
            value = ((state.makeupGainDb - MAKEUP_GAIN_MIN_DB) / (MAKEUP_GAIN_MAX_DB - MAKEUP_GAIN_MIN_DB))
                .coerceIn(0f, 1f),
            valueText = state.makeupGainDisplayText,
            onValueChange = { norm ->
                onMakeupGainChange(MAKEUP_GAIN_MIN_DB + norm * (MAKEUP_GAIN_MAX_DB - MAKEUP_GAIN_MIN_DB))
            },
        )

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

/** Collects [CameraControlViewModel.meterState] itself — see [AudioMeterHost]'s doc for
 * why (same reasoning, different leaf). */
@Composable
private fun AudioStatsRow(viewModel: CameraControlViewModel) {
    val meter by viewModel.meterState.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Louder of the two channels — a compact single figure for this debug-style row;
        // the full per-channel breakdown is the StereoAudioMeter overlay on the preview.
        // formatDbChip (not String.format) — this row recomposes at the same meter-polling
        // rate as StereoAudioMeter/AudioMeterBar; see AudioMeterBar.kt's formatDb doc for
        // why String.format's per-call Formatter allocation matters at that rate.
        StatChip(label = "PEAK", value = formatDbChip(maxOf(meter.peakDbL, meter.peakDbR)))
        StatChip(label = "RMS", value = formatDbChip(maxOf(meter.rmsDbL, meter.rmsDbR)))
        StatChip(label = "XRUN", value = meter.xrunCount.toString(), warn = meter.xrunCount > 0)
        StatChip(label = "OVRN", value = meter.ringBufferOverrunCount.toString(), warn = meter.ringBufferOverrunCount > 0)
    }
}

/** "%.1fdB".format() without java.util.Formatter's per-call allocation — see call site's doc. */
private fun formatDbChip(db: Float): String {
    val tenths = Math.round(db * 10)
    val sign = if (tenths < 0) "-" else ""
    val absTenths = kotlin.math.abs(tenths)
    return "$sign${absTenths / 10}.${absTenths % 10}dB"
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

