package io.martin.hypercameramod;

import java.io.File;

/**
 * What the camera hardware layer can actually do, as opposed to what the camera UI can list.
 *
 * <p>The 14 Ultra, 17 Ultra and 18 Pro Max share most of the Camera app, but not the vendor camera
 * HAL. In particular the Leica Essential / Legendary processing plugins and the M10R style models
 * are only present on some firmwares. The module uses these checks so a style is only exposed when
 * the files needed to render it exist. On a device with a ported HAL overlay the same checks turn
 * true without any per-device code.
 *
 * <p>File existence is enough here: the paths are part of the vendor partition and are not
 * readable by the wrong process by accident. A failed check is treated as "not available", which
 * keeps the camera usable on a stock 14 Ultra.
 */
public final class HalCapabilities {

    private static final String ODM = "/odm";
    private static final String CAMERA = ODM + "/etc/camera";
    private static final String LEGEND_BRIDGE_MARKER =
            CAMERA + "/hypercameramod_legend_bridge";
    private static final String PLUGINS = ODM + "/lib64/camera/plugins";
    private static final String LIB = ODM + "/lib64";

    private HalCapabilities() {
    }

    /** The snapshot plugin that applies the Leica Essential M3/M9 parameter sets. */
    public static boolean hasMileicaFilter() {
        return exists(PLUGINS + "/com.xiaomi.plugin.mileicafilter.so");
    }

    /** The neural style-transfer plugin used by the Legendary snapshot pipeline. */
    public static boolean hasLegendST() {
        return exists(PLUGINS + "/com.xiaomi.plugin.legendST.so");
    }

    /** The colour-transform plugin used by the M10R (CMOS) style on 18 Pro Max. */
    public static boolean hasLegendCT() {
        return exists(PLUGINS + "/com.xiaomi.plugin.legendCT.so");
    }

    /**
     * M3/M9 Legendary processing. This is the set that the 17 Ultra has and the stock 14 Ultra
     * does not: the filter parameter files, the filter plugin, and the style-transfer plugin.
     */
    public static boolean hasLegendHal() {
        return hasMileicaFilter()
                && hasLegendST()
                && exists(CAMERA + "/leica_filter_param.bin")
                && exists(CAMERA + "/leica_filter_param_m3.bin")
                && exists(CAMERA + "/leica_filter_param_m9_preview.bin")
                && exists(CAMERA + "/leica_filter_param_m9_snapshot.bin");
    }

    /**
     * A working M10R capture needs more than an M10R picker item: the CMOS colour transform, the
     * two M10R filter parameter files, and the two M10R style-transfer models. The 17 Ultra and a
     * stock 14 Ultra have none of them, so the module must not inject the item there.
     */
    public static boolean hasM10r() {
        return hasMileicaFilter()
                && hasLegendCT()
                && exists(LIB + "/libmialgo_styletrans.so")
                && exists(CAMERA + "/leica_filter_param_m10r_preview.bin")
                && exists(CAMERA + "/leica_filter_param_m10r_snapshot.bin")
                && exists(CAMERA + "/styletrans/compressed/styletrans_high_m10r.minn")
                && exists(CAMERA + "/styletrans/compressed/styletrans_low_m10r.minn");
    }

    /**
     * Marker written by the Aurora port. It says the ported plugin expects the Legendary
     * selection in {@code stylizationType}; the module then bridges the app's
     * {@code legendMode} request into that key.
     */
    public static boolean hasLegendBridge() {
        return exists(LEGEND_BRIDGE_MARKER);
    }

    /** The external style-transfer engine and its model description. */
    public static boolean hasStyleTransfer() {
        return exists(LIB + "/libmialgo_styletrans.so")
                && exists(CAMERA + "/styletrans_params.json");
    }

    /** Short state for one log line; no file paths, so it stays readable in a bug report. */
    public static String summary() {
        return "legend=" + hasLegendHal()
                + " m10r=" + hasM10r()
                + " styleTransfer=" + hasStyleTransfer()
                + " bridge=" + hasLegendBridge();
    }

    private static boolean exists(String path) {
        try {
            return new File(path).exists();
        } catch (Throwable t) {
            return false;
        }
    }
}
