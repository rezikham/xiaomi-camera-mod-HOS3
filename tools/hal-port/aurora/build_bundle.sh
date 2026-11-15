#!/usr/bin/env bash
# Build an experimental Magisk/KernelSU overlay that adds the Nezha (17 Ultra)
# M3/M9 Legendary plugin stack to an Aurora (14 Ultra) firmware tree.
#
# This does not modify the source firmware. It copies only the files the HAL
# needs and generates a patched `leicasnapshot.json`. Flash the resulting
# directory as a Magisk module on a rooted device with KernelSU/Magisk; use
# package_module.py to create a flashable zip with the `system/odm` layout.
#
# Usage:
#   tools/hal-port/aurora/build_bundle.sh [OUT_DIR]
#
# Environment overrides:
#   NEZHA_ODM   path to extracted 17 Ultra odm (default 970/extracted_nezha/odm)
#   AURORA_ODM  path to extracted 14 Ultra odm (default 970/extracted_aurora/odm)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
NEZHA_ODM="${NEZHA_ODM:-$ROOT/970/extracted_nezha/odm}"
AURORA_ODM="${AURORA_ODM:-$ROOT/970/extracted_aurora/odm}"
OUT="${1:-${OUT:-$ROOT/out/aurora-legend-port}}"

log() { printf '[aurora-legend] %s\n' "$*"; }
die() { printf '[aurora-legend] error: %s\n' "$*" >&2; exit 1; }

[ -d "$NEZHA_ODM" ] || die "missing Nezha odm: $NEZHA_ODM"
[ -d "$AURORA_ODM" ] || die "missing Aurora odm: $AURORA_ODM"

# Required donor files. Keep this list explicit: a silent partial copy would
# produce a module that fails to load a plugin at capture time.
FILES=(
  "lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so"
  "lib64/libcom.xiaomi.cam.miexifmgr.so"
  "lib64/libcameraopt_stub.so"
  "lib64/libmetatagwrapper.so"
  "etc/camera/leica_filter_param.bin"
  "etc/camera/leica_filter_param_m3.bin"
  "etc/camera/leica_filter_param_m9_preview.bin"
  "etc/camera/leica_filter_param_m9_snapshot.bin"
)
for rel in "${FILES[@]}"; do
  [ -f "$NEZHA_ODM/$rel" ] || die "missing donor file: $NEZHA_ODM/$rel"
done

log "cleaning $OUT"
rm -rf "$OUT"
mkdir -p "$OUT/odm/lib64/camera/plugins" "$OUT/odm/lib64" \
         "$OUT/odm/etc/camera/xiaomi"

for rel in "${FILES[@]}"; do
  install -D -m 0644 "$NEZHA_ODM/$rel" "$OUT/odm/$rel"
done

# Aurora's engine has no legendMode -> MiMetadata tag 508 path. Patch the
# plugin to read stylizationType, and leave a marker so the module knows to
# bridge the app's legendMode request into that key.
PLUGIN="$OUT/odm/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so"
python3 "$ROOT/tools/hal-port/aurora/patch_plugin.py" "$PLUGIN" "$PLUGIN.patched"
mv "$PLUGIN.patched" "$PLUGIN"

: > "$OUT/odm/etc/camera/hypercameramod_legend_bridge"

python3 "$ROOT/tools/hal-port/aurora/patch_leicasnapshot.py" \
  "$AURORA_ODM/etc/camera/xiaomi/leicasnapshot.json" \
  "$OUT/odm/etc/camera/xiaomi/leicasnapshot.json"

# Aurora 6.7 selects SatSnapshot for ordinary Leica captures, so the same
# LeicaFilter node must be present there too.
python3 "$ROOT/tools/hal-port/aurora/patch_leicasnapshot.py" \
  "$AURORA_ODM/etc/camera/xiaomi/satsnapshot.json" \
  "$OUT/odm/etc/camera/xiaomi/satsnapshot.json"

# The app process may not share the HAL's mount namespace, so the module
# advertises the overlay through properties as well as files. HalCapabilities
# treats these as a positive override; the files stay the source of truth for
# stock nezha/madrid.
cat > "$OUT/post-fs-data.sh" <<'EOF'
#!/system/bin/sh
RESETPROP=/data/adb/ksu/bin/resetprop
[ -x "$RESETPROP" ] || RESETPROP=resetprop
"$RESETPROP" -n persist.sys.hypercam.legend 1
"$RESETPROP" -n persist.sys.hypercam.style_transfer 0
"$RESETPROP" -n persist.sys.hypercam.bridge 1
"$RESETPROP" -n persist.sys.hypercam.m10r 0
EOF
chmod 0755 "$OUT/post-fs-data.sh"

# KernelSU mounts module files with the system_file label. The camera HAL
# domain may read /odm/vendor_file but not /odm/system_file, so allow the
# overlay's plugins and parameter files explicitly.
cat > "$OUT/sepolicy.rule" <<'EOF'
allow hal_camera_default system_file file { read open getattr map execute execute_no_trans }
EOF

cat > "$OUT/module.prop" <<'EOF'
id=hypercam-aurora-legend
name=HyperCameraMod Aurora Legend HAL port
version=v0.1-experimental
versionCode=1
author=HyperCameraMod
description=Experimental Aurora mileicafilter/LeicaFilter pipeline port; plugin registration is still unresolved.
EOF

cat > "$OUT/README.md" <<'EOF'
# Aurora Legend HAL port (experimental)

This overlay adds the 17 Ultra (nezha) M3/M9 Legendary snapshot processing to a
14 Ultra (aurora) firmware:

* `com.xiaomi.plugin.mileicafilter` and its Leica parameter files;
* `com.xiaomi.plugin.legendST`, `libmialgo_styletrans`, `libLMSceneSeg` and the
  style-transfer models;
* the metadata/EXIF/cameraopt compatibility libraries the plugins link;
* a `leicasnapshot.json` with `LeicaFilterInstance0` and `StyleTransInstance0`.

It does **not** add M10R: that needs `legendCT`, the M10R parameter files and
the two M10R models, which the 17 Ultra does not have.

## Install

1. Run `tools/hal-port/aurora/package_module.py <bundle>` and install the
   resulting zip with `ksud module install` (KernelSU) or the Magisk app.
   `/odm` is read-only EROFS, so do not try to copy files into it directly.
2. Reboot. The overlay is a `system/odm/...` module; clear the Camera app data
   only if the pipeline is cached, then open the camera.
3. Select Leica Essential -> M3 or M9. If the camera process fails, capture
   `logcat -b all -d` and restore the stock `leicasnapshot.json`.

This is a static port: it has not been validated on a physical 14 Ultra. See
`docs/hal-backport/` for the evidence and the failure modes.
EOF

(
  cd "$OUT"
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)

log "bundle written to $OUT"
du -sh "$OUT"
find "$OUT" -type f -printf '%P\n' | sort | sed 's/^/  /'
