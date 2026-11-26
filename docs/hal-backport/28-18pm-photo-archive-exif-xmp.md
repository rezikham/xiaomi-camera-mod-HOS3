# 28 — Xiaomi 18 Pro (`hongkong` / `Madrid`) Photo Archive Forensics: EXIF, MakerNote V2 (`M10R`), and Palette / Parameters (`ReEditDataBeanMsg`)

Analysis of the 10-photo capture archive from Xiaomi 18 Pro (`hongkong_global` / `Madrid`, shot `2026-11-21` at `21°2'9"N 105°47'26"E`) cross-referenced against the extracted Xiaomi 18 Pro firmware (`/run/media/rezikham/970/PoC/extracted_madrid/`).

---

## 1. Archive Inventory & Container Classification

All 10 files in `/run/media/rezikham/970/PoC/18pm_photo_archive/files/` share:
- `Make` (`0x010f`) = `"Xiaomi"`, `Model` (`0x0110`) = `"Xiaomi 18 Pro"`, `XiaomiProduct` (`0x9a00`) = `"Xiaomi 18 Pro"`
- `themeCustomize` (`0x889d`) = `"Madrid"` (present in both `IFD0` and `ExifIFD`)
- `SmartFusion` (`0x889a`) = `0x00`
- `XiaomiCamAccInfo` (`0x88a3`) = `"{}"`
- `XiaomiCvSessionkeyType` (`0x889f`): `0x01` in all 6 HEICs; absent in all 4 Portrait JPEGs
- **`ExifIFD 0x88b0` (`legendmode`)**: **absent** in all 10 files (superseded on Portrait captures by Moment/Legendary V2 in `MakerNote` `0x927c`)
- **`ExifIFD 0x88a4` (`reedit`)**: **absent** in all 10 files (even the 2 Palette/Parameters HEICs; camera firmware relies on `micamedit` XMP + HEIC `edit` item, whereas `MiMediaEditor` and Java `MiPropXmp` writers set `0x88a4 = "1"`)

### Complete 10-File Matrix

