package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Owns the final multi-version launch model.
 *
 * <p>Game bodies are immutable payloads under {@code files/payloads/<id>/game}.
 * Launch profiles under {@code files/instances/<id>} bind one payload to an
 * optional compatibility pack and choose whether saves/settings and MOD files
 * come from the legacy global roots or from the profile's isolated roots.</p>
 */
public final class LaunchProfileManager {
	public static final String PAYLOADS_DIR_NAME = "payloads";
	public static final String INSTANCES_DIR_NAME = "instances";
	public static final String PROFILE_FILE_NAME = "instance.json";
	public static final String SAVE_MODE_GLOBAL = "global";
	public static final String SAVE_MODE_ISOLATED = "isolated";
	public static final String MODS_MODE_GLOBAL = "global";
	public static final String MODS_MODE_ISOLATED = "isolated";

	private static final String TAG = "Sts2LaunchProfiles";
	private static final String PREFS_NAME = "sts2_version_manager";
	private static final String KEY_SELECTED_LAUNCH_PROFILE_ID = "selected_launch_profile_id";
	private static final String LEGACY_KEY_SELECTED_GAME_VERSION_ID = "selected_game_version_id";

	private final Context context;

	public LaunchProfileManager(Context context) {
		this.context = context.getApplicationContext();
	}

	public synchronized void bootstrapIfNeeded() {
		try {
			FileBrowserSupport.ensureDirectory(getPayloadsRootDir());
			FileBrowserSupport.ensureDirectory(getProfilesRootDir());
			migrateLegacyGameDirIfPresent();
			migrateLegacyArchivedVersionsIfPresent();
			ensureProfileExistsForEveryPayloadIfNoProfiles();
			ensureSelectedProfileIfPossible();
		} catch (Exception exception) {
			Log.w(TAG, "Unable to bootstrap launch profile state.", exception);
		}
	}

	public List<GamePayload> listPayloads() {
		bootstrapIfNeeded();
		List<GamePayload> payloads = new ArrayList<>();
		File[] children = getPayloadsRootDir().listFiles(File::isDirectory);
		if (children == null) {
			return payloads;
		}
		for (File child : children) {
			try {
				GamePayload payload = readPayload(child.getName());
				if (payload != null) {
					payloads.add(payload);
				}
			} catch (Exception ignored) {
			}
		}
		payloads.sort(Comparator.comparing((GamePayload payload) -> payload.label, String.CASE_INSENSITIVE_ORDER));
		return payloads;
	}

	public GamePayload readPayload(String payloadId) throws Exception {
		if (TextUtils.isEmpty(payloadId)) {
			return null;
		}
		File payloadDir = new File(getPayloadsRootDir(), sanitizeId(payloadId));
		File gameDir = new File(payloadDir, PayloadManager.GAME_DIR_NAME);
		File manifestFile = new File(gameDir, PayloadManager.MANIFEST_FILE_NAME);
		if (!manifestFile.isFile()) {
			return null;
		}
		JSONObject manifest = new JSONObject(FileBrowserSupport.readTextFile(manifestFile));
		PayloadIdentity identity = readPayloadIdentity(manifest);
		String label = buildPayloadLabel(identity, payloadDir.getName());
		File pck = new File(gameDir, PayloadManager.PCK_FILE_NAME);
		File dll = new File(gameDir, PayloadManager.STS2_DLL_PATH);
		JSONObject game = manifest.optJSONObject("game");
		long pckSize = game == null ? pck.length() : game.optLong("pck_size", pck.length());
		long dllSize = game == null ? dll.length() : game.optLong("dll_size", dll.length());
		int fileCount = game == null ? 0 : game.optInt("file_count", 0);
		long totalBytes = game == null ? 0L : game.optLong("total_uncompressed_bytes", 0L);
		return new GamePayload(
			payloadDir.getName(),
			label,
			identity.version,
			identity.commit,
			identity.sts2DllSha256,
			identity.pckSha256,
			payloadDir,
			gameDir,
			manifest,
			pck.isFile() && dll.isFile(),
			payloadDir.lastModified() / 1000L,
			pckSize,
			dllSize,
			fileCount,
			totalBytes
		);
	}

