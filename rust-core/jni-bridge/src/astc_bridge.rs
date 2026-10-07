//! Android entry points for the shared conversion engine. Descriptors are borrowed.
use crate::{
    call,
    store::conversion,
    string,
    transcode::{AdvancedSettings, Settings},
};
use anyhow::{Context, Result};
use jni::{
    objects::{JClass, JString},
    sys::{jint, jlong, jstring},
    JNIEnv,
};
use serde::Deserialize;
use std::path::Path;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Request {
    block: String,
    quality: String,
    workers: usize,
    keep_going: bool,
    scan_only: bool,
    in_place: bool,
    #[serde(default)]
    repair_pages: bool,
    #[serde(default)]
    require_no_bc: bool,
    #[serde(default)]
    effort: Option<f32>,
    #[serde(default = "neutral_weights")]
    channel_weights: [f32; 4],
    #[serde(default)]
    alpha_weight_radius: Option<u32>,
    #[serde(default)]
    perceptual: bool,
}
fn neutral_weights() -> [f32; 4] {
    [1.0; 4]
}

fn options(text: &str) -> Result<conversion::FdOptions> {
    let request: Request = serde_json::from_str(text).context("Invalid conversion options")?;
    let (width, height) = request
        .block
        .split_once('x')
        .context("Invalid ASTC block")?;
    let settings = Settings {
        block: (width.parse()?, height.parse()?),
        quality: request.quality,
        advanced: AdvancedSettings {
            effort: request.effort,
            channel_weights: request.channel_weights,
            alpha_weight_radius: request.alpha_weight_radius,
            perceptual: request.perceptual,
        },
    };
    settings.validate()?;
    anyhow::ensure!(
        (1..=4).contains(&request.workers),
        "Android workers must be between 1 and 4"
    );
    Ok(conversion::FdOptions {
        settings,
        jobs: request.workers,
        keep_going: request.keep_going,
        dry_run: request.scan_only,
        skip_unchanged: request.in_place,
        repair_pages: request.repair_pages,
        require_no_bc: request.require_no_bc,
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_astc_AstcCore_beginBatch(
    mut env: JNIEnv,
    _: JClass,
) {
    call(&mut env, |_| conversion::begin_batch())
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_astc_AstcCore_endBatch(mut env: JNIEnv, _: JClass) {
    call(&mut env, |_| {
        conversion::end_batch();
        Ok(())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_astc_AstcCore_convertArchive(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    output: jint,
    scratch: JString,
    request: JString,
) -> jstring {
    call(&mut env, |env| {
        let scratch = string(env, &scratch)?;
        let options = options(&string(env, &request)?)?;
        let report = conversion::convert_fd(handle, output, Path::new(&scratch), options)?;
        Ok(env.new_string(report.to_string())?.into_raw())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_gtav_rpfmanager_astc_AstcCore_progress(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    call(&mut env, |env| {
        Ok(env
            .new_string(conversion::progress(handle).to_string())?
            .into_raw())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn request() -> serde_json::Value {
        json!({"block":"12x12","quality":"exhaustive","workers":2,
            "keepGoing":true,"scanOnly":false,"inPlace":false,
            "effort":75,"channelWeights":[1,2,3,4],"alphaWeightRadius":3,
            "perceptual":true,"repairPages":true,"requireNoBc":true})
    }
    #[test]
    fn android_options_preserve_advanced_and_repair_settings() {
        let options = options(&request().to_string()).unwrap();
        assert_eq!(options.settings.block, (12, 12));
        assert_eq!(
            options.settings.advanced.channel_weights,
            [1.0, 2.0, 3.0, 4.0]
        );
        assert_eq!(options.settings.advanced.effort, Some(75.0));
        assert_eq!(options.settings.advanced.alpha_weight_radius, Some(3));
        assert!(
            options.settings.advanced.perceptual && options.repair_pages && options.require_no_bc
        );
    }
    #[test]
    fn android_rejects_invalid_signed_and_advanced_parameters() {
        for (key, value) in [
            ("workers", json!(-1)),
            ("workers", json!(5)),
            ("block", json!("7x7")),
            ("effort", json!(101)),
            ("channelWeights", json!([1, 0, 1, 1])),
            ("alphaWeightRadius", json!(-1)),
        ] {
            let mut request = request();
            request[key] = value;
            assert!(options(&request.to_string()).is_err(), "{key}");
        }
    }
}