| File | Size (B) | Container & W×H | Mode (`0xa661`) / `0x8889` | Lens / Exposure (`0xa405`) | `0x9999` (`XiaomiComment`) highlights | `0x889e` `filterName` / `cvLensName` | Visual / Mean `\|R−B\|` | Special Payloads |
|---|---|---|---|---|---|---|---|---|
| `IMG_20261121_125556.HEIC` | 2,509,644 | HEIC `3072×4552` (`54` tiles `9×6`) | `163` / `"HDR"` | `6.62mm` (`46mm`), `f/1.67`, `4669013/500000000s` (`~1/107`), `ISO 50` | `OpMode:36866`, `filterId:66048`, `zoomMultiple:2`, `Hdr:"auto"` | `"G?c"` / `""` | Stock | `Exif`, `XMDD`, `XMDB`, `mime`, `User` |
| `IMG_20261121_125624.HEIC` | 2,186,369 | HEIC `3072×4552` (`54` tiles `9×6`) | `163` / `"HDR"` | `6.62mm` (`46mm`), `f/1.67`, `1/100s`, `ISO 50` | `OpMode:36866`, `filterId:66048`, `zoomMultiple:2` | `"G?c"` / `""` | Stock | `Exif`, `XMDD`, `XMDB`, `mime`, `User` |
| `IMG_20261121_125656.HEIC` | 2,121,873 | HEIC `3072×4552` (`54` tiles `9×6`) | `163` / `"HDR"` | `6.62mm` (`23mm`), `f/1.67`, `1043137/200000000s` (`~1/192`), `ISO 50` | `OpMode:36866`, `filterId:66048`, `zoomMultiple:1` | `"G?c"` / `""` | Stock | `Exif`, `XMDD`, `XMDB`, `mime`, `User` |
| `IMG_20261121_125714.HEIC` | 2,357,518 | HEIC `3072×4552` (`54` tiles `9×6`) | `163` / `"HDR"` | `17.10mm` (`75mm`), `f/2.40`, `7143/1000000s` (`~1/140`), `ISO 160` | `OpMode:36866`, `filterId:66048`, `zoomMultiple:3.2` | `"G?c"` / `""` | Stock | `Exif`, `XMDD`, `XMDB`, `mime`, `User` |
| **`IMG_20261121_125735.HEIC`** | **5,467,818** | HEIC `3072×4552` (`54` tiles `9×6`) | `163` / `"HDR"` | `17.10mm` (`75mm`), `f/2.40`, `1/100s`, `ISO 100` | **`filterId:655447`**, `OpMode:36866`, `zoomMultiple:3.2` | **`"Gi? m?a"`** (`Gió mùa`) / `""` | **KP160 + params** | **`edit` (1,237,599 B)** + `micamedit` XMP + inner `3072×4096` HEIC |
| **`IMG_20261121_125741.HEIC`** | **6,177,904** | HEIC `3072×4552` (`54` tiles `9×6`) | `163` / `"HDR"` | `6.62mm` (`23mm`), `f/1.67`, `1063703/250000000s` (`~1/235`), `ISO 50` | **`filterId:655447`**, `OpMode:36866`, `zoomMultiple:1` | **`"Gi? m?a"`** (`Gió mùa`) / `""` | **KP160 + params** | **`edit` (1,368,266 B)** + `micamedit` XMP + inner `3072×4096` HEIC |
| **`IMG_20261121_125755.jpg`** | **11,681,266** | JPEG `3072×4552` | `256` / `"portrait"` | `6.62mm` (`35mm`), `f/1.67`, `3787969/500000000s` (`~1/132`), `ISO 50` | `OpMode:36864`, `filterId:66048`, `zoomMultiple:1.5`, `Hdr:"off"` | `"G?c"` / `""` | Color Portrait (`8.35`) | **`MakerNote` `0x0032=3` (`M10R`)**, `depthMapVersion=4`, `GainMap` + `Raw` + `Depth` |
| **`IMG_20261121_125758.jpg`** | **14,350,223** | JPEG `3072×4552` | `256` / `"portrait"` | `6.62mm` (`35mm`), `f/1.67`, `7794183/1000000000s` (`~1/128`), `ISO 50` | `OpMode:36864`, `filterId:66048`, `zoomMultiple:1.5`, `Hdr:"off"` | `"G?c"` / `""` | **True B&W** (`0.26`) | **`MakerNote` `0x0032=2` (`M3`)**, `depthMapVersion=4`, `GainMap` + `Raw` + `Depth` |
| **`IMG_20261121_125831.jpg`** | **13,522,818** | JPEG `3072×4552` | `256` / `"portrait"` | `17.10mm` (`75mm`), `f/2.40`, `1/100s`, `ISO 80` | `OpMode:36864`, `filterId:66048`, `zoomMultiple:3.2`, `Hdr:"off"` | `"G?c"` / **`"M?t m?o"`** (`Mắt mèo`) | Color Swirly (`27.17`) | **`MakerNote` `0x0032=3` (`M10R`)**, `lensMode="3"`, `0x88a2` `XiaomiFaceRoi` |
| **`IMG_20261121_125847.jpg`** | **12,340,023** | JPEG `4096×3528` | `256` / `"portrait"` | `6.62mm` (`35mm`), `f/1.67`, `3790399/500000000s` (`~1/132`), `ISO 50` | `OpMode:36864`, `filterId:66048`, `zoomMultiple:1.5`, `Hdr:"off"` | `"G?c"` / `""` | Color Landscape (`9.43`) | **`MakerNote` `0x0032=3` (`M10R`)**, `depthMapVersion=4`, `GainMap` + `Raw` + `Depth` |

> **Watermark geometry:** Unframed primary frames in `MiCamera:XMPMeta` (`madrid_image` / `images[0]` grid) are `3072×4096` (or `4096×3072` landscape in `125847.jpg`). Adding the bottom white Leica/Madrid frame adds `456px` along the vertical axis → `3072×4552` portrait (`4096 + 456`) and `4096×3528` landscape (`3072 + 456`).

