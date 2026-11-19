//! Engine benches (Phase 1, Task 10): open, decode, resize and render, on the generated corpus.
//!
//! Corpus directory: `$PV_CORPUS`, else `<CARGO_MANIFEST_DIR>/../corpus/generated`; if it is missing the bench panics
//! with "run: cargo run --release -p gen-corpus -- corpus/generated". `scripts/device-run.ps1` sets `PV_CORPUS` to the
//! directory it pushes the corpus files to.
//!
//! Only host runs are wiring checks. Performance numbers come from the reference device (spec section 3).
//!
//! Rules every bench follows:
//! * File bytes and decoded inputs are prepared once, outside the timed loops, lazily (a bench that a name filter
//!   skips never reads its inputs), and are checked before timing (sizes, page counts, output not blank) so a wrong
//!   bench fails loudly instead of timing nothing.
//! * Every group uses flat sampling (one fixed iteration count per sample), so a run takes about
//!   `max(measurement_time, sample_size x time per iteration)` plus the warm-up instead of the ~n/2 times longer
//!   linear sampling (which would make the 1 s huge-image decodes take a minute each).
//! * Per-group `sample_size`, `warm_up_time` and `measurement_time` only bound the *length* of a run (the phone
//!   throttles thermally, so a full device run must stay short). Criterion's statistics and the quality of what is
//!   measured are the same as with its defaults; no workload is reduced.
//!
//! Bench ids: `open/many-pages`, `open/many-pages+probe-all`, `decode/<input>/d<denom>`, `resize/...`,
//! `render/cold-fit`, `render/warm-fit`.

mod support;

use criterion::{criterion_group, criterion_main, BatchSize, Criterion, SamplingMode};
use proviewer_core::archive::open_path;
use proviewer_core::decode::{
    decode, probe, supported_denoms, DecodedImage, ImageFormat, PageInfo, PixelLayout,
};
use proviewer_core::engine::{fit_size, Book, CancelToken, Engine, EngineConfig, TileRequest};
use proviewer_core::limits::Limits;
use proviewer_core::resize::{choose_denom, render_region, Rect};
use std::hint::black_box;
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};
use std::time::Duration;
use support::{assert_varied, corpus_file};

/// Viewport used for every measurement (`docs/perf/reference-device.md`).
const VIEW_W: u32 = 1080;
const VIEW_H: u32 = 2378;
const MANY_PAGES: u32 = 500;

fn path_of(archive: &str) -> String {
    let path: PathBuf = corpus_file(archive);
    path.to_str().expect("corpus path is UTF-8").to_string()
}

// ---------------------------------------------------------------------------------------------------------------
// Lazily prepared inputs
// ---------------------------------------------------------------------------------------------------------------

/// Which entry of an archive to take.
#[derive(Clone, Copy)]
enum Pick {
    Index(usize),
    Suffix(&'static str),
}

/// The raw bytes of one page, read once from the archive (outside every timed loop), and its probed header.
struct PageBytes {
    bytes: Vec<u8>,
    info: PageInfo,
}

struct LazyPage {
    archive: &'static str,
    pick: Pick,
    expect: ImageFormat,
    cell: OnceLock<PageBytes>,
}

impl LazyPage {
    const fn new(archive: &'static str, pick: Pick, expect: ImageFormat) -> Self {
        LazyPage {
            archive,
            pick,
            expect,
            cell: OnceLock::new(),
        }
    }

