use anyhow::Result;
use jni::{
    objects::{JClass, JString},
    sys::{jboolean, jbyteArray, jint, jlong, jstring},
    JNIEnv,
};
use std::panic::{catch_unwind, AssertUnwindSafe};
#[cfg(feature = "conversion")]
mod astc_encoder;
pub mod audio;
pub mod dds;
#[cfg(any(test, feature = "fixtures"))]
pub mod fixtures;
pub mod mesh;
mod store;
#[cfg(feature = "conversion")]
pub mod transcode;
#[cfg(feature = "conversion")]
pub use store::conversion;

// JNI never unwinds into ART. Errors remain exceptions until the repository maps
// them to a localized state; no empty successful response conceals a failure.
fn call<T: Default>(env: &mut JNIEnv, work: impl FnOnce(&mut JNIEnv) -> Result<T>) -> T {
    let result = catch_unwind(AssertUnwindSafe(|| work(env)));
    match result {
        Ok(Ok(value)) => value,
        error => {
            let message = match error {
                Ok(Err(e)) => format!("{e:#}"),
                _ => "Invalid resource data".into(),
            };
            let _ = env.throw_new("java/io/IOException", message);
            T::default()
        }
    }
}
fn string(env: &mut JNIEnv, s: &JString) -> Result<String> {
    Ok(env.get_string(s)?.into())
}

