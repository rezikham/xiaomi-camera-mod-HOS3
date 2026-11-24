# Mi Master Camera Combo — analysis and relevance to HyperCameraMod

Status: reference analysis, no code changes. The related repository has been
moved to the 970 drive and all current Git LFS release objects are materialized.

- Repository: `/run/media/rezikham/970/PoC/Mi_Master_Camera_Combo`
- Origin: `bjorndith-cmd/Mi_Master_Camera_Combo`
- Branch / HEAD at analysis: `main` / `c53dfbd` (`feat(mi14u): release v5.58.0`)
- License: **none declared**. Treat the code, XML, presets, and binaries as
  all-rights-reserved third-party work. Do not copy them into this Apache-2.0
  project.
- Working tree: the 28 English-translation edits from the earlier pass are
  preserved and uncommitted.

Evidence labels used below:

- `[observed]` — read from a file, archive entry, APK manifest, or hash.
- `[inferred]` — a conclusion from the observed data.
- `[not verified]` — plausible but needs a device test.

## 1. Executive summary

`Mi_Master_Camera_Combo` is not camera source code and is not a usable
reference implementation for HyperCameraMod. It is a family of Magisk /
KernelSU / APatch system-overlay packages that replace the Camera APK, and in
some releases copy vendor HAL files, device-feature XML, properties, and
configuration into the system partitions.

The most important finding is that the release names overstate the camera
version:

- **Only the 13U v8.2 release contains a real Camera 6.8 APK.**
- The entire `Combo_5_v6.2` family uses **Universal 5.0 Beta 8.3**, a 13U port.
  The “6.2” in the filename is the suite version, not the Camera version.
- The 14U, 15U, and 17U `6.2 Full` archives all contain the **same**
  170,343,998-byte 5.0 APK and differ only in HAL payload, properties, and XML.
- The most substantive 14U release, 5.58 EU/Eliterom, is a 13U-lineage module
  (`id=mi13u_camera`) with the 237 MB 5.0 APK, no HAL payload, a malformed
  device-feature XML, and no `META-INF/update-binary`.
- HyperCameraMod already implements the parts that are genuinely interesting
  here: the film-filter list, the 12 film LUTs, HAL capability checks, and
  post-capture Leica/Moment processing. The related repo does not supply new
  LUT data or a working 14U 6.8 build.

Its value is as a **failure catalogue and device/property inventory**: it
documents symptoms, HAL/XML path mistakes, sensor/device mapping, and packaging
traps that HyperCameraMod should avoid.

## 2. What was verified

### 2.1 Repository and LFS state

- The repository is on an exFAT volume. Unix modes are synthesized, so
  `git config core.filemode false` is set locally to suppress mode-only diffs.
  There are no tracked symlinks.
- `git lfs pull` completed: **31/31 current objects**, about **6.1 GB** in
  `.git/lfs/objects`; the working repo is about **8.1 GB**.
- `git lfs fsck` reports OK. All **29 zip releases** pass
  `zipfile.testzip()`. Their total is about **6.10 GB**; the two standalone
  APKs add about **0.34 GB**.
- The full history contains 209 unique LFS objects (about 24.25 GiB). Only the
  current checkout was pulled; historical release binaries were not analyzed.
- Earlier, every `releases/*.zip` was a 134-byte LFS pointer. That is fixed in
  this checkout, but a fresh clone without `git lfs pull` still gives pointers.
  The repository's own CI does not fail on that; see §5.7.

### 2.2 Integrity checks used

- Zip directory listing, `testzip()`, entry counts, and uncompressed sizes.
- APK manifest fields via `aapt2 dump badging` and `apkanalyzer` / `keytool`.
- SHA-256 of APKs, HAL libraries, XML, and MIVI JSON files.
- `readelf -l` for ELF segment alignment inside APKs and HAL payloads.
- XML parsing and schema inspection against stock `device_features` files.
- `verify.py` run against the materialized archives, not the LFS pointers.

## 3. Release inventory

The repository has 31 release files: 29 zips and 2 standalone APKs. The table
groups them by what they actually contain. Exact sizes are in Appendix A.

| Family | Releases | Camera APK | Payload and verdict |
| --- | --- | --- | --- |
| 13U 5.48–5.57 EU Definitive | 6 zips, ~252 MB each | 237,266,333 B, Universal 5.0 Beta 8.3 | APK replacement plus properties, XML, LUTs, WebUI. No `update-binary`. |
| 13U 5.6.1 EU Stable | 1 zip, ~453 MB | 170,343,998 B, 5.0 Beta 8.3 | Same 5.0 APK as the 6.2 Full family; MIUI 14 compatibility variant. |
| 13U 6.2 Full / MasterFinal | 2 zips, ~453 MB each | 170,343,998 B, 5.0 Beta 8.3 | Content-identical packages; only zip metadata differs. Includes 13U XML but no HAL. |
| 13U v8.2 Full | 1 zip, ~296 MB | 170,366,483 B, **Camera 6.8.001960.0** | The only real 6.8 build. 6.8 APK plus configs; XML/permissions are shipped but never deployed. |
| 14U 5.58 EU / Eliterom | 2 zips, ~252 MB each | 237,266,333 B, 5.0 Beta 8.3 | 13U-lineage module with aurora/shennong XML, LUTs, WebUI; no HAL; no `update-binary`. |
| 14U 6.2 Full | 1 zip, ~211 MB | 170,343,998 B, 5.0 Beta 8.3 | APK replacement only; no HAL; XML outside `/odm`. |
| 15U 6.2 Full | 1 zip, ~321 MB | 170,343,998 B, 5.0 Beta 8.3 | xuanyuan HAL payload plus 5.0 APK; no device guard. |
| 15 6.2 Full | 1 zip, ~223 MB | 170,343,998 B, 5.0 Beta 8.3 | `dada` HAL profile; no device guard. |
| 17U 6.2 Full | 1 zip, ~388 MB | 170,343,998 B, 5.0 Beta 8.3 | `nezha` HAL payload; no `camera.qcom.so`; no device guard. |
| Universal 6.2 Full | 1 zip, ~512 MB | 170,343,998 B, 5.0 Beta 8.3 | Multi-device HAL profiles; unknown devices fall back to ishtar; no guard. |
| Universal 6.2 Slim | 1 zip, ~298 MB | none | Real device guard; HAL payload; file-level XML bind mounts. Sets `support_super_resolution=true`. |
| 15U / 15 / 17U Slim | 3 zips, ~109 / 12 / 177 MB | none | Real device guard; HAL payload and bind mounts. 14U/13U Slim have no HAL. |
| 17U SimpleRom ST Non-Leica | 1 zip, ~177 MB | none | Standard `nezha.xml` copied to five partitions; disables cloud props and `com.xiaomi.camera.cloud`. |
| 14U / 13U Slim | 2 zips, ~22–24 KB | none | Scripts/properties only; 14U Slim injects aggressive XML, 13U Slim is conservative. |
| AI suite | 4 zips, 5–10 KB | none | Property/XML sprays only; no AISP/GenAI/Director implementation. |
| Config pack | 1 zip, 4.8 KB | none | Three JSON style presets plus a still-Russian README; importability unverified. |
| Standalone APKs | 2 files | 5.0 and 6.8 | `Mi13U_Camera_5_v6.1_MIUI14_by_borndead.apk` and `Mi13U_Camera_6_v8.2_by_borndead.apk`. |

