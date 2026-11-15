# 12 — Essential Moments internals

This page is the low-level companion to
[10-essential-moments.md](10-essential-moments.md). Page 10 maps the Java
pipeline and the module integration; this page documents the parts that page
summarises: the exact JNI surface and file offsets, the `legend18` binary
format, the `libleicamodelembed` wrapper, the spawned-helper contracts, the
preview shader, and the measured M3/M9 artifacts.

Evidence markers follow [04-evidence.md](04-evidence.md): `[observed]`,
`[inferred]`, `[unknown]`.

Source APK: `essential-moments (1).apk`, `com.martin.essentialmoments`
`6.8.001960.0`, SHA-256
`6585eeec28b0b1affbecbf050ceb83cc5294032cafa8cc3798bbe1316f2ee7fe`
`[observed]`.

Two statements in page 10 are corrected here and should be read with this
page:

* The Monopan helper is **not** proven to crash. On this unit the app produced
  a genuinely re-encoded, monochrome M3 JPEG, which is only possible when
  `applyMonochrome` returned `true` (section 7).
* The preview is **not** only a stub. `LegendaryPostProcessor.previewShader` /
  `bindPreview` are dead, but `essentialmoments.look.LookStage` renders an
  approximate M3/M10R preview through the OEM extension session (section 9).

---

## 1. Execution map

### 1.1 Capture → save

```
com.android.camera.Camera (activity)
  └─ Camera2Module.updateParallelTaskData()          // builds the Sh.t save task
       ├─ t.b.f17731g = moduleIndex                  // 256 = Legendary
       ├─ MadridBridge.snapshot(t, cameraManager)    // fills t.legendaryStyle,
       │                                            //       t.legendaryPreviewResult
       └─ setupPhotoSaveInterceptors(t)              // installs p691t7.d
  └─ save worker
       └─ p691t7.d.a(t)                              // p585q7.d interceptor
            ├─ MadridBridge.process(t)               // replaces t.f17789a.f17685i (Vh.e bytes)
            └─ stock saver writes DCIM/IMG_*.jpg
```

`[observed]` (`Camera2Module.java:3087`, `p691t7/d.java:113`).

`MadridBridge.snapshot` only runs when `t.b.f17731g == 256`; it reads
`p617r2.C.getComponentValue(256)` into `t.legendaryStyle` and the preview
session's last `CaptureResult` into `t.legendaryPreviewResult`
`[observed]` (`MadridBridge.java:78-89`).

`MadridBridge.process` also handles component 167 (`@StyleAdjustEffect`,
`PalettePostProcessor`) and 171, plus the non-Legendary watermark path; for
256 it calls `LegendaryPostProcessor.process(bytes, task)` and swaps the byte
array only when the result is a different object `[observed]`.

### 1.2 Where the selection lives

| Store | Key | Values | Written by |
| --- | --- | --- | --- |
| Camera component 256 | `pref_legendary_mode_key` | `"M3"`, `"M10R"` in the main picker; `"M9"` via the V43 UI | `p617r2.C`, `p076c6.d`, `LeicaV43Ui` |
| Capture request | `com.xiaomi.sessionparams.legendMode` (Byte) | `1`=M9, `2`=M3, `3`=M10R/other | `T3.c.d()` (`p301ia.V` case 4) |
| Shared prefs | `lmoment_v101` → `style` | `1`=M3, `4`=M10R | `StyleSync.publish`, `M10rFull` |
| EXIF/task | `Sh.t.legendaryStyle` | the same strings | `MadridBridge.snapshot` |

`[observed]` (`p617r2/C.java:91`, `T3/c.java:56-61`, `StyleSync.java:40-51`).

`LegendaryPostProcessor.processStyled` decides M3 vs the M9-family branch with
one comparison:

```java
boolean m3 = "M3".equals(task.legendaryStyle);
```

so `M10R` takes the M9 branch. `LegendaryPostProcessor.setMode()` only changes
the static `useM3` used by the gallery re-render and the OEM extension
(`isM9()`), not the normal capture `[observed]`.

`LegendTag.value()` maps the `lmoment_v101` style to the EXIF `legendmode`
byte: style 1 → 2, everything else → 1, failure → 3 `[observed]`.

### 1.3 Threading and lifecycle

* `processStyled` refuses the main looper and returns the source unchanged
  (`"Unexpected main-thread saver"`) `[observed]`.
* `RenderQueue(6, 100663296)` (6 tickets, 96 MB) bounds concurrent renders.
  `RenderQueue.enter` is **native** (`libleicamodelembed.so` 0x487ea8); the
  Java side only tracks counters and a fair `Semaphore(1)` `[observed]`.
* `ensureModels()` runs lazily on the first render: it extracts
  `leica_filter_param_m3.bin` and `leica_filter_param_m9_snapshot.bin` from
  **`assets/` root** (not `assets/legend18/`), copies the `leica-v4` tree to
  `files/leica-v4`, writes `.ready`, and caches `nativeLibraryDir` paths for
  `libstyletrans_runner.so` and `libmonopan_runner.so` `[observed]`.
* The preview side is `SoftwareRaw` → `OemExtensionSession.open` →
  `LookStage.wrap` (section 9).

Note the two asset roots are not the same files: the capture path uses
`assets/leica_filter_param_m9_snapshot.bin` (SHA-256 `b2b7769…`), while
`MadridLegendary` and the module use
`assets/legend18/leica_filter_param_m9_snapshot.bin` (`60643c…`). They differ
in one 17³ LUT block and a build-timestamp string (section 4.5).
`[observed]`

---

## 2. JNI surface

All entry points below are resolved by class/method name; there is no
`RegisterNatives`. `libm9base.so` and `libleicamodelembed.so` export the same
`LegendaryPostProcessor` names, so **load order matters**: the class loads
`leicamodelembed`, which `dlopen`s `libm9base`; the wrapper's symbol wins for
the app's calls (section 3).

### 2.1 `libm9base.so` (132,216 bytes, SHA-256 `457a099b…`)

