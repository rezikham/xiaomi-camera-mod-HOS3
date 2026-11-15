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

The device rebooted and is reachable on Tailscale, but ADB over TCP did not come
back automatically:

```console
$ ping -c 1 100.104.2.7
64 bytes from 100.104.2.7: icmp_seq=1 ttl=64 time=56.7 ms

$ adb connect 100.104.2.7:5555
failed to connect to '100.104.2.7:5555': Connection refused

$ adb mdns services
List of discovered mdns services
```

Wireless debugging is off after the reboot, so the device cannot be queried
until it is re-enabled. No claim is made yet that the overlay mounts, that the
plugins load, or that M3/M9 render.

### Boot and overlay visibility

_Pending._

### Camera provider and plugin load

_Pending._

### M9/M3 capture

_Pending._

### Regression and rollback

_Pending._
