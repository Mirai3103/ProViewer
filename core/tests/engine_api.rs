mod common;

use proviewer_core::archive::open_path;
use proviewer_core::decode::{supported_denoms, ImageFormat, PageInfo};
use proviewer_core::engine::{
    default_engine_config, fit_size, Book, CancelToken, Engine, EngineConfig, ReadDirection,
    TileRequest,
};
use proviewer_core::error::EngineError;
use proviewer_core::limits::Limits;
use proviewer_core::resize::{choose_denom, Rect};
use std::io::{Read, Write};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

const VIEW_W: u32 = 1080;
const VIEW_H: u32 = 2378;
const TIMEOUT: Duration = Duration::from_secs(5);

fn corpus_path(name: &str) -> String {
    common::small_corpus()
        .join(name)
        .to_str()
        .unwrap()
        .to_string()
}

fn engine_with(config: EngineConfig) -> Arc<Engine> {
    Engine::new(config)
}

fn engine() -> Arc<Engine> {
    engine_with(EngineConfig::default())
}

fn open(engine: &Engine, name: &str) -> Arc<Book> {
    engine.open_path(corpus_path(name)).unwrap()
}

/// Polls `cond` until it holds. Panics after 5 s. Never used as a plain delay.
fn wait_until(what: &str, cond: impl Fn() -> bool) {
    let deadline = Instant::now() + TIMEOUT;
    while !cond() {
        assert!(Instant::now() < deadline, "timed out waiting for: {what}");
        std::thread::yield_now();
        std::thread::sleep(Duration::from_millis(1));
    }
}

fn wait_prefetch_idle(engine: &Engine) {
    wait_until("prefetch jobs to finish", || {
        engine.pending_prefetch_jobs() == 0
    });
}

/// The cache denom a fit render of `page` asks for.
fn fit_denom(book: &Book, page: u32) -> u8 {
    let req = fit_request(book, page);
    let info = book.page_info(page).unwrap();
    choose_denom(
        req.region,
        req.out_width,
        req.out_height,
        supported_denoms(info.format),
    )
}

/// Waits until some thread is blocked on the in-flight decode of `page`'s fit-render key. Only then is it
/// certain that a render joined a running decode instead of racing it.
fn wait_for_waiter(engine: &Engine, book: &Book, page: u32) {
    let denom = fit_denom(book, page);
    wait_until("a render to wait on the running decode", || {
        engine.inflight_waiters(book, page, denom) >= 1
    });
}

fn full_region(info: PageInfo) -> Rect {
    Rect {
        x: 0,
        y: 0,
        width: info.width,
        height: info.height,
    }
}

fn fit_request(book: &Book, page: u32) -> TileRequest {
    let info = book.page_info(page).unwrap();
    let (w, h) = fit_size(info.width, info.height, VIEW_W, VIEW_H);
    TileRequest {
        page,
        region: full_region(info),
        out_width: w,
        out_height: h,
    }
}

/// Renders the whole page `page` scaled to `w x h` into a tightly packed RGBA buffer.
fn render_full(book: &Book, page: u32, w: u32, h: u32) -> Vec<u8> {
    let info = book.page_info(page).unwrap();
    let mut dst = vec![0u8; w as usize * h as usize * 4];
    let req = TileRequest {
        page,
        region: full_region(info),
        out_width: w,
        out_height: h,
    };
    book.render_tile(&req, &mut dst, w as usize * 4, &CancelToken::new())
        .unwrap();
    dst
}

fn render_fit(book: &Book, page: u32) {
    let req = fit_request(book, page);
    let mut dst = vec![0u8; req.out_width as usize * req.out_height as usize * 4];
    book.render_tile(
        &req,
        &mut dst,
        req.out_width as usize * 4,
        &CancelToken::new(),
    )
    .unwrap();
}

/// A test hook that parks the decode of one page until `open()` is called.
struct Gate {
    page: u32,
    open: Mutex<bool>,
    cv: Condvar,
    entered: AtomicUsize,
}

