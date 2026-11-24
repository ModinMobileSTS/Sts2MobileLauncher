package com.godot.game.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** No Steam routing, redirects, logging interceptors, or service-error-body echo. */
public final class OpenAiClient implements ChatSession.Transport {
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private final LlmConfig config;
	private final OkHttpClient http = new OkHttpClient.Builder()
		.followRedirects(false).followSslRedirects(false)
		.connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build();

	public OpenAiClient(LlmConfig config) {
		config.validate();
		this.config = config;
	}

	@Override public JSONObject complete(JSONArray messages, JSONArray tools, Cancellation cancellation, Consumer<String> onText) throws Exception {
		cancellation.check();
		JSONObject json = new JSONObject().put("model", config.model).put("messages", messages)
			.put("tools", tools).put("tool_choice", "auto").put("stream", true);
		if (!config.reasoningEffort.isEmpty()) json.put("reasoning_effort", config.reasoningEffort);
		Request.Builder builder = new Request.Builder().url(config.endpoint())
			.header("Accept", "text/event-stream, application/json")
			.post(RequestBody.create(json.toString(), JSON));
		if (!config.apiKey.isEmpty()) {
			try { builder.header("Authorization", "Bearer " + config.apiKey); }
			catch (IllegalArgumentException invalid) { throw new IOException("API Key 含有 HTTP 请求头不支持的字符"); }
		}
		Call call = http.newCall(builder.build());
		cancellation.attach(call);
		try (Response response = call.execute()) {
			cancellation.check();
			if (!response.isSuccessful()) {
				throw new ReplyException("服务返回 HTTP " + response.code() + "；请检查地址、密钥、模型和工具支持");
			}
			if (response.body() == null) throw new ReplyException("服务响应为空");
			BufferedReader reader = new BufferedReader(response.body().charStream());
			// Compatible endpoints don't always label the body correctly. A non-stream
			// completion is a JSON object; look ahead one character, not one whole body.
			int first;
			do {
				cancellation.check();
				reader.mark(1);
				first = reader.read();
			} while (Character.isWhitespace(first));
			if (first == -1) throw new ReplyException("服务响应为空");
			reader.reset();
			JSONObject message = first == '{' ? readJson(reader, cancellation, onText) : readStream(reader, cancellation, onText);
			cancellation.check();
			return message;
		} catch (ReplyException e) {
			cancellation.check();
			throw e;
		} catch (JSONException e) {
			cancellation.check();
			throw new IOException("服务未返回有效的 Chat Completions 消息；请确认协议和工具支持");
		} catch (IOException e) {
			cancellation.check();
			// Network and JSON exceptions can contain URLs, credentials or response text.
			throw new IOException("网络请求失败或响应中断，请检查自定义服务连接");
		} finally {
			cancellation.detach(call);
		}
	}

	/** Only our own, display-safe protocol errors may cross the transport boundary. */
	private static final class ReplyException extends IOException {
		ReplyException(String message) { super(message); }
	}

	private static JSONArray parseChoices(String json) throws JSONException, ReplyException {
		JSONTokener tokens = new JSONTokener(json);
		JSONObject envelope = new JSONObject(tokens);
		if (tokens.nextClean() != 0) throw new JSONException("Trailing data");
		if (!envelope.isNull("error")) throw providerError();
		return envelope.getJSONArray("choices");
	}

	private static ReplyException providerError() {
		return new ReplyException("服务报告 Chat Completions 错误，本次回答未完成；请检查模型及工具支持");
	}

	private static boolean finished(JSONObject choice, boolean hasCalls) throws ReplyException {
		Object reason = choice.opt("finish_reason");
		if (!choice.isNull("error") || "error".equals(reason)) throw providerError();
		if (reason == null || reason == JSONObject.NULL || "".equals(reason)) return false;
		if ("tool_calls".equals(reason) && !hasCalls) throw new ReplyException("服务以 tool_calls 结束，但未返回工具请求");
		if ("stop".equals(reason) || "tool_calls".equals(reason)) return true;
		if ("length".equals(reason) || "content_filter".equals(reason)) {
			throw new ReplyException("服务提前终止（" + reason + "），未获得完整分析；可调整配置后重试");
		}
		throw new ReplyException("服务返回不支持的 finish_reason，未获得完整分析");
	}

	private static JSONObject readJson(BufferedReader reader, Cancellation token, Consumer<String> onText) throws IOException, JSONException {
		StringBuilder body = new StringBuilder();
		char[] buffer = new char[8192];
		int count;
		while ((count = reader.read(buffer)) != -1) {
			token.check();
			body.append(buffer, 0, count);
		}
		JSONObject choice = parseChoices(body.toString()).getJSONObject(0);
		JSONObject message = choice.optJSONObject("message");
		JSONArray calls = message == null ? null : message.optJSONArray("tool_calls");
		finished(choice, calls != null && calls.length() != 0);
		if (message == null) throw new JSONException("Missing assistant message");
		Object text = message.opt("content");
		if ("assistant".equals(message.opt("role")) && text instanceof String && !((String) text).isEmpty()) {
			onText.accept((String) text); // One whole message, not simulated deltas.
		}
		return message;
	}

