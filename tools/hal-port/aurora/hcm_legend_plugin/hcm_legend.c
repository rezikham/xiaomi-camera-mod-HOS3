/* Aurora-native wrapper plugin for the Nezha Legendary filter.
 *
 * The engine loads this .so as a normal Chi plugin, under its own NodeName.
 * It implements Aurora's plugin vtable (15 slots) and forwards the pixel
 * request to the Nezha donoralgorithm, which it dlopens privately:
 *
 *   Aurora engine --slot2(ProcessRequestInfo*)--> wrapper --slot5--> donor
 *
 * The donor is never registered with the engine and its own vtable is never
 * used by the engine; only its algorithm entry points are called, with the
 * Nezha-shaped structures it expects.
 *
 * Build: tools/hal-port/aurora/build_legend_wrapper.sh
 */
typedef unsigned char u8;
typedef unsigned int u32;
typedef unsigned long u64;

typedef void *(*dlopen_fn)(const char *, int);
typedef void *(*dlsym_fn)(void *, const char *);
typedef int (*log_fn)(int, const char *, const char *, ...);
typedef int (*prop_get_fn)(const char *, char *);

extern void *malloc(u64);
extern void free(void *);
extern void *memset(void *, int, u64);
extern void *memcpy(void *, const void *, u64);
extern int dlclose(void *);
extern void *dlopen(const char *, int);
extern void *dlsym(void *, const char *);
extern char *dlerror(void);

#define RTLD_NOW 2
#define RTLD_GLOBAL 0x100
#define RTLD_LOCAL 0

static dlopen_fn p_dlopen;
static dlsym_fn p_dlsym;
static log_fn p_log;
static prop_get_fn p_prop;

static void logi(const char *fmt, u64 a, u64 b) {
    if (p_log) p_log(4, "HCM-Legend", fmt, (void *)a, (void *)b);
}

/* ------------------------------------------------------------------ */
/* Provider registration                                               */
/* ------------------------------------------------------------------ */

static void *g_plugin_vtable[16];
static void *g_provider_vtable[6];

extern int provider_manager_add(void *pm, void *provider)
        __asm__("_ZN7mialgo215ProviderManager3addEPNS_8ProviderE");

static char g_name[] = "com.xiaomi.plugin.hcmlegend";

static int noop(void) { return 0; }

static void *provider_create(void *self);
/* getPluginName() returns std::string by value: the caller passes the return
 * object in x8 and frees its data. libc++ long form is {capacity|1, size,
 * data}; the name below is long (>22 bytes), so that is what we build. */
typedef struct {
    u64 cap;
    u64 size;
    char *data;
} hcm_string;

static hcm_string provider_name(void *self) {
    (void)self;
    u64 n = 0;
    while (g_name[n]) n++;
    char *p = (char *)malloc(n + 1);
    if (p) {
        for (u64 i = 0; i <= n; i++) p[i] = g_name[i];
    }
    hcm_string s;
    s.cap = n | 1;
    s.size = n;
    s.data = p;
    return s;
}

static void *provider_create(void *self) {
    (void)self;
    void *p = malloc(sizeof(void *) * 24);
    if (!p) return 0;
    memset(p, 0, sizeof(void *) * 24);
    *(void ***)p = g_plugin_vtable;
    logi("provider create -> %lx", (u64)p, 0);
    return p;
}

/* ------------------------------------------------------------------ */
/* Donor loading                                                       */
/* ------------------------------------------------------------------ */

#define DONOR_PATH "/odm/lib64/com.xiaomi.plugin.mileicafilter.so"
#define DONOR_SIZE 0x150

typedef void (*donor_init_fn)(void *, void *, void *);
typedef int (*donor_pri_fn)(void *, void *);
typedef int (*donor_params_fn)(void *, void *);
typedef void (*donor_destroy_fn)(void *, int);
typedef void (*donor_void_fn)(void *);

static void *g_donor;
static donor_init_fn g_donor_init;
static donor_pri_fn g_donor_pri;
static donor_params_fn g_donor_enabled;
static donor_destroy_fn g_donor_destroy;
static donor_void_fn g_donor_reinit;
static int g_donor_inited;
static u32 g_style;

static u32 forced_style(void) {
    char buf[16];
    if (!p_prop) return 0;
    buf[0] = 0;
    p_prop("persist.vendor.camera.hypercam.style", buf);
    if (buf[0] == '1') return 1;   /* M9 */
    if (buf[0] == '2') return 2;   /* M3 */
    return 0;
}

