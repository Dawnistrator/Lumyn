//! Windows Taskbar `ITaskbarList3` thumbnail toolbar/progress and Explorer `WM_DROPFILES` hook.

use std::fmt;
use std::path::PathBuf;

use crossbeam_channel::Sender;

use crate::{ShellCommand, WinError};

/// Attaches native Windows Taskbar Thumbnail Toolbar buttons (`Prev`, `Play/Pause`, `Next`),
/// Taskbar Progress indicator, and `WM_DROPFILES` Explorer drag-and-drop to the Slint `HWND`.
pub struct MainWindowHook {
    #[cfg(windows)]
    inner: Option<WinMainHook>,
}

impl fmt::Debug for MainWindowHook {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("MainWindowHook").finish_non_exhaustive()
    }
}

#[cfg(windows)]
const HOOK_SUBCLASS_ID: usize = 0x4C59_4D4E;
#[cfg(windows)]
const THUMB_PREV_ID: u32 = 3001;
#[cfg(windows)]
const THUMB_PLAY_PAUSE_ID: u32 = 3002;
#[cfg(windows)]
const THUMB_NEXT_ID: u32 = 3003;

#[cfg(windows)]
struct SubclassPayload {
    cmd_tx: Sender<ShellCommand>,
    taskbar_created_msg: u32,
}

#[cfg(windows)]
struct WinMainHook {
    hwnd_raw: isize,
    payload_ptr: *mut SubclassPayload,
    taskbar: Option<windows::Win32::UI::Shell::ITaskbarList3>,
    prev_icon: windows::Win32::UI::WindowsAndMessaging::HICON,
    play_icon: windows::Win32::UI::WindowsAndMessaging::HICON,
    pause_icon: windows::Win32::UI::WindowsAndMessaging::HICON,
    next_icon: windows::Win32::UI::WindowsAndMessaging::HICON,
    toolbar_ready: bool,
    last_playing_state: Option<bool>,
}

impl MainWindowHook {
    /// Hooks Taskbar controls and Explorer drag-and-drop onto native `raw_hwnd`.
    pub fn attach(raw_hwnd: isize, cmd_tx: Sender<ShellCommand>) -> Result<Self, WinError> {
        #[cfg(windows)]
        {
            attach_win(raw_hwnd, cmd_tx)
        }
        #[cfg(not(windows))]
        {
            let _ = (raw_hwnd, cmd_tx);
            Ok(Self {})
        }
    }

    /// Synchronizes Taskbar Play/Pause icon and Taskbar playback progress bar state.
    pub fn update_playback(
        &mut self,
        is_playing: bool,
        has_track: bool,
        position_ms: u64,
        duration_ms: u64,
    ) {
        #[cfg(windows)]
        if let Some(inner) = self.inner.as_mut() {
            update_win_taskbar(inner, is_playing, has_track, position_ms, duration_ms);
        }
        #[cfg(not(windows))]
        let _ = (is_playing, has_track, position_ms, duration_ms);
    }

    /// Re-registers `ITaskbarList3` thumbnail buttons (e.g. after Explorer `TaskbarButtonCreated`).
    pub fn reinit_taskbar_buttons(&mut self, is_playing: bool) {
        #[cfg(windows)]
        if let Some(inner) = self.inner.as_mut() {
            reinit_win_taskbar(inner, is_playing);
        }
        #[cfg(not(windows))]
        let _ = is_playing;
    }
}

