package com.codex.leicaapp;

/**
 * Histogram/tone helpers from the 17 Ultra Moment App's FilmInput stage.
 * Kept in the original package so the shader inputs match the app.
 */
public final class FilmTone {

    private FilmTone() {
    }

    public static float linear(float v) {
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
    }

    public static float encoded(float v) {
        return v <= 0.0031308f ? v * 12.92f
                : ((float) Math.pow(v, 0.4166666666666667)) * 1.055f - 0.055f;
    }

    public static float luma(float r, float g, float b, boolean p3) {
        return encoded(linear(r) * (p3 ? 0.22897457f : 0.2126f)
                + linear(g) * (p3 ? 0.69173855f : 0.7152f)
                + linear(b) * (p3 ? 0.07928691f : 0.0722f));
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    public static float smooth(float low, float high, float value) {
        float t = clamp((value - low) / (high - low));
        return t * t * (3f - 2f * t);
    }

    public static float percentile(int[] histogram, float fraction) {
        long total = 0;
        for (int count : histogram) {
            total += count;
        }
        if (total == 0) {
            return 0.5f;
        }
        long target = Math.max(1L, (long) Math.ceil(total * fraction));
        long seen = 0;
        for (int i = 0; i < histogram.length; i++) {
            seen += histogram[i];
            if (seen >= target) {
                return i / 255f;
            }
        }
        return 1f;
    }

    public static float gamma(int[] histogram, float iso) {
        float mid = 1f - smooth(0.18f, 0.55f, percentile(histogram, 0.5f));
        float highIso = 0f;
        if (Float.isFinite(iso) && iso > 0) {
            highIso = smooth(200f, 1600f, iso);
        }
        return mid * (0.14f - highIso * 0.07f) + 1.08f;
    }

    public static float denoise(float iso) {
        return (!Float.isFinite(iso) || iso <= 0) ? 0f : smooth(400f, 2400f, iso);
    }

    public static float whitePoint(int[] histogram) {
        float high = percentile(histogram, 0.998f);
        float low = percentile(histogram, 0.1f);
        if (high < 0.82f || high >= 0.98f || high - low < 0.25f) {
            return 1f;
        }
        return Math.max(0.9f, high + 0.015f);
    }

    public static float detailStrength(int[] histogram, float iso) {
        float noise = (!Float.isFinite(iso) || iso <= 0) ? 0.4f : smooth(400f, 1600f, iso);
        return (1f - noise) * 0.12f * smooth(0.08f, 0.35f, percentile(histogram, 0.5f));
    }
}
