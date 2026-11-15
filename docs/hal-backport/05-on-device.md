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

The replacement stack is not viable: each Nezha core library pulls another
Aurora-missing dependency. The stable state is therefore the v5a minimal
overlay, with `legendST` and M10R disabled.

### Current device state

The `hypercam-aurora-legend` KernelSU module is **disabled**, `/odm` is stock,
and the stock camera/provider are running. The stable v5a bundle is retained at
`/tmp/opencode/v5a.zip`; the later v5b-v5f compatibility experiments are kept
only as evidence. No experimental module is active.

The live `/odm` overlay is fixed when the module is mounted: editing files in
`/data/adb/modules/.../system/odm` while the module is running does **not**
change `/odm` contents. Every plugin patch must be packaged into the zip and
installed with a reboot.

## What remains

The remaining blocker is Aurora's `MiaPluginModule` registration, not the
pipeline JSON and not SELinux. The next step is to port or patch the Aurora
plugin registry so it registers `com.xiaomi.plugin.mileicafilter` (and then
`legendST`), or to replace the older Aurora engine with a compatible Nezha
engine. Until that succeeds, M3/M9 do not render even though the picker and the
SAT pipeline accept the node.

