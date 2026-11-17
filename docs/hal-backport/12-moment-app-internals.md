# 12 — Moment App internals

This page is a comprehensive static-analysis report of the standalone "Moment App" camera app (`com.martin.essentialmoments`, version 6.8.001960.0), built from the input APK
`essential-moments (1).apk` (SHA-256 `6585eeec28b0b1affbecbf050ceb83cc5294032cafa8cc3798bbe1316f2ee7fe`, `[observed]`).

**Scope:** static analysis on the host APK only. No `adb`, no device install, no runtime execution. Evidence markers follow `[observed]`, `[inferred]`, `[unknown]`.

This page replaces/rewrites any prior internals content. The previous version of this file analyzed `com.android.camera` (Xiaomi's stock camera); this document analyzes `com.martin.essentialmoments`, a *standalone* camera app that bundles its own Leica pipeline.

---

## 0. Input inventory

APK contents relevant to the Leica/Legendary pipeline (`[observed]` from `unzip -l`):

| Asset / native | Size | Role |
|---|---|---|
| `assets/leica_filter_param_m3.bin` | 93,170 B | M3 capture parameter table |
| `assets/leica_filter_param_m9_snapshot.bin` | 1,686,102 B | M9 capture parameter table |
| `assets/leica_filter_param_m9_preview.bin` | 1,686,102 B | M9 preview parameter table |
| `assets/legend18/leica_filter_param_m3.bin` | 93,170 B | M3 (MadridLegendary path) |
| `assets/legend18/leica_filter_param_m9_snapshot.bin` | 1,686,102 B | M9 (MadridLegendary path) |
| `assets/legend18/leica_filter_param_m10r_snapshot.bin` | 1,597,668 B | M10R (MadridLegendary path) |
| `assets/legend18/provenance.json` | 1,006 B | .bin provenance |
| `lib/arm64-v8a/libm9base.so` | 132,216 B | core filter JNI + Monopan helper spawn |
| `lib/arm64-v8a/libleicamodelembed.so` | 4,753,584 B | B12 wrapper around libm9base |
| `lib/arm64-v8a/libmadridlegend.so` | 5,736 B | thin JNI shim -> libm9base PLT slots |
| `lib/arm64-v8a/libm9b2.so` | 40,624 B | M9DualB2 variant, **not loaded** |
| `lib/arm64-v8a/libmonopan_runner.so` | 9,352 B | spawned Monopan helper host |
| `lib/arm64-v8a/libmialgo_monopan.so` | 18,312,840 B | Monopan helper payload (dlopen'd by runner) |
| `lib/arm64-v8a/libstyletrans_runner.so` | 10,168 B | style-transfer host |
| `lib/arm64-v8a/libperson_runner.so` | 9,816 B | human-segmentation host |
| `lib/arm64-v8a/libleicaquality.so` | 464,376 B | person-mask post stage |
| `lib/arm64-v8a/libb43tone.so` | 21,528 B | fixed 17³ tone LUT |

Key Java packages: `com.codex.leicaapp.*` (LegendaryPostProcessor, MadridBridge, MadridLegendary), `essentialmoments.*` (StyleSync, LegendContainer, M10rBin, SoftwareRaw), `com.android.camera.module.Camera2Module`.

---

## 1. Java/Kotlin Architecture

### 1.1 Main activity and entry points

The app reuses the Xiaomi `com.android.camera` framework classes (imported wholesale into the APK). The capture flow entry is:

```
com.android.camera.Camera (activity)
  └─ Camera2Module (mModuleIndex == 256 = "Legendary" module)
       ├─ updateParallelTaskData(t)                  // Camera2Module.java:3087
       │    ├─ t.b.f17731g = moduleIndex             // 256
       │    ├─ MadridBridge.snapshot(t, cameraManager)
       │    │    ├─ t.legendaryStyle  = component 256 value ("M3"/"M9"/"M10R")
       │    │    └─ t.legendaryPreviewResult = last CaptureResult
       │    └─ setupPhotoSaveInterceptors(t)         // installs p691t7.d
       └─ save worker
            └─ p691t7.d.a(t)                         // p585q7.d interceptor
                 ├─ MadridBridge.process(t)         // replaces Vh.e byte payload
                 └─ stock saver writes DCIM/IMG_*.jpg
```

`[observed]` (`Camera2Module.java:3087`, `MadridBridge.java`).

### 1.2 How M3/M9 selection flows from UI to native

| Store | Key | Values | Written by | Consumed by |
|---|---|---|---|---|
| Camera component 256 | `pref_legendary_mode_key` | `"M3"`, `"M9"`, `"M10R"` | `p617r2.C`, `p076c6.d`, `LeicaV43Ui` | `MadridBridge.snapshot` |
| Capture request | `com.xiaomi.sessionparams.legendMode` (Byte) | `1`=M9, `2`=M3, `3`=M10R | `T3.c.d()` (`p301ia.V` case 4) | OemExtensionSession |
| Shared prefs | `lmoment_v101` → `style` | `1`=M3, `4`=M10R | `StyleSync.publish`, `M10rFull` | `LegendContainer.style()` |
| Task field | `Sh.t.legendaryStyle` | `"M3"`/`"M9"`/`"M10R"` | `MadridBridge.snapshot` | `LegendaryPostProcessor.processStyled` |

`LegendaryPostProcessor.processStyled` decides M3 vs M9-family with one comparison `[observed]` (`LegendaryPostProcessor.java:180`):

```java
boolean zEquals = AbstractC2757c.VALUE_M3.equals(tVar.legendaryStyle);
// AbstractC2757c.VALUE_M3 = "M3", VALUE_M9 = "M9", VALUE_M10R = "M10R"
```

So `M10R` takes the M9 branch. `LegendaryPostProcessor.setMode()` only changes the static `useM3` flag (used by gallery re-render and `isM9()`), not normal capture `[observed]`.

### 1.3 MadridBridge class — UI to native glue

`MadridBridge` (`com.codex.leicaapp.MadridBridge`) has two static methods `[observed]`:

```java
public static void snapshot(t tVar, k cameraManager) {
    if (tVar.b.f17731g != 256) return;
    tVar.legendaryStyle  = C.getComponentValue(256);   // "M3"/"M9"/"M10R"
    tVar.legendaryPreviewResult = cameraManager.T().B(); // last CaptureResult
}

public static void process(t tVar) {
    int i10 = tVar.b.f17731g;   // moduleIndex
    if (i10 == 256) {
        e eVar = tVar.f17789a.f17685i;   // Vh.e byte payload
        byte[] bArrD = eVar.d();
        byte[] bArrProcess = LegendaryPostProcessor.process(bArrD, tVar);
        if (bArrProcess != bArrD) { H.i(eVar, f.a(bArrProcess)); c10.f17685i = eVarA2; }
    }
    // also handles 163/167/171 (PalettePostProcessor) and 167 watermark
}
```

`MadridBridge.snapshot` reads the user's Legendary selection into the capture task; `MadridBridge.process` runs on the save worker and delegates to `LegendaryPostProcessor.process`. Only moduleIndex 256 (Legendary) triggers Leica post-processing.

### 1.4 LegendaryPostProcessor JNI wrapper class

`com.codex.leicaapp.LegendaryPostProcessor` — final class, all-static. Static initializer `[observed]`:

```java
static {
    System.loadLibrary("leicamodelembed");   // loads libleicamodelembed.so, which dlopen's libm9base.so
    WB_TEMPERATURE = new CaptureRequest.Key<>("org.codeaurora.qcamera3.manualWB.color_temperature", Integer.class);
    WB_PARTIAL = new CaptureRequest.Key<>("org.codeaurora.qcamera3.manualWB.partial_mwb_mode", Integer.class);
    zoomRanges = new HashMap<>();
    renderQueue = new RenderQueue(6, 100663296L);   // 6 tickets, 96 MB
}
```

Declared JNI natives (all `private static native boolean`) `[observed]`:

| Java native | libm9base offset | Signature |
|---|---|---|
| `applyOriginalFilter` | 0xccf8 | `(Bitmap, String model, float lux, float cct, float zoom, float phase)` |
| `applyWarmOriginalFilter` | 0xd3e4 | `(Bitmap, String, float, float, float, float, float, float, float)` |
| `applyBoldOriginalFilter` | 0xd2c8 | `(Bitmap, String, float, float, float, float, float)` |
| `applyMonochrome` | 0xa994 | `(Bitmap, String runner, String cacheDir)` |
| `applyStyle` | 0xc2a4 | `(Bitmap, String runner, String cacheDir, String modelsDir, int orientation, int lux, int cct)` |
| `applyLut` | 0xa540 | `(Bitmap, String file, int block)` — unused |
| `applyAdaptiveLut` | 0xc3f8 | `(Bitmap, String file, float lux, float cct)` — unused |
| `applyAdaptiveLut`/`previewLut`/`previewShading`/`protectShadows` | 0xc49c/0xd4c8/0xe988 | unused or dead |
| `protectShadows` | 0xe988 | `(Bitmap, Bitmap, boolean)` — dead |

Native `renderQueue` is a `RenderQueue` whose `enter` is implemented natively in `libleicamodelembed.so` (0x487ea8).

### 1.5 Capture/save workflow — when post-processing happens

`LegendaryPostProcessor.process(byte[], obj)` → `processStyled(Crop35.apply(bArr), obj)` → `LegendContainer.finish(...)`.

`processStyled` runs on a **save worker thread**, never the main looper (returns source unchanged with `"Unexpected main-thread saver"` if it is). Steps `[observed]`:

1. Rejects main looper; bounds concurrency via `renderQueue.enter(bArr.length)` (ticket). Full queue → original saved.
2. Decodes JPEG → `ARGB_8888` bitmap (SRGB for M3, DISPLAY_P3 for M9).
3. `SoftwareRaw.develop(...)` RAW fallback; on failure shows toast "RAW is unavailable... processed from camera JPEG instead".
4. Reads lux/cct from `CaptureResult` (AecLux / AWB CCT keys, plus `MadridLegendary.number`).
5. **M3 branch** (`zEquals == true`):
   ```java
   z10 = applyOriginalFilter(bitmapApply, m3ModelPath, lux, cct, zoom, uptimeMillis % 60000)
       && applyMonochrome(bitmapApply, monopanRunner, cachePath);
   ```
   `applyMonochrome` hardcodes `lux=200`, `cct=5000` internally (see native disasm). `monopanRunner` = `nativeLibraryDir + "/libmonopan_runner.so"`.
6. **M9 branch** (`zEquals == false`):
   ```java
   applyStyleOriented(bitmapApply, orientation, lux, cct);   // style transfer
   applyWarmOriginalFilter(bitmapApply, M10rBin.path(m9ModelPath), lux, cct, zoom, phase,
                           denoise, M9_DENSITY_EV(-0.95), M9_NEUTRAL_WARMTH(0.3));
   ```
   `M10rBin.path` may substitute `leica_filter_param_m10r_snapshot.bin` when `persist.em.m10r` is enabled.
7. `B43Tone.apply`, `LeicaQuality.apply`, `LeicaDynamicRange.apply`, `OriginalWatermark.apply`.
8. Re-encode JPEG quality 95 → `JpegMetadata.copyExif(original, processed, ...)`.
9. `LegendContainer.finish(processed)` re-compresses at quality 92 and injects the Leica `Legend.MONOPAN` XMP container + `XIAOMI_CUSTOMIZE` segment (+ EXIF `legendmode` byte via `LegendTag`: style 1 → 2, else 1, failure → 3).

The JPEG bytes returned by `processStyled` replace the task payload **before** the stock saver writes `DCIM/IMG_*.jpg`. So post-processing happens **during save, after shutter**, not at preview.

---
## 2. Native Library Analysis

### 2.1 libm9base.so (132,216 bytes, SHA-256 `457a099b…`)

Core Leica filter library. All entry points resolved by JNI name (no `RegisterNatives`); mangled as `Java_com_codex_leicaapp_LegendaryPostProcessor_<method>`. Exported JNI symbols `[observed]` via `nm -D`:

| Offset | JNI symbol | Java signature | Contract |
|---|---|---|---|
| 0xa994 | `Java_com_codex_leicaapp_LegendaryPostProcessor_applyMonochrome` | `(Bitmap, String runner, String cacheDir)` | spawns Monopan helper; `false` on any failure |
| 0xccf8 | `Java_com_codex_leicaapp_LegendaryPostProcessor_applyOriginalFilter` | `(Bitmap, String model, float lux, float cct, float zoom, float phase)` | neutral parametric filter; `false` on bad bitmap/file |
| 0xd3e4 | `Java_com_codex_leicaapp_LegendaryPostProcessor_applyWarmOriginalFilter` | `(Bitmap, String, float, float, float, float, float, float, float)` | P3 linear-light grading variant (M9) |
| 0xd2c8 | `Java_com_codex_leicaapp_LegendaryPostProcessor_applyBoldOriginalFilter` | `(Bitmap, String, float, float, float, float, float)` | neutral branch, denoise clamped 0..1 (unused) |
| 0xc2a4 | `Java_com_codex_leicaapp_LegendaryPostProcessor_applyStyle` | `(Bitmap, String runner, String cacheDir, String modelsDir, int orientation, int lux, int cct)` | spawns style transfer; `false` on any failure |
| 0xa540 | `Java_com_codex_leicaapp_LegendaryPostProcessor_applyLut` | `(Bitmap, String file, int block)` | direct 17³ block apply; unused |
| 0xc3f8 | `Java_com_codex_leicaapp_LegendaryPostProcessor_applyAdaptiveLut` | `(Bitmap, String file, float lux, float cct)` | adaptive LUT apply; unused |
| 0xc49c | `Java_com_codex_leicaapp_LegendaryPostProcessor_previewLut` | `(String file, float lux, float cct)` | returns `byte[14739]` LUT; no Java declaration |
| 0xd4c8 | `Java_com_codex_leicaapp_LegendaryPostProcessor_previewShading` | `(String file, float lux, float cct, float zoom)` | returns `float[8]`; no Java declaration |
| 0xe988 | `Java_com_codex_leicaapp_LegendaryPostProcessor_protectShadows` | `(Bitmap, Bitmap, boolean)` | dead in this build |
| 0xc118 | `Java_com_codex_leicaapp_NativeModel_applyLut` | alias of applyLut | no `NativeModel` class in APK |
| 0xc1c0 | `Java_com_codex_leicaapp_NativeModel_applyMonochrome` | alias of applyMonochrome | same |
| 0xdbb8 | `Java_com_codex_leicaapp_SoftwareRaw_render` | `(Bitmap, byte[], int, int, int, float[], float[], int, int, float[], boolean, Bitmap, float[])` | software RAW develop |
| 0xe300 | `Java_com_codex_leicaapp_SoftwareRaw_fuse` | `(byte[][], float[][], int, int)` | RAW burst fusion |

Libc/NDK imports relevant to the pipeline `[observed]`: `posix_spawn`, `mkstemp`, `waitpid`, `unlink`, `close`, `fopen`/`fread`/`fwrite`/`fclose` (via `basic_filebuf`), `AndroidBitmap_getInfo/lockPixels/unlockPixels`, `memcpy`/`memset`/`strlen`, `__android_log_print`, `dl_iterate_phdr`, `pthread_create`, `malloc`/`free`.

#### Internal functions (disassembled, `[observed]`)

| Address | Symbol | Role |
|---|---|---|
| 0xaa78 | `applyAlgorithm(JNIEnv*, jobject, const char*, const char*, const char*, int, int)` | NV12 conversion → write temp input → `posix_spawn(monopan_runner)` → `waitpid` → read temp output |
| 0xcdd0 | `applyLeicaFilter` | common implementation of the three `*OriginalFilter` variants |
| 0xd620 | `adaptiveShading` | validates file, selects zoom band, blends 8-float records |
| 0xc620 | `adaptiveLut` | blends 17³ LUT blocks from the `common` table |
| 0xc9c4 | `previewModel` | caches a whole file per path (max 8 MB) |
| 0xeedc | `leicaRanges` | parses a nested range table |
| 0xf1c8 | `leicaWeights` | 2-tap linear weights for a value |
| 0xec00 | `sampleLut` | 17³ trilinear sample |
| 0x116e8 | `leicaShadingDelta` | vignette/noise/tone term (8 floats + phase) |

`applyAlgorithm` at 0xaa78 validates the bitmap (ARGB_8888, mutable, ≤ 36864×36864), builds two `mkstemp` temp paths, runs an ARGB→NV12-like conversion, writes input to `/monopan-in-XXXXXX`, calls `posix_spawn(path=runner, ..., argv)` at 0xbadc then `waitpid` at 0xbaf8, reads `/monopan-out-XXXXXX`. Returns `w23 & 1`.

### 2.2 libleicamodelembed.so (4,753,584 bytes, SHA-256 `7594e681…`)

B12/B8 wrapper. Every wrapper follows the same pattern `[observed]` (`applyOriginalFilter`, 0x4878a0 shown):

```c
if (env->ExceptionCheck()) return 0;
if (state->bitmap_ref) { env->DeleteLocalRef(state->bitmap_ref); state->bitmap_ref = 0; }
b8_color_free(state->color_ref); state->color_ref = 0;

lib = dlopen("libm9base.so", RTLD_NOW);
fn  = dlsym(lib, "Java_..._applyOriginalFilter");
t0 = now_ms();
ref = snapshot(lux, cct, env, bitmap, /*stage*/ 1, &color_ref);
t1 = now_ms();
ok  = fn(env, obj, bitmap, path, lux, cct, zoom, phase);   // real libm9base entry
t2 = now_ms();
if (!ok) { free(ref); return 0; }
result = finish(cct, t0, t1, t2, env, bitmap, ref, color_ref, 0, 1, /*stage*/ 3);
...
dlclose(lib);
return result;
```

Exported JNI symbols `[observed]`:

| Offset | Symbol | Role |
|---|---|---|
| 0x4878a0 | `…_applyOriginalFilter` | B12 wrapper around libm9base entry |
| 0x487b30 | `…_applyMonochrome` | B12 wrapper |
| 0x486a20 | `…_applyStyle` | B12 wrapper |
| 0x48745c | `…_applyWarmOriginalFilter` | B12 wrapper |
| 0x487ea8 | `…_RenderQueue_enter` | native render ticket |
| 0x48853c | `JpegMetadata_copyExif___3B_3BZ` | EXIF copy with B8 recovery |
| 0x486dd8 | `snapshot` | reference snapshot before the model |
| 0x486f94 | `finish` | B12 reconciliation after the model |

`LegendaryPostProcessor`'s static initializer loads `leicamodelembed`; since `leicamodelembed` `dlopen`s `libm9base`, JNI lookup finds the wrapper's symbols first. A process loading `libm9base.so` directly gets the *unwrapped* filter (no reference snapshot / B12 reconciliation). Pixel pipeline is the same; tone/colour reconciliation is missing `[inferred from bionic load order]`.

### 2.3 libmadridlegend.so (5,736 bytes)

Thin JNI shim — each export is a `b` (branch) to the corresponding `libm9base.so` PLT slot `[observed]`:

| Offset | Symbol | Target |
|---|---|---|
| 0x4748 | `Java_com_codex_leicaapp_MadridLegendary_filter` | `applyOriginalFilter` PLT slot |
| 0x474c | `Java_com_codex_leicaapp_MadridLegendary_grain` | `applyMonochrome` PLT slot |

Loaded directly via `System.load(str + "/libmadridlegend.so")` in `MadridLegendary.load()`. Used by the **MadridLegendary** capture path (a second, parallel pipeline that reads `assets/legend18/*.bin`). Note: `MadridLegendary.process` is defined in the APK but no caller was found in the decompiled sources `[inferred]` — it may be invoked reflectively or by a port module.

### 2.4 libm9b2.so (40,624 bytes)

"M9DualB2 / final-look" variant `[observed]`:

| Offset | Symbol |
|---|---|
| 0xc19c | `applyStyle` |
| 0xc938 | `applyWarmOriginalFilter` |

Not loaded by this build `[observed]`.

### 2.5 libmonopan_runner.so (9,352 B)

Spawned Monopan helper host. Exports `[observed]`:

| Offset | Symbol | Role |
|---|---|---|
| 0x4cd8 | `main` | helper entry: `argc==6`, `argv[1]` = .so to `dlopen` (libmialgo_monopan.so), `argv[2]` = input temp file, `argv[4]`/`argv[5]` = lux/cct via `atoi` |
| 0x5008 | `load_vendor` | `dlopen("libc++_shared.so", RTLD_NOW)` then `dlsym` helper loader |

`main` flow `[observed]`: validate `argc==6`; `atoi(argv[1])`→width, `atoi(argv[2])`→height; bounds-check `w*h ≤ 36864` and alignment; `malloc`/`calloc` buffers; `fopen(argv[3], "wb")` + `fread` input; `load_vendor(argv[0])`; `dlsym` the Monopan entry; process; write output. `.rodata` strings: `MonopanProc`, `MonopanInit`, `MonopanDeinit`, `libc++_shared.so`, `/libmialgo_monopan.so`.

### 2.6 Other libraries

| Library | Entry | Offset | Role in pipeline |
|---|---|---|---|
| `libmialgo_monopan.so` | (Monopan payload) | — | 18 MB; `dlopen`'d by monopan_runner; performs the monochrome/grain conversion |
| `libmialgo_styletrans.so` | — | — | style-transfer model backend |
| `libstyletrans_runner.so` | `main` | 0x4e10 | style-transfer host (loaded by `applyStyle`) |
| `libperson_runner.so` | `main` | 0x4da8 | human-segmentation host |
| `libleicaquality.so` | `LeicaQuality_finish` | 0x200b0 | person-mask post stage |
| `libb43tone.so` | `B43Tone_applyNative` | 0x80c8 | fixed 17³ tone LUT baked in (`kTone` at 0x5a8) |

### 2.7 Dependency/load order

```
LegendaryPostProcessor static init
  └─ System.loadLibrary("leicamodelembed")        // libleicamodelembed.so
        └─ dlopen("libm9base.so")                 // per-call, RTLD_NOW
              └─ dlsym(applyOriginalFilter|applyMonochrome|…)

MadridLegendary.load(nativeLibraryDir)
  └─ System.load("…/libmadridlegend.so")          // branches to libm9base PLT slots

applyMonochrome → applyAlgorithm
  └─ mkstemp(/monopan-in-XXXXXX) + mkstemp(/monopan-out-XXXXXX)
  └─ posix_spawn(libmonopan_runner.so, argv=[runner.so, in, out, lux, cct])
  └─ runner main → dlopen(libmialgo_monopan.so) → process → SIGSEGV possible here
```

## 3. M3 vs M9 Processing Pipeline

### 3.1 Where M3 vs M9 is selected

The decision point is `LegendaryPostProcessor.processStyled` line 180 `[observed]`:

```java
boolean zEquals = AbstractC2757c.VALUE_M3.equals(tVar.legendaryStyle);
```

`tVar.legendaryStyle` is populated by `MadridBridge.snapshot` from camera component 256 (`pref_legendary_mode_key`). UI writes `"M3"` / `"M9"` / `"M10R"`. `M10R` falls into the M9 branch (only `"M3"` is special-cased).

### 3.2 Step-by-step from shutter press to saved JPEG

**Capture request (preview/still):**
- `Camera2Module.updateParallelTaskData` → `MadridBridge.snapshot(t, cameraManager)` stores style + last `CaptureResult` into the task `t`.
- `OemExtensionSession.request(...)` builds the OEM still capture request; `LegendaryPostProcessor.copyCaptureStyle` copies `useM3` into `captureStyles` keyed by `CaptureRequest`; `copyCaptureStyle` also snapshots preview tuning.
- `LegendaryPostProcessor.buildRequest(builder)` sets fixed WB temperature 5000, AWB mode auto, color correction mode 1.

**Shutter → save worker (post-processing happens HERE, not at preview):**
- `MadridBridge.process(t)` → `LegendaryPostProcessor.process(bArrD, tVar)` → `processStyled(Crop35.apply(bArr), obj)`.
- `processStyled` (save worker, `RenderQueue` ticket):
  1. Decode JPEG to `ARGB_8888` bitmap (SRGB if M3, DISPLAY_P3 if M9).
  2. `SoftwareRaw.develop(captureResult, bitmap, !zEquals)` RAW fallback.
  3. Read lux/cct from `CaptureResult` metadata keys.
  4. **M3 branch** (`zEquals`):
     ```java
     z10 = applyOriginalFilter(bitmapApply, m3ModelPath, lux, cct, zoom, uptimeMillis % 60000)
         && applyMonochrome(bitmapApply, monopanRunner, cachePath);
     ```
     `m3ModelPath` = files-dir copy of `assets/leica_filter_param_m3.bin`; `monopanRunner` = `nativeLibraryDir/libmonopan_runner.so`; `cachePath` = cache dir. Native `applyMonochrome` hardcodes `lux=200`, `cct=5000` internally.
  5. **M9 branch** (`!zEquals`):
     ```java
     applyStyleOriented(bitmapApply, orientation, lux, cct);           // style transfer
     applyWarmOriginalFilter(bitmapApply, M10rBin.path(m9ModelPath),
                             lux, cct, zoom, phase,
                             denoise, M9_DENSITY_EV(-0.95), M9_NEUTRAL_WARMTH(0.3));
     ```
     `M10rBin.path` may substitute `leica_filter_param_m10r_snapshot.bin` when `persist.em.m10r` enabled.
  6. `B43Tone.apply`, `LeicaQuality.apply`, `LeicaDynamicRange.apply`, `OriginalWatermark.apply`.
  7. Re-encode JPEG Q95 → `JpegMetadata.copyExif(original, processed, ...)`.
  8. `LegendContainer.finish(processed)` re-encodes Q92 and injects Leica `Legend.MONOPAN` XMP container + `XIAOMI_CUSTOMIZE` segment + EXIF `legendmode` byte.
- Result bytes replace task payload; stock saver writes `DCIM/IMG_*.jpg`.

### 3.3 Monochrome vs color path divergence

| Stage | M3 | M9 / M10R |
|---|---|---|
| `applyOriginalFilter` | yes (`m3ModelPath`) | no (M9 uses style + warm filter) |
| `applyMonochrome` (Monopan) | **yes** — spawns helper | no |
| `applyStyleOriented` (style transfer) | no | **yes** |
| `applyWarmOriginalFilter` | no | **yes** (`m9ModelPath`, density EV -0.95, warmth 0.3) |
| `B43Tone` | yes | yes |
| `LeicaQuality` (person mask) | yes | yes |
| `LeicaDynamicRange` | yes | yes |

M3 is a **two-stage** pipeline: parametric filter then monochrome conversion via the spawned Monopan helper. M9 is a **style-transfer + warm-grade** pipeline. M10R shares the M9 branch, optionally substituting `m10r_snapshot.bin`.

### 3.4 What libmialgo_monopan.so does (the Monopan helper)

`libmialgo_monopan.so` (18 MB) is the Monopan payload. It is **not executed directly** by the app. `applyAlgorithm` (libm9base, 0xaa78) spawns `libmonopan_runner.so` via `posix_spawn`; the runner's `main` (0x4cd8) `dlopen`s `libmialgo_monopan.so` (passed as `argv[1]`) and invokes its Monopan entry. The runner reads the input temp file (`/monopan-in-XXXXXX`), processes (monochrome/grain), writes `/monopan-out-XXXXXX`, and exits. `applyAlgorithm` reads the output and returns success/failure to `applyMonochrome`.

### 3.5 Why the Monopan helper spawns and why it crashes (SIGSEGV)

- It spawns because `applyMonochrome` (0xa994) delegates to `applyAlgorithm` (0xaa78), which `posix_spawn`s `libmonopan_runner.so` to perform the monochrome conversion out-of-process (helper isolation, likely for crashes/memory limits).
- The crash `[observed from prior context]`: `posix_spawn → SIGSEGV`. The `SIGSEGV` occurs **inside the spawned helper process**, most likely during `libmialgo_monopan.so` initialization/processing after `dlopen`, not in the app process. The app's `applyMonochrome` receives the helper's non-zero exit via `waitpid` and returns `false`.
- Consequence: `z10 = false` → `"Original algorithm failed for M3"` → `processStyled` returns the **original unprocessed JPEG** (grayscale is NOT applied by this path; the M3 grayscale observed previously comes from the *MadridLegendary* parallel path's `ColorMatrix.setSaturation(0)` fallback when `grain()`/`applyMonochrome` fails).
- In the MadridLegendary path: `filter()` runs, then `grain()` = `applyMonochrome`; if it returns false, a Java `ColorMatrix` saturation-0 fallback is applied → exact grayscale, then the helper had already crashed.

### 3.6 Temp file naming pattern

From libm9base.so `.rodata` `[observed]`:
- `0x3790`: `/monopan-in-XXXXXX` (input, `mkstemp` template at `applyAlgorithm` 0xace4)
- `0x3610`: `/monopan-out-XXXXXX` (output, `mkstemp` template at 0xacf0)
- `0x3326`: `/libmialgo_monopan.so` (the .so the runner dlopen's)

Both temp files are `mkstemp`'d, written, `posix_spawn`'d, `waitpid`'d, then `unlink`'d on both success and failure paths (0xae18–0xae68).

## 4. Parameter Files (.bin format)

### 4.1 leica_filter_param_m3.bin structure

Header + table layout `[observed]` via `parse_legend18.py` (little-endian; not neural weights — half-float-free table of u16 ranges, f32 thresholds/records, u8 LUT entries):

```
0x0000  u16   magic/version = 6
0x0002  u16   scene_len[6]            (6 × 64-byte scene name block + table)
0x000e  u16   0x0400                  scene area start
0x0010  u16   0x1000                  LUT area start
0x0012  u16   17                      LUT edge (17³)
0x0014  u16   5                       number of zoom-band tables
0x0016  u16   zoom_len[5]             bytes per zoom band
0x0020  char  "aiscene-lux-cct\0"     tag
0x0120  char  "uint16_t\0"            element type tag
0x0128  ..    padding (0x20 bytes) to 0x0400
0x0400  scene blocks, 6 × (64-byte name + range table)
        5 zoom-band tables, each (u32 length + float threshold + table)
        padding to 0x1000
0x1000  17³ RGB LUT blocks, N = max_scene_index + 1, 0x3993 bytes each
        shading records, 8 × float32, 32 bytes each
EOF exactly after last record
```

Generic range table (used by scene and zoom tables) `[observed]`:

```
u16 count                        1..32
repeat count:
    u16 lo, u16 hi, u16 nsub
    repeat nsub:
        u16 lo, u16 hi, u16 value   // value = LUT block or shading record index
```

`adaptiveShading` requires `lo <= hi`, monotonically increasing table, `nsub >= 1`; `leicaRanges` rejects overruns.

### 4.2 Per-file map `[observed]`

| File | Size | tables_end | LUT blocks | shade_start | Records | Flags 0x360/0x362/0x366 |
|---|---|---|---|---|---|---|
| `m3` (root + legend18) | 93,170 (0x16bf2) | 0x6e2 | 6 (idx 0..5) | 0x16972 | 20 | `0/0/1` |
| `m9_snapshot` (root + legend18) | 1,686,102 (0x19ba56) | 0xb0e | 114 (0..113) | 0x19b376 | 55 | `0/1/1` |
| `m10r_snapshot` (legend18) | 1,597,668 (0x1860e4) | 0xac6 | 108 (0..107) | 0x185a04 | 55 | `1/1/1` |
| `m9_preview` (root) | 1,686,102 | 0xb0e | 114 | 0x19b376 | 55 | `1/0/1` |

`0x360` gates the extra highlight/shadow stage; `0x362` requires `adaptiveLut` (LUT present); `0x366` enables the shading delta. M3 has **no LUT flag** (6 blocks unused); M9/M10R have both. Record counts match `assets/legend18/provenance.json` (20/55/55).

### 4.3 Scene and zoom tables

Every file has six scenes (incl. the typo `protrait`): `common, protrait, night, plants, food, sunrise_sunset`.

- M3: one record per scene, `[0,999] lux → [1,10000] CCT → one index` (indices 0..5).
- M9/M10R: per scene, 6 lux/CCT bands mapping to 18+ indices (e.g. M9 `common`: `[0,220]→{(1,5500,0),(6000,10000,1)}`, `[240,300]→{…indices 2..5}`, …).

5 special tables (zoom-band), each `len=54` for M3 / `len=102` for M9, with float thresholds `{0.6, 1.0, 3.0, 4.3, 10.0}` mapping lux ranges to shading-record indices (0..19 for M3).

### 4.4 leica_filter_param_m9_snapshot.bin structure

Same container as M3 but 114 LUT blocks, 55 shading records, richer per-scene tables. M9 preview (`m9_preview.bin`) differs only in flags (`1/0/1` vs `0/1/1`). Root vs legend18 `m9_snapshot.bin` differ in one 17³ LUT block and a build-timestamp string `[observed from prior analysis]`.

### 4.5 How these are loaded and consumed by native code

`LegendaryPostProcessor.ensureModels()` (lazy, first render) `[observed]`:
- extracts `leica_filter_param_m3.bin` and `leica_filter_param_m9_snapshot.bin` from **`assets/` root** (not `assets/legend18/`),
- copies the `leica-v4` tree to `files/leica-v4`, writes `.ready`,
- caches `nativeLibraryDir` paths for `libstyletrans_runner.so` and `libmonopan_runner.so`.

`applyOriginalFilter(bitmap, m3ModelPath, lux, cct, zoom, phase)` passes the copied `.bin` path to native `adaptiveLut`/`adaptiveShading`, which select the LUT block and shading record per scene/lux/CCT/zoom. `applyMonochrome(bitmap, monopanRunner, cachePath)` ignores the `.bin` and spawns the helper.

`MadridLegendary.process(Context, byte[], style, captureResult)` reads from **`assets/legend18/<file>`** (a separate copy), decodes, calls native `filter`/`grain` (0x4748/0x474c → libm9base PLT), with lux/cct from `CaptureResult` and zoom clamped to [0.1, 200], lux to [0,1000], cct to [1500,15000].

### 4.6 provenance.json contents

`assets/legend18/provenance.json` `[observed]`:

```json
{
  "leica_filter_param_m3.bin": {
    "source_sha256": "d7586c92c5317123314ce23f5c333530ebbae6f10880989160dddb1cbd85af97",
    "apk_sha256": "6e9217ee8c59872a3d700614681c30742df01d9bc9b44b7f2a170eeaab68052b",
    "shading_records": 20,
    "neutral_gain_removed": 1.0,
    "original_bytes": 93330,
    "apk_bytes": 93170
  },
  "leica_filter_param_m9_snapshot.bin": {
    "source_sha256": "188f07de45a625333a18b657747c3ff9cda7e507e4418b08860b0d97bda1e5ac",
    "apk_sha256": "60643c2747a05e2b6c5266461a6c4a0b06d7236f15b4b8d61637015420125013",
    "shading_records": 55,
    "neutral_gain_removed": 1.0,
    "original_bytes": 1686542,
    "apk_bytes": 1686102
  },
  "leica_filter_param_m10r_snapshot.bin": {
    "source_sha256": "7f6ab019e7d9332a8aeea760d7b78e31dfe52a3a5f5ed1d1cf43875cf8f7957d",
    "apk_sha256": "9713e4f6310b27b4e2db9d9c82c327ab7dc4aadbf49ffc805427e14ac80981c4",
    "shading_records": 55,
    "neutral_gain_removed": 1.0,
    "original_bytes": 1598108,
    "apk_bytes": 1597668
  }
}
```

Reveals: `neutral_gain_removed` (neutral tone gain stripped from distributed bins), `shading_records` counts matching the parsed tables, and that APK bytes < original bytes (160/440/440 bytes trimmed — the build-timestamp string and one LUT block). `assets/leica-v43-provenance.json` (158 KB) is a watermark asset inventory unrelated to filter tuning.

## 5. Crash Analysis (Monopan helper)

### 5.1 Exact posix_spawn arguments

In `applyAlgorithm` (libm9base.so, 0xaa78), at 0xbad4–0xbadc `[observed]`:

```
x0 = path  (= argv[0] of runner = libmonopan_runner.so path, built in x25)
x1 = argv  (stack-built argv array at sp+0x384)
x2 = file_actions = NULL   (xzr)
x3 = attrp     = NULL   (xzr)
call posix_spawn@plt (0x1c880)
```

`argv` layout `[inferred from runner main at 0x4cd8]`:
- `argv[0]` = runner path itself (libmonopan_runner.so)
- `argv[1]` = width (string)  — actually `atoi(argv[1])`
- `argv[2]` = height (string) — `atoi(argv[2])`
- `argv[3]` = input temp file path (`/monopan-in-XXXXXX`)
- `argv[4]` = lux (string) → `atoi`
- `argv[5]` = cct (string) → `atoi`

`applyMonochrome` calls `applyAlgorithm` with hardcoded `lux=200`, `cct=5000` (0xaa18 `w6=0xc8=200`, 0xaa1c `w7=0x1388=5000`), ignoring the Java lux/cct args.

After spawn: `waitpid(pid, &status, 0)` at 0xbaf8; on helper exit, both temp files are `unlink`'d (0xae18–0xae68). Helper exit status → `applyAlgorithm` returns `w23 & 1` → `applyMonochrome` returns boolean.

### 5.2 What the helper executable is

The helper is **not** `libmialgo_monopan.so` directly executed. It is `libmonopan_runner.so` (9,352 B), a small ELF host whose `main` (0x4cd8):
1. Validates `argc == 6`.
2. `atoi(argv[1])` → width, `atoi(argv[2])` → height; bounds `w*h ≤ 36864`.
3. Allocates buffers, `fopen`s the input temp file (`argv[3]`), `fread`s it.
4. `load_vendor(argv[0])` → `dlopen("libc++_shared.so", RTLD_NOW)` + `dlsym`.
5. `dlopen`s `libmialgo_monopan.so` (the path hardcoded at `.rodata` 0x3326, passed as `argv[1]`? Actually `load_vendor(argv[0])` and dlsym by name), resolves the Monopan entry, processes the frame, writes `/monopan-out-XXXXXX`, exits.

`libmialgo_monopan.so` (18 MB) is the actual monochrome/grain payload, loaded out-of-process by the runner.

### 5.3 Temp file naming pattern `[observed]`

From libm9base.so `.rodata`:
- `0x3790`: `/monopan-in-XXXXXX`  — input template, `mkstemp` at 0xace4
- `0x3610`: `/monopan-out-XXXXXX` — output template, `mkstemp` at 0xacf0
- `0x3326`: `/libmialgo_monopan.so` — payload the runner dlopen's

Both files are created in `cachePath` (app cache dir), `mkstemp`'d, written/read, then `unlink`'d on every exit path (success and failure).

### 5.4 Why SIGSEGV occurs

`applyMonochrome` → `applyAlgorithm` → `posix_spawn(libmonopan_runner.so)`. The spawned helper process `dlopen`s `libmialgo_monopan.so` and invokes its Monopan entry. The `SIGSEGV` occurs **inside the helper process** during that load/processing (missing vendor symbols, QCNN/Htp dependency mismatch, or a null deref in the 18 MB payload). The app process itself does not segfault; `waitpid` reaps the dead helper, reports non-zero status, and `applyAlgorithm` returns 0.

Root cause `[inferred]`: `libmialgo_monopan.so` expects a vendor/runtime environment (QNN DSP libs, `libc++_shared`, specific HAL symbols) not present or incompatible when loaded from the runner in this build, so its initializer/entry dereferences a null pointer → SIGSEGV.

### 5.5 Whether the JPEG is saved before or after the crash

The crash is in a **separate process**, so the app process survives. Timeline `[observed/inferred]`:

1. Shutter → save worker → `processStyled`.
2. M3 branch: `applyOriginalFilter` runs (succeeds), then `applyMonochrome` spawns helper → helper SIGSEGVs → `waitpid` reaps → `applyMonochrome` returns `false`.
3. `z10 = false` → `"Original algorithm failed for M3"` log → `processStyled` returns the **original unprocessed JPEG bytes** (no monochrome conversion).
4. Stock saver writes `DCIM/IMG_*.jpg` from those bytes.

So the JPEG **is saved**, but without Monopan processing (original, not grayscale, in the LegendaryPostProcessor path). The *exact grayscale* reported previously comes from the **parallel MadridLegendary path**: when its `grain()` (= `applyMonochrome`) fails, it applies a Java `ColorMatrix.setSaturation(0)` fallback → exact grayscale JPEG. Both paths share the same root cause (helper crash), but surface it differently.

### 5.6 Crash summary

| Item | Detail |
|---|---|
| Spawn API | `posix_spawn` (libm9base, imported from libc) |
| Spawn offset | 0xbadc in `applyAlgorithm` (0xaa78) |
| Helper binary | `libmonopan_runner.so` (host), `dlopen`s `libmialgo_monopan.so` (payload) |
| Temp input | `/monopan-in-XXXXXX` (mkstemp, cache dir) |
| Temp output | `/monopan-out-XXXXXX` (mkstemp, cache dir) |
| Hardcoded params | lux=200, cct=5000 (native, overrides Java args) |
| Crash location | helper process, `libmialgo_monopan.so` init/entry |
| Crash signal | SIGSEGV (reaped via `waitpid`) |
| App impact | `applyMonochrome` returns false; original JPEG saved |
| Grayscale source | MadridLegendary `ColorMatrix.setSaturation(0)` fallback |

## 6. Preview vs Capture

### 6.1 Is M3/M9 applied in preview or only capture?

**Only capture (save-time post-processing).** Preview does **not** apply the M3/M9 filter pipeline `[observed]`:

- `LegendaryPostProcessor.previewShader(String, int)` is a dead stub returning its input unchanged (0x793).
- `LegendaryPostProcessor.bindPreview(int, int, int, Rect)` is an empty no-op (0x797).
- `OemExtensionSession` (preview side) uses `SoftwareRaw` → `OemExtensionSession.open` → `LookStage.wrap`, which renders an **approximate** M3/M10R preview through the OEM extension session (Leica tone curve, not the full `.bin` LUT pipeline).
- `LegendaryPostProcessor.isM9()` (driven by `useM3`) gates preview exposure compensation in `OemExtensionSession.request` — only affects EV, not the look.

### 6.2 How the preview pipeline differs from capture

| Aspect | Preview | Capture (save) |
|---|---|---|
| Filter applied | Approximate Leica look via OEM extension / LookStage | Full `processStyled`: filter + monochrome/style + tone |
| `.bin` consumption | Not used (preview shader stubs dead) | `m3.bin` / `m9_snapshot.bin` consumed by native |
| Monopan helper | Not spawned | Spawned for M3 (`applyMonochrome`) |
| Thread | Camera preview thread | Save worker (`RenderQueue` ticket) |
| Output | Display surface | JPEG bytes → DCIM/IMG_*.jpg |
| JNI path | `SoftwareRaw_render`/`SoftwareRaw_fuse` (0xdbb8/0xe300) | `applyOriginalFilter`/`applyMonochrome`/`applyStyle` (0xccf8/0xa994/0xc2a4) |

The capture path is the authoritative M3/M9 rendering; preview is a best-effort approximation.

---

## 7. Recommendations for integrating into HyperCameraMod

1. **Reuse the app's load order.** Load `libleicamodelembed.so` (not `libm9base.so` directly) so the B12 wrapper + `libm9base` dlopen chain reproduces the app's tone/colour reconciliation byte-for-byte.

2. **Monopan helper crash is the blocker for M3.** `applyMonochrome` spawns `libmonopan_runner.so` → `dlopen(libmialgo_monopan.so)` → SIGSEGV. Options:
   - Extract `libmialgo_monopan.so` and its QNN dependencies (`libQnnHtp.so`, `libQnnHtpV81.so`, `libQnnHtpV81Skel.so`, `libQnnSystem.so`) from the APK assets/`lib/` and provide them to the helper's `LD_LIBRARY_PATH`.
   - Replace the helper call with an in-process monochrome conversion (e.g. `ColorMatrix.setSaturation(0)` as the MadridLegendary fallback already does) to avoid the out-of-process crash entirely.
   - Prefer the MadridLegendary path (`libmadridlegend.so` → libm9base PLT) which already degrades gracefully to grayscale on helper failure.

3. **Parameter files.** The capture path reads `assets/leica_filter_param_m3.bin` / `m9_snapshot.bin` from **assets root**; MadridLegendary reads `assets/legend18/`. They differ (one LUT block + timestamp). For a port, bundle both sets and match the consumer to the path.

4. **Style (M3/M9/M10R) selection.** Mirror `MadridBridge`/`LegendaryPostProcessor`: component 256 value → `legendaryStyle`; `"M3"` → monochrome branch; anything else → M9 style-transfer + warm-grade branch.

5. **EXIF/container.** `LegendContainer.finish` injects the Leica `Legend.MONOPAN` XMP container and `XIAOMI_CUSTOMIZE` segment; `LegendTag` maps style→EXIF `legendmode` byte (1→2, else 1, failure→3). Reproduce this for Leica ecosystem compatibility.

6. **Threading.** Post-processing runs on a save worker, never the main looper; bound concurrency with a `RenderQueue(6, 96MB)` equivalent to avoid blocking shutter.

7. **Native symbols to export/wrap** (if reimplementing): `applyOriginalFilter` (0xccf8), `applyWarmOriginalFilter` (0xd3e4), `applyMonochrome` (0xa994), `applyStyle` (0xc2a4), `SoftwareRaw_render` (0xdbb8), `SoftwareRaw_fuse` (0xe300) — all in `libm9base.so`, wrapped by `libleicamodelembed.so`.

---

## 8. Evidence summary

All offsets, signatures, and call chains above were obtained by static analysis of `essential-moments (1).apk`: jadx 1.5.6 decompilation of `classes.dex`…`classes13.dex` (sources at `/tmp/opencode/em_src/sources`), `nm -D`/`objdump` of the extracted `lib/arm64-v8a` `.so` files, and `parse_legend18.py` parsing of the `.bin` assets. Markers: `[observed]` = directly verified in the APK; `[inferred]` = deduced from disassembly/control flow; `[unknown]` = not resolved by static analysis.

Commit scope: only `docs/hal-backport/12-moment-app-internals.md`. No `adb`, no device install, no other files touched.