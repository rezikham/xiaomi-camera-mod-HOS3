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
  - Note: on `LEICA STD` (no filter active), Camera 6.7 leaves `com.xiaomi.mivi2.renderType = 0`, so `FilterPlugin::isEnabled()` skips the filter node; when any Leica Look / Film filter is active, `com.xiaomi.mivi2.renderType != 0` and both `CubeLutEffect` + `StyleAdjustEffect` execute on GPU.

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

### 2.3 Live Device Verification (`1c4e4dec`)
- M9 Essential (`IMG_20261126_043857.jpg`): `3072×4096`, `IFD0 0x88b0 = 1`, intact MakerNotes (`88` B) & zero exiftool warnings.
- M10R Essential (`IMG_20261126_043908.jpg`): `3072×4096`, `IFD0 0x88b0 = 3`, intact MakerNotes (`88` B), `leica_filter_param_m10r_snapshot.bin` (`1,597,668` B) loaded cleanly by `libm9base`/B12.
- `ESTL M10R` Leica Looks strip (`IMG_20261126_044251.jpg`): live preview (`OpenGl3dUtils LUT lut_nc -> lut_m10r`) and CandySDK capture (`512×512`) both verified using authentic 18PM `M10R_Cmos.png` (`md5 = e1a95801d1a720f9ea446c290a8cc8ab`).

---

## 3. Additional 18PM Assets & AI Model Inventory

- **18PM `videofilter/` LUTs:** All 8 shared film stocks (`C50D`, `F50`, `FC400`, `H400`, `KC64`, `KG200`, `KP160`, `V250`) are byte-identical to our KSU module. 18PM adds 7 new Leica Looks (`169_LeicaChrome.png`..`175_LeicaIModelA.png`) and `167_CC.png`/`168_NC.png` (14U stock `/odm/etc/camera/videofilter/` stops at `162_Natural.png`).
- **Original Portrait LUT audit:** `src/main/assets/original_portrait_lut.png` (`2,631` bytes) is **100.00% identity** (`mean_diff = 0.0000`, `max_diff = 0.0`).
- **18PM `.minn` AI models (140 top-level files):** Dual-cam AI bokeh (`dualcam_capture_aibokeh_*.minn`, `matting_depth_refine.minn`), AI tone (`ATM_*.minn`, `merge_aitone_coeff.minn`, `sr_tone_*.minn`), and `styletrans_*_{m9,m10r}.minn` are precompiled for QNN HTP V85 (`SM8975`/`SM8973`), incompatible with 14U HTP V75 (`SM8650`). `libanc_single_bokeh.so` (18PM, `28 MB`) bundles `modelopr` QNN/ONNX/XNN + `bokehmodel.data` (`AncModelPack`, `18.7 MB`).
