# On-device Aurora validation

This page records the first execution attempt on a physical Xiaomi 14 Ultra.
It is deliberately separate from the static analysis pages: commands and
outputs here were run against the device, while the rest of `docs/hal-backport`
is derived from firmware images.

## Device inventory

| Property | Value |
| --- | --- |
| ADB serial | `100.104.2.7:5555` (Tailscale) |
| Product / device | `aurorapro` / `aurora` |
| Model | `24031PN0DC` (Xiaomi 14 Ultra) |
| Android | 16 (SDK 36) |
| HyperOS | `OS3.0.304.0.WNAMIXM` |
| Root | KernelSU `3.2.5-54-gcfac3be3`, `u:r:ksu:s0` |
| Mount manager | Hybrid Mount 6.2.3 (KernelSU metamodule) |
| Xposed | Vector (Zygisk) 3114, `/data/adb/lspd` present |
| Camera app | `6.7.000280.0` (versionCode 670002800); `6.4.000430.0` also installed |

Commands:

```console
$ adb devices -l
100.104.2.7:5555  device product:aurorapro model:24031PN0DC device:aurora

$ adb shell 'getprop ro.product.device; getprop ro.build.version.release; \
    getprop ro.mi.os.version.incremental; su -c id'
aurora
16
OS3.0.304.0.WNAMIXM
uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0

$ adb shell 'su -c "ksud -V"'
ksud 3.2.5-54-gcfac3be3 (uapi: 2)

$ adb shell 'su -c "ls /odm/lib64/camera/plugins | grep -E \"mileica|legend\""'
(no output)
```

The last command confirms the stock HAL has neither `mileicafilter` nor a
Legend plugin, matching the static inventory in `01-aurora-vs-sources.md`.

## Installation

`/odm` is a read-only EROFS mount:

```console
$ adb shell 'mount | grep " /odm "'
/dev/block/dm-16 on /odm type erofs (ro,seclabel,relatime,user_xattr,acl,cache_strategy=readaround)
```

Therefore the bundle is repackaged as a KernelSU module with `system/odm/...`
paths and installed with `ksud`:

```console
$ tools/hal-port/aurora/package_module.py /tmp/opencode/aurora-legend-port-final
module: /tmp/opencode/aurora-legend-port-final.zip
files:  23
bytes:  50203154

$ adb push /tmp/opencode/aurora-legend-port-final.zip /data/local/tmp/
$ adb shell su -c 'ksud module install /data/local/tmp/aurora-legend-port-final.zip'
- Module size: 76.71 MB
- Installing to /data/adb/modules_update/hypercam-aurora-legend
- Extracting module files
- Hybrid Mount metainstall
- Module installed successfully!
```

`ksud module list` reports the module as `enabled: true`. The APK is installed
separately:

```console
$ adb install -r build/outputs/apk/debug/xiaomi-camera-mod-debug.apk
Success
$ adb shell 'dumpsys package io.rezikham.hypercameramod | grep version'
versionCode=100 ... versionName=1.0.0
```

A reboot is required because the module is staged in
`/data/adb/modules_update` and because the camera provider reads the pipeline
JSON at startup.

## Results

The device rebooted and was reachable over USB and Tailscale. The first
installation (v2, full Nezha plugin stack) reached the camera HAL and showed
that Aurora selects the SAT pipeline for ordinary Leica captures:

```text
ChiX: OnProcessCaptureResult() sat_snapshot, frame=..., capture done
```

The stock Aurora `satsnapshot.json` is the pipeline that must contain the
Legendary nodes; the earlier `leicasnapshot.json`-only port was on the wrong
path.

### v5a: stable minimal overlay

To separate a pipeline problem from a plugin problem, v5a contains only
`mileicafilter`, the four Leica parameter files, the three compatibility
libraries, and one `LeicaFilterInstance0` node in both `leicasnapshot.json` and
`satsnapshot.json`. It does **not** contain `legendST`, style-transfer models,
or replacement core libraries.

Result: the camera app and provider stay alive, Leica Authentic remains in the
style panel, and a capture completes:

```text
MCAM_MiCamera2: takePicture CaptureModule ... mid = 163
ChiX: OnProcessCaptureResult() sat_snapshot, ... capture done
```

The plugin is still not processed:

```text
MiaPluginModule.cpp:28 getPluginWraperByPluginName()error not found pluginName: com.xiaomi.plugin.mileicafilter
MiaOfflineSession.cpp:1199 initAllNodes()node:com.xiaomi.plugin.mileicafilter Init failed
```

No `MiaDLibrary` error is emitted and no `avc: denied` appears, so the node is
reached but the Aurora `MiaPluginModule` registry does not contain the Nezha
plugin.

### Symbol work

The Nezha plugins import two symbols that Aurora's older libraries do not
export:

