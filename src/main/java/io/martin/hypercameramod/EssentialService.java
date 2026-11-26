package io.martin.hypercameramod;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.codex.leicaapp.B43Tone;
import com.codex.leicaapp.LegendaryPostProcessor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Private process for the genuine Leica pipeline.
 *
 * <p>The native model library is not safe to load in the camera process: the Monopan stage
 * posix_spawns a helper, and on aurora that helper has crashed. Running all native work here means
 * a SIGSEGV kills only this process; the camera keeps the untouched JPEG.
 *
 * <p>The camera process talks to this service over a Messenger. The JPEG travels through a pipe
 * (never through shared storage), so the module needs no storage permission at all.
 *
 * <p>Improvements (2026-11-17):
 * - Lazy native initialization: loads libraries on first request, not at service creation
 * - Better error handling: distinguishes decode failure, filter failure, Monopan failure, encode failure
 * - Graceful fallback: if Monopan libs missing or fail, uses app's exact saturation-0 fallback
 * - Structured error replies with codes for client-side debugging
 * - Asset extraction cached per-process
 */
public final class EssentialService extends Service {

    private static final String TAG = "HCM-Essential";
    private static final String CAMERA_PACKAGE = "com.android.camera";
    private static final String PREFS_DIR = "essential";
    private static final int MSG_PROCESS = 1;
    private static final int MAX_JPEG_BYTES = 64 * 1024 * 1024;

    private static final Object NATIVE_LOCK = new Object();
    private static volatile boolean nativeReady;
    private static volatile boolean nativeFailed;
    private static volatile String nativeError;

