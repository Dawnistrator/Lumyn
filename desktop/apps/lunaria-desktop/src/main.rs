//! Lunaria for Windows desktop application entry point (Milestone 5 Native Shell & Release).

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod canvas;
mod controller;
mod queue;
mod theme;

use std::path::PathBuf;
use std::sync::Arc;

use anyhow::Context;
use crossbeam_channel::unbounded;
use lunaria_audio::{PlaybackEngine, enumerate_render_devices};
use lunaria_db::{LibraryDb, resolve_storage_paths};
use lunaria_dsp::DspConfigStore;
use lunaria_win::{ShellCommand, SingleInstanceOutcome, WindowsShellHost, acquire_or_forward};
use slint::ComponentHandle;
use tracing_subscriber::EnvFilter;

use crate::theme::{LUNARIA_BRAND_SEED_ARGB, build_palette_from_seed, format_rgb_hex};

#[allow(
    unsafe_code,
    clippy::all,
    clippy::pedantic,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::todo,
    clippy::dbg_macro,
    clippy::print_stdout,
    missing_debug_implementations,
    unused_results,
    unused_qualifications
)]
pub mod ui {
    slint::include_modules!();
}

use ui::MainWindow;

fn init_tracing() {
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    let _ = tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_target(false)
        .try_init();
}

fn run_wasapi_probe() -> anyhow::Result<()> {
    let devices = enumerate_render_devices().context("failed to enumerate WASAPI devices")?;
    let dark_palette = build_palette_from_seed(LUNARIA_BRAND_SEED_ARGB, true, 0.0);
    let light_palette = build_palette_from_seed(LUNARIA_BRAND_SEED_ARGB, false, 0.0);

    tracing::info!(
        device_count = devices.len(),
        dark_surface = %format_rgb_hex(dark_palette.surface),
        dark_primary = %format_rgb_hex(dark_palette.primary),
        light_surface = %format_rgb_hex(light_palette.surface),
        light_primary = %format_rgb_hex(light_palette.primary),
        "lunaria windows wasapi probe completed"
    );

    for (idx, dev) in devices.iter().enumerate() {
        tracing::info!(
            index = idx,
            is_default = dev.is_default,
            category = dev.category.label(),
            name = %dev.name,
            format = %dev.mix_format_summary(),
            "detected wasapi render endpoint"
        );
    }

    Ok(())
}

fn main() -> anyhow::Result<()> {
    init_tracing();

    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|arg| arg == "--probe-wasapi") {
        return run_wasapi_probe();
    }
    let allow_multi = args.iter().any(|arg| arg == "--allow-multi-instance");
    let startup_paths: Vec<PathBuf> = args
        .iter()
        .skip(1)
        .filter(|arg| !arg.starts_with('-'))
        .map(PathBuf::from)
        .collect();

    let (shell_tx, shell_rx) = unbounded::<ShellCommand>();
    let _instance_guard = if allow_multi {
        None
    } else {
        match acquire_or_forward("LunariaDesktopV1", &startup_paths, shell_tx.clone()) {
            Ok(SingleInstanceOutcome::ForwardedToExisting) => return Ok(()),
            Ok(SingleInstanceOutcome::Primary(guard)) => Some(guard),
            Err(err) => {
                tracing::warn!(error = %err, "single-instance lock unavailable; starting standalone");
                None
            }
        }
    };

    let storage_paths = resolve_storage_paths(None);
    storage_paths
        .ensure_dirs()
        .context("failed to prepare Lunaria storage directories")?;

    let db = LibraryDb::open(&storage_paths.db_path).with_context(|| {
        format!(
            "failed to open SQLite DB at {}",
            storage_paths.db_path.display()
        )
    })?;
    let prefs = db
        .load_preferences()
        .context("failed to load user preferences")?;

    // Apply Zero-VRAM Software Renderer preference safely prior to MainWindow::new().
    if prefs.use_software_renderer && std::env::var_os("SLINT_BACKEND").is_none() {
        let _ = slint::BackendSelector::new()
            .renderer_name("software".to_owned())
            .select();
    }

    let dsp_store = Arc::new(DspConfigStore::new());
    dsp_store.update_equalizer(|_| prefs.equalizer.clone());

    let engine = Arc::new(
        PlaybackEngine::new(Arc::clone(&dsp_store))
            .context("failed to start WASAPI playback engine")?,
    );
    let _ = engine.set_output_mode(prefs.audio_output_mode);
    let _ = engine.set_device(prefs.preferred_device_id.clone());

    let window = MainWindow::new().context("failed to instantiate Slint MainWindow")?;

    let shell_host = match WindowsShellHost::new(shell_tx.clone()) {
        Ok(host) => Some(host),
        Err(err) => {
            tracing::warn!(error = %err, "Windows tray/SMTC host disabled");
            None
        }
    };

    let shutdown_hook = controller::init_and_bind(
        &window,
        &engine,
        &dsp_store,
        db,
        storage_paths,
        prefs,
        &startup_paths,
        shell_host,
        shell_tx,
        shell_rx,
    )?;

    window.show().context("failed to show Lunaria MainWindow")?;

    slint::run_event_loop_until_quit().context("Slint event loop exited with error")?;
    shutdown_hook();
    engine.shutdown();
    Ok(())
}
