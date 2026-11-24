package io.martin.hypercameramod;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipFile;

/**
 * 64^3 Leica colour LUT stored as a 512x512 PNG (8x8 tiles).
 *
 * <p>Tile index is blue, tile row is green, tile column is red. The LUTs come from the Nezha
 * (17 Ultra) firmware ({@code /odm/etc/camera/videofilter/*.png}) and are bundled as assets.
 */
final class LeicaLut {

    private static final int SIZE = 64;
    private static final int TILE = 64;
    private static final int TILES = 8;

    private final int[] table = new int[SIZE * SIZE * SIZE];

    private LeicaLut() {
    }

    static LeicaLut loadFromApk(String apkPath, String asset) {
        try (ZipFile zip = new ZipFile(apkPath);
             InputStream in = zip.getInputStream(zip.getEntry("assets/" + asset))) {
            Bitmap src = BitmapFactory.decodeStream(in);
            if (src == null || src.getWidth() != TILE * TILES || src.getHeight() != TILE * TILES) {
                return null;
            }
            LeicaLut lut = new LeicaLut();
            int[] row = new int[src.getWidth()];
            for (int b = 0; b < SIZE; b++) {
                int ty = (b / TILES) * TILE;
                int tx = (b % TILES) * TILE;
                for (int g = 0; g < SIZE; g++) {
                    src.getPixels(row, 0, TILE, tx, ty + g, TILE, 1);
                    for (int r = 0; r < SIZE; r++) {
                        lut.table[((b << 12) | (g << 6) | r)] = row[r] & 0x00FFFFFF;
                    }
                }
            }
            src.recycle();
            return lut;
        } catch (IOException e) {
            return null;
        }
    }

    /** Applies the LUT in horizontal strips to keep memory bounded. */
    Bitmap apply(Bitmap in, float strength) {
        int w = in.getWidth();
        int h = in.getHeight();
        float s = Math.max(0f, Math.min(1f, strength));
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

        final int rows = Math.min(128, h);
        int[] pixels = new int[w * rows];
        int[] out = new int[w * rows];

        for (int y0 = 0; y0 < h; y0 += rows) {
            int rh = Math.min(rows, h - y0);
            in.getPixels(pixels, 0, w, 0, y0, w, rh);
            int n = w * rh;
            for (int i = 0; i < n; i++) {
                int p = pixels[i];
                int r = (p >>> 16) & 0xFF;
                int g = (p >>> 8) & 0xFF;
                int b = p & 0xFF;

                int rl = (r * (SIZE - 1)) / 255;
                int gl = (g * (SIZE - 1)) / 255;
                int bl = (b * (SIZE - 1)) / 255;
                int rh2 = Math.min(rl + 1, SIZE - 1);
                int gh = Math.min(gl + 1, SIZE - 1);
                int bh = Math.min(bl + 1, SIZE - 1);
                float fr = (r * (SIZE - 1)) / 255f - rl;
                float fg = (g * (SIZE - 1)) / 255f - gl;
                float fb = (b * (SIZE - 1)) / 255f - bl;

                int c000 = table[(bl << 12) | (gl << 6) | rl];
                int c100 = table[(bl << 12) | (gl << 6) | rh2];
                int c010 = table[(bl << 12) | (gh << 6) | rl];
                int c110 = table[(bl << 12) | (gh << 6) | rh2];
                int c001 = table[(bh << 12) | (gl << 6) | rl];
                int c101 = table[(bh << 12) | (gl << 6) | rh2];
                int c011 = table[(bh << 12) | (gh << 6) | rl];
                int c111 = table[(bh << 12) | (gh << 6) | rh2];

                float or = tri(c000, c100, c010, c110, c001, c101, c011, c111, 16, fr, fg, fb);
                float og = tri(c000, c100, c010, c110, c001, c101, c011, c111, 8, fr, fg, fb);
                float ob = tri(c000, c100, c010, c110, c001, c101, c011, c111, 0, fr, fg, fb);

                out[i] = 0xFF000000 | (clamp(mix(r, or, s)) << 16)
                        | (clamp(mix(g, og, s)) << 8) | clamp(mix(b, ob, s));
            }
            result.setPixels(out, 0, w, 0, y0, w, rh);
        }
        return result;
    }

    private static float tri(int c000, int c100, int c010, int c110, int c001, int c101,
                             int c011, int c111, int shift, float fr, float fg, float fb) {
        float v000 = (c000 >>> shift) & 0xFF;
        float v100 = (c100 >>> shift) & 0xFF;
        float v010 = (c010 >>> shift) & 0xFF;
        float v110 = (c110 >>> shift) & 0xFF;
        float v001 = (c001 >>> shift) & 0xFF;
        float v101 = (c101 >>> shift) & 0xFF;
        float v011 = (c011 >>> shift) & 0xFF;
        float v111 = (c111 >>> shift) & 0xFF;
        float v00 = v000 + (v100 - v000) * fr;
        float v10 = v010 + (v110 - v010) * fr;
        float v01 = v001 + (v101 - v001) * fr;
        float v11 = v011 + (v111 - v011) * fr;
        float v0 = v00 + (v10 - v00) * fg;
        float v1 = v01 + (v11 - v01) * fg;
        return v0 + (v1 - v0) * fb;
    }

    private static float mix(float original, float value, float strength) {
        return original * (1f - strength) + value * strength;
    }

    private static int clamp(float v) {
        if (v < 0f) {
            return 0;
        }
        if (v > 255f) {
            return 255;
        }
        return (int) (v + 0.5f);
    }
}
