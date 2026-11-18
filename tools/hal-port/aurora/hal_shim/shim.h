#ifndef HYPERCAMERA_SHIM_H
#define HYPERCAMERA_SHIM_H

#include <cstdint>
#include <string>
#include <vector>
#include <map>

// Forward declarations for the MiaPluginModule interface (from donor)
// The donor libAncHumanPreviewBokeh.so exports get_api_impl which fills
// a caller-provided array of 10 function pointers.
typedef void* (*GetApiImplFn)(void* vtable, int struct_size, int major, int minor);

// Parameter tags (from libAncHumanPreviewBokeh.so RE)
enum PreviewBokehTag : uint32_t {
    TAG_F_NUMBER          = 0x10000,
    TAG_FOCUS_RECT        = 0x10001,
    TAG_BOKEH_STYLE       = 0x10002,
    TAG_AF_CODE           = 0x10003,
    TAG_ZOOM_RATE         = 0x10004,
    TAG_CAMERA_ROLE       = 0x10005,
    TAG_FOCAL_RANGE       = 0x10006,
    TAG_IS_AUTO_F_NUMBER  = 0x10007,
    TAG_AF_CODE_BYPASS    = 0x10008,
    TAG_IS_STATIC_SCENE   = 0x10009,
    TAG_MOTION_SPEED      = 0x1000A,
    TAG_BYPASS            = 0x1000B,
    TAG_BATCH_PARAMS      = 0x1000C,
};

// Camera role values
enum CameraRole : uint32_t {
    CAMERA_ROLE_MAIN      = 0,
    CAMERA_ROLE_TELE      = 1,
    CAMERA_ROLE_ULTRAWIDE = 2,
};

// Bokeh style values
enum BokehStyle : uint32_t {
    BOKEH_NATURAL   = 0,
    BOKEH_SMOOTH    = 1,
    BOKEH_SWIRLY    = 2,
    BOKEH_CATSEYE   = 3,
    BOKEH_POLYGON   = 4,
};

// Result codes
enum ShimResult : int32_t {
    SHIM_OK             = 0,
    SHIM_ERR_NOT_INIT   = -1,
    SHIM_ERR_NO_LIB     = -2,
    SHIM_ERR_NO_VTABLE  = -3,
    SHIM_ERR_UNSUPPORTED= -4,
};

// VTable entry type: each plugin function takes (void* ctx, ...args)
// The exact signatures are unknown; this is a placeholder shim.
struct MiaPluginVTable {
    void*   initialize;   // index 0
    void*   isEnabled;    // index 1
    void*   processRequest; // index 2
    void*   setParameter; // index 3
    void*   flushRequest; // index 4
    void*   getParameter; // index 5
    void*   destroy;      // index 6
    void*   unk7;         // index 7
    void*   unk8;         // index 8
    void*   unk9;         // index 9
};

// Shim session handle
struct ShimSession {
    bool            initialized = false;
    void*           lib_handle  = nullptr;
    GetApiImplFn    get_api_impl = nullptr;
    MiaPluginVTable vtable{};
    void*           plugin_ctx  = nullptr;
    uint8_t         legend_mode = 0;   // 0=off, 1=M9, 2=M3
    uint8_t         style_type  = 0;   // 171=Original Portrait
};

// Shim API (extern "C" for dlsym loading)
#ifdef __cplusplus
extern "C" {
#endif

// Initialize the shim: dlopen donor library, get vtable
ShimResult shim_init(ShimSession* session);

// Load libAncHumanPreviewBokeh.so and get its API
ShimResult shim_load_original_portrait(ShimSession* session);

// Set a session parameter (called from HAL when building CaptureRequest)
ShimResult shim_set_parameter(ShimSession* session, uint32_t tag,
                               const void* value, uint32_t value_size);

// Process a preview frame (Original Portrait real-time path)
ShimResult shim_process_frame(ShimSession* session,
                               const void* input_buf, void* output_buf,
                               uint32_t width, uint32_t height,
                               uint32_t stride);

// Flush pipeline (stream change)
ShimResult shim_flush(ShimSession* session);

// Destroy session
ShimResult shim_destroy(ShimSession* session);

// Query if Original Portrait is supported
bool shim_is_original_portrait_supported(void);

// Query if Legendary M3/M9 is supported
bool shim_is_legendary_supported(void);

#ifdef __cplusplus
}
#endif

#endif // HYPERCAMERA_SHIM_H