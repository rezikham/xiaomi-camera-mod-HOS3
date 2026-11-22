# 19 - KernelSU module incremental rebuild

The camera freeze reported with **APK 245 + module 244** only happens when the
KernelSU module is enabled. With the module disabled the APK works normally, so
the module is the component to bisect.

The module is being rebuilt one component at a time. Each stage is installed on
its own and tested with the full APK active. Nothing from a later stage is added
until the current stage is confirmed stable.

## Stages

| Stage | Contents | Status |
|-------|----------|--------|
| 1 | M3/M9 LUTs + 12 film-filter LUTs staged to `/data/vendor/camera` + `camera.debug.mivi3.output.jpeg` gate | **built, awaiting device test** |
| 2 | + Leica filter parameter bins | not started |
| 3 | + SAT pipeline JSONs | not started |
| 4 | + plugin libraries / other HAL files | not started |

## Stage 1 - LUT only

ZIP: `camera-mod-ksu-v1.1.0-254-lut.zip` (about 2.5 MB, 22 files)

Contains only:

- `module.prop`
- `post-fs-data.sh`, `service.sh`, `hotinstall.sh`, `action.sh`, `customize.sh`, `uninstall.sh`
- `sepolicy.rule`
- `data/vendor/camera/lut_m3.png` (14,535 bytes, authentic 17U `M3_Monopan`) + `lut_m9.png` (77,354 bytes)

Note: an earlier 21,772-byte `M3_Monopan.png` was a reproduced fallback
(`c4fed8e`), not the authentic extraction. Camera 5.3 has no M3/M9; the
authentic M3/M9 LUTs came from 17U firmware (`8463447`).
- `data/vendor/camera/lut_600f.png`, `lut_bf70.png`, `lut_r600.png`, `lut_p100f.png`,
  `lut_f50.png`, `lut_kc64.png`, `lut_v250.png`, `lut_h400.png`, `lut_kp160.png`,
  `lut_fc400.png`, `lut_c50d.png`, `lut_kg200.png` (the 12 Camera 5.3 film-filter LUTs)

Deliberately absent:

- every `.so` file (no plugin libraries, no `libAncHumanPreviewBokeh.so`, no
  `libmialgoengine.so`, no `libmiSAT*.so`)
- every `system/` and `data/odm/` path, so nothing overlays or bind-mounts
  `/odm/etc/camera` or `/odm/lib64`
- Leica `leica_filter_param*.bin` and SAT `sat*.json`
- the bundled APK (the APK is installed manually and the module never touches it)

The scripts copy all 14 PNGs into `/data/vendor/camera`, set
`0644` + `u:object_r:vendor_camera_data_file:s0`, and set the engine gate.
`action.sh` prints each LUT's path and size.

Removed components are archived under `camera-mod-ksu-staging/` so they can be
restored one at a time for the later stages.

## Build

```bash
python3 tools/build-ksu-module.py camera-mod-ksu-v1.1.0-254-lut.zip
```

The packer refuses to include `.so` files by default.

## Install / test procedure

1. Remove the old module in KernelSU (module 244).
2. **Reboot.** The previous module mounted a `system/` overlay, and KernelSU
   mounts persist until reboot.
3. Install `camera-mod-ksu-v1.1.0-254-lut.zip`.
4. Reboot again so `post-fs-data.sh` runs cleanly.
5. Keep APK 254 installed, enabled in Vector/LSPosed with scope
   `com.android.camera`.
6. Open Camera. Check:
   - camera opens without freeze/hang
   - preview works
   - photo capture works
   - M3/M9 and the film entries appear and apply their looks

`action.sh` prints the state of all 14 LUT files and the gate property from the
KernelSU module action button.

## APK regression found before stage 1 testing

APK 245 was built from `ModuleMain.java` as of commit `89afe40`. The
essential-mode toggle fix landed *after* that commit:

- `3da2085` - `essentialStdEnabled()` reads the `essential_mode_enabled` remote
  preference instead of `camera.feature.essential_std`.
- `79feb6b` - M10R entry appears when its toggle is on regardless of HAL.

Restoring from `89afe40` reverted both, which is why the Essential toggle
appeared broken again. APK 246 is built from `ModuleMain.java` at `17e2797`
(the last full-hook commit) and contains both fixes. APK 254 is the current
all-film-filters build.

`build.gradle.kts` intentionally keeps the `generateKsuModuleProp` task out, so
building the APK cannot overwrite this staged module's `module.prop`.

## Result log

| Build | Result |
|-------|--------|
| APK 254 + 254-lut | pending |
| 254-lut | pending |
