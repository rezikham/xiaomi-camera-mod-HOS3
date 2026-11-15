# Engine-side filter injection: who consumes `filterId`, and how to inject a LUT

This page answers the question that was still open after the plugin route was
abandoned: **on Aurora, which component turns the app's filter selection into
pixels, and can a custom LUT (M3/M9) be injected there?**

Short answer:

* the style is applied by the **snapshot filter plugin**
  `odm/lib64/camera/plugins/com.xiaomi.plugin.filter.so`
  (Chi node `com.xiaomi.plugin.filter`, instance `FilterInstance0`), which loads
  `CubeLutEffect` LUTs from a PNG file and runs them through `libMiPhotoFilter.so`;
* the app decides whether that plugin gets a payload at all. The switch is the
  system property **`camera.debug.mivi3.output.jpeg`**; when it is set the app
  stops rendering the filter itself and sends
  `com.xiaomi.mivi2.renderType` + `com.xiaomi.mivi2.render` to the HAL instead;
* the LUT is a plain **512x512 RGB PNG** (8x8 tiles of a 64^3 lattice) at
  `/data/vendor/camera/<lutName>.png`. Replacing that file replaces the look;
* verified on the device: the same shot/filter produced
  saturation `0.142` with the stock Negative LUT and **`0.000`** with the
  `M3_Monopan` LUT, and a solid-black LUT produced a fully black JPEG.

## 1. The app-side producer

`com.xiaomi.camera.mivi.filter.MIVIRenderTag` (Camera 6.7.000280.0) builds the
render document:

```json
{"lutPath":"/data/vendor/camera/","generalFilterId":72,
 "filterScript":"@CubeLutEffect;cube_strength=1.0;lut_type=0;@CvEffect;NoiseStrength=0.4;@",
 "isCustomLut":false,"renderType":1,"version":3.002,"lutNames":["lut_nc"]}
```

[observed] `n9.A0.K1(CaptureRequest.Builder)` (classes4.dex, method header at
`0x5bb6cc`, `MIVIRenderTag.<init>:(IIIIFZ)V` call at `0x5bb744`) writes it:

```text
MCAM_CaptureRequestBuilder: applyMIVIRenderType: 1
MCAM_MiCamera2: applySettingsForMIVIRender: { ... "lutNames":["lut_nc"] }
```

* `renderType` goes to the vendor tag `com.xiaomi.mivi2.renderType` (Integer),
* the JSON + `\0` goes to `com.xiaomi.mivi2.render` (byte array, `n9.k0.u0`).

The constructor call passes `mMIVIFilter = true`: in this mode the app does **not**
add its own LUT bitmaps (`if (!mMIVIFilter) mLutBitmaps.add(...)`), i.e. it
expects the HAL to do the pixel work.

Gate [observed], `Te.c.o2()`:

```java
public final boolean o2() { return this.f16477e.B8() || Va.b.f17711X; }
// Va/b.java:  f17711X = Wr.g.c("camera.debug.mivi3.output.jpeg", false);
```

`B8()` is a device capability that is false on Aurora, so the property is the
only way to reach the HAL path. Without it the app renders preview *and* still
capture itself (`MCAM_CRE_PreviewRenderEngine`,
`MCAM_FilterFactory: FilterType: LUT_NC(231, ColorLookupFilter(512x512, lut_nc))`,
`MCAM_OpenGl3dUtils: load3dLutTexture ... mCurrentLutName = lut_nc`) and the HAL
LUT file is irrelevant.

The app also materialises the LUT for the HAL at capture time
[observed, `MCAM_MIVILutSaver`]:

```text
MCAM_MIVILutSaver: Write Lut to persist dir: lut_nc.png
```

The destination is `/data/vendor/camera/` (the `lutPath` value), the source is
the APK `res/raw/lut_*` resource of the selected filter.

## 2. The HAL-side consumer

