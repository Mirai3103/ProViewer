//! Engine and Book API: open a book, request tiles, hint prefetch, cancel.
//!
//! # Ownership
//!
//! ```text
//! Engine ── Arc<Core> ── DecodeCache (one byte budget shared by every open book), counters, limits, hook
//!        └─ Arc<PrefetchPool>
//! Book   ── Arc<BookInner> ── page source, memoized page info, per-page decode counters, generation
//!        └─ Arc<PrefetchPool>
//! prefetch job ── Weak<BookInner> + a pending-job guard (Arc<Core>)
//! ```
//!
//! * The pool is owned by `Engine` and `Book` handles only. `BookInner` and `Core` never hold it, so a prefetch
//!   job that ends up dropping the last reference to a book (or to the core) on a worker thread can never be
//!   the one that drops, and so tries to join, the pool.
//! * A job holds only a `Weak<BookInner>`: it never keeps a closed book alive. A job that upgrades the `Weak`
//!   holds a temporary `Arc` for its duration.
//!
//! # Prefetch generations (per book, checked inside the job)
//!
//! `PrefetchPool::set_generation` is pool-global and not monotonic, so it would let one book's hint drop the
//! queued jobs of every other open book. The engine therefore never touches the pool generation (every job is
//! submitted with generation 0). Staleness is per book instead: each `BookInner` has an `AtomicU64`
//! generation, `prefetch_hint` and `cancel_prefetch` `fetch_add` it, and a job remembers the value it was
//! created with and exits at start (and again before it decodes) if the book's generation has moved on.
//! Stale jobs stay in the queue until a worker pops them, which costs one `Weak::upgrade` and one atomic
//! load; a hint queues at most `prefetch_ahead + prefetch_behind` jobs, so the queue stays small. Running jobs
//! are never interrupted (decodes are not cancellable).
//!
//! # Cancellation and the shared decode closure
//!
//! `DecodeCache::get_or_decode` hands the leader's result, or error, to every waiter, including a foreground
//! page turn that joined a prefetch decode. The closure passed to it therefore never returns
//! `EngineError::Cancelled` and never consults a cancel flag. `CancelToken` is checked by `render_tile`
//! before and after `get_or_decode`; prefetch jobs just skip work when stale.
//!
//! # Closing a book
//!
//! `Book::drop` marks the book closed, bumps its generation and calls `cache.remove_book(id)`, which also
//! discards the late result of an in-flight decode of that book. A job that upgraded its `Weak` just before the
//! drop may still start a *new* decode afterwards; the book's cache entries would then outlive the book. To
//! close that window `BookInner::drop` (which runs when the last temporary `Arc` of such a job is released)
//! sweeps the cache again. After `Book::drop` returns, `cache_bytes` is therefore 0 unless a job is in exactly
//! that window, and it is 0 as soon as the job finishes.
//!
//! # Counters
//!
//! `decodes` counts decode closures that ran (successful or not), from render and prefetch alike.
//! `cache_hits` counts `render_tile` calls served without running a decode, including a render that joined an
//! in-flight decode; prefetch jobs never count hits.

use crate::archive::{open_path, PageSource};
use crate::cache::{CacheKey, DecodeCache};
use crate::decode::{decode, probe, supported_denoms, DecodedImage, PageInfo};
use crate::error::{EngineError, Result};
use crate::limits::Limits;
use crate::resize::{choose_denom, render_region, Rect};
use crate::scheduler::PrefetchPool;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, Weak};

#[derive(uniffi::Record, Clone, Debug)]
pub struct EngineConfig {
    pub cache_bytes: u64,
    pub prefetch_ahead: u32,
    pub prefetch_behind: u32,
    pub worker_threads: u32,
    pub limits: Limits,
}

impl Default for EngineConfig {
    fn default() -> Self {
        EngineConfig {
            cache_bytes: 256 * 1024 * 1024,
            prefetch_ahead: 3,
            prefetch_behind: 1,
            worker_threads: 2,
            limits: Limits::default(),
        }
    }
}

#[uniffi::export]
pub fn default_engine_config() -> EngineConfig {
    EngineConfig::default()
}

