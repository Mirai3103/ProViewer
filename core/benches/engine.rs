//! Benchmark skeleton: proves criterion is wired into the workspace.
//! Host-side numbers are a wiring check only; real engine benches arrive in Phase 1.
use criterion::{criterion_group, criterion_main, Criterion};
use proviewer_core::engine_version;
use std::hint::black_box;

fn bench_engine_version(c: &mut Criterion) {
    c.bench_function("engine_version", |b| b.iter(|| black_box(engine_version())));
}

criterion_group!(benches, bench_engine_version);
criterion_main!(benches);
