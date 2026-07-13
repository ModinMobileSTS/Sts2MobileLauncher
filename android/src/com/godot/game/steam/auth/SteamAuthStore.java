package com.godot.game.steam.auth;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import top.apricityx.workshop.steam.protocol.SteamAuthTransactionHandle;

@SuppressLint("ApplySharedPref") // Auth transaction generation changes require synchronous commits.
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
	private static final String KEY_PENDING_AUTH_TRANSACTION = "pending_auth_transaction";
	private static final Object PENDING_AUTH_LOCK = new Object();

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

	/**
	 * Atomically commits tokens only if the pending generation still matches. A late poll from a
	 * cancelled/replaced login therefore cannot overwrite the active account.
	 */
	public static boolean recordAuthSuccessIfPendingMatches(Context context, String transactionId, String accountName, String refreshToken, String guardData, String steamId64) {
		synchronized (PENDING_AUTH_LOCK) {
			Boolean committed = readSafely(context, prefs -> {
				SteamAuthTransactionHandle current = parsePendingTransaction(prefs, true, false);
				if (current == null || !current.getTransactionId().equals(trim(transactionId))) {
					return false;
				}
				return putAuthSuccess(
					prefs.edit(),
					accountName,
					refreshToken,
					guardData,
					steamId64
				).remove(KEY_PENDING_AUTH_TRANSACTION).commit();
			});
			return Boolean.TRUE.equals(committed);
		}
	}

	/**
	 * Reads the encrypted pending handle. An expired handle is removed atomically but returned once
	 * so callers can render an explicit EXPIRED state instead of treating it as missing credentials.
	 */
	public static SteamAuthTransactionHandle readPendingAuthTransaction(Context context) {
		synchronized (PENDING_AUTH_LOCK) {
			return readSafely(context, prefs -> parsePendingTransaction(prefs, true, true));
		}
	}

	/** Replaces any previous pending generation. Used only after BeginAuth returns a new handle. */
	public static void savePendingAuthTransaction(Context context, SteamAuthTransactionHandle handle) {
		if (handle == null) {
			throw new IllegalArgumentException("Steam auth transaction handle is required.");
		}
		if (handle.isExpired()) {
			throw new IllegalArgumentException("Cannot persist an expired Steam auth transaction.");
		}
		synchronized (PENDING_AUTH_LOCK) {
			writeSafely(context, prefs -> prefs.edit()
				.putString(KEY_PENDING_AUTH_TRANSACTION, handle.toJson())
				.commit());
		}
	}

	/** Updates phase/client routing only when the stored generation still matches. */
	public static boolean updatePendingAuthTransaction(Context context, SteamAuthTransactionHandle handle) {
		if (handle == null || handle.isExpired()) {
			return false;
		}
		synchronized (PENDING_AUTH_LOCK) {
			Boolean updated = readSafely(context, prefs -> {
				SteamAuthTransactionHandle current = parsePendingTransaction(prefs, true, false);
				if (current == null || !current.getTransactionId().equals(handle.getTransactionId())) {
					return false;
				}
				return prefs.edit()
					.putString(KEY_PENDING_AUTH_TRANSACTION, handle.toJson())
					.commit();
			});
			return Boolean.TRUE.equals(updated);
		}
	}

	/** Clears whichever pending transaction is current without touching saved refresh-token data. */
	public static void clearPendingAuthTransaction(Context context) {
		synchronized (PENDING_AUTH_LOCK) {
			writeSafely(context, prefs -> prefs.edit().remove(KEY_PENDING_AUTH_TRANSACTION).commit());
		}
	}

	/** Clears only the specified generation, leaving a newer login transaction intact. */
	public static boolean clearPendingAuthTransaction(Context context, String transactionId) {
		synchronized (PENDING_AUTH_LOCK) {
			Boolean cleared = readSafely(context, prefs -> {
				SteamAuthTransactionHandle current = parsePendingTransaction(prefs, true, false);
				if (current == null || !current.getTransactionId().equals(trim(transactionId))) {
					return false;
				}
				return prefs.edit().remove(KEY_PENDING_AUTH_TRANSACTION).commit();
			});
			return Boolean.TRUE.equals(cleared);
		}
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
		synchronized (PENDING_AUTH_LOCK) {
			try {
				prefs(app).edit().clear().commit();
			} catch (Exception exception) {
				Log.w(TAG, "Unable to clear encrypted Steam auth prefs.", exception);
			}
			app.deleteSharedPreferences(PREFS_NAME);
		}
	}

	private static SharedPreferences.Editor putAuthSuccess(SharedPreferences.Editor editor, String accountName, String refreshToken, String guardData, String steamId64) {
		long now = System.currentTimeMillis();
		return editor
			.putString(KEY_ACCOUNT_NAME, trim(accountName))
			.putString(KEY_REFRESH_TOKEN, trim(refreshToken))
			.putString(KEY_GUARD_DATA, trim(guardData))
			.putString(KEY_STEAM_ID_64, trim(steamId64))
			.putLong(KEY_LAST_AUTH_AT_MS, now)
			.putLong(KEY_LAST_SUCCESS_AT_MS, now)
			.remove(KEY_LAST_ERROR);
	}

	private static SteamAuthTransactionHandle parsePendingTransaction(SharedPreferences prefs, boolean clearInvalidOrExpired, boolean returnExpiredOnce) {
		String encoded = trim(prefs.getString(KEY_PENDING_AUTH_TRANSACTION, ""));
		if (encoded.isEmpty()) {
			return null;
		}
		try {
			SteamAuthTransactionHandle handle = SteamAuthTransactionHandle.fromJson(encoded);
			if (handle.isExpired()) {
				if (clearInvalidOrExpired) {
					prefs.edit().remove(KEY_PENDING_AUTH_TRANSACTION).commit();
				}
				return returnExpiredOnce ? handle : null;
			}
			return handle;
		} catch (Exception exception) {
			Log.w(TAG, "Discarding invalid pending Steam auth transaction.", exception);
			if (clearInvalidOrExpired) {
				prefs.edit().remove(KEY_PENDING_AUTH_TRANSACTION).commit();
			}
			return null;
		}
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
