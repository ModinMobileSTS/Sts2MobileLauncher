package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;

public final class ExtraSettingsPreferences {
	private static final String PREFERENCES_NAME = "sts2_extra_settings";
	private static final String KEY_FIRST_RUN_SETUP_COMPLETED = "first_run_setup_completed";
	private static final String KEY_LAST_SELECTED_MAIN_TAB = "last_selected_main_tab";
	private static final String KEY_UPDATE_CHECK_ENABLED = "update_check_enabled";
	private static final String KEY_LOG_LEVEL = "log_level";

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

	public static String getLogLevel(Context context, String fallback) {
		String value = getPreferences(context).getString(KEY_LOG_LEVEL, fallback);
		return value == null ? fallback : value;
	}

	public static void setLogLevel(Context context, String logLevel) {
		getPreferences(context).edit().putString(KEY_LOG_LEVEL, logLevel).apply();
	}

	private static SharedPreferences getPreferences(Context context) {
		return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
	}
}
