package com.godot.game;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.system.Os;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class,
		shadows = AndroidLinuxTestShadow.class)
public class AndroidRuntimeEnvironmentTest {
	private String previousTemp;

	@Before public void resetEnvironment() throws Exception {
		previousTemp = System.getProperty("java.io.tmpdir");
		resetConfiguration();
	}

	@After public void restoreEnvironment() throws Exception {
		if (previousTemp == null) {
			System.clearProperty("java.io.tmpdir");
		} else {
			System.setProperty("java.io.tmpdir", previousTemp);
		}
		resetConfiguration();
	}

	private static void resetConfiguration() throws Exception {
		AndroidLinuxTestShadow.environment.clear();
		Field home = AndroidRuntimeEnvironment.class.getDeclaredField("homeConfigured");
		home.setAccessible(true);
		home.setBoolean(null, false);
		Field temp = AndroidRuntimeEnvironment.class.getDeclaredField("configuredPath");
		temp.setAccessible(true);
		temp.set(null, null);
	}

	@Test public void inheritedSystemHomeCannotEscapePrivateModData() throws Exception {
		Context context = privateContext();
		AndroidLinuxTestShadow.environment.put("HOME", "/data");
		AndroidRuntimeEnvironment.configure(context, "RuntimeEnvironmentTest");
		File log = writeModLog(context);
		File temp = File.createTempFile("native-consumer-", ".tmp", new File(Os.getenv("TMPDIR")));
		Files.write(temp.toPath(), "temp-data".getBytes(StandardCharsets.UTF_8));
		AndroidRuntimeEnvironment.configure(context, "RuntimeEnvironmentTest");
		assertEquals("merchant-init", new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));
		assertEquals("temp-data", new String(Files.readAllBytes(temp.toPath()), StandardCharsets.UTF_8));
	}

	@Test public void blockedHomePreservesTempAndCanRetryWithoutReplacingFiles() throws Exception {
		Context context = privateContext();
		File blocked = new File(context.getFilesDir(), ".config");
		Files.write(blocked.toPath(), "keep-existing-file".getBytes(StandardCharsets.UTF_8));
		AndroidLinuxTestShadow.environment.put("HOME", "/data");
		AndroidRuntimeEnvironment.configure(context, "RuntimeEnvironmentTest");
		assertEquals("keep-existing-file", new String(Files.readAllBytes(blocked.toPath()), StandardCharsets.UTF_8));
		File temp = File.createTempFile("independent-temp-", ".tmp", new File(Os.getenv("TMPDIR")));
		Files.write(temp.toPath(), "temp-still-writable".getBytes(StandardCharsets.UTF_8));
		assertTrue(blocked.delete());
		AndroidRuntimeEnvironment.configure(context, "RuntimeEnvironmentTest");
		assertTrue(blocked.isDirectory());
		File log = writeModLog(context);
		assertEquals("merchant-init", new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));
		assertEquals("temp-still-writable", new String(Files.readAllBytes(temp.toPath()), StandardCharsets.UTF_8));
	}

	private static File writeModLog(Context context) throws Exception {
		// Matches the packaged Mono ApplicationData contract and ColorlessRun.Logger.
		File home = new File(Os.getenv("HOME"));
		assertEquals(context.getFilesDir().getCanonicalFile(), home.getCanonicalFile());
		File log = new File(home, ".config/SlayTheSpire2/logs/mod_log.txt");
		Files.createDirectories(log.getParentFile().toPath());
		Files.write(log.toPath(), "merchant-init".getBytes(StandardCharsets.UTF_8));
		return log;
	}

	private static Context privateContext() throws Exception {
		Context application = RuntimeEnvironment.getApplication();
		File files = Files.createTempDirectory(application.getCacheDir().toPath(), "runtime-env-").toFile();
		return new ContextWrapper(application) {
			@Override public Context getApplicationContext() { return this; }
			@Override public File getFilesDir() { return files; }
		};
	}

}
