//! Application controller connecting SQLite, Rayon Scanner, Lofty Tag Writer, 64-bit DSP,
//! WASAPI PlaybackEngine, and reactive Slint Material Design 3 UI views.

use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crossbeam_channel::{Receiver, Sender};
use lunaria_audio::{
    PlaybackEngine, PlaybackRequest, PlaybackStatus, enumerate_render_devices, play_test_chime,
};
use lunaria_db::{AppStoragePaths, LibraryDb};
use lunaria_dsp::DspConfigStore;
use lunaria_model::{
    AlbumId, AlbumSummary, ArtistSummary, AudioDeviceInfo, AudioOutputCategory, AudioOutputMode,
    BUILT_IN_EQ_PRESETS, FolderFilterMode, FolderSummary, ParsedLyrics, PlaylistId,
    PlaylistSummary, RepeatMode, ReplayGainMode, STANDARD_EQ_FREQUENCIES_HZ, SearchCriteria,
    SearchFilterChip, SleepTimerState, SmartPlaylistType, ThemeMode, Track, TrackId,
    TrackSortOrder, UserPreferences, active_line_index_at, build_virtual_tracks,
    compute_listening_stats, export_m3u, match_m3u_tracks, parse_autoeq, parse_cue_sheet,
    parse_m3u, summarize_smart_playlists, tracks_for_smart_playlist,
};
use lunaria_scanner::{
    DEFAULT_WATCH_DEBOUNCE, LibraryWatcher, ScanConfig, apply_watch_paths, load_local_lyrics,
    normalize_path_key, scan_library, scan_single_audio_file,
};
use lunaria_tags::{CoverArtEdit, TagEditRequest, save_track_tags};
use lunaria_win::{
    DiscordActivitySnapshot, DiscordRpcHost, DiscordRpcStatus, MainWindowHook,
    MediaMetadataSnapshot, ShellCommand, WindowsShellHost, configure_frameless_window_chrome,
    focus_window, is_supported_drop_or_open_path, is_window_maximized, minimize_window,
    start_window_drag, sync_window_border_color, toggle_maximize_window,
};
use raw_window_handle::{HasWindowHandle, RawWindowHandle};
use slint::{
    CloseRequestResponse, ComponentHandle, Model, ModelRc, SharedString, Timer, TimerMode, VecModel,
};

use crate::canvas::{load_artwork_variants, render_bode_curve_image};
use crate::queue::PlaybackQueue;
use crate::theme::{
    LUNARIA_BRAND_SEED_ARGB, LunariaMaterialPalette, PRESET_SEED_SWATCHES, argb_to_slint_color,
    build_palette_from_seed, format_rgb_hex,
};
use crate::ui::{
    DeviceRow, EqBandRow, GroupRow, LunariaTheme, LyricRow, MainWindow, MarqueeClock, QueueRow,
    SmartPlaylistRow, StatArtistRow, SwatchRow, TrackRow, WatchedFolderRow,
};

const SPEED_CYCLE: [f32; 5] = [1.0, 1.15, 1.25, 1.5, 0.75];
const SLEEP_PRESETS_MIN: [Option<u64>; 5] = [None, Some(0), Some(15), Some(30), Some(60)];
const BODE_WIDTH_PX: u32 = 960;
const BODE_HEIGHT_PX: u32 = 118;
const HERO_ARTWORK_LOGICAL_PX: f32 = 272.0;
const MINI_ARTWORK_LOGICAL_PX: f32 = 48.0;

#[derive(Debug, Clone, PartialEq, Eq)]
enum LibraryDrilldown {
    Album(AlbumId, String),
    Artist(String),
    Folder(String),
    Playlist(PlaylistId, String),
    SmartPlaylist(SmartPlaylistType),
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum GroupKey {
    Album(AlbumId, String),
    Artist(String),
    Folder(String),
    Playlist(PlaylistId, String),
}

type UiTask =
    Box<dyn FnOnce(&MainWindow, &Rc<RefCell<AppState>>, &Arc<PlaybackEngine>, &Rc<Timer>) + Send>;
type UiTaskQueue = Arc<Mutex<VecDeque<UiTask>>>;

fn enqueue_ui_task<F>(queue: &UiTaskQueue, weak: &slint::Weak<MainWindow>, task: F)
where
    F: FnOnce(&MainWindow, &Rc<RefCell<AppState>>, &Arc<PlaybackEngine>, &Rc<Timer>)
        + Send
        + 'static,
{
    if let Ok(mut q) = queue.lock() {
        q.push_back(Box::new(task));
    }
    let weak_ui = weak.clone();
    let _ = slint::invoke_from_event_loop(move || {
        if let Some(ui) = weak_ui.upgrade() {
            ui.invoke_dispatch_shell_commands();
        }
    });
}

/// Application state owned on the Slint UI event loop thread.
struct AppState {
    db: LibraryDb,
    storage_paths: AppStoragePaths,
    prefs: UserPreferences,
    dark_theme: bool,
    manual_seed_argb: u32,
    selected_swatch_idx: usize,
    active_palette: LunariaMaterialPalette,

    devices: Vec<AudioDeviceInfo>,
    selected_device_idx: usize,
    watched_folders: Vec<String>,

    all_tracks: Vec<Track>,
    albums: Vec<AlbumSummary>,
    artists: Vec<ArtistSummary>,
    folders: Vec<FolderSummary>,
    playlists: Vec<PlaylistSummary>,
    active_playlist_target: Option<PlaylistId>,

    sort_order: TrackSortOrder,
    library_subtab: usize,
    drilldown: Option<LibraryDrilldown>,
    visible_groups: Vec<GroupKey>,
    visible_library_tracks: Vec<Track>,
    home_spotlight_tracks: Vec<Track>,

    search_criteria: SearchCriteria,
    visible_search_tracks: Vec<Track>,
    stats_top_tracks: Vec<Track>,

    queue: PlaybackQueue,
    current_lyrics: Option<ParsedLyrics>,
    active_lyric_line: Option<usize>,
    last_track_id: Option<TrackId>,
    last_artwork_key: Option<(String, u64, u32)>,
    artwork_revision: u64,
    last_bode_signature: String,
    eq_bands_model: Rc<VecModel<EqBandRow>>,
    marquee_elapsed_sec: f32,
    last_marquee_track_key: String,
    restored_position_ms: u64,

    sleep_timer: SleepTimerState,
    sleep_cycle_idx: usize,
    last_sleep_tick: Option<Instant>,
    timer_hidden_mode: bool,
    muted: bool,
    pre_mute_volume: f32,
    speed_cycle_idx: usize,
    scan_in_flight: Arc<AtomicBool>,
    watcher: Option<LibraryWatcher>,

    shell_host: Option<WindowsShellHost>,
    discord_rpc: DiscordRpcHost,
    last_discord_key: String,
    last_discord_position_ms: u64,
    last_discord_sync_at: Option<Instant>,
    last_discord_status: Option<DiscordRpcStatus>,
    shell_tx: Option<Sender<ShellCommand>>,
    window_hook: Option<MainWindowHook>,
    raw_hwnd: Option<isize>,
    window_visible: bool,
    quit_requested: bool,
    last_shell_meta_key: String,
    last_taskbar_progress_ms: u64,
    ui_tasks: UiTaskQueue,

    tag_editor_target: Option<Track>,
    tag_editor_cover: CoverArtEdit,
}

/// Coordinates startup hydration, UI callbacks, adaptive timer pacing, and clean shutdown.
#[expect(
    clippy::too_many_arguments,
    reason = "wires UI, audio, DB, storage paths, and native Windows shell hooks"
)]
pub fn init_and_bind(
    window: &MainWindow,
    engine: &Arc<PlaybackEngine>,
    dsp_store: &Arc<DspConfigStore>,
    db: LibraryDb,
    storage_paths: AppStoragePaths,
    prefs: UserPreferences,
    startup_paths: &[PathBuf],
    shell_host: Option<WindowsShellHost>,
    shell_tx: Sender<ShellCommand>,
    shell_rx: Receiver<ShellCommand>,
) -> anyhow::Result<Box<dyn FnOnce()>> {
    window
        .window()
        .set_size(slint::LogicalSize::new(1_180.0, 724.0));

    let dark_theme = !matches!(prefs.theme_mode, ThemeMode::Light);
    let active_palette = build_palette_from_seed(LUNARIA_BRAND_SEED_ARGB, dark_theme, 0.0);

    let initial_eq_bands: Vec<EqBandRow> = STANDARD_EQ_FREQUENCIES_HZ
        .iter()
        .enumerate()
        .map(|(i, &hz)| {
            let freq_label = if hz >= 1_000 {
                format!("{}k", hz / 1_000)
            } else {
                hz.to_string()
            };
            EqBandRow {
                band_index: i as i32,
                freq_label: SharedString::from(freq_label),
                gain_db: 0.0,
                gain_text: SharedString::from("+0.0"),
            }
        })
        .collect();
    let eq_bands_model = Rc::new(VecModel::from(initial_eq_bands));
    window.set_eq_bands(ModelRc::from(eq_bands_model.clone()));

    let discord_rpc = DiscordRpcHost::new();
    discord_rpc.configure(prefs.discord_rpc_enabled, prefs.discord_client_id.clone());

    let initial_state = AppState {
        db,
        storage_paths,
        prefs,
        dark_theme,
        manual_seed_argb: LUNARIA_BRAND_SEED_ARGB,
        selected_swatch_idx: 0,
        active_palette,
        devices: Vec::new(),
        selected_device_idx: 0,
        watched_folders: Vec::new(),
        all_tracks: Vec::new(),
        albums: Vec::new(),
        artists: Vec::new(),
        folders: Vec::new(),
        playlists: Vec::new(),
        active_playlist_target: None,
        sort_order: TrackSortOrder::TitleAsc,
        library_subtab: 0,
        drilldown: None,
        visible_groups: Vec::new(),
        visible_library_tracks: Vec::new(),
        home_spotlight_tracks: Vec::new(),
        search_criteria: SearchCriteria::default(),
        visible_search_tracks: Vec::new(),
        stats_top_tracks: Vec::new(),
        queue: PlaybackQueue::default(),
        current_lyrics: None,
        active_lyric_line: None,
        last_track_id: None,
        last_artwork_key: None,
        artwork_revision: 0,
        last_bode_signature: String::new(),
        eq_bands_model,
        marquee_elapsed_sec: 0.0,
        last_marquee_track_key: String::new(),
        restored_position_ms: 0,

        sleep_timer: SleepTimerState::Off,
        sleep_cycle_idx: 0,
        last_sleep_tick: None,
        timer_hidden_mode: false,
        muted: false,
        pre_mute_volume: 1.0,
        speed_cycle_idx: 0,
        scan_in_flight: Arc::new(AtomicBool::new(false)),
        watcher: None,
        shell_host,
        discord_rpc,
        last_discord_key: String::new(),
        last_discord_position_ms: 0,
        last_discord_sync_at: None,
        last_discord_status: None,
        shell_tx: Some(shell_tx),
        window_hook: None,
        raw_hwnd: None,
        window_visible: true,
        quit_requested: false,
        last_shell_meta_key: String::new(),
        last_taskbar_progress_ms: 0,
        ui_tasks: Arc::new(Mutex::new(VecDeque::new())),
        tag_editor_target: None,
        tag_editor_cover: CoverArtEdit::KeepExisting,
    };

    let state = Rc::new(RefCell::new(initial_state));
    let timer = Rc::new(Timer::default());

    {
        let mut st = state.borrow_mut();
        ensure_default_watched_folder(&mut st);
        hydrate_from_db(&mut st);
        restore_persisted_queue_state(&mut st, engine);
        reload_devices_into_state(window, &mut st);
        sync_theme_and_visuals(window, &mut st, engine);
        sync_all_views(window, &mut st, engine);
        let initial_watched = st.watched_folders.clone();
        restart_folder_watcher(&mut st, window.as_weak(), &initial_watched);
    }

    if !startup_paths.is_empty() {
        handle_open_paths(window, &state, engine, &timer, startup_paths);
    } else {
        // Trigger fast incremental scan in background without delaying window frame display.
        spawn_library_scan_with_refresh(window, &state, engine);
    }

    bind_navigation_and_theme(window, &state, engine);
    bind_library_and_search(window, &state, engine, &timer);
    bind_equalizer_callbacks(window, &state, dsp_store, engine);
    bind_transport_and_queue(window, &state, engine, &timer);
    bind_settings_and_devices(window, &state, engine, &timer);
    bind_tag_editor_callbacks(window, &state, engine);
    bind_close_to_tray_and_shell(window, &state, engine, &timer, shell_rx);

    let attach_timer = Rc::new(Timer::default());
    let needs_deferred_attach = {
        let mut st = state.borrow_mut();
        ensure_native_window_attached(window, &mut st, engine).is_none()
    };
    if needs_deferred_attach {
        let weak_attach = window.as_weak();
        let state_attach = Rc::clone(&state);
        let engine_attach = Arc::clone(engine);
        let timer_attach = Rc::downgrade(&attach_timer);
        let attempts = Cell::new(0_u16);
        attach_timer.start(TimerMode::Repeated, Duration::from_millis(16), move || {
            let next_attempt = attempts.get().saturating_add(1);
            attempts.set(next_attempt);
            let attached = if let Some(win) = weak_attach.upgrade() {
                let mut st = state_attach.borrow_mut();
                ensure_native_window_attached(&win, &mut st, &engine_attach).is_some()
            } else {
                true
            };
            if (attached || next_attempt >= 250)
                && let Some(tm) = timer_attach.upgrade()
            {
                tm.stop();
            }
        });
    }

    let weak_sync = window.as_weak();
    let state_sync = Rc::clone(&state);
    let engine_sync = Arc::clone(engine);
    let timer_sync = Rc::clone(&timer);
    window.on_sync_telemetry(move || {
        if let Some(win) = weak_sync.upgrade() {
            {
                let mut st = state_sync.borrow_mut();
                sync_playback_telemetry(&win, &mut st, &engine_sync);
            }
            ensure_adaptive_timer(&timer_sync, &weak_sync, &state_sync, &engine_sync, false);
        }
    });

    let state_shutdown = Rc::clone(&state);
    let timer_shutdown = Rc::clone(&timer);
    let attach_timer_shutdown = Rc::clone(&attach_timer);
    let engine_shutdown = Arc::clone(engine);
    Ok(Box::new(move || {
        timer_shutdown.stop();
        attach_timer_shutdown.stop();
        let mut st = state_shutdown.borrow_mut();
        st.watcher = None;
        st.shell_host = None;
        st.window_hook = None;
        st.shell_tx = None;
        let speed = SPEED_CYCLE[st.speed_cycle_idx];
        let pos_ms = engine_shutdown.position_ms();
        let pq = st.queue.to_persisted(pos_ms, speed);
        let _ = st.db.save_persisted_queue(&pq);
        let _ = st.db.save_preferences(&st.prefs);
    }))
}

fn extract_win32_hwnd(window: &MainWindow) -> Option<isize> {
    let handle = window.window().window_handle();
    let raw = handle.window_handle().ok()?.as_raw();
    match raw {
        RawWindowHandle::Win32(h) => Some(h.hwnd.get()),
        _ => None,
    }
}

fn ensure_native_window_attached(
    win: &MainWindow,
    st: &mut AppState,
    engine: &PlaybackEngine,
) -> Option<isize> {
    let current_hwnd = extract_win32_hwnd(win)?;
    if st.raw_hwnd == Some(current_hwnd) && st.window_hook.is_some() {
        return Some(current_hwnd);
    }

    st.window_hook = None;
    st.raw_hwnd = Some(current_hwnd);
    configure_frameless_window_chrome(current_hwnd);
    win.set_is_maximized(is_window_maximized(current_hwnd));

    let ov = st.active_palette.outline_variant;
    sync_window_border_color(
        current_hwnd,
        (
            ((ov >> 16) & 0xFF) as u8,
            ((ov >> 8) & 0xFF) as u8,
            (ov & 0xFF) as u8,
        ),
    );

    if let Some(tx) = st.shell_tx.as_ref() {
        match MainWindowHook::attach(current_hwnd, tx.clone()) {
            Ok(mut hook) => {
                let tel = engine.telemetry();
                let has_track = tel.active_track.is_some();
                let playing = tel.status == PlaybackStatus::Playing;
                hook.update_playback(playing, has_track, tel.position_ms, tel.duration_ms);
                st.window_hook = Some(hook);
                tracing::info!(
                    hwnd = format_args!("0x{current_hwnd:X}"),
                    "attached native Windows HWND hooks"
                );
            }
            Err(err) => {
                tracing::warn!(error = %err, "failed to attach native Windows HWND hook");
            }
        }
    }

    Some(current_hwnd)
}

