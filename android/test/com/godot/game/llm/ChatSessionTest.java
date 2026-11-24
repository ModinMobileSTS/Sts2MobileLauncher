package com.godot.game.llm;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = android.app.Application.class)
public class ChatSessionTest {
	private static ChatSession.Tools pages(String excerpt) {
		return new ChatSession.Tools() {
			@Override public JSONArray definitions() throws Exception {
				return new JSONArray().put(new JSONObject().put("type", "function")
					.put("function", new JSONObject().put("name", "read_page")));
			}
			@Override public JSONObject execute(String name, JSONObject args, Cancellation token) throws Exception {
				assertEquals("read_page", name);
				return new JSONObject().put("page", args.getString("page")).put("excerpt", excerpt);
			}
			@Override public String summary(String name, JSONObject result) { return name; }
		};
	}

	private static JSONObject toolRound(int round, int count) throws Exception {
		JSONArray calls = new JSONArray();
		for (int i = 0; i < count; i++) {
			String page = round + "-" + i;
			calls.put(new JSONObject().put("id", "call-" + page).put("type", "function")
				.put("function", new JSONObject().put("name", "read_page")
					.put("arguments", new JSONObject().put("page", page).toString())));
		}
		return new JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls);
	}

	private static JSONObject answer(String text) throws Exception {
		return new JSONObject().put("role", "assistant").put("content", text);
	}

	@Test public void longInvestigationReturnsEveryPageToTheProviderUntilItAnswers() throws Exception {
		char[] chars = new char[8192];
		Arrays.fill(chars, 'x');
		String excerpt = new String(chars);
		AtomicInteger requests = new AtomicInteger();
		ChatSession session = new ChatSession((messages, definitions, token, onText) -> {
			int round = requests.getAndIncrement();
			if (round > 0) {
				int previousCalls = round == 1 ? 10 : 1;
				int start = messages.length() - previousCalls;
				JSONArray calls = messages.getJSONObject(start - 1).getJSONArray("tool_calls");
				assertEquals(previousCalls, calls.length());
				for (int i = 0; i < previousCalls; i++) {
					JSONObject tool = messages.getJSONObject(start + i);
					String page = (round - 1) + "-" + i;
					assertEquals("tool", tool.getString("role"));
					assertEquals("call-" + page, calls.getJSONObject(i).getString("id"));
					assertEquals("call-" + page, tool.getString("tool_call_id"));
					JSONObject result = new JSONObject(tool.getString("content"));
					assertEquals(page, result.getString("page"));
					assertEquals(excerpt, result.getString("excerpt"));
				}
			}
			if (round == 27) return answer("完整证据已核实");
			return toolRound(round, round == 0 ? 10 : 1);
		}, pages(excerpt), "system");
		AtomicInteger statuses = new AtomicInteger();
		assertEquals("完整证据已核实", session.ask("核实所有分页", new Cancellation(), status -> statuses.incrementAndGet()));
		assertEquals(36, statuses.get());
		assertEquals(28, requests.get());
	}

	@Test public void stoppingBetweenParallelToolsRollsBackTheWholeQuestionOnly() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		ChatSession session = new ChatSession((messages, definitions, token, onText) -> {
			switch (requests.getAndIncrement()) {
				case 0: return answer("先前的回答");
				case 1: return toolRound(0, 2);
				default:
					assertEquals(4, messages.length());
					assertEquals("先前的问题", messages.getJSONObject(1).getString("content"));
					assertEquals("先前的回答", messages.getJSONObject(2).getString("content"));
					assertEquals("重新提问", messages.getJSONObject(3).getString("content"));
					return answer("新回答");
			}
		}, pages("evidence"), "system");
		session.ask("先前的问题", new Cancellation(), status -> {});
		Cancellation token = new Cancellation();
		AtomicInteger executed = new AtomicInteger();
		try {
			session.ask("取消的问题", token, status -> { executed.incrementAndGet(); token.cancel(); });
			fail("Expected cancellation");
		} catch (InterruptedIOException expected) { assertEquals("已停止", expected.getMessage()); }
		assertEquals(1, executed.get());
		assertEquals("新回答", session.ask("重新提问", new Cancellation(), status -> {}));
	}

	@Test public void missingOrDuplicateIdsFailBeforeAnyToolRunsAndDoNotPolluteHistory() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		ChatSession session = new ChatSession((messages, definitions, token, onText) -> {
			assertEquals(2, messages.length());
			int attempt = requests.getAndIncrement();
			if (attempt == 2) return answer("服务响应恢复");
			JSONObject response = toolRound(0, 2);
			JSONArray calls = response.getJSONArray("tool_calls");
			if (attempt == 0) calls.getJSONObject(1).remove("id");
			else calls.getJSONObject(1).put("id", calls.getJSONObject(0).getString("id"));
			return response;
		}, pages("evidence"), "system");
		for (int i = 0; i < 2; i++) {
			try { session.ask("问题", new Cancellation(), status -> fail("Unexpected tool result")); fail("Expected protocol error"); }
			catch (IOException expected) { assertTrue(expected.getMessage().contains("tool_call_id")); }
		}
		assertEquals("服务响应恢复", session.ask("重新提问", new Cancellation(), status -> {}));
	}
}
