# Windows HAL dump

`dump-hal.ps1` collects the Xiaomi Camera HAL from connected phones on Windows.

## Requirements

- Windows 10 1803 or later (the script uses the built-in `tar.exe`) and
  PowerShell 5.1 or later.
- USB debugging enabled and the phone authorized.
- **adb:** if `adb.exe` is already in `PATH`, that copy is used. Otherwise the
  script downloads Google's official platform-tools from
  `https://dl.google.com/android/repository/platform-tools-latest-windows.zip`
  into `platform-tools\` next to the script and uses that copy, so a separate
  Android SDK install is not needed. The download happens once; after that the
  folder works offline.

The platform-tools binaries are **not** committed to this repository. They are
fetched on first use (Google's license applies to that download).

## Usage

```powershell
powershell -ExecutionPolicy Bypass -File dump-hal.ps1
powershell -ExecutionPolicy Bypass -File dump-hal.ps1 -Serial <serial>
powershell -ExecutionPolicy Bypass -File dump-hal.ps1 -FullConfig
powershell -ExecutionPolicy Bypass -File dump-hal.ps1 -SkipTuning
```

| Option | Meaning |
| ------ | ------- |
| `-Serial <serial>` | dump only this adb serial (default: every connected device) |
| `-OutDir <dir>` | output root (default `.\hal-dump`) |
| `-FullConfig` | also pull `/odm/etc/camera` (models and tuning, several GB) |
| `-SkipTuning` | skip `/vendor/lib64/camera` and the odm plugin directories |

## Output

```
hal-dump\<codename>-<model>-<os>\
  DEVICE.txt               model, market, OS, Android/API, fingerprint, camera, root, date
  props.txt
  dumpsys-media-camera.txt
  camera-package.txt
  apk\                     Camera APK and its splits
  root\vendor\..., root\odm\...   the staged HAL files
  MANIFEST.txt             sizes and sha256 of everything collected
```

As with the other scripts, some files need root on the phone. When `su` is
available the script stages them through `su -c`; without root the dump is
partial.