#[cfg(windows)]
impl Drop for MainWindowHook {
    fn drop(&mut self) {
        if let Some(inner) = self.inner.take() {
            #[expect(
                unsafe_code,
                reason = "removes subclass callback, restores drop state, and frees Win32 GDI icons"
            )]
            // SAFETY: `inner.hwnd_raw` and icon handles belong exclusively to this hook lifecycle.
            unsafe {
                let hwnd =
                    windows::Win32::Foundation::HWND(inner.hwnd_raw as *mut core::ffi::c_void);
                if windows::Win32::UI::WindowsAndMessaging::IsWindow(Some(hwnd)).as_bool() {
                    let _ = windows::Win32::UI::Shell::RemoveWindowSubclass(
                        hwnd,
                        Some(main_subclass_proc),
                        HOOK_SUBCLASS_ID,
                    );
                }
                if !inner.payload_ptr.is_null() {
                    drop(Box::from_raw(inner.payload_ptr));
                }
                for icon in [
                    inner.prev_icon,
                    inner.play_icon,
                    inner.pause_icon,
                    inner.next_icon,
                ] {
                    if !icon.is_invalid() {
                        let _ = windows::Win32::UI::WindowsAndMessaging::DestroyIcon(icon);
                    }
                }
            }
        }
    }
}

/// Restores and foregrounds the main application window reliably from tray or IPC wakeup.
pub fn focus_window(raw_hwnd: isize) {
    #[cfg(windows)]
    if raw_hwnd != 0 {
        #[expect(
            unsafe_code,
            reason = "brings restored Lunaria desktop window to foreground"
        )]
        // SAFETY: Caller supplies live top-level `HWND` from Slint `window_handle`.
        unsafe {
            let hwnd = windows::Win32::Foundation::HWND(raw_hwnd as *mut core::ffi::c_void);
            let _ = windows::Win32::UI::WindowsAndMessaging::ShowWindow(
                hwnd,
                windows::Win32::UI::WindowsAndMessaging::SW_RESTORE,
            );
            let _ = windows::Win32::UI::WindowsAndMessaging::SetForegroundWindow(hwnd);
        }
    }
    #[cfg(not(windows))]
    let _ = raw_hwnd;
}

/// Configures native Windows 11 rounded corners and ensures `WS_MINIMIZEBOX` and `WS_MAXIMIZEBOX`
/// are enabled so Taskbar click-to-minimize and Win32 maximize/snap work cleanly on a frameless (`no-frame`) window.
pub fn configure_frameless_window_chrome(raw_hwnd: isize) {
    #[cfg(windows)]
    if raw_hwnd != 0 {
        use windows::Win32::Foundation::HWND;
        use windows::Win32::Graphics::Dwm::{
            DWM_WINDOW_CORNER_PREFERENCE, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND,
            DwmSetWindowAttribute,
        };
        use windows::Win32::UI::WindowsAndMessaging::{
            GWL_STYLE, GetWindowLongPtrW, SetWindowLongPtrW, WS_MAXIMIZEBOX, WS_MINIMIZEBOX,
        };

        let hwnd = HWND(raw_hwnd as *mut core::ffi::c_void);
        #[expect(
            unsafe_code,
            reason = "applies native WS_MINIMIZEBOX/WS_MAXIMIZEBOX styles and DWM rounded corner chrome to top-level HWND"
        )]
        // SAFETY: Caller supplies the live top-level `HWND` owned by the Slint UI thread.
        unsafe {
            let style = GetWindowLongPtrW(hwnd, GWL_STYLE);
            let wanted_flags = (WS_MINIMIZEBOX.0 | WS_MAXIMIZEBOX.0) as isize;
            if style != 0 && (style & wanted_flags) != wanted_flags {
                let _ = SetWindowLongPtrW(hwnd, GWL_STYLE, style | wanted_flags);
            }
            let pref = DWMWCP_ROUND;
            let _ = DwmSetWindowAttribute(
                hwnd,
                DWMWA_WINDOW_CORNER_PREFERENCE,
                (&raw const pref).cast(),
                size_of::<DWM_WINDOW_CORNER_PREFERENCE>() as u32,
            );
        }
    }
    #[cfg(not(windows))]
    let _ = raw_hwnd;
}

