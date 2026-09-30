package com.godot.game.steam.auth;

import android.content.Context;
import android.content.SharedPreferences;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Replaces only Android Keystore storage; the real store's expiry and compare-and-commit run. */
@Implements(value = SteamAuthStore.class, isInAndroidSdk = false)
public class SteamAuthPreferencesShadow {
	@Implementation
	protected static SharedPreferences prefs(Context context) {
		return context.getApplicationContext().getSharedPreferences("sts2_steam_auth", Context.MODE_PRIVATE);
	}
}
