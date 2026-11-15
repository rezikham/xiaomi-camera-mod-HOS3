#!/usr/bin/env python3
"""Rewrite an equal-length dynstr string in an ELF .so.

Used to give a bundled library a private SONAME without patchelf: the new name
must be the same byte length as the old one.

Usage: patch_dynstr.py OLD NEW FILE [FILE ...]
"""
import sys


def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__, file=sys.stderr)
        return 2
    old, new = sys.argv[1].encode() + b"\x00", sys.argv[2].encode() + b"\x00"
    if len(old) != len(new):
        print(f"error: {sys.argv[1]} ({len(old)-1}) and {sys.argv[2]} "
              f"({len(new)-1}) differ in length", file=sys.stderr)
        return 1
    for path in sys.argv[3:]:
        with open(path, "rb") as fh:
            data = fh.read()
        n = data.count(old)
        if n == 0:
            print(f"{path}: no {sys.argv[1]} reference")
            continue
        with open(path, "wb") as fh:
            fh.write(data.replace(old, new))
        print(f"{path}: {n} reference(s) rewritten")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
