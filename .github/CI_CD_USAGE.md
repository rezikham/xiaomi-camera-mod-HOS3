# GitHub Actions CI/CD for Xiaomi Camera Mod

This repository includes GitHub Actions workflows for building and installing the modules.

## Workflows

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `ci.yml` | PR/push to main | Validate build, compile check, create release APKs |
| `build-and-install.yml` | Manual (`workflow_dispatch`) | Build selected module(s) and optionally install to device via ADB |

---

## Quick Start: Build & Install to Xiaomi 14 Ultra

### Prerequisites on Xiaomi 14 Ultra

1. **Enable Developer Options:**
   - Settings → About phone → Tap "MIUI version" 7 times
   - Settings → Additional settings → Developer options

2. **Enable USB Debugging:**
   - Developer options → USB debugging → ON

3. **Enable Install via USB (optional but recommended):**
   - Developer options → Install via USB → ON (requires Mi account)

4. **For WiFi ADB (recommended for CI):**
   - Developer options → Wireless debugging → ON
   - Pair with pairing code or use `adb pair IP:PORT`
   - Note the IP:PORT shown (e.g., `192.168.1.100:5555`)

### Option A: Using GitHub Actions (Self-hosted Runner)

If you have a machine on the same network as the phone (e.g., a laptop, Raspberry Pi, or server):

1. **Add self-hosted runner to GitHub repo:**
   - Repo Settings → Actions → Runners → New self-hosted runner
   - Follow setup instructions (Linux x64)
   - Run as service: `sudo ./svc.sh install && sudo ./svc.sh start`

2. **Trigger build & install:**
   - Go to Actions → "Build and Install Xiaomi Camera Mod" → Run workflow
   - Select module: `cameramod` (recommended) or `app`
   - Check "Install to device via ADB"
   - Enter ADB TCP/IP: `192.168.1.100:5555` (your phone's WiFi ADB address)
   - Click "Run workflow"

### Option B: Local Build + ADB Install

```bash
# On your machine with ADB access to the phone
cd /home/rezikham/PoC/xiaomi-camera-mod

# Build
./gradlew :cameramod:assembleRelease --no-daemon

# Install via USB
adb install -r cameramod/build/outputs/apk/release/cameramod-release.apk

# Or install via WiFi ADB
adb connect 192.168.1.100:5555
adb install -r cameramod/build/outputs/apk/release/cameramod-release.apk

# Force stop Camera to reload module
adb shell am force-stop com.android.camera
```

---

## CI Workflow Details

### `ci.yml` (Automatic on PR/Push)

- Sets up JDK 21 + Android SDK (API 37, build-tools 37.0.0)
- Compiles both modules (`compileReleaseJavaWithJavac`)
- Builds release APKs (`assembleRelease`)
- Uploads APKs as artifacts (7-day retention)

### `build-and-install.yml` (Manual)

**Inputs:**
| Input | Type | Default | Description |
|-------|------|---------|-------------|
| `module` | choice | `cameramod` | `cameramod`, `app`, or `both` |
| `install` | boolean | `false` | Whether to install after build |
| `adb_device_serial` | string | (empty) | Specific device serial if multiple |
| `adb_tcp_ip` | string | (empty) | WiFi ADB address (e.g., `192.168.1.100:5555`) |

**Behavior:**
1. Builds selected module(s) on GitHub-hosted Ubuntu runner
2. Uploads APKs as artifacts
3. If `install=true`: Downloads APKs on self-hosted runner and installs via ADB
4. Force-stops Camera app to reload Xposed module

---

## Troubleshooting

### Build Fails

```bash
# Check locally first
./gradlew :cameramod:compileReleaseJavaWithJavac --no-daemon
```

Common issues:
- **SDK not found:** Ensure `local.properties` has `sdk.dir` (not needed in CI, uses `setup-android`)
- **Gradle version:** Wrapper in repo should work
- **Out of memory:** Add `org.gradle.jvmargs=-Xmx4g` to `gradle.properties`

### ADB Connection Issues

| Problem | Solution |
|---------|----------|
| `adb: device unauthorized` | Accept RSA fingerprint on phone screen |
| `adb: connection refused` | Check WiFi ADB is enabled, correct IP:PORT |
| `adb: more than one device` | Use `adb_device_serial` input |
| `Install failed: test only` | Add `-r` flag (workflow does this) |

### Module Not Loading in Camera

1. **Check LSPosed/Vector:** Module enabled? Scope set to `com.android.camera` only?
2. **Check logs:**
   ```bash
   adb logcat -c
   # Open Camera
   adb logcat -d -s HyperCameraMod
   ```
3. **Force stop Camera:** Required after every toggle change
4. **Verify installation:**
   ```bash
   adb shell pm list packages | grep hypercamera
   # Should show: package:io.martin.hypercameramod
   ```

---

## APK Output Locations

| Module | Package | APK Path |
|--------|---------|----------|
| HyperCameraMod | `io.martin.hypercameramod` | `cameramod/build/outputs/apk/release/cameramod-release.apk` |
| HyperFilmFilters | `io.martin.hyperfilters` | `app/build/outputs/apk/release/app-release.apk` |

---

## Version Info

- **compileSdk:** 37 (Android 14)
- **buildTools:** 37.0.0
- **minSdk:** 26 (Android 8.0)
- **targetSdk:** 37
- **Java:** 21
- **Xposed API:** libxposed 102.0.0

---

## Security Notes

- **Self-hosted runner:** Required for ADB install (device access). Never expose ADB over public networks.
- **WiFi ADB:** Only enable on trusted networks. Disable when not in use.
- **Artifacts:** APKs uploaded to GitHub Actions (7-day retention). No secrets in APKs.