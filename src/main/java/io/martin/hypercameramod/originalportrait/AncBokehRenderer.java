package io.martin.hypercameramod.originalportrait;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.FaceDetector;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Wrapper for the 14 Ultra v3 AI single-bokeh engine
 * (/odm/lib64/libanc_single_bokeh.so) and its SM8650 sg_* models.
 */
public class AncBokehRenderer {
    private static final String TAG = "AncBokehRenderer";
    private static final String ASSET_DIR = "anc_bokeh";
    private static final String MODEL_VERSION = "1";

    private static native boolean nativeInit(String libPath, String modelDir);
    private static native boolean nativeProcess(Bitmap src, Bitmap dst,
                                                int x1, int y1, int x2, int y2,
                                                float fNumber);
    private static native void nativeRelease();

    private boolean ready = false;

    public synchronized boolean initialize(@NonNull Context context,
                                           @Nullable String moduleApk,
                                           @Nullable String nativeLibraryDir) {
        if (ready) return true;
        if (!BokehRenderer.ensureNativeLoaded()) {
            Log.e(TAG, "native bokeh library not loaded");
            return false;
        }
        if (moduleApk == null || nativeLibraryDir == null) {
            Log.e(TAG, "module APK or native lib dir is null");
            return false;
        }
        try {
            File modelDir = new File(context.getCacheDir(), ASSET_DIR);
            if (!extractModels(moduleApk, modelDir)) {
                Log.e(TAG, "cannot extract AI bokeh models");
                return false;
            }
            File lib = new File(nativeLibraryDir, "libanc_single_bokeh.so");
            if (!lib.isFile()) {
                Log.e(TAG, "missing " + lib);
                return false;
            }
            ready = nativeInit(lib.getAbsolutePath(), modelDir.getAbsolutePath());
            Log.i(TAG, "AI bokeh init " + (ready ? "ok" : "failed"));
            return ready;
        } catch (Throwable t) {
            Log.e(TAG, "AI bokeh init failed", t);
            return false;
        }
    }

    public synchronized boolean isReady() {
        return ready;
    }

    /**
     * Render AI bokeh into a new bitmap. Returns null on failure.
     */
    public @Nullable Bitmap process(@NonNull Bitmap src, @NonNull OriginalPortraitParams params) {
        if (!ready) return null;
        int width = src.getWidth();
        int height = src.getHeight();
        if (width <= 0 || height <= 0) return null;

        int[] face = findFace(src, params);
        Bitmap dst = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        boolean ok;
        try {
            ok = nativeProcess(src, dst, face[0], face[1], face[2], face[3], params.fNumber);
        } catch (Throwable t) {
            Log.e(TAG, "AI bokeh process failed", t);
            ok = false;
        }
        if (!ok) {
            dst.recycle();
            return null;
        }
        return dst;
    }

    public synchronized void release() {
        if (ready) {
            nativeRelease();
            ready = false;
        }
    }

    private int[] findFace(@NonNull Bitmap src, @NonNull OriginalPortraitParams params) {
        int width = src.getWidth();
        int height = src.getHeight();

        try {
            int dw = Math.min(640, width);
            int dh = Math.max(1, height * dw / width);
            Bitmap small = Bitmap.createScaledBitmap(src, dw, dh, false);
            Bitmap rgb565 = small.getConfig() == Bitmap.Config.RGB_565
                    ? small : small.copy(Bitmap.Config.RGB_565, false);
            FaceDetector detector = new FaceDetector(dw, dh, 1);
            FaceDetector.Face[] faces = new FaceDetector.Face[1];
            int count = detector.findFaces(rgb565, faces);
            if (rgb565 != small) rgb565.recycle();
            small.recycle();
            if (count > 0) {
                android.graphics.PointF mid = new android.graphics.PointF();
                faces[0].getMidPoint(mid);
                float eyes = faces[0].eyesDistance();
                float sx = (float) width / dw;
                float sy = (float) height / dh;
                int cx = (int) (mid.x * sx);
                int cy = (int) (mid.y * sy);
                int halfW = (int) (eyes * 1.6f * sx);
                int halfH = (int) (eyes * 2.1f * sy);
                int x1 = Math.max(0, cx - halfW);
                int y1 = Math.max(0, cy - halfH);
                int x2 = Math.min(width, cx + halfW);
                int y2 = Math.min(height, cy + halfH);
                if (x2 > x1 && y2 > y1) {
                    Log.i(TAG, "face rect " + x1 + "," + y1 + " " + x2 + "," + y2);
                    return new int[]{x1, y1, x2, y2};
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "face detection failed", t);
        }

        float[] f = params.focusRect;
        if (f == null || f.length != 4) f = new float[]{0.5f, 0.5f, 0.5f, 0.5f};
        int x1 = (int) ((f[0] - f[2] * 0.5f) * width);
        int y1 = (int) ((f[1] - f[3] * 0.5f) * height);
        int x2 = (int) ((f[0] + f[2] * 0.5f) * width);
        int y2 = (int) ((f[1] + f[3] * 0.5f) * height);
        x1 = Math.max(0, Math.min(width - 1, x1));
        y1 = Math.max(0, Math.min(height - 1, y1));
        x2 = Math.max(1, Math.min(width, x2));
        y2 = Math.max(1, Math.min(height, y2));
        return new int[]{x1, y1, x2, y2};
    }

    private boolean extractModels(@NonNull String apkPath, @NonNull File modelDir) throws IOException {
        File marker = new File(modelDir, ".version");
        if (marker.isFile() && MODEL_VERSION.equals(readText(marker))) {
            return true;
        }
        if (!modelDir.isDirectory() && !modelDir.mkdirs()) {
            return false;
        }
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("assets/" + ASSET_DIR + "/") || entry.isDirectory()) {
                    continue;
                }
                String base = name.substring(("assets/" + ASSET_DIR + "/").length());
                File out = new File(modelDir, base);
                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
            }
        }
        try (FileOutputStream fos = new FileOutputStream(marker)) {
            fos.write(MODEL_VERSION.getBytes());
        }
        return true;
    }

    private static String readText(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] b = new byte[16];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
