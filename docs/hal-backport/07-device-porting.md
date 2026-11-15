# Choosing a path for another device (14 Pro Ti and similar)

No 14 Pro Ti dump is present in this workspace, so this is a decision procedure,
not a claim about that firmware. Run the inventory first.

## Inventory that decides the path

From the target `odm` (or a root dump of `/odm`):

```text
odm/lib64/libmialgoengine.so
odm/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so
odm/etc/camera/leica_filter_param*.bin
odm/etc/camera/xiaomi/legendsnapshot.json
odm/lib64/hw/camera.xiaomi.so        # Aurora-style split HAL
odm/lib64/hw/camera.qcom.core.so    # Nezha/Madrid-style core
```

Check:

```bash
nm -D odm/lib64/libmialgoengine.so | grep -E 'MiaPluginModule|getPluginWraperByPluginName'
grep -a -l 'mileicafilter' odm/lib64/libmialgoengine.so
grep -a -l 'LegendModeM9\|LegendM10R' odm/lib64/libmialgoengine.so
```

## Decision tree

| Target has | Best path |
| --- | --- |
| `mileicafilter`, params, `legendsnapshot.json`, Legend strings | Already native; module UI only |
| Aurora-style `camera.xiaomi.so`, older engine, no Legend strings | **Legacy graph first**, then registry patch for production |
| Nezha/Madrid-style `camera.qcom.core.so`, `MiaPluginModule`, no plugin | **Patch the registry**; engine already has the newer loader |
| Camera 6.4 or older | No Legend UI; film filters only |

## Why legacy is more portable

The legacy path uses `MiaDLibrary::load(path)` and does not require the
device's `MiaPluginModule` to know the plugin name. On an Aurora-like HAL this
is the shortest route to prove plugin execution:

```text
Camera -> LeicaSnapshot graph -> MiaDLibrary -> mileicafilter
```

It still needs the plugin's symbol compatibility (`catchLogEncryptLog`,
`miexifmgr`, `camdfx`), and it may lose SAT-specific nodes, gainmap handling,
or HDR behavior. Treat it as a diagnostic and a possible fallback, not the
ideal production path.

## Why patching is better long-term

The patched path keeps the device's normal SAT pipeline and only teaches the
engine to register `com.xiaomi.plugin.mileicafilter`. If the target engine
already has a modern `MiaPluginModule` (Nezha/Madrid generation), this is
smaller and preserves image quality. If the engine is the older Aurora one,
the registry itself has to be ported, which is the current blocker on the
14 Ultra.

## Expected case for 14 Pro Ti

The 14 Pro Ti is the same Snapdragon 8 Gen 3 / HyperOS 3 family as the
14 Ultra. If its HAL is the Aurora-style split (`camera.xiaomi.so` plus an
older `libmialgoengine`), the legacy graph is the faster first experiment, and
the registry patch is the longer-term fix. If it ships the newer
`camera.qcom.core.so` generation, prefer the registry patch.

Either way, the app-side compatibility toggle is device-agnostic and needs no
HAL changes.