### 3.1 The 13U v8.2 release is the only real Camera 6.8 package

`[observed]` `Mi13U_Master_Camera_Combo_6_v8.2_Full_by_borndead.zip`:

- 296,428,437 B, 111 entries.
- Camera APK 170,366,483 B, SHA-256
  `a45e06d48c1162f5a121d172d47183eff9b19d9bedea668c9b6332a3d2fa77a0`.
- Manifest: `versionCode=680019600`, `versionName=6.8.001960.0`,
  targetSdk/compileSdk 36.
- 49 arm64 libraries: 48 are 16 KB-aligned, one is 64 KB-aligned. No 4 KB-only
  library was found, so this APK is suitable for 16 KB-page Android 16 devices
  from an alignment perspective.
- Signer: `CN=MiuiCamera Ishtar Port, O=borndead, C=RU` — a third-party port
  certificate, not Xiaomi's.
- No HAL payload. The package ships `configs/device_features/ishtar.xml`
  (11.7 KB) and `configs/permissions/privapp-permissions-camera.xml`, but
  `customize.sh` never copies them, and `post-fs-data.sh` deletes
  `$MODDIR/system/etc`, `system/odm`, and `system/vendor`. The Quad-50MP XML
  claim is therefore not installed by this release.
- `post-fs-data.sh` makes `hal_camera_default`, `platform_app`, and `priv_app`
  permissive via `magiskpolicy --live`, sets
  `persist.vendor.camera.maxRAWSizes=55`, and writes a 196-character aux
  package list. The package fails the repository's own verifier for the
  missing `install_module` symbol in `update-binary` and the overlong property.

### 3.2 The 6.2 “Full” family is one 5.0 APK with different HAL payloads

`[observed]` The 13U 6.2 Full and MasterFinal archives are content-identical
(all entry CRCs equal); the 13U 5.6.1 release differs only in `module.prop` and
`customize.sh`. The 14U, 15U, 15, 17U, and Universal 6.2 Full packages all
contain the same 170,343,998-byte APK
`1d1ebcf58e63e8b654270b5edd76e6d5269d464707b7f79c84ce6a7d760682c2`
(Universal 5.0 Beta 8.3, versionCode 599830000, targetSdk 33).

The releases differ in:

- HAL payload under `devices/<codename>/` (or none).
- `device_features` XML and properties.
- LUTs, configs, and scripts.

They do **not** differ in Camera app generation. A 14U, 15U, or 17U user who
installs one of these gets a 13U-lineage 5.0 Camera, not the stock 6.8 app.

The 13U 6.2 package also shows version chaos: `module.prop` says v6.1 /
versionCode 2026093008, the `customize.sh` banner says v5.8, and the archive
name says 6.2. The 14U 5.58 package has the same problem in a different form:
the archive and `module.prop` say 5.58, but the module id is `mi13u_camera`.

### 3.3 The 14U 5.58 release is a 13U-lineage APK replacement

`[observed]` `Mi14U_Master_Camera_Combo_5_v5.58.0_EU_Definitive_by_borndead.zip`
and the Eliterom variant:

- 252,262,348 B, 1,501 entries.
- `module.prop`: `id=mi13u_camera`,
  `name=Xiaomi 13 Ultra, 14 Pro & 14 Ultra Master Camera Suite v5.58.0`,
  `versionCode=20261115`.
- Camera APK 237,266,333 B, SHA-256
  `26b098579a1b30211eeb64588043cd1247ff37d779c5135f0fc04a63462d11d0`,
  Universal 5.0 Beta 8.3.
- **No HAL payload**: the only 57 `.so` files are inside
  `system/priv-app/MiuiCamera/lib/arm64/`, i.e. APK-adjacent libraries. There is
  no `system/odm/lib64/camera`, no `camera.qcom.so`, and no Chromatix tuning.
- **No `META-INF/update-binary`**: not installable by Magisk as packaged.
  KernelSU/APatch may run `customize.sh`, but the README claims Magisk support.
- XML is written to `system/etc/device_features/` and
  `product/etc/device_features/`, with no `/odm` path and no bind mount.
