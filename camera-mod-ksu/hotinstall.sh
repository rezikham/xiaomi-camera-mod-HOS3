#!/system/bin/sh
# HyperCameraMod hotinstall.sh -- LUT stage
# Re-stage all bundled LUTs when the module is hot-installed (no reboot).
# No APK install, no library mounts.

MODDIR=${0%/*}

log -t HyperCameraMod "hotinstall: LUT stage"

mkdir -p /data/vendor/camera

LUTS="lut_m3.png lut_m9.png lut_m10r.png
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

resetprop -n camera.debug.mivi3.output.jpeg true

log -t HyperCameraMod "hotinstall: LUTs staged"
exit 0