fn now_epoch_seconds() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_secs()).unwrap_or(i64::MAX))
}

fn now_epoch_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

fn format_timecode_ms(ms: u64) -> String {
    let total_secs = ms / 1_000;
    let hours = total_secs / 3_600;
    let mins = (total_secs % 3_600) / 60;
    let secs = total_secs % 60;
    if hours > 0 {
        format!("{hours}:{mins:02}:{secs:02}")
    } else {
        format!("{mins:02}:{secs:02}")
    }
}

fn format_duration_compact(ms: u64) -> String {
    let total_mins = ms / 60_000;
    let hours = total_mins / 60;
    let mins = total_mins % 60;
    if hours > 0 {
        format!("{hours}h {mins}m")
    } else {
        format!("{mins}m")
    }
}

fn track_to_playback_request(track: &Track) -> PlaybackRequest {
    PlaybackRequest {
        file_path: PathBuf::from(&track.file_path),
        track_id: Some(track.id),
        title: track.title.clone(),
        artist: track.artist.clone(),
        album: track.album.clone(),
        clip_start_ms: track.clip_start_ms,
        clip_end_ms: track.clip_end_ms,
        replay_gain_track_db: track.replay_gain_track_db,
        replay_gain_album_db: track.replay_gain_album_db,
        replay_gain_track_peak: track.replay_gain_track_peak,
        replay_gain_album_peak: track.replay_gain_album_peak,
    }
}

fn track_to_row(track: &Track, active_id: Option<TrackId>) -> TrackRow {
    TrackRow {
        track_id: track.id.0 as i32,
        title: SharedString::from(track.title.as_str()),
        artist: SharedString::from(track.artist.as_str()),
        album: SharedString::from(track.album.as_str()),
        quality_badge: SharedString::from(track.audio_spec.format_quality_summary()),
        duration_text: SharedString::from(format_timecode_ms(track.duration_ms)),
        is_hi_res: track.audio_spec.is_hi_res(),
        is_favorite: track.is_favorite,
        is_cue: track.is_cue_track(),
        is_playing: active_id == Some(track.id),
    }
}

fn ensure_default_watched_folder(st: &mut AppState) {
    let Ok(existing) = st.db.list_watched_folders() else {
        return;
    };
    if !existing.is_empty() {
        return;
    }
    if let Some(default_dir) = lunaria_db::default_music_folder()
        && default_dir.is_dir()
    {
        let _ = st
            .db
            .add_watched_folder(&default_dir.to_string_lossy(), now_epoch_seconds());
    }
}

fn hydrate_from_db(st: &mut AppState) {
    st.watched_folders = st.db.list_watched_folders().unwrap_or_default();
    st.all_tracks = st.db.list_tracks(st.sort_order).unwrap_or_default();
    st.albums = st.db.list_albums().unwrap_or_default();
    st.artists = st.db.list_artists().unwrap_or_default();
    st.folders = st.db.list_folders().unwrap_or_default();
    st.playlists = st.db.list_playlists().unwrap_or_default();

    if let Some(target_pid) = st.active_playlist_target
        && !st.playlists.iter().any(|p| p.id == target_pid)
    {
        st.active_playlist_target = None;
    }
    if st.active_playlist_target.is_none() {
        st.active_playlist_target = st.playlists.first().map(|p| p.id);
    }

    st.visible_search_tracks = st
        .db
        .search_tracks(&st.search_criteria, 400)
        .unwrap_or_default();
}

fn restore_persisted_queue_state(st: &mut AppState, engine: &PlaybackEngine) {
    let Ok(Some(saved)) = st.db.load_persisted_queue() else {
        return;
    };
    if saved.track_ids.is_empty() {
        return;
    }
    let resolved = st
        .db
        .get_tracks_by_ids(&saved.track_ids)
        .unwrap_or_default();
    st.queue
        .restore_from_persisted(&saved, resolved, now_epoch_ms() as u64);
    st.restored_position_ms = saved.position_ms;
    if let Some(idx) = SPEED_CYCLE
        .iter()
        .position(|&s| (s - saved.speed).abs() < 0.05)
    {
        st.speed_cycle_idx = idx;
        let _ = engine.set_speed(SPEED_CYCLE[idx]);
    }
}

fn active_seed_for_state(st: &AppState) -> u32 {
    if st.prefs.use_album_art_theme
        && let Some(track) = st.queue.current_track()
        && let Some(seed) = track.seed_color_argb
    {
        return seed;
    }
    st.manual_seed_argb
}

fn sync_theme_and_visuals(win: &MainWindow, st: &mut AppState, engine: &PlaybackEngine) {
    let seed = active_seed_for_state(st);
    let palette = build_palette_from_seed(seed, st.dark_theme, 0.0);
    st.active_palette = palette;

    let global = win.global::<LunariaTheme>();
    global.set_is_dark(palette.is_dark);
    global.set_primary(argb_to_slint_color(palette.primary));
    global.set_on_primary(argb_to_slint_color(palette.on_primary));
    global.set_primary_container(argb_to_slint_color(palette.primary_container));
    global.set_on_primary_container(argb_to_slint_color(palette.on_primary_container));
    global.set_secondary(argb_to_slint_color(palette.secondary));
    global.set_on_secondary(argb_to_slint_color(palette.on_secondary));
    global.set_secondary_container(argb_to_slint_color(palette.secondary_container));
    global.set_on_secondary_container(argb_to_slint_color(palette.on_secondary_container));
    global.set_tertiary(argb_to_slint_color(palette.tertiary));
    global.set_surface(argb_to_slint_color(palette.surface));
    global.set_surface_container_lowest(argb_to_slint_color(palette.surface_container_lowest));
    global.set_surface_container_low(argb_to_slint_color(palette.surface_container_low));
    global.set_surface_container(argb_to_slint_color(palette.surface_container));
    global.set_surface_container_high(argb_to_slint_color(palette.surface_container_high));
    global.set_surface_container_highest(argb_to_slint_color(palette.surface_container_highest));
    global.set_on_surface(argb_to_slint_color(palette.on_surface));
    global.set_on_surface_variant(argb_to_slint_color(palette.on_surface_variant));
    global.set_error(argb_to_slint_color(palette.error));
    global.set_on_error(argb_to_slint_color(palette.on_error));
    global.set_error_container(argb_to_slint_color(palette.error_container));
    global.set_on_error_container(argb_to_slint_color(palette.on_error_container));
    global.set_outline(argb_to_slint_color(palette.outline));
    global.set_outline_variant(argb_to_slint_color(palette.outline_variant));

    if let Some(hwnd) = st.raw_hwnd {
        let ov = palette.outline_variant;
        sync_window_border_color(
            hwnd,
            (
                ((ov >> 16) & 0xFF) as u8,
                ((ov >> 8) & 0xFF) as u8,
                (ov & 0xFF) as u8,
            ),
        );
    }

    win.set_active_seed_hex(SharedString::from(format_rgb_hex(seed)));
    win.set_use_album_art_theme(st.prefs.use_album_art_theme);
    let swatches: Vec<SwatchRow> = PRESET_SEED_SWATCHES
        .iter()
        .enumerate()
        .map(|(idx, sw)| SwatchRow {
            name: SharedString::from(sw.name),
            hex: SharedString::from(format_rgb_hex(sw.argb)),
            color_preview: argb_to_slint_color(sw.argb),
            selected: idx == st.selected_swatch_idx,
        })
        .collect();
    win.set_swatches(ModelRc::new(VecModel::from(swatches)));

    st.last_bode_signature.clear();
    sync_eq_views(win, st, engine);
}

fn sync_all_views(win: &MainWindow, st: &mut AppState, engine: &PlaybackEngine) {
    win.set_nav_library_badge(SharedString::from(st.all_tracks.len().to_string()));
    sync_home_views(win, st);
    sync_library_views(win, st);
    sync_search_views(win, st);
    sync_eq_views(win, st, engine);
    sync_stats_views(win, st);
    sync_settings_views(win, st);
    sync_queue_and_lyrics_views(win, st);
    sync_playback_telemetry(win, st, engine);
}

fn sync_home_views(win: &MainWindow, st: &mut AppState) {
    let lossless_cnt = st
        .all_tracks
        .iter()
        .filter(|t| t.audio_spec.is_lossless)
        .count();
    win.set_library_summary(SharedString::from(format!(
        "{} tracks • {} albums • {} lossless",
        st.all_tracks.len(),
        st.albums.len(),
        lossless_cnt
    )));

    let now_ms = now_epoch_ms();
    let summaries = summarize_smart_playlists(&st.all_tracks, now_ms);
    let sp_rows: Vec<SmartPlaylistRow> = summaries
        .into_iter()
        .enumerate()
        .map(|(idx, sp)| SmartPlaylistRow {
            kind_index: idx as i32,
            title: SharedString::from(sp.title),
            subtitle: SharedString::from(sp.subtitle),
            count_text: SharedString::from(format!("{} tracks", sp.track_count)),
        })
        .collect();
    win.set_smart_playlists(ModelRc::new(VecModel::from(sp_rows)));

    if let Some(resume_t) = st.queue.current_track()
        && !st.queue.tracks().is_empty()
    {
        win.set_has_resume(true);
        win.set_resume_title(SharedString::from(resume_t.title.as_str()));
        win.set_resume_subtitle(SharedString::from(format!(
            "{} • {} in queue",
            resume_t.artist,
            st.queue.tracks().len()
        )));
    } else {
        win.set_has_resume(false);
    }

    let mut spotlight: Vec<Track> = st.all_tracks.clone();
    spotlight.sort_by(|a, b| {
        b.audio_spec
            .is_hi_res()
            .cmp(&a.audio_spec.is_hi_res())
            .then_with(|| b.date_added_seconds.cmp(&a.date_added_seconds))
            .then_with(|| a.title.cmp(&b.title))
    });
    spotlight.truncate(24);
    let active_id = st.queue.current_track().map(|t| t.id);
    let rows: Vec<TrackRow> = spotlight
        .iter()
        .map(|t| track_to_row(t, active_id))
        .collect();
    st.home_spotlight_tracks = spotlight;
    win.set_spotlight_tracks(ModelRc::new(VecModel::from(rows)));
}

fn sync_library_views(win: &MainWindow, st: &mut AppState) {
    win.set_library_subtab(st.library_subtab as i32);
    win.set_show_playlist_add_btn(!st.playlists.is_empty());
    win.set_sort_label(SharedString::from(match st.sort_order {
        TrackSortOrder::TitleAsc => "Sort: Title",
        TrackSortOrder::ArtistAsc => "Sort: Artist",
        TrackSortOrder::AlbumAsc => "Sort: Album",
        TrackSortOrder::DateAddedDesc => "Sort: Added",
        TrackSortOrder::DurationDesc => "Sort: Length",
    }));

    let active_id = st.queue.current_track().map(|t| t.id);

    if let Some(drill) = &st.drilldown {
        win.set_library_in_drilldown(true);
        let (title, is_pl, tracks) = match drill {
            LibraryDrilldown::Album(aid, name) => (
                format!("Album: {name}"),
                false,
                st.all_tracks
                    .iter()
                    .filter(|t| t.album_id == *aid)
                    .cloned()
                    .collect(),
            ),
            LibraryDrilldown::Artist(name) => (
                format!("Artist: {name}"),
                false,
                st.all_tracks
                    .iter()
                    .filter(|t| t.artist.eq_ignore_ascii_case(name))
                    .cloned()
                    .collect(),
            ),
            LibraryDrilldown::Folder(folder_path) => (
                format!("Folder: {folder_path}"),
                false,
                st.all_tracks
                    .iter()
                    .filter(|t| t.folder_path.eq_ignore_ascii_case(folder_path))
                    .cloned()
                    .collect(),
            ),
            LibraryDrilldown::Playlist(pid, name) => (
                format!("Playlist: {name}"),
                true,
                st.db.get_playlist_tracks(*pid).unwrap_or_default(),
            ),
            LibraryDrilldown::SmartPlaylist(kind) => (
                format!("Smart: {}", kind.title()),
                false,
                tracks_for_smart_playlist(*kind, &st.all_tracks, now_epoch_ms()),
            ),
        };
        win.set_library_drilldown_title(SharedString::from(title));
        win.set_library_in_playlist_drilldown(is_pl);
        let rows: Vec<TrackRow> = tracks.iter().map(|t| track_to_row(t, active_id)).collect();
        st.visible_library_tracks = tracks;
        win.set_library_tracks(ModelRc::new(VecModel::from(rows)));
        win.set_library_groups(ModelRc::new(VecModel::from(Vec::<GroupRow>::new())));
        return;
    }

    win.set_library_in_drilldown(false);
    win.set_library_in_playlist_drilldown(false);

    match st.library_subtab {
        0 => {
            let rows: Vec<TrackRow> = st
                .all_tracks
                .iter()
                .map(|t| track_to_row(t, active_id))
                .collect();
            st.visible_library_tracks = st.all_tracks.clone();
            win.set_library_tracks(ModelRc::new(VecModel::from(rows)));
            win.set_library_groups(ModelRc::new(VecModel::from(Vec::<GroupRow>::new())));
        }
        1 => {
            st.visible_groups = st
                .albums
                .iter()
                .map(|a| GroupKey::Album(a.id, a.title.clone()))
                .collect();
            let rows: Vec<GroupRow> = st
                .albums
                .iter()
                .map(|a| GroupRow {
                    key_id: a.id.0 as i32,
                    key_text: SharedString::default(),
                    title: SharedString::from(a.title.as_str()),
                    subtitle: SharedString::from(if a.year > 0 {
                        format!("{} • {}", a.artist, a.year)
                    } else {
                        a.artist.clone()
                    }),
                    meta_badge: SharedString::from(format!("{} tracks", a.track_count)),
                    has_hi_res: a.has_hi_res,
                })
                .collect();
            win.set_library_groups(ModelRc::new(VecModel::from(rows)));
            st.visible_library_tracks.clear();
            win.set_library_tracks(ModelRc::new(VecModel::from(Vec::<TrackRow>::new())));
        }
        2 => {
            st.visible_groups = st
                .artists
                .iter()
                .map(|a| GroupKey::Artist(a.name.clone()))
                .collect();
            let rows: Vec<GroupRow> = st
                .artists
                .iter()
                .map(|a| GroupRow {
                    key_id: 0,
                    key_text: SharedString::from(a.name.as_str()),
                    title: SharedString::from(a.name.as_str()),
                    subtitle: SharedString::from(format!("{} album(s)", a.album_count)),
                    meta_badge: SharedString::from(format!("{} tracks", a.track_count)),
                    has_hi_res: false,
                })
                .collect();
            win.set_library_groups(ModelRc::new(VecModel::from(rows)));
            st.visible_library_tracks.clear();
            win.set_library_tracks(ModelRc::new(VecModel::from(Vec::<TrackRow>::new())));
        }
        3 => {
            st.visible_groups = st
                .folders
                .iter()
                .map(|f| GroupKey::Folder(f.path.clone()))
                .collect();
            let rows: Vec<GroupRow> = st
                .folders
                .iter()
                .map(|f| GroupRow {
                    key_id: 0,
                    key_text: SharedString::from(f.path.as_str()),
                    title: SharedString::from(f.display_name.as_str()),
                    subtitle: SharedString::from(f.path.as_str()),
                    meta_badge: SharedString::from(format!(
                        "{} • {}",
                        f.track_count,
                        format_duration_compact(f.total_duration_ms)
                    )),
                    has_hi_res: false,
                })
                .collect();
            win.set_library_groups(ModelRc::new(VecModel::from(rows)));
            st.visible_library_tracks.clear();
            win.set_library_tracks(ModelRc::new(VecModel::from(Vec::<TrackRow>::new())));
        }
        _ => {
            st.visible_groups = st
                .playlists
                .iter()
                .map(|p| GroupKey::Playlist(p.id, p.name.clone()))
                .collect();
            let rows: Vec<GroupRow> = st
                .playlists
                .iter()
                .map(|p| GroupRow {
                    key_id: p.id.0 as i32,
                    key_text: SharedString::from(p.name.as_str()),
                    title: SharedString::from(p.name.as_str()),
                    subtitle: SharedString::from("Local SQLite Playlist (.m3u8 exportable)"),
                    meta_badge: SharedString::from(format!("{} tracks", p.track_count)),
                    has_hi_res: false,
                })
                .collect();
            win.set_library_groups(ModelRc::new(VecModel::from(rows)));
            st.visible_library_tracks.clear();
            win.set_library_tracks(ModelRc::new(VecModel::from(Vec::<TrackRow>::new())));
        }
    }
}

