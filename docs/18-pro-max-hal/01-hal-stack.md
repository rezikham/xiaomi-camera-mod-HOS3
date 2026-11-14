# `madrid` HAL stack and inventory

Device: Xiaomi 18 Pro Max, `M154FF`, codename `madrid`.
Firmware: HyperOS `OS4.0.29.0.XEOCNXM`, Android 17 (SDK 37), security patch
`2026-09-01`. Camera package: `com.android.camera` `6.8.002230.0`
(versionCode `680022300`).

Evidence files:

```text
970/device_dump/runtime/getprop.txt
970/device_dump/runtime/dumpsys_media.camera.txt
970/device_dump/camera_apk/18PM_camera_6.8.002230.0.apk
970/extracted_madrid/{odm,vendor,system,product,mi_ext,system_dlkm,vendor_dlkm}
```

## Device identity

[observed] From `getprop.txt`:

| Property | Value |
| -------- | ----- |
| `ro.product.marketname` | `Xiaomi 18 Pro Max` |
| `ro.product.model` | `M154FF` |
| `ro.product.device` | `madrid` |
| `ro.build.version.release` | `17` |
| `ro.build.version.sdk` | `37` |
| `ro.build.version.incremental` | `OS4.0.29.0.XEOCNXM` |
| `ro.mi.os.version.name` | `OS4.0` |
| `ro.mi.os.version.code` | `4` |
| `ro.mi.os.soc.vendor` | `qcom` |
| `ro.build.version.security_patch` | `2026-09-01` |

## Provider and camera devices

[observed] From `dumpsys media.camera`:

| Item | Value |
| ---- | ----- |
| Provider | `android.hardware.camera.provider.ICameraProvider/vendor_qti/0-0` |
| Provider version | `v2.0` |
| HAL device | `device@1.1/vendor_qti/0` … `/8` |
| HAL device version | `v1.4` |
| Camera devices | `9` (`Device 0` … `Device 8`) |

The provider exposes nine camera devices. The static information dump has a
section per device:

```text
== Camera Provider HAL android.hardware.camera.provider.ICameraProvider/vendor_qti/0-0 (v2.0, remote) static info: 9 devices: ==
== Camera HAL device device@1.1/vendor_qti/0 (v1.4) static information: ==
...
== Camera HAL device device@1.1/vendor_qti/8 (v1.4) static information: ==
```

### `stylizationTypeMask` per device

[observed] `xiaomi.capabilities.stylizationTypeMask (81d2000e): int32[1]`:

| HAL device | Mask | Binary | Cameras |
| ---------- | ---- | ------ | ------- |
| `vendor_qti/0` | `14` | `1110` | bits 1, 2, 3 |
| `vendor_qti/1` | `12` | `1100` | bits 2, 3 |
| `vendor_qti/2` | `14` | `1110` | bits 1, 2, 3 |
| `vendor_qti/3` | `14` | `1110` | bits 1, 2, 3 |
| `vendor_qti/4` | `12` | `1100` | bits 2, 3 |
| `vendor_qti/5` | `14` | `1110` | bits 1, 2, 3 |
| `vendor_qti/6` | `12` | `1100` | bits 2, 3 |
| `vendor_qti/7` | — | — | no characteristic |
| `vendor_qti/8` | — | — | no characteristic |

`14` means bit 3 is already set, so HyperCameraMod’s
`mask | (1 << 3)` hook is a no-op on this phone. The 12/14 split is consistent
with different logical cameras (for example, front versus rear) and does not
change the conclusion.

### `legendMode.capsInfo`

[observed] `com.xiaomi.legendMode.capsInfo (82010000): byte[1120]` is present on
devices `0`, `1`, and `2`. The first words decode as `version=1`, `count=6`,
then focal lengths `35`, `75`, and four zero entries, then a per-focal list
size, per-focal mode lists, a per-focal aperture count, and float aperture
stops. The float runs observed include:

```text
2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0
1.4, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0
```

The exact parser is in Camera 6.8 `p402l9/C4034g.java`, method
`getLegendBokehCapsInfo` (source lines around 3268–3323). It reads:

