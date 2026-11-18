//! Deterministic synthetic comic corpus for ProViewer tests and benchmarks.
//!
//! No RNG is used anywhere: every pixel is a pure function of its coordinates and the
//! arguments, so generated archives are byte-for-byte reproducible.

use anyhow::{Context, Result};
use image::codecs::jpeg::JpegEncoder;
use image::codecs::png::PngEncoder;
use image::codecs::webp::WebPEncoder;
use image::{ExtendedColorType, GrayImage, ImageEncoder, Luma};
use std::io::Write;
use std::path::{Path, PathBuf};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipWriter};

const JPEG_QUALITY: u8 = 85;

/// Rotated halftone dot screen. Dot size follows a smooth tone gradient so the page contains
/// the full range from tiny dots to near-solid areas. Downscaling it produces moiré.
pub fn halftone_page(width: u32, height: u32, dot_period_px: f32, angle_deg: f32) -> GrayImage {
    let (sin, cos) = angle_deg.to_radians().sin_cos();
    let period = dot_period_px.max(0.5);
    let (w, h) = (width as f32, height as f32);
    GrayImage::from_fn(width, height, |x, y| {
        let (fx, fy) = (x as f32 + 0.5, y as f32 + 0.5);
        // Tone 0.05..0.85 sweeping diagonally across the page.
        let tone = 0.05 + 0.8 * ((fx / w + fy / h) * 0.5);
        let u = (fx * cos + fy * sin) / period;
        let v = (-fx * sin + fy * cos) / period;
        let (du, dv) = (u - u.round(), v - v.round());
        let dist2 = du * du + dv * dv;
        // Dot radius (in cell units) so that dot area ~ tone.
        let r2 = tone / std::f32::consts::PI;
        Luma([if dist2 < r2 { 0 } else { 255 }])
    })
}

/// Tall strip with panel-like blocks, borders, fine diagonal line art and tone ramps.
pub fn webtoon_strip(width: u32, height: u32) -> GrayImage {
    let panel_h = (width * 3 / 2).max(8);
    let gutter = (width / 16).max(2);
    GrayImage::from_fn(width, height, |x, y| {
        let cell = y / (panel_h + gutter);
        let ly = y % (panel_h + gutter);
        if ly >= panel_h {
            return Luma([255]); // gutter
        }
        let margin = (width / 32).max(1);
        let on_border =
            x < margin || x >= width.saturating_sub(margin) || ly < margin || ly >= panel_h.saturating_sub(margin);
        if on_border {
            return Luma([0]);
        }
        // Fine 1 px diagonal hatching whose spacing varies per panel.
        let spacing = 3 + (cell % 5);
        let hatch = (x + ly) % spacing == 0;
        // Smooth tone ramp across the panel.
        let ramp = 120 + (135 * ly / panel_h) as u8 / 2;
        Luma([if hatch { 20 } else { ramp }])
    })
}

/// Cheap deterministic page for the bulk archives: border, hatch bands and a page-number bar.
fn simple_page(width: u32, height: u32, index: u32) -> GrayImage {
    GrayImage::from_fn(width, height, |x, y| {
        let m = (width / 40).max(1);
        if x < m || y < m || x >= width.saturating_sub(m) || y >= height.saturating_sub(m) {
            return Luma([0]);
        }
        let band = y * 12 / height.max(1);
        let hatch = (x + y + index) % (4 + band % 4) == 0;
        let bar = x < (width * ((index % 10) + 1) / 10)
            && y + 3 * m + 1 > height
            && y + 2 * m < height;
        Luma([if hatch || bar { 30 } else { 235 - (band * 8) as u8 }])
    })
}

fn encode_jpeg(img: &GrayImage) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    JpegEncoder::new_with_quality(&mut out, JPEG_QUALITY)
        .write_image(img.as_raw(), img.width(), img.height(), ExtendedColorType::L8)
        .context("jpeg encode")?;
    Ok(out)
}

fn encode_png(img: &GrayImage) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    PngEncoder::new(&mut out)
        .write_image(img.as_raw(), img.width(), img.height(), ExtendedColorType::L8)
        .context("png encode")?;
    Ok(out)
}

fn encode_webp_lossless(img: &GrayImage) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    WebPEncoder::new_lossless(&mut out)
        .write_image(img.as_raw(), img.width(), img.height(), ExtendedColorType::L8)
        .context("webp encode")?;
    Ok(out)
}

fn is_image_name(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    [".jpg", ".jpeg", ".png", ".webp"].iter().any(|e| lower.ends_with(e))
}

