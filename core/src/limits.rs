//! Resource limits applied to untrusted archives and images before anything is allocated.

#[derive(uniffi::Record, Clone, Copy, Debug, PartialEq, Eq)]
pub struct Limits {
    pub max_entries: u32,
    pub max_entry_bytes: u64,
    pub max_total_bytes: u64,
    pub max_image_pixels: u64,
    pub max_image_dimension: u32,
}

impl Default for Limits {
    /// The pixel limit fits `huge-single` (96 MP) and keeps the largest RGB allocation near 300 MB.
    fn default() -> Self {
        Limits {
            max_entries: 100_000,
            max_entry_bytes: 256 * 1024 * 1024,
            max_total_bytes: 16 * 1024 * 1024 * 1024,
            max_image_pixels: 100_000_000,
            max_image_dimension: 65_535,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_spec() {
        let l = Limits::default();
        assert_eq!(l.max_entries, 100_000);
        assert_eq!(l.max_entry_bytes, 256 * 1024 * 1024);
        assert_eq!(l.max_total_bytes, 16 * 1024 * 1024 * 1024);
        assert_eq!(l.max_image_pixels, 100_000_000);
        assert_eq!(l.max_image_dimension, 65_535);
    }
}
