# Original Portrait Software Implementation Specification

**Target**: Xiaomi 14 Ultra (aurora) - Camera 6.7.000280.0, HyperOS 3.0.304.0.WNAMIXM
**Base**: RE Report `docs/original-portrait-re-report.md`
**Status**: Design Phase - No Device Testing

---

## 1. Implementation Strategy: Hybrid Approach

### 1.1 Primary: Post-Capture Processing (Approach A)
- **Capture**: Standard Master Portrait (works natively on aurora)
- **Process**: Software bokeh + color profile in EssentialService
- **Output**: Replace JPEG with "Original" look
- **Risk**: LOW - No HAL modification

### 1.2 Fallback: Minimal HAL Stub (Approach C)
- **Preview**: Stub `MiaPluginModule` that passes frames through
- **Capture**: Post-capture processing as above
- **Risk**: MEDIUM - Requires SELinux policy for new .so

### 1.3 Not Pursued
- ❌ Full CHI plugin injection (Approach B) - Too complex, high crash risk
- ❌ NPU-accelerated bokeh (Approach D) - Model training required, large binary

---

## 2. Software Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Camera App (6.7)                              │
│  Portrait Mode → filterId=2 (Original) → stylizationType=171    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    HAL / MiAlgo Engine                           │
│  • Creates standard preview session (NOT algorithm-up SAT)       │
│  • Uses libmialgo_mc_bokeh_cdsp_skel.so (DSP) for preview       │
│  • Capture: Master pipeline → JPEG + Depth Map                  │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
    ┌─────────────────────┐         ┌─────────────────────┐
    │ Preview Stream      │         │ Capture Callback    │
    │ (Master bokeh)      │         │ (ModuleMain hook)   │
    └─────────────────────┘         └─────────────────────┘
                                           │
                                           ▼
                              ┌─────────────────────────┐
                              │ EssentialPipeline       │
                              │ .handlePortraitOriginal(│
                              │   jpegFile, depthMap,   │
                              │   metadata)             │
                              └─────────────────────────┘
                                           │
                                           ▼
                              ┌─────────────────────────┐
                              │ EssentialService        │
                              │ (private process)       │
                              │ ┌───────────────────┐   │
                              │ │ Color Profile     │   │
                              │ │ (libm9base.so     │   │
                              │ │  parametric)      │   │
                              │ └───────────────────┘   │
                              │ ┌───────────────────┐   │
                              │ │ Bokeh Renderer    │   │
                              │ │ (OpenGL Compute)  │   │
                              │ └───────────────────┘   │
                              │ ┌───────────────────┐   │
                              │ │ Depth Compositor  │   │
                              │ │ (confidence-wt)   │   │
                              │ └───────────────────┘   │
                              └─────────────────────────┘
                                           │
                                           ▼
                              ┌─────────────────────────┐
                              │ Output JPEG             │
                              │ (replaces original)     │
                              └─────────────────────────┘
```

---

## 3. Component Specifications

### 3.1 EssentialPipeline Extension

**File**: `src/main/java/com/xiaomi/camera/essential/EssentialPipeline.kt`

```kotlin
object EssentialPipeline {
    // Existing: bindToService(), processPortrait(), etc.
    
    /** New: Handle Original Portrait post-capture */
    @JvmStatic
    fun handlePortraitOriginal(
        jpegFile: File,
        depthMap: DepthMap?,           // From CaptureResult.JPEG_DEPTH_MAP
        metadata: CaptureResult,       // Full capture metadata
        callback: (File) -> Unit       // Returns processed file
    ) {
        // 1. Validate depth map availability
        if (depthMap == null) {
            Log.w(TAG, "No depth map - falling back to Master")
            callback(jpegFile)
            return
        }
        
        // 2. Extract depth data
        val depthData = extractDepthBuffer(depthMap)
        val confidenceData = extractConfidenceMap(metadata) // Vendor metadata
        
        // 3. Prepare parameters from metadata
        val params = OriginalPortraitParams.fromCaptureResult(metadata)
        
        // 4. Send to EssentialService
        val request = OriginalPortraitRequest(
            jpegPath = jpegFile.absolutePath,
            depthBuffer = depthData,
            confidenceBuffer = confidenceData,
            params = params,
            outputPath = jpegFile.absolutePath + ".original.jpg"
        )
        
        EssentialService.enqueueOriginalPortrait(request) { resultFile ->
            if (resultFile.exists()) {
                // Atomic replace
                resultFile.renameTo(jpegFile)
            }
            callback(jpegFile)
        }
    }
    
