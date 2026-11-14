# Xiaomi Camera Mod

**HyperCameraMod** is an LSPosed/Vector module (libxposed API 101-102) for the Xiaomi Camera on HyperOS. It turns on features the camera already contains but leaves off, and brings back the film filters:

- Palette and Parameters panel
- Original colour style (Photo)
- Original portrait style (device-aware toggle; off by default on 14 Ultra due to HAL limitation)
- Leica Essential mode in the standard UI (M3/M9)
- Leica Essential CMOS (M10R) style
- Film simulation filters: R600, P100F, F-50, KC64, KP160
- Settings app with toggles, Force Stop Camera button, and Hide launcher icon option

Package: `io.rezikham.hypercameramod`

## Requirements

- A Xiaomi phone on HyperOS 3 or HyperOS 4 with Xiaomi Camera 6.7. Tested on the Xiaomi 14 Ultra (HyperOS 3) and the Xiaomi 17 Ultra (HyperOS 4).
- Root with Magisk, KernelSU or APatch. Only the settings app's **Force Stop Camera** button needs root.
- LSPosed or Vector with libxposed API 101/102. The settings-to-camera bridge uses API 102; on API 101 the toggles do not reach Camera, but the hooks and the system-property fallbacks still work.
- Module scope: **Camera** only (`com.android.camera`).

## Feature availability

The module can only expose what the phone's camera hardware layer (`camera.qcom.so`, CHI) implements. It cannot add missing processing.

| Feature | Xiaomi 14 Ultra | Xiaomi 17 Ultra | Xiaomi 18 Pro Max |
| ------- | --------------- | --------------- | ----------------- |
| Palette and Parameters | ✅ | ✅ | ✅ |
| Film filters | ✅ | ✅ | ✅ |
| Original colour style (Photo) | ✅ | ✅ | ✅ |
| Original portrait style | ❌ HAL limitation | ✅ (on by default) | ✅ (on by default) |
| Leica Essential (M3/M9) | ❌ HAL limitation | ✅ | ✅ |
| CMOS (M10R) | ❌ HAL limitation | Experimental — the HAL has no M10R processing | Not verified |

**14 Ultra HAL limitation.** On the 14 Ultra, M3, M9, M10R and Original portrait cannot work, because the camera hardware layer does not implement the processing they need:

- **Original portrait** starts an algorithm-up SAT preview session. The Xiaomi preview-bokeh engine (`libmialgo_mc_bokeh_cdsp_skel.so`) never returns a frame, the camera waits 5780 ms on a semaphore (`ChiX WaitForSemaphore() ... timed out`), the preview drops to about 0.1 fps, and the vendor camera provider eventually aborts (`ExtensionModule::TriggerRecovery`). The module therefore removes Original from the Portrait style picker on the 14 Ultra by default and moves a saved value back to Master. The **Original Portrait Style** toggle is off there; turning it on only restores the freeze.
- **M3/M9 (Leica Essential)** need the Leica Essential processing available on the 17 Ultra-class hardware layer. The 14 Ultra HAL has no M10R processing either, so a CMOS shot comes back as an ordinary picture, without Leica processing and without the `legendmode` tag Gallery uses for its LEICA button.

These are firmware limitations; no module can add the missing HAL processing. Original colour in Photo is unaffected and works on all three phones.

**CMOS is off by default.** The Xiaomi 17 Ultra's hardware layer (`camera.qcom.core.so`, `com.qti.chi.override.so`) has the Leica Essential processing for M3 and M9 only. A CMOS shot comes back as an ordinary picture, without the Leica processing and without the `legendmode` tag the Gallery uses for its LEICA button. Enable it with the CMOS (M10R) toggle (or `camera.feature.m10r=1`) only to experiment, or on a phone whose firmware supports it.

## Settings

HyperCameraMod has a settings app. Open it from the app drawer ("HyperCameraMod") or from the module's entry in LSPosed/Vector.

