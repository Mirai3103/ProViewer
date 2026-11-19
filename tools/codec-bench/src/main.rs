//! JPEG codec comparison for ProViewer Phase 1 (spec §5 "Decoding note", plan Task 3).
//!
//! Inputs are generated in-process and deterministically (gen-corpus halftone + jpeg-encoder), so the
//! same bytes are decoded on the host, on the device, and by the platform benchmark (`--export`).
//!
//! Modes:
//!   codec-bench --export <dir>   write the inputs as .jpg files
//!   codec-bench [--iters N]      print CSV `codec,input,denom,supported,median_ms,p90_ms,out_w,out_h`
//!
//! Before timing, each codec's full-size output must match zune-jpeg's within a mean absolute
//! difference of 2; otherwise the run exits 1.

use std::hint::black_box;
use std::io::Write;
use std::path::PathBuf;
use std::process::ExitCode;
use std::time::Instant;

const QUALITY: u8 = 85;
const DENOMS: [u32; 3] = [1, 2, 4];
const WARMUP: usize = 3;
const DEFAULT_ITERS: usize = 15;
const DEFAULT_ITERS_HUGE: usize = 5;
const MAX_MEAN_ABS_DIFF: f64 = 2.0;
const CSV_HEADER: &str = "codec,input,denom,supported,median_ms,p90_ms,out_w,out_h";

// ---------------------------------------------------------------------------------------------
// Pure helpers (unit tested)
// ---------------------------------------------------------------------------------------------

/// Median and nearest-rank 90th percentile of `samples` (any order). Empty input gives (NaN, NaN).
fn median_p90(samples: &[f64]) -> (f64, f64) {
    if samples.is_empty() {
        return (f64::NAN, f64::NAN);
    }
    let mut v = samples.to_vec();
    v.sort_by(f64::total_cmp);
    let n = v.len();
    let median = if n % 2 == 1 {
        v[n / 2]
    } else {
        (v[n / 2 - 1] + v[n / 2]) / 2.0
    };
    // Nearest-rank percentile: the ceil(0.9 * n)-th smallest value (integer math, no float rounding).
    let rank = (9 * n).div_ceil(10).max(1);
    (median, v[rank - 1])
}

/// Mean absolute difference between two equally long byte buffers; `None` if the lengths differ.
fn mean_abs_diff(a: &[u8], b: &[u8]) -> Option<f64> {
    if a.len() != b.len() {
        return None;
    }
    if a.is_empty() {
        return Some(0.0);
    }
    let sum: u64 = a
        .iter()
        .zip(b)
        .map(|(&x, &y)| u64::from(x.abs_diff(y)))
        .sum();
    Some(sum as f64 / a.len() as f64)
}

/// Output length of a 1/`denom` scaled decode (libjpeg rounds up).
fn scaled_len(len: u32, denom: u32) -> u32 {
    len.div_ceil(denom)
}

#[derive(Debug, Clone, PartialEq)]
struct Row {
    codec: &'static str,
    input: &'static str,
    denom: u32,
    supported: bool,
    median_ms: f64,
    p90_ms: f64,
    out_w: u32,
    out_h: u32,
}

fn csv_row(r: &Row) -> String {
    format!(
        "{},{},{},{},{:.3},{:.3},{},{}",
        r.codec, r.input, r.denom, r.supported, r.median_ms, r.p90_ms, r.out_w, r.out_h
    )
}

#[derive(Debug, PartialEq)]
enum Mode {
    Export(PathBuf),
    Bench { iters: Option<usize> },
}

fn parse_args(args: &[String]) -> Result<Mode, String> {
    match args {
        [] => Ok(Mode::Bench { iters: None }),
        [flag, n] if flag == "--iters" => match n.parse::<usize>() {
            Ok(n) if n > 0 => Ok(Mode::Bench { iters: Some(n) }),
            _ => Err(format!("--iters needs a positive integer, got '{n}'")),
        },
        [flag, dir] if flag == "--export" => Ok(Mode::Export(PathBuf::from(dir))),
        _ => Err(format!("unrecognised arguments: {args:?}")),
    }
}

