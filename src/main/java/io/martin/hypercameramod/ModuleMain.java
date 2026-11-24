package io.martin.hypercameramod;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedModule;

// Turns on three things the camera already contains but leaves off for this phone:
//  1. the Palette and Parameters filter panel (it needs the system property
//     camera.feature.styleVersion to be 2 or more, which the camera reads through
//     android.os.SystemProperties),
//  2. the "Original" colour style next to Leica Authentic and Leica Vibrant (bit 3 of the camera
//     characteristic xiaomi.capabilities.stylizationTypeMask, which this phone's camera hardware
//     layer does not set),
//  3. the Leica Essential CMOS (M10R) style, added when m10r_enabled is on. The 14 Ultra has
//     no M10R HAL, so the module post-processes M10R captures with the Essential Moments M10R
//     bin plus B12/B43; the 18 Pro Max neural M10R style model is not available here.
// Each part is independent; one that does not fit the camera build is skipped and logged.
public class ModuleMain extends XposedModule {
    private static final String TAG = "HyperCameraMod";
    private static final String CAMERA_PACKAGE = "com.android.camera";

    // 1. Palette and Parameters
    private static final String STYLE_VERSION_KEY = "camera.feature.styleVersion";
    private static final int STYLE_VERSION = 2;

    // 2. Original colour style
    private static final String STYLIZATION_KEY = "xiaomi.capabilities.stylizationTypeMask";
    private static final int STYLIZATION_ORIGINAL = 1 << 3;
    private static final String LEGEND_MODE_KEY = "com.xiaomi.sessionparams.legendMode";
    private static final String STYLIZATION_TYPE_KEY = "com.xiaomi.sessionparams.stylizationType";
    private static final ThreadLocal<Byte> BRIDGE_LEGEND = new ThreadLocal<>();
    // Last Legendary style seen in a request: 1 = M9, 2 = M3. Used by the LUT fallback and by
    // the optional Essential pipeline. The timestamp lets the pipeline ignore a stale selection.
    private static volatile byte lastLegend;
    private static volatile long lastLegendTime;
    private static final ThreadLocal<Boolean> BRIDGE_SETTING = ThreadLocal.withInitial(() -> false);

    // 3. M10R. The Leica Essential style list builds M3 (B&W) and M9 (CCD); a profile names where
    // that list and its item class live in one camera layout.
    private record LegendProfile(String name, String listClass, String itemClass,
                                 String stringIds, String imageIds) {
    }

    private static final LegendProfile[] LEGEND_PROFILES = {
            // Camera 6.7 (HyperOS 4): 6.7.000280.0 (tested), 6.7.000070.0 (has the item already).
            new LegendProfile("6.7", "s2.A", "com.android.camera.data.data.d", "ci.e", "ci.b"),
    };
    // The photography style picker (Leica Authentic, Leica Vibrant, and Original once its bit is
    // set). The Leica UI draws each as a badge from a mapping that knows only two values, so
    // Original comes out as the Authentic badge; the module swaps in its own "ORIG" badge.
    private record StyleProfile(String name, String listClass, String itemClass,
                                String currentIconMethod, String valueIconMethod) {
    }

    private static final StyleProfile[] STYLE_PROFILES = {
            new StyleProfile("6.7", "s2.m", "com.android.camera.data.data.d", "o", "r"),
    };
    private static final String STYLE_ORIGINAL = "2";
    private static final String STYLE_AUTHENTIC = "1";
    private static final String STYLE_MASTER = "0";
    // In Portrait the same component lists Original, Master and Leica. Original portrait runs the
    // algorithm-up SAT preview session, whose Xiaomi preview-bokeh engine never returns a frame on
    // the 14 Ultra hardware layer: the camera waits on a semaphore for 5780 ms, the preview drops
    // to about 0.15 fps and the vendor camera provider eventually aborts. Photo mode keeps the
    // Original colour style. On the phones whose hardware layer implements the Original portrait
    // preview (17 Ultra, 18 Pro Max) the style stays in the list; elsewhere it is removed and the
    // settings toggle decides (see originalPortraitEnabled()).
    private static final int MODE_PORTRAIT = 171;
    private static final String ORIGINAL_PORTRAIT_KEY = "original_portrait_enabled";
    private static final String COMPAT_FALLBACK_KEY = "compat_fallback_enabled";
    // Genuine Moment App M3/M9 pipeline. Off by default. Runs all native work in the
    // module's private :essential process (EssentialService), never in the camera process.
    private static final String ESSENTIAL_PIPELINE_KEY = "essential_pipeline_enabled";
    private static final String ESSENTIAL_PIPELINE_PROP = "camera.feature.essential_pipeline";
    private static final long ESSENTIAL_LEGEND_FRESH_MS = 180_000;
    // The stock filter node (com.xiaomi.plugin.filter / FilterInstance0) renders the look itself
    // when the camera hands it the render payload. The camera only does that when
    // camera.debug.mivi3.output.jpeg is true; otherwise it renders in the app and the HAL LUT is
    // ignored. The gate is read once by the camera process, so the module can answer it here
    // without root. The LUT file still lives in /data/vendor/camera and is installed by
    // tools/hal-port/engine/leica_look.sh.
    private static final String HAL_GATE_KEY = "camera.debug.mivi3.output.jpeg";
    private static final String HAL_LUT_SLOT_KEY = "hal_lut_slot_enabled";
    private static final String ORIGINAL_PORTRAIT_PROP = "camera.feature.original_portrait";
    private static final int ASSET_ORIGINAL_BADGE = 3;
    private static volatile android.content.pm.ApplicationInfo moduleInfo;

    private static final String M10R = "M10R";
    private static final String M10R_ENABLE_KEY = "camera.feature.m10r";
    // The item's three images are not in builds that drop the item, so the module carries them: a
    // made-up resource id (this base plus an index into M10R_ASSETS) that ImageView.setImageResource
    // and Resources.getDrawable turn into the file from the module's assets.
    private static final int ASSET_ICON_BASE = 0x7e010000;
    private static final int ASSET_TEXT_BASE = 0x7e020000;   // the CMOS title string
    private static final String[] M10R_ASSETS = {"template", "icon", "text_icon"};
    private static final Map<Integer, Bitmap> ASSET_CACHE = new HashMap<>();
    private static volatile String moduleApk;

    public static String getModuleApkPath() {
        return moduleApk;
    }

    // What did not work, shown to the user once per camera version.
    private static final String MSG_LEGEND =
            "The Leica Essential style list is different on this camera version, so the CMOS "
                    + "style was not added.";
    private final List<String> problems = new CopyOnWriteArrayList<>();
    private final AtomicBoolean noticeScheduled = new AtomicBoolean();

    private volatile boolean propertiesHooked;
    private volatile boolean tabsHooked;
    private volatile boolean modeListHooked;
    private volatile boolean nameStyleHooked;
    private volatile boolean stylizationHooked;
    private volatile boolean legendBridgeHooked;
    private volatile boolean legendObserverHooked;
    private volatile boolean legendHooked;
    private volatile boolean assetsHooked;
    private volatile boolean styleHooked;
    private volatile boolean textHooked;
    // -1 = not read yet, 0 = off, 1 = on. Read once per Camera process.
    private volatile int originalPortraitState = -1;
    // LUT files staged to /data/vendor/camera/ on first M3/M9 selection.
    private static volatile boolean lutFilesInstalled = false;
    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        install("onPackageLoaded", param.getPackageName(), param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        install("onPackageReady", param.getPackageName(), param.getClassLoader());
        if (CAMERA_PACKAGE.equals(param.getPackageName())) {
            scheduleNotice();
            installLutFileWatcher();
            // Stage LUT files at camera app startup (Option B: no KSU module needed).
            // Runs in camera process context with su; requires root granted to camera app.
            ensureLutFiles();
        }
    }

    private void install(String stage, String pkg, ClassLoader loader) {
        if (!CAMERA_PACKAGE.equals(pkg) || loader == null) {
            return;
        }
        try {
            moduleInfo = getModuleApplicationInfo();
            moduleApk = moduleInfo.sourceDir;
            io.martin.hypercameramod.originalportrait.BokehRenderer
                    .setNativeLibraryDir(moduleInfo.nativeLibraryDir);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot find the module APK: " + t);
        }
        log(Log.INFO, TAG, "HAL capabilities: " + HalCapabilities.summary());
        installPropertyHook();
        installTabHook();
        installModeListHook(loader);
        installNameStyleHook(loader);
        installStylizationHook();
        installLegendBridgeHook();
        installLegendObserver();
        installOriginalPortraitCaptureHook(loader);
        installLutFallbackHook(loader);
        installLutFileWatcher();
        installBlurRadiusHook(loader);
        installLegendHook(loader);
        installStyleHook(loader);
        installLeicaLooksEntries(loader);
        installFilterStripEntries(loader);
        if (!problems.isEmpty() && stage.equals("onPackageLoaded")) {
            // onPackageReady tries once more; report only what is still wrong after it
            problems.clear();
        }
    }

    // ---- 1. Palette and Parameters ----------------------------------------------------------

