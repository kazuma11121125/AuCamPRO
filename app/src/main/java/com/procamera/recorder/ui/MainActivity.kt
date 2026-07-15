package com.procamera.recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.procamera.recorder.ui.theme.Amber
import com.procamera.recorder.ui.theme.OnSurfaceSecondary
import com.procamera.recorder.ui.theme.ProCameraTheme
import com.procamera.recorder.ui.theme.SurfaceBlack
import com.procamera.recorder.ui.viewmodel.CameraControlViewModel

/**
 * Host Activity for ProCamera.
 *
 * Responsibilities (by intent, matching §4.5/§4.6 design):
 * - Requests CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS permissions before composing [MainScreen].
 * - Sets [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] so the viewfinder stays alive.
 * - Uses edge-to-edge display so [MainScreen] can lay out behind the status bar and nav bar
 *   (using statusBarsPadding / navigationBarsPadding in the composable hierarchy).
 *
 * Everything else (camera session lifecycle, recording pipeline, ViewModel) lives in
 * [CameraControlViewModel] and [MainScreen] — the Activity is intentionally thin.
 */
class MainActivity : ComponentActivity() {

    // Same instance MainScreen's `viewModel()` call resolves later (both go through this
    // Activity's ViewModelStore) — obtained here too so the hardware key handler below has
    // somewhere to dispatch to without threading a reference through the Compose tree.
    private val viewModel: CameraControlViewModel by viewModels()

    // See dispatchKeyEvent's doc — debounces rapid repeated camera-key presses.
    private var lastCameraKeyAcceptedAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on while the activity is in the foreground (§4.6 requirement).
        // A WakeLock will supplement this when the Foreground Service is running (Phase 4b).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // §4.6 thermal investigation follow-up (2026-07-15): there is no Android API that
        // lets an app *ignore* thermal throttling — the SoC's DVFS governor protects the
        // hardware at the kernel/firmware level regardless of anything an app requests, and
        // real-device measurement this session confirmed the throttling itself is genuine
        // (a clean recording early in a long test session hit ~29.75fps of a 30fps target;
        // one taken after hours of continuous rebuild/install/record cycles measured only
        // ~17-18fps, with Android's own PowerManager thermal-status API misleadingly
        // reporting NONE throughout — that coarse status is not a reliable signal for this).
        // `setSustainedPerformanceMode` is the closest legitimate lever: it doesn't disable
        // throttling, but it asks the system to favor a stable *sustained* clock speed from
        // the start over the usual burst-to-max-turbo-then-throttle-hard pattern — a better
        // fit for a continuous 4K encode workload like this app's than the default policy.
        if (getSystemService(android.os.PowerManager::class.java)?.isSustainedPerformanceModeSupported == true) {
            window.setSustainedPerformanceMode(true)
        }

        // Edge-to-edge: the composable uses statusBarsPadding / navigationBarsPadding.
        enableEdgeToEdge()

        setContent {
            ProCameraTheme {
                PermissionGate {
                    MainScreen()
                }
            }
        }
    }

    /**
     * Xperia's dedicated hardware camera/shutter key (Sony__________.pdf: "カメラキー(シャッ
     * ターボタン)の割り当てAssign shutter button") toggles REC, mirroring the on-screen
     * button (§[CameraControlViewModel.toggleRecording]). Handled at `dispatchKeyEvent`
     * rather than `onKeyDown` so it's intercepted ahead of Compose's own focus-based key
     * handling — none of the current UI needs `KEYCODE_CAMERA` for anything else, so there
     * is nothing to conflict with. `repeatCount == 0` guards against a held key re-firing
     * on every auto-repeat tick.
     *
     * **実機で発見・修正**: rapid repeated presses (tested via `adb shell input keyevent
     * KEYCODE_CAMERA` a few times in under 2s each) reproduced a hard app crash —
     * `ForegroundServiceDidNotStartInTimeException`. [CameraControlViewModel]'s recording
     * state machine already no-ops a toggle that arrives mid-transition (see
     * [CameraControlViewModel.startRecording]/[CameraControlViewModel.stopRecording]'s own
     * guards), but that only protects against *overlapping* start/stop calls — it doesn't
     * stop a *new* start from firing the instant the *previous* attempt's failure cleanup
     * (`stopRecordingService()`) has just barely finished, which real-hardware testing
     * showed is fast enough to race a fresh `startForegroundService()` call against the
     * OS's own bookkeeping for the *previous* one, confusing whether `startForeground()`
     * was called in time for the new one. A flat minimum interval between *accepted*
     * presses sidesteps the race entirely rather than chasing its exact timing — no real
     * user's shutter-button presses are anywhere near this close together.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_CAMERA &&
            event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastCameraKeyAcceptedAtMs >= CAMERA_KEY_DEBOUNCE_MS) {
                lastCameraKeyAcceptedAtMs = now
                viewModel.toggleRecording()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val CAMERA_KEY_DEBOUNCE_MS = 1500L
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Permission gate composable
// ──────────────────────────────────────────────────────────────────────────────

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    // POST_NOTIFICATIONS is required on Android 13+ for the foreground service notification;
    // it is non-critical for the recording pipeline itself so we request it alongside the
    // others but only gate [content] on CAMERA + RECORD_AUDIO.
)

// Computed lazily at call site inside PermissionGate composable to avoid top-level
// initialisation with Build.VERSION.SDK_INT (safe to evaluate anywhere, but keeping it
// inside the composable avoids any static initialisation ordering surprises).
private fun notificationPermissions(): Array<String> =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

/**
 * Checks CAMERA / RECORD_AUDIO / POST_NOTIFICATIONS and shows a rationale screen while
 * they are missing. Once all required permissions are granted, displays [content].
 *
 * Permission rationale follows §4.6: "完全実装" including rationale and settings
 * deep-link when the user permanently denies.
 */
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val allPermissions = REQUIRED_PERMISSIONS + notificationPermissions()

    var permissionsGranted by remember {
        mutableStateOf(
            allPermissions.all { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    var deniedPermanently by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val cameraOk = grants[Manifest.permission.CAMERA] == true
        val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true
        permissionsGranted = cameraOk && audioOk
        if (!permissionsGranted) {
            // If the user has permanently denied, we need to guide them to Settings.
            // We can't distinguish "denied this time" vs "permanent" without
            // shouldShowRequestPermissionRationale, which is an Activity method.
            // Set a flag to show the settings link on repeated refusal.
            deniedPermanently = !cameraOk || !audioOk
        }
    }

    if (permissionsGranted) {
        content()
    } else {
        val context = androidx.compose.ui.platform.LocalContext.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "ProCamera",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Amber,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "録画を開始するには\nカメラとマイクのアクセス権限が必要です。",
                    color = OnSurfaceSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))

                if (deniedPermanently) {
                    Text(
                        text = "権限が拒否されました。\n設定アプリから権限を有効化してください。",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            ).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber),
                    ) {
                        Text("設定を開く", color = SurfaceBlack)
                    }
                } else {
                    Button(
                        onClick = { launcher.launch(allPermissions) },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber),
                    ) {
                        Text("権限を許可する", color = SurfaceBlack)
                    }
                }
            }
        }
    }
}
