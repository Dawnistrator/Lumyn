package com.lumyn.feature.equalizer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumyn.core.designsystem.component.LumynListDivider
import com.lumyn.core.designsystem.component.LumynListGroup
import com.lumyn.core.designsystem.component.LumynSectionHeader
import com.lumyn.core.designsystem.component.lumynGroupedListItemColors
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.model.AudioOutputMode
import com.lumyn.core.model.BuiltInEqPresets
import com.lumyn.core.model.EqualizerPreset
import com.lumyn.core.model.EqualizerSettings
import com.lumyn.core.model.ReplayGainMode
import com.lumyn.core.model.StandardEqFrequenciesHz
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EqualizerEntry(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EqualizerViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onNavigateBack)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val autoEqLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) { readUriTextWithName(context, uri) }
                if (loaded != null) {
                    viewModel.importAutoEq(loaded.first, loaded.second)
                }
            }
        }
    }

    EqualizerScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSwitchToDspMode = viewModel::switchToDspMode,
        onEnabledChange = viewModel::setEnabled,
        onSelectPreset = viewModel::selectPreset,
        onPreampChange = viewModel::setPreampDb,
        onBandGainChange = viewModel::setBandGain,
        onImportAutoEqClick = {
            autoEqLauncher.launch(arrayOf("text/plain", "text/*"))
        },
        onClearParametricFilters = viewModel::clearParametricFilters,
        onReplayGainModeChange = viewModel::setReplayGainMode,
        onReplayGainPreampChange = viewModel::setReplayGainPreampDb,
        onChannelBalanceChange = viewModel::setChannelBalance,
        onMonoAudioChange = viewModel::setMonoAudio,
        onCrossfadeChange = viewModel::setCrossfadeDurationSec,
        onResetEq = viewModel::resetToFlat,
        onDismissStatus = viewModel::clearStatusMessage,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqualizerScreen(
    uiState: EqualizerUiState,
    onNavigateBack: (() -> Unit)? = null,
    onSwitchToDspMode: () -> Unit = {},
    onEnabledChange: (Boolean) -> Unit = {},
    onSelectPreset: (EqualizerPreset) -> Unit = {},
    onPreampChange: (Float) -> Unit = {},
    onBandGainChange: (Int, Float) -> Unit = { _, _ -> },
    onImportAutoEqClick: () -> Unit = {},
    onClearParametricFilters: () -> Unit = {},
    onReplayGainModeChange: (ReplayGainMode) -> Unit = {},
    onReplayGainPreampChange: (Float) -> Unit = {},
    onChannelBalanceChange: (Float) -> Unit = {},
    onMonoAudioChange: (Boolean) -> Unit = {},
    onCrossfadeChange: (Int) -> Unit = {},
    onResetEq: () -> Unit = {},
    onDismissStatus: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Equalizer?") },
            text = { Text("Reset all 10 frequency bands, preamp gain, and AutoEQ filters to Flat.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetEq()
                        showResetDialog = false
                    },
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
        )
    }

    val isPurist = uiState.outputMode == AudioOutputMode.PURIST_DIRECT_BIT_PERFECT
    val settings = uiState.settings

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Equalizer & DSP") },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                painter = painterResource(LumynIcons.ArrowBack),
                                contentDescription = "Back",
                            )
                        }
                    }
                },
                actions = {
                    if (!isPurist) {
                        TextButton(onClick = { showResetDialog = true }) {
                            Text("Reset")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isPurist) {
                PuristDisabledBanner(onSwitchToDspMode = onSwitchToDspMode)
            } else {
                uiState.statusMessage?.let { msg ->
                    StatusBanner(message = msg, onDismiss = onDismissStatus)
                }

                LumynListGroup {
                    ListItem(
                        modifier = Modifier.toggleable(
                            value = settings.enabled,
                            role = Role.Switch,
                            onValueChange = onEnabledChange,
                        ),
                        colors = lumynGroupedListItemColors(),
                        headlineContent = { Text("32-bit Float Equalizer") },
                        supportingContent = {
                            Text(
                                if (settings.enabled) {
                                    "Active profile: ${settings.activePresetName}"
                                } else {
                                    "Off (zero-overhead passthrough)"
                                },
                            )
                        },
                        trailingContent = {
                            Switch(checked = settings.enabled, onCheckedChange = null)
                        },
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = onImportAutoEqClick) {
                        Icon(
                            painter = painterResource(LumynIcons.FileOpen),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Import AutoEQ (.txt)", modifier = Modifier.padding(start = 8.dp))
                    }
                    if (settings.parametricFilters.isNotEmpty()) {
                        val activeFilters = settings.parametricFilters.count { it.enabled }
                        InputChip(
                            selected = true,
                            onClick = onClearParametricFilters,
                            label = { Text("${settings.activePresetName} ($activeFilters)") },
                            trailingIcon = {
                                Icon(
                                    painter = painterResource(LumynIcons.Close),
                                    contentDescription = "Clear AutoEQ filters",
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }

                LumynSectionHeader("Presets")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BuiltInEqPresets.forEach { preset ->
                        FilterChip(
                            selected = settings.activePresetName == preset.name && settings.parametricFilters.isEmpty(),
                            onClick = { onSelectPreset(preset) },
                            label = { Text(preset.name) },
                        )
                    }
                }

                LumynSectionHeader("10-Band Equalizer")
                LumynListGroup {
                    DbSliderRow(
                        label = "Preamp",
                        valueDb = settings.preampDb,
                        valueRange = EqualizerSettings.MIN_GAIN_DB..EqualizerSettings.MAX_GAIN_DB,
                        enabled = settings.enabled,
                        onValueCommit = onPreampChange,
                    )
                    LumynListDivider()
                    val gains = settings.normalizedBandGains()
                    StandardEqFrequenciesHz.forEachIndexed { index, freqHz ->
                        DbSliderRow(
                            label = formatFrequencyLabel(freqHz),
                            valueDb = gains[index],
                            valueRange = EqualizerSettings.MIN_GAIN_DB..EqualizerSettings.MAX_GAIN_DB,
                            enabled = settings.enabled,
                            onValueCommit = { nextDb -> onBandGainChange(index, nextDb) },
                        )
                        if (index < StandardEqFrequenciesHz.lastIndex) {
                            LumynListDivider()
                        }
                    }
                }

                LumynSectionHeader("ReplayGain")
                LumynListGroup {
                    ListItem(
                        colors = lumynGroupedListItemColors(),
                        headlineContent = { Text("Normalization mode") },
                        supportingContent = {
                            val options = listOf(
                                ReplayGainMode.OFF to "Off",
                                ReplayGainMode.TRACK to "Track",
                                ReplayGainMode.ALBUM to "Album",
                            )
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                options.forEachIndexed { index, (option, label) ->
                                    SegmentedButton(
                                        selected = settings.replayGainMode == option,
                                        onClick = { onReplayGainModeChange(option) },
                                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        },
                    )
                    LumynListDivider()
                    DbSliderRow(
                        label = "RG Preamp",
                        valueDb = settings.replayGainPreampDb,
                        valueRange = -6f..6f,
                        enabled = settings.replayGainMode != ReplayGainMode.OFF,
                        onValueCommit = onReplayGainPreampChange,
                    )
                }

                LumynSectionHeader("Output & Mix")
                LumynListGroup {
                    BalanceSliderRow(
                        balance = settings.channelBalance,
                        onBalanceCommit = onChannelBalanceChange,
                    )
                    LumynListDivider()
                    ListItem(
                        modifier = Modifier.toggleable(
                            value = settings.monoAudio,
                            role = Role.Switch,
                            onValueChange = onMonoAudioChange,
                        ),
                        colors = lumynGroupedListItemColors(),
                        headlineContent = { Text("Mono audio") },
                        supportingContent = { Text("Combine left and right channels") },
                        trailingContent = { Switch(checked = settings.monoAudio, onCheckedChange = null) },
                    )
                    LumynListDivider()
                    CrossfadeSliderRow(
                        durationSec = settings.crossfadeDurationSec,
                        onDurationCommit = onCrossfadeChange,
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PuristDisabledBanner(onSwitchToDspMode: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Equalizer is disabled in Purist Mode", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Purist Mode routes audio directly to Direct Playback or a Bit-Perfect USB DAC, " +
                    "bypassing EQ, ReplayGain, and the system mixer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onSwitchToDspMode) { Text("Switch to DSP Mode") }
        }
    }
}

@Composable
private fun StatusBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun DbSliderRow(
    label: String,
    valueDb: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueCommit: (Float) -> Unit,
) {
    var localValue by remember(valueDb) { mutableFloatStateOf(valueDb) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(68.dp),
        )
        Slider(
            value = localValue,
            onValueChange = { raw -> localValue = (raw * 2f).roundToInt() / 2f },
            onValueChangeFinished = { onValueCommit(localValue) },
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDbLabel(localValue),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
    }
}

@Composable
private fun BalanceSliderRow(
    balance: Float,
    onBalanceCommit: (Float) -> Unit,
) {
    var localBalance by remember(balance) { mutableFloatStateOf(balance) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("L/R Balance", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = formatBalanceLabel(localBalance),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = localBalance,
            onValueChange = { raw -> localBalance = ((raw * 20f).roundToInt() / 20f).coerceIn(-1f, 1f) },
            onValueChangeFinished = { onBalanceCommit(localBalance) },
            valueRange = -1f..1f,
        )
    }
}

@Composable
private fun CrossfadeSliderRow(
    durationSec: Int,
    onDurationCommit: (Int) -> Unit,
) {
    var localSec by remember(durationSec) { mutableFloatStateOf(durationSec.toFloat()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Crossfade / Gapless", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (localSec.roundToInt() == 0) "Off (Gapless)" else "${localSec.roundToInt()} s",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = "Smooth equal-power fade between tracks in DSP mode",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = localSec,
            onValueChange = { raw -> localSec = raw.roundToInt().toFloat() },
            onValueChangeFinished = { onDurationCommit(localSec.roundToInt()) },
            valueRange = 0f..EqualizerSettings.MAX_CROSSFADE_SEC.toFloat(),
            steps = EqualizerSettings.MAX_CROSSFADE_SEC - 1,
        )
    }
}

private fun formatFrequencyLabel(freqHz: Int): String =
    if (freqHz >= 1_000) "${freqHz / 1_000} kHz" else "$freqHz Hz"

private fun formatDbLabel(db: Float): String {
    val clean = if (abs(db) < 0.05f) 0f else db
    return if (clean > 0f) {
        String.format(Locale.US, "+%.1f dB", clean)
    } else {
        String.format(Locale.US, "%.1f dB", clean)
    }
}

private fun formatBalanceLabel(balance: Float): String {
    val pct = (abs(balance) * 100f).roundToInt()
    return when {
        pct < 2 -> "Center"
        balance < 0f -> "Left $pct%"
        else -> "Right $pct%"
    }
}

private fun readUriTextWithName(context: Context, uri: Uri): Pair<String, String>? {
    val name = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "AutoEQ"
    val content = context.contentResolver.openInputStream(uri)?.use { stream ->
        stream.bufferedReader(Charsets.UTF_8).readText()
    } ?: return null
    return content to name
}
