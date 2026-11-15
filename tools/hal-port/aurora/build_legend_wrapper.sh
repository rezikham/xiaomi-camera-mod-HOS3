#!/usr/bin/env bash
# Build and package an experimental Aurora Legend wrapper plugin.
#
# The wrapper is a normal Chi plugin that forwards the pixel request to the
# Nezha algorithm it dlopens privately. It is inserted as LegendFilterInstance0
# in front of the stock filter node, so stock filters keep working.
#
# Usage: build_legend_wrapper.sh [OUT_DIR]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
NEZHA_ODM="${NEZHA_ODM:-$ROOT/970/extracted_nezha/odm}"
AURORA_ODM="${AURORA_ODM:-$ROOT/970/extracted_aurora/odm}"
OUT="${1:-${OUT:-$ROOT/out/hcm-legend}}"
SRC="$HERE/hcm_legend_plugin/hcm_legend.c"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
[ -f "$SRC" ] || die "missing $SRC"
[ -d "$NEZHA_ODM" ] || die "missing Nezha odm: $NEZHA_ODM"
[ -d "$AURORA_ODM" ] || die "missing Aurora odm: $AURORA_ODM"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

clang --target=aarch64-linux-android21 -fPIC -O2 -c "$SRC" -o "$TMP/hcm_legend.o"
LIBS="${ANDROID_LIBS:-/tmp/opencode/android-libs}"
LINK_LIBS=""
[ -d "$LIBS" ] && LINK_LIBS="-L$LIBS -Wl,--no-as-needed -lc -ldl -llog -l:libcom.xiaomi.pluginutils.so -l:libhcm_shim.so -l:com.xiaomi.plugin.mileicafilter.so"
clang --target=aarch64-linux-android21 -fPIC -shared -nostdlib \
  -Wl,-soname,com.xiaomi.plugin.hcmlegend.so $LINK_LIBS \
  -o "$TMP/com.xiaomi.plugin.hcmlegend.so" "$TMP/hcm_legend.o"

rm -rf "$OUT"
mkdir -p "$OUT/system/odm/lib64/camera/plugins" "$OUT/system/odm/lib64/hcm" \
         "$OUT/system/odm/etc/camera/xiaomi"

clang --target=aarch64-linux-android21 -fPIC -shared -nostdlib \
  -Wl,-soname,libhcm_shim.so -o "$TMP/libhcm_shim.so" "$HERE/compat_shim.S"
install -m 0644 "$TMP/libhcm_shim.so" "$OUT/system/odm/lib64/libhcm_shim.so"

install -m 0644 "$TMP/com.xiaomi.plugin.hcmlegend.so" \
  "$OUT/system/odm/lib64/camera/plugins/com.xiaomi.plugin.hcmlegend.so"
# Private libc++: Aurora's system one has no std::__1::__fs::filesystem, which
# the donor needs. Ship the Nezha one under a second SONAME and repoint the
# donor (and the companions built with it).
NEZHA_CXX="$NEZHA_ODM/../vendor/lib64/libc++.so"
[ -f "$NEZHA_CXX" ] || NEZHA_CXX="$NEZHA_ODM/lib64/libc++.so"
install -m 0644 "$NEZHA_CXX" "$OUT/system/odm/lib64/hcicxx.so"
install -m 0644 "$NEZHA_ODM/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so" \
  "$OUT/system/odm/lib64/com.xiaomi.plugin.mileicafilter.so"
python3 "$HERE/patch_private_libcxx.py" "$OUT/system/odm/lib64/hcicxx.so" \
  "$OUT/system/odm/lib64/com.xiaomi.plugin.mileicafilter.so"
for f in leica_filter_param.bin leica_filter_param_m3.bin \
         leica_filter_param_m9_preview.bin leica_filter_param_m9_snapshot.bin; do
  install -m 0644 "$NEZHA_ODM/etc/camera/$f" "$OUT/system/odm/etc/camera/$f"
done
for f in libcom.xiaomi.cam.miexifmgr.so libcameraopt_stub.so libmetatagwrapper.so; do
  [ -f "$NEZHA_ODM/lib64/$f" ] && install -m 0644 "$NEZHA_ODM/lib64/$f" "$OUT/system/odm/lib64/$f"
done

for snap in leicasnapshot.json satsnapshot.json normalsnapshot.json superhdsnapshot.json; do
  [ -f "$AURORA_ODM/etc/camera/xiaomi/$snap" ] || continue
  install -m 0644 "$AURORA_ODM/etc/camera/xiaomi/$snap" \
    "$OUT/system/odm/etc/camera/xiaomi/$snap"
  python3 "$HERE/patch_legend_snapshot.py" "$OUT/system/odm/etc/camera/xiaomi/$snap"
done

cat > "$OUT/sepolicy.rule" <<'RULE'
allow hal_camera_default system_file file { open read getattr map execute }
allow hal_camera_default system_file dir { open read getattr search }
RULE

cat > "$OUT/module.prop" <<'PROP'
id=hcm-legend-wrapper
name=HCM Legend wrapper (experimental)
version=v1
versionCode=1
author=HyperCameraMod
description=Adds LegendFilterInstance0 (M3/M9 via the Nezha algorithm) in front of the stock filter node.
PROP

ZIP="${OUT%/}.zip"
rm -f "$ZIP"
python3 - "$OUT" "$ZIP" <<'PY'
import os, sys, zipfile
out, zip_path = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(out):
        for name in files:
            full = os.path.join(root, name)
            z.write(full, os.path.relpath(full, out))
print("packaged", zip_path)
PY
echo "bundle: $OUT"
echo "zip   : $ZIP"
