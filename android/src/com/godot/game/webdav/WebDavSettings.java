package com.godot.game.webdav;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

public final class WebDavSettings {
	private static final String TAG = "Sts2WebDavSettings";
	private static final String PREFS_NAME = "sts2_webdav_settings";
	private static final String KEY_BASE_URL = "base_url";
	private static final String KEY_USERNAME = "username";
	private static final String KEY_PASSWORD = "password";
	private static final String KEY_REMOTE_SLOT = "remote_slot";
	private static final String KEY_CLOUD_MODE = "cloud_mode";
	private static final String KEY_SYNC_SETTINGS_SAVE = "sync_settings_save";
	private static final String KEY_LAST_SUCCESS_AT_MS = "last_successful_connect_at_ms";
	private static final String KEY_LAST_MANIFEST_AT_MS = "last_manifest_at_ms";
	private static final String KEY_LAST_PULL_AT_MS = "last_pull_at_ms";
	private static final String KEY_LAST_PUSH_AT_MS = "last_push_at_ms";
	private static final String KEY_LAST_ERROR = "last_error";

	public static final String CLOUD_MODE_OFF = "off";
	public static final String CLOUD_MODE_MANUAL = "manual";
	public static final String CLOUD_MODE_PULL_ON_LAUNCH = "pull_on_launch";
	public static final String CLOUD_MODE_FULL_AUTO = "full_auto";

	private WebDavSettings() {
	}

	public static Config readConfig(Context context) {
		Config config = readSafely(context, prefs -> new Config(
			trim(prefs.getString(KEY_BASE_URL, "")),
			trim(prefs.getString(KEY_USERNAME, "")),
			prefs.getString(KEY_PASSWORD, "") == null ? "" : prefs.getString(KEY_PASSWORD, ""),
			trim(prefs.getString(KEY_REMOTE_SLOT, "")),
			normalizeCloudMode(prefs.getString(KEY_CLOUD_MODE, CLOUD_MODE_MANUAL)),
			prefs.getBoolean(KEY_SYNC_SETTINGS_SAVE, false),
			optionalLong(prefs, KEY_LAST_SUCCESS_AT_MS),
			optionalLong(prefs, KEY_LAST_MANIFEST_AT_MS),
			optionalLong(prefs, KEY_LAST_PULL_AT_MS),
			optionalLong(prefs, KEY_LAST_PUSH_AT_MS),
			trim(prefs.getString(KEY_LAST_ERROR, ""))
		));
		return config == null ? Config.empty() : config;
	}

	public static void saveConnection(Context context, String baseUrl, String username, String password, String remoteSlot) {
		writeSafely(context, prefs -> prefs.edit()
			.putString(KEY_BASE_URL, trim(baseUrl))
			.putString(KEY_USERNAME, trim(username))
			.putString(KEY_PASSWORD, password == null ? "" : password)
			.putString(KEY_REMOTE_SLOT, trim(remoteSlot))
			.remove(KEY_LAST_ERROR)
			.apply());
	}

	public static void setCloudMode(Context context, String mode) {
		writeSafely(context, prefs -> prefs.edit().putString(KEY_CLOUD_MODE, normalizeCloudMode(mode)).apply());
	}

	public static void setSyncSettingsSave(Context context, boolean enabled) {
		writeSafely(context, prefs -> prefs.edit().putBoolean(KEY_SYNC_SETTINGS_SAVE, enabled).apply());
	}

	public static boolean shouldPullBeforeLaunch(Context context) {
		String mode = readConfig(context).cloudMode;
		return CLOUD_MODE_PULL_ON_LAUNCH.equals(mode) || CLOUD_MODE_FULL_AUTO.equals(mode);
	}

	public static boolean shouldPushAfterCleanExit(Context context) {
		return CLOUD_MODE_FULL_AUTO.equals(readConfig(context).cloudMode);
	}

	public static boolean isConfigured(Context context) {
		return readConfig(context).isConfigured();
	}

	public static void recordSuccessfulConnect(Context context) {
		writeSafely(context, prefs -> prefs.edit().putLong(KEY_LAST_SUCCESS_AT_MS, System.currentTimeMillis()).remove(KEY_LAST_ERROR).apply());
	}

	public static void recordManifestSuccess(Context context) {
		writeSafely(context, prefs -> prefs.edit().putLong(KEY_LAST_MANIFEST_AT_MS, System.currentTimeMillis()).remove(KEY_LAST_ERROR).apply());
	}

