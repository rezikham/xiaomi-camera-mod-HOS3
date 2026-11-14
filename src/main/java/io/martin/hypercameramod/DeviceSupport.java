package io.martin.hypercameramod;

import java.util.Locale;

/**
 * Tells the phone model apart so features that depend on the camera hardware layer can start
 * with the right default.
 *
 * <p>The camera hooks run in the Camera process and the settings app runs in its own process, so
 * this class reads the model from system properties, which both can do without permissions.
 */
public final class DeviceSupport {

    public static final int UNKNOWN = 0;
    public static final int XIAOMI_14_ULTRA = 1;
    public static final int XIAOMI_17_ULTRA = 2;
    public static final int XIAOMI_18_PRO_MAX = 3;

    private DeviceSupport() {
    }

    public static int family() {
        String name = (prop("ro.product.marketname") + " "
                + prop("ro.product.odm.marketname") + " "
                + prop("ro.product.vendor.marketname"))
                .toUpperCase(Locale.ROOT);
        String device = (prop("ro.product.device") + " " + prop("ro.product.name"))
                .toLowerCase(Locale.ROOT);

        if (name.contains("18 PRO MAX") || name.contains("18 PROMAX")) {
            return XIAOMI_18_PRO_MAX;
        }
        if (name.contains("17 ULTRA")) {
            return XIAOMI_17_ULTRA;
        }
        // 14 Ultra codenames are aurora (China) and aurorapro (global).
        if (name.contains("14 ULTRA") || device.contains("aurora")) {
            return XIAOMI_14_ULTRA;
        }
        return UNKNOWN;
    }

    /**
     * The Original portrait style starts enabled on the phones whose hardware layer implements
     * it. On the 14 Ultra the Original portrait preview session never returns a frame (see the
     * README), so it starts disabled there.
     */
    public static boolean originalPortraitDefault() {
        int family = family();
        return family == XIAOMI_17_ULTRA || family == XIAOMI_18_PRO_MAX;
    }

    /** Human readable model for logs, or "unknown". */
    public static String modelName() {
        String name = prop("ro.product.marketname");
        if (name.isEmpty()) {
            name = prop("ro.product.odm.marketname");
        }
        if (name.isEmpty()) {
            name = prop("ro.product.device");
        }
        return name.isEmpty() ? "unknown" : name;
    }

    private static String prop(String key) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Object value = props.getMethod("get", String.class).invoke(null, key);
            return value instanceof String string ? string : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