impl Gate {
    fn install(engine: &Engine, page: u32) -> Arc<Gate> {
        let gate = Arc::new(Gate {
            page,
            open: Mutex::new(false),
            cv: Condvar::new(),
            entered: AtomicUsize::new(0),
        });
        let g = Arc::clone(&gate);
        engine.set_decode_hook(Some(Arc::new(move |_book, page| {
            if page == g.page {
                g.entered.fetch_add(1, Ordering::SeqCst);
                let guard = g.open.lock().unwrap();
                let (_guard, res) =
                    g.cv.wait_timeout_while(guard, Duration::from_secs(10), |open| !*open)
                        .unwrap();
                assert!(!res.timed_out(), "gate was never opened");
            }
        })));
        gate
    }

    fn wait_entered(&self) {
        wait_until("a decode to reach the gate", || {
            self.entered.load(Ordering::SeqCst) >= 1
        });
    }

    fn open(&self) {
        *self.open.lock().unwrap() = true;
        self.cv.notify_all();
    }
}

#[test]
fn fit_size_table() {
    assert_eq!(fit_size(1600, 2400, VIEW_W, VIEW_H), (1080, 1620));
    assert_eq!(fit_size(800, 24000, VIEW_W, VIEW_H), (79, 2378));
    assert_eq!(fit_size(3200, 2400, VIEW_W, VIEW_H), (1080, 810));
}

#[test]
fn fit_size_never_returns_zero() {
    assert_eq!(fit_size(1, 100_000, 1080, 100), (1, 100));
    let (w, h) = fit_size(0, 0, 1080, 2378);
    assert!(w >= 1 && h >= 1);
}

#[test]
fn default_config_matches_spec() {
    let c = default_engine_config();
    assert_eq!(c.cache_bytes, 256 * 1024 * 1024);
    assert_eq!(c.prefetch_ahead, 3);
    assert_eq!(c.prefetch_behind, 1);
    assert_eq!(c.worker_threads, 2);
    assert_eq!(c.limits, Limits::default());
}

#[test]
fn open_zip_and_folder_report_same_pages() {
    let cbz = common::small_corpus().join("mixed-small.cbz");
    let dir = tempfile::tempdir().unwrap();
    let mut archive = zip::ZipArchive::new(std::fs::File::open(&cbz).unwrap()).unwrap();
    for i in 0..archive.len() {
        let mut f = archive.by_index(i).unwrap();
        let mut data = Vec::new();
        f.read_to_end(&mut data).unwrap();
        let out = dir.path().join(f.name().unwrap().as_ref());
        std::fs::create_dir_all(out.parent().unwrap()).unwrap();
        std::fs::write(out, data).unwrap();
    }

    let engine = engine();
    let zip_book = engine.open_path(cbz.to_str().unwrap().to_string()).unwrap();
    let folder_book = engine
        .open_path(dir.path().to_str().unwrap().to_string())
        .unwrap();
    assert_eq!(zip_book.page_count(), 20);
    assert_eq!(folder_book.page_count(), 20);
    for i in 0..20 {
        assert_eq!(
            zip_book.page_name(i).unwrap(),
            folder_book.page_name(i).unwrap()
        );
    }
    assert_eq!(
        zip_book.page_name(20),
        Err(EngineError::PageOutOfRange {
            index: 20,
            count: 20
        })
    );
}

#[test]
fn page_info_matches_generator() {
    let engine = engine();
    let screentone = open(&engine, "screentone.cbz");
    assert_eq!(
        screentone.page_info(0).unwrap(),
        PageInfo {
            width: 80,
            height: 120,
            format: ImageFormat::Jpeg
        }
    );
    let mixed = open(&engine, "formats-and-names.cbz");
    assert_eq!(mixed.page_info(2).unwrap().format, ImageFormat::Webp);
    assert_eq!(
        mixed.page_info(9),
        Err(EngineError::PageOutOfRange { index: 9, count: 4 })
    );
}

#[test]
fn render_fit_page_is_opaque_rgba() {
    let engine = engine();
    let book = open(&engine, "mixed-small.cbz");
    let req = fit_request(&book, 0);
    let stride = VIEW_W as usize * 4;
    let mut dst = vec![0u8; stride * VIEW_H as usize];
    book.render_tile(&req, &mut dst, stride, &CancelToken::new())
        .unwrap();
    for y in 0..req.out_height as usize {
        let row = &dst[y * stride..y * stride + req.out_width as usize * 4];
        assert!(
            row.iter().skip(3).step_by(4).all(|&a| a == 255),
            "row {y} has a non-opaque pixel"
        );
    }
    assert_eq!(engine.stats().decodes, 1);
}

