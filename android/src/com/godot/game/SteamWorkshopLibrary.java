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
import java.util.Set;

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

	public static final class Scope {
		public final String key;
		public final String profileId;
		public final String modsMode;
		public final File rootDir;

		Scope(String key, String profileId, String modsMode, File rootDir) {
			this.key = TextUtils.isEmpty(key) ? "legacy" : key;
			this.profileId = profileId == null ? "" : profileId;
			this.modsMode = modsMode == null ? "" : modsMode;
			this.rootDir = rootDir;
		}
	}

	private static final class ScopeMatch {
		final Scope scope;
		final String relativePath;

		ScopeMatch(Scope scope, String relativePath) {
			this.scope = scope;
			this.relativePath = relativePath;
		}
	}

	public Scope getCurrentScope() {
		LaunchProfileManager manager = new LaunchProfileManager(context);
		LaunchProfileManager.LaunchProfile profile = manager.getSelectedProfile();
		if (profile != null && LaunchProfileManager.MODS_MODE_ISOLATED.equals(profile.modsMode)) {
			return new Scope("profile:" + profile.id, profile.id, LaunchProfileManager.MODS_MODE_ISOLATED, new File(profile.dir, "mods"));
		}
		return new Scope("global", "", LaunchProfileManager.MODS_MODE_GLOBAL, new File(context.getFilesDir(), "mods"));
	}

	public synchronized List<Entry> listEntries() {
		List<Entry> rawEntries = readEntries();
		List<Entry> entries = normalizeEntries(rawEntries);
		boolean normalizedChanged = rawEntries.size() != entries.size();
		if (!normalizedChanged) {
			for (int i = 0; i < rawEntries.size(); i++) {
				if (rawEntries.get(i) != entries.get(i)) {
					normalizedChanged = true;
					break;
				}
			}
		}
		List<Entry> migrated = migrateLegacyScopeMetadata(entries);
		if (normalizedChanged || migrated != entries) {
			try {
				writeEntries(migrated);
			} catch (Exception ignored) {
				// Keep the in-memory migration usable; the next refresh can retry persistence.
			}
			entries = migrated;
		}
		entries.sort(Comparator.comparingLong((Entry entry) -> entry.installedAtMs).reversed());
		return entries;
	}

	private List<Entry> migrateLegacyScopeMetadata(List<Entry> entries) {
		if (entries == null || entries.isEmpty()) {
			return entries == null ? new ArrayList<>() : entries;
		}
		List<Scope> knownScopes = knownScopes();
		List<Entry> migrated = new ArrayList<>(entries.size());
		boolean changed = false;
		for (Entry entry : entries) {
			if (entry == null) {
				continue;
			}
			if (!TextUtils.isEmpty(entry.scopeKey) && !TextUtils.isEmpty(entry.installedRelativePath)) {
				migrated.add(entry);
				continue;
			}
			ScopeMatch match = findScopeMatch(entry.installedRootPath, knownScopes);
			Entry updated = match == null
				? entry.withScope("legacy", "", "", "")
				: entry.withScope(match.scope.key, match.scope.profileId, match.scope.modsMode, match.relativePath);
			migrated.add(updated);
			changed |= !entry.scopeKey.equals(updated.scopeKey)
				|| !entry.profileId.equals(updated.profileId)
				|| !entry.modsMode.equals(updated.modsMode)
				|| !entry.installedRelativePath.equals(updated.installedRelativePath);
		}
		return changed ? migrated : entries;
	}

	private List<Scope> knownScopes() {
		List<Scope> scopes = new ArrayList<>();
		scopes.add(new Scope("global", "", LaunchProfileManager.MODS_MODE_GLOBAL, new File(context.getFilesDir(), "mods")));
		try {
			for (LaunchProfileManager.LaunchProfile profile : new LaunchProfileManager(context).listProfiles()) {
				if (profile != null && LaunchProfileManager.MODS_MODE_ISOLATED.equals(profile.modsMode)) {
					scopes.add(new Scope("profile:" + profile.id, profile.id, LaunchProfileManager.MODS_MODE_ISOLATED, new File(profile.dir, "mods")));
				}
			}
		} catch (Exception ignored) {
			// A partially initialized profile store must not prevent legacy records from loading.
		}
		return scopes;
	}

	private ScopeMatch findScopeMatch(String installedRootPath, List<Scope> scopes) {
		File installedRoot = firstInstalledRootPath(installedRootPath);
		if (installedRoot == null) {
			return null;
		}
		for (Scope scope : scopes) {
			if (scope == null || scope.rootDir == null || !isSameOrDescendant(installedRoot, scope.rootDir)) {
				continue;
			}
			return new ScopeMatch(scope, relativePath(scope.rootDir, installedRoot));
		}
		return null;
	}

	private static File firstInstalledRootPath(String installedRootPath) {
		if (TextUtils.isEmpty(installedRootPath)) {
			return null;
		}
		for (String line : installedRootPath.split("\\n")) {
			String trimmed = line == null ? "" : line.trim();
			if (!TextUtils.isEmpty(trimmed)) {
				return new File(trimmed);
			}
		}
		return null;
	}

	private static boolean isSameOrDescendant(File file, File root) {
		if (file == null || root == null) {
			return false;
		}
		try {
			String rootPath = root.getCanonicalPath();
			String filePath = file.getCanonicalPath();
			return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
		} catch (IOException ignored) {
			return false;
		}
	}

	private static String relativePath(File root, File file) {
		if (root == null || file == null) {
			return "";
		}
		try {
			String rootPath = root.getCanonicalPath();
			String filePath = file.getCanonicalPath();
			if (filePath.equals(rootPath)) {
				return "";
			}
			if (!filePath.startsWith(rootPath + File.separator)) {
				return "";
			}
			return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
		} catch (IOException ignored) {
			return "";
		}
	}


	public Entry recordInstall(SteamWorkshopCatalog.Item item, File installRoot, List<ExtraSettingsRepository.ModEntry> importedMods) throws Exception {
		return recordInstall(item, installRoot, importedMods, InstallContext.empty());
	}

	public Entry recordInstall(SteamWorkshopCatalog.Item item, File installRoot, List<ExtraSettingsRepository.ModEntry> importedMods, InstallContext installContext) throws Exception {
		InstallContext safeContext = installContext == null ? InstallContext.empty() : installContext;
		List<String> modIds = new ArrayList<>();
		if (importedMods != null) {
			for (ExtraSettingsRepository.ModEntry mod : importedMods) {
				if (!TextUtils.isEmpty(mod.modId) && !modIds.contains(mod.modId)) {
					modIds.add(mod.modId);
				}
			}
		}
		String installedRootPath = installRoot == null ? "" : installRoot.getAbsolutePath();
		Scope scope = getCurrentScope();
		String installedRelativePath = relativePath(scope.rootDir, installRoot);
		long now = System.currentTimeMillis();
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
			directorySize(installRoot),
			sha1InstalledRoot(installRoot),
			safeContext.workshopBranch,
			safeContext.branchMode,
			safeContext.payloadId,
			safeContext.payloadVersion,
			safeContext.payloadSts2DllSha256,
			safeContext.resolvedManifestId,
			safeContext.resolutionSource,
			safeContext.matchedBranchMin,
			safeContext.matchedBranchMax,
			safeContext.fallbackReason,
			scope.key,
			scope.profileId,
			scope.modsMode,
			installedRelativePath
		);
		// Hashing the payload must never hold the index lock. Re-read after hashing
		// so concurrent update checks/deletions are preserved when committing.
		synchronized (this) {
			List<Entry> entries = migrateLegacyScopeMetadata(normalizeEntries(readEntries()));
			Map<String, Entry> byId = new LinkedHashMap<>();
			for (Entry entry : entries) {
				if (!shouldDropSupersededEntry(entry, updated)) {
					byId.put(entry.key(), entry);
				}
			}
			byId.put(updated.key(), updated);
			writeEntries(new ArrayList<>(byId.values()));
		}
		return updated;
	}

	public synchronized UpdateSummary updateCheckResults(Map<String, SteamWorkshopCatalog.Item> details) throws Exception {
		long now = System.currentTimeMillis();
		List<Entry> entries = listEntries();
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
			boolean updateAvailable = entry.hasRemoteUpdate(remoteUpdatedAtMs);
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
		List<Entry> entries = listEntries();
		List<Entry> kept = new ArrayList<>();
		for (Entry entry : entries) {
			if (!publishedFileId.equals(entry.publishedFileId)) {
				kept.add(entry);
			}
		}
		writeEntries(kept);
	}

	public synchronized void removeEntry(Entry target) throws Exception {
		if (target == null) {
			return;
		}
		List<Entry> entries = listEntries();
		List<Entry> kept = new ArrayList<>();
		for (Entry entry : entries) {
			boolean sameRecord = target.key().equals(entry.key())
				|| (target.scopeKey.equals("legacy") && sameItemKey(target, entry));
			if (!sameRecord) {
				kept.add(entry);
			}
		}
		writeEntries(kept);
	}

	public synchronized void removeEntry(String publishedFileId, String workshopBranch) throws Exception {
		if (TextUtils.isEmpty(publishedFileId)) {
			return;
		}
		List<Entry> entries = listEntries();
		List<Entry> kept = new ArrayList<>();
		for (Entry entry : entries) {
			boolean removeEntry = sameItemKey(publishedFileId, workshopBranch, entry)
				|| (publishedFileId.equals(entry.publishedFileId) && (isLegacyEntry(entry) || isLegacyScopeEntry(entry)));
			if (!removeEntry) {
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
		File tempFile = new File(rootDir, INDEX_FILE_NAME + ".tmp");
		File backupFile = new File(rootDir, INDEX_FILE_NAME + ".bak");
		byte[] bytes = array.toString(2).getBytes(StandardCharsets.UTF_8);
		try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
			outputStream.write(bytes);
			outputStream.getFD().sync();
		}
		if (backupFile.exists() && !backupFile.delete()) {
			throw new IOException("Unable to replace Workshop index backup: " + backupFile.getAbsolutePath());
		}
		if (indexFile.isFile() && !indexFile.renameTo(backupFile)) {
			tempFile.delete();
			throw new IOException("Unable to stage Workshop index: " + indexFile.getAbsolutePath());
		}
		if (!tempFile.renameTo(indexFile)) {
			if (backupFile.isFile()) {
				backupFile.renameTo(indexFile);
			}
			tempFile.delete();
			throw new IOException("Unable to publish Workshop index: " + indexFile.getAbsolutePath());
		}
		if (backupFile.exists() && !backupFile.delete()) {
			// The new index is already durable; keep the backup for manual recovery.
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

	private static String sha1InstalledRoot(File installRoot) {
		if (installRoot == null || !installRoot.exists()) {
			return "";
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			digest.update(installRoot.getName().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			digest.update(sha1Tree(installRoot).getBytes(StandardCharsets.UTF_8));
			return toHex(digest.digest());
		} catch (Exception ignored) {
			return "";
		}
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


	private static String toHex(byte[] bytes) {

		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			builder.append(String.format(Locale.US, "%02x", b & 0xff));
		}
		return builder.toString();
	}

	private static String entryKey(String publishedFileId, String workshopBranch) {
		return sanitizeKeyPart(publishedFileId) + "@" + sanitizeKeyPart(normalizeBranch(workshopBranch));
	}

	private static String scopedEntryKey(String scopeKey, String publishedFileId, String workshopBranch) {
		return sanitizeKeyPart(scopeKey) + ":" + entryKey(publishedFileId, workshopBranch);
	}

	private static boolean sameItemKey(String publishedFileId, String workshopBranch, Entry entry) {
		return entry != null
			&& sanitizeKeyPart(publishedFileId).equals(sanitizeKeyPart(entry.publishedFileId))
			&& sanitizeKeyPart(normalizeBranch(workshopBranch)).equals(sanitizeKeyPart(entry.workshopBranch));
	}

	private static boolean sameItemKey(Entry left, Entry right) {
		return left != null && right != null && sameItemKey(left.publishedFileId, left.workshopBranch, right);
	}

	private static List<Entry> normalizeEntries(List<Entry> entries) {
		if (entries == null || entries.isEmpty()) {
			return new ArrayList<>();
		}
		Map<String, Entry> modernEntries = new LinkedHashMap<>();
		for (Entry entry : entries) {
			if (entry != null && !isLegacyEntry(entry) && !isLegacyScopeEntry(entry)) {
				modernEntries.put(entry.publishedFileId, entry);
			}
		}
		Set<String> idsWithModernEntry = modernEntries.keySet();
		Map<String, Entry> byKey = new LinkedHashMap<>();
		for (Entry entry : entries) {
			if (entry == null) {
				continue;
			}
			if (idsWithModernEntry.contains(entry.publishedFileId)
				&& (isLegacyEntry(entry) || (isLegacyScopeEntry(entry) && canDropLegacyScopeEntry(entry, modernEntries.get(entry.publishedFileId))))) {
				continue;
			}
			Entry previous = byKey.get(entry.key());
			if (previous == null || entry.installedAtMs >= previous.installedAtMs) {
				byKey.put(entry.key(), entry);
			}
		}
		return new ArrayList<>(byKey.values());
	}

	private static boolean canDropLegacyScopeEntry(Entry legacy, Entry modern) {
		File legacyRoot = firstInstalledRootPath(legacy == null ? "" : legacy.installedRootPath);
		if (legacyRoot == null || !legacyRoot.exists()) {
			return true;
		}
		File modernRoot = firstInstalledRootPath(modern == null ? "" : modern.installedRootPath);
		if (modernRoot == null) {
			return false;
		}
		try {
			return legacyRoot.getCanonicalFile().equals(modernRoot.getCanonicalFile());
		} catch (IOException ignored) {
			return legacyRoot.getAbsoluteFile().equals(modernRoot.getAbsoluteFile());
		}
	}

	private static boolean shouldDropSupersededEntry(Entry existing, Entry installed) {
		if (existing == null || installed == null) {
			return false;
		}
		if (existing.key().equals(installed.key())) {
			return true;
		}
		return existing.publishedFileId.equals(installed.publishedFileId)
			&& (isLegacyEntry(existing) || (isLegacyScopeEntry(existing) && canDropLegacyScopeEntry(existing, installed)));
	}

	private static boolean isLegacyEntry(Entry entry) {
		return entry != null && "legacy".equals(entry.branchMode);
	}

	private static boolean isLegacyScopeEntry(Entry entry) {
		return entry != null && "legacy".equals(entry.scopeKey) && TextUtils.isEmpty(entry.installedRelativePath);
	}

	private static String normalizeBranch(String workshopBranch) {
		String trimmed = workshopBranch == null ? "" : workshopBranch.trim();
		return trimmed.isEmpty() ? "public" : trimmed;
	}

	private static String sanitizeKeyPart(String value) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isEmpty()) {
			return "unknown";
		}
		return trimmed.replace('\\', '_').replace('/', '_').replace('@', '_');
	}

	public static final class InstallContext {
		public final String workshopBranch;
		public final String branchMode;
		public final String payloadId;
		public final String payloadVersion;
		public final String payloadSts2DllSha256;
		public final String resolvedManifestId;
		public final String resolutionSource;
		public final String matchedBranchMin;
		public final String matchedBranchMax;
		public final String fallbackReason;

		InstallContext(String workshopBranch, String branchMode, String payloadId, String payloadVersion, String payloadSts2DllSha256, String resolvedManifestId, String resolutionSource, String matchedBranchMin, String matchedBranchMax, String fallbackReason) {
			this.workshopBranch = normalizeBranch(workshopBranch);
			this.branchMode = branchMode == null ? "manual" : branchMode;
			this.payloadId = payloadId == null ? "" : payloadId;
			this.payloadVersion = payloadVersion == null ? "" : payloadVersion;
			this.payloadSts2DllSha256 = payloadSts2DllSha256 == null ? "" : payloadSts2DllSha256;
			this.resolvedManifestId = resolvedManifestId == null ? "" : resolvedManifestId;
			this.resolutionSource = resolutionSource == null ? "" : resolutionSource;
			this.matchedBranchMin = matchedBranchMin == null ? "" : matchedBranchMin;
			this.matchedBranchMax = matchedBranchMax == null ? "" : matchedBranchMax;
			this.fallbackReason = fallbackReason == null ? "" : fallbackReason;
		}

		static InstallContext empty() {
			return new InstallContext("public", "legacy", "", "", "", "", "", "", "", "");
		}

		public static InstallContext fromDownloadResult(SteamWorkshopDownloader.Result result) {
			if (result == null) {
				return empty();
			}
			return new InstallContext(
				result.getBranch(),
				"manual",
				"",
				"",
				"",
				result.getManifestId(),
				result.getResolutionSource(),
				result.getMatchedBranchMin(),
				result.getMatchedBranchMax(),
				result.getFallbackReason()
			);
		}
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
		public final String workshopBranch;
		public final String branchMode;
		public final String payloadId;
		public final String payloadVersion;
		public final String payloadSts2DllSha256;
		public final String resolvedManifestId;
		public final String resolutionSource;
		public final String matchedBranchMin;
		public final String matchedBranchMax;
		public final String fallbackReason;
		public final String scopeKey;
		public final String profileId;
		public final String modsMode;
		public final String installedRelativePath;

		Entry(String appId, String publishedFileId, String gameTitle, String title, String description, String previewUrl, long fileSizeBytes, long installedRemoteUpdatedAtMs, long installedAtMs, long lastCheckedAtMs, long remoteUpdatedAtMs, String updateStatus, String lastError, String installedRootPath, List<String> importedModIds, long installedBytes, String installedSha1, String workshopBranch, String branchMode, String payloadId, String payloadVersion, String payloadSts2DllSha256, String resolvedManifestId, String resolutionSource, String matchedBranchMin, String matchedBranchMax, String fallbackReason, String scopeKey, String profileId, String modsMode, String installedRelativePath) {
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
			this.workshopBranch = normalizeBranch(workshopBranch);
			this.branchMode = branchMode == null ? "" : branchMode;
			this.payloadId = payloadId == null ? "" : payloadId;
			this.payloadVersion = payloadVersion == null ? "" : payloadVersion;
			this.payloadSts2DllSha256 = payloadSts2DllSha256 == null ? "" : payloadSts2DllSha256;
			this.resolvedManifestId = resolvedManifestId == null ? "" : resolvedManifestId;
			this.resolutionSource = resolutionSource == null ? "" : resolutionSource;
			this.matchedBranchMin = matchedBranchMin == null ? "" : matchedBranchMin;
			this.matchedBranchMax = matchedBranchMax == null ? "" : matchedBranchMax;
			this.fallbackReason = fallbackReason == null ? "" : fallbackReason;
			this.scopeKey = TextUtils.isEmpty(scopeKey) ? "legacy" : scopeKey;
			this.profileId = profileId == null ? "" : profileId;
			this.modsMode = modsMode == null ? "" : modsMode;
			this.installedRelativePath = installedRelativePath == null ? "" : installedRelativePath;
		}

		String key() {
			return scopedEntryKey(scopeKey, publishedFileId, workshopBranch);
		}

		boolean hasRemoteUpdate(long remoteUpdatedAtMs) {
			// Local installation time is not comparable with Steam's content timestamps.
			return installedRemoteUpdatedAtMs > 0L && remoteUpdatedAtMs > installedRemoteUpdatedAtMs;
		}

		Entry withScope(String newScopeKey, String newProfileId, String newModsMode, String newRelativePath) {
			return new Entry(appId, publishedFileId, gameTitle, title, description, previewUrl, fileSizeBytes, installedRemoteUpdatedAtMs, installedAtMs, lastCheckedAtMs, remoteUpdatedAtMs, updateStatus, lastError, installedRootPath, importedModIds, installedBytes, installedSha1, workshopBranch, branchMode, payloadId, payloadVersion, payloadSts2DllSha256, resolvedManifestId, resolutionSource, matchedBranchMin, matchedBranchMax, fallbackReason, newScopeKey, newProfileId, newModsMode, newRelativePath);
		}

		Entry withRemoteDetail(SteamWorkshopCatalog.Item item, long checkedAtMs, long remoteUpdatedAtMs, String status, String error) {
			return new Entry(appId, publishedFileId, gameTitle, item.getTitle(), item.getDescription(), item.getPreviewUrl(), item.getFileSizeBytes(), installedRemoteUpdatedAtMs, installedAtMs, checkedAtMs, remoteUpdatedAtMs, status, error, installedRootPath, importedModIds, installedBytes, installedSha1, workshopBranch, branchMode, payloadId, payloadVersion, payloadSts2DllSha256, resolvedManifestId, resolutionSource, matchedBranchMin, matchedBranchMax, fallbackReason, scopeKey, profileId, modsMode, installedRelativePath);
		}

		Entry withCheckResult(long checkedAtMs, long remoteUpdatedAtMs, String status, String error) {
			return new Entry(appId, publishedFileId, gameTitle, title, description, previewUrl, fileSizeBytes, installedRemoteUpdatedAtMs, installedAtMs, checkedAtMs, remoteUpdatedAtMs, status, error, installedRootPath, importedModIds, installedBytes, installedSha1, workshopBranch, branchMode, payloadId, payloadVersion, payloadSts2DllSha256, resolvedManifestId, resolutionSource, matchedBranchMin, matchedBranchMax, fallbackReason, scopeKey, profileId, modsMode, installedRelativePath);
		}

		JSONObject toJson() throws Exception {
			JSONObject object = new JSONObject();
			object.put("schema_version", 2);
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
			object.put("workshop_branch", workshopBranch);
			object.put("branch_mode", branchMode);
			object.put("payload_id", payloadId);
			object.put("payload_version", payloadVersion);
			object.put("payload_sts2_dll_sha256", payloadSts2DllSha256);
			object.put("resolved_manifest_id", resolvedManifestId);
			object.put("resolution_source", resolutionSource);
			object.put("matched_branch_min", matchedBranchMin);
			object.put("matched_branch_max", matchedBranchMax);
			object.put("fallback_reason", fallbackReason);
			object.put("mods_scope", scopeKey);
			object.put("profile_id", profileId);
			object.put("mods_mode", modsMode);
			object.put("installed_relative_path", installedRelativePath);
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
				object.optString("installed_sha1", ""),
				object.optString("workshop_branch", "public"),
				object.optString("branch_mode", "legacy"),
				object.optString("payload_id", ""),
				object.optString("payload_version", ""),
				object.optString("payload_sts2_dll_sha256", ""),
				object.optString("resolved_manifest_id", ""),
				object.optString("resolution_source", ""),
				object.optString("matched_branch_min", ""),
				object.optString("matched_branch_max", ""),
				object.optString("fallback_reason", ""),
				object.optString("mods_scope", ""),
				object.optString("profile_id", ""),
				object.optString("mods_mode", ""),
				object.optString("installed_relative_path", "")
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