1. `int version`
2. `int legendFocalLength` (count)
3. one `int EquivalentFocalLength` per focal length
4. `int madridLegendModeListMax`
5. `focalCount × max` mode `int`s
6. `int legendApertureListMax`
7. `focalCount × max` `float` apertures

This is the HAL-side capability that makes the 35 mm / 75 mm Leica Essential
bokeh and aperture controls available. The 17 Ultra report said
`com.xiaomi.legendMode.capsInfo` was absent there; on `madrid` it is present.

## HAL binaries

[observed] `970/hal_analysis_madrid/binaries/` (sizes in bytes):

| File | Size | Role |
| ---- | ---- | ---- |
| `camera.qcom.core.so` | 19,729,664 | CamX/CHI core |
| `camera.qcom.sm8975.so` | 9,180,936 | SoC camera HAL (SM8975) |
| `com.qti.chi.override.so` | 6,423,544 | CHI vendor override |
| `camera.qcom.so` | 517,752 | HAL entry |
| `camera.qcom.ncs.so` | 52,208 | NCS node |
| `vendor.qti.camera.provider-service_64` | 120,008 | Provider service |
| `vendor.xiaomi.hardware.dynamiccameraserver` | 52,216 | Dynamic camera server |
| `libmialgoengine.so` | 2,796,816 | MiAlgo pipeline engine |
| `libmialgoengine_custom.so` | 613,336 | Pipeline customization |
| `libAncHumanPreviewBokeh.so` | 8,671,560 | Preview bokeh |
| `libmiphone_capture_bokeh.so` | 4,916,840 | Capture bokeh |
| `libmialgo_monopan.so` | 18,858,832 | Monopan |
| `libmialgo_styletrans.so` | 304,712 | Neural style transfer |
| `liblegendmsg.so` | 70,880 | Legend message/helper |
| `libmetadataHelper.so` | (in `odm/lib64`) | Metadata get/set helpers |
| other `libmialgo_*` | — | AISP, AIUR, HDR, LDC, SAT, etc. |

The full list and SHA-256 values are in
`970/hal_analysis_madrid/reports/00_binaries.txt` and
`970/hal_analysis_madrid/reports/02_binaries_sha256.csv`.

### Related HAL libraries outside `binaries/`

| Path | Size | Note |
| ---- | ---- | ---- |
| `odm/lib64/libmetadataHelper.so` | — | exports `GetLegendMode` and `GetStylizationType` |
| `odm/lib64/libAncHumanPreviewBokeh.so` | 8,671,560 | same family as 17 Ultra |
| `odm/lib64/camera/plugins/` | 61 files | processing plugins |
| `odm/lib64/camera/dynamicplugins/` | 10 files | capture bokeh / depth / LDC |

## Xiaomi camera plugins

[observed] `odm/lib64/camera/plugins/` contains 61 files. The ones relevant to
this investigation are:

| Plugin | Size | Purpose |
| ------ | ---- | ------- |
| `com.xiaomi.plugin.legendCT.so` | 85,608 | Legend colour transform (`Legend-COLORTRANS`) |
| `com.xiaomi.plugin.legendST.so` | — | Legend style transfer (`LEGEND_STYLETRANS`, loads `libmialgo_styletrans.so`) |
| `com.xiaomi.plugin.mileicafilter.so` | 168,464 | Leica filter parameter selection and LUT/shading application |
| `com.xiaomi.plugin.filter.so` | — | generic filter |
| `com.xiaomi.plugin.videofilter.so` | — | video LUT filters |
| `com.xiaomi.plugin.gainmap.so` / `gainmapPostProc.so` | — | gain-map pipeline |
| `com.xiaomi.plugin.mialgoanchor.so` | — | anchor selection |
| `com.xiaomi.plugin.depurple.so`, `ldc.so`, `filmnoise.so` | — | pipeline stages seen in `legendsnapshot.json` |
| `com.xiaomi.plugin.offcammfnr.so`, `offcamb2y.so`, `offcamjpeg.so` | — | offline MFNR / Bayer-to-YUV / JPEG |

