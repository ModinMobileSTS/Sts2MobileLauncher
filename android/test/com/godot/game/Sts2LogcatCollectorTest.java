package com.godot.game;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class Sts2LogcatCollectorTest {
	@After public void close() throws Exception { call("stopLocked"); }

	@Test public void errorsAndStopFlushEarlierBufferedLines() throws Exception {
		File log = new File(Files.createTempDirectory(RuntimeEnvironment.getApplication().getCacheDir().toPath(), "collector-").toFile(), "sts2.log");
		append(log, "I ordinary 中文\n");
		append(log, "E failure\n");
		assertEquals("I ordinary 中文\nE failure\n", new String(Files.readAllBytes(log.toPath()), java.nio.charset.StandardCharsets.UTF_8));
		append(log, "I shutdown\n");
		call("stopLocked");
		assertEquals("I ordinary 中文\nE failure\nI shutdown\n", new String(Files.readAllBytes(log.toPath()), java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test public void sizeRotationFlushesOldFileAndKeepsNewTailSeparate() throws Exception {
		File directory = Files.createTempDirectory(RuntimeEnvironment.getApplication().getCacheDir().toPath(), "rotation-").toFile();
		File log = new File(directory, "sts2.log");
		String block = "I " + "x".repeat(4093) + "\n";
		for (int i = 0; i < 4096; i++) append(log, block);
		append(log, "I new-tail\n");
		call("stopLocked");
		assertEquals("I new-tail\n", new String(Files.readAllBytes(log.toPath()), java.nio.charset.StandardCharsets.UTF_8));
		File[] archives = directory.listFiles((dir, name) -> name.startsWith("sts2") && !name.equals("sts2.log"));
		assertNotNull(archives);
		assertEquals(1, archives.length);
		assertEquals(16L * 1024 * 1024, archives[0].length());
		assertEquals(block.repeat(4096), new String(Files.readAllBytes(archives[0].toPath()), java.nio.charset.StandardCharsets.UTF_8));
	}

	private static void append(File file, String line) throws Exception {
		Method append = Sts2LogcatCollector.class.getDeclaredMethod("appendRaw", File.class, String.class);
		append.setAccessible(true);
		append.invoke(null, file, line);
	}

	private static void call(String name) throws Exception {
		java.lang.reflect.Field lock = Sts2LogcatCollector.class.getDeclaredField("LOCK");
		lock.setAccessible(true);
		synchronized (lock.get(null)) {
			Method method = Sts2LogcatCollector.class.getDeclaredMethod(name);
			method.setAccessible(true);
			method.invoke(null);
		}
	}
}
