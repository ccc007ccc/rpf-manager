#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$project_root/scripts/host-env.sh"
source "$project_root/scripts/android-env.sh"
ndk_root="$ANDROID_NDK_ROOT"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android27-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export CXX_aarch64_linux_android="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android27-clang++"
export AR_aarch64_linux_android="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--exclude-libs,ALL -C link-arg=-Wl,--no-undefined'
export CXXSTDLIB_aarch64_linux_android=c++_shared
ndk_sysroot="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--target=aarch64-linux-android27 --sysroot=$ndk_sysroot -I$ndk_sysroot/usr/include/c++/v1"
cd "$project_root/rust-core/jni-bridge"
cargo build --locked --release --lib --features conversion --target aarch64-linux-android
mkdir -p "$project_root/app/build/rustJni/arm64-v8a"
cp "${CARGO_TARGET_DIR:-target}/aarch64-linux-android/release/librpf_jni.so" "$project_root/app/build/rustJni/arm64-v8a/"
# Both Rust's ASTC codec and the Vulkan viewer use this NDK C++ runtime.
cp "$ndk_sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$project_root/app/build/rustJni/arm64-v8a/"
