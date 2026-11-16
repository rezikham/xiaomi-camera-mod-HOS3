/*
 * mount_hide.c — initial mount-hiding function for HyperCameraMod.
 *
 * Purpose
 * -------
 * Duck-Detector's Mount detector reads /proc/self/mounts and
 * /proc/self/mountinfo and flags systemless-root bind mounts, overlays,
 * loop devices and dm-verity bypass patterns.  It also compares mount-ID
 * sequences, anonymous-device minors, namespace identity vs init and
 * /proc/self/maps library paths.
 *
 * This module hides the *module's own* mount entries from this process's
 * mount namespace so they no longer appear in those tables.  Two backends
 * are tried in order:
 *
 *   1. NoMount keyring (VFS path redirection) — maxsteeel/nomount
 *      Sends NM_CMD_ADD_RULE / NM_FLAG_WHITEOUT rules through the Linux
 *      keyring (add_key("nomount", "trigger", &ptr, 8, -1)).  This is a
 *      pure in-kernel VFS change: no real mount is created, nothing
 *      appears in /proc/self/mounts, and no CAP_SYS_ADMIN is needed for
 *      the syscall itself (the kernel module must be loaded).  This is
 *      the preferred backend because it is invisible to mount-table
 *      scanners and works on read-only partitions (erofs).
 *
 *   2. meta-hybrid_mount — unshare(CLONE_NEWNS) + bind-mount empty files
 *      over the module paths.  Requires CAP_SYS_ADMIN (root) and creates
 *      real (but private-namespace-only) mount entries.
 *
 *   susfs4ksu / BRENE concept: per-process mount-view isolation.  The
 *   unshare() approach is the userspace equivalent; kernel-level SUSFS
 *   (custom kernel) is the only way to hide from every namespace.
 *
 *   ReZygisk concept: PLT introspection noted as future work; this
 *   version hides mounts via namespace isolation + bind mounts / keyring.
 *
 * Limitations (documented in docs/mount-hiding.md):
 *   * NoMount backend requires the NoMount kernel module / provider to
 *     be loaded (KernelSU/APatch metamodule).  On stock kernels it will
 *     fall back to bind-mounts or do nothing.
 *   * Hides mounts only in this process's namespace.  Duck-Detector runs
 *     some detectors in isolated services whose namespaces are separate.
 *   * Does not remove root, su binaries, or other Duck-Detector signals.
 *
 * Build: src/main/jni/mount_hide/CMakeLists.txt (externalNativeBuild).
 */

#define _GNU_SOURCE
#include <jni.h>

#include <sched.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <limits.h>

#include <android/log.h>

#define LOG_TAG "MountHide"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

/* ------------------------------------------------------------------ */
/* NoMount keyring interface (maxsteeel/nomount)                      */
/* ------------------------------------------------------------------ */

#define NOMOUNT_KEY_TYPE   "nomount"
#define NOMOUNT_KEY_DESC   "trigger"
#define NOMOUNT_MAGIC      0x4E4F4D4F554E54ULL /* "NOMOUNT" */

enum {
    NM_CMD_UNSPEC = 0,
    NM_CMD_GET_VERSION = 1,
    NM_CMD_ADD_RULE = 2,
    NM_CMD_DEL_RULE = 3,
    NM_CMD_ADD_UID = 4,
    NM_CMD_DEL_UID = 5,
    NM_CMD_CLEAR_ALL = 6,
    NM_CMD_CLEAR_RULES = 7,
    NM_CMD_CLEAR_UIDS = 8,
    NM_CMD_GET_LIST = 9,
    NM_CMD_GET_UIDS = 10,
    NM_CMD_BLOCK_ISOLATED_UIDS = 11,
    NM_CMD_GET_ISOLATED_STATE = 12,
};

#define NM_FLAG_IS_DIR      (1u << 0)
#define NM_FLAG_VIRTUAL_DIR (1u << 1)
#define NM_FLAG_WHITEOUT    (1u << 2)
#define NM_FLAG_OPAQUE      (1u << 3)

struct nm_rule_hdr {
    unsigned int flags;
    unsigned int uid;
    unsigned int uid_end;
    unsigned int deny_start;
    unsigned int deny_end;
    unsigned short v_len;
    unsigned short r_len;
} __attribute__((packed));