	public GamePayload readPayloadFromGameDir(File gameDir) throws Exception {
		if (gameDir == null || !gameDir.isDirectory()) {
			return null;
		}
		File manifestFile = new File(gameDir, PayloadManager.MANIFEST_FILE_NAME);
		if (!manifestFile.isFile()) {
			return null;
		}
		JSONObject manifest = new JSONObject(FileBrowserSupport.readTextFile(manifestFile));
		String id = buildPayloadId(manifest);
		return readPayload(id);
	}

	public File getPayloadGameDir(String payloadId) {
		return new File(new File(getPayloadsRootDir(), sanitizeId(payloadId)), PayloadManager.GAME_DIR_NAME);
	}

	public String buildPayloadId(JSONObject manifest) {
		PayloadIdentity identity = readPayloadIdentity(manifest);
		String version = TextUtils.isEmpty(identity.version) ? "unknown" : identity.version;
		String commit = TextUtils.isEmpty(identity.commit) ? "" : identity.commit;
		String sha = !TextUtils.isEmpty(identity.sts2DllSha256) ? identity.sts2DllSha256 : identity.pckSha256;
		String suffix = TextUtils.isEmpty(sha) ? "" : "-" + sha.substring(0, Math.min(12, sha.length()));
		return sanitizeId("sts2-" + version + (TextUtils.isEmpty(commit) ? "" : "-" + commit) + suffix);
	}

	public List<LaunchProfile> listProfiles() {
		bootstrapIfNeeded();
		List<LaunchProfile> profiles = new ArrayList<>();
		File[] children = getProfilesRootDir().listFiles(File::isDirectory);
		if (children == null) {
			return profiles;
		}
		for (File child : children) {
			try {
				LaunchProfile profile = readProfile(child.getName());
				if (profile != null) {
					profiles.add(profile);
				}
			} catch (Exception ignored) {
			}
		}
		profiles.sort(Comparator.comparing((LaunchProfile profile) -> profile.displayName, String.CASE_INSENSITIVE_ORDER));
		return profiles;
	}

	public LaunchProfile getSelectedProfile() {
		bootstrapIfNeeded();
		String id = getSelectedProfileId();
		if (TextUtils.isEmpty(id)) {
			return null;
		}
		try {
			return readProfile(id);
		} catch (Exception ignored) {
			return null;
		}
	}

	public String getSelectedProfileId() {
		String selected = prefs().getString(KEY_SELECTED_LAUNCH_PROFILE_ID, "");
		if (!TextUtils.isEmpty(selected)) {
			return selected;
		}
		return prefs().getString(LEGACY_KEY_SELECTED_GAME_VERSION_ID, "");
	}

	public LaunchProfile readProfile(String profileId) throws Exception {
		if (TextUtils.isEmpty(profileId)) {
			return null;
		}
		File dir = new File(getProfilesRootDir(), sanitizeId(profileId));
		File file = new File(dir, PROFILE_FILE_NAME);
		if (!file.isFile()) {
			return null;
		}
		JSONObject json = new JSONObject(FileBrowserSupport.readTextFile(file));
		String id = sanitizeId(json.optString("id", dir.getName()));
		String payloadId = sanitizeId(json.optString("payload_id", ""));
		GamePayload payload = readPayload(payloadId);
		String saveMode = normalizeSaveMode(json.optString("save_mode", SAVE_MODE_GLOBAL));
		String modsMode = normalizeModsMode(json.optString("mods_mode", MODS_MODE_GLOBAL));
		String displayName = json.optString("display_name", "").trim();
		if (TextUtils.isEmpty(displayName)) {
			displayName = payload == null ? id : payload.label;
		}
		String compatPackId = sanitizeOptionalId(json.optString("compat_pack_id", ""));
		long createdAt = json.optLong("created_at_unix", dir.lastModified() / 1000L);
		long updatedAt = json.optLong("updated_at_unix", dir.lastModified() / 1000L);
		return new LaunchProfile(id, displayName, payloadId, compatPackId, saveMode, modsMode, dir, json, payload, createdAt, updatedAt, payload != null && payload.ready);
	}

	public LaunchProfile createProfile(String payloadId, String displayName, String saveMode, String modsMode, boolean select) throws Exception {
		return createProfile(payloadId, displayName, saveMode, modsMode, null, select);
	}

