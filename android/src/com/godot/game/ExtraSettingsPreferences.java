package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;

public final class ExtraSettingsPreferences {
	private static final String PREFERENCES_NAME = "sts2_extra_settings";
	private static final String KEY_FIRST_RUN_SETUP_COMPLETED = "first_run_setup_completed";
	private static final String KEY_LAST_SELECTED_MAIN_TAB = "last_selected_main_tab";
	public static final String LAUNCHER_STARTUP_SETTINGS = "settings";
	public static final String LAUNCHER_STARTUP_GAME = "game";

	private static final String KEY_UPDATE_CHECK_ENABLED = "update_check_enabled";
	private static final String KEY_LAUNCHER_STARTUP_BEHAVIOR = "launcher_startup_behavior";
	private static final String KEY_LOG_LEVEL = "log_level";
	private static final String KEY_PERFORMANCE_OVERLAY_ENABLED = "android_performance_overlay_enabled";
	private static final String KEY_DISPLAY_REFRESH_RATE_MODE = "android_display_refresh_rate_mode";
	private static final String LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED = "android_high_refresh_rate_enabled";

	private ExtraSettingsPreferences() {
	}

	public static boolean isFirstRunSetupCompleted(Context context) {
		return getPreferences(context).getBoolean(KEY_FIRST_RUN_SETUP_COMPLETED, false);
	}

	public static void setFirstRunSetupCompleted(Context context, boolean completed) {
		getPreferences(context).edit().putBoolean(KEY_FIRST_RUN_SETUP_COMPLETED, completed).apply();
	}

	public static int getLastSelectedMainTab(Context context, int fallbackItemId) {
		return getPreferences(context).getInt(KEY_LAST_SELECTED_MAIN_TAB, fallbackItemId);
	}

	public static void setLastSelectedMainTab(Context context, int itemId) {
		getPreferences(context).edit().putInt(KEY_LAST_SELECTED_MAIN_TAB, itemId).apply();
	}

	public static boolean isUpdateCheckEnabled(Context context) {
		return getPreferences(context).getBoolean(KEY_UPDATE_CHECK_ENABLED, true);
	}

	public static void setUpdateCheckEnabled(Context context, boolean enabled) {
		getPreferences(context).edit().putBoolean(KEY_UPDATE_CHECK_ENABLED, enabled).apply();
	}

	public static String getLauncherStartupBehavior(Context context) {
		return normalizeLauncherStartupBehavior(getPreferences(context).getString(KEY_LAUNCHER_STARTUP_BEHAVIOR, LAUNCHER_STARTUP_SETTINGS));
	}

	public static void setLauncherStartupBehavior(Context context, String behavior) {
		getPreferences(context).edit().putString(KEY_LAUNCHER_STARTUP_BEHAVIOR, normalizeLauncherStartupBehavior(behavior)).apply();
	}

	private static String normalizeLauncherStartupBehavior(String behavior) {
		if (LAUNCHER_STARTUP_GAME.equals(behavior)) {
			return LAUNCHER_STARTUP_GAME;
		}
		return LAUNCHER_STARTUP_SETTINGS;
	}

	public static String getLogLevel(Context context, String fallback) {
		String value = getPreferences(context).getString(KEY_LOG_LEVEL, fallback);
		return value == null ? fallback : value;
	}

	public static void setLogLevel(Context context, String logLevel) {
		getPreferences(context).edit().putString(KEY_LOG_LEVEL, logLevel).apply();
	}

	public static boolean isPerformanceOverlayEnabled(Context context) {
		return getPreferences(context).getBoolean(KEY_PERFORMANCE_OVERLAY_ENABLED, false);
	}

	public static void setPerformanceOverlayEnabled(Context context, boolean enabled) {
		getPreferences(context).edit().putBoolean(KEY_PERFORMANCE_OVERLAY_ENABLED, enabled).apply();
	}

	public static String getDisplayRefreshRateMode(Context context) {
		SharedPreferences preferences = getPreferences(context);
		boolean hasMode = preferences.contains(KEY_DISPLAY_REFRESH_RATE_MODE);
		if (hasMode) {
			String rawMode;
			try {
				rawMode = preferences.getString(KEY_DISPLAY_REFRESH_RATE_MODE, null);
			} catch (ClassCastException ignored) {
				rawMode = null;
			}
			String normalized = ExtraSettingsRepository.normalizeDisplayRefreshRateMode(rawMode);
			if (!normalized.equals(rawMode) || preferences.contains(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED)) {
				preferences.edit()
					.putString(KEY_DISPLAY_REFRESH_RATE_MODE, normalized)
					.remove(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED)
					.apply();
			}
			return normalized;
		}

		if (preferences.contains(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED)) {
			boolean enabled;
			try {
				enabled = preferences.getBoolean(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED, true);
			} catch (ClassCastException ignored) {
				enabled = true;
			}
			String migrated = enabled
				? ExtraSettingsRepository.DISPLAY_REFRESH_RATE_HIGH
				: ExtraSettingsRepository.DISPLAY_REFRESH_RATE_SYSTEM;
			preferences.edit()
				.putString(KEY_DISPLAY_REFRESH_RATE_MODE, migrated)
				.remove(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED)
				.apply();
			return migrated;
		}
		return ExtraSettingsRepository.DISPLAY_REFRESH_RATE_HIGH;
	}

	public static void setDisplayRefreshRateMode(Context context, String mode) {
		String normalized = ExtraSettingsRepository.normalizeDisplayRefreshRateMode(mode);
		getPreferences(context).edit()
			.putString(KEY_DISPLAY_REFRESH_RATE_MODE, normalized)
			.remove(LEGACY_KEY_HIGH_REFRESH_RATE_ENABLED)
			.apply();
	}

	private static SharedPreferences getPreferences(Context context) {
		return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
	}
}
