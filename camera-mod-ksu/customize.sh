#!/system/bin/sh
# HyperCameraMod module installer customization script
# This script is SOURCED by the KernelSU module installer (update-binary)
# after all files are extracted. Do NOT execute directly.

# KernelSU installer provides these variables:
#   MODPATH - where module files should be installed
#   ZIPFILE - path to the module ZIP
#   ARCH    - device architecture (arm64, etc.)
#   API     - Android API level
#   KSU_VER - KernelSU version
#   TMPDIR  - temporary directory

# ui_print and abort are available as functions

ui_print "HyperCameraMod installer: preparing module..."

# Check if APK exists in the extracted files
APK_PATH="$MODPATH/HyperCameraMod.apk"
if [ ! -f "$APK_PATH" ]; then
    abort "ERROR: HyperCameraMod.apk not found in module ZIP"
fi

# Module will be installed to /data/adb/modules/hypercameramod (MODPATH)
# APK is already in the right place at $MODPATH/HyperCameraMod.apk
# post-fs-data.sh will handle installation on first boot

ui_print "HyperCameraMod module files prepared."
ui_print ""
ui_print "IMPORTANT: After installation completes and you reboot:"
ui_print "1. Open KernelSU app (or your root manager)"
ui_print "2. Go to App List / Superuser settings"
ui_print "3. Find 'HyperCameraMod' (io.rezikham.hypercameramod)"
ui_print "4. Grant root / Allow superuser access"
ui_print ""
ui_print "Then enable the module in LSPosed/Vector,"
ui_print "scope 'com.android.camera', force-stop Camera."
ui_print ""

# Request hot install if possible (apply without reboot)
# Only if we're not changing system/ files (we removed system/)
export MODULE_HOT_INSTALL_REQUEST=true

ui_print "HyperCameraMod installer done."