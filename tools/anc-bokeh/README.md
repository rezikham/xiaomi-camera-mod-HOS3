# 14 Ultra AI single-bokeh harness

This harness runs the 14 Ultra's own AI single-bokeh engine
(`/odm/lib64/libanc_single_bokeh.so`, v3 API) with the 14 Ultra SM8650 models
from `/odm/etc/camera/sg_*` and `intsense_config_singlebokeh.bin`.

Verified on `1c4e4dec` (aurora, build 338):

```text
get_api=0
slot0..slot5 ...
create=0
init=0
process=0
wrote out.nv12
```

The engine logs `model info: maps(...), context(3), runtime(1)` and runs the
OpenCL/DNN path. Output PSNR vs input was y:21.5, u:34.3, v:32.3 dB, with the
subject sharp and the background rendered as bokeh circles.

## API layout (v3)

```text
sg_single_bokeh_get_api_impl(api, 0x30, 3, 0, 0)
  slot0 create(void** handle)
  slot1 destroy(handle)
  slot2 init(handle, Config*)
  slot3 process(handle, WSGImage* in, Params*, WSGImage* out)
  slot4 get_mask(handle, Mask*)
  slot5 get_version(handle, char**)
```

Config: `+0x00 config_dir`, `+0x18 config_buf`, `+0x20 config_size`,
`+0x28 config_buf`, `+0x30 config_size`.

WSGImage: `+0x00 rotation`, `+0x04 format` (0=NV12, 1=P010),
`+0x08 width`, `+0x0c height`, `+0x10 plane0`, `+0x18 plane1`,
`+0x30 stride`, `+0x40 scanline`.

Params: `+0x00 fNumber`, `+0x08 face count`, `+0x10` pointer to
`int[4] {x1,y1,x2,y2}` corners.

## Build and run

```sh
NDK=$HOME/PoC/.android-sdk/ndk/27.0.12077973
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang
$CC -O2 -o front_test front_test.c -ldl
adb push front_test libanc_single_bokeh.so intsense_config_singlebokeh.bin \
  sg_depth_model sg_depth_policy sg_depth_cache \
  sg_seg_model sg_seg_policy sg_seg_cache \
  sg_head_model sg_head_policy sg_head_cache \
  sg_pre_model sg_pre_policy sg_pre_cache portrait.nv12 /data/local/tmp/anc-front/
adb shell "su -c 'cd /data/local/tmp/anc-front && ./front_test ./libanc_single_bokeh.so \
  /data/local/tmp/anc-front ./intsense_config_singlebokeh.bin ./portrait.nv12'"
```

## 18 PM Original LUT

The engine output can be fed through the exact 18 PM Original portrait LUT:

```sh
python3 make_lut_cube.py src/main/assets/original_portrait_lut.png original_portrait.cube
ffmpeg -i portrait_out.png -vf lut3d=file=original_portrait.cube:interp=trilinear portrait_out_lut.png
```
