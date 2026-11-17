# Original Portrait RE Report: libAncHumanPreviewBokeh.so Analysis

**Target**: Xiaomi 14 Ultra (aurora) - Camera 6.7.000280.0, HyperOS 3.0.304.0.WNAMIXM
**Donor**: Xiaomi 18 Pro Max (madrid) / 17 Ultra (nezha)
**Analysis Date**: 2026-11-17
**Analyst**: REA/Ghidra 12.2 static analysis

---

## 1. Executive Summary

The **Original Portrait** style on 17 Ultra/18 Pro Max is implemented by `libAncHumanPreviewBokeh.so` (ARM64, ~8.7 MB), a **MiaPluginModule**-compatible library that implements a **10-function vtable interface**. This library runs on the **application processor (CPU)**, not on the DSP.

On **14 Ultra (aurora)**, the preview bokeh engine is `libmialgo_mc_bokeh_cdsp_skel.so` (Hexagon DSP, ~200 KB), a fundamentally different architecture that **does not implement the MiaPluginModule interface** required for Original Portrait's algorithm-up SAT preview session.

**Root Cause of Failure**: When Camera app requests Original Portrait (`stylizationType=171, filterId=2`), the HAL attempts to start an **algorithm-up SAT preview session** that expects the `MiaPluginModule` interface. Aurora's DSP engine cannot satisfy this protocol, causing the preview frame callback to never return → semaphore timeout (5780 ms) → preview collapse → provider recovery.

---

## 2. libAncHumanPreviewBokeh.so Interface Analysis (18 Pro Max / madrid)

### 2.1 Exported Symbols

| Symbol | Address | Type | Description |
|--------|---------|------|-------------|
| `_anc_human_effects_preview_bokeh_get_api_impl` | `0x3effb8` | **Primary entry point** | Populates vtable with 10 function pointers |
| `_anc_human_effects_preview_bokeh_init_default_config` | `0x3f137c` | Secondary entry | Zero-initializes config struct (version check only) |

### 2.2 VTable Structure (MiaPluginModule Interface)

The `get_api_impl` function populates a caller-provided array of 10 function pointers (0x50 bytes = 10 × 8):

| Index | Address | Function | Likely Role |
|-------|---------|----------|-------------|
| 0 | `0x3f00f8` | `FUN_003f00f8` | **initialize** - plugin init, loads config |
| 1 | `0x3f0400` | `LAB_003f0400` | **isEnabled** - query if plugin active |
| 2 | `0x3f0450` | `LAB_003f0450` | **processRequest** - main frame processing |
| 3 | `0x3f0898` | `FUN_003f0898` | **setParameter** - configure bokeh params |
| 4 | `0x3f09f8` | `LAB_003f09f8` | **flushRequest** - pipeline flush |
| 5 | `0x3f0c94` | `FUN_003f0c94` | **getParameter** - read back params |
| 6 | `0x3f0dd0` | `LAB_003f0dd0` | Unknown (destroy?) |
| 7 | `0x3f1064` | `LAB_003f1064` | Unknown |
| 8 | `0x3f1290` | `LAB_003f1290` | Unknown |
| 9 | `0x3f12b4` | `LAB_003f12b4` | Unknown |

**Version Check**: Caller must pass `param_2 == 0x50` (struct size), `param_3 == 2` (major), `param_4 == 4` (minor). Mismatch logs error but continues.

### 2.3 Key Function Analysis

#### 2.3.1 initialize (`FUN_003f00f8`, 744 bytes)
- Validates magic: `*(short*)(param_1 + 0x6a) == 0xB156` (`-0x4EAA`)
- Decrypts/constructs string: `"debug.cube.review.bokeh"` (XOR-obfuscated)
- Calls `FUN_00874860` (logging/registration) with the string
- Calls `FUN_007FED74` with `param_1` (initialization callback)

#### 2.3.2 setParameter (`FUN_003f0898`, 352 bytes)
Handles tag-based parameter setting. Tag range: `0x1000D` to `0x1001F` (validated by `param_2 - 0x1000D < 0xFFFFFFFFFFFFFFF3`).

Parameter table at `PTR_s_previewbokeh_f_number_00945388` / `DAT_00945390`:

| Tag (hex) | Tag (dec) | Parameter Name | Type | Config Offset |
|-----------|-----------|----------------|------|---------------|
| `0x10000` | 65536 | `previewbokeh_f_number` | float | `+0x424` / `+0x4B8` (auto) |
| `0x10001` | 65537 | `previewbokeh_focus_rect` | float[4] (rect) | Focus config array |
| `0x10002` | 65538 | `previewbokeh_bokeh_style` | uint32 | `+0x4B0` (0-4) |
| `0x10003` | 65539 | `previewbokeh_af_code` | uint32 | `+0x4A8` |
| `0x10004` | 65540 | `previewbokeh_zoom_rate` | float | `+0x448` |
| `0x10005` | 65541 | `previewbokeh_camera_role` | uint32 | `+0x44C` (0-5) |
| `0x10006` | 65542 | `previewbokeh_focal_range` | uint32 | `+0x4AC` |
| `0x10007` | 65543 | `previewbokeh_is_auto_f_number` | bool | `+0x4B4` |
| `0x10008` | 65544 | `previewbokeh_af_code_bypass` | uint32 | `+0x4BC` (get-only) |
| `0x10009` | 65545 | `previewbokeh_is_static_scene` | bool | `+0x4C1` |
| `0x1000A` | 65546 | `previewbokeh_motion_speed` | float | `+0x4C4` |
| `0x1000B` | 65547 | `previewbokeh_bypass` | bool | `+0x4C8` |
| `0x1000C` | 65548 | `previewbokeh_batch_params` | struct[] | Batch set multiple |

**Key Config Offsets** (in render_config at `param_1 + 0x40` → `+0x98`):
- `0x424`: f_number (manual)
- `0x4B8`: f_number (auto, when `+0x4B4 != 0`)
- `0x4B0`: bokeh_style (0-4)
- `0x4A8`: af_code
- `0x448`: zoom_rate
- `0x44C`: camera_role (0-5)
- `0x4AC`: focal_range
- `0x4B4`: is_auto_f_number flag
- `0x4BC`: af_code_bypass
- `0x4C1`: is_static_scene
- `0x4C4`: motion_speed
- `0x4C8`: bypass flag

#### 2.3.3 getParameter (`FUN_003f0c94`, 316 bytes)
Mirror of setParameter, reads from same config offsets.

#### 2.3.4 Core Parameter Handler (`FUN_003f5BFC`, 3836 bytes)
Massive switch on tag (`param_2 & 0xFFFF`). Handles both get (`param_6 & 1 == 0`) and set (`param_6 & 1 == 1`) paths. Validates `*(char*)(param_1 + 0x68)` for "pipeline active" before allowing writes.

#### 2.3.5 Batch Parameter Set (tag `0x1000C`)
Tag `0x1000C` takes an array of (tag, value) pairs at `param_4[2]` with count `param_4[4]`. Iterates and calls `FUN_003F5BFC` for each. Uses obfuscated format strings for logging.

---

## 3. 17 Ultra (nezha) Comparison

