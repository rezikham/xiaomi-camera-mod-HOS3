# Changelog

All notable changes to HyperCameraMod. The module follows a simple scheme: stable releases are tagged `v<version>`, development builds are published as the `CI-Release` pre-release.

## [1.2.2] - 2026-11-22

### Added

- The **Leica UI (Looks)** toggle now controls the Leica Looks panel. When on,
  the module adds M3/M9 and the film filters to the Leica Looks tables
  (`E9.h.c` and `E9.h.d`). When off, it only modifies the standard FILTER
  strip. The camera chooses which UI to build; both paths are supported.

### Changed

- The M3/M9 look-name and preview hooks are installed on every UI and no
  longer depend on the Looks-entries or Leica UI toggles.
- The Leica UI summary now describes the actual behavior and asks for a Camera
  restart after changing.

### Notes

- On the Xiaomi 14 Ultra the camera builds the standard FILTER strip, not the
  Leica Looks panel, so the switch has no visible effect there. On devices
  that use the Leica UI, the panel now follows the switch.
- The KernelSU LUT module is unchanged from 1.2.0
  (`camera-mod-ksu-v1.2.0-306.zip`).

## [1.2.1] - 2026-11-22

### Removed

- The **Render M3/M9 in the camera engine (HAL LUT slot)** toggle. The HAL LUT
  slot belongs to the separate HAL backport branch; its engine hook is not part
  of this APK.
- The **Compatibility fallback (M9/M3 approximation)** toggle and the app-side
  LUT rewrite of saved JPEGs. M3/M9 use the native filter LUT slots instead.
- The **Leica Essential pipeline (real M3/M9)** toggle, the private
  `:essential` service, and the Moment App post-processing path.
- The HAL gate `SystemProperties` hook and the tied Moment App pipeline skip.

### Changed

- The **M3 and M9 as their own Looks entries** toggle is unchanged and still
  controls the M3/M9 chips in the Leica Looks panel and FILM filter strip.
- The Leica UI (Looks) toggle is unchanged; it is still used on non-Ultra
  phones that show the Leica Looks panel.
- The KernelSU LUT module is unchanged from 1.2.0
  (`camera-mod-ksu-v1.2.0-306.zip`).

## [1.2.0] - 2026-11-22

Stable release: all twelve Camera 5.3 film filters, M3/M9 Leica Looks with a
working live preview, the authentic 17U M3 LUT, and a LUT-only KernelSU module.

### Added

- **All 12 film filters**: 600F, BF70, R600, P100F, F-50, KC64, V250, H400,
  KP160, FC400, C-50D, KG200. Every entry is displayed by its film code.
- **M3 and M9 Leica Looks** as separate entries on the hidden enum slots
  `91` (`N_NEUTRAL`) and `92` (`N_SOFT`), so V250 and H400 are not hijacked.
- **LUT-only KernelSU module** that stages the M3/M9 and all twelve film LUTs
  at `/data/vendor/camera`; it contains no libraries, plugin mounts, or
  `/odm`/`/vendor` changes and does not install the APK.

### Changed

- Film filters reuse an existing camera entry (matched by ordinal, display-name
  resource, strip key, or cloud id) instead of adding a duplicate. Existing
  entries are renamed to the film code, so a 6.7/6.8 camera shows the stock
  code instead of an artistic name.
- The M3 LUT is the authentic 17U `M3_Monopan.png` (md5
  `c4ff65cebdcd8edfa7146c6ab2f838f9`). The simulated fallback
  (`167c769a022945ea5bce80985b344209`) is rejected by validation.
- The KernelSU module is now the LUT stage only; APK installation is done
  manually and is no longer part of the module.

### Fixed

- **M3/M9 live preview**: the render engine asks for the neutral fallback
  `lut_nc` for slots 91/92, not `lut_m3`/`lut_m9`. `OpenGl3dUtils.a` and
  `Xu.g` now return a freshly decoded M3/M9 bitmap for that name while
  `EffectController.h` reports filter `91`/`92`.
- **Preview crash**: cached bitmaps were handed to native consumers that
  recycle them. The next upload then called `AndroidBitmap_lockPixels` on a
  recycled bitmap and `CandySDK_setLutBitmap` read an uninitialized pixel
  pointer. Every preview request now decodes a fresh 512x512 bitmap, which
  removes the SIGSEGV on repeated M3/M9 switching.

### Verified

- Xiaomi 14 Ultra `aurora`, HyperOS `3.0.304.0.WNAMIXM`, Camera `6.7.000280.0`:
  M3 preview saturation `0.0` (exact monochrome), M9 about `7.0` versus `8.7`
  for Original on the same scene; nine live M3/M9/Original switches completed
  with the camera process alive and no fatal signal.
- M3 capture still reports `FilterId 91` and produces a monochrome image.

### Notes

- The HAL backport and the Madrid Gallery re-edit plugin port remain on the
  `hal-re` branch and are not part of this release.