    private fun extractDepthBuffer(depthMap: DepthMap): ByteArray {
        // DepthMap → ByteBuffer → ByteArray (16-bit normalized)
        val buffer = depthMap.getData(0) // Y plane = depth
        return buffer.toByteArray()
    }
    
    private fun extractConfidenceMap(metadata: CaptureResult): ByteArray? {
        // Try vendor metadata keys
        val keys = tryVendorDepthKeys(metadata)
        return keys?.let { it.toByteArray() }
    }
}

data class OriginalPortraitParams(
    val fNumber: Float = 2.0f,           // Aperture
    val focalLength: Float = 35.0f,      // 35mm or 75mm equivalent
    val focusRect: RectF = RectF(0.5f, 0.5f, 0.5f, 0.5f), // Normalized [cx, cy, w, h]
    val bokehStyle: Int = 0,             // 0=Natural, 1=Smooth, 2=Swirly, 3=Catseye
    val zoomRate: Float = 1.0f,          // Digital zoom factor
    val cameraRole: Int = 0,             // 0=Main, 1=Tele, 2=Ultra-wide
    val isAutoFNumber: Boolean = false,
    val isStaticScene: Boolean = false,
    val motionSpeed: Float = 1.0f,
    val bypass: Boolean = false
) {
    companion object {
        fun fromCaptureResult(result: CaptureResult): OriginalPortraitParams {
            return OriginalPortraitParams(
                fNumber = result.get(VendorCaptureRequestKeys.F_NUMBER) ?: 2.0f,
                focalLength = result.get(VendorCaptureRequestKeys.FOCAL_LENGTH) ?: 35.0f,
                focusRect = result.get(VendorCaptureRequestKeys.FOCUS_RECT) 
                    ?: RectF(0.5f, 0.5f, 0.5f, 0.5f),
                bokehStyle = result.get(VendorCaptureRequestKeys.BOKEH_STYLE) ?: 0,
                zoomRate = result.get(VendorCaptureRequestKeys.ZOOM_RATE) ?: 1.0f,
                cameraRole = result.get(VendorCaptureRequestKeys.CAMERA_ROLE) ?: 0,
                isAutoFNumber = result.get(VendorCaptureRequestKeys.AUTO_F_NUMBER) ?: false,
                isStaticScene = result.get(VendorCaptureRequestKeys.STATIC_SCENE) ?: false,
                motionSpeed = result.get(VendorCaptureRequestKeys.MOTION_SPEED) ?: 1.0f,
                bypass = result.get(VendorCaptureRequestKeys.BOKEH_BYPASS) ?: false
            )
        }
    }
}

data class OriginalPortraitRequest(
    val jpegPath: String,
    val depthBuffer: ByteArray,      // 16-bit depth, same res as JPEG
    val confidenceBuffer: ByteArray?, // 8-bit confidence, same res
    val params: OriginalPortraitParams,
    val outputPath: String
)
```

### 3.2 EssentialService Extension

**File**: `src/main/java/com/xiaomi/camera/essential/EssentialService.kt`

```kotlin
class EssentialService : Service() {
    private val originalPortraitExecutor = Executors.newSingleThreadExecutor()
    private var bokehRenderer: BokehRenderer? = null
    private var colorProfile: ColorProfile? = null
    
