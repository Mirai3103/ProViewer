//! Linear-light Lanczos3 resize of a decoded page (or a region of it) into an RGBA8888 buffer.
//!
//! Pipeline: u8 sRGB -> u16 linear (256-entry table) -> `fast_image_resize` Lanczos3 convolution on `U16`/`U16x3`
//! -> u8 sRGB (65536-entry table) -> RGBA. Everything is validated before any buffer is allocated, and every
//! intermediate buffer is sized from the validated crop and output dimensions only.

use std::sync::OnceLock;

use fast_image_resize::images::TypedImage;
use fast_image_resize::pixels::{InnerPixel, U16x3, U16};
use fast_image_resize::{FilterType, PixelTrait, ResizeAlg, ResizeOptions, Resizer};

use crate::decode::{DecodedImage, PixelLayout};
use crate::error::{EngineError, Result};

/// A rectangle in **page** pixel coordinates (the original, undecimated page).
#[derive(uniffi::Record, Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rect {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

/// Largest supported decimation `d` with `d <= min(region.width/out_w, region.height/out_h)`; 1 if none.
/// Never picks a source that would have to be upsampled.
pub fn choose_denom(region: Rect, out_w: u32, out_h: u32, supported: &[u8]) -> u8 {
    if out_w == 0 || out_h == 0 {
        return 1;
    }
    let ratio = (region.width as f64 / out_w as f64).min(region.height as f64 / out_h as f64);
    supported
        .iter()
        .copied()
        .filter(|&d| d >= 1 && d as f64 <= ratio)
        .max()
        .unwrap_or(1)
}

fn invalid(message: impl Into<String>) -> EngineError {
    EngineError::InvalidRequest {
        message: message.into(),
    }
}

/// sRGB u8 -> linear u16 (0..=65535).
fn to_linear_table() -> &'static [u16; 256] {
    static TABLE: OnceLock<[u16; 256]> = OnceLock::new();
    TABLE.get_or_init(|| {
        let mut t = [0u16; 256];
        for (i, v) in t.iter_mut().enumerate() {
            let c = i as f64 / 255.0;
            let lin = if c <= 0.04045 {
                c / 12.92
            } else {
                ((c + 0.055) / 1.055).powf(2.4)
            };
            *v = (lin * 65535.0).round() as u16;
        }
        t
    })
}

/// Linear u16 (0..=65535) -> sRGB u8.
fn to_srgb_table() -> &'static [u8] {
    static TABLE: OnceLock<Vec<u8>> = OnceLock::new();
    TABLE.get_or_init(|| {
        (0..65536u32)
            .map(|i| {
                let lin = i as f64 / 65535.0;
                let c = if lin <= 0.0031308 {
                    lin * 12.92
                } else {
                    1.055 * lin.powf(1.0 / 2.4) - 0.055
                };
                (c * 255.0).round().clamp(0.0, 255.0) as u8
            })
            .collect()
    })
}

/// Resize `src` (already linear) into a new `out_w x out_h` image, sampling the f64 crop box.
fn resize_linear<P: PixelTrait + InnerPixel>(
    src: &TypedImage<'_, P>,
    out_w: u32,
    out_h: u32,
    crop: [f64; 4],
) -> Result<TypedImage<'static, P>> {
    let mut dst = TypedImage::<P>::new(out_w, out_h);
    let options = ResizeOptions::new()
        .resize_alg(ResizeAlg::Convolution(FilterType::Lanczos3))
        .crop(crop[0], crop[1], crop[2], crop[3]);
    Resizer::new()
        .resize_typed(src, &mut dst, &options)
        .map_err(|e| invalid(format!("resize failed: {e}")))?;
    Ok(dst)
}

/// Upper bound on the linear u16 buffer (and on the output band) allocated per band: 16 MiB. Big enough that
/// ordinary pages render in one or a few bands, small enough that a 100 MP source never needs a
/// source-sized intermediate (a full-page 100 MP RGB render uses ~23 bands of ~16 MiB instead of ~600 MB).
const BAND_BUDGET_BYTES: usize = 16 * 1024 * 1024;

/// Source pixels of filter margin needed on each side of a crop: Lanczos3 support is 3 source px at
/// scale <= 1 and `3 * scale` above that; +1 covers the rounding of the window edges.
fn band_margin(scale: f64) -> u32 {
    (3.0 * scale.max(1.0)).ceil() as u32 + 1
}

/// Upper bound on the source rows of a band of `rows` output rows (crop height, both margins, floor/ceil slack).
fn band_src_rows(rows: u32, scale_y: f64, margin: u32) -> usize {
    (rows as f64 * scale_y).ceil() as usize + 2 * margin as usize + 2
}

/// Bytes of the linear u16 buffer for a band of `rows` output rows (upper bound).
fn band_lin_bytes(box_w: u32, channels: usize, rows: u32, scale_y: f64, margin: u32) -> usize {
    band_src_rows(rows, scale_y, margin)
        .saturating_mul(box_w as usize)
        .saturating_mul(2 * channels)
}