/// Deterministic colour comic page (RGB8): coloured gradient background, a rotated halftone dot
/// screen, fine diagonal hatching and black panel borders. Every pixel is a pure function of (x, y).
fn color_page(width: u32, height: u32) -> Vec<u8> {
    let (w, h) = (width.max(1) as f32, height.max(1) as f32);
    let (sin, cos) = 30f32.to_radians().sin_cos();
    let period = 4.0f32;
    let panel_h = (height / 3).max(1);
    let border = (width / 100).max(1);
    let mut out = Vec::with_capacity(width as usize * height as usize * 3);
    for y in 0..height {
        for x in 0..width {
            let (fx, fy) = (x as f32 + 0.5, y as f32 + 0.5);
            let ly = y % panel_h;
            let on_border =
                x < border || x + border >= width || ly < border || ly + border >= panel_h;
            if on_border {
                out.extend_from_slice(&[0, 0, 0]);
                continue;
            }
            // Smooth background gradient in all three channels.
            let mut r = 80.0 + 175.0 * (fx / w);
            let mut g = 80.0 + 175.0 * (fy / h);
            let mut b = 255.0 - 150.0 * ((fx + fy) / (w + h));
            // Magenta halftone dots whose size sweeps across the page.
            let tone = 0.05 + 0.6 * (fy / h);
            let u = (fx * cos + fy * sin) / period;
            let v = (-fx * sin + fy * cos) / period;
            let (du, dv) = (u - u.round(), v - v.round());
            if du * du + dv * dv < tone / std::f32::consts::PI {
                g *= 0.25;
                r *= 0.9;
                b *= 0.8;
            }
            // Fine dark-blue diagonal hatching in the middle panel.
            if y / panel_h == 1 && (x + y) % 6 == 0 {
                r = 20.0;
                g = 30.0;
                b = 90.0;
            }
            out.extend_from_slice(&[r as u8, g as u8, b as u8]);
        }
    }
    out
}

// ---------------------------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq)]
enum Kind {
    Gray,
    Color,
    ColorProgressive,
}

#[derive(Clone, Copy, Debug)]
struct InputSpec {
    name: &'static str,
    width: u32,
    height: u32,
    kind: Kind,
}

const INPUTS: [InputSpec; 4] = [
    InputSpec {
        name: "gray-1600x2400.jpg",
        width: 1600,
        height: 2400,
        kind: Kind::Gray,
    },
    InputSpec {
        name: "color-1600x2400.jpg",
        width: 1600,
        height: 2400,
        kind: Kind::Color,
    },
    InputSpec {
        name: "progressive-1600x2400.jpg",
        width: 1600,
        height: 2400,
        kind: Kind::ColorProgressive,
    },
    InputSpec {
        name: "gray-12000x8000.jpg",
        width: 12000,
        height: 8000,
        kind: Kind::Gray,
    },
];

impl InputSpec {
    fn is_huge(&self) -> bool {
        u64::from(self.width) * u64::from(self.height) > 50_000_000
    }
}

fn encode_input(kind: Kind, width: u32, height: u32) -> Result<Vec<u8>, String> {
    let (w16, h16) = (
        u16::try_from(width).map_err(|_| "width > 65535".to_string())?,
        u16::try_from(height).map_err(|_| "height > 65535".to_string())?,
    );
    let mut out = Vec::new();
    let mut enc = jpeg_encoder::Encoder::new(&mut out, QUALITY);
    let res = match kind {
        Kind::Gray => {
            let page = gen_corpus::halftone_page(width, height, 3.0, 45.0);
            enc.encode(page.as_raw(), w16, h16, jpeg_encoder::ColorType::Luma)
        }
        Kind::Color | Kind::ColorProgressive => {
            enc.set_sampling_factor(jpeg_encoder::SamplingFactor::R_4_2_0);
            enc.set_progressive(kind == Kind::ColorProgressive);
            enc.encode(
                &color_page(width, height),
                w16,
                h16,
                jpeg_encoder::ColorType::Rgb,
            )
        }
    };
    res.map_err(|e| format!("jpeg encode: {e}"))?;
    Ok(out)
}

