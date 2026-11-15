# Evidence and reproduction

All commands assume the repository root
`/home/rezikham/PoC/xiaomi-camera-mod` and the 970 volume symlink.

## 1. Extracting the Aurora HAL

`extracted_aurora` was initially only `odm/` and `vendor/`. The actual Aurora
`super` image is `970/super_aurora.img` (sparse). It is separate from
`970/super.img`; do not extract `system_a.img` from the wrong super and assume
it is Aurora.

```bash
cd 970
simg2img super_aurora.img super_aurora_raw.img
mkdir -p extracted_aurora_super
lpunpack super_aurora_raw.img extracted_aurora_super
ls -lh extracted_aurora_super
```

`lpunpack` takes the image first and the output directory second. `-p` means
“partition name”, not “print”.

Mount the EROFS partitions with `erofsfuse`:

```bash
mkdir -p /tmp/opencode/aurora2_{system,product,vendor,odm,system_ext,mi_ext}
erofsfuse 970/extracted_aurora_super/system_a.img     /tmp/opencode/aurora2_system
erofsfuse 970/extracted_aurora_super/product_a.img    /tmp/opencode/aurora2_product
erofsfuse 970/extracted_aurora_super/vendor_a.img     /tmp/opencode/aurora2_vendor
erofsfuse 970/extracted_aurora_super/odm_a.img        /tmp/opencode/aurora2_odm
erofsfuse 970/extracted_aurora_super/system_ext_a.img /tmp/opencode/aurora2_system_ext
erofsfuse 970/extracted_aurora_super/mi_ext_a.img     /tmp/opencode/aurora2_mi_ext
```

The extracted `odm`/`vendor` used by the port match the mounted images for the
HAL files checked:

```console
$ sha256sum 970/extracted_aurora/odm/lib64/hw/camera.xiaomi.so \
            /tmp/opencode/aurora2_odm/lib64/hw/camera.xiaomi.so
a58e66587c804fe572cfcb8da1f5ecba4f19afbeb039a6cb049cb217bff53ae3  ...
a58e66587c804fe572cfcb8da1f5ecba4f19afbeb039a6cb049cb217bff53ae3  ...
```

## 2. Camera APK

The 6.7 Camera used for the Legendary trace was decompiled with jadx:

```bash
jadx -d /tmp/opencode/cam67 --no-res --no-debug-info <MiuiCamera-6.7.apk>
```

JADX exits non-zero because of individual method errors; the sources are still
usable. Key files:

```text
p638s2/A.java          ComponentConfigLegendary (M3, M9)
F3/o.java              maps component value -> legendMode byte
p399la/z0.java         f45465g0 = legendMode request key
p399la/Q.java          key name string
```

The 6.4 APK in the Aurora product image is not the target UI. It has no
`pref_legendary_mode_key`, `ComponentConfigLegendary`, `legend_bw` or
`legend_ccd` strings; that is why the 6.7 layout is used for the HAL trace.

Check the 6.7 app:

```bash
grep -R "pref_legendary_mode_key\|ComponentConfigLegendary" /tmp/opencode/cam67/sources
grep -R "com.xiaomi.sessionparams.legendMode" /tmp/opencode/cam67/sources
sed -n '70,95p' /tmp/opencode/cam67/sources/F3/o.java
```

Observed mapping in `F3/o.java`:

```text
VALUE_M9  -> 1
VALUE_M3  -> 2
otherwise -> 3   (M10R)
```

## 3. Aurora HAL facts

```bash
A=970/extracted_aurora/odm
file $A/lib64/hw/*
readelf -d $A/lib64/hw/camera.xiaomi.so | grep NEEDED
strings -a $A/lib64/hw/camera.xiaomi.so | grep -iE 'legend|m10r|leica'
```

Observed:

* Aurora has `camera.xiaomi.so` (3,435,168) and no `camera.qcom.core.so`.
* Nezha/Madrid have `camera.qcom.core.so` and no `camera.xiaomi.so`.
* Aurora has `libmialgoenginecustom.so` (no underscore), not
  `libmialgoengine_custom.so`.
* `camera.xiaomi.so` links `libmialgoengine.so`; the engine dlopens
  `/odm/lib64/libmialgoenginecustom.so`.
* Aurora `libmialgoengine.so` contains the JSON path
  `/odm/etc/camera/xiaomi/leicasnapshot.json`.
* Aurora `libcom.xiaomi.pluginutils.so` contains
  `/odm/lib64/camera/plugins`, the plugin directory used by the loader.

Hashes:

```text
a58e66587c804fe572cfcb8da1f5ecba4f19afbeb039a6cb049cb217bff53ae3  camera.xiaomi.so
87860ee76d196b4d47b758108e677bd0fe71682b3438d2bb202a3e46176b7939  camera.qcom.so
cadfdc9343c43b905b9956c4743fd04c636e489a60775c35622130516a97391e  camera.qcom.sm8650.so
9c42efa9f50776d76e3299ea7322c375155ac823fd01c380218f0e2efd0024ed  libmialgoengine.so
04de098c8df8f74b7f43feb5711c285b3f939552470904cc27f7eae4f86abe48  libcom.xiaomi.pluginutils.so
4dac3a756308912294a4d6b977cb227290e3b878a9dc252ad146ef9b5eaee582  leicasnapshot.json
```

ABI exports:

```bash
nm -DC 970/extracted_aurora/odm/lib64/libmialgoengine.so | \
  grep -E 'MPluginModule|Pipeline::Pipeline'
nm -DC 970/extracted_aurora/odm/lib64/libcom.xiaomi.pluginutils.so | \
  grep -E 'ProviderManager::add|PLUMA_INTERFACE'
```

Observed `MPluginModule::getInstance`,
`MPluginModule::getPluginWraperByPluginName`,
`ProviderManager::add(Provider*)`, and PLUMA interface version 1.

## 4. Donor files and hashes

Source: `970/extracted_nezha/odm`.

```text
43dec6bf21bbb84c9e96d2b2a78913909eaa026287375084d646db4c5c17dd38  camera/plugins/com.xiaomi.plugin.mileicafilter.so
5febdb53e695ee41dcd2f12a92fbb1f2bf17c31e2b78be1e812f8ae776782c15  camera/plugins/com.xiaomi.plugin.legendST.so
8e399a1b8e710affee23760e63e6fa3286d571020bfb797c53211ff42fa795bb  libmialgo_styletrans.so
a188a67e9871472f3239ecc5e8d7803bcdcc691a43e3101c3ba82a8141f30457  libLMSceneSeg.so
7702b28fd8c0a6f028b6e2871662a46e4603a7fe23edffe6b7c154af34a4c2cc  libcom.xiaomi.cam.miexifmgr.so
226f6da8cb2e1a0e1a065513a878cb28c505926a97aba30a02b09cb2b134cf1e  libcameraopt_stub.so
3786907c69ee6a927532f08db287b3fa0e7c5faa0a2af4ba7f5f4e35603fdecd  libmetatagwrapper.so
ba20d3e6dda12ba10217212349ce626ad6781d11e9702cdd8f03a62ed9e81dd7  leica_filter_param.bin
6e9217ee8c59872a3d700614681c30742df01d9bc9b44b7f2a170eeaab68052b  leica_filter_param_m3.bin
94dbd9da14f754b6d540cdcab599ab0f7d3ed084ceee0081e858402e9b991e2f  leica_filter_param_m9_preview.bin
b2b7769ad97ff11205ec052f910153066c43763b7ddc2860bc3a57972e52d9eb  leica_filter_param_m9_snapshot.bin
87dad50d1cc11fb5efbce3792dead2ce007333b912b1b297d2aad54dc982426e  styletrans_params.json
```

Nezha `libmialgo_styletrans.so` linked `libLMSceneSeg.so`; the latter is
8,238,656 bytes and has no additional non-system dependencies.

## 5. Plugin patch

Original bytes:

```console
$ xxd -s 0xc768 -l 4 970/extracted_nezha/odm/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so
0000c768: 21b8 0d91
$ xxd -s 0xd780 -l 4 ...
0000d780: 21b8 0d91
```

Patched bytes:

```console
$ xxd -s 0xc768 -l 4 /tmp/opencode/aurora-legend-port/.../com.xiaomi.plugin.mileicafilter.so
0000c768: 215c 3791
$ xxd -s 0xd780 -l 4 ...
0000d780: 215c 3791
```

Disassembly confirms the immediate:

```console
$ llvm-objdump -d --start-address=0xc764 --stop-address=0xc770 <patched plugin>
    c764: adrp x1, 0x6000
    c768: add  x1, x1, #0xdd7
```

Patched plugin SHA-256:

```text
f74e56188c09c4fc4266d9429a70f0357d8b2f2a0ab0ef5ea46cf1ecc9f56f14
```

## 6. Pipeline patch

The Aurora source has 39 nodes / 55 links and `FilterInstance0` between MLAWB
and Watermark. The patched copy has 41 nodes / 57 links and these edges:

```text
FilterInstance0 -> LeicaFilterInstance0 -> WatermarkInstance0
YuvReprocessInstance0 -> StyleTransInstance0 -> DepurpleInstance0
```

Generate and inspect:

```bash
python3 tools/hal-port/aurora/patch_leicasnapshot.py \
  970/extracted_aurora/odm/etc/camera/xiaomi/leicasnapshot.json \
  /tmp/opencode/leicasnapshot.legend.json --with-styletrans

python3 - <<'PY'
import json
d = json.load(open('/tmp/opencode/leicasnapshot.legend.json'))
print(len(d['NodesList']['Node']), len(d['PortLinkages']['Link']))
print([n['NodeInstance'] for n in d['NodesList']['Node']
       if 'Filter' in n['NodeInstance'] or 'Style' in n['NodeInstance']])
PY
```

Patched JSON SHA-256:

```text
482b784ff570f6c22f2628306a9b4debb2de2ac902e005acab2ea4fdcfe57a50
```

## 7. Build and verification

```bash
tools/hal-port/aurora/build_bundle.sh /tmp/opencode/aurora-legend-port
tools/hal-port/aurora/verify_bundle.sh /tmp/opencode/aurora-legend-port
```

Expected final verification line:

```text
== result: OK (static checks only; no device execution)
```

The module compiles:

```bash
./gradlew compileDebugJavaWithJavac --offline -q
```

No output means success.

## 8. What is not evidenced

* No target device was connected. `adb devices` only showed a Nothing Phone;
  no Aurora/14 Ultra camera session was executed.
* The plugin was not `dlopen`ed on Aurora. Symbol version, SELinux and engine ABI
  compatibility are therefore unproven.
* The image quality of patched M3/M9, and whether `FilterInstance0` must be
  removed or moved, is unproven.
* The `ParamMode` enum values and the `this+0xd0` process-mode semantics are
  not resolved.
