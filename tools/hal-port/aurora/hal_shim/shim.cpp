#include "shim.h"

#include <dlfcn.h>
#include <android/log.h>
#include <cstring>
#include <cstdio>

#define TAG "HyperCameraShim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static const char* kAncHumanLib = "/odm/lib64/libAncHumanPreviewBokeh.so";

ShimResult shim_init(ShimSession* session) {
    if (!session) return SHIM_ERR_NOT_INIT;

    session->lib_handle = dlopen(kAncHumanLib, RTLD_NOW | RTLD_LOCAL);
    if (!session->lib_handle) {
        LOGE("dlopen %s failed: %s", kAncHumanLib, dlerror());
        return SHIM_ERR_NO_LIB;
    }
    LOGI("loaded %s", kAncHumanLib);

    session->get_api_impl = reinterpret_cast<GetApiImplFn>(
        dlsym(session->lib_handle, "_anc_human_effects_preview_bokeh_get_api_impl"));
    if (!session->get_api_impl) {
        LOGE("get_api_impl not found in %s", kAncHumanLib);
        return SHIM_ERR_NO_VTABLE;
    }

    session->initialized = true;
    LOGI("shim initialized");
    return SHIM_OK;
}

ShimResult shim_load_original_portrait(ShimSession* session) {
    if (!session || !session->initialized) return SHIM_ERR_NOT_INIT;

    // Build the vtable (10 entries, 0x50 bytes)
    uint64_t vtable_buf[10] = {0};
    session->get_api_impl(vtable_buf, 0x50, 2, 4);  // struct_size=0x50, major=2, minor=4

    session->vtable.initialize    = reinterpret_cast<void*>(vtable_buf[0]);
    session->vtable.isEnabled     = reinterpret_cast<void*>(vtable_buf[1]);
    session->vtable.processRequest = reinterpret_cast<void*>(vtable_buf[2]);
    session->vtable.setParameter   = reinterpret_cast<void*>(vtable_buf[3]);
    session->vtable.flushRequest   = reinterpret_cast<void*>(vtable_buf[4]);
    session->vtable.getParameter   = reinterpret_cast<void*>(vtable_buf[5]);
    session->vtable.destroy        = reinterpret_cast<void*>(vtable_buf[6]);

    if (!session->vtable.initialize || !session->vtable.processRequest) {
        LOGE("incomplete vtable from %s", kAncHumanLib);
        return SHIM_ERR_NO_VTABLE;
    }

    LOGI("Original Portrait vtable loaded");
    return SHIM_OK;
}

ShimResult shim_set_parameter(ShimSession* session, uint32_t tag,
                               const void* value, uint32_t value_size) {
    if (!session || !session->vtable.setParameter) return SHIM_ERR_NOT_INIT;

    // The donor setParameter signature is unknown; this is a placeholder.
    // The real call would be:
    //   setParameter(plugin_ctx, tag, value, value_size, flags)
    // where flags bit 0 = set (1) vs get (0).
    //
    // For now we store the tag/value mapping and apply it in process_frame.
    LOGW("shim_set_parameter: tag=0x%x not yet wired to plugin ctx", tag);
    return SHIM_OK;
}

ShimResult shim_process_frame(ShimSession* session,
                               const void* input_buf, void* output_buf,
                               uint32_t width, uint32_t height,
                               uint32_t stride) {
    if (!session || !session->vtable.processRequest) return SHIM_ERR_NOT_INIT;

    // The donor processRequest signature is unknown:
    //   processRequest(plugin_ctx, input_buf, output_buf, width, height, ...)
    //
    // This shim cannot safely call it without the exact signature.
    // Return UNSUPPORTED to signal the HAL should fall back to software.
    LOGE("shim_process_frame: plugin signature unknown; cannot safely invoke");
    return SHIM_ERR_UNSUPPORTED;
}

ShimResult shim_flush(ShimSession* session) {
    if (!session || !session->vtable.flushRequest) return SHIM_ERR_NOT_INIT;
    LOGW("shim_flush: not yet wired to plugin");
    return SHIM_OK;
}

ShimResult shim_destroy(ShimSession* session) {
    if (!session) return SHIM_OK;
    if (session->vtable.destroy && session->plugin_ctx) {
        // session->vtable.destroy(session->plugin_ctx);
    }
    if (session->lib_handle) {
        dlclose(session->lib_handle);
        session->lib_handle = nullptr;
    }
    session->initialized = false;
    return SHIM_OK;
}

bool shim_is_original_portrait_supported(void) {
    // Check if the donor library exists on this device
    FILE* f = fopen(kAncHumanLib, "r");
    if (f) { fclose(f); return true; }
    return false;
}

bool shim_is_legendary_supported(void) {
    // Check if mileicafilter plugin exists
    FILE* f = fopen("/odm/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so", "r");
    if (f) { fclose(f); return true; }
    return false;
}