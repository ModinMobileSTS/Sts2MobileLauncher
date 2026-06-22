package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class SteamWorkshopDownloadCleaner {
	private static final String TAG = "Sts2WorkshopCleanup";
	private static final String PREFERENCES_NAME = "sts2_steam_workshop_cleanup";
	private static final String KEY_LAST_DAILY_CLEANUP_AT_MS = "last_daily_cleanup_at_ms";
	private static final long DAILY_CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L;
	private static final AtomicBoolean dailyCleanupRunning = new AtomicBoolean(false);

	private SteamWorkshopDownloadCleaner() {
	}

	static void maybeRunDailyCleanup(Context context) {
		if (context == null) {
			return;
		}
		Context appContext = context.getApplicationContext();
		SharedPreferences preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
		long now = System.currentTimeMillis();
		long lastCleanupAtMs = preferences.getLong(KEY_LAST_DAILY_CLEANUP_AT_MS, 0L);
		if (lastCleanupAtMs > 0L && now >= lastCleanupAtMs && now - lastCleanupAtMs < DAILY_CLEANUP_INTERVAL_MS) {
			return;
		}
		if (!dailyCleanupRunning.compareAndSet(false, true)) {
			return;
		}
		new Thread(() -> {
			try {
				cleanupDownloadsDirectory(appContext);
			} catch (Exception exception) {
				Log.w(TAG, "Unable to clean Steam Workshop downloads directory.", exception);
			} finally {
				preferences.edit().putLong(KEY_LAST_DAILY_CLEANUP_AT_MS, System.currentTimeMillis()).apply();
				dailyCleanupRunning.set(false);
			}
		}, "sts2-workshop-download-cleanup").start();
	}

	static void deleteImportedDownloadDirectory(Context context, File outputDir) {
		if (context == null || outputDir == null) {
			return;
		}
		try {
			if (!outputDir.exists() && !isSymbolicLink(outputDir)) {
				return;
			}
			File downloadsRoot = getDownloadsRoot(context.getApplicationContext());
			if (!isDirectDownloadEntry(downloadsRoot, outputDir)) {
				Log.w(TAG, "Skipping Steam Workshop cleanup outside downloads root: " + outputDir.getAbsolutePath());
				return;
			}
			deleteRecursively(outputDir, downloadsRoot);
		} catch (Exception exception) {
			Log.w(TAG, "Unable to delete imported Steam Workshop download: " + outputDir.getAbsolutePath(), exception);
		}
	}

	private static void cleanupDownloadsDirectory(Context context) throws IOException {
		File downloadsRoot = getDownloadsRoot(context);
		if (!downloadsRoot.isDirectory()) {
			return;
		}
		File[] children = downloadsRoot.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			try {
				if (isDirectDownloadEntry(downloadsRoot, child)) {
					deleteRecursively(child, downloadsRoot);
				}
			} catch (Exception exception) {
				Log.w(TAG, "Unable to delete Steam Workshop download entry: " + child.getAbsolutePath(), exception);
			}
		}
	}

	private static File getDownloadsRoot(Context context) {
		return new File(new File(context.getFilesDir(), "workshop"), "downloads");
	}

	private static boolean isDirectDownloadEntry(File downloadsRoot, File entry) throws IOException {
		File parent = entry.getParentFile();
		return parent != null && parent.getCanonicalFile().equals(downloadsRoot.getCanonicalFile());
	}

	private static boolean isPathUnderRoot(File root, File file) throws IOException {
		String rootPath = root.getCanonicalPath();
		String filePath = file.getCanonicalPath();
		return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
	}

	private static void deleteRecursively(File file, File downloadsRoot) throws IOException {
		if (file == null || (!file.exists() && !isSymbolicLink(file))) {
			return;
		}
		if (isSymbolicLink(file)) {
			deleteFile(file);
			return;
		}
		if (!isPathUnderRoot(downloadsRoot, file)) {
			throw new IOException("Refusing to delete outside Steam Workshop downloads root: " + file.getAbsolutePath());
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child, downloadsRoot);
				}
			}
		}
		deleteFile(file);
	}

	private static void deleteFile(File file) throws IOException {
		if (!file.delete() && (file.exists() || isSymbolicLink(file))) {
			throw new IOException("Unable to delete: " + file.getAbsolutePath());
		}
	}

	private static boolean isSymbolicLink(File file) {
		try {
			File parent = file.getParentFile();
			File fileInCanonicalParent = parent == null ? file : new File(parent.getCanonicalFile(), file.getName());
			return !fileInCanonicalParent.getCanonicalFile().equals(fileInCanonicalParent.getAbsoluteFile());
		} catch (IOException ignored) {
			return false;
		}
	}
}
