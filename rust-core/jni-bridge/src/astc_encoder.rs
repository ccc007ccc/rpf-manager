//! Single-thread ASTC contexts owned by one transcoder. Use the public C API so
//! advanced settings never depend on astcenc-rs's private Rust struct layout.
use crate::transcode::Settings;
use anyhow::{ensure, Context, Result};
use astcenc_sys as astc;
use std::{
    ffi::{c_void, CStr},
    mem::MaybeUninit,
    ptr::NonNull,
};

fn check(status: astc::astcenc_error, operation: &str) -> Result<()> {
    if status == astc::astcenc_error_ASTCENC_SUCCESS {
        return Ok(());
    }
    // The library owns this static, NUL-terminated error string.
    let message = unsafe {
        let pointer = astc::astcenc_get_error_string(status);
        if pointer.is_null() {
            "unknown error".into()
        } else {
            CStr::from_ptr(pointer).to_string_lossy().into_owned()
        }
    };
    anyhow::bail!("ASTC {operation}: {message} ({status})")
}

fn configuration(settings: &Settings, srgb: bool) -> Result<astc::astcenc_config> {
    settings.validate()?;
    let mut flags = 0;
    if settings.advanced.alpha_weight_radius.is_some() {
        flags |= astc::ASTCENC_FLG_USE_ALPHA_WEIGHT;
    }
    if settings.advanced.perceptual {
        flags |= astc::ASTCENC_FLG_USE_PERCEPTUAL;
    }
    let mut config = MaybeUninit::<astc::astcenc_config>::uninit();
    // config_init initializes every public field on success.
    check(
        unsafe {
            astc::astcenc_config_init(
                if srgb {
                    astc::astcenc_profile_ASTCENC_PRF_LDR_SRGB
                } else {
                    astc::astcenc_profile_ASTCENC_PRF_LDR
                },
                settings.block.0,
                settings.block.1,
                1,
                settings.effective_effort()?,
                flags,
                config.as_mut_ptr(),
            )
        },
        "configuration",
    )?;
    let mut config = unsafe { config.assume_init() };
    // Perceptual mode establishes RGB luminance weights in config_init. User
    // values multiply these, so neutral [1;4] preserves the selected metric.
    let mut weights = [
        config.cw_r_weight,
        config.cw_g_weight,
        config.cw_b_weight,
        config.cw_a_weight,
    ];
    for (weight, multiplier) in weights.iter_mut().zip(settings.advanced.channel_weights) {
        *weight *= multiplier;
    }
    // astcenc 0.2.0's public context allocator floors each weight at max/1000.
    // Apply the same floor explicitly so reports describe the actual metric.
    let minimum = weights.into_iter().fold(0.0f32, f32::max) / 1000.0;
    for weight in &mut weights {
        *weight = weight.max(minimum);
    }
    [
        config.cw_r_weight,
        config.cw_g_weight,
        config.cw_b_weight,
        config.cw_a_weight,
    ] = weights;
    if let Some(radius) = settings.advanced.alpha_weight_radius {
        config.a_scale_radius = radius;
    }
    Ok(config)
}

pub(crate) fn effective_channel_weights(settings: &Settings) -> Result<[f32; 4]> {
    let config = configuration(settings, false)?;
    Ok([
        config.cw_r_weight,
        config.cw_g_weight,
        config.cw_b_weight,
        config.cw_a_weight,
    ])
}

pub(crate) struct Encoder {
    context: NonNull<astc::astcenc_context>,
    block: (u32, u32),
}
impl Encoder {
    pub(crate) fn new(settings: &Settings, srgb: bool) -> Result<Self> {
        let config = configuration(settings, srgb)?;
        let mut context = std::ptr::null_mut();
        // Parallelism is between resources. A context always receives thread
        // index 0 and has exactly one worker; it is never shared between threads.
        check(
            unsafe { astc::astcenc_context_alloc(&config, 1, &mut context) },
            "context allocation",
        )?;
        Ok(Self {
            context: NonNull::new(context).context("ASTC returned a null context")?,
            block: settings.block,
        })
    }
    fn sizes(&self, width: u32, height: u32) -> Result<(usize, usize)> {
        ensure!(
            width > 0 && height > 0,
            "ASTC image dimensions must be positive"
        );
        let pixels = (width as usize)
            .checked_mul(height as usize)
            .and_then(|size| size.checked_mul(4))
            .context("ASTC image size overflow")?;
        let blocks = (width.div_ceil(self.block.0) as usize)
            .checked_mul(height.div_ceil(self.block.1) as usize)
            .and_then(|size| size.checked_mul(16))
            .context("ASTC block size overflow")?;
        Ok((pixels, blocks))
    }
    fn swizzle() -> astc::astcenc_swizzle {
        astc::astcenc_swizzle {
            r: astc::astcenc_swz_ASTCENC_SWZ_R,
            g: astc::astcenc_swz_ASTCENC_SWZ_G,
            b: astc::astcenc_swz_ASTCENC_SWZ_B,
            a: astc::astcenc_swz_ASTCENC_SWZ_A,
        }
    }
    pub(crate) fn compress(&mut self, rgba: &[u8], width: u32, height: u32) -> Result<Vec<u8>> {
        let (pixels, blocks) = self.sizes(width, height)?;
        ensure!(rgba.len() == pixels, "ASTC source RGBA size mismatch");
        let mut output = vec![0u8; blocks];
        let mut plane = rgba.as_ptr() as *mut c_void;
        let mut image = astc::astcenc_image {
            dim_x: width,
            dim_y: height,
            dim_z: 1,
            data_type: astc::astcenc_type_ASTCENC_TYPE_U8,
            data: &mut plane,
        };
        // The C API takes a mutable descriptor but reads the input pixel plane.
        // Both planes and the descriptor remain alive until the single worker returns.
        let status = unsafe {
            astc::astcenc_compress_image(
                self.context.as_ptr(),
                &mut image,
                &Self::swizzle(),
                output.as_mut_ptr(),
                output.len(),
                0,
            )
        };
        let reset = unsafe { astc::astcenc_compress_reset(self.context.as_ptr()) };
        check(status, "compression")?;
        check(reset, "compression reset")?;
        Ok(output)
    }
    pub(crate) fn decompress(&mut self, blocks: &[u8], width: u32, height: u32) -> Result<Vec<u8>> {
        let (pixels, size) = self.sizes(width, height)?;
        ensure!(
            blocks.len() == size,
            "ASTC compressed payload size mismatch"
        );
        let mut output = vec![0u8; pixels];
        let mut plane = output.as_mut_ptr() as *mut c_void;
        let mut image = astc::astcenc_image {
            dim_x: width,
            dim_y: height,
            dim_z: 1,
            data_type: astc::astcenc_type_ASTCENC_TYPE_U8,
            data: &mut plane,
        };
        // The checked output contains one complete RGBA8 plane, exclusively borrowed here.
        let status = unsafe {
            astc::astcenc_decompress_image(
                self.context.as_ptr(),
                blocks.as_ptr(),
                blocks.len(),
                &mut image,
                &Self::swizzle(),
                0,
            )
        };
        let reset = unsafe { astc::astcenc_decompress_reset(self.context.as_ptr()) };
        check(status, "decompression")?;
        check(reset, "decompression reset")?;
        Ok(output)
    }
}
impl Drop for Encoder {
    fn drop(&mut self) {
        // This wrapper is the unique owner of the context allocated in new().
        unsafe { astc::astcenc_context_free(self.context.as_ptr()) };
    }
}
