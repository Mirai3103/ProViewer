//! Handle registries that hand out opaque `u64` ids for `Arc`-owned engine objects so the JNI
//! pixel path can fetch a `Book` or a `CancelToken` without relying on uniffi's internal
//! object-pointer layout.
//!
//! The registry is platform-neutral (no JNI dependency): it lives in `core` so the Windows host
//! build and `cargo test --workspace` compile it, and the uniffi exports in `engine.rs` reference it
//! on every platform.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, MutexGuard, PoisonError};

use crate::engine::{Book, CancelToken};

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(PoisonError::into_inner)
}

/// A grow-only registry mapping non-zero, never-reused `u64` handles to `Arc<T>`.
pub struct HandleRegistry<T> {
    next: AtomicU64,
    entries: Mutex<Vec<(u64, Arc<T>)>>,
}

impl<T> HandleRegistry<T> {
    pub fn new() -> Self {
        HandleRegistry {
            next: AtomicU64::new(1),
            entries: Mutex::new(Vec::new()),
        }
    }

    /// Stores `value` and returns a non-zero handle that will never be handed out again.
    pub fn insert(&self, value: Arc<T>) -> u64 {
        loop {
            let handle = self.next.fetch_add(1, Ordering::Relaxed);
            // 0 is reserved ("no handle"); skip it. The collision re-check below only becomes
            // reachable after a full 64-bit counter wraparound, which cannot happen in practice.
            if handle == 0 {
                continue;
            }
            let mut entries = lock(&self.entries);
            if entries.iter().any(|(h, _)| *h == handle) {
                continue;
            }
            entries.push((handle, value));
            return handle;
        }
    }

    /// Clones the `Arc` behind `handle` under a short lock, or returns `None` if it is not (or no
    /// longer) registered.
    pub fn get(&self, handle: u64) -> Option<Arc<T>> {
        if handle == 0 {
            return None;
        }
        let entries = lock(&self.entries);
        entries
            .iter()
            .find(|(h, _)| *h == handle)
            .map(|(_, v)| Arc::clone(v))
    }

    /// Removes `handle`, returning whether it was present.
    pub fn remove(&self, handle: u64) -> bool {
        let mut entries = lock(&self.entries);
        if let Some(pos) = entries.iter().position(|(h, _)| *h == handle) {
            entries.swap_remove(pos);
            true
        } else {
            false
        }
    }
}

/// Registered books, keyed by the handle returned from [`Book::native_handle`].
pub static BOOKS: LazyLock<HandleRegistry<Book>> = LazyLock::new(HandleRegistry::new);
/// Registered cancel tokens, keyed by the handle returned from the JNI `newCancelToken`.
pub static CANCELS: LazyLock<HandleRegistry<CancelToken>> = LazyLock::new(HandleRegistry::new);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_are_unique_and_nonzero() {
        let reg = HandleRegistry::<u32>::new();
        let mut seen = Vec::new();
        for i in 0..1000u32 {
            let h = reg.insert(Arc::new(i));
            assert_ne!(h, 0, "handle must be non-zero");
            assert!(!seen.contains(&h), "handle {h} was reused");
            seen.push(h);
        }
    }

    #[test]
    fn get_after_remove_is_none() {
        let reg = HandleRegistry::<u32>::new();
        let h = reg.insert(Arc::new(7));
        assert!(reg.get(h).is_some());
        assert!(reg.remove(h));
        assert!(reg.get(h).is_none());
    }

    #[test]
    fn remove_twice_is_false() {
        let reg = HandleRegistry::<u32>::new();
        let h = reg.insert(Arc::new(9));
        assert!(reg.remove(h));
        assert!(!reg.remove(h));
    }

    #[test]
    fn get_is_none_for_unknown_and_zero() {
        let reg = HandleRegistry::<u32>::new();
        assert!(reg.get(0).is_none());
        assert!(reg.get(42).is_none());
    }

    #[test]
    fn remove_keeps_other_entries() {
        let reg = HandleRegistry::<u32>::new();
        let a = reg.insert(Arc::new(1));
        let b = reg.insert(Arc::new(2));
        assert!(reg.remove(a));
        assert!(reg.get(a).is_none());
        assert_eq!(reg.get(b).map(|v| *v), Some(2));
    }
}
