# 10 — Moment App: the real M3/M9 pipeline

This page maps the genuine Leica pipeline in **Moment App**
(`com.martin.essentialmoments`, version `6.8.001960.0`, 150 MB APK) and documents
the optional module path that runs it on saved JPEGs.

> **Deep material lives in
> [12-moment-app-internals.md](12-moment-app-internals.md).**
> That page has the JNI symbol/offset tables, the `legend18` binary layout,
> the `libleicamodelembed` B12 wrapper, the helper argv/env contracts, the
> preview shader, and the measured M3/M9 artifacts. Two conclusions below are
> corrected there: the Monopan helper does not provably crash (the app
> produced a processed mono M3 JPEG), and the M9 style stage fails on aurora
> for a QNN-version reason, not a namespace rule. Section 12 of page 12 lists
> every correction.

The short version:

* The app is a repackaged Xiaomi Camera (`com.android.camera`) with extra
  classes under `com.codex.leicaapp` and `essentialmoments.*`.
* The real capture chain is `LegendaryPostProcessor.processStyled`; the simple
  two-call core is `MadridLegendary.filter/grain`.
* M3 and M9 use the same parametric filter (`libm9base.so` + a legend18 `.bin`)
  and differ in what runs around it. M3 adds Monopan grain; M9 adds the
  style-transfer model, warm grading, person correction, tone and gainmap.
* The Monopan helper is spawned by `libm9base.so` with `posix_spawn`. It can
  fail or be killed, so the module runs all native work in a private process
  and treats Monopan as optional, falling back to the app's own grayscale path.
  The app's own artifact shows the helper does run successfully on this unit
  (page 12 section 7).
* The module path is **off by default**, is **additional**, and never replaces
  the HAL LUT slot, the stock filters, or the existing LUT fallback.

## 1. Where the code is

| Layer | Class / file | Role |
|---|---|---|
| Launcher | `com.codex.leicaapp.CameraLauncher` | Starts `com.android.camera.Camera` inside the repackaged APK. |
| Camera UI | `com.android.camera.*` | The stock Xiaomi Camera classes, patched by the app. |
| Capture snapshot | `com.android.camera.module.Camera2Module` → `MadridBridge.snapshot(t, cameraManager)` | Copies the selected Legendary style and the last preview `CaptureResult` onto the save task. |
| Save hook | `p691t7.d.a(t)` → `MadridBridge.process(t)` | Runs before storage; replaces the JPEG bytes when a Legendary style is active. |
| Capture chain | `com.codex.leicaapp.LegendaryPostProcessor` | JPEG → Bitmap → RAW develop → film tone → filter → grain/style → tone/quality/HDR/watermark → JPEG 95. |
| Simple core | `com.codex.leicaapp.MadridLegendary` | JPEG → Bitmap → `filter()` → optional `grain()` → JPEG 95. Also used by the M10R gallery re-render. |
| RAW | `com.codex.leicaapp.SoftwareRaw` | Camera2 RAW reprocess session, develops DNG-like frames and replaces the JPEG session. |
| Extension session | `com.codex.leicaapp.OemExtensionSession` | OEM camera extension for M9 exposure/WB tuning. |
| Person correction | `com.codex.leicaapp.LeicaQuality` | Spawns `libperson_runner.so`, applies a skin/person mask. |
| EXIF/metadata | `JpegMetadata`, `LegendContainer`, `OriginalWatermark` | Copies EXIF, adds the Leica container/watermark and `Xiaomi 17 Ultra by Leica` model. |
| Layout/UI | `p076c6.d`, `p617r2.C` | `FragmentLegendarySelected` and `ComponentConfigLegendary` (component 256). |

The app's `Camera2Module` is the stock class plus one call at the save snapshot
site; this is why the pipeline receives a `Sh.t` save task containing the
`CaptureResult` and the JPEG bytes.

## 2. M3 vs M9 selection

The style is a string component, not a global mode for normal captures:

* `com.android.camera.data.data.AbstractC2757c.VALUE_M3` = `"M3"`
* `VALUE_M9` = `"M9"`
* `VALUE_M10R` = `"M10R"`

`p617r2.C` (the `ComponentConfigLegendary` implementation) stores it under
component id **256**. `MadridBridge.snapshot()` reads
`getComponentValue(256)` into `t.legendaryStyle`; `processStyled()` compares it
with `VALUE_M3`:

```java
boolean m3 = VALUE_M3.equals(t.legendaryStyle);
```

