#!/system/bin/sh
# HyperCameraMod post-fs-data
# Stages M3/M9 LUT files and enables the HAL engine gate.
# Runs early boot after module is mounted.

MODDIR=/data/adb/modules/hypercameramod

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

# Enable HAL engine gate so camera hands look to com.xiaomi.plugin.filter
setprop camera.debug.mivi3.output.jpeg true

log -t HyperCameraMod "M3/M9 LUTs staged, engine gate enabled"