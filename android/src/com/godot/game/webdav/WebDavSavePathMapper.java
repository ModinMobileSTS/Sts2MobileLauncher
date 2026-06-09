package com.godot.game.webdav;

import java.io.File;
import java.util.Locale;

public final class WebDavSavePathMapper {
	private final boolean includeSettingsSave;

	public WebDavSavePathMapper(boolean includeSettingsSave) {
		this.includeSettingsSave = includeSettingsSave;
	}

	public String toLocalRelativePath(String remoteRelativePath) {
		String normalized = normalize(remoteRelativePath);
		return isSupportedLocalPath(normalized) ? normalized : "";
	}

	public String toRemoteRelativePath(String localRelativePath) {
		String normalized = normalize(localRelativePath);
		return isSupportedLocalPath(normalized) ? normalized : "";
	}

	public boolean isSupportedLocalPath(String localRelativePath) {
		String path = normalize(localRelativePath);
		if (path.isEmpty() || path.contains(":")) {
			return false;
		}
		String[] parts = path.split("/");
		for (String part : parts) {
			if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
				return false;
			}
		}
		String lower = path.toLowerCase(Locale.ROOT);
		if ("profile.save".equals(lower)) {
			return true;
		}
		if ("settings.save".equals(lower)) {
			return includeSettingsSave;
		}
		return isProfileSavePath(lower, "") || isProfileSavePath(lower, "modded/");
	}

	public File resolveLocalFile(File accountRoot, String localRelativePath) {
		String normalized = normalize(localRelativePath);
		File file = new File(accountRoot, normalized.replace('/', File.separatorChar));
		try {
			String rootPath = accountRoot.getCanonicalPath();
			String filePath = file.getCanonicalPath();
			if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
				throw new IllegalArgumentException("WebDAV path escapes account root: " + localRelativePath);
			}
		} catch (Exception exception) {
			throw new IllegalArgumentException("Invalid WebDAV path: " + localRelativePath, exception);
		}
		return file;
	}

	private boolean isProfileSavePath(String lower, String prefix) {
		for (int slot = 1; slot <= 3; slot++) {
			String base = prefix + "profile" + slot + "/saves/";
			if ((base + "progress.save").equals(lower)
				|| (base + "prefs.save").equals(lower)
				|| (base + "current_run.save").equals(lower)
				|| (base + "current_run.save.backup").equals(lower)
				|| (base + "current_run_mp.save").equals(lower)
				|| (base + "current_run_mp.save.backup").equals(lower)) {
				return true;
			}
			String history = base + "history/";
			if (lower.startsWith(history) && lower.endsWith(".run") && lower.length() > history.length() + 4) {
				return true;
			}
		}
		return false;
	}

	private String normalize(String path) {
		String value = path == null ? "" : path.trim().replace('\\', '/');
		while (value.startsWith("/")) {
			value = value.substring(1);
		}
		while (value.contains("//")) {
			value = value.replace("//", "/");
		}
		return value;
	}
}
