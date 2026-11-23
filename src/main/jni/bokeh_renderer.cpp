/**
 * Original Portrait Bokeh Renderer - Implementation
 */

#include "bokeh_renderer.h"
#include <android/log.h>
#include <cstring>
#include <vector>

#define LOG_TAG "OriginalPortraitBokeh"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace xiaomi {
namespace original_portrait {

// -----------------------------------------------------------------------------
// Shader Source (embedded from bokeh.comp)
// -----------------------------------------------------------------------------
static const char* kComputeShaderSource = R"GLSL(
#version 310 es

precision highp float;
precision highp int;

layout(local_size_x = 16, local_size_y = 16) in;

layout(rgba8, binding = 0) readonly uniform highp image2D srcImage;
layout(r32f, binding = 1) readonly uniform highp image2D depthMap;
layout(rgba8, binding = 3) writeonly uniform highp image2D dstImage;

layout(std140, binding = 0) uniform Params {
    float fNumber;
    float focalLength;
    vec4 focusRect;
    int bokehStyle;
    float zoomRate;
    int cameraRole;
    int isAutoFNumber;
    int isStaticScene;
    float motionSpeed;
    int bypass;
} params;

const float MAX_COC_PIXELS = 64.0;
const float MIN_COC_PIXELS = 0.5;
const float SENSOR_SCALE_BASE = 200.0;
const float FOCUS_FALLBACK_DEPTH = 0.5;

float computeCoC(float depth) {
    if (depth <= 0.0) return 0.0;
    vec2 focusUV = params.focusRect.xy;
    ivec2 focusPos = ivec2(focusUV * vec2(imageSize(depthMap)));
    float focusDepth = imageLoad(depthMap, focusPos).r;
    if (focusDepth <= 0.0) focusDepth = FOCUS_FALLBACK_DEPTH;
    float aperture_mm = params.focalLength / params.fNumber;
    float coc_mm = aperture_mm * abs(1.0/focusDepth - 1.0/depth);
    float sensorScale = SENSOR_SCALE_BASE * params.zoomRate;
    return clamp(coc_mm * sensorScale, 0.0, MAX_COC_PIXELS);
}

float diskWeight_Natural(vec2 offset, float cocRadius) {
    float normalizedDist = length(offset) / cocRadius;
    return exp(-normalizedDist * normalizedDist * 4.0);
}

float diskWeight_Smooth(vec2 offset, float cocRadius) {
    float normalizedDist = length(offset) / cocRadius;
    return 1.0 - smoothstep(0.6, 1.0, normalizedDist);
}

float diskWeight_Swirly(vec2 offset, float cocRadius) {
    float dist = length(offset);
    float normalizedDist = dist / cocRadius;
    float angle = atan(offset.y, offset.x) + normalizedDist * 0.8;
    float baseWeight = 1.0 - smoothstep(0.7, 1.0, normalizedDist);
    float swirl = 0.85 + 0.15 * sin(angle * 4.0);
    return baseWeight * swirl;
}

float diskWeight_Catseye(vec2 offset, float cocRadius) {
    float dist = length(offset);
    float normalizedDist = dist / cocRadius;
    float baseWeight = 1.0 - smoothstep(0.5, 1.0, normalizedDist);
    float vignette = 1.0 - normalizedDist * 0.6;
    return baseWeight * vignette;
}

float diskWeight_Polygon(vec2 offset, float cocRadius) {
    float dist = length(offset);
    float normalizedDist = dist / cocRadius;
    if (normalizedDist > 1.0) return 0.0;
    float sides = 7.0;
    float angle = atan(offset.y, offset.x);
    float polygonR = cocRadius / cos(mod(abs(angle), 2.0 * 3.14159265 / sides) - 3.14159265 / sides);
    float edgeDist = polygonR - dist;
    return smoothstep(-2.0, 2.0, edgeDist);
}

float diskWeight(vec2 offset, float cocRadius, float confidence) {
    if (cocRadius <= 0.0) return 0.0;
    float w = 0.0;
    switch (params.bokehStyle) {
        case 0: w = diskWeight_Natural(offset, cocRadius); break;
        case 1: w = diskWeight_Smooth(offset, cocRadius); break;
        case 2: w = diskWeight_Swirly(offset, cocRadius); break;
        case 3: w = diskWeight_Catseye(offset, cocRadius); break;
        case 4: w = diskWeight_Polygon(offset, cocRadius); break;
        default: w = diskWeight_Smooth(offset, cocRadius); break;
    }
    return w * (confidence / 255.0);
}

float edgeWeight(float centerDepth, float neighborDepth, float depthScale) {
    float depthDiff = abs(centerDepth - neighborDepth);
    return exp(-depthDiff * depthScale);
}

vec3 preserveHighlights(vec3 color, float weight) {
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    if (luminance > 1.5) {
        float compression = mix(1.0, 0.5, weight);
        return color * compression;
    }
    return color;
}

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    ivec2 dims = imageSize(srcImage);
    if (pos.x >= dims.x || pos.y >= dims.y) return;
    
