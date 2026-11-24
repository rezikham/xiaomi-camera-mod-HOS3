package io.martin.hypercameramod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.Log;

import com.codex.leicaapp.B43Tone;
import com.codex.leicaapp.LegendaryPostProcessor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * In-process implementation of the genuine M3/M9 capture stages.
 *
 * <p>The module's bound service is invisible to the camera on Android 11+ package-visibility
 * rules ({@code bindService} resolves to "not found"), so the same native pipeline also runs
 * in the camera save path. This matches the 17 Ultra app, which processes on its save worker
 * in the camera process. The Monopan stage still spawns its own helper process, so the least
 * safe native work stays out of process.
 */
final class EssentialProcessor {

    private static final String TAG = "HCM-Essential";
    private static final Object LOCK = new Object();
    private static volatile String loadedDir;
    private static volatile boolean nativeReady;
    private static volatile boolean nativeFailed;
    private static volatile String nativeError;
    private static volatile boolean wrapped;

    private EssentialProcessor() {
    }

    static boolean processFile(File file, int legend, String moduleApk,
                               String nativeLibraryDir, Context context) {
        if (file == null || moduleApk == null || nativeLibraryDir == null || context == null) {
            return false;
        }
        byte[] jpeg;
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), 64L << 20));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            jpeg = out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "cannot read " + file, t);
            return false;
        }
        byte[] result = process(jpeg, legend, moduleApk, nativeLibraryDir, context);
        if (result == null) {
            return false;
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".hcm-essential.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(result);
        } catch (Throwable t) {
            Log.w(TAG, "cannot write " + tmp, t);
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(file)) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(result);
            } catch (Throwable t) {
                Log.w(TAG, "cannot replace " + file, t);
                tmp.delete();
                return false;
            }
            tmp.delete();
        }
        return true;
    }

    private static byte[] process(byte[] jpeg, int legend, String moduleApk,
                                  String nativeLibraryDir, Context context) {
        if (!loadNative(nativeLibraryDir)) {
            Log.e(TAG, "native load failed: " + nativeError);
            return null;
        }
        String asset = legend == 2 ? "leica_filter_param_m3.bin"
                : legend == 1 ? "leica_filter_param_m9_snapshot.bin" : null;
        if (asset == null) {
            return null;
        }
        String model = ensureAsset(asset, moduleApk, context);
        if (model == null) {
            return null;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = true;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (bitmap == null) {
            return null;
        }
        if (!bitmap.isMutable()) {
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            bitmap.recycle();
            bitmap = copy;
            if (bitmap == null) {
                return null;
            }
        }

        try {
            Scene scene = scene(jpeg, bitmap);
            float phase = SystemClock.uptimeMillis() % 60000;
            Log.i(TAG, "M" + (legend == 2 ? "3" : "9") + " scene lux=" + scene.lux
                    + " cct=" + scene.cct + " iso=" + scene.iso
                    + " denoise=" + scene.denoise + " backend=" + (wrapped ? "B12" : "m9base"));
            boolean ok;
            if (legend == 2) {
                ok = LegendaryPostProcessor.applyOriginalFilter(
                        bitmap, model, scene.lux, scene.cct, 1.0f, phase);
            } else {
                ok = LegendaryPostProcessor.applyWarmOriginalFilter(
                        bitmap, model, scene.lux, scene.cct, 1.0f, phase,
                        scene.denoise, -0.95f, 0.3f);
                if (ok) {
                    B43Tone.apply(bitmap, false);
                }
            }
            if (!ok) {
                Log.e(TAG, "original filter failed");
                return null;
            }

            if (legend == 2) {
                boolean grain = false;
                File runner = new File(nativeLibraryDir, "libmonopan_runner.so");
                File helper = new File(nativeLibraryDir, "libmialgo_monopan.so");
                if (runner.isFile() && helper.isFile()) {
                    long start = SystemClock.elapsedRealtime();
                    try {
                        grain = LegendaryPostProcessor.applyMonochrome(
                                bitmap, runner.getAbsolutePath(),
                                context.getCacheDir().getAbsolutePath());
                    } catch (Throwable t) {
                        Log.w(TAG, "Monopan stage threw: " + t);
                    }
                    Log.i(TAG, "Monopan grain=" + grain + " in "
                            + (SystemClock.elapsedRealtime() - start) + " ms");
                }
                if (!grain) {
                    desaturate(bitmap);
                }
            }

            ByteArrayOutputStream encoded = new ByteArrayOutputStream(jpeg.length);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, encoded)) {
                return null;
            }
            byte[] withExif = copyExif(jpeg, encoded.toByteArray());
            return ExifUtils.injectLegendMode(withExif, legend);
        } catch (Throwable t) {
            Log.e(TAG, "pipeline failed", t);
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    private static boolean loadNative(String dir) {
        synchronized (LOCK) {
            if (nativeReady && dir.equals(loadedDir)) {
                return true;
            }
            if (nativeFailed && dir.equals(loadedDir)) {
                return false;
            }
            loadedDir = dir;
            nativeFailed = false;
            wrapped = false;
            try {
                System.load(dir + "/libc++_shared.so");
            } catch (Throwable t) {
                Log.w(TAG, "c++_shared preload failed: " + t);
            }
            try {
                System.load(dir + "/libm9base.so");
            } catch (Throwable t) {
                Log.w(TAG, "cannot preload libm9base: " + t);
            }
            try {
                System.load(dir + "/libleicamodelembed.so");
                wrapped = true;
                nativeReady = true;
                Log.i(TAG, "libleicamodelembed loaded (B12 wrapper)");
            } catch (Throwable wrappedError) {
                Log.w(TAG, "B12 wrapper unavailable, using libm9base: " + wrappedError);
                try {
                    System.load(dir + "/libm9base.so");
                    nativeReady = true;
                    Log.i(TAG, "libm9base loaded (no B12 wrapper)");
                } catch (Throwable t) {
                    nativeFailed = true;
                    nativeError = t.toString();
                    Log.e(TAG, "cannot load libm9base", t);
                }
            }
            if (nativeReady) {
                try {
                    B43Tone.load(dir);
                } catch (Throwable t) {
                    Log.w(TAG, "b43tone unavailable: " + t);
                }
            }
            return nativeReady;
        }
    }

    private static String ensureAsset(String name, String moduleApk, Context context) {
        try {
            File dir = new File(context.getCacheDir(), "essential");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            File target = new File(dir, name);
            if (target.isFile() && target.length() > 0) {
                return target.getAbsolutePath();
            }
            File tmp = new File(dir, name + ".tmp");
            try (ZipFile zip = new ZipFile(moduleApk)) {
                ZipEntry entry = zip.getEntry("assets/essential/" + name);
                if (entry == null) {
                    Log.e(TAG, "missing APK asset assets/essential/" + name);
                    return null;
                }
                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                return null;
            }
            return target.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "cannot extract " + name, t);
            return null;
        }
    }

    private static final class Scene {
        final float lux;
        final float cct;
        final float iso;
        final float denoise;

        Scene(float lux, float cct, float iso, float denoise) {
            this.lux = lux;
            this.cct = cct;
            this.iso = iso;
            this.denoise = denoise;
        }
    }

    private static Scene scene(byte[] jpeg, Bitmap bitmap) {
        float iso = 100f;
        float exposure = 1f / 60f;
        float fNumber = 1.8f;
        try {
            android.media.ExifInterface exif =
                    new android.media.ExifInterface(new ByteArrayInputStream(jpeg));
            float value = (float) exif.getAttributeDouble("PhotographicSensitivity", Float.NaN);
            if (!Float.isFinite(value) || value <= 0) {
                value = (float) exif.getAttributeDouble("ISOSpeedRatings", 100);
            }
            if (Float.isFinite(value) && value > 0) {
                iso = value;
            }
            value = (float) exif.getAttributeDouble(
                    android.media.ExifInterface.TAG_EXPOSURE_TIME, 1.0 / 60.0);
            if (Float.isFinite(value) && value > 0 && value < 60) {
                exposure = value;
            }
            value = (float) exif.getAttributeDouble(
                    android.media.ExifInterface.TAG_F_NUMBER, 1.8);
            if (Float.isFinite(value) && value > 0.5 && value < 64) {
                fNumber = value;
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot read exposure EXIF: " + t);
        }
        double ev100 = Math.log((double) fNumber * fNumber / exposure) / Math.log(2.0)
                - Math.log((double) iso / 100.0) / Math.log(2.0);
        float lux = (float) Math.max(0.1, Math.min(10000.0, 2.5 * Math.pow(2.0, ev100)));
        float cct = estimateCct(bitmap);
        if (!Float.isFinite(cct)) {
            cct = 5500f;
        }
        float denoise = (!Float.isFinite(iso) || iso <= 0) ? 0.6f
                : smooth(400f, 2400f, iso);
        return new Scene(lux, cct, iso, denoise);
    }

    private static float smooth(float low, float high, float value) {
        float t = Math.max(0f, Math.min(1f, (value - low) / (high - low)));
        return t * t * (3f - 2f * t);
    }

    private static float estimateCct(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return Float.NaN;
        }
        int scale = Math.max(1, Math.max(width, height) / 512);
        int sw = Math.max(1, width / scale);
        int sh = Math.max(1, height / scale);
        int[] pixels = new int[sw * sh];
        Bitmap small = Bitmap.createScaledBitmap(bitmap, sw, sh, false);
        try {
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh);
        } finally {
            if (small != bitmap) {
                small.recycle();
            }
        }
        double sum = 0.0;
        int count = 0;
        for (int pixel : pixels) {
            float r = ((pixel >> 16) & 255) / 255f;
            float g = ((pixel >> 8) & 255) / 255f;
            float b = (pixel & 255) / 255f;
            float max = Math.max(r, Math.max(g, b));
            float min = Math.min(r, Math.min(g, b));
            if (min >= 0.15f && max <= 0.85f && max - min <= 0.12f) {
                float rb = r + b;
                if (Math.abs(g - 0.5f * rb) <= 0.04f) {
                    sum += (r - b) / Math.max(0.01f, rb);
                    count++;
                }
            }
        }
        if (count < Math.max(16, pixels.length / 50)) {
            return Float.NaN;
        }
        double mean = sum / count;
        double clamped = Math.max(-1.0, Math.min(1.0, mean / 0.08));
        return (float) (5150.0 - clamped * 350.0);
    }

    private static void desaturate(Bitmap bitmap) {
        Bitmap source = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        if (source == null) {
            return;
        }
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        new Canvas(bitmap).drawBitmap(source, 0f, 0f, paint);
        source.recycle();
    }

    private static byte[] copyExif(byte[] source, byte[] processed) {
        if (source.length < 4 || processed.length < 4
                || (source[0] & 0xFF) != 0xFF || (source[1] & 0xFF) != 0xD8) {
            return processed;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(processed.length + 8192);
        out.write(processed, 0, 2);
        int i = 2;
        while (true) {
            int next = i + 4;
            if (next > source.length || (source[i] & 0xFF) != 0xFF) {
                break;
            }
            int marker = source[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int length = ((source[i + 2] & 0xFF) << 8) | (source[i + 3] & 0xFF);
            if (length < 2 || i + length + 2 > source.length) {
                break;
            }
            boolean exif = marker == 0xE1 && length >= 8
                    && source[next] == 'E' && source[i + 5] == 'x' && source[i + 6] == 'i'
                    && source[i + 7] == 'f' && source[i + 8] == 0 && source[i + 9] == 0;
            if (exif) {
                out.write(source, i, length + 2);
            }
            i += length + 2;
        }
        out.write(processed, 2, processed.length - 2);
        return out.toByteArray();
    }
}