    override fun onCreate() {
        super.onCreate()
        // Initialize renderers
        bokehRenderer = BokehRenderer(this)
        colorProfile = ColorProfile.load(this, "original_portrait")
    }
    
    fun enqueueOriginalPortrait(request: OriginalPortraitRequest, callback: (File) -> Unit) {
        originalPortraitExecutor.execute {
            try {
                val result = processPortraitOriginal(request)
                callback(File(request.outputPath))
            } catch (e: Exception) {
                Log.e(TAG, "Original portrait failed", e)
                callback(File(request.jpegPath)) // Return original
            }
        }
    }
    
    private fun processPortraitOriginal(request: OriginalPortraitRequest): Boolean {
        // 1. Decode JPEG
        val bitmap = BitmapFactory.decodeFile(request.jpegPath)
            ?: return false
        
        // 2. Apply color profile FIRST (on full-res bitmap)
        val colorCorrected = colorProfile?.apply(bitmap) ?: bitmap
        
        // 3. Prepare depth map (resize to match bitmap if needed)
        val depthMap = prepareDepthMap(request.depthBuffer, bitmap.width, bitmap.height)
        val confidenceMap = request.confidenceBuffer?.let { 
            prepareConfidenceMap(it, bitmap.width, bitmap.height) 
        }
        
        // 4. Render bokeh
        val bokehBitmap = bokehRenderer?.render(
            source = colorCorrected,
            depthMap = depthMap,
            confidenceMap = confidenceMap,
            params = request.params
        ) ?: return false
        
        // 5. Encode output
        return saveJpeg(bokehBitmap, request.outputPath, 95)
    }
    
    private fun prepareDepthMap(buffer: ByteArray, width: Int, height: Int): FloatArray {
        // Convert 16-bit depth to normalized float [0,1]
        val depth = FloatArray(width * height)
        for (i in 0 until width * height) {
            val raw = (buffer[i * 2].toInt() and 0xFF) | 
                      ((buffer[i * 2 + 1].toInt() and 0xFF) shl 8)
            depth[i] = raw / 65535.0f
        }
        return depth
    }
}
```

### 3.3 Bokeh Renderer (OpenGL ES 3.1 Compute Shader)

**File**: `src/main/jni/bokeh_renderer.cpp`

```cpp
// BokehRenderer.h
class BokehRenderer {
public:
    BokehRenderer(JNIEnv* env, jobject context);
    ~BokehRenderer();
    
    bool initialize(int width, int height);
    bool render(
        const uint8_t* srcRGBA,      // Input bitmap (RGBA_8888)
        const float* depthMap,       // Normalized depth [0,1], same res
        const uint8_t* confidenceMap, // Optional confidence [0,255], same res
        const OriginalParams& params,
        uint8_t* dstRGBA             // Output bitmap
    );
    
private:
    GLuint computeProgram_;
    GLuint srcTexture_, depthTexture_, confidenceTexture_, dstTexture_;
    GLuint uniformBlock_;
    int width_, height_;
    
    bool createShaders();
    bool createTextures(int width, int height);
    void updateUniforms(const OriginalParams& params);
    void dispatchCompute(int width, int height);
};

// OriginalParams matches Kotlin data class
struct OriginalParams {
    float fNumber = 2.0f;
    float focalLength = 35.0f;  // mm
    float focusRect[4] = {0.5f, 0.5f, 0.5f, 0.5f}; // cx, cy, w, h normalized
    int bokehStyle = 0;
    float zoomRate = 1.0f;
    int cameraRole = 0;
    bool isAutoFNumber = false;
    bool isStaticScene = false;
    float motionSpeed = 1.0f;
    bool bypass = false;
};
```

**File**: `src/main/jni/shaders/bokeh.comp` (GLSL 310 es)

```glsl
#version 310 es
#extension GL_EXT_shader_explicit_arithmetic_types_float16 : enable