/// Synchronizes Windows 11 DWM border accent color (`DWMWA_BORDER_COLOR`) with active Lunaria theme.
pub fn sync_window_border_color(raw_hwnd: isize, rgb: (u8, u8, u8)) {
    #[cfg(windows)]
    if raw_hwnd != 0 {
        use windows::Win32::Foundation::HWND;
        use windows::Win32::Graphics::Dwm::{DWMWA_BORDER_COLOR, DwmSetWindowAttribute};

        let hwnd = HWND(raw_hwnd as *mut core::ffi::c_void);
        let (r, g, b) = rgb;
        let colorref: u32 = u32::from(r) | (u32::from(g) << 8) | (u32::from(b) << 16);
        #[expect(
            unsafe_code,
            reason = "sets DWM border COLORREF on Windows 11 top-level HWND"
        )]
        // SAFETY: `colorref` points to a stack-resident `u32` valid for the synchronous DWM call.
        unsafe {
            let _ = DwmSetWindowAttribute(
                hwnd,
                DWMWA_BORDER_COLOR,
                (&raw const colorref).cast(),
                size_of::<u32>() as u32,
            );
        }
    }
    #[cfg(not(windows))]
    let _ = (raw_hwnd, rgb);
}

/// Initiates native Windows caption dragging on a frameless custom title bar.
pub fn start_window_drag(raw_hwnd: isize) {
    #[cfg(windows)]
    if raw_hwnd != 0 {
        use windows::Win32::Foundation::{HWND, LPARAM, POINT, WPARAM};
        use windows::Win32::UI::Input::KeyboardAndMouse::ReleaseCapture;
        use windows::Win32::UI::WindowsAndMessaging::{
            GetCursorPos, HTCAPTION, PostMessageW, WM_NCLBUTTONDOWN,
        };

        let hwnd = HWND(raw_hwnd as *mut core::ffi::c_void);
        #[expect(
            unsafe_code,
            reason = "delegates custom title bar drag to native Win32 caption move"
        )]
        // SAFETY: `hwnd` is the active top-level window receiving the mouse press on the UI thread.
        unsafe {
            let mut pt = POINT::default();
            let _ = GetCursorPos(&mut pt);
            let packed = u32::from(pt.x as i16 as u16) | (u32::from(pt.y as i16 as u16) << 16);
            let _ = ReleaseCapture();
            let _ = PostMessageW(
                Some(hwnd),
                WM_NCLBUTTONDOWN,
                WPARAM(HTCAPTION as usize),
                LPARAM(packed as usize as isize),
            );
        }
    }
    #[cfg(not(windows))]
    let _ = raw_hwnd;
}

/// Minimizes the main Lunaria desktop window cleanly into the Taskbar.
pub fn minimize_window(raw_hwnd: isize) {
    #[cfg(windows)]
    if raw_hwnd != 0 {
        use windows::Win32::Foundation::HWND;
        use windows::Win32::UI::WindowsAndMessaging::{SW_MINIMIZE, ShowWindow};

        let hwnd = HWND(raw_hwnd as *mut core::ffi::c_void);
        #[expect(
            unsafe_code,
            reason = "minimizes top-level HWND via native ShowWindow(SW_MINIMIZE)"
        )]
        // SAFETY: `hwnd` is the live top-level application window handle.
        unsafe {
            let _ = ShowWindow(hwnd, SW_MINIMIZE);
        }
    }
    #[cfg(not(windows))]
    let _ = raw_hwnd;
}

/// Returns `true` when the main desktop window is currently maximized.
#[must_use]
pub fn is_window_maximized(raw_hwnd: isize) -> bool {
    #[cfg(windows)]
    if raw_hwnd != 0 {
        use windows::Win32::Foundation::HWND;
        use windows::Win32::UI::WindowsAndMessaging::IsZoomed;

        let hwnd = HWND(raw_hwnd as *mut core::ffi::c_void);
        #[expect(
            unsafe_code,
            reason = "queries native IsZoomed state on top-level HWND"
        )]
        // SAFETY: `hwnd` is the live top-level application window handle.
        return unsafe { IsZoomed(hwnd).as_bool() };
    }
    #[cfg(not(windows))]
    let _ = raw_hwnd;
    false
}