**SHA256**: `a94a36a12df7f29edb5cbd5e94c9d565567b84c0b64b3e6bcaf8f810e6d4db93` (different from madrid's `70c05a2894aa5aa0ab1c1b86c2e1b63b1103bb8facdb67d8ae3b17b8c2dd42c2`)

### 3.1 Differences

| Aspect | 18 Pro Max (madrid) | 17 Ultra (nezha) |
|--------|---------------------|------------------|
| `get_api_impl` address | `0x3EFFB8` | `0x358560` |
| Version check | major=2, minor=4 | major=0, minor=4 |
| SDK version string | `2.4.0x10` (2.4.16) | `0.4.0x2790` (0.4.10128) |
| VTable layout | Identical (10 entries) | Identical (10 entries) |
| Parameter tags | Same (`0x10000`-`0x1000C`) | Same |
| Config offsets | Same | Same |

**Conclusion**: The **interface is stable** across 17U/18PM. The version bump (0.4 → 2.4) likely reflects HAL/API evolution. The core parameter set and vtable layout are identical.

---

## 4. Aurora (14 Ultra) Preview Bokeh Engine

### 4.1 Library: `libmialgo_mc_bokeh_cdsp_skel.so`
- **Path**: `/odm/lib/rfsa/adsp/libmialgo_mc_bokeh_cdsp_skel.so`
- **Architecture**: **Hexagon DSP** (not ARM64)
- **Size**: ~200 KB (195 procedures vs 8505 in AncHuman)
- **SHA256**: `b2bf922977fb49252ca7bddafc65ed3489f5ae8ad886250ee9e229b844fba871`

### 4.2 Exported Interface (DSP SKEL API)

| Function | Address | Description |
|----------|---------|-------------|
| `mialgo_mc_bokeh_cdsp_open` | `0x4BCE0` | Open/initialize DSP session |
| `mialgo_mc_bokeh_cdsp_close` | `0x4BD60` | Close DSP session |
| `mialgo_mc_bokeh_cdsp_call` | `0x4BD80` | Invoke DSP processing |
| `mialgo_mc_bokeh_cdsp_skel_handle_invoke` | `0x11B10` | Handle invocation callback |

### 4.3 Internal DSP Implementations (Not Exported)

| Function | Description |
|----------|-------------|
| `BokehBlury5DCallback` / `Impl` / `Interface` | 5D blur kernel |
| `BokehBMFuncImpl` / `Interface` | Block matching? |
| `BokehDTGuideFilterImpl` / `Interface` | Domain transform guided filter |
| `BokehEffectGaussBlurInterface` | Gaussian blur |
| `BokehGatherBlurFuncInterface` | Gather-based blur |
| `BokehLUTFuncImpl` / `Interface` | Lookup table bokeh |
| `BokehPreviewFuncInterface` | Preview path |
| `BokehHashFuncNoMaskImpl` / `MaskImpl` | Hash-based (no mask / with mask) |
| `BokehHashOrderedCoordFuncInterface` | Ordered coordinate hash |
| `MialgoCdspBokehImgFusionImpl` | Image fusion |
| `MialgoCdspBokehMaskBinaryImpl` | Binary mask generation |
| `MialgoCdspBokehDividAndImgFusionImpl` | Divide + fuse |

### 4.4 Critical Difference

| Aspect | libAncHumanPreviewBokeh (17U/18PM) | libmialgo_mc_bokeh_cdsp_skel (aurora) |
|--------|-----------------------------------|---------------------------------------|
| **ISA** | ARM64 (CPU) | Hexagon (DSP) |
| **Interface** | MiaPluginModule (10-fn vtable) | SKEL (open/close/call) |
| **Session Type** | Algorithm-up SAT preview | Standard DSP offload |
| **Parameter Model** | Tag-based (0x10000-0x1000C) | Opaque DSP buffers |
| **Human-Aware** | Yes (focus_rect, camera_role) | No evidence |
| **Legend/M10R Integration** | Yes (camera_role 0-5) | No |

**The aurora DSP engine does not implement `MiaPluginModule`**. It cannot participate in the algorithm-up SAT preview session that Original Portrait requires.

---

## 5. Session Parameter Flow (App → HAL → Plugin)

### 5.1 App-Side (Camera 6.8)
```java
// ComponentConfigLegendary (p613r2/B.java)
com.xiaomi.sessionparams.stylizationType = 171;  // MODE_PORTRAIT
com.xiaomi.sessionparams.filterId = 2;           // STYLE_ORIGINAL
com.xiaomi.sessionparams.legendMode = 0;         // Not Legend mode
com.xiaomi.sessionparams.BokehMode = "original"; // Inferred
```

### 5.2 HAL-Side (camera.qcom.core.so)
- Reads `legendMode` (MiMetadata tag 508) → `oemIsLegendMode = (legendMode - 1) < 2`
- For Original: `legendMode = 0`, `oemIsLegendMode = false`
- Selects preview pipeline with `libAncHumanPreviewBokeh.so` plugin

### 5.3 MiAlgo Engine (libmialgoengine.so)
- Pipeline: `legendsnapshot.json` (capture) / preview equivalent
- Plugin: `com.xiaomi.plugin.mileicafilter` → `LeicaFilterInstance0`
- But for **preview**, the SAT session directly loads `libAncHumanPreviewBokeh.so`

### 5.4 Metadata Tags (libmetadataHelper.so)
| Tag | Index | Name | Used By |
|-----|-------|------|---------|
| 29 (0x1D) | `stylizationType` | byte | `MiLeicaFilterPlugin` |
| 508 (0x1FC) | `legendMode` | byte | Core HAL, `MiLeicaFilterPlugin` |

### 5.5 SAT Preview Session Protocol
1. HAL creates **algorithm-up preview session** (special mode)
2. Loads `libAncHumanPreviewBokeh.so` via `get_api_impl`
3. Calls `initialize` with config struct (0x50 bytes)
4. For each preview frame:
   - `setParameter` with runtime params (f_number, focus_rect, zoom_rate, etc.)
   - `processRequest` with input buffers → output buffer
   - `getParameter` for feedback (af_code, etc.)
5. On stream change: `flushRequest`
6. On close: `destroy` (vtable index 6)

---

## 6. Original Portrait "Look" Specification (To Be Measured)

### 6.1 Required Donor Captures (17U/18PM)
| Capture | Purpose |
|---------|---------|
| Master Portrait (same scene) | Baseline |
| Original Portrait (same scene) | Target look |
| Multiple apertures (f/1.4-f/16) | Falloff curve |
| Multiple focal lengths (35mm, 75mm) | Disk scaling |
| Edge cases (hair, glasses, transparency) | Edge handling |

### 6.2 Quantitative Parameters to Extract

| Parameter | Method | Target |
|-----------|--------|--------|
| **Color Profile** | Master vs Original ΔE in LAB | LUT or parametric (libm9base?) |
| **Bokeh Disk Shape** | PSF analysis of point lights | Polygon sides, rotation |
| **Falloff Curve** | Radial blur vs distance | Mathematical model |
| **Catseye Effect** | Off-axis point light shape | Vignetting function |
| **Edge Handling** | Transition width at subject boundary | Feathering radius |
| **Depth Weighting** | Blur vs depth map value | Compositing equation |
| **Highlight Handling** | Specular blowout behavior | Tone mapping curve |

### 6.3 Parameter Mapping (from RE)

| Original Param | Tag | Software Equivalent |
|----------------|-----|---------------------|
| Aperture (f-number) | `0x10000` | `f_number` float |
| Focus Region | `0x10001` | `focus_rect` [x,y,w,h] normalized |
| Bokeh Style | `0x10002` | 0=Natural, 1=Smooth, 2=Swirly, 3=Catseye, 4=Custom? |
| Zoom Rate | `0x10004` | Focal length equivalent |
| Camera Role | `0x10005` | 0=Main, 1=Tele, 2=Ultra-wide, etc. |
| Focal Range | `0x10006` | Near/far focus range |
| Auto Aperture | `0x10007` | Enable auto f-number |
| Static Scene | `0x10009` | Optimize for static |

---

## 7. Depth Map Format on Aurora

### 7.1 CameraCharacteristics Query Needed
```java
// Check at runtime:
CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
// Must contain: REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
```

### 7.2 Expected Formats (from HAL docs)
| Format | Resolution | Description |
|--------|------------|-------------|
| `JPEG_DEPTH_MAP` | Same as JPEG | Embedded in JPEG |
| `DEPTH_POINT_CLOUD` | Variable | XYZ point cloud |
| `DEPTH_JPEG` | Preview res | Compressed depth |
| Vendor: `xiaomi.camera.depth` | Proprietary | Confidence + depth |
| Vendor: `xiaomi.camera.bokeh_data` | Proprietary | Bokeh-specific metadata |

### 7.3 Required for Software Implementation
- **Depth map**: 16-bit or float, same aspect as main image
- **Confidence map**: 8-bit per pixel (0-255)
- **Segmentation mask**: Human/background (if available)

---

## 8. Software Implementation Design

### 8.1 Architecture: Post-Capture Processing (Approach A)

```
Portrait Capture (Master style, filterId=1)
         │
         ▼
CaptureResult callback (ModuleMain)
         │
         ▼
Extract: JPEG + Depth Map + Metadata
         │
         ▼
EssentialPipeline.handlePortraitOriginal(jpeg, depth, metadata)
         │
         ▼
EssentialService (private process)
         │
         ├── libm9base.so parametric filter (Original color profile)
         ├── Custom bokeh renderer (OpenGL compute shader)
         │      ├── Depth-guided circle-of-confusion
         │      ├── Disk shape from params (f_number, focal_len)
         │      ├── Catseye/vignetting model
         │      └── Edge-preserving compositing (confidence-weighted)
         └── Optional: libmialgo_monopan.so (film grain)
         │
         ▼
Processed JPEG → replace output / save as "Original"
```

### 8.2 Bokeh Renderer (OpenGL Compute Shader)

```glsl
// Pseudocode for compute shader
layout(local_size_x = 16, local_size_y = 16) in;

uniform sampler2D uDepthMap;      // Normalized 0-1
uniform sampler2D uConfidenceMap; // 0-1
uniform sampler2D uSourceImage;   // RGBA
uniform float uFNumber;           // Aperture (1.4-16)
uniform float uFocalLength;       // mm equivalent (35, 75)
uniform float uFocusDistance;     // From focus_rect
uniform vec2 uFocusRect;          // Normalized [x,y,w,h]
uniform int uBokehStyle;          // 0-4
uniform bool uIsStaticScene;

float circleOfConfusion(float depth) {
    // Thin lens formula: CoC = |A * (1/focusDist - 1/depth)|
    // A = aperture_area = (focal_length / f_number)^2 * pi/4
    float aperture = uFocalLength / uFNumber;
    float CoC = abs(aperture * (1.0/uFocusDistance - 1.0/depth));
    return clamp(CoC, 0.0, MAX_COC);
}

vec3 bokehKernel(vec2 uv, float CoC, float confidence) {
    // Disk with catseye vignetting
    float r = CoC * MAX_RADIUS;
    vec2 offset = uv - gl_GlobalInvocationID.xy;
    float dist = length(offset);
    
    // Catseye: vignette toward edges
    float vignette = 1.0 - smoothstep(0.7, 1.0, dist / r);
    
    // Polygon bokeh (style-dependent)
    float sides = (uBokehStyle == 3) ? 5.0 : 7.0; // Catseye = pentagon?
    float angle = atan(offset.y, offset.x);
    float polygonR = r / cos(fmod(angle, 2*PI/sides) - PI/sides);
    
    float weight = smoothstep(polygonR*0.9, polygonR, dist) * vignette;
    return texture(uSourceImage, uv).rgb * weight * confidence;
}

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    float depth = texture(uDepthMap, pos).r;
    float confidence = texture(uConfidenceMap, pos).r;
    
    if (depth <= 0.0) { output = texture(uSourceImage, pos); return; }
    
    float CoC = circleOfConfusion(depth);
    if (CoC < MIN_COC) { output = texture(uSourceImage, pos); return; }
    
    // Gather samples within CoC radius
    vec3 sum = vec3(0);
    float totalWeight = 0;
    int radius = int(CoC * MAX_RADIUS);
    
    for (int dy = -radius; dy <= radius; dy++) {
        for (int dx = -radius; dx <= radius; dx++) {
            vec2 samplePos = pos + vec2(dx, dy);
            vec3 sample = bokehKernel(samplePos, CoC, confidence);
            // ... accumulate
        }
    }
    
    output = sum / totalWeight;
}
```

### 8.3 Color Profile Application

**Option 1: LUT (if simple)**
- Extract 3D LUT (17x17x17 or 33x33x33) from Master→Original pairs
- Apply via `libm9base.so` parametric filter or custom shader

**Option 2: Parametric (if complex)**
- `libm9base.so` exposes parametric filter (contrast, saturation, hue shift, tone curve)
- Fit parameters to match donor captures

**Option 3: Neural Style Transfer (if needed)**
- `libmialgo_styletrans.so` + `.minn` models (M9/M10R)
- But Original is NOT a Legend style - likely simpler

### 8.4 Integration Points

| Component | File | Change |
|-----------|------|--------|
| Settings | `preferences.xml` | Add `original_portrait_enabled` switch |
| ModuleMain | `ModuleMain.kt` | Hook portrait capture callback |
| EssentialPipeline | `EssentialPipeline.kt` | Add `handlePortraitOriginal()` |
| EssentialService | `EssentialService.kt` | Add `processPortraitOriginal()` |
| Shaders | `shaders/bokeh.comp` | New compute shader |
| JNI | `native/bokeh_renderer.cpp` | Shader dispatch + buffer mgmt |

---

## 9. Minimal HAL Stub (To Avoid Semaphore Timeout)

If post-capture approach is insufficient and real-time preview is required:

### 9.1 Stub Plugin Interface
Implement minimal `MiaPluginModule` that:
1. `initialize`: Accept config, return success
2. `isEnabled`: Return true when `stylizationType=171 && filterId=2`
3. `processRequest`: **Pass-through** - copy input to output, mark frame done
4. `setParameter`/`getParameter`: No-op (log only)
5. `flushRequest`: No-op
6. `destroy`: Cleanup

### 9.2 Effect
- Preview shows **Master bokeh** (from aurora DSP)
- Capture triggers **software Original** post-processing
- No semaphore timeout (frame callback returns immediately)

### 9.3 Implementation
```c
// Minimal stub (compile as libanc_human_preview_bokeh_stub.so)
uint32_t initialize(void* config, int size, int major, int minor, int patch) {
    if (size == 0x50 && major == 2 && minor == 4) return 0;
    return 0x2EB8F1;
}

uint32_t processRequest(ProcessRequestInfo* info) {
    // Copy input buffer 0 to output buffer 0
    memcpy(info->outputBuffers[0], info->inputBuffers[0], info->bufferSize);
    return 0; // Success - frame done
}
```

---

## 10. Evidence Index

| Evidence ID | Artifact | Analysis |
|-------------|----------|----------|
| `ev_a7b3acc7...` | madrid `libAncHumanPreviewBokeh.so` | `get_api_impl` vtable population |
| `ev_6012269...` | madrid | `initialize` function |
| `ev_357e7b8...` | madrid | `setParameter` function |
| `ev_7e68a17...` | madrid | `getParameter` function |
| `ev_b560180...` | madrid | Core param handler `FUN_003F5BFC` |
| `ev_7ee311c...` | madrid | `init_default_config` |
| `ev_53e0166...` | nezha `libAncHumanPreviewBokeh.so` | `get_api_impl` (version 0.4) |
| `ev_12cebc9...` | aurora `libmialgo_mc_bokeh_cdsp_skel.so` | DSP SKEL exports |

---

## 11. Open Questions / Next Steps

1. **Donor captures needed**: Master vs Original from 17U/18PM (same scene)
2. **Depth format on aurora**: Confirm `JPEG_DEPTH_MAP` availability in Portrait capture
3. **Color profile complexity**: LUT vs parametric vs neural?
4. **Real-time preview requirement**: User expectation?
5. **Gallery integration**: Will software output show "Original" badge?
6. **Bokeh style values**: What do 0-4 map to visually?
7. **Focus rect coordinate space**: Normalized 0-1 or pixel?
8. **Camera role values**: Mapping for aurora's 3 rear cameras?

---

## 12. Appendix: Key Addresses (madrid / 18 Pro Max)

| Symbol | Address | Size |
|--------|---------|------|
| `_anc_human_effects_preview_bokeh_get_api_impl` | `0x3EFFB8` | 320 B |
| `_anc_human_effects_preview_bokeh_init_default_config` | `0x3F137C` | 216 B |
| `FUN_003F00F8` (initialize) | `0x3F00F8` | 744 B |
| `FUN_003F0898` (setParameter) | `0x3F0898` | 352 B |
| `FUN_003F0C94` (getParameter) | `0x3F0C94` | 316 B |
| `FUN_003F5BFC` (core handler) | `0x3F5BFC` | 3836 B |
| Parameter string table | `0x945388` | — |
| Parameter default table | `0x945390` | — |
| Config magic offset | `+0x6A` | `0xB156` |
| Pipeline active flag | `+0x68` | byte |
| Render config pointer | `+0x40` | ptr |
| Render config data | `+0x98` | struct |

---

*End of RE Report*