    vec4 srcColor = imageLoad(srcImage, pos);
    float depth = imageLoad(depthMap, pos).r;
    float confidence = 255.0;
    
    if (params.bypass != 0 || depth <= 0.0) {
        imageStore(dstImage, pos, srcColor);
        return;
    }
    
    float coc = computeCoC(depth);
    if (coc < MIN_COC_PIXELS) {
        imageStore(dstImage, pos, srcColor);
        return;
    }
    
    int radius = int(ceil(coc));
    radius = min(radius, int(MAX_COC_PIXELS));
    
    vec3 accum = vec3(0.0);
    float totalWeight = 0.0;
    int step = max(1, radius / 16);
    
    for (int dy = -radius; dy <= radius; dy += step) {
        for (int dx = -radius; dx <= radius; dx += step) {
            ivec2 samplePos = pos + ivec2(dx, dy);
            if (samplePos.x < 0 || samplePos.x >= dims.x ||
                samplePos.y < 0 || samplePos.y >= dims.y) continue;
            
            vec2 offset = vec2(float(dx), float(dy));
            float dist = length(offset);
            if (dist > coc) continue;
            
            float sampleDepth = imageLoad(depthMap, samplePos).r;
            if (sampleDepth <= 0.0) continue;
            
            float w = diskWeight(offset, coc, confidence);
            if (w <= 0.001) continue;
            
            w *= edgeWeight(depth, sampleDepth, 50.0);
            
            vec4 sampleColor = imageLoad(srcImage, samplePos);
            vec3 sampleRGB = preserveHighlights(sampleColor.rgb, w);
            
            accum += sampleRGB * w;
            totalWeight += w;
        }
    }
    
    if (totalWeight > 0.0) {
        vec3 result = accum / totalWeight;
        imageStore(dstImage, pos, vec4(result, srcColor.a));
    } else {
        imageStore(dstImage, pos, srcColor);
    }
}
)GLSL";

// -----------------------------------------------------------------------------
// Implementation Class
// -----------------------------------------------------------------------------
class BokehRendererImpl {
public:
    BokehRendererImpl() : computeProgram_(0), initialized_(false) {}
    ~BokehRendererImpl() { cleanup(); }
    
    bool initialize(int width, int height, const RendererConfig& config) {
        width_ = width;
        height_ = height;
        config_ = config;
        
        if (!createContext()) return false;
        if (!createShaders()) return false;
        if (!createTextures()) return false;
        if (!createUniformBuffer()) return false;
        
        initialized_ = true;
        LOGD("BokehRenderer initialized: %dx%d", width_, height_);
        return true;
    }
    
    bool render(const uint8_t* srcRGBA,
                const float* depthMap,
                const uint8_t* confidenceMap,
                const OriginalParams& params,
                uint8_t* dstRGBA) {
        if (!initialized_) {
            LOGE("Renderer not initialized");
            return false;
        }
        
        // Update textures
        updateTexture(srcTexture_, srcRGBA, GL_RGBA, GL_UNSIGNED_BYTE);
        updateTexture(depthTexture_, depthMap, GL_RED, GL_FLOAT);
        if (confidenceMap && config_.useConfidenceMap) {
            updateTexture(confidenceTexture_, confidenceMap, GL_RED_INTEGER, GL_UNSIGNED_BYTE);
        }
        
        // Update uniform buffer
        updateUniforms(params);
        
        // Dispatch compute
        dispatchCompute();
        
        // Read back result
        readbackTexture(dstTexture_, dstRGBA);
        
        return true;
    }
    
private:
    // OpenGL resources
    GLuint computeProgram_ = 0;
    GLuint srcTexture_ = 0, depthTexture_ = 0, confidenceTexture_ = 0, dstTexture_ = 0;
    GLuint uniformBuffer_ = 0;
    GLuint colorProfileBuffer_ = 0;
    
