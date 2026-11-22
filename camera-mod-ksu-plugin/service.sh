#!/system/bin/sh
# HyperCameraMod Madrid plugin port -- service.sh
# Late diagnostic only. The plugins are supplied by the /odm overlay.

MODDIR=${0%/*}

sleep 5

for plugin in com.xiaomi.plugin.jpegrAggr.so com.xiaomi.plugin.heifedit.so; do
    dst="/odm/lib64/camera/plugins/$plugin"
    if [ -f "$dst" ]; then
        log -t HyperCameraModPlugin "service: $plugin md5 $(md5sum "$dst" | cut -d' ' -f1)"
    else
        log -t HyperCameraModPlugin "service: $plugin missing"
    fi
done

if [ -f /odm/lib64/libhcm_pluginutils.so ] || [ -f /vendor/lib64/libhcm_pluginutils.so ]; then
    log -t HyperCameraModPlugin "service: private library namespace visible"
else
    log -t HyperCameraModPlugin "service: private library namespace NOT visible (metamodule?)"
fi
