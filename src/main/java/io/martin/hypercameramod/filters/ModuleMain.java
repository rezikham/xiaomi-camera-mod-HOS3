package io.martin.hypercameramod.filters;

import android.app.Application;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

public class ModuleMain extends XposedModule {
    private static final String TAG = "HyperFilmFilters";
    private static final String CAMERA_PACKAGE = "com.android.camera";
    private static final int PHOTO_CATEGORY = 10;
    // The visible filter strip on Aurora asks for category 5; it must get the films too.
    private static final int STRIP_CATEGORY = 5;

    // Where the camera keeps its filter code. The camera is obfuscated, so the names change from
    // build to build; a profile is one known layout. The module tries each profile in turn and
    // uses the first whose classes, methods and fields all exist. When none fits, the module
    // leaves the camera alone and tells the user.
    private record Profile(
            String name,
            // Builds the photo filter list (category 10) as a list of items.
            String listClass, String listMethod,
            // The filter item and its fields: filter ordinal, cloud id, display-name string,
            // image, name icon, sample images, sample labels, description. The constructor
            // stores its 3rd argument (the name) in titleField and its 4th (the image) in
            // iconField.
            String itemClass, String ordinalField, String cloudField, String titleField,
            String iconField, String nameIconField, String samplesField, String labelsField,
            String descField,
            // The camera's resource id classes (strings, images). Optional: when missing the
            // ids are looked up by name instead.
            String stringIds, String imageIds,
            // The filter panel. Its method says whether to merge the list with the cloud list,
            // which drops any local filter the cloud list does not name.
            String panelClass, String panelMethod,
            // Leica UI: builds the "Looks" list (back camera) from a fixed table.
            String looksClass, String looksMethod,
            // The filter strip's workspace: after the cloud data arrives it adds every filter the
            // strip does not have yet at position 1, one after the other. The strip's items keep
            // their filter's data object in itemDataField (the same objects as in the camera's
            // filter component, whose order is the order the camera lists its filters in);
            // sortIndexMethod gives an item its place in the strip, and initMethod sets the
            // workspace up when the camera opens. The component is found through the camera's data
            // registry.
            String workspaceClass, String itemDataField, String sortIndexMethod,
            String initMethod, String registryClass, String registryMethod, String lookupMethod,
            String componentClass,
            // The camera's cloud filter data, which the reload method reads along with the
            // component; the module passes it so that reloading does not drop cloud filters.
            String cloudClass, String cloudMethod, String effectClass, String categoryMethod) {
    }

    private static final Profile[] PROFILES = {
            // Camera 6.7.000280.0 (HyperOS 4.0.5.0).
            new Profile("6.7", "Ii.i0", "d",
                    "j3.b", "b", "l", "c", "e", "d", "g", "h", "i",
                    "Di.p", "Di.n",
                    "com.android.camera.fragment.m0", "ut",
                    "E9.h", "c",
                    "com.android.camera.features.mode.capture.e", "L", "Y", "G",
                    "h2.a", "a", "x", "s2.t", "Ei.q", "b", "com.xiaomi.camera.effect.EffectController", "q"),
            // Camera 6.7.000500.0: the filter list, item and registry are as above; the filter
            // panel, the Looks table and the strip's workspace and item moved to other classes.
            new Profile("6.7.000500", "Ii.i0", "d",
                    "j3.b", "b", "l", "c", "e", "d", "g", "h", "i",
                    "Di.p", "Di.n",
                    "com.android.camera.fragment.j0", "Bt",
                    "E9.i", "c",
                    "com.android.camera.features.mode.capture.d", "M", "Y", "G",
                    "h2.a", "a", "x", "s2.t", "Ei.q", "b", "com.xiaomi.camera.effect.EffectController", "q"),
            // Camera 6.8.002230.0 (Xiaomi 18 Pro Max, HyperOS 4). The list/item/workspace
            // names are verified; the panel, Looks table and registry lookup are the best
            // static candidates (the strip-order hook may log a failure on this build).
            // imageIds is null: the drawables are resolved by name.
            new Profile("6.8.002230", "xi.h0", "d",
                    "i3.b", "b", "l", "c", "e", "d", "g", "h", "i",
                    "com.xiaomi.camera.cloudfilter.R$string", null,
                    "com.android.camera.fragment.j0", "kt",
                    "B9.j", "c",
                    "com.android.camera.features.mode.capture.d", "K", "Y", "G",
                    "g2.a", "a", "x", "r2.t", "ti.f", "b", "com.xiaomi.camera.effect.EffectController", "q"),
    };

    // ic_vector_lc_looks_filter_name_icon_standard in 6.7.000280.0 (no R classes at runtime).
    private static final int LOOKS_NAME_ICON = 0x7f0808f5;

    // ordinal = position of the filter in the camera's filter enum, order = sort slot,
    // cloudId = the id the camera's own item builder / cloud catalogue gives the filter.
    //
    // codeStringId is the pre-6.7 film-code string resource (600F, KC64, ...). stockStringId is
    // the key the running camera uses for the same filter; on 6.7/6.8 many films were renamed
    // (F-50 -> reversal, KC64 -> hanjiao, V250 -> besson, H400 -> freehand_brushwork,
    // KP160 -> monsoon, FC400 -> jingdu, C-50D -> old_roadway, KG200 -> slack). Both keys are
    // used to recognise an entry that is already in the camera's list, so it can be renamed
    // instead of added twice.
    private record Film(int ordinal, String label, String codeStringId, String stockStringId,
                        String imageId, int order, int cloudId) {
    }

    // Every film stock the 5.3 back-camera strip had, in the 6.7/6.8 strip order (12..23).
    // The labels are the film codes, and the module always draws these labels, even on a
    // camera version whose own UI uses the later artistic names.
    private static final Film[] ALL_FILMS = {
            new Film(79, "600F", "color_effect_entry_600_f", "color_effect_entry_600_f",
                    "color_effect_image_600_f", 12, 11),
            new Film(80, "BF70", "color_effect_entry_bf_70", "color_effect_entry_bf_70",
                    "color_effect_image_bf_70", 13, 12),
            new Film(81, "R600", "color_effect_entry_r_600", "color_effect_entry_r_600",
                    "color_effect_image_r_600", 14, 56),
            new Film(82, "P100F", "color_effect_entry_p_100f", "color_effect_entry_p_100f",
                    "color_effect_image_p_100f", 15, 57),
            new Film(83, "F-50", "color_effect_entry_f_50", "color_effect_entry_reversal",
                    "color_effect_image_f_50", 16, 46),
            new Film(84, "KC64", "color_effect_entry_kc_64", "color_effect_entry_hanjiao",
                    "color_effect_image_c_64", 17, 38),
            new Film(85, "V250", "color_effect_entry_v_250", "color_effect_entry_besson",
                    "color_effect_image_v_5207", 18, 15),
            new Film(86, "H400", "color_effect_entry_h_400", "color_effect_entry_freehand_brushwork",
                    "color_effect_image_h_400", 19, 16),
            new Film(87, "KP160", "color_effect_entry_kp_160", "color_effect_entry_monsoon",
                    "color_effect_image_p_160nc", 20, 58),
            new Film(88, "FC400", "color_effect_entry_fc_400", "color_effect_entry_jingdu",
                    "color_effect_image_p_400h", 21, 59),
            new Film(89, "C-50D", "color_effect_entry_c_50d", "color_effect_entry_old_roadway",
                    "color_effect_image_c_50d", 22, 70),
            new Film(90, "KG200", "color_effect_entry_kg_200", "color_effect_entry_slack",
                    "color_effect_image_g_200", 23, 69),
    };

    // The photo strip and the Leica Looks table both get the full set.
    private static final Film[] FILMS = ALL_FILMS;
    private static final Film[] LOOKS_FILMS = ALL_FILMS;

    // Camera 6.4 only has the four filters the old module exposed. Its item class and filter
    // enum predate the 79..90 range, so the six newer entries must not be passed to it.
    private static final Film[] LEGACY_FILMS = {
            new Film(81, "R600", "color_effect_entry_r_600", "color_effect_entry_r_600",
                    "color_effect_image_r_600", 9, 56),
            new Film(82, "P100F", "color_effect_entry_p_100f", "color_effect_entry_p_100f",
                    "color_effect_image_p_100f", 10, 57),
            new Film(83, "F-50", "color_effect_entry_f_50", "color_effect_entry_f_50",
                    "color_effect_image_f_50", 11, 46),
            new Film(84, "KC64", "color_effect_entry_kc_64", "color_effect_entry_kc_64",
                    "color_effect_image_c_64", 12, 38),
    };