    // EGL context (for offscreen rendering)
    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    
    // State
    int width_ = 0, height_ = 0;
    RendererConfig config_;
    bool initialized_ = false;
    
    // -------------------------------------------------------------------------
    // EGL Context Creation (Offscreen)
    // -------------------------------------------------------------------------
    bool createContext() {
        // Get default display
        eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (eglDisplay_ == EGL_NO_DISPLAY) {
            LOGE("eglGetDisplay failed: %d", eglGetError());
            return false;
        }
        
        // Initialize
        EGLint major, minor;
        if (!eglInitialize(eglDisplay_, &major, &minor)) {
            LOGE("eglInitialize failed: %d", eglGetError());
            return false;
        }
        LOGD("EGL version: %d.%d", major, minor);
        
        // Choose config for OpenGL ES 3.1
        const EGLint configAttribs[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0,
            EGL_STENCIL_SIZE, 0,
            EGL_NONE
        };
        
        EGLConfig config;
        EGLint numConfigs;
        if (!eglChooseConfig(eglDisplay_, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
            LOGE("eglChooseConfig failed: %d", eglGetError());
            return false;
        }
        
        // Create pbuffer surface (1x1, we don't render to screen)
        const EGLint pbufferAttribs[] = {
            EGL_WIDTH, 1,
            EGL_HEIGHT, 1,
            EGL_NONE
        };
        eglSurface_ = eglCreatePbufferSurface(eglDisplay_, config, pbufferAttribs);
        if (eglSurface_ == EGL_NO_SURFACE) {
            LOGE("eglCreatePbufferSurface failed: %d", eglGetError());
            return false;
        }
        
        // Create context (ES 3.1)
        const EGLint contextAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE
        };
        eglContext_ = eglCreateContext(eglDisplay_, config, EGL_NO_CONTEXT, contextAttribs);
        if (eglContext_ == EGL_NO_CONTEXT) {
            LOGE("eglCreateContext failed: %d", eglGetError());
            return false;
        }
        
        // Make current
        if (!eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_)) {
            LOGE("eglMakeCurrent failed: %d", eglGetError());
            return false;
        }
        
