# Backporting Legendary HAL features to Aurora (14 Ultra)

This directory documents the reverse engineering and the first implementation of
a HAL-side backport:

* source devices: `nezha` (Xiaomi 17 Ultra) and `madrid` (Xiaomi 18 Pro Max);
* target: `aurora` / `aurorapro` (Xiaomi 14 Ultra);
* target Camera UI: the 6.7 layout that `nezha`/`madrid` use, not the 6.4 APK
  shipped in the Aurora image used for this dump.

The work separates two questions:

1. **What does the Legendary path actually need?** The app request, the
   metadata tags, the plugins, the parameter files, the style-transfer models,
   and the pipeline nodes are listed in
   [02-legend-data-path.md](02-legend-data-path.md).
2. **What can Aurora run today?** The stock Aurora HAL has none of the
   Legendary plugins, parameter files, models or pipeline nodes. It cannot
   render M3/M9/M10R from the app alone. A file overlay is technically
   plausible because Aurora's engine exports the plugin ABI the Nezha plugins
   use; the complete dependency closure and the remaining risks are in
   [03-port-bundle.md](03-port-bundle.md).

## Result at a glance

| Capability | Aurora stock | Aurora + this port | Nezha 17 Ultra | Madrid 18 Pro Max |
| --- | --- | --- | --- | --- |
| M3/M9 picker in Camera 6.7 | app-side only | app + HAL | native | native |
| M3/M9 look rendered by the engine | no | **yes, via a LUT slot** ([09](09-engine-lut-injection.md)) | native | native |
| `mileicafilter` plugin | no | yes | yes | yes |
| `legendST` + style-transfer models | no | yes (Nezha models) | yes | yes (M9/M10R models) |
| M3/M9 Leica parameters | no | yes (Nezha files) | yes | yes |
| `legendCT` (CMOS colour transform) | no | no | no | yes |
| M10R parameters/models | no | no | no | yes |
| M10R EXIF `legendmode` | no | no | no | yes |

The port deliberately stops at **M3/M9**. M10R is not a copy of M3/M9: it
needs `legendCT`, `libmialgo_ccm_3x8`, `libmage`, two M10R parameter files,
two M10R models, and the Android 17-era engine. See
[03-port-bundle.md](03-port-bundle.md#why-m10r-is-not-in-this-port).

## Deliverables

* `tools/hal-port/aurora/build_bundle.sh` — assembles a Magisk/KernelSU overlay
  from an extracted Nezha `odm` and an extracted Aurora `odm`.
* `tools/hal-port/aurora/patch_leicasnapshot.py` — inserts
  `LeicaFilterInstance0` and `StyleTransInstance0` into Aurora's
  `leicasnapshot.json`.
* `tools/hal-port/aurora/package_module.py` — repackages the bundle as a
  KernelSU/Magisk zip with `system/odm/...` paths.
* `tools/hal-port/aurora/verify_bundle.sh` — static file, pipeline and dynamic
  dependency checks.
* `HalCapabilities.java` in the module — file-based feature detection so the
  M10R picker item is only injected when the HAL can render it. `ModuleMain`
  also stops trying to inject the M10R item when Camera 6.8 builds it natively.
* `tools/hal-port/engine/install_lut_slot.sh` — installs a 512x512 RGB LUT
  (e.g. `assets/leica_luts/M3_Monopan.png`) into the HAL filter slot
  `/data/vendor/camera/<name>.png` and restores the stock state with
  `--restore`. This is the engine-side injection that works on Aurora today;
  see [09-engine-lut-injection.md](09-engine-lut-injection.md).
* `tools/hal-port/engine/leica_look.sh` — one command per look
  (`m3`, `m9`, `off`, `status`): installs the LUT, restarts the camera provider
  (it caches the decoded LUT) and the camera app, and can leave the engine gate
  to the module with `--no-gate`.
* The module's **Render M3/M9 in the camera engine (HAL LUT slot)** setting
  (`hal_lut_slot_enabled`) answers `camera.debug.mivi3.output.jpeg` from a
  `SystemProperties` hook, so after the one-time root install of the LUT the
  capture path needs no adb and no root.
* `tools/hal-port/engine/hook_tag_lookup.py` — Frida 17 probe for
  `MiMetadata::find` / `ChiMetadata::GetTag`. Unverified on aurora: attaching to
  the camera provider reboots this unit (immune-system protection switch).

## Evidence status

The analysis pages are static evidence from extracted firmware, APK
decompilation and ELF disassembly. A physical 14 Ultra became available at the
end of the work; the overlay and module APK were installed on
`OS3.0.304.0.WNAMIXM` with KernelSU 3.2.5. Execution results are tracked in
[05-on-device.md](05-on-device.md), separated from the static claims.

What has run on the device: the engine renders a supplied LUT through the stock
`com.xiaomi.plugin.filter` node - M3 (Monopan) as exact grayscale and the M9
look in colour, both at full resolution with the module's own post-processing
disabled ([09](09-engine-lut-injection.md) section 3b). The Nezha
`mileicafilter` plugin itself still never executes on Aurora
(`processRequest`/`initResource` never run), so the real Legendary pipeline is
not ported; the LUT slot is the engine-side approximation that works today.
[11-chix-adapter.md](11-chix-adapter.md) maps the engine entry that *does* carry
buffers (`processRequest`, not `postProcess`) and the wrapper-plugin design that
can host the donor algorithm without patching it.

Evidence conventions:

* `[observed]` — directly read from a file, string table, disassembly or JSON.
* `[inferred]` — a conclusion from the observed data that has not been executed.
* `[unknown]` — not established by the available evidence.

## Documents

| File | Contents |
| --- | --- |
| [01-aurora-vs-sources.md](01-aurora-vs-sources.md) | HAL architecture, plugins, configs and ABI differences |
| [02-legend-data-path.md](02-legend-data-path.md) | App request → metadata → plugin → pipeline |
| [03-port-bundle.md](03-port-bundle.md) | Overlay contents, build/verify/install, risks and test plan |
| [04-evidence.md](04-evidence.md) | Commands, hashes, offsets and reproducibility |
| [05-on-device.md](05-on-device.md) | Physical 14 Ultra install and validation log |
| [06-app-side-processing.md](06-app-side-processing.md) | Why vendor HAL plugins cannot run inside the LSPosed app process |
| [07-device-porting.md](07-device-porting.md) | Inventory and path choice for other devices (e.g. 14 Pro Ti) |
| [09-engine-lut-injection.md](09-engine-lut-injection.md) | Runtime consumer of the render payload (`com.xiaomi.plugin.filter`), LUT slot injection and the verified M3 monochrome capture |
| [13-chix-wrapper-implementation.md](13-chix-wrapper-implementation.md) | Aurora-native wrapper plugin: what the provider/plugin ABI really requires, the sepolicy and **DT_NEEDED** traps, and the libc++ blocker |
| [10-moment-app.md](10-moment-app.md) | The genuine Moment App M3/M9 pipeline: Java/native map, Monopan spawn and crash analysis, process-isolated module integration |
| [11-chix-adapter.md](11-chix-adapter.md) | ChiX plugin lifecycle: `postProcess()` vs `processRequest()`, `NodeMask` dispatch, donor ABI and the wrapper-plugin plan to host the Legendary algorithm |
| [12-moment-app-internals.md](12-moment-app-internals.md) | Moment App internals: JNI entry points and offsets, `libleicamodelembed` B12 wrapper, `legend18` binary format, helper argv/env, style models, preview shader, measured M3/M9 artifacts |
