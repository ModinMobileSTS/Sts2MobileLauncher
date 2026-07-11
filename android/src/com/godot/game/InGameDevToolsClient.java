package com.godot.game;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * File-based bridge to STS2Mobile DevToolsHost (request.json / response.json).
 */
public final class InGameDevToolsClient {
	private static final String TAG = "InGameDevTools";
	private static final long DEFAULT_TIMEOUT_MS = 4000L;
	private static final long POLL_MS = 80L;
	private static final long HOST_MARKER_STALE_MS = 10L * 60L * 1000L;
	private static final String LEGACY_RESPONSE_FILE_NAME = "response.json";
	private static final String HOST_MARKER_FILE_NAME = "host.json";

	// Keep these prefixes stable: the overlay maps them to actionable localized text.
	public static final String ERROR_HOST_UNAVAILABLE = "DEVTOOLS_HOST_UNAVAILABLE";
	public static final String ERROR_REQUEST_TIMEOUT = "DEVTOOLS_REQUEST_TIMEOUT";

	public interface Callback {
		void onResult(JSONObject response);
		void onError(String message);
	}

	private final Context appContext;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "InGameDevToolsClient");
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	private final AtomicBoolean closed = new AtomicBoolean(false);

	public InGameDevToolsClient(Context context) {
		this.appContext = context.getApplicationContext();
	}

	public void close() {
		closed.set(true);
		executor.shutdownNow();
	}

	public void request(String op, JSONObject extra, Callback callback) {
		request(op, extra, DEFAULT_TIMEOUT_MS, callback);
	}

	public void request(String op, JSONObject extra, long timeoutMs, Callback callback) {
		if (closed.get()) {
			if (callback != null) {
				callback.onError("DevTools client closed");
			}
			return;
		}
		executor.execute(() -> {
			try {
				executeRequest(op, extra, timeoutMs, callback);
			} catch (Exception exception) {
				Log.w(TAG, "DevTools request failed", exception);
				postError(callback, exception.getMessage() == null ? "request failed" : exception.getMessage());
			}
		});
	}

	private void executeRequest(String op, JSONObject extra, long timeoutMs, Callback callback) throws Exception {
		JSONObject baseRequest = extra == null ? new JSONObject() : new JSONObject(extra.toString());
		File dir = getDevToolsDir();
		FileBrowserSupport.ensureDirectory(dir);
		File requestFile = new File(dir, "request.json");
		File legacyResponseFile = new File(dir, LEGACY_RESPONSE_FILE_NAME);
		int attempts = canRetry(op) ? 2 : 1;

		for (int attempt = 0; attempt < attempts; attempt++) {
			if (closed.get()) {
				postError(callback, "Cancelled");
				return;
			}

			String id = UUID.randomUUID().toString();
			File responseFile = new File(dir, "response-" + id + ".json");
			deleteQuietly(responseFile);
			// An older host may still write response.json. Poll it as a compatibility
			// fallback, but require the request id before accepting its contents.
			deleteQuietly(legacyResponseFile);

			JSONObject request = new JSONObject(baseRequest.toString());
			request.put("id", id);
			request.put("op", op);
			request.put("response_file", responseFile.getName());
			writeAtomically(requestFile, request.toString());

			long deadline = System.currentTimeMillis() + Math.max(500L, timeoutMs);
			while (System.currentTimeMillis() < deadline) {
				if (closed.get()) {
					postError(callback, "Cancelled");
					return;
				}
				JSONObject response = readMatchingResponse(responseFile, id);
				if (response == null) {
					response = readMatchingResponse(legacyResponseFile, id);
				}
				if (response != null) {
					postResult(callback, response);
					return;
				}
				try {
					Thread.sleep(POLL_MS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					postError(callback, "Interrupted");
					return;
				}
			}

			if (isHostReady(dir) && attempt + 1 < attempts) {
				Log.i(TAG, "DevTools request timed out; retrying once: " + op);
				continue;
			}
			postError(callback, isHostReady(dir)
				? ERROR_REQUEST_TIMEOUT + ": host is ready but did not answer. Try again or restart the game."
				: ERROR_HOST_UNAVAILABLE + ": no current full compatibility-pack DevTools host was detected. The offline bootstrap and older packs do not include the inspector; select/update a full compat pack and restart the game.");
			return;
		}
	}

	private static boolean canRetry(String op) {
		return "ping".equals(op)
			|| "apply_settings".equals(op)
			|| "inspector.roots".equals(op)
			|| "inspector.members".equals(op);
	}

	private static JSONObject readMatchingResponse(File responseFile, String id) {
		if (!responseFile.isFile() || responseFile.length() <= 0) {
			return null;
		}
		String raw = readText(responseFile);
		if (raw == null || raw.trim().isEmpty()) {
			return null;
		}
		try {
			JSONObject response = new JSONObject(raw);
			if (!id.equals(response.optString("id", ""))) {
				return null;
			}
			deleteQuietly(responseFile);
			return response;
		} catch (Exception ignored) {
			// A legacy host can expose response.json while it is being replaced.
			// Keep polling instead of turning a transient partial read into a false
			// "not connected" error.
			return null;
		}
	}

	private static boolean isHostReady(File dir) {
		File marker = new File(dir, HOST_MARKER_FILE_NAME);
		if (!marker.isFile() || marker.length() <= 0) {
			return false;
		}
		long age = Math.max(0L, System.currentTimeMillis() - marker.lastModified());
		if (age > HOST_MARKER_STALE_MS) {
			return false;
		}
		try {
			JSONObject state = new JSONObject(readText(marker));
			return state.optBoolean("ready", false);
		} catch (Exception ignored) {
			return false;
		}
	}

	public void applySettings(String... keys) {
		try {
			JSONObject extra = new JSONObject();
			if (keys != null && keys.length > 0) {
				JSONArray array = new JSONArray();
				for (String key : keys) {
					array.put(key);
				}
				extra.put("keys", array);
			}
			request("apply_settings", extra, null);
		} catch (Exception exception) {
			Log.w(TAG, "applySettings enqueue failed", exception);
		}
	}

	private void postResult(Callback callback, JSONObject response) {
		if (callback == null) {
			return;
		}
		mainHandler.post(() -> callback.onResult(response));
	}

	private void postError(Callback callback, String message) {
		if (callback == null) {
			return;
		}
		mainHandler.post(() -> callback.onError(message));
	}

	private File getDevToolsDir() {
		return new File(new File(appContext.getFilesDir(), "launcher"), "devtools");
	}

	private static void writeAtomically(File file, String content) throws Exception {
		File parent = file.getParentFile();
		FileBrowserSupport.ensureDirectory(parent);
		File tmp = new File(parent, file.getName() + ".tmp");
		try (FileOutputStream outputStream = new FileOutputStream(tmp, false)) {
			outputStream.write(content.getBytes(StandardCharsets.UTF_8));
			outputStream.flush();
		}
		if (file.exists() && !file.delete()) {
			Log.w(TAG, "Unable to delete old request file");
		}
		if (!tmp.renameTo(file)) {
			// Fallback copy
			try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
				outputStream.write(content.getBytes(StandardCharsets.UTF_8));
			}
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
		}
	}

	private static String readText(File file) {
		try {
			byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
			return new String(bytes, StandardCharsets.UTF_8);
		} catch (Exception exception) {
			return null;
		}
	}

	private static void deleteQuietly(File file) {
		if (file != null && file.exists()) {
			//noinspection ResultOfMethodCallIgnored
			file.delete();
		}
	}
}