#[test]
fn second_render_hits_cache() {
    let engine = engine();
    let book = open(&engine, "mixed-small.cbz");
    let req = fit_request(&book, 0);
    let pixels = render_full(&book, 0, req.out_width, req.out_height);
    let first = engine.stats();
    assert_eq!(first.decodes, 1);
    assert_eq!(first.cache_hits, 0);
    let again = render_full(&book, 0, req.out_width, req.out_height);
    assert!(pixels == again, "cached render must be pixel-identical");
    let second = engine.stats();
    assert_eq!(second.decodes, first.decodes);
    assert_eq!(second.cache_hits, first.cache_hits + 1);
    assert_eq!(second.cache_entries, 1);
    assert!(second.cache_bytes > 0);
}

#[test]
fn render_rejects_bad_requests() {
    let engine = engine();
    let book = open(&engine, "mixed-small.cbz");
    let good = fit_request(&book, 0);
    let info = book.page_info(0).unwrap();
    let cancel = CancelToken::new();
    let mut dst = vec![0u8; good.out_width as usize * good.out_height as usize * 4];
    let stride = good.out_width as usize * 4;

    let mut bad_page = good;
    bad_page.page = 20;
    assert_eq!(
        book.render_tile(&bad_page, &mut dst, stride, &cancel),
        Err(EngineError::PageOutOfRange {
            index: 20,
            count: 20
        })
    );

    let invalid = |r: Result<(), EngineError>, what: &str| match r {
        Err(EngineError::InvalidRequest { .. }) => {}
        other => panic!("{what}: expected InvalidRequest, got {other:?}"),
    };

    let mut outside = good;
    outside.region = Rect {
        x: info.width,
        y: 0,
        width: 1,
        height: 1,
    };
    invalid(
        book.render_tile(&outside, &mut dst, stride, &cancel),
        "region outside",
    );
    let mut overflow = good;
    overflow.region = Rect {
        x: u32::MAX,
        y: 0,
        width: u32::MAX,
        height: 1,
    };
    invalid(
        book.render_tile(&overflow, &mut dst, stride, &cancel),
        "region overflow",
    );
    let mut empty = good;
    empty.region.width = 0;
    invalid(
        book.render_tile(&empty, &mut dst, stride, &cancel),
        "empty region",
    );
    let mut zero_w = good;
    zero_w.out_width = 0;
    invalid(
        book.render_tile(&zero_w, &mut dst, stride, &cancel),
        "zero out width",
    );
    let mut zero_h = good;
    zero_h.out_height = 0;
    invalid(
        book.render_tile(&zero_h, &mut dst, stride, &cancel),
        "zero out height",
    );
    let short = dst.len() - 1;
    invalid(
        book.render_tile(&good, &mut dst[..short], stride, &cancel),
        "short dst",
    );
    invalid(
        book.render_tile(&good, &mut dst, stride - 1, &cancel),
        "stride below row size",
    );
    // Every failure above must be detected before anything is decoded.
    assert_eq!(engine.stats().decodes, 0);
}

#[test]
fn render_rejects_output_above_pixel_limit() {
    let engine = engine_with(EngineConfig {
        limits: Limits {
            max_image_pixels: 10_000,
            ..Limits::default()
        },
        ..EngineConfig::default()
    });
    let book = open(&engine, "mixed-small.cbz");
    let info = book.page_info(0).unwrap();
    let req = TileRequest {
        page: 0,
        region: full_region(info),
        out_width: 101,
        out_height: 100,
    };
    let mut dst = vec![0u8; 101 * 100 * 4];
    match book.render_tile(&req, &mut dst, 101 * 4, &CancelToken::new()) {
        Err(EngineError::InvalidRequest { .. }) => {}
        other => panic!("expected InvalidRequest, got {other:?}"),
    }
    assert_eq!(engine.stats().decodes, 0);
}

#[test]
fn cancelled_token_returns_cancelled() {
    let engine = engine();
    let book = open(&engine, "mixed-small.cbz");
    let req = fit_request(&book, 0);
    let mut dst = vec![0u8; req.out_width as usize * req.out_height as usize * 4];
    let cancel = CancelToken::new();
    cancel.cancel();
    assert!(cancel.is_cancelled());
    assert_eq!(
        book.render_tile(&req, &mut dst, req.out_width as usize * 4, &cancel),
        Err(EngineError::Cancelled)
    );
    assert_eq!(engine.stats().decodes, 0);
}

