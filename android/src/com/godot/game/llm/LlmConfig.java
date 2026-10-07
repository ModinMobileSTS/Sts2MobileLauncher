package com.godot.game.llm;

import okhttp3.HttpUrl;

/** Independent connection settings; never include this object in logs or intents. */
public final class LlmConfig {
	public final String baseUrl;
	public final String apiKey;
	public final String model;
	public final String reasoningEffort;

	public LlmConfig(String baseUrl, String apiKey, String model, String reasoningEffort) {
		this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.model = model == null ? "" : model.trim();
		this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
	}

	public HttpUrl endpoint() {
		HttpUrl url = HttpUrl.parse(baseUrl);
		if (url == null || !url.username().isEmpty() || !url.password().isEmpty()
			|| url.query() != null || url.fragment() != null) {
			throw new IllegalArgumentException("Base URL 必须是无账号、查询参数或片段的 HTTP(S) 地址");
		}
		String path = url.encodedPath();
		while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
		if (!path.endsWith("/chat/completions")) {
			if (path.isEmpty()) path = "/v1";
			path += "/chat/completions";
		}
		return url.newBuilder().encodedPath(path).build();
	}

	public void validate() {
		endpoint();
		if (model.isEmpty()) throw new IllegalArgumentException("请填写 Model ID");
		if (apiKey.indexOf('\n') >= 0 || apiKey.indexOf('\r') >= 0) {
			throw new IllegalArgumentException("API Key 不能含换行");
		}
	}
}