- `product/etc/device_features/zzz_borndead_camera.xml` is malformed; see §5.4.
- `system.prop` contains `persist.vendor.camera.maxPictureSize=6144x8192`,
  `maxPreviewSize=1920x1080`, `maxRAWSizes=55`, video bitrate 2.5, AISP off,
  and DCG on.
- `post-fs-data.sh` / `service.sh` inject a 1,799-byte `mivi_info.json` into
  the Camera cache. The aurora, shennong, and ishtar copies are byte-identical
  (SHA-256 `c37f1970c67e03d43dc9347e5ef8fb5e2ee12fe95bf32c3addc5cc7b202333ac`),
  hard-code role IDs `20#21#60#61#0`, and advertise only Night 4096x3072, not
  50 MP.
- The module ships a WebUI and deploys 30 LUTs (`.cube` plus 512×512 PNG) and
  24 watermark styles. The APK's 57 native libraries have mixed alignment: 19 at
  4 KB, 25 at 16 KB, 13 at 64 KB. `libion.so` and `libdmabufheap.so` are 4 KB.

This is the most complete 14U package, but it is still an APK replacement, not
a HAL port, and it is not packaged correctly for Magisk.

### 3.4 The 14U 6.2 Full is the least useful 14U package

`[observed]` 211,442,575 B, 61 entries. It contains the 5.0 APK, but **no HAL
payload**, no `devices/` directory, and no Chromatix. XML is written only to
`system/etc`, `system/product/etc`, and `product/etc`; there is no `/odm`
overlay and no bind mount. Its XML patch is safer than the Slim (it sets
`support_super_resolution=false` and omits the zoom tag), but the package does
not supply the 6.8 app or the 14U HAL.

### 3.5 Device HAL payloads in the 6.2 Full family

`[observed]`

- **15U (`xuanyuan`)**: 10 tuned `.bin` files, `camera.qcom.so` (10,189,904 B,
  SHA-256 `0fc793e1c29c66da30b7b5aa233779d059c9ae51ced0d017cf3ea312d4e0b990`),
  `libmialgo_snsc.so`, and camera JSONs. No device guard.
- **15 (`dada`)**: hard-coded `DEV_PROFILE=dada`; no guard. A 15 Pro
  (`haotian`) installing this gets the 15 profile.
- **17U (`nezha`)**: 4 tuned `.bin` files (including a 93.9 MB OVX10500U wide
  and a 50.1 MB S5KHPE tele), `libmialgo_snsc.so`,
  `libqcodec2_v4l2codec.so`, and 8 JSONs. No `camera.qcom.so`.
- **Universal**: profiles for ishtar, dada, xuanyuan, and nezha; aurora has
  only `aisp.json`; `haotian` maps to `dada`; unknown devices fall back to
  ishtar. No guard.
- All of these use the same 5.0 APK, set `maxRAWSizes=55`, and write XML
  without an `/odm` bind mount.

### 3.6 The Slim releases use the better packaging pattern

`[observed]` The Universal, 15U, 15, 17U, 14U, and 13U Slim modules:

- contain no APK;
- abort on an unsupported device (a real guard, unlike the Full builds);
- carry a device-specific HAL payload where one exists;
- bind-mount individual XML files over `/odm`, `/vendor`, `/product`,
  `/system`, and `/system/product` in `post-fs-data.sh` and `service.sh`;
- do not spoof device properties or make SELinux permissive;
- split aux package lists under the 91-character property limit.

That is the right **packaging** pattern. It is not a safe **feature** pattern:
every Slim sets `support_super_resolution=true` and a zoom list that includes
1.0×, which is the documented `mialgo_sr` / IMX989 freeze condition on 13U and
14U. The 14U Slim also has no HAL payload, so it cannot add the missing 14U
processing.

### 3.7 17U SimpleRom ST Non-Leica

`[observed]` 177,086,147 B, 46 entries, no APK. It copies a standard 5,347-byte
`nezha.xml` to `system/odm`, `system/vendor`, `system/vendor/odm`,
`system/product`, and `system/etc` device_features. It sets:

- `support_super_resolution=true` and
  `support_ultra_hd_zoom=0.5:1.0:3.0:5.0`;
- `support_50mp=true`, `support_camera_dcg=true`, `maxRAWSizes=55`;
- all cloud properties to 0;
- `pm disable com.xiaomi.camera.cloud` and
  `settings put ... camera_cloud_process 0` on install.

The device check only warns if the device is not `nezha`. This is the clearest
attempt in the repository to address pink/magenta noise by disabling cloud and
Leica Essential upload. It is unverified and potentially too broad: it disables
the cloud service for every camera feature, not just the problematic filter.

### 3.8 AI modules and config pack

`[observed]` The four AI zips are 5–10 KB and contain no APK, libraries, or XML
payload. They generate XML only into `system/etc` and `system/product/etc`,
without an `/odm` bind mount. Their `post-fs-data.sh` is a shebang, and
`service.sh` uses an undefined `LOG_DIR`, so diagnostics write nowhere. They are
property/XML sprays, not implementations of AISP, GenAI, or Director.

`[observed]` `Leica_13U_Configs_Master_Pack.zip` contains a still-Russian
`README_RUS.txt` and three JSON presets (`Leica_Pure_Optics`,
`Ultra_Dynamic_Range`, `Street_Photography_35mm`) plus nested zips. The JSON
looks like a Camera 6.8 style-preset schema (`tone`, `vibrance`, `texture`,
`soft_light`, `sharpness_bias`, `sensor_tuning`), but importability is
`[not verified]`. The `.agc` files elsewhere in the repo are descriptive JSON,
not standard AGC presets.

### 3.9 How AISP / ASD / LOCAL AI are actually enabled

The AI modules do **not** contain an AI implementation. They contain no APK, no
`.so`, no QNN/TFLite models, and no image-processing code. Their payload is a
`system.prop` file, a generated `device_features` XML, and scripts that mostly
call `resetprop`. The actual inference, if it happens, is done by the Camera app
and the vendor camera stack that are already on the phone.

