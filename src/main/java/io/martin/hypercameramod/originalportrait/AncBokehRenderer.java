package io.martin.hypercameramod.originalportrait;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final float MIN_FACE_CONFIDENCE = 0.40f;
    private static final int MAX_DETECT_FACES = 4;

    private static final Pattern XMP_FOCUS_PATTERN =
            Pattern.compile("focuspoint=(?:&quot;|\")(\\d+),(\\d+)(?:&quot;|\")");
    private static final Pattern XMP_DEPTHSIZE_PATTERN =
            Pattern.compile("depthsize=(?:&quot;|\")(\\d+),(\\d+)(?:&quot;|\")");
    private static final Pattern XMP_DEPTH_ORI_PATTERN =
            Pattern.compile("depthOrientation=(?:&quot;|\")(\\d+)(?:&quot;|\")");

    private static native boolean nativeInit(String libPath, String modelDir);
    private static native boolean nativeProcess(Bitmap src, Bitmap dst,
                                                int x1, int y1, int x2, int y2,
                                                float fNumber, int orientation);
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
        return process(src, params, 0);
    }

    /**
     * Render AI bokeh into a new bitmap, honoring EXIF orientation degrees (0/90/180/270).
     * Returns null on failure.
     */
    public @Nullable Bitmap process(@NonNull Bitmap src,
                                    @NonNull OriginalPortraitParams params,
                                    int exifOrientationDegrees) {
        if (!ready) return null;
        int width = src.getWidth();
        int height = src.getHeight();
        if (width <= 0 || height <= 0) return null;

        int ori = normalizeOrientation(exifOrientationDegrees);
        int[] face = findFace(src, params, ori);
        Bitmap dst = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        boolean ok;
        try {
            ok = nativeProcess(src, dst, face[0], face[1], face[2], face[3], params.fNumber, ori);
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

    /**
     * Extract Xiaomi portrait XMP focuspoint="x,y" (plus depthsize and depthOrientation)
     * from a pre-processed JPEG header and map it to normalized [0,1] coordinates
     * matching the decoded bitmap buffer.
     */
    public static @Nullable float[] extractXmpFocusPoint(@NonNull File jpegFile,
                                                         int imgWidth, int imgHeight) {
        if (imgWidth <= 0 || imgHeight <= 0) return null;
        try (InputStream in = new FileInputStream(jpegFile)) {
            byte[] buf = new byte[128 * 1024];
            int total = 0;
            int n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            if (total <= 0) return null;
            String header = new String(buf, 0, total, StandardCharsets.ISO_8859_1);
            Matcher fm = XMP_FOCUS_PATTERN.matcher(header);
            if (!fm.find()) return null;

            float fx = Float.parseFloat(fm.group(1));
            float fy = Float.parseFloat(fm.group(2));
            float dw = imgWidth;
            float dh = imgHeight;
            Matcher dm = XMP_DEPTHSIZE_PATTERN.matcher(header);
            if (dm.find()) {
                dw = Float.parseFloat(dm.group(1));
                dh = Float.parseFloat(dm.group(2));
            }
            int depthOri = 0;
            Matcher om = XMP_DEPTH_ORI_PATTERN.matcher(header);
            if (om.find()) {
                depthOri = normalizeOrientation(Integer.parseInt(om.group(1)));
            }
            if (dw <= 1f || dh <= 1f) return null;

            float nx;
            float ny;
            // Xiaomi depthsize is typically landscape (e.g. 4096x3072) while portrait JPEG
            // pixels are already rotated upright (3072x4096) with depthOrientation=90/270.
            boolean swapped = (dw > dh) != (imgWidth > imgHeight);
            if (swapped && depthOri == 90) {
                nx = (dh - 1f - fy) / dh;
                ny = fx / dw;
            } else if (swapped && depthOri == 270) {
                nx = fy / dh;
                ny = (dw - 1f - fx) / dw;
            } else if (!swapped && depthOri == 180) {
                nx = (dw - 1f - fx) / dw;
                ny = (dh - 1f - fy) / dh;
            } else {
                nx = fx / dw;
                ny = fy / dh;
            }

            if (nx < 0.01f || nx > 0.99f || ny < 0.01f || ny > 0.99f) {
                return null;
            }
            Log.i(TAG, "XMP focuspoint raw=(" + (int) fx + "," + (int) fy
                    + ") depth=" + (int) dw + "x" + (int) dh + " ori=" + depthOri
                    + " -> norm=(" + String.format(java.util.Locale.US, "%.3f,%.3f", nx, ny) + ")");
            return new float[]{nx, ny};
        } catch (Throwable t) {
            Log.w(TAG, "cannot parse XMP focuspoint: " + t);
            return null;
        }
    }

    private int[] findFace(@NonNull Bitmap src, @NonNull OriginalPortraitParams params, int ori) {
        int width = src.getWidth();
        int height = src.getHeight();
        float[] hint = (params.focusRect != null && params.focusRect.length == 4)
                ? params.focusRect : null;

        // 1. Try FaceDetector on an upright downscaled thumbnail. Keep a genuine face-sized
        //    box because libanc_single_bokeh uses it for both the head ROI and focus midpoint.
        try {
            int maxSide = Math.max(width, height);
            float downScale = Math.min(1.0f, 640.0f / maxSide);
            int sw = Math.max(2, Math.round(width * downScale) & ~1);
            int sh = Math.max(2, Math.round(height * downScale) & ~1);
            Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, false);
            Bitmap oriented = small;
            if (ori != 0) {
                Matrix m = new Matrix();
                m.postRotate(ori);
                oriented = Bitmap.createBitmap(small, 0, 0, sw, sh, m, false);
                small.recycle();
            }
            int dw = oriented.getWidth();
            int dh = oriented.getHeight();
            Bitmap rgb565 = oriented.getConfig() == Bitmap.Config.RGB_565
                    ? oriented : oriented.copy(Bitmap.Config.RGB_565, false);
            FaceDetector detector = new FaceDetector(dw, dh, MAX_DETECT_FACES);
            FaceDetector.Face[] faces = new FaceDetector.Face[MAX_DETECT_FACES];
            int count = detector.findFaces(rgb565, faces);
            if (rgb565 != oriented) rgb565.recycle();
            oriented.recycle();

            if (count > 0) {
                FaceDetector.Face best = null;
                float bestScore = -Float.MAX_VALUE;
                PointF tmp = new PointF();
                float[] hintUp = hint != null ? rotateNorm(hint[0], hint[1], ori) : null;
                for (int i = 0; i < count; i++) {
                    FaceDetector.Face f = faces[i];
                    if (f == null) continue;
                    float conf = f.confidence();
                    float eyes = f.eyesDistance();
                    if (conf < MIN_FACE_CONFIDENCE || eyes < 4.0f) continue;
                    f.getMidPoint(tmp);
                    float score = conf * 2.0f + (eyes / Math.max(dw, dh)) * 2.0f;
                    if (hintUp != null) {
                        float dx = (tmp.x / dw) - hintUp[0];
                        float dy = (tmp.y / dh) - hintUp[1];
                        float dist = (float) Math.hypot(dx, dy);
                        score -= dist * 3.0f;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        best = f;
                    }
                }
                if (best != null) {
                    PointF mid = new PointF();
                    best.getMidPoint(mid);
                    float eyes = best.eyesDistance();
                    float cxUp = mid.x / dw;
                    float cyUp = (mid.y + eyes * 0.15f) / dh;
                    float hwUp = (eyes * 1.6f) / dw;
                    float hhUp = (eyes * 2.0f) / dh;
                    int[] rect = uprightBoxToBufferRect(cxUp, cyUp, hwUp, hhUp, ori, width, height);
                    if (rect != null) {
                        Log.i(TAG, "FaceDetector hit conf="
                                + String.format(java.util.Locale.US, "%.2f", best.confidence())
                                + " rect=" + rect[0] + "," + rect[1] + " " + rect[2] + "," + rect[3]);
                        return rect;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "face detection failed", t);
        }

        // 2. Fallback to the camera's real focus point (XMP focuspoint or live AF region).
        if (hint != null) {
            int[] rect = makeFocusRect(hint[0], hint[1], width, height);
            Log.i(TAG, "using camera focus hint norm=("
                    + String.format(java.util.Locale.US, "%.3f,%.3f", hint[0], hint[1])
                    + ") rect=" + rect[0] + "," + rect[1] + " " + rect[2] + "," + rect[3]);
            return rect;
        }

        // 3. Final fallback: upper-center portrait head position (in upright space).
        float[] defBuf = rotateNorm(0.50f, 0.38f, (360 - ori) % 360);
        int[] rect = makeFocusRect(defBuf[0], defBuf[1], width, height);
        Log.i(TAG, "using portrait default focus rect="
                + rect[0] + "," + rect[1] + " " + rect[2] + "," + rect[3]);
        return rect;
    }

    private static int[] makeFocusRect(float nx, float ny, int width, int height) {
        float cx = Math.max(0.05f, Math.min(0.95f, nx)) * width;
        float cy = Math.max(0.05f, Math.min(0.95f, ny)) * height;
        float minSide = Math.min(width, height);
        float halfW = minSide * 0.08f;
        float halfH = minSide * 0.10f;
        int x1 = Math.max(0, Math.min(width - 2, Math.round(cx - halfW)));
        int y1 = Math.max(0, Math.min(height - 2, Math.round(cy - halfH)));
        int x2 = Math.max(x1 + 1, Math.min(width, Math.round(cx + halfW)));
        int y2 = Math.max(y1 + 1, Math.min(height, Math.round(cy + halfH)));
        return new int[]{x1, y1, x2, y2};
    }

    private static int[] uprightBoxToBufferRect(float cxUp, float cyUp, float hwUp, float hhUp,
                                                int ori, int width, int height) {
        float x1u = Math.max(0f, cxUp - hwUp);
        float y1u = Math.max(0f, cyUp - hhUp);
        float x2u = Math.min(1f, cxUp + hwUp);
        float y2u = Math.min(1f, cyUp + hhUp);
        int inv = (360 - ori) % 360;
        float[] p1 = rotateNorm(x1u, y1u, inv);
        float[] p2 = rotateNorm(x2u, y2u, inv);
        int x1 = Math.max(0, Math.min(width - 2, Math.round(Math.min(p1[0], p2[0]) * width)));
        int y1 = Math.max(0, Math.min(height - 2, Math.round(Math.min(p1[1], p2[1]) * height)));
        int x2 = Math.max(x1 + 1, Math.min(width, Math.round(Math.max(p1[0], p2[0]) * width)));
        int y2 = Math.max(y1 + 1, Math.min(height, Math.round(Math.max(p1[1], p2[1]) * height)));
        return (x2 > x1 && y2 > y1) ? new int[]{x1, y1, x2, y2} : null;
    }

    private static float[] rotateNorm(float x, float y, int cwDegrees) {
        switch (normalizeOrientation(cwDegrees)) {
            case 90:
                return new float[]{1f - y, x};
            case 180:
                return new float[]{1f - x, 1f - y};
            case 270:
                return new float[]{y, 1f - x};
            default:
                return new float[]{x, y};
        }
    }

    private static int normalizeOrientation(int deg) {
        int r = ((deg % 360) + 360) % 360;
        return (r == 90 || r == 180 || r == 270) ? r : 0;
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
