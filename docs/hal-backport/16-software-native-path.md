# Software-Only "Close to Native" Path for Aurora

**Status**: Offline specification. Can be implemented in userspace (APK + KSU module) without HAL patching.

**Goal**: Get as close to native M3/M9 and Original Portrait as possible without modifying `camera.qcom.so`.

---

## Gap Analysis: What Native Provides vs. What Software Can Approximate

| Feature | Native (17U/18PM) | Software on Aurora | Gap |
|---------|-------------------|-------------------|-----|
| M3/M9 color grade | `mileicafilter` + `legendST` neural | LUT injection + color matrix | Neural style transfer missing |
| EXIF `legendmode` (0x88b0) | Written by HAL | Written by APK post-capture | HAL doesn't write it |
| Gallery LEICA button | Triggered by EXIF tag | Triggered by EXIF tag (if we write it) | None — can be added |
| Real-time preview bokeh | `libAncHumanPreviewBokeh.so` per-frame | Not possible in userspace | Preview stays unprocessed |
| Post-capture bokeh | SAT pipeline | OpenGL ES 3.1 compute shader | Already working |
| `oemIsLegendMode` flag | HAL computes it | Not available | HAL-only |

---

## Enhancement 1: EXIF `legendmode` Tagging

**Why**: Gallery reads EXIF tag id 34992 (0x88b0), type 1, to show the LEICA button. Without it, M3/M9 photos look normal in Gallery.

**Implementation**: In `EssentialService` or `EssentialPipeline`, after capturing a JPEG with M3/M9, inject the EXIF tag.

```java
// In EssentialService.java (private process, post-capture)
private void injectLegendModeExif(byte[] jpegData, int legendMode) {
    // ExifInterface is available from API 24+
    // Write tag 34992 (0x88b0) as BYTE, count 1, value = legendMode
    // Then re-save the JPEG with updated EXIF
}
```

**Where**: `EssentialPipeline.handleCaptureResult()` — after the JPEG is produced, before returning to Camera app.

**Values**:
- M3 → `legendmode = 2`
- M9 → `legendmode = 1`
- Off → no tag (or 0)

**Verification**: Open Gallery → photo should show LEICA badge/button.

---

## Enhancement 2: Color Profile Matching (M3/M9)

**Why**: LUT injection gives the right hue/saturation but may not match the donor's exact color response (contrast, highlight rolloff, shadow lift).

**Implementation**: Add a parametric color profile stage after the LUT:

```cpp
// ColorProfile.cpp — parametric color adjustment
struct ColorParams {
    float contrast;   // 0.5 - 2.0
    float saturation; // 0.0 - 2.0
    float hue_shift;  // -180 - +180 degrees
    float temp;       // color temperature
    float tint;       // green-magenta shift
    float highlights; // highlight compression
    float shadows;    // shadow lift
};

// M3 profile (approximate, to be measured from donor captures)
static const ColorParams kM3Profile = {
    .contrast = 1.15f,
    .saturation = 0.0f,    // monochrome
    .highlights = 0.9f,
    .shadows = 1.1f,
};

// M9 profile (approximate)
static const ColorParams kM9Profile = {
    .contrast = 1.05f,
    .saturation = 0.85f,   // slightly muted CCD look
    .highlights = 0.85f,
    .shadows = 1.05f,
};
```

**Implementation**: Apply via OpenGL ES 3.1 compute shader (same pipeline as Original Portrait bokeh) or via `libm9base.so`'s existing color matrix functions.

**Measurement needed**: Capture same scene with donor (17U/18PM) and Aurora, compute ΔE in LAB space, adjust params to minimize delta.

---

## Enhancement 3: Preview Bokeh (Software, Real-Time)

**Why**: Native Original Portrait has real-time bokeh in preview. Software version can only do post-capture.

**Implementation**: Use Camera2 `RepeatingRequest` with a software blur pass:

```java
// In Camera2 capture session
CaptureRequest.Builder builder = session.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
// Apply software blur to the output surface
// Use RenderScript or OpenGL ES 3.1 to blur the preview frame
```

**Challenges**:
- Preview frame rate: 30fps target; software blur at full res is too slow
- Resolution: Must blur at preview resolution (typically 720p or 1080p)
- Latency: Must add <33ms per frame

**Pragmatic approach**: Skip preview bokeh; only apply post-capture. The user sees normal preview, gets bokeh JPEG after capture. This matches what most "portrait mode" apps do on mid-range devices.

---

## Enhancement 4: Focus Rect & Depth-Aware Bokeh

**Why**: Native Original Portrait uses `focus_rect` (tag 0x10001) and `camera_role` (tag 0x10005) to direct the bokeh.

**Implementation**: 
1. Hook Camera2 `setRepeatingRequest` to capture the AF focus rect
2. Pass it to the post-capture bokeh renderer
3. Use depth map (from Portrait mode) for accurate foreground/background separation

**Already implemented**: The OpenGL ES 3.1 bokeh renderer (`liboriginal_portrait_bokeh.so`) supports depth-aware compositing with confidence-weighted edge preservation.

---

## Implementation Plan (APK + KSU Module, No HAL Patch)

### Phase 1: EXIF Tagging (Week 1)

| File | Change |
|------|--------|
| `EssentialService.java` | Add `injectLegendModeExif()` after M3/M9 capture |
| `EssentialPipeline.java` | Call EXIF injection in `handleCaptureResult()` |
| `ModuleMain.java` | Track current legend mode (1=M9, 2=M3) per session |

**Verification**: Gallery shows LEICA button on M3/M9 photos.

### Phase 2: Color Profile Matching (Week 2)

| File | Change |
|------|--------|
| `src/main/jni/color_profile.cpp` | New: parametric color adjustment (contrast, saturation, highlights, shadows) |
| `src/main/jni/bokeh_renderer.cpp` | Integrate color profile after bokeh pass |
| `BokehRenderer.java` | Expose color profile params |
| `EssentialService.java` | Select M3/M9 profile based on current mode |

**Verification**: M3/M9 photos visually match donor captures (subjective + ΔE measurement).

### Phase 3: Preview Bokeh (Week 3, Optional)

| File | Change |
|------|--------|
| `ModuleMain.java` | Hook preview request to add software blur surface |
| `src/main/jni/preview_blur.cpp` | New: fast Gaussian blur at preview resolution |

**Verification**: Preview shows mild bokeh effect (not required for MVP).

---

## KSU Module Changes

The KSU module already stages LUTs and sets the gate property. Add:

| File | Change |
|------|--------|
| `post-fs-data.sh` | Set `persist.sys.hypercam.software_native=1` |
| `sepolicy.rule` | Allow `hal_camera_default` to read/write EXIF-injected files (already covered) |

---

## Verification Checklist (Offline)

| Check | Method | Expected |
|-------|--------|----------|
| EXIF tag present | `exiftool -legendmode photo.jpg` | Tag 34992 = 1 (M9) or 2 (M3) |
| Gallery LEICA button | Open photo in Gallery | LEICA badge visible |
| Color match | ΔE measurement vs donor | ΔE < 5 (good match) |
| No HAL modification | `sha256sum camera.qcom.so` | Matches stock |
| No mount detection | Duck Detector | Clean (no KSU overlay for HAL) |

---

## Status

| Component | Status |
|-----------|--------|
| EXIF tagging spec | Complete (this document) |
| Color profile params | Approximate (needs donor measurement) |
| Preview bokeh | Deferred (performance concerns) |
| Implementation | Not started |
| Device testing | Blocked (no phone) |
