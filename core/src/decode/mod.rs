//! Image decoding (JPEG, PNG, WebP) with limits applied before any pixel buffer is allocated.
//!
//! Page bytes are untrusted. `decode` sniffs the format from magic bytes (never from a file name), probes the
//! header, checks the [`Limits`], and only then allocates. Each format module re-checks the dimensions its own
//! decoder reports before it allocates, so a header that disagrees with the probe cannot slip past the limits.

use crate::error::{EngineError, Result};
use crate::limits::Limits;

mod jpeg;
mod png;
mod webp;

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum ImageFormat {
    Jpeg,
    Png,
    Webp,
}

#[derive(uniffi::Record, Clone, Copy, Debug, PartialEq, Eq)]
pub struct PageInfo {
    pub width: u32,
    pub height: u32,
    pub format: ImageFormat,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PixelLayout {
    Gray8,
    Rgb8,
}

impl PixelLayout {
    pub fn channels(self) -> u64 {
        match self {
            PixelLayout::Gray8 => 1,
            PixelLayout::Rgb8 => 3,
        }
    }
}

/// Decoded pixels, tightly packed (no row stride).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DecodedImage {
    pub width: u32,
    pub height: u32,
    pub layout: PixelLayout,
    pub pixels: Vec<u8>,
    /// The DCT scale actually used: `width == ceil(page_width / denom)`.
    pub denom: u8,
}

impl DecodedImage {
    pub fn byte_size(&self) -> u64 {
        self.pixels.len() as u64
    }
}

const PNG_MAGIC: &[u8] = b"\x89PNG\r\n\x1a\n";

/// Identify the format from magic bytes only.
pub fn sniff(bytes: &[u8]) -> Result<ImageFormat> {
    if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Ok(ImageFormat::Jpeg)
    } else if bytes.starts_with(PNG_MAGIC) {
        Ok(ImageFormat::Png)
    } else if bytes.len() >= 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Ok(ImageFormat::Webp)
    } else {
        Err(EngineError::UnsupportedFormat {
            message: "not a JPEG, PNG or WebP image".to_string(),
        })
    }
}

/// Read the page size from the header only. Works on a prefix of the file. A truncated or unreadable header is
/// `Corrupt`. Zero and oversized dimensions are rejected by [`decode`], not here.
pub fn probe(bytes: &[u8]) -> Result<PageInfo> {
    let format = sniff(bytes)?;
    let size = imagesize::blob_size(bytes).map_err(|e| EngineError::Corrupt {
        message: format!("unreadable image header: {e}"),
    })?;
    Ok(PageInfo {
        width: u32::try_from(size.width).map_err(|_| corrupt("image width does not fit in u32"))?,
        height: u32::try_from(size.height)
            .map_err(|_| corrupt("image height does not fit in u32"))?,
        format,
    })
}

/// The DCT scales the backend really supports: `[1, 2, 4, 8]` for JPEG, `[1]` for PNG and WebP.
pub fn supported_denoms(format: ImageFormat) -> &'static [u8] {
    match format {
        ImageFormat::Jpeg => jpeg::SUPPORTED_DENOMS,
        ImageFormat::Png | ImageFormat::Webp => &[1],
    }
}

/// Decode a page at the requested DCT scale (`denom`, 1 for full size).
///
/// If the output would exceed `limits.max_image_pixels`, JPEG moves to the smallest larger supported denom that
/// fits and reports it in `DecodedImage::denom`; PNG and WebP return `LimitExceeded`. Output is `Gray8` for gray
/// sources and `Rgb8` otherwise (alpha flattened onto white, 16-bit reduced to 8-bit, palette expanded, CMYK
/// converted). A decoder panic becomes `Corrupt`.
pub fn decode(bytes: &[u8], denom: u8, limits: &Limits) -> Result<DecodedImage> {
    catch_panic_as_corrupt(|| decode_inner(bytes, denom, limits))
}

/// Run `f`, turning a panic into `Corrupt` (spec section 5: nothing may unwind out of a decode).
fn catch_panic_as_corrupt<T>(f: impl FnOnce() -> Result<T>) -> Result<T> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f))
        .unwrap_or_else(|_| Err(corrupt("decoder panicked")))
}