// ---------------------------------------------------------------------------------------------
// Codecs
// ---------------------------------------------------------------------------------------------

struct Decoded {
    width: u32,
    height: u32,
    channels: u8,
    pixels: Vec<u8>,
}

trait Codec {
    fn name(&self) -> &'static str;
    /// True if the codec decodes directly at 1/`denom` size (DCT scaling).
    fn supports(&self, denom: u32) -> bool;
    fn decode(&self, data: &[u8], denom: u32) -> Result<Decoded, String>;
}

struct Zune;

impl Codec for Zune {
    fn name(&self) -> &'static str {
        "zune-jpeg"
    }
    fn supports(&self, denom: u32) -> bool {
        denom == 1
    }
    fn decode(&self, data: &[u8], denom: u32) -> Result<Decoded, String> {
        use zune_core::colorspace::ColorSpace;
        if denom != 1 {
            return Err(format!("zune-jpeg has no 1/{denom} scaled decode"));
        }
        let opts = zune_core::options::DecoderOptions::default();
        let mut dec = zune_jpeg::JpegDecoder::new_with_options(
            zune_core::bytestream::ZCursor::new(data),
            opts,
        );
        dec.decode_headers()
            .map_err(|e| format!("zune headers: {e:?}"))?;
        let out_cs = match dec.input_colorspace() {
            Some(ColorSpace::Luma) => ColorSpace::Luma,
            _ => ColorSpace::RGB,
        };
        dec.set_options(opts.jpeg_set_out_colorspace(out_cs));
        let pixels = dec.decode().map_err(|e| format!("zune decode: {e:?}"))?;
        let info = dec.info().ok_or("zune: no info")?;
        Ok(Decoded {
            width: u32::from(info.width),
            height: u32::from(info.height),
            channels: out_cs.num_components() as u8,
            pixels,
        })
    }
}

struct JpegDecoderCrate;

impl Codec for JpegDecoderCrate {
    fn name(&self) -> &'static str {
        "jpeg-decoder"
    }
    fn supports(&self, denom: u32) -> bool {
        matches!(denom, 1 | 2 | 4 | 8)
    }
    fn decode(&self, data: &[u8], denom: u32) -> Result<Decoded, String> {
        let mut dec = jpeg_decoder::Decoder::new(data);
        if denom != 1 {
            dec.read_info()
                .map_err(|e| format!("jpeg-decoder info: {e}"))?;
            let full = dec.info().ok_or("jpeg-decoder: no info")?;
            let rw = scaled_len(u32::from(full.width), denom) as u16;
            let rh = scaled_len(u32::from(full.height), denom) as u16;
            dec.scale(rw, rh)
                .map_err(|e| format!("jpeg-decoder scale: {e}"))?;
        }
        let pixels = dec
            .decode()
            .map_err(|e| format!("jpeg-decoder decode: {e}"))?;
        let info = dec.info().ok_or("jpeg-decoder: no info")?;
        let channels = match info.pixel_format {
            jpeg_decoder::PixelFormat::L8 => 1,
            jpeg_decoder::PixelFormat::RGB24 => 3,
            other => return Err(format!("jpeg-decoder: unexpected pixel format {other:?}")),
        };
        Ok(Decoded {
            width: u32::from(info.width),
            height: u32::from(info.height),
            channels,
            pixels,
        })
    }
}

#[cfg(feature = "turbo")]
struct Turbo;

