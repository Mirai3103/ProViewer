//! JNI exports for `com.proviewer.app.engine.NativePixels`.
//!
//! Each export resolves the book/cancel handles from the registries in [`crate::handles`], then
//! calls [`Book::render_tile`] straight into the pixels of a locked `Bitmap` or `AHardwareBuffer`.
//! Every export runs inside [`EnvUnowned::with_env`]'s `catch_unwind`, so a panic is caught and
//! rethrown to Kotlin as a [`NativeRenderException`] with kind `"Panic"` rather than unwinding
//! across the FFI boundary.

use std::mem::MaybeUninit;
use std::os::raw::c_void;
use std::sync::Arc;

use jni::objects::{JObject, JString, JThrowable};
use jni::sys::{jint, jlong};
use jni::{jni_mangle, jni_sig, jni_str, Env, EnvUnowned, JValue, Outcome};

use crate::engine::{Book, CancelToken, TileRequest};
use crate::error::EngineError;
use crate::resize::Rect;

use super::ffi;

/// Turn a panic payload into a readable string (the same treatment jni-rs uses).
fn panic_message(payload: Box<dyn std::any::Any + Send>) -> String {
    match payload.downcast::<&'static str>() {
        Ok(s) => (*s).to_string(),
        Err(p) => match p.downcast::<String>() {
            Ok(s) => *s,
            Err(_) => "non-string panic payload".to_string(),
        },
    }
}

/// The failure kinds the pixel path can report to Kotlin. `Engine` carries the original
/// `EngineError` (kind = variant name); `InvalidHandle` covers an unknown or released book/cancel
/// handle.
enum RenderError {
    Engine(EngineError),
    InvalidHandle(u64),
}

impl From<EngineError> for RenderError {
    fn from(e: EngineError) -> Self {
        RenderError::Engine(e)
    }
}

impl From<jni::errors::Error> for RenderError {
    fn from(e: jni::errors::Error) -> Self {
        // Satisfies `with_env`'s `E: From<jni::errors::Error>` bound. The render closures below use
        // the raw NDK externs (not jni-rs fallible calls), so this branch is not reached in practice.
        RenderError::Engine(EngineError::Io {
            message: format!("JNI error: {e}"),
        })
    }
}

fn invalid(message: impl Into<String>) -> RenderError {
    RenderError::Engine(EngineError::InvalidRequest {
        message: message.into(),
    })
}

fn engine_error_kind(e: &EngineError) -> &'static str {
    match e {
        EngineError::Io { .. } => "Io",
        EngineError::UnsupportedFormat { .. } => "UnsupportedFormat",
        EngineError::Corrupt { .. } => "Corrupt",
        EngineError::LimitExceeded { .. } => "LimitExceeded",
        EngineError::NoPages => "NoPages",
        EngineError::PageOutOfRange { .. } => "PageOutOfRange",
        EngineError::InvalidRequest { .. } => "InvalidRequest",
        EngineError::Cancelled => "Cancelled",
        EngineError::NotSeekable => "NotSeekable",
    }
}

fn render_error_kind_detail(e: RenderError) -> (&'static str, String) {
    match e {
        RenderError::Engine(e) => (engine_error_kind(&e), e.to_string()),
        RenderError::InvalidHandle(h) => ("InvalidHandle", format!("handle {h} is not registered")),
    }
}

/// Throws a new `com.proviewer.app.engine.NativeRenderException(kind, detail)`.
fn throw_native_render_exception(
    env: &mut Env<'_>,
    kind: &str,
    detail: &str,
) -> jni::errors::Result<()> {
    let class = env.find_class(jni_str!("com/proviewer/app/engine/NativeRenderException"))?;
    let kind_j = JString::from_str(env, kind)?;
    let detail_j = JString::from_str(env, detail)?;
    let obj = env.new_object(
        &class,
        jni_sig!("(Ljava/lang/String;Ljava/lang/String;)V"),
        &[JValue::from(&kind_j), JValue::from(&detail_j)],
    )?;
    let throwable = JThrowable::cast_local(env, obj)?;
    env.throw(throwable)
}

