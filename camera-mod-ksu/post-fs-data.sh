#!/system/bin/sh
# HyperCameraMod post-fs-data.sh -- LUT stage
# Stages the authentic 17U Leica M3/M9 LUTs and the Camera 5.3 film-filter LUTs
# into /data/vendor/camera, and enables the HAL engine gate.
# No libraries, no plugin mounts, no /odm or /vendor modifications, no APK install.

MODDIR=${0%/*}

log -t HyperCameraMod "post-fs-data: LUT stage starting"

mkdir -p /data/vendor/camera

# All LUTs bundled with the module: M3/M9 first, then the 12 film filters.
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
        log -t HyperCameraMod "post-fs-data: $lut staged ($(wc -c < "$src" | tr -d ' ') bytes)"
    else
        log -t HyperCameraMod "post-fs-data: WARNING $lut missing from module"
    fi
done


# KernelSU ignores the Magisk-style sepolicy.rule file on this build.
# Apply the preview read rule through ksud so platform_app can read the LUTs.
if [ -x /data/adb/ksud ]; then
    /data/adb/ksud sepolicy patch "allow platform_app vendor_data_file dir search" 2>/dev/null
    /data/adb/ksud sepolicy patch "allow platform_app vendor_camera_data_file dir { search read open getattr }" 2>/dev/null
    /data/adb/ksud sepolicy patch "allow platform_app vendor_camera_data_file file { read open getattr map }" 2>/dev/null
    log -t HyperCameraMod "post-fs-data: applied platform_app LUT read policy"
fi

# resetprop in post-fs-data (setprop can deadlock this early)
resetprop -n camera.debug.mivi3.output.jpeg true

log -t HyperCameraMod "post-fs-data: LUT stage complete (no libraries mounted)"