static int load_donor(void) {
    if (g_donor) return 1;
    void *shim = p_dlopen("/odm/lib64/hcm/libhcm_shim.so", RTLD_NOW | RTLD_GLOBAL);
    if (!shim) {
        if (p_log) p_log(4, "HCM-Legend", "shim dlopen failed: %s", dlerror());
    }
    void *h = p_dlopen(DONOR_PATH, RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        if (p_log) p_log(4, "HCM-Legend", "donor dlopen failed: %s", dlerror());
        return 0;
    }
    void *ctor = p_dlsym(h, "_ZN19MiLeicaFilterPluginC1Ev");
    if (!ctor) {
        logi("donor ctor missing", 0, 0);
        return 0;
    }
    void *obj = malloc(DONOR_SIZE);
    if (!obj) return 0;
    memset(obj, 0, DONOR_SIZE);
    ((void (*)(void *))ctor)(obj);
    void **vt = *(void ***)obj;
    g_donor_init = (donor_init_fn)vt[0];
    g_donor_pri = (donor_pri_fn)vt[5];
    g_donor_enabled = (donor_params_fn)vt[12];
    g_donor_destroy = (donor_destroy_fn)vt[11];
    g_donor_reinit = (donor_void_fn)p_dlsym(h, "_ZN19MiLeicaFilterPlugin12initResourceEv");
    g_donor = obj;
    logi("donor loaded vt=%lx", (u64)vt, 0);
    return 1;
}

/* ------------------------------------------------------------------ */
/* Plugin vtable                                                       */
/* ------------------------------------------------------------------ */

/* Aurora ImageParams (input side; output side has the same body) */
struct AImg {
    u32 fmt;          /* +0x00 */
    u32 width;        /* +0x04 */
    u32 height;       /* +0x08 */
    u8 pad0[0x10];
    u32 usage;        /* +0x1c */
    u8 pad1[0x08];    /* +0x20 */
    void *meta;       /* +0x28 shared_ptr<MiMetadata> */
    void *meta2;      /* +0x30 */
    u8 pad2[0x38];
    void *native;     /* +0x70 native_handle* (stock filter 0xc474) */
};

/* Nezha ImageParams, which the donor reads */
struct NImg {
    u32 fmt;
    u32 width;
    u32 height;
    u8 pad0[0x2c];
    u32 usage;        /* +0x38 (donor getImageInfo 0x11f3c) */
    u32 fmt2;         /* +0x3c */
    void *meta;       /* +0x40 */
    void *meta2;      /* +0x48 */
    u8 pad1[0x20];
    u32 planes;       /* +0x70 */
    u8 pad2[0x3c];
    void *native;     /* +0xb0 */
};

struct Container {
    u8 pad[0x28];
    void *img;
};

struct NezhaPri {
    void *in;         /* +0x00 */
    u8 pad1[0x10];
    void *out;        /* +0x18 */
    u8 pad2[0x28];
    u32 frame;        /* +0x48 */
};

static void translate(struct AImg *src, struct NImg *dst) {
    memset(dst, 0, sizeof(*dst));
    dst->fmt = src->fmt;
    dst->width = src->width;
    dst->height = src->height;
    dst->usage = src->usage;
    dst->fmt2 = 0;
    dst->meta = src->meta;
    dst->meta2 = src->meta2;
    dst->native = src->native;
}

static int plugin_initialize(void *self, void *ci, void *iface) {
    (void)self; (void)ci; (void)iface;
    logi("plugin initialize", (u64)ci, (u64)iface);
    return 0;
}

static u32 g_frame;

