#!/usr/bin/env python3
"""Pack camera-mod-ksu-plugin/ into a flashable KernelSU module ZIP.

This is deliberately separate from tools/build-ksu-module.py. The plugin-port
module is the only build allowed to ship .so files.

Usage:
    python3 tools/build-plugin-module.py <output.zip> [--source camera-mod-ksu-plugin]
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SOURCE = REPO_ROOT / "camera-mod-ksu-plugin"
ZIP_DATE = (2026, 1, 1, 0, 0, 0)


def collect(source: Path) -> list[tuple[Path, str]]:
    files: list[tuple[Path, str]] = []
    for path in sorted(source.rglob("*")):
        if not path.is_file():
            continue
        arcname = path.relative_to(source).as_posix()
        if arcname.startswith(".") or "/." in arcname:
            continue
        files.append((path, arcname))
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="ZIP path to write")
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    args = parser.parse_args()

    source: Path = args.source
    if not (source / "module.prop").is_file():
        print(f"error: {source}/module.prop not found", file=sys.stderr)
        return 1

    files = collect(source)
    if not any(arc.endswith(".so") for _, arc in files):
        print("error: no .so files found; run tools/prepare-plugin-port.py first",
              file=sys.stderr)
        return 1

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path, arcname in files:
            info = zipfile.ZipInfo(arcname, date_time=ZIP_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            mode = 0o755 if arcname.endswith(".sh") else 0o644
            info.external_attr = (mode & 0xFFFF) << 16
            archive.writestr(info, path.read_bytes())

    size_mb = args.output.stat().st_size / 1024 / 1024
    print(f"module: {args.output}")
    print(f"bytes:  {args.output.stat().st_size} ({size_mb:.2f} MB)")
    print(f"files:  {len(files)}")
    so_count = sum(1 for _, arc in files if arc.endswith(".so"))
    print(f"libraries: {so_count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
