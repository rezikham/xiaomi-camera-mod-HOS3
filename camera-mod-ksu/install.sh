#!/system/bin/sh
# HyperCameraMod module install script
# Runs when module is installed/updated via KernelSU app
# Prepares APK for user app installation (actual install deferred to post-fs-data)

# During module install, KernelSU extracts to modules_update, not modules
# KSU_MOUNT_PATH is set by KernelSU to the actual mount path
MODDIR="${KSU_MOUNT_PATH:-/data/adb/modules_update/hypercameramod}"
APK_PATH="$MODDIR/HyperCameraMod.apk"
PACKAGE="io.rezikham.hypercameramod"

log -t HyperCameraMod "Module install: preparing APK at $APK_PATH"

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    log -t HyperCameraMod "ERROR: APK not found at $APK_PATH"
    exit 1
fi

# Copy APK to a persistent location for post-fs-data to install
# The module directory will be moved to /data/adb/modules/hypercameramod after install
mkdir -p /data/adb/modules/hypercameramod
cp -f "$APK_PATH" /data/adb/modules/hypercameramod/HyperCameraMod.apk

# Print user-facing message (visible in KernelSU app install log)
echo ""
echo "============================================"
echo "  HyperCameraMod module installed!"
echo "============================================"
echo ""
echo "The APK will be installed as a user app on"
echo "next reboot (via post-fs-data.sh)."
echo ""
echo "IMPORTANT: After reboot, you MUST grant"
echo "Superuser (root) permission to the"
echo "HyperCameraMod app in KernelSU / Magisk"
echo "for the LUT self-staging feature to work."
echo ""
echo "Steps after reboot:"
echo "  1. Open KernelSU app (or your root manager)"
echo "  2. Go to App List / Superuser settings"
echo "  3. Find 'HyperCameraMod' (io.rezikham.hypercameramod)"
echo "  4. Grant root / Allow superuser access"
echo ""
echo "Then enable the module in LSPosed/Vector,"
echo "scope 'com.android.camera', force-stop Camera."
echo "============================================"
echo ""

log -t HyperCameraMod "Module install complete, APK staged for post-fs-data install"
exit 0