package com.godot.game;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import me.zhanghai.android.libarchive.Archive;
import me.zhanghai.android.libarchive.ArchiveEntry;
import me.zhanghai.android.libarchive.ArchiveException;

/**
 * Extracts non-ZIP import archives through libarchive.
 *
 * <p>ZIP keeps the existing Java fast path. This class is deliberately limited to
 * regular files and directories: links, device nodes, encrypted entries and
 * path traversal are never materialized in an app-private import staging tree.</p>
 */
public final class ArchiveExtractor {
	public interface ProgressListener {
		void onProgress(long processedBytes, long totalBytes) throws IOException;
	}

	public interface Cancellation {
		void throwIfCancelled() throws IOException;
	}

	public static final class UnsupportedArchiveException extends IOException {
		public UnsupportedArchiveException(String message) {
			super(message);
		}

		public UnsupportedArchiveException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private static final int BUFFER_SIZE = 64 * 1024;
	private static final int MAX_ENTRY_COUNT = 200_000;
	private static final long MAX_ENTRY_BYTES = 8L * 1024L * 1024L * 1024L;
	private static final long MAX_TOTAL_BYTES = 16L * 1024L * 1024L * 1024L;
	private static final int MAX_ENTRY_NAME_LENGTH = 4096;

	private ArchiveExtractor() {
	}

	public static boolean isArchive(File source) throws IOException {
		return ArchiveFormatDetector.isArchive(source);
	}

	public static ArchiveFormatDetector.Format detectFormat(File source) throws IOException {
		return ArchiveFormatDetector.detect(source);
	}