/// Write a CBZ with entries in the given order. Image entries are stored (already compressed),
/// everything else is deflated. Non-ASCII names get the UTF-8 flag (the zip writer sets it).
pub fn write_cbz(path: &Path, entries: &[(String, Vec<u8>)]) -> Result<()> {
    let file = std::fs::File::create(path).with_context(|| format!("create {}", path.display()))?;
    let mut zip = ZipWriter::new(std::io::BufWriter::new(file));
    for (name, data) in entries {
        let method =
            if is_image_name(name) { CompressionMethod::Stored } else { CompressionMethod::Deflated };
        zip.start_file(name.as_str(), SimpleFileOptions::default().compression_method(method))?;
        zip.write_all(data)?;
    }
    zip.finish()?.flush()?;
    Ok(())
}

fn sc(v: u32, scale: f32) -> u32 {
    ((v as f32 * scale).round() as u32).max(1)
}

fn numbered(i: usize, ext: &str) -> String {
    format!("{:03}.{}", i + 1, ext)
}

/// Generate the seven corpus archives with all pixel dimensions multiplied by `scale`
/// (minimum 1 px). Entry counts and names do not depend on `scale`.
pub fn generate_with_scale(out_dir: &Path, scale: f32) -> Result<Vec<PathBuf>> {
    std::fs::create_dir_all(out_dir)?;
    let mut written = Vec::new();
    let mut emit = |name: &str, entries: Vec<(String, Vec<u8>)>| -> Result<()> {
        let p = out_dir.join(name);
        write_cbz(&p, &entries)?;
        written.push(p);
        Ok(())
    };

    // screentone.cbz: 6 pages, periods 2.0..=6.0
    let mut e = Vec::new();
    for i in 0..6usize {
        let period = 2.0 + 0.8 * i as f32;
        let page = halftone_page(sc(1600, scale), sc(2400, scale), period, 15.0 * i as f32 + 45.0);
        e.push((numbered(i, "jpg"), encode_jpeg(&page)?));
    }
    emit("screentone.cbz", e)?;

    // webtoon-long.cbz: 3 strips
    let mut e = Vec::new();
    for i in 0..3usize {
        e.push((numbered(i, "jpg"), encode_jpeg(&webtoon_strip(sc(800, scale), sc(24000, scale)))?));
    }
    emit("webtoon-long.cbz", e)?;

    // huge-single.cbz
    let huge = halftone_page(sc(12000, scale), sc(8000, scale), 4.0, 45.0);
    emit("huge-single.cbz", vec![(numbered(0, "jpg"), encode_jpeg(&huge)?)])?;

    // double-spread.cbz: portrait, spread, portrait, spread (PNG)
    let portrait = |i: u32| simple_page(sc(1600, scale), sc(2400, scale), i);
    let spread = |i: u32| simple_page(sc(3200, scale), sc(2400, scale), i);
    let e = vec![
        (numbered(0, "jpg"), encode_jpeg(&portrait(0))?),
        (numbered(1, "jpg"), encode_jpeg(&spread(1))?),
        (numbered(2, "jpg"), encode_jpeg(&portrait(2))?),
        (numbered(3, "png"), encode_png(&spread(3))?),
    ];
    emit("double-spread.cbz", e)?;

    // mixed-small.cbz and many-pages.cbz
    for (file, n) in [("mixed-small.cbz", 20usize), ("many-pages.cbz", 500usize)] {
        let mut e = Vec::with_capacity(n);
        for i in 0..n {
            let page = simple_page(sc(800, scale), sc(1200, scale), i as u32);
            e.push((numbered(i, "jpg"), encode_jpeg(&page)?));
        }
        emit(file, e)?;
    }

    // formats-and-names.cbz
    let small = |i: u32| simple_page(sc(400, scale), sc(600, scale), i);
    let comic_info = b"<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ComicInfo><Title>ProViewer synthetic corpus</Title><PageCount>4</PageCount></ComicInfo>\n".to_vec();
    let e = vec![
        ("ComicInfo.xml".to_string(), comic_info),
        ("vol1/page10.webp".to_string(), encode_webp_lossless(&small(10))?),
        ("vol1/page2.png".to_string(), encode_png(&small(2))?),
        ("vol1/page1.jpg".to_string(), encode_jpeg(&small(1))?),
        ("第3話.jpg".to_string(), encode_jpeg(&small(3))?),
    ];
    emit("formats-and-names.cbz", e)?;

    Ok(written)
}

