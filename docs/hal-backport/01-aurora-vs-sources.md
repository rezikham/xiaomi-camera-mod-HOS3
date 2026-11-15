# Aurora (14 Ultra) versus the Nezha/Madrid HAL

All paths are relative to the extracted partition roots:

* `970/extracted_aurora/odm` and `970/extracted_aurora/vendor` — Aurora HAL.
* `970/extracted_nezha/odm` — Nezha donor files.
* `970/extracted_madrid/odm` — Madrid donor files.

Aurora was also extracted from `970/super_aurora.img`; its `odm`/`vendor` match
`extracted_aurora` by SHA-256 for the files checked here.

## Device and camera generation

| Item | Aurora (14 Ultra) | Nezha (17 Ultra) | Madrid (18 Pro Max) |
| --- | --- | --- | --- |
| Codename | `aurora` / `aurorapro` | `nezha` | `madrid` |
| Vendor/ODM in this dump | Android 16 / `OS3.1` vendor tree | Nezha vendor/ODM tree | Android 17 / `OS4.0.29.0.XEOCNXM` |
| Camera UI used for the Legendary work | 6.7 target, not the 6.4 APK in the Aurora image | 6.7 | 6.8 |
| SoC | SM8650 | SM8850 | SM8975 |

The 6.4 APK inside the extracted Aurora product image is not the app under
test. The 6.7 layout used by Nezha/Madrid is the one that exposes the
Legendary M3/M9 list.

## Camera HAL architecture

Aurora is not just an older build of the same HAL. The top-level module is
different:

| File | Aurora | Nezha | Madrid |
| --- | --- | --- | --- |
| `odm/lib64/hw/camera.xiaomi.so` | **3,435,168** | absent | absent |
| `odm/lib64/hw/camera.qcom.so` | 17,288,320 | 12,971,336 | 517,752 |
| `odm/lib64/hw/camera.qcom.core.so` | **absent** | 24,137,800 | 19,729,664 |
| `odm/lib64/hw/camera.qcom.sm8650.so` | 18,891,856 | — | — |
| `odm/lib64/hw/camera.qcom.sm8850.so` | — | 8,626,544 | — |
| `odm/lib64/hw/camera.qcom.sm8975.so` | — | — | 9,180,936 |
| `odm/lib64/libmialgoengine.so` | 834,440 | 1,939,520 | 2,796,816 |
| `odm/lib64/libmialgoenginecustom.so` | 148,504 | — | — |
| `odm/lib64/libmialgoengine_custom.so` | — | 446,264 | 613,336 |
| `odm/lib64/libcom.xiaomi.pluginutils.so` | 405,104 | present | present |

The important consequence is that Aurora's plugin loading and pipeline engine
live behind `camera.xiaomi.so` + `libmialgoengine.so`, while Nezha/Madrid use
`camera.qcom.core.so` + their own engine. A plugin overlay must target the
Aurora engine, not replace the Nezha module wholesale.

## Plugins

Aurora has **39** plugins under `odm/lib64/camera/plugins`; Nezha has **44**;
Madrid has about **61**.

Missing on Aurora but present on Nezha/Madrid:

* `com.xiaomi.plugin.mileicafilter.so` — Leica Essential M3/M9 parameters;
* `com.xiaomi.plugin.legendST.so` — neural style transfer;
* `com.xiaomi.plugin.videofilter.so`;
* `com.xiaomi.plugin.mialgoanchor.so`, `mialgooptics`, `dsac`, `filmnoise`,
  `singlebokehpreview`.

`com.xiaomi.plugin.legendCT.so` is present on Madrid only. It is the colour
transform used by the M10R/CMOS path, not by M3/M9 on Nezha.

Aurora does have the generic `filter`, `depurple`, `ldc`, `gainmap`, `jpegrAggr`,
`swjpegencode`, `watermark`, `movieeffect` and `offlinepostproc` plugins.

## Configuration

