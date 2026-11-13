package io.martin.hypercameramod;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.content.res.Resources;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedModule;

// Turns on three things the camera already contains but leaves off for this phone:
//  1. the Palette and Parameters filter panel (it needs the system property
//     camera.feature.styleVersion to be 2 or more, which the camera reads through
//     android.os.SystemProperties),
//  2. the "Original" colour style next to Leica Authentic and Leica Vibrant (bit 3 of the camera
//     characteristic xiaomi.capabilities.stylizationTypeMask, which this phone's camera hardware
//     layer does not set),
//  3. the Leica Essential CMOS (M10R) style, whose list entry is compiled out of this build. Off
//     unless the system property camera.feature.m10r is 1: the hardware layer of the 17 Ultra
//     has no M10R processing, so the style only gives a plain picture.
// Each part is independent; one that does not fit the camera build is skipped and logged.
public class ModuleMain extends XposedModule {
    private static final String TAG = "HyperCameraMod";
    private static final String CAMERA_PACKAGE = "com.android.camera";

    // The framework's log is only visible in LSPosed/Vector. Mirror every line to logcat too,
    // so a user can pull it with `su -c "logcat -d -s HyperCameraMod"` without the manager UI.
    private void dbg(int priority, String tag, String message) {
        log(priority, tag, message);
        Log.println(priority, tag, message);
    }

    private void dbg(int priority, String tag, String message, Throwable t) {
        log(priority, tag, message, t);
        Log.println(priority, tag, message + ": " + t);
    }

    // 1. Palette and Parameters
    private static final String STYLE_VERSION_KEY = "camera.feature.styleVersion";
    private static final int STYLE_VERSION = 2;

    // 2. Original colour style
    private static final String STYLIZATION_KEY = "xiaomi.capabilities.stylizationTypeMask";
    private static final int STYLIZATION_ORIGINAL = 1 << 3;

    // 3. M10R. The Leica Essential style list builds M3 (B&W) and M9 (CCD); a profile names where
    // that list and its item class live in one camera layout.
    private record LegendProfile(String name, String listClass, String itemClass,
                                 String stringIds, String imageIds) {
    }

    // Profiles ordered by preference. Selection is version-aware: if camera version contains
    // "500" (14 Ultra HyperOS 3: 6.7.000500.0), "6.7-hyperos3" is tried first.
    private static final LegendProfile[] LEGEND_PROFILES = {
            // Camera 6.7 (HyperOS 3): 6.7.000500.0 (14 Ultra) — uses same item class as layout 6.7
            new LegendProfile("6.7-hyperos3", "s2.B", "com.android.camera.data.data.d", "ci.e", "ci.b"),
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
            // Same order as LEGEND_PROFILES for consistency
            new StyleProfile("6.7-hyperos3", "s2.m", "com.android.camera.data.data.d", "o", "r"),
            new StyleProfile("6.7", "s2.m", "com.android.camera.data.data.d", "o", "r"),
    };
    private static final String STYLE_ORIGINAL = "2";
    private static final String STYLE_AUTHENTIC = "1";
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

    // What did not work, shown to the user once per camera version.
    private static final String MSG_LEGEND =
            "The Leica Essential style list is different on this camera version, so the CMOS "
                    + "style was not added.";
    private final List<String> problems = new CopyOnWriteArrayList<>();
    private final AtomicBoolean noticeScheduled = new AtomicBoolean();
    private final AtomicBoolean loadLogged = new AtomicBoolean();
    private final AtomicBoolean versionLogged = new AtomicBoolean();

    private volatile boolean propertiesHooked;
    private volatile boolean tabsHooked;
    private volatile boolean modeListHooked;
    private volatile boolean nameStyleHooked;
    private volatile boolean stylizationHooked;
    private volatile boolean legendHooked;
    private volatile boolean assetsHooked;
    private volatile boolean styleHooked;
    private volatile boolean textHooked;
    private volatile boolean filmFiltersHooked;

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        install("onPackageLoaded", param.getPackageName(), param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        install("onPackageReady", param.getPackageName(), param.getClassLoader());
        if (CAMERA_PACKAGE.equals(param.getPackageName())) {
            scheduleNotice();
        }
    }