#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_openArchive(
    mut env: JNIEnv,
    _: JClass,
    path: JString,
    name: JString,
) -> jlong {
    call(&mut env, |env| {
        store::open(&string(env, &path)?, &string(env, &name)?)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_closeArchive(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    call(&mut env, |_| {
        store::close(handle);
        Ok(())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_openArchiveFd(
    mut env: JNIEnv,
    _: JClass,
    fd: jint,
    name: JString,
) -> jlong {
    call(&mut env, |env| store::open_fd(fd, &string(env, &name)?))
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_openResourceFd(
    mut env: JNIEnv,
    _: JClass,
    fd: jint,
) -> jlong {
    call(&mut env, |_| store::open_document(fd))
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_openNestedArchive(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    path: JString,
) -> jlong {
    call(&mut env, |env| {
        store::open_nested(handle, &string(env, &path)?)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_getArchiveInfo(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    call(&mut env, |env| {
        Ok(env.new_string(store::info(handle)?.to_string())?.into_raw())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_listFiles(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    path: JString,
) -> jstring {
    call(&mut env, |env| {
        let p = string(env, &path)?;
        Ok(env
            .new_string(store::list(handle, &p)?.to_string())?
            .into_raw())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_extractFile(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    path: JString,
    output: JString,
) {
    call(&mut env, |env| {
        store::extract(handle, &string(env, &path)?, &string(env, &output)?)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_extractArchiveToDir(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    output: JString,
) -> jlong {
    call(&mut env, |env| {
        Ok(store::extract_archive_to_dir(handle, &string(env, &output)?)? as jlong)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_packDirectory(
    mut env: JNIEnv,
    _: JClass,
    input: JString,
    output_fd: jint,
) -> jlong {
    call(&mut env, |env| {
        Ok(store::pack_directory(&string(env, &input)?, output_fd)? as jlong)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_extractFileFd(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    path: JString,
    output: jint,
    cancel_handle: jlong,
) {
    call(&mut env, |env| {
        store::extract_fd(handle, &string(env, &path)?, output, cancel_handle)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_beginOperation(
    mut env: JNIEnv,
    _: JClass,
) -> jlong {
    call(&mut env, |_| Ok(store::begin_operation()))
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_finishOperation(
    mut env: JNIEnv,
    _: JClass,
    operation: jlong,
) {
    call(&mut env, |_| {
        store::finish_operation(operation);
        Ok(())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_packDirectoryCancellable(
    mut env: JNIEnv,
    _: JClass,
    input: JString,
    output: jint,
    operation: jlong,
) -> jlong {
    call(&mut env, |env| {
        Ok(store::pack_directory_cancellable(&string(env, &input)?, output, operation)? as jlong)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_getFileData(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    path: JString,
) -> jbyteArray {
    call(&mut env, |env| {
        let data = store::read(handle, &string(env, &path)?)?;
        Ok(env.byte_array_from_slice(&data)?.into_raw())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_listTextures(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    path: JString,
) -> jstring {
    call(&mut env, |env| {
        let p = string(env, &path)?;
        Ok(env
            .new_string(store::textures(handle, &p)?.to_string())?
            .into_raw())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_texturePng(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    path: JString,
    index: jint,
) -> jbyteArray {
    call(&mut env, |env| {
        let data = store::texture_png(handle, &string(env, &path)?, index as usize)?;
        Ok(env.byte_array_from_slice(&data)?.into_raw())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_previewMesh(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    path: JString,
) -> jbyteArray {
    call(&mut env, |env| {
        let p = string(env, &path)?;
        let ext = p.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
        let data = store::read(handle, &p)?;
        let mesh = mesh::parse(&data, &ext)?;
        Ok(env.byte_array_from_slice(&mesh.encode())?.into_raw())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_loadKeys(
    mut env: JNIEnv,
    _: JClass,
    path: JString,
) {
    call(&mut env, |env| store::load_keys(&string(env, &path)?))
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_clearKeys(mut env: JNIEnv, _: JClass) {
    call(&mut env, |_| {
        store::clear_keys()?;
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_editArchive(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    command: JString,
) {
    call(&mut env, |env| store::edit(handle, &string(env, &command)?))
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_editFileFd(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    path: JString,
    fd: jint,
    replace: jboolean,
    source_name: JString,
) {
    call(&mut env, |env| {
        store::edit_named_file(
            handle,
            &string(env, &path)?,
            fd,
            replace != 0,
            &string(env, &source_name)?,
        )
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_attachNestedArchive(
    mut env: JNIEnv,
    _: JClass,
    parent: jlong,
    path: JString,
    child: jlong,
) {
    call(&mut env, |env| {
        store::attach_nested(parent, &string(env, &path)?, child)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_saveArchiveFd(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    fd: jint,
) {
    call(&mut env, |_| store::save_fd(handle, fd))
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_stageArchiveFd(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    fd: jint,
) {
    call(&mut env, |_| store::stage_fd(handle, fd))
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_cancelOperation(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    call(&mut env, |_| {
        store::cancel(handle);
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_listAudio(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    path: JString,
) -> jstring {
    call(&mut env, |env| {
        let bytes = store::read(handle, &string(env, &path)?)?;
        let metadata = audio::Awc::parse(&bytes)?.metadata();
        Ok(env.new_string(metadata.to_string())?.into_raw())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_audioWav(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    path: JString,
    index: jint,
    output: JString,
) {
    call(&mut env, |env| {
        let bytes = store::read(handle, &string(env, &path)?)?;
        let audio = audio::Awc::parse(&bytes)?;
        let track = audio
            .tracks
            .get(index as usize)
            .ok_or_else(|| anyhow::anyhow!("Audio track index out of range"))?;
        let mut output = std::io::BufWriter::new(std::fs::File::create(string(env, &output)?)?);
        track.wav(&mut output)?;
        std::io::Write::flush(&mut output)?;
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_resetCancellation(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    call(&mut env, |_| {
        store::reset_cancel(handle);
        Ok(())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_copyOutputFd(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    source: jint,
    target: jint,
) {
    call(&mut env, |_| store::copy_output_fd(handle, source, target))
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_scanArchive(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    call(&mut env, |env| {
        Ok(env.new_string(store::scan(handle)?.to_string())?.into_raw())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_scanProgress(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    call(&mut env, |env| {
        Ok(env
            .new_string(store::scan_progress(handle)?.to_string())?
            .into_raw())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_RpfCore_textureSources(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    call(&mut env, |env| {
        Ok(env
            .new_string(store::texture_sources(handle)?.to_string())?
            .into_raw())
    })
}

// Android and Linux link the same conversion and preview implementation.
#[cfg(feature = "conversion")]
mod astc_bridge;
