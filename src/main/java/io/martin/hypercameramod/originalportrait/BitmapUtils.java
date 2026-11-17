package io.martin.hypercameramod.originalportrait;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.media.Image;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility class for bitmap operations in Original Portrait pipeline.
 */
public class BitmapUtils {
    private static final String TAG = "OriginalPortraitBitmap";
    
    // RenderScript for fast conversions (if available)
    private static RenderScript renderScript = null;
    
    /**
     * Initialize RenderScript context (call once at app startup).
     */
    public static void initRenderScript(@NonNull android.content.Context context) {
        if (renderScript == null) {
            renderScript = RenderScript.create(context);
        }
    }
    
    /**
     * Convert Bitmap to direct ByteBuffer (RGBA_8888).
     */
    public static @NonNull ByteBuffer bitmapToByteBuffer(@NonNull Bitmap bitmap) {
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int size = width * height * 4;
        
        ByteBuffer buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        bitmap.copyPixelsToBuffer(buffer);
        buffer.rewind();
        return buffer;
    }
    
    /**
     * Convert direct ByteBuffer to Bitmap (RGBA_8888).
     */
    public static @NonNull Bitmap byteBufferToBitmap(@NonNull ByteBuffer buffer, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        buffer.rewind();
        bitmap.copyPixelsFromBuffer(buffer);
        return bitmap;
    }
    
    /**
     * Create mutable copy of bitmap.
     */
    public static @NonNull Bitmap createMutableCopy(@NonNull Bitmap src) {
        if (src.isMutable()) return src;
        return src.copy(Bitmap.Config.ARGB_8888, true);
    }
    
    /**
     * Convert 16-bit depth buffer to normalized float array [0,1].
     */
    public static float[] depth16ToFloat(@NonNull byte[] depth16, int width, int height) {
        float[] depthFloat = new float[width * height];
        for (int i = 0; i < width * height; i++) {
            int raw = (depth16[i * 2] & 0xFF) | ((depth16[i * 2 + 1] & 0xFF) << 8);
            depthFloat[i] = raw / 65535.0f;
        }
        return depthFloat;
    }
    
    /**
     * Convert normalized float depth to 16-bit buffer.
     */
    public static byte[] depthFloatTo16(@NonNull float[] depthFloat, int width, int height) {
        byte[] depth16 = new byte[width * height * 2];
        for (int i = 0; i < width * height; i++) {
            int raw = Math.round(Math.min(1.0f, Math.max(0.0f, depthFloat[i])) * 65535.0f);
            depth16[i * 2] = (byte) (raw & 0xFF);
            depth16[i * 2 + 1] = (byte) ((raw >> 8) & 0xFF);
        }
        return depth16;
    }
    
    /**
     * Resize depth map using bilinear interpolation.
     */
    public static float[] resizeDepthMap(@NonNull float[] src, int srcW, int srcH, int dstW, int dstH) {
        float[] dst = new float[dstW * dstH];
        float scaleX = (float) srcW / dstW;
        float scaleY = (float) srcH / dstH;
        
        for (int y = 0; y < dstH; y++) {
            float srcY = y * scaleY;
            int y0 = (int) srcY;
            int y1 = Math.min(y0 + 1, srcH - 1);
            float wy = srcY - y0;
            
            for (int x = 0; x < dstW; x++) {
                float srcX = x * scaleX;
                int x0 = (int) srcX;
                int x1 = Math.min(x0 + 1, srcW - 1);
                float wx = srcX - x0;
                
                // Bilinear interpolation
                float v00 = src[y0 * srcW + x0];
                float v01 = src[y0 * srcW + x1];
                float v10 = src[y1 * srcW + x0];
                float v11 = src[y1 * srcW + x1];
                
                float v0 = v00 + (v01 - v00) * wx;
                float v1 = v10 + (v11 - v10) * wx;
                dst[y * dstW + x] = v0 + (v1 - v0) * wy;
            }
        }
        return dst;
    }
    
