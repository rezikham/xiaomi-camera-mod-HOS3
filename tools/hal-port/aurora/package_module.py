#!/usr/bin/env python3
"""Package an Aurora Legend bundle as a KernelSU/Magisk module zip.

The bundle keeps the donor tree under ``odm/`` so it can also be inspected or
bind-mounted by hand. KernelSU/Magisk overlays expect partition files under
``system/<partition>/``, so this repackages ``odm/...`` as
``system/odm/...`` and keeps ``module.prop``, ``README.md`` and
``SHA256SUMS`` at the archive root.

Usage:
    tools/hal-port/aurora/package_module.py BUNDLE_DIR [OUTPUT_ZIP]
"""

from __future__ import annotations

import os
import sys
import zipfile
from pathlib import Path


def main() -> int:
    if len(sys.argv) not in (2, 3):
        print(__doc__, file=sys.stderr)
        return 2

    bundle = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve() if len(sys.argv) == 3 else bundle.with_suffix(".zip")

    module_prop = bundle / "module.prop"
    odm = bundle / "odm"
    if not module_prop.is_file():
        print(f"error: missing {module_prop}", file=sys.stderr)
        return 1
    if not (odm / "lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so").is_file():
        print(f"error: {odm} does not look like a Legend bundle", file=sys.stderr)
        return 1

    root_files = [module_prop]
    for name in ("README.md", "SHA256SUMS"):
        path = bundle / name
        if path.is_file():
            root_files.append(path)

    files: list[tuple[Path, str]] = []
    for path in root_files:
        files.append((path, path.name))
    for path in sorted(odm.rglob("*")):
        if path.is_file():
            files.append((path, f"system/odm/{path.relative_to(odm).as_posix()}"))

    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path, arcname in files:
            # Fixed timestamp keeps repeated builds byte-comparable.
            info = zipfile.ZipInfo(arcname, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o644 & 0xFFFF) << 16
            archive.writestr(info, path.read_bytes())

    print(f"module: {output}")
    print(f"files:  {len(files)}")
    print(f"bytes:  {output.stat().st_size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