static int plugin_process_request(void *self, void *pri) {
    (void)self;
    if (!pri) return 0;
    if (!load_donor()) return 0;

    struct AImg *ai = 0, *ao = 0;
    void *in = *(void **)pri;
    void *out = *(void **)((u8 *)pri + 0x18);
    if (in) ai = (struct AImg *)*(void **)((u8 *)in + 0x28);
    if (out) ao = (struct AImg *)*(void **)((u8 *)out + 0x28);
    logi("pri in=%lx out=%lx ai=%lx ao=%lx", (u64)in, (u64)out);
    if (!ai || !ao) return 0;

    if (!g_donor_inited) {
        u8 ci[0x60];
        memset(ci, 0, sizeof(ci));
        *(u32 *)(ci + 0x44) = 1;
        *(void **)(ci + 0x48) = ai->meta;
        u8 iface[0x80];
        memset(iface, 0, sizeof(iface));
        u32 style = forced_style();
        g_donor_init(g_donor, ci, iface);
        g_donor_inited = 1;
        logi("donor initialized style=%lx", style, 0);
    }
    /* isEnabled rewrites the look byte from the request metadata on every
     * capture, so force it here each time. */
    {
        u32 style = forced_style();
        if (style != g_style) {
            ((u8 *)g_donor)[0x25] = (u8)style;
            ((u8 *)g_donor)[0x26] = (u8)style;
            if (g_style && g_donor_reinit) {
                /* A different look needs the parameter table reloaded: the
                 * donor picks its .bin inside initialize()/initResource(). */
                g_donor_reinit(g_donor);
                logi("resource reloaded style=%lx", style, 0);
            }
            g_style = style;
        }
    }

    struct NImg ni, no;
    translate(ai, &ni);
    translate(ao, &no);
    logi("img fmt=%lx wh=%lx", ((u64)ai->fmt << 32) | ai->width, ai->height);
    logi("native in=%lx out=%lx", (u64)ai->native, (u64)ao->native);

    struct Container cin, cout;
    memset(&cin, 0, sizeof(cin));
    memset(&cout, 0, sizeof(cout));
    cin.img = &ni;
    cout.img = &no;

    struct NezhaPri np;
    memset(&np, 0, sizeof(np));
    np.in = &cin;
    np.out = &cout;
    np.frame = g_frame++;

    int r = g_donor_pri(g_donor, &np);
    logi("donor processRequest -> %lx", (u64)(long)r, 0);
    return r;
}

static int plugin_enabled(void *self, void *params) {
    (void)self; (void)params;
    u32 style = forced_style();
    if (!style) return 0;
    if (!load_donor()) return 0;
    logi("isEnabled style=%lx", style, 0);
    return 1;
}

static int plugin_destroy(void *self) {
    (void)self;
    if (g_donor && g_donor_destroy) {
        g_donor_destroy(g_donor, 1);
        g_donor = 0;
    }
    return 0;
}

static int ret0(void) { return 0; }
static int ret1(void) { return 1; }

/* Aurora plugin vtable, 16 slots. */
static void *g_plugin_vtable[16] = {
    (void *)plugin_initialize,   /* 0 */
    (void *)ret0,                /* 1 */
    (void *)plugin_process_request, /* 2 */
    (void *)ret0,                /* 3 */
    (void *)noop,                /* 4 */
    (void *)ret0,                /* 5 postProcess: no-op */
    (void *)ret0,                /* 6 */
    (void *)plugin_destroy,      /* 7 */
    (void *)plugin_enabled,      /* 8 */
    (void *)noop,                /* 9 */
    (void *)noop,                /* 10 */
    (void *)noop,                /* 11 */
    (void *)ret0,                /* 12 */
    (void *)ret0,                /* 13 */
    (void *)ret1,                /* 14 */
    (void *)noop,                /* 15 */
};

/* ------------------------------------------------------------------ */
/* connect()                                                           */
/* ------------------------------------------------------------------ */

static int provider_ready;

static int setup_provider(void) {
    if (provider_ready) return 1;
    void *h = p_dlopen("/odm/lib64/camera/plugins/com.xiaomi.plugin.filter.so",
                       RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        logi("stock plugin dlopen failed", 0, 0);
        return 0;
    }
    void **vt = (void **)p_dlsym(h, "_ZTV20FilterPluginProvider");
    if (!vt) {
        logi("stock provider vtable missing", 0, 0);
        return 0;
    }
    /* [0]=offset-to-top, [1]=typeinfo, [2..7]=slots 0..5 */
    for (int i = 0; i < 6; i++) g_provider_vtable[i] = vt[2 + i];
    g_provider_vtable[4] = (void *)provider_create;
    g_provider_vtable[5] = (void *)provider_name;
    provider_ready = 1;
    logi("provider table ready", 0, 0);
    return 1;
}

int connect(void *pm) {
    if (!setup_provider()) return 0;
    void *p = malloc(8);
    if (!p) return 0;
    *(void **)p = g_provider_vtable;
    provider_manager_add(pm, p);
    logi("connect registered", (u64)pm, 0);
    return 1;
}

/* ------------------------------------------------------------------ */
/* Resolve libc helpers without a libc start file                      */
/* ------------------------------------------------------------------ */

__attribute__((constructor)) static void hcm_init(void) {
    p_dlopen = (dlopen_fn)dlsym((void *)0, "dlopen");
    p_dlsym = (dlsym_fn)dlsym((void *)0, "dlsym");
    p_log = (log_fn)dlsym((void *)0, "__android_log_print");
    p_prop = (prop_get_fn)dlsym((void *)0, "__system_property_get");
    if (p_log) p_log(4, "HCM-Legend", "wrapper loaded", 0, 0);
}
