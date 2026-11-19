# Passed to CMake through .cargo/config.toml ([env] CMAKE_TOOLCHAIN_FILE_<target>) for the C dependencies
# that Cargo builds on the Windows host (libjpeg-turbo via turbojpeg-sys).
#
# libjpeg-turbo defaults to the static MSVC runtime (/MT, /MTd), but Rust always links the release dynamic runtime
# (/MD), even in debug builds. Mixing them produces linker warning LNK4098 and two C heaps in one process.
# WITH_CRT_DLL=ON stops libjpeg-turbo from forcing the static runtime, and CMAKE_MSVC_RUNTIME_LIBRARY picks /MD
# for every build type (CMake's default for Debug would be /MDd).
set(WITH_CRT_DLL ON CACHE BOOL "Link libjpeg-turbo with the C run-time DLL" FORCE)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreadedDLL" CACHE STRING "MSVC runtime, matches Rust's /MD" FORCE)
