# The ChiX wrapper plugin: implementation notes

This page records what it took to build an Aurora-native wrapper plugin and how
far it got on the device. The design is in
[11-chix-adapter.md](11-chix-adapter.md); this is the engineering log.

Status: the wrapper is **loaded, registered and called** by the engine
(`connect` → `provider create` → `initialize` → `isEnabled` → `processRequest`).
The remaining blocker is the donor's C++ runtime, not the plugin ABI.

## Files

| File | Purpose |
| --- | --- |
| `tools/hal-port/aurora/hcm_legend_plugin/hcm_legend.c` | the wrapper (freestanding C) |
| `tools/hal-port/aurora/build_legend_wrapper.sh` | builds the `.so` and the KernelSU bundle |
| `tools/hal-port/aurora/patch_legend_snapshot.py` | inserts `LegendFilterInstance0` |
| `out/hcm-legend.zip` | the installable module |

Layout installed by the bundle:

```text
/odm/lib64/camera/plugins/com.xiaomi.plugin.hcmlegend.so   wrapper (NodeName)
/odm/lib64/com.xiaomi.plugin.mileicafilter.so              donor, private
/odm/lib64/libhcm_shim.so                                  midebug/DFX symbols
/odm/lib64/libcameraopt_stub.so, libcom.xiaomi.cam.miexifmgr.so, libmetatagwrapper.so
/odm/etc/camera/leica_filter_param*.bin
/odm/etc/camera/xiaomi/{normal,leica,sat,superhd}snapshot.json   + LegendFilterInstance0
```

## What the ABI work actually required

### 1. `getPluginName()` returns `std::string` by value

`ProviderManager` compares the name returned by provider slot 5 and then frees
the string. The stock implementation (`0xd614`) is:

```text
mov  x8, <sret pointer>          ; provided by the caller
new(0x20); copy name
str  #0x21, [x8]                 ; libc++ long form: capacity | 1
str  #0x18, [x8, #8]             ; size
str  ptr,  [x8, #0x10]           ; data
```

Returning a `char*` leaves the `std::string` uninitialised, so the registry
lookup misses (`error not found pluginName: …`) and the later destruction
aborts with `Scudo ERROR: invalid chunk state`. Returning a static buffer also
aborts, because the caller frees it.

The wrapper therefore builds the libc++ long form itself:

```c
typedef struct { u64 cap; u64 size; char *data; } hcm_string;
/* cap = length | 1, data = malloc(len + 1) */
```

This only works for names longer than 22 bytes, which is the case here
(`com.xiaomi.plugin.hcmlegend`).

### 2. The provider vtable can be cloned, the plugin vtable cannot

`_ZTV20FilterPluginProvider` is a **weak, exported** symbol in the stock filter
plugin, so the wrapper copies slots 0–5 from it and overrides only:

```text
slot 4  create()          -> wrapper object
slot 5  getPluginName()   -> allocated std::string
```

That keeps `PLUMA_INTERFACE_VERSION` / `PLUMA_PROVIDER_TYPE` byte-for-byte
correct without knowing their values. The plugin vtable is Aurora's own 15-slot
order: slot 2 is `processRequest(PRI*)`, slot 5 is the no-op `postProcess()`.

### 3. A standalone `.so` must declare its libc dependencies

The engine loads plugins with a namespace whose global scope does not expose
`malloc`/`dlopen` to a library that declares no `DT_NEEDED`. The wrapper must be
linked against the device's bionic (`-L … -lc -ldl -llog`) and against
`libcom.xiaomi.pluginutils.so`, which is where
`mialgo2::ProviderManager::add` lives.

The donor's missing `midebug::*` symbols are solved by making the shim and the
donor **`DT_NEEDED` entries of the wrapper**, so all three land in the same
link group:

```text
NEEDED libc.so libdl.so liblog.so libcom.xiaomi.pluginutils.so
NEEDED libhcm_shim.so com.xiaomi.plugin.mileicafilter.so
```

`dlopen` of the shim with `RTLD_GLOBAL` was **not** enough: the donor is opened
`RTLD_LOCAL`, and its undefined symbols were still resolved only from the global
scope.

### 4. Node placement: `normalsnapshot.json` matters

Photo mode on this build uses `normalsnapshot.json`. Patching only
`leicasnapshot.json`/`satsnapshot.json` yields a node that is never created.
The patcher now handles `normal`, `leica`, `sat` and `superhd`.

