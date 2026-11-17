package io.martin.hypercameramod.originalportrait;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Java wrapper for the Original Portrait Bokeh Renderer.
 * 
 * Uses OpenGL ES 3.1 Compute Shaders via JNI to render bokeh effects
 * matching the libAncHumanPreviewBokeh.so behavior on 17 Ultra / 18 Pro Max.
 */
public class BokehRenderer {
    private static final String TAG = "OriginalPortraitBokeh";
    
    // Native library name
    static {
        System.loadLibrary("original_portrait_bokeh");
    }
    
    // Quality presets
    public enum QualityPreset {
        FAST,       // Separable 2-pass, half resolution (~150ms)
        BALANCED,   // Full disk, adaptive sampling (~350ms)
        QUALITY     // Full disk, temporal stability (~600ms)
    }
    
    // Native peer
    private long nativePtr = 0;
    private boolean initialized = false;
    private int width = 0;
    private int height = 0;
    private QualityPreset quality = QualityPreset.BALANCED;
    
    // Native methods
    private static native long nativeCreate();
    private static native void nativeDestroy(long ptr);
    private static native boolean nativeInitialize(long ptr, int width, int height, int qualityOrdinal);
    private static native boolean nativeRender(long ptr,
                                               ByteBuffer srcRGBA,
                                               ByteBuffer depthMap,
                                               ByteBuffer confidenceMap,
                                               OriginalPortraitParams params,
                                               ByteBuffer dstRGBA);
    private static native boolean nativeRenderBitmaps(long ptr,
                                                      Bitmap srcBitmap,
                                                      Bitmap depthBitmap,
                                                      Bitmap confidenceBitmap,
                                                      OriginalPortraitParams params,
                                                      Bitmap dstBitmap);
    
    /**
     * Create a new BokehRenderer instance.
     * Must call initialize() before render().
     */
    public BokehRenderer() {
        nativePtr = nativeCreate();
        if (nativePtr == 0) {
            Log.e(TAG, "Failed to create native renderer");
        }
    }
    
    /**
     * Initialize the renderer with output dimensions.
     * 
     * @param width  Output image width
     * @param height Output image height
     * @param quality Quality preset
     * @return true on success
     */
    public boolean initialize(int width, int height, @NonNull QualityPreset quality) {
        if (nativePtr == 0) return false;
        if (width <= 0 || height <= 0) return false;
        
        this.width = width;
        this.height = height;
        this.quality = quality;
        
        initialized = nativeInitialize(nativePtr, width, height, quality.ordinal());
        if (!initialized) {
            Log.e(TAG, "Failed to initialize renderer: " + width + "x" + height);
        } else {
            Log.d(TAG, "Renderer initialized: " + width + "x" + height + " quality=" + quality);
        }
        return initialized;
    }
    
    /**
     * Initialize with default BALANCED quality.
     */
    public boolean initialize(int width, int height) {
        return initialize(width, height, QualityPreset.BALANCED);
    }
    
    /**
     * Render bokeh effect from raw buffers.
     * 
     * @param srcRGBA       Source image (RGBA_8888, width x height)
     * @param depthMap      Normalized depth [0,1] (float, width x height)
     * @param confidenceMap Optional confidence [0,255] (byte, width x height)
     * @param params        Original portrait parameters
     * @param dstRGBA       Output buffer (RGBA_8888, width x height)
     * @return true on success
     */
    public boolean render(@NonNull ByteBuffer srcRGBA,
                          @NonNull ByteBuffer depthMap,
                          @Nullable ByteBuffer confidenceMap,
                          @NonNull OriginalPortraitParams params,
                          @NonNull ByteBuffer dstRGBA) {
        if (!initialized || nativePtr == 0) return false;
        
        // Validate buffers
        int expectedSize = width * height * 4;
        if (srcRGBA.remaining() < expectedSize ||
            dstRGBA.remaining() < expectedSize) {
            Log.e(TAG, "Buffer size mismatch: expected " + expectedSize);
            return false;
        }
        
        int depthExpectedSize = width * height * 4; // float = 4 bytes
        if (depthMap.remaining() < depthExpectedSize) {
            Log.e(TAG, "Depth buffer size mismatch: expected " + depthExpectedSize);
            return false;
        }
        
        if (confidenceMap != null && confidenceMap.remaining() < width * height) {
            Log.e(TAG, "Confidence buffer size mismatch");
            return false;
        }
        
        // Ensure byte order
        srcRGBA.order(ByteOrder.nativeOrder());
        depthMap.order(ByteOrder.nativeOrder());
        dstRGBA.order(ByteOrder.nativeOrder());
        if (confidenceMap != null) confidenceMap.order(ByteOrder.nativeOrder());
        
        return nativeRender(nativePtr, srcRGBA, depthMap, confidenceMap, params, dstRGBA);
    }
    
