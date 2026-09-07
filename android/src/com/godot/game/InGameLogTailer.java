package com.godot.game;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.system.Os;
import android.system.StructStat;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** Bounded, background file tail for the in-game log panel. */
public final class InGameLogTailer {
	public enum Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

	public interface Listener {
		void onLines(List<String> lines, boolean reset);
	}

	private static final String TAG = "InGameLogTailer";
	private static final int MAX_BUFFER_LINES = 2000;
	private static final long POLL_MS = 800L;
	private static final int MAX_BATCH_BYTES = 256 * 1024;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final EnumSet<Level> enabledLevels = EnumSet.allOf(Level.class);
	private File file;
	private String textFilter = "";
	private boolean excludeFilter;
	private Listener listener;
	private volatile Filter filter = new Filter("", false, enabledLevels);
	private volatile ReadSession session;

	public void setListener(Listener listener) { this.listener = listener; }
	public File getFile() { return file; }

	public void setFile(File file) {
		this.file = file;
		if (session != null) {
			stop();
			start();
		}
	}

	public void setTextFilter(String text, boolean exclude) {
		textFilter = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
		excludeFilter = exclude;
		refreshFilter();
	}

	public void setLevelEnabled(Level level, boolean enabled) {
		if (enabled) enabledLevels.add(level); else enabledLevels.remove(level);
		refreshFilter();
	}

	public void setLevels(EnumSet<Level> levels) {
		enabledLevels.clear();
		if (levels != null) enabledLevels.addAll(levels);
		refreshFilter();
	}

	private void refreshFilter() {
		filter = new Filter(textFilter, excludeFilter, enabledLevels);
		ReadSession current = session;
		if (current != null) {
			current.worker.removeCallbacks(current.refilter);
			current.worker.post(current.refilter);
		}
	}

	public void start() {
		if (session != null) return;
		ReadSession current = new ReadSession(file);
		session = current;
		current.worker.post(current.poll);
	}

	public void stop() {
		ReadSession current = session;
		session = null;
		if (current != null) {
			current.worker.removeCallbacksAndMessages(null);
			mainHandler.removeCallbacks(current.deliver);
			current.thread.quitSafely();
		}
	}

	public void clearView() {
		ReadSession current = session;
		if (current != null) current.worker.post(() -> {
			current.lines.clear();
			current.partial.reset();
			current.partialLine = null;
			current.emit(true);
		});
	}

	private static final class Filter {
		final String text;
		final boolean exclude;
		final EnumSet<Level> levels;
		Filter(String text, boolean exclude, EnumSet<Level> levels) {
			this.text = text;
			this.exclude = exclude;
			this.levels = levels.clone();
		}
		boolean passes(Line line) {
			return levels.contains(line.level) && (text.isEmpty()
				|| exclude != line.text.toLowerCase(Locale.ROOT).contains(text));
		}
	}

	private static final class Line {
		final String text;
		final Level level;
		Line(String text) { this.text = text; level = detectLevel(text); }
	}

	private static final class Delivery {
		final Filter filter;
		final List<String> lines;
		final boolean reset;
		Delivery(Filter filter, List<String> lines, boolean reset) {
			this.filter = filter;
			this.lines = lines;
			this.reset = reset;
		}
	}

	private final class ReadSession {
		final File source;
		final HandlerThread thread = new HandlerThread("Sts2LogTail", android.os.Process.THREAD_PRIORITY_BACKGROUND);
		final Handler worker;
		final ArrayDeque<Line> lines = new ArrayDeque<>(MAX_BUFFER_LINES);
		final byte[] bytes = new byte[32 * 1024];
		final ByteArrayOutputStream partial = new ByteArrayOutputStream();
		Line partialLine;
		long position;
		long inode, device;
		boolean initialized, skipPartial, skipLf;
		volatile Delivery pendingDelivery;
		final Runnable deliver = () -> {
			Delivery delivery = pendingDelivery;
			if (session == this && delivery != null && delivery.filter == filter && listener != null) {
				listener.onLines(delivery.lines, delivery.reset);
			}
		};
		final Runnable refilter = () -> emit(true);
		final Runnable poll = new Runnable() {
			@Override public void run() {
				if (session != ReadSession.this) return;
				boolean backlog = readBatch();
				if (session == ReadSession.this) worker.postDelayed(this, backlog ? 16L : POLL_MS);
			}
		};

