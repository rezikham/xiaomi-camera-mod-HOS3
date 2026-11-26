package io.martin.hypercameramod;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

/**
 * EXIF tag injection for Gallery LEICA button recognition.
 *
 * Gallery reads EXIF tag id 34992 (0x88B0), type BYTE, count 1, value = legend mode.
 * When present, Gallery shows the LEICA badge/button on the photo.
 *
 * Values:
 *   1 = M9 (CCD colour)
 *   2 = M3 (B&W Monopan)
 *   3 = M10R (CMOS colour)
 */
public final class ExifUtils {

    private static final String TAG = "HCM-ExifUtils";

    /** EXIF tag id for legendmode, as read by Gallery (ExifIFD). */
    public static final int TAG_LEGENDMODE = 34992; // 0x88B0
    /** IFD0 tag pointing to ExifIFD. */
    private static final int TAG_EXIF_IFD = 0x8769;

    /** EXIF type codes. */
    private static final int TYPE_BYTE = 1;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;

    /** Size in bytes per type. */
    private static final int TYPE_SIZE_BYTE = 1;
    private static final int TYPE_SIZE_SHORT = 2;
    private static final int TYPE_SIZE_LONG = 4;
    private static final int TYPE_SIZE_RATIONAL = 8;

    private ExifUtils() {}

    /**
     * Injects the legendmode EXIF tag into JPEG bytes.
     *
     * @param jpeg  Source JPEG bytes (must start with FF D8)
     * @param legendMode  1=M9, 2=M3, 0=off (no tag added)
     * @return JPEG bytes with legendmode tag injected, or original bytes on failure
     */
    public static byte[] injectLegendMode(byte[] jpeg, int legendMode) {
        if (legendMode == 0) {
            return jpeg;
        }
        if (jpeg == null || jpeg.length < 4
                || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            Log.w(TAG, "not a JPEG");
            return jpeg;
        }

        try {
            byte[] withExif = injectLegendModeIntoExif(jpeg, (byte) legendMode);
            return injectLegendXmp(withExif, legendMode);
        } catch (Throwable t) {
            Log.w(TAG, "EXIF injection failed: " + t);
            return jpeg;
        }
    }

    private static byte[] injectLegendModeIntoExif(byte[] jpeg, byte legendMode) {
        // Find APP1 (EXIF) segment
        int i = 2;
        while (i + 4 <= jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                break;
            }
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break; // SOS or EOI - no EXIF found
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (length < 2 || i + length + 2 > jpeg.length) {
                break;
            }

            if (marker == 0xE1 && length >= 10) {
                // Check for "Exif\0\0" header
                int next = i + 4;
                if (jpeg[next] == 'E' && jpeg[next + 1] == 'x' && jpeg[next + 2] == 'i'
                        && jpeg[next + 3] == 'f' && jpeg[next + 4] == 0 && jpeg[next + 5] == 0) {
                    return injectIntoExifSegment(jpeg, i, length, legendMode);
                }
            }
            i += length + 2;
        }

