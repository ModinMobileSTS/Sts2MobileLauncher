package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class CompatPackManager {
	public static final String COMPAT_PACKS_DIR_NAME = "compat-packs";
	public static final String COMPAT_ASSET_DIR = "compat_packs";
	public static final String MANIFEST_FILE_NAME = "compat_manifest.json";
	public static final String ENTRY_DLL = "STS2Mobile.dll";
	public static final String ENTRY_OVERLAY_PCK = "port_compat.pck";

	private static final String PREFS_NAME = "sts2_version_manager";
	private static final String KEY_SELECTED_COMPAT_PACK_ID = "selected_compat_pack_id";
	private static final int BUFFER_SIZE = 1024 * 1024;

	private final Context context;

	public CompatPackManager(Context context) {
		this.context = context.getApplicationContext();
	}

	public List<CompatPack> listInstalledPacks() {
		List<CompatPack> packs = new ArrayList<>();
		File root = getCompatPacksRootDir();
		File[] children = root.listFiles(File::isDirectory);
		if (children == null) {
			return packs;
		}
		for (File child : children) {
			try {
				CompatPack pack = readPack(child);
				if (pack != null) {
					packs.add(pack);
				}
			} catch (Exception ignored) {
			}
		}
		packs.sort(Comparator.comparing((CompatPack pack) -> pack.displayName, String.CASE_INSENSITIVE_ORDER));
		return packs;
	}

	public CompatPack getSelectedPack() {
		if (!isCompatPackEnabled()) {
			return null;
		}
		String id = getSelectedPackId();
		if (TextUtils.isEmpty(id)) {
			return null;
		}
		try {
			return readPack(new File(getCompatPacksRootDir(), id));
		} catch (Exception ignored) {
			return null;
		}
	}

	public String getSelectedPackId() {
		if (!isCompatPackEnabled()) {
			return "";
		}
		return prefs().getString(KEY_SELECTED_COMPAT_PACK_ID, "");
	}

	public boolean isCompatPackEnabled() {
		try {
			return new ExtraSettingsRepository(context).isAndroidCompatPackEnabled();
		} catch (Exception exception) {
			return true;
		}
	}

	public void selectPack(String packId) throws Exception {
		if (TextUtils.isEmpty(packId)) {
			prefs().edit().remove(KEY_SELECTED_COMPAT_PACK_ID).apply();
			writeSelectedCompatJson(null);
			return;
		}
		CompatPack pack = readPack(new File(getCompatPacksRootDir(), packId));
		if (pack == null || !pack.ready) {
			throw new IOException("Compat pack is not installed or incomplete: " + packId);
		}
		prefs().edit().putString(KEY_SELECTED_COMPAT_PACK_ID, pack.packId).apply();
		writeSelectedCompatJson(pack);
	}

	public File getSelectedCompatDll() {
		CompatPack pack = getSelectedPack();
		return pack == null ? null : pack.dllFile;
	}

	public File getSelectedCompatOverlayPck() {
		CompatPack pack = getSelectedPack();
		return pack == null ? null : pack.overlayPckFile;
	}

	public String buildSelectedCompatStamp() {
		if (!isCompatPackEnabled()) {
			return "compat-disabled";
		}
		CompatPack pack = getSelectedPack();
		if (pack == null || !pack.ready) {
			return "no-selected-compat";
		}
		return pack.packId + ":" + pack.compatVersion + ":" + pack.dllFile.length() + ":" + pack.dllFile.lastModified() + ":" + pack.overlayPckFile.length() + ":" + pack.overlayPckFile.lastModified();
	}

	public CompatPack findBestMatch(JSONObject payloadManifest) {
		return findBestMatch(payloadManifest, listInstalledPacks());
	}

	public CompatPack findBestMatch(JSONObject payloadManifest, List<CompatPack> installedPacks) {
		if (payloadManifest == null || installedPacks == null) {
			return null;
		}
		PayloadIdentity identity = readPayloadIdentity(payloadManifest);
		for (CompatPack pack : installedPacks) {
			if (!pack.ready) {
				continue;
			}
			if (!TextUtils.isEmpty(identity.version) && identity.version.equalsIgnoreCase(pack.targetVersion)) {
				return pack;
			}
		}
		return null;
	}

	public boolean isPackCompatibleWithPayload(CompatPack pack, JSONObject payloadManifest) {
		if (pack == null || !pack.ready || payloadManifest == null) {
			return false;
		}
		PayloadIdentity identity = readPayloadIdentity(payloadManifest);
		return !TextUtils.isEmpty(identity.version) && !TextUtils.isEmpty(pack.targetVersion) && identity.version.equalsIgnoreCase(pack.targetVersion);
	}

	public String getPayloadVersion(JSONObject payloadManifest) {
		return readPayloadIdentity(payloadManifest).version;
	}

	public int installBundledCompatPacks() throws Exception {
		String[] names;
		try {
			names = context.getAssets().list(COMPAT_ASSET_DIR);
		} catch (IOException exception) {
			return 0;
		}
		if (names == null || names.length == 0) {
			return 0;
		}
		File importRoot = getImportRootDir();
		FileBrowserSupport.ensureDirectory(importRoot);
		int installed = 0;
		for (String name : names) {
			if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
				continue;
			}
			File tempZip = new File(importRoot, "bundled-compat-" + UUID.randomUUID() + ".zip");
			try (InputStream inputStream = context.getAssets().open(COMPAT_ASSET_DIR + "/" + name)) {
				copyStreamToFile(inputStream, tempZip);
			}
			try {
				installFromZipFile(tempZip, "bundled_asset", name);
				installed++;
			} finally {
				FileBrowserSupport.deleteRecursively(tempZip);
			}
		}
		selectLatestInstalledIfNeeded();
		return installed;
	}

	public CompatPack importCompatPack(Uri uri) throws Exception {
		String displayName = queryDisplayName(uri);
		if (TextUtils.isEmpty(displayName)) {
			displayName = "compat-pack.zip";
		}
		File importRoot = getImportRootDir();
		FileBrowserSupport.ensureDirectory(importRoot);
		File tempZip = new File(importRoot, "compat-" + UUID.randomUUID() + ".zip");
		try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
			if (inputStream == null) {
				throw new IOException("Unable to open selected compatibility pack.");
			}
			copyStreamToFile(inputStream, tempZip);
		}
		try {
			CompatPack pack = installFromZipFile(tempZip, "saf_zip", displayName);
			selectPack(pack.packId);
			return pack;
		} finally {
			FileBrowserSupport.deleteRecursively(tempZip);
		}
	}

	public void deletePack(String packId) throws Exception {
		if (TextUtils.isEmpty(packId)) {
			return;
		}
		File dir = new File(getCompatPacksRootDir(), packId);
		if (dir.isDirectory()) {
			FileBrowserSupport.deleteRecursively(dir);
		}
		if (packId.equals(getSelectedPackId())) {
			selectPack("");
			selectLatestInstalledIfNeeded();
		}
	}

	private PayloadIdentity readPayloadIdentity(JSONObject payloadManifest) {
		JSONObject identity = payloadManifest.optJSONObject("identity");
		JSONObject game = payloadManifest.optJSONObject("game");
		JSONObject releaseInfo = identity == null ? null : identity.optJSONObject("release_info");
		if (releaseInfo == null && game != null) {
			releaseInfo = game.optJSONObject("release_info");
		}
		String version = releaseInfo == null ? "" : releaseInfo.optString("version", "");
		if (TextUtils.isEmpty(version) && identity != null) {
			version = identity.optString("version", "");
		}
		if (TextUtils.isEmpty(version) && game != null) {
			version = game.optString("version", "");
		}
		return new PayloadIdentity(version);
	}

	private CompatPack installFromZipFile(File zipFile, String sourceKind, String sourceName) throws Exception {
		File importRoot = getImportRootDir();
		File stagingRoot = new File(importRoot, "compat-staging-" + UUID.randomUUID());
		FileBrowserSupport.ensureDirectory(stagingRoot);
		try {
			extractZipSafely(zipFile, stagingRoot);
			File packRoot = locatePackRoot(stagingRoot);
			JSONObject manifest = new JSONObject(readTextFile(new File(packRoot, MANIFEST_FILE_NAME)));
			String packId = manifest.optString("pack_id", packRoot.getName());
			packId = sanitizeId(packId);
			if (TextUtils.isEmpty(packId)) {
				throw new IOException("Compatibility pack manifest has no pack_id.");
			}
			requireFile(new File(packRoot, ENTRY_DLL), "Missing " + ENTRY_DLL);
			requireFile(new File(packRoot, ENTRY_OVERLAY_PCK), "Missing " + ENTRY_OVERLAY_PCK);
			String zipSha256 = sha256(zipFile);
			File targetDir = new File(getCompatPacksRootDir(), packId);
			CompatPack existing = readPack(targetDir);
			JSONObject existingSource = existing == null ? null : existing.manifest.optJSONObject("installed_source");
			if (existing != null && existing.ready && existingSource != null && zipSha256.equalsIgnoreCase(existingSource.optString("zip_sha256", ""))) {
				return existing;
			}
			manifest.put("installed_at_unix", System.currentTimeMillis() / 1000L);
			JSONObject source = new JSONObject();
			source.put("kind", sourceKind);
			source.put("display_name", sourceName == null ? "" : sourceName);
			source.put("zip_sha256", zipSha256);
			manifest.put("installed_source", source);
			File backup = new File(importRoot, "compat-backup-" + UUID.randomUUID());
			boolean moved = false;
			try {
				FileBrowserSupport.ensureDirectory(getCompatPacksRootDir());
				if (targetDir.exists()) {
					if (!targetDir.renameTo(backup)) {
						throw new IOException("Unable to replace existing compatibility pack: " + targetDir.getAbsolutePath());
					}
					moved = true;
				}
				FileBrowserSupport.copyEntryRecursively(packRoot, targetDir);
				writeTextFile(new File(targetDir, MANIFEST_FILE_NAME), manifest.toString(2));
				FileBrowserSupport.deleteRecursively(backup);
				CompatPack installed = readPack(targetDir);
				if (installed == null || !installed.ready) {
					throw new IOException("Installed compatibility pack is incomplete: " + targetDir.getAbsolutePath());
				}
				return installed;
			} catch (Exception exception) {
				FileBrowserSupport.deleteRecursively(targetDir);
				if (moved && backup.exists()) {
					backup.renameTo(targetDir);
				}
				throw exception;
			}
		} finally {
			FileBrowserSupport.deleteRecursively(stagingRoot);
		}
	}

	private void selectLatestInstalledIfNeeded() throws Exception {
		if (!TextUtils.isEmpty(getSelectedPackId()) && getSelectedPack() != null) {
			return;
		}
		List<CompatPack> packs = listInstalledPacks();
		if (!packs.isEmpty()) {
			packs.sort((a, b) -> Long.compare(b.installedAtUnix, a.installedAtUnix));
			selectPack(packs.get(0).packId);
		}
	}

	private CompatPack readPack(File dir) throws Exception {
		if (dir == null || !dir.isDirectory()) {
			return null;
		}
		File manifestFile = new File(dir, MANIFEST_FILE_NAME);
		if (!manifestFile.isFile()) {
			return null;
		}
		JSONObject manifest = new JSONObject(readTextFile(manifestFile));
		String packId = sanitizeId(manifest.optString("pack_id", dir.getName()));
		JSONObject target = manifest.optJSONObject("target_game");
		File dll = new File(dir, ENTRY_DLL);
		File overlay = new File(dir, ENTRY_OVERLAY_PCK);
		return new CompatPack(
			packId,
			manifest.optString("display_name", packId),
			manifest.optString("compat_version", ""),
			manifest.optString("channel", ""),
			target == null ? "" : target.optString("version", ""),
			target == null ? "" : target.optString("commit", ""),
			target == null ? "" : target.optString("sts2_dll_sha256", ""),
			manifest.optLong("installed_at_unix", dir.lastModified() / 1000L),
			dir,
			dll,
			overlay,
			manifest,
			dll.isFile() && overlay.isFile()
		);
	}

	private File locatePackRoot(File stagingRoot) throws IOException {
		if (new File(stagingRoot, MANIFEST_FILE_NAME).isFile()) {
			return stagingRoot;
		}
		File[] children = stagingRoot.listFiles(File::isDirectory);
		File singleRoot = null;
		if (children != null) {
			for (File child : children) {
				if (new File(child, MANIFEST_FILE_NAME).isFile()) {
					return child;
				}
				if (singleRoot == null) {
					singleRoot = child;
				} else {
					singleRoot = null;
					break;
				}
			}
		}
		if (singleRoot != null) {
			File nested = locatePackRoot(singleRoot);
			if (nested != null) {
				return nested;
			}
		}
		throw new IOException("Compatibility pack manifest is missing: " + MANIFEST_FILE_NAME);
	}

	private void extractZipSafely(File zipFile, File targetRoot) throws Exception {
		String rootPath = targetRoot.getCanonicalPath();
		try (ZipFile archive = new ZipFile(zipFile)) {
			Enumeration<? extends ZipEntry> entries = archive.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = normalizeEntryName(entry.getName());
				if (name.isEmpty() || name.startsWith("__MACOSX/") || name.contains(":")) {
					continue;
				}
				if (name.equals("..") || name.startsWith("../") || name.contains("/../") || name.endsWith("/..")) {
					throw new IOException("Blocked invalid zip entry: " + entry.getName());
				}
				File out = new File(targetRoot, name);
				String outPath = out.getCanonicalPath();
				if (!outPath.equals(rootPath) && !outPath.startsWith(rootPath + File.separator)) {
					throw new IOException("Blocked invalid zip entry: " + entry.getName());
				}
				if (entry.isDirectory()) {
					FileBrowserSupport.ensureDirectory(out);
					continue;
				}
				File parent = out.getParentFile();
				if (parent != null) {
					FileBrowserSupport.ensureDirectory(parent);
				}
				try (InputStream inputStream = archive.getInputStream(entry); OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(out))) {
					copy(inputStream, outputStream);
				}
			}
		}
	}

	private String normalizeEntryName(String name) {
		String normalized = name == null ? "" : name.replace('\\', '/').trim();
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		return normalized;
	}

	private void requireFile(File file, String message) throws IOException {
		if (!file.isFile() || file.length() <= 0) {
			throw new IOException(message);
		}
	}

	private void copyStreamToFile(InputStream inputStream, File destination) throws IOException {
		File parent = destination.getParentFile();
		if (parent != null) {
			FileBrowserSupport.ensureDirectory(parent);
		}
		try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(destination))) {
			copy(inputStream, outputStream);
		}
	}

	private void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
		byte[] buffer = new byte[BUFFER_SIZE];
		int read;
		while ((read = inputStream.read(buffer)) != -1) {
			outputStream.write(buffer, 0, read);
		}
		outputStream.flush();
	}

	private String readTextFile(File file) throws IOException {
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file)); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			copy(inputStream, outputStream);
			return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private void writeTextFile(File file, String value) throws IOException {
		File parent = file.getParentFile();
		if (parent != null) {
			FileBrowserSupport.ensureDirectory(parent);
		}
		try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(file))) {
			outputStream.write(value.getBytes(StandardCharsets.UTF_8));
		}
	}

	private String sha256(File file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
		StringBuilder builder = new StringBuilder();
		for (byte value : digest.digest()) {
			builder.append(String.format(Locale.US, "%02x", value & 0xff));
		}
		return builder.toString();
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

	private String sanitizeId(String value) {
		String sanitized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		sanitized = sanitized.replaceAll("[^a-z0-9._-]+", "-");
		while (sanitized.startsWith("-") || sanitized.startsWith(".")) {
			sanitized = sanitized.substring(1);
		}
		while (sanitized.endsWith("-") || sanitized.endsWith(".")) {
			sanitized = sanitized.substring(0, sanitized.length() - 1);
		}
		return sanitized;
	}

	private void writeSelectedCompatJson(CompatPack pack) throws Exception {
		File launcherDir = new File(context.getFilesDir(), "launcher");
		FileBrowserSupport.ensureDirectory(launcherDir);
		JSONObject root = new JSONObject();
		root.put("schema", 1);
		root.put("compat_pack_enabled", isCompatPackEnabled());
		if (pack != null && isCompatPackEnabled()) {
			root.put("selected_compat_pack_id", pack.packId);
			root.put("selected_compat_pack_dir", pack.dir.getAbsolutePath());
		}
		writeTextFile(new File(launcherDir, "selected_compat_pack.json"), root.toString(2));
	}

	public File getCompatPacksRootDir() {
		return new File(context.getFilesDir(), COMPAT_PACKS_DIR_NAME);
	}

	private File getImportRootDir() {
		return new File(context.getFilesDir(), "compat_pack_import");
	}

	private SharedPreferences prefs() {
		return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	private static final class PayloadIdentity {
		final String version;

		PayloadIdentity(String version) {
			this.version = version == null ? "" : version;
		}
	}

	public static final class CompatPack {
		public final String packId;
		public final String displayName;
		public final String compatVersion;
		public final String channel;
		public final String targetVersion;
		public final String targetCommit;
		public final String targetSts2DllSha256;
		public final long installedAtUnix;
		public final File dir;
		public final File dllFile;
		public final File overlayPckFile;
		public final JSONObject manifest;
		public final boolean ready;

		CompatPack(String packId, String displayName, String compatVersion, String channel, String targetVersion, String targetCommit, String targetSts2DllSha256, long installedAtUnix, File dir, File dllFile, File overlayPckFile, JSONObject manifest, boolean ready) {
			this.packId = packId == null ? "" : packId;
			this.displayName = TextUtils.isEmpty(displayName) ? this.packId : displayName;
			this.compatVersion = compatVersion == null ? "" : compatVersion;
			this.channel = channel == null ? "" : channel;
			this.targetVersion = targetVersion == null ? "" : targetVersion;
			this.targetCommit = targetCommit == null ? "" : targetCommit;
			this.targetSts2DllSha256 = targetSts2DllSha256 == null ? "" : targetSts2DllSha256;
			this.installedAtUnix = installedAtUnix;
			this.dir = dir;
			this.dllFile = dllFile;
			this.overlayPckFile = overlayPckFile;
			this.manifest = manifest;
			this.ready = ready;
		}

		public String targetLabel() {
			if (!TextUtils.isEmpty(targetVersion) && !TextUtils.isEmpty(targetCommit)) {
				return targetVersion + " (" + targetCommit + ")";
			}
			if (!TextUtils.isEmpty(targetVersion)) {
				return targetVersion;
			}
			if (!TextUtils.isEmpty(targetSts2DllSha256)) {
				return targetSts2DllSha256.substring(0, Math.min(12, targetSts2DllSha256.length()));
			}
			return "unknown";
		}
	}
}