    private void install(String stage, String pkg, ClassLoader loader) {
        if (!CAMERA_PACKAGE.equals(pkg) || loader == null) {
            return;
        }
        try {
            moduleInfo = getModuleApplicationInfo();
            moduleApk = moduleInfo.sourceDir;
        } catch (Throwable t) {
            dbg(Log.WARN, TAG, "cannot find the module APK: " + t);
        }
        if (loadLogged.compareAndSet(false, true)) {
            dbg(Log.INFO, TAG, "loaded in " + pkg + " (libxposed API " + getApiVersion() + ")");
        }
        if (versionLogged.compareAndSet(false, true)) {
            Application app = currentApplication();
            if (app != null) {
                try {
                    String cameraVersion = app.getPackageManager()
                            .getPackageInfo(CAMERA_PACKAGE, 0).versionName;
                    dbg(Log.INFO, TAG, "Camera " + cameraVersion + "; remote preferences: "
                            + (remotePrefs() != null ? "available" : "unavailable"));
                } catch (Throwable t) {
                    dbg(Log.WARN, TAG, "cannot read the Camera version: " + t);
                }
            } else {
                versionLogged.set(false);   // try again on onPackageReady
            }
        }
        installPropertyHook();
        installTabHook();
        installModeListHook(loader);
        installNameStyleHook(loader);
        installStylizationHook();
        installBeautyConfigHook(loader);
        installBlurRadiusHook(loader);
        // Get context for preference checks
        android.content.Context context = null;
        try {
            context = currentApplication();
        } catch (Throwable ignored) {}
        installLegendHook(loader, context);
        installStyleHook(loader);
        installFilmFiltersHook(loader, context);
        if (!problems.isEmpty() && stage.equals("onPackageLoaded")) {
            // onPackageReady tries once more; report only what is still wrong after it
            problems.clear();
        }
    }

