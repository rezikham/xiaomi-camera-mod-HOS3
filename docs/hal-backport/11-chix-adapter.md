# The ChiX adapter: how Aurora actually drives an offline plugin

This page answers the question that blocked the last iteration of the
`mileicafilter` port: **can an Aurora-side adapter run the Nezha Legendary
algorithm, and what exactly does it have to do?**

The short answer is **yes, but not through `postProcess()`**. Aurora's
`MiaNode::postProcess()` is a no-argument, return-ignored teardown hook. The
pixel work for an offline node happens in the plugin's
`processRequest(ProcessRequestInfo*)`, and Aurora *does* call that entry for
nodes whose `NodeMask` has bit 0 clear. The stock `com.xiaomi.plugin.filter`
node is one of those nodes, which is why the LUT injection in
[09](09-engine-lut-injection.md) works while the donor never ran: the earlier
vtable-only shim put a no-op in slot 5 and the node never reached the
`processRequest` call that carries buffers.

The recommended implementation is therefore an **Aurora-native wrapper plugin**
that implements Aurora's fifteen-slot plugin vtable, `dlopen`s the donor
privately, and forwards `processRequest(ProcessRequestInfo*)` after translating
the two `ImageParams` structs. The donor's `ProcessRequestInfo` layout is
already identical to Aurora's for the fields that matter, and the donor's GL
path needs only five fields from `ImageParams`. No further binary patch of the
donor is required.

Evidence labels follow [README.md](README.md): `[observed]` is read from the
extracted firmware, `[inferred]` is a conclusion that has not been executed,
`[unknown]` is not established.

## 0. Binaries analysed

| Binary | Path (dump) | SHA-256 (prefix) |
| --- | --- | --- |
| Aurora engine | `extracted_aurora/odm/lib64/libmialgoengine.so` (834 440 B) | `9c42efa9f507…` |
| Aurora filter plugin | `extracted_aurora/…/plugins/com.xiaomi.plugin.filter.so` | `64b3e5ddaef8…` |
| Aurora provider | `extracted_aurora/odm/bin/hw/vendor.qti.camera.provider-service_64` | `0a93b99067b7…` |
| Nezha engine | `extracted_nezha/odm/lib64/libmialgoengine.so` (1 939 520 B) | `9933ccf38227…` |
| Nezha donor | `extracted_nezha/…/plugins/com.xiaomi.plugin.mileicafilter.so` | `43dec6bf21bb…` |
| Aurora pipeline | `extracted_aurora/odm/etc/camera/xiaomi/satsnapshot.json` | — |

All disassembly below uses link-time addresses (`llvm-objdump -d`), and
Ghidra decompilation with image base `0x100000` (so a Ghidra address
`0x18c2e0` is file offset `0x8c2e0`).

## 1. Aurora's plugin ABI and lifecycle

### 1.1 The vtable

Aurora's `PluginWraper` vtable is 15 virtuals (`_ZTV12FilterPlugin`,
`0x1d008`, 136 B; the object's vptr is `vtable+0x10 = 0x1d018`). Slots are
numbered from the vptr, so slot *n* is `[vptr + 8n]`.

| slot | Aurora entry | stock filter (`0x1d018` vptr) | called by |
| ---: | --- | --- | --- |
| 0 | `initialize(CreateInfo*, MiaNodeInterface)` | `0xc08c` | `MiaNode::init` @ `0x8a7ec` |
| 1 | `preProcess(PreProcessInfo)` | `0xd4dc` (return 0) | session pre-process (observed via shim) |
| 2 | `processRequest(ProcessRequestInfo*)` | **`0xc218`** (LUT code) | `MiaNode::processRequest` V2 overload @ `0x8fab4` |
| 3 | `processRequest(ProcessRequestInfoV2*)` | `0xd4d0` (return 0) | `MiaNode::processRequest` V1 overload @ `0x94200` |
| 4 | `customizeOutBufferFormats(…)` | `0xd4e8` (ret) | `Pipeline::getOutputImage` @ `0x83190` |
| 5 | **`postProcess()`** | `0xd4dc` (return 0) | `MiaNode::postProcess` @ `0x8c2fc` |
| 6 | `flushRequest(FlushRequestInfo*)` | `0xd4c4` | `MiaNode::flush` @ `0x94b90` |
| 7 | `destroy()` | `0xd3cc` | `MiaNode::deInit` @ `0x8c278` |
| 8 | `isEnabled(MiaParams)` | **`0xc194`** | `PluginWraper::needPreProcess` @ `0x8cb6c`, `MiaNode::isEnable` @ `0x8cd9c` |
| 9 | auxiliary (called after flush) | `0xd4e8` | `MiaNode::flush` @ `0x94ba0` |
| 10 | `~D1` | `0xbf50` | — |
| 11 | `~D0` | `0xc060` | `MiaNode::deInit` @ `0x8c28c` |
| 12 | out/info helper (`ImageParams`→`MiaFrameInfo`) | `0xd4f0` |  `DepurplePlugin::processRequestV2` @ `+0x60` |
| 13 | "skip pre-process" flag (return 0) | `0xd4dc` | `needPreProcess` @ `0x8cb6c` |
| 14 | "supports pre-process" flag (return 1) | `0xd574` | `needPreProcess` @ `0x8cb6c` |

