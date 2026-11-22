# Re-editable camera metadata (6.8)

Camera 6.8 can store a picture-style/palette adjustment in the file so HyperOS
Gallery can reopen and change it. The format is now confirmed from real
Xiaomi 18 Pro samples.

## HEIF layout (confirmed)

A KP160-with-parameters HEIC has:

```text
XMP micamedit:version = 1
XMP micamedit:offset  = -1        # HEIF sentinel; payload is the "edit" item
XMP micamedit:len     = 1237599
EXIF  reedit (34980)  = 1
HEIF  metadata item   = edit/, 1237599 bytes
```

The `edit/` item is an outer protobuf message:

```text
field 3, length 1,237,595
  field 1, length 1,237,186  -> original HEIC image (3072x4096)
  field 2, length 402        -> ReEditDataBeanMsg/FilterBeanMsg
```

At the end of the original HEIC is a 14-byte box:

```text
00 00 00 0e 63 64 73 63 27 41 00 01 27 40
             "c d s c"  'A \0 \x01 '@
```

`cdsc` is the edit-container marker used by the media editor.

## Filter protobuf (confirmed)

The 402-byte filter message is:

```text
0a 06 "filter"                 field 1  type       = "filter"
12 03 "1.0"                    field 2  version    = "1.0"
1a f8 02 <nested, 376 bytes>   field 3  filter data
   08 3a                      nested field 1 = 58
   10 64                      nested field 2 = 100
   1a 03 "513"                nested field 3 = "513"
   22 de 02 <script, 350 B>   nested field 4 = filter script
   2a 0c "APPLY_FILTER"       nested field 5 = "APPLY_FILTER"
   5a 08 "micamera"           nested field 11 = "micamera"
```

The KP160 script payload is:

```text
@CubeLutEffect;cube_strength=1.0;lut_type=0;
@CvEffect;SmoothStartValue=1.4;Falloff=1.0;SmoothEndValue=0.2;
DarkStrength=1.0;NoiseStrength=0.4;
@@StyleAdjustEffect;Algos=12;SharpenIntensity=0.0;ContrastIntensity=-0.44;
SaturationIntensity=-0.4;Temperature=0.0;Tint=0.0;Grain=0.0;
VignetteIntensity=0.0
@BlackMistEffect;LumThresh=0.5;Raduis=4.0;MixRatio=0.3
```

`StyleAdjustEffect` is the palette/parameters menu. `Algos` is a bitmask:
sharpen, contrast, saturation, temperature, tint, grain and vignette are
enabled per bit. The shader lives in `libMiPhotoFilter.so`.

`FilterId` in the sample is `655447` = `0xA0057`, low ordinal `87` = KP160.
`filterName` in Exif 0x889e is the localized film name (Vietnamese
`Gi? m?a` = monsoon in this sample).

## Producer

On Madrid the JPEG/HEIF writer is the camera HAL plugin:

```text
/odm/lib64/camera/plugins/com.xiaomi.plugin.jpegrAggr.so
/odm/lib64/camera/plugins/com.xiaomi.plugin.heifedit.so
```

`jpegrAggrPlugin::processFullLinkEditableJpeg` reads:

```text
renderType
renderTagStr
filterScript
isCustomLut
lutNames
miCamStyle
shareCode
```

and appends the protobuf, writes `micamedit:offset`/`len`, embeds the XMP APP1,
and sets `reedit=1`. Logs seen in the binary confirm `protoBufSize`,
`set lutData from <path>`, `set miCamStyle`, and `set shareCode to token`.

Aurora has the same plugin paths, but its `jpegrAggr.so` is much older
(212 KB vs 587 KB) and has none of the `FullLinkEditableJpeg` / `micamedit`
strings. The Madrid plugin also needs 10 libraries that are absent on aurora:

```text
libmetatagwrapper.so
libcom.xiaomi.cam.miexifmgr.so
libexif.so
libprotobuf-cpp-full-6.33.5.so
liblegendmsg.so
libcameraopt_stub.so
libcrypto.so
libopbeanmsg.so
libcom.xiaomi.camera.configmanager.so
libcom.xiaomi.cam.oisexif.so
```

## Sample notes

- `IMG_20261121_125735.HEIC` and `125741.HEIC`: KP160, re-edit payload present.
- `125556/125624/125656/125714.HEIC`: stock, no re-edit payload.
- `125755/125758/125831/125847.jpg`: stock portrait JPEGs; `125758` is
  monochrome (likely the M3 sample), but all four carry `filterId=66048`
  (stock) and no `micamedit`, so the M3/M10R files do not include re-edit data.

## Implementation options

1. **HAL plugin port**: mount Madrid's `jpegrAggr.so`/`heifedit.so` and their
   dependency libraries over aurora via the KSU module. Highest fidelity, but
   the plugin ABI and library versions must be tested.
2. **LSPosed writer**: after capture, append a synthesized `ReEditDataBeanMsg`
   (filter script + LUT bytes) to the JPEG, set `reedit=1`, and add the
   `micamedit` XMP offset/len. Needs a JPEG re-edit sample to confirm the JPEG
   payload layout; the HEIF sample uses a full original image plus the
   protobuf.
3. **Render-only**: add `@StyleAdjustEffect;...` to the MIVI `filterScript`
   without re-edit metadata. This gives the visual palette/parameters result
   but Gallery cannot reopen the adjustments.
