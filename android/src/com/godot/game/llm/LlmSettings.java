package com.godot.game.llm;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.IOException;

/** No plaintext fallback, destructive recovery, or secret-bearing diagnostics. */
public final class LlmSettings {
	private LlmSettings() {}

	private static SharedPreferences prefs(Context context) throws Exception {
		Context app = context.getApplicationContext();
		MasterKey key = new MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
		return EncryptedSharedPreferences.create(app, "sts2_llm_settings", key,
			EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
			EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
	}

	public static LlmConfig read(Context context) throws IOException {
		try {
			SharedPreferences p = prefs(context);
			return new LlmConfig(p.getString("base_url", ""), p.getString("api_key", ""),
				p.getString("model", ""), p.getString("effort", ""));
		} catch (Exception e) {
			throw new IOException("无法读取加密配置，请检查设备密钥存储；未清除原配置");
		}
	}

	public static void save(Context context, LlmConfig config) throws IOException {
		config.validate();
		try {
			if (!prefs(context).edit().putString("base_url", config.baseUrl)
				.putString("api_key", config.apiKey).putString("model", config.model)
				.putString("effort", config.reasoningEffort).commit()) {
				throw new IOException();
			}
		} catch (Exception e) {
			throw new IOException("加密保存失败，配置未生效；不会退回明文保存");
		}
	}
}