`[observed]` `needPreProcess` is:

```c
int PluginWraper::needPreProcess(MiaParams& p) {
    if (!(this->vtbl[14]() & 1)) return 0;   // capability
    if (this->vtbl[13](this) & 1) return 0;  // "skip" flag
    return this->vtbl[8](this, &p);          // isEnabled
}
```

This is the correct explanation of the `getCustomFrameOrder+8` crash recorded in
[05](05-on-device.md): in the Nezha order slot 13/14 are different functions,
and slot 8 is `getCustomFrameOrder(shared_ptr<MiMetadata>)`, which writes a
vector through an sret register the engine never set.

### 1.2 `postProcess()` receives nothing

`MiaNode::postProcess()` at `0x8c2e0` (48 B) is exactly:

```c
undefined8 MiaNode::postProcess() {
    PluginWraper *p = *(PluginWraper **)(this + 0x50);   // plugin instance
    if (p) (**(code **)(*(long *)p + 0x28))();           // slot 5, no args
    return 0;                                            // return ignored
}
```

`[observed]` There is no image, no metadata, no return channel. The engine also
never calls `requestOutputBuffer`/`setResultBuffer`/`setOutputFormat` for these
nodes (`[observed]`, [05](05-on-device.md)), because output buffers are
allocated by the engine from the pipeline links and handed to the plugin in
`ProcessRequestInfo` instead.

`[observed]` Among the plugins inspected, the only one that does real work in
slot 5 is `DepurplePlugin::postProcess()` at `0x7a0c` (60 B), and all it does is
`QAltekDepurple::decInit()` — a teardown of state built in `preProcess`.
Slot 5 is not a processing entry.

### 1.3 `processRequest` is the pixel path

There are **two** `MiaNode::processRequest` overloads, and they call the plugin
ABI in opposite directions:

| engine function | signature | plugin call |
| --- | --- | --- |
| `0x93880` | `(map&, int&, int&, sp<Pipeline>&)` | `[vptr+0x18]` = slot 3 (`ProcessRequestInfoV2*`) |
| `0x8e968` | `(map&, map&&, int&, int&, sp<Pipeline>&, int)` | `[vptr+0x10]` = slot 2 (`ProcessRequestInfo*`) |

`[observed]` `SigframeNode::processTask` (`0x9b0e8`) and
`OtherNode::processTask` (`0x9caf0`) choose between them with a single bit:

```c
if (((this[8] & 1) == 0) || local_308 != 0)
    MiaNode::processRequest(..., /* 6-arg V2 overload */);  // plugin slot 2
else
    MiaNode::processRequest(..., /* 4-arg V1 overload */);  // plugin slot 3
```

`this[8]` is copied in `MiaNode::init` from `NodeItem+0x30`
(`0x8a35c: ldr w8,[x20,#0x30]; str w8,[x19,#8]`), which is the parsed
`NodeMask` property. `MiaNodeFactory::CreateNodeInstance` (`0x9e440`) uses the
same field to pick the node class: bit 9 → plain `MiaNode`, bit 1 →
`ConcurrencyNode`, bit 4 → `SigframeNode`, else `OtherNode`.

Consequences `[observed]`:

* `FilterInstance0` in `satsnapshot.json`/`leicasnapshot.json` has **no
  `NodeMask` property**. `[inferred]` The parsed node is zero-initialised, so
  the value is 0 → `OtherNode`, and the `processRequest` selection takes the
  first branch → **plugin slot 2**.
* That is precisely `FilterPlugin::processRequest(PRI*)` at `0xc218`, the
  `candyProcess`/`CubeLutEffect` path that [09](09-engine-lut-injection.md)
  verified on the device. The pixel work is *not* in `postProcess` and not in
  the slot-3 stub.
* Nodes with odd `NodeMask` (e.g. `AnchorInstance0` = 49) use slot 3, the "V2"
  plugin API. This is why the on-device trace logged `processRequest` for
  `anchor` but the donor (`NodeMask` 0) was expected on the other path.

### 1.4 Where the buffers come from

