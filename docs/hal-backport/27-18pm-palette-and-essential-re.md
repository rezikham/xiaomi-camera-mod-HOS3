# 27 — 18 Pro Max (`madrid`) Palette, Parameters, and Essential Moments RE

Date: 2026-11-26  
Donor: Xiaomi 18 Pro Max (`madrid`, SM8975 / HTP V85, Camera 6.8)  
Target: Xiaomi 14 Ultra (`aurora`, `1c4e4dec`, SM8650 / HTP V75, HyperOS 3, Camera 6.7)

---

## 1. Palette & Parameters (`StyleAdjustEffect`) Capture Parity Gap

### Live Diagnosis on `1c4e4dec`
- **Preview:** Camera 6.7 (14U) and Camera 6.8 (18PM) bundle **bit-for-bit identical** `lib/arm64-v8a/libMiFilterSDK.so` (`724,224` bytes, `md5 = d23f8e8d3e2b1d93f681d8325184fd9e`) and identical `assets/cloudfilter/cloud_filter_custom.json` (32 filters, 0 `ExtraParam` diffs). Live preview renders Palette and all 8 Parameters (`Saturation`, `Tone`, `Soft light`, `Cold/Warm`, `Cyan/Magenta`, `Noise`, `Sharpness`, `Vignette`) via:
  ```text
  CandySDK: setFilterScript @StyleAdjustEffect;Algos=<mask>;SharpenIntensity=...;ContrastIntensity=...;SaturationIntensity=...;Temperature=...;Tint=...;Grain=...;VignetteIntensity=...
  ```
- **Capture (stock 14U HAL):** `com.xiaomi.plugin.filter.so` (`FilterPlugin.cpp`) forwards the `filterScript` from `com.xiaomi.mivi2.render` directly to `/odm/lib64/libMiPhotoFilter.so`.
  - Stock 14U `/odm/lib64/libMiPhotoFilter.so` (`1,083,576` bytes) **does not contain `StyleAdjustEffect`** (`STYLE_ADJUST` shader = absent) and uses the older `CvStyleEffect` without `RatioSat`.
  - Live verification on `1c4e4dec`: shooting with `Saturation = +50` (`SaturationIntensity=0.7`) showed vivid cobalt blue in preview, while the saved JPEG (`IMG_20261126_050527.jpg`) had `mean_sat = 0.4998` vs `0.5012` at `Saturation = 0` — **100% dropped on capture**.

### 18PM `libMiPhotoFilter.so` (`1,350,800` bytes) Shader & ABI Findings
- `NEEDED` dependencies: **only standard Android NDK libs** (`liblog.so`, `libGLESv3.so`, `libEGL.so`, `libnativewindow.so`, `libz.so`, `libm.so`, `libdl.so`, `libc.so`) — zero QNN or vendor HAL dependencies.
- Implements the complete 13,683-byte **`STYLE_ADJUST`** GLSL shader (Sharpen, Contrast, Saturation, 20-term cubic RGB Temperature + `-0.15 * abs(temp)` brightness, 20-term cubic RGB Tint, Grain, Vignette) and upgraded **`CvStyleEffect`** with `RatioSat` luminance-only Leica vignette (`color.rgb = color.rgb * (vignette * (1 - ratioSat) + ratioSat) + Y * (vignette - 1) * ratioSat`, where `ratioSat = 1.0` in all 18PM `.bin` files).
- **VideoFilter vtable delta (14U `0x107aa8` vs 18PM `0x1457f8`):**
  - 18PM inserts `updateDynamicWaterMark` at `+0x50`, shifting:
    - `setFilterScript`: `+0x50` → **`+0x58`**
    - `GPUVideoFilter::Process`: `+0x60` → **`+0x68`**
    - `Reset`: `+0x68` → `+0x78`
  - `com.xiaomi.plugin.filter.so` on 14U calls `ProcessorCreator::createFilter(videoprocess::VideoFormat, bool)` (exported identically by 18PM) and only 4 shifted virtual-call instructions (`0xb138`, `0xb5b4`, `0xc8e4`, `0xc918`).
