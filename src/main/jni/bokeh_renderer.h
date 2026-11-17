/**
 * Original Portrait Bokeh Renderer - JNI Interface
 * 
 * OpenGL ES 3.1 Compute Shader based bokeh renderer
 * matching libAncHumanPreviewBokeh.so behavior on 17U/18PM.
 */

#ifndef BOKEH_RENDERER_H
#define BOKEH_RENDERER_H

#include <jni.h>
#include <GLES3/gl31.h>
#include <EGL/egl.h>
#include <android/bitmap.h>
#include <memory>
#include <vector>

namespace xiaomi {
namespace original_portrait {

// Parameter structure matching donor plugin tags (0x10000-0x1000B)
struct OriginalParams {
    float fNumber = 2.0f;           // tag 0x10000: Aperture (1.4-16.0)
    float focalLength = 35.0f;      // derived: Equivalent focal length (mm)
    float focusRect[4] = {0.5f, 0.5f, 0.5f, 0.5f}; // tag 0x10001: [cx, cy, w, h] normalized
    int bokehStyle = 0;             // tag 0x10002: 0=Natural,1=Smooth,2=Swirly,3=Catseye,4=Polygon
    float zoomRate = 1.0f;          // tag 0x10004: Digital zoom factor
    int cameraRole = 0;             // tag 0x10005: 0=Main,1=Tele,2=Ultra-wide
    bool isAutoFNumber = false;     // tag 0x10007: Auto aperture
    bool isStaticScene = false;     // tag 0x10009: Static scene optimization
    float motionSpeed = 1.0f;       // tag 0x1000A: Motion blur factor
    bool bypass = false;            // tag 0x1000B: Bypass bokeh entirely
    
    // Computed from cameraRole + zoomRate if not set explicitly
    void computeFocalLength() {
        switch (cameraRole) {
            case 0: focalLength = 23.0f * zoomRate; break;  // Main ~23mm
            case 1: focalLength = 75.0f * zoomRate; break;  // Tele ~75mm
            case 2: focalLength = 13.0f * zoomRate; break;  // Ultra-wide ~13mm
            default: focalLength = 35.0f * zoomRate; break;
        }
    }
};

// Quality presets
enum class QualityPreset {
    FAST,       // Separable 2-pass, half resolution
    BALANCED,   // Full disk, adaptive sampling
    QUALITY     // Full disk, temporal stability
};

// Renderer configuration
struct RendererConfig {
    QualityPreset quality = QualityPreset::BALANCED;
    bool useConfidenceMap = true;
    bool enableHighlightPreservation = true;
    bool enableEdgePreservation = true;
    int maxBlurRadius = 64;
};

// Forward declaration
class BokehRendererImpl;

/**
 * Main BokehRenderer class - JNI wrapper
 */
class BokehRenderer {
public:
    BokehRenderer();
    ~BokehRenderer();
    
    // Non-copyable, movable
    BokehRenderer(const BokehRenderer&) = delete;
    BokehRenderer& operator=(const BokehRenderer&) = delete;
    BokehRenderer(BokehRenderer&&) noexcept;
    BokehRenderer& operator=(BokehRenderer&&) noexcept;
    
    /**
     * Initialize renderer with output dimensions
     * @param width  Output image width
     * @param height Output image height
     * @param config Renderer configuration
     * @return true on success
     */
    bool initialize(int width, int height, const RendererConfig& config = {});
    
    /**
     * Render bokeh effect
     * @param srcRGBA       Source image (RGBA_8888, width x height)
     * @param depthMap      Normalized depth [0,1] (float, width x height)
     * @param confidenceMap Optional confidence [0,255] (uint8_t, width x height)
     * @param params        Original portrait parameters
     * @param dstRGBA       Output image (RGBA_8888, width x height)
     * @return true on success
     */
    bool render(const uint8_t* srcRGBA,
                const float* depthMap,
                const uint8_t* confidenceMap,
                const OriginalParams& params,
                uint8_t* dstRGBA);
    
    /**
     * Render from Android Bitmaps (convenience)
     * @param env JNI environment
     * @param srcBitmap Source bitmap (ARGB_8888)
     * @param depthBitmap Depth bitmap (single channel, normalized)
     * @param confidenceBitmap Optional confidence bitmap
     * @param params Original parameters
     * @param dstBitmap Output bitmap (ARGB_8888)
     * @return true on success
     */
    bool renderBitmaps(JNIEnv* env,
                       jobject srcBitmap,
                       jobject depthBitmap,
                       jobject confidenceBitmap,
                       const OriginalParams& params,
                       jobject dstBitmap);
    
    // Getters
    bool isInitialized() const { return initialized_; }
    int getWidth() const { return width_; }
    int getHeight() const { return height_; }
    
private:
    std::unique_ptr<BokehRendererImpl> impl_;
    bool initialized_ = false;
    int width_ = 0;
    int height_ = 0;
};

/**
 * JNI Helper Functions
 */

// Convert Java OriginalParams object to C++ struct
OriginalParams jniParamsToCpp(JNIEnv* env, jobject javaParams);

// Convert C++ OriginalParams to Java object (if needed)
jobject cppParamsToJni(JNIEnv* env, const OriginalParams& params);

// Extract bitmap pixels to buffer
bool lockBitmapPixels(JNIEnv* env, jobject bitmap, void** pixels, int* width, int* height);
void unlockBitmapPixels(JNIEnv* env, jobject bitmap);

// Check OpenGL errors
void checkGLError(const char* operation);

} // namespace original_portrait
} // namespace xiaomi

#endif // BOKEH_RENDERER_H