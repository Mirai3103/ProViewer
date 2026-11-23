//! JPEG decoding through libjpeg-turbo's tj3 API (`turbojpeg::raw`), with DCT-scaled decode.
//!
//! This module is the only place that knows the backend (the seam for swapping it). The C library is not
//! memory-safe against hostile input and its aborts cannot be caught, so this module reads the header and checks
//! every limit before allocating, and hands turbojpeg a buffer we sized with checked arithmetic. turbojpeg never
//! allocates the output itself.

use std::ffi::CStr;

use turbojpeg::raw;

use super::{alloc_zeroed, buffer_len, corrupt, plan, scaled_dim, DecodedImage, PixelLayout};
use crate::error::{EngineError, Result};
use crate::limits::Limits;

/// Scales libjpeg-turbo decodes directly in the DCT (1/denom). Each one is exercised by a test.
pub(super) const SUPPORTED_DENOMS: &[u8] = &[1, 2, 4, 8];

/// Progressive JPEGs can declare huge numbers of scans to burn CPU. Real files use around ten.
const MAX_SCANS: u32 = 256;

/// Owns a `tjhandle` and destroys it on drop, so every error path stays leak-free.
struct Decompressor {
    handle: raw::tjhandle,
}

impl Decompressor {
    fn new() -> Result<Self> {
        let handle = unsafe { raw::tj3Init(raw::TJINIT_TJINIT_DECOMPRESS as i32) };
        if handle.is_null() {
            return Err(EngineError::Io {
                message: format!("jpeg decoder init failed: {}", error_str(std::ptr::null_mut())),
            });
        }
        Ok(Self { handle })
    }
}

impl Drop for Decompressor {
    fn drop(&mut self) {
        unsafe { raw::tj3Destroy(self.handle) };
    }
}

