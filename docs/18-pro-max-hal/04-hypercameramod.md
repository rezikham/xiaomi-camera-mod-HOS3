# Implementing the `madrid` findings in HyperCameraMod

This document maps the firmware findings to the module’s current code and
lists the changes that are actually needed. The short version: on
`madrid` + Camera 6.8, the only module work that remains is verification. The
HAL and the app already implement M10R, the Legend styles, the four M9/M10R
style-transfer models, the original bit, and the film-filter data.

## Current module state

### Device detection

`src/main/java/io/martin/hypercameramod/DeviceSupport.java`

```java
public static final int XIAOMI_18_PRO_MAX = 3;

if (name.contains("18 PRO MAX") || name.contains("18 PROMAX")) {
    return XIAOMI_18_PRO_MAX;
}

public static boolean originalPortraitDefault() {
    int family = family();
    return family == XIAOMI_17_ULTRA || family == XIAOMI_18_PRO_MAX;
}
```

[observed] `ro.product.marketname` is `Xiaomi 18 Pro Max` and
`ro.product.device` is `madrid`, so `family()` returns `3` and the Original
portrait default is already on. No change is needed.

### Stylization / Original bit

`ModuleMain.java` lines 54–55:

```java
private static final String STYLIZATION_KEY = "xiaomi.capabilities.stylizationTypeMask";
private static final int STYLIZATION_ORIGINAL = 1 << 3;
```

The hook at lines 471–478 ORs bit 3 into the characteristic. On `madrid`, the
HAL already reports `14` (`0b1110`) or `12` (`0b1100`) on the seven cameras
that expose the key, so the OR is a no-op. Keeping it is harmless and still
needed for older models.

### Legend hook

`ModuleMain.java` lines 63–66:

```java
private static final LegendProfile[] LEGEND_PROFILES = {
        new LegendProfile("6.7", "s2.A", "com.android.camera.data.data.d",
                "ci.e", "ci.b"),
};
```

On 6.8, `installLegendHook` tries the 6.7 class, fails, and logs:

```text
the Leica Essential style list is not one this module knows; the CMOS style is not added
```

