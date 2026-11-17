# Original Portrait Software Implementation Investigation

**Target**: Xiaomi 14 Ultra (aurora) - Android 16, Camera 6.7.000280.0
**Goal**: Implement "Original" portrait style without native HAL support
**Status**: Research phase - no device testing yet

---

## 1. What is "Original Portrait"?

### 1.1 On supported devices (17 Ultra, 18 Pro Max)
- **HAL library**: `libAncHumanPreviewBokeh.so` (8.7 MB)
- **Pipeline**: Native algorithm-up SAT (Scene Analysis Task) preview session
- **Output**: Distinct bokeh rendering + color profile different from "Master" style
- **Gallery label**: Shows "Original" in portrait metadata

### 1.2 What makes it different from "Master"
| Aspect | Master (default) | Original |
|--------|------------------|----------|
| Bokeh engine | Standard preview bokeh | `libAncHumanPreviewBokeh.so` |
| Preview session | Standard | Algorithm-up SAT session |
| Color profile | Standard portrait | Unique Original color science |
| Depth processing | Basic | Advanced human-aware segmentation |

### 1.3 Session parameters sent to HAL
```java
com.xiaomi.sessionparams.stylizationType = 171 (MODE_PORTRAIT)
com.xiaomi.sessionparams.filterId = 2 (STYLE_ORIGINAL)
com.xiaomi.sessionparams.legendMode = 0 (not a Legend mode)
com.xiaomi.sessionparams.BokehMode = "original" (inferred)
```

---

## 2. Why it fails on 14 Ultra (aurora)

### 2.1 Missing HAL component
| Device | Preview Bokeh Engine | Original Portrait Support |
|--------|---------------------|--------------------------|
| 17 Ultra | `libAncHumanPreviewBokeh.so` | ✅ Native |
| 18 Pro Max | `libAncHumanPreviewBokeh.so` | ✅ Native |
| 14 Ultra (aurora) | `libmialgo_mc_bokeh_cdsp_skel.so` (DSP) | ❌ **Missing** |

### 2.2 Failure mechanism
1. Camera requests Original portrait → sends `stylizationType=171, filterId=2`
2. HAL starts **algorithm-up SAT preview session** (special preview mode)
3. Preview bokeh engine (`libmialgo_mc_bokeh_cdsp_skel.so` on DSP) **never returns a frame**
4. Camera waits on semaphore: `ChiX WaitForSemaphore() ... timed out` (5780 ms)
5. Preview drops to ~0.1 fps
6. Vendor camera provider aborts: `ExtensionModule::TriggerRecovery`

### 2.3 Root cause
The DSP-based preview bokeh engine on aurora (`libmialgo_mc_bokeh_cdsp_skel.so`) doesn't implement the SAT preview session protocol that Original requires. It's a fundamentally different architecture.

---

## 3. Software Implementation Approaches

### Approach A: App-side post-capture processing (RECOMMENDED)
**Concept**: Capture with Master style, apply Original look in post-processing

| Pros | Cons |
|------|------|
| Works on any device | Not real-time preview |
| No HAL modification needed | Requires depth map access |
| Can use Moment App / ChiX pipeline | Quality may not match native |
| Preview works normally (Master) | Extra processing latency |

**Implementation path**:
1. Hook portrait capture with Master style
2. Extract depth map + JPEG from capture result
3. Apply Original-style bokeh rendering + color profile in software
4. Replace output JPEG

**Required components**:
- Depth map extraction (from `CaptureResult.JPEG_DEPTH_MAP` or vendor metadata)
- Bokeh rendering shader (simulate `libAncHumanPreviewBokeh.so` behavior)
- Original color profile (LUT or parametric filter)
- Integration with existing Moment App pipeline (`EssentialPipeline`)

### Approach B: HAL plugin injection (ChiX-style)
**Concept**: Inject a custom CHI plugin that implements Original portrait

