# Changelog

All notable changes to HyperCameraMod. The module follows a simple scheme: stable releases are tagged `v<version>`, development builds are published as the `CI-Release` pre-release.

## [Unreleased]

### Added

- **18 Pro Max palette/parameters bridge**: `patch_filter_palette_18pm.py` patches the 14U filter plugin to use 18PM `libMiPhotoFilter.so` (`StyleAdjustEffect`), so Saturation/Tone/etc. now apply on capture. `LEICA STD` captures are covered too.
- **Authentic 18PM M3/M9/M10R data**: 18PM `libmialgo_monopan.so` (M3), M9 snapshot with `lut_snapshot_en` fixed, M10R on the 18PM non-QNN `applyOriginalFilter` path, and genuine `M9_Ccd.png`/`M10R_Cmos.png` LUTs.
- Docs 27 and 28: palette/essential RE and 18PM photo-archive EXIF/XMP/MakerNote analysis.

- **MakerNote V2 for M3/M9/M10R**: Essential output now also carries ExifIFD `0x927c` (`Xiaomi\0\1MM`, `MomentVersion=0x00020000`, `MomentSubMode` 1/2/3), which MediaEditor/CN Gallery need to recognise M10R; legacy `0x88b0` + XMP container are kept for older Gallery builds.
- **Re-edit data writer (`ReEditWriter`)**: builds `ReEditDataBeanMsg` (clean capture + `filter` op), appends it after EOI, adds `micamedit` XMP and EXIF `reedit=1`. Opt-in via `setprop camera.feature.reedit 1` until the JPEG offset convention is verified on-device.

### Fixed

- Gallery "couldn't parse image" on M9/M3 caused by a duplicate APP1 XMP block.

### Changed

- **All 12 film filters are exposed**: 600F, BF70, R600, P100F, F-50, KC64, V250, H400, KP160, FC400, C-50D, KG200.
- The filter module detects the camera layout (6.4, 6.7, 6.7.000500, 6.8) and reuses a filter that is already present in that version's list instead of adding a duplicate. Existing entries are renamed to the film code, so a 6.7/6.8 camera that calls a filter 反转/韩胶/贝松/etc. shows the code instead.
- The film name is supplied by the module's own string hook, so the displayed name is the film stock (letters and digits) regardless of the ROM's artistic label.
- **M3/M9 live preview fixed (APK 305)**: the render engine asks for the neutral fallback `lut_nc` for slots 91/92, not `lut_m3`/`lut_m9`. `OpenGl3dUtils.a`/`Xu.g` now return a freshly decoded M3/M9 bitmap for that name while `EffectController.h` is 91/92. The old SIGSEGV was caused by returning cached bitmaps that native consumers had already recycled; every preview request now decodes a fresh 512x512 bitmap. Verified: M3 preview is monochrome (saturation 0.0), M9 keeps its subtle Leica Natural look, repeated M3/M9/Original switching is stable.

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

## [1.1.0-222] - 2026-11-18

HAL backport module for Original Portrait on 14 Ultra (experimental).

### Added

- **KernelSU module with bind mounts** — `post-fs-data.sh` and `service.sh` now bind-mount plugin libraries to `/odm/lib64/` where CHi plugin manager scans:
  - 61 camera plugins from 18 Pro Max → `/odm/lib64/camera/plugins/`
  - `libAncHumanPreviewBokeh.so` (8.7 MB) → `/odm/lib64/`
  - `libmialgoengine.so` (2.8 MB) → `/odm/lib64/`
  - SAT pipeline configs (`satpreview.json`, `satsnapshot.json`) → `/odm/etc/camera/xiaomi/`
  - Leica filter parameters → `/odm/etc/camera/`
- **Updated SELinux policy** (`sepolicy.rule`) — allows `hal_camera_default` to read/execute module files with `kernelsu_data_file`, `odm_file`, and `system_file` contexts
- **SAT config fix** — removed non-existent `com.xiaomi.plugin.ancienthumanpreviewbokeh` node from `satsnapshot.json`; connected `jpegrAggrInstanceSharp` directly to `SingleBokehPreviewInstance0`
- **Test plan** — `TEST_PLAN.md` with 8 test cases and debug commands