`[observed]` The engine builds the request from the pipeline images
(`buildBufferParams`, `MiaNode::getOutputBuffer` @ `0x8cfcc`) and passes a
pointer to a stack structure as `x1`. Its layout, recovered from the stock
filter's own code (which is known-good on the device), is:

```c
struct ProcessRequestInfo {       // Aurora and Nezha agree here
    void *input;                  // +0x00  container
    ...
    void *output;                 // +0x18  container
    ...
    uint32_t frameIndex;          // +0x48
};
// container->[0x28] is ImageParams*
```

`FilterPlugin::processRequest` reads `*(long*)info` and `*(long*)(info+0x18)`,
takes `[container+0x28]`, and builds an `android::GraphicBuffer` from it, then
an `EGLClientBuffer` (`0xc464`–`0xc4f0`). The donor's `processRequest` does the
same thing at `0xdb3c`–`0xdb94`. **The container and the request layout are
shared between the two frameworks; only the `ImageParams` body differs.**

## 2. Why a `postProcess` adapter cannot work

A shim that only overrides slot 5 has no access to:

* the input/output `ImageParams` (not passed, not stored by the engine),
* the request metadata (`MiMetadata`),
* the output buffer or its format.

The `MiaNodeInterface` that `initialize` receives does contain buffer callbacks,
but `[observed]` Aurora never invokes them (zero calls during a verified stock
capture, [05](05-on-device.md)). The donor stores the interface at
`this+0xd8` and — checked by scanning every load in that range — never calls it
either. So there is nothing to "synthesize" from a `postProcess` call.

A `postProcess` adapter is feasible only if it **also** implements slot 2 and
remembers the last `ProcessRequestInfo`; at that point it is a processRequest
adapter with an empty slot 5, which is exactly the wrapper in section 5.

## 3. What the donor actually needs

### 3.1 Vtable and entry points

`_ZTV19MiLeicaFilterPlugin` = `0x208a8` (184 B, 21 slots; vptr `0x208b8`).

| Nezha slot | function | address | notes |
| ---: | --- | --- | --- |
| 0 | `initialize(CreateInfo*, MiaNodeInterface)` | `0xc620` | copies 14 qwords of the interface to `this+0xd8`; reads `CreateInfo+0x44` (processMode) and `CreateInfo+0x48` (`MiMetadata*`) |
| 1 | `preProcess(PreProcessInfo)` | `0xd990` | logging only |
| 2 | `needPreProcess(MiaParams)` | `0x11d10` | Nezha copy of the engine helper |
| 5 | `processRequest(ProcessRequestInfo*)` | **`0xdafc`** | the algorithm driver |
| 6 | `processRequest(ProcessRequestInfoV2*)` | `0x10f28` | **log-only stub, returns 0** |
| 10 | `flushRequest` | `0x11094` | |
| 11 | `destroy` | `0x11200` | |
| 12 | `isEnabled(MiaParams)` | `0xd714` | reads `[MiaParams+0x18]` (metadata), stores style at `this+0x26` |
| 13 | `reset` | `0x11534` | |
| 16/17 | `~D1` / `~D0` | `0xc4f0` / `0xc598` | |
| 18 | `getImageInfo(ImageParams*, MiaFrameInfo*)` | `0x11f20` | copies the plane/stride fields |
| 19/20 | flags | `0x11f08` | return 0 |

Other exported helpers:

| function | address | use |
| --- | --- | --- |
| ctor `MiLeicaFilterPlugin::MiLeicaFilterPlugin()` | `0xc2d4` | object is `0x150` bytes (factory `0x12028`) |
| `initResource()` | `0xcc54` | allocates `param_lut_t` (0x3993 B), loads the `.bin`, selects M3/M9 |
| `releaseResource()` | `0x113c0` | |
| `getInitProperties()` | `0x11708` | |
| `candyProcess(FilterFrameParam*, FilterFrameParam*, VideoFormat, param_lut_t*, param_shading_float_t*)` | `0x10180` | the actual GL/videoprocess call |
| `fillMetaDataInfo(ImageParams&)` | `0xeae4` | AWB/AEC EXIF tags |
| `getPluginName()` | `0xc5c8` | `"com.xiaomi.plugin.mileicafilter"` |

`[observed]` `processRequest` reads exactly three fields of
`ProcessRequestInfo`:

```text
info+0x00              -> container; [container+0x28] = ImageParams*  (input)
info+0x18              -> container; [container+0x28] = ImageParams*  (output)
info+0x48  (u32)       -> this+0x14 (frame index / EXIF)
```

It then calls its own slot 18 on both `ImageParams` (`0xdb78`, `0xdb94`) and
`candyProcess` with the two EGL client buffers (`0xe3f4`–`0xe400`). Output is
written **into the output buffer**; success is `w0 == 0`, and on a bypass path
it does `miCopyBuffer(out, in)` (`0xe554` region).

