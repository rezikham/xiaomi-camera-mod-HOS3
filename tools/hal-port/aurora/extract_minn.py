#!/usr/bin/env python3
"""MiNN container parser and decryptor for Xiaomi QNN models.

Parses and decrypts .minn files (MiNN container, magic: 4e 4e 69 4d = "NNiM")
used by libmage.so (mage::NNDecrypt -> mage::NNEncrypt).

Verified container layouts:
  - NNiM v1 (e.g. 17 Ultra / nezha, QNN v2.33, HTP V81):
      30-byte (0x1e) header; entire payload XORed with 16-byte key
      "legenddddddddddd" ("legend" padded with 'd' = 0x64).
  - NNiM v2 (e.g. 18 Pro Max / madrid, QNN v2.47, HTP V85):
      84-byte (0x54) header containing input/output tensor names and payload
      length; first 0x10000 (64 KiB) of payload XORed with "legenddddddddddd",
      remainder stored in plaintext.

Usage:
    python3 extract_minn.py <model.minn> [output_dir]
"""

from __future__ import annotations

import json
import re
import struct
import sys
from pathlib import Path

NNIM_MAGIC = b"\x4e\x4e\x69\x4d"  # "NNiM"
DEFAULT_KEY_STR = "legend"
PARTIAL_XOR_LEN_V2 = 0x10000


def make_xor_key16(key: str = DEFAULT_KEY_STR) -> bytes:
    """Pad key to 16 bytes with 'd' (0x64), matching mage::NNEncrypt."""
    raw = key.encode("ascii")
    if len(raw) >= 16:
        return raw[:16]
    return raw + b"d" * (16 - len(raw))


def xor_bytes(data: bytes, key16: bytes, length: int) -> bytes:
    """XOR the first `length` bytes of `data` with repeating 16-byte `key16`."""
    n = min(len(data), length)
    out = bytearray(data)
    for i in range(n):
        out[i] ^= key16[i & 15]
    return bytes(out)


def parse_tensor_meta(meta: bytes) -> list[dict]:
    """Extract tensor names, 4D shapes, and quantization scale/offset from QNN SystemContext header."""
    tensors = []
    for tok in (b"input_1", b"output_1", b"input", b"output"):
        idx = meta.find(tok)
        if idx < 0:
            continue
        if tok in (b"input", b"output") and (b"input_1" in meta or b"output_1" in meta):
            continue
        entry: dict = {"name": tok.decode("ascii"), "meta_offset": idx}
        if idx >= 20:
            dims = list(struct.unpack_from("<4I", meta, idx - 20))
            if dims[0] == 1 and all(1 <= d <= 16384 for d in dims):
                entry["shape"] = dims
        # Scale/offset sit immediately after the 4-byte-aligned null-terminated tensor name.
        name_end = idx + len(tok) + 1
        aligned = (name_end + 3) & ~3
        for off in range(aligned, min(len(meta) - 8, aligned + 16), 4):
            scale = struct.unpack_from("<f", meta, off)[0]
            if 1e-7 < scale < 1.0 and scale == scale:
                zero_off = struct.unpack_from("<i", meta, off + 4)[0]
                entry["scale"] = round(scale, 9)
                entry["offset"] = zero_off
                break
        tensors.append(entry)
    return tensors


def parse_minn(
    model_path: Path,
    output_dir: Path | None = None,
    key: str = DEFAULT_KEY_STR,
) -> dict:
    """Parse and decrypt a .minn MiNN container file."""
    data = model_path.read_bytes()
    size = len(data)
    if size < 32 or data[:4] != NNIM_MAGIC:
        raise ValueError(f"Not a MiNN file: magic={data[:4].hex()}")

    key16 = make_xor_key16(key)
    ver_major = struct.unpack_from("<I", data, 4)[0]
    ver_minor = struct.unpack_from("<I", data, 8)[0]

    # Locate payload start (first encrypted QNN header bytes start with "legg" = 00 00 00 02 ^ "lege")
    payload_offset = data.find(b"legg", 16, 256)
    if payload_offset < 0:
        payload_offset = data.find(key16[:3], 16, 256)
    if payload_offset < 0:
        raise ValueError("Could not locate encrypted QNN payload inside MiNN container")

    raw_payload = data[payload_offset:]
    # v2 containers (payload_offset >= 0x40) encrypt only the first 64 KiB; v1 encrypts the whole payload.
    is_partial_v2 = payload_offset >= 0x40 and raw_payload[-4096:].count(0) > 100
    xor_len = PARTIAL_XOR_LEN_V2 if is_partial_v2 else len(raw_payload)
    decrypted = xor_bytes(raw_payload, key16, xor_len)

    qnn_hdr = struct.unpack_from("<4I3Q", decrypted, 0)
    meta_size = qnn_hdr[4]
    blob_offset = qnn_hdr[5]
    blob_size = qnn_hdr[6]

    scan_window = decrypted[: min(len(decrypted), 262144)]
    sdk_match = re.search(rb"v2\.\d+\.\d+\.\d+[\w_]*", scan_window)
    soc_match = re.search(rb"soc_type=[^;\x00]+", scan_window)
    arch_match = re.search(rb"(?:^|;)arch=(\d+)", scan_window)
    min_arch_match = re.search(rb"min_arch=(\d+)", scan_window)
    graph_strs = [
        s.decode("ascii")
        for s in re.findall(rb"[A-Za-z0-9_]{3,}", decrypted[:0x400])
        if s not in (b"input_1", b"output_1", b"input", b"output") and not s.startswith(b"v2.")
    ]

    info: dict = {
        "file": str(model_path),
        "size": size,
        "magic": data[:4].decode("ascii"),
        "container_version_fields": [ver_major, ver_minor],
        "payload_offset": payload_offset,
        "payload_size": len(raw_payload),
        "xor_key16": key16.decode("ascii", errors="replace"),
        "xor_encrypted_bytes": xor_len,
        "qnn_meta_size": meta_size,
        "qnn_blob_offset": blob_offset,
        "qnn_blob_size": blob_size,
        "qnn_sdk_version": sdk_match.group(0).decode("ascii") if sdk_match else None,
        "soc_type": soc_match.group(0).decode("ascii") if soc_match else None,
        "htp_arch": int(arch_match.group(1)) if arch_match else None,
        "htp_min_arch": int(min_arch_match.group(1)) if min_arch_match else None,
        "graph_name_candidates": graph_strs[:3],
        "tensors": parse_tensor_meta(decrypted[:0x400]),
    }

    if output_dir:
        output_dir.mkdir(parents=True, exist_ok=True)
        out_path = output_dir / f"{model_path.stem}.qnn.bin"
        out_path.write_bytes(decrypted)
        info["decrypted_to"] = str(out_path)
        info["decrypted_size"] = len(decrypted)

    return info


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    model_path = Path(sys.argv[1])
    if not model_path.is_file():
        print(f"error: not a file: {model_path}", file=sys.stderr)
        return 1

    output_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    info = parse_minn(model_path, output_dir)
    print(json.dumps(info, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