	public LaunchProfile createProfile(String payloadId, String displayName, String saveMode, String modsMode, String compatPackId, boolean select) throws Exception {
		GamePayload payload = readPayload(payloadId);
		if (payload == null || !payload.ready) {
			throw new IOException("Game payload is missing or incomplete: " + payloadId);
		}
		String name = TextUtils.isEmpty(displayName) ? payload.label : displayName.trim();
		String id = buildUniqueProfileId(name + "-" + UUID.randomUUID().toString().substring(0, 8));
		String selectedCompatPackId = compatPackId == null ? findBestCompatPackId(payload) : sanitizeOptionalId(compatPackId);
		LaunchProfile profile = writeProfile(id, name, payload.id, selectedCompatPackId, normalizeSaveMode(saveMode), normalizeModsMode(modsMode), System.currentTimeMillis() / 1000L);
		ensureProfileDirectories(profile);
		if (select) {
			selectProfile(profile.id);
		}
		return readProfile(profile.id);
	}

	public LaunchProfile createOrSelectDefaultProfileForPayload(GamePayload payload, boolean select) throws Exception {
		if (payload == null || !payload.ready) {
			throw new IOException("Game payload is missing or incomplete.");
		}
		LaunchProfile existing = findFirstProfileForPayload(payload.id);
		if (existing != null) {
			if (select) {
				selectProfile(existing.id);
			}
			return readProfile(existing.id);
		}
		String baseId = buildUniqueProfileId("profile-" + payload.id);
		String compatPackId = findBestCompatPackId(payload);
		LaunchProfile profile = writeProfile(baseId, payload.label, payload.id, compatPackId, SAVE_MODE_GLOBAL, MODS_MODE_GLOBAL, System.currentTimeMillis() / 1000L);
		ensureProfileDirectories(profile);
		if (select) {
			selectProfile(profile.id);
		}
		return readProfile(profile.id);
	}

	public LaunchProfile updateProfile(String profileId, String displayName, String saveMode, String modsMode, String compatPackId) throws Exception {
		LaunchProfile profile = readProfile(profileId);
		return updateProfile(profileId, profile == null ? "" : profile.payloadId, displayName, saveMode, modsMode, compatPackId);
	}

	public LaunchProfile updateProfile(String profileId, String payloadId, String displayName, String saveMode, String modsMode, String compatPackId) throws Exception {
		LaunchProfile profile = readProfile(profileId);
		if (profile == null) {
			throw new IOException("Launch profile not found: " + profileId);
		}
		String name = TextUtils.isEmpty(displayName) ? profile.displayName : displayName.trim();
		String normalizedPayloadId = TextUtils.isEmpty(payloadId) ? profile.payloadId : sanitizeId(payloadId);
		String normalizedCompatPackId = sanitizeOptionalId(compatPackId);
		LaunchProfile updated = writeProfile(profile.id, name, normalizedPayloadId, normalizedCompatPackId, normalizeSaveMode(saveMode), normalizeModsMode(modsMode), profile.createdAtUnix);
		ensureProfileDirectories(updated);
		if (profile.id.equals(getSelectedProfileId())) {
			writeSelectedLaunchContextJson(readProfile(profile.id));
		}
		return readProfile(profile.id);
	}

	public void setSelectedProfileCompatPack(String compatPackId) throws Exception {
		LaunchProfile profile = getSelectedProfile();
		if (profile == null) {
			return;
		}
		updateProfile(profile.id, profile.displayName, profile.saveMode, profile.modsMode, compatPackId);
	}

	public void selectProfile(String profileId) throws Exception {
		LaunchProfile profile = readProfile(profileId);
		if (profile == null) {
			throw new IOException("Launch profile not found: " + profileId);
		}
		ensureProfileDirectories(profile);
		prefs().edit()
			.putString(KEY_SELECTED_LAUNCH_PROFILE_ID, profile.id)
			.putString(LEGACY_KEY_SELECTED_GAME_VERSION_ID, profile.payloadId)
			.apply();
		writeSelectedLaunchContextJson(profile);
	}

	public void deleteProfile(String profileId) throws Exception {
		if (TextUtils.isEmpty(profileId)) {
			return;
		}
		File dir = new File(getProfilesRootDir(), sanitizeId(profileId));
		FileBrowserSupport.deleteRecursively(dir);
		if (sanitizeId(profileId).equals(getSelectedProfileId())) {
			prefs().edit().remove(KEY_SELECTED_LAUNCH_PROFILE_ID).apply();
			ensureSelectedProfileIfPossible();
			LaunchProfile selected = getSelectedProfile();
			writeSelectedLaunchContextJson(selected);
		}
	}