fn sync_search_views(win: &MainWindow, st: &AppState) {
    win.set_search_filter_hires(
        st.search_criteria
            .filters
            .contains(&SearchFilterChip::HiRes),
    );
    win.set_search_filter_lossless(
        st.search_criteria
            .filters
            .contains(&SearchFilterChip::Lossless),
    );
    win.set_search_filter_flac(st.search_criteria.filters.contains(&SearchFilterChip::Flac));
    win.set_search_filter_favorites(
        st.search_criteria
            .filters
            .contains(&SearchFilterChip::Favorites),
    );
    win.set_search_result_summary(SharedString::from(format!(
        "{} match(es)",
        st.visible_search_tracks.len()
    )));
    let active_id = st.queue.current_track().map(|t| t.id);
    let rows: Vec<TrackRow> = st
        .visible_search_tracks
        .iter()
        .map(|t| track_to_row(t, active_id))
        .collect();
    win.set_search_results(ModelRc::new(VecModel::from(rows)));
}

fn sync_eq_views(win: &MainWindow, st: &mut AppState, engine: &PlaybackEngine) {
    let eq = &st.prefs.equalizer;
    win.set_eq_enabled(eq.enabled);
    win.set_eq_preset_name(SharedString::from(eq.active_preset_name.as_str()));

    let preset_idx = BUILT_IN_EQ_PRESETS
        .iter()
        .position(|p| p.name.eq_ignore_ascii_case(&eq.active_preset_name))
        .map_or(-1, |i| i as i32);
    win.set_eq_preset_idx(preset_idx);

    for (i, &hz) in STANDARD_EQ_FREQUENCIES_HZ.iter().enumerate() {
        let raw = eq.band_gains_db[i];
        let gain = if raw.abs() < 0.05 { 0.0 } else { raw };
        let freq_label = if hz >= 1_000 {
            format!("{}k", hz / 1_000)
        } else {
            hz.to_string()
        };
        let gain_text = SharedString::from(format!("{gain:+.1}"));
        let should_update = st.eq_bands_model.row_data(i).is_none_or(|existing| {
            (existing.gain_db - gain).abs() > 0.001
                || existing.gain_text != gain_text
                || existing.freq_label != freq_label.as_str()
        });
        if should_update {
            st.eq_bands_model.set_row_data(
                i,
                EqBandRow {
                    band_index: i as i32,
                    freq_label: SharedString::from(freq_label),
                    gain_db: gain,
                    gain_text,
                },
            );
        }
    }

    win.set_preamp_label(SharedString::from(format!("{:+.1} dB", eq.preamp_db)));
    win.set_rg_mode_label(SharedString::from(match eq.replay_gain_mode {
        ReplayGainMode::Off => "ReplayGain: Off",
        ReplayGainMode::Track => "ReplayGain: Track",
        ReplayGainMode::Album => "ReplayGain: Album",
    }));
    win.set_rg_active(eq.replay_gain_mode != ReplayGainMode::Off);
    win.set_rg_preamp_label(SharedString::from(format!(
        "{:+.1} dB",
        eq.replay_gain_preamp_db
    )));
    let bal_label = if eq.channel_balance.abs() < 0.05 {
        "Center".to_owned()
    } else if eq.channel_balance < 0.0 {
        format!("L {:.0}%", eq.channel_balance.abs() * 100.0)
    } else {
        format!("R {:.0}%", eq.channel_balance * 100.0)
    };
    win.set_balance_label(SharedString::from(bal_label));
    win.set_mono_audio(eq.mono_audio);
    win.set_crossfade_label(SharedString::from(format!(
        "Crossfade: {}s",
        eq.crossfade_duration_sec
    )));

    let sr = engine
        .telemetry()
        .source_info
        .as_ref()
        .map_or(48_000, |i| i.spec.sample_rate_hz);
    let pf_sig: String = eq
        .parametric_filters
        .iter()
        .map(|f| {
            format!(
                "{}:{:?}:{:.1}:{:.2}:{:.2}",
                f.enabled, f.filter_type, f.frequency_hz, f.gain_db, f.q_factor
            )
        })
        .collect::<Vec<_>>()
        .join(",");
    let sig = format!(
        "{}:{}:{:.2}:{:?}:{}:{}:{:08X}:{:08X}",
        eq.enabled,
        sr,
        eq.preamp_db,
        eq.band_gains_db,
        pf_sig,
        st.active_palette.is_dark,
        st.active_palette.primary,
        st.active_palette.surface_container_lowest
    );
    if sig != st.last_bode_signature {
        st.last_bode_signature = sig;
        let bode_img =
            render_bode_curve_image(eq, sr, BODE_WIDTH_PX, BODE_HEIGHT_PX, &st.active_palette);
        win.set_bode_curve_image(bode_img);
    }
}

fn sync_stats_views(win: &MainWindow, st: &mut AppState) {
    let stats = compute_listening_stats(&st.all_tracks, 12);
    win.set_kpi_total_plays(SharedString::from(stats.total_plays.to_string()));
    win.set_kpi_listen_time(SharedString::from(format_duration_compact(
        stats.total_listening_ms,
    )));
    win.set_kpi_unique_tracks(SharedString::from(stats.unique_tracks_played.to_string()));
    let hires_pct = stats
        .hi_res_plays
        .saturating_mul(100)
        .checked_div(stats.total_plays)
        .unwrap_or(0);
    win.set_kpi_hires_share(SharedString::from(format!("{hires_pct}%")));

    let artists: Vec<StatArtistRow> = stats
        .top_artists
        .iter()
        .enumerate()
        .map(|(i, art)| StatArtistRow {
            rank: (i + 1) as i32,
            name: SharedString::from(art.artist_name.as_str()),
            plays_text: SharedString::from(format!("{} plays", art.play_count)),
            duration_text: SharedString::from(format_duration_compact(art.total_listening_ms)),
        })
        .collect();
    win.set_stat_artists(ModelRc::new(VecModel::from(artists)));

    let active_id = st.queue.current_track().map(|t| t.id);
    let top_rows: Vec<TrackRow> = stats
        .top_tracks
        .iter()
        .map(|t| track_to_row(t, active_id))
        .collect();
    st.stats_top_tracks = stats.top_tracks;
    win.set_stat_tracks(ModelRc::new(VecModel::from(top_rows)));
}

fn sync_settings_views(win: &MainWindow, st: &AppState) {
    win.set_output_mode_index(match st.prefs.audio_output_mode {
        AudioOutputMode::DspFloat32 => 0,
        AudioOutputMode::PuristDirectBitPerfect => 1,
    });
    let dev_rows: Vec<DeviceRow> = st
        .devices
        .iter()
        .enumerate()
        .map(|(idx, dev)| DeviceRow {
            id: SharedString::from(dev.id.as_str()),
            name: SharedString::from(dev.name.as_str()),
            format_summary: SharedString::from(dev.mix_format_summary()),
            category: SharedString::from(dev.category.label()),
            is_default: dev.is_default,
            is_selected: idx == st.selected_device_idx,
        })
        .collect();
    win.set_devices(ModelRc::new(VecModel::from(dev_rows)));

    let folder_rows: Vec<WatchedFolderRow> = st
        .watched_folders
        .iter()
        .enumerate()
        .map(|(i, p)| WatchedFolderRow {
            index: i as i32,
            path: SharedString::from(p.as_str()),
        })
        .collect();
    win.set_watched_folders(ModelRc::new(VecModel::from(folder_rows)));

    win.set_folder_filter_label(SharedString::from(match st.prefs.folder_filter.mode {
        FolderFilterMode::Blacklist => "Filter: Blacklist",
        FolderFilterMode::Whitelist => "Filter: Whitelist",
    }));
    win.set_use_software_renderer(st.prefs.use_software_renderer);
    win.set_minimize_to_tray(st.prefs.minimize_to_tray);
    win.set_discord_rpc_enabled(st.prefs.discord_rpc_enabled);
    let rpc_status = st.discord_rpc.status();
    win.set_discord_rpc_status_text(SharedString::from(rpc_status.label()));
    win.set_storage_mode_badge(SharedString::from(if st.storage_paths.is_portable {
        "Portable Mode (./data)"
    } else {
        "Standard AppData"
    }));
    win.set_db_path_text(SharedString::from(
        st.storage_paths.db_path.to_string_lossy().as_ref(),
    ));
    win.set_app_version_label(SharedString::from(concat!("v", env!("CARGO_PKG_VERSION"))));
}

fn sync_queue_and_lyrics_views(win: &MainWindow, st: &mut AppState) {
    let cur_idx = st.queue.current_index();
    let q_rows: Vec<QueueRow> = st
        .queue
        .tracks()
        .iter()
        .enumerate()
        .map(|(idx, t)| QueueRow {
            queue_index: idx as i32,
            track_id: t.id.0 as i32,
            title: SharedString::from(t.title.as_str()),
            artist: SharedString::from(t.artist.as_str()),
            duration_text: SharedString::from(format_timecode_ms(t.duration_ms)),
            is_current: cur_idx == Some(idx),
        })
        .collect();
    win.set_queue_summary(SharedString::from(format!("{} track(s)", q_rows.len())));
    win.set_queue_rows(ModelRc::new(VecModel::from(q_rows)));

    win.set_repeat_label(SharedString::from(match st.queue.repeat_mode() {
        RepeatMode::Off => "Repeat: Off",
        RepeatMode::All => "Repeat: All",
        RepeatMode::One => "Repeat: One",
    }));
    win.set_repeat_active(st.queue.repeat_mode() != RepeatMode::Off);
    win.set_shuffle_enabled(st.queue.shuffle_enabled());
    let speed = SPEED_CYCLE[st.speed_cycle_idx];
    win.set_speed_label(SharedString::from(format!("{speed:.2}x")));

    win.set_sleep_label(SharedString::from(match st.sleep_timer {
        SleepTimerState::Off => "Sleep: Off".to_owned(),
        SleepTimerState::EndOfTrack => "Sleep: Track End".to_owned(),
        SleepTimerState::Countdown { remaining_ms } => {
            let mins = remaining_ms.div_ceil(60_000);
            format!("Sleep: {mins}m")
        }
    }));
    win.set_sleep_active(st.sleep_timer != SleepTimerState::Off);

    sync_lyrics_list_model(win, st, st.active_lyric_line);
}

fn reload_lyrics_for_current(win: &MainWindow, st: &mut AppState) {
    st.current_lyrics = st
        .queue
        .current_track()
        .and_then(|t| load_local_lyrics(Path::new(&t.file_path)));
    st.active_lyric_line = None;
    sync_lyrics_list_model(win, st, Some(0));
}

fn sync_lyrics_list_model(win: &MainWindow, st: &AppState, active_line: Option<usize>) {
    let Some(doc) = &st.current_lyrics else {
        win.set_lyrics_source_label(SharedString::from("No local lyrics"));
        win.set_lyrics_synced(false);
        win.set_lyric_rows(ModelRc::new(VecModel::from(Vec::<LyricRow>::new())));
        return;
    };

    win.set_lyrics_source_label(SharedString::from(doc.source_label.as_str()));
    win.set_lyrics_synced(doc.is_synced);
    let rows: Vec<LyricRow> = doc
        .lines
        .iter()
        .enumerate()
        .map(|(i, line)| LyricRow {
            line_index: i as i32,
            timestamp_ms: i32::try_from(line.timestamp_ms).unwrap_or(i32::MAX),
            time_label: SharedString::from(format_timecode_ms(line.timestamp_ms)),
            text: SharedString::from(line.text.as_str()),
            is_active: active_line == Some(i),
        })
        .collect();
    win.set_lyric_rows(ModelRc::new(VecModel::from(rows)));
}

fn sync_playback_telemetry(win: &MainWindow, st: &mut AppState, engine: &PlaybackEngine) {
    let tel = engine.telemetry();
    let signal = &tel.signal_path;

    let playing = tel.status == PlaybackStatus::Playing;
    let active_req = tel.active_track.as_ref();
    let has_track = active_req.is_some() || st.queue.current_track().is_some();

    win.set_has_track(has_track);
    win.set_is_playing(playing);

    // Sync active track metadata, artwork & dynamic album art HCT palette cleanly
    if let Some(q_track) = st.queue.current_track().cloned() {
        win.set_track_title(SharedString::from(q_track.title.as_str()));
        win.set_track_artist(SharedString::from(q_track.artist.as_str()));
        win.set_track_album(SharedString::from(if q_track.year > 0 {
            format!("{} ({})", q_track.album, q_track.year)
        } else {
            q_track.album.clone()
        }));
        win.set_track_spec_badge(SharedString::from(
            q_track.audio_spec.format_quality_summary(),
        ));
        win.set_current_is_favorite(q_track.is_favorite);
        win.set_current_is_cue(q_track.is_cue_track());

        let scale = win.window().scale_factor().clamp(1.0, 4.0);
        let hero_px = (HERO_ARTWORK_LOGICAL_PX * scale)
            .round()
            .clamp(272.0, 1_024.0) as u32;
        let mini_px = (MINI_ARTWORK_LOGICAL_PX * scale).round().clamp(48.0, 256.0) as u32;

        let wanted_key = q_track
            .artwork_uri
            .as_ref()
            .map(|u| (u.clone(), st.artwork_revision, hero_px));
        if wanted_key != st.last_artwork_key {
            st.last_artwork_key.clone_from(&wanted_key);
            let art = load_artwork_variants(q_track.artwork_uri.as_deref(), hero_px, mini_px);
            let has_art = art.hero.size().width > 0;
            win.set_has_artwork(has_art);
            win.set_now_artwork(art.hero);
            win.set_mini_artwork(art.mini);
        }
    } else if let Some(req) = active_req {
        win.set_track_title(SharedString::from(req.title.as_str()));
        win.set_track_artist(SharedString::from(req.artist.as_str()));
        win.set_track_album(SharedString::from(req.album.as_str()));
        win.set_track_spec_badge(SharedString::from(tel.source_info.as_ref().map_or_else(
            || "Audio Stream".to_owned(),
            |i| i.spec.format_quality_summary(),
        )));
        win.set_current_is_favorite(false);
        win.set_current_is_cue(req.clip_start_ms.is_some());
        st.last_artwork_key = None;
        win.set_has_artwork(false);
        win.set_now_artwork(slint::Image::default());
        win.set_mini_artwork(slint::Image::default());
    } else {
        win.set_track_title(SharedString::from("Ready to Play"));
        win.set_track_artist(SharedString::from("Lunaria Local Studio"));
        win.set_track_album(SharedString::from("Zero Network • Bit-Accurate"));
        win.set_track_spec_badge(SharedString::from("FLAC / WASAPI"));
        win.set_current_is_favorite(false);
        win.set_current_is_cue(false);
        if st.last_artwork_key.is_some() {
            st.last_artwork_key = None;
            win.set_has_artwork(false);
            win.set_now_artwork(slint::Image::default());
            win.set_mini_artwork(slint::Image::default());
        }
    }

    let duration_ms = if tel.duration_ms > 0 {
        tel.duration_ms
    } else {
        st.queue.current_track().map_or(0, |t| t.duration_ms)
    };
    let progress = if duration_ms > 0 {
        ((tel.position_ms as f64) / (duration_ms as f64)).clamp(0.0, 1.0) as f32
    } else {
        0.0
    };
    win.set_playback_progress(progress);
    win.set_position_text(SharedString::from(format_timecode_ms(tel.position_ms)));
    win.set_duration_text(SharedString::from(format_timecode_ms(duration_ms)));

    win.set_volume_level(tel.volume);
    win.set_is_muted(st.muted || tel.volume <= 0.001);
    let pct = (tel.volume * 100.0).round().clamp(0.0, 100.0) as u32;
    win.set_volume_text(SharedString::from(format!("{pct}%")));

    win.set_signal_source(SharedString::from(signal.source_format_label.as_str()));
    win.set_signal_decoder(SharedString::from(signal.decoder_name.as_str()));
    win.set_signal_stage_1(SharedString::from(
        signal.processing_chain.first().map_or("", String::as_str),
    ));
    win.set_signal_stage_2(SharedString::from(
        signal.processing_chain.get(1).map_or("", String::as_str),
    ));
    win.set_signal_stage_3(SharedString::from(
        signal.processing_chain.get(2).map_or("", String::as_str),
    ));
    win.set_signal_device(SharedString::from(signal.output_device_name.as_str()));
    win.set_signal_output_spec(SharedString::from(format!(
        "{}-bit / {} Hz • {} (xruns: {})",
        signal.output_bit_depth,
        signal.output_sample_rate_hz,
        signal.output_encoding_label,
        tel.xrun_count
    )));
    win.set_signal_is_bit_perfect(signal.is_bit_perfect_active);
    win.set_signal_honesty_badge(SharedString::from(if signal.is_bit_perfect_active {
        "Bit-Perfect"
    } else if signal.output_category == AudioOutputCategory::BluetoothA2dp {
        "Bluetooth A2DP"
    } else {
        signal.output_mode.label()
    }));

    if let Some(doc) = &st.current_lyrics
        && doc.is_synced
    {
        let next_line = active_line_index_at(&doc.lines, tel.position_ms);
        if next_line != st.active_lyric_line {
            st.active_lyric_line = next_line;
            sync_lyrics_list_model(win, st, next_line);
        }
    }

    if let Some(err) = &tel.error_message {
        win.set_status_banner(SharedString::from(err.as_str()));
    }

    let marquee_key = if let Some(q_track) = st.queue.current_track() {
        format!("id:{}:{}:{}", q_track.id.0, q_track.title, q_track.artist)
    } else if let Some(req) = active_req {
        format!("req:{}:{}", req.title, req.artist)
    } else {
        String::new()
    };
    if marquee_key != st.last_marquee_track_key || !playing {
        st.last_marquee_track_key = marquee_key;
        st.marquee_elapsed_sec = 0.0;
    }
    let mc = win.global::<MarqueeClock>();
    mc.set_active(has_track && playing && st.window_visible);
    mc.set_elapsed_sec(st.marquee_elapsed_sec);

    let rpc_status = st.discord_rpc.status();
    if st.last_discord_status != Some(rpc_status) {
        st.last_discord_status = Some(rpc_status);
        win.set_discord_rpc_status_text(SharedString::from(rpc_status.label()));
    }
    sync_discord_rpc_state(
        st,
        has_track,
        playing,
        tel.position_ms,
        duration_ms,
        tel.speed,
    );
    sync_windows_shell_state(st, has_track, playing, tel.position_ms, duration_ms);
}