struct nm_payload {
    unsigned long long magic;
    unsigned int cmd;
    unsigned int target_uid;
    int status;
    unsigned int arg1;
    unsigned int data_size;
    char buffer[4068];
} __attribute__((packed));

/* SYS_add_key may not be exposed by every NDK header; define by arch. */
#if defined(__aarch64__)
    #define MH_SYS_ADD_KEY 217
#elif defined(__arm__)
    #define MH_SYS_ADD_KEY 309
#elif defined(__x86_64__)
    #define MH_SYS_ADD_KEY 248
#elif defined(__i386__)
    #define MH_SYS_ADD_KEY 248
#else
    #define MH_SYS_ADD_KEY 217
#endif

/* Send a payload to the NoMount kernel provider via add_key keyring.
 * The kernel expects payload to be a userspace pointer; we pass a
 * pointer-to-pointer (8 bytes) as the add_key payload, then the kernel
 * dereferences it to read the full struct. */
static int nm_keyring_send(struct nm_payload *pl) {
    pl->status = -1;
    unsigned long ptr = (unsigned long)pl;
    long ret = syscall(MH_SYS_ADD_KEY,
                       (long)NOMOUNT_KEY_TYPE,
                       (long)NOMOUNT_KEY_DESC,
                       (long)&ptr,
                       (long)sizeof(ptr),
                       (long)-1); /* KEY_SPEC_THREAD_KEYRING */
    if (ret < 0) {
        LOGE("NoMount add_key syscall failed: %s", strerror(errno));
        return -1;
    }
    return pl->status;
}

/* Add a whiteout rule: hide vpath from this process's VFS view. */
static int nm_keyring_whiteout(const char *vpath) {
    if (!vpath) return -1;
    size_t vlen = strlen(vpath);
    if (vlen == 0 || vlen > 4000) return -1;

    struct nm_payload *pl = malloc(sizeof(*pl));
    if (!pl) return -1;

    memset(pl, 0, sizeof(*pl));
    pl->magic = NOMOUNT_MAGIC;
    pl->cmd = NM_CMD_ADD_RULE;
    pl->target_uid = 0;
    pl->status = -1;
    pl->arg1 = 0;

    struct nm_rule_hdr *hdr = (struct nm_rule_hdr *)pl->buffer;
    hdr->flags = NM_FLAG_WHITEOUT;
    hdr->uid = 0;
    hdr->uid_end = 0;
    hdr->deny_start = 0;
    hdr->deny_end = 0;
    hdr->v_len = (unsigned short)vlen;
    hdr->r_len = 0;

    memcpy(pl->buffer + sizeof(*hdr), vpath, vlen);
    pl->data_size = (unsigned int)(sizeof(*hdr) + vlen);

    int ret = nm_keyring_send(pl);
    free(pl);
    return ret;
}

/* ------------------------------------------------------------------ */
/* Bind-mount backend (meta-hybrid_mount / susfs4ksu concept)         */
/* ------------------------------------------------------------------ */

static char g_staging_dir[PATH_MAX];
static volatile int g_inited = 0;
static volatile int g_use_nomount = 0;

static const char *g_hide_paths[] = {
    "/data/adb/modules",
    "/data/adb/ksu",
    "/data/adb/modules_update",
    "/data/adb/modules_compat",
    "/data/adb/hypercam",
    NULL,
};

/* Replace '/' with '_' so a mount path becomes a legal file name. */
static void mh_sanitize(const char *src, char *dst, size_t dstsz) {
    size_t i;
    for (i = 0; i + 1 < dstsz && src[i]; i++) {
        dst[i] = (src[i] == '/') ? '_' : src[i];
    }
    dst[i] = '\0';
}

/* Recursively create parent directories for a path. */
static int mh_mkdir_parents(const char *path) {
    char tmp[PATH_MAX];
    char *p;
    size_t len = strlen(path);
    if (len >= sizeof(tmp)) return -1;
    memcpy(tmp, path, len + 1);
    for (p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            if (mkdir(tmp, 0755) < 0 && errno != EEXIST) return -1;
            *p = '/';
        }
    }
    if (mkdir(tmp, 0755) < 0 && errno != EEXIST) return -1;
    return 0;
}

