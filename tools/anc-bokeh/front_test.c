#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

typedef int (*getapi_t)(void*, int, int, int, int);
typedef int (*create_t)(void**);
typedef int (*destroy_t)(void**);
typedef int (*init_t)(void*, void*);
typedef int (*process_t)(void*, void*, void*, void*);
typedef int (*mask_t)(void*, void*);

typedef struct { uint8_t b[0x48]; } WSG;

int main(int argc, char** argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* lib = argv[1];
    const char* cfgdir = argv[2];
    const char* cfgbin = argv[3];
    const char* nv12 = argv[4];
    int w = 1920, h = 1080;

    void* hdl = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    if (!hdl) { printf("dlopen fail: %s\n", dlerror()); return 1; }
    getapi_t getapi = (getapi_t)dlsym(hdl, "sg_single_bokeh_get_api_impl");
    if (!getapi) { printf("dlsym fail: %s\n", dlerror()); return 1; }
    void* api[6]; memset(api, 0, sizeof api);
    int r = getapi(api, 0x30, 3, 0, 0);
    printf("get_api=%d\n", r);
    if (r) return 1;
    for (int i=0;i<6;i++) printf("slot%d=%p\n", i, api[i]);

    create_t create = (create_t)api[0];
    init_t init = (init_t)api[2];
    process_t process = (process_t)api[3];

    void* handle = 0;
    r = create(&handle);
    printf("create=%d handle=%p\n", r, handle);
    if (r || !handle) return 1;

    FILE* f = fopen(cfgbin, "rb");
    if (!f) { perror(cfgbin); return 1; }
    fseek(f, 0, SEEK_END); long csz = ftell(f); fseek(f, 0, SEEK_SET);
    void* cbuf = malloc(csz); fread(cbuf, 1, csz, f); fclose(f);
    uint8_t cfg[0x50]; memset(cfg, 0, sizeof cfg);
    *(const char**)(cfg + 0x00) = cfgdir;
    *(void**)(cfg + 0x18) = cbuf;
    *(uint64_t*)(cfg + 0x20) = (uint64_t)csz;
    *(void**)(cfg + 0x28) = cbuf;
    *(uint64_t*)(cfg + 0x30) = (uint64_t)csz;
    printf("calling init dir=%s binsize=%ld\n", cfgdir, csz); fflush(stdout);
    r = init(handle, cfg);
    printf("init=%d\n", r);
    if (r) return 1;

    size_t ysz = (size_t)w*h, uvsz = ysz/2;
    uint8_t *sy,*suv,*dy,*duv;
    posix_memalign((void**)&sy,64,ysz); posix_memalign((void**)&suv,64,uvsz);
    posix_memalign((void**)&dy,64,ysz); posix_memalign((void**)&duv,64,uvsz);
    memset(dy,0,ysz); memset(duv,128,uvsz);
    f = fopen(nv12,"rb"); if(!f){perror(nv12);return 1;}
    fread(sy,1,ysz,f); fread(suv,1,uvsz,f); fclose(f);

    WSG in; memset(&in,0,sizeof in); WSG out; memset(&out,0,sizeof out);
    *(int*)(in.b+0x00)=0; *(int*)(in.b+0x04)=0;
    *(int*)(in.b+0x08)=w; *(int*)(in.b+0x0c)=h;
    *(void**)(in.b+0x10)=sy; *(void**)(in.b+0x18)=suv;
    *(int*)(in.b+0x30)=w; *(int*)(in.b+0x34)=h; *(int*)(in.b+0x40)=h;
    *(int*)(out.b+0x00)=0; *(int*)(out.b+0x04)=0;
    *(int*)(out.b+0x08)=w; *(int*)(out.b+0x0c)=h;
    *(void**)(out.b+0x10)=dy; *(void**)(out.b+0x18)=duv;
    *(int*)(out.b+0x30)=w; *(int*)(out.b+0x34)=h; *(int*)(out.b+0x40)=h;

    uint8_t params[0x100]; memset(params,0,sizeof params);
    *(float*)(params+0x00)=1.4f;
    static int face[4] = {650, 60, 1250, 780};
    for (int off=0x04; off<=0x1c; off+=4) *(int*)(params+off)=1;
    *(float*)(params+0x00)=1.4f;
    *(void**)(params+0x10)=face;
    printf("calling process %dx%d\n", w, h); fflush(stdout);
    r = process(handle, &in, params, &out);
    printf("process=%d\n", r);
    if (r==0) {
        FILE* o=fopen("/data/local/tmp/anc-front/out.nv12","wb");
        fwrite(dy,1,ysz,o); fwrite(duv,1,uvsz,o); fclose(o);
        printf("wrote out.nv12\n");
    }
    return r ? 1 : 0;
}
