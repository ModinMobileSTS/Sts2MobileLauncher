package com.godot.game;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExtraSettingsUpdateChecker {
	public static final String DOWNLOAD_URL = "https://pan.quark.cn/s/0eaccff60679#/list/share/d4c45fa0a6db4d17b6ed46e168fecfa1";

	private static final String TAG = "Sts2UpdateChecker";
	private static final String WINDOWS_10_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
	private static final String TOKEN_URL = "https://drive-h.quark.cn/1/clouddrive/share/sharepage/token";
	private static final String DETAIL_URL = "https://drive-h.quark.cn/1/clouddrive/share/sharepage/detail";
	private static final Pattern PWD_PATTERN = Pattern.compile("/s/([^/?#]+)");
	private static final Pattern FID_PATTERN = Pattern.compile("/list/share/([^/?#]+)");

	private ExtraSettingsUpdateChecker() {
	}

	public static UpdateInfo checkForUpdate(Context context) throws Exception {
		ShareUrl shareUrl = parseShareUrl(DOWNLOAD_URL);
		String pwdId = shareUrl.pwdId;
		String pdirFid = shareUrl.pdirFid == null ? "0" : shareUrl.pdirFid;
		JSONObject pageToken = requestShareToken(pwdId);
		String stoken = pageToken.getJSONObject("data").getString("stoken");
		List<UpdateInfo> candidates = new ArrayList<>();
		collectLatestEntries(candidates, requestDetail(pwdId, stoken, "0", true));
		if (!"0".equals(pdirFid)) {
			collectLatestEntries(candidates, requestDetail(pwdId, stoken, pdirFid, false));
		}
		UpdateInfo newest = null;
		for (UpdateInfo candidate : candidates) {
			if (newest == null || candidate.versionCode > newest.versionCode) {
				newest = candidate;
			}
		}
		if (newest == null) {
			Log.d(TAG, "No [最新] entry found in update share.");
			return null;
		}
		long currentVersionCode = BuildConfig.VERSION_CODE;
		Log.d(TAG, "Current versionCode=" + currentVersionCode + ", latest=" + newest.versionName + " code=" + newest.versionCode);
		return currentVersionCode < newest.versionCode ? newest : null;
	}

	private static ShareUrl parseShareUrl(String url) throws Exception {
		URI uri = URI.create(url);
		Matcher pwdMatcher = PWD_PATTERN.matcher(uri.getPath() == null ? "" : uri.getPath());
		if (!pwdMatcher.find()) {
			throw new IllegalArgumentException("Unable to parse pwd_id from " + url);
		}
		String pdirFid = null;
		String fragment = uri.getFragment();
		if (fragment != null) {
			Matcher fidMatcher = FID_PATTERN.matcher(fragment);
			if (fidMatcher.find()) {
				pdirFid = fidMatcher.group(1);
			}
		}
		return new ShareUrl(pwdMatcher.group(1), pdirFid);
	}

	private static JSONObject requestShareToken(String pwdId) throws Exception {
		JSONObject body = new JSONObject();
		body.put("pwd_id", pwdId);
		body.put("passcode", "");
		body.put("support_visit_limit_private_share", true);
		JSONObject response = requestJson(
			"POST",
			TOKEN_URL + "?pr=ucpro&fr=pc&uc_param_str=",
			body.toString(),
			new String[][] {
				{ "Content-Type", "application/json" },
				{ "Origin", "https://pan.quark.cn" },
				{ "Accept", "application/json, text/plain, */*" }
			}
		);
		Log.d(TAG, "token API code=" + response.optInt("code") + ", message=" + response.optString("message") + ", title=" + response.optJSONObject("data"));
		if (response.optInt("code", -1) != 0) {
			throw new IOException("Token API returned code=" + response.optInt("code") + ", message=" + response.optString("message"));
		}
		return response;
	}

	private static JSONObject requestDetail(String pwdId, String stoken, String pdirFid, boolean fetchShare) throws Exception {
		String url = DETAIL_URL
			+ "?pr=ucpro&fr=pc&uc_param_str=&ver=2"
			+ "&pwd_id=" + encode(pwdId)
			+ "&stoken=" + encode(stoken)
			+ "&pdir_fid=" + encode(pdirFid)
			+ "&force=0&_page=1&_size=100"
			+ "&_fetch_banner=" + (fetchShare ? "1" : "0")
			+ "&_fetch_share=" + (fetchShare ? "1" : "0")
			+ "&fetch_total=1&sort=file_type:asc,file_name:asc";
		JSONObject response = requestJson(
			"GET",
			url,
			null,
			new String[][] {
				{ "Accept", "application/json, text/plain, */*" },
				{ "Origin", "https://pan.quark.cn" }
			}
		);
		JSONArray items = response.optJSONObject("data") == null ? null : response.optJSONObject("data").optJSONArray("list");
		Log.d(TAG, "detail API pdir_fid=" + pdirFid + ", code=" + response.optInt("code") + ", count=" + (items == null ? 0 : items.length()));
		if (response.optInt("code", -1) != 0) {
			throw new IOException("Detail API returned code=" + response.optInt("code") + ", message=" + response.optString("message"));
		}
		return response;
	}

	private static void collectLatestEntries(List<UpdateInfo> out, JSONObject detail) {
		JSONObject data = detail.optJSONObject("data");
		JSONArray items = data == null ? null : data.optJSONArray("list");
		if (items == null) {
			return;
		}
		for (int i = 0; i < items.length(); i++) {
			JSONObject item = items.optJSONObject(i);
			if (item == null) {
				continue;
			}
			UpdateInfo info = parseUpdateEntry(item.optString("file_name", ""));
			if (info != null) {
				out.add(info);
			}
		}
	}

	private static UpdateInfo parseUpdateEntry(String fileName) {
		if (fileName == null || !fileName.startsWith("[最新]")) {
			return null;
		}
		String[] parts = fileName.split("-", 4);
		if (parts.length != 4) {
			Log.d(TAG, "Skip malformed latest entry: " + fileName);
			return null;
		}
		try {
			long versionCode = Long.parseLong(parts[3].trim().replaceAll("[^0-9]", ""));
			return new UpdateInfo(parts[1].trim(), parts[2].trim(), versionCode);
		} catch (Exception exception) {
			Log.d(TAG, "Skip latest entry with invalid code: " + fileName, exception);
			return null;
		}
	}

	private static JSONObject requestJson(String method, String url, String body, String[][] extraHeaders) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		connection.setRequestMethod(method);
		connection.setConnectTimeout(30000);
		connection.setReadTimeout(30000);
		connection.setRequestProperty("User-Agent", WINDOWS_10_UA);
		connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
		connection.setRequestProperty("Referer", DOWNLOAD_URL);
		for (String[] header : extraHeaders) {
			connection.setRequestProperty(header[0], header[1]);
		}
		if (body != null) {
			connection.setDoOutput(true);
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			connection.setFixedLengthStreamingMode(bytes.length);
			try (OutputStream stream = connection.getOutputStream()) {
				stream.write(bytes);
			}
		}
		int status = connection.getResponseCode();
		String text;
		try (InputStream stream = new BufferedInputStream(status >= 400 ? nonNullErrorStream(connection) : connection.getInputStream())) {
			text = readAll(stream);
		}
		if (status < 200 || status >= 300) {
			throw new IOException("HTTP " + status + " from " + url + ": " + text);
		}
		return new JSONObject(text);
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

	private static String encode(String value) throws Exception {
		return URLEncoder.encode(value, "UTF-8");
	}

	private static final class ShareUrl {
		final String pwdId;
		final String pdirFid;

		ShareUrl(String pwdId, String pdirFid) {
			this.pwdId = pwdId;
			this.pdirFid = pdirFid;
		}
	}

	public static final class UpdateInfo {
		public final String versionName;
		public final String changelog;
		public final long versionCode;

		UpdateInfo(String versionName, String changelog, long versionCode) {
			this.versionName = versionName;
			this.changelog = changelog;
			this.versionCode = versionCode;
		}
	}
}