### 3.2 `ImageParams` fields the donor touches

| Nezha offset | meaning | read by |
| ---: | --- | --- |
| `+0x00` | format | `GraphicBuffer`, slot 18 |
| `+0x04` | width | `GraphicBuffer`, slot 18 |
| `+0x08` | height | `GraphicBuffer`, slot 18 |
| `+0x18` | (frame/video format) | slot 18 → `MiaFrameInfo+0x58` |
| `+0x34` | (scanline?) | slot 18 → `+0x5c` |
| `+0x38` | usage (GraphicBuffer arg 9) | `0xe2c8`, slot 18 → `+0xc` |
| `+0x3c` | (format?) | slot 18 → `+0x10` |
| `+0x40`/`+0x48` | `shared_ptr<MiMetadata>` | `fillMetaDataInfo` |
| `+0x70` | plane count | slot 18 |
| `+0x74 + 4i` | plane strides | slot 18 |
| `+0x80 + 8i` | plane pointers | slot 18 |
| `+0x98 + 8i` | plane second pointer | slot 18 |
| `+0xb0` | `native_handle*` | `GraphicBuffer` @ `0xe2d4` |

`[inferred]` Because the GL path only constructs `GraphicBuffer` from
`+0x00/+0x04/+0x08/+0x38/+0xb0` and the `FilterFrameParam` from the EGL buffer
plus the `+0x04` pair and `+0x38`, the plane arrays are not needed for
processing; they feed slot 18's `MiaFrameInfo` and the dump path.

### 3.3 Parameter files and state

`[observed]` `initResource` selects a `ParamUtil` by `this+0x70`, computed from
`this+0x26` (style) and `this+0xd0` (processMode, 0 = preview, 1 = snapshot):

| style (`this+0x26`) | processMode 0 | processMode 1 |
| ---: | --- | --- |
| 0 | no look (`this+0x70 = 0`) | no look |
| 1 (M9) | mode 2 → `leica_filter_param_m9_preview.bin` | mode 1 → `leica_filter_param_m9_snapshot.bin` |
| 2 (M3) | mode 3 → `leica_filter_param_m3.bin` | mode 3 → `leica_filter_param_m3.bin` |

Files used by the donor (`[observed]` string table):

```text
/odm/etc/camera/leica_filter_param.bin
/odm/etc/camera/leica_filter_param_m3.bin
/odm/etc/camera/leica_filter_param_m9_preview.bin
/odm/etc/camera/leica_filter_param_m9_snapshot.bin
```

All four are already in the port bundle ([03](03-port-bundle.md)).

The lazy-init contract is convenient: `processRequest` itself does

```c
if (this[0x22] == 0) { releaseResource(this); initResource(this); }
```

so the adapter does not need to call `initResource` explicitly — set the style
first and the first request initialises everything.

## 4. ABI delta that matters

Everything below is the delta between the Nezha plugin and Aurora's engine.
The container/request layout is **not** in the table because it matches.

| Item | Aurora | Nezha/Madrid | adapter action |
| --- | --- | --- | --- |
| `MiaParams.metadata` | `+0x00` | `+0x18` | wrapper reads Aurora's `+0x00`; donor `isEnabled` is not called (or gets a synthesized `MiaParams`) |
| `CreateInfo` | no metadata pointer; ints at `+0x48/+0x4c` ([05](05-on-device.md)) | `processMode` at `+0x44`, metadata at `+0x48` | wrapper builds its own `CreateInfo` (`+0x44` = 1 snapshot / 0 preview, `+0x48` = request metadata) |
| `ImageParams` metadata | `+0x28/+0x30` | `+0x40/+0x48` | copy |
| `ImageParams` usage | `+0x1c` | `+0x38` | copy |
| `ImageParams` native handle | `+0x70` | `+0xb0` | copy |
| `ImageParams` plane count | `+0x48` | `+0x70` | copy (0 if unused) |
| `ImageParams` plane strides | `+0x4c + 4i` | `+0x74 + 4i` | copy |
| `ImageParams` plane pointers | `+0x58 + 8i` | `+0x80 + 8i` | copy |
| plugin vtable | 15 slots | 21 slots | irrelevant: the wrapper calls the donor directly, no vtable rewrite |
| provider vtable | 6 slots; slot 4 = `create`, slot 5 = `getPluginName` | same | wrapper mimics stock |

`[observed]` The Aurora `ImageParams` offsets are taken from the stock
`FilterPlugin` itself: `0xc464`–`0xc497` reads `+0x70` (native handle),
`+0x00/+0x04/+0x08/+0x1c` for `GraphicBuffer`, `+0x28/+0x30` for the metadata
`shared_ptr`, and `0xd4f0` (slot 12) maps `+0x1c/+0x20/+0x48/+0x4c/+0x58` into
`MiaFrameInfo`.

