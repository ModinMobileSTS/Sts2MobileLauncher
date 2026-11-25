package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.CancellationSignal;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
	static final int MAX_MANIFEST_SCHEMA = 2;

	private static final String PREFS_NAME = "sts2_version_manager";
	private static final String KEY_SELECTED_COMPAT_PACK_ID = "selected_compat_pack_id";
	private static final String KEY_OFFLINE_BOOTSTRAP_APPROVED_PREFIX = "offline_bootstrap_approved_";
	private static final String OFFLINE_BOOTSTRAP_PROBE_FILE_NAME = "offline-bootstrap-probe.json";
	private static final int BUFFER_SIZE = 1024 * 1024;
	private static final Object PACK_STORAGE_LOCK = new Object();
	private static final String CHECKSUM_FILE_NAME = "SHA256SUMS";
	private static final Pattern CHECKSUM_LINE = Pattern.compile("([0-9a-fA-F]{64})  (.+)");
	private static final long MAX_REMOTE_UNZIPPED_BYTES = 256L * 1024 * 1024;
	private static final int MAX_REMOTE_ZIP_ENTRIES = 4096;
	private static final int MAX_REMOTE_METADATA_BYTES = 1024 * 1024;

	private final Context context;
	private final BundledAssets bundledAssets;

	public CompatPackManager(Context context) {
		this(context, null);
	}

	CompatPackManager(Context context, BundledAssets bundledAssets) {
		this.context = context.getApplicationContext();
		this.bundledAssets = bundledAssets;
	}

	public List<CompatPack> listInstalledPacks() {
		List<CompatPack> packs = new ArrayList<>();
		synchronized (PACK_STORAGE_LOCK) {
			File[] children = getCompatPacksRootDir().listFiles(File::isDirectory);
			if (children == null) {
				return packs;
			}
			for (File child : children) {
				try {
					packs.addAll(readPacks(child));
				} catch (Exception ignored) {
				}
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

	void requireSelectedPackReady() throws IOException {
		if (!isCompatPackEnabled()) {
			return;
		}
		String requestedPackId = getSelectedPackIdIgnoringEnabled();
		if (TextUtils.isEmpty(requestedPackId)) {
			return;
		}
		CompatPack pack = getSelectedPackIgnoringEnabled();
		if (pack == null || !pack.ready) {
			throw new IOException(context.getString(R.string.launch_profile_compat_missing_format, requestedPackId));
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

	public void selectPack(String packId, String targetId, String selectionSource) throws Exception {
		if (TextUtils.isEmpty(packId)) {
			prefs().edit().remove(KEY_SELECTED_COMPAT_PACK_ID).apply();
			new LaunchProfileManager(context).setSelectedProfileCompatPack("", "", selectionSource);
			writeSelectedCompatJson("", null);
			return;
		}
		CompatPack pack = readSelectionPack(new File(getCompatPacksRootDir(), packId), targetId);
		if (pack == null || !pack.ready) {
			throw new IOException("Compat pack is not installed or incomplete: " + packId + (TextUtils.isEmpty(targetId) ? "" : "/" + targetId));
		}
		prefs().edit().remove(KEY_SELECTED_COMPAT_PACK_ID).apply();
		LaunchProfileManager profiles = new LaunchProfileManager(context);
		profiles.setSelectedProfileCompatPack(pack.packId, pack.targetId, selectionSource);
		profiles.refreshAutomaticOfflineCompatSelection();
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
		boolean offlineWildcard = pack.isOfflineWildcard();
		int score = scoreExplicitMatch(identity.version, identity.sts2DllSha256,
			offlineWildcard ? "" : pack.targetVersion, pack.targetVersions, pack.targetSts2DllSha256s);
		return score != 0 ? score : offlineWildcard ? 10 : 0;
	}

	static int scoreExplicitMatch(String payloadVersion, String payloadSha, String targetVersion,
			List<String> targetVersions, List<String> targetShas) {
		if (TextUtils.isEmpty(payloadVersion)) {
			return 0;
		}
		if (!TextUtils.isEmpty(payloadSha)) {
			for (String hash : targetShas) {
				if (payloadSha.equalsIgnoreCase(hash)) {
					return 400;
				}
			}
		}
		if (!"*".equals(targetVersion) && payloadVersion.equalsIgnoreCase(targetVersion)) {
			return 300;
		}
		for (String version : targetVersions) {
			if (!"*".equals(version) && payloadVersion.equalsIgnoreCase(version)) {
				return 200;
			}
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
		return installBundledCompatPacks(false);
	}

	public int installBundledCompatPacks(boolean forceOverwrite) throws Exception {
		return installBundledCompatPacksWithResult(forceOverwrite).changedCount;
	}

	public BundledInstallResult installBundledCompatPacksWithResult() throws Exception {
		return installBundledCompatPacksWithResult(false);
	}

	private BundledInstallResult installBundledCompatPacksWithResult(boolean forceOverwrite) throws Exception {
		int installed = 0;
		boolean preserved = false;
		try {
			String[] names;
			try {
				names = bundledAssets == null ? context.getAssets().list(COMPAT_ASSET_DIR) : bundledAssets.list();
			} catch (IOException exception) {
				Log.w(TAG, "Unable to list bundled compatibility packs.", exception);
				return new BundledInstallResult(0, false);
			}
			if (names == null || names.length == 0) {
				return new BundledInstallResult(0, false);
			}
			File importRoot = getImportRootDir();
			FileBrowserSupport.ensureDirectory(importRoot);
			InstallPolicy policy = forceOverwrite ? InstallPolicy.FORCE_BUNDLED : InstallPolicy.DEFAULT;
			for (String name : names) {
				if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
					continue;
				}
				File tempZip = new File(importRoot, "bundled-compat-" + UUID.randomUUID() + ".zip");
				try {
					try (InputStream input = bundledAssets == null ? context.getAssets().open(COMPAT_ASSET_DIR + "/" + name) : bundledAssets.open(name)) {
						copyStreamToFile(input, tempZip);
					}
					InstallResult result = installFromZipFile(tempZip, "bundled_asset", name, policy);
					if (result.changed) {
						installed++;
					}
					preserved |= result.preserved;
				} finally {
					FileBrowserSupport.deleteRecursively(tempZip);
				}
			}
			return new BundledInstallResult(installed, preserved);
		} finally {
			refreshCompatSelections(false);
		}
	}

	private void refreshCompatSelections(boolean syncLaunchContext) {
		LaunchProfileManager profiles = new LaunchProfileManager(context);
		try {
			int migrated = profiles.migrateLegacyBundledCompatSelectionsToFlatPack();
			if (migrated > 0) {
				Log.i(TAG, "Migrated launch profiles to flat compatibility pack: " + migrated);
			}
		} catch (Exception exception) {
			Log.w(TAG, "Unable to migrate legacy bundled compatibility pack selections.", exception);
		}
		try {
			profiles.refreshAutomaticOfflineCompatSelection();
			if (syncLaunchContext) {
				profiles.refreshSelectedLaunchContext();
			}
		} catch (Exception exception) {
			// The installed pack remains valid; the next launch revalidates the selected context.
			Log.w(TAG, "Unable to refresh compatibility pack selections.", exception);
		}
	}

	public CompatPack importCompatPack(Uri uri) throws Exception {
		String displayName = queryDisplayName(uri);
		if (TextUtils.isEmpty(displayName)) {
			displayName = "compat-pack.zip";
		}
		File importRoot = getImportRootDir();
		FileBrowserSupport.ensureDirectory(importRoot);
		File tempZip = new File(importRoot, "compat-" + UUID.randomUUID() + ".zip");
		try {
			try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
				if (inputStream == null) {
					throw new IOException("Unable to open selected compatibility pack.");
				}
				copyStreamToFile(inputStream, tempZip);
			}
			CompatPack installed = installFromZipFile(tempZip, "saf_zip", displayName, InstallPolicy.DEFAULT).pack;
			new LaunchProfileManager(context).refreshAutomaticOfflineCompatSelection();
			return installed;
		} finally {
			FileBrowserSupport.deleteRecursively(tempZip);
		}
	}

	public void deletePack(String packId) throws Exception {
		if (TextUtils.isEmpty(packId)) {
			return;
		}
		File dir = new File(getCompatPacksRootDir(), packId);
		synchronized (PACK_STORAGE_LOCK) {
			if (dir.isDirectory()) {
				FileBrowserSupport.deleteRecursively(dir);
			}
		}
		if (packId.equals(getSelectedPackId())) {
			writeSelectedCompatJson(packId, null);
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
		String dllSha = identity == null ? "" : identity.optString("sts2_dll_sha256", "");
		if (TextUtils.isEmpty(dllSha) && game != null) {
			dllSha = game.optString("sts2_dll_sha256", "");
		}
		return new PayloadIdentity(version, dllSha);
	}

	public CompatPack installFromVerifiedDownload(File zipFile, String displayName, CompatPackRelease releaseMetadata) throws Exception {
		return installFromVerifiedDownload(zipFile, displayName, releaseMetadata, null);
	}

	CompatPack installFromVerifiedDownload(File zipFile, String displayName, CompatPackRelease releaseMetadata,
			CancellationSignal cancellation) throws Exception {
		if (releaseMetadata == null) {
			throw new IOException("Verified compatibility release metadata is required.");
		}
		if (!releaseMetadata.isInstallable(BuildConfig.VERSION_CODE)) {
			throw new LauncherUpdateRequiredException(releaseMetadata.minLauncherVersionCode,
				releaseMetadata.minLauncherVersionName, releaseMetadata.manifestSchema);
		}
		CompatPack installed = installFromZipFile(zipFile, "github_release", displayName,
			new InstallPolicy(false, releaseMetadata, cancellation)).pack;
		refreshCompatSelections(true);
		return installed;
	}

	private InstallResult installFromZipFile(File zipFile, String sourceKind, String sourceName, InstallPolicy policy) throws Exception {
		CompatPackRelease release = policy.release;
		CancellationSignal cancellation = policy.cancellation;
		throwIfCancelled(cancellation);
		if (release != null && zipFile.length() != release.zipSizeBytes) {
			throw new IOException("Compatibility pack ZIP does not match the signed size.");
		}
		String zipSha256 = sha256(zipFile, cancellation);
		if (release != null && !zipSha256.equalsIgnoreCase(release.zipSha256)) {
			throw new IOException("Compatibility pack ZIP does not match the signed SHA-256.");
		}
		File importRoot = getImportRootDir();
		File stagingRoot = new File(importRoot, "compat-staging-" + UUID.randomUUID());
		FileBrowserSupport.ensureDirectory(stagingRoot);
		try {
			extractZipSafely(zipFile, stagingRoot, policy);
			File packRoot = release == null ? locatePackRoot(stagingRoot) : locateReleasePackRoot(stagingRoot, release.packId);
			JSONObject manifest = new JSONObject(readTextFile(new File(packRoot, MANIFEST_FILE_NAME)));
			String packId = sanitizeId(manifest.optString("pack_id", packRoot.getName()));
			if (TextUtils.isEmpty(packId)) {
				throw new IOException("Compatibility pack manifest has no pack_id.");
			}
			validatePackHeader(manifest);
			if (release != null) {
				validateReleaseManifest(manifest, release);
			}
			validatePackArtifacts(packRoot, manifest, release != null, cancellation);
			manifest.put("installed_at_unix", System.currentTimeMillis() / 1000L);
			JSONObject source = new JSONObject();
			source.put("kind", sourceKind);
			source.put("display_name", sourceName == null ? "" : sourceName);
			source.put("zip_sha256", zipSha256);
			if (release != null) {
				source.put("release_tag", release.tagName);
				source.put("compat_revision", release.compatRevision);
				source.put("release_url", release.releaseUrl);
				source.put("key_id", release.keyId);
			}
			manifest.put("installed_source", source);
			writeTextFile(new File(packRoot, MANIFEST_FILE_NAME), manifest.toString(2));
			synchronized (PACK_STORAGE_LOCK) {
				throwIfCancelled(cancellation); // From here, the commit runs to completion.
				boolean bundled = "bundled_asset".equals(sourceKind);
				File targetDir = new File(getCompatPacksRootDir(), packId);
				CompatPack existing = readReadyFamilyForInstall(targetDir);
				if (existing != null) {
					JSONObject previous = existing.manifest.optJSONObject("installed_source");
					String previousKind = previous == null ? "" : previous.optString("kind", "");
					boolean sameZip = previous != null && zipSha256.equalsIgnoreCase(previous.optString("zip_sha256", ""));
					boolean sameProvenance = release == null || (sourceKind.equals(previousKind)
						&& release.tagName.equals(previous.optString("release_tag")) && release.keyId.equals(previous.optString("key_id")));
					if (sameZip && sameProvenance && (!policy.forceOverwrite || sourceKind.equals(previousKind))) {
						return new InstallResult(existing, false, bundled && !sourceKind.equals(previousKind));
					}
					if (bundled && !policy.forceOverwrite) {
						Integer order = CompatPackVersionComparator.compare(manifest.optInt("compat_revision", 0),
							manifest.optString("compat_version", ""), existing.manifest.optInt("compat_revision", 0), existing.compatVersion);
						boolean existingBundled = sourceKind.equals(previousKind);
						if (existingBundled ? order != null && order < 0 : order == null || order <= 0) {
							Log.i(TAG, "Preserving installed " + packId + " " + existing.compatVersion + " (" + previousKind
								+ "); bundled version " + manifest.optString("compat_version", "") + " does not qualify to replace it.");
							return new InstallResult(existing, false, true);
						}
					}
				}
				return new InstallResult(replaceInstalledPack(packRoot, targetDir, importRoot), true, false);
			}
		} finally {
			FileBrowserSupport.deleteRecursively(stagingRoot);
		}
	}

	private CompatPack readReadyFamilyForInstall(File targetDir) {
		try {
			List<CompatPack> packs = readPacks(targetDir);
			return allTargetsReady(packs) ? packs.get(0) : null;
		} catch (Exception exception) {
			Log.w(TAG, "Unreadable installed pack; allowing repair of " + targetDir.getName(), exception);
			return null;
		}
	}

	private static boolean allTargetsReady(List<CompatPack> packs) {
		if (packs.isEmpty()) {
			return false;
		}
		for (CompatPack pack : packs) {
			if (!pack.ready) {
				return false;
			}
		}
		return true;
	}

	private CompatPack replaceInstalledPack(File packRoot, File targetDir, File importRoot) throws Exception {
		FileBrowserSupport.ensureDirectory(getCompatPacksRootDir());
		File backup = null;
		if (targetDir.exists()) {
			backup = new File(importRoot, "compat-backup-" + UUID.randomUUID());
			if (!targetDir.renameTo(backup)) {
				throw new IOException("Unable to back up existing compatibility pack: " + targetDir.getName());
			}
		}
		boolean published = false;
		try {
			// Both directories are on the same private filesystem. Never expose a partial copy.
			if (!packRoot.renameTo(targetDir)) {
				throw new IOException("Unable to publish compatibility pack: " + targetDir.getName());
			}
			published = true;
			List<CompatPack> installed = readPacks(targetDir);
			if (!allTargetsReady(installed)) {
				throw new IOException("Installed compatibility pack is incomplete: " + targetDir.getName());
			}
			FileBrowserSupport.deleteRecursively(backup);
			return installed.get(0);
		} catch (Exception exception) {
			if (published) {
				FileBrowserSupport.deleteRecursively(targetDir);
			}
			if (backup != null && !backup.renameTo(targetDir)) {
				exception.addSuppressed(new IOException("Could not restore previous pack; it remains at " + backup));
			}
			throw exception;
		}
	}


	private CompatPack readSelectionPack(File dir, String requestedTargetId) throws Exception {
		CompatPack pack = readPack(dir, requestedTargetId);
		if (pack != null && pack.manifest.optInt("schema", 1) >= 2 && TextUtils.isEmpty(sanitizeId(requestedTargetId))) {
			return null;
		}
		return pack;
	}

	private CompatPack readPack(File dir, String requestedTargetId) throws Exception {
		List<CompatPack> packs;
		synchronized (PACK_STORAGE_LOCK) {
			packs = readPacks(dir);
		}
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
				File dll = resolvePackPath(dir, readArtifactPath(target, "dll", ARTIFACTS_DIR_NAME + "/" + targetId + "/" + ENTRY_DLL));
				File overlay = resolvePackPath(dir, readArtifactPath(target, "overlay_pck", ARTIFACTS_DIR_NAME + "/" + targetId + "/" + ENTRY_OVERLAY_PCK));
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
					isCompleteFile(dll) && isCompleteFile(overlay)
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
			isCompleteFile(dll) && isCompleteFile(overlay)
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

	private void validatePackHeader(JSONObject manifest) throws IOException {
		int schema = positiveManifestInt(manifest, "schema", 1);
		int minCode = positiveManifestInt(manifest, "min_launcher_version_code", 0);
		positiveManifestInt(manifest, "compat_revision", 0);
		if (schema > MAX_MANIFEST_SCHEMA || minCode > BuildConfig.VERSION_CODE) {
			throw new LauncherUpdateRequiredException(minCode, manifest.optString("min_launcher_version_name", ""), schema);
		}
	}

	private int positiveManifestInt(JSONObject manifest, String key, int fallback) throws IOException {
		if (!manifest.has(key)) {
			return fallback;
		}
		Object value = manifest.opt(key);
		if (value instanceof Integer || value instanceof Long) {
			long number = ((Number) value).longValue();
			if (number > 0 && number <= Integer.MAX_VALUE) {
				return (int) number;
			}
		}
		throw new IOException("Invalid compatibility manifest field: " + key);
	}

	private void validateReleaseManifest(JSONObject manifest, CompatPackRelease release) throws IOException {
		if (!release.packId.equals(manifest.opt("pack_id"))
				|| !release.compatVersion.equals(manifest.opt("compat_version"))
				|| manifest.optInt("schema", 0) != MAX_MANIFEST_SCHEMA || release.manifestSchema != MAX_MANIFEST_SCHEMA
				|| manifest.optInt("compat_revision", 0) != release.compatRevision
				|| manifest.optInt("min_launcher_version_code", 0) != release.minLauncherVersionCode
				|| PACK_KIND_OFFLINE_BOOTSTRAP.equals(manifest.optString("pack_kind"))) {
			throw new IOException("Compatibility pack manifest does not match the signed release.");
		}
		JSONArray targets = manifest.optJSONArray("targets");
		if (targets == null || targets.length() != release.targets.size()) {
			throw new IOException("Compatibility pack targets do not match the signed release.");
		}
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < targets.length(); i++) {
			JSONObject target = targets.optJSONObject(i);
			String id = target == null ? "" : target.optString("target_id", "");
			CompatPackReleaseTarget signed = null;
			for (CompatPackReleaseTarget candidate : release.targets) {
				if (candidate.targetId.equals(id)) {
					signed = candidate;
					break;
				}
			}
			if (signed == null || !seen.add(id) || !id.equals(sanitizeId(id))) {
				throw new IOException("Unexpected compatibility target in the downloaded pack: " + id);
			}
			List<String> versions = readTargetVersions(target);
			String version = target.optString("version", "");
			if (version.isEmpty() && !versions.isEmpty()) {
				version = versions.get(0);
			}
			Set<String> signedVersions = caseInsensitiveSet(signed.versions);
			if (!signed.version.isEmpty()) {
				signedVersions.add(signed.version.toLowerCase(Locale.ROOT));
			}
			if (!version.equalsIgnoreCase(signed.version) || !caseInsensitiveSet(versions).equals(signedVersions)
					|| !caseInsensitiveSet(readTargetDllShas(target)).equals(caseInsensitiveSet(signed.sts2DllSha256s))) {
				throw new IOException("Compatibility target match data differs from the signed release: " + id);
			}
		}
	}

	private static Set<String> caseInsensitiveSet(List<String> values) {
		Set<String> result = new HashSet<>();
		for (String value : values) {
			result.add(value.toLowerCase(Locale.ROOT));
		}
		return result;
	}

	private void validatePackArtifacts(File packRoot, JSONObject manifest, boolean requireSha256Sums,
			CancellationSignal cancellation) throws Exception {
		validatePackManifestMatchRules(manifest);
		Set<String> requiredPaths = new HashSet<>();
		if (manifest.optInt("schema", 1) == 2) {
			JSONArray targets = manifest.optJSONArray("targets");
			if (targets == null || targets.length() == 0) {
				throw new IOException("Compatibility pack manifest has no targets.");
			}
			Set<String> targetIds = new HashSet<>();
			for (int i = 0; i < targets.length(); i++) {
				JSONObject target = targets.optJSONObject(i);
				if (target == null) {
					throw new IOException("Invalid compatibility pack target.");
				}
				String targetId = sanitizeId(target.optString("target_id", target.optString("id", "")));
				if (TextUtils.isEmpty(targetId)) {
					targetId = "target-" + (i + 1);
				}
				if (!targetIds.add(targetId)) {
					throw new IOException("Duplicate compatibility target: " + targetId);
				}
				String prefix = ARTIFACTS_DIR_NAME + "/" + targetId + "/";
				requiredPaths.add(readArtifactPath(target, "dll", prefix + ENTRY_DLL));
				requiredPaths.add(readArtifactPath(target, "overlay_pck", prefix + ENTRY_OVERLAY_PCK));
			}
			requiredPaths.add(MANIFEST_FILE_NAME);
		} else {
			// Historical schema 1 releases listed only these two files in SHA256SUMS.
			requiredPaths.add(ENTRY_DLL);
			requiredPaths.add(ENTRY_OVERLAY_PCK);
		}
		for (String path : requiredPaths) {
			requireFile(resolvePackPath(packRoot, path), "Missing compatibility pack file: " + path);
		}
		File sums = new File(packRoot, CHECKSUM_FILE_NAME);
		if (requireSha256Sums || sums.exists()) {
			verifyPackChecksums(packRoot, sums, requiredPaths, cancellation);
		}
	}

	private void verifyPackChecksums(File packRoot, File sums, Set<String> requiredPaths,
			CancellationSignal cancellation) throws Exception {
		requireFile(sums, "Missing compatibility pack SHA256SUMS.");
		Set<String> seen = new HashSet<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(sums), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				throwIfCancelled(cancellation);
				Matcher match = CHECKSUM_LINE.matcher(line);
				if (!match.matches()) {
					throw new IOException("Invalid compatibility pack SHA256SUMS line.");
				}
				String path = match.group(2);
				File file = resolvePackPath(packRoot, path);
				if (!seen.add(path) || CHECKSUM_FILE_NAME.equals(path) || !file.isFile()
						|| !sha256(file, cancellation).equalsIgnoreCase(match.group(1))) {
					throw new IOException("Compatibility pack SHA-256 verification failed: " + path);
				}
			}
		}
		if (!seen.containsAll(requiredPaths)) {
			throw new IOException("Compatibility pack SHA256SUMS does not cover its manifest and required artifacts.");
		}
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

	private File locateReleasePackRoot(File stagingRoot, String packId) throws IOException {
		File nested = new File(stagingRoot, packId);
		File root = new File(stagingRoot, MANIFEST_FILE_NAME).isFile() ? stagingRoot : nested;
		requireFile(new File(root, MANIFEST_FILE_NAME), "Compatibility release manifest is missing.");
		return root;
	}

	private void extractZipSafely(File zipFile, File targetRoot, InstallPolicy policy) throws IOException {
		boolean remote = policy.release != null;
		Set<String> seen = new HashSet<>();
		long total = 0;
		byte[] buffer = new byte[64 * 1024];
		try (ZipFile archive = new ZipFile(zipFile)) {
			if (remote && archive.size() > MAX_REMOTE_ZIP_ENTRIES) {
				throw new IOException("Too many files in the compatibility release ZIP.");
			}
			Enumeration<? extends ZipEntry> entries = archive.entries();
			while (entries.hasMoreElements()) {
				throwIfCancelled(policy.cancellation);
				ZipEntry entry = entries.nextElement();
				String name = remote ? entry.getName() : normalizeEntryName(entry.getName());
				if (!remote && (name.isEmpty() || name.startsWith("__MACOSX/") || name.contains(":"))) {
					continue;
				}
				if (entry.isDirectory() && name.endsWith("/")) {
					name = name.substring(0, name.length() - 1);
				}
				File out = resolvePackPath(targetRoot, name);
				if (!seen.add(name)) {
					throw new IOException("Duplicate compatibility pack archive entry: " + name);
				}
				if (entry.isDirectory()) {
					FileBrowserSupport.ensureDirectory(out);
					continue;
				}
				FileBrowserSupport.ensureDirectory(out.getParentFile());
				boolean metadata = MANIFEST_FILE_NAME.equals(out.getName()) || CHECKSUM_FILE_NAME.equals(out.getName());
				try (InputStream input = archive.getInputStream(entry); OutputStream output = new BufferedOutputStream(new FileOutputStream(out))) {
					long entrySize = 0;
					int read;
					while ((read = input.read(buffer)) != -1) {
						throwIfCancelled(policy.cancellation);
						total += read;
						entrySize += read;
						if (remote && (total > MAX_REMOTE_UNZIPPED_BYTES || (metadata && entrySize > MAX_REMOTE_METADATA_BYTES))) {
							throw new IOException("Compatibility release exceeds its unpacked size limit.");
						}
						output.write(buffer, 0, read);
					}
				}
			}
		}
	}

	private static File resolvePackPath(File root, String name) throws IOException {
		if (name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
			throw new IOException("Invalid compatibility pack path: " + name);
		}
		for (int i = 0; i < name.length(); i++) {
			if (Character.isISOControl(name.charAt(i))) {
				throw new IOException("Invalid compatibility pack path.");
			}
		}
		for (String component : name.split("/", -1)) {
			if (component.isEmpty() || component.equals(".") || component.equals("..")) {
				throw new IOException("Invalid compatibility pack path: " + name);
			}
		}
		File file = new File(root, name);
		if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
			throw new IOException("Compatibility pack path escapes its directory: " + name);
		}
		return file;
	}

	private static void throwIfCancelled(CancellationSignal cancellation) {
		if (cancellation != null) {
			cancellation.throwIfCanceled();
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
		if (!isCompleteFile(file)) {
			throw new IOException(message);
		}
	}

	private static boolean isCompleteFile(File file) {
		return file.isFile() && file.length() > 0;
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

	private String sha256(File file, CancellationSignal cancellation) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				throwIfCancelled(cancellation);
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
		char[] hex = new char[digest.length * 2];
		for (int i = 0; i < digest.length; i++) {
			hex[2 * i] = Character.forDigit((digest[i] & 0xff) >>> 4, 16);
			hex[2 * i + 1] = Character.forDigit(digest[i] & 0xf, 16);
		}
		return new String(hex);
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

	interface BundledAssets {
		String[] list() throws IOException;
		InputStream open(String name) throws IOException;
	}

	public static final class BundledInstallResult {
		public final int changedCount;
		public final boolean preservedNewerOrExternal;

		private BundledInstallResult(int changedCount, boolean preservedNewerOrExternal) {
			this.changedCount = changedCount;
			this.preservedNewerOrExternal = preservedNewerOrExternal;
		}
	}

	public static final class LauncherUpdateRequiredException extends IOException {
		public final int minLauncherVersionCode;
		public final String minLauncherVersionName;
		public final int manifestSchema;

		LauncherUpdateRequiredException(int minLauncherVersionCode, String minLauncherVersionName, int manifestSchema) {
			super("Compatibility pack requires a newer launcher (manifest schema " + manifestSchema
				+ ", minimum launcher version code " + minLauncherVersionCode + ").");
			this.minLauncherVersionCode = minLauncherVersionCode;
			this.minLauncherVersionName = minLauncherVersionName;
			this.manifestSchema = manifestSchema;
		}
	}

	private static final class InstallPolicy {
		static final InstallPolicy DEFAULT = new InstallPolicy(false, null, null);
		static final InstallPolicy FORCE_BUNDLED = new InstallPolicy(true, null, null);

		final boolean forceOverwrite;
		final CompatPackRelease release;
		final CancellationSignal cancellation;

		InstallPolicy(boolean forceOverwrite, CompatPackRelease release, CancellationSignal cancellation) {
			this.forceOverwrite = forceOverwrite;
			this.release = release;
			this.cancellation = cancellation;
		}
	}

	private static final class InstallResult {
		final CompatPack pack;
		final boolean changed;
		final boolean preserved;

		InstallResult(CompatPack pack, boolean changed, boolean preserved) {
			this.pack = pack;
			this.changed = changed;
			this.preserved = preserved;
		}
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
