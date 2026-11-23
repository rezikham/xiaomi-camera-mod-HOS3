package io.martin.hypercameramod.originalportrait;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Original Portrait Color Profile.
 * 
 * Applies the characteristic Original Portrait color science
 * derived from 17 Ultra / 18 Pro Max donor captures.
 * 
 * Two modes supported:
 * 1. Parametric (ColorMatrix-based) - Fast, adjustable
 * 2. 3D LUT - Accurate, for complex transforms
 */
public class ColorProfile {
    private static final String TAG = "OriginalPortraitColor";
    
    // Profile assets
    private static final String PARAMETRIC_ASSET = "original_portrait_params.bin";
    private static final String LUT_ASSET = "original_portrait_lut.png"; // 512x512 = 32^3
    
    // Parametric model (16 params)
    // Based on libm9base.so parametric filter structure
    private float[] params = new float[16];
    
    // 3D LUT (optional)
    private Bitmap lutBitmap = null;
    private int lutSize = 64; // 64^3 = 262144 entries
    
    // Mode
    private boolean useLut = false;
    
    private ColorProfile() {}
    
    /**
     * Load color profile from assets.
     */
    public static @Nullable ColorProfile load(@NonNull Context context, @NonNull String profileName) {
        return load(context, profileName, null);
    }

    public static @Nullable ColorProfile load(@NonNull Context context, @NonNull String profileName,
                                              @Nullable String moduleApk) {
        ColorProfile profile = new ColorProfile();
        
        // Try parametric first
        if (profile.loadParametric(context, profileName)) {
            profile.useLut = false;
            Log.d(TAG, "Loaded parametric profile: " + profileName);
            return profile;
        }
        
        // Try LUT from the camera assets, then from the module APK.
        if (profile.loadLut(context, profileName)
                || profile.loadLutFromApk(moduleApk)) {
            profile.useLut = true;
            Log.d(TAG, "Loaded LUT profile: " + profileName);
            return profile;
        }
        
        // Default fallback
        profile.setDefaultParams();
        profile.useLut = false;
        Log.w(TAG, "Using default profile for: " + profileName);
        return profile;
    }
    
    /**
     * Load parametric parameters from asset.
     * File format: 16 floats (64 bytes)
     */
    private boolean loadParametric(@NonNull Context context, String profileName) {
        try (InputStream is = context.getAssets().open(PARAMETRIC_ASSET)) {
            byte[] data = new byte[is.available()];
            int read = is.read(data);
            if (read >= params.length * 4) {
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
                FloatBuffer fb = buf.asFloatBuffer();
                fb.get(params);
                return true;
            }
        } catch (IOException e) {
            Log.d(TAG, "Parametric asset not found: " + PARAMETRIC_ASSET);
        }
        return false;
    }
    
    /**
     * Load 3D LUT from asset.
     * Expected format: 512x512 PNG (32x32x32 = 32768 colors)
     * Layout: 32x32 grid of 32x32 slices
     */
    private boolean loadLut(@NonNull Context context, String profileName) {
        try (InputStream is = context.getAssets().open(LUT_ASSET)) {
            lutBitmap = android.graphics.BitmapFactory.decodeStream(is);
            if (lutBitmap != null && lutBitmap.getWidth() == 512 && lutBitmap.getHeight() == 512) {
                return true;
            }
        } catch (IOException e) {
            Log.d(TAG, "LUT asset not found: " + LUT_ASSET);
        }
        return false;
    }
    