## 5. Recommended design: an Aurora-native wrapper plugin

### 5.1 Why a wrapper, not a patched donor

The previous attempts patched the donor's vtable and metadata offsets and
installed it as `com.xiaomi.plugin.filter`. That fights two problems at once:

1. the vtable orders differ (21 vs 15 slots), and
2. the donor's `ImageParams` body differs, which only shows up after the
   lifecycle problem is solved.

A wrapper removes both: it is compiled against **our** description of Aurora's
ABI, and it owns the donor object. The donor file is never registered with
`MPluginModule` and never patched.

### 5.2 What the wrapper is

```text
/odm/lib64/camera/plugins/com.xiaomi.plugin.hcmlegend.so   <- new, our code
/odm/lib64/hcm/legend_algo.so                              <- copy of donor, private
/odm/lib64/libhcm_shim.so                                  <- existing shim symbols
/odm/etc/camera/leica_filter_param_*.bin                   <- existing bundle
```

The wrapper is modelled on the stock provider/factory at the byte level, since
Aurora's mivifwk headers are not public:

`[observed]` `connect(ProviderManager*)` in the stock filter (`0xb020`):

```c
int connect(ProviderManager *pm) {
    void *p = operator new(8);                  // stateless Provider
    *(void **)p = &our_provider_vtable[0];      // vptr = table+0x10
    ProviderManager::add(pm, p);
    return 1;
}
```

The provider vtable is 6 slots (`_ZTV20FilterPluginProvider` = `0x1d090`,
64 B):

| provider slot | stock target | wrapper implementation |
| ---: | --- | --- |
| 0 | `Provider::~Provider` | no-op destructor |
| 1 | deleting destructor | no-op |
| 2 | interface version | return `PLUMA_INTERFACE_VERSION` |
| 3 | provider type | return `PluginWraperProvider` type string |
| 4 | `create()` — `0xd5d4` | allocate wrapper object, set vptr |
| 5 | `getPluginName()` — `0xd614` | return `"com.xiaomi.plugin.hcmlegend"` |

`[observed]` The engine's registry (`MPluginModule::getPluginWraperByPluginName`
@ `0x4489c`) walks a vector of providers, calls provider slot 5 for the name and
compares it byte-for-byte, then calls provider slot 4 to create the instance.
The `NodeName` in the pipeline JSON is the key. There is **no hard-coded name
table**: the string `com.xiaomi.plugin.filter` appears in no Aurora binary
(`[observed]`, grep over `odm/lib64` and the provider).

### 5.3 Wrapper vtable

Implement Aurora's 15 slots (the exact stock filter values are a safe model):

| slot | wrapper behaviour |
| ---: | --- |
| 0 | create the donor object (deferred init), remember `MiaNodeInterface` |
| 1 | no-op (return 0) |
| 2 | **translate and forward to donor `processRequest`** |
| 3 | no-op (return 0) |
| 4 | no-op (`ret`) |
| 5 | no-op (return 0) — this is the "postProcess" everybody was looking for |
| 6 | no-op (return 0) |
| 7 | destroy donor (`destroy`/`D1`) |
| 8 | read Aurora `MiaParams+0x00` metadata; return 1 for style 1/2 |
| 9 | no-op |
| 10/11 | destructors |
| 12 | no-op |
| 13 | return 0 |
| 14 | return 1 |

### 5.4 Forwarding a request

```c
int wrapper_processRequest(Wrapper *w, AuroraPRI *info) {
    // 0. first call: donor initialize with the request metadata
    if (!w->inited) {
        CreateInfo ci = {0};
        ci.procMode = w->snapshot ? 1 : 0;    // +0x44
        ci.metadata = aurora_image_metadata(info); // +0x48
        w->donor->initialize(&ci, &w->iface); // 14 qwords, may be zero
        w->inited = 1;
    }

    // 1. Aurora image params for input and output
    AImageParams *ai = *(AImageParams **)(*(char **)info + 0x28);
    AImageParams *ao = *(AImageParams **)(*(char **)((char *)info + 0x18) + 0x28);

    // 2. Nezha-shaped copies (stack or pool)
    NImageParams ni = {0}, no = {0};
    translate(ai, &ni);
    translate(ao, &no);

    // 3. Nezha-shaped containers + ProcessRequestInfo
    struct { char pad[0x28]; NImageParams *p; } ci_in  = {{0}, &ni};
    struct { char pad[0x28]; NImageParams *p; } ci_out = {{0}, &no};
    NezhaPRI pri = {0};
    pri.input  = &ci_in;        // +0x00
    pri.output = &ci_out;       // +0x18
    pri.frame  = w->frame++;    // +0x48

    // 4. forward
    return w->donor->processRequest(&pri);   // 0 = success
}
```

