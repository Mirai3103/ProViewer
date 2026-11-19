//! Fuzz entry logic shared by the libFuzzer targets and by `core/tests/fuzz_regressions.rs`
//! (included with `#[path]`), so a crash input replayed on the host runs exactly the code the fuzzer ran.
//!
//! Results are ignored on purpose: only crashes, OOM and timeouts count as findings.
#![allow(dead_code)] // each target uses one of the two entry points

use proviewer_core::archive::{PageSource, ZipSource};
use proviewer_core::decode::{decode, probe, sniff, supported_denoms};
use proviewer_core::limits::Limits;
use std::io::Cursor;

/// Fuzz limits: small enough that one input stays well inside `-malloc_limit_mb=512`.
pub const LIMITS: Limits = Limits {
    max_entries: 256,
    max_entry_bytes: 8 * 1024 * 1024,
    max_total_bytes: 64 * 1024 * 1024,
    max_image_pixels: 4_000_000,
    max_image_dimension: 16_384,
};

/// At most this many pages of one archive are exercised per input.
const MAX_PAGES: usize = 32;

pub fn run_archive(data: &[u8]) {
    let Ok(src) = ZipSource::from_reader(Cursor::new(data.to_vec()), LIMITS) else {
        return;
    };
    for i in 0..src.pages().len().min(MAX_PAGES) {
        let _ = src.read_prefix(i, 4096);
        if let Ok(bytes) = src.read(i) {
            let _ = probe(&bytes);
            let _ = decode(&bytes, 1, &LIMITS);
        }
    }
}

pub fn run_decode(data: &[u8]) {
    let _ = probe(data);
    let Ok(format) = sniff(data) else {
        return;
    };
    for &denom in supported_denoms(format) {
        let _ = decode(data, denom, &LIMITS);
    }
}
