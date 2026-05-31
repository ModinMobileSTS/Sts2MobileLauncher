package com.godot.game.steam.cloud;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class SteamCleanExitTracker {
	private static final String MARKER_FILE_NAME = "expected_clean_game_exit.json";
	private static final long MARKER_MAX_AGE_MS = 30L * 60L * 1000L;

	private SteamCleanExitTracker() {
	}

	public static void mark(Context context, String source) {
		try {
			File file = markerFile(context);
			File parent = file.getParentFile();
			if (parent != null && !parent.isDirectory()) {
				parent.mkdirs();
			}
			JSONObject json = new JSONObject();
			json.put("schema", 1);
			json.put("source", source == null ? "" : source);
			json.put("created_at_ms", System.currentTimeMillis());
			try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
				output.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
			}
		} catch (Exception ignored) {
		}
	}

	public static boolean consumeIfRecent(Context context) {
		File file = markerFile(context);
		if (!file.isFile()) {
			return false;
		}
		try {
			JSONObject json = new JSONObject(readText(file));
			long createdAt = json.optLong("created_at_ms", file.lastModified());
			return System.currentTimeMillis() - createdAt <= MARKER_MAX_AGE_MS;
		} catch (Exception ignored) {
			return false;
		} finally {
			file.delete();
		}
	}

	private static File markerFile(Context context) {
		return new File(new File(context.getFilesDir(), "launcher"), MARKER_FILE_NAME);
	}

	private static String readText(File file) throws Exception {
		try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
			 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) != -1) {
				output.write(buffer, 0, read);
			}
			return new String(output.toByteArray(), StandardCharsets.UTF_8);
		}
	}
}
