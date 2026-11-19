mod common;

use proviewer_core::archive::{open_path, FolderSource, PageSource};
use proviewer_core::limits::Limits;
use std::io::Read;

#[test]
fn formats_and_names_corpus_order() {
    let src = open_path(
        &common::small_corpus().join("formats-and-names.cbz"),
        Limits::default(),
    )
    .unwrap();
    let names: Vec<&str> = src.pages().iter().map(|p| p.name.as_str()).collect();
    assert_eq!(
        names,
        [
            "vol1/page1.jpg",
            "vol1/page2.png",
            "vol1/page10.webp",
            "第3話.jpg"
        ]
    );
}

#[test]
fn folder_and_zip_agree() {
    let cbz = common::small_corpus().join("mixed-small.cbz");
    let zip_src = open_path(&cbz, Limits::default()).unwrap();

    let dir = tempfile::tempdir().unwrap();
    let mut archive = zip::ZipArchive::new(std::fs::File::open(&cbz).unwrap()).unwrap();
    for i in 0..archive.len() {
        let mut f = archive.by_index(i).unwrap();
        let mut data = Vec::new();
        f.read_to_end(&mut data).unwrap();
        let out = dir.path().join(f.name().unwrap().as_ref());
        std::fs::create_dir_all(out.parent().unwrap()).unwrap();
        std::fs::write(out, data).unwrap();
    }
    let folder_src = FolderSource::open(dir.path(), Limits::default()).unwrap();

    assert_eq!(zip_src.pages().len(), 20);
    let zip_names: Vec<&str> = zip_src.pages().iter().map(|p| p.name.as_str()).collect();
    let folder_names: Vec<&str> = folder_src.pages().iter().map(|p| p.name.as_str()).collect();
    assert_eq!(zip_names, folder_names);
    for i in 0..zip_src.pages().len() {
        assert_eq!(
            zip_src.pages()[i].declared_size,
            folder_src.pages()[i].declared_size
        );
        assert_eq!(
            zip_src.read(i).unwrap(),
            folder_src.read(i).unwrap(),
            "page {i}"
        );
    }
}
