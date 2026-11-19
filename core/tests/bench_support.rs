//! Tests for the helpers of `benches/engine.rs` (corpus directory resolution and the setup sanity checks).
//! The bench harness itself is not a test target, so the helper module is compiled into this test binary too.

#[allow(dead_code)] // the bench uses parts of the module that no test calls
#[path = "../benches/support/mod.rs"]
mod support;

use std::ffi::OsString;
use std::path::{Path, PathBuf};
use support::{assert_varied, check_corpus_dir, corpus_dir_from, CORPUS_HINT};

#[test]
fn pv_corpus_wins_over_the_manifest_default() {
    let dir = corpus_dir_from(
        Some(OsString::from("/data/local/tmp/pv/corpus")),
        Path::new("/repo/core"),
    );
    assert_eq!(dir, PathBuf::from("/data/local/tmp/pv/corpus"));
}

#[test]
fn default_is_the_generated_corpus_next_to_the_crate() {
    let dir = corpus_dir_from(None, Path::new("/repo/core"));
    assert_eq!(dir, Path::new("/repo/core").join("../corpus/generated"));
}

#[test]
fn an_empty_pv_corpus_counts_as_unset() {
    let dir = corpus_dir_from(Some(OsString::new()), Path::new("/repo/core"));
    assert_eq!(dir, Path::new("/repo/core").join("../corpus/generated"));
}

#[test]
fn missing_corpus_dir_reports_the_exact_generate_command() {
    assert_eq!(
        CORPUS_HINT,
        "run: cargo run --release -p gen-corpus -- corpus/generated"
    );
    let missing = std::env::temp_dir().join("proviewer-no-such-corpus-dir-0f3a");
    assert_eq!(check_corpus_dir(&missing), Err(CORPUS_HINT.to_string()));
}

#[test]
fn existing_corpus_dir_is_accepted() {
    let dir = tempfile::tempdir().unwrap();
    assert_eq!(check_corpus_dir(dir.path()), Ok(()));
}

#[test]
fn a_file_is_not_a_corpus_dir() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("x");
    std::fs::write(&file, b"x").unwrap();
    assert_eq!(check_corpus_dir(&file), Err(CORPUS_HINT.to_string()));
}

#[test]
fn varied_buffer_passes() {
    assert_varied(&[0, 255, 0, 17], "two-tone");
}

#[test]
#[should_panic(expected = "every byte is 0")]
fn all_zero_buffer_fails_loudly() {
    assert_varied(&[0; 64], "all-zero");
}

#[test]
#[should_panic(expected = "buffer is empty")]
fn empty_buffer_fails_loudly() {
    assert_varied(&[], "empty");
}
