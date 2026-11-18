use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (cmd, path) = match (args.first().map(String::as_str), args.get(1)) {
        (Some(c), Some(p)) => (c, p),
        _ => {
            eprintln!("usage: framestat frames <frames.csv> | framestat refresh <display.txt>");
            return ExitCode::from(2);
        }
    };
    let text = match std::fs::read_to_string(path) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("cannot read {path}: {e}");
            return ExitCode::from(2);
        }
    };
    match cmd {
        "frames" => match framestat::parse_frames_csv(&text) {
            Ok(frames) => {
                let s = framestat::summarize(&frames);
                println!(
                    "{},{},{:.2},{:.3},{:.3},{:.3},{:.3}",
                    s.frames, s.janky, s.janky_pct, s.p50_ms, s.p90_ms, s.p95_ms, s.p99_ms
                );
                ExitCode::SUCCESS
            }
            Err(e) => {
                eprintln!("{e}");
                ExitCode::from(2)
            }
        },
        "period" => match framestat::effective_hz(&text) {
            Ok(Some(hz)) => {
                println!("{hz:.2}");
                ExitCode::SUCCESS
            }
            Ok(None) => {
                eprintln!("no expected-frame rows");
                ExitCode::from(2)
            }
            Err(e) => {
                eprintln!("{e}");
                ExitCode::from(2)
            }
        },
        "refresh" =>match framestat::parse_active_refresh_hz(&text) {
            Some(hz) => {
                println!("{hz}");
                ExitCode::SUCCESS
            }
            None => {
                eprintln!("no active refresh rate found");
                ExitCode::from(2)
            }
        },
        other => {
            eprintln!("unknown command `{other}`");
            ExitCode::from(2)
        }
    }
}