	public void deletePayload(String payloadId) throws Exception {
		if (TextUtils.isEmpty(payloadId)) {
			return;
		}
		String normalized = sanitizeId(payloadId);
		FileBrowserSupport.deleteRecursively(new File(getPayloadsRootDir(), normalized));
		ensureSelectedProfileIfPossible();
		writeSelectedLaunchContextJson(getSelectedProfile());
	}

	public void clearSelectedProfileAndUnusedPayload() throws Exception {
		LaunchProfile selected = getSelectedProfile();
		if (selected == null || TextUtils.isEmpty(selected.payloadId)) {
			return;
		}
		deletePayload(selected.payloadId);
	}

	public GamePayload getSelectedPayload() {
		LaunchProfile profile = getSelectedProfile();
		return profile == null ? null : profile.payload;
	}

	public File getSelectedGameDir() {
		LaunchProfile profile = getSelectedProfile();
		if (profile != null) {
			if (profile.payload != null) {
				return profile.payload.gameDir;
			}
			if (!TextUtils.isEmpty(profile.payloadId)) {
				return getPayloadGameDir(profile.payloadId);
			}
			return new File(profile.dir, "missing-game");
		}
		return new File(context.getFilesDir(), "missing-game");
	}

	public File getSelectedManifestFile() {
		return new File(getSelectedGameDir(), PayloadManager.MANIFEST_FILE_NAME);
	}

	public File getSelectedAccountRootDir() {
		LaunchProfile profile = getSelectedProfile();
		if (profile != null && SAVE_MODE_ISOLATED.equals(profile.saveMode)) {
			return new File(profile.dir, "default/1");
		}
		return getGlobalAccountRootDir();
	}

	public File getSelectedModsRootDir() {
		LaunchProfile profile = getSelectedProfile();
		if (profile != null && MODS_MODE_ISOLATED.equals(profile.modsMode)) {
			return new File(profile.dir, "mods");
		}
		return getGlobalModsRootDir();
	}
	public File getSelectedLogsRootDir() {
		LaunchProfile profile = getSelectedProfile();
		if (profile != null) {
			return new File(profile.dir, "logs");
		}
		return new File(context.getFilesDir(), "logs");
	}

	public String getSelectedCompatPackId() {
		LaunchProfile profile = getSelectedProfile();
		return profile == null ? "" : profile.compatPackId;
	}

	public String buildSelectedLaunchContextJson() {
		try {
			LaunchProfile profile = getSelectedProfile();
			JSONObject root = buildLaunchContextJson(profile);
			return root.toString(2);
		} catch (Exception exception) {
			return "{}";
		}
	}

	public File getPayloadsRootDir() {
		return new File(context.getFilesDir(), PAYLOADS_DIR_NAME);
	}

	public File getProfilesRootDir() {
		return new File(context.getFilesDir(), INSTANCES_DIR_NAME);
	}

	private void migrateLegacyGameDirIfPresent() throws Exception {
		File legacyGame = getLegacyActiveGameDir();
		if (!isValidPayloadGameDir(legacyGame)) {
			return;
		}
		JSONObject manifest = new JSONObject(FileBrowserSupport.readTextFile(new File(legacyGame, PayloadManager.MANIFEST_FILE_NAME)));
		String id = buildPayloadId(manifest);
		File targetGame = getPayloadGameDir(id);
		if (!targetGame.exists()) {
			FileBrowserSupport.ensureDirectory(targetGame.getParentFile());
			if (!legacyGame.renameTo(targetGame)) {
				Log.w(TAG, "Unable to move legacy active game into payload store: " + legacyGame.getAbsolutePath());
				return;
			}
			Log.i(TAG, "Migrated legacy active game into payload store: " + id);
		}
		GamePayload payload = readPayload(id);
		if (payload != null) {
			createOrSelectDefaultProfileForPayload(payload, TextUtils.isEmpty(prefs().getString(KEY_SELECTED_LAUNCH_PROFILE_ID, "")));
		}
	}

