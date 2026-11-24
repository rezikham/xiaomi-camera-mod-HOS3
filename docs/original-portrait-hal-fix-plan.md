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
  image textures), but the shipping path now relies on the camera's Master
  portrait bokeh and only applies the Original colour profile.

Remaining gap: the bokeh uses the module's synthetic radial depth, not the
camera's real depth map, and the preview is Master rather than the native
SAT preview. Real depth extraction and the native `libAncHumanPreviewBokeh`
port remain the next steps.

### Real depth buffer finding (2026-11-24)

The camera does receive a real portrait depth buffer. It is reachable from the
JPEG task `s7.c.a(di.t)`:

```text
ParallelTaskData.e        -> ExifData
ExifData.getDepthData()   -> DepthData
DepthData.getPortraitDepthData() -> gi.e
gi.e.c()                  -> 25,165,824-byte buffer for a 12 MP portrait
```

The buffer is a 152-byte header followed by a `MCBOKEHSOT` container. Header
fields include `2047x1536`, `4096x3072`, and a payload length of `8,047,692`.
The container's `MCBOKEHDEPTH` section is compressed; the decoder is
`MialgoDeCodeData` in `/odm/lib64/libmiphone_capture_mdbokeh.so`
(`MialgoCaptureMdbokehProcess`). `portraitRawData` is the unprocessed portrait
JPEG, not a depth map.

Until that vendor decoder is wired up, the software path uses the camera's own
Master portrait bokeh (real depth processing) and applies the camera's real
Original portrait 3D LUT (`assets/original_portrait_lut.png`, `raw/lut_portrait_star_original`
from the 18 Pro Max Camera 6.8 APK). The synthetic OpenGL bokeh renderer is kept but disabled so it
does not double-blur the frame.

### 18 Pro Max preview bokeh engine ABI (2026-11-24)

The private `libAncHumanPreviewBokeh.so` request ABI is now mapped from
`com.xiaomi.plugin.singlebokehpreview.so` (`SingleBokehPreviewPlugin::LibInit`
and `LibProcessYuvBuffer`):

```text
api = [_anc_human_effects_preview_bokeh_get_api_impl(buf, 0x50, 2, 4, 0x10c2)]
  slot 0: set_log_level(int)
  slot 1: get_version(char*)
  slot 2: create_handle(void** out, Config*)
  slot 7: process(handle, AncHumPreviewBokehProcessFrame* in,
                  AncHumPreviewBokehProcessFrame* out)
```

`Config` is 0x50 bytes: `+0x00 frame_request_type`, `+0x04 power_mode`,
`+0x08 cache_path`, `+0x10 native_lib_path`, `+0x18 models_buf`,
`+0x20 models_size`, `+0x28 models_path`, `+0x30 width`, `+0x34 height`,
`+0x38 camera_type`, `+0x3c is_10bit_data`, `+0x40 config_bin_path`,
`+0x48 config_bin_size`.

`AncHumPreviewBokehProcessFrame` is 0x58 bytes: `+0x00 camera_type`,
`+0x04 request_type`, `+0x08 format (0x101 = NV12)`, `+0x0c store_mode`,
`+0x20 width`, `+0x24 height`, `+0x28 orientation`, `+0x2c stride`,
`+0x30 scanline`, `+0x38 plane0`, `+0x40 plane0_size`, `+0x48 plane1`,
`+0x50 plane1_size`. The earlier `cv::Mat` hypothesis was wrong; the engine
takes this flat frame struct with a handle from `create_handle`.

With the 18 Pro Max `bokehmodel.data` and `previewbokeh_config.bin` staged
under `/data/local/tmp`, the engine now creates a handle and processes a
1920x1080 NV12 frame on the 14 Ultra:

```text
getapi=0
initcfg=0
cfg req=2 ... camera=2 w=1920 h=1080
create_handle=0 handle=0xb400007b960a57f0
process=0 out w=1920 h=1080
```

However inference does not run: `backendGetBuildId failed with code: 10000`,
then `[fsp] unknown type 5` and `LoadModels error`. The 18 Pro Max model pack
is compiled for a different NPU generation:

