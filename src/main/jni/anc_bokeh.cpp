#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <dlfcn.h>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "AncBokehJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kApiSlots = 6;
constexpr int kApiSize = 0x30;
constexpr int kApiVersion = 3;

struct WsgImage {
    uint8_t b[0x48];
};

struct Engine {
    void* lib = nullptr;
    void* api[kApiSlots] = {};
    void* handle = nullptr;
    void* config = nullptr;
    size_t configSize = 0;
    std::string modelDir;
    std::mutex lock;
};

Engine gEngine;

typedef int (*GetApiFn)(void*, int, int, int, int);
typedef int (*CreateFn)(void**);
typedef int (*DestroyFn)(void*);
typedef int (*InitFn)(void*, void*);
typedef int (*ProcessFn)(void*, void*, void*, void*);

inline uint8_t clamp8(int v) {
    return (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v));
}

inline void argbToNv12(const uint8_t* argb, int width, int height, int stride,
                       uint8_t* yPlane, uint8_t* uvPlane) {
    for (int y = 0; y < height; ++y) {
        const uint8_t* row = argb + (size_t)y * stride;
        uint8_t* yRow = yPlane + (size_t)y * width;
        for (int x = 0; x < width; ++x) {
            int r = row[x * 4 + 0];
            int g = row[x * 4 + 1];
            int b = row[x * 4 + 2];
            yRow[x] = clamp8((77 * r + 150 * g + 29 * b + 128) >> 8);
        }
    }
    for (int y = 0; y < height; y += 2) {
        const uint8_t* row0 = argb + (size_t)y * stride;
        const uint8_t* row1 = argb + (size_t)(y + 1 < height ? y + 1 : y) * stride;
        uint8_t* uvRow = uvPlane + (size_t)(y / 2) * width;
        for (int x = 0; x < width; x += 2) {
            int r = 0, g = 0, b = 0;
            for (int dy = 0; dy < 2; ++dy) {
                const uint8_t* row = dy == 0 ? row0 : row1;
                for (int dx = 0; dx < 2; ++dx) {
                    int xx = x + dx < width ? x + dx : x;
                    r += row[xx * 4 + 0];
                    g += row[xx * 4 + 1];
                    b += row[xx * 4 + 2];
                }
            }
            r /= 4; g /= 4; b /= 4;
            int u = ((-43 * r - 85 * g + 128 * b + 128) >> 8) + 128;
            int v = ((128 * r - 107 * g - 21 * b + 128) >> 8) + 128;
            uvRow[x + 0] = clamp8(u);
            uvRow[x + 1] = clamp8(v);
        }
    }
}

inline void nv12ToArgb(const uint8_t* yPlane, const uint8_t* uvPlane, int width, int height,
                       uint8_t* argb, int stride) {
    for (int y = 0; y < height; ++y) {
        uint8_t* row = argb + (size_t)y * stride;
        const uint8_t* yRow = yPlane + (size_t)y * width;
        const uint8_t* uvRow = uvPlane + (size_t)(y / 2) * width;
        for (int x = 0; x < width; ++x) {
            int yy = yRow[x];
            int u = uvRow[(x & ~1) + 0] - 128;
            int v = uvRow[(x & ~1) + 1] - 128;
            int r = (int)lroundf(yy + 1.402f * v);
            int g = (int)lroundf(yy - 0.344136f * u - 0.714136f * v);
            int b = (int)lroundf(yy + 1.772f * u);
            row[x * 4 + 0] = clamp8(r);
            row[x * 4 + 1] = clamp8(g);
            row[x * 4 + 2] = clamp8(b);
            row[x * 4 + 3] = 255;
        }
    }
}

bool readFile(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) {
        LOGE("cannot open %s", path.c_str());
        return false;
    }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0) {
        fclose(f);
        LOGE("empty file %s", path.c_str());
        return false;
    }
    out.resize((size_t)size);
    size_t read = fread(out.data(), 1, out.size(), f);
    fclose(f);
    return read == out.size();
}