    /**
     * Render bokeh effect from Android Bitmaps (convenience method).
     * 
     * @param srcBitmap       Source bitmap (ARGB_8888)
     * @param depthBitmap     Depth bitmap (single channel, normalized float or 16-bit)
     * @param confidenceBitmap Optional confidence bitmap (8-bit grayscale)
     * @param params          Original portrait parameters
     * @param dstBitmap       Output bitmap (ARGB_8888, mutable)
     * @return true on success
     */
    public boolean render(@NonNull Bitmap srcBitmap,
                          @NonNull Bitmap depthBitmap,
                          @Nullable Bitmap confidenceBitmap,
                          @NonNull OriginalPortraitParams params,
                          @NonNull Bitmap dstBitmap) {
        if (!initialized || nativePtr == 0) return false;
        
        // Validate bitmaps
        if (srcBitmap.getWidth() != width || srcBitmap.getHeight() != height) {
            Log.e(TAG, "Source bitmap size mismatch");
            return false;
        }
        if (depthBitmap.getWidth() != width || depthBitmap.getHeight() != height) {
            Log.e(TAG, "Depth bitmap size mismatch");
            return false;
        }
        if (dstBitmap.getWidth() != width || dstBitmap.getHeight() != height) {
            Log.e(TAG, "Destination bitmap size mismatch");
            return false;
        }
        if (!dstBitmap.isMutable()) {
            Log.e(TAG, "Destination bitmap must be mutable");
            return false;
        }
        
        return nativeRenderBitmaps(nativePtr, srcBitmap, depthBitmap, 
                                   confidenceBitmap, params, dstBitmap);
    }
    
    /**
     * Render with automatic depth map conversion from 16-bit to float.
     * 
     * @param srcBitmap       Source bitmap (ARGB_8888)
     * @param depthBuffer     Raw depth buffer (16-bit unsigned, width x height)
     * @param confidenceBuffer Optional confidence buffer (8-bit, width x height)
     * @param params          Original portrait parameters
     * @param dstBitmap       Output bitmap (ARGB_8888, mutable)
     * @return true on success
     */
    public boolean renderWithRawDepth(@NonNull Bitmap srcBitmap,
                                       @NonNull byte[] depthBuffer,
                                       @Nullable byte[] confidenceBuffer,
                                       @NonNull OriginalPortraitParams params,
                                       @NonNull Bitmap dstBitmap) {
        if (!initialized || nativePtr == 0) return false;
        
        if (depthBuffer.length < width * height * 2) {
            Log.e(TAG, "Depth buffer too small");
            return false;
        }
        
        // Convert 16-bit depth to float [0,1]
        float[] depthFloat = new float[width * height];
        for (int i = 0; i < width * height; i++) {
            int raw = (depthBuffer[i * 2] & 0xFF) | ((depthBuffer[i * 2 + 1] & 0xFF) << 8);
            depthFloat[i] = raw / 65535.0f;
        }
        
        // Convert confidence if present
        byte[] confBytes = null;
        if (confidenceBuffer != null && confidenceBuffer.length >= width * height) {
            confBytes = confidenceBuffer;
        }
        
        // Use direct ByteBuffers
        ByteBuffer srcBuf = BitmapUtils.bitmapToByteBuffer(srcBitmap);
        ByteBuffer depthBuf = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder());
        depthBuf.asFloatBuffer().put(depthFloat);
        depthBuf.rewind();
        
        ByteBuffer confBuf = null;
        if (confBytes != null) {
            confBuf = ByteBuffer.wrap(confBytes);
        }
        
        ByteBuffer dstBuf = BitmapUtils.bitmapToByteBuffer(dstBitmap);
        
        return render(srcBuf, depthBuf, confBuf, params, dstBuf);
    }
    
    // Getters
    public boolean isInitialized() { return initialized; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public @NonNull QualityPreset getQuality() { return quality; }
    
    // Cleanup
    public void release() {
        if (nativePtr != 0) {
            nativeDestroy(nativePtr);
            nativePtr = 0;
            initialized = false;
            Log.d(TAG, "Renderer released");
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }
}