```text
"default_device": "htp-v85"   (x2)
min_arch=85                   (x4)
soc_type=SM8975               (x4)
```

The 14 Ultra is `SM8650` / `pineapple` with HTP v75, so the 18 Pro Max
monocular depth model cannot execute on it. The processed frame is effectively
an NV12 round-trip (PSNR y:64.0, u:48.8, v:54.7 dB vs input), not bokeh.
The 18 Pro Max capture also ships Qualcomm `Jiigan_bokeh_snapshot_libProxyAlgo`
and has no `libmiphone_capture_mdbokeh.so`, unlike the 14 Ultra, so its saved
portrait bokeh path is tied to the newer SoC as well.

Consequence: bit-exact 1:1 with the 18 Pro Max Original portrait is not
achievable on 14 Ultra hardware. The closest software approximation remains
the 14 Ultra Master depth bokeh plus the exact 18 Pro Max Original LUT.

### 18 Pro Max capture bokeh path (2026-11-24)

The saved-photo path on the 18 Pro Max is different from the 14 Ultra:

```text
libmialgoengine.so (madrid, vendor/lib64)
  -> Jiigan_bokeh_snapshot_libProxyAlgo.so
  -> libanc_single_bokeh.so
  -> singlebokeh_mecp.bin + sg_depth_model/sg_seg_model/sg_head_model
```

The 14 Ultra has no `Jiigan_bokeh_snapshot_libProxyAlgo.so`; its `libmialgoengine`
only registers `com.xiaomi.plugin.capbokeh`, which is the
`libmiphone_capture_mdbokeh.so` path already used by build 338.

The 18 Pro Max capture engine itself is portable enough to run on the 14 Ultra.
`sg_single_bokeh_get_api_impl(buf, 0x38, "3.2.0_2025101514")` returns seven
functions; slot 0 creates a handle, slot 2 initializes it, slot 3 processes:

```text
get_api=0
create=0 handle=0xb40000720ee7ee90
init=0
```

`slot 3` takes `(handle, in, params, out)` where `in/out` are
`{int count; Item* items;}` and each item is 0x48 bytes:
`+0x00 orientation`, `+0x04 format`, `+0x08 width`, `+0x0c height`,
`+0x10 plane0`, `+0x18 plane1`, `+0x30 stride`, `+0x40 scanline`.
The init config is a 0x50-byte struct with `+0x00 config_dir`,
`+0x18 config_buf`, `+0x20 config_size` and `+0x2c flag`, receiving
`singlebokeh_mecp.bin` (77,161 bytes).

Processing still fails on the 14 Ultra because the capture models are compiled
for the 18 Pro Max NPU:

```text
[model info] ... backend: QNN (ver: 2.47.4), device: HTP_V85
sg_depth_model: "default_device": "htp-v85", soc_type=SM8975, min_arch=85
sg_seg_model:   "default_device": "htp-v85", soc_type=SM8975, min_arch=85
sg_head_model:  contains V85
```

The 14 Ultra is SM8650/HTP v75, so the 18 Pro Max depth, segmentation and head
matting models cannot execute. The capture engine returns `3011001` and reports
`invaild input` once model inference is required.

Consequence: both the 18 Pro Max preview engine (`libAncHumanPreviewBokeh`) and
the saved-photo engine (`libanc_single_bokeh` + Jiigan proxy) depend on
HTP v85 models. If the 18 Pro Max Original style uses this AI capture path,
bit-exact 1:1 is not achievable on 14 Ultra hardware. If it instead uses the
non-AI Mage capture path, the remaining difference is parameters plus the
already-ported Original LUT; that still needs a matched 18 Pro Max capture to
determine.

Pipeline evidence narrows the ambiguity: the 18 Pro Max `dualbokehsnapshot.json`
contains a single `com.xiaomi.plugin.capbokeh` node (`CapbokehInstance0`) and
`bokehsettings.json` sets `stylizationTypeMask = 14`, so Original is processed
by the same bokeh node as the other styles. The 18 Pro Max `libmialgoengine`
implements that node through the Jiigan proxy and `libanc_single_bokeh`, so the
saved portrait uses the HTP v85 AI depth/segmentation models, not the 14 Ultra
Mage mdbokeh path. This makes exact 1:1 on 14 Ultra hardware very unlikely; a
matched 18 Pro Max capture is still needed to confirm the final rendering.

