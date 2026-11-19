//! Page sources: a folder or a ZIP/CBZ archive exposed as an ordered list of page byte blobs.

pub mod folder;
pub mod zip;

pub use folder::FolderSource;
pub use zip::ZipSource;

use crate::error::{EngineError, Result};
use crate::limits::Limits;
use std::fs::File;
use std::io::{ErrorKind, Read};
use std::path::Path;
use std::sync::Arc;

/// One page of a book as listed by a [`PageSource`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PageEntry {
    /// Path inside the book, always `/`-separated.
    pub name: String,
    /// Index of the entry in the underlying source (archive entry or folder file list).
    pub source_index: usize,
    /// Uncompressed size announced by the source. Untrusted, always re-checked while reading.
    pub declared_size: u64,
}

/// A book's pages in natural reading order.
pub trait PageSource: Send + Sync {
    /// Page entries only (junk such as `ComicInfo.xml` or `__MACOSX/` is already filtered).
    fn pages(&self) -> &[PageEntry];
    /// Whole bytes of page `page` (an index into [`PageSource::pages`]).
    fn read(&self, page: usize) -> Result<Vec<u8>>;
    /// At most the first `max_len` bytes of page `page`, for cheap format and size sniffing.
    fn read_prefix(&self, page: usize, max_len: usize) -> Result<Vec<u8>>;
}

const SEPARATORS: [char; 2] = ['/', '\\'];
/// Start of a ZIP local file header, and of the end-of-central-directory record of an empty ZIP.
const ZIP_LOCAL_MAGIC: &[u8] = b"PK\x03\x04";
const ZIP_EMPTY_MAGIC: &[u8] = b"PK\x05\x06";

/// Hidden entries (`.DS_Store`, AppleDouble `._001.jpg`, `.git`) and macOS `__MACOSX` resource forks.
fn is_junk_component(component: &str) -> bool {
    component.starts_with('.') || component == "__MACOSX"
}

/// True for names that look like a page image: a known extension and no hidden or
/// `__MACOSX` path component. Accepts both `/` and `\` separators.
pub fn is_page_name(name: &str) -> bool {
    if name.split(SEPARATORS).any(is_junk_component) {
        return false;
    }
    let file = name.rsplit(SEPARATORS).next().unwrap_or("");
    match file.rsplit_once('.') {
        Some((_, ext)) => ["jpg", "jpeg", "jfif", "png", "webp"]
            .iter()
            .any(|e| ext.eq_ignore_ascii_case(e)),
        None => false,
    }
}

/// Maps an I/O error raised while reading page bytes. Data-level failures (bad CRC, truncated
/// stream, bad deflate data) are corruption, not environment problems.
fn read_error(e: std::io::Error) -> EngineError {
    match e.kind() {
        ErrorKind::InvalidData | ErrorKind::UnexpectedEof => EngineError::Corrupt {
            message: e.to_string(),
        },
        ErrorKind::Unsupported => EngineError::UnsupportedFormat {
            message: e.to_string(),
        },
        _ => EngineError::from(e),
    }
}

/// Reads exactly `declared` bytes from `reader` without ever trusting `declared` for more than
/// the allocation cap. Reading goes through `take(declared + 1)`, so a source that holds more
/// bytes than announced is detected after one extra byte and reported as corrupt.
/// Callers must have checked `declared <= max_entry_bytes`.
fn read_bounded(reader: impl Read, declared: u64, max_entry_bytes: u64) -> Result<Vec<u8>> {
    let cap =
        usize::try_from(declared.min(max_entry_bytes)).map_err(|_| EngineError::LimitExceeded {
            limit: "entry_bytes".into(),
            value: declared,
            max: max_entry_bytes,
        })?;
    let mut buf = Vec::new();
    buf.try_reserve_exact(cap).map_err(|e| EngineError::Io {
        message: format!("cannot allocate {cap} bytes: {e}"),
    })?;
    reader
        .take(declared.saturating_add(1))
        .read_to_end(&mut buf)
        .map_err(read_error)?;
    if buf.len() as u64 != declared {
        return Err(EngineError::Corrupt {
            message: format!("entry declared {declared} bytes but holds {}", buf.len()),
        });
    }
    Ok(buf)
}

/// Reads at most `max_len` bytes (and never more than `declared`).
fn read_prefix_bounded(reader: impl Read, declared: u64, max_len: usize) -> Result<Vec<u8>> {
    let want = declared.min(max_len as u64);
    let mut buf = Vec::new();
    reader
        .take(want)
        .read_to_end(&mut buf)
        .map_err(read_error)?;
    Ok(buf)
}