```text
midebug::Log::catchLogEncryptLog(...)
mialgo2::PluginUtils::printAndroidAndOfflineLog(...)
```

Tested fixes and results:

| Attempt | Result |
| --- | --- |
| Add a `DT_NEEDED` shim library | Android linker did not pick up the added dependency |
| `wrap.vendor.camera-provider=LD_PRELOAD=...` | property was set, but the provider did not load the shim |
| Rewrite the missing dynsym entries to a local `ret` | symbol table showed `T`, but `dlopen` still reported `cannot locate` |
| Patch the `catchLogEncryptLog` PLT relocation/stub to no-op | `MiaDLibrary` error disappeared, but registration still failed |
| Replace Aurora `libcom.xiaomi.debug.so` with Nezha's | provider crash loop: missing `libcom.xiaomi.threadpool.so`, then `libabnormal_img_dectect.so`, then `libcamera.xiaomi.catchlog.so`, then `camlog::send_message_to_mqs` |
| Patch `miexifmgr` offline globals and `catchLogEncryptLog` | progressed to `DFXEvent::setSkynetMessage`; the DFX relocation has no matching PLT stub |
| Add Nezha `com.xiaomi.camdfx.so` | broke `camera.xiaomi.so`: `DFXEvent::putString` is not exported by Aurora's camdfx |
| Replace `satsnapshot.json` with the Leica graph | legacy `MiaDLibrary` load is attempted, but the unpatched plugin still fails on `catchLogEncryptLog` |
| Add an AArch64 shim for the missing logging/DFX/filesystem/libc++ symbols | `MiaDLibrary` load error disappears, but the SAT node is not in the executed node list |
| Ship Aurora's own `libc++.so` as a private `libcxx.so` and retarget the plugin | plugin loads; `MiaDLibrary` reports no missing symbol at all |
| Export a `connect` trampoline from the shim that forwards to `miviconnect` | plugin registers; `MiLeicaFilterPlugin::initialize()` executes |
| Rename the plugin's `miviconnect` dynstr entry to `connect` | wrong: the GNU hash still hashes `miviconnect`, so `dlsym` misses and returns libc's `connect()` |

The replacement stack is not viable: each Nezha core library pulls another
Aurora-missing dependency. The stable state is therefore the v5a minimal
overlay, with `legendST` and M10R disabled.

### Current device state

The `hypercam-aurora-legend` KernelSU module is **disabled**, `/odm` is stock,
and the stock camera/provider are running. The stable v5a bundle is retained at
`/tmp/opencode/v5a.zip`; the later v5b-v5l compatibility experiments and the v6
loader experiments are kept only as evidence. No experimental module is active.

The v6 experiments added `libshim.so` (Xiaomi DFX symbols plus the `connect`
trampoline) and `libcxx.so` (Aurora `/system/lib64/libc++.so` with a private
SONAME). The tools that reproduce them live next to the other Aurora scripts:

- `tools/hal-port/aurora/compat_shim.S`
- `tools/hal-port/aurora/compat_shim_connect.c`
- `tools/hal-port/aurora/build_compat_shim.sh`
- `tools/hal-port/aurora/patch_plugin_runtime_deps.py`

The live `/odm` overlay is fixed when the module is mounted: editing files in
`/data/adb/modules/.../system/odm` while the module is running does **not**
change `/odm` contents. Every plugin patch must be packaged into the zip and
installed with a reboot.

## Loader and registration result

Once the private libc++ and the `connect` trampoline are in place, the node is
no longer skipped and the plugin genuinely executes. The observable chain is:

```text
MiaPluginManager.cpp:65 load()Nothing registered by plugin ...   # before connect bridge
    -> disappears
MiLeicaFilterPlugin::initialize(CreateInfo*, MiaNodeInterface)    # executes
    -> crash inside initialize
```

The remaining failures are ABI mismatches between the Nezha `mivifwk` build and
Aurora's `mivifwk-aurora`, not loader problems:

1. **`CreateInfo` layout.** Aurora's `MiaNode::init` builds `CreateInfo` on the
   stack and stores ints at `+0x48`/`+0x4c`; the Nezha plugin reads a
   `MiMetadata*` from `CreateInfo+0x48` and calls `MiMetadata::find()`. The
   result is:

   ```text
   signal 11 SIGSEGV, fault addr 0x0000100000000049
   #00 pthread_mutex_lock+16
   #03 MiMetadata::find(unsigned int)
   #05 MiLeicaFilterPlugin::initialize(...)+296
   ```

   Aurora delivers metadata through `MiaParams` (its first field is a
   `MiMetadata*`), not through `CreateInfo`.