### Can the 18 Pro Max AI models run on 14 Ultra? (2026-11-24)

The shipped model packs are not portable. `sg_depth_model` is a 12.8 MB
`model.bin` referenced from JSON as `"backend": "qnn"`,
`"default_device": "htp-v85"`, `"soc_type": "SM8975"`, `"min_arch": 85`.
The pack contains no ONNX protobuf (`ir_version` is absent); the only `onnx`
strings are the training/build paths. `libanc_single_bokeh` supports ONNX,
IREE, SNPE and other backends, but this camera ships only the compiled QNN
HTP v85 variant.

Recompiling for HTP v75 would need the original ONNX graph plus calibration
data and the Qualcomm QNN SDK; none of that is in the 18 Pro Max dump. Feeding
the v85 context binary to the 14 Ultra's v75 HTP cannot work.

Practical fallback options, in order of visual closeness:

1. Keep the 18 Pro Max renderer (`libanc_single_bokeh` + `singlebokeh_mecp.bin`)
   and feed it the 14 Ultra's real portrait depth map instead of the AI depth
   model. This preserves the 18 PM bokeh shape/aperture response, but needs
   the process input layout to be reversed far enough to supply depth.
2. Use the 14 Ultra's own v75 AI single-bokeh engine
   (`libanc_single_bokeh.so`, API `get_api(buf, 0x30, 3, 0, 0)`) with the
   18 Pro Max LUT and aperture parameters. This definitely runs on the 14U
   NPU, but its segmentation/depth model is the older Xiaomi model.
3. Port the 18 PM aperture/blur parameters into the 14 Ultra Mage mdbokeh path
   already used by build 338, keeping the exact Original LUT.

All three still need a matched 18 Pro Max Original capture to judge closeness.

### 14 Ultra already has an AI single-bokeh stack (2026-11-24)

The 14 Ultra `/odm/etc/camera` ships its own AI single-bokeh assets, separate
from the Mage/mdbokeh path:

```text
intsense_config_singlebokeh.bin   (9.9K, header contains "8650")
sg_depth_model                    (18M, strings V73/v75)
sg_seg_model                      (1.4M)
sg_head_model                     (7.2M)
sg_pre_model                      (1.7M)
sg_*_cache / sg_*_policy
```

The 14 Ultra `libanc_single_bokeh.so` exposes the older v3 API:

```text
sg_single_bokeh_get_api_impl(api, 0x30, 3, 0, 0)
  slot0 create(void** handle)
  slot1 destroy(handle)
  slot2 init(handle, Config*)
  slot3 process(handle, WSGImage* in, Params*, WSGImage* out)
  slot4 get_mask(handle, Mask*)
  slot5 get_version(handle, char**)
```

`Config` (built by `FrontBokehPlugin::LibInit`, 0x50 bytes):
`+0x00 config_dir`, `+0x18 config_buf`, `+0x20 config_size`,
`+0x28 config_buf`, `+0x30 config_size`.

`WSGImage` (0x48 bytes): `+0x00 rotation`, `+0x04 format` (0=NV12, 1=P010),
`+0x08 width`, `+0x0c height`, `+0x10 plane0`, `+0x18 plane1`,
`+0x30 stride`, `+0x40 scanline`. `Params+0x00` is `fNumber`.

This is the practical route to a genuine AI bokeh on 14 Ultra hardware: run
the 14 Ultra v3 engine with its own SM8650/HTP v75 `sg_*` models, then apply
the 18 Pro Max Original LUT on top. A test harness is built at
`/tmp/opencode/anc-front/front_test.c`; it was not run because the 14 Ultra
disconnected (`adb devices` empty) before the push.

### 14 Ultra AI single bokeh runs (2026-11-24)

The v3 engine test now succeeds on the 14 Ultra with the 14 Ultra's own
SM8650/HTP v75 models:

```text
get_api=0
create=0
init=0
process=0
wrote out.nv12
```

