//! Prefetch worker pool: a small priority queue served by background threads.
//!
//! * **Order**: lower `priority` runs first, FIFO within a priority (a monotonically increasing sequence
//!   number breaks ties).
//! * **Generations**: the owner bumps the generation when the reading position jumps.
//!   [`PrefetchPool::set_generation`] drops every queued job older than the new generation without running
//!   it. A job submitted with a generation older than the current one is dropped immediately at `submit` (it is already
//!   stale, so queueing it would only waste a slot). A job that is already running is never interrupted.
//! * **Panics**: each job runs inside `catch_unwind`, so a panicking job never kills its worker.
//! * **Shutdown**: `Drop` discards jobs still queued, lets a running job complete, and joins the workers.
//! * **Priority**: on Linux and Android the workers lower their own scheduling priority (nice 10) so prefetch
//!   decoding does not compete with the UI thread.
//!
//! Dropped jobs are destroyed after the queue lock is released, so a job's destructor never runs under it.

use std::collections::BTreeMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, PoisonError};
use std::thread::{self, JoinHandle};

type Job = Box<dyn FnOnce() + Send>;

struct QueuedJob {
    generation: u64,
    run: Job,
}

#[derive(Default)]
struct State {
    /// Ordered by `(priority, submission sequence)`, so the first entry is the next job to run.
    queue: BTreeMap<(u32, u64), QueuedJob>,
    generation: u64,
    next_seq: u64,
    shutdown: bool,
    /// Test hook: workers do not take jobs while paused.
    paused: bool,
}

struct Shared {
    state: Mutex<State>,
    wake: Condvar,
    /// Workers that have not exited yet (incremented before spawn, decremented when a worker returns).
    alive: Arc<AtomicUsize>,
}

fn lock(m: &Mutex<State>) -> MutexGuard<'_, State> {
    m.lock().unwrap_or_else(PoisonError::into_inner)
}

pub struct PrefetchPool {
    shared: Arc<Shared>,
    workers: Vec<JoinHandle<()>>,
}

impl PrefetchPool {
    pub fn new(threads: usize) -> Self {
        Self::with_state(threads, State::default())
    }

    /// A pool whose workers hold off until [`resume`](Self::resume), so ordering tests are deterministic.
    #[cfg(test)]
    fn new_paused(threads: usize) -> Self {
        Self::with_state(
            threads,
            State {
                paused: true,
                ..State::default()
            },
        )
    }

    #[cfg(test)]
    fn resume(&self) {
        lock(&self.shared.state).paused = false;
        self.shared.wake.notify_all();
    }

    #[cfg(test)]
    fn alive_workers(&self) -> Arc<AtomicUsize> {
        Arc::clone(&self.shared.alive)
    }

    fn with_state(threads: usize, state: State) -> Self {
        let shared = Arc::new(Shared {
            state: Mutex::new(state),
            wake: Condvar::new(),
            alive: Arc::new(AtomicUsize::new(0)),
        });
        let workers = (0..threads.max(1))
            .map(|i| {
                shared.alive.fetch_add(1, Ordering::SeqCst);
                let shared = Arc::clone(&shared);
                thread::Builder::new()
                    .name(format!("proviewer-prefetch-{i}"))
                    .spawn(move || worker(&shared))
                    .expect("failed to spawn a prefetch worker thread")
            })
            .collect();
        PrefetchPool { shared, workers }
    }

    /// Queue `job`. Lower `priority` runs first; equal priorities run in submission order. A job whose
    /// `generation` is older than the current one is dropped without running.
    pub fn submit(&self, generation: u64, priority: u32, job: Job) {
        let rejected = {
            let mut state = lock(&self.shared.state);
            if state.shutdown || generation < state.generation {
                Some(job)
            } else {
                let seq = state.next_seq;
                state.next_seq += 1;
                state.queue.insert(
                    (priority, seq),
                    QueuedJob {
                        generation,
                        run: job,
                    },
                );
                None
            }
        };
        match rejected {
            None => self.shared.wake.notify_one(),
            Some(job) => drop(job),
        }
    }

