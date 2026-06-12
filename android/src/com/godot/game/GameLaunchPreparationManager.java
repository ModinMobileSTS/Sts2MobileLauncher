package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Performs heavyweight launch preparation away from the Activity main thread.
 *
 * <p>Godot requires the Mono publish directory and the selected compatibility
 * overlay to be ready before {@link GodotApp} starts. Doing those copies inside
 * Activity.onCreate() can block the UI for several seconds when a game payload is
 * installed, so GameSettingsActivity runs this preparer on a worker thread before
 * starting GodotApp. GodotApp still keeps its own fallback setup path for direct
 * launches, but the normal launcher path should already be warm and fast.</p>
 */
public final class GameLaunchPreparationManager {
	private static final String TAG = "Sts2LaunchPrep";
	private static final String ASSEMBLY_SETUP_PREFERENCES_NAME = "sts2_assembly_setup";
	private static final String KEY_ASSEMBLY_SETUP_VERSION_CODE = "last_version_code";
	private static final String KEY_ASSEMBLY_SETUP_COMPAT_STAMP = "compat_stamp";
	private static final String KEY_ASSEMBLY_SETUP_PAYLOAD_STAMP = "payload_stamp";
	private static final String TEXTURE_CACHE_PREFERENCES_NAME = "sts2_texture_cache";
	private static final String KEY_TEXTURE_CACHE_PCK_STAMP = "pck_stamp";
	private static final int BUFFER_SIZE = 1024 * 1024;

	private final Context context;
	private final AssetManager assets;
	private final LaunchProfileManager launchProfiles;

	public GameLaunchPreparationManager(Context context) {
		this.context = context.getApplicationContext();
		this.assets = this.context.getAssets();
		this.launchProfiles = new LaunchProfileManager(this.context);
	}

	public void prepareForLaunch() throws Exception {
		long startedAt = System.currentTimeMillis();
		Log.i(TAG, "DIAG prepareForLaunch begin versionCode=" + BuildConfig.VERSION_CODE + " versionName=" + BuildConfig.VERSION_NAME + " flavor=" + BuildConfig.FLAVOR + " filesDir=" + context.getFilesDir().getAbsolutePath() + " thread=" + Thread.currentThread().getName());
		AndroidTempDirectory.configure(context, TAG);
		Sts2LogcatCollector.startNewLaunchForSelectedProfile(context);
		logLaunchStateSnapshot("after_logcat_start");
		logStepBegin("normalize_saved_language");
		normalizeSavedLanguageIfNeeded();
		logStepEnd("normalize_saved_language");
		logStepBegin("refresh_bundled_compat_packs");
		refreshBundledCompatPacksIfNeeded();
		logStepEnd("refresh_bundled_compat_packs");
		logLaunchStateSnapshot("after_bundled_compat_refresh");
		logStepBegin("log_selected_compat_pack");
		logSelectedCompatPackForLaunch();
		logStepEnd("log_selected_compat_pack");
		logStepBegin("patch_payload_if_needed");
		patchPayloadIfNeeded();
		logStepEnd("patch_payload_if_needed");
		logStepBegin("clear_texture_cache_if_payload_changed");
		clearTextureCacheIfPayloadChanged();
		logStepEnd("clear_texture_cache_if_payload_changed");
		logStepBegin("prepare_assemblies_and_overlay");
		prepareAssembliesAndOverlay();
		logStepEnd("prepare_assemblies_and_overlay");
		logLaunchStateSnapshot("after_prepare_assemblies");
		Log.i(TAG, "DIAG prepareForLaunch end elapsed_ms=" + (System.currentTimeMillis() - startedAt));
	}

	private void normalizeSavedLanguageIfNeeded() {
		try {
			Locale locale = Locale.getDefault();
			String rawLocale = locale == null ? "" : locale.toString();
			String languageTag = locale == null ? "" : locale.toLanguageTag();
			ExtraSettingsRepository repository = new ExtraSettingsRepository(context);
			org.json.JSONObject settings = repository.loadSettingsJson();
			String current = settings.optString("language", "").trim();
			if (isSupportedGameLanguage(current)) {
				return;
			}
			if (!isProblematicAndroidLocale(rawLocale, languageTag, current)) {
				return;
			}
			String resolved = resolveAndroidGameLanguage(locale);
			settings.put("language", resolved);
			repository.saveSettingsJson(settings);
			Log.i(TAG, "Normalized unsupported game language '" + current + "' for problematic Android locale raw=" + rawLocale + " tag=" + languageTag + " -> " + resolved);
		} catch (Exception exception) {
			Log.w(TAG, "Unable to normalize game language before launch; continuing with existing settings.", exception);
		}
	}

