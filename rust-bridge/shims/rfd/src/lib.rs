#![forbid(unsafe_code)]

use std::collections::{HashMap, VecDeque};
use std::future::Future;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::task::{Context, Poll, Waker};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FileHandle {
    path: PathBuf,
}

impl FileHandle {
    pub fn wrap(path: PathBuf) -> Self {
        Self { path }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn file_name(&self) -> String {
        self.path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default()
    }

    pub async fn read(&self) -> Vec<u8> {
        std::fs::read(&self.path).unwrap_or_default()
    }

    pub async fn write(&self, data: &[u8]) -> std::io::Result<()> {
        std::fs::write(&self.path, data)
    }

    pub fn inner(&self) -> &Path {
        &self.path
    }
}

impl From<PathBuf> for FileHandle {
    fn from(path: PathBuf) -> Self {
        Self { path }
    }
}

#[derive(Clone, Debug)]
pub struct BridgePickerRequest {
    pub id: u64,
    pub mode: &'static str,
    pub title: Option<String>,
    pub suggested_name: Option<String>,
    pub extensions: Vec<String>,
}

struct PickerSlot {
    result: Option<Vec<PathBuf>>,
    waker: Option<Waker>,
}

#[derive(Default)]
struct PickerRegistry {
    outbound: VecDeque<BridgePickerRequest>,
    active: HashMap<u64, Arc<Mutex<PickerSlot>>>,
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);
static REGISTRY: std::sync::LazyLock<Mutex<PickerRegistry>> =
    std::sync::LazyLock::new(|| Mutex::new(PickerRegistry::default()));

/// Drain the next pending file picker request so the Android host can open the corresponding SAF contract.
pub fn take_pending_picker_request() -> Option<BridgePickerRequest> {
    let mut reg = REGISTRY.lock().unwrap_or_else(PoisonError::into_inner);
    reg.outbound.pop_front()
}

/// Deliver the user's chosen local path(s) (or an empty Vec on cancellation) and wake the waiting worker thread.
pub fn deliver_picker_result(request_id: u64, paths: Vec<PathBuf>) -> bool {
    let slot = {
        let mut reg = REGISTRY.lock().unwrap_or_else(PoisonError::into_inner);
        reg.active.remove(&request_id)
    };
    let Some(slot) = slot else {
        return false;
    };
    let waker = {
        let mut guard = slot.lock().unwrap_or_else(PoisonError::into_inner);
        guard.result = Some(paths);
        guard.waker.take()
    };
    if let Some(w) = waker {
        w.wake();
    }
    true
}

/// Cancel and drain all pending picker requests (used in tests or engine teardown).
pub fn reset_picker_registry() {
    let slots: Vec<Arc<Mutex<PickerSlot>>> = {
        let mut reg = REGISTRY.lock().unwrap_or_else(PoisonError::into_inner);
        reg.outbound.clear();
        reg.active.drain().map(|(_, v)| v).collect()
    };
    for slot in slots {
        let waker = {
            let mut guard = slot.lock().unwrap_or_else(PoisonError::into_inner);
            guard.result = Some(Vec::new());
            guard.waker.take()
        };
        if let Some(w) = waker {
            w.wake();
        }
    }
}

struct RawPickFuture {
    id: u64,
    slot: Arc<Mutex<PickerSlot>>,
}

impl Future for RawPickFuture {
    type Output = Vec<PathBuf>;

    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        let mut guard = self.slot.lock().unwrap_or_else(PoisonError::into_inner);
        if let Some(paths) = guard.result.take() {
            Poll::Ready(paths)
        } else {
            guard.waker = Some(cx.waker().clone());
            Poll::Pending
        }
    }
}

impl Drop for RawPickFuture {
    fn drop(&mut self) {
        let mut reg = REGISTRY.lock().unwrap_or_else(PoisonError::into_inner);
        reg.active.remove(&self.id);
    }
}