---

## 2. M10R & Essential / Legendary V2 via `ExifIFD 0x927c` (`MakerNote`)

### 2.1 Why `0x88b0` (`legendmode`) & `Legend.*` XMP Are Absent

Older Xiaomi Legendary/Essential pipelines (V1) wrote a single byte `ExifIFD 0x88b0` (`1=M9`, `2=M3`) and `Legend.M9` / `Legend.MONOPAN` entries inside `MiContainer:Directory`. **All 4 Portrait JPEGs in the 18 Pro archive omit `0x88b0` completely**, and instead encode **Moment/Legendary V2** inside standard `ExifIFD 0x927c` (`MakerNote`).

### 2.2 Xiaomi MakerNote Binary Layout & Observed Archive Bytes

Header verified identically in `MiuiCamera.apk` (`p162vf/g.java:51-79`), `MiMediaEditor.apk` (`pc/g.java`), and `com.xiaomi.plugin.jpegrAggr.so :: buildExifOverrideTags` (`0x2d9f0`):
- Magic + Version: `58 69 61 6f 6d 69 00` (`"Xiaomi\0"`) + `01` (version `1`)
- Endianness marker: `4d 4d` (`"MM"`, big-endian `19789` / `0x4d4d`)
- Entry count: `00 04` (`4` IFD entries × 12 bytes = `48 B` + `12 B` header = **`60` bytes total**)
- Big-endian IFD entries (`tag:u16`, `type:u16`, `count:u32`, `value:u32`):

| MakerNote Tag | Name (`pc/g.java:30`, `vf/g.java:19`) | Type | `125755.jpg` (35mm color) | `125758.jpg` (35mm B&W) | `125831.jpg` (75mm `Mắt mèo`) | `125847.jpg` (35mm land.) |
|---|---|---|---|---|---|---|
| `0x0030` (`48`) | `MomentVersion` | `ULONG` (`4`) | `131072` (`00 02 00 00`) | `131072` (`00 02 00 00`) | `131072` (`00 02 00 00`) | `131072` (`00 02 00 00`) |
| `0x0031` (`49`) | `MomentType` | `ULONG` (`4`) | `0` (`00 00 00 00`) | `0` (`00 00 00 00`) | `0` (`00 00 00 00`) | `0` (`00 00 00 00`) |
| `0x0032` (`50`) | `MomentSubMode` | `ULONG` (`4`) | **`3` (`M10R`)** | **`2` (`M3`)** | **`3` (`M10R`)** | **`3` (`M10R`)** |
| `0x0033` (`51`) | `MomentPro` | `ULONG` (`4`) | `0` (`00 00 00 00`) | `0` (`00 00 00 00`) | `0` (`00 00 00 00`) | `0` (`00 00 00 00`) |
| *(opt.)* `0x0034` (`52`) | `MomentBokehDepthVersion` | `ULONG` (`4`) | — | — | — | — |

(Full Xiaomi MakerNote tag table in `pc/g.java:30` and `p162vf/g.java:19` also defines HDR tags `1..5`: `HdrDisplayVersion`, `HdrDisplayEnable`, `HdrDisplayAdrcGain`, `HdrDisplayLuxIndex` (float `11`), `HdrDisplayCaptureType`.)

### 2.3 Firmware Producer (`com.xiaomi.plugin.jpegrAggr.so`)

