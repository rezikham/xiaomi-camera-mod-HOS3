# Leica Essential, Legend mode, and M10R on `madrid`

This is the deep dive for the Leica Essential styles: M3, M9, and the CMOS /
M10R style. It covers the app-to-HAL parameters, the metadata tags, the
`mileicafilter` parameters, the `legendCT`/`legendST` plugins, the capture
pipeline, and what Camera 6.8 does natively.

Short conclusion: the 18 Pro Max HAL and Camera 6.8 both implement M10R
natively. There is no missing HAL processing to enable with a module.

## The data path

```text
Camera app (6.8)
  style picker: ComponentConfigLegendary (p613r2.B)
  style value:  "M3" / "M9" / "M10R"
        |
        |  CaptureRequest / session parameters
        |  com.xiaomi.sessionparams.legendMode : int
        |  com.xiaomi.sessionparams.stylizationType : int
        v
Camera HAL (CamX / CHI)
  reads legendMode, logs oemIsLegendMode
        |
        v
MiAlgo engine (libmialgoengine.so)
  legendsnapshot.json / legendmonopansnapshot.json
        |
        +--> com.xiaomi.plugin.mileicafilter   (LeicaFilterInstance0)
        |       loads /odm/etc/camera/leica_filter_param*.bin
        |
        +--> com.xiaomi.plugin.legendST        (StyleTransInstance0)
        |       libmialgo_styletrans.so
        |
        +--> com.xiaomi.plugin.legendCT        (colour transform)
        |
        +--> gainmap, LDC, MFNR, JPEG, ...
```

## Keys and tags

### Android / Xiaomi keys

[observed] Strings and classes in the Camera 6.8 APK and HAL:

| Key | Kind | Where |
| --- | ---- | ----- |
| `xiaomi.capabilities.stylizationTypeMask` | `CameraCharacteristics.Key<int[]>` | Camera 6.8 `p299ia/s0.java`; HAL static info |
| `com.xiaomi.legendMode.capsInfo` | `CameraCharacteristics.Key<byte[]>` | Camera 6.8 `p299ia/U.java` case 1; HAL static info |
| `com.xiaomi.sessionparams.legendMode` | `CaptureRequest.Key<Integer>` | Camera 6.8 string; `camera.qcom.core.so` |
| `com.xiaomi.sessionparams.stylizationType` | session parameter | Camera 6.8 strings |
| `com.xiaomi.sessionparams.filterId` | session parameter | Camera 6.8 strings |
| `com.xiaomi.sessionparams.bokehRole` | session parameter | Camera 6.8 strings |
| `com.xiaomi.sessionparams.BokehMode` | session parameter | Camera 6.8 strings |
| `xiaomi.camera.legendMode` | HAL string | `camera.qcom.core.so` at file offset `0x8b5ea` |
| `com.xiaomi.legendMode` | HAL string | `camera.qcom.core.so` at `0x146b74` |
| `legendMode` | HAL string | `camera.qcom.core.so` at `0xaf237` |
| `Legend` / `LegendMonopan` / `LegendM10R` | pipeline/style names | `camera.qcom.core.so` at `0x20647a`, `0x1cda08`, `0x24094d` |
| `OemRetrieveLegendMode` | symbol/string | `camera.qcom.core.so` at `0x284a8a` |

The core HAL has this log format at `0x10aee4`:

```text
[ VERB]%s %s:3352 %s() legendMode: %d, oemIsLegendMode: %d
```

Its only reference is at `0x11a5818`. The code immediately before it does:

```asm
11a57cc: ldr  w22, [x8]          ; legendMode
11a57d0: sub  w8, w22, #1
11a57d4: cmp  w8, #2
11a57d8: cset w8, lo             ; oemIsLegendMode = (legendMode - 1) < 2
11a57dc: str  w8, [x20]
```