    // Stage Leica LUT files from module APK to /data/vendor/camera/ so the stock filter
    // node can render M3/M9. Requires root (su); fails silently if not rooted.
    // Writes bytes directly through su stdin — no temp files needed.
    private static void ensureLutFiles() {
        if (lutFilesInstalled) {
            return;
        }
        try {
            copyAssetToSystem("leica_luts/M3_Monopan.png", "/data/vendor/camera/lut_m3.png");
            copyAssetToSystem("leica_luts/130_LeicaNatural.png", "/data/vendor/camera/lut_m9.png");
            lutFilesInstalled = true;
            android.util.Log.i(TAG, "LUT files staged to /data/vendor/camera/");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "Failed to stage LUT files: " + t);
        }
    }

    private static void copyAssetToSystem(String assetName, String destPath) throws Exception {
        ZipFile zip = new ZipFile(moduleApk);
        ZipEntry entry = zip.getEntry("assets/" + assetName);
        if (entry == null) {
            throw new Exception("Asset not found: " + assetName);
        }
        InputStream is = zip.getInputStream(entry);
        byte[] data = is.readAllBytes();
        is.close();
        zip.close();

        // Base64 encode for safe transmission through su stdin
        String base64 = android.util.Base64.encodeToString(data, android.util.Base64.DEFAULT);

        // Copy to system location using su:
        //   su -c "base64 -d > /data/vendor/camera/lut_m3.png"
        // su runs the shell command with stdin connected to our OutputStream.
        // base64 reads from stdin (su's stdin), decodes, writes to destPath.
        Process su = Runtime.getRuntime().exec(new String[]{"su", "-c", "base64 -d > " + destPath});
        OutputStream os = su.getOutputStream();
        os.write(base64.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        os.flush();
        int exitCode = su.waitFor();

        if (exitCode != 0) {
            throw new Exception("su exit code: " + exitCode);
        }

        // Set permissions
        Process chmod = Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 0644 " + destPath});
        chmod.waitFor();
    }

    private void installPropertyHook() {
        if (propertiesHooked) {
            return;
        }
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            hook(props.getDeclaredMethod("getInt", String.class, int.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (STYLE_VERSION_KEY.equals(chain.getArg(0)) && (Integer) result < STYLE_VERSION) {
                    return STYLE_VERSION;
                }
                return result;
            });
            hook(props.getDeclaredMethod("get", String.class, String.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (STYLE_VERSION_KEY.equals(chain.getArg(0)) && parse(result) < STYLE_VERSION) {
                    return String.valueOf(STYLE_VERSION);
                }
                return result;
            });
            hook(props.getDeclaredMethod("get", String.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (STYLE_VERSION_KEY.equals(chain.getArg(0)) && parse(result) < STYLE_VERSION) {
                    return String.valueOf(STYLE_VERSION);
                }
                return result;
            });
            propertiesHooked = true;
            log(Log.INFO, TAG, "hooked SystemProperties (" + STYLE_VERSION_KEY + " >= "
                    + STYLE_VERSION + ")");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook SystemProperties: " + t);
        }
        installHalGateHook();
    }

    // The camera caches camera.debug.mivi3.output.jpeg in a static field on first use
    // (f16477e.B8() || Va.b.f17711X), so it must be answered before the app touches it. Hooking
    // the property reads gives the same effect as setprop, per module preference.
    private void installHalGateHook() {
        if (halGateHooked) {
            return;
        }
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            hook(props.getDeclaredMethod("getBoolean", String.class, boolean.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (HAL_GATE_KEY.equals(chain.getArg(0))
                        && !Boolean.TRUE.equals(result) && halLutSlotEnabled()) {
                    return true;
                }
                return result;
            });
            try {
                hook(props.getDeclaredMethod("getBoolean", String.class)).intercept(chain -> {
                    Object result = chain.proceed();
                    if (HAL_GATE_KEY.equals(chain.getArg(0))
                            && !Boolean.TRUE.equals(result) && halLutSlotEnabled()) {
                        return true;
                    }
                    return result;
                });
            } catch (NoSuchMethodException ignored) {
            }
            hook(props.getDeclaredMethod("get", String.class, String.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (HAL_GATE_KEY.equals(chain.getArg(0)) && halLutSlotEnabled()) {
                    return "true";
                }
                return result;
            });
            hook(props.getDeclaredMethod("get", String.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (HAL_GATE_KEY.equals(chain.getArg(0)) && halLutSlotEnabled()) {
                    return "true";
                }
                return result;
            });
            halGateHooked = true;
            log(Log.INFO, TAG, "hooked SystemProperties (" + HAL_GATE_KEY + " -> HAL when enabled)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook " + HAL_GATE_KEY + ": " + t);
        }
    }

    private volatile boolean halGateHooked;

    private boolean halLutSlotEnabled() {
        try {
            android.content.SharedPreferences p = getRemotePreferences("hypercameramod");
            if (p != null) {
                return p.getBoolean(HAL_LUT_SLOT_KEY, false);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot read " + HAL_LUT_SLOT_KEY + ": " + t);
        }
        return false;
    }

    private static int parse(Object value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- 1b. Width of the Palette / Parameters tabs --------------------------------------------

    // The tab bar of the filter edit panel is a fixed 120 dp wide (two 60 dp tabs), made for two
    // Chinese words; longer words in other languages are cut off. When the panel is inflated, the
    // bar is made as wide as its longer label needs, and the indicator that slides between the
    // tabs gets the same width as one tab (the camera places the indicator from the bar's width).
    private static final String TAB_BAR_ID = "filter_edit_tab_bar";
    private static final String TAB_INDICATOR_ID = "filter_edit_tab_indicator";
    private static final float TAB_MARGIN_DP = 10f;     // space left and right of the longer label
    private static final float TAB_INDICATOR_EXTRA_DP = 2f;   // the indicator is 2 dp wider than a tab
    private volatile int tabBarId = -1;
    private volatile int tabIndicatorId;
    private final AtomicBoolean tabFailureLogged = new AtomicBoolean();

    private void installTabHook() {
        if (tabsHooked) {
            return;
        }
        try {
            Method inflate = LayoutInflater.class.getDeclaredMethod("inflate", int.class,
                    ViewGroup.class, boolean.class);
            hook(inflate).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof View view) {
                    try {
                        widenTabs(view);
                    } catch (Throwable t) {
                        if (tabFailureLogged.compareAndSet(false, true)) {
                            log(Log.WARN, TAG, "cannot widen the filter edit tabs: " + t);
                        }
                    }
                }
                return result;
            });
            tabsHooked = true;
            log(Log.INFO, TAG, "hooked LayoutInflater.inflate (filter edit tab width)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook LayoutInflater.inflate: " + t);
        }
    }

    private void widenTabs(View root) {
        Resources res = root.getResources();
        if (tabBarId < 0) {
            tabIndicatorId = res.getIdentifier(TAB_INDICATOR_ID, "id", CAMERA_PACKAGE);
            tabBarId = res.getIdentifier(TAB_BAR_ID, "id", CAMERA_PACKAGE);
        }
        if (tabBarId == 0 || tabIndicatorId == 0) {
            return;   // this camera has no such panel
        }
        if (!(root.findViewById(tabBarId) instanceof ViewGroup bar)) {
            return;
        }
        View indicator = bar.findViewById(tabIndicatorId);
        ViewGroup.LayoutParams barParams = bar.getLayoutParams();
        if (indicator == null || barParams == null || indicator.getLayoutParams() == null) {
            return;
        }
        float density = res.getDisplayMetrics().density;
        int tab = barParams.width / 2;   // the camera's own tab width
        int labels = 0;
        for (int i = 0; i < bar.getChildCount(); i++) {
            if (bar.getChildAt(i) instanceof TextView label) {
                labels++;
                int need = (int) Math.ceil(label.getPaint().measureText(label.getText().toString()))
                        + label.getPaddingStart() + label.getPaddingEnd()
                        + Math.round(2 * TAB_MARGIN_DP * density);
                tab = Math.max(tab, need);
            }
        }
        if (labels != 2 || tab * 2 == barParams.width) {
            return;
        }
        barParams.width = tab * 2;
        bar.setLayoutParams(barParams);
        ViewGroup.LayoutParams indicatorParams = indicator.getLayoutParams();
        indicatorParams.width = tab + Math.round(TAB_INDICATOR_EXTRA_DP * density);
        indicator.setLayoutParams(indicatorParams);
        log(Log.INFO, TAG, "filter edit tabs widened to " + Math.round(tab * 2 / density) + " dp");
    }

    // ---- 1b2. Presets in the Leica UI's filter strip ---------------------------------------------

    // The Leica UI shows a filter's name as a picture (com.android.camera.fragment.G, "image name
    // style"). A preset the user saves has no name picture, so the strip asks that class to show
    // its text name, and the method for that is a placeholder in this camera that throws
    // "An operation is not implemented", which closes the camera whenever the strip reaches the
    // preset. Without the exception the preset is shown by its thumbnail only; its name goes into
    // the thumbnail's content description.
    private void installNameStyleHook(ClassLoader loader) {
        if (nameStyleHooked) {
            return;
        }
        try {
            Class<?> style = Class.forName("com.android.camera.fragment.G", false, loader);
            Method setName = style.getDeclaredMethod("h", String.class);
            Field image = null;
            for (Field f : style.getDeclaredFields()) {
                if (ImageView.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    image = f;
                }
            }
            Field imageField = image;
            hook(setName).intercept(chain -> {
                try {
                    if (imageField != null && imageField.get(chain.getThisObject()) instanceof ImageView view
                            && chain.getArg(0) instanceof String name) {
                        view.setContentDescription(name);
                    }
                } catch (Throwable ignored) {
                }
                return null;
            });
            nameStyleHooked = true;
            log(Log.INFO, TAG, "hooked " + style.getName() + ".h (preset names in the Leica UI)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook the Leica UI name style: " + t);
        }
    }

    // ---- 1c. Leica Essential in the standard UI's mode list --------------------------------------

    // The camera registers the Essential mode (256) only when its support() answers true, which
    // is a device feature that is off when the Leica customisation is off, so the standard UI has
    // no such mode. Unless camera.feature.essential_std is set to 0, support() answers true, mode 256
    // is added to the standard UI's mode order (v2.S.y), in front of "More" (254), and the check
    // that decides between the strip and "More" (v2.S.E) accepts it.
    private static final String ESSENTIAL_STD_KEY = "camera.feature.essential_std";
    private static final int ESSENTIAL_MODE = 256;
    private static final int MORE_MODE = 254;
    private final AtomicBoolean modeListLogged = new AtomicBoolean();
    private final AtomicBoolean supportLogged = new AtomicBoolean();
    private final AtomicBoolean modeListReported = new AtomicBoolean();

    private void installModeListHook(ClassLoader loader) {
        if (modeListHooked) {
            return;
        }
        try {
            Class<?> list = Class.forName("v2.S", false, loader);
            Method order = null;
            for (Method m : list.getDeclaredMethods()) {
                if (m.getName().equals("y") && m.getReturnType() == int[].class
                        && m.getParameterCount() == 1) {
                    order = m;
                }
            }
            if (order == null) {
                throw new NoSuchMethodException("mode order method in " + list);
            }
            hook(order).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof int[] modes) || !essentialStdEnabled()) {
                    return result;
                }
                for (int mode : modes) {
                    if (mode == ESSENTIAL_MODE) {
                        return result;
                    }
                }
                int at = modes.length;
                for (int i = 0; i < modes.length; i++) {
                    if (modes[i] == MORE_MODE) {
                        at = i;
                        break;
                    }
                }
                int[] extended = new int[modes.length + 1];
                System.arraycopy(modes, 0, extended, 0, at);
                extended[at] = ESSENTIAL_MODE;
                System.arraycopy(modes, at, extended, at + 1, modes.length - at);
                if (modeListLogged.compareAndSet(false, true)) {
                    log(Log.INFO, TAG, "added the Leica Essential mode to the mode order at "
                            + at + " of " + extended.length);
                }
                return extended;
            });
            // Whether a mode sits in the strip or under "More" is decided from a saved list of
            // all supported modes (written when the camera first ran) that does not hold 256, so
            // the check answers for the mode itself.
            Method visible = null;
            for (Method m : list.getDeclaredMethods()) {
                if (m.getName().equals("E") && m.getReturnType() == boolean.class
                        && m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                    visible = m;
                }
            }
            if (visible != null) {
                hook(visible).intercept(chain -> {
                    Object result = chain.proceed();
                    if (Boolean.FALSE.equals(result) && chain.getArg(0) instanceof Integer mode
                            && mode == ESSENTIAL_MODE && essentialStdEnabled()) {
                        return true;
                    }
                    return result;
                });
            }
            // The Essential entry is only registered when its support() answers true, which is a
            // device feature that is off with the Leica customisation (and so in the standard UI).
            try {
                Class<?> entry = Class.forName(
                        "com.android.camera.features.mode.legendary.LegendaryEnter", false, loader);
                Method support = entry.getDeclaredMethod("support");
                hook(support).intercept(chain -> {
                    Object result = chain.proceed();
                    if (Boolean.FALSE.equals(result) && essentialStdEnabled()) {
                        if (supportLogged.compareAndSet(false, true)) {
                            log(Log.INFO, TAG, "LegendaryEnter.support() was false, answering true");
                        }
                        return true;
                    }
                    return result;
                });
            } catch (Throwable t) {
                log(Log.WARN, TAG, "cannot hook LegendaryEnter.support: " + t);
            }
            // For the experiment: report what the camera's final mode list holds.
            try {
                Method build = list.getDeclaredMethod("C");
                Field strip = list.getDeclaredField("c");
                Field more = list.getDeclaredField("d");
                strip.setAccessible(true);
                more.setAccessible(true);
                Class<?> item = Class.forName("com.android.camera.data.data.d", false, loader);
                Field key = item.getDeclaredField("r");
                key.setAccessible(true);
                hook(build).intercept(chain -> {
                    Object result = chain.proceed();
                    if (essentialStdEnabled() && modeListReported.compareAndSet(false, true)) {
                        try {
                            Object self = chain.getThisObject();
                            log(Log.INFO, TAG, "mode list: all " + keys((List<?>) result, key)
                                    + ", strip " + keys((List<?>) strip.get(self), key)
                                    + ", more " + keys((List<?>) more.get(self), key));
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "cannot read the mode list: " + t);
                        }
                    }
                    return result;
                });
            } catch (Throwable t) {
                log(Log.WARN, TAG, "cannot hook the mode list builder: " + t);
            }
            modeListHooked = true;
            log(Log.INFO, TAG, "hooked " + list.getName() + "." + order.getName()
                    + (visible != null ? ", " + visible.getName() : "")
                    + " (mode order; " + ESSENTIAL_STD_KEY + ")");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook the mode order: " + t);
        }
    }

    // ---- 2. Original colour style -------------------------------------------------------------

    private void installStylizationHook() {
        if (stylizationHooked) {
            return;
        }
        try {
            Method method = CameraCharacteristics.class.getDeclaredMethod("get",
                    CameraCharacteristics.Key.class);
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof Integer mask
                        && chain.getArg(0) instanceof CameraCharacteristics.Key<?> key
                        && STYLIZATION_KEY.equals(key.getName())
                        && (mask & STYLIZATION_ORIGINAL) == 0) {
                    return mask | STYLIZATION_ORIGINAL;
                }
                return result;
            });
            stylizationHooked = true;
            log(Log.INFO, TAG, "hooked CameraCharacteristics.get (" + STYLIZATION_KEY + " bit 3)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook CameraCharacteristics.get: " + t);
        }
    }

    // ---- 2b. Cap excessive blur radius for portrait preview (HyperOS 3 / 14 Ultra) -------------
    // BlurBackgroundView is a UI View (com.xiaomi.camera.ui.blur) used for the live preview's
    // background blur rendering. It does NOT affect the final captured image processing.
    // Values seen in code: 30, 66; log showed 183 (from resource dimension). Cap at 100.

    private void installBlurRadiusHook(ClassLoader loader) {
        if (blurRadiusHooked) {
            return;
        }
        boolean anyHooked = false;
        // 1) Hook BlurBackgroundView.setBlurRadius (legacy path, values 30/66)
        try {
            Class<?> blurClass = Class.forName("com.xiaomi.camera.ui.blur.BlurBackgroundView", false, loader);
            Method setBlur = blurClass.getDeclaredMethod("setBlurRadius", int.class);
            setBlur.setAccessible(true);
            hook(setBlur).intercept(chain -> {
                int blurRadius = (Integer) chain.getArg(0);
                if (blurRadius > 100) {
                    log(Log.INFO, TAG, "capped BlurBackgroundView blurRadius " + blurRadius + " -> 100");
                    return chain.proceed(new Object[]{100});
                }
                return chain.proceed();
            });
            anyHooked = true;
            log(Log.INFO, TAG, "hooked BlurBackgroundView.setBlurRadius (preview blur radius cap)");
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "BlurBackgroundView not found: " + t.getMessage());
        }
        // 2) Hook RenderEffect.createBlurEffect (modern path, values 150/160)
        try {
            Class<?> renderEffectClass = Class.forName("android.graphics.RenderEffect");
            Method createBlur1 = renderEffectClass.getDeclaredMethod("createBlurEffect", float.class, float.class, Class.forName("android.graphics.Shader$TileMode"));
            createBlur1.setAccessible(true);
            hook(createBlur1).intercept(chain -> {
                float radiusX = (Float) chain.getArg(0);
                float radiusY = (Float) chain.getArg(1);
                if (radiusX > 100 || radiusY > 100) {
                    float cappedX = Math.min(radiusX, 100);
                    float cappedY = Math.min(radiusY, 100);
                    log(Log.INFO, TAG, "capped RenderEffect.createBlurEffect " + radiusX + "/" + radiusY + " -> " + cappedX + "/" + cappedY);
                    return chain.proceed(new Object[]{cappedX, cappedY, chain.getArg(2)});
                }
                return chain.proceed();
            });
            anyHooked = true;
            log(Log.INFO, TAG, "hooked RenderEffect.createBlurEffect (preview blur radius cap)");
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "RenderEffect.createBlurEffect not hookable: " + t.getMessage());
        }
        if (anyHooked) {
            blurRadiusHooked = true;
        }
    }

    private volatile boolean blurRadiusHooked;
    private volatile boolean lutHooked;
    private final java.util.Map<String, LeicaLut> lutCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Applies the reproduced Leica 64^3 LUT to the full-size bitmap right before JPEG encoding.
    // Only active when the compatibility fallback is enabled and the app requested M9/M3.
    private volatile boolean watcherStarted;
    private static final ThreadLocal<Boolean> IN_LUT = new ThreadLocal<>();

    // Post-process the saved JPEG when the camera app writes it. This avoids depending on the
    // app's internal encoder, which does not use Bitmap.compress on this build.
    private void installLutFileWatcher() {
        if (watcherStarted) {
            return;
        }
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DCIM), "Camera");
            log(Log.INFO, TAG, "LUT watcher dir " + dir + " exists=" + dir.isDirectory());

            final java.util.Set<String> processed =
                    java.util.concurrent.ConcurrentHashMap.newKeySet();
            java.io.File[] existing = dir.listFiles((d, n) -> isCameraPhotoName(n));
            if (existing != null) {
                for (java.io.File f : existing) {
                    processed.add(f.getAbsolutePath());
                }
            }
            new Thread(() -> {
                while (true) {
                    try {
                        java.io.File[] files = dir.listFiles((d, n) -> isCameraPhotoName(n));
                        log(Log.INFO, TAG, "LUT poll n=" + (files == null ? -1 : files.length)
                                + " readable=" + dir.canRead());
                        if (files != null) {
                            for (java.io.File f : files) {
                                if (!isCameraPhoto(f)) {
                                    continue;
                                }
                                if (processed.add(f.getAbsolutePath())) {
                                    log(Log.INFO, TAG, "LUT saw new file " + f.getName());
                                    long size = f.length();
                                    Thread.sleep(1500);
                                    if (f.length() == size && size > 50000) {
                                        applyLutToFile(f);
                                    }
                                }
                            }
                        }
                        Thread.sleep(2000);
                    } catch (Throwable ignored) {
                    }
                }
            }, "hcm-lut-poll").start();
            watcherStarted = true;
            log(Log.INFO, TAG, "watching DCIM/Camera for LUT post-processing");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot watch DCIM/Camera: " + t);
        }
    }

    /**
     * Only real camera JPEGs are post-processed. Gallery trash entries
     * (".trashed-<id>-IMG_..."), pending/temp files and the module's own
     * ".hcm-*" working files must never enter the pipeline; a gallery
     * cleanup used to queue dozens of deleted photos and delayed the next
     * capture by minutes.
     */
    private static boolean isCameraPhotoName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("img_")
                && lower.endsWith(".jpg")
                && !lower.contains(".trashed-")
                && !lower.contains(".pending")
                && !lower.contains(".hcm")
                && !lower.contains(".tmp")
                && !lower.startsWith(".");
    }

    private static boolean isCameraPhoto(java.io.File file) {
        return file != null && file.isFile() && isCameraPhotoName(file.getName());
    }

    private void applyLutToFile(java.io.File file) {
        try {
            // --- Original Portrait Software Post-Processing ---
            // Check if Original Portrait was selected for this capture
            if (lastOriginalPortrait) {
                long age = android.os.SystemClock.elapsedRealtime() - lastOriginalPortraitTime;
                if (age <= ESSENTIAL_LEGEND_FRESH_MS) {
                    log(Log.INFO, TAG, "Original Portrait candidate " + file.getName() + " age=" + age + "ms");
                    handleOriginalPortraitPostProcess(file);
                    // One selection, one file: a later normal capture must not inherit this look.
                    lastOriginalPortrait = false;
                    lastOriginalPortraitTime = 0;
                    return;
                } else {
                    log(Log.INFO, TAG, "Original Portrait skipped " + file.getName() + ": stale selection (age=" + age + "ms)");
                    lastOriginalPortrait = false;
                    lastOriginalPortraitTime = 0;
                }
            }

            // The genuine pipeline is an additional path. It never replaces the LUT fallback or
            // the HAL slot: when it is on it owns the file, and it refuses to run if the HAL slot
            // is also rendering M3/M9 in the camera engine.
            byte latest = lastLegend;
            if (essentialPipelineEnabled()) {
                if (halLutSlotEnabled()) {
                    log(Log.INFO, TAG, "Moment App pipeline skipped: HAL LUT slot is on");
                    return;
                }
                long age = android.os.SystemClock.elapsedRealtime() - lastLegendTime;
                if (latest == 0 && essentialModeActive()) {
                    // The 14 Ultra's Essential mode does not put legendMode into the request
                    // (the HAL has no Legendary plugin), but the camera still stores the
                    // selected style in pref_legendary_mode_key. In this mode the style is
                    // persistent, like the 17 Ultra, so every capture is processed.
                    byte style = legendFromPrefs();
                    if (style == 1 || style == 2 || style == 3) {
                        latest = style;
                        age = 0;
                        log(Log.INFO, TAG, "Moment App using stored Essential style legend=" + style
                                + " module=" + currentModuleId);
                    }
                }
                if (latest == 0 || age > ESSENTIAL_LEGEND_FRESH_MS) {
                    log(Log.INFO, TAG, "Moment App pipeline skipped " + file.getName()
                            + ": no fresh M3/M9 selection (legend=" + latest + ", age=" + age
                            + " ms, module=" + currentModuleId + ")");
                    return;
                }
                log(Log.INFO, TAG, "Moment App candidate " + file.getName() + " legend=" + latest);
                // The bound service is invisible to Camera under Android 11+ package
                // visibility, so run the same native stages in the save path (as the
                // 17 Ultra app does). The Monopan helper still runs out of process.
                android.content.Context ctx = currentContext();
                boolean applied = false;
                if (ctx != null && moduleApk != null && moduleInfo != null) {
                    applied = EssentialProcessor.processFile(file, latest, moduleApk,
                            moduleInfo.nativeLibraryDir, ctx);
                }
                log(Log.INFO, TAG, "Moment App " + (applied ? "applied" : "left unchanged")
                        + " legend=" + latest + " " + file.getName());
                // One selection, one file: a later normal capture must not inherit this look.
                lastLegend = 0;
                lastLegendTime = 0;
                return;
            }
            if (!compatFallbackEnabled()) {
                return;
            }
            byte legend = legendFromPrefs();
            log(Log.INFO, TAG, "LUT candidate " + file.getName() + " legend=" + legend
                    + " apk=" + (moduleApk != null) + " size=" + file.length());
            legend = legendFromPrefs();
            String asset = legend == 1 ? "leica_luts/130_LeicaNatural.png"
                    : legend == 2 ? "leica_luts/M3_Monopan.png"
                    : legend == 3 ? "leica_luts/M10R_Cmos.png" : null;
            if (asset == null || moduleApk == null) {
                return;
            }
            if (file.length() < 500_000L) {
                return;
            }
            Thread.sleep(800);
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            log(Log.INFO, TAG, "LUT decoded " + (bm == null ? "null" : bm.getWidth() + "x" + bm.getHeight()));
            if (bm == null || bm.getWidth() < 2000) {
                return;
            }
            LeicaLut lut = lutCache.get(asset);
            if (lut == null) {
                lut = LeicaLut.loadFromApk(moduleApk, asset);
                log(Log.INFO, TAG, "LUT load " + (lut != null));
                if (lut == null) {
                    return;
                }
                lutCache.put(asset, lut);
            }
            log(Log.INFO, TAG, "LUT applying " + asset);
            android.graphics.Bitmap out = lut.apply(bm, 1.0f);
            log(Log.INFO, TAG, "LUT bitmap ready " + (out != null));
            if (out == null) {
                return;
            }
            java.io.File tmp = new java.io.File(file.getParentFile(), file.getName() + ".hcm.tmp");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fos);
            }
            out.recycle();
            bm.recycle();
            if (!tmp.renameTo(file)) {
                tmp.delete();
                log(Log.WARN, TAG, "could not replace " + file.getName());
                return;
            }
            log(Log.INFO, TAG, "applied Leica LUT to " + file.getName());
        } catch (Throwable t) {
            log(Log.WARN, TAG, "LUT file post-process failed: " + t);
        }
    }

    // Handle Original Portrait post-processing via EssentialPipelineExtension
    private void handleOriginalPortraitPostProcess(java.io.File file) {
        try {
            log(Log.INFO, TAG, "Starting Original Portrait post-processing for " + file.getName());
            
            // Get application context
            android.content.Context context = currentApplication();
            if (context == null) {
                log(Log.WARN, TAG, "Cannot get application context for Original Portrait post-process");
                return;
            }
            
            // Initialize EssentialPipelineExtension if needed
            io.martin.hypercameramod.originalportrait.EssentialPipelineExtension ext = 
                io.martin.hypercameramod.originalportrait.EssentialPipelineExtension.getInstance(context);
            ext.setEnabled(true);
            ext.initialize();
            
            // Create a simple synthetic depth map (center-weighted)
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bm == null) {
                log(Log.WARN, TAG, "Failed to decode JPEG for Original Portrait post-process");
                return;
            }
            
            // The camera's Master portrait already has real depth-based bokeh;
            // no synthetic depth map is needed for the current software path.
            android.hardware.camera2.CaptureResult result = createMockCaptureResult();
            
            // Process with EssentialPipelineExtension
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            ext.handlePortraitOriginal(file, null, result, processedFile -> {
                if (processedFile != file) {
                    // Replace original file with processed version
                    try {
                        java.nio.file.Files.move(processedFile.toPath(), file.toPath(), 
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        log(Log.INFO, TAG, "Original Portrait post-process applied to " + file.getName());
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "Failed to replace file after Original Portrait post-process: " + t);
                    }
                } else {
                    log(Log.INFO, TAG, "Original Portrait post-process returned original file");
                }
                latch.countDown();
            });
            
            // Wait for completion (with timeout)
            try {
                if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    log(Log.WARN, TAG, "Original Portrait post-process timed out");
                }
            } catch (InterruptedException e) {
                log(Log.WARN, TAG, "Original Portrait post-process interrupted");
            }
            
            if (bm != null && !bm.isRecycled()) {
                bm.recycle();
            }
            
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Original Portrait post-process failed: " + t);
        }
    }
    
    // Create a simple synthetic depth map (center-weighted, for testing)
    private float[] createSyntheticDepthMap(int width, int height) {
        float[] depth = new float[width * height];
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        float maxDist = (float) Math.hypot(cx, cy);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float dx = x - cx;
                float dy = y - cy;
                float dist = (float) Math.hypot(dx, dy) / maxDist;
                // Center is in focus (depth=0.5), edges are blurred (depth=0.0 or 1.0)
                depth[y * width + x] = 0.5f + 0.3f * (1.0f - dist);
            }
        }
        return depth;
    }
    
    // Create a mock capture result with minimal metadata
    private android.hardware.camera2.CaptureResult createMockCaptureResult() {
        try {
            Class<?> builderClass = Class.forName("android.hardware.camera2.CaptureResult$Builder");
            Object builder = builderClass.getDeclaredConstructor().newInstance();
            return (android.hardware.camera2.CaptureResult) builderClass.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to create mock capture result: " + t);
            return null;
        }
    }

    // Fallback for builds that never send com.xiaomi.sessionparams.legendMode: read the style the
    // app has stored for the rear camera. M9 = 1, M3 = 2.
    private static byte legendFromPrefs() {
        // Stage LUT files on first M3/M9 selection so the stock filter node can render them.
        ensureLutFiles();
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String forced = (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, "camera.feature.lut_style", "");
            if ("M9".equalsIgnoreCase(forced)) {
                return 1;
            }
            if ("M3".equalsIgnoreCase(forced)) {
                return 2;
            }
            if ("M10R".equalsIgnoreCase(forced)) {
                return 3;
            }
            // An explicit property always wins, including "off": without this, a build that
            // always starts the watcher would still resolve the style from the camera's saved
            // preference and post-process a capture the HAL already rendered.
            if (!forced.isEmpty()) {
                return 0;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                android.content.SharedPreferences p = ((android.content.Context) app)
                        .getSharedPreferences("camera_settings_simple_mode_local_0", 0);
                String v = p.getString("pref_legendary_mode_key", "");
                if ("M9".equalsIgnoreCase(v)) {
                    return 1;
                }
                if ("M3".equalsIgnoreCase(v)) {
                    return 2;
                }
                if ("M10R".equalsIgnoreCase(v)) {
                    return 3;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** The camera application context, or null before the process is bound. */
    private static android.content.Context currentContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                return (android.content.Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** True when the camera is in the Leica Essential mode (module index 256). */
    private boolean essentialModeActive() {
        // The module index is authoritative. Camera writes pref_camera_mode_key_intent_0
        // only when it leaves the mode, so that preference is stale right after entering
        // Essential. Without this check a fresh capture is skipped as "no fresh selection".
        if (currentModuleId == ESSENTIAL_MODE) {
            return true;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                android.content.SharedPreferences p = ((android.content.Context) app)
                        .getSharedPreferences("camera_settings_global", 0);
                return p.getInt("pref_camera_mode_key_intent_0", 0) == ESSENTIAL_MODE;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void installLutFallbackHook(ClassLoader loader) {
        if (lutHooked) {
            return;
        }
        try {
            Method compress = android.graphics.Bitmap.class.getDeclaredMethod("compress",
                    android.graphics.Bitmap.CompressFormat.class, int.class,
                    java.io.OutputStream.class);
            compress.setAccessible(true);
            hook(compress).intercept(chain -> {
                if (Boolean.TRUE.equals(IN_LUT.get())) {
                    return chain.proceed();
                }
                if (!compatFallbackEnabled()) {
                    return chain.proceed();
                }
                try {
                    android.graphics.Bitmap bm = (android.graphics.Bitmap) chain.getThisObject();
                    if (bm == null || bm.getWidth() < 2000) {
                        return chain.proceed();
                    }
                    byte legend = lastLegend;
                    if (legend == 0) {
                        legend = legendFromPrefs();
                    }
                    String asset = legend == 1 ? "leica_luts/130_LeicaNatural.png"
                            : legend == 2 ? "leica_luts/M3_Monopan.png"
                            : legend == 3 ? "leica_luts/M10R_Cmos.png" : null;
                    if (asset == null) {
                        return chain.proceed();
                    }
                    if (moduleApk == null) {
                        return chain.proceed();
                    }
                    LeicaLut lut = lutCache.get(asset);
                    if (lut == null) {
                        lut = LeicaLut.loadFromApk(moduleApk, asset);
                        if (lut == null) {
                            return chain.proceed();
                        }
                        lutCache.put(asset, lut);
                    }
                    log(Log.INFO, TAG, "LUT applying " + asset);
            android.graphics.Bitmap out = lut.apply(bm, 1.0f);
            log(Log.INFO, TAG, "LUT bitmap ready " + (out != null));
                    if (out == null) {
                        return chain.proceed();
                    }
                    out.compress((android.graphics.Bitmap.CompressFormat) chain.getArg(0),
                            (Integer) chain.getArg(1), (java.io.OutputStream) chain.getArg(2));
                    out.recycle();
                    log(Log.INFO, TAG, "applied Leica LUT " + asset);
                    return true;
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "LUT fallback failed: " + t);
                    return chain.proceed();
                }
            });
            lutHooked = true;
            log(Log.INFO, TAG, "installed the Leica LUT compatibility fallback");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook Bitmap.compress: " + t);
        }
    }

    private boolean compatFallbackEnabled() {
        // Explicit property wins; used for testing and for managed deployments.
        if (propertyOn("camera.feature.compat_fallback")) {
            return true;
        }
        try {
            android.content.SharedPreferences p = getRemotePreferences("hypercameramod");
            if (p != null && p.contains(COMPAT_FALLBACK_KEY)) {
                return p.getBoolean(COMPAT_FALLBACK_KEY, false);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot read " + COMPAT_FALLBACK_KEY + ": " + t);
        }
        return propertyOn("camera.feature.compat_fallback");
    }

    // Genuine Moment App pipeline (from Moment App). Off by default; all native work
    // happens in the module's :essential process.
    private boolean essentialPipelineEnabled() {
        if (propertyOn(ESSENTIAL_PIPELINE_PROP)) {
            return true;
        }
        try {
            android.content.SharedPreferences p = getRemotePreferences("hypercameramod");
            if (p != null && p.contains(ESSENTIAL_PIPELINE_KEY)) {
                return p.getBoolean(ESSENTIAL_PIPELINE_KEY, false);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot read " + ESSENTIAL_PIPELINE_KEY + ": " + t);
        }
        return false;
    }

    // ---- 2b. Legendary request bridge -----------------------------------------------------------
    // The 6.7 app sends com.xiaomi.sessionparams.legendMode (M9=1, M3=2). The Aurora engine has no
    // code to copy that key into MiMetadata tag 508, so a ported plugin would see 0 and disable
    // itself. The port patches the plugin to read stylizationType instead; this hook makes the
    // request carry the Legendary byte in that key. It only runs when the port's marker file is
    // present, so a stock camera is never changed.
    private void installLegendBridgeHook() {
        // The marker means the real Nezha plugin is installed. Without it, the optional
        // compatibility fallback still forwards the M9/M3 choice as stylizationType, which
        // makes the stock Leica Authentic/Vibrant colour path approximate the look. It is
        // not the real Legendary plugin.
        boolean realBridge = HalCapabilities.hasLegendBridge();
        if (legendBridgeHooked || (!realBridge && !compatFallbackEnabled())) {
            return;
        }
        try {
            Class<?> builder = Class.forName("android.hardware.camera2.CaptureRequest$Builder");
            Class<?> keyClass = Class.forName("android.hardware.camera2.CaptureRequest$Key");
            Method set = null;
            for (Method m : builder.getDeclaredMethods()) {
                if (m.getName().equals("set") && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == keyClass) {
                    set = m;
                }
            }
            if (set == null) {
                log(Log.WARN, TAG, "CaptureRequest.Builder.set(Key, Object) not found");
                return;
            }
            Object stylizationKey = keyClass.getConstructor(String.class, Class.class)
                    .newInstance(STYLIZATION_TYPE_KEY, Byte.class);
            Method setMethod = builder.getMethod("set", keyClass, Object.class);
            Method build = builder.getMethod("build");

            hook(set).intercept(chain -> {
                Object key = chain.getArg(0);
                if (!(key instanceof CaptureRequest.Key<?> requestKey)) {
                    return chain.proceed();
                }
                String name = requestKey.getName();
                if (LEGEND_MODE_KEY.equals(name)) {
                    byte legend = chain.getArg(1) instanceof Number number
                            ? number.byteValue() : 0;
                    BRIDGE_LEGEND.set(legend);
                    if (legend != 0) {
                        lastLegend = legend;
                        lastLegendTime = android.os.SystemClock.elapsedRealtime();
                    }
                    Object result = chain.proceed();
                    if (legend != 0 && !BRIDGE_SETTING.get()) {
                        BRIDGE_SETTING.set(true);
                        try {
                            setMethod.invoke(chain.getThisObject(), stylizationKey, legend);
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "cannot add stylizationType to the request: " + t);
                        } finally {
                            BRIDGE_SETTING.set(false);
                        }
                    }
                    return result;
                }
                if (STYLIZATION_TYPE_KEY.equals(name) && !BRIDGE_SETTING.get()) {
                    Byte legend = BRIDGE_LEGEND.get();
                    if (legend != null && legend != 0) {
                        return chain.proceed(new Object[]{key, legend});
                    }
                }
                return chain.proceed();
            });
            hook(build).intercept(chain -> {
                try {
                    return chain.proceed();
                } finally {
                    BRIDGE_LEGEND.remove();
                }
            });
            legendBridgeHooked = true;
            log(Log.INFO, TAG, "installed the Legendary -> stylizationType request bridge"
                    + (realBridge ? " (HAL plugin)" : " (compatibility fallback)"));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot install the Legendary request bridge: " + t);
        }
    }

    // Records the M3/M9 choice even when the real HAL bridge or the compatibility fallback is off,
    // so the optional Essential pipeline knows which look the user selected. It does not modify
    // the request: stock filters and LUT slots stay exactly as they were.
    private void installLegendObserver() {
        if (legendObserverHooked || legendBridgeHooked || !essentialPipelineEnabled()) {
            return;
        }
        try {
            Class<?> builder = Class.forName("android.hardware.camera2.CaptureRequest$Builder");
            Class<?> keyClass = Class.forName("android.hardware.camera2.CaptureRequest$Key");
            Method set = null;
            for (Method m : builder.getDeclaredMethods()) {
                if (m.getName().equals("set") && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == keyClass) {
                    set = m;
                }
            }
            if (set == null) {
                return;
            }
            hook(set).intercept(chain -> {
                Object key = chain.getArg(0);
                if (key instanceof CaptureRequest.Key<?> requestKey
                        && LEGEND_MODE_KEY.equals(requestKey.getName())
                        && chain.getArg(1) instanceof Number number
                        && number.byteValue() != 0) {
                    lastLegend = number.byteValue();
                    lastLegendTime = android.os.SystemClock.elapsedRealtime();
                }
                return chain.proceed();
            });
            legendObserverHooked = true;
            log(Log.INFO, TAG, "observing " + LEGEND_MODE_KEY + " for the Moment App pipeline");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot observe the Legendary style: " + t);
        }
    }

    // ---- Original Portrait Software Post-Processing Hook ----------------------------------------
    // Intercepts filterId for Portrait mode when Original is selected, forces Master for capture,
    // and stores the Original selection for post-processing via EssentialPipelineExtension.

    private void setHalBokehBypass(boolean on) {
        if (lastHalBypass != null && lastHalBypass == on) {
            return;
        }
        lastHalBypass = on;
        try {
            android.content.Context context = currentApplication();
            if (context == null) {
                log(Log.WARN, TAG, "HAL bokeh bypass " + on + ": no context");
                return;
            }
            java.io.File dir = context.getFilesDir();
            java.io.File file = new java.io.File(dir, "hcm_bokeh_bypass");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                fos.write((on ? "1" : "0").getBytes());
            }
            log(Log.INFO, TAG, "HAL bokeh bypass file=" + on + " " + file);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot write HAL bokeh bypass=" + on + ": " + t);
        }
    }

    private void installOriginalPortraitCaptureHook(ClassLoader loader) {
        if (originalPortraitCaptureHooked) {
            return;
        }
        try {
            Class<?> builder = Class.forName("android.hardware.camera2.CaptureRequest$Builder");
            Class<?> keyClass = Class.forName("android.hardware.camera2.CaptureRequest$Key");
            Method set = null;
            for (Method m : builder.getDeclaredMethods()) {
                if (m.getName().equals("set") && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == keyClass) {
                    set = m;
                }
            }
            if (set == null) {
                log(Log.WARN, TAG, "CaptureRequest.Builder.set for Original Portrait not found");
                return;
            }

            hook(set).intercept(chain -> {
                Object key = chain.getArg(0);
                if (!(key instanceof CaptureRequest.Key<?> requestKey)) {
                    return chain.proceed();
                }
                String name = requestKey.getName();
                // The portrait style reaches the HAL as stylizationType, not filterId:
                // 0 = Master, 1 = Leica, 2 = Original. Original starts the algorithm-up
                // SAT preview that the Aurora DSP bokeh engine cannot serve, so map it to
                // Master for preview and capture and remember it for post-processing.
                if ("com.xiaomi.sessionparams.stylizationType".equals(name)
                        && chain.getArg(1) instanceof Number stylNum
                        && currentModuleId == MODE_PORTRAIT) {
                    byte style = stylNum.byteValue();
                    if (style == 2 && originalPortraitEnabled()) {
                        setHalBokehBypass(true);
                        lastOriginalPortrait = true;
                        lastOriginalPortraitTime = android.os.SystemClock.elapsedRealtime();
                        log(Log.INFO, TAG, "Original Portrait selected -> AI bokeh, HAL bokeh bypassed");
                        return chain.proceed(new Object[]{key, (byte) 0});
                    }
                    if (style == 0 || style == 1) {
                        setHalBokehBypass(false);
                    }
                }
                return chain.proceed();
            });
            // Track which camera module is active. The portrait style hook needs to know
            // that stylizationType 2 means Original portrait and not the Photo colour style.
            try {
                Class<?> moduleBase = Class.forName("z3.a", false, loader);
                Class<?> moduleInterface = Class.forName("z3.r", false, loader);
                Method getModuleId = moduleInterface.getMethod("getModuleId");
                for (Method m : moduleBase.getDeclaredMethods()) {
                    if (!m.getName().equals("s") || m.getParameterCount() != 1) {
                        continue;
                    }
                    hook(m).intercept(chain -> {
                        try {
                            currentModuleId = (Integer) getModuleId.invoke(chain.getThisObject());
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                }
                log(Log.INFO, TAG, "tracking the active camera module for Original Portrait");
            } catch (Throwable t) {
                log(Log.WARN, TAG, "cannot track the active camera module: " + t);
            }
            originalPortraitCaptureHooked = true;
            log(Log.INFO, TAG, "installed Original Portrait capture hook (forces Master, post-processes)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot install Original Portrait capture hook: " + t);
        }
    }

    // State for Original Portrait post-processing
    private volatile boolean lastOriginalPortrait = false;
    private volatile long lastOriginalPortraitTime = 0;
    private volatile Boolean lastHalBypass = null;
    private volatile boolean originalPortraitCaptureHooked = false;
    private volatile int currentModuleId = -1;

    // ---- 3. Leica Essential CMOS (M10R) ---------------------------------------------------------

    // Read on every build of the list, so setprop takes effect the next time Camera starts.
    private boolean m10rEnabled() {
        try {
            android.content.SharedPreferences p = getRemotePreferences("hypercameramod");
            if (p != null && p.contains("m10r_enabled")) {
                return p.getBoolean("m10r_enabled", false);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot read m10r_enabled preference: " + t);
        }
        // Fallback to system property
        return propertyOn(M10R_ENABLE_KEY);
    }

    // On by default only on the phones whose hardware layer can run the Original portrait preview;
    // off on the 14 Ultra, where it deadlocks the preview (see MODE_PORTRAIT). The settings toggle
    // wins, then the camera.feature.original_portrait system property. Read on every call so
    // turning the switch on takes effect the next time the style list is built, without restarting
    // the camera process.
    private boolean originalPortraitEnabled() {
        boolean enabled;
        try {
            android.content.SharedPreferences p = getRemotePreferences("hypercameramod");
            if (p != null && p.contains(ORIGINAL_PORTRAIT_KEY)) {
                enabled = p.getBoolean(ORIGINAL_PORTRAIT_KEY,
                        DeviceSupport.originalPortraitDefault());
            } else {
                String value = property(ORIGINAL_PORTRAIT_PROP);
                enabled = !value.isEmpty() ? "1".equals(value)
                        : DeviceSupport.originalPortraitDefault();
                if (p == null) {
                    log(Log.WARN, TAG, "getRemotePreferences returned null");
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot read " + ORIGINAL_PORTRAIT_KEY + ": " + t);
            enabled = DeviceSupport.originalPortraitDefault();
        }
        int state = enabled ? 1 : 0;
        if (originalPortraitState != state) {
            originalPortraitState = state;
            log(Log.INFO, TAG, "Original portrait style " + (enabled ? "enabled" : "disabled")
                    + " on " + DeviceSupport.modelName());
        }
        return enabled;
    }

    private static List<String> keys(List<?> items, Field key) throws IllegalAccessException {
        List<String> out = new java.util.ArrayList<>();
        for (Object item : items) {
            out.add(String.valueOf(key.get(item)));
        }
        return out;
    }

    // Reads the essential_mode_enabled preference mirrored from SettingsActivity.
    private boolean essentialStdEnabled() {
        try {
            android.content.SharedPreferences p = getRemotePreferences("hypercameramod");
            if (p != null && p.contains("essential_mode_enabled")) {
                return p.getBoolean("essential_mode_enabled", true);
            }
        } catch (Throwable t) {
        }
        return true; // default on
    }

    private static boolean propertyOn(String key) {
        return "1".equals(property(key, "0"));
    }

    private static String property(String key) {
        return property(key, "");
    }

    private static String property(String key, String fallback) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Object value = props.getMethod("get", String.class, String.class)
                    .invoke(null, key, fallback);
            return value instanceof String string ? string : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void installLegendHook(ClassLoader loader) {
        if (legendHooked) {
            return;
        }
        // Camera 6.8 builds the M10R item itself when the device profile is the 18 Pro Max. The
        // 6.7 layout below cannot describe that list, and adding a second item would at best be
        // ignored. Treat the native list as the supported path and stay out of it.
        if (DeviceSupport.family() == DeviceSupport.XIAOMI_18_PRO_MAX
                && classExists("r2.B", loader)) {
            legendHooked = true;
            log(Log.INFO, TAG, "Camera 6.8 builds the Leica Essential list; M10R is native");
            return;
        }
        for (LegendProfile profile : LEGEND_PROFILES) {
            try {
                Class<?> list = Class.forName(profile.listClass(), false, loader);
                Method getItems = list.getDeclaredMethod("getItems");
                // The list is filled by one method that takes the camera's mode data. The list is
                // extended right after that: the style picker's adapter keeps the list and counts
                // its items once, so growing it on a later read breaks the picker.
                Method build = findBuilder(list);
                Class<?> item = Class.forName(profile.itemClass(), false, loader);
                allocate(item);   // fail now, not in the hook, if instances cannot be made
                Field key = field(item, "r");
                Field title = field(item, "l");
                Field alias = field(item, "m");
                Field intro = field(item, "n");
                Field image = field(item, "g");
                Field template = field(item, "h");
                Field icon = field(item, "i");
                Field textIcon = field(item, "c");
                Class<?> strings = Class.forName(profile.stringIds(), false, loader);
                Class<?> images = Class.forName(profile.imageIds(), false, loader);

                hook(build).intercept(chain -> {
                    Object result = chain.proceed();
                    // Show M10R when the toggle is on. On the 14 Ultra the save-path pipeline
                    // post-processes it; phones with the 18 Pro Max HAL process it natively.
                    if (!m10rEnabled()) {
                        return result;
                    }
                    try {
                        if (getItems.invoke(chain.getThisObject()) instanceof List<?> raw) {
                            @SuppressWarnings("unchecked")
                            List<Object> items = (List<Object>) raw;
                            Object ccd = null;
                            for (Object entry : items) {
                                if (M10R.equals(key.get(entry))) {
                                    return result;
                                }
                                if ("M9".equals(key.get(entry))) {
                                    ccd = entry;
                                }
                            }
                            if (ccd == null) {
                                return result;
                            }
                            // Start from the CCD (M9) item, so every field the camera's theme
                            // fills in is there, then swap in the CMOS equivalents.
                            Object entry = allocate(item);
                            for (Field f : item.getDeclaredFields()) {
                                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                                    f.setAccessible(true);
                                    f.set(entry, f.get(ccd));
                                }
                            }
                            key.set(entry, M10R);
                            // The CMOS title string is not in this build's resources: the
                            // module supplies it (see installTextHook).
                            title.setInt(entry, ASSET_TEXT_BASE);
                            alias.setInt(entry, swap(strings, alias.getInt(ccd), "legend_ccd_alias",
                                    "legend_cmos_alias"));
                            intro.setInt(entry, swap(strings, intro.getInt(ccd), "m9", "m10r"));
                            image.setInt(entry, swap(images, image.getInt(ccd), "m9", "m10r"));
                            template.setInt(entry, ASSET_ICON_BASE);
                            icon.setInt(entry, ASSET_ICON_BASE | 1);
                            textIcon.setInt(entry, ASSET_ICON_BASE | 2);
                            items.add(Math.min(1, items.size()), entry);   // after M3, before M9
                            log(Log.INFO, TAG, "added the M10R (CMOS) style to the Leica Essential list");
                        }
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "could not add the M10R style", t);
                    }
                    return result;
                });
                legendHooked = true;
                installAssetHooks();
                installTextHook();
                log(Log.INFO, TAG, "hooked " + profile.listClass() + "." + build.getName()
                        + " (Leica Essential, layout " + profile.name() + ")");
                return;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "Leica Essential layout " + profile.name()
                        + " does not match: " + t);
            }
        }
        // M10R toggle is on but HAL check failed - still log but do not block
        if (m10rEnabled() && !HalCapabilities.hasM10r()) {
            log(Log.INFO, TAG, "M10R toggle on but HAL resources not present; entry will show but processing falls back to plain picture");
        }
        log(Log.WARN, TAG, "the Leica Essential style list is not one this module knows; "
                + "the CMOS style is not added");
        if (m10rEnabled() && !problems.contains(MSG_LEGEND)) {
            problems.add(MSG_LEGEND);   // only worth a notice when the CMOS entry was asked for
        }
    }

    private static boolean classExists(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // The method that fills a component's item list: not static, returns nothing and takes one
    // argument (the mode data). Named differently in each build.
    private static Method findBuilder(Class<?> cls) throws NoSuchMethodException {
        Method found = null;
        for (Method m : cls.getDeclaredMethods()) {
            Class<?> param = m.getParameterCount() == 1 ? m.getParameterTypes()[0] : null;
            // The argument is the mode data, but the build erases its type to Object
            if (m.getReturnType() == void.class && param != null
                    && !java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && !m.isSynthetic()
                    && (param == Object.class
                    || param.getName().startsWith("com.android.camera.data.data."))) {
                if (found != null) {
                    throw new NoSuchMethodException("more than one list builder in " + cls.getName());
                }
                found = m;
            }
        }
        if (found == null) {
            throw new NoSuchMethodException("no list builder in " + cls.getName());
        }
        return found;
    }

    // The id of the camera resource that matches the CCD one in the same id class (by the name the
    // id class gives it): the name with "from" replaced by "to", or the id itself when there is no
    // such resource.
    private static int swap(Class<?> ids, int id, String from, String to) {
        try {
            for (Field f : ids.getFields()) {
                if (f.getType() == int.class && f.getInt(null) == id) {
                    String name = f.getName().contains(from) && !f.getName().equals(from)
                            ? f.getName().replace(from, to)
                            : f.getName().equals(from) ? to : f.getName();
                    return ids.getField(name).getInt(null);
                }
            }
        } catch (Throwable ignored) {
        }
        return id;
    }

    // Texts the camera build does not have: a made-up string id (this base) answered from the
    // module's own strings, in the phone's language.
    private void installTextHook() {
        if (textHooked) {
            return;
        }
        try {
            Method getText = Resources.class.getDeclaredMethod("getText", int.class);
            hook(getText).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) != ASSET_TEXT_BASE) {
                    return chain.proceed();
                }
                try {
                    Application app = currentApplication();
                    Resources own = app.getPackageManager().getResourcesForApplication(moduleInfo);
                    return own.getText(R.string.m10r_title);
                } catch (Throwable t) {
                    return "CMOS";
                }
            });
            textHooked = true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook Resources.getText: " + t);
        }
    }

    // The camera's item class has no constructor in this build (the build inlined the empty one
    // away), so an instance is made without calling one; the fields are all set by the caller.
    private static Object allocate(Class<?> cls) throws ReflectiveOperationException {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field the = unsafe.getDeclaredField("theUnsafe");
        the.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class).invoke(the.get(null), cls);
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Field field = cls.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    // ---- 1c. M3/M9 as separate Leica Looks entries -------------------------------------------
    // The Looks panel is a static table (E9.h) from a filter enum value to a display config, and
    // c()/d() turn it into the back/front lists. Adding two items there leaves every stock filter
    // and every stock look untouched: the enum values we borrow are hidden film entries on this
    // build, and their look name - which MIVIRenderTag puts into "lutNames" and MIVILutSaver
    // writes out - is redirected to our own files in Di.k.d.
    private static final int LOOK_TEXT_BASE = 0x7e030000;   // made-up ids for the chip labels
    private static final int LOOK_M3 = 91;                  // enum entry N_NEUTRAL, unused in Photo
    private static final int LOOK_M9 = 92;                  // enum entry N_SOFT, unused in Photo
    private static final int LOOK_M10R = 93;                // enum entry N_WHITENING, unused in Photo
    // Authentic 17U M3 Monopan extraction. The old 167c769a... file was the
    // earlier reproduced fallback and must be replaced.
    private static final String M3_MD5 = "c4ff65cebdcd8edfa7146c6ab2f838f9";
    private static final String M9_MD5 = "ecbafbf8130ba7340749e95ace25616b";
    // Derived warm-density LUT (the 18 PM ships the M10R 3D LUTs neutral), so this
    // file is a bake of the -0.95 EV / warmth 0.3 grade, not a Xiaomi asset.
    private static final String M10R_MD5 = "e5176c87c69877917b3d24e9b74f5348";
    private static volatile int ACTIVE_LOOK = 0;
    private static volatile long ACTIVE_LOOK_AT = 0L;
    private static volatile java.lang.reflect.Method EFFECT_CONTROLLER_GET;
    private static volatile java.lang.reflect.Field EFFECT_CONTROLLER_FILTER;
    private static final java.util.Set<String> PREVIEW_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final String LOOKS_ENTRIES_KEY = "looks_entries_enabled";
    private volatile boolean looksEntriesHooked;
    private volatile boolean lookTextHooked;

    private boolean looksEntriesEnabled() {
        try {
            android.content.SharedPreferences p = getRemotePreferences("hypercameramod");
            if (p != null) {
                return p.getBoolean(LOOKS_ENTRIES_KEY, true);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot read " + LOOKS_ENTRIES_KEY + ": " + t);
        }
        return false;
    }

    private void installLeicaLooksEntries(ClassLoader loader) {
        if (looksEntriesHooked || loader == null || !looksEntriesEnabled()) {
            return;
        }
        try {
            Class<?> enums = Class.forName("p3.d", false, loader);
            Class<?> looks = Class.forName("E9.h", false, loader);
            Class<?> config = Class.forName("E9.h$a", false, loader);
            Class<?> item = Class.forName("j3.b", false, loader);
            Method back = looks.getDeclaredMethod("c");
            Method build = looks.getDeclaredMethod("b", enums, int.class, config);
            Object[] values = enums.getEnumConstants();
            if (values == null || values.length <= LOOK_M10R) {
                log(Log.WARN, TAG, "looks: filter enum has only "
                        + (values == null ? 0 : values.length) + " entries, M3/M9/M10R skipped");
                return;
            }
            Object base = null;
            for (Field f : looks.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || f.getType().isPrimitive()) {
                    continue;
                }
                f.setAccessible(true);
                Object value = f.get(null);
                if (value instanceof java.util.Map<?, ?> map && !map.isEmpty()) {
                    for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                        String n = e.getKey() instanceof Enum<?> en ? en.name() : "";
                        if (n.contains("BW") || n.contains("MONO")) {
                            base = e.getValue();
                            break;
                        }
                    }
                    if (base == null) {
                        base = map.values().iterator().next();
                    }
                    break;
                }
            }
            if (base == null) {
                log(Log.WARN, TAG, "looks: no config table found, M3/M9 skipped");
                return;
            }
            final Object baseCfg = base;
            Constructor<?> makeConfig = config.getDeclaredConstructor(int.class, int.class,
                    int.class, int.class, int.class, int.class, int.class, java.util.ArrayList.class,
                    int.class, java.util.ArrayList.class, java.util.ArrayList.class);
            makeConfig.setAccessible(true);
            Field samples = configField(config, "j");
            Field labels = configField(config, "k");
            Field icon = configField(config, "e");
            int monochromeIcon = icon.getInt(baseCfg);
            if (monochromeIcon == 0) {
                monochromeIcon = currentApplication().getResources().getIdentifier(
                        "master_filter_color_monochrome_mm", "drawable", CAMERA_PACKAGE);
            }
            installLookTextHook();
            final int foIcon = monochromeIcon;
            hook(back).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof java.util.List<?> list)) {
                    return result;
                }
                try {
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> target = (java.util.List<Object>) list;
                    String[] names = {"M3", "M9", "M10R"};
                    int[] ordinals = {LOOK_M3, LOOK_M9, LOOK_M10R};
                    for (int i = 0; i < ordinals.length; i++) {
                        Object cfg = makeConfig.newInstance(13 + i, -1, -1,
                                LOOK_TEXT_BASE | (i + 1), foIcon, foIcon, 0, null, -1,
                                samples.get(baseCfg), labels.get(baseCfg));
                        target.add(build.invoke(null, values[ordinals[i]], 10, cfg));
                        log(Log.INFO, TAG, "looks: added " + names[i] + " entry (ordinal "
                                + ordinals[i] + ")");
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "looks: cannot add M3/M9 entries: " + t);
                }
                return result;
            });
            installLookLutHooks(loader);
            installPreviewLutHook(loader);
            looksEntriesHooked = true;
            log(Log.INFO, TAG, "installed M3/M9 Looks entries");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot install M3/M9 Looks entries: " + t);
        }
    }

    // ---- 1d. M3/M9 entries in the FILM strip -------------------------------------------------
    // The strip is built from Ii.i0.d() and handed out per category by EffectController.q(int);
    // appending there adds two chips without touching any stock filter.
    private static final int STRIP_ICON_BASE = 0x7e040000;
    private volatile boolean stripEntriesHooked;

    private void installFilterStripEntries(ClassLoader loader) {
        if (stripEntriesHooked || loader == null || !looksEntriesEnabled()) {
            return;
        }
        try {
            Class<?> item = Class.forName("j3.b", false, loader);
            Constructor<?> make = item.getDeclaredConstructor(int.class, int.class, int.class,
                    int.class, int.class, int.class, String.class);
            make.setAccessible(true);
            Field ordinalField = field(item, "b");
            Field nameField = field(item, "c");
            Field iconField = field(item, "e");
            Field nameIconField = field(item, "f");
            Field samplesField = field(item, "g");
            Field labelsField = field(item, "h");
            Field descField = field(item, "i");
            Field orderField = field(item, "k");
            Field cloudField = field(item, "l");
            installStripIconHooks();
            Method list = Class.forName("Ii.i0", false, loader).getDeclaredMethod("d");
            hook(list).intercept(chain -> {
                Object result = chain.proceed();
                addStripEntries(result, make, ordinalField, nameField, iconField, nameIconField,
                        samplesField, labelsField, descField, orderField, cloudField);
                return result;
            });
            try {
                Method category = Class.forName("com.xiaomi.camera.effect.EffectController",
                        false, loader).getDeclaredMethod("q", int.class);
                hook(category).intercept(chain -> {
                    Object result = chain.proceed();
                    Object cat = chain.getArg(0);
                    if (cat instanceof Integer c && (c == 2 || c == 10)) {
                        addStripEntries(result, make, ordinalField, nameField, iconField,
                                nameIconField, samplesField, labelsField, descField, orderField,
                                cloudField);
                    }
                    return result;
                });
            } catch (Throwable t) {
                log(Log.WARN, TAG, "strip: no EffectController.q: " + t);
            }
            stripEntriesHooked = true;
            log(Log.INFO, TAG, "installed M3/M9 filter-strip entries");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot install M3/M9 filter-strip entries: " + t);
        }
    }

    private void addStripEntries(Object result, Constructor<?> make, Field ordinalField,
                                 Field nameField, Field iconField, Field nameIconField,
                                 Field samplesField, Field labelsField, Field descField,
                                 Field orderField, Field cloudField) {
        if (!(result instanceof java.util.List<?> list)) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            java.util.List<Object> target = (java.util.List<Object>) list;
            boolean hasM3 = false;
            boolean hasM9 = false;
            boolean hasM10R = false;
            for (Object o : target) {
                int ord = ordinalField.getInt(o);
                hasM3 |= ord == LOOK_M3;
                hasM9 |= ord == LOOK_M9;
                hasM10R |= ord == LOOK_M10R;
            }
            if (hasM3 && hasM9 && hasM10R) {
                return;
            }
            Object proto = target.isEmpty() ? null : target.get(0);
            int icon = proto == null ? 0 : iconField.getInt(proto);
            int nameIcon = proto == null ? 0 : nameIconField.getInt(proto);
            ArrayList<?> samples = proto == null ? null : (ArrayList<?>) samplesField.get(proto);
            ArrayList<?> labels = proto == null ? null : (ArrayList<?>) labelsField.get(proto);
            int desc = proto == null ? 0 : descField.getInt(proto);
            int order = proto == null ? 100 : Math.max(100, orderField.getInt(proto) + 90);
            // The strip inserts a filter it does not know yet at position 1, so the item added
            // last ends up first: add M9 first, M3 last, to get "M3, M9".
            // The name icon is the stock one: the palette editor draws that field large, and
            // a generated label there shows up as text over a transparency checkerboard.
            if (!hasM10R) {
                target.add(stripItem(make, LOOK_M10R, LOOK_TEXT_BASE | 3, nameIcon,
                        icon, nameIcon, samples, labels, desc, order - 1, ordinalField,
                        samplesField, labelsField, descField, cloudField));
            }
            if (!hasM9) {
                target.add(stripItem(make, LOOK_M9, LOOK_TEXT_BASE | 2, nameIcon,
                        icon, nameIcon, samples, labels, desc, order, ordinalField,
                        samplesField, labelsField, descField, cloudField));
            }
            if (!hasM3) {
                target.add(stripItem(make, LOOK_M3, LOOK_TEXT_BASE | 1, nameIcon,
                        icon, nameIcon, samples, labels, desc, order + 1, ordinalField,
                        samplesField, labelsField, descField, cloudField));
            }
            log(Log.INFO, TAG, "strip: M3/M9/M10R entries added (" + target.size() + " items)");
            StringBuilder ordinalList = new StringBuilder();
            for (Object o : target) {
                ordinalList.append(ordinalField.getInt(o)).append(' ');
            }
            log(Log.INFO, TAG, "strip: ordinals " + ordinalList);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "strip: cannot add M3/M9/M10R: " + t);
        }
    }

    private Object stripItem(Constructor<?> make, int ordinal, int nameRes, int nameIconRes,
                             int icon, int fallbackNameIcon, ArrayList<?> samples,
                             ArrayList<?> labels, int desc, int order, Field ordinalField,
                             Field samplesField, Field labelsField, Field descField,
                             Field cloudField) throws ReflectiveOperationException {
        Object item = make.newInstance(0, ordinal, nameRes, icon, nameIconRes, order, "NORMAL");
        samplesField.set(item, samples);
        labelsField.set(item, labels);
        descField.setInt(item, desc);
        cloudField.setInt(item, 0);
        return item;
    }

    private void installStripIconHooks() {
        try {
            hook(ImageView.class.getDeclaredMethod("setImageResource", int.class)).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) != STRIP_ICON_BASE) {
                    return chain.proceed();
                }
                ImageView view = (ImageView) chain.getThisObject();
                view.setImageDrawable(stripLabel(view.getResources(), id & 0xFFFF));
                return null;
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "strip: no ImageView hook: " + t);
        }
        try {
            hook(Resources.class.getDeclaredMethod("getDrawable", int.class,
                    Resources.Theme.class)).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) != STRIP_ICON_BASE) {
                    return chain.proceed();
                }
                return stripLabel(null, id & 0xFFFF);
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "strip: no getDrawable hook: " + t);
        }
    }

    private static Drawable stripLabel(Resources res, int which) {
        String text = which == 1 ? "M3" : "M9";
        float density = res == null ? 2.75f : res.getDisplayMetrics().density;
        int size = Math.round(36 * density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextSize(size * 0.42f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(text, size / 2f, size / 2f - (metrics.ascent + metrics.descent) / 2f, paint);
        return new BitmapDrawable(res, bitmap);
    }

    private static Field configField(Class<?> config, String name) throws NoSuchFieldException {
        Field f = config.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    // The chip label: a made-up string id answered from here, so no camera resource is needed.
    private void installLookTextHook() {
        if (lookTextHooked) {
            return;
        }
        try {
            hook(Resources.class.getDeclaredMethod("getText", int.class)).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) != LOOK_TEXT_BASE) {
                    return chain.proceed();
                }
                return (id & 0xFFFF) == 1 ? "M3"
                        : (id & 0xFFFF) == 3 ? "M10R" : "M9";
            });
            hook(Resources.class.getDeclaredMethod("getString", int.class)).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) != LOOK_TEXT_BASE) {
                    return chain.proceed();
                }
                return (id & 0xFFFF) == 1 ? "M3"
                        : (id & 0xFFFF) == 3 ? "M10R" : "M9";
            });
            lookTextHooked = true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook Resources.getText (look labels): " + t);
        }
    }

    // Di.k.d(filterId, degree) is what the render tag and the LUT saver ask for the look. For our
    // two ordinals it answers a bean with our own name; everything else is copied from the
    // camera's own bean, so the candy script keeps the stock shape.
    private void installLookLutHooks(ClassLoader loader) {
        try {
            Class<?> bean = Class.forName("p3.b", false, loader);
            Method resolve = Class.forName("Di.k", false, loader)
                    .getDeclaredMethod("d", int.class, int.class);
            Constructor<?> make = bean.getDeclaredConstructor(String.class, int.class, int.class,
                    float[].class, int.class);
            make.setAccessible(true);
            Field idField = field(bean, "i");
            Field degreeField = field(bean, "k");
            Field paramsField = field(bean, "l");
            Field nameField = field(bean, "j");
            hook(resolve).intercept(chain -> {
                Object result = chain.proceed();
                int id = (Integer) chain.getArg(0);
                int ordinal = id & 0xFFFF;
                String name = ordinal == LOOK_M3 ? "lut_m3"
                        : ordinal == LOOK_M9 ? "lut_m9"
                        : ordinal == LOOK_M10R ? "lut_m10r" : null;
                if (name == null || result == null) {
                    return result;
                }
                ACTIVE_LOOK = ordinal;
                ACTIVE_LOOK_AT = System.currentTimeMillis();
                log(Log.INFO, TAG, "look resolve: id=" + id + " ordinal=" + ordinal
                        + " name=" + name);
                writeLegendStyle(ordinal == LOOK_M3 ? 2 : ordinal == LOOK_M10R ? 3 : 1);
                return make.newInstance(name, idField.getInt(result), degreeField.getInt(result),
                        (float[]) paramsField.get(result), 0);
            });
            hook(bean.getDeclaredMethod("a", android.content.Context.class)).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    String lookName = (String) nameField.get(self);
                    if (!"lut_m3".equals(lookName) && !"lut_m9".equals(lookName)
                            && !"lut_m10r".equals(lookName)) {
                        return result;
                    }
                    ensureLookFile(lookName);
                    android.graphics.Bitmap bm = freshLookBitmap(lookName);
                    if (bm != null) {
                        if (PREVIEW_LOGGED.add("bean:" + lookName)) {
                            log(Log.INFO, TAG, "look bitmap " + lookName + " -> "
                                    + bm.getWidth() + "x" + bm.getHeight());
                        }
                        return bm;
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "look bitmap substitution failed: " + t);
                }
                return result;
            });
            log(Log.INFO, TAG, "installed M3/M9 look names (lut_m3, lut_m9)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot install M3/M9 look names: " + t);
        }
    }

    // The preview renderer loads the native enum LUT through Xu.g.b(context, name) (or
    // Xu.g.a(path)). M3/M9 use slots 91/92, whose native names are lut_normal_neutral and
    // lut_normal_soft, so the preview was showing those stock LUTs. Return our own bitmap for
    // those two names.
    private void installPreviewLutHook(ClassLoader loader) {
        try {
            Class<?> loaderClass = Class.forName("Xu.g", false, loader);
            Method fromRaw = loaderClass.getDeclaredMethod("b",
                    android.content.Context.class, String.class);
            Method fromFile = loaderClass.getDeclaredMethod("a", String.class);
            hook(fromRaw).intercept(chain -> {
                String name = (String) chain.getArg(1);
                Bitmap bm = previewLut(name);
                return bm != null ? bm : chain.proceed();
            });
            hook(fromFile).intercept(chain -> {
                String path = (String) chain.getArg(0);
                Bitmap bm = previewLutFromPath(path);
                return bm != null ? bm : chain.proceed();
            });
            // The GL renderer's own LUT resolver is the path that actually runs
            // in the camera app UID. It cannot read /data/vendor/camera, so give
            // it the APK asset bitmap directly.
            Class<?> gl = Class.forName("com.xiaomi.utils.OpenGl3dUtils", false, loader);
            Method resolve = gl.getDeclaredMethod("a",
                    android.content.Context.class, String.class,
                    boolean.class, boolean.class);
            hook(resolve).intercept(chain -> {
                String name = (String) chain.getArg(1);
                Bitmap bm = previewLut(name);
                if (PREVIEW_LOGGED.add("gl:" + name)) {
                    log(Log.INFO, TAG, "OpenGl3dUtils LUT " + name
                            + " active=" + currentFilterOrdinal()
                            + " -> " + describeBitmap(bm));
                }
                return bm != null ? bm : chain.proceed();
            });
            installPreviewLutSaverHook(loader);
            installFilterSelectHook(loader);
            installCandyProbeHook(loader);
            log(Log.INFO, TAG, "installed M3/M9 preview LUT hook");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook Xu.g: " + t);
        }
    }

    private void installFilterSelectHook(ClassLoader loader) {
        try {
            Class<?> ec = Class.forName("com.xiaomi.camera.effect.EffectController",
                    false, loader);
            Method setEffect = ec.getDeclaredMethod("e0", int.class, int.class);
            EFFECT_CONTROLLER_GET = ec.getDeclaredMethod("u");
            EFFECT_CONTROLLER_FILTER = ec.getDeclaredField("h");
            EFFECT_CONTROLLER_FILTER.setAccessible(true);
            hook(setEffect).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                int ordinal = id & 0xFFFF;
                if (ordinal == LOOK_M3 || ordinal == LOOK_M9) {
                    ACTIVE_LOOK = ordinal;
                    ACTIVE_LOOK_AT = System.currentTimeMillis();
                    log(Log.INFO, TAG, "effect filter id=" + id
                            + " ordinal=" + ordinal);
                } else if (ACTIVE_LOOK != 0) {
                    ACTIVE_LOOK = 0;
                    log(Log.INFO, TAG, "effect filter cleared by e0 id=" + id);
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "installed filter selection hook");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook EffectController.e0: " + t);
        }
    }

    // Logging-only probe: record the LUT bitmap shape CandySDK receives,
    // without changing it. Used to match the native preview LUT format safely.
    private void installCandyProbeHook(ClassLoader loader) {
        try {
            Class<?> candy = Class.forName("com.xiaomi.milab.filtersdk.CandySDK",
                    false, loader);
            Method setIndexed = candy.getDeclaredMethod("f", int.class, Bitmap.class);
            Method setSingle = candy.getDeclaredMethod("g", Bitmap.class);
            hook(setIndexed).intercept(chain -> {
                Bitmap bm = (Bitmap) chain.getArg(1);
                if (PREVIEW_LOGGED.add("probe-f")) {
                    log(Log.INFO, TAG, "CandySDK f index=" + chain.getArg(0)
                            + " bmp=" + describeBitmap(bm));
                }
                return chain.proceed();
            });
            hook(setSingle).intercept(chain -> {
                Bitmap bm = (Bitmap) chain.getArg(0);
                if (PREVIEW_LOGGED.add("probe-g")) {
                    log(Log.INFO, TAG, "CandySDK g bmp=" + describeBitmap(bm));
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "installed CandySDK LUT probe");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot probe CandySDK: " + t);
        }
    }

    private static String describeBitmap(Bitmap bm) {
        if (bm == null) {
            return "null";
        }
        return bm.getWidth() + "x" + bm.getHeight() + "/" + bm.getConfig()
                + "/recycled=" + bm.isRecycled();
    }

    // The live preview in some builds reads the native Neutral/Soft file that
    // MIVILutSaver writes. Replace that writer for slots 91/92 only, so the native
    // preview name resolves to the same LUT as capture.
    private void installPreviewLutSaverHook(ClassLoader loader) {
        try {
            Class<?> saver = Class.forName("com.xiaomi.camera.mivi.filter.MIVILutSaver",
                    false, loader);
            Method save = saver.getDeclaredMethod("saveLutByFilterId", int.class);
            Class<?> cache = Class.forName("com.xiaomi.camera.mivi.filter.MIVILutCache",
                    false, loader);
            Object instance = cache.getMethod("getInstance").invoke(null);
            Method add = cache.getMethod("addLutName", String.class);
            Method remove = cache.getMethod("removeLutName", String.class);
            hook(save).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                int ordinal = id & 0xFFFF;
                if (ordinal != LOOK_M3 && ordinal != LOOK_M9 && ordinal != LOOK_M10R) {
                    return chain.proceed();
                }
                String ourName = ordinal == LOOK_M3 ? "lut_m3"
                        : ordinal == LOOK_M9 ? "lut_m9" : "lut_m10r";
                String nativeName = ordinal == LOOK_M3
                        ? "lut_normal_neutral" : ordinal == LOOK_M9
                        ? "lut_normal_soft" : "lut_normal_whitening";
                try {
                    java.nio.file.Path dst = java.nio.file.Paths.get(
                            "/data/vendor/camera/" + nativeName + ".png");
                    if (!writeLookAsset(ourName, dst)) {
                        throw new java.io.IOException("asset write failed");
                    }
                    dst.toFile().setReadable(true, false);
                    remove.invoke(instance, nativeName);
                    add.invoke(instance, nativeName);
                    if (PREVIEW_LOGGED.add("native:" + nativeName)) {
                        log(Log.INFO, TAG, "preview native LUT " + nativeName
                                + " <- APK asset " + ourName);
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "native preview LUT failed for "
                            + nativeName + ": " + t);
                }
                return null;
            });
            log(Log.INFO, TAG, "installed M3/M9/M10R native preview LUT hook");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook MIVILutSaver: " + t);
        }
    }

    private Bitmap freshLookBitmap(String name) {
        String asset = "lut_m3".equals(name) ? "leica_luts/M3_Monopan.png"
                : "lut_m9".equals(name) ? "leica_luts/130_LeicaNatural.png"
                : "lut_m10r".equals(name) ? "leica_luts/M10R_Cmos.png" : null;
        if (moduleApk == null) {
            return null;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(moduleApk)) {
            java.util.zip.ZipEntry entry = zip.getEntry("assets/" + asset);
            if (entry == null) {
                return null;
            }
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot decode fresh look " + name + ": " + t);
            return null;
        }
    }

    private static int currentFilterOrdinal() {
        try {
            java.lang.reflect.Method get = EFFECT_CONTROLLER_GET;
            java.lang.reflect.Field filter = EFFECT_CONTROLLER_FILTER;
            if (get != null && filter != null) {
                Object controller = get.invoke(null);
                if (controller != null) {
                    return ((Integer) filter.get(controller)) & 0xFFFF;
                }
            }
        } catch (Throwable ignored) {
        }
        return ACTIVE_LOOK;
    }

    private Bitmap previewLut(String name) {
        if (name == null) {
            return null;
        }
        // The preview GL renderer asks the enum LUT name. Slots 91/92 are not in the
        // render engine's filter table, so it requests the neutral "lut_nc" LUT instead
        // of lut_normal_neutral/lut_normal_soft. While M3/M9 is selected, answer with
        // our own bitmap for that neutral name.
        //
        // Always decode a fresh bitmap: OpenGl3dUtils.c() recycles the bitmap after the
        // GL upload, so a cached instance would be recycled on the next upload and the
        // native side would read freed pixels.
        if (name.contains("lut_m3")) {
            if (PREVIEW_LOGGED.add(name)) {
                log(Log.INFO, TAG, "preview LUT " + name + " -> lut_m3");
            }
            return freshLookBitmap("lut_m3");
        }
        if (name.contains("lut_m9")) {
            if (PREVIEW_LOGGED.add(name)) {
                log(Log.INFO, TAG, "preview LUT " + name + " -> lut_m9");
            }
            return freshLookBitmap("lut_m9");
        }
        if (name.contains("lut_m10r")) {
            if (PREVIEW_LOGGED.add(name)) {
                log(Log.INFO, TAG, "preview LUT " + name + " -> lut_m10r");
            }
            return freshLookBitmap("lut_m10r");
        }
        if ("lut_normal_neutral".equals(name)) {
            return freshLookBitmap("lut_m3");
        }
        if ("lut_normal_soft".equals(name)) {
            return freshLookBitmap("lut_m9");
        }
        if ("lut_normal_whitening".equals(name)) {
            return freshLookBitmap("lut_m10r");
        }
        // The GL renderer falls back to a stock LUT name for enum slots that are not in
        // its filter table: 91/92 get "lut_nc", 93 gets "lut_normal_lively". Gate on the
        // live filter ordinal so a real stock filter keeps its own LUT.
        if ("lut_nc".equals(name) || "0".equals(name)
                || "lut_debug_blank_lut".equals(name)
                || "lut_normal_lively".equals(name)) {
            int look = currentFilterOrdinal();
            if (look == LOOK_M3 || look == LOOK_M9 || look == LOOK_M10R) {
                Bitmap bm = freshLookBitmap(look == LOOK_M3 ? "lut_m3"
                        : look == LOOK_M9 ? "lut_m9" : "lut_m10r");
                if (bm != null && PREVIEW_LOGGED.add("neutral:" + name + ":" + look)) {
                    log(Log.INFO, TAG, "preview LUT " + name + " -> "
                            + (look == LOOK_M3 ? "lut_m3" : look == LOOK_M9
                            ? "lut_m9" : "lut_m10r"));
                }
                return bm;
            }
            return null;
        }
        return null;
    }

    private Bitmap previewLutFromPath(String path) {
        if (path == null) {
            return null;
        }
        if (path.contains("lut_m3")) {
            return freshLookBitmap("lut_m3");
        }
        if (path.contains("lut_m9")) {
            return freshLookBitmap("lut_m9");
        }
        if (path.contains("lut_m10r")) {
            return freshLookBitmap("lut_m10r");
        }
        return null;
    }

    // The HAL wrapper node (LegendFilterInstance0) reads this to pick the parameter table: the
    // look registry is the last thing that runs before the request is handed to the engine.
    private void writeLegendStyle(int style) {
        try {
            java.io.File dir = new java.io.File("/data/vendor/camera");
            if (!dir.isDirectory()) {
                return;
            }
            try (java.io.FileOutputStream out =
                         new java.io.FileOutputStream(new java.io.File(dir, "hcm_style"))) {
                out.write(String.valueOf(style).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            ensureLookFile(style == 2 ? "lut_m3" : style == 3 ? "lut_m10r" : "lut_m9");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot write hcm_style: " + t);
        }
    }

    // The engine loads the look from /data/vendor/camera/<name>.png. The module owns those two
    // names, so it writes its own LUTs there, next to the files the camera saves for the stock
    // filters; nothing stock is touched.
    private void ensureLookFile(String name) {
        String asset = "lut_m3".equals(name) ? "leica_luts/M3_Monopan.png"
                : "lut_m9".equals(name) ? "leica_luts/130_LeicaNatural.png"
                : "lut_m10r".equals(name) ? "leica_luts/M10R_Cmos.png" : null;
        if (moduleApk == null || asset == null) {
            return;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(moduleApk)) {
            java.util.zip.ZipEntry entry = zip.getEntry("assets/" + asset);
            if (entry == null) {
                return;
            }
            java.io.File target = new java.io.File("/data/vendor/camera", name + ".png");
            // Accept only the known-good staged LUTs. A stale or partial file from an
            // earlier experiment must never win over the authoritative APK/KSU copy.
            if (target.length() > 0) {
                String md5 = md5(target);
                boolean valid = "lut_m9".equals(name) ? M9_MD5.equals(md5)
                        : "lut_m3".equals(name) ? M3_MD5.equals(md5)
                        : "lut_m10r".equals(name) && M10R_MD5.equals(md5);
                if (valid) {
                    return;
                }
                log(Log.WARN, TAG, "replacing invalid " + target + " md5=" + md5);
            }
            try (java.io.InputStream in = zip.getInputStream(entry);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            log(Log.INFO, TAG, "wrote " + target + " (" + target.length() + " bytes)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot write " + name + ": " + t);
        }
    }

    private boolean writeLookAsset(String name, java.nio.file.Path dst) {
        String asset = "lut_m3".equals(name) ? "leica_luts/M3_Monopan.png"
                : "lut_m9".equals(name) ? "leica_luts/130_LeicaNatural.png"
                : "lut_m10r".equals(name) ? "leica_luts/M10R_Cmos.png" : null;
        if (moduleApk == null || asset == null) {
            return false;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(moduleApk)) {
            java.util.zip.ZipEntry entry = zip.getEntry("assets/" + asset);
            if (entry == null) {
                return false;
            }
            try (java.io.InputStream in = zip.getInputStream(entry);
                 java.io.OutputStream out = java.nio.file.Files.newOutputStream(dst)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot write " + dst + " from " + asset + ": " + t);
            return false;
        }
    }

    private static String md5(java.io.File file) {
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void installStyleHook(ClassLoader loader) {
        if (styleHooked) {
            return;
        }
        for (StyleProfile profile : STYLE_PROFILES) {
            try {
                Class<?> list = Class.forName(profile.listClass(), false, loader);
                Method getItems = list.getDeclaredMethod("getItems");
                Class<?> item = Class.forName(profile.itemClass(), false, loader);
                Field key = field(item, "r");
                Field[] images = {field(item, "c"), field(item, "g"), field(item, "h")};
                Field currentMode = null;
                for (Class<?> c = list.getSuperclass(); c != null && currentMode == null;
                        c = c.getSuperclass()) {
                    try {
                        currentMode = field(c, "mCurrentMode");
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                final Field modeField = currentMode;

                hook(getItems).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        if (result instanceof List<?> items) {
                            Object original = null;
                            Object authentic = null;
                            for (Object entry : items) {
                                if (STYLE_ORIGINAL.equals(key.get(entry))) {
                                    original = entry;
                                } else if (STYLE_AUTHENTIC.equals(key.get(entry))) {
                                    authentic = entry;
                                }
                            }
                            // Only when Original really shares the Authentic Leica UI badge
                            if (original != null && authentic != null
                                    && images[0].getInt(original) == images[0].getInt(authentic)
                                    && isLeicaBadge(images[0].getInt(original))) {
                                for (Field f : images) {
                                    f.setInt(original, ASSET_ICON_BASE | ASSET_ORIGINAL_BADGE);
                                }
                                log(Log.INFO, TAG, "gave the Original colour style its own Leica UI badge");
                            }
                        }
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "could not fix the Original colour style badge", t);
                    }
                    try {
                        if (modeField != null && result instanceof List<?> items
                                && modeField.getInt(chain.getThisObject()) == MODE_PORTRAIT
                                && !originalPortraitEnabled()) {
                            List<Object> kept = new ArrayList<>(items.size());
                            for (Object entry : items) {
                                if (!STYLE_ORIGINAL.equals(key.get(entry))) {
                                    kept.add(entry);
                                }
                            }
                            if (kept.size() != items.size()) {
                                return kept;
                            }
                        }
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "could not remove Original portrait", t);
                    }
                    return result;
                });
                // A camera that was left on Original portrait keeps value "2" in
                // pref_camera_cv_type_key_171. Fall back to Master (the Portrait default) so the
                // removed style is never requested from the hardware layer.
                hook(list.getDeclaredMethod("getComponentValue", int.class)).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        if (chain.getArg(0) instanceof Integer mode && mode == MODE_PORTRAIT
                                && STYLE_ORIGINAL.equals(result) && !originalPortraitEnabled()) {
                            log(Log.INFO, TAG, "moved the portrait style from Original to Master");
                            return STYLE_MASTER;
                        }
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "could not fall back from Original portrait", t);
                    }
                    return result;
                });
                // The badge of the chosen style (top bar) and of a given style come from two more
                // methods of the same component, with the same two-value mapping.
                Method getValue = list.getMethod("getComponentValue", int.class);
                hook(list.getDeclaredMethod(profile.currentIconMethod())).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        if (result instanceof Integer id && isLeicaBadge(id)
                                && STYLE_ORIGINAL.equals(getValue.invoke(chain.getThisObject(), 253))) {
                            return ASSET_ICON_BASE | ASSET_ORIGINAL_BADGE;
                        }
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "could not fix the chosen style badge", t);
                    }
                    return result;
                });
                hook(list.getDeclaredMethod(profile.valueIconMethod(), String.class))
                        .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        if (result instanceof Integer id && isLeicaBadge(id)
                                && STYLE_ORIGINAL.equals(chain.getArg(0))) {
                            return ASSET_ICON_BASE | ASSET_ORIGINAL_BADGE;
                        }
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "could not fix the style badge", t);
                    }
                    return result;
                });
                styleHooked = true;
                installAssetHooks();
                log(Log.INFO, TAG, "hooked " + profile.listClass() + ".getItems (colour styles, "
                        + "layout " + profile.name() + ")");
                return;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "colour style layout " + profile.name()
                        + " does not match: " + t);
            }
        }
    }

    // The Leica UI's badges are the drawables whose names end in _lc_sp
    private static boolean isLeicaBadge(int id) {
        try {
            Application app = currentApplication();
            return app != null && app.getResources().getResourceEntryName(id).endsWith("_lc_sp");
        } catch (Throwable t) {
            return false;
        }
    }

    // The item's images are made-up resource ids, answered here wherever the camera loads them:
    // ImageView.setImageResource (the style picker) and Resources.getDrawable.
    private void installAssetHooks() {
        if (assetsHooked) {
            return;
        }
        try {
            Method getDrawable = Resources.class.getDeclaredMethod("getDrawable", int.class,
                    Resources.Theme.class);
            hook(getDrawable).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) != ASSET_ICON_BASE) {
                    return chain.proceed();
                }
                return assetDrawable((Resources) chain.getThisObject(), id & 0xFFFF);
            });
            // AppCompat image views (the top bar) look the id up with getValue before they load
            // the drawable, and fail on an id that is not in any resource table; give them a
            // harmless value so they go on to load the drawable, which is answered above.
            Method getValue = Resources.class.getDeclaredMethod("getValue", int.class,
                    android.util.TypedValue.class, boolean.class);
            hook(getValue).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) != ASSET_ICON_BASE) {
                    return chain.proceed();
                }
                android.util.TypedValue value = (android.util.TypedValue) chain.getArg(1);
                value.type = android.util.TypedValue.TYPE_INT_DEC;
                value.data = 0;
                value.string = null;
                value.resourceId = id;
                value.assetCookie = 0;
                value.changingConfigurations = 0;
                return null;
            });
            Method setImage = ImageView.class.getDeclaredMethod("setImageResource", int.class);
            hook(setImage).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) != ASSET_ICON_BASE) {
                    return chain.proceed();
                }
                ImageView view = (ImageView) chain.getThisObject();
                view.setImageDrawable(assetDrawable(view.getResources(), id & 0xFFFF));
                return null;
            });
            assetsHooked = true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook the image loading for the M10R images: " + t);
        }
    }

    // One of the M10R images, read from the module's own APK.
    private static synchronized Drawable assetDrawable(Resources resources, int index) {
        if (index == ASSET_ORIGINAL_BADGE) {
            try {
                Application app = currentApplication();
                Resources own = app.getPackageManager().getResourcesForApplication(moduleInfo);
                return own.getDrawable(R.drawable.ic_cvtype_original_lc_sp, null);
            } catch (Throwable t) {
                Log.w(TAG, "cannot load the Original badge: " + t);
                return new ColorDrawable(Color.TRANSPARENT);
            }
        }
        Bitmap bitmap = ASSET_CACHE.get(index);
        if (bitmap == null) {
            try (ZipFile zip = new ZipFile(moduleApk)) {
                var entry = zip.getEntry("assets/m10r/" + M10R_ASSETS[index] + ".webp");
                try (var in = zip.getInputStream(entry)) {
                    bitmap = BitmapFactory.decodeStream(in);
                }
                // The images are the camera's xhdpi ones; saying so lets the drawable scale them
                // to the screen the way the camera's own resources are scaled.
                bitmap.setDensity(android.util.DisplayMetrics.DENSITY_XHIGH);
                ASSET_CACHE.put(index, bitmap);
            } catch (Throwable t) {
                Log.w(TAG, "cannot read the M10R image " + index + ": " + t);
                return new ColorDrawable(Color.TRANSPARENT);
            }
        }
        return new BitmapDrawable(resources, bitmap);
    }

    // ---- telling the user ---------------------------------------------------------------------
    // A module has no screen inside the camera, so a problem is shown as a toast the first time
    // the camera starts after a camera update, then not again for that camera version.

    private void scheduleNotice() {
        if (problems.isEmpty() || !noticeScheduled.compareAndSet(false, true)) {
            return;
        }
        var handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            private int attempts;

            @Override
            public void run() {
                try {
                    Application app = currentApplication();
                    if (app == null) {
                        if (++attempts < 8) {
                            handler.postDelayed(this, 1500);
                        }
                        return;
                    }
                    showNotice(app);
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "could not show the notice: " + t);
                }
            }
        }, 3000);
    }

    private void showNotice(Application app) throws Exception {
        String version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0)
                .versionName;
        StringBuilder text = new StringBuilder();
        for (String problem : problems) {
            text.append(text.length() == 0 ? "" : " ").append(problem);
        }
        String key = "notice_" + version + "_" + text.toString().hashCode();
        var prefs = app.getSharedPreferences(TAG, Application.MODE_PRIVATE);
        if (prefs.getBoolean(key, false)) {
            return;
        }
        prefs.edit().putBoolean(key, true).apply();
        Toast.makeText(app, TAG + " (camera " + version + "): " + text, Toast.LENGTH_LONG).show();
    }

    private static Application currentApplication() {
        try {
            return (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
