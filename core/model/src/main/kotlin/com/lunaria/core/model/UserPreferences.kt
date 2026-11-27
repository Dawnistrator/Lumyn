package com.lunaria.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val useAlbumArtTheme: Boolean = true,
    val audioOutputMode: AudioOutputMode = AudioOutputMode.DSP_FLOAT_32,
    val folderFilter: FolderFilterConfig = FolderFilterConfig(),
    val equalizer: EqualizerSettings = EqualizerSettings(),
)

/** Runtime state of the audio-library permission as the UI should present it. */
enum class AudioPermissionStatus {
    Granted,
    Denied,
    PermanentlyDenied,
}
