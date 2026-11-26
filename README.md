# Lumyn

**100% Offline Local Hi-Res Music Player for Android** — built with Kotlin, Jetpack Compose, Material Design 3, and Jetpack Media3. Lumyn declares **zero `INTERNET` or `ACCESS_NETWORK_STATE` permissions**: your audio files, listening history, playlists, and tags never leave your device.

## Highlights

- **3-Tier Hi-Res Audio Pipeline (`core:audio-engine`)**
  - **DSP 32-bit Float Mode:** Real-time RBJ biquad 10-band graphic EQ + parametric AutoEQ (`.txt`) import, ReplayGain (`REPLAYGAIN_*` & `R128_*`) with peak clipping protection, L/R channel balance, mono downmix, soft limiter, and smooth equal-power crossfade.
  - **Direct PCM (Android 13+):** System mixer bypass when supported by the audio route.
  - **Bit-Perfect USB DAC (Android 14+):** Native sample rate and bit depth handoff over USB-C DACs with automatic pause on disconnect.
  - **Honest Signal Path Sheet:** Live telemetry of Source → Decoder (`MediaCodec` / NextLib FFmpeg) → Processing → Output Device.
- **Offline Library & Audiophile Metadata (`core:media-scanner`, `core:database`)**
  - Room + MediaStore local indexing with configurable folder whitelist/blacklist filters.
  - **In-File Tag Editor:** Streaming pure-Kotlin writer for FLAC (Vorbis Comment + front cover `PICTURE`), MP3 (ID3v2.3/v2.4 + `APIC`), and MP4/M4A (`ilst` + `stco`/`co64` offset shifting) with Android 11+ `MediaStore.createWriteRequest` consent.
  - **Virtual CUE Sheet Splitting:** Uses Media3 `ClippingConfiguration` to play single-file album rips (`.flac` + `.cue`) as clipped tracks.
  - **Playlists & Local Lyrics:** M3U8 import/export, synced `.lrc` & embedded lyrics viewer, drag-to-reorder playback queue, and persisted queue resumption across app restarts.
  - **Smart Playlists & Listening Stats:** 100% offline **Recently Played**, **Most Played**, **Forgotten Gems**, **Hi-Res Collection**, **Favorites**, and local listening analytics.
- **Adaptive UI & Ecosystem (`app`, `feature:*`, `core:designsystem`)**
  - Material Design 3 (`1.4.0` stable via Compose BOM `2026.06.01`), `material3.adaptive` layouts (phone compact, foldable/tablet rail, expanded supporting pane), dynamic album-art theming, and bundled offline **JetBrains Mono** typography.
  - Jetpack Glance Home Screen widget and Android Auto (`MediaLibraryService`) browse tree + playback resumption.

## Module Architecture

| Module | Responsibility |
|---|---|
| `:app` | Adaptive shell (`LumynApp`), Navigation, Glance widget, Android Auto entry |
| `:core:model` | Pure Kotlin domain models, ReplayGain math, AutoEQ/LRC/M3U/CUE parsers, Smart Playlist engine |
| `:core:designsystem` | Bundled JetBrains Mono typography, Material 3 color system, shared components & icons |
| `:core:database` | Room database (`v3` with `MIGRATION_2_3`), DAOs, DataStore preferences & queue persistence |
| `:core:media-scanner` | MediaStore scanner/observer, binary header & ReplayGain parser, in-file tag writer, lyrics loader |
| `:core:audio-engine` | `LumynPlaybackService`, `LumynDspAudioProcessor`, RBJ biquad DSP, crossfade, USB bit-perfect routing |
| `:feature:home` | Home dashboard, Album Spotlight, Smart Playlists strip, offline Listening Stats |
| `:feature:library` | Songs, Albums, Artists, Folders, Playlists tabs + `LibraryDetailScreen` |
| `:feature:player` | Now Playing screen & supporting pane, Queue sheet, Signal Path, Lyrics, Tag Editor |
| `:feature:search` | Local instant search across tracks, albums, and artists |
| `:feature:equalizer` | 10-band EQ, presets, AutoEQ `.txt` import, ReplayGain, L/R balance, mono, crossfade |
| `:feature:settings` | Theme mode, dynamic color, folder filters, audio output mode, device capability diagnostics |

## Tech Stack & Requirements

- **Android SDK:** `minSdk = 26` (Android 8.0 Oreo), `compileSdk = 36`, `targetSdk = 36`
- **Build Tools:** AGP `9.0.1`, Gradle `9.1.0`, Kotlin `2.4.21`, JDK 17+
- **Core Libraries:** Jetpack Compose (`BOM 2026.06.01`, Material 3 `1.4.0`), Jetpack Media3 `1.11.1`, NextLib FFmpeg extension `1.11.1-0.19.0`, Room `2.8.5`, DataStore `1.3.0`, Hilt `2.60.1`, Glance `1.2.0`, Coil `3.4.0`

## Building & Testing

### Debug Build & Unit Tests
```bash
./gradlew assembleDebug testDebugUnitTest :core:model:test
```

### Release Signing Setup
Copy `keystore.properties.example` to `keystore.properties` (gitignored) and populate your keystore credentials:
```properties
storeFile=keystore/lumyn-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=lumyn
keyPassword=YOUR_KEY_PASSWORD
```
For CI environments without a checked-in `keystore.properties`, provide `LUMYN_KEYSTORE_PATH`, `LUMYN_KEYSTORE_PASSWORD`, `LUMYN_KEY_ALIAS`, and `LUMYN_KEY_PASSWORD` as environment variables.

### Release APKs (Per-ABI Splits + Universal)
```bash
./gradlew assembleRelease
```
Outputs signed APKs for `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, and `universal` under `app/build/outputs/apk/release/`.

### Play Store App Bundle (`.aab`)
```bash
./gradlew bundleRelease
```
Outputs the signed App Bundle under `app/build/outputs/bundle/release/app-release.aab`.
