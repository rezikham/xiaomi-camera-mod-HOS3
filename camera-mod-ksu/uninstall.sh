#!/system/bin/sh
# HyperCameraMod uninstall.sh
# Runs when KernelSU removes this module

MODDIR=${0%/*}
PACKAGE="io.rezikham.hypercameramod"

log -t HyperCameraMod "Uninstalling module..."

# Unmount bind mounts
TARGET_PLUGIN_DIR="/odm/lib64/camera/plugins"
TARGET_ANCHUMAN="/odm/lib64/libAncHumanPreviewBokeh.so"
TARGET_MIALGO="/odm/lib64/libmialgoengine.so"

log -t HyperCameraMod "Unmounting plugin directory..."
umount "$TARGET_PLUGIN_DIR" 2>/dev/null

log -t HyperCameraMod "Unmounting libAncHumanPreviewBokeh.so..."
umount "$TARGET_ANCHUMAN" 2>/dev/null

log -t HyperCameraMod "Unmounting libmialgoengine.so..."
umount "$TARGET_MIALGO" 2>/dev/null

# Uninstall the APK if present
if pm list packages | grep -q "^package:$PACKAGE$"; then
    log -t HyperCameraMod "Uninstalling HyperCameraMod APK..."
    pm uninstall "$PACKAGE" >/dev/null 2>&1
    if [ $? -eq 0 ]; then
        log -t HyperCameraMod "APK uninstalled successfully"
    else
        log -t HyperCameraMod "WARNING: APK uninstall failed"
    fi
else
    log -t HyperCameraMod "APK not installed, skipping uninstall"
fi

# Remove staged LUT files
rm -f /data/vendor/camera/lut_m3.png
rm -f /data/vendor/camera/lut_m9.png

# Reset HAL engine gate property
resetprop --delete camera.debug.mivi3.output.jpeg 2>/dev/null

log -t HyperCameraMod "Module uninstalled completely"
exit 0