	public static void extract(File source, File targetRoot, ProgressListener progress,
			Cancellation cancellation) throws IOException {
		if (source == null || !source.isFile()) {
			throw new IOException("Archive source file is missing.");
		}
		if (targetRoot == null) {
			throw new IOException("Archive extraction target is missing.");
		}
		ensureDirectory(targetRoot);
		String canonicalRoot = targetRoot.getCanonicalPath();
		long sourceLength = source.length();
		ArchiveFormatDetector.Format detectedFormat = ArchiveFormatDetector.detect(source);
		long archive = 0L;
		boolean opened = false;
		long extractedBytes = 0L;
		try {
			archive = Archive.readNew();
			Archive.setCharset(archive, StandardCharsets.UTF_8.name().getBytes(StandardCharsets.UTF_8));
			Archive.readSupportFilterAll(archive);
			Archive.readSupportFormatAll(archive);
			if (isSingleStreamCompression(detectedFormat)) {
				Archive.readSupportFormatRaw(archive);
			}
			Archive.readOpenFileName(archive, source.getCanonicalPath().getBytes(StandardCharsets.UTF_8), BUFFER_SIZE);
			opened = true;
			if (Archive.readHasEncryptedEntries(archive) > 0) {
				throw new UnsupportedArchiveException("Encrypted archives are not supported.");
			}

			ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
			byte[] transfer = new byte[BUFFER_SIZE];
			int entryCount = 0;
			long entry = Archive.readNextHeader(archive);
			while (entry != 0L) {
				checkCancelled(cancellation);
				entryCount++;
				if (entryCount > MAX_ENTRY_COUNT) {
					throw new UnsupportedArchiveException("Archive contains too many entries.");
				}
				if (ArchiveEntry.isEncrypted(entry)) {
					throw new UnsupportedArchiveException("Encrypted archive entries are not supported.");
				}
				if (ArchiveEntry.symlink(entry) != null || ArchiveEntry.hardlinkIsSet(entry)) {
					throw new UnsupportedArchiveException("Symbolic links and hard links are not supported.");
				}

				String rawName = getEntryName(entry);
				String entryName = normalizeEntryName(rawName);
				if (entryName.isEmpty() || shouldSkipEntry(entryName)) {
					Archive.readDataSkip(archive);
					entry = Archive.readNextHeader(archive);
					continue;
				}
				File outputFile = resolveEntry(targetRoot, canonicalRoot, entryName, rawName);
				int filetype = ArchiveEntry.filetype(entry) & ArchiveEntry.AE_IFMT;
				if (filetype == 0) {
					// Some archive formats omit an explicit file type for regular entries.
					filetype = ArchiveEntry.AE_IFREG;
				}
				if (filetype == ArchiveEntry.AE_IFDIR) {
					ensureDirectory(outputFile);
					Archive.readDataSkip(archive);
				} else if (filetype == ArchiveEntry.AE_IFREG) {
					long declaredSize = ArchiveEntry.sizeIsSet(entry) ? ArchiveEntry.size(entry) : -1L;
					if (declaredSize > MAX_ENTRY_BYTES || declaredSize > MAX_TOTAL_BYTES) {
						throw new UnsupportedArchiveException("Archive entry exceeds the extraction safety limit: " + rawName);
					}
					if (declaredSize >= 0 && extractedBytes > MAX_TOTAL_BYTES - declaredSize) {
						throw new UnsupportedArchiveException("Archive expands beyond the extraction safety limit.");
					}
					File parent = outputFile.getParentFile();
					if (parent != null) {
						ensureDirectory(parent);
					}
					try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(outputFile))) {
						while (true) {
							checkCancelled(cancellation);
							buffer.clear();
							Archive.readData(archive, buffer);
							int read = buffer.position();
							if (read <= 0) {
								break;
							}
							if (read > MAX_TOTAL_BYTES - extractedBytes) {
								throw new UnsupportedArchiveException("Archive expands beyond the extraction safety limit.");
							}
							buffer.flip();
							buffer.get(transfer, 0, read);
							output.write(transfer, 0, read);
							extractedBytes += read;
							reportProgress(archive, sourceLength, extractedBytes, progress);
						}
					}
				} else {
					throw new UnsupportedArchiveException("Archive contains a special file: " + rawName);
				}
				reportProgress(archive, sourceLength, extractedBytes, progress);
				entry = Archive.readNextHeader(archive);
			}
			if (progress != null) {
				progress.onProgress(sourceLength, sourceLength);
			}
		} catch (UnsupportedArchiveException exception) {
			throw exception;
		} catch (ArchiveException exception) {
			throw new UnsupportedArchiveException("Unable to read archive: " + safeMessage(exception), exception);
		} finally {
			if (opened) {
				try {
					Archive.readClose(archive);
				} catch (ArchiveException ignored) {
				}
			}
			if (archive != 0L) {
				try {
					Archive.readFree(archive);
				} catch (ArchiveException ignored) {
				}
			}
		}
	}

	static String normalizeEntryName(String rawName) throws IOException {
		if (rawName == null || rawName.isEmpty()) {
			return "";
		}
		if (rawName.indexOf('\0') >= 0 || rawName.length() > MAX_ENTRY_NAME_LENGTH) {
			throw new IOException("Blocked invalid archive entry: " + rawName);
		}
		String name = rawName.replace('\\', '/');
		if (name.startsWith("/") || name.startsWith("//")
				|| name.matches("^[A-Za-z]:($|/).*$")) {
			throw new IOException("Blocked absolute archive entry: " + rawName);
		}
		StringBuilder normalized = new StringBuilder(name.length());
		for (String part : name.split("/", -1)) {
			if (part.isEmpty() || ".".equals(part)) {
				continue;
			}
			if ("..".equals(part)) {
				throw new IOException("Blocked parent traversal archive entry: " + rawName);
			}
			if (normalized.length() > 0) {
				normalized.append('/');
			}
			normalized.append(part);
		}
		return normalized.toString();
	}

	private static File resolveEntry(File targetRoot, String canonicalRoot, String entryName,
			String rawName) throws IOException {
		File outputFile = new File(targetRoot, entryName);
		String outputPath = outputFile.getCanonicalPath();
		if (!outputPath.equals(canonicalRoot)
				&& !outputPath.startsWith(canonicalRoot + File.separator)) {
			throw new IOException("Blocked archive entry outside extraction root: " + rawName);
		}
		return outputFile;
	}

	private static String getEntryName(long entry) throws IOException {
		String utf8Name = ArchiveEntry.pathnameUtf8(entry);
		if (utf8Name != null) {
			return utf8Name;
		}
		byte[] name = ArchiveEntry.pathname(entry);
		if (name != null) {
			return new String(name, StandardCharsets.UTF_8);
		}
		throw new IOException("Archive entry has no pathname.");
	}

	private static boolean shouldSkipEntry(String entryName) {
		return "__MACOSX".equals(entryName) || entryName.startsWith("__MACOSX/")
				|| ".DS_Store".equals(entryName) || entryName.endsWith("/.DS_Store");
	}

	private static void reportProgress(long archive, long sourceLength, long extractedBytes,
			ProgressListener progress) throws IOException {
		if (progress == null) {
			return;
		}
		int filterCount = Archive.filterCount(archive);
		long processed = filterCount > 0 ? Archive.filterBytes(archive, filterCount - 1) : extractedBytes;
		if (processed < 0L) {
			processed = extractedBytes;
		}
		progress.onProgress(processed, sourceLength);
	}

	private static boolean isSingleStreamCompression(ArchiveFormatDetector.Format format) {
		return format == ArchiveFormatDetector.Format.GZIP
				|| format == ArchiveFormatDetector.Format.BZIP2
				|| format == ArchiveFormatDetector.Format.XZ
				|| format == ArchiveFormatDetector.Format.ZSTD
				|| format == ArchiveFormatDetector.Format.LZ4
				|| format == ArchiveFormatDetector.Format.LZIP;
	}

	private static void checkCancelled(Cancellation cancellation) throws IOException {
		if (cancellation != null) {
			cancellation.throwIfCancelled();
		}
	}

	private static void ensureDirectory(File directory) throws IOException {
		if (directory.exists()) {
			if (!directory.isDirectory()) {
				throw new IOException("Archive extraction path is not a directory: " + directory.getAbsolutePath());
			}
			return;
		}
		if (!directory.mkdirs() && !directory.isDirectory()) {
			throw new IOException("Unable to create archive extraction directory: " + directory.getAbsolutePath());
		}
	}

	private static String safeMessage(Exception exception) {
		String message = exception.getMessage();
		return message == null || message.isEmpty() ? exception.toString() : message;
	}
}