`com.xiaomi.plugin.filter.so` (169 672 B, `ChiNodeEntry`, built from
`vendor/xiaomi/proprietary/mivifwk-aurora/external/odm/plugins/qcom/filter/FilterPlugin.cpp`).

Pipeline membership [observed] in `odm/etc/camera/xiaomi/`:

```text
normalsnapshot.json : ... mialgorithmwrapper MLAWBInstance0 -> filter FilterInstance0
                      -> watermark WatermarkInstance0 -> JpegInstanceFinal ...
leicasnapshot.json  : same position (also after IdealRaw2Yuv/YuvP010ToYuvNV12)
```

It is therefore in the stills path, between the colour-processing nodes and the
JPEG encoder — exactly where the film look must be applied.

Functions [observed, offsets in the shipped `.so`]:

| address | function | behaviour |
| --- | --- | --- |
| `0xc194` | `FilterPlugin::isEnabled(MiaParams)` | `MiMetadata::find("com.xiaomi.mivi2.renderType") != 0` |
| `0xc218` | `FilterPlugin::processRequest(ProcessRequestInfo)` | re-reads `renderType`, reads `com.xiaomi.mivi2.render`, parses the JSON |
| `0xb06c` | `candyProcess(FilterFrameParam*, FilterFrameParam*, VideoFormat, nlohmann::json&)` | reads `lutNames`, builds `CubeLutEffect;cube_strength=...`, calls `videoprocess` (libMiPhotoFilter) with `<dir>/<name>.png` |

Its only metadata import is `MiMetadata::find(char const*)`
(`libcom.xiaomi.metadatautils.so`, `0xb684`), i.e. the plugin resolves the
Xiaomi vendor tags **by name** on every request. This is the runtime consumer
of the render payload; `com.xiaomi.mivi2.filterId` itself is not read by this
plugin - it is the app's bookkeeping/EXIF value (the same `filterId & 0xffff`
indexes the app's filter enum, e.g. `655591 -> 231 -> LUT_NC`).

LUT handling [observed]:

* a name list (`lutNames`) and `CubeLutEffect;cube_strength=%.2f` script;
* LUT files are `512x512` RGB PNGs, 8x8 tiles of a 64x64x64 lattice
  (identical layout to the `odm/etc/camera/videofilter/*.png` and to the
  module's `assets/leica_luts/*.png`);
* the LUT directory used at runtime is the app's `lutPath`
  (`/data/vendor/camera/`); `/mnt/vendor/persist/camera` is the plugin's
  fallback string when the JSON carries no directory.

Cross-check with SELinux [observed, `dmesg`]:

```text
avc: denied { read } for comm="AlgoFwkThd1" name="lut_nc.png" dev="dm-54"
  scontext=u:r:hal_camera_default:s0 tcontext=u:object_r:shell_data_file:s0 tclass=file
```

`AlgoFwkThd1` is the provider's algorithm-framework thread
(`u:r:hal_camera_default`), i.e. the HAL opens the LUT PNG during the capture.

## 3. Injection

Because the LUT is a file that the HAL re-reads for each snapshot, replacing it
replaces the look. There is no `.so` patch, no pipeline JSON edit and no new
node; the stock Negative/Positive picker entries become M3/M9 slots.

Procedure (implemented in `tools/hal-port/engine/install_lut_slot.sh`):

1. `setprop camera.debug.mivi3.output.jpeg true` (app must be restarted; the
   value is read once into a `static final`).
2. Select the slot filter in the app (e.g. *Filters -> Negative* = `LUT_NC` =
   `lut_nc`). The app writes its own `lut_nc.png` at the first capture.
3. Put the custom PNG at `/data/vendor/camera/.slot_<name>.png`, owned
   `root:root`, mode `0444`, SELinux label `u:object_r:vendor_camera_data_file:s0`,
   and bind-mount it over `/data/vendor/camera/<name>.png`.
   The read-only, root-owned source survives the app's `MIVILutSaver` write (the
   write is rejected and logged), while the HAL can still read it.
4. Capture. The JPEG is rendered by the HAL with the injected LUT.