| Toggle | Default | What it changes |
| ------ | ------- | --------------- |
| Leica Essential Mode in Standard UI | on | Adds the Leica Essential mode (M3/M9) to the standard UI mode strip. |
| Leica UI (Looks) | on | Shows the Leica Looks panel in the filter strip instead of the standard filter UI. |
| CMOS (M10R) Style | off | Adds the CMOS (M10R) style to the Leica Essential style list. |
| Extra Film Filters | on | Adds the R600, P100F, F-50, KC64 and KP160 film filters. |
| Original Portrait Style | on (17U/18PM), off (14U) | Shows Original in the Portrait style picker. Default is chosen from the phone model; on the 14 Ultra it is off because the HAL cannot preview it (see Feature availability). |

The toolbar has a **Force Stop Camera** button (root is required; grant it in Magisk/KernelSU/APatch). The overflow menu has **Hide launcher icon**, which removes the app icon without breaking access from LSPosed/Vector. After changing a toggle, force-stop Camera and open it again.

## Use

1. Install the module and enable it in LSPosed or Vector. Set its scope to **Camera** only.
2. Open **HyperCameraMod** settings from the app drawer or from the module entry in LSPosed/Vector.
3. Set the toggles, then use **Force Stop Camera** in the settings toolbar (root is required; grant it in Magisk/KernelSU/APatch) and open Camera again.

The settings app has five toggles: Leica Essential mode in the standard UI (on by default), Leica UI / Looks (on), CMOS (M10R) (off), Extra Film Filters (on) and Original Portrait Style (on by default on the 17 Ultra and 18 Pro Max, off on the 14 Ultra). The Original Portrait Style default is chosen from the phone model when the app first opens; the toggle can override it on any phone. The overflow menu has **Hide launcher icon**; when the icon is hidden, settings stay reachable from LSPosed/Vector.

The system properties still work as a fallback. To add the CMOS entry without the settings app, run as root and force-stop Camera:

```bash
su -c "setprop camera.feature.m10r 1"
```

It resets on reboot (set it to 0 or reboot to turn it off). To keep it, run the command from a boot script such as Magisk's `service.sh`.

The Original portrait toggle has a property too. It is only useful on hardware that can preview it; on the 14 Ultra it restores the freeze:

```bash
su -c "setprop camera.feature.original_portrait 1"
```

It also resets on reboot.

The Leica Essential mode is added to the standard UI's mode strip, in front of "More". It is on by default; turn it off with the settings toggle, or as root:

```bash
su -c "setprop camera.feature.essential_std 0"
```

The property resets on reboot (the mode comes back). Tested on camera 6.7.000280.0 and 6.7.000500.0 (Xiaomi 14 Ultra, standard UI).

If the camera misbehaves, disable the module and force-stop Camera to restore the stock behaviour.

## Logs

The module logs to logcat under the tag `HyperCameraMod`. Clear the buffer, open Camera, reproduce the problem, then dump it:

```bash
su -c "logcat -c"
su -c "logcat -d -s HyperCameraMod"
```

The same lines are in LSPosed/Vector under **Logs** (filter `HyperCameraMod`). The useful ones look like:

```
HyperCameraMod: hooked s2.A.<method> (Leica Essential, layout 6.7)
HyperCameraMod: Leica Essential layout 6.7-hyperos3 does not match: ...
HyperCameraMod: the Leica Essential style list is not one this module knows
HyperCameraMod: remote preferences are not available: ...
```

When reporting a problem, include the phone model, HyperOS version, Camera version (for example `6.7.000500.0`), and these log lines. If a layout does not match, the `does not match` line names the class or field that is missing, which is what a new profile needs.

## Changelog

The changes in each release are in [`CHANGELOG.md`](CHANGELOG.md). The current stable release is 1.0.0.

## Build

A Gradle project. It needs the Android SDK (compileSdk 37, build-tools 37.0.0); put `sdk.dir=...` in `local.properties`, then:

```bash
./gradlew assembleRelease
```

The release builds are signed with the debug key.

## Disclaimer

An unofficial community module, not affiliated with Xiaomi or Leica. The three CMOS images in `src/main/assets/m10r` are taken from Xiaomi Camera. It hooks internal camera code and is provided as is, for personal use. Use it at your own risk.