/// Toggles the main desktop window between maximized and restored states,
/// returning the resulting maximized state (`true` if maximized).
pub fn toggle_maximize_window(raw_hwnd: isize) -> bool {
    #[cfg(windows)]
    if raw_hwnd != 0 {
        use windows::Win32::Foundation::HWND;
        use windows::Win32::UI::WindowsAndMessaging::{
            IsZoomed, SW_MAXIMIZE, SW_RESTORE, ShowWindow,
        };

        let hwnd = HWND(raw_hwnd as *mut core::ffi::c_void);
        #[expect(
            unsafe_code,
            reason = "toggles top-level HWND between maximized and restored states"
        )]
        // SAFETY: `hwnd` is the live top-level application window handle.
        return unsafe {
            if IsZoomed(hwnd).as_bool() {
                let _ = ShowWindow(hwnd, SW_RESTORE);
                false
            } else {
                let _ = ShowWindow(hwnd, SW_MAXIMIZE);
                true
            }
        };
    }
    #[cfg(not(windows))]
    let _ = raw_hwnd;
    false
}

#[cfg(windows)]
fn encode_tip_260(dst: &mut [u16; 260], text: &str) {
    dst.fill(0);
    for (slot, code_unit) in dst.iter_mut().take(259).zip(text.encode_utf16()) {
        *slot = code_unit;
    }
}

#[cfg(windows)]
fn create_initialized_taskbar_list() -> Option<windows::Win32::UI::Shell::ITaskbarList3> {
    use windows::Win32::System::Com::{CLSCTX_INPROC_SERVER, CoCreateInstance};
    use windows::Win32::UI::Shell::{ITaskbarList3, TaskbarList};

    #[expect(
        unsafe_code,
        reason = "instantiates ITaskbarList3 COM shell taskbar interface on UI STA thread"
    )]
    // SAFETY: Called on UI STA thread where COM is initialized by Slint/winit.
    unsafe {
        match CoCreateInstance::<_, ITaskbarList3>(&TaskbarList, None, CLSCTX_INPROC_SERVER) {
            Ok(tb) if tb.HrInit().is_ok() => Some(tb),
            Ok(_) => None,
            Err(err) => {
                tracing::warn!(error = %err, "ITaskbarList3 unavailable on this session");
                None
            }
        }
    }
}

#[cfg(windows)]
fn init_taskbar_buttons(
    tb: &windows::Win32::UI::Shell::ITaskbarList3,
    hwnd: windows::Win32::Foundation::HWND,
    prev_icon: windows::Win32::UI::WindowsAndMessaging::HICON,
    play_or_pause_icon: windows::Win32::UI::WindowsAndMessaging::HICON,
    next_icon: windows::Win32::UI::WindowsAndMessaging::HICON,
    is_playing: bool,
) -> bool {
    use windows::Win32::UI::Shell::{THB_FLAGS, THB_ICON, THB_TOOLTIP, THBF_ENABLED, THUMBBUTTON};

    let mut b_prev = THUMBBUTTON {
        dwMask: THB_ICON | THB_TOOLTIP | THB_FLAGS,
        iId: THUMB_PREV_ID,
        hIcon: prev_icon,
        dwFlags: THBF_ENABLED,
        ..Default::default()
    };
    encode_tip_260(&mut b_prev.szTip, "Previous Track");

    let mut b_pp = THUMBBUTTON {
        dwMask: THB_ICON | THB_TOOLTIP | THB_FLAGS,
        iId: THUMB_PLAY_PAUSE_ID,
        hIcon: play_or_pause_icon,
        dwFlags: THBF_ENABLED,
        ..Default::default()
    };
    encode_tip_260(&mut b_pp.szTip, if is_playing { "Pause" } else { "Play" });

    let mut b_next = THUMBBUTTON {
        dwMask: THB_ICON | THB_TOOLTIP | THB_FLAGS,
        iId: THUMB_NEXT_ID,
        hIcon: next_icon,
        dwFlags: THBF_ENABLED,
        ..Default::default()
    };
    encode_tip_260(&mut b_next.szTip, "Next Track");

    #[expect(
        unsafe_code,
        reason = "adds ITaskbarList3 thumbnail toolbar buttons to main HWND"
    )]
    // SAFETY: `tb` is an initialized `ITaskbarList3` instance and `hwnd` is a valid top-level window handle.
    unsafe {
        tb.ThumbBarAddButtons(hwnd, &[b_prev, b_pp, b_next]).is_ok()
    }
}