#[derive(uniffi::Record, Clone, Copy, Debug, PartialEq, Eq)]
pub struct EngineStats {
    pub decodes: u64,
    pub cache_hits: u64,
    pub cache_bytes: u64,
    pub cache_entries: u32,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReadDirection {
    Forward,
    Backward,
}

/// A request for one tile: `region` of page `page` (page pixel coordinates) rendered to exactly
/// `out_width x out_height` RGBA pixels. An exact size replaces a float scale so a bitmap and its tile can
/// never disagree by rounding.
#[derive(uniffi::Record, Clone, Copy, Debug, PartialEq, Eq)]
pub struct TileRequest {
    pub page: u32,
    pub region: Rect,
    pub out_width: u32,
    pub out_height: u32,
}

/// Cooperative cancellation flag for [`Book::render_tile`]. Clones share the flag.
#[derive(Clone, Default)]
pub struct CancelToken(Arc<AtomicBool>);

impl CancelToken {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cancel(&self) {
        self.0.store(true, Ordering::SeqCst);
    }

    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }
}

/// Size of a `page_w x page_h` page scaled to fit inside `view_w x view_h`, preserving the aspect ratio,
/// rounded, at least 1 in each dimension.
pub fn fit_size(page_w: u32, page_h: u32, view_w: u32, view_h: u32) -> (u32, u32) {
    let (pw, ph) = (page_w.max(1) as f64, page_h.max(1) as f64);
    let (vw, vh) = (view_w.max(1), view_h.max(1));
    let scale = (vw as f64 / pw).min(vh as f64 / ph);
    let dim = |p: f64, v: u32| ((p * scale).round() as u32).clamp(1, v);
    (dim(pw, vw), dim(ph, vh))
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(PoisonError::into_inner)
}

type DecodeHook = Arc<dyn Fn(u64, u32) + Send + Sync>;

/// State shared by the engine and every book. Does not own the pool (see the module docs).
struct Core {
    limits: Limits,
    cache: DecodeCache,
    decodes: AtomicU64,
    cache_hits: AtomicU64,
    pending_jobs: AtomicUsize,
    hook: Mutex<Option<DecodeHook>>,
}

impl Core {
    fn run_hook(&self, book: u64, page: u32) {
        let hook = lock(&self.hook).clone();
        if let Some(hook) = hook {
            hook(book, page);
        }
    }
}

/// Counts a queued prefetch job until the job has run (including its temporary book `Arc`) or was discarded.
struct PendingJob(Arc<Core>);

impl PendingJob {
    fn new(core: &Arc<Core>) -> Self {
        core.pending_jobs.fetch_add(1, Ordering::SeqCst);
        PendingJob(Arc::clone(core))
    }
}

impl Drop for PendingJob {
    fn drop(&mut self) {
        self.0.pending_jobs.fetch_sub(1, Ordering::SeqCst);
    }
}

#[derive(uniffi::Object)]
pub struct Engine {
    config: EngineConfig,
    core: Arc<Core>,
    pool: Arc<PrefetchPool>,
    next_book_id: AtomicU64,
}

#[uniffi::export]
impl Engine {
    #[uniffi::constructor]
    pub fn new(config: EngineConfig) -> Arc<Self> {
        let core = Arc::new(Core {
            limits: config.limits,
            cache: DecodeCache::new(config.cache_bytes),
            decodes: AtomicU64::new(0),
            cache_hits: AtomicU64::new(0),
            pending_jobs: AtomicUsize::new(0),
            hook: Mutex::new(None),
        });
        let pool = Arc::new(PrefetchPool::new(config.worker_threads.max(1) as usize));
        Arc::new(Engine {
            config,
            core,
            pool,
            next_book_id: AtomicU64::new(1),
        })
    }

    /// Opens a folder or a ZIP/CBZ file. Every book gets a unique id and shares the engine's cache budget.
    pub fn open_path(&self, path: String) -> Result<Arc<Book>> {
        let source = open_path(Path::new(&path), self.core.limits)?;
        let count = source.pages().len();
        let id = self.next_book_id.fetch_add(1, Ordering::Relaxed);
        let inner = Arc::new(BookInner {
            id,
            core: Arc::clone(&self.core),
            infos: Mutex::new(vec![None; count]),
            decode_counts: (0..count).map(|_| AtomicU64::new(0)).collect(),
            source,
            generation: AtomicU64::new(0),
            closed: AtomicBool::new(false),
        });
        Ok(Arc::new(Book {
            inner,
            pool: Arc::clone(&self.pool),
            prefetch_ahead: self.config.prefetch_ahead,
            prefetch_behind: self.config.prefetch_behind,
        }))
    }

