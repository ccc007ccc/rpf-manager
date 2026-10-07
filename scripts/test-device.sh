#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$project_root/scripts/host-env.sh"
source "$project_root/scripts/android-env.sh"
test_suffix="${RPF_TEST_SUFFIX:-.debug}"
if [[ ! "$test_suffix" =~ ^\.[A-Za-z0-9_.]+$ ]]; then
    echo "Invalid RPF_TEST_SUFFIX" >&2
    exit 1
fi
"$project_root/scripts/build.sh" assembleDebug assembleDebugAndroidTest "-PrpfTestSuffix=$test_suffix"
adb_tool="$ANDROID_SDK_ROOT/platform-tools/adb"
adb_args=()
if [[ -n "${RPF_TEST_SERIAL:-}" ]]; then adb_args=(-s "$RPF_TEST_SERIAL"); fi
# Explicit PackageInstaller sessions also work when a third-party installer
# intercepts `pm install` and stalls on a test-only APK.
install_apk() {
    local apk="$1"
    shift
    local remote="/data/local/tmp/rpf-manager${test_suffix}-$$.apk"
    local created install_session bytes
    bytes=$(stat -c %s "$apk")
    "$adb_tool" "${adb_args[@]}" push "$apk" "$remote"
    if ! created=$("$adb_tool" "${adb_args[@]}" shell cmd package install-create -r "$@"); then
        "$adb_tool" "${adb_args[@]}" shell rm -f "$remote"
        return 1
    fi
    if [[ "$created" =~ \[([0-9]+)\] ]]; then
        install_session="${BASH_REMATCH[1]}"
    else
        echo "$created" >&2
        "$adb_tool" "${adb_args[@]}" shell rm -f "$remote"
        return 1
    fi
    if ! "$adb_tool" "${adb_args[@]}" shell cmd package install-write -S "$bytes" "$install_session" base.apk "$remote" ||
       ! "$adb_tool" "${adb_args[@]}" shell cmd package install-commit "$install_session"; then
        "$adb_tool" "${adb_args[@]}" shell cmd package install-abandon "$install_session" || true
        "$adb_tool" "${adb_args[@]}" shell rm -f "$remote"
        return 1
    fi
    "$adb_tool" "${adb_args[@]}" shell rm -f "$remote"
}
install_apk "$project_root/app/build/outputs/apk/debug/app-debug.apk"
install_apk "$project_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" -t
"$adb_tool" "${adb_args[@]}" shell input keyevent KEYCODE_WAKEUP
"$adb_tool" "${adb_args[@]}" shell wm dismiss-keyguard
report="$project_root/app/build/reports/device-tests.txt"
mkdir -p "$(dirname "$report")"
"$adb_tool" "${adb_args[@]}" shell am instrument -w -r "$@" "com.gtav.rpfmanager${test_suffix}.test/androidx.test.runner.AndroidJUnitRunner" | tee "$report"
if ! rg -q '^OK \([0-9]+ tests?\)' "$report"; then exit 1; fi
