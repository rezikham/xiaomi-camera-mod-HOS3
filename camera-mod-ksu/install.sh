#!/system/bin/sh
# HyperCameraMod module install script
# Runs when module is installed/updated via KernelSU app
# Installs the APK as a user app (not system) so LSPosed can hook it.

MODDIR=/data/adb/modules/hypercameramod
APK_PATH="$MODDIR/HyperCameraMod.apk"
PACKAGE="io.rezikham.hypercameramod"

log -t HyperCameraMod "Installing HyperCameraMod APK as user app..."

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    log -t HyperCameraMod "ERROR: APK not found at $APK_PATH"
    exit 1
fi

# Install/replace the APK as a user app
# Using pm install with -r (replace) and -g (grant runtime permissions)
pm install -r -g "$APK_PATH" >/dev/null 2>&1

if [ $? -eq 0 ]; then
    log -t HyperCameraMod "APK installed successfully as user app"
    
    # Print user-facing message (visible in KernelSU app install log)
    echo ""
    echo "============================================"
    echo "  HyperCameraMod APK installed!"
    echo "============================================"
    echo ""
    echo "IMPORTANT: You MUST grant Superuser (root)"
    echo "permission to the HyperCameraMod app in"
    echo "KernelSU / Magisk / APatch for the LUT"
    echo "self-staging feature to work on first launch."
    echo ""
    echo "Steps:"
    echo "  1. Open KernelSU app (or your root manager)"
    echo "  2. Go to App List / Superuser settings"
    echo "  3. Find 'HyperCameraMod' (io.rezikham.hypercameramod)"
    echo "  4. Grant root / Allow superuser access"
    echo ""
    echo "Then enable the module in LSPosed/Vector,"
    echo "scope 'com.android.camera', force-stop Camera."
    echo "============================================"
    echo ""
else
    log -t HyperCameraMod "ERROR: APK installation failed"
    echo "ERROR: Failed to install APK. Check logcat for details."
    exit 1
fi

exit 0