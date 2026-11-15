#!/system/bin/sh
# hcm-hal.sh - on-device Xiaomi Camera HAL dump (no computer needed).
#
# Requires root (su) or a Shizuku shell such as aShell You. Without either, the
# root-only vendor/odm files and the dumpsys sections are missing or partial.
#
# Run on the phone (Termux or any root shell):
#   su -c "sh /sdcard/Download/hcm-hal.sh"
# or, inside a root shell:
#   sh /sdcard/Download/hcm-hal.sh
#
# Output: /sdcard/Download/hal-<codename>-<model>-<os>-<timestamp>.tar
#         (the OS build is part of the name so dumps can be archived by version)
#
# Flags:
#   --full-config   also include /odm/etc/camera (models/tuning, several GB)
#   --skip-tuning   skip /vendor/lib64/camera and the odm plugin directories
#   -o DIR          output directory (default /sdcard/Download)
#
# The tar contains the camera HAL libraries and provider binaries,
# odm camera plugins/dynamicplugins, vendor tuning, camera configs, the Camera
# APK and splits, and meta/ (DEVICE.txt, props.txt, dumpsys output).

FULL=0
TUNING=1
OUTDIR=/sdcard/Download

while [ $# -gt 0 ]; do
  case "$1" in
    --full-config) FULL=1; shift ;;
    --skip-tuning) TUNING=0; shift ;;
    -o) OUTDIR="${2:?-o needs a directory}"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ "$(id -u)" != "0" ]; then
  if command -v su >/dev/null 2>&1; then
    SELF=$(readlink -f "$0" 2>/dev/null || echo "$0")
    exec su -c "sh '$SELF' $*"
  fi
  echo "warning: not root; root-only vendor/odm files may be missing" >&2
fi

MODEL=$(getprop ro.product.model)
CODENAME=$(getprop ro.product.device)
MARKET=$(getprop ro.product.marketname)
OS=$(getprop ro.build.version.incremental)
ANDROID=$(getprop ro.build.version.release)
SDK=$(getprop ro.build.version.sdk)
FINGERPRINT=$(getprop ro.build.fingerprint)
[ -n "$CODENAME" ] || CODENAME=unknown
SAFE_OS=$(printf '%s' "${OS:-unknown}" | sed 's/[^A-Za-z0-9._-]/_/g')
TS=$(date +%Y%m%d-%H%M%S)

if [ "$(id -u)" = "0" ]; then
  WORK=/data/local/tmp/hcm-hal-$$
else
  WORK="${TMPDIR:-${HOME:-/data/local/tmp}}/hcm-hal-$$"
fi
mkdir -p "$WORK/meta" || exit 1
trap 'rm -rf "$WORK"' EXIT

{
  echo "model:       ${MODEL:-?}"
  echo "market:      ${MARKET:-?}"
  echo "device:      ${CODENAME}"
  echo "os:          ${OS:-?}"
  echo "android:     ${ANDROID:-?} (API ${SDK:-?})"
  echo "fingerprint: ${FINGERPRINT:-?}"
  echo "camera:      $(dumpsys package com.android.camera 2>/dev/null | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2)"
  echo "uid:         $(id -u)"
  echo "dumped:      $(date)"
} >"$WORK/meta/DEVICE.txt"
getprop >"$WORK/meta/props.txt" 2>/dev/null
dumpsys media.camera >"$WORK/meta/dumpsys-media-camera.txt" 2>/dev/null
dumpsys package com.android.camera >"$WORK/meta/camera-package.txt" 2>/dev/null

LIST="$WORK/list.txt"
{
  find /vendor/lib64 /vendor/lib /odm/lib64 /odm/lib /vendor/bin /odm/bin -maxdepth 4 \( -name "camera*.so" -o -name "com.qti.chi*.so" -o -name "libmialgo*" -o -name "libAnc*.so" -o -name "libmiSAT*.so" -o -name "libportrait*.so" -o -name "libmiphone*.so" -o -name "vendor.qti.camera*" -o -name "android.hardware.camera.provider*" -o -name "vendor.xiaomi.hardware.dynamiccameraserver" \) 2>/dev/null
  echo /vendor/etc/camera
  echo /odm/etc/camera/xiaomi
  if [ "$TUNING" = 1 ]; then
    echo /vendor/lib64/camera
    echo /odm/lib64/camera/plugins
    echo /odm/lib64/camera/dynamicplugins
  fi
  if [ "$FULL" = 1 ]; then
    echo /odm/etc/camera
  fi
  echo "$WORK/meta"
  pm path com.android.camera 2>/dev/null | sed -n 's/^package://p'
} | grep -v '^$' | sed 's|^/||' | sort -u >"$LIST"

echo "dumping ${CODENAME} (${MODEL:-?}) ${OS:-?}"
TAR="$WORK/hal.tar"
if ! tar -C / -cf "$TAR" -T "$LIST" 2>"$WORK/tar.err"; then
  echo "warning: tar reported errors (see the first lines below)" >&2
  head -n 5 "$WORK/tar.err" >&2
fi

OUT="$OUTDIR/hal-${CODENAME}-${MODEL:-unknown}-${SAFE_OS}-${TS}.tar"
if cp "$TAR" "$OUT" 2>/dev/null; then
  echo "wrote $OUT ($(du -h "$OUT" 2>/dev/null | cut -f1))"
else
  trap - EXIT
  echo "could not write to $OUTDIR; kept $TAR"
fi
