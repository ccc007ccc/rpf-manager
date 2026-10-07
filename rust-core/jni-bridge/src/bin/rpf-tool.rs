use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use rpf_jni::{
    conversion,
    transcode::{AdvancedSettings, Settings},
};
use std::path::PathBuf;

fn parse_channel_weights(value: &str) -> std::result::Result<[f32; 4], String> {
    let weights = value
        .split(',')
        .map(|part| part.parse::<f32>().map_err(|error| error.to_string()))
        .collect::<std::result::Result<Vec<_>, _>>()?;
    weights
        .try_into()
        .map_err(|_| "Provide exactly four comma-separated RGBA channel weights".into())
}

#[derive(Parser)]
#[command(
    about = "RPF Manager: Linux RPF7 extraction, packing and ASTC conversion",
    version
)]
struct Args {
    #[arg(
        long,
        global = true,
        help = "User key directory; required for AES/NG archives (no game keys are bundled)"
    )]
    keys: Option<PathBuf>,
    #[command(subcommand)]
    command: Command,
}
#[derive(Subcommand)]
enum Command {
    Extract {
        input: PathBuf,
        output: PathBuf,
    },
    Pack {
        input: PathBuf,
        output: PathBuf,
    },
    /// Remove the highest ASTC mip used only above the game's minimum texture setting.
    TrimMips {
        input: PathBuf,
        #[arg(
            long,
            help = "Atomically replace ASTC RPFs; retain rpf-mip-trim sidecars with the resources"
        )]
        in_place: bool,
        #[arg(long, default_value = "gta-minimum-v1", value_parser = ["gta-minimum-v1"])]
        profile: String,
        #[arg(long, default_value_t = 4)]
        jobs: usize,
        #[arg(
            long,
            help = "Limit to this relative top-level RPF path; may be repeated"
        )]
        archive: Vec<PathBuf>,
        #[arg(
            long,
            help = "Inspect and report changes without modifying game resources"
        )]
        dry_run: bool,
        #[arg(
            long,
            help = "Write the current run summary (per-archive journals always accompany modified resources)"
        )]
        report: Option<PathBuf>,
    },
    Convert {
        input: PathBuf,
        #[arg(long, conflicts_with = "in_place")]
        output: Option<PathBuf>,
        #[arg(
            long,
            help = "Replace each original RPF only after writing and validating its replacement"
        )]
        in_place: bool,
        #[arg(
            long,
            default_value = "4x4",
            help = "ASTC footprint: 4x4, 5x4, 5x5, 6x5, 6x6, 8x5, 8x6, 8x8, 10x5, 10x6, 10x8, 10x10, 12x10, 12x12. Larger blocks use less texture memory; dimensions and mips stay unchanged"
        )]
        block: String,
        #[arg(long,default_value="thorough",value_parser=["fastest","fast","medium","thorough","very-thorough","exhaustive"])]
        quality: String,
        #[arg(
            long,
            help = "Custom finite search effort from 0 to 100; overrides the named quality preset"
        )]
        effort: Option<f32>,
        #[arg(
            long,
            value_parser = parse_channel_weights,
            default_value = "1,1,1,1",
            value_name = "R,G,B,A",
            help = "RGBA error-weight multipliers, each finite and 0.01..64; applied after perceptual weighting. Effective ratios are limited to 1000:1 by astcenc"
        )]
        channel_weights: [f32; 4],
        #[arg(
            long,
            help = "Enable alpha-weighted RGB error with neighborhood radius 0..16; can discard RGB in transparent pixels, so use only for known transparency textures"
        )]
        alpha_weight_radius: Option<u32>,
        #[arg(
            long,
            help = "Weight RGB errors by perceptual luminance; channel weights multiply these defaults"
        )]
        perceptual: bool,
        #[arg(long, default_value_t = 4)]
        jobs: usize,
        #[arg(
            long,
            help = "Limit to this relative top-level RPF path; may be repeated"
        )]
        archive: Vec<PathBuf>,
        #[arg(
            long,
            help = "Write bounded BC/ASTC PNG samples and per-mip quality audits for all converted textures"
        )]
        samples: Option<PathBuf>,
        #[arg(long, help = "Scan formats without converting or replacing files")]
        dry_run: bool,
        #[arg(
            long,
            help = "Repair resource page counts without re-encoding textures"
        )]
        repair_pages: bool,
        #[arg(
            long,
            help = "Preserve resources that fail to decode and list them in the incomplete conversion report"
        )]
        keep_going: bool,
        #[arg(
            long,
            help = "Refuse to publish a converted archive with retained BC textures or resource errors"
        )]
        require_no_bc: bool,
    },
}
fn run() -> Result<()> {
    let args = Args::parse();
    if let Some(keys) = args.keys {
        conversion::import_keys(&keys)?;
    }
    match args.command {
        Command::Extract { input, output } => println!(
            "{}",
            serde_json::json!({"event":"complete","files":conversion::extract_archive(&input,&output)?})
        ),
        Command::Pack { input, output } => println!(
            "{}",
            serde_json::json!({"event":"complete","bytes":conversion::pack_archive(&input,&output)?})
        ),
        Command::TrimMips {
            input,
            in_place,
            profile: _,
            jobs,
            archive,
            dry_run,
            report,
        } => {
            let report = conversion::trim_mips(conversion::TrimOptions {
                input,
                in_place,
                archives: archive,
                policy: rpf_jni::transcode::mip_trim::Policy::gta_minimum(),
                jobs,
                dry_run,
                report,
            })?;
            println!(
                "{}",
                serde_json::json!({"event":"complete","report":report})
            );
        }
        Command::Convert {
            input,
            output,
            in_place,
            block,
            quality,
            effort,
            channel_weights,
            alpha_weight_radius,
            perceptual,
            jobs,
            archive,
            samples,
            dry_run,
            repair_pages,
            keep_going,
            require_no_bc,
        } => {
            let (w, h) = block
                .split_once('x')
                .context("Block size must look like 4x4; use --help for the 14 legal footprints")?;
            let result = conversion::convert(conversion::ConvertOptions {
                input,
                output,
                in_place,
                archives: archive,
                settings: Settings {
                    block: (w.parse()?, h.parse()?),
                    quality,
                    advanced: AdvancedSettings {
                        effort,
                        channel_weights,
                        alpha_weight_radius,
                        perceptual,
                    },
                },
                jobs,
                samples,
                dry_run,
                repair_pages,
                keep_going,
                require_no_bc,
            })?;
            println!(
                "{}",
                serde_json::json!({"event":"complete","report":result})
            );
        }
    }
    Ok(())
}
fn main() {
    if let Err(error) = run() {
        eprintln!(
            "{}",
            serde_json::json!({"event":"error","message":format!("{error:#}")})
        );
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn advanced_arguments_accept_a_single_comma_separated_weight_value() {
        let args = Args::try_parse_from([
            "rpf-tool",
            "convert",
            "gta",
            "--dry-run",
            "--block",
            "8x8",
            "--quality",
            "fastest",
            "--effort",
            "77.5",
            "--channel-weights",
            "1,2,3,4",
            "--alpha-weight-radius",
            "0",
            "--perceptual",
        ])
        .unwrap();
        match args.command {
            Command::Convert {
                block,
                quality,
                effort,
                channel_weights,
                alpha_weight_radius,
                perceptual,
                ..
            } => {
                assert_eq!(block, "8x8");
                assert_eq!(quality, "fastest");
                assert_eq!(effort, Some(77.5));
                assert_eq!(channel_weights, [1.0, 2.0, 3.0, 4.0]);
                assert_eq!(alpha_weight_radius, Some(0));
                assert!(perceptual);
            }
            _ => panic!("Expected convert command"),
        }
        assert!(Args::try_parse_from([
            "rpf-tool",
            "convert",
            "gta",
            "--dry-run",
            "--channel-weights",
            "1,2,3"
        ])
        .is_err());
    }
}
