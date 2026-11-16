# Mount hiding — initial implementation

## Goal

Stop Duck-Detector's **Mount** detector from flagging this module's systemless
files as root evidence.  Duck-Detector reads `/proc/self/mounts` and
`/proc/self/mountinfo` and reports:

* Magisk / systemless-root bind-mount entries
* writable or overlaid system partitions
* loop devices and dm-verity bypass patterns
* mount-ID discontinuities and anonymous-device gaps
* namespace identity vs init
* hidden-library paths in `/proc/self/maps`

This module hides the *module's own* mount entries from **this process's**
mount namespace so they no longer appear in those tables.

## Design

Two backends are tried, in order:

| Backend | Reference project | How it works | Mount table impact |
|---------|-------------------|--------------|-------------------|
| **NoMount keyring** (preferred) | [maxsteeel/nomount](https://github.com/maxsteeel/nomount) | Sends `NM_CMD_ADD_RULE` / `NM_FLAG_WHITEOUT` rules via `add_key("nomount", "trigger", &ptr, 8, -1)`. Pure in-kernel VFS path redirection — no real mount is created. | **None** — invisible to `/proc/self/mounts` |
| **Bind-mount** (fallback) | [meta-hybrid_mount](https://github.com/Hybrid-Mount/meta-hybrid_mount) | `unshare(CLONE_NEWNS)` + bind-mount empty staging files over module paths. Real mount, but private to this namespace. | Hidden from this namespace only |

Both draw on the **susfs4ksu / BRENE** concept of per-process mount-view
isolation: the kernel-level SUSFS approach is the only way to hide from
*every* namespace; this userspace implementation hides from *this process's*
namespace only.

## Backend selection

`nativeInit()` tries the backends in order:

1. **NoMount keyring** — calls `nm_keyring_whiteout()` for each path in the
   hide list.  If at least one rule is accepted by the kernel provider, the
   NoMount backend is marked active and the bind-mount backend is skipped.
2. **Bind-mount** — enters a private mount namespace via
   `unshare(CLONE_NEWNS)`, marks `/` `MS_PRIVATE`, then bind-mounts empty
   files over each path in the hide list.  Requires `CAP_SYS_ADMIN` (root).

`nativeHidePath()` uses whichever backend was selected at init time.

### NoMount keyring protocol

The userspace side of NoMount (`maxsteeel/nomount/userspace/src/nm.h`)
communicates with the kernel provider via the Linux keyring:

```c
struct nm_payload {
    unsigned long long magic;   // 0x4E4F4D4F554E54 ("NOMOUNT")
    unsigned int cmd;           // NM_CMD_ADD_RULE = 2
    unsigned int target_uid;    // 0 = all UIDs
    int status;                 // -1 initially, filled by kernel
    unsigned int arg1;
    unsigned int data_size;
    char buffer[4068];          // nm_rule_hdr + vpath + rpath
} __attribute__((packed));

struct nm_rule_hdr {
    unsigned int flags;         // NM_FLAG_WHITEOUT = 4
    unsigned int uid;
    unsigned int uid_end;
    unsigned int deny_start;
    unsigned int deny_end;
    unsigned short v_len;       // virtual path length
    unsigned short r_len;       // real path length (0 for whiteout)
} __attribute__((packed));
```

The payload pointer (8 bytes) is passed as the `add_key` payload:

```c
syscall(SYS_add_key, "nomount", "trigger", &ptr, sizeof(ptr), -1);
```

where `ptr` is the address of the `struct nm_payload`.  The kernel
dereferences it to read the full rule.

## Files

```
src/main/java/io/martin/hypercameramod/MountHide.java   # Java wrapper
src/main/jni/mount_hide/CMakeLists.txt                   # NDK build
src/main/jni/mount_hide/mount_hide.c                     # native implementation
src/main/java/io/martin/hypercameramod/ModuleMain.java   # integration (one call)
build.gradle.kts                                         # externalNativeBuild + ndkVersion
docs/mount-hiding.md                                     # this document
```

## Build requirements

* Android NDK `27.0.12077973` (declared via `ndkVersion` in `build.gradle.kts`)
* CMake `3.22.1+` (bundled with the SDK)
* The `.so` is built by `externalNativeBuild` and packaged automatically; no
  manual `ndk-build` step.

Build locally:

```sh
./gradlew :assembleRelease
```

## Limitations (read before relying on this)

1. **NoMount backend requires the kernel module.**  The keyring syscall
   returns an error if no NoMount provider is loaded (stock kernel).  The
   module then falls back to bind-mounts, which need `CAP_SYS_ADMIN`
   (root).  On a non-rooted, non-NoMount device every call fails silently
   and the camera keeps working normally.
2. **Per-process only.**  Mounts/VFS rules are hidden in *this process's*
   namespace.  Duck-Detector runs some detectors in dedicated/isolated
   services whose namespaces are separate, so those instances may still see
   the module mounts.  Kernel-level SUSFS on a custom kernel
   (susfs4ksu / BRENE approach) is the only way to hide from every
   namespace.
3. **Not a root remover.**  This hides mount entries / adds VFS whiteout
   rules; it does not remove root, su binaries, or other Duck-Detector
   signals (SU, Zygisk, SELinux, bootloader, etc.).

## Testing

On a rooted 14 Ultra (KernelSU), after installing the module:

```sh
# Confirm the module's mounts are hidden from this namespace
cat /proc/self/mounts | grep -E 'modules|ksu|hypercam'
# should return nothing after MountHide.init() has run

# Compare against a process that did not initialize MountHide
cat /proc/1/mounts | grep -E 'modules|ksu'
```

If NoMount is loaded, also check:

```sh
nm rule list
# should show whiteout rules for /data/adb/modules, /data/adb/ksu, etc.
```

## Future work

* Hook `/proc/self/maps` reads (`openat`/`read`) to hide hidden-library
  paths from Duck-Detector's Zygisk/Next map scan.
* Extend the hide list at runtime from the module settings UI.
* Add UID-scoped NoMount rules so root detectors in other apps see a clean
  view while this module's process stays hidden.

## License

This file and the mount-hiding code are part of HyperCameraMod and are
licensed under the project LICENSE (MIT).  Referenced projects retain their
own licenses (see their repositories):
* maxsteeel/nomount — GPL-2.0 (kernel) / GPL-3.0 (userspace)
* Hybrid-Mount/meta-hybrid_mount — GPL-3.0 (core) / Apache-2.0 (WebUI)
* sidex15/susfs4ksu-module — GPL-3.0
* rrr333nnn333/BRENE — AGPL-3.0
* PerformanC/ReZygisk — AGPL-3.0
