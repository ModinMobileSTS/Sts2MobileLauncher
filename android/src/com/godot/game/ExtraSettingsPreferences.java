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

	private static SharedPreferences getPreferences(Context context) {
		return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
	}
}
