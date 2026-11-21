//! Damaged-JPEG behaviour (Phase 2, owner decision D3). A JPEG whose header reads but whose scan
//! is damaged decodes the rows it still has instead of failing; a header that cannot be read is
//! still `Corrupt`.

use proviewer_core::decode::decode;
use proviewer_core::error::EngineError;
use proviewer_core::limits::Limits;

/// Smooth 256×256 RGB gradient, so the JPEG encode/decode error stays tiny.
fn gradient() -> Vec<u8> {
    let (w, h) = (256u32, 256u32);
    let mut v = Vec::with_capacity((w * h * 3) as usize);
    for y in 0..h {
        for x in 0..w {
            v.push((x * 255 / (w - 1)) as u8);
            v.push((y * 255 / (h - 1)) as u8);
            v.push(((x + y) * 255 / (w + h - 2)) as u8);
        }
    }
    v
}

/// 256×256 baseline quality-90 RGB JPEG.
fn clean_jpeg() -> Vec<u8> {
    let mut out = Vec::new();
    let enc = jpeg_encoder::Encoder::new(&mut out, 90);
    enc.encode(&gradient(), 256, 256, jpeg_encoder::ColorType::Rgb)
        .unwrap();
    out
}

fn mean_abs_diff(a: &[u8], b: &[u8]) -> f64 {
    assert_eq!(a.len(), b.len());
    let sum: u64 = a
        .iter()
        .zip(b)
        .map(|(x, y)| u64::from(x.abs_diff(*y)))
        .sum();
    sum as f64 / a.len() as f64
}

#[test]
fn truncated_mid_scan_decodes_partially() {
    let clean = clean_jpeg();
    let clean_img = decode(&clean, 1, &Limits::default()).unwrap();
    let damaged = gen_corpus::truncate_mid_scan(&clean, 0.6);
    let img = decode(&damaged, 1, &Limits::default()).unwrap();
    assert_eq!((img.width, img.height), (256, 256));
    // The scan keeps 60 % of its entropy data, so the first 64 rows must match the clean decode;
    // rows after the cut may be padding.
    let row = 256 * 3;
    let mae = mean_abs_diff(&img.pixels[..64 * row], &clean_img.pixels[..64 * row]);
    assert!(mae <= 2.0, "rows 0..64 differ from the clean decode (mae {mae})");
}

#[test]
fn extraneous_bytes_decode_fully() {
    let clean = clean_jpeg();
    let clean_img = decode(&clean, 1, &Limits::default()).unwrap();
    let damaged = gen_corpus::insert_extraneous_before_sos(&clean);
    let img = decode(&damaged, 1, &Limits::default()).unwrap();
    assert_eq!((img.width, img.height), (256, 256));
    let mae = mean_abs_diff(&img.pixels, &clean_img.pixels);
    assert!(mae <= 1.0, "whole image differs from the clean decode (mae {mae})");
}

#[test]
fn truncated_header_is_corrupt() {
    let clean = clean_jpeg();
    assert!(matches!(
        decode(&clean[..20], 1, &Limits::default()),
        Err(EngineError::Corrupt { .. })
    ));
}

#[test]
fn fatal_garbage_is_corrupt() {
    // JPEG magic, then a bogus marker and 64 garbage bytes: the header cannot be read.
    let mut garbage = vec![0xFF, 0xD8, 0xFF];
    garbage.extend_from_slice(&[0xAB; 64]);
    assert!(matches!(
        decode(&garbage, 1, &Limits::default()),
        Err(EngineError::Corrupt { .. })
    ));
}
