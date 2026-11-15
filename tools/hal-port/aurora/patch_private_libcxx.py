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
"""
import sys

OLD = b"libc++.so\x00"
NEW = b"hcicxx.so\x00"
assert len(OLD) == len(NEW)


def patch(path: str) -> str:
    with open(path, "rb") as fh:
        data = fh.read()
    if NEW in data and OLD not in data:
        return "already patched"
    count = data.count(OLD)
    if count == 0:
        return "no libc++.so reference"
    with open(path, "wb") as fh:
        fh.write(data.replace(OLD, NEW))
    return f"{count} reference(s) rewritten"


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    for path in sys.argv[1:]:
        print(f"{path}: {patch(path)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
