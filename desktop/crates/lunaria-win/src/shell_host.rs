//! Dedicated STA shell thread hosting Windows System Tray (`Shell_NotifyIconW`) and
//! Windows SMTC (`SystemMediaTransportControls`) media flyout & hardware key bindings.

use std::fmt;
use std::path::Path;
use std::sync::atomic::{AtomicIsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use crossbeam_channel::Sender;

use crate::{MediaMetadataSnapshot, ShellCommand, WinError};

/// Hosts native System Tray icon + popup menu and WinRT SMTC on an isolated STA thread,
/// avoiding any COM apartment conflict (`RPC_E_CHANGED_MODE`) on Slint's UI thread.
pub struct WindowsShellHost {
    meta: Arc<Mutex<MediaMetadataSnapshot>>,
    #[cfg(windows)]
    hidden_hwnd: Arc<AtomicIsize>,
    #[cfg(windows)]
    thread_handle: Option<JoinHandle<()>>,
}

impl fmt::Debug for WindowsShellHost {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("WindowsShellHost").finish_non_exhaustive()
    }
}

impl WindowsShellHost {
    /// Spawns the Windows Shell STA thread initializing System Tray and SMTC hooks.
    pub fn new(cmd_tx: Sender<ShellCommand>) -> Result<Self, WinError> {
        let meta = Arc::new(Mutex::new(MediaMetadataSnapshot::default()));

        #[cfg(windows)]
        {
            let hidden_hwnd = Arc::new(AtomicIsize::new(0));
            let meta_bg = Arc::clone(&meta);
            let hwnd_bg = Arc::clone(&hidden_hwnd);
            let (ready_tx, ready_rx) = crossbeam_channel::bounded::<Result<(), WinError>>(1);

            let thread_handle = thread::Builder::new()
                .name("lunaria-win-shell".to_owned())
                .spawn(move || {
                    run_win_shell_thread(&cmd_tx, &meta_bg, &hwnd_bg, &ready_tx);
                })
                .map_err(|e| WinError::Platform(format!("failed spawning shell host: {e}")))?;

            match ready_rx.recv_timeout(std::time::Duration::from_secs(3)) {
                Ok(Ok(())) => {}
                Ok(Err(err)) => {
                    tracing::warn!(error = %err, "Windows shell host reported non-fatal init error");
                }
                Err(_) => {
                    tracing::warn!("timed out waiting for Windows shell host ready signal");
                }
            }

            Ok(Self {
                meta,
                hidden_hwnd,
                thread_handle: Some(thread_handle),
            })
        }

        #[cfg(not(windows))]
        {
            let _ = cmd_tx;
            Ok(Self { meta })
        }
    }

    /// Publishes updated track title/artist/album/artwork & playback state to SMTC and Tray tooltip.
    pub fn update_metadata(&self, snapshot: MediaMetadataSnapshot) {
        if let Ok(mut guard) = self.meta.lock() {
            *guard = snapshot;
        }
        #[cfg(windows)]
        {
            let raw = self.hidden_hwnd.load(Ordering::Acquire);
            if raw != 0 {
                #[expect(
                    unsafe_code,
                    reason = "posts metadata refresh message to hidden shell STA window"
                )]
                // SAFETY: `raw` is the live hidden `HWND` owned by `WindowsShellHost`'s STA thread.
                unsafe {
                    let hwnd = windows::Win32::Foundation::HWND(raw as *mut core::ffi::c_void);
                    let _ = windows::Win32::UI::WindowsAndMessaging::PostMessageW(
                        Some(hwnd),
                        WM_LUNARIA_UPDATE_META,
                        windows::Win32::Foundation::WPARAM(0),
                        windows::Win32::Foundation::LPARAM(0),
                    );
                }
            }
        }
    }
}

