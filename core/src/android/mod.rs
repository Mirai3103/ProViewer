//! Android-only JNI pixel path: render tiles straight into an `android.graphics.Bitmap` or an
//! `android.hardware.HardwareBuffer`, so pixels never cross the FFI boundary. Compiled only for
//! `target_os = "android"`; the host build (and `cargo test --workspace`) never sees this module.

pub mod ffi;
pub mod pixels;