		ReadSession(File source) {
			this.source = source;
			thread.start();
			worker = new Handler(thread.getLooper());
		}

		boolean readBatch() {
			if (source == null || !source.isFile()) return false;
			try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
				StructStat stat = Os.fstat(input.getFD());
				long length = input.length();
				boolean reset = !initialized || inode != stat.st_ino || device != stat.st_dev || length < position;
				if (reset) {
					lines.clear();
					partial.reset();
					position = Math.max(0L, length - MAX_BATCH_BYTES);
					skipPartial = position > 0L;
					skipLf = false;
					inode = stat.st_ino;
					device = stat.st_dev;
					initialized = true;
				}
				input.seek(position);
				int budget = (int) Math.min(MAX_BATCH_BYTES, length - position);
				boolean changed = reset;
				while (budget > 0 && session == this) {
					int count = input.read(bytes, 0, Math.min(bytes.length, budget));
					if (count < 0) break;
					changed = true;
					position += count;
					budget -= count;
					int start = 0;
					for (int i = 0; i < count; i++) {
						byte value = bytes[i];
						if (skipLf && value == '\n') { skipLf = false; start = i + 1; continue; }
						skipLf = false;
						if (value != '\n' && value != '\r') continue;
						if (!skipPartial) {
							partial.write(bytes, start, i - start);
							lines.addLast(new Line(partial.toString(StandardCharsets.UTF_8.name())));
							if (lines.size() > MAX_BUFFER_LINES) lines.removeFirst();
						}
						partial.reset();
						skipPartial = false;
						skipLf = value == '\r';
						start = i + 1;
					}
					if (!skipPartial && start < count) partial.write(bytes, start, count - start);
				}
				if (changed) {
					partialLine = null;
					if (partial.size() > 0) {
						try {
							String text = StandardCharsets.UTF_8.newDecoder()
								.decode(java.nio.ByteBuffer.wrap(partial.toByteArray())).toString();
							partialLine = new Line(text);
						} catch (java.nio.charset.CharacterCodingException incomplete) {
							// Wait for the remaining UTF-8 bytes instead of showing replacement glyphs.
						}
					}
					emit(reset);
				}
				return position < length;
			} catch (Exception exception) {
				Log.w(TAG, "Log tail failed: " + source, exception);
				return false;
			}
		}

		void emit(boolean reset) {
			if (session != this) return;
			Filter current = filter;
			List<String> visible = new ArrayList<>(Math.min(MAX_BUFFER_LINES, lines.size() + 1));
			for (Line line : lines) if (current.passes(line)) visible.add(line.text);
			if (partialLine != null && current.passes(partialLine)) {
				if (visible.size() == MAX_BUFFER_LINES) visible.remove(0);
				visible.add(partialLine.text);
			}
			pendingDelivery = new Delivery(current, visible, reset);
			// A busy UI needs only the latest snapshot, not a backlog of render tasks.
			mainHandler.removeCallbacks(deliver);
			mainHandler.post(deliver);
		}
	}

	public static Level detectLevel(String line) {
		if (line == null || line.isEmpty()) return Level.INFO;
		if (line.length() >= 2 && line.charAt(1) == ' ') {
			switch (Character.toUpperCase(line.charAt(0))) {
				case 'V': return Level.VERBOSE;
				case 'D': return Level.DEBUG;
				case 'I': return Level.INFO;
				case 'W': return Level.WARN;
				case 'E':
				case 'F': return Level.ERROR;
				default: break;
			}
		}
		String upper = line.toUpperCase(Locale.ROOT);
		if (upper.contains(" FATAL") || upper.contains(" ERROR") || upper.contains("EXCEPTION") || upper.contains(" E ")) return Level.ERROR;
		if (upper.contains(" WARNING") || upper.contains(" WARN") || upper.contains(" W ")) return Level.WARN;
		if (upper.contains(" DEBUG") || upper.contains(" D ")) return Level.DEBUG;
		if (upper.contains(" VERBOSE") || upper.contains(" V ")) return Level.VERBOSE;
		return Level.INFO;
	}
}
