//! Native Windows 10/11 desktop shell integration for Lunaria:
//! - SMTC (`SystemMediaTransportControls`) media flyout & hardware key hooks
//! - System Tray (`Shell_NotifyIconW`) icon, now-playing tooltip & popup context menu
//! - Taskbar (`ITaskbarList3`) thumbnail toolbar (`Prev` / `Play-Pause` / `Next`) & progress bar
//! - Single-Instance Windows Mutex + Named Pipe file forwarding
//! - Windows Explorer drag-and-drop (`WM_DROPFILES`)
//! - Zero-network Discord Rich Presence over local Named Pipes (enabled by default with On/Off toggle)

pub mod discord_rpc;
mod error;
mod events;
mod shell_host;
mod single_instance;
mod taskbar_and_drop;

pub use discord_rpc::{
    DEFAULT_DISCORD_CLIENT_ID, DiscordActivitySnapshot, DiscordRpcHost, DiscordRpcStatus,
};
pub use error::WinError;
pub use events::{MediaMetadataSnapshot, ShellCommand, is_supported_drop_or_open_path};
pub use shell_host::WindowsShellHost;
pub use single_instance::{SingleInstanceGuard, SingleInstanceOutcome, acquire_or_forward};
pub use taskbar_and_drop::{
    MainWindowHook, configure_frameless_window_chrome, focus_window, is_window_maximized,
    minimize_window, start_window_drag, sync_window_border_color, toggle_maximize_window,
};

#[cfg(test)]
mod tests {
    use std::error::Error;
    use std::path::{Path, PathBuf};
    use std::time::{Duration, SystemTime, UNIX_EPOCH};

    use crossbeam_channel::unbounded;
    use tempfile::tempdir;

    use super::*;

    type TestResult<T = ()> = Result<T, Box<dyn Error>>;

    #[test]
    fn supported_drop_path_filter_recognizes_audio_cue_m3u_and_rejects_unrelated() -> TestResult {
        let dir = tempdir()?;
        assert!(is_supported_drop_or_open_path(dir.path()));
        assert!(is_supported_drop_or_open_path(Path::new(
            "D:/Music/album/track.FLAC"
        )));
        assert!(is_supported_drop_or_open_path(Path::new(
            "D:/Music/concert.cue"
        )));
        assert!(is_supported_drop_or_open_path(Path::new(
            "D:/Playlists/favorites.m3u8"
        )));
        assert!(!is_supported_drop_or_open_path(Path::new(
            "D:/Docs/readme.txt"
        )));
        assert!(!is_supported_drop_or_open_path(Path::new(
            "D:/Images/cover.jpg"
        )));
        Ok(())
    }

    #[test]
    fn single_instance_primary_receives_forwarded_paths_from_second_instance() -> TestResult {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| d.as_nanos());
        let unique_key = format!("LunariaTest_{}_{}", std::process::id(), nanos);

        let (tx1, rx1) = unbounded();
        let outcome1 = acquire_or_forward(&unique_key, &[], tx1)?;
        let SingleInstanceOutcome::Primary(guard) = outcome1 else {
            return Err("first call must acquire primary single-instance guard".into());
        };

        // Give named-pipe listener thread a brief moment to bind \\.\pipe\<key>_Pipe.
        std::thread::sleep(Duration::from_millis(60));

        let (tx2, _rx2) = unbounded();
        let passed = vec![PathBuf::from("D:/Music/Master.flac")];
        let outcome2 = acquire_or_forward(&unique_key, &passed, tx2)?;
        assert!(matches!(
            outcome2,
            SingleInstanceOutcome::ForwardedToExisting
        ));

        let received = rx1.recv_timeout(Duration::from_secs(2))?;
        assert_eq!(received, ShellCommand::OpenPaths(passed));

