# 25 — M3/M9 parity on the 14 Ultra (in-process port)

Status: working on `1c4e4dec` with module `1.1.0-355` to `1.1.0-357` (test builds).

## Why the bound service is no longer used

The camera process cannot bind `io.martin.hypercameramod.EssentialService` on
Android 16: `ActivityManager` resolves the component for the camera UID and
answers `not found`, because `com.android.camera` has no package-visibility
query for `io.rezikham.hypercameramod`. The module therefore runs the same
native stages in the camera save path, exactly where the 17 Ultra app runs
them. The Monopan helper still runs in a child process, so the least safe
native code stays out of process.

Native libraries are loaded from the module APK's extracted
`nativeLibraryDir` (the same mechanism the Original Portrait renderer uses):

```text
libc++_shared.so
libm9base.so
libleicamodelembed.so   (B12 wrapper, its DT_NEEDED libm9base is found)
libb43tone.so
libmonopan_runner.so
libmialgo_monopan.so
```

`libleicamodelembed.so` in `jniLibs` is patched with
`patchelf --set-rpath '$ORIGIN'` so its `DT_NEEDED libm9base.so` resolves in
the module's class-loader linker namespace. Without that RUNPATH the wrapper
loads but its `dlopen("libm9base.so")` fails (`not found in namespace clns-13`).

## Capture stages

The 17 Ultra app decision point is `LegendaryPostProcessor.processStyled`:
`"M3"` takes the monochrome branch, everything else takes the M9 branch.

| Stage | 17 Ultra | 14 Ultra port |
|---|---|---|
| M3 parameter filter | `applyOriginalFilter(m3.bin)` | same, bundled `assets/essential/leica_filter_param_m3.bin` |
| M3 grain | Monopan helper | yes, with bundled OpenCL ICD + shim |
| M9 colour grade | `applyWarmOriginalFilter(m9.bin, denoise, -0.95 EV, 0.3)` | same |
| M9 tone | `B43Tone.apply(bitmap, false)` | same, `libb43tone.so` |
| B12 reconciliation | `libleicamodelembed` wrapper | same wrapper, RUNPATH patched |
| M9 style transfer | `applyStyle` (QNN HTP V81) | not available on SM8650/HTP V75 |
| Person correction | `LeicaQuality` | not ported |
| M3 film tone | `FilmInput` GLES stage | not ported |
| EXIF | copy + `legendmode` | copy APP1 + append IFD0 tag |

The M9 capture parameter file is the **root** asset
`assets/leica_filter_param_m9_snapshot.bin` (SHA-256
`b2b7769ad97ff11205ec052f910153066c43763b7ddc2860bc3a57972e52d9eb`), not
`assets/legend18/` (`60643c…`). This is the file the app's capture path uses.

Scene values are estimated because the module post-processes after the
camera's `CaptureResult` is gone:

* lux from the exposure triangle (`ISO`, exposure time, f-number) and the
  standard incident-light constant;
* CCT from the app's `SceneWhiteBalance.jpegTarget` neutral-pixel estimator
  (4800–5500 K);
* denoise from `FilmTone.denoise(ISO)` = `smoothstep(400, 2400, ISO)`.

## EXIF

`ExifUtils` no longer rewrites IFD0 in place. The 14 Ultra EXIF is
big-endian; an in-place rewrite shifted every following byte by 12 and
corrupted MakerNotes/IFD1 (ExifTool reported `Bad format (39321)` for IFD0
entry 0). The fix appends a second IFD0 with the new tag and points the TIFF
header at it. Original data offsets, MakerNotes and the thumbnail IFD stay
valid.

Verified output:

```text
Exif Byte Order : Big-endian (Motorola, MM)
IFD0 Camera Model Name : Xiaomi 14 Ultra
IFD0 Xiaomi Model      : Xiaomi 14 Ultra
IFD0 Exif 0x88b0       : 1   (M9) / 2 (M3)
ExifIFD ISO            : 2500
IFD1 Camera Model Name : Xiaomi 14 Ultra
```

## Evidence (2026-11-24)

M9, `IMG_20261124_130529.jpg`:

```text
Moment App using stored Essential style legend=1
Moment App candidate IMG_20261124_130529.jpg legend=1
HCM-Essential: libleicamodelembed loaded (B12 wrapper)
HCM-Essential: M9 scene lux=10.0 cct=4800.0 iso=2500.0 denoise=1.0 backend=B12
LeicaM9M3B12: B12 look=M9_CLEAR_DENSITY_B12 applied=1 ...
```

M3, `IMG_20261124_132910.jpg` (Monopan grain enabled):

```text
Moment App using stored Essential style legend=2
Moment App candidate IMG_20261124_132910.jpg legend=2
HCM-Essential: libc++_shared / libm9base / libleicamodelembed loaded
HCM-Essential: M3 scene lux=10.0 cct=4800.0 iso=2500.0 denoise=1.0 backend=B12
HCM-Essential: Monopan grain=true in 959 ms
Moment App applied legend=2 IMG_20261124_132910.jpg
```

The earlier fallback capture `IMG_20261124_130603.jpg` logged
`Monopan grain=false` and used the app's saturation-0 path.

Pixel checks:

```text
M9: 3072x4096, colour, max |r-g| 185, max |g-b| 155
M3 with Monopan: 3072x4096, max |r-g| 0, max |g-b| 0, wall stddev 11.7
M3 fallback:     3072x4096, max |r-g| 0, max |g-b| 0, wall stddev 6.2
```

## Monopan OpenCL shim

`libmialgo_monopan.so` asks for `/system/vendor/lib64/libOpenCL.so`, but the
runner's default linker namespace cannot read `/vendor/lib64`. The runner is
launched with the module's native directory on `LD_LIBRARY_PATH`, so:

* `libhcm_opencl_shim.so` is added as a `DT_NEEDED` of
  `libmonopan_runner.so` and interposes `dlopen` / `android_dlopen_ext`.
* It redirects the OpenCL request to `<nativeLibraryDir>/libOpenCL.so`,
  writes a one-line `.icd` file under the camera cache, and sets
  `OPENCL_VENDOR_PATH` before the Khronos loader runs.
* The bundled `libOpenCL_adreno.so` is the Qualcomm ICD. It loads
  `libCB.so` / `libgsl.so` through `android_load_sphal_library`, so the GPU
  driver still comes from the vendor namespace.
* The loader cannot write `/data/vendor/camera/mialgo_monopan_cl.cache`
  (`vendor_camera_data_file` is denied to the camera); it recompiles the
  kernel in memory and the grain still renders.

## Remaining gaps

* M9 style transfer: the shipped model is QNN HTP V81 (SM8750); the 14 Ultra
  is HTP V75. The B12 warm grade runs without the style model.
* `LeicaQuality` person correction, `FilmInput` tone, `LeicaDynamicRange`
  and the Leica container/watermark are not ported.
* No matched 17 Ultra capture is available on this setup, so the look is
  compared against the app's own stage order and local captures only.
