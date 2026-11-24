package com.codex.leicaapp;

import android.graphics.Bitmap;

/**
 * JNI trampoline for the genuine Leica model library taken from Essential Moments
 * (com.martin.essentialmoments 6.8.001960.0).
 *
 * <p>The native library binds its entry points by class and method name, so this class has to use
 * the original package and name. It is only ever loaded in the module's private
 * {@code :essential} process; the camera process never loads libm9base.
 */
public final class LegendaryPostProcessor {

    private LegendaryPostProcessor() {
    }

    /**
     * Applies the parametric Leica M3/M9 filter stored in a legend18 .bin file.
     *
     * @param lux   measured scene lux, or 200 when unknown (the app's own default)
     * @param cct   measured colour temperature, or 5500 when unknown
     * @param zoom  display zoom ratio, clamped to 0.1..200 by the native code
     * @param phase uptime in milliseconds modulo 60000; used as a stable grain seed
     */
    public static native boolean applyOriginalFilter(Bitmap bitmap, String modelPath,
                                                     float lux, float cct, float zoom,
                                                     float phase);

    /**
     * M9 colour grade. This is the app's {@code applyWarmOriginalFilter} call with the
     * M9 density ({@code -0.95 EV}) and neutral warmth ({@code 0.3}); it is the stage the
     * 17 Ultra runs after the style-transfer model.
     */
    public static native boolean applyWarmOriginalFilter(Bitmap bitmap, String modelPath,
                                                         float lux, float cct, float zoom,
                                                         float phase, float denoise,
                                                         float densityEv, float warmth);

    /**
     * Runs the Monopan helper. This is the stage that segfaults in a foreign app process on
     * aurora. It is only called from the module's private process, and the caller treats a
     * false return or a process death as "grain unavailable" and falls back to grayscale.
     */
    public static native boolean applyMonochrome(Bitmap bitmap, String runnerPath,
                                                 String cachePath);
}
