# Evidence and reproduction commands

Everything in this directory comes from the shipped `madrid` firmware and
Camera 6.8 APK. This page lists the exact artifacts and commands so a later
pass can re-check or extend the findings.

## Tools used

| Tool | Use |
| ---- | --- |
| `llvm-objdump` | AArch64 disassembly. GNU `objdump` reports `UNKNOWN` architecture for these ELFs; use LLVM’s. |
| `nm -DC` | Dynamic symbols. Plain `nm` is empty because the libraries are stripped. |
| `readelf -SW` | Section offsets; for these libraries `.text` file offset equals virtual address. |
| `strings -a -t x` | String inventory with file offsets. |
| `grep -abo` | Byte offset of a string in a binary. |
| `jadx` | Camera 6.8 APK decompilation. |
| `python3` | Parse `dumpsys` and JSON. |

The decompiled APK lives at `/tmp/opencode/cam68/sources`; the extracted APK
assets at `/tmp/opencode/apkassets`. Those are temporary analysis directories
and are not part of the repository.

## Artifact hashes

```bash
sha256sum \
  970/extracted_madrid/odm/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so \
  970/extracted_madrid/odm/lib64/camera/plugins/com.xiaomi.plugin.legendCT.so \
  970/extracted_madrid/odm/lib64/camera/plugins/com.xiaomi.plugin.legendST.so \
  970/hal_analysis_madrid/binaries/camera.qcom.core.so \
  970/hal_analysis_madrid/binaries/libmialgoengine.so \
  970/device_dump/camera_apk/18PM_camera_6.8.002230.0.apk
```

| Artifact | SHA-256 |
| -------- | ------- |
| `mileicafilter.so` | `f5d9048636222d60dea9cc7cb6fb14a7392d1db81315b9cd09d740c4abe01119` |
| `legendCT.so` | `a9606b39496820ce213de625b4e661033f535d0b4a5100740232ff4c44e30a33` |
| `legendST.so` | `a6557764bcd37cd0b8ae23814db6b0258bac0619d6ec7081408f9c9415b024e3` |
| `camera.qcom.core.so` | `0370710fb0bf63b6e75e0de8840ed53fca704a8b7851e9bb935065e133016658` |
| `libmialgoengine.so` | `936ec2cb9262c409c7765d4b5f8cd5a4ba0de861f477a1b61522d8037a749914` |
| `18PM_camera_6.8.002230.0.apk` | `155d9012a46c980510802fcba88924a5772af228e8e6553633efde54fee49778` |

Leica parameter files and pipeline JSON:

| Artifact | SHA-256 |
| -------- | ------- |
| `leica_filter_param.bin` | `ceae27197c38b0764bfdee815e12744b0c2a678ba983087b30fea9ca9320c6c9` |
| `leica_filter_param_m3.bin` | `d7586c92c5317123314ce23f5c333530ebbae6f10880989160dddb1cbd85af97` |
| `leica_filter_param_m9_preview.bin` | `8aeb47a157ca13b86e7f4af05e1f588a87f28149476553b8cefa770d89c15627` |
| `leica_filter_param_m9_snapshot.bin` | `188f07de45a625333a18b657747c3ff9cda7e507e4418b08860b0d97bda1e5ac` |
| `leica_filter_param_m10r_preview.bin` | `ea65bf454bee5ca17bffd8f2bc2ea95b06400ac4ffce6f75065e2af28a4b817d` |
| `leica_filter_param_m10r_snapshot.bin` | `7f6ab019e7d9332a8aeea760d7b78e31dfe52a3a5f5ed1d1cf43875cf8f7957d` |
| `legendsnapshot.json` | `0b7e2f46a454ed4143b6dd0850467673910eb3da0ec5b8b6f2461abdabf6ac13` |
| `legendmonopansnapshot.json` | `9f71d2d989462d015891262f6b0163f62b51e6a6275575011dde9cf9c5be6937` |

The prior report also has a full hash CSV at
`970/hal_analysis_madrid/reports/02_binaries_sha256.csv`.

## Device and capability dump

```bash
grep -aE 'ro\.(product|build|system)\.(marketname|device|model|version\.release|version\.incremental)|ro\.mi\.os' \
  970/device_dump/runtime/getprop.txt | sort -u
```

Expected highlights:

```text
[ro.product.marketname]: [Xiaomi 18 Pro Max]
[ro.product.model]: [M154FF]
[ro.product.device]: [madrid]
[ro.build.version.release]: [17]
[ro.build.version.incremental]: [OS4.0.29.0.XEOCNXM]
```

Provider and HAL:

```bash
grep -aE 'Camera Provider|Camera HAL device|Number of camera' \
  970/device_dump/runtime/dumpsys_media.camera.txt | head
```