Disassembly of `jpegrAggrPlugin::buildExifOverrideTags(ExifOverrideContext const&)` (`0x2d9f0..0x31a58`, called at `0x3ae20` inside `processRequest` before `overrideExifTags`):
1. **`0x8898` (`depthMapVersion`)**: reads uint16 at `[ExifOverrideContext+0xa4]` (`bokehDepthVersion`, log at `0x2dc24`: `"[%s] bokehDepthVersion %d, imageStyle %d"`) and writes it as a 2-byte `USHORT`.
2. **`0x889f` (`XiaomiCvSessionkeyType`)**: looks up `MetaTagWrapper(0x1d)`; if `val != 0xff`, writes `(val < 2) ? (val | 2) : (val + 0x66)`.
3. **Constructs `"Xiaomi\0\x01MM"` MakerNote bytes directly in C++**:
   - **Important `0x0030` (`MomentVersion`) encoding quirk**: `buildExifOverrideTags` writes type `4` (`ULONG`) with **hardcoded big-endian value bytes `00 02 00 00`** (`0x2e5b4..0x2e5dc`), which parses as **`131072`** (`0x00020000`, e.g. `2` written into the high 16-bit half as if packed version `2.0`).
   - `0x0031` (`MomentType`): hardcoded `0`.
   - `0x0032` (`MomentSubMode`): writes low byte `w26` (at `0x2f2c8`, `imageStyle` / sub-mode) as `00 00 00 <byte>` (`2` for `125758.jpg`, `3` for `125755` / `125831` / `125847.jpg`).
   - `0x0033` (`MomentPro`): hardcoded `0` at camera capture time (`0x2f4c8..0x2f934`).
   - **Conditional 5th MakerNote tag `0x0040` (`ULONG = 1`)**: when `[x21, #0x20] == 1` (`0x2e108..0x2e110`, second branch ending at `0x2fa60..0x301ac`), `buildExifOverrideTags` writes `0x0030`..`0x0033` plus `0x0040` (`ULONG = 1`) and increments the entry-count byte `w20` by `5` (`add w20, w20, #0x5` at `0x30194`, patched at `[header+0xa..0xb]` via `0x301a8..0x301ac`), whereas the standard 4-tag branch (`[x21, #0x20] != 1`) sets `w20 = 4` (`0x31a48`). Not present in the 4 archive JPEGs.

### 2.4 `MiMediaEditor` Resolution & Re-Edit Behavior (`lv/t0.java`, `cf/x.java`, `rv/q0.java`)

- **Resolver (`MiMediaEditor.apk` `lv/t0.java :: LegendaryVersionResolver`)**:
  - **V2 (`s0.V2`)**: Calls `pc/b.C()` (`XiaomiMakerNote`) → `c(MomentVersion, MomentType, MomentSubMode, MomentPro, depthMapVersion)`.
    - Checks `MomentVersion >= 2` (`lv/t0.java:110`: integer parse of `MomentVersion` BE uint32; `131072 >= 2` succeeds cleanly alongside plain `2`).
    - Maps `MomentSubMode`: **`1 → e.M9` (`Legend.M9`), `2 → e.M3` (`Legend.MONOPAN`), `3 → e.M10R` (`Legend.M10R`)**.
    - Sets `isBokeh = (depthMapVersion >= 4)`.
  - **V1 fallback (`s0.V1`)**: Reads `ExifIFD 0x88b0` (`legendmode`) byte: `1 → M9`, `2 → M3`. **There is NO `M10R` mapping in the V1 `0x88b0` byte check.**
- **Watermark re-edit (`rv/q0.java:431-442`)**: When saving a watermarked/re-edited photo whose source resolved to `s0.V2`, `WatermarkHandler.copyOriExifInfo` sets **`MomentPro = "1"`** in `MakerNote` (`0x0033`) and clears `legendmode` to `"0"`.
- **Export tag preservation (`cf/x.java :: LegendExifTagHandler`)**: On `saveAsCopy` or destructive edits (anything other than overwrite using only `/watermark/provider` or `/portrait_refocus/provider`), clears V1 `legendmode` to `"0"` or V2 `MomentSubMode` (`0x0032`) to `"0"`.

### 2.5 Gallery App Parsing Differences

