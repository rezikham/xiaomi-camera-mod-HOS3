# Property-Based Software Workaround: Force-Enable Leica Filter on Aurora

**Status**: Offline specification. Requires our KSU module (provides `mileicafilter.so`).

**Prerequisite**: KSU module v1.1.0-222+ installed and enabled (provides `mileicafilter.so` via bind mount).

---

## Discovery

Aurora's module-provided `mileicafilter.so` reads these system properties via `property_get_int32`:

| Property | Offset in `mileicafilter.so` | Purpose |
|----------|------------------------------|---------|
| `persist.vendor.camera.leicafilter.forceEnable` | `0x61DB` | Force-enable flag |
| `persist.vendor.camera.leicafilter.lux` | `0x5ADF` | Lux value |
| `persist.vendor.camera.leicafilter.cct` | `0x4CFF` | CCT value |
| `persist.vendor.camera.leicafilter.bypassMode` | `0x5C2D` | Bypass mode |
| `vendor.debug.camera.liveshot.version` | `0x5E37` | Liveshot version |

The `forceEnable` property is read in `isEnabled()` at `0xDE38`:
```asm
adrp  x0, 0x5000
add   x0, x0, #0xE37      ; 0x5E37 = "persist.vendor.camera.leicafilter.forceEnable"
mov   w1, wzr             ; default value = 0
bl    property_get_int32  ; read property
str   w0, [x27, #0xA8]    ; store in m_debugMode or similar field
```

**Critical**: This only works because our module bind-mounts `mileicafilter.so` to `/odm/lib64/camera/plugins/`. Stock Aurora does NOT have this plugin.

---

## How to Test (When Phone Is Connected)

```bash
# Force-enable the Leica filter (may enable M3/M9 processing)
adb shell su -c "setprop persist.vendor.camera.leicafilter.forceEnable 1"

# Check if the plugin processes the capture
adb logcat -d | grep -iE 'mileicafilter|LeicaFilter|legendMode|isFeatureEnable'

# Take a photo and check if it has Leica processing
# Gallery should show LEICA button if EXIF legendmode tag is present
```

**Expected behavior**: With `forceEnable=1`, the `mileicafilter` plugin may process captures even without the HAL feeding `legendMode` tag 508. The plugin reads tag 508 via `MetaTagWrapper`, which may default to 0 — so M3/M9 may still not work correctly without the HAL patch.

**Alternative**: Combine with `persist.vendor.camera.leicafilter.bypassMode` to control bypass behavior.

---

## Limitations

| Limitation | Explanation |
|------------|-------------|
| Requires module | `mileicafilter.so` is module-provided, not stock Aurora |
| No `legendMode` tag 508 | HAL doesn't feed legendMode into metadata, so plugin reads 0 |
| No `leica_filter_param_m3.bin` natively | Module provides these via bind mount |
| No QNN style models | `legendST.so` needs `libmialgo_styletrans.so` (missing) |
| Temporary property | Resets on reboot; needs boot script to persist |

---

## Relationship to HAL Patch

This property workaround is **not a substitute** for the HAL binary patch. It's a quick test to verify that `mileicafilter.so` can load and execute on Aurora. The HAL patch is still needed for:

1. Reading `legendMode` from CaptureRequest session parameters
2. Writing tag 508 into MiMetadata
3. Routing captures through the Legend pipeline (`legendsnapshot.json`)

---

## Files Affected

| File | Role |
|------|------|
| `mileicafilter.so` (module) | Reads `persist.vendor.camera.leicafilter.forceEnable` |
| `post-fs-data.sh` (module) | Can set property at boot via `resetprop` |
| `service.sh` (module) | Can set property at late_start via `setprop` |
