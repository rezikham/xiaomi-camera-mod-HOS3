#!/system/bin/sh
# HyperCameraMod customize.sh -- LUT stage
# Sourced by the KernelSU installer after extraction.

ui_print "HyperCameraMod installer: LUT stage"
ui_print ""

MODPATH_HAS_LUTS=1
for lut in lut_m3.png lut_m9.png lut_m10r.png lut_600f.png lut_bf70.png lut_r600.png lut_p100f.png lut_f50.png lut_kc64.png lut_v250.png lut_h400.png lut_kp160.png lut_fc400.png lut_c50d.png lut_kg200.png; do
    if [ ! -f "$MODPATH/data/vendor/camera/$lut" ]; then
        ui_print "WARNING: $lut missing from module ZIP"
        MODPATH_HAS_LUTS=0
    fi
done

if [ "$MODPATH_HAS_LUTS" -eq 1 ]; then
    ui_print "All 15 LUTs present (M3/M9/M10R + 12 film filters)."
else
    ui_print "Some LUTs are missing - module may not work as expected."
fi

ui_print "This build stages LUTs only - no libraries, no mounts, no APK install."
ui_print ""

export MODULE_HOT_INSTALL_REQUEST=true
export MODULE_HOT_RUN_SCRIPT=hotinstall.sh

ui_print "HyperCameraMod installer done."
