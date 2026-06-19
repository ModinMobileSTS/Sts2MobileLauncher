package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;

public final class SteamWorkshopPreferences {
	public static final int DEFAULT_APP_ID = 2868840;
	private static final String PREFERENCES_NAME = "sts2_steam_workshop";
	private static final String KEY_AUTO_CHECK_UPDATES = "auto_check_updates";
	private static final String KEY_DOWNLOAD_GROUP = "download_group";
	private static final String KEY_CONCURRENT_CHUNKS = "concurrent_chunks";
	private static final String KEY_DIRECT_ACCESS_ENABLED = "direct_access_enabled";

	private SteamWorkshopPreferences() {
	}

	public static boolean isAutoCheckUpdatesEnabled(Context context) {
		return preferences(context).getBoolean(KEY_AUTO_CHECK_UPDATES, true);
	}

	public static void setAutoCheckUpdatesEnabled(Context context, boolean enabled) {
		preferences(context).edit().putBoolean(KEY_AUTO_CHECK_UPDATES, enabled).apply();
	}

	public static String getDownloadGroup(Context context) {
		return preferences(context).getString(KEY_DOWNLOAD_GROUP, "workshop");
	}

	public static void setDownloadGroup(Context context, String groupName) {
		preferences(context).edit().putString(KEY_DOWNLOAD_GROUP, sanitizeGroupName(groupName)).apply();
	}

	public static int getConcurrentChunks(Context context) {
		return Math.max(1, Math.min(8, preferences(context).getInt(KEY_CONCURRENT_CHUNKS, 4)));
	}

	public static void setConcurrentChunks(Context context, int value) {
		preferences(context).edit().putInt(KEY_CONCURRENT_CHUNKS, Math.max(1, Math.min(8, value))).apply();
	}

	public static boolean isDirectAccessEnabled(Context context) {
		return preferences(context).getBoolean(KEY_DIRECT_ACCESS_ENABLED, true);
	}

	public static void setDirectAccessEnabled(Context context, boolean enabled) {
		preferences(context).edit().putBoolean(KEY_DIRECT_ACCESS_ENABLED, enabled).apply();
	}

	private static SharedPreferences preferences(Context context) {
		return context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
	}

	private static String sanitizeGroupName(String value) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isEmpty()) {
			return "workshop";
		}
		return trimmed.replace('\\', '_').replace('/', '_');
	}
}
