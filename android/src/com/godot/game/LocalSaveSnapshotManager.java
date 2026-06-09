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
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
		if (hasSaveFiles(getAccountRootDir())) {
			createSnapshot("before-restore", true, false);
		}
		File accountRoot = getAccountRootDir();
		File stagingRoot = new File(getSnapshotRootDir(), ".restore-staging-" + System.nanoTime());
		FileBrowserSupport.deleteRecursively(stagingRoot);
		FileBrowserSupport.ensureDirectory(stagingRoot);
		try {
			unzipSnapshot(snapshotFile, stagingRoot);
			FileBrowserSupport.deleteRecursively(accountRoot);
			FileBrowserSupport.ensureDirectory(accountRoot);
			copyDirectoryContents(stagingRoot, accountRoot);
			return findSnapshotById(snapshotFile.getName());
		} finally {
			FileBrowserSupport.deleteRecursively(stagingRoot);
			pruneSnapshots();
		}
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
			snapshots.add(readSnapshot(file));
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
		try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(snapshotFile)))) {
			writeMetadata(output, createdAtMs, normalizedReason, accountRoot);
			zipDirectoryRecursive(accountRoot, accountRoot, output);
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

	private Snapshot findSnapshotById(String id) {
		for (Snapshot snapshot : listSnapshots()) {
			if (snapshot.id.equals(id)) {
				return snapshot;
			}
		}
		return readSnapshot(new File(getSnapshotRootDir(), id));
	}

	private Snapshot readSnapshot(File file) {
		JSONObject metadata = readSnapshotMetadata(file);
		String id = file.getName();
		String reason = metadata.optString("reason", inferReason(id));
		long createdAtMs = metadata.optLong("created_at_ms", file.lastModified());
		String profileId = metadata.optString("profile_id", getProfileId());
		String accountRoot = metadata.optString("account_root", getAccountRootDir().getAbsolutePath());
		int fileCount = metadata.optInt("file_count", -1);
		return new Snapshot(id, reason, createdAtMs, file.length(), fileCount, profileId, accountRoot);
	}

	private JSONObject readSnapshotMetadata(File file) {
		if (file == null || !file.isFile()) {
			return new JSONObject();
		}
		try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)))) {
			ZipEntry entry;
			while ((entry = input.getNextEntry()) != null) {
				if (!entry.isDirectory() && METADATA_ENTRY.equals(entry.getName())) {
					ByteArrayOutputStream output = new ByteArrayOutputStream();
					copy(input, output);
					return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
				}
			}
		} catch (Exception ignored) {
		}
		return new JSONObject();
	}

	private void writeMetadata(ZipOutputStream output, long createdAtMs, String reason, File accountRoot) throws Exception {
		JSONObject root = new JSONObject();
		root.put("schema", 1);
		root.put("type", "sts2_local_save_snapshot");
		root.put("created_at_ms", createdAtMs);
		root.put("reason", reason);
		root.put("profile_id", getProfileId());
		root.put("account_root", accountRoot.getAbsolutePath());
		root.put("file_count", countFiles(accountRoot));
		root.put("retention_limit", getRetentionLimit());
		JSONArray paths = new JSONArray();
		collectRelativePaths(accountRoot, accountRoot, paths);
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

	private void unzipSnapshot(File snapshotFile, File targetDir) throws Exception {
		try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(snapshotFile)))) {
			ZipEntry entry;
			while ((entry = input.getNextEntry()) != null) {
				String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
				if (name.isEmpty() || METADATA_ENTRY.equals(name)) {
					continue;
				}
				File target = new File(targetDir, name);
				String rootPath = targetDir.getCanonicalPath();
				String targetPath = target.getCanonicalPath();
				if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
					throw new IOException("Snapshot entry escapes target directory: " + name);
				}
				if (entry.isDirectory()) {
					FileBrowserSupport.ensureDirectory(target);
					continue;
				}
				File parent = target.getParentFile();
				if (parent != null) {
					FileBrowserSupport.ensureDirectory(parent);
				}
				try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
					copy(input, output);
				}
			}
		}
	}

	private void copyDirectoryContents(File sourceDir, File targetDir) throws IOException {
		File[] children = sourceDir.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			FileBrowserSupport.copyEntryRecursively(child, new File(targetDir, child.getName()));
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
