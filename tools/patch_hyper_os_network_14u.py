#!/usr/bin/env python3
"""
Patch Xiaomi 14 Ultra (aurora, HyperOS 3) libhyper_os_network_private.dylib.so
so ConnectivityManager getters return Rust None/false/empty instead of
panicking with core::result::unwrap_failed when Binder returns Err(Status).

Without this patch, 18 Pro Max's Rust MIUIGallery crashes in a loop whenever a
VPN (such as Tailscale) is active and ConnectivityManager::get_network_capabilities
returns Status(-4, EX_NULL_POINTER).
"""

import argparse
import hashlib
import struct
import sys
from pathlib import Path

from capstone import CS_ARCH_ARM64, CS_MODE_ARM, Cs

EXPECTED_SIZE = 351344
EXPECTED_MD5 = "d7d2603c7db223113b002ceecde50810"
TEXT_VADDR = 0x30000
TEXT_OFFSET = 0x30000
UNWRAP_FAILED_PLT = 0x48DF8
STACK_CHK_FAIL_PLT = 0x48DE0
STATUS_DROP_PLT = 0x495F0
NOP = 0xD503201F


def enc_b(pc: int, target: int) -> int:
    diff = target - pc
    assert diff % 4 == 0
    imm26 = (diff >> 2) & 0x03FFFFFF
    return 0x14000000 | imm26


def enc_bl(pc: int, target: int) -> int:
    diff = target - pc
    assert diff % 4 == 0
    imm26 = (diff >> 2) & 0x03FFFFFF
    return 0x94000000 | imm26


def enc_movz(rd: int, imm16: int, shift: int = 0, is64: bool = True) -> int:
    assert shift in (0, 16, 32, 48)
    sf = 1 if is64 else 0
    hw = shift // 16
    return (sf << 31) | (0b10100101 << 23) | (hw << 21) | ((imm16 & 0xFFFF) << 5) | rd


def enc_movk(rd: int, imm16: int, shift: int = 0, is64: bool = True) -> int:
    assert shift in (0, 16, 32, 48)
    sf = 1 if is64 else 0
    hw = shift // 16
    return (sf << 31) | (0b11100101 << 23) | (hw << 21) | ((imm16 & 0xFFFF) << 5) | rd


def enc_mov_zero(rd: int, is64: bool = False) -> int:
    # orr rd, wzr/xzr, wzr/xzr
    sf = 1 if is64 else 0
    return (sf << 31) | (0b0101010 << 24) | (31 << 16) | (31 << 5) | rd


def enc_add_sp_imm(rd: int, imm12: int) -> int:
    assert 0 <= imm12 <= 0xFFF
    return 0x910003E0 | (imm12 << 10) | rd


def enc_str_x_uoff(rt: int, rn: int, byte_off: int = 0) -> int:
    assert byte_off % 8 == 0
    imm12 = byte_off // 8
    assert 0 <= imm12 <= 0xFFF
    return 0xF9000000 | (imm12 << 10) | (rn << 5) | rt