impl Drop for WindowsShellHost {
    fn drop(&mut self) {
        #[cfg(windows)]
        {
            let raw = self.hidden_hwnd.load(Ordering::Acquire);
            if raw != 0 {
                #[expect(
                    unsafe_code,
                    reason = "posts WM_CLOSE to terminate STA shell message loop cleanly"
                )]
                // SAFETY: `raw` is the owned hidden message-only `HWND`.
                unsafe {
                    let hwnd = windows::Win32::Foundation::HWND(raw as *mut core::ffi::c_void);
                    let _ = windows::Win32::UI::WindowsAndMessaging::PostMessageW(
                        Some(hwnd),
                        windows::Win32::UI::WindowsAndMessaging::WM_CLOSE,
                        windows::Win32::Foundation::WPARAM(0),
                        windows::Win32::Foundation::LPARAM(0),
                    );
                }
            }
            if let Some(handle) = self.thread_handle.take() {
                let _ = handle.join();
            }
        }
    }
}

#[cfg(windows)]
const WM_LUNARIA_TRAY: u32 = windows::Win32::UI::WindowsAndMessaging::WM_APP + 101;
#[cfg(windows)]
const WM_LUNARIA_UPDATE_META: u32 = windows::Win32::UI::WindowsAndMessaging::WM_APP + 102;
#[cfg(windows)]
const TRAY_ICON_ID: u32 = 1;

#[cfg(windows)]
const MENU_SHOW_HIDE: usize = 2001;
#[cfg(windows)]
const MENU_PLAY_PAUSE: usize = 2002;
#[cfg(windows)]
const MENU_PREV: usize = 2003;
#[cfg(windows)]
const MENU_NEXT: usize = 2004;
#[cfg(windows)]
const MENU_QUIT: usize = 2005;

#[cfg(windows)]
struct ShellThreadCtx {
    cmd_tx: Sender<ShellCommand>,
    meta: Arc<Mutex<MediaMetadataSnapshot>>,
    smtc: Option<windows::Media::SystemMediaTransportControls>,
    last_artwork_path: Option<std::path::PathBuf>,
    last_thumb: Option<windows::Storage::Streams::RandomAccessStreamReference>,
    tray_nid: windows::Win32::UI::Shell::NOTIFYICONDATAW,
    tray_active: bool,
    taskbar_created_msg: u32,
}

pub(crate) fn truncate_with_ellipsis(text: &str, max_chars: usize) -> String {
    let trimmed = text.trim();
    if trimmed.chars().count() <= max_chars {
        return trimmed.to_owned();
    }
    let keep = max_chars.saturating_sub(1);
    let prefix: String = trimmed.chars().take(keep).collect();
    format!("{}…", prefix.trim_end())
}

fn format_track_summary(meta: &MediaMetadataSnapshot) -> Option<String> {
    if !meta.has_track {
        return None;
    }
    let title = meta.title.trim();
    if title.is_empty() {
        return None;
    }
    let artist = meta.artist.trim();
    if artist.is_empty() {
        Some(title.to_owned())
    } else {
        Some(format!("{title} - {artist}"))
    }
}

pub(crate) fn format_tray_tooltip(meta: &MediaMetadataSnapshot) -> String {
    if !meta.has_track {
        return "Lunaria".to_owned();
    }
    let title = meta.title.trim();
    if title.is_empty() {
        return "Lunaria".to_owned();
    }
    let artist = meta.artist.trim();
    if artist.is_empty() {
        format!("Lunaria\n{}", truncate_with_ellipsis(title, 42))
    } else {
        format!(
            "Lunaria\n{}\n{}",
            truncate_with_ellipsis(title, 42),
            truncate_with_ellipsis(artist, 42)
        )
    }
}

pub(crate) fn format_tray_menu_track_label(meta: &MediaMetadataSnapshot) -> String {
    match format_track_summary(meta) {
        Some(summary) => truncate_with_ellipsis(&summary, 38).replace('&', "&&"),
        None => "Not Playing".to_owned(),
    }
}

