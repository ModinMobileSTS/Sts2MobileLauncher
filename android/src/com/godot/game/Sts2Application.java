package com.godot.game;

import android.app.Application;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;

import java.io.File;
import java.util.Locale;

import cat.ereza.customactivityoncrash.CustomActivityOnCrash;
import cat.ereza.customactivityoncrash.config.CaocConfig;

public class Sts2Application extends Application {
	@Override
	public void onCreate() {
		super.onCreate();
		AndroidTempDirectory.configure(this, "Sts2Application");

		CaocConfig.Builder.create()
			.backgroundMode(CaocConfig.BACKGROUND_MODE_SHOW_CUSTOM)
			.enabled(true)
			.showErrorDetails(true)
			.showRestartButton(true)
			.logErrorOnRestart(true)
			.trackActivities(true)
			.minTimeBetweenCrashesMs(2000)
			.restartActivity(GameSettingsActivity.class)
			.errorActivity(Sts2CrashActivity.class)
			.customCrashDataCollector(new Sts2CrashDataCollector(this))
			.apply();
	}

	private static final class Sts2CrashDataCollector implements CustomActivityOnCrash.CustomCrashDataCollector {
		private final String packageName;
		private final String versionName;
		private final int versionCode;
		private final String buildType;
		private final String flavor;
		private final boolean debug;
		private final String selectedRenderer;
		private final String selectedRendererArgs;
		private final String locale;
		private final String supportedAbis;
		private final String filesDir;
		private final String cacheDir;
		private final String modsDir;
		private final String startupMarker;

		Sts2CrashDataCollector(Application application) {
			packageName = BuildConfig.APPLICATION_ID;
			versionName = BuildConfig.VERSION_NAME;
			versionCode = BuildConfig.VERSION_CODE;
			buildType = BuildConfig.BUILD_TYPE;
			flavor = BuildConfig.FLAVOR;
			debug = BuildConfig.DEBUG;
			selectedRenderer = RendererPreference.getSelectedRenderer(application);
			selectedRendererArgs = formatArgs(RendererPreference.buildGodotCommandLineArgs(selectedRenderer));
			locale = Locale.getDefault().toLanguageTag();
			supportedAbis = TextUtils.join(", ", Build.SUPPORTED_ABIS);
			filesDir = safePath(application.getFilesDir());
			cacheDir = safePath(application.getCacheDir());
			modsDir = safePath(new File(application.getFilesDir(), "mods"));
			startupMarker = StartupHealthTracker.describePendingLaunch(application);
		}

		@Override
		public String onCrash() {
			try {
				Thread thread = Thread.currentThread();
				Runtime runtime = Runtime.getRuntime();
				String processName = getProcessNameCompat(packageName);

				StringBuilder builder = new StringBuilder();
				appendLine(builder, "Package", packageName);
				appendLine(builder, "Version", versionName + " (" + versionCode + ")");
				appendLine(builder, "Build", flavor + "/" + buildType + " debug=" + debug);
				appendLine(builder, "Process", processName);
				appendLine(builder, "Crashing thread", thread.getName() + " (id=" + thread.getId() + ")");
				appendLine(builder, "PID/TID", Process.myPid() + "/" + Process.myTid());
				appendLine(builder, "Renderer preference", selectedRenderer);
				appendLine(builder, "Renderer command line", selectedRendererArgs);
				appendLine(builder, "Locale", locale);
				appendLine(builder, "Supported ABIs", supportedAbis);
				appendLine(builder, "Files dir", filesDir);
				appendLine(builder, "Cache dir", cacheDir);
				appendLine(builder, "Mods dir", modsDir);
				appendLine(builder, "Startup watchdog", startupMarker);
				appendLine(builder, "Java VM", System.getProperty("java.vm.name", "unknown") + " " + System.getProperty("java.vm.version", ""));
				appendLine(builder, "Uptime", SystemClock.uptimeMillis() + " ms");
				appendLine(builder, "Heap max", formatBytes(runtime.maxMemory()));
				appendLine(builder, "Heap total", formatBytes(runtime.totalMemory()));
				appendLine(builder, "Heap free", formatBytes(runtime.freeMemory()));
				appendLine(builder, "Native heap allocated", formatBytes(Debug.getNativeHeapAllocatedSize()));
				appendLine(builder, "Native heap free", formatBytes(Debug.getNativeHeapFreeSize()));
				return builder.toString().trim();
			} catch (Throwable throwable) {
				return "Failed to collect additional crash data: " + throwable;
			}
		}

		private static String formatArgs(String[] args) {
			if (args == null || args.length == 0) {
				return "(default)";
			}
			return TextUtils.join(" ", args);
		}

		private static String safePath(File file) {
			return file == null ? "(unavailable)" : file.getAbsolutePath();
		}

		private static void appendLine(StringBuilder builder, String key, String value) {
			builder.append(key).append(": ").append(value).append('\n');
		}

		private static String formatBytes(long bytes) {
			return String.format(Locale.US, "%.1f MiB", bytes / 1048576.0d);
		}

		private static String getProcessNameCompat(String fallback) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				String processName = Application.getProcessName();
				if (processName != null && !processName.isEmpty()) {
					return processName;
				}
			}
			return fallback;
		}
	}
}