layout(local_size_x = 16, local_size_y = 16) in;

layout(rgba8, binding = 0) uniform image2D srcImage;
layout(r32f, binding = 1) uniform image2D depthMap;
layout(r8ui, binding = 2) uniform uimage2D confidenceMap;  // Optional
layout(rgba8, binding = 3) uniform image2D dstImage;

layout(std140, binding = 0) uniform Params {
    float fNumber;           // Aperture (e.g., 2.0)
    float focalLength;       // mm (35 or 75)
    vec4 focusRect;          // cx, cy, w, h normalized [0,1]
    int bokehStyle;          // 0-4
    float zoomRate;          // Digital zoom
    int cameraRole;          // 0=main, 1=tele, 2=uw
    bool isAutoFNumber;
    bool isStaticScene;
    float motionSpeed;
    bool bypass;
} params;

const float MAX_COC_PIXELS = 64.0;  // Max blur radius in pixels
const float MIN_COC_PIXELS = 0.5;   // Below this = no blur

// Thin lens Circle of Confusion
float computeCoC(float depth) {
    if (depth <= 0.0) return 0.0;
    
    // Focus distance from focusRect center (normalized)
    float focusDepth = texture(depthMap, params.focusRect.xy).r;
    if (focusDepth <= 0.0) focusDepth = 0.5; // Fallback
    
    // Aperture diameter in mm
    float aperture = params.focalLength / params.fNumber;
    
    // CoC in mm (simplified thin lens)
    float coc_mm = aperture * abs(1.0/focusDepth - 1.0/depth);
    
    // Convert to pixels: CoC_pixels = CoC_mm * sensor_scale * zoom
    // Sensor scale approx: 1mm = 200 pixels (for 12MP output)
    float sensorScale = 200.0 * params.zoomRate;
    
    return clamp(coc_mm * sensorScale, 0.0, MAX_COC_PIXELS);
}

// Bokeh disk weight function
float diskWeight(vec2 offset, float cocRadius, float confidence) {
    float dist = length(offset);
    if (dist > cocRadius) return 0.0;
    
    float normalizedDist = dist / cocRadius;
    
    // Style-dependent disk shape
    float weight;
    switch (params.bokehStyle) {
        case 0: // Natural - smooth gaussian-like
            weight = exp(-normalizedDist * normalizedDist * 4.0);
            break;
        case 1: // Smooth - softer edge
            weight = 1.0 - smoothstep(0.6, 1.0, normalizedDist);
            break;
        case 2: // Swirly - slight spiral
            float angle = atan(offset.y, offset.x) + normalizedDist * 0.5;
            weight = (1.0 - smoothstep(0.7, 1.0, normalizedDist)) * (0.8 + 0.2 * sin(angle * 3.0));
            break;
        case 3: // Catseye - vignetted toward edges
            weight = (1.0 - smoothstep(0.5, 1.0, normalizedDist)) * 
                     (1.0 - normalizedDist * 0.5); // Vignetting
            break;
        case 4: // Polygon (heptagon)
            {
                float sides = 7.0;
                float angle = atan(offset.y, offset.x);
                float polygonR = cocRadius / cos(fmod(abs(angle), 2.0*3.14159/sides) - 3.14159/sides);
                weight = smoothstep(polygonR*0.85, polygonR, dist);
            }
            break;
        default:
            weight = 1.0 - smoothstep(0.7, 1.0, normalizedDist);
    }
    
    // Confidence weighting (0-1)
    weight *= confidence / 255.0;
    
    return weight;
}

