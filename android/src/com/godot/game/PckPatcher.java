package com.godot.game;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/**
 * Length-preserving patches for app-private Godot PCK payload copies.
 *
 * <p>The PC payload declares the Sentry GDExtension/autoload in project metadata.
 * Android builds do not ship that extension, so Godot tries to parse the GDScript
 * autoload before the C# compatibility MOD can run and emits noisy parse errors.
 * This patcher mutates only the extracted private PCK copy, never the user's zip.</p>
 */
public final class PckPatcher {
	private static final String TAG = "Sts2Re";
	private static final int MAGIC = 0x43504447; // GDPC, little endian int.
	private static final int HEADER_SIZE = 104;
	private static final int PACK_REL_FILEBASE = 0x02;
	private static final byte[] PROJECT_BINARY_SEARCH = "autoload/SentryInit".getBytes(StandardCharsets.UTF_8);
	private static final byte[] PROJECT_BINARY_REPLACE = "disabled/SentryInit".getBytes(StandardCharsets.UTF_8);
	private static final byte[] PROJECT_GODOT_SEARCH = "SentryInit=\"*res://addons/sentry/SentryInit.gd\"".getBytes(StandardCharsets.UTF_8);
	private static final byte[] EXTENSION_LIST_SEARCH = "res://addons/sentry/sentry.gdextension".getBytes(StandardCharsets.UTF_8);

	private PckPatcher() {
	}

	public static PatchResult patchSentry(File pckFile) throws IOException {
		PatchResult result = new PatchResult();
		if (pckFile == null || !pckFile.isFile()) {
			throw new IOException("PCK file missing: " + pckFile);
		}
		try (RandomAccessFile raf = new RandomAccessFile(pckFile, "rw")) {
			if (raf.length() < HEADER_SIZE) {
				throw new IOException("PCK too small: " + pckFile.getAbsolutePath());
			}
			int magic = readIntLE(raf);
			if (magic != MAGIC) {
				throw new IOException("Invalid PCK magic while patching: " + pckFile.getAbsolutePath());
			}
			result.formatVersion = readIntLE(raf);
			result.godotMajor = readIntLE(raf);
			result.godotMinor = readIntLE(raf);
			result.godotPatch = readIntLE(raf);
			int flags = readIntLE(raf);
			long fileBase = readLongLE(raf);
			long dirBase = readLongLE(raf);
			if ((flags & PACK_REL_FILEBASE) == 0) {
				fileBase = 0;
			}
			if (dirBase <= 0 || dirBase >= raf.length()) {
				throw new IOException("Invalid PCK directory offset: " + dirBase);
			}
			raf.seek(dirBase);
			long fileCountOffset = raf.getFilePointer();
			long fileCountLong = Integer.toUnsignedLong(readIntLE(raf));
			if (fileCountLong <= 0 || fileCountLong > 1_000_000L) {
				throw new IOException("Invalid PCK file count at " + fileCountOffset + ": " + fileCountLong);
			}
			for (long i = 0; i < fileCountLong; i++) {
				long entryStart = raf.getFilePointer();
				int pathLength = readIntLE(raf);
				if (pathLength <= 0 || pathLength > 64 * 1024) {
					throw new IOException("Invalid PCK path length at entry " + i + ": " + pathLength);
				}
				byte[] pathBytes = new byte[pathLength];
				raf.readFully(pathBytes);
				String path = decodePaddedPath(pathBytes);
				long relativeOffset = readLongLE(raf);
				long size = readLongLE(raf);
				long md5Offset = raf.getFilePointer();
				byte[] md5 = new byte[16];
				raf.readFully(md5);
				int entryFlags = readIntLE(raf);
				if (size < 0 || fileBase + relativeOffset < 0 || fileBase + relativeOffset + size > raf.length()) {
					throw new IOException("Invalid PCK entry bounds for " + path);
				}
				if (entryFlags != 0 && (isProjectEntry(path) || isExtensionList(path))) {
					throw new IOException("Cannot patch compressed/encrypted PCK metadata entry: " + path + " flags=" + entryFlags);
				}
				long nextEntryOffset = raf.getFilePointer();
				if (isProjectBinary(path)) {
					result.seenProjectBinary = true;
					if (patchEntryBytes(raf, fileBase + relativeOffset, size, PROJECT_BINARY_SEARCH, PROJECT_BINARY_REPLACE, null)) {
						result.projectBinaryPatched = true;
						writeEntryMd5(raf, fileBase + relativeOffset, size, md5Offset);
					}
				} else if (isProjectGodot(path)) {
					result.seenProjectGodot = true;
					if (patchEntryBytes(raf, fileBase + relativeOffset, size, PROJECT_GODOT_SEARCH, null, (byte) ';')) {
						result.projectGodotPatched = true;
						writeEntryMd5(raf, fileBase + relativeOffset, size, md5Offset);
					}
				} else if (isExtensionList(path)) {
					result.seenExtensionList = true;
					if (patchEntryBytes(raf, fileBase + relativeOffset, size, EXTENSION_LIST_SEARCH, null, null)) {
						result.extensionListPatched = true;
						writeEntryMd5(raf, fileBase + relativeOffset, size, md5Offset);
					}
				}
				raf.seek(nextEntryOffset);
				if (raf.getFilePointer() < entryStart) {
					throw new IOException("PCK parser moved backwards at entry " + i);
				}
			}
		}
		if (result.changed()) {
			result.pckSha256AfterPatch = sha256(pckFile);
			Log.i(TAG, "Patched Sentry metadata in PCK: " + result.toJson());
		} else {
			Log.i(TAG, "PCK Sentry metadata already patched or absent: " + result.toJson());
		}
		return result;
	}

