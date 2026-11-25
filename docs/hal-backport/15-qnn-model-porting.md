# QNN Model Porting: HTP V85 (SM8975) / V81 (SM8850) → V75 (SM8650)

**Status**: Complete static reverse engineering of 18 Pro Max (`madrid`) and 17 Ultra (`nezha`) QNN `.minn` models, `libmage.so`, `libmialgo_styletrans.so`, and `com.xiaomi.plugin.legendCT.so`. Extracted files live under `/run/media/rezikham/970/PoC/hal-extract-aurora/qnn_models/` and `/run/media/rezikham/970/PoC/extracted_madrid/`.

---

## QNN Runtime & Target Comparison (Verified)

| Component | Aurora (14 Ultra, SM8650) | Nezha (17 Ultra, SM8850) | Madrid (18 Pro Max, SM8975) |
|-----------|---------------------------|--------------------------|-----------------------------|
| **QNN HTP Arch** | **V75** | **V81** (`min_arch=81`) | **V85** (`soc_type=SM8975; arch=85; min_arch=85`) |
| **Styletrans QNN SDK** | N/A | `v2.33.0.250327124043_117917` | `v2.47.0.260601114230` |
| **HAL `libQnnHtp.so`** | `odm/lib64/libQnnHtp.so` (V75) | V81 | `vendor/lib64/libQnnHtp.so` (`v2.47.6`) |
| **HTP Stub / Skel** | `libQnnHtpV75{Stub,Skel}.so` | `libQnnHtpV81{Stub,Skel}.so` | `libQnnHtpV85{Stub,Skel}.so` + `libQnnHtpV85.so` |
| **LiteRT QNN bundle** | — | — | `system_ext/lib64/litert/` (`v2.39.0`, `V68..V81` + `libQnnGpu.so`) |

> **Correction to earlier notes:** Madrid (18 Pro Max) runs **HTP V85** (`SM8975`, QNN `v2.47.x`), not V81. Nezha (17 Ultra) runs **HTP V81** (`v2.33.0`), not V75-era.

---

## MiNN (`.minn`) Container & Encryption — Cracked

Earlier inspection mistook a random `\x1f\x8b` byte pair inside the binary for "proprietary gzip compression (CM=0x67)". Disassembly of `mage::NNDecrypt` (`0x92ba20`) → `mage::NNEncrypt` (`0x92b544`) in `libmage.so` proves there is **no compression**:

| Property | Madrid (`NNiM` v2.0.1) | Nezha (`NNiM` v1) |
|----------|------------------------|-------------------|
| Magic | `4e 4e 69 4d` (`"NNiM"`) | `4e 4e 69 4d` (`"NNiM"`) |
| Header size | **84 bytes (`0x54`)** — includes tensor names (`input_1`, `output_1`) and payload byte length | **30 bytes (`0x1e`)** |
| Encryption | 16-byte repeating XOR with **`"legenddddddddddd"`** (`"legend"` padded with `'d'` / `0x64`) | Same 16-byte repeating XOR key (`"legenddddddddddd"`) |
| Encrypted range | **First `0x10000` (64 KiB) only**; remainder (~39.9 MB) is plaintext | Entire payload |
| Decrypted payload | Raw Qualcomm **QNN SystemContext + HTP Context Binary** (`0x00..0x3a0` FlatBuffer metadata, HTP blob at `0x1000`) | Raw Qualcomm **QNN SystemContext + HTP Context Binary** (HTP blob at `0x328`) |

`tools/hal-port/aurora/extract_minn.py` now decrypts both v1 and v2 containers cleanly and parses QNN SDK version, SoC/arch, graph name, and tensor metadata.

---

## Decrypted Model Specifications

### Madrid (18 Pro Max) — 4 Bilateral-Grid Style-Transfer Models (HTP V85, QNN v2.47.0)

| File | Size | Graph | `input_1` (`UFIXED_POINT_16`) | `output_1` (`UFIXED_POINT_16`) | Output Range |
|------|------|-------|-------------------------------|--------------------------------|--------------|
| `styletrans_high_m10r.minn` | 39,972,948 | `m10_high_sim` | `(1, 512, 512, 3)` (`scale=1/65535, off=0`) | `(1, 256, 256, 384)` (`scale=2.332e-5, off=-17229`) | `[-0.40, +1.13]` |
| `styletrans_low_m10r.minn` | 39,981,140 | `m10_low_sim` | `(1, 512, 512, 3)` | `(1, 256, 256, 384)` (`scale=2.220e-5, off=-12427`) | `[-0.28, +1.18]` |
| `styletrans_high_m9.minn` | 39,731,284 | `m9_high_sim` | `(1, 512, 512, 3)` | `(1, 256, 256, 384)` (`scale=1.982e-4, off=-11061`) | `[-2.19, +10.80]` |
| `styletrans_low_m9.minn` | 39,723,092 | `m9_low_sim` | `(1, 512, 512, 3)` | `(1, 256, 256, 384)` (`scale=2.151e-4, off=-9901`) | `[-2.13, +11.97]` |

