package com.godot.game.llm;

import com.godot.game.loganalysis.LogTools;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = android.app.Application.class)
public class OpenAiSessionTest {
	private static JSONObject call(String id, String name, Object args) throws Exception {
		return new JSONObject().put("id", id).put("type", "function").put("function",
			new JSONObject().put("name", name).put("arguments", args));
	}
	private static JSONObject finalMessage(String text) throws Exception {
		return new JSONObject().put("role", "assistant").put("content", text);
	}
	private static String answer(JSONObject message) throws Exception {
		return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
			.put("finish_reason", message.has("tool_calls") ? "tool_calls" : "stop").put("message", message))).toString();
	}
	private static final class Received {
		final String headers;
		final JSONObject body;
		Received(String headers, JSONObject body) { this.headers = headers; this.body = body; }
	}

	/** Tiny loopback HTTP peer: no production mocks, no extra dependency, no diagnostic secret output. */
	private static final class LocalServer implements AutoCloseable {
		final ServerSocket server = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
		final ArrayBlockingQueue<Received> requests = new ArrayBlockingQueue<>(32);
		final ExecutorService worker = Executors.newSingleThreadExecutor();
		final CountDownLatch release = new CountDownLatch(1);
		volatile Socket active;
		final Future<?> loop;
		LocalServer(int status, String... bodies) throws IOException {
			loop = worker.submit(() -> {
				try {
					for (String body : bodies) {
						try (Socket socket = server.accept()) {
							active = socket; socket.setSoTimeout(5000);
							InputStream input = socket.getInputStream();
							StringBuilder headers = new StringBuilder();
							String line; int length = 0;
							while (!(line = asciiLine(input)).isEmpty()) {
								headers.append(line).append('\n');
								if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
							}
							byte[] bytes = input.readNBytes(length);
							requests.put(new Received(headers.toString(), new JSONObject(new String(bytes, StandardCharsets.UTF_8))));
							if (body == null) release.await();
							else {
								byte[] response = body.getBytes(StandardCharsets.UTF_8);
								String header = "HTTP/1.1 " + status + " Test\r\nContent-Type: application/json\r\nContent-Length: " + response.length
									+ (status == 302 ? "\r\nLocation: http://localhost:9/must-not-leak" : "") + "\r\nConnection: close\r\n\r\n";
								socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
								socket.getOutputStream().write(response); socket.getOutputStream().flush();
							}
						}
					}
				} catch (Exception e) { if (!server.isClosed()) throw new RuntimeException("Loopback peer failed", e); }
			});
		}
		String base() { return "http://127.0.0.1:" + server.getLocalPort() + "/v1"; }
		Received next() throws Exception { Received received = requests.poll(5, TimeUnit.SECONDS); assertNotNull("Missing HTTP request", received); return received; }
		void finished() throws Exception { loop.get(5, TimeUnit.SECONDS); }
		@Override public void close() throws Exception { server.close(); release.countDown(); if (active != null) active.close(); worker.shutdownNow(); }
		static String asciiLine(InputStream input) throws IOException {
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

	@Test public void realHttpMultiToolLoopPreservesIdsAndFollowupWithoutInitialContentUpload() throws Exception {
		File file = File.createTempFile("evidence-", ".log");
		try {
			Files.write(file.toPath(), "PRIVATE_LOG_MARKER\nERROR crash\nstack frame".getBytes(StandardCharsets.UTF_8));
			LogTools tools = new LogTools(Collections.singletonList(file), new Cancellation());
			JSONObject request = new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
				.put("tool_calls", new JSONArray()
					.put(call("read-id", "read_lines", "{\"file_id\":\"log_1\",\"start_line\":2,\"end_line\":3}"))
					.put(call("search-id", "search_logs", "{\"file_id\":\"log_1\",\"keyword\":\"ERROR\"}")));
			try (LocalServer server = new LocalServer(200, answer(request), answer(finalMessage("已证实崩溃日志：log_1:2；原因仍待证实")), answer(finalMessage("进一步证据：log_1:3")))) {
				ChatSession session = new ChatSession(new OpenAiClient(new LlmConfig(server.base(), "test-private-key", "model-id", "")), tools, tools.systemPrompt());
				List<String> statuses = new ArrayList<>();
				assertFalse(session.ask("分析崩溃", new Cancellation(), statuses::add).budgetReached);
				Received initial = server.next();
				assertFalse(initial.body.toString().contains("PRIVATE_LOG_MARKER"));
				assertFalse(initial.body.toString().contains("test-private-key"));
				assertTrue(initial.headers.contains("Bearer test-private-key"));
				assertFalse(initial.body.has("reasoning_effort"));
				assertEquals("auto", initial.body.getString("tool_choice"));
				assertEquals(2, initial.body.getJSONArray("tools").length());
				JSONArray messages = server.next().body.getJSONArray("messages");
				assertEquals("assistant", messages.getJSONObject(2).getString("role"));
				assertEquals(2, messages.getJSONObject(2).getJSONArray("tool_calls").length());
				assertEquals("tool", messages.getJSONObject(3).getString("role"));
				assertEquals("read-id", messages.getJSONObject(3).getString("tool_call_id"));
				assertEquals("search-id", messages.getJSONObject(4).getString("tool_call_id"));
				assertEquals(2, statuses.size()); assertFalse(statuses.get(0).contains("stack frame"));
				assertTrue(session.ask("再核实调用栈", new Cancellation(), statuses::add).text.contains("log_1:3"));
				JSONArray followup = server.next().body.getJSONArray("messages");
				assertEquals("read-id", followup.getJSONObject(3).getString("tool_call_id"));
				assertEquals("assistant", followup.getJSONObject(5).getString("role"));
				assertEquals("再核实调用栈", followup.getJSONObject(6).getString("content"));
				server.finished();
			}
		} finally { Files.deleteIfExists(file.toPath()); }
	}

	@Test public void malformedAndUnauthorizedToolCallsReturnAssociatedToolErrors() throws Exception {
		File file = File.createTempFile("tool-errors-", ".log");
		try {
			Files.write(file.toPath(), "data".getBytes(StandardCharsets.UTF_8));
			LogTools tools = new LogTools(Collections.singletonList(file), new Cancellation());
			JSONObject assistant = finalMessage("").put("tool_calls", new JSONArray()
				.put(call("unknown", "shell", "{}"))
				.put(call("bad-json", "read_lines", "not JSON"))
				.put(call("escape", "read_lines", "{\"file_id\":\"../../other\",\"start_line\":1,\"end_line\":1}")));
			try (LocalServer server = new LocalServer(200, answer(assistant), answer(finalMessage("工具错误，无法证明原因")))) {
				ChatSession session = new ChatSession(new OpenAiClient(new LlmConfig(server.base(), "", "model", "high")), tools, tools.systemPrompt());
				session.ask("分析", new Cancellation(), status -> {});
				assertEquals("high", server.next().body.getString("reasoning_effort"));
				JSONArray history = server.next().body.getJSONArray("messages");
				String[] ids = {"unknown", "bad-json", "escape"};
				for (int i = 0; i < ids.length; i++) {
					JSONObject tool = history.getJSONObject(3 + i);
					assertEquals(ids[i], tool.getString("tool_call_id"));
					assertTrue(new JSONObject(tool.getString("content")).has("error"));
				}
				server.finished();
			}
		} finally { Files.deleteIfExists(file.toPath()); }
	}

	@Test public void cancellationAbortsAnInFlightHttpRequest() throws Exception {
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try (LocalServer server = new LocalServer(200, (String) null)) {
			OpenAiClient client = new OpenAiClient(new LlmConfig(server.base(), "", "model", ""));
			Cancellation cancellation = new Cancellation();
			Future<Boolean> stopped = worker.submit(() -> {
				try { client.complete(new JSONArray(), new JSONArray(), cancellation); return false; }
				catch (InterruptedIOException expected) { return true; }
			});
			server.next(); cancellation.cancel();
			assertTrue(stopped.get(5, TimeUnit.SECONDS));
		} finally { worker.shutdownNow(); }
	}

	@Test public void serviceFailureDoesNotEchoSecretsOrFallbackToFullLog() throws Exception {
		try (LocalServer server = new LocalServer(400, "{\"error\":\"secret-body-from-service\"}")) {
			OpenAiClient client = new OpenAiClient(new LlmConfig(server.base(), "private-key", "model", ""));
			try { client.complete(new JSONArray(), new JSONArray(), new Cancellation()); fail("Expected HTTP failure"); }
			catch (IOException expected) {
				assertTrue(expected.getMessage().contains("HTTP 400"));
				assertFalse(expected.getMessage().contains("secret-body"));
				assertFalse(expected.getMessage().contains("private-key"));
			}
			server.next(); server.finished(); assertEquals(0, server.requests.size());
		}
	}

	@Test public void redirectsFailClosedWithoutFollowingAnotherHost() throws Exception {
		try (LocalServer server = new LocalServer(302, "")) {
			try {
				new OpenAiClient(new LlmConfig(server.base(), "private-key", "model", ""))
					.complete(new JSONArray(), new JSONArray(), new Cancellation());
				fail("Expected redirect refusal");
			} catch (IOException expected) { assertTrue(expected.getMessage().contains("HTTP 302")); }
			server.next(); server.finished();
		}
	}
}