	private boolean isProblematicAndroidLocale(String rawLocale, String languageTag, String currentLanguage) {
		String raw = rawLocale == null ? "" : rawLocale;
		String tag = languageTag == null ? "" : languageTag;
		String current = currentLanguage == null ? "" : currentLanguage;
		return raw.contains("#")
			|| tag.contains("#")
			|| current.contains("#")
			|| raw.contains("@")
			|| current.contains("@");
	}

	private boolean isSupportedGameLanguage(String language) {
		switch (language) {
			case "eng":
			case "zhs":
			case "deu":
			case "esp":
			case "fra":
			case "ita":
			case "jpn":
			case "kor":
			case "pol":
			case "ptb":
			case "rus":
			case "spa":
			case "tha":
			case "tur":
				return true;
			default:
				return false;
		}
	}

	private String resolveAndroidGameLanguage(Locale locale) {
		if (locale == null) {
			return "eng";
		}
		String language = locale.getLanguage() == null ? "" : locale.getLanguage().toLowerCase(Locale.ROOT);
		String country = locale.getCountry() == null ? "" : locale.getCountry().toUpperCase(Locale.ROOT);
		if (language.startsWith("zh")) {
			// The current game payload only exposes simplified Chinese (zhs).  Mapping
			// all Android Chinese variants here avoids Godot/Mono later seeing Huawei
			// style locale strings such as zh_CN_#Hans / zh-CN-#Hans.
			return "zhs";
		}
		if ("pt".equals(language)) {
			return "ptb";
		}
		if ("es".equals(language)) {
			return "ES".equals(country) ? "spa" : "esp";
		}
		switch (language) {
			case "de": return "deu";
			case "fr": return "fra";
			case "it": return "ita";
			case "ja": return "jpn";
			case "ko": return "kor";
			case "pl": return "pol";
			case "ru": return "rus";
			case "th": return "tha";
			case "tr": return "tur";
			default: return "eng";
		}
	}

	private void refreshBundledCompatPacksIfNeeded() {
		try {
			CompatPackManager manager = new CompatPackManager(context);
			if (!manager.isCompatPackEnabled()) {
				Log.i(TAG, "Bundled compatibility pack refresh skipped because compatibility pack is disabled.");
				return;
			}
			int count = manager.installBundledCompatPacks();
			if (count > 0) {
				Log.i(TAG, "Bundled compatibility packs refreshed before launch: " + count);
			}
		} catch (Exception exception) {
			Log.w(TAG, "Unable to refresh bundled compatibility packs before launch; continuing with currently selected pack.", exception);
		}
	}

	private void logStepBegin(String step) {
		Log.i(TAG, "DIAG step_begin " + step);
	}

	private void logStepEnd(String step) {
		Log.i(TAG, "DIAG step_end " + step);
	}

	private void logLaunchStateSnapshot(String phase) {
		try {
			LaunchProfileManager.LaunchProfile profile = launchProfiles.getSelectedProfile();
			LaunchProfileManager.GamePayload payload = profile == null ? null : profile.payload;
			CompatPackManager compatManager = new CompatPackManager(context);
			boolean compatEnabled = compatManager.isCompatPackEnabled();
			CompatPackManager.CompatPack selectedPack = null;
			try {
				selectedPack = compatManager.getSelectedPack();
			} catch (Exception exception) {
				Log.w(TAG, "DIAG snapshot selected_pack_lookup_failed phase=" + phase, exception);
			}
			File publishDir = new File(context.getFilesDir(), ".godot/mono/publish/arm64");
			Log.i(TAG,
				"DIAG snapshot phase=" + phase
					+ " selected_profile_id=" + launchProfiles.getSelectedProfileId()
					+ " profile=" + describeProfile(profile)
					+ " payload=" + describePayload(payload)
					+ " compat_enabled=" + compatEnabled
					+ " selected_compat_id=" + launchProfiles.getSelectedCompatPackId()
					+ " selected_compat_target_id=" + launchProfiles.getSelectedCompatTargetId()
					+ " selected_pack=" + describeCompatPack(selectedPack)
					+ " game_dir=" + describeFile(launchProfiles.getSelectedGameDir())
					+ " manifest=" + describeFile(launchProfiles.getSelectedManifestFile())
					+ " account_root=" + describeFile(launchProfiles.getSelectedAccountRootDir())
					+ " mods_root=" + describeFile(launchProfiles.getSelectedModsRootDir())
					+ " logs_root=" + describeFile(launchProfiles.getSelectedLogsRootDir())
					+ " overlay_stage=" + describeFile(new File(context.getFilesDir(), "port_compat.pck"))
					+ " publish_dir=" + describeFile(publishDir));
			logPublishDirectorySnapshot(publishDir, phase);
		} catch (Exception exception) {
			Log.w(TAG, "DIAG snapshot failed phase=" + phase, exception);
		}
	}