`translate()` maps the five fields that the GL path uses:

```c
dst->fmt    = src->fmt;        // +0x00
dst->width  = src->width;      // +0x04
dst->height = src->height;     // +0x08
dst->usage  = src->usage;      // Nezha +0x38  <- Aurora +0x1c
dst->handle = src->native;     // Nezha +0xb0  <- Aurora +0x70
dst->meta   = src->meta;       // Nezha +0x40/+0x48 <- Aurora +0x28/+0x30
dst->planes = 0;               // +0x70; not used by the GL path
```

The wrapper does not need to touch the donor's metadata path at all if it
initialises on the first request and sets the style itself:

```c
uint8_t style = read_style(info);      // stylizationType, 1=M9, 2=M3
w->donor->this->style25 = w->donor->this->style26 = style;
```

The donor's `initResource` then chooses M9/M3 from the shipped `.bin` files.

### 5.5 Loading the donor privately

Because the wrapper is a normal plugin `.so`, it can `dlopen` the donor at a
path that the plugin scanner does not visit:

```c
dlopen("/odm/lib64/hcm/libhcm_shim.so", RTLD_NOW | RTLD_GLOBAL); // symbols
void *h = dlopen("/odm/lib64/hcm/legend_algo.so", RTLD_NOW | RTLD_LOCAL);
```

* `RTLD_GLOBAL` on the shim resolves the two Aurora-missing symbols
  (`midebug::Log::catchLogEncryptLog`,
  `mialgo2::PluginUtils::printAndroidAndOfflineLog`) exactly as the existing
  `compat_shim.S` does.
* `RTLD_LOCAL` on the donor keeps its `ParamManager`, `ParamUtil`,
  `ParamTrigger` and `MiLeicaFilterPlugin` symbols from colliding with
  anything else.
* The donor's `DT_NEEDED` closure needs `libcameraopt_stub.so` and
  `libcom.xiaomi.cam.miexifmgr.so`, which the bundle already ships.

The donor object is created by allocating `0x150` bytes and calling the exported
constructor `MiLeicaFilterPlugin::MiLeicaFilterPlugin()` (`0xc2d4`). It does not
need PLUMA registration.

### 5.6 Pipeline insertion

The wrapper node must be an offline node with **no `NodeMask`**, so the engine
selects `OtherNode` and plugin slot 2. Place it in the same Gralloc chain as
the stock filter `[observed]`:

```text
MovieEffectInstance0 (Gralloc)
  -> LegendFilterInstance0  ("com.xiaomi.plugin.hcmlegend", no NodeMask)
  -> FilterInstance0        (stock LUT filter, if the payload is also set)
  -> WatermarkInstance0
```

The node entry is therefore:

```json
{ "NodeName": "com.xiaomi.plugin.hcmlegend",
  "NodeInstance": "LegendFilterInstance0" }
```

and the two links carry `BufferType: "Gralloc"`, copying the stock filter links.
Do not set `NodeMask`; an odd mask would route the node to the (stub) V2 API.

## 6. Node dispatch: verified claims and corrections

| Claim in earlier pages | Status |
| --- | --- |
| "NodeName selects the plugin" | `[observed]` yes: `NodeName` is the provider registry key and the `.so` basename. |
| "unknown NodeName picks a generic path" | `[observed]` partly: an unregistered name makes `getPluginWraperByPluginName` return null, `MiaNode::init` logs `not found pluginName` and the node is dropped. A registered name behaves like any other node; there is no special-cased name. |
| "NodeName `com.xiaomi.plugin.filter` is special" | `[observed]` no. The special behaviour is the *stock plugin's* implementation: slot 2 = LUT code, slot 5 = no-op, slot 8 reads `com.xiaomi.mivi2.renderType`, slots 13/14 = pre-process flags. The engine only knows the name as a registry key. |
| "Aurora drives the filter node through `postProcess()`, not `processRequest()`" | `[observed]` **incomplete.** Aurora calls both: `MiaNode::postProcess` (slot 5, no args, no-op for the stock filter) and `MiaNode::processRequest` (slot 2 for `NodeMask` bit 0 = 0). The LUT is applied in slot 2; flagging slot 5 is why the donor was never called. |
| "the engine never calls `MiaNodeInterface` output callbacks" | `[observed]` correct; buffers come from the pipeline via `ProcessRequestInfo`. |
| "Nezha plugin uses slot 5 for `processRequest`" | `[observed]` correct for the donor vtable (slot 5 = `0xdafc`), but the fix is not "move it to Aurora slot 5"; it is "call it from the wrapper with the V1 request". |

