//! Frame statistics from Perfetto FrameTimeline output.
//!
//! A frame is janky when SurfaceFlinger's `jank_type` is anything other than `None` or
//! `Buffer Stuffing` (buffer stuffing alone is back-pressure, not a missed frame).
//! Frame-duration percentiles are informational only (spec §7).

use thiserror::Error;

#[derive(Debug, Clone, PartialEq)]
pub struct Frame {
    pub dur_ms: f64,
    pub jank_type: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct FrameStats {
    pub frames: usize,
    pub janky: usize,
    pub janky_pct: f64,
    pub p50_ms: f64,
    pub p90_ms: f64,
    pub p95_ms: f64,
    pub p99_ms: f64,
}

#[derive(Debug, Error, PartialEq)]
pub enum ParseError {
    #[error("missing required column `{0}`")]
    MissingColumn(&'static str),
    #[error("row {row}: invalid value `{value}` in column `{column}`")]
    BadValue { row: usize, column: &'static str, value: String },
}

/// Frame budget in milliseconds at the given refresh rate (informational).
pub fn frame_budget_ms(hz: f64) -> f64 {
    1000.0 / hz
}

/// Split one CSV line, honouring double quotes (and `""` escapes inside them).
fn split_csv_line(line: &str) -> Vec<String> {
    let mut fields = Vec::new();
    let mut cur = String::new();
    let mut in_quotes = false;
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '"' if in_quotes && chars.peek() == Some(&'"') => {
                cur.push('"');
                chars.next();
            }
            '"' => in_quotes = !in_quotes,
            ',' if !in_quotes => fields.push(std::mem::take(&mut cur)),
            _ => cur.push(c),
        }
    }
    fields.push(cur);
    fields
}

/// Parse the CSV printed by `trace_processor_shell -q`. Columns are located by header name
/// (`dur` in nanoseconds, `jank_type`); order and quoting do not matter.
pub fn parse_frames_csv(csv: &str) -> Result<Vec<Frame>, ParseError> {
    let mut lines = csv.lines().map(str::trim_end).filter(|l| !l.trim().is_empty());
    let header = split_csv_line(lines.next().ok_or(ParseError::MissingColumn("dur"))?);
    let find = |name: &'static str| {
        header
            .iter()
            .position(|h| h.trim() == name)
            .ok_or(ParseError::MissingColumn(name))
    };
    let dur_col = find("dur")?;
    let jank_col = find("jank_type")?;

    let mut frames = Vec::new();
    for (i, line) in lines.enumerate() {
        let row = split_csv_line(line);
        let dur_raw = row.get(dur_col).map(|s| s.trim()).unwrap_or("");
        let dur_ns: f64 = dur_raw.parse().map_err(|_| ParseError::BadValue {
            row: i + 1,
            column: "dur",
            value: dur_raw.to_string(),
        })?;
        let jank_type = row.get(jank_col).map(|s| s.trim().to_string()).unwrap_or_default();
        frames.push(Frame { dur_ms: dur_ns / 1e6, jank_type });
    }
    Ok(frames)
}

/// Effective refresh rate the app's frames were presented at, from the `ts` column (ns) of the
/// frames CSV: `1e9 / median(delta between consecutive frames)`. Deltas of 50 ms or more are idle
/// gaps and are ignored. Catches a panel or per-app rate change that the display's idle
/// `mActiveModeId` cannot show. `Ok(None)` when there are fewer than two usable frames.
/// (`expected_frame_timeline_slice.dur` is NOT the vsync period: it is the app's deadline.)
pub fn effective_hz(csv: &str) -> Result<Option<f64>, ParseError> {
    let mut lines = csv.lines().filter(|l| !l.trim().is_empty());
    let header = split_csv_line(lines.next().ok_or(ParseError::MissingColumn("ts"))?);
    let col = header
        .iter()
        .position(|h| h.trim() == "ts")
        .ok_or(ParseError::MissingColumn("ts"))?;
    let mut ts = Vec::new();
    for (i, line) in lines.enumerate() {
        let raw = split_csv_line(line).get(col).map(|s| s.trim().to_string()).unwrap_or_default();
        let v: f64 = raw.parse().map_err(|_| ParseError::BadValue { row: i + 1, column: "ts", value: raw })?;
        ts.push(v);
    }
    ts.sort_by(|a, b| a.total_cmp(b));
    let mut deltas: Vec<f64> = ts.windows(2).map(|w| w[1] - w[0]).filter(|d| *d > 0.0 && *d < 50e6).collect();
    if deltas.is_empty() {
        return Ok(None);
    }
    deltas.sort_by(|a, b| a.total_cmp(b));
    Ok(Some(1e9 / deltas[(deltas.len() - 1) / 2]))
}

