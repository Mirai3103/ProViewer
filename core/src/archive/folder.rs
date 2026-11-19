//! A directory of images as a page source.

use super::{
    check_entry_size, is_junk_component, is_page_name, page_at, read_bounded, read_prefix_bounded,
    PageEntry, PageSource,
};
use crate::error::{EngineError, Result};
use crate::limits::Limits;
use crate::natsort::natural_path_cmp;
use std::fs::File;
use std::path::{Path, PathBuf};

/// Page source over a directory tree.
pub struct FolderSource {
    entries: Vec<PageEntry>,
    files: Vec<PathBuf>,
    limits: Limits,
}

/// Directory levels below the opened directory that are searched (`a/b/c/d/1.jpg` is found,
/// `a/b/c/d/e/1.jpg` is not).
const MAX_DEPTH: usize = 4;

struct Scan<'a> {
    limits: &'a Limits,
    /// Regular files seen, page or not (the folder equivalent of the ZIP entry count).
    seen: u64,
    total: u64,
    found: Vec<(String, PathBuf, u64)>,
}

impl Scan<'_> {
    fn walk(&mut self, dir: &Path, prefix: &str, depth: usize) -> Result<()> {
        for item in std::fs::read_dir(dir)? {
            let item = item?;
            // `DirEntry::file_type` does not follow symlinks, so links are neither files nor dirs here.
            let kind = item.file_type()?;
            let Some(file_name) = item.file_name().to_str().map(str::to_owned) else {
                continue; // not valid Unicode: cannot be a '/'-joined page name
            };
            let name = format!("{prefix}{file_name}");
            if kind.is_dir() {
                if depth < MAX_DEPTH && !is_junk_component(&file_name) {
                    self.walk(&item.path(), &format!("{name}/"), depth + 1)?;
                }
            } else if kind.is_file() {
                self.seen += 1;
                if self.seen > u64::from(self.limits.max_entries) {
                    return Err(EngineError::LimitExceeded {
                        limit: "entries".into(),
                        value: self.seen,
                        max: u64::from(self.limits.max_entries),
                    });
                }
                let size = item.metadata()?.len();
                self.total = self.total.saturating_add(size);
                if self.total > self.limits.max_total_bytes {
                    return Err(EngineError::LimitExceeded {
                        limit: "total_bytes".into(),
                        value: self.total,
                        max: self.limits.max_total_bytes,
                    });
                }
                if size > 0 && is_page_name(&name) {
                    self.found.push((name, item.path(), size));
                }
            }
        }
        Ok(())
    }
}

impl FolderSource {
    pub fn open(dir: &Path, limits: Limits) -> Result<FolderSource> {
        let mut scan = Scan {
            limits: &limits,
            seen: 0,
            total: 0,
            found: Vec::new(),
        };
        scan.walk(dir, "", 0)?;
        if scan.found.is_empty() {
            return Err(EngineError::NoPages);
        }
        let mut found = scan.found;
        found.sort_by(|a, b| natural_path_cmp(&a.0, &b.0));
        let mut entries = Vec::with_capacity(found.len());
        let mut files = Vec::with_capacity(found.len());
        for (i, (name, path, declared_size)) in found.into_iter().enumerate() {
            entries.push(PageEntry {
                name,
                source_index: i,
                declared_size,
            });
            files.push(path);
        }
        Ok(FolderSource {
            entries,
            files,
            limits,
        })
    }
}

impl PageSource for FolderSource {
    fn pages(&self) -> &[PageEntry] {
        &self.entries
    }

    fn read(&self, page: usize) -> Result<Vec<u8>> {
        let e = page_at(&self.entries, page)?;
        check_entry_size(e.declared_size, &self.limits)?;
        let file = File::open(&self.files[e.source_index])?;
        read_bounded(file, e.declared_size, self.limits.max_entry_bytes)
    }

