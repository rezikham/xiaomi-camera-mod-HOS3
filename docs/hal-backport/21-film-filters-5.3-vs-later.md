# 21 - 5.3 film filters vs HyperCameraMod and later cameras

"Film filters" here means the rear photo-strip filters whose names contain both
letters and digits. Camera 5.3.000460.0 has twelve:

`600F`, `BF70`, `R600`, `P100F`, `F-50`, `KC64`, `V250`, `H400`, `KP160`,
`FC400`, `C-50D`, `KG200`.

Extracted files and a per-filter CSV are in
`/run/media/rezikham/970/PoC/camera-5.3-filters/film-filters/`.

## Byte identity

Eight of the twelve 5.3 LUTs are byte-identical to the local LUTs later cameras
ship in `/odm/etc/camera/videofilter/`:

- 6.7 and 6.8: `KC64`, `V250`, `H400`, `KP160`
- 6.8 only: `F-50`, `FC400`, `C-50D`, `KG200`

The remaining four (`600F`, `BF70`, `R600`, `P100F`) have no local LUT in the
6.7 Aurora or 6.8 Madrid `videofilter` directories. The 5.3 APK contains real
512x512 LUTs for them, so it is a local source for those slots.
`R600` and `P100F` are known cloud filters in later builds.

## Availability by version

| Version | Local film LUTs | Filter factory |
|---|---|---|
| 5.3.000460.0 | 12/12 | APK-local `res/raw` |
| 6.7.000280.0 (Aurora) | 4/12: KC64, V250, H400, KP160 | ordinals 79-90 |
| 6.8.002230.0 (Madrid) | 8/12: adds F-50, FC400, C-50D, KG200 | ordinals 79-90 |

The 6.7/6.8 filter factory (`Ii.i0` and `p832xi.h0` respectively) maps the same
ordinals `79`-`90` to strip order `12`-`23`. The missing local LUTs are
`600F`, `BF70`, `R600`, `P100F`.

## HyperCameraMod coverage

The module adds five of the twelve:

| Film | Ordinal | Cloud id | LUT situation on 6.7 / 6.8 |
|---|---|---|---|
| R600 | 81 | 56 | cloud only / cloud only |
| P100F | 82 | 57 | cloud only / cloud only |
| F-50 | 83 | 46 | no local LUT / local LUT |
| KC64 | 84 | 38 | local LUT / local LUT |
| KP160 | 87 | 58 | local LUT / local LUT |

Not exposed by the module:

- `V250`, `H400` - local LUTs already exist on both 6.7 and 6.8.
- `FC400`, `C-50D`, `KG200` - local LUTs exist on 6.8.
- `600F`, `BF70` - no local LUT on 6.7/6.8; 5.3 supplies them.

## Naming changed after 5.3

5.3 shows the raw film codes. By 6.7/6.8, eight of the twelve were renamed:

| Code | 6.7/6.8 label |
|---|---|
| F-50 | 反转 (Reversal) |
| KC64 | 韩胶 (Hanjiao) |
| V250 | 贝松 (Besson) |
| H400 | 写意 (Freehand) |
| KP160 | 季风 (Monsoon) |
| FC400 | 京都 (Kyoto) |
| C-50D | 旧巷 (Old Alley) |
| KG200 | 松弛 (Slack) |

`600F`, `BF70`, `R600` and `P100F` kept their code names.

HyperCameraMod mixes the tables: the photo strip uses the 6.8 keys
`color_effect_entry_reversal` and `color_effect_entry_hanjiao` for F-50 and
KC64, while the Leica Looks entries use the older `f_50` / `kc_64` keys. The
module-drawn icon still shows the short code.

## Module implementation

All twelve are now exposed by `filters/ModuleMain.java`; see
`22-all-film-filters.md`. Existing camera entries are reused and renamed to the
film code, so a 6.7/6.8 camera that shows 反转/韩胶/贝松/... shows the code
instead.

## Practical consequence

The 5.3 APK can fill the four missing film LUT slots (`600F`, `BF70`, `R600`,
`P100F`) and can also supply the five local-6.8 LUTs for devices that do not
have them. Adding `V250`, `H400`, `FC400`, `C-50D` and `KG200` to
HyperCameraMod would only need new profile entries, because the LUTs already
exist in 6.7/6.8; adding `600F`/`BF70` also needs the LUT files from this
extraction.
