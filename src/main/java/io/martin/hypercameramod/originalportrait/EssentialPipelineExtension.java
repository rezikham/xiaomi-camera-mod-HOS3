package io.martin.hypercameramod.originalportrait;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

// EssentialPipeline and EssentialService are in ModuleMain - use reflection if needed

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EssentialPipeline extension for Original Portrait post-capture processing.
 * 
 * This integrates with the existing EssentialPipeline/EssentialService framework
 * to apply Original Portrait look after Master Portrait capture.
 */
public class EssentialPipelineExtension {
    private static final String TAG = "EssentialPipelineExt";
    
    // Vendor capture request/result keys (from RE)
    public static final String KEY_FILTER_ID = "com.xiaomi.sessionparams.filterId";
    public static final String KEY_STYLIZATION_TYPE = "com.xiaomi.sessionparams.stylizationType";
    public static final String KEY_LEGEND_MODE = "com.xiaomi.sessionparams.legendMode";
    public static final String KEY_BOKEH_MODE = "com.xiaomi.sessionparams.BokehMode";
    public static final String KEY_F_NUMBER = "xiaomi.camera.f_number";
    public static final String KEY_FOCAL_LENGTH = "xiaomi.camera.focal_length";
    public static final String KEY_FOCUS_RECT = "xiaomi.camera.focus_rect";
    public static final String KEY_BOKEH_STYLE = "xiaomi.camera.bokeh_style";
    public static final String KEY_ZOOM_RATE = "xiaomi.camera.zoom_rate";
    public static final String KEY_CAMERA_ROLE = "xiaomi.camera.camera_role";
    public static final String KEY_AUTO_F_NUMBER = "xiaomi.camera.auto_f_number";
    public static final String KEY_STATIC_SCENE = "xiaomi.camera.static_scene";
    public static final String KEY_MOTION_SPEED = "xiaomi.camera.motion_speed";
    public static final String KEY_BOKEH_BYPASS = "xiaomi.camera.bokeh_bypass";
    
    // Original Portrait constants
    public static final int FILTER_ID_ORIGINAL = 2;
    public static final int STYLIZATION_TYPE_PORTRAIT = 171;
    
    // Singleton
    private static volatile EssentialPipelineExtension instance;
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    // Configuration
    private boolean enabled = false;
    private BokehRenderer.QualityPreset qualityPreset = BokehRenderer.QualityPreset.BALANCED;
    private int intensity = 75; // 0-100
    
    // State
    private BokehRenderer bokehRenderer;
    private ColorProfile colorProfile;
    