| Symbol | Address | Java signature | Contract |
| --- | --- | --- | --- |
| `…_applyOriginalFilter` | `0xccf8` | `(Bitmap, String model, float lux, float cct, float zoom, float phase)` | neutral parametric filter; `false` on bad bitmap/file |
| `…_applyWarmOriginalFilter` | `0xd3e4` | `(Bitmap, String, float lux, float cct, float zoom, float phase, float denoise, float densityEv, float warmth)` | P3 linear-light grading variant (M9) |
| `…_applyBoldOriginalFilter` | `0xd2c8` | same + 1 float | neutral branch, denoise clamped 0..1 (unused) |
| `…_applyMonochrome` | `0xa994` | `(Bitmap, String runner, String cacheDir)` | spawns Monopan; `false` on any failure |
| `…_applyStyle` | `0xc2a4` | `(Bitmap, String runner, String cacheDir, String modelsDir, int orientation, int lux, int cct)` | spawns style transfer; `false` on any failure |
| `…_applyLut` | `0xa540` | `(Bitmap, String file, int block)` | direct 17³ block apply; `lux=-1` path; **unused** |
| `…_applyAdaptiveLut` | `0xc3f8` | `(Bitmap, String file, float lux, float cct)` | adaptive LUT apply; **unused** |
| `…_previewLut` | `0xc49c` | `(String file, float lux, float cct)` (no Java declaration) | returns `byte[14739]` LUT |
| `…_previewShading` | `0xd4c8` | `(String file, float lux, float cct, float zoom)` (no Java declaration) | returns `float[8]` |
| `…_protectShadows` | `0xe988` | `(Bitmap, Bitmap, boolean)` | dead in this build |
| `…_SoftwareRaw_render` | `0xdbb8` | `(Bitmap, byte[], int, int, int, float[], float[], int, int, float[], boolean, Bitmap, float[])` | software RAW develop |
| `…_SoftwareRaw_fuse` | `0xe300` | `(byte[][], float[][], int, int)` | RAW burst fusion |
| `NativeModel_applyLut` | `0xc118` | alias of the above | no `NativeModel` class in the APK |
| `NativeModel_applyMonochrome` | `0xc1c0` | alias of the above | same |

Internal functions used by the doc:

| Function | Address | Role |
| --- | --- | --- |
| `applyLeicaFilter` | `0xcdd0` | common implementation of the three `*OriginalFilter` variants |
| `adaptiveShading` | `0xd620` | validates the file, selects a zoom band, blends 8-float records |
| `adaptiveLut` | `0xc620` | blends 17³ LUT blocks from the `common` table |
| `previewModel` | `0xc9c4` | caches a whole file per path (max 8 MB) |
| `leicaRanges` | `0xeedc` | parses a nested range table |
| `leicaWeights` | `0xf1c8` | 2-tap linear weights for a value |
| `sampleLut` | `0xec00` | 17³ trilinear sample |
| `leicaShadingDelta` | `0x116e8` | vignette/noise/tone term (8 floats + phase) |
| `applyAlgorithm` | `0xaa78` | NV12 conversion + `posix_spawn` + poll + read-back |

### 2.2 `libleicamodelembed.so` (4,753,584 bytes, SHA-256 `7594e681…`)

| Symbol | Address | Role |
| --- | --- | --- |
| `…_applyStyle` | `0x486a20` | B12 wrapper around the `libm9base` entry |
| `…_applyWarmOriginalFilter` | `0x48745c` | B12 wrapper |
| `…_applyOriginalFilter` | `0x4878a0` | B12 wrapper |
| `…_applyMonochrome` | `0x487b30` | B12 wrapper |
| `…_RenderQueue_enter` | `0x487ea8` | native render ticket |
| `…_JpegMetadata_copyExif([B[BZ)` | `0x48853c` | EXIF copy with B8 recovery |
| `snapshot` | `0x486dd8` | reference snapshot before the model |
| `finish` | `0x486f94` | B12 reconciliation after the model |
| `scalar` / `triple` | `0x486950` / `0x486878` | backing computations |

### 2.3 Other libraries

| Library | Entry | Address | Notes |
| --- | --- | --- | --- |
| `libmadridlegend.so` | `MadridLegendary_filter` | `0x4748` | `b` to the `applyOriginalFilter` PLT slot |
| | `MadridLegendary_grain` | `0x474c` | `b` to the `applyMonochrome` PLT slot |
| `libleicaquality.so` | `LeicaQuality_finish` | `0x200b0` | applies a person mask (section 6.3) |
| `libb43tone.so` | `B43Tone_applyNative` | `0x80c8` | fixed 17³ tone LUT baked into the `.so` (`kTone` at `0x5a8`) |
| `libm9b2.so` | `applyStyle` | `0xc19c` | "M9DualB2 / final-look" variant, **not loaded by this build** |
| | `applyWarmOriginalFilter` | `0xc938` | same |
| `libmonopan_runner.so` | `main` | `0x4cd8` | Monopan helper host |
| | `load_vendor` | `0x5008` | namespace + `libc++_shared` + helper loader |
| `libstyletrans_runner.so` | `main` | `0x4e10` | style-transfer host |
| `libperson_runner.so` | `main` | `0x4da8` | human-segmentation host |

