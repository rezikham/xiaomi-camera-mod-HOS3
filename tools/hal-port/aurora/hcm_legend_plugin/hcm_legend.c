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
 *
 * ========================================================================
 * ABI SAFETY NOTES (reviewed 2026-11-17):
 * - getPluginName() returns std::string by value as libc++ long form:
 *   {cap = len|1, size = len, data = malloc(len+1)}. The name
 *   "com.xiaomi.plugin.hcmlegend" (26 bytes) exceeds the 22-byte SSO
 *   threshold so long form is required. Caller frees the buffer.
 * - DT_NEEDED: libc.so, libdl.so, liblog.so, libcom.xiaomi.pluginutils.so,
 *   libhcm_shim.so (RTLD_GLOBAL), hcicxx.so (private libc++), 
 *   com.xiaomi.plugin.mileicafilter.so (donor, RTLD_LOCAL),
 *   libHcmPhotoFiltr.so (private libMiPhotoFilter), libcameraopt_stub.so,
 *   libcom.xiaomi.cam.miexifmgr.so, libmetatagwrapper.so
 * - ImageParams translation: Aurora native handle at +0x70, donor expects
 *   at +0xB0. Aurora usage at +0x1C, donor at +0x38. Metadata shared_ptr
 *   at +0x28/+0x30 (Aurora) -> +0x40/+0x48 (donor).
 * - sepolicy: hal_camera_default needs {open read getattr map execute} on
 *   system_file (KernelSU labels module files system_file under /odm).
 * - Error handling: load_donor() returns bool, isEnabled() returns 0 on
 *   failure (clean pass-through), processRequest logs donor return code.
 * ========================================================================
 */
typedef unsigned char u8;
typedef unsigned int u32;
typedef unsigned long u64;
typedef unsigned long size_t;

#ifndef NULL
#define NULL ((void *)0)
#endif

#define EEXIST 17
#define O_CREAT 0x40

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
extern int open(const char *, int, ...);
extern long read(int, void *, unsigned long);
extern long write(int, const void *, unsigned long);
extern int close(int);
extern int mkdir(const char *, unsigned int);
extern int mount(const char *, const char *, const char *, unsigned long, const void *);
extern int umount2(const char *, int);
extern int unshare(int);
extern long syscall(long, ...);
extern int errno;
extern char *strcpy(char *, const char *);
extern size_t strlen(const char *);

#define RTLD_NOW 2
#define RTLD_GLOBAL 0x100
#define RTLD_LOCAL 0

#define O_RDONLY  0
#define O_WRONLY  1
#define O_RDWR    0x2
#define O_CLOEXEC 0x80000

#define CLONE_NEWNS 0x00020000
#define MS_BIND 0x1000
#define MS_REC 0x4000
#define MS_PRIVATE 0x20000

/* SUSFS procfs interface candidates (kernel module exposes one of these). */
static const char *g_susfs_paths[] = {
    "/proc/susfs/rules",
    "/proc/susfs/hide",
    "/proc/susfs/command",
    "/proc/susfs/whiteout",
    "/proc/susfs/add_rule",
    NULL,
};

/* Paths to hide via the SUSFS backend (module trees under /data/adb/modules). */
static const char *g_susfs_hide_targets[] = {
    "/data/adb/modules",
    "/data/adb/modules_update",
    "/data/adb/modules_compat",
    NULL,
};

/* Wrapper-level init guard: prevents double initialization of the donor. */
static volatile int g_inited = 0;

/* Track mounts we created so shutdown can unwind them. */
#define GC_MAX_MOUNTS 16
static char *g_mount_list[GC_MAX_MOUNTS];
static int g_mount_count;

static dlopen_fn p_dlopen;
static dlsym_fn p_dlsym;
static log_fn p_log;
static prop_get_fn p_prop;

static void logi(const char *fmt, u64 a, u64 b) {
    if (p_log) p_log(4, "HCM-Legend", fmt, (void *)a, (void *)b);
}

static void loge(const char *fmt, u64 a, u64 b) {
    if (p_log) p_log(6, "HCM-Legend", fmt, (void *)a, (void *)b);
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
    if (!p) {
        loge("provider_name malloc failed", 0, 0);
        hcm_string s = {0, 0, NULL};
        return s;
    }
    for (u64 i = 0; i <= n; i++) p[i] = g_name[i];
    hcm_string s;
    s.cap = n | 1;
    s.size = n;
    s.data = p;
    return s;
}