## [1.1.0] - 2026-11-17

Major feature release adding Original Portrait software implementation, Moment App terminology unification, and complete documentation.

### Added

- **Original Portrait software implementation** — full post-capture pipeline for 14 Ultra (aurora):
  - OpenGL ES 3.1 Compute Shader bokeh renderer (`liboriginal_portrait_bokeh.so`) matching `libAncHumanPreviewBokeh.so` behavior on 17U/18PM
  - 5 bokeh styles: Natural, Smooth, Swirly, Catseye, Polygon (parameter tags 0x10000-0x1000B)
  - Parametric color profile (ColorMatrix) + 3D LUT (64³) support
  - Depth-aware compositing with confidence-weighted edge preservation
  - Integrated via `EssentialPipelineExtension` for post-capture processing
  - EXIF tagging (`legendmode=0`) for Gallery recognition
  - Quality presets: FAST / BALANCED / QUALITY (~150ms / ~350ms / ~600ms)
- **Moment App terminology** — renamed "Essential Moments" → "Moment App" throughout codebase and documentation to distinguish from stock camera's "Essential Moment mode"
- **Complete RE documentation**:
  - `docs/original-portrait-re-report.md` — Deep RE of `libAncHumanPreviewBokeh.so` (MiaPluginModule vtable, SAT session protocol, parameter tags)
  - `docs/original-portrait-implementation-spec.md` — Full software architecture, shader algorithms, integration spec
  - `docs/original-portrait-software-investigation.md` — Investigation summary with 3 failed approaches analyzed
- **KSU Module enhancements**:
  - `post-fs-data.sh` + `service.sh` for boot + late-mount LUT staging
  - `sepolicy.rule` for SELinux context (vendor_camera_data_file)
  - Compatible with nomount/zeromount/mountify metamodules
  - No mount-hiding code in APK (clean Duck Detector scan when used with metamodule)

### Changed

- **Essential Moment mode in UI** — Now **disabled by default** (was enabled). Enable via `setprop camera.feature.essential_std 1` for testing.
- **Version scheme** — Automated `baseVersion-versionCode` (e.g., `1.1.0-186`) with local auto-increment, CI override via `-PversionCode`
- **Documentation restructured** — `10-moment-app.md`, `12-moment-app-internals.md` (renamed from Essential Moments)

### Fixed

- **Module structure** — APK now properly includes `liboriginal_portrait_bokeh.so` + existing `libm9base.so` + `libc++_shared.so`
- **Build system** — CMake integration for native library, NDK version pinned to 27.0.12077973

### Notes

- Original Portrait on 14 Ultra requires **post-capture depth map** from Portrait mode — verified on device when available
- For clean Duck Detector: use **nomount metamodule** (keyring) + KSU module, or **APK only** (no KSU module)
- Moment App pipeline (M3/M9) remains **off by default** — enable via `camera.feature.essential_pipeline` or settings

## [1.0.0] - 2026-11-14

First stable release of this fork, verified on a **Xiaomi 14 Ultra** running **HyperOS 3.0.304MIXMC09** with **Xiaomi Camera 6.7.000280.0** (the 17 Ultra camera build, installed as a normal app update).

### Added

- **Leica Essential mode in the standard UI**: Essential Moment (mode `256`) is added to the standard mode strip and the More menu.
- **CMOS (M10R) style**: adds the CMOS entry between B&W (M3) and CCD (M9) in the Leica Essential style list, with its own "Modern vintage" title and images.
- **Original colour style** next to Leica Authentic and Leica Vibrant, with a dedicated "ORIG" badge in the Leica UI.
- **Palette and Parameters** filter panel, including filter presets, sharing and importing.
- **Leica UI (Looks)** panel in the filter strip.
- **Film filters** R600, P100F, F-50, KC64 and KP160, in the standard filter panel and the Leica UI.
- **Settings app**: toggles for Essential mode, Leica UI, CMOS (M10R) and Extra Film Filters, a **Force Stop Camera** button, and **Hide launcher icon**. Settings stay reachable from LSPosed/Vector when the icon is hidden.

### Changed

- The package is now **`io.rezikham.hypercameramod`** (previously `io.martin.hypercameramod`). Enable the module again in LSPosed/Vector after installing; the old package can be uninstalled.
- Version reset to **1.0.0** (versionCode `100`).

### Fixed

- The **CMOS (M10R)** toggle did not add the style: the module read the `camera.feature.m10r` system property, which cannot be set on production HyperOS builds, and ignored the preference written by the settings app. The toggle value is now read first, with the system property kept as a fallback.

### Notes

- The module still targets the HyperOS 4 Leica Essential layout (`s2.A`). Camera builds that use a different layout are left unchanged and the module logs `does not match`, naming the missing class or field.
- Nothing is written to the system partition. Disable the module and force-stop Camera to return to stock behaviour.
