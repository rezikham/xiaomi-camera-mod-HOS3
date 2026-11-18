# HAL Patch Specification: Aurora (14 Ultra) → Native Legendary M3/M9 + Original Portrait

**Status**: Offline specification. Aurora HAL binaries extracted to `/run/media/rezikham/970/PoC/hal-extract-aurora/`. Device access needed for patch application and testing.

**Target Binaries (Stock Aurora — extracted from device):**

| Binary | Size | SHA-256 | Path |
|--------|------|---------|------|
| `camera.qcom.so` | 17,288,384 | `5b30cefde5b4a4aabf52282778c6d757` | hal-extract-aurora/camera.qcom.so |
| `camera.qcom.sm8650.so` | 18,891,856 | `cadfdc9343c43b905b9956c4743fd04c636e489a60775c35622130516a97391e` | hal-extract-aurora/camera.qcom.sm8650.so |
| `camera.xiaomi.so` | 3,435,168 | `cfc2f41fb0fd152fce77db0ea3663e4e` | hal-extract-aurora/camera.xiaomi.so |
| `com.qti.chi.override.so` | 5,921,984 | `7bb41bef9278322f981f07b9abb327d7` | hal-extract-aurora/com.qti.chi.override.so |
| `libmialgoengine.so` | 834,440 | `5e5497e90f2a43d89a62178d406d18ed` | hal-extract-aurora/libmialgoengine.so |
| `libcom.xiaomi.pluginutils.so` | 405,104 | `2bc0c96b9f08075bacb3d9a51ad4997c` | hal-extract-aurora/libcom.xiaomi.pluginutils.so |

**Stock Aurora plugin directory** (39 plugins, NO mileicafilter/legendST/singlebokehpreview natively — these are module-provided).

---

## Overview

Aurora's HAL cannot render M3/M9 or Original Portrait because:

1. **No `legendMode` session parameter handling** — `camera.qcom.so` has no `legendMode` string, no `QueryVendorTagLocation("com.xiaomi.sessionparams.legendMode")` call
2. **No `MiaPluginModule` vtable loader** for `libAncHumanPreviewBokeh.so`
3. **No `mileicafilter`/`legendST` plugin nodes** in pipeline JSONs

Two patch approaches:

| Approach | Scope | Risk | When to use |
|----------|-------|------|-------------|
| **A. Plugin bridge (existing)** | Patch `mileicafilter.so` to read `stylizationType` instead of `legendMode`; bridge app `legendMode` → `stylizationType` in ModuleMain | Medium — double-processing risk | First attempt |
| **B. HAL binary patch** | Patch `camera.qcom.so` to add `legendMode` vendor tag lookup + MiMetadata tag 508 write | High — breaks all captures if wrong | When plugin bridge fails |

This document specifies **Approach B**.

---

## Aurora HAL Findings (From Extracted Binaries)

### `stylizationType` Is Supported, `legendMode` Is Not

| Finding | Evidence |
|---------|----------|
| `stylizationType` string at `0x917d7` | `strings -t x camera.qcom.so \| grep stylizationType` |
| Two `QueryVendorTagLocation("com.xiaomi.sessionparams.stylizationType")` calls | At `0xecd8a4` (in `ReadOutputVendorTag`) and `0xf55bc0` (in `DataCompress`) |
| `GetImageStylizationTypeTag` string at `0x83559` | String reference only |
| NO `legendMode` string anywhere | Confirmed — zero matches in all 5 stock binaries |
| NO `QueryVendorTagLocation("com.xiaomi.sessionparams.legendMode")` | Confirmed — no such call |

### `stylizationType` Query Pattern (To Replicate for `legendMode`)

Both call sites use the same pattern:

```asm
; QueryVendorTagLocation("com.xiaomi.sessionparams.stylizationType", &tag_location, &tag_count)
adrp  x1, 0x91000          ; page containing "stylizationType" string
add   x1, x1, #0x7d7       ; offset to string = 0x917d7
add   x2, sp, #0xa8        ; output: tag location
str   wzr, [sp, #0xa8]     ; init tag location = 0
bl    QueryVendorTagLocation
cbz   w0, skip             ; if query failed, skip
; ... read tag value from CaptureRequest metadata ...
```

The `QueryVendorTagLocation` function is at PLT `0xff0120`.

### Donor Reference (Nezha/Madrid `camera.qcom.core.so`)