	private String describeProfile(LaunchProfileManager.LaunchProfile profile) {
		if (profile == null) {
			return "null";
		}
		return "id=" + safe(profile.id)
			+ ",payload_id=" + safe(profile.payloadId)
			+ ",compat_pack_id=" + safe(profile.compatPackId)
			+ ",compat_target_id=" + safe(profile.compatTargetId)
			+ ",save_mode=" + safe(profile.saveMode)
			+ ",mods_mode=" + safe(profile.modsMode)
			+ ",ready=" + profile.ready
			+ ",dir=" + describeFile(profile.dir);
	}

	private String describePayload(LaunchProfileManager.GamePayload payload) {
		if (payload == null) {
			return "null";
		}
		return "id=" + safe(payload.id)
			+ ",version=" + safe(payload.version)
			+ ",commit=" + safe(payload.commit)
			+ ",ready=" + payload.ready
			+ ",dll_sha=" + shortSha(payload.sts2DllSha256)
			+ ",pck_sha=" + shortSha(payload.pckSha256)
			+ ",game_dir=" + describeFile(payload.gameDir);
	}

	private String describeCompatPack(CompatPackManager.CompatPack pack) {
		if (pack == null) {
			return "null";
		}
		return "id=" + safe(pack.packId)
			+ ",target_id=" + safe(pack.targetId)
			+ ",version=" + safe(pack.compatVersion)
			+ ",channel=" + safe(pack.channel)
			+ ",target=" + safe(pack.targetLabel())
			+ ",ready=" + pack.ready
			+ ",dir=" + describeFile(pack.dir)
			+ ",dll=" + describeFile(pack.dllFile)
			+ ",overlay=" + describeFile(pack.overlayPckFile);
	}

	private void logPublishDirectorySnapshot(File publishDir, String phase) {
		try {
			String[] importantNames = {
				"STS2Mobile.dll",
				"0Harmony.dll",
				"MonoMod.Core.dll",
				"MonoMod.Utils.dll",
				"MonoMod.Iced.dll",
				"GodotSharp.dll",
				"sts2.dll",
				"sts2.deps.json",
				"sts2.runtimeconfig.json"
			};
			StringBuilder builder = new StringBuilder("DIAG publish_snapshot phase=").append(phase).append(" dir=").append(describeFile(publishDir));
			for (String name : importantNames) {
				builder.append("; ").append(name).append("=").append(describeFile(new File(publishDir, name)));
			}
			builder.append("; entries=").append(listDirectorySample(publishDir, 30));
			Log.i(TAG, builder.toString());
		} catch (Exception exception) {
			Log.w(TAG, "DIAG publish_snapshot failed phase=" + phase, exception);
		}
	}

	private String listDirectorySample(File dir, int limit) {
		if (dir == null) {
			return "null";
		}
		File[] files = dir.listFiles();
		if (files == null) {
			return "list_null";
		}
		List<String> names = new ArrayList<>();
		for (File file : files) {
			String suffix = file.isDirectory() ? "/" : "(" + file.length() + ")";
			names.add(file.getName() + suffix);
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);
		if (names.size() > limit) {
			return names.subList(0, limit).toString() + " ... total=" + names.size();
		}
		return names.toString();
	}

	private String describeFile(File file) {
		if (file == null) {
			return "null";
		}
		return file.getAbsolutePath()
			+ "{exists=" + file.exists()
			+ ",file=" + file.isFile()
			+ ",dir=" + file.isDirectory()
			+ ",bytes=" + (file.isFile() ? file.length() : -1L)
			+ ",mtime=" + (file.exists() ? file.lastModified() : -1L)
			+ "}";
	}

