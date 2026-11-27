# Lunaria for Windows (`desktop/`)

Native Windows 10 / Windows 11 (`x86_64`) desktop build of **Lunaria**, written in **100% Pure Rust (`edition = "2024"`, MSRV `1.99`)** with **Slint `1.19.1`** (Material Design 3, resizable `1180×724` default / `1080×700` minimum studio window, offline bundled **JetBrains Mono**) and direct **WASAPI Shared + Exclusive** audio output.

- **Zero Network by Architecture:** Enforced via `cargo tree` audit (`NET_MATCHES=0` — no `reqwest`, `hyper`, `tokio`, TLS, or telemetry crates). Discord Rich Presence uses purely local Windows Named Pipes (`\\.\pipe\discord-ipc-0..9`) with a one-click On/Off toggle in Settings.
- **Compact & Offline Release:** Self-contained single stripped release executable (`lunaria-desktop.exe`) and compressed portable ZIP (`portable.flag`).

---

## Workspace Layout

| Crate / App | Responsibility |
| :--- | :--- |
| [`crates/lunaria-model`](crates/lunaria-model) | Pure domain models, AutoEQ, CUE sheets, LRC lyrics, M3U8, ReplayGain/R128, Smart Playlists, Folder Filters (`zero I/O`) |
| [`crates/lunaria-dsp`](crates/lunaria-dsp) | Lock-free `f64` internal / `f32` I/O RBJ biquad 10-band EQ, parametric AutoEQ, preamp, balance, mono, soft limiter, cosine crossfade |
| [`crates/lunaria-audio`](crates/lunaria-audio) | `symphonia` pure-Rust decoders, `rtrb` gapless ring buffer, `rubato` resampler, WSOLA speed `0.5x–2.0x`, WASAPI Shared/Exclusive + hot-plug auto-pause, honest `AudioSignalPathBuilder` |
| [`crates/lunaria-scanner`](crates/lunaria-scanner) | Parallel `walkdir` + `rayon` indexer, `notify` live file watcher, `blake3` artwork cache, `mcu-material-color` HCT seed extraction |
| [`crates/lunaria-tags`](crates/lunaria-tags) | `lofty` metadata & cover writer (`FLAC`, `MP3`, `MP4/M4A`, `OGG`, `WAV`, `AIFF`) with Windows File-Lock Handover Protocol |
| [`crates/lunaria-db`](crates/lunaria-db) | Bundled SQLite WAL + FTS5 instant search, playlists, queue persistence (`MAX = 2,000`), Portable Mode (`portable.flag`) |
| [`crates/lunaria-win`](crates/lunaria-win) | Pure Win32/WinRT integration: SMTC media flyout/keys (isolated STA), System Tray (`Shell_NotifyIconW`), `ITaskbarList3` thumbnails/progress + `TaskbarButtonCreated` recovery, `WM_DROPFILES` drag-and-drop, Single-Instance Mutex + Named Pipe IPC, local Named Pipe Discord Rich Presence |
| [`apps/lunaria-desktop`](apps/lunaria-desktop) | Slint M3 UI shell, controller, wavy seekbar, live EQ Bode curve, Close-to-Tray (`0 Hz` redraw when hidden) |

---

## Build, Verification & Packaging Commands

Run from `desktop/`:

```powershell
# 1. Zero-warning strict static analysis
$env:CARGO_BUILD_WARNINGS = "deny"
cargo clippy --workspace --all-targets

# 2. Full workspace unit, parity & property tests (53 tests)
cargo test --workspace

# 3. Real-time DSP benchmark gate (96 kHz stereo 15 biquads)
cargo bench -p lunaria-dsp -- --test

# 4. Zero-network dependency verification (must return 0 matches)
cargo tree --workspace --edges normal | Select-String -Pattern "reqwest|hyper|tokio|curl|ureq|surf|isahc|rustls|native-tls|openssl|h2|quinn|tungstenite"

# 5. Production release binary (LTO fat, single codegen unit, panic=abort, stripped)
cargo build --release -p lunaria-desktop

# 6. Probe detected Windows WASAPI endpoints & HCT palette colors (debug console build)
cargo run -p lunaria-desktop -- --probe-wasapi
```

### Runtime Flags & Storage Modes

- **Portable Mode:** Place an empty `portable.flag` file next to `lunaria.exe` (`Lunaria-windows-x64-portable.zip` includes this automatically). All SQLite databases, artwork cache, and logs stay inside `./data/` beside the executable without touching `%APPDATA%` or `%LOCALAPPDATA%`.
- **Zero-VRAM Software Renderer:** Enable `SW 0-VRAM` in **Settings**, or launch with `$env:SLINT_BACKEND="winit-software"`.
- **Single-Instance & Explorer Integration:** Launching a second `lunaria.exe` forwards any file/folder arguments to the running instance via Named Pipe (`\\.\pipe\LunariaDesktopV1_Pipe`) and focuses the window. Pass `--allow-multi-instance` to bypass single-instance locking for diagnostics.
- **Discord Rich Presence (On by Default):** Works out of the box with Lunaria's built-in Application ID via local Windows Named Pipes (`\\.\pipe\discord-ipc-0..9`), and can be toggled **On / Off** anytime in **Settings** (`LUNARIA_DISCORD_CLIENT_ID` env var override also supported).
- **CLI Audio Probe:** `lunaria-desktop.exe --probe-wasapi` (from `cargo run` / debug build) prints all active WASAPI endpoints and theme seed values without launching the GUI.

---

## Keyboard Shortcuts

| Shortcut | Action |
| :--- | :--- |
| `Space` | Play / Pause (outside text input fields) |
| `←` / `→` | Seek `-5s` / `+5s` (outside text input fields) |
| `↑` / `↓` | Volume `+5%` / `-5%` (outside text input fields) |
| `Ctrl+O` | Open local audio file(s) |
| `Ctrl+K` | Focus Instant Search |
| `Ctrl+E` | Open Equalizer & DSP |
| `Ctrl+Q` | Open Queue pane |
| `Ctrl+L` | Open Synced Lyrics pane |
| `Ctrl+I` | Open Audio Signal Path pane |
| `Ctrl+Left` / `Ctrl+Right` | Previous / Next track |
| `F` / `M` / `Esc` | Toggle Favorite / Mute / Close Tag Editor |

---

## Installer & Automated CI Releases

- **NSIS Per-User Installer:** [`packaging/windows/lunaria-installer.nsi`](packaging/windows/lunaria-installer.nsi) installs into `%LOCALAPPDATA%\Programs\Lunaria` without UAC elevation, creates Start Menu/Desktop shortcuts, registers `HKCU` `OpenWithProgids` audio/playlist associations (`.flac`, `.wav`, `.alac`, `.mp3`, `.m4a`, `.aac`, `.ogg`, `.aiff`, `.aif`, `.cue`, `.m3u`, `.m3u8`), and includes an uninstaller.
- **GitHub Actions CI:** [`../.github/workflows/release-windows.yml`](../.github/workflows/release-windows.yml) builds `x86_64-pc-windows-msvc` on `win-*` tags (e.g. `win-v1.0.0`) or manual `workflow_dispatch`, verifies zero-network/Clippy/tests/bench, and publishes the NSIS Setup EXE, Portable ZIP, and `SHA256SUMS.txt`.
- **License:** Distributed under the **GNU General Public License v3.0 (`GPL-3.0-only`)** — see [`../LICENSE`](../LICENSE), aligned with Slint 1.19's `GPL-3.0-only` license.
