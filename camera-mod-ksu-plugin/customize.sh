#!/system/bin/sh
# HyperCameraMod Madrid plugin port -- customize.sh
# Sourced by the KernelSU installer after extraction.

ui_print "HyperCameraMod installer: Madrid plugin port (experimental)"
ui_print ""

MODPATH_PLUGINS=1
for plugin in com.xiaomi.plugin.jpegrAggr.so com.xiaomi.plugin.heifedit.so; do
    if [ ! -f "$MODPATH/data/plugins/$plugin" ]; then
        ui_print "WARNING: $plugin missing from module ZIP"
        MODPATH_PLUGINS=0
    fi
done

if [ "$MODPATH_PLUGINS" -eq 1 ]; then
    ui_print "Both Madrid plugins present."
else
    ui_print "Some plugins are missing - module will not activate."
fi

ui_print "This module needs a KernelSU metamodule with /odm overlay support."
ui_print "Without it the private libhcm_* namespace is not visible and the"
ui_print "script intentionally keeps the stock Aurora plugins."
ui_print ""
ui_print "This is a separate module. It does not modify the LUT module or APK."
ui_print ""