### 5. KernelSU labels module files `system_file`

`hal_camera_default` cannot read `system_file` under `/odm`, so the plugin
fails with `dlopen failed: … not found` (bionic masks EACCES). `chcon` on the
overlay fails (`Read-only file system`). The bundle ships a `sepolicy.rule`
using **magiskpolicy syntax, no colon**:

```text
allow hal_camera_default system_file file { open read getattr map execute }
allow hal_camera_default system_file dir { open read getattr search }
```

The rule is applied at boot, or live with
`ksud sepolicy apply /data/local/tmp/legend.rule` (the colon form
`system_file:file` is rejected by `ksud sepolicy check`).

### 6. Failure must disable the node, not return success

With `isEnabled` returning 1 and `processRequest` returning 0 without writing
the output buffer, the JPEG comes out corrupted (`mean |R-G| = |G-B| = 135`).
`isEnabled` now loads the donor first and returns 0 when that fails, which keeps
the capture a clean pass-through.

## Where it stands after the private libc++

With `hcicxx.so` in the bundle and the donor repointed at it, the whole chain
runs on the device:

```text
HCM-Legend: donor loaded vt=0x7965ab28b8
HCM-Legend: isEnabled style=2
HCM-Legend: pri in=0x… out=0x… ai=0x… ao=0x…
HCM-Legend: donor initialized style=2
HCM-Legend: native in=0x1200000 out=0x1200000      <- wrong
```

and then the provider dies inside the donor:

```text
#00 libc++.so
#01 com.xiaomi.plugin.mileicafilter.so
      MiLeicaFilterPlugin::candyProcess(FilterFrameParam*, FilterFrameParam*,
                                        VideoFormat, param_lut_t*, param_shading_float_t*)+428
#02 MiLeicaFilterPlugin::processRequest(ProcessRequestInfo*)+2308
#03 com.xiaomi.plugin.hcmlegend.so plugin_process_request+720
#04 libmialgoengine.so MiaNode::processRequest(...)+4428
```

Cause: `trying to execute non-executable memory` inside libc++, reached from
`candyProcess`. The translated `ImageParams` is the suspect: the field our
wrapper copies as the native handle reads `0x1200000` for both input and output,
which is not a pointer. Aurora's `ImageParams` body differs from the Nezha one
the donor expects, so the wrapper forwards a wrong handle and `candyProcess`
builds a bogus `GraphicBuffer`.

Next step before touching the algorithm: dump the first `0xC0` bytes of the
Aurora input/output `ImageParams` from `plugin_process_request` and identify the
real `native_handle*` word (it will be a `0xb4…`/`0x7…` pointer), then fix
`translate()`. The rest of the five-field translation (`fmt`, `width`,
`height`, `usage`) already lines up with the observed `fmt=0x23`, `4096x3072`.

With `persist.vendor.camera.hypercam.style` set to `0`, `isEnabled` returns 0,
the node is a pass-through, and the capture path is unaffected.

## Remaining blocker

### libc++ (solved)

Aurora's `/system/lib64/libc++.so` predates `std::__1::__fs::filesystem`, so
the donor failed to `dlopen` with

```text
cannot locate symbol
  "_ZNSt3__14__fs10filesystem8__statusERKNS1_4pathEPNS_10error_codeE"
  referenced by "/odm/lib64/com.xiaomi.plugin.mileicafilter.so"
```

Solved with `tools/hal-port/aurora/patch_private_libcxx.py`: the Nezha
`libc++.so` is installed as `/odm/lib64/hcicxx.so` with its SONAME rewritten,
and the donor's `DT_NEEDED` entry is repointed to it. Both names are nine
characters, so the patch is a byte-for-byte dynstr edit (no `patchelf` here).

### Pixel ABI (open)

See "Where it stands after the private libc++" above: the donor executes and
faults in `candyProcess` because the translated `ImageParams` is incomplete.

## Device state used for the test

```text
style force : persist.vendor.camera.hypercam.style = 2 (M3), 1 (M9), 0 (off)
node        : LegendFilterInstance0 in normal/leica/sat/superhd snapshots
engine log  : HCM-Legend: connect registered / provider create / plugin initialize
snapshots   : stock filter node kept, wrapper inserted in front of it
```

The wrapper leaves stock filters untouched: it is an additional node that
passes frames through whenever `isEnabled` is 0.