#[cfg(windows)]
fn attach_win(raw_hwnd: isize, cmd_tx: Sender<ShellCommand>) -> Result<MainWindowHook, WinError> {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::Shell::{DragAcceptFiles, SetWindowSubclass};
    use windows::Win32::UI::WindowsAndMessaging::RegisterWindowMessageW;
    use windows::core::PCWSTR;

    if raw_hwnd == 0 {
        return Err(WinError::Platform("null HWND".to_owned()));
    }
    let hwnd = HWND(raw_hwnd as *mut core::ffi::c_void);

    let msg_name: Vec<u16> = "TaskbarButtonCreated\0".encode_utf16().collect();
    #[expect(
        unsafe_code,
        reason = "registers TaskbarButtonCreated Explorer shell message"
    )]
    // SAFETY: `msg_name` is a null-terminated UTF-16 string alive for this call.
    let taskbar_created_msg =
        unsafe { RegisterWindowMessageW(PCWSTR::from_raw(msg_name.as_ptr())) };

    let payload_ptr = Box::into_raw(Box::new(SubclassPayload {
        cmd_tx,
        taskbar_created_msg,
    }));

    #[expect(
        unsafe_code,
        reason = "enables Explorer WM_DROPFILES and subclasses main HWND"
    )]
    // SAFETY: `hwnd` is live on the UI thread and `payload_ptr` remains valid until subclass removal + Drop.
    let subclass_ok = unsafe {
        DragAcceptFiles(hwnd, true);
        SetWindowSubclass(
            hwnd,
            Some(main_subclass_proc),
            HOOK_SUBCLASS_ID,
            payload_ptr as usize,
        )
        .as_bool()
    };

    if !subclass_ok {
        #[expect(
            unsafe_code,
            reason = "frees SubclassPayload on SetWindowSubclass failure"
        )]
        // SAFETY: `payload_ptr` was never registered on failure.
        unsafe {
            drop(Box::from_raw(payload_ptr));
        }
        return Err(WinError::Platform("SetWindowSubclass failed".to_owned()));
    }

    let prev_icon = create_glyph_icon(GlyphKind::Previous);
    let play_icon = create_glyph_icon(GlyphKind::Play);
    let pause_icon = create_glyph_icon(GlyphKind::Pause);
    let next_icon = create_glyph_icon(GlyphKind::Next);

    let taskbar = create_initialized_taskbar_list();
    let toolbar_ready = taskbar
        .as_ref()
        .is_some_and(|tb| init_taskbar_buttons(tb, hwnd, prev_icon, play_icon, next_icon, false));

    Ok(MainWindowHook {
        inner: Some(WinMainHook {
            hwnd_raw: raw_hwnd,
            payload_ptr,
            taskbar,
            prev_icon,
            play_icon,
            pause_icon,
            next_icon,
            toolbar_ready,
            last_playing_state: None,
        }),
    })
}

#[cfg(windows)]
fn reinit_win_taskbar(inner: &mut WinMainHook, is_playing: bool) {
    use windows::Win32::Foundation::HWND;

    if inner.taskbar.is_none() {
        inner.taskbar = create_initialized_taskbar_list();
    }
    let Some(tb) = &inner.taskbar else {
        return;
    };
    let hwnd = HWND(inner.hwnd_raw as *mut core::ffi::c_void);
    let active_icon = if is_playing {
        inner.pause_icon
    } else {
        inner.play_icon
    };
    inner.toolbar_ready = init_taskbar_buttons(
        tb,
        hwnd,
        inner.prev_icon,
        active_icon,
        inner.next_icon,
        is_playing,
    );
    inner.last_playing_state = Some(is_playing);
}

