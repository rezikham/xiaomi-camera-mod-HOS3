#!/usr/bin/env bash
# pull_camera_log.sh - collect HyperCameraMod / Xiaomi Camera diagnostics into one text file.
#
# usage: pull_camera_log.sh [-s SERIAL] [-o OUTFILE] [-w SECONDS] [--no-restart]
#
#   -s SERIAL      adb serial (default: the only connected device)
#   -o OUTFILE     output file (default: /home/rezikham/PoC/logs/<serial>-<device>-<ts>.txt)
#   -w SECONDS     wait after starting Camera before reading the log (default 15)
#   --no-restart   do not clear the log or restart Camera; capture the current buffer
#
# The file contains: device/OS/camera/module versions, the module's stored toggles,
# the resumed activity, the HyperCameraMod/HyperFilmFilters lines, the Camera process
# lines, the crash buffer, and any recent AVC denials. Enough to debug a missing
# feature without the phone in hand.
set -uo pipefail

SERIAL=""
OUT=""
WAIT=15
RESTART=1
LOG_DIR="${LOG_DIR:-$PWD/logs}"

while [ $# -gt 0 ]; do
  case "$1" in
    -s) SERIAL="${2:?-s needs a serial}"; shift 2 ;;
    -o) OUT="${2:?-o needs a path}"; shift 2 ;;
    -w) WAIT="${2:?-w needs seconds}"; shift 2 ;;
    --no-restart) RESTART=0; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$SERIAL" ]; then
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [ "${#DEVICES[@]}" -eq 0 ]; then
    echo "no adb device in 'device' state" >&2
    exit 1
  fi
  if [ "${#DEVICES[@]}" -gt 1 ]; then
    echo "multiple devices, pass -s: ${DEVICES[*]}" >&2
    exit 1
  fi
  SERIAL="${DEVICES[0]}"
fi

dev() { adb -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }

MODEL=$(dev getprop ro.product.model)
MARKET=$(dev getprop ro.product.marketname)
DEVICE=$(dev getprop ro.product.device)
OS=$(dev getprop ro.build.version.incremental)
ANDROID=$(dev getprop ro.build.version.release)
CAM=$(dev 'dumpsys package com.android.camera' | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2)
MOD=$(dev 'dumpsys package io.rezikham.hypercameramod' | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2)
ROOT=$(dev 'su -c id' | head -1)

TS=$(date +%Y%m%d-%H%M%S)
if [ -z "$OUT" ]; then
  SAFE_SERIAL=${SERIAL//[:.]/_}
  OUT="$LOG_DIR/${SAFE_SERIAL}-${DEVICE:-device}-${TS}.txt"
fi
mkdir -p "$(dirname "$OUT")"

{
  echo "==== HyperCameraMod camera diagnostics ===="
  echo "generated: $(date -Is)"
  echo "serial:    $SERIAL"
  echo "model:     ${MODEL:-?} (${MARKET:-?})"
  echo "device:    ${DEVICE:-?}"
  echo "os:        ${OS:-?} (Android ${ANDROID:-?})"
  echo "camera:    ${CAM:-unknown}"
  echo "module:    ${MOD:-not installed}"
  echo "root:      ${ROOT:-no}"
  echo

  echo "==== module settings (shared_prefs) ===="
  dev 'su -c "cat /data/data/io.rezikham.hypercameramod/shared_prefs/*.xml"'
  echo

  if [ "$RESTART" = 1 ]; then
    dev 'logcat -c' >/dev/null
    dev 'am force-stop com.android.camera' >/dev/null
    sleep 2
    dev 'monkey -p com.android.camera -c android.intent.category.LAUNCHER 1' >/dev/null
    sleep "$WAIT"
  fi

  RESUMED=$(dev 'dumpsys activity activities | grep -m1 mResumedActivity')
  echo "==== resumed activity ===="
  echo "${RESUMED:-unknown}"
  case "$RESUMED" in
    *com.android.camera*) ;;
    *) echo "warning: Camera is not in the foreground (screen locked or start failed); the log may be short." ;;
  esac
  echo

  echo "==== HyperCameraMod / HyperFilmFilters / Vector ===="
  dev 'logcat -d -v threadtime' \
    | grep -iE 'HyperCameraMod|HyperFilmFilters|VectorModuleManager|does not match|hooked |could not hook|filter list|filter component' \
    | tail -300
  echo

  echo "==== Camera process / crashes ===="
  dev 'logcat -d -v threadtime' \
    | grep -iE 'com\.android\.camera|AndroidRuntime|FATAL EXCEPTION' \
    | tail -200
  echo

  echo "==== crash buffer ===="
  dev 'logcat -b crash -d -v threadtime' | tail -80
  echo

  echo "==== recent AVC denials (if readable) ===="
  dev dmesg | grep -i 'avc:' | tail -20
} >"$OUT" 2>&1

echo "wrote $OUT ($(wc -l <"$OUT") lines)"
