package com.godot.game;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight file tail for in-game log panel. Prefer godot.log, fallback sts2.log.
 */
public final class InGameLogTailer {
	public enum Level {
		VERBOSE, DEBUG, INFO, WARN, ERROR
	}

	public interface Listener {
		void onLines(List<String> lines, boolean reset);
	}

	private static final String TAG = "InGameLogTailer";
	private static final int MAX_BUFFER_LINES = 2000;
	private static final long POLL_MS = 800L;
	private static final long MAX_INITIAL_BYTES = 256L * 1024L;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final List<String> buffer = new ArrayList<>();
	private final EnumSet<Level> enabledLevels = EnumSet.allOf(Level.class);

	private File file;
	private long filePointer;
	private String textFilter = "";
	private boolean excludeFilter;
	private Listener listener;
	private final Runnable pollRunnable = new Runnable() {
		@Override
		public void run() {
			if (!running.get()) {
				return;
			}
			pollOnce();
			handler.postDelayed(this, POLL_MS);
		}
	};

	public void setListener(Listener listener) {
		this.listener = listener;
	}

	public void setFile(File file) {
		this.file = file;
		resetReadState();
		reloadAll();
	}

	public File getFile() {
		return file;
	}

	public void setTextFilter(String filter, boolean exclude) {
		this.textFilter = filter == null ? "" : filter.trim();
		this.excludeFilter = exclude;
		emitFiltered(true);
	}

	public void setLevelEnabled(Level level, boolean enabled) {
		if (enabled) {
			enabledLevels.add(level);
		} else {
			enabledLevels.remove(level);
		}
		emitFiltered(true);
	}

	public void setLevels(EnumSet<Level> levels) {
		enabledLevels.clear();
		if (levels != null) {
			enabledLevels.addAll(levels);
		}
		emitFiltered(true);
	}

	public void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		handler.post(pollRunnable);
	}

	public void stop() {
		running.set(false);
		handler.removeCallbacks(pollRunnable);
	}

	public void clearView() {
		buffer.clear();
		emitFiltered(true);
	}

	private void resetReadState() {
		filePointer = 0L;
		buffer.clear();
	}

	private void reloadAll() {
		resetReadState();
		if (file == null || !file.isFile()) {
			emitFiltered(true);
			return;
		}
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			long length = raf.length();
			if (length > MAX_INITIAL_BYTES) {
				filePointer = length - MAX_INITIAL_BYTES;
				raf.seek(filePointer);
				// skip partial first line
				raf.readLine();
				filePointer = raf.getFilePointer();
			} else {
				raf.seek(0L);
				filePointer = 0L;
			}
			readAvailable(raf);
		} catch (Exception exception) {
			Log.w(TAG, "reload failed: " + (file == null ? "null" : file.getAbsolutePath()), exception);
		}
		emitFiltered(true);
	}

	private void pollOnce() {
		if (file == null || !file.isFile()) {
			return;
		}
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			long length = raf.length();
			if (length < filePointer) {
				// truncated / rotated
				reloadAll();
				return;
			}
			if (length == filePointer) {
				return;
			}
			raf.seek(filePointer);
			List<String> added = readAvailable(raf);
			if (!added.isEmpty()) {
				emitFiltered(false);
			}
		} catch (Exception exception) {
			Log.w(TAG, "poll failed", exception);
		}
	}

	private List<String> readAvailable(RandomAccessFile raf) throws Exception {
		List<String> added = new ArrayList<>();
		String line;
		while ((line = raf.readLine()) != null) {
			// RandomAccessFile reads ISO-8859-1; re-decode as UTF-8 best-effort
			String decoded = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
			buffer.add(decoded);
			added.add(decoded);
			while (buffer.size() > MAX_BUFFER_LINES) {
				buffer.remove(0);
			}
		}
		filePointer = raf.getFilePointer();
		return added;
	}

	private void emitFiltered(boolean reset) {
		if (listener == null) {
			return;
		}
		List<String> filtered = new ArrayList<>();
		for (String line : buffer) {
			if (passes(line)) {
				filtered.add(line);
			}
		}
		listener.onLines(filtered, reset);
	}

	private boolean passes(String line) {
		if (line == null) {
			return false;
		}
		Level level = detectLevel(line);
		if (!enabledLevels.contains(level)) {
			return false;
		}
		if (textFilter.isEmpty()) {
			return true;
		}
		boolean contains = line.toLowerCase(Locale.ROOT).contains(textFilter.toLowerCase(Locale.ROOT));
		return excludeFilter != contains;
	}

	public static Level detectLevel(String line) {
		if (line == null || line.isEmpty()) {
			return Level.INFO;
		}
		String upper = line.toUpperCase(Locale.ROOT);
		// sts2.log compact: "I tag message" or "E tag ..."
		if (line.length() >= 2 && line.charAt(1) == ' ') {
			switch (line.charAt(0)) {
				case 'V':
				case 'v':
					return Level.VERBOSE;
				case 'D':
				case 'd':
					return Level.DEBUG;
				case 'I':
				case 'i':
					return Level.INFO;
				case 'W':
				case 'w':
					return Level.WARN;
				case 'E':
				case 'e':
				case 'F':
				case 'f':
					return Level.ERROR;
				default:
					break;
			}
		}
		if (upper.contains(" FATAL") || upper.contains(" ERROR") || upper.contains("EXCEPTION") || upper.contains(" E ")) {
			return Level.ERROR;
		}
		if (upper.contains(" WARNING") || upper.contains(" WARN") || upper.contains(" W ")) {
			return Level.WARN;
		}
		if (upper.contains(" DEBUG") || upper.contains(" D ")) {
			return Level.DEBUG;
		}
		if (upper.contains(" VERBOSE") || upper.contains(" V ")) {
			return Level.VERBOSE;
		}
		return Level.INFO;
	}
}