	private void migrateLegacyArchivedVersionsIfPresent() throws Exception {
		File versionsRoot = new File(context.getFilesDir(), "game-versions");
		File[] children = versionsRoot.listFiles(File::isDirectory);
		if (children == null) {
			return;
		}
		for (File child : children) {
			File gameDir = new File(child, PayloadManager.GAME_DIR_NAME);
			if (!isValidPayloadGameDir(gameDir)) {
				continue;
			}
			try {
				JSONObject manifest = new JSONObject(FileBrowserSupport.readTextFile(new File(gameDir, PayloadManager.MANIFEST_FILE_NAME)));
				String id = buildPayloadId(manifest);
				File targetGame = getPayloadGameDir(id);
				if (!targetGame.exists()) {
					FileBrowserSupport.ensureDirectory(targetGame.getParentFile());
					if (gameDir.renameTo(targetGame)) {
						Log.i(TAG, "Migrated archived game into payload store: " + id);
					} else {
						Log.w(TAG, "Unable to move archived game into payload store: " + gameDir.getAbsolutePath());
						continue;
					}
				}
				GamePayload payload = readPayload(id);
				if (payload != null && findFirstProfileForPayload(payload.id) == null) {
					createOrSelectDefaultProfileForPayload(payload, false);
				}
			} catch (Exception exception) {
				Log.w(TAG, "Unable to migrate archived game version: " + child.getAbsolutePath(), exception);
			}
		}
	}

	private void ensureProfileExistsForEveryPayloadIfNoProfiles() throws Exception {
		File[] profileDirs = getProfilesRootDir().listFiles(File::isDirectory);
		if (profileDirs != null && profileDirs.length > 0) {
			return;
		}
		File[] payloadDirs = getPayloadsRootDir().listFiles(File::isDirectory);
		if (payloadDirs == null) {
			return;
		}
		for (File payloadDir : payloadDirs) {
			GamePayload payload = readPayload(payloadDir.getName());
			if (payload != null && payload.ready) {
				createOrSelectDefaultProfileForPayload(payload, false);
			}
		}
	}

	private void ensureSelectedProfileIfPossible() throws Exception {
		String selectedId = prefs().getString(KEY_SELECTED_LAUNCH_PROFILE_ID, "");
		if (!TextUtils.isEmpty(selectedId) && readProfile(selectedId) != null) {
			return;
		}
		List<LaunchProfile> profiles = listProfilesWithoutBootstrap();
		if (profiles.isEmpty()) {
			prefs().edit().remove(KEY_SELECTED_LAUNCH_PROFILE_ID).remove(LEGACY_KEY_SELECTED_GAME_VERSION_ID).apply();
			return;
		}
		profiles.sort((a, b) -> Long.compare(b.updatedAtUnix, a.updatedAtUnix));
		selectProfile(profiles.get(0).id);
	}

	private List<LaunchProfile> listProfilesWithoutBootstrap() {
		List<LaunchProfile> profiles = new ArrayList<>();
		File[] children = getProfilesRootDir().listFiles(File::isDirectory);
		if (children == null) {
			return profiles;
		}
		for (File child : children) {
			try {
				LaunchProfile profile = readProfileWithoutBootstrap(child.getName());
				if (profile != null) {
					profiles.add(profile);
				}
			} catch (Exception ignored) {
			}
		}
		return profiles;
	}

	private LaunchProfile readProfileWithoutBootstrap(String profileId) throws Exception {
		if (TextUtils.isEmpty(profileId)) {
			return null;
		}
		File dir = new File(getProfilesRootDir(), sanitizeId(profileId));
		File file = new File(dir, PROFILE_FILE_NAME);
		if (!file.isFile()) {
			return null;
		}
		JSONObject json = new JSONObject(FileBrowserSupport.readTextFile(file));
		String id = sanitizeId(json.optString("id", dir.getName()));
		String payloadId = sanitizeId(json.optString("payload_id", ""));
		GamePayload payload = readPayload(payloadId);
		String saveMode = normalizeSaveMode(json.optString("save_mode", SAVE_MODE_GLOBAL));
		String modsMode = normalizeModsMode(json.optString("mods_mode", MODS_MODE_GLOBAL));
		String displayName = json.optString("display_name", "").trim();
		if (TextUtils.isEmpty(displayName)) {
			displayName = payload == null ? id : payload.label;
		}
		String compatPackId = sanitizeOptionalId(json.optString("compat_pack_id", ""));
		long createdAt = json.optLong("created_at_unix", dir.lastModified() / 1000L);
		long updatedAt = json.optLong("updated_at_unix", dir.lastModified() / 1000L);
		return new LaunchProfile(id, displayName, payloadId, compatPackId, saveMode, modsMode, dir, json, payload, createdAt, updatedAt, payload != null && payload.ready);
	}

