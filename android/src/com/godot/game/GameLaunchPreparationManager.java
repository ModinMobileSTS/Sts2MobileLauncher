package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Performs heavyweight launch preparation away from the Activity main thread.
 *
 * <p>Godot requires the Mono publish directory and the selected compatibility
 * overlay to be ready before {@link GodotApp} starts. Doing those copies inside
 * Activity.onCreate() can block the UI for several seconds when a game payload is
 * installed, so GameSettingsActivity runs this preparer on a worker thread before
 * starting GodotApp. GodotApp still keeps its own fallback setup path for direct
 * launches, but the normal launcher path should already be warm and fast.</p>
 */
public final class GameLaunchPreparationManager {
	private static final String TAG = "Sts2LaunchPrep";
	private static final String ASSEMBLY_SETUP_PREFERENCES_NAME = "sts2_assembly_setup";
	private static final String KEY_ASSEMBLY_SETUP_VERSION_CODE = "last_version_code";
	private static final String KEY_ASSEMBLY_SETUP_COMPAT_STAMP = "compat_stamp";
	private static final String KEY_ASSEMBLY_SETUP_PAYLOAD_STAMP = "payload_stamp";
	private static final String TEXTURE_CACHE_PREFERENCES_NAME = "sts2_texture_cache";
	private static final String KEY_TEXTURE_CACHE_PCK_STAMP = "pck_stamp";
	private static final int BUFFER_SIZE = 1024 * 1024;

	private final Context context;
	private final AssetManager assets;

	public GameLaunchPreparationManager(Context context) {
		this.context = context.getApplicationContext();
		this.assets = this.context.getAssets();
	}

	public void prepareForLaunch() throws Exception {
		patchPayloadIfNeeded();
		clearTextureCacheIfPayloadChanged();
		prepareAssembliesAndOverlay();
	}

	public void prepareAssembliesAndOverlay() throws Exception {
		if (!BuildConfig.FLAVOR.equals("mono")) {
			return;
		}
		stageSelectedCompatOverlay();
		File destDir = new File(context.getFilesDir(), ".godot/mono/publish/arm64");
		File srcDir = findAssembliesDir();
		Set<String> protectedBclNames = new HashSet<>();
		copyBclAssembliesIfNeeded(destDir, protectedBclNames);
		SharedPreferences preferences = context.getSharedPreferences(ASSEMBLY_SETUP_PREFERENCES_NAME, Context.MODE_PRIVATE);
		String payloadStamp = buildPayloadStamp();
		boolean forceGameAssemblyCopy = !payloadStamp.equals(preferences.getString(KEY_ASSEMBLY_SETUP_PAYLOAD_STAMP, ""));
		if (copyGameAssembliesIfNeeded(srcDir, destDir, protectedBclNames, forceGameAssemblyCopy)) {
			preferences.edit().putString(KEY_ASSEMBLY_SETUP_PAYLOAD_STAMP, payloadStamp).apply();
		}
	}

	private void patchPayloadIfNeeded() {
		File manifestFile = new PayloadManager(context).getManifestFile();
		if (isPckPatchRecorded(manifestFile)) {
			return;
		}
		try {
			new PayloadManager(context).patchInstalledPayloadIfNeeded();
		} catch (IOException exception) {
			Log.w(TAG, "Unable to patch imported PCK before launch; continuing with existing payload.", exception);
		}
	}

	private boolean isPckPatchRecorded(File manifestFile) {
		try {
			if (manifestFile == null || !manifestFile.isFile()) {
				return true;
			}
			org.json.JSONObject root = new org.json.JSONObject(FileBrowserSupport.readTextFile(manifestFile));
			org.json.JSONObject compat = root.optJSONObject("compat");
			return compat != null && compat.optJSONObject("pck_patches") != null;
		} catch (Exception exception) {
			return false;
		}
	}