| Pros | Cons |
|------|------|
| Real-time preview possible | Extremely complex |
| Native integration | Requires deep CHI knowledge |
| Matches native behavior | High crash risk |

**Feasibility**: LOW - CHI plugin development requires:
- `MiaPluginModule` interface implementation
- Graph JSON definition (`original_portrait_snapshot.json`)
- Vendor library dependencies (`libAncHumanPreviewBokeh.so` or equivalent)
- SELinux policy for new plugin

### Approach C: Preview session bypass
**Concept**: Intercept the SAT preview session request, substitute standard preview

| Pros | Cons |
|------|------|
| Keeps native capture pipeline | Preview won't show Original bokeh |
| Capture might work | Semantic mismatch (preview ≠ capture) |

**Feasibility**: MEDIUM - Would need to hook:
- `ICameraDevice::createCaptureSession`
- Session parameter parsing
- Preview stream configuration

### Approach D: Depth map + AI bokeh (modern approach)
**Concept**: Use device NPU/DSP for software bokeh with Original characteristics

| Pros | Cons |
|------|------|
| High quality possible | Requires model training/conversion |
| Real-time potential | NPU access complexity |
| Works on any hardware | Large model size |

**Components needed**:
- Human segmentation model (portrait mattes)
- Depth refinement network
- Bokeh rendering with Original optical characteristics
- QNN/HTP or TFLite delegate for acceleration

---

## 4. Recommended Implementation: Approach A (Post-capture)

### 4.1 Why this approach
- **Lowest risk**: No HAL modification, no preview session interference
- **Reuses existing code**: Moment App pipeline (`EssentialPipeline`/`EssentialService`)
- **Configurable**: User can enable/disable, adjust intensity
- **Testable**: Can compare Master vs Original output side-by-side

### 4.2 Architecture
```
Portrait Capture (Master style)
         │
         ▼
CaptureResult callback (in ModuleMain)
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
         ├── libmialgo_monopan.so (if Monopan-style grain needed)
         └── Custom bokeh renderer (software)
         │
         ▼
Processed JPEG → replace original output
```

### 4.3 Required new components

#### 4.3.1 Original Color Profile
- Extract from 17 Ultra/18 Pro Max captures
- Could be: LUT (like M3/M9), parametric filter params, or both
- **Investigation needed**: Capture comparison on donor device

#### 4.3.2 Software Bokeh Renderer
Options:
1. **OpenGL/Compute shader** - Fast, runs on GPU
2. **CPU reference** - Slow, for validation
3. **NPU-accelerated** - Best quality/speed, complex

Key characteristics of Original bokeh (from docs):
- Human-aware segmentation (`libAncHumanPreviewBokeh.so` name)
- 35mm/75mm equivalent focal lengths (from `legendMode.capsInfo`)
- Variable aperture simulation

#### 4.3.3 Depth Map Access
```java
// In CaptureResult callback:
DepthMap depthMap = result.get(CaptureResult.JPEG_DEPTH_MAP);
// Or vendor-specific:
byte[] depthData = result.get(VendorCaptureResultKeys.DEPTH_DATA);
```

**Investigation needed**: What depth format does aurora provide?

---

## 5. Integration with Existing Module

### 5.1 Settings
Add to preferences (already exists):
```xml
<SwitchPreferenceCompat
    android:key="original_portrait_enabled"
    android:title="Original Portrait Style"
    android:summary="Software implementation (post-capture). Works on 14 Ultra." />
```

### 5.2 ModuleMain hooks needed
```java
// In install() -> Portrait capture hook
private void installOriginalPortraitHook(ClassLoader loader) {
    // Hook capture callback for Portrait mode
    // When style == STYLE_ORIGINAL && originalPortraitSoftwareEnabled()
    //   -> divert to EssentialPipeline
}

// New method
private boolean originalPortraitSoftwareEnabled() {
    // Check preference + property
}
```