`[observed]` (`llvm-nm -D` and `llvm-objdump` on the APK's `lib/arm64-v8a`).

### 2.4 What runs when

| Stage | M3 capture | M9 capture | M10R capture | Gallery M10R |
| --- | --- | --- | --- | --- |
| `applyOriginalFilter` (or warm variant) | yes | yes (after style) | yes (M9 branch) | `MadridLegendary.filter` |
| `applyMonochrome` (Monopan) | yes | no | no | `MadridLegendary.grain` |
| `applyStyle` (style transfer) | no | yes (gate) | yes (gate) | only `FullM9Engine`/`native` |
| `B43Tone` | no | yes | yes | no |
| `LeicaQuality` | no | yes | yes | no |
| `LeicaDynamicRange` | yes | yes | yes | no |
| `OriginalWatermark` | yes (if enabled) | yes | yes | processJpeg for 167 only |

`[observed]` (`processStyled` and `M10rFull`).

---

## 3. `libleicamodelembed`: the B12/B8 wrapper

`libleicamodelembed` is not a second implementation. Every wrapper follows the
same pattern `[observed]` (`applyOriginalFilter` shown):

```c
if (env->ExceptionCheck()) return 0;
/* per-thread state; release a cached bitmap ref from the previous stage */
if (state->bitmap_ref) { env->DeleteLocalRef(state->bitmap_ref); state->bitmap_ref = 0; }
b8_color_free(state->color_ref); state->color_ref = 0;

lib = dlopen("libm9base.so", RTLD_NOW);
fn  = dlsym(lib, "Java_..._applyOriginalFilter");

t0 = now_ms();
ref = snapshot(lux, cct, env, bitmap, /*stage*/ 1, &color_ref);   // reference of the input
t1 = now_ms();
ok  = fn(env, obj, bitmap, path, lux, cct, zoom, phase);          // the real libm9base entry
t2 = now_ms();
if (!ok) { free(ref); return 0; }
result = finish(cct, t0, t1, t2, env, bitmap, ref, color_ref, 0, 1, /*stage*/ 3);
if (result) {
    state->bitmap_ref = env->NewLocalRef(bitmap);   // keep a cross-stage reference
    state->color_ref  = color_ref;
}
dlclose(lib);
return result;
```

* `snapshot` locks the bitmap, stores a compact reference (and a colour
  reference when a `float*` out-parameter is supplied) before the model runs.
* `finish` reconciles the model output against that reference. The stage id is
  `3` for `applyOriginalFilter`, `4` for `applyMonochrome`, `1` for
  `applyStyle`; the hard-coded parameters are `-1.0` (monochrome) or the
  caller's lux/cct.
* If the model returns `false`, the wrapper frees the reference and returns
  `0`. If the referenced bitmap is gone, it logs
  `B12 cross_stage_reference_unavailable; next stage takes fresh input
  reference` and keeps the result.
* `copyExif([B[BZ)` has a separate **B8 recovery** path: it checks that the
  call is not on the main looper, rewrites the EXIF block, and returns the
  source array unchanged on any failure (`B8 recovery=failed … source_retained=1`).

The wrapper is active in the app because `LegendaryPostProcessor`'s static
initializer loads `leicamodelembed`, and that library is loaded after its
`libm9base.so` dependency, so JNI lookup finds the wrapper's symbols
`[inferred from bionic load order]`.

**Consequence:** a process that loads `libm9base.so` directly (the module's
`EssentialService`) gets the *unwrapped* filter: no reference snapshot and no
B12 reconciliation. The pixel pipeline is the same, but the tone/colour
reconciliation is missing. To match the app byte-for-byte, load
`libleicamodelembed` and call through it (section 10).

---

## 4. The `legend18` parameter files

The `.bin` files are a half-float-free, little-endian table format: `uint16`
ranges, `float32` thresholds/records, `uint8` LUT entries. They are not neural
weights.

### 4.1 Header and layout

```
0x0000  u16   magic/version = 6
0x0002  u16   scene_len[6]            (name block + table, per scene)
0x000e  u16   0x0400                  scene area start
0x0010  u16   0x1000                  LUT area start
0x0012  u16   17                      LUT edge (17³)
0x0014  u16   5                       number of zoom-band tables
0x0016  u16   zoom_len[5]             bytes per zoom band
0x0020  char  "aiscene-lux-cct\0"     tag
0x0120  char  "uint16_t\0"            element type tag
0x0128  ..    padding (0x20 bytes) to 0x0400
0x0400  scene blocks, 6 × (64-byte name + table)
       5 zoom-band tables, each (u32 length + float threshold + table)
       padding to 0x1000
0x1000  17³ RGB LUT blocks, N = max_scene_index + 1, 0x3993 bytes each
       shading records, 8 × float32, 32 bytes each
0x????  EOF exactly after the last record
```

`[observed]` by parsing the three files with the decompiled reader. The
generic range table (used by both the scene and zoom tables) is:

```
u16 count                        1..32
repeat count:
    u16 lo, u16 hi, u16 nsub
    repeat nsub:
        u16 lo, u16 hi, u16 value  // value = LUT block or shading record index
```

Both `lo`/`hi` are direct 16-bit integers. `adaptiveShading` requires
`lo <= hi`, a monotonically increasing table, and `nsub >= 1`; `leicaRanges`
rejects a table that overruns its declared byte length.

### 4.2 Per-file map

| File | Size | `tables_end` | LUT blocks | `shade_start` | Records | Flags `0x360/0x362/0x366` |
| --- | --- | --- | --- | --- | --- | --- |
| `m3` | 93,170 (0x16bf2) | 0x6e2 | 6 (idx 0..5) | 0x16972 | 20 | `0/0/1` |
| `m9_snapshot` (capture, root) | 1,686,102 (0x19ba56) | 0xb0e | 114 (0..113) | 0x19b376 | 55 | `0/1/1` |
| `m9_snapshot` (legend18) | 1,686,102 (0x19ba56) | 0xb0e | 114 | 0x19b376 | 55 | `0/1/1` |
| `m9_preview` (root) | 1,686,102 | 0xb0e | 114 | 0x19b376 | 55 | `1/0/1` |
| `m10r_snapshot` | 1,597,668 (0x1860e4) | 0xac6 | 108 (0..107) | 0x185a04 | 55 | `1/1/1` |

`0x360` gates the extra highlight/shadow stage; `0x362` requires
`adaptiveLut` (LUT present); `0x366` enables the shading delta. M3 has **no
LUT flag** (the 6 blocks are unused); M9/M10R have both.

The record counts match `assets/legend18/provenance.json` (20/55/55); the
"max index" in the table above is one less than the record count, because the
index space is inclusive and the last record is at `shade_start + idx*0x20`.

### 4.3 Scene and zoom tables

Every file has the same six scene names, including the typo `protrait`:

```
common, protrait, night, plants, food, sunrise_sunset
```

M3 is a single record per scene (`[0,999] lux → [1,10000] CCT → one index`);
M9 has six lux ranges per scene, M10R five, each with up to four CCT ranges.
The 19 indices of the M9 `common` scene are the only LUT blocks the capture
path can select:

| Scene | M9 block indices |
| --- | --- |
| common | 0–18 |
| protrait | 19–37 |
| night | 38–56 |
| plants | 57–75 |
| food | 76–94 |
| sunrise_sunset | 95–113 |

`adaptiveLut` **hard-codes the `common` table at 0x440**. The other five
scene tables are parsed only to compute the maximum LUT index, which sizes
the LUT area. `[observed]` (`adaptiveLut` call site and `adaptiveShading`
first loop). A future build could select a scene, but this one does not.

The five zoom bands are thresholds `0.6, 1.0, 3.0, 4.3, 10.0` (the classic
0.6×/1×/3×/4.3×/10× lens steps). Selection is "highest threshold ≤ zoom"
(with the first band as the fallback). Each band maps lux ranges to CCT
ranges to a shading-record index. `[observed]`

### 4.4 Runtime selection

```
adaptiveShading(file, lux, cct, zoom, out[8]):
    validate magic 6, u16[7]=0x400, u16[8]=0x1000, u16[9]=17, u16[10]=5
    for each of the 6 scene tables: parse/validate, track max index
    band = last zoom table whose threshold <= zoom (or table 0)
    wl   = leicaWeights(band.outer, lux)        // two nonzero weights
    for each outer range with wl > 0:
        wc = leicaWeights(outer.subs, cct)      // two nonzero weights
        for each sub with wc > 0:
            out += record[index] * wl * wc      // 8 floats
```

`adaptiveLut` is the same walk over the **common** scene table, but it blends
the 0x3993-byte LUT blocks instead of 8 floats, then quantises the float
accumulator back to bytes. `[observed]`

### 4.5 LUT block format

0x3993 = 17³ × 3 bytes:

```
index(r,g,b) = (r*17 + g)*17 + b
byte offset  = index*3 + channel          // channel 0=R, 1=G, 2=B
```

`sampleLut` performs trilinear interpolation with `r,g,b / 255 * 16` and
clamps the cell index to 15 (the 17th sample is the edge). `[observed]`
(`sampleLut`). M9 block 0 starts `00 00 00 10 01 00 1f 02 00 2e 05 00 …`, a
tone curve rather than an identity.

The `m9_preview` file has the same geometry but the LUT flag is 0 and the
block payload differs; it exists for `previewLut`/`previewShading`, which no
Java code calls.

**Capture vs gallery M9:** the two `m9_snapshot` files differ in four header
bytes and one LUT block:

| | Capture (assets root) | `legend18/` |
| --- | --- | --- |
| build stamp at 0x325 | `202601082045` | `202609091645` |
| differing LUT block | block 5 (lux 240–300, CCT 4700–10000) | same block, newer values |
| SHA-256 | `b2b7769a…` | `60643c27…` |
| size | 1,686,102 | 1,686,102 |

`[observed]` (`cmp -l`, parser). The module currently bundles the `legend18`
variant; the app's capture path uses the root variant.

### 4.6 Shading records

Each record is eight little-endian `float32`:

| Index | Role in `leicaShadingDelta` |
| --- | --- |
| 0, 1 | low/high edge of the vignette term `t` |
| 2 | dither/noise amplitude (multiplied by `0.04`) |
| 3 | strength of the gain term |
| 4, 5 | exponential contrast: `exp(p4 * (p5 - luma))` |
| 6 | gain numerator |
| 7 | gain offset |

Observed ranges in the M9 file: `p0` 2.2–4.0, `p1` 0.1, `p2` 1.0–5.0,
`p3` 1.0–1.02, `p4` 3.5–4.3, `p5` 0.6, `p6` 1.15, `p7` 0.05–0.1. `[observed]`

Approximate formula (renamed from the decompiled code):

```
u = (x + 0.5 - w*0.5) / w
v = (y + 0.5) / h - 0.5
d = sqrt(u*u + v*v)
n = mean over 6 sin-based hashes, offset by phase*0.005
t = clamp((d + p2 * n * 0.04 - p0) / (p1 - p0), 0, 1)
luma = 0.299 R + 0.587 G + 0.114 B
g = exp(p4 * (p5 - luma));
gain = p7 + p6 / (g + 1)
delta = luma * (p3 * (gain + smoothstep(t) * (1 - gain)) - 1)
```

The `phase` argument (Java passes `uptimeMillis % 60000`) is the dither seed,
so successive captures differ by a sub-LSB grain pattern `[observed]`.

### 4.7 Worker split

For `w*h >= 0x100000` (1,048,576 pixels), `applyLeicaFilter` starts **three
worker threads** and also runs one band on the calling thread:

```
main:   rows [0,        h/4)
t0:     rows [h/4,      h/2)
t1:     rows [h/2,      3h/4)
t2:     rows [3h/4,     h)
```

Smaller images are processed on one thread. Each band calls
`AndroidBitmap_lockPixels` once per thread (the parent locks/unlocks around
the whole loop). `[observed]` (`applyLeicaFilter`).

---

## 5. The parametric filter

`applyLeicaFilter(env, bitmap, path, lux, cct, zoom, phase, denoise, warm,
density, warmth)` `[observed]`:

1. Read the whole `.bin` into a `vector<uint8_t>`; run `adaptiveShading`.
2. If `u16[0x362] != 0`, run `adaptiveLut`; any failure aborts with `false`.
3. Require `AndroidBitmap_getInfo` format 1 (`RGBA_8888`) and nonzero size.
4. Per pixel, per band:
   * optional 17³ LUT sample (when `0x362`);
   * clamp; if `warm == 0` and (LUT applied or `0x360`), a fixed
     highlight/shadow separation and a mild desaturation using luma
     thresholds around 0.03/0.22 and 0.02/0.20;
   * if `denoise > 0` (M9 derives it from ISO with
     `smooth(400, 2400, iso)`), blend toward a local smoothed estimate;
   * if `warm != 0` (the M9 variant), convert to linear light with the P3 luma
     coefficients (0.22897457, 0.69173855, 0.07928691), apply the 3×3 colour
     matrix and the density/warmth tone, then re-encode;
   * if `0x366`, add `leicaShadingDelta(...)`;
   * clamp to 0..255 and write.
5. Log `Original filter warm=%d LUT=%d shading=%d zoom=%.2f lux=%.1f cct=%.0f params=…`
   on the `LeicaNative` tag and return `true`.

Argument sets actually used by the app:

| Entry | lux | cct | zoom | phase | denoise | density | warmth |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `applyOriginalFilter` | measured | measured | display | uptime%60000 | −1 | −0.35 | 0.025 |
| `applyWarmOriginalFilter` (M9) | `M10rBin.lux` | `M10rBin.cct` | display | uptime%60000 | `M10rBin.denoise(iso)` | −0.95 | 0.3 |

`[observed]` (`applyOriginalFilter`/`applyWarmOriginalFilter` wrappers,
`processStyled`). Unknown metadata falls back to 200 lux / 5500 K / zoom 1.0
(`M9CaptureTuning`, `MadridLegendary`).

`sRGB` vs `Display-P3`: M3 decodes and encodes as sRGB; M9 as Display-P3
(`BitmapFactory.inPreferredColorSpace`, `options.inPreferredColorSpace`).
The M3 artifact's ICC is the 456-byte Google **sRGB** profile; the M9
artifact's is the 520-byte Google **Display P3 Gamut with sRGB Transfer**
profile `[observed]`.

`B43Tone` (M9 only) is a second, fixed 17³ LUT baked into `libb43tone.so`
(`kTone` at `0x5a8`), applied trilinearly after the parametric filter
`[observed]`.

---

## 6. The spawned helpers

All three helpers are standalone executables shipped as `.so` files. They are
started either by `posix_spawn` (Monopan, style) or by Java
`ProcessBuilder` (person).

### 6.1 `libmonopan_runner.so` (Monopan)

```
argv[0] = <nativeLibraryDir>/libmonopan_runner.so
argv[1] = <nativeLibraryDir>/libmialgo_monopan.so
argv[2] = <cacheDir>/monopan-in-XXXXXX
argv[3] = <cacheDir>/monopan-out-XXXXXX
argv[4] = width   (decimal)
argv[5] = height  (decimal)
envp    = { "LD_LIBRARY_PATH=<nativeLibraryDir>", NULL }
cwd     = inherited (the app's, normally "/")
```

`[observed]` (`applyAlgorithm` argv construction and `main` at `0x4cd8`).

* `argc` must be exactly 6 (`main` returns 1 otherwise).
* Width/height must be even, 1..16384, `w*h < 64,000,001`.
* Input file: `1.5*w*h` bytes. The parent allocates `1.5*w*h`, fills it with
  `0x80`, and writes the **luma only** into the first `w*h` bytes:
  `Y = ((66R + 129G + 25B + 128) >> 8) + 16` (Rec.601, limited range).
  Chroma stays neutral `0x80`. `[observed]`
* Output file: `w*h` bytes; the parent expands each byte to `R=G=B`.
* Poll `waitpid(WNOHANG)` every 50 ms, 600 times (30 s), then `SIGKILL` and
  return `false`. `[observed]`

Runner internals (`load_vendor`, `0x5008`):

1. `dlopen("libdl_android.so")`, `dlsym("android_get_exported_namespace")`,
   `android_get_exported_namespace("sphal")`.
2. Build `<dir>/libc++_shared.so` from `argv[1]`'s directory and
   `android_dlopen_ext(..., RTLD_NOW, {namespace=sphal, flags=0x200})` when a
   namespace was returned; otherwise `flags = 0` (default namespace).
3. `android_dlopen_ext(argv[1], ...)`.
4. `dlsym` `MonopanInit`, `MonopanProcess`, `MonopanDeinit`.
5. Process the NV12 buffer in place and write the `w*h` output.

The helper `libmialgo_monopan.so` (18,312,840 bytes) has only
`liblog/libm/libc++_shared/libdl/libc` in `DT_NEEDED`; `MonopanInit` takes a
large parameter struct and `MonopanProcess(handle, in, out)` fills the mono
plane. Its strings show CPU/GPU/DSP candidates but no literal `libQnn*`
name. `[observed]`

### 6.2 `libstyletrans_runner.so` (M9 style transfer)

```
argv[0] = <nativeLibraryDir>/libstyletrans_runner.so
argv[1] = <nativeLibraryDir>
argv[2] = <filesDir>/leica-v4
argv[3] = <cacheDir>/monopan-in-XXXXXX     (same prefixes as Monopan)
argv[4] = <cacheDir>/monopan-out-XXXXXX
argv[5] = width
argv[6] = height
argv[7] = orientation (0/90/180/270)
argv[8] = lux
argv[9] = cct
envp    = { "LD_LIBRARY_PATH=<nativeLibraryDir>", NULL }
```

`[observed]` (`applyAlgorithm` style argv, `main` at `0x4e10`).

* `argc` must be exactly 10.
* `main` does `chdir(argv[2])`, sets `ADSP_LIBRARY_PATH=<argv[2]>/dsp`, then
  loads six libraries from `argv[1]` in the `sphal` namespace, in this order:

  ```
  libmialgo_utils.so, libLMSceneSeg.so, libQnnHtpV81Stub.so,
  libQnnSystem.so, libQnnHtp.so, libmialgo_styletrans.so
  ```

  Each is loaded as `<libDir>/<name>`; the last one provides
  `MialgoAi_DIPS_Init`, `MialgoAi_DIPS_Run`, `MialgoAi_DIPS_Unit`
  `[observed]` (`.data.rel.ro` relocations at 0x9500).
* Input: full NV12 `1.5*w*h` with **Rec.601** limited-range luma
  (`Y = (19595R + 38470G + 7471B + 0x8000) >> 16`) and real interleaved
  Cb/Cr. Output: `1.5*w*h` NV12.
* `MialgoAi_DIPS_Init` is called with the models directory; `Run` gets the
  input/output plane pointers, the literal `"leica-m9"`, rotation, lux, cct.
* Rotation must be 0..270, lux 0..10000, cct 1..20000 (else exit 2).
* The parent treats any non-zero exit, a missing output, or a 30 s timeout as
  `false`.

Models (`assets/leica-v4`, extracted sizes `[observed]`):

| File | Size | Shape / role |
| --- | --- | --- |
| `styletrans_low.minn` | 10,005,294 | input `1×864×1120×4`, output `1×864×1120×3`, tf16, offset −128, delta 0.007843 |
| `styletrans_high.minn` | 9,939,758 | same shape, second model (`model_index` 1) |
| `styletrans_colorfix.minn` | 8,398,942 | input `1×544×544×6`, output `1×544×544×3` |
| `human_segmentation_768/…model` | 6,243,370 | person mask (section 6.3) |
| `human_segmentation_768/…pipeline` | 801 | QNN pipeline descriptor |
| `dsp/libQnnHtpV81.so` | 11,495,296 | DSP skel |
| `dsp/libQnnHtpV81Skel.so` | 8,756,920 | DSP skel |

`styletrans_params.json` selects `runtime: dsp`, key `legend`, and the
thresholds `lux_threshold_daytime = 260`, `cct_threshold = 4692`
`[observed]`. The `.minn` files start with `4e 4e 69 4d` ("MiNN") and contain
obfuscated tensor names (`leica`, `legend`, …), not plain ONNX.

### 6.3 `libperson_runner.so` (person correction)

Java `LeicaQuality.applyPerson` uses `ProcessBuilder`, not `posix_spawn`
`[observed]`:

```
argv[0] = <nativeLibraryDir>/libperson_runner.so
argv[1] = <nativeLibraryDir>
argv[2] = <filesDir>/leica-v4
argv[3] = <cache>/leica-person-XXXX.rgb     // w*h*3, RGB888
argv[4] = <cache>/leica-person-XXXX.mask    // w*h
argv[5] = width
argv[6] = height
env     = cleared, then LD_LIBRARY_PATH=<nativeLibraryDir>
timeout = 8 s
```

`argc` must be 7; width/height must be 1..768 (`0x301`). The input is the
**upright, downscaled** image (`upright()` caps the long edge at 768); the
mask is `w*h` bytes. The runner loads five libraries from `argv[1]`
(`utils`, `LMSceneSeg`, `QnnHtpV81Stub`, `QnnSystem`, `QnnHtp`), chdirs to
the models dir, sets `ADSP_LIBRARY_PATH`, and calls `sceneSegInit` /
`sceneSegRun` / `sceneSegRelease` with the model name
`human_segmentation_768` `[observed]` (`main` at `0x4da8`).

`libleicaquality.finish(src, dst, mask, w, h, orientation)` then applies the
mask to the full-resolution pair. It requires `src`/`dst` to have equal
dimensions and format 1, at most 16000 px per side, and a `w*h` mask whose
`w`/`h` are at most 768. The mask is reduced with a 5×5 minimum and
thresholded at 204 before `finish` `[observed]`.

**Practical effect:** person correction is a no-op if the QNN stack cannot
initialize (the same V81 problem as the style stage), and it only ever sees a
768-pixel mask, so it is a coarse correction.

---

## 7. Measured M3/M9 artifacts

Two captures from the repackaged app on the aurora unit were pulled with
`adb pull` and analysed offline (read-only). They settle the "does the native
chain actually run?" question.

| File | Size | SHA-256 | Content |
| --- | --- | --- | --- |
| `IMG_20261115_192329.jpg` | 2,237,666 | `6a8afa67…` | M9-tagged capture (`88b0=1`) |
| `IMG_20261115_192351_1.jpg` | 3,752,965 | `5084bf92…` | M3-tagged capture (`88b0=2`, `Legend.MONOPAN`) |

### 7.1 M3: the chain ran

`[observed]`:

* The primary image is 3072×4096, **exactly monochrome** (`max|R-G| =
  max|G-B| = 0`).
* Its JPEG quantisation tables are the standard libjpeg **quality 95**
  tables (`00 02 01 01 01 01 01 02 …`), consistent with
  `Bitmap.compress(95)`.
* Its ICC profile is the 456-byte Google **sRGB** profile, which Skia writes
  for an sRGB bitmap.
* Its segment order is `SOI, APP0, APP1(XMP), APP4, APP2(ICC), DQT…` and it
  has **no EXIF APP1**.
* The file ends with a second, quality-92 JPEG referenced by XMP as
  `Legend.MONOPAN` (length 1,687,913, offset from EOF); that second image is
  the same mono frame at quality 92.

A monochrome Skia re-encode is only reachable when
`applyOriginalFilter(...) && applyMonochrome(...)` both return `true`;
`processStyled` otherwise returns the source bytes untouched. The camera HAL
is not the source of the mono frame: aurora's `camera.xiaomi.so` /
`libmialgoengine.so` contain no `legend` strings, and the module's earlier
measurements found the stock HAL inert for M3. Therefore **the Monopan spawn
succeeded on this unit** at 19:23:51, and `applyOriginalFilter` applied the M3
parameters. This is the single most important correction to page 10.

### 7.2 M9: the styled bitmap was discarded

`[observed]`:

* The primary is Display-P3 (`ICC_PROFILE` 520 bytes, "Display P3 Gamut with
  sRGB Transfer").
* Segment order is `APP0, APP1(Exif), APP1(XMP), APP4, APP2(ICC), …`: the
  EXIF is in the camera's original position, not inserted at offset 2 as
  `JpegMetadata.copyExif` would do.
* The EXIF lacks the app's `legendmode` tag (34992), which `markMoment`
  writes on the successful `copyExif(…, true)` path.
* It still carries the app's `LegendExif` tags (Model
  `Xiaomi 17 Ultra by Leica`, `Madrid`, `{}`, the shot JSON with
  `filterId 66048`) because `LegendContainer.finish` runs on the source too.

These signs mean the styled bitmap never reached the final
`compress + copyExif` step: the file is the camera JPEG plus the app's
container/metadata rewriting. The M9 branch is gated by
`if (applyStyleOriented(...)) { applyWarmOriginalFilter(...); … }`, so the
`applyStyle` gate (or the warm filter immediately after it) is the leading
failure point; any later exception in the M9 stage chain would discard the
styled bitmap the same way. A logcat run that captures `processed=M9`
(success) or `Original algorithm failed for M9` (gate failure) would settle
it; the 19:23 buffer is gone.

### 7.3 Why M9 fails and M3 does not

`[inferred]`, with strong support:

* The APK ships **QNN HTP V81** (`libQnnHtpV81Stub.so`, and V81 DSP skels in
  `assets/leica-v4/dsp`), which is the 17 Ultra / SM8750 generation.
* The aurora unit is `ro.board.platform=pineapple`, `ro.soc.model=SM8650`
  (HTP V75-era) `[observed]` (`adb shell getprop`).
* `libLMSceneSeg.so` contains explicit `GetHtpDeviceArch failed` and
  `frame version not match qnn version` checks; the runner loads the APK's
  V81 stub by absolute path, not the device's `/odm/lib64` QNN.
* `MialgoAi_DIPS_Init`/`Run` therefore fail (`Styletrans init=%d` or
  `run=%d` non-zero), the helper exits non-zero, and `applyAlgorithm`
  returns `false`.
* The style stage also has a 30 s poll; a cold model load (`~28 MB` over
  three `.minn` files + DSP skels) could time out even with a compatible
  runtime. Both paths give the same observable result.

`[observed]` the only tombstones on the device are 32 pairs from
`vendor.qti.camera.provider-service_64` (`FORTIFY: fclose: null FILE*`),
unrelated to Essential Moments; there is no Monopan/style-runner tombstone.
So the page-10 claim "the helper SIGSEGVs" is not supported. The helper
fails gracefully (non-zero exit) when its dependencies are wrong; a SIGSEGV
is possible but not what happened here.

### 7.4 Namespace question

The device's `/linkerconfig/ld.config.txt` gives the `sphal` namespace
`permitted.paths` of `/odm/lib64`, `/vendor/lib64`, `/vendor/odm/lib64`,
`/system/vendor/lib64` only — **not** `/data/app` `[observed]` (line 684-687).
If `android_get_exported_namespace("sphal")` returns a namespace handle to an
app process, `android_dlopen_ext("/data/app/.../lib/arm64/libc++_shared.so",
USE_NAMESPACE)` should fail with "not accessible for namespace".

Since the M3 artifact proves the helper ran, the likely explanation is that
`android_get_exported_namespace("sphal")` returns **NULL** for this app, so
the runner leaves `flags = 0` and both `android_dlopen_ext` calls use the
app's default namespace, which does permit `/data/app` `[inferred]`. In that
case the `sphal` code path in both runners is inert on this Android 16
configuration, and the "namespace denies /data/app" theory is not the failure
mode. It remains a risk for other devices/builds and is a reason to keep the
work isolated.

The same reasoning applies to the style and person runners: their
`android_get_exported_namespace("sphal")` call is not the reason M9 fails.

---

## 8. EXIF, container, watermark, naming

### 8.1 Tags written by the app

`LegendExif.apply` rebuilds IFD0 and the Exif IFD and adds `[observed]`:

| Tag | Value |
| --- | --- |
| 272 `Model` | `Xiaomi 17 Ultra by Leica` |
| 39424 `XiaomiModel` | `Xiaomi 17 Ultra by Leica` |
| 34973 | `Madrid` |
| 34979 | `{}` |
| 39321 `XiaomiComment` | shot JSON: `filterId 66048`, `OpMode 36866`, `Hdr off`, `zoomMultiple` = lens factor |
| 34953 | `portrait` (Exif IFD) |
| 34965 | `0` (Exif IFD) |
| 34974 | device JSON (`buildDevice: nezha`, `customize: P1_Leica`, `waterName: 17 Ultra by Leica`) |
| 34992 `legendmode` | 1/2/3 — written *only* by `JpegMetadata.markMoment` in the `copyExif` path, not by `LegendExif` |

`JpegMetadata.markMoment` inserts tag 34992 into the Exif IFD, using
`LegendTag.value()` (1 = M9/M10R, 2 = M3); `writeMomentEntry` is the insert
path.

### 8.2 Container and custom segment

* `LegendContainer.finish` adds an APP4 segment whose payload starts
  `XIAOMI_CUSTOMIZE\0\1\1` followed by a JSON with `"88b0"` = `1` (M9) or
  `2` (M3), `"110"/"9a00"` = `Xiaomi 17 Ultra by Leica`, `"889d"` = `Madrid`,
  `"9999"` = the shot JSON.
* For M3 only, it also builds an XMP `MiContainer` block and appends a second
  JPEG at EOF named `Legend.MONOPAN`; the XMP records its length, offset from
  EOF, MIME, orientation and a SHA-256 of the first 1000 bytes. The second
  image is a quality-92 re-encode of the whole result, not the original
  colour frame `[observed]` (`IMG_…192351_1.jpg`, section 7.1).
* For M9/M10R it adds the EXIF tags and the APP4 segment only (no
  MiContainer) `[observed]`.

### 8.3 Watermark and gainmap

* `OriginalWatermark.apply` reflects into the camera's own
  `qs.b`/`zi.a` watermark engine and only runs when the save task's
  `ExifData.e` flag is true (user enabled the watermark). It renders,
  re-encodes at quality 95, and copies EXIF; on failure it keeps the styled
  image and shows a toast `[observed]`.
* `LeicaDynamicRange.apply` adds an Android `Gainmap` when the source JPEG
  has one (SDR/HDR gainmap); the M9 artifact carries an MPF `MPImage2` +
  ISO 21496 APP2 block `[observed]`.
* `HdrGainmaps.attach/capture` converts the gainmap to/from the app's
  log-ratio representation; M3 also calls `attach` before the film stage.

### 8.4 Naming

The app uses the stock saver's `IMG_yyyyMMdd_HHmmss.jpg` (and `_1` for a
collision); no Leica-specific rename. The module's own pipeline keeps the
file name and atomically replaces the bytes.