/// Full-size corpus (`scale` 1.0).
pub fn generate_all(out_dir: &Path) -> Result<Vec<PathBuf>> {
    generate_with_scale(out_dir, 1.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Cursor, Read};
    use zip::{CompressionMethod, ZipArchive};

    fn open(path: &std::path::Path) -> ZipArchive<std::fs::File> {
        ZipArchive::new(std::fs::File::open(path).unwrap()).unwrap()
    }

    fn names(path: &std::path::Path) -> Vec<String> {
        let mut z = open(path);
        (0..z.len()).map(|i| z.by_index(i).unwrap().name().unwrap().to_string()).collect()
    }

    #[test]
    fn halftone_page_has_requested_dimensions() {
        assert_eq!(halftone_page(320, 480, 3.0, 45.0).dimensions(), (320, 480));
    }

    #[test]
    fn halftone_period_changes_pixels() {
        assert_ne!(halftone_page(128, 128, 3.0, 45.0), halftone_page(128, 128, 4.0, 45.0));
    }

    #[test]
    fn halftone_is_deterministic() {
        assert_eq!(halftone_page(128, 128, 3.0, 45.0), halftone_page(128, 128, 3.0, 45.0));
    }

    #[test]
    fn webtoon_strip_has_requested_height() {
        assert_eq!(webtoon_strip(100, 5000).height(), 5000);
    }

    #[test]
    fn cbz_roundtrip_preserves_order_and_count() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("t.cbz");
        let entries = vec![
            ("b.xml".to_string(), b"<a/>".to_vec()),
            ("第3話.jpg".to_string(), vec![1, 2, 3]),
            ("a.png".to_string(), vec![4, 5]),
        ];
        write_cbz(&p, &entries).unwrap();
        let got = names(&p);
        let want: Vec<String> = entries.iter().map(|(n, _)| n.clone()).collect();
        assert_eq!(got.len(), 3);
        for (g, w) in got.iter().zip(&want) {
            assert_eq!(g.as_bytes(), w.as_bytes());
        }
        let mut z = open(&p);
        let mut buf = Vec::new();
        z.by_name("第3話.jpg").unwrap().read_to_end(&mut buf).unwrap();
        assert_eq!(buf, vec![1, 2, 3]);
    }

    #[test]
    fn cbz_stores_images_and_deflates_others() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("t.cbz");
        let xml = "<ComicInfo>".repeat(200).into_bytes();
        let entries = vec![
            ("a.jpg".to_string(), vec![0u8; 64]),
            ("b.png".to_string(), vec![0u8; 64]),
            ("c.webp".to_string(), vec![0u8; 64]),
            ("ComicInfo.xml".to_string(), xml),
        ];
        write_cbz(&p, &entries).unwrap();
        let mut z = open(&p);
        for i in 0..3 {
            assert_eq!(z.by_index(i).unwrap().compression(), CompressionMethod::Stored);
        }
        assert_eq!(z.by_index(3).unwrap().compression(), CompressionMethod::Deflated);
    }

    #[test]
    fn generate_writes_expected_archives() {
        let dir = tempfile::tempdir().unwrap();
        let paths = generate_with_scale(dir.path(), 0.05).unwrap();
        let mut file_names: Vec<String> =
            paths.iter().map(|p| p.file_name().unwrap().to_string_lossy().to_string()).collect();
        file_names.sort();
        let mut want = vec![
            "screentone.cbz",
            "webtoon-long.cbz",
            "huge-single.cbz",
            "double-spread.cbz",
            "mixed-small.cbz",
            "many-pages.cbz",
            "formats-and-names.cbz",
        ];
        want.sort();
        assert_eq!(file_names, want);

        let counts = [
            ("screentone.cbz", 6),
            ("webtoon-long.cbz", 3),
            ("huge-single.cbz", 1),
            ("double-spread.cbz", 4),
            ("mixed-small.cbz", 20),
            ("many-pages.cbz", 500),
            ("formats-and-names.cbz", 5),
        ];
        for (f, n) in counts {
            assert_eq!(names(&dir.path().join(f)).len(), n, "{f}");
        }

        let fan = dir.path().join("formats-and-names.cbz");
        assert_eq!(
            names(&fan),
            vec!["ComicInfo.xml", "vol1/page10.webp", "vol1/page2.png", "vol1/page1.jpg", "第3話.jpg"]
        );
        let mut z = open(&fan);
        let mut buf = Vec::new();
        z.by_name("vol1/page10.webp").unwrap().read_to_end(&mut buf).unwrap();
        let img = image::load_from_memory_with_format(&buf, image::ImageFormat::WebP).unwrap();
        assert!(img.width() >= 1 && img.height() >= 1);
    }

    #[test]
    #[ignore]
    fn huge_single_full_scale_dimensions() {
        let dir = tempfile::tempdir().unwrap();
        generate_all(dir.path()).unwrap();
        let mut z = open(&dir.path().join("huge-single.cbz"));
        let mut buf = Vec::new();
        z.by_index(0).unwrap().read_to_end(&mut buf).unwrap();
        let dims = image::ImageReader::new(Cursor::new(buf))
            .with_guessed_format()
            .unwrap()
            .into_dimensions()
            .unwrap();
        assert_eq!(dims, (12000, 8000));
    }
}