/// Number of output rows per band so that both the linear source buffer and the linear output band stay within
/// `budget` bytes. Never less than 1 and never more than `out_h`.
fn band_rows(
    box_w: u32,
    out_w: u32,
    channels: usize,
    scale_y: f64,
    margin: u32,
    out_h: u32,
    budget: usize,
) -> u32 {
    let src_row_bytes = (box_w as usize).saturating_mul(2 * channels).max(1);
    let out_row_bytes = (out_w as usize).saturating_mul(2 * channels).max(1);
    let by_output = (budget / out_row_bytes).max(1);
    let src_rows_budget = budget / src_row_bytes;
    let usable = src_rows_budget.saturating_sub(2 * margin as usize + 2);
    let by_source = ((usable as f64 / scale_y).floor() as usize).max(1);
    let mut rows = by_output.min(by_source).min(out_h as usize).max(1) as u32;
    // `by_source` is a floor of a float division: tighten it against the exact bound.
    while rows > 1 && band_lin_bytes(box_w, channels, rows, scale_y, margin) > budget {
        rows -= 1;
    }
    rows
}

fn alloc_error(what: &str) -> EngineError {
    EngineError::Io {
        message: format!("could not allocate {what}"),
    }
}

/// Keep `len` (a crop extent) inside `avail` (the room after the crop origin) despite float rounding.
fn fit_extent(len: f64, avail: f64) -> f64 {
    if len <= avail {
        len
    } else {
        avail * (1.0 - 1e-12)
    }
}

/// Geometry shared by every band of one render.
struct BandPlan {
    /// Source-space crop of the whole region.
    left: f64,
    top: f64,
    crop_w: f64,
    crop_h: f64,
    /// Source columns kept per band (crop plus margin), `[ex0, ex1)`.
    ex0: u32,
    ex1: u32,
    margin_y: u32,
}

/// Render in output bands: per band only the needed source rows (plus filter margin) are converted to linear,
/// and the exact fractional crop keeps the band identical to the matching rows of a one-shot render.
#[allow(clippy::too_many_arguments)]
fn render_bands<P, C, E>(
    src: &DecodedImage,
    plan: &BandPlan,
    out_w: u32,
    out_h: u32,
    rows_per_band: u32,
    dst: &mut [u8],
    dst_stride: usize,
    convert_row: C,
    emit_row: E,
) -> Result<()>
where
    P: PixelTrait + InnerPixel + Default,
    C: Fn(&[u8], &mut [P]),
    E: Fn(&[P], &mut [u8]),
{
    let channels = src.layout.channels() as usize;
    let src_row = src.width as usize * channels;
    let (ew, row_bytes) = (plan.ex1 - plan.ex0, out_w as usize * 4);
    let scale_y = plan.crop_h / out_h as f64;
    let ew_us = ew as usize;

    // One reusable buffer sized for the largest band, allocated fallibly.
    let max_rows = band_src_rows(rows_per_band, scale_y, plan.margin_y).min(src.height as usize);
    let mut lin: Vec<P> = Vec::new();
    lin.try_reserve_exact(max_rows * ew_us)
        .map_err(|_| alloc_error("the linear source band"))?;
    lin.resize(max_rows * ew_us, P::default());

    let mut r0 = 0u32;
    while r0 < out_h {
        let r1 = r0.saturating_add(rows_per_band).min(out_h);
        let y_top = plan.top + r0 as f64 * scale_y;
        let y_bot = if r1 == out_h {
            plan.top + plan.crop_h
        } else {
            plan.top + r1 as f64 * scale_y
        };
        let ey0 = ((y_top - plan.margin_y as f64).floor().max(0.0) as u32).min(src.height - 1);
        let ey1 = ((y_bot + plan.margin_y as f64).ceil().min(src.height as f64) as u32)
            .clamp(ey0 + 1, src.height);
        let eh = (ey1 - ey0) as usize;
        let band = &mut lin[..eh * ew_us];
        for (r, out_row) in band.chunks_exact_mut(ew_us).enumerate() {
            let off = (ey0 as usize + r) * src_row + plan.ex0 as usize * channels;
            convert_row(&src.pixels[off..off + ew_us * channels], out_row);
        }
        let rel_left = plan.left - plan.ex0 as f64;
        let rel_top = y_top - ey0 as f64;
        let crop = [
            rel_left,
            rel_top,
            fit_extent(plan.crop_w, ew as f64 - rel_left),
            fit_extent(y_bot - y_top, eh as f64 - rel_top),
        ];
        let img = TypedImage::from_pixels_slice(ew, eh as u32, band)
            .map_err(|e| invalid(e.to_string()))?;
        let out = resize_linear(&img, out_w, r1 - r0, crop)?;
        for (i, o) in out.pixels().chunks_exact(out_w as usize).enumerate() {
            let start = (r0 as usize + i) * dst_stride;
            emit_row(o, &mut dst[start..start + row_bytes]);
        }
        r0 = r1;
    }
    Ok(())
}

/// Upper bound on either dimension of the virtual full-page size of [`render_scaled_region`] (2^20). It keeps
/// every `coordinate * page dimension` product exact in `f64` and the scale far from degenerate.
pub const MAX_SCALED_FULL_DIM: u32 = 1 << 20;

/// A crop in **page** coordinates with sub-pixel precision.
#[derive(Clone, Copy, Debug)]
struct PageCrop {
    left: f64,
    top: f64,
    width: f64,
    height: f64,
}

