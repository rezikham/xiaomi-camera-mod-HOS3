#!/system/bin/sh
# HyperCameraMod service.sh
# Runs in late_start service mode (non-blocking, recommended for most scripts)
# 1. Installs APK as user app (for LSPosed hooking)
# 2. Late-mount support: stages LUTs and enables gate if module loaded after boot

# Use MODDIR=${0%/*} to get module path (per KernelSU guide)
MODDIR=${0%/*}
PACKAGE="io.rezikham.hypercameramod"

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

log -t HyperCameraMod "Late-mount: M3/M9 LUTs staged, engine gate enabled, APK ready"