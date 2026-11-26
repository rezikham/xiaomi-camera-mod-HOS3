package io.martin.hypercameramod;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds Xiaomi re-edit data ({@code ReEditDataBeanMsg}) for JPEGs so Gallery/MediaEditor can
 * reopen the adjustment non-destructively. Layout mirrors camera 6.8
 * ({@code jpegrAggrPlugin::processFullLinkEditableJpeg}, docs/hal-backport/28 section 3):
 *
 * <pre>
 * ReEditDataBeanMsg { f3 presetData { f1 images[0] = clean JPEG, f2 opList[0] = BizOpBeanMsg } }
 * BizOpBeanMsg { f1 "filter", f2 "1.0", f3 FilterBeanMsg, f11 "micamera" }
 * FilterBeanMsg { f1 filterId, f2 progress, f3 renderType, f4 colorParam, f5 "APPLY_FILTER",
 *                 f8 miCamStyle }
 * </pre>
 *
 * The payload is appended after EOI; XMP gets micamedit:version/offset/len where offset is the
 * EOF-relative distance to the payload start (unverified for JPEG: only HEIC samples exist).
 */
public final class ReEditWriter {
    private static final String TAG = "HCM-ReEdit";
    private static final String NS = "http://ns.xiaomi.com/photos/1.0/camera/reedit";

    private ReEditWriter() {}

    /** Off by default: the JPEG offset convention is not yet verified against a real sample. */
    public static boolean enabled() {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return "1".equals(c.getMethod("get", String.class, String.class)
                    .invoke(null, "camera.feature.reedit", "0"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Script describing the Legendary look so MediaEditor can re-render it. */
    public static String scriptFor(int legend) {
        return "@CubeLutEffect;cube_strength=1.0;lut_type=0;"
                + "@@StyleAdjustEffect;Algos=0;SharpenIntensity=0.0;ContrastIntensity=0.0;"
                + "SaturationIntensity=" + (legend == 2 ? "-1.0" : "0.0") + ";Temperature=0.0;"
                + "Tint=0.0;Grain=0.0;VignetteIntensity=0.0";
    }

    public static String styleName(int legend) {
        return legend == 1 ? "M9" : legend == 2 ? "M3" : legend == 3 ? "M10R" : "";
    }

    /**
     * @param processed JPEG with EXIF/XMP already finalised
     * @param clean     the unprocessed capture, stored as images[0]
     */
    public static byte[] append(byte[] processed, byte[] clean, int filterId, String script,
                                String style) {
        try {
            byte[] filter = cat(
                    varint(1, filterId), varint(2, 100), str(3, "513"), str(4, script),
                    str(5, "APPLY_FILTER"), style.isEmpty() ? new byte[0] : str(8, style));
            byte[] op = cat(str(1, "filter"), str(2, "1.0"), bytes(3, filter), str(11, "micamera"));
            byte[] preset = cat(bytes(1, clean), bytes(2, op));
            byte[] payload = bytes(3, preset);

            byte[] xmpd = ("  <rdf:Description rdf:about=\"\" xmlns:micamedit=\"" + NS + "\"\n"
                    + "    micamedit:version=\"1\" micamedit:offset=\"" + payload.length
                    + "\" micamedit:len=\"" + payload.length + "\"/>\n")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] withXmp = addXmp(processed, xmpd);
            if (withXmp == null) {
                return processed;
            }
            byte[] flagged = ExifUtils.setReEditFlag(withXmp);
            ByteArrayOutputStream out = new ByteArrayOutputStream(flagged.length + payload.length);
            out.write(flagged, 0, flagged.length);
            out.write(payload, 0, payload.length);
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "re-edit append failed: " + t);
            return processed;
        }
    }

    private static byte[] addXmp(byte[] jpeg, byte[] desc) {
        byte[] hdr = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);
        int i = 2;
        while (i + 4 <= jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (len < 2 || i + len + 2 > jpeg.length) {
                break;
            }
            if (marker == 0xE1 && len >= 2 + hdr.length) {
                boolean m = true;
                for (int k = 0; k < hdr.length && m; k++) {
                    m = jpeg[i + 4 + k] == hdr[k];
                }
                if (m) {
                    int ps = i + 4 + hdr.length;
                    String x = new String(jpeg, ps, len - 2 - hdr.length, StandardCharsets.UTF_8);
                    if (x.contains(NS)) {
                        return jpeg;
                    }
                    int end = x.lastIndexOf("</rdf:RDF>");
                    if (end < 0) {
                        return null;
                    }
                    byte[] np = (x.substring(0, end) + new String(desc, StandardCharsets.UTF_8)
                            + x.substring(end)).getBytes(StandardCharsets.UTF_8);
                    int nl = 2 + hdr.length + np.length;
                    if (nl > 0xFFFF) {
                        return null;
                    }
                    ByteArrayOutputStream o = new ByteArrayOutputStream(jpeg.length + desc.length);
                    o.write(jpeg, 0, i);
                    o.write(0xFF);
                    o.write(0xE1);
                    o.write(nl >> 8);
                    o.write(nl & 0xFF);
                    o.write(hdr, 0, hdr.length);
                    o.write(np, 0, np.length);
                    int after = i + 2 + len;
                    o.write(jpeg, after, jpeg.length - after);
                    return o.toByteArray();
                }
            }
            i += len + 2;
        }
        return null; // no XMP to extend: caller keeps the file unchanged
    }

    private static byte[] varint(int field, long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeVar(o, (long) field << 3);
        writeVar(o, v);
        return o.toByteArray();
    }

    private static byte[] str(int field, String s) {
        return bytes(field, s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(int field, byte[] b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length + 8);
        writeVar(o, ((long) field << 3) | 2);
        writeVar(o, b.length);
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    private static void writeVar(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }
}
