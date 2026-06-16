/**************************************************************************/
/*  GodotApp.java                                                         */
/**************************************************************************/
/*                         This file is part of:                          */
/*                             GODOT ENGINE                               */
/*                        https://godotengine.org                         */
/**************************************************************************/
/* Copyright (c) 2014-present Godot Engine contributors (see AUTHORS.md). */
/* Copyright (c) 2007-2014 Juan Linietsky, Ariel Manzur.                  */
/*                                                                        */
/* Permission is hereby granted, free of charge, to any person obtaining  */
/* a copy of this software and associated documentation files (the        */
/* "Software"), to deal in the Software without restriction, including    */
/* without limitation the rights to use, copy, modify, merge, publish,    */
/* distribute, sublicense, and/or sell copies of the Software, and to     */
/* permit persons to whom the Software is furnished to do so, subject to  */
/* the following conditions:                                              */
/*                                                                        */
/* The above copyright notice and this permission notice shall be         */
/* included in all copies or substantial portions of the Software.        */
/*                                                                        */
/* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,        */
/* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF     */
/* MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. */
/* IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY   */
/* CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,   */
/* TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE      */
/* SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.                 */
/**************************************************************************/

package com.godot.game;

import org.godotengine.godot.Godot;
import org.godotengine.godot.GodotActivity;
import org.godotengine.godot.GodotLib;
import org.godotengine.godot.GodotRenderView;

import org.fmod.FMOD;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONObject;

import androidx.activity.EdgeToEdge;
import androidx.core.splashscreen.SplashScreen;

import com.godot.game.steam.cloud.SteamCleanExitTracker;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Template activity for Godot Android builds.
 * Feel free to extend and modify this class for your custom logic.
 */
public class GodotApp extends GodotActivity {
	private static final String TAG = "Sts2Re";
	private static final String SETTINGS_FILE_NAME = "settings.save";
	private static final String SOFT_KEYBOARD_SHORTCUT_SETTING_KEY = "android_volume_up_soft_keyboard";
	private static final String PCK_FILE_NAME = PayloadManager.PCK_FILE_NAME;
	private static final int MAX_GODOT_LOG_FILES = 5;

	private static volatile GodotApp currentInstance;
	private static volatile boolean currentWindowFocused;
	private static volatile boolean currentResumed;
	private File gameDir;
	private android.view.OrientationEventListener orientationEventListener;

	static {
		Log.i(TAG, "DIAG GodotApp static init begin versionCode=" + BuildConfig.VERSION_CODE + " versionName=" + BuildConfig.VERSION_NAME + " flavor=" + BuildConfig.FLAVOR);
		try {
			System.loadLibrary("fmod");
			System.loadLibrary("fmodstudio");
		} catch (UnsatisfiedLinkError error) {
			Log.w(TAG, "Unable to preload FMOD libraries; continuing so settings/diagnostics can still open.", error);
		}
		try {
			System.loadLibrary("monomod_android_libc_shim");
			Log.i(TAG, "Loaded MonoMod Android libc shim.");
		} catch (UnsatisfiedLinkError error) {
			Log.w(TAG, "Unable to preload MonoMod Android libc shim; Harmony may fail on ROMs requiring executable-page patch fallback.", error);
		}
		if (BuildConfig.FLAVOR.equals("mono")) {
			try {
				Log.v(TAG, "Loading System.Security.Cryptography.Native.Android library");
				System.loadLibrary("System.Security.Cryptography.Native.Android");
			} catch (UnsatisfiedLinkError error) {
				Log.e(TAG, "Unable to load System.Security.Cryptography.Native.Android library", error);
			}
		}
		Log.i(TAG, "DIAG GodotApp static init end");
	}

	private final Runnable updateWindowAppearance = () -> {
		Godot godot = getGodot();
		if (godot != null) {
			godot.enableImmersiveMode(godot.isInImmersiveMode(), true);
			godot.enableEdgeToEdge(godot.isInEdgeToEdgeMode(), true);
			godot.setSystemBarsAppearance();
		}
	};

