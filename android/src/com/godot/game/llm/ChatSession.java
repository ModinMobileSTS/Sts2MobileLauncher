package com.godot.game.llm;

import java.io.IOException;
import java.util.HashSet;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** A single-worker, in-memory conversation with complete standard tool-call association. */
public final class ChatSession {
	public interface Transport {
		JSONObject complete(JSONArray messages, JSONArray tools, Cancellation cancellation, Consumer<String> onText) throws Exception;
	}
	public interface Tools {
		JSONArray definitions() throws Exception;
		JSONObject execute(String name, JSONObject arguments, Cancellation cancellation) throws Exception;
		String summary(String name, JSONObject result);
	}
	public interface Observer {
		void toolStatus(String status);
		/** Starts a fresh draft, including when a tool result triggers another completion. */
		default void onRoundStart() {}
		/** Provisional text for this round; only ask's successful return is the final answer. */
		default void onText(String delta) {}
	}
	private final Transport transport;
	private final Tools tools;
	private final JSONArray messages = new JSONArray();

	public ChatSession(Transport transport, Tools tools, String systemPrompt) throws Exception {
		this.transport = transport;
		this.tools = tools;
		messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
	}

	public String ask(String question, Cancellation cancellation, Observer observer) throws Exception {
		int checkpoint = messages.length();
		boolean committed = false;
		try {
			cancellation.check();
			messages.put(new JSONObject().put("role", "user").put("content", question));
			JSONArray definitions = tools.definitions();
			Consumer<String> onText = observer::onText;
			while (true) {
				cancellation.check();
				observer.onRoundStart();
				JSONObject response = transport.complete(messages, definitions, cancellation, onText);
				cancellation.check();
				if (!"assistant".equals(response.optString("role"))) {
					throw new IOException("服务消息角色不是 assistant，无法继续工具会话");
				}
				JSONArray calls = response.optJSONArray("tool_calls");
				if (calls == null && !response.isNull("tool_calls")) throw new IOException("服务返回的 tool_calls 不是数组");
				JSONObject assistant = new JSONObject().put("role", "assistant");
				Object content = response.opt("content");
				if (content != null && content != JSONObject.NULL && !(content instanceof String)) {
					throw new IOException("服务返回不支持的 assistant 内容类型");
				}
				assistant.put("content", content == null ? JSONObject.NULL : content);
				assistant.putOpt("reasoning_content", response.opt("reasoning_content"));
				if (calls == null || calls.length() == 0) {
					if (!(content instanceof String) || ((String) content).trim().isEmpty()) {
						throw new IOException("模型未返回分析或工具请求，请确认模型支持 Chat Completions tools");
					}
					messages.put(assistant);
					cancellation.check();
					committed = true;
					return (String) content;
				}
				HashSet<String> ids = new HashSet<>();
				for (int i = 0; i < calls.length(); i++) {
					JSONObject call = calls.optJSONObject(i);
					Object id = call == null ? null : call.opt("id");
					if (!(id instanceof String) || ((String) id).trim().isEmpty() || !ids.add((String) id)
						|| !"function".equals(call.opt("type"))) {
						throw new IOException("服务返回无法关联的 tool_call_id 或不支持的工具类型");
					}
				}
				assistant.put("tool_calls", calls);
				messages.put(assistant);
				for (int i = 0; i < calls.length(); i++) {
					cancellation.check();
					JSONObject call = calls.getJSONObject(i);
					JSONObject result;
					String name = "";
					try {
						JSONObject function = call.getJSONObject("function");
						name = function.getString("name");
						Object arguments = function.get("arguments");
						if (!(arguments instanceof String)) throw new IllegalArgumentException();
						result = tools.execute(name, new JSONObject((String) arguments), cancellation);
					} catch (Exception e) {
						cancellation.check();
						result = new JSONObject().put("error", "工具调用失败；请检查工具名称、参数和日志文件状态");
					}
					cancellation.check();
					messages.put(new JSONObject().put("role", "tool").put("tool_call_id", call.getString("id"))
						.put("content", result.toString()));
					observer.toolStatus(tools.summary(name, result));
				}
			}
		} finally {
			// Keep earlier successful questions, never a partial user/tool turn.
			if (!committed) while (messages.length() > checkpoint) messages.remove(messages.length() - 1);
		}
	}
}