#[cfg(windows)]
fn to_wide_null(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}

#[cfg(windows)]
fn write_fixed_wide_tip(dst: &mut [u16; 128], text: &str) {
    dst.fill(0);
    for (slot, code_unit) in dst.iter_mut().take(127).zip(text.encode_utf16()) {
        *slot = code_unit;
    }
}

#[cfg(windows)]
fn run_win_shell_thread(
    cmd_tx: &Sender<ShellCommand>,
    meta: &Arc<Mutex<MediaMetadataSnapshot>>,
    hidden_hwnd_slot: &Arc<AtomicIsize>,
    ready_tx: &Sender<Result<(), WinError>>,
) {
    use windows::Win32::Foundation::{HWND, LPARAM, WPARAM};
    use windows::Win32::System::Com::{COINIT_APARTMENTTHREADED, CoInitializeEx, CoUninitialize};
    use windows::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows::Win32::UI::Shell::{
        NIF_ICON, NIF_MESSAGE, NIF_TIP, NIM_ADD, NIM_DELETE, NOTIFYICONDATAW, Shell_NotifyIconW,
    };
    use windows::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DispatchMessageW, GWLP_USERDATA, GetMessageW, GetSystemMetrics, HICON,
        IDI_APPLICATION, IMAGE_ICON, LR_DEFAULTCOLOR, LR_SHARED, LoadIconW, LoadImageW, MSG,
        RegisterClassExW, RegisterWindowMessageW, SM_CXSMICON, SM_CYSMICON, SetWindowLongPtrW,
        TranslateMessage, WINDOW_EX_STYLE, WNDCLASSEXW, WS_OVERLAPPEDWINDOW,
    };
    use windows::core::PCWSTR;

    #[expect(
        unsafe_code,
        reason = "initializes isolated STA apartment on shell host thread"
    )]
    // SAFETY: Standard per-thread STA COM initialization at thread entry.
    let com_ok = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED).is_ok() };

    let class_name = to_wide_null("LunariaHiddenShellHostV1");
    let win_title = to_wide_null("Lunaria");
    let taskbar_msg_name = to_wide_null("TaskbarCreated");

    #[expect(
        unsafe_code,
        reason = "registers hidden Win32 message window class for Tray & SMTC"
    )]
    // SAFETY: Null-terminated UTF-16 class/window names remain pinned for the synchronous Win32 registration calls.
    let (hwnd, icon_handle, taskbar_created_msg) = unsafe {
        let hinst = GetModuleHandleW(None).unwrap_or_default();
        let icon_res = PCWSTR::from_raw(std::ptr::without_provenance::<u16>(1));
        let cx = GetSystemMetrics(SM_CXSMICON).max(16);
        let cy = GetSystemMetrics(SM_CYSMICON).max(16);
        let icon: HICON = match LoadImageW(
            Some(hinst.into()),
            icon_res,
            IMAGE_ICON,
            cx,
            cy,
            LR_DEFAULTCOLOR | LR_SHARED,
        ) {
            Ok(h) => HICON(h.0),
            Err(err) => {
                tracing::warn!(error = %err, "LoadImageW failed for tray icon resource ID 1; trying LoadIconW");
                LoadIconW(Some(hinst.into()), icon_res)
                    .or_else(|err2| {
                        tracing::warn!(error = %err2, "LoadIconW failed for tray icon resource ID 1; falling back to IDI_APPLICATION");
                        LoadIconW(None, IDI_APPLICATION)
                    })
                    .unwrap_or_default()
            }
        };

        let wc = WNDCLASSEXW {
            cbSize: size_of::<WNDCLASSEXW>() as u32,
            lpfnWndProc: Some(shell_wnd_proc),
            hInstance: hinst.into(),
            hIcon: icon,
            hIconSm: icon,
            lpszClassName: PCWSTR::from_raw(class_name.as_ptr()),
            ..Default::default()
        };
        let _ = RegisterClassExW(&wc);
        let taskbar_msg = RegisterWindowMessageW(PCWSTR::from_raw(taskbar_msg_name.as_ptr()));

        let created = CreateWindowExW(
            WINDOW_EX_STYLE(0),
            PCWSTR::from_raw(class_name.as_ptr()),
            PCWSTR::from_raw(win_title.as_ptr()),
            WS_OVERLAPPEDWINDOW,
            0,
            0,
            0,
            0,
            None,
            None,
            Some(hinst.into()),
            None,
        );
        (created, icon, taskbar_msg)
    };

    let Ok(hwnd) = hwnd else {
        let _ = ready_tx.send(Err(WinError::Platform(
            "failed creating hidden shell host window".to_owned(),
        )));
        if com_ok {
            #[expect(unsafe_code, reason = "balances COM init on failure exit")]
            // SAFETY: Balances the successful `CoInitializeEx` on this thread.
            unsafe {
                CoUninitialize();
            }
        }
        return;
    };

    let mut tray_nid = NOTIFYICONDATAW {
        cbSize: size_of::<NOTIFYICONDATAW>() as u32,
        hWnd: hwnd,
        uID: TRAY_ICON_ID,
        uFlags: NIF_MESSAGE | NIF_ICON | NIF_TIP,
        uCallbackMessage: WM_LUNARIA_TRAY,
        hIcon: icon_handle,
        ..Default::default()
    };
    write_fixed_wide_tip(&mut tray_nid.szTip, "Lunaria");

    #[expect(
        unsafe_code,
        reason = "registers Lunaria icon into the Windows notification tray"
    )]
    // SAFETY: `tray_nid` is fully initialized with a live `hwnd` owned by this thread.
    let tray_active = unsafe { Shell_NotifyIconW(NIM_ADD, &tray_nid).as_bool() };

    let smtc = init_smtc_for_hwnd(hwnd, cmd_tx.clone());

    let ctx_box = Box::new(std::cell::RefCell::new(ShellThreadCtx {
        cmd_tx: cmd_tx.clone(),
        meta: Arc::clone(meta),
        smtc,
        last_artwork_path: None,
        last_thumb: None,
        tray_nid,
        tray_active,
        taskbar_created_msg,
    }));
    let ctx_ptr = Box::into_raw(ctx_box);

    #[expect(
        unsafe_code,
        reason = "attaches owned RefCell<ShellThreadCtx> pointer to hidden HWND GWLP_USERDATA"
    )]
    // SAFETY: `hwnd` is a newly created STA window owned by this thread and `ctx_ptr` outlives the pump loop.
    unsafe {
        let _ = SetWindowLongPtrW(hwnd, GWLP_USERDATA, ctx_ptr as isize);
    }

    hidden_hwnd_slot.store(hwnd.0 as isize, Ordering::Release);
    let _ = ready_tx.send(Ok(()));

    let mut msg = MSG::default();
    #[expect(
        unsafe_code,
        reason = "standard STA Win32 message pump for Tray & SMTC callbacks"
    )]
    // SAFETY: Pump runs exclusively on the dedicated STA thread that created `hwnd`.
    unsafe {
        while GetMessageW(&mut msg, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&msg);
            let _ = DispatchMessageW(&msg);
        }
    }

    hidden_hwnd_slot.store(0, Ordering::Release);

    #[expect(
        unsafe_code,
        reason = "detaches and frees ShellThreadCtx, removes tray icon, and cleans COM"
    )]
    // SAFETY: Message loop has exited so no further `shell_wnd_proc` calls occur; `ctx_ptr` is uniquely owned here.
    unsafe {
        let _ = SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
        let owned_cell = Box::from_raw(ctx_ptr);
        let mut owned_ctx = owned_cell.into_inner();
        if owned_ctx.tray_active {
            let _ = Shell_NotifyIconW(NIM_DELETE, &owned_ctx.tray_nid);
            owned_ctx.tray_active = false;
        }
        owned_ctx.smtc = None;
        let _ = (WPARAM(0), LPARAM(0), HWND::default());
        if com_ok {
            CoUninitialize();
        }
    }
}