The features are exposed by a mix of properties and XML flags:

| Claimed feature | Properties used by the modules | XML flags used by the generated XML | Where the work would actually happen |
| --- | --- | --- | --- |
| AISP | `persist.vendor.camera.aisp`, `.aisp.enable`, `.aisp.setting`, `.aisp_sr.enable`, `.aisp_portrait.enable`, `ro.vendor.camera.aisp`, `vendor.camera.aisp` | `support_aisp`, `support_aisp_sr`, `support_aisp_portrait`, `support_aisp_ultra_raw` | Vendor camera HAL / `libmialgo*` / Qualcomm QNN, not the module |
| ASD / AI scene | `persist.vendor.camera.asd.enable`, `.ai.enable`, `.ai_scene`, `.mialgo.asd` | `support_asd`, `support_camera_ai_asd`, `support_ai_scene`, `support_ai_scene_detection`, `support_super_night_asd` | Camera app scene detector plus vendor metadata; the result is a scene badge/tuning choice |
| AINR | `persist.vendor.camera.sensor.ainr`; `persist.vendor.camera.arcsoft.aisp_algo_nr.bypass` | no dedicated flag in most files | Vendor noise-reduction pipeline |
| CyberFocus | `persist.vendor.camera.cyberfocus.enable` | `support_cyber_focus` | Vendor tracking/focus engine |
| MIVI SR | `persist.vendor.camera.mivi.super_resolution`, `.super_resolution`, `.sr.fusion` | `support_super_resolution` (not the same thing) | MIVI / vendor upscale path |
| AI Director / coach | `persist.vendor.camera.ai.director`, `persist.sys.camera.ai_coach.enable` | `support_ai_composition_guide`, `support_ai_pose`, `support_ai_lens_advisor`, `support_ai_assistant` | Camera UI/HUD, not image processing |
| GenAI / ExtraPhoto | `ro.miui.has_gm_genai`, `persist.sys.miui.gallery.ai_editor`, `ro.miui.support_ai_*` | `support_ai_editor`, `support_magic_elimination`, `support_image_expand`, `support_ai_sky` | Gallery / `com.miui.extraphoto`, a separate app |
| Cloud off | `persist.vendor.camera.cloud.enable=0`, `.ai_cloud.enable=0`, `.cloud_process=0` | `support_cloud_* = false` | Nothing in the module enforces a network block; these are feature flags |

`[observed]` The four `Mi_AI_*` zips are 5–10 KB. Their `post-fs-data.sh` is a
32-byte shebang. Their `service.sh` only writes diagnostics, and it uses an
undefined `LOG_DIR`. Their XML is generated at install time by copying a stock
XML, deleting tags, and appending flags, but it is written only to
`system/etc/device_features` and `system/product/etc/device_features`; there is
no `/odm` bind mount. On Android 16, where `/odm` is polled first, the XML may
never be read.

`[observed]` The 14U 5.58 package is more substantial but still does not ship a
vendor AI stack. It has no `system/odm` HAL payload and no `aisp.json`; its 57
`.so` files are app-local libraries. It relies on the stock 14U HAL for any AISP
processing. Its `system.prop` sets AISP off:

```text
persist.vendor.camera.aisp.enable=0
persist.vendor.camera.aisp.setting=0
ro.vendor.camera.aisp=0
vendor.camera.aisp=0
persist.vendor.camera.aisp_sr.enable=0
persist.vendor.camera.aisp_portrait.enable=0
```

but `service.sh` later sets the same keys to `1` when the `no_ai` flag is
absent. The `post-fs-data.sh` “LOCAL AI ON” branch also sets AISP to `0`, so the
final state depends on whether `service.sh` runs and on which branch wins. The
module does not test or reconcile this.

The action menu compounds the confusion:

- Item **“AISP/ASD”** creates `no_aisp` and only changes `asd.enable`,
  `ai.enable`, `ai_scene`, `mialgo.asd`, and `mialgo.support`. It does not touch
  the AISP keys.
- Item **“LOCAL AI”** creates `no_ai` and changes AISP, AINR, CyberFocus, and
  MIVI SR.
- `webui.sh` prints the AISP/ASD and 50 MP strings for the LOCAL AI toggle and
  the LUT/watermark strings for the AISP/ASD toggle, so the UI text is also
  wrong.

`[observed]` The standalone AI module sets
`persist.vendor.camera.arcsoft.aisp_algo_nr.bypass=1` while claiming to enable
AINR; the 14U 5.58 package sets the same key to `0` and describes that as
“bypass disabled”. A `bypass=1` value normally means the algorithm is skipped,
so the AI module's claim and its property contradict each other.

`[observed]` The 13U v8.2 package deliberately disables AISP
(`persist.vendor.camera.aisp=0`, `.aisp.enable=0`, `ro.vendor.camera.aisp=0`)
to preserve what it calls authentic Leica colour, while leaving ASD on. The
suite therefore does not have one consistent AI policy across releases.

`[inferred]` These are configuration guesses, not a working AI stack. Setting
`ro.vendor.camera.ai.engine=qualcomm_qnn` does not load a QNN model. The
properties may be ignored, may select a different vendor path, or may enable a
feature the HAL cannot run. The repository contains no controlled AISP/ASD
on/off comparison, no model inventory, and no proof that the XML flags reach the
parser on a 14U.

## 4. APK identity matrix

