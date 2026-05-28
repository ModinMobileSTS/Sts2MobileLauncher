package com.godot.game;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Configures a process-wide writable temp directory for native/.NET code.
 *
 * <p>Some Android devices do not provide /tmp. .NET's Path.GetTempPath() and
 * MonoMod/Harmony use the native TMPDIR/TMP/TEMP environment variables; if they
 * fall back to /tmp, HarmonySharedState fails before any compatibility patches
 * can be applied. Configure the variables from Java before Godot/Mono starts.</p>
 */
public final class AndroidTempDirectory {
	private static final String DEFAULT_TAG = "Sts2TempDir";
	private static volatile boolean configured;
	private static volatile String configuredPath;

	private AndroidTempDirectory() {
	}

	public static String configure(Context context, String tag) {
		String logTag = tag == null || tag.isEmpty() ? DEFAULT_TAG : tag;
		if (configured && configuredPath != null && !configuredPath.isEmpty()) {
			return configuredPath;
		}
		try {
			Context appContext = context == null ? null : context.getApplicationContext();
			File root = appContext == null ? null : appContext.getFilesDir();
			if (root == null) {
				Log.w(logTag, "Unable to configure Android temp directory: filesDir unavailable");
				return null;
			}
			File tempDir = new File(root, "tmp");
			if (!tempDir.isDirectory() && !tempDir.mkdirs() && !tempDir.isDirectory()) {
				Log.w(logTag, "Unable to create Android temp directory: " + tempDir.getAbsolutePath());
				return null;
			}
			File probe = new File(tempDir, ".sts2_temp_probe");
			try (FileOutputStream outputStream = new FileOutputStream(probe)) {
				outputStream.write("ok".getBytes(StandardCharsets.UTF_8));
			}
			if (probe.exists() && !probe.delete()) {
				Log.v(logTag, "Unable to delete temp probe immediately: " + probe.getAbsolutePath());
			}

			String path = tempDir.getAbsolutePath();
			android.system.Os.setenv("TMPDIR", path, true);
			android.system.Os.setenv("TMP", path, true);
			android.system.Os.setenv("TEMP", path, true);
			System.setProperty("java.io.tmpdir", path);
			configuredPath = path;
			configured = true;
			Log.i(logTag, "Configured Android temp directory: " + path);
			return path;
		} catch (Throwable throwable) {
			Log.w(logTag, "Unable to configure Android temp directory", throwable);
			return null;
		}
	}
}
