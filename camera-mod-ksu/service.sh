#!/system/bin/sh
# HyperCameraMod service.sh
# Late-mount support: stages LUTs and enables gate if module loaded after boot.

MODDIR=/data/adb/modules/hypercameramod

# Wait for vendor partition to be ready
while [ ! -d /data/vendor ]; do
    sleep 1
done

# Ensure /data/vendor/camera/ exists
mkdir -p /data/vendor/camera

# Copy LUT files from module overlay
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

# Enable HAL engine gate
setprop camera.debug.mivi3.output.jpeg true

log -t HyperCameraMod "Late-mount: M3/M9 LUTs staged, engine gate enabled"