//! Shared fixture for integration tests.

use std::path::Path;
use std::sync::OnceLock;
use tempfile::TempDir;

static CORPUS: OnceLock<(TempDir, std::path::PathBuf)> = OnceLock::new();

/// Directory holding the corpus archives generated at 5% pixel scale (names and entry counts
/// match the full corpus). Generated once per test binary and kept alive until the process exits.
#[allow(dead_code)] // each test binary uses a different subset of this module
pub fn small_corpus() -> &'static Path {
    let (_, path) = CORPUS.get_or_init(|| {
        let dir = tempfile::tempdir().expect("create temp dir");
        let out = dir.path().join("corpus");
        gen_corpus::generate_with_scale(&out, 0.05).expect("generate small corpus");
        (dir, out)
    });
    path
}
