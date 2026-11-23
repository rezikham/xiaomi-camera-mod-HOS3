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

    private static byte[] injectIntoExifSegment(byte[] jpeg, int exifOffset, int exifLength,
                                                 byte legendMode) {
        // exifOffset points to FF E1 marker
        // exifLength includes the 2 length bytes
        // TIFF header starts at exifOffset + 4 (after "Exif\0\0")
        int tiffOffset = exifOffset + 10; // 4 (marker+length) + 6 ("Exif\0\0")

        if (tiffOffset + 8 > jpeg.length) {
            Log.w(TAG, "EXIF segment too small");
            return jpeg;
        }

        // Parse TIFF header
        ByteBuffer tiff = ByteBuffer.wrap(jpeg, tiffOffset, exifLength - 6);
        tiff.order(ByteOrder.LITTLE_ENDIAN);

        short byteOrder = tiff.getShort();
        if (byteOrder != 0x4949 && byteOrder != 0x4D4D) {
            Log.w(TAG, "unknown TIFF byte order: " + Integer.toHexString(byteOrder));
            return jpeg;
        }
        tiff.order(byteOrder == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

        short magic = tiff.getShort();
        if (magic != 0x2A) {
            Log.w(TAG, "not a TIFF file (magic=" + Integer.toHexString(magic) + ")");
            return jpeg;
        }

        int ifd0Offset = tiff.getInt() & 0xFFFFFFFF;
        int ifd0AbsOffset = tiffOffset + ifd0Offset;

        if (ifd0AbsOffset + 2 > jpeg.length) {
            Log.w(TAG, "IFD0 offset out of bounds");
            return jpeg;
        }

        // Parse IFD0
        ByteBuffer ifd0 = ByteBuffer.wrap(jpeg, ifd0AbsOffset, jpeg.length - ifd0AbsOffset);
        ifd0.order(tiff.order());

        short entryCount = ifd0.getShort();
        int entriesStart = ifd0AbsOffset + 2;
        int nextIfdOffset = entriesStart + entryCount * 12;

        // Check if tag already exists
        for (int n = 0; n < entryCount; n++) {
            int entryOffset = entriesStart + n * 12;
            if (entryOffset + 12 > jpeg.length) {
                break;
            }
            int tag = (jpeg[entryOffset] & 0xFF) | ((jpeg[entryOffset + 1] & 0xFF) << 8);
            if (tag == TAG_LEGENDMODE) {
                // Tag already exists - update value
                ByteBuffer entry = ByteBuffer.wrap(jpeg, entryOffset + 8, 4);
                entry.order(tiff.order());
                entry.put(legendMode);
                return jpeg;
            }
        }

        // Need to insert new entry. This requires shifting all subsequent data.
        // Simpler approach: rebuild IFD0 with one more entry.
        return rebuildIfd0(jpeg, exifOffset, exifLength, tiffOffset, tiff.order(),
                ifd0Offset, entryCount, entriesStart, nextIfdOffset, legendMode);
    }

    private static byte[] rebuildIfd0(byte[] jpeg, int exifOffset, int exifLength,
                                       int tiffOffset, ByteOrder tiffOrder,
                                       int ifd0Offset, short oldEntryCount,
                                       int entriesStart, int nextIfdOffset,
                                       byte legendMode) {
        // Read all existing IFD0 entries
        byte[][] entries = new byte[oldEntryCount][12];
        for (int n = 0; n < oldEntryCount; n++) {
            int entryOffset = entriesStart + n * 12;
            System.arraycopy(jpeg, entryOffset, entries[n], 0, 12);
        }

        // Create new entry for legendmode tag
        // Tag: 0x88B0, Type: BYTE(1), Count: 1, Value: legendMode (fits in 4 bytes)
        byte[] newEntry = new byte[12];
        newEntry[0] = (byte) (TAG_LEGENDMODE & 0xFF);
        newEntry[1] = (byte) ((TAG_LEGENDMODE >> 8) & 0xFF);
        newEntry[2] = TYPE_BYTE;
        newEntry[3] = 0;
        newEntry[4] = 1; // count
        newEntry[5] = 0;
        newEntry[6] = 0;
        newEntry[7] = 0;
        newEntry[8] = legendMode;
        newEntry[9] = 0;
        newEntry[10] = 0;
        newEntry[11] = 0;

        short newEntryCount = (short) (oldEntryCount + 1);
        int newIfd0Size = 2 + newEntryCount * 12 + 4; // count + entries + next IFD offset

        // Build new IFD0 section
        ByteArrayOutputStream newIfd0 = new ByteArrayOutputStream(newIfd0Size);
        newIfd0.write(newEntryCount & 0xFF);
        newIfd0.write((newEntryCount >> 8) & 0xFF);
        for (byte[] entry : entries) {
            newIfd0.write(entry, 0, 12);
        }
        newIfd0.write(newEntry, 0, 12);
        // Next IFD offset (0 = no more IFDs, keep it simple)
        newIfd0.write(0);
        newIfd0.write(0);
        newIfd0.write(0);
        newIfd0.write(0);

        byte[] newIfd0Bytes = newIfd0.toByteArray();

        // Calculate size difference
        int oldIfd0Size = 2 + oldEntryCount * 12 + 4;
        int sizeDiff = newIfd0Bytes.length - oldIfd0Size;

        // Build output JPEG
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + sizeDiff);

        // Copy everything up to IFD0 start
        out.write(jpeg, 0, entriesStart);

        // Write new IFD0
        out.write(newIfd0Bytes, 0, newIfd0Bytes.length);

        // Copy everything after old IFD0 (next IFD offset pointer and beyond)
        int afterOldIfd0 = entriesStart + oldIfd0Size;
        out.write(jpeg, afterOldIfd0, jpeg.length - afterOldIfd0);

        // Update IFD0 offset in TIFF header
        // The IFD0 offset is relative to TIFF header start
        // Since we inserted data before IFD0 but IFD0 is at entriesStart which is fixed
        // relative to TIFF header, the offset doesn't change.
        // Wait - we replaced IFD0 in place, so the offset is the same.
        // But we need to update the EXIF segment length in the JPEG marker
        int newExifLength = exifLength + sizeDiff;
        ByteArrayOutputStream finalOut = new ByteArrayOutputStream(out.size());
        finalOut.write(jpeg, 0, exifOffset + 2); // FF E1 + length bytes placeholder
        finalOut.write((newExifLength >> 8) & 0xFF);
        finalOut.write(newExifLength & 0xFF);
        finalOut.write(out.toByteArray(), exifOffset + 4, out.size() - (exifOffset + 4));

        return finalOut.toByteArray();
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
