# The ChiX wrapper plugin: Aurora-native host for the Nezha Legendary algorithm

This document is the design record for the ChiX wrapper plugin: an Aurora-native
Chi plugin that implements Aurora's plugin vtable, `dlopen`s the Nezha
`mileicafilter.so` donor privately, and forwards `processRequest(ProcessRequestInfo*)`
calls to it after translating `ImageParams`.

Evidence labels follow [README.md](README.md): `[observed]` is read from the
extracted firmware, extracted plugin, device logcat, or on-device measurement;
`[inferred]` is a conclusion from observed data that has not been executed;
`[unknown]` is not established by the available evidence.

## 1. Overview

The ChiX wrapper (`com.xiaomi.plugin.hcmlegend`, source at
`tools/hal-port/aurora/hcm_legend_plugin/hcm_legend.c`) is a new, Aurora-native
plugin `.so` installed at
`/odm/lib64/camera/plugins/com.xiaomi.plugin.hcmlegend.so`. It does not patch
the donor plugin and does not register the donor with Aurora's engine. Instead:

* the wrapper implements Aurora's own 15-slot plugin vtable (slot 2 =
  `processRequest(ProcessRequestInfo*)`, slot 5 = no-op `postProcess()`);
* on first request it allocates a `MiLeicaFilterPlugin` object (`0x150` bytes)
  from the donor and runs its exported constructor;
* it translates Aurora's `ImageParams` into the Nezha layout and calls the
  donor's slot-5 `processRequest` directly;
* the donor runs its M3/M9 look (from the shipped `leica_filter_param_*.bin`
  tables) and writes into the output buffer.

### What is working (verified on device)

* Wrapper loads: `HCM-Legend: wrapper loaded`, `provider table ready`,
  `connect registered` `[observed]`.
* Provider create → wrapper object → donor initialize → `isEnabled` style=1/2
  → donor `processRequest` returns `0` (SUCCESS) `[observed]`.
* Style selection works: style=1 (M9 params) and style=2 (M3 params) produce
  measurably different full-resolution outputs `[observed]`.
* Full-resolution 3072x4096 frames rendered by the donor `[observed]`.
* `post-fs-data.sh` stages `lut_m3.png`/`lut_m9.png` and sets
  `camera.debug.mivi3.output.jpeg=true` `[observed]`.

### What is blocked (requires phone testing)

* `candyProcess` crashes with `SEGV_ACCERR` ("execute non-executable memory") at
  `candyProcess+428`; the fault address sits in the same heap region as
  Aurora's `ImageParams` `[observed]`. Root cause unknown — needs tombstone
  analysis plus dumping `ImageParams` fields on device `[next steps]`.
* Private `libMiPhotoFilter.so` was shipped (as `libHcmPhotoFiltr.so`) and
  `processRequest` returned `0`, but `candyProcess` still faults `[observed]`.

## 2. Architecture

```
Aurora engine (libmialgoengine.so)
  |
  |-- MiaNode::processRequest (V1 overload, slot 2)  --+
  |                                                    v
  |------------------------------------> wrapper plugin (com.xiaomi.plugin.hcmlegend.so)
                                            implements Aurora 15-slot vtable
                                            |
                                            |-- translate() -> Nezha-shaped ImageParams
                                            |-- calls donor slot-5 processRequest
                                            v
                                     donor .so (com.xiaomi.plugin.mileicafilter.so)
                                     dlopened RTLD_LOCAL, never registered
                                            |
                                            |-- candyProcess -> videoprocess (GL/EGL)
                                            v
                                     output buffer filled (M3/M9 look)
```

The wrapper is an **Aurora-native** plugin: it is compiled against the project's
own description of Aurora's ABI, owns the donor object, and marshals Aurora
structs into the Nezha layout. The donor file is never registered with
`MPluginModule` and never patched.

### RTLD_LOCAL vs RTLD_GLOBAL

The wrapper loads three libraries:

* `libhcm_shim.so` (`/odm/lib64/libhcm_shim.so`) with `RTLD_NOW | RTLD_GLOBAL`:
  resolves the two Aurora-missing symbols `midebug::Log::catchLogEncryptLog` and
  `mialgo2::PluginUtils::printAndroidAndOfflineLog`, exactly as the existing
  `compat_shim.S` does `[observed]`.
* The donor (`/odm/lib64/com.xiaomi.plugin.mileicafilter.so`) with
  `RTLD_NOW | RTLD_LOCAL`: keeps its `ParamManager`, `ParamUtil`,
  `ParamTrigger`, and `MiLeicaFilterPlugin` symbols from colliding with anything
  else `[inferred]`.
* The donor's `DT_NEEDED` closure needs `libcameraopt_stub.so` and
  `libcom.xiaomi.cam.miexifmgr.so`, both shipped in the bundle `[observed]`.

The shim/DT_NEEDED approach was needed because `dlopen` of the shim with
`RTLD_GLOBAL` alone was not enough: the donor is opened `RTLD_LOCAL`, and its
undefined symbols were still resolved only from the global scope
`[observed]` (13-chix-wrapper-implementation.md §3).

### Private SONAMEs

Two libraries are shipped under renamed SONAMEs so Aurora's older system
libraries do not satisfy the donor's dependencies:

| shipped name | source SONAME | patch | purpose |
|---|---|---|---|
| `hcicxx.so` | `libc++.so` (Nezha) | `patch_private_libcxx.py`, `libc++.so`→`hcicxx.so` | donor needs `std::__1::__fs::filesystem`, absent from Aurora's system `libc++.so` |
| `libHcmPhotoFiltr.so` | `libMiPhotoFilter.so` (Nezha) | `patch_dynstr.py`, `libMiPhotoFilter.so`→`libHcmPhotoFiltr.so` | donor's `candyProcess` enters videoprocess; Aurora's `libMiPhotoFilter.so` is older |

Both names are the same length as the originals, so the patch is a byte-for-byte
dynstr edit (no `patchelf`) `[observed]`. The wrapper's link line also declares
`NEEDED libc.so libdl.so liblog.so libcom.xiaomi.pluginutils.so libhcm_shim.so
com.xiaomi.plugin.mileicafilter.so` so all three land in one link group
`[observed]`.

### Donor DT_NEEDED closure (required at runtime)

| Library | Source | Purpose |
|---|---|---|
| `libcom.xiaomi.plugin.mileicafilter.so` | Nezha `/odm/lib64/camera/plugins/` | donor algorithm |
| `libhcm_shim.so` | built from `compat_shim.S` | midebug/DFX symbols |
| `hcicxx.so` | Nezha `libc++.so` (SONAME-rewritten) | private libc++ with filesystem |
| `libHcmPhotoFiltr.so` | Nezha `libMiPhotoFilter.so` (SONAME-rewritten) | videoprocess API for `candyProcess` |
| `libcameraopt_stub.so` | Nezha `/odm/lib64/` | donor dependency |
| `libcom.xiaomi.cam.miexifmgr.so` | Nezha `/odm/lib64/` | donor dependency |
| `libmetatagwrapper.so` | Nezha `/odm/lib64/` | donor dependency |
| `libcom.xiaomi.metadatautils.so` | Nezha `/odm/lib64/` | `MiMetadata::find` for style selection |

All must be present under `/odm/lib64/` (or `/odm/lib64/hcm/` for the shim) at runtime.

## 3. ABI mapping

### 3.1 Aurora plugin vtable (15 slots)

`_ZTV12FilterPlugin` = `0x1d008` in `com.xiaomi.plugin.filter.so` (136 B;
vptr at `vtable+0x10 = 0x1d018`). Slot *n* is `[vptr + 8n]`.

