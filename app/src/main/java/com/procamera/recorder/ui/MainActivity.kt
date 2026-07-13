package com.procamera.recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.procamera.recorder.pipeline.RecordingPipeline
import kotlinx.coroutines.launch

/**
 * Host Activity. This is the record-pipeline smoke-test milestone's entry point (see
 * docs/ARCHITECTURE.md's Phase4 note): a single button drives [RecordingPipeline]
 * end-to-end on a physical device before the real UI (§4.5's camera/audio control panels)
 * is built. Real preview/manual-control UI arrives once this is proven to produce a
 * playable file with correct A/V sync.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecordingSmokeTestScreen()
                }
            }
        }
    }
}

private enum class RecordingUiState { IDLE, RECORDING }

@Composable
private fun RecordingSmokeTestScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val pipeline = remember { RecordingPipeline(context) }

    var uiState by remember { mutableStateOf(RecordingUiState.IDLE) }
    var statusText by remember { mutableStateOf("待機中") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            startRecording(pipeline, scope, onStateChange = { uiState = it }, onStatus = { statusText = it })
        } else {
            statusText = "CAMERA/RECORD_AUDIO権限が必要です"
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column {
            Text("ProCamera — 録画スモークテスト (Phase 4着手時点)")
            Text(statusText)
            Button(onClick = {
                when (uiState) {
                    RecordingUiState.IDLE -> {
                        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (hasCamera && hasMic) {
                            startRecording(pipeline, scope, onStateChange = { uiState = it }, onStatus = { statusText = it })
                        } else {
                            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                        }
                    }
                    RecordingUiState.RECORDING -> {
                        pipeline.stop()
                        uiState = RecordingUiState.IDLE
                        statusText = "停止しました"
                    }
                }
            }) {
                Text(if (uiState == RecordingUiState.IDLE) "録画開始" else "録画停止")
            }
        }
    }
}

private fun startRecording(
    pipeline: RecordingPipeline,
    scope: kotlinx.coroutines.CoroutineScope,
    onStateChange: (RecordingUiState) -> Unit,
    onStatus: (String) -> Unit,
) {
    onStatus("録画開始中…")
    scope.launch {
        @Suppress("MissingPermission") // Verified by caller immediately before invoking this function.
        pipeline.start { event ->
            when (event) {
                is RecordingPipeline.Event.Started -> {
                    onStateChange(RecordingUiState.RECORDING)
                    onStatus("録画中: ${event.outputDirectory}")
                }
                is RecordingPipeline.Event.Failed -> {
                    onStateChange(RecordingUiState.IDLE)
                    onStatus("エラー: ${event.message}")
                }
                RecordingPipeline.Event.Stopped -> {
                    onStateChange(RecordingUiState.IDLE)
                    onStatus("停止しました")
                }
            }
        }
    }
}