fn ensure_adaptive_timer(
    timer: &Rc<Timer>,
    weak_win: &slint::Weak<MainWindow>,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
    force_start: bool,
) {
    let (visible, cur_hidden_mode) = {
        let st = state.borrow();
        (st.window_visible, st.timer_hidden_mode)
    };
    let status = engine.telemetry().status;
    let active = force_start || status == PlaybackStatus::Playing;
    if !active {
        timer.stop();
        state.borrow_mut().last_sleep_tick = None;
        return;
    }

    let hidden_mode = !visible;
    if timer.running() && cur_hidden_mode == hidden_mode {
        return;
    }
    if timer.running() {
        timer.stop();
    }

    {
        let mut st = state.borrow_mut();
        st.timer_hidden_mode = hidden_mode;
        st.last_sleep_tick = Some(Instant::now());
    }

    let interval = if hidden_mode {
        Duration::from_millis(1_000)
    } else {
        Duration::from_millis(33)
    };
    let timer_ref = Rc::downgrade(timer);
    let weak_cb = weak_win.clone();
    let state_cb = Rc::clone(state);
    let engine_cb = Arc::clone(engine);

    timer.start(TimerMode::Repeated, interval, move || {
        let Some(win) = weak_cb.upgrade() else {
            if let Some(t) = timer_ref.upgrade() {
                t.stop();
            }
            return;
        };

        let tel = engine_cb.telemetry();
        let mut need_pause_from_sleep = false;
        let mut need_advance_track = false;
        let mut track_changed_via_gapless = false;
        let visible_now = state_cb.borrow().window_visible;

        {
            let mut st = state_cb.borrow_mut();

            // Detect seamless gapless transition from audio engine decode thread
            let engine_id = tel.active_track.as_ref().and_then(|r| r.track_id);
            if engine_id.is_some()
                && engine_id != st.last_track_id
                && tel.status == PlaybackStatus::Playing
            {
                if let Some(prev_id) = st.last_track_id {
                    let prev_dur = st.queue.current_track().map_or(0, |t| t.duration_ms);
                    let _ = st.db.record_play(prev_id, now_epoch_ms(), prev_dur);
                }
                if let Some(new_id) = engine_id
                    && let Some(pos) = st.queue.tracks().iter().position(|t| t.id == new_id)
                {
                    let _ = st.queue.jump_to_index(pos);
                    track_changed_via_gapless = true;
                }
                st.last_track_id = engine_id;
            }

            if tel.status == PlaybackStatus::Ended {
                if let Some(prev_id) = st.last_track_id {
                    let prev_dur = st
                        .queue
                        .current_track()
                        .map_or(tel.duration_ms, |t| t.duration_ms);
                    let _ = st.db.record_play(prev_id, now_epoch_ms(), prev_dur);
                }
                st.last_track_id = None;
                need_advance_track = true;
            }

            let now = Instant::now();
            let elapsed_ms = st
                .last_sleep_tick
                .replace(now)
                .map_or(if visible_now { 33_u64 } else { 1_000_u64 }, |prev| {
                    (now.duration_since(prev).as_millis() as u64).clamp(1, 5_000)
                });

            if let SleepTimerState::Countdown { remaining_ms } = st.sleep_timer {
                let next_rem = remaining_ms.saturating_sub(elapsed_ms);
                if next_rem == 0 {
                    st.sleep_timer = SleepTimerState::Off;
                    st.sleep_cycle_idx = 0;
                    need_pause_from_sleep = true;
                } else {
                    st.sleep_timer = SleepTimerState::Countdown {
                        remaining_ms: next_rem,
                    };
                }
            }

            if track_changed_via_gapless {
                if visible_now {
                    hydrate_from_db(&mut st);
                    sync_theme_and_visuals(&win, &mut st, &engine_cb);
                    reload_lyrics_for_current(&win, &mut st);
                    sync_queue_and_lyrics_views(&win, &mut st);
                    sync_home_views(&win, &mut st);
                    sync_library_views(&win, &mut st);
                    sync_stats_views(&win, &mut st);
                }
                let next_req = st.queue.peek_next_track().map(track_to_playback_request);
                let _ = engine_cb.queue_next(next_req);
            }

            if visible_now {
                if tel.status == PlaybackStatus::Playing {
                    st.marquee_elapsed_sec += (elapsed_ms as f32) / 1_000.0;
                }
                sync_playback_telemetry(&win, &mut st, &engine_cb);
            } else {
                let dur = if tel.duration_ms > 0 {
                    tel.duration_ms
                } else {
                    st.queue.current_track().map_or(0, |t| t.duration_ms)
                };
                let has_track = tel.active_track.is_some() || st.queue.current_track().is_some();
                let playing = tel.status == PlaybackStatus::Playing;
                sync_windows_shell_state(&mut st, has_track, playing, tel.position_ms, dur);
            }
        }

        if need_pause_from_sleep {
            let _ = engine_cb.pause();
            if visible_now {
                win.set_sleep_label(SharedString::from("Sleep: Off"));
                win.set_sleep_active(false);
                win.set_status_banner(SharedString::from("Sleep timer paused playback."));
            } else {
                let mut st = state_cb.borrow_mut();
                let has_track = st.queue.current_track().is_some();
                sync_windows_shell_state(
                    &mut st,
                    has_track,
                    false,
                    tel.position_ms,
                    tel.duration_ms,
                );
            }
            if let Some(t) = timer_ref.upgrade() {
                t.stop();
            }
            return;
        }

        if need_advance_track {
            let handle_eot = {
                let mut st = state_cb.borrow_mut();
                if matches!(st.sleep_timer, SleepTimerState::EndOfTrack) {
                    st.sleep_timer = SleepTimerState::Off;
                    st.sleep_cycle_idx = 0;
                    true
                } else {
                    false
                }
            };

            if handle_eot {
                let _ = engine_cb.stop();
                if visible_now {
                    win.set_sleep_label(SharedString::from("Sleep: Off"));
                    win.set_sleep_active(false);
                    win.set_status_banner(SharedString::from(
                        "Sleep timer stopped at end of track.",
                    ));
                } else {
                    let mut st = state_cb.borrow_mut();
                    sync_windows_shell_state(&mut st, false, false, 0, 0);
                }
                if let Some(t) = timer_ref.upgrade() {
                    t.stop();
                }
            } else {
                let next_opt = {
                    let mut st = state_cb.borrow_mut();
                    if visible_now {
                        hydrate_from_db(&mut st);
                        sync_home_views(&win, &mut st);
                        sync_stats_views(&win, &mut st);
                    }
                    st.queue.advance_next(true).cloned()
                };
                if let Some(next_track) = next_opt {
                    let _ = engine_cb.load_and_play(track_to_playback_request(&next_track));
                    let mut st = state_cb.borrow_mut();
                    st.last_track_id = Some(next_track.id);
                    if visible_now {
                        sync_theme_and_visuals(&win, &mut st, &engine_cb);
                        reload_lyrics_for_current(&win, &mut st);
                        sync_queue_and_lyrics_views(&win, &mut st);
                        sync_library_views(&win, &mut st);
                    } else {
                        sync_windows_shell_state(&mut st, true, true, 0, next_track.duration_ms);
                    }
                    let peek_next = st.queue.peek_next_track().map(track_to_playback_request);
                    let _ = engine_cb.queue_next(peek_next);
                } else if let Some(t) = timer_ref.upgrade() {
                    t.stop();
                }
            }
            return;
        }

        if tel.status != PlaybackStatus::Playing
            && let Some(t) = timer_ref.upgrade()
        {
            t.stop();
        }
    });
}

fn start_playing_queue_current(
    win: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
    timer: &Rc<Timer>,
) {
    let current_opt = {
        let mut st = state.borrow_mut();
        let cur = st.queue.current_track().cloned();
        if let Some(track) = &cur {
            st.last_track_id = Some(track.id);
            let req = track_to_playback_request(track);
            if let Err(err) = engine.load_and_play(req) {
                win.set_status_banner(SharedString::from(format!("Playback failed: {err}")));
                return;
            }
            let next_req = st.queue.peek_next_track().map(track_to_playback_request);
            let _ = engine.queue_next(next_req);
            sync_theme_and_visuals(win, &mut st, engine);
            reload_lyrics_for_current(win, &mut st);
            sync_queue_and_lyrics_views(win, &mut st);
            sync_home_views(win, &mut st);
            sync_library_views(win, &mut st);
            sync_search_views(win, &st);
            sync_stats_views(win, &mut st);
            win.set_status_banner(SharedString::from(format!(
                "Playing: {} - {}",
                track.title, track.artist
            )));
        }
        cur
    };

    if current_opt.is_some() {
        ensure_adaptive_timer(timer, &win.as_weak(), state, engine, true);
    }
}

fn play_track_list_at(
    win: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
    timer: &Rc<Timer>,
    tracks: Vec<Track>,
    start_idx: usize,
) {
    if tracks.is_empty() {
        return;
    }
    {
        let mut st = state.borrow_mut();
        st.restored_position_ms = 0;
        st.queue.set_queue(tracks, start_idx, now_epoch_ms() as u64);
    }
    start_playing_queue_current(win, state, engine, timer);
}

fn play_external_file(
    win: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
    timer: &Rc<Timer>,
    path: &Path,
) {
    let scanned_opt = {
        let st = state.borrow();
        scan_single_audio_file(path, Some(&st.storage_paths.artwork_cache_dir), None).ok()
    };

    if let Some(track) = scanned_opt {
        play_track_list_at(win, state, engine, timer, vec![track], 0);
        return;
    }

    let req = PlaybackRequest::from_path(path);
    let title = req.title.clone();
    match engine.load_and_play(req) {
        Ok(()) => {
            {
                let mut st = state.borrow_mut();
                st.queue.clear();
                st.last_track_id = None;
                st.current_lyrics = None;
                sync_queue_and_lyrics_views(win, &mut st);
                sync_playback_telemetry(win, &mut st, engine);
            }
            win.set_status_banner(SharedString::from(format!("Playing: {title}")));
            ensure_adaptive_timer(timer, &win.as_weak(), state, engine, true);
        }
        Err(err) => {
            win.set_status_banner(SharedString::from(format!("Failed opening file: {err}")));
        }
    }
}

fn reload_devices_into_state(win: &MainWindow, st: &mut AppState) {
    match enumerate_render_devices() {
        Ok(devices) => {
            let preferred = st.prefs.preferred_device_id.as_deref();
            let chosen_idx = preferred
                .and_then(|pid| devices.iter().position(|d| d.id == pid))
                .or_else(|| devices.iter().position(|d| d.is_default))
                .unwrap_or(0);
            st.selected_device_idx = chosen_idx;
            win.set_status_banner(SharedString::from(format!(
                "WASAPI ready: {} active render endpoint(s).",
                devices.len()
            )));
            st.devices = devices;
        }
        Err(err) => {
            st.devices.clear();
            st.selected_device_idx = 0;
            win.set_status_banner(SharedString::from(format!("WASAPI error: {err}")));
        }
    }
}

fn spawn_library_scan_with_refresh(
    win: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    _engine: &Arc<PlaybackEngine>,
) {
    let (db_path, scan_config, scan_flag, ui_tasks) = {
        let st = state.borrow();
        if st.watched_folders.is_empty() {
            win.set_status_banner(SharedString::from(
                "Add at least one music folder before scanning.",
            ));
            return;
        }
        if st.scan_in_flight.swap(true, Ordering::AcqRel) {
            return;
        }
        (
            st.storage_paths.db_path.clone(),
            ScanConfig {
                root_folders: st.watched_folders.iter().map(PathBuf::from).collect(),
                filter: st.prefs.folder_filter.clone(),
                artwork_cache_dir: Some(st.storage_paths.artwork_cache_dir.clone()),
                parse_cue_sheets: true,
            },
            Arc::clone(&st.scan_in_flight),
            Arc::clone(&st.ui_tasks),
        )
    };

    win.set_scan_busy(true);
    win.set_status_banner(SharedString::from("Scanning library in parallel..."));
    let weak_cb = win.as_weak();

    let _handle = std::thread::Builder::new()
        .name("lunaria-scanner-worker".to_owned())
        .spawn(move || {
            let res = LibraryDb::open(&db_path)
                .map_err(anyhow::Error::from)
                .and_then(|mut bg_db| {
                    scan_library(&mut bg_db, &scan_config).map_err(anyhow::Error::from)
                });
            scan_flag.store(false, Ordering::Release);
            let err_msg = res.as_ref().err().map(ToString::to_string);
            let ok_report = res.ok();
            enqueue_ui_task(
                &ui_tasks,
                &weak_cb,
                move |ui, state_cb, engine_cb, _timer| {
                    ui.set_scan_busy(false);
                    let mut st = state_cb.borrow_mut();
                    if let Some(report) = ok_report {
                        hydrate_from_db(&mut st);
                        sync_all_views(ui, &mut st, engine_cb);
                        ui.set_status_banner(SharedString::from(format!(
                            "Library ready: {} scanned, {} cached, {} CUE, {} removed.",
                            report.scanned_tracks,
                            report.skipped_unchanged,
                            report.cue_tracks_indexed,
                            report.removed_tracks
                        )));
                    } else if let Some(msg) = err_msg {
                        ui.set_status_banner(SharedString::from(format!("Scan failed: {msg}")));
                    }
                },
            );
        });
}

fn restart_folder_watcher(
    st: &mut AppState,
    weak_win: slint::Weak<MainWindow>,
    folders: &[String],
) {
    st.watcher = None;
    let Ok(mut watcher) = LibraryWatcher::new(DEFAULT_WATCH_DEBOUNCE) else {
        return;
    };
    let mut watched_any = false;
    for dir in folders {
        let p = Path::new(dir);
        if p.is_dir() && watcher.watch_folder(p).is_ok() {
            watched_any = true;
        }
    }
    if !watched_any {
        return;
    }

    let rx = watcher.receiver().clone();
    let db_path = st.storage_paths.db_path.clone();
    let art_cache = st.storage_paths.artwork_cache_dir.clone();
    let filter = st.prefs.folder_filter.clone();

    let _bridge = std::thread::Builder::new()
        .name("lunaria-watch-bridge".to_owned())
        .spawn(move || {
            while let Ok(batch) = rx.recv() {
                if batch.is_empty() {
                    continue;
                }
                let Ok(mut bg_db) = LibraryDb::open(&db_path) else {
                    continue;
                };
                let has_cue_changes = !batch.cue_paths.is_empty();
                if let Ok((upserted, removed)) = apply_watch_paths(
                    &mut bg_db,
                    &batch.upsert_paths,
                    &batch.remove_paths,
                    &filter,
                    Some(&art_cache),
                ) && (upserted > 0 || removed > 0 || has_cue_changes)
                {
                    let weak_ui = weak_win.clone();
                    let _ = slint::invoke_from_event_loop(move || {
                        if let Some(ui) = weak_ui.upgrade() {
                            ui.set_status_banner(SharedString::from(format!(
                                "Live watcher: +{upserted} / -{removed} change(s) detected - rescanning library."
                            )));
                            ui.invoke_rescan_library();
                        }
                    });
                }
            }
        });

    st.watcher = Some(watcher);
}