        LOGD("EGL context created successfully");
        return true;
    }
    
    void destroyContext() {
        if (eglDisplay_ != EGL_NO_DISPLAY) {
            eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (eglContext_ != EGL_NO_CONTEXT) {
                eglDestroyContext(eglDisplay_, eglContext_);
                eglContext_ = EGL_NO_CONTEXT;
            }
            if (eglSurface_ != EGL_NO_SURFACE) {
                eglDestroySurface(eglDisplay_, eglSurface_);
                eglSurface_ = EGL_NO_SURFACE;
            }
            eglTerminate(eglDisplay_);
            eglDisplay_ = EGL_NO_DISPLAY;
        }
    }
    
    // -------------------------------------------------------------------------
    // Shader Compilation
    // -------------------------------------------------------------------------
    bool createShaders() {
        // Compile compute shader
        GLuint computeShader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(computeShader, 1, &kComputeShaderSource, nullptr);
        glCompileShader(computeShader);
        
        GLint compiled = 0;
        glGetShaderiv(computeShader, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            GLint logLen = 0;
            glGetShaderiv(computeShader, GL_INFO_LOG_LENGTH, &logLen);
            std::vector<char> log(logLen);
            glGetShaderInfoLog(computeShader, logLen, nullptr, log.data());
            LOGE("Compute shader compilation failed:\n%s", log.data());
            glDeleteShader(computeShader);
            return false;
        }
        
        // Create program
        computeProgram_ = glCreateProgram();
        glAttachShader(computeProgram_, computeShader);
        glLinkProgram(computeProgram_);
        
        GLint linked = 0;
        glGetProgramiv(computeProgram_, GL_LINK_STATUS, &linked);
        if (!linked) {
            GLint logLen = 0;
            glGetProgramiv(computeProgram_, GL_INFO_LOG_LENGTH, &logLen);
            std::vector<char> log(logLen);
            glGetProgramInfoLog(computeProgram_, logLen, nullptr, log.data());
            LOGE("Program linking failed:\n%s", log.data());
            glDeleteProgram(computeProgram_);
            computeProgram_ = 0;
            return false;
        }
        
        glDeleteShader(computeShader);
        LOGD("Compute shader program created: %u", computeProgram_);
        return true;
    }
    
    // -------------------------------------------------------------------------
    // Texture Creation
    // -------------------------------------------------------------------------
    bool createTextures() {
        glGenTextures(1, &srcTexture_);
        glGenTextures(1, &depthTexture_);
        glGenTextures(1, &confidenceTexture_);
        glGenTextures(1, &dstTexture_);
        
        // Source texture (RGBA8)
        glBindTexture(GL_TEXTURE_2D, srcTexture_);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width_, height_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        
        // Depth texture (R32F)
        glBindTexture(GL_TEXTURE_2D, depthTexture_);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F, width_, height_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        
        // Confidence texture (R8UI)
        glBindTexture(GL_TEXTURE_2D, confidenceTexture_);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8UI, width_, height_, 0, GL_RED_INTEGER, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        
        // Destination texture (RGBA8)
        glBindTexture(GL_TEXTURE_2D, dstTexture_);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width_, height_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        
        glBindTexture(GL_TEXTURE_2D, 0);
        
        GLint maxImageUnits = 0;
        glGetIntegerv(GL_MAX_IMAGE_UNITS, &maxImageUnits);
        LOGD("textures src=%u depth=%u dst=%u maxImageUnits=%d",
             srcTexture_, depthTexture_, dstTexture_, maxImageUnits);
        checkGLError("createTextures");
        return true;
    }
    
    void updateTexture(GLuint texture, const void* data, GLenum format, GLenum type) {
        glBindTexture(GL_TEXTURE_2D, texture);
        if (format == GL_RGBA) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width_, height_, format, type, data);
        } else if (format == GL_RED) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width_, height_, format, type, data);
        } else if (format == GL_RED_INTEGER) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width_, height_, format, type, data);
        }
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    
    // -------------------------------------------------------------------------
    // Uniform Buffer
    // -------------------------------------------------------------------------
    bool createUniformBuffer() {
        glGenBuffers(1, &uniformBuffer_);
        glBindBuffer(GL_UNIFORM_BUFFER, uniformBuffer_);
        glBufferData(GL_UNIFORM_BUFFER, sizeof(UniformParams), nullptr, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, uniformBuffer_);

        // Color profile uniform buffer (binding = 1)
        glGenBuffers(1, &colorProfileBuffer_);
        glBindBuffer(GL_UNIFORM_BUFFER, colorProfileBuffer_);
        glBufferData(GL_UNIFORM_BUFFER, sizeof(ColorProfileUniforms), nullptr, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_UNIFORM_BUFFER, 1, colorProfileBuffer_);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);

        return true;
    }

    // Packed uniform structure (must match GLSL std140 layout)
    struct UniformParams {
        float fNumber;
        float focalLength;
        float focusRect[4];
        int bokehStyle;
        float zoomRate;
        int cameraRole;
        int isAutoFNumber;   // bool as int
        int isStaticScene;   // bool as int
        float motionSpeed;
        int bypass;          // bool as int
        float padding[3];    // std140 alignment
    };

    // Color profile uniforms (binding = 1)
    struct ColorProfileUniforms {
        float contrast;
        float saturation;
        float highlights;
        float shadows;
        float colorTemp;
        float tint;
        float padding[2];
    };

    void updateUniforms(const OriginalParams& params) {
        UniformParams ubo{};
        ubo.fNumber = params.fNumber;
        ubo.focalLength = params.focalLength;
        ubo.focusRect[0] = params.focusRect[0];
        ubo.focusRect[1] = params.focusRect[1];
        ubo.focusRect[2] = params.focusRect[2];
        ubo.focusRect[3] = params.focusRect[3];
        ubo.bokehStyle = params.bokehStyle;
        ubo.zoomRate = params.zoomRate;
        ubo.cameraRole = params.cameraRole;
        ubo.isAutoFNumber = params.isAutoFNumber ? 1 : 0;
        ubo.isStaticScene = params.isStaticScene ? 1 : 0;
        ubo.motionSpeed = params.motionSpeed;
        ubo.bypass = params.bypass ? 1 : 0;

        glBindBuffer(GL_UNIFORM_BUFFER, uniformBuffer_);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, sizeof(UniformParams), &ubo);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);

        // Update color profile uniforms
        ColorProfileUniforms cp{};
        cp.contrast = params.contrast;
        cp.saturation = params.saturation;
        cp.highlights = params.highlights;
        cp.shadows = params.shadows;
        cp.colorTemp = params.colorTemp;
        cp.tint = params.tint;
        cp.padding[0] = 0.0f;
        cp.padding[1] = 0.0f;

        glBindBuffer(GL_UNIFORM_BUFFER, colorProfileBuffer_);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, sizeof(ColorProfileUniforms), &cp);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
    }
    
    // -------------------------------------------------------------------------
    // Compute Dispatch
    // -------------------------------------------------------------------------
    void dispatchCompute() {
        while (glGetError() != GL_NO_ERROR) {
        }
        glUseProgram(computeProgram_);
        checkGLError("useProgram");
        
        // Bind textures to image units
        glBindImageTexture(0, srcTexture_, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA8);
        checkGLError("bind srcImage");
        glBindImageTexture(1, depthTexture_, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
        checkGLError("bind depthMap");
        glBindImageTexture(3, dstTexture_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
        checkGLError("bind dstImage");
        
        // Dispatch work groups (16x16 local size)
        int numGroupsX = (width_ + 15) / 16;
        int numGroupsY = (height_ + 15) / 16;
        glDispatchCompute(numGroupsX, numGroupsY, 1);
        checkGLError("dispatch");
        
        // Memory barrier for image writes
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        checkGLError("barrier");
    }
    
    void readbackTexture(GLuint texture, uint8_t* dst) {
        // Bind texture to framebuffer for reading
        GLuint fbo;
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        
        // Check framebuffer completeness
        GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            LOGE("Framebuffer incomplete: %x", status);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(1, &fbo);
            return;
        }
        
        // Read pixels directly (synchronous but works on all ES 3.1)
        glReadPixels(0, 0, width_, height_, GL_RGBA, GL_UNSIGNED_BYTE, dst);
        
        checkGLError("glReadPixels");
        
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, &fbo);
    }
    
    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------
    void cleanup() {
        if (computeProgram_) {
            glDeleteProgram(computeProgram_);
            computeProgram_ = 0;
        }
        if (srcTexture_) {
            glDeleteTextures(1, &srcTexture_);
            srcTexture_ = 0;
        }
        if (depthTexture_) {
            glDeleteTextures(1, &depthTexture_);
            depthTexture_ = 0;
        }
        if (confidenceTexture_) {
            glDeleteTextures(1, &confidenceTexture_);
            confidenceTexture_ = 0;
        }
        if (dstTexture_) {
            glDeleteTextures(1, &dstTexture_);
            dstTexture_ = 0;
        }
        if (uniformBuffer_) {
            glDeleteBuffers(1, &uniformBuffer_);
            uniformBuffer_ = 0;
        }
        if (colorProfileBuffer_) {
            glDeleteBuffers(1, &colorProfileBuffer_);
            colorProfileBuffer_ = 0;
        }
        destroyContext();
        initialized_ = false;
    }
};

