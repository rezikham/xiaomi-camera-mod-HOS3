# HyperCameraMod v1.1.0-224 Test Plan

## Prerequisites
- Xiaomi 14 Ultra (aurora) with HyperOS 3.0.304.0.WNAMIXM
- Camera 6.7.000280.0
- KernelSU Next (KOWX712) + rezygisk
- LSPosed/Vector installed
- nomount metamodule enabled

## Installation Steps
1. Flash `camera-mod-ksu-v1.1.0-224-fixed.zip` via KernelSU
2. Reboot device
3. Open KernelSU app → Grant root to "HyperCameraMod" (io.rezikham.hypercameramod)
4. Enable module in LSPosed/Vector:
   - Scope: `com.android.camera`
   - Force-stop Camera app after enabling
5. Open Camera app

## Test Cases

### 1. Module Installation & Boot Verification
**Expected**: Module loads without errors
```bash
# Check module is loaded
ls /data/adb/modules/hypercameramod/

# Check bind mounts
adb shell su -c "mount | grep hypercameramod"
```

**Verify in logs**:
```bash
adb logcat -d | grep -iE 'HyperCameraMod.*bind mount|HyperCameraMod.*M3/M9 LUTs'
```
Should show:
- "Plugin directory bind mount successful"
- "libAncHumanPreviewBokeh.so bind mount successful"
- "libmialgoengine.so bind mount successful"
- "M3/M9 LUTs staged, engine gate enabled, APK ready, plugins bind-mounted"

### 2. Plugin Loading Verification (Critical)
**Expected**: SingleBokehPreview and libAncHumanPreviewBokeh.so loaded in cameraserver
```bash
adb shell su -c "grep -E 'singlebokeh|AncHuman|mialgoengine' /proc/\$(pidof vendor.qti.cameraserver)/maps"
```
Should show:
- `com.xiaomi.plugin.singlebokehpreview.so`
- `libAncHumanPreviewBokeh.so`
- `libmialgoengine.so`

### 3. SELinux Verification
**Expected**: No SELinux denials for module files
```bash
adb logcat -d | grep avc | grep -iE 'hypercameramod|singlebokeh|AncHuman|mialgoengine'
```
Should show NO denials (empty output)

### 4. SAT Pipeline Verification
**Expected**: No DualSAT CreateUsecaseObjectFail errors
```bash
adb logcat -d | grep -iE 'DualSAT|CreateUsecaseObjectFail'
```
Should show NO errors (empty output)

### 5. M3/M9 LUT Self-Install (APK)
**Expected**: LUT files staged on first M3/M9 selection
```bash
# Before selecting M3/M9
adb shell su -c "ls -la /data/vendor/camera/lut_m*.png"

# After selecting M3 or M9 in camera UI and taking a photo
adb shell su -c "ls -la /data/vendor/camera/lut_m*.png"
```
Files should exist with correct permissions (0644) and SELinux context (vendor_camera_data_file)

**Logs to verify**:
```bash
adb logcat -d | grep -iE 'LUT files staged|ensureLutFiles'
```
Should show: "LUT files staged to /data/vendor/camera/"

### 6. M3/M9 Filter Functionality
**Steps**:
1. Open Camera → Photo mode
2. Tap filter icon → Film strip
3. Select "M3" (should be after "Original")
4. Take a photo
5. Verify grayscale output (M3 = filterId 86 → lut_m3 exact grayscale)
6. Select "M9"
7. Take a photo
8. Verify color output (M9 = filterId 85 → lut_m9 color)

### 7. Original Portrait (Software Path)
**Steps**:
1. Enable "Original Portrait Style" toggle in HyperCameraMod settings
2. Open Camera → Portrait mode
3. Select "Original" style (Leica Looks)
4. Take a portrait photo
5. Verify bokeh effect applied in the saved JPEG
6. Check Gallery for "Original" badge (EXIF legendmode=0)

