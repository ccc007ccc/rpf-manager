#!/usr/bin/env python3
"""Check the publishable worktree and Git history without printing secret values."""
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def forbidden_path(name):
    path = Path(name)
    if name == "app/src/main/assets/studio.rpf":
        return False
    return (
        "bundled_ng" in path.parts
        or path.name in {"bundled_ng.rs", "pc_aes_key.bin", "gtav_aes_key.dat",
                         "gtav_ng_key.dat", "gtav_ng_decrypt_tables.dat",
                         "ng_key_hashes.rs", "ng_table_hashes.rs", "local.properties",
                         "keystore.properties"}
        or path.name == ".env" or path.name.startswith(".env.")
        or path.suffix.lower() in {".rpf", ".exe", ".obj", ".o", ".so", ".apk",
                                  ".aab", ".jks", ".keystore", ".pem", ".p12", ".pfx"}
    )


SECRET = re.compile(
    rb"gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|"
    rb"AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,}|"
    rb"-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----"
)
NATIVE = re.compile(rb"rpf_pc_ng_decrypt|TFIT_op_iAES2|tfit_iAES2_keys|include_bytes!\([^)]*pc_aes")


def main():
    failures = set()
    names = git("ls-files", "--cached", "--others", "--exclude-standard", "-z").split(b"\0")
    for raw in set(names) - {b""}:
        name = raw.decode()
        path = ROOT / name
        if forbidden_path(name):
            failures.add(f"Forbidden publication path: {name}")
        if path.is_symlink():
            failures.add(f"Review symlink before publishing: {name}")
        if path.is_file():
            data = path.read_bytes()
            if SECRET.search(data):
                failures.add(f"Credential pattern in: {name}")
            if path.suffix in {".rs", ".cpp", ".h"} and NATIVE.search(data):
                failures.add(f"Bundled decoder reference in: {name}")

    # Inspect objects reachable from every local ref, so merging private history
    # cannot be hidden by deleting its files in a later commit.
    objects = git("rev-list", "--objects", "--all").decode().splitlines()
    for line in objects:
        oid, _, name = line.partition(" ")
        if name and forbidden_path(name):
            failures.add(f"Forbidden path in Git history: {name}")
        if git("cat-file", "-t", oid).strip() == b"blob":
            if SECRET.search(git("cat-file", "blob", oid)):
                failures.add(f"Credential pattern in historical blob: {name or oid}")

    if failures:
        raise SystemExit("\n".join(sorted(failures)))
    print("Public worktree and reachable Git objects passed the publication checks.")


if __name__ == "__main__":
    main()