/// Runs `f` in a fresh `Env`, catching panics via `with_env`; any `RenderError` or panic is mapped
/// to a thrown `NativeRenderException` and the default value is returned.
fn catch_and_throw<'local, T: Default>(
    env: &mut EnvUnowned<'local>,
    f: impl FnOnce(&mut Env<'local>) -> Result<T, RenderError>,
) -> T {
    let outcome = env.with_env(f);
    match outcome.into_outcome() {
        Outcome::Ok(t) => t,
        Outcome::Err(e) => {
            let (kind, detail) = render_error_kind_detail(e);
            let _ = env.with_env(|env| throw_native_render_exception(env, kind, &detail));
            T::default()
        }
        Outcome::Panic(p) => {
            let detail = panic_message(p);
            let _ = env.with_env(|env| throw_native_render_exception(env, "Panic", &detail));
            T::default()
        }
    }
}

fn u32_arg(v: jint) -> Result<u32, RenderError> {
    u32::try_from(v).map_err(|_| invalid("negative argument"))
}

/// Resolves the book and cancel-token handles. `cancel == 0` means "no token" (a fresh token).
fn resolve(book: jlong, cancel: jlong) -> Result<(Arc<Book>, CancelToken), RenderError> {
    let book_handle = book as u64;
    let book = crate::handles::BOOKS
        .get(book_handle)
        .ok_or(RenderError::InvalidHandle(book_handle))?;
    let cancel_token = if cancel == 0 {
        CancelToken::new()
    } else {
        let h = cancel as u64;
        crate::handles::CANCELS
            .get(h)
            .map(|t| (*t).clone())
            .ok_or(RenderError::InvalidHandle(h))?
    };
    Ok((book, cancel_token))
}

/// Unlocks a locked `AHardwareBuffer` on every exit path, including a panic (the guard drops
/// during unwind). It is a C call, so no `Env` is needed.
struct HardwareBufferLock {
    buffer: *mut ffi::AHardwareBuffer,
}

impl Drop for HardwareBufferLock {
    fn drop(&mut self) {
        if !self.buffer.is_null() {
            unsafe { ffi::AHardwareBuffer_unlock(self.buffer, std::ptr::null_mut()) };
        }
    }
}

/// Unlocks a locked `Bitmap` on every exit path, including a panic (the guard drops during
/// unwind). The raw `JNIEnv` and `jobject` stay valid for the whole native-method frame.
struct BitmapLock {
    env: *mut jni::sys::JNIEnv,
    bitmap: jni::sys::jobject,
}

impl Drop for BitmapLock {
    fn drop(&mut self) {
        unsafe { ffi::AndroidBitmap_unlockPixels(self.env, self.bitmap) };
    }
}

/// `NativePixels.renderToBitmap(book, page, x, y, w, h, bitmap, cancel)`: output size = bitmap size,
/// format must be `RGBA_8888`, stride from `AndroidBitmapInfo`.
#[jni_mangle("com.proviewer.app.engine.NativePixels", "renderToBitmap")]
pub fn render_to_bitmap<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    book: jlong,
    page: jint,
    x: jint,
    y: jint,
    w: jint,
    h: jint,
    bitmap: JObject<'local>,
    cancel: jlong,
) {
    catch_and_throw(&mut env, |env| -> Result<(), RenderError> {
        let (book, cancel_token) = resolve(book, cancel)?;
        let region = Rect {
            x: u32_arg(x)?,
            y: u32_arg(y)?,
            width: u32_arg(w)?,
            height: u32_arg(h)?,
        };

        let mut info = MaybeUninit::<ffi::AndroidBitmapInfo>::zeroed();
        let rc = unsafe {
            ffi::AndroidBitmap_getInfo(env.get_raw(), bitmap.as_raw(), info.as_mut_ptr())
        };
        if rc != ffi::ANDROID_BITMAP_RESULT_SUCCESS {
            return Err(invalid("AndroidBitmap_getInfo failed"));
        }
        let info = unsafe { info.assume_init() };
        if info.format != ffi::ANDROID_BITMAP_FORMAT_RGBA_8888 {
            return Err(invalid("bitmap format must be RGBA_8888"));
        }

        let req = TileRequest {
            page: u32_arg(page)?,
            region,
            out_width: info.width,
            out_height: info.height,
        };

        let mut addr: *mut c_void = std::ptr::null_mut();
        let rc = unsafe {
            ffi::AndroidBitmap_lockPixels(env.get_raw(), bitmap.as_raw(), &mut addr)
        };
        if rc != ffi::ANDROID_BITMAP_RESULT_SUCCESS || addr.is_null() {
            return Err(invalid("AndroidBitmap_lockPixels failed"));
        }
        let _guard = BitmapLock {
            env: env.get_raw(),
            bitmap: bitmap.as_raw(),
        };
        let dst_stride = info.stride as usize;
        let dst_len = dst_stride.saturating_mul(info.height as usize);
        let dst = unsafe { std::slice::from_raw_parts_mut(addr as *mut u8, dst_len) };
        book.render_tile(&req, dst, dst_stride, &cancel_token)
            .map_err(RenderError::from)
    });
}

