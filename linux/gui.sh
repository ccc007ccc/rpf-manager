#!/usr/bin/env bash
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$here/../scripts/host-env.sh"
source_root="${RPF_MANAGER_SOURCE:-$(dirname "$here")}"
out="${RPF_ASTC_BUILD_DIR:-$source_root/build/linux-astc}"
if [[ ! -x "$out/bin/rpf-tool" ]]; then
    bash "$here/build.sh"
fi
exec python3 "$here/gui.py" --tool "$out/bin/rpf-tool" "$@"