    private EssentialPipelineExtension(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public static EssentialPipelineExtension getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (EssentialPipelineExtension.class) {
                if (instance == null) {
                    instance = new EssentialPipelineExtension(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * Initialize the extension (call at app startup).
     */
    public void initialize() {
        // Initialize RenderScript for bitmap utils
        BitmapUtils.initRenderScript(context);
        
        // Load color profile
        colorProfile = ColorProfile.load(context, "original_portrait");
        
        Log.d(TAG, "EssentialPipelineExtension initialized");
    }
    
    /**
     * Enable/disable Original Portrait processing.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.d(TAG, "Original Portrait " + (enabled ? "enabled" : "disabled"));
    }
    
    public void setQualityPreset(@NonNull BokehRenderer.QualityPreset preset) {
        this.qualityPreset = preset;
    }
    
    public void setIntensity(int intensity) {
        this.intensity = Math.max(0, Math.min(100, intensity));
    }
    
    /**
     * Check if capture request is for Original Portrait.
     */
    public static boolean isOriginalPortraitRequest(@NonNull CaptureRequest request) {
        Integer filterId = request.get(getVendorRequestKey(KEY_FILTER_ID));
        Integer stylizationType = request.get(getVendorRequestKey(KEY_STYLIZATION_TYPE));
        
        return filterId != null && filterId == FILTER_ID_ORIGINAL &&
               stylizationType != null && stylizationType == STYLIZATION_TYPE_PORTRAIT;
    }
    
    /**
     * Extract Original Portrait parameters from capture result.
     */
    public static @NonNull OriginalPortraitParams extractParams(@NonNull CaptureResult result) {
        OriginalPortraitParams params = new OriginalPortraitParams();
        
        // f_number (aperture)
        Float fNumber = result.get(getVendorResultKey(KEY_F_NUMBER));
        if (fNumber != null) params.fNumber = fNumber;
        
        // focal_length
        Float focalLength = result.get(getVendorResultKey(KEY_FOCAL_LENGTH));
        if (focalLength != null) params.focalLength = focalLength;
        
        // focus_rect
        float[] focusRect = result.get(getVendorResultKey(KEY_FOCUS_RECT));
        if (focusRect != null && focusRect.length == 4) {
            params.focusRect = focusRect;
        }
        
        // bokeh_style
        Integer bokehStyle = result.get(getVendorResultKey(KEY_BOKEH_STYLE));
        if (bokehStyle != null) params.bokehStyle = bokehStyle;
        
        // zoom_rate
        Float zoomRate = result.get(getVendorResultKey(KEY_ZOOM_RATE));
        if (zoomRate != null) params.zoomRate = zoomRate;
        
        // camera_role
        Integer cameraRole = result.get(getVendorResultKey(KEY_CAMERA_ROLE));
        if (cameraRole != null) params.cameraRole = cameraRole;
        
        // auto_f_number
        Boolean autoFNumber = result.get(getVendorResultKey(KEY_AUTO_F_NUMBER));
        if (autoFNumber != null) params.isAutoFNumber = autoFNumber;
        
        // static_scene
        Boolean staticScene = result.get(getVendorResultKey(KEY_STATIC_SCENE));
        if (staticScene != null) params.isStaticScene = staticScene;
        
        // motion_speed
        Float motionSpeed = result.get(getVendorResultKey(KEY_MOTION_SPEED));
        if (motionSpeed != null) params.motionSpeed = motionSpeed;
        
        // bypass
        Boolean bypass = result.get(getVendorResultKey(KEY_BOKEH_BYPASS));
        if (bypass != null) params.bypass = bypass;
        
        // Compute focal length from role if not set
        params.computeFocalLengthFromRole();
        
        return params;
    }
    
    /**
     * Main entry point: Handle Original Portrait post-capture.
     * Called from ModuleMain hook on capture completion.
     * 
     * @param jpegFile    Captured JPEG file (Master Portrait)
     * @param depthMap    Depth map from CaptureResult (nullable)
     * @param result      Full CaptureResult
     * @param callback    Callback with processed file
     */
    public void handlePortraitOriginal(@NonNull File jpegFile,
                                        @Nullable Object depthMap,
                                        @Nullable CaptureResult result,
                                        @NonNull OnProcessedCallback callback) {
        if (!enabled) {
            callback.onResult(jpegFile); // Return original
            return;
        }
        
        // Extract parameters. The camera-app process cannot always build a CaptureResult,
        // so fall back to the defaults instead of failing the whole post-process.
        OriginalPortraitParams params = result == null
                ? new OriginalPortraitParams()
                : extractParams(result);
        params.computeFocalLengthFromRole();
        
        // Apply intensity scaling to f_number
        params.fNumber = applyIntensity(params.fNumber);
        
        // Submit to background executor
        executor.execute(() -> {
            try {
                File processedFile = processPortraitOriginal(jpegFile, depthMap, params);
                mainHandler.post(() -> callback.onResult(processedFile));
            } catch (Throwable e) {
                Log.e(TAG, "Original portrait processing failed", e);
                mainHandler.post(() -> callback.onResult(jpegFile)); // Return original on failure
            }
        });
    }
    
    /**
     * Process portrait with Original look.
     */
    @WorkerThread
    private File processPortraitOriginal(@NonNull File jpegFile,
                                          @Nullable Object depthMap,
                                          @NonNull OriginalPortraitParams params) throws IOException {
        // Create output file
        File outputFile = File.createTempFile("original_portrait_", ".jpg", context.getCacheDir());
        
        // Load JPEG
        Bitmap bitmap = BitmapUtils.loadJpeg(jpegFile);
        if (bitmap == null) {
            throw new IOException("Failed to load JPEG: " + jpegFile);
        }
        
        // Apply color profile FIRST (on full-res)
        if (colorProfile != null) {
            bitmap = colorProfile.apply(bitmap);
        }
        
        // Prepare depth map
        float[] depthFloat = null;
        byte[] confidenceBytes = null;
        
        if (depthMap != null) {
            // Convert depth map to float array
            depthFloat = convertDepthMap(depthMap, bitmap.getWidth(), bitmap.getHeight());
            
            // Generate confidence map if not provided
            confidenceBytes = BitmapUtils.createConfidenceMap(depthFloat, bitmap.getWidth(), bitmap.getHeight());
        }
        
        // Render bokeh if depth available
        Bitmap resultBitmap = bitmap;
        if (depthFloat != null) {
            // Initialize renderer if needed
            if (bokehRenderer == null) {
                bokehRenderer = new BokehRenderer();
                if (!bokehRenderer.initialize(bitmap.getWidth(), bitmap.getHeight(), qualityPreset)) {
                    Log.w(TAG, "Bokeh renderer init failed, skipping bokeh");
                }
            }
            
            if (bokehRenderer != null && bokehRenderer.isInitialized()) {
                resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                boolean success = bokehRenderer.renderWithRawDepth(
                    bitmap,
                    depthFloatToBytes(depthFloat),
                    confidenceBytes,
                    params,
                    resultBitmap
                );
                
                if (!success) {
                    Log.w(TAG, "Bokeh render failed, using color-only result");
                    resultBitmap = bitmap;
                }
            }
        }
        
        // Save result
        if (!BitmapUtils.saveJpeg(resultBitmap, outputFile, 95)) {
            throw new IOException("Failed to save processed JPEG");
        }
        
        // Tag as Original Portrait for Gallery
        tagAsOriginalPortrait(outputFile);
        
        // Cleanup
        if (resultBitmap != bitmap) {
            resultBitmap.recycle();
        }
        bitmap.recycle();
        
        return outputFile;
    }
    
    // Helper: Apply intensity to f_number (lower f-number = more blur)
    private float applyIntensity(float fNumber) {
        // intensity 0 = no change, 100 = max effect
        // Map intensity to f_number adjustment
        float factor = 1.0f - (intensity / 100.0f) * 0.5f; // 1.0 to 0.5
        return fNumber * factor;
    }
    
    // Helper: Convert depth map to float array
    private float[] convertDepthMap(@Nullable Object depthMap, int width, int height) {
        if (depthMap == null) return null;
        
        if (depthMap instanceof float[]) {
            float[] depth = (float[]) depthMap;
            if (depth.length == width * height) return depth;
            return BitmapUtils.resizeDepthMap(depth, 
                (int) Math.sqrt(depth.length), (int) Math.sqrt(depth.length), width, height);
        }
        
        if (depthMap instanceof byte[]) {
            return BitmapUtils.depth16ToFloat((byte[]) depthMap, width, height);
        }
        
        if (depthMap instanceof Image) {
            // Convert YUV_420_888 or DEPTH16 image
            // Requires ImageReader or similar
            return null;
        }
        
        Log.w(TAG, "Unknown depth map type: " + depthMap.getClass());
        return null;
    }
    
    // Helper: Convert float[] depth to 16-bit bytes
    private byte[] depthFloatToBytes(float[] depthFloat) {
        byte[] depth16 = new byte[depthFloat.length * 2];
        for (int i = 0; i < depthFloat.length; i++) {
            int raw = Math.round(Math.min(1.0f, Math.max(0.0f, depthFloat[i])) * 65535.0f);
            depth16[i * 2] = (byte) (raw & 0xFF);
            depth16[i * 2 + 1] = (byte) ((raw >> 8) & 0xFF);
        }
        return depth16;
    }
    
    // Helper: Tag JPEG as Original Portrait for Gallery
    private void tagAsOriginalPortrait(@NonNull File file) {
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(file.getAbsolutePath());
            // EXIF tag 34992 (0x88B0) = legendmode
            // 0 = Original, 1 = M9, 2 = M3, 3 = M10R (per Camera 6.8)
            exif.setAttribute("34992", "0");
            exif.saveAttributes();
        } catch (IOException e) {
            Log.w(TAG, "Failed to write EXIF tag", e);
        }
    }
    
    // Helper: Get vendor capture request key
    @SuppressWarnings("unchecked")
    private static <T> CaptureRequest.Key<T> getVendorRequestKey(String key) {
        try {
            return (CaptureRequest.Key<T>) Class.forName("android.hardware.camera2.CaptureRequest")
                    .getDeclaredField(key).get(null);
        } catch (Exception e) {
            return null;
        }
    }
    
    // Helper: Get vendor capture result key
    @SuppressWarnings("unchecked")
    private static <T> CaptureResult.Key<T> getVendorResultKey(String key) {
        try {
            return (CaptureResult.Key<T>) Class.forName("android.hardware.camera2.CaptureResult")
                    .getDeclaredField(key).get(null);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Callback for processed result.
     */
    public interface OnProcessedCallback {
        void onResult(@NonNull File processedFile);
    }
    
    /**
     * Shutdown the extension.
     */
    public void shutdown() {
        executor.shutdown();
        if (bokehRenderer != null) {
            bokehRenderer.release();
            bokehRenderer = null;
        }
    }
}