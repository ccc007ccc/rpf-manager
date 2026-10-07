#!/usr/bin/env bash
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$here/../scripts/host-env.sh"
source_root="${RPF_MANAGER_SOURCE:-$(dirname "$here")}"
out="${RPF_ASTC_BUILD_DIR:-$source_root/build/linux-astc}"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$out/cargo}"
manifest="$source_root/rust-core/jni-bridge/Cargo.toml"
cargo test --locked --features desktop --lib --bin rpf-tool --manifest-path "$manifest"
cargo build --locked --release --features desktop --bin rpf-tool --manifest-path "$manifest"
mkdir -p "$out/bin"
install -m 755 "$CARGO_TARGET_DIR/release/rpf-tool" "$out/bin/rpf-tool"
echo "Linux tool: $out/bin/rpf-tool"
