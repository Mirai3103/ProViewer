//! ProViewer engine. Phase 0: only a version export to prove the uniffi toolchain.

uniffi::setup_scaffolding!();

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