    // ---- 1. Palette and Parameters ----------------------------------------------------------

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
            dbg(Log.INFO, TAG, "hooked SystemProperties (" + STYLE_VERSION_KEY + " >= "
                    + STYLE_VERSION + ")");
        } catch (Throwable t) {
            dbg(Log.WARN, TAG, "cannot hook SystemProperties: " + t);
        }
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
                            dbg(Log.WARN, TAG, "cannot widen the filter edit tabs: " + t);
                        }
                    }
                }
                return result;
            });
            tabsHooked = true;
            dbg(Log.INFO, TAG, "hooked LayoutInflater.inflate (filter edit tab width)");
        } catch (Throwable t) {
            dbg(Log.WARN, TAG, "cannot hook LayoutInflater.inflate: " + t);
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
        dbg(Log.INFO, TAG, "filter edit tabs widened to " + Math.round(tab * 2 / density) + " dp");
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
            dbg(Log.INFO, TAG, "hooked " + style.getName() + ".h (preset names in the Leica UI)");
        } catch (Throwable t) {
            dbg(Log.WARN, TAG, "cannot hook the Leica UI name style: " + t);
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
                Log.d(TAG, "ORDERHOOK: result=" + result + ", isIntArray=" + (result != null && result.getClass().getName().contains("int[]")) + ", essentialStdEnabled=" + essentialStdEnabled((android.content.Context) chain.getThisObject()));
                if (!(result instanceof int[] modes) || !essentialStdEnabled((android.content.Context) chain.getThisObject())) {
                    Log.d(TAG, "ORDERHOOK: SKIPPING because result=" + result + " essentialStdEnabled=false");
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
                    dbg(Log.INFO, TAG, "added the Leica Essential mode to the mode order at "
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
                            && mode == ESSENTIAL_MODE && essentialStdEnabled((android.content.Context) chain.getThisObject())) {
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
                    if (Boolean.FALSE.equals(result) && essentialStdEnabled((android.content.Context) chain.getThisObject())) {
                        if (supportLogged.compareAndSet(false, true)) {
                            dbg(Log.INFO, TAG, "LegendaryEnter.support() was false, answering true");
                        }
                        return true;
                    }
                    return result;
                });
            } catch (Throwable t) {
                dbg(Log.WARN, TAG, "cannot hook LegendaryEnter.support: " + t);
            }
            // For the experiment: report what the camera's final mode list holds.
            // Also ensure Essential mode (256) is in the strip if the property is enabled.
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
                    if (essentialStdEnabled()) {
                        try {
                            Object self = chain.getThisObject();
                            @SuppressWarnings("unchecked")
                            List<Object> stripList = (List<Object>) strip.get(self);
                            @SuppressWarnings("unchecked")
                            List<Object> moreList = (List<Object>) more.get(self);
                            // Ensure Essential mode (256) is in strip if property is on
                            boolean hasEssential = false;
                            for (Object entry : stripList) {
                                Object keyVal = key.get(entry);
                                if (keyVal != null && ESSENTIAL_MODE == Integer.parseInt(keyVal.toString())) {
                                    hasEssential = true;
                                    break;
                                }
                            }
                            if (!hasEssential) {
                                // Find the MORE_MODE entry in moreList and move it to strip, or create new
                                Object essentialEntry = null;
                                for (Object entry : moreList) {
                                    Object keyVal = key.get(entry);
                                    if (keyVal != null && ESSENTIAL_MODE == Integer.parseInt(keyVal.toString())) {
                                        essentialEntry = entry;
                                        break;
                                    }
                                }
                                if (essentialEntry != null) {
                                    moreList.remove(essentialEntry);
                                    stripList.add(essentialEntry);
                                    dbg(Log.INFO, TAG, "moved Essential mode (256) from more to strip");
                                }
                            }
                            if (modeListReported.compareAndSet(false, true)) {
                                dbg(Log.INFO, TAG, "mode list: all " + keys((List<?>) result, key)
                                        + ", strip " + keys(stripList, key)
                                        + ", more " + keys(moreList, key));
                            }
                        } catch (Throwable t) {
                            dbg(Log.WARN, TAG, "cannot adjust mode list: " + t);
                        }
                    }
                    return result;
                });
            } catch (Throwable t) {
                dbg(Log.WARN, TAG, "cannot hook the mode list builder: " + t);
            }
            modeListHooked = true;
            dbg(Log.INFO, TAG, "hooked " + list.getName() + "." + order.getName()
                    + (visible != null ? ", " + visible.getName() : "")
                    + " (mode order; " + ESSENTIAL_STD_KEY + ")");
        } catch (Throwable t) {
            dbg(Log.WARN, TAG, "cannot hook the mode order: " + t);
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
            dbg(Log.INFO, TAG, "hooked CameraCharacteristics.get (" + STYLIZATION_KEY + " bit 3)");
        } catch (Throwable t) {
            dbg(Log.WARN, TAG, "cannot hook CameraCharacteristics.get: " + t);
        }
    }

    // ---- 2b. Beauty config fallback for missing portrait scenes (HyperOS 3 / 14 Ultra) ----------
    
    private void installBeautyConfigHook(ClassLoader loader) {
        if (beautyConfigHooked) {
            return;
        }
        try {
            Class<?> beautyClass = Class.forName("com.android.camera.module.miui.beauty.MCAM_HalBeautyJsonData", false, loader);
            // Hook the method that throws "sceneKey don't exist" error
            Method parseJson = beautyClass.getDeclaredMethod("parseJson", String.class);
            parseJson.setAccessible(true);
            
            hook(parseJson).intercept(chain -> {
                String sceneKey = (String) chain.getArg(0);
                try {
                    return chain.proceed();
                } catch (Throwable t) {
                    // Provide fallback configs for missing scenes on HyperOS 3
                    if (t.getMessage() != null && t.getMessage().contains("don't exist")) {
                        dbg(Log.INFO, TAG, "Providing fallback beauty config for missing scene: " + sceneKey);
                        return getFallbackBeautyConfig(sceneKey);
                    }
                    throw t;
                }
            });
            beautyConfigHooked = true;
            dbg(Log.INFO, TAG, "hooked MCAM_HalBeautyJsonData.parseJson (beauty config fallback)");
        } catch (Throwable t) {
            dbg(Log.WARN, TAG, "cannot hook MCAM_HalBeautyJsonData: " + t);
        }
    }
    
    private volatile boolean beautyConfigHooked;
    
    // Default beauty configs for scenes missing on HyperOS 3 (based on standard portrait configs)
    private String getFallbackBeautyConfig(String sceneKey) {
        // Return minimal valid JSON that won't crash - based on RearPortrait config
        return "{\"Map\":{},\"INRegion\":{},\"GLRegion\":{}}";
    }

    // ---- 2c. Cap excessive blur radius for portrait preview (HyperOS 3 / 14 Ultra) ------------
    
    private void installBlurRadiusHook(ClassLoader loader) {
        if (blurRadiusHooked) {
            return;
        }
        try {
            // Hook BlurBackgroundView.makeBackgroundImage to cap blurRadius
            Class<?> blurClass = Class.forName("com.android.camera.ui.BlurBackgroundView", false, loader);
            Method makeBg = blurClass.getDeclaredMethod("makeBackgroundImage", int.class, int.class, float.class);
            makeBg.setAccessible(true);
            
            hook(makeBg).intercept(chain -> {
                // Cap blurRadius at 150 (was 241 causing slow preview)
                int blurRadius = (Integer) chain.getArg(0);
                if (blurRadius > 150) {
                    List<Object> args = chain.getArgs();
                    Object[] modified = args.toArray(new Object[0]);
                    modified[0] = 150;
                    return chain.proceed(modified);
                }
                return chain.proceed();
            });
            blurRadiusHooked = true;
            dbg(Log.INFO, TAG, "hooked BlurBackgroundView.makeBackgroundImage (blur radius cap)");
        } catch (Throwable t) {
            dbg(Log.WARN, TAG, "cannot hook BlurBackgroundView: " + t);
        }
    }
    
    private volatile boolean blurRadiusHooked;

    // ---- 3. Leica Essential CMOS (M10R) ---------------------------------------------------------

    // Read on every build of the list, so setprop takes effect the next time Camera starts.
    private static List<String> keys(List<?> items, Field key) throws IllegalAccessException {
        List<String> out = new java.util.ArrayList<>();
        for (Object item : items) {
            out.add(String.valueOf(key.get(item)));
        }
        return out;
    }

    // Settings live in the manager's "remote preferences" store, written by SettingsActivity.
    // The camera process cannot read the module app's private SharedPreferences directly.
    private static final String PREFS_GROUP = "hypercameramod";
    private static volatile android.content.SharedPreferences remotePrefs;

    private android.content.SharedPreferences remotePrefs() {
        android.content.SharedPreferences p = remotePrefs;
        if (p != null) {
            return p;
        }
        try {
            p = getRemotePreferences(PREFS_GROUP);
            remotePrefs = p;
            return p;
        } catch (Throwable t) {
            dbg(Log.WARN, TAG, "remote preferences are not available: " + t);
            return null;
        }
    }

    // On unless camera.feature.essential_std is 0, and the user hasn't disabled it in settings.
    private boolean essentialStdEnabled() {
        return essentialStdEnabled(null);
    }

    private boolean essentialStdEnabled(android.content.Context context) {
        android.content.SharedPreferences p = remotePrefs();
        if (p != null && p.contains("essential_mode_enabled")) {
            boolean v = p.getBoolean("essential_mode_enabled", true);
            Log.d(TAG, "ESSENTIAL: remotePrefs contains key, value=" + v);
            return v;
        }
        // Fallback to system property
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            String v = (String) props.getMethod("get", String.class, String.class)
                    .invoke(null, ESSENTIAL_STD_KEY, "1");
            boolean b = !"0".equals(v);
            Log.d(TAG, "ESSENTIAL: systemProperty=" + ESSENTIAL_STD_KEY + " value=" + v + " -> " + b);
            return b;
        } catch (Throwable t) {
            Log.d(TAG, "ESSENTIAL: systemProperty failed: " + t);
            return true;
        }
    }

    private static boolean propertyOn(String key) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return "1".equals(props.getMethod("get", String.class, String.class)
                    .invoke(null, key, "0"));
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean leicaUIEnabled(android.content.Context context) {
        android.content.SharedPreferences p = remotePrefs();
        if (p != null && p.contains("leica_ui_enabled")) {
            return p.getBoolean("leica_ui_enabled", true);
        }
        return true;
    }

    private boolean leicaUIEnabled() {
        return leicaUIEnabled(null);
    }

    private boolean m10rEnabled(android.content.Context context) {
        android.content.SharedPreferences p = remotePrefs();
        if (p != null && p.contains("m10r_enabled")) {
            return p.getBoolean("m10r_enabled", false);
        }
        // Fallback to system property
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return "1".equals(props.getMethod("get", String.class, String.class)
                    .invoke(null, "camera.feature.m10r", "0"));
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean m10rEnabled() {
        return m10rEnabled(null);
    }

    private boolean extraFiltersEnabled(android.content.Context context) {
        android.content.SharedPreferences p = remotePrefs();
        if (p != null && p.contains("extra_filters_enabled")) {
            return p.getBoolean("extra_filters_enabled", true);
        }
        return true;
    }

    private boolean extraFiltersEnabled() {
        return extraFiltersEnabled(null);
    }

    private void installLegendHook(ClassLoader loader, android.content.Context context) {
        if (legendHooked) {
            return;
        }
        if (!leicaUIEnabled(context)) {
            dbg(Log.INFO, TAG, "Leica UI disabled in settings, skipping Legend hook");
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

                final android.content.Context ctx = context;
                hook(build).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!m10rEnabled(ctx)) {
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
                            dbg(Log.INFO, TAG, "added the M10R (CMOS) style to the Leica Essential list");
                        }
                    } catch (Throwable t) {
                        dbg(Log.ERROR, TAG, "could not add the M10R style", t);
                    }
                    return result;
                });
                legendHooked = true;
                installAssetHooks();
                installTextHook();
                dbg(Log.INFO, TAG, "hooked " + profile.listClass() + "." + build.getName()
                        + " (Leica Essential, layout " + profile.name() + ")");
                return;
            } catch (Throwable t) {
                dbg(Log.WARN, TAG, "Leica Essential layout " + profile.name()
                        + " does not match: " + t);
            }
        }
        dbg(Log.WARN, TAG, "the Leica Essential style list is not one this module knows; "
                + "the CMOS style is not added");
        if (m10rEnabled(context) && !problems.contains(MSG_LEGEND)) {
            problems.add(MSG_LEGEND);   // only worth a notice when the CMOS entry was asked for
        }
    }

    // The method that fills a component's item list: not static, returns nothing and takes one
    // argument (the mode data). Named differently in each build.
    private static Method findBuilder(Class<?> cls) throws NoSuchMethodException {
        Method found = null;
        Method preferred = null;
        for (Method m : cls.getDeclaredMethods()) {
            Class<?> param = m.getParameterCount() == 1 ? m.getParameterTypes()[0] : null;
            // The argument is the mode data, but the build erases its type to Object
            if (m.getReturnType() == void.class && param != null
                    && !java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && !m.isSynthetic()
                    && (param == Object.class
                    || param.getName().startsWith("com.android.camera.data.data."))) {
                if (found != null) {
                    // Log candidates for debugging
                    Log.println(Log.DEBUG, TAG, "findBuilder: multiple candidates in " + cls.getName()
                            + ": " + found.getName() + "(" + found.getParameterTypes()[0].getSimpleName() + ")"
                            + ", " + m.getName() + "(" + param.getSimpleName() + ")");
                    // Prefer the one with a more specific parameter type (not Object)
                    if (param != Object.class && found.getParameterTypes()[0] == Object.class) {
                        preferred = m;
                    }
                }
                found = m;
            }
        }
        if (found == null) {
            throw new NoSuchMethodException("no list builder in " + cls.getName());
        }
        if (preferred != null) {
            Log.println(Log.DEBUG, TAG, "findBuilder: choosing " + preferred.getName() + " over " + found.getName() + " in " + cls.getName());
            return preferred;
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
            dbg(Log.WARN, TAG, "cannot hook Resources.getText: " + t);
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
                                dbg(Log.INFO, TAG, "gave the Original colour style its own Leica UI badge");
                            }
                        }
                    } catch (Throwable t) {
                        dbg(Log.ERROR, TAG, "could not fix the Original colour style badge", t);
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
                        dbg(Log.ERROR, TAG, "could not fix the chosen style badge", t);
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
                        dbg(Log.ERROR, TAG, "could not fix the style badge", t);
                    }
                    return result;
                });
                styleHooked = true;
                installAssetHooks();
                dbg(Log.INFO, TAG, "hooked " + profile.listClass() + ".getItems (colour styles, "
                        + "layout " + profile.name() + ")");
                return;
            } catch (Throwable t) {
                dbg(Log.WARN, TAG, "colour style layout " + profile.name()
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
            dbg(Log.WARN, TAG, "cannot hook the image loading for the M10R images: " + t);
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
                    dbg(Log.WARN, TAG, "could not show the notice: " + t);
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

    // Helper: optional class lookup
    private static Class<?> optionalClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    // Helper: resource ID lookup
    private static int resourceId(Class<?> cls, String name, String type) {
        if (cls == null) {
            try {
                Application app = currentApplication();
                if (app != null) {
                    Object result = app.getResources().getClass()
                            .getMethod("getIdentifier", String.class, String.class, String.class)
                            .invoke(app.getResources(), name, type, "com.android.camera");
                    return result instanceof Integer ? (Integer) result : 0;
                }
            } catch (Throwable t) {
                return 0;
            }
            return 0;
        }
        try {
            return cls.getField(name).getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- 8. Film Filters (R600, P100F, F-50, KC64, KP160) -----------------------------------------

    private void installFilmFiltersHook(ClassLoader loader, android.content.Context context) {
        if (filmFiltersHooked) {
            return;
        }
        if (!extraFiltersEnabled(context)) {
            dbg(Log.INFO, TAG, "Extra film filters disabled in settings, skipping film filters hook");
            return;
        }
        // Delegate to the filters module's logic
        try {
            Class<?> filtersModule = Class.forName("io.martin.hypercameramod.filters.ModuleMain", false, loader);
            // The filters module is a separate XposedModule that will be loaded via java_init.list
            // We just need to ensure it's enabled. The filters module reads the same shared prefs.
            filmFiltersHooked = true;
            dbg(Log.INFO, TAG, "Film filters module will handle filter injection (extra_filters_enabled=true)");
        } catch (Throwable t) {
            dbg(Log.WARN, TAG, "Cannot access filters module: " + t);
        }
    }
}
