#!/usr/bin/env python3
"""MiNN container parser for QNN style-transfer models.

Parses .minn files (MiNN container, magic: 4e 4e 69 4d = "NNiM")
to extract the embedded model blob and identify its format.

Usage:
    python3 extract_minn.py <model.minn> [output_dir]
"""

from __future__ import annotations

import json
import struct
import sys
from pathlib import Path

# MiNN container magic
NNiM_MAGIC = b"\x4e\x4e\x69\x4d"  # "NNiM"


def parse_minn(model_path: Path, output_dir: Path | None = None) -> dict:
    """Parse a .minn MiNN container file.

    Returns a dict with container info and, if output_dir is given,
    writes the extracted embedded model blob.
    """
    data = model_path.read_bytes()
    size = len(data)

    # Check magic
    if data[:4] != NNiM_MAGIC:
        raise ValueError(f"Not a MiNN file: magic={data[:4].hex()}")

    info: dict = {
        "file": str(model_path),
        "size": size,
        "magic": data[:4].hex(),
        "format": "unknown",
        "embedding_offset": None,
        "embedding_size": None,
    }

    # MiNN container layout (reverse-engineered from sample files):
    #   offset 0x00: magic "NNiM" (4 bytes)
    #   offset 0x04: version or header size (4 bytes, LE uint32)
    #   offset 0x08: total container size or model offset (4 bytes, LE uint32)
    #   ... more header fields ...
    #   embedded model blob follows the header

    # Read some header fields for inspection
    if size >= 16:
        header_dword1 = struct.unpack_from("<I", data, 4)[0]
        header_dword2 = struct.unpack_from("<I", data, 8)[0]
        header_dword3 = struct.unpack_from("<I", data, 12)[0]
        info["header_dword1"] = header_dword1
        info["header_dword2"] = header_dword2
        info["header_dword3"] = header_dword3

    # Try to identify embedded model format by scanning for known magics
    # ONNX: starts with protobuf, no fixed magic but has "onnx" strings
    # TFLite: starts with "TFL3"
    # QNN binary: may have QNN-specific headers
    scan_results = []
    for magic, name in [
        (b"TFL3", "TensorFlow Lite"),
        (b"ONNX", "ONNX"),
        (b"QNN", "QNN binary"),
        (b"\x1f\x8b", "gzip"),
        (b"\x7fELF", "ELF"),
    ]:
        idx = data.find(magic, 4)  # skip the NNiM magic
        if idx >= 0:
            scan_results.append({"offset": idx, "magic": magic.hex(), "format": name})

    info["embedded_candidates"] = scan_results

    # If we found a TFLite or ONNX magic, mark the format
    for candidate in scan_results:
        if candidate["format"] == "TensorFlow Lite":
            info["format"] = "tflite"
            info["embedding_offset"] = candidate["offset"]
        elif candidate["format"] == "ONNX":
            info["format"] = "onnx"
            info["embedding_offset"] = candidate["offset"]

    # If no known format found, the embedded model may start right after header
    if info["format"] == "unknown" and size > 64:
        info["embedding_offset"] = 64  # Common: header is ~64 bytes
        info["embedding_size"] = size - 64

    if output_dir:
        output_dir.mkdir(parents=True, exist_ok=True)
        out_path = output_dir / f"{model_path.stem}.extracted"
        if info["embedding_offset"] is not None:
            emb_size = info.get("embedding_size") or (size - info["embedding_offset"])
            out_path.write_bytes(data[info["embedding_offset"]:info["embedding_offset"] + emb_size])
            info["extracted_to"] = str(out_path)
            info["extracted_size"] = emb_size

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