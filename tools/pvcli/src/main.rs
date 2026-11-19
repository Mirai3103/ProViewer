//! pvcli: desktop and device CLI for the ProViewer engine (`info`, `render`, `read`).

mod mem;

use proviewer_core::engine::{
    fit_size, Book, CancelToken, Engine, EngineConfig, ReadDirection, TileRequest,
};
use proviewer_core::error::EngineError;
use proviewer_core::resize::Rect;
use std::io::Write;
use std::sync::Arc;
use std::time::{Duration, Instant};

const USAGE: &str = "usage:
  pvcli info <book>
  pvcli render <book> <page> --fit <W>x<H> -o <out.png>
  pvcli read <book> --fit <W>x<H> [--cache-mb N] [--dwell-ms N] [--pages N]";

#[derive(Debug, PartialEq)]
enum Command {
    Info {
        book: String,
    },
    Render {
        book: String,
        page: u32,
        fit: (u32, u32),
        out: String,
    },
    Read(ReadOpts),
}

#[derive(Debug, PartialEq)]
struct ReadOpts {
    book: String,
    fit: (u32, u32),
    cache_mb: Option<u64>,
    dwell_ms: u64,
    pages: Option<u32>,
}

#[derive(Debug, PartialEq)]
struct Percentiles {
    p50: f64,
    p95: f64,
    max: f64,
}

#[derive(Debug, PartialEq)]
enum RowOutcome {
    Decoded(bool),
    Error,
}

#[derive(Debug, PartialEq)]
struct Summary {
    cold_first_ms: f64,
    p50_ms: f64,
    p95_ms: f64,
    max_ms: f64,
    decodes: u64,
    cache_hits: u64,
    peak_rss_kb: Option<u64>,
}

/// Largest output buffer pvcli will allocate for a viewport (RGBA bytes).
const MAX_VIEWPORT_BYTES: u64 = 1 << 30;

const ROW_HEADER: &str = "page,ms,decoded";
const SUMMARY_HEADER: &str = "cold_first_ms,p50_ms,p95_ms,max_ms,decodes,cache_hits,peak_rss_kb";

/// `WxH` with both sides positive; anything else is `None`.
fn parse_fit(s: &str) -> Option<(u32, u32)> {
    let (w, h) = s.split_once('x')?;
    let (w, h) = (w.parse::<u32>().ok()?, h.parse::<u32>().ok()?);
    (w > 0 && h > 0).then_some((w, h))
}

/// Nearest-rank percentiles (rank = ceil(p/100 * n), 1-based) of the samples; zeros for no samples.
fn summary_percentiles(ms: &[f64]) -> Percentiles {
    if ms.is_empty() {
        return Percentiles {
            p50: 0.0,
            p95: 0.0,
            max: 0.0,
        };
    }
    let mut sorted = ms.to_vec();
    sorted.sort_by(|a, b| a.total_cmp(b));
    let rank = |p: usize| sorted[(sorted.len() * p).div_ceil(100).clamp(1, sorted.len()) - 1];
    Percentiles {
        p50: rank(50),
        p95: rank(95),
        max: sorted[sorted.len() - 1],
    }
}