    pub fn stats(&self) -> EngineStats {
        EngineStats {
            decodes: self.core.decodes.load(Ordering::SeqCst),
            cache_hits: self.core.cache_hits.load(Ordering::SeqCst),
            cache_bytes: self.core.cache.used_bytes(),
            cache_entries: u32::try_from(self.core.cache.len()).unwrap_or(u32::MAX),
        }
    }
}

/// Rust-only diagnostics, used by tests.
impl Engine {
    /// Prefetch jobs that were queued and have neither finished nor been discarded.
    #[doc(hidden)]
    pub fn pending_prefetch_jobs(&self) -> usize {
        self.core.pending_jobs.load(Ordering::SeqCst)
    }

    /// Threads waiting on the in-flight decode of `(book, page, denom)`. Lets a test prove that a render
    /// has joined a running decode before the decode is released.
    #[doc(hidden)]
    pub fn inflight_waiters(&self, book: &Book, page: u32, denom: u8) -> usize {
        self.core.cache.inflight_waiters(CacheKey {
            book: book.inner.id,
            page,
            denom,
        })
    }

    /// Installs a function called as `(book id, page)` inside every decode, after it is registered as in
    /// flight and before any work. Tests use it to park a decode at a precise point.
    #[doc(hidden)]
    pub fn set_decode_hook(&self, hook: Option<Arc<dyn Fn(u64, u32) + Send + Sync>>) {
        *lock(&self.core.hook) = hook;
    }
}

struct BookInner {
    id: u64,
    core: Arc<Core>,
    source: Arc<dyn PageSource>,
    /// Memoized probe results; errors are not stored.
    infos: Mutex<Vec<Option<PageInfo>>>,
    decode_counts: Vec<AtomicU64>,
    /// Bumped by every hint, by `cancel_prefetch` and by drop; a prefetch job is stale once it differs.
    generation: AtomicU64,
    closed: AtomicBool,
}

/// Probe prefixes tried in order before reading the whole entry.
const PROBE_PREFIXES: [usize; 2] = [16 * 1024, 256 * 1024];

impl BookInner {
    fn count(&self) -> u32 {
        self.source.pages().len() as u32
    }

    fn page_info(&self, index: u32) -> Result<PageInfo> {
        let count = self.count();
        if index >= count {
            return Err(EngineError::PageOutOfRange { index, count });
        }
        if let Some(info) = lock(&self.infos)[index as usize] {
            return Ok(info);
        }
        // No lock is held while reading. Two threads probing the same page both do the work, which is rare
        // and harmless; the stored value is identical.
        let info = self.probe_page(index as usize)?;
        lock(&self.infos)[index as usize] = Some(info);
        Ok(info)
    }

    /// Reads 16 KiB, then 256 KiB, then the whole entry, until the header parses. Only a truncated header
    /// (`Corrupt`) is worth a bigger read; any other error, or a prefix that already was the whole entry, is final.
    fn probe_page(&self, page: usize) -> Result<PageInfo> {
        for want in PROBE_PREFIXES {
            let bytes = self.source.read_prefix(page, want)?;
            match probe(&bytes) {
                Ok(info) => return Ok(info),
                Err(EngineError::Corrupt { .. }) if bytes.len() >= want => continue,
                Err(e) => return Err(e),
            }
        }
        probe(&self.source.read(page)?)
    }

    /// Cached image for `(page, denom)` or a fresh decode. Returns whether this call ran the decode.
    ///
    /// The closure never returns `Cancelled` (see the module docs) and counts as one decode.
    fn decode_page(&self, page: u32, denom: u8) -> Result<(Arc<DecodedImage>, bool)> {
        let key = CacheKey {
            book: self.id,
            page,
            denom,
        };
        let mut ran = false;
        let image = self.core.cache.get_or_decode(key, || {
            ran = true;
            self.core.decodes.fetch_add(1, Ordering::SeqCst);
            self.decode_counts[page as usize].fetch_add(1, Ordering::SeqCst);
            self.core.run_hook(self.id, page);
            let bytes = self.source.read(page as usize)?;
            decode(&bytes, denom, &self.core.limits)
        })?;
        Ok((image, ran))
    }

    fn is_stale(&self, generation: u64) -> bool {
        self.closed.load(Ordering::SeqCst) || self.generation.load(Ordering::SeqCst) != generation
    }

