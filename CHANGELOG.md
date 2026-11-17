# Changelog

All notable changes to HyperCameraMod. The module follows a simple scheme: stable releases are tagged `v<version>`, development builds are published as the `CI-Release` pre-release.

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