2. **Plugin vtable signatures.** With the metadata reads skipped in
   `initialize`, the next call is:

   ```text
   #00 getCustomFrameOrder(std::__1::shared_ptr<MiMetadata>)+8  (plugin)
   #01 PluginWraper::needPreProcess(MiaParams)+208              (engine)
   ```

   The engine calls the slot expecting a different return convention than the
   Nezha build implements (the fault is a store through a garbage sret pointer
   in `x8`). The virtual interface itself has diverged between the two
   `mivifwk` versions.

Both are binary-ABI differences. They cannot be fixed by symbol shims or by
patching one or two field loads; either the donor plugin must be built against
Aurora's `mivifwk`, or a wrapper plugin compiled against Aurora headers must
marshal Aurora structs into the Nezha layout and forward the algorithm calls.

## What remains

The plugin now loads, registers, and executes. The blocker is the
`mivifwk` ABI version: Nezha's plugin was built against a newer framework than
Aurora's engine provides (`CreateInfo` metadata location and plugin virtual
signatures differ). Next options, in order:

1. Obtain a `com.xiaomi.plugin.mileicafilter.so` built against an
   Aurora-compatible `mivifwk` (for example from a firmware whose engine matches
   `mivifwk-aurora`), then reuse the loader shim unchanged.
2. Build a thin Aurora-native plugin that implements Aurora's plugin vtable and
   forwards to the Nezha algorithm/params.
3. Only if neither is possible, keep the app-side compatibility fallback and
   treat M3/M9 as unsupported on Aurora.


## mivifwk ABI bridge (working path)

The donor plugin is built against a newer `mivifwk` than Aurora's engine. The
differences that block execution are structural, not symbol-level:

| Interface | Nezha/Madrid | Aurora |
| --- | --- | --- |
| `MiaParams.metadata` | `+0x18` | `+0x00` |
| `CreateInfo.metadata` | `+0x48` (`MiMetadata*`) | absent (metadata travels in `MiaParams`) |
| plugin vtable `processRequest` | slot 5 | slot 2 |
| plugin vtable `isEnabled` | slot 12 | slot 8 |
| plugin vtable `flushRequest` | slot 10 | slot 6 |
| plugin vtable `destroy` | slot 11 | slot 7 |

`tools/hal-port/aurora/compat_vtable.c` runs as a `libshim.so` constructor. It
finds the donor module with `dl_iterate_phdr`, `mprotect`s the `.data.rel.ro`
page and rewrites the vtable into Aurora's order. It also wraps
`isEnabled`/`processRequest` with logging and mirrors the style byte into the
two fields the plugin's `initResource` reads (`this+0x25`, `this+0x26`).
`persist.vendor.camera.hypercam.style` (1 = M9, 2 = M3) forces a style for
testing; the vendor property is used because the provider's SELinux domain
cannot read `persist.sys.*`.

`tools/hal-port/aurora/patch_plugin_framework_abi.py` patches the two
`MiMetadata::find()` loads in `isEnabled` from `+0x18` to `+0x00`, and turns the
metadata block in `initialize` into a branch (Aurora's `CreateInfo` has no
metadata pointer).

### Execution evidence

```text
HyperCamVt: isEnabled r=1 style=1/1 forced=1
HyperCamVt: processRequest self=0xb4000070a4263650 info=0x6c6955c230
```

No tombstone, and the capture completes:

```text
ChiX: OnProcessCaptureResult() sat_snapshot ... capture done
```

Earlier stages (before the vtable bridge) crashed exactly as predicted:
`initialize+296` on `MiMetadata::find`, then `getCustomFrameOrder+8`, then
`processRequest+124` when `postProcess` still pointed at the donor's slot 5.

### Known remaining issue

With the forced style the pipeline produces a 1440x1080 JPEG instead of
4096x3072, and `processRequest` is not called on every capture (the app's
quick-shot path skips it). The node is now wired as `FilterInstance0` with
`MovieEffectInstance0 -> FilterInstance0 -> WatermarkInstance0`; the output port
format/size negotiation still needs to match what the donor expects, and the
M9/M3 parameter selection inside `initResource` must be verified against the
shipped `leica_filter_param_m9_*.bin` / `_m3.bin` files.

### Node dispatch is by NodeName, not NodeInstance

Renaming `FilterInstance0` to the donor plugin name was wrong: the engine keys
the node behaviour on `NodeName`. With an unknown name the node follows the
generic plugin path (`initialize` then `postProcess()` with no arguments). The
filter path is only taken for `NodeName == "com.xiaomi.plugin.filter"`.

The working arrangement is therefore to keep the stock graph untouched and
make the donor **register under the stock name**:

- `compat_vtable.c` patches the provider vtable's `getPluginName()` slot to
  return `"com.xiaomi.plugin.filter"` (allocation per call; the caller frees the
  returned long string).
