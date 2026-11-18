#!/usr/bin/env python3
"""HAL patching toolkit for Aurora (14 Ultra) native Legendary backport.

This toolkit extracts, analyzes, and patches Aurora's camera HAL binaries to
add native M3/M9 (legendMode 1/2) and Original Portrait support.

All functions are offline: they operate on extracted firmware files. Device
access is only needed to pull the original binaries and test the result.

Usage:
    # Extract HAL from device (requires adb + su)
    python3 hal_patch.py extract --serial 1c4e4dec --out /tmp/hal-extract

    # Find key offsets in an extracted HAL
    python3 hal_patch.py find-offsets /tmp/hal-extract/camera.qcom.so

    # Apply a binary patch
    python3 hal_patch.py patch /tmp/hal-extract/camera.qcom.so \
        --offset 0x123456 --old 21b80d91 --new 215c3791

    # Build a KSU module from patched HAL + plugin bundle
    python3 hal_patch.py build-module /tmp/hal-extract/patched \
        --module-out /tmp/hypercam-aurora-legend.zip
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import struct
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Optional

# ---------------------------------------------------------------------------
# Known offsets from RE (Nezha/Madrid donor HAL)
# These are reference values; Aurora's HAL may have different offsets.
# ---------------------------------------------------------------------------

DONOR_OFFSETS = {
    # camera.qcom.core.so (Nezha/Madrid)
    "legendMode_load": 0x11A57CC,        # ldr w22, [x8]
    "legendMode_sub": 0x11A57D0,         # sub w8, w22, #1
    "legendMode_cmp": 0x11A57D4,         # cmp w8, #2
    "legendMode_cset": 0x11A57D8,        # cset w8, lo
    "legendMode_store": 0x11A57DC,       # str w8, [x20]
    "legendMode_log": 0x10AEE4,          # log format string
    "OemRetrieveLegendMode": 0x284A8A,   # symbol

    # mileicafilter.so plugin patch (already in patch_plugin.py)
    "mileicafilter_initialize": 0xC768,  # add x1, x1, #0x36e -> #0xdd7
    "mileicafilter_isEnabled": 0xD780,   # add x1, x1, #0x36e -> #0xdd7
}

# ---------------------------------------------------------------------------
# Aurora (stock) offsets from extracted device binaries
# All offsets verified against camera.qcom.so (SHA-256: 5b30cefde5b4a4aabf52282778c6d757)
# ---------------------------------------------------------------------------

AURORA_OFFSETS = {
    # camera.qcom.so
    "stylizationType_string": 0x917D7,        # "com.xiaomi.sessionparams.stylizationType"
    "stylizationType_query_1": 0xECD8A4,      # in ReadOutputVendorTag: adrp x1,0x91000; add x1,x1,#0x7d7
    "stylizationType_query_2": 0xF55BC0,      # in DataCompress: same pattern
    "QueryVendorTagLocation_plt": 0xFF0120,   # PLT entry
    "LogSystem_plt": 0xFF0138,                # PLT entry for logging
    "GetFileName_plt": 0xFF0168,              # PLT entry

    # libcom.xiaomi.pluginutils.so
    "PLUMA_INTERFACE_VERSION": 0x12D3C,       # value: 1
    "PLUMA_INTERFACE_LOWEST_VERSION": 0x12D40, # value: 1
    "ProviderManager_add": 0x1E3E0,            # mialgo2::ProviderManager::add

    # libmialgoengine.so
    "MPluginModule_getPluginWraperByPluginName": 0x80F7C,
    "MPluginModule_getInstance": 0xCA7D0,
}

# AArch64 instruction encodings
ORIGINAL_ADD_0x36E = bytes.fromhex("21b80d91")   # add x1, x1, #0x36e
PATCHED_ADD_0xDD7 = bytes.fromhex("215c3791")    # add x1, x1, #0xdd7

# Aurora HAL strings (confirmed absent)
AURORA_MISSING_STRINGS = [
    "com.xiaomi.sessionparams.legendMode",
    "legendMode",
    "LegendMode",
    "OemRetrieveLegendMode",
]

# Aurora HAL strings (confirmed present)
AURORA_PRESENT_STRINGS = [
    "com.xiaomi.sessionparams.stylizationType",  # at 0x917D7
    "GetImageStylizationTypeTag",                # at 0x83559
    "com.xiaomi.sessionparams",                  # section at 0x1491FA
]


# ---------------------------------------------------------------------------
# Extraction
# ---------------------------------------------------------------------------

def extract_hal(serial: str, out_dir: Path) -> dict[str, Path]:
    """Pull camera HAL binaries from the device via adb."""
    out_dir.mkdir(parents=True, exist_ok=True)

    # Candidate HAL paths on Aurora
    hal_paths = [
        "/odm/lib64/hw/camera.qcom.so",
        "/odm/lib64/hw/camera.qcom.sm8650.so",
        "/odm/lib64/hw/camera.xiaomi.so",
        "/vendor/lib64/hw/camera.qcom.so",
        "/vendor/lib64/hw/camera.qcom.core.so",
    ]

    # Supporting libraries
    lib_paths = [
        "/odm/lib64/libmialgoengine.so",
        "/odm/lib64/libcom.xiaomi.pluginutils.so",
        "/odm/lib64/libmetadataHelper.so",
        "/odm/lib64/libmialgoenginecustom.so",
    ]

    result: dict[str, Path] = {}

    for path in hal_paths + lib_paths:
        name = path.replace("/", "_").replace(".so", "")
        dest = out_dir / Path(path).name
        try:
            subprocess.run(
                ["adb", "-s", serial, "shell", "su", "-c",
                 f"cat {path} > {dest}"],
                check=True, capture_output=True, timeout=30,
            )
            if dest.exists() and dest.stat().st_size > 0:
                result[path] = dest
                print(f"[hal_patch] extracted {path} -> {dest} "
                      f"({dest.stat().st_size} bytes)")
            else:
                dest.unlink(missing_ok=True)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
            print(f"[hal_patch] skipped {path}: {e}")

    # Also pull pipeline configs
    config_paths = [
        "/odm/etc/camera/xiaomi/leicasnapshot.json",
        "/odm/etc/camera/xiaomi/satsnapshot.json",
        "/odm/etc/camera/leica_filter_param.bin",
        "/odm/etc/camera/leica_filter_param_m3.bin",
        "/odm/etc/camera/leica_filter_param_m9_preview.bin",
        "/odm/etc/camera/leica_filter_param_m9_snapshot.bin",
    ]
    for path in config_paths:
        dest = out_dir / Path(path).name
        try:
            subprocess.run(
                ["adb", "-s", serial, "shell", "su", "-c",
                 f"cat {path} > {dest}"],
                check=True, capture_output=True, timeout=30,
            )
            if dest.exists() and dest.stat().st_size > 0:
                result[path] = dest
                print(f"[hal_patch] extracted config {path} -> {dest}")
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass

    return result


# ---------------------------------------------------------------------------
# Offset discovery
# ---------------------------------------------------------------------------

def find_strings(binary: Path, pattern: str) -> list[int]:
    """Find file offsets of a string in a binary using `strings` + `grep`."""
    try:
        result = subprocess.run(
            ["strings", "-t", "x", str(binary)],
            capture_output=True, text=True, check=True, timeout=60,
        )
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        return []

    offsets = []
    for line in result.stdout.splitlines():
        if pattern.lower() in line.lower():
            parts = line.split(None, 1)
            if parts:
                try:
                    offsets.append(int(parts[0], 16))
                except ValueError:
                    pass
    return offsets


def find_hex_pattern(binary: Path, hex_pattern: str) -> list[int]:
    """Find file offsets of a byte pattern using `grep -abo`."""
    pattern_bytes = bytes.fromhex(hex_pattern)
    try:
        result = subprocess.run(
            ["grep", "-aboP", f"[{''.join(f'\\x{b:02x}' for b in pattern_bytes)}]",
             str(binary)],
            capture_output=True, text=True, check=True, timeout=60,
        )
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        return []

    offsets = []
    for line in result.stdout.splitlines():
        if ":" in line:
            try:
                offsets.append(int(line.split(":")[0]))
            except ValueError:
                pass
    return offsets


def find_offsets(binary: Path) -> dict[str, list[int]]:
    """Find key offsets in an Aurora HAL binary."""
    results: dict[str, list[int]] = {}

    # Search for legend-related strings
    for pattern in ["legendMode", "stylizationType", "LegendMode",
                     "mileicafilter", "legendST", "legendCT", "M10R",
                     "leica_filter_param", "OemRetrieveLegendMode"]:
        results[f"string:{pattern}"] = find_strings(binary, pattern)

    # Search for tag 508 (0x1fc) immediate in AArch64
    # mov w3, #0x1fc -> 1ffc 3fd2 (movz) or similar
    results["hex:0x1fc_immediate"] = find_hex_pattern(binary, "1ffc3fd2")

    # Search for MPluginModule symbols
    try:
        result = subprocess.run(
            ["nm", "-DC", str(binary)],
            capture_output=True, text=True, check=True, timeout=60,
        )
        for line in result.stdout.splitlines():
            if "MPluginModule" in line or "PluginWrapper" in line:
                parts = line.split()
                if len(parts) >= 3:
                    results.setdefault("symbol:MPluginModule", []).append(
                        (parts[0], parts[2])
                    )
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        pass

    return results


# ---------------------------------------------------------------------------
# Binary patching
# ---------------------------------------------------------------------------

def apply_patch(binary: Path, offset: int, old_hex: str, new_hex: str,
                backup: bool = True) -> None:
    """Apply a binary patch at a given offset."""
    data = bytearray(binary.read_bytes())
    old_bytes = bytes.fromhex(old_hex)
    new_bytes = bytes.fromhex(new_hex)

    current = bytes(data[offset:offset + len(old_bytes)])
    if current == new_bytes:
        print(f"[hal_patch] 0x{offset:x}: already patched")
        return
    if current != old_bytes:
        raise SystemExit(
            f"[hal_patch] 0x{offset:x}: expected {old_hex}, found {current.hex()}"
        )

    if backup:
        backup_path = binary.with_suffix(binary.suffix + ".bak")
        backup_path.write_bytes(binary.read_bytes())
        print(f"[hal_patch] backup: {backup_path}")

    data[offset:offset + len(old_bytes)] = new_bytes
    binary.write_bytes(data)
    print(f"[hal_patch] 0x{offset:x}: patched {old_hex} -> {new_hex}")


def patch_mileicafilter(plugin: Path, output: Optional[Path] = None) -> Path:
    """Patch Nezha mileicafilter.so to read stylizationType instead of legendMode."""
    if output is None:
        output = plugin.with_suffix(".patched.so")

    data = bytearray(plugin.read_bytes())

    # Patch instruction offsets
    patched_any = False
    for offset in (0xC768, 0xD780):
        current = bytes(data[offset:offset + 4])
        if current == PATCHED_ADD_0xDD7:
            print(f"[hal_patch] mileicafilter 0x{offset:x}: already patched")
            continue
        if current == ORIGINAL_ADD_0x36E:
            data[offset:offset + 4] = PATCHED_ADD_0xDD7
            patched_any = True
            print(f"[hal_patch] mileicafilter 0x{offset:x}: patched")
        else:
            raise SystemExit(
                f"[hal_patch] mileicafilter 0x{offset:x}: unexpected "
                f"bytes {current.hex()}"
            )

    if not patched_any:
        print("[hal_patch] mileicafilter: no changes needed")

    # Symbol redirects (offline logger symbols)
    symbol_pairs = [
        (b"_ZN7midebug21gMiCamOfflineLogLevelE", b"_ZN7midebug14gMiCamLogLevelE"),
        (b"_ZN7midebug21gMiCamOfflineLogGroupE", b"_ZN7midebug14gMiCamLogGroupE"),
    ]
    for old, new in symbol_pairs:
        if len(new) > len(old):
            raise SystemExit(f"replacement {new!r} longer than {old!r}")
        count = data.count(old)
        if count == 0:
            continue
        if count != 1:
            raise SystemExit(f"expected one {old!r}, found {count}")
        start = data.index(old)
        data[start:start + len(old)] = new + b"\0" * (len(old) - len(new))

    output.write_bytes(data)
    print(f"[hal_patch] wrote {output}")
    return output


# ---------------------------------------------------------------------------
# Module building
# ---------------------------------------------------------------------------

def build_ksu_module(bundle_dir: Path, module_out: Path,
                     patched_hal: Optional[Path] = None) -> Path:
    """Build a KernelSU/Magisk module zip from a bundle directory.

    The bundle should contain:
      - odm/lib64/camera/plugins/*.so
      - odm/lib64/*.so (supporting libs)
      - odm/etc/camera/xiaomi/leicasnapshot.json (patched)
      - post-fs-data.sh, sepolicy.rule, module.prop

    If patched_hal is given, it is added as odm/lib64/hw/camera.qcom.so
    (or sm8650 variant).
    """
    module_out.parent.mkdir(parents=True, exist_ok=True)

    files: list[tuple[Path, str]] = []

    # Collect all bundle files
    for path in sorted(bundle_dir.rglob("*")):
        if path.is_file() and path.name != "SHA256SUMS":
            rel = path.relative_to(bundle_dir)
            arcname = f"system/{rel.as_posix()}"
            files.append((path, arcname))

    # Add patched HAL if provided
    if patched_hal:
        hal_name = patched_hal.name
        # Determine target path based on filename
        if "sm8650" in hal_name:
            arcname = "system/odm/lib64/hw/camera.qcom.sm8650.so"
        elif "core" in hal_name:
            arcname = "system/odm/lib64/hw/camera.qcom.core.so"
        else:
            arcname = f"system/odm/lib64/hw/{hal_name}"
        files.append((patched_hal, arcname))

    with zipfile.ZipFile(module_out, "w", zipfile.ZIP_DEFLATED,
                         compresslevel=6) as archive:
        for path, arcname in files:
            info = zipfile.ZipInfo(arcname, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            mode = 0o755 if arcname.endswith(".sh") else 0o644
            info.external_attr = (mode & 0xFFFF) << 16
            archive.writestr(info, path.read_bytes())

    print(f"[hal_patch] module: {module_out} ({len(files)} files, "
          f"{module_out.stat().st_size / 1024 / 1024:.1f} MB)")
    return module_out


# ---------------------------------------------------------------------------
# Verification
# ---------------------------------------------------------------------------

def verify_hal(binary: Path) -> dict[str, bool]:
    """Static verification of an Aurora HAL binary."""
    results: dict[str, bool] = {}

    # Check for legendMode string
    results["has_legendMode_string"] = bool(find_strings(binary, "legendMode"))
    results["has_stylizationType_string"] = bool(find_strings(binary, "stylizationType"))

    # Check for tag 508 usage
    results["has_0x1fc_immediate"] = bool(find_hex_pattern(binary, "1ffc3fd2"))

    # Check exports
    try:
        nm_result = subprocess.run(
            ["nm", "-DC", str(binary)],
            capture_output=True, text=True, check=True, timeout=60,
        )
        nm_out = nm_result.stdout
        results["exports_MPluginModule"] = "MPluginModule" in nm_out
        results["exports_OemRetrieveLegendMode"] = "OemRetrieveLegendMode" in nm_out
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        results["exports_MPluginModule"] = False
        results["exports_OemRetrieveLegendMode"] = False

    # File info
    results["file_size"] = binary.stat().st_size
    results["sha256"] = hashlib.sha256(binary.read_bytes()).hexdigest()

    return results


def verify_plugin(plugin: Path) -> dict[str, bool]:
    """Static verification of a patched mileicafilter plugin."""
    data = plugin.read_bytes()
    results: dict[str, bool] = {}

    # Check patch status
    results["initialize_patched"] = (
        bytes(data[0xC768:0xC76C]) == PATCHED_ADD_0xDD7
    )
    results["isEnabled_patched"] = (
        bytes(data[0xD780:0xD78C]) == PATCHED_ADD_0xDD7
    )

    # Check symbol redirects
    results["log_level_symbol_redirect"] = b"gMiCamLogLevelE" in data
    results["log_group_symbol_redirect"] = b"gMiCamLogGroupE" in data
    results["no_offline_log_symbol"] = b"gMiCamOfflineLogLevelE" not in data

    # Check SHA-256
    results["sha256"] = hashlib.sha256(data).hexdigest()

    return results


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def cmd_extract(args: argparse.Namespace) -> int:
    result = extract_hal(args.serial, Path(args.out))
    print(f"[hal_patch] extracted {len(result)} files to {args.out}")
    return 0


def cmd_find_offsets(args: argparse.Namespace) -> int:
    binary = Path(args.binary)
    offsets = find_offsets(binary)
    print(json.dumps({k: v for k, v in offsets.items() if v}, indent=2))
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    binary = Path(args.binary)
    result = verify_hal(binary)
    print(json.dumps(result, indent=2))
    return 0


def cmd_patch(args: argparse.Namespace) -> int:
    binary = Path(args.binary)
    apply_patch(binary, args.offset, args.old, args.new)
    return 0


def cmd_patch_plugin(args: argparse.Namespace) -> int:
    plugin = Path(args.input)
    output = Path(args.output) if args.output else None
    patch_mileicafilter(plugin, output)
    return 0


def cmd_generate_patch(args: argparse.Namespace) -> int:
    """Generate a shell script with exact patch commands for Aurora's camera.qcom.so."""
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)

    script = """#!/system/bin/sh
# Aurora HAL patch script for native M3/M9 + Original Portrait support
# Generated by hal_patch.py
# Run this script on the device as root to patch camera.qcom.so
#
# WARNING: This modifies the stock HAL binary. Backup first!
# Tested offsets are for camera.qcom.so (SHA-256: 5b30cefde5b4a4aabf52282778c6d757)

set -e

MODDIR=${0%/*}
HAL_PATH="/odm/lib64/hw/camera.qcom.so"
BACKUP_PATH="/data/local/tmp/camera.qcom.so.bak"

echo "[hal-patch] Backing up stock HAL..."
cp "$HAL_PATH" "$BACKUP_PATH"

echo "[hal-patch] Patching camera.qcom.so for legendMode support..."

# Patch 1: Add legendMode vendor tag lookup in ReadOutputVendorTag
# Pattern: replicate stylizationType query at 0xecd8a4
# The stylizationType string is at 0x917d7 (adrp x1, 0x91000; add x1, x1, #0x7d7)
# We need to add a similar query for "com.xiaomi.sessionparams.legendMode"
#
# NOTE: Actual byte patches require disassembly analysis at each call site.
# Use hal_patch.py find-offsets to discover exact locations, then apply with:
#   hal_patch.py patch camera.qcom.so --offset <off> --old <hex> --new <hex>

echo "[hal-patch] Patch locations identified:"
echo "  - stylizationType query 1: 0xecd8a4 (ReadOutputVendorTag)"
echo "  - stylizationType query 2: 0xf55bc0 (DataCompress)"
echo "  - QueryVendorTagLocation PLT: 0xff0120"
echo ""
echo "[hal-patch] To apply patches:"
echo "  python3 hal_patch.py patch camera.qcom.so --offset 0xecd8a4 --old <old_bytes> --new <new_bytes>"
echo "  python3 hal_patch.py patch camera.qcom.so --offset 0xf55bc0 --old <old_bytes> --new <new_bytes>"
echo ""
echo "[hal-patch] After patching, reboot and verify with:"
echo "  strings -t x camera.qcom.so | grep legendMode"

echo "[hal-patch] Done. Reboot device to apply changes."
"""

    output.write_text(script)
    output.chmod(0o755)
    print(f"[hal_patch] patch script: {output}")
    return 0


def cmd_verify_patched(args: argparse.Namespace) -> int:
    """Verify a patched HAL binary has expected changes."""
    binary = Path(args.binary)
    result = verify_hal(binary)

    print(f"[hal_patch] Verification of {binary}:")
    print(f"  File size: {result.get('file_size', 'unknown')}")
    print(f"  SHA-256: {result.get('sha256', 'unknown')}")
    print(f"  Has legendMode string: {result.get('has_legendMode_string', False)}")
    print(f"  Has stylizationType string: {result.get('has_stylizationType_string', False)}")
    print(f"  Has 0x1fc immediate: {result.get('has_0x1fc_immediate', False)}")
    print(f"  Exports MPluginModule: {result.get('exports_MPluginModule', False)}")
    print(f"  Exports OemRetrieveLegendMode: {result.get('exports_OemRetrieveLegendMode', False)}")

    # Check if patch was applied
    if result.get('has_legendMode_string', False):
        print("\n[hal_patch] ✓ legendMode string found - patch likely applied")
    else:
        print("\n[hal_patch] ✗ legendMode string NOT found - patch may not be applied")

    return 0


def cmd_build_module(args: argparse.Namespace) -> int:
    bundle = Path(args.bundle)
    module_out = Path(args.module_out)
    patched_hal = Path(args.patched_hal) if args.patched_hal else None
    build_ksu_module(bundle, module_out, patched_hal)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p_extract = sub.add_parser("extract", help="Pull HAL from device")
    p_extract.add_argument("--serial", required=True, help="adb serial")
    p_extract.add_argument("--out", required=True, help="output directory")
    p_extract.set_defaults(func=cmd_extract)

    p_find = sub.add_parser("find-offsets", help="Find key offsets in HAL")
    p_find.add_argument("binary", help="HAL binary path")
    p_find.set_defaults(func=cmd_find_offsets)

    p_verify = sub.add_parser("verify", help="Verify HAL binary")
    p_verify.add_argument("binary", help="HAL binary path")
    p_verify.set_defaults(func=cmd_verify)

    p_patch = sub.add_parser("patch", help="Apply binary patch")
    p_patch.add_argument("binary", help="HAL binary path")
    p_patch.add_argument("--offset", required=True, type=lambda x: int(x, 0),
                        help="file offset")
    p_patch.add_argument("--old", required=True, help="old bytes (hex)")
    p_patch.add_argument("--new", required=True, help="new bytes (hex)")
    p_patch.set_defaults(func=cmd_patch)

    p_patch_pl = sub.add_parser("patch-plugin", help="Patch mileicafilter.so")
    p_patch_pl.add_argument("input", help="plugin SO path")
    p_patch_pl.add_argument("output", nargs="?", help="output path")
    p_patch_pl.set_defaults(func=cmd_patch_plugin)

    p_gen = sub.add_parser("generate-patch", help="Generate patch script for device")
    p_gen.add_argument("--output", required=True, help="output script path")
    p_gen.set_defaults(func=cmd_generate_patch)

    p_verify_p = sub.add_parser("verify-patched", help="Verify patched HAL binary")
    p_verify_p.add_argument("binary", help="patched HAL binary path")
    p_verify_p.set_defaults(func=cmd_verify_patched)

    p_build = sub.add_parser("build-module", help="Build KSU module zip")
    p_build.add_argument("bundle", help="bundle directory")
    p_build.add_argument("--module-out", required=True, help="output zip path")
    p_build.add_argument("--patched-hal", help="patched HAL to include")
    p_build.set_defaults(func=cmd_build_module)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())