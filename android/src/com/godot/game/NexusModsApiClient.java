package com.godot.game;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.Formatter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NexusModsApiClient {
	private static final String API_BASE_V1 = "https://api.nexusmods.com/v1";
	private static final String API_BASE_V3 = "https://api.nexusmods.com/v3";
	private static final int CONNECT_TIMEOUT_MS = 20000;
	private static final int READ_TIMEOUT_MS = 60000;
	private static final int MAX_UPDATED_DETAILS = 24;
	private static final Pattern MOD_URL_PATTERN = Pattern.compile("nexusmods\\.com/([^/]+)/mods/(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern NXM_LINK_PATTERN = Pattern.compile("nxm://([^/]+)/mods/(\\d+)/files/(\\d+)(?:\\?([^\\s]+))?", Pattern.CASE_INSENSITIVE);

	private final Context context;
	private final String apiKey;
	private final String gameDomain;

	public NexusModsApiClient(Context context, String apiKey, String gameDomain) {
		this.context = context.getApplicationContext();
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.gameDomain = TextUtils.isEmpty(gameDomain) ? NexusModsStorePreferences.DEFAULT_GAME_DOMAIN : gameDomain.trim().toLowerCase(Locale.ROOT);
	}

	public String validateKey() throws Exception {
		requireApiKey();
		Object value = requestJson(API_BASE_V1 + "/users/validate.json", true);
		JSONObject object = asObject(value);
		String name = firstNonEmpty(
			object.optString("name", ""),
			object.optString("username", ""),
			object.optString("email", ""),
			object.optString("user_id", "")
		);
		return TextUtils.isEmpty(name) ? context.getString(R.string.nexus_mod_store_api_key_valid) : name;
	}

	public List<NexusMod> discoverMods() throws Exception {
		LinkedHashMap<String, NexusMod> merged = new LinkedHashMap<>();
		mergeMods(merged, fetchV1Feed("trending"));
		mergeMods(merged, fetchV1Feed("latest_added"));
		mergeMods(merged, fetchV1Feed("latest_updated"));
		if (merged.isEmpty()) {
			mergeMods(merged, fetchV3TrendingFeed());
		}
		return new ArrayList<>(merged.values());
	}

	public List<NexusMod> searchMods(String rawQuery) throws Exception {
		String query = rawQuery == null ? "" : rawQuery.trim();
		String modId = parseModId(query);
		if (!TextUtils.isEmpty(modId)) {
			List<NexusMod> single = new ArrayList<>();
			single.add(getMod(modId));
			return single;
		}
		LinkedHashMap<String, NexusMod> merged = new LinkedHashMap<>();
		mergeMods(merged, discoverMods());
		mergeMods(merged, fetchUpdatedDetails("1m"));
		if (TextUtils.isEmpty(query)) {
			return new ArrayList<>(merged.values());
		}
		String normalizedQuery = query.toLowerCase(Locale.ROOT);
		List<NexusMod> results = new ArrayList<>();
		for (NexusMod mod : merged.values()) {
			if (mod.searchText().contains(normalizedQuery)) {
				results.add(mod);
			}
		}
		return results;
	}

	public NexusMod getMod(String modId) throws Exception {
		requireApiKey();
		Object value = requestJson(API_BASE_V1 + "/games/" + encodePath(gameDomain) + "/mods/" + encodePath(modId) + ".json", true);
		return parseMod(asObject(value));
	}

	public List<NexusModFile> listFiles(String modId) throws Exception {
		requireApiKey();
		Object value = requestJson(API_BASE_V1 + "/games/" + encodePath(gameDomain) + "/mods/" + encodePath(modId) + "/files.json", true);
		JSONArray filesArray;
		if (value instanceof JSONObject object) {
			filesArray = object.optJSONArray("files");
			if (filesArray == null) {
				filesArray = object.optJSONArray("data");
			}
		} else if (value instanceof JSONArray array) {
			filesArray = array;
		} else {
			filesArray = null;
		}
		List<NexusModFile> files = new ArrayList<>();
		if (filesArray != null) {
			for (int i = 0; i < filesArray.length(); i++) {
				JSONObject fileObject = filesArray.optJSONObject(i);
				if (fileObject != null) {
					NexusModFile file = parseFile(fileObject);
					if (!TextUtils.isEmpty(file.fileId)) {
						files.add(file);
					}
				}
			}
		}
		files.sort((first, second) -> {
			if (first.primary != second.primary) {
				return first.primary ? -1 : 1;
			}
			int categoryCompare = categoryPriority(first.category) - categoryPriority(second.category);
			if (categoryCompare != 0) {
				return categoryCompare;
			}
			return first.name.compareToIgnoreCase(second.name);
		});
		return files;
	}

	public List<DownloadLink> getDownloadLinks(String modId, String fileId) throws Exception {
		return getDownloadLinks(modId, fileId, "", "");
	}

	public List<DownloadLink> getDownloadLinks(String modId, String fileId, String key, String expires) throws Exception {
		requireApiKey();
		StringBuilder url = new StringBuilder(API_BASE_V1)
			.append("/games/").append(encodePath(gameDomain))
			.append("/mods/").append(encodePath(modId))
			.append("/files/").append(encodePath(fileId))
			.append("/download_link.json");
		if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(expires)) {
			url.append("?key=").append(encodeQuery(key)).append("&expires=").append(encodeQuery(expires));
		}
		Object value = requestJson(url.toString(), true);
		JSONArray array;
		if (value instanceof JSONArray jsonArray) {
			array = jsonArray;
		} else if (value instanceof JSONObject object) {
			array = object.optJSONArray("data");
			if (array == null) {
				array = object.optJSONArray("links");
			}
			if (array == null && !TextUtils.isEmpty(object.optString("URI", ""))) {
				array = new JSONArray();
				array.put(object);
			}
		} else {
			array = null;
		}
		List<DownloadLink> links = new ArrayList<>();
		if (array != null) {
			for (int i = 0; i < array.length(); i++) {
				JSONObject object = array.optJSONObject(i);
				if (object == null) {
					continue;
				}
				String uri = firstNonEmpty(object.optString("URI", ""), object.optString("uri", ""), object.optString("url", ""));
				if (!TextUtils.isEmpty(uri)) {
					links.add(new DownloadLink(firstNonEmpty(object.optString("name", ""), object.optString("short_name", ""), "Nexus"), uri));
				}
			}
		}
		return links;
	}

	public File downloadToCache(String downloadUrl, String fallbackFileName, DownloadProgressListener listener) throws Exception {
		File downloadDirectory = new File(context.getCacheDir(), "nexus-mod-downloads");
		if (!downloadDirectory.isDirectory() && !downloadDirectory.mkdirs() && !downloadDirectory.isDirectory()) {
			throw new IOException("Unable to create download cache: " + downloadDirectory.getAbsolutePath());
		}
		HttpURLConnection connection = null;
		try {
			connection = openConnection(downloadUrl, false);
			int responseCode = connection.getResponseCode();
			if (responseCode < 200 || responseCode >= 300) {
				String body = readStream(connection.getErrorStream());
				throw new NexusApiException(responseCode, body, "HTTP " + responseCode + " while downloading MOD file.");
			}
			String fileName = firstNonEmpty(extractContentDispositionFileName(connection.getHeaderField("Content-Disposition")), fileNameFromUrl(downloadUrl), fallbackFileName, "nexus_mod_download.zip");
			fileName = sanitizeFileName(fileName);
			File outputFile = makeUniqueFile(downloadDirectory, fileName);
			long totalBytes = connection.getContentLengthLong();
			try (InputStream inputStream = new BufferedInputStream(connection.getInputStream());
				 OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFile))) {
				byte[] buffer = new byte[64 * 1024];
				long copied = 0L;
				int lastPercent = -1;
				int read;
				while ((read = inputStream.read(buffer)) != -1) {
					outputStream.write(buffer, 0, read);
					copied += read;
					if (listener != null && totalBytes > 0L) {
						int percent = (int) Math.max(0L, Math.min(100L, (copied * 100L) / totalBytes));
						if (percent != lastPercent) {
							lastPercent = percent;
							listener.onProgress(percent, copied, totalBytes);
						}
					}
				}
				outputStream.flush();
			}
			if (listener != null) {
				listener.onProgress(100, outputFile.length(), totalBytes > 0L ? totalBytes : outputFile.length());
			}
			return outputFile;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	public String modPageUrl(String modId) {
		return "https://www.nexusmods.com/" + gameDomain + "/mods/" + modId;
	}

	public String modFilesUrl(String modId) {
		return modPageUrl(modId) + "?tab=files";
	}

	public String searchUrl(String query) {
		String trimmed = query == null ? "" : query.trim();
		if (TextUtils.isEmpty(trimmed)) {
			return "https://www.nexusmods.com/" + gameDomain + "/mods/";
		}
		return "https://www.nexusmods.com/" + gameDomain + "/search/?BH=0&RH_ModList=nav:true,home:false,type:0,user_id:0,game_id:0,advfilt:true,search%5Bfilename%5D:" + encodeQuery(trimmed);
	}

	public String getGameDomain() {
		return gameDomain;
	}

	private List<NexusMod> fetchV1Feed(String feedName) throws Exception {
		requireApiKey();
		Object value = requestJson(API_BASE_V1 + "/games/" + encodePath(gameDomain) + "/mods/" + encodePath(feedName) + ".json", true);
		JSONArray array = asArray(value);
		List<NexusMod> results = new ArrayList<>();
		for (int i = 0; i < array.length(); i++) {
			JSONObject object = array.optJSONObject(i);
			if (object != null) {
				NexusMod mod = parseMod(object);
				if (!TextUtils.isEmpty(mod.modId)) {
					results.add(mod);
				}
			}
		}
		return results;
	}

	private List<NexusMod> fetchV3TrendingFeed() throws Exception {
		Object value = requestJson(API_BASE_V3 + "/games/" + encodePath(gameDomain) + "/trending-mods", false);
		JSONObject root = asObject(value);
		JSONObject data = root.optJSONObject("data");
		JSONArray mods = data == null ? null : data.optJSONArray("mods");
		List<NexusMod> results = new ArrayList<>();
		if (mods == null) {
			return results;
		}
		for (int i = 0; i < mods.length(); i++) {
			JSONObject object = mods.optJSONObject(i);
			if (object == null) {
				continue;
			}
			String pageUrl = object.optString("mod_page_url", "");
			String modId = parseModId(pageUrl);
			results.add(new NexusMod(
				modId,
				firstNonEmpty(object.optString("name", ""), context.getString(R.string.nexus_mod_store_unknown_mod)),
				object.optString("summary", ""),
				"",
				object.optString("author", ""),
				"",
				"",
				object.optString("picture_url", ""),
				pageUrl,
				"",
				"",
				""
			));
		}
		return results;
	}

	private List<NexusMod> fetchUpdatedDetails(String period) throws Exception {
		requireApiKey();
		Object value = requestJson(API_BASE_V1 + "/games/" + encodePath(gameDomain) + "/mods/updated.json?period=" + encodeQuery(period), true);
		JSONArray array = asArray(value);
		List<NexusMod> results = new ArrayList<>();
		int fetched = 0;
		for (int i = 0; i < array.length() && fetched < MAX_UPDATED_DETAILS; i++) {
			JSONObject object = array.optJSONObject(i);
			if (object == null) {
				continue;
			}
			String modId = firstNonEmpty(object.optString("mod_id", ""), object.optString("id", ""), object.optString("game_scoped_id", ""));
			if (TextUtils.isEmpty(modId)) {
				continue;
			}
			try {
				results.add(getMod(modId));
				fetched++;
			} catch (Exception ignored) {
			}
		}
		return results;
	}

	private void mergeMods(LinkedHashMap<String, NexusMod> merged, List<NexusMod> mods) {
		for (NexusMod mod : mods) {
			String key = TextUtils.isEmpty(mod.modId) ? mod.modPageUrl : mod.modId;
			if (TextUtils.isEmpty(key)) {
				key = mod.name;
			}
			if (!TextUtils.isEmpty(key) && !merged.containsKey(key)) {
				merged.put(key, mod);
			}
		}
	}

	private NexusMod parseMod(JSONObject object) {
		String modId = firstNonEmpty(
			object.optString("mod_id", ""),
			object.optString("game_scoped_id", ""),
			object.optString("id", ""),
			object.optString("uid", "")
		);
		String pageUrl = firstNonEmpty(object.optString("mod_page_uri", ""), object.optString("mod_page_url", ""));
		if (TextUtils.isEmpty(pageUrl) && !TextUtils.isEmpty(modId)) {
			pageUrl = modPageUrl(modId);
		}
		return new NexusMod(
			modId,
			firstNonEmpty(object.optString("name", ""), object.optString("title", ""), context.getString(R.string.nexus_mod_store_unknown_mod)),
			firstNonEmpty(object.optString("summary", ""), object.optString("short_description", "")),
			firstNonEmpty(object.optString("description", ""), object.optString("desc", "")),
			firstNonEmpty(object.optString("author", ""), object.optString("uploaded_by", ""), object.optString("user", "")),
			object.optString("version", ""),
			firstNonEmpty(object.optString("category_name", ""), object.optString("category", "")),
			object.optString("picture_url", ""),
			pageUrl,
			formatCount(firstNonEmpty(object.optString("mod_downloads", ""), object.optString("downloads", ""), object.optString("unique_downloads", ""))),
			formatTimestamp(firstNonEmpty(object.optString("updated_timestamp", ""), object.optString("updated_time", ""), object.optString("updated_at", ""))),
			formatTimestamp(firstNonEmpty(object.optString("created_timestamp", ""), object.optString("created_time", ""), object.optString("created_at", "")))
		);
	}

	private NexusModFile parseFile(JSONObject object) {
		String fileId = firstNonEmpty(object.optString("file_id", ""), object.optString("id", ""), object.optString("game_scoped_id", ""));
		String category = firstNonEmpty(object.optString("category_name", ""), object.optString("category", ""));
		boolean primary = object.optBoolean("is_primary", false) || "main".equalsIgnoreCase(category);
		String sizeLabel = readSizeLabel(object);
		return new NexusModFile(
			fileId,
			firstNonEmpty(object.optString("name", ""), object.optString("file_name", ""), context.getString(R.string.nexus_mod_store_unknown_file)),
			object.optString("version", ""),
			category,
			firstNonEmpty(object.optString("description", ""), object.optString("changelog_html", "")),
			sizeLabel,
			formatTimestamp(firstNonEmpty(object.optString("uploaded_timestamp", ""), object.optString("uploaded_time", ""), object.optString("uploaded_at", ""))),
			primary
		);
	}

	private String readSizeLabel(JSONObject object) {
		String directSize = object.optString("size", "");
		if (!TextUtils.isEmpty(directSize) && !directSize.matches("\\d+")) {
			return directSize;
		}
		long bytes = 0L;
		if (object.has("size_kb")) {
			bytes = object.optLong("size_kb", 0L) * 1024L;
		} else if (object.has("size_in_bytes")) {
			bytes = object.optLong("size_in_bytes", 0L);
		} else if (!TextUtils.isEmpty(directSize)) {
			bytes = parseLong(directSize, 0L);
		}
		return bytes > 0L ? Formatter.formatFileSize(context, bytes) : "";
	}

	private int categoryPriority(String category) {
		String normalized = category == null ? "" : category.toLowerCase(Locale.ROOT);
		if (normalized.contains("main")) {
			return 0;
		}
		if (normalized.contains("update")) {
			return 1;
		}
		if (normalized.contains("optional")) {
			return 2;
		}
		if (normalized.contains("misc")) {
			return 3;
		}
		return 4;
	}

	private Object requestJson(String url, boolean authenticated) throws Exception {
		HttpURLConnection connection = null;
		try {
			connection = openConnection(url, authenticated);
			int responseCode = connection.getResponseCode();
			String body = responseCode >= 200 && responseCode < 300 ? readStream(connection.getInputStream()) : readStream(connection.getErrorStream());
			if (responseCode < 200 || responseCode >= 300) {
				throw new NexusApiException(responseCode, body, buildApiErrorMessage(responseCode, body));
			}
			if (TextUtils.isEmpty(body)) {
				return new JSONObject();
			}
			return new JSONTokener(body).nextValue();
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	private HttpURLConnection openConnection(String rawUrl, boolean authenticated) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(rawUrl).openConnection();
		connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
		connection.setReadTimeout(READ_TIMEOUT_MS);
		connection.setInstanceFollowRedirects(true);
		connection.setRequestProperty("Accept", "application/json");
		connection.setRequestProperty("User-Agent", "StS2MobileLauncher/" + BuildConfig.VERSION_NAME + " Android");
		connection.setRequestProperty("Application-Name", "StS2MobileLauncher");
		connection.setRequestProperty("Application-Version", BuildConfig.VERSION_NAME);
		if (authenticated) {
			connection.setRequestProperty("apikey", apiKey);
		}
		return connection;
	}

	private String buildApiErrorMessage(int responseCode, String body) {
		String detail = "";
		try {
			JSONObject object = new JSONObject(body == null ? "" : body);
			detail = firstNonEmpty(object.optString("detail", ""), object.optString("message", ""), object.optString("error", ""), object.optString("title", ""));
		} catch (Exception ignored) {
			detail = body == null ? "" : body.trim();
		}
		if (responseCode == 401 || responseCode == 403) {
			return TextUtils.isEmpty(detail) ? context.getString(R.string.nexus_mod_store_api_forbidden) : detail;
		}
		if (responseCode == 404) {
			return TextUtils.isEmpty(detail) ? context.getString(R.string.nexus_mod_store_api_not_found) : detail;
		}
		return TextUtils.isEmpty(detail) ? "NexusMods API HTTP " + responseCode : detail;
	}

	private void requireApiKey() {
		if (TextUtils.isEmpty(apiKey)) {
			throw new IllegalStateException(context.getString(R.string.nexus_mod_store_api_key_required));
		}
	}

	private JSONObject asObject(Object value) throws Exception {
		if (value instanceof JSONObject object) {
			return object;
		}
		throw new IOException("Expected JSON object from NexusMods API.");
	}

	private JSONArray asArray(Object value) throws Exception {
		if (value instanceof JSONArray array) {
			return array;
		}
		if (value instanceof JSONObject object) {
			JSONArray data = object.optJSONArray("data");
			if (data != null) {
				return data;
			}
			JSONArray mods = object.optJSONArray("mods");
			if (mods != null) {
				return mods;
			}
		}
		return new JSONArray();
	}

	private String parseModId(String text) {
		if (TextUtils.isEmpty(text)) {
			return "";
		}
		String trimmed = text.trim();
		if (trimmed.matches("\\d+")) {
			return trimmed;
		}
		Matcher matcher = MOD_URL_PATTERN.matcher(trimmed);
		if (matcher.find()) {
			String domain = matcher.group(1);
			if (TextUtils.isEmpty(domain) || gameDomain.equalsIgnoreCase(domain)) {
				return matcher.group(2);
			}
			return matcher.group(2);
		}
		return "";
	}

	public static NxmDownloadToken parseNxmLink(String rawLink) {
		if (TextUtils.isEmpty(rawLink)) {
			return null;
		}
		Matcher matcher = NXM_LINK_PATTERN.matcher(rawLink.trim());
		if (!matcher.find()) {
			return null;
		}
		Map<String, String> query = parseQuery(matcher.group(4));
		return new NxmDownloadToken(
			matcher.group(1),
			matcher.group(2),
			matcher.group(3),
			query.getOrDefault("key", ""),
			query.getOrDefault("expires", "")
		);
	}

	private static Map<String, String> parseQuery(String queryString) {
		Map<String, String> result = new LinkedHashMap<>();
		if (TextUtils.isEmpty(queryString)) {
			return result;
		}
		String[] pairs = queryString.split("&");
		for (String pair : pairs) {
			int equals = pair.indexOf('=');
			String key = equals >= 0 ? pair.substring(0, equals) : pair;
			String value = equals >= 0 ? pair.substring(equals + 1) : "";
			result.put(urlDecode(key), urlDecode(value));
		}
		return result;
	}

	private static String urlDecode(String value) {
		try {
			return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8.name());
		} catch (Exception ignored) {
			return value == null ? "" : value;
		}
	}

	private String readStream(InputStream inputStream) throws IOException {
		if (inputStream == null) {
			return "";
		}
		try (InputStream source = inputStream;
			 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = source.read(buffer)) != -1) {
				outputStream.write(buffer, 0, read);
			}
			return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private String extractContentDispositionFileName(String contentDisposition) {
		if (TextUtils.isEmpty(contentDisposition)) {
			return "";
		}
		String[] parts = contentDisposition.split(";");
		for (String part : parts) {
			String trimmed = part.trim();
			if (trimmed.toLowerCase(Locale.ROOT).startsWith("filename*=")) {
				String value = trimmed.substring("filename*=".length()).trim();
				int quoteIndex = value.indexOf("''");
				if (quoteIndex >= 0) {
					value = value.substring(quoteIndex + 2);
				}
				return urlDecode(stripQuotes(value));
			}
			if (trimmed.toLowerCase(Locale.ROOT).startsWith("filename=")) {
				return stripQuotes(trimmed.substring("filename=".length()).trim());
			}
		}
		return "";
	}

	private String fileNameFromUrl(String rawUrl) {
		try {
			String path = new URL(rawUrl).getPath();
			int slash = path.lastIndexOf('/');
			String fileName = slash >= 0 ? path.substring(slash + 1) : path;
			return urlDecode(fileName);
		} catch (Exception ignored) {
			return "";
		}
	}

	private String sanitizeFileName(String input) {
		String sanitized = input == null ? "" : input.replace('\\', '_').replace('/', '_').trim();
		return TextUtils.isEmpty(sanitized) ? "nexus_mod_download.zip" : sanitized;
	}

	private File makeUniqueFile(File directory, String fileName) {
		File candidate = new File(directory, fileName);
		if (!candidate.exists()) {
			return candidate;
		}
		String base = fileName;
		String extension = "";
		int dot = fileName.lastIndexOf('.');
		if (dot > 0) {
			base = fileName.substring(0, dot);
			extension = fileName.substring(dot);
		}
		int suffix = 2;
		while (candidate.exists()) {
			candidate = new File(directory, base + "-" + suffix + extension);
			suffix++;
		}
		return candidate;
	}

	private String stripQuotes(String value) {
		if (value == null) {
			return "";
		}
		String trimmed = value.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1);
		}
		return trimmed;
	}

	private String formatCount(String value) {
		long number = parseLong(value, Long.MIN_VALUE);
		if (number == Long.MIN_VALUE) {
			return value == null ? "" : value;
		}
		return String.format(Locale.getDefault(), "%,d", number);
	}

	private String formatTimestamp(String value) {
		if (TextUtils.isEmpty(value)) {
			return "";
		}
		long timestamp = parseLong(value, Long.MIN_VALUE);
		if (timestamp == Long.MIN_VALUE) {
			return value;
		}
		long millis = timestamp < 100000000000L ? timestamp * 1000L : timestamp;
		return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(millis));
	}

	private long parseLong(String value, long fallback) {
		try {
			return Long.parseLong(value == null ? "" : value.trim());
		} catch (Exception ignored) {
			return fallback;
		}
	}

	private String firstNonEmpty(String... values) {
		for (String value : values) {
			if (!TextUtils.isEmpty(value)) {
				return value.trim();
			}
		}
		return "";
	}

	private String encodePath(String value) {
		return encodeQuery(value).replace("+", "%20");
	}

	private static String encodeQuery(String value) {
		try {
			return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
		} catch (Exception ignored) {
			return value == null ? "" : value;
		}
	}

	public interface DownloadProgressListener {
		void onProgress(int percent, long copiedBytes, long totalBytes);
	}

	public static final class NexusApiException extends IOException {
		public final int responseCode;
		public final String responseBody;

		NexusApiException(int responseCode, String responseBody, String message) {
			super(message);
			this.responseCode = responseCode;
			this.responseBody = responseBody == null ? "" : responseBody;
		}
	}

	public static final class NxmDownloadToken {
		public final String gameDomain;
		public final String modId;
		public final String fileId;
		public final String key;
		public final String expires;

		NxmDownloadToken(String gameDomain, String modId, String fileId, String key, String expires) {
			this.gameDomain = gameDomain == null ? "" : gameDomain;
			this.modId = modId == null ? "" : modId;
			this.fileId = fileId == null ? "" : fileId;
			this.key = key == null ? "" : key;
			this.expires = expires == null ? "" : expires;
		}

		public boolean isComplete() {
			return !TextUtils.isEmpty(modId) && !TextUtils.isEmpty(fileId) && !TextUtils.isEmpty(key) && !TextUtils.isEmpty(expires);
		}
	}

	public static final class NexusMod {
		public final String modId;
		public final String name;
		public final String summary;
		public final String description;
		public final String author;
		public final String version;
		public final String category;
		public final String pictureUrl;
		public final String modPageUrl;
		public final String downloads;
		public final String updatedDate;
		public final String createdDate;

		NexusMod(String modId, String name, String summary, String description, String author, String version, String category, String pictureUrl, String modPageUrl, String downloads, String updatedDate, String createdDate) {
			this.modId = modId == null ? "" : modId;
			this.name = name == null ? "" : name;
			this.summary = summary == null ? "" : summary;
			this.description = description == null ? "" : description;
			this.author = author == null ? "" : author;
			this.version = version == null ? "" : version;
			this.category = category == null ? "" : category;
			this.pictureUrl = pictureUrl == null ? "" : pictureUrl;
			this.modPageUrl = modPageUrl == null ? "" : modPageUrl;
			this.downloads = downloads == null ? "" : downloads;
			this.updatedDate = updatedDate == null ? "" : updatedDate;
			this.createdDate = createdDate == null ? "" : createdDate;
		}

		String searchText() {
			return (modId + " " + name + " " + summary + " " + description + " " + author + " " + version + " " + category + " " + modPageUrl).toLowerCase(Locale.ROOT);
		}
	}

	public static final class NexusModFile {
		public final String fileId;
		public final String name;
		public final String version;
		public final String category;
		public final String description;
		public final String sizeLabel;
		public final String uploadedDate;
		public final boolean primary;

		NexusModFile(String fileId, String name, String version, String category, String description, String sizeLabel, String uploadedDate, boolean primary) {
			this.fileId = fileId == null ? "" : fileId;
			this.name = name == null ? "" : name;
			this.version = version == null ? "" : version;
			this.category = category == null ? "" : category;
			this.description = description == null ? "" : description;
			this.sizeLabel = sizeLabel == null ? "" : sizeLabel;
			this.uploadedDate = uploadedDate == null ? "" : uploadedDate;
			this.primary = primary;
		}
	}

	public static final class DownloadLink {
		public final String name;
		public final String uri;

		DownloadLink(String name, String uri) {
			this.name = name == null ? "" : name;
			this.uri = uri == null ? "" : uri;
		}
	}
}