/// Parses `args` (without the program name). Every flag takes one value; each subcommand accepts its own
/// flags and a fixed number of positional arguments.
fn parse_args(args: &[String]) -> Result<Command, String> {
    let (cmd, rest) = args.split_first().ok_or("missing subcommand")?;
    let (allowed, positionals): (&[&str], usize) = match cmd.as_str() {
        "info" => (&[], 1),
        "render" => (&["--fit", "-o"], 2),
        "read" => (&["--fit", "--cache-mb", "--dwell-ms", "--pages"], 1),
        other => return Err(format!("unknown subcommand '{other}'")),
    };
    let mut pos: Vec<&str> = Vec::new();
    let mut flags: Vec<(&str, &str)> = Vec::new();
    let mut i = 0;
    while i < rest.len() {
        let a = rest[i].as_str();
        if a.starts_with('-') {
            let flag = *allowed
                .iter()
                .find(|f| **f == a)
                .ok_or(format!("unknown flag '{a}' for '{cmd}'"))?;
            let value = rest.get(i + 1).ok_or(format!("{a} needs a value"))?;
            if flags.iter().any(|(f, _)| *f == flag) {
                return Err(format!("{a} given twice"));
            }
            flags.push((flag, value));
            i += 2;
        } else {
            pos.push(a);
            i += 1;
        }
    }
    if pos.len() != positionals {
        return Err(format!(
            "'{cmd}' takes {positionals} positional argument(s), got {}",
            pos.len()
        ));
    }
    let flag = |name: &str| flags.iter().find(|(f, _)| *f == name).map(|(_, v)| *v);
    let required_fit = || -> Result<(u32, u32), String> {
        let v = flag("--fit").ok_or("--fit <W>x<H> is required")?;
        parse_fit(v).ok_or(format!("invalid --fit '{v}' (expected <W>x<H>, both > 0)"))
    };
    let number = |name: &str| -> Result<Option<u64>, String> {
        flag(name)
            .map(|v| {
                v.parse::<u64>()
                    .map_err(|_| format!("invalid {name} '{v}' (expected a non-negative integer)"))
            })
            .transpose()
    };
    match cmd.as_str() {
        "info" => Ok(Command::Info {
            book: pos[0].to_string(),
        }),
        "render" => Ok(Command::Render {
            book: pos[0].to_string(),
            page: pos[1]
                .parse()
                .map_err(|_| format!("invalid page '{}'", pos[1]))?,
            fit: required_fit()?,
            out: flag("-o").ok_or("-o <out.png> is required")?.to_string(),
        }),
        _ => {
            let pages = match number("--pages")? {
                Some(0) => return Err("--pages must be at least 1".into()),
                Some(n) => Some(u32::try_from(n).map_err(|_| "--pages is too large".to_string())?),
                None => None,
            };
            Ok(Command::Read(ReadOpts {
                book: pos[0].to_string(),
                fit: required_fit()?,
                cache_mb: number("--cache-mb")?,
                dwell_ms: number("--dwell-ms")?.unwrap_or(50),
                pages,
            }))
        }
    }
}

fn format_row(page: u32, ms: f64, outcome: &RowOutcome) -> String {
    match outcome {
        RowOutcome::Decoded(d) => format!("{page},{ms:.3},{d}"),
        RowOutcome::Error => format!("{page},{ms:.3},ERR"),
    }
}

fn format_summary(s: &Summary) -> String {
    let rss = s.peak_rss_kb.map_or("NA".to_string(), |v| v.to_string());
    format!(
        "{:.3},{:.3},{:.3},{:.3},{},{},{}",
        s.cold_first_ms, s.p50_ms, s.p95_ms, s.max_ms, s.decodes, s.cache_hits, rss
    )
}