Input: 1920x1080 NV12, `intsense_config_singlebokeh.bin`, and
`/data/local/tmp/anc-front/sg_{depth,seg,head,pre}_{model,policy,cache}`.
Process params: `+0x00 fNumber`, `+0x08 face count`, `+0x10` pointer to
`int[4] {x1,y1,x2,y2}` (corners must satisfy x2>x1 and y2>y1).
The engine loaded all three model contexts (`model info: maps(...),
context(3), runtime(1)`), ran the OpenCL/DNN path, and produced a real
portrait bokeh: background blurred with bokeh circles, subject sharp.
PSNR against the input was y:21.5, u:34.3, v:32.3 dB, confirming a large
localized change rather than a passthrough.

This is the first genuine AI portrait bokeh on 14 Ultra hardware. The next
step is integration: use this engine in the Original post-process (JPEG or
bitmap to NV12, face rect from capture metadata or detector), then apply the
18 Pro Max Original LUT on top.

### Integrated 14U AI bokeh in the module (build 1.1.0-340)

The AI engine is now wired into `EssentialPipelineExtension`:

- `src/main/jniLibs/arm64-v8a/libanc_single_bokeh.so` (prebuilt 14U v3 engine)
- `src/main/assets/anc_bokeh/` (intsense config + sg_depth/seg/head/pre models)
- `src/main/jni/anc_bokeh.cpp` (JNI: ARGB -> NV12, v3 create/init/process,
  NV12 -> ARGB)
- `AncBokehRenderer.java` (extracts the models from the module APK, detects a
  face with `FaceDetector`, calls the engine)
- `EssentialPipelineExtension` runs AI bokeh first, then the exact 18 PM
  Original LUT, then saves with EXIF.

Verified on `1c4e4dec` with build `1.1.0-340`:

```text
AncBokehJNI: AI bokeh engine ready: /data/user/0/com.android.camera/cache/anc_bokeh (10061 byte config)
AncBokehRenderer: AI bokeh init ok
EssentialPipelineExt: AI bokeh ready
AncBokehRenderer: face rect 1656,1861 2090,2431
EssentialPipelineExt: AI bokeh rendered
HyperCameraMod: Original Portrait post-process applied to IMG_20261124_105156.jpg
```

The capture kept its EXIF (Xiaomi 14 Ultra, 2026:11:24 10:51:56, f/1.6,
ISO 640) and was 3072x4096.

Important: the test requires the HAL capture bokeh to be bypassed so the AI
engine gets a sharp source:

```text
persist.vendor.camera.capturemdbokeh.bypass = 1
```

This was set manually. With it set, Master/Leica portraits also lose the
stock HAL bokeh until the property is reset. The next integration step is a
per-style toggle so only Original uses the bypass + AI path.

### Per-style HAL bypass (build 1.1.0-347)

The global property is no longer needed for every portrait style. The camera
app writes `/data/user/0/com.android.camera/files/hcm_bokeh_bypass` from
`ModuleMain.setHalBokehBypass`:

- Original selected -> `1`
- Master / Leica selected -> `0`

A root watcher in `camera-mod-ksu/service.sh` reads that file and runs
`setprop persist.vendor.camera.capturemdbokeh.bypass <0|1>`.

Verified on `1c4e4dec` with build `1.1.0-347`:

```text
HAL bokeh bypass file=true  .../hcm_bokeh_bypass
Original Portrait selected -> AI bokeh, HAL bokeh bypassed
hcm_bokeh_watch.log: setprop 1

HAL bokeh bypass file=false .../hcm_bokeh_bypass
hcm_bokeh_watch.log: setprop 0
```

Original capture `IMG_20261124_111537.jpg` (3072x4096) ran the AI engine and
the 18 PM LUT; Master capture `IMG_20261124_111653.jpg` kept the stock HAL
bokeh and was not post-processed. Side-by-side comparison uploaded.

### Boot watcher verified (build 1.1.0-347)

After a full reboot of `1c4e4dec`:

```text
sys.boot_completed=1
busybox sh /data/adb/modules/hypercameramod/service.sh   (running)
```

Camera opened in Master portrait: signal file `0`, property `0`.
Selected Original: signal file `1`, property `1`.

