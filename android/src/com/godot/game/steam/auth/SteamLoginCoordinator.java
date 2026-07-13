package com.godot.game.steam.auth;

import android.content.Context;

import com.godot.game.steam.cloud.Sts2SteamCloudClient;

public final class SteamLoginCoordinator {
	private SteamLoginCoordinator() {
	}

	public static String verifyRefreshToken(Context context) throws Exception {
		SteamAuthStore.SavedAuthMaterial material = SteamAuthStore.readAuthMaterial(context);
		if (material == null) {
			throw new IllegalStateException("Steam account is not logged in.");
		}
		try (Sts2SteamCloudClient client = new Sts2SteamCloudClient(context)) {
			client.beginOperationDiagnostics("refresh_token_check", material.accountName, material.guardData != null && !material.guardData.trim().isEmpty());
			client.start();
			client.logOnWithRefreshToken(material.accountName, material.refreshToken);
			String steamId64 = client.getCurrentSteamId64();
			SteamAuthStore.recordSuccessfulConnect(context, steamId64);
			return steamId64;
		} catch (Exception exception) {
			SteamAuthStore.recordFailure(context, exception.getMessage() == null ? exception.toString() : exception.getMessage());
			throw exception;
		}
	}
}