    private HandlerThread worker;
    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        worker = new HandlerThread("hcm-essential-worker");
        worker.start();
        handler = new Handler(worker.getLooper(), this::onMessage);
        Log.i(TAG, "service created (lazy native init)");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new Messenger(handler).getBinder();
    }

    @Override
    public void onDestroy() {
        if (worker != null) {
            worker.quitSafely();
        }
        super.onDestroy();
    }

    private boolean onMessage(Message msg) {
        if (msg.what != MSG_PROCESS) {
            return false;
        }
        Bundle data = msg.getData();
        ParcelFileDescriptor input = data == null ? null : data.getParcelable("input");
        int legend = data == null ? 0 : data.getInt("legend", 0);
        float lux = data == null ? 200f : data.getFloat("lux", 200f);
        float cct = data == null ? 5500f : data.getFloat("cct", 5500f);

        if (!callerAllowed(msg.sendingUid)) {
            Log.w(TAG, "rejected request from uid " + msg.sendingUid);
            closeQuietly(input);
            reply(msg, null, "caller_rejected");
            return true;
        }
        if (input == null || legend == 0) {
            closeQuietly(input);
            reply(msg, null, "bad_request");
            return true;
        }

        byte[] jpeg = readAll(input);
        closeQuietly(input);
        if (jpeg == null || jpeg.length < 4) {
            reply(msg, null, "read_failed");
            return true;
        }

        byte[] out;
        long start = SystemClock.elapsedRealtime();
        try {
            out = process(jpeg, legend, lux, cct);
        } catch (Throwable t) {
            Log.e(TAG, "pipeline threw", t);
            out = null;
        }
        String errorCode = out == null ? "process_failed" : "ok";
        Log.i(TAG, "legend=" + legend + " " + jpeg.length + " -> "
                + (out == null ? "none" : out.length) + " bytes in "
                + (SystemClock.elapsedRealtime() - start) + " ms [" + errorCode + "]");

        if (out == null) {
            reply(msg, null, errorCode);
            return true;
        }
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (Throwable t) {
            Log.e(TAG, "cannot create output pipe", t);
            reply(msg, null, "pipe_failed");
            return true;
        }
        // Hand the read end to the camera, then write on a separate thread so the binder reply
        // is not blocked by the 64 KiB pipe buffer.
        reply(msg, pipe[0], null);
        final byte[] payload = out;
        final ParcelFileDescriptor writeEnd = pipe[1];
        new Thread(() -> {
            try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                os.write(payload);
            } catch (Throwable t) {
                Log.w(TAG, "output write failed: " + t);
            }
        }, "hcm-essential-out").start();
        return true;
    }

    private boolean callerAllowed(int uid) {
        if (uid == Process.myUid()) {
            return true;
        }
        try {
            String[] packages = getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String p : packages) {
                    if (CAMERA_PACKAGE.equals(p)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot resolve caller: " + t);
        }
        return false;
    }

    private static void reply(Message request, ParcelFileDescriptor output, String error) {
        try {
            Message answer = Message.obtain(null, MSG_PROCESS);
            answer.arg1 = output == null ? 0 : 1;
            Bundle b = new Bundle();
            if (error != null) {
                b.putString("error", error);
            }
            if (output != null) {
                b.putParcelable("output", output);
            }
            answer.setData(b);
            request.replyTo.send(answer);
        } catch (Throwable t) {
            Log.w(TAG, "reply failed: " + t);
        } finally {
            closeQuietly(output);
        }
    }

    private static byte[] readAll(ParcelFileDescriptor pfd) {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_JPEG_BYTES) {
                    Log.w(TAG, "input too large");
                    return null;
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "input read failed: " + t);
            return null;
        }
    }

    /**
     * Runs the same stage order as the 17 Ultra capture path:
     *
     * <pre>
     * M3: applyOriginalFilter -> Monopan (or the app's saturation-0 fallback)
     * M9: applyWarmOriginalFilter (-0.95 EV, warmth 0.3) -> B43Tone
     * </pre>
     *
     * <p>The M9 style-transfer model is not available on SM8650 (QNN HTP V81 vs V75), so the
     * warm parametric grade is applied directly. The scene lux/CCT are estimated from the JPEG
     * (EXIF EV and neutral-pixel balance), because the module saves after the camera's own
     * CaptureResult has gone away.
     */
    private byte[] process(byte[] jpeg, int legend, float unusedLux, float unusedCct) {
        if (!loadNative()) {
            Log.e(TAG, "native load failed: " + nativeError);
            return null;
        }
        String asset = legend == 2
                ? "leica_filter_param_m3.bin"
                : legend == 3 ? "leica_filter_param_m10r_snapshot.bin"
                : legend == 1 ? "leica_filter_param_m9_snapshot.bin" : null;
        if (asset == null) {
            return null;
        }
        String model = ensureAsset(asset);
        if (model == null) {
            Log.e(TAG, "asset extraction failed for " + asset);
            return null;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = true;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (bitmap == null) {
            Log.w(TAG, "JPEG decode returned null");
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
            String style = legend == 2 ? "M3" : legend == 3 ? "M10R" : "M9";
            Log.i(TAG, style + " scene lux=" + scene.lux
                    + " cct=" + scene.cct + " iso=" + scene.iso
                    + " denoise=" + scene.denoise);
            boolean ok;
            try {
                if (legend == 2) {
                    // This is exactly MadridLegendary.filter / applyOriginalFilter: the .bin file
                    // is a parametric shading + LUT table, not a neural model.
                    ok = LegendaryPostProcessor.applyOriginalFilter(
                            bitmap, model, scene.lux, scene.cct, 1.0f, phase);
                } else {
                    // The 17 Ultra M9 colour stage. Density -0.95 EV and warmth 0.3 are the
                    // app's M9_DENSITY_EV / M9_NEUTRAL_WARMTH constants.
                    ok = LegendaryPostProcessor.applyWarmOriginalFilter(
                            bitmap, model, scene.lux, scene.cct, 1.0f, phase,
                            scene.denoise, -0.95f, 0.3f);
                    if (ok) {
                        // M9-only fixed tone LUT (the app's B43Tone stage).
                        B43Tone.apply(bitmap, false);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "parametric filter threw", t);
                ok = false;
            }
            if (!ok) {
                Log.e(TAG, "original filter failed");
                return null;
            }

            if (legend == 2) {
                boolean grain = false;
                File runner = new File(getApplicationInfo().nativeLibraryDir,
                        "libmonopan_runner.so");
                File helper = new File(getApplicationInfo().nativeLibraryDir,
                        "libmialgo_monopan.so");
                if (runner.isFile() && helper.isFile()) {
                    long monopanStart = SystemClock.elapsedRealtime();
                    try {
                        grain = LegendaryPostProcessor.applyMonochrome(
                                bitmap, runner.getAbsolutePath(), getCacheDir().getAbsolutePath());
                    } catch (Throwable t) {
                        Log.w(TAG, "Monopan stage threw: " + t);
                    }
                    Log.i(TAG, "Monopan grain=" + grain + " in "
                            + (SystemClock.elapsedRealtime() - monopanStart) + " ms");
                } else {
                    Log.i(TAG, "Monopan libs not bundled; using grayscale fallback");
                }
                if (!grain) {
                    // Matches the app's own fallback in MadridLegendary.process for M3.
                    desaturate(bitmap);
                }
            }

            ByteArrayOutputStream encoded = new ByteArrayOutputStream(jpeg.length);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, encoded)) {
                Log.e(TAG, "JPEG encode failed");
                return null;
            }
            byte[] withExif = copyExif(jpeg, encoded.toByteArray());
            // Inject legendmode EXIF tag (0x88B0) so Gallery shows LEICA button
            return ExifUtils.finalizeLegend(withExif, jpeg, legend);
        } finally {
            bitmap.recycle();
        }
    }

    /** Scene values the native adaptive LUT needs, estimated from the saved JPEG. */
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
                    new android.media.ExifInterface(new java.io.ByteArrayInputStream(jpeg));
            float value = (float) exif.getAttributeDouble(
                    "PhotographicSensitivity", Float.NaN);
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

        // EV at ISO 100 from the exposure triangle, then the standard incident-light constant.
        double ev100 = Math.log((double) fNumber * fNumber / exposure) / Math.log(2.0)
                - Math.log((double) iso / 100.0) / Math.log(2.0);
        float lux = (float) Math.max(0.1, Math.min(10000.0, 2.5 * Math.pow(2.0, ev100)));
        if (iso <= 0) {
            lux = 200f;
        }

        float cct = estimateCct(bitmap);
        if (!Float.isFinite(cct)) {
            cct = 5500f;
        }

        // FilmTone.denoise: invalid ISO falls back to 0.6; otherwise smoothstep(400, 2400, ISO).
        float denoise;
        if (!Float.isFinite(iso) || iso <= 0) {
            denoise = 0.6f;
        } else {
            denoise = smooth(400f, 2400f, iso);
        }
        return new Scene(lux, cct, iso, denoise);
    }

    private static float smooth(float low, float high, float value) {
        float t = Math.max(0f, Math.min(1f, (value - low) / (high - low)));
        return t * t * (3f - 2f * t);
    }

    /**
     * The app's JPEG white-balance estimator (SceneWhiteBalance.jpegTarget). It samples neutral
     * mid-tones and returns a 4800..5500 K estimate; the native LUT uses this to choose the
     * scene block when the camera CaptureResult is unavailable.
     */
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

    private static boolean loadNative() {
        synchronized (NATIVE_LOCK) {
            if (nativeReady) {
                return true;
            }
            if (nativeFailed) {
                return false;
            }
            try {
                System.loadLibrary("c++_shared");
            } catch (Throwable t) {
                Log.w(TAG, "libc++_shared preload failed: " + t);
                // Not fatal; m9base may pull it via DT_NEEDED
            }
            try {
                // The app loads the B12 wrapper first; the wrapper dlopens libm9base itself.
                // This is the 17 Ultra load order and applies the reference/reconciliation pass.
                System.loadLibrary("leicamodelembed");
                nativeReady = true;
                Log.i(TAG, "libleicamodelembed loaded (B12 wrapper)");
            } catch (Throwable wrapped) {
                Log.w(TAG, "libleicamodelembed failed, trying libm9base: " + wrapped);
                try {
                    System.loadLibrary("m9base");
                    nativeReady = true;
                    Log.i(TAG, "libm9base loaded (no B12 wrapper)");
                } catch (Throwable t) {
                    nativeFailed = true;
                    nativeError = t.toString();
                    Log.e(TAG, "cannot load libm9base", t);
                }
            }
            return nativeReady;
        }
    }

    private String ensureAsset(String name) {
        try {
            File dir = new File(getFilesDir(), PREFS_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            File target = new File(dir, name);
            if (target.isFile() && target.length() > 0) {
                return target.getAbsolutePath();
            }
            File tmp = new File(dir, name + ".tmp");
            try (InputStream in = getAssets().open("essential/" + name);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
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

    /** Copies the source EXIF APP1 segment, like the app's JpegMetadata.copyExif. */
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

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