There is also a static `LegendaryPostProcessor.setMode(String)` / `useM3` flag,
but the normal capture path does **not** use it. It is used by the gallery /
M10R re-render path (`essentialmoments.texture.M10rFull`) through reflection,
which also temporarily swaps `m9ModelPath` to the M10R snapshot. `isM9()` is
used by the OEM extension session for exposure and white balance.

So: **M3/M9 is per-capture metadata**. The module's own `lastLegend` hook reads
the same `com.xiaomi.sessionparams.legendMode` byte (`M9=1`, `M3=2`).

Output-side markers exist but are not reliable pre-capture selectors:

* The app's finished M3 JPEG carries an XMP `Xiaomi MiContainer` item named
  `Legend.MONOPAN`; its colour shots carry the model `Xiaomi 17 Ultra by Leica`
  and JSON `FilterId` 66048.
* A stock-camera monochrome capture observed on the same phone had no
  MiContainer item, model `Xiaomi 14 Ultra` and `FilterId` 655591.

The module therefore uses the **request key**, not the output marker, and
consumes one selection per saved file (3-minute freshness window) so a later
normal capture cannot inherit an old M3/M9 choice.

## 3. The capture chain

`LegendaryPostProcessor.process(bytes, task)`:

1. `Bypass.skip()` — `persist.em.process=0` returns the input untouched.
2. `Crop35.apply(bytes)` — optional 35/75 mm crop for the app's portrait mode.
3. `processStyled(bytes, task)` — the real chain.
4. `LegendContainer.finish(bytes)` — rewrites EXIF, adds the Leica container
   block and the `Xiaomi 17 Ultra by Leica` model.

`processStyled` runs on the save worker, never the main thread (it explicitly
returns the source JPEG if called on the main looper). It uses a
`RenderQueue(6 tickets, 96 MB)` to bound concurrent renders.

### 3.1 JPEG decode and colour space

```java
options.inMutable = true;
options.inPreferredConfig = ARGB_8888;
options.inPreferredColorSpace = (m3 ? SRGB : DISPLAY_P3);
Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
```

M3 is processed in sRGB, M9 in Display-P3. If the decoded bitmap's colour space
does not match the requested one, the whole render is abandoned and the source
JPEG is kept.

### 3.2 M3 path

1. `HdrGainmaps.attach(bitmap, null)`.
2. `SoftwareRaw.develop(task, bitmap, true)` — RAW develop when the capture
   session produced RAW. On failure it falls back to the camera JPEG and shows
   a toast ("RAW is unavailable for this frame...").
3. `FilmInput.apply(bitmap, true, ISO, gains)` — a GLES20 tile renderer that
   softens, denoises, applies the film tone curve and finally a mono mix
   (`dot(c, vec3(.46,.48,.06))`). This is the M3 "film" tone.
4. `applyOriginalFilter(bitmap, m3ModelPath, lux, cct, zoom, phase)` — the
   parametric Leica filter from `libm9base.so`.
5. `applyMonochrome(bitmap, monopanRunner, cachePath)` — the Monopan stage.
   The Java code uses short-circuit `&&`, so if the filter fails Monopan is
   not called; if Monopan fails the whole M3 chain is treated as failed and
   the source JPEG is kept.
6. `B43Tone.apply` (M9 only in practice), `LeicaQuality.apply` (skipped for M3),
   `LeicaDynamicRange.apply`, `OriginalWatermark.apply`.
7. `bitmap.compress(JPEG, 95)` and `JpegMetadata.copyExif(...)`.

### 3.3 M9 path

1. Decode in Display-P3, attach gainmap, `SoftwareRaw.develop(..., false)`
   (M9 skips the GL film stage).
2. `M10rBin.before/lut1` — the B12 pre-LUT.
3. `applyStyleOriented(bitmap, orientation, lux, cct)` — calls
   `applyStyle(bitmap, libstyletrans_runner.so, cacheDir, leica-v4 dir,
   orientation, lux, cct)`. The native `applyStyle` runs the style-transfer
   model (`libmialgo_styletrans.so`) through the same `applyAlgorithm`
   spawn mechanism as Monopan. The bitmap is rotated upright first and rotated
   back afterwards.
4. `applyWarmOriginalFilter(bitmap, m9ModelPath, lux, cct, zoom, phase,
   denoise, densityEv, warmth)` — the M9 grading with the M9 snapshot, density
   `-0.95 EV`, warmth `0.3`.
5. `B43Tone.apply`, `LeicaQuality.apply` (person correction),
   `LeicaDynamicRange.apply` (SDR/HDR gainmap), `OriginalWatermark.apply`.
6. JPEG 95 + EXIF copy.