Expected:

```text
Number of camera devices: 9
== Camera Provider HAL android.hardware.camera.provider.ICameraProvider/vendor_qti/0-0 (v2.0, remote) static info: 9 devices: ==
== Camera HAL device device@1.1/vendor_qti/0 (v1.4) static information: ==
...
```

Per-device `stylizationTypeMask`:

```bash
python3 - <<'PY'
import re
p = '970/device_dump/runtime/dumpsys_media.camera.txt'
lines = open(p, encoding='utf-8', errors='replace').read().splitlines()
hdr = ''
for i, l in enumerate(lines, 1):
    if 'static information' in l:
        hdr = l.strip()
    if 'stylizationTypeMask (81d2000e)' in l:
        print(i, hdr, lines[i].strip())
PY
```

Observed values: device 0 `14`, 1 `12`, 2 `14`, 3 `14`, 4 `12`, 5 `14`,
6 `12`; devices 7 and 8 do not expose the key.

`legendMode.capsInfo`:

```bash
python3 - <<'PY'
import re, struct
p = '970/device_dump/runtime/dumpsys_media.camera.txt'
lines = open(p, encoding='utf-8', errors='replace').read().splitlines()
start = next(i for i, l in enumerate(lines) if 'legendMode.capsInfo' in l)
vals = []
for l in lines[start+1:start+120]:
    if re.fullmatch(r'\s*\[[0-9 ]*\]\s*', l):
        vals += [int(x) for x in re.findall(r'\d+', l)]
    else:
        break
ints = struct.unpack('<%dI' % (len(vals)//4), bytes(vals[:len(vals)//4*4]))
print('version, count, first focals:', ints[:4])
print('nonzero:', [(i, v) for i, v in enumerate(ints) if v][:40])
PY
```

## `mileicafilter`

List the exported API and the parameter strings:

```bash
P=970/extracted_madrid/odm/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so
nm -DC "$P" | grep -E 'MiLeicaFilterPlugin|ParamManager|ParamUtil|ParamTrigger'
strings -a -t x "$P" | grep -E 'leica_filter_param|use pre param|Disable shading|Bokeh snapshot'
```

Expected file strings:

```text
5156 /odm/etc/camera/leica_filter_param_m9_snapshot.bin
55ac /odm/etc/camera/leica_filter_param_m3.bin
5b05 /odm/etc/camera/leica_filter_param.bin
62e6 /odm/etc/camera/leica_filter_param_m9_preview.bin
6573 /odm/etc/camera/leica_filter_param_m10r_snapshot.bin
6b28 /odm/etc/camera/leica_filter_param_m10r_preview.bin
```

Read the parameter-mode table construction:

```bash
llvm-objdump -d --start-address=0x1f4a0 --stop-address=0x1f630 "$P"
```

Look for the `adrp/add` pairs that form the six string addresses, and the
`mov w8, #1..5` key writes. The sixth entry follows the same node layout.

Read the selection table:

```bash
llvm-objdump -d --start-address=0xcca0 --stop-address=0xced4 "$P"
```

