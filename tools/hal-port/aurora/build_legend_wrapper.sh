#!/usr/bin/env bash
# Build and package an experimental Aurora Legend wrapper plugin.
#
# The wrapper is a normal Chi plugin that forwards the pixel request to the
# Nezha algorithm it dlopens privately. It is inserted as LegendFilterInstance0
# in front of the stock filter node, so stock filters keep working.
#
# Usage: build_legend_wrapper.sh [OUT_DIR]
# Env:
#   NEZHA_ODM   - path to extracted Nezha odm (default: $ROOT/970/extracted_nezha/odm)
#   AURORA_ODM  - path to extracted Aurora odm (default: $ROOT/970/extracted_aurora/odm)
#   ANDROID_LIBS - path to android libs for linking (default: /tmp/opencode/android-libs)
#   CLANG       - clang binary (default: clang)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
NEZHA_ODM="${NEZHA_ODM:-$ROOT/970/extracted_nezha/odm}"
AURORA_ODM="${AURORA_ODM:-$ROOT/970/extracted_aurora/odm}"
OUT="${1:-${OUT:-$ROOT/out/hcm-legend}}"
SRC="$HERE/hcm_legend_plugin/hcm_legend.c"
CLANG="${CLANG:-clang}"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
[ -f "$SRC" ] || die "missing $SRC"
[ -d "$NEZHA_ODM" ] || die "missing Nezha odm: $NEZHA_ODM"
[ -d "$AURORA_ODM" ] || die "missing Aurora odm: $AURORA_ODM"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Compile the wrapper
"$CLANG" --target=aarch64-linux-android21 -fPIC -O2 -c "$SRC" -o "$TMP/hcm_legend.o"

LIBS="${ANDROID_LIBS:-/tmp/opencode/android-libs}"
LINK_LIBS=""
[ -d "$LIBS" ] && LINK_LIBS="-L$LIBS -Wl,--no-as-needed -lc -ldl -llog -l:libcom.xiaomi.pluginutils.so -l:libhcm_shim.so -l:com.xiaomi.plugin.mileicafilter.so -l:libcom.xiaomi.metadatautils.so"

"$CLANG" --target=aarch64-linux-android21 -fPIC -shared -nostdlib \
  -Wl,-soname,com.xiaomi.plugin.hcmlegend.so $LINK_LIBS \
  -o "$TMP/com.xiaomi.plugin.hcmlegend.so" "$TMP/hcm_legend.o"

# Verify the wrapper's DT_NEEDED entries
echo "=== Wrapper DT_NEEDED ==="
readelf -d "$TMP/com.xiaomi.plugin.hcmlegend.so" | grep NEEDED || true

rm -rf "$OUT"
mkdir -p "$OUT/system/odm/lib64/camera/plugins" "$OUT/system/odm/lib64/hcm" \
         "$OUT/system/odm/etc/camera/xiaomi"

# Build compat shim
"$CLANG" --target=aarch64-linux-android21 -fPIC -shared -nostdlib \
  -Wl,-soname,libhcm_shim.so -o "$TMP/libhcm_shim.so" "$HERE/compat_shim.S"
install -m 0644 "$TMP/libhcm_shim.so" "$OUT/system/odm/lib64/libhcm_shim.so"

# Install wrapper
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

# Verify libc++ patch
echo "=== Donor DT_NEEDED after libc++ patch ==="
readelf -d "$OUT/system/odm/lib64/com.xiaomi.plugin.mileicafilter.so" | grep NEEDED || true

# The donor's candyProcess enters the videoprocess API in libMiPhotoFilter,
# which changed between Nezha and Aurora. Ship Nezha's copy privately and
# repoint the donor (same-length SONAME).
install -m 0644 "$NEZHA_ODM/lib64/libMiPhotoFilter.so" "$OUT/system/odm/lib64/libHcmPhotoFiltr.so"
python3 "$HERE/patch_dynstr.py" libMiPhotoFilter.so libHcmPhotoFiltr.so \
  "$OUT/system/odm/lib64/libHcmPhotoFiltr.so" \
  "$OUT/system/odm/lib64/com.xiaomi.plugin.mileicafilter.so"

echo "=== Donor DT_NEEDED after libMiPhotoFilter patch ==="
readelf -d "$OUT/system/odm/lib64/com.xiaomi.plugin.mileicafilter.so" | grep NEEDED || true

# Parameter tables
for f in leica_filter_param.bin leica_filter_param_m3.bin \
         leica_filter_param_m9_preview.bin leica_filter_param_m9_snapshot.bin; do
  install -m 0644 "$NEZHA_ODM/etc/camera/$f" "$OUT/system/odm/etc/camera/$f"
done

# Companion libraries
for f in libcom.xiaomi.cam.miexifmgr.so libcameraopt_stub.so libmetatagwrapper.so; do
  [ -f "$NEZHA_ODM/lib64/$f" ] && install -m 0644 "$NEZHA_ODM/lib64/$f" "$OUT/system/odm/lib64/$f"
done

# Patch snapshot JSONs to insert LegendFilterInstance0
for snap in leicasnapshot.json satsnapshot.json normalsnapshot.json superhdsnapshot.json; do
  [ -f "$AURORA_ODM/etc/camera/xiaomi/$snap" ] || continue
  install -m 0644 "$AURORA_ODM/etc/camera/xiaomi/$snap" \
    "$OUT/system/odm/etc/camera/xiaomi/$snap"
  python3 "$HERE/patch_legend_snapshot.py" "$OUT/system/odm/etc/camera/xiaomi/$snap"
done

# sepolicy rules (magiskpolicy syntax, no colon)
cat > "$OUT/sepolicy.rule" <<'RULE'
allow hal_camera_default system_file file { open read getattr map execute }
allow hal_camera_default system_file dir { open read getattr search }
RULE

# LUT files for post-fs-data.sh staging
mkdir -p "$OUT/files"
install -m 0644 "$ROOT/assets/leica_luts/M3_Monopan.png" "$OUT/files/lut_m3.png"
install -m 0644 "$ROOT/assets/leica_luts/130_LeicaNatural.png" "$OUT/files/lut_m9.png"

cat > "$OUT/post-fs-data.sh" <<'SH'
#!/system/bin/sh
# Stage the two look files where the engine reads them and turn the gate on, so
# nothing has to be installed by hand after a reboot.
MODDIR=${0%/*}
DST=/data/vendor/camera
[ -d "$DST" ] || exit 0
for n in lut_m3 lut_m9; do
    cp -f "$MODDIR/files/$n.png" "$DST/$n.png"
    chown root:camera "$DST/$n.png"
    chmod 0644 "$DST/$n.png"
    restorecon "$DST/$n.png" 2>/dev/null
done
setprop camera.debug.mivi3.output.jpeg true
SH
chmod 0755 "$OUT/post-fs-data.sh"

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