    /// Body of a prefetch job: warm exactly the entry a fit render of `page` would ask for.
    fn prefetch_page(&self, generation: u64, page: u32, view_w: u32, view_h: u32) {
        if self.is_stale(generation) {
            return;
        }
        let Ok(info) = self.page_info(page) else {
            return; // a broken page is reported when the reader actually shows it
        };
        let (out_w, out_h) = fit_size(info.width, info.height, view_w, view_h);
        let region = Rect {
            x: 0,
            y: 0,
            width: info.width,
            height: info.height,
        };
        let denom = choose_denom(region, out_w, out_h, supported_denoms(info.format));
        if self.is_stale(generation) {
            return;
        }
        let _ = self.decode_page(page, denom);
    }

    fn validate_tile(
        &self,
        req: &TileRequest,
        info: PageInfo,
        dst_len: usize,
        dst_stride: usize,
    ) -> Result<()> {
        let invalid = |message: &str| EngineError::InvalidRequest {
            message: message.to_string(),
        };
        if req.out_width == 0 || req.out_height == 0 {
            return Err(invalid("output size must be non-zero"));
        }
        let out_pixels = req.out_width as u64 * req.out_height as u64;
        if out_pixels > self.core.limits.max_image_pixels {
            return Err(invalid("output exceeds the pixel limit"));
        }
        let region = req.region;
        if region.width == 0 || region.height == 0 {
            return Err(invalid("region is empty"));
        }
        let inside = region
            .x
            .checked_add(region.width)
            .is_some_and(|r| r <= info.width)
            && region
                .y
                .checked_add(region.height)
                .is_some_and(|b| b <= info.height);
        if !inside {
            return Err(invalid("region is outside the page"));
        }
        let row_bytes = (req.out_width as usize)
            .checked_mul(4)
            .ok_or_else(|| invalid("output row size overflows"))?;
        if dst_stride < row_bytes {
            return Err(invalid("dst_stride is smaller than out_width * 4"));
        }
        let needed = dst_stride
            .checked_mul(req.out_height as usize - 1)
            .and_then(|n| n.checked_add(row_bytes))
            .ok_or_else(|| invalid("output buffer size overflows"))?;
        if dst_len < needed {
            return Err(invalid("dst is too small for the requested output"));
        }
        Ok(())
    }
}

impl Drop for BookInner {
    /// Last reference gone (the `Book` and any in-flight job's temporary `Arc`): sweep entries a job may have
    /// inserted after `Book::drop` ran.
    fn drop(&mut self) {
        self.core.cache.remove_book(self.id);
    }
}

#[derive(uniffi::Object)]
pub struct Book {
    inner: Arc<BookInner>,
    pool: Arc<PrefetchPool>,
    prefetch_ahead: u32,
    prefetch_behind: u32,
}

#[uniffi::export]
impl Book {
    pub fn page_count(&self) -> u32 {
        self.inner.count()
    }

    pub fn page_name(&self, index: u32) -> Result<String> {
        let pages = self.inner.source.pages();
        pages
            .get(index as usize)
            .map(|p| p.name.clone())
            .ok_or(EngineError::PageOutOfRange {
                index,
                count: pages.len() as u32,
            })
    }

    /// Size and format of a page, read lazily from the first bytes only and memoized (errors are not).
    pub fn page_info(&self, index: u32) -> Result<PageInfo> {
        self.inner.page_info(index)
    }

    /// Supersedes every earlier hint of this book and queues decodes for the pages around `current`: up to
    /// `prefetch_ahead` pages in `direction` and `prefetch_behind` the other way, nearest first, out-of-range
    /// pages skipped. Each job decodes at the denom a fit render for the viewport would use. Never blocks.
    pub fn prefetch_hint(
        &self,
        current: u32,
        direction: ReadDirection,
        viewport_width: u32,
        viewport_height: u32,
    ) {
        let generation = self.inner.generation.fetch_add(1, Ordering::SeqCst) + 1;
        if viewport_width == 0 || viewport_height == 0 {
            return;
        }
        let targets = prefetch_targets(
            current,
            direction,
            self.inner.count(),
            self.prefetch_ahead,
            self.prefetch_behind,
        );
        for (page, priority) in targets {
            let pending = PendingJob::new(&self.inner.core);
            let weak: Weak<BookInner> = Arc::downgrade(&self.inner);
            self.pool.submit(
                0,
                priority,
                Box::new(move || {
                    // Declared before `inner`, so it drops after the temporary book reference.
                    let _pending = pending;
                    let Some(inner) = weak.upgrade() else {
                        return;
                    };
                    inner.prefetch_page(generation, page, viewport_width, viewport_height);
                }),
            );
        }
    }