### Fixed

- **Plugin loading blocker** — CHi plugin manager now finds plugins via bind mounts (was: plugins not loaded, `DualSAT CreateUsecaseObjectFail`)
- **SELinux denials** — `hal_camera_default` can now read module libraries

### Notes

- This is an **experimental HAL overlay**. Requires device testing to verify `SingleBokehPreview` and `libAncHumanPreviewBokeh.so` load in cameraserver.
- Module ZIP: `camera-mod-ksu-v1.1.0-222-fixed.zip` (55.9 MB)
- Requires nomount metamodule for clean mount detection

## [1.1.0-223] - 2026-11-18

### Fixed

- **LUT self-install in APK** — Fixed `copyAssetToSystem()` to use base64 encoding through `su -c "base64 -d > destPath"` (was broken `cp /dev/stdin` approach)
- **Module version sync** — Updated `module.prop` to 1.1.0-223 via Gradle `generateKsuModuleProp` task
- **Release assets** — `camera-mod-ksu-v1.1.0-223-fixed.zip` + `hypercameramod-1.1.0-223-apk-lut-slot.apk` uploaded to CI-Release

## [1.1.0-224] - 2026-11-18

### Added

- **HAL patch specification** — `docs/hal-backport/14-hal-patch-specification.md`: exact binary patch targets for Aurora's `camera.qcom.so` to add native `legendMode` 1/2 handling and `MiaPluginModule` vtable loading for Original Portrait. Includes actual Aurora binary offsets from extracted device HAL (`camera.qcom.so` SHA-256: `5b30cefde5b4a4aabf52282778c6d757`)
- **HAL patching toolkit** — `tools/hal-port/aurora/hal_patch.py`: offline HAL extraction, offset discovery, binary patching, and KSU module building. Compiles cleanly.
- **HAL shim layer** — `tools/hal-port/aurora/hal_shim/`: C++ shim (`shim.h`, `shim.cpp`, `CMakeLists.txt`) for `MiaPluginModule` compatibility between Aurora's HAL and donor `libAncHumanPreviewBokeh.so`
- **QNN model porting plan** — `docs/hal-backport/15-qnn-model-porting.md`: HTP V81 (SM8750) → V75 (SM8650) conversion approaches for `legendST` style-transfer models
- **Software-native path** — `docs/hal-backport/16-software-native-path.md`: EXIF `legendmode` tagging (0x88b0), color profile matching, and preview bokeh — all implementable in userspace without HAL patching
- **Property-based software workaround** — `docs/hal-backport/17-property-workaround.md`: `persist.vendor.camera.leicafilter.forceEnable` property to force-enable `mileicafilter.so` processing (requires KSU module)

### Fixed

- **Corrected stock vs module distinction**: All docs now clearly mark which files are stock Aurora vs module-provided via bind mount. Stock Aurora does NOT natively have `mileicafilter.so`, `legendST.so`, `singlebokehpreview.so`, or `leica_filter_param_m3.bin`/`m9_*.bin` — these are module-provided.
- **HAL binary hashes updated**: Actual SHA-256 hashes from extracted device binaries documented in patch specification.

### Documentation

- **README.md** — Added property workaround reference, corrected stock vs module distinction in feature table
- **CHANGELOG.md** — This entry
- **TEST_PLAN.md** — Added property workaround test case (test 10), clarified stock vs module file locations

## [1.1.0-225] - 2026-11-19

### Added