	private void logSelectedCompatPackForLaunch() {
		try {
			CompatPackManager manager = new CompatPackManager(context);
			if (!manager.isCompatPackEnabled()) {
				Log.i(TAG, "Android compatibility pack disabled for this launch.");
				return;
			}
			CompatPackManager.CompatPack pack = manager.getSelectedPack();
			if (pack == null || !pack.ready) {
				Log.w(TAG, "No ready Android compatibility pack is selected for this launch.");
				return;
			}
			org.json.JSONObject source = pack.manifest.optJSONObject("installed_source");
			org.json.JSONObject buildInfo = pack.manifest.optJSONObject("build_info");
			Log.i(TAG,
				"Selected compatibility pack for launch:"
					+ " id=" + safe(pack.packId)
					+ "; target_id=" + safe(pack.targetId)
					+ "; display=" + safe(pack.displayName)
					+ "; compat_version=" + safe(pack.compatVersion)
					+ "; channel=" + safe(pack.channel)
					+ "; target=" + safe(pack.targetLabel())
					+ "; target_commit=" + safe(pack.targetCommit)
					+ "; source=" + safe(source == null ? "" : source.optString("kind", ""))
					+ "; source_name=" + safe(source == null ? "" : source.optString("display_name", ""))
					+ "; source_zip_sha256=" + shortSha(source == null ? "" : source.optString("zip_sha256", ""))
					+ "; build_branch=" + safe(buildInfo == null ? "" : buildInfo.optString("branch", ""))
					+ "; build_commit=" + safe(buildInfo == null ? "" : buildInfo.optString("commit", ""))
					+ "; build_dirty=" + safe(buildInfo == null ? "" : buildInfo.optString("dirty", ""))
					+ "; build_subject=" + safe(buildInfo == null ? "" : buildInfo.optString("subject", ""))
					+ "; notes=" + notesForLog(pack.manifest.optJSONArray("notes")));
		} catch (Exception exception) {
			Log.w(TAG, "Unable to log selected compatibility pack for launch.", exception);
		}
	}

	private String safe(String value) {
		if (value == null || value.trim().isEmpty()) {
			return "unknown";
		}
		return value.replace('\n', ' ').replace('\r', ' ').trim();
	}

	private String shortSha(String value) {
		String text = value == null ? "" : value.trim();
		if (text.isEmpty()) {
			return "unknown";
		}
		return text.substring(0, Math.min(12, text.length()));
	}