    /// Makes every queued or not-yet-started prefetch job of this book a no-op.
    pub fn cancel_prefetch(&self) {
        self.inner.generation.fetch_add(1, Ordering::SeqCst);
    }
}

/// Rust-only API (not exported over uniffi).
impl Book {
    /// Renders `req.region` of `req.page` into `dst` as `out_width x out_height` RGBA8888 rows `dst_stride`
    /// bytes apart; padding bytes are left untouched. Everything is validated before anything is decoded.
    pub fn render_tile(
        &self,
        req: &TileRequest,
        dst: &mut [u8],
        dst_stride: usize,
        cancel: &CancelToken,
    ) -> Result<()> {
        let inner = &*self.inner;
        let info = inner.page_info(req.page)?;
        inner.validate_tile(req, info, dst.len(), dst_stride)?;
        if cancel.is_cancelled() {
            return Err(EngineError::Cancelled);
        }
        let denom = choose_denom(
            req.region,
            req.out_width,
            req.out_height,
            supported_denoms(info.format),
        );
        let (image, decoded) = inner.decode_page(req.page, denom)?;
        if !decoded {
            inner.core.cache_hits.fetch_add(1, Ordering::SeqCst);
        }
        if cancel.is_cancelled() {
            return Err(EngineError::Cancelled);
        }
        render_region(
            &image,
            info.width,
            info.height,
            req.region,
            req.out_width,
            req.out_height,
            dst,
            dst_stride,
        )
    }

    /// Decodes of page `index` so far, across render and prefetch. For tests and diagnostics.
    #[doc(hidden)]
    pub fn page_decode_count(&self, index: u32) -> u64 {
        self.inner
            .decode_counts
            .get(index as usize)
            .map_or(0, |c| c.load(Ordering::SeqCst))
    }
}

impl Drop for Book {
    fn drop(&mut self) {
        self.inner.closed.store(true, Ordering::SeqCst);
        self.inner.generation.fetch_add(1, Ordering::SeqCst);
        self.inner.core.cache.remove_book(self.inner.id);
    }
}

/// `(page, priority)` pairs for a hint: `ahead` pages in `direction` and `behind` pages the other way,
/// priority = distance from `current`, ahead before behind at equal distance, out-of-range pages skipped.
fn prefetch_targets(
    current: u32,
    direction: ReadDirection,
    count: u32,
    ahead: u32,
    behind: u32,
) -> Vec<(u32, u32)> {
    let step: i64 = match direction {
        ReadDirection::Forward => 1,
        ReadDirection::Backward => -1,
    };
    let page_at = |distance: u32, sign: i64| {
        let p = current as i64 + sign * distance as i64;
        (0..count as i64).contains(&p).then_some(p as u32)
    };
    let mut targets = Vec::new();
    // Distances beyond `count` cannot be in range, so the loop is bounded by the book size, not by a
    // caller-supplied `u32::MAX`.
    for distance in 1..=ahead.max(behind).min(count) {
        if distance <= ahead {
            targets.extend(page_at(distance, step).map(|p| (p, distance)));
        }
        if distance <= behind {
            targets.extend(page_at(distance, -step).map(|p| (p, distance)));
        }
    }
    targets
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn targets_forward_ahead_then_behind_by_distance() {
        assert_eq!(
            prefetch_targets(5, ReadDirection::Forward, 20, 3, 1),
            [(6, 1), (4, 1), (7, 2), (8, 3)]
        );
    }

    #[test]
    fn targets_backward_flips_directions() {
        assert_eq!(
            prefetch_targets(5, ReadDirection::Backward, 20, 2, 1),
            [(4, 1), (6, 1), (3, 2)]
        );
    }

    #[test]
    fn targets_skip_out_of_range_pages() {
        assert_eq!(
            prefetch_targets(0, ReadDirection::Forward, 3, 3, 2),
            [(1, 1), (2, 2)]
        );
        assert_eq!(
            prefetch_targets(2, ReadDirection::Forward, 3, 2, 1),
            [(1, 1)]
        );
        assert!(prefetch_targets(0, ReadDirection::Forward, 0, 3, 1).is_empty());
    }

    #[test]
    fn targets_are_bounded_for_huge_distances() {
        let t = prefetch_targets(1, ReadDirection::Forward, 4, u32::MAX, u32::MAX);
        assert_eq!(t.len(), 3);
    }

    #[test]
    fn targets_handle_current_beyond_the_end() {
        assert!(prefetch_targets(u32::MAX, ReadDirection::Forward, 10, 3, 3).is_empty());
    }
}