#[cfg(windows)]
fn init_smtc_for_hwnd(
    hwnd: windows::Win32::Foundation::HWND,
    cmd_tx: Sender<ShellCommand>,
) -> Option<windows::Media::SystemMediaTransportControls> {
    use windows::Foundation::TypedEventHandler;
    use windows::Media::{
        SystemMediaTransportControls, SystemMediaTransportControlsButton,
        SystemMediaTransportControlsButtonPressedEventArgs,
    };
    use windows::Win32::System::WinRT::ISystemMediaTransportControlsInterop;
    use windows::core::factory;

    let interop: ISystemMediaTransportControlsInterop =
        match factory::<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>() {
            Ok(i) => i,
            Err(err) => {
                tracing::warn!(error = %err, "SMTC interop factory unavailable in this session");
                return None;
            }
        };

    #[expect(
        unsafe_code,
        reason = "binds Windows SMTC instance to hidden desktop HWND via WinRT interop"
    )]
    // SAFETY: `hwnd` is a valid hidden window on this STA thread and `interop` is a live COM factory.
    let smtc = unsafe {
        match interop.GetForWindow::<SystemMediaTransportControls>(hwnd) {
            Ok(s) => s,
            Err(err) => {
                tracing::warn!(error = %err, "SMTC GetForWindow failed; continuing with tray only");
                return None;
            }
        }
    };

    let _ = smtc.SetIsEnabled(true);
    let _ = smtc.SetIsPlayEnabled(true);
    let _ = smtc.SetIsPauseEnabled(true);
    let _ = smtc.SetIsNextEnabled(true);
    let _ = smtc.SetIsPreviousEnabled(true);
    let _ = smtc.SetIsStopEnabled(true);

    let handler = TypedEventHandler::<
        SystemMediaTransportControls,
        SystemMediaTransportControlsButtonPressedEventArgs,
    >::new(move |_sender, args_ref| {
        if let Some(args) = args_ref.as_ref()
            && let Ok(btn) = args.Button()
        {
            let mapped = match btn {
                SystemMediaTransportControlsButton::Play => Some(ShellCommand::Play),
                SystemMediaTransportControlsButton::Pause => Some(ShellCommand::Pause),
                SystemMediaTransportControlsButton::Next => Some(ShellCommand::Next),
                SystemMediaTransportControlsButton::Previous => Some(ShellCommand::Previous),
                SystemMediaTransportControlsButton::Stop => Some(ShellCommand::Stop),
                _ => None,
            };
            if let Some(cmd) = mapped {
                let _ = cmd_tx.send(cmd);
            }
        }
        Ok(())
    });
    let _ = smtc.ButtonPressed(&handler);
    Some(smtc)
}

