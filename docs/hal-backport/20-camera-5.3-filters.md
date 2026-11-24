# 20 - Filters extracted from Camera 5.3.000460.0

Source: `/run/media/rezikham/970/PoC/com.android.camera_5.3.000460.0.apk`
(package `com.android.camera`, version `5.3.000460.0`).
Extraction output: `/run/media/rezikham/970/PoC/camera-5.3-filters/`.

## Why this APK matters

Unlike Camera 6.7, this build stores the actual colour-filter LUTs as
`res/raw/*` resources instead of relying only on the HAL. The APK contains
99 unique 512x512 LUT resources. The filter lists are built in
`com.android.camera.effect.o` from the `n2.e` enum; the UI label for each
entry comes from a string resource.

## Output layout

```
camera-5.3-filters/
  README.md
  index.csv              # 99 unique LUTs: UI name, raw key, size, blank flag
  by-mode.csv            # filter-strip order for every mode
  luts/                  # 74 real LUTs, named by UI label
  placeholders/          # 25 blank (2,095-byte) LUTs
  by-mode/photo-back/    # rear photo filter strip, numbered
  by-mode/photo-front/   # front photo filter strip
  by-mode/portrait/      # portrait filter strip
  by-mode/video/         # video filter strip
  by-mode/makeup/        # makeup looks
  by-mode/lighting/      # 3D lighting effects
  film-filters/          # the 12 letter+number film-code filters
```

The `film-filters/` folder is the subset whose names contain both letters and
digits; its comparison against HyperCameraMod and the 6.7/6.8 cameras is in
`21-film-filters-5.3-vs-later.md`.

## Format

All real LUTs are 512x512 PNG/WebP images in the 8x8 tile layout: 64 blue
slices of 64x64 pixels, each holding a 64x64 red/green lattice. This is the
same layout the stock HAL filter node consumes, so the files can be used
directly as `lut_m3.png`/`lut_m9.png`-style slot replacements.

The blank 2,095-byte files are identity/placeholder LUTs. They are used by the
six Leica entries in Camera 5.3's photo/portrait strips (Leica VIV, Leica NAT,
Leica BW NAT, Leica BW HC, Leica Sepia, Leica Blue) and by the CV-style
entries; the real Leica processing is not stored in this APK.

## Naming notes

The filenames use the UI strings, not the internal resource names:

| Internal raw key | UI label (EN / ZH) |
|---|---|
| `normal_negative_film` | Negative / 负片 |
| `normal_forest_green` | Pine / 森绿 |
| `normal_warm_blue` | Blue dream / 暖青 |
| `normal_flower_dream` | Glee / 繁花如梦 |
| `normal_distinct` | Bright / 鲜明 |
| `normal_blackgold` | Gold vibes / 黑金 |
| `normal_lively` | Vivid / 生动 |
| `normal_mint` | Mint / 薄荷 |
| `normal_whiteandblack` | B&W / 黑白 |
| `video_blackgold` | Gold vibes / 黑金 |
| `video_cyberpink` | Cyberpunk / 赛博朋克 |
| `makeup_sweet` | 女粉 (Verve in the front photo strip) |
| `makeup_cool` | 女蓝 |
| `makeup_hardline` | 男刚 |
| `makeup_soft` | 男柔 |

The full mapping is in `index.csv` and `by-mode.csv`.

## Filters with no LUT in this APK

The **master filter** strip (Poetry, Nocturne, Amber, Bygone, Charm,
Daguerreotype, Mystery, BBP, FR500, FE250, etc.) and the **cinematic** strip
(Scarlet, Vermilion, Teal mist, Ink, Green tea, Hazelnut) are listed in
`by-mode.csv` with their UI names, but their LUTs are not stored as `res/raw`
resources in this APK. They are rendered by a different component (HAL/plugin
or embedded native data).

## Duplicates

- `normal_blackice` and `video_blackice` are byte-identical.
- `normal_sibopenk` and `video_cyberpink` are byte-identical.
- `lighting_2_lanshan` and `lighting_2_phantom` are byte-identical.
- All 25 placeholder files share the same 2,095-byte identity LUT.
