/* Runtime ABI bridge for the donor plugin object.
 *
 * The Nezha/Madrid plugin classes are built against a newer mivifwk whose
 * virtual table order differs from Aurora's:
 *
 *   Aurora FilterPlugin          Nezha MiLeicaFilterPlugin
 *   -------------------          -------------------------
 *   0  initialize                0  initialize
 *   1  (none)                    1  preProcess(PreProcessInfo)
 *   2  processRequest(PRI*)      2  ...
 *   3  processRequest(PRIV2*)    3  ...
 *   4  (none)                    4  ...
 *   5  (none)                    5  processRequest(PRI*)
 *   6  flushRequest              6  processRequest(PRIV2*)
 *   7  destroy                   7  ...
 *   8  isEnabled(MiaParams)      8  getCustomFrameOrder(shared_ptr)
 *   9  (none)                    9  ...
 *  10  ~D1                      10  flushRequest
 *  11  ~D0                      11  destroy
 *                                12  isEnabled(MiaParams)
 *                                16  ~D1
 *                                17  ~D0
 *
 * The engine resolves several of these by raw vptr offset, so it calls the
 * wrong function (e.g. slot +0x40 = isEnabled in Aurora is
 * getCustomFrameOrder in Nezha, which crashes storing a vector through the
 * sret register the engine never set).
 *
 * We rebuild the plugin's vtable in place, in Aurora's order, after the module
 * is mapped but before the provider calls createPlugin(). The vtable lives in
 * .data.rel.ro; mprotect() re-enables writes after RELRO.
 */

typedef unsigned long u64;
typedef unsigned int u32;

struct dl_phdr_info {
    u64 dlpi_addr;
    const char *dlpi_name;
    const void *dlpi_phdr;
    unsigned short dlpi_phnum;
};

extern int dl_iterate_phdr(
    int (*callback)(struct dl_phdr_info *, unsigned long, void *), void *data);
extern int mprotect(void *addr, unsigned long len, int prot);
extern int __android_log_print(int prio, const char *tag, const char *fmt, ...);
extern int __system_property_get(const char *name, char *value);
extern void *malloc(unsigned long size);
extern void *_Znwm(unsigned long size) __asm__("_Znwm");

#define PROT_READ 1
#define PROT_WRITE 2

/* Link-time addresses inside com.xiaomi.plugin.mileicafilter.so. */
#define PLUGIN_VTABLE 0x208a8UL

#define FN_INITIALIZE 0xc620UL
#define FN_PREPROCESS 0xd990UL
#define FN_PROCESS_REQUEST 0xdafcUL
#define FN_PROCESS_REQUEST_V2 0x10f28UL
#define FN_FLUSH 0x11094UL
#define FN_DESTROY 0x11200UL
#define FN_IS_ENABLED 0xd714UL
#define FN_DTOR_D1 0xc4f0UL
#define FN_DTOR_D0 0xc598UL
#define FN_NOOP 0x11eecUL

static u64 g_plugin_base;

/* libc++ long-string layout on this platform is {cap, size, data}; the least
 * significant bit of cap is set for the long representation. */
typedef struct {
    unsigned long cap;
    unsigned long size;
    void *data;
} cxx_string;

/* The engine resolves a node by its NodeName and then looks the name up in the
 * plugin registry. To take over the existing filter node we must register the
 * donor under "com.xiaomi.plugin.filter" instead of its own name. */
static const char kFilterName[] = "com.xiaomi.plugin.filter";

cxx_string shim_provider_name(void *self) {
    (void)self;
    /* The caller takes ownership of the returned string and frees it, so this
     * must allocate a fresh buffer on every call. */
    unsigned long n = sizeof(kFilterName);
    char *p = (char *)_Znwm(32);
    for (unsigned long i = 0; i < n; ++i) {
        p[i] = kFilterName[i];
    }
    cxx_string s;
    s.cap = 32 | 1;
    s.size = n - 1;
    s.data = p;
    return s;
}
static u64 g_fn_is_enabled;
static u64 g_fn_process_request;
static u64 g_fn_init;
static u64 g_fn_v2;
static u64 g_fn_flush;
static u64 g_fn_preprocess;

int shim_is_enabled(void *self, void *params) {
    int (*fn)(void *, void *) = (void *)g_fn_is_enabled;
    int r = fn(self, params);

    unsigned char *obj = (unsigned char *)self;
    char value[8];
    int forced = 0;
    if (__system_property_get("persist.vendor.camera.hypercam.style", value) > 0) {
        forced = value[0] - '0';
    }
    if (forced == 1 || forced == 2) {
        obj[0x25] = (unsigned char)forced;
        obj[0x26] = (unsigned char)forced;
        r = 1;
    } else if (obj[0x26] && !obj[0x25]) {
        /* The port routes both legacy keys (legendMode, stylizationType) to
         * stylizationType; mirror it so initResource sees the same value. */
        obj[0x25] = obj[0x26];
    }
    __android_log_print(4, "HyperCamVt",
                        "isEnabled r=%d style=%d/%d forced=%d", r, obj[0x25],
                        obj[0x26], forced);
    return r;
}

