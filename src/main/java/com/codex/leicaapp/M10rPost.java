package com.codex.leicaapp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.Log;

/**
 * M10R (CMOS) post stage from the Essential Moments app's M10rBin.
 *
 * <p>The 18 Pro Max M10R look is the M10R filter plus the HAL's M10R style
 * model and legendCT. Those two stages need the 18 Pro Max QNN/HAL runtime,
 * which cannot run on the 14 Ultra. This is the app's own local M10R
 * reconstruction: restore the input white balance after the filter, soften
 * slightly, then apply the vignette and grain. It is what makes CMOS visibly
 * different from CCD/M9; the defaults are the app's M10rBin defaults.
 */
public final class M10rPost {

    private static final String TAG = "M10rPost";
    private static final float SATURATION = 1.0f;
    private static final float VIGNETTE = 0.3f;
    private static final float GRAIN = 0.3f;
    private static final float DETAIL = 0.3f;
    private static final float AWB = 0.8f;

    private M10rPost() {
    }

    /** Average R/G and B/G, sampled like M10rBin.balance. */
    public static float[] balance(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int stepX = Math.max(1, width / 256);
        int stepY = Math.max(1, height / 512);
        long count = 0;
        double red = 0;
        double green = 0;
        double blue = 0;
        for (int y = stepY / 2; y < height; y += stepY) {
            for (int x = stepX / 2; x < width; x += stepX) {
                int pixel = bitmap.getPixel(x, y);
                red += (pixel >> 16) & 255;
                green += (pixel >> 8) & 255;
                blue += pixel & 255;
                count++;
            }
        }
        if (count == 0 || green <= 0) {
            return null;
        }
        return new float[]{(float) (red / green), (float) (blue / green)};
    }

    public static void after(Bitmap bitmap, float[] beforeBalance) {
        if (bitmap == null || bitmap.isRecycled() || !bitmap.isMutable()) {
            return;
        }
        try {
            restoreBalance(bitmap, beforeBalance);
            soften(bitmap);
            finish(bitmap);
        } catch (Throwable t) {
            Log.w(TAG, "M10R post failed", t);
        }
    }

    private static void restoreBalance(Bitmap bitmap, float[] before) {
        if (before == null) {
            return;
        }
        float[] after = balance(bitmap);
        if (after == null || after[0] <= 0 || after[1] <= 0) {
            return;
        }
        float gainR = clamp((float) Math.pow(before[0] / after[0], AWB), 0.8f, 1.2f);
        float gainB = clamp((float) Math.pow(before[1] / after[1], AWB), 0.8f, 1.2f);
        Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (copy == null) {
            return;
        }
        ColorMatrix matrix = new ColorMatrix(new float[]{
                gainR, 0, 0, 0, 0,
                0, 1, 0, 0, 0,
                0, 0, gainB, 0, 0,
                0, 0, 0, 1, 0});
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        new Canvas(bitmap).drawBitmap(copy, 0f, 0f, paint);
        copy.recycle();
        Log.i(TAG, "balance before=" + before[0] + "," + before[1]
                + " after=" + after[0] + "," + after[1]
                + " gains=" + gainR + "," + gainB);
    }

    private static void soften(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Bitmap small = Bitmap.createScaledBitmap(bitmap,
                Math.max(1, width / 2), Math.max(1, height / 2), true);
        Bitmap up = Bitmap.createScaledBitmap(small, width, height, true);
        if (small != bitmap) {
            small.recycle();
        }
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setAlpha(Math.round(255f * DETAIL));
        new Canvas(bitmap).drawBitmap(up, 0f, 0f, paint);
        up.recycle();
    }

    private static void finish(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float saturation = Math.max(0f, Math.min(2f, SATURATION));
        float vignette = Math.max(0f, Math.min(1f, VIGNETTE));
        float grain = Math.max(0f, Math.min(1f, GRAIN));
        if (Math.abs(saturation - 1f) < 0.005f && vignette <= 0f && grain <= 0f) {
            return;
        }

        float[] vignetteTable = new float[1025];
        for (int i = 0; i <= 1024; i++) {
            float r = (float) Math.sqrt((i * 0.390625f) / 1024f);
            float s = smooth(1.4f, 0.2f, r);
            vignetteTable[i] = 1f - (1f - ((1f - s) * 0.2f + s)) * vignette;
        }
        float[] xVignette = new float[width];
        for (int x = 0; x < width; x++) {
            float t = (((x + 0.5f) / width) - 0.5f) * 0.75f;
            xVignette[x] = t * t;
        }
        int nano = ((int) (System.nanoTime() & 0x7FFFFFFFL)) | 1;
        int[] pixels = new int[width * 256];
        for (int y0 = 0; y0 < height; y0 += 256) {
            int rows = Math.min(256, height - y0);
            bitmap.getPixels(pixels, 0, width, 0, y0, width, rows);
            for (int row = 0; row < rows; row++) {
                int y = y0 + row;
                float yv = ((y + 0.5f) / height) - 0.5f;
                float yVignette = yv * yv;
                int seed = (y * (-1640531535)) ^ nano;
                if (seed == 0) {
                    seed = 1;
                }
                int base = row * width;
                for (int x = 0; x < width; x++) {
                    int pixel = pixels[base + x];
                    float r = (pixel >> 16) & 255;
                    float g = (pixel >> 8) & 255;
                    float b = pixel & 255;
                    if (Math.abs(saturation - 1f) >= 0.005f) {
                        float luma = r * 0.299f + g * 0.587f + b * 0.114f;
                        r = luma + (r - luma) * saturation;
                        g = luma + (g - luma) * saturation;
                        b = luma + (b - luma) * saturation;
                    }
                    int index = (int) ((xVignette[x] + yVignette) * 2621.44f);
                    if (index > 1024) {
                        index = 1024;
                    }
                    float factor = vignetteTable[index];
                    if (grain > 0f) {
                        seed = seed ^ (seed << 13);
                        seed = seed ^ (seed >>> 17);
                        seed = seed ^ (seed << 5);
                        float luma = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f;
                        factor = (0.14f * grain * (1f - luma * 0.6f)
                                * (((((seed >>> 24) & 255) + ((seed >>> 16) & 255)
                                + ((seed >>> 8) & 255)) - 382) * 0.007874016f)
                                + 1f) * factor;
                    }
                    int nr = clamp255(r * factor);
                    int ng = clamp255(g * factor);
                    int nb = clamp255(b * factor);
                    pixels[base + x] = (pixel & 0xFF000000) | (nr << 16) | (ng << 8) | nb;
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, y0, width, rows);
        }
        Log.i(TAG, "finish saturation=" + saturation + " vignette=" + vignette
                + " grain=" + grain);
    }

    private static int clamp255(float value) {
        int v = Math.round(value);
        return v < 0 ? 0 : Math.min(255, v);
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    private static float smooth(float low, float high, float value) {
        float t = Math.max(0f, Math.min(1f, (value - low) / (high - low)));
        return t * t * (3f - 2f * t);
    }
}