`--restore` unmounts the slot, deletes the source, resets the property and
restarts the provider/app, leaving the stock pipeline.

### Verified results (device 1c4e4dec, OS3.0.304.0.WNAMIXM)

Same scene, same filter (`FilterId 655591`, payload `lutNames:["lut_nc"]`),
only the LUT file changed:

| capture | LUT at `/data/vendor/camera/lut_nc.png` | md5 | mean RGB | saturation |
| --- | --- | --- | --- | --- |
| `base.jpg` 17:57:38 | stock Negative (`lut_nc` from the APK, 219 748 B) | `1717c1a51fc32dc3b82f1ba5bad94d76` | 0.138 / 0.150 / 0.188 | 0.142 |
| `m3.jpg` 17:58:15 | `assets/leica_luts/M3_Monopan.png` (21 772 B) | `167c769a022945ea5bce80985b344209` | 0.122 / 0.122 / 0.122 | **0.000** |
| `t12.jpg` 17:46:16 | solid black 512x512 PNG (843 B) | `da2e6a66c89e3a17973274b5088c85bc` | 0.000 / 0.000 / 0.000 | 0.000 |

`m3.jpg` is byte-for-byte a monochrome JPEG (`max-min` per pixel = 0) while the
filter id and the request payload are unchanged - the only variable is the LUT
file, which proves the HAL applies it.

Restored state: with the property cleared the next capture was
`FilterId 66048`, no `applySettingsForMIVIRender` line, saturation `0.598`, i.e.
the stock pipeline is back.

### Limits

* `camera.debug.mivi3.output.jpeg` must stay set for the HAL path. It is a normal
  system property; the overlay/script can set it from a boot hook. Without it the
  app renders the filter itself and the slot file is ignored.
* the app overwrites `<name>.png` whenever the filter selection changes for the
  first time in a process, hence the read-only bind mount. The preview keeps
  using the in-memory APK bitmap, so preview ≠ capture while a slot is overridden
  (the module should show the injected look in its own UI).
* one slot per stock filter: M3 and M9 need two slots (e.g. Negative -> M3,
  Positive -> M9), or a swap between captures.
* the M9 LUT has to be supplied as a 512x512 RGB PNG (the module already ships
  `leica_luts/130_LeicaNatural.png` for the M9 approximation and
  `leica_luts/M3_Monopan.png` for M3).

## 4. Why frida was not the tool here

The plan was to hook `ChiMetadata::GetTag` / `MiMetadata::find` in the provider.
Two attempts to attach frida-server to `vendor.qti.camera.provider-service_64`
ended with the device rebooting within seconds, and the property
`persist.vendor.camera.immunesystem.protection.switch` is `true` on this unit
[observed]. Treat provider ptrace as blocked by the vendor protection; the
behavioural A/B test above gives the same answer without it.

The hook script is kept in `tools/hal-port/engine/hook_tag_lookup.py` (Frida 17
API, symbol-based) for units where attaching is allowed, but it is **unverified**
on this device.

## 5. Reproduce

```sh
# 0. stock state, app in Photo mode, some scene
adb -s 1c4e4dec shell 'setprop camera.debug.mivi3.output.jpeg true'
adb -s 1c4e4dec shell 'am force-stop com.android.camera'
adb -s 1c4e4dec shell 'am start -n com.android.camera/.Camera'
# 1. Filters -> Negative, then capture once (the app writes lut_nc.png)
# 2. install the M3 LUT into the slot
tools/hal-port/engine/install_lut_slot.sh --serial 1c4e4dec \
    --lut assets/leica_luts/M3_Monopan.png --name lut_nc
# 3. capture again and compare saturation
# 4. restore
tools/hal-port/engine/install_lut_slot.sh --serial 1c4e4dec --restore
```

The screenshots that pin the UI: filter strip at `(1293,2284)` opens it,
`(1218,2215)` selects *Negative* when the strip is at its origin, shutter at
`(719,2785)`.
