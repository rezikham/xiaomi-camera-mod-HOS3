# Xiaomi Camera Mod

[![CI](https://github.com/rezikham/xiaomi-camera-mod-HOS3/actions/workflows/build.yml/badge.svg)](https://github.com/rezikham/xiaomi-camera-mod-HOS3/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/rezikham/xiaomi-camera-mod-HOS3?include_prereleases&label=latest%20release)](https://github.com/rezikham/xiaomi-camera-mod-HOS3/releases)
[![License](https://img.shields.io/github/license/rezikham/xiaomi-camera-mod-HOS3)](LICENSE)
[![API](https://img.shields.io/badge/libxposed%20API-101%2F102-blue)](https://github.com/LSPosed/libxposed)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com/about/versions)

**HyperCameraMod** is an LSPosed/Vector module (libxposed API 101-102) for the Xiaomi Camera on HyperOS. It turns on features the camera already contains but leaves off, and brings back the film filters:

- Palette and Parameters panel
- Original colour style (Photo)
- Original portrait style (opt-in toggle; off by default on the 14 Ultra)
- Leica Essential mode in the standard UI (M3/M9)
- Leica Essential CMOS (M10R) style
- Film simulation filters: R600, P100F, F-50, KC64, KP160
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
| Xiaomi 14 Ultra | `24031PN0DC` / aurora | HyperOS `3.0.304.0.WNAMIXM` (Android 16) | `6.7.000280.0` | Original Portrait toggle, film filters |
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

| Feature | Xiaomi 14 Ultra | Xiaomi 17 Ultra | Xiaomi 18 Pro Max |
| ------- | --------------- | --------------- | ----------------- |
| Palette and Parameters | ✅ | ✅ | ✅ |
| Film filters | ✅ | ✅ | ✅ |
| Original colour style (Photo) | ✅ | ✅ | ✅ |
| Original portrait style | ⚠️ Opt-in — may freeze the preview | ✅ (on by default) | ✅ (on by default) |
| Leica Essential (M3/M9) | ❌ HAL limitation | ✅ | ✅ |
| CMOS (M10R) | ❌ HAL limitation | Experimental — the HAL has no M10R processing | ✅ Native (HAL + Camera 6.8) |

**14 Ultra HAL limitation.** M3, M9, M10R and Original portrait cannot work on the 14 Ultra because its camera hardware layer does not implement the processing they need:

- **Original portrait** starts an algorithm-up SAT preview session. The Xiaomi preview-bokeh engine (`libmialgo_mc_bokeh_cdsp_skel.so`) never returns a frame, the camera waits 5780 ms on a semaphore (`ChiX WaitForSemaphore() ... timed out`), the preview drops to about 0.1 fps, and the vendor camera provider eventually aborts (`ExtensionModule::TriggerRecovery`). The module therefore removes Original from the Portrait style picker on the 14 Ultra by default and moves a saved value back to Master. The **Original Portrait Style** toggle is off there; turning it on restores Original in the picker (together with the freeze), which is why it is opt-in rather than blocked.
- **M3/M9 (Leica Essential)** need the Leica Essential processing available on the 17 Ultra-class hardware layer. The 14 Ultra HAL has no M10R processing either, so a CMOS shot comes back as an ordinary picture, without Leica processing and without the `legendmode` tag Gallery uses for its LEICA button.

These are firmware limitations; no module can add the missing HAL processing. Original colour in Photo is unaffected.

**CMOS is off by default.** The Xiaomi 17 Ultra's hardware layer (`camera.qcom.core.so`, `com.qti.chi.override.so`) has the Leica Essential processing for M3 and M9 only. A CMOS shot comes back as an ordinary picture, without the Leica processing and without the `legendmode` tag the Gallery uses for its LEICA button. Enable it with the CMOS (M10R) toggle (or `camera.feature.m10r=1`) only to experiment, or on a phone whose firmware supports it.

On the **18 Pro Max** the CMOS style is native: the HAL ships `LegendM10R`, the M10R Leica filter parameters, an M10R pipeline and four dedicated M9/M10R style-transfer models, and Camera 6.8 adds the M10R item and defaults to it. The toggle is not needed on that phone; see [`docs/18-pro-max-hal/02-legend-m10r.md`](docs/18-pro-max-hal/02-legend-m10r.md).

## Settings

Open **HyperCameraMod** from the app drawer or from the module's entry in LSPosed/Vector.

| Toggle | Default | What it changes |
| ------ | ------- | --------------- |
| Leica Essential Mode in Standard UI | on | Adds the Leica Essential mode (M3/M9) to the standard UI mode strip. |
| Leica UI (Looks) | on | Shows the Leica Looks panel in the filter strip instead of the standard filter UI. |
| CMOS (M10R) Style | off | Adds the CMOS (M10R) style to the Leica Essential style list. |
| Extra Film Filters | on | Adds the R600, P100F, F-50, KC64 and KP160 film filters. |
| Original Portrait Style | on (17 Ultra / 18 Pro Max), off (14 Ultra) | Shows Original in the Portrait style picker. The default follows the phone model; on the 14 Ultra it is off because the HAL cannot preview it. |

The toolbar has **Force Stop Camera** (root required; grant it in Magisk/KernelSU/APatch). The overflow menu has **Hide launcher icon**, which removes the app icon while keeping the settings reachable from LSPosed/Vector. After changing a toggle, force-stop Camera and open it again.

## Usage

1. Install the module and enable it in LSPosed or Vector, scope **Camera** only.
2. Open **HyperCameraMod** settings and set the toggles.
3. Tap **Force Stop Camera**, then open Camera again.

If the camera misbehaves, disable the module and force-stop Camera to restore the stock behaviour.

### System properties (fallback)

The toggles have system-property equivalents for setups without the settings bridge. They reset on reboot; run them as root from a boot script such as Magisk's `service.sh` to keep them.

```bash
su -c "setprop camera.feature.m10r 1"                # CMOS (M10R) style
su -c "setprop camera.feature.original_portrait 1"   # Original portrait (freezes the 14 Ultra preview)
su -c "setprop camera.feature.essential_std 0"       # disable Leica Essential in the standard UI
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

When reporting a problem, include the phone model, HyperOS version, Camera version and the log lines. If a layout does not match, the `does not match` line names the missing class or field, which is what a new profile needs.

## Build

A Gradle project. It needs the Android SDK (compileSdk 37, build-tools 37.0.0); put `sdk.dir=...` in `local.properties`, then:

```bash
./gradlew assembleRelease
```

Release builds are signed with the debug key.

## Changelog

The changes in each release are in [`CHANGELOG.md`](CHANGELOG.md). The current stable release is 1.0.0.

## Disclaimer

An unofficial community module, not affiliated with Xiaomi or Leica. The three CMOS images in `src/main/assets/m10r` are taken from Xiaomi Camera. It hooks internal camera code and is provided as is, for personal use. Use it at your own risk.