    fn get(&self) -> &PageBytes {
        self.cell.get_or_init(|| {
            let source = open_path(
                std::path::Path::new(&path_of(self.archive)),
                Limits::default(),
            )
            .unwrap_or_else(|e| panic!("open {}: {e}", self.archive));
            let index = match self.pick {
                Pick::Index(i) => i,
                Pick::Suffix(s) => source
                    .pages()
                    .iter()
                    .position(|p| p.name.ends_with(s))
                    .unwrap_or_else(|| panic!("{}: no page ending in {s}", self.archive)),
            };
            let bytes = source
                .read(index)
                .unwrap_or_else(|e| panic!("read {} page {index}: {e}", self.archive));
            let info = probe(&bytes).unwrap_or_else(|e| panic!("probe {}: {e}", self.archive));
            assert_eq!(
                info.format, self.expect,
                "{}: unexpected format",
                self.archive
            );
            PageBytes { bytes, info }
        })
    }
}

static SCREENTONE_P0: LazyPage = LazyPage::new("screentone.cbz", Pick::Index(0), ImageFormat::Jpeg);
static MIXED_P0: LazyPage = LazyPage::new("mixed-small.cbz", Pick::Index(0), ImageFormat::Jpeg);
static HUGE_P0: LazyPage = LazyPage::new("huge-single.cbz", Pick::Index(0), ImageFormat::Jpeg);
static FORMATS_WEBP: LazyPage = LazyPage::new(
    "formats-and-names.cbz",
    Pick::Suffix(".webp"),
    ImageFormat::Webp,
);
static FORMATS_PNG: LazyPage = LazyPage::new(
    "formats-and-names.cbz",
    Pick::Suffix(".png"),
    ImageFormat::Png,
);

/// Decodes `page` at `denom` and checks the result before it is timed: size from the header, buffer length,
/// not blank.
fn checked_decode(page: &PageBytes, denom: u8, what: &str) -> DecodedImage {
    assert!(
        supported_denoms(page.info.format).contains(&denom),
        "{what}: denom {denom} is not supported for {:?}",
        page.info.format
    );
    let img = decode(&page.bytes, denom, &Limits::default())
        .unwrap_or_else(|e| panic!("{what}: decode failed: {e}"));
    assert_eq!(img.denom, denom, "{what}: decoded at another denom");
    let d = denom as u32;
    assert_eq!(img.width, page.info.width.div_ceil(d), "{what}: width");
    assert_eq!(img.height, page.info.height.div_ceil(d), "{what}: height");
    assert_eq!(
        img.pixels.len() as u64,
        img.width as u64 * img.height as u64 * img.layout.channels(),
        "{what}: pixel buffer length"
    );
    assert_varied(&img.pixels, what);
    img
}

/// The denom a fit render of the whole page for the measurement viewport would use.
fn fit_denom_of(info: PageInfo) -> u8 {
    let (w, h) = fit_size(info.width, info.height, VIEW_W, VIEW_H);
    let full = Rect {
        x: 0,
        y: 0,
        width: info.width,
        height: info.height,
    };
    choose_denom(full, w, h, supported_denoms(info.format))
}

fn check_rgba(dst: &[u8], out_w: u32, out_h: u32, what: &str) {
    assert_eq!(
        dst.len(),
        out_w as usize * out_h as usize * 4,
        "{what}: output size"
    );
    assert_varied(dst, what);
    assert!(
        dst.as_chunks::<4>().0.iter().all(|px| px[3] == 255),
        "{what}: output is not opaque"
    );
}

// ---------------------------------------------------------------------------------------------------------------
// open/*   (open/many-pages is budget d)
// ---------------------------------------------------------------------------------------------------------------

fn bench_open(c: &mut Criterion) {
    let mut g = c.benchmark_group("open");
    g.sampling_mode(SamplingMode::Flat);
    // Millisecond-scale iterations: criterion defaults (100 samples, 5 s) give a stable median.
    g.bench_function("many-pages", |b| {
        let path = path_of("many-pages.cbz");
        let engine = Engine::new(EngineConfig::default());
        let book = engine.open_path(path.clone()).expect("open many-pages");
        assert_eq!(book.page_count(), MANY_PAGES, "many-pages page count");
        drop(book);
        b.iter(|| black_box(engine.open_path(path.clone()).unwrap()));
    });
    // Informational (Phase 3 Webtoon layout): every iteration is one open plus 500 lazy header probes.
    g.sample_size(50);
    g.bench_function("many-pages+probe-all", |b| {
        let path = path_of("many-pages.cbz");
        let engine = Engine::new(EngineConfig::default());
        let probe_all = || {
            let book = engine.open_path(path.clone()).unwrap();
            assert_eq!(book.page_count(), MANY_PAGES, "many-pages page count");
            let mut pixels = 0u64;
            for i in 0..MANY_PAGES {
                let info = book.page_info(i).unwrap();
                pixels += info.width as u64 * info.height as u64;
            }
            (book, pixels)
        };
        let (_, pixels) = probe_all();
        assert!(pixels > 0, "many-pages probe-all saw empty pages");
        b.iter(|| black_box(probe_all()));
    });
    g.finish();
}

// ---------------------------------------------------------------------------------------------------------------
// decode/<input>/d<denom>
// ---------------------------------------------------------------------------------------------------------------

fn bench_decode(c: &mut Criterion) {
    let mut g = c.benchmark_group("decode");
    g.sampling_mode(SamplingMode::Flat);
    // Page-sized decodes take milliseconds to tens of ms: 50 samples in 5 s are plenty for a median.
    g.sample_size(50);

    // (id, input, denom). The fit denoms are asserted in setup: screentone fits at 1 (so d1 is both the full and the fit
    // decode), huge-single at 8.
    let cases: [(&str, &'static LazyPage, u8); 6] = [
        ("screentone-p0/d1", &SCREENTONE_P0, 1),
        ("mixed-small-p0/d1", &MIXED_P0, 1),
        ("huge-single-p0/d1", &HUGE_P0, 1),
        ("huge-single-p0/d8", &HUGE_P0, 8),
        ("formats-webp/d1", &FORMATS_WEBP, 1),
        ("formats-png/d1", &FORMATS_PNG, 1),
    ];
    for (id, input, denom) in cases {
        if id.starts_with("huge-single") {
            // 96 MP: ~1 s per full decode on the device, so 10 samples of one iteration each.
            g.sample_size(10)
                .warm_up_time(Duration::from_secs(1))
                .measurement_time(Duration::from_secs(12));
        } else {
            g.sample_size(50)
                .warm_up_time(Duration::from_secs(3))
                .measurement_time(Duration::from_secs(5));
        }
        g.bench_function(id, |b| {
            let page = input.get();
            if id.starts_with("screentone") {
                assert_eq!(fit_denom_of(page.info), 1, "screentone fit denom");
            }
            if id == "huge-single-p0/d8" {
                assert_eq!(fit_denom_of(page.info), 8, "huge-single fit denom");
            }
            drop(checked_decode(page, denom, id));
            let limits = Limits::default();
            b.iter(|| black_box(decode(black_box(&page.bytes), denom, &limits).unwrap()));
        });
    }
    g.finish();
}

// ---------------------------------------------------------------------------------------------------------------
// resize/*   (render_region on an already decoded image: linear-light Lanczos3)
// ---------------------------------------------------------------------------------------------------------------

/// Deterministic RGB page (no RNG): smooth colour ramps plus a hatch so every channel varies.
fn synthetic_rgb(width: u32, height: u32) -> DecodedImage {
    let mut pixels = Vec::with_capacity(width as usize * height as usize * 3);
    for y in 0..height {
        for x in 0..width {
            let hatch = ((x + y) % 7 == 0) as u32 * 60;
            pixels.push(((x * 255 / width) + hatch).min(255) as u8);
            pixels.push(((y * 255 / height) + hatch).min(255) as u8);
            pixels.push(((x ^ y) & 0xFF) as u8);
        }
    }
    DecodedImage {
        width,
        height,
        layout: PixelLayout::Rgb8,
        pixels,
        denom: 1,
    }
}

/// Renders `region` of `img` (whose page size is `page_w x page_h`) to `out_w x out_h` once, checked.
fn checked_render(
    img: &DecodedImage,
    (page_w, page_h): (u32, u32),
    region: Rect,
    (out_w, out_h): (u32, u32),
    what: &str,
) -> Vec<u8> {
    let mut dst = vec![0u8; out_w as usize * out_h as usize * 4];
    render_region(
        img,
        page_w,
        page_h,
        region,
        out_w,
        out_h,
        &mut dst,
        out_w as usize * 4,
    )
    .unwrap_or_else(|e| panic!("{what}: render_region failed: {e}"));
    check_rgba(&dst, out_w, out_h, what);
    dst
}

fn bench_resize(c: &mut Criterion) {
    static GRAY: OnceLock<DecodedImage> = OnceLock::new();
    static RGB: OnceLock<DecodedImage> = OnceLock::new();
    static HUGE: OnceLock<DecodedImage> = OnceLock::new();

    let mut g = c.benchmark_group("resize");
    g.sampling_mode(SamplingMode::Flat);
    g.sample_size(50).measurement_time(Duration::from_secs(5));

    let page = Rect {
        x: 0,
        y: 0,
        width: 1600,
        height: 2400,
    };
    let page_out = fit_size(1600, 2400, VIEW_W, VIEW_H);
    assert_eq!(page_out, (1080, 1620), "fit size of a 1600x2400 page");

    g.bench_function("gray-1600x2400-to-1080x1620", |b| {
        let img = GRAY.get_or_init(|| checked_decode(SCREENTONE_P0.get(), 1, "resize gray input"));
        assert_eq!(
            (img.width, img.height, img.layout),
            (1600, 2400, PixelLayout::Gray8)
        );
        let mut dst = checked_render(img, (1600, 2400), page, page_out, "resize gray");
        b.iter(|| {
            render_region(
                img,
                1600,
                2400,
                page,
                page_out.0,
                page_out.1,
                &mut dst,
                page_out.0 as usize * 4,
            )
            .unwrap();
            black_box(&dst);
        });
    });

    g.bench_function("rgb-1600x2400-to-1080x1620", |b| {
        let img = RGB.get_or_init(|| synthetic_rgb(1600, 2400));
        let mut dst = checked_render(img, (1600, 2400), page, page_out, "resize rgb");
        b.iter(|| {
            render_region(
                img,
                1600,
                2400,
                page,
                page_out.0,
                page_out.1,
                &mut dst,
                page_out.0 as usize * 4,
            )
            .unwrap();
            black_box(&dst);
        });
    });

    // A 2x zoom tile out of the 96 MP page: 2160x4756 source pixels to the 1080x2378 viewport. The page is decoded
    // at denom 1 so the bench measures a real 2:1 Lanczos downscale of that many source pixels (the engine would
    // pick denom 2 for this tile, which turns it into a 1:1 pass; that case is covered by render/*).
    g.measurement_time(Duration::from_secs(10)).sample_size(20);
    g.bench_function("huge-region-2160x4756-to-1080x2378", |b| {
        let region = Rect {
            x: 3000,
            y: 1500,
            width: 2160,
            height: 4756,
        };
        let out = fit_size(region.width, region.height, VIEW_W, VIEW_H);
        assert_eq!(out, (1080, 2378), "fit size of the huge region");
        let info = HUGE_P0.get().info;
        assert_eq!(
            (info.width, info.height),
            (12000, 8000),
            "huge-single page size"
        );
        let img = HUGE.get_or_init(|| checked_decode(HUGE_P0.get(), 1, "resize huge input"));
        let mut dst = checked_render(
            img,
            (info.width, info.height),
            region,
            out,
            "resize huge region",
        );
        b.iter(|| {
            render_region(
                img,
                info.width,
                info.height,
                region,
                out.0,
                out.1,
                &mut dst,
                out.0 as usize * 4,
            )
            .unwrap();
            black_box(&dst);
        });
    });
    g.finish();
}

// ---------------------------------------------------------------------------------------------------------------
// render/*   (the whole Book::render_tile path: probe, decode, cache, resize)
// ---------------------------------------------------------------------------------------------------------------

fn fit_request(book: &Book) -> (TileRequest, usize) {
    let info = book.page_info(0).expect("page_info of screentone p0");
    let (out_w, out_h) = fit_size(info.width, info.height, VIEW_W, VIEW_H);
    assert_eq!((out_w, out_h), (1080, 1620), "screentone fit size");
    let req = TileRequest {
        page: 0,
        region: Rect {
            x: 0,
            y: 0,
            width: info.width,
            height: info.height,
        },
        out_width: out_w,
        out_height: out_h,
    };
    (req, out_w as usize * 4)
}

fn bench_render(c: &mut Criterion) {
    static WARM: OnceLock<(Arc<Engine>, Arc<Book>)> = OnceLock::new();

    let mut g = c.benchmark_group("render");
    g.sampling_mode(SamplingMode::Flat);
    g.sample_size(30).measurement_time(Duration::from_secs(5));

    // Cold: a new Engine (empty cache) per iteration, so every iteration probes, decodes and resizes. The engine
    // and book are built, and dropped, outside the timed section.
    g.bench_function("cold-fit", |b| {
        let path = path_of("screentone.cbz");
        let cancel = CancelToken::new();
        let fresh = || {
            let engine = Engine::new(EngineConfig::default());
            let book = engine.open_path(path.clone()).unwrap();
            (engine, book)
        };
        // Sanity before timing: one cold render decodes exactly once and fills the output.
        let (engine, book) = fresh();
        let (req, stride) = fit_request(&book);
        let mut dst = vec![0u8; stride * req.out_height as usize];
        book.render_tile(&req, &mut dst, stride, &cancel).unwrap();
        check_rgba(&dst, req.out_width, req.out_height, "render cold-fit");
        assert_eq!(engine.stats().decodes, 1, "cold render decodes once");
        assert_eq!(
            engine.stats().cache_hits,
            0,
            "cold render is not a cache hit"
        );
        drop((book, engine));
        b.iter_batched(
            fresh,
            |(engine, book)| {
                book.render_tile(&req, &mut dst, stride, &cancel).unwrap();
                (engine, book)
            },
            BatchSize::PerIteration,
        );
    });

    // Warm: the decoded page stays in the cache, so only the resize and the RGBA write are timed.
    g.bench_function("warm-fit", |b| {
        let (engine, book) = WARM.get_or_init(|| {
            let engine = Engine::new(EngineConfig::default());
            let book = engine.open_path(path_of("screentone.cbz")).unwrap();
            (engine, book)
        });
        let cancel = CancelToken::new();
        let (req, stride) = fit_request(book);
        let mut dst = vec![0u8; stride * req.out_height as usize];
        book.render_tile(&req, &mut dst, stride, &cancel).unwrap(); // fills the cache
        let hits = engine.stats().cache_hits;
        book.render_tile(&req, &mut dst, stride, &cancel).unwrap();
        check_rgba(&dst, req.out_width, req.out_height, "render warm-fit");
        let stats = engine.stats();
        assert_eq!(stats.decodes, 1, "warm render must not decode again");
        assert_eq!(
            stats.cache_hits,
            hits + 1,
            "warm render must be a cache hit"
        );
        b.iter(|| {
            book.render_tile(&req, &mut dst, stride, &cancel).unwrap();
            black_box(&dst);
        });
    });
    g.finish();
}

criterion_group!(
    benches,
    bench_open,
    bench_decode,
    bench_resize,
    bench_render
);
criterion_main!(benches);
