package com.procamera.recorder.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procamera.recorder.camera.CameraCapabilityInspector
import com.procamera.recorder.ui.theme.Amber
import com.procamera.recorder.ui.theme.OnSurfacePrimary
import com.procamera.recorder.ui.theme.OnSurfaceSecondary
import com.procamera.recorder.ui.theme.SurfaceDark
import com.procamera.recorder.ui.viewmodel.CameraControlViewModel
import com.procamera.recorder.ui.viewmodel.CameraUiState
import com.procamera.recorder.ui.viewmodel.FrameLineAspectRatio
import com.procamera.recorder.ui.viewmodel.StorageLocation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    state: CameraUiState,
    viewModel: CameraControlViewModel,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "設定",
                color = OnSurfacePrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Video Config Selection
            Text(
                text = "解像度・フレームレート",
                color = OnSurfaceSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            state.availableVideoConfigs.forEach { config ->
                val isSelected = state.selectedVideoConfig == config
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectVideoConfig(config) }
                        .padding(vertical = 8.dp)
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = Amber)
                    )
                    val codec = if (config.mimeType.contains("hevc", ignoreCase = true)) "HEVC" else "H.264"
                    val res = if (config.width >= 3840) "4K" else if (config.width >= 1920) "1080p" else "720p"
                    val mbps = config.bitrate / 1_000_000
                    Text(
                        text = "$res ${config.frameRate}fps $codec ${mbps}Mbps",
                        color = if (isSelected) OnSurfacePrimary else OnSurfaceSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Storage Location
            Text(
                text = "保存場所",
                color = OnSurfaceSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            val locations = listOf(
                StorageLocation.AppPrivate to "アプリ専用領域 (ギャラリー非表示)",
                StorageLocation.PublicMovies to "Movies フォルダ (ギャラリー表示)"
            )
            
            locations.forEach { (loc, label) ->
                val isSelected = state.settings.storageLocation == loc
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setStorageLocation(loc) }
                        .padding(vertical = 8.dp)
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = Amber)
                    )
                    Text(
                        text = label,
                        color = if (isSelected) OnSurfacePrimary else OnSurfaceSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Frame-line composition guide — preview-only, does not crop the recorded
            // file (see FrameLineAspectRatio's doc for why).
            Text(
                text = "構図ガイド (プレビューのみ・録画映像はクロップされません)",
                color = OnSurfaceSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            FrameLineAspectRatio.entries.forEach { option ->
                val isSelected = state.settings.frameLineAspectRatio == option
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setFrameLineAspectRatio(option) }
                        .padding(vertical = 8.dp)
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = Amber)
                    )
                    Text(
                        text = option.label,
                        color = if (isSelected) OnSurfacePrimary else OnSurfaceSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
