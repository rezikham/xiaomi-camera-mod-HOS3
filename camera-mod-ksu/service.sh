#!/system/bin/sh
# HyperCameraMod service.sh
# Runs in late_start service mode (non-blocking, recommended for most scripts)
# 1. Installs APK as user app (for LSPosed hooking)
# 2. Late-mount support: stages LUTs and enables gate if module loaded after boot
# 3. Bind mounts plugin libraries for CHi plugin manager

# Use MODDIR=${0%/*} to get module path (per KernelSU guide)
MODDIR=${0%/*}
PACKAGE="io.rezikham.hypercameramod"

# --- 0. Bind mount plugin libraries for CHi plugin manager ---
# CHi plugin manager scans /odm/lib64/camera/plugins/ and /vendor/lib64/camera/plugins/
# Module files are at $MODDIR/system/odm/lib64/camera/plugins/ and $MODDIR/system/odm/lib64/

MODULE_ODM_LIB64="$MODDIR/system/odm/lib64"
MODULE_PLUGIN_DIR="$MODULE_ODM_LIB64/camera/plugins"
TARGET_ODM_LIB64="/odm/lib64"
TARGET_PLUGIN_DIR="/odm/lib64/camera/plugins"

# Create target plugin directory if it doesn't exist
mkdir -p "$TARGET_PLUGIN_DIR"

# Bind mount plugin directory
if [ -d "$MODULE_PLUGIN_DIR" ]; then
    log -t HyperCameraMod "Bind mounting plugin directory: $MODULE_PLUGIN_DIR -> $TARGET_PLUGIN_DIR"
    mount --bind "$MODULE_PLUGIN_DIR" "$TARGET_PLUGIN_DIR" 2>/dev/null
    if [ $? -eq 0 ]; then
        log -t HyperCameraMod "Plugin directory bind mount successful"
    else
        log -t HyperCameraMod "WARNING: Plugin directory bind mount failed"
    fi
else
    log -t HyperCameraMod "WARNING: Module plugin directory not found at $MODULE_PLUGIN_DIR"
fi

# Bind mount libAncHumanPreviewBokeh.so
MODULE_ANCHUMAN="$MODULE_ODM_LIB64/libAncHumanPreviewBokeh.so"
TARGET_ANCHUMAN="$TARGET_ODM_LIB64/libAncHumanPreviewBokeh.so"
if [ -f "$MODULE_ANCHUMAN" ]; then
    log -t HyperCameraMod "Bind mounting libAncHumanPreviewBokeh.so"
    mount --bind "$MODULE_ANCHUMAN" "$TARGET_ANCHUMAN" 2>/dev/null
    if [ $? -eq 0 ]; then
        log -t HyperCameraMod "libAncHumanPreviewBokeh.so bind mount successful"
    else
        log -t HyperCameraMod "WARNING: libAncHumanPreviewBokeh.so bind mount failed"
    fi
else
    log -t HyperCameraMod "WARNING: libAncHumanPreviewBokeh.so not found at $MODULE_ANCHUMAN"
fi

# Bind mount libmialgoengine.so (for SAT pipeline)
MODULE_MIALGO="$MODULE_ODM_LIB64/libmialgoengine.so"
TARGET_MIALGO="$TARGET_ODM_LIB64/libmialgoengine.so"
if [ -f "$MODULE_MIALGO" ]; then
    log -t HyperCameraMod "Bind mounting libmialgoengine.so"
    mount --bind "$MODULE_MIALGO" "$TARGET_MIALGO" 2>/dev/null
    if [ $? -eq 0 ]; then
        log -t HyperCameraMod "libmialgoengine.so bind mount successful"
    else
        log -t HyperCameraMod "WARNING: libmialgoengine.so bind mount failed"
    fi
fi

# --- 1. Install APK as user app ---
APK_PATH="$MODDIR/HyperCameraMod.apk"
if [ -f "$APK_PATH" ]; then
    # Check if already installed
    if ! pm list packages | grep -q "^package:$PACKAGE$"; then
        log -t HyperCameraMod "Installing APK as user app..."
        pm install -r -g "$APK_PATH" >/dev/null 2>&1
        if [ $? -eq 0 ]; then
            log -t HyperCameraMod "APK installed successfully as user app"
        else
            log -t HyperCameraMod "WARNING: APK install failed, will retry on next boot"
        fi
    else
        log -t HyperCameraMod "APK already installed"
    fi
else
    log -t HyperCameraMod "WARNING: APK not found at $APK_PATH"
fi

# --- 2. Stage LUT files (late-mount fallback) ---
# Wait for vendor partition to be ready
while [ ! -d /data/vendor ]; do
    sleep 1
done

# Ensure /data/vendor/camera/ exists
mkdir -p /data/vendor/camera

# Copy LUT files from module directory
if [ -f "$MODDIR/data/vendor/camera/lut_m3.png" ]; then
    cp -f "$MODDIR/data/vendor/camera/lut_m3.png" /data/vendor/camera/lut_m3.png
fi
if [ -f "$MODDIR/data/vendor/camera/lut_m9.png" ]; then
    cp -f "$MODDIR/data/vendor/camera/lut_m9.png" /data/vendor/camera/lut_m9.png
fi

# Set permissions
chmod 0644 /data/vendor/camera/lut_m3.png 2>/dev/null
chmod 0644 /data/vendor/camera/lut_m9.png 2>/dev/null

# Set SELinux context so hal_camera_default can read them
chcon u:object_r:vendor_camera_data_file:s0 /data/vendor/camera/lut_m3.png 2>/dev/null
chcon u:object_r:vendor_camera_data_file:s0 /data/vendor/camera/lut_m9.png 2>/dev/null

# Enable HAL engine gate (OK to use setprop here - late_start service mode)
setprop camera.debug.mivi3.output.jpeg true

log -t HyperCameraMod "Late-mount: M3/M9 LUTs staged, engine gate enabled, APK ready, plugins bind-mounted"