	private String notesForLog(org.json.JSONArray notes) {
		if (notes == null || notes.length() == 0) {
			return "unknown";
		}
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < notes.length(); i++) {
			String note = notes.optString(i, "").trim();
			if (note.isEmpty()) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append(" | ");
			}
			builder.append(note.replace('\n', ' ').replace('\r', ' '));
		}
		return builder.length() == 0 ? "unknown" : builder.toString();
	}

	public void prepareAssembliesAndOverlay() throws Exception {
		Log.i(TAG, "DIAG prepareAssembliesAndOverlay begin flavor=" + BuildConfig.FLAVOR);
		if (!BuildConfig.FLAVOR.equals("mono")) {
			Log.i(TAG, "DIAG prepareAssembliesAndOverlay skipped_non_mono");
			return;
		}
		stageSelectedCompatOverlay();
		File destDir = new File(context.getFilesDir(), ".godot/mono/publish/arm64");
		File srcDir = findAssembliesDir();
		Log.i(TAG, "DIAG assembly_dirs src=" + describeFile(srcDir) + " dest=" + describeFile(destDir));
		Set<String> protectedBclNames = new HashSet<>();
		copyBclAssembliesIfNeeded(destDir, protectedBclNames);
		SharedPreferences preferences = context.getSharedPreferences(ASSEMBLY_SETUP_PREFERENCES_NAME, Context.MODE_PRIVATE);
		String payloadStamp = buildPayloadStamp();
		String previousPayloadStamp = preferences.getString(KEY_ASSEMBLY_SETUP_PAYLOAD_STAMP, "");
		boolean forceGameAssemblyCopy = !payloadStamp.equals(previousPayloadStamp);
		Log.i(TAG, "DIAG payload_stamp current=" + payloadStamp + " previous=" + previousPayloadStamp + " force_game_copy=" + forceGameAssemblyCopy);
		if (copyGameAssembliesIfNeeded(srcDir, destDir, protectedBclNames, forceGameAssemblyCopy)) {
			preferences.edit().putString(KEY_ASSEMBLY_SETUP_PAYLOAD_STAMP, payloadStamp).apply();
			Log.i(TAG, "DIAG payload_stamp stored=" + payloadStamp);
		}
		logPublishDirectorySnapshot(destDir, "after_copy_game_assemblies");
		Log.i(TAG, "DIAG prepareAssembliesAndOverlay end");
	}

	private void patchPayloadIfNeeded() {
		File manifestFile = launchProfiles.getSelectedManifestFile();
		if (isPckPatchRecorded(manifestFile)) {
			return;
		}
		try {
			new PayloadManager(context).patchInstalledPayloadIfNeeded();
		} catch (IOException exception) {
			Log.w(TAG, "Unable to patch imported PCK before launch; continuing with existing payload.", exception);
		}
	}

	private boolean isPckPatchRecorded(File manifestFile) {
		try {
			if (manifestFile == null || !manifestFile.isFile()) {
				return true;
			}
			org.json.JSONObject root = new org.json.JSONObject(FileBrowserSupport.readTextFile(manifestFile));
			org.json.JSONObject compat = root.optJSONObject("compat");
			return compat != null && compat.optJSONObject("pck_patches") != null;
		} catch (Exception exception) {
			return false;
		}
	}

	public void clearTextureCacheForNextLaunch() throws IOException {
		int removed = clearTextureCacheDirs();
		context.getSharedPreferences(TEXTURE_CACHE_PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.remove(KEY_TEXTURE_CACHE_PCK_STAMP)
			.apply();
		Log.i(TAG, "Texture cache cleared by user request; removed entries=" + removed);
	}

	private void clearTextureCacheIfPayloadChanged() throws IOException {
		String currentStamp = buildTextureCachePayloadStamp();
		if ("no-payload".equals(currentStamp)) {
			return;
		}
		SharedPreferences preferences = context.getSharedPreferences(TEXTURE_CACHE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		String previousStamp = preferences.getString(KEY_TEXTURE_CACHE_PCK_STAMP, "");
		if (!previousStamp.isEmpty() && previousStamp.equals(currentStamp)) {
			return;
		}
		int removed = clearTextureCacheDirs();
		preferences.edit().putString(KEY_TEXTURE_CACHE_PCK_STAMP, currentStamp).apply();
		Log.i(TAG, "Texture cache stamp updated " + previousStamp + " -> " + currentStamp + "; removed entries=" + removed);
	}

	private String buildTextureCachePayloadStamp() {
		File pck = new File(launchProfiles.getSelectedGameDir(), PayloadManager.PCK_FILE_NAME);
		if (!pck.isFile()) {
			return "no-payload";
		}
		String profileId = launchProfiles.getSelectedProfileId();
		return profileId + ":" + pck.getAbsolutePath() + ":" + pck.length() + ":" + pck.lastModified();
	}

	private int clearTextureCacheDirs() throws IOException {
		String[] candidates = {
			".godot/imported",
			"etc2_cache/.godot/imported",
		};
		int total = 0;
		for (String relativePath : candidates) {
			File dir = new File(context.getFilesDir(), relativePath);
			if (!dir.exists()) {
				continue;
			}
			int[] counter = new int[] { 0 };
			deleteRecursivelyCounting(dir, counter);
			total += counter[0];
			Log.i(TAG, "Texture cache cleanup " + relativePath + ": removed entries=" + counter[0]);
		}
		return total;
	}

	private void deleteRecursivelyCounting(File file, int[] counter) throws IOException {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursivelyCounting(child, counter);
				}
			}
		}
		if (!file.delete() && file.exists()) {
			throw new IOException("Unable to delete texture cache entry: " + file.getAbsolutePath());
		}
		counter[0]++;
	}

	private void stageSelectedCompatOverlay() throws IOException {
		CompatPackManager manager = new CompatPackManager(context);
		File dest = new File(context.getFilesDir(), "port_compat.pck");
		boolean compatEnabled = manager.isCompatPackEnabled();
		Log.i(TAG, "DIAG stageSelectedCompatOverlay compat_enabled=" + compatEnabled + " dest_before=" + describeFile(dest));
		if (!compatEnabled) {
			deleteFileIfExists(dest);
			Log.i(TAG, "DIAG stageSelectedCompatOverlay deleted_because_disabled dest_after=" + describeFile(dest));
			return;
		}
		File overlay = manager.getSelectedCompatOverlayPck();
		Log.i(TAG, "DIAG stageSelectedCompatOverlay selected_overlay=" + describeFile(overlay));
		if (overlay != null && overlay.isFile()) {
			copyFile(overlay, dest);
			logPreparedFile("compat overlay", "selected_pack", overlay, dest);
			return;
		}
		extractAssetIfChanged("port_compat.pck", dest);
		logPreparedFile("compat overlay", "asset_fallback", null, dest);
	}

	private void copyBclAssembliesIfNeeded(File destDir, Set<String> copiedNames) throws IOException {
		String[] bclFiles = assets.list("dotnet_bcl");
		if (bclFiles == null || bclFiles.length == 0) {
			Log.w(TAG, "No dotnet_bcl assets found.");
			return;
		}
		FileBrowserSupport.ensureDirectory(destDir);
		CompatPackManager compatPackManager = new CompatPackManager(context);
		boolean stageCompatEntry = compatPackManager.isCompatPackEnabled();
		File selectedCompatDll = compatPackManager.getSelectedCompatDll();
		Log.i(TAG, "DIAG copyBclAssemblies assets_count=" + bclFiles.length + " compat_enabled=" + stageCompatEntry + " selected_compat_dll=" + describeFile(selectedCompatDll));
		for (String name : bclFiles) {
			copiedNames.add(name);
		}
		syncCompatEntryDll(destDir, stageCompatEntry, selectedCompatDll);
		String compatStamp = compatPackManager.buildSelectedCompatStamp();
		SharedPreferences preferences = context.getSharedPreferences(ASSEMBLY_SETUP_PREFERENCES_NAME, Context.MODE_PRIVATE);
		int previousVersion = preferences.getInt(KEY_ASSEMBLY_SETUP_VERSION_CODE, -1);
		String previousCompatStamp = preferences.getString(KEY_ASSEMBLY_SETUP_COMPAT_STAMP, "");
		boolean bclReady = new File(destDir, "GodotSharp.dll").isFile()
			&& (!stageCompatEntry || new File(destDir, "STS2Mobile.dll").isFile());
		Log.i(TAG, "DIAG bcl_state previous_version=" + previousVersion + " current_version=" + BuildConfig.VERSION_CODE + " previous_compat_stamp=" + previousCompatStamp + " current_compat_stamp=" + compatStamp + " bcl_ready=" + bclReady + " godotsharp=" + describeFile(new File(destDir, "GodotSharp.dll")) + " sts2mobile=" + describeFile(new File(destDir, "STS2Mobile.dll")));
		if (previousVersion == BuildConfig.VERSION_CODE && compatStamp.equals(previousCompatStamp) && bclReady) {
			Log.i(TAG, "DIAG copyBclAssemblies skipped_cache_hit");
			return;
		}

		for (String name : bclFiles) {
			if ("STS2Mobile.dll".equals(name)) {
				continue;
			}
			try (InputStream inputStream = assets.open("dotnet_bcl/" + name)) {
				copyStreamToFile(inputStream, new File(destDir, name));
			}
		}
		preferences.edit()
			.putInt(KEY_ASSEMBLY_SETUP_VERSION_CODE, BuildConfig.VERSION_CODE)
			.putString(KEY_ASSEMBLY_SETUP_COMPAT_STAMP, compatStamp)
			.apply();
		Log.i(TAG, "DIAG copyBclAssemblies copied_bcl_assets protected_count=" + copiedNames.size() + " dest=" + describeFile(destDir));
	}

	private void syncCompatEntryDll(File destDir, boolean compatEnabled, File selectedCompatDll) throws IOException {
		File dest = new File(destDir, "STS2Mobile.dll");
		Log.i(TAG, "DIAG syncCompatEntryDll begin compat_enabled=" + compatEnabled + " selected=" + describeFile(selectedCompatDll) + " dest_before=" + describeFile(dest));
		if (!compatEnabled) {
			deleteFileIfExists(dest);
			Log.i(TAG, "DIAG syncCompatEntryDll deleted_because_disabled dest_after=" + describeFile(dest));
			return;
		}
		if (selectedCompatDll != null && selectedCompatDll.isFile()) {
			copyFileIfDifferent(selectedCompatDll, dest);
			logPreparedFile("compat entry dll", "selected_pack", selectedCompatDll, dest);
			return;
		}
		try (InputStream inputStream = assets.open("dotnet_bcl/STS2Mobile.dll")) {
			// STS2Mobile.dll is small compared with the BCL/runtime set.  Refresh it
			// on every launch so sideloaded APK hotfixes replace stale files even when
			// versionCode and selected compat-pack stamp did not change.
			copyStreamToFile(inputStream, dest);
			logPreparedFile("compat entry dll", "asset_fallback", null, dest);
		} catch (IOException exception) {
			Log.w(TAG, "No fallback STS2Mobile.dll asset found; Android compatibility patcher will be unavailable.", exception);
			deleteFileIfExists(dest);
			Log.w(TAG, "Prepared compat entry dll missing after fallback failure: dest=" + dest.getAbsolutePath());
		}
	}

	private void logPreparedFile(String label, String sourceKind, File source, File dest) {
		try {
			StringBuilder builder = new StringBuilder();
			builder.append("Prepared ").append(label)
				.append(": source=").append(sourceKind)
				.append("; dest=").append(dest == null ? "null" : dest.getAbsolutePath());
			if (source != null) {
				builder.append("; source_path=").append(source.getAbsolutePath())
					.append("; source_exists=").append(source.isFile())
					.append("; source_bytes=").append(source.isFile() ? source.length() : -1L)
					.append("; source_mtime=").append(source.isFile() ? source.lastModified() : -1L)
					.append("; source_sha256=").append(source.isFile() ? sha256(source) : "missing");
			}
			if (dest != null) {
				builder.append("; dest_exists=").append(dest.isFile())
					.append("; dest_bytes=").append(dest.isFile() ? dest.length() : -1L)
					.append("; dest_mtime=").append(dest.isFile() ? dest.lastModified() : -1L)
					.append("; dest_sha256=").append(dest.isFile() ? sha256(dest) : "missing");
			}
			Log.i(TAG, builder.toString());
		} catch (Exception exception) {
			Log.w(TAG, "Unable to log prepared " + label + ".", exception);
		}
	}

	private String sha256(File file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
		byte[] hash = digest.digest();
		StringBuilder builder = new StringBuilder(hash.length * 2);
		for (byte value : hash) {
			builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
		}
		return builder.toString();
	}

	private boolean copyGameAssembliesIfNeeded(File srcDir, File destDir, Set<String> protectedBclNames, boolean forceCopy) throws IOException {
		Log.i(TAG, "DIAG copyGameAssemblies begin forceCopy=" + forceCopy + " src=" + describeFile(srcDir) + " dest=" + describeFile(destDir));
		if (!srcDir.isDirectory()) {
			Log.w(TAG, "Game assembly source directory is missing: " + srcDir.getAbsolutePath());
			return false;
		}
		FileBrowserSupport.ensureDirectory(destDir);
		File[] files = srcDir.listFiles();
		if (files == null) {
			Log.w(TAG, "DIAG copyGameAssemblies source_list_null src=" + srcDir.getAbsolutePath());
			return false;
		}
		Set<String> sourceNames = new HashSet<>();
		for (File src : files) {
			if (!src.isFile() || src.getName().endsWith(".so")) {
				continue;
			}
			String name = src.getName();
			if (protectedBclNames.contains(name) || isProtectedBclAssembly(name)) {
				continue;
			}
			sourceNames.add(name);
		}
		if (forceCopy) {
			deleteStaleGameAssemblies(destDir, protectedBclNames, sourceNames);
		}
		int copiedOrChecked = 0;
		for (File src : files) {
			if (!src.isFile() || src.getName().endsWith(".so")) {
				continue;
			}
			String name = src.getName();
			if (protectedBclNames.contains(name) || isProtectedBclAssembly(name)) {
				continue;
			}
			File dest = new File(destDir, name);
			if (forceCopy) {
				copyFile(src, dest);
			} else {
				copyFileIfDifferent(src, dest);
			}
			copiedOrChecked++;
		}
		Log.i(TAG, "DIAG copyGameAssemblies end source_candidates=" + sourceNames.size() + " copied_or_checked=" + copiedOrChecked + " sts2_dll=" + describeFile(new File(destDir, "sts2.dll")) + " deps=" + describeFile(new File(destDir, "sts2.deps.json")) + " runtimeconfig=" + describeFile(new File(destDir, "sts2.runtimeconfig.json")));
		return true;
	}

	private void deleteStaleGameAssemblies(File destDir, Set<String> protectedBclNames, Set<String> sourceNames) throws IOException {
		File[] existingFiles = destDir.listFiles();
		if (existingFiles == null) {
			return;
		}
		for (File file : existingFiles) {
			String name = file.getName();
			if (!file.isFile() || sourceNames.contains(name) || protectedBclNames.contains(name) || isProtectedBclAssembly(name) || "STS2Mobile.dll".equals(name)) {
				continue;
			}
			String lower = name.toLowerCase(Locale.ROOT);
			if (lower.endsWith(".dll") || lower.endsWith(".json")) {
				deleteFileIfExists(file);
			}
		}
	}

	private String buildPayloadStamp() {
		File manifestFile = launchProfiles.getSelectedManifestFile();
		if (manifestFile == null || !manifestFile.isFile()) {
			return "no-payload";
		}
		try {
			org.json.JSONObject root = new org.json.JSONObject(FileBrowserSupport.readTextFile(manifestFile));
			org.json.JSONObject identity = root.optJSONObject("identity");
			org.json.JSONObject game = root.optJSONObject("game");
			String version = identity == null ? "" : identity.optString("version", "");
			String commit = identity == null ? "" : identity.optString("commit", "");
			long dllSize = identity == null ? 0L : identity.optLong("sts2_dll_size", 0L);
			if (dllSize <= 0L && game != null) {
				dllSize = game.optLong("dll_size", 0L);
			}
			return launchProfiles.getSelectedProfileId() + ":" + manifestFile.getAbsolutePath() + ":" + manifestFile.lastModified() + ":" + version + ":" + commit + ":" + dllSize;
		} catch (Exception exception) {
			return launchProfiles.getSelectedProfileId() + ":" + manifestFile.getAbsolutePath() + ":" + manifestFile.lastModified() + ":" + manifestFile.length();
		}
	}

	private File findAssembliesDir() {
		File root = launchProfiles.getSelectedGameDir();
		File[] children = root.listFiles();
		if (children != null) {
			for (File child : children) {
				if (child.isDirectory() && child.getName().startsWith("data_")) {
					return child;
				}
			}
		}
		return new File(root, PayloadManager.ASSEMBLY_DIR_NAME);
	}

	private boolean isProtectedBclAssembly(String name) {
		return name.startsWith("System.")
			|| name.equals("mscorlib.dll")
			|| name.equals("netstandard.dll")
			|| name.equals("Microsoft.CSharp.dll")
			|| name.equals("Microsoft.VisualBasic.dll")
			|| name.equals("Microsoft.VisualBasic.Core.dll");
	}

	private boolean extractAssetIfChanged(String assetName, File dest) throws IOException {
		try (InputStream inputStream = assets.open(assetName)) {
			File parent = dest.getParentFile();
			if (parent != null) {
				FileBrowserSupport.ensureDirectory(parent);
			}
			File temp = new File(parent == null ? context.getCacheDir() : parent, dest.getName() + ".tmp");
			copyStreamToFile(inputStream, temp);
			if (dest.isFile() && dest.length() == temp.length()) {
				FileBrowserSupport.deleteRecursively(temp);
				return false;
			}
			replaceFile(temp, dest);
			return true;
		}
	}

	private void copyFileIfDifferent(File src, File dest) throws IOException {
		if (dest.isFile() && dest.length() == src.length() && dest.lastModified() >= src.lastModified()) {
			return;
		}
		copyFile(src, dest);
	}

	private void copyFile(File src, File dest) throws IOException {
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(src))) {
			copyStreamToFile(inputStream, dest);
		}
	}

	private void copyStreamToFile(InputStream inputStream, File dest) throws IOException {
		File parent = dest.getParentFile();
		if (parent != null) {
			FileBrowserSupport.ensureDirectory(parent);
		}
		try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(dest))) {
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				outputStream.write(buffer, 0, read);
			}
		}
	}

	private void deleteFileIfExists(File file) throws IOException {
		if (file.exists() && !file.delete()) {
			throw new IOException("Unable to delete file: " + file.getAbsolutePath());
		}
	}

	private void replaceFile(File temp, File dest) throws IOException {
		if (dest.exists() && !dest.delete()) {
			throw new IOException("Unable to replace file: " + dest.getAbsolutePath());
		}
		if (!temp.renameTo(dest)) {
			throw new IOException("Unable to move prepared file into place: " + dest.getAbsolutePath());
		}
	}
}