---

## 9. Preview path

### 9.1 What is dead

* `LegendaryPostProcessor.previewShader(String, int)` returns its input.
* `LegendaryPostProcessor.bindPreview(int, int, int, Rect)` is empty.
* `libm9base` exports `previewLut` (returns `byte[14739]`) and
  `previewShading` (returns `float[8]`), but no Java class declares them, so
  they are unreachable in this APK.

### 9.2 What actually renders

`SoftwareRaw` opens an OEM camera extension session when the device supports
one; `OemExtensionSession.open` passes
`LookStage.wrap(sessionConfiguration.getOutputConfigurations())`
`[observed]`. `LookStage`:

* replaces the first output surface with an OES `SurfaceTexture` and an
  `EGL` window surface;
* runs a GLES2 fragment shader per preview frame;
* `LookStage.map(surface)` redirects request targets back to the real
  surface;
* `LookStage.release()` tears it down.

The shader (`LookStage.FRAGMENT`) is a small approximation, not the
parametric filter `[observed]`:

```glsl
vec3 c = texture2D(uImage, vTex).rgb;
float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
if (uStyle == 1) {                 // M3
    float v = clamp((y - 0.5) * 1.22 + 0.5, 0.0, 1.0);
    c = vec3(v);
} else if (uStyle == 4) {          // M10R
    c = sat(c, 0.90);
    c = (c - 0.5) * 1.10 + 0.5;
    c += vec3(-0.010, 0.004, 0.014) * (1.0 - smoothstep(0.0, 0.45, y));
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.35);
    c *= 1.0 - 0.15 * smoothstep(0.25, 0.78, length(vPos - vec2(0.5)));
}
```