#[derive(Clone, Debug, Default)]
pub struct AsyncFileDialog {
    title: Option<String>,
    suggested_name: Option<String>,
    directory: Option<PathBuf>,
    extensions: Vec<String>,
    can_create_directories: Option<bool>,
}

impl AsyncFileDialog {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn add_filter(mut self, _name: impl Into<String>, extensions: &[impl ToString]) -> Self {
        for ext in extensions {
            let clean = ext.to_string().trim().trim_start_matches('.').to_ascii_lowercase();
            if !clean.is_empty() && !self.extensions.contains(&clean) {
                self.extensions.push(clean);
            }
        }
        self
    }

    pub fn set_directory<P: AsRef<Path>>(mut self, path: P) -> Self {
        self.directory = Some(path.as_ref().to_path_buf());
        self
    }

    pub fn set_file_name(mut self, file_name: impl Into<String>) -> Self {
        self.suggested_name = Some(file_name.into());
        self
    }

    pub fn set_title(mut self, title: impl Into<String>) -> Self {
        self.title = Some(title.into());
        self
    }

    pub fn set_can_create_directories(mut self, can: bool) -> Self {
        self.can_create_directories = Some(can);
        self
    }

    pub fn set_parent<W>(self, _parent: &W) -> Self {
        self
    }

    fn enqueue(self, mode: &'static str) -> RawPickFuture {
        let id = NEXT_ID.fetch_add(1, Ordering::SeqCst);
        let slot = Arc::new(Mutex::new(PickerSlot {
            result: None,
            waker: None,
        }));
        let request = BridgePickerRequest {
            id,
            mode,
            title: self.title,
            suggested_name: self.suggested_name,
            extensions: self.extensions,
        };
        let mut reg = REGISTRY.lock().unwrap_or_else(PoisonError::into_inner);
        reg.active.insert(id, Arc::clone(&slot));
        reg.outbound.push_back(request);
        RawPickFuture { id, slot }
    }

    pub fn pick_file(self) -> impl Future<Output = Option<FileHandle>> + Send + 'static {
        let fut = self.enqueue("open_file");
        async move { fut.await.into_iter().next().map(FileHandle::from) }
    }

    pub fn pick_files(self) -> impl Future<Output = Option<Vec<FileHandle>>> + Send + 'static {
        let fut = self.enqueue("open_multiple");
        async move {
            let paths = fut.await;
            if paths.is_empty() {
                None
            } else {
                Some(paths.into_iter().map(FileHandle::from).collect())
            }
        }
    }

    pub fn pick_folder(self) -> impl Future<Output = Option<FileHandle>> + Send + 'static {
        let fut = self.enqueue("pick_folder");
        async move { fut.await.into_iter().next().map(FileHandle::from) }
    }

    pub fn save_file(self) -> impl Future<Output = Option<FileHandle>> + Send + 'static {
        let fut = self.enqueue("save_file");
        async move { fut.await.into_iter().next().map(FileHandle::from) }
    }
}

#[derive(Clone, Debug, Default)]
pub struct FileDialog {
    inner: AsyncFileDialog,
}

impl FileDialog {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn add_filter(mut self, name: impl Into<String>, extensions: &[impl ToString]) -> Self {
        self.inner = self.inner.add_filter(name, extensions);
        self
    }

    pub fn set_directory<P: AsRef<Path>>(mut self, path: P) -> Self {
        self.inner = self.inner.set_directory(path);
        self
    }

    pub fn set_file_name(mut self, file_name: impl Into<String>) -> Self {
        self.inner = self.inner.set_file_name(file_name);
        self
    }

    pub fn set_title(mut self, title: impl Into<String>) -> Self {
        self.inner = self.inner.set_title(title);
        self
    }

    pub fn set_can_create_directories(mut self, can: bool) -> Self {
        self.inner = self.inner.set_can_create_directories(can);
        self
    }
}
