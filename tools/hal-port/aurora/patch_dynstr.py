#!/usr/bin/env python3
"""Rewrite an equal-length dynstr string in an ELF .so.

Used to give a bundled library a private SONAME without patchelf: the new name
must be the same byte length as the old one.

Usage: patch_dynstr.py OLD NEW FILE [FILE ...]

Both OLD and NEW are NUL-terminated in the string table.
"""
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__, file=sys.stderr)
        return 2
    old = sys.argv[1].encode() + b"\x00"
    new = sys.argv[2].encode() + b"\x00"
    if len(old) != len(new):
        print(f"error: {sys.argv[1]} ({len(old)-1}) and {sys.argv[2]} "
              f"({len(new)-1}) differ in length", file=sys.stderr)
        return 1
    
    ret = 0
    for arg in sys.argv[3:]:
        path = Path(arg)
        try:
            data = path.read_bytes()
            n = data.count(old)
            if n == 0:
                print(f"{path}: no {sys.argv[1]} reference")
                continue
            if new in data and old not in data:
                print(f"{path}: already patched")
                continue
            patched = data.replace(old, new)
            path.write_bytes(patched)
            print(f"{path}: {n} reference(s) rewritten")
        except Exception as e:
            print(f"{path}: error: {e}", file=sys.stderr)
            ret = 1
    return ret


if __name__ == "__main__":
    raise SystemExit(main())
