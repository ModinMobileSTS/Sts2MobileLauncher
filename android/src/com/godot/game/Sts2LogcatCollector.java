package com.godot.game;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class Sts2LogcatCollector {
	private static final String TAG = "Sts2Logcat";
	private static final String LOG_FILE_NAME = "sts2.log";
	private static final int MODE_FULL = 0;
	private static final int MODE_MAIN_ONLY = 1;
	private static final long MAX_LOG_BYTES = 16L * 1024L * 1024L;
	private static final int MAX_STS2_LOG_FILES = 5;
	private static final long FAST_EXIT_RETRY_WINDOW_MS = 3000L;

	private static final Object LOCK = new Object();

	private static Process process;
	private static File activeLogsDir;
	private static String activeLogLevel = ExtraSettingsRepository.LOG_LEVEL_INFO;
	private static int activeGeneration;

	private Sts2LogcatCollector() {
	}

	public static void start(Context context) {
		if (context == null) {
			return;
		}
		start(context, false, false, false);
	}

	public static void startForSelectedProfile(Context context) {
		if (context == null) {
			return;
		}
		start(context, false, true, false);
	}

	public static void startNewLaunchForSelectedProfile(Context context) {
		if (context == null) {
			return;
		}
		start(context, true, true, true);
	}

	private static void start(Context context, boolean archivePreviousLog, boolean useLaunchLogLevel, boolean forceRestart) {
		if (context == null) {
			return;
		}
		Context appContext = context.getApplicationContext() == null ? context : context.getApplicationContext();
		if (!isMainProcess(appContext)) {
			return;
		}
		File targetLogsDir = getGlobalLogsDir(appContext);
		String logLevel = resolveLogLevel(appContext, useLaunchLogLevel);
		synchronized (LOCK) {
			if (ExtraSettingsRepository.LOG_LEVEL_OFF.equals(logLevel)) {
				stopLocked();
				return;
			}
			if (!forceRestart && isCollectorAliveLocked() && isSameDirectory(activeLogsDir, targetLogsDir) && logLevel.equals(activeLogLevel)) {
				return;
			}
			stopLocked();
			startLocked(appContext, targetLogsDir, MODE_FULL, logLevel, archivePreviousLog, true);
		}
	}

	private static File getGlobalLogsDir(Context context) {
		return new File(context.getFilesDir(), "logs");
	}

	private static void startLocked(Context context, File logsDir, int mode, String logLevel, boolean archivePreviousLog, boolean usePidFilter) {
		File logFile = new File(logsDir, LOG_FILE_NAME);
		List<String> command = buildCommand(mode, logLevel, usePidFilter);
		try {
			FileBrowserSupport.ensureDirectory(logsDir);
			if (archivePreviousLog) {
				archiveCurrentLogIfNeeded(logsDir, logFile);
			} else {
				archiveIfOversized(logsDir, logFile);
			}
			appendCollectorHeader(logFile, context, logsDir, command, mode, logLevel);
			ProcessBuilder processBuilder = new ProcessBuilder(command);
			processBuilder.redirectErrorStream(true);
			Process startedProcess = processBuilder.start();
			process = startedProcess;
			activeLogsDir = logsDir;
			activeLogLevel = logLevel;
			int generation = ++activeGeneration;
			long startedAt = SystemClock.uptimeMillis();
			startPipeThread(startedProcess, logFile, minPriorityRank(logLevel), generation);
			startWaiterThread(context, logsDir, logFile, startedProcess, mode, logLevel, startedAt, generation, usePidFilter);
			Log.i(TAG, "Capturing Android logcat to " + logFile.getAbsolutePath() + " mode=" + describeMode(mode) + " logLevel=" + logLevel);
		} catch (Exception exception) {
			appendCollectorLine(logFile, 'E', "failed to start logcat: " + exception);
			Log.w(TAG, "Unable to start Android logcat collector.", exception);
			process = null;
			activeLogsDir = null;
		}
	}

	private static List<String> buildCommand(int mode, String logLevel, boolean usePidFilter) {
		List<String> command = new ArrayList<>();
		command.add("logcat");
		if (usePidFilter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			command.add("--pid");
			command.add(String.valueOf(android.os.Process.myPid()));
		}
		command.add("-v");
		command.add("threadtime");
		command.add("-b");
		command.add("main");
		if (mode == MODE_FULL) {
			command.add("-b");
			command.add("system");
			command.add("-b");
			command.add("crash");
		}
		command.add("*:" + toLogcatFilterPriority(logLevel));
		return command;
	}

	private static void startPipeThread(Process startedProcess, File logFile, int minPriority, int generation) {
		Thread thread = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(startedProcess.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (!isActiveCollector(startedProcess, generation)) {
						break;
					}
					String compactLine = compactLogcatLine(line, minPriority);
					if (compactLine != null) {
						appendCompactLineIfActive(logFile, compactLine, startedProcess, generation);
					}
				}
			} catch (IOException exception) {
				if (!hasExited(startedProcess)) {
					appendCollectorLine(logFile, 'W', "logcat diagnostic pipe failed: " + exception);
				}
			}
		}, "Sts2LogcatCollectorPipe");
		thread.setDaemon(true);
		thread.start();
	}

	private static String compactLogcatLine(String line, int minPriority) {
		if (line == null) {
			return null;
		}
		int messageIndex = line.indexOf(": ");
		if (messageIndex < 0) {
			return shouldKeepPriority('I', minPriority) ? "I LOGCAT " + line + "\n" : null;
		}
		String header = line.substring(0, messageIndex).trim();
		String message = line.substring(messageIndex + 2);
		String[] parts = header.split("\\s+", 6);
		if (parts.length < 6 || parts[4].isEmpty()) {
			return shouldKeepPriority('I', minPriority) ? "I LOGCAT " + line + "\n" : null;
		}
		char priority = Character.toUpperCase(parts[4].charAt(0));
		if (!shouldKeepPriority(priority, minPriority)) {
			return null;
		}
		String tag = parts[5].trim();
		if (tag.isEmpty()) {
			tag = "LOGCAT";
		}
		return priority + " " + tag + " " + message + "\n";
	}

	private static void appendCompactLineIfActive(File logFile, String line, Process expectedProcess, int generation) throws IOException {
		synchronized (LOCK) {
			if (process != expectedProcess || activeGeneration != generation) {
				return;
			}
			appendRawLocked(logFile, line);
		}
	}

	private static void startWaiterThread(Context context, File logsDir, File logFile, Process startedProcess, int mode, String logLevel, long startedAt, int generation, boolean usePidFilter) {
		Thread thread = new Thread(() -> {
			int exitCode;
			try {
				exitCode = startedProcess.waitFor();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				exitCode = Integer.MIN_VALUE;
			}
			long elapsed = SystemClock.uptimeMillis() - startedAt;
			appendCollectorLine(logFile, 'I', "logcat exited code=" + exitCode + " elapsed_ms=" + elapsed + " mode=" + describeMode(mode));
			synchronized (LOCK) {
				if (process != startedProcess || activeGeneration != generation) {
					return;
				}
				process = null;
				if (elapsed <= FAST_EXIT_RETRY_WINDOW_MS && usePidFilter) {
					appendCollectorLine(logFile, 'W', "retrying without --pid filter");
					startLocked(context, logsDir, mode, logLevel, false, false);
				} else if (mode == MODE_FULL && elapsed <= FAST_EXIT_RETRY_WINDOW_MS) {
					appendCollectorLine(logFile, 'W', "retrying with main log buffer only");
					startLocked(context, logsDir, MODE_MAIN_ONLY, logLevel, false, false);
				}
			}
		}, "Sts2LogcatCollectorWaiter");
		thread.setDaemon(true);
		thread.start();
	}

	private static void archiveIfOversized(File logsDir, File logFile) {
		if (logFile.isFile() && logFile.length() >= MAX_LOG_BYTES) {
			archiveCurrentLogIfNeeded(logsDir, logFile);
		}
	}

	private static void archiveCurrentLogIfNeeded(File logsDir, File logFile) {
		if (!logFile.isFile() || logFile.length() <= 0L) {
			return;
		}
		File archivedLogFile = buildArchivedLogFile(logsDir, logFile.lastModified());
		if (logFile.renameTo(archivedLogFile)) {
			Log.i(TAG, "Archived previous sts2.log to: " + archivedLogFile.getAbsolutePath());
			pruneArchivedLogs(logsDir);
		} else {
			Log.w(TAG, "Unable to archive previous sts2.log: " + logFile.getAbsolutePath());
		}
	}

	private static File buildArchivedLogFile(File logsDir, long timestampMillis) {
		String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH.mm.ss", Locale.US).format(new Date(Math.max(0L, timestampMillis)));
		File candidate = new File(logsDir, "sts2" + timestamp + ".log");
		int suffix = 1;
		while (candidate.exists()) {
			candidate = new File(logsDir, "sts2" + timestamp + "-" + suffix + ".log");
			suffix++;
		}
		return candidate;
	}

	private static void pruneArchivedLogs(File logsDir) {
		File[] archivedLogs = logsDir.listFiles((dir, name) -> name != null && name.matches("sts2\\d{4}-\\d{2}-\\d{2}T\\d{2}\\.\\d{2}\\.\\d{2}(?:-\\d+)?\\.log"));
		if (archivedLogs == null || archivedLogs.length <= MAX_STS2_LOG_FILES - 1) {
			return;
		}
		Arrays.sort(archivedLogs, Comparator.comparingLong(File::lastModified).reversed());
		for (int i = MAX_STS2_LOG_FILES - 1; i < archivedLogs.length; i++) {
			if (!archivedLogs[i].delete()) {
				Log.w(TAG, "Unable to delete old archived sts2 log: " + archivedLogs[i].getAbsolutePath());
			}
		}
	}

	private static void stopLocked() {
		if (process == null) {
			return;
		}
		activeGeneration++;
		try {
			process.destroy();
		} catch (Exception ignored) {
		}
		process = null;
		activeLogsDir = null;
	}

	private static boolean isCollectorAliveLocked() {
		return process != null && !hasExited(process);
	}

	private static boolean isActiveCollector(Process expectedProcess, int generation) {
		synchronized (LOCK) {
			return process == expectedProcess && activeGeneration == generation;
		}
	}

	private static boolean hasExited(Process candidate) {
		try {
			candidate.exitValue();
			return true;
		} catch (IllegalThreadStateException exception) {
			return false;
		} catch (Exception exception) {
			return true;
		}
	}

	private static void appendCollectorHeader(File logFile, Context context, File logsDir, List<String> command, int mode, String logLevel) {
		appendCollectorLine(logFile, 'I', "collector started time=" + formatDate(System.currentTimeMillis())
			+ " package=" + context.getPackageName()
			+ " process=" + getProcessNameCompat(context)
			+ " pid=" + android.os.Process.myPid()
			+ " logs_dir=" + logsDir.getAbsolutePath()
			+ " mode=" + describeMode(mode)
			+ " log_level=" + logLevel
			+ " command=" + joinCommand(command));
		appendCollectorLine(logFile, 'I', "format=compact fields=level tag message archive=current sts2.log archived sts2YYYY-MM-DDTHH.mm.ss.log");
		appendCollectorLine(logFile, 'I', "note=Android only exposes logcat entries visible to this app UID; full device logs still require adb logcat");
	}

	private static void appendCollectorLine(File logFile, char priority, String line) {
		appendRaw(logFile, Character.toUpperCase(priority) + " " + TAG + " " + line + "\n");
	}

	private static void appendRaw(File logFile, String content) {
		try {
			File parent = logFile.getParentFile();
			if (parent != null) {
				FileBrowserSupport.ensureDirectory(parent);
			}
			synchronized (LOCK) {
				appendRawLocked(logFile, content);
			}
		} catch (Exception exception) {
			Log.w(TAG, "Unable to append collector metadata to " + logFile.getAbsolutePath(), exception);
		}
	}

	private static void appendRawLocked(File logFile, String content) throws IOException {
		File logsDir = logFile.getParentFile();
		if (logsDir != null) {
			archiveIfOversized(logsDir, logFile);
		}
		try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(logFile, true))) {
			outputStream.write(content.getBytes(StandardCharsets.UTF_8));
			outputStream.flush();
		}
	}

	private static boolean isMainProcess(Context context) {
		String processName = getProcessNameCompat(context);
		String packageName = context.getPackageName();
		return processName == null || processName.isEmpty() || packageName.equals(processName);
	}

	private static String getProcessNameCompat(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			String processName = Application.getProcessName();
			if (processName != null && !processName.isEmpty()) {
				return processName;
			}
		}
		String procName = readProcCmdline();
		if (procName != null && !procName.isEmpty()) {
			return procName;
		}
		return context == null ? "" : context.getPackageName();
	}

	private static String readProcCmdline() {
		File file = new File("/proc/self/cmdline");
		if (!file.isFile()) {
			return "";
		}
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file));
			 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[128];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				for (int i = 0; i < read; i++) {
					if (buffer[i] == 0) {
						return new String(outputStream.toByteArray(), StandardCharsets.UTF_8).trim();
					}
					outputStream.write(buffer[i]);
				}
			}
			return new String(outputStream.toByteArray(), StandardCharsets.UTF_8).trim();
		} catch (Exception exception) {
			return "";
		}
	}

	private static boolean isSameDirectory(File left, File right) {
		if (left == null || right == null) {
			return false;
		}
		try {
			return left.getCanonicalPath().equals(right.getCanonicalPath());
		} catch (IOException exception) {
			return left.getAbsolutePath().equals(right.getAbsolutePath());
		}
	}

	private static String resolveLogLevel(Context context, boolean useLaunchSettings) {
		if (useLaunchSettings) {
			try {
				return new ExtraSettingsRepository(context).getLogLevelForLaunch();
			} catch (Exception ignored) {
			}
		}
		return ExtraSettingsRepository.normalizeLogLevel(ExtraSettingsPreferences.getLogLevel(context, ExtraSettingsRepository.LOG_LEVEL_INFO));
	}

	private static int minPriorityRank(String logLevel) {
		String normalized = ExtraSettingsRepository.normalizeLogLevel(logLevel);
		if (ExtraSettingsRepository.LOG_LEVEL_OFF.equals(normalized)) {
			return priorityRank('S');
		}
		if (ExtraSettingsRepository.LOG_LEVEL_VERY_DEBUG.equals(normalized)) {
			return priorityRank('V');
		}
		if (ExtraSettingsRepository.LOG_LEVEL_DEBUG.equals(normalized)) {
			return priorityRank('D');
		}
		return priorityRank('I');
	}

	private static String toLogcatFilterPriority(String logLevel) {
		String normalized = ExtraSettingsRepository.normalizeLogLevel(logLevel);
		if (ExtraSettingsRepository.LOG_LEVEL_OFF.equals(normalized)) {
			return "S";
		}
		if (ExtraSettingsRepository.LOG_LEVEL_VERY_DEBUG.equals(normalized)) {
			return "V";
		}
		if (ExtraSettingsRepository.LOG_LEVEL_DEBUG.equals(normalized)) {
			return "D";
		}
		return "I";
	}

	private static boolean shouldKeepPriority(char priority, int minPriority) {
		return priorityRank(priority) >= minPriority;
	}

	private static int priorityRank(char priority) {
		switch (Character.toUpperCase(priority)) {
			case 'V':
				return 0;
			case 'D':
				return 1;
			case 'I':
				return 2;
			case 'W':
				return 3;
			case 'E':
				return 4;
			case 'F':
				return 5;
			case 'S':
				return 6;
			default:
				return 2;
		}
	}

	private static String describeMode(int mode) {
		return mode == MODE_MAIN_ONLY ? "main" : "main,system,crash";
	}

	private static String formatDate(long timeMillis) {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(timeMillis));
	}

	private static String joinCommand(List<String> command) {
		StringBuilder builder = new StringBuilder();
		for (String item : command) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(item == null ? "" : item.replace("\n", " "));
		}
		return builder.toString();
	}
}
