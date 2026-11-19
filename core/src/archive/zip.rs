//! ZIP / CBZ archive as a page source.

use super::{
    check_entry_size, is_page_name, page_at, read_bounded, read_prefix_bounded, PageEntry,
    PageSource,
};
use crate::error::{EngineError, Result};
use crate::limits::Limits;
use crate::natsort::natural_path_cmp;
use ::zip::read::ZipFileEntry;
use ::zip::result::ZipError;
use ::zip::ZipArchive;
use std::io::{Read, Seek};
use std::sync::{Mutex, MutexGuard, PoisonError};

trait ReadSeek: Read + Seek + Send {}
impl<T: Read + Seek + Send> ReadSeek for T {}

/// Page source over a ZIP archive. The archive is behind a mutex that is held only while
/// bytes are read out of it, never while they are decoded.
pub struct ZipSource {
    archive: Mutex<ZipArchive<Box<dyn ReadSeek>>>,
    entries: Vec<PageEntry>,
    limits: Limits,
}

fn zip_error(e: ZipError) -> EngineError {
    match e {
        ZipError::Io(io) => EngineError::from(io),
        ZipError::UnsupportedArchive(m) => EngineError::UnsupportedFormat {
            message: m.to_string(),
        },
        ZipError::CompressionMethodNotSupported(m) => EngineError::UnsupportedFormat {
            message: format!("unsupported ZIP compression method {m}"),
        },
        other => EngineError::Corrupt {
            message: other.to_string(),
        },
    }
}

/// Entry name: valid UTF-8 is taken as is (many tools write UTF-8 without setting the flag),
/// anything else goes through the crate's legacy-codepage decoding. `\` becomes `/`.
fn entry_name(e: &ZipFileEntry<'_>) -> String {
    let raw = e.name_raw();
    let name = match std::str::from_utf8(raw) {
        Ok(s) => s.to_owned(),
        Err(_) => e
            .name()
            .map(|n| n.into_owned())
            .unwrap_or_else(|_| String::from_utf8_lossy(raw).into_owned()),
    };
    name.replace('\\', "/")
}

impl ZipSource {
    pub fn from_reader<R: Read + Seek + Send + 'static>(
        reader: R,
        limits: Limits,
    ) -> Result<ZipSource> {
        let boxed: Box<dyn ReadSeek> = Box::new(reader);
        let archive = ZipArchive::new(boxed).map_err(zip_error)?;

        let count = archive.len() as u64;
        if count > u64::from(limits.max_entries) {
            return Err(EngineError::LimitExceeded {
                limit: "entries".into(),
                value: count,
                max: u64::from(limits.max_entries),
            });
        }

        let mut total = 0u64;
        let mut entries = Vec::new();
        for index in 0..archive.len() {
            let e = archive.by_index_data(index).map_err(zip_error)?;
            let declared_size = e.size();
            total = total.saturating_add(declared_size);
            if total > limits.max_total_bytes {
                return Err(EngineError::LimitExceeded {
                    limit: "total_bytes".into(),
                    value: total,
                    max: limits.max_total_bytes,
                });
            }
            if !e.is_file() || declared_size == 0 {
                continue;
            }
            let name = entry_name(&e);
            if is_page_name(&name) {
                entries.push(PageEntry {
                    name,
                    source_index: index,
                    declared_size,
                });
            }
        }
        if entries.is_empty() {
            return Err(EngineError::NoPages);
        }
        entries.sort_by(|a, b| natural_path_cmp(&a.name, &b.name));
        Ok(ZipSource {
            archive: Mutex::new(archive),
            entries,
            limits,
        })
    }

    fn lock(&self) -> MutexGuard<'_, ZipArchive<Box<dyn ReadSeek>>> {
        // A panic while the lock was held cannot leave the archive in a state we rely on.
        self.archive.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

impl PageSource for ZipSource {
    fn pages(&self) -> &[PageEntry] {
        &self.entries
    }

    fn read(&self, page: usize) -> Result<Vec<u8>> {
        let e = page_at(&self.entries, page)?;
        check_entry_size(e.declared_size, &self.limits)?;
        // Inflating and the CRC check happen under the lock (they are the "byte read"); the
        // caller decodes the returned bytes after the guard is gone.
        let mut archive = self.lock();
        let file = archive.by_index(e.source_index).map_err(zip_error)?;
        read_bounded(file, e.declared_size, self.limits.max_entry_bytes)
    }

