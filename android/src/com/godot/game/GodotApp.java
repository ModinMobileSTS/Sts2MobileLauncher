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
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONObject;

import androidx.activity.EdgeToEdge;
import androidx.core.splashscreen.SplashScreen;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Template activity for Godot Android builds.
 * Feel free to extend and modify this class for your custom logic.
 */
public class GodotApp extends GodotActivity {
	private static final String TAG = "Sts2Re";
	private static final String SETTINGS_FILE_NAME = "settings.save";
	private static final String SOFT_KEYBOARD_SHORTCUT_SETTING_KEY = "android_volume_up_soft_keyboard";
	private static final String PCK_FILE_NAME = PayloadManager.PCK_FILE_NAME;

	private static volatile GodotApp currentInstance;
	private static volatile boolean currentWindowFocused;
	private static volatile boolean currentResumed;
	private File gameDir;

	static {
		try {
			System.loadLibrary("fmod");
			System.loadLibrary("fmodstudio");
		} catch (UnsatisfiedLinkError error) {
			Log.w(TAG, "Unable to preload FMOD libraries; continuing so settings/diagnostics can still open.", error);
		}
		if (BuildConfig.FLAVOR.equals("mono")) {
			try {
				Log.v(TAG, "Loading System.Security.Cryptography.Native.Android library");
				System.loadLibrary("System.Security.Cryptography.Native.Android");
			} catch (UnsatisfiedLinkError error) {
				Log.e(TAG, "Unable to load System.Security.Cryptography.Native.Android library", error);
			}
		}
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
		gameDir = new File(getFilesDir(), PayloadManager.GAME_DIR_NAME);
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
		EdgeToEdge.enable(this);
		try {
			FMOD.init(this);
		} catch (Throwable throwable) {
			Log.w(TAG, "FMOD init failed; continuing so launcher diagnostics remain available.", throwable);
		}
		setupAssemblies();
		super.onCreate(savedInstanceState);
		currentInstance = this;
		currentWindowFocused = hasWindowFocus();
	}

	@Override
	public List<String> getCommandLine() {
		List<String> commandLine = new ArrayList<>(super.getCommandLine());
		Collections.addAll(commandLine, RendererPreference.buildGodotCommandLineArgs(this));
		File pckFile = new File(getGameDir(), PCK_FILE_NAME);
		if (pckFile.isFile()) {
			commandLine.add("--main-pack");
			commandLine.add(pckFile.getAbsolutePath());
			Log.i(TAG, "Loading imported game PCK: " + pckFile.getAbsolutePath());
		} else {
			String bootstrapPck = extractBootstrapPck();
			if (bootstrapPck != null) {
				commandLine.add("--main-pack");
				commandLine.add(bootstrapPck);
				Log.i(TAG, "Using bootstrap PCK because no imported payload is ready.");
			} else {
				Log.w(TAG, "No imported PCK and no bootstrap PCK asset available.");
			}
		}
		return commandLine;
	}


	private File getGameDir() {
		if (gameDir == null) {
			gameDir = new File(getFilesDir(), PayloadManager.GAME_DIR_NAME);
		}
		return gameDir;
	}

	private void setupAssemblies() {
		if (!BuildConfig.FLAVOR.equals("mono")) {
			return;
		}
		extractAssetIfChanged("port_compat.pck", new File(getFilesDir(), "port_compat.pck"));
		File destDir = new File(getFilesDir(), ".godot/mono/publish/arm64");
		File srcDir = findAssembliesDir();
		Set<String> protectedBclNames = new HashSet<>();
		boolean copiedAnyBcl = copyBclAssemblies(destDir, protectedBclNames);
		if (!srcDir.isDirectory()) {
			Log.w(TAG, "Game assembly source directory is missing: " + srcDir.getAbsolutePath());
			return;
		}
		File[] files = srcDir.listFiles();
		if (files == null) {
			return;
		}
		int copied = 0;
		int skipped = 0;
		for (File src : files) {
			if (!src.isFile() || src.getName().endsWith(".so")) {
				continue;
			}
			String name = src.getName();
			if (protectedBclNames.contains(name) || isProtectedBclAssembly(name)) {
				skipped++;
				continue;
			}
			File dest = new File(destDir, name);
			if (dest.isFile() && dest.length() == src.length() && dest.lastModified() >= src.lastModified()) {
				skipped++;
				continue;
			}
			try {
				copyFile(src, dest);
				copied++;
			} catch (IOException exception) {
				Log.e(TAG, "Failed to copy game assembly: " + name, exception);
			}
		}
		Log.i(TAG, "Assembly setup complete. BCL copied=" + copiedAnyBcl + ", game copied=" + copied + ", skipped=" + skipped);
	}

	private boolean copyBclAssemblies(File destDir, Set<String> copiedNames) {
		try {
			String[] bclFiles = getAssets().list("dotnet_bcl");
			if (bclFiles == null || bclFiles.length == 0) {
				Log.w(TAG, "No dotnet_bcl assets found.");
				return false;
			}
			ensureDirectory(destDir);
			int count = 0;
			for (String name : bclFiles) {
				try (InputStream inputStream = getAssets().open("dotnet_bcl/" + name)) {
					copyStreamToFile(inputStream, new File(destDir, name));
					copiedNames.add(name);
					count++;
				}
			}
			Log.i(TAG, "Copied " + count + " dotnet_bcl assemblies.");
			return true;
		} catch (IOException exception) {
			Log.e(TAG, "Failed to copy dotnet_bcl assemblies.", exception);
			return false;
		}
	}

	private File findAssembliesDir() {
		File root = getGameDir();
		File[] children = root.listFiles();
		if (children != null) {
			for (File child : children) {
				if (child.isDirectory() && child.getName().startsWith("data_")) {
					return child;
				}
			}
		}
		return new File(root, PayloadManager.ASSEMBLY_DIR_NAME);
	}

	private boolean isProtectedBclAssembly(String name) {
		return name.startsWith("System.")
			|| name.equals("mscorlib.dll")
			|| name.equals("netstandard.dll")
			|| name.equals("Microsoft.CSharp.dll")
			|| name.equals("Microsoft.VisualBasic.dll")
			|| name.equals("Microsoft.VisualBasic.Core.dll");
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
		updateWindowAppearance.run();
	}

	@Override
	public void onPause() {
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
		File defaultDirectory = new File(getFilesDir(), "default");
		File[] accountDirectories = defaultDirectory.listFiles(File::isDirectory);
		if (accountDirectories != null && accountDirectories.length > 0) {
			List<File> directories = new ArrayList<>(Arrays.asList(accountDirectories));
			directories.sort(Comparator.comparing(File::getName, String::compareToIgnoreCase));
			return directories.get(0);
		}
		return new File(defaultDirectory, "1");
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
	}

	@Override
	public void onDestroy() {
		try {
			FMOD.close();
		} catch (Throwable ignored) {
		}
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
		runOnUiThread(updateWindowAppearance);
	}

	public static boolean isGameWindowInteractive() {
		return currentResumed && currentWindowFocused;
	}

	public static String getGodotDataDir() {
		GodotApp activity = currentInstance;
		return activity == null ? "" : activity.getFilesDir().getAbsolutePath();
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
		activity.runOnUiThread(activity::restartToSettingsAndExitProcess);
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