	private LaunchProfile writeProfile(String id, String displayName, String payloadId, String compatPackId, String saveMode, String modsMode, long createdAtUnix) throws Exception {
		String normalizedId = sanitizeId(id);
		File dir = new File(getProfilesRootDir(), normalizedId);
		FileBrowserSupport.ensureDirectory(dir);
		long now = System.currentTimeMillis() / 1000L;
		JSONObject json = new JSONObject();
		json.put("schema", 1);
		json.put("id", normalizedId);
		json.put("display_name", displayName == null ? "" : displayName.trim());
		json.put("payload_id", sanitizeId(payloadId));
		json.put("compat_pack_id", sanitizeOptionalId(compatPackId));
		json.put("save_mode", normalizeSaveMode(saveMode));
		json.put("mods_mode", normalizeModsMode(modsMode));
		json.put("created_at_unix", createdAtUnix > 0 ? createdAtUnix : now);
		json.put("updated_at_unix", now);
		FileBrowserSupport.writeTextFile(new File(dir, PROFILE_FILE_NAME), json.toString(2));
		return readProfileWithoutBootstrap(normalizedId);
	}

	private void ensureProfileDirectories(LaunchProfile profile) {
		if (profile == null) {
			return;
		}
		FileBrowserSupport.ensureDirectory(getAccountRootDir(profile));
		FileBrowserSupport.ensureDirectory(getModsRootDir(profile));
		FileBrowserSupport.ensureDirectory(new File(profile.dir, "logs"));
	}

	private File getAccountRootDir(LaunchProfile profile) {
		if (profile != null && SAVE_MODE_ISOLATED.equals(profile.saveMode)) {
			return new File(profile.dir, "default/1");
		}
		return getGlobalAccountRootDir();
	}

	private File getModsRootDir(LaunchProfile profile) {
		if (profile != null && MODS_MODE_ISOLATED.equals(profile.modsMode)) {
			return new File(profile.dir, "mods");
		}
		return getGlobalModsRootDir();
	}

	private void writeSelectedLaunchContextJson(LaunchProfile profile) throws Exception {
		File launcherDir = new File(context.getFilesDir(), "launcher");
		FileBrowserSupport.ensureDirectory(launcherDir);
		JSONObject root = buildLaunchContextJson(profile);
		FileBrowserSupport.writeTextFile(new File(launcherDir, "selected_instance.json"), root.toString(2));

		JSONObject legacy = new JSONObject();
		legacy.put("schema", 2);
		if (profile != null) {
			legacy.put("selected_game_version_id", profile.payloadId);
			legacy.put("selected_launch_profile_id", profile.id);
			if (profile.payload != null) {
				legacy.put("selected_game_dir", profile.payload.gameDir.getAbsolutePath());
			}
		}
		FileBrowserSupport.writeTextFile(new File(launcherDir, "selected_game_version.json"), legacy.toString(2));
		new CompatPackManager(context).writeSelectedCompatJsonForProfile(profile == null ? "" : profile.compatPackId);
	}

	private JSONObject buildLaunchContextJson(LaunchProfile profile) throws Exception {
		JSONObject root = new JSONObject();
		root.put("schema", 1);
		root.put("data_dir", context.getFilesDir().getAbsolutePath());
		if (profile == null) {
			return root;
		}
		File selectedGameDir = profile.payload == null ? getPayloadGameDir(profile.payloadId) : profile.payload.gameDir;
		root.put("selected_instance_id", profile.id);
		root.put("selected_profile_id", profile.id);
		root.put("display_name", profile.displayName);
		root.put("payload_id", profile.payloadId);
		root.put("selected_game_dir", selectedGameDir.getAbsolutePath());
		root.put("selected_release_info", new File(selectedGameDir, PayloadManager.RELEASE_INFO_FILE_NAME).getAbsolutePath());
		root.put("save_mode", profile.saveMode);
		root.put("mods_mode", profile.modsMode);
		root.put("selected_account_root", getAccountRootDir(profile).getAbsolutePath());
		root.put("selected_settings_path", new File(getAccountRootDir(profile), "settings.save").getAbsolutePath());
		root.put("selected_mods_dir", getModsRootDir(profile).getAbsolutePath());
		root.put("selected_logs_dir", new File(profile.dir, "logs").getAbsolutePath());
		root.put("compat_pack_id", profile.compatPackId);
		CompatPackManager.CompatPack pack = findInstalledCompatPack(profile.compatPackId);
		if (pack != null) {
			root.put("selected_compat_pack_dir", pack.dir.getAbsolutePath());
			root.put("selected_compat_overlay_pck", pack.overlayPckFile.getAbsolutePath());
			root.put("selected_compat_dll", pack.dllFile.getAbsolutePath());
		}
		return root;
	}

