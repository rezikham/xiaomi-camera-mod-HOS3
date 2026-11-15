> **On-device status (2026-11-15):** the full `mileicafilter` + `legendST`
> bundle reaches the SAT pipeline but Aurora's `MiaPluginModule` does not
> register the Nezha plugin. The stable bundle is the minimal
> `mileicafilter`-only variant; see [05-on-device.md](05-on-device.md). Do not
> enable the full bundle on a daily device.

# Aurora Legend port bundle

This is the first implementation of the HAL backport. It is a file overlay plus
one small plugin binary patch, not a replacement of Aurora's camera provider.

## What the bundle contains

`tools/hal-port/aurora/build_bundle.sh` copies these files from
`970/extracted_nezha/odm`:

| Destination | Size (bytes) | Role |
| --- | ---: | --- |
| `odm/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so` | 151,712 → patched | M3/M9 Leica parameters |
| `odm/lib64/camera/plugins/com.xiaomi.plugin.legendST.so` | 85,584 | neural style transfer |
| `odm/lib64/libmialgo_styletrans.so` | 13,668,744 | style-transfer engine |
| `odm/lib64/libLMSceneSeg.so` | 8,238,656 | scene-segmentation model runtime |
| `odm/lib64/libcom.xiaomi.cam.miexifmgr.so` | 483,272 | EXIF/maker-note support |
| `odm/lib64/libcameraopt_stub.so` | 134,008 | cameraopt compatibility |
| `odm/lib64/libmetatagwrapper.so` | 68,144 | metadata wrapper |
| `odm/etc/camera/leica_filter_param.bin` | 1,686,102 | generic Leica parameters |
| `odm/etc/camera/leica_filter_param_m3.bin` | 93,170 | M3 parameters |
| `odm/etc/camera/leica_filter_param_m9_preview.bin` | 1,686,102 | M9 preview |
| `odm/etc/camera/leica_filter_param_m9_snapshot.bin` | 1,686,102 | M9 snapshot |
| `odm/etc/camera/styletrans_params.json` | 1,556 | model description |
| `odm/etc/camera/styletrans/` | ~45 MB | three Nezha models, compressed + raw |

It also writes:

* a patched `odm/etc/camera/xiaomi/leicasnapshot.json` (41 nodes, 57 links);
* an empty marker `odm/etc/camera/hypercameramod_legend_bridge`;
* `module.prop`, `README.md` and `SHA256SUMS` for a Magisk/KernelSU module.

The resulting directory is about 74 MB because both the compressed and raw
model copies are included. The library selects one through
`styletrans_params.json`; keeping both avoids a second guess about which path
the Aurora build will take.

## The plugin patch

Aurora's engine has no `com.xiaomi.sessionparams.legendMode` path. The stock
Nezha plugin would read `MiMetadata` tag 508 as 0 and disable itself. The build
patches two instructions in `com.xiaomi.plugin.mileicafilter.so`:

```text
initialize 0xc768: add x1, x1, #0x36e  ->  add x1, x1, #0xdd7
isEnabled  0xd780: add x1, x1, #0x36e  ->  add x1, x1, #0xdd7
```

`0x6dd7` is `com.xiaomi.sessionparams.stylizationType`; `0x636e` is the old
`legendMode` string. After the patch the plugin's `this+0x26` receives the
stylization byte, and its existing `initResource` mapping selects M9 for 1 and
M3 for 2.

The module half of the bridge is in `ModuleMain.installLegendBridgeHook()`:
when the app sets `com.xiaomi.sessionparams.legendMode` to 1/2, the hook copies
that byte into `com.xiaomi.sessionparams.stylizationType` on the same
`CaptureRequest.Builder`. It only runs when
`/odm/etc/camera/hypercameramod_legend_bridge` exists, so stock devices are
unaffected.

> `[inferred]` The normal Leica classic/vivid path uses the same
> `stylizationType` key with values 0/1/2. In Legendary mode the bridge
> deliberately writes 1/2, which may also make the generic `FilterInstance0`
> apply its classic/vivid look. If that double-processes the image, the next
> iteration should move `LeicaFilterInstance0` before `FilterInstance0` or use
> private values and extend `initResource`. This is the main image-quality
> unknown.

## Build and static verification

```console
$ tools/hal-port/aurora/build_bundle.sh /tmp/opencode/aurora-legend-port
[aurora-legend] cleaning /tmp/opencode/aurora-legend-port
0xc768: patchable
0xd780: patchable
wrote .../com.xiaomi.plugin.mileicafilter.so.patched
wrote .../leicasnapshot.json: added LeicaFilterInstance0, StyleTransInstance0; 41 nodes, 57 links
[aurora-legend] bundle written to /tmp/opencode/aurora-legend-port
74M /tmp/opencode/aurora-legend-port
```

```console
$ tools/hal-port/aurora/verify_bundle.sh /tmp/opencode/aurora-legend-port
== required files
  ok  odm/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so
  ...
  ok  odm/etc/camera/hypercameramod_legend_bridge
== plugin bridge patch
0xc768: already patched
0xd780: already patched
== pipeline nodes
  ok  LeicaFilterInstance0
  ok  StyleTransInstance0
  ok  FilterInstance0 -> LeicaFilterInstance0
  ok  LeicaFilterInstance0 -> WatermarkInstance0
  ok  YuvReprocessInstance0 -> StyleTransInstance0
  ok  StyleTransInstance0 -> DepurpleInstance0
== dynamic dependencies
== result: OK (static checks only; no device execution)
```

