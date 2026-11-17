package io.martin.hypercameramod.originalportrait;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/**
 * Parameters for Original Portrait rendering.
 * 
 * Matches the donor plugin tags from libAncHumanPreviewBokeh.so:
 * - 0x10000: f_number (aperture)
 * - 0x10001: focus_rect (normalized focus region)
 * - 0x10002: bokeh_style (0-4)
 * - 0x10003: af_code (autofocus code)
 * - 0x10004: zoom_rate (digital zoom)
 * - 0x10005: camera_role (0=main, 1=tele, 2=ultra-wide)
 * - 0x10006: focal_range
 * - 0x10007: is_auto_f_number
 * - 0x10008: af_code_bypass
 * - 0x10009: is_static_scene
 * - 0x1000A: motion_speed
 * - 0x1000B: bypass
 * - 0x1000C: batch_params
 */
public class OriginalPortraitParams implements Parcelable {
    
    // Bokeh style constants (matching donor plugin)
    public static final int STYLE_NATURAL = 0;      // Natural gaussian-like falloff
    public static final int STYLE_SMOOTH = 1;       // Smooth soft edge
    public static final int STYLE_SWIRLY = 2;       // Swirly/spiral bokeh
    public static final int STYLE_CATSEYE = 3;      // Catseye (vignetted)
    public static final int STYLE_POLYGON = 4;      // Polygon (heptagon) aperture
    
    // Camera role constants
    public static final int ROLE_MAIN = 0;          // Main camera (~23mm)
    public static final int ROLE_TELE = 1;          // Telephoto (~75mm)
    public static final int ROLE_ULTRA_WIDE = 2;    // Ultra-wide (~13mm)
    public static final int ROLE_FRONT = 3;         // Front camera
    public static final int ROLE_MACRO = 4;         // Macro
    public static final int ROLE_DEPTH = 5;         // Depth sensor
    
    // Fields
    public float fNumber = 2.0f;                    // Aperture (1.4-16.0)
    public float focalLength = 35.0f;               // Equivalent focal length (mm)
    public float[] focusRect = {0.5f, 0.5f, 0.5f, 0.5f}; // [cx, cy, w, h] normalized [0,1]
    public int bokehStyle = STYLE_NATURAL;          // Bokeh style (0-4)
    public float zoomRate = 1.0f;                   // Digital zoom factor
    public int cameraRole = ROLE_MAIN;              // Camera role
    public boolean isAutoFNumber = false;           // Auto aperture
    public boolean isStaticScene = false;           // Static scene optimization
    public float motionSpeed = 1.0f;                // Motion blur factor
    public boolean bypass = false;                  // Bypass bokeh entirely
    
    // Constructors
    public OriginalPortraitParams() {}
    
    public OriginalPortraitParams(float fNumber, float focalLength, float[] focusRect,
                                   int bokehStyle, float zoomRate, int cameraRole,
                                   boolean isAutoFNumber, boolean isStaticScene,
                                   float motionSpeed, boolean bypass) {
        this.fNumber = fNumber;
        this.focalLength = focalLength;
        this.focusRect = focusRect != null ? focusRect.clone() : new float[]{0.5f, 0.5f, 0.5f, 0.5f};
        this.bokehStyle = bokehStyle;
        this.zoomRate = zoomRate;
        this.cameraRole = cameraRole;
        this.isAutoFNumber = isAutoFNumber;
        this.isStaticScene = isStaticScene;
        this.motionSpeed = motionSpeed;
        this.bypass = bypass;
    }
    
    // Copy constructor
    public OriginalPortraitParams(@NonNull OriginalPortraitParams other) {
        this.fNumber = other.fNumber;
        this.focalLength = other.focalLength;
        this.focusRect = other.focusRect.clone();
        this.bokehStyle = other.bokehStyle;
        this.zoomRate = other.zoomRate;
        this.cameraRole = other.cameraRole;
        this.isAutoFNumber = other.isAutoFNumber;
        this.isStaticScene = other.isStaticScene;
        this.motionSpeed = other.motionSpeed;
        this.bypass = other.bypass;
    }
    
    // Parcelable implementation
    @Override
    public int describeContents() {
        return 0;
    }
    
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeFloat(fNumber);
        dest.writeFloat(focalLength);
        dest.writeFloatArray(focusRect);
        dest.writeInt(bokehStyle);
        dest.writeFloat(zoomRate);
        dest.writeInt(cameraRole);
        dest.writeByte((byte) (isAutoFNumber ? 1 : 0));
        dest.writeByte((byte) (isStaticScene ? 1 : 0));
        dest.writeFloat(motionSpeed);
        dest.writeByte((byte) (bypass ? 1 : 0));
    }
    
    public static final Creator<OriginalPortraitParams> CREATOR = new Creator<OriginalPortraitParams>() {
        @Override
        public OriginalPortraitParams createFromParcel(Parcel in) {
            return new OriginalPortraitParams(in);
        }
        
        @Override
        public OriginalPortraitParams[] newArray(int size) {
            return new OriginalPortraitParams[size];
        }
    };
    
    private OriginalPortraitParams(Parcel in) {
        fNumber = in.readFloat();
        focalLength = in.readFloat();
        focusRect = in.createFloatArray();
        bokehStyle = in.readInt();
        zoomRate = in.readFloat();
        cameraRole = in.readInt();
        isAutoFNumber = in.readByte() != 0;
        isStaticScene = in.readByte() != 0;
        motionSpeed = in.readFloat();
        bypass = in.readByte() != 0;
    }
    
    // Utility methods
    public void computeFocalLengthFromRole() {
        switch (cameraRole) {
            case ROLE_MAIN:
                focalLength = 23.0f * zoomRate;
                break;
            case ROLE_TELE:
                focalLength = 75.0f * zoomRate;
                break;
            case ROLE_ULTRA_WIDE:
                focalLength = 13.0f * zoomRate;
                break;
            case ROLE_FRONT:
                focalLength = 22.0f * zoomRate;
                break;
            default:
                focalLength = 35.0f * zoomRate;
                break;
        }
    }
    
    @NonNull
    @Override
    public String toString() {
        return "OriginalPortraitParams{" +
                "fNumber=" + fNumber +
                ", focalLength=" + focalLength +
                ", focusRect=[" + focusRect[0] + "," + focusRect[1] + "," + focusRect[2] + "," + focusRect[3] + "]" +
                ", bokehStyle=" + bokehStyle +
                ", zoomRate=" + zoomRate +
                ", cameraRole=" + cameraRole +
                ", isAutoFNumber=" + isAutoFNumber +
                ", isStaticScene=" + isStaticScene +
                ", motionSpeed=" + motionSpeed +
                ", bypass=" + bypass +
                '}';
    }
    
    // Validation
    public boolean isValid() {
        return fNumber >= 1.0f && fNumber <= 16.0f &&
               focalLength > 0.0f &&
               focusRect != null && focusRect.length == 4 &&
               bokehStyle >= 0 && bokehStyle <= 4 &&
               zoomRate >= 0.1f && zoomRate <= 10.0f;
    }
}