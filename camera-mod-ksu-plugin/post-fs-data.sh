#!/system/bin/sh
# HyperCameraMod Madrid plugin port -- post-fs-data.sh
# The private libhcm_* libraries are supplied by the /odm and/or /vendor
# overlays. The two plugins stay in data/plugins and are bind-mounted only
# when the module is enabled and every private library is visible.
# The stock Aurora plugins remain the default failure state.

MODDIR=${0%/*}
STOCK=/odm/lib64/camera/plugins
ODM=/odm/lib64
VENDOR=/vendor/lib64

log -t HyperCameraModPlugin "post-fs-data: checking private library overlay"

if [ ! -f "$MODDIR/enable" ]; then
    log -t HyperCameraModPlugin "post-fs-data: enable marker absent, stock plugins kept"
    exit 0
fi

LIBS="libhcm_pluginutils.so libhcm_metadatautils.so libhcm_debug.so
libhcm_camdfx.so libhcm_camlog.so libhcm_metatagwrapper.so
libhcm_miexifmgr.so libhcm_oisexif.so libhcm_configmanager.so
libhcm_threadpool.so libhcm_abnormal.so libhcm_catchlog.so
libhcm_cameraopt.so libhcm_opbeanmsg.so libhcm_legendmsg.so
libhcm_protobuf_full.so libhcm_protobuf_lite.so libhcm_image_io.so
libhcm_ultrahdr.so libhcm_cameraheif.so libhcm_exif.so
libhcm_globalpool.so libhcm_jpegencoder.so libhcm_jpegdecoder.so
libhcm_jpeg.so libhcm_cxx_shared.so libhcm_crypto.so libhcm_ssl.so
libhcm_lz4.so"

# The metamodule may finish mounting slightly after this script starts.
i=0
while [ "$i" -lt 20 ]; do
    missing=""
    for lib in $LIBS; do
        if [ ! -f "$ODM/$lib" ] && [ ! -f "$VENDOR/$lib" ]; then
            missing="$missing $lib"
            break
        fi
    done
    if [ -z "$missing" ]; then
        break
    fi
    sleep 0.5
    i=$((i + 1))
done

if [ -n "$missing" ]; then
    log -t HyperCameraModPlugin "post-fs-data: private overlay inactive, stock plugins kept:$missing"
    exit 0
fi

for lib in $LIBS; do
    if [ -f "$ODM/$lib" ]; then
        chmod 0644 "$ODM/$lib" 2>/dev/null
        chcon u:object_r:odm_file:s0 "$ODM/$lib" 2>/dev/null
    fi
    if [ -f "$VENDOR/$lib" ]; then
        chmod 0644 "$VENDOR/$lib" 2>/dev/null
        chcon u:object_r:vendor_file:s0 "$VENDOR/$lib" 2>/dev/null
    fi
done

for cfg in /odm/etc/camera/xiaomi/MiExif.json /odm/etc/camera/xiaomi/videoexif.jsonc; do
    if [ -f "$cfg" ]; then
        chmod 0644 "$cfg" 2>/dev/null
        chcon u:object_r:odm_file:s0 "$cfg" 2>/dev/null
    fi
done

for plugin in com.xiaomi.plugin.jpegrAggr.so com.xiaomi.plugin.heifedit.so; do
    src="$MODDIR/data/plugins/$plugin"
    dst="$STOCK/$plugin"
    if [ ! -f "$src" ] || [ ! -f "$dst" ]; then
        log -t HyperCameraModPlugin "post-fs-data: $plugin unavailable, skipping"
        continue
    fi
    if mount -o bind "$src" "$dst"; then
        chcon u:object_r:odm_file:s0 "$dst" 2>/dev/null
        log -t HyperCameraModPlugin "post-fs-data: mounted $plugin (md5 $(md5sum "$src" | cut -d' ' -f1))"
    else
        log -t HyperCameraModPlugin "post-fs-data: bind mount failed for $plugin"
    fi
done

log -t HyperCameraModPlugin "post-fs-data: plugin port active"