impl PageCrop {
    fn of_rect(region: Rect) -> Self {
        PageCrop {
            left: region.x as f64,
            top: region.y as f64,
            width: region.width as f64,
            height: region.height as f64,
        }
    }
}

/// Checks that `dst` holds `out_h` rows of `out_w * 4` bytes, `dst_stride` bytes apart. Returns the row size.
fn validate_dst(out_w: u32, out_h: u32, dst_len: usize, dst_stride: usize) -> Result<usize> {
    if out_w == 0 || out_h == 0 {
        return Err(invalid("output size must be non-zero"));
    }
    let row_bytes = (out_w as usize)
        .checked_mul(4)
        .ok_or_else(|| invalid("output row size overflows"))?;
    if dst_stride < row_bytes {
        return Err(invalid("dst_stride is smaller than out_w * 4"));
    }
    let needed = dst_stride
        .checked_mul(out_h as usize - 1)
        .and_then(|n| n.checked_add(row_bytes))
        .ok_or_else(|| invalid("output buffer size overflows"))?;
    if dst_len < needed {
        return Err(invalid("dst is too small for the requested output"));
    }
    Ok(row_bytes)
}

/// Checks that `region` is non-empty and inside a `page_w x page_h` page, without overflowing.
fn validate_region(region: Rect, page_w: u32, page_h: u32) -> Result<()> {
    if region.width == 0 || region.height == 0 {
        return Err(invalid("region is empty"));
    }
    let right = region.x.checked_add(region.width);
    let bottom = region.y.checked_add(region.height);
    if right.is_none_or(|r| r > page_w) || bottom.is_none_or(|b| b > page_h) {
        return Err(invalid("region is outside the page"));
    }
    Ok(())
}

/// Checks a scaled-tile request: `full_w x full_h` in `1..=MAX_SCALED_FULL_DIM` and the output rectangle
/// `(out_x, out_y, out_w, out_h)` non-empty and inside it, without overflowing. Shared with the engine, which
/// runs it before decoding.
pub(crate) fn validate_scaled_rect(
    full_w: u32,
    full_h: u32,
    out_x: u32,
    out_y: u32,
    out_w: u32,
    out_h: u32,
) -> Result<()> {
    let dims = 1..=MAX_SCALED_FULL_DIM;
    if !dims.contains(&full_w) || !dims.contains(&full_h) {
        return Err(invalid(format!(
            "full size must be 1..={MAX_SCALED_FULL_DIM} in each dimension"
        )));
    }
    if out_w == 0 || out_h == 0 {
        return Err(invalid("output size must be non-zero"));
    }
    let right = out_x.checked_add(out_w);
    let bottom = out_y.checked_add(out_h);
    if right.is_none_or(|r| r > full_w) || bottom.is_none_or(|b| b > full_h) {
        return Err(invalid("output rectangle is outside the full size"));
    }
    Ok(())
}

/// Render `region` (page coordinates) of `src` into `out_w x out_h` RGBA8888 rows of `dst`.
///
/// `src` may be a decimated decode (`src.denom > 1`): it is smaller than the page, so the region is scaled by
/// `src.width/page_w` and `src.height/page_h`. Bytes beyond `out_w*4` in each `dst` row are left untouched.
#[allow(clippy::too_many_arguments)]
pub fn render_region(
    src: &DecodedImage,
    page_w: u32,
    page_h: u32,
    region: Rect,
    out_w: u32,
    out_h: u32,
    dst: &mut [u8],
    dst_stride: usize,
) -> Result<()> {
    validate_region(region, page_w, page_h)?;
    render_region_with_budget(
        src,
        page_w,
        page_h,
        PageCrop::of_rect(region),
        out_w,
        out_h,
        dst,
        dst_stride,
        BAND_BUDGET_BYTES,
    )
}

/// Render the `out_w x out_h` rectangle at `(out_x, out_y)` of a virtual `full_w x full_h` render of the whole
/// page into `dst` (RGBA8888 rows `dst_stride` bytes apart; padding untouched).
///
/// The rectangle maps to the page crop `out_x * page_w / full_w` (and likewise for every edge), so tiles of the
/// same full size share one page <-> output mapping: their pixels are the matching rows and columns of a
/// one-shot render of the whole page at `full_w x full_h` within 1 channel level, with no gap, overlap or
/// row/column jump at a seam. (Not byte-exact: the resizer's coefficients for a tile come from differently
/// rounded f64 values, which can flip an isolated value by 1.)
#[allow(clippy::too_many_arguments)]
pub fn render_scaled_region(
    src: &DecodedImage,
    page_w: u32,
    page_h: u32,
    full_w: u32,
    full_h: u32,
    out_x: u32,
    out_y: u32,
    out_w: u32,
    out_h: u32,
    dst: &mut [u8],
    dst_stride: usize,
) -> Result<()> {
    validate_scaled_rect(full_w, full_h, out_x, out_y, out_w, out_h)?;
    if page_w == 0 || page_h == 0 {
        return Err(invalid("page and source sizes must be non-zero"));
    }
    // Every product is below 2^20 * 2^32 < 2^53, so it is exact and each edge is one correctly rounded division.
    let to_page = |out: u32, page: u32, full: u32| out as f64 * page as f64 / full as f64;
    let crop = PageCrop {
        left: to_page(out_x, page_w, full_w),
        top: to_page(out_y, page_h, full_h),
        width: to_page(out_w, page_w, full_w),
        height: to_page(out_h, page_h, full_h),
    };
    render_region_with_budget(
        src,
        page_w,
        page_h,
        crop,
        out_w,
        out_h,
        dst,
        dst_stride,
        BAND_BUDGET_BYTES,
    )
}

