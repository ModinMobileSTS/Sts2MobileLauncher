package com.godot.game;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

/** Detects common archive signatures without loading a native archive parser. */
public final class ArchiveFormatDetector {
	public enum Format {
		ZIP,
		RAR4,
		RAR5,
		SEVEN_Z,
		GZIP,
		BZIP2,
		XZ,
		ZSTD,
		LZ4,
		LZIP,
		TAR,
		CAB,
		ISO9660,
		CPIO,
		AR,
		LHA,
		XAR,
		UNKNOWN
	}

	private static final int MAX_SIGNATURE_BYTES = 512;

	private ArchiveFormatDetector() {
	}

    public static Format detect(File file) throws IOException {
        return detect(file, file == null ? null : file.getName());
    }

    public static Format detect(File file, String displayName) throws IOException {
        if (file == null || !file.isFile()) {
            return Format.UNKNOWN;
        }
        byte[] header = new byte[MAX_SIGNATURE_BYTES];
        int length = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (length < header.length) {
                int read = input.read(header, length, header.length - length);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                length += read;
            }
        }
        return detect(header, length, displayName);
    }

	static Format detect(byte[] header, String displayName) {
		return detect(header, header == null ? 0 : header.length, displayName);
	}

	static Format detect(byte[] header, int length, String displayName) {
		if (header == null || length <= 0) {
			return Format.UNKNOWN;
		}
		int available = Math.min(length, header.length);
		if (has(header, available, 0, 0x50, 0x4b, 0x03, 0x04)
				|| has(header, available, 0, 0x50, 0x4b, 0x05, 0x06)
				|| has(header, available, 0, 0x50, 0x4b, 0x07, 0x08)) {
			return Format.ZIP;
		}
		if (has(header, available, 0, 0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)) {
			return Format.RAR5;
		}
		if (has(header, available, 0, 0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)) {
			return Format.RAR4;
		}
		if (has(header, available, 0, 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c)) {
			return Format.SEVEN_Z;
		}
		if (has(header, available, 0, 0x1f, 0x8b)) {
			return Format.GZIP;
		}
		if (has(header, available, 0, 0x42, 0x5a, 0x68)) {
			return Format.BZIP2;
		}
		if (has(header, available, 0, 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00)) {
			return Format.XZ;
		}
		if (has(header, available, 0, 0x28, 0xb5, 0x2f, 0xfd)) {
			return Format.ZSTD;
		}
		if (has(header, available, 0, 0x04, 0x22, 0x4d, 0x18)) {
			return Format.LZ4;
		}
		if (has(header, available, 0, 0x4c, 0x5a, 0x49, 0x50)) {
			return Format.LZIP;
		}
		if (has(header, available, 0, 0x4d, 0x53, 0x43, 0x46)) {
			return Format.CAB;
		}
		if (hasAscii(header, available, 0, "!<arch>\n")) {
			return Format.AR;
		}
		if (hasAscii(header, available, 0, "070701")
				|| hasAscii(header, available, 0, "070702")
				|| hasAscii(header, available, 0, "070707")) {
			return Format.CPIO;
		}
		if (hasAscii(header, available, 257, "ustar")) {
			return Format.TAR;
		}
        if (hasArchiveExtension(displayName)) {
            String lower = displayName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".rar") || lower.matches(".*\\.r\\d{2,3}")) {
                return Format.RAR4;
            }
            if (lower.endsWith(".7z")) {
                return Format.SEVEN_Z;
            }
            if (lower.endsWith(".tar") || lower.endsWith(".tgz") || lower.endsWith(".tbz")
                    || lower.endsWith(".tbz2") || lower.endsWith(".txz") || lower.endsWith(".tzst")) {
                return Format.TAR;
            }
            if (lower.endsWith(".gz")) {
                return Format.GZIP;
            }
            if (lower.endsWith(".bz2")) {
                return Format.BZIP2;
            }
            if (lower.endsWith(".xz")) {
                return Format.XZ;
            }
            if (lower.endsWith(".zst")) {
                return Format.ZSTD;
            }
            if (lower.endsWith(".lz4")) {
                return Format.LZ4;
            }
            if (lower.endsWith(".lzip")) {
                return Format.LZIP;
            }
            if (lower.endsWith(".cab")) {
                return Format.CAB;
            }
            if (lower.endsWith(".iso")) {
                return Format.ISO9660;
            }
            if (lower.endsWith(".cpio")) {
                return Format.CPIO;
            }
            if (lower.endsWith(".ar")) {
                return Format.AR;
            }
            if (lower.endsWith(".lzh") || lower.endsWith(".lha")) {
                return Format.LHA;
            }
            if (lower.endsWith(".xar")) {
                return Format.XAR;
            }
        }
		return Format.UNKNOWN;
	}

	public static boolean isArchive(File file) throws IOException {
		return file != null && file.isFile()
				&& (detect(file) != Format.UNKNOWN || hasArchiveExtension(file.getName()));
	}

	static boolean hasArchiveExtension(String name) {
		if (name == null) {
			return false;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.endsWith(".zip") || lower.endsWith(".rar") || lower.matches(".*\\.r\\d{2,3}")
				|| lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".tar.gz")
				|| lower.endsWith(".tgz") || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz")
				|| lower.endsWith(".tbz2") || lower.endsWith(".tar.xz") || lower.endsWith(".txz")
				|| lower.endsWith(".tar.zst") || lower.endsWith(".tzst") || lower.endsWith(".tar.lz4")
				|| lower.endsWith(".gz") || lower.endsWith(".bz2") || lower.endsWith(".xz")
				|| lower.endsWith(".zst") || lower.endsWith(".lz4") || lower.endsWith(".lzip")
				|| lower.endsWith(".cab") || lower.endsWith(".iso") || lower.endsWith(".cpio")
				|| lower.endsWith(".ar") || lower.endsWith(".lzh") || lower.endsWith(".lha")
				|| lower.endsWith(".xar");
	}

	private static boolean has(byte[] bytes, int length, int offset, int... expected) {
		if (offset < 0 || offset + expected.length > length) {
			return false;
		}
		for (int i = 0; i < expected.length; i++) {
			if ((bytes[offset + i] & 0xff) != expected[i]) {
				return false;
			}
		}
		return true;
	}

	private static boolean hasAscii(byte[] bytes, int length, int offset, String expected) {
		if (offset < 0 || offset + expected.length() > length) {
			return false;
		}
		for (int i = 0; i < expected.length(); i++) {
			if ((byte) expected.charAt(i) != bytes[offset + i]) {
				return false;
			}
		}
		return true;
	}
}
