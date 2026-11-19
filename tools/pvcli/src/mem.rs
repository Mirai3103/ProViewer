//! Peak resident memory of this process.

/// Peak resident set of this process in KiB: `VmHWM` from `/proc/self/status` on Linux and Android,
/// `PeakWorkingSetSize` on Windows. `None` where unsupported or unreadable.
pub fn peak_rss_kb() -> Option<u64> {
    imp()
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn imp() -> Option<u64> {
    parse_vmhwm(&std::fs::read_to_string("/proc/self/status").ok()?)
}

/// `VmHWM:   123456 kB` -> 123456.
#[cfg(any(target_os = "linux", target_os = "android", test))]
fn parse_vmhwm(status: &str) -> Option<u64> {
    let line = status.lines().find(|l| l.starts_with("VmHWM:"))?;
    line.trim_start_matches("VmHWM:")
        .trim()
        .strip_suffix("kB")?
        .trim()
        .parse()
        .ok()
}

#[cfg(windows)]
fn imp() -> Option<u64> {
    use windows_sys::Win32::System::ProcessStatus::{
        K32GetProcessMemoryInfo, PROCESS_MEMORY_COUNTERS,
    };
    use windows_sys::Win32::System::Threading::GetCurrentProcess;
    // SAFETY: `counters` is a plain-data struct that lives for the call; `cb` carries its size as the API
    // requires; GetCurrentProcess returns a pseudo handle that needs no closing.
    unsafe {
        let mut counters: PROCESS_MEMORY_COUNTERS = std::mem::zeroed();
        counters.cb = std::mem::size_of::<PROCESS_MEMORY_COUNTERS>() as u32;
        if K32GetProcessMemoryInfo(GetCurrentProcess(), &mut counters, counters.cb) == 0 {
            return None;
        }
        Some(counters.PeakWorkingSetSize as u64 / 1024)
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android", windows)))]
fn imp() -> Option<u64> {
    None
}

#[cfg(test)]
mod tests {
    use super::parse_vmhwm;

    #[test]
    fn parses_vmhwm_line() {
        let status = "Name:\tpvcli\nVmPeak:\t  999 kB\nVmHWM:\t  123456 kB\nVmRSS:\t 100 kB\n";
        assert_eq!(parse_vmhwm(status), Some(123456));
        assert_eq!(parse_vmhwm("Name:\tx\n"), None);
        assert_eq!(parse_vmhwm("VmHWM:\tabc kB\n"), None);
    }
}
