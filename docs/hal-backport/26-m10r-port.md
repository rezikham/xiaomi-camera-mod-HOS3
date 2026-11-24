# 26 — M10R (CMOS) on the 14 Ultra

Status: working on `1c4e4dec` with module `1.1.0-369` (test build).

## What the 18 Pro Max does

The 18 Pro Max (`madrid`, Camera 6.8) has M10R native in both the app and
the HAL. The app adds M10R to the Leica Essential list (and makes it the
default); the HAL pipeline runs `mileicafilter` with
`leica_filter_param_m10r_snapshot.bin` / `m10r_preview.bin`, then `legendST`
with the dedicated M10R `styletrans_*_m10r.minn` models and `legendCT`.
See [18-pro-max-hal/02-legend-m10r.md](../18-pro-max-hal/02-legend-m10r.md).

The 18 PM source filter bin is 1,598,108 bytes
(SHA-256 `7f6ab019…`). The Essential Moments app ships a trimmed
`assets/legend18/leica_filter_param_m10r_snapshot.bin` of 1,597,668 bytes
(SHA-256 `9713e4f6…`), the form its `libm9base` filter expects.

## 14 Ultra port

Camera 6.7 on the 14 Ultra has no M10R item and its HAL has no M10R
processing. The module adds the CMOS (M10R) item to the Essential list when
`m10r_enabled` is on (default on in the test profile). Selecting it stores
`pref_legendary_mode_key=M10R`; the save-path pipeline maps that to
`legend=3`.

The M10R capture runs the app's M10R pipeline shape:

1. decode the saved JPEG (sRGB), estimate lux/CCT/ISO;
2. `applyWarmOriginalFilter(bitmap, m10r.bin, lux, cct, 1.0, phase,
   denoise, -0.95 EV, 0.3)` through the B12 wrapper;
3. `B43Tone` (M9/M10R branch);
4. JPEG 95 + APP1 copy + appended-IFD0 `legendmode` tag.

FilmInput and Monopan are M3-only and are skipped. The M10R neural style
transfer and `LeicaQuality` are not run (see below).

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
runtime (NEEDED `libmage.so`), not the 17 Ultra QNN lib. It exports the same
`MialgoAi_DIPS_*` API as the 17 Ultra `libstyletrans_runner.so`, and the
18 PM `styletrans_low_m10r.minn` model is 39,981,140 bytes. The runner was
patched to the default namespace and given the Monopan OpenCL shim/ICD; the
18 PM lib, `libmage.so`, `libLMSceneSeg.so`, the QNN stub/system/htp,
`libcdsprpc.so`, `libvmmem.so` and the DSP HIDL lib were staged. The runner
then reached the 18 PM MAGE runtime:

```text
StyletransDIPS: StyletransDIPS version 2.0.0.20260920
Styletrans init=0
Styletrans run=1140001 rotation=0 lux=20 cct=5500
HCM-Essential: M10R style transfer=false
LeicaM9M3B12: B12 upstream_failed stage=1 recovery=fresh_source_requested
```

The same `run=1140001` occurs with the genuine 18 PM `styletrans_low_m9.minn`
model, so the failure is the 18 PM MAGE runtime/model on the 14 Ultra GPU,
not the M10R model specifically. The 18 PM model/legendCT remain the main
M10R parity gap.

## Remaining gaps

* 18 PM M10R neural style transfer (`styletrans_low/high_m10r.minn`) and
  `legendCT` are not runnable through the 17 Ultra runner on the 14 Ultra.
* No 18 PM M10R reference capture exists in the dump: all 72 camera JPEGs
  are `filterName:"Original"`; the only other file is a video with an empty
  filter name. A matched comparison needs an 18 PM M10R capture or the 18 PM
  device.
* The 18 PM source bin (with neutral gain/build timestamp) is incompatible
  with the app engine; the trimmed Essential Moments bin is used.
