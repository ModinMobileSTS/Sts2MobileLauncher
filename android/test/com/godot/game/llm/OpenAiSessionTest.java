package com.godot.game.llm;

import com.godot.game.loganalysis.LogTools;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = android.app.Application.class)
public class OpenAiSessionTest {
	private static final int WAIT_SECONDS = 10;
	private static final String SSE = "text/event-stream; charset=utf-8";
	private static final String JSON = "application/json";
	private static final String DONE = "data: [DONE]\r\n\r\n";
	@Rule public final TemporaryFolder temp = new TemporaryFolder();

	private LogTools syntheticLogs() throws Exception {
		File root = temp.newFolder();
		File logs = new File(root, "logs");
		assertTrue(logs.mkdir());
		Files.write(new File(logs, "sts2.log").toPath(), "PRIVATE_LOG_MARKER\nloading\nERROR synthetic crash\nstack frame\n".getBytes(StandardCharsets.UTF_8));
		return new LogTools(root, null, null);
	}

	private static JSONObject call(String id, String name, String args) throws Exception {
		return new JSONObject().put("id", id).put("type", "function").put("function",
			new JSONObject().put("name", name).put("arguments", args));
	}
	private static JSONObject fragment(int index, String id, String name, String args) throws Exception {
		return new JSONObject().put("index", index).put("id", id).put("function",
			new JSONObject().put("name", name).put("arguments", args));
	}
	private static JSONObject text(String text) throws Exception { return new JSONObject().put("content", text); }
	private static JSONObject assistant(String text) throws Exception { return text(text).put("role", "assistant"); }
	private static String choice(JSONObject choice) throws Exception {
		return new JSONObject().put("choices", new JSONArray().put(choice)).toString();
	}
	private static String chunk(JSONObject delta, String finish) throws Exception {
		return "data: " + choice(new JSONObject().put("index", 0).put("delta", delta)
			.put("finish_reason", finish == null ? JSONObject.NULL : finish)) + "\r\n\r\n";
	}
	private static Reply json(JSONObject message) throws Exception {
		return body(200, JSON, choice(new JSONObject().put("message", message)
			.put("finish_reason", message.has("tool_calls") ? "tool_calls" : "stop")));
	}
	private static Reply listLogs() throws Exception {
		JSONObject delta = assistant("先列出日志。").put("tool_calls", new JSONArray()
			.put(fragment(0, "list-id", "search_logs", "{}").put("type", "function")));
		return body(200, SSE, chunk(delta, "tool_calls")); // Terminal finish_reason, no [DONE].
	}

