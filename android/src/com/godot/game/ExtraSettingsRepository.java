package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.text.format.Formatter;

import org.json.JSONArray;
import org.json.JSONException;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ExtraSettingsRepository {
	public static final int SETTINGS_SCHEMA_VERSION = 6;
	public static final String KEY_ANDROID_COMPAT_PACK_ENABLED = "android_compat_pack_enabled";
	public static final String KEY_LOG_LEVEL = "log_level";
	public static final String LOG_LEVEL_INFO = "info";
	public static final String LOG_LEVEL_DEBUG = "debug";
	public static final String LOG_LEVEL_VERY_DEBUG = "very_debug";
	public static final String TOOLTIP_MODE_IMMEDIATE = "immediate";
	public static final String TOOLTIP_MODE_LONG_PRESS = "long_press";
	public static final String TOOLTIP_MODE_HIDDEN = "hidden";

	private static final String MOD_SOURCE_MODS_DIRECTORY = "mods_directory";
	private static final String MOD_GROUP_MARKER_FILE_NAME = ".sts2_mod_group";
	private static final String MOD_GROUP_CORE_NAME = "core";
	private static final String MOD_GROUP_CONTENT_NAME = "content";
	private static final String SETTINGS_FILE_NAME = "settings.save";
	private static final String PENDING_UNLOCK_ALL_FILE_NAME = "pending_unlock_all.flag";
	private static final String MOD_PROFILE_PREFERENCES_NAME = "sts2_mod_profiles";
	private static final String KEY_MOD_PROFILES = "profiles";
	private static final String KEY_ACTIVE_MOD_PROFILE_ID = "active_profile_id";
	private static final String KEY_MOD_GROUP_ORDER = "mod_group_order";
	private static final String KEY_MOD_ORDER = "mod_order";
	private static final String DEFAULT_MOD_PROFILE_ID = "default";
	private static final String KEY_ANDROID_GRAPHICS_PRESET = "android_graphics_preset";
	private static final String KEY_ANDROID_DISPLAY_PRESET = "android_display_preset";

	public static final String GRAPHICS_PRESET_RECOMMENDED = "recommended";
	public static final String GRAPHICS_PRESET_QUALITY = "quality";
	public static final String GRAPHICS_PRESET_COMPATIBILITY = "compatibility";
	public static final String GRAPHICS_PRESET_CUSTOM = "custom";
	public static final String DISPLAY_PRESET_ORIGINAL = "original";
	public static final String DISPLAY_PRESET_MOBILE = "mobile";
	public static final String DISPLAY_PRESET_CUSTOM = "custom";
	public static final String OPERATION_PRESET_TOUCH = "touch";
	public static final String OPERATION_PRESET_ORIGINAL = "original";

	private final Context context;

	public ExtraSettingsRepository(Context context) {
		this.context = context;
	}

	public void ensureAppDirectories() {
		ensureDirectory(getAccountRootDir());
		File modsRoot = getModsRootDir();
		ensureDirectory(modsRoot);
		normalizeRuntimeModAliases(modsRoot);
	}

	public void saveSetting(JsonMutator mutator) throws Exception {
		JSONObject settings = loadSettingsJson();
		mutator.mutate(settings);
		saveSettingsJson(settings);
	}

	public JSONObject loadSettingsJson() throws Exception {
		File settingsFile = getSettingsFile();
		if (!settingsFile.isFile()) {
			JSONObject defaults = createDefaultSettingsJson();
			saveSettingsJson(defaults);
			return defaults;
		}
		String content = readTextFile(settingsFile);
		if (content.trim().isEmpty()) {
			JSONObject defaults = createDefaultSettingsJson();
			saveSettingsJson(defaults);
			return defaults;
		}
		JSONObject settings = new JSONObject(content);
		boolean migrated = ensureAndroidCompanionDefaults(settings);
		if (migrated) {
			saveSettingsJson(settings);
		}
		return settings;
	}

	public void saveSettingsJson(JSONObject settings) throws Exception {
		ensureDirectory(getAccountRootDir());
		writeTextFile(getSettingsFile(), settings.toString(2));
	}

	public JSONObject createDefaultSettingsJson() throws JSONException {
		JSONObject settings = new JSONObject();
		settings.put("schema_version", SETTINGS_SCHEMA_VERSION);
		settings.put("fullscreen", true);
		settings.put("aspect_ratio", "auto");
		settings.put("target_display", -1);
		settings.put("resize_windows", true);
		settings.put("fps_limit", 60);
		settings.put("msaa", 2);
		settings.put("shader_compatibility_mode", false);
		settings.put("vsync", "off");
		putVector(settings, "window_position", -1, -1);
		putVector(settings, "window_size", 1920, 1080);
		putVector(settings, "fullscreen_render_size", 0, 0);
		settings.put("preload_enabled", true);
		settings.put("preload_startup_common_enabled", true);
		settings.put("preload_startup_main_menu_enabled", true);
		settings.put("preload_menu_hotspots_enabled", false);
		settings.put("preload_vfx_mode", "off");
		settings.put("preload_combat_code_enabled", false);
		settings.put("preload_shader_mode", "off");
		settings.put("preload_runtime_enabled", true);
		settings.put(KEY_ANDROID_COMPAT_PACK_ENABLED, true);
		settings.put("global_scale", 1.0f);
		settings.put("ui_font_scale_percent", 100);
		settings.put("show_more_hand_card_text", true);
		settings.put("show_more_hand_card_text_lift_height_percent", 50);
		settings.put("touch_lift_preview", true);
		settings.put("touch_lift_retap_action", "put_down");
		settings.put("mobile_selection_confirmation", true);
		settings.put("mobile_two_finger_inspect", true);
		settings.put("mobile_tooltip_mode", TOOLTIP_MODE_IMMEDIATE);
		settings.put("mobile_tooltip_long_press_ms", 1000);
		settings.put("show_mobile_emoji_button", true);
		settings.put("lan_multiplayer_enabled", true);
		settings.put("lan_compatibility_mod_names", new JSONArray());
		settings.put("audio_compatibility_mode", false);
		settings.put(KEY_LOG_LEVEL, getStoredLogLevel());
		settings.put("android_volume_up_soft_keyboard", false);
		settings.put("android_flip_screen_180", false);
		settings.put("lan_use_custom_player_id", false);
		settings.put("lan_use_custom_platform_player_id", false);
		settings.put("lan_custom_player_id", "");
		settings.put("lan_join_host", "");
		settings.put("lan_join_port", 33771);
		settings.put("max_multiplayer_players", 4);
		settings.put("max_multiplayer_enabled", true);
		settings.put("quick_sl_enabled", true);
		settings.put("mod_settings", JSONObject.NULL);
		settings.put(KEY_ANDROID_GRAPHICS_PRESET, GRAPHICS_PRESET_RECOMMENDED);
		settings.put(KEY_ANDROID_DISPLAY_PRESET, DISPLAY_PRESET_ORIGINAL);
		return settings;
	}

	private boolean ensureAndroidCompanionDefaults(JSONObject settings) throws JSONException {
		boolean changed = false;
		changed |= putIfMissing(settings, "schema_version", SETTINGS_SCHEMA_VERSION);
		changed |= putIfMissing(settings, "aspect_ratio", "auto");
		changed |= putIfMissing(settings, "shader_compatibility_mode", false);
		changed |= putIfMissing(settings, "preload_enabled", true);
		changed |= putIfMissing(settings, "preload_startup_common_enabled", true);
		changed |= putIfMissing(settings, "preload_startup_main_menu_enabled", true);
		changed |= putIfMissing(settings, "preload_menu_hotspots_enabled", false);
		changed |= putIfMissing(settings, "preload_vfx_mode", "off");
		changed |= putIfMissing(settings, "preload_combat_code_enabled", false);
		changed |= putIfMissing(settings, "preload_shader_mode", "off");
		changed |= putIfMissing(settings, "preload_runtime_enabled", true);
		changed |= putIfMissing(settings, KEY_ANDROID_COMPAT_PACK_ENABLED, true);
		changed |= putIfMissing(settings, "fullscreen_render_size", vector(0, 0));
		changed |= putIfMissing(settings, "global_scale", 1.0f);
		changed |= putIfMissing(settings, "ui_font_scale_percent", 100);
		changed |= putIfMissing(settings, "show_more_hand_card_text", true);
		changed |= putIfMissing(settings, "show_more_hand_card_text_lift_height_percent", 50);
		changed |= putIfMissing(settings, "touch_lift_preview", true);
		changed |= putIfMissing(settings, "touch_lift_retap_action", "put_down");
		changed |= putIfMissing(settings, "mobile_selection_confirmation", true);
		changed |= putIfMissing(settings, "mobile_two_finger_inspect", true);
		changed |= putIfMissing(settings, "mobile_tooltip_mode", TOOLTIP_MODE_IMMEDIATE);
		changed |= putIfMissing(settings, "mobile_tooltip_long_press_ms", 1000);
		changed |= normalizeExistingTooltipLongPressDelay(settings);
		changed |= putIfMissing(settings, "show_mobile_emoji_button", true);
		changed |= putIfMissing(settings, "lan_multiplayer_enabled", true);
		changed |= putIfMissing(settings, "lan_compatibility_mod_names", new JSONArray());
		changed |= putIfMissing(settings, "audio_compatibility_mode", false);
		changed |= putIfMissing(settings, KEY_LOG_LEVEL, getStoredLogLevel());
		changed |= normalizeExistingLogLevel(settings);
		changed |= putIfMissing(settings, "android_volume_up_soft_keyboard", false);
		changed |= putIfMissing(settings, "android_flip_screen_180", false);
		changed |= putIfMissing(settings, "lan_use_custom_player_id", false);
		changed |= putIfMissing(settings, "lan_use_custom_platform_player_id", false);
		changed |= putIfMissing(settings, "lan_custom_player_id", "");
		changed |= putIfMissing(settings, "lan_join_host", "");
		changed |= putIfMissing(settings, "lan_join_port", 33771);
		changed |= putIfMissing(settings, "max_multiplayer_players", 4);
		changed |= putIfMissing(settings, "max_multiplayer_enabled", true);
		changed |= putIfMissing(settings, "quick_sl_enabled", true);
		return changed;
	}

	private boolean normalizeExistingTooltipLongPressDelay(JSONObject settings) throws JSONException {
		int delayMs = settings.optInt("mobile_tooltip_long_press_ms", 1000);
		// Earlier preview builds used 3000 ms. There is no public custom-delay UI,
		// so migrate that old default to the current mobile-friendly 1 second.
		if (delayMs == 3000 || delayMs <= 0) {
			settings.put("mobile_tooltip_long_press_ms", 1000);
			return true;
		}
		return false;
	}

	private boolean putIfMissing(JSONObject object, String key, Object value) throws JSONException {
		if (object.has(key)) {
			return false;
		}
		object.put(key, value);
		return true;
	}

	public boolean isAndroidCompatPackEnabled() throws Exception {
		return loadSettingsJson().optBoolean(KEY_ANDROID_COMPAT_PACK_ENABLED, true);
	}

	public String getConfiguredLogLevel(JSONObject settings) {
		String value = settings == null ? getStoredLogLevel() : settings.optString(KEY_LOG_LEVEL, getStoredLogLevel());
		return normalizeLogLevel(value);
	}

	public String getLogLevelForLaunch() {
		String value = getStoredLogLevel();
		try {
			JSONObject settings = loadSettingsJson();
			value = settings.optString(KEY_LOG_LEVEL, value);
		} catch (Exception ignored) {
		}
		String normalized = normalizeLogLevel(value);
		ExtraSettingsPreferences.setLogLevel(context, normalized);
		return normalized;
	}

	public void saveLogLevel(String value) throws Exception {
		String normalized = normalizeLogLevel(value);
		ExtraSettingsPreferences.setLogLevel(context, normalized);
		saveSetting(settings -> settings.put(KEY_LOG_LEVEL, normalized));
	}

	public static String normalizeLogLevel(String value) {
		if (value == null) {
			return LOG_LEVEL_INFO;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(" ", "_");
		if ("verydebug".equals(normalized) || "very_debug".equals(normalized)) {
			return LOG_LEVEL_VERY_DEBUG;
		}
		if (LOG_LEVEL_DEBUG.equals(normalized)) {
			return LOG_LEVEL_DEBUG;
		}
		return LOG_LEVEL_INFO;
	}

	private boolean normalizeExistingLogLevel(JSONObject settings) throws JSONException {
		String rawValue = settings.optString(KEY_LOG_LEVEL, getStoredLogLevel());
		String normalized = normalizeLogLevel(rawValue);
		ExtraSettingsPreferences.setLogLevel(context, normalized);
		if (normalized.equals(rawValue)) {
			return false;
		}
		settings.put(KEY_LOG_LEVEL, normalized);
		return true;
	}

	private String getStoredLogLevel() {
		return normalizeLogLevel(ExtraSettingsPreferences.getLogLevel(context, LOG_LEVEL_INFO));
	}

	public void applyFirstRunDefaults() throws Exception {
		applyGraphicsPreset(GRAPHICS_PRESET_RECOMMENDED);
		applyAspectRatio("auto");
		applyDisplayPreset(DISPLAY_PRESET_ORIGINAL);
		applyOperationPreset(OPERATION_PRESET_TOUCH);
	}

	public void resetPreloadAdvancedDefaults() throws Exception {
		saveSetting(settings -> {
			settings.put("preload_startup_common_enabled", true);
			settings.put("preload_startup_main_menu_enabled", true);
			settings.put("preload_menu_hotspots_enabled", false);
			settings.put("preload_vfx_mode", "off");
			settings.put("preload_combat_code_enabled", false);
			settings.put("preload_shader_mode", "off");
			settings.put("preload_runtime_enabled", true);
		});
	}

	public void applyGraphicsPreset(String preset) throws Exception {
		saveSetting(settings -> {
			if (GRAPHICS_PRESET_CUSTOM.equals(preset)) {
				settings.put(KEY_ANDROID_GRAPHICS_PRESET, GRAPHICS_PRESET_CUSTOM);
				return;
			}
			if (GRAPHICS_PRESET_RECOMMENDED.equals(preset)) {
				settings.put("msaa", 2);
				settings.put("shader_compatibility_mode", false);
				settings.put("vsync", "off");
			} else if (GRAPHICS_PRESET_QUALITY.equals(preset)) {
				settings.put("msaa", 2);
				putVector(settings, "fullscreen_render_size", 0, 0);
				settings.put("shader_compatibility_mode", false);
				settings.put("vsync", "on");
			} else if (GRAPHICS_PRESET_COMPATIBILITY.equals(preset)) {
				settings.put("msaa", 0);
				settings.put("shader_compatibility_mode", true);
				settings.put("vsync", "off");
			}
			settings.put(KEY_ANDROID_GRAPHICS_PRESET, preset);
		});
		if (GRAPHICS_PRESET_QUALITY.equals(preset)) {
			RendererPreference.setSelectedRenderer(context, RendererPreference.RENDERER_VULKAN);
		} else if (GRAPHICS_PRESET_RECOMMENDED.equals(preset) || GRAPHICS_PRESET_COMPATIBILITY.equals(preset)) {
			RendererPreference.setSelectedRenderer(context, RendererPreference.RENDERER_OPENGL_ES3);
		}
	}

	public void applyDisplayPreset(String preset) throws Exception {
		saveSetting(settings -> {
			if (DISPLAY_PRESET_CUSTOM.equals(preset)) {
				settings.put(KEY_ANDROID_DISPLAY_PRESET, DISPLAY_PRESET_CUSTOM);
				return;
			}
			if (DISPLAY_PRESET_MOBILE.equals(preset)) {
				settings.put("global_scale", 1.1f);
				settings.put("ui_font_scale_percent", 160);
				putVector(settings, "fullscreen_render_size", 0, 0);
			} else if (DISPLAY_PRESET_ORIGINAL.equals(preset)) {
				settings.put("global_scale", 1.0f);
				settings.put("ui_font_scale_percent", 100);
				putVector(settings, "fullscreen_render_size", 0, 0);
			}
			settings.put(KEY_ANDROID_DISPLAY_PRESET, preset);
		});
	}

	public void applyOperationPreset(String preset) throws Exception {
		saveSetting(settings -> {
			boolean touchOptimized = OPERATION_PRESET_TOUCH.equals(preset);
			settings.put("mobile_selection_confirmation", touchOptimized);
			settings.put("show_more_hand_card_text", touchOptimized);
			settings.put("touch_lift_preview", touchOptimized);
		});
	}

	public void applyAspectRatio(String aspectRatio) throws Exception {
		saveSetting(settings -> settings.put("aspect_ratio", aspectRatio));
	}

	public void exportSaveZip(Uri outputUri) throws Exception {
		File sourceRoot = getAccountRootDir();
		ensureDirectory(sourceRoot);
		try (OutputStream rawStream = context.getContentResolver().openOutputStream(outputUri, "w");
			 BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(requireNonNull(rawStream));
			 ZipOutputStream zipOutputStream = new ZipOutputStream(bufferedOutputStream)) {
			zipDirectoryRecursive(sourceRoot, sourceRoot, zipOutputStream);
		}
	}

	public void importSaveZip(Uri inputUri) throws Exception {
		File saveRoot = getAccountRootDir();
		deleteRecursively(saveRoot);
		ensureDirectory(saveRoot);
		unzipIntoDirectory(inputUri, saveRoot);
	}

	public void exportFullDataBackupZip(Uri outputUri) throws Exception {
		ensureAppDirectories();
		File dataRoot = context.getDataDir();
		try (OutputStream rawStream = context.getContentResolver().openOutputStream(outputUri, "w");
			 BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(requireNonNull(rawStream));
			 ZipOutputStream zipOutputStream = new ZipOutputStream(bufferedOutputStream)) {
			writeArchiveTextEntry(zipOutputStream, "sts2_full_data_backup.json", buildFullDataBackupMetadata());
			zipDirectoryRecursive(dataRoot, dataRoot, zipOutputStream, "data");
		}
	}

	public void importFullDataBackupZip(Uri inputUri) throws Exception {
		File tempRoot = createFullDataRestoreTempRoot();
		deleteRecursively(tempRoot);
		ensureDirectory(tempRoot);
		try {
			unzipIntoDirectory(inputUri, tempRoot);
			File restoredDataRoot = new File(tempRoot, "data");
			if (restoredDataRoot.isDirectory()) {
				File dataRoot = context.getDataDir();
				validateRestoredDataRoot(restoredDataRoot);
				deleteChildren(dataRoot, tempRoot);
				copyDirectoryContents(restoredDataRoot, dataRoot);
			} else {
				File restoredFilesRoot = new File(tempRoot, "files");
				File restoredSharedPreferencesRoot = new File(tempRoot, "shared_prefs");
				if (!restoredFilesRoot.isDirectory() && !restoredSharedPreferencesRoot.isDirectory()) {
					throw new IOException(context.getString(R.string.full_data_backup_invalid_archive));
				}
				if (restoredFilesRoot.isDirectory()) {
					File filesRoot = context.getFilesDir();
					deleteChildren(filesRoot);
					copyDirectoryContents(restoredFilesRoot, filesRoot);
				}
				if (restoredSharedPreferencesRoot.isDirectory()) {
					File sharedPreferencesRoot = getSharedPreferencesRootDir();
					deleteChildren(sharedPreferencesRoot);
					copyDirectoryContents(restoredSharedPreferencesRoot, sharedPreferencesRoot);
				}
			}
			ensureAppDirectories();
		} finally {
			deleteRecursively(tempRoot);
		}
	}

	public String importMod(Uri inputUri) throws Exception {
		PreparedModImport preparedImport = prepareModImport(inputUri);
		return commitPreparedModImport(preparedImport, true);
	}

	public String importDownloadedModFile(File sourceFile, String displayName) throws Exception {
		PreparedModImport preparedImport = prepareDownloadedModImport(sourceFile, displayName);
		return commitPreparedModImport(preparedImport, true);
	}

	public PreparedModImport prepareModImport(Uri inputUri) throws Exception {
		File stagingRoot = createModImportTempRoot();
		deleteRecursively(stagingRoot);
		ensureDirectory(stagingRoot);
		try {
			String displayName = queryDisplayName(inputUri);
			String normalizedName = (displayName == null) ? "imported_mod" : sanitizeFileName(displayName);
			String lowerName = normalizedName.toLowerCase(Locale.ROOT);
			boolean shouldUnzip = lowerName.endsWith(".zip") || isZipUri(inputUri);
			if (shouldUnzip) {
				unzipIntoDirectory(inputUri, stagingRoot);
			} else {
				copyUriToFile(inputUri, new File(stagingRoot, normalizedName));
			}
			return finishPreparedModImport(stagingRoot, displayName, normalizedName);
		} catch (Exception exception) {
			deleteRecursively(stagingRoot);
			throw exception;
		}
	}

	public PreparedModImport prepareDownloadedModImport(File sourceFile, String displayName) throws Exception {
		if (sourceFile == null || !sourceFile.isFile()) {
			throw new IOException(context.getString(R.string.nexus_mod_store_download_missing_file));
		}
		File stagingRoot = createModImportTempRoot();
		deleteRecursively(stagingRoot);
		ensureDirectory(stagingRoot);
		try {
			String normalizedName = sanitizeFileName(TextUtils.isEmpty(displayName) ? sourceFile.getName() : displayName);
			String lowerName = normalizedName.toLowerCase(Locale.ROOT);
			boolean shouldUnzip = lowerName.endsWith(".zip") || isZipFile(sourceFile);
			if (shouldUnzip) {
				unzipFileIntoDirectory(sourceFile, stagingRoot);
			} else {
				copyRecursively(sourceFile, new File(stagingRoot, normalizedName));
			}
			return finishPreparedModImport(stagingRoot, displayName, normalizedName);
		} catch (Exception exception) {
			deleteRecursively(stagingRoot);
			throw exception;
		}
	}

	private PreparedModImport finishPreparedModImport(File stagingRoot, String displayName, String normalizedName) {
		normalizeRuntimeModAliases(stagingRoot);
		List<ModEntry> incomingEntries = new ArrayList<>();
		collectManifestFiles(stagingRoot, stagingRoot, incomingEntries);
		List<ModImportConflict> conflicts = findImportConflicts(incomingEntries);
		return new PreparedModImport(stagingRoot, displayName, normalizedName, incomingEntries, conflicts);
	}

	public String commitPreparedModImport(PreparedModImport preparedImport, boolean replaceExistingConflicts) throws Exception {
		if (preparedImport == null || preparedImport.stagingRoot == null || !preparedImport.stagingRoot.isDirectory()) {
			throw new IOException("Prepared MOD import is no longer available.");
		}
		try {
			if (replaceExistingConflicts) {
				deleteExistingImportConflicts(findCurrentImportConflicts(preparedImport));
			}
			File modsRoot = getModsRootDir();
			ensureDirectory(modsRoot);
			copyDirectoryContents(preparedImport.stagingRoot, modsRoot);
			normalizeRuntimeModAliases(modsRoot);
			JSONObject settings = loadSettingsJson();
			ensureModSettings(settings).put("mods_enabled", true);
			saveSettingsJson(settings);
			return preparedImport.normalizedName;
		} finally {
			discardPreparedModImport(preparedImport);
		}
	}

	public void discardPreparedModImport(PreparedModImport preparedImport) {
		if (preparedImport != null) {
			deleteRecursively(preparedImport.stagingRoot);
		}
	}

	public List<ModImportConflict> findCurrentImportConflicts(PreparedModImport preparedImport) {
		if (preparedImport == null) {
			return Collections.emptyList();
		}
		return findImportConflicts(preparedImport.incomingEntries);
	}

	private List<ModImportConflict> findImportConflicts(List<ModEntry> incomingEntries) {
		if (incomingEntries == null || incomingEntries.isEmpty()) {
			return Collections.emptyList();
		}
		Map<String, List<ModEntry>> installedById = new LinkedHashMap<>();
		for (ModEntry entry : listInstalledModManifests()) {
			installedById.computeIfAbsent(entry.modId, ignored -> new ArrayList<>()).add(entry);
		}
		List<ModImportConflict> conflicts = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (ModEntry incomingEntry : incomingEntries) {
			List<ModEntry> existingEntries = installedById.get(incomingEntry.modId);
			if (existingEntries == null || existingEntries.isEmpty() || !seen.add(incomingEntry.modId)) {
				continue;
			}
			conflicts.add(new ModImportConflict(incomingEntry.modId, existingEntries, incomingEntry));
		}
		return conflicts;
	}

	private void deleteExistingImportConflicts(List<ModImportConflict> conflicts) throws Exception {
		if (conflicts == null || conflicts.isEmpty()) {
			return;
		}
		Set<String> deletedManifestPaths = new LinkedHashSet<>();
		for (ModImportConflict conflict : conflicts) {
			for (ModEntry existingEntry : conflict.existingEntries) {
				String canonicalPath = existingEntry.manifestFile.getCanonicalPath();
				if (deletedManifestPaths.add(canonicalPath)) {
					deleteMod(existingEntry);
				}
			}
		}
	}

	private void normalizeRuntimeModAliases(File modsRoot) {
		List<ModEntry> entries = new ArrayList<>();
		collectManifestFiles(modsRoot, modsRoot, entries);
		for (ModEntry entry : entries) {
			try {
				ensureRuntimeModAlias(entry, ".pck");
				ensureRuntimeModAlias(entry, ".dll");
				ensureRuntimeModAlias(entry, ".json");
			} catch (Exception ignored) {
			}
		}
		for (ModEntry entry : entries) {
			try {
				deleteDuplicateManifestAlias(entry);
			} catch (Exception ignored) {
			}
		}
	}

	private void deleteDuplicateManifestAlias(ModEntry entry) throws IOException {
		File parent = entry.manifestFile.getParentFile();
		if (parent == null) {
			return;
		}
		File preferred = new File(parent, entry.modId + ".json");
		File manifest = entry.manifestFile.getCanonicalFile();
		File preferredCanonical = preferred.getCanonicalFile();
		if (manifest.equals(preferredCanonical) || !preferredCanonical.isFile()) {
			return;
		}
		ModEntry preferredEntry = tryParseModEntry(preferredCanonical);
		if (preferredEntry != null && entry.modId.equals(preferredEntry.modId) && isGeneratedManifestAlias(preferredEntry.manifestFile)) {
			deleteIfExists(entry.manifestFile);
		}
	}

	private void markGeneratedManifestAlias(File manifestFile) {
		try {
			JSONObject manifest = new JSONObject(readTextFile(manifestFile));
			manifest.put("android_generated_manifest_alias", true);
			writeTextFile(manifestFile, manifest.toString(2));
		} catch (Exception ignored) {
		}
	}

	private boolean isGeneratedManifestAlias(File manifestFile) {
		try {
			return new JSONObject(readTextFile(manifestFile)).optBoolean("android_generated_manifest_alias", false);
		} catch (Exception ignored) {
			return false;
		}
	}

	private void ensureRuntimeModAlias(ModEntry entry, String extension) throws IOException {
		File parent = entry.manifestFile.getParentFile();
		if (parent == null) {
			return;
		}
		if (".json".equals(extension)) {
			File source = entry.manifestFile;
			File target = new File(parent, entry.modId + extension);
			if (!source.equals(target) && source.isFile() && !target.exists()) {
				copyRecursively(source, target);
				markGeneratedManifestAlias(target);
			}
			return;
		}
		if (entry.modId.equals(entry.pckName)) {
			return;
		}
		File source = new File(parent, entry.pckName + extension);
		File target = new File(parent, entry.modId + extension);
		if (!source.isFile() || target.exists()) {
			return;
		}
		copyRecursively(source, target);
	}

	public void deleteMod(ModEntry modEntry) throws Exception {
		File manifestFile = modEntry.manifestFile;
		File parent = manifestFile.getParentFile();
		deleteIfExists(manifestFile);
		if (parent != null) {
			deleteIfExists(new File(parent, modEntry.modId + ".json"));
			deleteIfExists(new File(parent, modEntry.modId + ".pck"));
			deleteIfExists(new File(parent, modEntry.modId + ".dll"));
			if (!modEntry.modId.equals(modEntry.pckName)) {
				deleteIfExists(new File(parent, modEntry.pckName + ".pck"));
				deleteIfExists(new File(parent, modEntry.pckName + ".dll"));
			}
			pruneEmptyDirectories(parent, getModsRootDir());
		}
		JSONObject settings = loadSettingsJson();
		removeModEntry(settings, modEntry.modId);
		removeModEntry(settings, modEntry.pckName);
		saveSettingsJson(settings);
	}

	public void deleteMods(List<ModEntry> modEntries) throws Exception {
		for (ModEntry modEntry : modEntries) {
			deleteMod(modEntry);
		}
	}

	public List<String> listModGroups() {
		List<String> groups = new ArrayList<>();
		File modsRoot = getModsRootDir();
		File[] children = modsRoot.listFiles(file -> file.isDirectory() && !file.getName().startsWith(".") && !isSymbolicLink(file) && isModGroupDirectory(file));
		if (children != null) {
			for (File child : children) {
				groups.add(child.getName());
			}
		}
		groups.sort(String::compareToIgnoreCase);
		return groups;
	}

	public String createModGroup(String rawGroupName) throws Exception {
		String groupName = sanitizeFileName(normalizeModGroupName(rawGroupName));
		if (TextUtils.isEmpty(groupName)) {
			throw new IOException("Group name cannot be empty.");
		}
		File modsRoot = getModsRootDir();
		ensureDirectory(modsRoot);
		if (MOD_GROUP_CORE_NAME.equalsIgnoreCase(groupName) || MOD_GROUP_CONTENT_NAME.equalsIgnoreCase(groupName)) {
			return groupName;
		}
		File groupDirectory = new File(modsRoot, groupName);
		ensureDirectory(groupDirectory);
		markModGroupDirectory(groupDirectory);
		return groupName;
	}

	public List<String> loadModGroupOrder() {
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		return decodeStringList(preferences.getString(KEY_MOD_GROUP_ORDER, ""));
	}

	public void saveModGroupOrder(List<String> groupIds) {
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		preferences.edit().putString(KEY_MOD_GROUP_ORDER, encodeStringList(groupIds)).apply();
	}

	public List<String> loadModOrder(String groupId) {
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		return decodeStringList(preferences.getString(KEY_MOD_ORDER + ":" + safeOrderKey(groupId), ""));
	}

	public void saveModOrder(String groupId, List<String> modIds) {
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		preferences.edit().putString(KEY_MOD_ORDER + ":" + safeOrderKey(groupId), encodeStringList(modIds)).apply();
	}

	private String safeOrderKey(String value) {
		return TextUtils.isEmpty(value) ? "__root__" : value.replace(':', '_');
	}

	private String encodeStringList(List<String> values) {
		JSONArray array = new JSONArray();
		if (values != null) {
			for (String value : values) {
				if (!TextUtils.isEmpty(value)) {
					array.put(value);
				}
			}
		}
		return array.toString();
	}

	private List<String> decodeStringList(String encoded) {
		List<String> values = new ArrayList<>();
		if (TextUtils.isEmpty(encoded)) {
			return values;
		}
		try {
			JSONArray array = new JSONArray(encoded);
			for (int i = 0; i < array.length(); i++) {
				String value = array.optString(i, "");
				if (!TextUtils.isEmpty(value) && !values.contains(value)) {
					values.add(value);
				}
			}
		} catch (Exception ignored) {
		}
		return values;
	}

	public void moveModToGroup(ModEntry modEntry, String rawGroupName) throws Exception {
		if (modEntry == null || TextUtils.isEmpty(modEntry.modId)) {
			return;
		}
		String groupName = normalizeModGroupName(rawGroupName);
		File modsRoot = getModsRootDir();
		ensureDirectory(modsRoot);
		File sourceDirectory = getModEntryDirectory(modEntry);
		File targetGroupDirectory = TextUtils.isEmpty(groupName) ? modsRoot : new File(modsRoot, sanitizeFileName(groupName));
		ensureDirectory(targetGroupDirectory);
		if (!TextUtils.isEmpty(groupName)) {
			markModGroupDirectory(targetGroupDirectory);
		}
		boolean targetIsRoot = targetGroupDirectory.getCanonicalFile().equals(modsRoot.getCanonicalFile());
		if (!targetIsRoot && sourceDirectory != null && sourceDirectory.isDirectory()) {
			String sourcePath = sourceDirectory.getCanonicalPath();
			String targetGroupPath = targetGroupDirectory.getCanonicalPath();
			if (sourcePath.equals(targetGroupPath) || sourcePath.startsWith(targetGroupPath + File.separator)) {
				return;
			}
		}
		if (!targetIsRoot && sourceDirectory != null && sourceDirectory.isDirectory() && !sourceDirectory.getCanonicalFile().equals(modsRoot.getCanonicalFile()) && !isModGroupDirectory(sourceDirectory) && shouldMoveWholeModDirectory(sourceDirectory, modEntry)) {
			File targetDirectory = uniqueDirectory(new File(targetGroupDirectory, sourceDirectory.getName()));
			String sourcePath = sourceDirectory.getCanonicalPath();
			String targetPath = targetDirectory.getCanonicalPath();
			if (targetPath.equals(sourcePath) || targetPath.startsWith(sourcePath + File.separator)) {
				return;
			}
			if (!sourceDirectory.renameTo(targetDirectory)) {
				copyRecursively(sourceDirectory, targetDirectory);
				deleteRecursively(sourceDirectory);
			}
			pruneEmptyDirectories(sourceDirectory.getParentFile(), modsRoot);
		} else {
			moveModEntryFiles(modEntry, targetGroupDirectory);
		}
		normalizeRuntimeModAliases(modsRoot);
	}

	private boolean isModGroupDirectory(File directory) {
		return new File(directory, MOD_GROUP_MARKER_FILE_NAME).isFile();
	}

	private void markModGroupDirectory(File directory) throws IOException {
		File marker = new File(directory, MOD_GROUP_MARKER_FILE_NAME);
		if (!marker.isFile()) {
			writeTextFile(marker, "STS2 Android MOD group\n");
		}
	}

	private boolean shouldMoveWholeModDirectory(File sourceDirectory, ModEntry modEntry) {
		File[] manifests = sourceDirectory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
		if (manifests == null || manifests.length == 0) {
			return true;
		}
		int parsedCount = 0;
		for (File manifest : manifests) {
			ModEntry parsed = tryParseModEntry(sourceDirectory, manifest);
			if (parsed != null && !parsed.modId.equals(modEntry.modId)) {
				return false;
			}
			if (parsed != null) {
				parsedCount++;
			}
		}
		return parsedCount <= 1 || sourceDirectory.getName().equals(modEntry.modId) || sourceDirectory.getName().equals(modEntry.pckName);
	}

	private void moveModEntryFiles(ModEntry modEntry, File targetDirectory) throws Exception {
		File currentParent = modEntry.manifestFile.getParentFile();
		if (currentParent != null && currentParent.getCanonicalFile().equals(targetDirectory.getCanonicalFile())) {
			return;
		}
		List<File> sources = new ArrayList<>();
		addIfFile(sources, modEntry.manifestFile);
		File parent = modEntry.manifestFile.getParentFile();
		if (parent != null) {
			addIfFile(sources, new File(parent, modEntry.modId + ".json"));
			addIfFile(sources, new File(parent, modEntry.modId + ".pck"));
			addIfFile(sources, new File(parent, modEntry.modId + ".dll"));
			if (!modEntry.modId.equals(modEntry.pckName)) {
				addIfFile(sources, new File(parent, modEntry.pckName + ".pck"));
				addIfFile(sources, new File(parent, modEntry.pckName + ".dll"));
			}
		}
		Set<String> moved = new LinkedHashSet<>();
		for (File source : sources) {
			String canonical = source.getCanonicalPath();
			if (!moved.add(canonical)) {
				continue;
			}
			File target = uniqueFile(new File(targetDirectory, source.getName()));
			if (!source.renameTo(target)) {
				copyRecursively(source, target);
				deleteIfExists(source);
			}
		}
		if (parent != null) {
			pruneEmptyDirectories(parent, getModsRootDir());
		}
	}

	private void addIfFile(List<File> files, File file) {
		if (file != null && file.isFile()) {
			files.add(file);
		}
	}

	private File getModEntryDirectory(ModEntry modEntry) {
		File parent = modEntry.manifestFile.getParentFile();
		File modsRoot = getModsRootDir();
		if (parent == null) {
			return null;
		}
		try {
			File parentCanonical = parent.getCanonicalFile();
			File rootCanonical = modsRoot.getCanonicalFile();
			if (parentCanonical.equals(rootCanonical)) {
				return parentCanonical;
			}
			return parentCanonical;
		} catch (Exception ignored) {
			return parent;
		}
	}

	private String normalizeModGroupName(String rawGroupName) {
		if (rawGroupName == null) {
			return "";
		}
		String trimmed = rawGroupName.trim();
		if (trimmed.isEmpty() || "__root__".equals(trimmed)) {
			return "";
		}
		return trimmed;
	}

	private File uniqueDirectory(File desired) {
		File candidate = desired;
		int index = 2;
		while (candidate.exists()) {
			candidate = new File(desired.getParentFile(), desired.getName() + "-" + index);
			index++;
		}
		return candidate;
	}

	private File uniqueFile(File desired) {
		if (!desired.exists()) {
			return desired;
		}
		String name = desired.getName();
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		String extension = dot > 0 ? name.substring(dot) : "";
		int index = 2;
		File candidate;
		do {
			candidate = new File(desired.getParentFile(), base + "-" + index + extension);
			index++;
		} while (candidate.exists());
		return candidate;
	}

	public void setModsEnabled(List<ModEntry> modEntries, boolean enabled) throws Exception {
		JSONObject settings = loadSettingsJson();
		for (ModEntry modEntry : modEntries) {
			setModDisabled(settings, modEntry.modId, !enabled);
			setModDisabled(settings, modEntry.pckName, !enabled);
		}
		saveSettingsJson(settings);
	}

	public void queueUnlockAll() throws Exception {
		File flagFile = new File(getAccountRootDir(), PENDING_UNLOCK_ALL_FILE_NAME);
		writeTextFile(flagFile, Long.toString(System.currentTimeMillis()));
	}

	public String transferModSaveProfiles(boolean sourceIsModded) throws Exception {
		File accountRoot = getAccountRootDir();
		ensureDirectory(accountRoot);
		File sourceBase = sourceIsModded ? new File(accountRoot, "modded") : accountRoot;
		File targetBase = sourceIsModded ? accountRoot : new File(accountRoot, "modded");
		String sourceLabel = context.getString(sourceIsModded ? R.string.mod_save_bucket_modded : R.string.mod_save_bucket_normal);
		String targetLabel = context.getString(sourceIsModded ? R.string.mod_save_bucket_normal : R.string.mod_save_bucket_modded);
		List<File> sourceProfiles = listProfileDirectories(sourceBase);
		if (sourceProfiles.isEmpty()) {
			return context.getString(R.string.status_mod_save_transfer_no_source, sourceLabel);
		}
		ensureDirectory(targetBase);
		int transferredCount = 0;
		for (File sourceProfile : sourceProfiles) {
			File targetProfile = new File(targetBase, sourceProfile.getName());
			deleteRecursively(targetProfile);
			copyRecursively(sourceProfile, targetProfile);
			transferredCount++;
		}
		return context.getString(R.string.status_mod_save_transfer_done, transferredCount, sourceLabel, targetLabel);
	}

	public List<ModEntry> listInstalledModManifests() {
		List<ModEntry> results = new ArrayList<>();
		File modsRoot = getModsRootDir();
		collectManifestFiles(modsRoot, modsRoot, results);
		results.sort(Comparator.comparing(entry -> entry.relativePath, String::compareToIgnoreCase));
		return results;
	}

	public int getEnabledModCount(JSONObject settings, List<ModEntry> entries) throws JSONException {
		int count = 0;
		for (ModEntry entry : entries) {
			if (!isModDisabled(settings, entry)) {
				count++;
			}
		}
		return count;
	}

	public ModProfileState loadModProfileState() throws Exception {
		return loadModProfileState(loadSettingsJson(), listInstalledModManifests());
	}

	public ModProfileState loadModProfileState(JSONObject settings, List<ModEntry> installedMods) throws Exception {
		Set<String> installedIds = new LinkedHashSet<>();
		Set<String> enabledIds = new LinkedHashSet<>();
		for (ModEntry entry : installedMods) {
			installedIds.add(entry.modId);
			if (!isModDisabled(settings, entry)) {
				enabledIds.add(entry.modId);
			}
		}
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		List<ModProfile> profiles = readModProfiles(preferences);
		if (profiles.isEmpty()) {
			profiles.add(new ModProfile(DEFAULT_MOD_PROFILE_ID, context.getString(R.string.mod_profile_default), enabledIds));
		} else if (findModProfile(profiles, DEFAULT_MOD_PROFILE_ID) == null) {
			profiles.add(0, new ModProfile(DEFAULT_MOD_PROFILE_ID, context.getString(R.string.mod_profile_default), enabledIds));
		}
		List<ModProfile> sanitizedProfiles = new ArrayList<>();
		for (ModProfile profile : profiles) {
			Set<String> sanitizedIds = new LinkedHashSet<>();
			for (String modId : profile.enabledModIds) {
				if (installedIds.contains(modId)) {
					sanitizedIds.add(modId);
				}
			}
			sanitizedProfiles.add(new ModProfile(profile.id, profile.name, sanitizedIds));
		}
		String activeId = preferences.getString(KEY_ACTIVE_MOD_PROFILE_ID, DEFAULT_MOD_PROFILE_ID);
		if (findModProfile(sanitizedProfiles, activeId) == null) {
			activeId = sanitizedProfiles.get(0).id;
		}
		persistModProfiles(sanitizedProfiles, activeId);
		return new ModProfileState(sanitizedProfiles, activeId);
	}

	public ModProfile createModProfileFromCurrent(String rawName) throws Exception {
		String name = rawName == null ? "" : rawName.trim();
		if (TextUtils.isEmpty(name)) {
			throw new IllegalArgumentException(context.getString(R.string.mod_profile_name_required));
		}
		JSONObject settings = loadSettingsJson();
		List<ModEntry> installedMods = listInstalledModManifests();
		ModProfileState state = loadModProfileState(settings, installedMods);
		Set<String> enabledIds = new LinkedHashSet<>();
		for (ModEntry entry : installedMods) {
			if (!isModDisabled(settings, entry)) {
				enabledIds.add(entry.modId);
			}
		}
		ModProfile profile = new ModProfile(UUID.randomUUID().toString(), name, enabledIds);
		List<ModProfile> profiles = new ArrayList<>(state.profiles);
		profiles.add(profile);
		persistModProfiles(profiles, profile.id);
		return profile;
	}

	public void applyModProfile(String profileId) throws Exception {
		JSONObject settings = loadSettingsJson();
		List<ModEntry> installedMods = listInstalledModManifests();
		ModProfileState state = loadModProfileState(settings, installedMods);
		ModProfile profile = findModProfile(state.profiles, profileId);
		if (profile == null) {
			throw new IllegalArgumentException(context.getString(R.string.mod_profile_missing));
		}
		for (ModEntry entry : installedMods) {
			boolean profileEnabled = profile.enabledModIds.contains(entry.modId) || profile.enabledModIds.contains(entry.pckName);
			setModDisabled(settings, entry.modId, !profileEnabled);
			setModDisabled(settings, entry.pckName, !profileEnabled);
		}
		ensureModSettings(settings).put("mods_enabled", true);
		saveSettingsJson(settings);
		persistModProfiles(state.profiles, profile.id);
	}

	public void deleteModProfile(String profileId) throws Exception {
		ModProfileState state = loadModProfileState();
		if (DEFAULT_MOD_PROFILE_ID.equals(profileId)) {
			throw new IllegalArgumentException(context.getString(R.string.mod_profile_default_delete_forbidden));
		}
		List<ModProfile> profiles = new ArrayList<>();
		for (ModProfile profile : state.profiles) {
			if (!profile.id.equals(profileId)) {
				profiles.add(profile);
			}
		}
		if (profiles.size() == state.profiles.size() || profiles.isEmpty()) {
			throw new IllegalArgumentException(context.getString(R.string.mod_profile_missing));
		}
		String activeId = state.activeProfileId.equals(profileId) ? profiles.get(0).id : state.activeProfileId;
		persistModProfiles(profiles, activeId);
	}

	private List<ModProfile> readModProfiles(SharedPreferences preferences) {
		List<ModProfile> profiles = new ArrayList<>();
		try {
			JSONArray array = new JSONArray(preferences.getString(KEY_MOD_PROFILES, "[]"));
			for (int i = 0; i < array.length(); i++) {
				JSONObject item = array.optJSONObject(i);
				if (item == null) {
					continue;
				}
				String id = item.optString("id", "").trim();
				String name = item.optString("name", "").trim();
				if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) {
					continue;
				}
				Set<String> enabledIds = new LinkedHashSet<>();
				JSONArray enabledArray = item.optJSONArray("enabledModIds");
				if (enabledArray != null) {
					for (int enabledIndex = 0; enabledIndex < enabledArray.length(); enabledIndex++) {
						String modId = enabledArray.optString(enabledIndex, "").trim();
						if (!TextUtils.isEmpty(modId)) {
							enabledIds.add(modId);
						}
					}
				}
				profiles.add(new ModProfile(id, name, enabledIds));
			}
		} catch (Exception ignored) {
			profiles.clear();
		}
		return profiles;
	}

	private void persistModProfiles(List<ModProfile> profiles, String activeProfileId) throws JSONException {
		JSONArray array = new JSONArray();
		for (ModProfile profile : profiles) {
			JSONObject item = new JSONObject();
			item.put("id", profile.id);
			item.put("name", profile.name);
			JSONArray enabledArray = new JSONArray();
			for (String modId : profile.enabledModIds) {
				enabledArray.put(modId);
			}
			item.put("enabledModIds", enabledArray);
			array.put(item);
		}
		context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.putString(KEY_MOD_PROFILES, array.toString())
			.putString(KEY_ACTIVE_MOD_PROFILE_ID, activeProfileId)
			.apply();
	}

	private ModProfile findModProfile(List<ModProfile> profiles, String profileId) {
		for (ModProfile profile : profiles) {
			if (profile.id.equals(profileId)) {
				return profile;
			}
		}
		return null;
	}

	public SaveStatus getSaveStatus() {
		File accountRoot = getAccountRootDir();
		File moddedRoot = new File(accountRoot, "modded");
		long totalBytes = directorySize(accountRoot);
		return new SaveStatus(
			accountRoot,
			getSettingsFile().isFile(),
			listProfileDirectories(accountRoot).size(),
			listProfileDirectories(moddedRoot).size(),
			totalBytes,
			formatByteCount(totalBytes)
		);
	}

	public FullDataStatus getFullDataStatus() {
		File dataRoot = context.getDataDir();
		long totalBytes = directorySize(dataRoot);
		return new FullDataStatus(
			dataRoot,
			listInstalledModManifests().size(),
			totalBytes,
			formatByteCount(totalBytes)
		);
	}

	public String buildDefaultSaveExportName() {
		String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
		return "sts2-save-" + timestamp + ".zip";
	}

	public String buildDefaultFullDataBackupName() {
		String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
		return "sts2-full-data-" + timestamp + ".zip";
	}

	private String buildFullDataBackupMetadata() throws JSONException {
		JSONObject metadata = new JSONObject();
		metadata.put("type", "sts2_full_data_backup");
		metadata.put("schema_version", 1);
		metadata.put("created_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
		metadata.put("package", context.getPackageName());
		metadata.put("version_name", BuildConfig.VERSION_NAME);
		metadata.put("version_code", BuildConfig.VERSION_CODE);
		metadata.put("root", "data");
		metadata.put("contains_files", true);
		metadata.put("contains_shared_prefs", getSharedPreferencesRootDir().isDirectory());
		return metadata.toString(2);
	}

	public List<String> loadLanCompatibilityModNames(JSONObject settings) {
		List<String> modNames = new ArrayList<>();
		JSONArray jsonArray = settings.optJSONArray("lan_compatibility_mod_names");
		if (jsonArray == null) {
			return modNames;
		}
		for (int i = 0; i < jsonArray.length(); i++) {
			String modName = normalizeLanCompatibilityModName(jsonArray.optString(i, ""));
			if (!TextUtils.isEmpty(modName) && !containsLanCompatibilityModName(modNames, modName)) {
				modNames.add(modName);
			}
		}
		return modNames;
	}

	public void saveLanCompatibilityModNames(List<String> modNames) throws Exception {
		JSONObject settings = loadSettingsJson();
		putLanCompatibilityModNames(settings, modNames);
		saveSettingsJson(settings);
	}

	public void putLanCompatibilityModNames(JSONObject settings, List<String> modNames) throws JSONException {
		JSONArray jsonArray = new JSONArray();
		List<String> sanitizedNames = new ArrayList<>();
		for (String modName : modNames) {
			String sanitizedName = normalizeLanCompatibilityModName(modName);
			if (TextUtils.isEmpty(sanitizedName) || containsLanCompatibilityModName(sanitizedNames, sanitizedName)) {
				continue;
			}
			sanitizedNames.add(sanitizedName);
			jsonArray.put(sanitizedName);
		}
		settings.put("lan_compatibility_mod_names", jsonArray);
	}

	public boolean containsLanCompatibilityModName(List<String> modNames, String candidate) {
		for (String modName : modNames) {
			if (candidate.equals(modName)) {
				return true;
			}
		}
		return false;
	}

	public String normalizeLanCompatibilityModName(String rawModName) {
		return rawModName == null ? "" : rawModName.trim();
	}

	public JSONObject ensureModSettings(JSONObject settings) throws JSONException {
		Object rawModSettings = settings.opt("mod_settings");
		if (rawModSettings instanceof JSONObject jsonObject) {
			if (!jsonObject.has("mod_list")) {
				jsonObject.put("mod_list", new JSONArray());
			}
			if (!jsonObject.has("mods_enabled")) {
				jsonObject.put("mods_enabled", false);
			}
			return jsonObject;
		}
		JSONObject modSettings = new JSONObject();
		modSettings.put("mods_enabled", false);
		modSettings.put("mod_list", new JSONArray());
		settings.put("mod_settings", modSettings);
		return modSettings;
	}

	public boolean isModLoadingEnabled(JSONObject settings) {
		JSONObject modSettings = settings.optJSONObject("mod_settings");
		return modSettings != null && modSettings.optBoolean("mods_enabled", false);
	}

	public boolean isModDisabled(JSONObject settings, String modId) throws JSONException {
		return isModDisabled(settings, modId, modId);
	}

	public boolean isModDisabled(JSONObject settings, ModEntry entry) throws JSONException {
		return isModDisabled(settings, entry.modId, entry.pckName);
	}

	private boolean isModDisabled(JSONObject settings, String modId, String pckName) throws JSONException {
		JSONObject modSettings = ensureModSettings(settings);
		JSONObject modEntry = findExistingModListEntry(modSettings.optJSONArray("mod_list"), modId);
		if (modEntry == null && !modId.equals(pckName)) {
			modEntry = findExistingModListEntry(modSettings.optJSONArray("mod_list"), pckName);
		}
		if (modEntry != null) {
			return !modEntry.optBoolean("is_enabled", true);
		}
		JSONArray disabledMods = modSettings.optJSONArray("disabled_mods");
		if (disabledMods == null) {
			return false;
		}
		for (int i = 0; i < disabledMods.length(); i++) {
			JSONObject item = disabledMods.optJSONObject(i);
			if (item == null) {
				continue;
			}
			String name = item.optString("name", "");
			String source = item.optString("source", "");
			if ((modId.equals(name) || pckName.equals(name)) && (source.isEmpty() || MOD_SOURCE_MODS_DIRECTORY.equals(source))) {
				return true;
			}
		}
		return false;
	}

	public void setModDisabled(JSONObject settings, String modId, boolean disabled) throws JSONException {
		JSONObject modSettings = ensureModSettings(settings);
		JSONArray modList = modSettings.optJSONArray("mod_list");
		if (modList == null) {
			modList = new JSONArray();
			modSettings.put("mod_list", modList);
		}
		JSONObject modEntry = findOrCreateModListEntry(modList, modId);
		modEntry.put("id", modId);
		modEntry.put("source", MOD_SOURCE_MODS_DIRECTORY);
		modEntry.put("is_enabled", !disabled);
		removeLegacyDisabledModEntry(modSettings, modId);
	}

	private void removeModEntry(JSONObject settings, String modId) throws JSONException {
		JSONObject modSettings = ensureModSettings(settings);
		JSONArray modList = modSettings.optJSONArray("mod_list");
		if (modList != null) {
			JSONArray newModList = new JSONArray();
			for (int i = 0; i < modList.length(); i++) {
				JSONObject item = modList.optJSONObject(i);
				if (item == null) {
					continue;
				}
				String id = item.optString("id", "");
				String source = item.optString("source", "");
				if (modId.equals(id) && MOD_SOURCE_MODS_DIRECTORY.equals(source)) {
					continue;
				}
				newModList.put(item);
			}
			modSettings.put("mod_list", newModList);
		}
		removeLegacyDisabledModEntry(modSettings, modId);
	}

	private JSONObject findExistingModListEntry(JSONArray modList, String modId) {
		if (modList == null) {
			return null;
		}
		for (int i = 0; i < modList.length(); i++) {
			JSONObject item = modList.optJSONObject(i);
			if (item == null) {
				continue;
			}
			String id = item.optString("id", "");
			String source = item.optString("source", "");
			if (modId.equals(id) && (source.isEmpty() || MOD_SOURCE_MODS_DIRECTORY.equals(source))) {
				return item;
			}
		}
		return null;
	}

	private JSONObject findOrCreateModListEntry(JSONArray modList, String modId) throws JSONException {
		JSONObject existingEntry = findExistingModListEntry(modList, modId);
		if (existingEntry != null) {
			return existingEntry;
		}
		JSONObject newEntry = new JSONObject();
		newEntry.put("id", modId);
		newEntry.put("source", MOD_SOURCE_MODS_DIRECTORY);
		newEntry.put("is_enabled", true);
		modList.put(newEntry);
		return newEntry;
	}

	private void removeLegacyDisabledModEntry(JSONObject modSettings, String modId) throws JSONException {
		JSONArray disabledMods = modSettings.optJSONArray("disabled_mods");
		if (disabledMods == null) {
			return;
		}
		JSONArray newDisabledMods = new JSONArray();
		for (int i = 0; i < disabledMods.length(); i++) {
			JSONObject item = disabledMods.optJSONObject(i);
			if (item == null) {
				continue;
			}
			String name = item.optString("name", "");
			String source = item.optString("source", "");
			if (modId.equals(name) && (source.isEmpty() || MOD_SOURCE_MODS_DIRECTORY.equals(source))) {
				continue;
			}
			newDisabledMods.put(item);
		}
		modSettings.put("disabled_mods", newDisabledMods);
	}

	private void collectManifestFiles(File directory, List<ModEntry> results) {
		File root = getModsRootDir();
		collectManifestFiles(root, directory, results);
	}

	private void collectManifestFiles(File rootDirectory, File directory, List<ModEntry> results) {
		if (directory == null || !directory.isDirectory()) {
			return;
		}
		File[] files = directory.listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			if (file.isDirectory()) {
				collectManifestFiles(rootDirectory, file, results);
			} else if (file.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
				ModEntry modEntry = tryParseModEntry(rootDirectory, file);
				if (modEntry != null) {
					results.add(modEntry);
				}
			}
		}
	}

	private ModEntry tryParseModEntry(File manifestFile) {
		return tryParseModEntry(getModsRootDir(), manifestFile);
	}

	private ModEntry tryParseModEntry(File rootDirectory, File manifestFile) {
		try {
			String content = readTextFile(manifestFile);
			if (TextUtils.isEmpty(content)) {
				return null;
			}
			JSONObject manifest = new JSONObject(content);
			String modId = firstNonEmpty(
				manifest.optString("id", ""),
				manifest.optString("mod_id", ""),
				manifest.optString("modId", ""),
				manifest.optString("ID", "")
			).trim();
			if (modId.isEmpty()) {
				return null;
			}
			String pckName = firstNonEmpty(
				manifest.optString("pck_name", ""),
				manifest.optString("pckName", ""),
				manifest.optString("PckName", ""),
				findPayloadBaseName(manifestFile.getParentFile()),
				modId
			).trim();
			String displayName = firstNonEmpty(manifest.optString("name", ""), manifest.optString("display_name", ""), modId).trim();
			String version = firstNonEmpty(manifest.optString("version", ""), manifest.optString("mod_version", ""), manifest.optString("Version", ""));
			String authors = readAuthors(manifest);
			String description = firstNonEmpty(manifest.optString("description", ""), manifest.optString("desc", ""), manifest.optString("Description", ""));
			String category = readCategory(manifest);
			List<String> dependencies = readDependencies(manifest);
			File parent = manifestFile.getParentFile();
			boolean hasPck = hasSibling(parent, modId, ".pck") || hasSibling(parent, pckName, ".pck");
			boolean hasDll = hasSibling(parent, modId, ".dll") || hasSibling(parent, pckName, ".dll");
			return new ModEntry(manifestFile, modId, pckName, displayName, getRelativePath(rootDirectory, manifestFile), version, authors, description, category, dependencies, hasPck, hasDll);
		} catch (Exception ignored) {
			return null;
		}
	}

	private String findPayloadBaseName(File parent) {
		if (parent == null || !parent.isDirectory()) {
			return "";
		}
		File[] files = parent.listFiles((dir, name) -> {
			String lower = name.toLowerCase(Locale.ROOT);
			return lower.endsWith(".pck") || lower.endsWith(".dll");
		});
		if (files == null || files.length == 0) {
			return "";
		}
		Arrays.sort(files, Comparator.comparing(File::getName, String::compareToIgnoreCase));
		return stripExtension(files[0].getName());
	}

	private String stripExtension(String fileName) {
		if (fileName == null) {
			return "";
		}
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	private boolean hasSibling(File parent, String modId, String extension) {
		if (parent == null || !parent.isDirectory()) {
			return false;
		}
		if (new File(parent, modId + extension).isFile()) {
			return true;
		}
		File[] files = parent.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(extension));
		return files != null && files.length > 0;
	}

	private String readAuthors(JSONObject manifest) {
		String direct = firstNonEmpty(manifest.optString("author", ""), manifest.optString("authors", ""), manifest.optString("Author", ""));
		if (!TextUtils.isEmpty(direct)) {
			return direct;
		}
		JSONArray array = manifest.optJSONArray("author_list");
		if (array == null) {
			array = manifest.optJSONArray("contributors");
		}
		if (array == null) {
			return "";
		}
		List<String> authors = new ArrayList<>();
		for (int i = 0; i < array.length(); i++) {
			Object item = array.opt(i);
			if (item instanceof JSONObject object) {
				String name = firstNonEmpty(object.optString("name", ""), object.optString("id", ""));
				if (!TextUtils.isEmpty(name)) {
					authors.add(name);
				}
			} else {
				String text = String.valueOf(item).trim();
				if (!TextUtils.isEmpty(text)) {
					authors.add(text);
				}
			}
		}
		return TextUtils.join(", ", authors);
	}

	private String readCategory(JSONObject manifest) {
		String direct = firstNonEmpty(
			manifest.optString("category", ""),
			manifest.optString("type", ""),
			manifest.optString("mod_type", ""),
			manifest.optString("kind", "")
		).trim();
		if (!TextUtils.isEmpty(direct)) {
			return direct;
		}
		List<String> tags = new ArrayList<>();
		readStringArray(manifest.optJSONArray("categories"), tags);
		readStringArray(manifest.optJSONArray("tags"), tags);
		return TextUtils.join(", ", tags);
	}

	private void readStringArray(JSONArray array, List<String> out) {
		if (array == null) {
			return;
		}
		for (int i = 0; i < array.length(); i++) {
			String value = array.optString(i, "").trim();
			if (!TextUtils.isEmpty(value) && !out.contains(value)) {
				out.add(value);
			}
		}
	}

	private List<String> readDependencies(JSONObject manifest) {
		Set<String> dependencies = new LinkedHashSet<>();
		readDependencyArray(manifest.optJSONArray("dependencies"), dependencies);
		readDependencyArray(manifest.optJSONArray("optional_dependencies"), dependencies);
		readDependencyArray(manifest.optJSONArray("load_after"), dependencies);
		return new ArrayList<>(dependencies);
	}

	private void readDependencyArray(JSONArray array, Set<String> out) {
		if (array == null) {
			return;
		}
		for (int i = 0; i < array.length(); i++) {
			Object item = array.opt(i);
			String label;
			if (item instanceof JSONObject object) {
				label = firstNonEmpty(object.optString("id", ""), object.optString("name", ""), object.optString("mod_id", ""));
				String version = firstNonEmpty(object.optString("version", ""), object.optString("min_version", ""));
				if (!TextUtils.isEmpty(version)) {
					label = label + " " + version;
				}
			} else {
				label = String.valueOf(item);
			}
			label = label == null ? "" : label.trim();
			if (!label.isEmpty()) {
				out.add(label);
			}
		}
	}

	private String firstNonEmpty(String... values) {
		for (String value : values) {
			if (value != null && !value.trim().isEmpty()) {
				return value.trim();
			}
		}
		return "";
	}

	private void zipDirectoryRecursive(File rootDirectory, File currentFile, ZipOutputStream outputStream) throws IOException {
		zipDirectoryRecursive(rootDirectory, currentFile, outputStream, "");
	}

	private void zipDirectoryRecursive(File rootDirectory, File currentFile, ZipOutputStream outputStream, String entryPrefix) throws IOException {
		String normalizedPrefix = normalizeZipEntryPrefix(entryPrefix);
		String currentRelativePath = getRelativePath(rootDirectory, currentFile);
		if (currentFile.isDirectory() && !currentRelativePath.isEmpty()) {
			outputStream.putNextEntry(new ZipEntry(normalizedPrefix + currentRelativePath + "/"));
			outputStream.closeEntry();
		}
		File[] children = currentFile.listFiles();
		if (children == null || children.length == 0) {
			return;
		}
		for (File child : children) {
			if (isSymbolicLink(child)) {
				continue;
			}
			if (child.isDirectory()) {
				zipDirectoryRecursive(rootDirectory, child, outputStream, normalizedPrefix);
				continue;
			}
			String relativePath = getRelativePath(rootDirectory, child);
			outputStream.putNextEntry(new ZipEntry(normalizedPrefix + relativePath));
			try (InputStream inputStream = new BufferedInputStream(new FileInputStream(child))) {
				copyStream(inputStream, outputStream);
			}
			outputStream.closeEntry();
		}
	}

	private String normalizeZipEntryPrefix(String prefix) {
		if (prefix == null || prefix.trim().isEmpty()) {
			return "";
		}
		String normalized = prefix.replace('\\', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		if (!normalized.endsWith("/")) {
			normalized += "/";
		}
		return normalized;
	}

	private void writeArchiveTextEntry(ZipOutputStream outputStream, String entryName, String text) throws IOException {
		outputStream.putNextEntry(new ZipEntry(entryName));
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		outputStream.write(bytes, 0, bytes.length);
		outputStream.closeEntry();
	}

	private void unzipIntoDirectory(Uri uri, File targetDirectory) throws Exception {
		ensureDirectory(targetDirectory);
		try (InputStream rawStream = context.getContentResolver().openInputStream(uri);
			 BufferedInputStream bufferedInputStream = new BufferedInputStream(requireNonNull(rawStream));
			 ZipInputStream zipInputStream = new ZipInputStream(bufferedInputStream)) {
			unzipStreamIntoDirectory(zipInputStream, targetDirectory);
		}
	}

	private void unzipFileIntoDirectory(File sourceFile, File targetDirectory) throws Exception {
		ensureDirectory(targetDirectory);
		try (InputStream rawStream = new FileInputStream(sourceFile);
			 BufferedInputStream bufferedInputStream = new BufferedInputStream(rawStream);
			 ZipInputStream zipInputStream = new ZipInputStream(bufferedInputStream)) {
			unzipStreamIntoDirectory(zipInputStream, targetDirectory);
		}
	}

	private void unzipStreamIntoDirectory(ZipInputStream zipInputStream, File targetDirectory) throws Exception {
		ZipEntry entry;
		while ((entry = zipInputStream.getNextEntry()) != null) {
			String entryName = entry.getName().replace('\\', '/');
			if (entryName.startsWith("__MACOSX/") || entryName.contains("../")) {
				zipInputStream.closeEntry();
				continue;
			}
			File outputFile = new File(targetDirectory, entryName);
			String targetRootPath = targetDirectory.getCanonicalPath();
			String outputPath = outputFile.getCanonicalPath();
			if (!outputPath.equals(targetRootPath) && !outputPath.startsWith(targetRootPath + File.separator)) {
				zipInputStream.closeEntry();
				throw new IOException("Blocked invalid zip entry: " + entryName);
			}
			if (entry.isDirectory()) {
				ensureDirectory(outputFile);
				zipInputStream.closeEntry();
				continue;
			}
			File parent = outputFile.getParentFile();
			if (parent != null) {
				ensureDirectory(parent);
			}
			try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFile))) {
				copyStream(zipInputStream, outputStream);
			}
			zipInputStream.closeEntry();
		}
	}

	private void copyUriToFile(Uri uri, File destinationFile) throws Exception {
		File parent = destinationFile.getParentFile();
		if (parent != null) {
			ensureDirectory(parent);
		}
		try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
			 OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(destinationFile))) {
			copyStream(requireNonNull(inputStream), outputStream);
		}
	}

	private void copyStream(InputStream inputStream, OutputStream outputStream) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = inputStream.read(buffer)) != -1) {
			outputStream.write(buffer, 0, read);
		}
		outputStream.flush();
	}

	private boolean isZipUri(Uri uri) {
		try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
			if (inputStream == null) {
				return false;
			}
			return hasZipSignature(inputStream);
		} catch (Exception ignored) {
			return false;
		}
	}

	private boolean isZipFile(File file) {
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
			return hasZipSignature(inputStream);
		} catch (Exception ignored) {
			return false;
		}
	}

	private boolean hasZipSignature(InputStream inputStream) throws IOException {
		byte[] signature = new byte[4];
		int read = inputStream.read(signature);
		return read == 4 && signature[0] == 'P' && signature[1] == 'K' && (signature[2] == 3 || signature[2] == 5 || signature[2] == 7) && (signature[3] == 4 || signature[3] == 6 || signature[3] == 8);
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

	private String sanitizeFileName(String input) {
		String sanitized = input.replace('\\', '_').replace('/', '_').trim();
		if (sanitized.isEmpty()) {
			return "imported_file";
		}
		return sanitized;
	}

	private String removeExtension(String fileName) {
		int dotIndex = fileName.lastIndexOf('.');
		if (dotIndex <= 0) {
			return fileName;
		}
		return fileName.substring(0, dotIndex);
	}

	private List<File> listProfileDirectories(File baseDirectory) {
		List<File> results = new ArrayList<>();
		if (baseDirectory == null || !baseDirectory.isDirectory()) {
			return results;
		}
		File[] children = baseDirectory.listFiles(file -> file.isDirectory() && file.getName().matches("profile\\d+"));
		if (children == null || children.length == 0) {
			return results;
		}
		results.addAll(Arrays.asList(children));
		results.sort(Comparator.comparing(File::getName, String::compareToIgnoreCase));
		return results;
	}

	private void copyRecursively(File source, File target) throws IOException {
		if (isSymbolicLink(source)) {
			return;
		}
		if (source.isDirectory()) {
			ensureDirectory(target);
			File[] children = source.listFiles();
			if (children == null || children.length == 0) {
				return;
			}
			for (File child : children) {
				copyRecursively(child, new File(target, child.getName()));
			}
			return;
		}
		File parent = target.getParentFile();
		if (parent != null) {
			ensureDirectory(parent);
		}
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(source));
			 OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(target))) {
			copyStream(inputStream, outputStream);
		}
	}

	private void copyDirectoryContents(File sourceDirectory, File targetDirectory) throws IOException {
		ensureDirectory(targetDirectory);
		File[] children = sourceDirectory.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			copyRecursively(child, new File(targetDirectory, child.getName()));
		}
	}

	private void validateRestoredDataRoot(File restoredDataRoot) throws IOException {
		if (!new File(restoredDataRoot, "files").isDirectory()) {
			throw new IOException(context.getString(R.string.full_data_backup_invalid_archive));
		}
	}

	public void deleteRecursively(File file) {
		if (file == null || !file.exists()) {
			return;
		}
		if (isSymbolicLink(file)) {
			deleteIfExists(file);
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
		if (!file.delete() && file.exists()) {
			throw new RuntimeException("Unable to delete: " + file.getAbsolutePath());
		}
	}

	private void deleteChildren(File directory) {
		deleteChildren(directory, null);
	}

	private void deleteChildren(File directory, File except) {
		if (directory == null || !directory.isDirectory()) {
			return;
		}
		File[] children = directory.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			if (isSymbolicLink(child)) {
				continue;
			}
			if (except != null && isSameOrDescendant(child, except)) {
				continue;
			}
			if (except != null && child.isDirectory() && isSameOrDescendant(except, child)) {
				deleteChildren(child, except);
				continue;
			}
			deleteRecursively(child);
		}
	}

	private boolean isSameOrDescendant(File file, File possibleAncestor) {
		if (file == null || possibleAncestor == null) {
			return false;
		}
		try {
			String filePath = file.getCanonicalPath();
			String ancestorPath = possibleAncestor.getCanonicalPath();
			return filePath.equals(ancestorPath) || filePath.startsWith(ancestorPath + File.separator);
		} catch (IOException ignored) {
			String filePath = file.getAbsolutePath();
			String ancestorPath = possibleAncestor.getAbsolutePath();
			return filePath.equals(ancestorPath) || filePath.startsWith(ancestorPath + File.separator);
		}
	}

	private boolean isSymbolicLink(File file) {
		try {
			File fileInCanonicalParent;
			File parent = file.getParentFile();
			if (parent == null) {
				fileInCanonicalParent = file;
			} else {
				fileInCanonicalParent = new File(parent.getCanonicalFile(), file.getName());
			}
			return !fileInCanonicalParent.getCanonicalFile().equals(fileInCanonicalParent.getAbsoluteFile());
		} catch (IOException ignored) {
			return false;
		}
	}

	private void deleteIfExists(File file) {
		if (file != null && file.exists() && !file.delete()) {
			throw new RuntimeException("Unable to delete: " + file.getAbsolutePath());
		}
	}

	private void pruneEmptyDirectories(File directory, File stopDirectory) {
		File current = directory;
		while (current != null && !current.equals(stopDirectory)) {
			String[] children = current.list();
			if (children != null && children.length == 0) {
				if (!current.delete()) {
					break;
				}
				current = current.getParentFile();
				continue;
			}
			break;
		}
	}

	public void ensureDirectory(File directory) {
		if (directory.isDirectory()) {
			return;
		}
		if (!directory.mkdirs() && !directory.isDirectory()) {
			throw new RuntimeException("Unable to create directory: " + directory.getAbsolutePath());
		}
	}

	public File getAccountRootDir() {
		return new LaunchProfileManager(context).getSelectedAccountRootDir();
	}

	public File getSettingsFile() {
		return new File(getAccountRootDir(), SETTINGS_FILE_NAME);
	}

	public File getModsRootDir() {
		return new LaunchProfileManager(context).getSelectedModsRootDir();
	}

	private File getSharedPreferencesRootDir() {
		return new File(context.getDataDir(), "shared_prefs");
	}

	private File createFullDataRestoreTempRoot() {
		File cacheDir = context.getCacheDir();
		if (cacheDir != null) {
			return new File(cacheDir, "sts2_full_data_restore_" + UUID.randomUUID());
		}
		return new File(context.getDataDir(), "cache/sts2_full_data_restore_" + UUID.randomUUID());
	}

	private File createModImportTempRoot() {
		File cacheDir = context.getCacheDir();
		if (cacheDir != null) {
			return new File(cacheDir, "sts2_mod_import_" + UUID.randomUUID());
		}
		return new File(context.getDataDir(), "cache/sts2_mod_import_" + UUID.randomUUID());
	}

	private String getRelativePath(File root, File file) {
		if (root == null || file == null) {
			return file == null ? "" : file.getName();
		}
		String rootPath = root.getAbsolutePath();
		String filePath = file.getAbsolutePath();
		if (filePath.equals(rootPath)) {
			return "";
		}
		if (filePath.startsWith(rootPath + File.separator)) {
			String relative = filePath.substring(rootPath.length() + 1);
			return relative.replace(File.separatorChar, '/');
		}
		return file.getName();
	}

	private long directorySize(File file) {
		return DirectoryStatsCalculator.calculate(file).totalBytes;
	}

	public String formatByteCount(long bytes) {
		return Formatter.formatFileSize(context, bytes);
	}

	public int[] getVector(JSONObject object, String key, int defaultX, int defaultY) {
		JSONObject vector = object.optJSONObject(key);
		if (vector == null) {
			return new int[] { defaultX, defaultY };
		}
		return new int[] { vector.optInt("X", defaultX), vector.optInt("Y", defaultY) };
	}

	public void putVector(JSONObject object, String key, int x, int y) throws JSONException {
		object.put(key, vector(x, y));
	}

	private JSONObject vector(int x, int y) throws JSONException {
		JSONObject vector = new JSONObject();
		vector.put("X", x);
		vector.put("Y", y);
		return vector;
	}

	private String readTextFile(File file) throws IOException {
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file));
			 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			copyStream(inputStream, outputStream);
			return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private void writeTextFile(File file, String content) throws IOException {
		File parent = file.getParentFile();
		if (parent != null) {
			ensureDirectory(parent);
		}
		try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(file))) {
			outputStream.write(content.getBytes(StandardCharsets.UTF_8));
			outputStream.flush();
		}
	}

	private <T> T requireNonNull(T value) {
		if (value == null) {
			throw new IllegalStateException("Received null stream from content resolver.");
		}
		return value;
	}

	public interface JsonMutator {
		void mutate(JSONObject root) throws Exception;
	}

	public interface ThrowingSupplier<T> {
		T run() throws Exception;
	}

	public static final class SaveStatus {
		public final File accountRoot;
		public final boolean hasSettings;
		public final int normalProfiles;
		public final int moddedProfiles;
		public final long totalBytes;
		public final String formattedBytes;

		SaveStatus(File accountRoot, boolean hasSettings, int normalProfiles, int moddedProfiles, long totalBytes, String formattedBytes) {
			this.accountRoot = accountRoot;
			this.hasSettings = hasSettings;
			this.normalProfiles = normalProfiles;
			this.moddedProfiles = moddedProfiles;
			this.totalBytes = totalBytes;
			this.formattedBytes = formattedBytes;
		}
	}

	public static final class FullDataStatus {
		public final File dataRoot;
		public final int modCount;
		public final long totalBytes;
		public final String formattedBytes;

		FullDataStatus(File dataRoot, int modCount, long totalBytes, String formattedBytes) {
			this.dataRoot = dataRoot;
			this.modCount = modCount;
			this.totalBytes = totalBytes;
			this.formattedBytes = formattedBytes;
		}
	}

	public static final class ModProfileState {
		public final List<ModProfile> profiles;
		public final String activeProfileId;

		ModProfileState(List<ModProfile> profiles, String activeProfileId) {
			this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
			this.activeProfileId = activeProfileId;
		}
	}

	public static final class ModProfile {
		public final String id;
		public final String name;
		public final Set<String> enabledModIds;

		ModProfile(String id, String name, Set<String> enabledModIds) {
			this.id = id;
			this.name = name;
			this.enabledModIds = Collections.unmodifiableSet(new LinkedHashSet<>(enabledModIds));
		}
	}

	public static final class PreparedModImport {
		public final File stagingRoot;
		public final String displayName;
		public final String normalizedName;
		public final List<ModEntry> incomingEntries;
		public final List<ModImportConflict> conflicts;

		PreparedModImport(File stagingRoot, String displayName, String normalizedName, List<ModEntry> incomingEntries, List<ModImportConflict> conflicts) {
			this.stagingRoot = stagingRoot;
			this.displayName = displayName == null ? normalizedName : displayName;
			this.normalizedName = normalizedName;
			this.incomingEntries = Collections.unmodifiableList(new ArrayList<>(incomingEntries));
			this.conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts));
		}
	}

	public static final class ModImportConflict {
		public final String modId;
		public final List<ModEntry> existingEntries;
		public final ModEntry incomingEntry;

		ModImportConflict(String modId, List<ModEntry> existingEntries, ModEntry incomingEntry) {
			this.modId = modId;
			this.existingEntries = Collections.unmodifiableList(new ArrayList<>(existingEntries));
			this.incomingEntry = incomingEntry;
		}
	}

	public static final class ModEntry {
		public final File manifestFile;
		public final String modId;
		public final String pckName;
		public final String displayName;
		public final String relativePath;
		public final String version;
		public final String authors;
		public final String description;
		public final String category;
		public final List<String> dependencies;
		public final boolean hasPck;
		public final boolean hasDll;

		ModEntry(File manifestFile, String modId, String pckName, String displayName, String relativePath, String version, String authors, String description, String category, List<String> dependencies, boolean hasPck, boolean hasDll) {
			this.manifestFile = manifestFile;
			this.modId = modId;
			this.pckName = TextUtils.isEmpty(pckName) ? modId : pckName;
			this.displayName = displayName;
			this.relativePath = relativePath;
			this.version = version;
			this.authors = authors;
			this.description = description;
			this.category = category == null ? "" : category;
			this.dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies));
			this.hasPck = hasPck;
			this.hasDll = hasDll;
		}
	}
}
