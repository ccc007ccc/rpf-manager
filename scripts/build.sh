#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$project_root/scripts/host-env.sh"
source "$project_root/scripts/android-env.sh"
cd "$project_root"
if [[ $# -eq 0 ]]; then set -- assembleDebug; fi
exec ./gradlew "$@" --console=plain