#[cfg(windows)]
fn update_win_taskbar(
    inner: &mut WinMainHook,
    is_playing: bool,
    has_track: bool,
    position_ms: u64,
    duration_ms: u64,
) {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::Shell::{
        TBPF_NOPROGRESS, TBPF_NORMAL, TBPF_PAUSED, THB_FLAGS, THB_ICON, THB_TOOLTIP, THBF_ENABLED,
        THUMBBUTTON,
    };

    let Some(tb) = &inner.taskbar else {
        return;
    };
    let hwnd = HWND(inner.hwnd_raw as *mut core::ffi::c_void);

    #[expect(
        unsafe_code,
        reason = "updates ITaskbarList3 thumbnail play/pause button and progress bar"
    )]
    // SAFETY: `tb` is a live `ITaskbarList3` interface and `hwnd` & GDI `HICON`s belong to `WinMainHook`.
    unsafe {
        if inner.toolbar_ready && inner.last_playing_state != Some(is_playing) {
            inner.last_playing_state = Some(is_playing);
            let mut btn = THUMBBUTTON {
                dwMask: THB_ICON | THB_TOOLTIP | THB_FLAGS,
                iId: THUMB_PLAY_PAUSE_ID,
                hIcon: if is_playing {
                    inner.pause_icon
                } else {
                    inner.play_icon
                },
                dwFlags: THBF_ENABLED,
                ..Default::default()
            };
            encode_tip_260(&mut btn.szTip, if is_playing { "Pause" } else { "Play" });
            let _ = tb.ThumbBarUpdateButtons(hwnd, &[btn]);
        }

        if !has_track || duration_ms == 0 {
            let _ = tb.SetProgressState(hwnd, TBPF_NOPROGRESS);
        } else {
            let state = if is_playing { TBPF_NORMAL } else { TBPF_PAUSED };
            let _ = tb.SetProgressState(hwnd, state);
            let _ = tb.SetProgressValue(hwnd, position_ms.min(duration_ms), duration_ms);
        }
    }
}