// Edge-preserving: bilateral-style weight
float edgeWeight(vec2 center, vec2 neighbor, float centerDepth, float neighborDepth) {
    float depthDiff = abs(centerDepth - neighborDepth);
    // Sharp depth transition = preserve edge
    return exp(-depthDiff * 50.0);
}

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    ivec2 dims = imageSize(srcImage);
    
    if (pos.x >= dims.x || pos.y >= dims.y) return;
    
    // Read source pixel
    vec4 srcColor = imageLoad(srcImage, pos);
    
    // Read depth
    float depth = imageLoad(depthMap, pos).r;
    
    // Read confidence (optional)
    float confidence = 255.0;
    if (gl_IsBoundTextureUnit(2)) {
        confidence = float(imageLoad(confidenceMap, pos).r);
    }
    
    // If no depth or bypass, pass through
    if (depth <= 0.0 || params.bypass) {
        imageStore(dstImage, pos, srcColor);
        return;
    }
    
    float coc = computeCoC(depth);
    if (coc < MIN_COC_PIXELS) {
        imageStore(dstImage, pos, srcColor);
        return;
    }
    
    // Gather samples within CoC radius
    int radius = int(ceil(coc));
    vec3 accum = vec3(0.0);
    float totalWeight = 0.0;
    
    // Optimized: separable or importance sampling
    // For quality: full disk sampling
    for (int dy = -radius; dy <= radius; dy++) {
        for (int dx = -radius; dx <= radius; dx++) {
            ivec2 samplePos = pos + ivec2(dx, dy);
            if (samplePos.x < 0 || samplePos.x >= dims.x ||
                samplePos.y < 0 || samplePos.y >= dims.y) continue;
            
            vec2 offset = vec2(float(dx), float(dy));
            float sampleDepth = imageLoad(depthMap, samplePos).r;
            if (sampleDepth <= 0.0) continue;
            
            float w = diskWeight(offset, coc, confidence);
            if (w <= 0.0) continue;
            
            // Edge preservation
            w *= edgeWeight(vec2(pos), vec2(samplePos), depth, sampleDepth);
            
            vec4 sampleColor = imageLoad(srcImage, samplePos);
            accum += sampleColor.rgb * w;
            totalWeight += w;
        }
    }
    
    if (totalWeight > 0.0) {
        vec3 result = accum / totalWeight;
        // Preserve alpha
        imageStore(dstImage, pos, vec4(result, srcColor.a));
    } else {
        imageStore(dstImage, pos, srcColor);
    }
}
```

### 3.4 Color Profile Module

**Option A: Parametric (libm9base.so)** - Preferred if Original look is parametric

```kotlin
// ColorProfile.kt
class ColorProfile @JvmOverloads constructor(
    context: Context,
    profileName: String = "original_portrait"
) {
    // libm9base.so parametric filter params
    // Determined by fitting to donor captures
    private val params = FloatArray(32) // Example size
    
    companion object {
        @JvmStatic
        fun load(context: Context, name: String): ColorProfile {
            // Load from assets/original_portrait_params.bin
            // Or generate from hardcoded values
        }
    }
    
    fun apply(bitmap: Bitmap): Bitmap {
        // Use libm9base.so JNI if available
        // Or implement in RenderScript/OpenGL
        val renderer = ColorProfileRenderer()
        return renderer.apply(bitmap, params)
    }
    
    // Parametric model (example):
    // params[0] = contrast (1.0 = neutral)
    // params[1] = saturation (1.0 = neutral)
    // params[2] = hueShift (degrees)
    // params[3] = highlights (-1 to 1)
    // params[4] = shadows (-1 to 1)
    // params[5] = midtones (-1 to 1)
    // params[6-21] = toneCurve (16 points)
    // params[22-31] = colorGrading (shadows/midtones/highlights RGB)
}
```

**Option B: 3D LUT** - If color transform is complex

```glsl
// LUT shader (if needed)
layout(binding = 4) uniform sampler3D lutTexture;
vec3 applyLUT(vec3 color) {
    return texture(lutTexture, color).rgb;
}
```

**Donor Capture Analysis Required**:
1. Shoot identical scene: Master vs Original on 17U/18PM
2. Compute ΔE in LAB space across patches
3. Fit parametric model or extract LUT
4. Validate on multiple scenes

### 3.5 Depth Map Handling

```kotlin
// DepthMapUtils.kt
object DepthMapUtils {
    