    /// Make `generation` current and drop queued jobs older than it without running them.
    pub fn set_generation(&self, generation: u64) {
        let stale = {
            let mut state = lock(&self.shared.state);
            state.generation = generation;
            let (keep, stale): (BTreeMap<_, _>, BTreeMap<_, _>) = std::mem::take(&mut state.queue)
                .into_iter()
                .partition(|(_, q)| q.generation >= generation);
            state.queue = keep;
            stale
        };
        drop(stale);
    }
}

impl Drop for PrefetchPool {
    fn drop(&mut self) {
        let discarded = {
            let mut state = lock(&self.shared.state);
            state.shutdown = true;
            std::mem::take(&mut state.queue)
        };
        self.shared.wake.notify_all();
        let current = thread::current().id();
        for handle in self.workers.drain(..) {
            // A job that held the last reference to the pool would be dropping it on a worker thread; joining
            // oneself would deadlock, and that worker exits on its own right after the job returns.
            if handle.thread().id() != current {
                let _ = handle.join();
            }
        }
        drop(discarded);
    }
}

/// Decrements the live-worker count when the worker returns, on any path.
struct AliveGuard(Arc<AtomicUsize>);

impl Drop for AliveGuard {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

fn worker(shared: &Shared) {
    let _alive = AliveGuard(Arc::clone(&shared.alive));
    lower_thread_priority();
    loop {
        let job = {
            let mut state = lock(&shared.state);
            loop {
                if state.shutdown {
                    return;
                }
                if !state.paused {
                    if let Some((_, queued)) = state.queue.pop_first() {
                        break queued;
                    }
                }
                state = shared
                    .wake
                    .wait(state)
                    .unwrap_or_else(PoisonError::into_inner);
            }
        };
        // A panicking job must not take the worker down. The payload is dropped here; the panic message has
        // already been reported by the panic hook.
        let _ = catch_unwind(AssertUnwindSafe(job.run));
    }
}

/// Best effort: nice 10 for the calling thread so prefetch yields to the UI. A failure (for example a
/// restrictive sandbox) only means the worker keeps the default priority.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn lower_thread_priority() {
    // SAFETY: `gettid` takes no arguments and cannot fail. `setpriority` only reads its integer arguments;
    // with `PRIO_PROCESS` and a thread id it affects just the calling thread on Linux.
    let rc = unsafe { libc::setpriority(libc::PRIO_PROCESS, libc::gettid() as libc::id_t, 10) };
    // Intentionally ignored: priority is an optimisation, not a correctness requirement.
    let _ = rc;
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn lower_thread_priority() {}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{mpsc, Arc, Mutex};
    use std::time::Duration;

    const TIMEOUT: Duration = Duration::from_secs(5);

    /// A job that records `tag` into `log`, then signals `done`.
    fn tagged(
        tag: u32,
        log: &Arc<Mutex<Vec<u32>>>,
        done: &mpsc::Sender<()>,
    ) -> Box<dyn FnOnce() + Send> {
        let (log, done) = (Arc::clone(log), done.clone());
        Box::new(move || {
            log.lock().unwrap().push(tag);
            done.send(()).unwrap();
        })
    }

    fn recv_n(rx: &mpsc::Receiver<()>, n: usize) {
        for _ in 0..n {
            rx.recv_timeout(TIMEOUT).expect("job did not run in time");
        }
    }

    #[test]
    fn runs_in_priority_order() {
        let pool = PrefetchPool::new_paused(1);
        let log = Arc::new(Mutex::new(Vec::new()));
        let (tx, rx) = mpsc::channel();
        for p in [3, 1, 2] {
            pool.submit(0, p, tagged(p, &log, &tx));
        }
        pool.resume();
        recv_n(&rx, 3);
        assert_eq!(*log.lock().unwrap(), vec![1, 2, 3]);
    }