`0xce6c`–`0xced0` is the `legendMode` / `proc` switch reproduced in
[02-legend-m10r.md](02-legend-m10r.md#how-initresource-selects-a-mode).

Read the metadata reads:

```bash
llvm-objdump -d --start-address=0xc900 --stop-address=0xcb00 "$P"   # initialize
llvm-objdump -d --start-address=0xd6a0 --stop-address=0xd900 "$P"   # isEnabled
```

Expected: `mov w8, #0x1d` (tag 29) and `mov w8, #0x1fc` (tag 508) before the
`MiMetadata::find` calls.

## `libmetadataHelper`

```bash
P=970/extracted_madrid/odm/lib64/libmetadataHelper.so
nm -DC "$P" | grep -E 'GetLegendMode|GetStylizationType'
llvm-objdump -d --start-address=0x40bec --stop-address=0x40d70 "$P" | grep -E '0x1fc|GetMetaData'
llvm-objdump -d --start-address=0x1ebbc --stop-address=0x1ec40 "$P" | grep -E '#0x1d|GetMetaData'
```

Expected: `mov w3, #0x1fc` for `GetLegendMode` and `mov w3, #0x1d` for
`GetStylizationType`.

## Core HAL

```bash
P=970/hal_analysis_madrid/binaries/camera.qcom.core.so
for s in 'xiaomi.camera.legendMode' 'com.xiaomi.legendMode' 'LegendM10R' \
         'OemRetrieveLegendMode' 'legendMode: %d, oemIsLegendMode'; do
  echo "$s:"; grep -abo "$s" "$P" | head -3
done
```

Expected offsets: `570858`, `1338228`, `2361677`, `2640522`, `1093371`.

The `oemIsLegendMode` test:

```bash
llvm-objdump -d --start-address=0x11a57c0 --stop-address=0x11a5840 "$P"
```

Expected:

```asm
11a57cc: ldr  w22, [x8]
11a57d0: sub  w8, w22, #1
11a57d4: cmp  w8, #2
11a57d8: cset w8, lo
```

## `libmialgoengine`

```bash
P=970/hal_analysis_madrid/binaries/libmialgoengine.so
for s in LegendModeM3 LegendModeM9 LegendModeM10R legendsnapshot.json \
         virtualInstanceLegendMetaPath; do
  echo "$s:"; grep -abo "$s" "$P" | head -3
done
```

Expected offsets: `370307`, `370320`, `370333`, `246353`, `308033`.

`libmialgoengine_custom.so`:

```bash
nm -DC 970/hal_analysis_madrid/binaries/libmialgoengine_custom.so | grep -i legend
strings -a 970/hal_analysis_madrid/binaries/libmialgoengine_custom.so \
  | grep -E 'customizeForLegend|LegendMode PruneValue|Concurrency is set|LegendZoom'
```

## Camera 6.8 decompilation

The APK was decompiled with jadx (`--no-res`) and finished with 157 errors;
the relevant classes decompiled cleanly.

```bash
jadx -d /tmp/opencode/cam68 --no-res \
  970/device_dump/camera_apk/18PM_camera_6.8.002230.0.apk
```

Key source files:

| Path | Purpose |
| ---- | ------- |
| `p613r2/B.java` | `ComponentConfigLegendary` (M3 / M10R / M9 builder, default value) |
| `com/android/camera/data/data/AbstractC2783c.java` | `VALUE_M3`, `VALUE_M9`, `VALUE_M10R` |
| `com/android/camera/data/data/C2784d.java` | style item fields |
| `defpackage/C1223.java` | `b3()` / `c3()` = `instanceof C1217` |
| `defpackage/C1217.java` | `madrid` device profile; supported modes include 256 |
| `p890z4/D.java` | style UI, `"M3"` / `"M9"` / `"M10R"` branch |
| `p402l9/C4034g.java` | `getLegendBokehCapsInfo`, `capsInfo` parser, `madrid` logs |
| `p832xi/h0.java` | 6.8 filter factory and film IDs |
| `p291i3/b.java` | filter item fields |
| `p764vf/b.java` | EXIF `legendmode` id 34992 |
| `p299ia/U.java` | `com.xiaomi.legendMode.capsInfo` / `sessionparams.legendMode` strings |

Verification commands:

```bash
grep -n 'VALUE_M10R\|VALUE_M3\|VALUE_M9' \
  /tmp/opencode/cam68/sources/com/android/camera/data/data/AbstractC2783c.java

grep -n 'instanceof C1217' /tmp/opencode/cam68/sources/defpackage/C1223.java

grep -n 'legendary_leica_m10r' /tmp/opencode/cam68/sources/p613r2/B.java

grep -n 'color_effect_entry_r_600\|color_effect_entry_p_100f\|color_effect_entry_kc_64\|color_effect_entry_kp_160' \
  /tmp/opencode/cam68/sources/p832xi/h0.java
```

APK assets:

```bash
unzip -l 970/device_dump/camera_apk/18PM_camera_6.8.002230.0.apk \
  | grep -E 'assets/cloudfilter|assets/effect.json|libMiFilterSDK'
unzip -p 970/device_dump/camera_apk/18PM_camera_6.8.002230.0.apk \
  assets/cloudfilter/cloud_filter_custom.json > /tmp/opencode/cloud_filter_custom.json
```

The local `filterConfig.filterList` is the 32-entry table in
[03-film-filters.md](03-film-filters.md#the-local-cloud-filter-table).

## Pipeline JSON

```bash
python3 - <<'PY'
import json
for name in ('legendsnapshot.json', 'legendmonopansnapshot.json'):
    p = '970/extracted_madrid/odm/etc/camera/xiaomi/' + name
    d = json.load(open(p))
    print(name, d['PipelineName'])
    for n in d['NodesList']['Node']:
        print(' ', n['NodeName'], '|', n['NodeInstance'])
PY
```

The node lists are reproduced in
[02-legend-m10r.md](02-legend-m10r.md#where-they-run).

Check the encrypted config:

```bash
file 970/extracted_madrid/odm/etc/camera/xiaomi/config/custom/algofeature/legendmode.json
head -c 80 970/extracted_madrid/odm/etc/camera/xiaomi/config/custom/algofeature/legendmode.json
```

Expected: `ASCII text, with very long lines`, starting with
`encrypted_data: `.

## LUT inventory

```bash
find 970/extracted_madrid/odm/etc/camera/videofilter -maxdepth 1 -type f \
  -printf '%f\n' | sort
```

Look for the film pairs:

```text
153_KC64.cube        153_KC64.png
161_KP160.cube       161_KP160.png
166_F50.png
169_LeicaChrome.png  ... 175_LeicaIModelA.png
```

R600 and P100F have no local `.cube` or `.png`; they are cloud entries.

## Plugin inventory

[observed] `ls 970/extracted_madrid/odm/lib64/camera/plugins/`:

```text
com.xiaomi.plugin.anchor.so
com.xiaomi.plugin.arcsoftvideobokeh.so
com.xiaomi.plugin.deblur.so
com.xiaomi.plugin.deflicker2.so
com.xiaomi.plugin.depurple.so
com.xiaomi.plugin.dngcreator.so
com.xiaomi.plugin.dsac.so
com.xiaomi.plugin.ecdsnapshotdet.so
com.xiaomi.plugin.edof.so
com.xiaomi.plugin.ehdrdecompand.so
com.xiaomi.plugin.evawarp.so
com.xiaomi.plugin.extendbuffer.so
com.xiaomi.plugin.fd.so
com.xiaomi.plugin.filmnoise.so
com.xiaomi.plugin.filter.so
com.xiaomi.plugin.gainmapPostProc.so
com.xiaomi.plugin.gainmap.so
com.xiaomi.plugin.generic.so
com.xiaomi.plugin.gpu.so
com.xiaomi.plugin.heifedit.so
com.xiaomi.plugin.jpegrAggr.so
com.xiaomi.plugin.legendCT.so
com.xiaomi.plugin.legendST.so
com.xiaomi.plugin.losslessjpeg.so
com.xiaomi.plugin.lsc.so
com.xiaomi.plugin.mialgoanchor.so
com.xiaomi.plugin.mialgooptics.so
com.xiaomi.plugin.mialphablending.so
com.xiaomi.plugin.mibeautydeformation.so
com.xiaomi.plugin.mihdrtosdrplugin.so
com.xiaomi.plugin.mihevcdec.so
com.xiaomi.plugin.mihevcdecv2.so
com.xiaomi.plugin.mihevcenc.so
com.xiaomi.plugin.mihevcencv2.so
com.xiaomi.plugin.mileicafilter.so
com.xiaomi.plugin.mimanualpgtm.so
com.xiaomi.plugin.miskinbeautifier.so
com.xiaomi.plugin.misv2.so
com.xiaomi.plugin.misv3.so
com.xiaomi.plugin.mitie.so
com.xiaomi.plugin.mlfrc.so
com.xiaomi.plugin.movieeffect.so
com.xiaomi.plugin.offlineawb.so
com.xiaomi.plugin.pack2unpack.so
com.xiaomi.plugin.pixman.so
com.xiaomi.plugin.portraitrelux.so
com.xiaomi.plugin.portraitrepair.so
com.xiaomi.plugin.psmetasplit.so
com.xiaomi.plugin.quality.so
com.xiaomi.plugin.sdsr.so
com.xiaomi.plugin.singlebokehpreview.so
com.xiaomi.plugin.supermoon.so
com.xiaomi.plugin.swflip.so
com.xiaomi.plugin.swjpegencode.so
com.xiaomi.plugin.videofilter.so
com.xiaomi.plugin.videowatermark.so
com.xiaomi.plugin.watermark.so
com.xiaomi.plugin.watermarkV2.so
com.xiaomi.plugin.y2yconvertor.so
com.xiaomi.plugin.yuv2Heic.so
com.xiaomi.plugin.yuvresize.so
```

61 files. The 10 dynamic plugins are listed in
[01-hal-stack.md](01-hal-stack.md#dynamic-plugins).

## MediaTek note

The provider and HAL names (`vendor_qti`, `camera.qcom.*`, `device@1.1`) and
`ro.mi.os.soc.vendor=qcom` all identify this as a Qualcomm device. Any future
port should re-check the HAL stack rather than assume the same paths.

## What was not available

* No root, so no live `dumpsys` while switching styles, no `logcat` of the
  camera process, and no `setprop` experiments.
* No `vendor` headers for `CreateInfo`, so field `+0x44` / `+0x40` are named
  by observation only.
* No cloud catalogue fetch, so R600/P100F LUT bytes were not inspected.
* Ghidra analysis of `libmialgoengine.so` timed out during startup; all
  native claims above come from LLVM disassembly/string analysis.