#[test]
fn cancel_token_clones_share_state() {
    let a = CancelToken::new();
    let b = a.clone();
    assert!(!b.is_cancelled());
    a.cancel();
    assert!(b.is_cancelled());
}

#[test]
fn prefetch_warms_next_pages() {
    let engine = engine();
    let book = open(&engine, "mixed-small.cbz");
    book.prefetch_hint(0, ReadDirection::Forward, VIEW_W, VIEW_H);
    wait_until("3 prefetched pages", || engine.stats().cache_entries >= 3);
    wait_prefetch_idle(&engine);
    let before = engine.stats();
    for page in 1..=3 {
        render_fit(&book, page);
    }
    let after = engine.stats();
    assert_eq!(after.decodes, before.decodes);
    assert_eq!(after.cache_hits, before.cache_hits + 3);
}

#[test]
fn prefetch_backward_warms_previous_pages() {
    let engine = engine_with(EngineConfig {
        prefetch_ahead: 2,
        prefetch_behind: 1,
        ..EngineConfig::default()
    });
    let book = open(&engine, "mixed-small.cbz");
    book.prefetch_hint(10, ReadDirection::Backward, VIEW_W, VIEW_H);
    wait_prefetch_idle(&engine);
    assert_eq!(engine.stats().cache_entries, 3);
    for page in [9, 8, 11] {
        assert_eq!(book.page_decode_count(page), 1, "page {page}");
    }
    assert_eq!(book.page_decode_count(10), 0);
}

#[test]
fn render_during_prefetch_does_not_double_decode() {
    let engine = engine_with(EngineConfig {
        worker_threads: 1,
        prefetch_ahead: 1,
        ..EngineConfig::default()
    });
    let book = open(&engine, "mixed-small.cbz");
    book.prefetch_hint(0, ReadDirection::Forward, VIEW_W, VIEW_H);
    render_fit(&book, 1);
    wait_prefetch_idle(&engine);
    assert_eq!(book.page_decode_count(1), 1);
    assert_eq!(engine.stats().decodes, 1);
    assert_eq!(engine.stats().cache_entries, 1);
}

#[test]
fn render_waits_for_the_running_prefetch_decode() {
    // Deterministic twin of `render_during_prefetch_does_not_double_decode` (which keeps the brief's form and
    // may or may not overlap). The prefetch decode of page 1 is parked, the foreground render of the same page
    // is proven to be waiting on it (waiter count >= 1) and only then is the decode released: the render must
    // share that single decode.
    let engine = engine_with(EngineConfig {
        worker_threads: 1,
        prefetch_ahead: 1,
        ..EngineConfig::default()
    });
    let book = open(&engine, "mixed-small.cbz");
    let gate = Gate::install(&engine, 1);
    book.prefetch_hint(0, ReadDirection::Forward, VIEW_W, VIEW_H);
    gate.wait_entered();
    std::thread::scope(|s| {
        let render = s.spawn(|| render_fit(&book, 1));
        wait_for_waiter(&engine, &book, 1);
        gate.open();
        render.join().unwrap();
    });
    wait_prefetch_idle(&engine);
    assert_eq!(book.page_decode_count(1), 1);
    assert_eq!(engine.stats().decodes, 1);
    assert_eq!(
        engine.stats().cache_hits,
        1,
        "the render joined, it did not decode"
    );
}

#[test]
fn cancelled_prefetch_does_not_poison_foreground_render() {
    // The prefetch decode of page 1 is parked and its generation then goes stale. A foreground render that is
    // proven to be waiting on that decode (waiter count >= 1) must still get the leader's image, never
    // `Cancelled`.
    let engine = engine_with(EngineConfig {
        worker_threads: 1,
        prefetch_ahead: 1,
        ..EngineConfig::default()
    });
    let book = open(&engine, "mixed-small.cbz");
    let gate = Gate::install(&engine, 1);
    book.prefetch_hint(0, ReadDirection::Forward, VIEW_W, VIEW_H);
    gate.wait_entered();
    book.cancel_prefetch();
    std::thread::scope(|s| {
        let render = s.spawn(|| {
            let req = fit_request(&book, 1);
            let mut dst = vec![0u8; req.out_width as usize * req.out_height as usize * 4];
            book.render_tile(
                &req,
                &mut dst,
                req.out_width as usize * 4,
                &CancelToken::new(),
            )
        });
        wait_for_waiter(&engine, &book, 1);
        gate.open();
        assert_eq!(render.join().unwrap(), Ok(()));
    });
    wait_prefetch_idle(&engine);
    assert_eq!(book.page_decode_count(1), 1);
    assert_eq!(engine.stats().decodes, 1);
}

