//! PNG decoding with the pure-Rust `png` crate. Output is always 8-bit, gray or RGB, alpha flattened onto white.

use std::io::Cursor;

use ::png::{BitDepth, ColorType, Decoder, DecodingError, Transformations};

use super::{alloc_zeroed, buffer_len, corrupt, over_white, plan, DecodedImage, PixelLayout};
use crate::error::{EngineError, Result};
use crate::limits::Limits;

pub(super) fn decode(bytes: &[u8], limits: &Limits) -> Result<DecodedImage> {
    let mut decoder = Decoder::new(Cursor::new(bytes));
    // EXPAND: palette to RGB, sub-8-bit gray to 8-bit, tRNS to an alpha channel. STRIP_16: 16-bit to 8-bit.
    decoder.set_transformations(Transformations::EXPAND | Transformations::STRIP_16);
    let mut reader = decoder.read_info().map_err(map_err)?;

    let (width, height) = {
        let info = reader.info();
        (info.width, info.height)
    };
    plan(width, height, 1, &[1], limits)?;

    let (color, depth) = reader.output_color_type();
    if depth != BitDepth::Eight {
        return Err(corrupt("png output is not 8-bit after normalisation"));
    }
    let channels: u64 = match color {
        ColorType::Grayscale => 1,
        ColorType::GrayscaleAlpha => 2,
        ColorType::Rgb => 3,
        ColorType::Rgba => 4,
        ColorType::Indexed => return Err(corrupt("png palette was not expanded")),
    };
    let expected =
        buffer_len(width, height, channels).ok_or_else(|| corrupt("png output size overflows"))?;
    if reader.output_buffer_size() != Some(expected) {
        return Err(corrupt("png output size disagrees with its header"));
    }
    let mut pixels = alloc_zeroed(expected)?;
    reader.next_frame(&mut pixels).map_err(map_err)?;

    let layout = match color {
        ColorType::Grayscale => PixelLayout::Gray8,
        ColorType::GrayscaleAlpha => {
            flatten_in_place(&mut pixels, 2);
            PixelLayout::Gray8
        }
        ColorType::Rgb => PixelLayout::Rgb8,
        ColorType::Rgba => {
            flatten_in_place(&mut pixels, 4);
            PixelLayout::Rgb8
        }
        ColorType::Indexed => unreachable!("rejected above"),
    };
    Ok(DecodedImage {
        width,
        height,
        layout,
        pixels,
        denom: 1,
    })
}

/// Blend `[colour.., alpha]` pixels onto white, compacting the buffer in place (`stride` is 2 or 4).
pub(super) fn flatten_in_place(buf: &mut Vec<u8>, stride: usize) {
    let colour = stride - 1;
    let pixels = buf.len() / stride;
    for i in 0..pixels {
        let a = buf[i * stride + colour];
        for c in 0..colour {
            // The write index i * colour + c never passes the read index i * stride + c.
            buf[i * colour + c] = over_white(buf[i * stride + c], a);
        }
    }
    buf.truncate(pixels * colour);
    // Truncating keeps the old capacity; release it so memory accounting by `len` is honest.
    buf.shrink_to_fit();
}

fn map_err(e: DecodingError) -> EngineError {
    match e {
        DecodingError::LimitsExceeded => EngineError::LimitExceeded {
            limit: "png_decoder_memory".to_string(),
            value: 0,
            max: 0,
        },
        other => EngineError::Corrupt {
            message: format!("png decode failed: {other}"),
        },
    }
}
