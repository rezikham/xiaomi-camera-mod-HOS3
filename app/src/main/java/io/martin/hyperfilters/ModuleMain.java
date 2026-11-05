package io.martin.hyperfilters;

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

    // Where the camera keeps its filter code. The camera is obfuscated, so the names change from
    // build to build; a profile is one known layout. The module tries each profile in turn and
    // uses the first whose classes, methods and fields all exist. When none fits, the module
    // leaves the camera alone and tells the user.
    private record Profile(
            String name,
            // Builds the photo filter list (category 10) as a list of items.
            String listClass, String listMethod,
            // The filter item and its fields: filter ordinal, cloud id, icon, name icon,
            // sample images, sample labels, description.
            String itemClass, String ordinalField, String cloudField, String iconField,
            String nameIconField, String samplesField, String labelsField, String descField,
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
                    "j3.b", "b", "l", "e", "d", "g", "h", "i",
                    "Di.p", "Di.n",
                    "com.android.camera.fragment.m0", "ut",
                    "E9.h", "c",
                    "com.android.camera.features.mode.capture.e", "L", "Y", "G",
                    "h2.a", "a", "x", "s2.t", "Ei.q", "b", "com.xiaomi.camera.effect.EffectController", "q"),
    };

    // ic_vector_lc_looks_filter_name_icon_standard in 6.7.000280.0 (no R classes at runtime).
    private static final int LOOKS_NAME_ICON = 0x7f0808f5;

    // ordinal = position of the filter in the camera's filter enum (p3.d), order = sort slot,
    // cloudId = the id the camera's own item builder gives the same filter.
    private record Film(int ordinal, String label, String stringId, String imageId, int order,
                        int cloudId) {
    }

    private static final Film[] FILMS = {
            new Film(81, "R600", "color_effect_entry_r_600", "color_effect_image_r_600", 14, 56),
            new Film(82, "P100F", "color_effect_entry_p_100f", "color_effect_image_p_100f", 15, 57),
            new Film(83, "F-50", "color_effect_entry_reversal", "color_effect_image_f_50", 16, 46),
            new Film(84, "KC64", "color_effect_entry_hanjiao", "color_effect_image_c_64", 17, 38),
    };

    // The Leica Looks list names the filters by their film codes, and does not list KP160.
    private static final Film[] LOOKS_FILMS = {
            new Film(81, "R600", "color_effect_entry_r_600", "color_effect_image_r_600", 14, 56),
            new Film(82, "P100F", "color_effect_entry_p_100f", "color_effect_image_p_100f", 15, 57),
            new Film(83, "F-50", "color_effect_entry_f_50", "color_effect_image_f_50", 16, 46),
            new Film(84, "KC64", "color_effect_entry_kc_64", "color_effect_image_c_64", 17, 38),
            new Film(87, "KP160", "color_effect_entry_kp_160", "color_effect_image_p_160nc", 18, 58),
    };

    // The Looks list draws a small image of each look's name under its thumbnail. There is no
    // such image for the film filters, so the name icon is a made-up resource id (this base plus
    // the filter ordinal) that ImageView.setImageResource turns into the film's name as text.
    private static final int STANDARD_CATEGORY = 2;
    // The camera identifies a filter in its strip by a key made from this field; the films were
    // all left at 0 and so shared one key. Give each its own, clear of the camera's (8 bit) ids.
    private static final int FILM_KEY_BASE = 0x10000;
    private static final int NAME_ICON_BASE = 0x7e000000;
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
    // What did not work, shown to the user once per camera version (see scheduleNotice).
    private final List<String> problems = new CopyOnWriteArrayList<>();
    private final AtomicBoolean noticeScheduled = new AtomicBoolean();

    // What a profile resolved to on the running camera.
    private record Resolved(Profile profile, Constructor<?> create, Field ordinal, Field cloud,
                            Class<?> strings, Class<?> images) {
    }

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
        if (!CAMERA_PACKAGE.equals(pkg) || hooked || loader == null) {
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
                continue;
            }
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
            log(Log.INFO, TAG, stage + ": camera layout " + profile.name() + ", hooked "
                    + profile.listClass() + "." + profile.listMethod());
            installPanelHook(profile, loader);
            installNameIconHook();
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
            Class<?> strings = Class.forName("Z9.f", false, loader);
            // The camera has no class for its image ids (they are inlined), so the images are
            // looked up by name when the lists are built.
            for (Film film : FILMS) {
                strings.getField(film.stringId());
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
                        addLegacyFilms(list, c, create, category, ordinal, strings);
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "could not add film filters", t);
                }
                return result;
            });
            hooked = true;
            log(Log.INFO, TAG, stage + ": camera layout 6.4, hooked " + effect.getName() + "."
                    + lists.getName());
            installLegacyComponentHook(loader);
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, stage + ": camera layout 6.4 does not match: " + t);
            return false;
        }
    }

    // As in 6.7, the camera builds its filter component from the list above, or, when it holds
    // cloud filter data for the mode, from that data merged with the list, which leaves the films
    // out. When the films are missing after that, build it again from the list alone.
    private void installLegacyComponentHook(ClassLoader loader) {
        try {
            Class<?> cls = Class.forName("f0.M", false, loader);
            Method build = cls.getDeclaredMethod("mapToCloudItems", int.class, Map.class);
            Method init = cls.getDeclaredMethod("initItems", int.class);
            Method items = cls.getMethod("getItems");
            Class<?> data = Class.forName("com.android.camera.data.data.d", false, loader);
            Field key = field(data, "p");
            hook(init).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    List<?> built = (List<?>) items.invoke(chain.getThisObject());
                    log(Log.INFO, TAG, "filter component set up for mode " + chain.getArg(0) + ": "
                            + built.size() + " items, " + countLegacyFilms(built, key) + " films");
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "could not check the filter component", t);
                }
                return result;
            });
            hook(build).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object mode = chain.getArg(0);
                    Map<?, ?> cloud = (Map<?, ?>) chain.getArg(1);
                    List<?> built = (List<?>) items.invoke(chain.getThisObject());
                    boolean hasCloud = cloud != null && cloud.containsKey(mode);
                    int films = countLegacyFilms(built, key);
                    log(Log.INFO, TAG, "filter component for mode " + mode + ": " + built.size()
                            + " items, " + films + " films, cloud data " + hasCloud);
                    if (films == 0 && hasCloud) {
                        Map<Object, Object> local = new java.util.concurrent.ConcurrentHashMap<>(cloud);
                        local.remove(mode);
                        build.invoke(chain.getThisObject(), mode, local);
                        built = (List<?>) items.invoke(chain.getThisObject());
                        log(Log.INFO, TAG, "rebuilt from the list: " + built.size() + " items, "
                                + countLegacyFilms(built, key) + " films");
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "could not check the filter component", t);
                }
                return result;
            });
            log(Log.INFO, TAG, "hooked f0.M (filter component)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook the filter component: " + t);
        }
    }

    // A component filter's key is its category in the high 16 bits and its ordinal in the low 16
    private static int countLegacyFilms(List<?> built, Field key) throws Exception {
        int count = 0;
        for (Object item : built) {
            Object k = key.get(item);
            if (k instanceof String text && text.matches("\\d+")) {
                int ordinal = (int) (Long.parseLong(text) & 0xFFFF);
                for (Film film : FILMS) {
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
        for (int i = 0; i < FILMS.length; i++) {
            Film film = FILMS[i];
            if (present.contains(film.ordinal())) {
                continue;
            }
            int name = resourceId(strings, film.stringId(), "string");
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
        Class<?> item = Class.forName(profile.itemClass(), false, loader);
        Constructor<?> create = item.getDeclaredConstructor(int.class, int.class, int.class,
                int.class, int.class, int.class, String.class);
        create.setAccessible(true);
        Field ordinal = field(item, profile.ordinalField());
        Field cloud = field(item, profile.cloudField());
        return new Resolved(profile, create, ordinal, cloud,
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
                if (category instanceof Integer c && (c == STANDARD_CATEGORY || c == PHOTO_CATEGORY)) {
                    // The list for category 10 can differ from the one the camera built (000500
                    // hands out a copy without the films), so check it here too; films already
                    // in the list are left alone.
                    List<Object> created = addFilms(result, FILMS, resolved);
                    if (!created.isEmpty()) {
                        log(Log.INFO, TAG, "category " + c + ": added " + created.size()
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
                    if (films == 0 && hasCloud) {
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
        Set<Integer> names = new HashSet<>();
        for (Film[] group : new Film[][] {FILMS, LOOKS_FILMS}) {
            for (Film film : group) {
                names.add(resourceId(resolved.strings(), film.stringId(), "string"));
            }
        }
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

    // The film's own sort number (R600, P100F, F-50, KC64, KP160), found by key or by name
    private static int filmDefaultOrder(Object filter, Field title, Resolved resolved) {
        try {
            Object key = field(filter.getClass(), "r").get(filter);
            int name = title.getInt(filter);
            for (Film[] group : new Film[][] {LOOKS_FILMS, FILMS}) {
                for (Film film : group) {
                    if (key instanceof String k && k.equals(String.valueOf(FILM_KEY_BASE + film.ordinal()))
                            || name == resourceId(resolved.strings(), film.stringId(), "string")) {
                        return film.order();
                    }
                }
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
        Set<Integer> names = new HashSet<>();
        for (Film film : FILMS) {
            names.add(resourceId(resolved.strings(), film.stringId(), "string"));
        }
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
        Set<Integer> filmNames = new HashSet<>();
        for (Film[] group : new Film[][] {FILMS, LOOKS_FILMS}) {
            for (Film film : group) {
                try {
                    filmNames.add(resourceId(resolved.strings(), film.stringId(), "string"));
                } catch (Throwable ignored) {
                }
            }
        }
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
                film = isFilm(filter, title, filmNames);
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
                String name = id > 0 && app != null
                        ? app.getResources().getResourceEntryName(id) : "none";
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
        for (Film film : LOOKS_FILMS) {
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
            var method = looks.getDeclaredMethod(profile.looksMethod());
            Class<?> item = resolved.create().getDeclaringClass();
            Field iconField = field(item, profile.iconField());
            Field nameIconField = field(item, profile.nameIconField());
            Field samplesField = field(item, profile.samplesField());
            Field labelsField = field(item, profile.labelsField());
            Field descField = field(item, profile.descField());

            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    for (Object entry : addFilms(result, LOOKS_FILMS, resolved)) {
                        int icon = iconField.getInt(entry);
                        nameIconField.setInt(entry, nameIconHooked
                                ? NAME_ICON_BASE | resolved.ordinal().getInt(entry)
                                : LOOKS_NAME_ICON);
                        samplesField.set(entry, new ArrayList<>(List.of(icon, icon, icon)));
                        labelsField.set(entry, new ArrayList<>(List.of(icon, icon, icon)));
                        descField.setInt(entry, 0);
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "could not finish Looks items", t);
                }
                return result;
            });
            log(Log.INFO, TAG, "hooked " + profile.looksClass() + "." + profile.looksMethod()
                    + " (Leica Looks)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook " + profile.looksClass() + "." + profile.looksMethod()
                    + ": " + t);
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

    // Appends any missing film items to the finished list. Leaves the list alone on any surprise.
    @SuppressWarnings("unchecked")
    private List<Object> addFilms(Object result, Film[] films, Resolved resolved) {
        List<Object> created = new ArrayList<>();
        try {
            if (!(result instanceof List<?>)) {
                return created;
            }
            List<Object> list = (List<Object>) result;
            Set<Integer> present = new HashSet<>();
            for (Object entry : list) {
                present.add(resolved.ordinal().getInt(entry));
            }
            List<String> added = new ArrayList<>();
            for (Film film : films) {
                if (present.contains(film.ordinal())) {
                    continue;
                }
                int name = resourceId(resolved.strings(), film.stringId(), "string");
                int image = resourceId(resolved.images(), film.imageId(), "drawable", "mipmap",
                        "raw");
                Object entry = resolved.create().newInstance(PHOTO_CATEGORY, film.ordinal(), name,
                        image, 0, film.order(), "NORMAL");
                resolved.cloud().setInt(entry, film.cloudId());
                if (films == FILMS) {
                    field(entry.getClass(), "n").setInt(entry, FILM_KEY_BASE + film.ordinal());
                }
                list.add(entry);
                created.add(entry);
                added.add(film.label());
            }
            log(Log.INFO, TAG, "filter list: " + present.size() + " items, added " + added);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "could not add film filters", t);
        }
        return created;
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
