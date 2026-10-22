package com.godot.game;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ArchiveFormatDetectorTest {
    @Test
    public void detectsCommonArchiveSignatures() {
        assertEquals(ArchiveFormatDetector.Format.ZIP,
                ArchiveFormatDetector.detect(bytes(0x50, 0x4b, 0x03, 0x04), "payload.bin"));
        assertEquals(ArchiveFormatDetector.Format.RAR4,
                ArchiveFormatDetector.detect(bytes(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00), "payload.bin"));
        assertEquals(ArchiveFormatDetector.Format.RAR5,
                ArchiveFormatDetector.detect(bytes(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00), "payload.bin"));
        assertEquals(ArchiveFormatDetector.Format.SEVEN_Z,
                ArchiveFormatDetector.detect(bytes(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c), "payload.bin"));
        assertEquals(ArchiveFormatDetector.Format.GZIP,
                ArchiveFormatDetector.detect(bytes(0x1f, 0x8b), "payload.bin"));
        assertEquals(ArchiveFormatDetector.Format.XZ,
                ArchiveFormatDetector.detect(bytes(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00), "payload.bin"));
    }

    @Test
    public void usesArchiveExtensionWhenHeaderIsShort() {
        assertEquals(ArchiveFormatDetector.Format.RAR4,
                ArchiveFormatDetector.detect(new byte[] {0x01}, 1, "mod.rar"));
        assertEquals(ArchiveFormatDetector.Format.SEVEN_Z,
                ArchiveFormatDetector.detect(new byte[] {0x01}, 1, "mod.7z"));
        assertTrue(ArchiveFormatDetector.hasArchiveExtension("mod.tar.zst"));
    }

    @Test
    public void rejectsUnknownFormat() {
        assertEquals(ArchiveFormatDetector.Format.UNKNOWN,
                ArchiveFormatDetector.detect(bytes(0x01, 0x02, 0x03, 0x04), "mod.dll"));
        assertTrue(!ArchiveFormatDetector.hasArchiveExtension("mod.dll"));
    }

    @Test
    public void normalizesSafeEntryNames() throws Exception {
        assertEquals("folder/file.dll", ArchiveExtractor.normalizeEntryName("folder\\file.dll"));
        assertEquals("folder/file.dll", ArchiveExtractor.normalizeEntryName("./folder/file.dll"));
    }

    @Test
    public void rejectsTraversalAndAbsoluteEntryNames() {
        assertThrowsIOException("../outside");
        assertThrowsIOException("/outside");
        assertThrowsIOException("C:\\\\outside");
    }

    private static void assertThrowsIOException(String name) {
        try {
            ArchiveExtractor.normalizeEntryName(name);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected archive path rejection: " + name);
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }
}
