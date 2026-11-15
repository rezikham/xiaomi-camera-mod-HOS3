#!/usr/bin/env python3
"""Neutralise missing PLT logging imports without adding a shim library.

`mileicafilter` imports `midebug::Log::catchLogEncryptLog`, which Aurora's
`libcom.xiaomi.debug.so` does not export. The call is only for dump logging.
Patch the corresponding `.rela.plt` entry to `R_AARCH64_NONE` and replace the
PLT stub with `ret`, so the loader never looks the symbol up and calls are
no-ops.

Symbols whose relocation is not a normal `.rela.plt` JUMP_SLOT (for example the
packed `.rela.dyn` `printAndroidAndOfflineLog` in `legendST`) are left alone.

Usage:
    patch_plt_noop.py INPUT OUTPUT
    patch_plt_noop.py --check INPUT
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
RET = b"\xc0\x03\x5f\xd6"
NOP = b"\x1f\x20\x03\xd5"
SHDR = struct.Struct("<IIQQQQIIQQ")
SYM = struct.Struct("<IBBHQQ")


def parse(data: bytes):
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise SystemExit("not an ELF64 file")
    shoff = struct.unpack_from("<Q", data, 0x28)[0]
    shentsize = struct.unpack_from("<H", data, 0x3A)[0]
    shnum = struct.unpack_from("<H", data, 0x3C)[0]
    shstrndx = struct.unpack_from("<H", data, 0x3E)[0]
    headers = [SHDR.unpack_from(data, shoff + i * shentsize) for i in range(shnum)]
    strhdr = headers[shstrndx]
    names = data[strhdr[4]:strhdr[4] + strhdr[5]]
    sections = {}
    for index, header in enumerate(headers):
        end = names.find(b"\0", header[0])
        sections[names[header[0]:end].decode()] = (index, header)
    return sections


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    data = bytearray(args.input.read_bytes())
    secs = parse(data)
    for section in (".dynsym", ".dynstr", ".rela.plt", ".plt"):
        if section not in secs:
            raise SystemExit(f"missing {section}")

    _, dynsym = secs[".dynsym"]
    _, dynstr = secs[".dynstr"]
    _, rela = secs[".rela.plt"]
    _, plt = secs[".plt"]
    strings = data[dynstr[4]:dynstr[4] + dynstr[5]]

    symbol_names = {}
    for index in range(dynsym[5] // dynsym[9]):
        offset = dynsym[4] + index * dynsym[9]
        st_name = struct.unpack_from("<I", data, offset)[0]
        end = strings.find(b"\0", st_name)
        symbol_names[index] = strings[st_name:end].decode(errors="replace")

    def got_address(stub_offset: int, vaddr: int) -> int | None:
        insn0, insn1 = struct.unpack_from("<II", data, stub_offset)
        if (insn0 & 0x9F00001F) != 0x90000010:  # adrp x16, page
            return None
        immhi = (insn0 >> 5) & 0x7FFFF
        immlo = (insn0 >> 29) & 0x3
        imm = (immhi << 2) | immlo
        if imm & (1 << 20):
            imm -= 1 << 21
        page = (vaddr & ~0xFFF) + (imm << 12)
        if (insn1 & 0xFFC00000) != 0xF9400000:  # ldr x17, [x16, #imm]
            return None
        return page + ((insn1 >> 10) & 0xFFF) * 8

    patched = []
    for rel_index in range(rela[5] // rela[9]):
        offset = rela[4] + rel_index * rela[9]
        r_offset, r_info, r_addend = struct.unpack_from("<QQq", data, offset)
        sym_index = r_info >> 32
        name = symbol_names.get(sym_index, "")
        if name not in TARGETS:
            continue
        if (r_info & 0xFFFFFFFF) != 1026:  # R_AARCH64_JUMP_SLOT
            continue
        stub_offset = None
        for candidate in range(plt[4], plt[4] + plt[5], 16):
            vaddr = plt[3] + (candidate - plt[4])
            if got_address(candidate, vaddr) == r_offset:
                stub_offset = candidate
                break
        if stub_offset is None:
            continue
        # ELF64_R_INFO(sym, R_AARCH64_NONE)
        struct.pack_into("<Q", data, offset + 8, sym_index << 32)
        # Also mark the symbol as a defined absolute value, so the loader's
        # undefined-symbol pass does not look it up by name (the name is not in
        # the DSO's GNU hash table).
        sym_offset = dynsym[4] + sym_index * dynsym[9]
        st_name = struct.unpack_from("<I", data, sym_offset)[0]
        SYM.pack_into(data, sym_offset, st_name, 0, 0, 0xFFF1, 0, 0)
        data[stub_offset:stub_offset + 4] = RET
        data[stub_offset + 4:stub_offset + 16] = NOP * 3
        patched.append((name, rel_index, r_offset, vaddr))

    if not patched:
        raise SystemExit("none of the PLT target symbols were found")
    for name, index, got, vaddr in patched:
        print(f"{name}: PLT[{index}] GOT 0x{got:x} stub 0x{vaddr:x} -> ret")
    if args.check:
        return 0
    if args.output is None:
        raise SystemExit("OUTPUT is required unless --check is used")
    args.output.write_bytes(data)
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