**Logs to verify**:
```bash
adb logcat -d | grep -iE 'original_portrait|bokeh_renderer|EssentialService|legendmode'
```

### 8. EXIF legendmode Tagging (Software-Native Enhancement)
**Expected**: Gallery shows LEICA button on M3/M9 photos
```bash
# After taking an M9 photo, check EXIF
adb shell su -c "exiftool -legendmode /sdcard/DCIM/Camera/IMG_*.jpg | tail -1"
# Expected: legendmode = 1 (M9) or 2 (M3)
```

### 9. Duck Detector / Mount Detection
**Expected**: Clean mount scan (if KSU module not used for APK)
```bash
# If testing APK without KSU module (LUTs staged manually via adb)
# Duck Detector should show no KernelSU overlay mount
```

### 10. Property-Based Software Workaround (Requires KSU Module v1.1.0-222+)
**Expected**: `persist.vendor.camera.leicafilter.forceEnable` may force-enable Leica filter processing

**Important**: This only works because our KSU module bind-mounts `mileicafilter.so`. Stock Aurora does NOT have this plugin natively.

```bash
# Force-enable Leica filter
adb shell su -c "setprop persist.vendor.camera.leicafilter.forceEnable 1"

# Check if plugin processes captures
adb logcat -d | grep -iE 'mileicafilter|LeicaFilter|legendMode|isFeatureEnable'

# Take an M9 photo and check EXIF
adb shell su -c "exiftool -legendmode /sdcard/DCIM/Camera/IMG_*.jpg | tail -1"
# May show legendmode if plugin processes correctly
```

**Limitations**:
- HAL doesn't feed `legendMode` tag 508, so plugin reads 0 (no M3/M9 distinction)
- Property resets on reboot
- Not a substitute for HAL binary patch

---

## HAL Backport Test Cases (Requires HAL Patch — Not Yet Built)

These tests apply only after the HAL binary patch (specified in `docs/hal-backport/14-hal-patch-specification.md`) is applied and flashed.

### 10. HAL Patch Verification
**Expected**: Patched HAL loads without crashes
```bash
# Check HAL binary integrity
adb shell su -c "sha256sum /odm/lib64/hw/camera.qcom.so"
# Should match the patched binary's hash

# Check boot stability
adb logcat -d -b all -d | grep -iE 'camera.*crash|provider.*abort|ChiX.*timeout'
# Should show NO camera crashes
```

### 11. Native legendMode Handling
**Expected**: HAL reads legendMode 1/2 and processes M3/M9 inline
```bash
# Select M9 in camera, capture, check logs
adb logcat -d | grep -iE 'legendMode|oemIsLegend|MPluginModule|mileicafilter|legendST'
# Should show legendMode being processed by HAL
```

### 12. Native Original Portrait
**Expected**: `libAncHumanPreviewBokeh.so` loads and processes preview frames
```bash
# Check plugin loaded in HAL process
adb shell su -c "grep libAncHuman /proc/\$(pidof vendor.qti.camera.provider-service_64)/maps"
# Should show libAncHumanPreviewBokeh.so loaded

# Check no 5780ms semaphore timeout
adb logcat -d | grep -iE 'WaitForSemaphore.*timeout|TriggerRecovery'
# Should show NO timeouts
```

---

## Debug Commands Reference

### Check Module Status
```bash
adb shell su -c "cat /data/adb/modules/hypercameramod/module.prop"
```

### Check Bind Mounts
```bash
adb shell su -c "mount | grep -E 'odm/lib64|hypercameramod'"
```

### Check LUT Files
```bash
adb shell su -c "ls -laZ /data/vendor/camera/"
```

### Check Engine Gate Property
```bash
adb shell getprop camera.debug.mivi3.output.jpeg
```

### Check APK Installation
```bash
adb shell pm list packages | grep hypercameramod
adb shell dumpsys package io.rezikham.hypercameramod | grep version
```