### 3.4 What `libm9base.so` exports

All methods are JNI-bound to `com.codex.leicaapp.LegendaryPostProcessor`:

```
applyMonochrome(Bitmap, String runner, String cacheDir)
applyStyle(Bitmap, String runner, String cacheDir, String modelsDir, int orientation, int lux, int cct)
applyOriginalFilter(Bitmap, String model, float lux, float cct, float zoom, float phase)
applyWarmOriginalFilter(Bitmap, String model, float lux, float cct, float zoom,
                        float phase, float denoise, float densityEv, float warmth)
applyBoldOriginalFilter(...)
applyLut(Bitmap, String lut, int mode)
applyAdaptiveLut(Bitmap, String lut, float a, float b)
previewLut(...)
previewShading(...)
protectShadows(...)
```

`applyOriginalFilter` and `applyWarmOriginalFilter` both end in
`applyLeicaFilter`, which is the parametric filter. `applyStyle` and
`applyMonochrome` both end in `applyAlgorithm`, which is the spawner.

The app does not call these `libm9base` entries directly. Its static
initializer loads `libleicamodelembed.so`, which `dlopen`s `libm9base.so`,
calls the original symbol, and wraps it in a B12 reference/reconciliation pass
(`snapshot` before, `finish` after). A process that loads only `libm9base`
gets the same pixels but not the B12 reconciliation. Symbol addresses and the
wrapper contract are in [12](12-moment-app-internals.md) section 2-3.

### 3.5 How the legend18 `.bin` files are used

The `.bin` files are **not** neural weights. `applyLeicaFilter` reads the whole
file into a `std::vector<uint8_t>`, then:

* validates a little-endian header (`u16[0] = 6`, `u16[7] = 0x400`,
  `u16[8] = 0x1000`, `u16[9] = 17`, `u16[10] = 5`; tag
  `aiscene-lux-cct` at 0x20, `uint16_t` at 0x120);
* runs `adaptiveShading(data, lux, cct, zoom, out[8])` — selects a zoom band
  (0.6/1.0/3.0/4.3/10.0), then bilinearly blends the 8-float shading records
  by lux and CCT;
* checks flags at `0x362` (LUT) and `0x366` (shading);
* when `0x362` is set, runs `adaptiveLut(data, lux, cct, out)` — a 17³ RGB
  LUT (0x3993 bytes per block) blended from the `common` scene table;
* processes the bitmap in four horizontal bands (main + three workers) when
  `w*h >= 1,048,576`, via `AndroidBitmap_lockPixels`.

`provenance.json` in the app records 20 shading records for M3 and 55 for M9;
the file sizes are 93,170 / 1,686,102 / 1,597,668 bytes. The capture path uses
`assets/leica_filter_param_m9_snapshot.bin` at the APK root (SHA-256
`b2b7769a…`), which differs from the `assets/legend18/` copy (`60643c27…`) in
one LUT block and a build timestamp; the module currently bundles the
`legend18` copy. The full byte layout, per-file tables and selection
algorithm are in [12](12-moment-app-internals.md) section 4.

Unknown metadata defaults, taken from `MadridLegendary.process`:

| Parameter | Range | Default |
|---|---|---|
| lux | 0..1000 | 200 |
| CCT | 1500..15000 | 5500 |
| zoom | 0.1..200 | 1.0 |
| phase | uptime ms % 60000 | 0 |

### 3.6 What `libleicamodelembed.so` / `libleicaquality.so` do

* `libleicamodelembed.so` is loaded by `LegendaryPostProcessor`'s static
  initializer as the "APK native Leica runner". It re-exports the same JNI
  entry points and adds the M9 `M9_CLEAR_DENSITY_B12` grading, a B8 recovery
  path and the `RenderQueue`/`JpegMetadata` JNI helpers. It depends on
  `libm9base.so`.
* `libleicaquality.so` implements `LeicaQuality.finish(Bitmap src, Bitmap
  out, byte[] mask, w, h, orientation)`. `LeicaQuality.apply` decodes a
  downscaled copy of the source, asks `libperson_runner.so` for a person mask
  (raw RGB temp file, 8 s timeout, `LD_LIBRARY_PATH=nativeLibraryDir`), then
  applies the masked correction to the processed bitmap. M9 only.
* `libm9b2.so` is the M9 "B2" grading variant (`M9DualB2` /
  `final-look`) with its own `applyStyle` and `applyWarmOriginalFilter`
  implementations, layered on `libm9base.so`.
