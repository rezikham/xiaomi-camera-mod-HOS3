# QNN Model Porting: HTP V81 (SM8750) → V75 (SM8650)

**Status**: Offline documentation. QNN model files extracted to `/run/media/rezikham/970/PoC/hal-extract-aurora/qnn_models/`. Device access needed for model conversion and testing.

---

## QNN Runtime Comparison

| Component | Aurora (14 Ultra, SM8650) | Madrid (18 Pro Max, SM8975) | Nezha (17 Ultra, SM8850) |
|-----------|---------------------------|-----------------------------|--------------------------|
| QNN HTP | V75 | V81 | V75-era |
| `libQnnHtp.so` | Present (`odm/lib64/libQnnHtp.so`) | V81 | Present |
| `libQnnHtpV75.so` | Present (`odm/lib/rfsa/adsp/libQnnHtpV75.so`) | N/A | Present |
| `libQnnHtpV75Skel.so` | Present | N/A | Present |

**Key finding**: Aurora runs QNN HTP V75, while Madrid uses V81. The `.minn` models are **not binary-compatible** between versions.

---

## Extracted QNN Model Files

All files are in `/run/media/rezikham/970/PoC/hal-extract-aurora/qnn_models/`:

### Madrid (18 Pro Max) — 4 style-transfer models (~40 MB each)

| File | Size | Purpose |
|------|------|---------|
| `qnn_models/madrid/styletrans_high_m9.minn` | 39,731,284 | High-res M9 neural style transfer |
| `qnn_models/madrid/styletrans_low_m9.minn` | 39,723,092 | Low-res M9 neural style transfer |
| `qnn_models/madrid/styletrans_high_m10r.minn` | 39,972,948 | High-res M10R neural style transfer |
| `qnn_models/madrid/styletrans_low_m10r.minn` | 39,981,140 | Low-res M10R neural style transfer |

### Nezha (17 Ultra) — 3 style-transfer models (~8-10 MB each)

| File | Size | Purpose |
|------|------|---------|
| `qnn_models/nezha/styletrans_colorfix.minn` | 8,398,942 | Color fix style transfer |
| `qnn_models/nezha/styletrans_high.minn` | 9,939,758 | High-res style transfer |
| `qnn_models/nezha/styletrans_low.minn` | 10,005,294 | Low-res style transfer |

### Nezha styletrans_params.json

Located at `qnn_models/nezha/styletrans_params.json` (1,556 bytes) — describes model paths and parameters.

---

## MiNN Container Analysis (Offline, Complete)

All `.minn` files were parsed with `tools/hal-port/aurora/extract_minn.py`:

| Finding | Detail |
|---------|--------|
| Container magic | `4e 4e 69 4d` ("NNiM") |
| Header structure | 4-byte magic + 3 dword fields (version, size, offset) |
| Embedded model | Compressed at variable offset within container |
| Compression | **Proprietary** — starts with `\x1f\x8b` (gzip-like) but CM byte = 0x67 (not standard deflate 0x08) |
| Madrid M9 high-res | Gzip-like magic at offset 159,400, CM=0x67, FLG=0x77 |
| Nezha models | Similar proprietary compression at varying offsets |

**Implication**: The embedded models use Xiaomi's proprietary compression, not standard gzip/deflate. Decompressing requires either:
1. Reverse-engineering Xiaomi's compression algorithm, or
2. Loading the model directly through QNN runtime (which may handle the container internally)

---

## Required Libraries (Already Extracted)

### Nezha libraries

| File | Size | Location |
|------|------|----------|
| `libs/nezha/libmialgo_styletrans.so` | 13,668,744 | Style-transfer engine (loads Nezha `.minn` models) |
| `libs/nezha/libLMSceneSeg.so` | 8,238,656 | Scene-segmentation model runtime |

### Madrid libraries

| File | Size | Location |
|------|------|----------|
| `libs/madrid/libmialgo_styletrans.so` | 304,712 | Style-transfer engine (loads Madrid M9/M10R `.minn` models) |
| `libs/madrid/libLMSceneSeg.so` | 8,492,368 | Scene-segmentation model runtime |

### M10R-specific libraries (Madrid)

| File | Size | Location |
|------|------|----------|
| `libs/m10r/libmage.so` | — | M10R color management (Madrid only) |
| `libs/m10r/libmialgo_ccm_3x8.so` | — | M10R CCM matrix (Madrid only) |
| `libs/m10r/libmage_nezha.so` | — | Nezha's libmage (for reference) |

---

## Missing Libraries on Aurora (Confirmed Absent)

| Library | Needed By | Status on Aurora |
|---------|-----------|------------------|
| `libmialgo_styletrans.so` | `legendST.so` | ❌ Absent |
| `libLMSceneSeg.so` | `libmialgo_styletrans.so` | ❌ Absent |
| `libmetatagwrapper.so` | `mileicafilter.so`, `legendST.so` | ❌ Absent |
| `libcameraopt_stub.so` | `mileicafilter.so`, `legendST.so` | ❌ Absent |
| `libcom.xiaomi.cam.miexifmgr.so` | `mileicafilter.so`, `legendST.so` | ❌ Absent |
| `libmage.so` | M10R path | ❌ Absent |
| `libmialgo_ccm_3x8.so` | M10R path | ❌ Absent |

