//! JNI bridge from the Android benchmark `CorpusGenerator` to `gen-corpus`.
//!
//! This cdylib (`libproviewer_benchgen.so`) is packaged only in the `debug` and `benchmark`
//! build types; the release APK never links or ships it (enforced by
//! `scripts/check-release-clean.ps1`). The measured core `libproviewer_core.so` is therefore
//! the same build in every variant.

use std::path::Path;

use jni::objects::{JObject, JObjectArray, JString};
use jni::sys::jfloat;
use jni::{jni_mangle, EnvUnowned};

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

/// `CorpusGenerator.nativeVersion(): String` — the `gen-corpus` crate version.
#[jni_mangle("com.proviewer.app.bench.CorpusGenerator", "nativeVersion")]
pub fn native_version<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
) -> JString<'local> {
    env.with_env(|env| -> jni::errors::Result<JString<'local>> {
        JString::from_str(env, gen_corpus::VERSION)
    })
    .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

/// `CorpusGenerator.nativeGenerate(dir, scale, names): String?` — null on success, the error
/// text otherwise. Panics are caught by [`EnvUnowned::with_env`] and returned as text instead of
/// crossing the FFI boundary.
#[jni_mangle("com.proviewer.app.bench.CorpusGenerator", "nativeGenerate")]
pub fn native_generate<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    dir: JString<'local>,
    scale: jfloat,
    names: JObjectArray<'local, JString<'local>>,
) -> JString<'local> {
    let outcome = env.with_env(|env| -> jni::errors::Result<Option<String>> {
        let dir_str = dir.try_to_string(env)?;
        let n = names.len(env)?;
        let mut v = Vec::with_capacity(n);
        for i in 0..n {
            let s = names.get_element(env, i)?;
            v.push(s.try_to_string(env)?);
        }
        let refs: Vec<&str> = v.iter().map(|s| s.as_str()).collect();
        match gen_corpus::generate_named(Path::new(&dir_str), scale, &refs) {
            Ok(_) => Ok(None),
            Err(e) => Ok(Some(format!("{e:#}"))),
        }
    });

    // Re-acquire an Env (with_env drops its internal guard before returning) to build the
    // result string for the error/panic paths.
    let mut guard: jni::vm::AttachGuard<'local> =
        unsafe { jni::vm::AttachGuard::from_unowned(env.as_raw()) };
    let env = guard.borrow_env_mut();

    let message = match outcome.into_outcome() {
        jni::Outcome::Ok(None) => None,
        jni::Outcome::Ok(Some(m)) => Some(m),
        jni::Outcome::Err(e) => Some(format!("{e}")),
        jni::Outcome::Panic(p) => Some(format!("panic: {}", panic_message(p))),
    };

    match message {
        None => JString::default(),
        Some(m) => JString::from_str(env, m).unwrap_or_else(|_| JString::default()),
    }
}
