#!/usr/bin/env python3
"""Pack camera-mod-ksu/ into a flashable KernelSU module ZIP.

Usage:
    python3 tools/build-ksu-module.py <output.zip> [--source camera-mod-ksu]

Keeps the ZIP layout KernelSU expects (module.prop at root, scripts executable,
everything else 0644) and refuses to pack .so files so a LUT-only stage can
never accidentally ship a library again.
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SOURCE = REPO_ROOT / "camera-mod-ksu"

# Fixed timestamp keeps builds reproducible.
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
    parser.add_argument(
        "--allow-so",
        action="store_true",
        help="permit .so files (off by default)",
    )
    args = parser.parse_args()

    source: Path = args.source
    if not (source / "module.prop").is_file():
        print(f"error: {source}/module.prop not found", file=sys.stderr)
        return 1

    files = collect(source)

    so_files = [arc for _, arc in files if arc.endswith(".so")]
    if so_files and not args.allow_so:
        print("error: refusing to pack .so files:", file=sys.stderr)
        for arc in so_files:
            print(f"  {arc}", file=sys.stderr)
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
    for _, arcname in files:
        print(f"  {arcname}")
    if not so_files:
        print("no .so files packed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
