package com.godot.game.loganalysis;

import com.godot.game.llm.Cancellation;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
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
public class LogToolsTest {
	private File file;
	@Before public void setup() throws Exception { file = File.createTempFile("analysis-", ".log"); }
	@After public void cleanup() throws Exception { Files.deleteIfExists(file.toPath()); }
	private LogTools tools(String content) throws Exception {
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
		return new LogTools(Collections.singletonList(file), new Cancellation());
	}
	private JSONObject read(LogTools tools, long start, long end) throws Exception {
		return tools.execute("read_lines", new JSONObject().put("file_id", "log_1")
			.put("start_line", start).put("end_line", end), new Cancellation());
	}

	@Test public void originalUtf8CrLfAndUnterminatedTailHaveExactRanges() throws Exception {
		LogTools tools = tools("x".repeat(8191) + "\r\n中文🙂\r\n尾行");
		assertEquals(3, tools.metadata().getJSONObject(0).getLong("lines"));
		JSONArray lines = read(tools, 2, 3).getJSONArray("lines");
		assertEquals(2, lines.getJSONObject(0).getLong("line"));
		assertEquals("中文🙂", lines.getJSONObject(0).getString("text"));
		assertEquals("尾行", lines.getJSONObject(1).getString("text"));
		assertFalse(lines.getJSONObject(0).getBoolean("line_truncated"));
	}

	@Test public void emptyAndTerminalNewlineDoNotInventTailLines() throws Exception {
		assertEquals(0, tools("").metadata().getJSONObject(0).getLong("lines"));
		assertEquals(1, tools("\n").metadata().getJSONObject(0).getLong("lines"));
		LogTools tools = tools("a\r\n\r\nb\r");
		assertEquals(3, tools.metadata().getJSONObject(0).getLong("lines"));
		assertEquals("", read(tools, 2, 2).getJSONArray("lines").getJSONObject(0).getString("text"));
	}

	@Test public void literalSearchFindsMatchBeyondBoundedLongLinePrefix() throws Exception {
		LogTools tools = tools("before\n" + "x".repeat(20000) + "[.*]崩溃\nafter\nnot a match");
		JSONObject page = tools.execute("search_logs", new JSONObject().put("file_id", "log_1")
			.put("keyword", "[.*]崩溃"), new Cancellation());
		JSONArray lines = page.getJSONArray("lines");
		assertEquals(3, lines.length());
		JSONObject hit = lines.getJSONObject(1);
		assertEquals(2, hit.getLong("line")); assertTrue(hit.getBoolean("match"));
		assertTrue(hit.getBoolean("line_truncated"));
		assertEquals(LogTools.MAX_LINE_CHARS, hit.getString("text").length());
		assertFalse(page.getBoolean("truncated"));
		assertTrue(page.isNull("next_line"));
	}

	@Test public void pagesRetainOriginalLineNumbersAndResumeWithoutSkipping() throws Exception {
		StringBuilder log = new StringBuilder();
		for (int i = 1; i <= 130; i++) log.append(i).append('\n');
		LogTools tools = tools(log.toString());
		JSONObject first = read(tools, 1, 130);
		assertEquals(60, first.getJSONArray("lines").length());
		assertTrue(first.getBoolean("truncated")); assertEquals(61, first.getLong("next_line"));
		JSONObject second = read(tools, first.getLong("next_line"), 130);
		assertEquals(61, second.getJSONArray("lines").getJSONObject(0).getLong("line"));
		assertEquals(121, second.getLong("next_line"));
		JSONObject last = read(tools, 121, 130);
		assertFalse(last.getBoolean("truncated")); assertTrue(last.isNull("next_line"));
	}

	@Test public void escapedCharacterBudgetDoesNotDropAnUnreturnedLine() throws Exception {
		LogTools tools = tools(("\u0001".repeat(1200) + "\n").repeat(4));
		JSONObject first = read(tools, 1, 4);
		assertEquals(1, first.getJSONArray("lines").length());
		assertEquals(2, first.getLong("next_line"));
		assertTrue(first.toString().length() < 14000);
		assertEquals(2, read(tools, 2, 4).getJSONArray("lines").getJSONObject(0).getLong("line"));
	}

	@Test public void allowlistAndStrictBoundsRejectPathsAndInvalidArguments() throws Exception {
		LogTools tools = tools("one\ntwo\n");
		assertTrue(read(tools, 0, 1).has("error"));
		assertTrue(read(tools, 2, 1).has("error"));
		assertTrue(read(tools, 1, 3).has("error"));
		assertTrue(tools.execute("read_lines", new JSONObject().put("file_id", file.getPath())
			.put("start_line", 1).put("end_line", 1), new Cancellation()).has("error"));
		assertTrue(tools.execute("read_lines", new JSONObject().put("file_id", "log_1")
			.put("start_line", 1.5).put("end_line", 2), new Cancellation()).has("error"));
		assertTrue(tools.execute("read_lines", new JSONObject().put("file_id", "log_1")
			.put("start_line", 1).put("end_line", 1).put("path", file.getPath()), new Cancellation()).has("error"));
		assertTrue(tools.execute("shell", new JSONObject(), new Cancellation()).has("error"));
	}

	@Test public void fileMutationAndCancellationAreNotSilentlyAccepted() throws Exception {
		LogTools tools = tools("one\n");
		Files.write(file.toPath(), "two\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
		assertTrue(read(tools, 1, 1).getString("error").contains("发生变化"));
		Cancellation cancellation = new Cancellation(); cancellation.cancel();
		try { tools.execute("read_lines", new JSONObject(), cancellation); fail("Expected cancellation"); }
		catch (InterruptedIOException expected) { assertEquals("已停止", expected.getMessage()); }
		try { new LogTools(Collections.singletonList(file), cancellation); fail("Expected cancellation"); }
		catch (InterruptedIOException expected) { assertEquals("已停止", expected.getMessage()); }
	}

	@Test public void searchPaginationDoesNotLoseNextMatchingLine() throws Exception {
		LogTools tools = tools("prefix\nERROR first\nERROR second\nend");
		JSONObject args = new JSONObject().put("file_id", "log_1").put("keyword", "ERROR").put("limit", 1);
		JSONObject first = tools.execute("search_logs", args, new Cancellation());
		assertEquals(3, first.getLong("next_line"));
		args.put("start_line", first.getLong("next_line"));
		JSONArray next = tools.execute("search_logs", args, new Cancellation()).getJSONArray("lines");
		assertEquals(3, next.getJSONObject(next.length() - 1).getLong("line"));
		assertTrue(next.getJSONObject(next.length() - 1).getBoolean("match"));
	}

	@Test public void boundedPrefixNeverSplitsAnEmojiSurrogatePair() throws Exception {
		LogTools tools = tools("x".repeat(LogTools.MAX_LINE_CHARS - 1) + "🙂");
		JSONObject line = read(tools, 1, 1).getJSONArray("lines").getJSONObject(0);
		assertTrue(line.getBoolean("line_truncated"));
		assertEquals(LogTools.MAX_LINE_CHARS - 1, line.getString("text").length());
		assertFalse(Character.isHighSurrogate(line.getString("text").charAt(line.getString("text").length() - 1)));
	}
}
