package com.godot.game.loganalysis;

import com.godot.game.llm.Cancellation;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = android.app.Application.class)
public class LogToolsTest {
	@Rule public TemporaryFolder temporary = new TemporaryFolder();
	private static final String ID = "internal/logs/sts2.log";
	private File internal, external, live;
	private LogTools tools;

	@Before public void setup() throws Exception {
		internal = temporary.newFolder("internal");
		external = temporary.newFolder("external");
		live = write(internal, "logs/sts2.log", "");
		tools = new LogTools(internal, external, live);
	}

	private File write(File root, String relative, String text) throws IOException {
		File file = new File(root, relative);
		Files.createDirectories(file.getParentFile().toPath());
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	private void content(String text) throws IOException { Files.write(live.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
	private void append(String text) throws IOException {
		Files.write(live.toPath(), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
	}
	private JSONObject call(String name, JSONObject args) throws Exception { return tools.execute(name, args, new Cancellation()); }
	private JSONObject search(JSONObject args) throws Exception { return call("search_logs", args); }
	private JSONObject id(String fileId) throws Exception { return new JSONObject().put("file_id", fileId); }
	private JSONObject read(long start, long end) throws Exception {
		return call("read_lines", id(ID).put("start_line", start).put("end_line", end));
	}
	private static JSONObject line(JSONObject result, int index) throws Exception { return result.getJSONArray("lines").getJSONObject(index); }

	@Test public void freshViewerMetadataAcrossBothRootsHasStableFindableIdsAndTimes() throws Exception {
		content("PRIVATE-BODY");
		Files.setLastModifiedTime(live.toPath(), FileTime.fromMillis(1700000000000L));
		write(external, "logs/sts2.log", "other root");
		write(internal, "instances/profile-A/userdata/logs/godot.2026-11-01.log", "older run");
		write(internal, "mods/runtime.LOG.2", "numbered rotation outside logs");
		write(external, "background/engine.log.old", "plain text rotation");
		write(external, "profile/sentry/reports/report.json", "crash report");
		write(internal, "logs/no-extension", "viewer log");
		write(internal, "preferences/private.json", "not a viewer log");
		JSONObject page = search(new JSONObject().put("limit", 2));
		assertEquals(7, page.getInt("total"));
		assertEquals(2, page.getInt("next_offset"));
		JSONObject current = page.getJSONArray("files").getJSONObject(0);
		assertEquals(ID, current.getString("file_id"));
		assertEquals("sts2.log", current.getString("name"));
		assertEquals("internal", current.getString("root"));
		assertEquals("logs/sts2.log", current.getString("relative_path"));
		assertTrue(current.getBoolean("current"));
		assertEquals(live.length(), current.getLong("size"));
		assertEquals(1700000000000L, current.getLong("modified_ms"));
		assertEquals("2023-11-14T22:13:20Z", current.getString("modified_utc"));
		assertFalse(current.has("lines"));
		assertFalse(page.toString().contains("PRIVATE-BODY"));
		assertFalse(tools.systemPrompt().contains("PRIVATE-BODY"));
		assertFalse(page.toString().contains(temporary.getRoot().getName()));

		Set<String> seen = new HashSet<>();
		while (true) {
			JSONArray files = page.getJSONArray("files");
			assertTrue(files.length() <= 2);
			for (int i = 0; i < files.length(); i++) assertTrue(seen.add(files.getJSONObject(i).getString("file_id")));
			if (page.isNull("next_offset")) break;
			page = search(new JSONObject().put("offset", page.getInt("next_offset")).put("limit", 2));
		}
		assertEquals(new HashSet<>(Arrays.asList(ID, "external/logs/sts2.log", "internal/mods/runtime.LOG.2",
			"internal/instances/profile-A/userdata/logs/godot.2026-11-01.log", "external/background/engine.log.old",
			"external/profile/sentry/reports/report.json", "internal/logs/no-extension")), seen);
		assertEquals(2, search(new JSONObject().put("query", "STS2.LOG")).getInt("total"));
		JSONObject profile = search(new JSONObject().put("query", "PROFILE-a/Userdata"));
		assertEquals(1, profile.getInt("total"));
		assertEquals("godot.2026-11-01.log", profile.getJSONArray("files").getJSONObject(0).getString("name"));
	}

	@Test public void metadataPagesBoundOutputNotFileAvailability() throws Exception {
		String path = ("a".repeat(160) + "/").repeat(3);
		for (int n = 0; n < 65; n++) write(external, path + "test.log." + n, "");
		JSONObject args = new JSONObject().put("query", "test.log").put("limit", 1000);
		Set<String> found = new HashSet<>();
		while (true) {
			JSONObject result = search(args);
			assertEquals(65, result.getInt("total"));
			JSONArray files = result.getJSONArray("files");
			assertTrue(files.length() > 0 && files.length() < 60); // Long paths consume the per-page text budget.
			for (int i = 0; i < files.length(); i++) assertTrue(found.add(files.getJSONObject(i).getString("file_id")));
			if (result.isNull("next_offset")) break;
			args.put("offset", result.getLong("next_offset"));
		}
		assertEquals(65, found.size());
		JSONObject pastEnd = search(args.put("offset", Long.MAX_VALUE));
		assertEquals(0, pastEnd.getJSONArray("files").length());
		assertTrue(pastEnd.isNull("next_offset"));
	}

	@Test public void rotationDeletionAndCreationAreRediscoveredWithinOneSession() throws Exception {
		content("old evidence");
		assertEquals(ID, search(new JSONObject()).getJSONArray("files").getJSONObject(0).getString("file_id"));
		File rotated = new File(live.getParentFile(), "sts2.log.1");
		Files.move(live.toPath(), rotated.toPath());
		assertTrue(call("read_lines", id(ID)).has("error"));
		content("new run");
		write(external, "instances/new-profile/logs/startup", "new profile");
		assertEquals(3, search(new JSONObject()).getInt("total"));
		String rotatedId = search(new JSONObject().put("query", "sts2.log.1")).getJSONArray("files").getJSONObject(0).getString("file_id");
		assertNotEquals(ID, rotatedId);
		assertEquals("new run", line(call("read_lines", id(ID)), 0).getString("text"));
		assertEquals("old evidence", line(call("read_lines", id(rotatedId)), 0).getString("text"));
		Files.delete(rotated.toPath());
		assertEquals(0, search(new JSONObject().put("query", "sts2.log.1")).getInt("total"));
		assertTrue(call("read_lines", id(rotatedId)).has("error"));
	}

	@Test public void emptyEofUnterminatedAppendAndTruncationDoNotInvalidateTheId() throws Exception {
		for (JSONObject empty : Arrays.asList(call("read_lines", id(ID)), search(id(ID).put("keyword", "x")))) {
			assertEquals(0, empty.getJSONArray("lines").length());
			assertEquals(0, empty.getLong("total_lines"));
			assertTrue(empty.getBoolean("eof")); assertTrue(empty.isNull("next_line"));
			assertFalse(empty.getBoolean("truncated"));
		}
		append("one\npartial");
		assertEquals("partial", line(read(2, 100), 0).getString("text"));
		append(" continued\nthree\n"); // The previously unterminated row is still line 2.
		JSONObject grown = read(2, Long.MAX_VALUE);
		assertEquals(3, grown.getLong("total_lines"));
		assertEquals("partial continued", line(grown, 0).getString("text"));
		assertEquals("three", line(grown, 1).getString("text"));
		assertEquals(3, line(search(id(ID).put("keyword", "three")), 0).getLong("line"));
		assertEquals(live.length(), search(new JSONObject()).getJSONArray("files").getJSONObject(0).getLong("size"));
		content("restarted\n");
		JSONObject pastEnd = read(3, 100);
		assertEquals(0, pastEnd.getJSONArray("lines").length());
		assertEquals(1, pastEnd.getLong("total_lines")); assertTrue(pastEnd.getBoolean("eof"));
		assertEquals("restarted", line(call("read_lines", id(ID)), 0).getString("text"));
	}

	@Test public void utf8CrLfAcrossBuffersLoneCrAndEmptyLinesKeepOriginalNumbers() throws Exception {
		content("x".repeat(8191) + "\r\n中文🙂\r\r\n尾行");
		JSONObject page = read(2, 999);
		assertEquals(4, page.getLong("total_lines"));
		assertEquals(2, line(page, 0).getLong("line"));
		assertEquals("中文🙂", line(page, 0).getString("text"));
		assertEquals("", line(page, 1).getString("text"));
		assertEquals("尾行", line(page, 2).getString("text"));
		assertFalse(line(page, 0).getBoolean("line_truncated"));
		content("\n");
		assertEquals(1, read(1, 10).getLong("total_lines"));
	}

	@Test public void literalMatchNearEndOfHugeLogIncludesTheKeywordNotJustThePrefix() throws Exception {
		String keyword = "[.*]崩溃";
		String block = "[info] no error here\n".repeat(1024);
		String prefix = "x".repeat(32766); // Literal crosses a read boundary, with data on both sides.
		try (BufferedWriter writer = Files.newBufferedWriter(live.toPath(), StandardCharsets.UTF_8)) {
			for (int i = 0; i < 256; i++) writer.write(block);
			writer.write(prefix); writer.write(keyword); writer.write("🙂".repeat(3000)); // No final terminator.
		}
		JSONObject page = search(id(ID).put("keyword", keyword));
		assertEquals(1, page.getJSONArray("lines").length());
		JSONObject hit = line(page, 0);
		long last = 256L * 1024 + 1;
		assertEquals(last, hit.getLong("line"));
		assertTrue(hit.getBoolean("match")); assertTrue(hit.getBoolean("line_truncated"));
		String text = hit.getString("text");
		assertTrue(text.contains(keyword)); assertTrue(text.length() <= LogTools.MAX_LINE_CHARS);
		assertEquals(prefix.length() + 1L, hit.getLong("match_column"));
		assertEquals(hit.getLong("match_column"), hit.getLong("column") + text.indexOf(keyword));
		assertFalse(Character.isHighSurrogate(text.charAt(text.length() - 1)));
		assertTrue(page.getBoolean("eof")); assertTrue(page.isNull("next_line"));
		assertEquals(last, page.getLong("total_lines"));
		append("\nnewly appended\n");
		assertEquals("newly appended", line(call("read_lines", id(ID).put("start_line", last + 1)), 0).getString("text"));
	}

	@Test public void readPagesUseLookaheadNotAnEagerTotalOrAStrictEndBound() throws Exception {
		StringBuilder data = new StringBuilder();
		for (int i = 1; i <= 130; i++) data.append(i).append('\n');
		content(data.toString());
		JSONObject first = call("read_lines", id(ID));
		assertEquals(60, first.getJSONArray("lines").length());
		assertTrue(first.getBoolean("truncated")); assertFalse(first.getBoolean("eof"));
		assertTrue(first.isNull("total_lines")); assertEquals(61, first.getLong("next_line"));
		JSONObject next = read(first.getLong("next_line"), 1000);
		assertEquals(61, line(next, 0).getLong("line")); assertEquals(121, next.getLong("next_line"));
		JSONObject last = read(next.getLong("next_line"), 1000);
		assertEquals(10, last.getJSONArray("lines").length());
		assertFalse(last.getBoolean("truncated")); assertTrue(last.getBoolean("eof")); assertTrue(last.isNull("next_line"));
		assertEquals(130, last.getLong("total_lines"));
		JSONObject range = read(1, 2);
		assertFalse(range.getBoolean("truncated")); assertEquals(3, range.getLong("next_line"));
		content("x\n".repeat(60)); // At exactly the page boundary we know whether there is another line.
		assertTrue(call("read_lines", id(ID)).getBoolean("eof"));
	}

	@Test public void grepAndEscapedTextBudgetsResumeWithoutLosingHits() throws Exception {
		content("error ignored\nERROR first\nx\nERROR second\nERROR third");
		JSONObject args = id(ID).put("keyword", "ERROR").put("limit", 1);
		JSONObject page = search(args);
		assertEquals(2, line(page, 0).getLong("line")); assertEquals(3, page.getLong("next_line"));
		page = search(args.put("start_line", page.getLong("next_line")));
		assertEquals(4, line(page, 0).getLong("line")); assertEquals(5, page.getLong("next_line"));
		page = search(args.put("start_line", 5));
		assertEquals(5, line(page, 0).getLong("line")); assertTrue(page.getBoolean("eof"));
		content(("E" + "\u0001".repeat(1199) + "\n").repeat(4));
		for (JSONObject bounded : Arrays.asList(read(1, 100), search(id(ID).put("keyword", "E")))) {
			assertEquals(1, bounded.getJSONArray("lines").length()); assertEquals(2, bounded.getLong("next_line"));
			assertTrue(bounded.toString().length() < 14000);
		}
		assertEquals(2, line(search(id(ID).put("keyword", "E").put("start_line", 2)), 0).getLong("line"));
		content("x".repeat(LogTools.MAX_LINE_CHARS - 1) + "🙂");
		JSONObject prefix = line(read(1, 1), 0);
		assertEquals(LogTools.MAX_LINE_CHARS - 1, prefix.getString("text").length());
		assertTrue(prefix.getBoolean("line_truncated")); assertTrue(prefix.isNull("match_column"));
	}

	@Test public void grepLimitsHitsPerPageNotHowFarItCanRead() throws Exception {
		content("hit\n".repeat(35));
		JSONObject args = id(ID).put("keyword", "hit");
		JSONObject first = search(args);
		assertEquals(10, first.getJSONArray("lines").length());
		JSONObject second = search(args.put("start_line", first.getLong("next_line")).put("limit", 1000));
		assertEquals(20, second.getJSONArray("lines").length());
		assertEquals(11, line(second, 0).getLong("line"));
		JSONObject last = search(args.put("start_line", second.getLong("next_line")));
		assertEquals(5, last.getJSONArray("lines").length()); assertTrue(last.getBoolean("eof"));
	}

	@Test public void appendedBytesDuringScanningAreReadableAndCancellationChecksInsideHugeLines() throws Exception {
		content("initial\n");
		try (Reader growing = new FilterReader(Files.newBufferedReader(live.toPath(), StandardCharsets.UTF_8)) {
			boolean appended;
			@Override public int read(char[] data, int off, int len) throws IOException {
				int count = super.read(data, off, len);
				if (!appended && count > 0) { appended = true; LogToolsTest.this.append("later\n"); }
				return count;
			}
		}) {
			JSONObject page = LogTools.scan(ID, growing, "later", 1, Long.MAX_VALUE, 10, new Cancellation());
			assertEquals(2, line(page, 0).getLong("line"));
		}
		Cancellation token = new Cancellation();
		try (Reader neverEndingLine = new Reader() {
			int chunks;
			@Override public int read(char[] buf, int off, int len) {
				assertTrue("Did not cancel inside a line", ++chunks <= 3);
				if (chunks == 3) token.cancel();
				Arrays.fill(buf, off, off + len, 'x');
				return len;
			}
			@Override public void close() {}
		}) {
			try { LogTools.scan(ID, neverEndingLine, "not present", 1, Long.MAX_VALUE, 10, token); fail("Expected cancellation"); }
			catch (InterruptedIOException expected) { assertEquals("已停止", expected.getMessage()); }
		}
	}

	@Test public void cancellationDuringDiscoveryDoesNotWaitForALogOrAnotherBatch() throws Exception {
		Cancellation token = new Cancellation();
		File cancelOnVisit = new File(internal.getPath()) {
			@Override public File[] listFiles() { token.cancel(); return new File[0]; }
		};
		File unreachable = new File(external.getPath()) {
			@Override public File[] listFiles() { throw new AssertionError("Visited another root after cancellation"); }
		};
		try { new LogTools(cancelOnVisit, unreachable, null).execute("search_logs", new JSONObject(), token); fail("Expected cancellation"); }
		catch (InterruptedIOException expected) { assertEquals("已停止", expected.getMessage()); }
	}

	@Test public void currentLogAndLinksCannotIntroduceFilesOrRawPaths() throws Exception {
		File outside = temporary.newFolder("internal-other");
		File secret = write(outside, "private.log", "SECRET");
		File setting = write(internal, "preferences.json", "INTERNAL SECRET");
		File leafLink = new File(internal, "fake.log");
		Files.createSymbolicLink(leafLink.toPath(), secret.toPath());
		Files.createSymbolicLink(new File(internal, "settings.log").toPath(), setting.toPath());
		Files.createSymbolicLink(new File(internal, "linked-directory").toPath(), outside.toPath());
		File linkedRoot = new File(temporary.getRoot(), "linked-root");
		Files.createSymbolicLink(linkedRoot.toPath(), outside.toPath());
		for (File hint : Arrays.asList(secret, setting, leafLink)) {
			tools = new LogTools(internal, linkedRoot, hint);
			JSONObject list = search(new JSONObject());
			assertEquals(1, list.getInt("total"));
			assertFalse(list.getJSONArray("files").getJSONObject(0).getBoolean("current"));
			for (String notAnId : Arrays.asList(secret.getPath(), live.getPath(), "../internal-other/private.log",
				"internal/../internal-other/private.log", "internal/fake.log", "internal/settings.log", "internal/preferences.json")) {
				assertTrue(call("read_lines", id(notAnId)).has("error"));
			}
		}
		// Swap a listed parent for a directory link, without running discovery again.
		write(outside, "sts2.log", "SECRET");
		Files.move(live.getParentFile().toPath(), new File(internal, "old-logs").toPath());
		Files.createSymbolicLink(live.getParentFile().toPath(), outside.toPath());
		assertTrue(search(id(ID).put("keyword", "SECRET")).has("error"));
	}

	@Test public void leafSwappedToALinkBetweenValidationAndOpenCannotLeakBytes() throws Exception {
		File secret = write(temporary.newFolder("outside"), "private.txt", "SECRET");
		AtomicBoolean swap = new AtomicBoolean();
		File leaf = new File(write(internal, "swapped.log", "allowed").getPath()) {
			@Override public String getCanonicalPath() throws IOException {
				String before = super.getCanonicalPath();
				if (swap.getAndSet(false)) {
					Files.delete(toPath());
					Files.createSymbolicLink(toPath(), secret.toPath());
				}
				return before;
			}
		};
		File root = new File(internal.getPath()) { @Override public File[] listFiles() { return new File[]{leaf}; } };
		tools = new LogTools(root, null, null);
		String listed = search(new JSONObject()).getJSONArray("files").getJSONObject(0).getString("file_id");
		swap.set(true);
		JSONObject reply = call("read_lines", id(listed));
		assertTrue(reply.has("error")); assertFalse(reply.toString().contains("SECRET"));
	}

	@Test public void unrelatedFieldsNeverOverrideTheFileIdOrRejectAValidRead() throws Exception {
		content("first\nsecond");
		File other = write(external, "logs/other.log", "OTHER");
		JSONObject read = call("read_lines", id(ID).put("path", other.getPath()).put("start_line", 2).put("end_line", JSONObject.NULL));
		assertEquals("second", line(read, 0).getString("text"));
		assertEquals(2, search(new JSONObject().put("file_id", JSONObject.NULL).put("keyword", JSONObject.NULL)).getInt("total"));
		for (JSONObject bad : Arrays.asList(id(ID), new JSONObject().put("keyword", "x"),
			new JSONObject().put("offset", -1), id(ID).put("keyword", "x\ny"), id(ID).put("keyword", ""))) {
			assertTrue(call("search_logs", bad).has("error"));
		}
		assertTrue(read(2, 1).has("error")); assertTrue(read(0, 1).has("error"));
		assertTrue(call("read_lines", id(ID).put("start_line", 1.5)).has("error"));
		assertTrue(call("shell", new JSONObject()).has("error"));
	}
}