- **Network architecture:** 4-stage UNet (`_downs_0..3` / `_ups_0..3`) with multi-scale average-pool fusion blocks (`_fn_convs_0..3`) and residual blocks (`block1` / `block2`), mixed `UFIXED_POINT_8` / `UFIXED_POINT_16`, ~34 MB quantized weights.
- **How `libmialgo_styletrans.so` (Madrid) uses the output (OpenCL kernels verified):**
  - `384 = 32 luma/guidance bins × 12 affine 3×4 RGB color-matrix coefficients` (`base_ch = grid_channel * 4 + gk * 12`).
  - Pipeline: `styletrans_process_yuv{12,21}_2_rgb_cl` → resize `512×512×3` → QNN UNet → `256×256×384` bilateral affine grid → `styletrans_process_bilateral_process_cl` (8-vertex trilinear slice at full resolution) → `styletrans_process_rgb_2_yuv{12,21}_cl`.
  - It is a **resolution-independent HDRnet-style bilateral color-transform grid**, not a generative texture network.

### Nezha (17 Ultra) — 3 Image-to-Image Style Models (HTP V81, QNN v2.33.0)

| File | Size | Input (`UFIXED_POINT_16`) | Output (`UFIXED_POINT_16`) |
|------|------|---------------------------|----------------------------|
| `styletrans_high.minn` | 9,939,758 | `(1, 864, 1120, 4)` | `(1, 864, 1120, 3)` |
| `styletrans_low.minn` | 10,005,294 | `(1, 864, 1120, 4)` | `(1, 864, 1120, 3)` |
| `styletrans_colorfix.minn` | 8,398,942 | `(1, 544, 544, 6)` | `(1, 544, 544, 3)` |

---

## `libmage.so` Executor & Why 14 Ultra Failed (`m_interface is null`)

- `libmage.so` embeds versioned QNN executors `QnnExecutorImplV2133` through **`QnnExecutorImplV2474`** (`qnn.v2.47`) plus `libQnnHtp.so` / `libQnnGpu.so` / `libQnnCpu.so` loaders.
- On init, it matches the model's QNN SDK version (`v2.47.0` → `QnnExecutorImplV2474`), opens `libQnnHtp.so`, calls `QnnInterface_getProviders`, and checks `GetHtpDeviceArch`. `m_interface is null` fires when no compatible `v2.47` provider initializes on 14 Ultra.
- **Hard portability blocker:** All four Madrid `.minn` models contain **only** precompiled HTP V85 (`soc_type=SM8975; arch=85; min_arch=85`) context binaries with HMX/HVX VTCM schedules and crouton-tiled weights. There is no ONNX, TFLite, DLC, QNN GPU, or QNN CPU payload anywhere in the dump. Re-targeting to V75 requires the pre-compilation ONNX/PyTorch graph.

---

## Non-QNN 18PM Components & Authentic 1:1 M10R LUT Discovery

### `legendCT` (`com.xiaomi.plugin.legendCT.so` / `libmialgo_ccm_3x8.so`) — Verified No-Op Placeholder
- `com.xiaomi.plugin.legendCT.so` (`Legend-COLORTRANS`) does **not** use QNN; it links `libmialgo_ccm_3x8.so` (14 MB, OpenCL/CPU `LUTProcessor`).
- Static inspection of `.rodata` (`0x18fd68..0xa6e8f0`) found all **153 embedded 17³ float32 3D LUTs** (`luts_m9_{w,uw,t}_size17_cube`), and **all 153 are exact identity (`global max_abs_diff = 0.000000`)**. Furthermore, `legendsnapshot.json` does not even wire `legendCT`. There is no missing color transform to port from `legendCT`.

### Authentic 18PM M10R & M9 3D LUTs Found in `*_preview.bin`
- On 18 Pro Max (`madrid`), `leica_filter_param_m10r_snapshot.bin` has **0/108 non-neutral LUTs** (`max mean_diff = 0.000`) because snapshot relies on the QNN HTP V85 `styletrans_*_m10r.minn` UNet.
- However, real-time viewfinder preview cannot run the 40 MB UNet per frame, so Xiaomi bakes the official **18 Pro Max Leica M10-R (CMOS) 17³ 3D LUT** into **all 108/108 slots** of `leica_filter_param_m10r_preview.bin` (`2026-09-04`, `mean_diff = 10.70/255`, `max_diff = 65/255` vs identity; `mean_diff = 17.79/255`, `max_diff = 83/255` vs `leica_filter_param_m9_preview.bin`). Likewise, `leica_filter_param_m9_preview.bin` has **114/114 non-neutral LUTs** (`mean_diff = 12.55–13.52/255`), vs only `42/114` in `m9_snapshot.bin`.
- The 440-byte size delta between 18PM bins (`1,598,108` bytes) and `libm9base.so`'s expected layout (`1,597,668` bytes) is strictly in the 55 trailing parameter records: 18PM stores `int32(9) + float32[9]` (40 bytes/record, trailing float `1.0f`), whereas `libm9base.so` expects `float32[8]` (32 bytes/record). Bytes `0..4095` are byte-for-byte identical except the 6-digit timestamp, and all 55×8 floats match 100%.
- `tools/hal-port/luts/make_m10r_lut.py` now extracts the genuine 18PM M10R 17³ LUT into both the 512×512 strip LUT (`M10R_Cmos.png` / `lut_m10r.png`, MD5 `e1a95801d1a720f9ea446c290a8cc8ab`) and the trimmed 1,597,668-byte `libm9base`/B12 filter bin (`src/main/assets/essential/leica_filter_param_m10r_snapshot.bin`, SHA-256 `52a41273...`).