	private static JSONObject readStream(BufferedReader reader, Cancellation token, Consumer<String> onText) throws IOException, JSONException {
		StreamMessage message = new StreamMessage();
		StringBuilder data = new StringBuilder();
		boolean errorEvent = false;
		while (true) {
			token.check();
			String line = reader.readLine();
			if (line == null || line.isEmpty()) {
				if (errorEvent) throw providerError();
				if (data.length() != 0) {
					String event = data.toString();
					if ("[DONE]".equals(event.trim()) || message.appendEvent(event, onText)) return message.build();
					data.setLength(0);
				}
				if (line == null) throw new ReplyException("服务流式响应中断（缺少 finish_reason 或 [DONE]），本次回答未完成");
				errorEvent = false;
			} else if (line.startsWith("data:") || line.equals("data")) {
				if (data.length() != 0) data.append('\n');
				int start = line.startsWith("data: ") ? 6 : Math.min(5, line.length());
				data.append(line, start, line.length());
			} else if (line.startsWith("event:")) {
				errorEvent = "error".equals(line.substring(6).trim());
			} // Comments/keepalives, id, retry and unknown SSE fields carry no delta.
		}
	}

	private static final class StreamMessage {
		private final StringBuilder content = new StringBuilder();
		private StringBuilder reasoning;
		private final TreeMap<Long, ToolCall> calls = new TreeMap<>();

		boolean appendEvent(String event, Consumer<String> onText) throws JSONException, ReplyException {
			JSONArray choices = parseChoices(event);
			for (int i = 0; i < choices.length(); i++) {
				JSONObject choice = choices.getJSONObject(i);
				if (choice.optInt("index", i) != 0) continue; // Never mix different alternatives.
				JSONObject delta = choice.optJSONObject("delta");
				JSONArray fragments = delta == null ? null : delta.optJSONArray("tool_calls");
				boolean done = finished(choice, !calls.isEmpty() || (fragments != null && fragments.length() != 0));
				if (delta == null) {
					if (done && choice.isNull("delta")) return true;
					throw new JSONException("Missing delta");
				}
				if (!delta.isNull("role") && !"assistant".equals(delta.opt("role"))) {
					throw new ReplyException("服务消息角色不是 assistant，无法继续工具会话");
				}
				// Some reasoning providers require their own opaque context back on the next tool turn.
				Object reasoningPart = delta.opt("reasoning_content");
				if (reasoningPart instanceof String) {
					if (reasoning == null) reasoning = new StringBuilder();
					reasoning.append((String) reasoningPart);
				}
				String text = stringPart(delta, "content");
				if (text != null && !text.isEmpty()) {
					content.append(text);
					onText.accept(text);
				}
				if (fragments == null && !delta.isNull("tool_calls")) throw new ReplyException("服务返回的 tool_calls 不是数组");
				if (fragments != null) {
					for (int j = 0; j < fragments.length(); j++) {
						JSONObject fragment = fragments.getJSONObject(j);
						Object index = fragment.opt("index");
						if (!(index instanceof Integer || index instanceof Long) || ((Number) index).longValue() < 0) {
							throw new ReplyException("服务返回无效的 tool_calls index，无法关联工具请求");
						}
						calls.computeIfAbsent(((Number) index).longValue(), ignored -> new ToolCall()).append(fragment);
					}
				}
				return done;
			}
			return false; // e.g. a usage-only chunk with choices:[]
		}

		JSONObject build() throws JSONException {
			JSONObject message = new JSONObject().put("role", "assistant")
				.put("content", content.length() == 0 ? JSONObject.NULL : content.toString());
			if (reasoning != null) message.put("reasoning_content", reasoning.toString());
			if (!calls.isEmpty()) {
				JSONArray array = new JSONArray();
				for (ToolCall call : calls.values()) array.put(call.build());
				message.put("tool_calls", array);
			}
			return message;
		}
	}

	private static String stringPart(JSONObject delta, String key) throws JSONException {
		if (delta.isNull(key)) return null;
		Object value = delta.get(key);
		if (!(value instanceof String)) throw new JSONException("Expected a string delta");
		return (String) value;
	}

	private static final class ToolCall {
		private final StringBuilder id = new StringBuilder();
		private final StringBuilder name = new StringBuilder();
		private final StringBuilder arguments = new StringBuilder();

		void append(JSONObject fragment) throws JSONException, ReplyException {
			if (!fragment.isNull("type") && !"function".equals(fragment.opt("type"))) {
				throw new ReplyException("服务返回不支持的工具类型");
			}
			String idPart = stringPart(fragment, "id");
			if (idPart != null) id.append(idPart);
			if (!fragment.isNull("function")) {
				JSONObject function = fragment.getJSONObject("function");
				String namePart = stringPart(function, "name");
				String argsPart = stringPart(function, "arguments");
				if (namePart != null) name.append(namePart);
				if (argsPart != null) arguments.append(argsPart);
			}
		}

		JSONObject build() throws JSONException {
			return new JSONObject().put("id", id.toString()).put("type", "function").put("function",
				new JSONObject().put("name", name.toString()).put("arguments", arguments.toString()));
		}
	}
}
