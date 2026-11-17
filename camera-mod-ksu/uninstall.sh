#!/system/bin/sh
# HyperCameraMod uninstall.sh
# Runs when KernelSU removes this module

MODDIR=${0%/*}
PACKAGE="io.rezikham.hypercameramod"

log -t HyperCameraMod "Uninstalling module..."

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