`uStyle` comes from `com.codex.lmport.PreviewTone.selected` when present,
otherwise `essentialmoments.stock.PreviewStyle.selected`. `StyleSync.publish`
writes `1` for M3 and `4` for M10R; the default is `4`. There is no branch
for M9 (`2`), so an M9 preview is unstyled.

`persist.em.look` (default `1`) or `-1` disables the stage; `persist.em.look.rot`
overrides the rotation; `LensZoom.factor()` drives the zoom crop. `[observed]`

### 9.3 Preview vs capture

| | Preview | Capture |
| --- | --- | --- |
| Renderer | GLES2 shader on the extension preview surface | CPU filter (`libm9base`) + helpers |
| M3 | mono + contrast 1.22 | FilmInput + parametric filter + Monopan |
| M9 | pass-through | style model + warm filter (fails on aurora) |
| M10R | 0.9 sat, 1.10 contrast, shadow tint, S-curve, vignette | M9 branch / M10R gallery chain |
| Colour space | extension output | sRGB (M3), Display-P3 (M9) |

`[observed]`

---

## 10. What this means for HyperCameraMod

### 10.1 Reusable as-is

* **M3 capture look**: `libm9base.so` + the **root** `leica_filter_param_m3.bin`
  (identical to the `legend18` copy) + Monopan. The evidence in section 7
  shows the real helper runs on this unit, so the module can offer true
  Monopan grain rather than only the desaturation fallback.