    fn read_prefix(&self, page: usize, max_len: usize) -> Result<Vec<u8>> {
        let e = page_at(&self.entries, page)?;
        let file = File::open(&self.files[e.source_index])?;
        read_prefix_bounded(file, e.declared_size, max_len)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(root: &Path, rel: &str, data: &[u8]) {
        let p = root.join(rel);
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(p, data).unwrap();
    }

    #[test]
    fn folder_is_recursive_filtered_and_depth_limited() {
        let dir = tempfile::tempdir().unwrap();
        for rel in [
            "2.jpg",
            "10.jpg",
            ".hidden.jpg",
            "sub/1.png",
            "a/b/c/d/e/deep.jpg",
        ] {
            write(dir.path(), rel, b"x");
        }
        write(dir.path(), "notes.txt", b"x");
        let src = FolderSource::open(dir.path(), Limits::default()).unwrap();
        let names: Vec<&str> = src.pages().iter().map(|p| p.name.as_str()).collect();
        assert_eq!(names, ["2.jpg", "10.jpg", "sub/1.png"]);
    }

    #[test]
    fn folder_allows_depth_four_but_not_five() {
        let dir = tempfile::tempdir().unwrap();
        write(dir.path(), "a/b/c/d/ok.jpg", b"x");
        write(dir.path(), "a/b/c/d/e/no.jpg", b"x");
        let src = FolderSource::open(dir.path(), Limits::default()).unwrap();
        let names: Vec<&str> = src.pages().iter().map(|p| p.name.as_str()).collect();
        assert_eq!(names, ["a/b/c/d/ok.jpg"]);
    }

    #[test]
    fn folder_reads_bytes_prefix_and_enforces_limits() {
        let dir = tempfile::tempdir().unwrap();
        write(dir.path(), "1.jpg", &[7u8; 50]);
        write(dir.path(), "2.jpg", &[8u8; 500]);
        write(dir.path(), "empty.jpg", b"");
        let limits = Limits {
            max_entry_bytes: 100,
            ..Limits::default()
        };
        let src = FolderSource::open(dir.path(), limits).unwrap();
        assert_eq!(src.pages().len(), 2, "zero-byte file is skipped");
        assert_eq!(src.read(0).unwrap(), vec![7u8; 50]);
        assert_eq!(src.read_prefix(0, 16).unwrap(), vec![7u8; 16]);
        match src.read(1) {
            Err(crate::error::EngineError::LimitExceeded { limit, .. }) => {
                assert_eq!(limit, "entry_bytes")
            }
            other => panic!("expected LimitExceeded, got {other:?}"),
        }
        match src.read(2) {
            Err(crate::error::EngineError::PageOutOfRange { index: 2, count: 2 }) => {}
            other => panic!("expected PageOutOfRange, got {other:?}"),
        }
    }

    #[test]
    fn folder_enforces_entry_and_total_limits() {
        let dir = tempfile::tempdir().unwrap();
        for i in 1..=4 {
            write(dir.path(), &format!("{i}.jpg"), &[1u8; 10]);
        }
        let few = Limits {
            max_entries: 3,
            ..Limits::default()
        };
        assert!(matches!(
            FolderSource::open(dir.path(), few),
            Err(crate::error::EngineError::LimitExceeded { ref limit, max: 3, .. }) if limit == "entries"
        ));
        let small = Limits {
            max_total_bytes: 30,
            ..Limits::default()
        };
        assert!(matches!(
            FolderSource::open(dir.path(), small),
            Err(crate::error::EngineError::LimitExceeded { ref limit, .. }) if limit == "total_bytes"
        ));
        let empty = tempfile::tempdir().unwrap();
        assert!(matches!(
            FolderSource::open(empty.path(), Limits::default()),
            Err(crate::error::EngineError::NoPages)
        ));
    }

    #[cfg(unix)]
    #[test]
    fn folder_does_not_follow_symlinks() {
        let outside = tempfile::tempdir().unwrap();
        write(outside.path(), "1.jpg", b"x");
        let dir = tempfile::tempdir().unwrap();
        write(dir.path(), "2.jpg", b"x");
        std::os::unix::fs::symlink(outside.path(), dir.path().join("link")).unwrap();
        std::os::unix::fs::symlink(outside.path().join("1.jpg"), dir.path().join("3.jpg")).unwrap();
        let src = FolderSource::open(dir.path(), Limits::default()).unwrap();
        let names: Vec<&str> = src.pages().iter().map(|p| p.name.as_str()).collect();
        assert_eq!(names, ["2.jpg"]);
    }
}