/// True if any comma-separated jank type is neither `None` nor `Buffer Stuffing`.
pub fn is_janky(jank_type: &str) -> bool {
    jank_type
        .split(',')
        .map(str::trim)
        .filter(|p| !p.is_empty())
        .any(|p| p != "None" && p != "Buffer Stuffing")
}

fn nearest_rank(sorted: &[f64], pct: f64) -> f64 {
    if sorted.is_empty() {
        return 0.0;
    }
    let rank = ((pct / 100.0) * sorted.len() as f64).ceil() as usize;
    sorted[rank.clamp(1, sorted.len()) - 1]
}

pub fn summarize(frames: &[Frame]) -> FrameStats {
    let janky = frames.iter().filter(|f| is_janky(&f.jank_type)).count();
    let janky_pct = if frames.is_empty() { 0.0 } else { 100.0 * janky as f64 / frames.len() as f64 };
    let mut durs: Vec<f64> = frames.iter().map(|f| f.dur_ms).collect();
    durs.sort_by(|a, b| a.total_cmp(b));
    FrameStats {
        frames: frames.len(),
        janky,
        janky_pct,
        p50_ms: nearest_rank(&durs, 50.0),
        p90_ms: nearest_rank(&durs, 90.0),
        p95_ms: nearest_rank(&durs, 95.0),
        p99_ms: nearest_rank(&durs, 99.0),
    }
}