`[observed]` `MiaNodeFactory::CreateNodeInstance` and the `NodeMask` parser
(`MJsonUtils::parseJson2Struct` @ `0x4f7cc`, `"NodeMask"` at rodata `0x2622d`)
show that the JSON value is used for both the node class and the plugin API
selection. `[inferred]` A missing property stays zero because the parser only
writes when the property is found.

## 7. Chi metadata path for a plugin that reads M3/M9 style

`[observed]` Aurora delivers metadata two ways:

* to `isEnabled(MiaParams)`: a `MiaParams` **by value** whose first qword is
  the `MiMetadata *` (stock filter `0xc194`: `MiMetadata::find(*(char**)
  param_2)`);
* to `ProcessRequestInfo` → `ImageParams+0x28/+0x30`: a
  `shared_ptr<MiMetadata>` (stock filter `0xc6cc`–`0xc6d4`: `ldp x21, x8,
  [x10, #0x28]`).

`[observed]` The donor reads the same tags by name, so a wrapper can use
`MiMetadata::find(const char*)` directly:

| key | source | use |
| --- | --- | --- |
| `com.xiaomi.sessionparams.stylizationType` | app request, written by the module bridge | 1 = M9, 2 = M3; the selector the bundle already patches the donor to use |
| `com.xiaomi.sessionparams.legendMode` | older Nezha key | not set by the Aurora app |
| `com.xiaomi.mivi2.renderType` | app render payload gate | stock filter `isEnabled`; not needed for the donor |
| `com.xiaomi.mivi2.render` | JSON `{lutNames, renderType, …}` | stock filter `processRequest`; unrelated to the `.bin` look |

`[observed]` `MiMetadata::find` exists in Aurora's
`libcom.xiaomi.metadatautils.so` in both the `char const*` and `uint32_t`
overloads, so no tag-number map is needed for the wrapper.

## 8. Remaining risks and unknowns

| Risk | Why it matters | Mitigation |
| --- | --- | --- |
| `ImageParams` translation is inferred | a wrong stride/usage gives striped or black output | log the five source fields once per capture and compare against the stock filter's `input: %dx%d,sw=…` line at `0xc30c` |
| videoprocess ABI | the donor is built against Nezha's `libMiPhotoFilter.so` (1.33 MB); Aurora's is 1.08 MB and lacks the newer `createFilter(VideoFormat,VideoFormat,VideoFormat,bool)` overload. The donor imports only the two-argument overload, which Aurora exports, but the returned `VideoFilterInterface` vtable may still differ | first run with Aurora's lib; if it crashes, ship Nezha's `libMiPhotoFilter.so` under a private SONAME and a patched `DT_NEEDED` (tooling exists) |
| GL/EGL thread context | `candyProcess` creates GL work; it must run on the engine's algorithm thread | the wrapper is called on that same thread as the stock filter, so it inherits the working environment |
| output-buffer negotiation | the engine must give the new node a Gralloc output at the right size | copy `FilterInstance0`'s link/format and keep the node in the same position |
| first-request init | donor `initialize` reads `CreateInfo+0x48` metadata | defer it to the first `processRequest`, where a real `MiMetadata` is available; do not call it at node init |
| early-exit/scheduling of the previous donor node | the old build logged `initialize`/`isEnabled`/`preProcess` but no `processRequest`; root cause not proven | the wrapper can log every slot; also compare `NodeMask`/link position with the stock filter |
| SELinux | new `.so` under `hal_camera_default` | Magisk file contexts; check `avc: denied` |

`[unknown]` Why the previous in-place donor (correct slot 2, correct name) did
not reach `processTask`. Candidates: the node was pruned after `preProcess`
because its output format negotiation did not produce a Gralloc buffer, or the
node was not in the pruned executed set. The wrapper experiments will answer
this because they can log without touching the provider.

## 9. Implementation plan

1. **Write `tools/hal-port/aurora/hcm_legend_plugin/`**
   * `wrapper.c`/`wrapper.cpp`: provider (`connect`, 6-slot table), wrapper
     object (15-slot table), donor loader, `translate()`, style read.
   * `shim.c`: the symbols the donor needs that Aurora lacks; reuse
     `compat_shim.S` and `compat_shim_connect.c` where possible.
   * `build.sh`: NDK clang, `-fPIC -shared -Wl,-z,max-page-size=16384`,
     `DT_SONAME=com.xiaomi.plugin.hcmlegend.so`.
2. **Bundle changes** in `tools/hal-port/aurora/build_bundle.sh`:
   * ship the donor as `/odm/lib64/hcm/legend_algo.so`;
   * ship the wrapper as `/odm/lib64/camera/plugins/com.xiaomi.plugin.hcmlegend.so`;
   * keep `libcameraopt_stub.so` and `libcom.xiaomi.cam.miexifmgr.so`;
   * drop the donor binary patches (`patch_plugin.py`,
     `patch_plugin_framework_abi.py`) for this path — the wrapper does not need
     them.