#[cfg(feature = "turbo")]
impl Codec for Turbo {
    fn name(&self) -> &'static str {
        "turbojpeg"
    }
    fn supports(&self, denom: u32) -> bool {
        matches!(denom, 1 | 2 | 4 | 8)
    }
    fn decode(&self, data: &[u8], denom: u32) -> Result<Decoded, String> {
        let mut dec = turbojpeg::Decompressor::new().map_err(|e| format!("turbojpeg init: {e}"))?;
        let header = dec
            .read_header(data)
            .map_err(|e| format!("turbojpeg header: {e}"))?;
        let factor = turbojpeg::ScalingFactor::new(1, denom as usize);
        dec.set_scaling_factor(factor)
            .map_err(|e| format!("turbojpeg scale: {e}"))?;
        let scaled = header.scaled(factor);
        let (format, channels) = match header.colorspace {
            turbojpeg::Colorspace::Gray => (turbojpeg::PixelFormat::GRAY, 1u8),
            _ => (turbojpeg::PixelFormat::RGB, 3u8),
        };
        let pitch = scaled.width * usize::from(channels);
        let mut pixels = vec![0u8; pitch * scaled.height];
        let image = turbojpeg::Image {
            pixels: pixels.as_mut_slice(),
            width: scaled.width,
            pitch,
            height: scaled.height,
            format,
        };
        dec.decompress(data, image)
            .map_err(|e| format!("turbojpeg decode: {e}"))?;
        Ok(Decoded {
            width: scaled.width as u32,
            height: scaled.height as u32,
            channels,
            pixels,
        })
    }
}

fn codecs() -> Vec<Box<dyn Codec>> {
    vec![
        Box::new(Zune),
        Box::new(JpegDecoderCrate),
        #[cfg(feature = "turbo")]
        Box::new(Turbo),
    ]
}

// ---------------------------------------------------------------------------------------------
// Measurement
// ---------------------------------------------------------------------------------------------

/// Warm up, then time `iters` decodes; returns per-iteration milliseconds and the last output.
fn measure(
    codec: &dyn Codec,
    data: &[u8],
    denom: u32,
    iters: usize,
) -> Result<(Vec<f64>, Decoded), String> {
    for _ in 0..WARMUP {
        black_box(codec.decode(black_box(data), denom)?);
    }
    let mut times = Vec::with_capacity(iters);
    let mut last = None;
    for _ in 0..iters {
        let t = Instant::now();
        let d = codec.decode(black_box(data), denom)?;
        times.push(t.elapsed().as_secs_f64() * 1000.0);
        last = Some(black_box(d));
    }
    Ok((times, last.ok_or("zero iterations")?))
}

/// Full-size output of every codec must match zune-jpeg's (the reference) within MAX_MEAN_ABS_DIFF.
fn cross_check(spec: &InputSpec, data: &[u8], codecs: &[Box<dyn Codec>]) -> Result<(), String> {
    let reference = Zune.decode(data, 1)?;
    if (reference.width, reference.height) != (spec.width, spec.height) {
        return Err(format!(
            "{}: zune-jpeg decoded {}x{}, expected {}x{}",
            spec.name, reference.width, reference.height, spec.width, spec.height
        ));
    }
    for c in codecs {
        let d = c.decode(data, 1)?;
        if (d.width, d.height, d.channels)
            != (reference.width, reference.height, reference.channels)
        {
            return Err(format!(
                "{} on {}: {}x{}x{} vs zune-jpeg {}x{}x{}",
                c.name(),
                spec.name,
                d.width,
                d.height,
                d.channels,
                reference.width,
                reference.height,
                reference.channels
            ));
        }
        let mad = mean_abs_diff(&d.pixels, &reference.pixels)
            .ok_or_else(|| format!("{} on {}: buffer length mismatch", c.name(), spec.name))?;
        eprintln!(
            "cross-check {} on {}: mean abs diff vs zune-jpeg = {mad:.4}",
            c.name(),
            spec.name
        );
        if mad > MAX_MEAN_ABS_DIFF {
            return Err(format!(
                "{} on {}: mean abs diff {mad:.4} > {MAX_MEAN_ABS_DIFF} vs zune-jpeg",
                c.name(),
                spec.name
            ));
        }
    }
    Ok(())
}

