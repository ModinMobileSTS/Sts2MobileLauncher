package com.godot.game;

import android.os.Handler;
import android.os.Looper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = InGameLogTailerTest.InodeLinux.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class InGameLogTailerTest {
	private InGameLogTailer tailer;
	private File file;
	private List<String> visible;

	@Before public void setup() throws Exception {
		file = File.createTempFile("tail-", ".log", RuntimeEnvironment.getApplication().getCacheDir());
		InodeLinux.inode = 1L;
		visible = new ArrayList<>();
		tailer = new InGameLogTailer();
		tailer.setListener((lines, reset) -> {
			assertEquals(Looper.getMainLooper(), Looper.myLooper());
			visible = new ArrayList<>(lines);
		});
		tailer.setFile(file);
	}

	@After public void close() { tailer.stop(); }

	@Test public void utf8AndCrLfCanSpanReadBatches() throws Exception {
		Files.write(file.toPath(), ("I 开始\n").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		tailer.start();
		tick();
		assertEquals(List.of("I 开始"), visible);
		byte[] line = "W 中文完成\r\n".getBytes(StandardCharsets.UTF_8);
		Files.write(file.toPath(), java.util.Arrays.copyOf(line, 3), StandardOpenOption.APPEND);
		tick();
		assertEquals(List.of("I 开始"), visible);
		Files.write(file.toPath(), java.util.Arrays.copyOfRange(line, 3, line.length - 1), StandardOpenOption.APPEND);
		tick();
		Files.write(file.toPath(), new byte[] {'\n'}, StandardOpenOption.APPEND);
		tick();
		assertEquals(List.of("I 开始", "W 中文完成"), visible);
	}

	@Test public void unfinishedTailRemainsVisibleAndContinuesAsOneLine() throws Exception {
		Files.write(file.toPath(), "E 崩溃末行".getBytes(StandardCharsets.UTF_8));
		tailer.start();
		tick();
		assertEquals(List.of("E 崩溃末行"), visible);
		Files.write(file.toPath(), " continued\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
		tick();
		assertEquals(List.of("E 崩溃末行 continued"), visible);
	}

	@Test public void burstsRetainOrderedTailWithoutSplittingLines() throws Exception {
		tailer.start();
		tick();
		StringBuilder burst = new StringBuilder();
		for (int i = 0; i < 5000; i++) burst.append("I ").append(i).append(' ').append("x".repeat(200)).append('\n');
		Files.write(file.toPath(), (burst).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		for (int i = 0; i < 8; i++) tick();
		assertEquals(2000, visible.size());
		assertEquals("I 3000 " + "x".repeat(200), visible.get(0));
		assertEquals("I 4999 " + "x".repeat(200), visible.get(1999));
	}

	@Test public void rotationFilterAndSourceSwitchDiscardStaleDelivery() throws Exception {
		Files.write(file.toPath(), ("I first\nW keep\nW drop\n").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		tailer.start();
		tick();
		tailer.setLevels(EnumSet.of(InGameLogTailer.Level.WARN));
		tailer.setTextFilter("drop", true);
		tick();
		assertEquals(List.of("W keep"), visible);
		File replacement = File.createTempFile("rotate-", ".log", file.getParentFile());
		Files.write(replacement.toPath(), ("I other\nW next\nW drop\n").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		InodeLinux.inode++;
		tick();
		assertEquals(List.of("W next"), visible);
		Files.write(file.toPath(), ("W old\n").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		shadowOf(worker().getLooper()).idleFor(Duration.ofMillis(801));
		File other = File.createTempFile("source-", ".log", file.getParentFile());
		Files.write(other.toPath(), ("W new\n").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		tailer.setFile(other);
		tick();
		assertEquals(List.of("W new"), visible);
		tailer.clearView();
		tick();
		assertTrue(visible.isEmpty());
	}

	private Handler worker() throws Exception {
		Field session = InGameLogTailer.class.getDeclaredField("session");
		session.setAccessible(true);
		Object state = session.get(tailer);
		Field worker = state.getClass().getDeclaredField("worker");
		worker.setAccessible(true);
		return (Handler) worker.get(state);
	}

	private void tick() throws Exception {
		shadowOf(worker().getLooper()).idleFor(Duration.ofMillis(801));
		shadowOf(Looper.getMainLooper()).idle();
	}

	// Robolectric 4.15 returns inode=0 for every fstat. Supply the missing OS
	// identity transition while exercising real files and the public tailer API.
	@org.robolectric.annotation.Implements(className = "libcore.io.Linux", isInAndroidSdk = false)
	public static class InodeLinux extends org.robolectric.shadows.ShadowLinux {
		static volatile long inode;
		@org.robolectric.annotation.Implementation
		@Override protected android.system.StructStat fstat(java.io.FileDescriptor descriptor) {
			return new android.system.StructStat(1, inode, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		}
	}
}
