use std::path::PathBuf;

/// `gen-corpus [out_dir]` writes the full corpus (default `corpus/generated`).
/// `gen-corpus --fuzz-seeds <dir>` writes the small fuzz seed corpus instead.
fn main() -> anyhow::Result<()> {
    let mut args = std::env::args_os().skip(1);
    let first = args.next();
    let written = if first.as_deref() == Some(std::ffi::OsStr::new("--fuzz-seeds")) {
        let dir = args
            .next()
            .map(PathBuf::from)
            .ok_or_else(|| anyhow::anyhow!("usage: gen-corpus --fuzz-seeds <dir>"))?;
        gen_corpus::generate_fuzz_seeds(&dir)?
    } else {
        let out = first
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("corpus/generated"));
        gen_corpus::generate_all(&out)?
    };
    for p in written {
        println!("{}", p.display());
    }
    Ok(())
}
