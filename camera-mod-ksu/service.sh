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


# setprop is safe in late_start service mode (skip on 13U ishtar / 14 Pro Ti shennong where it outputs raw 12MB buffers)
DEV=$(getprop ro.product.device)
if [ "$DEV" != "ishtar" ] && [ "$DEV" != "shennong" ]; then
    setprop camera.debug.mivi3.output.jpeg true
fi

log -t HyperCameraMod "service: LUT stage complete"

# Original Portrait beauty: use the 18 PM RearPortraitOriginal value (10)
# instead of the stock Master values (20 / 0).
BEAUTY_SRC="$MODDIR/data/odm/etc/camera/beauty_config_gl.json"
if [ -f "$BEAUTY_SRC" ]; then
    chcon u:object_r:vendor_configs_file:s0 "$BEAUTY_SRC" 2>/dev/null
    mount --bind "$BEAUTY_SRC" /odm/etc/camera/beauty_config_gl.json
    log -t HyperCameraMod "beauty config mounted (Original skin smoothing 10)"
fi

# Original Portrait per-style HAL bypass.
# The camera app writes 1 only while Original is selected; this root watcher
# flips the 14U capture mdbokeh bypass so Master/Leica keep their stock bokeh.
BYPASS_SIGNAL=/data/user/0/com.android.camera/files/hcm_bokeh_bypass
(
    last=""
    while true; do
        v=$(cat "$BYPASS_SIGNAL" 2>/dev/null)
        if [ "$v" != "$last" ] && { [ "$v" = "0" ] || [ "$v" = "1" ]; }; then
            setprop persist.vendor.camera.capturemdbokeh.bypass "$v"
            last="$v"
            log -t HyperCameraMod "capture bokeh bypass=$v"
        fi
        sleep 1
    done
) &
log -t HyperCameraMod "service: Original portrait bypass watcher started"
