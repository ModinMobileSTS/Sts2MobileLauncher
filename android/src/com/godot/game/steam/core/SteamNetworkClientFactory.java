package com.godot.game.steam.core;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public final class SteamNetworkClientFactory {
	private SteamNetworkClientFactory() {
	}

	public static OkHttpClient createDefaultClient() {
		return new OkHttpClient.Builder()
			.connectTimeout(40, TimeUnit.SECONDS)
			.readTimeout(90, TimeUnit.SECONDS)
			.writeTimeout(90, TimeUnit.SECONDS)
			.callTimeout(180, TimeUnit.SECONDS)
			.retryOnConnectionFailure(true)
			.build();
	}
}
