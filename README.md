# Xiaomi Camera Mod

LSPosed/Vector modules (libxposed API 101-102) for the Xiaomi Camera on HyperOS. **HyperCameraMod** is the all-in-one module: it turns on the Palette and Parameters panel, the Original colour style, the Leica Essential mode, brings back the film filters, and has a small settings app for the toggles. **HyperFilmFilters** remains available as the standalone film-filter module.

Install **one** of them, not both: they add the same film filters, and two copies in the same camera process would duplicate the entries.

| Module | What it does | Package |
| ------ | ------------ | ------- |
| [HyperCameraMod](cameramod/README.md) | Palette and Parameters panel, Original colour style, Leica Essential mode (standard UI and CMOS/M10R), the film filters R600, P100F, F-50, KC64 and KP160, plus a settings app with toggles, a Force Stop Camera button and an option to hide the launcher icon. | `io.martin.hypercameramod` |
| [HyperFilmFilters](app/README.md) | Brings the film filters R600, P100F, F-50 and KC64 (and KP160 in the Leica UI) back to the filter panel and the filter strip, in the standard UI and the Leica UI. | `io.martin.hyperfilters` |

Both are developed and tested on the Xiaomi 17 Ultra (Leica edition) with Xiaomi Camera 6.7 on HyperOS 4, and on the Xiaomi 14 Ultra with Xiaomi Camera 6.7 on HyperOS 3. They do not check the camera's version number: they look for what they need and, when it is not there, leave the camera unchanged and say so. Each module's README lists what it looks for.

## Settings

HyperCameraMod has a settings app. Open it from the app drawer ("HyperCameraMod") or from the module's entry in LSPosed/Vector.

| Toggle | Default | What it changes |
| ------ | ------- | --------------- |
| Leica Essential Mode in Standard UI | on | Adds the Leica Essential mode (M3/M9) to the standard UI mode strip. |
| Leica UI (Looks) | on | Shows the Leica Looks panel in the filter strip instead of the standard filter UI. |
| CMOS (M10R) Style | off | Adds the CMOS (M10R) style to the Leica Essential style list. |
| Extra Film Filters | on | Adds the R600, P100F, F-50, KC64 and KP160 film filters. |

The toolbar has a **Force Stop Camera** button (root is required; grant it in Magisk/KernelSU/APatch). The overflow menu has **Hide launcher icon**, which removes the app icon without breaking access from LSPosed/Vector. After changing a toggle, force-stop Camera and open it again.

## Use

1. Install the module you want and enable it in LSPosed or Vector. Set its scope to **Camera** only.
2. Force-stop Camera and open it again.

If the camera misbehaves, disable the module and force-stop Camera to restore the stock behaviour. Nothing on the system partition is changed.

## Build

A Gradle project with two modules. It needs the Android SDK (compileSdk 37, build-tools 37.0.0); put `sdk.dir=...` in `local.properties`, then:

```bash
./gradlew assembleRelease
```

or `:app:assembleRelease` / `:cameramod:assembleRelease` for one module. The release builds are signed with the debug key.

## Disclaimer

Unofficial community modules, not affiliated with Xiaomi. They hook internal camera code and are provided as is, for personal use. Use them at your own risk.
