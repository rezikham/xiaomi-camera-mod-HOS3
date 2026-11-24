# 22 - Exposing all 12 film filters

The filter module (`filters/ModuleMain.java`) now exposes every film stock that
Camera 5.3 had in the rear photo strip:

`600F`, `BF70`, `R600`, `P100F`, `F-50`, `KC64`, `V250`, `H400`, `KP160`,
`FC400`, `C-50D`, `KG200`.

## Behaviour

- **Version detection**: the existing profile resolution picks the layout
  (6.4, 6.7, 6.7.000500, 6.8). The 6.7/6.8 profiles use filter ordinals
  `79`-`90` and strip order `12`-`23`. Camera 6.4 keeps its older four-film
  set (`LEGACY_FILMS`) because its filter enum predates the newer ordinals.
- **Reuse before add**: `addFilms` walks the camera's finished list and matches
  an existing entry by, in order:
  1. filter ordinal;
  2. display-name resource (either the film-code key, e.g.
     `color_effect_entry_kc_64`, or the later artistic key, e.g.
     `color_effect_entry_hanjiao`);
  3. strip key (`FILM_KEY_BASE + ordinal`);
  4. cloud id.
  A matched entry is renamed and kept; a second entry for the same film is
  removed. Only when no entry matches is a new item created.
- **Names are always film codes**: new and existing entries get a made-up name
  resource id (`FILM_NAME_BASE | ordinal`). Hooks on
  `Resources.getString(int)`, `Resources.getText(int)` and
  `Resources.getString(int, Object[])` return the code. This overrides the
  artistic names used by 6.7/6.8 (反转, 韩胶, 贝松, 写意, 季风, 京都, 旧巷,
  松弛) without needing a resource in the camera APK.
- **Component and strip items** are renamed in place as well, not only the
  list handed to the filter panel, so the top banner and the strip data show
  the code too.
- **Leica Looks**: the full set is added to the Looks table; existing entries
  are reused and their name icons are drawn by the module.

## Per-version source of the LUT

| Film | 6.7 local LUT | 6.8 local LUT |
|---|---|---|
| 600F | — | — |
| BF70 | — | — |
| R600 | — (cloud) | — (cloud) |
| P100F | — (cloud) | — (cloud) |
| F-50 | — | `166_F50.png` |
| KC64 | `153_KC64.png` | `153_KC64.png` |
| V250 | `154_V250.png` | `154_V250.png` |
| H400 | `155_H400.png` | `155_H400.png` |
| KP160 | `161_KP160.png` | `161_KP160.png` |
| FC400 | — | `164_FC400.png` |
| C-50D | — | `165_C50D.png` |
| KG200 | — | `163_KG200.png` |

The 5.3 APK supplies real LUTs for the four that 6.7/6.8 lack locally
(`600F`, `BF70`, `R600`, `P100F`); see
`/run/media/rezikham/970/PoC/camera-5.3-filters/film-filters/`.

## M3/M9 slots

The item constructor stores the display-name string in field `c`, the image in
field `e`, and the name icon in field `d`.

M3/M9 originally borrowed the hidden V250 (`85`) and H400 (`86`) enum slots.
They now use **91 (`N_NEUTRAL`) and 92 (`N_SOFT`)**, which are not in the photo
filter list. The look-name redirect was updated to the new ordinals.

Verified on the device after the move:

- M3 capture: mean RGB `70.8 / 70.8 / 70.8`, saturation `0.000` (exact mono).
- M9 capture: mean RGB `74.2 / 77.7 / 74.4`, saturation `0.111` (colour).

V250 and H400 are now exposed as normal film filters, so the photo list carries
all 12 films. The module logs `46 items, 12 films`.

## Visible filter strip

Adding films to the filter list is not enough on Aurora: the visible strip is
built from the camera's filter **component**, and the cloud merge leaves the
local film entries out. The component hook rebuilds from the local list whenever
it holds fewer than the expected 12 films. The visible strip itself is a saved
subset; use the strip's **+ → Manage filters** screen to add the remaining
films. The Manage filters list shows M3, M9, V250, H400, C-50D, KG200 and the
other film codes.

This requires the **M3 and M9 as their own Looks entries** setting to be ON;
that switch also enables the main module's strip entries. With it OFF, M3/M9 are
not added and the strip loses them.

## Testing notes

- Expected log lines: `film list: ... reused ... added ...`,
  `hooked Resources.getString (film names)`, and the usual
  `camera layout 6.7/6.8` line.
- If a filter is still missing, the camera's list may be built before the
  module hooks; capture `HyperFilmFilters` logcat. The fallback is the
  category-2/category-10 list hook, which runs on every list request.
- Camera 5.3 is not a supported module profile; the 5.3 extraction is the
  reference source for names and LUTs, not a hook target.

## LUTs bundled with the KSU module

The KSU module (`camera-mod-ksu-v1.1.0-254-lut.zip`) stages all 14 LUTs into
`/data/vendor/camera`: `lut_m3.png`, `lut_m9.png`, and the 12 film-filter LUTs
(`lut_600f.png` ... `lut_kg200.png`). They are copied at `post-fs-data` with
mode `0644` and SELinux label `vendor_camera_data_file`, and the engine gate
`camera.debug.mivi3.output.jpeg=true` is set.