* `libmadridlegend.so` is only a two-symbol bridge: `filter` jumps to
  `applyOriginalFilter`, `grain` jumps to `applyMonochrome`. It is the ABI used
  by `MadridLegendary`.

## 4. The Monopan spawn, exactly

`applyMonochrome` calls:

```c
applyAlgorithm(env, bitmap, runnerPath, cacheDir, /*extra=*/NULL,
               /*mode=*/0, /*lux=*/200, /*cct=*/5000)
```

`applyAlgorithm` does the following:

1. Validates the bitmap (RGBA_8888, even dimensions, `w*h <= 50,000,000`).
2. Allocates `1.5 * w * h` bytes, fills it with `0x80`, then writes the
   limited-range luma `Y = (66R + 129G + 25B + 128) >> 8` into the first
   `w*h` bytes. The remaining half stays `0x80` (neutral chroma), so the input
   is effectively NV12 with a flat chroma plane.
3. Creates temp files in `cacheDir`:

```
<cache>/monopan-in-XXXXXX
<cache>/monopan-out-XXXXXX
```

   with `mkstemp`, writes the `1.5*w*h` buffer, closes both.
4. Builds `helperPath = dirname(runnerPath) + "/libmialgo_monopan.so"`.
5. Builds argv/envp:

```
argv[0] = <nativeLibraryDir>/libmonopan_runner.so
argv[1] = <nativeLibraryDir>/libmialgo_monopan.so
argv[2] = <cache>/monopan-in-XXXXXX
argv[3] = <cache>/monopan-out-XXXXXX
argv[4] = width
argv[5] = height
envp[0] = "LD_LIBRARY_PATH=<nativeLibraryDir>"
envp[1] = NULL
```

   then `posix_spawn(&pid, argv[0], NULL, NULL, argv, envp)`.
6. Polls `waitpid(WNOHANG)` every 50 ms, up to 600 iterations (30 s), then
   `SIGKILL`s the child.
7. On success, reads `w*h` bytes from the out file and expands each byte to
   `R=G=B` in the bitmap. On any failure it returns `false`.

`libmonopan_runner.so` is a tiny executable (9 KB, `NEEDED libdl/libc`). Its
`main` requires exactly 6 arguments, reads `1.5*w*h` bytes, then:

1. `dlopen("libdl_android.so")`, `dlsym("android_get_exported_namespace")`,
   `android_get_exported_namespace("sphal")`;
2. builds `<dir>/libc++_shared.so` from the helper path;
3. `android_dlopen_ext(libc++_shared, RTLD_NOW, {namespace=sphal,
   flags=ANDROID_DLEXT_USE_NAMESPACE})`;
4. `android_dlopen_ext(helper, RTLD_NOW, ...)`;
5. `dlsym` for `MonopanInit` / `MonopanProcess` / `MonopanDeinit`;
6. runs the model, writes `w*h` bytes, returns 0.

### What actually fails

The earlier "the helper SIGSEGVs" diagnosis is not supported and is corrected
here. **Observed:** the app produced a re-encoded monochrome M3 JPEG
(`IMG_20261115_192351_1.jpg`, 19:23:51) with Skia quality-95 tables and an sRGB
ICC profile. That output is only reachable when both `applyOriginalFilter` and
`applyMonochrome` return `true`, so the Monopan spawn and the helper ran
successfully on this unit. There is also no Monopan tombstone: the only
`/data/tombstones` entries are the camera provider's unrelated
`FORTIFY: fclose` aborts.

The runner and the helper can still fail, and the failure is graceful: a bad
`dlopen`, a QNN init error or a timeout makes the runner exit non-zero,
`applyAlgorithm` returns `false`, and M3 would fall back to the source. The
static risk factors remain real but are not the observed cause:

* `sphal`'s permitted paths on this Android 16 build are `/odm/lib64`,
  `/vendor/lib64`, `/vendor/odm/lib64` and `/system/vendor/lib64` — not
  `/data/app`. If `android_get_exported_namespace("sphal")` returned a handle
  to the app, the absolute-path `android_dlopen_ext` would fail. Since Monopan
  works, the likely path is that the lookup returns `NULL`, the runner leaves
  `flags=0`, and the app's default namespace (which does permit `/data/app`)
  loads the two libraries `[inferred]`.
* Monopan's `MonopanInit` is global; two concurrent renders or a render during
  teardown can still race. The app's `RenderQueue` and the module's private
  process are the mitigations.
* The spawn sets only `LD_LIBRARY_PATH` (no cwd, file actions or attributes);
  a helper that opens libraries late by name relies on the app's
  native-library search path.