- **Bridge tool (`tools/hal-port/aurora/patch_filter_palette_18pm.py`):**
  - Patches those 4 instructions in a copy of 14U `com.xiaomi.plugin.filter.so` (`+0x50 -> +0x58`, `+0x60 -> +0x68`) and renames its `DT_NEEDED` (and 18PM `libMiPhotoFilter.so`'s `DT_SONAME`) in-place from `libMiPhotoFilter.so` to `libMiPhotoFlt18p.so` (exact 19-byte `.dynstr` match).
  - Leaves stock `/odm/lib64/libMiPhotoFilter.so` completely untouched for `com.xiaomi.plugin.watermark.so`.
- **Live on-device verification (`1c4e4dec`, `/data/adb/modules/hcm-palette-18pm`):**
  - Mounted via KernelSU `$MODDIR/system/odm/lib64/{libMiPhotoFlt18p.so,camera/plugins/com.xiaomi.plugin.filter.so}` (`0644`, `u:object_r:vendor_file:s0`).
  - `vendor.qti.camera.provider-service_64` loaded both `libMiPhotoFlt18p.so` (for `com.xiaomi.plugin.filter.so`) and stock `libMiPhotoFilter.so` (for `com.xiaomi.plugin.watermark.so`) cleanly with zero linker or SELinux errors.
  - Capture test `IMG_20261126_104557.jpg` (`ESTL M10R` + `Saturation = -1.0`):
    ```text
    CandySDK: process effect StyleAdjustEffect
    CandySDK: effect_name: StyleAdjustEffect, params_name: Algos, params_value: 4.000000
    CandySDK: effect_name: StyleAdjustEffect, params_name: SaturationIntensity, params_value: -1.000000
    CandySDK: VideoFilter::Process in_frame->width: 4096 in_frame->height: 3072 totalProcess 43 uploadProcess 37 renderProcss 6 downloadProcess 0
    ```
  - Pixel verification: `3072×4096`, `mean_sat = 0.0000`, `p95_sat = 0.0000`, `mean_chroma = 0.00`, `max|R-B| = 0.0` — 1:1 18PM `StyleAdjustEffect` GPU rendering in 43 ms inside the stock HAL.
  - **`LEICA STD` (no LUT filter active) capture fix (`ModuleMain.java` `installPaletteCaptureHook`):**
    - On `LEICA STD` (`filterId = 0x10200`), `Camera2Module.updateFilter()` sets `com.xiaomi.camera.captureSat.isFilterApplied = false` and `updateStyle*()` never updates it, while `MIVIRenderTag` sets `renderType = 512` (`PICTURE_STYLE` only, bit 0 = 0), causing HAL to route through `CamX nonZSL` / `satsnapshotjpeg.json` and skip `FilterPlugin`.
    - Hooked `CameraConfigManager.s(boolean)`, `Camera2Module.updateStyle*()`, and `MiCamera2.K1(CaptureRequest.Builder)` to keep `captureSat.isFilterApplied = true` and set `renderType | 1` whenever `StyleAdjustEffect` is active on `LEICA STD`.
    - Live verification (`IMG_20261126_150615.jpg`, Photo mode `LEICA STD` + `Saturation = -100`): `CandySDK: VideoFilter::Process ... 4096x3072 totalProcess 46 ms`, `meanRGB = (67.15, 67.15, 67.15)`, `max|R-B| = 0.0`.
- **EXIF / XMP / XML Parity Audit (18PM vs 14U):**
  - **Essential / Legendary tag (`0x88b0`):** `ExifUtils.injectLegendMode` writes `IFD0:0x88b0` (`1=M9`, `2=M3`, `3=M10R`) on all Essential captures; `EssentialProcessor.copyExif()` also preserves `APP1` XMP segments.
  - **18PM `FullLinkEditableJpeg` & `LegendMsg`:** 18PM `jpegrAggr.so` / `heifedit.so` embed `FilterBeanMsg` (`filterScript`, `miCamStyle`, `generalFilterId`) and `LegendMsg` (`Legend.MONOPAN`) protobufs referenced via `http://ns.xiaomi.com/photos/1.0/camera/reedit` + `MiCamera:XMPMeta`. Scanning all dexes of 14U's `MIUIGalleryGlobal.apk` and 14U's `jpegrAggr.so` / `heifedit.so` confirmed neither `FilterBeanMsg` nor `LegendMsg` exists on 14U, so `0x88b0` in IFD0 is the active Gallery-recognized Essential tag on 14U.

---

## 2. Essential Moments (M3 / M9 / M10R) RE & Fixes

### 2.1 QCOM AEC `lux_idx` Inversion Bug in `EssentialProcessor.java`
- Disassembly of `libm9base.so` (`adaptiveLut` @ `0xc620`, `leicaWeights` @ `0xf1c8`) and binary decoding of `leica_filter_param_{m3,m9,m10r}_*.bin` (header `u16[0]=6` scenes, `u16[7]=1024` param offset, `u16[8]=4096` LUT offset, `u16[9]=17`, `u16[10]=5` zoom bands `{0.6, 1.0, 3.0, 4.3, 10.0}`) prove that the `lux` argument is **Qualcomm AEC `lux_idx`** (low = bright, high = dark):
  - `0..210`: bright sunlight
  - `240..310`: outdoor overcast / shade
  - `330..380`: indoor
  - `400..470`: dim indoor / night
  - `490..530`: dark night
  - `550..999`: very dark
- `EssentialProcessor.java` previously computed physical lux `2.5 * 2^ev100` (`6.25` in a dark room → hit bright-sunlight slot 0; `10000` in daylight → hit pitch-dark slot 18 with heavy night vignette `SmoothCoordScale=3.35`).
- Fixed to `luxIdx = clamp(470.0 - 25.0 * ev100, 0.0, 999.0)`.

### 2.2 Authentic 18PM M9 LUT & Bin (`leica_filter_param_m9_preview.bin`, `202609041131`)
- `com.xiaomi.plugin.mileicafilter.so` explicitly logs `[LeicaFilter][%s] use pre param for M9` / `use pre param for M10R` when `m_styleTransON == 0`.
- Comparing all Essential `.bin` files:
  - `18PM m3` vs bundled `m3`: 100% identical (`6/6` neutral LUTs; M3 look is shading + `FilmInput` + `Monopan`).
  - Bundled `m9_snapshot.bin` (`20260108`): only `72/114` mild post-QNN residual LUTs (`mean_diff = 3.96/255`), and **all `42/114` dim/night slots (`lux_idx >= 400`) are 100% identity**.
  - `18PM m9_preview.bin` (`202609041131`): **114/114 non-neutral LUTs** (`5` unique lux/CCT-tuned LUTs, `mean_diff = 12.81/255` vs identity, `18.97/255` vs `130_LeicaNatural.png`, `17.79/255` vs 18PM M10R).
  - Previously, `lut_m9.png` (`ESTL M9` in the Leica Looks strip) was a copy of `130_LeicaNatural.png` (`md5 = ecbafbf8130ba7340749e95ace25616b`).
- Extracted the genuine 18PM M9 64³ 512×512 LUT into `src/main/assets/leica_luts/M9_Ccd.png` and `camera-mod-ksu/data/vendor/camera/lut_m9.png` (`md5 = b34771febab9723f6ce1dbe9bd3c75a4`), and trimmed 18PM `leica_filter_param_m9_preview.bin` (440 bytes stripped → `1,686,102` bytes) into `src/main/assets/essential/leica_filter_param_m9_snapshot.bin`.
- **Critical header flag fix (`0x362 = 1`):** `libm9base.so` (`applyLeicaFilter` @ `0xce78`) checks `u16[0x362]` (`lut_snapshot_en`) before calling `adaptiveLut`. In 18PM `leica_filter_param_m9_preview.bin`, `[0x360..0x367]` was `(1, 0, 1, 1)` (`lut_snap = 0`), causing `LeicaNative` to log `LUT=0` and skip all 114 M9 LUTs during capture. Patching `u16[0x362] = 1` restored `LUT=1 shading=1` on live M9 captures.

### 2.3 18PM M3 OpenCL Model (`libmialgo_monopan.so`) & M10R Pipeline Parity
- **18PM M3 (`libmialgo_monopan.so`, `18,858,832` B, `md5 = 43c5f99b73a4e68e39798aa8a952a0fb`):**
  - Pure OpenCL (`mage2.0`) GPU film-grain model linking only standard NDK libs (`liblog`, `libm`, `libc++_shared`, `libdl`, `libc`) with identical `MonopanInit`/`MonopanProcess`/`MonopanDeinit` ABI.
  - Replaced bundled 17U `libmialgo_monopan.so` (`18,312,840` B) in `src/main/jniLibs/arm64-v8a/libmialgo_monopan.so`.
  - Verified both standalone (`libmonopan_runner.so`: `MonopanProcess result=0`, `RC=0`, grain `std = 7.310`) and end-to-end inside Xiaomi Camera (`LeicaNative: M3 original algorithm result=0 size=3072x4096`, `HCM-Essential: Monopan grain=true in 1097 ms`).
- **18PM M10R (`EssentialProcessor.java` `m_styleTransON == 0` parity):**
  - Previously, M10R (`legend=3`) ran `applyWarmOriginalFilter` (`M9_CLEAR_DENSITY_B12` `-0.95 EV` / `warmth=0.3` / `cream=0.702`), then `M10rPost.after()` (`restoreBalance` undoing 80% of the LUT color shift, `soften` 30% half-res blur, plus a third vignette and second grain), then 17U M9 `B43Tone`.
  - Switched M10R to `LegendaryPostProcessor.applyOriginalFilter` on authentic 18PM `leica_filter_param_m10r_preview.bin` (108 real 17³ LUTs + 55-slot shading), matching 18PM's exact `m_styleTransON == 0` path (`LUT=1 shading=1`, `cream=0.000`, `grade_ms=0.00`, `1036 ms`).
- **QNN `styletrans_{high,low}_{m9,m10r}.minn` hardware boundary:**
  - All 4 neural bilateral-grid UNets (`(1,512,512,3)` `UFIXED_POINT_16` → `(1,256,256,384)`) are precompiled QNN `v2.47.0` **Hexagon HTP V85** (`SM8975`, `min_arch=85`) context binaries with no ONNX/TFLite/GPU/CPU graph; they cannot execute on 14U's HTP V75 (`SM8650`).

### 2.4 Live Device Verification (`1c4e4dec`)
- M3 Essential (`IMG_20261126_112929.jpg`): `3072×4096`, `0x88b0=2`, `meanRGB=(117.1, 117.1, 117.1)`, `mean|R-B|=0.00`, 18PM `libmialgo_monopan.so` OpenCL `result=0` (`1097 ms`), 0 exiftool warnings.
- M9 Essential (`IMG_20261126_113702.jpg`): `3072×4096`, `0x88b0=1`, `meanRGB=(131.9, 141.3, 149.3)`, `mean|R-B|=61.20`, `LUT=1 shading=1` (18PM `m9_preview.bin` + `M9_CLEAR_DENSITY_B12` + `B43Tone`), 0 exiftool warnings.
- M10R Essential (`IMG_20261126_113630.jpg`): `3072×4096`, `0x88b0=3`, `meanRGB=(130.2, 137.3, 143.0)`, `mean|R-B|=37.52`, `LUT=1 shading=1` (clean 18PM `m10r_preview.bin` path, `1036 ms`), 0 exiftool warnings.
- `ESTL M10R` Leica Looks strip (`IMG_20261126_044251.jpg`): live preview (`OpenGl3dUtils LUT lut_nc -> lut_m10r`) and CandySDK capture (`512×512`) both verified using authentic 18PM `M10R_Cmos.png` (`md5 = e1a95801d1a720f9ea446c290a8cc8ab`).

---

## 3. Additional 18PM Assets & AI Model Inventory

- **18PM `videofilter/` LUTs:** All 8 shared film stocks (`C50D`, `F50`, `FC400`, `H400`, `KC64`, `KG200`, `KP160`, `V250`) are byte-identical to our KSU module. 18PM adds 7 new Leica Looks (`169_LeicaChrome.png`..`175_LeicaIModelA.png`) and `167_CC.png`/`168_NC.png` (14U stock `/odm/etc/camera/videofilter/` stops at `162_Natural.png`).
- **Original Portrait LUT audit:** `src/main/assets/original_portrait_lut.png` (`2,631` bytes) is **100.00% identity** (`mean_diff = 0.0000`, `max_diff = 0.0`).
- **18PM `.minn` AI models (140 top-level files):** Dual-cam AI bokeh (`dualcam_capture_aibokeh_*.minn`, `matting_depth_refine.minn`), AI tone (`ATM_*.minn`, `merge_aitone_coeff.minn`, `sr_tone_*.minn`), and `styletrans_*_{m9,m10r}.minn` are precompiled for QNN HTP V85 (`SM8975`/`SM8973`), incompatible with 14U HTP V75 (`SM8650`). `libanc_single_bokeh.so` (18PM, `28 MB`) bundles `modelopr` QNN/ONNX/XNN + `bokehmodel.data` (`AncModelPack`, `18.7 MB`).
