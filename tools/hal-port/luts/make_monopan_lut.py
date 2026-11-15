#!/usr/bin/env python3
"""Generate a 64^3 monochrome (Leica Monopan 50-like) LUT as a 512x512 tile PNG.

M3 is Leica Monopan 50: monochrome, deep blacks, bright highlights, grain.
A LUT can reproduce the tone/colour part (grain is spatial and not included).
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
