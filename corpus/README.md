# Test corpus

Fixed inputs for tests and benchmarks. **Never commit real manga or comics.**

- `corpus/generated/` — synthetic archives produced by `tools/gen-corpus` (gitignored, regenerate any time):
  `cargo run --release -p gen-corpus -- corpus/generated`
- `corpus/local/` — put your own real files here for manual testing (gitignored, never committed).

Generated files are deterministic: no RNG anywhere, every pixel is a pure function of its
coordinates and the generator arguments, so two runs produce byte-identical archives.

| File | Content | Purpose |
|---|---|---|
| `screentone.cbz` | 6 pages 1600×2400, periods 2.0–6.0 px, JPEG | moiré / downscale quality |
| `webtoon-long.cbz` | 3 strips 800×24000, JPEG | vertical scroll, tiling past max texture size |
| `huge-single.cbz` | 1 image 12000×8000, JPEG | memory peak, zoom |
| `double-spread.cbz` | 4 pages: 2 portrait 1600×2400 + 2 spreads 3200×2400 (one spread PNG) | spread splitting |
| `mixed-small.cbz` | 20 pages 800×1200, JPEG | paged swipe |
| `many-pages.cbz` | 500 pages 800×1200, JPEG | open-time benchmark (spec §7) |
| `formats-and-names.cbz` | 5 entries, stored order: `ComicInfo.xml`, `vol1/page10.webp`, `vol1/page2.png`, `vol1/page1.jpg`, `第3話.jpg` | WebP/gray PNG decode, natural sort, nested dirs, UTF-8 names, non-image skip |

Entry names elsewhere: `001.jpg`, `002.jpg`, … (`.png` where PNG).
