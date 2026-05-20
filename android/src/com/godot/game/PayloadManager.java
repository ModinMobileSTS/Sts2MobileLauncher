package com.godot.game;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports a user-provided or bundled PC game zip into the app-private payload
 * directory used by the restructured Android launcher.
 *
 * <p>The imported game body lives under {@code getFilesDir()/game}; the APK and
 * compatibility launcher/MOD stay separate from the body and can be updated
 * without rewriting the original game archive.</p>
 */
public final class PayloadManager {
	public static final String GAME_DIR_NAME = "game";
	public static final String IMPORT_DIR_NAME = "payload_import";
	public static final String MANIFEST_FILE_NAME = ".payload_manifest.json";
	public static final String BUNDLED_PAYLOAD_ASSET = "payload/SlayTheSpire2.zip";
	public static final String PCK_FILE_NAME = "SlayTheSpire2.pck";
	public static final String RELEASE_INFO_FILE_NAME = "release_info.json";
	public static final String ASSEMBLY_DIR_NAME = "data_sts2_windows_x86_64";
	public static final String STS2_DLL_PATH = ASSEMBLY_DIR_NAME + "/sts2.dll";
	public static final String STS2_DEPS_PATH = ASSEMBLY_DIR_NAME + "/sts2.deps.json";
	public static final String STS2_RUNTIME_CONFIG_PATH = ASSEMBLY_DIR_NAME + "/sts2.runtimeconfig.json";

	private static final int BUFFER_SIZE = 1024 * 1024;
	private static final int PCK_MAGIC_G = 0x47;
	private static final int PCK_MAGIC_D = 0x44;
	private static final int PCK_MAGIC_P = 0x50;
	private static final int PCK_MAGIC_C = 0x43;

	private final Context context;

	public PayloadManager(Context context) {
		this.context = context.getApplicationContext();
	}

	public Status getStatus() {
		File gameDir = getGameDir();
		JSONObject manifest = readManifestQuietly(gameDir);
		try {
			if (!gameDir.isDirectory()) {
				throw new IOException("Payload directory is missing.");
			}
			if (manifest == null) {
				throw new IOException("Payload manifest is missing; import the game zip again.");
			}
			Validation validation = validateGameDir(gameDir);
			JSONObject releaseInfo = validation.releaseInfo;
			JSONObject source = manifest == null ? null : manifest.optJSONObject("source");
			return new Status(
				true,
				"ready",
				gameDir,
				releaseInfo.optString("version", ""),
				releaseInfo.optString("commit", ""),
				source == null ? "" : source.optString("kind", ""),
				source == null ? "" : source.optString("display_name", ""),
				source == null ? "" : source.optString("sha256", ""),
				validation.pckSize,
				validation.dllSize,
				validation.fileCount,
				validation.totalBytes,
				manifest
			);
		} catch (Exception exception) {
			String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
			return new Status(false, message, gameDir, "", "", "", "", "", 0, 0, 0, 0, manifest);
		}
	}

	public boolean hasReadyPayload() {
		return getStatus().ready;
	}

	public boolean hasBundledPayload() {
		try (InputStream ignored = context.getAssets().open(BUNDLED_PAYLOAD_ASSET)) {
			return true;
		} catch (IOException ignored) {
			return false;
		}
	}

