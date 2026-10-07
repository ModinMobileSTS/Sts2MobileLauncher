package com.godot.game.llm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** No Steam routing, redirects, logging interceptors, or service-error-body echo. */
public final class OpenAiClient implements ChatSession.Transport {
	private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
	private final LlmConfig config;
	private final OkHttpClient http = new OkHttpClient.Builder()
		.followRedirects(false).followSslRedirects(false)
		.connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
		.callTimeout(150, TimeUnit.SECONDS).build();

	public OpenAiClient(LlmConfig config) {
		config.validate();
		this.config = config;
	}

	@Override public JSONObject complete(JSONArray messages, JSONArray tools, Cancellation cancellation) throws Exception {
		cancellation.check();
		JSONObject json = new JSONObject().put("model", config.model).put("messages", messages)
			.put("tools", tools).put("tool_choice", "auto");
		if (!config.reasoningEffort.isEmpty()) json.put("reasoning_effort", config.reasoningEffort);
		Request.Builder builder = new Request.Builder().url(config.endpoint())
			.post(RequestBody.create(json.toString(), MediaType.get("application/json; charset=utf-8")));
		if (!config.apiKey.isEmpty()) builder.header("Authorization", "Bearer " + config.apiKey);
		Call call = http.newCall(builder.build());
		cancellation.attach(call);
		try (Response response = call.execute()) {
			cancellation.check();
			if (!response.isSuccessful()) {
				throw new IOException("服务返回 HTTP " + response.code()
					+ "；请检查地址、密钥、模型和工具支持（未上传全文，也不会自动降级）");
			}
			if (response.body() == null) throw new IOException("服务响应为空");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (InputStream input = response.body().byteStream()) {
				byte[] buffer = new byte[8192];
				int count;
				while ((count = input.read(buffer)) != -1) {
					cancellation.check();
					if (output.size() + count > MAX_RESPONSE_BYTES) throw new IOException("服务响应超出 1 MiB 上限");
					output.write(buffer, 0, count);
				}
			}
			try {
				JSONObject choice = new JSONObject(output.toString(StandardCharsets.UTF_8.name()))
					.getJSONArray("choices").getJSONObject(0);
				String finish = choice.optString("finish_reason", "");
				if ("length".equals(finish) || "content_filter".equals(finish)) {
					throw new IOException("服务提前终止（" + finish + "），未获得完整分析；可调整配置后重试");
				}
				return choice.getJSONObject("message");
			} catch (JSONException e) {
				throw new IOException("服务未返回有效的 Chat Completions 消息；请确认协议和工具支持");
			}
		} catch (IOException e) {
			cancellation.check();
			// Only locally generated messages are safe to show; network errors may embed a URL or headers.
			String message = e.getMessage();
			if (message != null && (message.startsWith("服务") || message.startsWith("已停止"))) throw e;
			throw new IOException("网络请求失败或超时，请检查自定义服务连接");
		} finally {
			cancellation.detach(call);
		}
	}
}