	private static boolean patchEntryBytes(RandomAccessFile raf, long offset, long size, byte[] search, byte[] replacement, Byte replaceFirstByteOnly) throws IOException {
		if (size > Integer.MAX_VALUE) {
			throw new IOException("PCK metadata entry too large to patch safely: " + size);
		}
		byte[] data = new byte[(int) size];
		raf.seek(offset);
		raf.readFully(data);
		int index = indexOf(data, search);
		if (index < 0) {
			return false;
		}
		if (replacement != null) {
			if (replacement.length != search.length) {
				throw new IOException("Replacement must be length preserving.");
			}
			System.arraycopy(replacement, 0, data, index, replacement.length);
		} else if (replaceFirstByteOnly != null) {
			data[index] = replaceFirstByteOnly;
		} else {
			Arrays.fill(data, index, index + search.length, (byte) ' ');
		}
		raf.seek(offset);
		raf.write(data);
		return true;
	}

	private static void writeEntryMd5(RandomAccessFile raf, long offset, long size, long md5Offset) throws IOException {
		byte[] md5 = md5(raf, offset, size);
		raf.seek(md5Offset);
		raf.write(md5);
	}

	private static byte[] md5(RandomAccessFile raf, long offset, long size) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			byte[] buffer = new byte[1024 * 1024];
			long remaining = size;
			raf.seek(offset);
			while (remaining > 0) {
				int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
				if (read < 0) {
					throw new IOException("Unexpected EOF while hashing PCK entry.");
				}
				digest.update(buffer, 0, read);
				remaining -= read;
			}
			return digest.digest();
		} catch (Exception exception) {
			if (exception instanceof IOException) {
				throw (IOException) exception;
			}
			throw new IOException("Unable to compute MD5", exception);
		}
	}

	private static String sha256(File file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (java.io.InputStream input = new java.io.BufferedInputStream(new java.io.FileInputStream(file))) {
				byte[] buffer = new byte[1024 * 1024];
				int read;
				while ((read = input.read(buffer)) >= 0) {
					digest.update(buffer, 0, read);
				}
			}
			return toHex(digest.digest());
		} catch (Exception exception) {
			if (exception instanceof IOException) {
				throw (IOException) exception;
			}
			throw new IOException("Unable to compute SHA-256", exception);
		}
	}

	private static int indexOf(byte[] data, byte[] search) {
		outer:
		for (int i = 0; i <= data.length - search.length; i++) {
			for (int j = 0; j < search.length; j++) {
				if (data[i + j] != search[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static boolean isProjectEntry(String path) {
		return isProjectBinary(path) || isProjectGodot(path);
	}

	private static boolean isProjectBinary(String path) {
		return "project.binary".equals(path) || "res://project.binary".equals(path);
	}

	private static boolean isProjectGodot(String path) {
		return "project.godot".equals(path) || "res://project.godot".equals(path);
	}

	private static boolean isExtensionList(String path) {
		return ".godot/extension_list.cfg".equals(path) || "res://.godot/extension_list.cfg".equals(path);
	}

	private static String decodePaddedPath(byte[] pathBytes) {
		int length = 0;
		while (length < pathBytes.length && pathBytes[length] != 0) {
			length++;
		}
		return new String(pathBytes, 0, length, StandardCharsets.UTF_8);
	}

	private static int readIntLE(RandomAccessFile raf) throws IOException {
		int b0 = raf.readUnsignedByte();
		int b1 = raf.readUnsignedByte();
		int b2 = raf.readUnsignedByte();
		int b3 = raf.readUnsignedByte();
		return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
	}

	private static long readLongLE(RandomAccessFile raf) throws IOException {
		long b0 = raf.readUnsignedByte();
		long b1 = raf.readUnsignedByte();
		long b2 = raf.readUnsignedByte();
		long b3 = raf.readUnsignedByte();
		long b4 = raf.readUnsignedByte();
		long b5 = raf.readUnsignedByte();
		long b6 = raf.readUnsignedByte();
		long b7 = raf.readUnsignedByte();
		return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) | (b4 << 32) | (b5 << 40) | (b6 << 48) | (b7 << 56);
	}

	private static String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			builder.append(String.format(Locale.US, "%02x", value & 0xff));
		}
		return builder.toString();
	}

	public static final class PatchResult {
		public int formatVersion;
		public int godotMajor;
		public int godotMinor;
		public int godotPatch;
		public boolean seenProjectBinary;
		public boolean seenProjectGodot;
		public boolean seenExtensionList;
		public boolean projectBinaryPatched;
		public boolean projectGodotPatched;
		public boolean extensionListPatched;
		public String pckSha256AfterPatch = "";

		public boolean changed() {
			return projectBinaryPatched || projectGodotPatched || extensionListPatched;
		}

		public JSONObject toJson() {
			JSONObject object = new JSONObject();
			try {
				object.put("schema", 1);
				object.put("sentry_autoload_disabled", seenProjectBinary || seenProjectGodot);
				object.put("seen_project_binary", seenProjectBinary);
				object.put("seen_project_godot", seenProjectGodot);
				object.put("seen_extension_list", seenExtensionList);
				object.put("project_binary_patched", projectBinaryPatched);
				object.put("project_godot_patched", projectGodotPatched);
				object.put("extension_list_patched", extensionListPatched);
				object.put("pck_sha256_after_patch", pckSha256AfterPatch);
				object.put("godot_version", godotMajor + "." + godotMinor + "." + godotPatch);
				object.put("format_version", formatVersion);
			} catch (Exception ignored) {
			}
			return object;
		}
	}
}