fn bench(iters_override: Option<usize>) -> Result<(), String> {
    let codecs = codecs();
    let mut out = std::io::stdout().lock();
    writeln!(out, "{CSV_HEADER}").map_err(|e| e.to_string())?;
    for spec in &INPUTS {
        eprintln!("generating {}", spec.name);
        let data = encode_input(spec.kind, spec.width, spec.height)?;
        cross_check(spec, &data, &codecs)?;
        let iters = iters_override.unwrap_or(if spec.is_huge() {
            DEFAULT_ITERS_HUGE
        } else {
            DEFAULT_ITERS
        });
        for c in &codecs {
            for row in rows_for(c.as_ref(), spec, &data, iters)? {
                writeln!(out, "{}", csv_row(&row)).map_err(|e| e.to_string())?;
                out.flush().map_err(|e| e.to_string())?;
            }
        }
    }
    Ok(())
}

/// One CSV row per denominator for `codec` on `spec`. Unsupported denominators report the full
/// decode time (the engine would decode fully and resize) and the full output size.
fn rows_for(
    c: &dyn Codec,
    spec: &InputSpec,
    data: &[u8],
    iters: usize,
) -> Result<Vec<Row>, String> {
    let (full_times, full) = measure(c, data, 1, iters)?;
    let mut rows = Vec::with_capacity(DENOMS.len());
    for denom in DENOMS {
        let row = if c.supports(denom) {
            let (times, d) = if denom == 1 {
                (full_times.clone(), None)
            } else {
                let (t, d) = measure(c, data, denom, iters)?;
                (t, Some(d))
            };
            let (w, h) = d
                .as_ref()
                .map_or((full.width, full.height), |d| (d.width, d.height));
            let want = (
                scaled_len(spec.width, denom),
                scaled_len(spec.height, denom),
            );
            if (w, h) != want {
                return Err(format!(
                    "{} on {} at 1/{denom}: got {w}x{h}, expected {}x{}",
                    c.name(),
                    spec.name,
                    want.0,
                    want.1
                ));
            }
            let (median_ms, p90_ms) = median_p90(&times);
            Row {
                codec: c.name(),
                input: spec.name,
                denom,
                supported: true,
                median_ms,
                p90_ms,
                out_w: w,
                out_h: h,
            }
        } else {
            let (median_ms, p90_ms) = median_p90(&full_times);
            Row {
                codec: c.name(),
                input: spec.name,
                denom,
                supported: false,
                median_ms,
                p90_ms,
                out_w: full.width,
                out_h: full.height,
            }
        };
        rows.push(row);
    }
    Ok(rows)
}