    // The Looks list draws a small image of each look's name under its thumbnail. There is no
    // such image for the film filters, so the name icon is a made-up resource id (this base plus
    // the filter ordinal) that ImageView.setImageResource turns into the film's name as text.
    private static final int STANDARD_CATEGORY = 2;
    // The camera identifies a filter in its strip by a key made from this field; the films were
    // all left at 0 and so shared one key. Give each its own, clear of the camera's (8 bit) ids.
    private static final int FILM_KEY_BASE = 0x10000;
    // Made-up resource ids for the film names. The camera asks Resources for the item's string
    // id, so the hook returns the film code ("600F", "KC64", ...) no matter what name the
    // running camera version would otherwise use.
    private static final int FILM_NAME_BASE = 0x7d000000;
    private static final int NAME_ICON_BASE = 0x7e000000;
    // The main module labels its M3/M9 strip and Looks entries with ids from this range
    // (0x7e030001 = M3, 0x7e030002 = M9). M3/M9 borrow the hidden V250/H400 enum slots, so
    // those two entries must never be renamed or duplicated by the film pass.
    private static final int RESERVED_LOOK_NAME_BASE = 0x7e030000;
    // Same coordinate system as the camera's own name icons (36 dp square), so the label is drawn
    // at the same size wherever the icon is used. Letter height and stroke are those of the
    // native "LEICA" line as measured on screen (26 px tall, 4 px stroke, letters 3-4 px apart,
    // 5 px between the two lines, at about 2.65 px per dp).
    private static final float LABEL_SIZE_DP = 36f;
    private static final float LABEL_CAP_DP = 9.45f;
    private static final float LABEL_GAP_DP = 1.05f;    // gap between the letters of the top line, as native
    private static final float LABEL_NAME_GAP_DP = 1.25f; // same for the film code: its letters sat tighter than native
    private static final float LABEL_LINE_GAP_DP = 2.25f; // gap between the two lines (31 px pitch at 2.65 px/dp), as native
    private static final float LABEL_BLOCK_SHIFT_DP = -0.4f; // moves both lines up, as native (outer edges incl. the ring)
    private static final float LABEL_GLYPH_HEIGHT_DP = 9.1f; // letter height; the widths stay those of LABEL_CAP_DP
    // The native labels have a light gray outline around the letters (about 1.75 px at 2.65 px per
    // dp): white letters keep a gray rim on a bright background, and gray (unselected) letters get
    // that much heavier. The outline is not tinted, only the letters are.
    private static final float LABEL_RING_DP = 0.66f;
    // The outline is translucent black (22%), not a fixed gray: over a near-white panel it comes
    // out as the light gray of the native labels (#C1C1C1), and over a dark panel it is hardly
    // visible, as on the native labels.
    private static final int LABEL_RING_ALPHA = 56;
    private static final String LABEL_TOP_LINE = "FLM";
    private static final float HYPHEN_LENGTH = 0.5f;    // hyphen length, as a share of letter height
    private static final Map<Character, Bitmap> GLYPH_CACHE = new HashMap<>();

    private final Set<String> loggedCategories = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean hooked;
    private volatile boolean nameIconHooked;
    private volatile boolean nameHooksInstalled;
    private volatile boolean nameStringHooked;

    // The main module's M3/M9 now use enum slots 91/92 (N_NEUTRAL/N_SOFT), which are not in the
    // photo filter list. The film pass must keep those two slots free for M3/M9, so every other
    // film slot (including V250=85 and H400=86) can be exposed.
    private static final int LOOK_M9_SLOT = 92;
    private static final int LOOK_M3_SLOT = 91;
    // What did not work, shown to the user once per camera version (see scheduleNotice).
    private final List<String> problems = new CopyOnWriteArrayList<>();
    private final AtomicBoolean noticeScheduled = new AtomicBoolean();

    // What a profile resolved to on the running camera.
    private record Resolved(Profile profile, Constructor<?> create, Field ordinal, Field cloud,
                            Field name, Class<?> strings, Class<?> images) {
    }

    // The entries a film pass touched: all film entries (existing ones that were renamed and new
    // ones that were added), plus the subset that was newly created.
    private record FilmResult(List<Object> touched, List<Object> created) {
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        install("onPackageLoaded", param.getPackageName(), param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        install("onPackageReady", param.getPackageName(), param.getClassLoader());
        if (CAMERA_PACKAGE.equals(param.getPackageName())) {
            debugEnvironment();
            scheduleNotice();
        }
    }

    private void install(String stage, String pkg, ClassLoader loader) {
        if (!CAMERA_PACKAGE.equals(pkg) || hooked || loader == null) {
            return;
        }
        // Check if extra film filters are enabled in the main module's settings
        if (!extraFiltersEnabled()) {
            log(Log.INFO, TAG, "Extra film filters disabled in settings, skipping filter injection");
            return;
        }
        problems.clear();
        for (Profile profile : PROFILES) {
            Resolved resolved;
            try {
                resolved = resolve(profile, loader);
            } catch (Throwable t) {
                log(Log.WARN, TAG, stage + ": camera layout " + profile.name()
                        + " does not match: " + t);
                trace(stage + ": layout " + profile.name() + " does not match: " + t);
                continue;
            }
            // Install the name hooks before the list hook: the items added below carry made-up
            // string/icon ids, and the camera may ask for them as soon as its list is built.
            installNameIconHook();
            try {
                hook(listMethod(profile, loader)).intercept(chain -> {
                    Object result = chain.proceed();
                    addFilms(result, FILMS, resolved);
                    return result;
                });
            } catch (Throwable t) {
                log(Log.WARN, TAG, stage + ": cannot hook " + profile.listClass() + "."
                        + profile.listMethod() + ": " + t);
                continue;
            }
            hooked = true;
            profileName = profile.name();
            trace(stage + ": layout " + profile.name() + " matched");
            log(Log.INFO, TAG, stage + ": camera layout " + profile.name() + ", hooked "
                    + profile.listClass() + "." + profile.listMethod());
            installPanelHook(profile, loader);
            installLooksHook(profile, loader, resolved);
            installCategoryHook(profile, loader, resolved);
            installComponentHook(loader, resolved);
            installOrderHook(profile, loader, resolved);
            return;
        }
        if (installLegacy(stage, loader)) {
            return;
        }
        log(Log.WARN, TAG, stage + ": the camera's filter code is not one this module knows "
                + "(a different camera version?); the camera is left unchanged");
        problems.add(MSG_UNSUPPORTED);
    }

    // Camera 6.4 (HyperOS 3): the filter lists are kept in EffectController, one list per
    // category, and handed out by n(int). The standard UI's list is category 2; it leaves out
    // the films that the camera's own code has the LUTs, names and images for.
    // The panel of the standard UI asks for category 10 (on the 14 Ultra).
    private static final int LEGACY_CATEGORY = 2;
    private static final int LEGACY_PANEL_CATEGORY = 10;
    private static final int[] LEGACY_ORDER = {9, 10, 11, 12};

