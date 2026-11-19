//! Byte-budget LRU cache of decoded pages, with in-flight decode de-duplication.
//!
//! Entries are keyed by `(book, page, actual denom)`. [`DecodeCache::get`] returns the cached entry with the
//! largest `denom <= max_denom`: the smallest image that is still sharp enough for the request.
//!
//! # Concurrency
//!
//! One `Mutex` guards the entries and the table of in-flight decodes; each in-flight decode owns a small
//! `Mutex` + `Condvar` slot that waiters sleep on. The cache mutex is never held while a decode runs.
//! The lookup, the in-flight check and the in-flight registration happen in a single critical section, so a
//! caller can never miss both a finished entry and a running decode and start a duplicate.
//!
//! * **Same key**: concurrent `get_or_decode` calls run `decode` once; the others block and receive the same
//!   `Arc` (or a clone of the same error). Errors are never cached.
//! * **Different denom**: an in-flight decode for the same page with a *smaller or equal* denom will produce
//!   an image that satisfies the request (the `get` rule would hand it out once inserted), so the caller waits
//!   for it instead of decoding again. An in-flight decode with a *larger* denom is too blurry for the
//!   request, so the caller decodes on its own. A waiter on a different denom therefore shares that decode's
//!   error too.
//! * **Panics**: if `decode` panics, a drop guard clears the in-flight slot and wakes every waiter with
//!   `EngineError::Corrupt` ("decode panicked"); the panic itself keeps unwinding through the leader's caller.
//!   Corrupt (not Cancelled) because a decoder panic means the page's bytes broke the decoder, and callers
//!   typically treat Cancelled as a silent, expected outcome.
//! * **Poisoning**: no user code runs under the cache mutex, but a poisoned lock is recovered with
//!   `PoisonError::into_inner` anyway so a panic can never cascade into every later cache call.
//!
//! `decode` must not call back into the same cache for the same page (it would wait on itself).
//! * **`remove_book`**: frees the book's entries and also *discards* its in-flight decodes: each such flight is
//!   marked and detached from the in-flight table under the cache lock. The running decode is not interrupted,
//!   and its leader and any waiters that already joined still receive the image, but `finish` does not insert
//!   it, so a closed book never leaves bytes behind. The mark belongs to that one flight: a request for the
//!   same book id made after `remove_book` (a reopened book) finds no flight, starts a fresh decode and is
//!   retained normally.

use crate::decode::DecodedImage;
use crate::error::{EngineError, Result};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, PoisonError};

#[derive(Clone, Copy, Debug, Hash, PartialEq, Eq)]
pub struct CacheKey {
    pub book: u64,
    pub page: u32,
    pub denom: u8,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(PoisonError::into_inner)
}

struct Entry {
    image: Arc<DecodedImage>,
    /// Recency stamp; the smallest stamp is the least recently used entry.
    tick: u64,
}

#[derive(Default)]
struct Inner {
    entries: HashMap<CacheKey, Entry>,
    inflight: HashMap<CacheKey, Arc<Flight>>,
    used: u64,
    tick: u64,
}

impl Inner {
    fn next_tick(&mut self) -> u64 {
        self.tick += 1;
        self.tick
    }

    /// Entry with the largest denom `<= max_denom`, marked most recently used.
    fn lookup(&mut self, book: u64, page: u32, max_denom: u8) -> Option<Arc<DecodedImage>> {
        let found = (1..=max_denom)
            .rev()
            .map(|denom| CacheKey { book, page, denom })
            .find(|k| self.entries.contains_key(k))?;
        let tick = self.next_tick();
        let entry = self.entries.get_mut(&found)?;
        entry.tick = tick;
        Some(Arc::clone(&entry.image))
    }

    /// In-flight decode of the same page whose result will satisfy `key` (largest denom `<= key.denom`).
    fn inflight_for(&self, key: CacheKey) -> Option<Arc<Flight>> {
        (1..=key.denom)
            .rev()
            .find_map(|denom| self.inflight.get(&CacheKey { denom, ..key }))
            .map(Arc::clone)
    }

    /// Remove `key`'s in-flight slot, but only if it still holds `flight` (`remove_book` may have detached it
    /// and a newer flight may now own the key).
    fn detach_flight(&mut self, key: CacheKey, flight: &Arc<Flight>) {
        if self
            .inflight
            .get(&key)
            .is_some_and(|f| Arc::ptr_eq(f, flight))
        {
            self.inflight.remove(&key);
        }
    }

