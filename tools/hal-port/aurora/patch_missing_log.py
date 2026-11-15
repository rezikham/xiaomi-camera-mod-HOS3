#!/usr/bin/env python3
"""Turn missing Aurora-only logging imports into local no-op functions.

Aurora's `libcom.xiaomi.debug.so` and `libcom.xiaomi.pluginutils.so` are older
than the Nezha versions, so `mileicafilter` and `legendST` import functions that
do not exist on Aurora:

    midebug::Log::catchLogEncryptLog(...)
    mialgo2::PluginUtils::printAndroidAndOfflineLog(...)

Both are dump-log helpers and their return value is not used. Rewrite their
`.dynsym` entries so the loader resolves them to a local `ret` in the plugin's
own `.text`. The relocation type and symbol index stay unchanged; only the
symbol becomes defined.

Usage:
    patch_missing_log.py INPUT OUTPUT
    patch_missing_log.py --check INPUT
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

TARGETS = {
    "_ZN7midebug3Log18catchLogEncryptLogEjPKciS2_cS2_z",
    "_ZN7mialgo211PluginUtils25printAndroidAndOfflineLogEiPKcS2_z",
}
RET_AARCH64 = b"\xc0\x03\x5f\xd6"
SHDR = struct.Struct("<IIQQQQIIQQ")
SYM = struct.Struct("<IBBHQQ")


def sections(data: bytes) -> dict[str, tuple[int, tuple[int, ...]]]:
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise SystemExit("not an ELF64 file")
    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", data, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", data, 0x3C)[0]
    e_shstrndx = struct.unpack_from("<H", data, 0x3E)[0]
    headers = [SHDR.unpack_from(data, e_shoff + i * e_shentsize) for i in range(e_shnum)]
    str_header = headers[e_shstrndx]
    names = data[str_header[4]:str_header[4] + str_header[5]]
    out = {}
    for index, header in enumerate(headers):
        end = names.find(b"\0", header[0])
        name = names[header[0]:end].decode()
        out[name] = (index, header)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    data = bytearray(args.input.read_bytes())
    secs = sections(data)
    for required in (".dynsym", ".dynstr", ".text"):
        if required not in secs:
            raise SystemExit(f"missing {required}")

    text_index, text_header = secs[".text"]
    text = data[text_header[4]:text_header[4] + text_header[5]]
    ret_offset = text.find(RET_AARCH64)
    if ret_offset < 0:
        raise SystemExit("no AArch64 ret found in .text")
    ret_offset -= ret_offset % 4
    ret_vaddr = text_header[3] + ret_offset

    _, dynsym_header = secs[".dynsym"]
    _, dynstr_header = secs[".dynstr"]
    dynstr = data[dynstr_header[4]:dynstr_header[4] + dynstr_header[5]]
    count = dynsym_header[5] // dynsym_header[9]
    entries = {}
    for index in range(count):
        offset = dynsym_header[4] + index * dynsym_header[9]
        st_name, st_info, st_other, st_shndx, st_value, st_size = SYM.unpack_from(
            data, offset
        )
        end = dynstr.find(b"\0", st_name)
        name = dynstr[st_name:end].decode(errors="replace")
        if name in TARGETS:
            entries[name] = (index, offset, st_name, st_shndx)

    if not entries:
        raise SystemExit("none of the target symbols were found")

    changed = []
    for name, (index, offset, st_name, st_shndx) in entries.items():
        if st_shndx != 0:
            changed.append((name, "already local"))
            continue
        SYM.pack_into(
            data,
            offset,
            st_name,
            0x12,  # STB_GLOBAL | STT_FUNC
            0,
            text_index,
            ret_vaddr,
            4,
        )
        changed.append((name, f"local ret @ 0x{ret_vaddr:x}"))

    for name, state in changed:
        print(f"{name}: {state}")
    if args.check:
        return 0
    if args.output is None:
        raise SystemExit("OUTPUT is required unless --check is used")
    args.output.write_bytes(data)
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