// -----------------------------------------------------------------------------
// BokehRenderer Wrapper Implementation
// -----------------------------------------------------------------------------
BokehRenderer::BokehRenderer() : impl_(std::make_unique<BokehRendererImpl>()) {}

BokehRenderer::~BokehRenderer() = default;

BokehRenderer::BokehRenderer(BokehRenderer&& other) noexcept 
    : impl_(std::move(other.impl_)), initialized_(other.initialized_), 
      width_(other.width_), height_(other.height_) {
    other.initialized_ = false;
}

BokehRenderer& BokehRenderer::operator=(BokehRenderer&& other) noexcept {
    if (this != &other) {
        impl_ = std::move(other.impl_);
        initialized_ = other.initialized_;
        width_ = other.width_;
        height_ = other.height_;
        other.initialized_ = false;
    }
    return *this;
}

bool BokehRenderer::initialize(int width, int height, const RendererConfig& config) {
    if (impl_->initialize(width, height, config)) {
        initialized_ = true;
        width_ = width;
        height_ = height;
        return true;
    }
    return false;
}

bool BokehRenderer::render(const uint8_t* srcRGBA,
                           const float* depthMap,
                           const uint8_t* confidenceMap,
                           const OriginalParams& params,
                           uint8_t* dstRGBA) {
    if (!initialized_) return false;
    
    OriginalParams mutableParams = params;
    mutableParams.computeFocalLength();
    
    return impl_->render(srcRGBA, depthMap, confidenceMap, mutableParams, dstRGBA);
}

