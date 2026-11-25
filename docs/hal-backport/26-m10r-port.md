# 26 — M10R (CMOS) on the 14 Ultra

Status: working on `1c4e4dec` with module `1.1.0-369` (test build).

## What the 18 Pro Max does

The 18 Pro Max (`madrid`, Camera 6.8) has M10R native in both the app and
the HAL. The app adds M10R to the Leica Essential list (and makes it the
default); the HAL pipeline runs `mileicafilter` with
`leica_filter_param_m10r_snapshot.bin` / `m10r_preview.bin`, then `legendST`
with the dedicated M10R `styletrans_*_m10r.minn` models and `legendCT`.
See [18-pro-max-hal/02-legend-m10r.md](../18-pro-max-hal/02-legend-m10r.md).

The 18 PM `leica_filter_param_m10r_snapshot.bin` (1,598,108 bytes, SHA-256
`7f6ab019…`) has **0/108 non-neutral LUTs** because snapshot delegates color
styling to the QNN HTP V85 `styletrans_*_m10r.minn` UNet. However,
`leica_filter_param_m10r_preview.bin` (1,598,108 bytes, timestamp `202609041724`)
contains Xiaomi's **authentic 18 Pro Max Leica M10-R (CMOS) 17³ 3D LUT** in all
108/108 slots (`mean_diff = 10.70/255`, `max_diff = 65/255` vs identity;
`mean_diff = 17.79/255` vs 18PM M9 preview).

Trimming the 440-byte tail delta (`55 × [int32(9), float32[9]]` → `55 × float32[8]`)
via `tools/hal-port/luts/make_m10r_lut.py --trim-bin` produces a 1,597,668-byte
`libm9base`/B12-compatible bin (SHA-256 `52a41273…`) bundled as
`assets/essential/leica_filter_param_m10r_snapshot.bin`, and trilinearly
upsampling the same 17³ LUT produces the 512×512 Leica Looks strip LUT
(`assets/leica_luts/M10R_Cmos.png` & `camera-mod-ksu/data/vendor/camera/lut_m10r.png`,
MD5 `e1a95801d1a720f9ea446c290a8cc8ab`).

## 14 Ultra port

Camera 6.7 on the 14 Ultra has no M10R item and its HAL has no M10R
processing. The module adds the CMOS (M10R) item to the Essential list when
`m10r_enabled` is on (default on in the test profile). Selecting it stores
`pref_legendary_mode_key=M10R`; the save-path pipeline maps that to
`legend=3`.

The M10R capture runs the app's M10R pipeline shape:

1. decode the saved JPEG (sRGB), estimate lux/CCT/ISO;
2. `applyWarmOriginalFilter(bitmap, m10r.bin, lux, cct, 1.0, phase,
   denoise + 0.15, -0.95 EV, 0.3)` through the B12 wrapper
   (the app's `M10rBin` soft);
3. `M10rPost.after` — restore the input white balance, soften, then the
   `M10rBin` vignette 0.3 / grain 0.3 (saturation 1.0);
4. `B43Tone` (M9/M10R branch);
5. JPEG 95 + APP1 copy + appended-IFD0 `legendmode` tag.

FilmInput and Monopan are M3-only and are skipped. The M10R neural style
transfer and `LeicaQuality` are not run (see below). The M10R post is the
Essential Moments app's own local M10R reconstruction, not the 18 PM neural
style/legendCT.

Without the post the M10R filter bin alone is almost indistinguishable from
M9 (the bins differ in one LUT block). The post makes CMOS visibly
different. Same-scene pair from the 14 Ultra:

```text
M9   IMG_20261124_180935.jpg: R/B 0.465, edge stddev 14.9, 0x88b0=1
M10R IMG_20261124_180953.jpg: R/B 0.602, edge stddev 23.1, 0x88b0=3
M10rPost: balance before=1.0619,1.5581 after=0.9294,1.6321 gains=1.1125,0.9635
M10rPost: finish saturation=1.0 vignette=0.3 grain=0.3
```

M10R is warmer and grainier, which is the intended CMOS/Modern vintage
difference from CCD/M9.

The asset used is the Essential Moments trimmed bin, **not** the 18 PM
source bin. The 18 PM source bin is accepted by the 18 PM HAL plugin but the
app's `libm9base`/B12 engine rejects it:

```text
HCM-Essential: M10R scene ... backend=B12
LeicaM9M3B12: B12 upstream_failed stage=2 recovery=fresh_source_requested
HCM-Essential: original filter failed
```

With the trimmed bin the same engine completes:

```text
HCM-Essential: M10R scene lux=20.0 cct=5500.0 iso=1250.0 denoise=0.388 backend=B12
LeicaM9M3B12: B12 look=M9_CLEAR_DENSITY_B12 applied=1 ...
HyperCameraMod: Moment App applied legend=3 IMG_20261124_172359.jpg
```

Verified output `IMG_20261124_172359.jpg`:

```text
3072x4096, colour
IFD0 Camera Model Name : Xiaomi 14 Ultra
IFD0 Exif 0x88b0       : 3   (M10R)
ExifIFD ISO            : 1250
```

The 18 PM M10R style model attempt is recorded below; it did not run.

## 18 PM M10R style-transfer attempt

The 18 PM `libmialgo_styletrans.so` (304,712 bytes) is a MAGE/OpenCL
host (NEEDED `libmage.so`), not the 17 Ultra QNN lib. It exports the same
`MialgoAi_DIPS_*` API as the 17 Ultra `libstyletrans_runner.so`. The runner
was patched to the default namespace and given the Monopan OpenCL shim/ICD;
the 18 PM lib, `libmage.so`, `libLMSceneSeg.so`, the QNN stub/system/htp,
`libcdsprpc.so`, `libvmmem.so` and the DSP HIDL lib were staged.

The initial `run=1140001` was a **missing model**: the 18 PM library selects
the high tier and requested `styletrans_high_m9.minn`; only the low model was
staged. After adding the high M10R model (and the low M10R under the high
name), the model loads but its QNN executor fails:

```text
StyletransDIPS: StyletransDIPS version 2.0.0.20260920
Styletrans init=0
QnnExecutorImplV2: m_interface is null
Initialize: m_is_valid is invalid
StyletransModelForward: model legend Initialize fail!!
MialgoAi_DIPS_Run run fail!
```

The 18 PM style models are **QNN HTP V85 (`soc_type=SM8975; arch=85; min_arch=85`, QNN SDK `v2.47.0`)**, not V81 (17 Ultra / `nezha` is HTP V81, QNN `v2.33.0`). Decrypting the `NNiM` v2 container (`mage::NNDecrypt`: 16-byte repeating XOR `"legenddddddddddd"` over the first `0x10000` bytes of the payload) confirms all four Madrid models (`m10_high_sim`, `m10_low_sim`, `m9_high_sim`, `m9_low_sim`) are precompiled HTP V85 UNets mapping `(1, 512, 512, 3)` → `(1, 256, 256, 384)` (`32 luma bins × 12 affine 3×4 RGB coefficients`), sliced at full resolution via OpenCL (`styletrans_process_bilateral_process_cl`). The 14 Ultra is HTP V75, so the V85 context binary cannot execute and no ONNX/GPU/CPU graph is shipped; `libmage.so` (`QnnExecutorImplV2474`) fails at `m_interface is null` when no compatible `v2.47` HTP provider initializes. See [15-qnn-model-porting.md](15-qnn-model-porting.md).

`com.xiaomi.plugin.legendCT.so` (`libmialgo_ccm_3x8.so`, 14 MB, no QNN) was
reversed and confirmed to be a no-op placeholder on `madrid`: all 153 embedded
17³ LUTs in `.rodata` are exact identity (`max_abs_diff = 0.000000`), and
`legendsnapshot.json` does not wire `legendCT`.

## Remaining gaps

* 18 PM M10R neural style transfer (`styletrans_high/low_m10r.minn`) is a
  precompiled **QNN HTP V85 (`SM8975`, `v2.47.0`)** bilateral-grid UNet
  (`512×512×3 → 256×256×384`) with no ONNX/GPU/CPU payload; the 14 Ultra V75
  returns `m_interface is null` / `m_is_valid is invalid`. The trimmed 18 PM
  `leica_filter_param_m10r_preview.bin` supplies the authentic Xiaomi 18 PM
  M10-R 3D LUT (used for both live viewfinder and Essential/Looks on 14 Ultra)
  as the closest 1:1 hardware-portable color transform.
* No 18 PM M10R reference capture exists in the dump: all 72 camera JPEGs
  are `filterName:"Original"`; the only other file is a video with an empty
  filter name. A matched comparison needs an 18 PM M10R capture or the 18 PM
  device.
* The 18 PM source bin (with neutral gain/build timestamp) is incompatible
  with the app engine; the trimmed Essential Moments bin is used.