	private static final class Received {
		final String headers;
		final JSONObject body;
		Received(String headers, JSONObject body) { this.headers = headers; this.body = body; }
		JSONArray messages() throws JSONException { return body.getJSONArray("messages"); }
	}
	private interface Reply { void send(Received request, OutputStream output) throws Exception; }
	private static void headers(OutputStream out, int status, String type) throws IOException {
		String head = "HTTP/1.1 " + status + " Test\r\nContent-Type: " + type + "\r\nConnection: close\r\n"
			+ (status == 302 ? "Location: http://localhost:9/must-not-leak\r\n" : "") + "\r\n";
		out.write(head.getBytes(StandardCharsets.US_ASCII));
	}
	private static void flush(OutputStream out, String data) throws IOException {
		out.write(data.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}
	private static Reply body(int status, String type, String data) {
		return (request, out) -> { headers(out, status, type); flush(out, data); };
	}

	/** Scripted HTTP peer. Flushing an SSE event need not complete its HTTP response. */
	private static final class LocalServer implements AutoCloseable {
		final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
		final BlockingQueue<Received> requests = new LinkedBlockingQueue<>();
		final ExecutorService worker = Executors.newSingleThreadExecutor();
		final Future<?> loop;
		volatile Socket active;
		LocalServer(Reply... replies) throws IOException {
			loop = worker.submit(() -> {
				try (ServerSocket listener = server) {
					for (Reply reply : replies) {
						try (Socket socket = listener.accept()) {
							active = socket;
							socket.setSoTimeout(WAIT_SECONDS * 1000);
							InputStream input = socket.getInputStream();
							StringBuilder headers = new StringBuilder();
							String line;
							int length = 0;
							while (!(line = asciiLine(input)).isEmpty()) {
								headers.append(line).append('\n');
								if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) length = Integer.parseInt(line.substring(15).trim());
							}
							String body = new String(input.readNBytes(length), StandardCharsets.UTF_8);
							Received request = new Received(headers.toString(), new JSONObject(body));
							requests.add(request);
							reply.send(request, socket.getOutputStream());
						}
					}
				}
				return null;
			});
		}
		String base() { return "http://127.0.0.1:" + server.getLocalPort() + "/v1"; }
		Received next() throws Exception {
			Received request = requests.poll(WAIT_SECONDS, TimeUnit.SECONDS);
			assertNotNull("Missing HTTP request", request);
			assertTrue(request.headers.startsWith("POST /v1/chat/completions HTTP/1.1\n"));
			assertTrue(request.body.getBoolean("stream"));
			return request;
		}
		void finished() throws Exception { loop.get(WAIT_SECONDS, TimeUnit.SECONDS); assertTrue(requests.isEmpty()); }
		@Override public void close() throws IOException {
			server.close();
			if (active != null) active.close();
			worker.shutdownNow();
		}
		private static String asciiLine(InputStream input) throws IOException {
			ByteArrayOutputStream line = new ByteArrayOutputStream(); int value;
			while ((value = input.read()) != -1 && value != '\n') if (value != '\r') line.write(value);
			if (value == -1) throw new IOException("EOF in HTTP headers");
			return line.toString(StandardCharsets.US_ASCII.name());
		}
	}

