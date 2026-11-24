package com.codex.leicaapp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Port of the 17 Ultra Moment App's FilmInput GLES tone stage.
 *
 * <p>The app runs this before the M3 parametric filter: a histogram-derived
 * gamma/white point/detail pass, a bilateral 5x5 smoothing, an optional 9x9
 * denoise, the film tone curve, vignette and the mono mix. It is M3-only in
 * the app; the M9 branch skips it.
 */
public final class FilmInput {

    private static final String TAG = "FilmInput";
    private static final String VERTEX =
            "attribute vec2 position; varying vec2 uv; void main(){uv=(position+1.)*.5;gl_Position=vec4(position,0.,1.);}";
    private static final String FRAGMENT =
            "precision highp float; varying vec2 uv; uniform sampler2D image; uniform vec2 texel; uniform float mono;uniform vec3 weights;uniform vec3 wb;uniform float warmInput;uniform float gamma;uniform float whitePoint;uniform float detailStrength;uniform float denoise;uniform vec2 tileOrigin;uniform vec2 tileSize;uniform vec2 fullSize;vec3 linearize(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}vec3 encode(vec3 c){return mix(c*12.92,1.055*pow(max(c,vec3(0.)),vec3(1./2.4))-.055,step(vec3(.0031308),c));}void main(){vec4 pixel=texture2D(image,uv); vec3 c=pixel.rgb;float bilateralRange=.0144;float bilateralMix=.25;if(warmInput>.5){bilateralRange=.0256;bilateralMix=.75;}vec3 sum=vec3(0.);float total=0.;for(int y=-2;y<=2;y++){for(int x=-2;x<=2;x++){vec3 n=texture2D(image,uv+vec2(float(x),float(y))*texel).rgb;vec3 d=n-c;float w=exp(-float(x*x+y*y)/2.88-dot(d,d)/bilateralRange);sum+=n*w;total+=w;}}c=mix(c,sum/total,bilateralMix);if(denoise>.001){vec3 accum=vec3(0.);float count=0.;vec3 guide=vec3(.25,.65,.10);for(int j=-4;j<=4;j++){for(int i=-4;i<=4;i++){vec3 n=texture2D(image,uv+vec2(float(i),float(j))*texel).rgb;vec3 diff=n-c;float dy=dot(diff,guide);vec3 chroma=diff-vec3(dy);float w=exp(-float(i*i+j*j)/10.-dy*dy/.0036-dot(chroma,chroma)/.08);accum+=n*w;count+=w;}}vec3 filtered=accum/max(count,.000001);float cy=dot(c,guide),fy=dot(filtered,guide);c=vec3(mix(cy,fy,.25*denoise))+mix(c-vec3(cy),filtered-vec3(fy),.98*denoise);}vec3 rgb=linearize(c);float y=dot(rgb,weights);float l=encode(vec3(y)).r;float detail=encode(vec3(dot(linearize(pixel.rgb),weights))).r-l;float stretched=.65+(l-.65)*.35/max(.20,whitePoint-.65);l=mix(l,min(1.,stretched),smoothstep(.65,.85,l));float mask=smoothstep(.06,.25,l)*(1.-smoothstep(.65,.94,l));vec2 p=((tileOrigin+uv*tileSize)/fullSize-.5)*2.;float center=pow(max(0.,1.-dot(p,p)),2.);l*=1.+.12*center*mask;mask=smoothstep(.06,.25,l)*(1.-smoothstep(.65,.94,l));float base=mix(pow(l,1.12),l,smoothstep(.035,.16,l));float t=base+(pow(l,1./gamma)-l)*mask;t+=0.*detailStrength;if(warmInput>.5)t=encode(vec3(y)).r;float gain=min(linearize(vec3(clamp(t,0.,1.))).r/max(y,.000001),1./max(max(rgb.r,max(rgb.g,rgb.b)),.000001));rgb*=gain;float before=dot(rgb,weights);float neutral=1.-smoothstep(.015,.12,max(rgb.r,max(rgb.g,rgb.b))-min(rgb.r,min(rgb.g,rgb.b)));float rg=rgb.g/max(rgb.r,.000001);float skin=smoothstep(.3,.55,rg)*(1.-smoothstep(.9,1.,rg))*step(rgb.b,rgb.g)*step(rgb.g,rgb.r);vec3 balanced=rgb*mix(vec3(1.),mix(wb,vec3(1.),max(.65*neutral,.45*skin)),smoothstep(.01,.08,before)*(1.-smoothstep(.75,.98,before)));balanced*=before/max(dot(balanced,weights),.000001);balanced/=max(1.,max(balanced.r,max(balanced.g,balanced.b)));c=encode(balanced);if(mono>.5)c=vec3(dot(c,vec3(.46,.48,.06)));gl_FragColor=vec4(clamp(c,0.,1.),pixel.a);}";
    private static final int TILE = 1024;
    private static final int HALO = 4;

    private FilmInput() {
    }

    public static Bitmap apply(Bitmap bitmap, boolean m3, float iso) throws Throwable {
        int[] histogram = histogram(bitmap);
        float gamma = FilmTone.gamma(histogram, iso);
        float white = FilmTone.whitePoint(histogram);
        float detail = FilmTone.detailStrength(histogram, iso);
        float denoise = FilmTone.denoise(iso);
        Log.i(TAG, "gamma=" + gamma + " white=" + white + " detail=" + detail
                + " denoise=" + denoise + " iso=" + iso);
        return render(bitmap, m3, gamma, white, detail, new float[]{1f, 1f, 1f},
                denoise, false);
    }

