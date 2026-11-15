#!/usr/bin/env bash
# dump-hal.sh - collect the Xiaomi Camera HAL from connected devices.
#
# usage: dump-hal.sh [-s SERIAL] [-o DIR] [--full-config] [--skip-tuning]
#
#   -s SERIAL       dump only this adb serial (default: every connected device)
#   -o DIR          output root (default: ./hal-dump)
#   --full-config   also pull /odm/etc/camera (models and tuning, several GB)
#   --skip-tuning   skip /vendor/lib64/camera and the odm plugin directories
#
# For each device the script writes hal-dump/<codename>-<model>-<os>/ with:
#
#   DEVICE.txt                 model, OS, Android/API, fingerprint, camera, dump time
#   props.txt                  getprop
#   dumpsys-media-camera.txt   camera provider / device state
#   camera-package.txt         dumpsys package com.android.camera
#   root/vendor/..., root/odm/...   the staged HAL files (tar, relative paths)
#   apk/                       the installed Camera APK and its splits
#   MANIFEST.txt               sizes and sha256 of everything collected
#
# Root is used when available (su -c) so that root-only vendor/odm files can be
# staged; without root the script still tries a direct read as the shell user.
set -uo pipefail

OUT_ROOT=./hal-dump
SERIAL=""
FULL=0
TUNING=1

while [ $# -gt 0 ]; do
  case "$1" in
    -s) SERIAL="${2:?-s needs a serial}"; shift 2 ;;
    -o) OUT_ROOT="${2:?-o needs a directory}"; shift 2 ;;
    --full-config) FULL=1; shift ;;
    --skip-tuning) TUNING=0; shift ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -n "$SERIAL" ]; then
  SERIALS=("$SERIAL")
else
  mapfile -t SERIALS < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [ "${#SERIALS[@]}" -eq 0 ]; then
    echo "no adb device in 'device' state" >&2
    exit 1
  fi
fi

mkdir -p "$OUT_ROOT"

for S in "${SERIALS[@]}"; do
  dev() { adb -s "$S" shell "$@" 2>/dev/null | tr -d '\r'; }

  MODEL=$(dev getprop ro.product.model)
  CODENAME=$(dev getprop ro.product.device)
  MARKET=$(dev getprop ro.product.marketname)
  OS=$(dev getprop ro.build.version.incremental)
  ANDROID=$(dev getprop ro.build.version.release)
  SDK=$(dev getprop ro.build.version.sdk)
  FINGERPRINT=$(dev getprop ro.build.fingerprint)
  if [ -z "$CODENAME" ]; then
    echo "== $S: not reachable, skipped"
    continue
  fi
  SAFE_OS=$(printf '%s' "${OS:-unknown}" | sed 's/[^A-Za-z0-9._-]/_/g')
  OUT="$OUT_ROOT/${CODENAME}-${MODEL:-unknown}-${SAFE_OS}"
  mkdir -p "$OUT/apk" "$OUT/root"

  ROOT=""
  ROOT_TXT=no
  if dev 'su -c id' | grep -q 'uid=0'; then
    ROOT=1
    ROOT_TXT=yes
  fi

  echo "== $S: ${CODENAME} (${MODEL:-?}), root=$ROOT_TXT"

  LIST=$(mktemp)
  {
    dev 'find /vendor/lib64 /vendor/lib /odm/lib64 /odm/lib /vendor/bin /odm/bin -maxdepth 4 \( -name "camera*.so" -o -name "com.qti.chi*.so" -o -name "libmialgo*" -o -name "libAnc*.so" -o -name "libmiSAT*.so" -o -name "libportrait*.so" -o -name "libmiphone*.so" -o -name "vendor.qti.camera*" -o -name "android.hardware.camera.provider*" -o -name "vendor.xiaomi.hardware.dynamiccameraserver" \) 2>/dev/null'
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
  } | grep -v '^$' | sed 's|^/||' | sort -u >"$LIST"

  REMOTE_LIST=/data/local/tmp/hal-dump-list.txt
  REMOTE_TAR=/data/local/tmp/hal-dump.tar
  adb -s "$S" shell "cat > $REMOTE_LIST" <"$LIST"

  if [ -n "$ROOT" ]; then
    adb -s "$S" shell "su -c 'tar -C / -cf $REMOTE_TAR -T $REMOTE_LIST; chmod 644 $REMOTE_TAR'" >/dev/null 2>&1
  else
    adb -s "$S" shell "tar -C / -cf $REMOTE_TAR -T $REMOTE_LIST; chmod 644 $REMOTE_TAR" >/dev/null 2>&1
  fi

  if adb -s "$S" pull "$REMOTE_TAR" "$OUT/hal.tar" >/dev/null 2>&1; then
    tar -xf "$OUT/hal.tar" -C "$OUT/root" 2>/dev/null
    rm -f "$OUT/hal.tar"
  else
    echo "   warning: could not stage the HAL files"
  fi
  if [ -n "$ROOT" ]; then
    adb -s "$S" shell "su -c 'rm -f $REMOTE_TAR $REMOTE_LIST'" >/dev/null 2>&1
  else
    adb -s "$S" shell "rm -f $REMOTE_TAR $REMOTE_LIST" >/dev/null 2>&1
  fi
  rm -f "$LIST"

  dev getprop >"$OUT/props.txt"
  dev 'dumpsys media.camera' >"$OUT/dumpsys-media-camera.txt"
  dev 'dumpsys package com.android.camera' >"$OUT/camera-package.txt"

  {
    echo "model:       ${MODEL:-?}"
    echo "market:      ${MARKET:-?}"
    echo "device:      ${CODENAME}"
    echo "os:          ${OS:-?}"
    echo "android:     ${ANDROID:-?} (API ${SDK:-?})"
    echo "fingerprint: ${FINGERPRINT:-?}"
    echo "camera:      $(dev 'dumpsys package com.android.camera' | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2)"
    echo "root:        $ROOT_TXT"
    echo "serial:      $S"
    echo "dumped:      $(date -Is)"
  } >"$OUT/DEVICE.txt"

  for p in $(dev 'pm path com.android.camera' | sed -n 's/^package://p'); do
    adb -s "$S" pull "$p" "$OUT/apk/$(basename "$p")" >/dev/null 2>&1 || true
  done

  {
    echo "device:      ${CODENAME} (${MODEL:-?})"
    echo "market:      ${MARKET:-?}"
    echo "serial:      $S"
    echo "os:          ${OS:-?}"
    echo "android:     ${ANDROID:-?} (API ${SDK:-?})"
    echo "fingerprint: ${FINGERPRINT:-?}"
    echo "camera:      $(dev 'dumpsys package com.android.camera' | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2)"
    echo "root:        $ROOT_TXT"
    echo
    ( cd "$OUT" && find . -type f ! -name MANIFEST.txt -printf '%12s  %p\n' | sort -k2 )
    echo
    ( cd "$OUT" && find . -type f ! -name MANIFEST.txt -exec sha256sum {} + 2>/dev/null | sort -k2 )
  } >"$OUT/MANIFEST.txt"

  echo "   -> $OUT ($(du -sh "$OUT" 2>/dev/null | cut -f1))"
done

echo "done: $OUT_ROOT"
