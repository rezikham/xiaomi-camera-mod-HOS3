# Device-Dependent Tasks (for when phone connects)

This file lists all tasks that require a physical device connection (adb, logcat, etc.).
All static analysis, code review, documentation, and build system work is complete.

---

## ChiX Wrapper (HCM Legend)

### 1. Tombstone Analysis - CRITICAL
**Command:** `adb pull /data/tombstones/tombstone_XX`
**Goal:** Map the `candyProcess+428` fault address to a specific field/load instruction.
**Expected:** Identify which `ImageParams` field or donor structure the crashing call loads.
**Files to check:** `hcm_legend.c` translate(), donor `getImageInfo` at 0x11f3c.

### 2. Dump ImageParams Fields - CRITICAL
**Command:** Add debug logging to wrapper, rebuild, install, capture.
**Log:** `HCM-Legend: img fmt=... wh=... native=... usage=... meta=...`
**Compare:** Against stock filter's `input: %dx%d,sw=...` at 0xc30c.
**Verify:** Translated `native` handle (+0x70→+0xb0) and `usage` (+0x1c→+0x38).

### 3. Compare libMiPhotoFilter.so Exports
**Command:** 
```sh
adb pull /system/lib64/libMiPhotoFilter.so
nm -D libMiPhotoFilter.so | grep -E 'videoprocess|GL|EGL'
```
**Compare:** With Nezha donor's expected symbols (from `candyProcess` disassembly).
**Action:** If mismatch, ship Nezha's `libMiPhotoFilter.so` as `libHcmPhotoFiltr.so` (already done in build).

### 4. Verify Style Selection End-to-End
**Test:** 
```sh
adb shell setprop persist.vendor.camera.hypercam.style 2
# Take photo, check saturation
adb shell setprop persist.vendor.camera.hypercam.style 1
# Take photo, check saturation
adb shell setprop persist.vendor.camera.hypercam.style 0
# Verify pass-through
```
**Expected:** Measurable saturation deltas matching docs (M3: |R-G|~2.8, M9: |R-G|~9.3).

### 5. Test Render Payload Path
**Command:** Select M3/M9 in Leica Essential UI, capture, check logcat.
**Log:** `HCM-Legend: style_from_meta found lut_m3` or similar.
**Verify:** `style_from_meta()` parses `com.xiaomi.mivi2.render` JSON correctly.

### 6. Confirm Pipeline Insertion in normalsnapshot.json
**Command:** `adb pull /odm/etc/camera/xiaomi/normalsnapshot.json`
**Verify:** `LegendFilterInstance0` present before `FilterInstance0`, no `NodeMask`.

### 7. SM8750 Testing (17 Ultra / 18 Pro Max)
**If device available:** Install module, test wrapper.
**Adjust:** `translate()` if `ImageParams` layout differs (check offsets via tombstone/logging).

---

## Moment App Pipeline

### 1. Verify libm9base.so Loads
**Command:** 
```sh
adb shell setprop camera.feature.essential_pipeline 1
adb logcat -s HCM-Essential
```
**Expected:** `libm9base loaded`

### 2. Test M3 Capture (Grayscale Fallback)
**Steps:** Enable Essential pipeline, select M3, take photo.
**Log:** 
```
Essential candidate IMG_....jpg legend=2
service created
libm9base loaded
Monopan grain=false in ... ms
legend=2 <in> -> <out> bytes in ... ms
applied legend=2 to IMG_....jpg
```
**Verify:** Pull JPEG, check `max(|R-G|, |G-B|) == 0` (true monochrome).

### 3. Test M9 Capture (Parametric Filter)
**Steps:** Select M9, take photo.
**Log:** `legend=1` with color output.
**Verify:** Color JPEG with M9 parametric look (not full style-transfer).

### 4. Verify EXIF Preservation
**Command:** `exiftool` on result JPEG.
**Check:** Original EXIF APP1 copied, Model unchanged, no Leica container added.