    /**
     * Create confidence map from depth map (simple gradient-based).
     */
    public static byte[] createConfidenceMap(@NonNull float[] depth, int width, int height) {
        byte[] confidence = new byte[width * height];
        
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = y * width + x;
                float center = depth[idx];
                
                if (center <= 0.0f) {
                    confidence[idx] = 0;
                    continue;
                }
                
                // Compute depth gradient magnitude
                float dx = depth[idx + 1] - depth[idx - 1];
                float dy = depth[(y + 1) * width + x] - depth[(y - 1) * width + x];
                float gradient = (float) Math.sqrt(dx * dx + dy * dy);
                
                // Low gradient = high confidence (flat regions)
                // High gradient = low confidence (edges)
                float conf = 1.0f - Math.min(1.0f, gradient * 10.0f);
                confidence[idx] = (byte) Math.round(conf * 255.0f);
            }
        }
        
        // Border pixels
        for (int x = 0; x < width; x++) {
            confidence[x] = (byte) 128;
            confidence[(height - 1) * width + x] = (byte) 128;
        }
        for (int y = 0; y < height; y++) {
            confidence[y * width] = (byte) 128;
            confidence[y * width + width - 1] = (byte) 128;
        }
        
        return confidence;
    }
    
    /**
     * Save bitmap to JPEG file.
     */
    public static boolean saveJpeg(@NonNull Bitmap bitmap, @NonNull File file, int quality) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            return bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos);
        } catch (IOException e) {
            Log.e(TAG, "Failed to save JPEG", e);
            return false;
        }
    }
    
    /**
     * Load JPEG file to bitmap.
     */
    public static @Nullable Bitmap loadJpeg(@NonNull File file) {
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }
    
    /**
     * Extract depth map from CaptureResult (Android 10+).
     */
    public static @Nullable float[] extractDepthFromCaptureResult(
            @NonNull android.hardware.camera2.CaptureResult result,
            int width, int height) {
        
        // Try JPEG_DEPTH_MAP first (API 29+)
        try {
            java.lang.reflect.Field field = android.hardware.camera2.CaptureResult.class.getDeclaredField("JPEG_DEPTH_MAP");
            field.setAccessible(true);
            android.hardware.camera2.CaptureResult.Key<float[]> depthKey = 
                    (android.hardware.camera2.CaptureResult.Key<float[]>) field.get(null);
            if (depthKey != null) {
                float[] depth = result.get(depthKey);
                if (depth != null && depth.length == width * height) {
                    return depth;
                }
            }
        } catch (Throwable ignored) {
            // Field not available on this API level
        }
        
        // Try vendor metadata
        // Note: Requires vendor-specific keys
        return null;
    }
    
    /**
     * Convert YUV_420_888 Image to Bitmap (for preview frames).
     */
    public static @Nullable Bitmap yuvToBitmap(@NonNull Image image) {
        if (image.getFormat() != android.graphics.ImageFormat.YUV_420_888) {
            return null;
        }
        
        Image.Plane[] planes = image.getPlanes();
        if (planes.length != 3) return null;
        
        int width = image.getWidth();
        int height = image.getHeight();
        
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        
        int yStride = planes[0].getRowStride();
        int uStride = planes[1].getRowStride();
        int vStride = planes[2].getRowStride();
        
        // Use RenderScript for fast conversion if available
        if (renderScript != null) {
            try {
                Allocation yAlloc = Allocation.createSized(renderScript, Element.U8(renderScript), width * height);
                Allocation uAlloc = Allocation.createSized(renderScript, Element.U8(renderScript), width * height / 4);
                Allocation vAlloc = Allocation.createSized(renderScript, Element.U8(renderScript), width * height / 4);
                
                yAlloc.copyFrom(yBuffer);
                uAlloc.copyFrom(uBuffer);
                vAlloc.copyFrom(vBuffer);
                
                Type.Builder yuvType = new Type.Builder(renderScript, Element.YUV(renderScript))
                        .setX(width).setY(height);
                Allocation rgbaAlloc = Allocation.createTyped(renderScript, 
                        new Type.Builder(renderScript, Element.RGBA_8888(renderScript))
                                .setX(width).setY(height).create(), Allocation.USAGE_SCRIPT);
                
                // Simple YUV to RGBA kernel would go here
                // For now, fallback to CPU
            } catch (Exception e) {
                Log.w(TAG, "RenderScript conversion failed", e);
            }
        }
        
        // CPU fallback (slow, for testing only)
        // In production, use libyuv or RenderScript
        return null;
    }
    
    /**
     * Compute image quality metrics (for validation).
     */
    public static float computeSharpness(@NonNull Bitmap bitmap) {
        // Laplacian variance as sharpness metric
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        
        double sum = 0;
        double sumSq = 0;
        int count = 0;
        
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = y * width + x;
                int center = pixels[idx] & 0xFF; // Use red channel
                int laplacian = 4 * center 
                    - (pixels[idx - 1] & 0xFF) 
                    - (pixels[idx + 1] & 0xFF)
                    - (pixels[idx - width] & 0xFF)
                    - (pixels[idx + width] & 0xFF);
                
                sum += laplacian;
                sumSq += laplacian * laplacian;
                count++;
            }
        }
        
        double mean = sum / count;
        double variance = (sumSq / count) - (mean * mean);
        return (float) variance;
    }
}