int shim_init(void *self, void *create, void *iface) {
    __android_log_print(4, "HyperCamVt", "initialize");
    int (*fn)(void *, void *, void *) = (void *)g_fn_init;
    int r = fn(self, create, iface);
    __android_log_print(4, "HyperCamVt", "initialize -> %d", r);
    return r;
}

int shim_slot1(void *self, void *info) {
    __android_log_print(4, "HyperCamVt", "preProcess self=%p info=%p", self, info);
    int (*fn)(void *, void *) = (void *)g_fn_preprocess;
    int r = fn(self, info);
    __android_log_print(4, "HyperCamVt", "preProcess -> %d", r);
    return r;
}

int shim_slot5(void *self) {
    __android_log_print(4, "HyperCamVt", "slot5(postProcess)");
    return 0;
}

int shim_v2(void *self, void *info) {
    __android_log_print(4, "HyperCamVt", "processRequestV2");
    int (*fn)(void *, void *) = (void *)g_fn_v2;
    return fn(self, info);
}

int shim_flush(void *self, void *info) {
    __android_log_print(4, "HyperCamVt", "flushRequest");
    int (*fn)(void *, void *) = (void *)g_fn_flush;
    return fn(self, info);
}

int shim_process_request(void *self, void *info) {
    int (*fn)(void *, void *) = (void *)g_fn_process_request;
    int r = fn(self, info);
    __android_log_print(4, "HyperCamVt", "processRequest -> %d", r);
    return r;
}

static int find_plugin(struct dl_phdr_info *info, unsigned long size, void *data) {
    (void)size;
    (void)data;
    const char *name = info->dlpi_name;
    if (!name || !name[0]) {
        return 0;
    }
    static const char *needles[] = {
        "com.xiaomi.plugin.mileicafilter.so",
        "com.xiaomi.plugin.filter.so",
    };
    for (unsigned n = 0; n < 2; ++n) {
        const char *needle = needles[n];
        for (const char *p = name; *p; ++p) {
            const char *a = p;
            const char *b = needle;
            while (*a && *b && *a == *b) {
                ++a;
                ++b;
            }
            if (!*b) {
                g_plugin_base = info->dlpi_addr;
                return 1;
            }
        }
    }
    return 0;
}

void shim_apply_vtable_patch(void) {
    dl_iterate_phdr(find_plugin, 0);
    __android_log_print(4, "HyperCamVt", "plugin base=%p", (void *)g_plugin_base);
    if (!g_plugin_base) {
        return;
    }

    u64 vtable = g_plugin_base + PLUGIN_VTABLE;
    u64 page = vtable & ~0xfffUL;

    if (mprotect((void *)page, 0x1000, PROT_READ | PROT_WRITE) != 0) {
        __android_log_print(4, "HyperCamVt", "mprotect failed");
        return;
    }

    /* slots[] is the object's view: slot 0 is the first virtual function. */
    u64 *slots = (u64 *)(vtable + 0x10);

    slots[0] = g_plugin_base + FN_INITIALIZE;
    slots[1] = g_plugin_base + FN_PREPROCESS;
    g_fn_is_enabled = g_plugin_base + FN_IS_ENABLED;
    g_fn_process_request = g_plugin_base + FN_PROCESS_REQUEST;
    g_fn_init = g_plugin_base + FN_INITIALIZE;
    g_fn_v2 = g_plugin_base + FN_PROCESS_REQUEST_V2;
    g_fn_flush = g_plugin_base + FN_FLUSH;
    g_fn_preprocess = g_plugin_base + FN_PREPROCESS;
    slots[0] = (u64)&shim_init;
    slots[1] = (u64)&shim_slot1;
    slots[2] = (u64)&shim_process_request;
    slots[3] = (u64)&shim_v2;
    slots[5] = (u64)&shim_slot5;
    slots[6] = (u64)&shim_flush;
    slots[3] = g_plugin_base + FN_PROCESS_REQUEST_V2;
    slots[5] = g_plugin_base + FN_NOOP;
    slots[6] = g_plugin_base + FN_FLUSH;
    slots[7] = g_plugin_base + FN_DESTROY;
    slots[8] = (u64)&shim_is_enabled;
    slots[10] = g_plugin_base + FN_DTOR_D1;
    slots[11] = g_plugin_base + FN_DTOR_D0;

#ifndef SHIM_KEEP_PLUGIN_NAME
    /* Provider vtable: slot 5 is getPluginName(). Register as the stock filter
     * so the existing "com.xiaomi.plugin.filter" node resolves to this donor. */
    u64 *provider = (u64 *)(g_plugin_base + 0x20960 + 0x10);
    provider[5] = (u64)&shim_provider_name;
#endif

    __android_log_print(4, "HyperCamVt", "vtable remapped at %p", (void *)vtable);
}

__attribute__((constructor)) static void shim_ctor(void) {
    shim_apply_vtable_patch();
}
