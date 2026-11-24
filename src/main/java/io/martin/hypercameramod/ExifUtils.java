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
 */
public final class ExifUtils {

    private static final String TAG = "HCM-ExifUtils";

    /** EXIF tag id for legendmode, as read by Gallery. */
    public static final int TAG_LEGENDMODE = 34992; // 0x88B0

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
            return injectLegendModeIntoExif(jpeg, (byte) legendMode);
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
     * Appends a second IFD0 that carries the legend tag and points the TIFF header at it.
     *
     * <p>The original IFD0 and every data block keep their offsets, so MakerNotes, the
     * thumbnail IFD and vendor blocks stay valid. Rewriting IFD0 in place would shift all
     * following data by 12 bytes and break every absolute offset in the EXIF.
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
        int ifd0Abs = tiffOffset + ifd0Offset;
        if (ifd0Offset < 0 || ifd0Abs + 2 > jpeg.length) {
            Log.w(TAG, "IFD0 offset out of bounds");
            return jpeg;
        }
        int entryCount = read16(jpeg, ifd0Abs, order);
        int entriesStart = ifd0Abs + 2;
        int nextPointer = entriesStart + entryCount * 12;
        if (entryCount < 0 || nextPointer + 4 > jpeg.length) {
            Log.w(TAG, "IFD0 entry list out of bounds");
            return jpeg;
        }

        // Update the value in place when the tag is already present.
        for (int n = 0; n < entryCount; n++) {
            int entry = entriesStart + n * 12;
            if (read16(jpeg, entry, order) == TAG_LEGENDMODE) {
                int type = read16(jpeg, entry + 2, order);
                long count = read32(jpeg, entry + 4, order) & 0xFFFFFFFFL;
                if (type == TYPE_BYTE && count == 1) {
                    jpeg[entry + 8] = legendMode;
                }
                return jpeg;
            }
        }

        int newIfd0Size = 2 + (entryCount + 1) * 12 + 4;
        int newExifLength = exifLength + newIfd0Size;
        if (newExifLength > 0xFFFF || tiffLength + newIfd0Size > 0xFFFF) {
            Log.w(TAG, "EXIF segment would overflow");
            return jpeg;
        }
        int oldNext = read32(jpeg, nextPointer, order);
        int newIfdOffset = tiffLength;

        byte[] tiff = new byte[tiffLength + newIfd0Size];
        System.arraycopy(jpeg, tiffOffset, tiff, 0, tiffLength);
        put32(tiff, 4, newIfdOffset, order);

        int p = tiffLength;
        put16(tiff, p, entryCount + 1, order);
        p += 2;
        System.arraycopy(jpeg, entriesStart, tiff, p, entryCount * 12);
        p += entryCount * 12;
        put16(tiff, p, TAG_LEGENDMODE, order);
        p += 2;
        put16(tiff, p, TYPE_BYTE, order);
        p += 2;
        put32(tiff, p, 1, order);
        p += 4;
        tiff[p] = legendMode;
        tiff[p + 1] = 0;
        tiff[p + 2] = 0;
        tiff[p + 3] = 0;
        p += 4;
        put32(tiff, p, oldNext, order);

        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + 12);
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
        // Build a minimal EXIF segment with IFD0 containing the legendmode tag
        // TIFF header: II (little endian), 0x2A, IFD0 offset = 8
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write(0x49); tiff.write(0x49); // II (little endian)
        tiff.write(0x2A); tiff.write(0x00); // magic
        tiff.write(0x08); tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); // IFD0 offset = 8

        // IFD0: entry count = 1
        tiff.write(0x01); tiff.write(0x00);

        // Entry: tag=0x88B0, type=BYTE(1), count=1, value=legendMode
        tiff.write(0xB0); tiff.write(0x88); // tag
        tiff.write(0x01); tiff.write(0x00); // type = BYTE
        tiff.write(0x01); tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); // count = 1
        tiff.write(legendMode); tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); // value

        // Next IFD offset = 0
        tiff.write(0x00); tiff.write(0x00); tiff.write(0x00); tiff.write(0x00);

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
            return injectLegendModeIntoExif(jpeg, (byte) legendMode);
        } catch (Throwable t) {
            Log.w(TAG, "legendmode injection failed: " + t);
            return jpeg;
        }
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
