# HAL Patch Specification: Detailed Binary Patch for Aurora `camera.qcom.so`

**Status**: Offline specification. Device access required for testing.

**Target Binary**: `camera.qcom.so` (SHA-256: `8522181a2a68f17758e6b5d59b0cf7d08cd415fe590be651127e2a3a6bae65be`, Size: 17,288,384 bytes)

## Overview

This document specifies the exact binary patches needed to add native `legendMode` 1/2 (M9/M3) handling to Aurora's camera HAL. The patch adds:

1. **New string**: `com.xiaomi.sessionparams.legendMode` in `.rodata`
2. **Code patch**: At two existing `stylizationType` query sites, add `legendMode` vendor tag lookup
3. **Metadata write**: Write MiMetadata tag 508 when `legendMode` is 1 (M9) or 2 (M3)

## Patch 1: Add `legendMode` String to .rodata

**Location**: File offset `0x1E7C4C` (4074-byte gap in .rodata on page `0x1E7000`)

**String**: `com.xiaomi.sessionparams.legendMode\0` (36 bytes)

**Bytes to write** at `0x1E7C4C`:
```
63 6f 6d 2e 78 69 61 6f 6d 69 2e 73 65 73 73 69 6f 6e 70 61 72 61 6d 73 2e 6c 65 67 65 6e 64 4d 6f 64 65 00
```

**Why `0x1E7C4C`**: On page `0x1E7000`, offset `0xC4C` (3148). Fits in AArch64 `adrp`+`add` addressing (imm ≤ 4095).

**Reference code** (AArch64):
```asm
adrp  xN, 0x1E7000   ; load page
add   xN, xN, #0xC4C ; add offset = 0x1E7C4C
```

## Patch 2: Add `legendMode` Query at Call Site 1 (`ReadOutputVendorTag`)

**Location**: File offset `0xecd8a4` (in `ReadOutputVendorTag` function)

**Existing code** (queries `stylizationType`):
```asm
ecd8a4: 90ff8e21      adrp  x1, 0x91000       ; page of "stylizationType" string
ecd8a8: 911f5c21      add   x1, x1, #0x7d7    ; offset to "stylizationType" (0x917d7)
ecd8ac: 9102a3e2      add   x2, sp, #0xa8     & tag location output
ecd8b0: b900abff      str   wzr, [sp, #0xa8]  ; init output to 0
ecd8b4: b90057e9      str   w9, [sp, #0x54]   ; store w9
ecd8b8: 94048a1a      bl    QueryVendorTagLocation@plt
ecd8bc: 34000680      cbz   w0, skip          ; if failed, skip
```

**Patch**: Insert `legendMode` query immediately after the `stylizationType` query succeeds:

```asm
; After ecd8bc (cbz w0, skip), insert before ecd8c0:
ecd8bc: 90ff8e21      adrp  x1, 0x32000       ; page of "legendMode" string
ecd8c0: 911f5b21      add   x1, x1, #0xb91    ; offset to "legendMode" (0x32b91)
ecd8c4: 9102a3e2      add   x2, sp, #0xa8     ; tag location output (reuse same stack slot)
ecd8c8: b900abff      str   wzr, [sp, #0xa8]  ; init output to 0
ecd8cc: b90057e9      str   w9, [sp, #0x54]   ; preserve w9
ecd8d0: 94048a1a      bl    QueryVendorTagLocation@plt
ecd8d4: 34000680      cbz   w0, skip_legend   ; if legendMode query failed, skip
; Read legendMode value from CaptureRequest metadata and write tag 508 to MiMetadata
; (see Patch 3 for details)
skip_legend:
ecd8c0: 52800040      mov   w0, #0x2          ; original code continues
```

**Instruction encoding** (AArch64):
- `adrp x1, 0x32000`: `90ff8e21` (same encoding as `adrp x1, 0x91000` since both use page-aligned addresses)
- `add x1, x1, #0xb91`: `911f5b21`
- `add x2, sp, #0xa8`: `9102a3e2`
- `str wzr, [sp, #0xa8]`: `b900abff`
- `str w9, [sp, #0x54]`: `b90057e9`
- `bl QueryVendorTagLocation@plt`: `94048a1a`
- `cbz w0, skip_legend`: `34000680`