The style-transfer stage uses the same `applyAlgorithm` mechanism but fails
for a different reason: the APK ships **QNN HTP V81** stubs and
DSP skels for the SM8750 generation, while aurora is SM8650 (HTP V75-era). The
observed M9 JPEG never received the styled bitmap: its EXIF was not re-inserted
and the `legendmode` tag is absent, so the M9 chain discarded the result. The
`applyStyle` gate is the leading failure point, with the QNN version mismatch
as the likely cause. Details and the artifact proof are in
[12](12-moment-app-internals.md) sections 6-7.

## 5. Preview vs capture

The capture path above is the only path that produces the final look. The
preview path is different:

* `LegendaryPostProcessor.previewShader()` / `bindPreview()` are dead code in
  this build (`previewShader` returns its input, `bindPreview` is empty).
* `SoftwareRaw` opens an `OemExtensionSession` when the device supports camera
  extensions; `LookStage.wrap()` replaces the preview surface with an OES
  texture and a GLES2 pass renders an **approximate** look per frame:
  `uStyle == 1` is M3 (luma + contrast 1.22), `uStyle == 4` is M10R (0.9
  saturation, 1.10 contrast, shadow tint, S-curve, vignette). There is no M9
  branch, so an M9 preview is unstyled.
* `StyleSync` writes the shader's style field (`1` for M3, `4` for M10R;
  default `4`); `persist.em.look` can disable the stage.
* `LegendaryPostProcessor.previewLut` / `previewShading` exist in
  `libm9base.so` (and can return a `byte[14739]` LUT and a `float[8]`) but no
  Java class declares them, so they are unreachable.

So the preview is a cheap shader approximation; the real M3/M9 look is
**post-capture**. The module follows the same model: preview is untouched, the
saved JPEG is processed. The shader source and lifecycle are in
[12](12-moment-app-internals.md) section 9.

## 6. Integration design

### 6.1 New setting

`Leica Essential pipeline (real M3/M9)`, key `essential_pipeline_enabled`,
default **false**. It is an additional path:

* It does not change any stock filter, LUT slot or camera request.
* When on, it owns the post-processing of a new DCIM JPEG **only** when
  `legendMode` is M3 or M9.
* It refuses to run when the HAL LUT slot setting is on, so the look is never
  applied twice.
* It is skipped when `legendMode` is 0; stock photos and stock filters are
  untouched.
* It replaces the LUT fallback for that file (one post-process, not two).
* It consumes one M3/M9 request per saved file: the request must be at most
  3 minutes old, and `lastLegend` is cleared after the file is handled. A later
  normal capture cannot inherit a stale M3/M9 selection.

### 6.2 Process isolation

Native work never runs in the camera process:

```
camera process (com.android.camera)
  ModuleMain / EssentialPipeline
      binds an explicit ComponentName
      sends JPEG bytes through a ParcelFileDescriptor pipe
      waits up to 90 s
      reads result from a second pipe
      atomically replaces the DCIM JPEG
                    |
                    v
module process (io.rezikham.hypercameramod:essential)
  EssentialService
      checks Message.sendingUid against com.android.camera
      extracts the .bin asset once
      System.loadLibrary("c++_shared"); System.loadLibrary("m9base")
      applyOriginalFilter(...)
      M3: optional applyMonochrome(...), else desaturate (app fallback)
      JPEG 95 + EXIF APP1 copy
```

Why a bound service instead of a thread:

* `applyOriginalFilter` is native and can fault on a bad bitmap or a future
  library mismatch. A fault kills only `:essential`.
* `applyMonochrome` `posix_spawn`s a helper that can load third-party QNN code
  and fault; it runs as a child of `:essential`, not of the camera.
* If `:essential` dies, the binder connection drops, the client's latch is
  released, and the original JPEG stays. The camera session is unaffected.
* No storage permission is needed: the JPEG travels through pipes, not through
  `/sdcard`.

The service is exported because the camera process has a different UID. It
validates `msg.sendingUid`; requests from any other UID are rejected.

### 6.3 What is bundled

| File | Size | Purpose |
|---|---|---|
| `lib/arm64-v8a/libm9base.so` | 132 KB | Parametric filter + spawn logic. |
| `lib/arm64-v8a/libc++_shared.so` | 1.26 MB | `DT_NEEDED` of `libm9base.so`. |
| `assets/essential/leica_filter_param_m3.bin` | 93 KB | M3 shading/LUT parameters (identical to the APK root file). |
| `assets/essential/leica_filter_param_m9_snapshot.bin` | 1.69 MB | M9 shading/LUT parameters — currently the `assets/legend18/` copy (`60643c27…`), not the capture file (`b2b7769a…`). |