| SHA-256 (first 8) | Size | Version | Used by |
| --- | ---: | --- | --- |
| `1d1ebcf5` | 170,343,998 B | Universal 5.0 Beta 8.3 (599830000), targetSdk 33 | 13U 5.6.1, 13U 6.2 Full, 13U 6.2 MasterFinal, 14U 6.2 Full, 15U Full, 15 Full, 17U Full, Universal Full |
| `26b09857` | 237,266,333 B | Universal 5.0 Beta 8.3 | 13U 5.48–5.57, 14U 5.58 EU/Eliterom |
| `a45e06d4` | 170,366,483 B | Camera 6.8.001960.0 (680019600), targetSdk 36 | 13U v8.2 only |
| `01909c3f` | 170,343,998 B | Universal 5.0 Beta 8.3 | standalone 5.6.1 APK; likely differs from the in-module `1d1ebcf` copy in signing/zip metadata |

The `1d1ebcf` and `26b09857` APKs are both 5.0 Beta 8.3. Their sizes differ
because they are different builds/packagings of the same camera generation, not
because one is 6.8.

## 5. What is technically credible

### 5.1 The Super Resolution / 50 MP distinction

`[observed]` The repository's own audit identifies the failure mode:
`support_super_resolution=true` at 1.0× makes `SuperResolutionProcessor` select
`mialgo_sr` on IMX989, which the HAL does not implement, and the viewfinder
freezes. The intended fix is to remove the SR tags and expose 50 MP only in
Ultra HD / Pro RAW.

The releases are inconsistent:

- 14U **Slim** sets `support_super_resolution=true` and a zoom list containing
  1.0× — it reintroduces the freeze.
- 14U **6.2 Full** sets `support_super_resolution=false` and omits the zoom
  tag — it follows the audit.
- 15U and 17U Full set SR true again.
- 14U 5.58's full XMLs (`aurora`, `ishtar`, `shennong`) all set SR true. The
  only file that tries to set it false is the malformed `zzz` XML, which
  probably never parses.

This is the strongest lesson for HyperCameraMod: do not treat a boolean XML
flag as a feature switch without checking the pipeline it selects.

### 5.2 The 1.6 MP / `mivi_info.json` claim

`[observed]` The 14U 5.58 `post-fs-data.sh` contains a comment that the “6.8
app” reads its MIVI contract from
`/data/user/0/com.android.camera/cache/mivi_info.json`; if the file is missing,
the background save service never delivers the full-resolution frame, producing
a 1.6 MP placeholder and a roughly 40-second capture wait. The same package
contains a 5.0 APK, so the comment is at best copy-pasted and at worst describes
a different build. The script injects a 1,799-byte JSON to prevent the symptom.

Caution:

- The JSON is generic and byte-identical for aurora, shennong, and ishtar.
- It hard-codes role IDs and only advertises Night 4096×3072.
- It does **not** advertise 50 MP or a 6144×8192 stream.
- The repository's changelog gives a different explanation for the same
  symptom: conflicting `support_super_resolution`/`support_200mp` flags
  exhausting CamX buffers, fixed by purging those flags and setting
  `maxRAWSizes=55`.
- No controlled test was found that isolates the JSON from the XML/property
  changes.

`[inferred]` The MIVI cache is a plausible diagnostic lead for a 1.6 MP
placeholder, but it is not evidence that injecting the JSON enables 50 MP.
HyperCameraMod should not write into Camera's data directory without an
explicit, tested reason.

### 5.3 Device-property spoofing and permissive SELinux

`[observed]` The 13U 6.2 Full family is the worst example:

- `post-fs-data.sh` spoofs `ro.product.device`, `ro.product.model`,
  `ro.product.name`, `ro.product.brand`, and `ro.product.manufacturer` to
  ishtar / Xiaomi 13 Ultra.
- It makes `hal_camera_default`, `platform_app`, `priv_app`, `system_app`, and
  `cameraserver` permissive.
- It sets `ro.control_privapp_permissions.enforce=0`.
- It writes 200+ character aux package lists via `resetprop`, even though
  `system.prop` happens to be under the limit; the verifier only checks
  `system.prop`.

`[inferred]` Loading a 13U sensor map on another device can break frames,
1.5 MP output, and SAT arbitration. This matches the audit's warning and is a
hard no for HyperCameraMod.

### 5.4 Device-feature XML schema and partition path

`[observed]` The 14U 5.58 `zzz_borndead_camera.xml` is not valid
`device_features` XML:

- Every element is `<feature_name name="...">value</feature_name>`.
- There are zero `<bool>`, `<string>`, `<int>`, `<string-array>`, or
  `<int-array>` elements.
- Every stock XML in the same package uses the standard schema.
- It declares `device_name=ishtar`, `market_name=Xiaomi 13 Ultra`,
  `support_camera_aperture_keys=1.94.0`, `dual_aperture_values=1.9,4.0`, and
  `support_stepless_aperture=false`.
- On a 14U, the aperture values are wrong (the 14U is 1.63–4.0 stepless), and
  the device name is wrong.

`[inferred]` The intended overrides in that file probably never load. The
standard `aurora.xml`, `ishtar.xml`, and `shennong.xml` are valid, but they do
not carry the 50 MP/DCG/AISP overrides; they set SR true. The only plausible
50 MP mechanism left in this package is the `maxPictureSize` property, not XML.

Path handling is also inconsistent. Many Full packages write XML only to
`system/etc`, `system/product/etc`, and `product/etc`. The repository's own
audit says `/odm/etc/device_features` is polled first on Android 16. Without an
`/odm` bind mount, the overlay may be ignored. The Slim packages do mount over
`/odm`; that is the correct pattern if XML injection is required at all.

### 5.5 AISP configuration

`[observed]` The 4,108-byte `aisp.json` shipped in the 14U Slim, 14U Full, and
14U 5.58 packages has 17 algorithms. It omits stock algorithms and the `lib`
and `policy` fields used by the 17U/18PM configs, and it changes some `bypass`
values. It is not device-matched. Do not use it as a drop-in.

### 5.6 Film LUTs and cloud filters

