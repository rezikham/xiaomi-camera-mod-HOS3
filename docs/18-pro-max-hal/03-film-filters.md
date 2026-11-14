# Film filters and LUTs on `madrid`

The film filters are ordinary LUT effects. The LUT data is shipped in
`/odm/etc/camera/videofilter/`, and Camera 6.8 builds the picker entries from
its own filter enum. HyperCameraMod does not add new LUT data; it restores the
list entries the camera hides.

## LUT inventory

[observed] `970/extracted_madrid/odm/etc/camera/videofilter/` has 93 files.
Most filters have a pair: a `.cube` 3D LUT and a `.png` thumbnail. Some newer
entries have only the `.png` thumbnail in this directory; their LUT data is
either generated on device or downloaded from the cloud filter service.

### Filters with a `.cube` LUT

| ID | Name | Files |
| -- | ---- | ----- |
| 110 | Black Gold | `110_BlackGold.cube` + `.png` |
| 129 | Leica Vivid | `129_Vivid.cube` + `129_LeicaVivid.png` |
| 130 | Leica Natural | `130_Natural.cube` + `130_LeicaNatural.png` |
| 131 | Monochrome True | `131_MonochromeTrue.cube` + `.png` |
| 132 | Monochrome High Contrast | `132_MonochromeHighContrast.cube` + `.png` |
| 133 | Monochrome Brown | `133_MonochromeBrown.cube` + `.png` |
| 134 | Monochrome Blue | `134_MonochromeBlue.cube` + `.png` |
| 144 | Red Hero | `144_RedHero.cube` + `.png` |
| 146 | Tactile Green | `146_TactileGreen.cube` + `.png` |
| 148 | Mild | `148_Mild.cube` + `.png` |
| 149 | Lily White | `149_LilyWhite.cube` + `.png` |
| 150 | Bright | `150_Bright.cube` + `.png` |
| 151 | Fresh | `151_Fresh.cube` + `.png` |
| 152 | Limpid | `152_Limpid.cube` + `.png` |
| **153** | **KC64** | `153_KC64.cube` + `.png` |
| 154 | V250 | `154_V250.cube` + `.png` |
| 155 | H400 | `155_H400.cube` + `.png` |
| 156 | Cold White | `156_ColdWhite.cube` + `.png` |
| 157 | Native | `157_Native.cube` + `.png` |
| 158 | B&W Classical | `158_BWClassical.cube` + `.png` |
| 159 | Flowers | `159_Flowers.cube` + `.png` |
| 160 | Vivid | `160_Vivid.cube` + `.png` |
| **161** | **KP160** | `161_KP160.cube` + `.png` |
| 162 | Natural | `162_Natural.cube` + `.png` |

### Thumbnail-only entries

These have a `.png` in the same directory but no local `.cube`:

```text
102_SummerDay.png          103_Fantasy.png           104_Meet.png
105_WindSing.png           106_Lost.png              107_Central.png
108_NorthernEurope.png     109_Rome.png              111_GreenOrange.png
112_CyberPunk.png          113_BlackIce.png          121_Bleach.png
122_Thriller.png           123_TealOrange.png        124_JapaneseStyle.png
125_Latin.png              126_Fantasy2.png          127_Creamy.png
128_Sepia.png              135_Classical.png         136_Filene.png
137_GreenNight.png         138_LiteratureAndArt.png   139_OrangeHoney.png
140_Romance.png            141_FR500.png             142_FE250.png
143_RedLantern.png         145_TactileBlackWhite.png 147_Neutral.png
163_KG200.png              164_FC400.png             165_C50D.png
166_F50.png                167_CC.png                168_NC.png
169_LeicaChrome.png        170_LeicaClassic.png      171_LeicaContemporary.png
172_LeicaEternal.png       173_LeicaBrass.png        174_LeicaTeal.png
175_LeicaIModelA.png
```

The module’s filters are:

| Module name | Local LUT | Thumbnail |
| ----------- | --------- | --------- |
| R600 | none found | resource only |
| P100F | none found | resource only |
| F-50 | none found | `166_F50.png` |
| KC64 | `153_KC64.cube` | `153_KC64.png` |
| KP160 | `161_KP160.cube` | `161_KP160.png` |

[inferred] R600 and P100F are cloud filters: their names appear in Camera 6.8
resources but not in the local `videofilter` directory. The device fetches the
LUT from Xiaomi’s cloud filter service when the filter is first used. F-50 has
a local thumbnail (`166_F50.png`) but no local `.cube`; it is likely also
cloud-backed or generated. This matches the module’s experimental status for
6.8 film filters.

## Camera 6.8 filter list

[observed] `p832xi/h0.java` is the 6.8 filter factory (`FilterFactory`). It
maps the filter enum ordinal to a `p291i3.b` item:

```java
b bVar = new b(10, dVar.ordinal(), entryRes, imageRes, paletteRes, order, "FILM");
bVar.f46928l = cloudId;
```

Relevant switch branches:

| Ordinal | Filter | Entry resource | Image resource | Cloud id | Sort order |
| ------- | ------ | -------------- | -------------- | -------- | ---------- |
| `81` | R600 | `color_effect_entry_r_600` `2132018488` | `color_effect_image_r_600` `2131231298` | `56` | `14` |
| `82` | P100F | `color_effect_entry_p_100f` `2132018486` | `color_effect_image_p_100f` `2131231295` | `57` | `15` |
| `83` | F-50 | `color_effect_entry_reversal` `2132018489` (in the Looks table `color_effect_entry_f_50` `2132018411`) | `color_effect_image_f_50` `2131231284` | `46` | `16` |
| `84` | KC64 | `color_effect_entry_hanjiao` `2132018421` (in the Looks table `color_effect_entry_kc_64` `2132018423`) | `color_effect_image_c_64` `2131231278` | `38` | `17` |
| `87` | KP160 | `color_effect_entry_monsoon` `2132018466` (in the Looks table `color_effect_entry_kp_160` `2132018426`) | `color_effect_image_p_160nc` `2131231296` | `58` | `18` |

The ordinals and cloud IDs match the `filters/ModuleMain.java` profile:

```java
private static final Film[] FILMS = {
    new Film(81, "R600",  "color_effect_entry_r_600",  "color_effect_image_r_600",  14, 56),
    new Film(82, "P100F", "color_effect_entry_p_100f", "color_effect_image_p_100f", 15, 57),
    new Film(83, "F-50",  "color_effect_entry_reversal","color_effect_image_f_50",  16, 46),
    new Film(84, "KC64",  "color_effect_entry_hanjiao", "color_effect_image_c_64",  17, 38),
};

private static final Film[] LOOKS_FILMS = {
    ... new Film(87, "KP160", "color_effect_entry_kp_160", "color_effect_image_p_160nc", 18, 58),
};
```

This is why the existing 6.8 profile needs only a runtime verification pass,
not new IDs.

## The local cloud-filter table

[observed] The APK ships two cloud-filter JSON files in
`assets/cloudfilter/`:

```text
cloud_filter_custom.json      28 KB
cloud_filter_not_custom.json  22 KB
```

They contain the cloud filter catalogue (Chinese names) and per-module
`backFilterList` / `frontFilterList` arrays. The local camera’s photo module
list is:

```text
1, 73, 72, 2, 3, 4, 5, 6, 7, 8, 14, 26, 28, 47, 58
```

This covers the classic Leica looks and KP160 (`58`). The full local
`filterConfig.filterList` has 32 entries with IDs and Chinese names:

```text
1 原图            2 徕卡鲜艳        3 徕卡自然        4 徕卡单色
5 徕卡单色HC      6 徕卡漂棕        7 徕卡单色蓝      8 繁花如梦
9 鲜明           14 黑金           17 冷白           18 原生
20 经典          25 自然           26 赤霞红         28 远山青
32 中性          33 奶油           34 净白           35 鲜亮
36 清新          37 清晰           38 韩胶           39 贝松
40 写意          41 反差           42 质感           43 黑白
47 生动          58 季风           72 负片           73 正片
```

[observed] R600 (`56`) and P100F (`57`) are not in this local table. The
camera resolves them from the cloud catalogue / update path. The module’s
`addFilms` path adds the items locally, so the camera can show them even when
the cloud list omits them.

## How the module adds them

`filters/ModuleMain.java` uses the 6.8 profile:

```java
new Profile("6.8.002230", "xi.h0", "d",
        "i3.b", "b", "l", "e", "d", "g", "h", "i",
        "com.xiaomi.camera.cloudfilter.R$string", null,
        "com.android.camera.fragment.j0", "kt",
        "B9.j", "c",
        "com.android.camera.features.mode.capture.d", "K", "Y", "G",
        "g2.a", "a", "x", "r2.t", "ti.f", "b",
        "com.xiaomi.camera.effect.EffectController", "q")
```

The list/item/workspace names are the verified part. The comment in the
source already says the panel, Looks table, and registry lookup are the best
static candidates. That is accurate: the runtime names in the profile
(`xi.h0`, `i3.b`, `r2.t`) are the deobfuscated runtime forms of
`p832xi.h0`, `p291i3.b`, and `p506o3.d` respectively.

### Item fields

`p291i3.b` has the fields the profile uses:

| Profile field | Real field | Meaning |
| ------------- | ---------- | ------- |
| `b` | `b` | filter ordinal |
| `l` | `f46928l` | cloud filter id |
| `e` | `f46921e` | entry string resource |
| `d` | `f46920d` | image resource |
| `g` | `f46923g` | sample images |
| `h` | `f46924h` | sample labels |
| `i` | `f46925i` | description |

## Verification steps on a device

1. Install the module, enable it for Camera, force-stop Camera.
2. Open Camera → Photo → filter strip.
3. Check the module log:

   ```bash
   adb logcat -s HyperFilmFilters
   ```

   Expect a line naming the 6.8 profile and its hooked list/workspace.

4. Confirm R600, P100F, F-50, KC64 appear in the photo strip, and KP160 in the
   Leica Looks strip.
5. If an entry is missing, the `does not match` log names the missing class or
   field. Capture the full log with `tools/pull_camera_log.sh` and update the
   profile.

No firmware change is needed: every ID above is already in the 6.8 APK.

## Unknowns

* R600 / P100F local LUT data is not in `/odm/etc/camera/videofilter/`. Whether
  the camera’s own renderer can process them offline, or only after the cloud
  fetch, was not established.
* F-50 has a thumbnail but no local `.cube`; same caveat.
* The Looks table (`B9.j`) and filter panel (`com.android.camera.fragment.j0`)
  class names are static candidates. They are the first things to check if the
  module logs a profile mismatch on a 6.8 build.