Release APK: **2.47 MB** (was 1.36 MB). The 18 MB Monopan helper, the 13 MB
style-transfer helper, the 4.7 MB model embed and the QNN runtime are **not**
bundled. Page 12 section 10 recommends loading `libleicamodelembed.so` and
switching the M9 file to the capture variant.

### 6.4 Optional Monopan grain

`EssentialService` checks for `libmonopan_runner.so` and
`libmialgo_monopan.so` in the module's `nativeLibraryDir`. If both are present
it calls the real `applyMonochrome`, with the module's own cache dir and its
own native lib dir. If that returns false, times out, or crashes, the service
falls back to the app's own M3 fallback: saturation 0 via `ColorMatrix`.

To enable the real grain stage, copy the files into `jniLibs` before building:

```sh
unzip -o "/run/media/rezikham/970/PoC/essential-moments (1).apk" \
  'lib/arm64-v8a/libmonopan_runner.so' 'lib/arm64-v8a/libmialgo_monopan.so' \
  'lib/arm64-v8a/libQnn*.so' -d /tmp/opencode/em_libs

cp /tmp/opencode/em_libs/lib/arm64-v8a/libmonopan_runner.so \
   /tmp/opencode/em_libs/lib/arm64-v8a/libmialgo_monopan.so \
   /tmp/opencode/em_libs/lib/arm64-v8a/libQnn*.so \
   src/main/jniLibs/arm64-v8a/
./gradlew :assembleRelease --offline
```

This is intentionally not the default: it adds about 22 MB. The app's own
artifact proves the helper runs on aurora, but it can still return `false` when
the QNN dependencies are wrong. When it returns false or dies, the fallback is
the same saturation-0 result the app itself uses when grain is unavailable.

### 6.5 What is implemented vs blocked

Implemented:

* Genuine M3/M9 parametric filter from `libm9base.so` and the real `.bin` files.
  Decoding follows `MadridLegendary` (sRGB for both styles); the full
  `processStyled` Display-P3 M9 path is not included.
* True monochrome M3, using the app's exact saturation-0 fallback.
* EXIF APP1 preservation and atomic file replacement.
* Process-isolated native execution with binder-death and timeout handling.
* Optional real Monopan attempt with graceful fallback.
* Off-by-default setting, caller validation, double-processing guard.

Not implemented (deliberately, to keep this small and honest):

* `FilmInput` GLES film tone (M3) and `LeicaDynamicRange` gainmap.
* M9 `applyStyle` style-transfer model (`libstyletrans_runner.so`,
  `libmialgo_styletrans.so`, `leica-v4` models).
* M9 `applyWarmOriginalFilter` warm grading, `M10rBin` B12 LUT.
* `LeicaQuality` person correction, `B43Tone`, `OriginalWatermark`.
* `SoftwareRaw` RAW development and `LegendContainer` EXIF container/watermark.
* M10R and the preview path.

Blocked on aurora / not bundled:

* Monopan grain: the helper and its QNN closure are optional and not bundled by
  default. Evidence now shows the app's own Monopan stage runs on aurora, so
  this is a packaging choice, not a platform block; without the closure M3 uses
  the app's own grayscale fallback.
* M9 style-transfer: the APK's model/runtime is QNN HTP **V81** (SM8750), while
  aurora is SM8650 (V75). The observed M9 JPEG never received the styled
  bitmap, so this stage fails on aurora; the module uses the M9 parametric
  filter instead.
* The module calls `libm9base` directly, so it misses the app's
  `libleicamodelembed` B12 reconciliation. See
  [12](12-moment-app-internals.md) section 10 for the recommended
  change.

## 7. Test steps (on device)

The user runs these; the module itself does not touch `/data/vendor/camera` or
system properties.

1. Build and install the module:

```sh
./gradlew :assembleRelease --offline
adb -s 1c4e4dec install -r build/outputs/apk/release/xiaomi-camera-mod-release.apk
```

2. Keep the LSPosed scope on `com.android.camera`, then open the module
   settings and enable **Leica Essential pipeline (real M3/M9)**. Leave
   **Render M3/M9 in the camera engine (HAL LUT slot)** **off** (the pipeline
   deliberately skips when the slot is on).

3. Force-stop Camera once so the setting is read:

```sh
adb -s 1c4e4dec shell am force-stop com.android.camera
adb -s 1c4e4dec logcat -c
adb -s 1c4e4dec logcat -s HCM-Essential HyperCameraMod
```