#[cfg(windows)]
#[expect(
    unsafe_code,
    reason = "window subclass proc handling WM_NCHITTEST resize/drag, WM_SIZE maximize state, THBN_CLICKED, and WM_DROPFILES"
)]
unsafe extern "system" fn main_subclass_proc(
    hwnd: windows::Win32::Foundation::HWND,
    msg: u32,
    wparam: windows::Win32::Foundation::WPARAM,
    lparam: windows::Win32::Foundation::LPARAM,
    _uidsubclass: usize,
    dwrefdata: usize,
) -> windows::Win32::Foundation::LRESULT {
    use windows::Win32::Foundation::{LRESULT, POINT, RECT};
    use windows::Win32::Graphics::Gdi::ScreenToClient;
    use windows::Win32::UI::HiDpi::GetDpiForWindow;
    use windows::Win32::UI::Shell::{
        DefSubclassProc, DragFinish, DragQueryFileW, HDROP, RemoveWindowSubclass, THBN_CLICKED,
    };
    use windows::Win32::UI::WindowsAndMessaging::{
        GetClientRect, HTBOTTOM, HTBOTTOMLEFT, HTBOTTOMRIGHT, HTCAPTION, HTCLIENT, HTLEFT, HTRIGHT,
        HTTOP, HTTOPLEFT, HTTOPRIGHT, IsZoomed, SIZE_MAXIMIZED, SIZE_RESTORED, WM_COMMAND,
        WM_DROPFILES, WM_NCDESTROY, WM_NCHITTEST, WM_SIZE,
    };

    if msg == WM_NCHITTEST {
        let sx = i32::from((lparam.0 & 0xFFFF) as i16);
        let sy = i32::from(((lparam.0 >> 16) & 0xFFFF) as i16);
        let mut pt = POINT { x: sx, y: sy };
        let mut rect = RECT::default();
        // SAFETY: `hwnd` is a live top-level Win32 window for the duration of `WM_NCHITTEST`.
        let (in_client, zoomed) = unsafe {
            let ok =
                ScreenToClient(hwnd, &mut pt).as_bool() && GetClientRect(hwnd, &mut rect).is_ok();
            let is_max = ok && IsZoomed(hwnd).as_bool();
            (ok, is_max)
        };
        if in_client && pt.x >= 0 && pt.x < rect.right && pt.y >= 0 && pt.y < rect.bottom {
            // SAFETY: `hwnd` is a live top-level Win32 window.
            let raw_dpi = unsafe { GetDpiForWindow(hwnd) };
            let dpi = i32::try_from(raw_dpi).unwrap_or(96).max(96);
            let title_h = ((36 * dpi) / 96).max(36);
            let controls_w = ((138 * dpi) / 96).max(138);
            if !zoomed {
                let edge = ((6 * dpi) / 96).max(6);
                let top_edge = ((5 * dpi) / 96).max(5);
                let on_left = pt.x < edge;
                let on_right = pt.x >= (rect.right - edge);
                let on_top = pt.y < top_edge;
                let on_bottom = pt.y >= (rect.bottom - edge);
                match (on_left, on_right, on_top, on_bottom) {
                    (true, _, true, _) => return LRESULT(HTTOPLEFT as isize),
                    (_, true, true, _) => return LRESULT(HTTOPRIGHT as isize),
                    (true, _, _, true) => return LRESULT(HTBOTTOMLEFT as isize),
                    (_, true, _, true) => return LRESULT(HTBOTTOMRIGHT as isize),
                    (true, _, _, _) => return LRESULT(HTLEFT as isize),
                    (_, true, _, _) => return LRESULT(HTRIGHT as isize),
                    (_, _, true, _) => return LRESULT(HTTOP as isize),
                    (_, _, _, true) => return LRESULT(HTBOTTOM as isize),
                    _ => {}
                }
            }
            if pt.y < title_h {
                if pt.x < (rect.right - controls_w) {
                    return LRESULT(HTCAPTION as isize);
                }
                return LRESULT(HTCLIENT as isize);
            }
        }
    }

    let payload_ptr = dwrefdata as *const SubclassPayload;
    if !payload_ptr.is_null() {
        // SAFETY: `payload_ptr` outlives subclass registration.
        let payload = unsafe { &*payload_ptr };
        if payload.taskbar_created_msg != 0 && msg == payload.taskbar_created_msg {
            let _ = payload.cmd_tx.send(ShellCommand::TaskbarCreated);
        } else if msg == WM_SIZE {
            if wparam.0 == SIZE_MAXIMIZED as usize {
                let _ = payload
                    .cmd_tx
                    .send(ShellCommand::WindowStateChanged { maximized: true });
            } else if wparam.0 == SIZE_RESTORED as usize {
                let _ = payload
                    .cmd_tx
                    .send(ShellCommand::WindowStateChanged { maximized: false });
            }
        } else if msg == WM_COMMAND {
            let hiword = ((wparam.0 >> 16) & 0xFFFF) as u32;
            let loword = (wparam.0 & 0xFFFF) as u32;
            if hiword == THBN_CLICKED {
                let cmd = match loword {
                    THUMB_PREV_ID => Some(ShellCommand::Previous),
                    THUMB_PLAY_PAUSE_ID => Some(ShellCommand::TogglePlayPause),
                    THUMB_NEXT_ID => Some(ShellCommand::Next),
                    _ => None,
                };
                if let Some(c) = cmd {
                    let _ = payload.cmd_tx.send(c);
                }
                return LRESULT(0);
            }
        } else if msg == WM_DROPFILES {
            let hdrop = HDROP(wparam.0 as *mut core::ffi::c_void);
            let mut dropped = Vec::new();
            // SAFETY: `hdrop` is provided by Windows Explorer for `WM_DROPFILES` and freed by `DragFinish`.
            unsafe {
                let count = DragQueryFileW(hdrop, 0xFFFF_FFFF, None);
                for idx in 0..count {
                    let needed = DragQueryFileW(hdrop, idx, None);
                    if needed > 0 {
                        let mut buf = vec![0u16; (needed as usize) + 1];
                        let written = DragQueryFileW(hdrop, idx, Some(&mut buf));
                        if written > 0 {
                            let path_str = String::from_utf16_lossy(&buf[..written as usize]);
                            dropped.push(PathBuf::from(path_str));
                        }
                    }
                }
                DragFinish(hdrop);
            }
            if !dropped.is_empty() {
                let _ = payload.cmd_tx.send(ShellCommand::OpenPaths(dropped));
            }
            return LRESULT(0);
        }
    }

    if msg == WM_NCDESTROY {
        // SAFETY: Detaches our subclass callback cleanly on final HWND destruction message.
        unsafe {
            let _ = RemoveWindowSubclass(hwnd, Some(main_subclass_proc), HOOK_SUBCLASS_ID);
        }
    }

    // SAFETY: Delegates remaining window messages to the default Win32 subclass chain.
    unsafe { DefSubclassProc(hwnd, msg, wparam, lparam) }
}