`[observed]` HyperCameraMod already has the stronger implementation:

- `filters/ModuleMain.java` models all 12 films and their cloud IDs.
- `docs/18-pro-max-hal/03-film-filters.md` documents the local `.cube`
  inventory and identifies R600/P100F as cloud-only on 6.8, F-50 as
  thumbnail-only/likely cloud-backed.
- `docs/hal-backport/20-camera-5.3-filters.md` and
  `docs/hal-backport/22-all-film-filters.md` document the Camera 5.3
  extraction, which supplies real 512×512 LUTs for 600F, BF70, R600, and
  P100F. The KSU module bundles 14 LUTs (`lut_m3`, `lut_m9`, and the 12 film
  LUTs) under `/data/vendor/camera`.
- The related repository's 14U 5.58 LUTs are the same class of 512×512 PNG
  files, but its 5.0 APK/filter factory is not the 6.8 target. It does not
  provide a 6.8 filter factory or a demonstrated new LUT source; its LUTs are
  not needed for HyperCameraMod's 5.3-derived set.

`[inferred]` The remaining question is not data availability but whether the
6.7/6.8 renderer resolves a locally staged LUT for a filter whose cloud entry
is missing. That is a device test, not a reason to disable
`com.xiaomi.camera.cloud` globally.

### 5.7 Packaging and CI defects

`[observed]`

- Eight modules have no `META-INF/update-binary`: 13U 5.48, 5.50, 5.52, 5.53,
  5.56, 5.57 and 14U 5.58 EU/Eliterom.
- `tools/verify.py` catches `zipfile.BadZipFile` and `continue`s before
  `check_module_zip`, so a checkout with LFS pointers passes CI without
  auditing any module.
- When run on materialized archives:
  - 14U 5.58 fails for missing `update-binary`, `libion`/`libdmabufheap`, and
    the `DEVICE_POWER` permission;
  - 13U v8.2 fails for a missing `install_module` symbol and the 196-character
    aux list;
  - 13U 6.2 passes despite property spoofing and permissive SELinux;
  - Universal Full passes despite no device guard and `enforce=0`.
- `tools/patch_release_modules.py` truncates property values over 91 characters
  instead of splitting or rejecting them, deletes remosaic/AI libraries it
  considers dangerous, and edits zips in place.
- `scripts/build_full_and_slim_lineup.py` is a stub; the other packagers need
  external staging directories that are not in the repository. The release
  artifacts are not reproducible from the checked-in source.

## 6. Security and legal assessment

These are third-party system packages that replace the stock camera. They are
not suitable as a supply chain for HyperCameraMod.

- **No license.** There is no `LICENSE`, `COPYING`, or `NOTICE` file in the
  related repository. Do not copy code, XML, presets, or binaries into this
  Apache-2.0 project without permission.
- **Third-party signer.** The inspected 5.0 APK is signed by
  `CN=MiuiCamera Ishtar Port, O=borndead, C=RU`, not Xiaomi. A system app with
  this signer can access camera data and privileged permissions.
- **Old target SDK.** The inspected 170 MB 5.0 APK is targetSdk 33; the 237 MB
  variant was not rechecked. The 6.8 v8.2 APK is targetSdk 36.
- **Cleartext/network surface.** `[observed]` The 170 MB 5.0 APK has
  `allowBackup=true`, a global `network_security_config` with
  `cleartextTrafficPermitted="true"`, and a literal
  `http://121.199.7.120:3000` string that is not present in the stock 18PM 6.8
  APK. This is not proof of malicious behavior, but it is not a hardened build.
  The 237 MB 5.0 variant was not rechecked for the same strings.
- **Broad permissions.** The 14U 5.58 privapp XML grants
  `WRITE_SECURE_SETTINGS`, `SYSTEM_CAMERA`, `MANAGE_EXTERNAL_STORAGE`,
  `MANAGE_USB`, `READ_PHONE_STATE`, and `RECORD_AUDIO`, among others. Some may
  be needed by the ported APK, but the set is not minimal.
- **Destructive install scripts.** Several Full packages run
  `pm clear com.android.camera`, `pm clear com.miui.extraphoto`, delete
  `/data/app/*com.android.camera*`, and purge caches.
- **SELinux.** Several Full packages set `ro.control_privapp_permissions.enforce=0`
  and make camera-related domains permissive.
- **Packaging.** Missing `update-binary` means the package is not a valid
  Magisk module; a user who force-installs it may get an incomplete overlay.

## 7. Relevance to HyperCameraMod

### 7.1 DeviceSupport: 14 Pro is listed but not modeled

`[observed]` `src/main/java/io/martin/hypercameramod/DeviceSupport.java` has
three families: 14 Ultra, 17 Ultra, and 18 Pro Max. The README lists the 14 Pro
Ti (`shennong`, Camera 6.4) as tested for film filters, but `family()` falls
through to `UNKNOWN` for it.

This does not break the 6.4 film-filter path: the filter profiles handle the
6.4 layout, and `originalPortraitDefault()` already returns false for anything
that is not 17U/18PM. The practical effect is limited to `modelName()` and any
future family-specific default. Add `XIAOMI_14_PRO` if the enum is meant to
cover every supported device; do not attach 6.7/6.8 features to it.

### 7.2 HAL capability checks match the related repo's inventory

`[observed]` `HalCapabilities.java` checks the same class of files the related
repo copies:

- `com.xiaomi.plugin.mileicafilter.so`
- `com.xiaomi.plugin.legendST.so`
- `com.xiaomi.plugin.legendCT.so`
- `leica_filter_param*.bin`
- `styletrans/compressed/*m10r*.minn`
- `libmialgo_styletrans.so`
- the `hypercameramod_legend_bridge` marker

The related repo confirms the 14U stock HAL lacks Legend/M10R and that the 17U
lacks M10R. HyperCameraMod's file-based capability checks are the right layer;
the related repo's permissive/spoofing workarounds are not.

