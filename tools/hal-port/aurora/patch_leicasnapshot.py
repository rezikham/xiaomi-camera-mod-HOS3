#!/usr/bin/env python3
"""Insert the Legendary plugins into an Aurora (14 Ultra) LeicaSnapshot pipeline.

The stock 14 Ultra leicasnapshot.json has a single `FilterInstance0`
(`com.xiaomi.plugin.filter`) between MLAWB and Watermark. The 17 Ultra
`legendsnapshot.json` adds two plugins in the same chain:

    ... -> LDC -> LeicaFilterInstance0 (mileicafilter) -> Watermark ...
    ... -> YuvReprocess -> StyleTransInstance0 (legendST) -> Depurple ...

This script creates the same two nodes while keeping the existing Aurora nodes,
formats, and follow links. It is intentionally conservative: if the expected
edge is missing it fails instead of guessing, because a wrong pipeline edge
makes the camera provider drop the session.

Usage:
    patch_leicasnapshot.py INPUT OUTPUT [--with-styletrans] [--check]

The output is pretty-printed JSON and is safe to compare with `git diff`.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path


def node_list(doc: dict) -> list[dict]:
    return doc["NodesList"]["Node"]


def link_list(doc: dict) -> list[dict]:
    return doc["PortLinkages"]["Link"]


def node_names(doc: dict) -> set[str]:
    return {n["NodeInstance"] for n in node_list(doc)}


def add_node(doc: dict, node_name: str, instance: str) -> bool:
    if instance in node_names(doc):
        return False
    node_list(doc).append({"NodeName": node_name, "NodeInstance": instance})
    return True


def insert_between(
    doc: dict,
    before: str,
    after: str,
    new_instance: str,
    *,
    port_format: str | None = None,
    buffer_type: str | None = None,
) -> None:
    """Rewire one edge `before -> after` as `before -> new -> after`."""
    links = link_list(doc)
    for index, link in enumerate(links):
        src = link.get("SrcPort", {})
        dst = link.get("DstPort", {})
        if src.get("NodeInstance") != before or dst.get("NodeInstance") != after:
            continue

        # The input side of the new node inherits the old destination fields.
        new_dst = copy.deepcopy(dst)
        new_dst["NodeInstance"] = new_instance
        new_dst["PortId"] = 0
        if port_format is not None:
            new_dst["PortFormat"] = port_format
        if buffer_type is not None:
            new_dst["BufferType"] = buffer_type

        # The output side inherits the old source fields, but is a new port.
        new_src = copy.deepcopy(src)
        new_src["NodeInstance"] = new_instance
        new_src["PortId"] = 0
        if port_format is not None:
            new_src["PortFormat"] = port_format
        if buffer_type is not None:
            new_src["BufferType"] = buffer_type

        old_dst = copy.deepcopy(dst)
        link["DstPort"] = new_dst
        links.insert(index + 1, {"SrcPort": new_src, "DstPort": old_dst})
        return

    raise SystemExit(
        f"expected edge {before} -> {after} not found; refusing to guess a pipeline order"
    )


def validate(doc: dict) -> None:
    names = node_names(doc)
    allowed_external = {"SourceBuffer0", "SinkBuffer", "SinkBuffer0"}
    missing: set[str] = set()
    for link in link_list(doc):
        for key in ("SrcPort", "DstPort"):
            instance = link.get(key, {}).get("NodeInstance")
            if instance and instance not in names and instance not in allowed_external:
                missing.add(instance)
    if missing:
        raise SystemExit("pipeline references missing nodes: " + ", ".join(sorted(missing)))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument(
        "--with-styletrans",
        action="store_true",
        help="also insert legendST before DepurpleInstance0",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="validate the input only; do not write the output",
    )
    args = parser.parse_args()

    doc = json.loads(args.input.read_text())
    validate(doc)

    if args.check:
        print(f"{args.input}: {len(node_list(doc))} nodes, {len(link_list(doc))} links")
        return 0

    added: list[str] = []
    if add_node(doc, "com.xiaomi.plugin.mileicafilter", "LeicaFilterInstance0"):
        added.append("LeicaFilterInstance0")
    insert_between(
        doc,
        "FilterInstance0",
        "WatermarkInstance0",
        "LeicaFilterInstance0",
        port_format="YUV420_NV12",
        buffer_type="Gralloc",
    )

    if args.with_styletrans:
        if add_node(doc, "com.xiaomi.plugin.legendST", "StyleTransInstance0"):
            added.append("StyleTransInstance0")
        insert_between(
            doc,
            "YuvReprocessInstance0",
            "DepurpleInstance0",
            "StyleTransInstance0",
            port_format="YUV420_NV12",
        )

    validate(doc)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n")
    print(
        f"wrote {args.output}: added {', '.join(added) if added else 'nothing (already patched)'}; "
        f"{len(node_list(doc))} nodes, {len(link_list(doc))} links"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
