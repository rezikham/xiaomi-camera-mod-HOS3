# HyperFilmFilters

An LSPosed/Vector module (libxposed API 101-102) that brings back the film filters from old Xiaomi Camera to the filter panel of the new Xiaomi Camera (v6), on HyperOS.

HyperCameraMod now includes the same film filters; install only one of the two modules, or the filters are added twice.

The Palette and Parameters panel, the Original colour style and the Leica Essential CMOS style are in a separate module, [HyperCameraMod](../cameramod/README.md).

## What it does

Adds film filters that the camera's current filter list leaves out. In the standard UI:

| Filter | Shown in the panel as |
| ------ | --------------------- |
| R600 | R600 |
| P100F | P100F |
| F-50 | Reversal |
| KC64 | Orchid |

KP160 is already in the standard panel as "Monsoon", so the module leaves it alone there; the Leica UI list does not have it, so the module adds it. The LUTs for all five are already part of the camera app; the module only makes them appear in the list.

The standard panel shows F-50 as "Reversal" and KC64 as "Orchid", the camera's own display names. The Leica UI ("Looks" panel) lists all five filters (R600, P100F, F-50, KC64, KP160) by their film codes.

## Camera versions

The module does not check the camera's version number. It looks at the camera's filter code and works with any build whose code has the layout it knows (a "profile"). The camera is obfuscated, so class names change between builds; the module tries each known profile in turn and uses the first that fits.

| Profile | Camera build | From | Status |
| ------- | ------------ | ---- | ------ |
| 6.7 | 6.7.000280.0 | HyperOS 4 (Xiaomi 17 Ultra) | Tested: standard UI and Leica UI. |
| 6.4 | 6.4.000430.0 | HyperOS 3 (Xiaomi 14 Ultra) | Tested: standard UI filter panel. |

Other builds, such as one from another HyperOS release, may work if their filter code is unchanged, but only the profiles above are tested. The film code exists in older builds too (5.3 from HyperOS 2, 6.3 from HyperOS 3), and the filter LUTs are byte-identical across them, but those builds name their classes differently, so they need their own profile. The 6.4 profile is the one for those builds that has been done.

### When the camera's filter code is different

If the module can't find the code it expects, it leaves the camera unchanged (nothing is hooked, so the camera behaves as stock) and tells you:

- A toast appears in Camera a few seconds after it starts, once for each camera version, for example "HyperFilmFilters (camera 6.9.000100.0): The camera's filter code is different from the layouts this module knows, so no film filters were added."
- The same is written to the log (see Logs).

Smaller problems get their own message: the filter panel hook failing ("Some film filters may not show in the filter panel"), or, on a phone that uses the Leica UI, the Looks list hook failing ("The Leica Looks list is different on this camera version").

To get support for a new camera build, open an issue with the camera version and the `HyperFilmFilters` log lines.

## How it works

1. **Add the items.** The camera builds its photo filter list in one method. The module hooks it and appends the four missing items, using the camera's own names and thumbnails. Resource ids come from the camera's resource classes, or are looked up by name when a build has no such class.
2. **Skip the cloud merge.** The filter panel merges the local list with the cloud filter list and drops any local filter the cloud list does not name. The module makes the panel's merge check return `false`, so the panel uses the local list as it is.
3. **Leica UI.** The Leica UI builds its own "Looks" list from a separate table. The module appends the five filters there too.
4. **Order.** The camera's filter strip adds every filter it does not have yet at position 1, one after the other, so the films would come first and in reverse. After the strip has reloaded (and once when the camera opens) the module moves the films to the end and leaves everything else where it is, so an order you set in Manage filters is kept. Films that are new to the strip (a fresh install, or a strip the camera rebuilt) get the default order R600, P100F, F-50, KC64 (and KP160 in the Leica UI); films already in the strip keep the order the strip has. A strip saved before the films existed does not get them until the camera next reloads its cloud data, so when the camera opens the module also asks the strip to reload, which adds the filters the camera's list has and the strip lacks. The standard UI's strip is built from a second filter list, which the module adds the films to as well.

Profile 6.4 works the same way in outline, on different code: the camera keeps its filter lists in `EffectController`, one list per category, and a component class (`f0.M`) builds the filter panel's items from the list, or from the list merged with cloud filter data. The module appends the films to the lists for categories 10 and 2, then checks the component's items and, if the merge left the films out, builds them again from the list alone. It adds the films after the camera's own filters, which gives the order Monsoon, R600, P100F, Reversal, Orchid in the panel. The Leica UI, the filter strip's order and the Leica-style film labels are not covered by this profile.

| Target (profile 6.7) | Purpose |
| -------------------- | ------- |
| `Ii.i0.d()` | Photo filter list (standard UI): appends R600, P100F, F-50 and KC64. |
| `com.android.camera.fragment.m0.ut()` | Filter panel: returns `false` so the list is not merged with the cloud list. |
| `E9.h.c()` | Leica "Looks" list: appends R600, P100F, F-50, KC64 and KP160. |
| `com.android.camera.features.mode.capture.e` (the strip's reload and set-up methods) | Filter strip: moves the films to the end, keeps the rest. |
| `com.xiaomi.camera.effect.EffectController.q` | Filter list of the standard UI: adds the films. |

| Target (profile 6.4) | Purpose |
| -------------------- | ------- |
| `com.android.camera.effect.EffectController.n(int)` | Filter lists by category: appends R600, P100F, F-50 and KC64 to categories 10 and 2. |
| `f0.M.mapToCloudItems(int, Map)` | Filter component: builds it again from the list when the cloud merge dropped the films. |

## Use

1. Install the module and enable it in LSPosed or Vector. Set its scope to **Camera** only.
2. Force-stop Camera and open it again.
3. For the standard UI on a phone that uses the Leica UI, turn the Leica theme off. This resets on reboot:

   ```bash
   su -c "setprop camera.debug.theme_customize.disabled true"
   ```

   Set it back to `false` and force-stop Camera to return to the Leica UI.

If the camera misbehaves, disable the module and force-stop Camera to restore the stock behaviour. Nothing on the system partition is changed.

## Logs

The module logs to logcat under the tag `HyperFilmFilters`, not to the manager's module log:

```bash
su -c "logcat -d | grep HyperFilmFilters"
```

Useful lines:

- `camera layout 6.7, hooked ...`: a profile fit and the hooks are installed.
- `filter list: N items, added [...]`: the items were added.
- `does not match` or `cannot hook ...`: the camera's filter code differs from that profile.
- `the camera's filter code is not one this module knows`: no profile fit, so the camera was left unchanged.

## Debug output

The module logs to logcat under the tag `HyperFilmFilters`. Besides the normal lines it writes a few `debug:` lines: the camera and Android versions, the device, and the layout (profile) it chose at start, and the filter keys of each filter list and of the filter component (once per distinct list). They show which filters the camera had and what the module added, so a log is enough to see why a filter is missing.

```bash
su -c "logcat -d | grep HyperFilmFilters"
```

`su -c "setprop camera.feature.filters_debug 0"` turns the `debug:` lines off (it resets on reboot).

## Build

A Gradle project. It needs the Android SDK (compileSdk 37, build-tools 37.0.0); put `sdk.dir=...` in `local.properties`, then:

```bash
./gradlew :app:assembleRelease
```

The release build is signed with the debug key.

## Disclaimer

An unofficial community module, not affiliated with Xiaomi. It hooks internal camera code and is provided as is, for personal use. Use it at your own risk.