| APK Inspected | Moment / Essential Support | Observed Evidence |
|---|---|---|
| 18PM `MIUIGallery.apk` (CN Flutter+Rust) | **V1 + V2 (`MakerNote` + `depthMapVersion`)** | No `.dex`; native `libapp.so` / `libapp_gallery.so` / `libheifrust.so` reference `MakerNote`, `depthMapVersion`, `Leica Essential`, `Essential Moment`, `essentialPhoto`, `essentialPro`, `xiaomiEssential`, `xiaomiEssentialPro`, `ReEdit`, `ReEditEssentialPhoto`, `com.miui.mediaeditor.legendary.LegendaryActivity`, `com.miui.mediaeditor.photo.reedit.ReEditServiceActivity`. |
| 18PM `HyperGalleryPluginCn.apk` | Helper plugin strings present | Contains `MakerNote` (`X/f.java`), `1078` flag references, and `Moment` references in `classes.dex`. |
| `nezha_global` `MIUIGalleryGlobal.apk` (`4.3.1.14-global`) | **V1 only (`0x88b0` = `34992`)** | `ExifUtil.isEssentialPhoto` (`lines 861-868`) reads only tag `34992` (`0x88b0`), so V2-only `MakerNote` JPEGs (`0x0030` + `0x0032`) are NOT recognized as Essential photos by that Global APK build without adding `MakerNote` parsing. |

### 2.6 Xiaomi 14 Ultra (`aurora`) Backport Implications

1. **`M10R` requires MakerNote V2**: Because `MiMediaEditor` V1 (`lv/t0.java:72-76`) only maps `legendmode` (`0x88b0`) bytes `1` (`M9`) and `2` (`M3`), tagging an **`M10R`** shot so `MiMediaEditor` / CN `MIUIGallery` recognize it **must** emit `ExifIFD 0x927c` (`MakerNote` `"Xiaomi\0\x01MM"`, `0x0030 = 2` or `0x00020000`, `0x0031 = 0`, `0x0032 = 3`, `0x0033 = 0`) plus `0x8898` (`depthMapVersion = 4`) when Bokeh is active.
2. **Legacy Global Gallery compatibility**: For `M3` / `M9` shots viewed in older `MIUIGalleryGlobal` (`4.3.1.14`) builds lacking `MakerNote` V2 detection, dual-writing `0x88b0` alongside `MakerNote` V2 preserves Essential badge detection in older Gallery builds (note: `cf/x.java` / `lv/t0.java` check `MakerNote` V2 first and only fall back to `0x88b0` if `MomentVersion < 2`).

---

## 3. Palette / Parameters (`ReEditDataBeanMsg` & `FilterBeanMsg`)

### 3.1 Container Formats (HEIC vs JPEG)

Both HAL plugins share identical protobuf construction logic (`FilterScript` parsing + `ReEditDataBeanMsg` serialization):
- **HEIC (`com.xiaomi.plugin.heifedit.so`)**:
  - `HeifEditPlugin::processEditableImage` (`0x24d90`) builds `ReEditDataBeanMsg` and embeds it as a **`SubImage` item with type `"edit"`** (in `125735.HEIC` / `125741.HEIC`, `iid=10056`, `iref` `cdsc → 10054` grid item).
  - `XmpCreator::getXmpStr` (`0x47aec`, `0x487a4..0x48920`) writes into the `mime` XMP item:
    ```xml
    <rdf:Description
      rdf:about=""
      xmlns:micamedit="http://ns.xiaomi.com/photos/1.0/camera/reedit"
      micamedit:version="1"
      micamedit:offset="-1"
      micamedit:len="1237599"/>
    ```
    (`offset="-1"` signals an ISOBMFF `"edit"` box/item rather than a byte-offset JPEG trailer; `len="1368266"` in `125741.HEIC`).
- **JPEG (`com.xiaomi.plugin.jpegrAggr.so`)**:
  - `jpegrAggrPlugin::processFullLinkEditableJpeg` (`0x44f9c`, called from `processRequest` at `0x3a880`) builds the same `ReEditDataBeanMsg` bytes with the unedited clean JPEG inside `images[0]`, and `fullLinkEditableSubImg` (`vfunc 0x6d580`) writes `micamedit:version`, `micamedit:offset` (EOF-relative byte offset per `Xa/e.java` & `Ta/a.java:197-205`), and `micamedit:len` into the Primary `APP1` XMP.

### 3.2 MIALGO Pipeline Proven by Decompressed `XMDD`

