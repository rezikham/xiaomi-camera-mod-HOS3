#!/usr/bin/env python3
"""Insert LegendFilterInstance0 into an Aurora snapshot JSON.

The node goes immediately before the stock filter node and takes over its
input link, so the chain becomes:

    <upstream> -> LegendFilterInstance0 -> FilterInstance0 -> Watermark

The stock filter is left in place (a pass-through when no look is selected).

Usage: patch_legend_snapshot.py <snapshot.json>

Idempotent: running multiple times produces the same result.
"""
import json
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    if not path.is_file():
        print(f"error: {path} not found", file=sys.stderr)
        return 1
    
    doc = json.load(open(path))
    nodes_dict = doc["NodesList"]
    # NodesList is a dict with one key (pipeline name) -> list of nodes
    pipeline_key = list(nodes_dict)[0]
    nodes = nodes_dict[pipeline_key]
    
    # Check if already patched
    if any(n.get("NodeInstance") == "LegendFilterInstance0" for n in nodes):
        print(f"{path}: already patched (LegendFilterInstance0 present)")
        return 0
    
    # Find FilterInstance0 index
    try:
        idx = next(i for i, n in enumerate(nodes) if n.get("NodeInstance") == "FilterInstance0")
    except StopIteration:
        print(f"{path}: FilterInstance0 not found, cannot insert", file=sys.stderr)
        return 1
    
    # Insert LegendFilterInstance0 before FilterInstance0
    nodes.insert(idx, {"NodeName": "com.xiaomi.plugin.hcmlegend",
                       "NodeInstance": "LegendFilterInstance0"})
    
    # Rewire PortLinkages: redirect upstream -> LegendFilterInstance0 -> FilterInstance0
    links_dict = doc["PortLinkages"]
    link_key = list(links_dict)[0]
    links = links_dict[link_key]
    
    for i, link in enumerate(links):
        dst = link.get("DstPort", {})
        if dst.get("NodeInstance") != "FilterInstance0":
            continue
        src = link["SrcPort"]
        links[i:i + 1] = [
            {"SrcPort": dict(src),
             "DstPort": {"PortId": 0, "NodeInstance": "LegendFilterInstance0",
                         "BufferType": "Gralloc"}},
            {"SrcPort": {"PortId": 0, "NodeInstance": "LegendFilterInstance0",
                         "BufferType": "Gralloc"},
             "DstPort": dict(dst)},
        ]
        break
    
    # Write back with consistent formatting
    json.dump(doc, open(path, "w"), indent=2)
    open(path, "a").write("\n")  # trailing newline
    print(f"patched {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
