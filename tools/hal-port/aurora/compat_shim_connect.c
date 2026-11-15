/* Aurora plugin ABI bridge: the provider's PluginManager resolves the ABI
 * entry point with dlsym(handle, "connect") on the donor plugin handle.
 *
 * Nezha/Madrid builds export "miviconnect" instead, so the loader never finds
 * the registration hook and the node fails with
 *   MiaPluginManager.cpp:65 Nothing registered by plugin <name>
 *
 * This shim is listed before every other dependency of the plugin, so its own
 * "connect" wins the local-group dlsym() lookup (libc.so also exports a
 * "connect", but only after all DT_NEEDED entries). It forwards to the
 * plugin's intact "miviconnect".
 *
 * Do NOT rename the dynstr entry instead: the GNU hash of the new name is not
 * updated, so dlsym() misses the local symbol and silently returns libc's
 * unrelated connect().
 */

extern void *dlopen(const char *path, int flags);
extern void *dlsym(void *handle, const char *symbol);
extern int __android_log_print(int prio, const char *tag, const char *fmt, ...);
extern void shim_apply_vtable_patch(void);
extern void shim_mark(const char *msg, unsigned long a, unsigned long b);

typedef int (*mivi_connect_fn)(void *provider_manager);

int connect(void *provider_manager) {
    static mivi_connect_fn real;

    shim_apply_vtable_patch();
    shim_mark("connect", (unsigned long)provider_manager, 0);
    if (!real) {
        /* RTLD_NOW matches how the engine loaded the plugin. The library is
         * already mapped, so this only bumps its refcount. */
        void *handle = dlopen(
            "/odm/lib64/camera/plugins/com.xiaomi.plugin.mileicafilter.so", 2);
        if (!handle) {
            handle = dlopen(
                "/odm/lib64/camera/plugins/com.xiaomi.plugin.filter.so", 2);
        }
        if (!handle) {
            return 0;
        }
        real = (mivi_connect_fn)dlsym(handle, "miviconnect");
        if (!real) {
            return 0;
        }
    }

    int r = real(provider_manager);
    __android_log_print(4, "HyperCamCt", "miviconnect -> %d", r);
    return r;
}