def enc_stp_x_off(rt: int, rt2: int, rn: int, byte_off: int = 0) -> int:
    assert byte_off % 8 == 0
    imm7 = (byte_off // 8) & 0x7F
    return 0xA9000000 | (imm7 << 15) | (rt2 << 10) | (rn << 5) | rt


PATCH_SITES = [
    {
        "name": "get_network_capabilities",
        "start": 0x438F8,
        "unwrap_bl": 0x43920,
        "stack_chk": 0x43924,
        "epilogue": 0x438CC,
        "insns": lambda pc: [
            enc_movz(8, 8, is64=False),             # mov w8, #8
            enc_str_x_uoff(8, 19, 0),               # str x8, [x19]
            enc_add_sp_imm(0, 8),                   # add x0, sp, #8
            enc_bl(pc + 12, STATUS_DROP_PLT),       # bl #0x495f0
            enc_b(pc + 16, 0x438CC),                # b #0x438cc
            NOP, NOP, NOP, NOP, NOP, NOP,
        ],
        "expected_disasm": [
            ("mov", "w8, #8"),
            ("str", "x8, [x19]"),
            ("add", "x0, sp, #8"),
            ("bl", "#0x495f0"),
            ("b", "#0x438cc"),
            ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""),
        ],
    },
    {
        "name": "get_active_network_info",
        "start": 0x43554,
        "unwrap_bl": 0x4357C,
        "stack_chk": 0x43580,
        "epilogue": 0x43528,
        "insns": lambda pc: [
            enc_movz(8, 1, is64=True),              # mov x8, #1
            enc_movk(8, 0x8000, shift=48, is64=True),  # movk x8, #0x8000, lsl #48
            enc_str_x_uoff(8, 19, 0),               # str x8, [x19]
            enc_add_sp_imm(0, 0xA0),                # add x0, sp, #0xa0
            enc_bl(pc + 16, STATUS_DROP_PLT),       # bl #0x495f0
            enc_b(pc + 20, 0x43528),                # b #0x43528
            NOP, NOP, NOP, NOP, NOP,
        ],
        "expected_disasm": [
            ("mov", "x8, #1"),
            ("movk", "x8, #0x8000, lsl #48"),
            ("str", "x8, [x19]"),
            ("add", "x0, sp, #0xa0"),
            ("bl", "#0x495f0"),
            ("b", "#0x43528"),
            ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""),
        ],
    },
    {
        "name": "get_active_network",
        "start": 0x435E8,
        "unwrap_bl": 0x43610,
        "stack_chk": 0x43614,
        "epilogue": 0x435C4,
        "insns": lambda pc: [
            enc_add_sp_imm(0, 8),                   # add x0, sp, #8
            enc_bl(pc + 4, STATUS_DROP_PLT),        # bl #0x495f0
            enc_mov_zero(0, is64=False),            # mov w0, wzr
            enc_mov_zero(1, is64=False),            # mov w1, wzr
            enc_b(pc + 16, 0x435C4),                # b #0x435c4
            NOP, NOP, NOP, NOP, NOP, NOP,
        ],
        "expected_disasm": [
            ("add", "x0, sp, #8"),
            ("bl", "#0x495f0"),
            ("mov", "w0, wzr"),
            ("mov", "w1, wzr"),
            ("b", "#0x435c4"),
            ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""),
        ],
    },
    {
        "name": "get_network_info",
        "start": 0x436CC,
        "unwrap_bl": 0x436F4,
        "stack_chk": 0x436F8,
        "epilogue": 0x436A0,
        "insns": lambda pc: [
            enc_movz(8, 1, is64=True),              # mov x8, #1
            enc_movk(8, 0x8000, shift=48, is64=True),  # movk x8, #0x8000, lsl #48
            enc_str_x_uoff(8, 19, 0),               # str x8, [x19]
            enc_add_sp_imm(0, 0xA0),                # add x0, sp, #0xa0
            enc_bl(pc + 16, STATUS_DROP_PLT),       # bl #0x495f0
            enc_b(pc + 20, 0x436A0),                # b #0x436a0
            NOP, NOP, NOP, NOP, NOP,
        ],
        "expected_disasm": [
            ("mov", "x8, #1"),
            ("movk", "x8, #0x8000, lsl #48"),
            ("str", "x8, [x19]"),
            ("add", "x0, sp, #0xa0"),
            ("bl", "#0x495f0"),
            ("b", "#0x436a0"),
            ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""),
        ],
    },
    {
        "name": "get_all_network_info",
        "start": 0x43784,
        "unwrap_bl": 0x437AC,
        "stack_chk": 0x437B0,
        "epilogue": 0x43760,
        "insns": lambda pc: [
            enc_movz(8, 8, is64=True),              # mov x8, #8
            enc_stp_x_off(31, 8, 19, 0),            # stp xzr, x8, [x19]
            enc_str_x_uoff(31, 19, 0x10),           # str xzr, [x19, #0x10]
            enc_add_sp_imm(0, 0x10),                # add x0, sp, #0x10
            enc_bl(pc + 16, STATUS_DROP_PLT),       # bl #0x495f0
            enc_b(pc + 20, 0x43760),                # b #0x43760
            NOP, NOP, NOP, NOP, NOP,
        ],
        "expected_disasm": [
            ("mov", "x8, #8"),
            ("stp", "xzr, x8, [x19]"),
            ("str", "xzr, [x19, #0x10]"),
            ("add", "x0, sp, #0x10"),
            ("bl", "#0x495f0"),
            ("b", "#0x43760"),
            ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""),
        ],
    },
    {
        "name": "is_active_network_metered",
        "start": 0x4381C,
        "unwrap_bl": 0x43844,
        "stack_chk": 0x43848,
        "epilogue": 0x437F4,
        "insns": lambda pc: [
            enc_add_sp_imm(0, 8),                   # add x0, sp, #8
            enc_bl(pc + 4, STATUS_DROP_PLT),        # bl #0x495f0
            enc_mov_zero(8, is64=False),            # mov w8, wzr
            enc_b(pc + 12, 0x437F4),                # b #0x437f4
            NOP, NOP, NOP, NOP, NOP, NOP, NOP,
        ],
        "expected_disasm": [
            ("add", "x0, sp, #8"),
            ("bl", "#0x495f0"),
            ("mov", "w8, wzr"),
            ("b", "#0x437f4"),
            ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""),
        ],
    },
    {
        "name": "get_proxy_for_network",
        "start": 0x43A20,
        "unwrap_bl": 0x43A48,
        "stack_chk": 0x43A4C,
        "epilogue": 0x439FC,
        "insns": lambda pc: [
            enc_movz(8, 1, is64=True),              # mov x8, #1
            enc_movk(8, 0x8000, shift=48, is64=True),  # movk x8, #0x8000, lsl #48
            enc_str_x_uoff(8, 19, 0),               # str x8, [x19]
            enc_add_sp_imm(0, 0x10),                # add x0, sp, #0x10
            enc_bl(pc + 16, STATUS_DROP_PLT),       # bl #0x495f0
            enc_b(pc + 20, 0x439FC),                # b #0x439fc
            NOP, NOP, NOP, NOP, NOP,
        ],
        "expected_disasm": [
            ("mov", "x8, #1"),
            ("movk", "x8, #0x8000, lsl #48"),
            ("str", "x8, [x19]"),
            ("add", "x0, sp, #0x10"),
            ("bl", "#0x495f0"),
            ("b", "#0x439fc"),
            ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""),
        ],
    },
    {
        "name": "is_network_supported",
        "start": 0x43ABC,
        "unwrap_bl": 0x43AE4,
        "stack_chk": 0x43AE8,
        "epilogue": 0x43A94,
        "insns": lambda pc: [
            enc_add_sp_imm(0, 8),                   # add x0, sp, #8
            enc_bl(pc + 4, STATUS_DROP_PLT),        # bl #0x495f0
            enc_mov_zero(8, is64=False),            # mov w8, wzr
            enc_b(pc + 12, 0x43A94),                # b #0x43a94
            NOP, NOP, NOP, NOP, NOP, NOP, NOP,
        ],
        "expected_disasm": [
            ("add", "x0, sp, #8"),
            ("bl", "#0x495f0"),
            ("mov", "w8, wzr"),
            ("b", "#0x43a94"),
            ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""), ("nop", ""),
        ],
    },
]