	public void clearTextureCacheForNextLaunch() throws IOException {
		int removed = clearTextureCacheDirs();
		context.getSharedPreferences(TEXTURE_CACHE_PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.remove(KEY_TEXTURE_CACHE_PCK_STAMP)
			.apply();
		Log.i(TAG, "Texture cache cleared by user request; removed entries=" + removed);
	}

	private void clearTextureCacheIfPayloadChanged() throws IOException {
		String currentStamp = buildTextureCachePayloadStamp();
		if ("no-payload".equals(currentStamp)) {
			return;
		}
		SharedPreferences preferences = context.getSharedPreferences(TEXTURE_CACHE_PREFERENCES_NAME, Context.MODE_PRIVATE);
		String previousStamp = preferences.getString(KEY_TEXTURE_CACHE_PCK_STAMP, "");
		if (!previousStamp.isEmpty() && previousStamp.equals(currentStamp)) {
			return;
		}
		int removed = clearTextureCacheDirs();
		preferences.edit().putString(KEY_TEXTURE_CACHE_PCK_STAMP, currentStamp).apply();
		Log.i(TAG, "Texture cache stamp updated " + previousStamp + " -> " + currentStamp + "; removed entries=" + removed);
	}

	private String buildTextureCachePayloadStamp() {
		File pck = new File(context.getFilesDir(), PayloadManager.GAME_DIR_NAME + "/" + PayloadManager.PCK_FILE_NAME);
		if (!pck.isFile()) {
			return "no-payload";
		}
		return pck.length() + ":" + pck.lastModified();
	}

	private int clearTextureCacheDirs() throws IOException {
		String[] candidates = {
			".godot/imported",
			"etc2_cache/.godot/imported",
		};
		int total = 0;
		for (String relativePath : candidates) {
			File dir = new File(context.getFilesDir(), relativePath);
			if (!dir.exists()) {
				continue;
			}
			int[] counter = new int[] { 0 };
			deleteRecursivelyCounting(dir, counter);
			total += counter[0];
			Log.i(TAG, "Texture cache cleanup " + relativePath + ": removed entries=" + counter[0]);
		}
		return total;
	}

	private void deleteRecursivelyCounting(File file, int[] counter) throws IOException {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursivelyCounting(child, counter);
				}
			}
		}
		if (!file.delete() && file.exists()) {
			throw new IOException("Unable to delete texture cache entry: " + file.getAbsolutePath());
		}
		counter[0]++;
	}

	private void stageSelectedCompatOverlay() throws IOException {
		CompatPackManager manager = new CompatPackManager(context);
		File overlay = manager.getSelectedCompatOverlayPck();
		File dest = new File(context.getFilesDir(), "port_compat.pck");
		if (overlay != null && overlay.isFile()) {
			copyFile(overlay, dest);
			return;
		}
		extractAssetIfChanged("port_compat.pck", dest);
	}

	private void copyBclAssembliesIfNeeded(File destDir, Set<String> copiedNames) throws IOException {
		String[] bclFiles = assets.list("dotnet_bcl");
		if (bclFiles == null || bclFiles.length == 0) {
			Log.w(TAG, "No dotnet_bcl assets found.");
			return;
		}
		FileBrowserSupport.ensureDirectory(destDir);
		for (String name : bclFiles) {
			copiedNames.add(name);
		}
		String compatStamp = new CompatPackManager(context).buildSelectedCompatStamp();
		SharedPreferences preferences = context.getSharedPreferences(ASSEMBLY_SETUP_PREFERENCES_NAME, Context.MODE_PRIVATE);
		int previousVersion = preferences.getInt(KEY_ASSEMBLY_SETUP_VERSION_CODE, -1);
		String previousCompatStamp = preferences.getString(KEY_ASSEMBLY_SETUP_COMPAT_STAMP, "");
		boolean bclReady = new File(destDir, "STS2Mobile.dll").isFile()
			&& new File(destDir, "GodotSharp.dll").isFile();
		if (previousVersion == BuildConfig.VERSION_CODE && compatStamp.equals(previousCompatStamp) && bclReady) {
			return;
		}

		File selectedCompatDll = new CompatPackManager(context).getSelectedCompatDll();
		for (String name : bclFiles) {
			if ("STS2Mobile.dll".equals(name) && selectedCompatDll != null && selectedCompatDll.isFile()) {
				continue;
			}
			try (InputStream inputStream = assets.open("dotnet_bcl/" + name)) {
				copyStreamToFile(inputStream, new File(destDir, name));
			}
		}
		if (selectedCompatDll != null && selectedCompatDll.isFile()) {
			copyFile(selectedCompatDll, new File(destDir, "STS2Mobile.dll"));
		}
		preferences.edit()
			.putInt(KEY_ASSEMBLY_SETUP_VERSION_CODE, BuildConfig.VERSION_CODE)
			.putString(KEY_ASSEMBLY_SETUP_COMPAT_STAMP, compatStamp)
			.apply();
	}

	private boolean copyGameAssembliesIfNeeded(File srcDir, File destDir, Set<String> protectedBclNames, boolean forceCopy) throws IOException {
		if (!srcDir.isDirectory()) {
			Log.w(TAG, "Game assembly source directory is missing: " + srcDir.getAbsolutePath());
			return false;
		}
		FileBrowserSupport.ensureDirectory(destDir);
		File[] files = srcDir.listFiles();
		if (files == null) {
			return false;
		}
		for (File src : files) {
			if (!src.isFile() || src.getName().endsWith(".so")) {
				continue;
			}
			String name = src.getName();
			if (protectedBclNames.contains(name) || isProtectedBclAssembly(name)) {
				continue;
			}
			File dest = new File(destDir, name);
			if (forceCopy) {
				copyFile(src, dest);
			} else {
				copyFileIfDifferent(src, dest);
			}
		}
		return true;
	}

	private String buildPayloadStamp() {
		File manifestFile = new PayloadManager(context).getManifestFile();
		if (manifestFile == null || !manifestFile.isFile()) {
			return "no-payload";
		}
		try {
			org.json.JSONObject root = new org.json.JSONObject(FileBrowserSupport.readTextFile(manifestFile));
			org.json.JSONObject identity = root.optJSONObject("identity");
			org.json.JSONObject game = root.optJSONObject("game");
			String version = identity == null ? "" : identity.optString("version", "");
			String commit = identity == null ? "" : identity.optString("commit", "");
			long dllSize = identity == null ? 0L : identity.optLong("sts2_dll_size", 0L);
			if (dllSize <= 0L && game != null) {
				dllSize = game.optLong("dll_size", 0L);
			}
			return manifestFile.lastModified() + ":" + version + ":" + commit + ":" + dllSize;
		} catch (Exception exception) {
			return manifestFile.lastModified() + ":" + manifestFile.length();
		}
	}

	private File findAssembliesDir() {
		File root = new File(context.getFilesDir(), PayloadManager.GAME_DIR_NAME);
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

	private boolean extractAssetIfChanged(String assetName, File dest) throws IOException {
		try (InputStream inputStream = assets.open(assetName)) {
			File parent = dest.getParentFile();
			if (parent != null) {
				FileBrowserSupport.ensureDirectory(parent);
			}
			File temp = new File(parent == null ? context.getCacheDir() : parent, dest.getName() + ".tmp");
			copyStreamToFile(inputStream, temp);
			if (dest.isFile() && dest.length() == temp.length()) {
				FileBrowserSupport.deleteRecursively(temp);
				return false;
			}
			replaceFile(temp, dest);
			return true;
		}
	}

	private void copyFileIfDifferent(File src, File dest) throws IOException {
		if (dest.isFile() && dest.length() == src.length() && dest.lastModified() >= src.lastModified()) {
			return;
		}
		copyFile(src, dest);
	}

	private void copyFile(File src, File dest) throws IOException {
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(src))) {
			copyStreamToFile(inputStream, dest);
		}
	}

	private void copyStreamToFile(InputStream inputStream, File dest) throws IOException {
		File parent = dest.getParentFile();
		if (parent != null) {
			FileBrowserSupport.ensureDirectory(parent);
		}
		try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(dest))) {
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				outputStream.write(buffer, 0, read);
			}
		}
	}

	private void replaceFile(File temp, File dest) throws IOException {
		if (dest.exists() && !dest.delete()) {
			throw new IOException("Unable to replace file: " + dest.getAbsolutePath());
		}
		if (!temp.renameTo(dest)) {
			throw new IOException("Unable to move prepared file into place: " + dest.getAbsolutePath());
		}
	}
}
