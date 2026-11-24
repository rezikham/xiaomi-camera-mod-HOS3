# Madrid plugin port (experimental, separate module)

Goal: test whether the Madrid 6.8 `jpegrAggr`/`heifedit` plugins can write the
Gallery re-edit payload on Aurora 6.7. This is a separate KernelSU module; the
known-good LUT module and the LSPosed APK are untouched.

## Module

```text
camera-mod-ksu-plugin/
  module.prop                 id=hypercameramod_plugin
  enable                      marker; delete to keep stock plugins
  data/plugins/               Madrid jpegrAggr + heifedit
  odm/lib64/libhcm_*.so       private Xiaomi dependency namespace
  vendor/lib64/libhcm_*.so    same files for vendor-first linker namespaces
  odm/etc/camera/xiaomi/      Madrid MiExif.json + videoexif.jsonc
```

Built ZIP: `camera-mod-ksu-plugin-v0.1.0.zip` (about 12 MB).

## Private namespace

Every ported Xiaomi library is renamed to `libhcm_*` and its SONAME plus all
`DT_NEEDED` references are patched with `patchelf`. The two plugins are patched
the same way. This means the Madrid plugin loads its own copies of:

```text
pluginutils metadatautils debug camdfx camlog metatagwrapper
miexifmgr oisexif configmanager threadpool abnormal catchlog
cameraopt_stub opbeanmsg legendmsg protobuf-full protobuf-lite
image_io ultrahdr cameraheif exif globalpool jpegencoder jpegdecoder
jpeg c++_shared crypto ssl lz4
```

Platform libraries (`libc`, `liblog`, `libcamera_metadata`, `libbinder`,
`libui`, `libbase`, `libhidl*`, `libEGL`, `libGLESv3`, ...) keep their stock
names and are not replaced.

## Activation

`post-fs-data.sh`:

1. exits if `enable` is absent;
2. waits up to 10 s for all `libhcm_*` libraries in `/odm/lib64` or
   `/vendor/lib64`;
3. fixes modes and labels;
4. bind-mounts the two plugins over
   `/odm/lib64/camera/plugins/`.

If the private libraries are not visible (no metamodule `/odm`/`/vendor`
overlay), it keeps the stock plugins. If the bind mount fails, the stock plugin
also remains.

## Why the plugins are not in the overlay

The plugins are the risky part: the Madrid 6.8 plugin receives
`MiMetadata`/`ProcessRequestInfo` from the Aurora 6.7 HAL, so a changed C++
ABI can crash the camera provider. Keeping them in `data/plugins` and
bind-mounting only when the `enable` marker exists gives a clean fallback:
delete `enable` and reboot.

The private libraries are safe to leave overlaid; nothing loads `libhcm_*`
unless the Madrid plugins are mounted.

## Test plan

1. Install both modules, reboot.
2. `logcat -d -s HyperCameraModPlugin` must show `plugin port active`.
3. Open camera, take a JPEG with M3/M9 or a film filter.
4. Check the result:

```bash
adb logcat -d | grep -iE "FullLinkEditableJpeg|jpegrAggr|heifedit|micamedit|reedit"
```

5. Dump the JPEG metadata:

```bash
exiftool -a -G1 -s IMG_*.jpg | grep -iE "reedit|micamedit|Filter|Style"
```

Expected on success: `reedit`/`micamedit` keys and a non-zero payload length.
If the camera provider crashes, delete `enable`, reboot, and capture the
crash log before retrying.
