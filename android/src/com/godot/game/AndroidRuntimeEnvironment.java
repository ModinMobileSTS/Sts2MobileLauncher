package com.godot.game;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Configures writable process-wide native/.NET directories before Godot/Mono starts.
 *
 * <p>Android user records may resolve HOME to /data. The packaged Mono derives
 * ApplicationData from HOME/.config and caches both environment and special-folder
 * lookups, so set the native HOME before runtime initialization. Temp configuration
 * remains independent: a failed home setup must not break Harmony/MonoMod.</p>
 */
public final class AndroidRuntimeEnvironment {
	private static final String DEFAULT_TAG = "Sts2RuntimeEnv";
	private static boolean homeConfigured;
	private static String configuredPath;

	private AndroidRuntimeEnvironment() {
	}

	public static synchronized String configure(Context context, String tag) {
		String logTag = tag == null || tag.isEmpty() ? DEFAULT_TAG : tag;
		if (homeConfigured && configuredPath != null) {
			return configuredPath;
		}
		File root;
		try {
			Context appContext = context == null ? null : context.getApplicationContext();
			root = appContext == null ? null : appContext.getFilesDir();
		} catch (Throwable throwable) {
			Log.w(logTag, "Unable to resolve Android runtime directories", throwable);
			return configuredPath;
		}
		if (root == null) {
			Log.w(logTag, "Unable to configure Android runtime environment: filesDir unavailable");
			return configuredPath;
		}

		if (!homeConfigured) {
			try {
				prepareWritableDirectory(new File(root, ".config"), logTag);
				String home = root.getAbsolutePath();
				android.system.Os.setenv("HOME", home, true);
				homeConfigured = true;
				Log.i(logTag, "Configured Android HOME: " + home);
			} catch (Throwable throwable) {
				Log.w(logTag, "Unable to configure Android HOME", throwable);
			}
		}
		if (configuredPath != null) {
			return configuredPath;
		}
		try {
			File tempDir = new File(root, "tmp");
			prepareWritableDirectory(tempDir, logTag);
			String path = tempDir.getAbsolutePath();
			android.system.Os.setenv("TMPDIR", path, true);
			android.system.Os.setenv("TMP", path, true);
			android.system.Os.setenv("TEMP", path, true);
			System.setProperty("java.io.tmpdir", path);
			configuredPath = path;
			Log.i(logTag, "Configured Android temp directory: " + path);
			return path;
		} catch (Throwable throwable) {
			Log.w(logTag, "Unable to configure Android temp directory", throwable);
			return null;
		}
	}

	private static void prepareWritableDirectory(File directory, String logTag) throws IOException {
		if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
			throw new IOException("Unable to create runtime directory: " + directory.getAbsolutePath());
		}
		File probe = File.createTempFile(".sts2_env_", ".tmp", directory);
		try (FileOutputStream outputStream = new FileOutputStream(probe)) {
			outputStream.write(0);
		} finally {
			if (probe.exists() && !probe.delete()) {
				Log.v(logTag, "Unable to delete runtime directory probe: " + probe.getAbsolutePath());
			}
		}
	}
}
