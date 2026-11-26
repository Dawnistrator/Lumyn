package com.lumyn.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumyn.core.audioengine.UsbBitPerfectManager
import com.lumyn.core.database.preferences.LumynPreferencesDataSource
import com.lumyn.core.mediascanner.scanner.AudioLibraryScanner
import com.lumyn.core.mediascanner.scanner.ScanStatus
import com.lumyn.core.model.AudioCapabilitiesSnapshot
import com.lumyn.core.model.AudioOutputMode
import com.lumyn.core.model.FolderFilterMode
import com.lumyn.core.model.ThemeMode
import com.lumyn.core.model.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesDataSource: LumynPreferencesDataSource,
    private val scanner: AudioLibraryScanner,
    usbBitPerfectManager: UsbBitPerfectManager,
) : ViewModel() {

    init {
        usbBitPerfectManager.startMonitoring()
    }

    val preferences: StateFlow<UserPreferences> = preferencesDataSource.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserPreferences(),
    )

    val discoveredFolders: StateFlow<List<String>> = scanner.discoveredFolders

    val scanStatus: StateFlow<ScanStatus> = scanner.scanState

    val capabilities: StateFlow<AudioCapabilitiesSnapshot> =
        usbBitPerfectManager.routeSnapshot
            .map { it.capabilities }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AudioCapabilitiesSnapshot(
                    apiLevel = Build.VERSION.SDK_INT,
                    supportsFloatOutput = true,
                    supportsDirectPlayback = false,
                    supportsBitPerfectUsb = false,
                    activeOutputSummary = "Checking audio device…",
                ),
            )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferencesDataSource.setThemeMode(mode) }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferencesDataSource.setUseDynamicColor(enabled) }
    }

    fun setUseAlbumArtTheme(enabled: Boolean) {
        viewModelScope.launch { preferencesDataSource.setUseAlbumArtTheme(enabled) }
    }

    fun setAudioOutputMode(mode: AudioOutputMode) {
        viewModelScope.launch { preferencesDataSource.setAudioOutputMode(mode) }
    }

    fun setFolderFilterMode(mode: FolderFilterMode) {
        viewModelScope.launch {
            preferencesDataSource.setFolderFilterMode(mode)
            scanner.triggerScan()
        }
    }

    fun toggleFolderRule(folderPath: String, mode: FolderFilterMode) {
        viewModelScope.launch {
            when (mode) {
                FolderFilterMode.BLACKLIST -> preferencesDataSource.toggleExcludedFolder(folderPath)
                FolderFilterMode.WHITELIST -> preferencesDataSource.toggleIncludedFolder(folderPath)
            }
            scanner.triggerScan()
        }
    }

    fun resetFolderFilter() {
        viewModelScope.launch {
            preferencesDataSource.resetFolderFilter()
            scanner.triggerScan()
        }
    }

    fun triggerScan() {
        viewModelScope.launch { scanner.triggerScan() }
    }
}
