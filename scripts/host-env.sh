#!/usr/bin/env bash
# Source from entry scripts. SteamOS builds stay in the existing dev container;
# ordinary Linux and CI use their configured native toolchain.
if [[ ! -f /run/.containerenv && -f /etc/os-release ]] &&
   (source /etc/os-release; [[ "${ID:-}" == steamos ]]); then
    exec distrobox enter dev -- bash "$0" "$@"
fi