- The donor is installed as
  `/odm/lib64/camera/plugins/com.xiaomi.plugin.filter.so`, replacing the stock
  filter plugin.
- The stock `satsnapshot.json` is used unchanged.

With that, the engine logs:

```text
HyperCamCt: connect called pm=... ; miviconnect -> 1
HyperCamVt: initialize -> 0
HyperCamVt: isEnabled r=1 style=1/1 forced=1
HyperCamVt: preProcess self=... info=... -> 0
```

### Remaining failure at the buffer stage

When `isEnabled` returns 1, the app saves only its quick-shot fallback
(`combine parallel task data to small jpeg`, 1440x1080). With
`persist.vendor.camera.hypercam.style=0` the same graph produces a full
4096x3072 JPEG, so the regression is tied to the node becoming active.

`processRequest` is still never called; only `initialize`, `isEnabled` and
`preProcess` are. The next target is the `PreProcessInfo`/buffer handoff: the
donor's `preProcess` returns 0 but does not appear to request/set its output
buffers through `MiaNodeInterface`, so the offline stage never materialises the
full-size image. That is the next ABI surface to map (image map, output buffer
callbacks, and the `ProcessRequestInfo` metadata at `+0x48`).

### Donor graph order (from the Nezha dump)

The donor's own `satsnapshot.json` puts the mileica node **upstream of the
stock filter**, not in its place:

```text
SuperMoonInstance0 -> LeicaFilterInstance0 (mileicafilter)
                   -> FilterInstance0 (com.xiaomi.plugin.filter)
                   -> MovieEffectInstance0 -> WatermarkInstance0
```

The current Aurora experiment instead replaced `FilterInstance0` with the donor
(or appended it after the filter), which is the wrong relative position. The
next test is to keep the stock `FilterInstance0` node name and insert the donor
between the node that feeds the filter and the filter itself, while still
registering the donor under its own name (`com.xiaomi.plugin.mileicafilter`).

This also means the vtable registration as `com.xiaomi.plugin.filter` is only a
diagnostic; the production bundle should keep the two plugins distinct.

### Shim marker result: mprotect is denied

`compat_vtable.c` writes a marker to `/data/vendor/camera/hcm_shim.txt` so shim
execution is visible without logcat. A forced-style boot produced:

```text
start 0 0
base 7a2f94d000 0
mprotect_fail 0 0
connect b400007c2192e948 0
```

So the shim is a real dependency of the donor, `dl_iterate_phdr` finds the
module, and `connect()` runs. The vtable patch fails only because `mprotect()`
on the donor's `.data.rel.ro` page is refused (Android 16 RELRO/sealing), which
is why `getCustomFrameOrder+8` still crashes.

Next fix: replace the mprotect-in-place patch with an anonymous
`MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED` remap of that 4 KiB page (copy the
original contents first, then write the remapped slots), or relocate the vtable
entries in the file. The patch logic itself is known-good; only the write
primitive is blocked.

### File-level vtable patch works

Because `mprotect()` on the RELRO page is denied, the vtable is now patched
offline in the donor file instead (`/tmp/opencode/patch_vtable_file.py`):

- `slot 2 -> 0xdafc` (`processRequest`)
- `slot 3 -> 0x10f28` (`processRequestV2`)
- `slot 7 -> 0x11200` (`destroy`)
- `slot 8 -> 0xd714` (`isEnabled`)
- `initialize` writes `this+0x25 = this+0x26 = 1` (M9) and skips the
  `CreateInfo` metadata reads
- `isEnabled` returns 1

The four patched vtable words are RELR addends, so the loader rebases them
normally. With this build the previous `getCustomFrameOrder+8` SIGSEGV is gone.
A forced capture still produced the 1440x1080 quick-shot JPEG (56 KB) and no
full-resolution image, so the pipeline still does not consume the node's
output; `processRequest` execution has not yet been observed directly.

### processRequest is not invoked (atime proof)

`MiLeicaFilterPlugin::processRequest()` calls `initResource()`, which reads the
`leica_filter_param*.bin` files. Their atime was sampled around a forced
capture on the file-patched build:

```text
BEFORE 3659589 3659589 3659589 3659589
AFTER  3659589 3659589 3659589 3659589
```

No read happened, so `processRequest`/`initResource` never ran. The capture
still produced the 1440x1080 quick-shot (57,884 bytes) and there was no crash.

The node goes `initialize` -> `isEnabled` -> `preProcess` and is then dropped
before the offline stage. The likely reason is that the donor's `preProcess`
does not ask the engine for an output buffer through the `MiaNodeInterface`
callbacks, so the node has no output and is not scheduled for `postProcess`.
The next step is to map `MiaNodeInterface` (`requestOutputBuffer`,
`setResultBuffer`, `setOutputFormat`) and have the shim issue the output request
during `preProcess`.
