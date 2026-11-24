#!/usr/bin/env python3
"""Generate a SYNTHETIC 64^3 monochrome LUT as a 512x512 tile PNG.

WARNING: this is a prototype tone curve, not the authentic Leica M3
M3_Monopan LUT. It must not be shipped as `lut_m3.png`. The real asset is
`src/main/assets/leica_luts/M3_Monopan.png`.
"""
import numpy as np
from PIL import Image

S = 64
img = np.zeros((512, 512, 3), np.uint8)

def tone(v):
    v = np.clip((v - 0.02) / 0.96, 0.0, 1.0)
    return v ** 1.15

for b in range(S):
    ty, tx = (b // 8) * 64, (b % 8) * 64
    for g in range(S):
        for r in range(S):
            R, G, B = r / 63.0, g / 63.0, b / 63.0
            y = 0.2126 * R + 0.7152 * G + 0.0722 * B
            v = tone(y)
            img[ty + g, tx + r] = (int(v * 255 + 0.5),) * 3

Image.fromarray(img).save('M3_Monopan.png')
print('wrote M3_Monopan.png')