    /** Aurora depth format detection */
    fun detectDepthFormat(metadata: CaptureResult): DepthFormat {
        // Check JPEG_DEPTH_MAP
        val depthMap = metadata.get(CaptureResult.JPEG_DEPTH_MAP)
        if (depthMap != null) return DepthFormat.JPEG_DEPTH_MAP
        
        // Check vendor metadata
        val vendorKeys = listOf(
            "xiaomi.camera.depth",
            "xiaomi.camera.bokeh_data",
            "xiaomi.camera.depth_confidence"
        )
        for (key in vendorKeys) {
            val data = metadata.get(VendorMetadataKey(key))
            if (data != null) return DepthFormat.VENDOR_SPECIFIC
        }
        
        return DepthFormat.NONE
    }
    
    /** Normalize any depth format to FloatArray [0,1] */
    fun normalizeDepth(
        raw: ByteArray, 
        width: Int, 
        height: Int, 
        format: DepthFormat
    ): FloatArray {
        return when (format) {
            DepthFormat.JPEG_DEPTH_MAP -> normalizeJpegDepth(raw, width, height)
            DepthFormat.VENDOR_SPECIFIC -> normalizeVendorDepth(raw, width, height)
            else -> FloatArray(width * height) { 0.5f } // Fallback
        }
    }
    
    private fun normalizeJpegDepth(raw: ByteArray, w: Int, h: Int): FloatArray {
        // JPEG depth is typically 16-bit grayscale in Y plane
        val depth = FloatArray(w * h)
        for (i in 0 until w * h) {
            val v = (raw[i * 2].toInt() and 0xFF) | ((raw[i * 2 + 1].toInt() and 0xFF) shl 8)
            depth[i] = v / 65535.0f
        }
        return depth
    }
}
```

---

## 4. Configuration Parameters

### 4.1 User Settings (preferences.xml)

```xml
<SwitchPreferenceCompat
    android:key="original_portrait_enabled"
    android:title="@string/original_portrait_title"
    android:summary="@string/original_portrait_summary"
    android:defaultValue="false" />

<ListPreference
    android:key="original_portrait_quality"
    android:title="@string/original_portrait_quality_title"
    android:entries="@array/original_portrait_quality_entries"
    android:entryValues="@array/original_portrait_quality_values"
    android:defaultValue="balanced" />

<SeekBarPreference
    android:key="original_portrait_intensity"
    android:title="@string/original_portrait_intensity_title"
    android:min="0"
    android:max="100"
    android:defaultValue="75" />

<SwitchPreferenceCompat
    android:key="original_portrait_realtime_preview"
    android:title="@string/original_portrait_realtime_title"
    android:summary="@string/original_portrait_realtime_summary"
    android:defaultValue="false" />
