#!/system/bin/sh
# HyperCameraMod Madrid plugin port -- action.sh
# KernelSU action button: toggle the enable marker without editing files.

MODDIR=${0%/*}

if [ -f "$MODDIR/enable" ]; then
    rm -f "$MODDIR/enable"
    echo "Madrid plugin port DISABLED. Reboot to restore stock Aurora plugins."
else
    touch "$MODDIR/enable"
    echo "Madrid plugin port ENABLED. Reboot to mount the Madrid plugins."
fi