Decompressing the `zlib` (`0x78 0xda`) `XMDD` item (`40,960 B` uncompressed text) directly contrasts stock vs Palette/Parameters HEICs:
- **Stock HEIC (`125556.HEIC`)**:
  `LeicaFilterInstance0 → GPURotateInstance → WatermarkInstance0 → virtualInstanceForXMTHSplit0 → (HeicInstance0 + GPUResizeInstance→swjpeg1080pInstance) → yuv2HeicInstance → HeifEditInstance:in:0`
- **Palette/Parameters HEIC (`125735.HEIC`, `125741.HEIC`)**:
  `LeicaFilterInstance0 → GPURotateInstance → virtualInstanceEditable` branches into two paths:
  - `virtualInstanceEditable:out:0 → FilterInstance0 → WatermarkInstance0 → ... → yuv2HeicInstance:out:0 → HeifEditInstance:in:0` → **final edited & watermarked `3072×4552` `54`-tile HEIC**
  - **`virtualInstanceEditable:out:1 → HeicEditableInstance0 → yuv2HeicEditableInstance → HeifEditInstance:in:12`** → **clean `3072×4096` `48`-tile HEIC embedded in `ReEditDataBeanMsg.presetData.images[0]`**

Notice what differs inside the embedded `images[0]` HEIC compared to the outer HEIC:
- Outer `125735.HEIC`: `grid = 000008050c0011c8` (`9×6 = 54` tiles, `3072×4552`), `Exif = 893,351 B`, `Model = "Xiaomi 18 Pro"`, includes `0x889d` (`Madrid`), `0x889e` (`filterName="Gi? m?a"`), `0x9999` (`filterId:655447`), `0x9a00`, `0xa661="163"`.
- Inner `images[0]` of `125735.HEIC` (`1,237,186 B`): valid ISOBMFF container (`ftyp` 24 B `@0` + `64`-bit `mdat` `largesize=1,233,951` `@24` + `meta` `3,211 B` `@1,233,975`, **0 trailing bytes**), `grid = 000007050c001000` (`8×6 = 48` tiles, `3072×4096`, no watermark frame), `Exif = 110,811 B` (`Model = "2609BPN61G"`, `DateTimeOriginal = "2026:11:21 12:58:12.566660"`, no `0x889d`/`0x889e`/`0x9999`/`0x9a00`/`0xa661`, no `XMDD`/`XMDB`/`User`/`mime` items). Inner `125741.HEIC` `images[0]` is `1,367,853 B` (`ftyp` 24 B + `mdat` `largesize=1,364,618` + `meta` `3,211 B`, `48` tiles `3072×4096`, `Model="2609BPN61G"`, `Exif = 443,432 B`, `12:58:10.422107`).

### 3.3 Exact Protobuf Schema (`jadx_mme`) & Archive Wire Decode

From `MiMediaEditor.apk` (`com/miui/camerainfra/reedit/data/BizOpBeanMsgOuterClass.java`, `BizFilterBeanMsg.java`, `ul/c.java`, `ul/j.java`):

```protobuf
syntax = "proto3";
package com.miui.camerainfra.reedit.data;

message ReEditDataBeanMsg {
  repeated bytes images = 1;         // unedited base HEIC/JPEG bytes
  repeated BizOpBeanMsg opList = 2;  // applied operations
  ReEditDataBeanMsg presetData = 3;  // camera preset snapshot wrapper (present in archive)
}

message BizOpBeanMsg {
  string name = 1;     // "filter", "crop", "portrait_refocus", "adjust", "watermark", "mark", "live", "aigc_mark"
  string version = 2;  // "1.0"
  oneof subBeans {
    FilterBeanMsg filter = 3;
    // 4: CropBeanMsg, 5: PortraitRefocusBeanMsg, 6: AdjustBeanMsg,
    // 7: WatermarkProtoBean, 8: MarkListBeanMsg, 9: LiveBeanMsg, 10: AIGCMarkBeanMsg
  }
  string from = 11;    // "micamera"
}

message FilterBeanMsg {
  int32 filterId = 1;
  int32 progress = 2;
  string renderType = 3;
  string colorParam = 4;
  string filterFunc = 5;
  optional bytes lutData = 6;
  optional string token = 7;
  string miCamStyle = 8;
}
```