```

### 4.2 Quality Presets

| Preset | Compute Shader | Resolution | Est. Latency |
|--------|----------------|------------|--------------|
| `fast` | Separable (2-pass) | 1/2 JPEG | ~150ms |
| `balanced` | Full disk (optimized) | 1/1 JPEG | ~350ms |
| `quality` | Full disk + temporal | 1/1 JPEG | ~600ms |

---

## 5. Integration with Existing Module

### 5.1 ModuleMain Hook

```kotlin
// ModuleMain.kt - in install() method
private fun installOriginalPortraitHook(classLoader: ClassLoader) {
    val captureCallbackClass = classLoader.loadClass(
        "com.android.camera.capture.CaptureCallback"
    )
    
    // Hook onCaptureCompleted for Portrait mode
    XposedHelpers.findAndHookMethod(
        captureCallbackClass,
        "onCaptureCompleted",
        CameraCaptureSession::class.java,
        CaptureRequest::class.java,
        TotalCaptureResult::class.java,
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.args[2] as TotalCaptureResult
                val request = param.args[1] as CaptureRequest
                
                // Check if Original Portrait was requested
                val filterId = request.get(VendorCaptureRequestKeys.FILTER_ID) ?: 0
                val stylizationType = request.get(VendorCaptureRequestKeys.STYLIZATION_TYPE) ?: 0
                
                if (filterId == 2 && stylizationType == 171 && 
                    Preferences.getBoolean("original_portrait_enabled", false)) {
                    
                    // Get JPEG output
                    val jpegFile = getOutputFile(result)
                    if (jpegFile != null) {
                        EssentialPipeline.handlePortraitOriginal(
                            jpegFile,
                            result.get(CaptureResult.JPEG_DEPTH_MAP),
                            result
                        ) { processedFile ->
                            // Notify gallery, update UI
                            MediaScanner.scanFile(processedFile)
                        }
                    }
                }
            }
        }
    )
}
```

### 5.2 Gallery Integration

```kotlin
// Add EXIF tag for Gallery recognition
private fun tagAsOriginalPortrait(jpegFile: File) {
    val exif = ExifInterface(jpegFile.absolutePath)
    exif.setAttribute(ExifInterface.TAG_MAKE, "Xiaomi")
    exif.setAttribute(ExifInterface.TAG_MODEL, "14 Ultra")
    // Custom tag: legendmode = 0 (Original) per Camera 6.8
    exif.setAttribute("34992", "0") // 0x88B0 = legendmode EXIF tag
    exif.saveAttributes()
}
```

---

## 6. Testing & Validation Plan

### 6.1 Offline Validation (No Device)

| Test | Method | Success Criteria |
|------|--------|------------------|
| Shader compilation | `glslangValidator` | No errors |
| Parameter mapping | Unit tests | All tags map correctly |
| Depth normalization | Synthetic depth maps | Correct [0,1] range |
| Color profile fit | Python/NumPy on donor captures | ΔE < 2.0 vs target |
| Bokeh PSF | Point light synthetic | Matches donor disk shape |

### 6.2 Device Testing (When Phone Available)

| Priority | Test | Target |
|----------|------|--------|
| Critical | Master capture + callback works | Hook fires |
| Critical | Depth map available in Portrait | `JPEG_DEPTH_MAP` non-null |
| High | Master vs Original visual match | ΔE < 3.0 on test scenes |
| High | Latency (balanced preset) | < 500ms added |
| Medium | Various apertures (f/1.4-f/8) | Smooth falloff |
| Medium | Edge cases (hair, glasses) | No halos |
| Low | Gallery shows "Original" badge | EXIF tag recognized |

### 6.3 Quality Metrics

```python
# Validation script (offline)
def validate_original_look(master_jpg, original_jpg, donor_original_jpg):
    # 1. Color accuracy
    delta_e = compute_delta_e(original_jpg, donor_original_jpg)
    
    # 2. Bokeh disk similarity
    disk_similarity = compare_psf(original_jpg, donor_original_jpg)
    
    # 3. Edge handling
    edge_score = evaluate_edges(original_jpg, master_jpg)
    
    # 4. Overall perceptual
    lpips_score = compute_lpips(original_jpg, donor_original_jpg)
    
    return {
        "delta_e": delta_e,           # Target < 2.0
        "disk_similarity": disk_similarity,  # Target > 0.9
        "edge_score": edge_score,     # Target > 0.85
        "lpips": lpips_score          # Target < 0.1
    }
```

---

## 7. Build & Deployment

### 7.1 Gradle Dependencies

```gradle
// build.gradle.kts
dependencies {
    // OpenGL ES 3.1
    implementation("androidx.core:core-ktx:1.12.0")
    
    // Image processing
    implementation("androidx.renderscript:renderscript:1.0.0")
    
    // JNI
    externalNativeBuild {
        cmake {
            path "src/main/jni/CMakeLists.txt"
        }
    }
}
```

### 7.2 CMakeLists.txt

```cmake
cmake_minimum_required(VERSION 3.18.1)
project("bokeh_renderer" LANGUAGES CXX)

