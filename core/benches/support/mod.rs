//! Helpers for `engine.rs`: where the corpus lives and the sanity checks run in bench setup.
//! Kept in a module (not a bench target) so `core/tests/bench_support.rs` can test it without criterion.

use std::ffi::OsString;
use std::path::{Path, PathBuf};

/// What a bench prints when the corpus directory is missing.
pub const CORPUS_HINT: &str = "run: cargo run --release -p gen-corpus -- corpus/generated";

/// `$PV_CORPUS` (an empty value counts as unset), else `<manifest_dir>/../corpus/generated`.
/// `scripts/device-run.ps1` sets `PV_CORPUS` to the directory it pushes the corpus files to.
pub fn corpus_dir_from(pv_corpus: Option<OsString>, manifest_dir: &Path) -> PathBuf {
    match pv_corpus.filter(|v| !v.is_empty()) {
        Some(dir) => PathBuf::from(dir),
        None => manifest_dir.join("../corpus/generated"),
    }
}

/// `Err(CORPUS_HINT)` unless `dir` is an existing directory.
pub fn check_corpus_dir(dir: &Path) -> Result<(), String> {
    if dir.is_dir() {
        Ok(())
    } else {
        Err(CORPUS_HINT.to_string())
    }
}

/// The corpus directory for this process. Panics with exactly [`CORPUS_HINT`] if it does not exist.
pub fn corpus_dir() -> PathBuf {
    let dir = corpus_dir_from(
        std::env::var_os("PV_CORPUS"),
        Path::new(env!("CARGO_MANIFEST_DIR")),
    );
    if let Err(hint) = check_corpus_dir(&dir) {
        eprintln!("corpus directory not found: {}", dir.display());
        panic!("{hint}");
    }
    dir
}

/// Path of one corpus archive. Panics if the file is missing (on a device it must have been pushed).
pub fn corpus_file(name: &str) -> PathBuf {
    let path = corpus_dir().join(name);
    assert!(
        path.is_file(),
        "corpus file {} not found (push it with device-run -Push, or regenerate: {CORPUS_HINT})",
        path.display()
    );
    path
}

/// Fails loudly when a buffer that should hold real image data is empty or a single repeated byte,
/// so a bench that renders nothing cannot produce a (very fast) number.
pub fn assert_varied(bytes: &[u8], what: &str) {
    let Some(&first) = bytes.first() else {
        panic!("{what}: buffer is empty");
    };
    assert!(
        bytes.iter().any(|&b| b != first),
        "{what}: every byte is {first}"
    );
}