/// Render the page-space `crop` of `src` into `out_w x out_h` RGBA8888 rows of `dst`. The crop must lie inside
/// the page (callers validate it in their own integer coordinates first).
#[allow(clippy::too_many_arguments)]
fn render_region_with_budget(
    src: &DecodedImage,
    page_w: u32,
    page_h: u32,
    crop: PageCrop,
    out_w: u32,
    out_h: u32,
    dst: &mut [u8],
    dst_stride: usize,
    budget: usize,
) -> Result<()> {
    // ---- validate everything before allocating ----
    let row_bytes = validate_dst(out_w, out_h, dst.len(), dst_stride)?;
    if page_w == 0 || page_h == 0 || src.width == 0 || src.height == 0 {
        return Err(invalid("page and source sizes must be non-zero"));
    }
    let finite = [crop.left, crop.top, crop.width, crop.height]
        .iter()
        .all(|v| v.is_finite());
    if !finite || crop.left < 0.0 || crop.top < 0.0 || crop.width <= 0.0 || crop.height <= 0.0 {
        return Err(invalid("crop is empty or not finite"));
    }
    if crop.left >= page_w as f64 || crop.top >= page_h as f64 {
        return Err(invalid("crop is outside the page"));
    }
    let channels = src.layout.channels() as usize;
    let src_row = (src.width as usize)
        .checked_mul(channels)
        .ok_or_else(|| invalid("source row size overflows"))?;
    let src_len = src_row
        .checked_mul(src.height as usize)
        .ok_or_else(|| invalid("source size overflows"))?;
    if src.pixels.len() < src_len {
        return Err(invalid(
            "source pixel buffer is shorter than its dimensions",
        ));
    }

    // ---- identity: whole page at source size, no resampling ----
    let whole_page = crop.left == 0.0
        && crop.top == 0.0
        && crop.width == page_w as f64
        && crop.height == page_h as f64;
    if whole_page && out_w == src.width && out_h == src.height {
        for (y, row) in dst.chunks_mut(dst_stride).take(out_h as usize).enumerate() {
            let s = &src.pixels[y * src_row..(y + 1) * src_row];
            write_row_u8(src.layout, s, &mut row[..row_bytes]);
        }
        return Ok(());
    }

    // ---- crop in source coordinates (sub-pixel) ----
    let sx = src.width as f64 / page_w as f64;
    let sy = src.height as f64 / page_h as f64;
    let left = crop.left * sx;
    let top = crop.top * sy;
    let crop_w = (crop.width * sx).min(src.width as f64 - left);
    let crop_h = (crop.height * sy).min(src.height as f64 - top);
    // Source columns to keep: the crop plus the Lanczos margin on each side (clamped to the source), so edge
    // output pixels see the real neighbours and adjacent regions agree at their seam.
    let margin_x = band_margin(crop_w / out_w as f64);
    let margin_y = band_margin(crop_h / out_h as f64);
    let ex0 = ((left - margin_x as f64).floor().max(0.0) as u32).min(src.width - 1);
    let ex1 = (((left + crop_w) + margin_x as f64)
        .ceil()
        .min(src.width as f64) as u32)
        .clamp(ex0 + 1, src.width);
    let plan = BandPlan {
        left,
        top,
        crop_w,
        crop_h,
        ex0,
        ex1,
        margin_y,
    };
    let rows = band_rows(
        ex1 - ex0,
        out_w,
        channels,
        crop_h / out_h as f64,
        margin_y,
        out_h,
        budget,
    );

    let to_lin = to_linear_table();
    let to_srgb = to_srgb_table();
    match src.layout {
        PixelLayout::Gray8 => render_bands::<U16, _, _>(
            src,
            &plan,
            out_w,
            out_h,
            rows,
            dst,
            dst_stride,
            |s, o| {
                for (o, &v) in o.iter_mut().zip(s) {
                    *o = U16::new(to_lin[v as usize]);
                }
            },
            |p, row| {
                for (px, p) in row.as_chunks_mut::<4>().0.iter_mut().zip(p) {
                    let v = to_srgb[p.0 as usize];
                    px.copy_from_slice(&[v, v, v, 255]);
                }
            },
        ),
        PixelLayout::Rgb8 => render_bands::<U16x3, _, _>(
            src,
            &plan,
            out_w,
            out_h,
            rows,
            dst,
            dst_stride,
            |s, o| {
                for (o, p) in o.iter_mut().zip(s.as_chunks::<3>().0.iter()) {
                    *o = U16x3::new([
                        to_lin[p[0] as usize],
                        to_lin[p[1] as usize],
                        to_lin[p[2] as usize],
                    ]);
                }
            },
            |p, row| {
                for (px, p) in row.as_chunks_mut::<4>().0.iter_mut().zip(p) {
                    px.copy_from_slice(&[
                        to_srgb[p.0[0] as usize],
                        to_srgb[p.0[1] as usize],
                        to_srgb[p.0[2] as usize],
                        255,
                    ]);
                }
            },
        ),
    }
}

