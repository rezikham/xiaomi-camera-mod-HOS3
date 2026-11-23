# Original Portrait HAL Fix Plan for Xiaomi 14 Ultra (aurora)

## Problem
The 14 Ultra (aurora) HAL does not support Original Portrait. When selected:
1. Camera app sends `stylizationType=171` (MODE_PORTRAIT) + `filterId=2` (STYLE_ORIGINAL)
2. HAL attempts to start **algorithm-up SAT preview session**
2. Preview bokeh engine (`libmialgo_mc_bokeh_cdsp_skel.so` on DSP) doesn't implement SAT session protocol
3. Engine never returns frame → 5780ms semaphore timeout (`ChiX WaitForSemaphore()`)
4. Preview drops to ~0.1 fps, vendor camera provider aborts

## Root Cause: Missing HAL Components

| Component | 17U/18PM (Have) | 14U Aurora (Missing) |
|-----------|-----------------|---------------------|
| Preview Bokeh Engine | `libAncHumanPreviewBokeh.so` (ARM64, 8.7MB) | `libmialgo_mc_bokeh_cdsp_skel.so` (DSP SKEL) |
| MiaPluginModule Interface | 10-function vtable | SKEL (open/close/call only) |
| Session Type | Algorithm-up SAT preview | Standard DSP offload |
| Human-Aware Params | 12 params (focus_rect, camera_role, etc.) | None |

## Required HAL Porting

### 1. Port `libAncHumanPreviewBokeh.so`
- **Source**: 18 Pro Max (madrid) `/odm/lib64/libAncHumanPreviewBokeh.so` (8,671,560 bytes)
- **Architecture**: ARM64 (not DSP)
- **Interface**: `MiaPluginModule` vtable (10 functions: initialize, isEnabled, processRequest, setParameter, flushRequest, getParameter, destroy, etc.)
- **Parameter Tags**: 0x10000-0x1000B (f_number, focus_rect, bokeh_style, zoom_rate, camera_role, is_auto_f_number, is_static_scene, motion_speed, bypass)

### 2. Port Parameter Files
From 18 Pro Max `/odm/etc/camera/`:
- `leica_filter_param_original_preview.bin`
- `leica_filter_param_original_snapshot.bin`
- Any M10R/Original-specific parameter files

### 3. Update CHI Plugin Graph
Create `original_portrait_snapshot.json` referencing the new plugin.

### 4. Update HAL Capabilities
- Add `Original` to `legendMode.capsInfo` (if applicable)
- Ensure `xiaomi.capabilities.stylizationTypeMask` includes bit for Original (value 2)

### 5. Update SELinux Policy
Allow `hal_camera_default` to load/access the new library and parameter files.

## Porting Strategy

### Option A: Full HAL Port (Best)
1. Extract `libAncHumanPreviewBokeh.so` + dependencies from 18 Pro Max
2. Place in `/odm/lib64/` on aurora
3. Copy parameter files to `/odm/etc/camera/`
4. Create CHI plugin graph JSON
5. Update module's `post-fs-data.sh` to stage files and set SELinux contexts

### Option B: Minimal Plugin Injection (Via KernelSU Module)
1. Include `libAncHumanPreviewBokeh.so` in KernelSU module
2. `post-fs-data.sh` copies to `/odm/lib64/`
3. Copy parameter files to `/odm/etc/camera/`
4. Use existing `sepolicy.rule` for context
5. May require CHI plugin registration via hook

## Validation Steps

1. **HAL loads plugin**: `dmesg | grep -i anc` or `logcat | grep -i anc`
2. **SAT session starts**: `logcat | grep -i "SAT\|WaitForSemaphore"`
3. **Preview frames return**: No semaphore timeout, normal preview fps
4. **Capture works**: Original Portrait photo saved with correct processing
5. **EXIF tag**: `legendmode=0` (Original) in captured JPEG

## Dependencies to Check

| Library | Required? | Notes |
|---------|-----------|-------|
| `libAncHumanPreviewBokeh.so` | **Yes** | Core preview bokeh engine |
| `libmialgo_styletrans.so` | Maybe | For style transfer if used |
| `libmialgo_monopan.so` | No | M3 Monopan, not Original |
| `libmialgo_mc_bokeh_cdsp_skel.so` | **No** | Current DSP engine - causes freeze |

## Files to Extract from 18 Pro Max

```
/odm/lib64/libAncHumanPreviewBokeh.so
/odm/etc/camera/leica_filter_param_original_preview.bin
/odm/etc/camera/leica_filter_param_original_snapshot.bin
/odm/lib64/camera/plugins/com.xiaomi.plugin.originalportrait.so (if exists)
```

## Next Steps

1. **Extract 18 Pro Max HAL dump** (if not already available)
2. **Analyze `libAncHumanPreviewBokeh.so`** with Ghidra/IDA for exact interface
3. **Test minimal plugin injection** via KernelSU module
4. **Iterate on SAT session protocol** until preview frames return
5. **Validate capture pipeline** end-to-end

---

**Status**: Workaround reverted. Ready for proper HAL porting.

## Software path status (2026-11-24)

The app sends the portrait style as `com.xiaomi.sessionparams.stylizationType`,
not `filterId`:

| Value | Style |
| --- | --- |
| 0 | Master |
| 1 | Leica |
| 2 | Original |

The module now intercepts value `2` while the portrait module is active
(`z3.a.s` tracks the module id), maps it to `0` (Master) for preview and
capture, and post-processes the saved JPEG. This avoids the algorithm-up SAT
preview that deadlocks the Aurora DSP bokeh engine.

Verified on the 14 Ultra (`1c4e4dec`, Camera 6.7.000280.0):

- no `WaitForSemaphore` timeout or camera-provider recovery when Original is
  selected;
- the capture is saved and post-processed;
- the camera EXIF is copied to the processed JPEG (Make, Model, GPS,
  DateTimeOriginal, F-number, ISO);
- the OpenGL ES 3.1 bokeh renderer loads and runs (JNI bridge, immutable
  image textures, radial focus falloff).

Remaining gap: the bokeh uses the module's synthetic radial depth, not the
camera's real depth map, and the preview is Master rather than the native
SAT preview. Real depth extraction and the native `libAncHumanPreviewBokeh`
port remain the next steps.

