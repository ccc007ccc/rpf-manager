#!/usr/bin/env bash
# Caller sources host-env.sh first, so these checks run inside dev on SteamOS.
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_ROOT" || ! -d "$ANDROID_SDK_ROOT" ]]; then
    echo 'Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK directory.' >&2
    exit 1
fi
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/27.3.13750724}"
if [[ ! -f "$ANDROID_NDK_ROOT/source.properties" ]]; then
    echo 'Install NDK 27.3.13750724 or set ANDROID_NDK_ROOT to its directory.' >&2
    exit 1
fi