### Full Logcat for Camera Session
```bash
adb logcat -d -s Camera* HyperCameraMod *:S > camera_session.log
```

### Extract HAL from Device (for offline patching)
```bash
python3 tools/hal-port/aurora/hal_patch.py extract --serial 1c4e4dec --out /tmp/hal-extract
```

### Find Key Offsets in HAL
```bash
python3 tools/hal-port/aurora/hal_patch.py find-offsets /tmp/hal-extract/camera.qcom.so
```

### Verify Patched Plugin
```bash
python3 tools/hal-port/aurora/hal_patch.py patch-plugin \
  /tmp/hal-extract/com.xiaomi.plugin.mileicafilter.so \
  /tmp/patched/mileicafilter.so
```

---

## Expected File Locations After Install

| File | Location | Source | Notes |
|------|----------|--------|-------|
| Module scripts | `/data/adb/modules/hypercameramod/` | KSU module | |
| Plugin libs (mileicafilter, legendST, etc.) | `/odm/lib64/camera/plugins/` | **Module bind mount** | NOT stock Aurora |
| libAncHumanPreviewBokeh.so | `/odm/lib64/` | **Module bind mount** | NOT stock Aurora |
| libmialgoengine.so | `/odm/lib64/` | **Module bind mount** | Replaces stock 834KB version |
| SAT configs | `/odm/etc/camera/xiaomi/` | **Module bind mount** | Module-provided versions |
| Leica filter params | `/odm/etc/camera/` | **Module bind mount** | Module-provided versions |
| M3/M9 LUTs | `/data/vendor/camera/` | Staged by post-fs-data.sh / APK | |
| APK | User app | Installed by post-fs-data.sh | |
| Stock HAL | `/odm/lib64/hw/camera.qcom.so` | **Stock Aurora** | NOT bind-mounted — target for HAL patch |

**Important distinction**: Files marked "Module bind mount" are provided by our KSU module overlay, not stock Aurora. Stock Aurora does NOT natively have `mileicafilter.so`, `legendST.so`, `singlebokehpreview.so`, or `leica_filter_param_m3.bin`/`m9_*.bin`.

## Rollback Plan
If issues occur:
1. Disable module in KernelSU
2. Reboot
3. Or run uninstall: `adb shell su -c "/data/adb/modules/hypercameramod/uninstall.sh"`
4. If HAL was patched, restore stock HAL from backup

## Known Limitations
- ChiX wrapper not included (experimental, requires donor app on device)
- Essential Moments pipeline integrated but off by default (requires QNN model compatibility)
- Real M9 style-transfer blocked without compatible QNN HTP V81 stack
- Native HAL patching not yet built — requires device for HAL binary extraction and testing
- EXIF legendmode tagging not yet implemented in APK

## Success Criteria

### Software Path (Currently Built)
- [ ] Module loads without SELinux denials
- [ ] SingleBokehPreview plugin loaded in cameraserver
- [ ] libAncHumanPreviewBokeh.so loaded in cameraserver
- [ ] libmialgoengine.so loaded in cameraserver
- [ ] No DualSAT CreateUsecaseObjectFail errors
- [ ] M3/M9 LUTs staged and render correctly
- [ ] Original Portrait (software) captures with bokeh
- [ ] Gallery shows "Original" badge for captured portraits

### Software-Native Enhancements (Not Yet Implemented)
- [ ] EXIF legendmode tag written on M3/M9 photos
- [ ] Gallery shows LEICA button on M3/M9 photos
- [ ] Color profile matches donor look (ΔE < 5)

### HAL Patch Path (Not Yet Built)
- [ ] Patched HAL boots without camera crashes
- [ ] Native legendMode 1/2 processing in HAL
- [ ] libAncHumanPreviewBokeh.so processes preview frames
- [ ] No 5780ms semaphore timeout on Original Portrait
- [ ] QNN models load and execute on Aurora's DSP