#[cfg(windows)]
#[derive(Clone, Copy)]
enum GlyphKind {
    Previous,
    Play,
    Pause,
    Next,
}

#[cfg(windows)]
fn create_glyph_icon(kind: GlyphKind) -> windows::Win32::UI::WindowsAndMessaging::HICON {
    use windows::Win32::Graphics::Gdi::{CreateBitmap, DeleteObject};
    use windows::Win32::UI::WindowsAndMessaging::{CreateIconIndirect, ICONINFO};
    use windows::core::BOOL;

    const W: usize = 16;
    const H: usize = 16;
    let mut color_pixels = [0_u32; W * H];
    let mut mask_bits = [0xFF_u8; (W / 8) * H];

    let mut set_px = |x: usize, y: usize| {
        if x < W && y < H {
            // 0xAARRGGBB bright ocean/white crisp icon pixel
            color_pixels[y * W + x] = 0xFF_F5_FA_FC;
            let byte_idx = y * 2 + (x / 8);
            mask_bits[byte_idx] &= !(1 << (7 - (x % 8)));
        }
    };

    match kind {
        GlyphKind::Play => {
            for dy in 0..10 {
                let y = 3 + dy;
                let half = if dy < 5 { dy + 1 } else { 10 - dy };
                for dx in 0..=(half * 2).min(9) {
                    set_px(4 + dx / 2, y);
                    set_px(4 + dx, y);
                }
            }
        }
        GlyphKind::Pause => {
            for y in 3..13 {
                for x in 4..7 {
                    set_px(x, y);
                }
                for x in 9..12 {
                    set_px(x, y);
                }
            }
        }
        GlyphKind::Previous => {
            for y in 3..13 {
                set_px(3, y);
                set_px(4, y);
                let dist = if y < 8 { y - 3 } else { 12 - y };
                for dx in 0..=(dist * 2).min(7) {
                    set_px(12 - dx, y);
                }
            }
        }
        GlyphKind::Next => {
            for y in 3..13 {
                set_px(11, y);
                set_px(12, y);
                let dist = if y < 8 { y - 3 } else { 12 - y };
                for dx in 0..=(dist * 2).min(7) {
                    set_px(3 + dx, y);
                }
            }
        }
    }

    #[expect(
        unsafe_code,
        reason = "builds transient 16x16 GDI bitmaps to synthesize crisp Taskbar HICON"
    )]
    // SAFETY: `color_pixels` and `mask_bits` match the exact 16x16 bitmap dimensions and are freed via `DeleteObject`.
    unsafe {
        let color_bmp = CreateBitmap(
            W as i32,
            H as i32,
            1,
            32,
            Some(color_pixels.as_ptr().cast()),
        );
        let mask_bmp = CreateBitmap(W as i32, H as i32, 1, 1, Some(mask_bits.as_ptr().cast()));
        let info = ICONINFO {
            fIcon: BOOL(1),
            xHotspot: 0,
            yHotspot: 0,
            hbmMask: mask_bmp,
            hbmColor: color_bmp,
        };
        let icon = CreateIconIndirect(&info).unwrap_or_default();
        let _ = DeleteObject(color_bmp.into());
        let _ = DeleteObject(mask_bmp.into());
        icon
    }
}
