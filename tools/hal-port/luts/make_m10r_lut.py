#!/usr/bin/env python3
"""Bake the derived M10R (CMOS) strip LUT.

The 18 Pro Max ships the M10R 3D LUTs neutral (verified: max deviation 1/255
across all 108 blocks of leica_filter_param_m10r_snapshot.bin), so there is no
authentic M10R LUT to extract. The M10R look is the warm-density grade
(-0.95 EV, warmth 0.3) plus the M10rBin post. This script bakes that grade
into the camera's 512x512 strip-LUT tile format:

    linear exposure x 2^-0.95 (density -0.95 EV)
    warm tilt R x1.15, B x0.88  (measured M10R/M9 same-scene output ratios)

It is an approximation: the genuine per-pixel M10R processing stays in the
Essential Moment pipeline.
"""
import sys
from PIL import Image

DENSITY_EV = -0.95
EXPOSURE = 2.0 ** DENSITY_EV          # 0.5170
WARM_R = 1.15                          # measured M10R/M9 output R ratio
WARM_B = 0.88                          # measured M10R/M9 output B ratio

def srgb_to_lin(c):
    c /= 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

def lin_to_srgb(v):
    v = max(0.0, min(1.0, v))
    return (v * 12.92 if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055) * 255.0

def grade(r, g, b):
    lr = srgb_to_lin(r) * EXPOSURE * WARM_R
    lg = srgb_to_lin(g) * EXPOSURE
    lb = srgb_to_lin(b) * EXPOSURE * WARM_B
    return (round(lin_to_srgb(lr)), round(lin_to_srgb(lg)), round(lin_to_srgb(lb)))

S = 64
img = Image.new('RGB', (512, 512))
px = img.load()
for b in range(S):
    ty, tx = (b // 8) * 64, (b % 8) * 64
    for g in range(S):
        for r in range(S):
            px[tx + r, ty + g] = grade(r * 255 // 63, g * 255 // 63, b * 255 // 63)

out = sys.argv[1] if len(sys.argv) > 1 else 'M10R_Cmos.png'
img.save(out)
print('wrote', out)