def verify_elf_1_to_1_text(data: bytes) -> None:
    # Verify ELF64 magic and PT_LOAD / .text vaddr==offset assumption
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise ValueError("Not a little-endian 64-bit ELF")
    e_phoff = struct.unpack_from("<Q", data, 32)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 54)
    found_rx = False
    for idx in range(e_phnum):
        off = e_phoff + idx * e_phentsize
        p_type, p_flags, p_offset, p_vaddr, _, p_filesz, _, _ = struct.unpack_from(
            "<IIQQQQQQ", data, off
        )
        if p_type == 1 and (p_flags & 1):  # PT_LOAD with PF_X
            if p_vaddr == p_offset and p_vaddr <= TEXT_VADDR < p_vaddr + p_filesz:
                found_rx = True
                break
    if not found_rx:
        raise ValueError("Executable PT_LOAD is not 1:1 mapped at 0x30000")


def patch_binary(data: bytes) -> bytearray:
    if len(data) != EXPECTED_SIZE:
        raise ValueError(f"Expected {EXPECTED_SIZE} bytes, got {len(data)}")
    actual_md5 = hashlib.md5(data).hexdigest()
    if actual_md5 != EXPECTED_MD5:
        raise ValueError(f"MD5 mismatch: expected {EXPECTED_MD5}, got {actual_md5}")
    verify_elf_1_to_1_text(data)

    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)

    # Pre-patch checks
    for site in PATCH_SITES:
        start = site["start"]
        unwrap_pc = site["unwrap_bl"]
        stack_chk_pc = site["stack_chk"]
        assert unwrap_pc - start == 10 * 4
        assert stack_chk_pc - start == 11 * 4

        unwrap_insn = list(md.disasm(data[unwrap_pc:unwrap_pc + 4], unwrap_pc))
        if len(unwrap_insn) != 1 or unwrap_insn[0].mnemonic != "bl" or unwrap_insn[0].op_str != f"#{hex(UNWRAP_FAILED_PLT)}":
            raise ValueError(
                f"{site['name']}: expected bl #{hex(UNWRAP_FAILED_PLT)} at 0x{unwrap_pc:x}, got {unwrap_insn}"
            )
        chk_insn = list(md.disasm(data[stack_chk_pc:stack_chk_pc + 4], stack_chk_pc))
        if len(chk_insn) != 1 or chk_insn[0].mnemonic != "bl" or chk_insn[0].op_str != f"#{hex(STACK_CHK_FAIL_PLT)}":
            raise ValueError(
                f"{site['name']}: expected __stack_chk_fail bl at 0x{stack_chk_pc:x}, got {chk_insn}"
            )

    out = bytearray(data)

    for site in PATCH_SITES:
        start = site["start"]
        words = site["insns"](start)
        assert len(words) == 11, f"{site['name']}: expected 11 instructions, got {len(words)}"
        encoded = struct.pack("<11I", *words)
        out[start:start + len(encoded)] = encoded

        # Post-patch disassembly assertion (11 patched insns + untouched __stack_chk_fail)
        dis = list(md.disasm(bytes(out[start:start + 48]), start))
        if len(dis) != 12:
            raise AssertionError(f"{site['name']}: disassembled {len(dis)} insns, expected 12")
        for idx, (exp_mnem, exp_ops) in enumerate(site["expected_disasm"]):
            got_mnem = dis[idx].mnemonic.strip()
            got_ops = dis[idx].op_str.strip()
            if (got_mnem, got_ops) != (exp_mnem, exp_ops):
                raise AssertionError(
                    f"{site['name']} +0x{idx*4:x}: expected ({exp_mnem!r}, {exp_ops!r}), got ({got_mnem!r}, {got_ops!r})"
                )
        if (dis[11].mnemonic, dis[11].op_str) != ("bl", f"#{hex(STACK_CHK_FAIL_PLT)}"):
            raise AssertionError(f"{site['name']}: __stack_chk_fail was altered: {dis[11]}")

    assert len(out) == len(data)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "positional_input",
        nargs="?",
        help="Input 14U libhyper_os_network_private.dylib.so",
    )
    parser.add_argument(
        "positional_output",
        nargs="?",
        help="Output patched .so path",
    )
    parser.add_argument("--input", "-i", dest="flag_input", help="Input .so path")
    parser.add_argument("--output", "-o", dest="flag_output", help="Output .so path")
    args = parser.parse_args()

    in_path = Path(args.flag_input or args.positional_input or "/tmp/e/net/14u_priv.so")
    out_path = Path(
        args.flag_output
        or args.positional_output
        or "gallery-18pm-module/system/system_ext/lib64/libhyper_os_network_private.dylib.so"
    )

    raw = in_path.read_bytes()
    patched = patch_binary(raw)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(patched)
    out_md5 = hashlib.md5(patched).hexdigest()
    print(f"Patched {len(PATCH_SITES)} sites -> {out_path} ({len(patched)} bytes, md5={out_md5})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
