# WSL distro `proviewer` — one-time setup prompt

Paste the prompt below into a fresh Claude Code session in `E:\Code\ProViewer` (or follow it by hand). It is
separate from any implementation plan: Phase 1 Task 5 assumes this environment already exists and only checks it.

---

Set up a dedicated WSL 2 distro for the ProViewer project on this Windows 11 machine. This is environment setup
only: do not modify any file in the repository, do not commit anything.

**Hard constraints**
- Distro name `proviewer`, image `Ubuntu-26.04`, stored at `E:\WSL\proviewer`. Nothing of this distro may live on C:.
- Never touch, stop, export, or unregister the existing distros (`Ubuntu`, `kiri`) and never change the default distro.
- Do not edit the global `%USERPROFILE%\.wslconfig`.
- Every step must be idempotent: check first, skip what already exists, never reinstall or wipe.
- If a step fails, stop and report the exact command and output; do not improvise destructive fixes
  (e.g. never `wsl --unregister` without asking me).

**Steps**
1. Preflight: `wsl --version` (needs `--name`/`--location` support, WSL ≥ 2.4), `wsl -l -v`, free space on E:.
2. If `wsl -l -q` does not list `proviewer`:
   `wsl --install Ubuntu-26.04 --name proviewer --location E:\WSL\proviewer --no-launch`.
   Confirm `E:\WSL\proviewer\ext4.vhdx` exists afterwards.
3. As root (`wsl -d proviewer -u root -- …`):
   - Create user `dev` (home `/home/dev`, shell bash) if missing; passwordless sudo via `/etc/sudoers.d/dev`
     (mode 0440, validated with `visudo -cf`).
   - Write `/etc/wsl.conf` with:
     ```
     [user]
     default=dev
     [interop]
     appendWindowsPath=false
     ```
     (`appendWindowsPath=false` keeps the Windows `cargo.exe`/`rustc.exe` off the Linux PATH, so Linux builds can
     never silently use the Windows toolchain.)
   - `apt-get update && apt-get install -y build-essential pkg-config curl ca-certificates git cmake ninja-build`.
     (`cmake` and `ninja-build` are needed because the `turbojpeg` crate builds libjpeg-turbo from C source during
     `cargo build`, which the fuzz targets compile too.)
   - Then `wsl --terminate proviewer` so `wsl.conf` takes effect.
4. As `dev` (`wsl -d proviewer -- bash -lc '…'`):
   - Install rustup if missing: `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable`.
   - `rustup toolchain install nightly --profile minimal`.
   - `cargo install cargo-fuzz --locked` (skip if `cargo fuzz --version` already works).
5. Verification (all must pass):
   - `wsl -d proviewer -- whoami` → `dev`.
   - `wsl -d proviewer -- bash -lc 'command -v cargo'` → a path under `/home/dev/.cargo/bin` (not `/mnt/c/...`).
   - `wsl -d proviewer -- bash -lc 'rustc +nightly -V && cargo fuzz --version && cc --version | head -1'`.
   - libFuzzer smoke test in a throwaway dir inside the distro (not in the repo):
     `cd /tmp && rm -rf pvfuzz-smoke && cargo new --lib pvfuzz-smoke && cd pvfuzz-smoke && cargo fuzz init && cargo +nightly fuzz run fuzz_target_1 -- -max_total_time=10`
     → exits 0 with libFuzzer `Done` output. Then delete `/tmp/pvfuzz-smoke`.
   - Repo is reachable: `wsl -d proviewer -- ls /mnt/e/Code/ProViewer/Cargo.toml`.
   - `wsl -l -v` still shows `Ubuntu` and `kiri` unchanged, and the default distro unchanged.
6. Report: WSL version, distro path, Ubuntu release (`lsb_release -d`), stable + nightly `rustc` versions,
   `cargo fuzz --version`, `cc --version`, and the smoke-test result.

**Removal (only if I ask):** `wsl --unregister proviewer` deletes the distro and its disk.