        drop(guard);
        Ok(())
    }

    #[test]
    fn shell_host_lifecycle_starts_updates_metadata_and_drops_cleanly() -> TestResult {
        let (tx, _rx) = unbounded();
        let host = WindowsShellHost::new(tx)?;
        host.update_metadata(MediaMetadataSnapshot {
            title: "Kind of Blue".to_owned(),
            artist: "Miles Davis".to_owned(),
            album: "Blue Master".to_owned(),
            artwork_path: None,
            is_playing: true,
            has_track: true,
            position_ms: 12_000,
            duration_ms: 240_000,
        });
        std::thread::sleep(Duration::from_millis(60));
        drop(host);
        Ok(())
    }

    #[test]
    fn main_window_hook_rejects_null_hwnd_cleanly() {
        let (tx, _rx) = unbounded();
        let res = MainWindowHook::attach(0, tx);
        assert!(res.is_err());
    }

    #[test]
    fn window_chrome_helpers_ignore_null_hwnd_cleanly() {
        configure_frameless_window_chrome(0);
        sync_window_border_color(0, (14, 97, 120));
        start_window_drag(0);
        minimize_window(0);
        assert!(!is_window_maximized(0));
        assert!(!toggle_maximize_window(0));
    }

    #[test]
    fn tray_tooltip_and_menu_labels_stay_concise_and_escape_ampersands() {
        let idle = MediaMetadataSnapshot::default();
        assert_eq!(shell_host::format_tray_tooltip(&idle), "Lunaria");
        assert_eq!(
            shell_host::format_tray_menu_track_label(&idle),
            "Not Playing"
        );

        let incomplete_track = MediaMetadataSnapshot {
            title: "Incomplete (T-Mass Remix)".to_owned(),
            artist: "Aero Chord, Anuka & T-Mass".to_owned(),
            has_track: true,
            ..MediaMetadataSnapshot::default()
        };
        assert_eq!(
            shell_host::format_tray_tooltip(&incomplete_track),
            "Lunaria\nIncomplete (T-Mass Remix)\nAero Chord, Anuka & T-Mass"
        );
        let inc_menu = shell_host::format_tray_menu_track_label(&incomplete_track);
        assert!(inc_menu.ends_with('…'));

        let amp_track = MediaMetadataSnapshot {
            title: "1x1".to_owned(),
            artist: "Bring Me The Horizon & Nova Twins".to_owned(),
            has_track: true,
            ..MediaMetadataSnapshot::default()
        };
        let amp_menu = shell_host::format_tray_menu_track_label(&amp_track);
        assert!(amp_menu.contains("&&"));
        assert!(amp_menu.ends_with('…'));

        let title_only = MediaMetadataSnapshot {
            title: "Instrumental Sketch".to_owned(),
            artist: "   ".to_owned(),
            has_track: true,
            ..MediaMetadataSnapshot::default()
        };
        assert_eq!(
            shell_host::format_tray_tooltip(&title_only),
            "Lunaria\nInstrumental Sketch"
        );

        let long_track = MediaMetadataSnapshot {
            title: "Nothing Can Hold Us Down (feat. Haris) [Dr. Phunk Extended Remix]".to_owned(),
            artist: "Hardwell & Headhunterz featuring Special Orchestra Ensemble".to_owned(),
            has_track: true,
            ..MediaMetadataSnapshot::default()
        };
        let long_tip = shell_host::format_tray_tooltip(&long_track);
        let lines: Vec<&str> = long_tip.lines().collect();
        assert_eq!(lines.len(), 3);
        assert_eq!(lines[0], "Lunaria");
        assert!(lines[1].chars().count() <= 42);
        assert!(lines[1].ends_with('…'));
        assert!(lines[2].chars().count() <= 42);
        assert!(lines[2].ends_with('…'));
        assert!(long_tip.encode_utf16().count() <= 127);

        let long_menu = shell_host::format_tray_menu_track_label(&long_track);
        assert!(long_menu.ends_with('…'));
        assert!(long_menu.replace("&&", "&").chars().count() <= 38);
    }

    #[test]
    fn discord_rpc_frames_handshake_and_activity_payloads_encode_cleanly() -> TestResult {
        assert_eq!(
            discord_rpc::resolve_discord_client_id("  123456789012345678  ", None),
            Some("123456789012345678".to_owned())
        );
        assert_eq!(
            discord_rpc::resolve_discord_client_id("not-numeric", Some("987654321098765432")),
            Some("987654321098765432".to_owned())
        );
        assert_eq!(
            discord_rpc::resolve_discord_client_id("   ", None),
            Some(DEFAULT_DISCORD_CLIENT_ID.to_owned())
        );

        let handshake = discord_rpc::build_handshake_payload("123456789012345678");
        let encoded = discord_rpc::encode_ipc_frame(0, &handshake)?;
        let (op, decoded, consumed) =
            discord_rpc::decode_ipc_frame(&encoded).ok_or("failed decoding IPC frame")?;
        assert_eq!(op, 0);
        assert_eq!(consumed, encoded.len());
        assert_eq!(decoded["client_id"], "123456789012345678");

        let playing_snap = DiscordActivitySnapshot {
            has_track: true,
            is_playing: true,
            title: "A".to_owned(),
            artist: "Miles Davis".to_owned(),
            album: "Kind of Blue".to_owned(),
            quality_badge: "FLAC • 2840 kbps • 96 kHz".to_owned(),
            output_mode_label: "Purist Bit-Perfect".to_owned(),
            position_ms: 30_000,
            duration_ms: 300_000,
        };
        let payload =
            discord_rpc::build_set_activity_payload(42, "n1", &playing_snap, 1_700_000_030);
        assert_eq!(payload["cmd"], "SET_ACTIVITY");
        assert_eq!(payload["args"]["activity"]["type"], 2);
        assert_eq!(payload["args"]["activity"]["details"], "A ");
        assert_eq!(payload["args"]["activity"]["state"], "Miles Davis");
        assert_eq!(
            payload["args"]["activity"]["assets"]["large_text"],
            "FLAC • 2840 kbps • 96 kHz"
        );
        assert_eq!(
            payload["args"]["activity"]["timestamps"]["start"],
            1_700_000_000
        );
        assert_eq!(
            payload["args"]["activity"]["timestamps"]["end"],
            1_700_000_300
        );

        let paused_snap = DiscordActivitySnapshot {
            is_playing: false,
            ..playing_snap
        };
        let cleared =
            discord_rpc::build_set_activity_payload(42, "n2", &paused_snap, 1_700_000_035);
        assert!(cleared["args"]["activity"].is_null());
        Ok(())
    }
}
