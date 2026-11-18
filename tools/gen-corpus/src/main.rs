use std::path::PathBuf;

fn main() -> anyhow::Result<()> {
    let out: PathBuf = std::env::args_os()
        .nth(1)
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("corpus/generated"));
    for p in gen_corpus::generate_all(&out)? {
        println!("{}", p.display());
    }
    Ok(())
}
