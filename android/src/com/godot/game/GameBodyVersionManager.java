package com.godot.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class GameBodyVersionManager {
	private static final String GAME_VERSIONS_DIR_NAME = "game-versions";
	private static final String PREFS_NAME = "sts2_version_manager";
	private static final String KEY_SELECTED_GAME_VERSION_ID = "selected_game_version_id";

	private final Context context;
	private final PayloadManager payloadManager;

	public GameBodyVersionManager(Context context) {
		this.context = context.getApplicationContext();
		this.payloadManager = new PayloadManager(context);
	}

	public GameBodyVersion archiveActivePayload() throws Exception {
		PayloadManager.Status status = payloadManager.getStatus();
		if (!status.ready) {
			throw new IllegalStateException("No active game payload to archive.");
		}
		String id = buildVersionId(status.manifest);
		File targetGame = new File(new File(getVersionsRootDir(), id), PayloadManager.GAME_DIR_NAME);
		FileBrowserSupport.deleteRecursively(targetGame);
		FileBrowserSupport.copyEntryRecursively(status.gameDir, targetGame);
		setSelectedVersionId(id);
		writeSelectedVersionJson(readVersion(new File(getVersionsRootDir(), id)));
		return readVersion(new File(getVersionsRootDir(), id));
	}

	public List<GameBodyVersion> listVersions() {
		List<GameBodyVersion> versions = new ArrayList<>();
		File[] children = getVersionsRootDir().listFiles(File::isDirectory);
		if (children == null) {
			return versions;
		}
		for (File child : children) {
			try {
				GameBodyVersion version = readVersion(child);
				if (version != null) {
					versions.add(version);
				}
			} catch (Exception ignored) {
			}
		}
		versions.sort(Comparator.comparing((GameBodyVersion version) -> version.label, String.CASE_INSENSITIVE_ORDER));
		return versions;
	}

	public GameBodyVersion getSelectedVersion() {
		String id = getSelectedVersionId();
		if (TextUtils.isEmpty(id)) {
			return null;
		}
		try {
			return readVersion(new File(getVersionsRootDir(), id));
		} catch (Exception ignored) {
			return null;
		}
	}

	public String getSelectedVersionId() {
		return prefs().getString(KEY_SELECTED_GAME_VERSION_ID, "");
	}

	public void selectVersion(String id) throws Exception {
		GameBodyVersion version = readVersion(new File(getVersionsRootDir(), id));
		if (version == null || !version.ready) {
			throw new IllegalStateException("Game version is missing or incomplete: " + id);
		}
		File active = payloadManager.getGameDir();
		File importRoot = new File(context.getFilesDir(), PayloadManager.IMPORT_DIR_NAME);
		File backup = new File(importRoot, "switch-backup-" + System.currentTimeMillis());
		FileBrowserSupport.ensureDirectory(importRoot);
		boolean moved = false;
		try {
			if (active.exists()) {
				if (!active.renameTo(backup)) {
					throw new IllegalStateException("Unable to move current game aside: " + active.getAbsolutePath());
				}
				moved = true;
			}
			FileBrowserSupport.copyEntryRecursively(version.gameDir, active);
			PayloadManager.Status status = payloadManager.getStatus();
			if (!status.ready) {
				throw new IllegalStateException("Selected game did not validate after switch: " + status.message);
			}
			FileBrowserSupport.deleteRecursively(backup);
			setSelectedVersionId(version.id);
			writeSelectedVersionJson(version);
		} catch (Exception exception) {
			FileBrowserSupport.deleteRecursively(active);
			if (moved && backup.exists()) {
				backup.renameTo(active);
			}
			throw exception;
		}
	}

	public void deleteVersion(String id) throws Exception {
		if (TextUtils.isEmpty(id)) {
			return;
		}
		File dir = new File(getVersionsRootDir(), id);
		if (dir.isDirectory()) {
			FileBrowserSupport.deleteRecursively(dir);
		}
		if (id.equals(getSelectedVersionId())) {
			setSelectedVersionId("");
			writeSelectedVersionJson(null);
		}
	}

	private GameBodyVersion readVersion(File dir) throws Exception {
		File gameDir = new File(dir, PayloadManager.GAME_DIR_NAME);
		File manifestFile = new File(gameDir, PayloadManager.MANIFEST_FILE_NAME);
		if (!manifestFile.isFile()) {
			return null;
		}
		JSONObject manifest = new JSONObject(readTextFile(manifestFile));
		JSONObject identity = manifest.optJSONObject("identity");
		JSONObject releaseInfo = identity == null ? null : identity.optJSONObject("release_info");
		if (releaseInfo == null) {
			JSONObject game = manifest.optJSONObject("game");
			releaseInfo = game == null ? null : game.optJSONObject("release_info");
		}
		String version = releaseInfo == null ? "" : releaseInfo.optString("version", "");
		String commit = releaseInfo == null ? "" : releaseInfo.optString("commit", "");
		String label = !TextUtils.isEmpty(version) && !TextUtils.isEmpty(commit) ? version + " (" + commit + ")" : (!TextUtils.isEmpty(version) ? version : dir.getName());
		boolean ready = new File(gameDir, PayloadManager.PCK_FILE_NAME).isFile() && new File(gameDir, PayloadManager.STS2_DLL_PATH).isFile();
		return new GameBodyVersion(dir.getName(), label, version, commit, gameDir, manifest, ready, dir.lastModified() / 1000L);
	}

	private String buildVersionId(JSONObject manifest) {
		JSONObject identity = manifest == null ? null : manifest.optJSONObject("identity");
		JSONObject releaseInfo = identity == null ? null : identity.optJSONObject("release_info");
		if (releaseInfo == null && manifest != null) {
			JSONObject game = manifest.optJSONObject("game");
			releaseInfo = game == null ? null : game.optJSONObject("release_info");
		}
		String version = releaseInfo == null ? "unknown" : releaseInfo.optString("version", "unknown");
		String commit = releaseInfo == null ? "" : releaseInfo.optString("commit", "");
		String id = "sts2-" + version + (TextUtils.isEmpty(commit) ? "" : "-" + commit);
		return sanitizeId(id);
	}

	private String sanitizeId(String value) {
		String sanitized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		sanitized = sanitized.replaceAll("[^a-z0-9._-]+", "-");
		while (sanitized.startsWith("-") || sanitized.startsWith(".")) {
			sanitized = sanitized.substring(1);
		}
		while (sanitized.endsWith("-") || sanitized.endsWith(".")) {
			sanitized = sanitized.substring(0, sanitized.length() - 1);
		}
		return TextUtils.isEmpty(sanitized) ? "sts2-unknown" : sanitized;
	}

	private void setSelectedVersionId(String id) {
		SharedPreferences.Editor editor = prefs().edit();
		if (TextUtils.isEmpty(id)) {
			editor.remove(KEY_SELECTED_GAME_VERSION_ID);
		} else {
			editor.putString(KEY_SELECTED_GAME_VERSION_ID, id);
		}
		editor.apply();
	}

	private void writeSelectedVersionJson(GameBodyVersion version) throws Exception {
		File launcherDir = new File(context.getFilesDir(), "launcher");
		FileBrowserSupport.ensureDirectory(launcherDir);
		JSONObject root = new JSONObject();
		root.put("schema", 1);
		if (version != null) {
			root.put("selected_game_version_id", version.id);
			root.put("selected_game_dir", version.gameDir.getAbsolutePath());
		}
		FileBrowserSupport.writeTextFile(new File(launcherDir, "selected_game_version.json"), root.toString(2));
	}

	private String readTextFile(File file) throws Exception {
		return FileBrowserSupport.readTextFile(file);
	}

	private File getVersionsRootDir() {
		return new File(context.getFilesDir(), GAME_VERSIONS_DIR_NAME);
	}

	private SharedPreferences prefs() {
		return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	public static final class GameBodyVersion {
		public final String id;
		public final String label;
		public final String version;
		public final String commit;
		public final File gameDir;
		public final JSONObject manifest;
		public final boolean ready;
		public final long installedAtUnix;

		GameBodyVersion(String id, String label, String version, String commit, File gameDir, JSONObject manifest, boolean ready, long installedAtUnix) {
			this.id = id;
			this.label = label;
			this.version = version;
			this.commit = commit;
			this.gameDir = gameDir;
			this.manifest = manifest;
			this.ready = ready;
			this.installedAtUnix = installedAtUnix;
		}
	}
}