### 7.3 Film filters and cloud entries

`[observed]` HyperCameraMod already:

- models R600/P100F/F-50 with cloud IDs 56/57/46;
- merges local film entries into the cloud list;
- bundles 12 film LUTs plus M3/M9 in the KSU module;
- documents that R600/P100F are cloud-only on 6.8.

The related repo does not add LUT data or a working 6.8 filter factory. Its
SimpleRom variant is only a broad cloud-disable experiment. Do not copy the
`pm disable com.xiaomi.camera.cloud` approach without a per-filter test; it can
break other cloud features.

### 7.4 Diagnostics

`[observed]` `tools/hcm-log.sh` captures device/OS/camera/module versions,
settings, logcat, crash buffer, and AVC denials. It does not capture:

- HAL file presence (the `HalCapabilities` inputs);
- camera properties such as `maxPictureSize`, `maxRAWSizes`,
  `camera.debug.mivi3.output.jpeg`, and `persist.sys.hypercam.*`;
- LUT inventory in `/data/vendor/camera` and `/odm/etc/camera/videofilter`;
- ROM fingerprint and build incremental in the same compact block as the HAL
  state.

`tools/hcm-hal.sh` already captures the HAL files, props, dumpsys, and APK, but
it is a separate tar. A small compatibility snapshot in `hcm-log.sh` would make
bug reports comparable without a second tool.

### 7.5 KSU LUT module

`[observed]` `camera-mod-ksu/module.prop` is `1.1.0-254-lut`; `customize.sh`
checks all 14 LUTs; `post-fs-data.sh` copies them to `/data/vendor/camera` with
mode 0644 and `vendor_camera_data_file`, and sets
`camera.debug.mivi3.output.jpeg=true`. It contains no HAL libraries, no `/odm`
or `/vendor` mounts, and no APK.

The related repo's packaging lessons apply here: validate the zip inventory,
line endings, version/module.prop consistency, file modes, and the absence of
unintended APK/HAL payloads. Do not copy its `update-binary` or property
truncation logic.

## 8. Recommendations

### P0 — do not import the packages

Do not add the related repo's APKs, HAL libraries, XML, AISP JSON, `.agc`
files, or scripts to HyperCameraMod. They are unlicensed, partly malformed,
partly destructive, and not built for the module's Camera 6.4–6.8 hook model.
Keep this document as the reference.

### P1 — add 14 Pro to DeviceSupport (small, low risk)

Add `XIAOMI_14_PRO` for `shennong` (market name `14 PRO` / `14 PRO TI`, device
`shennong`). Keep `originalPortraitDefault()` false for it; the 6.4 camera has
no native Original portrait path and the module's software path is documented
for 14U. Acceptance: `family()` returns the new value on a `shennong` property
set, `modelName()` is not `unknown`, and the 6.4 filter profile still resolves.

### P1 — extend `hcm-log.sh` with a compact compatibility snapshot

Add a block with:

- `ro.build.fingerprint`, `ro.build.version.incremental`, `ro.product.device`,
  `ro.product.marketname`;
- Camera version and `dumpsys package` code path;
- presence of the `HalCapabilities` files (boolean only, not full paths);
- `getprop` for `persist.vendor.camera.maxPictureSize`, `maxRAWSizes`,
  `camera.debug.mivi3.output.jpeg`, `persist.sys.hypercam.*`;
- counts of `.cube`/`.png` files in `/odm/etc/camera/videofilter` and
  `/data/vendor/camera`, plus the 14 expected KSU LUT names;
- SELinux mode (`getenforce`) and whether the camera domain is permissive.

Do not dump serial numbers, IMEI, or full `getprop`. Acceptance: one run
produces a section that can be compared across devices without a second tar.

### P1 — add KSU packaging checks

Add a CI or pre-release check for `camera-mod-ksu`:

- exactly 14 LUTs, each 512×512 PNG, expected names;
- LF line endings in `.sh`, `module.prop`, and `customize.sh`;
- `version` and `versionCode` match across `module.prop` and the zip name;
- zip contains no APK, no `system/odm`, no `system/vendor`, no `.so`;
- modes 0644 for data, 0755 for scripts;
- `post-fs-data.sh` and `service.sh` pass `bash -n` / `sh -n`;
- `unzip -t` passes.

Do not add a `verify.py` that silently skips bad zips. A failed check must fail
the build.

### P2 — test the 50 MP / MIVI lead only as an experiment

If a 14U user reports 1.6 MP captures, capture before/after evidence:

- JPEG dimensions and file size;
- `maxPictureSize` / `maxRAWSizes` values;
- whether `mivi_info.json` exists and its SHA-256;
- Camera log lines around `MiviHelperForMiui` / background save;
- whether disabling SR/200MP flags changes the result.

Do not ship `maxPictureSize=6144x8192`, `maxRAWSizes=55`, or a MIVI JSON in the
default module. The related repo's evidence is confounded and its JSON is
generic.

### P2 — keep cloud filters experimental

Keep R600/P100F/F-50 marked cloud/experimental in the UI. The 5.3 LUTs are the
local data source; the remaining question is renderer path selection. Test one
filter at a time with the KSU LUTs staged and the cloud service left enabled.
Do not disable `com.xiaomi.camera.cloud` globally.

### P3 — documentation cleanup

If the related repo is cited in HCM docs, state explicitly:

- “6.2” is the suite version; the camera is 5.0 Beta 8.3;
- only 13U v8.2 is Camera 6.8;
- the 14U/15U/17U Full packages are not 6.8 ports;
- the 14U 5.58 package has no HAL and no `update-binary`;
- the `zzz` XML is malformed and must not be used as a schema example.

## 9. Open questions and device tests

