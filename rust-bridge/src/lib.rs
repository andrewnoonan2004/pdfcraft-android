use std::cell::RefCell;
use std::collections::{HashMap, VecDeque};
use std::ffi::CStr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, Once, PoisonError};
use std::time::Duration;

use jni::JNIEnv;
use jni::objects::{JObject, JObjectArray, JString};
use jni::sys::{jboolean, jdouble, jfloat, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use pdfcraft_ui_egui::{PdfCraftApp, RecoveryStore, set_saved_file_hook};
use serde::Serialize;

const MOD_ALT: i32 = 1;
const MOD_CTRL: i32 = 2;
const MOD_SHIFT: i32 = 4;
const MOD_MAC_CMD: i32 = 8;
const MOD_COMMAND: i32 = 16;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PickerRequestDto {
    pub id: i64,
    pub mode: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub suggested_name: Option<String>,
    pub extensions: Vec<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedFileEventDto {
    pub path: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PrintJobRequestDto {
    pub job_name: String,
    pub pdf_path: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImeRectDto {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlatformEffectsDto {
    pub cursor_icon: String,
    pub wants_ime: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ime_rect: Option<ImeRectDto>,
    pub repaint_after_ms: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub copy_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub open_url: Option<String>,
    pub window_title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub active_path: Option<String>,
    pub has_unsaved_changes: bool,
    pub close_requested: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub picker_request: Option<PickerRequestDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub saved_file: Option<SavedFileEventDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub print_job: Option<PrintJobRequestDto>,
}

impl Default for PlatformEffectsDto {
    fn default() -> Self {
        Self {
            cursor_icon: "default".to_string(),
            wants_ime: false,
            ime_rect: None,
            repaint_after_ms: -1,
            copy_text: None,
            open_url: None,
            window_title: "PdfCraft".to_string(),
            active_path: Some(String::new()),
            has_unsaved_changes: false,
            close_requested: false,
            picker_request: None,
            saved_file: None,
            print_job: None,
        }
    }
}

static SAVED_PATHS: LazyLock<Mutex<VecDeque<String>>> =
    LazyLock::new(|| Mutex::new(VecDeque::new()));

static PRINT_JOBS: LazyLock<Mutex<VecDeque<PrintJobRequestDto>>> =
    LazyLock::new(|| Mutex::new(VecDeque::new()));

static PRINT_CACHE_DIR: LazyLock<Mutex<Option<PathBuf>>> =
    LazyLock::new(|| Mutex::new(None));

static PRINT_COUNTER: AtomicU64 = AtomicU64::new(1);

static INIT_ONCE: Once = Once::new();

/// File (inside the app's `filesDir`) that receives the last native panic so the Kotlin host can
/// show it on the next launch; there is no logcat on the device for the user to read.
pub const NATIVE_CRASH_FILE_NAME: &str = "native_crash.txt";

static NATIVE_CRASH_LOG: LazyLock<Mutex<Option<PathBuf>>> = LazyLock::new(|| Mutex::new(None));

/// The `android-activity` native-activity glue (pulled in through eframe/winit's Android
/// backend) references `android_main` as a strong undefined symbol. The library is linked with
/// `BIND_NOW`, so leaving it undefined makes `System.loadLibrary` fail and the app die on launch.
/// This host embeds PdfCraft in its own `GLSurfaceView` and never starts a `NativeActivity`, so
/// the entry point is never called.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
fn android_main(_app: android_activity::AndroidApp) {
    log::warn!("android_main called, but PdfCraft is hosted by MainActivity; ignoring");
}

#[cfg(target_os = "android")]
fn record_native_panic(message: &str) {
    let path = NATIVE_CRASH_LOG
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .clone();
    if let Some(path) = path {
        let thread = std::thread::current();
        let name = thread.name().unwrap_or("<unnamed>");
        let backtrace = std::backtrace::Backtrace::force_capture();
        let body = format!("Native panic on thread '{name}':\n{message}\n\nBacktrace:\n{backtrace}\n");
        let _ = std::fs::write(path, body);
    }
}

fn ensure_global_hooks_installed() {
    INIT_ONCE.call_once(|| {
        #[cfg(target_os = "android")]
        {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag("PdfCraftRust"),
            );
            std::panic::set_hook(Box::new(|info| {
                log::error!("PdfCraft native panic caught: {info}");
                record_native_panic(&info.to_string());
            }));
        }

        set_saved_file_hook(Some(Arc::new(|path: &str| {
            let mut q = SAVED_PATHS.lock().unwrap_or_else(PoisonError::into_inner);
            q.push_back(path.to_string());
        })));

        pdfcraft_print::spool::set_submit_hook(Some(Arc::new(|pdf_bytes, job| {
            let base_dir = PRINT_CACHE_DIR
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .clone()
                .unwrap_or_else(std::env::temp_dir);
            let print_dir = base_dir.join("print_spool");
            std::fs::create_dir_all(&print_dir)
                .map_err(|e| format!("failed to create print spool dir: {e}"))?;
            let seq = PRINT_COUNTER.fetch_add(1, Ordering::SeqCst);
            let file_path = print_dir.join(format!("print_job_{seq}.pdf"));
            std::fs::write(&file_path, pdf_bytes)
                .map_err(|e| format!("failed to write spooled print PDF: {e}"))?;
            let job_name = if job.title.trim().is_empty() {
                "PdfCraft Document".to_string()
            } else {
                job.title.clone()
            };
            let mut q = PRINT_JOBS.lock().unwrap_or_else(PoisonError::into_inner);
            q.push_back(PrintJobRequestDto {
                job_name: job_name.clone(),
                pdf_path: file_path.to_string_lossy().into_owned(),
            });
            Ok(job_name)
        })));
    });
}

enum QueuedInput {
    PointerMoved { x_native_pt: f32, y_native_pt: f32 },
    PointerGone,
    PointerButton {
        x_native_pt: f32,
        y_native_pt: f32,
        button: i32,
        pressed: bool,
        modifiers: i32,
    },
    MouseWheel {
        delta_x: f32,
        delta_y: f32,
        is_line_unit: bool,
        modifiers: i32,
    },
    Touch {
        device_id: i32,
        pointer_id: i32,
        phase: i32,
        x_native_pt: f32,
        y_native_pt: f32,
        force: f32,
    },
    Zoom { delta: f32 },
    Key {
        key_code: i32,
        pressed: bool,
        repeat: bool,
        modifiers: i32,
    },
    Text(String),
}

#[derive(Default)]
struct PendingHostQueue {
    inputs: Vec<QueuedInput>,
    commands: Vec<String>,
    external_opens: Vec<(String, String)>,
    ui_scale_override: Option<f32>,
}

struct ThreadBoundCore {
    app: PdfCraftApp,
    ctx: egui::Context,
    painter: Option<egui_glow::Painter>,
    applied_modifiers: egui::Modifiers,
}

impl ThreadBoundCore {
    fn new(
        files_dir: &str,
        initial_prefs_json: Option<&str>,
        wake_flag: Arc<AtomicBool>,
    ) -> Self {
        let ctx = egui::Context::default();
        ctx.set_request_repaint_callback(move |_info| {
            wake_flag.store(true, Ordering::SeqCst);
        });

        let mut app = PdfCraftApp::new();
        app.units = pdfcraft_ui_egui::units::Unit::system();
        app.printer_source = Some(pdfcraft_ui_egui::system_printers());
        app.os_key_store_ids = false;

        if let Some(json) = initial_prefs_json.filter(|s| !s.trim().is_empty()) {
            app.restore(json);
        }

        let recovery_dir = PathBuf::from(files_dir).join("recovery");
        app.enable_recovery(RecoveryStore::new(recovery_dir));
        app.reopen_last_files(&[]);

        Self {
            app,
            ctx,
            painter: None,
            applied_modifiers: egui::Modifiers::NONE,
        }
    }
}

pub struct EngineEntry {
    id: i64,
    files_dir: String,
    initial_prefs_json: Option<String>,
    queue: Mutex<PendingHostQueue>,
    wake_requested: Arc<AtomicBool>,
    persisted_cache: Mutex<String>,
}

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static ENGINES: LazyLock<Mutex<HashMap<i64, Arc<EngineEntry>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

thread_local! {
    static THREAD_CORES: RefCell<HashMap<i64, ThreadBoundCore>> = RefCell::new(HashMap::new());
}

fn get_engine(handle: i64) -> Option<Arc<EngineEntry>> {
    if handle <= 0 {
        return None;
    }
    let map = ENGINES.lock().unwrap_or_else(PoisonError::into_inner);
    map.get(&handle).cloned()
}

impl EngineEntry {
    pub fn new(files_dir: &str, cache_dir: &str, initial_prefs_json: Option<&str>) -> Arc<Self> {
        let id = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
        Self::with_id(id, files_dir, cache_dir, initial_prefs_json)
    }

    fn with_id(
        id: i64,
        files_dir: &str,
        cache_dir: &str,
        initial_prefs_json: Option<&str>,
    ) -> Arc<Self> {
        if !files_dir.is_empty() {
            *NATIVE_CRASH_LOG.lock().unwrap_or_else(PoisonError::into_inner) =
                Some(PathBuf::from(files_dir).join(NATIVE_CRASH_FILE_NAME));
        }
        ensure_global_hooks_installed();
        *PRINT_CACHE_DIR.lock().unwrap_or_else(PoisonError::into_inner) =
            Some(PathBuf::from(cache_dir));

        let seed_cache = initial_prefs_json
            .filter(|s| !s.trim().is_empty())
            .map(ToOwned::to_owned)
            .unwrap_or_else(|| r#"{"theme":"System"}"#.to_string());

        Arc::new(Self {
            id,
            files_dir: files_dir.to_string(),
            initial_prefs_json: initial_prefs_json.map(ToOwned::to_owned),
            queue: Mutex::new(PendingHostQueue::default()),
            wake_requested: Arc::new(AtomicBool::new(true)),
            persisted_cache: Mutex::new(seed_cache),
        })
    }

    pub fn handle(&self) -> i64 {
        self.id
    }

    pub fn request_wake(&self) {
        self.wake_requested.store(true, Ordering::SeqCst);
    }

    pub fn consume_wake(&self) -> bool {
        self.wake_requested.swap(false, Ordering::SeqCst)
    }

    fn enqueue_input(&self, item: QueuedInput) {
        {
            let mut q = self.queue.lock().unwrap_or_else(PoisonError::into_inner);
            q.inputs.push(item);
        }
        self.request_wake();
    }

    pub fn execute_command(&self, command_id: String) {
        {
            let mut q = self.queue.lock().unwrap_or_else(PoisonError::into_inner);
            q.commands.push(command_id);
        }
        self.request_wake();
    }

    pub fn open_external_file(&self, display_name: String, local_path: String) {
        {
            let mut q = self.queue.lock().unwrap_or_else(PoisonError::into_inner);
            q.external_opens.push((display_name, local_path));
        }
        self.request_wake();
    }

    pub fn set_ui_scale_override(&self, scale: f32) {
        if scale.is_finite() && (0.5..=3.0).contains(&scale) {
            {
                let mut q = self.queue.lock().unwrap_or_else(PoisonError::into_inner);
                q.ui_scale_override = Some(scale);
            }
            self.request_wake();
        }
    }

    pub fn persist_state(&self) -> String {
        let from_thread_local = THREAD_CORES.with(|cell| {
            cell.borrow().get(&self.id).map(|core| core.app.persist())
        });
        if let Some(fresh) = from_thread_local {
            *self
                .persisted_cache
                .lock()
                .unwrap_or_else(PoisonError::into_inner) = fresh.clone();
            return fresh;
        }
        self.persisted_cache
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }

    fn ensure_painter_on_gl_thread(&self) {
        THREAD_CORES.with(|cell| {
            let mut map = cell.borrow_mut();
            let core = map.entry(self.id).or_insert_with(|| {
                ThreadBoundCore::new(
                    &self.files_dir,
                    self.initial_prefs_json.as_deref(),
                    Arc::clone(&self.wake_requested),
                )
            });
            if let Some(mut old) = core.painter.take() {
                old.destroy();
            }
            match create_gles_painter() {
                Ok(painter) => {
                    core.painter = Some(painter);
                }
                Err(e) => {
                    log::error!("Failed to initialize egui_glow Painter for {}: {e}", self.id);
                }
            }
        });
        self.request_wake();
    }

    fn destroy_painter_on_gl_thread(&self) {
        THREAD_CORES.with(|cell| {
            if let Some(core) = cell.borrow_mut().get_mut(&self.id)
                && let Some(mut painter) = core.painter.take()
            {
                painter.destroy();
            }
        });
    }

    pub fn step_headless_or_gl(
        &self,
        paint_gl: bool,
        time_secs: f64,
        width_px: u32,
        height_px: u32,
        pixels_per_point: f32,
        dark_mode: bool,
        focused: bool,
    ) -> PlatformEffectsDto {
        let safe_width = width_px.max(1);
        let safe_height = height_px.max(1);
        let native_ppp = if pixels_per_point.is_finite() && pixels_per_point > 0.05 {
            pixels_per_point
        } else {
            1.0
        };

        let drained = {
            let mut q = self.queue.lock().unwrap_or_else(PoisonError::into_inner);
            PendingHostQueue {
                inputs: std::mem::take(&mut q.inputs),
                commands: std::mem::take(&mut q.commands),
                external_opens: std::mem::take(&mut q.external_opens),
                ui_scale_override: q.ui_scale_override.take(),
            }
        };

        let had_mutations = !drained.commands.is_empty()
            || !drained.external_opens.is_empty()
            || drained.ui_scale_override.is_some();

        let (effects, updated_persist) = THREAD_CORES.with(|cell| {
            let mut map = cell.borrow_mut();
            let core = map.entry(self.id).or_insert_with(|| {
                ThreadBoundCore::new(
                    &self.files_dir,
                    self.initial_prefs_json.as_deref(),
                    Arc::clone(&self.wake_requested),
                )
            });

            if let Some(scale) = drained.ui_scale_override {
                core.app.ui_scale = None;
                core.app.system_text_scale = scale;
            }

            for (_display_name, local_path) in drained.external_opens {
                if !local_path.trim().is_empty() {
                    core.app.open_path(&local_path);
                }
            }

            for raw_cmd in drained.commands {
                let mapped = map_host_command(&raw_cmd);
                if !mapped.is_empty() {
                    core.app.execute(mapped);
                }
            }

            let zoom_factor = core.ctx.zoom_factor().max(0.1);
            let effective_ppp = (native_ppp * zoom_factor).max(0.1);
            let width_pt = safe_width as f32 / effective_ppp;
            let height_pt = safe_height as f32 / effective_ppp;

            let mut events = Vec::with_capacity(drained.inputs.len() * 2);
            for item in drained.inputs {
                translate_queued_input(
                    item,
                    zoom_factor,
                    &mut core.applied_modifiers,
                    &mut events,
                );
            }

            let make_raw_input = |pass_events: Vec<egui::Event>, t: f64| {
                let mut raw = egui::RawInput {
                    screen_rect: Some(egui::Rect::from_min_size(
                        egui::Pos2::ZERO,
                        egui::Vec2::new(width_pt, height_pt),
                    )),
                    time: Some(t.max(0.0)),
                    events: pass_events,
                    focused,
                    system_theme: Some(if dark_mode {
                        egui::Theme::Dark
                    } else {
                        egui::Theme::Light
                    }),
                    ..Default::default()
                };
                if let Some(vp) = raw.viewports.get_mut(&egui::ViewportId::ROOT) {
                    vp.native_pixels_per_point = Some(native_ppp);
                    vp.focused = Some(focused);
                    vp.inner_rect = raw.screen_rect;
                }
                raw
            };

            let mut full_output = core
                .app
                .step_host_frame(&core.ctx, make_raw_input(events, time_secs));

            // First-frame warmup: PdfCraftApp installs custom fonts on pass 1 and renders UI
            // starting on pass 2 (`fonts_ready = true`). Merging `textures_delta` renders on frame 1!
            if !full_output.textures_delta.is_empty() && full_output.shapes.is_empty() {
                let mut warmup_delta = std::mem::take(&mut full_output.textures_delta);
                let second_out = core
                    .app
                    .step_host_frame(&core.ctx, make_raw_input(Vec::new(), time_secs + 0.001));
                warmup_delta.append(second_out.textures_delta);
                full_output = egui::FullOutput {
                    textures_delta: warmup_delta,
                    ..second_out
                };
            }

            if paint_gl
                && let Some(painter) = core.painter.as_mut()
            {
                let clipped_primitives = core
                    .ctx
                    .tessellate(std::mem::take(&mut full_output.shapes), full_output.pixels_per_point);
                let clear_rgba = if dark_mode {
                    [0.098_f32, 0.106, 0.122, 1.0]
                } else {
                    [0.945_f32, 0.949, 0.957, 1.0]
                };
                painter.clear([safe_width, safe_height], clear_rgba);
                painter.paint_and_update_textures(
                    [safe_width, safe_height],
                    full_output.pixels_per_point,
                    &clipped_primitives,
                    &mut full_output.textures_delta,
                );
            } else {
                full_output.textures_delta.clear();
            }

            let fx = extract_platform_effects(&core.app, full_output);
            let persisted = if had_mutations || fx.saved_file.is_some() || fx.close_requested {
                Some(core.app.persist())
            } else {
                None
            };
            (fx, persisted)
        });

        if let Some(json) = updated_persist {
            *self
                .persisted_cache
                .lock()
                .unwrap_or_else(PoisonError::into_inner) = json;
        }
        effects
    }
}

fn map_host_command(command_id: &str) -> &str {
    match command_id.trim() {
        "app.command_palette" => "view.palette",
        other => other,
    }
}

fn extract_platform_effects(
    app: &PdfCraftApp,
    full_output: egui::FullOutput,
) -> PlatformEffectsDto {
    let cursor_icon = map_cursor_icon(full_output.platform_output.cursor_icon).to_string();

    let (wants_ime, ime_rect) = match full_output.platform_output.ime {
        Some(ime) => (
            true,
            Some(ImeRectDto {
                x: ime.rect.min.x,
                y: ime.rect.min.y,
                width: ime.rect.width(),
                height: ime.rect.height(),
            }),
        ),
        None => (false, None),
    };

    let mut copy_text: Option<String> = None;
    let mut open_url: Option<String> = None;
    for cmd in full_output.platform_output.commands {
        match cmd {
            egui::OutputCommand::CopyText(text) => {
                arboard::android_set_clipboard_text(text.clone());
                copy_text = Some(text);
            }
            egui::OutputCommand::CopyImage(color_image) => {
                if let Ok(mut cb) = arboard::Clipboard::new() {
                    let bytes: Vec<u8> = color_image
                        .pixels
                        .iter()
                        .flat_map(|c| c.to_array())
                        .collect();
                    let _ = cb.set_image(arboard::ImageData {
                        width: color_image.size[0],
                        height: color_image.size[1],
                        bytes: std::borrow::Cow::Owned(bytes),
                    });
                }
            }
            egui::OutputCommand::OpenUrl(url_cmd) => {
                open_url = Some(url_cmd.url);
            }
            _ => {}
        }
    }
    if copy_text.is_none() {
        copy_text = arboard::android_take_copied_text();
    } else {
        let _ = arboard::android_take_copied_text();
    }

    let mut repaint_after_ms: i64 = -1;
    let mut close_requested = false;
    let mut cancel_close = false;

    if let Some(root_out) = full_output.viewport_output.get(&egui::ViewportId::ROOT) {
        repaint_after_ms = duration_to_repaint_ms(root_out.repaint_delay);
        for cmd in &root_out.commands {
            match cmd {
                egui::ViewportCommand::Close => close_requested = true,
                egui::ViewportCommand::CancelClose => cancel_close = true,
                _ => {}
            }
        }
    }
    if cancel_close {
        close_requested = false;
    }

    let picker_request = rfd::take_pending_picker_request().map(|req| PickerRequestDto {
        id: req.id as i64,
        mode: req.mode.to_string(),
        title: req.title,
        suggested_name: req.suggested_name,
        extensions: req.extensions,
    });

    let saved_file = {
        let mut q = SAVED_PATHS.lock().unwrap_or_else(PoisonError::into_inner);
        q.pop_front().map(|path| SavedFileEventDto { path })
    };

    let print_job = {
        let mut q = PRINT_JOBS.lock().unwrap_or_else(PoisonError::into_inner);
        q.pop_front()
    };

    let window_title = if app.window_title.trim().is_empty() {
        "PdfCraft".to_string()
    } else {
        app.window_title.clone()
    };

    let active_path = Some(app.active_document_path().unwrap_or_default());
    let has_unsaved_changes = app.any_unsaved_work();

    PlatformEffectsDto {
        cursor_icon,
        wants_ime,
        ime_rect,
        repaint_after_ms,
        copy_text,
        open_url,
        window_title,
        active_path,
        has_unsaved_changes,
        close_requested,
        picker_request,
        saved_file,
        print_job,
    }
}

fn duration_to_repaint_ms(delay: Duration) -> i64 {
    if delay.is_zero() {
        0
    } else if delay >= Duration::from_secs(3600) {
        -1
    } else {
        i64::try_from(delay.as_millis()).unwrap_or(1000).min(60_000)
    }
}

fn decode_modifiers(bits: i32) -> egui::Modifiers {
    egui::Modifiers {
        alt: (bits & MOD_ALT) != 0,
        ctrl: (bits & MOD_CTRL) != 0,
        shift: (bits & MOD_SHIFT) != 0,
        mac_cmd: (bits & MOD_MAC_CMD) != 0,
        command: (bits & MOD_COMMAND) != 0 || (bits & MOD_CTRL) != 0 || (bits & MOD_MAC_CMD) != 0,
    }
}

fn sync_modifiers_event(
    next: egui::Modifiers,
    applied: &mut egui::Modifiers,
    out: &mut Vec<egui::Event>,
) {
    if *applied != next {
        *applied = next;
        out.push(egui::Event::ModifiersChanged(next));
    }
}

fn decode_pointer_button(button: i32) -> egui::PointerButton {
    match button {
        1 => egui::PointerButton::Secondary,
        2 => egui::PointerButton::Middle,
        3 => egui::PointerButton::Extra1,
        4 => egui::PointerButton::Extra2,
        _ => egui::PointerButton::Primary,
    }
}

fn decode_touch_phase(phase: i32) -> egui::TouchPhase {
    match phase {
        0 => egui::TouchPhase::Start,
        1 => egui::TouchPhase::Move,
        2 => egui::TouchPhase::End,
        _ => egui::TouchPhase::Cancel,
    }
}

fn decode_egui_key(code: i32) -> Option<egui::Key> {
    use egui::Key;
    Some(match code {
        1 => Key::ArrowDown,
        2 => Key::ArrowLeft,
        3 => Key::ArrowRight,
        4 => Key::ArrowUp,
        5 => Key::Escape,
        6 => Key::Tab,
        7 => Key::Backspace,
        8 => Key::Enter,
        9 => Key::Space,
        10 => Key::Insert,
        11 => Key::Delete,
        12 => Key::Home,
        13 => Key::End,
        14 => Key::PageUp,
        15 => Key::PageDown,
        16 => Key::Copy,
        17 => Key::Cut,
        18 => Key::Paste,
        19 => Key::Minus,
        20 => Key::Plus,
        21 => Key::Equals,
        22 => Key::OpenBracket,
        23 => Key::CloseBracket,
        24 => Key::Backslash,
        25 => Key::Semicolon,
        26 => Key::Quote,
        27 => Key::Comma,
        28 => Key::Period,
        29 => Key::Slash,
        30 => Key::Backtick,
        31 => Key::Num0,
        32 => Key::Num1,
        33 => Key::Num2,
        34 => Key::Num3,
        35 => Key::Num4,
        36 => Key::Num5,
        37 => Key::Num6,
        38 => Key::Num7,
        39 => Key::Num8,
        40 => Key::Num9,
        41 => Key::A,
        42 => Key::B,
        43 => Key::C,
        44 => Key::D,
        45 => Key::E,
        46 => Key::F,
        47 => Key::G,
        48 => Key::H,
        49 => Key::I,
        50 => Key::J,
        51 => Key::K,
        52 => Key::L,
        53 => Key::M,
        54 => Key::N,
        55 => Key::O,
        56 => Key::P,
        57 => Key::Q,
        58 => Key::R,
        59 => Key::S,
        60 => Key::T,
        61 => Key::U,
        62 => Key::V,
        63 => Key::W,
        64 => Key::X,
        65 => Key::Y,
        66 => Key::Z,
        67 => Key::F1,
        68 => Key::F2,
        69 => Key::F3,
        70 => Key::F4,
        71 => Key::F5,
        72 => Key::F6,
        73 => Key::F7,
        74 => Key::F8,
        75 => Key::F9,
        76 => Key::F10,
        77 => Key::F11,
        78 => Key::F12,
        _ => return None,
    })
}

fn map_cursor_icon(icon: egui::CursorIcon) -> &'static str {
    use egui::CursorIcon as C;
    match icon {
        C::Default => "default",
        C::None => "none",
        C::ContextMenu => "context_menu",
        C::Help => "help",
        C::PointingHand => "pointing_hand",
        C::Progress => "progress",
        C::Wait => "wait",
        C::Cell => "cell",
        C::Crosshair => "crosshair",
        C::Text => "text",
        C::VerticalText => "vertical_text",
        C::Alias => "alias",
        C::Copy => "copy",
        C::Move => "move",
        C::NoDrop => "no_drop",
        C::NotAllowed => "not_allowed",
        C::Grab => "grab",
        C::Grabbing => "grabbing",
        C::AllScroll => "all_scroll",
        C::ResizeHorizontal => "resize_horizontal",
        C::ResizeNeSw => "resize_ne_sw",
        C::ResizeNwSe => "resize_nw_se",
        C::ResizeVertical => "resize_vertical",
        C::ResizeEast => "resize_east",
        C::ResizeSouthEast => "resize_south_east",
        C::ResizeSouth => "resize_south",
        C::ResizeSouthWest => "resize_south_west",
        C::ResizeWest => "resize_west",
        C::ResizeNorthWest => "resize_north_west",
        C::ResizeNorth => "resize_north",
        C::ResizeNorthEast => "resize_north_east",
        C::ResizeColumn => "resize_column",
        C::ResizeRow => "resize_row",
        C::ZoomIn => "zoom_in",
        C::ZoomOut => "zoom_out",
    }
}

fn translate_queued_input(
    item: QueuedInput,
    zoom_factor: f32,
    applied_modifiers: &mut egui::Modifiers,
    out: &mut Vec<egui::Event>,
) {
    let z = zoom_factor.max(0.1);
    match item {
        QueuedInput::PointerMoved {
            x_native_pt,
            y_native_pt,
        } => {
            out.push(egui::Event::PointerMoved(egui::Pos2::new(
                x_native_pt / z,
                y_native_pt / z,
            )));
        }
        QueuedInput::PointerGone => {
            out.push(egui::Event::PointerGone);
        }
        QueuedInput::PointerButton {
            x_native_pt,
            y_native_pt,
            button,
            pressed,
            modifiers,
        } => {
            let mods = decode_modifiers(modifiers);
            sync_modifiers_event(mods, applied_modifiers, out);
            out.push(egui::Event::PointerButton {
                pos: egui::Pos2::new(x_native_pt / z, y_native_pt / z),
                button: decode_pointer_button(button),
                pressed,
                modifiers: mods,
            });
        }
        QueuedInput::MouseWheel {
            delta_x,
            delta_y,
            is_line_unit,
            modifiers,
        } => {
            let mods = decode_modifiers(modifiers);
            sync_modifiers_event(mods, applied_modifiers, out);
            let unit = if is_line_unit {
                egui::MouseWheelUnit::Line
            } else {
                egui::MouseWheelUnit::Point
            };
            let delta = if is_line_unit {
                egui::Vec2::new(delta_x, delta_y)
            } else {
                egui::Vec2::new(delta_x / z, delta_y / z)
            };
            out.push(egui::Event::MouseWheel {
                unit,
                delta,
                phase: egui::TouchPhase::Move,
                modifiers: mods,
                source: egui::MouseWheelSource::Unknown,
            });
        }
        QueuedInput::Touch {
            device_id,
            pointer_id,
            phase,
            x_native_pt,
            y_native_pt,
            force,
        } => {
            out.push(egui::Event::Touch {
                device_id: egui::TouchDeviceId(device_id.max(0) as u64),
                id: egui::TouchId(pointer_id.max(0) as u64),
                phase: decode_touch_phase(phase),
                pos: egui::Pos2::new(x_native_pt / z, y_native_pt / z),
                force: (force.is_finite() && force > 0.0).then_some(force),
            });
        }
        QueuedInput::Zoom { delta } => {
            if delta.is_finite() && delta > 0.0 {
                out.push(egui::Event::Zoom(delta));
            }
        }
        QueuedInput::Key {
            key_code,
            pressed,
            repeat,
            modifiers,
        } => {
            if let Some(key) = decode_egui_key(key_code) {
                let mods = decode_modifiers(modifiers);
                sync_modifiers_event(mods, applied_modifiers, out);
                if pressed && !repeat {
                    let exact_cmd = mods.command && !mods.shift && !mods.alt;
                    if key == egui::Key::Copy || (exact_cmd && key == egui::Key::C) {
                        out.push(egui::Event::Copy);
                    } else if key == egui::Key::Cut || (exact_cmd && key == egui::Key::X) {
                        out.push(egui::Event::Cut);
                    } else if key == egui::Key::Paste || (exact_cmd && key == egui::Key::V) {
                        if let Some(text) = arboard::android_peek_clipboard_text()
                            && !text.is_empty()
                        {
                            out.push(egui::Event::Paste(text));
                        }
                    }
                }
                out.push(egui::Event::Key {
                    key,
                    physical_key: Some(key),
                    pressed,
                    repeat,
                    modifiers: mods,
                });
            }
        }
        QueuedInput::Text(text) => {
            if !text.is_empty() {
                out.push(egui::Event::Text(text));
            }
        }
    }
}

fn create_gles_painter() -> Result<egui_glow::Painter, String> {
    // SAFETY: Loading libEGL.so and libGLESv3.so via dlopen/dlsym on an active EGL thread
    // created by Android's GLSurfaceView (EGLContextClientVersion 3). Null pointers are checked
    // at every step before transmuting or invoking `eglGetProcAddress`.
    let gl = unsafe {
        let egl_handle = libc::dlopen(c"libEGL.so".as_ptr(), libc::RTLD_LAZY | libc::RTLD_LOCAL);
        let gles_handle = {
            let h3 = libc::dlopen(c"libGLESv3.so".as_ptr(), libc::RTLD_LAZY | libc::RTLD_LOCAL);
            if !h3.is_null() {
                h3
            } else {
                libc::dlopen(c"libGLESv2.so".as_ptr(), libc::RTLD_LAZY | libc::RTLD_LOCAL)
            }
        };

        type EglGetProcAddressFn =
            unsafe extern "C" fn(*const libc::c_char) -> *const libc::c_void;
        let egl_get_proc: Option<EglGetProcAddressFn> = if !egl_handle.is_null() {
            let sym = libc::dlsym(egl_handle, c"eglGetProcAddress".as_ptr());
            if sym.is_null() {
                None
            } else {
                Some(std::mem::transmute::<
                    *mut libc::c_void,
                    EglGetProcAddressFn,
                >(sym))
            }
        } else {
            None
        };

        Arc::new(glow::Context::from_loader_function_cstr(|name: &CStr| {
            if let Some(get_proc) = egl_get_proc {
                let p = get_proc(name.as_ptr());
                if !p.is_null() {
                    return p;
                }
            }
            if !gles_handle.is_null() {
                let sym = libc::dlsym(gles_handle, name.as_ptr());
                if !sym.is_null() {
                    return sym as *const libc::c_void;
                }
            }
            std::ptr::null()
        }))
    };

    egui_glow::Painter::new(gl, "", None, false).map_err(|e| e.to_string())
}

fn jstring_to_rust(env: &mut JNIEnv<'_>, s: &JString<'_>) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn rust_to_jstring<'a>(env: &'a JNIEnv<'a>, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_createEngine(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    files_dir: JString<'_>,
    cache_dir: JString<'_>,
    initial_prefs_json: JString<'_>,
) -> jlong {
    let files = jstring_to_rust(&mut env, &files_dir).unwrap_or_default();
    let cache = jstring_to_rust(&mut env, &cache_dir).unwrap_or_default();
    let prefs = jstring_to_rust(&mut env, &initial_prefs_json);
    let entry = EngineEntry::new(&files, &cache, prefs.as_deref());
    let handle = entry.handle();
    ENGINES
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .insert(handle, entry);
    handle
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_destroyEngine(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    THREAD_CORES.with(|cell| {
        if let Some(mut core) = cell.borrow_mut().remove(&handle)
            && let Some(mut painter) = core.painter.take()
        {
            painter.destroy();
        }
    });
    ENGINES
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .remove(&handle);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_onSurfaceCreated(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    if let Some(engine) = get_engine(handle) {
        engine.ensure_painter_on_gl_thread();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_onSurfaceChanged(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    _width_px: jint,
    _height_px: jint,
    _pixels_per_point: jfloat,
) {
    if let Some(engine) = get_engine(handle) {
        engine.request_wake();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_onSurfaceDestroyed(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    if let Some(engine) = get_engine(handle) {
        engine.destroy_painter_on_gl_thread();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_stepFrame(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    time_secs: jdouble,
    width_px: jint,
    height_px: jint,
    pixels_per_point: jfloat,
    dark_mode: jboolean,
    focused: jboolean,
) -> jstring {
    let effects = match get_engine(handle) {
        Some(engine) => engine.step_headless_or_gl(
            true,
            time_secs,
            width_px.max(1) as u32,
            height_px.max(1) as u32,
            pixels_per_point,
            dark_mode != JNI_FALSE,
            focused != JNI_FALSE,
        ),
        None => PlatformEffectsDto::default(),
    };
    let json = serde_json::to_string(&effects).unwrap_or_else(|_| "{}".to_string());
    rust_to_jstring(&env, &json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_consumeWakeRequested(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) -> jboolean {
    if get_engine(handle).is_some_and(|e| e.consume_wake()) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_pushPointerMoved(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    x_pt: jfloat,
    y_pt: jfloat,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::PointerMoved {
            x_native_pt: x_pt,
            y_native_pt: y_pt,
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_pushPointerGone(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::PointerGone);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_pushPointerButton(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    x_pt: jfloat,
    y_pt: jfloat,
    button: jint,
    pressed: jboolean,
    modifiers: jint,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::PointerButton {
            x_native_pt: x_pt,
            y_native_pt: y_pt,
            button,
            pressed: pressed != JNI_FALSE,
            modifiers,
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_pushMouseWheel(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    delta_x: jfloat,
    delta_y: jfloat,
    is_line_unit: jboolean,
    modifiers: jint,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::MouseWheel {
            delta_x,
            delta_y,
            is_line_unit: is_line_unit != JNI_FALSE,
            modifiers,
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_pushTouch(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    device_id: jint,
    pointer_id: jint,
    phase: jint,
    x_pt: jfloat,
    y_pt: jfloat,
    force: jfloat,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::Touch {
            device_id,
            pointer_id,
            phase,
            x_native_pt: x_pt,
            y_native_pt: y_pt,
            force,
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_pushZoom(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    zoom_delta: jfloat,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::Zoom { delta: zoom_delta });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_pushKey(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    egui_key_code: jint,
    pressed: jboolean,
    repeat: jboolean,
    modifiers: jint,
) {
    if let Some(engine) = get_engine(handle) {
        engine.enqueue_input(QueuedInput::Key {
            key_code: egui_key_code,
            pressed: pressed != JNI_FALSE,
            repeat: repeat != JNI_FALSE,
            modifiers,
        });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_pushText(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    text: JString<'_>,
) {
    if let (Some(engine), Some(s)) = (get_engine(handle), jstring_to_rust(&mut env, &text)) {
        engine.enqueue_input(QueuedInput::Text(s));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_syncSystemClipboardText(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    text: JString<'_>,
) {
    if let Some(s) = jstring_to_rust(&mut env, &text) {
        arboard::android_set_clipboard_text(s);
    }
    if let Some(engine) = get_engine(handle) {
        engine.request_wake();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_deliverPickedFiles(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    request_id: jlong,
    paths: JObjectArray<'_>,
) {
    let mut out_paths = Vec::new();
    if !paths.is_null()
        && let Ok(len) = env.get_array_length(&paths)
    {
        for i in 0..len {
            if let Ok(elem) = env.get_object_array_element(&paths, i) {
                let js = JString::from(elem);
                if let Some(s) = jstring_to_rust(&mut env, &js)
                    && !s.trim().is_empty()
                {
                    out_paths.push(PathBuf::from(s));
                }
            }
        }
    }
    if request_id > 0 {
        rfd::deliver_picker_result(request_id as u64, out_paths);
    }
    if let Some(engine) = get_engine(handle) {
        engine.request_wake();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_openExternalFile(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    display_name: JString<'_>,
    local_path: JString<'_>,
) {
    let name = jstring_to_rust(&mut env, &display_name).unwrap_or_default();
    let path = jstring_to_rust(&mut env, &local_path).unwrap_or_default();
    if let Some(engine) = get_engine(handle) {
        engine.open_external_file(name, path);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_executeCommand(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    command_id: JString<'_>,
) {
    if let (Some(engine), Some(cmd)) = (get_engine(handle), jstring_to_rust(&mut env, &command_id))
    {
        engine.execute_command(cmd);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_setUiScaleOverride(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    scale: jfloat,
) {
    if let Some(engine) = get_engine(handle) {
        engine.set_ui_scale_override(scale);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andrewnoonan_pdfcraft_bridge_JniPdfCraftNativeBridge_persistState(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) -> jstring {
    let json = get_engine(handle)
        .map(|e| e.persist_state())
        .unwrap_or_default();
    rust_to_jstring(&env, &json)
}

#[cfg(test)]
mod tests {
    use super::*;

    static TEST_SERIAL: Mutex<()> = Mutex::new(());

    fn temp_test_dirs(tag: &str) -> (PathBuf, PathBuf) {
        let root = std::env::temp_dir().join(format!(
            "pdfcraft_android_bridge_test_{tag}_{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&root);
        let files = root.join("files");
        let cache = root.join("cache");
        std::fs::create_dir_all(&files).unwrap();
        std::fs::create_dir_all(&cache).unwrap();
        (files, cache)
    }

    #[test]
    fn headless_engine_boots_renders_home_and_opens_command_palette() {
        let _guard = TEST_SERIAL.lock().unwrap_or_else(PoisonError::into_inner);
        let (files, cache) = temp_test_dirs("boot");
        let engine = EngineEntry::new(
            &files.to_string_lossy(),
            &cache.to_string_lossy(),
            None,
        );

        let effects = engine.step_headless_or_gl(false, 0.1, 1440, 900, 1.5, true, true);
        assert_eq!(effects.window_title, "PdfCraft");
        assert_eq!(effects.active_path.as_deref(), Some(""));
        assert!(!effects.has_unsaved_changes);

        engine.execute_command("app.command_palette".to_string());
        let first_palette_frame = engine.step_headless_or_gl(false, 0.2, 1440, 900, 1.5, true, true);
        assert_eq!(first_palette_frame.repaint_after_ms, 0);
        let palette_effects = engine.step_headless_or_gl(false, 0.216, 1440, 900, 1.5, true, true);
        assert!(
            palette_effects.wants_ime,
            "Command palette should focus search TextEdit and request IME"
        );

        let persisted = engine.persist_state();
        assert!(persisted.contains("theme"));
        let _ = std::fs::remove_dir_all(files.parent().unwrap());
    }

    #[test]
    fn create_blank_edit_save_via_picker_triggers_saved_file_event() {
        let _guard = TEST_SERIAL.lock().unwrap_or_else(PoisonError::into_inner);
        rfd::reset_picker_registry();
        SAVED_PATHS.lock().unwrap_or_else(PoisonError::into_inner).clear();

        let (files, cache) = temp_test_dirs("save_flow");
        let engine = EngineEntry::new(
            &files.to_string_lossy(),
            &cache.to_string_lossy(),
            None,
        );
        let _ = engine.step_headless_or_gl(false, 0.1, 1440, 900, 1.0, false, true);

        // Create a blank PDF document -> marks document dirty!
        engine.execute_command("create.blank".to_string());
        let after_create = engine.step_headless_or_gl(false, 0.2, 1440, 900, 1.0, false, true);
        assert!(after_create.window_title.contains("PdfCraft"));
        assert!(after_create.has_unsaved_changes);

        // Trigger Save -> since blank PDF has no path yet, PdfCraft opens AsyncFileDialog::save_file!
        engine.execute_command("file.save".to_string());
        let save_ask = engine.step_headless_or_gl(false, 0.3, 1440, 900, 1.0, false, true);
        let picker = save_ask.picker_request.expect("Expected save_file PickerRequest");
        assert_eq!(picker.mode, "save_file");

        // Deliver target path from simulated Android SAF CreateDocument!
        let _ = engine.consume_wake();
        let out_pdf = files.join("created_on_android.pdf");
        assert!(rfd::deliver_picker_result(
            picker.id as u64,
            vec![out_pdf.clone()]
        ));

        // Step frames as Choreographer does until the worker thread's Picked closure runs save_doc_to
        let mut found_saved = None;
        for step in 0..30 {
            assert!(
                save_ask.repaint_after_ms == 0 || engine.consume_wake() || step > 0,
                "Engine must either request immediate repaint (0ms) or signal wake"
            );
            let frame_out = engine.step_headless_or_gl(
                false,
                0.4 + (step as f64) * 0.016,
                1440,
                900,
                1.0,
                false,
                true,
            );
            if frame_out.saved_file.is_some() {
                found_saved = Some(frame_out);
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }

        let after_save = found_saved.expect("Expected SavedFileEvent after atomic save");
        let saved = after_save.saved_file.expect("Expected SavedFileEvent after atomic save");
        assert_eq!(PathBuf::from(&saved.path), out_pdf);
        assert!(out_pdf.is_file());
        assert!(!after_save.has_unsaved_changes);
        assert_eq!(
            after_save.active_path.as_deref(),
            Some(out_pdf.to_string_lossy().as_ref())
        );

        let _ = std::fs::remove_dir_all(files.parent().unwrap());
    }

    #[test]
    fn clipboard_shim_roundtrips_with_platform_effects() {
        let _guard = TEST_SERIAL.lock().unwrap_or_else(PoisonError::into_inner);
        let _ = arboard::android_take_copied_text();
        let (files, cache) = temp_test_dirs("clipboard");
        let engine = EngineEntry::new(
            &files.to_string_lossy(),
            &cache.to_string_lossy(),
            None,
        );

        let mut cb = arboard::Clipboard::new().unwrap();
        cb.set_text("Copied from PdfCraft engine").unwrap();

        let effects = engine.step_headless_or_gl(false, 0.1, 1280, 800, 1.0, true, true);
        assert_eq!(
            effects.copy_text.as_deref(),
            Some("Copied from PdfCraft engine")
        );

        let _ = std::fs::remove_dir_all(files.parent().unwrap());
    }
}