The complete directory listing is reproduced in
[05-evidence.md](05-evidence.md#plugin-inventory).

### Dynamic plugins

[observed] `odm/lib64/camera/dynamicplugins/`:

```text
com.xiaomi.plugin.arcsoftAP.so
com.xiaomi.plugin.arcsoftPT.so
com.xiaomi.plugin.arcsoftSSSH.so
com.xiaomi.plugin.GestureEffects.so
com.xiaomi.plugin.capbokeh.so
com.xiaomi.plugin.capdepth.so
com.xiaomi.plugin.capdepthrefine.so
com.xiaomi.plugin.ldc.so
com.xiaomi.plugin.mialgoallinone.so
com.xiaomi.plugin.midualcamallinone.so
```

## Configuration

[observed] Sizes and notable files:

| Path | Size | Contents |
| ---- | ---- | -------- |
| `odm/etc/camera/` | 4.7 GB | models, JSON, tuning, LUTs |
| `vendor/etc/camera/` | 640 KB | vendor tuning |
| `odm/etc/camera/leica_filter_param.bin` | 1,596,788 | generic Leica filter params |
| `odm/etc/camera/leica_filter_param_m3.bin` | 93,330 | M3 params |
| `odm/etc/camera/leica_filter_param_m9_preview.bin` | 1,686,542 | M9 preview params |
| `odm/etc/camera/leica_filter_param_m9_snapshot.bin` | 1,686,542 | M9 snapshot params |
| `odm/etc/camera/leica_filter_param_m10r_preview.bin` | 1,598,108 | M10R preview params |
| `odm/etc/camera/leica_filter_param_m10r_snapshot.bin` | 1,598,108 | M10R snapshot params |
| `odm/etc/camera/xiaomi/legendsnapshot.json` | 25,374 | Legend still-capture pipeline |
| `odm/etc/camera/xiaomi/legendmonopansnapshot.json` | 22,489 | Legend Monopan pipeline |
| `odm/etc/camera/xiaomi/config/custom/algofeature/legendmode.json` | 2,640 | `encrypted_data: <base64>` only |
| `odm/etc/camera/videofilter/` | 93 files | film LUT `.cube` + preview `.png` |

`legendmode.json` is not plain JSON despite the extension: it is the single
line `encrypted_data: <base64>`. It is not usable as a plain configuration
table without the vendor decryption key.

## Comparison with `aurora` and `nezha`

[observed]

| Evidence | `aurora` (14 Ultra) | `nezha` (17 Ultra) | `madrid` (18 Pro Max) |
| -------- | ------------------- | ------------------ | --------------------- |
| `libmialgoengine.so` size | 834,440 | 1,939,520 | 2,796,816 |
| `LegendModeM3` / `M9` / `M10R` strings | absent | absent | present |
| `com.xiaomi.plugin.legendCT.so` | absent | absent | present |
| `com.xiaomi.plugin.legendST.so` | absent | present | present |
| `com.xiaomi.plugin.mileicafilter.so` | absent | present | present |
| M10R strings in `mileicafilter` | n/a | absent | `M9/M10R bokeh snapshot`, `use pre param for M10R` |
| `libAncHumanPreviewBokeh.so` | absent | present | present |
| Preview bokeh engine | `odm/lib/rfsa/adsp/libmialgo_mc_bokeh_cdsp_skel.so` | `odm/lib64/libAncHumanPreviewBokeh.so` | `odm/lib64/libAncHumanPreviewBokeh.so` |
| `com.xiaomi.legendMode.capsInfo` | absent | absent | present |
| `stylizationTypeMask` bit 3 | not native (module forces it) | — | native (`14`/`12`) |

This is the core of the model difference: the 18 Pro Max has the complete
Legend/M10R software stack, not just an app-side entry. The 14 Ultra has a
different preview-bokeh engine and no Legend plugins, which is why Original
portrait and Leica Essential fail there.

## How to re-dump the stack

On a rooted phone (the original dump did not have root; these are for future
verification):

```bash
adb shell dumpsys media.camera > dumpsys_media.camera.txt
adb shell getprop > getprop.txt
adb shell dumpsys package com.android.camera > dumpsys_package_com.android.camera.txt
adb pull /odm/etc/camera
adb pull /odm/lib64/camera/plugins
adb pull /vendor/lib64/hw/camera.qcom.core.so
```

Then repeat the checks in [05-evidence.md](05-evidence.md).