    private boolean installLegacy(String stage, ClassLoader loader) {
        try {
            Class<?> effect = Class.forName("com.android.camera.effect.EffectController", false,
                    loader);
            Method lists = effect.getDeclaredMethod("n", int.class);
            Class<?> item = Class.forName("P0.d", false, loader);
            Constructor<?> create = item.getDeclaredConstructor(String.class, int.class, int.class,
                    int.class, int.class, int.class, int.class);
            create.setAccessible(true);
            Field category = field(item, "a");
            Field ordinal = field(item, "b");
            // The camera's string-id class is R8-renamed per build (Z9.f on the 14 Ultra's
            // 6.4.000430.0, something else on the 14 Pro's build of the same version). The
            // cloudfilter R class is not obfuscated and exists in every build seen so far,
            // with the same color_effect_entry_* fields.
            Class<?> strings = null;
            for (String name : new String[]{"Z9.f", "com.xiaomi.camera.cloudfilter.R$string"}) {
                try {
                    strings = Class.forName(name, false, loader);
                    break;
                } catch (ClassNotFoundException ignored) {
                    // try the next candidate
                }
            }
            if (strings == null) {
                throw new ClassNotFoundException("Z9.f / com.xiaomi.camera.cloudfilter.R$string");
            }
            trace(stage + ": string ids from " + strings.getName());
            final Class<?> stringsClass = strings;
            // The camera has no class for its image ids (they are inlined), so the images are
            // looked up by name when the lists are built.
            for (Film film : LEGACY_FILMS) {
                stringsClass.getField(film.codeStringId());
            }
            hook(lists).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object asked = chain.getArg(0);
                    if (loggedCategories.add(String.valueOf(asked))) {
                        log(Log.INFO, TAG, "filter list asked for category " + asked + ": "
                                + (result instanceof List<?> l ? l.size() : "none") + " items");
                    }
                    if (asked instanceof Integer c && (c == LEGACY_CATEGORY
                            || c == LEGACY_PANEL_CATEGORY) && result instanceof List<?> list) {
                        addLegacyFilms(list, c, create, category, ordinal, stringsClass);
                        if (!list.isEmpty()) {
                            debugKeys("filter list category " + c + " (ordinals)", list, ordinal);
                        }
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "could not add film filters", t);
                }
                return result;
            });
            hooked = true;
            profileName = "6.4";
            trace(stage + ": layout 6.4 matched");
            log(Log.INFO, TAG, stage + ": camera layout 6.4, hooked " + effect.getName() + "."
                    + lists.getName());
            installLegacyComponentHook(loader);
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, stage + ": camera layout 6.4 does not match: " + t);
            trace(stage + ": layout 6.4 does not match: " + t);
            return false;
        }
    }

    // As in 6.7, the camera builds its filter component from the list above, or, when it holds
    // cloud filter data for the mode, from that data merged with the list, which leaves the films
    // out. When the films are missing after that, build it again from the list alone.
    private void installLegacyComponentHook(ClassLoader loader) {
        try {
            // The filter component moved between builds of the same camera version: f0.M on the
            // 14 Ultra's 6.4.000430.0, f0.L on the 14 Pro's. Both expose the same methods, so
            // pick the first candidate that has them.
            Class<?> cls = null;
            Method build = null;
            Method init = null;
            Method items = null;
            for (String name : new String[]{"f0.M", "f0.L"}) {
                try {
                    Class<?> candidate = Class.forName(name, false, loader);
                    build = candidate.getDeclaredMethod("mapToCloudItems", int.class, Map.class);
                    init = candidate.getDeclaredMethod("initItems", int.class);
                    items = candidate.getMethod("getItems");
                    cls = candidate;
                    break;
                } catch (Throwable ignored) {
                    // try the next candidate
                }
            }
            if (cls == null) {
                throw new NoSuchMethodException("filter component (f0.M / f0.L)");
            }
            final Method componentBuild = build;
            final Method componentInit = init;
            final Method componentItems = items;
            trace("string ids used, filter component is " + cls.getName());
            Class<?> data = Class.forName("com.android.camera.data.data.d", false, loader);
            Field key = field(data, "p");
            hook(componentInit).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    List<?> built = (List<?>) componentItems.invoke(chain.getThisObject());
                    log(Log.INFO, TAG, "filter component set up for mode " + chain.getArg(0) + ": "
                            + built.size() + " items, " + countLegacyFilms(built, key) + " films");
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "could not check the filter component", t);
                }
                return result;
            });
            hook(componentBuild).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object mode = chain.getArg(0);
                    Map<?, ?> cloud = (Map<?, ?>) chain.getArg(1);
                    List<?> built = (List<?>) componentItems.invoke(chain.getThisObject());
                    boolean hasCloud = cloud != null && cloud.containsKey(mode);
                    int films = countLegacyFilms(built, key);
                    log(Log.INFO, TAG, "filter component for mode " + mode + ": " + built.size()
                            + " items, " + films + " films, cloud data " + hasCloud);
                    debugKeys("filter component for mode " + mode + " (as built)", built, key);
                    if (films == 0 && hasCloud && !merging.get()) {
                        // Keep what the camera built from the cloud data (it holds filters that
                        // exist only there) and add the films, taken from a build that uses the
                        // local list alone.
                        merging.set(true);
                        try {
                            Object self = chain.getThisObject();
                            List<Object> merged = new ArrayList<>(built);
                            Map<Object, Object> local = new java.util.concurrent.ConcurrentHashMap<>(cloud);
                            local.remove(mode);
                            componentBuild.invoke(self, mode, local);
                            int added = 0;
                            for (Object item : (List<?>) componentItems.invoke(self)) {
                                if (isLegacyFilm(item, key)) {
                                    merged.add(item);
                                    added++;
                                }
                            }
                            setItems(self, merged);
                            log(Log.INFO, TAG, "added " + added + " films to the cloud filter list: "
                                    + merged.size() + " items");
                            debugKeys("filter component for mode " + mode + " (merged)", merged, key);
                        } finally {
                            merging.set(false);
                        }
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "could not check the filter component", t);
                }
                return result;
            });
            log(Log.INFO, TAG, "hooked " + cls.getName() + " (filter component)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook the filter component: " + t);
        }
    }

    private volatile String profileName;
    private final ThreadLocal<Boolean> merging = ThreadLocal.withInitial(() -> false);

    // The component keeps its items in a field of the camera's data class (mItems).
    private static void setItems(Object component, List<?> items) throws Exception {
        for (Class<?> c = component.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("mItems");
                f.setAccessible(true);
                f.set(component, items);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException("mItems");
    }

    private static boolean isLegacyFilm(Object item, Field key) throws Exception {
        Object k = key.get(item);
        if (k instanceof String text && text.matches("\\d+")) {
            int ordinal = (int) (Long.parseLong(text) & 0xFFFF);
            for (Film film : LEGACY_FILMS) {
                if (film.ordinal() == ordinal) {
                    return true;
                }
            }
        }
        return false;
    }

    // Debug output (on by default, camera.feature.filters_debug set to 0 turns it off): what the
    // lists and the component hold, so a log shows which filters are there and in what order.
    private static boolean debugEnabled() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return !"0".equals(props.getMethod("get", String.class, String.class)
                    .invoke(null, "camera.feature.filters_debug", "1"));
        } catch (Throwable t) {
            return true;
        }
    }

    private final Set<String> debugged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void debugKeys(String label, List<?> items, Field key) {
        if (!debugEnabled() || items == null) {
            return;
        }
        try {
            StringBuilder keys = new StringBuilder();
            for (Object item : items) {
                keys.append(keys.length() == 0 ? "" : " ").append(key.get(item));
            }
            if (debugged.add(label + keys)) {
                log(Log.INFO, TAG, "debug: " + label + ": " + items.size() + " items, keys [" + keys + "]");
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "debug: cannot read " + label + ": " + t);
        }
    }

    private static final String MODULE_VERSION = "2.0.0";

    // Some frameworks keep the module's own log lines out of logcat (they go to the framework's
    // module log). The key lines are also printed straight to logcat, under the same tag.
    private static void trace(String message) {
        if (debugEnabled()) {
            Log.println(Log.INFO, TAG, "direct: " + message);
        }
    }

    private void debugEnvironment() {
        if (!debugEnabled()) {
            return;
        }
        Application app = currentApplication();
        if (app == null) {
            return;
        }
        try {
            String camera = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
            String line = "module " + MODULE_VERSION + ", camera " + camera + ", android "
                    + android.os.Build.VERSION.RELEASE + ", device " + android.os.Build.DEVICE
                    + ", profile " + (profileName == null ? "none" : profileName);
            log(Log.INFO, TAG, "debug: " + line);
            trace(line);
            // One toast per module version, camera version and profile, so that it can be seen
            // that the module is running even when no log is readable.
            String key = "active_" + MODULE_VERSION + "_" + camera + "_" + profileName;
            var prefs = app.getSharedPreferences(TAG, Application.MODE_PRIVATE);
            if (!prefs.getBoolean(key, false)) {
                prefs.edit().putBoolean(key, true).apply();
                new Handler(Looper.getMainLooper()).postDelayed(() -> Toast.makeText(app,
                        TAG + " " + MODULE_VERSION + ": "
                                + (profileName == null ? "camera layout not recognised"
                                : "camera layout " + profileName), Toast.LENGTH_LONG).show(), 4000);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "debug: cannot read the camera version: " + t);
        }
    }

    // Check if extra film filters are enabled in the main module's settings
    private boolean extraFiltersEnabled() {
        try {
            android.content.SharedPreferences prefs = getRemotePreferences("hypercameramod");
            if (prefs != null && prefs.contains("extra_filters_enabled")) {
                return prefs.getBoolean("extra_filters_enabled", true);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot read extra_filters_enabled preference: " + t);
        }
        return true; // Default to enabled
    }

    // M3/M9 as separate Looks/film-strip entries. While this is on, ordinals 85/86 are
    // reserved for M3/M9, so the film pass must not expose V250/H400 there.
    private boolean looksEntriesEnabled() {
        try {
            android.content.SharedPreferences prefs = getRemotePreferences("hypercameramod");
            if (prefs != null && prefs.contains("looks_entries_enabled")) {
                return prefs.getBoolean("looks_entries_enabled", true);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot read looks_entries_enabled preference: " + t);
        }
        return true; // Default to enabled
    }

    // A component filter's key is its category in the high 16 bits and its ordinal in the low 16
    private static int countLegacyFilms(List<?> built, Field key) throws Exception {
        int count = 0;
        for (Object item : built) {
            Object k = key.get(item);
            if (k instanceof String text && text.matches("\\d+")) {
                int ordinal = (int) (Long.parseLong(text) & 0xFFFF);
                for (Film film : LEGACY_FILMS) {
                    if (film.ordinal() == ordinal) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private void addLegacyFilms(List<?> raw, int asked, Constructor<?> create, Field category,
            Field ordinal, Class<?> strings) throws Exception {
        List<Object> list = (List<Object>) raw;
        Set<Integer> present = new HashSet<>();
        for (Object entry : list) {
            if (category.getInt(entry) == asked) {
                present.add(ordinal.getInt(entry));
            }
        }
        List<String> added = new ArrayList<>();
        for (int i = 0; i < LEGACY_FILMS.length; i++) {
            Film film = LEGACY_FILMS[i];
            if (present.contains(film.ordinal())) {
                continue;
            }
            int name = resourceId(strings, film.codeStringId(), "string");
            int image = resourceId(null, film.imageId(), "drawable");
            list.add(create.newInstance("NORMAL", asked, film.ordinal(), name, image,
                    asked == LEGACY_CATEGORY ? LEGACY_ORDER[i] : film.order(), film.cloudId()));
            added.add(film.label());
        }
        log(Log.INFO, TAG, "filter list " + asked + ": " + present.size() + " items, added "
                + added);
    }

    private static java.lang.reflect.Method listMethod(Profile profile, ClassLoader loader)
            throws ReflectiveOperationException {
        return Class.forName(profile.listClass(), false, loader)
                .getDeclaredMethod(profile.listMethod());
    }

    // Looks up everything a profile names. Throws if any of it is missing.
    private static Resolved resolve(Profile profile, ClassLoader loader)
            throws ReflectiveOperationException {
        listMethod(profile, loader);
        // Every class and method the other hooks use must be there too, so that a profile for
        // another camera version is not taken for this one.
        Class.forName(profile.panelClass(), false, loader).getDeclaredMethod(profile.panelMethod());
        Class.forName(profile.looksClass(), false, loader).getDeclaredMethod(profile.looksMethod());
        Class.forName(profile.workspaceClass(), false, loader)
                .getDeclaredMethod(profile.initMethod(), int.class);
        Class<?> item = Class.forName(profile.itemClass(), false, loader);
        Constructor<?> create = item.getDeclaredConstructor(int.class, int.class, int.class,
                int.class, int.class, int.class, String.class);
        create.setAccessible(true);
        Field ordinal = field(item, profile.ordinalField());
        Field cloud = field(item, profile.cloudField());
        Field name = field(item, profile.titleField());
        return new Resolved(profile, create, ordinal, cloud, name,
                optionalClass(profile.stringIds(), loader), optionalClass(profile.imageIds(), loader));
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Field field = cls.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Class<?> optionalClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    // The strip puts a filter it does not have yet at position 1, so the films, which the camera
    // does not list itself, would end up first and in reverse. Its own rule for the order is the
    // order of the camera's filter component (the films are last in it): official filters in that
    // order, then the user's own presets. After the strip has reloaded, the module puts it back
    // to that rule, and also once when the camera opens (which repairs a strip an earlier
    // version of the module left in a wrong order).
    // The strip of the standard UI is built from the camera's filter list for category 2, which
    // (unlike the list for category 10 above) never got the films. Add them to it as well, where
    // the camera hands the list out (not where it builds it, one combined list is made from both).
    private void installCategoryHook(Profile profile, ClassLoader loader, Resolved resolved) {
        try {
            Method categoryList = Class.forName(profile.effectClass(), false, loader)
                    .getDeclaredMethod(profile.categoryMethod(), int.class);
            hook(categoryList).intercept(chain -> {
                Object result = chain.proceed();
                Object category = chain.getArg(0);
                if (loggedCategories.add(String.valueOf(category))) {
                    log(Log.INFO, TAG, "filter list asked for category " + category + ": "
                            + (result instanceof List<?> l ? l.size() : "none") + " items");
                }
                if (category instanceof Integer c
                        && (c == STANDARD_CATEGORY || c == STRIP_CATEGORY
                        || c == PHOTO_CATEGORY)) {
                    // The list for category 10 can differ from the one the camera built (000500
                    // hands out a copy without the films), so check it here too; entries already
                    // in the list are renamed, missing ones are added.
                    FilmResult added = addFilms(result, FILMS, resolved, c);
                    if (!added.created().isEmpty()) {
                        log(Log.INFO, TAG, "category " + c + ": added "
                                + added.created().size()
                                + " films to the list the camera handed out ("
                                + ((List<?>) result).size() + " items)");
                    }
                }
                return result;
            });
            log(Log.INFO, TAG, "hooked " + profile.effectClass() + "." + profile.categoryMethod()
                    + " (standard filter list)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook the standard filter list: " + t);
        }
    }

    // The camera builds its filter component from its local filter list, or, when it holds cloud
    // filter data for the mode, from that data merged with the local list, which leaves the
    // films out. When the films are missing after that, build it again from the local list.
    private void installComponentHook(ClassLoader loader, Resolved resolved) {
        try {
            Class<?> cls = Class.forName("w2.P", false, loader);
            Method build = cls.getDeclaredMethod("c", int.class, Map.class);
            Method items = cls.getMethod("getItems");
            hook(build).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object mode = chain.getArg(0);
                    Map<?, ?> cloud = (Map<?, ?>) chain.getArg(1);
                    List<?> built = (List<?>) items.invoke(chain.getThisObject());
                    boolean hasCloud = cloud != null && cloud.containsKey(mode);
                    int films = countFilms(built, resolved);
                    log(Log.INFO, TAG, "filter component for " + mode + ": " + built.size()
                            + " items, " + films + " films, cloud data " + hasCloud);
                    if (!built.isEmpty()) {
                        try {
                            debugKeys("filter component for mode " + mode, built,
                                    field(built.get(0).getClass(), "n"));
                        } catch (Throwable ignored) {
                        }
                    }
                    // If the cloud merge dropped every film, rebuild from the local list. The
                    // local list now carries every film, and the rebuild reuses entries that
                    // are already there instead of adding duplicates. A partial cloud list is
                    // left alone; the list hooks add the missing films to the strip.
                    // The cloud-merged component does not carry the local film entries. Rebuilding
                    // from the local list puts them in the component, which is what the visible
                    // strip reads. M3/M9 now live on 91/92, so all 12 films are expected.
                    int expectedFilms = ALL_FILMS.length;
                    if (films < expectedFilms && hasCloud) {
                        Map<Object, Object> local = new java.util.concurrent.ConcurrentHashMap<>(cloud);
                        local.remove(mode);
                        build.invoke(chain.getThisObject(), mode, local);
                        built = (List<?>) items.invoke(chain.getThisObject());
                            log(Log.INFO, TAG, "rebuilt from the local list: " + built.size()
                                + " items, " + countFilms(built, resolved) + " films");
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "could not check the filter component", t);
                }
                return result;
            });
            log(Log.INFO, TAG, "hooked w2.P.c (filter component)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook the filter component: " + t);
        }
    }

    // A component filter is a film when its key is one given above or, for a strip saved by an
    // earlier version, when its name is a film's
    // How many films a strip holds
    private int stripFilms(Object listObject, Profile profile, Resolved resolved) throws Exception {
        if (!(listObject instanceof List<?> list) || list.isEmpty()) {
            return 0;
        }
        Field data = findField(list.get(0).getClass(), profile.itemDataField());
        Set<Integer> names = filmNameMap(ALL_FILMS, resolved).keySet();
        int count = 0;
        Field title = null;
        for (Object item : list) {
            Object filter = data.get(item);
            if (filter == null) {
                continue;
            }
            if (title == null) {
                title = field(filter.getClass(), "l");
            }
            if (isFilm(filter, title, names)) {
                count++;
            }
        }
        return count;
    }
    private static int filmDefaultOrder(Object filter, Field title, Resolved resolved) {
        try {
            Film film = matchFilm(filter, resolved, ALL_FILMS,
                    filmNameMap(ALL_FILMS, resolved));
            if (film != null) {
                return film.order();
            }
        } catch (Throwable ignored) {
        }
        return Integer.MAX_VALUE;
    }

    private static boolean isFilm(Object filter, Field title, Set<Integer> names) throws Exception {
        Object key = field(filter.getClass(), "r").get(filter);
        if (key instanceof String k && k.matches("\\d+")) {
            int value = Integer.parseInt(k);
            for (Film film : FILMS) {
                if (value == FILM_KEY_BASE + film.ordinal()) {
                    return true;
                }
            }
        }
        return names.contains(title.getInt(filter));
    }

    private int countFilms(List<?> built, Resolved resolved) throws Exception {
        Set<Integer> names = filmNameMap(ALL_FILMS, resolved).keySet();
        int count = 0;
        Field title = null;
        for (Object item : built) {
            if (title == null) {
                title = field(item.getClass(), "l");
            }
            if (isFilm(item, title, names)) {
                count++;
            }
        }
        return count;
    }

    private void installOrderHook(Profile profile, ClassLoader loader, Resolved resolved) {
        try {
            Class<?> workspace = Class.forName(profile.workspaceClass(), false, loader);
            Method reload = null;
            for (Method m : workspace.getDeclaredMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (m.getReturnType() == boolean.class && types.length == 3
                        && types[0] == int.class && types[1] == android.content.Context.class
                        && types[2] == java.util.concurrent.ConcurrentHashMap.class) {
                    reload = m;
                }
            }
            if (reload == null) {
                throw new NoSuchMethodException("no filter reload method in " + workspace);
            }
            final Method reloadMethod = reload;
            reloadMethod.setAccessible(true);
            Method init = workspace.getDeclaredMethod(profile.initMethod(), int.class);
            Method getList = workspace.getMethod("getList");
            hook(reload).intercept(chain -> {
                boolean fresh = false;
                try {
                    fresh = stripFilms(getList.invoke(chain.getThisObject()), profile, resolved) == 0;
                } catch (Throwable ignored) {
                }
                Object result = chain.proceed();
                try {
                    log(Log.INFO, TAG, "strip reload, mode " + chain.getArg(0) + ": " + result);
                    if (Boolean.TRUE.equals(result)) {
                        restoreOrder(getList.invoke(chain.getThisObject()), profile, loader, resolved,
                                fresh);
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "could not order the filter strip", t);
                }
                return result;
            });
            hook(init).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    boolean fresh = stripFilms(getList.invoke(chain.getThisObject()), profile,
                            resolved) == 0;
                    // A strip that was saved before the films existed (or lost them) never gets
                    // them until the camera next reloads its cloud data, so reload it now: the
                    // camera adds the filters its list has and the strip lacks, and the order is
                    // put right below.
                    log(Log.INFO, TAG, "strip set up, mode " + chain.getArg(0));
                    Application app = currentApplication();
                    Object cloud = Class.forName(profile.cloudClass(), false, loader)
                            .getMethod(profile.cloudMethod()).invoke(null);
                    if (app != null && cloud instanceof Map<?, ?> map) {
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        var copy = new java.util.concurrent.ConcurrentHashMap(map);
                        Object reloaded = reloadMethod.invoke(chain.getThisObject(), chain.getArg(0),
                                app, copy);
                        log(Log.INFO, TAG, "reloaded the filter strip at start: " + reloaded
                                + ", cloud entries " + copy.size());
                    } else {
                        log(Log.WARN, TAG, "no reload at start: app " + app + ", cloud " + cloud);
                    }
                    restoreOrder(getList.invoke(chain.getThisObject()), profile, loader, resolved,
                            fresh);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "could not order the filter strip", t);
                }
                return result;
            });
            log(Log.INFO, TAG, "hooked " + profile.workspaceClass() + "." + reload.getName() + ", "
                    + init.getName() + " (filter strip order)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook the filter strip order: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private void restoreOrder(Object listObject, Profile profile, ClassLoader loader,
            Resolved resolved, boolean fresh)
            throws Exception {
        if (!(listObject instanceof List<?>) || ((List<?>) listObject).isEmpty()) {
            return;
        }
        List<Object> list = (List<Object>) listObject;
        // The camera's filter component and its items, in the camera's own order
        Object registry = Class.forName(profile.registryClass(), false, loader)
                .getMethod(profile.registryMethod()).invoke(null);
        Object component = registry.getClass().getMethod(profile.lookupMethod(), Class.class)
                .invoke(registry, Class.forName(profile.componentClass(), false, loader));
        List<?> official = (List<?>) component.getClass().getMethod("getItems").invoke(component);
        if (official == null || official.isEmpty()) {
            return;
        }
        Map<Object, Integer> rank = new java.util.IdentityHashMap<>();
        for (int i = 0; i < official.size(); i++) {
            rank.put(official.get(i), i);
        }
        Field data = findField(list.get(0).getClass(), profile.itemDataField());
        Method sortIndex = findMethod(list.get(0).getClass(), profile.sortIndexMethod());
        // The films are the strip items whose name is one of the film names. The user's own order
        // (Manage filters) is kept for everything else; the films go last, in the camera's order.
        // Existing film items are renamed here too, so a strip that the camera built with the
        // later artistic names shows the film codes as well.
        Map<Integer, Film> filmNames = filmNameMap(ALL_FILMS, resolved);
        Field title = null;
        List<Object> others = new ArrayList<>();
        List<Object> films = new ArrayList<>();
        for (Object item : list) {
            Object filter = data.get(item);
            boolean film = false;
            if (filter != null) {
                if (title == null) {
                    title = field(filter.getClass(), "l");
                }
                film = isFilm(filter, title, filmNames.keySet());
            }
            (film ? films : others).add(item);
        }
        // Films that are new to the strip get the default order (the films' own sort numbers);
        // films that were already there keep the user's order.
        if (fresh) {
            Field titleField = title;
            films.sort((a, b) -> Integer.compare(filmDefaultOrder(safeGet(data, a), titleField, resolved),
                    filmDefaultOrder(safeGet(data, b), titleField, resolved)));
        }
        log(Log.INFO, TAG, "films in the strip: " + films.size() + (fresh ? ", new, default order"
                : ", kept in the user's order"));
        List<Object> sorted = new ArrayList<>(others);
        sorted.addAll(films);
        log(Log.INFO, TAG, "filter component: " + official.size() + " items");
        logStrip(list, data, sorted.equals(list));
        if (sorted.equals(list)) {
            return;
        }
        list.clear();
        list.addAll(sorted);
        for (int i = 0; i < list.size(); i++) {
            sortIndex.invoke(list.get(i), i);
        }
        log(Log.INFO, TAG, "put the filter strip back in the camera's order (" + list.size()
                + " items)");
    }

    private static Object safeGet(Field field, Object item) {
        try {
            return field.get(item);
        } catch (Throwable t) {
            return null;
        }
    }

    // What the strip holds, for the log: its size and the names of its last items
    private void logStrip(List<Object> list, Field data, boolean inOrder) {
        try {
            StringBuilder tail = new StringBuilder();
            Application app = currentApplication();
            Field title = null;
            for (int i = Math.max(0, list.size() - 8); i < list.size(); i++) {
                Object filter = data.get(list.get(i));
                if (filter == null) {
                    tail.append("[no data] ");
                    continue;
                }
                if (title == null) {
                    title = field(filter.getClass(), "l");
                }
                int id = title.getInt(filter);
                String name;
                if ((id & 0xFFFF0000) == RESERVED_LOOK_NAME_BASE) {
                    name = (id & 0xFF) == 1 ? "M3" : "M9";
                } else if ((id & 0xFFFF0000) == FILM_NAME_BASE) {
                    name = filmLabel(id & 0xFFFF);
                } else if (id > 0 && app != null) {
                    name = app.getResources().getResourceEntryName(id);
                } else {
                    name = "none";
                }
                tail.append(name).append(' ');
            }
            log(Log.INFO, TAG, "filter strip: " + list.size() + " items, "
                    + (inOrder ? "in order" : "out of order") + ", last: " + tail);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "could not describe the filter strip: " + t);
        }
    }

    // A field of a class or of one of its superclasses
    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return field(c, name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    // A one-int-argument method of a class or of one of its superclasses
    private static Method findMethod(Class<?> cls, String name) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, int.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(name);
    }

    private void installPanelHook(Profile profile, ClassLoader loader) {
        try {
            var panel = Class.forName(profile.panelClass(), false, loader);
            var method = panel.getDeclaredMethod(profile.panelMethod());
            if (method.getReturnType() != boolean.class) {
                throw new NoSuchMethodException("does not return boolean");
            }
            hook(method).intercept(chain -> {
                log(Log.INFO, TAG, profile.panelMethod() + " called, returning false (no cloud merge)");
                return Boolean.FALSE;
            });
            log(Log.INFO, TAG, "hooked " + profile.panelClass() + "." + profile.panelMethod());
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook " + profile.panelClass() + "." + profile.panelMethod()
                    + ": " + t);
            problems.add(MSG_PANEL);
        }
    }

    // The name icon of a film item is a made-up resource id, so every way the camera loads it
    // has to be answered here: Resources.getDrawable (Context.getDrawable and ImageView end up
    // there) and, as a shortcut, ImageView.setImageResource. Without the first, the camera
    // throws Resources.NotFoundException when it shows the filter's name in its top banner.
    private void installNameIconHook() {
        if (nameHooksInstalled) {
            return;
        }
        nameHooksInstalled = true;
        installNameStringHook();
        try {
            var method = Resources.class.getDeclaredMethod("getDrawable", int.class,
                    Resources.Theme.class);
            hook(method).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) != NAME_ICON_BASE) {
                    return chain.proceed();
                }
                return nameDrawable((Resources) chain.getThisObject(), id & 0xFFFF);
            });
            nameIconHooked = true;
            log(Log.INFO, TAG, "hooked Resources.getDrawable (film name labels)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook Resources.getDrawable, using the stock name icon: "
                    + t);
            return;
        }
        try {
            var method = ImageView.class.getDeclaredMethod("setImageResource", int.class);
            hook(method).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) != NAME_ICON_BASE) {
                    return chain.proceed();
                }
                ImageView view = (ImageView) chain.getThisObject();
                view.setImageDrawable(nameDrawable(view.getResources(), id & 0xFFFF));
                return null;
            });
            log(Log.INFO, TAG, "hooked ImageView.setImageResource (film name labels)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook ImageView.setImageResource: " + t);
        }
    }

    // The film item's name field is a made-up string resource id. Every text path the camera
    // uses for a filter name is answered here, so the strip and the top banner always show the
    // film code even when the running camera normally calls it 反转/韩胶/贝松/...
    private void installNameStringHook() {
        boolean getString = false;
        boolean getText = false;
        try {
            var method = Resources.class.getDeclaredMethod("getString", int.class);
            hook(method).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) == FILM_NAME_BASE) {
                    return filmLabel(id & 0xFFFF);
                }
                if ((id & 0xFFFF0000) == RESERVED_LOOK_NAME_BASE) {
                    return (id & 0xFF) == 1 ? "M3" : "M9";
                }
                return chain.proceed();
            });
            getString = true;
            log(Log.INFO, TAG, "hooked Resources.getString (film names)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook Resources.getString: " + t);
        }
        try {
            var method = Resources.class.getDeclaredMethod("getText", int.class);
            hook(method).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) == FILM_NAME_BASE) {
                    return filmLabel(id & 0xFFFF);
                }
                if ((id & 0xFFFF0000) == RESERVED_LOOK_NAME_BASE) {
                    return (id & 0xFF) == 1 ? "M3" : "M9";
                }
                return chain.proceed();
            });
            getText = true;
            log(Log.INFO, TAG, "hooked Resources.getText (film names)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook Resources.getText: " + t);
        }
        // Either text path is enough: the camera reads filter names through one of them.
        nameStringHooked = getString || getText;
        if (nameStringHooked) {
            log(Log.INFO, TAG, "film name hook active (" + (getString ? "getString " : "")
                    + (getText ? "getText " : ")"));
        } else {
            log(Log.WARN, TAG, "film name hooks not installed; using camera's own string resources");
        }
        // Logging/analytics may ask for the resource name of an item. Answer the made-up ids
        // instead of letting Resources throw NotFoundException.
        try {
            var method = Resources.class.getDeclaredMethod("getResourceEntryName", int.class);
            hook(method).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) == FILM_NAME_BASE) {
                    return "film_" + (id & 0xFFFF);
                }
                if ((id & 0xFFFF0000) == RESERVED_LOOK_NAME_BASE) {
                    return (id & 0xFF) == 1 ? "film_m3" : "film_m9";
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook getResourceEntryName: " + t);
        }
        try {
            var method = Resources.class.getDeclaredMethod("getResourceName", int.class);
            hook(method).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if ((id & 0xFFFF0000) == FILM_NAME_BASE) {
                    return "com.android.camera:string/film_" + (id & 0xFFFF);
                }
                if ((id & 0xFFFF0000) == RESERVED_LOOK_NAME_BASE) {
                    return "com.android.camera:string/film_" + ((id & 0xFF) == 1 ? "m3" : "m9");
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook getResourceName: " + t);
        }
    }

    // The label image. It extends VectorDrawable because the camera only tints vector drawables
    // (the top banner tints its name icon), so it has to be one for the label to be tinted too.
    private static final class LabelDrawable extends VectorDrawable {
        private final Bitmap bitmap;
        private final Bitmap ring;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private ColorStateList tint;
        private PorterDuff.Mode tintMode = PorterDuff.Mode.SRC_IN;

        LabelDrawable(Bitmap bitmap, Bitmap ring) {
            this.bitmap = bitmap;
            this.ring = ring;
            ringPaint.setAlpha(LABEL_RING_ALPHA);
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawBitmap(ring, null, getBounds(), ringPaint);
            canvas.drawBitmap(bitmap, null, getBounds(), paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return bitmap.getWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return bitmap.getHeight();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            ringPaint.setAlpha(alpha * LABEL_RING_ALPHA / 255);
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return paint.getAlpha();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public ColorFilter getColorFilter() {
            return paint.getColorFilter();
        }

        @Override
        public void setTintList(ColorStateList tint) {
            this.tint = tint;
            applyTint();
        }

        @Override
        public void setTintMode(PorterDuff.Mode mode) {
            tintMode = mode;
            applyTint();
        }

        private void applyTint() {
            paint.setColorFilter(tint == null ? null
                    : new PorterDuffColorFilter(tint.getDefaultColor(), tintMode));
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // The film label in the Leica label typeface, white on a transparent square the size of the
    // name icon; the panel tints its name images. Like the camera's own two-line labels, it has
    // a short top word ("FLM", for film) above the film's name, both lines centred as a block.
    // Every label uses the same letter height, weight and spacing, and natural widths. Letter
    // height and line positions are whole pixels, so the top and bottom edges of both lines are
    // equally sharp (a fractional position blurs one edge and makes the line look shorter).
    private static Drawable nameDrawable(Resources resources, int ordinal) {
        String label = "";
        for (Film film : ALL_FILMS) {
            if (film.ordinal() == ordinal) {
                label = film.label();
            }
        }
        float density = resources.getDisplayMetrics().density;
        int size = Math.round(LABEL_SIZE_DP * density);
        int letterHeight = Math.round(LABEL_GLYPH_HEIGHT_DP * density);
        int letterWidthBasis = Math.round(LABEL_CAP_DP * density);   // glyph widths, weight and spacing are those of this height
        int pitch = Math.round((LABEL_CAP_DP + LABEL_LINE_GAP_DP) * density);   // line 1 top to line 2 top
        int top = Math.round((size - (letterHeight + pitch)) / 2f + LABEL_BLOCK_SHIFT_DP * density);
        float scale = letterWidthBasis / (float) LeicaFont.CAP_HEIGHT;

        // Report the current screen density so the drawables are exactly size x size pixels
        // (a vector of 36 dp is, whatever display-size setting is in use).
        int densityDpi = resources.getDisplayMetrics().densityDpi;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(densityDpi);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColor(Color.WHITE);
        drawLine(canvas, paint, LABEL_TOP_LINE, size, top, scale, letterWidthBasis, LABEL_GAP_DP, density, 0);
        drawLine(canvas, paint, label, size, top + pitch, scale, letterHeight, LABEL_NAME_GAP_DP, density, 0);

        // The outline: the same letters spread outwards, in a fixed gray.
        Bitmap ring = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        ring.setDensity(densityDpi);
        Canvas ringCanvas = new Canvas(ring);
        Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ringPaint.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN));
        float spread = LABEL_RING_DP * density;
        drawLine(ringCanvas, ringPaint, LABEL_TOP_LINE, size, top, scale, letterWidthBasis, LABEL_GAP_DP, density, spread);
        drawLine(ringCanvas, ringPaint, label, size, top + pitch, scale, letterHeight, LABEL_NAME_GAP_DP, density, spread);
        return new LabelDrawable(bitmap, ring);
    }

    // One line of text, centred horizontally, with its top edge at y. With a spread, every letter
    // is drawn again at points on circles of that radius around its place, which grows it
    // outwards by that much (the outline).
    private static void drawLine(Canvas canvas, Paint paint, String text, int width, float y,
                                 float scale, float height, float gapDp, float density,
                                 float spread) {
        float cap = LeicaFont.CAP_HEIGHT;
        float gap = gapDp * density;
        float hyphen = HYPHEN_LENGTH * cap * scale;

        float letters = 0;
        for (char c : text.toCharArray()) {
            Bitmap glyph = c == '-' ? null : glyph(c);
            letters += c == '-' ? hyphen : glyph == null ? 0 : glyph.getWidth() * scale;
        }
        // Never wider than the label with its outline: tighten the gap when a text would not fit.
        if (text.length() > 1) {
            float room = width - 1 - 2 * LABEL_RING_DP * density;
            gap = Math.min(gap, (room - letters) / (text.length() - 1));
        }
        float total = letters + gap * (text.length() - 1);
        float x = (width - total) / 2f;
        for (char c : text.toCharArray()) {
            if (c == '-') {
                float thick = LeicaFont.STROKE * scale;
                float mid = y + height / 2f;
                float grown = thick / 2f + spread;
                canvas.drawRoundRect(new RectF(x - spread, mid - grown, x + hyphen + spread,
                        mid + grown), grown, grown, paint);
                x += hyphen;
            } else {
                Bitmap glyph = glyph(c);
                if (glyph != null) {
                    float glyphWidth = glyph.getWidth() * scale;
                    drawSpread(canvas, glyph, x, y, glyphWidth, height, spread, paint);
                    x += glyphWidth;
                }
            }
            x += gap;
        }
    }

    private static void drawSpread(Canvas canvas, Bitmap glyph, float x, float y, float w, float h,
                                   float spread, Paint paint) {
        if (spread <= 0) {
            canvas.drawBitmap(glyph, null, new RectF(x, y, x + w, y + h), paint);
            return;
        }
        for (int ring = 1; ring <= 2; ring++) {
            float radius = spread * ring / 2f;
            for (int step = 0; step < 16; step++) {
                double angle = Math.PI * 2 * step / 16;
                float dx = (float) Math.cos(angle) * radius;
                float dy = (float) Math.sin(angle) * radius;
                canvas.drawBitmap(glyph, null,
                        new RectF(x + dx, y + dy, x + dx + w, y + dy + h), paint);
            }
        }
    }

    private static synchronized Bitmap glyph(char c) {
        Bitmap glyph = GLYPH_CACHE.get(c);
        if (glyph == null) {
            for (String[] entry : LeicaFont.GLYPHS) {
                if (entry[0].charAt(0) == c) {
                    byte[] data = Base64.decode(entry[1], Base64.DEFAULT);
                    glyph = BitmapFactory.decodeByteArray(data, 0, data.length);
                    GLYPH_CACHE.put(c, glyph);
                }
            }
        }
        return glyph;
    }

    // The Leica UI lists "Looks" from a separate fixed table. Add the same film items there,
    // filling in the extra fields that panel reads (name icon, sample images).
    private void installLooksHook(Profile profile, ClassLoader loader, Resolved resolved) {
        try {
            var looks = Class.forName(profile.looksClass(), false, loader);
            Class<?> item = resolved.create().getDeclaringClass();
            Field iconField = field(item, profile.iconField());
            Field nameIconField = field(item, profile.nameIconField());
            Field samplesField = field(item, profile.samplesField());
            Field labelsField = field(item, profile.labelsField());
            Field descField = field(item, profile.descField());

            // c() is the still Looks table; d() is the video/second Looks list used by some
            // camera builds (the panel that shows Positive/Negative/VIV on Aurora).
            for (String methodName : new String[]{profile.looksMethod(), "d"}) {
                final int looksCategory = "d".equals(methodName) ? 7 : PHOTO_CATEGORY;
                try {
                    var method = looks.getDeclaredMethod(methodName);
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            FilmResult films = addFilms(result, LOOKS_FILMS, resolved,
                                    looksCategory);
                            for (Object entry : films.touched()) {
                                int icon = iconField.getInt(entry);
                                if (nameIconHooked) {
                                    nameIconField.setInt(entry,
                                            NAME_ICON_BASE | resolved.ordinal().getInt(entry));
                                } else if (films.created().contains(entry)) {
                                    nameIconField.setInt(entry, LOOKS_NAME_ICON);
                                }
                                samplesField.set(entry,
                                        new ArrayList<>(List.of(icon, icon, icon)));
                                labelsField.set(entry,
                                        new ArrayList<>(List.of(icon, icon, icon)));
                                descField.setInt(entry, 0);
                            }
                        } catch (Throwable t) {
                            log(Log.ERROR, TAG, "could not finish Looks items", t);
                        }
                        return result;
                    });
                    log(Log.INFO, TAG, "hooked " + profile.looksClass() + "." + methodName
                            + " (Leica Looks)");
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "cannot hook " + profile.looksClass() + "." + methodName
                            + ": " + t);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook " + profile.looksClass() + ": " + t);
            // Only worth telling the user on a phone that actually uses the Leica UI.
            if ("LCC".equals(systemProperty("ro.theme_customize"))) {
                problems.add(MSG_LEICA);
            }
        }
    }

    private static String systemProperty(String key) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, key);
        } catch (Throwable t) {
            return "";
        }
    }

    // ---- telling the user ----
    // A module has no screen of its own inside the camera, so a problem is shown as a toast the
    // first time the camera starts after a camera update, then not again for that camera version.
    private static final String MSG_UNSUPPORTED =
            "The camera's filter code is different from the layouts this module knows, so no "
                    + "film filters were added. The module needs an update for this camera version.";
    private static final String MSG_PANEL =
            "Some film filters may not show in the filter panel on this camera version.";
    private static final String MSG_LEICA =
            "The Leica Looks list is different on this camera version, so the film filters were "
                    + "not added there.";

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

    // Adds every missing film item, and renames any film entry the camera already has. An entry
    // is reused when its ordinal, its name resource (film code or the later artistic name), its
    // strip key or its cloud id says it is the same filter, so a camera that already lists the
    // filter gets one renamed entry instead of a duplicate. New entries are built with the film
    // code as their name resource.
    @SuppressWarnings("unchecked")
    private FilmResult addFilms(Object result, Film[] films, Resolved resolved) {
        return addFilms(result, films, resolved, PHOTO_CATEGORY);
    }

    private FilmResult addFilms(Object result, Film[] films, Resolved resolved, int category) {
        List<Object> touched = new ArrayList<>();
        List<Object> created = new ArrayList<>();
        try {
            if (!(result instanceof List<?>)) {
                return new FilmResult(touched, created);
            }
            List<Object> list = (List<Object>) result;
            if (debugEnabled()) {
                StringBuilder ords = new StringBuilder();
                for (Object entry : list) {
                    try {
                        ords.append(resolved.ordinal().getInt(entry)).append(' ');
                    } catch (Throwable ignored) {
                    }
                }
                log(Log.INFO, TAG, "film list ordinals: " + ords);
            }
            Map<Integer, Film> names = filmNameMap(films, resolved);
            Map<Integer, Object> kept = new HashMap<>();
            java.util.Iterator<Object> iterator = list.iterator();
            while (iterator.hasNext()) {
                Object entry = iterator.next();
                Film film = matchFilm(entry, resolved, films, names);
                if (film == null) {
                    continue;
                }
                Object previous = kept.putIfAbsent(film.ordinal(), entry);
                if (previous != null) {
                    iterator.remove();
                    log(Log.INFO, TAG, "removed duplicate " + film.label() + " entry (ordinal "
                            + film.ordinal() + ")");
                    continue;
                }
                renameFilm(entry, film, resolved, false);
                touched.add(entry);
            }
            List<String> added = new ArrayList<>();
            for (Film film : films) {
                if (kept.containsKey(film.ordinal())) {
                    continue;
                }
                if (film.ordinal() == LOOK_M9_SLOT || film.ordinal() == LOOK_M3_SLOT) {
                    // M3/M9 (ordinals 85/86) are added by the main module's own hooks; skip them
                    // here so the film pass never overwrites them.
                    continue;
                }
                try {
                    int name = filmNameId(film, resolved);
                    if (name == 0) {
                        log(Log.WARN, TAG, "no safe name resource for " + film.label()
                                + ", not adding it");
                        continue;
                    }
                    int image = resourceId(resolved.images(), film.imageId(), "drawable", "mipmap",
                            "raw");
                    Object entry = resolved.create().newInstance(category, film.ordinal(),
                            name, image, 0, film.order(), "NORMAL");
                    resolved.cloud().setInt(entry, film.cloudId());
                    renameFilm(entry, film, resolved, true);
                    list.add(entry);
                    touched.add(entry);
                    created.add(entry);
                    added.add(film.label());
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "could not add film " + film.label(), t);
                }
            }
            log(Log.INFO, TAG, "film list: " + list.size() + " items, reused " + kept.size()
                    + ", added " + added);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "could not add film filters", t);
        }
        return new FilmResult(touched, created);
    }

    private void renameFilms(List<?> list, Resolved resolved) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<Integer, Film> names = filmNameMap(ALL_FILMS, resolved);
        for (Object entry : list) {
            Film film = matchFilm(entry, resolved, ALL_FILMS, names);
            if (film != null) {
                renameFilm(entry, film, resolved, false);
            }
        }
    }

    // The made-up id always returns the film code through the hook. If the hooks could not be
    // installed, fall back to the camera's own code-name string resource (e.g.
    // color_effect_entry_kp_160). On 6.7/6.8 some of these were renamed away, so only use the
    // fallback when it actually exists.
    private int filmNameId(Film film, Resolved resolved) {
        if (nameStringHooked) {
            return FILM_NAME_BASE | film.ordinal();
        }
        int code = resourceIdSafe(resolved.strings(), film.codeStringId());
        if (code != 0) {
            return code;
        }
        // Last resort: keep the camera's existing name. Better than showing nothing.
        return 0;
    }

    private void renameFilm(Object entry, Film film, Resolved resolved, boolean created) {
        int name = filmNameId(film, resolved);
        if (name == 0) {
            log(Log.WARN, TAG, "no safe name resource for " + film.label()
                    + ", keeping the camera's label");
        } else {
            try {
                resolved.name().setInt(entry, name);
            } catch (Throwable t) {
                log(Log.WARN, TAG, "could not rename " + film.label() + ": " + t);
            }
        }
        // An entry matched by its stock name may still have the neutral ordinal/cloud id; fill
        // those in so the camera renders the filter it now represents.
        try {
            if (resolved.ordinal().getInt(entry) == 0) {
                resolved.ordinal().setInt(entry, film.ordinal());
            }
        } catch (Throwable ignored) {
        }
        try {
            if (resolved.cloud().getInt(entry) == 0) {
                resolved.cloud().setInt(entry, film.cloudId());
            }
        } catch (Throwable ignored) {
        }
        if (created) {
            for (String keyField : new String[]{"n", "r"}) {
                try {
                    field(entry.getClass(), keyField).setInt(entry,
                            FILM_KEY_BASE + film.ordinal());
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // Resource ids that mean one film: the made-up name id, the pre-6.7 code key and the key the
    // running camera uses for the same filter.
    private static Map<Integer, Film> filmNameMap(Film[] films, Resolved resolved) {
        Map<Integer, Film> names = new HashMap<>();
        for (Film film : films) {
            names.put(FILM_NAME_BASE | film.ordinal(), film);
            int code = resourceIdSafe(resolved.strings(), film.codeStringId());
            if (code != 0) {
                names.put(code, film);
            }
            int stock = resourceIdSafe(resolved.strings(), film.stockStringId());
            if (stock != 0) {
                names.put(stock, film);
            }
        }
        return names;
    }

    // The film an entry already represents, by ordinal, display name, strip key or cloud id.
    private static Film matchFilm(Object entry, Resolved resolved, Film[] films,
            Map<Integer, Film> names) {
        // The entry must be the camera's own filter item class (j3.b on 6.7/6.8).
        // Cloud-created items (com.android.camera.data.data.d) are not film items.
        if (entry.getClass() != resolved.create().getDeclaringClass()) {
            return null;
        }
        // M3/M9 are not films. They borrow the V250/H400 slots but carry a reserved label.
        try {
            int name = resolved.name().getInt(entry);
            if ((name & 0xFFFF0000) == RESERVED_LOOK_NAME_BASE) {
                return null;
            }
        } catch (Throwable ignored) {
        }
        try {
            int ordinal = resolved.ordinal().getInt(entry);
            if (ordinal == LOOK_M9_SLOT || ordinal == LOOK_M3_SLOT) {
                // M3/M9 occupy these ordinals; they are not films.
                return null;
            }
            for (Film film : films) {
                if (film.ordinal() == ordinal) {
                    return film;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Film byName = names.get(resolved.name().getInt(entry));
            if (byName != null) {
                return byName;
            }
        } catch (Throwable ignored) {
        }
        for (String keyField : new String[]{"n", "r"}) {
            try {
                Object key = field(entry.getClass(), keyField).get(entry);
                if (key instanceof String text && text.matches("\\d+")) {
                    int value = Integer.parseInt(text);
                    for (Film film : films) {
                        if (value == FILM_KEY_BASE + film.ordinal()) {
                            return film;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            int cloud = resolved.cloud().getInt(entry);
            for (Film film : films) {
                if (film.cloudId() != 0 && film.cloudId() == cloud) {
                    return film;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int resourceIdSafe(Class<?> ids, String name) {
        try {
            return resourceId(ids, name, "string");
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String filmLabel(int ordinal) {
        for (Film film : ALL_FILMS) {
            if (film.ordinal() == ordinal) {
                return film.label();
            }
        }
        return "";
    }

    // A resource id, from the camera's id class when it has one, otherwise looked up by name.
    private static int resourceId(Class<?> ids, String name, String... types) throws Exception {
        if (ids != null) {
            try {
                return ids.getField(name).getInt(null);
            } catch (Throwable ignored) {
            }
        }
        Application app = currentApplication();
        if (app != null) {
            for (String type : types) {
                int id = app.getResources().getIdentifier(name, type, app.getPackageName());
                if (id != 0) {
                    return id;
                }
            }
        }
        throw new NoSuchFieldException("resource " + name);
    }
}
