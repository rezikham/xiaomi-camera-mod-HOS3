package io.martin.hypercameramod;

/**
 * Mount hiding for the HyperCameraMod LSPosed module.
 *
 * Duck-Detector's Mount detector reads /proc/self/mounts and
 * /proc/self/mountinfo and flags systemless-root bind mounts, overlays,
 * loop devices and dm-verity bypass patterns.  MountHide moves this
 * process into a private mount namespace (unshare CLONE_NEWNS) and
 * bind-mounts empty files over the module's own mount points so they no
 * longer appear in this namespace's mount tables.
 *
 * Design notes (see docs/mount-hiding.md for the full rationale):
 *
 *   * Requires CAP_SYS_ADMIN (root).  Without root every call degrades
 *     silently and the camera keeps working.
 *   * Hides mounts only in this process's namespace.  Duck-Detector runs
 *     some detectors in isolated services whose namespaces are separate;
 *     kernel-level SUSFS (custom kernel) is the only way to hide from
 *     every namespace.
 *   * Native side lives in src/main/jni/mount_hide/ and is built with the
 *     Android NDK via externalNativeBuild.
 */
public final class MountHide {

    static {
        System.loadLibrary("mounthide");
    }

    private MountHide() {
    }

    /** Whether the native library loaded and the device appears rooted. */
    public static native boolean nativeIsAvailable();

    /** Enter a private mount namespace and hide the default root mount paths. */
    public static native void nativeInit();

    /** Bind-mount {@code source} over {@code target} in this namespace. */
    public static native void nativeHidePath(String target, String source);

    private static boolean sInited = false;

    /**
     * Call once from ModuleMain.onPackageLoaded (or before any
     * Duck-Detector mount scan runs in this process).  Safe to call
     * repeatedly.
     */
    public static synchronized void init() {
        if (sInited) {
            return;
        }
        if (!nativeIsAvailable()) {
            // Not root: nothing we can hide, but keep the module alive.
            sInited = true;
            return;
        }
        nativeInit();
        sInited = true;
    }

    /**
     * Hide an additional path by bind-mounting {@code source} over it.
     * Call after {@link #init()}.
     */
    public static void hidePath(String target, String source) {
        if (!sInited) {
            init();
        }
        nativeHidePath(target, source);
    }
}
