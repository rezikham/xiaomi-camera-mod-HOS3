#!/usr/bin/env bash
# Install (or remove) a custom 512x512 LUT into one of the HAL filter slots on
# Aurora. See docs/hal-backport/09-engine-lut-injection.md.
#
#   install_lut_slot.sh --lut assets/leica_luts/M3_Monopan.png --name lut_nc
#   install_lut_slot.sh --restore
#
# The slot file is /data/vendor/camera/<name>.png, the file the Xiaomi filter
# plugin (com.xiaomi.plugin.filter / FilterInstance0) loads for the selected
# filter. It is bind-mounted from a root-owned, read-only copy so the camera
# app's MIVILutSaver cannot overwrite it.
#
# Requires: rooted device (KernelSU su), adb.
set -euo pipefail

SERIAL="1c4e4dec"
LUT=""
NAME=""
SET_PROP=1
RESTORE=0
SLOT_DIR="/data/vendor/camera"
PROP="camera.debug.mivi3.output.jpeg"

usage() {
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        --serial)  SERIAL="$2"; shift 2 ;;
        --lut)     LUT="$2"; shift 2 ;;
        --name)    NAME="$2"; shift 2 ;;
        --no-prop) SET_PROP=0; shift ;;
        --restore) RESTORE=1; shift ;;
        -h|--help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done

ADB=(adb -s "$SERIAL")
sh_() { "${ADB[@]}" shell "$@"; }
su_() { "${ADB[@]}" shell "su -c $(printf '%q' "$1")"; }

restore_all() {
    echo "== restoring"
    # Unmount every slot we may have created, newest first (mount order).
    su_ "for m in \$(mount | awk '/\/data\/vendor\/camera\/lut_/ {print \$3}' | tac); do umount \"\$m\"; done; true"
    su_ "rm -f ${SLOT_DIR}/.slot_*.png"
    # LUT copies the app wrote for the stock filters are cache; drop ours only.
    su_ "setprop ${PROP} ''"
    su_ "setprop ctl.restart vendor.camera-provider"
    sleep 6
    "${ADB[@]}" shell am force-stop com.android.camera
    "${ADB[@]}" shell am start -n com.android.camera/.Camera >/dev/null
    echo "== provider: $(su_ 'getprop init.svc.vendor.camera-provider')"
    echo "== remaining slot mounts: $(su_ 'mount | grep -c "/data/vendor/camera/lut_" || true')"
    echo "== ${PROP}: $(su_ "getprop ${PROP}")"
}

if [ "$RESTORE" = 1 ]; then
    restore_all
    exit 0
fi

[ -n "$LUT" ] && [ -n "$NAME" ] || usage
[ -f "$LUT" ] || { echo "LUT not found: $LUT" >&2; exit 1; }

LOCAL_MD5=$(md5sum "$LUT" | cut -d' ' -f1)
echo "== LUT      : $LUT (md5 $LOCAL_MD5)"
echo "== slot     : ${SLOT_DIR}/${NAME}.png"

if [ "$SET_PROP" = 1 ]; then
    su_ "setprop ${PROP} true"
    echo "== ${PROP} = $(su_ "getprop ${PROP}")"
    echo "   (restart com.android.camera once so the static flag is re-read)"
fi

# Stage inside the target directory so the file inherits vendor_camera_data_file.
REMOTE_STAGE="/data/local/tmp/slot_${NAME}.png"
"${ADB[@]}" push "$LUT" "$REMOTE_STAGE" >/dev/null

su_ "set -e
umount '${SLOT_DIR}/${NAME}.png' 2>/dev/null || true
cp '${REMOTE_STAGE}' '${SLOT_DIR}/.slot_${NAME}.png'
chown root:root '${SLOT_DIR}/.slot_${NAME}.png'
chmod 444 '${SLOT_DIR}/.slot_${NAME}.png'
restorecon '${SLOT_DIR}/.slot_${NAME}.png'
[ -e '${SLOT_DIR}/${NAME}.png' ] || : > '${SLOT_DIR}/${NAME}.png'
mount --bind '${SLOT_DIR}/.slot_${NAME}.png' '${SLOT_DIR}/${NAME}.png'
rm -f '${REMOTE_STAGE}'"

REMOTE_MD5=$(su_ "md5sum '${SLOT_DIR}/${NAME}.png'" | cut -d' ' -f1)
if [ "$REMOTE_MD5" != "$LOCAL_MD5" ]; then
    echo "ERROR: slot md5 $REMOTE_MD5 != $LOCAL_MD5" >&2
    exit 1
fi
echo "== installed: $(su_ "ls -laZ '${SLOT_DIR}/${NAME}.png'")"
echo
echo "Select the matching filter in the camera app and capture. Undo with:"
echo "  $0 --serial $SERIAL --restore"
