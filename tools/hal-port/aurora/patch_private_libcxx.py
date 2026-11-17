#!/usr/bin/env python3
"""Point a Nezha/Madrid .so at a private libc++ SONAME.

Aurora's system libc++ predates `std::__1::__fs::filesystem`, which the Nezha
Legendary plugin (and some of its companions) need. The fix is to ship the
Nezha libc++ under a second SONAME and make the donor depend on that instead.

Both names are the same length, so the patch is a byte-for-byte replacement of
the dynstr entry:

    libc++.so  ->  hcicxx.so

Usage:
    patch_private_libcxx.py <file.so> [<file.so> ...]

On the libc++ file itself this rewrites the SONAME; on any other library it
rewrites the DT_NEEDED entry. Idempotent: an already-patched file is skipped.

Returns non-zero exit code on error.
"""
import sys
from pathlib import Path

OLD = b"libc++.so\x00"
NEW = b"hcicxx.so\x00"
assert len(OLD) == len(NEW), "OLD and NEW must have same length"


def patch(path: Path) -> str:
    data = path.read_bytes()
    if NEW in data and OLD not in data:
        return "already patched"
    count = data.count(OLD)
    if count == 0:
        return "no libc++.so reference"
    if count > 2:
        # SONAME + up to N DT_NEEDED; warn but proceed
        print(f"warning: {path}: found {count} libc++.so references (expected 1-2)", file=sys.stderr)
    patched = data.replace(OLD, NEW)
    path.write_bytes(patched)
    return f"{count} reference(s) rewritten"


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    ret = 0
    for arg in sys.argv[1:]:
        path = Path(arg)
        try:
            result = patch(path)
            print(f"{path}: {result}")
        except Exception as e:
            print(f"{path}: error: {e}", file=sys.stderr)
            ret = 1
    return ret


if __name__ == "__main__":
    raise SystemExit(main())
