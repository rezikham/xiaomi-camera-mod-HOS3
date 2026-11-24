# Madrid camera-plugin port (experimental)

Separate KernelSU module for testing the Madrid 6.8 re-edit writer on Aurora.
It is intentionally independent of `camera-mod-ksu/` (the known-good LUT
module) and of the LSPosed APK.

## What it does

- Overlays `com.xiaomi.plugin.jpegrAggr.so` and
  `com.xiaomi.plugin.heifedit.so` from the Xiaomi 18 Pro 6.8 camera stack.
- Supplies their Xiaomi camera dependencies as private `libhcm_*` libraries
  in `/odm/lib64`, so stock `/odm` and `/vendor` libraries are not replaced.
- Leaves platform libraries (`libc`, `liblog`, `libbinder`, ...) alone.

The plugin port is only active if the KernelSU metamodule supports the `/odm`
overlay partition. If the private libraries are not visible at
`/odm/lib64/libhcm_pluginutils.so`, `post-fs-data.sh` logs a warning and the
stock Aurora plugins remain in use.

## Build

```bash
python3 tools/prepare-plugin-port.py \
  --madrid /run/media/rezikham/970/PoC/extracted_madrid \
  --patchelf /tmp/opencode/bin/patchelf
python3 tools/build-plugin-module.py camera-mod-ksu-plugin-v0.1.0.zip
```

`prepare-plugin-port.py` copies the Madrid binaries, renames their SONAMEs and
`DT_NEEDED` entries into the `libhcm_*` namespace, and verifies that no ported
object still asks for a stock Xiaomi library by name.

## Install / rollback

Install the ZIP in KernelSU Manager and reboot. This is separate from the LUT
module; both can be installed at once.

The module ships an empty `enable` marker. `post-fs-data.sh` bind-mounts the
Madrid plugins only when that marker exists **and** all private libraries are
visible. To fall back to the stock Aurora plugins, delete the marker and
reboot:

```bash
su -c 'rm /data/adb/modules/hypercameramod_plugin/enable'
su -c 'reboot'
```

You can also disable the whole module:

```bash
su -c 'touch /data/adb/modules/hypercameramod_plugin/disable'
su -c 'reboot'
```

After boot, check:

```bash
su -c 'logcat -d -s HyperCameraModPlugin'
su -c 'ls -l /odm/lib64/libhcm_pluginutils.so'
```

Then confirm the camera provider loaded the new plugin:

```bash
adb logcat -d | grep -iE "FullLinkEditableJpeg|jpegrAggr|heifedit|reedit"
```

## Risk

The Madrid plugin was built for the 6.8 camera HAL. Aurora is 6.7. The plugin
receives `MiMetadata`/`ProcessRequestInfo` objects from the HAL, so a changed
C++ ABI can crash the camera provider. Keep this module separate and disable
it if the camera does not start. The next step is symbol/ABI validation on the
device, not further feature work.