	public static void recordPullSuccess(Context context) {
		writeSafely(context, prefs -> prefs.edit().putLong(KEY_LAST_PULL_AT_MS, System.currentTimeMillis()).remove(KEY_LAST_ERROR).apply());
	}

	public static void recordPushSuccess(Context context) {
		writeSafely(context, prefs -> prefs.edit().putLong(KEY_LAST_PUSH_AT_MS, System.currentTimeMillis()).remove(KEY_LAST_ERROR).apply());
	}

	public static void recordFailure(Context context, String error) {
		writeSafely(context, prefs -> prefs.edit().putString(KEY_LAST_ERROR, trim(error)).apply());
	}

	public static void clear(Context context) {
		Context app = context.getApplicationContext();
		try {
			prefs(app).edit().clear().commit();
		} catch (Exception exception) {
			Log.w(TAG, "Unable to clear encrypted WebDAV prefs.", exception);
		}
		app.deleteSharedPreferences(PREFS_NAME);
	}

	private static SharedPreferences prefs(Context context) throws Exception {
		Context app = context.getApplicationContext();
		MasterKey masterKey = new MasterKey.Builder(app)
			.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
			.build();
		return EncryptedSharedPreferences.create(
			app,
			PREFS_NAME,
			masterKey,
			EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
			EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
		);
	}

	private static <T> T readSafely(Context context, Reader<T> reader) {
		Context app = context.getApplicationContext();
		try {
			return reader.read(prefs(app));
		} catch (Exception exception) {
			Log.w(TAG, "Encrypted WebDAV settings unavailable; clearing stored settings.", exception);
			app.deleteSharedPreferences(PREFS_NAME);
			return null;
		}
	}

	private static void writeSafely(Context context, Writer writer) {
		Context app = context.getApplicationContext();
		try {
			writer.write(prefs(app));
		} catch (Exception exception) {
			Log.w(TAG, "Unable to write WebDAV settings; resetting and retrying.", exception);
			app.deleteSharedPreferences(PREFS_NAME);
			try {
				writer.write(prefs(app));
			} catch (Exception retryException) {
				Log.w(TAG, "Unable to write WebDAV settings after reset.", retryException);
			}
		}
	}

	private static long optionalLong(SharedPreferences prefs, String key) {
		return prefs.contains(key) ? Math.max(0L, prefs.getLong(key, 0L)) : 0L;
	}

	private static String normalizeCloudMode(String mode) {
		String value = trim(mode);
		if (CLOUD_MODE_OFF.equals(value) || CLOUD_MODE_MANUAL.equals(value) || CLOUD_MODE_PULL_ON_LAUNCH.equals(value) || CLOUD_MODE_FULL_AUTO.equals(value)) {
			return value;
		}
		return CLOUD_MODE_MANUAL;
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private interface Reader<T> { T read(SharedPreferences prefs) throws Exception; }
	private interface Writer { void write(SharedPreferences prefs) throws Exception; }

	public static final class Config {
		public final String baseUrl;
		public final String username;
		public final String password;
		public final String remoteSlot;
		public final String cloudMode;
		public final boolean syncSettingsSave;
		public final long lastSuccessfulConnectAtMs;
		public final long lastManifestAtMs;
		public final long lastPullAtMs;
		public final long lastPushAtMs;
		public final String lastError;

		Config(String baseUrl, String username, String password, String remoteSlot, String cloudMode, boolean syncSettingsSave, long lastSuccessfulConnectAtMs, long lastManifestAtMs, long lastPullAtMs, long lastPushAtMs, String lastError) {
			this.baseUrl = baseUrl == null ? "" : baseUrl;
			this.username = username == null ? "" : username;
			this.password = password == null ? "" : password;
			this.remoteSlot = remoteSlot == null ? "" : remoteSlot;
			this.cloudMode = normalizeCloudMode(cloudMode);
			this.syncSettingsSave = syncSettingsSave;
			this.lastSuccessfulConnectAtMs = lastSuccessfulConnectAtMs;
			this.lastManifestAtMs = lastManifestAtMs;
			this.lastPullAtMs = lastPullAtMs;
			this.lastPushAtMs = lastPushAtMs;
			this.lastError = lastError == null ? "" : lastError;
		}

		public boolean isConfigured() {
			return !baseUrl.isEmpty();
		}

		static Config empty() {
			return new Config("", "", "", "", CLOUD_MODE_MANUAL, false, 0L, 0L, 0L, 0L, "");
		}
	}
}
