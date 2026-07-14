package com.godot.game.steam.core;

import android.content.Context;
import android.content.SharedPreferences;

public final class SteamSettings {
	public static final String CLOUD_MODE_OFF = "off";
	public static final String CLOUD_MODE_MANUAL = "manual";
	public static final String CLOUD_MODE_PULL_ON_LAUNCH = "pull_on_launch";
	public static final String CLOUD_MODE_FULL_AUTO = "full_auto";

	private static final String PREFS_NAME = "sts2_steam_settings";
	private static final String KEY_CLOUD_MODE = "cloud_mode";
	private static final String KEY_SYNC_SETTINGS_SAVE = "sync_settings_save";
	private static final String KEY_ACCOUNT_SAFETY_NOTICE_SEEN = "account_safety_notice_seen";
	private static final String KEY_PAYLOAD_CONCURRENT_CHUNKS = "payload_concurrent_chunks";

	private SteamSettings() {
	}

	public static String getCloudMode(Context context) {
		String mode = prefs(context).getString(KEY_CLOUD_MODE, CLOUD_MODE_MANUAL);
		if (CLOUD_MODE_OFF.equals(mode) || CLOUD_MODE_MANUAL.equals(mode) || CLOUD_MODE_PULL_ON_LAUNCH.equals(mode) || CLOUD_MODE_FULL_AUTO.equals(mode)) {
			return mode;
		}
		return CLOUD_MODE_MANUAL;
	}

	public static void setCloudMode(Context context, String mode) {
		if (!CLOUD_MODE_OFF.equals(mode) && !CLOUD_MODE_MANUAL.equals(mode) && !CLOUD_MODE_PULL_ON_LAUNCH.equals(mode) && !CLOUD_MODE_FULL_AUTO.equals(mode)) {
			mode = CLOUD_MODE_MANUAL;
		}
		prefs(context).edit().putString(KEY_CLOUD_MODE, mode).apply();
	}

	public static boolean shouldSyncSettingsSave(Context context) {
		return prefs(context).getBoolean(KEY_SYNC_SETTINGS_SAVE, false);
	}

	public static void setSyncSettingsSave(Context context, boolean enabled) {
		prefs(context).edit().putBoolean(KEY_SYNC_SETTINGS_SAVE, enabled).apply();
	}

	public static boolean hasSeenAccountSafetyNotice(Context context) {
		return prefs(context).getBoolean(KEY_ACCOUNT_SAFETY_NOTICE_SEEN, false);
	}

	public static void markAccountSafetyNoticeSeen(Context context) {
		prefs(context).edit().putBoolean(KEY_ACCOUNT_SAFETY_NOTICE_SEEN, true).apply();
	}

	public static int getPayloadConcurrentChunks(Context context) {
		return normalizePayloadConcurrentChunks(prefs(context).getInt(KEY_PAYLOAD_CONCURRENT_CHUNKS, 2));
	}

	public static void setPayloadConcurrentChunks(Context context, int value) {
		prefs(context).edit().putInt(KEY_PAYLOAD_CONCURRENT_CHUNKS, normalizePayloadConcurrentChunks(value)).apply();
	}

	private static int normalizePayloadConcurrentChunks(int value) {
		return value == 1 || value == 4 ? value : 2;
	}

	public static boolean shouldPullBeforeLaunch(Context context) {
		String mode = getCloudMode(context);
		return CLOUD_MODE_PULL_ON_LAUNCH.equals(mode) || CLOUD_MODE_FULL_AUTO.equals(mode);
	}

	public static boolean shouldPushAfterCleanExit(Context context) {
		return CLOUD_MODE_FULL_AUTO.equals(getCloudMode(context));
	}

	private static SharedPreferences prefs(Context context) {
		return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}
}