/* Bind-mount an empty tmpfs-backed file over `target`, hiding whatever
 * was mounted there from this namespace's mount tables. */
static int mh_bind_hide_file(const char *target) {
    char src[PATH_MAX];
    char parent[PATH_MAX];
    char *slash;
    int fd;

    mh_sanitize(target, src + strlen(g_staging_dir),
                sizeof(src) - strlen(g_staging_dir));
    memmove(src + strlen(g_staging_dir), src, strlen(src) + 1);
    memcpy(src, g_staging_dir, strlen(g_staging_dir));

    strncpy(parent, src, sizeof(parent));
    slash = strrchr(parent, '/');
    if (slash) *slash = '\0';
    if (mh_mkdir_parents(parent) < 0) return -1;

    fd = open(src, O_CREAT | O_RDWR | O_CLOEXEC, 0644);
    if (fd < 0) return -1;
    close(fd);

    strncpy(parent, target, sizeof(parent));
    slash = strrchr(parent, '/');
    if (slash) *slash = '\0';
    if (mh_mkdir_parents(parent) < 0) return -1;
    if (access(target, F_OK) < 0) {
        if (mkdir(target, 0755) < 0 && errno != EEXIST) return -1;
    }

    if (mount(src, target, "none", MS_BIND | MS_REC | MS_PRIVATE, NULL) < 0) {
        if (mount(src, target, "none", MS_BIND | MS_PRIVATE, NULL) < 0) {
            return -1;
        }
    }
    return 0;
}

static void mh_hide_root_mounts_bind(void) {
    int i;
    for (i = 0; g_hide_paths[i]; i++) {
        (void)mh_bind_hide_file(g_hide_paths[i]);
    }
}

static void mh_enter_private_ns(void) {
    if (unshare(CLONE_NEWNS) == 0) {
        mount("", "/", "none", MS_PRIVATE | MS_REC, NULL);
    }
}

/* ------------------------------------------------------------------ */
/* JNI entry points                                                   */
/* ------------------------------------------------------------------ */

JNIEXPORT void JNICALL
Java_io_martin_hypercameramod_MountHide_nativeInit(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (g_inited) return;

    /* 1. Try NoMount keyring first (VFS-only, invisible to mount tables). */
    int nm_ok = 0;
    for (int i = 0; g_hide_paths[i]; i++) {
        if (nm_keyring_whiteout(g_hide_paths[i]) == 0) {
            nm_ok = 1;
        }
    }

    if (nm_ok) {
        g_use_nomount = 1;
        g_inited = 1;
        LOGI("MountHide: NoMount keyring backend active");
        return;
    }

    /* 2. Fall back to bind-mount/unshare (meta-hybrid_mount style). */
    const char *staging = "/data/local/tmp/.mounthide_staging";
    if (mkdir(staging, 0755) < 0 && errno != EEXIST) {
        staging = NULL;
    }
    if (staging) {
        strncpy(g_staging_dir, staging, sizeof(g_staging_dir) - 1);
        g_staging_dir[sizeof(g_staging_dir) - 1] = '\0';
    }

    mh_enter_private_ns();
    mh_hide_root_mounts_bind();

    g_use_nomount = 0;
    g_inited = 1;
    LOGI("MountHide: bind-mount backend active (use_nomount=%d)", g_use_nomount);
}

JNIEXPORT void JNICALL
Java_io_martin_hypercameramod_MountHide_nativeHidePath(JNIEnv *env, jobject thiz,
                                                       jstring target, jstring source) {
    (void)thiz;
    const char *t = (*env)->GetStringUTFChars(env, target, NULL);
    if (!t) return;

    if (g_use_nomount) {
        nm_keyring_whiteout(t);
    } else {
        const char *s = (*env)->GetStringUTFChars(env, source, NULL);
        if (s) {
            mount(s, t, "none", MS_BIND | MS_REC | MS_PRIVATE, NULL);
            (*env)->ReleaseStringUTFChars(env, source, s);
        }
    }

    (*env)->ReleaseStringUTFChars(env, target, t);
}

JNIEXPORT jboolean JNICALL
Java_io_martin_hypercameramod_MountHide_nativeIsAvailable(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (getuid() == 0) ? JNI_TRUE : JNI_FALSE;
}
