package com.godot.game;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SteamWorkshopLibrary {
	private static final String INDEX_FILE_NAME = "index.json";
	private static final String APP_TITLE = "Slay the Spire 2";
	private final Context context;
	private final File rootDir;
	private final File indexFile;

	public SteamWorkshopLibrary(Context context) {
		this.context = context.getApplicationContext();
		this.rootDir = new File(new File(this.context.getFilesDir(), "workshop"), "library");
		this.indexFile = new File(rootDir, INDEX_FILE_NAME);
	}

	public synchronized List<Entry> listEntries() {
		List<Entry> entries = readEntries();
		entries.sort(Comparator.comparingLong((Entry entry) -> entry.installedAtMs).reversed());
		return entries;
	}

	public synchronized Entry recordInstall(SteamWorkshopCatalog.Item item, List<ExtraSettingsRepository.ModEntry> importedMods) throws Exception {
		ensureRoot();
		List<Entry> entries = readEntries();
		Map<String, Entry> byId = new LinkedHashMap<>();
		for (Entry entry : entries) {
			byId.put(entry.publishedFileId, entry);
		}
		List<String> modIds = new ArrayList<>();
		if (importedMods != null) {
			for (ExtraSettingsRepository.ModEntry mod : importedMods) {
				if (!TextUtils.isEmpty(mod.modId) && !modIds.contains(mod.modId)) {
					modIds.add(mod.modId);
				}
			}
		}
		String installedRootPath = joinedInstalledPaths(importedMods);
		long now = System.currentTimeMillis();
		Entry existing = byId.get(item.getPublishedFileId());
		Entry updated = new Entry(
			Integer.toString(item.getAppId()),
			item.getPublishedFileId(),
			APP_TITLE,
			item.getTitle(),
			item.getDescription(),
			item.getPreviewUrl(),
			item.getFileSizeBytes(),
			item.getTimeUpdatedEpochSeconds() * 1000L,
			now,
			now,
			item.getTimeUpdatedEpochSeconds() * 1000L,
			"current",
			"",
			installedRootPath,
			modIds,
			importedModsSize(importedMods),
			sha1ImportedMods(importedMods)
		);
		byId.put(updated.publishedFileId, updated);
		writeEntries(new ArrayList<>(byId.values()));
		return updated;
	}

	public synchronized UpdateSummary updateCheckResults(Map<String, SteamWorkshopCatalog.Item> details) throws Exception {
		long now = System.currentTimeMillis();
		List<Entry> entries = readEntries();
		int available = 0;
		int current = 0;
		int failed = 0;
		List<Entry> updated = new ArrayList<>();
		for (Entry entry : entries) {
			SteamWorkshopCatalog.Item detail = details.get(entry.publishedFileId);
			if (detail == null) {
				failed++;
				updated.add(entry.withCheckResult(now, entry.remoteUpdatedAtMs, "failed", context.getString(R.string.workshop_update_missing_detail)));
				continue;
			}
			long remoteUpdatedAtMs = Math.max(0L, detail.getTimeUpdatedEpochSeconds() * 1000L);
			boolean updateAvailable = remoteUpdatedAtMs > Math.max(entry.installedRemoteUpdatedAtMs, entry.installedAtMs);
			if (updateAvailable) {
				available++;
			} else {
				current++;
			}
			updated.add(entry.withRemoteDetail(detail, now, remoteUpdatedAtMs, updateAvailable ? "available" : "current", ""));
		}
		writeEntries(updated);
		return new UpdateSummary(available, current, failed, now);
	}

	public synchronized void clearEntries() throws Exception {
		writeEntries(Collections.emptyList());
	}

	public synchronized void removeEntry(String publishedFileId) throws Exception {
		if (TextUtils.isEmpty(publishedFileId)) {
			return;
		}
		List<Entry> entries = readEntries();
		List<Entry> kept = new ArrayList<>();
		for (Entry entry : entries) {
			if (!publishedFileId.equals(entry.publishedFileId)) {
				kept.add(entry);
			}
		}
		writeEntries(kept);
	}

	private List<Entry> readEntries() {
		if (!indexFile.isFile()) {
			return new ArrayList<>();
		}
		try {
			String content = new String(java.nio.file.Files.readAllBytes(indexFile.toPath()), StandardCharsets.UTF_8);
			JSONArray array = new JSONArray(content);
			List<Entry> entries = new ArrayList<>();
			for (int i = 0; i < array.length(); i++) {
				JSONObject object = array.optJSONObject(i);
				if (object != null) {
					entries.add(Entry.fromJson(object));
				}
			}
			return entries;
		} catch (Exception ignored) {
			return new ArrayList<>();
		}
	}

	private void writeEntries(List<Entry> entries) throws Exception {
		ensureRoot();
		JSONArray array = new JSONArray();
		for (Entry entry : entries) {
			array.put(entry.toJson());
		}
		try (FileOutputStream outputStream = new FileOutputStream(indexFile)) {
			outputStream.write(array.toString(2).getBytes(StandardCharsets.UTF_8));
		}
	}

	private void ensureRoot() throws IOException {
		if (!rootDir.isDirectory() && !rootDir.mkdirs() && !rootDir.isDirectory()) {
			throw new IOException("Unable to create workshop library directory: " + rootDir.getAbsolutePath());
		}
	}

	private static long directorySize(File file) {
		if (file == null || !file.exists()) {
			return 0L;
		}
		if (file.isFile()) {
			return file.length();
		}
		File[] children = file.listFiles();
		long total = 0L;
		if (children != null) {
			for (File child : children) {
				total += directorySize(child);
			}
		}
		return total;
	}

	private static String joinedInstalledPaths(List<ExtraSettingsRepository.ModEntry> importedMods) {
		if (importedMods == null || importedMods.isEmpty()) {
			return "";
		}
		List<String> paths = new ArrayList<>();
		for (ExtraSettingsRepository.ModEntry mod : importedMods) {
			File directory = installedDirectory(mod);
			if (directory == null) {
				continue;
			}
			String path = directory.getAbsolutePath();
			if (!TextUtils.isEmpty(path) && !paths.contains(path)) {
				paths.add(path);
			}
		}
		return TextUtils.join("\n", paths);
	}

	private static long importedModsSize(List<ExtraSettingsRepository.ModEntry> importedMods) {
		if (importedMods == null || importedMods.isEmpty()) {
			return 0L;
		}
		long total = 0L;
		List<String> seen = new ArrayList<>();
		for (ExtraSettingsRepository.ModEntry mod : importedMods) {
			File directory = installedDirectory(mod);
			if (directory == null) {
				continue;
			}
			String path = directory.getAbsolutePath();
			if (!seen.contains(path)) {
				seen.add(path);
				total += directorySize(directory);
			}
		}
		return total;
	}

	private static String sha1ImportedMods(List<ExtraSettingsRepository.ModEntry> importedMods) {
		if (importedMods == null || importedMods.isEmpty()) {
			return "";
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			List<File> roots = new ArrayList<>();
			List<String> seen = new ArrayList<>();
			for (ExtraSettingsRepository.ModEntry mod : importedMods) {
				File directory = installedDirectory(mod);
				if (directory == null || !directory.exists()) {
					continue;
				}
				String path = directory.getAbsolutePath();
				if (seen.contains(path)) {
					continue;
				}
				seen.add(path);
				roots.add(directory);
			}
			roots.sort(Comparator.comparing(File::getAbsolutePath, String::compareToIgnoreCase));
			for (File root : roots) {
				digest.update(root.getName().getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
				String tree = sha1Tree(root);
				digest.update(tree.getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
			}
			return toHex(digest.digest());
		} catch (Exception ignored) {
			return "";
		}
	}

	private static File installedDirectory(ExtraSettingsRepository.ModEntry mod) {
		if (mod == null || mod.manifestFile == null) {
			return null;
		}
		File parent = mod.manifestFile.getParentFile();
		return parent == null ? mod.manifestFile : parent;
	}

	private static String sha1Tree(File root) {
		if (root == null || !root.exists()) {
			return "";
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			List<File> files = new ArrayList<>();
			collectFiles(root, files);
			files.sort(Comparator.comparing(file -> relativePath(root, file), String::compareToIgnoreCase));
			for (File file : files) {
				digest.update(relativePath(root, file).getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
				try (FileInputStream inputStream = new FileInputStream(file)) {
					byte[] buffer = new byte[8192];
					int read;
					while ((read = inputStream.read(buffer)) != -1) {
						digest.update(buffer, 0, read);
					}
				}
			}
			return toHex(digest.digest());
		} catch (Exception ignored) {
			return "";
		}
	}

	private static void collectFiles(File file, List<File> files) {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isFile()) {
			files.add(file);
			return;
		}
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				collectFiles(child, files);
			}
		}
	}

	private static String relativePath(File root, File file) {
		String rootPath = root.getAbsolutePath();
		String path = file.getAbsolutePath();
		if (path.startsWith(rootPath)) {
			path = path.substring(rootPath.length());
		}
		while (path.startsWith(File.separator)) {
			path = path.substring(1);
		}
		return path.replace(File.separatorChar, '/');
	}

	private static String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			builder.append(String.format(Locale.US, "%02x", b & 0xff));
		}
		return builder.toString();
	}

	public static final class Entry {
		public final String appId;
		public final String publishedFileId;
		public final String gameTitle;
		public final String title;
		public final String description;
		public final String previewUrl;
		public final long fileSizeBytes;
		public final long installedRemoteUpdatedAtMs;
		public final long installedAtMs;
		public final long lastCheckedAtMs;
		public final long remoteUpdatedAtMs;
		public final String updateStatus;
		public final String lastError;
		public final String installedRootPath;
		public final List<String> importedModIds;
		public final long installedBytes;
		public final String installedSha1;

		Entry(String appId, String publishedFileId, String gameTitle, String title, String description, String previewUrl, long fileSizeBytes, long installedRemoteUpdatedAtMs, long installedAtMs, long lastCheckedAtMs, long remoteUpdatedAtMs, String updateStatus, String lastError, String installedRootPath, List<String> importedModIds, long installedBytes, String installedSha1) {
			this.appId = appId == null ? "" : appId;
			this.publishedFileId = publishedFileId == null ? "" : publishedFileId;
			this.gameTitle = gameTitle == null ? "" : gameTitle;
			this.title = title == null ? "" : title;
			this.description = description == null ? "" : description;
			this.previewUrl = previewUrl == null ? "" : previewUrl;
			this.fileSizeBytes = Math.max(0L, fileSizeBytes);
			this.installedRemoteUpdatedAtMs = Math.max(0L, installedRemoteUpdatedAtMs);
			this.installedAtMs = Math.max(0L, installedAtMs);
			this.lastCheckedAtMs = Math.max(0L, lastCheckedAtMs);
			this.remoteUpdatedAtMs = Math.max(0L, remoteUpdatedAtMs);
			this.updateStatus = updateStatus == null ? "" : updateStatus;
			this.lastError = lastError == null ? "" : lastError;
			this.installedRootPath = installedRootPath == null ? "" : installedRootPath;
			this.importedModIds = importedModIds == null ? new ArrayList<>() : new ArrayList<>(importedModIds);
			this.installedBytes = Math.max(0L, installedBytes);
			this.installedSha1 = installedSha1 == null ? "" : installedSha1;
		}

		Entry withRemoteDetail(SteamWorkshopCatalog.Item item, long checkedAtMs, long remoteUpdatedAtMs, String status, String error) {
			return new Entry(appId, publishedFileId, gameTitle, item.getTitle(), item.getDescription(), item.getPreviewUrl(), item.getFileSizeBytes(), installedRemoteUpdatedAtMs, installedAtMs, checkedAtMs, remoteUpdatedAtMs, status, error, installedRootPath, importedModIds, installedBytes, installedSha1);
		}

		Entry withCheckResult(long checkedAtMs, long remoteUpdatedAtMs, String status, String error) {
			return new Entry(appId, publishedFileId, gameTitle, title, description, previewUrl, fileSizeBytes, installedRemoteUpdatedAtMs, installedAtMs, checkedAtMs, remoteUpdatedAtMs, status, error, installedRootPath, importedModIds, installedBytes, installedSha1);
		}

		JSONObject toJson() throws Exception {
			JSONObject object = new JSONObject();
			object.put("app_id", appId);
			object.put("published_file_id", publishedFileId);
			object.put("game_title", gameTitle);
			object.put("title", title);
			object.put("description", description);
			object.put("preview_url", previewUrl);
			object.put("file_size_bytes", fileSizeBytes);
			object.put("installed_remote_updated_at_ms", installedRemoteUpdatedAtMs);
			object.put("installed_at_ms", installedAtMs);
			object.put("last_checked_at_ms", lastCheckedAtMs);
			object.put("remote_updated_at_ms", remoteUpdatedAtMs);
			object.put("update_status", updateStatus);
			object.put("last_error", lastError);
			object.put("installed_root_path", installedRootPath);
			object.put("installed_bytes", installedBytes);
			object.put("installed_sha1", installedSha1);
			JSONArray ids = new JSONArray();
			for (String id : importedModIds) {
				ids.put(id);
			}
			object.put("imported_mod_ids", ids);
			return object;
		}

		static Entry fromJson(JSONObject object) {
			List<String> modIds = new ArrayList<>();
			JSONArray ids = object.optJSONArray("imported_mod_ids");
			if (ids != null) {
				for (int i = 0; i < ids.length(); i++) {
					String id = ids.optString(i, "");
					if (!TextUtils.isEmpty(id)) {
						modIds.add(id);
					}
				}
			}
			return new Entry(
				object.optString("app_id", ""),
				object.optString("published_file_id", ""),
				object.optString("game_title", ""),
				object.optString("title", ""),
				object.optString("description", ""),
				object.optString("preview_url", ""),
				object.optLong("file_size_bytes", 0L),
				object.optLong("installed_remote_updated_at_ms", 0L),
				object.optLong("installed_at_ms", 0L),
				object.optLong("last_checked_at_ms", 0L),
				object.optLong("remote_updated_at_ms", 0L),
				object.optString("update_status", ""),
				object.optString("last_error", ""),
				object.optString("installed_root_path", ""),
				modIds,
				object.optLong("installed_bytes", 0L),
				object.optString("installed_sha1", "")
			);
		}
	}

	public static final class UpdateSummary {
		public final int availableCount;
		public final int currentCount;
		public final int failedCount;
		public final long checkedAtMs;

		UpdateSummary(int availableCount, int currentCount, int failedCount, long checkedAtMs) {
			this.availableCount = availableCount;
			this.currentCount = currentCount;
			this.failedCount = failedCount;
			this.checkedAtMs = checkedAtMs;
		}
	}
}