bool BokehRenderer::renderBitmaps(JNIEnv* env,
                                  jobject srcBitmap,
                                  jobject depthBitmap,
                                  jobject confidenceBitmap,
                                  const OriginalParams& params,
                                  jobject dstBitmap) {
    if (!initialized_) return false;
    
    // Lock source bitmap
    AndroidBitmapInfo srcInfo;
    void* srcPixels = nullptr;
    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0) return false;
    if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0) return false;
    
    // Lock depth bitmap
    AndroidBitmapInfo depthInfo;
    void* depthPixels = nullptr;
    if (AndroidBitmap_getInfo(env, depthBitmap, &depthInfo) < 0) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return false;
    }
    if (AndroidBitmap_lockPixels(env, depthBitmap, &depthPixels) < 0) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return false;
    }
    
    // Lock confidence bitmap (optional)
    uint8_t* confidencePixels = nullptr;
    if (confidenceBitmap != nullptr) {
        AndroidBitmapInfo confInfo;
        if (AndroidBitmap_getInfo(env, confidenceBitmap, &confInfo) >= 0) {
            AndroidBitmap_lockPixels(env, confidenceBitmap, reinterpret_cast<void**>(&confidencePixels));
        }
    }
    
    // Lock destination bitmap
    AndroidBitmapInfo dstInfo;
    void* dstPixels = nullptr;
    if (AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) < 0) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        AndroidBitmap_unlockPixels(env, depthBitmap);
        if (confidencePixels) AndroidBitmap_unlockPixels(env, confidenceBitmap);
        return false;
    }
    if (dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        AndroidBitmap_unlockPixels(env, depthBitmap);
        if (confidencePixels) AndroidBitmap_unlockPixels(env, confidenceBitmap);
        return false;
    }
    if (AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) < 0) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        AndroidBitmap_unlockPixels(env, depthBitmap);
        if (confidencePixels) AndroidBitmap_unlockPixels(env, confidenceBitmap);
        return false;
    }
    
    // Render
    OriginalParams mutableParams = params;
    mutableParams.computeFocalLength();
    
    bool success = impl_->render(
        static_cast<const uint8_t*>(srcPixels),
        static_cast<const float*>(depthPixels),
        confidencePixels,
        mutableParams,
        static_cast<uint8_t*>(dstPixels)
    );
    
    // Unlock all
    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, depthBitmap);
    if (confidencePixels) AndroidBitmap_unlockPixels(env, confidenceBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
    
    return success;
}

// -----------------------------------------------------------------------------
// JNI Helper Functions
// -----------------------------------------------------------------------------
OriginalParams jniParamsToCpp(JNIEnv* env, jobject javaParams) {
    OriginalParams params{};
    if (!javaParams) return params;
    
    jclass cls = env->GetObjectClass(javaParams);
    
    // Get field IDs
    jfieldID fNumberId = env->GetFieldID(cls, "fNumber", "F");
    jfieldID focalLengthId = env->GetFieldID(cls, "focalLength", "F");
    jfieldID focusRectId = env->GetFieldID(cls, "focusRect", "[F");
    jfieldID bokehStyleId = env->GetFieldID(cls, "bokehStyle", "I");
    jfieldID zoomRateId = env->GetFieldID(cls, "zoomRate", "F");
    jfieldID cameraRoleId = env->GetFieldID(cls, "cameraRole", "I");
    jfieldID isAutoFNumberId = env->GetFieldID(cls, "isAutoFNumber", "Z");
    jfieldID isStaticSceneId = env->GetFieldID(cls, "isStaticScene", "Z");
    jfieldID motionSpeedId = env->GetFieldID(cls, "motionSpeed", "F");
    jfieldID bypassId = env->GetFieldID(cls, "bypass", "Z");
    
    if (fNumberId) params.fNumber = env->GetFloatField(javaParams, fNumberId);
    if (focalLengthId) params.focalLength = env->GetFloatField(javaParams, focalLengthId);
    if (bokehStyleId) params.bokehStyle = env->GetIntField(javaParams, bokehStyleId);
    if (zoomRateId) params.zoomRate = env->GetFloatField(javaParams, zoomRateId);
    if (cameraRoleId) params.cameraRole = env->GetIntField(javaParams, cameraRoleId);
    if (isAutoFNumberId) params.isAutoFNumber = env->GetBooleanField(javaParams, isAutoFNumberId);
    if (isStaticSceneId) params.isStaticScene = env->GetBooleanField(javaParams, isStaticSceneId);
    if (motionSpeedId) params.motionSpeed = env->GetFloatField(javaParams, motionSpeedId);
    if (bypassId) params.bypass = env->GetBooleanField(javaParams, bypassId);
    
    // focusRect is float[4]
    if (focusRectId) {
        jfloatArray arr = static_cast<jfloatArray>(env->GetObjectField(javaParams, focusRectId));
        if (arr) {
            jfloat* elements = env->GetFloatArrayElements(arr, nullptr);
            if (elements) {
                for (int i = 0; i < 4; i++) params.focusRect[i] = elements[i];
                env->ReleaseFloatArrayElements(arr, elements, JNI_ABORT);
            }
        }
    }
    
    params.computeFocalLength();
    return params;
}