4. Open Camera, select **M3** in the Leica Essential picker, take one photo.
   Expected log sequence:

```
Essential candidate IMG_....jpg legend=2
service created
libm9base loaded
Monopan grain=false in ... ms
legend=2 <in> -> <out> bytes in ... ms
applied legend=2 to IMG_....jpg
```

   `Monopan grain=false` is expected without the optional libs.

5. Pull the file and verify true monochrome at the original resolution:

```sh
adb -s 1c4e4dec pull /sdcard/DCIM/Camera/IMG_....jpg /tmp/opencode/m3.jpg
python3 - <<'PY'
from PIL import Image
im = Image.open('/tmp/opencode/m3.jpg').convert('RGB')
px = list(im.getdata())
print(im.size, max(abs(r-g) for r,g,b in px), max(abs(g-b) for r,g,b in px))
PY
```

   Both maxima must be 0. The EXIF `Model` should still be the camera's
   original value; the module does not rewrite it.

6. Select **M9**, take one photo. Expect `legend=1` in the log and a colour
   result. This is the M9 parametric filter, not the full style-transfer
   chain.

7. Switch the setting off, force-stop Camera, take an M3 photo. There must be
   no `HCM-Essential` log, and the capture must behave exactly as it did before
   this change (the stock path, unchanged by the pipeline).

8. Stock-filter check: select a normal stock filter (e.g. Negative) with
   `legendMode` not set. The pipeline must log `no M3/M9 selection` or not run
   at all; the stock filter output must be unchanged.

9. Crash-isolation check: while a photo is being processed, kill only the
   module process:

```sh
adb -s 1c4e4dec shell am force-stop io.rezikham.hypercameramod
```

   Camera must stay alive, the shutter must remain usable, and the JPEG must
   remain the unprocessed original.

10. To test the optional Monopan path, rebuild with the extra libraries from
    section 6.4 and repeat step 4. Expect either a real grain result
    (`Monopan grain=true`) or a fast `false` and grayscale fallback; either
    way the camera must not crash.

## 8. Provenance and maintenance

* Source APK: `essential-moments (1).apk`, `com.martin.essentialmoments`
  `6.8.001960.0`, SHA-256 recorded in the session notes. Only the four files
  in section 6.3 are redistributed; they are the minimum needed for the
  parametric filter.
* The `.bin` hashes match `assets/legend18/provenance.json` in the APK:
  M3 `6e9217…052b`, M9 `60643c…5013`.
* If Camera is updated, `ModuleMain`'s existing version profiles and the
  `legendMode` observer are the only app-side dependencies. The native ABI is
  fixed by the JNI symbol names, so a different `libm9base.so` would need a
  new trampoline.
* Keep the pipeline off by default. It is the only path in the module that
  loads third-party native code.

## 9. Exact model requirements

### Required native libraries (bundled)

| Library | Source | Role |
|---|---|---|
| `libm9base.so` | Moment App APK `lib/arm64-v8a/` | Core parametric filter + Monopan spawn |
| `libc++_shared.so` | Moment App APK `lib/arm64-v8a/` | C++ runtime for `libm9base.so` |

### Required parameter files (bundled as assets)

| File | Source | SHA-256 (legend18 copy) | Size |
|---|---|---|---|
| `leica_filter_param_m3.bin` | APK `assets/leica_filter_param_m3.bin` (root) | `6e9217ee8c59872a3d700614681c30742df01d9bc9b44b7f2a170eeaab68052b` | 93,170 B |
| `leica_filter_param_m9_snapshot.bin` | APK `assets/legend18/leica_filter_param_m9_snapshot.bin` | `60643c2747a05e2b6c5266461a6c4a0b06d7236f15b4b8d61637015420125013` | 1,686,102 B |

**Note:** The capture path uses the APK root `m9_snapshot.bin` (`b2b7769a…`), which
differs in one LUT block and a build timestamp. For exact parity with the app's
capture output, replace the bundled file with the root copy.

### Optional native libraries (for Monopan grain)

| Library | Source | Size | Notes |
|---|---|---|---|
| `libmonopan_runner.so` | APK `lib/arm64-v8a/` | 9 KB | Spawn host |
| `libmialgo_monopan.so` | APK `lib/arm64-v8a/` | 18 MB | Monopan payload (QNN model) |
| `libQnnHtp.so`, `libQnnHtpV81.so`, `libQnnHtpV81Skel.so`, `libQnnSystem.so` | APK `lib/arm64-v8a/` | ~13 MB | QNN HTP V81 runtime (SM8750) |