#[test]
fn stale_prefetch_jobs_do_no_work() {
    // One worker, parked on page 1. A later hint queues page 6, then `cancel_prefetch` makes it stale before
    // the worker is free again: the queued job must exit without decoding.
    let engine = engine_with(EngineConfig {
        worker_threads: 1,
        prefetch_ahead: 1,
        prefetch_behind: 0,
        ..EngineConfig::default()
    });
    let book = open(&engine, "mixed-small.cbz");
    let gate = Gate::install(&engine, 1);
    book.prefetch_hint(0, ReadDirection::Forward, VIEW_W, VIEW_H);
    gate.wait_entered();
    book.prefetch_hint(5, ReadDirection::Forward, VIEW_W, VIEW_H);
    book.cancel_prefetch();
    gate.open();
    wait_prefetch_idle(&engine);
    assert_eq!(book.page_decode_count(1), 1);
    assert_eq!(book.page_decode_count(6), 0);
}

#[test]
fn newer_hint_supersedes_older_one() {
    let engine = engine_with(EngineConfig {
        worker_threads: 1,
        prefetch_ahead: 1,
        prefetch_behind: 0,
        ..EngineConfig::default()
    });
    let book = open(&engine, "mixed-small.cbz");
    let gate = Gate::install(&engine, 1);
    book.prefetch_hint(0, ReadDirection::Forward, VIEW_W, VIEW_H);
    gate.wait_entered();
    book.prefetch_hint(5, ReadDirection::Forward, VIEW_W, VIEW_H); // queued behind the parked job
    book.prefetch_hint(10, ReadDirection::Forward, VIEW_W, VIEW_H);
    gate.open();
    wait_prefetch_idle(&engine);
    assert_eq!(book.page_decode_count(6), 0, "older hint must be stale");
    assert_eq!(book.page_decode_count(11), 1, "latest hint must run");
}

#[test]
fn cache_respects_budget_across_pages() {
    let probe_engine = engine();
    let probe_book = open(&probe_engine, "mixed-small.cbz");
    render_fit(&probe_book, 0);
    let page_bytes = probe_engine.stats().cache_bytes;
    assert!(page_bytes > 0);

    let budget = page_bytes * 2;
    let engine = engine_with(EngineConfig {
        cache_bytes: budget,
        ..EngineConfig::default()
    });
    let book = open(&engine, "mixed-small.cbz");
    for page in 0..book.page_count() {
        render_fit(&book, page);
        assert!(
            engine.stats().cache_bytes <= budget,
            "over budget after page {page}"
        );
    }
}

#[test]
fn two_open_books_share_the_cache_budget() {
    let probe_engine = engine();
    let probe_book = open(&probe_engine, "mixed-small.cbz");
    render_fit(&probe_book, 0);
    let page_bytes = probe_engine.stats().cache_bytes;

    let budget = page_bytes * 2;
    let engine = engine_with(EngineConfig {
        cache_bytes: budget,
        ..EngineConfig::default()
    });
    let a = open(&engine, "mixed-small.cbz");
    let b = open(&engine, "mixed-small.cbz");
    render_fit(&a, 0);
    render_fit(&b, 0); // a different book: must not hit a's entry
    assert_eq!(engine.stats().decodes, 2);
    assert_eq!(engine.stats().cache_hits, 0);
    for page in 1..6 {
        render_fit(&b, page);
        assert!(engine.stats().cache_bytes <= budget);
    }
    // b's pages pushed a's page 0 (least recently used) out of the shared budget.
    let decodes = engine.stats().decodes;
    render_fit(&a, 0);
    assert_eq!(engine.stats().decodes, decodes + 1);
    assert!(engine.stats().cache_bytes <= budget);
    drop(a);
    assert!(engine.stats().cache_bytes <= budget);
    drop(b);
    assert_eq!(engine.stats().cache_bytes, 0);
}

