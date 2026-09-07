package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class LocalSaveSnapshotManager {
	private static final String PREFS_NAME = "sts2_local_save_snapshots";
	private static final String KEY_RETENTION_LIMIT = "retention_limit";
	private static final int DEFAULT_RETENTION_LIMIT = 5;
	private static final String METADATA_ENTRY = "sts2_local_save_snapshot.json";

	private final Context context;
	private final LaunchProfileManager launchProfiles;

	public LocalSaveSnapshotManager(Context context) {
		this.context = context.getApplicationContext();
		this.launchProfiles = new LaunchProfileManager(this.context);
	}

	public Status getStatus() {
		List<Snapshot> snapshots = listSnapshots();
		return new Status(getProfileId(), getAccountRootDir(), getSnapshotRootDir(), getRetentionLimit(), snapshots);
	}

	public Snapshot createAutomaticSnapshot(String reason) throws Exception {
		return createSnapshot(reason, false);
	}

	public Snapshot createManualSnapshot() throws Exception {
		return createSnapshot("manual", true);
	}

	public Snapshot restoreSnapshot(String snapshotId) throws Exception {
		File snapshotFile = resolveSnapshotFile(snapshotId);
		if (!snapshotFile.isFile()) {
			throw new IOException("Local save snapshot is missing: " + snapshotId);
		}
		File accountRoot = getAccountRootDir();
		// The snapshot store shares app-private storage, but is not scanned as an account.
		File parent = getSnapshotRootDir();
		String suffix = Long.toString(System.nanoTime());
		File stagingRoot = new File(parent, ".snapshot-staging-" + suffix);
		File rollbackRoot = new File(parent, ".snapshot-rollback-" + suffix);
		FileBrowserSupport.ensureDirectory(stagingRoot);
		try {
			// Validate all entries and their CRCs before touching the current saves or retention.
			JSONObject metadata = unzipSnapshot(snapshotFile, stagingRoot);
			Snapshot snapshot = snapshotFromMetadata(snapshotFile, metadata);
			if (hasSaveFiles(accountRoot)) {
				createSnapshot("before-restore", true, false);
			}
			installRestoredSaves(stagingRoot, accountRoot, rollbackRoot);
			pruneSnapshots();
			return snapshot;
		} finally {
			FileBrowserSupport.deleteRecursively(stagingRoot);
		}
	}

	private static void installRestoredSaves(File stagingRoot, File accountRoot, File rollbackRoot) throws IOException {
			boolean hadAccount = accountRoot.exists();
			if (hadAccount && !accountRoot.renameTo(rollbackRoot)) {
				throw new IOException("Unable to preserve current saves: " + accountRoot);
			}
			if (!stagingRoot.renameTo(accountRoot)) {
				IOException failure = new IOException("Unable to install restored saves: " + accountRoot);
				if (hadAccount && !rollbackRoot.renameTo(accountRoot)) {
					failure.addSuppressed(new IOException("Original saves retained at: " + rollbackRoot));
				}
				throw failure;
			}
			FileBrowserSupport.deleteRecursively(rollbackRoot);
	}

	public List<Snapshot> listSnapshots() {
		List<Snapshot> snapshots = new ArrayList<>();
		File[] files = getSnapshotRootDir().listFiles();
		if (files == null) {
			return snapshots;
		}
		for (File file : files) {
			if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
				continue;
			}
			try {
				snapshots.add(readSnapshot(file));
			} catch (Exception ignored) {
				// Incomplete/invalid archives are not usable recovery points. Leave them for diagnosis.
			}
		}
		snapshots.sort((a, b) -> Long.compare(b.createdAtMs, a.createdAtMs));
		return snapshots;
	}

	public int getRetentionLimit() {
		int value = prefs().getInt(KEY_RETENTION_LIMIT, DEFAULT_RETENTION_LIMIT);
		return Math.max(1, Math.min(50, value));
	}

	public void setRetentionLimit(int limit) {
		prefs().edit().putInt(KEY_RETENTION_LIMIT, Math.max(1, Math.min(50, limit))).apply();
		pruneSnapshots();
	}

	private Snapshot createSnapshot(String reason, boolean requireFiles) throws Exception {
		return createSnapshot(reason, requireFiles, true);
	}

	private Snapshot createSnapshot(String reason, boolean requireFiles, boolean pruneAfterCreate) throws Exception {
		File accountRoot = getAccountRootDir();
		FileBrowserSupport.ensureDirectory(accountRoot);
		if (!hasSaveFiles(accountRoot)) {
			if (requireFiles) {
				throw new IOException(context.getString(R.string.local_save_snapshot_no_saves));
			}
			return null;
		}
		String normalizedReason = sanitizeReason(reason);
		long createdAtMs = System.currentTimeMillis();
		String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(createdAtMs));
		File snapshotFile = FileBrowserSupport.buildUniqueChild(getSnapshotRootDir(), timestamp + "-" + normalizedReason + ".zip");
		File temporary = File.createTempFile(".snapshot-", ".part", snapshotFile.getParentFile());
		try {
			try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
				writeMetadata(output, createdAtMs, normalizedReason, accountRoot);
				zipDirectoryRecursive(accountRoot, accountRoot, output);
			}
			readSnapshotMetadata(temporary);
			if (!temporary.renameTo(snapshotFile)) {
				throw new IOException("Unable to publish local save snapshot: " + snapshotFile);
			}
		} finally {
			FileBrowserSupport.deleteRecursively(temporary);
		}
		if (pruneAfterCreate) {
			pruneSnapshots();
		}
		return readSnapshot(snapshotFile);
	}

	private void pruneSnapshots() {
		List<Snapshot> snapshots = listSnapshots();
		int retention = getRetentionLimit();
		for (int i = retention; i < snapshots.size(); i++) {
			File file = new File(getSnapshotRootDir(), snapshots.get(i).id);
			if (file.isFile()) {
				file.delete();
			}
		}
	}


	private Snapshot readSnapshot(File file) throws Exception {
		return snapshotFromMetadata(file, readSnapshotMetadata(file));
	}

	private Snapshot snapshotFromMetadata(File file, JSONObject metadata) {
		String id = file.getName();
		String reason = metadata.optString("reason", inferReason(id));
		long createdAtMs = metadata.optLong("created_at_ms", file.lastModified());
		String profileId = metadata.optString("profile_id", getProfileId());
		String accountRoot = metadata.optString("account_root", getAccountRootDir().getAbsolutePath());
		int fileCount = metadata.optInt("file_count", -1);
		return new Snapshot(id, reason, createdAtMs, file.length(), fileCount, profileId, accountRoot);
	}

	private JSONObject readSnapshotMetadata(File file) throws Exception {
		try (ZipFile zip = new ZipFile(file)) {
			return validateSnapshot(zip);
		}
	}

	private JSONObject validateSnapshot(ZipFile zip) throws Exception {
		ZipEntry metadataEntry = zip.getEntry(METADATA_ENTRY);
		if (metadataEntry == null || metadataEntry.isDirectory()) {
			throw new IOException("Snapshot metadata is missing.");
		}
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		copySnapshotEntry(zip, metadataEntry, buffer);
		JSONObject metadata = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
		JSONArray paths = metadata.optJSONArray("paths");
		if (metadata.optInt("schema", -1) != 1 || !"sts2_local_save_snapshot".equals(metadata.optString("type"))
			|| paths == null || paths.length() == 0 || metadata.optInt("file_count", -1) != paths.length()) {
			throw new IOException("Invalid snapshot metadata or file count.");
		}
		Set<String> expected = new HashSet<>();
		for (int i = 0; i < paths.length(); i++) {
			String name = paths.getString(i);
			validateSnapshotPath(name);
			if (METADATA_ENTRY.equals(name) || !expected.add(name)) {
				throw new IOException("Duplicate snapshot path: " + name);
			}
		}
		Set<String> seen = new HashSet<>();
		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String name = entry.getName();
			validateSnapshotPath(entry.isDirectory() ? name.substring(0, name.length() - 1) : name);
			if (!seen.add(name)) {
				throw new IOException("Duplicate snapshot entry: " + name);
			}
			if (!entry.isDirectory() && !METADATA_ENTRY.equals(name)) {
				if (entry.getSize() < 0 || entry.getCrc() < 0 || !expected.remove(name)) {
					throw new IOException("Unexpected snapshot entry: " + name);
				}
			}
		}
		if (!expected.isEmpty()) {
			throw new IOException("Snapshot is missing save files: " + expected);
		}
		return metadata;
	}

	private static void validateSnapshotPath(String name) throws IOException {
		if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0) {
			throw new IOException("Invalid snapshot path: " + name);
		}
		for (String part : name.split("/", -1)) {
			if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
				throw new IOException("Invalid snapshot path: " + name);
			}
		}
	}

	private static void copySnapshotEntry(ZipFile zip, ZipEntry entry, OutputStream output) throws IOException {
		CRC32 crc = new CRC32();
		long size = 0;
		try (InputStream input = zip.getInputStream(entry)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) != -1) {
				output.write(buffer, 0, read);
				crc.update(buffer, 0, read);
				size += read;
			}
		}
		if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
			throw new IOException("Snapshot checksum mismatch: " + entry.getName());
		}
	}

	private void writeMetadata(ZipOutputStream output, long createdAtMs, String reason, File accountRoot) throws Exception {
		JSONObject root = new JSONObject();
		root.put("schema", 1);
		root.put("type", "sts2_local_save_snapshot");
		root.put("created_at_ms", createdAtMs);
		root.put("reason", reason);
		root.put("profile_id", getProfileId());
		root.put("account_root", accountRoot.getAbsolutePath());
		root.put("retention_limit", getRetentionLimit());
		JSONArray paths = new JSONArray();
		collectRelativePaths(accountRoot, accountRoot, paths);
		root.put("file_count", paths.length());
		root.put("paths", paths);
		output.putNextEntry(new ZipEntry(METADATA_ENTRY));
		output.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
		output.closeEntry();
	}

	private void zipDirectoryRecursive(File root, File file, ZipOutputStream output) throws Exception {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					zipDirectoryRecursive(root, child, output);
				}
			}
			return;
		}
		String relative = buildRelativePath(root, file);
		if (relative.isEmpty()) {
			return;
		}
		output.putNextEntry(new ZipEntry(relative));
		try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
			copy(input, output);
		}
		output.closeEntry();
	}

	private JSONObject unzipSnapshot(File snapshotFile, File targetDir) throws Exception {
		try (ZipFile zip = new ZipFile(snapshotFile)) {
			JSONObject metadata = validateSnapshot(zip);
			Enumeration<? extends ZipEntry> entries = zip.entries();
			String rootPath = targetDir.getCanonicalPath();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (METADATA_ENTRY.equals(name)) {
					continue;
				}
				File target = new File(targetDir, name);
				if (!target.getCanonicalPath().startsWith(rootPath + File.separator)) {
					throw new IOException("Snapshot entry escapes target directory: " + name);
				}
				if (entry.isDirectory()) {
					FileBrowserSupport.ensureDirectory(target);
					continue;
				}
				FileBrowserSupport.ensureDirectory(target.getParentFile());
				try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
					copySnapshotEntry(zip, entry, output);
				}
			}
			return metadata;
		}
	}


	private boolean hasSaveFiles(File root) {
		return countFiles(root) > 0;
	}

	private int countFiles(File root) {
		if (root == null || !root.exists()) {
			return 0;
		}
		if (root.isFile()) {
			return 1;
		}
		int count = 0;
		File[] children = root.listFiles();
		if (children != null) {
			for (File child : children) {
				count += countFiles(child);
			}
		}
		return count;
	}

	private void collectRelativePaths(File root, File file, JSONArray paths) throws Exception {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					collectRelativePaths(root, child, paths);
				}
			}
			return;
		}
		String relative = buildRelativePath(root, file);
		if (!relative.isEmpty()) {
			paths.put(relative);
		}
	}

	private File resolveSnapshotFile(String snapshotId) throws Exception {
		String normalized = snapshotId == null ? "" : snapshotId.trim();
		if (normalized.isEmpty() || normalized.contains("/") || normalized.contains("\\")) {
			throw new IOException("Invalid local save snapshot id: " + snapshotId);
		}
		File root = getSnapshotRootDir();
		File file = new File(root, normalized);
		String rootPath = root.getCanonicalPath();
		String filePath = file.getCanonicalPath();
		if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
			throw new IOException("Snapshot id escapes snapshot root: " + snapshotId);
		}
		return file;
	}

	private File getAccountRootDir() {
		return launchProfiles.getSelectedAccountRootDir();
	}

	private String getProfileId() {
		LaunchProfileManager.LaunchProfile profile = launchProfiles.getSelectedProfile();
		return profile == null ? "global" : profile.id;
	}

	private File getSnapshotRootDir() {
		File root = new File(new File(new File(context.getFilesDir(), "save-snapshots"), "profiles"), sanitizeId(getProfileId()));
		FileBrowserSupport.ensureDirectory(root);
		return root;
	}

	private SharedPreferences prefs() {
		return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	private static String buildRelativePath(File root, File file) throws Exception {
		String rootPath = root.getCanonicalPath();
		String filePath = file.getCanonicalPath();
		if (filePath.equals(rootPath)) {
			return "";
		}
		if (filePath.startsWith(rootPath + File.separator)) {
			return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
		}
		throw new IOException("File is outside root: " + file.getAbsolutePath());
	}

	private static void copy(InputStream input, OutputStream output) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = input.read(buffer)) != -1) {
			output.write(buffer, 0, read);
		}
	}

	private static String sanitizeId(String value) {
		String sanitized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
		while (sanitized.startsWith("-") || sanitized.startsWith(".")) {
			sanitized = sanitized.substring(1);
		}
		while (sanitized.endsWith("-") || sanitized.endsWith(".")) {
			sanitized = sanitized.substring(0, sanitized.length() - 1);
		}
		return sanitized.isEmpty() ? "global" : sanitized;
	}

	private static String sanitizeReason(String reason) {
		String sanitized = reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
		while (sanitized.startsWith("-") || sanitized.startsWith(".")) {
			sanitized = sanitized.substring(1);
		}
		while (sanitized.endsWith("-") || sanitized.endsWith(".")) {
			sanitized = sanitized.substring(0, sanitized.length() - 1);
		}
		return sanitized.isEmpty() ? "snapshot" : sanitized;
	}

	private static String inferReason(String id) {
		String value = id == null ? "" : id;
		if (value.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			value = value.substring(0, value.length() - 4);
		}
		int index = value.indexOf('-');
		return index < 0 || index + 1 >= value.length() ? "snapshot" : value.substring(index + 1);
	}

	public static final class Status {
		public final String profileId;
		public final File accountRoot;
		public final File snapshotRoot;
		public final int retentionLimit;
		public final List<Snapshot> snapshots;

		Status(String profileId, File accountRoot, File snapshotRoot, int retentionLimit, List<Snapshot> snapshots) {
			this.profileId = profileId == null ? "" : profileId;
			this.accountRoot = accountRoot;
			this.snapshotRoot = snapshotRoot;
			this.retentionLimit = retentionLimit;
			this.snapshots = snapshots == null ? new ArrayList<>() : new ArrayList<>(snapshots);
		}
	}

	public static final class Snapshot {
		public final String id;
		public final String reason;
		public final long createdAtMs;
		public final long sizeBytes;
		public final int fileCount;
		public final String profileId;
		public final String accountRoot;

		Snapshot(String id, String reason, long createdAtMs, long sizeBytes, int fileCount, String profileId, String accountRoot) {
			this.id = id == null ? "" : id;
			this.reason = reason == null ? "" : reason;
			this.createdAtMs = createdAtMs;
			this.sizeBytes = sizeBytes;
			this.fileCount = fileCount;
			this.profileId = profileId == null ? "" : profileId;
			this.accountRoot = accountRoot == null ? "" : accountRoot;
		}
	}
}
