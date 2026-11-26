# Changelog

All notable changes to HyperCameraMod. The module follows a simple scheme: stable releases are tagged `v<version>`, development builds are published as the `CI-Release` pre-release.

## [Unreleased - hal-re merge] - 2026-11-24

### Added

- Genuine Essential Moments **M3/M9/M10R** capture pipeline running in the
  camera save path: the B12 wrapper, FilmInput M3 tone, real Monopan grain
  via a bundled OpenCL ICD shim, the M9 warm grade with B43Tone, and the
  M10R filter with the M10rBin post (AWB, soften, vignette, grain).
- **M10R (CMOS)** style in the Essential picker and a derived M10R chip in
  the Leica Looks strip (warm-density strip LUT, `leica_look.sh look-m10r`).
- 18 Pro Max Original portrait port: AI bokeh, 18PM LUT and aperture
  defaults (see docs/original-portrait-hal-fix-plan.md).

### Added

- **18 Pro Max palette/parameters bridge**: `patch_filter_palette_18pm.py` patches the 14U filter plugin to use 18PM `libMiPhotoFilter.so` (`StyleAdjustEffect`), so Saturation/Tone/etc. now apply on capture. `LEICA STD` captures are covered too.
- **Authentic 18PM M3/M9/M10R data**: 18PM `libmialgo_monopan.so` (M3), M9 snapshot with `lut_snapshot_en` fixed, M10R on the 18PM non-QNN `applyOriginalFilter` path, and genuine `M9_Ccd.png`/`M10R_Cmos.png` LUTs.
- Docs 27 and 28: palette/essential RE and 18PM photo-archive EXIF/XMP/MakerNote analysis.

- **MakerNote V2 for M3/M9/M10R**: Essential output now also carries ExifIFD `0x927c` (`Xiaomi\0\1MM`, `MomentVersion=0x00020000`, `MomentSubMode` 1/2/3), which MediaEditor/CN Gallery need to recognise M10R; legacy `0x88b0` + XMP container are kept for older Gallery builds.
- **Re-edit data writer (`ReEditWriter`)**: builds `ReEditDataBeanMsg` (clean capture + `filter` op), appends it after EOI, adds `micamedit` XMP and EXIF `reedit=1`. Opt-in via `setprop camera.feature.reedit 1` until the JPEG offset convention is verified on-device.

### Fixed

- Gallery "couldn't parse image" on M9/M3 caused by a duplicate APP1 XMP block.

### Changed

- The DCIM watcher only processes real `IMG_*.jpg` files; gallery trash
  entries and temp files are ignored, so a gallery cleanup no longer delays
  the next capture.
- Essential mode is detected from the live module index (256) instead of a
  stale camera preference.

### Notes

- The 18PM M9/M10R neural style transfer, LeicaQuality person correction
  and legendCT are QNN HTP V81 and cannot run on the 14 Ultra's V75 DSP.
  See docs/hal-backport/25-m3-m9-parity.md and 26-m10r-port.md.

## [1.2.6] - 2026-11-23

### Added

- M3 and M9 now appear as **ESTL M3** / **ESTL M9** two-line cards in the
  Leica Looks panel, using the same bundled LG1056 face as the film labels.
- Stock **Positive** and **Negative** filters now appear in the Leica Looks
  panel as **FLM NEG** / **FLM POS** cards, in addition to their existing
  place in the standard FILTER strip.

### Changed

- The M3/M9 toggles for the standard FILTER strip and the Leica Looks panel
  are merged into one **M3/M9 in the current UI** setting. The module reads
  the Leica UI theme and installs only the matching hook.
- Old `looks_entries_enabled` / `leica_ui_enabled` preferences are migrated
  once: M3/M9 stay on if either old switch was on.
- Film filters in the Leica Looks panel now follow **Extra Film Filters**
  instead of the removed Looks switch.

### Fixed

- Two-line label drawables are installed for both UIs. Previously the label
  hooks were only installed by the standard-strip path, so the Leica Looks
  cards could disappear when only the Looks path was active.

### Notes

- The KernelSU LUT module is unchanged from 1.2.0
  (`camera-mod-ksu-v1.2.0-306.zip`).

## [1.2.5] - 2026-11-23

### Changed

- Film labels now render from a bundled **LG1056 Regular** OTF instead of the
  generated 12-glyph bitmap table and the mixed camera-font fallback. Every
  label character now comes from the same official face.
- Both LG1056 weights are bundled in the module: `lg1056_regular.otf` for the
  labels and `lg1056_light.otf` for future use. The label renderer no longer
  depends on the camera APK for glyphs.
- Kept the native label metrics measured during the LG1050 trial: 9.7 dp cap
  height, 2.3 dp line gap, 0.66 dp translucent outline.

### Notes

- The bundled Regular is byte-identical to the camera's
  `assets/watermarks/leica/111/lg1056_regular.otf` and to the font in Leica
  FOTOS 6.3.0.
- The KernelSU LUT module is unchanged from 1.2.0
  (`camera-mod-ksu-v1.2.0-306.zip`).

## [1.2.4] - 2026-11-22

### Fixed

- Film labels in the Leica Looks panel and the FILTER strip now render every
  character. Missing glyphs are drawn with the camera's own **LG1056 Regular**
  (`assets/watermarks/leica/111/lg1056_regular.otf`), loaded at runtime from the
  camera APK. Nothing is bundled in the module, and the font already has full
  printable-ASCII coverage. Codes such as `600F`, `C-50D` and `KG200` no
  longer lose characters.

### Notes

- The LG1056 font is part of the camera APK; the module only reads it from the
  running camera app.
- The KernelSU LUT module is unchanged from 1.2.0
  (`camera-mod-ksu-v1.2.0-306.zip`).

## [1.2.3] - 2026-11-22

### Added

- **Leica UI theme (17U style)** switch. It chooses between the Leica Looks UI
  (LCC) and the standard FILTER strip and follows the device default until the
  user changes it. The camera process is overridden for
  `ro.theme_customize` / `camera.debug.theme_customize.disabled`; no root and no
  reboot are needed, only a Camera restart.
- The M3/M9 cards in the Leica Looks panel now use the stock
  `legendary_leica_m3` / `legendary_leica_m9` icon, template and text-icon
  resources, so they show proper "M3" / "M9" labels and previews.

### Changed

- Renamed the entry toggle to **M3/M9 and films in Leica Looks** to distinguish
  it from the new UI theme switch.
- Film labels use a fallback typeface for characters missing from the embedded
  Leica font (D, G, 2, etc.), keeping the longer film codes readable.

### Notes

- The Leica Looks panel and the standard FILTER strip both list M3 before M9.
- The KernelSU LUT module is unchanged from 1.2.0
  (`camera-mod-ksu-v1.2.0-306.zip`).

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