1. Does the 14U 6.8 `FeatureParser` actually poll `/odm/etc/device_features`
   before the paths the related repo writes? The related repo's own audit says
   yes, but its packages do not mount `/odm`.
2. Does `persist.vendor.camera.maxPictureSize=6144x8192` produce a 50 MP JPEG
   on a stock 14U 6.8, or only change the advertised size envelope? Measure the
   output, not the property.
3. Is `mivi_info.json` required on a stock 14U 6.8, or only on the 5.0 port?
   Compare a clean boot with and without the file.
4. Do the Camera 5.3 R600/P100F LUTs render offline in 6.8 when the cloud
   catalogue omits the IDs? Test with the KSU LUTs and cloud enabled.
5. Which 14 Pro (`shennong`) HAL generation is present: Aurora-style
   `camera.xiaomi.so` or Nezha/Madrid-style `camera.qcom.core.so`? This decides
   whether the HCM HAL backport path is even applicable; see
   `docs/hal-backport/07-device-porting.md`.
6. Why does 13U v8.2 ship `ishtar.xml` and privapp permissions but never copy
   them? This looks like a packaging regression, not a deliberate feature.

## Appendix A — release sizes

```text
Leica_13U_Configs_Master_Pack.zip                                    4,807
Mi13U_Camera_5_v6.1_MIUI14_by_borndead.apk                     170,343,998
Mi13U_Camera_5_v6.1_MIUI14_EU_Stable_by_borndead.zip           452,837,147
Mi13U_Camera_6_v8.2_by_borndead.apk                            170,366,483
Mi13U_Master_Camera_Combo_5_v5.48.0_EU_Definitive...zip        252,235,832
Mi13U_Master_Camera_Combo_5_v5.50.0_EU_Definitive...zip        252,242,950
Mi13U_Master_Camera_Combo_5_v5.52.0_EU_Definitive...zip        252,252,630
Mi13U_Master_Camera_Combo_5_v5.53.0_EU_Definitive...zip        252,252,416
Mi13U_Master_Camera_Combo_5_v5.56.0_EU_Definitive...zip        252,252,918
Mi13U_Master_Camera_Combo_5_v5.57.0_EU_Definitive...zip        252,252,949
Mi13U_Master_Camera_Combo_5_v6.2_Full_by_borndead.zip          452,836,968
Mi13U_Master_Camera_Combo_5_v6.2_MasterFinal_by_borndead.zip   452,836,968
Mi13U_Master_Camera_Combo_6_v8.2_Full_by_borndead.zip          296,428,437
Mi13U_Master_Imaging_MOD_5_v6.2_Slim_by_borndead.zip                23,670
Mi14U_Master_Camera_Combo_5_v5.58.0_Eliterom_by_borndead.zip   252,262,348
Mi14U_Master_Camera_Combo_5_v5.58.0_EU_Definitive...zip        252,262,348
Mi14U_Master_Camera_Combo_5_v6.2_Full_by_borndead.zip          211,442,575
Mi14U_Master_Imaging_MOD_5_v6.2_Slim_by_borndead.zip                22,488
Mi15_Master_Camera_Combo_5_v6.2_Full_by_borndead.zip           223,182,804
Mi15_Master_Imaging_MOD_5_v6.2_Slim_by_borndead.zip             11,762,648
Mi15U_Master_Camera_Combo_5_v6.2_Full_by_borndead.zip          320,628,212
Mi15U_Master_Imaging_MOD_5_v6.2_Slim_by_borndead.zip           109,208,056
Mi_AI_Director_Vision_Companion_5_v6.2_by_borndead.zip               6,284
Mi_AI_Master_Camera_Suite_5_v6.2_AllInOne_by_borndead.zip           10,165
Mi_AI_Master_Imaging_AISP_Hardware_5_v6.2_by_borndead.zip            7,398
Mi_AI_Studio_GenAI_ExtraPhoto_5_v6.2_by_borndead.zip                 6,951
Mi_Master_Camera_Combo_5_v6.2_Universal_Full_by_borndead.zip   512,059,658
Mi_Master_Camera_Combo_5_v6.2_Universal_Slim_by_borndead.zip   297,886,752
X17U_Master_Camera_Combo_5_v6.2_Full_by_borndead.zip            388,366,731
X17U_Master_Imaging_MOD_5_v6.2_SimpleRom_ST_NonLeica...zip     177,086,147
X17U_Master_Imaging_MOD_5_v6.2_Slim_by_borndead.zip             176,946,590
```

## Appendix B — verification commands

```bash
# LFS state
cd /run/media/rezikham/970/PoC/Mi_Master_Camera_Combo
git lfs ls-files | wc -l
git lfs fsck
find releases -maxdepth 1 -type f -printf '%f %s\n' | sort

# Zip integrity
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile
for p in sorted(Path('releases').glob('*.zip')):
    with ZipFile(p) as z:
        bad = z.testzip()
    print(p.name, len(z.namelist()), bad)
PY

# APK identity
for f in releases/*.apk; do
  sha256sum "$f"
  aapt2 dump badging "$f" | head -5
done
```

## Appendix C — principal evidence files

- `DETAILED_AUDIT_REPORT.md` — the repository's own post-mortems, including
  the SR/IMX989 freeze, property spoofing, APK rebuild, and packaging traps.
- `CHANGELOG.md` — 1.6 MP/50 MP claims and version history; contains
  contradictions with the actual archives.
- `README.md`, `MANUAL_Mi13U_v6.6_EU_RU.md`, `ADVANCED_SETTINGS_GUIDE_Mi13U.md`
  — user-facing claims, action-menu toggles, and manual workarounds.
- `configs/devices.json` — device/codename map.
- `scripts/`, `tools/verify.py`, `tools/patch_release_modules.py` — build and
  audit behavior.
- `releases/*` — the materialized release payloads analyzed above.
