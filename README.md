# Xiaomi Camera Mod

[![CI](https://github.com/rezikham/xiaomi-camera-mod-HOS3/actions/workflows/build.yml/badge.svg)](https://github.com/rezikham/xiaomi-camera-mod-HOS3/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/rezikham/xiaomi-camera-mod-HOS3?include_prereleases&label=latest%20release)](https://github.com/rezikham/xiaomi-camera-mod-HOS3/releases)
[![License](https://img.shields.io/github/license/rezikham/xiaomi-camera-mod-HOS3)](LICENSE)
[![API](https://img.shields.io/badge/libxposed%20API-101%2F102-blue)](https://github.com/LSPosed/libxposed)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com/about/versions)

**HyperCameraMod** is an LSPosed/Vector module (libxposed API 101-102) for the Xiaomi Camera on HyperOS. It turns on features the camera already contains but leaves off, and brings back the film filters:

- Palette and Parameters panel
- Original colour style (Photo)
- **Original portrait style — software implementation on 14 Ultra** (opt-in toggle; off by default; uses post-capture depth map + OpenGL ES 3.1 bokeh renderer)
- M3/M9 in the current UI (standard FILTER strip or Leica Looks)
- Leica Essential CMOS (M10R) style
- **Moment App pipeline** (real M3/M9 post-capture processing, off by default)
- Film simulation filters: 600F, BF70, R600, P100F, F-50, KC64, V250, H400, KP160, FC400, C-50D, KG200 (existing entries are reused and renamed to the film code)
- Settings app with toggles, **Force Stop Camera**, and **Hide launcher icon**

Package: `io.rezikham.hypercameramod`

## Requirements

- Xiaomi phone on HyperOS 3 or HyperOS 4 with Xiaomi Camera 6.4–6.8. Feature coverage depends on the camera version (see below).
- Root with Magisk, KernelSU or APatch. Only the settings app's **Force Stop Camera** button needs it.
- LSPosed or Vector with libxposed API 101/102. The settings-to-camera bridge needs API 102; on API 101 the toggles do not reach Camera, but the hooks and the system-property fallbacks still work.
- Module scope: **Camera** only (`com.android.camera`).

### Tested builds

| Device | Model / codename | OS | Camera | Verified |
| ------ | ---------------- | -- | ------ | -------- |
| Xiaomi 14 Ultra | `24031PN0DC` / aurora | HyperOS `3.0.304.0.WNAMIXM` (Android 16) | `6.7.000280.0` | Original Portrait toggle (software), film filters, HAL module experimental |
| Xiaomi 13 Ultra | `2304FPN6DC` / ishtar | HyperOS `3.0.308.0.WMACNXM` (Android 16) | `6.7.000280.0` | Leica Looks (M3/M9/M10R, POS/NEG), film filters, Moment App post-capture, Portrait; Original Photo hangs HAL |
| Xiaomi 14 Pro Ti | `23116PN5BC` / shennong | HyperOS `3.0.310.0.WNBCNXM` (Android 16) | `6.4.000430.0` | film filters |
| Xiaomi 18 Pro Max | `M154FF` / madrid | HyperOS `4.0.29.0.XEOCNXM` (Android 17) | `6.8.002230.0` | film filters (static, needs a device log); Legend/M10R native |

The 17 Ultra results below come from the upstream project (HyperOS 4, Camera 6.7); this fork has not re-tested that phone.

The 18 Pro Max HAL analysis — provider/device versions, Legend/M10R processing, metadata tags, pipelines and film LUTs — is in [`docs/18-pro-max-hal/`](docs/18-pro-max-hal/README.md).

## Camera version coverage

| Camera | Coverage |
| ------ | -------- |
| 6.7 | All module features. |
| 6.4 | Film filters only. The build has no style lists or mode order to hook, so the Original colour/portrait, Leica Essential (M3/M9) and CMOS (M10R) toggles have no effect. |
| 6.8 | Film filters, experimental. The layout was derived statically from the APK and still needs device verification; if a film is missing, pull a log (see [Logs](#logs)). On the 18 Pro Max the Leica Essential styles (M3/M9) and CMOS (M10R) are native in Camera 6.8, so the module does not inject them there. |

**Palette and Parameters on 6.4.** The module turns that panel on by raising `camera.feature.styleVersion` to 2. Camera 6.4 does not read that property, so the hook has no effect on a 6.4 build; the panel is only there if the 6.4 camera shows it by itself. On 6.4 the module's support is limited to the film filters.

## Feature availability

The module can only expose what the phone's camera hardware layer (`camera.qcom.so`, CHI) implements. It cannot add missing processing.

| Feature | Xiaomi 13 Ultra | Xiaomi 14 Ultra | Xiaomi 17 Ultra | Xiaomi 18 Pro Max |
| ------- | --------------- | --------------- | --------------- | ----------------- |
| Palette and Parameters | ✅ (preview; no 18PM HAL bridge) | ✅ | ✅ | ✅ |
| Film filters | ✅ | ✅ | ✅ | ✅ |
| Original colour style (Photo) | ❌ HAL hangs (disabled on `ishtar`) | ✅ | ✅ | ✅ |
| **Original portrait style (software)** | ⚠️ Needs scene depth verification | ⚠️ Opt-in — post-capture, uses depth map | ✅ (on by default, native) | ✅ (on by default, native) |
| Leica Essential (M3/M9) | ⚠️ Moment App post-capture | ⚠️ stock HAL cannot process; Moment App post-capture | ✅ | ✅ |
| Moment App pipeline (M3/M9) | ✅ (fallback when `essential_mode_enabled` is on) | ⚠️ Off by default; enable in settings | ✅ | ✅ |
| CMOS (M10R) | ⚠️ Moment App post-capture | ❌ HAL limitation | Experimental — HAL has no M10R | ✅ Native (HAL + Camera 6.8) |

**14 Ultra HAL limitation.** M3, M9, M10R and native Original portrait cannot work on the 14 Ultra because its camera hardware layer does not implement the processing they need:

- **Native Original portrait** starts an algorithm-up SAT preview session. The Xiaomi preview-bokeh engine (`libmialgo_mc_bokeh_cdsp_skel.so`) never returns a frame, the camera waits 5780 ms on a semaphore (`ChiX WaitForSemaphore() ... timed out`), the preview drops to about 0.1 fps, and the vendor camera provider eventually aborts (`ExtensionModule::TriggerRecovery`). The module therefore removes native Original from the Portrait style picker on the 14 Ultra by default.
- **Software Original portrait (v1.1.0+)** — **Post-capture implementation** that works on 14 Ultra: captures Master Portrait, extracts depth map, applies Original bokeh + color profile via OpenGL ES 3.1 compute shader (`liboriginal_portrait_bokeh.so`), tags EXIF `legendmode=0`. Enable via **Original Portrait Style** toggle (off by default). No preview freeze.
- **M3/M9 (Leica Essential)** need the Leica Essential processing available on the 17 Ultra-class hardware layer. The 14 Ultra HAL has no M10R processing either.
- **Moment App pipeline (v1.1.0+)** — **Post-capture implementation** using the genuine Moment App parametric filter (`libm9base.so`) + Monopan grain (`libmialgo_monopan.so`) in a private module process. Off by default; enable in settings.

These are firmware limitations for native processing. The module provides **software post-capture alternatives** for Original Portrait and M3/M9 on 14 Ultra.

**13 Ultra (`ishtar`) notes (Camera `6.7.000280.0`, HyperOS `3.0.308.0.WMACNXM`):**
- `camera.debug.mivi3.output.jpeg=true` must **not** be enabled on `ishtar`: MIVI3 outputs raw 12 MiB (`4096×3072`) non-JPEG buffers instead of valid JPEGs (`camera-mod-ksu` skips setting this property on `ishtar`).
- **Original style in Photo mode** hangs the `ishtar` HAL, so `STYLIZATION_ORIGINAL` is skipped on `ishtar`.
- Tapping the **Aperture** toggle inside the swipe-down menu crashes Camera `6.7.000280.0` (`TopConfigItem` NPE); moving the Aperture toggle to the top bar works normally.
- `hcm-palette-18pm` (18PM Palette HAL Bridge) does not apply to `ishtar` because `/odm/lib64/camera/plugins/com.xiaomi.plugin.filter.so` is not present on 13 Ultra.

## Native HAL Backport Path (Experimental, Requires Device)

Native M3/M9 and Original Portrait on Aurora require **patching `camera.qcom.so`** — mount-based file overlays alone cannot work because Aurora's HAL binary has no code path to load donor plugins or the `MiaPluginModule` vtable.

### What's needed for native support

| Component | What must change | Location |
|-----------|-----------------|----------|
| `legendMode` handling | HAL must copy `com.xiaomi.sessionparams.legendMode` into MiMetadata tag 508 | `camera.qcom.so` binary patch |
| `oemIsLegendMode` | Compute `(legendMode-1) < 2` for values 1/2 | `camera.qcom.so` binary patch |
| `MiaPluginModule` loader | `dlopen` `libAncHumanPreviewBokeh.so`, call `get_api_impl`, walk 10-function vtable | `camera.qcom.so` binary patch |
| Plugin nodes | `mileicafilter`, `legendST` in `leicasnapshot.json` | Already in module ZIP |
| QNN models | V81 `.minn` models → V75-compatible format | Porting required |

### Offline artifacts (ready for device testing)

- `docs/hal-backport/14-hal-patch-specification.md` — exact binary patch spec (with Aurora binary offsets)
- `tools/hal-port/aurora/hal_patch.py` — HAL extraction, analysis, patching, module build scripts
- `tools/hal-port/aurora/hal_shim/` — C++ shim layer for `MiaPluginModule` compatibility
- `docs/hal-backport/15-qnn-model-porting.md` — QNN HTP V81→V75 porting plan
- `docs/hal-backport/16-software-native-path.md` — software-only "close to native" path (EXIF tagging, color profile)
- `docs/hal-backport/17-property-workaround.md` — property-based software workaround (`persist.vendor.camera.leicafilter.forceEnable`)

### Software-only "close to native" (no HAL patch)

For users who cannot risk HAL patching, the software path adds:

| Enhancement | What it does | Status |
|-------------|-------------|--------|
| EXIF `legendmode` tag (0x88b0) | Gallery shows LEICA button on M3/M9 photos | Specified |
| Color profile matching | Parametric contrast/saturation/highlights to match donor look | Specified (needs measurement) |
| Preview bokeh | Software blur at preview resolution | Deferred (performance) |
| `persist.vendor.camera.leicafilter.forceEnable` | Forces `mileicafilter.so` to process captures | Specified (requires module) |

**Important**: The property workaround requires our KSU module (provides `mileicafilter.so`). Stock Aurora does NOT have this plugin natively.

The **Compatibility fallback** toggle (Settings) maps M9/M3 to the stock Leica Authentic/Vibrant colour path as an explicit approximation; it is off by default and is not the real M3/M9 pipeline. Original colour in Photo is unaffected.

**CMOS is off by default.** The Xiaomi 17 Ultra's hardware layer (`camera.qcom.core.so`, `com.qti.chi.override.so`) has the Leica Essential processing for M3 and M9 only. A CMOS shot comes back as an ordinary picture, without the Leica processing and without the `legendmode` tag the Gallery uses for its LEICA button. Enable it with the CMOS (M10R) toggle (or `camera.feature.m10r=1`) only to experiment, or on a phone whose firmware supports it.

On the **18 Pro Max** the CMOS style is native: the HAL ships `LegendM10R`, the M10R Leica filter parameters, an M10R pipeline and four dedicated M9/M10R style-transfer models, and Camera 6.8 adds the M10R item and defaults to it. The toggle is not needed on that phone; see [`docs/18-pro-max-hal/02-legend-m10r.md`](docs/18-pro-max-hal/02-legend-m10r.md).

## Settings

Open **HyperCameraMod** from the app drawer or from the module's entry in LSPosed/Vector.

| Toggle | Default | What it changes |
| ------ | ------- | --------------- |
| M3/M9 in the current UI | on | Adds M3/M9 to the Leica Looks panel when the Leica UI theme is on, or to the standard FILTER strip when it is off. |
| Leica UI theme (17U style) | off on 14 Ultra, on for LCC/17U | Uses the Leica Looks panel instead of the standard FILTER strip. Restart Camera after changing. |
| CMOS (M10R) Style | off | Adds the CMOS (M10R) style to the Leica Essential style list. |
| Extra Film Filters | on | Exposes all 12 film filters (600F through KG200). A filter the camera already has is reused and its name is changed to the film code instead of adding a duplicate. |
| Original Portrait Style | on (17 Ultra / 18 Pro Max), off (14 Ultra) | Shows Original in the Portrait style picker. On 14 Ultra uses software post-capture (depth map + bokeh renderer). |
| Moment App Pipeline (M3/M9) | off | Enables real M3/M9 post-capture processing via Moment App parametric filter + Monopan grain. |

The toolbar has **Force Stop Camera** (root required; grant it in Magisk/KernelSU/APatch). The overflow menu has **Hide launcher icon**, which removes the app icon while keeping the settings reachable from LSPosed/Vector. After changing a toggle, force-stop Camera and open it again.

## Usage

### Standard LSPosed Module (APK only)
1. Install the APK and enable it in LSPosed or Vector, scope **Camera** only.
2. Open **HyperCameraMod** settings and set the toggles.
3. Tap **Force Stop Camera**, then open Camera again.

If the camera misbehaves, disable the module and force-stop Camera to restore the stock behaviour.

### Experimental HAL Backport Module (KernelSU)
For native Original Portrait on 14 Ultra (requires device testing):

1. Flash `camera-mod-ksu-v1.1.0-223-fixed.zip` via KernelSU
2. Reboot device
3. Open KernelSU app → Grant root to "HyperCameraMod" (io.rezikham.hypercameramod)
4. Enable module in LSPosed/Vector:
   - Scope: `com.android.camera`
   - Force-stop Camera app after enabling
5. Open Camera app → Portrait → Original style

**Requirements**: nomount metamodule enabled, HyperOS 3.0.304.0.WNAMIXM, Camera 6.7.000280.0

### System properties (fallback)

The toggles have system-property equivalents for setups without the settings bridge. They reset on reboot; run them as root from a boot script such as Magisk's `service.sh` to keep them.

```bash
su -c "setprop camera.feature.m10r 1"                    # CMOS (M10R) style
su -c "setprop camera.feature.original_portrait 1"       # Original portrait (software post-capture on 14 Ultra)
su -c "setprop camera.feature.essential_std 1"           # enable Leica Essential in standard UI (M3/M9)
su -c "setprop camera.feature.essential_pipeline 1"      # enable Moment App pipeline (M3/M9 post-capture)
su -c "setprop camera.debug.mivi3.output.jpeg true"      # HAL gate for M3/M9 LUT slot
```

**HAL module properties** (set by `post-fs-data.sh` automatically):
```bash
su -c "setprop camera.debug.mivi3.output.jpeg true"      # Required for M3/M9 LUT slot
# LUT files staged to /data/vendor/camera/lut_m3.png and lut_m9.png
```

## Logs

The module logs to logcat under the tag `HyperCameraMod`. Clear the buffer, open Camera, reproduce the problem, then dump it:

```bash
su -c "logcat -c"
su -c "logcat -d -s HyperCameraMod"
```

The same lines are in LSPosed/Vector under **Logs** (filter `HyperCameraMod`). Typical lines:

```
HyperCameraMod: camera layout 6.8.002230, hooked xi.h0.d
HyperCameraMod: camera layout 6.8.002230 does not match: ...
HyperCameraMod: the camera's filter code is not one this module knows
HyperCameraMod: remote preferences are not available: ...
```

### Collect a diagnostic file

Two helpers live in [`tools/`](tools):

- **`tools/hcm-log.sh`** runs on the phone (Termux or any root shell) and collects the versions, the module's stored toggles and the Camera/module log into one text file:

  ```sh
  su -c "sh /sdcard/Download/hcm-log.sh"
  ```

  Output: `/sdcard/Download/hcm-log-<timestamp>.txt`. Add `--no-restart` to capture what just happened instead of restarting Camera.

- **`tools/pull_camera_log.sh`** runs on a computer with adb and writes the same kind of file:

  ```sh
  tools/pull_camera_log.sh -s <serial>
  ```

  Output: `logs/<serial>-<device>-<timestamp>.txt` (override with `-o`).

**Root or Shizuku.** The on-device scripts (`hcm-log.sh`, `hcm-hal.sh`) need root (`su`). With Shizuku, run them from a shell app such as **aShell You**. Without either, the module preferences, dmesg and some vendor/odm files are unavailable and the dump is partial.

When reporting a problem, include the phone model, HyperOS version, Camera version and the log lines. If a layout does not match, the `does not match` line names the missing class or field, which is what a new profile needs.

## Build

A Gradle project. It needs the Android SDK (compileSdk 37, build-tools 37.0.0); put `sdk.dir=...` in `local.properties`, then:

```bash
./gradlew assembleRelease
```

Release builds are signed with the debug key.

For HAL analysis, `tools/dump-hal.sh` collects the camera HAL libraries, plugins, configs and Camera APK from every connected phone (it uses `su` when available):

```bash
tools/dump-hal.sh -o hal-dump
```

Without a computer, `tools/hcm-hal.sh` runs on the phone and writes the same set as one tar, with the OS build in the file name:

```sh
su -c "sh /sdcard/Download/hcm-hal.sh"
# → /sdcard/Download/hal-<codename>-<model>-<os>-<timestamp>.tar
```

On Windows, `tools/windows/dump-hal.ps1` does the same from a computer. It uses `adb.exe` from `PATH`, and if adb is missing it downloads Google's official platform-tools into `tools/windows/platform-tools` (not committed to the repository) and uses that copy.

## Changelog

The changes in each release are in [`CHANGELOG.md`](CHANGELOG.md). The current stable release is 1.0.0.

## Disclaimer

An unofficial community module, not affiliated with Xiaomi or Leica. The three CMOS images in `src/main/assets/m10r` are taken from Xiaomi Camera. It hooks internal camera code and is provided as is, for personal use. Use it at your own risk.
