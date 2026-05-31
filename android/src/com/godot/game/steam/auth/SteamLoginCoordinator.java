package com.godot.game.steam.auth;

import android.content.Context;

import com.godot.game.steam.cloud.Sts2SteamCloudClient;

public final class SteamLoginCoordinator {
	private SteamLoginCoordinator() {
	}

	public static AuthResult authenticateWithCredentials(Context context, String username, String password, Sts2SteamCloudClient.AuthPrompt prompt) throws Exception {
		String normalizedUsername = username == null ? "" : username.trim();
		String existingGuardData = "";
		SteamAuthStore.SavedAuthMaterial existing = SteamAuthStore.readAuthMaterial(context);
		if (existing != null) {
			existingGuardData = existing.guardData;
		}
		try (Sts2SteamCloudClient client = new Sts2SteamCloudClient(context)) {
			client.beginOperationDiagnostics("credentials_login", normalizedUsername, existingGuardData != null && !existingGuardData.trim().isEmpty());
			client.start();
			Sts2SteamCloudClient.AuthMaterial material = client.authenticateWithCredentials(normalizedUsername, password, existingGuardData, prompt);
			String steamId64 = "";
			try {
				client.logOnWithRefreshToken(material.getAccountName(), material.getRefreshToken());
				steamId64 = client.getCurrentSteamId64();
			} catch (Exception ignored) {
			}
			SteamAuthStore.recordAuthSuccess(context, material.getAccountName(), material.getRefreshToken(), material.getGuardData(), steamId64);
			return new AuthResult(material.getAccountName(), steamId64);
		} catch (Exception exception) {
			SteamAuthStore.recordFailure(context, exception.getMessage() == null ? exception.toString() : exception.getMessage());
			throw exception;
		}
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

	public static final class AuthResult {
		public final String accountName;
		public final String steamId64;

		AuthResult(String accountName, String steamId64) {
			this.accountName = accountName == null ? "" : accountName;
			this.steamId64 = steamId64 == null ? "" : steamId64;
		}
	}
}
