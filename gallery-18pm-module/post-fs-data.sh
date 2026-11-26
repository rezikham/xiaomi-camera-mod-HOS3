#!/system/bin/sh
MODDIR=${0%/*}
chcon u:object_r:system_lib_file:s0 $MODDIR/system/system_ext/lib64/*.so 2>/dev/null