fn decode_inner(bytes: &[u8], denom: u8, limits: &Limits) -> Result<DecodedImage> {
    let info = probe(bytes)?;
    let supported = supported_denoms(info.format);
    if !supported.contains(&denom) {
        return Err(EngineError::InvalidRequest {
            message: format!("denom {denom} is not supported for {:?}", info.format),
        });
    }
    // Authoritative checks happen again inside each format module, on the size its own decoder reports.
    plan(info.width, info.height, denom, supported, limits)?;
    match info.format {
        ImageFormat::Jpeg => jpeg::decode(bytes, denom, limits),
        ImageFormat::Png => png::decode(bytes, limits),
        ImageFormat::Webp => webp::decode(bytes, limits),
    }
}

pub(crate) fn corrupt(message: &str) -> EngineError {
    EngineError::Corrupt {
        message: message.to_string(),
    }
}

/// Allocate a zeroed buffer of exactly `len` bytes, returning an error instead of aborting if memory is short.
///
/// Callers have already bounded `len` with [`plan`]; this only turns a failed allocation into an `Err`.
pub(crate) fn alloc_zeroed(len: usize) -> Result<Vec<u8>> {
    let mut v = Vec::new();
    v.try_reserve_exact(len).map_err(|_| EngineError::Io {
        message: format!("cannot allocate {len} bytes for decoded pixels"),
    })?;
    v.resize(len, 0);
    Ok(v)
}

/// `width * height * channels` as `usize`, or `None` on overflow.
pub(crate) fn buffer_len(width: u32, height: u32, channels: u64) -> Option<usize> {
    let n = u64::from(width)
        .checked_mul(u64::from(height))?
        .checked_mul(channels)?;
    usize::try_from(n).ok()
}

/// `ceil(n / denom)`.
pub(crate) fn scaled_dim(n: u32, denom: u8) -> u32 {
    n.div_ceil(u32::from(denom))
}

/// Check the source size against the limits and choose the denom to decode at (at least `denom`).
///
/// Nothing is allocated here. Pixel counts use `u64` and cannot overflow (both sides are at most `u32`).
pub(crate) fn plan(
    width: u32,
    height: u32,
    denom: u8,
    supported: &[u8],
    limits: &Limits,
) -> Result<u8> {
    if width == 0 || height == 0 {
        return Err(corrupt("image has a zero dimension"));
    }
    let longest = width.max(height);
    if longest > limits.max_image_dimension {
        return Err(EngineError::LimitExceeded {
            limit: "image_dimension".to_string(),
            value: u64::from(longest),
            max: u64::from(limits.max_image_dimension),
        });
    }
    let pixels_at = |d: u8| u64::from(scaled_dim(width, d)) * u64::from(scaled_dim(height, d));
    if let Some(d) = supported
        .iter()
        .copied()
        .filter(|&d| d >= denom)
        .find(|&d| pixels_at(d) <= limits.max_image_pixels)
    {
        return Ok(d);
    }
    let smallest = supported.iter().copied().max().unwrap_or(1);
    Err(EngineError::LimitExceeded {
        limit: "image_pixels".to_string(),
        value: pixels_at(smallest),
        max: limits.max_image_pixels,
    })
}

