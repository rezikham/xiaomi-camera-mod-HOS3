#!/system/bin/sh
# HyperCameraMod action.sh -- LUT stage
# Shows the state of all staged LUTs when the Action button is pressed.

echo ""
echo "============================================"
echo "  HyperCameraMod - LUT stage"
echo "============================================"
echo ""

LUTS="lut_m3.png lut_m9.png
lut_600f.png lut_bf70.png lut_r600.png lut_p100f.png
lut_f50.png lut_kc64.png lut_v250.png lut_h400.png
lut_kp160.png lut_fc400.png lut_c50d.png lut_kg200.png"

for lut in $LUTS; do
    dst="/data/vendor/camera/$lut"
    if [ -f "$dst" ]; then
        echo "OK   $lut ($(wc -c < "$dst" | tr -d ' ') bytes)"
    else
        echo "MISS $lut"
    fi
done

echo ""
echo "HAL gate camera.debug.mivi3.output.jpeg = $(getprop camera.debug.mivi3.output.jpeg)"
echo ""
echo "This build stages LUTs only. No libraries are mounted."
echo "============================================"
echo ""

log -t HyperCameraMod "Action button: LUT status shown"
exit 0
