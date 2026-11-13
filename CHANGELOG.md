# Changelog

All notable changes to HyperCameraMod. The module follows a simple scheme: stable releases are tagged `v<version>`, development builds are published as the `CI-Release` pre-release.

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
