package com.godot.game.llm;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** A single-worker, in-memory conversation with complete standard tool-call association. */
public final class ChatSession {
	public static final int MAX_ROUNDS = 24;
	public static final int MAX_CONTEXT_CHARS = 256 * 1024;
	public interface Transport {
		JSONObject complete(JSONArray messages, JSONArray tools, Cancellation cancellation) throws Exception;
	}
	public interface Tools {
		JSONArray definitions() throws Exception;
		JSONObject execute(String name, JSONObject arguments, Cancellation cancellation) throws Exception;
		String summary(String name, JSONObject result);
	}
	public interface Observer { void toolStatus(String status); }
	public static final class Result {
		public final String text;
		public final boolean budgetReached;
		Result(String text, boolean budgetReached) { this.text = text; this.budgetReached = budgetReached; }
	}
	private final Transport transport;
	private final Tools tools;
	private final JSONArray messages = new JSONArray();

	public ChatSession(Transport transport, Tools tools, String systemPrompt) throws Exception {
		this.transport = transport;
		this.tools = tools;
		messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
	}

	public Result ask(String question, Cancellation cancellation, Observer observer) throws Exception {
		int checkpoint = messages.length();
		try {
			cancellation.check();
			messages.put(new JSONObject().put("role", "user").put("content", question));
			for (int round = 0; round < MAX_ROUNDS; round++) {
				cancellation.check();
				if (messages.toString().length() > MAX_CONTEXT_CHARS) {
					throw new IOException("会话达到 256 Ki 字符上限，本次问题未完成且已回退；请重新开始或缩小问题范围");
				}
				JSONObject response = transport.complete(messages, tools.definitions(), cancellation);
				cancellation.check();
				if (!"assistant".equals(response.optString("role"))) {
					throw new IOException("服务消息角色不是 assistant，无法继续工具会话");
				}
				JSONArray calls = response.optJSONArray("tool_calls");
				JSONObject assistant = new JSONObject().put("role", "assistant");
				Object content = response.opt("content");
				if (content != null && content != JSONObject.NULL && !(content instanceof String)) {
					throw new IOException("服务返回不支持的 assistant 内容类型");
				}
				assistant.put("content", content == null ? JSONObject.NULL : content);
				if (calls == null || calls.length() == 0) {
					if (!(content instanceof String) || ((String) content).trim().isEmpty()) {
						throw new IOException("模型未返回分析或工具请求，请确认模型支持 Chat Completions tools");
					}
					messages.put(assistant);
					return new Result((String) content, false);
				}
				if (calls.length() > 8) throw new IOException("单轮工具请求超过 8 个上限，请重新提问");
				java.util.HashSet<String> ids = new java.util.HashSet<>();
				for (int i = 0; i < calls.length(); i++) {
					JSONObject call = calls.getJSONObject(i);
					String id = call.optString("id", "");
					if (id.isEmpty() || !ids.add(id) || !"function".equals(call.optString("type"))) {
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
						result = new JSONObject().put("error", "工具名称、参数或文件状态无效；仅允许已授权文件及指定只读工具");
					}
					cancellation.check();
					messages.put(new JSONObject().put("role", "tool").put("tool_call_id", call.getString("id"))
						.put("content", result.toString()));
					observer.toolStatus(tools.summary(name, result));
				}
			}
			cancellation.check();
			return new Result("已达到本次问题 24 轮请求预算，尚未得到最终分析。可继续追问以继续探索，或停止并重新开始。", true);
		} catch (Exception e) {
			while (messages.length() > checkpoint) messages.remove(messages.length() - 1);
			throw e;
		}
	}
}