#[test]
fn dropping_book_releases_cache() {
    let engine = engine();
    let book = open(&engine, "mixed-small.cbz");
    for page in 0..3 {
        render_fit(&book, page);
    }
    assert_eq!(engine.stats().cache_entries, 3);
    assert!(engine.stats().cache_bytes > 0);
    drop(book);
    assert_eq!(engine.stats().cache_bytes, 0);
    assert_eq!(engine.stats().cache_entries, 0);
}

#[test]
fn dropping_book_during_prefetch_decode_leaves_cache_empty() {
    // The prefetch decode of page 1 is parked mid-flight; the book is dropped; the decode then finishes. Its
    // result must not be inserted into the cache of a book that no longer exists.
    let engine = engine_with(EngineConfig {
        worker_threads: 1,
        prefetch_ahead: 1,
        ..EngineConfig::default()
    });
    let book = open(&engine, "mixed-small.cbz");
    let gate = Gate::install(&engine, 1);
    book.prefetch_hint(0, ReadDirection::Forward, VIEW_W, VIEW_H);
    gate.wait_entered();
    drop(book);
    gate.open();
    wait_prefetch_idle(&engine);
    assert_eq!(engine.stats().cache_bytes, 0);
    assert_eq!(engine.stats().cache_entries, 0);
}

#[test]
fn queued_prefetch_of_dropped_book_does_nothing() {
    let engine = engine_with(EngineConfig {
        worker_threads: 1,
        prefetch_ahead: 3,
        prefetch_behind: 0,
        ..EngineConfig::default()
    });
    let book = open(&engine, "mixed-small.cbz");
    let gate = Gate::install(&engine, 1);
    book.prefetch_hint(0, ReadDirection::Forward, VIEW_W, VIEW_H);
    gate.wait_entered(); // page 1 running; pages 2 and 3 queued
    drop(book);
    gate.open();
    wait_prefetch_idle(&engine);
    assert_eq!(engine.stats().decodes, 1, "only the already running decode");
    assert_eq!(engine.stats().cache_bytes, 0);
}

#[test]
fn corrupt_page_fails_only_that_page() {
    let src = open_path(
        &common::small_corpus().join("mixed-small.cbz"),
        Limits::default(),
    )
    .unwrap();
    let dir = tempfile::tempdir().unwrap();
    let cbz = dir.path().join("broken.cbz");
    let mut jpeg_garbage = vec![0xFF, 0xD8, 0xFF, 0xE0];
    jpeg_garbage.extend((0..64u8).map(|i| i.wrapping_mul(37)));
    write_cbz(
        &cbz,
        &[
            ("1.jpg", src.read(0).unwrap()),
            ("2.jpg", jpeg_garbage),
            ("3.jpg", src.read(2).unwrap()),
            ("4.jpg", b"this is not an image at all".to_vec()),
        ],
    );

    let engine = engine();
    let book = engine.open_path(cbz.to_str().unwrap().to_string()).unwrap();
    assert_eq!(book.page_count(), 4);
    let render = |page: u32| {
        // The corrupt page has no usable dimensions; ask for a fixed small tile of the whole page.
        let info = book.page_info(page)?;
        let mut dst = vec![0u8; 16 * 16 * 4];
        let req = TileRequest {
            page,
            region: full_region(info),
            out_width: 16,
            out_height: 16,
        };
        book.render_tile(&req, &mut dst, 16 * 4, &CancelToken::new())
    };
    assert_eq!(render(0), Ok(()));
    match render(1) {
        Err(EngineError::Corrupt { .. }) => {}
        other => panic!("expected Corrupt for page 1, got {other:?}"),
    }
    assert_eq!(render(2), Ok(()));
    // Garbage without any image magic is a different, equally non-fatal error.
    match render(3) {
        Err(EngineError::UnsupportedFormat { .. }) => {}
        other => panic!("expected UnsupportedFormat for page 3, got {other:?}"),
    }
    // Errors are not memoized and do not poison later calls.
    assert!(book.page_info(1).is_err());
    assert_eq!(render(0), Ok(()));
}

