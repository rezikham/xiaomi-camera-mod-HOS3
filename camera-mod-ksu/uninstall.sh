#!/system/bin/sh
# HyperCameraMod uninstall.sh -- LUT stage
# Removes only what this module staged: the LUT files and the engine gate.
# Does not touch the APK (install/uninstall it manually) and unmounts nothing.

log -t HyperCameraMod "Uninstalling LUT module..."

LUTS="lut_m3.png lut_m9.png
lut_600f.png lut_bf70.png lut_r600.png lut_p100f.png
lut_f50.png lut_kc64.png lut_v250.png lut_h400.png
lut_kp160.png lut_fc400.png lut_c50d.png lut_kg200.png"

for lut in $LUTS; do
    rm -f "/data/vendor/camera/$lut"
done

resetprop --delete camera.debug.mivi3.output.jpeg 2>/dev/null

log -t HyperCameraMod "LUT module uninstalled"
exit 0
