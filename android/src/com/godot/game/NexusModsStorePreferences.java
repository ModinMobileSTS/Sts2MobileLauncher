package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

public final class NexusModsStorePreferences {
	public static final String DEFAULT_GAME_DOMAIN = "slaythespire2";
	private static final String PREFERENCES_NAME = "sts2_nexus_mod_store";
	private static final String KEY_PERSONAL_API_KEY = "personal_api_key";
	private static final String KEY_GAME_DOMAIN = "game_domain";

	private NexusModsStorePreferences() {
	}

	public static String getPersonalApiKey(Context context) {
		return preferences(context).getString(KEY_PERSONAL_API_KEY, "");
	}

	public static boolean hasPersonalApiKey(Context context) {
		return !TextUtils.isEmpty(getPersonalApiKey(context));
	}

	public static void setPersonalApiKey(Context context, String apiKey) {
		preferences(context)
			.edit()
			.putString(KEY_PERSONAL_API_KEY, sanitizeApiKey(apiKey))
			.apply();
	}

	public static void clearPersonalApiKey(Context context) {
		preferences(context)
			.edit()
			.remove(KEY_PERSONAL_API_KEY)
			.apply();
	}

	public static String getGameDomain(Context context) {
		String domain = preferences(context).getString(KEY_GAME_DOMAIN, DEFAULT_GAME_DOMAIN);
		domain = sanitizeGameDomain(domain);
		return TextUtils.isEmpty(domain) ? DEFAULT_GAME_DOMAIN : domain;
	}

	public static void setGameDomain(Context context, String gameDomain) {
		String sanitized = sanitizeGameDomain(gameDomain);
		preferences(context)
			.edit()
			.putString(KEY_GAME_DOMAIN, TextUtils.isEmpty(sanitized) ? DEFAULT_GAME_DOMAIN : sanitized)
			.apply();
	}

	public static String maskApiKey(String apiKey) {
		String sanitized = sanitizeApiKey(apiKey);
		if (TextUtils.isEmpty(sanitized)) {
			return "";
		}
		if (sanitized.length() <= 8) {
			return "••••";
		}
		return sanitized.substring(0, 4) + "••••" + sanitized.substring(sanitized.length() - 4);
	}

	private static SharedPreferences preferences(Context context) {
		return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
	}

	private static String sanitizeApiKey(String apiKey) {
		return apiKey == null ? "" : apiKey.trim();
	}

	private static String sanitizeGameDomain(String gameDomain) {
		if (gameDomain == null) {
			return "";
		}
		String sanitized = gameDomain.trim().toLowerCase(java.util.Locale.ROOT);
		return sanitized.replaceAll("[^a-z0-9_-]", "");
	}
}
