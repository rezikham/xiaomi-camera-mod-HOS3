#!/system/bin/sh
# HyperCameraMod service.sh -- LUT stage
# Late fallback in case post-fs-data ran before /data/vendor was ready.
# Stages all bundled LUTs and enables the engine gate.
# No libraries, no plugin mounts, no APK install.

MODDIR=${0%/*}

log -t HyperCameraMod "service: LUT stage starting"

while [ ! -d /data/vendor ]; do
    sleep 1
done

mkdir -p /data/vendor/camera

LUTS="lut_m3.png lut_m9.png
lut_600f.png lut_bf70.png lut_r600.png lut_p100f.png
lut_f50.png lut_kc64.png lut_v250.png lut_h400.png
lut_kp160.png lut_fc400.png lut_c50d.png lut_kg200.png"

for lut in $LUTS; do
    src="$MODDIR/data/vendor/camera/$lut"
    dst="/data/vendor/camera/$lut"
    if [ -f "$src" ]; then
        cp -f "$src" "$dst"
        chmod 0644 "$dst"
        chcon u:object_r:vendor_camera_data_file:s0 "$dst" 2>/dev/null
    fi
done


# setprop is safe in late_start service mode
setprop camera.debug.mivi3.output.jpeg true

log -t HyperCameraMod "service: LUT stage complete"