    fn read_prefix(&self, page: usize, max_len: usize) -> Result<Vec<u8>> {
        let e = page_at(&self.entries, page)?;
        let mut archive = self.lock();
        let file = archive.by_index(e.source_index).map_err(zip_error)?;
        read_prefix_bounded(file, e.declared_size, max_len)
    }
}

#[cfg(test)]
mod tests {
    use super::super::testutil::build_zip;
    use super::*;
    use crate::error::EngineError;
    use ::zip::CompressionMethod;
    use std::io::Cursor;

    fn open(bytes: Vec<u8>, limits: Limits) -> Result<ZipSource> {
        ZipSource::from_reader(Cursor::new(bytes), limits)
    }

    fn names(src: &ZipSource) -> Vec<&str> {
        src.pages().iter().map(|p| p.name.as_str()).collect()
    }

    /// Rewrites a field in every header that starts with `sig`: `offset` bytes after the
    /// signature, `len` bytes wide, using `f` on the current little-endian value.
    fn patch_headers(
        bytes: &mut [u8],
        sig: [u8; 4],
        offset: usize,
        len: usize,
        f: impl Fn(u64) -> u64,
    ) {
        let mut i = 0;
        let mut patched = 0;
        while i + offset + len <= bytes.len() {
            if bytes[i..i + 4] == sig {
                let mut v = 0u64;
                for k in 0..len {
                    v |= (bytes[i + offset + k] as u64) << (8 * k);
                }
                let nv = f(v);
                for k in 0..len {
                    bytes[i + offset + k] = (nv >> (8 * k)) as u8;
                }
                patched += 1;
            }
            i += 1;
        }
        assert!(patched > 0, "no header with signature {sig:?} found");
    }

    const LOCAL: [u8; 4] = *b"PK\x03\x04";
    const CENTRAL: [u8; 4] = *b"PK\x01\x02";

    #[test]
    fn zip_lists_only_pages_in_natural_order() {
        let bytes = build_zip(
            &[
                ("ComicInfo.xml", b"<x/>"),
                ("__MACOSX/._001.jpg", b"junk"),
                ("._002.jpg", b"junk"),
                (".DS_Store", b"junk"),
                ("Thumbs.db", b"junk"),
                ("dir/", b""),
                ("10.jpg", b"ten"),
                ("2.jpg", b"two"),
                ("1.JPG", b"one"),
                ("empty.jpg", b""),
            ],
            CompressionMethod::Stored,
        );
        let src = open(bytes, Limits::default()).unwrap();
        assert_eq!(names(&src), ["1.JPG", "2.jpg", "10.jpg"]);
        assert_eq!(src.read(0).unwrap(), b"one");
        assert_eq!(src.read(2).unwrap(), b"ten");
    }

    #[test]
    fn backslash_separators_sort_and_normalize() {
        let bytes = build_zip(
            &[("ch1\\10.jpg", b"a"), ("ch1\\2.jpg", b"b")],
            CompressionMethod::Stored,
        );
        let src = open(bytes, Limits::default()).unwrap();
        assert_eq!(names(&src), ["ch1/2.jpg", "ch1/10.jpg"]);
    }

    #[test]
    fn utf8_name_without_flag_decodes_as_utf8() {
        let mut bytes = build_zip(&[("第3話.jpg", b"abc")], CompressionMethod::Stored);
        // General-purpose flag bit 11 (UTF-8 names) lives in the local header at +6 and the
        // central header at +8 (relative to the signature).
        patch_headers(&mut bytes, LOCAL, 6, 2, |v| v & !(1 << 11));
        patch_headers(&mut bytes, CENTRAL, 8, 2, |v| v & !(1 << 11));
        let src = open(bytes, Limits::default()).unwrap();
        assert_eq!(names(&src), ["第3話.jpg"]);
    }

    #[test]
    fn too_many_entries_is_rejected() {
        let bytes = build_zip(
            &[
                ("1.jpg", b"a"),
                ("2.jpg", b"a"),
                ("3.jpg", b"a"),
                ("4.jpg", b"a"),
            ],
            CompressionMethod::Stored,
        );
        let limits = Limits {
            max_entries: 3,
            ..Limits::default()
        };
        match open(bytes, limits) {
            Err(EngineError::LimitExceeded { limit, value, max }) => {
                assert_eq!((limit.as_str(), value, max), ("entries", 4, 3));
            }
            other => panic!("expected LimitExceeded(entries), got {:?}", other.err()),
        }
    }