    fn insert(&mut self, budget: u64, book: u64, page: u32, image: &Arc<DecodedImage>) {
        let size = image.byte_size();
        if size > budget {
            // Larger than the whole cache: callers still get the image, but it is not retained and it does
            // not push anything else out.
            return;
        }
        let key = CacheKey {
            book,
            page,
            denom: image.denom,
        };
        if let Some(old) = self.entries.remove(&key) {
            self.used -= old.image.byte_size();
        }
        while self.used + size > budget {
            let Some(lru) = self
                .entries
                .iter()
                .min_by_key(|(_, e)| e.tick)
                .map(|(k, _)| *k)
            else {
                break;
            };
            if let Some(evicted) = self.entries.remove(&lru) {
                self.used -= evicted.image.byte_size();
            }
        }
        let tick = self.next_tick();
        self.used += size;
        self.entries.insert(
            key,
            Entry {
                image: Arc::clone(image),
                tick,
            },
        );
    }
}

/// One in-flight decode: its eventual result and the threads sleeping on it.
struct Flight {
    slot: Mutex<Option<Result<Arc<DecodedImage>>>>,
    ready: Condvar,
    waiters: AtomicUsize,
    /// Set (under the cache lock) when `remove_book` dropped this flight's book while it was running: the
    /// result is still delivered to the leader and its waiters, but not inserted into the cache.
    discarded: AtomicBool,
}

impl Flight {
    fn new() -> Self {
        Flight {
            slot: Mutex::new(None),
            ready: Condvar::new(),
            waiters: AtomicUsize::new(0),
            discarded: AtomicBool::new(false),
        }
    }

    fn publish(&self, result: Result<Arc<DecodedImage>>) {
        *lock(&self.slot) = Some(result);
        self.ready.notify_all();
    }

    fn wait(&self) -> Result<Arc<DecodedImage>> {
        let mut slot = lock(&self.slot);
        loop {
            if let Some(result) = slot.as_ref() {
                return result.clone();
            }
            slot = self
                .ready
                .wait(slot)
                .unwrap_or_else(PoisonError::into_inner);
        }
    }
}

/// Owned by the thread that runs `decode`. If it is dropped without `finish` (the decode panicked), it clears
/// the in-flight slot and fails the waiters, so nobody blocks forever and the page can be decoded again.
struct Leader<'a> {
    cache: &'a DecodeCache,
    key: CacheKey,
    flight: Arc<Flight>,
    done: bool,
}

impl Leader<'_> {
    fn finish(mut self, result: Result<DecodedImage>) -> Result<Arc<DecodedImage>> {
        let result = result.map(Arc::new);
        {
            let mut inner = lock(&self.cache.inner);
            // `discarded` is only written under this same lock, so it is atomic with `remove_book`.
            if let Ok(image) = &result {
                if !self.flight.discarded.load(Ordering::SeqCst) {
                    inner.insert(self.cache.budget, self.key.book, self.key.page, image);
                }
            }
            inner.detach_flight(self.key, &self.flight);
        }
        self.done = true;
        self.flight.publish(result.clone());
        result
    }
}

impl Drop for Leader<'_> {
    fn drop(&mut self) {
        if self.done {
            return;
        }
        lock(&self.cache.inner).detach_flight(self.key, &self.flight);
        self.flight.publish(Err(EngineError::Corrupt {
            message: "decode panicked".to_string(),
        }));
    }
}

pub struct DecodeCache {
    budget: u64,
    inner: Mutex<Inner>,
}

impl DecodeCache {
    pub fn new(budget_bytes: u64) -> Self {
        DecodeCache {
            budget: budget_bytes,
            inner: Mutex::new(Inner::default()),
        }
    }

    /// Insert an already decoded page under its actual `image.denom`, evicting least recently used entries
    /// until it fits. Re-inserting the same `(book, page, denom)` replaces the entry (no double counting). An
    /// image larger than the whole budget is returned but not retained.
    pub fn insert(&self, book: u64, page: u32, image: DecodedImage) -> Arc<DecodedImage> {
        let image = Arc::new(image);
        lock(&self.inner).insert(self.budget, book, page, &image);
        image
    }

