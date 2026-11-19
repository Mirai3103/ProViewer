//! Replays fuzzing inputs on the host. Every file in `tests/fixtures/fuzz/` is a minimized crash input found by
//! `scripts/fuzz.ps1`; it goes through the same code the fuzz targets run (`core/fuzz/harness.rs`) and must
//! return without panicking. The seed tests prove that code is wired up and does real work on valid input.

#[path = "../fuzz/harness.rs"]
mod harness;

use std::path::{Path, PathBuf};

fn fixture_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/fuzz")
}

/// Runs one stored crash input through the target named by its file-name prefix (`archive-` or `decode-`).
#[allow(dead_code)] // used by the per-crash regression tests below, if any
fn replay_fixture(name: &str) {
    let data = std::fs::read(fixture_dir().join(name)).expect("read fuzz fixture");
    replay(name, &data);
}

fn replay(name: &str, data: &[u8]) {
    if name.starts_with("archive-") {
        harness::run_archive(data);
    } else if name.starts_with("decode-") {
        harness::run_decode(data);
    } else {
        panic!("fixture {name} must start with archive- or decode-");
    }
}

#[test]
fn harness_limits_match_the_plan() {
    let l = harness::LIMITS;
    assert_eq!(l.max_entries, 256);
    assert_eq!(l.max_entry_bytes, 8 * 1024 * 1024);
    assert_eq!(l.max_total_bytes, 64 * 1024 * 1024);
    assert_eq!(l.max_image_pixels, 4_000_000);
    assert_eq!(l.max_image_dimension, 16_384);
}

/// Every stored crash input still returns. New fixtures are picked up automatically; each crash also gets a
/// named test below that documents its cause.
#[test]
fn all_fuzz_fixtures_return() {
    let Ok(dir) = std::fs::read_dir(fixture_dir()) else {
        return; // no crashes recorded yet
    };
    for entry in dir {
        let path = entry.unwrap().path();
        let name = path.file_name().unwrap().to_string_lossy().to_string();
        replay(&name, &std::fs::read(&path).unwrap());
    }
}

/// The seeds the fuzzer starts from run through the real harness: archives open and their pages decode.
#[test]
fn harness_runs_on_generated_seeds() {
    let dir = tempfile::tempdir().unwrap();
    gen_corpus::generate_fuzz_seeds(dir.path()).unwrap();

    let mut archives = 0;
    for e in std::fs::read_dir(dir.path().join("archive")).unwrap() {
        let data = std::fs::read(e.unwrap().path()).unwrap();
        // The seed is valid, so the fuzz limits must accept it and every page must decode.
        let src = proviewer_core::archive::ZipSource::from_reader(
            std::io::Cursor::new(data.clone()),
            harness::LIMITS,
        )
        .expect("seed archive opens under the fuzz limits");
        use proviewer_core::archive::PageSource;
        for i in 0..src.pages().len() {
            let bytes = src.read(i).unwrap();
            proviewer_core::decode::decode(&bytes, 1, &harness::LIMITS)
                .expect("seed page decodes under the fuzz limits");
        }
        harness::run_archive(&data);
        archives += 1;
    }
    assert_eq!(archives, 5);

    let mut images = 0;
    for e in std::fs::read_dir(dir.path().join("decode")).unwrap() {
        harness::run_decode(&std::fs::read(e.unwrap().path()).unwrap());
        images += 1;
    }
    assert_eq!(images, 35);
}

/// Truncations and bit flips of valid seeds (what libFuzzer does first) must never panic or hang.
#[test]
fn harness_survives_mutated_seeds() {
    let dir = tempfile::tempdir().unwrap();
    gen_corpus::generate_fuzz_seeds(dir.path()).unwrap();
    for e in std::fs::read_dir(dir.path().join("decode")).unwrap() {
        let data = std::fs::read(e.unwrap().path()).unwrap();
        for cut in [0, 1, 4, 12, data.len() / 3, data.len() / 2, data.len() - 1] {
            harness::run_decode(&data[..cut.min(data.len())]);
        }
        let mut flipped = data.clone();
        for i in (0..flipped.len()).step_by((flipped.len() / 16).max(1)) {
            flipped[i] ^= 0x5A;
        }
        harness::run_decode(&flipped);
    }
}

thread_local! {
    static PANICS: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

/// Counts panics raised on the current thread while alive; restores the previous panic hook on drop.
type PanicHook = Box<dyn Fn(&std::panic::PanicHookInfo<'_>) + Sync + Send + 'static>;

struct PanicCounter {
    previous: Option<PanicHook>,
}

impl PanicCounter {
    fn install() -> PanicCounter {
        PANICS.with(|c| c.set(0));
        let previous = std::panic::take_hook();
        std::panic::set_hook(Box::new(|_| PANICS.with(|c| c.set(c.get() + 1))));
        PanicCounter { previous: Some(previous) }
    }

    fn count(&self) -> usize {
        PANICS.with(|c| c.get())
    }
}

impl Drop for PanicCounter {
    fn drop(&mut self) {
        if let Some(previous) = self.previous.take() {
            std::panic::set_hook(previous);
        }
    }
}

/// `decode()` turns a decoder panic into `Corrupt` with `catch_unwind`, which hides it from the return value.
/// Under libFuzzer the panic hook aborts at panic time (so it counts as a crash); on the host this test gives the
/// same visibility: no panic may be raised, even a swallowed one, for the seeds, truncations and bit flips.
#[test]
fn harness_raises_no_panics_even_if_decode_swallows_them() {
    let dir = tempfile::tempdir().unwrap();
    gen_corpus::generate_fuzz_seeds(dir.path()).unwrap();
    let counter = PanicCounter::install();

    for e in std::fs::read_dir(dir.path().join("archive")).unwrap() {
        harness::run_archive(&std::fs::read(e.unwrap().path()).unwrap());
    }
    for e in std::fs::read_dir(dir.path().join("decode")).unwrap() {
        let data = std::fs::read(e.unwrap().path()).unwrap();
        harness::run_decode(&data);
        for cut in [0, 1, 4, 12, data.len() / 3, data.len() / 2, data.len() - 1] {
            harness::run_decode(&data[..cut.min(data.len())]);
        }
        let mut flipped = data.clone();
        for i in (0..flipped.len()).step_by((flipped.len() / 16).max(1)) {
            flipped[i] ^= 0x5A;
        }
        harness::run_decode(&flipped);
    }
    if let Ok(dir) = std::fs::read_dir(fixture_dir()) {
        for entry in dir {
            let path = entry.unwrap().path();
            let name = path.file_name().unwrap().to_string_lossy().to_string();
            replay(&name, &std::fs::read(&path).unwrap());
        }
    }
    let raised = counter.count();
    drop(counter);
    assert_eq!(raised, 0, "a panic was raised (and possibly swallowed by catch_unwind)");
}
