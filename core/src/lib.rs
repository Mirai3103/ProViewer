//! ProViewer engine: archive and image decoding, tile rendering, caching and prefetch, exported over uniffi.

uniffi::setup_scaffolding!();

pub mod archive;
pub mod cache;
pub mod decode;
pub mod engine;
pub mod error;
pub mod handles;
pub mod limits;
pub mod natsort;
pub mod resize;
pub mod scheduler;

#[cfg(target_os = "android")]
pub mod android;

/// Version of the engine crate, exposed to Kotlin as `engineVersion()`.
#[uniffi::export]
pub fn engine_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engine_version_matches_cargo_pkg() {
        assert_eq!(engine_version(), env!("CARGO_PKG_VERSION"));
    }
}
