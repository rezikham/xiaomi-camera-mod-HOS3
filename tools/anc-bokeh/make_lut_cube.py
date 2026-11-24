#!/usr/bin/env python3
"""Convert the 18 PM Original portrait 512x512 LUT PNG to a .cube file."""
from PIL import Image
import numpy as np
import sys

src = sys.argv[1] if len(sys.argv) > 1 else 'src/main/assets/original_portrait_lut.png'
out = sys.argv[2] if len(sys.argv) > 2 else 'original_portrait.cube'
a = np.asarray(Image.open(src).convert('RGB'))
assert a.shape == (512, 512, 3), a.shape
with open(out, 'w') as f:
    f.write('TITLE "18PM Original Portrait"\nLUT_3D_SIZE 64\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n')
    for b in range(64):
        tx = (b & 7) * 64
        ty = (b >> 3) * 64
        for g in range(64):
            row = a[ty + g, tx:tx + 64, :]
            for r in range(64):
                R, G, B = row[r]
                f.write(f'{R/255:.6f} {G/255:.6f} {B/255:.6f}\n')
print(out)