```text
HAL bokeh bypass file=false .../hcm_bokeh_bypass
HAL bokeh bypass file=true  .../hcm_bokeh_bypass
Original Portrait selected -> AI bokeh, HAL bokeh bypassed
```

So the per-style watcher survives reboot and works without the manual
`/data/local/tmp` watcher.

Remaining validation is user-supplied: a real-person Original portrait and a
matched 18 Pro Max Original capture (or the 18 PM device `edc1e967`). The test
phone is on a stand aimed at a laptop display, so the current test subject is
a portrait shown on that display, not a person in the room.

### Real-person Original vs Master (2026-11-24)

With the phone aimed at a person (not a screen), build `1.1.0-347`:

```text
Original: IMG_20261124_112515.jpg
  face rect 1844,845 3072,2459
  AncBokehRenderer: AI bokeh rendered
  Original Portrait post-process applied
  f/1.8, ISO 640, 3072x4096

Master: IMG_20261124_112620.jpg
  no AncBokeh / no Original post-process
  f/1.8, ISO 640, 3072x4096
```

The Original capture shows the AI bokeh on the jacket/window/bars with the
18 PM LUT; the Master capture keeps the stock rendering. Side-by-side uploaded
to the Discord thread.

### Exact 18 PM model port attempt (2026-11-24)

Tried to run the 18 Pro Max capture model on 14 Ultra hardware:

1. Patched `sg_depth_model` / `sg_seg_model` metadata in place:
   `htp-v85` -> `htp-v75`, `soc_type=SM8975` -> `SM8650`, `min_arch=85` -> `75`,
   `arch=85` -> `arch=75`.
2. Ran the 18 PM v6 engine (`libanc_single_bokeh.so`) with those models:
   `get_api=0`, `create=0`, `init=0`, `process=3011001`.
   The engine still reported `device: HTP_V85` and failed:
   `QnnDsp: provider isn't initialized`, `backendGetBuildId failed 10000`,
   `Load model failed(1)`.
3. Ran the 18 PM engine with the 14 Ultra's own SM8650 `sg_*` v75 models:
   `init=0`, `process=3011001`, `Load model failed(100)`, no model info.
   The 18 PM engine rejects the older 14U model-pack format.
4. Baseline unpatched 18 PM models: `device: HTP_V85`, same QNN provider
   failure and `Load model failed(1)`.

The HTP arch is embedded in the QNN context binary, not the JSON metadata;
patching the JSON does not change device selection. The model pack contains no
ONNX/DLC variant to recompile for v75. Exact 18 PM model execution therefore
needs the original ONNX graph plus calibration data (or a QNN SDK v75 build),
which are not in the dump.

Working port remains build `1.1.0-347`: 14 Ultra v75 AI engine + exact 18 PM
Original LUT, with per-style HAL bypass.

### 18 PM Original beauty config port (2026-11-24)

The 18 PM `RearPortraitOriginal` skin-smoothing value is `10`; the 14U
`RearPortrait` values were `20` and `0`. Ported the 18 PM value by patching
`beauty_config_gl.json` (both regions, `Val` 20/0 -> 10) and bind-mounting it
over `/odm/etc/camera/beauty_config_gl.json` from the KSU module:

```text
camera-mod-ksu/data/odm/etc/camera/beauty_config_gl.json
camera-mod-ksu/service.sh  -> mount --bind ... /odm/etc/camera/beauty_config_gl.json
```

Verified after reboot:

```text
/odm/etc/camera/beauty_config_gl.json == module beauty_config_gl.json
md5 ab6442eece3ac2c8f50ba9f696b01e4e
persist.vendor.camera.capturemdbokeh.bypass=1
busybox sh /data/adb/modules/hypercameramod/service.sh
```

Clean capture after reboot: `Original aperture 2.8 for 35mm`, `AI bokeh
rendered`, post-process applied to `IMG_20261124_121616.jpg`.

Caveat: because the module maps Original to Master in the HAL, the patched
`RearPortrait` value also applies to Master/Leica. A per-style variant needs a
capture-only stylizationType=2 path or a second config.
