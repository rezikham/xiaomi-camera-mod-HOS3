# Xiaomi 18 Pro Max (`madrid`) camera HAL — reverse-engineering notes

This directory documents the camera hardware layer of the Xiaomi 18 Pro Max
(`M154FF`, codename `madrid`) and what it means for HyperCameraMod.

Source firmware: HyperOS `OS4.0.29.0.XEOCNXM`, Android 17
(fingerprint `Xiaomi/madrid/madrid:17/CP2A.260605.016/OS4.0.29.0.XEOCNXM:user/release-keys`),
Xiaomi Camera `6.8.002230.0`.

All work is static analysis of the shipped firmware and APK. No on-device
tracing was possible (the dump came from a production build with no root), so
every runtime claim below is marked as observed in `dumpsys`, observed in a
binary, inferred, or unknown.

## Documents

| Document | Contents |
| -------- | -------- |
| [01-hal-stack.md](01-hal-stack.md) | Partitions, provider/device versions, camera count, HAL and plugin inventory, configuration files, comparison with `aurora` (14 Ultra) and `nezha` (17 Ultra). |
| [02-legend-m10r.md](02-legend-m10r.md) | Leica Essential / Legend mode, `legendMode`, MiMetadata tag 508, `stylizationType` tag 29, `mileicafilter` parameter selection, `legendCT`/`legendST`, capture pipelines, `legendMode.capsInfo`, M3/M9/M10R in Camera 6.8. |
| [03-film-filters.md](03-film-filters.md) | LUT inventory in `odm/etc/camera/videofilter`, filter ordinals/IDs, Camera 6.8 resource names, cloud-filter IDs, module profile status. |
| [04-hypercameramod.md](04-hypercameramod.md) | What HyperCameraMod currently does on 6.8/madrid, what it does not need to do, exact class/method/field mappings, test plan. |
| [05-evidence.md](05-evidence.md) | Commands that reproduce each finding, file hashes, and the artifact inventory. |

## Result in one page

| Feature | HAL support on `madrid` | Camera 6.8 app support | Module action |
| ------- | ----------------------- | ---------------------- | ------------- |
| Leica Essential (M3/M9) | Yes. `legendCT` + `legendST` plugins, Legend snapshot pipelines, `leica_filter_param*.bin`. | Yes, list built by `p613r2.B`. | Nothing to inject. |
| CMOS / M10R | Yes. `LegendM10R` strings, M10R filter params, M10R pipeline nodes, `mileicafilter` M10R paths. | **Yes, native.** `ComponentConfigLegendary` adds M10R when the device profile is `C1217`; `madrid` is that profile. | Do **not** inject M10R on 6.8/madrid; it is already there. The existing toggle only matters for older builds. |
| `stylizationTypeMask` bit 3 (Original) | Yes, native. Mask is `14` on most cameras, `12` on two. | Yes. | The module’s `CameraCharacteristics` OR is a no-op here. |
| Film filters R600, P100F, F-50, KC64, KP160 | Yes, LUT resources are shipped. | Yes, filter list includes them. | The 6.8 filter profile is already present; still needs a device log to confirm the strip order. |
| Legend bokeh 35 mm / 75 mm and aperture stops | Yes, `com.xiaomi.legendMode.capsInfo` is present with focal lengths 35 and 75 and float aperture stops. | Yes, `getLegendBokehCapsInfo` parses it. | No module work; the app reads it directly. |
| Original portrait style | Yes (`libAncHumanPreviewBokeh.so`). | Yes. | Keep the 18 Pro Max default on. |

### What this changes from the older notes

* The 17 Ultra report says the HAL has “no M10R or CMOS”. That is **not** true
  for `madrid`: M10R strings, M10R filter parameters, an M10R pipeline, and
  native Camera 6.8 support are all present.
* `README.md` lists CMOS (M10R) as “Not verified” for the 18 Pro Max. The
  firmware evidence below shows it is implemented by the HAL and enabled by
  the camera app on this model.
* The module’s `LEGEND_PROFILES` table has no 6.8 entry. On `madrid` that is
  correct: the 6.8 app already builds the M10R item. A 6.8 profile is only
  useful for a 6.8 build on a device whose profile is not `C1217`.

## Evidence conventions

* **[observed]** — read directly from `dumpsys`, a file, or a decompiled
  method. The command or location is given.
* **[inferred]** — a conclusion from disassembly/strings where the exact
  semantic is not proven.
* **[unknown]** — not established by the available artifacts.

The binary offsets in these documents are file offsets, which equal virtual
addresses for the plugin ELFs and for `libmetadataHelper.so`. For
`camera.qcom.core.so` the `.text` virtual address equals the file offset
(`.text` at `0x3a4000`, file offset `0x3a4000`).

## Firmware artifact root

The analysis used the external volume mounted in this workspace as `970`
(`/run/media/rezikham/970/PoC/`):

```text
970/extracted_madrid/                  full extracted firmware trees
970/hal_analysis_madrid/binaries/      44 extracted HAL / MiAlgo libraries
970/hal_analysis_madrid/reports/       prior string dumps and hashes
970/device_dump/runtime/               getprop, dumpsys media.camera, logcat
970/device_dump/camera_apk/            18PM_camera_6.8.002230.0.apk
```

Keep that layout when re-running the commands in
[05-evidence.md](05-evidence.md), or adjust the paths.
