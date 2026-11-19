//! Engine error type shared across the crate and exposed over uniffi.

/// Every failure the engine can report. Variants carry plain data so they cross the FFI boundary.
#[derive(Debug, Clone, PartialEq, thiserror::Error, uniffi::Error)]
pub enum EngineError {
    #[error("I/O error: {message}")]
    Io { message: String },
    #[error("unsupported format: {message}")]
    UnsupportedFormat { message: String },
    #[error("corrupt data: {message}")]
    Corrupt { message: String },
    #[error("limit exceeded: {limit} is {value}, max {max}")]
    LimitExceeded { limit: String, value: u64, max: u64 },
    #[error("no pages")]
    NoPages,
    #[error("page {index} out of range (count {count})")]
    PageOutOfRange { index: u32, count: u32 },
    #[error("invalid request: {message}")]
    InvalidRequest { message: String },
    #[error("cancelled")]
    Cancelled,
    #[error("file descriptor is not seekable")]
    NotSeekable,
}

impl From<std::io::Error> for EngineError {
    fn from(e: std::io::Error) -> Self {
        EngineError::Io {
            message: e.to_string(),
        }
    }
}

pub type Result<T> = std::result::Result<T, EngineError>;

#[cfg(test)]
mod tests {
    use super::*;
    use std::io;

    #[test]
    fn io_error_converts_to_io_variant() {
        let e = EngineError::from(io::Error::new(io::ErrorKind::NotFound, "x"));
        match e {
            EngineError::Io { message } => assert!(message.contains('x')),
            other => panic!("expected Io, got {other:?}"),
        }
    }

    #[test]
    fn not_seekable_display() {
        assert_eq!(
            EngineError::NotSeekable.to_string(),
            "file descriptor is not seekable"
        );
    }
}
