package com.codex.leicaapp;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * JNI trampoline for the app's {@code B43Tone} stage.
 *
 * <p>The 17 Ultra applies this fixed 17³ tone LUT to M9 only; the app's own
 * {@code B43Tone.apply(bitmap, m3)} returns early for M3. The library is normally
 * loaded from the module's native library directory because the camera process
 * cannot resolve the module's libraries by soname alone.
 */
public final class B43Tone {

    private static final String TAG = "B43Tone";
    private static volatile boolean loaded;

    static {
        try {
            System.loadLibrary("b43tone");
            loaded = true;
        } catch (Throwable t) {
            // The caller loads it explicitly from the module APK on Android 11+.
            Log.i(TAG, "b43tone not loadable by soname: " + t);
        }
    }

    private B43Tone() {
    }

    /** Loads the bundled copy from the module's extracted native library directory. */
    public static synchronized boolean load(String nativeLibraryDir) {
        if (loaded) {
            return true;
        }
        try {
            System.load(nativeLibraryDir + "/libb43tone.so");
            loaded = true;
            Log.i(TAG, "libb43tone loaded from " + nativeLibraryDir);
        } catch (Throwable t) {
            Log.w(TAG, "cannot load libb43tone", t);
        }
        return loaded;
    }

    private static native boolean applyNative(Bitmap bitmap);

    public static boolean apply(Bitmap bitmap, boolean m3) {
        if (m3 || !loaded || bitmap == null || bitmap.isRecycled() || !bitmap.isMutable()) {
            return false;
        }
        try {
            return applyNative(bitmap);
        } catch (Throwable t) {
            Log.w(TAG, "tone stage failed", t);
            return false;
        }
    }
}
