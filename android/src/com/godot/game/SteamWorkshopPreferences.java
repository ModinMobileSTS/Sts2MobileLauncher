package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;

public final class SteamWorkshopPreferences {
	public static final int DEFAULT_APP_ID = 2868840;
	public static final String BRANCH_MODE_AUTO = "auto";
	public static final String BRANCH_MODE_PUBLIC = "public";
	public static final String BRANCH_MODE_PUBLIC_BETA = "public-beta";
	public static final String BRANCH_MODE_CUSTOM = "custom";
	public static final String BRANCH_MODE_ASK = "ask";
	private static final String PREFERENCES_NAME = "sts2_steam_workshop";
	private static final String KEY_AUTO_CHECK_UPDATES = "auto_check_updates";
	private static final String KEY_DOWNLOAD_GROUP = "download_group";
	private static final String KEY_CONCURRENT_CHUNKS = "concurrent_chunks";
	private static final String KEY_DOWNLOAD_BRANCH_MODE = "download_branch_mode";
	private static final String KEY_CUSTOM_DOWNLOAD_BRANCH = "custom_download_branch";
	private static final String KEY_DIRECT_ACCESS_ENABLED = "direct_access_enabled";
	private static final String KEY_SUPPLY_STATION_ENABLED = "supply_station_enabled";

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
		return Math.max(1, Math.min(8, preferences(context).getInt(KEY_CONCURRENT_CHUNKS, 2)));
	}

	public static void setConcurrentChunks(Context context, int value) {
		preferences(context).edit().putInt(KEY_CONCURRENT_CHUNKS, Math.max(1, Math.min(8, value))).apply();
	}

	public static String getDownloadBranchMode(Context context) {
		String value = preferences(context).getString(KEY_DOWNLOAD_BRANCH_MODE, BRANCH_MODE_AUTO);
		if (BRANCH_MODE_PUBLIC.equals(value) || BRANCH_MODE_PUBLIC_BETA.equals(value) || BRANCH_MODE_CUSTOM.equals(value) || BRANCH_MODE_ASK.equals(value)) {
			return value;
		}
		return BRANCH_MODE_AUTO;
	}

	public static void setDownloadBranchMode(Context context, String mode) {
		String value = mode == null ? BRANCH_MODE_AUTO : mode.trim();
		if (!BRANCH_MODE_PUBLIC.equals(value) && !BRANCH_MODE_PUBLIC_BETA.equals(value) && !BRANCH_MODE_CUSTOM.equals(value) && !BRANCH_MODE_ASK.equals(value)) {
			value = BRANCH_MODE_AUTO;
		}
		preferences(context).edit().putString(KEY_DOWNLOAD_BRANCH_MODE, value).apply();
	}

	public static String getCustomDownloadBranch(Context context) {
		return sanitizeBranch(preferences(context).getString(KEY_CUSTOM_DOWNLOAD_BRANCH, ""));
	}

	public static void setCustomDownloadBranch(Context context, String branch) {
		preferences(context).edit().putString(KEY_CUSTOM_DOWNLOAD_BRANCH, sanitizeBranch(branch)).apply();
	}

	public static boolean isDirectAccessEnabled(Context context) {
		return preferences(context).getBoolean(KEY_DIRECT_ACCESS_ENABLED, true);
	}

	public static void setDirectAccessEnabled(Context context, boolean enabled) {
		preferences(context).edit().putBoolean(KEY_DIRECT_ACCESS_ENABLED, enabled).apply();
	}

	public static boolean isSupplyStationEnabled(Context context) {
		return preferences(context).getBoolean(KEY_SUPPLY_STATION_ENABLED, false);
	}

	public static void setSupplyStationEnabled(Context context, boolean enabled) {
		preferences(context).edit().putBoolean(KEY_SUPPLY_STATION_ENABLED, enabled).apply();
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

	private static String sanitizeBranch(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.replace('\\', '_').replace('/', '_');
	}
}