That message is misleading on `madrid`: the camera itself already adds the
M10R item (see [02-legend-m10r.md](02-legend-m10r.md#native-m10r-in-camera-68)).
The module does not need to add it, but it also should not imply the style is
missing.

### Film filters

`src/main/java/io/martin/hypercameramod/filters/ModuleMain.java` lines 97–107
already contain the 6.8 profile and the five films. See
[03-film-filters.md](03-film-filters.md) for the ID table. Nothing is missing
from the static mapping; only a device log is needed to confirm the panel /
Looks / workspace candidates.

## Feature-by-feature decision table

| Feature | HAL | Camera 6.8 app | Module today | Action |
| ------- | --- | -------------- | ------------ | ------ |
| Original colour bit | native bit 3 | reads mask | ORs bit 3 | Keep; no-op on `madrid`. |
| Original portrait | `libAncHumanPreviewBokeh.so` | supported | defaults on for `madrid` | Keep. |
| Leica Essential M3/M9 | `legendCT`, `legendST`, params | list built by `r2.B` | 6.7 profile only | No injection needed. |
| CMOS M10R | `LegendM10R`, M10R params, pipeline | native item, default on `C1217` | 6.7 profile only; default toggle off | Do **not** add on `madrid`; update the docs/status. |
| Legend bokeh 35/75, apertures | `legendMode.capsInfo` | parsed natively | none | No module work. |
| Film filters R600/P100F/F-50/KC64/KP160 | LUT / cloud | filter enum includes them | 6.8 profile present | Verify on device. |
| `legendmode` EXIF 34992 | written by HAL for real Legend captures | Gallery reads it | none | No module work. |

## What M10R actually requires

[observed] M10R is not only a picker entry. A working M10R capture on `madrid`
uses all of the following:

* the `legendST` plugin and `libmialgo_styletrans.so`;
* `styletrans_high_m10r.minn` and `styletrans_low_m10r.minn` under
  `/odm/etc/camera/styletrans/compressed/`;
* `leica_filter_param_m10r_preview.bin` and
  `leica_filter_param_m10r_snapshot.bin`;
* the `mileicafilter` M10R branches and the Legend snapshot pipeline.

[inferred] Adding an `"M10R"` item to a device that lacks those models would
make the picker show a style that cannot be rendered, or would fall back to M9
silently. Any future port should gate the UI item on the models and plugin
being present, not only on the app class name.

## Recommended changes

### 1. Do not add a 6.8 M10R injection profile for `madrid`

The 6.8 app already adds M10R when the device profile is `C1217`, which is
`madrid`. Injecting a second item would at best be ignored (the hook checks
for an existing `"M10R"` item) and at worst confuse the picker’s adapter if it
were added before the native list is built.

The current 6.7-only profile is therefore correct for the target device.

### 2. Avoid the misleading “not one this module knows” warning on 6.8

A small guard in `installLegendHook` would make the log honest:

```java
private void installLegendHook(ClassLoader loader) {
    // Camera 6.8 + madrid already builds M3, M10R, M9 natively.
    // Adding the 6.7 layout would fail and only produce a misleading warning.
    if (DeviceSupport.family() == DeviceSupport.XIAOMI_18_PRO_MAX
            && classExists("r2.B", loader)) {
        log(Log.INFO, TAG,
                "CMOS (M10R) is native on this camera build; nothing to add");
        return;
    }
    ...
}
```

`r2.B` is the 6.8 `ComponentConfigLegendary` class. Checking for it is
enough; no version string is needed. Add the small `classExists` helper next
to the existing `findBuilder`/`allocate` helpers (or reuse whatever helper the
legend hook already has for optional classes).

This is optional, but it prevents support reports that say “M10R is not added”
on a phone where it is already present.

### 3. Update the README status

`README.md` currently says:

```text
| CMOS (M10R) | ❌ HAL limitation | Experimental ... | Not verified |
```

For the 18 Pro Max column, the evidence supports:

```text
| CMOS (M10R) | ... | ... | ✅ Native (HAL + Camera 6.8) |
```

Also update the tested-builds row’s camera remark from “film filters
(experimental, pending verification)” once the device log confirms the filter
strip. Link this directory from the README so the evidence is discoverable.

### 4. Optional: a 6.8 `LegendProfile` for non-`madrid` devices

If a 6.8 build appears on a device whose profile is **not** `C1217`, the app
will not add M10R. A profile would then be useful. The 6.8 runtime names are:

```java
new LegendProfile(
        "6.8",
        "r2.B",                                  // ComponentConfigLegendary
        "com.android.camera.data.data.d",        // item (C2784d at build time)
        "Rh.e", "Rh.b")                          // string ids / drawable ids
```

Do **not** enable this for `madrid`; it is only a fallback for other 6.8
devices. Validate it on a non-`C1217` 6.8 build before shipping, because the
resource-id classes are obfuscated differently per build.

The existing hook is already safe when the list contains M10R: it scans the
items for `"M10R"` and returns without changing anything.

### 5. Optional: expose the M10R default as a setting note

The 6.8 app defaults to M10R on `madrid` (`getDefaultValue` returns `"M10R"`
when `b3()` is true). The module’s `m10r_enabled` toggle therefore cannot
“turn M10R on” on this phone; it can only control the injection path used on
older builds. The settings text could say so, or the toggle could be hidden on
6.8/madrid. This is cosmetic and not required for function.

## What not to do

* Do not force `com.xiaomi.sessionparams.legendMode` from the module. The
  numeric style mapping is not fully established
  ([02-legend-m10r.md](02-legend-m10r.md#unknowns)), and the camera already
  sets it correctly.
* Do not add the `legendmode` EXIF tag from the module. The tag is written by
  the HAL during a real Legend capture; faking it would make Gallery show a
  LEICA button for an unprocessed image.
* Do not add a second M10R item to the 6.8 list on `madrid`.
* Do not expose M10R on a device that lacks the M10R `.minn` models and
  `legendST`; the picker entry alone does not process anything.
* Do not patch `legendmode.json`; it is an encrypted blob, not a style table.

## Test plan

### Static checks before flashing

```bash
./gradlew assembleRelease
```

The module compiles with the existing 6.8 filter profile and no legend change.

### On-device checks

1. **Capability mask**

   ```bash
   adb shell dumpsys media.camera | grep -A1 stylizationTypeMask
   ```

   Expect `14` or `12`, never a value without bit 3 on the seven cameras that
   expose the key.

2. **Legend styles**

   Open Camera → Leica Essential style picker. Expect M3, M10R, M9 in that
   order, with M10R selected by default. The module log should not claim the
   style is missing.

3. **Real M10R capture**

   Take a photo with M10R. Check the EXIF tag `legendmode` (`34992` /
   `0x88b0`) and the Gallery LEICA button. A plain photo without the tag means
   the HAL did not process the request, not that the app list is wrong.

4. **Film filters**

   ```bash
   adb logcat -s HyperFilmFilters
   ```

   Open the filter strip. Expect the 6.8 profile to hook the list and
   workspace; R600, P100F, F-50, KC64 should appear in Photo, KP160 in Looks.

5. **Original portrait**

   Open Portrait and check that Original is present and that the preview does
   not stall. `libAncHumanPreviewBokeh.so` is the same family as the 17 Ultra,
   so the 14 Ultra freeze is not expected here.

### Log capture

Use `tools/pull_camera_log.sh -s <serial>` or the on-device
`tools/hcm-log.sh`. The relevant tags are:

```text
HyperCameraMod
HyperFilmFilters
CameraCapabilities
LeicaFilter
```

`CameraCapabilities` prints the parsed `legendMode.capsInfo` values and the
`support Legenda Mode` line. `LeicaFilter` prints the M9/M10R parameter
selection. Neither requires root.

## Acceptance criteria

* [ ] `./gradlew assembleRelease` succeeds.
* [ ] On `madrid` 6.8, the style picker shows M3, M10R, M9 and defaults to M10R.
* [ ] M10R capture carries EXIF `legendmode` and Gallery shows LEICA.
* [ ] Photo filter strip shows R600, P100F, F-50, KC64.
* [ ] Looks strip shows KP160.
* [ ] Original portrait preview works.
* [ ] Module log contains no false “CMOS style is not added” warning (after the
      optional guard).