    private boolean loadLutFromApk(@Nullable String apkPath) {
        if (apkPath == null) {
            return false;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkPath)) {
            java.util.zip.ZipEntry entry = zip.getEntry("assets/" + LUT_ASSET);
            if (entry == null) {
                Log.d(TAG, "LUT asset not in module APK: " + LUT_ASSET);
                return false;
            }
            try (InputStream is = zip.getInputStream(entry)) {
                lutBitmap = android.graphics.BitmapFactory.decodeStream(is);
            }
            return lutBitmap != null && lutBitmap.getWidth() == 512
                    && lutBitmap.getHeight() == 512;
        } catch (IOException e) {
            Log.d(TAG, "LUT asset not readable from module APK: " + e);
            return false;
        }
    }

    /**
     * Set default parametric values (identity-ish).
     * These should be replaced with fitted values from donor captures.
     */
    private void setDefaultParams() {
        // Parametric model (matching typical libm9base structure):
        // params[0]  = contrast (1.0 = neutral)
        // params[1]  = saturation (1.0 = neutral)
        // params[2]  = hue shift (degrees, 0 = neutral)
        // params[3]  = highlights (-1.0 to 1.0, 0 = neutral)
        // params[4]  = shadows (-1.0 to 1.0, 0 = neutral)
        // params[5]  = midtones (-1.0 to 1.0, 0 = neutral)
        // params[6]  = color temperature (K, 6500 = neutral)
        // params[7]  = tint (-1.0 to 1.0, 0 = neutral)
        // params[8]  = vibrance (0.0 to 1.0, 0 = neutral)
        // params[9]  = clarity (0.0 to 1.0, 0 = neutral)
        // params[10] = dehaze (0.0 to 1.0, 0 = neutral)
        // params[11] = grain amount (0.0 to 1.0)
        // params[12] = vignette amount (0.0 to 1.0)
        // params[13] = split tone highlight hue (0-360)
        // params[14] = split tone shadow hue (0-360)
        // params[15] = split tone balance (-1 to 1)
        
        params[0] = 1.02f;   // Slight contrast boost
        params[1] = 1.05f;   // Slight saturation boost
        params[2] = -2.0f;   // Slight warm hue shift
        params[3] = -0.1f;   // Lower highlights
        params[4] = 0.05f;   // Lift shadows slightly
        params[5] = 0.02f;   // Slight midtone lift
        params[6] = 6200f;   // Slightly warmer than 6500K
        params[7] = 0.02f;   // Slight magenta tint
        params[8] = 0.1f;    // Mild vibrance
        params[9] = 0.05f;   // Mild clarity
        params[10] = 0.0f;   // No dehaze
        params[11] = 0.02f;  // Subtle grain
        params[12] = 0.05f;  // Subtle vignette
        params[13] = 40.0f;  // Warm highlight split tone
        params[15] = 0.1f;   // Balance toward highlights
    }
    
    /**
     * Apply color profile to bitmap.
     */
    public @NonNull Bitmap apply(@NonNull Bitmap bitmap) {
        if (useLut && lutBitmap != null) {
            return applyLut(bitmap);
        } else {
            return applyParametric(bitmap);
        }
    }
    
    /**
     * Apply parametric color transform using ColorMatrix.
     */
    private @NonNull Bitmap applyParametric(@NonNull Bitmap src) {
        Bitmap dst = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(dst);
        Paint paint = new Paint();
        
        ColorMatrix cm = new ColorMatrix();
        
        // 1. Temperature/Tint (simplified)
        float temp = params[6]; // 6500K neutral
        float tint = params[7];
        applyTemperatureTint(cm, temp, tint);
        
        // 2. Contrast
        float contrast = params[0];
        applyContrast(cm, contrast);
        
        // 3. Saturation
        float saturation = params[1];
        applySaturation(cm, saturation);
        
        // 4. Hue shift
        float hueShift = params[2];
        applyHueShift(cm, hueShift);
        
        // 5. Highlights/Shadows/Midtones (lift/gamma/gain)
        applyLiftGammaGain(cm, params[4], params[5], params[3]);
        
        // 6. Vibrance (selective saturation)
        if (params[8] != 0.0f) {
            applyVibrance(cm, params[8]);
        }
        
        // 7. Clarity (local contrast approximation)
        if (params[9] != 0.0f) {
            applyClarity(cm, params[9]);
        }
        
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        
        // Note: Grain, vignette, split toning require pixel shaders
        // For full quality, use LUT or compute shader
        
        return dst;
    }
    
    // ColorMatrix helper methods
    private void applyTemperatureTint(ColorMatrix cm, float tempK, float tint) {
        // Simplified temperature/tint matrix
        // Full implementation would use Bradford transform
        float r = 1.0f, g = 1.0f, b = 1.0f;
        
        if (tempK < 6500) {
            // Warmer
            float factor = (6500 - tempK) / 6500.0f;
            r += factor * 0.1f;
            b -= factor * 0.1f;
        } else if (tempK > 6500) {
            // Cooler
            float factor = (tempK - 6500) / 6500.0f;
            r -= factor * 0.1f;
            b += factor * 0.1f;
        }
        
        // Tint
        if (tint > 0) {
            g += tint * 0.05f;
        } else {
            r -= tint * 0.05f;
            b -= tint * 0.05f;
        }
        
        // Apply to matrix (diagonal)
        float[] m = cm.getArray();
        m[0] *= r;    // R
        m[6] *= g;    // G
        m[12] *= b;   // B
        cm.set(m);
    }
    
    private void applyContrast(ColorMatrix cm, float contrast) {
        if (contrast == 1.0f) return;
        float[] m = cm.getArray();
        float c = contrast;
        float t = (1.0f - c) * 0.5f;
        
        // Scale and offset
        m[0] *= c; m[1] *= c; m[2] *= c; m[3] *= c;
        m[5] *= c; m[6] *= c; m[7] *= c; m[8] *= c;
        m[10] *= c; m[11] *= c; m[12] *= c; m[13] *= c;
        
        // Add offset
        m[4] += t; m[9] += t; m[14] += t; m[19] += t;
        cm.set(m);
    }
    
    private void applySaturation(ColorMatrix cm, float saturation) {
        if (saturation == 1.0f) return;
        cm.setSaturation(saturation);
    }
    
    private void applyHueShift(ColorMatrix cm, float degrees) {
        if (degrees == 0.0f) return;
        float rad = (float) Math.toRadians(degrees);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        
        float[] m = new float[20];
        // Hue rotation matrix
        m[0] = cos + (1 - cos) * 0.299f;
        m[1] = (1 - cos) * 0.587f - sin * 0.114f;
        m[2] = (1 - cos) * 0.114f + sin * 0.587f;
        m[5] = (1 - cos) * 0.299f + sin * 0.114f;
        m[6] = cos + (1 - cos) * 0.587f;
        m[7] = (1 - cos) * 0.114f - sin * 0.299f;
        m[10] = (1 - cos) * 0.299f - sin * 0.587f;
        m[11] = (1 - cos) * 0.587f + sin * 0.299f;
        m[12] = cos + (1 - cos) * 0.114f;
        m[15] = m[19] = 1.0f;
        
        cm.postConcat(new ColorMatrix(m));
    }
    
    private void applyLiftGammaGain(ColorMatrix cm, float lift, float gamma, float gain) {
        float[] m = cm.getArray();
        
        // Lift (shadows)
        if (lift != 0.0f) {
            m[4] += lift * 255.0f;
            m[9] += lift * 255.0f;
            m[14] += lift * 255.0f;
        }
        
        // Gain (highlights)
        if (gain != 0.0f) {
            float g = 1.0f + gain;
            m[0] *= g; m[1] *= g; m[2] *= g; m[3] *= g;
            m[5] *= g; m[6] *= g; m[7] *= g; m[8] *= g;
            m[10] *= g; m[11] *= g; m[12] *= g; m[13] *= g;
        }
        
        // Gamma (midtones) - approximate with power curve
        if (gamma != 0.0f) {
            // Gamma in ColorMatrix is limited; full gamma needs LUT
            // This is a rough approximation
            float g = 1.0f + gamma * 0.5f;
            m[0] *= g; m[1] *= g; m[2] *= g; m[3] *= g;
            m[5] *= g; m[6] *= g; m[7] *= g; m[8] *= g;
            m[10] *= g; m[11] *= g; m[12] *= g; m[13] *= g;
        }
        
        cm.set(m);
    }
    
    private void applyVibrance(ColorMatrix cm, float vibrance) {
        // Vibrance = selective saturation (protect skin tones)
        // Approximate: reduce saturation of already-saturated colors
        // Full implementation needs pixel shader
        float s = 1.0f + vibrance * 0.5f;
        cm.setSaturation(s);
    }
    
    private void applyClarity(ColorMatrix cm, float clarity) {
        // Clarity = local contrast
        // Approximate with midtone contrast boost
        if (clarity != 0.0f) {
            float c = 1.0f + clarity * 0.3f;
            float t = (1.0f - c) * 0.5f;
            float[] m = cm.getArray();
            m[0] *= c; m[1] *= c; m[2] *= c; m[3] *= c;
            m[5] *= c; m[6] *= c; m[7] *= c; m[8] *= c;
            m[10] *= c; m[11] *= c; m[12] *= c; m[13] *= c;
            m[4] += t * 255; m[9] += t * 255; m[14] += t * 255;
            cm.set(m);
        }
    }
    
    /**
     * Apply 3D LUT.
     */
    private @NonNull Bitmap applyLut(@NonNull Bitmap src) {
        if (lutBitmap == null) return src;

        final int w = src.getWidth();
        final int h = src.getHeight();
        final int[] in = new int[w * h];
        final int[] out = new int[w * h];
        src.getPixels(in, 0, w, 0, 0, w, h);

        final int[] lut = new int[512 * 512];
        lutBitmap.getPixels(lut, 0, 512, 0, 0, 512, 512);

        for (int i = 0; i < in.length; i++) {
            int color = in[i];
            float r = ((color >> 16) & 0xff) / 255f;
            float g = ((color >> 8) & 0xff) / 255f;
            float b = (color & 0xff) / 255f;

            float rs = r * 63f;
            float gs = g * 63f;
            float bs = b * 63f;
            int r0 = Math.min(63, (int) rs);
            int g0 = Math.min(63, (int) gs);
            int b0 = Math.min(63, (int) bs);
            int r1 = Math.min(63, r0 + 1);
            int g1 = Math.min(63, g0 + 1);
            int b1 = Math.min(63, b0 + 1);
            float rt = rs - r0;
            float gt = gs - g0;
            float bt = bs - b0;

            int c000 = lutAt(lut, b0, r0, g0);
            int c100 = lutAt(lut, b0, r1, g0);
            int c010 = lutAt(lut, b0, r0, g1);
            int c110 = lutAt(lut, b0, r1, g1);
            int c001 = lutAt(lut, b1, r0, g0);
            int c101 = lutAt(lut, b1, r1, g0);
            int c011 = lutAt(lut, b1, r0, g1);
            int c111 = lutAt(lut, b1, r1, g1);

            int c00 = lerpColor(c000, c100, rt);
            int c01 = lerpColor(c010, c110, rt);
            int c0 = lerpColor(c00, c01, gt);
            int c10 = lerpColor(c001, c101, rt);
            int c11 = lerpColor(c011, c111, rt);
            int c1 = lerpColor(c10, c11, gt);
            int rgb = lerpColor(c0, c1, bt);

            out[i] = (color & 0xff000000) | (rgb & 0x00ffffff);
        }

        Bitmap dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        dst.setPixels(out, 0, w, 0, 0, w, h);
        return dst;
    }

    private static int lutAt(int[] lut, int blue, int red, int green) {
        int tileX = (blue & 7) * 64;
        int tileY = (blue >> 3) * 64;
        return lut[(tileY + green) * 512 + tileX + red];
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }
    
    // Getters
    public float[] getParams() { return params.clone(); }
    public void setParams(@NonNull float[] newParams) { 
        System.arraycopy(newParams, 0, params, 0, Math.min(params.length, newParams.length));
    }
    public boolean isUsingLut() { return useLut; }
    public int getLutSize() { return lutSize; }
    
    /**
     * Export current params to asset format.
     */
    public byte[] toByteArray() {
        ByteBuffer buf = ByteBuffer.allocate(params.length * 4).order(ByteOrder.nativeOrder());
        buf.asFloatBuffer().put(params);
        return buf.array();
    }
}