package com.godot.game.llm;

import com.godot.game.loganalysis.LogTools;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = android.app.Application.class)
public class ChatSessionTest {
	private File file;
	private LogTools tools;
	@Before public void setup() throws Exception {
		file = File.createTempFile("session-", ".log");
		Files.write(file.toPath(), "ERROR evidence".getBytes(StandardCharsets.UTF_8));
		tools = new LogTools(Collections.singletonList(file), new Cancellation());
	}
	@After public void cleanup() throws Exception { Files.deleteIfExists(file.toPath()); }
	private JSONObject request(String id) throws Exception {
		return new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
			.put("tool_calls", new JSONArray().put(new JSONObject().put("id", id).put("type", "function")
				.put("function", new JSONObject().put("name", "read_lines")
					.put("arguments", "{\"file_id\":\"log_1\",\"start_line\":1,\"end_line\":1}"))));
	}

	@Test public void roundBudgetLeavesCompleteToolAssociationsAndAllowsAnExplicitFollowup() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		ChatSession session = new ChatSession((messages, definitions, cancellation) -> {
			int index = requests.incrementAndGet();
			if (index <= ChatSession.MAX_ROUNDS) return request("call-" + index);
			assertEquals("tool", messages.getJSONObject(messages.length() - 2).getString("role"));
			assertEquals("call-24", messages.getJSONObject(messages.length() - 2).getString("tool_call_id"));
			assertEquals("继续", messages.getJSONObject(messages.length() - 1).getString("content"));
			return new JSONObject().put("role", "assistant").put("content", "证据 log_1:1，原因仍未知");
		}, tools, tools.systemPrompt());
		ChatSession.Result first = session.ask("分析", new Cancellation(), status -> {});
		assertTrue(first.budgetReached); assertEquals(24, requests.get());
		assertTrue(first.text.contains("尚未得到最终分析"));
		ChatSession.Result second = session.ask("继续", new Cancellation(), status -> {});
		assertFalse(second.budgetReached); assertEquals(25, requests.get());
	}

	@Test public void cancelledToolTurnRollsBackAllPartialProtocolHistory() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		ChatSession session = new ChatSession((messages, definitions, cancellation) -> {
			if (requests.incrementAndGet() == 1) return request("cancelled-call");
			assertEquals(2, messages.length());
			assertEquals("重新提问", messages.getJSONObject(1).getString("content"));
			assertFalse(messages.toString().contains("cancelled-call"));
			return new JSONObject().put("role", "assistant").put("content", "需要进一步证据");
		}, tools, tools.systemPrompt());
		Cancellation cancellation = new Cancellation();
		try {
			session.ask("取消的问题", cancellation, status -> cancellation.cancel());
			fail("Expected cancellation");
		} catch (InterruptedIOException expected) { assertEquals("已停止", expected.getMessage()); }
		assertFalse(session.ask("重新提问", new Cancellation(), status -> {}).budgetReached);
	}

	@Test public void missingToolIdsFailClearlyAndDoNotPolluteNextQuestion() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		ChatSession session = new ChatSession((messages, definitions, cancellation) -> {
			if (requests.incrementAndGet() == 1) {
				JSONObject response = request("unused");
				response.getJSONArray("tool_calls").getJSONObject(0).remove("id");
				return response;
			}
			assertEquals(2, messages.length());
			return new JSONObject().put("role", "assistant").put("content", "服务响应恢复");
		}, tools, tools.systemPrompt());
		try { session.ask("问题", new Cancellation(), status -> {}); fail("Expected protocol error"); }
		catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("tool_call_id")); }
		assertEquals("服务响应恢复", session.ask("重新提问", new Cancellation(), status -> {}).text);
	}
}