#[test]
fn corrupt_page_body_reports_corrupt_on_render() {
    // A valid header (so `page_info` works) followed by a destroyed body: the failure is at decode time.
    let src = open_path(
        &common::small_corpus().join("mixed-small.cbz"),
        Limits::default(),
    )
    .unwrap();
    let mut good = src.read(0).unwrap();
    let keep = good.len() / 3;
    good.truncate(keep);
    for b in good.iter_mut().skip(keep / 2) {
        *b = 0x5A;
    }
    let dir = tempfile::tempdir().unwrap();
    let cbz = dir.path().join("truncated.cbz");
    write_cbz(&cbz, &[("1.jpg", good), ("2.jpg", src.read(1).unwrap())]);
    let engine = engine();
    let book = engine.open_path(cbz.to_str().unwrap().to_string()).unwrap();
    let info = book.page_info(0).unwrap();
    let mut dst = vec![0u8; 16 * 16 * 4];
    let req = TileRequest {
        page: 0,
        region: full_region(info),
        out_width: 16,
        out_height: 16,
    };
    match book.render_tile(&req, &mut dst, 64, &CancelToken::new()) {
        Err(EngineError::Corrupt { .. }) => {}
        other => panic!("expected Corrupt, got {other:?}"),
    }
    // The next page is unaffected, and nothing broken stayed in the cache.
    render_fit(&book, 1);
}

#[test]
fn render_into_padded_stride_leaves_padding_untouched() {
    let engine = engine();
    let book = open(&engine, "mixed-small.cbz");
    let info = book.page_info(0).unwrap();
    let (w, h) = (40u32, 50u32);
    let stride = w as usize * 4 + 24;
    let mut dst = vec![0xABu8; stride * h as usize];
    let req = TileRequest {
        page: 0,
        region: full_region(info),
        out_width: w,
        out_height: h,
    };
    book.render_tile(&req, &mut dst, stride, &CancelToken::new())
        .unwrap();
    for y in 0..h as usize {
        let row = &dst[y * stride..(y + 1) * stride];
        assert!(row[..w as usize * 4]
            .iter()
            .skip(3)
            .step_by(4)
            .all(|&a| a == 255));
        if y + 1 < h as usize {
            assert!(
                row[w as usize * 4..].iter().all(|&b| b == 0xAB),
                "padding of row {y} was written"
            );
        }
    }
}

#[test]
fn page_info_reads_past_the_probe_prefixes() {
    // Real JPEG with 40 KB (beyond the 16 KiB prefix) and then 400 KB (beyond the 256 KiB prefix) of APPn
    // padding in front of the frame header.
    let src = open_path(
        &common::small_corpus().join("screentone.cbz"),
        Limits::default(),
    )
    .unwrap();
    let plain = src.read(0).unwrap();
    assert_eq!(&plain[..2], &[0xFF, 0xD8]);
    let padded = |total: usize| {
        let mut out = vec![0xFF, 0xD8];
        let mut left = total;
        while left > 0 {
            let n = left.min(60_000);
            out.extend([0xFF, 0xE1]);
            out.extend(((n + 2) as u16).to_be_bytes());
            out.extend(std::iter::repeat_n(0u8, n));
            left -= n;
        }
        out.extend(&plain[2..]);
        out
    };
    let dir = tempfile::tempdir().unwrap();
    let cbz = dir.path().join("padded.cbz");
    write_cbz(
        &cbz,
        &[("1.jpg", padded(40_000)), ("2.jpg", padded(400_000))],
    );
    let engine = engine();
    let book = engine.open_path(cbz.to_str().unwrap().to_string()).unwrap();
    let expected = PageInfo {
        width: 80,
        height: 120,
        format: ImageFormat::Jpeg,
    };
    assert_eq!(book.page_info(0).unwrap(), expected);
    assert_eq!(book.page_info(1).unwrap(), expected);
    // Memoized: asking again returns the same value.
    assert_eq!(book.page_info(1).unwrap(), expected);
}

fn write_cbz(path: &std::path::Path, entries: &[(&str, Vec<u8>)]) {
    let file = std::fs::File::create(path).unwrap();
    let mut w = zip::ZipWriter::new(file);
    let opts =
        zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
    for (name, data) in entries {
        w.start_file(*name, opts).unwrap();
        w.write_all(data).unwrap();
    }
    w.finish().unwrap();
}
