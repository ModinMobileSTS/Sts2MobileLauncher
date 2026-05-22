package com.godot.game;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExtraSettingsUpdateChecker {
	public static final String GAME_DOWNLOAD_URL = "https://pan.quark.cn/s/9e8dcfd284cb";
	public static final String RELEASES_URL = "https://github.com/ModinMobileSTS/Sts2MobileLauncher/releases";

	private static final String TAG = "Sts2UpdateChecker";
	private static final String RELEASES_API_URL = "https://api.github.com/repos/ModinMobileSTS/Sts2MobileLauncher/releases?per_page=20";
	private static final String USER_AGENT = "Sts2MobileLauncher/" + BuildConfig.VERSION_NAME + " Android";
	private static final Pattern VERSION_WITH_DOT_PATTERN = Pattern.compile("(?i)(?:^|[^0-9A-Za-z])v?(\\d+(?:\\.\\d+)+)([-+][0-9A-Za-z.-]+)?(?:$|[^0-9A-Za-z])");
	private static final Pattern SINGLE_NUMBER_VERSION_PATTERN = Pattern.compile("(?i)(?:^|[^0-9A-Za-z])v?(\\d+)([-+][0-9A-Za-z.-]+)?(?:$|[^0-9A-Za-z])");

	private ExtraSettingsUpdateChecker() {
	}

	public static UpdateInfo checkForUpdate(Context context) throws Exception {
		JSONObject latestRelease = requestLatestRelease();
		if (latestRelease == null) {
			Log.d(TAG, "No GitHub release entry found.");
			return null;
		}

		String latestVersionName = firstNonEmpty(latestRelease.optString("tag_name", ""), latestRelease.optString("name", ""));
		if (latestVersionName.isEmpty()) {
			Log.d(TAG, "Latest GitHub release has no tag/name.");
			return null;
		}

		String currentVersionName = BuildConfig.VERSION_NAME;
		int comparison = compareVersions(latestVersionName, currentVersionName);
		Log.d(TAG, "Current launcher version=" + currentVersionName + ", latest release=" + latestVersionName + ", compare=" + comparison);
		if (comparison <= 0) {
			return null;
		}

		return new UpdateInfo(
			latestVersionName,
			latestRelease.optString("name", ""),
			latestRelease.optString("body", ""),
			firstNonEmpty(latestRelease.optString("html_url", ""), RELEASES_URL),
			latestRelease.optBoolean("prerelease", false)
		);
	}

	private static JSONObject requestLatestRelease() throws Exception {
		JSONArray releases = requestJsonArray(RELEASES_API_URL);
		for (int i = 0; i < releases.length(); i++) {
			JSONObject release = releases.optJSONObject(i);
			if (release == null || release.optBoolean("draft", false)) {
				continue;
			}
			return release;
		}
		return null;
	}

	private static int compareVersions(String latestVersionName, String currentVersionName) {
		Version latest = Version.parse(latestVersionName);
		Version current = Version.parse(currentVersionName);
		if (latest == null || current == null) {
			return normalizeVersionLabel(latestVersionName).equalsIgnoreCase(normalizeVersionLabel(currentVersionName)) ? 0 : -1;
		}

		int count = Math.max(latest.parts.length, current.parts.length);
		for (int i = 0; i < count; i++) {
			long latestPart = i < latest.parts.length ? latest.parts[i] : 0;
			long currentPart = i < current.parts.length ? current.parts[i] : 0;
			if (latestPart != currentPart) {
				return latestPart > currentPart ? 1 : -1;
			}
		}

		if (latest.preRelease != current.preRelease) {
			return latest.preRelease ? -1 : 1;
		}
		return 0;
	}

	private static JSONArray requestJsonArray(String url) throws Exception {
		HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(30000);
		connection.setReadTimeout(30000);
		connection.setRequestProperty("User-Agent", USER_AGENT);
		connection.setRequestProperty("Accept", "application/vnd.github+json");
		connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
		connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

		int status = connection.getResponseCode();
		String text;
		try (InputStream stream = new BufferedInputStream(status >= 400 ? nonNullErrorStream(connection) : connection.getInputStream())) {
			text = readAll(stream);
		}
		if (status < 200 || status >= 300) {
			throw new IOException("HTTP " + status + " from " + url + ": " + text);
		}
		return new JSONArray(text);
	}

	private static InputStream nonNullErrorStream(HttpURLConnection connection) throws IOException {
		InputStream errorStream = connection.getErrorStream();
		return errorStream == null ? connection.getInputStream() : errorStream;
	}

	private static String readAll(InputStream stream) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = stream.read(buffer)) != -1) {
			output.write(buffer, 0, read);
		}
		return new String(output.toByteArray(), StandardCharsets.UTF_8);
	}

	private static String firstNonEmpty(String first, String second) {
		String trimmedFirst = first == null ? "" : first.trim();
		if (!trimmedFirst.isEmpty()) {
			return trimmedFirst;
		}
		return second == null ? "" : second.trim();
	}

	private static String normalizeVersionLabel(String value) {
		return value == null ? "" : value.trim().replaceFirst("^[vV]", "");
	}

	private static final class Version {
		final long[] parts;
		final boolean preRelease;

		Version(long[] parts, boolean preRelease) {
			this.parts = parts;
			this.preRelease = preRelease;
		}

		static Version parse(String label) {
			if (label == null) {
				return null;
			}
			Matcher matcher = VERSION_WITH_DOT_PATTERN.matcher(label);
			if (!matcher.find()) {
				matcher = SINGLE_NUMBER_VERSION_PATTERN.matcher(label);
				if (!matcher.find()) {
					return null;
				}
			}
			String[] tokens = matcher.group(1).split("\\.");
			long[] parts = new long[tokens.length];
			try {
				for (int i = 0; i < tokens.length; i++) {
					parts[i] = Long.parseLong(tokens[i]);
				}
			} catch (NumberFormatException exception) {
				return null;
			}
			String suffix = matcher.group(2);
			return new Version(parts, suffix != null && suffix.startsWith("-"));
		}
	}

	public static final class UpdateInfo {
		public final String versionName;
		public final String title;
		public final String changelog;
		public final String releaseUrl;
		public final boolean prerelease;

		UpdateInfo(String versionName, String title, String changelog, String releaseUrl, boolean prerelease) {
			this.versionName = versionName;
			this.title = title;
			this.changelog = changelog;
			this.releaseUrl = releaseUrl;
			this.prerelease = prerelease;
		}
	}
}