void releaseLocked() {
    if (gEngine.handle && gEngine.api[1]) {
        ((DestroyFn)gEngine.api[1])(&gEngine.handle);
    }
    gEngine.handle = nullptr;
    if (gEngine.lib) {
        dlclose(gEngine.lib);
        gEngine.lib = nullptr;
    }
    free(gEngine.config);
    gEngine.config = nullptr;
    gEngine.configSize = 0;
    memset(gEngine.api, 0, sizeof(gEngine.api));
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_io_martin_hypercameramod_originalportrait_AncBokehRenderer_nativeInit(
        JNIEnv* env, jclass, jstring libPath, jstring modelDir) {
    std::lock_guard<std::mutex> guard(gEngine.lock);
    releaseLocked();

    const char* libChars = env->GetStringUTFChars(libPath, nullptr);
    const char* dirChars = env->GetStringUTFChars(modelDir, nullptr);
    if (!libChars || !dirChars) {
        if (libChars) env->ReleaseStringUTFChars(libPath, libChars);
        if (dirChars) env->ReleaseStringUTFChars(modelDir, dirChars);
        return JNI_FALSE;
    }
    std::string lib(libChars);
    std::string dir(dirChars);
    env->ReleaseStringUTFChars(libPath, libChars);
    env->ReleaseStringUTFChars(modelDir, dirChars);

    gEngine.lib = dlopen(lib.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!gEngine.lib) {
        LOGE("dlopen %s failed: %s", lib.c_str(), dlerror());
        return JNI_FALSE;
    }
    GetApiFn getApi = (GetApiFn)dlsym(gEngine.lib, "sg_single_bokeh_get_api_impl");
    if (!getApi) {
        LOGE("dlsym get_api failed: %s", dlerror());
        releaseLocked();
        return JNI_FALSE;
    }
    memset(gEngine.api, 0, sizeof(gEngine.api));
    int r = getApi(gEngine.api, kApiSize, kApiVersion, 0, 0);
    if (r != 0) {
        LOGE("get_api returned %d", r);
        releaseLocked();
        return JNI_FALSE;
    }
    CreateFn create = (CreateFn)gEngine.api[0];
    InitFn init = (InitFn)gEngine.api[2];
    if (!create || !init) {
        LOGE("missing create/init slot");
        releaseLocked();
        return JNI_FALSE;
    }
    r = create(&gEngine.handle);
    if (r != 0 || !gEngine.handle) {
        LOGE("create returned %d handle=%p", r, gEngine.handle);
        releaseLocked();
        return JNI_FALSE;
    }

    std::vector<uint8_t> config;
    if (!readFile(dir + "/intsense_config_singlebokeh.bin", config)) {
        releaseLocked();
        return JNI_FALSE;
    }
    gEngine.config = malloc(config.size());
    if (!gEngine.config) {
        releaseLocked();
        return JNI_FALSE;
    }
    memcpy(gEngine.config, config.data(), config.size());
    gEngine.configSize = config.size();
    gEngine.modelDir = dir;

    uint8_t cfg[0x50];
    memset(cfg, 0, sizeof(cfg));
    *(const char**)(cfg + 0x00) = gEngine.modelDir.c_str();
    *(void**)(cfg + 0x18) = gEngine.config;
    *(uint64_t*)(cfg + 0x20) = (uint64_t)gEngine.configSize;
    *(void**)(cfg + 0x28) = gEngine.config;
    *(uint64_t*)(cfg + 0x30) = (uint64_t)gEngine.configSize;
    r = init(gEngine.handle, cfg);
    if (r != 0) {
        LOGE("init returned %d", r);
        releaseLocked();
        return JNI_FALSE;
    }
    LOGI("AI bokeh engine ready: %s (%zu byte config)", dir.c_str(), gEngine.configSize);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_martin_hypercameramod_originalportrait_AncBokehRenderer_nativeRelease(
        JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(gEngine.lock);
    releaseLocked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_martin_hypercameramod_originalportrait_AncBokehRenderer_nativeProcess(
        JNIEnv* env, jclass, jobject srcBitmap, jobject dstBitmap,
        jint fx1, jint fy1, jint fx2, jint fy2, jfloat fNumber) {
    std::lock_guard<std::mutex> guard(gEngine.lock);
    if (!gEngine.handle) {
        LOGE("process without engine");
        return JNI_FALSE;
    }
    ProcessFn process = (ProcessFn)gEngine.api[3];
    if (!process) {
        LOGE("missing process slot");
        return JNI_FALSE;
    }

    AndroidBitmapInfo srcInfo{};
    AndroidBitmapInfo dstInfo{};
    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("bitmap info failed");
        return JNI_FALSE;
    }
    if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("bitmap format is not RGBA_8888");
        return JNI_FALSE;
    }
    const int width = (int)srcInfo.width;
    const int height = (int)srcInfo.height;
    if ((int)dstInfo.width != width || (int)dstInfo.height != height) {
        LOGE("bitmap size mismatch");
        return JNI_FALSE;
    }

    void* srcPixels = nullptr;
    void* dstPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("lock pixels failed");
        if (srcPixels) AndroidBitmap_unlockPixels(env, srcBitmap);
        return JNI_FALSE;
    }

    const size_t ySize = (size_t)width * height;
    const size_t uvSize = ySize / 2;
    uint8_t* srcY = nullptr;
    uint8_t* srcUv = nullptr;
    uint8_t* dstY = nullptr;
    uint8_t* dstUv = nullptr;
    posix_memalign((void**)&srcY, 64, ySize);
    posix_memalign((void**)&srcUv, 64, uvSize);
    posix_memalign((void**)&dstY, 64, ySize);
    posix_memalign((void**)&dstUv, 64, uvSize);
    if (!srcY || !srcUv || !dstY || !dstUv) {
        LOGE("alloc failed");
        free(srcY); free(srcUv); free(dstY); free(dstUv);
        AndroidBitmap_unlockPixels(env, srcBitmap);
        AndroidBitmap_unlockPixels(env, dstBitmap);
        return JNI_FALSE;
    }
    memset(dstY, 0, ySize);
    memset(dstUv, 128, uvSize);
    argbToNv12((const uint8_t*)srcPixels, width, height, (int)srcInfo.stride, srcY, srcUv);

    int x1 = fx1, y1 = fy1, x2 = fx2, y2 = fy2;
    if (x1 > x2) std::swap(x1, x2);
    if (y1 > y2) std::swap(y1, y2);
    x1 = std::max(0, std::min(width - 1, x1));
    x2 = std::max(1, std::min(width, x2));
    y1 = std::max(0, std::min(height - 1, y1));
    y2 = std::max(1, std::min(height, y2));
    if (x2 <= x1 || y2 <= y1) {
        x1 = width / 4; x2 = width * 3 / 4;
        y1 = height / 4; y2 = height * 3 / 4;
    }
    int face[4] = {x1, y1, x2, y2};

    WsgImage in;
    WsgImage out;
    memset(&in, 0, sizeof(in));
    memset(&out, 0, sizeof(out));
    *(int*)(in.b + 0x00) = 0;
    *(int*)(in.b + 0x04) = 0;
    *(int*)(in.b + 0x08) = width;
    *(int*)(in.b + 0x0c) = height;
    *(void**)(in.b + 0x10) = srcY;
    *(void**)(in.b + 0x18) = srcUv;
    *(int*)(in.b + 0x30) = width;
    *(int*)(in.b + 0x34) = height;
    *(int*)(in.b + 0x40) = height;

    *(int*)(out.b + 0x00) = 0;
    *(int*)(out.b + 0x04) = 0;
    *(int*)(out.b + 0x08) = width;
    *(int*)(out.b + 0x0c) = height;
    *(void**)(out.b + 0x10) = dstY;
    *(void**)(out.b + 0x18) = dstUv;
    *(int*)(out.b + 0x30) = width;
    *(int*)(out.b + 0x34) = height;
    *(int*)(out.b + 0x40) = height;

    uint8_t params[0x100];
    memset(params, 0, sizeof(params));
    *(float*)(params + 0x00) = fNumber;
    for (int off = 0x04; off <= 0x1c; off += 4) {
        *(int*)(params + off) = 1;
    }
    *(void**)(params + 0x10) = face;

    int r = process(gEngine.handle, &in, params, &out);
    if (r == 0) {
        nv12ToArgb(dstY, dstUv, width, height, (uint8_t*)dstPixels, (int)dstInfo.stride);
    } else {
        LOGE("process returned %d", r);
    }

    free(srcY); free(srcUv); free(dstY); free(dstUv);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
    return r == 0 ? JNI_TRUE : JNI_FALSE;
}