### 5.3 EssentialPipeline extension
```java
// New method in EssentialPipeline
public static void handlePortraitOriginal(File jpegFile, DepthMap depthMap, 
                                           CaptureResult metadata) {
    // Bind to EssentialService
    // Send: JPEG + depth + "original_portrait" command
}

// New method in EssentialService
private void processPortraitOriginal(byte[] jpeg, DepthMap depth, 
                                      Map<String, Object> meta) {
    // 1. Apply Original color profile (parametric filter)
    // 2. Render bokeh using depth map
    // 3. Merge + save
}
```

---

## 6. Investigation Tasks (No Device Needed)

### 6.1 Static analysis
- [ ] Analyze `libAncHumanPreviewBokeh.so` from 18 Pro Max dump
  - Exports, symbols, interface
  - Compare with `libmialgo_mc_bokeh_cdsp_skel.so`
- [ ] Extract Original color profile from donor captures
  - Compare Master vs Original JPEGs (same scene)
  - Derive LUT or parametric params
- [ ] Analyze depth map format from aurora
  - Check `CameraCharacteristics` for depth capabilities
  - Review vendor metadata keys

### 6.2 Donor device captures needed
When 17 Ultra/18 Pro Max available:
- [ ] Same scene: Master portrait + Original portrait
- [ ] Extract EXIF differences
- [ ] Measure bokeh disk size, falloff, color fringing
- [ ] Test with various apertures (if capsInfo supports)

### 6.3 Prototype development (offline)
- [ ] Software bokeh renderer prototype (OpenGL compute)
- [ ] Test with synthetic depth maps
- [ ] Integrate with EssentialPipeline framework
- [ ] Benchmark: latency, memory, quality

---

## 7. Device-Dependent Tasks (When Phone Connected)

| Priority | Task | Why |
|----------|------|-----|
| **Critical** | Capture depth map format on aurora | Determines software approach feasibility |
| **Critical** | Test Master portrait capture + callback | Verify hook points work |
| **High** | Compare Master vs Original on donor | Define "Original look" quantitatively |
| **High** | Test EssentialPipeline integration | Validate process isolation works |
| **Medium** | Benchmark software bokeh latency | UX target: <500ms added |
| **Medium** | Test with various lighting/subjects | Robustness |
| **Low** | NPU acceleration attempt | If compute shader too slow |

---

## 8. Open Questions

1. **Depth map availability**: Does aurora provide `JPEG_DEPTH_MAP` in Portrait capture?
2. **Original color profile**: Is it a simple LUT, parametric filter, or complex pipeline?
3. **Bokeh parameters**: What are the exact optical characteristics (disk shape, falloff, catseye)?
4. **Preview requirement**: Do users need real-time Original preview, or is post-capture OK?
5. **Gallery integration**: Will Gallery recognize software Original as "Original" style?

---

## 9. Related Documentation

| Document | Relevance |
|----------|-----------|
| `docs/hal-backport/10-moment-app.md` | Moment App pipeline for post-processing |
| `docs/hal-backport/11-chix-adapter.md` | ChiX wrapper for HAL plugin reference |
| `docs/18-pro-max-hal/01-hal-stack.md` | HAL differences (libAncHumanPreviewBokeh vs libmialgo_mc_bokeh) |
| `docs/18-pro-max-hal/02-legend-m10r.md` | Session parameter flow |
| `README.md` lines 63-67 | Current failure analysis |

---

## 10. Next Steps

1. **Complete static analysis** of donor libraries (offline)
2. **Define Original "look"** quantitatively from donor captures
3. **Prototype software bokeh** with synthetic data
4. **Integrate with EssentialPipeline** framework
5. **Device testing** when phone available

---

*Document created: 2026-11-17*
*Module version: 1.0.0-181*
*Target device: Xiaomi 14 Ultra (aurora), Camera 6.7.000280.0*