    #[test]
    fn declared_total_over_limit_is_rejected() {
        let bytes = build_zip(
            &[("1.jpg", &[0u8; 8]), ("2.jpg", &[0u8; 8])],
            CompressionMethod::Stored,
        );
        let limits = Limits {
            max_total_bytes: 10,
            ..Limits::default()
        };
        match open(bytes, limits) {
            Err(EngineError::LimitExceeded { limit, max, .. }) => {
                assert_eq!((limit.as_str(), max), ("total_bytes", 10));
            }
            other => panic!("expected LimitExceeded(total_bytes), got {:?}", other.err()),
        }
    }

    #[test]
    fn entry_over_limit_fails_only_that_page() {
        let big = vec![1u8; 500];
        let small = vec![2u8; 50];
        let bytes = build_zip(
            &[("big.jpg", &big), ("small.jpg", &small)],
            CompressionMethod::Stored,
        );
        let limits = Limits {
            max_entry_bytes: 100,
            ..Limits::default()
        };
        let src = open(bytes, limits).unwrap();
        assert_eq!(names(&src), ["big.jpg", "small.jpg"]);
        match src.read(0) {
            Err(EngineError::LimitExceeded { limit, value, max }) => {
                assert_eq!((limit.as_str(), value, max), ("entry_bytes", 500, 100));
            }
            other => panic!("expected LimitExceeded(entry_bytes), got {other:?}"),
        }
        assert_eq!(src.read(1).unwrap(), small);
    }

    #[test]
    fn lying_declared_size_is_bounded() {
        let zeros = vec![0u8; 1024 * 1024];
        let mut bytes = build_zip(&[("a.jpg", &zeros)], CompressionMethod::Deflated);
        // Uncompressed size is at +22 in the local header and +24 in the central header.
        patch_headers(&mut bytes, LOCAL, 22, 4, |_| 100);
        patch_headers(&mut bytes, CENTRAL, 24, 4, |_| 100);
        let src = open(bytes, Limits::default()).unwrap();
        assert_eq!(src.pages()[0].declared_size, 100);
        match src.read(0) {
            Err(EngineError::Corrupt { .. }) => {}
            other => panic!("expected Corrupt, got {other:?}"),
        }
    }

    #[test]
    fn read_prefix_is_bounded() {
        let data: Vec<u8> = (0..1000u32).map(|i| (i % 251) as u8).collect();
        let bytes = build_zip(&[("1.jpg", &data)], CompressionMethod::Deflated);
        let src = open(bytes, Limits::default()).unwrap();
        let prefix = src.read_prefix(0, 16).unwrap();
        assert!(prefix.len() <= 16);
        assert_eq!(prefix, src.read(0).unwrap()[..prefix.len()]);
        assert_eq!(
            src.read_prefix(0, 5000).unwrap(),
            data,
            "prefix never exceeds the page"
        );
    }

    #[test]
    fn empty_archive_is_no_pages() {
        let bytes = build_zip(&[("ComicInfo.xml", b"<x/>")], CompressionMethod::Stored);
        match open(bytes, Limits::default()) {
            Err(EngineError::NoPages) => {}
            other => panic!("expected NoPages, got {:?}", other.err()),
        }
    }

    #[test]
    fn out_of_range_page_is_an_error() {
        let bytes = build_zip(&[("1.jpg", b"a")], CompressionMethod::Stored);
        let src = open(bytes, Limits::default()).unwrap();
        assert!(matches!(
            src.read(1),
            Err(EngineError::PageOutOfRange { index: 1, count: 1 })
        ));
        assert!(matches!(
            src.read_prefix(7, 4),
            Err(EngineError::PageOutOfRange { index: 7, count: 1 })
        ));
    }

    #[test]
    fn concurrent_reads_return_correct_pages() {
        let pages: Vec<Vec<u8>> = (0..8u8).map(|i| vec![i; 4096]).collect();
        let names_in: Vec<String> = (0..8).map(|i| format!("{i}.jpg")).collect();
        let entries: Vec<(&str, &[u8])> = names_in
            .iter()
            .zip(&pages)
            .map(|(n, d)| (n.as_str(), d.as_slice()))
            .collect();
        let src = std::sync::Arc::new(
            open(
                build_zip(&entries, CompressionMethod::Deflated),
                Limits::default(),
            )
            .unwrap(),
        );
        std::thread::scope(|s| {
            for (i, expected) in pages.iter().enumerate() {
                let src = src.clone();
                s.spawn(move || {
                    for _ in 0..20 {
                        assert_eq!(&src.read(i).unwrap(), expected);
                    }
                });
            }
        });
    }
}
