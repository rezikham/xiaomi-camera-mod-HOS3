# 10 — Essential Moments: the real M3/M9 pipeline

This page maps the genuine Leica pipeline in **Essential Moments**
(`com.martin.essentialmoments`, version `6.8.001960.0`, 150 MB APK) and documents
the optional module path that runs it on saved JPEGs.

The short version:

* The app is a repackaged Xiaomi Camera (`com.android.camera`) with extra
  classes under `com.codex.leicaapp` and `essentialmoments.*`.
* The real capture chain is `LegendaryPostProcessor.processStyled`; the simple
  two-call core is `MadridLegendary.filter/grain`.
* M3 and M9 use the same parametric filter (`libm9base.so` + a legend18 `.bin`)
  and differ in what runs around it. M3 adds Monopan grain; M9 adds the
  style-transfer model, warm grading, person correction, tone and gainmap.
* The Monopan helper is spawned by `libm9base.so` with `posix_spawn`. It is the
  stage that crashes on aurora. The module runs all native work in a private
  process and treats Monopan as optional, falling back to the app's own
  grayscale path.
* The module path is **off by default**, is **additional**, and never replaces
  the HAL LUT slot, the stock filters, or the existing LUT fallback.

## 1. Where the code is

| Layer | Class / file | Role |
| --- | --- | --- |
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

### 3.5 How the legend18 `.bin` files are used

The `.bin` files are **not** neural weights. `applyLeicaFilter` reads the whole
file into a `std::vector<uint8_t>`, then:

* reads a fixed header (`aiscene-lux-cct` at offset 0x20);
* runs `adaptiveShading(data, lux, cct, zoom, out)` — the vignetting/shading
  model, with records keyed by lux and CCT;
* checks flags at offsets `0x362` and `0x366`;
* when set, runs `adaptiveLut(data, lux, cct, out)` — the colour/mono LUT;
* processes the bitmap in three worker threads, in horizontal bands, via
  `AndroidBitmap_lockPixels`.

`provenance.json` in the app records 20 shading records for M3 and 55 for M9.
Sizes: M3 93,170 bytes, M9 1,686,102 bytes, M10R 1,597,668 bytes. The M3 file
is small because it is a monochrome parameter set; M9 has the full colour
shading table.

Unknown metadata defaults, taken from `MadridLegendary.process`:

| Parameter | Range | Default |
| --- | --- | --- |
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

### Why it SIGSEGVs from a foreign app process

The failure is not a bad image, and it is not unconditional: the app produced a
Monopan-marked M3 JPEG on this phone (XMP `Legend.MONOPAN`, 19:23:51), so the
spawn can succeed. The known crash happens **after** that JPEG is saved, which
points to a second invocation (thumbnail/gallery preview) or a frame-dependent
helper fault rather than the first capture render. The static evidence narrows
the candidates to:

* The runner loads `libc++_shared.so` and `libmialgo_monopan.so` through the
  **`sphal` namespace**. `/data/app/.../lib/arm64` is not normally a permitted
  path for `sphal` on Android 16; when `android_dlopen_ext` returns null the
  runner does not null-check every subsequent symbol.
* `libmialgo_monopan.so` has only `liblog/libm/libc++_shared/libdl/libc` in
  `DT_NEEDED`, yet the APK ships `libQnnHtp.so`, `libQnnSystem.so` and
  `libQnnHtpV81Stub.so` next to it. The QNN backend is opened late by name.
  App namespace rules ignore `LD_LIBRARY_PATH` for `dlopen`, so a lookup can
  fail from the APK directory, leaving a null function pointer or model handle.
* The spawn sets only `LD_LIBRARY_PATH`, no `cwd`, no file actions, no
  attributes. It inherits the camera app's `/` cwd and its SELinux domain.
* Monopan is a model with global init/deinit. Two concurrent renders, or a
  render during teardown, can race; the app has a `RenderQueue` but the helper
  itself is a separate process with no supervision.

This diagnosis is inferred from the runner and helper ELF strings plus
Android's namespace rules; no tombstone for the helper was present under
`/data/tombstones` or the app's xcrash directory when this page was written.
The saved JPEG observed on aurora is consistent with the crash happening after
storage, not during it.

The same `applyAlgorithm` mechanism is used by `applyStyle` for M9 with
`libstyletrans_runner.so` + `libmialgo_styletrans.so`, so the M9 style-transfer
stage is exposed to the same class of failure.

## 5. Preview vs capture

The capture path above is the only path that produces the final look. The
preview path is different:

* `LegendaryPostProcessor.previewShader()` and `bindPreview()` are stubs in
  this build (`previewShader` returns its input, `bindPreview` is empty).
* `OemExtensionSession` (OEM camera extension) and `SoftwareRaw` maintain the
  preview session, exposure and white balance; `rememberPreview` stores the
  last `CaptureResult` for capture tuning.
* `LookStage.wrap` maps the extension output surfaces, but the visible preview
  look comes from the camera's normal preview pipeline.
* `LegendaryPostProcessor.previewLut` / `previewShading` exist in
  `libm9base.so` but are not used by the capture chain in this build.

So M3/M9 is effectively a **post-capture** look. The module follows the same
model: preview is untouched, the saved JPEG is processed.

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
* `applyMonochrome` `posix_spawn`s a helper that is known to crash. It runs as
  a child of `:essential`, not of the camera.
* If `:essential` dies, the binder connection drops, the client's latch is
  released, and the original JPEG stays. The camera session is unaffected.
* No storage permission is needed: the JPEG travels through pipes, not through
  `/sdcard`.

The service is exported because the camera process has a different UID. It
validates `msg.sendingUid`; requests from any other UID are rejected.

### 6.3 What is bundled

| File | Size | Purpose |
| --- | --- | --- |
| `lib/arm64-v8a/libm9base.so` | 132 KB | Parametric filter + spawn logic. |
| `lib/arm64-v8a/libc++_shared.so` | 1.26 MB | `DT_NEEDED` of `libm9base.so`. |
| `assets/essential/leica_filter_param_m3.bin` | 93 KB | M3 shading/LUT parameters. |
| `assets/essential/leica_filter_param_m9_snapshot.bin` | 1.69 MB | M9 shading/LUT parameters. |

Release APK: **2.47 MB** (was 1.36 MB). The 18 MB Monopan helper, the 13 MB
style-transfer helper, the 4.7 MB model embed and the QNN runtime are **not**
bundled.

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

This is intentionally not the default: it adds about 22 MB and the helper is
the crash-prone stage. When it returns false or dies, the fallback is the same
saturation-0 result the app itself uses when grain is unavailable.

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

* Monopan grain: correct-by-design only when the optional helper and QNN
  libraries are added; the app can produce it, but it is the crash-prone stage.
  Without them M3 uses the app's own grayscale fallback.
* M9 style-transfer: same spawn mechanism and same missing model/runtime
  dependencies; the module uses the M9 parametric filter instead.

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