bool lockBitmapPixels(JNIEnv* env, jobject bitmap, void** pixels, int* width, int* height) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return false;
    if (AndroidBitmap_lockPixels(env, bitmap, pixels) < 0) return false;
    if (width) *width = info.width;
    if (height) *height = info.height;
    return true;
}

void unlockBitmapPixels(JNIEnv* env, jobject bitmap) {
    AndroidBitmap_unlockPixels(env, bitmap);
}

void checkGLError(const char* operation) {
    GLint error = glGetError();
    if (error != GL_NO_ERROR) {
        LOGE("GL Error after %s: 0x%04x", operation, error);
    }
}


// -----------------------------------------------------------------------------
// JNI entry points
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_io_martin_hypercameramod_originalportrait_BokehRenderer_nativeCreate(
        JNIEnv*, jclass) {
    auto* renderer = new BokehRenderer();
    return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_io_martin_hypercameramod_originalportrait_BokehRenderer_nativeDestroy(
        JNIEnv*, jclass, jlong ptr) {
    delete reinterpret_cast<BokehRenderer*>(ptr);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_martin_hypercameramod_originalportrait_BokehRenderer_nativeInitialize(
        JNIEnv*, jclass, jlong ptr, jint width, jint height, jint qualityOrdinal) {
    auto* renderer = reinterpret_cast<BokehRenderer*>(ptr);
    if (renderer == nullptr) {
        return JNI_FALSE;
    }
    RendererConfig config;
    config.quality = static_cast<QualityPreset>(qualityOrdinal);
    return renderer->initialize(width, height, config) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_martin_hypercameramod_originalportrait_BokehRenderer_nativeRender(
        JNIEnv* env, jclass, jlong ptr,
        jobject srcRGBA, jobject depthMap, jobject confidenceMap,
        jobject params, jobject dstRGBA) {
    auto* renderer = reinterpret_cast<BokehRenderer*>(ptr);
    if (renderer == nullptr || srcRGBA == nullptr || depthMap == nullptr
            || dstRGBA == nullptr) {
        return JNI_FALSE;
    }
    auto* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcRGBA));
    auto* depth = static_cast<float*>(env->GetDirectBufferAddress(depthMap));
    auto* confidence = confidenceMap == nullptr ? nullptr
            : static_cast<uint8_t*>(env->GetDirectBufferAddress(confidenceMap));
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(dstRGBA));
    if (src == nullptr || depth == nullptr || dst == nullptr) {
        return JNI_FALSE;
    }
    OriginalParams cppParams = jniParamsToCpp(env, params);
    return renderer->render(src, depth, confidence, cppParams, dst)
            ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_martin_hypercameramod_originalportrait_BokehRenderer_nativeRenderBitmaps(
        JNIEnv* env, jclass, jlong ptr,
        jobject srcBitmap, jobject depthBitmap, jobject confidenceBitmap,
        jobject params, jobject dstBitmap) {
    auto* renderer = reinterpret_cast<BokehRenderer*>(ptr);
    if (renderer == nullptr || srcBitmap == nullptr || depthBitmap == nullptr
            || dstBitmap == nullptr) {
        return JNI_FALSE;
    }
    OriginalParams cppParams = jniParamsToCpp(env, params);
    return renderer->renderBitmaps(env, srcBitmap, depthBitmap,
            confidenceBitmap, cppParams, dstBitmap) ? JNI_TRUE : JNI_FALSE;
}

} // namespace original_portrait
} // namespace xiaomi