Verified wire-level decode of **`125735.HEIC` (`1,237,599 B` `edit` item)** and **`125741.HEIC` (`1,368,266 B` `edit` item)**:
- Outer `ReEditDataBeanMsg`:
  - `field 3` (`presetData`, wire tag `0x1a`): `1,237,595 B` (`125735`) / `1,368,262 B` (`125741`)
    - `presetData.images[0]` (`field 1`, `0x0a`): `1,237,186 B` (`125735`) / `1,367,853 B` (`125741`) — clean unedited `3072×4096` HEIC
    - `presetData.opList[0]` (`field 2`, `0x12`): **`402 B`** `BizOpBeanMsg`:
      - `name` (`f1`): `"filter"`
      - `version` (`f2`): `"1.0"`
      - `filter` (`f3`, `376 B` `FilterBeanMsg`):
        - `filterId` (`f1`): `58`
        - `progress` (`f2`): `100`
        - `renderType` (`f3`): `"513"`
        - `colorParam` (`f4`, `350 B`):
          ```text
          @CubeLutEffect;cube_strength=1.0;lut_type=0;@CvEffect;SmoothStartValue=1.4;Falloff=1.0;SmoothEndValue=0.2;DarkStrength=1.0;NoiseStrength=0.4;@@StyleAdjustEffect;Algos=12;SharpenIntensity=0.0;ContrastIntensity=-0.44;SaturationIntensity=-0.4;Temperature=0.0;Tint=0.0;Grain=0.0;VignetteIntensity=0.0@BlackMistEffect;LumThresh=0.5;Raduis=4.0;MixRatio=0.3
          ```
          *(Note the literal Xiaomi spelling `Raduis=4.0` inside `@BlackMistEffect`, matching HAL filterScript keys.)*
        - `filterFunc` (`f5`): `"APPLY_FILTER"`
      - `from` (`f11`): `"micamera"`
- Additional optional `FilterBeanMsg` fields populated in `HeifEditPlugin::processEditableImage` (`0x24d90`) and `jpegrAggrPlugin::processFullLinkEditableJpeg` (`0x44f9c`) when metadata is supplied:
  - `miCamStyle` (`f8`): from `miCamStyle` key
  - `token` (`f7`): from `shareCode` key
  - `lutData` (`f6`): when `isCustomLut=true` and `lutNames` entry passes `^[a-zA-Z0-9_-]+$`, read directly from `/data/vendor/camera/<lutName>.png`

### 3.4 `0x889e` (`XiaomiAuxiliaryInfo`) Non-ASCII Truncation Bug

In all 10 archive files, `ExifIFD 0x889e` (`XiaomiAuxiliaryInfo`) replaces every non-ASCII UTF-8 character with ASCII `0x3f` (`'?'`):
- `"Gốc"` → `"G?c"`
- `"Gió mùa"` → `"Gi? m?a"` (`125735.HEIC`, `125741.HEIC`)
- `"Mắt mèo"` → `"M?t m?o"` (`125831.jpg`)

Consumers should never rely on `0x889e.filterName` / `cvLensName` for exact display identity; use numeric `filterId` (`0x9999` / `FilterBeanMsg.filterId`) and `depthmap lensMode` instead.

### 3.5 Xiaomi 14 Ultra Palette/Parameters Backport Notes

To support full camera-to-editor non-destructive Palette/Parameters re-editing on 14U:
1. Keep the pre-filter frame branch in the MIALGO graph (`virtualInstanceEditable` after rotation, before `FilterInstance0` and `WatermarkInstance0`) and encode the clean frame into `images[0]`.
2. Emit `ReEditDataBeanMsg` with **`presetData` (`field 3`)** wrapping `images[0]` + `opList[0]` (`name="filter"`, `version="1.0"`, `from="micamera"`, `FilterBeanMsg` with `filterId`, `progress`, `renderType`, `colorParam`, `filterFunc="APPLY_FILTER"`, plus `lutData` / `token` / `miCamStyle` if using custom Palette LUTs).
3. Emit matching container linkage: HEIC `"edit"` item + `micamedit:offset="-1" micamedit:len="<size>"`, or JPEG post-EOI trailer + EOF-relative `micamedit:offset` and `micamedit:len`.