[inferred] Therefore `legendMode` values `1` and `2` are the two “OEM legend”
modes (M9 and M3), while `3` is a different/extra mode. This is consistent
with `3` being the newer M10R mode, but the app-side writer of the value was
not recovered (see [Unknowns](#unknowns)).

### MiMetadata tags

[observed] `odm/lib64/libmetadataHelper.so` exports two relevant helpers:

```text
metahelper::metadataHelperBase::GetLegendMode(shared_ptr<MiMetadata>, unsigned char*)        @ 0x40bec
metahelper::metadataHelperBase::GetStylizationType(shared_ptr<MiMetadata>, unsigned char*)   @ 0x1ebbc
```

Disassembly proves the tag indices:

```asm
; GetLegendMode
40c38: mov w3, #0x1fc          ; 508
40c44: bl  ...GetMetaData...

; GetStylizationType
1ec08: mov w3, #0x1d           ; 29
1ec14: bl  ...GetMetaData...
```

| MiMetadata index | Name | Type | Used by |
| ---------------- | ---- | ---- | ------- |
| `29` (`0x1d`) | stylization type | byte | `MiLeicaFilterPlugin::initialize`, `isEnabled` |
| `508` (`0x1fc`) | legend mode | byte | `MiLeicaFilterPlugin::initialize`, `isEnabled`; core HAL |

In `MiLeicaFilterPlugin::initialize` (`0xc900`):

```asm
ca50: ldr  x21, [x20, #0x48]       ; MiMetadata from CreateInfo
ca5c: mov  w8, #0x1d               ; tag 29
ca78: bl   MiMetadata::find
ca8c: cmp  w8, #1
ca94: strb w8, [x19, #0x25]        ; stylizationType == 1 flag

caa0: mov  w8, #0x1fc              ; tag 508
cad8: strb w8, [x19, #0x26]        ; legendMode byte
```

`isEnabled` (`0xd6a0`) reads the same tags at `0xd884`–`0xd8f8` and stores
the legend-mode byte into the same object field (`+0x26`).

The plugin’s debug strings use the values directly:

```text
[LeicaFilter][%s] use pre param for M9
[LeicaFilter][%s] use pre param for M10R
[LeicaFilter][%s] Disable shading for M9/M10R bokeh snapshot!
[LeicaFilter][%s] M3 Bokeh snapshot bypass!
```

## `mileicafilter` parameter files

[observed] The plugin’s static initializer (`0x1f4a0`–`0x1f630`) builds a
`std::map<ParamMode, std::string>` with these entries. The string addresses
are file/virtual offsets because this ELF has `vaddr == file offset`.

| `ParamMode` | File | String offset |
| ----------- | ---- | ------------- |
| `0` | (none) | — |
| `1` | `/odm/etc/camera/leica_filter_param.bin` | `0x5b05` |
| `2` | `/odm/etc/camera/leica_filter_param_m9_preview.bin` | `0x62e6` |
| `3` | `/odm/etc/camera/leica_filter_param_m9_snapshot.bin` | `0x5156` |
| `4` | `/odm/etc/camera/leica_filter_param_m3.bin` | `0x55ac` |
| `5` | `/odm/etc/camera/leica_filter_param_m10r_preview.bin` | `0x6b28` |
| `6` | `/odm/etc/camera/leica_filter_param_m10r_snapshot.bin` | `0x6573` |

The keys are written as immediates `1`, `2`, `3`, `4`, `5`, and the sixth
entry follows the same node layout. The order above is also the order of the
string references in the initializer. The two M10R files exist in
`odm/etc/camera/` with the sizes listed in
[01-hal-stack.md](01-hal-stack.md#configuration).

### How `initResource` selects a mode

[observed] `MiLeicaFilterPlugin::initResource` (`0xcca0`) reads:

* `obj+0x26` — legend mode byte (tag 508)
* `obj+0xe0` — a `CreateInfo` field copied from `CreateInfo+0x44` in
  `initialize` (values `0`, `1`, `4` are handled specially elsewhere)

The selection block is at `0xce6c`–`0xced0`:

```asm
ce6c: ldrb w8, [x19, #0x26]        ; legendMode
ce70: cmp  w8, #1
ce74: b.gt ce94
ce78: cbz  w8, ceb4                ; 0 -> ParamMode 0
ce7c: cmp  w8, #1
ce80: b.ne ceb4
ce84: ldr  w8, [x19, #0xe0]        ; proc
ce88: cbz  w8, cec4                ; proc == 0 -> 2
ce8c: mov  w8, #1                  ; else       -> 1
...
ce94: cmp  w8, #2
ce98: b.eq cebc                    ; 2 -> 3
ce9c: cmp  w8, #3
cea0: b.ne ceb4                    ; other -> 0
cea4: ldr  w8, [x19, #0xe0]
cea8: cbz  w8, cecc                ; proc == 0 -> 5
ceac: mov  w8, #4                  ; else       -> 4
```

Resulting table:

| `legendMode` (tag 508) | `proc != 0` | `proc == 0` |
| ---------------------- | ----------- | ----------- |
| `0` | `0` (none) | `0` (none) |
| `1` | `1` generic | `2` M9 preview |
| `2` | `3` M9 snapshot | `3` M9 snapshot |
| `3` | `4` M3 | `5` M10R preview |
| other | `0` (none) | `0` (none) |

[inferred] `proc == 0` appears to be the preview path (the branch that maps
`legendMode 1` to `m9_preview` and `legendMode 3` to `m10r_preview`). The
non-zero branch is used for other process modes (`1` and `4` are explicitly
handled earlier in the function, and `4` creates a video-format filter). The
fact that `legendMode 3` + non-zero selects `m3.bin` is surprising and is
recorded as observed, not explained. It may mean that the M10R snapshot path
is handled outside `initResource` (for example in `processRequest` or
`candyProcess`), or that the numeric style mapping is different from the
simple 1=M9, 2=M3, 3=M10R assumption.

This is the single most important thing **not** to over-claim: the numeric
mapping is observed in the core HAL (`(legendMode-1) < 2`), but the full
style-to-parameter table is not proven. The module does not need it on
`madrid`, because the camera app and HAL already agree.

## `legendCT` and `legendST`

### `com.xiaomi.plugin.legendCT.so`

[observed] Size 85,608 bytes. Exported entry points:

```text
mialgo2::LegendCTPlugin::initialize(CreateInfo*, MiaNodeInterface)
mialgo2::LegendCTPlugin::isEnabled(MiaParams)
mialgo2::LegendCTPlugin::processRequest(ProcessRequestInfo*)
mialgo2::LegendCTPlugin::processRequest(ProcessRequestInfoV2*)
mialgo2::LegendCTPlugin::flushRequest(FlushRequestInfo*)
mialgo2::LegendCTPlugin::destroy()
```

Strings:

```text
com.xiaomi.plugin.legendCT
Legend-COLORTRANS
LegendError: CT perf-bypass
LEGEND_STYLETRANS
LegendST input %dx%d,fmt=0x%x not equal to output %dx%d,fmt=0x%x
```

The source path in the binary is
`vendor/xiaomi/proprietary/mivifwk/external/odm/plugins/general/legend/LegendColorTransPlugin.cpp`.
It links against `MialgoAi_DIPS_*` (the MiAlgo AI processing service).

### `com.xiaomi.plugin.legendST.so`

[observed] Strings and symbols:

```text
com.xiaomi.plugin.legendST
Legend-ST
LegendError: ST perf-bypass
legendMode = %u
LEGEND_STYLETRANS
libmialgo_styletrans.so
/odm/etc/camera/styletrans/
/odm/etc/camera/styletrans/compressed/
persist.vendor.camera.styletransfer.debug
```

It exports `mialgo2::LegendSTPlugin::initialize/isEnabled/processRequest/
flushRequest/destroy` and loads `libmialgo_styletrans.so`. This is the neural
style-transfer stage.

#### Style-transfer models (M9 vs M10R)

[observed] `/odm/etc/camera/styletrans/compressed/` contains exactly four
MiNN models. This is the model data behind `legendST`:

| File | Size (bytes) | SHA-256 |
| ---- | ------------ | ------- |
| `styletrans_high_m10r.minn` | 39,972,948 | `db8a9c16160a019d8b426a42c418dbe99f92af338ce7cb91635ac8a00a31f9c1` |
| `styletrans_high_m9.minn` | 39,731,284 | `99c38289ce922ae7f12dbe921d40477ad46100cc71924e7becc74e4f2185acc3` |
| `styletrans_low_m10r.minn` | 39,981,140 | `9c2212599a258c17bc8b22a94923166099aa16225f733f170c30437aee9e4f65` |
| `styletrans_low_m9.minn` | 39,723,092 | `0870166020f14940afad6735e858c1fa713a9f4d0954d73b35d5837ce5b753e7` |

All four start with the bytes `4e 4e 69 4d` (`NNiM`). [inferred] This is the
MiNN model-container magic used by Xiaomi’s on-device NN runtime; the exact
container layout is not documented here. The extracted firmware contains only
`compressed/`; the uncompressed `/odm/etc/camera/styletrans/` directory named by
the plugin is not present.

[observed] `libmialgo_styletrans.so` (304,712 bytes) contains the four basenames
and the path fragment that the plugin prepends:

| Offset | String |
| ------ | ------ |
| `0x8525` | `/styletrans_high_m10r.minn` |
| `0x87d7` | `/styletrans_high_m9.minn` |
| `0x890c` | `/styletrans_low_m10r.minn` |
| `0x8ae8` | `/styletrans_low_m9.minn` |
| `0x98a0` | `M10r` |
| `0x9ecf` | `M9` (inside the `clEnqueueMarker`/`M9` string group) |
| `0x9bf9` | `/odm/etc/camera/mialgo_styletrans_cl.bin` |

The model path is assembled in the function at `0x2d780`–`0x2e1b8`
(`styletrans_utils.cpp`, source path at `0x8a8c`). The branches show:

* `[object + 0x160] == 0` selects a `*_m9.minn` name; a non-zero value selects
  `*_m10r.minn`. The four copying blocks are at `0x2d9c8` (high M9), `0x2d938`
  (low M10R), `0x2da0c` (high M10R), and `0x2e14c` (low M9).
* The high/low choice is made before the M9/M10R choice. When
  `[object + 0x188 + 0x10] >= 0x105` and `[object + 0x188 + 0x14] <= 0x1253`,
  the low model is used; otherwise the high model is used (`0x2d910`–`0x2d930`
  and `0x2d924`–`0x2d92c`).

[observed] The runtime log path agrees with the model naming. In
`MialgoAi_DIPS_Run` the format string at `0x844d` prints `mode=%s`; the pointer
is selected at `0x19610`–`0x1964c` from `[info + 0x48]`:

```asm
19634: cmp  w8, #0
19638: mov  w8, #4
1963c: csel x2, x9, x8, eq
19648: lsl  w9, w2, #1
1964c: csel x1, x11, x10, eq     ; x11="M9" (0x9ecf), x10="M10r" (0x98a0)
```

So a zero field selects `"M9"` and a non-zero field selects `"M10r"` in the
same way as the model filename. [inferred] `+0x160` and `+0x48` carry the same
style selector at different struct levels. [unknown] The exact meaning of
`+0x188+0x10`/`+0x188+0x14` (the values compared with `261` and `4691`) and
whether “high/low” means snapshot/preview or a resolution tier were not
established by static analysis.

[observed] The 17 Ultra (`nezha`) ships a different, older style-transfer set:
`styletrans_colorfix.minn` (6,953,155 bytes), `styletrans_high.minn`
(6,700,350), and `styletrans_low.minn` (6,698,160), with no `m9`/`m10r` names.
Its `libmialgo_styletrans.so` is 13,668,744 bytes, versus 304,712 on `madrid`.
[inferred] `madrid` moved the model data into external `.minn` files and added
dedicated M9 and M10R models; the roughly six-times-larger models are
consistent with a new AI style model rather than a parameter tweak.

### Where they run

[observed] `legendsnapshot.json` includes, in order:

```text
com.xiaomi.plugin.mialgoanchor       AnchorInstance0
com.xiaomi.plugin.anchor             AnchorInstanceForMFNR
com.xiaomi.plugin.virtual            virtualInstanceAnchorToGainmap
com.xiaomi.plugin.offcammfnr         MfnrInstance0
com.xiaomi.plugin.offcamb2y          BayerRaw2YuvInstanceForSigFrame
com.xiaomi.plugin.offcamb2y          BayerRaw2YuvInstanceGainmapAnchor
com.xiaomi.plugin.virtual            virtualInstance0
com.xiaomi.plugin.offcamformatconvertor  FormatConvertorInstance0
com.xiaomi.plugin.offcamformatconvertor  FormatConvertorInstancePreRawEmbeder
com.xiaomi.plugin.mialgoallinone     AllinOneInstance0
com.xiaomi.plugin.mialgoallinone     AllinOneRawEmbederInstance0
com.xiaomi.plugin.offcamb2y          BayerRaw2YuvInstanceForRGB
com.xiaomi.plugin.virtual            virtualInstanceAIORGBSplit
com.xiaomi.plugin.offcamb2y          BayerRaw2YuvInstanceGainmapForRGB
com.xiaomi.plugin.gainmap            GainMapInstance0
com.xiaomi.plugin.mileicafilter      LeicaFilterInstance0
com.xiaomi.plugin.depurple           DepurpleInstance0
com.xiaomi.plugin.ldc                LDCInstance0
com.xiaomi.plugin.ldc                WideLDCInstance0
com.xiaomi.plugin.legendST          StyleTransInstance0
com.xiaomi.plugin.gainmapPostProc    GainMapPostProcInstance
com.xiaomi.plugin.watermark         WatermarkInstance0
com.xiaomi.plugin.swjpegencode      swjpegInstance0
com.xiaomi.plugin.offcamjpeg        JpegInstanceFinal
com.xiaomi.plugin.offcamjpeg        JpegInstanceFinal1
com.xiaomi.plugin.jpegrAggr         jpegrAggrInstance
com.xiaomi.plugin.virtual            virtualInstanceGainmapSplitForWatermark
com.xiaomi.plugin.gainmapPostProc   GainMapPostProcInstanceSharp
com.xiaomi.plugin.jpegrAggr         jpegrAggrInstanceSharp
```

`FeaturePipelineList` points at `preanchormfnrsatsnapshot.json` and
`preanchorallinonesatsnapshot.json`; the pipeline can therefore splice in the
pre-anchor MFNR/SAT or all-in-one/SAT stages.

`legendmonopansnapshot.json` is the Monopan variant. It replaces
`virtualInstance0`/`AllinOneRawEmbederInstance0` with a
`virtualInstanceForM3` and adds `com.xiaomi.plugin.filmnoise`
(`FilmnoiseInstance0`) before the gain-map post-process. It still uses
`mileicafilter` + `legendST`.

Node masks observed in `legendsnapshot.json` include `49` (`INPLACE_MODE |
SIGFRAME_MODE | SIGRELEASEINPUT_MODE`), `134` (`SCALE_MODE |
CONCURRENCY_MODE | CUSTOMSIZE_MODE`), `248`, `512` (`VIRTUAL_MODE`), and
`pluginMode: 1` (`AICam`) on the MFNR/B2Y nodes.

## `libmialgoengine` and the M10R name

[observed] `libmialgoengine.so` (2,796,816 bytes) contains the strings:

| String | Offset |
| ------ | ------ |
| `LegendModeM3` | `0x5a683` |
| `LegendModeM9` | `0x5a690` |
| `LegendModeM10R` | `0x5a69d` |
| `legendsnapshot.json` | `0x3c251` |
| `virtualInstanceLegendMetaPath` | `0x4b341` |

The three `LegendMode*` strings sit next to each other in `.rodata`, which is
consistent with an enum/name table. `LegendModeM3` is referenced as a log
argument at `0x1cfd2c` and `0x1e14a0`; no direct comparison against the
literal was found in the disassembly, so this pass does not prove which
numeric value selects each name.

[observed] `libmialgoengine_custom.so` (613,336 bytes) exports:

```text
mialgo2::QcomPrunerCustomization::customizeForLegend(
    std::string const&,
    std::shared_ptr<MiMetadata>,
    std::vector<std::string>&,
    std::map<std::string, std::vector<throughPath>>&,
    std::map<std::string, std::set<disablePort>>&)
```

Its strings:

```text
customizeForLegend
LegendMode PruneValue:%s
Concurrency is set to false for Legend mode
persist.vendor.camera.LegendZoom.enable
LeicaSnapshot_virtualInstance*ToGainmap
```

This is where the Legend pipeline disables concurrency and prunes ports. It is
part of the reason Legend capture works on this model.

## Legend capture metadata (`liblegendmsg.so`)

[observed] `liblegendmsg.so` (70,880 bytes) is a small protobuf library, not a
processing plugin. It exports only `LegendMsg` types and depends on
`libprotobuf`-style symbols (`google::protobuf::*`).

The message schema is visible in its descriptor strings:

```text
LegendMsg.M9Msg
LegendMsg.M9Msg.aisp_algoversion
LegendMsg.M9Msg.AwbGain
LegendMsg.M9Msg.filter_meta
LegendMsg.M9Msg.imgname
LegendMsg.M9Msg.phone_code
LegendMsg.M9Msg.rawcompress_version
LegendMsg.M9Msg.Rect
```

`M9Msg_AwbGain` and `M9Msg_Rect` are nested messages. There is **no**
`M10RMsg` in this library. [inferred] M10R captures either reuse `M9Msg` or
carry the M10R distinction only in `filter_meta`; the schema itself does not
name M10R.

## Native M10R in Camera 6.8

[observed] The Camera 6.8 dex has M10R compiled in. This is the decisive
difference from Camera 6.7 / the 17 Ultra.

### List builder

`p613r2/B.java` is `ComponentConfigLegendary`:

```java
getKey(int)   -> "pref_legendary_mode_key"
getTag()      -> "ComponentConfigLegendary"
isSupportMode(int mode) -> mode == 256
```

Its `Q(A)` builder appends items in this order:

1. **M3**
   `f30594r = AbstractC2783c.VALUE_M3` (`"M3"`)
2. **M10R**, only if `Pe.b.C0264b.f14799a.f14793e.b3()`
   `f30594r = AbstractC2783c.VALUE_M10R` (`"M10R"`)
3. **M9**
   `f30594r = AbstractC2783c.VALUE_M9` (`"M9"`)

`getDefaultValue(int)` returns `"M10R"` when `b3()` is true, otherwise `"M9"`.

### Why `madrid` gets M10R

`b3()` is defined in `defpackage/C1223.java`:

```java
public boolean b3() {
    return this instanceof C1217;
}
```

`C1217` is the device profile for this model. Its supported-mode array in
`G()` includes `256` (the Leica Essential mode), along with the other camera
modes. Therefore `b3()` is true on `madrid`, the M10R item is added, and M10R
is also the default style.

The item fields are on `com.android.camera.data.data.C2784d`:

| Field | Meaning |
| ----- | ------- |
| `f30594r` | value key (`M3` / `M9` / `M10R`) |
| `f30588l` | title string resource |
| `f30589m` | alias |
| `f30590n` | intro |
| `f30583g` | image |
| `f30584h` | template drawable |
| `f30585i` | icon |
| `f30579c` | text icon |

Resource IDs seen in the 6.8 dex:

```text
legend_cmos            2132019491
legend_cmos_alias      2132019492
legendary_mode_m10r_intro   2132019501
legendary_mode_m3_intro     2132019502
legendary_mode_m9_intro     2132019503
legendary_mode_selected     2132019504
legendary_leica_m10r_icon    2131233558
legendary_leica_m10r_template 2131233559
legendary_leica_m10r_text_icon 2131233560
```

`p890z4/D.java` switches the UI on the style string `"M3"` / `"M9"` /
`"M10R"` and selects the matching `legendary_m10r_*` drawables.

### EXIF

[observed] Camera 6.8 contains EXIF tag `legendmode`, id **34992** (`0x88b0`),
type 1, in `p764vf/b.java`. This is the tag Gallery reads for its LEICA
button. A module cannot add it reliably unless the HAL actually processed the
image, which is exactly what happens on `madrid`.

## Legend bokeh capabilities

The app parses `com.xiaomi.legendMode.capsInfo` in
`p402l9/C4034g.java` (around lines 3268–3323). The gate is
`Pe.b...c3()`, which is also `this instanceof C1217`, so it is the same
`madrid` profile check as `b3()`; the log line is `support Legenda Mode`
(typo in the original). The parser reads focal lengths, per-focal mode lists, and
per-focal aperture lists. See
[01-hal-stack.md](01-hal-stack.md#legendmodecapsinfo) for the byte layout and
observed values.

The same class contains `madrid`-specific log strings:

```text
getBokehCapsInfo: i = ...  setMadridModeList　temp = ...
getLegendBokehCapsInfo: madridLegendModeListMax = ...
getLegendBokehCapsInfo setMadridModeList　temp = ...
getLegendBokehCapsInfo: LegendPortraitBokehCapsInfo is null
```

[inferred] The `madrid` name in these logs is the device codename, not a
generic label. It confirms that Camera 6.8 has a model-specific path for this
phone’s Legend bokeh list.

## Confidence summary

| Claim | Confidence | Evidence |
| ----- | ---------- | -------- |
| `legendMode` is session param `com.xiaomi.sessionparams.legendMode` | observed | APK string + core HAL string |
| MiMetadata tag 508 is legend mode | observed | `GetLegendMode` disassembly `mov w3,#0x1fc` |
| MiMetadata tag 29 is stylization type | observed | `GetStylizationType` disassembly `mov w3,#0x1d` |
| `mileicafilter` loads six Leica parameter files | observed | static initializer + file inventory |
| `ParamMode` 1–6 mapping to files | observed | initializer string order and key writes |
| `initResource` legend/proc table | observed | disassembly at `0xce6c` |
| `legendMode` 1/2 are OEM legend, 3 is extra | inferred | core `(legendMode-1) < 2` at `0x11a57d0` |
| M10R is native in Camera 6.8 | observed | `p613r2.B` + `C1223.b3()` + `C1217` |
| `madrid` is the `C1217` profile | inferred | profile mode array contains 256; all 6.8/madrid behavior matches |
| Full numeric style→ParamMode mapping | unknown | see below |

## Unknowns

1. **Exact meaning of `legendMode` 1/2/3.** Core treats 1 and 2 as OEM legend
   (`oemIsLegendMode`), and 3 as extra. The most likely mapping is 1=M9,
   2=M3, 3=M10R, but no single app code path was recovered that writes the
   value. JADX merges supplier classes that return the `legendMode` key with
   unrelated HDR keys, so the decompiled writers are not reliable.
2. **The `initResource` snapshot branch.** `legendMode 3` with non-zero
   `proc` selects `m3.bin`, while preview selects `m10r_preview.bin`. Either
   the snapshot parameter is selected later, or the numeric mapping differs
   from the obvious one. The plugin strings prove both M9 and M10R snapshot
   paths exist.
3. **`proc` field semantics.** It is copied from `CreateInfo+0x44`. Values
   `0`, `1`, and `4` get different `ProcessorCreator::createFilter` formats
   (`10` for 1, `9` for 4) and different labels. The exact enum name is not in
   the shipped headers.
4. **`legendmode.json` contents.** The file is an `encrypted_data:` blob, not
   plain JSON. Its role in selecting styles or pipelines is not established.
5. **Style-transfer data.** `/odm/etc/camera/styletrans/` was not enumerated;
   the `legendST` plugin loads `libmialgo_styletrans.so` and uses that
   directory, but the exact files for M3/M9/M10R are unknown.
6. **No runtime trace.** The dump had no root and no camera session capture,
   so the numeric session-parameter values were not observed live. A rooted
   `dumpsys`/logcat capture while switching M3 → M9 → M10R would settle 1 and
   2 and confirm 3.