fn check_entry_size(declared: u64, limits: &Limits) -> Result<()> {
    if declared > limits.max_entry_bytes {
        return Err(EngineError::LimitExceeded {
            limit: "entry_bytes".into(),
            value: declared,
            max: limits.max_entry_bytes,
        });
    }
    Ok(())
}

fn page_at(entries: &[PageEntry], page: usize) -> Result<&PageEntry> {
    entries.get(page).ok_or(EngineError::PageOutOfRange {
        index: page as u32,
        count: entries.len() as u32,
    })
}

/// Opens a directory, or a ZIP/CBZ file recognised by its magic bytes (not its extension).
pub fn open_path(path: &Path, limits: Limits) -> Result<Arc<dyn PageSource>> {
    if path.is_dir() {
        return Ok(Arc::new(FolderSource::open(path, limits)?));
    }
    let mut file = File::open(path)?;
    let mut magic = Vec::with_capacity(4);
    (&mut file).take(4).read_to_end(&mut magic)?;
    if magic == ZIP_LOCAL_MAGIC || magic == ZIP_EMPTY_MAGIC {
        Ok(Arc::new(ZipSource::from_reader(file, limits)?))
    } else {
        Err(EngineError::UnsupportedFormat {
            message: format!(
                "{} is neither a folder nor a ZIP/CBZ archive",
                path.display()
            ),
        })
    }
}

#[cfg(test)]
pub(crate) mod testutil {
    use ::zip::write::SimpleFileOptions;
    use ::zip::{CompressionMethod, ZipWriter};
    use std::io::{Cursor, Write};

    /// Builds a ZIP in memory. An entry whose name ends in `/` becomes a directory entry.
    pub fn build_zip(entries: &[(&str, &[u8])], method: CompressionMethod) -> Vec<u8> {
        let mut w = ZipWriter::new(Cursor::new(Vec::new()));
        let opts = SimpleFileOptions::default().compression_method(method);
        for (name, data) in entries {
            if name.ends_with('/') {
                w.add_directory(*name, opts).unwrap();
            } else {
                w.start_file(*name, opts).unwrap();
                w.write_all(data).unwrap();
            }
        }
        w.finish().unwrap().into_inner()
    }
}

#[cfg(test)]
mod tests {
    use super::testutil::build_zip;
    use super::*;
    use crate::error::EngineError;
    use ::zip::CompressionMethod;

    #[test]
    fn page_name_filter() {
        assert!(is_page_name("1.jpg"));
        assert!(is_page_name("a/b/C.PNG"));
        assert!(is_page_name("x.jfif"));
        assert!(is_page_name("x.WebP"));
        assert!(is_page_name("ch1\\2.jpeg"));
        assert!(!is_page_name("ComicInfo.xml"));
        assert!(!is_page_name("Thumbs.db"));
        assert!(!is_page_name(".DS_Store"));
        assert!(!is_page_name("._001.jpg"));
        assert!(!is_page_name("__MACOSX/001.jpg"));
        assert!(!is_page_name("a/.hidden/1.jpg"));
        assert!(!is_page_name("dir/"));
        assert!(!is_page_name("jpg"));
    }

    #[test]
    fn open_path_rejects_non_zip_file() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("notes.txt");
        std::fs::write(&p, b"definitely not a zip").unwrap();
        match open_path(&p, Limits::default()) {
            Err(EngineError::UnsupportedFormat { .. }) => {}
            Err(other) => panic!("expected UnsupportedFormat, got {other:?}"),
            Ok(_) => panic!("expected UnsupportedFormat, got a source"),
        }
    }

    #[test]
    fn open_path_detects_zip_without_extension() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("book.bin");
        std::fs::write(
            &p,
            build_zip(&[("1.jpg", b"abc")], CompressionMethod::Stored),
        )
        .unwrap();
        let src = open_path(&p, Limits::default()).unwrap();
        assert_eq!(src.pages().len(), 1);
        assert_eq!(src.read(0).unwrap(), b"abc");
    }

    #[test]
    fn open_path_opens_directories() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("1.jpg"), b"abc").unwrap();
        let src = open_path(dir.path(), Limits::default()).unwrap();
        assert_eq!(src.pages()[0].name, "1.jpg");
    }
}