    private static int[] histogram(Bitmap bitmap) {
        float scale = Math.min(1f, 128f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        int w = Math.max(1, Math.round(bitmap.getWidth() * scale));
        int h = Math.max(1, Math.round(bitmap.getHeight() * scale));
        Bitmap small = Bitmap.createScaledBitmap(bitmap, w, h, false);
        try {
            ByteBuffer buffer = ByteBuffer.allocate(small.getByteCount());
            small.copyPixelsToBuffer(buffer);
            boolean p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
                    .equals(small.getColorSpace());
            int[] hist = new int[256];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int offset = y * small.getRowBytes() + x * 4;
                    float r = (buffer.get(offset) & 255) / 255f;
                    float g = (buffer.get(offset + 1) & 255) / 255f;
                    float b = (buffer.get(offset + 2) & 255) / 255f;
                    int luma = Math.max(0, Math.min(255,
                            Math.round(FilmTone.luma(r, g, b, p3) * 255f)));
                    hist[luma]++;
                }
            }
            return hist;
        } finally {
            if (small != bitmap) {
                small.recycle();
            }
        }
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != 0) {
            return shader;
        }
        String log = GLES20.glGetShaderInfoLog(shader);
        GLES20.glDeleteShader(shader);
        throw new IllegalStateException(log);
    }

    private static Bitmap render(Bitmap src, boolean mono, float gamma, float white,
                                 float detail, float[] wb, float denoise,
                                 boolean warmInput) throws Throwable {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("no EGL display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new IllegalStateException("EGL init");
        }
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        int[] configAttributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE};
        if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0)
                || count[0] == 0) {
            throw new IllegalStateException("EGL config");
        }
        EGLContext context = EGL14.eglCreateContext(display, configs[0],
                EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{EGL14.EGL_WIDTH, TILE + HALO * 2,
                        EGL14.EGL_HEIGHT, TILE + HALO * 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE
                || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw new IllegalStateException("EGL current");
        }
        int program = 0;
        int texture = 0;
        Bitmap out = null;
        try {
            int vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX);
            int fragment = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            }
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            GLES20.glUseProgram(program);

            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "wb"),
                    wb[0], wb[1], wb[2]);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "denoise"), denoise);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "warmInput"),
                    warmInput ? 1f : 0f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "mono"),
                    mono ? 1f : 0f);
            boolean p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
                    .equals(src.getColorSpace());
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "weights"),
                    p3 ? 0.22897457f : 0.2126f,
                    p3 ? 0.69173855f : 0.7152f,
                    p3 ? 0.07928691f : 0.0722f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "gamma"), gamma);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "whitePoint"), white);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "detailStrength"),
                    detail);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "fullSize"),
                    src.getWidth(), src.getHeight());
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0);

            FloatBuffer quad = ByteBuffer.allocateDirect(32)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f}).position(0);
            int position = GLES20.glGetAttribLocation(program, "position");
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad);

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            texture = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glDisable(GLES20.GL_DITHER);

            ColorSpace cs = src.getColorSpace() != null
                    ? src.getColorSpace() : ColorSpace.get(ColorSpace.Named.SRGB);
            out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                    Bitmap.Config.ARGB_8888, false, cs);
            Canvas canvas = new Canvas(out);
            ByteBuffer pixels = ByteBuffer.allocateDirect(
                    (TILE + HALO * 2) * (TILE + HALO * 2) * 4)
                    .order(ByteOrder.nativeOrder());
            for (int y = 0; y < src.getHeight(); y += TILE) {
                for (int x = 0; x < src.getWidth(); x += TILE) {
                    int tw = Math.min(TILE, src.getWidth() - x);
                    int th = Math.min(TILE, src.getHeight() - y);
                    int hx = Math.max(0, x - HALO);
                    int hy = Math.max(0, y - HALO);
                    int hw = Math.min(src.getWidth(), x + tw + HALO) - hx;
                    int hh = Math.min(src.getHeight(), y + th + HALO) - hy;
                    Bitmap tile = Bitmap.createBitmap(src, hx, hy, hw, hh);
                    try {
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, tile, 0);
                    } finally {
                        if (tile != src) {
                            tile.recycle();
                        }
                    }
                    GLES20.glViewport(0, 0, hw, hh);
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "texel"),
                            1f / hw, 1f / hh);
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "tileOrigin"),
                            hx, hy);
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "tileSize"),
                            hw, hh);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    pixels.clear();
                    GLES20.glReadPixels(x - hx, y - hy, tw, th,
                            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
                    if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                        throw new IllegalStateException("FilmInput GPU pass");
                    }
                    Bitmap tileOut = Bitmap.createBitmap(tw, th,
                            Bitmap.Config.ARGB_8888, false, cs);
                    try {
                        pixels.position(0);
                        tileOut.copyPixelsFromBuffer(pixels);
                        canvas.drawBitmap(tileOut, x, y, null);
                    } finally {
                        tileOut.recycle();
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            if (out != null) {
                out.recycle();
            }
            throw t;
        } finally {
            if (texture != 0) {
                GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program);
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface);
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context);
            }
            EGL14.eglReleaseThread();
        }
    }
}
