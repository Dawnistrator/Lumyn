package com.lumyn.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumyn.core.designsystem.component.LumynListDivider
import com.lumyn.core.designsystem.component.LumynListGroup
import com.lumyn.core.designsystem.component.lumynGroupedListItemColors
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.model.AudioOutputCategory
import com.lumyn.core.model.AudioOutputMode
import com.lumyn.core.model.AudioSignalPath

/**
 * Transparent Audio Signal Path sheet: shows Source -> Decoder -> Processing -> Output honestly,
 * and lets the user toggle DSP 32-bit Float vs Purist Bit-Perfect in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalPathSheet(
    signalPath: AudioSignalPath?,
    onSelectOutputMode: (AudioOutputMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentMode = signalPath?.outputMode ?: AudioOutputMode.DSP_FLOAT_32

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Audio Signal Path", style = MaterialTheme.typography.titleLarge)
                if (signalPath != null) {
                    val statusLabel = when {
                        signalPath.isBitPerfectActive -> "Bit-Perfect"
                        signalPath.isDirectPlaybackActive -> "Direct PCM"
                        signalPath.outputCategory == AudioOutputCategory.BLUETOOTH_A2DP -> "System Bluetooth"
                        else -> "32-bit Float DSP"
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (signalPath.isBitPerfectActive || signalPath.isDirectPlaybackActive) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (signalPath.isBitPerfectActive || signalPath.isDirectPlaybackActive) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            if (signalPath != null) {
                LumynListGroup {
                    SignalStepRow(
                        iconRes = LumynIcons.MusicNote,
                        label = "Source file",
                        value = signalPath.sourceFormatLabel,
                    )
                    LumynListDivider()
                    SignalStepRow(
                        iconRes = LumynIcons.Tune,
                        label = "Decoder",
                        value = signalPath.decoderName,
                    )
                    LumynListDivider()
                    SignalStepRow(
                        iconRes = LumynIcons.GraphicEq,
                        label = "Signal processing",
                        value = signalPath.processingChain.joinToString(" → "),
                    )
                    LumynListDivider()
                    SignalStepRow(
                        iconRes = LumynIcons.Usb,
                        label = "Output device",
                        value = "${signalPath.outputDeviceName}\n${formatRateKhz(signalPath.outputSampleRateHz)} • " +
                            "${signalPath.outputBitDepth}-bit (${signalPath.outputEncodingLabel})",
                    )
                }
            } else {
                Text(
                    text = "Play a track to inspect the live audio processing chain.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Audio processing mode", style = MaterialTheme.typography.titleSmall)
                val options = listOf(
                    AudioOutputMode.DSP_FLOAT_32 to "DSP 32-bit float",
                    AudioOutputMode.PURIST_DIRECT_BIT_PERFECT to "Purist bit-perfect",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (option, label) ->
                        SegmentedButton(
                            selected = currentMode == option,
                            onClick = { onSelectOutputMode(option) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Text(
                    text = if (currentMode == AudioOutputMode.PURIST_DIRECT_BIT_PERFECT) {
                        "Purist mode sends native PCM directly to Direct / USB DAC (Android 14+), bypassing mixer and EQ."
                    } else {
                        "DSP mode processes audio in 32-bit float headroom to keep EQ and ReplayGain clean."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SignalStepRow(
    iconRes: Int,
    label: String,
    value: String,
) {
    ListItem(
        colors = lumynGroupedListItemColors(),
        leadingContent = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        headlineContent = { Text(label, style = MaterialTheme.typography.labelMedium) },
        supportingContent = { Text(value, style = MaterialTheme.typography.bodyMedium) },
    )
}

private fun formatRateKhz(rateHz: Int): String =
    if (rateHz % 1_000 == 0) "${rateHz / 1_000} kHz" else "%.1f kHz".format(rateHz / 1_000.0)
