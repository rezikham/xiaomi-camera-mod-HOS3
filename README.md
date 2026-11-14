# Xiaomi Camera Mod

LSPosed/Vector modules (libxposed API 101-102) for the Xiaomi Camera on HyperOS. **HyperCameraMod** is the all-in-one module: it turns on the Palette and Parameters panel, the Original colour style, the Leica Essential mode, brings back the film filters, and has a small settings app for the toggles. **HyperFilmFilters** remains available as the standalone film-filter module.

Install **one** of them, not both: they add the same film filters, and two copies in the same camera process would duplicate the entries.

| Module | What it does | Package |
| ------ | ------------ | ------- |
| [HyperCameraMod](cameramod/README.md) | Palette and Parameters panel, Original colour style, Leica Essential mode (standard UI and CMOS/M10R), the film filters R600, P100F, F-50, KC64 and KP160, plus a settings app with toggles, a Force Stop Camera button and an option to hide the launcher icon. | `io.rezikham.hypercameramod` |
| [HyperFilmFilters](app/README.md) | Brings the film filters R600, P100F, F-50 and KC64 (and KP160 in the Leica UI) back to the filter panel and the filter strip, in the standard UI and the Leica UI. | `io.rezikham.hyperfilters` |

Both are developed and tested on the Xiaomi 17 Ultra (Leica edition) with Xiaomi Camera 6.7 on HyperOS 4, and on the Xiaomi 14 Ultra with Xiaomi Camera 6.7 on HyperOS 3. They do not check the camera's version number: they look for what they need and, when it is not there, leave the camera unchanged and say so. Each module's README lists what it looks for.

## Requirements

| Requirement | Notes |
| ----------- | ----- |
| Xiaomi phone on HyperOS 3 or HyperOS 4 | Developed on the Xiaomi 14 Ultra (HyperOS 3) and the Xiaomi 17 Ultra (HyperOS 4). Other HyperOS phones with the same Camera 6.7 may work. |
| Xiaomi Camera 6.7 | The module targets the camera app shipped with those builds. It does not change the camera APK on disk. |
| Root: Magisk, KernelSU or APatch | Only the **Force Stop Camera** button needs root. The hooks themselves run inside LSPosed/Vector. |
| LSPosed or Vector | A build that supports the libxposed API (API 101 for the hooks, API 102 for the settings-to-camera bridge). Set the module scope to **Camera** only (`com.android.camera`). |
| No system partition changes | Nothing is written to `/system`; disable the module and force-stop Camera to return to stock. |

If the manager supports only API 101, the hooks still work, but the settings toggles cannot reach the Camera process. Use the system-property fallbacks documented in [`cameramod/README.md`](cameramod/README.md) in that case.

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

1. Install the module you want and enable it in LSPosed or Vector. Set its scope to **Camera** only.
2. Force-stop Camera and open it again.

If the camera misbehaves, disable the module and force-stop Camera to restore the stock behaviour. Nothing on the system partition is changed.

## Troubleshooting and logs

If a feature does not appear:

1. Check its toggle in **HyperCameraMod** settings, then use **Force Stop Camera** and open Camera again. The toggles are read when the Camera process starts.
2. If a layout is not recognised for that camera build, the module shows a toast in Camera once per camera version (for example, "The Leica Essential style list is different on this camera version").
3. Pull the module log and send it with a report.

**From LSPosed/Vector:** open the manager, go to **Logs**, filter for `HyperCameraMod` (or `HyperFilmFilters` for the standalone module), reproduce the problem, and copy the lines around the Camera start. The important ones look like:

```
HyperCameraMod: hooked s2.A.<method> (Leica Essential, layout 6.7)
HyperCameraMod: Leica Essential layout 6.7-hyperos3 does not match: ...
HyperCameraMod: the Leica Essential style list is not one this module knows
HyperCameraMod: remote preferences are not available: ...
```

**From a root shell or ADB:** the module mirrors its log lines to logcat under the same tag.

```bash
su -c "logcat -c"                       # clear
# open Camera, reproduce the problem, then:
su -c "logcat -d -s HyperCameraMod"
```

Or over ADB: `adb logcat -c` then `adb logcat -d -s HyperCameraMod`.

When reporting a problem, include:

- Phone model and HyperOS version
- Camera version (Settings → Apps → Camera → App info, for example `6.7.000500.0`)
- Whether the module is HyperCameraMod or HyperFilmFilters
- The `HyperCameraMod` / `HyperFilmFilters` log lines, or a screenshot of the toast
- If **Force Stop Camera** fails: whether root was granted in Magisk/KernelSU/APatch

## Build

A Gradle project with two modules. It needs the Android SDK (compileSdk 37, build-tools 37.0.0); put `sdk.dir=...` in `local.properties`, then:

```bash
./gradlew assembleRelease
```

or `:app:assembleRelease` / `:cameramod:assembleRelease` for one module. The release builds are signed with the debug key.

## Disclaimer

Unofficial community modules, not affiliated with Xiaomi. They hook internal camera code and are provided as is, for personal use. Use them at your own risk.
