#!/usr/bin/env python3
"""Insert LegendFilterInstance0 into an Aurora snapshot JSON.

The node goes immediately before the stock filter node and takes over its
input link, so the chain becomes:

    <upstream> -> LegendFilterInstance0 -> FilterInstance0 -> Watermark

The stock filter is left in place (a pass-through when no look is selected).
"""
import json
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    path = sys.argv[1]
    doc = json.load(open(path))
    nodes = doc["NodesList"][list(doc["NodesList"])[0]]
    if not any(n.get("NodeInstance") == "LegendFilterInstance0" for n in nodes):
        idx = next(i for i, n in enumerate(nodes) if n.get("NodeInstance") == "FilterInstance0")
        nodes.insert(idx, {"NodeName": "com.xiaomi.plugin.hcmlegend",
                           "NodeInstance": "LegendFilterInstance0"})
    links = doc["PortLinkages"][list(doc["PortLinkages"])[0]]
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
    json.dump(doc, open(path, "w"), indent=2)
    print(f"patched {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
