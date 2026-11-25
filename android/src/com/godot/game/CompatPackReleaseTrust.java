package com.godot.game;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

/** Fixed discovery endpoints and trust roots for the official compatibility-pack repository. */
public final class CompatPackReleaseTrust {
	public static final String OWNER = "ModinMobileSTS";
	public static final String REPO = "sts2-android-compat";
	public static final String REPOSITORY = OWNER + "/" + REPO;
	public static final String PACK_ID = REPO;
	public static final String ZIP_ASSET_NAME = PACK_ID + ".zip";
	static final String INDEX_NAME = "compat-index.json";
	static final String SIGNATURE_NAME = INDEX_NAME + ".sig";
	private static final String RELEASE_BASE = "https://github.com/" + REPOSITORY + "/releases/";
	public static final String INDEX_URL = RELEASE_BASE + "latest/download/" + INDEX_NAME;
	public static final String SIGNATURE_URL = RELEASE_BASE + "latest/download/" + SIGNATURE_NAME;
	private static final String DOWNLOAD_BASE = RELEASE_BASE + "download/";
	private static final String ZIP_SUFFIX = "/" + ZIP_ASSET_NAME;
	private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

	private CompatPackReleaseTrust() { }

	static Map<String, String> trustedPublicKeys() {
		return Collections.singletonMap("sts2-compat-release-v1", "Zde+E6dIJ4lIIk9sBgmKVhHAdfbPQMvwbmze5ncnDDQ=");
	}

	public static boolean isAllowedOfficialIndexUrl(String url) {
		return INDEX_URL.equals(url) || SIGNATURE_URL.equals(url);
	}
	public static boolean isAllowedOfficialDownloadUrl(String url) {
		return url != null && url.length() > DOWNLOAD_BASE.length() + ZIP_SUFFIX.length()
			&& url.startsWith(DOWNLOAD_BASE) && url.endsWith(ZIP_SUFFIX)
			&& isSafeTag(url.substring(DOWNLOAD_BASE.length(), url.length() - ZIP_SUFFIX.length()));
	}

	static boolean isAllowedOfficialDownloadUrl(String url, String tag) {
		return isSafeTag(tag) && (DOWNLOAD_BASE + tag + ZIP_SUFFIX).equals(url);
	}

	static boolean isAllowedOfficialReleaseUrl(String url, String tag) {
		return isSafeTag(tag) && (RELEASE_BASE + "tag/" + tag).equals(url);
	}

	static boolean isSafeTag(String tag) {
		return tag != null && TAG.matcher(tag).matches();
	}

	public static boolean isAllowedRedirectUrl(HttpUrl url) {
		return url != null && url.isHttps() && url.port() == 443
			&& url.encodedUsername().isEmpty() && url.encodedPassword().isEmpty()
			&& url.fragment() == null && isOfficialHost(url.host());
	}

	static HttpUrl parseRedirect(String location) {
		if (location == null) return null;
		try {
			// HttpUrl normalizes some malformed authorities and loses empty userinfo. Validate raw input first.
			URI uri = new URI(location);
			String host = uri.getHost();
			String authority = uri.getRawAuthority();
			if (!"https".equalsIgnoreCase(uri.getScheme()) || !isOfficialHost(host)
					|| uri.getRawUserInfo() != null || uri.getRawFragment() != null
					|| !(host.equalsIgnoreCase(authority) || (host + ":443").equalsIgnoreCase(authority))) {
				return null;
			}
			HttpUrl parsed = HttpUrl.get(location);
			return isAllowedRedirectUrl(parsed) ? parsed : null;
		} catch (IllegalArgumentException | URISyntaxException ignored) {
			return null;
		}
	}

	private static boolean isOfficialHost(String host) {
		return "github.com".equalsIgnoreCase(host)
			|| "objects.githubusercontent.com".equalsIgnoreCase(host)
			|| "release-assets.githubusercontent.com".equalsIgnoreCase(host);
	}
}