/// Expand one row of 8-bit pixels (gray or RGB) to opaque RGBA.
fn write_row_u8(layout: PixelLayout, src: &[u8], dst: &mut [u8]) {
    match layout {
        PixelLayout::Gray8 => {
            for (px, &v) in dst.as_chunks_mut::<4>().0.iter_mut().zip(src) {
                px.copy_from_slice(&[v, v, v, 255]);
            }
        }
        PixelLayout::Rgb8 => {
            for (px, p) in dst
                .as_chunks_mut::<4>()
                .0
                .iter_mut()
                .zip(src.as_chunks::<3>().0.iter())
            {
                px.copy_from_slice(&[p[0], p[1], p[2], 255]);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::decode::PixelLayout;
    use crate::error::EngineError;

    fn gray(width: u32, height: u32, f: impl Fn(u32, u32) -> u8) -> DecodedImage {
        let mut pixels = Vec::with_capacity((width * height) as usize);
        for y in 0..height {
            for x in 0..width {
                pixels.push(f(x, y));
            }
        }
        DecodedImage {
            width,
            height,
            layout: PixelLayout::Gray8,
            pixels,
            denom: 1,
        }
    }

    fn full(w: u32, h: u32) -> Rect {
        Rect {
            x: 0,
            y: 0,
            width: w,
            height: h,
        }
    }

    fn render(src: &DecodedImage, region: Rect, out_w: u32, out_h: u32) -> Vec<u8> {
        let mut dst = vec![0u8; (out_w * out_h * 4) as usize];
        render_region(
            src,
            src.width,
            src.height,
            region,
            out_w,
            out_h,
            &mut dst,
            (out_w * 4) as usize,
        )
        .unwrap();
        dst
    }

    #[test]
    fn choose_denom_table() {
        let r = Rect {
            x: 0,
            y: 0,
            width: 1600,
            height: 2400,
        };
        let all = [1u8, 2, 4, 8];
        assert_eq!(choose_denom(r, 1080, 1620, &all), 1);
        assert_eq!(choose_denom(r, 400, 600, &all), 4);
        assert_eq!(choose_denom(r, 300, 450, &all), 4);
        assert_eq!(choose_denom(r, 1080, 1620, &[1]), 1);
        assert_eq!(choose_denom(r, 3200, 4800, &all), 1);
    }

    #[test]
    fn linear_light_checkerboard_downscale() {
        let src = gray(64, 64, |x, y| if (x + y) % 2 == 0 { 0 } else { 255 });
        let out = render(&src, full(64, 64), 32, 32);
        let (lo, hi) = out
            .as_chunks::<4>()
            .0
            .iter()
            .fold((255u8, 0u8), |(l, h), p| (l.min(p[0]), h.max(p[0])));
        println!("checkerboard R range {lo}..={hi}");
        for px in out.as_chunks::<4>().0.iter() {
            assert!((185..=190).contains(&px[0]), "R = {}", px[0]);
            assert_eq!(px[3], 255);
        }
    }

    #[test]
    fn uniform_gray_stays_uniform() {
        let src = gray(37, 53, |_, _| 128);
        for (w, h) in [(20, 11), (80, 120)] {
            let out = render(&src, full(37, 53), w, h);
            let dev = out
                .as_chunks::<4>()
                .0
                .iter()
                .flat_map(|p| p[..3].iter())
                .map(|&c| (c as i32 - 128).abs())
                .max();
            println!("uniform gray {w}x{h} max error {dev:?}");
            for px in out.as_chunks::<4>().0.iter() {
                for c in &px[..3] {
                    assert!((127..=129).contains(c), "{w}x{h}: {c}");
                }
                assert_eq!(px[3], 255);
            }
        }
    }

    #[test]
    fn halftone_downscale_suppresses_moire() {
        let img = gen_corpus::halftone_page(1600, 2400, 2.0, 45.0);
        let src = DecodedImage {
            width: 1600,
            height: 2400,
            layout: PixelLayout::Gray8,
            pixels: img.clone().into_raw(),
            denom: 1,
        };
        let region = Rect {
            x: 400,
            y: 800,
            width: 800,
            height: 800,
        };
        let out = render(&src, region, 200, 200);
        let lanczos: Vec<f64> = out.as_chunks::<4>().0.iter().map(|p| p[0] as f64).collect();
        let mut nearest = Vec::new();
        for y in 0..200u32 {
            for x in 0..200u32 {
                nearest.push(img.get_pixel(400 + x * 4 + 2, 800 + y * 4 + 2).0[0] as f64);
            }
        }
        let std_dev = |v: &[f64]| {
            let mean = v.iter().sum::<f64>() / v.len() as f64;
            (v.iter().map(|x| (x - mean).powi(2)).sum::<f64>() / v.len() as f64).sqrt()
        };
        let (a, b) = (std_dev(&lanczos), std_dev(&nearest));
        println!("lanczos std {a:.3}, nearest std {b:.3}, ratio {:.4}", a / b);
        assert!(a < 0.25 * b, "lanczos std {a}, nearest std {b}");
    }

    #[test]
    fn crop_selects_region() {
        let src = gray(100, 100, |x, _| if x < 50 { 0 } else { 255 });
        let region = Rect {
            x: 50,
            y: 0,
            width: 50,
            height: 100,
        };
        let out = render(&src, region, 25, 50);
        for row in out.as_chunks::<100>().0.iter() {
            for px in row.as_chunks::<4>().0.iter().skip(2) {
                assert!(px[0] >= 250, "R = {}", px[0]);
            }
        }
    }

    #[test]
    fn decimated_source_maps_page_coords() {
        let src = DecodedImage {
            width: 50,
            height: 50,
            layout: PixelLayout::Gray8,
            pixels: (0..2500u32).map(|i| (i * 7 % 251) as u8).collect(),
            denom: 2,
        };
        let mut dst = vec![0u8; 50 * 50 * 4];
        render_region(&src, 100, 100, full(100, 100), 50, 50, &mut dst, 200).unwrap();
        for (i, px) in dst.as_chunks::<4>().0.iter().enumerate() {
            let v = src.pixels[i];
            assert_eq!(*px, [v, v, v, 255], "pixel {i}");
        }
    }

    #[test]
    fn rgb_source_outputs_opaque_rgba() {
        let src = DecodedImage {
            width: 16,
            height: 16,
            layout: PixelLayout::Rgb8,
            pixels: [10u8, 200, 30].repeat(256),
            denom: 1,
        };
        let out = render(&src, full(16, 16), 5, 7);
        for px in out.as_chunks::<4>().0.iter() {
            assert!((9..=11).contains(&px[0]), "R = {}", px[0]);
            assert!((199..=201).contains(&px[1]), "G = {}", px[1]);
            assert!((29..=31).contains(&px[2]), "B = {}", px[2]);
            assert_eq!(px[3], 255);
        }
    }

    #[test]
    fn stride_padding_untouched() {
        let src = gray(40, 40, |x, y| (x * 5 + y) as u8);
        let (out_w, out_h) = (10usize, 12usize);
        let stride = out_w * 4 + 16;
        let mut dst = vec![0xABu8; stride * out_h];
        render_region(&src, 40, 40, full(40, 40), 10, 12, &mut dst, stride).unwrap();
        for row in dst.chunks_exact(stride) {
            assert!(row[out_w * 4..].iter().all(|&b| b == 0xAB));
            assert!(row[..out_w * 4]
                .as_chunks::<4>()
                .0
                .iter()
                .all(|p| p[3] == 255));
        }
    }

    #[test]
    fn bad_requests_are_invalid() {
        let src = gray(8, 8, |_, _| 1);
        let is_invalid = |r: Result<()>| matches!(r, Err(EngineError::InvalidRequest { .. }));
        let mut dst = vec![0u8; 4 * 4 * 4];
        assert!(is_invalid(render_region(
            &src,
            8,
            8,
            full(8, 8),
            0,
            4,
            &mut dst,
            16
        )));
        assert!(is_invalid(render_region(
            &src,
            8,
            8,
            full(8, 8),
            4,
            0,
            &mut dst,
            16
        )));
        let mut short = vec![0u8; 4 * 4 * 4 - 1];
        assert!(is_invalid(render_region(
            &src,
            8,
            8,
            full(8, 8),
            4,
            4,
            &mut short,
            16
        )));
        assert!(is_invalid(render_region(
            &src,
            8,
            8,
            full(8, 8),
            4,
            4,
            &mut dst,
            15
        )));
        // region outside the page / empty
        let outside = Rect {
            x: 4,
            y: 0,
            width: 8,
            height: 8,
        };
        assert!(is_invalid(render_region(
            &src, 8, 8, outside, 4, 4, &mut dst, 16
        )));
        let empty = Rect {
            x: 0,
            y: 0,
            width: 0,
            height: 8,
        };
        assert!(is_invalid(render_region(
            &src, 8, 8, empty, 4, 4, &mut dst, 16
        )));
        // overflowing arithmetic must not panic or allocate
        let huge = Rect {
            x: u32::MAX,
            y: 0,
            width: u32::MAX,
            height: 1,
        };
        assert!(is_invalid(render_region(
            &src, 8, 8, huge, 4, 4, &mut dst, 16
        )));
        assert!(is_invalid(render_region(
            &src,
            8,
            8,
            full(8, 8),
            4,
            4,
            &mut dst,
            usize::MAX
        )));
    }

    fn noise_value(x: u32, y: u32, salt: u32) -> u8 {
        let mut h = x
            .wrapping_mul(0x9E37_79B1)
            .wrapping_add(y.wrapping_mul(0x85EB_CA6B))
            .wrapping_add(salt.wrapping_mul(0xC2B2_AE35));
        h ^= h >> 15;
        h = h.wrapping_mul(0x2C1B_3C6D);
        h ^= h >> 12;
        // thin lines plus noise: content-rich at every scale
        let line = if x.is_multiple_of(7) || y.is_multiple_of(5) {
            255
        } else {
            0
        };
        ((h >> 8) as u8 / 2).saturating_add(line / 2)
    }

    fn noise_gray(w: u32, h: u32) -> DecodedImage {
        gray(w, h, |x, y| noise_value(x, y, 0))
    }

    fn noise_rgb(w: u32, h: u32) -> DecodedImage {
        let mut pixels = Vec::new();
        for y in 0..h {
            for x in 0..w {
                for c in 0..3 {
                    pixels.push(noise_value(x, y, c + 1));
                }
            }
        }
        DecodedImage {
            width: w,
            height: h,
            layout: PixelLayout::Rgb8,
            pixels,
            denom: 1,
        }
    }

    fn assert_close(a: &[u8], b: &[u8], what: &str) {
        assert_eq!(a.len(), b.len(), "{what}: length");
        let worst = a
            .iter()
            .zip(b)
            .map(|(&x, &y)| (x as i32 - y as i32).abs())
            .max()
            .unwrap_or(0);
        println!("{what}: max channel difference {worst}");
        assert!(worst <= 1, "{what}: max channel difference {worst}");
    }

    /// Two adjacent regions must equal the matching halves of one wider render (same scale), so
    /// the filter margin outside each region is really used.
    fn check_seam(
        src: &DecodedImage,
        region_w: u32,
        region_h: u32,
        out_w: u32,
        out_h: u32,
        what: &str,
    ) {
        let (x0, y0) = (100, 50);
        let a = Rect {
            x: x0,
            y: y0,
            width: region_w,
            height: region_h,
        };
        let b = Rect {
            x: x0 + region_w,
            y: y0,
            width: region_w,
            height: region_h,
        };
        let wide = Rect {
            x: x0,
            y: y0,
            width: region_w * 2,
            height: region_h,
        };
        let (ra, rb) = (render(src, a, out_w, out_h), render(src, b, out_w, out_h));
        let rw = render(src, wide, out_w * 2, out_h);
        let stride = (out_w * 4) as usize;
        let wide_stride = stride * 2;
        let mut left = Vec::new();
        let mut right = Vec::new();
        for y in 0..out_h as usize {
            left.extend_from_slice(&rw[y * wide_stride..y * wide_stride + stride]);
            right.extend_from_slice(&rw[y * wide_stride + stride..(y + 1) * wide_stride]);
        }
        assert_close(&ra, &left, &format!("{what} left tile"));
        assert_close(&rb, &right, &format!("{what} right tile"));
    }

    #[test]
    fn adjacent_regions_match_wider_render_downscale() {
        // 100x200 source px -> 40x80: 2.5 source px per output px.
        check_seam(&noise_gray(400, 300), 100, 200, 40, 80, "gray downscale");
        check_seam(&noise_rgb(400, 300), 100, 200, 40, 80, "rgb downscale");
    }

    #[test]
    fn adjacent_regions_match_wider_render_upscale() {
        // 20x20 source px -> 50x50: 0.4 source px per output px.
        check_seam(&noise_gray(400, 300), 20, 20, 50, 50, "gray upscale");
        check_seam(&noise_rgb(400, 300), 20, 20, 50, 50, "rgb upscale");
    }

    fn render_budget(
        src: &DecodedImage,
        region: Rect,
        out_w: u32,
        out_h: u32,
        budget: usize,
    ) -> Vec<u8> {
        let mut dst = vec![0u8; (out_w * out_h * 4) as usize];
        render_region_with_budget(
            src,
            src.width,
            src.height,
            PageCrop::of_rect(region),
            out_w,
            out_h,
            &mut dst,
            (out_w * 4) as usize,
            budget,
        )
        .unwrap();
        dst
    }

    #[test]
    fn banded_output_matches_single_band_reference() {
        const HUGE: usize = 1 << 40;
        const TINY: usize = 6 * 1024;
        let cases: [(u32, u32); 5] = [(7, 5), (33, 77), (100, 3), (150, 200), (400, 300)];
        for (name, src) in [("gray", noise_gray(400, 300)), ("rgb", noise_rgb(400, 300))] {
            let region = Rect {
                x: 13,
                y: 21,
                width: 361,
                height: 250,
            };
            for (out_w, out_h) in cases {
                let reference = render_budget(&src, region, out_w, out_h, HUGE);
                let banded = render_budget(&src, region, out_w, out_h, TINY);
                assert_close(&reference, &banded, &format!("{name} {out_w}x{out_h}"));
            }
            // the tiny budget really forces several bands, the huge one is a single band
            let scale = 250.0 / 200.0;
            let channels = if name == "gray" { 1 } else { 3 };
            assert!(band_rows(400, 150, channels, scale, band_margin(scale), 200, TINY) < 200);
            assert_eq!(
                band_rows(400, 150, channels, scale, band_margin(scale), 200, HUGE),
                200
            );
            // an out_h smaller than one band is a single band
            assert_eq!(
                band_rows(
                    400,
                    100,
                    channels,
                    250.0 / 3.0,
                    band_margin(250.0 / 3.0),
                    3,
                    BAND_BUDGET_BYTES
                ),
                3
            );
        }
    }

    /// `out_w x out_h` tile at `(x, y)` of `src` scaled (as a whole page) to `full_w x full_h`.
    fn render_scaled(
        src: &DecodedImage,
        full_w: u32,
        full_h: u32,
        (x, y, w, h): (u32, u32, u32, u32),
    ) -> Vec<u8> {
        let mut dst = vec![0u8; (w * h * 4) as usize];
        render_scaled_region(
            src,
            src.width,
            src.height,
            full_w,
            full_h,
            x,
            y,
            w,
            h,
            &mut dst,
            (w * 4) as usize,
        )
        .unwrap();
        dst
    }

    /// Rows `y..y+h`, columns `x..x+w` of a tightly packed `full_w`-wide RGBA buffer.
    fn sub_rect(full: &[u8], full_w: u32, (x, y, w, h): (u32, u32, u32, u32)) -> Vec<u8> {
        let stride = full_w as usize * 4;
        let mut out = Vec::with_capacity((w * h * 4) as usize);
        for row in y as usize..(y + h) as usize {
            let start = row * stride + x as usize * 4;
            out.extend_from_slice(&full[start..start + w as usize * 4]);
        }
        out
    }

    /// Tiles every `tile_w x tile_h` cell of a `full_w x full_h` scaled render and checks each one equals
    /// the matching rows/cols of the one-shot render within 1 channel level (the f64 crop arithmetic of a
    /// tile and of the one-shot render round differently, which flips an isolated value by 1 at most). A
    /// row or column shift would differ by far more on this noise image.
    fn check_scaled_tiles(
        src: &DecodedImage,
        full_w: u32,
        full_h: u32,
        tile_w: u32,
        tile_h: u32,
        what: &str,
    ) {
        let whole = render_scaled(src, full_w, full_h, (0, 0, full_w, full_h));
        let mut differing = 0;
        for y in (0..full_h).step_by(tile_h as usize) {
            for x in (0..full_w).step_by(tile_w as usize) {
                let rect = (x, y, tile_w.min(full_w - x), tile_h.min(full_h - y));
                let tile = render_scaled(src, full_w, full_h, rect);
                let expected = sub_rect(&whole, full_w, rect);
                assert_close(&tile, &expected, &format!("{what} tile {rect:?}"));
                differing += tile.iter().zip(&expected).filter(|(a, b)| a != b).count();
            }
        }
        println!("{what}: {differing} channel values differ (by 1) from the one-shot render");
    }

    #[test]
    fn scaled_bands_match_full_render() {
        for (name, src) in [("gray", noise_gray(80, 2400)), ("rgb", noise_rgb(80, 2400))] {
            // 1000/1000/1000/240-row bands of a 108x3240 render (1.35x).
            check_scaled_tiles(&src, 108, 3240, 108, 1000, &format!("{name} bands"));
            // 2-D: 100x1000 tiles of a 216x6480 render (2.7x).
            check_scaled_tiles(&src, 216, 6480, 100, 1000, &format!("{name} 2-D tiles"));
        }
    }

    #[test]
    fn render_scaled_region_rejects_bad_requests() {
        let src = gray(8, 8, |_, _| 1);
        let mut dst = vec![0u8; 16 * 16 * 4];
        let mut call = |full_w: u32, full_h: u32, x: u32, y: u32, w: u32, h: u32| {
            let r = render_scaled_region(&src, 8, 8, full_w, full_h, x, y, w, h, &mut dst, 64);
            assert!(
                matches!(r, Err(EngineError::InvalidRequest { .. })),
                "full {full_w}x{full_h}, rect ({x},{y},{w},{h}): {r:?}"
            );
        };
        // out rect outside the full size
        call(16, 16, 8, 0, 9, 4);
        call(16, 16, 0, 13, 4, 4);
        call(16, 16, u32::MAX, 0, 2, 2);
        call(16, 16, 0, 16, 1, 1);
        // zero sizes
        call(16, 16, 0, 0, 0, 4);
        call(16, 16, 0, 0, 4, 0);
        call(0, 16, 0, 0, 1, 1);
        call(16, 0, 0, 0, 1, 1);
        // full size above the cap
        call(MAX_SCALED_FULL_DIM + 1, 16, 0, 0, 4, 4);
        call(16, MAX_SCALED_FULL_DIM + 1, 0, 0, 4, 4);
        // a valid request still succeeds
        render_scaled_region(&src, 8, 8, 16, 16, 4, 4, 8, 8, &mut dst, 64).unwrap();
    }

    #[test]
    fn band_allocation_never_exceeds_budget() {
        // 100 MP (10000x10000) page at full page into a 1080x1080 output.
        for channels in [1usize, 3] {
            let scale = 10_000.0 / 1080.0;
            let margin = band_margin(scale);
            let rows = band_rows(
                10_000,
                1080,
                channels,
                scale,
                margin,
                1080,
                BAND_BUDGET_BYTES,
            );
            assert!((1..1080).contains(&rows), "rows {rows}");
            let bytes = band_lin_bytes(10_000, channels, rows, scale, margin);
            println!(
                "100 MP {channels}-channel: {rows} output rows per band, {bytes} linear bytes"
            );
            assert!(bytes <= BAND_BUDGET_BYTES, "{bytes} > budget");
            // the band output buffer is bounded as well
            assert!(rows as usize * 1080 * 2 * channels <= BAND_BUDGET_BYTES);
        }
        // upscale: the output band, not the source, is what has to stay bounded
        let rows = band_rows(
            100,
            8000,
            3,
            0.01,
            band_margin(0.01),
            8000,
            BAND_BUDGET_BYTES,
        );
        assert!(rows as usize * 8000 * 6 <= BAND_BUDGET_BYTES);
        // never less than one row
        assert_eq!(
            band_rows(1_000_000, 1_000_000, 3, 50.0, band_margin(50.0), 10, 1024),
            1
        );
    }
}