* **M3/M9 parametric filter**: the `.bin` format and the message-port service
  already match; the pixel pipeline is CPU-only and safe to run in
  `:essential`.
* **EXIF/container logic**: `LegendExif`/`LegendContainer` are ordinary Java
  and could be ported into the module if the user wants the Leica
  `Model`/`Madrid`/`filterId` metadata. That is a metadata rewrite of a stock
  camera file, so keep it opt-in.
* **Preview approximation**: the `LookStage` shader is a cheap way to give
  the module's own preview a mono/contrast look if a GLES stage is added to
  the camera process. It is not the real filter.

### 10.2 Must stay out of the camera process

* All native filters (`applyOriginalFilter`, `applyWarmOriginalFilter`,
  `applyMonochrome`) — a bad bitmap or library mismatch faults the process.
* The three helpers (`monopan_runner`, `styletrans_runner`,
  `person_runner`) — separate executables, separate lifetimes.
* The style/person QNN stack loads device-specific DSP code; never load it in
  the camera app.

The existing `EssentialPipeline` → `EssentialService` design is correct and
should be kept. The 90 s client timeout is generous compared with the native
30 s Monopan poll.

### 10.3 Blocked on aurora

* **M9 style transfer** (`styletrans_low/high/colorfix` + `libQnnHtpV81Stub`):
  the APK targets SM8750/V81; aurora is SM8650/V75. This is the leading reason
  the observed M9 capture never received the styled bitmap. Porting it needs
  V75-compatible QNN models/skels, not a namespace fix. The module's current
  M9 parametric filter is the honest fallback.
