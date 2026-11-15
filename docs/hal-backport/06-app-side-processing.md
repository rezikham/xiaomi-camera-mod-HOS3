# Can HyperCameraMod do the processing in the app process?

Short answer: **not for the real M3/M9 HAL path.** HyperCameraMod is an
LSPosed module that runs inside `com.android.camera`; the Legendary processing
is a vendor camera-provider pipeline. Putting the vendor `.so` files into the
module APK does not move that processing into the app.

## Why the vendor libraries cannot simply be loaded

The Nezha/Madrid plugins are not standalone image filters:

* they are linked against `libcom.xiaomi.metadatautils`, `libmetatagwrapper`,
  `libcom.xiaomi.cam.miexifmgr`, `libcameraopt_stub`, `com.xiaomi.camdfx`, and
  `libcamlog`;
* they consume `MiMetadata`, `MiaParams`, `CreateInfo`, `MiaNodeInterface`,
  gralloc buffers, and HAL callbacks, not a plain `Bitmap`;
* `legendST` needs `libmialgo_styletrans`, `libLMSceneSeg`, `libcdsprpc`,
  OpenCL, and the DSP style models;
* the camera app runs in an app linker namespace. The provider runs in
  `hal_camera_default` and can read `/odm` vendor libraries; the app cannot
  load arbitrary `/odm` libraries by path.

The on-device `dlopen` harness showed the first failures directly:

```text
cannot locate symbol midebug::Log::catchLogEncryptLog
cannot locate symbol midebug::gMiCamOfflineLogLevel
cannot locate symbol DFXEvent::setSkynetMessage
```

Those are HAL/library ABI problems, not something an app-side `System.load()`
can bypass. Even after supplying the missing symbols, the plugin still expects
the provider's node interface and buffer lifecycle.

## What is possible in the app process

| Approach | Preview | Photo | Video | Real M3/M9 | Verdict |
| --- | --- | --- | --- | --- | --- |
| HAL overlay + pipeline JSON | yes | yes | yes | yes, if registration succeeds | the only real path |
| Load vendor plugin in app process | no | no | no | no | not viable |
| App-side native post-processor | no | yes | no | approximation only | possible fallback |
| Root helper process | no | yes | no | approximation only | complex, not a camera pipeline |
| Existing LUT/film filters in module | yes | yes | limited | no | already works |

An app-side processor could be written as a **new, self-contained** filter:
ship a C++/GPU implementation and the LUT/model assets in the APK, hook the
JPEG/ImageReader result, and process stills after capture. It would not be the
Nezha `mileicafilter` or `legendST` code, would not affect preview or video, and
would not produce the same result as the HAL.

## Recommended split

1. **Keep HyperCameraMod as the UI/feature detector.** `HalCapabilities` should
   only expose M3/M9 when the HAL is actually able to render them.
2. **Keep the real processing in the HAL overlay.** The remaining work is the
   Aurora plugin-registration/DFX compatibility problem documented in
   [05-on-device.md](05-on-device.md), not an APK packaging problem.
3. **For Camera 6.7+ devices without the HAL stack**, use the existing
   app-side film-filter/LUT path as the compatibility feature. Do not present
   it as M3/M9.

## If an app-side fallback is still wanted

The minimum honest scope is:

* still photos only;
* a new self-contained renderer, not the vendor `.so` files;
* explicit UI labeling as a simulation/fallback;
* no claim of preview or video support;
* a device test matrix covering HEIF, RAW, portrait, and HDR.

That is a separate feature from the HAL backport, and it should not be used to
justify enabling the M3/M9 picker on a device whose provider cannot process it.

## Compatibility fallback toggle

`compat_fallback_enabled` (Settings -> Compatibility fallback) is off by
default. When enabled on a device without the real Legendary HAL bridge, the
module still forwards the app's M9/M3 choice as
`com.xiaomi.sessionparams.stylizationType`. The stock Leica
Authentic/Vibrant colour path then produces an approximation.

It is explicitly **not** the Nezha `mileicafilter`/`legendST` pipeline: no
preview style transfer, no M3/M9 parameter LUTs, and no claim of identical
output. The real HAL bridge marker takes precedence when the plugin path is
installed.

On-device check (stock 14 Ultra, module disabled): toggling it on and
restarting Camera logs:

```text
HyperCameraMod: installed the Legendary -> stylizationType request bridge (compatibility fallback)
```