/// Parse the active refresh rate out of `dumpsys display`: find `mActiveModeId=<id>`, then the
/// mode record with `id=<id>` and return its `fps=` value.
pub fn parse_active_refresh_hz(dumpsys_display: &str) -> Option<f64> {
    let key = "mActiveModeId=";
    let start = dumpsys_display.find(key)? + key.len();
    let active: String = dumpsys_display[start..].chars().take_while(|c| c.is_ascii_digit()).collect();
    if active.is_empty() {
        return None;
    }
    let needle = format!("id={active},");
    let mut from = 0;
    while let Some(rel) = dumpsys_display[from..].find(&needle) {
        let at = from + rel;
        from = at + needle.len();
        // `id=` must be a standalone field (`{id=`, ` id=`), not the tail of `mActiveModeId=`.
        let standalone = dumpsys_display[..at]
            .chars()
            .next_back()
            .is_none_or(|c| !c.is_ascii_alphanumeric());
        if !standalone {
            continue;
        }
        let rest = &dumpsys_display[from..];
        let record_end = rest.find('\n').unwrap_or(rest.len());
        if let Some(p) = rest[..record_end].find("fps=") {
            let num: String = rest[p + 4..]
                .chars()
                .take_while(|c| c.is_ascii_digit() || *c == '.')
                .collect();
            if let Ok(hz) = num.parse() {
                return Some(hz);
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    const FRAMES: &str = include_str!("../tests/fixtures/frames.csv");
    const DISPLAY: &str = include_str!("../tests/fixtures/display.txt");

    #[test]
    fn frame_budget_for_185hz() {
        assert!((frame_budget_ms(185.0) - 5.405).abs() < 0.001);
    }

    #[test]
    fn parse_frames_csv_reads_by_header() {
        let frames = parse_frames_csv(FRAMES).unwrap();
        assert_eq!(frames.len(), 6);
        assert!((frames[0].dur_ms - 4.8).abs() < 1e-9);
        assert_eq!(frames[3].jank_type, "App Deadline Missed");
        assert_eq!(frames[4].jank_type, "App Deadline Missed, Buffer Stuffing");

        // Same data, columns reordered and unquoted.
        let reordered = "jank_type,layer_name,dur,ts\n\
            None,l,4800000,1\n\
            None,l,5100000,2\n\
            Buffer Stuffing,l,5300000,3\n\
            App Deadline Missed,l,12000000,4\n\
            \"App Deadline Missed, Buffer Stuffing\",l,11000000,5\n\
            None,l,4900000,6\n";
        assert_eq!(parse_frames_csv(reordered).unwrap(), frames);
    }

    #[test]
    fn parse_frames_csv_header_only_is_empty() {
        let frames = parse_frames_csv("\"ts\",\"dur\",\"jank_type\"\n").unwrap();
        assert!(frames.is_empty());
    }

    #[test]
    fn parse_frames_csv_errors_without_required_column() {
        let err = parse_frames_csv("ts,dur\n1,2\n").unwrap_err();
        assert_eq!(err, ParseError::MissingColumn("jank_type"));
    }

    #[test]
    fn is_janky_classification() {
        assert!(!is_janky("None"));
        assert!(!is_janky("Buffer Stuffing"));
        assert!(!is_janky(""));
        assert!(is_janky("App Deadline Missed"));
        assert!(is_janky("App Deadline Missed, Buffer Stuffing"));
        assert!(is_janky("Prediction Error"));
    }

    #[test]
    fn summarize_counts_jank_and_percentiles() {
        let s = summarize(&parse_frames_csv(FRAMES).unwrap());
        assert_eq!(s.frames, 6);
        assert_eq!(s.janky, 2);
        assert!((s.janky_pct - 33.333).abs() < 0.01);

        let frames: Vec<Frame> = (1..=100)
            .map(|i| Frame { dur_ms: i as f64, jank_type: "None".to_string() })
            .collect();
        let s = summarize(&frames);
        assert_eq!((s.p50_ms, s.p90_ms, s.p95_ms, s.p99_ms), (50.0, 90.0, 95.0, 99.0));
        assert_eq!(s.janky, 0);
    }

    #[test]
    fn effective_hz_is_median_frame_spacing() {
        // Fixture frames are 5.405 ms apart -> ~185 Hz.
        let hz = effective_hz(FRAMES).unwrap().unwrap();
        assert!((hz - 185.0).abs() < 0.1, "{hz}");

        // 120 Hz spacing with one long idle gap (ignored) and one dropped frame.
        let csv = "ts,dur
0,1
8333333,1
16666666,1
25000000,1
1000000000,1
1008333333,1
1025000000,1
";
        let hz = effective_hz(csv).unwrap().unwrap();
        assert!((hz - 120.0).abs() < 0.1, "{hz}");

        assert_eq!(effective_hz("\"ts\"
5
").unwrap(), None);
        assert_eq!(effective_hz("dur
1
2
").unwrap_err(), ParseError::MissingColumn("ts"));
    }

    #[test]
    fn real_device_fixtures_parse() {
        // Captured on the reference device (OnePlus CPH2767, Android 16) at 120 Hz.
        let frames = parse_frames_csv(include_str!("../tests/fixtures/frames-real.csv")).unwrap();
        assert!(frames.len() > 100);
        let hz = parse_active_refresh_hz(include_str!("../tests/fixtures/display-real.txt")).unwrap();
        assert!((hz - 120.0).abs() < 0.5, "{hz}");
        let eff = effective_hz(include_str!("../tests/fixtures/frames-real.csv")).unwrap().unwrap();
        assert!((eff - 120.0).abs() < 6.0, "{eff}");
    }

    #[test]
    fn parse_active_refresh_real_android_dump() {
        // Verbatim lines from `adb shell dumpsys display` on the Android 17 emulator (60 Hz).
        // TODO(device): add display-real.txt from the reference device (185 Hz) in Phase 0 step 6.
        let real = include_str!("../tests/fixtures/display-emulator.txt");
        let hz = parse_active_refresh_hz(real).unwrap();
        assert!((hz - 60.0).abs() < 0.01, "{hz}");
    }

    #[test]
    fn parse_active_refresh_picks_active_mode() {
        assert_eq!(parse_active_refresh_hz(DISPLAY), Some(185.0));
        assert_eq!(parse_active_refresh_hz(""), None);
    }
}