The patched instructions can be confirmed independently:

```console
$ llvm-objdump -d --start-address=0xc764 --stop-address=0xc770 \
    /tmp/opencode/aurora-legend-port/odm/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so
    c764: adrp x1, 0x6000
    c768: add  x1, x1, #0xdd7
```

## Why M10R is not in this port

M10R on Madrid needs a different set:

* `com.xiaomi.plugin.legendCT.so` and `libmialgo_ccm_3x8.so`;
* `libmage.so`;
* `leica_filter_param_m10r_preview.bin` and `_snapshot.bin`;
* `styletrans_high_m10r.minn` and `styletrans_low_m10r.minn`;
* an engine that understands the Android 17-era plugin/provider surface.

Nezha has none of the M10R files, and Aurora lacks `legendCT`, `libmage` and
`libmialgo_ccm_3x8`. Adding only the picker item would produce a plain picture,
which is exactly the failure mode already documented for the 17 Ultra.

## Install

Aurora's `/odm` is a read-only EROFS mount, so copying files into it is not an
install method. Package the bundle with the partition layout expected by
KernelSU/Magisk:

```console
$ tools/hal-port/aurora/package_module.py /tmp/opencode/aurora-legend-port
$ adb push /tmp/opencode/aurora-legend-port.zip /data/local/tmp/
$ adb shell su -c 'ksud module install /data/local/tmp/aurora-legend-port.zip'
$ adb reboot
```

The packager rewrites `odm/...` as `system/odm/...` and keeps `module.prop` at
the archive root. On the tested device (KernelSU 3.2.5 with Hybrid Mount 6.2.3)
`ksud` reports `Module installed successfully` and stages it under
`/data/adb/modules_update/hypercam-aurora-legend`; it becomes active after the
reboot. The camera provider reads the pipeline JSON at startup.

1. Reboot after installing. The camera provider reads the pipeline JSON at startup.
2. Clear Camera app data only if the app caches the style list.
3. Verify the module log contains:

```text
HyperCameraMod: HAL capabilities: legend=true m10r=false styleTransfer=true bridge=true
HyperCameraMod: installed the Legendary -> stylizationType request bridge
```

If the log says `bridge=false`, the marker was not installed. If it says
`legend=false`, the plugin/parameter files are not visible to the camera
process.

## Rollback

Keep copies of the stock files before installing:

```bash
adb shell su -c 'cp /odm/etc/camera/xiaomi/leicasnapshot.json /data/local/tmp/'
adb shell su -c 'ls /odm/lib64/camera/plugins > /data/local/tmp/plugins.before'
```

To roll back, remove the module (or restore `leicasnapshot.json` and delete the
added plugin/library files), then reboot. If the camera provider crashes at
boot, boot to safe mode or disable the module from recovery.

## Risks

| Risk | Why it matters | Mitigation |
| --- | --- | --- |
| Symbol versions | Nezha plugin declares `LIBLOG` 4 / `LIBEGL` 5; Aurora's indices differ | Check `logcat` for `cannot locate symbol`/`version`; repack the plugin's `.gnu.version` if needed |
| Engine ABI | Aurora's `mialgo2::Pipeline` constructor is older than Nezha/Madrid | Plugins only import `ProviderManager::add`; validate at load |
| SM8650 vs SM8850 | `libLMSceneSeg` and the style models were built for Nezha | Test; try the Nezha DSP/OpenCL path first |
| Pipeline order | `FilterInstance0` and `LeicaFilterInstance0` may double-process | Compare output; move/remove Filter for Legendary only |
| SELinux | New `.so` files need the plugin/file contexts | Magisk handles most; check `avc: denied` |
| No device test | All conclusions are static | Follow the test plan before calling it working |

## Test plan

1. **Boot stability** — install, reboot, open Camera; confirm no
   `camx`/`ChiX`/provider restart and preview is stable for 60 s.
2. **Plugin load** — `logcat -b all -d | grep -iE 'mileicafilter|legendST|styletrans|MPluginModule'`.
   A missing `dlopen`/`cannot locate symbol` means the dependency or symbol
   version is wrong.
3. **Bridge** — `logcat -b all -d | grep HyperCameraMod`; expect
   `HAL capabilities: legend=true m10r=false styleTransfer=true bridge=true`
   and `installed the Legendary -> stylizationType request bridge`.
4. **M9** — select Leica Essential → M9; capture JPEG and RAW; compare with the
   stock Leica Classic/Vibrant output. Check for a distinct colour rendering and
   no crash.
5. **M3** — select M3; verify monochrome output and that the preview does not
   fall back to colour.
6. **Non-Legendary regression** — take a normal Photo and a Leica Authentic
   shot; the new nodes must disable themselves and the image must match the
   stock pipeline.
7. **Failure handling** — if the provider aborts, restore
   `leicasnapshot.json`, remove the added plugin, reboot, and capture the full
   `logcat -b all -d` before retrying.
