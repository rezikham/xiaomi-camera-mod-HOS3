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
  (`/data/vendor/camera/`). The plugin also carries the literal
  `/mnt/vendor/persist/camera`; [inferred] that is the fallback when the JSON
  has no directory - a black LUT placed there was *not* picked up while the
  `/data/vendor/camera` one was.

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

The module's own DCIM watcher post-processes saved JPEGs, so the pulled files
were verified against the on-device hashes *after* the test
(`af11abb3…` base, `6c526e63…` m3, `9a107b00…` scripted); they match, i.e. the
numbers above are the raw HAL outputs and not the watcher's work.

Restored state: with the property cleared the next capture was
`FilterId 66048`, no `applySettingsForMIVIRender` line, saturation `0.598`, i.e.
the stock pipeline is back.

### 3b. Module integration: the gate without root

The camera reads `camera.debug.mivi3.output.jpeg` once into a static field
(`f16477e.B8() || Va.b.f17711X`, `Va.b.f17711X = SystemProperties.getBoolean(...)`).
A `SystemProperties` hook installed before the camera class initialises is
therefore equivalent to `setprop`, and needs no root at capture time.

`ModuleMain.installHalGateHook()` answers that key with `true` while the module
setting **Render M3/M9 in the camera engine (HAL LUT slot)**
(`hal_lut_slot_enabled`) is on. Only the LUT *file* needs root, once, to land in
`/data/vendor/camera`; the module never writes it.

`tools/hal-port/engine/leica_look.sh` is the root side:

```sh
leica_look.sh m3            # Monopan LUT into lut_nc + gate property + restarts
leica_look.sh m9            # Leica Natural LUT into the same slot
leica_look.sh m3 --no-gate  # leave the gate to the module setting
leica_look.sh off           # restore the stock slot and property
leica_look.sh status
```

Three traps found while wiring it:

* **The camera provider caches the decoded LUT per process.** Swapping the file
  with the same slot name kept rendering the previous look (M9 stayed perfectly
  monochrome). `leica_look.sh` therefore restarts `vendor.camera-provider`
  (`setprop ctl.restart vendor.camera-provider`) after every install, and the
  camera app so it re-reads the gate.
* **The app's filter strip scrolls.** `filter_item_parent` bounds move; with the
  selected chip in view *Negative* was at `(1217,2235)` and its node reads
  `content-desc="Negative, Selected"`. Select it by reading the dump, not by a
  hard-coded coordinate.
* **A forced `camera.feature.compat_fallback=1` overrides the module setting.**
  The property won over the disabled switch, so the DCIM watcher kept
  re-rendering plain captures as M9 (`filterId 66048`, saturation 0.63, module
  log `LUT applying leica_luts/130_LeicaNatural.png`). Clear it before measuring
  a HAL-only capture; both the watcher and the `Bitmap.compress` hook now bail
  out unless the compatibility fallback is on.

A/B with the gate supplied only by the module (`getprop` empty), same scene,
same filter `655591`, provider restarted between captures:

| capture | LUT in `lut_nc` | mean RGB | \|R-G\| | \|G-B\| | saturation |
| --- | --- | --- | --- | --- | --- |
| `ab_m3.jpg` 18:23:02 | `M3_Monopan.png` (21 772 B) | 33.36 / 33.36 / 33.36 | 0.0 | 0.0 | **0.000** |
| `ab_m9.jpg` 18:23:49 | `130_LeicaNatural.png` (77 354 B) | 44.91 / 43.91 / 59.98 | 1.99 | 16.08 | **0.310** |

Both at 3072x4096 with `FilterId 655591`; the module logged
`LUT candidate ... legend=0` and `applied` never appeared, i.e. the file the app
saved was untouched and the colour came from the engine.

Final restored state on `1c4e4dec`: `leica_look.sh off`, the zero-byte slot
placeholder removed, `camera.debug.mivi3.output.jpeg`,
`camera.feature.compat_fallback` and `camera.feature.lut_style` cleared, the
module's *HAL LUT slot* switch off, 0 slot mounts, provider running. The next
stock capture was `FilterId 66048`, saturation 0.623, with **0** module
post-processing lines in logcat.

### Limits

* `camera.debug.mivi3.output.jpeg` must be true for the HAL path. Either set it as
  a normal system property or let the module answer it
  (`hal_lut_slot_enabled`, section 3b). Without it the app renders the filter
  itself and the slot file is ignored.
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
# with the module: enable "Render M3/M9 in the camera engine (HAL LUT slot)",
# then use the root helper, which installs the LUT and restarts both processes
tools/hal-port/engine/leica_look.sh m3
# -> Filters -> Negative, capture

# without the module (gate from the property)
adb -s 1c4e4dec shell 'setprop camera.debug.mivi3.output.jpeg true'
adb -s 1c4e4dec shell 'am force-stop com.android.camera'
adb -s 1c4e4dec shell 'am start -n com.android.camera/.Camera'
tools/hal-port/engine/install_lut_slot.sh --serial 1c4e4dec \
    --lut assets/leica_luts/M3_Monopan.png --name lut_nc
# restore
tools/hal-port/engine/leica_look.sh off
```

`install_lut_slot.sh` does not restart the provider; run
`setprop ctl.restart vendor.camera-provider` (or use `leica_look.sh`) or the
previous LUT stays cached.

The screenshots that pin the UI: filter strip at `(1293,2284)` opens it; the
*Negative* chip moves with the strip scroll (read the `filter_item_parent`
bounds from `uiautomator dump`), and the shutter is at `(719,2785)`.

## 6. The stock app's Essential Moment mode is inert on Aurora

The 6.7 app on Aurora exposes an **Essential Moment / "Leica Essential"** panel
with `M3` and `Modern vintage` chips, and the mode does open
(`LegendaryModule`, `mid = 256`, `MCAM_LegendaryModeUI: items size = 1`). It was
measured twice on the same scene, M3 selected (`pref_legendary_mode_key = M3`):

| state | app log | capture |
| --- | --- | --- |
| gate off | `applyMIVIRenderType: 0`, `legendmode null` | 3072x4096, filterId 66048, sat 0.4591 |
| `camera.debug.mivi3.output.jpeg=true`, app restarted | `applyMIVIRenderType: 0`, `legendmode null` | 3072x4096, filterId 66048, sat 0.4177 |

So the gate does **not** make Essential Moment use the engine, and the app does
not put `legendmode` in the request either. There is no `com.xiaomi.mivi2.render`
payload at all in this mode (0 matching log lines), which is why the stock filter
node has nothing to apply: the Legendary path asks for nodes and parameter files
that Aurora does not have, and falls back to a plain capture.

Consequence: on Aurora, M3/M9 can only be rendered through the filter/LUT slot
above (Photo mode, Negative filter), not through Essential Moment. The
`ModeUI` panel exists but its selection has no processing behind it.

The standalone **Moment App** app (`com.martin.essentialmoments`) is the
opposite case: it ships the real pipeline (`libmialgo_monopan.so`,
`libm9base.so`, `legend18/leica_filter_param_m{3,9,m10r}*.bin`) and produced a
verified exact-grayscale 3072x4096 M3 capture on Aurora, then crashed in the
spawned Monopan helper (`posix_spawn` -> `SIGSEGV` in `libm9base.so`); the JPEG
was already saved. It is an app-side reference, not a HAL path.