**Problem**: Inserting instructions shifts all subsequent code. Need to either:
- Find unused instruction slots (nop padding)
- Expand `.text` section (shifts all addresses after)
- Replace existing instructions with equivalent longer sequences

**Recommended**: Find a `nop` or redundant instruction sequence near the call site to replace.

## Patch 3: Write MiMetadata Tag 508

After successfully querying `legendMode`, read the value from CaptureRequest metadata and write tag 508 to MiMetadata:

```c
// Pseudo-code for what the inserted code should do:
uint8_t legendMode = read_capture_request_metadata_tag(legendMode_tag_location);
if (legendMode == 1 || legendMode == 2) {
    // oemIsLegendMode = (legendMode - 1) < 2  → true for 1 and 2
    // Write MiMetadata tag 508 with value legendMode
    update_camera_metadata_entry(miMetadata, 508, BYTE, 1, &legendMode);
}
```

**Available symbols** (from `camera.qcom.so` dynamic symbols):
- `update_camera_metadata_entry` (PLT)
- `add_camera_metadata_entry` (PLT)
- `append_camera_metadata` (PLT)
- `find_camera_metadata_entry` (PLT)
- `get_camera_metadata_entry` (PLT)

## Patch 4: Add `legendMode` Query at Call Site 2 (`DataCompress`)

**Location**: File offset `0xf55bc0` (in `DataCompress` function)

**Existing code** (same pattern as call site 1):
```asm
f55bc0: 90ff89e1      adrp  x1, 0x91000
f55bc4: 911f5c21      add   x1, x1, #0x7d7
f55bc8: 910203e2      add   x2, sp, #0x80
f55bcc: b90083ff      str   wzr, [sp, #0x80]
f55bd0: b90063ff      str   wzr, [sp, #0x60]
f55bd4: f90093ff      str   xzr, [sp, #0x120]
f55bd8: f90073ff      str   xzr, [sp, #0xe0]
f55bdc: 94026951      bl    QueryVendorTagLocation@plt
f55be0: 340006c0      cbz   w0, skip
```

**Patch**: Same as call site 1, but with stack offsets adjusted (`0x80` instead of `0xa8`).

## Alternative: Plugin Bridge (Already Implemented)

Instead of HAL binary patching, the plugin bridge approach (Approach A) is already implemented:

1. `mileicafilter.so` patched to read `stylizationType` (tag 29) instead of `legendMode` (tag 508)
   - Offset `0xC768`: `add x1, x1, #0x36e` → `add x1, x1, #0xdd7`
   - Offset `0xD780`: same patch
2. `ModuleMain.installLegendBridgeHook()` bridges `legendMode` → `stylizationType` in CaptureRequest
3. Marker file `/odm/etc/camera/hypercameramod_legend_bridge` enables the bridge

This approach does NOT require HAL binary patching and is the recommended path.

## Testing

When the phone is connected:

```bash
# 1. Flash patched HAL (via KSU module or vendor partition modification)
# 2. Reboot
# 3. Verify legendMode string exists in HAL
adb shell su -c "strings -t x /odm/lib64/hw/camera.qcom.so | grep legendMode"

# 4. Select M9 in camera UI, capture photo
# 5. Check logs for legendMode processing
adb logcat -d | grep -iE 'legendMode|oemIsLegend|MPluginModule|mileicafilter'

# 6. Verify Gallery shows LEICA button (EXIF tag 34992)
adb shell su -c "exiftool -legendmode /sdcard/DCIM/Camera/IMG_*.jpg | tail -1"
```

## Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| HAL patch breaks all captures | Critical | Keep stock HAL backup; test boot stability |
| Code injection shifts addresses | High | Use nop padding or section expansion |
| Calling convention mismatch | High | Study existing call patterns exactly |
| Metadata write fails silently | Medium | Add logging, check logcat |
