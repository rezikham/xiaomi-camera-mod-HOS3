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

import io.martin.hypercameramod.ExifUtils;

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
    private int intensity = 75; // 0-100 (100 = full 18PM baseline fNumber, lower = gentler blur)
    
    // State
    private BokehRenderer bokehRenderer;
    private ColorProfile colorProfile;
    private AncBokehRenderer ancBokeh;
    private boolean initialized = false;
    
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
    public synchronized void initialize() {
        if (initialized && colorProfile != null && ancBokeh != null && ancBokeh.isReady()) {
            return;
        }
        // Initialize RenderScript for bitmap utils
        BitmapUtils.initRenderScript(context);
        
        // Load color profile once
        if (colorProfile == null) {
            colorProfile = ColorProfile.load(context, "original_portrait",
                    io.martin.hypercameramod.ModuleMain.getModuleApkPath());
        }

        // Load the 14 Ultra AI single-bokeh engine (SM8650 models, v3 API).
        if (ancBokeh == null) {
            ancBokeh = new AncBokehRenderer();
        }
        if (!ancBokeh.isReady()) {
            boolean ok = ancBokeh.initialize(context,
                    io.martin.hypercameramod.ModuleMain.getModuleApkPath(),
                    BokehRenderer.getNativeLibraryDir());
            Log.i(TAG, "AI bokeh " + (ok ? "ready" : "unavailable"));
        }

        initialized = true;
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
        handlePortraitOriginal(jpegFile, depthMap, result, null, callback);
    }

    public void handlePortraitOriginal(@NonNull File jpegFile,
                                        @Nullable Object depthMap,
                                        @Nullable CaptureResult result,
                                        @Nullable float[] liveFocusPointNorm,
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
        // Clear the synthetic center default unless real focus metadata is available.
        params.focusRect = null;
        if (liveFocusPointNorm != null && liveFocusPointNorm.length >= 2) {
            params.focusRect = new float[]{liveFocusPointNorm[0], liveFocusPointNorm[1], 0.16f, 0.20f};
        }
        
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
        
        int exifOrientation = readExifOrientationDegrees(jpegFile);

        // Prefer the camera's real XMP focuspoint (before re-encoding strips XMP) over
        // any live AF hint so non-frontal/downward faces keep the sharp focal plane on the subject.
        float[] xmpFocus = AncBokehRenderer.extractXmpFocusPoint(
                jpegFile, bitmap.getWidth(), bitmap.getHeight());
        if (xmpFocus != null) {
            params.focusRect = new float[]{xmpFocus[0], xmpFocus[1], 0.16f, 0.20f};
        }

        // Resolve the 18 Pro Max Original aperture baseline (2.8 at 23-35 mm, 4.5 at 75 mm),
        // then apply intensity scaling AFTER resolution so intensity actually controls blur.
        float baseFNumber = resolveOriginalAperture(jpegFile, params);
        params.fNumber = applyIntensity(baseFNumber);
        Log.i(TAG, "Original aperture base=" + baseFNumber + " intensity=" + intensity
                + " -> f=" + params.fNumber + " ori=" + exifOrientation);

        // Render the 14 Ultra AI bokeh first, on the sharp capture.
        if (ancBokeh != null && ancBokeh.isReady() && !params.bypass) {
            Bitmap aiBokeh = ancBokeh.process(bitmap, params, exifOrientation);
            if (aiBokeh != null) {
                bitmap.recycle();
                bitmap = aiBokeh;
                Log.i(TAG, "AI bokeh rendered");
            } else {
                Log.w(TAG, "AI bokeh failed, keeping sharp image");
            }
        }

        // Apply the exact 18 Pro Max Original LUT after bokeh, recycling the intermediate bitmap.
        if (colorProfile != null) {
            Bitmap graded = colorProfile.apply(bitmap);
            if (graded != bitmap) {
                bitmap.recycle();
                bitmap = graded;
            }
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
        
        // The camera's Master portrait already has real depth-based bokeh. The
        // synthetic renderer is kept for the real-depth experiment but must not
        // double-blur the image; it will be enabled again when the vendor depth
        // container is decoded.
        final boolean useSyntheticBokeh = false;
        Bitmap resultBitmap = bitmap;
        if (useSyntheticBokeh && depthFloat != null) {
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

        // Release large bitmaps before optional depth/raw JPEG grading to keep peak heap low.
        if (resultBitmap != bitmap) {
            resultBitmap.recycle();
        }
        bitmap.recycle();

        // Keep the camera's EXIF (time, location, orientation, maker notes), then rewrite
        // XiaomiCvSessionkeyType (0x889F) from Master Portrait (2) — which HAL writes during the
        // preview-bokeh remap — to NATURAL_PORTRAIT (104) so Gallery details show "Original portrait".
        ExifUtils.copyExifFromFile(jpegFile, outputFile);
        ExifUtils.setCvSessionKeyTypeInFile(outputFile, ExifUtils.CV_SESSION_ORIGINAL_PORTRAIT);

        boolean depthPreserved = ExifUtils.preservePortraitDepthFromFile(
                jpegFile, outputFile,
                colorProfile != null ? raw -> colorProfile.apply(raw) : null);
        if (!depthPreserved) {
            ExifUtils.stripRefocusTagsInFile(outputFile);
        }
        Log.i(TAG, "Original Portrait depth "
                + (depthPreserved ? "preserved" : "unavailable (refocus tags stripped)"));

        return outputFile;
    }
    
    private float resolveOriginalAperture(@NonNull File jpegFile, @NonNull OriginalPortraitParams params) {
        try {
            android.media.ExifInterface exif =
                    new android.media.ExifInterface(jpegFile.getAbsolutePath());
            int focal35 = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0);
            if (focal35 > 0) {
                float target = focal35 <= 35 ? 2.8f : 4.5f;
                Log.i(TAG, "Original aperture " + target + " for " + focal35 + "mm");
                return target;
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot read focal length: " + t);
        }
        return params.focalLength <= 35 ? 2.8f : 4.5f;
    }

    private int readExifOrientationDegrees(@NonNull File jpegFile) {
        try {
            android.media.ExifInterface exif =
                    new android.media.ExifInterface(jpegFile.getAbsolutePath());
            int o = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    // Helper: Apply intensity to f_number (higher f-number = gentler blur).
    // intensity=100 keeps the 18PM baseline f-number (2.8 / 4.5);
    // intensity=75 stops down gently (~3.85 / 6.2) for cleaner edges on 14U's single-bokeh engine;
    // intensity=0 stops down to f/15.5 (minimal blur within libanc's [1.0, 16.0) range).
    private float applyIntensity(float baseFNumber) {
        if (intensity <= 0) {
            return 15.5f;
        }
        float scale = 1.0f + ((100 - intensity) / 100.0f) * 1.5f;
        float adjusted = baseFNumber * scale;
        return Math.max(1.2f, Math.min(15.5f, adjusted));
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
            // EXIF tag 34992 (0x88B0) = legendmode
            // 0 = Original, 1 = M9, 2 = M3, 3 = M10R (per Camera 6.8)
            ExifUtils.setLegendModeInFile(file, 0);
        } catch (Throwable e) {
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