#!/usr/bin/env bash
# Static checks for an Aurora Legend port bundle. It does not run the camera.
#
# Usage:
#   tools/hal-port/aurora/verify_bundle.sh [BUNDLE_DIR]
#
# Environment:
#   AURORA_ODM  extracted 14 Ultra odm (default 970/extracted_aurora/odm)
#   AURORA_VENDOR extracted 14 Ultra vendor (default 970/extracted_aurora/vendor)
#   AURORA_SYS  extracted 14 Ultra system mount (default /tmp/opencode/aurora_sys/system)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BUNDLE="${1:-${OUT:-$ROOT/out/aurora-legend-port}}"
AURORA_ODM="${AURORA_ODM:-$ROOT/970/extracted_aurora/odm}"
AURORA_VENDOR="${AURORA_VENDOR:-$ROOT/970/extracted_aurora/vendor}"
AURORA_SYS="${AURORA_SYS:-/tmp/opencode/aurora2_system/system}"

fail=0
say() { printf '%s\n' "$*"; }
bad() { printf 'MISSING: %s\n' "$*"; fail=1; }

[ -d "$BUNDLE" ] || { echo "no bundle: $BUNDLE" >&2; exit 2; }

say "== required files"
required=(
  "odm/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so"
  "odm/lib64/camera/plugins/com.xiaomi.plugin.legendST.so"
  "odm/lib64/libmialgo_styletrans.so"
  "odm/lib64/libLMSceneSeg.so"
  "odm/lib64/libcom.xiaomi.cam.miexifmgr.so"
  "odm/lib64/libcameraopt_stub.so"
  "odm/lib64/libmetatagwrapper.so"
  "odm/etc/camera/leica_filter_param.bin"
  "odm/etc/camera/leica_filter_param_m3.bin"
  "odm/etc/camera/leica_filter_param_m9_preview.bin"
  "odm/etc/camera/leica_filter_param_m9_snapshot.bin"
  "odm/etc/camera/styletrans_params.json"
  "odm/etc/camera/styletrans/compressed/styletrans_high.minn"
  "odm/etc/camera/xiaomi/leicasnapshot.json"
  "odm/etc/camera/hypercameramod_legend_bridge"
)
for rel in "${required[@]}"; do
  if [ -f "$BUNDLE/$rel" ]; then
    printf '  ok  %s\n' "$rel"
  else
    bad "$rel"
  fi
done

say "== plugin bridge patch"
python3 "$ROOT/tools/hal-port/aurora/patch_plugin.py" --check \
  "$BUNDLE/odm/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so" || fail=1

say "== pipeline nodes"
python3 - "$BUNDLE/odm/etc/camera/xiaomi/leicasnapshot.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
nodes = [n["NodeInstance"] for n in d["NodesList"]["Node"]]
links = d["PortLinkages"]["Link"]
need = ["LeicaFilterInstance0", "StyleTransInstance0"]
for name in need:
    print(("  ok  " if name in nodes else "  MISSING ") + name)
edges = [(l["SrcPort"]["NodeInstance"], l["DstPort"]["NodeInstance"]) for l in links]
for edge in [("FilterInstance0", "LeicaFilterInstance0"),
             ("LeicaFilterInstance0", "WatermarkInstance0"),
             ("YuvReprocessInstance0", "StyleTransInstance0"),
             ("StyleTransInstance0", "DepurpleInstance0")]:
    print(("  ok  " if edge in edges else "  MISSING ") + f"{edge[0]} -> {edge[1]}")
PY

say "== dynamic dependencies"
# Search order mirrors the vendor/system layout a camera provider sees.
search_lib() {
  local name="$1" dir
  for dir in "$BUNDLE/odm/lib64" "$BUNDLE/odm/lib64/camera/plugins" \
             "$AURORA_ODM/lib64" "$AURORA_ODM/lib64/camera/plugins" \
             "$AURORA_VENDOR/lib64" "$AURORA_SYS/lib64"; do
    { [ -e "$dir/$name" ] || [ -L "$dir/$name" ]; } && return 0
  done
  return 1
}

while IFS= read -r -d '' lib; do
  rel="${lib#"$BUNDLE/"}"
  while IFS= read -r needed; do
    [ -n "$needed" ] || continue
    if ! search_lib "$needed"; then
      bad "$rel needs $needed"
    fi
  done < <(readelf -d "$lib" 2>/dev/null | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
done < <(find "$BUNDLE/odm" -type f \( -name '*.so' \) -print0)

if [ "$fail" -ne 0 ]; then
  say "== result: FAILED"
  exit 1
fi
say "== result: OK (static checks only; no device execution)"