    /// The cached entry with the largest denom `<= max_denom`, marked most recently used.
    pub fn get(&self, book: u64, page: u32, max_denom: u8) -> Option<Arc<DecodedImage>> {
        lock(&self.inner).lookup(book, page, max_denom)
    }

    /// Cached image for `key`, or the result of `decode`, run at most once per in-flight page (see the module
    /// docs for how concurrent callers, other denoms, errors and panics are handled).
    pub fn get_or_decode(
        &self,
        key: CacheKey,
        decode: impl FnOnce() -> Result<DecodedImage>,
    ) -> Result<Arc<DecodedImage>> {
        let flight = {
            let mut inner = lock(&self.inner);
            if let Some(hit) = inner.lookup(key.book, key.page, key.denom) {
                return Ok(hit);
            }
            if let Some(flight) = inner.inflight_for(key) {
                // Counted while the cache lock is held, so a counted waiter is guaranteed to reach `wait`.
                flight.waiters.fetch_add(1, Ordering::SeqCst);
                drop(inner);
                return flight.wait();
            }
            let flight = Arc::new(Flight::new());
            inner.inflight.insert(key, Arc::clone(&flight));
            flight
        };
        let leader = Leader {
            cache: self,
            key,
            flight,
            done: false,
        };
        // No lock is held here. A panic in `decode` unwinds through `leader`'s Drop.
        let result = decode();
        leader.finish(result)
    }

    pub fn remove_book(&self, book: u64) {
        let mut inner = lock(&self.inner);
        let mut freed = 0;
        inner.entries.retain(|k, e| {
            let keep = k.book != book;
            if !keep {
                freed += e.image.byte_size();
            }
            keep
        });
        inner.used -= freed;
        // Discard in-flight decodes of this book: detach them so later requests start fresh flights, and mark
        // them so their leaders do not insert the result.
        inner.inflight.retain(|k, flight| {
            let keep = k.book != book;
            if !keep {
                flight.discarded.store(true, Ordering::SeqCst);
            }
            keep
        });
    }

    pub fn used_bytes(&self) -> u64 {
        lock(&self.inner).used
    }