3. **Pipeline patch**: add `LegendFilterInstance0` with no `NodeMask` and the
   two Gralloc links (model on `patch_leicasnapshot.py`); do it for
   `satsnapshot.json` first (the graph the device actually uses).
4. **Static verification**: the wrapper exports `connect`, has no
   `DT_NEEDED` on the donor (it is loaded with `dlopen`), and its provider
   table has the stock shape.
5. **Device test (user)**: log wrapper slot calls and the five translated fields
   for one capture; then compare full-res JPEG with the working LUT-slot
   capture. A correct run shows `LegendFilter processRequest`, the donor's
   `param_lut` line, and a 4096x3072 JPEG.
6. **Fallback**: if the videoprocess ABI is the blocker, keep the LUT-slot
   approximation from [09](09-engine-lut-injection.md) as the shipped feature.

## 10. Verdict

* **Feasibility: high for the lifecycle, medium for the pixel ABI.**
  `[observed]` The engine's V1 request is structurally the same object the donor
  consumes; only `ImageParams` (five fields for the GL path) and the metadata
  location differ, and both are writable from a wrapper.
* **A `postProcess` adapter is not viable** in the literal sense: slot 5 is a
  no-argument teardown hook and carries no pixels. A wrapper that implements
  slot 2 is required.
* **No further donor binary patch is needed** if the wrapper sets the style and
  supplies the metadata at first request.
* **The single biggest unknown is not the ChiX ABI but `libMiPhotoFilter`**:
  the donor's `candyProcess` calls into Aurora's older videoprocess
  implementation. That is a runtime test, not a static blocker.

## Appendix: key addresses

Aurora `libmialgoengine.so`:

```text
0x4489c  MPluginModule::getPluginWraperByPluginName   provider slot5 name, slot4 create
0x4f7cc  MJsonUtils::parseJson2Struct                 NodeMask parser
0x6b270  MiaOfflineSession::postProcess()             per-node postProcess loop
0x8a2cc  MiaNode::init(NodeItem, MiaCreateParams*)    plugin lookup, initialize
0x8c254  MiaNode::deInit                              destroy + D0
0x8c2e0  MiaNode::postProcess()                       slot 5, no args, return ignored
0x8c310  MiaNode::preProcess(PostMiaPreProcParams*)
0x8cb6c  PluginWraper::needPreProcess(MiaParams)      slots 14/13/8
0x8cd00  MiaNode::isEnable(MiaParams)
0x8cfcc  MiaNode::getOutputBuffer
0x8e968  MiaNode::processRequest (V2 overload)        plugin slot 2
0x93880  MiaNode::processRequest (V1 overload)        plugin slot 3
0x94b48  MiaNode::flush(bool)                         slots 6/9
0x9b0e8  SigframeNode::processTask                    NodeMask bit0 switch
0x9caf0  OtherNode::processTask                       NodeMask bit0 switch
0x9e440  MiaNodeFactory::CreateNodeInstance           NodeMask class select
0xb2384  PipelinePruner::checkNodeEnable              node vtable+0x20
```

Aurora `com.xiaomi.plugin.filter.so`:

```text
0x1d008  _ZTV12FilterPlugin (15 slots)
0x1d090  _ZTV20FilterPluginProvider (6 slots)
0xb020   connect(ProviderManager*)
0xc08c   initialize          (slot 0)
0xc194   isEnabled           (slot 8)
0xc218   processRequest      (slot 2, LUT code)
0xd4d0   processRequestV2    (slot 3, stub)
0xd4dc   "return 0" stub     (slot 5 = postProcess, slot 1, slot 13)
0xd4f0   ImageParams→MiaFrameInfo helper (slot 12)
0xd574   "return 1" stub     (slot 14)
0xd5d4   provider create
0xd614   provider getPluginName ("com.xiaomi.plugin.filter")
```

Nezha `com.xiaomi.plugin.mileicafilter.so`:

```text
0x208a8  _ZTV19MiLeicaFilterPlugin (21 slots, vptr 0x208b8)
0xc2d4   constructor (object 0x150 B)
0xc620   initialize
0xcc54   initResource
0xd714   isEnabled
0xd990   preProcess
0xdafc   processRequest(ProcessRequestInfo*)   <- the algorithm driver
0xeae4   fillMetaDataInfo
0x10180  candyProcess
0x10f28  processRequest(ProcessRequestInfoV2*)  <- log-only stub
0x11200  destroy
0x11f20  getImageInfo (slot 18)
```
