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
* `tools/hal-port/aurora/verify_bundle.sh` — static file, pipeline and dynamic
  dependency checks.
* `HalCapabilities.java` in the module — file-based feature detection so the
  M10R picker item is only injected when the HAL can render it. `ModuleMain`
  also stops trying to inject the M10R item when Camera 6.8 builds it natively.

## Evidence status

Everything here is static evidence from extracted firmware, APK decompilation
and ELF disassembly. No physical Aurora device was available during this work:
the only ADB target was a Nothing Phone, not a Xiaomi camera device. The port
bundle therefore has **not** been executed on a 14 Ultra. Do not present it as
working until the test plan in
[03-port-bundle.md](03-port-bundle.md#test-plan) has been run.

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