fn ms_since(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

/// Output buffers are allocated for the viewport at most; refuse absurd viewports before allocating.
fn viewport_buffer_len(fit: (u32, u32)) -> Result<usize, String> {
    let len = fit.0 as u64 * fit.1 as u64 * 4;
    if len > MAX_VIEWPORT_BYTES {
        return Err(format!(
            "--fit {}x{} needs {len} bytes, max {MAX_VIEWPORT_BYTES}",
            fit.0, fit.1
        ));
    }
    Ok(len as usize)
}

fn open_book(engine: &Engine, path: &str) -> Result<Arc<Book>, String> {
    engine
        .open_path(path.to_string())
        .map_err(|e| format!("cannot open {path}: {e}"))
}

/// Renders the whole of `page` at the fit size for `fit` into the start of `buf` (stride = width * 4).
/// Returns the output size.
fn render_fit(
    book: &Book,
    page: u32,
    fit: (u32, u32),
    buf: &mut [u8],
) -> Result<(u32, u32), EngineError> {
    let info = book.page_info(page)?;
    let (out_w, out_h) = fit_size(info.width, info.height, fit.0, fit.1);
    let req = TileRequest {
        page,
        region: Rect {
            x: 0,
            y: 0,
            width: info.width,
            height: info.height,
        },
        out_width: out_w,
        out_height: out_h,
    };
    let len = out_w as usize * out_h as usize * 4;
    book.render_tile(
        &req,
        &mut buf[..len],
        out_w as usize * 4,
        &CancelToken::new(),
    )?;
    Ok((out_w, out_h))
}

/// Prints `open_ms`, one tab-separated line per page and `probe_all_ms`. Returns how many pages failed to
/// probe (printed as `index<TAB>name<TAB>-<TAB>-<TAB>ERR: message`).
fn run_info(book: &str, out: &mut dyn Write) -> Result<u32, String> {
    let engine = Engine::new(EngineConfig::default());
    let t = Instant::now();
    let book = open_book(&engine, book)?;
    let open_ms = ms_since(t);
    let t = Instant::now();
    let infos: Vec<_> = (0..book.page_count()).map(|i| book.page_info(i)).collect();
    let probe_all_ms = ms_since(t);
    let io = |e: std::io::Error| e.to_string();
    writeln!(out, "open_ms={open_ms:.3}").map_err(io)?;
    let mut failed = 0;
    for (i, info) in infos.iter().enumerate() {
        let i = i as u32;
        let name = book.page_name(i).map_err(|e| e.to_string())?;
        match info {
            Ok(p) => writeln!(
                out,
                "{i}\t{name}\t{}\t{}\t{:?}",
                p.width, p.height, p.format
            ),
            Err(e) => {
                failed += 1;
                writeln!(out, "{i}\t{name}\t-\t-\tERR: {e}")
            }
        }
        .map_err(io)?;
    }
    writeln!(out, "probe_all_ms={probe_all_ms:.3}").map_err(io)?;
    Ok(failed)
}

/// Writes the fit-size render of `page` as an RGBA PNG. Returns the PNG size.
fn run_render(book: &str, page: u32, fit: (u32, u32), out_png: &str) -> Result<(u32, u32), String> {
    let mut buf = vec![0u8; viewport_buffer_len(fit)?];
    let engine = Engine::new(EngineConfig::default());
    let book = open_book(&engine, book)?;
    let (w, h) = render_fit(&book, page, fit, &mut buf).map_err(|e| format!("page {page}: {e}"))?;
    let file =
        std::fs::File::create(out_png).map_err(|e| format!("cannot create {out_png}: {e}"))?;
    let mut enc = png::Encoder::new(std::io::BufWriter::new(file), w, h);
    enc.set_color(png::ColorType::Rgba);
    enc.set_depth(png::BitDepth::Eight);
    let mut writer = enc.write_header().map_err(|e| e.to_string())?;
    writer
        .write_image_data(&buf[..w as usize * h as usize * 4])
        .map_err(|e| e.to_string())?;
    writer.finish().map_err(|e| e.to_string())?;
    Ok((w, h))
}

/// Simulates reading forward. Per page: prefetch hint, timed fit render (including the lazy page probe),
/// then the dwell. A page that fails to render prints `page,ms,ERR` on stdout and `ERR: page N: ...` on
/// stderr, is left out of the statistics, and does not stop the run. Returns how many pages failed.
fn run_read(opts: &ReadOpts, out: &mut dyn Write, err: &mut dyn Write) -> Result<u32, String> {
    let mut buf = vec![0u8; viewport_buffer_len(opts.fit)?];
    let mut config = EngineConfig::default();
    if let Some(mb) = opts.cache_mb {
        config.cache_bytes = mb.saturating_mul(1024 * 1024);
    }
    let engine = Engine::new(config);
    let book = open_book(&engine, &opts.book)?;
    let count = book.page_count();
    let n = opts.pages.map_or(count, |p| p.min(count));
    let io = |e: std::io::Error| e.to_string();
    writeln!(out, "{ROW_HEADER}").map_err(io)?;
    let mut samples: Vec<f64> = Vec::new();
    let mut failed = 0;
    for page in 0..n {
        book.prefetch_hint(page, ReadDirection::Forward, opts.fit.0, opts.fit.1);
        let hits_before = engine.stats().cache_hits;
        let t = Instant::now();
        let result = render_fit(&book, page, opts.fit, &mut buf);
        let ms = ms_since(t);
        // `decoded`: this render ran the decode itself. `render_tile` counts a cache hit exactly when it did
        // not (a prefetched page, or one it joined mid-decode). The global `decodes` counter cannot tell:
        // it also counts the prefetch workers' decodes of the following pages, which run during the render.
        let decoded = engine.stats().cache_hits == hits_before;
        let outcome = match &result {
            Ok(_) => {
                samples.push(ms);
                RowOutcome::Decoded(decoded)
            }
            Err(e) => {
                failed += 1;
                writeln!(err, "ERR: page {page}: {e}").map_err(io)?;
                RowOutcome::Error
            }
        };
        writeln!(out, "{}", format_row(page, ms, &outcome)).map_err(io)?;
        out.flush().map_err(io)?;
        std::thread::sleep(Duration::from_millis(opts.dwell_ms));
    }
    let p = summary_percentiles(&samples);
    let stats = engine.stats();
    let summary = Summary {
        cold_first_ms: samples.first().copied().unwrap_or(0.0),
        p50_ms: p.p50,
        p95_ms: p.p95,
        max_ms: p.max,
        decodes: stats.decodes,
        cache_hits: stats.cache_hits,
        peak_rss_kb: mem::peak_rss_kb(),
    };
    writeln!(out, "{SUMMARY_HEADER}\n{}", format_summary(&summary)).map_err(io)?;
    Ok(failed)
}

/// Exit codes: 0 ok, 1 runtime failure (including any failed page), 2 usage error.
fn real_main(args: &[String]) -> i32 {
    let command = match parse_args(args) {
        Ok(c) => c,
        Err(msg) => {
            eprintln!("pvcli: {msg}\n{USAGE}");
            return 2;
        }
    };
    let stdout = std::io::stdout();
    let mut out = stdout.lock();
    let result = match command {
        Command::Info { book } => run_info(&book, &mut out),
        Command::Render {
            book,
            page,
            fit,
            out: path,
        } => run_render(&book, page, fit, &path).map(|(w, h)| {
            println!("wrote {path} ({w}x{h})");
            0
        }),
        Command::Read(opts) => run_read(&opts, &mut out, &mut std::io::stderr()),
    };
    let _ = out.flush();
    match result {
        Ok(0) => 0,
        Ok(failed) => {
            eprintln!("pvcli: {failed} page(s) failed");
            1
        }
        Err(msg) => {
            eprintln!("pvcli: {msg}");
            1
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    std::process::exit(real_main(&args));
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::{Path, PathBuf};

    fn args(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    fn png_bytes(w: u32, h: u32, gray: u8) -> Vec<u8> {
        let mut out = Vec::new();
        let mut enc = png::Encoder::new(&mut out, w, h);
        enc.set_color(png::ColorType::Grayscale);
        enc.set_depth(png::BitDepth::Eight);
        enc.write_header()
            .unwrap()
            .write_image_data(&vec![gray; (w * h) as usize])
            .unwrap();
        out
    }

    /// A CBZ with `good` valid 40x60 PNG pages; with `broken_at` a garbage `.png` entry at that index.
    fn make_cbz(dir: &Path, good: usize, broken_at: Option<usize>) -> PathBuf {
        let mut entries = Vec::new();
        for i in 0..good {
            entries.push((
                format!("{:03}.png", i + 1),
                png_bytes(40, 60, (i * 40) as u8),
            ));
        }
        if let Some(at) = broken_at {
            entries.insert(
                at,
                (
                    format!("{:03}.png", at + 1),
                    b"not an image at all".to_vec(),
                ),
            );
        }
        // Renumber so natural order equals vector order.
        let entries: Vec<(String, Vec<u8>)> = entries
            .into_iter()
            .enumerate()
            .map(|(i, (_, d))| (format!("{:03}.png", i + 1), d))
            .collect();
        let path = dir.join("t.cbz");
        gen_corpus::write_cbz(&path, &entries).unwrap();
        path
    }

    #[test]
    fn parse_fit_accepts_wxh_only() {
        assert_eq!(parse_fit("1080x2378"), Some((1080, 2378)));
        assert_eq!(parse_fit("1080"), None);
        assert_eq!(parse_fit("0x10"), None);
        assert_eq!(parse_fit("10x"), None);
        assert_eq!(parse_fit("axb"), None);
    }

    #[test]
    fn summary_percentiles_nearest_rank() {
        let ms: Vec<f64> = (1..=10).map(|i| i as f64 * 10.0).collect();
        let p = summary_percentiles(&ms);
        assert_eq!(
            p,
            Percentiles {
                p50: 50.0,
                p95: 100.0,
                max: 100.0
            }
        );
        // Order of the input must not matter.
        let mut rev = ms.clone();
        rev.reverse();
        assert_eq!(summary_percentiles(&rev), p);
        assert_eq!(
            summary_percentiles(&[]),
            Percentiles {
                p50: 0.0,
                p95: 0.0,
                max: 0.0
            }
        );
    }

    #[test]
    fn peak_rss_kb_is_some_and_positive() {
        let kb = mem::peak_rss_kb();
        assert!(kb.is_some_and(|v| v > 0), "got {kb:?}");
    }

    #[test]
    fn parse_info_and_render() {
        assert_eq!(
            parse_args(&args(&["info", "a.cbz"])),
            Ok(Command::Info {
                book: "a.cbz".into()
            })
        );
        assert_eq!(
            parse_args(&args(&[
                "render", "a.cbz", "3", "--fit", "100x200", "-o", "o.png"
            ])),
            Ok(Command::Render {
                book: "a.cbz".into(),
                page: 3,
                fit: (100, 200),
                out: "o.png".into()
            })
        );
        // Flag order is free.
        assert!(parse_args(&args(&[
            "render", "a.cbz", "0", "-o", "o.png", "--fit", "1x1"
        ]))
        .is_ok());
    }

    #[test]
    fn parse_read_defaults_and_options() {
        assert_eq!(
            parse_args(&args(&["read", "a.cbz", "--fit", "1080x2378"])),
            Ok(Command::Read(ReadOpts {
                book: "a.cbz".into(),
                fit: (1080, 2378),
                cache_mb: None,
                dwell_ms: 50,
                pages: None
            }))
        );
        assert_eq!(
            parse_args(&args(&[
                "read",
                "a.cbz",
                "--fit",
                "10x20",
                "--pages",
                "7",
                "--cache-mb",
                "64",
                "--dwell-ms",
                "0"
            ])),
            Ok(Command::Read(ReadOpts {
                book: "a.cbz".into(),
                fit: (10, 20),
                cache_mb: Some(64),
                dwell_ms: 0,
                pages: Some(7)
            }))
        );
    }

    #[test]
    fn parse_errors_for_missing_and_unknown_arguments() {
        for bad in [
            vec![],
            vec!["frobnicate"],
            vec!["info"],
            vec!["info", "a", "b"],
            vec!["render", "a.cbz", "0", "-o", "o.png"],
            vec!["render", "a.cbz", "0", "--fit", "10x10"],
            vec!["render", "a.cbz", "x", "--fit", "10x10", "-o", "o.png"],
            vec!["read", "a.cbz"],
            vec!["read", "a.cbz", "--fit", "1080"],
            vec!["read", "a.cbz", "--fit", "10x10", "--bogus", "1"],
            vec!["read", "a.cbz", "--fit", "10x10", "--pages"],
            vec!["read", "a.cbz", "--fit", "10x10", "--pages", "many"],
        ] {
            assert!(parse_args(&args(&bad)).is_err(), "should reject {bad:?}");
        }
    }

    #[test]
    fn csv_row_and_summary_formatting() {
        assert_eq!(ROW_HEADER, "page,ms,decoded");
        assert_eq!(
            format_row(3, 12.3456, &RowOutcome::Decoded(true)),
            "3,12.346,true"
        );
        assert_eq!(
            format_row(4, 0.5, &RowOutcome::Decoded(false)),
            "4,0.500,false"
        );
        assert_eq!(format_row(5, 1.0, &RowOutcome::Error), "5,1.000,ERR");
        assert_eq!(
            SUMMARY_HEADER,
            "cold_first_ms,p50_ms,p95_ms,max_ms,decodes,cache_hits,peak_rss_kb"
        );
        let s = Summary {
            cold_first_ms: 40.0,
            p50_ms: 5.5,
            p95_ms: 9.0,
            max_ms: 40.0,
            decodes: 20,
            cache_hits: 7,
            peak_rss_kb: Some(123456),
        };
        assert_eq!(format_summary(&s), "40.000,5.500,9.000,40.000,20,7,123456");
        let none = Summary {
            peak_rss_kb: None,
            ..s
        };
        assert!(format_summary(&none).ends_with(",NA"));
    }

    #[test]
    fn info_prints_header_lines_per_page_and_probe_time() {
        let dir = tempfile::tempdir().unwrap();
        let cbz = make_cbz(dir.path(), 3, None);
        let mut out = Vec::new();
        assert_eq!(run_info(cbz.to_str().unwrap(), &mut out).unwrap(), 0);
        let text = String::from_utf8(out).unwrap();
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines.len(), 5, "{text}");
        assert!(lines[0].starts_with("open_ms="));
        assert_eq!(lines[1], "0\t001.png\t40\t60\tPng");
        assert_eq!(lines[3], "2\t003.png\t40\t60\tPng");
        assert!(lines[4].starts_with("probe_all_ms="));
    }

    #[test]
    fn render_writes_an_rgba_png_of_the_fit_size() {
        let dir = tempfile::tempdir().unwrap();
        let cbz = make_cbz(dir.path(), 1, None);
        let out = dir.path().join("o.png");
        // 40x60 fitted into 100x100 -> 67x100.
        let dims = run_render(cbz.to_str().unwrap(), 0, (100, 100), out.to_str().unwrap()).unwrap();
        assert_eq!(dims, (67, 100));
        let dec = png::Decoder::new(std::io::BufReader::new(std::fs::File::open(&out).unwrap()));
        let reader = dec.read_info().unwrap();
        let info = reader.info();
        assert_eq!((info.width, info.height), (67, 100));
        assert_eq!(info.color_type, png::ColorType::Rgba);
        assert!(run_render(cbz.to_str().unwrap(), 9, (10, 10), out.to_str().unwrap()).is_err());
    }

    #[test]
    fn read_prints_a_row_per_page_then_the_summary() {
        let dir = tempfile::tempdir().unwrap();
        let cbz = make_cbz(dir.path(), 4, None);
        let opts = ReadOpts {
            book: cbz.to_str().unwrap().into(),
            fit: (50, 50),
            cache_mb: Some(16),
            dwell_ms: 0,
            pages: Some(3),
        };
        let (mut out, mut err) = (Vec::new(), Vec::new());
        assert_eq!(run_read(&opts, &mut out, &mut err).unwrap(), 0);
        let text = String::from_utf8(out).unwrap();
        let lines: Vec<&str> = text.lines().collect();
        // header, 3 rows (--pages 3 of 4), summary header, summary values
        assert_eq!(lines.len(), 6, "{text}");
        assert_eq!(lines[0], "page,ms,decoded");
        assert!(
            lines[1].starts_with("0,")
                && (lines[1].ends_with(",true") || lines[1].ends_with(",false"))
        );
        assert!(lines[3].starts_with("2,"));
        assert_eq!(lines[4], SUMMARY_HEADER);
        assert_eq!(lines[5].split(',').count(), 7);
        assert!(err.is_empty());
    }

    #[test]
    fn decoded_means_the_render_ran_the_decode_itself_not_the_prefetch() {
        let dir = tempfile::tempdir().unwrap();
        let cbz = make_cbz(dir.path(), 2, None);
        // The dwell gives the prefetch of page 1 ample time to finish before page 1 is rendered.
        let opts = ReadOpts {
            book: cbz.to_str().unwrap().into(),
            fit: (50, 50),
            cache_mb: None,
            dwell_ms: 400,
            pages: None,
        };
        let mut out = Vec::new();
        run_read(&opts, &mut out, &mut Vec::new()).unwrap();
        let text = String::from_utf8(out).unwrap();
        let rows: Vec<&str> = text.lines().skip(1).take(2).collect();
        assert!(
            rows[0].ends_with(",true"),
            "cold page 0 decodes inside its render: {text}"
        );
        assert!(
            rows[1].ends_with(",false"),
            "page 1 was prefetched during the dwell: {text}"
        );
        // 2 decodes in total (one per page), 1 cache hit (page 1).
        assert!(text.lines().last().unwrap().contains(",2,1,"), "{text}");
    }

    #[test]
    fn read_continues_after_a_failed_page_and_reports_it() {
        let dir = tempfile::tempdir().unwrap();
        let cbz = make_cbz(dir.path(), 3, Some(1)); // pages: ok, broken, ok, ok
        let opts = ReadOpts {
            book: cbz.to_str().unwrap().into(),
            fit: (50, 50),
            cache_mb: None,
            dwell_ms: 0,
            pages: None,
        };
        let (mut out, mut err) = (Vec::new(), Vec::new());
        let failed = run_read(&opts, &mut out, &mut err).unwrap();
        assert_eq!(failed, 1);
        let text = String::from_utf8(out).unwrap();
        let rows: Vec<&str> = text.lines().skip(1).take(4).collect();
        assert!(
            rows[1].starts_with("1,") && rows[1].ends_with(",ERR"),
            "{text}"
        );
        assert!(
            rows[2].starts_with("2,") && !rows[2].ends_with(",ERR"),
            "{text}"
        );
        assert!(
            rows[3].starts_with("3,") && !rows[3].ends_with(",ERR"),
            "{text}"
        );
        let err = String::from_utf8(err).unwrap();
        assert!(err.contains("ERR: page 1"), "{err}");
    }

    #[test]
    fn read_of_a_missing_book_is_an_error() {
        let opts = ReadOpts {
            book: "no/such.cbz".into(),
            fit: (50, 50),
            cache_mb: None,
            dwell_ms: 0,
            pages: None,
        };
        assert!(run_read(&opts, &mut Vec::new(), &mut Vec::new()).is_err());
    }
}
