package com.godot.game.steam.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

public final class SteamAuthStore {
	private static final String TAG = "Sts2SteamAuth";
	private static final String PREFS_NAME = "sts2_steam_auth";
	private static final String KEY_ACCOUNT_NAME = "account_name";
	private static final String KEY_STEAM_ID_64 = "steam_id_64";
	private static final String KEY_REFRESH_TOKEN = "refresh_token";
	private static final String KEY_GUARD_DATA = "guard_data";
	private static final String KEY_LAST_AUTH_AT_MS = "last_auth_at_ms";
	private static final String KEY_LAST_SUCCESS_AT_MS = "last_successful_connect_at_ms";
	private static final String KEY_LAST_MANIFEST_AT_MS = "last_manifest_at_ms";
	private static final String KEY_LAST_PULL_AT_MS = "last_pull_at_ms";
	private static final String KEY_LAST_PUSH_AT_MS = "last_push_at_ms";
	private static final String KEY_LAST_ERROR = "last_error";

	private SteamAuthStore() {
	}

	public static SavedAuthMaterial readAuthMaterial(Context context) {
		return readSafely(context, prefs -> {
			String account = trim(prefs.getString(KEY_ACCOUNT_NAME, ""));
			String token = trim(prefs.getString(KEY_REFRESH_TOKEN, ""));
			if (account.isEmpty() || token.isEmpty()) {
				return null;
			}
			return new SavedAuthMaterial(account, token, trim(prefs.getString(KEY_GUARD_DATA, "")));
		});
	}

	public static AuthSnapshot readSnapshot(Context context) {
		AuthSnapshot snapshot = readSafely(context, prefs -> new AuthSnapshot(
			trim(prefs.getString(KEY_ACCOUNT_NAME, "")),
			!trim(prefs.getString(KEY_REFRESH_TOKEN, "")).isEmpty(),
			!trim(prefs.getString(KEY_GUARD_DATA, "")).isEmpty(),
			trim(prefs.getString(KEY_STEAM_ID_64, "")),
			optionalLong(prefs, KEY_LAST_AUTH_AT_MS),
			optionalLong(prefs, KEY_LAST_SUCCESS_AT_MS),
			optionalLong(prefs, KEY_LAST_MANIFEST_AT_MS),
			optionalLong(prefs, KEY_LAST_PULL_AT_MS),
			optionalLong(prefs, KEY_LAST_PUSH_AT_MS),
			trim(prefs.getString(KEY_LAST_ERROR, ""))
		));
		return snapshot == null ? AuthSnapshot.empty() : snapshot;
	}

	public static void recordAuthSuccess(Context context, String accountName, String refreshToken, String guardData, String steamId64) {
		writeSafely(context, prefs -> prefs.edit()
			.putString(KEY_ACCOUNT_NAME, trim(accountName))
			.putString(KEY_REFRESH_TOKEN, trim(refreshToken))
			.putString(KEY_GUARD_DATA, trim(guardData))
			.putString(KEY_STEAM_ID_64, trim(steamId64))
			.putLong(KEY_LAST_AUTH_AT_MS, System.currentTimeMillis())
			.putLong(KEY_LAST_SUCCESS_AT_MS, System.currentTimeMillis())
			.remove(KEY_LAST_ERROR)
			.apply());
	}

	public static void recordSuccessfulConnect(Context context, String steamId64) {
		writeSafely(context, prefs -> prefs.edit()
			.putString(KEY_STEAM_ID_64, trim(steamId64))
			.putLong(KEY_LAST_SUCCESS_AT_MS, System.currentTimeMillis())
			.remove(KEY_LAST_ERROR)
			.apply());
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
			Log.w(TAG, "Unable to clear encrypted Steam auth prefs.", exception);
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
			Log.w(TAG, "Encrypted Steam auth storage unavailable; clearing stored auth.", exception);
			app.deleteSharedPreferences(PREFS_NAME);
			return null;
		}
	}

	private static void writeSafely(Context context, Writer writer) {
		Context app = context.getApplicationContext();
		try {
			writer.write(prefs(app));
		} catch (Exception exception) {
			Log.w(TAG, "Unable to write Steam auth storage; resetting and retrying.", exception);
			app.deleteSharedPreferences(PREFS_NAME);
			try {
				writer.write(prefs(app));
			} catch (Exception retryException) {
				Log.w(TAG, "Unable to write Steam auth storage after reset.", retryException);
			}
		}
	}

	private static long optionalLong(SharedPreferences prefs, String key) {
		return prefs.contains(key) ? Math.max(0L, prefs.getLong(key, 0L)) : 0L;
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private interface Reader<T> { T read(SharedPreferences prefs) throws Exception; }
	private interface Writer { void write(SharedPreferences prefs) throws Exception; }

	public static final class SavedAuthMaterial {
		public final String accountName;
		public final String refreshToken;
		public final String guardData;

		SavedAuthMaterial(String accountName, String refreshToken, String guardData) {
			this.accountName = accountName == null ? "" : accountName;
			this.refreshToken = refreshToken == null ? "" : refreshToken;
			this.guardData = guardData == null ? "" : guardData;
		}
	}

	public static final class AuthSnapshot {
		public final String accountName;
		public final boolean refreshTokenConfigured;
		public final boolean guardDataConfigured;
		public final String steamId64;
		public final long lastAuthAtMs;
		public final long lastSuccessfulConnectAtMs;
		public final long lastManifestAtMs;
		public final long lastPullAtMs;
		public final long lastPushAtMs;
		public final String lastError;

		AuthSnapshot(String accountName, boolean refreshTokenConfigured, boolean guardDataConfigured, String steamId64, long lastAuthAtMs, long lastSuccessfulConnectAtMs, long lastManifestAtMs, long lastPullAtMs, long lastPushAtMs, String lastError) {
			this.accountName = accountName == null ? "" : accountName;
			this.refreshTokenConfigured = refreshTokenConfigured;
			this.guardDataConfigured = guardDataConfigured;
			this.steamId64 = steamId64 == null ? "" : steamId64;
			this.lastAuthAtMs = lastAuthAtMs;
			this.lastSuccessfulConnectAtMs = lastSuccessfulConnectAtMs;
			this.lastManifestAtMs = lastManifestAtMs;
			this.lastPullAtMs = lastPullAtMs;
			this.lastPushAtMs = lastPushAtMs;
			this.lastError = lastError == null ? "" : lastError;
		}

		static AuthSnapshot empty() {
			return new AuthSnapshot("", false, false, "", 0L, 0L, 0L, 0L, 0L, "");
		}
	}
}
