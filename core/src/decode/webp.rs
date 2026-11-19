//! WebP decoding with the pure-Rust `image-webp` crate. Output is RGB, alpha flattened onto white.
//! Animated files decode their first frame.

use std::io::Cursor;

use image_webp::{DecodingError, WebPDecoder};

use super::{alloc_zeroed, corrupt, plan, png::flatten_in_place, DecodedImage, PixelLayout};
use crate::error::{EngineError, Result};
use crate::limits::Limits;

pub(super) fn decode(bytes: &[u8], limits: &Limits) -> Result<DecodedImage> {
    let mut decoder = WebPDecoder::new(Cursor::new(bytes)).map_err(map_err)?;
    let (width, height) = decoder.dimensions();
    plan(width, height, 1, &[1], limits)?;

    // The decoder's own scratch allocations (some are not covered by this limit) scale with the output size.
    let memory_limit =
        usize::try_from(limits.max_image_pixels.saturating_mul(4)).unwrap_or(usize::MAX);
    decoder.set_memory_limit(memory_limit);

    let has_alpha = decoder.has_alpha();
    let len = decoder
        .output_buffer_size()
        .ok_or_else(|| corrupt("webp output size overflows"))?;
    let mut pixels = alloc_zeroed(len)?;
    decoder.read_image(&mut pixels).map_err(map_err)?;
    if has_alpha {
        flatten_in_place(&mut pixels, 4);
    }
    Ok(DecodedImage {
        width,
        height,
        layout: PixelLayout::Rgb8,
        pixels,
        denom: 1,
    })
}

fn map_err(e: DecodingError) -> EngineError {
    EngineError::Corrupt {
        message: format!("webp decode failed: {e}"),
    }
}