---

## 4. Additional 18 Pro Metadata Structures Observed

### 4.1 Portrait Multi-Image Layout, `depthmap` v4, and `MiCamBokeh`

Every Portrait JPEG in the archive (`125755`, `125758`, `125831`, `125847.jpg`) follows this exact contiguous 4-segment physical layout (`0` gap bytes, verified exact to EOF):
```text
[0 .. gm_start]              Primary JPEG (ends 0xff 0xd9; MPF Entry 1 points here)
[gm_start .. raw_start]      GainMap JPEG (length = Container Item:Length & MPF img1_sz)
[raw_start .. depth_start]   Raw/SharpBokehOriginal JPEG (length = rawoffset - depthoffset == rawlength)
[depth_start .. EOF]         Depth binary blob ("MCBOKEHSOT\0\0\0\0\0\0..." ; length = depthoffset == depthlength)
```
Key `MiCamera:XMPMeta` attributes:
- `<madrid_image type="701" offset="0" length="0" paddingx="0" paddingy="0" width="..." height="..." location_enabled="0" time_enabled="0" dark="0" />`
- `<depthmap version="4" focuspoint="..." blurlevel="9|12" depthsize="4096,3072" rawlength="..." depthlength="..." rawoffset="..." depthoffset="..." depthOrientation="90|0" productName="hongkong_global" lensMode="7|3" aperturesList="[...]" />`
  - Notice `125831.jpg` (`Mắt mèo` Swirly bokeh) uses **`lensMode="3"`** and `aperturesList="[1.400000,...,16.000000]"` (`blurlevel="12"`), whereas the 35mm `M3`/`M10R` captures use `lensMode="7"`, `blurlevel="9"`, and `aperturesList="[2.000000,...,16.000000]"`.
- Primary XMP also includes `MiContainer:Item` (`name="Primary"`, `Mime="info"`, `SHA_Start="SOS"`, `SHA_length`, `SHA`), `Container:Directory` + `hdrgm:Version="1.0"`, and `MiCamBokeh` (`capsInfo`, `capsStream`, `configStream`, `configRequest`, `configResult`).

### 4.2 Sideband APP Segments (JPEG) & HEIC Items

- **`User` item (`455..456 B` in HEICs) / `APP5 CLOUD_WATER` (`471..473 B` payload in JPEGs)**: `CLOUD_WATER\0\x01\0\0\x01` + a ZIP archive containing `userConfig.json` (`watermark_id`, `frame_background`, `time_data`, `picture_size`, `location_data_latlng`, switch booleans).
- **`APP4 XIAOMI_CUSTOMIZE` (`608..695 B` payload in JPEGs)**: `XIAOMI_CUSTOMIZE\0\x01\x01` + JSON mirroring key EXIF tags (`"version":"32"`, `"10f"`, `"110"`, `"8889"`, `"8895"`, `"8898"`, `"889a"`, `"889d"`, `"889e"`, `"88a3"`, `"9999"`, `"9a00"`, `"a661"`), where `9aaa` (`algoComment`) is serialized with Java's default byte-array `toString()` (`"[B@f7ec5fe"`, `"[B@46df9c1"`, etc.).
- **`APP3 ALGO_COMMON`** (`3,470..3,598 B` payload), **`APP5 XMI Camera Data`** (chunked `zlib` QTI `MiDebug_Version`), **`APP2 XMTH`** / **`APP2 XMDD`** (chunked `zlib` MIALGO graph + metadata), **`APP3 XMDBG_3A`** / **`XMDBG_HAL`** / **`OISINFO`** (`zlib`), **`APP2 MPF`**, and HEIC **`XMDB`** (`104–181 KB` opaque binary debug item).