/// `NativePixels.renderToHardwareBuffer(book, page, x, y, w, h, buffer, cancel)`: format must be
/// `R8G8B8A8_UNORM` with CPU-write usage, stride = `desc.stride * 4`, lock with `CPU_WRITE_OFTEN`.
#[jni_mangle("com.proviewer.app.engine.NativePixels", "renderToHardwareBuffer")]
pub fn render_to_hardware_buffer<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    book: jlong,
    page: jint,
    x: jint,
    y: jint,
    w: jint,
    h: jint,
    buffer: JObject<'local>,
    cancel: jlong,
) {
    catch_and_throw(&mut env, |env| -> Result<(), RenderError> {
        let (book, cancel_token) = resolve(book, cancel)?;
        let region = Rect {
            x: u32_arg(x)?,
            y: u32_arg(y)?,
            width: u32_arg(w)?,
            height: u32_arg(h)?,
        };

        let ahb =
            unsafe { ffi::AHardwareBuffer_fromHardwareBuffer(env.get_raw(), buffer.as_raw()) };
        if ahb.is_null() {
            return Err(invalid("not a HardwareBuffer"));
        }

        let mut desc = MaybeUninit::<ffi::AHardwareBufferDesc>::zeroed();
        unsafe { ffi::AHardwareBuffer_describe(ahb as *const ffi::AHardwareBuffer, desc.as_mut_ptr()) };
        let desc = unsafe { desc.assume_init() };
        if desc.format != ffi::AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM {
            return Err(invalid("HardwareBuffer format must be R8G8B8A8_UNORM"));
        }
        if desc.usage & ffi::AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN == 0 {
            return Err(invalid("HardwareBuffer must have CPU-write usage"));
        }

        let stride = (desc.stride as usize).saturating_mul(4);
        let req = TileRequest {
            page: u32_arg(page)?,
            region,
            out_width: desc.width,
            out_height: desc.height,
        };

        let mut addr: *mut c_void = std::ptr::null_mut();
        let rc = unsafe {
            ffi::AHardwareBuffer_lock(
                ahb,
                ffi::AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                -1,
                std::ptr::null(),
                &mut addr,
            )
        };
        if rc != 0 || addr.is_null() {
            return Err(invalid("AHardwareBuffer_lock failed"));
        }
        let _guard = HardwareBufferLock { buffer: ahb };
        let dst_len = stride.saturating_mul(desc.height as usize);
        let dst = unsafe { std::slice::from_raw_parts_mut(addr as *mut u8, dst_len) };
        book.render_tile(&req, dst, stride, &cancel_token)
            .map_err(RenderError::from)
    });
}

/// `NativePixels.newCancelToken(): Long` — registers a fresh token and returns its non-zero handle.
#[jni_mangle("com.proviewer.app.engine.NativePixels", "newCancelToken")]
pub fn new_cancel_token<'local>(mut env: EnvUnowned<'local>, _this: JObject<'local>) -> jlong {
    catch_and_throw(&mut env, |_env| -> Result<jlong, RenderError> {
        let handle = crate::handles::CANCELS.insert(Arc::new(CancelToken::new()));
        Ok(handle as jlong)
    })
}

/// `NativePixels.cancel(token: Long)` — sets the token's flag. `token == 0` is a no-op.
#[jni_mangle("com.proviewer.app.engine.NativePixels", "cancel")]
pub fn cancel_token<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    token: jlong,
) {
    catch_and_throw(&mut env, |_env| -> Result<(), RenderError> {
        if token == 0 {
            return Ok(());
        }
        let h = token as u64;
        let t = crate::handles::CANCELS
            .get(h)
            .ok_or(RenderError::InvalidHandle(h))?;
        t.cancel();
        Ok(())
    });
}

/// `NativePixels.releaseCancelToken(token: Long)` — unregisters the token. `token == 0` is a no-op.
#[jni_mangle("com.proviewer.app.engine.NativePixels", "releaseCancelToken")]
pub fn release_cancel_token<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    token: jlong,
) {
    catch_and_throw(&mut env, |_env| -> Result<(), RenderError> {
        if token == 0 {
            return Ok(());
        }
        let h = token as u64;
        if crate::handles::CANCELS.remove(h) {
            Ok(())
        } else {
            Err(RenderError::InvalidHandle(h))
        }
    });
}