    pub fn len(&self) -> usize {
        lock(&self.inner).entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Threads currently waiting on the in-flight decode registered under exactly `key`. Diagnostics for
    /// tests: it lets a test prove a caller has joined a running decode before that decode is released.
    #[doc(hidden)]
    pub fn inflight_waiters(&self, key: CacheKey) -> usize {
        lock(&self.inner)
            .inflight
            .get(&key)
            .map_or(0, |f| f.waiters.load(Ordering::SeqCst))
    }

    /// Poison the cache mutex by panicking while holding it.
    #[cfg(test)]
    fn poison_for_test(&self) {
        let _ = std::thread::scope(|s| {
            s.spawn(|| {
                let _guard = self.inner.lock().unwrap();
                panic!("poisoning the cache lock on purpose");
            })
            .join()
        });
        assert!(self.inner.is_poisoned());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::decode::PixelLayout;
    use crate::error::EngineError;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::mpsc;
    use std::thread;
    use std::time::{Duration, Instant};

    const TIMEOUT: Duration = Duration::from_secs(5);

    fn img(denom: u8, bytes: usize) -> DecodedImage {
        DecodedImage {
            width: bytes as u32,
            height: 1,
            layout: PixelLayout::Gray8,
            pixels: vec![denom; bytes],
            denom,
        }
    }

    fn key(page: u32, denom: u8) -> CacheKey {
        CacheKey {
            book: 1,
            page,
            denom,
        }
    }

    /// Poll (bounded) until `cond` holds; panics after 5 s. Used to wait for a thread to reach a known
    /// blocked state, never as a substitute for a happens-before edge on the data under test.
    fn wait_until(cond: impl Fn() -> bool) {
        let start = Instant::now();
        while !cond() {
            assert!(start.elapsed() < TIMEOUT, "timed out waiting for condition");
            thread::yield_now();
        }
    }

    #[test]
    fn get_prefers_largest_sufficient_denom() {
        let cache = DecodeCache::new(1 << 20);
        cache.insert(1, 0, img(1, 100));
        cache.insert(1, 0, img(4, 10));
        assert_eq!(cache.get(1, 0, 2).unwrap().denom, 1);
        assert_eq!(cache.get(1, 0, 4).unwrap().denom, 4);
        assert_eq!(cache.get(1, 0, 8).unwrap().denom, 4);
        assert!(cache.get(1, 1, 8).is_none());
        assert!(cache.get(1, 0, 0).is_none());
    }

    #[test]
    fn lru_evicts_by_bytes_within_budget() {
        let budget = 300;
        let cache = DecodeCache::new(budget);
        for page in 0..4 {
            cache.insert(1, page, img(1, 100));
            assert!(cache.used_bytes() <= budget);
        }
        assert!(cache.get(1, 0, 1).is_none(), "oldest entry must be evicted");
        for page in 1..4 {
            assert!(cache.get(1, page, 1).is_some());
        }
        assert_eq!(cache.used_bytes(), 300);
        assert_eq!(cache.len(), 3);
    }

    #[test]
    fn get_refreshes_recency() {
        let cache = DecodeCache::new(300);
        for page in 0..3 {
            cache.insert(1, page, img(1, 100));
        }
        assert!(cache.get(1, 0, 1).is_some());
        cache.insert(1, 3, img(1, 100));
        assert!(cache.get(1, 0, 1).is_some(), "refreshed entry survives");
        assert!(
            cache.get(1, 1, 1).is_none(),
            "second entry is evicted instead"
        );
        assert!(cache.get(1, 2, 1).is_some());
    }

    #[test]
    fn eviction_frees_enough_for_a_larger_image() {
        let cache = DecodeCache::new(300);
        for page in 0..3 {
            cache.insert(1, page, img(1, 100));
        }
        cache.insert(1, 9, img(1, 250));
        assert_eq!(cache.used_bytes(), 250);
        assert_eq!(cache.len(), 1);
    }

    #[test]
    fn oversized_image_is_returned_not_retained() {
        let cache = DecodeCache::new(100);
        let out = cache.get_or_decode(key(0, 1), || Ok(img(1, 101))).unwrap();
        assert_eq!(out.byte_size(), 101);
        assert_eq!(cache.len(), 0);
        assert_eq!(cache.used_bytes(), 0);
    }

    #[test]
    fn oversized_image_does_not_evict_existing_entries() {
        let cache = DecodeCache::new(100);
        cache.insert(1, 0, img(1, 60));
        cache.insert(1, 1, img(1, 101));
        assert_eq!(cache.len(), 1);
        assert_eq!(cache.used_bytes(), 60);
    }

    #[test]
    fn reinserting_same_entry_does_not_double_count() {
        let cache = DecodeCache::new(1000);
        cache.insert(1, 0, img(1, 100));
        cache.insert(1, 0, img(1, 100));
        assert_eq!(cache.used_bytes(), 100);
        assert_eq!(cache.len(), 1);
        // Same slot, different size: the new size replaces the old one.
        cache.insert(1, 0, img(1, 40));
        assert_eq!(cache.used_bytes(), 40);
        assert_eq!(cache.len(), 1);
    }

    #[test]
    fn get_or_decode_hits_cache_without_decoding() {
        let cache = DecodeCache::new(1000);
        let a = cache.insert(1, 0, img(1, 100));
        let b = cache
            .get_or_decode(key(0, 2), || panic!("must not decode on a hit"))
            .unwrap();
        assert!(Arc::ptr_eq(&a, &b), "denom 1 satisfies a denom 2 request");
    }

    #[test]
    fn get_or_decode_inserts_under_actual_denom() {
        let cache = DecodeCache::new(1000);
        // Requested denom 4, but the decoder only produced denom 1 (e.g. a PNG).
        cache.get_or_decode(key(0, 4), || Ok(img(1, 10))).unwrap();
        assert_eq!(cache.get(1, 0, 1).unwrap().denom, 1);
        assert_eq!(cache.get(1, 0, 4).unwrap().denom, 1);
    }

    #[test]
    fn concurrent_get_or_decode_decodes_once() {
        let cache = Arc::new(DecodeCache::new(1 << 20));
        let counter = Arc::new(AtomicUsize::new(0));
        let handles: Vec<_> = (0..8)
            .map(|_| {
                let cache = Arc::clone(&cache);
                let counter = Arc::clone(&counter);
                thread::spawn(move || {
                    cache
                        .get_or_decode(key(0, 1), || {
                            counter.fetch_add(1, Ordering::SeqCst);
                            thread::sleep(Duration::from_millis(50));
                            Ok(img(1, 64))
                        })
                        .unwrap()
                })
            })
            .collect();
        let results: Vec<_> = handles.into_iter().map(|h| h.join().unwrap()).collect();
        assert_eq!(counter.load(Ordering::SeqCst), 1);
        for r in &results[1..] {
            assert!(Arc::ptr_eq(&results[0], r));
        }
    }

    #[test]
    fn waiters_share_the_leaders_result_deterministically() {
        let cache = Arc::new(DecodeCache::new(1 << 20));
        let counter = Arc::new(AtomicUsize::new(0));
        let (started_tx, started_rx) = mpsc::channel::<()>();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let leader = {
            let (cache, counter) = (Arc::clone(&cache), Arc::clone(&counter));
            thread::spawn(move || {
                cache
                    .get_or_decode(key(0, 1), || {
                        counter.fetch_add(1, Ordering::SeqCst);
                        started_tx.send(()).unwrap();
                        release_rx.recv_timeout(TIMEOUT).unwrap();
                        Ok(img(1, 64))
                    })
                    .unwrap()
            })
        };
        started_rx.recv_timeout(TIMEOUT).unwrap();
        let waiters: Vec<_> = (0..4)
            .map(|_| {
                let (cache, counter) = (Arc::clone(&cache), Arc::clone(&counter));
                thread::spawn(move || {
                    cache
                        .get_or_decode(key(0, 1), || {
                            counter.fetch_add(1, Ordering::SeqCst);
                            Ok(img(1, 64))
                        })
                        .unwrap()
                })
            })
            .collect();
        wait_until(|| cache.inflight_waiters(key(0, 1)) == 4);
        release_tx.send(()).unwrap();
        let first = leader.join().unwrap();
        for w in waiters {
            assert!(Arc::ptr_eq(&first, &w.join().unwrap()));
        }
        assert_eq!(counter.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn errors_are_shared_but_not_cached() {
        let cache = Arc::new(DecodeCache::new(1 << 20));
        let counter = Arc::new(AtomicUsize::new(0));
        let (started_tx, started_rx) = mpsc::channel::<()>();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let corrupt = EngineError::Corrupt {
            message: "bad page".to_string(),
        };
        let leader = {
            let (cache, counter, corrupt) =
                (Arc::clone(&cache), Arc::clone(&counter), corrupt.clone());
            thread::spawn(move || {
                cache.get_or_decode(key(0, 1), || {
                    counter.fetch_add(1, Ordering::SeqCst);
                    started_tx.send(()).unwrap();
                    release_rx.recv_timeout(TIMEOUT).unwrap();
                    Err(corrupt)
                })
            })
        };
        started_rx.recv_timeout(TIMEOUT).unwrap();
        let waiters: Vec<_> = (0..3)
            .map(|_| {
                let (cache, counter) = (Arc::clone(&cache), Arc::clone(&counter));
                thread::spawn(move || {
                    cache.get_or_decode(key(0, 1), || {
                        counter.fetch_add(1, Ordering::SeqCst);
                        Ok(img(1, 8))
                    })
                })
            })
            .collect();
        wait_until(|| cache.inflight_waiters(key(0, 1)) == 3);
        release_tx.send(()).unwrap();
        assert_eq!(leader.join().unwrap().unwrap_err(), corrupt);
        for w in waiters {
            assert_eq!(w.join().unwrap().unwrap_err(), corrupt);
        }
        assert_eq!(counter.load(Ordering::SeqCst), 1);
        assert_eq!(cache.len(), 0);

        // Not cached: a later call decodes again, and the slot is free.
        let again = cache
            .get_or_decode(key(0, 1), || {
                counter.fetch_add(1, Ordering::SeqCst);
                Ok(img(1, 8))
            })
            .unwrap();
        assert_eq!(counter.load(Ordering::SeqCst), 2);
        assert_eq!(again.denom, 1);
    }

    #[test]
    fn panicking_decode_errors_waiters_and_clears_the_slot() {
        let cache = Arc::new(DecodeCache::new(1 << 20));
        let (started_tx, started_rx) = mpsc::channel::<()>();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let leader = {
            let cache = Arc::clone(&cache);
            thread::spawn(move || {
                cache.get_or_decode(key(0, 1), || -> Result<DecodedImage> {
                    started_tx.send(()).unwrap();
                    release_rx.recv_timeout(TIMEOUT).unwrap();
                    panic!("decoder bug");
                })
            })
        };
        started_rx.recv_timeout(TIMEOUT).unwrap();
        let waiter = {
            let cache = Arc::clone(&cache);
            thread::spawn(move || {
                cache.get_or_decode(key(0, 1), || panic!("waiter must not decode"))
            })
        };
        wait_until(|| cache.inflight_waiters(key(0, 1)) == 1);
        release_tx.send(()).unwrap();

        assert!(
            leader.join().is_err(),
            "the leader's panic propagates to its caller"
        );
        match waiter.join().unwrap() {
            Err(EngineError::Corrupt { message }) => assert!(message.contains("panicked")),
            other => panic!("waiter should get a Corrupt error, got {other:?}"),
        }
        // The slot is cleared: the next call decodes normally instead of hanging or inheriting the failure.
        let ok = cache.get_or_decode(key(0, 1), || Ok(img(1, 8))).unwrap();
        assert_eq!(ok.denom, 1);
        assert_eq!(cache.len(), 1);
    }

    #[test]
    fn waits_for_an_in_flight_decode_that_is_sharp_enough() {
        let cache = Arc::new(DecodeCache::new(1 << 20));
        let counter = Arc::new(AtomicUsize::new(0));
        let (started_tx, started_rx) = mpsc::channel::<()>();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let leader = {
            let (cache, counter) = (Arc::clone(&cache), Arc::clone(&counter));
            thread::spawn(move || {
                cache
                    .get_or_decode(key(0, 1), || {
                        counter.fetch_add(1, Ordering::SeqCst);
                        started_tx.send(()).unwrap();
                        release_rx.recv_timeout(TIMEOUT).unwrap();
                        Ok(img(1, 64))
                    })
                    .unwrap()
            })
        };
        started_rx.recv_timeout(TIMEOUT).unwrap();
        // A denom 2 request is satisfied by the denom 1 image that is already being decoded.
        let waiter = {
            let (cache, counter) = (Arc::clone(&cache), Arc::clone(&counter));
            thread::spawn(move || {
                cache
                    .get_or_decode(key(0, 2), || {
                        counter.fetch_add(1, Ordering::SeqCst);
                        Ok(img(2, 16))
                    })
                    .unwrap()
            })
        };
        wait_until(|| cache.inflight_waiters(key(0, 1)) == 1);
        release_tx.send(()).unwrap();
        let first = leader.join().unwrap();
        assert!(Arc::ptr_eq(&first, &waiter.join().unwrap()));
        assert_eq!(counter.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn does_not_wait_for_an_in_flight_decode_that_is_too_small() {
        let cache = Arc::new(DecodeCache::new(1 << 20));
        let (started_tx, started_rx) = mpsc::channel::<()>();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let leader = {
            let cache = Arc::clone(&cache);
            thread::spawn(move || {
                cache
                    .get_or_decode(key(0, 2), || {
                        started_tx.send(()).unwrap();
                        release_rx.recv_timeout(TIMEOUT).unwrap();
                        Ok(img(2, 16))
                    })
                    .unwrap()
            })
        };
        started_rx.recv_timeout(TIMEOUT).unwrap();
        // A denom 1 request needs more detail than the in-flight denom 2 decode will give: it decodes on its
        // own and returns while the other is still blocked (so this cannot deadlock).
        let full = cache.get_or_decode(key(0, 1), || Ok(img(1, 64))).unwrap();
        assert_eq!(full.denom, 1);
        release_tx.send(()).unwrap();
        assert_eq!(leader.join().unwrap().denom, 2);
        assert_eq!(cache.get(1, 0, 2).unwrap().denom, 2, "largest denom <= 2");
        assert_eq!(cache.get(1, 0, 1).unwrap().denom, 1);
    }

    #[test]
    fn poisoned_lock_is_recovered() {
        let cache = DecodeCache::new(1000);
        cache.insert(1, 0, img(1, 100));
        cache.poison_for_test();
        assert_eq!(cache.get(1, 0, 1).unwrap().denom, 1);
        cache.insert(1, 1, img(1, 100));
        assert_eq!(cache.used_bytes(), 200);
        let out = cache.get_or_decode(key(2, 1), || Ok(img(1, 50))).unwrap();
        assert_eq!(out.byte_size(), 50);
    }

    #[test]
    fn remove_book_discards_in_flight_decode_result() {
        let cache = Arc::new(DecodeCache::new(1 << 20));
        let (started_tx, started_rx) = mpsc::channel::<()>();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let leader = {
            let cache = Arc::clone(&cache);
            thread::spawn(move || {
                cache
                    .get_or_decode(key(0, 1), || {
                        started_tx.send(()).unwrap();
                        release_rx.recv_timeout(TIMEOUT).unwrap();
                        Ok(img(1, 64))
                    })
                    .unwrap()
            })
        };
        started_rx.recv_timeout(TIMEOUT).unwrap();
        let waiter = {
            let cache = Arc::clone(&cache);
            thread::spawn(move || {
                cache
                    .get_or_decode(key(0, 1), || panic!("waiter must not decode"))
                    .unwrap()
            })
        };
        wait_until(|| cache.inflight_waiters(key(0, 1)) == 1);
        // A different book's in-flight state and entries are untouched.
        cache.insert(2, 0, img(1, 10));
        cache.remove_book(1);
        release_tx.send(()).unwrap();

        let first = leader.join().unwrap();
        assert!(
            Arc::ptr_eq(&first, &waiter.join().unwrap()),
            "leader and waiter still share the image"
        );
        assert_eq!(first.byte_size(), 64);
        assert!(
            cache.get(1, 0, 1).is_none(),
            "removed book must not reappear"
        );
        assert_eq!(
            cache.used_bytes(),
            10,
            "only the other book's entry remains"
        );
        assert_eq!(cache.len(), 1);
        assert_eq!(cache.inflight_waiters(key(0, 1)), 0, "no leaked flight");
        assert!(lock(&cache.inner).inflight.is_empty(), "no leaked flight");
    }

    #[test]
    fn discarded_flag_does_not_leak_to_later_flights() {
        let cache = Arc::new(DecodeCache::new(1 << 20));
        let counter = Arc::new(AtomicUsize::new(0));
        let (started_tx, started_rx) = mpsc::channel::<()>();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let old_leader = {
            let (cache, counter) = (Arc::clone(&cache), Arc::clone(&counter));
            thread::spawn(move || {
                cache
                    .get_or_decode(key(0, 1), || {
                        counter.fetch_add(1, Ordering::SeqCst);
                        started_tx.send(()).unwrap();
                        release_rx.recv_timeout(TIMEOUT).unwrap();
                        Ok(img(1, 64))
                    })
                    .unwrap()
            })
        };
        started_rx.recv_timeout(TIMEOUT).unwrap();
        cache.remove_book(1);
        // The book is reopened (same id) while the old decode is still running: a new request must start its
        // own decode instead of attaching to the discarded flight, and its result is retained.
        let fresh = cache
            .get_or_decode(key(0, 1), || {
                counter.fetch_add(1, Ordering::SeqCst);
                Ok(img(1, 32))
            })
            .unwrap();
        assert_eq!(fresh.byte_size(), 32);
        assert_eq!(counter.load(Ordering::SeqCst), 2);
        assert_eq!(cache.used_bytes(), 32);

        release_tx.send(()).unwrap();
        let old = old_leader.join().unwrap();
        assert_eq!(old.byte_size(), 64, "the old leader still gets its image");
        // The old result is discarded and must not replace or double count the fresh entry.
        assert_eq!(cache.used_bytes(), 32);
        assert_eq!(cache.len(), 1);
        assert!(Arc::ptr_eq(&cache.get(1, 0, 1).unwrap(), &fresh));
        assert!(lock(&cache.inner).inflight.is_empty());
    }

    #[test]
    fn remove_book_frees_bytes() {
        let cache = DecodeCache::new(1000);
        cache.insert(1, 0, img(1, 100));
        cache.insert(1, 1, img(2, 50));
        cache.insert(2, 0, img(1, 70));
        cache.remove_book(1);
        assert_eq!(cache.used_bytes(), 70);
        assert_eq!(cache.len(), 1);
        assert!(cache.get(1, 0, 8).is_none());
        assert!(cache.get(2, 0, 1).is_some());
        cache.remove_book(2);
        assert_eq!(cache.used_bytes(), 0);
        assert!(cache.is_empty());
    }
}