	private String findBestCompatPackId(GamePayload payload) {
		if (payload == null) {
			return "";
		}
		try {
			CompatPackManager manager = new CompatPackManager(context);
			CompatPackManager.CompatPack pack = manager.findBestMatch(payload.manifest, manager.listInstalledPacks());
			return pack == null ? "" : pack.packId;
		} catch (Exception ignored) {
			return "";
		}
	}

	private CompatPackManager.CompatPack findInstalledCompatPack(String packId) {
		if (TextUtils.isEmpty(packId)) {
			return null;
		}
		try {
			for (CompatPackManager.CompatPack pack : new CompatPackManager(context).listInstalledPacks()) {
				if (pack.packId.equals(packId)) {
					return pack;
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private LaunchProfile findFirstProfileForPayload(String payloadId) {
		String normalized = sanitizeId(payloadId);
		for (LaunchProfile profile : listProfilesWithoutBootstrap()) {
			if (normalized.equals(profile.payloadId)) {
				return profile;
			}
		}
		return null;
	}

	private boolean isValidPayloadGameDir(File gameDir) {
		return gameDir != null
			&& new File(gameDir, PayloadManager.MANIFEST_FILE_NAME).isFile()
			&& new File(gameDir, PayloadManager.PCK_FILE_NAME).isFile()
			&& new File(gameDir, PayloadManager.STS2_DLL_PATH).isFile();
	}

	private PayloadIdentity readPayloadIdentity(JSONObject manifest) {
		JSONObject identity = manifest == null ? null : manifest.optJSONObject("identity");
		JSONObject game = manifest == null ? null : manifest.optJSONObject("game");
		JSONObject releaseInfo = identity == null ? null : identity.optJSONObject("release_info");
		if (releaseInfo == null && game != null) {
			releaseInfo = game.optJSONObject("release_info");
		}
		String version = releaseInfo == null ? "" : releaseInfo.optString("version", "");
		if (TextUtils.isEmpty(version) && identity != null) {
			version = identity.optString("version", "");
		}
		String commit = releaseInfo == null ? "" : releaseInfo.optString("commit", "");
		if (TextUtils.isEmpty(commit) && identity != null) {
			commit = identity.optString("commit", "");
		}
		String dllSha = identity == null ? "" : identity.optString("sts2_dll_sha256", "");
		if (TextUtils.isEmpty(dllSha) && game != null) {
			dllSha = game.optString("sts2_dll_sha256", "");
		}
		String pckSha = identity == null ? "" : identity.optString("pck_sha256_after_patch", "");
		if (TextUtils.isEmpty(pckSha) && game != null) {
			pckSha = game.optString("pck_sha256_after_patch", "");
		}
		return new PayloadIdentity(version, commit, dllSha, pckSha);
	}

	private String buildPayloadLabel(PayloadIdentity identity, String fallback) {
		if (!TextUtils.isEmpty(identity.version) && !TextUtils.isEmpty(identity.commit)) {
			return identity.version + " (" + identity.commit + ")";
		}
		if (!TextUtils.isEmpty(identity.version)) {
			return identity.version;
		}
		return TextUtils.isEmpty(fallback) ? "unknown" : fallback;
	}

	private File getLegacyActiveGameDir() {
		return new File(context.getFilesDir(), PayloadManager.GAME_DIR_NAME);
	}

	private File getGlobalAccountRootDir() {
		File defaultDirectory = new File(context.getFilesDir(), "default");
		File[] accountDirectories = defaultDirectory.listFiles(File::isDirectory);
		if (accountDirectories != null && accountDirectories.length > 0) {
			List<File> directories = new ArrayList<>(Arrays.asList(accountDirectories));
			directories.sort(Comparator.comparing(File::getName, String::compareToIgnoreCase));
			return directories.get(0);
		}
		return new File(defaultDirectory, "1");
	}

	private File getGlobalModsRootDir() {
		return new File(context.getFilesDir(), "mods");
	}

	private String buildUniqueProfileId(String desired) {
		String base = sanitizeId(desired);
		if (TextUtils.isEmpty(base)) {
			base = "profile";
		}
		File candidate = new File(getProfilesRootDir(), base);
		if (!candidate.exists()) {
			return base;
		}
		for (int i = 2; ; i++) {
			String id = base + "-" + i;
			if (!new File(getProfilesRootDir(), id).exists()) {
				return id;
			}
		}
	}

	private String normalizeSaveMode(String value) {
		return SAVE_MODE_ISOLATED.equalsIgnoreCase(value) ? SAVE_MODE_ISOLATED : SAVE_MODE_GLOBAL;
	}

	private String normalizeModsMode(String value) {
		return MODS_MODE_ISOLATED.equalsIgnoreCase(value) ? MODS_MODE_ISOLATED : MODS_MODE_GLOBAL;
	}

	private String sanitizeOptionalId(String value) {
		String sanitized = sanitizeId(value);
		return "unnamed".equals(sanitized) ? "" : sanitized;
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
		return TextUtils.isEmpty(sanitized) ? "unnamed" : sanitized;
	}

	private SharedPreferences prefs() {
		return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	private static final class PayloadIdentity {
		final String version;
		final String commit;
		final String sts2DllSha256;
		final String pckSha256;

		PayloadIdentity(String version, String commit, String sts2DllSha256, String pckSha256) {
			this.version = version == null ? "" : version;
			this.commit = commit == null ? "" : commit;
			this.sts2DllSha256 = sts2DllSha256 == null ? "" : sts2DllSha256;
			this.pckSha256 = pckSha256 == null ? "" : pckSha256;
		}
	}

	public static final class GamePayload {
		public final String id;
		public final String label;
		public final String version;
		public final String commit;
		public final String sts2DllSha256;
		public final String pckSha256;
		public final File dir;
		public final File gameDir;
		public final JSONObject manifest;
		public final boolean ready;
		public final long installedAtUnix;
		public final long pckSize;
		public final long dllSize;
		public final int fileCount;
		public final long totalBytes;

		GamePayload(String id, String label, String version, String commit, String sts2DllSha256, String pckSha256, File dir, File gameDir, JSONObject manifest, boolean ready, long installedAtUnix, long pckSize, long dllSize, int fileCount, long totalBytes) {
			this.id = id == null ? "" : id;
			this.label = TextUtils.isEmpty(label) ? this.id : label;
			this.version = version == null ? "" : version;
			this.commit = commit == null ? "" : commit;
			this.sts2DllSha256 = sts2DllSha256 == null ? "" : sts2DllSha256;
			this.pckSha256 = pckSha256 == null ? "" : pckSha256;
			this.dir = dir;
			this.gameDir = gameDir;
			this.manifest = manifest;
			this.ready = ready;
			this.installedAtUnix = installedAtUnix;
			this.pckSize = pckSize;
			this.dllSize = dllSize;
			this.fileCount = fileCount;
			this.totalBytes = totalBytes;
		}
	}

	public static final class LaunchProfile {
		public final String id;
		public final String displayName;
		public final String payloadId;
		public final String compatPackId;
		public final String saveMode;
		public final String modsMode;
		public final File dir;
		public final JSONObject manifest;
		public final GamePayload payload;
		public final long createdAtUnix;
		public final long updatedAtUnix;
		public final boolean ready;

		LaunchProfile(String id, String displayName, String payloadId, String compatPackId, String saveMode, String modsMode, File dir, JSONObject manifest, GamePayload payload, long createdAtUnix, long updatedAtUnix, boolean ready) {
			this.id = id == null ? "" : id;
			this.displayName = TextUtils.isEmpty(displayName) ? this.id : displayName;
			this.payloadId = payloadId == null ? "" : payloadId;
			this.compatPackId = compatPackId == null ? "" : compatPackId;
			this.saveMode = SAVE_MODE_ISOLATED.equals(saveMode) ? SAVE_MODE_ISOLATED : SAVE_MODE_GLOBAL;
			this.modsMode = MODS_MODE_ISOLATED.equals(modsMode) ? MODS_MODE_ISOLATED : MODS_MODE_GLOBAL;
			this.dir = dir;
			this.manifest = manifest;
			this.payload = payload;
			this.createdAtUnix = createdAtUnix;
			this.updatedAtUnix = updatedAtUnix;
			this.ready = ready;
		}
	}
}