pub(super) fn decode(bytes: &[u8], denom: u8, limits: &Limits) -> Result<DecodedImage> {
    let tj = Decompressor::new()?;

    // Recoverable warnings must not abort the decode (owner decision D3), and progressive scans are capped.
    set_param(tj.handle, raw::TJPARAM_TJPARAM_STOPONWARNING as i32, 0)?;
    set_param(tj.handle, raw::TJPARAM_TJPARAM_SCANLIMIT as i32, MAX_SCANS as i32)?;

    let size = raw::size_t::try_from(bytes.len()).map_err(|_| corrupt("jpeg input is too large"))?;
    // A warning here (e.g. extraneous bytes before the first scan) still leaves the dimensions and
    // colorspace valid, so it counts as success; only a fatal error is `Corrupt`.
    if unsafe { raw::tj3DecompressHeader(tj.handle, bytes.as_ptr(), size) } != 0 {
        check_warning(tj.handle)?;
    }

    let header_w = get_param(tj.handle, raw::TJPARAM_TJPARAM_JPEGWIDTH as i32)?;
    let header_h = get_param(tj.handle, raw::TJPARAM_TJPARAM_JPEGHEIGHT as i32)?;
    if get_param(tj.handle, raw::TJPARAM_TJPARAM_LOSSLESS as i32)? != 0 {
        return Err(EngineError::UnsupportedFormat {
            message: "lossless JPEG is not supported".to_string(),
        });
    }
    let is_progressive = get_param(tj.handle, raw::TJPARAM_TJPARAM_PROGRESSIVE as i32)? != 0;

    let width = u32::try_from(header_w).map_err(|_| corrupt("jpeg width does not fit in u32"))?;
    let height = u32::try_from(header_h).map_err(|_| corrupt("jpeg height does not fit in u32"))?;
    // The size turbojpeg reports is the one it will decode, so limits are enforced on it, not on the probe.
    let denom = plan(width, height, denom, SUPPORTED_DENOMS, limits)?;
    if is_progressive || !is_single_interleaved_scan(bytes) {
        // libjpeg allocates and zeroes a full-size DCT coefficient array (about 2 * w * h * components bytes) for any
        // multi-scan file whatever the denom: progressive (SOF2), and also sequential SOF0/SOF1 files whose first
        // scan does not carry every component (non-interleaved, so several scans). So the SOURCE size must fit the
        // pixel limit too. If the marker structure cannot be read, the check applies (the safe direction).
        let source_pixels = u64::from(width) * u64::from(height);
        if source_pixels > limits.max_image_pixels {
            return Err(EngineError::LimitExceeded {
                limit: "image_pixels".to_string(),
                value: source_pixels,
                max: limits.max_image_pixels,
            });
        }
    }

    set_scaling_factor(tj.handle, denom)?;
    let (out_w, out_h) = (scaled_dim(width, denom), scaled_dim(height, denom));

    let colorspace = get_param(tj.handle, raw::TJPARAM_TJPARAM_COLORSPACE as i32)?;
    // The bindgen `TJPF_*` constants are `i32` on MSVC and `u32` on Linux/Android, so the casts are only
    // redundant on some targets.
    #[allow(clippy::unnecessary_cast)]
    let (format, layout, cmyk, channels) = match colorspace as u32 {
        raw::TJCS_TJCS_GRAY => (raw::TJPF_TJPF_GRAY as i32, PixelLayout::Gray8, false, 1u64),
        // libjpeg cannot convert CMYK/YCCK to RGB itself, so it is decoded as CMYK and converted below.
        raw::TJCS_TJCS_CMYK | raw::TJCS_TJCS_YCCK => {
            (raw::TJPF_TJPF_CMYK as i32, PixelLayout::Rgb8, true, 4u64)
        }
        _ => (raw::TJPF_TJPF_RGB as i32, PixelLayout::Rgb8, false, 3u64),
    };
    let len = buffer_len(out_w, out_h, channels)
        .ok_or_else(|| corrupt("jpeg output size overflows"))?;
    let mut pixels = alloc_zeroed(len)?;
    let pitch = i32::try_from(u64::from(out_w) * channels)
        .map_err(|_| corrupt("jpeg output pitch overflows"))?;

    let r = unsafe {
        raw::tj3Decompress8(tj.handle, bytes.as_ptr(), size, pixels.as_mut_ptr(), pitch, format)
    };
    if r != 0 {
        // A warning means libjpeg decoded what it could and padded the rest; keep the buffer.
        check_warning(tj.handle)?;
    }

    if cmyk {
        cmyk_to_rgb_in_place(&mut pixels, has_adobe_marker(bytes));
    }
    Ok(DecodedImage {
        width: out_w,
        height: out_h,
        layout,
        pixels,
        denom,
    })
}

/// The turbojpeg error string for `handle`, or a fallback if none is available.
fn error_str(handle: raw::tjhandle) -> String {
    let ptr = unsafe { raw::tj3GetErrorStr(handle) };
    if ptr.is_null() {
        "unknown turbojpeg error".to_string()
    } else {
        unsafe { CStr::from_ptr(ptr) }.to_string_lossy().into_owned()
    }
}

fn set_param(handle: raw::tjhandle, param: i32, value: i32) -> Result<()> {
    if unsafe { raw::tj3Set(handle, param, value) } != 0 {
        Err(EngineError::Corrupt {
            message: format!("jpeg decode failed: {}", error_str(handle)),
        })
    } else {
        Ok(())
    }
}

fn get_param(handle: raw::tjhandle, param: i32) -> Result<i32> {
    let v = unsafe { raw::tj3Get(handle, param) };
    if v < 0 {
        Err(EngineError::Corrupt {
            message: format!("jpeg decode failed: {}", error_str(handle)),
        })
    } else {
        Ok(v)
    }
}

fn set_scaling_factor(handle: raw::tjhandle, denom: u8) -> Result<()> {
    let r = unsafe {
        raw::tj3SetScalingFactor(
            handle,
            raw::tjscalingfactor {
                num: 1,
                denom: i32::from(denom),
            },
        )
    };
    if r != 0 {
        Err(EngineError::Corrupt {
            message: format!("jpeg decode failed: {}", error_str(handle)),
        })
    } else {
        Ok(())
    }
}