**QNN version mismatch:** The APK ships QNN HTP V81 (SM8750). On aurora
(SM8650, HTP V75), the Monopan helper **may** fail to initialize the QNN
backend. The app's own artifact shows it works on this unit, but this is not
guaranteed across builds. The fallback (grayscale) is always safe.

### JNI symbols required from `libm9base.so`

All bound to `com.codex.leicaapp.LegendaryPostProcessor`:

| Symbol | Offset | Signature |
|---|---|---|
| `Java_com_codex_leicaapp_LegendaryPostProcessor_applyOriginalFilter` | 0xccf8 | `(Bitmap, String, float, float, float, float)` |
| `Java_com_codex_leicaapp_LegendaryPostProcessor_applyWarmOriginalFilter` | 0xd3e4 | `(Bitmap, String, float, float, float, float, float, float, float)` |
| `Java_com_codex_leicaapp_LegendaryPostProcessor_applyMonochrome` | 0xa994 | `(Bitmap, String, String)` |
| `Java_com_codex_leicaapp_LegendaryPostProcessor_applyStyle` | 0xc2a4 | `(Bitmap, String, String, String, int, int, int)` |

If `libleicamodelembed.so` is used instead (recommended), the same JNI symbols
are re-exported with B12 wrapper logic at offsets in section 2.2 of page 12.

## 10. Integration architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ Camera process (com.android.camera)                             │
│  ModuleMain.installLutFallbackHook()                            │
│    └─ Bitmap.compress hook → EssentialPipeline.handle()        │
│         └─ bindService(:essential) → send JPEG via pipe        │
└──────────────────────────┬──────────────────────────────────────┘
                           │ Binder IPC (ParcelFileDescriptor)
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ Module process (io.rezikham.hypercameramod:essential)           │
│  EssentialService.onBind() → Messenger → handler thread         │
│    onMessage(MSG_PROCESS):                                      │
│      1. validate caller UID = com.android.camera                │
│      2. read JPEG from input pipe                               │
│      3. loadNative(): System.loadLibrary("c++_shared")          │
│         System.loadLibrary("m9base")                            │
│      4. ensureAsset(.bin) → copy from APK assets to files dir   │
│      5. decode JPEG → ARGB_8888 mutable bitmap (sRGB)           │
│      6. LegendaryPostProcessor.applyOriginalFilter(bitmap, ...) │
│      7. if M3: try applyMonochrome() → fallback desaturate()    │
│      8. bitmap.compress(JPEG, 95) + copyExif()                  │
│      9. write result to output pipe → reply to camera           │
└─────────────────────────────────────────────────────────────────┘
```

### Key design decisions

| Decision | Rationale |
|---|---|
| Bound service (not thread) | Native crash isolation; binder death detection; no storage permission |
| ParcelFileDescriptor pipes | Zero-copy-ish; no world-readable files; works across UID boundary |
| Off-by-default (`essential_pipeline_enabled`) | Only path loading third-party native code; explicit opt-in |
| 3-min freshness on `lastLegend` | Prevents stale M3/M9 selection on subsequent normal captures |
| HAL LUT slot guard | Prevents double-processing (HAL + module) on same file |
| sRGB decode for both M3/M9 | Matches `MadridLegendary`; simpler than full `processStyled` DISPLAY_P3 path |
| `libleicamodelembed` not loaded | Module is minimal; B12 reconciliation omitted (see page 12 §10) |

## 11. Device-dependent tasks (for when phone connects)

| Task | Command / Action | Expected Outcome |
|---|---|---|
| Verify `libm9base.so` loads | `adb shell setprop camera.feature.essential_pipeline 1; adb logcat -s HCM-Essential` | `libm9base loaded` |
| Test M3 capture | Select M3 in Leica UI, take photo | `legend=2`, JPEG is true monochrome (R=G=B) |
| Test M9 capture | Select M9, take photo | `legend=1`, colour JPEG with M9 parametric look |
| Verify EXIF preservation | `exiftool` on result | Original EXIF APP1 copied, no Leica container added |
| Test Monopan (with libs) | Copy optional libs to jniLibs, rebuild, test M3 | `Monopan grain=true` or fast fallback; no crash |
| Test crash isolation | `am force-stop io.rezikham.hypercameramod` during processing | Camera stays alive; original JPEG kept |
| Test double-processing guard | Enable HAL LUT slot + Essential pipeline, take M3 photo | Pipeline logs skip; HAL slot renders |
| Measure latency | Log timestamps in `EssentialPipeline.handle()` | End-to-end < 5 s typical |
