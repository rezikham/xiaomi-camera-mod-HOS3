#!/usr/bin/env python3
"""Apply a 512x512 tiled 64^3 LUT PNG (8x8 tiles) to an image.

Usage: apply_lut.py LUT.png INPUT.jpg OUTPUT.jpg [strength]
"""
import sys
import numpy as np
from PIL import Image

def load_lut(path):
    im = np.asarray(Image.open(path).convert('RGB'), dtype=np.float32) / 255.0
    lut = np.zeros((64, 64, 64, 3), dtype=np.float32)
    for b in range(64):
        tx, ty = (b % 8) * 64, (b // 8) * 64
        tile = im[ty:ty+64, tx:tx+64]          # rows=g, cols=r
        lut[:, :, b, :] = tile.transpose(1, 0, 2)
    return lut

def apply(img, lut, strength=1.0):
    a = np.asarray(img.convert('RGB'), dtype=np.float32) / 255.0
    h, w, _ = a.shape
    p = a.reshape(-1, 3) * 63.0
    i0 = np.floor(p).astype(np.int32)
    f = p - i0
    i0 = np.clip(i0, 0, 63); i1 = np.clip(i0 + 1, 0, 63)
    r0, g0, b0 = i0[:, 0], i0[:, 1], i0[:, 2]
    r1, g1, b1 = i1[:, 0], i1[:, 1], i1[:, 2]
    fr, fg, fb = f[:, 0:1], f[:, 1:2], f[:, 2:3]
    c000 = lut[r0, g0, b0]; c100 = lut[r1, g0, b0]
    c010 = lut[r0, g1, b0]; c110 = lut[r1, g1, b0]
    c001 = lut[r0, g0, b1]; c101 = lut[r1, g0, b1]
    c011 = lut[r0, g1, b1]; c111 = lut[r1, g1, b1]
    c00 = c000*(1-fr) + c100*fr; c10 = c010*(1-fr) + c110*fr
    c01 = c001*(1-fr) + c101*fr; c11 = c011*(1-fr) + c111*fr
    c0 = c00*(1-fg) + c10*fg; c1 = c01*(1-fg) + c11*fg
    out = c0*(1-fb) + c1*fb
    out = a.reshape(-1, 3) * (1.0 - strength) + out * strength
    return Image.fromarray((np.clip(out, 0, 1).reshape(h, w, 3) * 255.0 + 0.5).astype(np.uint8))

if __name__ == '__main__':
    lut = load_lut(sys.argv[1])
    img = Image.open(sys.argv[2])
    strength = float(sys.argv[4]) if len(sys.argv) > 4 else 1.0
    apply(img, lut, strength).save(sys.argv[3], quality=95)
    print('wrote', sys.argv[3])