static void *provider_create(void *self) {
    (void)self;
    void *p = malloc(sizeof(void *) * 24);
    if (!p) {
        loge("provider_create malloc failed", 0, 0);
        return 0;
    }
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

typedef hcm_string (*meta_find_fn)(void *, const char *);

static meta_find_fn p_find;

/* The app asks the engine for a look by putting its name into the render payload
 * ("lutNames":["lut_m3"]). That is what this node keys on, so the selection the user made
 * drives it: 2 = M3, 1 = M9, 0 = not our look. Falls back to the vendor property. */
static u32 style_from_meta(void *meta) {
    if (!meta || !p_find) return 0;
    hcm_string s = p_find(meta, "com.xiaomi.mivi2.render");
    if (!s.data || s.size == 0) {
        if (s.data) free(s.data);
        return 0;
    }
    u32 style = 0;
    for (u64 i = 0; i + 6 <= s.size; i++) {
        if (s.data[i] != 'l' || s.data[i + 1] != 'u' || s.data[i + 2] != 't'
                || s.data[i + 3] != '_') {
            continue;
        }
        if (s.data[i + 4] == 'm' && s.data[i + 5] == '3') { style = 2; break; }
        if (s.data[i + 4] == 'm' && s.data[i + 5] == '9') { style = 1; break; }
    }
    free(s.data);
    return style;
}

/* Parse render payload from intent extras: com.xiaomi.mivi2.render JSON with
 * "lutNames":["lut_m3"] or ["lut_m9"]. Expected structure:
 *   Intent extra key: "com.xiaomi.mivi2.render"
 *   Value: JSON string e.g. {"lutNames":["lut_m3"],"otherKeys":...}
 *   This is set by the camera app when user selects M3/M9 in Leica Essential UI.
 *   The wrapper reads it via MiMetadata::find() which returns the raw JSON string.
 *   We scan for "lut_m3" or "lut_m9" substrings (robust against JSON formatting).
 *   Fallback order: meta -> /data/vendor/camera/hcm_style -> persist.vendor.camera.hypercam.style */
static u32 forced_style(void) {
    int fd = open("/data/vendor/camera/hcm_style", 0);
    if (fd >= 0) {
        char buf[4];
        long n = read(fd, buf, sizeof(buf));
        close(fd);
        if (n > 0) {
            if (buf[0] == '1') return 1;
            if (buf[0] == '2') return 2;
            if (buf[0] == '0') return 0;
        }
    }
    char buf[16];
    if (!p_prop) return 0;
    buf[0] = 0;
    p_prop("persist.vendor.camera.hypercam.style", buf);
    if (buf[0] == '1') return 1;
    if (buf[0] == '2') return 2;
    return 0;
}

/* ------------------------------------------------------------------ */
/* SUSFS backend (third mount-hiding option, between NoMount and bind) */
/* ------------------------------------------------------------------ */

/* Write a single hide rule to the first SUSFS procfs node that exists.
 * Returns 0 on first success, -1 if none of the candidate nodes exist. */
static int susfs_hide_path(const char *vpath) {
    if (!vpath) return -1;
    size_t vlen = strlen(vpath);
    if (vlen == 0 || vlen > 4000) return -1;

    for (int i = 0; g_susfs_paths[i]; i++) {
        const char *proc = g_susfs_paths[i];
        int fd = open(proc, O_WRONLY | O_CLOEXEC);
        if (fd < 0) continue; /* try the next candidate path */
        long n = write(fd, vpath, vlen);
        int saved = errno;
        close(fd);
        errno = saved;
        if (n >= 0) {
            logi("susfs hide via %s -> %s", (u64)proc, (u64)vpath);
            return 0;
        }
        logi("susfs write %s failed errno=%d", (u64)proc, (u64)(long)saved);
    }
    return -1;
}

/* Hide all configured /data/adb/module* trees through SUSFS. */
static void susfs_hide_all(void) {
    for (int i = 0; g_susfs_hide_targets[i]; i++) {
        (void)susfs_hide_path(g_susfs_hide_targets[i]);
    }
}

/* ------------------------------------------------------------------ */
/* Style derivation from the camera render payload                    */
/* ------------------------------------------------------------------ */

/* Read the M3/M9 look from the camera's render payload
 * (com.xiaomi.mivi2.render "lutNames":["lut_m3"] or ["lut_m9"]).
 *
 * Parsing the actual payload from kernel/userspace render memory is complex
 * without device access, so this is a placeholder: it confirms we are in the
 * camera process by scanning /proc/self/cmdline for "camera", logs what it
 * finds, and returns 0 (unknown). The real implementation should open the
 * render-payload FD/expose it via a property and parse the lutNames array.
 * TODO(device): replace with real render-payload parsing once the FD/property
 * contract is known on the target build. */
static u32 style_from_render_payload(void) {
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    char buf[128];
    long n = read(fd, buf, sizeof(buf) - 1);
    int saved = errno;
    close(fd);
    errno = saved;
    if (n > 0) {
        buf[n] = 0;
        for (long i = 0; i < n; i++) {
            if (buf[i] == '\0') buf[i] = ' ';
        }
        int is_camera = 0;
        for (long i = 0; i + 6 <= n; i++) {
            if (buf[i] == 'c' && buf[i+1] == 'a' && buf[i+2] == 'm'
                    && buf[i+3] == 'e' && buf[i+4] == 'r' && buf[i+5] == 'a') {
                is_camera = 1;
                break;
            }
        }
        logi("render payload cmdline=%s is_camera=%d", (u64)(long)buf, (u64)is_camera);
        if (!is_camera) return 0;
    }
    /* TODO: parse "lutNames":[...] from the real render payload here. */
    logi("render payload parse: not implemented, returning 0 (unknown)", 0, 0);
    return 0;
}

static int load_donor(void) {
    if (g_donor) return 1;
    if (!p_find) {
        void *mu = p_dlopen("/odm/lib64/libcom.xiaomi.metadatautils.so", RTLD_NOW | RTLD_LOCAL);
        if (mu) {
            p_find = (meta_find_fn)p_dlsym(mu, "_ZNK10MiMetadata4findEPKc");
        }
        logi("metadata find=%lx", (u64)p_find, 0);
    }
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
        loge("donor ctor missing", 0, 0);
        return 0;
    }
    void *obj = malloc(DONOR_SIZE);
    if (!obj) {
        loge("donor object malloc failed", 0, 0);
        return 0;
    }
    memset(obj, 0, DONOR_SIZE);
    ((void (*)(void *))ctor)(obj);
    void **vt = *(void ***)obj;
    g_donor_init = (donor_init_fn)vt[0];
    g_donor_pri = (donor_pri_fn)vt[5];
    g_donor_enabled = (donor_params_fn)vt[12];
    g_donor_destroy = (donor_destroy_fn)vt[11];
    g_donor_reinit = (donor_void_fn)p_dlsym(h, "_ZN19MiLeicaFilterPlugin12initResourceEv");
    if (!g_donor_reinit) {
        loge("donor initResource symbol missing", 0, 0);
    }
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
    dst->usage = src->usage;      /* Nezha +0x38 <- Aurora +0x1c */
    dst->fmt2 = 0;
    dst->meta = src->meta;        /* Nezha +0x40 <- Aurora +0x28 */
    dst->meta2 = src->meta2;      /* Nezha +0x48 <- Aurora +0x30 */
    dst->native = src->native;    /* Nezha +0xb0 <- Aurora +0x70 */
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
    if (!load_donor()) {
        loge("process_request: load_donor failed", 0, 0);
        return 0;
    }

    struct AImg *ai = 0, *ao = 0;
    void *in = *(void **)pri;
    void *out = *(void **)((u8 *)pri + 0x18);
    if (in) ai = (struct AImg *)*(void **)((u8 *)in + 0x28);
    if (out) ao = (struct AImg *)*(void **)((u8 *)out + 0x28);
    logi("pri in=%lx out=%lx ai=%lx ao=%lx", (u64)in, (u64)out, (u64)ai, (u64)ao);
    if (!ai || !ao) {
        loge("process_request: missing image params", (u64)ai, (u64)ao);
        return 0;
    }

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
        u32 style = style_from_meta(ai->meta);
        if (!style) style = forced_style();
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
    (void)self;
    void *m0 = params ? *(void **)params : 0;
    void *m18 = params ? *(void **)((u8 *)params + 0x18) : 0;
    u32 style = style_from_meta(m0);
    if (!style) style = style_from_meta(m18);
    if (!style) style = forced_style();
    logi("isEnabled m0=%lx m18=%lx find=%lx style=%lx",
         (u64)m0, ((u64)m18 << 8) | (u64)style);
    if (!style) return 0;
    if (!load_donor()) {
        loge("isEnabled: load_donor failed", 0, 0);
        return 0;
    }
    return 1;
}

