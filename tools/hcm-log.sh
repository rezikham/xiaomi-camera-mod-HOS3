#!/system/bin/sh
# hcm-log.sh - on-device HyperCameraMod / Camera log collector.
#
# Requires root (su) or a Shizuku shell such as aShell You. Without either, the
# module preferences, logcat and dmesg sections are empty or incomplete.
#
# Run on the phone (Termux or any root shell):
#   su -c "sh /sdcard/Download/hcm-log.sh"
# or, inside a root shell:
#   sh /sdcard/Download/hcm-log.sh
#
# Output: /sdcard/Download/hcm-log-<timestamp>.txt
# Flags:  --no-restart   capture the current buffer instead of restarting Camera
#
# The file contains the device/OS/camera/module versions, the module's stored
# toggles, the resumed activity, the HyperCameraMod/HyperFilmFilters/Vector
# lines, the Camera process lines, the crash buffer, and recent AVC denials.

RESTART=1
for a in "$@"; do
  [ "$a" = "--no-restart" ] && RESTART=0
done

if [ "$(id -u)" != "0" ]; then
  if command -v su >/dev/null 2>&1; then
    SELF=$(readlink -f "$0" 2>/dev/null || echo "$0")
    exec su -c "sh '$SELF' $*"
  fi
  echo "warning: not root; module settings, logcat and dmesg may be empty" >&2
fi

OUT_DIR=/sdcard/Download
[ -d "$OUT_DIR" ] || OUT_DIR=/data/local/tmp
TS=$(date +%Y%m%d-%H%M%S)
OUT="$OUT_DIR/hcm-log-$TS.txt"

ver() { dumpsys package "$1" 2>/dev/null | grep -m1 -o "versionName=[^ ]*" | cut -d= -f2; }

{
  echo "==== HyperCameraMod camera diagnostics ===="
  echo "generated: $(date)"
  echo "model:     $(getprop ro.product.model) ($(getprop ro.product.marketname))"
  echo "device:    $(getprop ro.product.device)"
  echo "os:        $(getprop ro.build.version.incremental) (Android $(getprop ro.build.version.release))"
  echo "camera:    $(ver com.android.camera)"
  echo "module:    $(ver io.rezikham.hypercameramod)"
  echo "uid:       $(id -u)"
  echo

  echo "==== module settings (shared_prefs) ===="
  cat /data/data/io.rezikham.hypercameramod/shared_prefs/*.xml 2>/dev/null
  echo

  if [ "$RESTART" = 1 ]; then
    logcat -c 2>/dev/null
    am force-stop com.android.camera 2>/dev/null
    sleep 2
    monkey -p com.android.camera -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 15
  fi

  RESUMED=$(dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity)
  echo "==== resumed activity ===="
  echo "${RESUMED:-unknown}"
  case "$RESUMED" in
    *com.android.camera*) ;;
    *) echo "warning: Camera is not in the foreground (screen locked or start failed); the log may be short." ;;
  esac
  echo

  echo "==== HyperCameraMod / HyperFilmFilters / Vector ===="
  logcat -d -v threadtime 2>/dev/null \
    | grep -iE 'HyperCameraMod|HyperFilmFilters|VectorModuleManager|does not match|hooked |could not hook|filter list|filter component' \
    | tail -300
  echo

  echo "==== Camera process / crashes ===="
  logcat -d -v threadtime 2>/dev/null \
    | grep -iE 'com\.android\.camera|AndroidRuntime|FATAL EXCEPTION' \
    | tail -200
  echo

  echo "==== crash buffer ===="
  logcat -b crash -d -v threadtime 2>/dev/null | tail -80
  echo

  echo "==== recent AVC denials ===="
  dmesg 2>/dev/null | grep -i 'avc:' | tail -20
} >"$OUT" 2>&1

echo "wrote $OUT"
