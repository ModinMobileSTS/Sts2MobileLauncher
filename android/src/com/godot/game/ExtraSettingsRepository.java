package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.system.OsConstants;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
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
	public static final String KEY_PERFORMANCE_OVERLAY_ENABLED = "android_performance_overlay_enabled";
	public static final String KEY_DISPLAY_REFRESH_RATE_MODE = "android_display_refresh_rate_mode";
	public static final String DISPLAY_REFRESH_RATE_HIGH = "high";
	public static final String DISPLAY_REFRESH_RATE_60HZ = "60hz";
	public static final String DISPLAY_REFRESH_RATE_SYSTEM = "system";
	public static final String KEY_SCREEN_ROTATION_MODE = "android_screen_rotation_mode";
	public static final String KEY_IN_GAME_OVERLAY_ENABLED = "android_in_game_overlay_enabled";
	public static final String KEY_FLOATING_MOUSE_ENABLED = "android_floating_mouse_enabled";
	public static final String KEY_BUILTIN_FULL_KEYBOARD = "android_builtin_full_keyboard";
	public static final String KEY_FLOATING_KEYBOARD_ENABLED = "android_floating_keyboard_enabled";
	public static final String KEY_VOLUME_UP_SOFT_KEYBOARD = "android_volume_up_soft_keyboard";
	public static final String KEY_DEV_TOOLS_ENABLED = "android_dev_tools_enabled";
	public static final String KEY_DEV_INSPECTOR_WRITABLE = "android_dev_inspector_writable";
	public static final String LOG_LEVEL_OFF = "off";
	public static final String LOG_LEVEL_INFO = "info";
	public static final String LOG_LEVEL_DEBUG = "debug";
	public static final String LOG_LEVEL_VERY_DEBUG = "very_debug";
	public static final String SCREEN_ROTATION_AUTO = "auto";
	public static final String SCREEN_ROTATION_USER_LANDSCAPE = "user_landscape";
	public static final String SCREEN_ROTATION_LANDSCAPE = "landscape";
	public static final String SCREEN_ROTATION_REVERSE_LANDSCAPE = "reverse_landscape";
	public static final String SCREEN_ROTATION_PORTRAIT = "portrait";
	public static final String TOOLTIP_MODE_IMMEDIATE = "immediate";
	public static final String TOOLTIP_MODE_LONG_PRESS = "long_press";
	public static final String TOOLTIP_MODE_HIDDEN = "hidden";

	private static final String LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED = "android_high_refresh_rate_enabled";
	private static final String MOD_SOURCE_MODS_DIRECTORY = "mods_directory";
	private static final String MOD_GROUP_MARKER_FILE_NAME = ".sts2_mod_group";
	private static final String MOD_GROUP_CORE_NAME = "core";
	private static final String MOD_GROUP_CONTENT_NAME = "content";
	private static final String MOD_GROUP_UNGROUPED_NAME = "__root__";
	private static final String SETTINGS_FILE_NAME = "settings.save";
	private static final String PENDING_UNLOCK_ALL_FILE_NAME = "pending_unlock_all.flag";
	private static final String MOD_PROFILE_PREFERENCES_NAME = "sts2_mod_profiles";
	private static final String KEY_MOD_PROFILES = "profiles";
	private static final String KEY_ACTIVE_MOD_PROFILE_ID = "active_profile_id";
	private static final String KEY_MOD_GROUPS = "mod_groups";
	private static final String KEY_MOD_GROUP_ORDER = "mod_group_order";
	private static final String KEY_MOD_ORDER = "mod_order";
	private static final String KEY_MOD_GROUP_ASSIGNMENTS = "mod_group_assignments";
	private static final String KEY_HIDDEN_MOD_GROUPS = "hidden_mod_groups";
	private static final String KEY_MOD_NOTES = "mod_notes";
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

	private static final String[] MOD_FILE_NAME_KEYS = {"id", "mod_id", "modId", "ID", "pck_name", "pckName", "PckName"};
	private final Context context;

	public ExtraSettingsRepository(Context context) {
		this.context = context;
	}

	/** Worker readiness gate for settings/store entry. A clean receipt needs no MOD tree walk. */
	public void ensureAppDirectories() throws IOException {
		ensureDirectory(getAccountRootDir());
		new ModInstallStore(context.getFilesDir(), getModsRootDir()).recover();
	}

	/** Run on the launch worker, once for the actual launch, including for manually copied MODs. */
	public File prepareModsForLaunch() throws IOException {
		ensureDirectory(getAccountRootDir());
		File modsRoot = getModsRootDir().getAbsoluteFile();
		maintainMods(modsRoot, true);
		return modsRoot;
	}

	private void maintainMods(File modsRoot, boolean normalizeAliases) throws IOException {
		new ModInstallStore(context.getFilesDir(), modsRoot).recover(() -> {
			try {
				if (normalizeAliases) normalizeRuntimeModAliases(modsRoot, false);
				else scanManifests(modsRoot, modsRoot, ScanMode.RECOVER_ONLY);
			} finally {
				ModManifestCache.invalidatePath(modsRoot);
			}
		});
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
		settings.put("msaa", 0);
		settings.put("shader_compatibility_mode", false);
		settings.put("vsync", "off");
		putVector(settings, "window_position", -1, -1);
		putVector(settings, "window_size", 1920, 1080);
		putVector(settings, "fullscreen_render_size", 0, 0);
		settings.put("preload_enabled", true);
		settings.put("preload_render_warmup_enabled", false);
		settings.put("preload_startup_common_enabled", true);
		settings.put("preload_startup_main_menu_enabled", true);
		settings.put("preload_menu_hotspots_enabled", false);
		settings.put("preload_vfx_mode", "off");
		settings.put("preload_vfx_tree_warmup_enabled", false);
		settings.put("preload_vfx_tree_warmup_scope", "safe");
		settings.put("preload_vfx_tree_warmup_frames", 3);
		settings.put("preload_vfx_retain_cache_enabled", false);
		settings.put("preload_combat_animation_warmup_mode", "off");
		settings.put("preload_combat_animation_warmup_frames", 1);
		settings.put("preload_combat_hit_effect_warmup_enabled", false);
		settings.put("preload_combat_code_enabled", false);
		settings.put("preload_shader_mode", "off");
		settings.put("preload_runtime_enabled", true);
		settings.put("preload_protect_warm_cache_enabled", true);
		settings.put("preload_gameplay_assets_enabled", false);
		settings.put("preload_learned_assets_enabled", true);
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
		settings.put(KEY_PERFORMANCE_OVERLAY_ENABLED, isStoredPerformanceOverlayEnabled());
		settings.put(KEY_DISPLAY_REFRESH_RATE_MODE, getStoredDisplayRefreshRateMode());
		settings.put("android_volume_up_soft_keyboard", false);
		settings.put("android_flip_screen_180", false);
		settings.put(KEY_SCREEN_ROTATION_MODE, SCREEN_ROTATION_USER_LANDSCAPE);
		settings.put("lan_use_custom_player_id", false);
		settings.put("lan_use_custom_platform_player_id", false);
		settings.put("lan_custom_player_id", "");
		settings.put("lan_join_host", "");
		settings.put("lan_join_port", 33771);
		settings.put("max_multiplayer_players", 4);
		settings.put("max_multiplayer_enabled", true);
		settings.put("quick_sl_enabled", true);
		settings.put(KEY_IN_GAME_OVERLAY_ENABLED, false);
		settings.put(KEY_FLOATING_MOUSE_ENABLED, false);
		settings.put(KEY_BUILTIN_FULL_KEYBOARD, false);
		settings.put(KEY_FLOATING_KEYBOARD_ENABLED, false);
		settings.put(KEY_DEV_TOOLS_ENABLED, false);
		settings.put(KEY_DEV_INSPECTOR_WRITABLE, false);
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
		changed |= putIfMissing(settings, "preload_render_warmup_enabled", false);
		changed |= putIfMissing(settings, "preload_startup_common_enabled", true);
		changed |= putIfMissing(settings, "preload_startup_main_menu_enabled", true);
		changed |= putIfMissing(settings, "preload_menu_hotspots_enabled", false);
		changed |= putIfMissing(settings, "preload_vfx_mode", "off");
		changed |= putIfMissing(settings, "preload_vfx_tree_warmup_enabled", false);
		changed |= putIfMissing(settings, "preload_vfx_tree_warmup_scope", "safe");
		changed |= putIfMissing(settings, "preload_vfx_tree_warmup_frames", 3);
		changed |= putIfMissing(settings, "preload_vfx_retain_cache_enabled", false);
		changed |= putIfMissing(settings, "preload_combat_animation_warmup_mode", "off");
		changed |= putIfMissing(settings, "preload_combat_animation_warmup_frames", 1);
		changed |= putIfMissing(settings, "preload_combat_hit_effect_warmup_enabled", false);
		changed |= putIfMissing(settings, "preload_combat_code_enabled", false);
		changed |= putIfMissing(settings, "preload_shader_mode", "off");
		changed |= putIfMissing(settings, "preload_runtime_enabled", true);
		changed |= putIfMissing(settings, "preload_protect_warm_cache_enabled", true);
		changed |= putIfMissing(settings, "preload_gameplay_assets_enabled", false);
		changed |= putIfMissing(settings, "preload_learned_assets_enabled", true);
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
		changed |= putIfMissing(settings, KEY_PERFORMANCE_OVERLAY_ENABLED, isStoredPerformanceOverlayEnabled());
		changed |= syncExistingPerformanceOverlaySetting(settings);
		changed |= syncExistingDisplayRefreshRateSetting(settings);
		changed |= putIfMissing(settings, "android_volume_up_soft_keyboard", false);
		changed |= putIfMissing(settings, "android_flip_screen_180", false);
		changed |= ensureScreenRotationMode(settings);
		changed |= putIfMissing(settings, "lan_use_custom_player_id", false);
		changed |= putIfMissing(settings, "lan_use_custom_platform_player_id", false);
		changed |= putIfMissing(settings, "lan_custom_player_id", "");
		changed |= putIfMissing(settings, "lan_join_host", "");
		changed |= putIfMissing(settings, "lan_join_port", 33771);
		changed |= putIfMissing(settings, "max_multiplayer_players", 4);
		changed |= putIfMissing(settings, "max_multiplayer_enabled", true);
		changed |= putIfMissing(settings, "quick_sl_enabled", true);
		changed |= putIfMissing(settings, KEY_IN_GAME_OVERLAY_ENABLED, false);
		changed |= putIfMissing(settings, KEY_FLOATING_MOUSE_ENABLED, false);
		changed |= putIfMissing(settings, KEY_BUILTIN_FULL_KEYBOARD, false);
		changed |= putIfMissing(settings, KEY_FLOATING_KEYBOARD_ENABLED, false);
		changed |= putIfMissing(settings, KEY_DEV_TOOLS_ENABLED, false);
		changed |= putIfMissing(settings, KEY_DEV_INSPECTOR_WRITABLE, false);
		return changed;
	}

	public boolean isInGameOverlayEnabled() {
		try {
			return loadSettingsJson().optBoolean(KEY_IN_GAME_OVERLAY_ENABLED, false);
		} catch (Exception exception) {
			return false;
		}
	}

	/** Built-in full PC keyboard switch; off keeps the original system IME path. */
	public boolean isBuiltinFullKeyboardEnabled() {
		try {
			return loadSettingsJson().optBoolean(KEY_BUILTIN_FULL_KEYBOARD, false);
		} catch (Exception exception) {
			return false;
		}
	}

	public static boolean isBuiltinKeyboardEnabled(android.content.Context context) {
		return new ExtraSettingsRepository(context).isBuiltinFullKeyboardEnabled();
	}

	public boolean isDevToolsEnabled() {
		try {
			return loadSettingsJson().optBoolean(KEY_DEV_TOOLS_ENABLED, false);
		} catch (Exception exception) {
			return false;
		}
	}

	public boolean isDevInspectorWritable() {
		try {
			return loadSettingsJson().optBoolean(KEY_DEV_INSPECTOR_WRITABLE, false);
		} catch (Exception exception) {
			return false;
		}
	}

	public static String normalizeScreenRotationMode(String value) {
		if (value == null) {
			return SCREEN_ROTATION_USER_LANDSCAPE;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(" ", "_");
		if ("none".equals(normalized) || "normal".equals(normalized) || "no_rotate".equals(normalized) || "no_rotation".equals(normalized) || SCREEN_ROTATION_LANDSCAPE.equals(normalized)) {
			return SCREEN_ROTATION_LANDSCAPE;
		}
		if ("180".equals(normalized) || "flip_180".equals(normalized) || "rotate_180".equals(normalized) || "reverse".equals(normalized) || SCREEN_ROTATION_REVERSE_LANDSCAPE.equals(normalized)) {
			return SCREEN_ROTATION_REVERSE_LANDSCAPE;
		}
		if (SCREEN_ROTATION_PORTRAIT.equals(normalized) || "portrait_mode".equals(normalized)) {
			return SCREEN_ROTATION_PORTRAIT;
		}
		if ("user".equals(normalized) || "system".equals(normalized) || "follow_system".equals(normalized) || SCREEN_ROTATION_USER_LANDSCAPE.equals(normalized)) {
			return SCREEN_ROTATION_USER_LANDSCAPE;
		}
		if (SCREEN_ROTATION_AUTO.equals(normalized) || "sensor".equals(normalized) || "sensor_landscape".equals(normalized) || "auto_rotate".equals(normalized) || "auto_rotation".equals(normalized)) {
			return SCREEN_ROTATION_AUTO;
		}
		return SCREEN_ROTATION_USER_LANDSCAPE;
	}

	private boolean ensureScreenRotationMode(JSONObject settings) throws JSONException {
		String fallback = settings.optBoolean("android_flip_screen_180", false)
			? SCREEN_ROTATION_REVERSE_LANDSCAPE
			: SCREEN_ROTATION_USER_LANDSCAPE;
		String normalized = normalizeScreenRotationMode(settings.optString(KEY_SCREEN_ROTATION_MODE, fallback));
		boolean changed = false;
		if (!settings.has(KEY_SCREEN_ROTATION_MODE) || !normalized.equals(settings.optString(KEY_SCREEN_ROTATION_MODE, ""))) {
			settings.put(KEY_SCREEN_ROTATION_MODE, normalized);
			changed = true;
		}
		boolean flip180 = SCREEN_ROTATION_REVERSE_LANDSCAPE.equals(normalized);
		if (settings.optBoolean("android_flip_screen_180", false) != flip180) {
			settings.put("android_flip_screen_180", flip180);
			changed = true;
		}
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

	public boolean isPerformanceOverlayEnabled(JSONObject settings) {
		if (settings != null && settings.has(KEY_PERFORMANCE_OVERLAY_ENABLED)) {
			boolean enabled = settings.optBoolean(KEY_PERFORMANCE_OVERLAY_ENABLED, false);
			ExtraSettingsPreferences.setPerformanceOverlayEnabled(context, enabled);
			return enabled;
		}
		return isStoredPerformanceOverlayEnabled();
	}

	public boolean isPerformanceOverlayEnabledForLaunch() {
		boolean enabled = isStoredPerformanceOverlayEnabled();
		try {
			JSONObject settings = loadSettingsJson();
			enabled = settings.optBoolean(KEY_PERFORMANCE_OVERLAY_ENABLED, enabled);
		} catch (Exception ignored) {
		}
		ExtraSettingsPreferences.setPerformanceOverlayEnabled(context, enabled);
		return enabled;
	}

	public void savePerformanceOverlayEnabled(boolean enabled) throws Exception {
		ExtraSettingsPreferences.setPerformanceOverlayEnabled(context, enabled);
		saveSetting(settings -> settings.put(KEY_PERFORMANCE_OVERLAY_ENABLED, enabled));
	}

	public String getDisplayRefreshRateMode(JSONObject settings) {
		if (settings != null) {
			if (settings.has(KEY_DISPLAY_REFRESH_RATE_MODE)) {
				String normalized = normalizeDisplayRefreshRateMode(settings.optString(KEY_DISPLAY_REFRESH_RATE_MODE, null));
				ExtraSettingsPreferences.setDisplayRefreshRateMode(context, normalized);
				try {
					settings.put(KEY_DISPLAY_REFRESH_RATE_MODE, normalized);
					settings.remove(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED);
				} catch (JSONException ignored) {
				}
				return normalized;
			}
			if (settings.has(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED)) {
				String migrated = settings.optBoolean(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED, true)
					? DISPLAY_REFRESH_RATE_HIGH
					: DISPLAY_REFRESH_RATE_SYSTEM;
				ExtraSettingsPreferences.setDisplayRefreshRateMode(context, migrated);
				try {
					settings.put(KEY_DISPLAY_REFRESH_RATE_MODE, migrated);
					settings.remove(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED);
				} catch (JSONException ignored) {
				}
				return migrated;
			}
		}
		return getStoredDisplayRefreshRateMode();
	}

	public String getDisplayRefreshRateModeForLaunch() {
		String mode = getStoredDisplayRefreshRateMode();
		try {
			mode = getDisplayRefreshRateMode(loadSettingsJson());
		} catch (Exception ignored) {
		}
		mode = normalizeDisplayRefreshRateMode(mode);
		ExtraSettingsPreferences.setDisplayRefreshRateMode(context, mode);
		return mode;
	}

	public void saveDisplayRefreshRateMode(String value) throws Exception {
		String normalized = normalizeDisplayRefreshRateMode(value);
		saveSetting(settings -> {
			settings.put(KEY_DISPLAY_REFRESH_RATE_MODE, normalized);
			settings.remove(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED);
		});
		ExtraSettingsPreferences.setDisplayRefreshRateMode(context, normalized);
	}

	public static String normalizeDisplayRefreshRateMode(String value) {
		if (DISPLAY_REFRESH_RATE_HIGH.equals(value)) {
			return DISPLAY_REFRESH_RATE_HIGH;
		}
		if (DISPLAY_REFRESH_RATE_60HZ.equals(value)) {
			return DISPLAY_REFRESH_RATE_60HZ;
		}
		if (DISPLAY_REFRESH_RATE_SYSTEM.equals(value)) {
			return DISPLAY_REFRESH_RATE_SYSTEM;
		}
		return DISPLAY_REFRESH_RATE_HIGH;
	}

	public static String normalizeLogLevel(String value) {
		if (value == null) {
			return LOG_LEVEL_INFO;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(" ", "_");
		if ("none".equals(normalized) || "disabled".equals(normalized) || LOG_LEVEL_OFF.equals(normalized)) {
			return LOG_LEVEL_OFF;
		}
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

	private boolean isStoredPerformanceOverlayEnabled() {
		return ExtraSettingsPreferences.isPerformanceOverlayEnabled(context);
	}

	private String getStoredDisplayRefreshRateMode() {
		return normalizeDisplayRefreshRateMode(ExtraSettingsPreferences.getDisplayRefreshRateMode(context));
	}

	private boolean syncExistingPerformanceOverlaySetting(JSONObject settings) throws JSONException {
		boolean enabled = settings.optBoolean(KEY_PERFORMANCE_OVERLAY_ENABLED, isStoredPerformanceOverlayEnabled());
		ExtraSettingsPreferences.setPerformanceOverlayEnabled(context, enabled);
		if (settings.has(KEY_PERFORMANCE_OVERLAY_ENABLED)) {
			return false;
		}
		settings.put(KEY_PERFORMANCE_OVERLAY_ENABLED, enabled);
		return true;
	}

	private boolean syncExistingDisplayRefreshRateSetting(JSONObject settings) throws JSONException {
		String normalized;
		boolean changed = false;
		if (settings.has(KEY_DISPLAY_REFRESH_RATE_MODE)) {
			String raw = settings.optString(KEY_DISPLAY_REFRESH_RATE_MODE, null);
			normalized = normalizeDisplayRefreshRateMode(raw);
			if (!normalized.equals(raw)) {
				settings.put(KEY_DISPLAY_REFRESH_RATE_MODE, normalized);
				changed = true;
			}
		} else if (settings.has(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED)) {
			normalized = settings.optBoolean(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED, true)
				? DISPLAY_REFRESH_RATE_HIGH
				: DISPLAY_REFRESH_RATE_SYSTEM;
			settings.put(KEY_DISPLAY_REFRESH_RATE_MODE, normalized);
			changed = true;
		} else {
			normalized = getStoredDisplayRefreshRateMode();
			settings.put(KEY_DISPLAY_REFRESH_RATE_MODE, normalized);
			changed = true;
		}
		if (settings.has(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED)) {
			settings.remove(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED);
			changed = true;
		}
		ExtraSettingsPreferences.setDisplayRefreshRateMode(context, normalized);
		return changed;
	}

	public void applyFirstRunDefaults() throws Exception {
		applyGraphicsPreset(GRAPHICS_PRESET_RECOMMENDED);
		applyAspectRatio("auto");
		applyDisplayPreset(DISPLAY_PRESET_ORIGINAL);
		applyOperationPreset(OPERATION_PRESET_TOUCH);
	}

	public void resetPreloadAdvancedDefaults() throws Exception {
		saveSetting(settings -> {
			settings.put("preload_render_warmup_enabled", false);
			settings.put("preload_startup_common_enabled", true);
			settings.put("preload_startup_main_menu_enabled", true);
			settings.put("preload_menu_hotspots_enabled", false);
			settings.put("preload_vfx_mode", "off");
			settings.put("preload_vfx_tree_warmup_enabled", false);
			settings.put("preload_vfx_tree_warmup_scope", "safe");
			settings.put("preload_vfx_tree_warmup_frames", 3);
			settings.put("preload_vfx_retain_cache_enabled", false);
			settings.put("preload_combat_animation_warmup_mode", "off");
			settings.put("preload_combat_animation_warmup_frames", 1);
			settings.put("preload_combat_hit_effect_warmup_enabled", false);
			settings.put("preload_combat_code_enabled", false);
			settings.put("preload_shader_mode", "off");
			settings.put("preload_runtime_enabled", true);
			settings.put("preload_protect_warm_cache_enabled", true);
			settings.put("preload_gameplay_assets_enabled", false);
			settings.put("preload_learned_assets_enabled", true);
		});
	}

	public void applyGraphicsPreset(String preset) throws Exception {
		saveSetting(settings -> {
			if (GRAPHICS_PRESET_CUSTOM.equals(preset)) {
				settings.put(KEY_ANDROID_GRAPHICS_PRESET, GRAPHICS_PRESET_CUSTOM);
				return;
			}
			if (GRAPHICS_PRESET_RECOMMENDED.equals(preset)) {
				settings.put("msaa", 0);
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
		prepareModsForLaunch(); // Never archive a half-published legacy Workshop installation.
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
			prepareModsForLaunch();
		} finally {
			// Data/ and legacy files/ restores may replace every profile, even on partial failure.
			ModManifestCache.invalidateAll();
			deleteRecursively(tempRoot);
		}
	}



	public PreparedModImport prepareDownloadedModDirectory(File sourceDirectory, String displayName) throws Exception {
		if (sourceDirectory == null || !OsConstants.S_ISDIR(ModInstallStore.modeNoFollow(sourceDirectory))) {
			throw new IOException(context.getString(R.string.workshop_download_missing_directory));
		}
		File singleArchive = findSingleImportArchive(sourceDirectory);
		if (singleArchive != null) {
			return prepareDownloadedModImport(singleArchive, TextUtils.isEmpty(displayName) ? singleArchive.getName() : displayName);
		}
		File stagingRoot = createModImportTempRoot();
		deleteRecursively(stagingRoot);
		ensureDirectory(stagingRoot);
		try {
			copyDirectoryContentsForModImport(sourceDirectory, stagingRoot);
			return finishPreparedModImport(stagingRoot, displayName, sanitizeFileName(TextUtils.isEmpty(displayName) ? sourceDirectory.getName() : displayName), true);
		} catch (Exception exception) {
			deleteRecursively(stagingRoot);
			throw exception;
		}
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
			boolean fullPackage = shouldUnzip;
			if (shouldUnzip) {
				unzipIntoDirectory(inputUri, stagingRoot);
			} else {
				File archiveSource = new File(stagingRoot.getParentFile(), stagingRoot.getName() + "-source-" + normalizedName);
				try {
					copyUriToFile(inputUri, archiveSource);
					if (ArchiveExtractor.isArchive(archiveSource)) {
						fullPackage = true;
						extractArchiveFile(archiveSource, stagingRoot);
					} else {
						copyRecursively(archiveSource, new File(stagingRoot, normalizedName));
					}
				} finally {
					deleteRecursively(archiveSource);
				}
			}
			return finishPreparedModImport(stagingRoot, displayName, normalizedName, fullPackage);
		} catch (Exception exception) {
			deleteRecursively(stagingRoot);
			throw exception;
		}
	}

	public PreparedModImport prepareDownloadedModImport(File sourceFile, String displayName) throws Exception {
		if (sourceFile == null || !OsConstants.S_ISREG(ModInstallStore.modeNoFollow(sourceFile))) {
			throw new IOException(context.getString(R.string.nexus_mod_store_download_missing_file));
		}
		File stagingRoot = createModImportTempRoot();
		deleteRecursively(stagingRoot);
		ensureDirectory(stagingRoot);
		try {
			String normalizedName = sanitizeFileName(TextUtils.isEmpty(displayName) ? sourceFile.getName() : displayName);
			String lowerName = normalizedName.toLowerCase(Locale.ROOT);
			boolean zipArchive = lowerName.endsWith(".zip") || isZipFile(sourceFile);
			boolean shouldUnzip = zipArchive || ArchiveExtractor.isArchive(sourceFile);
			if (zipArchive) {
				unzipFileIntoDirectory(sourceFile, stagingRoot);
			} else if (shouldUnzip) {
				extractArchiveFile(sourceFile, stagingRoot);
			} else {
				copyRecursively(sourceFile, new File(stagingRoot, normalizedName));
			}
			return finishPreparedModImport(stagingRoot, displayName, normalizedName, shouldUnzip);
		} catch (Exception exception) {
			deleteRecursively(stagingRoot);
			throw exception;
		}
	}

	private PreparedModImport finishPreparedModImport(File stagingRoot, String displayName, String normalizedName, boolean fullPackage) throws IOException {
		File modsRoot = getModsRootDir().getAbsoluteFile();
		maintainMods(modsRoot, false);
		List<ModEntry> incomingEntries = normalizeRuntimeModAliases(stagingRoot, true);
		return new PreparedModImport(stagingRoot, displayName, normalizedName, incomingEntries, modsRoot, fullPackage && !incomingEntries.isEmpty());
	}

	public String commitPreparedModImport(PreparedModImport preparedImport, boolean replaceExistingConflicts) throws Exception {
		return commitPreparedModImport(preparedImport, replaceExistingConflicts, false);
	}

	public String commitPreparedModImport(PreparedModImport preparedImport, boolean replaceExistingConflicts, boolean allowPathConflicts) throws Exception {
		try {
			requirePreparedModImport(preparedImport);
			maintainMods(preparedImport.modsRoot, false);
			List<ModImportConflict> idConflicts = replaceExistingConflicts ? findCurrentImportConflicts(preparedImport) : Collections.emptyList();
			ModInstallStore store = new ModInstallStore(context.getFilesDir(), preparedImport.modsRoot);
			try (ModInstallStore.Transaction transaction = store.beginInstall(preparedImport.stagingRoot, "",
				toInstallModules(preparedImport.stagingRoot, preparedImport.incomingEntries, ""),
				toInstallModules(preparedImport.modsRoot, conflictEntries(idConflicts), ""),
				preparedImport.isReplaceTrackedFiles(), allowPathConflicts, false)) {
				JSONObject settings = loadSettingsJson();
				ensureModSettings(settings).put("mods_enabled", true);
				saveSettingsJson(settings);
				transaction.commit();
				preparedImport.fileNotice = formatInstallNotice(transaction.preservedPaths, transaction.untrackedModIds);
			} catch (ModInstallStore.OverwriteException exception) {
				throw new ModImportPathConflictException(toPathConflicts(exception.paths));
			}
			return preparedImport.normalizedName;
		} finally {
			discardPreparedModImport(preparedImport);
		}
	}

	public WorkshopModImportResult commitPreparedWorkshopModImport(PreparedModImport preparedImport, String rawGroupName, String publishedFileId, boolean replaceExistingConflicts) throws Exception {
		return commitPreparedWorkshopModImport(preparedImport, rawGroupName, publishedFileId, "public", replaceExistingConflicts);
	}

	public WorkshopModImportResult commitPreparedWorkshopModImport(PreparedModImport preparedImport, String rawGroupName, String publishedFileId, String workshopBranch, boolean replaceExistingConflicts) throws Exception {
		ModInstallStore.Transaction transaction = null;
		try {
			requirePreparedModImport(preparedImport);
			maintainMods(preparedImport.modsRoot, false);
			File installRoot = getWorkshopItemInstallDir(rawGroupName, publishedFileId, workshopBranch);
			String prefix = requireModRelativePath(preparedImport.modsRoot, installRoot);
			List<ModImportConflict> idConflicts = replaceExistingConflicts ? findCurrentWorkshopImportConflicts(preparedImport, rawGroupName, publishedFileId, workshopBranch) : Collections.emptyList();
			transaction = new ModInstallStore(context.getFilesDir(), preparedImport.modsRoot).beginInstall(
				preparedImport.stagingRoot, prefix,
				toInstallModules(preparedImport.stagingRoot, preparedImport.incomingEntries, prefix),
				toInstallModules(preparedImport.modsRoot, conflictEntries(idConflicts), ""), true, false, true);
			JSONObject settings = loadSettingsJson();
			ensureModSettings(settings).put("mods_enabled", true);
			saveSettingsJson(settings);
			List<ModEntry> installedEntries = listInstalledModManifestsUnder(installRoot);
			preparedImport.fileNotice = formatInstallNotice(transaction.preservedPaths, transaction.untrackedModIds);
			return new WorkshopModImportResult(installRoot, installedEntries, preparedImport.normalizedName, transaction, preparedImport.fileNotice);
		} catch (Exception exception) {
			if (transaction != null) {
				try {
					transaction.close();
				} catch (IOException rollbackException) {
					exception.addSuppressed(rollbackException);
				}
			}
			throw exception;
		} finally {
			discardPreparedModImport(preparedImport);
		}
	}

	public void finalizeWorkshopModImport(WorkshopModImportResult result) throws IOException {
		if (result != null) {
			try (ModInstallStore.Transaction transaction = result.transaction) {
				transaction.commit();
			}
		}
	}

	public void rollbackWorkshopModImport(WorkshopModImportResult result) throws IOException {
		if (result != null) {
			result.transaction.close();
		}
	}

	public void discardPreparedModImport(PreparedModImport preparedImport) {
		if (preparedImport != null) {
			deleteRecursively(preparedImport.stagingRoot);
		}
	}

	private void requirePreparedModImport(PreparedModImport preparedImport) throws IOException {
		if (preparedImport == null || preparedImport.stagingRoot == null || !preparedImport.stagingRoot.isDirectory()) {
			throw new IOException("Prepared MOD import is no longer available.");
		}
		if (!getModsRootDir().getCanonicalFile().equals(preparedImport.modsRoot.getCanonicalFile())) {
			throw new IOException("The selected MOD directory changed. Import the MOD again in the intended launch profile.");
		}
	}

	private String requireModRelativePath(File root, File file) throws IOException {
		String rootPath = root.getAbsoluteFile().getPath();
		String filePath = file.getAbsoluteFile().getPath();
		if (!filePath.startsWith(rootPath + File.separator)) {
			throw new IOException("MOD path is outside its installation directory: " + file);
		}
		return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
	}

	private List<ModInstallStore.Module> toInstallModules(File sourceRoot, List<ModEntry> entries, String prefix) throws IOException {
		List<ModInstallStore.Module> modules = new ArrayList<>();
		String pathPrefix = prefix.isEmpty() ? "" : prefix + "/";
		for (ModEntry entry : entries) {
			if (!isSafeModBaseName(entry.modId) || !isSafeModBaseName(entry.pckName)) {
				throw new IOException("Invalid MOD file name.");
			}
			Set<String> paths = new LinkedHashSet<>();
			paths.add(pathPrefix + requireModRelativePath(sourceRoot, entry.manifestFile));
			File parent = entry.manifestFile.getParentFile();
			for (String name : new String[] {entry.modId + ".json", entry.modId + ".dll", entry.modId + ".pck", entry.pckName + ".dll", entry.pckName + ".pck"}) {
				paths.add(pathPrefix + requireModRelativePath(sourceRoot, requireModSibling(parent, name)));
			}
			modules.add(new ModInstallStore.Module(entry.modId, pathPrefix + requireModRelativePath(sourceRoot, entry.manifestFile), entry.version, paths));
		}
		return modules;
	}

	private List<ModEntry> conflictEntries(List<ModImportConflict> conflicts) {
		List<ModEntry> entries = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (ModImportConflict conflict : conflicts) {
			for (ModEntry entry : conflict.existingEntries) {
				if (seen.add(entry.manifestFile.getAbsolutePath())) {
					entries.add(entry);
				}
			}
		}
		return entries;
	}

	private List<ModImportPathConflict> toPathConflicts(List<String> paths) {
		if (paths.isEmpty()) {
			return Collections.emptyList();
		}
		File modsRoot = getModsRootDir();
		Map<String, String> ownerLabels = new LinkedHashMap<>();
		for (ModEntry entry : listInstalledModManifests()) {
			File parent = entry.manifestFile.getParentFile();
			String label = entry.displayName + " (" + entry.modId + ")";
			for (String name : new String[] {entry.manifestFile.getName(), entry.modId + ".json", entry.modId + ".dll", entry.modId + ".pck", entry.pckName + ".dll", entry.pckName + ".pck"}) {
				ownerLabels.putIfAbsent(getRelativePath(modsRoot, new File(parent, name)), label);
			}
		}
		List<ModImportPathConflict> conflicts = new ArrayList<>();
		for (String path : paths) {
			int slash = path.lastIndexOf('/');
			String fallback = slash < 0 ? "" : path.substring(0, slash);
			conflicts.add(new ModImportPathConflict(path, ownerLabels.getOrDefault(path, fallback)));
		}
		return conflicts;
	}

	private String formatInstallNotice(List<String> preservedPaths, List<String> untrackedModIds) {
		List<String> notices = new ArrayList<>();
		if (!untrackedModIds.isEmpty()) {
			notices.add(context.getString(R.string.mod_import_legacy_notice, TextUtils.join(", ", untrackedModIds)));
		}
		if (!preservedPaths.isEmpty()) {
			notices.add(context.getString(R.string.mod_import_preserved_notice, TextUtils.join("\n", preservedPaths)));
		}
		return TextUtils.join("\n\n", notices);
	}


	public List<ModImportConflict> findCurrentImportConflicts(PreparedModImport preparedImport) {
		if (preparedImport == null) {
			return Collections.emptyList();
		}
		return findImportConflicts(preparedImport.incomingEntries);
	}

	public List<ModImportConflict> findCurrentWorkshopImportConflicts(PreparedModImport preparedImport, String rawGroupName, String publishedFileId) {
		return findCurrentWorkshopImportConflicts(preparedImport, rawGroupName, publishedFileId, "public");
	}

	public List<ModImportConflict> findCurrentWorkshopImportConflicts(PreparedModImport preparedImport, String rawGroupName, String publishedFileId, String workshopBranch) {
		if (preparedImport == null) {
			return Collections.emptyList();
		}
		File installRoot = getWorkshopItemInstallDir(rawGroupName, publishedFileId, workshopBranch);
		List<ModImportConflict> conflicts = findImportConflicts(preparedImport.incomingEntries);
		if (conflicts.isEmpty()) {
			return conflicts;
		}
		List<ModImportConflict> filtered = new ArrayList<>();
		for (ModImportConflict conflict : conflicts) {
			List<ModEntry> existingOutsideItem = new ArrayList<>();
			for (ModEntry existingEntry : conflict.existingEntries) {
				if (!isModEntryUnderDirectory(existingEntry, installRoot)) {
					existingOutsideItem.add(existingEntry);
				}
			}
			if (!existingOutsideItem.isEmpty()) {
				filtered.add(new ModImportConflict(conflict.modId, existingOutsideItem, conflict.incomingEntry));
			}
		}
		return filtered;
	}

	public List<ModImportPathConflict> findCurrentImportPathConflicts(PreparedModImport preparedImport, List<ModImportConflict> confirmedIdConflicts) throws IOException {
		if (preparedImport == null || preparedImport.stagingRoot == null || !preparedImport.stagingRoot.isDirectory()) {
			return Collections.emptyList();
		}
		return findImportPathConflicts(preparedImport, confirmedIdConflicts);
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

	private List<ModImportPathConflict> findImportPathConflicts(PreparedModImport preparedImport, List<ModImportConflict> confirmedIdConflicts) throws IOException {
		requirePreparedModImport(preparedImport);
		ModInstallStore.Review review = new ModInstallStore(context.getFilesDir(), preparedImport.modsRoot).review(
			preparedImport.stagingRoot, "", toInstallModules(preparedImport.stagingRoot, preparedImport.incomingEntries, ""),
			toInstallModules(preparedImport.modsRoot, conflictEntries(confirmedIdConflicts), ""), preparedImport.isReplaceTrackedFiles(), false);
		return toPathConflicts(review.conflictPaths);
	}



	private static boolean validateModFileNames(JSONObject manifest, File file, boolean staging) throws IOException {
		for (String key : MOD_FILE_NAME_KEYS) {
			String name = manifest.optString(key, "").trim();
			if (!name.isEmpty() && !isSafeModBaseName(name)) {
				if (staging) throw new IOException("Invalid MOD " + key + " in " + file.getName() + ": " + name);
				return false;
			}
		}
		return true;
	}

	private static boolean isSafeModBaseName(String name) {
		if (name == null || name.trim().isEmpty() || ".".equals(name) || "..".equals(name)) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c < 32 || "/\\:*?\"<>|".indexOf(c) >= 0) {
				return false;
			}
		}
		return true;
	}

	private static File requireModSibling(File parent, String name) throws IOException {
		if (parent == null || !isSafeModBaseName(name)) {
			throw new IOException("Invalid MOD file name: " + name);
		}
		File file = new File(parent, name);
		int mode = ModInstallStore.modeNoFollow(file);
		if (mode != 0 && !OsConstants.S_ISREG(mode)) {
			throw new IOException("Not a regular MOD file: " + file);
		}
		return file;
	}

	private List<ModEntry> normalizeRuntimeModAliases(File root, boolean staging) throws IOException {
		List<ModEntry> entries = scanManifests(root, root, staging ? ScanMode.STAGING : ScanMode.LAUNCH);
		boolean changed = false;
		Set<File> recognized = null;
		for (ModEntry entry : entries) {
			if (!entry.modId.equals(entry.pckName)) {
				changed |= ensurePayloadAlias(entry, ".pck");
				changed |= ensurePayloadAlias(entry, ".dll");
			}
			if (!entry.manifestFile.getName().equals(entry.modId + ".json")) {
				if (recognized == null) {
					recognized = new HashSet<>();
					for (ModEntry parsed : entries) recognized.add(parsed.manifestFile);
				}
				File target = requireModSibling(entry.directory(), entry.modId + ".json");
				if (!target.exists()) {
					File source = requireModSibling(entry.directory(), entry.manifestFile.getName());
					try {
						JSONObject manifest = new JSONObject(readTextFile(source));
						manifest.put("android_generated_manifest_alias", true);
						writeTextFile(target, manifest.toString(2));
					} catch (JSONException e) { throw new IOException("Invalid MOD manifest: " + source, e); }
					recognized.add(target);
					changed = true;
				}
			}
		}
		if (recognized != null) changed |= removeDuplicateAliases(entries, recognized, staging);
		// Receipts refer to the post-normalization staging filenames. A launch needs no rescan.
		return staging && changed ? scanManifests(root, root, ScanMode.STAGING) : entries;
	}

	private boolean removeDuplicateAliases(List<ModEntry> entries, Set<File> recognized, boolean staging) throws IOException {
		boolean changed = false;
		for (ModEntry entry : entries) {
			if (entry.manifestFile.getName().equals(entry.modId + ".json")) continue;
			File preferred = requireModSibling(entry.directory(), entry.modId + ".json");
			if (!recognized.contains(preferred) || !preferred.isFile()) continue;
			try {
				JSONObject manifest = new JSONObject(readTextFile(preferred));
				if (manifest.optBoolean("android_generated_manifest_alias", false)
					&& entry.modId.equals(readModId(manifest)) && validateModFileNames(manifest, preferred, staging)) {
					File original = requireModSibling(entry.directory(), entry.manifestFile.getName());
					if (!original.delete()) throw new IOException("Unable to remove duplicate MOD manifest: " + original);
					changed = true;
				}
			} catch (JSONException ignored) { /* Never delete the original for an unrecognized alias. */ }
		}
		return changed;
	}

	private boolean ensurePayloadAlias(ModEntry entry, String extension) throws IOException {
		File source = requireModSibling(entry.directory(), entry.pckName + extension);
		if (!source.isFile()) return false;
		File target = requireModSibling(entry.directory(), entry.modId + extension);
		if (target.exists()) return false;
		copyRecursively(source, target);
		return true;
	}

	public String deleteMod(ModEntry modEntry) throws Exception {
		return deleteMods(Collections.singletonList(modEntry));
	}

	public String deleteMods(List<ModEntry> modEntries) throws Exception {
		File modsRoot = getModsRootDir().getAbsoluteFile();
		maintainMods(modsRoot, false);
		String notice;
		try (ModInstallStore.Transaction transaction = new ModInstallStore(context.getFilesDir(), modsRoot)
			.beginRemoval(toInstallModules(modsRoot, modEntries, ""), "")) {
			transaction.commit();
			notice = formatInstallNotice(Collections.emptyList(), transaction.untrackedModIds);
			if (transaction.preservedDirectory != null) {
				String preserved = context.getString(R.string.mod_remove_preserved_notice, transaction.preservedDirectory.getAbsolutePath());
				notice = notice.isEmpty() ? preserved : notice + "\n\n" + preserved;
			}
		}
		removeDeletedModSettings(modEntries);
		return notice;
	}

	private void removeDeletedModSettings(List<ModEntry> entries) throws Exception {
		JSONObject settings = loadSettingsJson();
		for (ModEntry entry : entries) {
			removeModGroupAssignment(entry.modId);
			removeModEntry(settings, entry.modId);
			removeModEntry(settings, entry.pckName);
		}
		saveSettingsJson(settings);
	}

	/** Always fresh; callers use these entries to identify installed Workshop items for removal. */
	public List<ModEntry> listInstalledModManifestsUnder(File directory) {
		return scanInstalledManifests(getModsRootDir(), directory);
	}

	public File getWorkshopItemInstallDir(String rawGroupName, String publishedFileId) {
		return getWorkshopItemInstallDir(rawGroupName, publishedFileId, "public");
	}

	public File getWorkshopItemInstallDir(String rawGroupName, String publishedFileId, String workshopBranch) {
		String itemDirectoryName = sanitizeWorkshopPublishedFileId(publishedFileId);
		File parent = getWorkshopGroupDir(rawGroupName);
		String branchName = sanitizeWorkshopBranch(workshopBranch);
		if (!TextUtils.isEmpty(branchName)) {
			parent = new File(parent, branchName);
		}
		return new File(parent, itemDirectoryName);
	}

	private File getWorkshopGroupDir(String rawGroupName) {
		String groupName = sanitizeFileName(normalizeModGroupName(rawGroupName));
		File modsRoot = getModsRootDir();
		return TextUtils.isEmpty(groupName) ? modsRoot : new File(modsRoot, groupName);
	}

	public void deleteWorkshopItemInstall(String installedRootPath, String publishedFileId, List<ModEntry> modEntries) throws Exception {
		File installRoot = firstInstalledRootPath(installedRootPath);
		File modsRoot = getModsRootDir().getAbsoluteFile();
		if (installRoot == null || !installRoot.getName().equals(sanitizeWorkshopPublishedFileId(publishedFileId))) {
			throw new IOException("Invalid Workshop installation directory.");
		}
		maintainMods(modsRoot, false);
		String directory = requireModRelativePath(modsRoot, installRoot);
		List<ModEntry> entries = modEntries == null ? Collections.emptyList() : modEntries;
		try (ModInstallStore.Transaction transaction = new ModInstallStore(context.getFilesDir(), modsRoot)
			.beginRemoval(toInstallModules(modsRoot, entries, ""), directory)) {
			transaction.commit();
		}
		removeDeletedModSettings(entries);
	}

	public List<String> listModGroups() {
		List<String> groups = new ArrayList<>();
		Set<String> hiddenGroups = new LinkedHashSet<>(loadHiddenModGroups());
		for (String groupName : loadDeclaredModGroups()) {
			addVisibleModGroup(groups, groupName, hiddenGroups);
		}
		File modsRoot = getModsRootDir();
		try {
			if (isModScanDirectory(modsRoot)) {
				for (File child : listChildren(modsRoot)) {
					if (!child.getName().startsWith(".") && OsConstants.S_ISDIR(ModInstallStore.modeNoFollow(child))
						&& OsConstants.S_ISREG(ModInstallStore.modeNoFollow(new File(child, MOD_GROUP_MARKER_FILE_NAME)))) {
						addVisibleModGroup(groups, child.getName(), hiddenGroups);
					}
				}
			}
		} catch (IOException e) { throw new UncheckedIOException(e); }
		for (String groupName : loadModGroupAssignments().values()) {
			addVisibleModGroup(groups, groupName, hiddenGroups);
		}
		groups.sort(String::compareToIgnoreCase);
		return groups;
	}

	public String createModGroup(String rawGroupName) throws Exception {
		String groupName = sanitizeModGroupNameOrEmpty(rawGroupName);
		if (TextUtils.isEmpty(groupName)) {
			throw new IOException("Group name cannot be empty.");
		}
		if (isReservedModGroupName(groupName)) {
			return canonicalReservedModGroupName(groupName);
		}
		List<String> groups = loadDeclaredModGroups();
		if (!containsStringIgnoreCase(groups, groupName)) {
			groups.add(groupName);
			saveDeclaredModGroups(groups);
		}
		removeHiddenModGroup(groupName);
		addGroupIdToOrderIfMissing(groupName);
		return groupName;
	}

	public String renameModGroup(String rawOldName, String rawNewName) throws Exception {
		String oldName = sanitizeModGroupNameOrEmpty(rawOldName);
		String newName = sanitizeModGroupNameOrEmpty(rawNewName);
		if (TextUtils.isEmpty(oldName)) {
			throw new IOException("Group name cannot be empty.");
		}
		if (TextUtils.isEmpty(newName)) {
			throw new IOException("Group name cannot be empty.");
		}
		if (isReservedModGroupName(oldName) || isReservedModGroupName(newName)) {
			throw new IOException("Cannot rename reserved group: " + oldName + " -> " + newName);
		}
		if (oldName.equals(newName)) {
			return oldName;
		}
		List<String> existingGroups = listModGroups();
		if (!containsStringIgnoreCase(existingGroups, oldName)) {
			throw new IOException("Group not found: " + oldName);
		}
		if (containsStringIgnoreCase(existingGroups, newName)) {
			throw new IOException("Group already exists: " + newName);
		}
		List<String> groups = loadDeclaredModGroups();
		boolean replaced = replaceStringIgnoreCase(groups, oldName, newName);
		if (!replaced && !containsStringIgnoreCase(groups, newName)) {
			groups.add(newName);
		}
		saveDeclaredModGroups(groups);
		hideModGroup(oldName);
		removeHiddenModGroup(newName);
		reassignModsFromGroup(oldName, newName);
		replaceGroupIdInOrder(oldName, newName);
		List<String> modOrder = loadModOrder(oldName);
		if (!modOrder.isEmpty()) {
			saveModOrder(newName, modOrder);
			saveModOrder(oldName, new ArrayList<>());
		}
		return newName;
	}

	public void deleteModGroup(String rawGroupName) throws Exception {
		String groupName = sanitizeModGroupNameOrEmpty(rawGroupName);
		if (TextUtils.isEmpty(groupName)) {
			throw new IOException("Group name cannot be empty.");
		}
		if (isReservedModGroupName(groupName)) {
			throw new IOException("Cannot delete reserved group: " + groupName);
		}
		removeDeclaredModGroup(groupName);
		hideModGroup(groupName);
		reassignModsFromGroup(groupName, MOD_GROUP_UNGROUPED_NAME);
		removeGroupIdFromOrder(groupName);
		saveModOrder(groupName, new ArrayList<>());
	}

	private boolean isReservedModGroupName(String groupName) {
		return MOD_GROUP_CORE_NAME.equalsIgnoreCase(groupName)
			|| "library".equalsIgnoreCase(groupName)
			|| "libraries".equalsIgnoreCase(groupName)
			|| MOD_GROUP_CONTENT_NAME.equalsIgnoreCase(groupName);
	}

	private String canonicalReservedModGroupName(String groupName) {
		if (MOD_GROUP_CORE_NAME.equalsIgnoreCase(groupName) || "library".equalsIgnoreCase(groupName) || "libraries".equalsIgnoreCase(groupName)) {
			return MOD_GROUP_CORE_NAME;
		}
		if (MOD_GROUP_CONTENT_NAME.equalsIgnoreCase(groupName)) {
			return MOD_GROUP_CONTENT_NAME;
		}
		return groupName;
	}

	private String sanitizeModGroupNameOrEmpty(String rawGroupName) {
		String normalized = normalizeModGroupName(rawGroupName);
		if (TextUtils.isEmpty(normalized)) {
			return "";
		}
		return sanitizeFileName(normalized);
	}

	private String normalizeAssignmentGroupName(String rawGroupName) {
		String raw = rawGroupName == null ? "" : rawGroupName.trim();
		if (TextUtils.isEmpty(raw) || MOD_GROUP_UNGROUPED_NAME.equals(raw)) {
			return MOD_GROUP_UNGROUPED_NAME;
		}
		if (MOD_GROUP_CORE_NAME.equalsIgnoreCase(raw) || "library".equalsIgnoreCase(raw) || "libraries".equalsIgnoreCase(raw)) {
			return MOD_GROUP_CORE_NAME;
		}
		if (MOD_GROUP_CONTENT_NAME.equalsIgnoreCase(raw)) {
			return MOD_GROUP_CONTENT_NAME;
		}
		return sanitizeFileName(raw);
	}

	private void addVisibleModGroup(List<String> groups, String rawGroupName, Set<String> hiddenGroups) {
		String groupName = sanitizeModGroupNameOrEmpty(rawGroupName);
		if (TextUtils.isEmpty(groupName) || isReservedModGroupName(groupName) || containsStringIgnoreCase(hiddenGroups, groupName) || containsStringIgnoreCase(groups, groupName)) {
			return;
		}
		groups.add(groupName);
	}

	private List<String> loadDeclaredModGroups() {
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		return sanitizeCustomGroupList(decodeStringList(preferences.getString(KEY_MOD_GROUPS, "")));
	}

	private void saveDeclaredModGroups(List<String> groupIds) {
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		preferences.edit().putString(KEY_MOD_GROUPS, encodeStringList(sanitizeCustomGroupList(groupIds))).apply();
	}

	private List<String> loadHiddenModGroups() {
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		return sanitizeCustomGroupList(decodeStringList(preferences.getString(KEY_HIDDEN_MOD_GROUPS, "")));
	}

	private void saveHiddenModGroups(List<String> groupIds) {
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		preferences.edit().putString(KEY_HIDDEN_MOD_GROUPS, encodeStringList(sanitizeCustomGroupList(groupIds))).apply();
	}

	private List<String> sanitizeCustomGroupList(List<String> rawGroups) {
		List<String> groups = new ArrayList<>();
		if (rawGroups == null) {
			return groups;
		}
		for (String rawGroup : rawGroups) {
			String groupName = sanitizeModGroupNameOrEmpty(rawGroup);
			if (!TextUtils.isEmpty(groupName) && !isReservedModGroupName(groupName) && !containsStringIgnoreCase(groups, groupName)) {
				groups.add(groupName);
			}
		}
		return groups;
	}

	private void removeDeclaredModGroup(String groupName) {
		List<String> groups = loadDeclaredModGroups();
		if (removeStringIgnoreCase(groups, groupName)) {
			saveDeclaredModGroups(groups);
		}
	}

	private void hideModGroup(String groupName) {
		List<String> hidden = loadHiddenModGroups();
		if (!containsStringIgnoreCase(hidden, groupName)) {
			hidden.add(groupName);
			saveHiddenModGroups(hidden);
		}
	}

	private void removeHiddenModGroup(String groupName) {
		List<String> hidden = loadHiddenModGroups();
		if (removeStringIgnoreCase(hidden, groupName)) {
			saveHiddenModGroups(hidden);
		}
	}

	private void addGroupIdToOrderIfMissing(String groupId) {
		List<String> order = loadModGroupOrder();
		if (!containsStringIgnoreCase(order, groupId)) {
			order.add(groupId);
			saveModGroupOrder(order);
		}
	}

	private void replaceGroupIdInOrder(String oldName, String newName) {
		List<String> order = loadModGroupOrder();
		if (replaceStringIgnoreCase(order, oldName, newName)) {
			saveModGroupOrder(order);
		}
	}

	private void removeGroupIdFromOrder(String groupId) {
		List<String> order = loadModGroupOrder();
		if (removeStringIgnoreCase(order, groupId)) {
			saveModGroupOrder(order);
		}
	}

	public List<String> loadModGroupOrder() {
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		return decodeStringList(preferences.getString(KEY_MOD_GROUP_ORDER, ""));
	}

	public void saveModGroupOrder(List<String> groupIds) {
		saveModGroupOrder(groupIds, false);
	}

	public void saveModGroupOrder(List<String> groupIds, boolean sync) {
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = preferences.edit().putString(KEY_MOD_GROUP_ORDER, encodeStringList(groupIds));
		if (sync) {
			editor.commit();
		} else {
			editor.apply();
		}
	}

	public List<String> loadModOrder(String groupId) {
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		return decodeStringList(preferences.getString(KEY_MOD_ORDER + ":" + safeOrderKey(groupId), ""));
	}

	public void saveModOrder(String groupId, List<String> modIds) {
		saveModOrder(groupId, modIds, false);
	}

	public void saveModOrder(String groupId, List<String> modIds, boolean sync) {
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = preferences.edit().putString(KEY_MOD_ORDER + ":" + safeOrderKey(groupId), encodeStringList(modIds));
		if (sync) {
			editor.commit();
		} else {
			editor.apply();
		}
	}

	public Map<String, String> loadModGroupAssignments() {
		Map<String, String> assignments = new LinkedHashMap<>();
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		try {
			JSONObject object = new JSONObject(preferences.getString(KEY_MOD_GROUP_ASSIGNMENTS, "{}"));
			JSONArray names = object.names();
			if (names == null) {
				return assignments;
			}
			for (int i = 0; i < names.length(); i++) {
				String modId = names.optString(i, "").trim();
				String groupName = normalizeAssignmentGroupName(object.optString(modId, ""));
				if (!TextUtils.isEmpty(modId) && !TextUtils.isEmpty(groupName)) {
					assignments.put(modId, groupName);
				}
			}
		} catch (Exception ignored) {
		}
		return assignments;
	}

	private void saveModGroupAssignments(Map<String, String> assignments) throws JSONException {
		JSONObject object = new JSONObject();
		if (assignments != null) {
			for (Map.Entry<String, String> entry : assignments.entrySet()) {
				String modId = entry.getKey() == null ? "" : entry.getKey().trim();
				String groupName = normalizeAssignmentGroupName(entry.getValue());
				if (!TextUtils.isEmpty(modId) && !TextUtils.isEmpty(groupName)) {
					object.put(modId, groupName);
				}
			}
		}
		context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.putString(KEY_MOD_GROUP_ASSIGNMENTS, object.toString())
			.apply();
	}

	private void reassignModsFromGroup(String oldGroupName, String newGroupName) throws JSONException {
		Map<String, String> assignments = loadModGroupAssignments();
		String targetGroup = normalizeAssignmentGroupName(newGroupName);
		for (ModEntry entry : listInstalledModManifests()) {
			String assigned = assignments.get(entry.modId);
			if (oldGroupName.equals(assigned) || (TextUtils.isEmpty(assigned) && oldGroupName.equals(topLevelDirectory(entry.relativePath)))) {
				assignments.put(entry.modId, targetGroup);
			}
		}
		saveModGroupAssignments(assignments);
	}

	private void removeModGroupAssignment(String modId) throws JSONException {
		if (TextUtils.isEmpty(modId)) {
			return;
		}
		Map<String, String> assignments = loadModGroupAssignments();
		if (assignments.remove(modId) != null) {
			saveModGroupAssignments(assignments);
		}
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
				if (!TextUtils.isEmpty(value) && !containsStringIgnoreCase(values, value)) {
					values.add(value);
				}
			}
		} catch (Exception ignored) {
		}
		return values;
	}

	private boolean containsStringIgnoreCase(Iterable<String> values, String candidate) {
		if (TextUtils.isEmpty(candidate) || values == null) {
			return false;
		}
		for (String value : values) {
			if (candidate.equalsIgnoreCase(value)) {
				return true;
			}
		}
		return false;
	}

	private boolean removeStringIgnoreCase(List<String> values, String candidate) {
		if (TextUtils.isEmpty(candidate) || values == null) {
			return false;
		}
		for (int i = values.size() - 1; i >= 0; i--) {
			if (candidate.equalsIgnoreCase(values.get(i))) {
				values.remove(i);
				return true;
			}
		}
		return false;
	}

	private boolean replaceStringIgnoreCase(List<String> values, String oldValue, String newValue) {
		if (TextUtils.isEmpty(oldValue) || TextUtils.isEmpty(newValue) || values == null) {
			return false;
		}
		for (int i = 0; i < values.size(); i++) {
			if (oldValue.equalsIgnoreCase(values.get(i))) {
				values.set(i, newValue);
				return true;
			}
		}
		return false;
	}

	public void moveModToGroup(ModEntry modEntry, String rawGroupName) throws Exception {
		if (modEntry == null || TextUtils.isEmpty(modEntry.modId)) {
			return;
		}
		String groupName = normalizeAssignmentGroupName(rawGroupName);
		if (!MOD_GROUP_UNGROUPED_NAME.equals(groupName) && !isReservedModGroupName(groupName)) {
			createModGroup(groupName);
		}
		Map<String, String> assignments = loadModGroupAssignments();
		assignments.put(modEntry.modId, groupName);
		saveModGroupAssignments(assignments);
	}

	public void movePreparedImportFilesToGroup(PreparedModImport preparedImport, String rawGroupName) throws Exception {
		if (preparedImport == null || preparedImport.incomingEntries == null) {
			return;
		}
		for (ModEntry entry : preparedImport.incomingEntries) {
			moveModToGroup(entry, rawGroupName);
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

	private String sanitizeWorkshopPublishedFileId(String publishedFileId) {
		String sanitized = sanitizeFileName(publishedFileId == null ? "" : publishedFileId.trim());
		return TextUtils.isEmpty(sanitized) ? "unknown_workshop_item" : sanitized;
	}

	private String sanitizeWorkshopBranch(String workshopBranch) {
		String sanitized = sanitizeFileName(workshopBranch == null ? "" : workshopBranch.trim());
		return TextUtils.isEmpty(sanitized) ? "public" : sanitized;
	}

	private File firstInstalledRootPath(String installedRootPath) {
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

	private String topLevelDirectory(String relativePath) {
		if (TextUtils.isEmpty(relativePath)) {
			return "";
		}
		String normalized = relativePath.replace('\\', '/');
		int slash = normalized.indexOf('/');
		if (slash <= 0) {
			return "";
		}
		return normalized.substring(0, slash);
	}

	private boolean isModEntryUnderDirectory(ModEntry modEntry, File directory) {
		if (modEntry == null || modEntry.manifestFile == null || directory == null) {
			return false;
		}
		return isSameOrDescendant(modEntry.manifestFile, directory);
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
		File root = getModsRootDir();
		return scanInstalledManifests(root, root);
	}

	/** Blocking display-only read; share immutable manifest data, never settings or group state. */
	public List<ModEntry> listInstalledModManifestsForDisplay() {
		File root = getModsRootDir();
		try {
			// Check the requested path, not just the resolved cache key. Do not return another
			// root's cached data through a symlink inside the application files directory.
			if (!isModScanDirectory(root)) return Collections.emptyList();
			return ModManifestCache.get(root, () -> scanManifests(root, root, ScanMode.INSTALLED));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private List<ModEntry> scanInstalledManifests(File root, File directory) {
		try {
			return scanManifests(root, directory, ScanMode.INSTALLED);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
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
		return loadModProfileState(settings, installedMods, true);
	}

	public ModProfileState loadModProfileStateForDisplay(JSONObject settings, List<ModEntry> installedMods) throws Exception {
		return loadModProfileState(settings, installedMods, false);
	}

	private ModProfileState loadModProfileState(JSONObject settings, List<ModEntry> installedMods, boolean persist) throws Exception {
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
		if (persist) persistModProfiles(sanitizedProfiles, activeId);
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
			listInstalledModManifestsForDisplay().size(),
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

	private boolean isLocalModSource(String source) {
		if (TextUtils.isEmpty(source)) {
			return true;
		}
		String normalized = source.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		return MOD_SOURCE_MODS_DIRECTORY.equals(normalized)
			|| "modsdirectory".equals(normalized)
			|| "local".equals(normalized);
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
			if ((modId.equals(name) || pckName.equals(name)) && isLocalModSource(source)) {
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

	public List<String> loadRuntimeModListOrder(JSONObject settings) throws JSONException {
		List<String> order = new ArrayList<>();
		JSONObject modSettings = ensureModSettings(settings);
		JSONArray modList = modSettings.optJSONArray("mod_list");
		if (modList == null) {
			return order;
		}
		for (int i = 0; i < modList.length(); i++) {
			JSONObject item = modList.optJSONObject(i);
			if (item == null) {
				continue;
			}
			String source = item.optString("source", "");
			if (!isLocalModSource(source)) {
				continue;
			}
			String id = firstNonEmpty(item.optString("id", ""), item.optString("name", "")).trim();
			if (!TextUtils.isEmpty(id)) {
				order.add(id);
			}
		}
		return order;
	}

	public void saveRuntimeModListOrder(List<ModEntry> orderedEntries) throws Exception {
		JSONObject settings = loadSettingsJson();
		JSONObject modSettings = ensureModSettings(settings);
		JSONArray newModList = new JSONArray();
		Set<String> seen = new LinkedHashSet<>();
		if (orderedEntries != null) {
			for (ModEntry entry : orderedEntries) {
				if (entry == null || TextUtils.isEmpty(entry.modId) || !seen.add(entry.modId)) {
					continue;
				}
				JSONObject item = new JSONObject();
				item.put("id", entry.modId);
				item.put("source", MOD_SOURCE_MODS_DIRECTORY);
				item.put("is_enabled", !isModDisabled(settings, entry));
				newModList.put(item);
			}
		}
		modSettings.put("mod_list", newModList);
		saveSettingsJson(settings);
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
				if (modId.equals(id) && isLocalModSource(source)) {
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
			if (modId.equals(id) && isLocalModSource(source)) {
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
			if (modId.equals(name) && isLocalModSource(source)) {
				continue;
			}
			newDisabledMods.put(item);
		}
		modSettings.put("disabled_mods", newDisabledMods);
	}

	// Only the Android app boundary may use a system path alias (/data/user/0 vs /data/data).
	// Validate every component of an arbitrary Workshop subtree before entering it directly.
	private boolean isModScanDirectory(File directory) throws IOException {
		File boundary = context.getFilesDir().getAbsoluteFile().getParentFile();
		if (boundary == null || directory == null) return false;
		return isDirectoryBelow(boundary, directory.getAbsoluteFile())
			|| isDirectoryBelow(boundary.getCanonicalFile(), directory.getAbsoluteFile());
	}

	private static boolean isDirectoryBelow(File boundary, File directory) throws IOException {
		if (!directory.getPath().startsWith(boundary.getPath() + File.separator)) return false;
		ArrayDeque<File> chain = new ArrayDeque<>();
		for (File part = directory; !part.equals(boundary); part = part.getParentFile()) chain.push(part);
		while (!chain.isEmpty()) {
			File part = chain.pop();
			if (".".equals(part.getName()) || "..".equals(part.getName())
				|| !OsConstants.S_ISDIR(ModInstallStore.modeNoFollow(part))) return false;
		}
		return true;
	}

	private enum ScanMode { INSTALLED, STAGING, LAUNCH, RECOVER_ONLY }

	private List<ModEntry> scanManifests(File root, File directory, ScanMode purpose) throws IOException {
		boolean staging = purpose == ScanMode.STAGING;
		List<ModEntry> results = new ArrayList<>();
		if (!isModScanDirectory(directory)) {
			if (purpose != ScanMode.INSTALLED) throw new IOException("Unsafe or missing MOD directory: " + directory);
			return results;
		}
		if (!directory.equals(root)) {
			// A persisted subtree can use the other Android spelling. Rebase only *after*
			// checking for links all the way to the trusted boundary, never canonicalize to enter one.
			if (!isModScanDirectory(root)) return results;
			String rootPath = root.getCanonicalPath();
			String path = directory.getCanonicalPath();
			if (path.equals(rootPath)) directory = root;
			else if (path.startsWith(rootPath + File.separator)) directory = new File(root, path.substring(rootPath.length() + 1));
			else return results;
			for (File part = directory; !part.equals(root); part = part.getParentFile()) {
				if (isLegacyTransactionDirectory(part.getName())) return results;
			}
		}
		ArrayDeque<File> pending = new ArrayDeque<>();
		pending.push(directory);
		while (!pending.isEmpty()) {
			File current = pending.pop();
			List<File> json = Collections.emptyList();
			Set<String> payloads = Collections.emptySet();
			String firstPayload = "";
			for (File child : listChildren(current)) {
				int mode = ModInstallStore.modeNoFollow(child);
				String name = child.getName();
				if (OsConstants.S_ISDIR(mode)) {
					if (isLegacyTransactionDirectory(name)) {
						if (staging) throw new IOException("Reserved MOD transaction directory: " + child);
						if (purpose != ScanMode.INSTALLED) {
							File restored = recoverWorkshopTransaction(child);
							if (restored != null) pending.push(restored);
						}
					} else {
						pending.push(child);
					}
				} else if (OsConstants.S_ISREG(mode)) {
					if (purpose == ScanMode.RECOVER_ONLY) continue;
					if (hasExtension(name, ".json")) {
						if (json.isEmpty()) json = new ArrayList<>();
						json.add(child);
					} else if (hasExtension(name, ".pck") || hasExtension(name, ".dll")) {
						if (payloads.isEmpty()) payloads = new HashSet<>();
						payloads.add(name);
						if (firstPayload.isEmpty() || name.compareToIgnoreCase(firstPayload) < 0) firstPayload = name;
					}
				} else if (staging || mode == 0) {
					throw new IOException("Missing or unsupported MOD file: " + child);
				} // Symlinks (even dangling ones) are neither directories nor MOD files.
			}
			String fallback = stripExtension(firstPayload);
			for (File file : json) {
				ModEntry entry = tryParseModEntry(root, file, payloads, fallback, staging);
				if (entry != null) results.add(entry);
			}
		}
		if (purpose == ScanMode.INSTALLED) results.sort(Comparator.comparing(entry -> entry.relativePath, String::compareToIgnoreCase));
		return results;
	}

	private static File[] listChildren(File dir) throws IOException {
		File[] children = dir.listFiles();
		if (children == null) throw new IOException("Unable to list directory: " + dir);
		return children;
	}

	private static boolean hasExtension(String name, String extension) {
		return name.regionMatches(true, name.length() - extension.length(), extension, 0, extension.length());
	}

	private String readModId(JSONObject manifest) {
		return firstNonEmpty(manifest.optString("id", ""), manifest.optString("mod_id", ""),
			manifest.optString("modId", ""), manifest.optString("ID", ""));
	}

	private ModEntry tryParseModEntry(File rootDirectory, File manifestFile, Set<String> payloads,
		String fallbackPayload, boolean staging) throws IOException {
		try {
			String content = readTextFile(manifestFile);
			if (TextUtils.isEmpty(content)) {
				return null;
			}
			JSONObject manifest = new JSONObject(content);
			if (!validateModFileNames(manifest, manifestFile, staging)) return null;
			String modId = readModId(manifest);
			if (!isSafeModBaseName(modId)) {
				return null;
			}
			String pckName = firstNonEmpty(
				manifest.optString("pck_name", ""),
				manifest.optString("pckName", ""),
				manifest.optString("PckName", ""),
				fallbackPayload,
				modId
			).trim();
			if (!isSafeModBaseName(pckName)) {
				return null;
			}
			String displayName = firstNonEmpty(manifest.optString("name", ""), manifest.optString("display_name", ""), modId).trim();
			String version = firstNonEmpty(manifest.optString("version", ""), manifest.optString("mod_version", ""), manifest.optString("Version", ""));
			String authors = readAuthors(manifest);
			String description = firstNonEmpty(manifest.optString("description", ""), manifest.optString("desc", ""), manifest.optString("Description", ""));
			String category = readCategory(manifest);
			String minGameVersion = firstNonEmpty(
				manifest.optString("min_game_version", ""),
				manifest.optString("minGameVersion", ""),
				manifest.optString("game_version", "")
			).trim();
			boolean declaredHasPck = manifest.has("has_pck")
				? manifest.optBoolean("has_pck", false)
				: manifest.optBoolean("hasPck", false);
			boolean declaredHasDll = manifest.has("has_dll")
				? manifest.optBoolean("has_dll", false)
				: manifest.optBoolean("hasDll", false);
			List<ModDependency> dependencies = readDependencies(manifest);
			// Game loads <modId>.pck / <modId>.dll; also accept pckName for loosely packaged MODs.
			boolean hasPck = payloads.contains(modId + ".pck") || payloads.contains(pckName + ".pck");
			boolean hasDll = payloads.contains(modId + ".dll") || payloads.contains(pckName + ".dll");
			return new ModEntry(
				manifestFile,
				modId,
				pckName,
				displayName,
				getRelativePath(rootDirectory, manifestFile),
				version,
				authors,
				description,
				category,
				minGameVersion,
				dependencies,
				declaredHasPck,
				declaredHasDll,
				hasPck,
				hasDll
			);
		} catch (JSONException ignored) { // Other JSON resources are not manifests.
			return null;
		}
	}

	private String stripExtension(String fileName) {
		if (fileName == null) {
			return "";
		}
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
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

	/**
	 * Reads required dependencies from the manifest {@code dependencies} key only
	 * (not optional_dependencies / load_after). Matches original {@code ModManifest}.
	 */
	private List<ModDependency> readDependencies(JSONObject manifest) {
		List<ModDependency> dependencies = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		readDependencyArray(manifest.optJSONArray("dependencies"), dependencies, seen);
		return dependencies;
	}

	private void readDependencyArray(JSONArray array, List<ModDependency> out, Set<String> seen) {
		if (array == null) {
			return;
		}
		for (int i = 0; i < array.length(); i++) {
			Object item = array.opt(i);
			String id;
			String minVersion = "";
			if (item instanceof JSONObject object) {
				id = firstNonEmpty(object.optString("id", ""), object.optString("name", ""), object.optString("mod_id", ""));
				minVersion = firstNonEmpty(object.optString("min_version", ""), object.optString("version", ""));
			} else {
				id = item == null ? "" : String.valueOf(item).trim();
			}
			id = id == null ? "" : id.trim();
			if (id.isEmpty() || seen.contains(id)) {
				continue;
			}
			seen.add(id);
			out.add(new ModDependency(id, minVersion == null ? "" : minVersion.trim()));
		}
	}

	public String getModNote(String modId) {
		if (TextUtils.isEmpty(modId)) {
			return "";
		}
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		try {
			JSONObject notes = new JSONObject(preferences.getString(KEY_MOD_NOTES, "{}"));
			return notes.optString(modId, "").trim();
		} catch (Exception ignored) {
			return "";
		}
	}

	public void setModNote(String modId, String note) throws JSONException {
		if (TextUtils.isEmpty(modId)) {
			return;
		}
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		JSONObject notes;
		try {
			notes = new JSONObject(preferences.getString(KEY_MOD_NOTES, "{}"));
		} catch (Exception ignored) {
			notes = new JSONObject();
		}
		String trimmed = note == null ? "" : note.trim();
		if (TextUtils.isEmpty(trimmed)) {
			notes.remove(modId);
		} else {
			notes.put(modId, trimmed);
		}
		preferences.edit().putString(KEY_MOD_NOTES, notes.toString()).apply();
	}

	public Map<String, String> loadAllModNotes() {
		Map<String, String> result = new LinkedHashMap<>();
		SharedPreferences preferences = context.getSharedPreferences(MOD_PROFILE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		try {
			JSONObject notes = new JSONObject(preferences.getString(KEY_MOD_NOTES, "{}"));
			JSONArray names = notes.names();
			if (names == null) {
				return result;
			}
			for (int i = 0; i < names.length(); i++) {
				String modId = names.optString(i, "").trim();
				if (TextUtils.isEmpty(modId)) {
					continue;
				}
				String note = notes.optString(modId, "").trim();
				if (!TextUtils.isEmpty(note)) {
					result.put(modId, note);
				}
			}
		} catch (Exception ignored) {
		}
		return result;
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
		if (!currentRelativePath.isEmpty()) {
			outputStream.putNextEntry(new ZipEntry(normalizedPrefix + currentRelativePath + "/"));
			outputStream.closeEntry();
		}
		for (File child : listChildren(currentFile)) {
			int mode = ModInstallStore.modeNoFollow(child);
			if (OsConstants.S_ISLNK(mode)) continue;
			if (OsConstants.S_ISDIR(mode)) {
				zipDirectoryRecursive(rootDirectory, child, outputStream, normalizedPrefix);
				continue;
			}
			if (!OsConstants.S_ISREG(mode)) throw new IOException("Not a regular backup file: " + child);
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

	private void extractArchiveFile(File sourceFile, File targetDirectory) throws Exception {
		ArchiveFormatDetector.Format format = ArchiveFormatDetector.detect(sourceFile);
		if (format == ArchiveFormatDetector.Format.UNKNOWN) {
			throw new ArchiveExtractor.UnsupportedArchiveException("Unsupported or unrecognized archive format.");
		}
		ArchiveExtractor.extract(sourceFile, targetDirectory, null, null);
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
		int sourceMode = ModInstallStore.modeNoFollow(source);
		if (OsConstants.S_ISLNK(sourceMode)) return;
		int targetMode = ModInstallStore.modeNoFollow(target);
		if (OsConstants.S_ISDIR(sourceMode)) {
			if (targetMode != 0 && !OsConstants.S_ISDIR(targetMode)) throw new IOException("Unsafe copy destination: " + target);
			ensureDirectory(target);
			for (File child : listChildren(source)) {
				copyRecursively(child, new File(target, child.getName()));
			}
			return;
		}
		if (!OsConstants.S_ISREG(sourceMode) || (targetMode != 0 && !OsConstants.S_ISREG(targetMode))) {
			throw new IOException("Not a regular file copy: " + source + " -> " + target);
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
		for (File child : listChildren(sourceDirectory)) {
			copyRecursively(child, new File(targetDirectory, child.getName()));
		}
	}

	private void copyDirectoryContentsForModImport(File sourceDirectory, File targetDirectory) throws IOException {
		ensureDirectory(targetDirectory);
		for (File child : listChildren(sourceDirectory)) {
			if (isWorkshopDownloadSupportFile(child)) {
				continue;
			}
			copyRecursively(child, new File(targetDirectory, child.getName()));
		}
	}

	private File findSingleImportArchive(File sourceDirectory) throws IOException {
		File[] children = sourceDirectory.listFiles(file -> !isWorkshopDownloadSupportFile(file));
		if (children == null) throw new IOException("Unable to list download directory: " + sourceDirectory);
		if (children.length != 1 || !OsConstants.S_ISREG(ModInstallStore.modeNoFollow(children[0]))) {
			return null;
		}
		return ArchiveExtractor.isArchive(children[0]) ? children[0] : null;
	}

	private boolean isWorkshopDownloadSupportFile(File file) {
		if (file == null) {
			return false;
		}
		String name = file.getName();
		return ".chunks".equals(name) || "metadata.json".equals(name) || "download.log".equals(name);
	}

	private void validateRestoredDataRoot(File restoredDataRoot) throws IOException {
		if (!new File(restoredDataRoot, "files").isDirectory()) {
			throw new IOException(context.getString(R.string.full_data_backup_invalid_archive));
		}
	}

	public void deleteRecursively(File file) {
		if (file == null) return;
		try {
			deleteTreeNoFollow(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Caller supplies a trusted parent; unlink symlink entries, never their destinations. */
	static void deleteTreeNoFollow(File file) throws IOException {
		int mode = ModInstallStore.modeNoFollow(file);
		if (mode == 0) return;
		if (OsConstants.S_ISDIR(mode)) {
			for (File child : listChildren(file)) deleteTreeNoFollow(child);
		}
		// For links (including dangling links), delete only the directory entry itself.
		if (!file.delete()) throw new IOException("Unable to delete: " + file);
	}

	private void deleteChildren(File directory) throws IOException {
		deleteChildren(directory, null);
	}

	private void deleteChildren(File directory, File except) throws IOException {
		int mode = ModInstallStore.modeNoFollow(directory);
		if (mode == 0) return;
		if (!OsConstants.S_ISDIR(mode)) throw new IOException("Not a restore directory: " + directory);
		for (File child : listChildren(directory)) {
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
			deleteTreeNoFollow(child);
		}
	}

	private static int legacyMarker(String name, String marker) {
		if (!name.startsWith(".")) return -1;
		int at = name.lastIndexOf(marker);
		return at > 1 && at + marker.length() < name.length()
			&& isSafeModBaseName(name.substring(1, at)) ? at : -1;
	}

	private static boolean isLegacyTransactionDirectory(String name) {
		return legacyMarker(name, ".backup-") >= 0 || legacyMarker(name, ".incoming-") >= 0;
	}

	/** Called under the root lease for a real transaction directory; visit any restored item in this same walk. */
	private static File recoverWorkshopTransaction(File child) throws IOException {
		String name = child.getName();
		int backup = legacyMarker(name, ".backup-");
		if (backup >= 0) {
			File target = new File(child.getParentFile(), name.substring(1, backup));
			int targetMode = ModInstallStore.modeNoFollow(target);
			if (targetMode == 0) {
				if (!child.renameTo(target)) throw new IOException("Unable to restore Workshop backup: " + child);
				return target;
			}
			if (!OsConstants.S_ISDIR(targetMode)) throw new IOException("Keeping Workshop backup; unexpected target: " + target);
		}
		deleteTreeNoFollow(child);
		return null;
	}

	private boolean isSameOrDescendant(File file, File possibleAncestor) {
		if (file == null || possibleAncestor == null) {
			return false;
		}
		try {
			String filePath = file.getCanonicalPath();
			String ancestorPath = possibleAncestor.getCanonicalPath();
			return filePath.equals(ancestorPath) || filePath.startsWith(ancestorPath + File.separator);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private boolean isSymbolicLink(File file) {
		try {
			return OsConstants.S_ISLNK(ModInstallStore.modeNoFollow(file));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
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

	public static final class WorkshopModImportResult {
		public final File installRoot;
		public final List<ModEntry> installedEntries;
		public final String importedName;
		public final String fileNotice;
		private final ModInstallStore.Transaction transaction;

		WorkshopModImportResult(File installRoot, List<ModEntry> installedEntries, String importedName, ModInstallStore.Transaction transaction, String fileNotice) {
			this.installRoot = installRoot;
			this.transaction = transaction;
			this.installedEntries = Collections.unmodifiableList(new ArrayList<>(installedEntries));
			this.importedName = importedName == null ? "" : importedName;
			this.fileNotice = fileNotice;
		}
	}

	public static final class PreparedModImport {
		public final File stagingRoot;
		public final String displayName;
		public final String normalizedName;
		public final List<ModEntry> incomingEntries;
		public final boolean fullPackage;
		private final File modsRoot;
		private boolean replaceTrackedFiles;
		private String fileNotice = "";

		PreparedModImport(File stagingRoot, String displayName, String normalizedName, List<ModEntry> incomingEntries, File modsRoot, boolean fullPackage) {
			this.stagingRoot = stagingRoot;
			this.displayName = displayName == null ? normalizedName : displayName;
			this.normalizedName = normalizedName;
			this.incomingEntries = Collections.unmodifiableList(new ArrayList<>(incomingEntries));
			this.modsRoot = modsRoot;
			this.fullPackage = fullPackage;
			this.replaceTrackedFiles = fullPackage;
		}

		public boolean isReplaceTrackedFiles() {
			return replaceTrackedFiles;
		}

		public void setReplaceTrackedFiles(boolean replaceTrackedFiles) {
			this.replaceTrackedFiles = fullPackage && replaceTrackedFiles;
		}

		public String getFileNotice() {
			return fileNotice;
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

	public static final class ModImportPathConflict {
		public final String relativePath;
		public final String existingOwnerLabel;

		ModImportPathConflict(String relativePath, String existingOwnerLabel) {
			this.relativePath = relativePath == null ? "" : relativePath;
			this.existingOwnerLabel = existingOwnerLabel == null ? "" : existingOwnerLabel;
		}
	}

	public static final class ModImportPathConflictException extends IOException {
		private final List<ModImportPathConflict> conflicts;

		ModImportPathConflictException(List<ModImportPathConflict> conflicts) {
			super("MOD import would overwrite installed file(s): " + summarizePathConflicts(conflicts));
			this.conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts == null ? Collections.emptyList() : conflicts));
		}

		public List<ModImportPathConflict> getConflicts() {
			return conflicts;
		}

		private static String summarizePathConflicts(List<ModImportPathConflict> conflicts) {
			if (conflicts == null || conflicts.isEmpty()) {
				return "unknown";
			}
			int count = Math.min(6, conflicts.size());
			List<String> paths = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				paths.add(conflicts.get(i).relativePath);
			}
			String summary = String.join(", ", paths);
			if (conflicts.size() > count) {
				summary += ", … +" + (conflicts.size() - count);
			}
			return summary;
		}
	}

	public static final class ModDependency {
		public final String id;
		public final String minVersion;

		public ModDependency(String id, String minVersion) {
			this.id = id == null ? "" : id.trim();
			this.minVersion = minVersion == null ? "" : minVersion.trim();
		}

		public String displayLabel() {
			if (TextUtils.isEmpty(minVersion)) {
				return id;
			}
			return id + " ≥" + minVersion;
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
		public final String minGameVersion;
		/** Required dependencies from the manifest {@code dependencies} key. */
		public final List<ModDependency> dependencies;
		/** Display labels for dependencies (legacy-friendly). */
		public final List<String> dependencyLabels;
		/** Value of manifest {@code has_pck}. */
		public final boolean declaredHasPck;
		/** Value of manifest {@code has_dll}. */
		public final boolean declaredHasDll;
		/** Whether a matching .pck file exists next to the manifest. */
		public final boolean hasPck;
		/** Whether a matching .dll file exists next to the manifest. */
		public final boolean hasDll;

		ModEntry(
			File manifestFile,
			String modId,
			String pckName,
			String displayName,
			String relativePath,
			String version,
			String authors,
			String description,
			String category,
			String minGameVersion,
			List<ModDependency> dependencies,
			boolean declaredHasPck,
			boolean declaredHasDll,
			boolean hasPck,
			boolean hasDll
		) {
			this.manifestFile = manifestFile;
			this.modId = modId;
			this.pckName = TextUtils.isEmpty(pckName) ? modId : pckName;
			this.displayName = displayName;
			this.relativePath = relativePath;
			this.version = version;
			this.authors = authors;
			this.description = description;
			this.category = category == null ? "" : category;
			this.minGameVersion = minGameVersion == null ? "" : minGameVersion;
			this.dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies == null ? Collections.emptyList() : dependencies));
			List<String> labels = new ArrayList<>();
			for (ModDependency dependency : this.dependencies) {
				labels.add(dependency.displayLabel());
			}
			this.dependencyLabels = Collections.unmodifiableList(labels);
			this.declaredHasPck = declaredHasPck;
			this.declaredHasDll = declaredHasDll;
			this.hasPck = hasPck;
			this.hasDll = hasDll;
		}

		/** Folder containing the MOD payload (parent of the manifest file). */
		public File directory() {
			return manifestFile == null ? null : manifestFile.getParentFile();
		}
	}
}