fn export(dir: &std::path::Path) -> Result<(), String> {
    std::fs::create_dir_all(dir).map_err(|e| format!("create {}: {e}", dir.display()))?;
    for spec in &INPUTS {
        let data = encode_input(spec.kind, spec.width, spec.height)?;
        let p = dir.join(spec.name);
        std::fs::write(&p, &data).map_err(|e| format!("write {}: {e}", p.display()))?;
        println!("{}", p.display());
    }
    Ok(())
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match parse_args(&args) {
        Ok(Mode::Export(dir)) => export(&dir),
        Ok(Mode::Bench { iters }) => bench(iters),
        Err(e) => Err(format!(
            "{e}\nusage: codec-bench --export <dir> | codec-bench [--iters N]"
        )),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("codec-bench: {e}");
            ExitCode::from(1)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(v: &[&str]) -> Vec<String> {
        v.iter().map(|x| x.to_string()).collect()
    }

    #[test]
    fn median_p90_odd_even_and_unsorted() {
        assert_eq!(median_p90(&[3.0, 1.0, 2.0]), (2.0, 3.0));
        assert_eq!(median_p90(&[4.0, 1.0, 3.0, 2.0]), (2.5, 4.0));
        let ten: Vec<f64> = (1..=10).rev().map(f64::from).collect();
        assert_eq!(median_p90(&ten), (5.5, 9.0)); // nearest rank: ceil(0.9*10) = 9th
        let fifteen: Vec<f64> = (1..=15).map(f64::from).collect();
        assert_eq!(median_p90(&fifteen), (8.0, 14.0)); // ceil(13.5) = 14th
        let (m, p) = median_p90(&[]);
        assert!(m.is_nan() && p.is_nan());
    }

    #[test]
    fn mean_abs_diff_basics() {
        assert_eq!(mean_abs_diff(&[0, 10, 255], &[0, 10, 255]), Some(0.0));
        assert_eq!(
            mean_abs_diff(&[0, 10, 255, 4], &[2, 8, 250, 4]),
            Some(9.0 / 4.0)
        );
        assert_eq!(mean_abs_diff(&[1, 2], &[1]), None);
        assert_eq!(mean_abs_diff(&[], &[]), Some(0.0));
    }

    #[test]
    fn scaled_len_rounds_up() {
        assert_eq!(scaled_len(1600, 1), 1600);
        assert_eq!(scaled_len(1600, 4), 400);
        assert_eq!(scaled_len(1601, 2), 801);
        assert_eq!(scaled_len(7, 4), 2);
    }

    #[test]
    fn csv_row_format() {
        let r = Row {
            codec: "zune-jpeg",
            input: "gray-1600x2400.jpg",
            denom: 2,
            supported: false,
            median_ms: 12.34567,
            p90_ms: 13.0,
            out_w: 1600,
            out_h: 2400,
        };
        assert_eq!(
            csv_row(&r),
            "zune-jpeg,gray-1600x2400.jpg,2,false,12.346,13.000,1600,2400"
        );
        assert_eq!(
            CSV_HEADER.split(',').count(),
            csv_row(&r).split(',').count()
        );
    }

    #[test]
    fn parse_args_modes() {
        assert_eq!(parse_args(&[]), Ok(Mode::Bench { iters: None }));
        assert_eq!(
            parse_args(&s(&["--iters", "3"])),
            Ok(Mode::Bench { iters: Some(3) })
        );
        assert_eq!(
            parse_args(&s(&["--export", "out"])),
            Ok(Mode::Export(PathBuf::from("out")))
        );
        assert!(parse_args(&s(&["--iters"])).is_err());
        assert!(parse_args(&s(&["--iters", "0"])).is_err());
        assert!(parse_args(&s(&["--iters", "x"])).is_err());
        assert!(parse_args(&s(&["--export"])).is_err());
        assert!(parse_args(&s(&["--bogus"])).is_err());
    }

    #[test]
    fn color_page_is_deterministic_and_colourful() {
        let a = color_page(64, 96);
        assert_eq!(a.len(), 64 * 96 * 3);
        assert_eq!(a, color_page(64, 96));
        // Not grey: some pixel has distinct channels.
        assert!(a.chunks(3).any(|p| p[0] != p[1] || p[1] != p[2]));
        // The three channels follow different gradients: red grows left to right, blue shrinks
        // left to right, green grows top to bottom.
        let (w, h) = (64usize, 96usize);
        let mean = |chan: usize, xs: std::ops::Range<usize>, ys: std::ops::Range<usize>| {
            let (mut sum, mut n) = (0u64, 0u64);
            for y in ys {
                for x in xs.clone() {
                    sum += u64::from(a[(y * w + x) * 3 + chan]);
                    n += 1;
                }
            }
            sum as f64 / n as f64
        };
        let (left, right) = (0..w / 4, w - w / 4..w);
        let (top, bottom) = (0..h / 4, h - h / 4..h);
        assert!(
            mean(0, left.clone(), 0..h) < mean(0, right.clone(), 0..h),
            "red"
        );
        assert!(mean(2, left, 0..h) > mean(2, right, 0..h), "blue");
        assert!(
            mean(1, 0..w, top.clone()) < mean(1, 0..w, bottom.clone()),
            "green"
        );
    }

    fn has_marker(data: &[u8], m: u8) -> bool {
        data.windows(2).any(|w| w[0] == 0xFF && w[1] == m)
    }

    #[test]
    fn inputs_encode_deterministically_with_expected_structure() {
        for kind in [Kind::Gray, Kind::Color, Kind::ColorProgressive] {
            let a = encode_input(kind, 160, 240).unwrap();
            assert_eq!(
                a,
                encode_input(kind, 160, 240).unwrap(),
                "{kind:?} not deterministic"
            );
            let d = Zune.decode(&a, 1).unwrap();
            assert_eq!((d.width, d.height), (160, 240));
            assert_eq!(d.channels, if kind == Kind::Gray { 1 } else { 3 });
            // SOF0 = baseline, SOF2 = progressive.
            assert_eq!(
                has_marker(&a, 0xC2),
                kind == Kind::ColorProgressive,
                "{kind:?}"
            );
        }
    }

    #[test]
    fn jpeg_decoder_matches_zune_and_scales() {
        for kind in [Kind::Gray, Kind::Color, Kind::ColorProgressive] {
            let data = encode_input(kind, 160, 240).unwrap();
            let spec = InputSpec {
                name: "t",
                width: 160,
                height: 240,
                kind,
            };
            cross_check(&spec, &data, &codecs()).unwrap();
            for denom in [2, 4] {
                let d = JpegDecoderCrate.decode(&data, denom).unwrap();
                assert_eq!(
                    (d.width, d.height),
                    (160 / denom, 240 / denom),
                    "{kind:?} 1/{denom}"
                );
            }
        }
    }

    /// A codec that decodes like zune-jpeg but shifts every sample, so the cross-check must reject it.
    struct Skewed(u8);

    impl Codec for Skewed {
        fn name(&self) -> &'static str {
            "skewed"
        }
        fn supports(&self, denom: u32) -> bool {
            denom == 1
        }
        fn decode(&self, data: &[u8], denom: u32) -> Result<Decoded, String> {
            let mut d = Zune.decode(data, denom)?;
            for p in &mut d.pixels {
                *p = p.saturating_add(self.0);
            }
            Ok(d)
        }
    }

    #[test]
    fn cross_check_rejects_a_codec_that_disagrees_with_zune() {
        let data = encode_input(Kind::Gray, 160, 240).unwrap();
        let spec = InputSpec {
            name: "t",
            width: 160,
            height: 240,
            kind: Kind::Gray,
        };
        let bad: Vec<Box<dyn Codec>> = vec![Box::new(Skewed(10))];
        let err = cross_check(&spec, &data, &bad).unwrap_err();
        assert!(err.contains("mean abs diff"), "{err}");
        // A shift within the tolerance (<= 2) passes.
        let ok: Vec<Box<dyn Codec>> = vec![Box::new(Skewed(1))];
        cross_check(&spec, &data, &ok).unwrap();
    }

    #[test]
    fn rows_report_unsupported_denoms_as_full_decode() {
        let data = encode_input(Kind::Color, 160, 240).unwrap();
        let spec = InputSpec {
            name: "t",
            width: 160,
            height: 240,
            kind: Kind::Color,
        };
        // zune-jpeg has no scaled decode: 1/2 and 1/4 are full decodes at full size.
        let rows = rows_for(&Zune, &spec, &data, 2).unwrap();
        assert_eq!(rows.len(), 3);
        assert_eq!(
            rows.iter()
                .map(|r| (r.denom, r.supported))
                .collect::<Vec<_>>(),
            [(1, true), (2, false), (4, false)]
        );
        for r in &rows {
            assert_eq!((r.out_w, r.out_h), (160, 240));
            assert_eq!(r.median_ms, rows[0].median_ms, "full-decode time reused");
        }
        // jpeg-decoder scales: dimensions follow the denominator.
        let rows = rows_for(&JpegDecoderCrate, &spec, &data, 2).unwrap();
        assert!(rows.iter().all(|r| r.supported));
        assert_eq!(
            rows.iter().map(|r| (r.out_w, r.out_h)).collect::<Vec<_>>(),
            [(160, 240), (80, 120), (40, 60)]
        );
    }

    #[test]
    fn input_specs_match_brief() {
        let names: Vec<_> = INPUTS.iter().map(|i| i.name).collect();
        assert_eq!(
            names,
            [
                "gray-1600x2400.jpg",
                "color-1600x2400.jpg",
                "progressive-1600x2400.jpg",
                "gray-12000x8000.jpg"
            ]
        );
        assert!(INPUTS[3].is_huge() && !INPUTS[0].is_huge());
    }
}
