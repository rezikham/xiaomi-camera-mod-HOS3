#!/system/bin/sh
# HyperCameraMod action.sh
# Runs when user clicks the Action button in KernelSU app

MODDIR=${0%/*}
PACKAGE="io.rezikham.hypercameramod"

# Print to KernelSU app UI (captured by installer)
echo ""
echo "============================================"
echo "  HyperCameraMod Actions"
echo "============================================"
echo ""
echo "Installed package: $PACKAGE"
echo ""

# Check if APK is installed
if pm list packages | grep -q "^package:$PACKAGE$"; then
    echo "✓ APK is installed as user app"
    # Check version
    VERSION=$(pm dump "$PACKAGE" 2>/dev/null | grep versionName | head -1 | sed 's/.*versionName=//')
    if [ -n "$VERSION" ]; then
        echo "  Version: $VERSION"
    fi
else
    echo "✗ APK NOT installed"
    echo "  Run post-fs-data.sh or reboot to install"
fi

# Check root permission
ROOT_STATUS="Unknown"
if [ -f /data/adb/ksu/bin/ksud ]; then
    ROOT_STATUS="KernelSU"
elif [ -f /data/adb/magisk/magisk ]; then
    ROOT_STATUS="Magisk"
elif [ -f /data/adb/ap/bin/apd ]; then
    ROOT_STATUS="APatch"
fi
echo ""
echo "Root manager: $ROOT_STATUS"
echo ""
echo "To grant root permission to HyperCameraMod:"
echo "1. Open $ROOT_STATUS app"
echo "2. Go to App List / Superuser settings"
echo "3. Find 'HyperCameraMod' (io.rezikham.hypercameramod)"
echo "4. Grant root / Allow superuser access"
echo ""
echo "Then enable module in LSPosed/Vector:"
echo "  Scope: com.android.camera"
echo "  Force-stop Camera after enabling"
echo "============================================"
echo ""

log -t HyperCameraMod "Action button pressed, status shown"
exit 0