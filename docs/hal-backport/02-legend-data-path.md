# Legendary request and processing path

This document follows one M3/M9 capture from the Camera 6.7 app to the plugin
and the snapshot pipeline. Offsets are file offsets for the binaries in
`970/hal_analysis_madrid/binaries/` or the extracted Nezha `odm` tree.

## 1. App request

`[observed]` Camera 6.7, `p638s2/A.java` (`ComponentConfigLegendary`):

* key `pref_legendary_mode_key`, tag `ComponentConfigLegendary`;
* `isSupportMode(256)` returns true for the Leica Essential mode;
* builds **M3 first, then M9**; there is no M10R item in this build;
* `getDefaultValue` returns M9.

`[observed]` `F3/o.java` (`k()`), around line 86, maps the selected component
value to the `legendMode` request byte:

```java
String componentValue = a10.getComponentValue(256);
E0<CaptureRequest.Key<Byte>> key = z0.f45465g0;
byte b;
if (componentValue.equals(VALUE_M9)) {
    b = 1;
} else {
    b = componentValue.equals(VALUE_M3) ? (byte) 2 : (byte) 3;
}
h5.a(key, Byte.valueOf(b));
```

`[observed]` `p399la/z0.java:190` defines
`f45465g0 = a(new Q(4), Byte.class)`, and `p399la/Q.java` case 4 returns
`com.xiaomi.sessionparams.legendMode`. The request key is a
`CaptureRequest.Key<Byte>`.

So the app contract is:

| UI style | `com.xiaomi.sessionparams.legendMode` |
| --- | ---: |
| M9 (CCD) | 1 |
| M3 (B&W) | 2 |
| M10R (CMOS), when present | 3 |

`[observed]` `z0.f45442N` is the separate
`com.xiaomi.sessionparams.stylizationType` key. It is set from the colour-style
component (`C3304j.o()` / `C4982m`), not from the Legendary selection. The
values normally seen are 0/1/2 for the classic/vivid Leica styles.

## 2. Metadata tags

`[observed]` `libmetadataHelper.so`:

* `metahelper::metadataHelperBase::GetLegendMode` at `0x40bec` passes
  `w3 = 0x1fc` (508) to `GetMetaData`.
* `GetStylizationType` at `0x1ebbc` passes `w3 = 0x1d` (29).

`[observed]` Nezha `com.xiaomi.plugin.mileicafilter.so`
(`MiLeicaFilterPlugin`):

* `initialize` at `0xc620` reads tag 29 through
  `MiMetadata::find("com.xiaomi.sessionparams.stylizationType")` and stores the
  byte at `this+0x25` (`0xc748`–`0xc75c`).
* It reads tag 508 through
  `MiMetadata::find("com.xiaomi.sessionparams.legendMode")` and stores the byte
  at `this+0x26` (`0xc760`–`0xc78c`).
* `isEnabled` at `0xd714` repeats both reads: tag 29 into `w20`, tag 508 into
  `this+0x26`.

The tag strings are in `.rodata` at:

| String | Offset |
| --- | ---: |
| `com.xiaomi.sessionparams.stylizationType` | `0x6dd7` |
| `com.xiaomi.sessionparams.legendMode` | `0x636e` |

## 3. Plugin parameter selection

`[observed]` `MiLeicaFilterPlugin::initResource` at `0xcc54` selects a
`ParamMode` from `this+0x26` (the legend byte) and `this+0xd0` (the
process/snapshot flag copied from `CreateInfo+0x44` in `initialize`):

| `this+0x26` | condition | selected mode |
| ---: | --- | ---: |
| 0 | — | 0 (no parameter set) |
| 1 | `this+0xd0 == 0` | 2 |
| 1 | otherwise | 1 |
| 2 | — | 3 |
| 3 | — | 0 on Nezha (M10R unsupported) |

`[observed]` The Nezha plugin contains these parameter file names:

| File | String offset |
| --- | ---: |
| `leica_filter_param_m9_snapshot.bin` | `0x690a` |
| `leica_filter_param_m3.bin` | `0x6c66` |
| `leica_filter_param.bin` | `0x720d` |
| `leica_filter_param_m9_preview.bin` | `0x78ad` |

`[unknown]` The numeric `ParamMode` → filename mapping is not established.
The static initializer tables at `0x13650`/`0x17348` are not the parameter map;
the table at `0x20008`/`0x20118` is a camera-request error-string table. Do not
equate the filename order with the enum values.

`[observed]` Madrid's plugin adds the M10R branches and the two M10R parameter
files. Nezha returns 0 for legend byte 3, which is why the 17 Ultra cannot
render M10R even if the app sends it.

## 4. Style-transfer plugin

`[observed]` `legendST.so` (`com.xiaomi.plugin.legendST`) is the
`LEGEND_STYLETRANS` pipeline plugin. It links `libmialgo_styletrans.so` and
`libmetatagwrapper.so`.

`[observed]` Nezha `libmialgo_styletrans.so` (13,668,744 bytes) reads
`/odm/etc/camera/styletrans_params.json`. That file names:

| Model | Size |
| --- | ---: |
| `styletrans_low.minn` | 6,698,160 compressed / 10,005,294 uncompressed |
| `styletrans_high.minn` | 6,700,350 compressed / 9,939,758 uncompressed |
| `styletrans_colorfix.minn` | 6,953,155 compressed / 8,398,942 uncompressed |

