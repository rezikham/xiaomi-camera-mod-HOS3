#!/usr/bin/env python3
"""Retarget a donor camera plugin at Aurora's libc++ and the compat shim.

The Nezha/Madrid plugin is linked against a newer platform libc++ than the one
Aurora exposes to the vendor namespace. On Aurora the linker resolves
"libc++.so" to the VNDK v34 copy, which lacks symbols the plugin needs
(std::__1::__fs::filesystem::__status/__file_size,
std::__1::basic_filebuf::close and its complete ctor/dtor). The measurable
symptom is:

    MiaDLibrary.cpp:28 load()OS returned error: dlopen failed:
      cannot locate symbol "...__fs10filesystem8__status..."

Fix: declare a private copy of Aurora's own /system/lib64/libc++.so under a
unique SONAME ("libcxx.so") and make the plugin request that instead. The name
has exactly the same length as "libc++.so", so the DT_NEEDED string can be
rewritten in place without relocating the string table.

The same pass adds the compat shim (see compat_shim_connect.c) as the first
DT_NEEDED entry so the loader finds the "connect" ABI hook.

Usage:
    patch_plugin_runtime_deps.py PLUGIN \\
        --aurora-libcxx /path/to/extracted/system/lib64/libc++.so \\
        --shim /path/to/libshim.so \\
        --patchelf /path/to/patchelf

Writes PLUGIN in place and emits libcxx.so next to the shim.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

OLD_NEEDED = b"libc++.so\x00"
NEW_NEEDED = b"libcxx.so\x00"


def rewrite_needed(plugin: Path) -> bool:
    data = bytearray(plugin.read_bytes())
    needle = OLD_NEEDED
    hits = data.count(needle)
    if hits == 0:
        return False
    if hits != 1:
        raise SystemExit(
            f"{plugin}: expected exactly one DT_NEEDED {OLD_NEEDED!r}, found {hits}"
        )
    offset = data.index(needle)
    data[offset : offset + len(needle)] = NEW_NEEDED
    plugin.write_bytes(data)
    return True


def run(cmd: list[str]) -> None:
    subprocess.run(cmd, check=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("plugin", type=Path)
    ap.add_argument("--aurora-libcxx", type=Path, required=True)
    ap.add_argument("--shim", type=Path, required=True)
    ap.add_argument("--patchelf", default="patchelf")
    ap.add_argument("--out-dir", type=Path, default=None)
    args = ap.parse_args()

    out_dir = args.out_dir or args.plugin.parent
    out_dir.mkdir(parents=True, exist_ok=True)

    if rewrite_needed(args.plugin):
        print(f"retargeted libc++.so -> libcxx.so in {args.plugin}")
    else:
        print(f"{args.plugin}: libc++.so already retargeted")

    libcxx = out_dir / "libcxx.so"
    shutil.copyfile(args.aurora_libcxx, libcxx)
    libcxx.chmod(0o644)
    run([args.patchelf, "--set-soname", "libcxx.so", str(libcxx)])
    print(f"installed {libcxx}")

    shim = out_dir / "libshim.so"
    if args.shim.resolve() != shim.resolve():
        shutil.copyfile(args.shim, shim)
        shim.chmod(0o644)
    run([args.patchelf, "--add-needed", "libshim.so", str(args.plugin)])
    print(f"added libshim.so dependency to {args.plugin}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
