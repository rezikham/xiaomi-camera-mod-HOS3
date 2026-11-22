package io.martin.hypercameramod.filters;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The bundled Leica LG1056 label face.
 *
 * <p>Both weights ship in the module. Labels use Regular; Light is loaded on demand for future
 * variants. Glyphs are rasterised at {@link #CAP_HEIGHT} so the existing label layout keeps its
 * zero-sidebearing bitmap placement, natural widths and translucent outline.
 */
final class LabelFont {
    private static final String TAG = "HyperFilmFilters";
    private static final String REGULAR_ASSET = "fonts/lg1056_regular.otf";
    private static final String LIGHT_ASSET = "fonts/lg1056_light.otf";

    // Camera assets are only a fallback if the bundled copy cannot be read.
    private static final String[] CAMERA_PATHS = {
            "watermarks/leica/111/lg1056_regular.otf",
            "watermarks/leica/92/lg1056_regular.otf",
            "watermarks/leica/90/lg1056_regular.otf",
            "watermarks/leica/88/lg1056_regular.otf"
    };

    /** Height of every cached glyph, in pixels. */
    static final int CAP_HEIGHT = 66;

    /** Hyphen thickness at {@link #CAP_HEIGHT}, in pixels (the width of "I"). */
    static final float STROKE = 10f;

    private static final Map<Character, Bitmap> CACHE = new HashMap<>();
    private static Application app;
    private static Typeface regular;
    private static Typeface light;
    private static float regularTextSize;
    private static float lightTextSize;

    private LabelFont() {
    }

    static synchronized void init(Application application) {
        if (application != null) {
            app = application;
        }
        if (app == null) {
            app = ModuleMain.currentApplication();
        }
        if (regular == null) {
            regular = load(app, false);
            regularTextSize = textSizeFor(regular, CAP_HEIGHT);
        }
    }

    /** Regular is used for labels; Light is available for a future thin variant. */
    static synchronized Typeface typeface(boolean lightWeight) {
        if (app == null) {
            app = ModuleMain.currentApplication();
        }
        if (regular == null) {
            init(app);
        }
        if (lightWeight) {
            if (light == null) {
                light = load(app, true);
                lightTextSize = textSizeFor(light, CAP_HEIGHT);
            }
            return light;
        }
        return regular;
    }

    /** Text size whose cap height is {@code capPixels}. */
    static synchronized float textSize(float capPixels) {
        if (regular == null) {
            init(app);
        }
        return regularTextSize * capPixels / CAP_HEIGHT;
    }

    static synchronized Bitmap glyph(char c) {
        Bitmap cached = CACHE.get(c);
        if (cached != null) {
            return cached;
        }
        if (regular == null) {
            init(app);
        }
        Bitmap bitmap = rasterize(regular, regularTextSize, c);
        if (bitmap == null) {
            Typeface fallback = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            bitmap = rasterize(fallback, textSizeFor(fallback, CAP_HEIGHT), c);
        }
        if (bitmap != null) {
            CACHE.put(c, bitmap);
        }
        return bitmap;
    }

    private static Bitmap rasterize(Typeface typeface, float textSize, char c) {
        if (typeface == null || textSize <= 0f) {
            return null;
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(typeface);
        paint.setTextSize(textSize);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);

        Path path = new Path();
        paint.getTextPath(String.valueOf(c), 0, 1, 0f, 0f, path);
        RectF bounds = new RectF();
        path.computeBounds(bounds, false);
        if (bounds.isEmpty()) {
            return null;
        }

        int width = Math.max(1, (int) Math.ceil(bounds.width()));
        int height = Math.max(1, (int) Math.ceil(bounds.height()));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(-bounds.left, -bounds.top);
        canvas.drawPath(path, paint);
        return bitmap;
    }

    private static float textSizeFor(Typeface typeface, float capPixels) {
        if (typeface == null) {
            return capPixels / 0.805f;
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(typeface);
        paint.setTextSize(100f);
        Path path = new Path();
        paint.getTextPath("H", 0, 1, 0f, 0f, path);
        RectF bounds = new RectF();
        path.computeBounds(bounds, false);
        float cap = bounds.height();
        if (cap <= 1f) {
            cap = 80.5f;
        }
        return 100f * capPixels / cap;
    }

    private static Typeface load(Application application, boolean lightWeight) {
        String asset = lightWeight ? LIGHT_ASSET : REGULAR_ASSET;

        Typeface typeface = loadBundled(application, asset);
        if (typeface != null) {
            Log.i(TAG, "label font: bundled " + asset);
            return typeface;
        }

        if (!lightWeight && application != null) {
            for (String path : CAMERA_PATHS) {
                try {
                    typeface = Typeface.createFromAsset(application.getAssets(), path);
                    Log.i(TAG, "label font: camera " + path);
                    return typeface;
                } catch (Throwable ignored) {
                }
            }
        }

        Log.w(TAG, "label font: falling back to sans-serif-condensed");
        return Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    }

    private static Typeface loadBundled(Application application, String asset) {
        if (application == null) {
            return null;
        }
        File file = new File(application.getCacheDir(), asset.replace('/', '_'));
        try (InputStream in = openModuleAsset(asset)) {
            if (in == null) {
                return null;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) > 0) {
                    out.write(buffer, 0, count);
                }
            }
            return Typeface.createFromFile(file);
        } catch (Throwable t) {
            Log.w(TAG, "cannot load bundled " + asset + ": " + t);
            return null;
        }
    }

    private static InputStream openModuleAsset(String asset) {
        // The APK entry includes the assets/ prefix; the AssetManager path used for the camera
        // fallback does not.
        String entryName = "assets/" + asset;
        ClassLoader loader = LabelFont.class.getClassLoader();
        if (loader != null) {
            InputStream in = loader.getResourceAsStream(entryName);
            if (in != null) {
                return in;
            }
        }
        InputStream in = LabelFont.class.getResourceAsStream("/" + entryName);
        if (in != null) {
            return in;
        }

        // Last resort: read the module APK directly.
        try {
            File apk = new File(LabelFont.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            final ZipFile zip = new ZipFile(apk);
            ZipEntry entry = zip.getEntry(entryName);
            if (entry != null) {
                return new FilterInputStream(zip.getInputStream(entry)) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            zip.close();
                        }
                    }
                };
            }
            zip.close();
        } catch (Throwable ignored) {
        }
        return null;
    }
}
