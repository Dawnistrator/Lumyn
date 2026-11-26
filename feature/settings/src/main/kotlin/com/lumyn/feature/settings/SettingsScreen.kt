package com.lumyn.feature.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumyn.core.designsystem.component.LumynListDivider
import com.lumyn.core.designsystem.component.LumynListGroup
import com.lumyn.core.designsystem.component.LumynSectionHeader
import com.lumyn.core.designsystem.component.LumynStatusRow
import com.lumyn.core.designsystem.component.lumynGroupedListItemColors
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.mediascanner.scanner.ScanStatus
import com.lumyn.core.model.AudioCapabilitiesSnapshot
import com.lumyn.core.model.AudioOutputMode
import com.lumyn.core.model.AudioPermissionStatus
import com.lumyn.core.model.EqualizerSettings
import com.lumyn.core.model.FolderFilterConfig
import com.lumyn.core.model.FolderFilterMode
import com.lumyn.core.model.ThemeMode
import com.lumyn.core.model.UserPreferences

/** Hilt-aware entry point used by the app's navigation graph. */
@Composable
fun SettingsEntry(
    audioPermission: AudioPermissionStatus,
    onPermissionAction: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val discoveredFolders by viewModel.discoveredFolders.collectAsStateWithLifecycle()
    val scanStatus by viewModel.scanStatus.collectAsStateWithLifecycle()
    SettingsScreen(
        preferences = preferences,
        capabilities = capabilities,
        audioPermission = audioPermission,
        discoveredFolders = discoveredFolders,
        scanStatus = scanStatus,
        onThemeModeChange = viewModel::setThemeMode,
        onDynamicColorChange = viewModel::setUseDynamicColor,
        onAlbumArtThemeChange = viewModel::setUseAlbumArtTheme,
        onAudioOutputModeChange = viewModel::setAudioOutputMode,
        onOpenEqualizer = onOpenEqualizer,
        onFolderFilterModeChange = viewModel::setFolderFilterMode,
        onToggleFolderRule = viewModel::toggleFolderRule,
        onResetFolderFilter = viewModel::resetFolderFilter,
        onRescanClick = viewModel::triggerScan,
        onPermissionAction = onPermissionAction,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: UserPreferences,
    capabilities: AudioCapabilitiesSnapshot,
    audioPermission: AudioPermissionStatus,
    discoveredFolders: List<String> = emptyList(),
    scanStatus: ScanStatus = ScanStatus.Idle,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onAlbumArtThemeChange: (Boolean) -> Unit,
    onAudioOutputModeChange: (AudioOutputMode) -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onFolderFilterModeChange: (FolderFilterMode) -> Unit = {},
    onToggleFolderRule: (String, FolderFilterMode) -> Unit = { _, _ -> },
    onResetFolderFilter: () -> Unit = {},
    onRescanClick: () -> Unit = {},
    onPermissionAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFolderSheet by rememberSaveable { mutableStateOf(false) }

    if (showFolderSheet) {
        FolderFilterSheet(
            filter = preferences.folderFilter,
            discoveredFolders = discoveredFolders,
            onToggleFolder = { folder -> onToggleFolderRule(folder, preferences.folderFilter.mode) },
            onReset = onResetFolderFilter,
            onDismiss = { showFolderSheet = false },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppearanceSection(preferences, onThemeModeChange, onDynamicColorChange, onAlbumArtThemeChange)
            LibrarySection(
                filter = preferences.folderFilter,
                scanStatus = scanStatus,
                audioPermission = audioPermission,
                onModeChange = onFolderFilterModeChange,
                onManageFoldersClick = { showFolderSheet = true },
                onRescanClick = onRescanClick,
            )
            AudioSection(
                mode = preferences.audioOutputMode,
                equalizer = preferences.equalizer,
                capabilities = capabilities,
                onModeChange = onAudioOutputModeChange,
                onOpenEqualizer = onOpenEqualizer,
            )
            PrivacySection(audioPermission, onPermissionAction)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibrarySection(
    filter: FolderFilterConfig,
    scanStatus: ScanStatus,
    audioPermission: AudioPermissionStatus,
    onModeChange: (FolderFilterMode) -> Unit,
    onManageFoldersClick: () -> Unit,
    onRescanClick: () -> Unit,
) {
    LumynSectionHeader("Library & folder filter")
    LumynListGroup {
        ListItem(
            colors = lumynGroupedListItemColors(),
            supportingContent = {
                val options = listOf(
                    FolderFilterMode.BLACKLIST to "Exclude",
                    FolderFilterMode.WHITELIST to "Allow listed",
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    ) {
                        options.forEachIndexed { index, (option, label) ->
                            ToggleButton(
                                checked = filter.mode == option,
                                onCheckedChange = { if (it) onModeChange(option) },
                                shapes = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    Text(
                        text = if (filter.mode == FolderFilterMode.BLACKLIST) {
                            "${filter.excludedFolders.size} exclusion rules active (recordings, ringtones, etc.)."
                        } else {
                            "${filter.includedFolders.size} allowed folders included in your library."
                        },
                    )
                }
            },
            content = { Text("Folder filter mode") },
        )
        LumynListDivider()
        ListItem(
            onClick = onManageFoldersClick,
            colors = lumynGroupedListItemColors(),
            supportingContent = { Text("Choose folders to include or exclude") },
            trailingContent = {
                TextButton(onClick = onManageFoldersClick) { Text("Manage") }
            },
            content = { Text("Manage folders") },
        )
        LumynListDivider()
        ListItem(
            colors = lumynGroupedListItemColors(),
            supportingContent = {
                Text(
                    when (scanStatus) {
                        is ScanStatus.Idle -> "Ready to scan local music"
                        is ScanStatus.Scanning -> "Scanning (${scanStatus.indexedCount}/${scanStatus.totalCount})…"
                        is ScanStatus.Completed ->
                            "${scanStatus.indexedCount} tracks indexed (${scanStatus.filteredOutCount} filtered)"
                        ScanStatus.PermissionRequired -> "Audio file access required"
                        is ScanStatus.Error -> scanStatus.message
                    },
                )
            },
            trailingContent = {
                TextButton(
                    onClick = onRescanClick,
                    enabled = audioPermission == AudioPermissionStatus.Granted &&
                        scanStatus !is ScanStatus.Scanning,
                ) { Text("Scan") }
            },
            content = { Text("Rescan library") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderFilterSheet(
    filter: FolderFilterConfig,
    discoveredFolders: List<String>,
    onToggleFolder: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val activeRules = if (filter.mode == FolderFilterMode.BLACKLIST) {
        filter.excludedFolders
    } else {
        filter.includedFolders
    }
    val combinedFolders = remember(discoveredFolders, activeRules) {
        (discoveredFolders + activeRules)
            .map { FolderFilterConfig.normalizeFolderPath(it) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val listState = rememberLazyListState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (filter.mode == FolderFilterMode.BLACKLIST) {
                        "Excluded folders"
                    } else {
                        "Allowed folders"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onReset) { Text("Reset default") }
            }
            Text(
                text = if (filter.mode == FolderFilterMode.BLACKLIST) {
                    "Check folders to exclude from your music library."
                } else {
                    "Check folders to include in your music library."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(items = combinedFolders, key = { it.lowercase() }, contentType = { "folder-rule" }) { folder ->
                    val checked = activeRules.any { it.equals(folder, ignoreCase = true) }
                    ListItem(
                        modifier = Modifier.toggleable(
                            value = checked,
                            role = Role.Checkbox,
                            onValueChange = { onToggleFolder(folder) },
                        ),
                        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        content = { Text(folder) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppearanceSection(
    preferences: UserPreferences,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onAlbumArtThemeChange: (Boolean) -> Unit,
) {
    val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    LumynSectionHeader("Appearance & theme")
    LumynListGroup {
        ListItem(
            colors = lumynGroupedListItemColors(),
            supportingContent = {
                val options = listOf(
                    ThemeMode.SYSTEM to "System",
                    ThemeMode.LIGHT to "Light",
                    ThemeMode.DARK to "Dark",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    options.forEachIndexed { index, (mode, label) ->
                        ToggleButton(
                            checked = preferences.themeMode == mode,
                            onCheckedChange = { if (it) onThemeModeChange(mode) },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            },
            content = { Text("Theme mode") },
        )
        LumynListDivider()
        SwitchRow(
            title = "Wallpaper dynamic color",
            supporting = if (dynamicColorAvailable) {
                "Matches your wallpaper palette (Android 12+)"
            } else {
                "Unavailable on Android 8–11. Lumyn uses its built-in brand palette."
            },
            checked = preferences.useDynamicColor && dynamicColorAvailable,
            enabled = dynamicColorAvailable,
            onCheckedChange = onDynamicColorChange,
        )
        LumynListDivider()
        SwitchRow(
            title = "Album art theme",
            supporting = "Now Playing adapts colors from the current cover art",
            checked = preferences.useAlbumArtTheme,
            enabled = true,
            onCheckedChange = onAlbumArtThemeChange,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AudioSection(
    mode: AudioOutputMode,
    equalizer: EqualizerSettings,
    capabilities: AudioCapabilitiesSnapshot,
    onModeChange: (AudioOutputMode) -> Unit,
    onOpenEqualizer: () -> Unit,
) {
    LumynSectionHeader("Hi-Res audio engine")
    LumynListGroup {
        ListItem(
            onClick = onOpenEqualizer,
            colors = lumynGroupedListItemColors(),
            leadingContent = {
                Icon(
                    painter = painterResource(LumynIcons.Equalizer),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            },
            supportingContent = {
                Text(
                    when {
                        mode == AudioOutputMode.PURIST_DIRECT_BIT_PERFECT ->
                            "Bypassed in Purist mode"
                        equalizer.enabled ->
                            "Active: ${equalizer.activePresetName} • 10-band EQ & ReplayGain"
                        else ->
                            "10-band EQ, AutoEQ (.txt), ReplayGain & Balance"
                    },
                )
            },
            trailingContent = {
                TextButton(onClick = onOpenEqualizer) { Text("Open") }
            },
            content = { Text("Equalizer & DSP") },
        )
        LumynListDivider()
        ListItem(
            colors = lumynGroupedListItemColors(),
            supportingContent = {
                val options = listOf(
                    AudioOutputMode.DSP_FLOAT_32 to "DSP 32-bit float",
                    AudioOutputMode.PURIST_DIRECT_BIT_PERFECT to "Purist bit-perfect",
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    ) {
                        options.forEachIndexed { index, (option, label) ->
                            ToggleButton(
                                checked = mode == option,
                                onCheckedChange = { if (it) onModeChange(option) },
                                shapes = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    Text(
                        text = if (mode == AudioOutputMode.PURIST_DIRECT_BIT_PERFECT) {
                            "Purist bypasses the system mixer. EQ, ReplayGain, and crossfade are disabled."
                        } else {
                            "DSP preserves a 32-bit float pipeline for EQ and ReplayGain."
                        },
                    )
                }
            },
            content = { Text("Audio mode") },
        )
        LumynListDivider()
        LumynStatusRow(
            title = "FFmpeg / FLAC extension decoder",
            supporting = "FLAC on API 26, ALAC, APE, WavPack & WMA",
            statusText = if (capabilities.hasFfmpegExtension) "Active" else "Platform",
        )
        LumynListDivider()
        LumynStatusRow(
            title = "32-bit float output",
            supporting = "Used across all supported Android versions",
            statusText = if (capabilities.supportsFloatOutput) "Active" else "Unavailable",
        )
        LumynListDivider()
        LumynStatusRow(
            title = "Direct playback",
            supporting = "Android 13+",
            statusText = if (capabilities.supportsDirectPlayback) "Supported" else "Unsupported",
        )
        LumynListDivider()
        LumynStatusRow(
            title = "Bit-perfect USB DAC",
            supporting = "Android 14+ with a connected USB DAC",
            statusText = if (capabilities.supportsBitPerfectUsb) "Supported" else "Unsupported",
        )
        LumynListDivider()
        LumynStatusRow(
            title = "Current output",
            supporting = capabilities.activeOutputSummary,
            statusText = "API ${capabilities.apiLevel}",
        )
    }
}

@Composable
private fun PrivacySection(audioPermission: AudioPermissionStatus, onPermissionAction: () -> Unit) {
    LumynSectionHeader("Privacy & local permissions")
    LumynListGroup {
        ListItem(
            colors = lumynGroupedListItemColors(),
            supportingContent = {
                Text(
                    when (audioPermission) {
                        AudioPermissionStatus.Granted -> "Granted"
                        AudioPermissionStatus.Denied -> "Not granted"
                        AudioPermissionStatus.PermanentlyDenied -> "Denied. Update in app settings."
                    },
                )
            },
            trailingContent = {
                if (audioPermission != AudioPermissionStatus.Granted) {
                    TextButton(onClick = onPermissionAction) {
                        Text(
                            if (audioPermission == AudioPermissionStatus.PermanentlyDenied) {
                                "Open settings"
                            } else {
                                "Allow"
                            },
                        )
                    }
                }
            },
            content = { Text("Audio file access") },
        )
        LumynListDivider()
        ListItem(
            colors = lumynGroupedListItemColors(),
            supportingContent = {
                Text("Lumyn never requests internet permission, with zero tracking and zero analytics.")
            },
            content = { Text("100% offline") },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    supporting: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        colors = lumynGroupedListItemColors(),
        supportingContent = { Text(supporting) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        content = { Text(title) },
    )
}