#[cfg(windows)]
fn apply_metadata_to_shell(cell: &std::cell::RefCell<ShellThreadCtx>) {
    use windows::Media::{MediaPlaybackStatus, MediaPlaybackType};
    use windows::Storage::StorageFile;
    use windows::Storage::Streams::RandomAccessStreamReference;
    use windows::Win32::UI::Shell::{NIF_TIP, NIM_MODIFY, Shell_NotifyIconW};
    use windows::core::HSTRING;

    let Ok(mut ctx) = cell.try_borrow_mut() else {
        return;
    };
    let snap = match ctx.meta.lock() {
        Ok(g) => g.clone(),
        Err(_) => return,
    };

    let tip = format_tray_tooltip(&snap);
    write_fixed_wide_tip(&mut ctx.tray_nid.szTip, &tip);
    let tray_active = ctx.tray_active;
    if tray_active {
        ctx.tray_nid.uFlags = NIF_TIP;
    }
    let tray_nid_copy = ctx.tray_nid;
    let smtc_opt = ctx.smtc.clone();
    let artwork_changed = snap.artwork_path != ctx.last_artwork_path;
    let cached_thumb = if !artwork_changed {
        ctx.last_thumb.clone()
    } else {
        None
    };
    drop(ctx);

    if tray_active {
        #[expect(unsafe_code, reason = "updates Windows tray tooltip text")]
        // SAFETY: `tray_nid_copy` is an initialized stack-local copy referencing the live shell HWND.
        unsafe {
            let _ = Shell_NotifyIconW(NIM_MODIFY, &tray_nid_copy);
        }
    }

    let Some(smtc) = smtc_opt else {
        return;
    };

    let status = if !snap.has_track {
        MediaPlaybackStatus::Closed
    } else if snap.is_playing {
        MediaPlaybackStatus::Playing
    } else {
        MediaPlaybackStatus::Paused
    };
    let _ = smtc.SetPlaybackStatus(status);

    let Ok(updater) = smtc.DisplayUpdater() else {
        return;
    };
    let _ = updater.ClearAll();
    let _ = updater.SetType(MediaPlaybackType::Music);
    if let Ok(props) = updater.MusicProperties() {
        let _ = props.SetTitle(&HSTRING::from(snap.title.as_str()));
        let _ = props.SetArtist(&HSTRING::from(snap.artist.as_str()));
        let _ = props.SetAlbumTitle(&HSTRING::from(snap.album.as_str()));
    }

    let thumb = if artwork_changed {
        let loaded = snap
            .artwork_path
            .as_deref()
            .filter(|p| p.is_file())
            .and_then(|p| {
                let win_path = normalize_win32_path_for_winrt(p);
                StorageFile::GetFileFromPathAsync(&HSTRING::from(win_path.as_str()))
                    .ok()?
                    .join()
                    .ok()
                    .and_then(|file| RandomAccessStreamReference::CreateFromFile(&file).ok())
            });
        if let Ok(mut ctx) = cell.try_borrow_mut() {
            ctx.last_artwork_path.clone_from(&snap.artwork_path);
            ctx.last_thumb.clone_from(&loaded);
        }
        loaded
    } else {
        cached_thumb
    };
    if let Some(t) = &thumb {
        let _ = updater.SetThumbnail(t);
    }
    let _ = updater.Update();
}