* **Person correction** (`libperson_runner` + `libLMSceneSeg`): same V81
  dependency; a no-op on aurora.
* **M10R full look** (`M10rFull` → `com.codex.fullm9.FullM9Engine`, absent;
  `persist.em.native`, `MadridLegendary`): needs the M10R CCM/`legendCT`
  model and the same style runtime. Page 03 already excludes M10R.
* **Watermark**: reflection into the camera's own `qs.b`/`zi.a` classes only
  exists inside the camera app; the module would need to reimplement the
  renderer.

### 10.4 Concrete recommendations

1. **Load `libleicamodelembed.so`, not `libm9base.so` alone.** The app's
   capture path runs through the B12 wrapper; the module currently calls the
   unwrapped entry. Add `libleicamodelembed.so` to `jniLibs` and change the
   trampoline's `System.loadLibrary` order so the wrapper's JNI symbols win
   (`System.loadLibrary("m9base")` then `"leicamodelembed"`, or just
   `"leicamodelembed"`). Keep `libm9base.so` as its `DT_NEEDED`.
2. **Use the capture M9 `.bin`.** If M9 fidelity matters, swap
   `assets/essential/leica_filter_param_m9_snapshot.bin` to the APK-root
   file (SHA-256 `b2b7769a…`), not the `legend18` copy (`60643c27…`).
   The M3 file is identical in both locations.