| Address | Instruction | Meaning |
|---------|-------------|---------|
| `0x11a57cc` | `ldr w22, [x8]` | Load `legendMode` |
| `0x11a57d0` | `sub w8, w22, #1` | `legendMode - 1` |
| `0x11a57d4` | `cmp w8, #2` | Compare with 2 |
| `0x11a57d8` | `cset w8, lo` | `oemIsLegendMode = (legendMode-1) < 2` |
| `0x11a57dc` | `str w8, [x20]` | Store result |

### Plugin Symbols Available

| Library | Symbol | Address | Notes |
|---------|--------|---------|-------|
| `libmialgoengine.so` | `mialgo2::MPluginModule::getPluginWraperByPluginName` | `0x80f7c` | Exported, usable |
| `libmialgoengine.so` | `mialgo2::MPluginModule::getInstance` | `0xca7d0` | Exported |
| `libcom.xiaomi.pluginutils.so` | `mialgo2::ProviderManager::add` | `0x1e3e0` | Exported |
| `libcom.xiaomi.pluginutils.so` | `PLUMA_INTERFACE_VERSION` | `0x12d3c` | Value: 1 |
| `libcom.xiaomi.pluginutils.so` | `PLUMA_INTERFACE_LOWEST_VERSION` | `0x12d40` | Value: 1 |

---

## Approach B: HAL Binary Patch Details

### B.1: Add `legendMode` Vendor Tag Lookup

**Where to patch**: `camera.qcom.so`

**What to add** (in two places, matching the `stylizationType` pattern):

1. **In `ReadOutputVendorTag`** (around `0xecd8a4`): Add `legendMode` query after `stylizationType` query
2. **In `DataCompress`** (around `0xf55bc0`): Add `legendMode` query after `stylizationType` query

**Patch steps**:
1. Add string `com.xiaomi.sessionparams.legendMode` to `.rodata` section
2. Add `adrp x?, 0x91000; add x?, x1, #<offset>` to reference the new string
3. Add `QueryVendorTagLocation` call with the new string
4. Read tag value from CaptureRequest metadata
5. If value is 1 or 2, compute `oemIsLegendMode = (value - 1) < 2` and store in MiMetadata tag 508

### B.2: Add MiMetadata Tag 508 Write

When `legendMode` is 1 (M9) or 2 (M3):
1. Call `MetaTagWrapper::getInstance()` 
2. Call `MetaTagWrapper::operator()(tag_index=508)` to get the tag location
3. Call `MiMetadata::find()` to write the byte value
4. `mileicafilter` reads tag 508 at offset `this+0x26` in `initialize` and `isEnabled`

### B.3: Add `MiaPluginModule` Loader for Original Portrait

When `stylizationType == 171 && filterId == 2`:
1. `dlopen("/odm/lib64/libAncHumanPreviewBokeh.so")`
2. `dlsym("_anc_human_effects_preview_bokeh_get_api_impl")`
3. Call with vtable buffer, struct_size=0x50, major=2, minor=4
4. Walk 10-function vtable: initialize → setParameter/processRequest per frame → flushRequest → destroy

---

## Verification Commands (Offline)

```bash
# Verify no legendMode string in stock HAL
strings -t x camera.qcom.so | grep -i legendMode
# Expected: no output

# Verify stylizationType string location
strings -t x camera.qcom.so | grep stylizationType
# Expected: 917d7 stylizationType

# Verify PLUMA interface version
python3 -c "
import struct
with open('libcom.xiaomi.pluginutils.so','rb') as f:
    f.seek(0x12d3c); v1 = struct.unpack('<I', f.read(4))[0]
    f.seek(0x12d40); v2 = struct.unpack('<I', f.read(4))[0]
    print(f'PLUMA_INTERFACE_VERSION={v1}, LOWEST={v2}')
"
# Expected: PLUMA_INTERFACE_VERSION=1, LOWEST=1

# Verify MPluginModule symbol
nm -DC libmialgoengine.so | grep MPluginModule
# Expected: getPluginWraperByPluginName, getInstance exported
```

---

## Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| HAL patch breaks all captures | Critical | Keep stock HAL backup; test boot stability first |
| `.rodata` section full for new string | Medium | Use existing empty space or append to section |
| Calling convention mismatch | High | Study `stylizationType` call pattern exactly |
| `mileicafilter` double-processing with `FilterInstance0` | Medium | Compare output; move LeicaFilter before Filter for Legendary only |
| Missing dependency libs (`libmialgo_styletrans.so`, `libmetatagwrapper.so`) | Medium | Module bind-mounts these; verify they load |