### 5. Test Monopan (Optional Libs)
**Prereq:** Copy libs to jniLibs, rebuild:
```sh
unzip -o "essential-moments (1).apk" \
  'lib/arm64-v8a/libmonopan_runner.so' 'lib/arm64-v8a/libmialgo_monopan.so' \
  'lib/arm64-v8a/libQnn*.so' -d /tmp/em_libs
cp /tmp/em_libs/lib/arm64-v8a/*.so src/main/jniLibs/arm64-v8a/
./gradlew assembleRelease --offline
```
**Test:** M3 capture, expect `Monopan grain=true` OR fast fallback to grayscale.
**Critical:** Camera must not crash.

### 6. Test Crash Isolation
**Command:** During M3 processing, `adb shell am force-stop io.rezikham.hypercameramod`
**Verify:** Camera stays alive, shutter usable, original JPEG kept.

### 7. Test Double-Processing Guard
**Setup:** Enable both HAL LUT slot + Essential pipeline.
**Test:** M3 capture.
**Expected:** Pipeline logs skip; HAL slot renders.

### 8. Measure Latency
**Log:** Timestamps in `EssentialPipeline.handle()` and `EssentialService.process()`.
**Target:** End-to-end < 5 seconds typical.

---

## Build & Install Commands

### ChiX Wrapper
```sh
cd /home/rezikham/PoC/xiaomi-camera-mod
./tools/hal-port/aurora/build_legend_wrapper.sh
python3 tools/hal-port/aurora/package_module.py out/hcm-legend
ksud module install out/hcm-legend.zip
# Reboot
```

### Essential Pipeline (APK)
```sh
cd /home/rezikham/PoC/xiaomi-camera-mod
./gradlew assembleRelease --offline
adb install -r build/outputs/apk/release/xiaomi-camera-mod-release.apk
# Enable in LSPosed settings: "Leica Essential pipeline (real M3/M9)"
# Disable: "Render M3/M9 in the camera engine (HAL LUT slot)"
adb shell am force-stop com.android.camera
```

---

## Quick Debug Commands

```sh
# ChiX wrapper logs
adb logcat -s HCM-Legend

# Essential pipeline logs
adb logcat -s HCM-Essential HyperCameraMod

# Check module files
adb shell ls -la /data/adb/modules/hcm-legend*/system/odm/lib64/

# Check sepolicy
adb shell ksud sepolicy check /data/adb/modules/hcm-legend*/sepolicy.rule

# Pull snapshot JSONs
adb pull /odm/etc/camera/xiaomi/normalsnapshot.json
adb pull /odm/etc/camera/xiaomi/leicasnapshot.json

# Check vendor properties
adb shell getprop persist.vendor.camera.hypercam.style
adb shell getprop camera.debug.mivi3.output.jpeg

# Verify LUT files staged
adb shell ls -la /data/vendor/camera/lut_m*.png
```

---

## Known Blockers (require device)

| Blocker | Component | Priority |
|---|---|---|
| `candyProcess+428 SEGV_ACCERR` | ChiX wrapper | CRITICAL |
| Monopan QNN V81 vs V75 | Moment App | HIGH (optional) |
| SM8750 ImageParams layout | ChiX wrapper | MEDIUM |
| M9 style-transfer (QNN) | Moment App | LOW (not bundled) |

---

## Success Criteria

**ChiX Wrapper:**
- [ ] `candyProcess` completes without crash
- [ ] M3/M9 saturation deltas measurable and distinct
- [ ] Render payload (`com.xiaomi.mivi2.render`) drives style selection
- [ ] Works on SM8750 (17 Ultra/18 Pro Max) if available

**Moment App:**
- [ ] M3 produces true monochrome (R=G=B)
- [ ] M9 produces color parametric look
- [ ] EXIF preserved, no crashes
- [ ] Crash isolation works (camera survives module kill)
- [ ] Optional Monopan: either grain=true or graceful fallback