3. **Forward the real metadata.** `EssentialPipeline` currently sends
   `lux=200, cct=5500` and the service passes `zoom=1.0`. The app uses the
   capture `CaptureResult` (`AecLux`/`AWBFrameControlCCT`), the display zoom,
   and ISO. The camera process has all of these; add them to the `Bundle`
   (they are plain floats/ints) so the shading/LUT selection and the
   denoise/density parameters are correct.
4. **Bundle the Monopan closure** (`libmonopan_runner.so`,
   `libmialgo_monopan.so`, `libc++_shared.so`, and the QNN libs the helper
   actually loads) and enable the real grain stage. Keep the desaturation
   fallback for the `false`/timeout path. Section 7 shows it works; the
   earlier "it segfaults" assumption should be dropped.
5. **Do not call `applyWarmOriginalFilter` for M9 without the style stage.**
   The app only reaches it after `applyStyle`; using it alone would produce a
   different (and unsupported) look. The module's current choice of the
   neutral `applyOriginalFilter` for both styles is defensible.
6. **Keep the preview untouched by default.** A styled preview needs the
   `LookStage` GLES stage and the OEM extension session; it is a separate
   feature and only approximate for M3/M10R.
7. **Keep the service isolated.** Even with the corrected failure analysis,
   the native helpers and QNN are the only third-party code the module loads;
   a bad helper must never take down the camera.

---

## 11. Reproduction

All commands are read-only on the device and write only under `/tmp/opencode`.

```sh
# APK and hashes
sha256sum "/run/media/rezikham/970/PoC/essential-moments (1).apk"
unzip -l "/run/media/rezikham/970/PoC/essential-moments (1).apk" | \
  grep -E 'leica_filter_param|leica-v4|libm9|libmonopan|libstyletrans|libperson'

# decompile (already done in /tmp/opencode/em_src)
jadx -d /tmp/opencode/em_src --no-res --no-debug-info \
  "/run/media/rezikham/970/PoC/essential-moments (1).apk"

# native symbols and entry points
llvm-nm -D --defined-only /tmp/opencode/em_apk/lib/arm64-v8a/libm9base.so
llvm-nm -D --defined-only /tmp/opencode/em_apk/lib/arm64-v8a/libleicamodelembed.so

# decompile by address (Ghidra)
/opt/ghidra/support/analyzeHeadless /tmp/opencode/ghidra_proj em_m9base \
  -import /tmp/opencode/em_apk/lib/arm64-v8a/libm9base.so -overwrite \
  -scriptPath /tmp/opencode/ghidra_scripts \
  -postScript DecompileAt.java 0x10cdd0 0x10d620 0x10c620 0x10aa78

# legend18 parser (section 4)
python3 /tmp/opencode/parse_legend18.py \
  /tmp/opencode/em_apk/assets/legend18/leica_filter_param_m3.bin \
  /tmp/opencode/em_apk/assets/legend18/leica_filter_param_m9_snapshot.bin \
  /tmp/opencode/em_apk/assets/legend18/leica_filter_param_m10r_snapshot.bin

# compare capture vs gallery M9
unzip -o "/run/media/rezikham/970/PoC/essential-moments (1).apk" \
  'assets/leica_filter_param_*' -d /tmp/opencode/em_assets_root
cmp -l /tmp/opencode/em_assets_root/assets/leica_filter_param_m9_snapshot.bin \
       /tmp/opencode/em_apk/assets/legend18/leica_filter_param_m9_snapshot.bin | wc -l

# device reads
adb -s 1c4e4dec shell 'cat /linkerconfig/ld.config.txt' | grep -A8 'namespace.sphal'
adb -s 1c4e4dec shell 'getprop ro.board.platform; getprop ro.soc.model'
adb -s 1c4e4dec shell 'ls /data/tombstones | wc -l'
```

The parsed format was validated by requiring `shade_start + records*32 ==
file size` for all three files; the match is exact (section 4.2).

---

## 12. Corrections to page 10

| Page 10 statement | Correction |
| --- | --- |
| "The Monopan helper … is the stage that crashes on aurora." | No tombstone exists; the app produced a re-encoded mono M3 file, so the helper ran. Failures surface as a non-zero exit, not necessarily SIGSEGV. |
| "M9 adds the style-transfer model … the app can produce it" | The M9 artifact never received the styled bitmap (EXIF not re-inserted, no `legendmode` tag); the `applyStyle` gate is the leading failure point, most likely because the APK's QNN HTP V81 does not match SM8650. |
| "20 shading records for M3 and 55 for M9" | Correct: 20/55 records; the highest *index* is 19/54. |
| "processes the bitmap in three worker threads" | Three worker threads plus the calling thread: four horizontal bands, and only for `w*h >= 0x100000`. |
| "previewShader()/bindPreview() are stubs … the visible preview look comes from the camera's normal preview pipeline" | Those two methods are dead, but `LookStage` renders an M3/M10R GLES approximation through the OEM extension session. |
| "M9 uses `leica_filter_param_m9_snapshot.bin`" | The capture path uses the APK-root file (`b2b7769…`), not the `legend18/` file (`60643c27…`). |
| "applyOriginalFilter is the same as the app" | The app's call resolves to the `libleicamodelembed` B12 wrapper, which adds a reference snapshot and a reconciliation pass around the `libm9base` entry. |