	public Status importPayloadZip(Uri uri) throws Exception {
		ensureDirectory(getImportRootDir());
		String displayName = queryDisplayName(uri);
		if (displayName == null || displayName.trim().isEmpty()) {
			displayName = "selected-payload.zip";
		}
		long sourceSize = querySize(uri);
		File sourceZip = new File(getImportRootDir(), "source-" + UUID.randomUUID() + ".zip");
		String sha256;
		try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
			if (inputStream == null) {
				throw new IOException("Unable to open selected payload zip.");
			}
			sha256 = copyToFileWithSha256(inputStream, sourceZip);
		}
		if (sourceSize < 0) {
			sourceSize = sourceZip.length();
		}
		SourceInfo source = new SourceInfo("saf_zip", displayName, sourceSize, sha256);
		return installFromZip(sourceZip, source);
	}

	public Status extractBundledPayload() throws Exception {
		if (!hasBundledPayload()) {
			throw new IOException("Bundled payload asset is not present: " + BUNDLED_PAYLOAD_ASSET);
		}
		ensureDirectory(getImportRootDir());
		File sourceZip = new File(getImportRootDir(), "bundled-source-" + UUID.randomUUID() + ".zip");
		String sha256;
		try (InputStream inputStream = context.getAssets().open(BUNDLED_PAYLOAD_ASSET)) {
			sha256 = copyToFileWithSha256(inputStream, sourceZip);
		}
		SourceInfo source = new SourceInfo("bundled_zip", "SlayTheSpire2.zip", sourceZip.length(), sha256);
		return installFromZip(sourceZip, source);
	}

	public void clearPayload() {
		deleteRecursively(getGameDir());
		deleteRecursively(getImportRootDir());
	}

	public File getGameDir() {
		return new File(context.getFilesDir(), GAME_DIR_NAME);
	}

	public File getManifestFile() {
		return new File(getGameDir(), MANIFEST_FILE_NAME);
	}

	private Status installFromZip(File sourceZip, SourceInfo source) throws Exception {
		File importRoot = getImportRootDir();
		ensureDirectory(importRoot);
		cleanupOldImportScratch(importRoot);

		File staging = new File(importRoot, "staging-" + UUID.randomUUID());
		File backup = new File(importRoot, "backup-" + UUID.randomUUID());
		File gameDir = getGameDir();
		boolean gameMovedToBackup = false;
		boolean installed = false;
		try {
			ensureDirectory(staging);
			extractZipSafely(sourceZip, staging);
			Validation validation = validateGameDir(staging);
			writeManifest(staging, source, validation);

			File gameParent = gameDir.getParentFile();
			if (gameParent != null) {
				ensureDirectory(gameParent);
			}
			if (gameDir.exists()) {
				if (!gameDir.renameTo(backup)) {
					throw new IOException("Unable to move existing payload aside: " + gameDir.getAbsolutePath());
				}
				gameMovedToBackup = true;
			}
			if (!staging.renameTo(gameDir)) {
				if (gameMovedToBackup && backup.exists()) {
					backup.renameTo(gameDir);
				}
				throw new IOException("Unable to install payload into: " + gameDir.getAbsolutePath());
			}
			installed = true;
			deleteRecursively(backup);
			return getStatus();
		} finally {
			if (!installed) {
				deleteRecursively(staging);
				if (gameMovedToBackup && backup.exists() && !gameDir.exists()) {
					backup.renameTo(gameDir);
				}
			}
			if (installed) {
				deleteRecursively(backup);
			}
			deleteIfExists(sourceZip);
		}
	}

	private void extractZipSafely(File zipFile, File targetRoot) throws Exception {
		String canonicalRoot = targetRoot.getCanonicalPath();
		try (ZipFile archive = new ZipFile(zipFile)) {
			Enumeration<? extends ZipEntry> entries = archive.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String entryName = normalizeEntryName(entry.getName());
				if (entryName.isEmpty() || shouldSkipEntry(entryName)) {
					continue;
				}
				if (isDangerousEntry(entryName)) {
					throw new IOException("Blocked invalid zip entry: " + entry.getName());
				}
				File outputFile = new File(targetRoot, entryName);
				String outputPath = outputFile.getCanonicalPath();
				if (!outputPath.equals(canonicalRoot) && !outputPath.startsWith(canonicalRoot + File.separator)) {
					throw new IOException("Blocked invalid zip entry: " + entry.getName());
				}
				if (entry.isDirectory()) {
					ensureDirectory(outputFile);
					continue;
				}
				File parent = outputFile.getParentFile();
				if (parent != null) {
					ensureDirectory(parent);
				}
				try (InputStream inputStream = new BufferedInputStream(archive.getInputStream(entry));
					 OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFile))) {
					copy(inputStream, outputStream);
				}
			}
		}
	}

	private Validation validateGameDir(File gameDir) throws Exception {
		if (!gameDir.isDirectory()) {
			throw new IOException("Payload directory is missing.");
		}
		File pck = new File(gameDir, PCK_FILE_NAME);
		File releaseInfoFile = new File(gameDir, RELEASE_INFO_FILE_NAME);
		File sts2Dll = new File(gameDir, STS2_DLL_PATH);
		File deps = new File(gameDir, STS2_DEPS_PATH);
		File runtimeConfig = new File(gameDir, STS2_RUNTIME_CONFIG_PATH);
		requireFile(pck, "Missing " + PCK_FILE_NAME);
		requireFile(releaseInfoFile, "Missing " + RELEASE_INFO_FILE_NAME);
		requireFile(sts2Dll, "Missing " + STS2_DLL_PATH);
		requireFile(deps, "Missing " + STS2_DEPS_PATH);
		requireFile(runtimeConfig, "Missing " + STS2_RUNTIME_CONFIG_PATH);
		validatePckMagic(pck);
		JSONObject releaseInfo = new JSONObject(readTextFile(releaseInfoFile));
		Counter counter = new Counter();
		countFiles(gameDir, counter);
		return new Validation(releaseInfo, pck.length(), sts2Dll.length(), counter.files, counter.bytes);
	}

	private void writeManifest(File gameDir, SourceInfo source, Validation validation) throws Exception {
		JSONObject root = new JSONObject();
		root.put("schema", 1);
		root.put("imported_at_unix", System.currentTimeMillis() / 1000L);

		JSONObject sourceJson = new JSONObject();
		sourceJson.put("kind", source.kind);
		sourceJson.put("display_name", source.displayName);
		sourceJson.put("size", source.size);
		sourceJson.put("sha256", source.sha256);
		root.put("source", sourceJson);

		JSONObject gameJson = new JSONObject();
		gameJson.put("pck_size", validation.pckSize);
		gameJson.put("release_info", validation.releaseInfo);
		gameJson.put("dll_size", validation.dllSize);
		gameJson.put("file_count", validation.fileCount);
		gameJson.put("total_uncompressed_bytes", validation.totalBytes);
		root.put("game", gameJson);

		JSONObject compatJson = new JSONObject();
		compatJson.put("required_port_mod_version", "0.1.0");
		compatJson.put("payload_layout", "pc_zip_flat_v1");
		root.put("compat", compatJson);

		writeTextFile(new File(gameDir, MANIFEST_FILE_NAME), root.toString(2));
	}

	private File getImportRootDir() {
		return new File(context.getFilesDir(), IMPORT_DIR_NAME);
	}

	private void cleanupOldImportScratch(File importRoot) {
		File[] children = importRoot.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			String name = child.getName();
			if (name.startsWith("staging-") || name.startsWith("backup-") || name.startsWith("source-") || name.startsWith("bundled-source-")) {
				deleteRecursively(child);
			}
		}
	}

	private JSONObject readManifestQuietly(File gameDir) {
		try {
			File manifest = new File(gameDir, MANIFEST_FILE_NAME);
			if (!manifest.isFile()) {
				return null;
			}
			return new JSONObject(readTextFile(manifest));
		} catch (Exception ignored) {
			return null;
		}
	}

	private String copyToFileWithSha256(InputStream inputStream, File destination) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		File parent = destination.getParentFile();
		if (parent != null) {
			ensureDirectory(parent);
		}
		try (DigestInputStream digestInputStream = new DigestInputStream(new BufferedInputStream(inputStream), digest);
			 OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(destination))) {
			copy(digestInputStream, outputStream);
		}
		return toHex(digest.digest());
	}

	private void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
		byte[] buffer = new byte[BUFFER_SIZE];
		int read;
		while ((read = inputStream.read(buffer)) != -1) {
			outputStream.write(buffer, 0, read);
		}
		outputStream.flush();
	}

	private void validatePckMagic(File pck) throws Exception {
		try (InputStream inputStream = new FileInputStream(pck)) {
			int a = inputStream.read();
			int b = inputStream.read();
			int c = inputStream.read();
			int d = inputStream.read();
			if (a != PCK_MAGIC_G || b != PCK_MAGIC_D || c != PCK_MAGIC_P || d != PCK_MAGIC_C) {
				throw new IOException("Invalid " + PCK_FILE_NAME + " magic; expected GDPC.");
			}
		}
	}

	private void requireFile(File file, String message) throws IOException {
		if (!file.isFile() || file.length() <= 0) {
			throw new IOException(message);
		}
	}

	private void countFiles(File file, Counter counter) {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					countFiles(child, counter);
				}
			}
			return;
		}
		counter.files++;
		counter.bytes += file.length();
	}

	private String normalizeEntryName(String name) {
		if (name == null) {
			return "";
		}
		String normalized = name.replace('\\', '/').trim();
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		return normalized;
	}

	private boolean shouldSkipEntry(String entryName) {
		return entryName.startsWith("__MACOSX/")
			|| entryName.endsWith("/.DS_Store")
			|| entryName.equals(".DS_Store");
	}

	private boolean isDangerousEntry(String entryName) {
		return entryName.equals("..")
			|| entryName.startsWith("../")
			|| entryName.contains("/../")
			|| entryName.endsWith("/..")
			|| entryName.contains(":");
	}

	private String queryDisplayName(Uri uri) {
		Cursor cursor = null;
		try {
			cursor = context.getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
			if (cursor != null && cursor.moveToFirst()) {
				int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (index >= 0) {
					return cursor.getString(index);
				}
			}
		} catch (Exception ignored) {
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
		return null;
	}

	private long querySize(Uri uri) {
		Cursor cursor = null;
		try {
			cursor = context.getContentResolver().query(uri, new String[] { OpenableColumns.SIZE }, null, null, null);
			if (cursor != null && cursor.moveToFirst()) {
				int index = cursor.getColumnIndex(OpenableColumns.SIZE);
				if (index >= 0 && !cursor.isNull(index)) {
					return cursor.getLong(index);
				}
			}
		} catch (Exception ignored) {
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
		return -1L;
	}

	private String readTextFile(File file) throws IOException {
		try (InputStream inputStream = new FileInputStream(file);
			 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			copy(inputStream, outputStream);
			return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private void writeTextFile(File file, String value) throws IOException {
		File parent = file.getParentFile();
		if (parent != null) {
			ensureDirectory(parent);
		}
		try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(file))) {
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			outputStream.write(bytes, 0, bytes.length);
		}
	}

	private String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			builder.append(String.format(Locale.US, "%02x", value & 0xff));
		}
		return builder.toString();
	}

	private void ensureDirectory(File directory) {
		if (directory.isDirectory()) {
			return;
		}
		if (!directory.mkdirs() && !directory.isDirectory()) {
			throw new RuntimeException("Unable to create directory: " + directory.getAbsolutePath());
		}
	}

	private void deleteIfExists(File file) {
		if (file != null && file.exists() && !file.delete()) {
			throw new RuntimeException("Unable to delete: " + file.getAbsolutePath());
		}
	}

	private void deleteRecursively(File file) {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}
		deleteIfExists(file);
	}

	public static final class Status {
		public final boolean ready;
		public final String message;
		public final File gameDir;
		public final String version;
		public final String commit;
		public final String sourceKind;
		public final String sourceName;
		public final String sourceSha256;
		public final long pckSize;
		public final long dllSize;
		public final int fileCount;
		public final long totalBytes;
		public final JSONObject manifest;

		Status(boolean ready, String message, File gameDir, String version, String commit, String sourceKind, String sourceName, String sourceSha256, long pckSize, long dllSize, int fileCount, long totalBytes, JSONObject manifest) {
			this.ready = ready;
			this.message = message == null ? "" : message;
			this.gameDir = gameDir;
			this.version = version == null ? "" : version;
			this.commit = commit == null ? "" : commit;
			this.sourceKind = sourceKind == null ? "" : sourceKind;
			this.sourceName = sourceName == null ? "" : sourceName;
			this.sourceSha256 = sourceSha256 == null ? "" : sourceSha256;
			this.pckSize = pckSize;
			this.dllSize = dllSize;
			this.fileCount = fileCount;
			this.totalBytes = totalBytes;
			this.manifest = manifest;
		}

		public String shortVersionLabel() {
			if (!TextUtils.isEmpty(version) && !TextUtils.isEmpty(commit)) {
				return version + " (" + commit + ")";
			}
			if (!TextUtils.isEmpty(version)) {
				return version;
			}
			if (!TextUtils.isEmpty(commit)) {
				return commit;
			}
			return "unknown";
		}
	}

	private static final class SourceInfo {
		final String kind;
		final String displayName;
		final long size;
		final String sha256;

		SourceInfo(String kind, String displayName, long size, String sha256) {
			this.kind = kind;
			this.displayName = displayName;
			this.size = size;
			this.sha256 = sha256;
		}
	}

	private static final class Validation {
		final JSONObject releaseInfo;
		final long pckSize;
		final long dllSize;
		final int fileCount;
		final long totalBytes;

		Validation(JSONObject releaseInfo, long pckSize, long dllSize, int fileCount, long totalBytes) {
			this.releaseInfo = releaseInfo;
			this.pckSize = pckSize;
			this.dllSize = dllSize;
			this.fileCount = fileCount;
			this.totalBytes = totalBytes;
		}
	}

	private static final class Counter {
		int files;
		long bytes;
	}
}
