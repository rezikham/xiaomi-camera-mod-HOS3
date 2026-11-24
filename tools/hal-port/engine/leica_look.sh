#!/usr/bin/env bash
# Select one of the reproduced Leica looks and let the stock camera engine render it.
#
#   leica_look.sh m3        # Leica Monopan 50 (M3), monochrome
#   leica_look.sh m9        # Leica M9 / "Modern vintage" colour look
#   leica_look.sh m10r     # Leica M10R / "CMOS" warm vintage look
#   leica_look.sh off       # restore the stock slot and the gate property
#   leica_look.sh status
#
# How it works (see docs/hal-backport/09-engine-lut-injection.md):
#   * the LUT goes into /data/vendor/camera/lut_nc.png, the slot the stock Negative
#     filter loads through com.xiaomi.plugin.filter / FilterInstance0;
#   * camera.debug.mivi3.output.jpeg=true makes the camera hand the look to the
#     engine instead of rendering it in the app (the module can also answer this
#     flag itself, see the "HAL LUT slot" setting);
#   * the camera provider caches the decoded LUT per process, so the provider is
#     restarted after every change; the camera app is restarted so it re-reads the
#     flag.
#
# Then select the Negative filter (filterId 655591) in the stock camera. The
# preview keeps using the app's own bitmap; the saved JPEG is the HAL render.
#
# Requires: rooted device (KernelSU su), adb.
set -euo pipefail

SERIAL="1c4e4dec"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
SLOT_SCRIPT="$HERE/install_lut_slot.sh"
SLOT_DIR="/data/vendor/camera"
NAME="lut_nc"
PROP="camera.debug.mivi3.output.jpeg"
RESTART=1

usage() {
    sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

look_asset() {
    case "$1" in
        m3|look-m3) echo "$REPO/assets/leica_luts/M3_Monopan.png" ;;
        m9|look-m9) echo "$REPO/assets/leica_luts/130_LeicaNatural.png" ;;
        m10r|look-m10r) echo "$REPO/assets/leica_luts/M10R_Cmos.png" ;;
        *)   return 1 ;;
    esac
}

# The filter slot the look is bound to. The plain m3/m9 actions reuse the stock Negative
# filter's slot; look-m3/look-m9 use the dedicated names of the module's own Looks entries.
look_name() {
    case "$1" in
        look-m3) echo "lut_m3" ;;
        look-m9) echo "lut_m9" ;;
        look-m10r) echo "lut_m10r" ;;
        *)       echo "lut_nc" ;;
    esac
}

while [ $# -gt 0 ]; do
    case "$1" in
        m3|m9|m10r|look-m3|look-m9|look-m10r|off|status) ACTION="$1"; shift ;;
        --serial)   SERIAL="$2"; shift 2 ;;
        --no-restart) RESTART=0; shift ;;
        --no-gate)  NO_GATE=1; shift ;;
        -h|--help)  usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done

[ -n "${ACTION:-}" ] || usage

ADB=(adb -s "$SERIAL")
su_() { "${ADB[@]}" shell "su -c $(printf '%q' "$1")"; }

restart_camera() {
    if [ "$RESTART" = 0 ]; then
        echo "== restart skipped (--no-restart); the provider keeps the previous LUT"
        return
    fi
    su_ "setprop ctl.restart vendor.camera-provider"
    sleep 4
    "${ADB[@]}" shell am force-stop com.android.camera
    "${ADB[@]}" shell am start -n com.android.camera/.Camera >/dev/null 2>&1 || true
    sleep 2
}

status() {
    echo "== slot       : $(su_ "ls -laZ ${SLOT_DIR}/${NAME}.png" | tr -d '\r')"
    echo "== mounts     : $(su_ 'mount | grep -c "/data/vendor/camera/lut_" || true')"
    echo "== ${PROP} : $(su_ "getprop ${PROP}" | tr -d '\r')"
    echo "== provider   : pid $(su_ 'pidof vendor.qti.camera.provider-service_64' | tr -d '\r')" \
         "svc $(su_ 'getprop init.svc.vendor.camera-provider' | tr -d '\r')"
}

case "$ACTION" in
    m3|m9|m10r|look-m3|look-m9|look-m10r)
        LUT="$(look_asset "$ACTION")"
        NAME="$(look_name "$ACTION")"
        [ -f "$LUT" ] || { echo "LUT not found: $LUT" >&2; exit 1; }
        echo "== look       : $ACTION ($(basename "$LUT")) -> ${NAME}.png"
        if [ "${NO_GATE:-0}" = 1 ]; then
            "$SLOT_SCRIPT" --serial "$SERIAL" --lut "$LUT" --name "$NAME" --no-prop | sed -n '1,4p'
            echo "== gate       : left to the module's 'HAL LUT slot' setting"
        else
            "$SLOT_SCRIPT" --serial "$SERIAL" --lut "$LUT" --name "$NAME" | sed -n '1,4p'
        fi
        restart_camera
        ;;
    off)
        "$SLOT_SCRIPT" --serial "$SERIAL" --restore
        ;;
    status)
        ;;
esac

status