/// Blend one 8-bit channel over white: `alpha == 0` gives 255.
#[inline]
pub(crate) fn over_white(c: u8, a: u8) -> u8 {
    let (c, a) = (u32::from(c), u32::from(a));
    ((c * a + 255 * (255 - a) + 127) / 255) as u8
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use std::sync::OnceLock;

    // ---- test image builders ----

    /// Smooth RGB pattern, so JPEG/WebP loss stays small.
    fn rgb_pattern(w: u32, h: u32) -> Vec<u8> {
        let mut v = Vec::with_capacity((w * h * 3) as usize);
        for y in 0..h {
            for x in 0..w {
                v.push((x * 255 / w.max(1)) as u8);
                v.push((y * 255 / h.max(1)) as u8);
                v.push(((x + y) * 255 / (w + h).max(1)) as u8);
            }
        }
        v
    }

    fn gray_pattern(w: u32, h: u32) -> Vec<u8> {
        (0..h)
            .flat_map(|y| (0..w).map(move |x| ((x + y) * 255 / (w + h).max(1)) as u8))
            .collect()
    }

    fn jpeg_encode(
        data: &[u8],
        w: u32,
        h: u32,
        color: jpeg_encoder::ColorType,
        progressive: bool,
    ) -> Vec<u8> {
        let mut out = Vec::new();
        let mut enc = jpeg_encoder::Encoder::new(&mut out, 85);
        enc.set_progressive(progressive);
        enc.encode(data, w as u16, h as u16, color).unwrap();
        out
    }

    fn color_jpeg(w: u32, h: u32) -> Vec<u8> {
        jpeg_encode(
            &rgb_pattern(w, h),
            w,
            h,
            jpeg_encoder::ColorType::Rgb,
            false,
        )
    }

    /// 1600x2400 colour JPEG, encoded once and shared by the tests that need a page-sized input.
    fn page_jpeg() -> &'static [u8] {
        static BYTES: OnceLock<Vec<u8>> = OnceLock::new();
        BYTES.get_or_init(|| color_jpeg(1600, 2400))
    }

    /// 1600x2400 progressive colour JPEG (SOF2), encoded once.
    fn page_progressive_jpeg() -> &'static [u8] {
        static BYTES: OnceLock<Vec<u8>> = OnceLock::new();
        BYTES.get_or_init(|| {
            jpeg_encode(
                &rgb_pattern(1600, 2400),
                1600,
                2400,
                jpeg_encoder::ColorType::Rgb,
                true,
            )
        })
    }

    fn png_via_image<P>(img: &image::ImageBuffer<P, Vec<P::Subpixel>>) -> Vec<u8>
    where
        P: image::Pixel + image::PixelWithColorType,
        [P::Subpixel]: image::EncodableLayout,
    {
        let mut out = Cursor::new(Vec::new());
        img.write_to(&mut out, image::ImageFormat::Png).unwrap();
        out.into_inner()
    }

    fn lossless_webp(rgb: &[u8], w: u32, h: u32) -> Vec<u8> {
        let mut out = Vec::new();
        image::codecs::webp::WebPEncoder::new_lossless(&mut out)
            .encode(rgb, w, h, image::ExtendedColorType::Rgb8)
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

    fn lim() -> Limits {
        Limits::default()
    }

    // ---- tests ----

    #[test]
    fn sniff_detects_formats() {
        assert_eq!(sniff(&[0xFF, 0xD8, 0xFF, 0xE0]).unwrap(), ImageFormat::Jpeg);
        assert_eq!(sniff(b"\x89PNG\r\n\x1a\nrest").unwrap(), ImageFormat::Png);
        assert_eq!(sniff(b"RIFF\x10\0\0\0WEBPVP8 ").unwrap(), ImageFormat::Webp);
        assert!(matches!(
            sniff(b"GIF89a"),
            Err(EngineError::UnsupportedFormat { .. })
        ));
        assert!(matches!(
            sniff(b""),
            Err(EngineError::UnsupportedFormat { .. })
        ));
        // RIFF that is not WebP (a WAV header).
        assert!(matches!(
            sniff(b"RIFF\x10\0\0\0WAVEfmt "),
            Err(EngineError::UnsupportedFormat { .. })
        ));
    }

    #[test]
    fn probe_reads_dims_from_prefix() {
        let jpeg = page_jpeg();
        let info = probe(&jpeg[..16 * 1024]).unwrap();
        assert_eq!(
            info,
            PageInfo {
                width: 1600,
                height: 2400,
                format: ImageFormat::Jpeg
            }
        );
    }

    #[test]
    fn probe_truncated_header_is_corrupt() {
        let jpeg = color_jpeg(64, 64);
        assert!(matches!(
            probe(&jpeg[..4]),
            Err(EngineError::Corrupt { .. })
        ));
        let png = png_via_image(&image::RgbImage::new(8, 8));
        assert!(matches!(
            probe(&png[..12]),
            Err(EngineError::Corrupt { .. })
        ));
    }

    #[test]
    fn gray_jpeg_decodes_to_gray8() {
        let (w, h) = (120, 90);
        let jpeg = jpeg_encode(
            &gray_pattern(w, h),
            w,
            h,
            jpeg_encoder::ColorType::Luma,
            false,
        );
        let img = decode(&jpeg, 1, &lim()).unwrap();
        assert_eq!(
            (img.width, img.height, img.layout, img.denom),
            (w, h, PixelLayout::Gray8, 1)
        );
        assert_eq!(img.pixels.len(), (w * h) as usize);
        assert_eq!(img.byte_size(), u64::from(w * h));
        assert!(mean_abs_diff(&img.pixels, &gray_pattern(w, h)) <= 3.0);
    }

    #[test]
    fn color_jpeg_decodes_to_rgb8() {
        let (w, h) = (120, 90);
        let img = decode(&color_jpeg(w, h), 1, &lim()).unwrap();
        assert_eq!(
            (img.width, img.height, img.layout, img.denom),
            (w, h, PixelLayout::Rgb8, 1)
        );
        assert_eq!(img.pixels.len(), (w * h * 3) as usize);
        assert!(mean_abs_diff(&img.pixels, &rgb_pattern(w, h)) <= 3.0);
    }

    #[test]
    fn progressive_jpeg_decodes() {
        let (w, h) = (256, 256);
        let data = rgb_pattern(w, h);
        let base = decode(
            &jpeg_encode(&data, w, h, jpeg_encoder::ColorType::Rgb, false),
            1,
            &lim(),
        )
        .unwrap();
        let prog = decode(
            &jpeg_encode(&data, w, h, jpeg_encoder::ColorType::Rgb, true),
            1,
            &lim(),
        )
        .unwrap();
        assert_eq!(
            (prog.width, prog.height, prog.layout),
            (w, h, PixelLayout::Rgb8)
        );
        assert!(mean_abs_diff(&base.pixels, &prog.pixels) <= 3.0);
    }

    fn solid_red_cmyk(w: u32, h: u32) -> Vec<u8> {
        // C0 M255 Y255 K0 per pixel.
        (0..w * h).flat_map(|_| [0u8, 255, 255, 0]).collect()
    }

    /// `byte_size()` reports `len`, so the cache budget is only honest if no spare capacity is kept.
    fn assert_exact_capacity(img: &DecodedImage) {
        assert_eq!(
            img.pixels.capacity(),
            img.pixels.len(),
            "spare capacity after in-place conversion"
        );
    }

    fn assert_center_red(img: &DecodedImage) {
        assert_eq!(img.layout, PixelLayout::Rgb8);
        let i = (((img.height / 2) * img.width + img.width / 2) * 3) as usize;
        let (r, g, b) = (img.pixels[i], img.pixels[i + 1], img.pixels[i + 2]);
        assert!(
            r > 200 && g < 60 && b < 60,
            "center pixel was ({r},{g},{b})"
        );
    }

    #[test]
    fn cmyk_jpeg_decodes_to_rgb() {
        let (w, h) = (64, 64);
        let jpeg = jpeg_encode(
            &solid_red_cmyk(w, h),
            w,
            h,
            jpeg_encoder::ColorType::Cmyk,
            false,
        );
        let img = decode(&jpeg, 1, &lim()).unwrap();
        assert_eq!((img.width, img.height), (w, h));
        assert_eq!(img.pixels.len(), (w * h * 3) as usize);
        assert_exact_capacity(&img);
        assert_center_red(&img);
    }

    #[test]
    fn ycck_jpeg_decodes_to_rgb() {
        let (w, h) = (64, 64);
        let jpeg = jpeg_encode(
            &solid_red_cmyk(w, h),
            w,
            h,
            jpeg_encoder::ColorType::CmykAsYcck,
            false,
        );
        let img = decode(&jpeg, 1, &lim()).unwrap();
        assert_exact_capacity(&img);
        assert_center_red(&img);
    }

    #[test]
    fn jpeg_scaled_decode_dims() {
        assert!(supported_denoms(ImageFormat::Jpeg).contains(&2));
        let img = decode(page_jpeg(), 2, &lim()).unwrap();
        assert_eq!((img.width, img.height, img.denom), (800, 1200, 2));
        assert_eq!(img.pixels.len(), 800 * 1200 * 3);
    }

    #[test]
    fn every_supported_jpeg_denom_decodes_with_ceil_dims() {
        assert_eq!(supported_denoms(ImageFormat::Jpeg), &[1, 2, 4, 8]);
        // Odd dimensions: the scaled size is ceil(dim / denom).
        let (w, h) = (101, 77);
        let jpeg = color_jpeg(w, h);
        for &d in supported_denoms(ImageFormat::Jpeg) {
            let img = decode(&jpeg, d, &lim()).unwrap();
            assert_eq!(
                (img.width, img.height),
                (w.div_ceil(u32::from(d)), h.div_ceil(u32::from(d))),
                "denom {d}"
            );
            assert_eq!(img.denom, d);
            assert_eq!(
                img.pixels.len() as u64,
                u64::from(img.width) * u64::from(img.height) * 3
            );
        }
        assert_eq!(supported_denoms(ImageFormat::Png), &[1]);
        assert_eq!(supported_denoms(ImageFormat::Webp), &[1]);
    }

    #[test]
    fn unsupported_denom_is_invalid_request() {
        assert!(matches!(
            decode(&color_jpeg(32, 32), 3, &lim()),
            Err(EngineError::InvalidRequest { .. })
        ));
        assert!(matches!(
            decode(&color_jpeg(32, 32), 0, &lim()),
            Err(EngineError::InvalidRequest { .. })
        ));
        let png = png_via_image(&image::RgbImage::new(8, 8));
        assert!(matches!(
            decode(&png, 2, &lim()),
            Err(EngineError::InvalidRequest { .. })
        ));
    }

    #[test]
    fn png_variants_normalize() {
        // 16-bit gray -> Gray8 (high byte kept).
        let g16 = image::ImageBuffer::<image::Luma<u16>, _>::from_fn(4, 3, |x, y| {
            image::Luma([0xAB00 + (x * 16 + y) as u16])
        });
        let img = decode(&png_via_image(&g16), 1, &lim()).unwrap();
        assert_eq!(
            (img.width, img.height, img.layout),
            (4, 3, PixelLayout::Gray8)
        );
        assert_eq!(img.pixels.len(), 12);
        assert!(img.pixels.iter().all(|&p| p == 0xAB));

        // Palette -> Rgb8.
        let mut pal = Vec::new();
        {
            let mut enc = ::png::Encoder::new(&mut pal, 4, 2);
            enc.set_color(::png::ColorType::Indexed);
            enc.set_depth(::png::BitDepth::Eight);
            enc.set_palette(vec![255, 0, 0, 0, 255, 0, 0, 0, 255]);
            let mut w = enc.write_header().unwrap();
            w.write_image_data(&[0, 1, 2, 0, 1, 2, 0, 1]).unwrap();
        }
        let img = decode(&pal, 1, &lim()).unwrap();
        assert_eq!(
            (img.width, img.height, img.layout),
            (4, 2, PixelLayout::Rgb8)
        );
        assert_eq!(&img.pixels[0..9], &[255, 0, 0, 0, 255, 0, 0, 0, 255]);

        // RGBA: a fully transparent pixel becomes white; an opaque pixel is unchanged.
        let mut rgba = image::RgbaImage::from_pixel(2, 1, image::Rgba([10, 20, 30, 255]));
        rgba.put_pixel(1, 0, image::Rgba([10, 20, 30, 0]));
        let img = decode(&png_via_image(&rgba), 1, &lim()).unwrap();
        assert_eq!(img.layout, PixelLayout::Rgb8);
        assert_eq!(img.pixels, vec![10, 20, 30, 255, 255, 255]);
        assert_exact_capacity(&img);

        // Gray + alpha -> Gray8, transparent flattens to white.
        let mut ga = image::GrayAlphaImage::from_pixel(2, 1, image::LumaA([40, 255]));
        ga.put_pixel(1, 0, image::LumaA([40, 0]));
        let img = decode(&png_via_image(&ga), 1, &lim()).unwrap();
        assert_eq!(img.layout, PixelLayout::Gray8);
        assert_eq!(img.pixels, vec![40, 255]);
        assert_exact_capacity(&img);
    }

    #[test]
    fn png_half_alpha_blends_onto_white() {
        let rgba = image::RgbaImage::from_pixel(1, 1, image::Rgba([0, 0, 0, 128]));
        let img = decode(&png_via_image(&rgba), 1, &lim()).unwrap();
        // 255 * (255 - 128) / 255 = 127
        assert_eq!(img.pixels, vec![127, 127, 127]);
    }

    #[test]
    fn webp_lossless_and_lossy_decode() {
        let (w, h) = (33, 17);
        let rgb = rgb_pattern(w, h);
        let img = decode(&lossless_webp(&rgb, w, h), 1, &lim()).unwrap();
        assert_eq!(
            (img.width, img.height, img.layout, img.denom),
            (w, h, PixelLayout::Rgb8, 1)
        );
        assert_eq!(img.pixels, rgb);

        let lossy = include_bytes!("../../tests/fixtures/lossy.webp");
        assert_eq!(probe(lossy).unwrap().format, ImageFormat::Webp);
        let img = decode(lossy, 1, &lim()).unwrap();
        assert_eq!(
            (img.width, img.height, img.layout),
            (64, 96, PixelLayout::Rgb8)
        );
        assert_eq!(img.pixels.len(), 64 * 96 * 3);
    }

    #[test]
    fn webp_with_alpha_flattens_onto_white() {
        let rgba = [10u8, 20, 30, 255, 10, 20, 30, 0];
        let mut out = Vec::new();
        image::codecs::webp::WebPEncoder::new_lossless(&mut out)
            .encode(&rgba, 2, 1, image::ExtendedColorType::Rgba8)
            .unwrap();
        let img = decode(&out, 1, &lim()).unwrap();
        assert_eq!(img.layout, PixelLayout::Rgb8);
        assert_eq!(&img.pixels[0..3], &[10, 20, 30]);
        assert_eq!(&img.pixels[3..6], &[255, 255, 255]);
        assert_exact_capacity(&img);
    }

    #[test]
    fn decode_sniffs_content_not_extension() {
        // The decoder only ever sees bytes, so a ".jpg" that is really a PNG or WebP decodes as what it is.
        let png = png_via_image(&image::RgbImage::from_pixel(5, 4, image::Rgb([1, 2, 3])));
        assert_eq!(probe(&png).unwrap().format, ImageFormat::Png);
        let img = decode(&png, 1, &lim()).unwrap();
        assert_eq!(
            (img.width, img.height, img.layout),
            (5, 4, PixelLayout::Rgb8)
        );
        assert_eq!(&img.pixels[0..3], &[1, 2, 3]);

        let webp = lossless_webp(&rgb_pattern(8, 8), 8, 8);
        assert_eq!(probe(&webp).unwrap().format, ImageFormat::Webp);
        assert_eq!((decode(&webp, 1, &lim()).unwrap().width), 8);
    }

    #[test]
    fn over_limit_png_is_rejected() {
        let png = png_via_image(&image::RgbImage::new(100, 100));
        let limits = Limits {
            max_image_pixels: 1000,
            ..Limits::default()
        };
        assert_eq!(
            decode(&png, 1, &limits).unwrap_err(),
            EngineError::LimitExceeded {
                limit: "image_pixels".to_string(),
                value: 10_000,
                max: 1000
            }
        );
    }

    #[test]
    fn over_limit_jpeg_downscales_or_rejects() {
        let limits = Limits {
            max_image_pixels: 1_000_000,
            ..Limits::default()
        };
        // 1600x2400 = 3.84 MP at denom 1; 960 KP at denom 2 fits.
        let img = decode(page_jpeg(), 1, &limits).unwrap();
        assert_eq!((img.width, img.height, img.denom), (800, 1200, 2));

        // Nothing fits (even 1/8 is 200x300 = 60 KP): LimitExceeded, value measured at the smallest denom.
        let tiny = Limits {
            max_image_pixels: 1000,
            ..Limits::default()
        };
        assert_eq!(
            decode(page_jpeg(), 1, &tiny).unwrap_err(),
            EngineError::LimitExceeded {
                limit: "image_pixels".to_string(),
                value: 200 * 300,
                max: 1000
            }
        );
    }

    #[test]
    fn progressive_jpeg_source_pixels_are_capped() {
        // libjpeg allocates a full-size coefficient array for a progressive file whatever the denom, so the
        // source size, not just the scaled output, must fit max_image_pixels.
        let prog = page_progressive_jpeg();
        assert!(
            prog.windows(2).any(|w| w == [0xFF, 0xC2]),
            "test input must contain an SOF2 (progressive) marker"
        );
        let limits = Limits {
            max_image_pixels: 1_000_000,
            ..Limits::default()
        };
        // Denom 4 would output 400x600 = 240 KP, which fits, but the source is 3.84 MP.
        for denom in [1u8, 2, 4, 8] {
            assert_eq!(
                decode(prog, denom, &limits).unwrap_err(),
                EngineError::LimitExceeded {
                    limit: "image_pixels".to_string(),
                    value: 1600 * 2400,
                    max: 1_000_000
                },
                "denom {denom}"
            );
        }
        // Same limits: the baseline JPEG still downscales instead of being rejected.
        assert!(!page_jpeg().windows(2).any(|w| w == [0xFF, 0xC2]));
        let img = decode(page_jpeg(), 4, &limits).unwrap();
        assert_eq!((img.width, img.height, img.denom), (400, 600, 4));
        let img = decode(page_jpeg(), 1, &limits).unwrap();
        assert_eq!((img.width, img.height, img.denom), (800, 1200, 2));
        // With room for the source, the progressive file decodes (scaled).
        let img = decode(prog, 8, &lim()).unwrap();
        assert_eq!((img.width, img.height, img.denom), (200, 300, 8));
    }

    /// Flat mid-gray 3-component baseline JPEG (SOF0) coded as THREE non-interleaved scans (one per component).
    ///
    /// Hand-built: every 8x8 block is a zero DC difference plus an immediate end-of-block, coded with one-symbol
    /// Huffman tables ("0" for each), so a block is the two bits `00`. `w` and `h` must be multiples of 8.
    fn sequential_multiscan_jpeg(w: u16, h: u16) -> Vec<u8> {
        assert!(w.is_multiple_of(8) && h.is_multiple_of(8) && w > 0 && h > 0);
        let mut out = vec![0xFF, 0xD8]; // SOI
        out.extend_from_slice(&[0xFF, 0xDB, 0x00, 0x43, 0x00]); // DQT, table 0
        out.extend_from_slice(&[1u8; 64]);
        out.extend_from_slice(&[0xFF, 0xC0, 0x00, 0x11, 0x08]); // SOF0, 8-bit, 3 components
        out.extend_from_slice(&h.to_be_bytes());
        out.extend_from_slice(&w.to_be_bytes());
        out.push(3);
        for id in 1..=3u8 {
            out.extend_from_slice(&[id, 0x11, 0x00]); // 1x1 sampling, quant table 0
        }
        for class_id in [0x00u8, 0x10] {
            // DHT: DC table 0, then AC table 0. One code of length 1 for symbol 0 (DC category 0 / AC EOB).
            out.extend_from_slice(&[0xFF, 0xC4, 0x00, 0x14, class_id, 1]);
            out.extend_from_slice(&[0u8; 15]);
            out.push(0x00);
        }
        let blocks = usize::from(w / 8) * usize::from(h / 8);
        let mut data = vec![0u8; (blocks * 2).div_ceil(8)];
        let used_bits = blocks * 2;
        for bit in used_bits..data.len() * 8 {
            data[bit / 8] |= 0x80 >> (bit % 8); // pad with 1 bits
        }
        assert!(!data.contains(&0xFF), "fixture would need byte stuffing");
        for id in 1..=3u8 {
            out.extend_from_slice(&[0xFF, 0xDA, 0x00, 0x08, 0x01, id, 0x00, 0x00, 0x3F, 0x00]);
            out.extend_from_slice(&data);
        }
        out.extend_from_slice(&[0xFF, 0xD9]); // EOI
        out
    }

    fn patch_sof_dims(jpeg: &mut [u8], w: u16, h: u16) {
        let sof = jpeg
            .windows(2)
            .position(|p| p == [0xFF, 0xC0])
            .expect("SOF0 marker");
        jpeg[sof + 5..sof + 7].copy_from_slice(&h.to_be_bytes());
        jpeg[sof + 7..sof + 9].copy_from_slice(&w.to_be_bytes());
    }

    #[test]
    fn sequential_multiscan_jpeg_decodes_when_small() {
        // The hand-built fixture is a valid file: three SOS scans, not progressive.
        let jpeg = sequential_multiscan_jpeg(16, 16);
        assert_eq!(jpeg.windows(2).filter(|w| *w == [0xFF, 0xDA]).count(), 3);
        assert!(!jpeg.windows(2).any(|w| w == [0xFF, 0xC2]));
        let img = decode(&jpeg, 1, &lim()).unwrap();
        assert_eq!((img.width, img.height, img.denom), (16, 16, 1));
        assert!(img.pixels.iter().all(|&p| p.abs_diff(128) <= 1));
        // Also under a tight pixel limit that the 256-pixel source fits.
        let tight = Limits {
            max_image_pixels: 1_000,
            ..Limits::default()
        };
        assert_eq!(decode(&jpeg, 2, &tight).unwrap().width, 8);
    }

    #[test]
    fn sequential_multiscan_jpeg_source_pixels_are_capped() {
        // libjpeg allocates a full coefficient array for any multi-scan file, SOF0 included, so a tiny file that
        // declares 4000x4000 must be refused from the header, like the progressive case.
        let mut jpeg = sequential_multiscan_jpeg(16, 16);
        patch_sof_dims(&mut jpeg, 4000, 4000);
        let limits = Limits {
            max_image_pixels: 1_000_000,
            ..Limits::default()
        };
        // Denom 8 would output 500x500 = 250 KP, which fits, but the source is 16 MP.
        for denom in [1u8, 2, 4, 8] {
            assert_eq!(
                decode(&jpeg, denom, &limits).unwrap_err(),
                EngineError::LimitExceeded {
                    limit: "image_pixels".to_string(),
                    value: 4000 * 4000,
                    max: 1_000_000
                },
                "denom {denom}"
            );
        }
        // An interleaved single-scan baseline with the same declared size keeps its denom downscale.
        let mut baseline = color_jpeg(16, 16);
        let sof = baseline.windows(2).position(|w| w == [0xFF, 0xC0]).unwrap();
        baseline[sof + 5..sof + 9].copy_from_slice(&[0x0F, 0xA0, 0x0F, 0xA0]); // 4000 x 4000
        assert!(!matches!(
            decode(&baseline, 8, &limits),
            Err(EngineError::LimitExceeded { .. })
        ));
    }

    #[test]
    fn baseline_and_gray_single_scan_jpegs_keep_downscale_under_a_tight_limit() {
        let limits = Limits {
            max_image_pixels: 1_000_000,
            ..Limits::default()
        };
        // Colour interleaved (3 of 3 components in the one scan) and gray (1 of 1).
        let img = decode(page_jpeg(), 1, &limits).unwrap();
        assert_eq!((img.width, img.height, img.denom), (800, 1200, 2));
        let gray = jpeg_encode(
            &gray_pattern(1600, 2400),
            1600,
            2400,
            jpeg_encoder::ColorType::Luma,
            false,
        );
        let img = decode(&gray, 1, &limits).unwrap();
        assert_eq!((img.width, img.height, img.denom), (800, 1200, 2));
    }

    #[test]
    fn over_dimension_is_rejected_before_decoding() {
        let png = png_via_image(&image::RgbImage::new(100, 10));
        let limits = Limits {
            max_image_dimension: 99,
            ..Limits::default()
        };
        assert_eq!(
            decode(&png, 1, &limits).unwrap_err(),
            EngineError::LimitExceeded {
                limit: "image_dimension".to_string(),
                value: 100,
                max: 99
            }
        );
    }

    #[test]
    fn jpeg_header_claiming_huge_size_is_rejected_before_allocating() {
        // Patch the SOF0 size of a tiny JPEG to 60000x60000. The decoder must refuse from the header alone.
        let mut jpeg = color_jpeg(16, 16);
        let sof = jpeg
            .windows(2)
            .position(|w| w == [0xFF, 0xC0])
            .expect("baseline SOF0 marker");
        jpeg[sof + 5..sof + 9].copy_from_slice(&[0xEA, 0x60, 0xEA, 0x60]); // height, width = 60000
        assert_eq!(probe(&jpeg).unwrap().width, 60000);
        let limits = Limits {
            max_image_pixels: 1_000_000,
            ..Limits::default()
        };
        assert!(matches!(
            decode(&jpeg, 1, &limits),
            Err(EngineError::LimitExceeded { ref limit, .. }) if limit == "image_pixels"
        ));
        let narrow = Limits {
            max_image_dimension: 4096,
            ..Limits::default()
        };
        assert!(matches!(
            decode(&jpeg, 1, &narrow),
            Err(EngineError::LimitExceeded { ref limit, .. }) if limit == "image_dimension"
        ));
    }

    #[test]
    fn truncated_and_garbage_input_never_panic() {
        let jpeg = color_jpeg(200, 150);
        let png = png_via_image(&image::RgbImage::from_pixel(100, 80, image::Rgb([9, 8, 7])));
        let webp = lossless_webp(&rgb_pattern(100, 80), 100, 80);
        for (name, full) in [("jpeg", jpeg), ("png", png), ("webp", webp)] {
            let cut = &full[..full.len() * 6 / 10];
            match decode(cut, 1, &lim()) {
                Ok(_) | Err(EngineError::Corrupt { .. }) => {}
                Err(other) => panic!("{name}: unexpected error {other:?}"),
            }
        }
        assert!(decode(&[0xFFu8; 1024], 1, &lim()).is_err());
        assert!(decode(&[], 1, &lim()).is_err());
    }

    #[test]
    fn a_panic_in_the_decoder_becomes_corrupt() {
        // Spec section 5: a decoder panic must reach the caller as an error value, never unwind through the FFI.
        let result: Result<DecodedImage> =
            catch_panic_as_corrupt(|| panic!("simulated decoder bug"));
        assert_eq!(result.unwrap_err(), corrupt("decoder panicked"));
        // A closure that returns normally passes its result (Ok or Err) through untouched.
        assert_eq!(
            catch_panic_as_corrupt(|| Err::<(), _>(EngineError::InvalidRequest {
                message: "x".to_string()
            }))
            .unwrap_err(),
            EngineError::InvalidRequest {
                message: "x".to_string()
            }
        );
        assert_eq!(catch_panic_as_corrupt(|| Ok(7u8)).unwrap(), 7);
    }

    #[test]
    fn plan_rejects_zero_dimension() {
        assert!(matches!(
            plan(0, 10, 1, &[1], &lim()),
            Err(EngineError::Corrupt { .. })
        ));
        assert!(matches!(
            plan(10, 0, 1, &[1], &lim()),
            Err(EngineError::Corrupt { .. })
        ));
    }
}