`[observed]` Madrid uses a different model set: four M9/M10R models
(`styletrans_high_m10r.minn`, `styletrans_high_m9.minn`,
`styletrans_low_m10r.minn`, `styletrans_low_m9.minn`), about 39.7–40.0 MB each,
selected from `libmialgo_styletrans.so` by object fields. See
[`../18-pro-max-hal/02-legend-m10r.md`](../18-pro-max-hal/02-legend-m10r.md#style-transfer-models-m9-vs-m10r).

`[inferred]` The Nezha style-transfer library is the right donor for M3/M9 on
Aurora. The Madrid library is not: it needs `libmage.so`, which Aurora does not
have, and its models are M9/M10R-specific.

## 5. Snapshot pipeline

`[observed]` Nezha `legendsnapshot.json` has 29 nodes and 41 links. The relevant
chain is:

```text
BayerRaw2YuvInstanceForRGB
  -> StyleTransInstance0            (com.xiaomi.plugin.legendST)
  -> DepurpleInstance0
  -> WideLDCInstance0
  -> LDCInstance0
  -> LeicaFilterInstance0           (com.xiaomi.plugin.mileicafilter)
  -> WatermarkInstance0
  -> JpegInstanceFinal ...
```

`[observed]` Aurora `leicasnapshot.json` has 39 nodes and 55 links. Its chain is:

```text
YuvReprocessInstance0
  -> DepurpleInstance0
  -> LDCInstance0
  -> MiAIDeblurInstance0
  -> MLAWBInstance0
  -> FilterInstance0                (com.xiaomi.plugin.filter)
  -> WatermarkInstance0
  -> JpegInstanceFinal ...
```

The port inserts:

```text
FilterInstance0 -> LeicaFilterInstance0 -> WatermarkInstance0
YuvReprocessInstance0 -> StyleTransInstance0 -> DepurpleInstance0
```

Both new nodes are single-input/single-output YUV420_NV12. The script
`tools/hal-port/aurora/patch_leicasnapshot.py` performs exactly these two edge
splits and refuses to guess if the expected edge is missing.

`[inferred]` This ordering is deliberately conservative: it keeps Aurora's
existing reprocess/deblur/MLAWB/filter chain and adds the Legendary filter last,
so the normal Leica path is untouched when the plugin disables itself. A more
faithful port would move `LeicaFilterInstance0` between LDC and Watermark, as
Nezha does, but that requires re-validating Aurora's deblur/MLAWB stages.

## 6. The metadata gap on Aurora

`[observed]` Aurora's `camera.xiaomi.so` and `libmialgoengine.so` contain no
`com.xiaomi.sessionparams.legendMode` string. Nezha's engine does. Aurora's
`camera.qcom.so` does read `com.xiaomi.sessionparams.stylizationType`.

`[inferred]` On Aurora the app can still set the `legendMode` request key, but
the Aurora engine has no code that copies it into `MiMetadata` tag 508. The
Nezha `mileicafilter` would therefore read 0 and disable itself. This is the
central HAL gap, not the plugin file itself.

Two ways to close it:

### Option A — patch the plugin to read `stylizationType`

`[observed]` Both plugin reads of the legend string can be redirected with a
single AArch64 immediate change:

| Function | Address | Current | Patched |
| --- | --- | --- | --- |
| `initialize` | `0xc768` | `add x1, x1, #0x36e` | `add x1, x1, #0xdd7` |
| `isEnabled` | `0xd780` | `add x1, x1, #0x36e` | `add x1, x1, #0xdd7` |

The `adrp x1, 0x6000` base stays; `0x6000 + 0xdd7 = 0x6dd7`, the
`stylizationType` string. After the patch, `this+0x26` receives the
`stylizationType` byte, and `initResource` maps 1 → M9, 2 → M3.

`[inferred]` The module then has to make `stylizationType` equal the Legendary
value for a Legendary capture. This collides with the normal Leica
classic/vivid values 0/1/2. Two sub-options:

* use 1/2 directly and keep `FilterInstance0` (may double-apply the Leica
  look; test first);
* use distinct values (for example `0x11`/`0x12`) and extend the
  `initResource` mapping, which needs a second binary patch.

### Option B — patch the Aurora engine

Add a `legendMode` read in the Aurora engine and store it in `MiMetadata` tag
508. This is a much larger change: the engine is 834 KB, the metadata population
path is not symbol-annotated, and a mistake breaks every capture. It is not the
first choice.

## 7. What is still unknown

* Whether Aurora's linker accepts the Nezha plugin's `LIBLOG`/`LIBEGL` symbol
  version requirements.
* Whether Aurora's older `mialgo2::Pipeline` constructor and the Nezha plugin's
  provider expectations are compatible at runtime.
* Whether the Nezha `libmialgo_styletrans` / `libLMSceneSeg` pair works on
  SM8650; the models were built for the Nezha generation.
* The correct `ParamMode` enum values and the exact `this+0xd0` process-mode
  semantics.
* Whether `mileicafilter`/`legendST` need the `Legendary` mode to be present in
  the session parameters before the pipeline instantiates the nodes.