    #[test]
    fn fifo_within_a_priority() {
        let pool = PrefetchPool::new_paused(1);
        let log = Arc::new(Mutex::new(Vec::new()));
        let (tx, rx) = mpsc::channel();
        for tag in [10, 11, 12] {
            pool.submit(0, 5, tagged(tag, &log, &tx));
        }
        pool.submit(0, 1, tagged(1, &log, &tx));
        pool.resume();
        recv_n(&rx, 4);
        assert_eq!(*log.lock().unwrap(), vec![1, 10, 11, 12]);
    }

    #[test]
    fn stale_generation_is_dropped() {
        let pool = PrefetchPool::new_paused(1);
        let log = Arc::new(Mutex::new(Vec::new()));
        let (tx, rx) = mpsc::channel();
        for tag in 0..5 {
            pool.submit(1, 0, tagged(tag, &log, &tx));
        }
        pool.set_generation(2);
        pool.submit(2, 0, tagged(99, &log, &tx));
        pool.resume();
        recv_n(&rx, 1);
        // One worker, equal priority, FIFO: any surviving stale job would have run before the gen-2 job.
        assert_eq!(*log.lock().unwrap(), vec![99]);
        assert!(rx.recv_timeout(Duration::from_millis(100)).is_err());
    }

    #[test]
    fn job_submitted_with_stale_generation_is_dropped() {
        let pool = PrefetchPool::new_paused(1);
        let log = Arc::new(Mutex::new(Vec::new()));
        let (tx, rx) = mpsc::channel();
        pool.set_generation(5);
        pool.submit(4, 0, tagged(1, &log, &tx)); // older than current: dropped at submit
        pool.submit(5, 0, tagged(2, &log, &tx)); // current: kept
        pool.submit(6, 0, tagged(3, &log, &tx)); // newer: kept
        pool.resume();
        recv_n(&rx, 2);
        assert_eq!(*log.lock().unwrap(), vec![2, 3]);
        assert!(rx.recv_timeout(Duration::from_millis(100)).is_err());
    }

    #[test]
    fn panicking_job_does_not_kill_worker() {
        let pool = PrefetchPool::new(1);
        let (tx, rx) = mpsc::channel();
        pool.submit(0, 0, Box::new(|| panic!("job bug")));
        pool.submit(0, 1, Box::new(move || tx.send(()).unwrap()));
        rx.recv_timeout(TIMEOUT)
            .expect("the worker must survive a panicking job");
    }

    #[test]
    fn drop_joins_workers() {
        let pool = PrefetchPool::new(3);
        let alive = pool.alive_workers();
        assert_eq!(alive.load(Ordering::SeqCst), 3);
        drop(pool);
        assert_eq!(alive.load(Ordering::SeqCst), 0, "all workers joined");
    }

    #[test]
    fn drop_discards_queued_jobs() {
        let pool = PrefetchPool::new_paused(1);
        let ran = Arc::new(AtomicBool::new(false));
        let r = Arc::clone(&ran);
        pool.submit(0, 0, Box::new(move || r.store(true, Ordering::SeqCst)));
        let alive = pool.alive_workers();
        drop(pool);
        assert_eq!(alive.load(Ordering::SeqCst), 0);
        assert!(!ran.load(Ordering::SeqCst));
    }

    #[test]
    fn drop_waits_for_the_running_job() {
        let pool = PrefetchPool::new(1);
        let finished = Arc::new(AtomicBool::new(false));
        let (started_tx, started_rx) = mpsc::channel::<()>();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let f = Arc::clone(&finished);
        pool.submit(
            0,
            0,
            Box::new(move || {
                started_tx.send(()).unwrap();
                release_rx.recv_timeout(TIMEOUT).unwrap();
                f.store(true, Ordering::SeqCst);
            }),
        );
        started_rx.recv_timeout(TIMEOUT).unwrap();
        let releaser = std::thread::spawn(move || release_tx.send(()).unwrap());
        drop(pool); // blocks until the running job completes
        releaser.join().unwrap();
        assert!(finished.load(Ordering::SeqCst));
    }
}