add_library(bokeh_renderer SHARED
    bokeh_renderer.cpp
    # Shaders compiled at build time
)

target_link_libraries(bokeh_renderer
    android
    log
    EGL
    GLESv3
    # libm9base if available
)

# Compile shaders to SPIR-V or embed as strings
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/bokeh_comp.spv
    COMMAND glslangValidator -V -o ${CMAKE_CURRENT_BINARY_DIR}/bokeh_comp.spv
            ${CMAKE_CURRENT_SOURCE_DIR}/shaders/bokeh.comp
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/shaders/bokeh.comp
)
```

---

## 8. Risks & Mitigations

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| No depth map in capture | MEDIUM | HIGH | Fallback to Master; log warning |
| Depth map wrong resolution | LOW | MEDIUM | Resize with bilinear interp |
| Color profile mismatch | MEDIUM | HIGH | Multiple presets; user adjustment |
| Shader too slow on GPU | LOW | MEDIUM | Quality presets; separable blur |
| Gallery doesn't recognize | LOW | LOW | EXIF tag + sidecar JSON |
| SELinux blocks stub .so | MEDIUM | HIGH | (If using stub) proper policy |

---

## 9. Timeline

| Phase | Duration | Deliverable |
|-------|----------|-------------|
| 1. Shader & Core Logic | 1 week | Working compute shader + JNI |
| 2. Pipeline Integration | 3 days | EssentialPipeline/Service hooks |
| 3. Color Profile | 1 week | Fitted params from donor captures |
| 4. Offline Validation | 3 days | Synthetic test suite passes |
| 5. Device Testing | 1 week | On-device validation |
| 6. Polish & Release | 3 days | Settings, error handling, docs |

**Total**: ~4 weeks (part-time)

---

## 10. Appendix: Parameter Reference

### 10.1 Donor Plugin Tags (from RE)

| Tag | Name | Type | Range | Default |
|-----|------|------|-------|---------|
| 0x10000 | f_number | float | 1.4-16.0 | 2.0 |
| 0x10001 | focus_rect | float[4] | [0,1] normalized | 0.5,0.5,0.5,0.5 |
| 0x10002 | bokeh_style | uint32 | 0-4 | 0 |
| 0x10003 | af_code | uint32 | - | 0 |
| 0x10004 | zoom_rate | float | 1.0-10.0 | 1.0 |
| 0x10005 | camera_role | uint32 | 0-5 | 0 |
| 0x10006 | focal_range | uint32 | - | 0 |
| 0x10007 | is_auto_f_number | bool | - | false |
| 0x10008 | af_code_bypass | uint32 | - | 0 (RO) |
| 0x10009 | is_static_scene | bool | - | false |
| 0x1000A | motion_speed | float | 0.0-2.0 | 1.0 |
| 0x1000B | bypass | bool | - | false |
| 0x1000C | batch_params | struct[] | - | - |

### 10.2 Config Offsets (render_config + 0x98)

| Offset | Field | Type |
|--------|-------|------|
| 0x424 | f_number (manual) | float |
| 0x4B8 | f_number (auto) | float |
| 0x4B0 | bokeh_style | uint32 |
| 0x4A8 | af_code | uint32 |
| 0x448 | zoom_rate | float |
| 0x44C | camera_role | uint32 |
| 0x4AC | focal_range | uint32 |
| 0x4B4 | is_auto_f_number | byte |
| 0x4BC | af_code_bypass | uint32 |
| 0x4C1 | is_static_scene | byte |
| 0x4C4 | motion_speed | float |
| 0x4C8 | bypass | byte |

---

*End of Implementation Specification*