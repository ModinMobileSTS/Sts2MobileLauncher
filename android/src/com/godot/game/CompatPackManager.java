package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;

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
	private static final String TAG = "Sts2CompatPacks";
	public static final String COMPAT_PACKS_DIR_NAME = "compat-packs";
	public static final String COMPAT_ASSET_DIR = "compat_packs";
	public static final String MANIFEST_FILE_NAME = "compat_manifest.json";
	public static final String ENTRY_DLL = "STS2Mobile.dll";
	public static final String ENTRY_OVERLAY_PCK = "port_compat.pck";
	public static final String ARTIFACTS_DIR_NAME = "variants";
	public static final String PACK_KIND_OFFLINE_BOOTSTRAP = "offline-bootstrap";
	public static final String MATCH_MODE_OFFLINE_WILDCARD = "offline-wildcard";

	private static final String PREFS_NAME = "sts2_version_manager";
	private static final String KEY_SELECTED_COMPAT_PACK_ID = "selected_compat_pack_id";
	private static final String KEY_OFFLINE_BOOTSTRAP_APPROVED_PREFIX = "offline_bootstrap_approved_";
	private static final String OFFLINE_BOOTSTRAP_PROBE_FILE_NAME = "offline-bootstrap-probe.json";
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
				packs.addAll(readPacks(child));
			} catch (Exception ignored) {
			}
		}
		packs.sort(Comparator
			.comparing((CompatPack pack) -> pack.displayName, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(pack -> pack.targetLabel(), String.CASE_INSENSITIVE_ORDER));
		return packs;
	}

	public CompatPack getSelectedPack() {
		if (!isCompatPackEnabled()) {
			return null;
		}
		return getSelectedPackIgnoringEnabled();
	}

	public CompatPack getSelectedPackIgnoringEnabled() {
		String id = getSelectedPackIdIgnoringEnabled();
		if (TextUtils.isEmpty(id)) {
			return null;
		}
		try {
			return readSelectionPack(new File(getCompatPacksRootDir(), id), getSelectedTargetIdIgnoringEnabled());
		} catch (Exception ignored) {
			return null;
		}
	}

	public String getSelectedPackId() {
		if (!isCompatPackEnabled()) {
			return "";
		}
		return getSelectedPackIdIgnoringEnabled();
	}

	public String getSelectedPackIdIgnoringEnabled() {
		try {
			return new LaunchProfileManager(context).getSelectedCompatPackId();
		} catch (Exception ignored) {
			return "";
		}
	}

	public String getSelectedTargetId() {
		if (!isCompatPackEnabled()) {
			return "";
		}
		return getSelectedTargetIdIgnoringEnabled();
	}

	public String getSelectedTargetIdIgnoringEnabled() {
		try {
			return new LaunchProfileManager(context).getSelectedCompatTargetId();
		} catch (Exception ignored) {
			return "";
		}
	}

	public boolean isCompatPackEnabled() {
		try {
			return new ExtraSettingsRepository(context).isAndroidCompatPackEnabled();
		} catch (Exception exception) {
			return true;
		}
	}

	public void selectPack(String packId) throws Exception {
		selectPack(packId, "");
	}

	public void selectPack(String packId, String targetId) throws Exception {
		if (TextUtils.isEmpty(packId)) {
			prefs().edit().remove(KEY_SELECTED_COMPAT_PACK_ID).apply();
			new LaunchProfileManager(context).setSelectedProfileCompatPack("", "");
			writeSelectedCompatJson("", null);
			return;
		}
		CompatPack pack = readSelectionPack(new File(getCompatPacksRootDir(), packId), targetId);
		if (pack == null || !pack.ready) {
			throw new IOException("Compat pack is not installed or incomplete: " + packId + (TextUtils.isEmpty(targetId) ? "" : "/" + targetId));
		}
		prefs().edit().remove(KEY_SELECTED_COMPAT_PACK_ID).apply();
		new LaunchProfileManager(context).setSelectedProfileCompatPack(pack.packId, pack.targetId);
		writeSelectedCompatJson(pack.packId, pack);
	}

	public File getSelectedCompatDll() {
		CompatPack pack = getSelectedPack();
		return pack == null ? null : pack.dllFile;
	}

	public File getSelectedCompatDllIgnoringEnabled() {
		CompatPack pack = getSelectedPackIgnoringEnabled();
		return pack == null ? null : pack.dllFile;
	}

	public File getSelectedCompatOverlayPck() {
		CompatPack pack = getSelectedPack();
		return pack == null ? null : pack.overlayPckFile;
	}

	public File getSelectedCompatOverlayPckIgnoringEnabled() {
		CompatPack pack = getSelectedPackIgnoringEnabled();
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
		JSONObject installedSource = pack.manifest.optJSONObject("installed_source");
		String sourceZipSha256 = installedSource == null ? "" : installedSource.optString("zip_sha256", "");
		return pack.packId + ":" + pack.targetId + ":" + pack.compatVersion + ":" + sourceZipSha256 + ":" + pack.dllFile.length() + ":" + pack.dllFile.lastModified() + ":" + pack.overlayPckFile.length() + ":" + pack.overlayPckFile.lastModified();
	}

	public CompatPack findBestMatch(JSONObject payloadManifest) {
		return findBestMatch(payloadManifest, listInstalledPacks());
	}

	public CompatPack findBestMatch(JSONObject payloadManifest, List<CompatPack> installedPacks) {
		if (payloadManifest == null || installedPacks == null) {
			return null;
		}
		PayloadIdentity identity = readPayloadIdentity(payloadManifest);
		if (TextUtils.isEmpty(identity.version)) {
			return null;
		}
		CompatPack bestPack = null;
		int bestScore = 0;
		for (CompatPack pack : installedPacks) {
			int score = matchScore(pack, identity);
			if (score <= 0) {
				continue;
			}
			if (pack.isOfflineWildcard() && hasKnownFailedOfflineBootstrap(pack, payloadManifest)) {
				Log.w(TAG, "Skipping known-failed offline bootstrap auto-match for " + pack.selectionLabel() + " and payload " + identity.version + "/" + identity.sts2DllSha256);
				continue;
			}
			if (bestPack == null || isBetterMatch(pack, score, bestPack, bestScore)) {
				bestPack = pack;
				bestScore = score;
			}
		}
		return bestPack;
	}

	private int matchScore(CompatPack pack, PayloadIdentity identity) {
		if (pack == null || !pack.ready || identity == null || TextUtils.isEmpty(identity.version)) {
			return 0;
		}
		if (pack.supportsSts2DllSha256(identity.sts2DllSha256)) {
			return 400;
		}
		if (!pack.isOfflineWildcard() && identity.version.equalsIgnoreCase(pack.targetVersion)) {
			return 300;
		}
		if (packSupportsExplicitVersion(pack, identity.version)) {
			return 200;
		}
		if (pack.isOfflineWildcard()) {
			return 10;
		}
		return 0;
	}

	private boolean isBetterMatch(CompatPack candidate, int candidateScore, CompatPack current, int currentScore) {
		if (candidateScore != currentScore) {
			return candidateScore > currentScore;
		}
		if (candidate.selectionPriority != current.selectionPriority) {
			return candidate.selectionPriority > current.selectionPriority;
		}
		int candidateSchema = candidate.manifest.optInt("schema", 1);
		int currentSchema = current.manifest.optInt("schema", 1);
		if (candidateSchema != currentSchema) {
			return candidateSchema > currentSchema;
		}
		int packCompare = candidate.packId.compareToIgnoreCase(current.packId);
		if (packCompare != 0) {
			return packCompare < 0;
		}
		return candidate.targetId.compareToIgnoreCase(current.targetId) < 0;
	}

	public boolean isPackCompatibleWithPayload(CompatPack pack, JSONObject payloadManifest) {
		if (pack == null || !pack.ready || payloadManifest == null) {
			return false;
		}
		PayloadIdentity identity = readPayloadIdentity(payloadManifest);
		return matchScore(pack, identity) > 0;
	}

	public boolean isOfflineBootstrapPack(CompatPack pack) {
		return pack != null && PACK_KIND_OFFLINE_BOOTSTRAP.equals(pack.packKind);
	}

	public boolean requiresOfflineBootstrapApproval(CompatPack pack, JSONObject payloadManifest) {
		return pack != null
			&& pack.isOfflineWildcard()
			&& (hasKnownFailedOfflineBootstrap(pack, payloadManifest) || !hasApprovedOfflineBootstrap(pack, payloadManifest));
	}

	public boolean hasKnownFailedOfflineBootstrap(CompatPack pack, JSONObject payloadManifest) {
		OfflineBootstrapProbe probe = readOfflineBootstrapProbe(pack, payloadManifest);
		return probe != null && probe.isTerminalFailure();
	}

	public boolean hasVerifiedOfflineBootstrap(CompatPack pack, JSONObject payloadManifest) {
		OfflineBootstrapProbe probe = readOfflineBootstrapProbe(pack, payloadManifest);
		return probe != null && probe.isReady();
	}

	public OfflineBootstrapProbe getOfflineBootstrapProbe(CompatPack pack, JSONObject payloadManifest) {
		return readOfflineBootstrapProbe(pack, payloadManifest);
	}

	public boolean hasApprovedOfflineBootstrap(CompatPack pack, JSONObject payloadManifest) {
		String key = offlineBootstrapApprovalKey(pack, payloadManifest);
		return !TextUtils.isEmpty(key) && prefs().getBoolean(key, false);
	}

	public void approveOfflineBootstrap(CompatPack pack, JSONObject payloadManifest) {
		String key = offlineBootstrapApprovalKey(pack, payloadManifest);
		if (!TextUtils.isEmpty(key)) {
			prefs().edit().putBoolean(key, true).apply();
		}
	}

	private String offlineBootstrapApprovalKey(CompatPack pack, JSONObject payloadManifest) {
		if (pack == null || payloadManifest == null) {
			return "";
		}
		PayloadIdentity identity = readPayloadIdentity(payloadManifest);
		if (TextUtils.isEmpty(identity.version)) {
			return "";
		}
		String tuple = pack.packId + "|" + pack.targetId + "|" + pack.compatVersion + "|" + installedSourceZipSha256(pack) + "|" + identity.version + "|" + identity.sts2DllSha256;
		return KEY_OFFLINE_BOOTSTRAP_APPROVED_PREFIX + sha256Text(tuple);
	}

	private OfflineBootstrapProbe readOfflineBootstrapProbe(CompatPack pack, JSONObject payloadManifest) {
		if (pack == null || !pack.isOfflineWildcard() || payloadManifest == null) {
			return null;
		}
		File probeFile = new File(new File(context.getFilesDir(), "launcher"), OFFLINE_BOOTSTRAP_PROBE_FILE_NAME);
		if (!probeFile.isFile()) {
			return null;
		}
		try {
			JSONObject json = new JSONObject(readTextFile(probeFile));
			String probeContract = optStringAny(json, "ProbeContract", "probe_contract");
			if (!"offline-bootstrap-v2".equals(probeContract)) {
				return null;
			}
			PayloadIdentity identity = readPayloadIdentity(payloadManifest);
			OfflineBootstrapProbe probe = new OfflineBootstrapProbe(
				optStringAny(json, "Status", "status"),
				optBooleanAny(json, false, "Success", "success"),
				optStringAny(json, "PackId", "pack_id"),
				optStringAny(json, "TargetId", "target_id"),
				optStringAny(json, "CompatVersion", "compat_version"),
				optStringAny(json, "PackZipSha256", "pack_zip_sha256"),
				optStringAny(json, "PayloadVersion", "payload_version"),
				optStringAny(json, "Sts2DllSha256", "sts2_dll_sha256"),
				optStringAny(json, "FailureSummary", "failure_summary"),
				optStringAny(json, "WrittenUtc", "written_utc")
			);
			if (!probe.matches(pack, identity)) {
				return null;
			}
			if (TextUtils.isEmpty(probe.failureSummary)) {
				JSONArray checks = json.optJSONArray("Checks");
				if (checks == null) {
					checks = json.optJSONArray("checks");
				}
				if (checks != null) {
					for (int i = 0; i < checks.length(); i++) {
						JSONObject check = checks.optJSONObject(i);
						if (check == null || optBooleanAny(check, true, "Success", "success")) {
							continue;
						}
						String message = optStringAny(check, "Message", "message");
						if (!TextUtils.isEmpty(message)) {
							probe.failureSummary = message;
							break;
						}
					}
				}
			}
			return probe;
		} catch (Exception exception) {
			Log.w(TAG, "Unable to read offline bootstrap probe.", exception);
			return null;
		}
	}

	private String installedSourceZipSha256(CompatPack pack) {
		JSONObject installedSource = pack == null ? null : pack.manifest.optJSONObject("installed_source");
		return installedSource == null ? "" : installedSource.optString("zip_sha256", "");
	}

	private String optStringAny(JSONObject json, String... keys) {
		if (json == null || keys == null) {
			return "";
		}
		for (String key : keys) {
			String value = json.optString(key, "");
			if (!TextUtils.isEmpty(value)) {
				return value;
			}
		}
		return "";
	}

	private boolean optBooleanAny(JSONObject json, boolean fallback, String... keys) {
		if (json == null || keys == null) {
			return fallback;
		}
		for (String key : keys) {
			if (json.has(key)) {
				return json.optBoolean(key, fallback);
			}
		}
		return fallback;
	}

	public String getPayloadVersion(JSONObject payloadManifest) {
		return readPayloadIdentity(payloadManifest).version;
	}

	public String getPayloadSts2DllSha256(JSONObject payloadManifest) {
		return readPayloadIdentity(payloadManifest).sts2DllSha256;
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
		try {
			int migrated = new LaunchProfileManager(context).migrateLegacyBundledCompatSelectionsToFlatPack();
			if (migrated > 0) {
				Log.i(TAG, "Migrated launch profiles to bundled flat compatibility pack: " + migrated);
			}
		} catch (Exception exception) {
			Log.w(TAG, "Unable to migrate legacy bundled compatibility pack selections.", exception);
		}
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
			return installFromZipFile(tempZip, "saf_zip", displayName);
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
			writeSelectedCompatJson(packId, null);
		}
	}

	private boolean packSupportsVersion(CompatPack pack, String version) {
		if (pack == null || TextUtils.isEmpty(version)) {
			return false;
		}
		return pack.isOfflineWildcard() || packSupportsExplicitVersion(pack, version);
	}

	private boolean packSupportsExplicitVersion(CompatPack pack, String version) {
		if (pack == null || TextUtils.isEmpty(version)) {
			return false;
		}
		for (String supportedVersion : pack.targetVersions) {
			if ("*".equals(supportedVersion)) {
				continue;
			}
			if (version.equalsIgnoreCase(supportedVersion)) {
				return true;
			}
		}
		return false;
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
		String dllSha = identity == null ? "" : identity.optString("sts2_dll_sha256", "");
		if (TextUtils.isEmpty(dllSha) && game != null) {
			dllSha = game.optString("sts2_dll_sha256", "");
		}
		return new PayloadIdentity(version, dllSha);
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
			validatePackArtifacts(packRoot, manifest);
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
				CompatPack installed = readPack(targetDir, "");
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

	private CompatPack readPack(File dir) throws Exception {
		return readPack(dir, "");
	}

	private CompatPack readSelectionPack(File dir, String requestedTargetId) throws Exception {
		CompatPack pack = readPack(dir, requestedTargetId);
		if (pack != null && pack.manifest.optInt("schema", 1) >= 2 && TextUtils.isEmpty(sanitizeId(requestedTargetId))) {
			return null;
		}
		return pack;
	}

	private CompatPack readPack(File dir, String requestedTargetId) throws Exception {
		List<CompatPack> packs = readPacks(dir);
		if (packs.isEmpty()) {
			return null;
		}
		String targetId = sanitizeId(requestedTargetId);
		if (!TextUtils.isEmpty(targetId)) {
			for (CompatPack pack : packs) {
				if (targetId.equals(pack.targetId)) {
					return pack;
				}
			}
			return null;
		}
		return packs.get(0);
	}

	private List<CompatPack> readPacks(File dir) throws Exception {
		List<CompatPack> packs = new ArrayList<>();
		if (dir == null || !dir.isDirectory()) {
			return packs;
		}
		File manifestFile = new File(dir, MANIFEST_FILE_NAME);
		if (!manifestFile.isFile()) {
			return packs;
		}
		JSONObject manifest = new JSONObject(readTextFile(manifestFile));
		String packId = sanitizeId(manifest.optString("pack_id", dir.getName()));
		String packKind = manifest.optString("pack_kind", "");
		if (manifest.optInt("schema", 1) >= 2 && manifest.optJSONArray("targets") != null) {
			JSONArray targets = manifest.optJSONArray("targets");
			for (int i = 0; i < targets.length(); i++) {
				JSONObject target = targets.optJSONObject(i);
				if (target == null) {
					continue;
				}
				String targetId = sanitizeId(target.optString("target_id", target.optString("id", "")));
				if (TextUtils.isEmpty(targetId)) {
					targetId = "target-" + (i + 1);
				}
				List<String> targetVersions = readTargetVersions(target);
				String targetVersion = target.optString("version", "");
				if (TextUtils.isEmpty(targetVersion) && !targetVersions.isEmpty()) {
					targetVersion = targetVersions.get(0);
				}
				File dll = new File(dir, readArtifactPath(target, "dll", ARTIFACTS_DIR_NAME + "/" + targetId + "/" + ENTRY_DLL));
				File overlay = new File(dir, readArtifactPath(target, "overlay_pck", ARTIFACTS_DIR_NAME + "/" + targetId + "/" + ENTRY_OVERLAY_PCK));
				packs.add(new CompatPack(
					packId,
					targetId,
					packKind,
					target.optString("match_mode", manifest.optString("match_mode", "")),
					target.optInt("selection_priority", manifest.optInt("selection_priority", 0)),
					manifest.optString("display_name", packId),
					target.optString("display_name", ""),
					manifest.optString("compat_version", ""),
					manifest.optString("channel", ""),
					target.optString("channel", manifest.optString("channel", "")),
					targetVersion,
					targetVersions,
					target.optString("commit", ""),
					readTargetDllShas(target),
					manifest.optLong("installed_at_unix", dir.lastModified() / 1000L),
					dir,
					dll,
					overlay,
					manifest,
					dll.isFile() && overlay.isFile()
				));
			}
			return packs;
		}
		JSONObject target = manifest.optJSONObject("target_game");
		List<String> targetVersions = readTargetVersions(target);
		String targetVersion = target == null ? "" : target.optString("version", "");
		if (TextUtils.isEmpty(targetVersion) && !targetVersions.isEmpty()) {
			targetVersion = targetVersions.get(0);
		}
		File dll = new File(dir, ENTRY_DLL);
		File overlay = new File(dir, ENTRY_OVERLAY_PCK);
		packs.add(new CompatPack(
			packId,
			"",
			packKind,
			manifest.optString("match_mode", ""),
			manifest.optInt("selection_priority", 0),
			manifest.optString("display_name", packId),
			target == null ? "" : target.optString("display_name", ""),
			manifest.optString("compat_version", ""),
			manifest.optString("channel", ""),
			target == null ? manifest.optString("channel", "") : target.optString("channel", manifest.optString("channel", "")),
			targetVersion,
			targetVersions,
			target == null ? "" : target.optString("commit", ""),
			readTargetDllShas(target),
			manifest.optLong("installed_at_unix", dir.lastModified() / 1000L),
			dir,
			dll,
			overlay,
			manifest,
			dll.isFile() && overlay.isFile()
		));
		return packs;
	}

	private List<String> readTargetVersions(JSONObject target) {
		List<String> versions = new ArrayList<>();
		if (target == null) {
			return versions;
		}
		addTargetVersion(versions, target.optString("version", ""));
		for (String key : new String[] { "supported_versions", "compatible_versions", "versions" }) {
			JSONArray array = target.optJSONArray(key);
			if (array == null) {
				continue;
			}
			for (int i = 0; i < array.length(); i++) {
				addTargetVersion(versions, array.optString(i, ""));
			}
		}
		return versions;
	}

	private List<String> readTargetDllShas(JSONObject target) {
		List<String> hashes = new ArrayList<>();
		if (target == null) {
			return hashes;
		}
		JSONArray values = target.optJSONArray("sts2_dll_sha256");
		if (values != null) {
			for (int i = 0; i < values.length(); i++) {
				addTargetDllSha(hashes, values.optString(i, ""));
			}
			return hashes;
		}
		addTargetDllSha(hashes, target.optString("sts2_dll_sha256", ""));
		return hashes;
	}

	private void addTargetDllSha(List<String> hashes, String value) {
		String normalized = value == null ? "" : value.trim();
		if (TextUtils.isEmpty(normalized)) {
			return;
		}
		for (String existing : hashes) {
			if (normalized.equalsIgnoreCase(existing)) {
				return;
			}
		}
		hashes.add(normalized);
	}

	private String readArtifactPath(JSONObject target, String key, String fallback) {
		JSONObject artifacts = target == null ? null : target.optJSONObject("artifacts");
		String value = artifacts == null ? "" : artifacts.optString(key, "");
		if (TextUtils.isEmpty(value)) {
			value = target == null ? "" : target.optString(key, "");
		}
		return TextUtils.isEmpty(value) ? fallback : value;
	}

	private void validatePackArtifacts(File packRoot, JSONObject manifest) throws IOException {
		validatePackManifestMatchRules(manifest);
		if (manifest.optInt("schema", 1) >= 2 && manifest.optJSONArray("targets") != null) {
			JSONArray targets = manifest.optJSONArray("targets");
			if (targets.length() <= 0) {
				throw new IOException("Compatibility pack manifest has no targets.");
			}
			int readyTargets = 0;
			for (int i = 0; i < targets.length(); i++) {
				JSONObject target = targets.optJSONObject(i);
				if (target == null) {
					continue;
				}
				String targetId = sanitizeId(target.optString("target_id", target.optString("id", "")));
				if (TextUtils.isEmpty(targetId)) {
					targetId = "target-" + (i + 1);
				}
				File dll = new File(packRoot, readArtifactPath(target, "dll", ARTIFACTS_DIR_NAME + "/" + targetId + "/" + ENTRY_DLL));
				File overlay = new File(packRoot, readArtifactPath(target, "overlay_pck", ARTIFACTS_DIR_NAME + "/" + targetId + "/" + ENTRY_OVERLAY_PCK));
				requireFile(dll, "Missing target " + targetId + " " + ENTRY_DLL);
				requireFile(overlay, "Missing target " + targetId + " " + ENTRY_OVERLAY_PCK);
				readyTargets++;
			}
			if (readyTargets <= 0) {
				throw new IOException("Compatibility pack manifest has no readable targets.");
			}
			return;
		}
		requireFile(new File(packRoot, ENTRY_DLL), "Missing " + ENTRY_DLL);
		requireFile(new File(packRoot, ENTRY_OVERLAY_PCK), "Missing " + ENTRY_OVERLAY_PCK);
	}

	private void validatePackManifestMatchRules(JSONObject manifest) throws IOException {
		int schema = manifest.optInt("schema", 1);
		if (schema >= 2 && manifest.optJSONArray("targets") != null) {
			JSONArray targets = manifest.optJSONArray("targets");
			for (int i = 0; i < targets.length(); i++) {
				JSONObject target = targets.optJSONObject(i);
				if (target == null) {
					continue;
				}
				String rawTargetId = target.optString("target_id", target.optString("id", ""));
				if (rawTargetId.trim().contains("*")) {
					throw new IOException("Compatibility pack target_id cannot contain wildcard '*'.");
				}
				boolean allowWildcard = isOfflineWildcardManifestTarget(manifest, target);
				validateTargetVersionsForWildcard(readTargetVersions(target), allowWildcard);
				validateTargetShaForWildcard(target);
			}
			return;
		}
		JSONObject target = manifest.optJSONObject("target_game");
		validateTargetVersionsForWildcard(readTargetVersions(target), false);
		validateTargetShaForWildcard(target);
	}

	private boolean isOfflineWildcardManifestTarget(JSONObject manifest, JSONObject target) {
		String packKind = manifest.optString("pack_kind", "");
		String matchMode = target == null ? "" : target.optString("match_mode", manifest.optString("match_mode", ""));
		return PACK_KIND_OFFLINE_BOOTSTRAP.equals(packKind) && MATCH_MODE_OFFLINE_WILDCARD.equals(matchMode);
	}

	private void validateTargetVersionsForWildcard(List<String> versions, boolean allowWildcard) throws IOException {
		if (versions == null) {
			return;
		}
		for (String version : versions) {
			String value = version == null ? "" : version.trim();
			if (!value.contains("*")) {
				continue;
			}
			if (allowWildcard && "*".equals(value)) {
				continue;
			}
			throw new IOException("Wildcard target versions are only allowed for offline-bootstrap targets using match_mode=offline-wildcard.");
		}
	}

	private void validateTargetShaForWildcard(JSONObject target) throws IOException {
		if (target == null) {
			return;
		}
		JSONArray hashes = target.optJSONArray("sts2_dll_sha256");
		if (hashes != null) {
			for (int i = 0; i < hashes.length(); i++) {
				if (hashes.optString(i, "").contains("*")) {
					throw new IOException("sts2_dll_sha256 cannot contain wildcard '*'.");
				}
			}
		}
		if (target.optString("sts2_dll_sha256", "").contains("*")) {
			throw new IOException("sts2_dll_sha256 cannot contain wildcard '*'.");
		}
	}

	private void addTargetVersion(List<String> versions, String version) {
		String normalized = version == null ? "" : version.trim();
		if (TextUtils.isEmpty(normalized)) {
			return;
		}
		for (String existing : versions) {
			if (normalized.equalsIgnoreCase(existing)) {
				return;
			}
		}
		versions.add(normalized);
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
		return hexDigest(digest.digest());
	}

	private String sha256Text(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
			return hexDigest(digest.digest());
		} catch (Exception exception) {
			return sanitizeId(value);
		}
	}

	private String hexDigest(byte[] digest) {
		StringBuilder builder = new StringBuilder();
		for (byte value : digest) {
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

	public void writeSelectedCompatJsonForProfile(String compatPackId) {
		writeSelectedCompatJsonForProfile(compatPackId, "");
	}

	public void writeSelectedCompatJsonForProfile(String compatPackId, String compatTargetId) {
		try {
			String normalizedId = sanitizeId(compatPackId);
			if ("unnamed".equals(normalizedId)) {
				normalizedId = "";
			}
			String normalizedTargetId = sanitizeId(compatTargetId);
			CompatPack pack = TextUtils.isEmpty(normalizedId) ? null : readSelectionPack(new File(getCompatPacksRootDir(), normalizedId), normalizedTargetId);
			writeSelectedCompatJson(normalizedId, pack);
		} catch (Exception ignored) {
		}
	}

	private void writeSelectedCompatJson(String requestedPackId, CompatPack pack) throws Exception {
		File launcherDir = new File(context.getFilesDir(), "launcher");
		FileBrowserSupport.ensureDirectory(launcherDir);
		JSONObject root = new JSONObject();
		root.put("schema", 2);
		root.put("compat_pack_enabled", isCompatPackEnabled());
		String normalizedRequestedPackId = sanitizeId(requestedPackId);
		if ("unnamed".equals(normalizedRequestedPackId)) {
			normalizedRequestedPackId = "";
		}
		if (!TextUtils.isEmpty(normalizedRequestedPackId)) {
			root.put("selected_compat_pack_id", normalizedRequestedPackId);
		}
		if (pack != null && isCompatPackEnabled()) {
			root.put("selected_compat_pack_id", pack.packId);
			if (!TextUtils.isEmpty(pack.targetId)) {
				root.put("selected_compat_target_id", pack.targetId);
			}
			if (!TextUtils.isEmpty(pack.packKind)) {
				root.put("selected_compat_pack_kind", pack.packKind);
			}
			if (!TextUtils.isEmpty(pack.matchMode)) {
				root.put("selected_compat_match_mode", pack.matchMode);
			}
			root.put("selected_compat_pack_dir", pack.dir.getAbsolutePath());
			root.put("selected_compat_dll", pack.dllFile.getAbsolutePath());
			root.put("selected_compat_overlay_pck", pack.overlayPckFile.getAbsolutePath());
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
		final String sts2DllSha256;

		PayloadIdentity(String version, String sts2DllSha256) {
			this.version = version == null ? "" : version;
			this.sts2DllSha256 = sts2DllSha256 == null ? "" : sts2DllSha256;
		}
	}

	public static final class OfflineBootstrapProbe {
		public final String status;
		public final boolean success;
		public final String packId;
		public final String targetId;
		public final String compatVersion;
		public final String packZipSha256;
		public final String payloadVersion;
		public final String sts2DllSha256;
		public String failureSummary;
		public final String writtenUtc;

		OfflineBootstrapProbe(String status, boolean success, String packId, String targetId, String compatVersion, String packZipSha256, String payloadVersion, String sts2DllSha256, String failureSummary, String writtenUtc) {
			this.status = status == null ? "" : status;
			this.success = success;
			this.packId = packId == null ? "" : packId;
			this.targetId = targetId == null ? "" : targetId;
			this.compatVersion = compatVersion == null ? "" : compatVersion;
			this.packZipSha256 = packZipSha256 == null ? "" : packZipSha256;
			this.payloadVersion = payloadVersion == null ? "" : payloadVersion;
			this.sts2DllSha256 = sts2DllSha256 == null ? "" : sts2DllSha256;
			this.failureSummary = failureSummary == null ? "" : failureSummary;
			this.writtenUtc = writtenUtc == null ? "" : writtenUtc;
		}

		private boolean matches(CompatPack pack, PayloadIdentity identity) {
			if (pack == null || identity == null) {
				return false;
			}
			JSONObject installedSource = pack.manifest.optJSONObject("installed_source");
			String installedZipSha256 = installedSource == null ? "" : installedSource.optString("zip_sha256", "");
			return pack.packId.equalsIgnoreCase(packId)
				&& pack.targetId.equalsIgnoreCase(targetId)
				&& pack.compatVersion.equalsIgnoreCase(compatVersion)
				&& installedZipSha256.equalsIgnoreCase(packZipSha256)
				&& identity.version.equalsIgnoreCase(payloadVersion)
				&& identity.sts2DllSha256.equalsIgnoreCase(sts2DllSha256);
		}

		public boolean isReady() {
			return success && "ready".equalsIgnoreCase(status);
		}

		public boolean isTerminalFailure() {
			return "unsupported_api".equalsIgnoreCase(status)
				|| "apply_failed".equalsIgnoreCase(status)
				|| "runtime_failed".equalsIgnoreCase(status)
				|| "probe_failed".equalsIgnoreCase(status)
				|| "exception".equalsIgnoreCase(status);
		}
	}

	public static final class CompatPack {
		public final String packId;
		public final String targetId;
		public final String packKind;
		public final String matchMode;
		public final int selectionPriority;
		public final String displayName;
		public final String targetDisplayName;
		public final String compatVersion;
		public final String channel;
		public final String targetChannel;
		public final String targetVersion;
		public final List<String> targetVersions;
		public final String targetCommit;
		public final List<String> targetSts2DllSha256s;
		public final String targetSts2DllSha256;
		public final long installedAtUnix;
		public final File dir;
		public final File dllFile;
		public final File overlayPckFile;
		public final JSONObject manifest;
		public final boolean ready;

		CompatPack(String packId, String targetId, String packKind, String matchMode, int selectionPriority, String displayName, String targetDisplayName, String compatVersion, String channel, String targetChannel, String targetVersion, List<String> targetVersions, String targetCommit, List<String> targetSts2DllSha256s, long installedAtUnix, File dir, File dllFile, File overlayPckFile, JSONObject manifest, boolean ready) {
			this.packId = packId == null ? "" : packId;
			this.targetId = targetId == null ? "" : targetId;
			this.packKind = packKind == null ? "" : packKind;
			this.matchMode = matchMode == null ? "" : matchMode;
			this.selectionPriority = selectionPriority;
			this.displayName = TextUtils.isEmpty(displayName) ? this.packId : displayName;
			this.targetDisplayName = targetDisplayName == null ? "" : targetDisplayName;
			this.compatVersion = compatVersion == null ? "" : compatVersion;
			this.channel = channel == null ? "" : channel;
			this.targetChannel = targetChannel == null ? "" : targetChannel;
			this.targetVersion = targetVersion == null ? "" : targetVersion;
			this.targetVersions = targetVersions == null ? new ArrayList<>() : new ArrayList<>(targetVersions);
			if (this.targetVersions.isEmpty() && !TextUtils.isEmpty(this.targetVersion)) {
				this.targetVersions.add(this.targetVersion);
			}
			this.targetCommit = targetCommit == null ? "" : targetCommit;
			this.targetSts2DllSha256s = targetSts2DllSha256s == null ? new ArrayList<>() : new ArrayList<>(targetSts2DllSha256s);
			this.targetSts2DllSha256 = this.targetSts2DllSha256s.isEmpty() ? "" : this.targetSts2DllSha256s.get(0);
			this.installedAtUnix = installedAtUnix;
			this.dir = dir;
			this.dllFile = dllFile;
			this.overlayPckFile = overlayPckFile;
			this.manifest = manifest;
			this.ready = ready;
		}

		public boolean isOfflineWildcard() {
			return PACK_KIND_OFFLINE_BOOTSTRAP.equals(packKind) && MATCH_MODE_OFFLINE_WILDCARD.equals(matchMode) && targetVersions.contains("*");
		}

		public boolean supportsSts2DllSha256(String sha256) {
			if (TextUtils.isEmpty(sha256)) {
				return false;
			}
			for (String supportedSha256 : targetSts2DllSha256s) {
				if (sha256.equalsIgnoreCase(supportedSha256)) {
					return true;
				}
			}
			return false;
		}

		public String targetLabel() {
			if (isOfflineWildcard()) {
				return "any imported version (offline fallback)";
			}
			if (!targetVersions.isEmpty()) {
				String label = TextUtils.join(" / ", targetVersions);
				if (targetVersions.size() == 1 && !TextUtils.isEmpty(targetCommit)) {
					return label + " (" + targetCommit + ")";
				}
				return label;
			}
			if (!TextUtils.isEmpty(targetSts2DllSha256)) {
				return targetSts2DllSha256.substring(0, Math.min(12, targetSts2DllSha256.length()));
			}
			return "unknown";
		}

		public String selectionLabel() {
			return TextUtils.isEmpty(targetId) ? packId : packId + "/" + targetId;
		}
	}
}
