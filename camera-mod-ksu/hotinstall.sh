#!/system/bin/sh
# HyperCameraMod hotinstall.sh
# Runs during KernelSU hot install (module update without reboot)
# Handles APK update by reinstalling if version changed

MODDIR=${0%/*}
PACKAGE="io.rezikham.hypercameramod"
APK_PATH="$MODDIR/HyperCameraMod.apk"

log -t HyperCameraMod "Hot install: checking for APK update..."

if [ ! -f "$APK_PATH" ]; then
    log -t HyperCameraMod "Hot install: APK not found, skipping"
    exit 0
fi

# Check if package is installed
if ! pm list packages | grep -q "^package:$PACKAGE$"; then
    log -t HyperCameraMod "Hot install: APK not installed yet, will install on boot"
    exit 0
fi

# Get current installed version code
INSTALLED_VERSION=$(dumpsys package $PACKAGE 2>/dev/null | grep versionCode | head -1 | sed 's/.*versionCode=\([0-9]*\).*/\1/')

# Get new APK version code from module.prop (versionCode field)
NEW_VERSION=$(grep "^versionCode=" "$MODDIR/module.prop" 2>/dev/null | cut -d'=' -f2)

if [ -n "$INSTALLED_VERSION" ] && [ -n "$NEW_VERSION" ]; then
    if [ "$INSTALLED_VERSION" -lt "$NEW_VERSION" ]; then
        log -t HyperCameraMod "Hot install: updating APK from version $INSTALLED_VERSION to $NEW_VERSION"
        pm install -r -g "$APK_PATH" >/dev/null 2>&1
        if [ $? -eq 0 ]; then
            log -t HyperCameraMod "Hot install: APK updated successfully to version $NEW_VERSION"
        else
            log -t HyperCameraMod "Hot install: APK update failed"
        fi
    else
        log -t HyperCameraMod "Hot install: APK already at version $INSTALLED_VERSION (new: $NEW_VERSION), no update needed"
    fi
else
    # Fallback: just reinstall
    log -t HyperCameraMod "Hot install: version check failed, reinstalling APK"
    pm install -r -g "$APK_PATH" >/dev/null 2>&1
    if [ $? -eq 0 ]; then
        log -t HyperCameraMod "Hot install: APK reinstalled"
    fi
fi

# Also re-stage LUTs in case they changed
MODDIR=${0%/*}
if [ -f "$MODDIR/data/vendor/camera/lut_m3.png" ]; then
    cp -f "$MODDIR/data/vendor/camera/lut_m3.png" /data/vendor/camera/lut_m3.png
    chmod 0644 /data/vendor/camera/lut_m3.png 2>/dev/null
    chcon u:object_r:vendor_camera_data_file:s0 /data/vendor/camera/lut_m3.png 2>/dev/null
fi
if [ -f "$MODDIR/data/vendor/camera/lut_m9.png" ]; then
    cp -f "$MODDIR/data/vendor/camera/lut_m9.png" /data/vendor/camera/lut_m9.png
    chmod 0644 /data/vendor/camera/lut_m9.png 2>/dev/null
    chcon u:object_r:vendor_camera_data_file:s0 /data/vendor/camera/lut_m9.png 2>/dev/null
fi

# Reset HAL gate property
resetprop -n camera.debug.mivi3.output.jpeg true

log -t HyperCameraMod "Hot install complete"
exit 0