	private void dispatchImmediateGodotFocusChange(boolean hasFocus) {
		Godot godot = getGodot();
		if (godot == null) {
			Log.v("GODOT", "Skipping immediate focus dispatch because Godot instance is null.");
			return;
		}
		GodotRenderView renderView = godot.getRenderView();
		Log.v("GODOT", "Dispatching immediate Godot focus change: hasFocus=" + hasFocus + ", hasRenderView=" + (renderView != null));
		Runnable runnable = hasFocus ? GodotLib::focusin : GodotLib::focusout;
		if (renderView != null) {
			renderView.queueOnRenderThread(runnable);
		} else {
			runnable.run();
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		Log.i(TAG, "DIAG GodotApp.onCreate begin savedInstanceState=" + (savedInstanceState != null) + " intent=" + describeIntent(getIntent()));
		AndroidTempDirectory.configure(this, TAG);
		Sts2LogcatCollector.startForSelectedProfile(this);
		gameDir = new LaunchProfileManager(this).getSelectedGameDir();
		logGodotAppLaunchSnapshot("onCreate_after_logcat_start");
		SplashScreen.installSplashScreen(this);
		if (!ExtraSettingsPreferences.isFirstRunSetupCompleted(this)) {
			// Even when redirecting away from the game activity, Android requires every
			// Activity.onCreate() override to call through to super.onCreate(). Skipping
			// it triggers SuperNotCalledException during ActivityThread.performLaunchActivity.
			super.onCreate(savedInstanceState);
			Log.i("GODOT", "First-run setup is incomplete; redirecting GodotApp launch to GameSettingsActivity.");
			Intent settingsIntent = new Intent(this, GameSettingsActivity.class);
			settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
			startActivity(settingsIntent);
			finish();
			return;
		}
		StartupHealthTracker.markGameLaunchStarted(this);
		try {
			new ExtraSettingsRepository(this).ensureAppDirectories();
		} catch (Throwable throwable) {
			Log.w(TAG, "Unable to normalize extra-settings directories before launch.", throwable);
		}
		applyConfiguredScreenOrientation();
		EdgeToEdge.enable(this);
		try {
			FMOD.init(this);
		} catch (Throwable throwable) {
			Log.w(TAG, "FMOD init failed; continuing so launcher diagnostics remain available.", throwable);
		}
		boolean launchPrepared = getIntent().getBooleanExtra("launch_prepared", false);
		Log.e(TAG, "DIAG_FORCE GodotApp.onCreate launch_prepared=" + launchPrepared + " forcing_pre_super_prepare=true");
		ensureLaunchPreparedBeforeGodot(launchPrepared);
		logGodotAppLaunchSnapshot("onCreate_before_super");
		super.onCreate(savedInstanceState);
		applyConfiguredScreenOrientation();
		currentInstance = this;
		currentWindowFocused = hasWindowFocus();
		logGodotAppLaunchSnapshot("onCreate_after_super");
		HighRefreshRateController.applyWithRetries(this, getGodot(), "onCreate_after_super");
		Log.i(TAG, "DIAG GodotApp.onCreate end windowFocused=" + currentWindowFocused);
	}

	private String describeIntent(Intent intent) {
		if (intent == null) {
			return "null";
		}
		Bundle extras = intent.getExtras();
		return "action=" + intent.getAction()
			+ "; data=" + intent.getDataString()
			+ "; flags=0x" + Integer.toHexString(intent.getFlags())
			+ "; extras=" + (extras == null ? "null" : extras.keySet().toString());
	}

	private void logGodotAppLaunchSnapshot(String phase) {
		try {
			LaunchProfileManager manager = new LaunchProfileManager(this);
			LaunchProfileManager.LaunchProfile profile = manager.getSelectedProfile();
			LaunchProfileManager.GamePayload payload = profile == null ? null : profile.payload;
			CompatPackManager compatManager = new CompatPackManager(this);
			CompatPackManager.CompatPack pack = null;
			try {
				pack = compatManager.getSelectedPack();
			} catch (Exception exception) {
				Log.w(TAG, "DIAG GodotApp selected pack lookup failed phase=" + phase, exception);
			}
			File publishDir = new File(getFilesDir(), ".godot/mono/publish/arm64");
			Log.i(TAG,
				"DIAG GodotApp snapshot phase=" + phase
					+ " filesDir=" + getFilesDir().getAbsolutePath()
					+ " selectedProfileId=" + manager.getSelectedProfileId()
					+ " profile=" + describeProfile(profile)
					+ " payload=" + describePayload(payload)
					+ " compatEnabled=" + compatManager.isCompatPackEnabled()
					+ " selectedCompatId=" + manager.getSelectedCompatPackId()
					+ " selectedCompatTargetId=" + manager.getSelectedCompatTargetId()
					+ " selectedPack=" + describeCompatPack(pack)
					+ " gameDir=" + describeFile(manager.getSelectedGameDir())
					+ " selectedInstanceJson=" + describeFile(new File(getFilesDir(), "launcher/selected_instance.json"))
					+ " selectedCompatJson=" + describeFile(new File(getFilesDir(), "launcher/selected_compat_pack.json"))
					+ " stagedOverlay=" + describeFile(new File(getFilesDir(), "port_compat.pck"))
					+ " publishDir=" + describeFile(publishDir));
			logPublishSnapshot(publishDir, phase);
			logSmallFileText(new File(getFilesDir(), "launcher/selected_instance.json"), phase, "selected_instance_json");
			logSmallFileText(new File(getFilesDir(), "launcher/selected_compat_pack.json"), phase, "selected_compat_json");
		} catch (Exception exception) {
			Log.w(TAG, "DIAG GodotApp snapshot failed phase=" + phase, exception);
		}
	}

	private String describeProfile(LaunchProfileManager.LaunchProfile profile) {
		if (profile == null) {
			return "null";
		}
		return "id=" + safe(profile.id)
			+ ",payloadId=" + safe(profile.payloadId)
			+ ",compatPackId=" + safe(profile.compatPackId)
			+ ",compatTargetId=" + safe(profile.compatTargetId)
			+ ",saveMode=" + safe(profile.saveMode)
			+ ",modsMode=" + safe(profile.modsMode)
			+ ",ready=" + profile.ready
			+ ",dir=" + describeFile(profile.dir);
	}

	private String describePayload(LaunchProfileManager.GamePayload payload) {
		if (payload == null) {
			return "null";
		}
		return "id=" + safe(payload.id)
			+ ",version=" + safe(payload.version)
			+ ",commit=" + safe(payload.commit)
			+ ",ready=" + payload.ready
			+ ",dllSha=" + shortText(payload.sts2DllSha256)
			+ ",pckSha=" + shortText(payload.pckSha256)
			+ ",gameDir=" + describeFile(payload.gameDir);
	}

	private String describeCompatPack(CompatPackManager.CompatPack pack) {
		if (pack == null) {
			return "null";
		}
		return "id=" + safe(pack.packId)
			+ ",targetId=" + safe(pack.targetId)
			+ ",version=" + safe(pack.compatVersion)
			+ ",target=" + safe(pack.targetLabel())
			+ ",ready=" + pack.ready
			+ ",dir=" + describeFile(pack.dir)
			+ ",dll=" + describeFile(pack.dllFile)
			+ ",overlay=" + describeFile(pack.overlayPckFile);
	}

	private void logPublishSnapshot(File publishDir, String phase) {
		String[] importantNames = new String[] {
			"STS2Mobile.dll",
			"0Harmony.dll",
			"MonoMod.Core.dll",
			"MonoMod.Utils.dll",
			"MonoMod.Iced.dll",
			"GodotSharp.dll",
			"sts2.dll",
			"sts2.deps.json",
			"sts2.runtimeconfig.json"
		};
		StringBuilder builder = new StringBuilder("DIAG GodotApp publish_snapshot phase=").append(phase).append(" dir=").append(describeFile(publishDir));
		for (String name : importantNames) {
			builder.append("; ").append(name).append("=").append(describeFile(new File(publishDir, name)));
		}
		builder.append("; entries=").append(listDirectorySample(publishDir, 40));
		Log.i(TAG, builder.toString());
	}

	private void logSmallFileText(File file, String phase, String label) {
		try {
			if (file == null || !file.isFile()) {
				Log.i(TAG, "DIAG GodotApp " + label + " phase=" + phase + " file=" + describeFile(file));
				return;
			}
			String text = readTextFile(file);
			if (text.length() > 4000) {
				text = text.substring(0, 4000) + "...<truncated>";
			}
			Log.i(TAG, "DIAG GodotApp " + label + " phase=" + phase + " file=" + describeFile(file) + " text=" + text.replace('\n', ' '));
		} catch (Exception exception) {
			Log.w(TAG, "DIAG GodotApp failed to read " + label + " phase=" + phase, exception);
		}
	}

	private String listDirectorySample(File dir, int limit) {
		if (dir == null) {
			return "null";
		}
		File[] files = dir.listFiles();
		if (files == null) {
			return "list_null";
		}
		List<String> names = new ArrayList<>();
		for (File file : files) {
			String suffix = file.isDirectory() ? "/" : "(" + file.length() + ")";
			names.add(file.getName() + suffix);
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);
		if (names.size() > limit) {
			return names.subList(0, limit).toString() + " ... total=" + names.size();
		}
		return names.toString();
	}

	private String describeFile(File file) {
		if (file == null) {
			return "null";
		}
		return file.getAbsolutePath()
			+ "{exists=" + file.exists()
			+ ",file=" + file.isFile()
			+ ",dir=" + file.isDirectory()
			+ ",bytes=" + (file.isFile() ? file.length() : -1L)
			+ ",mtime=" + (file.exists() ? file.lastModified() : -1L)
			+ "}";
	}

	private String safe(String value) {
		return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
	}

	private String shortText(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		return value.substring(0, Math.min(12, value.length()));
	}

	@Override
	public List<String> getCommandLine() {
		List<String> superCommandLine = super.getCommandLine();
		Log.i(TAG, "DIAG GodotApp.getCommandLine super=" + superCommandLine);
		List<String> commandLine = new ArrayList<>(superCommandLine);
		Collections.addAll(commandLine, RendererPreference.buildGodotCommandLineArgs(this));
		writePerformanceOverlayLaunchFlag();
		appendGodotLogFileCommandLineArgs(commandLine);
		appendSts2PlatformCommandLineArgs(commandLine);
		appendSts2LogLevelCommandLineArgs(commandLine);
		appendAndroidDisplayCommandLineArgs(commandLine);
		File pckFile = new File(getGameDir(), PCK_FILE_NAME);
		if (pckFile.isFile()) {
			commandLine.add("--main-pack");
			commandLine.add(pckFile.getAbsolutePath());
			Log.i(TAG, "Loading imported game PCK: " + pckFile.getAbsolutePath());
			appendAndroidLaunchLog("Loading imported game PCK: " + pckFile.getAbsolutePath());
		} else {
			String bootstrapPck = extractBootstrapPck();
			if (bootstrapPck != null) {
				commandLine.add("--main-pack");
				commandLine.add(bootstrapPck);
				Log.i(TAG, "Using bootstrap PCK because no imported payload is ready.");
				appendAndroidLaunchLog("Using bootstrap PCK because no imported payload is ready: " + bootstrapPck);
			} else {
				Log.w(TAG, "No imported PCK and no bootstrap PCK asset available.");
				appendAndroidLaunchLog("No imported PCK and no bootstrap PCK asset available.");
			}
		}
		Log.i(TAG, "DIAG GodotApp.getCommandLine final=" + commandLine);
		appendAndroidLaunchLog("Final Godot command line: " + commandLine);
		logGodotAppLaunchSnapshot("getCommandLine_final");
		return commandLine;
	}

	private void writePerformanceOverlayLaunchFlag() {
		File flag = new File(new File(getFilesDir(), "launcher"), "enable_debug_menu.flag");
		try {
			ensureDirectory(flag.getParentFile());
			if (new ExtraSettingsRepository(this).isPerformanceOverlayEnabledForLaunch()) {
				try (OutputStream outputStream = new FileOutputStream(flag, false)) {
					outputStream.write("1\n".getBytes(StandardCharsets.UTF_8));
				}
				Log.i(TAG, "Enabled STS2 performance overlay launch flag: " + flag.getAbsolutePath());
				appendAndroidLaunchLog("Enabled STS2 performance overlay launch flag: " + flag.getAbsolutePath());
			} else if (flag.exists() && !flag.delete()) {
				Log.w(TAG, "Unable to remove STS2 performance overlay launch flag: " + flag.getAbsolutePath());
			}
		} catch (Exception exception) {
			Log.w(TAG, "Unable to update STS2 performance overlay launch flag.", exception);
		}
	}

	private void appendGodotLogFileCommandLineArgs(List<String> commandLine) {
		if (ExtraSettingsRepository.LOG_LEVEL_OFF.equals(new ExtraSettingsRepository(this).getLogLevelForLaunch())) {
			Log.i(TAG, "Godot runtime log file disabled by log level setting.");
			appendAndroidLaunchLog("Godot --log-file disabled by log level setting.");
			return;
		}
		File logsDir = new LaunchProfileManager(this).getSelectedLogsRootDir();
		File godotLogFile = new File(logsDir, "godot.log");
		try {
			ensureDirectory(logsDir);
			rotateGodotLogIfNeeded(logsDir, godotLogFile);
			commandLine.add("--log-file");
			commandLine.add(godotLogFile.getAbsolutePath());
			Log.i(TAG, "Writing Godot runtime log to: " + godotLogFile.getAbsolutePath());
			appendAndroidLaunchLog("Configured Godot --log-file: " + godotLogFile.getAbsolutePath());
		} catch (Exception exception) {
			Log.w(TAG, "Unable to configure Godot runtime log file.", exception);
		}
	}

	private void appendSts2PlatformCommandLineArgs(List<String> commandLine) {
		commandLine.add("--force-steam");
		commandLine.add("off");
		Log.i(TAG, "Configured STS2 platform command line: --force-steam off");
		appendAndroidLaunchLog("Configured STS2 platform command line: --force-steam off");
	}

	private void appendSts2LogLevelCommandLineArgs(List<String> commandLine) {
		String configuredLevel = new ExtraSettingsRepository(this).getLogLevelForLaunch();
		if (ExtraSettingsRepository.LOG_LEVEL_OFF.equals(configuredLevel)) {
			Log.i(TAG, "STS2 runtime log command line disabled by log level setting.");
			appendAndroidLaunchLog("STS2 runtime log command line disabled by log level setting.");
			return;
		}
		String commandLineLevel = toSts2LogLevelArgument(configuredLevel);
		String[] logTypes = new String[] { "Generic", "Network", "Actions", "GameSync", "VisualSync" };
		for (String logType : logTypes) {
			commandLine.add("-log");
			commandLine.add(logType);
			commandLine.add(commandLineLevel);
		}
		Log.i(TAG, "Configured STS2 runtime log level: " + commandLineLevel);
		appendAndroidLaunchLog("Configured STS2 runtime log level: " + commandLineLevel);
	}

	private String toSts2LogLevelArgument(String value) {
		String normalized = ExtraSettingsRepository.normalizeLogLevel(value);
		if (ExtraSettingsRepository.LOG_LEVEL_VERY_DEBUG.equals(normalized)) {
			return "VeryDebug";
		}
		if (ExtraSettingsRepository.LOG_LEVEL_DEBUG.equals(normalized)) {
			return "Debug";
		}
		return "Info";
	}

	private void rotateGodotLogIfNeeded(File logsDir, File godotLogFile) {
		if (!godotLogFile.isFile() || godotLogFile.length() <= 0) {
			return;
		}
		File archivedLogFile = buildArchivedGodotLogFile(logsDir, godotLogFile.lastModified());
		if (godotLogFile.renameTo(archivedLogFile)) {
			Log.i(TAG, "Archived previous Godot log to: " + archivedLogFile.getAbsolutePath());
			appendAndroidLaunchLog("Archived previous Godot log to: " + archivedLogFile.getAbsolutePath());
			pruneArchivedGodotLogs(logsDir);
		} else {
			Log.w(TAG, "Unable to archive previous Godot log: " + godotLogFile.getAbsolutePath());
		}
	}

	private File buildArchivedGodotLogFile(File logsDir, long timestampMillis) {
		String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH.mm.ss", Locale.US).format(new java.util.Date(Math.max(0L, timestampMillis)));
		File candidate = new File(logsDir, "godot" + timestamp + ".log");
		int suffix = 1;
		while (candidate.exists()) {
			candidate = new File(logsDir, "godot" + timestamp + "-" + suffix + ".log");
			suffix++;
		}
		return candidate;
	}

	private void pruneArchivedGodotLogs(File logsDir) {
		File[] archivedLogs = logsDir.listFiles((dir, name) -> name != null && name.matches("godot\\d{4}-\\d{2}-\\d{2}T\\d{2}\\.\\d{2}\\.\\d{2}(?:-\\d+)?\\.log"));
		if (archivedLogs == null || archivedLogs.length <= MAX_GODOT_LOG_FILES - 1) {
			return;
		}
		Arrays.sort(archivedLogs, Comparator.comparingLong(File::lastModified).reversed());
		for (int i = MAX_GODOT_LOG_FILES - 1; i < archivedLogs.length; i++) {
			if (!archivedLogs[i].delete()) {
				Log.w(TAG, "Unable to delete old archived Godot log: " + archivedLogs[i].getAbsolutePath());
			}
		}
	}

	private void appendAndroidLaunchLog(String message) {
		try {
			File logsDir = new LaunchProfileManager(this).getSelectedLogsRootDir();
			ensureDirectory(logsDir);
			File launchLog = new File(logsDir, "android-launch.log");
			String line = new java.util.Date() + "  " + message + "\n";
			try (OutputStream outputStream = new FileOutputStream(launchLog, true)) {
				outputStream.write(line.getBytes(StandardCharsets.UTF_8));
			}
		} catch (Exception ignored) {
		}
	}

	private void appendAndroidDisplayCommandLineArgs(List<String> commandLine) {
		try {
			File settingsFile = getSettingsFile();
			if (!settingsFile.isFile()) {
				return;
			}
			JSONObject settings = new JSONObject(readTextFile(settingsFile));
			JSONObject size = settings.optJSONObject("fullscreen_render_size");
			if (size == null) {
				return;
			}
			int width = size.optInt("X", size.optInt("x", 0));
			int height = size.optInt("Y", size.optInt("y", 0));
			if (width > 0 && height > 0) {
				commandLine.add("--resolution");
				commandLine.add(width + "x" + height);
				Log.i(TAG, "Applying Android render resolution command line: " + width + "x" + height);
			}
		} catch (Exception exception) {
			Log.w(TAG, "Unable to append Android display command line args.", exception);
		}
	}

	private File getGameDir() {
		if (gameDir == null) {
			gameDir = new LaunchProfileManager(this).getSelectedGameDir();
		}
		return gameDir;
	}

	private void ensureLaunchPreparedBeforeGodot(boolean launchPrepared) {
		File publishDir = new File(getFilesDir(), ".godot/mono/publish/arm64");
		File entryDll = new File(publishDir, "STS2Mobile.dll");
		File gameDll = new File(publishDir, "sts2.dll");
		boolean compatEnabled = new CompatPackManager(this).isCompatPackEnabled();
		boolean needsPrepare = !launchPrepared || (compatEnabled && !entryDll.isFile()) || !gameDll.isFile();
		Log.e(TAG, "DIAG_FORCE GodotApp.ensureLaunchPrepared before launch_prepared=" + launchPrepared + " compat_enabled=" + compatEnabled + " needs_prepare=" + needsPrepare + " entry=" + describeFile(entryDll) + " game_dll=" + describeFile(gameDll));
		if (needsPrepare) {
			prepareLaunchFallback();
		}
		forceStageCompatEntryDllIfMissing(publishDir, entryDll);
		Log.e(TAG, "DIAG_FORCE GodotApp.ensureLaunchPrepared after entry=" + describeFile(entryDll) + " game_dll=" + describeFile(gameDll) + " publish=" + describeFile(publishDir));
	}

	private void prepareLaunchFallback() {
		try {
			new GameLaunchPreparationManager(this).prepareForLaunch();
			Log.e(TAG, "DIAG_FORCE Launch preparation complete via Activity pre-super fallback.");
		} catch (Exception exception) {
			Log.e(TAG, "DIAG_FORCE Failed to prepare launch before Godot startup.", exception);
		}
	}

	private void forceStageCompatEntryDllIfMissing(File publishDir, File entryDll) {
		CompatPackManager compatPackManager = new CompatPackManager(this);
		if (!compatPackManager.isCompatPackEnabled()) {
			File overlay = new File(getFilesDir(), "port_compat.pck");
			deleteFileQuietly(entryDll);
			deleteFileQuietly(overlay);
			Log.e(TAG, "DIAG_FORCE forceStageCompatEntryDll skipped_because_disabled dest=" + describeFile(entryDll) + " overlay=" + describeFile(overlay));
			return;
		}
		if (entryDll != null && entryDll.isFile()) {
			return;
		}
		try {
			ensureDirectory(publishDir);
			File selected = compatPackManager.getSelectedCompatDll();
			if (selected != null && selected.isFile()) {
				copyFile(selected, entryDll);
				Log.e(TAG, "DIAG_FORCE forceStageCompatEntryDll selected_pack source=" + describeFile(selected) + " dest=" + describeFile(entryDll));
				return;
			}
			try (InputStream inputStream = getAssets().open("dotnet_bcl/STS2Mobile.dll")) {
				copyStreamToFile(inputStream, entryDll);
				Log.e(TAG, "DIAG_FORCE forceStageCompatEntryDll asset_fallback dest=" + describeFile(entryDll));
			}
		} catch (Exception exception) {
			Log.e(TAG, "DIAG_FORCE forceStageCompatEntryDll failed dest=" + describeFile(entryDll), exception);
		}
	}

	private void deleteFileQuietly(File file) {
		if (file == null || !file.exists()) {
			return;
		}
		if (!file.delete() && file.exists()) {
			Log.w(TAG, "Unable to delete file: " + file.getAbsolutePath());
		}
	}

	private String extractBootstrapPck() {
		File dest = new File(getFilesDir(), "bootstrap.pck");
		if (dest.isFile()) {
			return dest.getAbsolutePath();
		}
		if (extractAssetIfChanged("bootstrap.pck", dest)) {
			return dest.getAbsolutePath();
		}
		return null;
	}

	private boolean extractAssetIfChanged(String assetName, File dest) {
		try (InputStream inputStream = getAssets().open(assetName)) {
			byte[] assetBytes = readAllBytes(inputStream);
			if (dest.isFile() && dest.length() == assetBytes.length) {
				return true;
			}
			File parent = dest.getParentFile();
			if (parent != null) {
				ensureDirectory(parent);
			}
			try (OutputStream outputStream = new FileOutputStream(dest)) {
				outputStream.write(assetBytes);
			}
			Log.i(TAG, "Extracted asset " + assetName + " to " + dest.getAbsolutePath());
			return true;
		} catch (IOException exception) {
			return false;
		}
	}

	private byte[] readAllBytes(InputStream inputStream) throws IOException {
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				outputStream.write(buffer, 0, read);
			}
			return outputStream.toByteArray();
		}
	}

	private void copyFile(File src, File dest) throws IOException {
		try (InputStream inputStream = new FileInputStream(src)) {
			copyStreamToFile(inputStream, dest);
		}
	}

	private void copyStreamToFile(InputStream inputStream, File dest) throws IOException {
		File parent = dest.getParentFile();
		if (parent != null) {
			ensureDirectory(parent);
		}
		try (OutputStream outputStream = new FileOutputStream(dest)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				outputStream.write(buffer, 0, read);
			}
		}
	}

	private void ensureDirectory(File directory) throws IOException {
		if (directory.isDirectory()) {
			return;
		}
		if (!directory.mkdirs() && !directory.isDirectory()) {
			throw new IOException("Unable to create directory: " + directory.getAbsolutePath());
		}
	}

	public static Intent createLaunchIntent(Context context, boolean forceNewLaunch) {
		Intent intent = new Intent(context, GodotApp.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		if (forceNewLaunch) {
			intent.putExtra(getEXTRA_NEW_LAUNCH(), true);
		}
		return intent;
	}

	@Override
	public void onResume() {
		super.onResume();
		currentInstance = this;
		currentResumed = true;
		currentWindowFocused = hasWindowFocus();
		applyConfiguredScreenOrientation();
		updateWindowAppearance.run();
		HighRefreshRateController.applyWithRetries(this, getGodot(), "onResume");
	}

	@Override
	public void onPause() {
		disableOrientationEventListener();
		currentResumed = false;
		currentWindowFocused = false;
		super.onPause();
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (shouldOpenSoftKeyboardForVolumeUp(event)) {
			if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
				showSoftKeyboardForGame();
			}
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	private boolean shouldOpenSoftKeyboardForVolumeUp(KeyEvent event) {
		if (event == null || event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP || !isGameWindowInteractive()) {
			return false;
		}
		return isVolumeUpSoftKeyboardEnabled();
	}

	private boolean isVolumeUpSoftKeyboardEnabled() {
		try {
			File settingsFile = getSettingsFile();
			if (!settingsFile.isFile()) {
				return false;
			}
			String content = readTextFile(settingsFile).trim();
			if (content.isEmpty()) {
				return false;
			}
			return new JSONObject(content).optBoolean(SOFT_KEYBOARD_SHORTCUT_SETTING_KEY, false);
		} catch (Exception exception) {
			Log.w("GODOT", "Failed to read soft-keyboard shortcut setting.", exception);
			return false;
		}
	}

	private void applyConfiguredScreenOrientation() {
		String mode = ExtraSettingsRepository.SCREEN_ROTATION_USER_LANDSCAPE;
		try {
			File settingsFile = getSettingsFile();
			if (settingsFile.isFile()) {
				String content = readTextFile(settingsFile).trim();
				if (!content.isEmpty()) {
					JSONObject settings = new JSONObject(content);
					String fallback = settings.optBoolean("android_flip_screen_180", false)
						? ExtraSettingsRepository.SCREEN_ROTATION_REVERSE_LANDSCAPE
						: ExtraSettingsRepository.SCREEN_ROTATION_USER_LANDSCAPE;
					mode = ExtraSettingsRepository.normalizeScreenRotationMode(settings.optString(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE, fallback));
				}
			}
		} catch (Exception exception) {
			Log.w(TAG, "Unable to read screen rotation mode; using auto.", exception);
		}

		int orientation;
		switch (mode) {
			case ExtraSettingsRepository.SCREEN_ROTATION_LANDSCAPE:
				orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
				disableOrientationEventListener();
				break;
			case ExtraSettingsRepository.SCREEN_ROTATION_REVERSE_LANDSCAPE:
				orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
				disableOrientationEventListener();
				break;
			case ExtraSettingsRepository.SCREEN_ROTATION_USER_LANDSCAPE:
				orientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
				disableOrientationEventListener();
				break;
			case ExtraSettingsRepository.SCREEN_ROTATION_AUTO:
			default:
				orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
				enableOrientationEventListener();
				break;
		}
		if (getRequestedOrientation() != orientation) {
			setRequestedOrientation(orientation);
		}
		Log.i(TAG, "Configured GodotApp screen orientation mode=" + mode + " requestedOrientation=" + orientation);
	}

	private void enableOrientationEventListener() {
		if (orientationEventListener == null) {
			orientationEventListener = new android.view.OrientationEventListener(this, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) {
				@Override
				public void onOrientationChanged(int orientation) {
					if (orientation == ORIENTATION_UNKNOWN) {
						return;
					}
					if (orientation >= 65 && orientation <= 115) {
						int target = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
						if (getRequestedOrientation() != target) {
							setRequestedOrientation(target);
							Log.d(TAG, "Force orientation auto-rotate reversed landscape: orientation=" + orientation);
						}
					} else if (orientation >= 245 && orientation <= 295) {
						int target = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
						if (getRequestedOrientation() != target) {
							setRequestedOrientation(target);
							Log.d(TAG, "Force orientation auto-rotate standard landscape: orientation=" + orientation);
						}
					}
				}
			};
		}
		if (orientationEventListener.canDetectOrientation()) {
			orientationEventListener.enable();
		}
	}

	private void disableOrientationEventListener() {
		if (orientationEventListener != null) {
			orientationEventListener.disable();
		}
	}

	private void showSoftKeyboardForGame() {
		runOnUiThread(() -> {
			try {
				View targetView = getKeyboardTargetView();
				targetView.setFocusableInTouchMode(true);
				targetView.requestFocus();
				targetView.post(() -> {
					try {
						InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
						if (inputMethodManager == null) {
							return;
						}
						inputMethodManager.restartInput(targetView);
						boolean shown = inputMethodManager.showSoftInput(targetView, InputMethodManager.SHOW_IMPLICIT);
						if (!shown) {
							inputMethodManager.showSoftInput(targetView, InputMethodManager.SHOW_FORCED);
						}
					} catch (Exception exception) {
						Log.e("GODOT", "Failed to show soft keyboard for game input.", exception);
					}
				});
			} catch (Exception exception) {
				Log.e("GODOT", "Failed to prepare soft keyboard target view.", exception);
			}
		});
	}

	private View getKeyboardTargetView() {
		Godot godot = getGodot();
		if (godot != null && godot.getRenderView() != null && godot.getRenderView().getView() != null) {
			return godot.getRenderView().getView();
		}
		View currentFocus = getCurrentFocus();
		if (currentFocus != null) {
			return currentFocus;
		}
		return getWindow().getDecorView();
	}

	private File getSettingsFile() {
		return new File(getAccountRootDir(), SETTINGS_FILE_NAME);
	}

	private File getAccountRootDir() {
		return new LaunchProfileManager(this).getSelectedAccountRootDir();
	}

	private String readTextFile(File file) throws Exception {
		try (InputStream inputStream = new FileInputStream(file);
				 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				outputStream.write(buffer, 0, read);
			}
			return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		boolean wasFocused = currentWindowFocused;
		currentWindowFocused = hasFocus;
		if (wasFocused != hasFocus) {
			dispatchImmediateGodotFocusChange(hasFocus);
		}
		if (hasFocus) {
			HighRefreshRateController.applyWithRetries(this, getGodot(), "onWindowFocusChanged");
		}
	}

	@Override
	public void onDestroy() {
		try {
			FMOD.close();
		} catch (Throwable ignored) {
		}
		disableOrientationEventListener();
		currentResumed = false;
		currentWindowFocused = false;
		if (currentInstance == this) {
			currentInstance = null;
		}
		super.onDestroy();
	}

	@Override
	public void onGodotMainLoopStarted() {
		super.onGodotMainLoopStarted();
		StartupHealthTracker.markGameLaunchFinished(this);
		applyConfiguredScreenOrientation();
		runOnUiThread(updateWindowAppearance);
		HighRefreshRateController.applyWithRetries(this, getGodot(), "onGodotMainLoopStarted");
	}

	public static void applySelectedScreenOrientationFromGame() {
		GodotApp activity = currentInstance;
		if (activity == null) {
			return;
		}
		activity.runOnUiThread(activity::applyConfiguredScreenOrientation);
	}

	public static boolean isGameWindowInteractive() {
		return currentResumed && currentWindowFocused;
	}

	public static String getGodotDataDir() {
		GodotApp activity = currentInstance;
		return activity == null ? "" : activity.getFilesDir().getAbsolutePath();
	}

	public static String getSelectedGameDir() {
		GodotApp activity = currentInstance;
		return activity == null ? "" : new LaunchProfileManager(activity).getSelectedGameDir().getAbsolutePath();
	}

	public static String getSelectedAccountRootDir() {
		GodotApp activity = currentInstance;
		return activity == null ? "" : new LaunchProfileManager(activity).getSelectedAccountRootDir().getAbsolutePath();
	}

	public static String getSelectedModsDir() {
		GodotApp activity = currentInstance;
		return activity == null ? "" : new LaunchProfileManager(activity).getSelectedModsRootDir().getAbsolutePath();
	}

	public static String getSelectedLogsDir() {
		GodotApp activity = currentInstance;
		return activity == null ? "" : new LaunchProfileManager(activity).getSelectedLogsRootDir().getAbsolutePath();
	}

	public static String getSelectedLaunchContextJson() {
		GodotApp activity = currentInstance;
		return activity == null ? "{}" : new LaunchProfileManager(activity).buildSelectedLaunchContextJson();
	}

	public static boolean isAndroidCompatPackEnabled() {
		GodotApp activity = currentInstance;
		return activity != null && new CompatPackManager(activity).isCompatPackEnabled();
	}

	public static String getSelectedCompatPackDir() {
		GodotApp activity = currentInstance;
		if (activity == null) {
			return "";
		}
		CompatPackManager manager = new CompatPackManager(activity);
		if (!manager.isCompatPackEnabled()) {
			return "";
		}
		CompatPackManager.CompatPack pack = manager.getSelectedPack();
		return pack == null ? "" : pack.dir.getAbsolutePath();
	}

	public static String getSelectedCompatOverlayPck() {
		GodotApp activity = currentInstance;
		if (activity == null) {
			return "";
		}
		CompatPackManager manager = new CompatPackManager(activity);
		if (!manager.isCompatPackEnabled()) {
			return "";
		}
		File file = manager.getSelectedCompatOverlayPck();
		return file == null ? "" : file.getAbsolutePath();
	}

	public static boolean launchGameSettingsFromGame() {
		GodotApp activity = currentInstance;
		if (activity == null) {
			Log.e("GODOT", "Unable to launch GameSettingsActivity because current GodotApp instance is null.");
			return false;
		}
		activity.runOnUiThread(() -> {
			try {
				Intent intent = new Intent(activity, GameSettingsActivity.class);
				intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
				activity.startActivity(intent);
			} catch (Exception exception) {
				Log.e("GODOT", "Failed to launch GameSettingsActivity from in-game entry.", exception);
			}
		});
		return true;
	}

	public static boolean restartToSettingsFromGame() {
		GodotApp activity = currentInstance;
		if (activity == null) {
			Log.e("GODOT", "Unable to hard-restart to GameSettingsActivity because current GodotApp instance is null.");
			return false;
		}
		markExpectedCleanGameExit("restart_to_settings");
		activity.runOnUiThread(activity::restartToSettingsAndExitProcess);
		return true;
	}

	public static boolean markExpectedCleanGameExit(String source) {
		GodotApp activity = currentInstance;
		if (activity == null) {
			return false;
		}
		SteamCleanExitTracker.mark(activity, source);
		return true;
	}

	private void restartToSettingsAndExitProcess() {
		try {
			Log.i("GODOT", "[AndroidRestart] Restarting to GameSettingsActivity and exiting process to clear Godot/Mono/NativeDetour state.");
			Intent intent = new Intent(this, GameSettingsActivity.class);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
			startActivity(intent);
			Runtime.getRuntime().exit(0);
		} catch (Exception exception) {
			Log.e("GODOT", "[AndroidRestart] Failed to restart to GameSettingsActivity.", exception);
		}
	}
}