        // No EXIF segment found - create one
        Log.i(TAG, "no EXIF segment found, creating new APP1");
        return createExifSegment(jpeg, legendMode);
    }

    /**
     * Injects {@link #TAG_LEGENDMODE} (0x88B0) into ExifIFD (IfdId=2), where Gallery's
     * {@code ExifUtil.isEssentialPhoto()} reads it.
     *
     * <p>Inserts the 12-byte entry in-place at the end of ExifIFD and shifts all TIFF
     * pointers/offsets at or beyond the insertion point by +12 so streaming parsers
     * such as {@code com.miui.gallery3d.exif.ExifParser} never have to seek backwards.
     */
    private static byte[] injectIntoExifSegment(byte[] jpeg, int exifOffset, int exifLength,
                                                 byte legendMode) {
        int tiffOffset = exifOffset + 10; // FF E1 + length + "Exif\0\0"
        int tiffLength = exifLength - 8;  // length includes itself and "Exif\0\0"
        if (tiffOffset + 8 > jpeg.length || tiffLength < 8) {
            Log.w(TAG, "EXIF segment too small");
            return jpeg;
        }

        ByteOrder order;
        if (jpeg[tiffOffset] == 'I' && jpeg[tiffOffset + 1] == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (jpeg[tiffOffset] == 'M' && jpeg[tiffOffset + 1] == 'M') {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            Log.w(TAG, "unknown TIFF byte order");
            return jpeg;
        }
        if (read16(jpeg, tiffOffset + 2, order) != 0x2A) {
            Log.w(TAG, "not a TIFF file");
            return jpeg;
        }
        int ifd0Offset = read32(jpeg, tiffOffset + 4, order);
        if (ifd0Offset < 0 || tiffOffset + ifd0Offset + 2 > jpeg.length) {
            Log.w(TAG, "IFD0 offset out of bounds");
            return jpeg;
        }

        byte[] tiff = new byte[tiffLength];
        System.arraycopy(jpeg, tiffOffset, tiff, 0, tiffLength);

        int entryCount = read16(tiff, ifd0Offset, order);
        int entriesStart = ifd0Offset + 2;
        int nextPointer = entriesStart + entryCount * 12;
        if (entryCount < 0 || nextPointer + 4 > tiffLength) {
            Log.w(TAG, "IFD0 entry list out of bounds");
            return jpeg;
        }

        int exifIfdOffset = -1;
        for (int n = 0; n < entryCount; n++) {
            int entry = entriesStart + n * 12;
            if (read16(tiff, entry, order) == TAG_EXIF_IFD) {
                exifIfdOffset = read32(tiff, entry + 8, order);
                break;
            }
        }

        if (exifIfdOffset > 0 && exifIfdOffset + 2 <= tiffLength) {
            int exifCount = read16(tiff, exifIfdOffset, order);
            int exifEntriesStart = exifIfdOffset + 2;
            int insertPoint = exifEntriesStart + exifCount * 12;
            if (exifCount < 0 || insertPoint + 4 > tiffLength) {
                Log.w(TAG, "ExifIFD entry list out of bounds");
                return jpeg;
            }

            // Update in place if 0x88B0 already exists in ExifIFD.
            for (int n = 0; n < exifCount; n++) {
                int entry = exifEntriesStart + n * 12;
                if (read16(tiff, entry, order) == TAG_LEGENDMODE) {
                    int type = read16(tiff, entry + 2, order);
                    long count = read32(tiff, entry + 4, order) & 0xFFFFFFFFL;
                    if (type == TYPE_BYTE && count == 1) {
                        jpeg[tiffOffset + entry + 8] = legendMode;
                    }
                    return jpeg;
                }
            }

            int newExifLength = exifLength + 12;
            if (newExifLength > 0xFFFF) {
                Log.w(TAG, "EXIF segment would overflow");
                return jpeg;
            }

            shiftOffsetsFrom(tiff, ifd0Offset, insertPoint, 12, order, new java.util.HashSet<>());
            put16(tiff, exifIfdOffset, exifCount + 1, order);

            byte[] newTiff = new byte[tiffLength + 12];
            System.arraycopy(tiff, 0, newTiff, 0, insertPoint);
            writeLegendEntry(newTiff, insertPoint, legendMode, order);
            System.arraycopy(tiff, insertPoint, newTiff, insertPoint + 12, tiffLength - insertPoint);
            return replaceApp1Tiff(jpeg, exifOffset, exifLength, newExifLength, tiffOffset, newTiff);
        }

        // Fallback: no ExifIFD present — insert 0x8769 into IFD0 in-place and append 1-entry ExifIFD.
        int insertPoint = nextPointer;
        int exifIfdBlockSize = 2 + 12 + 4;
        int newExifLength = exifLength + 12 + exifIfdBlockSize;
        if (newExifLength > 0xFFFF) {
            Log.w(TAG, "EXIF segment would overflow");
            return jpeg;
        }
        shiftOffsetsFrom(tiff, ifd0Offset, insertPoint, 12, order, new java.util.HashSet<>());
        put16(tiff, ifd0Offset, entryCount + 1, order);
        int newExifIfdOffset = tiffLength + 12;

        byte[] newTiff = new byte[tiffLength + 12 + exifIfdBlockSize];
        System.arraycopy(tiff, 0, newTiff, 0, insertPoint);
        writeExifPtrEntry(newTiff, insertPoint, newExifIfdOffset, order);
        System.arraycopy(tiff, insertPoint, newTiff, insertPoint + 12, tiffLength - insertPoint);
        put16(newTiff, newExifIfdOffset, 1, order);
        writeLegendEntry(newTiff, newExifIfdOffset + 2, legendMode, order);
        put32(newTiff, newExifIfdOffset + 14, 0, order);
        return replaceApp1Tiff(jpeg, exifOffset, exifLength, newExifLength, tiffOffset, newTiff);
    }

    private static void shiftOffsetsFrom(byte[] tiff, int ifdOffset, int insertPoint, int delta,
                                         ByteOrder order, java.util.Set<Integer> visited) {
        if (ifdOffset <= 0 || ifdOffset + 2 > tiff.length || !visited.add(ifdOffset)) {
            return;
        }
        int count = read16(tiff, ifdOffset, order);
        int entriesStart = ifdOffset + 2;
        int nextPtr = entriesStart + count * 12;
        if (count < 0 || nextPtr + 4 > tiff.length) {
            return;
        }
        for (int n = 0; n < count; n++) {
            int entry = entriesStart + n * 12;
            int tag = read16(tiff, entry, order);
            int type = read16(tiff, entry + 2, order);
            int num = read32(tiff, entry + 4, order);
            int val = read32(tiff, entry + 8, order);
            if (tag == TAG_EXIF_IFD || tag == 0x8825 || tag == 0xA005) {
                shiftOffsetsFrom(tiff, val, insertPoint, delta, order, visited);
                if (val >= insertPoint) {
                    put32(tiff, entry + 8, val + delta, order);
                }
            } else {
                long byteLen = (long) tiffTypeSize(type) * (num & 0xFFFFFFFFL);
                if (byteLen > 4 || tag == 0x0201) {
                    if (val >= insertPoint) {
                        put32(tiff, entry + 8, val + delta, order);
                    }
                }
            }
        }
        int nextIfd = read32(tiff, nextPtr, order);
        if (nextIfd > 0) {
            shiftOffsetsFrom(tiff, nextIfd, insertPoint, delta, order, visited);
            if (nextIfd >= insertPoint) {
                put32(tiff, nextPtr, nextIfd + delta, order);
            }
        }
    }

    private static int tiffTypeSize(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            case 5, 10, 12 -> 8;
            default -> 1;
        };
    }

    private static void writeLegendEntry(byte[] dst, int offset, byte legendMode, ByteOrder order) {
        put16(dst, offset, TAG_LEGENDMODE, order);
        put16(dst, offset + 2, TYPE_BYTE, order);
        put32(dst, offset + 4, 1, order);
        dst[offset + 8] = legendMode;
        dst[offset + 9] = 0;
        dst[offset + 10] = 0;
        dst[offset + 11] = 0;
    }

    private static void writeExifPtrEntry(byte[] dst, int offset, int exifIfdOffset, ByteOrder order) {
        put16(dst, offset, TAG_EXIF_IFD, order);
        put16(dst, offset + 2, TYPE_LONG, order);
        put32(dst, offset + 4, 1, order);
        put32(dst, offset + 8, exifIfdOffset, order);
    }

    private static byte[] replaceApp1Tiff(byte[] jpeg, int exifOffset, int exifLength,
                                          int newExifLength, int tiffOffset, byte[] tiff) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + 24);
        out.write(jpeg, 0, exifOffset + 2);
        out.write((newExifLength >> 8) & 0xFF);
        out.write(newExifLength & 0xFF);
        out.write(jpeg, exifOffset + 4, tiffOffset - (exifOffset + 4));
        out.write(tiff, 0, tiff.length);
        int afterApp1 = exifOffset + 2 + exifLength;
        out.write(jpeg, afterApp1, jpeg.length - afterApp1);
        return out.toByteArray();
    }

    private static int read16(byte[] data, int offset, ByteOrder order) {
        if (offset < 0 || offset + 2 > data.length) {
            return 0;
        }
        return order == ByteOrder.LITTLE_ENDIAN
                ? (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                : ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int read32(byte[] data, int offset, ByteOrder order) {
        if (offset < 0 || offset + 4 > data.length) {
            return 0;
        }
        return order == ByteOrder.LITTLE_ENDIAN
                ? (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24)
                : ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static void put16(byte[] data, int offset, int value, ByteOrder order) {
        if (order == ByteOrder.LITTLE_ENDIAN) {
            data[offset] = (byte) value;
            data[offset + 1] = (byte) (value >> 8);
        } else {
            data[offset] = (byte) (value >> 8);
            data[offset + 1] = (byte) value;
        }
    }

    private static void put32(byte[] data, int offset, int value, ByteOrder order) {
        if (order == ByteOrder.LITTLE_ENDIAN) {
            data[offset] = (byte) value;
            data[offset + 1] = (byte) (value >> 8);
            data[offset + 2] = (byte) (value >> 16);
            data[offset + 3] = (byte) (value >> 24);
        } else {
            data[offset] = (byte) (value >> 24);
            data[offset + 1] = (byte) (value >> 16);
            data[offset + 2] = (byte) (value >> 8);
            data[offset + 3] = (byte) value;
        }
    }

    private static byte[] createExifSegment(byte[] jpeg, byte legendMode) {
        // Build a minimal EXIF segment with IFD0 -> 0x8769 -> ExifIFD containing 0x88B0.
        // TIFF header: II (little endian), 0x2A, IFD0 offset = 8
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write(0x49); tiff.write(0x49); // II (little endian)
        tiff.write(0x2A); tiff.write(0x00); // magic
        tiff.write(0x08); tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); // IFD0 offset = 8

        // IFD0: entry count = 1 (0x8769 ExifIFDPointer -> offset 26 = 8 + 2 + 12 + 4)
        tiff.write(0x01); tiff.write(0x00);
        tiff.write(0x69); tiff.write(0x87); // tag = 0x8769
        tiff.write(0x04); tiff.write(0x00); // type = LONG
        tiff.write(0x01); tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); // count = 1
        tiff.write(0x1A); tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); // offset = 26
        tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); // next IFD = 0

        // ExifIFD at offset 26: entry count = 1 (0x88B0 legendMode)
        tiff.write(0x01); tiff.write(0x00);
        tiff.write(0xB0); tiff.write(0x88); // tag = 0x88B0
        tiff.write(0x01); tiff.write(0x00); // type = BYTE
        tiff.write(0x01); tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); // count = 1
        tiff.write(legendMode); tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); // value
        tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); // next IFD = 0

        byte[] tiffBytes = tiff.toByteArray();

        // APP1 segment: FF E1, length, "Exif\0\0", TIFF data
        int segmentLength = 2 + 6 + tiffBytes.length; // 2 (length) + 6 ("Exif\0\0") + TIFF
        ByteArrayOutputStream app1 = new ByteArrayOutputStream(2 + segmentLength);
        app1.write(0xFF);
        app1.write(0xE1);
        app1.write((segmentLength >> 8) & 0xFF);
        app1.write(segmentLength & 0xFF);
        app1.write('E'); app1.write('x'); app1.write('i'); app1.write('f');
        app1.write(0); app1.write(0);
        app1.write(tiffBytes, 0, tiffBytes.length);

        // Insert APP1 after SOI (FF D8)
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + app1.size());
        out.write(jpeg, 0, 2); // FF D8
        out.write(app1.toByteArray(), 0, app1.size());
        out.write(jpeg, 2, jpeg.length - 2);

        return out.toByteArray();
    }

    /**
     * Writes the legendmode tag even when the value is 0 (Original).
     * injectLegendMode() treats 0 as "off", which is right for the LUT path
     * but not for Original Portrait.
     */
    public static byte[] setLegendMode(byte[] jpeg, int legendMode) {
        if (jpeg == null || jpeg.length < 4
                || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return jpeg;
        }
        try {
            byte[] withExif = injectLegendModeIntoExif(jpeg, (byte) legendMode);
            return injectLegendXmp(withExif, legendMode);
        } catch (Throwable t) {
            Log.w(TAG, "legendmode injection failed: " + t);
            return jpeg;
        }
    }

    /**
     * Injects Xiaomi's {@code http://ns.xiaomi.com/photos/1.0/container/} XMP directory entry
     * required by MediaEditor's {@code LegendaryActivity} ({@code LegendInfoExtraManager}) so
     * tapping the Gallery LEICA badge parses M3 ({@code Legend.MONOPAN}) and M9 ({@code Legend.M9})
     * cleanly instead of failing with "couldn't parse image".
     */
    private static byte[] injectLegendXmp(byte[] jpeg, int legendMode) {
        String legendName;
        if (legendMode == 1) {
            legendName = "Legend.M9";
        } else if (legendMode == 2) {
            legendName = "Legend.MONOPAN";
        } else {
            return jpeg;
        }

        int width = 0;
        int height = 0;
        int xmpOffset = -1;
        int xmpLength = -1;
        int insertAfterExif = 2;

        final byte[] xmpHeader = "http://ns.adobe.com/xap/1.0/\0".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII);

        int i = 2;
        while (i + 4 <= jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (length < 2 || i + length + 2 > jpeg.length) {
                break;
            }
            if ((marker == 0xC0 || marker == 0xC1 || marker == 0xC2) && length >= 7) {
                height = ((jpeg[i + 5] & 0xFF) << 8) | (jpeg[i + 6] & 0xFF);
                width = ((jpeg[i + 7] & 0xFF) << 8) | (jpeg[i + 8] & 0xFF);
            } else if (marker == 0xE1 && length >= 8
                    && jpeg[i + 4] == 'E' && jpeg[i + 5] == 'x'
                    && jpeg[i + 6] == 'i' && jpeg[i + 7] == 'f'
                    && jpeg[i + 8] == 0 && jpeg[i + 9] == 0) {
                insertAfterExif = i + length + 2;
            } else if (marker == 0xE1 && xmpOffset < 0 && length >= 2 + xmpHeader.length) {
                boolean match = true;
                for (int k = 0; k < xmpHeader.length; k++) {
                    if (jpeg[i + 4 + k] != xmpHeader[k]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    xmpOffset = i;
                    xmpLength = length;
                }
            }
            i += length + 2;
        }

        String descSnippet =
                "  <rdf:Description rdf:about=\"\"\n"
                        + "    xmlns:Directory=\"http://ns.xiaomi.com/photos/1.0/container/\"\n"
                        + "    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\">\n"
                        + "   <Directory:Directory>\n"
                        + "    <rdf:Seq>\n"
                        + "     <rdf:li rdf:parseType=\"Resource\">\n"
                        + "      <Directory:Item\n"
                        + "       Item:name=\"" + legendName + "\"\n"
                        + "       Item:width=\"" + width + "\"\n"
                        + "       Item:height=\"" + height + "\"\n"
                        + "       Item:Offset=\"0\"\n"
                        + "       Item:length=\"0\"\n"
                        + "       Item:Orient=\"0\"\n"
                        + "       Item:UseMainImage=\"1\"/>\n"
                        + "     </rdf:li>\n"
                        + "    </rdf:Seq>\n"
                        + "   </Directory:Directory>\n"
                        + "  </rdf:Description>\n";

        if (xmpOffset >= 0) {
            int payloadStart = xmpOffset + 4 + xmpHeader.length;
            int payloadLen = xmpLength - 2 - xmpHeader.length;
            if (payloadLen <= 0 || payloadStart + payloadLen > jpeg.length) {
                return jpeg;
            }
            String existing = new String(jpeg, payloadStart, payloadLen,
                    java.nio.charset.StandardCharsets.UTF_8);
            if (existing.contains("http://ns.xiaomi.com/photos/1.0/container/")) {
                return jpeg;
            }
            int rdfEnd = existing.lastIndexOf("</rdf:RDF>");
            if (rdfEnd < 0) {
                return jpeg;
            }
            byte[] newPayload = (existing.substring(0, rdfEnd) + descSnippet
                    + existing.substring(rdfEnd)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int newSegLen = 2 + xmpHeader.length + newPayload.length;
            if (newSegLen > 0xFFFF) {
                Log.w(TAG, "XMP segment would overflow");
                return jpeg;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + descSnippet.length());
            out.write(jpeg, 0, xmpOffset);
            out.write(0xFF);
            out.write(0xE1);
            out.write((newSegLen >> 8) & 0xFF);
            out.write(newSegLen & 0xFF);
            out.write(xmpHeader, 0, xmpHeader.length);
            out.write(newPayload, 0, newPayload.length);
            int afterXmp = xmpOffset + 2 + xmpLength;
            out.write(jpeg, afterXmp, jpeg.length - afterXmp);
            return out.toByteArray();
        }

        String fullXmp =
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">\n"
                        + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                        + descSnippet
                        + " </rdf:RDF>\n"
                        + "</x:xmpmeta>";
        byte[] payload = fullXmp.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int segLen = 2 + xmpHeader.length + payload.length;
        if (segLen > 0xFFFF) {
            return jpeg;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + segLen + 2);
        out.write(jpeg, 0, insertAfterExif);
        out.write(0xFF);
        out.write(0xE1);
        out.write((segLen >> 8) & 0xFF);
        out.write(segLen & 0xFF);
        out.write(xmpHeader, 0, xmpHeader.length);
        out.write(payload, 0, payload.length);
        out.write(jpeg, insertAfterExif, jpeg.length - insertAfterExif);
        return out.toByteArray();
    }



    /**
     * Legend tagging for Essential output: V1 byte + XMP container (older Gallery), MakerNote V2
     * (MediaEditor / CN Gallery, required for M10R) and, when enabled with
     * {@code setprop camera.feature.reedit 1}, Xiaomi re-edit data holding {@code clean}.
     */
    public static byte[] finalizeLegend(byte[] processed, byte[] clean, int legend) {
        byte[] out = injectMakerNoteV2(injectLegendMode(processed, legend), legend);
        if (ReEditWriter.enabled() && clean != null) {
            out = ReEditWriter.append(out, clean, 0, ReEditWriter.scriptFor(legend),
                    ReEditWriter.styleName(legend));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // MakerNote V2 (ExifIFD 0x927c) + re-edit tag (0x88A4)
    // ---------------------------------------------------------------------------------------

    public static final int TAG_MAKERNOTE = 0x927C;
    public static final int TAG_REEDIT = 0x88A4; // 34980

    /**
     * Writes Xiaomi MakerNote V2 ("Xiaomi\0\1MM", MomentVersion=0x00020000, MomentType=0,
     * MomentSubMode, MomentPro=0) as MediaEditor's LegendaryVersionResolver expects.
     *
     * @param subMode 1=M9, 2=M3, 3=M10R
     */
    public static byte[] injectMakerNoteV2(byte[] jpeg, int subMode) {
        if (subMode < 1 || subMode > 3 || !isJpeg(jpeg)) {
            return jpeg;
        }
        byte[] note = new byte[12 + 4 * 12];
        byte[] head = {'X', 'i', 'a', 'o', 'm', 'i', 0, 1, 'M', 'M', 0, 4};
        System.arraycopy(head, 0, note, 0, head.length);
        int[] tags = {0x30, 0x31, 0x32, 0x33};
        int[] vals = {0x00020000, 0, subMode, 0};
        for (int n = 0; n < 4; n++) {
            int o = 12 + n * 12;
            put16(note, o, tags[n], ByteOrder.BIG_ENDIAN);
            put16(note, o + 2, TYPE_LONG, ByteOrder.BIG_ENDIAN);
            put32(note, o + 4, 1, ByteOrder.BIG_ENDIAN);
            put32(note, o + 8, vals[n], ByteOrder.BIG_ENDIAN);
        }
        try {
            return upsertExifEntry(jpeg, TAG_MAKERNOTE, 7, note.length, null, note);
        } catch (Throwable t) {
            Log.w(TAG, "MakerNote V2 injection failed: " + t);
            return jpeg;
        }
    }

    /** Sets ExifIFD reedit (0x88A4, BYTE) = 1 so Gallery offers re-edit. */
    public static byte[] setReEditFlag(byte[] jpeg) {
        if (!isJpeg(jpeg)) {
            return jpeg;
        }
        try {
            return upsertExifEntry(jpeg, TAG_REEDIT, TYPE_BYTE, 1, new byte[] {1, 0, 0, 0}, null);
        } catch (Throwable t) {
            Log.w(TAG, "reedit flag failed: " + t);
            return jpeg;
        }
    }

    private static boolean isJpeg(byte[] j) {
        return j != null && j.length > 4 && (j[0] & 0xFF) == 0xFF && (j[1] & 0xFF) == 0xD8;
    }

    /**
     * Inserts or replaces an ExifIFD entry (kept sorted by tag). {@code inline} is the 4-byte
     * value field for small values; otherwise {@code ext} is appended to the TIFF block and
     * referenced by offset. Requires an existing Exif APP1 with an ExifIFD.
     */
    private static byte[] upsertExifEntry(byte[] jpeg, int tag, int type, int count,
                                          byte[] inline, byte[] ext) {
        int i = 2;
        int exifOffset = -1;
        int exifLength = 0;
        while (i + 4 <= jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (length < 2 || i + length + 2 > jpeg.length) {
                break;
            }
            if (marker == 0xE1 && length >= 10 && jpeg[i + 4] == 'E' && jpeg[i + 5] == 'x'
                    && jpeg[i + 6] == 'i' && jpeg[i + 7] == 'f' && jpeg[i + 8] == 0) {
                exifOffset = i;
                exifLength = length;
                break;
            }
            i += length + 2;
        }
        if (exifOffset < 0) {
            Log.w(TAG, "no Exif APP1 for tag " + Integer.toHexString(tag));
            return jpeg;
        }
        int tiffOffset = exifOffset + 10;
        int tiffLength = exifLength - 8;
        ByteOrder order = jpeg[tiffOffset] == 'M' ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        byte[] tiff = new byte[tiffLength];
        System.arraycopy(jpeg, tiffOffset, tiff, 0, tiffLength);
        int ifd0 = read32(tiff, 4, order);
        int c0 = read16(tiff, ifd0, order);
        int exifIfd = -1;
        for (int n = 0; n < c0; n++) {
            int e = ifd0 + 2 + n * 12;
            if (e + 12 <= tiffLength && read16(tiff, e, order) == TAG_EXIF_IFD) {
                exifIfd = read32(tiff, e + 8, order);
            }
        }
        if (exifIfd <= 0 || exifIfd + 2 > tiffLength) {
            Log.w(TAG, "no ExifIFD for tag " + Integer.toHexString(tag));
            return jpeg;
        }
        int cnt = read16(tiff, exifIfd, order);
        int start = exifIfd + 2;
        if (start + cnt * 12 + 4 > tiffLength) {
            return jpeg;
        }
        int existing = -1;
        int insertAt = start + cnt * 12;
        for (int n = 0; n < cnt; n++) {
            int e = start + n * 12;
            int t = read16(tiff, e, order);
            if (t == tag) {
                existing = e;
                break;
            }
            if (t > tag && insertAt == start + cnt * 12) {
                insertAt = e;
            }
        }
        int extLen = ext == null ? 0 : ext.length + (ext.length & 1);
        int delta = existing >= 0 ? 0 : 12;
        int newLength = tiffLength + delta + extLen;
        if (exifLength + delta + extLen > 0xFFFF) {
            Log.w(TAG, "EXIF segment would overflow");
            return jpeg;
        }
        byte[] out;
        int entryPos;
        if (existing >= 0) {
            out = new byte[newLength];
            System.arraycopy(tiff, 0, out, 0, tiffLength);
            entryPos = existing;
        } else {
            shiftOffsetsFrom(tiff, ifd0, insertAt, 12, order, new java.util.HashSet<>());
            put16(tiff, exifIfd, cnt + 1, order);
            out = new byte[newLength];
            System.arraycopy(tiff, 0, out, 0, insertAt);
            System.arraycopy(tiff, insertAt, out, insertAt + 12, tiffLength - insertAt);
            entryPos = insertAt;
        }
        put16(out, entryPos, tag, order);
        put16(out, entryPos + 2, type, order);
        put32(out, entryPos + 4, count, order);
        if (ext != null) {
            int extPos = tiffLength + delta;
            System.arraycopy(ext, 0, out, extPos, ext.length);
            put32(out, entryPos + 8, extPos, order);
        } else {
            System.arraycopy(inline, 0, out, entryPos + 8, 4);
        }
        return replaceApp1Tiff(jpeg, exifOffset, exifLength, exifLength + delta + extLen,
                tiffOffset, out);
    }

    /** Copy the source JPEG's EXIF APP1 segment into the processed JPEG. */
    public static void copyExifFromFile(File source, File processed) {
        try {
            byte[] exif = readExifSegment(source);
            if (exif == null) {
                return;
            }
            byte[] data = Files.readAllBytes(processed.toPath());
            if (data.length < 2 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
                return;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + exif.length);
            out.write(data, 0, 2);
            out.write(exif);
            out.write(data, 2, data.length - 2);
            File tmp = new File(processed.getParentFile(), processed.getName() + ".exif");
            Files.write(tmp.toPath(), out.toByteArray());
            if (!tmp.renameTo(processed)) {
                Files.move(tmp.toPath(), processed.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            Log.w(TAG, "copyExifFromFile failed: " + t);
        }
    }

    /** Set the legendmode tag in a JPEG file in place. */
    public static void setLegendModeInFile(File file, int mode) {
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            byte[] out = setLegendMode(data, mode);
            if (out != data) {
                Files.write(file.toPath(), out);
            }
        } catch (Throwable t) {
            Log.w(TAG, "setLegendModeInFile failed: " + t);
        }
    }

    private static byte[] readExifSegment(File source) {
        try (InputStream raw = new FileInputStream(source);
             BufferedInputStream in = new BufferedInputStream(raw)) {
            if (in.read() != 0xFF || in.read() != 0xD8) {
                return null;
            }
            while (true) {
                int b0 = in.read();
                int b1 = in.read();
                if (b0 != 0xFF || b1 < 0) {
                    return null;
                }
                int marker = b1 & 0xFF;
                if (marker == 0xDA || marker == 0xD9) {
                    return null;
                }
                int hi = in.read();
                int lo = in.read();
                if (hi < 0 || lo < 0) {
                    return null;
                }
                int length = (hi << 8) | lo;
                if (length < 2) {
                    return null;
                }
                byte[] segment = new byte[length + 2];
                segment[0] = (byte) 0xFF;
                segment[1] = (byte) marker;
                segment[2] = (byte) hi;
                segment[3] = (byte) lo;
                int read = 4;
                while (read < segment.length) {
                    int n = in.read(segment, read, segment.length - read);
                    if (n < 0) {
                        return null;
                    }
                    read += n;
                }
                if (marker == 0xE1 && length >= 8
                        && segment[4] == 'E' && segment[5] == 'x' && segment[6] == 'i'
                        && segment[7] == 'f' && segment[8] == 0 && segment[9] == 0) {
                    return segment;
                }
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
