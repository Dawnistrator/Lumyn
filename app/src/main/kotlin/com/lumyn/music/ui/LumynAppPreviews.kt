package com.lumyn.music.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.lumyn.core.designsystem.theme.LumynTheme
import com.lumyn.core.model.AudioCapabilitiesSnapshot
import com.lumyn.core.model.AudioPermissionStatus
import com.lumyn.core.model.PlaybackState

@Preview(name = "Phone", widthDp = 411, heightDp = 891)
@Preview(name = "Phone dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Foldable / medium", widthDp = 700, heightDp = 900)
@Preview(name = "Tablet / expanded", widthDp = 1100, heightDp = 750)
annotation class FormFactorPreviews

@FormFactorPreviews
@Composable
private fun LumynAppPreview() {
    LumynTheme(dynamicColor = false) {
        LumynApp(
            audioPermission = AudioPermissionStatus.Denied,
            onPermissionAction = {},
            capabilities = AudioCapabilitiesSnapshot(
                apiLevel = 34,
                supportsFloatOutput = true,
                supportsDirectPlayback = true,
                supportsBitPerfectUsb = false,
                activeOutputSummary = "System output",
            ),
            playbackState = PlaybackState(),
        )
    }
}