- **EXIF legendmode tag injection** — `src/main/java/io/martin/hypercameramod/ExifUtils.java`: injects EXIF tag 34992 (0x88B0) into M3/M9 JPEGs so Gallery shows LEICA button
- **EssentialService integration** — `EssentialService.process()` now calls `ExifUtils.injectLegendMode()` after `copyExif()`, passing the current legend mode
- **Color profile matching** — `src/main/jni/shaders/bokeh.comp`: added `ColorProfile` uniform block (binding=1) with contrast, saturation, highlights, shadows, colorTemp, tint parameters; `applyColorProfile()` function applied to all output pixels
- **OriginalParams presets** — `OriginalParams::makeM3()` and `OriginalParams::makeM9()` factory methods with approximate Leica look parameters (to be refined with donor capture measurements)
- **Second uniform buffer** — `bokeh_renderer.cpp`: `colorProfileBuffer_` for `ColorProfile` uniform block (GL binding = 1)
- **Stub libraries** — `tools/hal-port/aurora/stub_libs/`: `libmetatagwrapper.so`, `libcameraopt_stub.so`, `libcom.xiaomi.cam.miexifmgr.so` stub C++ sources + `CMakeLists.txt` for enabling `legendST.so`/`mileicafilter.so` on Aurora (missing dependency libs)
- **Patch data specification** — `tools/hal-port/aurora/patch_data/aurora_legend_patch.json`: JSON schema documenting exact patch targets for `camera.qcom.so`
- **hal_patch.py enhancements** — new `generate-patch` and `verify-patched` subcommands; Aurora-specific constants (`AURORA_OFFSETS`) with verified binary offsets

### Fixed

- **APK build** — `ExifUtils.java` compiles cleanly; `EssentialService.java` integrates EXIF injection without breaking existing logic
- **Bokeh renderer build** — Color profile uniform block and shader changes compile with CMake/NDK r27

### Notes

- EXIF tagging requires device testing to verify Gallery shows LEICA button on M3/M9 photos
- Color profile parameters are approximate (to be refined with donor vs Aurora ΔE measurements in LAB space)
- Stub libraries are untested on device; `legendST.so` loading depends on `libmialgo_styletrans.so` and `libLMSceneSeg.so` which are still missing

## [1.1.0-226] - 2026-11-19

### Fixed

- **Release asset upload** — `hypercameramod-1.1.0-226-apk-lut-slot.apk` uploaded to CI-Release

## [1.1.0-227] - 2026-11-19

### Fixed

- **Release asset upload** — `hypercameramod-1.1.0-227-apk-lut-slot.apk` uploaded to CI-Release (includes EXIF tagging, color profile matching, stub library sources)

## [1.1.0-228] - 2026-11-19

### Added

- **QNN model files extracted** — 4 Madrid models (~40MB each) + 3 Nezha models (~10MB each) copied to `/run/media/rezikham/970/PoC/hal-extract-aurora/qnn_models/`
- **Required libraries extracted** — `libmialgo_styletrans.so`, `libLMSceneSeg.so` (Nezha + Madrid), `libmage.so`, `libmialgo_ccm_3x8.so` (M10R)
- **MiNN container parser** — `tools/hal-port/aurora/extract_minn.py`: parses `.minn` files, identifies `NNiM` magic, extracts embedded compressed model blobs. Tested on all 7 models.
- **Proprietary compression identified** — MiNN containers use `\x1f\x8b` magic with CM=0x67 (not standard deflate), confirmed on Madrid M9 high-res model
- **QNN runtime comparison** — Aurora uses QNN HTP V75, Madrid uses V81. Documented in `docs/hal-backport/15-qnn-model-porting.md`

### Fixed

- **Release asset upload** — `hypercameramod-1.1.0-228-apk-lut-slot.apk` uploaded to CI-Release



## [1.1.0-229] - 2026-11-19

### Added

- **Authentic 17 Ultra LUTs extracted from device** — M3/M9 LUTs now sourced from the actual 17 Ultra (nezha) firmware instead of being programmatically generated:
  - `assets/leica_luts/M3_Monopan.png` → replaced with `131_MonochromeTrue.png` from 17U `/odm/etc/camera/videofilter/` (14,535 bytes, authentic monochrome LUT)
  - `assets/leica_luts/130_LeicaNatural.png` → verified identical to 17U version (77,354 bytes)
  - KSU module staged LUTs updated: `camera-mod-ksu/data/vendor/camera/lut_m3.png` and `lut_m9.png`

### Fixed

- **Release asset upload** — `camera-mod-ksu-v1.1.0-229-fixed.zip` and `hypercameramod-1.1.0-229-apk-lut-slot.apk` uploaded to CI-Release


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