#[cfg(windows)]
fn normalize_win32_path_for_winrt(p: &Path) -> String {
    let raw = p.to_string_lossy().replace('/', "\\");
    raw.strip_prefix(r"\\?\").unwrap_or(&raw).to_owned()
}

#[cfg(windows)]
#[expect(
    unsafe_code,
    reason = "Win32 window procedure handling tray notifications, popup menu, and SMTC updates"
)]
unsafe extern "system" fn shell_wnd_proc(
    hwnd: windows::Win32::Foundation::HWND,
    msg: u32,
    wparam: windows::Win32::Foundation::WPARAM,
    lparam: windows::Win32::Foundation::LPARAM,
) -> windows::Win32::Foundation::LRESULT {
    use windows::Win32::Foundation::{LRESULT, POINT};
    use windows::Win32::UI::Shell::Shell_NotifyIconW;
    use windows::Win32::UI::WindowsAndMessaging::{
        AppendMenuW, CreatePopupMenu, DefWindowProcW, DestroyMenu, DestroyWindow, GWLP_USERDATA,
        GetCursorPos, GetWindowLongPtrW, MF_GRAYED, MF_SEPARATOR, MF_STRING, PostQuitMessage,
        SetForegroundWindow, SetWindowLongPtrW, TPM_BOTTOMALIGN, TPM_LEFTALIGN, TPM_RETURNCMD,
        TrackPopupMenuEx, WM_CLOSE, WM_DESTROY, WM_LBUTTONDBLCLK, WM_LBUTTONUP, WM_NCDESTROY,
        WM_RBUTTONUP,
    };
    use windows::core::PCWSTR;

    // SAFETY: `ctx_ptr` was installed cleanly in `GWLP_USERDATA` on `run_win_shell_thread` and points to a `RefCell<ShellThreadCtx>` alive until after the message pump exits.
    let ctx_ptr = unsafe {
        GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *const std::cell::RefCell<ShellThreadCtx>
    };

    match msg {
        WM_LUNARIA_UPDATE_META => {
            if !ctx_ptr.is_null() {
                // SAFETY: `ctx_ptr` points to the live STA `RefCell<ShellThreadCtx>` on this thread.
                let ctx_cell = unsafe { &*ctx_ptr };
                apply_metadata_to_shell(ctx_cell);
            }
            LRESULT(0)
        }
        WM_LUNARIA_TRAY => {
            let notify_event = (lparam.0 & 0xFFFF) as u32;
            if notify_event == WM_LBUTTONUP || notify_event == WM_LBUTTONDBLCLK {
                if !ctx_ptr.is_null() {
                    // SAFETY: `ctx_ptr` belongs to this live STA window on this thread.
                    let ctx_cell = unsafe { &*ctx_ptr };
                    if let Ok(ctx) = ctx_cell.try_borrow() {
                        let _ = ctx.cmd_tx.send(ShellCommand::ToggleWindow);
                    }
                }
            } else if notify_event == WM_RBUTTONUP && !ctx_ptr.is_null() {
                // Extract everything needed before entering nested `TrackPopupMenuEx` loop.
                // SAFETY: `ctx_ptr` belongs to this live STA window on this thread.
                let menu_info = unsafe { &*ctx_ptr }.try_borrow().ok().map(|ctx| {
                    let (now_info, is_playing) = match ctx.meta.lock() {
                        Ok(g) => (
                            format_tray_menu_track_label(&g),
                            g.has_track && g.is_playing,
                        ),
                        Err(_) => ("Not Playing".to_owned(), false),
                    };
                    (ctx.cmd_tx.clone(), now_info, is_playing)
                });
                let Some((cmd_tx, now_info, is_playing)) = menu_info else {
                    return LRESULT(0);
                };

                // SAFETY: Builds transient Win32 tray popup menu synchronously without holding `RefCell` borrows.
                unsafe {
                    let Ok(menu) = CreatePopupMenu() else {
                        return LRESULT(0);
                    };

                    let info_w = to_wide_null(&now_info);
                    let pp_label = if is_playing { "Pause" } else { "Play" };
                    let pp_w = to_wide_null(pp_label);
                    let prev_w = to_wide_null("Previous");
                    let next_w = to_wide_null("Next");
                    let toggle_w = to_wide_null("Show / Hide");
                    let quit_w = to_wide_null("Exit");

                    let _ = AppendMenuW(
                        menu,
                        MF_STRING | MF_GRAYED,
                        0,
                        PCWSTR::from_raw(info_w.as_ptr()),
                    );
                    let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
                    let _ = AppendMenuW(
                        menu,
                        MF_STRING,
                        MENU_PLAY_PAUSE,
                        PCWSTR::from_raw(pp_w.as_ptr()),
                    );
                    let _ = AppendMenuW(
                        menu,
                        MF_STRING,
                        MENU_PREV,
                        PCWSTR::from_raw(prev_w.as_ptr()),
                    );
                    let _ = AppendMenuW(
                        menu,
                        MF_STRING,
                        MENU_NEXT,
                        PCWSTR::from_raw(next_w.as_ptr()),
                    );
                    let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
                    let _ = AppendMenuW(
                        menu,
                        MF_STRING,
                        MENU_SHOW_HIDE,
                        PCWSTR::from_raw(toggle_w.as_ptr()),
                    );
                    let _ = AppendMenuW(
                        menu,
                        MF_STRING,
                        MENU_QUIT,
                        PCWSTR::from_raw(quit_w.as_ptr()),
                    );

                    let mut pt = POINT::default();
                    let _ = GetCursorPos(&mut pt);
                    let _ = SetForegroundWindow(hwnd);
                    let chosen = TrackPopupMenuEx(
                        menu,
                        (TPM_RETURNCMD | TPM_LEFTALIGN | TPM_BOTTOMALIGN).0,
                        pt.x,
                        pt.y,
                        hwnd,
                        None,
                    );
                    let _ = DestroyMenu(menu);

                    let cmd = match chosen.0 as usize {
                        MENU_SHOW_HIDE => Some(ShellCommand::ToggleWindow),
                        MENU_PLAY_PAUSE => Some(ShellCommand::TogglePlayPause),
                        MENU_PREV => Some(ShellCommand::Previous),
                        MENU_NEXT => Some(ShellCommand::Next),
                        MENU_QUIT => Some(ShellCommand::QuitApp),
                        _ => None,
                    };
                    if let Some(c) = cmd {
                        let _ = cmd_tx.send(c);
                    }
                }
            }
            LRESULT(0)
        }
        WM_CLOSE => {
            if !ctx_ptr.is_null() {
                // SAFETY: `ctx_ptr` belongs to this live STA window on this thread.
                let ctx_cell = unsafe { &*ctx_ptr };
                if let Ok(mut ctx) = ctx_cell.try_borrow_mut()
                    && ctx.tray_active
                {
                    let tray_nid = ctx.tray_nid;
                    ctx.tray_active = false;
                    drop(ctx);
                    // SAFETY: Unregister tray icon immediately before window destruction.
                    unsafe {
                        let _ = Shell_NotifyIconW(windows::Win32::UI::Shell::NIM_DELETE, &tray_nid);
                    }
                }
            }
            // SAFETY: Triggers `WM_DESTROY` on owned hidden window.
            unsafe {
                let _ = DestroyWindow(hwnd);
            }
            LRESULT(0)
        }
        WM_DESTROY => {
            // SAFETY: Exits `GetMessageW` loop on `lunaria-win-shell`.
            unsafe {
                PostQuitMessage(0);
            }
            LRESULT(0)
        }
        WM_NCDESTROY => {
            // SAFETY: Clear user-data pointer on final non-client teardown message; cleanup frees box after loop exit.
            unsafe {
                let _ = SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
                DefWindowProcW(hwnd, msg, wparam, lparam)
            }
        }
        _ => {
            if !ctx_ptr.is_null() {
                // SAFETY: `ctx_ptr` belongs to this live STA window on this thread.
                let ctx_cell = unsafe { &*ctx_ptr };
                if let Ok(mut ctx) = ctx_cell.try_borrow_mut()
                    && ctx.taskbar_created_msg != 0
                    && msg == ctx.taskbar_created_msg
                {
                    use windows::Win32::UI::Shell::{NIF_ICON, NIF_MESSAGE, NIF_TIP, NIM_ADD};
                    ctx.tray_nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
                    let tray_nid = ctx.tray_nid;
                    drop(ctx);
                    #[expect(
                        unsafe_code,
                        reason = "re-registers tray icon after Explorer TaskbarCreated broadcast"
                    )]
                    // SAFETY: `tray_nid` is initialized and owned by the hidden shell window.
                    let added = unsafe { Shell_NotifyIconW(NIM_ADD, &tray_nid).as_bool() };
                    if let Ok(mut ctx_after) = ctx_cell.try_borrow_mut() {
                        ctx_after.tray_active = added;
                    }
                    return LRESULT(0);
                }
            }
            // SAFETY: Delegates all unhandled messages to default Win32 handler.
            unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) }
        }
    }
}