/* Shutdown/cleanup: unmount every bind-hide we created, release the donor,
 * and reset the init guard so a later re-initialization starts clean. */
static void hcm_shutdown(void);

static int plugin_destroy(void *self) {
    (void)self;
    if (g_donor && g_donor_destroy) {
        g_donor_destroy(g_donor, 1);
        g_donor = 0;
    }
    hcm_shutdown();
    return 0;
}

/* Shutdown/cleanup: unmount every bind-hide we created, release the donor,
 * and reset the init guard so a later re-initialization starts clean. */
static void hcm_shutdown(void) {
    for (int i = 0; i < g_mount_count; i++) {
        if (g_mount_list[i]) {
            umount2(g_mount_list[i], 0);
            free(g_mount_list[i]);
            g_mount_list[i] = 0;
        }
    }
    g_mount_count = 0;
    if (g_donor && g_donor_destroy) {
        g_donor_destroy(g_donor, 1);
        g_donor = 0;
    }
    g_donor_inited = 0;
    g_style = 0;
    g_inited = 0;
    logi("hcm shutdown complete", 0, 0);
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
        loge("stock plugin dlopen failed", 0, 0);
        return 0;
    }
    void **vt = (void **)p_dlsym(h, "_ZTV20FilterPluginProvider");
    if (!vt) {
        loge("stock provider vtable missing", 0, 0);
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
    if (!p) {
        loge("connect malloc failed", 0, 0);
        return 0;
    }
    *(void **)p = g_provider_vtable;
    provider_manager_add(pm, p);
    logi("connect registered", (u64)pm, 0);
    return 1;
}