/// A `-1` return is tolerable when the last error was a warning (libjpeg already filled what it
/// could); a fatal error is `Corrupt` with the turbojpeg message.
fn check_warning(handle: raw::tjhandle) -> Result<()> {
    if unsafe { raw::tj3GetErrorCode(handle) } == raw::TJERR_TJERR_WARNING as i32 {
        Ok(())
    } else {
        Err(EngineError::Corrupt {
            message: format!("jpeg decode failed: {}", error_str(handle)),
        })
    }
}

/// Convert packed CMYK (4 bytes per pixel) to packed RGB (3 bytes per pixel) in the same buffer, then shrink it.
///
/// Adobe CMYK JPEGs store inverted ink values (255 = no ink), so `R = C * K / 255` on the stored values.
/// Files without an Adobe marker are taken as plain ink amounts: `R = (255 - C) * (255 - K) / 255`.
fn cmyk_to_rgb_in_place(buf: &mut Vec<u8>, adobe_inverted: bool) {
    let pixels = buf.len() / 4;
    for i in 0..pixels {
        let [c, m, y, k] = [buf[4 * i], buf[4 * i + 1], buf[4 * i + 2], buf[4 * i + 3]];
        let (c, m, y, k) = if adobe_inverted {
            (c, m, y, k)
        } else {
            (255 - c, 255 - m, 255 - y, 255 - k)
        };
        // The write index 3 * i never passes the read index 4 * i, so no unread pixel is overwritten.
        buf[3 * i] = mul255(c, k);
        buf[3 * i + 1] = mul255(m, k);
        buf[3 * i + 2] = mul255(y, k);
    }
    buf.truncate(pixels * 3);
    // Truncating keeps the old capacity (4/3 of the length); release it so accounting by `len` is honest.
    buf.shrink_to_fit();
}

#[inline]
fn mul255(a: u8, b: u8) -> u8 {
    ((u32::from(a) * u32::from(b) + 127) / 255) as u8
}

/// Walk the marker segments up to and including the first SOS, calling `visit(marker, payload)` for each one
/// (the payload excludes the two length bytes). The walk also ends at EOI, at anything malformed, or when `visit`
/// returns true.
fn walk_segments<'a>(bytes: &'a [u8], mut visit: impl FnMut(u8, &'a [u8]) -> bool) {
    let mut i = 2; // after SOI
    while i + 4 <= bytes.len() {
        if bytes[i] != 0xFF {
            return;
        }
        let marker = bytes[i + 1];
        match marker {
            0xFF => i += 1, // fill byte
            0x00 | 0x01 | 0xD0..=0xD8 => i += 2,
            0xD9 => return, // end of image
            _ => {
                let len = usize::from(u16::from_be_bytes([bytes[i + 2], bytes[i + 3]]));
                if len < 2 {
                    return;
                }
                let end = (i + 2 + len).min(bytes.len());
                if visit(marker, &bytes[(i + 4).min(end)..end]) || marker == 0xDA {
                    return;
                }
                i += 2 + len;
            }
        }
    }
}

/// True if an Adobe APP14 segment appears before the first scan.
fn has_adobe_marker(bytes: &[u8]) -> bool {
    let mut found = false;
    walk_segments(bytes, |marker, payload| {
        found = marker == 0xEE && payload.starts_with(b"Adobe");
        found
    });
    found
}

/// True if the first scan (SOS) carries every component of the frame (SOF), so a sequential file is one interleaved
/// scan and libjpeg decodes it row by row. False when the file has several scans, or when either marker is missing.
fn is_single_interleaved_scan(bytes: &[u8]) -> bool {
    let (mut frame_components, mut scan_components) = (None, None);
    walk_segments(bytes, |marker, payload| {
        match marker {
            // SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC). Payload: precision, height, width, component count.
            0xC0..=0xCF if !matches!(marker, 0xC4 | 0xC8 | 0xCC) => {
                frame_components = frame_components.or(payload.get(5).copied());
            }
            0xDA => scan_components = payload.first().copied(),
            _ => {}
        }
        false
    });
    matches!((frame_components, scan_components), (Some(nf), Some(ns)) if nf == ns)
}