fn bind_navigation_and_theme(
    window: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
) {
    let weak_theme = window.as_weak();
    let state_theme = Rc::clone(state);
    let engine_theme = Arc::clone(engine);
    window.on_toggle_theme(move || {
        if let Some(win) = weak_theme.upgrade() {
            let mut st = state_theme.borrow_mut();
            st.dark_theme = !st.dark_theme;
            st.prefs.theme_mode = if st.dark_theme {
                ThemeMode::Dark
            } else {
                ThemeMode::Light
            };
            let _ = st.db.save_preferences(&st.prefs);
            sync_theme_and_visuals(&win, &mut st, &engine_theme);
        }
    });

    let weak_art_theme = window.as_weak();
    let state_art_theme = Rc::clone(state);
    let engine_art_theme = Arc::clone(engine);
    window.on_toggle_album_art_theme(move || {
        if let Some(win) = weak_art_theme.upgrade() {
            let mut st = state_art_theme.borrow_mut();
            st.prefs.use_album_art_theme = !st.prefs.use_album_art_theme;
            let _ = st.db.save_preferences(&st.prefs);
            sync_theme_and_visuals(&win, &mut st, &engine_art_theme);
        }
    });

    let weak_seed = window.as_weak();
    let state_seed = Rc::clone(state);
    let engine_seed = Arc::clone(engine);
    window.on_select_seed(move |idx| {
        if let (Some(win), Ok(index)) = (weak_seed.upgrade(), usize::try_from(idx))
            && let Some(swatch) = PRESET_SEED_SWATCHES.get(index)
        {
            let mut st = state_seed.borrow_mut();
            st.selected_swatch_idx = index;
            st.manual_seed_argb = swatch.argb;
            sync_theme_and_visuals(&win, &mut st, &engine_seed);
            win.set_status_banner(SharedString::from(format!(
                "Seed switched to {} ({}).",
                swatch.name,
                format_rgb_hex(swatch.argb)
            )));
        }
    });

    let weak_sw = window.as_weak();
    let state_sw = Rc::clone(state);
    window.on_toggle_software_renderer(move || {
        if let Some(win) = weak_sw.upgrade() {
            let mut st = state_sw.borrow_mut();
            st.prefs.use_software_renderer = !st.prefs.use_software_renderer;
            let _ = st.db.save_preferences(&st.prefs);
            sync_settings_views(&win, &st);
            win.set_status_banner(SharedString::from(
                "Renderer preference saved. Restart Lunaria to apply GPU/0-VRAM Software mode.",
            ));
        }
    });
}

fn toggle_track_favorite(
    win: &MainWindow,
    st: &mut AppState,
    track: &Track,
    engine: &PlaybackEngine,
) {
    let next_fav = !track.is_favorite;
    if st.db.set_favorite(track.id, next_fav).is_ok() {
        st.queue.set_favorite_by_id(track.id, next_fav);
        hydrate_from_db(st);
        sync_home_views(win, st);
        sync_library_views(win, st);
        sync_search_views(win, st);
        sync_playback_telemetry(win, st, engine);
    }
}

fn bind_library_and_search(
    window: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
    timer: &Rc<Timer>,
) {
    // Re-use `spawn_library_scan_with_refresh` cleanly so startup scan also refreshes UI!
    let weak_rescan = window.as_weak();
    let state_rescan = Rc::clone(state);
    let engine_rescan = Arc::clone(engine);
    window.on_rescan_library(move || {
        if let Some(win) = weak_rescan.upgrade() {
            spawn_library_scan_with_refresh(&win, &state_rescan, &engine_rescan);
        }
    });

    let weak_resume = window.as_weak();
    let state_resume = Rc::clone(state);
    let engine_resume = Arc::clone(engine);
    let timer_resume = Rc::clone(timer);
    window.on_resume_session(move || {
        if let Some(win) = weak_resume.upgrade() {
            let pos = {
                let mut st = state_resume.borrow_mut();
                std::mem::take(&mut st.restored_position_ms)
            };
            start_playing_queue_current(&win, &state_resume, &engine_resume, &timer_resume);
            if pos > 0 {
                let _ = engine_resume.seek_ms(pos);
            }
        }
    });

    let weak_sp = window.as_weak();
    let state_sp = Rc::clone(state);
    let engine_sp = Arc::clone(engine);
    window.on_open_smart_playlist(move |kind_idx| {
        if let (Some(win), Ok(idx)) = (weak_sp.upgrade(), usize::try_from(kind_idx))
            && let Some(&kind) = SmartPlaylistType::ALL.get(idx)
        {
            let mut st = state_sp.borrow_mut();
            st.drilldown = Some(LibraryDrilldown::SmartPlaylist(kind));
            st.library_subtab = 0;
            sync_library_views(&win, &mut st);
            sync_playback_telemetry(&win, &mut st, &engine_sp);
            win.set_active_nav(1);
        }
    });

    // Home Spotlight Row Actions
    bind_row_actions_for_source(window, state, engine, timer, RowSource::HomeSpotlight);
    bind_row_actions_for_source(window, state, engine, timer, RowSource::Library);
    bind_row_actions_for_source(window, state, engine, timer, RowSource::Search);
    bind_row_actions_for_source(window, state, engine, timer, RowSource::Stats);

    let weak_sub = window.as_weak();
    let state_sub = Rc::clone(state);
    window.on_select_library_subtab(move |tab| {
        if let (Some(win), Ok(idx)) = (weak_sub.upgrade(), usize::try_from(tab)) {
            let mut st = state_sub.borrow_mut();
            st.library_subtab = idx;
            st.drilldown = None;
            hydrate_from_db(&mut st);
            sync_library_views(&win, &mut st);
        }
    });

    let weak_sort = window.as_weak();
    let state_sort = Rc::clone(state);
    window.on_cycle_sort_order(move || {
        if let Some(win) = weak_sort.upgrade() {
            let mut st = state_sort.borrow_mut();
            st.sort_order = match st.sort_order {
                TrackSortOrder::TitleAsc => TrackSortOrder::ArtistAsc,
                TrackSortOrder::ArtistAsc => TrackSortOrder::AlbumAsc,
                TrackSortOrder::AlbumAsc => TrackSortOrder::DateAddedDesc,
                TrackSortOrder::DateAddedDesc => TrackSortOrder::DurationDesc,
                TrackSortOrder::DurationDesc => TrackSortOrder::TitleAsc,
            };
            hydrate_from_db(&mut st);
            sync_library_views(&win, &mut st);
        }
    });

    let weak_exit = window.as_weak();
    let state_exit = Rc::clone(state);
    window.on_exit_library_drilldown(move || {
        if let Some(win) = weak_exit.upgrade() {
            let mut st = state_exit.borrow_mut();
            st.drilldown = None;
            hydrate_from_db(&mut st);
            sync_library_views(&win, &mut st);
        }
    });

    let weak_grp = window.as_weak();
    let state_grp = Rc::clone(state);
    window.on_open_library_group(move |idx| {
        if let (Some(win), Ok(i)) = (weak_grp.upgrade(), usize::try_from(idx)) {
            let mut st = state_grp.borrow_mut();
            let Some(key) = st.visible_groups.get(i).cloned() else {
                return;
            };
            st.drilldown = Some(match key {
                GroupKey::Album(aid, name) => LibraryDrilldown::Album(aid, name),
                GroupKey::Artist(name) => LibraryDrilldown::Artist(name),
                GroupKey::Folder(path) => LibraryDrilldown::Folder(path),
                GroupKey::Playlist(pid, name) => {
                    st.active_playlist_target = Some(pid);
                    LibraryDrilldown::Playlist(pid, name)
                }
            });
            sync_library_views(&win, &mut st);
        }
    });

    let weak_pall = window.as_weak();
    let state_pall = Rc::clone(state);
    let engine_pall = Arc::clone(engine);
    let timer_pall = Rc::clone(timer);
    window.on_play_all_library(move || {
        if let Some(win) = weak_pall.upgrade() {
            let list = state_pall.borrow().visible_library_tracks.clone();
            if !list.is_empty() {
                state_pall
                    .borrow_mut()
                    .queue
                    .set_shuffle(false, now_epoch_ms() as u64);
                play_track_list_at(&win, &state_pall, &engine_pall, &timer_pall, list, 0);
            }
        }
    });

    let weak_shuf = window.as_weak();
    let state_shuf = Rc::clone(state);
    let engine_shuf = Arc::clone(engine);
    let timer_shuf = Rc::clone(timer);
    window.on_shuffle_all_library(move || {
        if let Some(win) = weak_shuf.upgrade() {
            let list = state_shuf.borrow().visible_library_tracks.clone();
            if !list.is_empty() {
                state_shuf
                    .borrow_mut()
                    .queue
                    .set_shuffle(true, now_epoch_ms() as u64);
                play_track_list_at(&win, &state_shuf, &engine_shuf, &timer_shuf, list, 0);
            }
        }
    });

    // Playlist CRUD + .m3u8 import / export
    let weak_pl_c = window.as_weak();
    let state_pl_c = Rc::clone(state);
    window.on_create_playlist(move |raw_name| {
        if let Some(win) = weak_pl_c.upgrade() {
            let clean = raw_name.trim();
            if clean.is_empty() {
                return;
            }
            let mut st = state_pl_c.borrow_mut();
            match st.db.create_playlist(clean, now_epoch_seconds()) {
                Ok(pid) => {
                    st.active_playlist_target = Some(pid);
                    hydrate_from_db(&mut st);
                    sync_library_views(&win, &mut st);
                    win.set_status_banner(SharedString::from(format!(
                        "Created playlist '{clean}'."
                    )));
                }
                Err(err) => {
                    win.set_status_banner(SharedString::from(format!("Playlist error: {err}")));
                }
            }
        }
    });

    let weak_pl_del = window.as_weak();
    let state_pl_del = Rc::clone(state);
    window.on_delete_current_playlist(move || {
        if let Some(win) = weak_pl_del.upgrade() {
            let mut st = state_pl_del.borrow_mut();
            if let Some(LibraryDrilldown::Playlist(pid, name)) = st.drilldown.clone() {
                let _ = st.db.delete_playlist(pid);
                st.drilldown = None;
                hydrate_from_db(&mut st);
                sync_library_views(&win, &mut st);
                win.set_status_banner(SharedString::from(format!("Deleted playlist '{name}'.")));
            }
        }
    });

    let weak_pl_exp = window.as_weak();
    let state_pl_exp = Rc::clone(state);
    window.on_export_current_playlist(move || {
        if weak_pl_exp.upgrade().is_some() {
            let (name, tracks) = {
                let st = state_pl_exp.borrow();
                let name = match &st.drilldown {
                    Some(LibraryDrilldown::Playlist(_, n)) => n.clone(),
                    _ => "playlist".to_owned(),
                };
                (name, st.visible_library_tracks.clone())
            };
            let m3u_text = export_m3u(&tracks);
            let weak_cb = weak_pl_exp.clone();
            let _h = std::thread::spawn(move || {
                let Some(dest) = rfd::FileDialog::new()
                    .set_file_name(format!("{name}.m3u8"))
                    .add_filter("M3U8 Playlist", &["m3u8", "m3u"])
                    .save_file()
                else {
                    return;
                };
                let write_res = std::fs::write(&dest, m3u_text);
                let _ = slint::invoke_from_event_loop(move || {
                    if let Some(ui) = weak_cb.upgrade() {
                        match write_res {
                            Ok(()) => ui.set_status_banner(SharedString::from(format!(
                                "Exported {} track(s) to {}",
                                tracks.len(),
                                dest.display()
                            ))),
                            Err(e) => ui.set_status_banner(SharedString::from(format!(
                                "Export failed: {e}"
                            ))),
                        }
                    }
                });
            });
        }
    });

    let weak_pl_imp = window.as_weak();
    let state_pl_imp = Rc::clone(state);
    window.on_import_m3u_playlist(move || {
        let Some(win) = weak_pl_imp.upgrade() else {
            return;
        };
        let (db_path, library_snapshot, ui_tasks) = {
            let st = state_pl_imp.borrow();
            (
                st.storage_paths.db_path.clone(),
                st.all_tracks.clone(),
                Arc::clone(&st.ui_tasks),
            )
        };
        let weak_cb = win.as_weak();
        let _h = std::thread::spawn(move || {
            let outcome = (|| -> anyhow::Result<(String, usize)> {
                let Some(src) = rfd::FileDialog::new()
                    .add_filter("Playlists", &["m3u8", "m3u"])
                    .pick_file()
                else {
                    return Ok((String::new(), 0));
                };
                let content = std::fs::read_to_string(&src)?;
                let entries = parse_m3u(&content);
                let matched = match_m3u_tracks(&entries, &library_snapshot);
                let pl_name = src
                    .file_stem()
                    .and_then(|s| s.to_str())
                    .unwrap_or("Imported Playlist")
                    .to_owned();
                let mut bg_db = LibraryDb::open(&db_path)?;
                let pid = bg_db.create_playlist(&pl_name, now_epoch_seconds())?;
                let ids: Vec<TrackId> = matched.iter().map(|t| t.id).collect();
                bg_db.add_tracks_to_playlist(pid, &ids)?;
                Ok((pl_name, ids.len()))
            })();
            let err_msg = outcome.as_ref().err().map(ToString::to_string);
            let ok_pair = outcome.ok();
            enqueue_ui_task(
                &ui_tasks,
                &weak_cb,
                move |ui, state_cb, engine_cb, _timer| {
                    if let Some((name, count)) = ok_pair {
                        if !name.is_empty() {
                            let mut st = state_cb.borrow_mut();
                            hydrate_from_db(&mut st);
                            sync_all_views(ui, &mut st, engine_cb);
                            ui.set_status_banner(SharedString::from(format!(
                                "Imported playlist '{name}' ({count} matched tracks)."
                            )));
                        }
                    } else if let Some(msg) = err_msg {
                        ui.set_status_banner(SharedString::from(format!("Import failed: {msg}")));
                    }
                },
            );
        });
    });

    // FTS5 Instant Search
    let weak_sq = window.as_weak();
    let state_sq = Rc::clone(state);
    window.on_search_query_changed(move |query| {
        if let Some(win) = weak_sq.upgrade() {
            let mut st = state_sq.borrow_mut();
            st.search_criteria.query = query.to_string();
            st.visible_search_tracks = st
                .db
                .search_tracks(&st.search_criteria, 400)
                .unwrap_or_default();
            sync_search_views(&win, &st);
        }
    });

    let weak_sc = window.as_weak();
    let state_sc = Rc::clone(state);
    window.on_toggle_search_chip(move |chip_idx| {
        if let Some(win) = weak_sc.upgrade() {
            let chip = match chip_idx {
                0 => SearchFilterChip::HiRes,
                1 => SearchFilterChip::Lossless,
                2 => SearchFilterChip::Flac,
                _ => SearchFilterChip::Favorites,
            };
            let mut st = state_sc.borrow_mut();
            if let Some(pos) = st.search_criteria.filters.iter().position(|&c| c == chip) {
                let _ = st.search_criteria.filters.remove(pos);
            } else {
                st.search_criteria.filters.push(chip);
            }
            st.visible_search_tracks = st
                .db
                .search_tracks(&st.search_criteria, 400)
                .unwrap_or_default();
            sync_search_views(&win, &st);
        }
    });
}

#[derive(Clone, Copy)]
enum RowSource {
    HomeSpotlight,
    Library,
    Search,
    Stats,
}

fn pick_source_list(st: &AppState, source: RowSource) -> Vec<Track> {
    match source {
        RowSource::HomeSpotlight => st.home_spotlight_tracks.clone(),
        RowSource::Library => st.visible_library_tracks.clone(),
        RowSource::Search => st.visible_search_tracks.clone(),
        RowSource::Stats => st.stats_top_tracks.clone(),
    }
}