| slot | Aurora entry | stock filter target | called by |
|---:|---|---|---|
| 0 | `initialize(CreateInfo*, MiaNodeInterface)` | `0xc08c` | `MiaNode::init` @ `0x8a2cc` |
| 1 | `preProcess(PreProcessInfo)` | `0xd4dc` (return 0) | session pre-process |
| 2 | `processRequest(ProcessRequestInfo*)` | `0xc218` (LUT code) | `MiaNode::processRequest` V2 @ `0x8e968` |
| 3 | `processRequest(ProcessRequestInfoV2*)` | `0xd4d0` (return 0) | `MiaNode::processRequest` V1 @ `0x93880` |
| 4 | `customizeOutBufferFormats(…)` | `0xd4e8` (ret) | `Pipeline::getOutputImage` @ `0x83190` |
| 5 | `postProcess()` | `0xd4dc` (return 0) | `MiaNode::postProcess` @ `0x8c2e0` |
| 6 | `flushRequest(FlushRequestInfo*)` | `0xd4c4` | `MiaNode::flush` @ `0x94b90` |
| 7 | `destroy()` | `0xd3cc` | `MiaNode::deInit` @ `0x8c254` |
| 8 | `isEnabled(MiaParams)` | `0xc194` | `needPreProcess` @ `0x8cb6c`, `MiaNode::isEnable` @ `0x8cd00` |
| 9 | auxiliary | `0xd4e8` | `MiaNode::flush` @ `0x94ba0` |
| 10 | `~D1` | `0xbf50` | — |
| 11 | `~D0` | `0xc060` | `MiaNode::deInit` @ `0x8c28c` |
| 12 | out/info helper (`ImageParams`→`MiaFrameInfo`) | `0xd4f0` | `DepurplePlugin::processRequestV2` @ `+0x60` |
| 13 | "skip pre-process" flag (return 0) | `0xd4dc` | `needPreProcess` @ `0x8cb6c` |
| 14 | "supports pre-process" flag (return 1) | `0xd574` | `needPreProcess` @ `0x8cb6c` |

`needPreProcess` (engine, `@ 0x8cb6c`):

```c
int PluginWraper::needPreProcess(MiaParams& p) {
    if (!(this->vtbl[14]() & 1)) return 0;   // capability
    if (this->vtbl[13](this) & 1) return 0;  // "skip" flag
    return this->vtbl[8](this, &p);          // isEnabled
}
```

`postProcess()` at `0x8c2e0` is a no-argument, return-ignored teardown hook;
the pixel path is slot 2 `[observed]` (correction to the earlier claim in
05-on-device.md that Aurora drove the filter node through `postProcess()`).

### 3.2 Donor vtable (21 slots)

`_ZTV19MiLeicaFilterPlugin` = `0x208a8` in `com.xiaomi.plugin.mileicafilter.so`
(184 B; vptr `0x208b8`). The engine never walks this table; the wrapper reads
slots directly by index.

| Nezha slot | function | address | wrapper use |
|---:|---|---|---|
| 0 | `initialize(CreateInfo*, MiaNodeInterface)` | `0xc620` | called by wrapper on first request |
| 1 | `preProcess(PreProcessInfo)` | `0xd990` | not called |
| 2 | `needPreProcess(MiaParams)` | `0x11d10` | not called |
| 5 | `processRequest(ProcessRequestInfo*)` | `0xdafc` | **the algorithm driver — forwarded to** |
| 6 | `processRequest(ProcessRequestInfoV2*)` | `0x10f28` | log-only stub, returns 0 (not called) |
| 10 | `flushRequest` | `0x11094` | not called |
| 11 | `destroy` | `0x11200` | called by wrapper on destroy |
| 12 | `isEnabled(MiaParams)` | `0xd714` | reads `[MiaParams+0x18]` metadata, stores style at `this+0x26` |
| 13 | `reset` | `0x11534` | not called |
| 16/17 | `~D1` / `~D0` | `0xc4f0` / `0xc598` | not called |
| 18 | `getImageInfo(ImageParams*, MiaFrameInfo*)` | `0x11f20` | not called |
| 19/20 | flags | `0x11f08` | return 0 |

Other exported helpers used by the wrapper:

| function | address | use |
|---|---|---|
| ctor `MiLeicaFilterPlugin::MiLeicaFilterPlugin()` | `0xc2d4` | object is `0x150` bytes |
| `initResource()` | `0xcc54` | allocates `param_lut_t`, loads `.bin`, selects M3/M9; exported symbol used for style switch |
| `candyProcess(...)` | `0x10180` | the actual GL/videoprocess call (faults at `+428`) |
| `getPluginName()` | `0xc5c8` | `"com.xiaomi.plugin.mileicafilter"` |

### 3.3 Slot-by-slot mapping table

| Aurora slot | Wrapper implementation | Donor slot used | Notes |
|---:|---|---|---|
| 0 | `plugin_initialize` — no-op (deferred to first request) | — | donor `initialize` is called from slot 2 |
| 1 | `ret0` | 1 | logging-only in donor |
| 2 | `plugin_process_request` — translate + forward | 5 | **the pixel path** |
| 3 | `ret0` | 6 | donor V2 stub returns 0 |
| 4 | `noop` | 4 | not in donor's meaningful set |
| 5 | `ret0` (postProcess, no-op) | 5 (donor's own meaning differs) | engine calls slot 5; wrapper keeps it a teardown no-op |
| 6 | `ret0` | 10 | flush |
| 7 | `plugin_destroy` | 11 | destroy donor |
| 8 | `plugin_enabled` — style gate | 12 | donor `isEnabled` sets style byte |
| 9 | `noop` | — | auxiliary |
| 10/11 | `noop` | 16/17 | destructors |
| 12 | `ret0` | 18 | image-info helper |
| 13 | `ret0` | 13 | skip-pre-process flag |
| 14 | `ret1` | 19/20 | supports-pre-process flag |

The wrapper calls the donor directly by vtable index rather than registering the
donor's vtable with Aurora `[observed]`.

## 4. ImageParams translation

The container/request layout is shared between Aurora and Nezha `[observed]`:

```c
struct ProcessRequestInfo {   // Aurora and Nezha agree here
    void *input;              // +0x00  container; [container+0x28] = ImageParams*
    void *output;             // +0x18  container; [container+0x28] = ImageParams*
    uint32_t frameIndex;      // +0x48
};
```

Only the `ImageParams` body differs. Aurora (from the stock filter at
`0xc464`–`0xc497`, slot 12 at `0xd4f0`) vs Nezha (donor `getImageInfo` at
`0x11f20`/`0x11f3c`):

| field | Aurora offset | Nezha offset | read by |
|---|---|---|---|
| format | `+0x00` | `+0x00` | `GraphicBuffer`, slot 18 |
| width | `+0x04` | `+0x04` | `GraphicBuffer`, slot 18 |
| height | `+0x08` | `+0x08` | `GraphicBuffer`, slot 18 |
| usage | `+0x1c` | `+0x38` | `0xe2c8`, slot 18 → `+0xc` |
| metadata `shared_ptr` (hi) | `+0x28` | `+0x40` | `fillMetaDataInfo` |
| metadata `shared_ptr` (lo) | `+0x30` | `+0x48` | `fillMetaDataInfo` |
| plane count | `+0x48` | `+0x70` | slot 18 |
| native handle `native_handle*` | `+0x70` | `+0xb0` | `GraphicBuffer` @ `0xe2d4` |

Wrapper `struct AImg` (Aurora) → `struct NImg` (Nezha) `translate()`:

```c
dst->fmt    = src->fmt;        // +0x00
dst->width  = src->width;      // +0x04
dst->height = src->height;     // +0x08
dst->usage  = src->usage;      // Nezha +0x38 <- Aurora +0x1c
dst->fmt2   = 0;               // +0x3c; not used by GL path
dst->meta   = src->meta;       // Nezha +0x40 <- Aurora +0x28
dst->meta2  = src->meta2;      // Nezha +0x48 <- Aurora +0x30
dst->native = src->native;     // Nezha +0xb0 <- Aurora +0x70
dst->planes = 0;               // +0x70; not used by the GL path
```

`[inferred]` The plane arrays (`+0x74/+4i` strides, `+0x80/+8i` pointers,
`+0x98/+8i` second pointer) are not needed for the GL path; they feed slot 18's
`MiaFrameInfo` and the dump path.

The donor's `candyProcess` builds `GraphicBuffer` from `+0x00/+0x04/+0x08/
+0x38/+0xb0` `[observed]`. After the handle-offset fix (`+0x74` → `+0x70`), the
translated handle read correctly:

```
native in=0xb4000070afd9fcc0 out=0xb4000070afdb6d30   [observed]
```

but `candyProcess` still faults at `+428` `[observed]` — see section 8.

## 5. Provider registration

### 5.1 `connect()` hooking

The wrapper exports `connect(ProviderManager*)`, modelled on the stock filter's
`connect` (`0xb020`) `[observed]`:

```c
int connect(void *pm) {
    void *p = malloc(8);                 // stateless Provider object
    *(void **)p = g_provider_vtable;     // vptr = table+0x10
    ProviderManager::add(pm, p);         // mialgo2::ProviderManager::add
    return 1;
}
```

`ProviderManager::add` is imported as `_ZN7mialgo215ProviderManager3addEPNS_8ProviderE`
`[observed]`. The engine's registry
(`MPluginModule::getPluginWraperByPluginName` @ `0x4489c`) walks the provider
vector, calls provider slot 5 for the name and compares byte-for-byte, then
calls provider slot 4 to create the instance. The `NodeName` in the pipeline JSON
is the key; there is no hard-coded name table: `com.xiaomi.plugin.filter`
appears in no Aurora binary `[observed]`.

### 5.2 Provider vtable (6 slots)

`_ZTV20FilterPluginProvider` = `0x1d090` (64 B). The wrapper clones slots 0–5
from the stock symbol (weak, exported) and overrides slots 4 and 5:

| provider slot | stock target | wrapper implementation |
|---:|---|---|
| 0 | `Provider::~Provider` | cloned (no-op destructor) |
| 1 | deleting destructor | cloned |
| 2 | interface version | cloned (`PLUMA_INTERFACE_VERSION`) |
| 3 | provider type | cloned (`PluginWraperProvider` type string) |
| 4 | `create()` (`0xd5d4`) | `provider_create` — allocates wrapper object, sets vptr |
| 5 | `getPluginName()` (`0xd614`) | `provider_name` — returns allocated `std::string` |

### 5.3 `getPluginName()` returns `std::string` by value

`ProviderManager` compares the name returned by provider slot 5 **and then frees
the string** `[observed]`. The stock implementation (`0xd614`) builds the libc++
long form:

```text
mov  x8, <sret pointer>   ; caller-provided return object
new(0x20); copy name
str  #0x21, [x8]          ; libc++ long form: capacity | 1
str  #0x18, [x8, #8]      ; size
str  ptr,  [x8, #0x10]    ; data
```

Returning a `char*` leaves the `std::string` uninitialised, so the registry
lookup misses (`error not found pluginName: …`) and the later destruction aborts
with `Scudo ERROR: invalid chunk state` `[observed]`. Returning a static buffer
also aborts, because the caller frees it.

The wrapper therefore builds the libc++ long form itself:

```c
typedef struct { u64 cap; u64 size; char *data; } hcm_string;
/* cap = length | 1, data = malloc(len + 1) */
```

`com.xiaomi.plugin.hcmlegend` is 26 bytes, longer than the 22-byte short-string
threshold, so the long form is required `[observed]`.

## 6. Pipeline insertion

The wrapper node is an **offline node with no `NodeMask`**, so the engine
selects `OtherNode` and the V1 plugin API (slot 2) `[observed]`. An odd `NodeMask`
would route to the stub V2 API (slot 3) `[observed]`.

It is inserted immediately **before** the stock filter node, in the same Gralloc
chain:

```
MovieEffectInstance0 (Gralloc)
  -> LegendFilterInstance0  ("com.xiaomi.plugin.hcmlegend", no NodeMask)
  -> FilterInstance0        (stock LUT filter, pass-through when no look)
  -> WatermarkInstance0
```

Node entry:

```json
{ "NodeName": "com.xiaomi.plugin.hcmlegend",
  "NodeInstance": "LegendFilterInstance0" }
```

The patcher (`patch_legend_snapshot.py`) moves the upstream link's `DstPort` to
`LegendFilterInstance0` and adds a new `LegendFilterInstance0 → FilterInstance0`
link, both `BufferType: "Gralloc"`. It runs on `leicasnapshot.json`,
`satsnapshot.json`, `normalsnapshot.json`, and `superhdsnapshot.json`
`[observed]`.

`[inferred]` Photo mode on this build uses `normalsnapshot.json` (the SAT graph
is selected for ordinary Leica captures); patching only `leicasnapshot.json`/
`satsnapshot.json` yields a node that is never created (13-chix-wrapper-
implementation.md §4).

The wrapper is an **additional** node: when `isEnabled` returns 0 it is a clean
pass-through and the stock filter keeps working `[observed]`.

## 7. Verification results

Measured on the device (Xiaomi 14 Ultra `aurorapro`, Android 16, HyperOS
`OS3.0.304.0.WNAMIXM`, KernelSU 3.2.5) `[observed]`:

Logcat chain:

```
HCM-Legend: wrapper loaded
HCM-Legend: provider table ready
HCM-Legend: connect registered
HCM-Legend: provider create -> 0x...
HCM-Legend: plugin initialize
HCM-Legend: isEnabled style=2
HCM-Legend: donor loaded vt=0x...
HCM-Legend: donor initialized style=2
HCM-Legend: img fmt=0x23 wh=0x1000/0xc00
HCM-Legend: native in=0xb4000070afd9fcc0 out=0xb4000070afdb6d30
HCM-Legend: donor processRequest -> 0
```

Full-resolution 3072x4096 frames rendered by the donor, no provider restart.

### Saturation measurements (same scene, full-resolution captures, node forced)

| style | `\|R-G\|` | `\|G-B\|` | mean | interpretation |
|---:|---:|---:|---:|---|
| 0 (node disabled / pass-through) | 1.7 | 13.6 | 47.1 | baseline |
| 2 (M3 parameter table) | 2.8 | 17.7 | 44.6 | M3 look engaged |
| 1 (M9 parameter table) | 9.3 | 23.0 | 52.3 | M9 look engaged |

The parameter tables are clearly engaged and distinct `[observed]`.

The donor has no monochrome stage (`strings` shows no monopan/desaturate symbol)
`[observed]`: Nezha's M3 output only becomes the Leica Monopan look when the
separate Monopan step runs (`libmialgo_monopan`) `[inferred]`.

### Force switch

`persist.vendor.camera.hypercam.style` (1 = M9, 2 = M3, 0 = off) drives the
wrapper; with `0`, `isEnabled` returns 0 and the node is a pass-through
`[observed]`. The wrapper also reads `lut_m3`/`lut_m9` from
`com.xiaomi.mivi2.render` metadata and falls back to `/data/vendor/camera/hcm_style`
then the vendor property `[observed]` (source).

#### Render payload intent structure

The camera app signals the selected Leica look via the render payload intent extra
`com.xiaomi.mivi2.render`. Expected JSON structure:

```json
{
  "lutNames": ["lut_m3"]   // or ["lut_m9"]
}
```

The wrapper's `style_from_meta()` scans the raw JSON string for the substring
`lut_m3` or `lut_m9` (robust against whitespace/formatting). This is the
**primary** style source. Fallback order:

1. `MiMetadata::find("com.xiaomi.mivi2.render")` → parse `lutNames` array
2. `/data/vendor/camera/hcm_style` (file with single char `1`/`2`/`0`)
3. `persist.vendor.camera.hypercam.style` (vendor property `1`/`2`/`0`)

The render payload is set by the camera app when the user selects M3/M9 in the
Leica Essential UI (component 256). This avoids the need for a system property
and works per-capture.

## 8. Blocked items

### candyProcess SEGV_ACCERR

`candyProcess` (donor @ `0x10180`) crashes with `SEGV_ACCERR` ("execute
non-executable memory") at `candyProcess+428` `[observed]`.

Full stack trace `[observed]`:

```
#00 libc++.so
#01 com.xiaomi.plugin.mileicafilter.so
      MiLeicaFilterPlugin::candyProcess(FilterFrameParam*, FilterFrameParam*,
                                        VideoFormat, param_lut_t*, param_shading_float_t*)+428
#02 MiLeicaFilterPlugin::processRequest(ProcessRequestInfo*)+2308
#03 com.xiaomi.plugin.hcmlegend.so plugin_process_request+720
#04 libmialgoengine.so MiaNode::processRequest(...)+4428
```

The fault address (`0x726dbdb9d8`) sits in the same heap region as Aurora's
`ImageParams` (`ai=0x72606efcbc`) `[observed]`, so the donor is reaching a call
target through a structure the wrapper has not translated `[inferred]`.

The likely candidate is the videoprocess/GL side: Aurora's `libMiPhotoFilter.so`
is older than Nezha's, and `candyProcess+428` is where the donor enters it
`[inferred]` (13-chix-wrapper-implementation.md §"Where it stands").

Private `libMiPhotoFilter.so` was shipped (as `libHcmPhotoFiltr.so`) and
`processRequest` returned `0`, but `candyProcess` still faults `[observed]`.

### Next debugging steps (requires phone)

1. Pull a tombstone for the `candyProcess+428` fault and map which field the
   call at that offset loads; decide whether it can be supplied from the Aurora
   `ImageParams`/`MiaNodeInterface` or needs a Nezha-shaped static (the
   videoprocess singleton) `[next steps]`.
2. Compare Aurora's `libMiPhotoFilter.so` exports with the donor's expectations;
   the fallback is to ship Nezha's own `libMiPhotoFilter.so` under a private
   SONAME, as was done for libc++ `[next steps]`.
3. Dump the Aurora `ImageParams` fields (`+0x00/+0x04/+0x08/+0x1c/+0x70`) per
   capture and compare against the stock filter's `input: %dx%d,sw=…` line at
   `0xc30c` `[next steps]`.

## 9. Build & install

### Build

`tools/hal-port/aurora/build_legend_wrapper.sh [OUT_DIR]`

Builds `com.xiaomi.plugin.hcmlegend.so` (NDK clang, `-fPIC -O2 -shared
-nostdlib`, soname `com.xiaomi.plugin.hcmlegend.so`) plus `libhcm_shim.so`, and
packages a KernelSU module with `system/odm/...` layout:

* `/system/odm/lib64/camera/plugins/com.xiaomi.plugin.hcmlegend.so` — wrapper
* `/system/odm/lib64/com.xiaomi.plugin.mileicafilter.so` — donor (private)
* `/system/odm/lib64/libhcm_shim.so` — midebug/DFX shim symbols
* `/system/odm/lib64/hcicxx.so` — private Nezha `libc++.so` (SONAME-rewritten)
* `/system/odm/lib64/libHcmPhotoFiltr.so` — private Nezha `libMiPhotoFilter.so`
* `/system/odm/lib64/libcameraopt_stub.so`, `libcom.xiaomi.cam.miexifmgr.so`,
  `libmetatagwrapper.so` — donor dependency closure
* `/system/odm/etc/camera/leica_filter_param_*.bin` — M3/M9 parameter tables
* `/system/odm/etc/camera/xiaomi/{normal,leica,sat,superhd}snapshot.json` —
  with `LegendFilterInstance0` inserted
* `/system/odm/files/lut_m3.png`, `/system/odm/files/lut_m9.png` — staged by
  `post-fs-data.sh` to `/data/vendor/camera/`
* `sepolicy.rule` (magiskpolicy syntax, no colon):

```text
allow hal_camera_default system_file file { open read getattr map execute }
allow hal_camera_default system_file dir { open read getattr search }
```

### Install

1. Run `build_legend_wrapper.sh`, then `package_module.py <bundle>` to produce
   the zip.
2. `ksud module install <zip>` (KernelSU) — or Magisk. `/odm` is read-only
   EROFS, so files must arrive via a module overlay `[observed]`.
3. Reboot (module staged in `/data/adb/modules_update`; camera provider reads
   pipeline JSON at startup) `[observed]`.
4. `post-fs-data.sh` copies `lut_m3.png`/`lut_m9.png` to `/data/vendor/camera/`
   and sets `camera.debug.mivi3.output.jpeg=true` `[observed]`.
5. Select M3/M9 (`persist.vendor.camera.hypercam.style = 2` / `1`) or let the
   `com.xiaomi.mivi2.render` payload drive the look `[observed]`.

KernelSU labels module files `system_file`; `hal_camera_default` cannot read
`system_file` under `/odm`, so the `sepolicy.rule` above is required
`[observed]`. `chcon` on the overlay fails (read-only file system).

### Donor library versions needed (exact)

| Library | Nezha source | Version / Build |
|---|---|---|
| `com.xiaomi.plugin.mileicafilter.so` | `/odm/lib64/camera/plugins/` | Madrid/Nezha (SM8650) |
| `libMiPhotoFilter.so` → `libHcmPhotoFiltr.so` | `/odm/lib64/` | Madrid/Nezha |
| `libc++.so` → `hcicxx.so` | `/vendor/lib64/` or `/odm/lib64/` | Nezha (has `std::filesystem`) |
| `libcameraopt_stub.so` | `/odm/lib64/` | Nezha |
| `libcom.xiaomi.cam.miexifmgr.so` | `/odm/lib64/` | Nezha |
| `libmetatagwrapper.so` | `/odm/lib64/` | Nezha |
| `libcom.xiaomi.metadatautils.so` | `/odm/lib64/` | Nezha |
| `leica_filter_param*.bin` | `/odm/etc/camera/` | Nezha (M3: 93KB, M9: 1.69MB) |

**SM8750 (17 Ultra / 18 Pro Max) vs Aurora (SM8650) compatibility:**
- The donor algorithm (`mileicafilter.so`) is from Madrid (SM8650). On SM8750
  devices the camera HAL may expect different `ImageParams` layouts or
  `GraphicBuffer` usage flags. The wrapper's `translate()` function would need
  adjustment if field offsets differ.
- QNN HTP version: Aurora is HTP V75-era; SM8750 donors may use HTP V81. The
  `libMiPhotoFilter.so` / `libHcmPhotoFiltr.so` videoprocess path is the main
  compatibility risk. If the SM8750 donor uses newer QNN APIs, the private
  `libHcmPhotoFiltr.so` must come from the same donor build.

## 10. Next steps (prioritized, requires the phone)

1. **Tombstone analysis** for `candyProcess+428` (`SEGV_ACCERR`,
   "execute non-executable memory"); map the call target and the fault address
   `0x726dbdb9d8` relative to the heap region holding Aurora's `ImageParams`
   (`ai=0x72606efcbc`).
2. **Dump `ImageParams` fields** per capture (`+0x00/+0x04/+0x08/+0x1c/+0x70`)
   and compare against the stock filter's `input: %dx%d,sw=…` line at `0xc30c`;
   verify the translated `native` handle and `usage`.
3. **Compare `libMiPhotoFilter.so`** exports (Aurora vs Nezha) at the
   `candyProcess+428` entry; if the videoprocess ABI differs, ship Nezha's own
   `libMiPhotoFilter.so` under a private SONAME (same-length dynstr edit, as
   with `libc++.so`→`hcicxx.so`).
4. **Verify style selection end-to-end**: confirm `isEnabled` style=1/2 and the
   M3/M9 saturation deltas on a clean reboot with only this module active.
5. **Complete M3**: add the Monopan helper (`libmialgo_monopan`, spawned by the
   Moment App app) as a second node/step so M3 output becomes the
   monochrome Leica look `[inferred]`.
6. **Confirm pipeline insertion** in `normalsnapshot.json` (the graph the device
   actually uses for Photo mode) and that `LegendFilterInstance0` has no
   `NodeMask` `[inferred]`.
7. **SM8750 testing**: validate wrapper on 17 Ultra / 18 Pro Max; adjust
   `translate()` if `ImageParams` layout differs.