	@Test public void endpointNormalizationNeverDuplicatesV1AndAcceptsFullEndpoint() {
		assertEquals("https://example.test/v1/chat/completions", new LlmConfig("https://example.test/", "", "m", "").endpoint().toString());
		assertEquals("https://example.test/v1/chat/completions", new LlmConfig("https://example.test/v1/", "", "m", "").endpoint().toString());
		assertEquals("https://example.test/custom/chat/completions", new LlmConfig("https://example.test/custom/chat/completions/", "", "m", "").endpoint().toString());
		assertEquals("https://example.test/proxy/v1/chat/completions", new LlmConfig("https://example.test/proxy/v1", "", "m", "").endpoint().toString());
		try { new LlmConfig("https://user:secret@example.test/v1", "", "m", "").endpoint(); fail("Reject URL credentials"); }
		catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("secret")); }
	}

	private static JSONObject result(JSONArray messages, int index, String id) throws Exception {
		JSONObject tool = messages.getJSONObject(index);
		assertEquals("tool", tool.getString("role"));
		assertEquals(id, tool.getString("tool_call_id"));
		return new JSONObject(tool.getString("content"));
	}

	private static void requestEvidence(Received request, OutputStream out) throws Exception {
		String id = result(request.messages(), 3, "list-id").getJSONArray("files").getJSONObject(0).getString("file_id");
		String prefix = "{\"file_id\":" + JSONObject.quote(id) + ",";
		// Index 1 arrives first. IDs, names and JSON arguments all cross event boundaries.
		JSONArray starts = new JSONArray().put(fragment(1, "find-", "search_", prefix).put("type", "function"))
			.put(fragment(0, "read-", "read_", prefix).put("type", "function"));
		JSONArray ends = new JSONArray().put(fragment(0, "001", "lines", "\"start_line\":3,\"end_line\":4}"))
			.put(fragment(1, "002", "logs", "\"keyword\":\"ERROR\"}"));
		headers(out, 200, SSE);
		flush(out, chunk(text("再查证据。").put("reasoning_content", "ctx-").put("tool_calls", starts), null)
			+ chunk(new JSONObject().put("reasoning_content", "42").put("tool_calls", ends), null) + DONE);
	}

	@Test public void streamingListReadSearchAndJsonFollowupKeepEvidenceAndRoundBoundaries() throws Exception {
		String prefix = "第三行出现 ";
		String suffix = "ERROR。";
		String followupAnswer = "调用栈在第四行。";
		CountDownLatch prefixObserved = new CountDownLatch(1);
		Reply streamingAnswer = (request, out) -> {
			headers(out, 200, SSE);
			String event = chunk(text(prefix), null).replace("data: {", "data: {\r\ndata: ");
			flush(out, ": keepalive\r\n\r\ndata: {\"choices\":[]}\r\n\r\nevent: message\r\n" + event);
			assertTrue("No observer delta while the response was open", prefixObserved.await(WAIT_SECONDS, TimeUnit.SECONDS));
			flush(out, chunk(text(suffix), "stop"));
		};
		LogTools tools = syntheticLogs();
		try (LocalServer server = new LocalServer(listLogs(), OpenAiSessionTest::requestEvidence, streamingAnswer, json(assistant(followupAnswer)))) {
			ChatSession session = new ChatSession(new OpenAiClient(new LlmConfig(server.base(), "test-private-key", "model-id", "")), tools, tools.systemPrompt());
			List<String> events = new ArrayList<>();
			List<String> statuses = new ArrayList<>();
			ChatSession.Observer observer = new ChatSession.Observer() {
				@Override public void onRoundStart() { events.add("round"); }
				@Override public void toolStatus(String status) { statuses.add(status); }
				@Override public void onText(String delta) {
					events.add(delta);
					if (prefix.equals(delta)) prefixObserved.countDown();
				}
			};
			assertEquals(prefix + suffix, session.ask("分析崩溃", new Cancellation(), observer));
			assertEquals(followupAnswer, session.ask("再核实调用栈", new Cancellation(), observer));
			assertEquals(Arrays.asList("round", "先列出日志。", "round", "再查证据。", "round", prefix, suffix, "round", followupAnswer), events);
			assertEquals(3, statuses.size());
			for (String status : statuses) assertFalse(status.contains("stack frame"));

			Received initial = server.next();
			assertFalse(initial.body.toString().contains("PRIVATE_LOG_MARKER"));
			assertFalse(initial.body.toString().contains("test-private-key"));
			assertTrue(initial.headers.contains("Bearer test-private-key"));
			assertFalse(initial.body.has("reasoning_effort"));
			assertEquals("model-id", initial.body.getString("model"));
			assertEquals("auto", initial.body.getString("tool_choice"));
			List<String> names = new ArrayList<>();
			JSONArray defs = initial.body.getJSONArray("tools");
			for (int i = 0; i < defs.length(); i++) names.add(defs.getJSONObject(i).getJSONObject("function").getString("name"));
			assertEquals(2, names.size());
			assertTrue(names.containsAll(Arrays.asList("read_lines", "search_logs")));
			JSONArray listed = server.next().messages();
			assertFalse(listed.toString().contains("PRIVATE_LOG_MARKER"));
			assertEquals("list-id", listed.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id"));
			String id = result(listed, 3, "list-id").getJSONArray("files").getJSONObject(0).getString("file_id");
			assertEquals("internal/logs/sts2.log", id);

			JSONArray evidence = server.next().messages();
			JSONObject queries = evidence.getJSONObject(4);
			assertEquals("assistant", queries.getString("role"));
			assertEquals("再查证据。", queries.getString("content"));
			assertEquals("ctx-42", queries.getString("reasoning_content"));
			JSONArray calls = queries.getJSONArray("tool_calls");
			assertEquals(2, calls.length());
			String[] ids = {"read-001", "find-002"};
			String[] functions = {"read_lines", "search_logs"};
			for (int i = 0; i < calls.length(); i++) {
				JSONObject call = calls.getJSONObject(i);
				assertEquals(ids[i], call.getString("id"));
				assertEquals("function", call.getString("type"));
				assertFalse(call.has("index"));
				JSONObject function = call.getJSONObject("function");
				assertEquals(functions[i], function.getString("name"));
				assertEquals(id, new JSONObject(function.getString("arguments")).getString("file_id"));
			}
			JSONArray lines = result(evidence, 5, "read-001").getJSONArray("lines");
			assertEquals(2, lines.length());
			assertEquals(4, lines.getJSONObject(1).getInt("line"));
			assertEquals("stack frame", lines.getJSONObject(1).getString("text"));
			JSONArray hits = result(evidence, 6, "find-002").getJSONArray("lines");
			boolean found = false;
			for (int i = 0; i < hits.length(); i++) {
				JSONObject hit = hits.getJSONObject(i);
				if (hit.getInt("line") == 3 && hit.getBoolean("match") && "ERROR synthetic crash".equals(hit.getString("text"))) found = true;
			}
			assertTrue(found);
			JSONArray followup = server.next().messages();
			assertEquals(9, followup.length());
			for (int i = 0; i < evidence.length(); i++) assertEquals(evidence.getJSONObject(i).toString(), followup.getJSONObject(i).toString());
			assertEquals(prefix + suffix, followup.getJSONObject(7).getString("content"));
			assertEquals("再核实调用栈", followup.getJSONObject(8).getString("content"));
			server.finished();
		}
	}

	@Test public void malformedArgumentsUnknownToolAndInvalidFileEachGetAnAssociatedError() throws Exception {
		LogTools tools = syntheticLogs();
		JSONObject message = assistant("").put("tool_calls", new JSONArray().put(call("unknown", "shell", "{}"))
			.put(call("bad-json", "read_lines", "not JSON"))
			.put(call("escape", "read_lines", "{\"file_id\":\"../../other\",\"start_line\":1,\"end_line\":1}")));
		try (LocalServer server = new LocalServer(json(message), json(assistant("工具错误，无法证明原因")))) {
			ChatSession session = new ChatSession(new OpenAiClient(new LlmConfig(server.base(), "", "model", "high")), tools, tools.systemPrompt());
			assertEquals("工具错误，无法证明原因", session.ask("分析", new Cancellation(), status -> {}));
			Received first = server.next();
			assertFalse(first.headers.contains("Authorization:"));
			assertEquals("high", first.body.getString("reasoning_effort"));
			JSONArray history = server.next().messages();
			String[] ids = {"unknown", "bad-json", "escape"};
			for (int i = 0; i < ids.length; i++) assertTrue(result(history, 3 + i, ids[i]).has("error"));
			server.finished();
		}
	}

	@Test public void eofAfterAPartialStreamRollsBackEvenTheCompletedToolRound() throws Exception {
		LogTools tools = syntheticLogs();
		try (LocalServer server = new LocalServer(json(assistant("保留")), listLogs(), body(200, SSE, chunk(text("未完成"), null)), json(assistant("恢复")))) {
			ChatSession session = new ChatSession(new OpenAiClient(new LlmConfig(server.base(), "", "model", "")), tools, tools.systemPrompt());
			session.ask("早先的问题", new Cancellation(), status -> {});
			try { session.ask("中断的问题", new Cancellation(), status -> {}); fail("Expected truncated stream"); }
			catch (IOException expected) { assertTrue(expected.getMessage().contains("中断")); }
			assertEquals("恢复", session.ask("重试", new Cancellation(), status -> {}));
			server.next(); server.next();
			assertEquals("list-id", server.next().messages().getJSONObject(5).getString("tool_call_id"));
			JSONArray recovered = server.next().messages();
			assertEquals(4, recovered.length());
			assertEquals("早先的问题", recovered.getJSONObject(1).getString("content"));
			assertEquals("保留", recovered.getJSONObject(2).getString("content"));
			assertEquals("重试", recovered.getJSONObject(3).getString("content"));
			server.finished();
		}
	}

	@Test public void cancellationAbortsAnOpenStreamAndDoesNotCommitThePartialTurn() throws Exception {
		CountDownLatch observed = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Reply openStream = (request, out) -> {
			headers(out, 200, SSE);
			flush(out, chunk(text("部分回答"), null));
			assertTrue(release.await(2L * WAIT_SECONDS, TimeUnit.SECONDS));
		};
		LogTools tools = syntheticLogs();
		ExecutorService worker = Executors.newSingleThreadExecutor();
		Cancellation token = new Cancellation();
		try (LocalServer server = new LocalServer(openStream, json(assistant("新的回答")))) {
			ChatSession session = new ChatSession(new OpenAiClient(new LlmConfig(server.base(), "", "model", "")), tools, tools.systemPrompt());
			Future<String> pending = worker.submit(() -> session.ask("停止的问题", token, new ChatSession.Observer() {
				@Override public void toolStatus(String status) { fail("Unexpected tool result"); }
				@Override public void onText(String delta) { if ("部分回答".equals(delta)) observed.countDown(); }
			}));
			assertTrue(observed.await(WAIT_SECONDS, TimeUnit.SECONDS));
			token.cancel();
			try { pending.get(WAIT_SECONDS, TimeUnit.SECONDS); fail("Expected cancellation while peer is still open"); }
			catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof InterruptedIOException); }
			release.countDown();
			assertEquals("新的回答", session.ask("重试", new Cancellation(), status -> {}));
			server.next();
			JSONArray recovered = server.next().messages();
			assertEquals(2, recovered.length());
			assertEquals("重试", recovered.getJSONObject(1).getString("content"));
			server.finished();
		} finally { release.countDown(); token.cancel(); worker.shutdownNow(); }
	}

	@Test public void fullJsonReplyIsNotCappedOrSplitIntoPretendDeltas() throws Exception {
		char[] chars = new char[1024 * 1024 + 1];
		Arrays.fill(chars, 'a');
		String answer = new String(chars);
		try (LocalServer server = new LocalServer(json(assistant(answer)))) {
			OpenAiClient client = new OpenAiClient(new LlmConfig(server.base(), "", "model", ""));
			List<String> observed = new ArrayList<>();
			JSONObject message = client.complete(new JSONArray(), new JSONArray(), new Cancellation(), observed::add);
			assertTrue(answer.equals(message.getString("content")));
			assertEquals(1, observed.size());
			assertTrue(answer.equals(observed.get(0)));
			server.next(); server.finished();
		}
	}

	@Test public void httpRedirectAndProtocolErrorsAreUsefulWithoutEchoingSecretsOrDowngrading() throws Exception {
		String error = "{\"error\":{\"message\":\"private-service-body\"}}";
		String filtered = choice(new JSONObject().put("message", assistant("private-service-body")).put("finish_reason", "content_filter"));
		String unindexed = chunk(new JSONObject().put("tool_calls", new JSONArray().put(call("orphan", "search_logs", "{}"))), null);
		try (LocalServer server = new LocalServer(body(400, JSON, error), body(302, JSON, ""), body(200, JSON, "{private-service-body"),
			body(200, JSON, filtered), body(200, SSE, chunk(text("private-service-body"), "length")), body(200, SSE, "data: " + error + "\n\n"),
			body(200, SSE, unindexed), body(200, SSE, chunk(text("preamble"), "tool_calls")))) {
			OpenAiClient client = new OpenAiClient(new LlmConfig(server.base(), "test-private-key", "model", ""));
			for (String reason : Arrays.asList("HTTP 400", "HTTP 302", "Chat Completions", "content_filter", "length", "错误", "index", "tool_calls")) {
				try { client.complete(new JSONArray(), new JSONArray(), new Cancellation(), text -> {}); fail("Expected " + reason); }
				catch (IOException expected) {
					assertTrue(expected.getMessage().contains(reason));
					assertFalse(expected.getMessage().contains("test-private-key"));
					assertFalse(expected.getMessage().contains("private-service-body"));
				}
				server.next();
			}
			server.finished();
		}
	}
}