fn bind_row_actions_for_source(
    window: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
    timer: &Rc<Timer>,
    source: RowSource,
) {
    let play_cb = {
        let w = window.as_weak();
        let s = Rc::clone(state);
        let e = Arc::clone(engine);
        let tm = Rc::clone(timer);
        move |idx: i32| {
            if let (Some(win), Ok(i)) = (w.upgrade(), usize::try_from(idx)) {
                let list = pick_source_list(&s.borrow(), source);
                if i < list.len() {
                    play_track_list_at(&win, &s, &e, &tm, list, i);
                }
            }
        }
    };

    let fav_cb = {
        let w = window.as_weak();
        let s = Rc::clone(state);
        let e = Arc::clone(engine);
        move |idx: i32| {
            if let (Some(win), Ok(i)) = (w.upgrade(), usize::try_from(idx)) {
                let mut st = s.borrow_mut();
                if let Some(t) = pick_source_list(&st, source).get(i).cloned() {
                    toggle_track_favorite(&win, &mut st, &t, &e);
                }
            }
        }
    };

    let q_cb = {
        let w = window.as_weak();
        let s = Rc::clone(state);
        let e = Arc::clone(engine);
        move |idx: i32| {
            if let (Some(win), Ok(i)) = (w.upgrade(), usize::try_from(idx)) {
                let mut st = s.borrow_mut();
                if let Some(t) = pick_source_list(&st, source).get(i).cloned() {
                    let title = t.title.clone();
                    st.queue.enqueue_next(t, now_epoch_ms() as u64);
                    let next_req = st.queue.peek_next_track().map(track_to_playback_request);
                    let _ = e.queue_next(next_req);
                    sync_queue_and_lyrics_views(&win, &mut st);
                    win.set_status_banner(SharedString::from(format!("Queued next: '{title}'.")));
                }
            }
        }
    };

    let pl_cb = {
        let w = window.as_weak();
        let s = Rc::clone(state);
        move |idx: i32| {
            if let (Some(win), Ok(i)) = (w.upgrade(), usize::try_from(idx)) {
                let mut st = s.borrow_mut();
                let Some(track) = pick_source_list(&st, source).get(i).cloned() else {
                    return;
                };
                let Some(pid) = st.active_playlist_target else {
                    return;
                };
                let pl_name = st
                    .playlists
                    .iter()
                    .find(|p| p.id == pid)
                    .map_or_else(|| "Playlist".to_owned(), |p| p.name.clone());
                if st.db.add_tracks_to_playlist(pid, &[track.id]).is_ok() {
                    hydrate_from_db(&mut st);
                    sync_library_views(&win, &mut st);
                    win.set_status_banner(SharedString::from(format!(
                        "Added '{}' to playlist '{pl_name}'.",
                        track.title
                    )));
                }
            }
        }
    };

    let tag_cb = {
        let w = window.as_weak();
        let s = Rc::clone(state);
        move |idx: i32| {
            if let (Some(win), Ok(i)) = (w.upgrade(), usize::try_from(idx)) {
                let mut st = s.borrow_mut();
                if let Some(track) = pick_source_list(&st, source).get(i).cloned() {
                    open_tag_editor_for_track(&win, &mut st, track);
                }
            }
        }
    };

    match source {
        RowSource::HomeSpotlight => {
            window.on_play_spotlight_track(play_cb);
            window.on_favorite_spotlight_track(fav_cb);
            window.on_queue_spotlight_track(q_cb);
            window.on_edit_spotlight_tags(tag_cb);
        }
        RowSource::Library => {
            window.on_play_library_track(play_cb);
            window.on_favorite_library_track(fav_cb);
            window.on_queue_library_track(q_cb);
            window.on_add_library_track_to_playlist(pl_cb);
            window.on_edit_library_track_tags(tag_cb);
        }
        RowSource::Search => {
            window.on_play_search_track(play_cb);
            window.on_favorite_search_track(fav_cb);
            window.on_queue_search_track(q_cb);
            window.on_add_search_track_to_playlist(pl_cb);
            window.on_edit_search_track_tags(tag_cb);
        }
        RowSource::Stats => {
            window.on_play_stat_track(play_cb);
            window.on_favorite_stat_track(fav_cb);
            window.on_queue_stat_track(q_cb);
        }
    }
}

fn apply_eq_change(
    win: &MainWindow,
    st: &mut AppState,
    dsp_store: &DspConfigStore,
    engine: &PlaybackEngine,
) {
    dsp_store.update_equalizer(|_| st.prefs.equalizer.clone());
    let _ = engine.refresh_signal_path();
    let _ = st.db.save_preferences(&st.prefs);
    sync_eq_views(win, st, engine);
    sync_playback_telemetry(win, st, engine);
}