Stub library sources are in `tools/hal-port/aurora/stub_libs/` for `libmetatagwrapper.so`, `libcameraopt_stub.so`, and `libcom_xiaomi_cam_miexifmgr.so`.

---

## Porting Approaches

### Approach 1: Direct Model Conversion (Recommended)

Convert `.minn` (MiNN container) → QNN V75-compatible format.

**Steps**:
1. **Parse MiNN container**: Read `NNiM` magic header, extract embedded model blob
2. **Handle proprietary compression**: The `\x1f\x8b` magic with CM=0x67 is NOT standard gzip — need to reverse-engineer or bypass
3. **Identify model format**: Check if embedded blob is ONNX, TensorFlow Lite, or custom QNN binary
4. **Convert to QNN V75 binary**: Use QNN model converter or manually repackage
5. **Re-quantize for V75**: QNN V75 uses INT8; V81 models may use different quantization
6. **Package with legendST**: Update `libmialgo_styletrans.so` to load the converted model path

**Challenges**:
- Proprietary compression (CM=0x67) blocks simple extraction
- QNN V81 ops may not exist in V75
- Model may use HVX DSP instructions not available on V75

### Approach 2: Runtime QNN Shim

Create a compatibility layer that translates V81 API calls to V75.

**Steps**:
1. Intercept `libQnnHtp.so` function calls from `libmialgo_styletrans.so`
2. Translate V81-specific ops to V75 equivalents
3. Fall back to CPU for unsupported ops

**Challenges**:
- QNN API surface is large and undocumented
- Performance may be unacceptable for real-time style transfer
- Risk of numerical differences affecting image quality

### Approach 3: CPU Fallback (Simplest, Slowest)

Run style transfer on CPU instead of DSP.

**Steps**:
1. Convert model to CPU-runnable format (TensorFlow Lite, ONNX Runtime, ncnn)
2. Run on CPU cores via `libmialgo_styletrans.so` shim
3. Accept slower capture time

**Challenges**:
- 39.7 MB model on CPU will be slow (2-5 seconds vs <1 second on DSP)
- May cause camera app timeout
- Battery drain

---

## Recommended Path

1. **Try bypassing MiNN container** — Have `libmialgo_styletrans.so` load models directly without container parsing (the library may handle NNiM internally)
2. **If that fails** — Write custom decompressor for CM=0x67, then convert to QNN V75
3. **If conversion fails** — Use CPU fallback (Approach 3) for non-real-time capture
4. **Document the gap** — M3/M9 without neural style transfer still works via `mileicafilter` parameters + LUT injection

---

## Offline Preparation Scripts

### MiNN Container Parser

`tools/hal-port/aurora/extract_minn.py` — Parses `.minn` files, identifies embedded model candidates, extracts compressed blobs. Tested on all 7 models.

### QNN V75 Compatibility Test

```python
# tools/hal-port/aurora/test_qnn_compat.py
# - Load libQnnHtp.so V75 from extracted Aurora files
# - Attempt to load converted model
# - Report supported ops / errors
```

---

## Files That Must Be Ported Together

| File | Source | Must Port? | Notes |
|------|--------|------------|-------|
| `styletrans_high_m9.minn` | Madrid/18PM | Yes | For M9 style transfer |
| `styletrans_low_m9.minn` | Madrid/18PM | Yes | For M9 preview |
| `styletrans_high_m10r.minn` | Madrid/18PM | M10R only | Requires `libmage.so` + `libmialgo_ccm_3x8.so` |
| `styletrans_low_m10r.minn` | Madrid/18PM | M10R only | Requires `libmage.so` + `libmialgo_ccm_3x8.so` |
| `libmialgo_styletrans.so` | Nezha/17U | Yes | Loads Nezha `.minn` models |
| `libLMSceneSeg.so` | Nezha/17U | Yes | Scene segmentation |
| `libmetatagwrapper.so` | Stub | Yes | Stub in `tools/hal-port/aurora/stub_libs/` |
| `libcameraopt_stub.so` | Stub | Yes | Stub in `tools/hal-port/aurora/stub_libs/` |
| `libcom.xiaomi.cam.miexifmgr.so` | Stub | Yes | Stub in `tools/hal-port/aurora/stub_libs/` |
| `styletrans_params.json` | Nezha/17U | Yes | Model path configuration |
| `libQnnHtp.so` (V81) | Madrid/18PM | No | Use Aurora's V75 |

---

## Status

| Component | Status |
|-----------|--------|
| QNN model files extracted | ✅ Done (4 Madrid + 3 Nezha models) |
| Required libs extracted | ✅ Done (Nezha + Madrid styletrans + LMSceneSeg) |
| M10R libs extracted | ✅ Done (libmage.so, libmialgo_ccm_3x8.so) |
| Stub library sources | ✅ Done (metatagwrapper, cameraopt_stub, miexifmgr) |
| MiNN container parser | ✅ Done (`extract_minn.py`) |
| Proprietary compression identified | ✅ Done (CM=0x67, not standard deflate) |
| MiNN→QNN converter | Not written |
| QNN V75 compatibility test | Not written |
| CPU fallback implementation | Not written |
| Device testing | Blocked (phone disconnected) |