Caveat: bundling the LUT files only guarantees they exist on disk. Whether a
film filter actually renders its look depends on the camera version's filter
factory referencing the right path/resource for that film. On 6.7/6.8, eight
of the twelve film LUTs already exist locally in `/odm/etc/camera/videofilter/`;
`600F`, `BF70`, `R600` and `P100F` are cloud-only there, so the bundled copies
are the only local source for those four.

## Preview vs capture LUT paths

Capture and preview resolve the look differently:

- Capture uses `MIVIRenderTag.initParams`, which calls `Di.k.d(id, degree)`.
  That is the hook that returns the `lut_m3`/`lut_m9` names.
- Preview resolves the LUT through
  `com.xiaomi.utils.OpenGl3dUtils.a(Context, String, boolean, boolean)`. It
  logs `LUT currentLutName: <name>` and loads the bitmap through `Xu.g.b`,
  which is `Resources.getIdentifier(name, "raw", package)`. Preview LUTs are
  therefore APK raw resources, not files in `/data/vendor/camera`.

Stock films ask for their own enum LUT name (for example V250 requests
`lut_normal_v_5207`). Filter ids 91/92 are not in that table, so the render
engine asks for the neutral fallback name **`lut_nc`** for both M3 and M9.
The `lut_m3`/`lut_m9` names are never requested by the preview, and remapping
only those names did nothing.

The fix (APK 305) is:

- Hook `OpenGl3dUtils.a`, `Xu.g.b` and `Xu.g.a`.
- If the requested name is `lut_nc`, `"0"` or `lut_debug_blank_lut` and the
  live filter id is 91 or 92, return a freshly decoded M3/M9 bitmap.
- Map `lut_normal_neutral` to M3 and `lut_normal_soft` to M9 for the
  thumbnail/cloud paths that still ask for the slot names.
- Read the live filter id from `EffectController.u().h` (public int, default
  `512`) instead of tracking `EffectController.e0`. `e0` is a generic effect
  setter and is also called with unrelated ids (for example `66048`), so an
  event-tracked flag is unreliable.

`ensureLookFile` validates the staged file instead of trusting it blindly. The
M9 file must match md5 `ecbafbf8130ba7340749e95ace25616b`; M3 must match the
authentic 17U `M3_Monopan` md5 `c4ff65cebdcd8edfa7146c6ab2f838f9`. Any other
file is replaced. The older M3 md5 `167c769a022945ea5bce80985b344209` was a
reproduced fallback, not the authentic extraction; Camera 5.3 has no M3/M9
filters. It is rejected so the reproduced fallback cannot survive.

The M9 look is Leica Natural and is intentionally subtle: applying the LUT to a
typical low-saturation scene changes mean RGB by about 2.6% and reduces mean
saturation from 0.039 to 0.031. M3 is the exact monochrome look. KP160 is a
stronger film reference (about 5.6% mean difference).

## Native crash root cause

The earlier SIGSEGV was not a preview-format mismatch. Native analysis of
`libMiFilterSDK.so` shows `CandySDK_setLutBitmap` is valid: it reads a 512x512
ARGB bitmap, tiles it into 64x64 blocks (exactly 1 MB) via `FUN_0017bae0`, and
uploads it as a 3D LUT.

The crash came from returning **cached** bitmaps to consumers that recycle
them. `OpenGl3dUtils.c` recycles the bitmap after the GL upload, and the Candy
SDK keeps the bitmap it is given. On the next request the cached instance was
already recycled, `AndroidBitmap_lockPixels` failed, `CandySDK_setLutBitmap`
ignored the return code and read an uninitialized pixel pointer, and
`FUN_0017bae0` crashed in `memcpy`.

The final code therefore never returns a cached bitmap on these paths. It
decodes a fresh 512x512 bitmap from the module APK for every request. This
also removed the crash on repeated M3/M9 switching.

Tombstone pattern for the old cached-bitmap build:

```text
#00 __memcpy
#01 libMiFilterSDK.so
#02 libMiFilterSDK.so (Java_com_xiaomi_milab_filtersdk_CandySDK_setLutBitmap)
#03 art_jni_trampoline
#05 CandySDK.g
```

The Candy SDK is still only probed (logging); no LUT is injected into it. The
preview fix is purely the `OpenGl3dUtils`/`Xu.g` bitmap substitution.

## Preview status: shipped

APK 305 fixes the live preview:

- M3: exact monochrome, preview saturation `0.0`.
- M9: subtle Leica Natural look, preview saturation about `7.0` versus `8.7`
  for Original on the same low-saturation scene.
- A nine-step live sequence M3/M9/M3/M9/Original/M3/M9/M3/M9 produced the
  same values every time, with the camera process alive and no fatal signal.

The stock `lut_normal_neutral.png`/`lut_normal_soft.png` files on
`/data/vendor/camera` are still written by `MIVILutSaver` for capture, but the
preview no longer depends on the app reading that directory.