fn bind_equalizer_callbacks(
    window: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    dsp_store: &Arc<DspConfigStore>,
    engine: &Arc<PlaybackEngine>,
) {
    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let dsp_c = Arc::clone(dsp_store);
    let eng_c = Arc::clone(engine);
    window.on_eq_toggle_enabled(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            st.prefs.equalizer.enabled = !st.prefs.equalizer.enabled;
            apply_eq_change(&win, &mut st, &dsp_c, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let dsp_c = Arc::clone(dsp_store);
    let eng_c = Arc::clone(engine);
    window.on_eq_reset_flat(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            st.prefs.equalizer.enabled = false;
            st.prefs.equalizer.preamp_db = 0.0;
            st.prefs.equalizer.band_gains_db = [0.0; 10];
            st.prefs.equalizer.parametric_filters.clear();
            st.prefs.equalizer.active_preset_name = "Flat".to_owned();
            apply_eq_change(&win, &mut st, &dsp_c, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let dsp_c = Arc::clone(dsp_store);
    let eng_c = Arc::clone(engine);
    window.on_eq_select_preset(move |idx| {
        if let (Some(win), Ok(i)) = (weak.upgrade(), usize::try_from(idx))
            && let Some(preset) = BUILT_IN_EQ_PRESETS.get(i)
        {
            let mut st = st_c.borrow_mut();
            st.prefs.equalizer.enabled = i != 0;
            st.prefs.equalizer.preamp_db = preset.preamp_db;
            st.prefs.equalizer.band_gains_db = preset.band_gains_db;
            st.prefs.equalizer.parametric_filters.clear();
            st.prefs.equalizer.active_preset_name = preset.name.to_owned();
            apply_eq_change(&win, &mut st, &dsp_c, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let dsp_c = Arc::clone(dsp_store);
    let eng_c = Arc::clone(engine);
    window.on_eq_set_band_gain(move |band_idx, gain_db| {
        if let (Some(win), Ok(i)) = (weak.upgrade(), usize::try_from(band_idx))
            && i < 10
        {
            let mut st = st_c.borrow_mut();
            st.prefs.equalizer.enabled = true;
            st.prefs.equalizer.band_gains_db[i] = (gain_db.clamp(-12.0, 12.0) * 2.0).round() / 2.0;
            st.prefs.equalizer.active_preset_name = "Custom".to_owned();
            apply_eq_change(&win, &mut st, &dsp_c, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let dsp_c = Arc::clone(dsp_store);
    let eng_c = Arc::clone(engine);
    window.on_eq_step_preamp(move |delta| {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            if delta == 0.0 {
                st.prefs.equalizer.preamp_db = 0.0;
            } else {
                st.prefs.equalizer.enabled = true;
                st.prefs.equalizer.preamp_db =
                    (st.prefs.equalizer.preamp_db + delta).clamp(-12.0, 12.0);
            }
            apply_eq_change(&win, &mut st, &dsp_c, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let dsp_c = Arc::clone(dsp_store);
    let eng_c = Arc::clone(engine);
    window.on_eq_cycle_replay_gain(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            st.prefs.equalizer.replay_gain_mode = match st.prefs.equalizer.replay_gain_mode {
                ReplayGainMode::Off => ReplayGainMode::Track,
                ReplayGainMode::Track => ReplayGainMode::Album,
                ReplayGainMode::Album => ReplayGainMode::Off,
            };
            apply_eq_change(&win, &mut st, &dsp_c, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let dsp_c = Arc::clone(dsp_store);
    let eng_c = Arc::clone(engine);
    window.on_eq_step_rg_preamp(move |delta| {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            let next = st.prefs.equalizer.replay_gain_preamp_db + delta;
            st.prefs.equalizer.replay_gain_preamp_db = if next > 6.0 { -6.0 } else { next };
            apply_eq_change(&win, &mut st, &dsp_c, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let dsp_c = Arc::clone(dsp_store);
    let eng_c = Arc::clone(engine);
    window.on_eq_cycle_balance(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            let cur = st.prefs.equalizer.channel_balance;
            st.prefs.equalizer.channel_balance = if cur.abs() < 0.05 {
                -0.35
            } else if cur < -0.05 {
                0.35
            } else {
                0.0
            };
            apply_eq_change(&win, &mut st, &dsp_c, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let dsp_c = Arc::clone(dsp_store);
    let eng_c = Arc::clone(engine);
    window.on_eq_toggle_mono(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            st.prefs.equalizer.mono_audio = !st.prefs.equalizer.mono_audio;
            apply_eq_change(&win, &mut st, &dsp_c, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let dsp_c = Arc::clone(dsp_store);
    let eng_c = Arc::clone(engine);
    window.on_eq_cycle_crossfade(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            let cur = st.prefs.equalizer.crossfade_duration_sec;
            st.prefs.equalizer.crossfade_duration_sec = match cur {
                0 => 2,
                2 => 4,
                4 => 6,
                6 => 10,
                _ => 0,
            };
            apply_eq_change(&win, &mut st, &dsp_c, &eng_c);
        }
    });

    // AutoEQ .txt Importer (ParametricEQ or GraphicEQ)
    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let dsp_c = Arc::clone(dsp_store);
    window.on_eq_import_autoeq(move || {
        let Some(win) = weak.upgrade() else {
            return;
        };
        let ui_tasks = Arc::clone(&st_c.borrow().ui_tasks);
        let weak_cb = win.as_weak();
        let dsp_cb = Arc::clone(&dsp_c);
        let _h = std::thread::spawn(move || {
            let picked = rfd::FileDialog::new()
                .add_filter("AutoEQ Profile", &["txt"])
                .pick_file();
            let res =
                picked.and_then(|p| std::fs::read_to_string(&p).ok().map(|content| (p, content)));
            enqueue_ui_task(&ui_tasks, &weak_cb, move |ui, st_cb, eng_cb, _timer| {
                let Some((path, raw)) = res else {
                    return;
                };
                let label = path
                    .file_name()
                    .and_then(|s| s.to_str())
                    .unwrap_or("AutoEQ");
                let Some(profile) = parse_autoeq(&raw, label) else {
                    ui.set_status_banner(SharedString::from(
                        "Invalid AutoEQ .txt file (expected ParametricEQ or GraphicEQ format).",
                    ));
                    return;
                };
                let mut st = st_cb.borrow_mut();
                st.prefs.equalizer.enabled = true;
                st.prefs.equalizer.preamp_db = profile.preamp_db;
                st.prefs.equalizer.band_gains_db = profile.band_gains_db;
                st.prefs.equalizer.parametric_filters = profile.parametric_filters;
                st.prefs.equalizer.active_preset_name = profile.name.clone();
                apply_eq_change(ui, &mut st, &dsp_cb, eng_cb);
                ui.set_status_banner(SharedString::from(format!(
                    "AutoEQ loaded: {} ({} parametric stages).",
                    profile.name,
                    st.prefs.equalizer.parametric_filters.len()
                )));
            });
        });
    });
}

fn bind_transport_and_queue(
    window: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
    timer: &Rc<Timer>,
) {
    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    let tm_c = Rc::clone(timer);
    window.on_toggle_play_pause(move || {
        let Some(win) = weak.upgrade() else {
            return;
        };
        let tel = eng_c.telemetry();
        if tel.status == PlaybackStatus::Playing {
            let _ = eng_c.pause();
            tm_c.stop();
            let mut st = st_c.borrow_mut();
            sync_playback_telemetry(&win, &mut st, &eng_c);
        } else if tel.active_track.is_some() {
            let _ = eng_c.play();
            {
                let mut st = st_c.borrow_mut();
                sync_playback_telemetry(&win, &mut st, &eng_c);
            }
            ensure_adaptive_timer(&tm_c, &weak, &st_c, &eng_c, true);
        } else {
            start_playing_queue_current(&win, &st_c, &eng_c, &tm_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    let tm_c = Rc::clone(timer);
    window.on_next_track(move || {
        if let Some(win) = weak.upgrade() {
            let next_opt = st_c.borrow_mut().queue.advance_next(false).cloned();
            if next_opt.is_some() {
                start_playing_queue_current(&win, &st_c, &eng_c, &tm_c);
            }
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    let tm_c = Rc::clone(timer);
    window.on_previous_track(move || {
        if let Some(win) = weak.upgrade() {
            let pos = eng_c.position_ms();
            let step = st_c
                .borrow_mut()
                .queue
                .step_previous(pos)
                .map(|(t, restart)| (t.clone(), restart));
            if let Some((_track, restart_only)) = step {
                if restart_only {
                    let _ = eng_c.seek_ms(0);
                    let mut st = st_c.borrow_mut();
                    sync_playback_telemetry(&win, &mut st, &eng_c);
                } else {
                    start_playing_queue_current(&win, &st_c, &eng_c, &tm_c);
                }
            }
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    let tm_c = Rc::clone(timer);
    window.on_stop_playback(move || {
        if let Some(win) = weak.upgrade() {
            let _ = eng_c.stop();
            tm_c.stop();
            let mut st = st_c.borrow_mut();
            sync_playback_telemetry(&win, &mut st, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_seek_ratio(move |ratio| {
        if let Some(win) = weak.upgrade() {
            let dur_ms = eng_c.telemetry().duration_ms;
            if dur_ms > 0 {
                let target_ms = (f64::from(ratio.clamp(0.0, 1.0)) * (dur_ms as f64)).round() as u64;
                let _ = eng_c.seek_ms(target_ms);
                let mut st = st_c.borrow_mut();
                sync_playback_telemetry(&win, &mut st, &eng_c);
            }
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_seek_lyric_ms(move |ms| {
        if let Some(win) = weak.upgrade()
            && ms >= 0
        {
            let _ = eng_c.seek_ms(ms as u64);
            let mut st = st_c.borrow_mut();
            sync_playback_telemetry(&win, &mut st, &eng_c);
        }
    });

    fn apply_volume_and_sync(
        win: &MainWindow,
        state: &Rc<RefCell<AppState>>,
        engine: &PlaybackEngine,
        vol: f32,
    ) {
        let clamped = vol.clamp(0.0, 1.0);
        let _ = engine.set_volume(clamped);
        let mut st = state.borrow_mut();
        st.muted = clamped <= 0.001;
        if !st.muted {
            st.pre_mute_volume = clamped;
        }
        sync_playback_telemetry(win, &mut st, engine);
    }

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_seek_relative_ms(move |delta_ms| {
        if let Some(win) = weak.upgrade() {
            let tel = eng_c.telemetry();
            if tel.duration_ms > 0 {
                let max_ms = i64::try_from(tel.duration_ms).unwrap_or(i64::MAX);
                let target_i64 = i64::try_from(tel.position_ms)
                    .unwrap_or(0)
                    .saturating_add(i64::from(delta_ms))
                    .clamp(0, max_ms);
                let target = u64::try_from(target_i64).unwrap_or(0);
                let _ = eng_c.seek_ms(target);
                let mut st = st_c.borrow_mut();
                sync_playback_telemetry(&win, &mut st, &eng_c);
            }
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_set_volume(move |vol| {
        if let Some(win) = weak.upgrade() {
            apply_volume_and_sync(&win, &st_c, &eng_c, vol);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_step_volume(move |delta| {
        if let Some(win) = weak.upgrade() {
            let cur = eng_c.telemetry().volume;
            apply_volume_and_sync(&win, &st_c, &eng_c, cur + delta);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_toggle_mute(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            let cur_vol = eng_c.telemetry().volume;
            if st.muted || cur_vol <= 0.001 {
                st.muted = false;
                let restore = st.pre_mute_volume.clamp(0.15, 1.0);
                let _ = eng_c.set_volume(restore);
            } else {
                st.muted = true;
                st.pre_mute_volume = cur_vol;
                let _ = eng_c.set_volume(0.0);
            }
            sync_playback_telemetry(&win, &mut st, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_toggle_current_favorite(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            if let Some(t) = st.queue.current_track().cloned() {
                toggle_track_favorite(&win, &mut st, &t, &eng_c);
            }
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_cycle_repeat(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            let _ = st.queue.cycle_repeat();
            let next_req = st.queue.peek_next_track().map(track_to_playback_request);
            let _ = eng_c.queue_next(next_req);
            sync_queue_and_lyrics_views(&win, &mut st);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_toggle_shuffle(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            let next_val = !st.queue.shuffle_enabled();
            st.queue.set_shuffle(next_val, now_epoch_ms() as u64);
            let next_req = st.queue.peek_next_track().map(track_to_playback_request);
            let _ = eng_c.queue_next(next_req);
            sync_queue_and_lyrics_views(&win, &mut st);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_cycle_speed(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            st.speed_cycle_idx = (st.speed_cycle_idx + 1) % SPEED_CYCLE.len();
            let speed = SPEED_CYCLE[st.speed_cycle_idx];
            let _ = eng_c.set_speed(speed);
            sync_queue_and_lyrics_views(&win, &mut st);
            sync_playback_telemetry(&win, &mut st, &eng_c);
            win.set_status_banner(SharedString::from(format!(
                "Playback speed: {speed:.2}x (WSOLA pitch-preserving)."
            )));
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    window.on_cycle_sleep_timer(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            st.sleep_cycle_idx = (st.sleep_cycle_idx + 1) % SLEEP_PRESETS_MIN.len();
            st.sleep_timer = match SLEEP_PRESETS_MIN[st.sleep_cycle_idx] {
                None => SleepTimerState::Off,
                Some(0) => SleepTimerState::EndOfTrack,
                Some(mins) => SleepTimerState::Countdown {
                    remaining_ms: mins * 60_000,
                },
            };
            sync_queue_and_lyrics_views(&win, &mut st);
        }
    });

    // Queue list interactions
    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    let tm_c = Rc::clone(timer);
    window.on_play_queue_index(move |idx| {
        if let (Some(win), Ok(i)) = (weak.upgrade(), usize::try_from(idx)) {
            let jumped = st_c.borrow_mut().queue.jump_to_index(i).is_some();
            if jumped {
                start_playing_queue_current(&win, &st_c, &eng_c, &tm_c);
            }
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_move_queue_item(move |from, to| {
        if let (Some(win), Ok(f), Ok(t)) =
            (weak.upgrade(), usize::try_from(from), usize::try_from(to))
        {
            let mut st = st_c.borrow_mut();
            if st.queue.move_item(f, t, now_epoch_ms() as u64) {
                let next_req = st.queue.peek_next_track().map(track_to_playback_request);
                let _ = eng_c.queue_next(next_req);
                sync_queue_and_lyrics_views(&win, &mut st);
            }
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    let tm_c = Rc::clone(timer);
    window.on_remove_queue_item(move |idx| {
        if let (Some(win), Ok(i)) = (weak.upgrade(), usize::try_from(idx)) {
            let outcome = st_c
                .borrow_mut()
                .queue
                .remove_item(i, now_epoch_ms() as u64);
            if let Some(removed_active) = outcome {
                if removed_active {
                    let has_cur = st_c.borrow().queue.current_track().is_some();
                    if has_cur {
                        start_playing_queue_current(&win, &st_c, &eng_c, &tm_c);
                    } else {
                        let _ = eng_c.stop();
                        tm_c.stop();
                    }
                }
                let mut st = st_c.borrow_mut();
                let next_req = st.queue.peek_next_track().map(track_to_playback_request);
                let _ = eng_c.queue_next(next_req);
                sync_queue_and_lyrics_views(&win, &mut st);
                sync_playback_telemetry(&win, &mut st, &eng_c);
            }
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    let tm_c = Rc::clone(timer);
    window.on_clear_queue(move || {
        if let Some(win) = weak.upgrade() {
            let _ = eng_c.stop();
            tm_c.stop();
            let mut st = st_c.borrow_mut();
            st.queue.clear();
            st.last_track_id = None;
            st.current_lyrics = None;
            sync_queue_and_lyrics_views(&win, &mut st);
            sync_playback_telemetry(&win, &mut st, &eng_c);
        }
    });
}

fn bind_settings_and_devices(
    window: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
    _timer: &Rc<Timer>,
) {
    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_select_output_mode(move |idx| {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            st.prefs.audio_output_mode = if idx == 1 {
                AudioOutputMode::PuristDirectBitPerfect
            } else {
                AudioOutputMode::DspFloat32
            };
            let _ = eng_c.set_output_mode(st.prefs.audio_output_mode);
            let _ = st.db.save_preferences(&st.prefs);
            sync_settings_views(&win, &st);
            sync_playback_telemetry(&win, &mut st, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_select_device(move |idx| {
        if let (Some(win), Ok(i)) = (weak.upgrade(), usize::try_from(idx)) {
            let mut st = st_c.borrow_mut();
            if let Some(dev) = st.devices.get(i).cloned() {
                st.selected_device_idx = i;
                st.prefs.preferred_device_id = Some(dev.id.clone());
                let _ = eng_c.set_device(Some(dev.id));
                let _ = st.db.save_preferences(&st.prefs);
                sync_settings_views(&win, &st);
                sync_playback_telemetry(&win, &mut st, &eng_c);
            }
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_refresh_devices(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            reload_devices_into_state(&win, &mut st);
            sync_settings_views(&win, &st);
            sync_playback_telemetry(&win, &mut st, &eng_c);
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    window.on_play_test_chime(move || {
        let Some(win) = weak.upgrade() else {
            return;
        };
        if win.get_chime_busy() {
            return;
        }
        let target_id = {
            let st = st_c.borrow();
            st.devices.get(st.selected_device_idx).map(|d| d.id.clone())
        };
        win.set_chime_busy(true);
        win.set_status_banner(SharedString::from("Playing 360 ms WASAPI test chime..."));

        let weak_done = weak.clone();
        let _h = std::thread::spawn(move || {
            let outcome = play_test_chime(target_id.as_deref());
            let _ = slint::invoke_from_event_loop(move || {
                if let Some(ui) = weak_done.upgrade() {
                    ui.set_chime_busy(false);
                    match outcome {
                        Ok(info) => ui.set_status_banner(SharedString::from(format!(
                            "Chime played cleanly on {} ({} Hz).",
                            info.name, info.default_sample_rate_hz
                        ))),
                        Err(e) => ui.set_status_banner(SharedString::from(format!(
                            "WASAPI chime failed: {e}"
                        ))),
                    }
                }
            });
        });
    });

    // Open Single File outside library
    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    window.on_open_audio_file(move || {
        let Some(win) = weak.upgrade() else {
            return;
        };
        let ui_tasks = Arc::clone(&st_c.borrow().ui_tasks);
        let weak_cb = win.as_weak();
        let _h = std::thread::spawn(move || {
            let picked = rfd::FileDialog::new()
                .add_filter(
                    "Audio Files",
                    &[
                        "flac", "wav", "alac", "mp3", "m4a", "aac", "ogg", "aiff", "aif", "cue",
                    ],
                )
                .pick_file();
            enqueue_ui_task(&ui_tasks, &weak_cb, move |ui, st_cb, eng_cb, tm_cb| {
                if let Some(path) = picked {
                    handle_open_paths(ui, st_cb, eng_cb, tm_cb, &[path]);
                }
            });
        });
    });

    // Add Watched Folder + immediate scan + live watcher update
    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    window.on_add_watched_folder(move || {
        let Some(win) = weak.upgrade() else {
            return;
        };
        let ui_tasks = Arc::clone(&st_c.borrow().ui_tasks);
        let weak_cb = win.as_weak();
        let _h = std::thread::spawn(move || {
            let picked = rfd::FileDialog::new().pick_folder();
            enqueue_ui_task(&ui_tasks, &weak_cb, move |ui, st_cb, eng_cb, _tm| {
                let Some(dir) = picked else {
                    return;
                };
                let folders_copy = {
                    let mut st = st_cb.borrow_mut();
                    let _ = st
                        .db
                        .add_watched_folder(&dir.to_string_lossy(), now_epoch_seconds());
                    hydrate_from_db(&mut st);
                    sync_settings_views(ui, &st);
                    let list = st.watched_folders.clone();
                    restart_folder_watcher(&mut st, ui.as_weak(), &list);
                    list
                };
                if !folders_copy.is_empty() {
                    spawn_library_scan_with_refresh(ui, st_cb, eng_cb);
                }
            });
        });
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_remove_watched_folder(move |idx| {
        if let (Some(win), Ok(i)) = (weak.upgrade(), usize::try_from(idx)) {
            let should_scan = {
                let mut st = st_c.borrow_mut();
                if let Some(folder) = st.watched_folders.get(i).cloned() {
                    let _ = st.db.remove_watched_folder(&folder);
                    hydrate_from_db(&mut st);
                    sync_settings_views(&win, &st);
                    let list = st.watched_folders.clone();
                    restart_folder_watcher(&mut st, weak.clone(), &list);
                    true
                } else {
                    false
                }
            };
            if should_scan {
                spawn_library_scan_with_refresh(&win, &st_c, &eng_c);
            }
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    window.on_cycle_folder_filter_mode(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            st.prefs.folder_filter.mode = match st.prefs.folder_filter.mode {
                FolderFilterMode::Blacklist => {
                    if st.prefs.folder_filter.included_folders.is_empty()
                        && !st.watched_folders.is_empty()
                    {
                        st.prefs.folder_filter.included_folders = st.watched_folders.clone();
                    }
                    FolderFilterMode::Whitelist
                }
                FolderFilterMode::Whitelist => FolderFilterMode::Blacklist,
            };
            let _ = st.db.save_preferences(&st.prefs);
            sync_settings_views(&win, &st);
            win.set_status_banner(SharedString::from(match st.prefs.folder_filter.mode {
                FolderFilterMode::Blacklist => "Folder filter switched to Blacklist mode.",
                FolderFilterMode::Whitelist => "Folder filter switched to Whitelist mode.",
            }));
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    window.on_toggle_minimize_to_tray(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            st.prefs.minimize_to_tray = !st.prefs.minimize_to_tray;
            let _ = st.db.save_preferences(&st.prefs);
            sync_settings_views(&win, &st);
            win.set_status_banner(SharedString::from(if st.prefs.minimize_to_tray {
                "Close button minimizes Lunaria to the Windows System Tray."
            } else {
                "Close button will exit Lunaria completely."
            }));
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_toggle_discord_rpc(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            st.prefs.discord_rpc_enabled = !st.prefs.discord_rpc_enabled;
            let enabled = st.prefs.discord_rpc_enabled;
            let client_id = st.prefs.discord_client_id.clone();
            st.discord_rpc.configure(enabled, client_id);
            st.last_discord_key.clear();
            let _ = st.db.save_preferences(&st.prefs);
            sync_settings_views(&win, &st);
            sync_playback_telemetry(&win, &mut st, &eng_c);
            win.set_status_banner(SharedString::from(if enabled {
                "Discord Rich Presence enabled (local Windows Named Pipe IPC)."
            } else {
                "Discord Rich Presence disabled."
            }));
        }
    });
}

fn open_tag_editor_for_track(win: &MainWindow, st: &mut AppState, track: Track) {
    win.set_tag_edit_title(SharedString::from(track.title.as_str()));
    win.set_tag_edit_artist(SharedString::from(track.artist.as_str()));
    win.set_tag_edit_album(SharedString::from(track.album.as_str()));
    win.set_tag_edit_album_artist(SharedString::from(track.album_artist.as_str()));
    win.set_tag_edit_track_no(SharedString::from(track.track_number.to_string()));
    win.set_tag_edit_disc_no(SharedString::from(track.disc_number.to_string()));
    win.set_tag_edit_year(SharedString::from(if track.year > 0 {
        track.year.to_string()
    } else {
        String::new()
    }));
    win.set_tag_edit_genre(SharedString::from(track.genre.as_str()));
    win.set_tag_edit_file_path(SharedString::from(track.file_path.as_str()));
    win.set_tag_edit_cover_status(SharedString::from(if track.artwork_uri.is_some() {
        "Keep existing cover"
    } else {
        "No cover embedded"
    }));
    win.set_tag_edit_is_cue(track.is_cue_track());
    win.set_tag_edit_busy(false);
    win.set_tag_edit_error(SharedString::default());
    st.tag_editor_target = Some(track);
    st.tag_editor_cover = CoverArtEdit::KeepExisting;
    win.set_tag_editor_open(true);
}

fn bind_tag_editor_callbacks(
    window: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
) {
    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    window.on_open_current_tag_editor(move || {
        if let Some(win) = weak.upgrade() {
            let mut st = st_c.borrow_mut();
            if let Some(track) = st.queue.current_track().cloned() {
                open_tag_editor_for_track(&win, &mut st, track);
            }
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    window.on_tag_editor_cancel(move || {
        if let Some(win) = weak.upgrade() {
            win.set_tag_editor_open(false);
            let mut st = st_c.borrow_mut();
            st.tag_editor_target = None;
            st.tag_editor_cover = CoverArtEdit::KeepExisting;
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    window.on_tag_editor_remove_cover(move || {
        if let Some(win) = weak.upgrade() {
            st_c.borrow_mut().tag_editor_cover = CoverArtEdit::Remove;
            win.set_tag_edit_cover_status(SharedString::from("Will remove embedded cover"));
        }
    });

    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    window.on_tag_editor_pick_cover(move || {
        let Some(win) = weak.upgrade() else {
            return;
        };
        let ui_tasks = Arc::clone(&st_c.borrow().ui_tasks);
        let weak_cb = win.as_weak();
        let _h = std::thread::spawn(move || {
            let picked = rfd::FileDialog::new()
                .add_filter("Cover Images", &["jpg", "jpeg", "png", "webp", "bmp"])
                .pick_file();
            let loaded = picked.and_then(|p| {
                let label = p
                    .file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or("selected image")
                    .to_owned();
                std::fs::read(&p).ok().map(|bytes| (label, bytes))
            });
            enqueue_ui_task(&ui_tasks, &weak_cb, move |ui, st_cb, _eng, _tm| {
                if let Some((label, bytes)) = loaded {
                    st_cb.borrow_mut().tag_editor_cover = CoverArtEdit::Replace(bytes);
                    ui.set_tag_edit_cover_status(SharedString::from(format!("Pending: {label}")));
                }
            });
        });
    });

    // Save tags via lunaria-tags (Windows File-Lock Handover: suspend -> atomic write -> resume -> FTS5)
    let weak = window.as_weak();
    let st_c = Rc::clone(state);
    let eng_c = Arc::clone(engine);
    window.on_tag_editor_save(move || {
        let Some(win) = weak.upgrade() else {
            return;
        };
        let (target_track, edit_req, db_path, art_cache, ui_tasks) = {
            let st = st_c.borrow();
            let Some(target) = st.tag_editor_target.clone() else {
                return;
            };
            let req = TagEditRequest {
                title: win.get_tag_edit_title().to_string(),
                artist: win.get_tag_edit_artist().to_string(),
                album: win.get_tag_edit_album().to_string(),
                album_artist: win.get_tag_edit_album_artist().to_string(),
                track_number: win.get_tag_edit_track_no().trim().parse::<u32>().ok(),
                disc_number: win.get_tag_edit_disc_no().trim().parse::<u32>().ok(),
                year: win.get_tag_edit_year().trim().parse::<u32>().ok(),
                genre: win.get_tag_edit_genre().to_string(),
                cover_art: st.tag_editor_cover.clone(),
            };
            (
                target,
                req,
                st.storage_paths.db_path.clone(),
                st.storage_paths.artwork_cache_dir.clone(),
                Arc::clone(&st.ui_tasks),
            )
        };

        win.set_tag_edit_busy(true);
        win.set_tag_edit_error(SharedString::default());
        let eng_bg = Arc::clone(&eng_c);
        let track_id = target_track.id;
        let weak_cb = win.as_weak();

        let _h = std::thread::Builder::new()
            .name("lunaria-tag-writer".to_owned())
            .spawn(move || {
                let res = LibraryDb::open(&db_path)
                    .map_err(anyhow::Error::from)
                    .and_then(|bg_db| {
                        let outcome = save_track_tags(
                            &bg_db,
                            Some(&eng_bg),
                            track_id,
                            &edit_req,
                            Some(&art_cache),
                        )?;
                        let updated_track = bg_db.get_track(track_id)?;
                        Ok((outcome, updated_track))
                    });
                let err_msg = res.as_ref().err().map(ToString::to_string);
                let ok_val = res.ok();
                enqueue_ui_task(&ui_tasks, &weak_cb, move |ui, st_cb, eng_cb, _tm| {
                    ui.set_tag_edit_busy(false);
                    if let Some((outcome, updated_opt)) = ok_val {
                        let mut st = st_cb.borrow_mut();
                        st.artwork_revision = st.artwork_revision.wrapping_add(1);
                        if let Some(updated) = &updated_opt {
                            let _ = st.queue.update_track_in_place(updated);
                        }
                        st.tag_editor_target = None;
                        st.tag_editor_cover = CoverArtEdit::KeepExisting;
                        hydrate_from_db(&mut st);
                        sync_theme_and_visuals(ui, &mut st, eng_cb);
                        sync_all_views(ui, &mut st, eng_cb);
                        ui.set_tag_editor_open(false);
                        ui.set_status_banner(SharedString::from(if outcome.handed_over_playback {
                            "Tags saved cleanly (Windows File-Lock Handover resumed playback)."
                        } else {
                            "Tags & cover saved to file and indexed for Instant Search."
                        }));
                    } else if let Some(msg) = err_msg {
                        ui.set_tag_edit_error(SharedString::from(msg));
                    }
                });
            });
    });
}

fn sync_windows_shell_state(
    st: &mut AppState,
    has_track: bool,
    playing: bool,
    position_ms: u64,
    duration_ms: u64,
) {
    let (title, artist, album, art_path) = if let Some(t) = st.queue.current_track() {
        (
            t.title.clone(),
            t.artist.clone(),
            t.album.clone(),
            t.artwork_uri.as_deref().map(PathBuf::from),
        )
    } else if has_track {
        (
            "Local Audio File".to_owned(),
            String::new(),
            String::new(),
            None,
        )
    } else {
        ("Lunaria".to_owned(), String::new(), String::new(), None)
    };

    let meta_key = format!(
        "{}:{}:{}:{}:{}:{}",
        has_track,
        playing,
        title,
        artist,
        art_path.as_ref().map_or("", |p| p.to_str().unwrap_or("")),
        st.artwork_revision
    );

    let meta_changed = meta_key != st.last_shell_meta_key;
    if meta_changed {
        st.last_shell_meta_key = meta_key;
        if let Some(host) = &st.shell_host {
            host.update_metadata(MediaMetadataSnapshot {
                title,
                artist,
                album,
                artwork_path: art_path,
                is_playing: playing,
                has_track,
                position_ms,
                duration_ms,
            });
        }
    }

    let progress_step_due =
        meta_changed || !playing || position_ms.abs_diff(st.last_taskbar_progress_ms) >= 500;
    if progress_step_due {
        st.last_taskbar_progress_ms = position_ms;
        if let Some(hook) = st.window_hook.as_mut() {
            hook.update_playback(playing, has_track, position_ms, duration_ms);
        }
    }
}

fn sync_discord_rpc_state(
    st: &mut AppState,
    has_track: bool,
    playing: bool,
    position_ms: u64,
    duration_ms: u64,
    speed: f32,
) {
    if !st.prefs.discord_rpc_enabled {
        st.last_discord_key.clear();
        st.last_discord_sync_at = None;
        return;
    }

    let (title, artist, album, quality_badge) = if let Some(track) = st.queue.current_track() {
        (
            track.title.clone(),
            track.artist.clone(),
            track.album.clone(),
            track.audio_spec.format_discord_quality(),
        )
    } else if has_track {
        (
            "Local Audio File".to_owned(),
            String::new(),
            String::new(),
            "Local PCM".to_owned(),
        )
    } else {
        (
            "Lunaria".to_owned(),
            String::new(),
            String::new(),
            String::new(),
        )
    };

    let output_mode_label = st.prefs.audio_output_mode.label().to_owned();
    let speed_bp = ((speed.clamp(0.5, 2.0) * 100.0).round() as u32).max(50);
    let effective_pos_ms = if speed_bp != 100 {
        position_ms.saturating_mul(100) / u64::from(speed_bp)
    } else {
        position_ms
    };
    let effective_dur_ms = if duration_ms > 0 && speed_bp != 100 {
        duration_ms.saturating_mul(100) / u64::from(speed_bp)
    } else {
        duration_ms
    };

    let state_key = format!(
        "{has_track}|{playing}|{title}|{artist}|{album}|{quality_badge}|{output_mode_label}|{speed_bp}"
    );
    let now = Instant::now();
    let key_changed = state_key != st.last_discord_key;
    let expected_elapsed_ms = st.last_discord_sync_at.map_or(0, |t| {
        u64::try_from(now.saturating_duration_since(t).as_millis()).unwrap_or(u64::MAX)
    });
    let expected_pos_ms = st
        .last_discord_position_ms
        .saturating_add(expected_elapsed_ms);
    let seek_drifted = playing && effective_pos_ms.abs_diff(expected_pos_ms) > 2_000;

    if !key_changed && !seek_drifted {
        return;
    }

    st.last_discord_key = state_key;
    st.last_discord_position_ms = effective_pos_ms;
    st.last_discord_sync_at = Some(now);
    st.discord_rpc.update_activity(DiscordActivitySnapshot {
        has_track,
        is_playing: playing,
        title,
        artist,
        album,
        quality_badge,
        output_mode_label,
        position_ms: effective_pos_ms,
        duration_ms: effective_dur_ms,
    });
}

fn execute_window_close(
    win: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
    timer: &Rc<Timer>,
    hide_explicitly: bool,
) -> CloseRequestResponse {
    let should_hide = {
        let mut st = state.borrow_mut();
        if st.prefs.minimize_to_tray && !st.quit_requested {
            st.window_visible = false;
            true
        } else {
            st.quit_requested = true;
            false
        }
    };
    if should_hide {
        win.global::<MarqueeClock>().set_active(false);
        win.set_status_banner(SharedString::from("Minimized to system tray."));
        if hide_explicitly {
            let _ = win.hide();
        }
        ensure_adaptive_timer(timer, &win.as_weak(), state, engine, false);
    } else {
        timer.stop();
        let _ = slint::quit_event_loop();
    }
    CloseRequestResponse::HideWindow
}

fn bind_close_to_tray_and_shell(
    window: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
    timer: &Rc<Timer>,
    shell_rx: Receiver<ShellCommand>,
) {
    let weak_drag = window.as_weak();
    let st_drag = Rc::clone(state);
    let eng_drag = Arc::clone(engine);
    window.on_start_window_drag(move || {
        let hwnd = if let Some(win) = weak_drag.upgrade() {
            let mut st = st_drag.borrow_mut();
            ensure_native_window_attached(&win, &mut st, &eng_drag)
        } else {
            st_drag.borrow().raw_hwnd
        };
        if let Some(h) = hwnd {
            start_window_drag(h);
        }
    });

    let weak_min = window.as_weak();
    let st_min = Rc::clone(state);
    let eng_min = Arc::clone(engine);
    window.on_minimize_window(move || {
        if let Some(win) = weak_min.upgrade() {
            let hwnd = {
                let mut st = st_min.borrow_mut();
                ensure_native_window_attached(&win, &mut st, &eng_min)
            };
            if let Some(h) = hwnd {
                minimize_window(h);
            } else {
                win.window().set_minimized(true);
            }
        } else if let Some(h) = st_min.borrow().raw_hwnd {
            minimize_window(h);
        }
    });

    let weak_max = window.as_weak();
    let st_max = Rc::clone(state);
    let eng_max = Arc::clone(engine);
    window.on_toggle_maximize_window(move || {
        if let Some(win) = weak_max.upgrade() {
            let hwnd = {
                let mut st = st_max.borrow_mut();
                ensure_native_window_attached(&win, &mut st, &eng_max)
            };
            if let Some(h) = hwnd {
                let now_max = toggle_maximize_window(h);
                win.set_is_maximized(now_max);
            } else {
                let next = !win.window().is_maximized();
                win.window().set_maximized(next);
                win.set_is_maximized(win.window().is_maximized());
            }
        } else if let Some(h) = st_max.borrow().raw_hwnd {
            let _ = toggle_maximize_window(h);
        }
    });

    let weak_close_btn = window.as_weak();
    let st_close_btn = Rc::clone(state);
    let eng_close_btn = Arc::clone(engine);
    let tm_close_btn = Rc::clone(timer);
    window.on_close_window(move || {
        if let Some(win) = weak_close_btn.upgrade() {
            let _ = execute_window_close(&win, &st_close_btn, &eng_close_btn, &tm_close_btn, true);
        }
    });

    let weak_close = window.as_weak();
    let state_close = Rc::clone(state);
    let engine_close = Arc::clone(engine);
    let timer_close = Rc::clone(timer);
    window.window().on_close_requested(move || {
        if let Some(win) = weak_close.upgrade() {
            execute_window_close(&win, &state_close, &engine_close, &timer_close, false)
        } else {
            timer_close.stop();
            let _ = slint::quit_event_loop();
            CloseRequestResponse::HideWindow
        }
    });

    let pending_cmds = Arc::new(Mutex::new(VecDeque::<ShellCommand>::new()));
    let pending_for_ui = Arc::clone(&pending_cmds);
    let weak_dispatch = window.as_weak();
    let state_dispatch = Rc::clone(state);
    let engine_dispatch = Arc::clone(engine);
    let timer_dispatch = Rc::clone(timer);

    window.on_dispatch_shell_commands(move || {
        let drained: Vec<ShellCommand> = match pending_for_ui.lock() {
            Ok(mut q) => q.drain(..).collect(),
            Err(_) => Vec::new(),
        };
        let tasks_q = Arc::clone(&state_dispatch.borrow().ui_tasks);
        let drained_tasks: Vec<UiTask> = match tasks_q.lock() {
            Ok(mut q) => q.drain(..).collect(),
            Err(_) => Vec::new(),
        };
        let Some(win) = weak_dispatch.upgrade() else {
            return;
        };
        for cmd in drained {
            handle_shell_command(
                &win,
                &state_dispatch,
                &engine_dispatch,
                &timer_dispatch,
                cmd,
            );
        }
        for task in drained_tasks {
            task(&win, &state_dispatch, &engine_dispatch, &timer_dispatch);
        }
    });

    let weak_bridge = window.as_weak();
    let _bridge = std::thread::Builder::new()
        .name("lunaria-win-events".to_owned())
        .spawn(move || {
            while let Ok(cmd) = shell_rx.recv() {
                if let Ok(mut q) = pending_cmds.lock() {
                    q.push_back(cmd);
                }
                let weak_ui = weak_bridge.clone();
                let _ = slint::invoke_from_event_loop(move || {
                    if let Some(ui) = weak_ui.upgrade() {
                        ui.invoke_dispatch_shell_commands();
                    }
                });
            }
        });
}

fn handle_shell_command(
    win: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
    timer: &Rc<Timer>,
    cmd: ShellCommand,
) {
    match cmd {
        ShellCommand::Play => {
            if engine.telemetry().active_track.is_some() {
                let _ = engine.play();
                let mut st = state.borrow_mut();
                sync_playback_telemetry(win, &mut st, engine);
                drop(st);
                ensure_adaptive_timer(timer, &win.as_weak(), state, engine, true);
            } else {
                start_playing_queue_current(win, state, engine, timer);
            }
        }
        ShellCommand::Pause => {
            let _ = engine.pause();
            timer.stop();
            let mut st = state.borrow_mut();
            sync_playback_telemetry(win, &mut st, engine);
        }
        ShellCommand::TogglePlayPause => {
            win.invoke_toggle_play_pause();
        }
        ShellCommand::Stop => {
            win.invoke_stop_playback();
        }
        ShellCommand::Next => {
            win.invoke_next_track();
        }
        ShellCommand::Previous => {
            win.invoke_previous_track();
        }
        ShellCommand::ToggleWindow => {
            let currently_visible = state.borrow().window_visible;
            if currently_visible {
                state.borrow_mut().window_visible = false;
                win.global::<MarqueeClock>().set_active(false);
                let _ = win.hide();
                ensure_adaptive_timer(timer, &win.as_weak(), state, engine, false);
            } else {
                let _ = win.show();
                let hwnd_opt = {
                    let mut st = state.borrow_mut();
                    st.window_visible = true;
                    hydrate_from_db(&mut st);
                    sync_theme_and_visuals(win, &mut st, engine);
                    sync_all_views(win, &mut st, engine);
                    ensure_native_window_attached(win, &mut st, engine)
                };
                if let Some(hwnd) = hwnd_opt {
                    focus_window(hwnd);
                }
                ensure_adaptive_timer(timer, &win.as_weak(), state, engine, false);
            }
        }
        ShellCommand::ShowWindow => {
            let _ = win.show();
            let hwnd_opt = {
                let mut st = state.borrow_mut();
                st.window_visible = true;
                hydrate_from_db(&mut st);
                sync_theme_and_visuals(win, &mut st, engine);
                sync_all_views(win, &mut st, engine);
                ensure_native_window_attached(win, &mut st, engine)
            };
            if let Some(hwnd) = hwnd_opt {
                focus_window(hwnd);
            }
            ensure_adaptive_timer(timer, &win.as_weak(), state, engine, false);
        }
        ShellCommand::QuitApp => {
            state.borrow_mut().quit_requested = true;
            timer.stop();
            let _ = slint::quit_event_loop();
        }
        ShellCommand::TaskbarCreated => {
            let tel = engine.telemetry();
            let has_track = tel.active_track.is_some();
            let playing = tel.status == PlaybackStatus::Playing;
            let mut st = state.borrow_mut();
            if let Some(hook) = st.window_hook.as_mut() {
                hook.reinit_taskbar_buttons(playing);
                hook.update_playback(playing, has_track, tel.position_ms, tel.duration_ms);
            }
        }
        ShellCommand::WindowStateChanged { maximized } => {
            win.set_is_maximized(maximized);
        }
        ShellCommand::OpenPaths(paths) => {
            let _ = win.show();
            let hwnd_opt = {
                let mut st = state.borrow_mut();
                st.window_visible = true;
                ensure_native_window_attached(win, &mut st, engine)
            };
            if let Some(hwnd) = hwnd_opt {
                focus_window(hwnd);
            }
            handle_open_paths(win, state, engine, timer, &paths);
        }
    }
}

fn handle_open_paths(
    win: &MainWindow,
    state: &Rc<RefCell<AppState>>,
    engine: &Arc<PlaybackEngine>,
    timer: &Rc<Timer>,
    raw_paths: &[PathBuf],
) {
    let valid: Vec<PathBuf> = raw_paths
        .iter()
        .filter(|p| is_supported_drop_or_open_path(p))
        .cloned()
        .collect();
    if valid.is_empty() {
        return;
    }

    let mut folders_added = false;
    let mut playlist_files = Vec::new();
    let mut cue_files = Vec::new();
    let mut audio_files = Vec::new();

    for path in valid {
        if path.is_dir() {
            let st = state.borrow();
            if st
                .db
                .add_watched_folder(&path.to_string_lossy(), now_epoch_seconds())
                .is_ok()
            {
                folders_added = true;
            }
        } else if let Some(ext) = path.extension().and_then(|e| e.to_str())
            && (ext.eq_ignore_ascii_case("m3u") || ext.eq_ignore_ascii_case("m3u8"))
        {
            playlist_files.push(path);
        } else if let Some(ext) = path.extension().and_then(|e| e.to_str())
            && ext.eq_ignore_ascii_case("cue")
        {
            cue_files.push(path);
        } else {
            audio_files.push(path);
        }
    }

    if folders_added {
        {
            let mut st = state.borrow_mut();
            hydrate_from_db(&mut st);
            sync_settings_views(win, &st);
            let list = st.watched_folders.clone();
            restart_folder_watcher(&mut st, win.as_weak(), &list);
        }
        spawn_library_scan_with_refresh(win, state, engine);
    }

    for pl_path in playlist_files {
        if let Ok(content) = std::fs::read_to_string(&pl_path) {
            let mut st = state.borrow_mut();
            let entries = parse_m3u(&content);
            let matched = match_m3u_tracks(&entries, &st.all_tracks);
            let pl_name = pl_path
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or("Opened Playlist")
                .to_owned();
            if let Ok(pid) = st.db.create_playlist(&pl_name, now_epoch_seconds()) {
                let ids: Vec<TrackId> = matched.iter().map(|t| t.id).collect();
                let _ = st.db.add_tracks_to_playlist(pid, &ids);
                hydrate_from_db(&mut st);
                sync_all_views(win, &mut st, engine);
                win.set_status_banner(SharedString::from(format!(
                    "Imported playlist '{pl_name}' ({} tracks).",
                    ids.len()
                )));
            }
        }
    }

    let mut to_play = Vec::new();
    let art_cache = state.borrow().storage_paths.artwork_cache_dir.clone();

    for cue_path in cue_files {
        if let Ok(raw_cue) = std::fs::read_to_string(&cue_path)
            && let Some(sheet) = parse_cue_sheet(&raw_cue)
            && let Some(cue_dir) = cue_path.parent()
        {
            let target_path = cue_dir.join(sheet.file_name.trim());
            if let Ok(parent_track) = scan_single_audio_file(&target_path, Some(&art_cache), None) {
                let cue_key = normalize_path_key(&cue_path);
                let virtuals = build_virtual_tracks(&sheet, &parent_track, &cue_key);
                to_play.extend(virtuals);
            }
        }
    }

    if audio_files.len() == 1 && to_play.is_empty() {
        play_external_file(win, state, engine, timer, &audio_files[0]);
        return;
    }

    for p in audio_files {
        if let Ok(t) = scan_single_audio_file(&p, Some(&art_cache), None) {
            to_play.push(t);
        }
    }

    if !to_play.is_empty() {
        play_track_list_at(win, state, engine, timer, to_play, 0);
    }
}
