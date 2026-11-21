//! Hand-written FFI declarations for the Android NDK APIs the pixel path needs. No `ndk-sys`: these
//! are the exact struct layouts, constants and signatures from the NDK 30 headers
//! (`<android/bitmap.h>`, `<android/hardware_buffer.h>`, `<android/hardware_buffer_jni.h>`).

use std::os::raw::{c_int, c_void};

use jni::sys::{JNIEnv, jobject};

/// `AHardwareBuffer` is an opaque handle.
#[repr(C)]
pub struct AHardwareBuffer {
    _private: [u8; 0],
}

/// `AHardwareBuffer_Desc` from `<android/hardware_buffer.h>` (NDK 30). The reserved trailing
/// fields are included so `AHardwareBuffer_describe` writes a correctly sized struct.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct AHardwareBufferDesc {
    pub width: u32,
    pub height: u32,
    pub layers: u32,
    pub format: u32,
    pub usage: u64,
    pub stride: u32,
    pub rfu0: u32,
    pub rfu1: u64,
}

/// `ARect` from `<android/rect.h>`.
#[repr(C)]
pub struct ARect {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

/// `AndroidBitmapInfo` from `<android/bitmap.h>` (NDK 30). `stride` is bytes per row.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct AndroidBitmapInfo {
    pub width: u32,
    pub height: u32,
    pub stride: u32,
    pub format: i32,
    pub flags: u32,
}

// AndroidBitmap result codes and formats.
pub const ANDROID_BITMAP_RESULT_SUCCESS: c_int = 0;
pub const ANDROID_BITMAP_FORMAT_NONE: i32 = 0;
pub const ANDROID_BITMAP_FORMAT_RGBA_8888: i32 = 1;
pub const ANDROID_BITMAP_FORMAT_RGB_565: i32 = 4;

// AHardwareBuffer format and usage flags.
pub const AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM: u32 = 1;
pub const AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN: u64 = 3 << 4;

#[link(name = "jnigraphics")]
extern "C" {
    pub fn AndroidBitmap_getInfo(
        env: *mut JNIEnv,
        jbitmap: jobject,
        info: *mut AndroidBitmapInfo,
    ) -> c_int;
    pub fn AndroidBitmap_lockPixels(
        env: *mut JNIEnv,
        jbitmap: jobject,
        addr_ptr: *mut *mut c_void,
    ) -> c_int;
    pub fn AndroidBitmap_unlockPixels(env: *mut JNIEnv, jbitmap: jobject) -> c_int;
}

#[link(name = "android")]
extern "C" {
    pub fn AHardwareBuffer_fromHardwareBuffer(
        env: *mut JNIEnv,
        hardware_buffer: jobject,
    ) -> *mut AHardwareBuffer;
}

#[link(name = "nativewindow")]
extern "C" {
    pub fn AHardwareBuffer_describe(
        buffer: *const AHardwareBuffer,
        out_desc: *mut AHardwareBufferDesc,
    );
    pub fn AHardwareBuffer_lock(
        buffer: *mut AHardwareBuffer,
        usage: u64,
        fence: i32,
        rect: *const ARect,
        out_virtual_address: *mut *mut c_void,
    ) -> c_int;
    pub fn AHardwareBuffer_unlock(buffer: *mut AHardwareBuffer, fence: *mut i32) -> c_int;
}