/* ------------------------------------------------------------------ */
/* Resolve libc helpers without a libc start file                      */
/* ------------------------------------------------------------------ */

__attribute__((constructor)) static void hcm_init(void) {
    if (g_inited) return;
    p_dlopen = (dlopen_fn)dlsym((void *)0, "dlopen");
    p_dlsym = (dlsym_fn)dlsym((void *)0, "dlsym");
    p_log = (log_fn)dlsym((void *)0, "__android_log_print");
    p_prop = (prop_get_fn)dlsym((void *)0, "__system_property_get");
    if (p_log) p_log(4, "HCM-Legend", "wrapper loaded", 0, 0);

    /* Third backend: SUSFS procfs hide rules for /data/adb/modules trees. */
    susfs_hide_all();

    /* Fallback: bind-mount empty files over the module paths in a private
     * namespace, logging errno on failure so device debugging is possible. */
    {
        const char *staging = "/data/local/tmp/.hcm_susfs_staging";
        if (mkdir(staging, 0755) < 0 && errno != EEXIST) {
            staging = NULL;
        }
        if (staging) {
            if (unshare(CLONE_NEWNS) == 0) {
                mount("", "/", "none", MS_PRIVATE | MS_REC, NULL);
            }
            for (int i = 0; g_susfs_hide_targets[i]; i++) {
                const char *target = g_susfs_hide_targets[i];
                char src[512];
                int src_len = 0;
                while (staging[src_len]) { src[src_len] = staging[src_len]; src_len++; }
                const char *t = target;
                while (*t && src_len < (int)sizeof(src) - 1) {
                    src[src_len++] = (*t == '/') ? '_' : *t;
                    t++;
                }
                src[src_len] = 0;
                int fd = open(src, O_CREAT | O_RDWR | O_CLOEXEC, 0644);
                if (fd >= 0) close(fd);
                if (mount(src, target, "none", MS_BIND | MS_PRIVATE, NULL) < 0) {
                    int saved = errno;
                    logi("bind-mount hide %s failed errno=%d", (u64)(long)target, (u64)(long)saved);
                } else {
                    if (g_mount_count < GC_MAX_MOUNTS) {
                        char *copy = malloc(strlen(target) + 1);
                        if (copy) {
                            strcpy(copy, target);
                            g_mount_list[g_mount_count++] = copy;
                        }
                    }
                    logi("bind-mount hide %s active", (u64)(long)target, 0);
                }
            }
        }
    }

    g_inited = 1;
    logi("hcm init done inited=%d", (u64)g_inited, 0);
}