| Path | Aurora | Nezha | Madrid |
| --- | --- | --- | --- |
| `odm/etc/camera/leica_filter_param*.bin` | absent | generic, M3, M9 preview/snapshot | plus M10R preview/snapshot |
| `odm/etc/camera/styletrans/` | absent | 3 models + `styletrans_params.json` | 4 M9/M10R models + params |
| `odm/etc/camera/xiaomi/leicasnapshot.json` | present, 39 nodes | — | — |
| `odm/etc/camera/xiaomi/legendsnapshot.json` | absent | present, 29 nodes | present, 29 nodes |
| `odm/etc/camera/videofilter/` | 24 files, no full LUT set | 69 files | 93 files |

Aurora's `leicasnapshot.json` is the “Leica classic/vivid” still pipeline. It
contains `FilterInstance0` (`com.xiaomi.plugin.filter`) after `MLAWBInstance0`
and before `WatermarkInstance0`; it has no `mileicafilter` or `legendST` node.
That is the insertion point used by the port.

## ABI and symbol surface

The port is plausible because these observed exports match:

* Aurora `libmialgoengine.so` exports
  `mialgo2::MPluginModule::getInstance()` and
  `getPluginWraperByPluginName(std::string)`.
* Aurora `libcom.xiaomi.pluginutils.so` exports
  `mialgo2::ProviderManager::add(Provider*)`, `registerProvider`,
  `clearProviders`, and the rest of the provider API.
* Aurora and Nezha `PluginWraperProvider::PLUMA_INTERFACE_VERSION` and
  `PLUMA_INTERFACE_LOWEST_VERSION` are both **1**.
* The Nezha `mileicafilter` and `legendST` plugins import only
  `ProviderManager::add` from the engine side, plus metadata/debug/cameraopt
  helpers. They do not import `MPluginModule::getPluginProvider`.

What does **not** match:

* Aurora has no `libmetatagwrapper.so`, `libcom.xiaomi.cam.miexifmgr.so`,
  `libcameraopt_stub.so`, `libmialgo_styletrans.so` or `libLMSceneSeg.so` in its
  ODM/vendor tree.
* Nezha `libmialgo_styletrans.so` links `libLMSceneSeg.so` (SM8850 DSP model
  runtime) and `libcdsprpc.so`/`libOpenCL.so`; Aurora has the latter two, but
  not the former.
* The Nezha plugin declares `LIBLOG` version 4 and `LIBEGL` version 5 in its
  `.gnu.version_r`. Aurora's liblog/libEGL export the version names, but the
  version indices differ. Whether the Android linker accepts this without a
  repack must be checked on the device; it is the first runtime risk.

## Why the stock Aurora HAL cannot render M3/M9

`[observed]` A string search over every Aurora `.so` found no `legendMode`,
`LegendMode`, `LegendM10R`, `mileicafilter`, `legendST`, `legendCT`, `M10R` or
`leica_filter_param` producer. Aurora's `camera.qcom.so` does contain classic
Leica strings (`leica mode:%d`, `leica classic style`, `leica vivid style`) and
reads `com.xiaomi.sessionparams.stylizationType`, but that is the classic/vivid
filter path, not Legendary M3/M9.

`[observed]` The Aurora engine's `leicasnapshot.json` uses
`com.xiaomi.plugin.filter`, not `mileicafilter`/`legendST`.

`[inferred]` Therefore adding a picker item or setting the Legendary mode in the
app cannot produce a Legendary image on stock Aurora. The missing processing is
in the HAL, and a UI-only module change is not sufficient.

## What the port has to add

Minimum viable M3/M9 port:

1. `com.xiaomi.plugin.mileicafilter.so` and `com.xiaomi.plugin.legendST.so`;
2. `libmialgo_styletrans.so` and `libLMSceneSeg.so`;
3. `libcom.xiaomi.cam.miexifmgr.so`, `libcameraopt_stub.so`,
   `libmetatagwrapper.so`;
4. `leica_filter_param.bin`, `_m3.bin`, `_m9_preview.bin`,
   `_m9_snapshot.bin`;
5. `styletrans_params.json` and the three Nezha models;
6. `LeicaFilterInstance0` and `StyleTransInstance0` in Aurora's
   `leicasnapshot.json`.

That is exactly what `tools/hal-port/aurora/build_bundle.sh` assembles.
