package com.godot.game.webdav;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilderFactory;

public final class WebDavClient {
	private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");
	private static final MediaType XML = MediaType.get("application/xml; charset=utf-8");
	private static final int MAX_PROPFIND_DEPTH = 8;

	private final OkHttpClient http;
	private final HttpUrl rootUrl;
	private final String authorizationHeader;

	public WebDavClient(WebDavSettings.Config config) {
		if (config == null || config.baseUrl.trim().isEmpty()) {
			throw new IllegalStateException("WebDAV URL is not configured.");
		}
		HttpUrl parsed = HttpUrl.parse(config.baseUrl.trim());
		if (parsed == null) {
			throw new IllegalArgumentException("Invalid WebDAV URL: " + config.baseUrl);
		}
		this.rootUrl = ensureDirectoryUrl(parsed);
		this.authorizationHeader = config.username.trim().isEmpty() && config.password.isEmpty() ? "" : Credentials.basic(config.username, config.password, StandardCharsets.UTF_8);
		this.http = new OkHttpClient.Builder()
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.writeTimeout(60, TimeUnit.SECONDS)
			.followRedirects(true)
			.followSslRedirects(true)
			.build();
	}

	public String getRootUrl() {
		return rootUrl.toString();
	}

	public void ensureDirectory(String relativeDir) throws Exception {
		String normalized = normalizeDir(relativeDir);
		if (normalized.isEmpty()) {
			return;
		}
		String[] parts = normalized.split("/");
		StringBuilder current = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			if (current.length() > 0) {
				current.append('/');
			}
			current.append(part);
			mkcolIfNeeded(current + "/");
		}
	}

	public List<RemoteResource> listFiles(String relativeDir) throws Exception {
		String root = normalizeDir(relativeDir);
		List<RemoteResource> entries = new ArrayList<>();
		listFilesRecursive(root, root, 0, entries, new HashSet<>());
		entries.sort((a, b) -> a.relativePath.compareToIgnoreCase(b.relativePath));
		return entries;
	}

	public byte[] readFileBytes(String relativePath) throws Exception {
		Request request = requestBuilder(relativePath).get().build();
		try (Response response = http.newCall(request).execute()) {
			if (response.code() == 404) {
				return null;
			}
			requireSuccess(response, "read " + relativePath);
			ResponseBody body = response.body();
			if (body == null) {
				return new byte[0];
			}
			try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				copy(input, output);
				return output.toByteArray();
			}
		}
	}

	public void downloadFile(String relativePath, File out) throws Exception {
		Request request = requestBuilder(relativePath).get().build();
		try (Response response = http.newCall(request).execute()) {
			requireSuccess(response, "download " + relativePath);
			ResponseBody body = response.body();
			if (body == null) {
				throw new IOException("WebDAV download returned no body: " + relativePath);
			}
			File parent = out.getParentFile();
			if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
				throw new IOException("Unable to create directory: " + parent.getAbsolutePath());
			}
			try (InputStream input = new BufferedInputStream(body.byteStream()); OutputStream output = new BufferedOutputStream(new FileOutputStream(out))) {
				copy(input, output);
			}
		}
	}

	public void uploadFile(String relativePath, File file) throws Exception {
		String parent = parentDir(relativePath);
		if (!parent.isEmpty()) {
			ensureDirectory(parent);
		}
		RequestBody body = RequestBody.create(file, OCTET_STREAM);
		Request request = requestBuilder(relativePath).put(body).build();
		try (Response response = http.newCall(request).execute()) {
			requireSuccess(response, "upload " + relativePath);
		}
	}

	public void writeTextFile(String relativePath, String content) throws Exception {
		String parent = parentDir(relativePath);
		if (!parent.isEmpty()) {
			ensureDirectory(parent);
		}
		RequestBody body = RequestBody.create(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8), OCTET_STREAM);
		Request request = requestBuilder(relativePath).put(body).build();
		try (Response response = http.newCall(request).execute()) {
			requireSuccess(response, "write " + relativePath);
		}
	}

	public void testConnection(String relativeDir) throws Exception {
		ensureDirectory(relativeDir);
		Request request = requestBuilder(relativeDir).method("PROPFIND", RequestBody.create("<?xml version=\"1.0\" encoding=\"utf-8\" ?><propfind xmlns=\"DAV:\"><prop><resourcetype/></prop></propfind>", XML)).header("Depth", "0").build();
		try (Response response = http.newCall(request).execute()) {
			requireSuccess(response, "test WebDAV directory");
		}
	}

	private void listFilesRecursive(String rootDir, String currentDir, int depth, List<RemoteResource> entries, Set<String> visited) throws Exception {
		if (depth > MAX_PROPFIND_DEPTH) {
			throw new IOException("WebDAV directory nesting is too deep under " + rootDir);
		}
		String normalizedCurrent = normalizeDir(currentDir);
		String visitKey = normalizedCurrent.toLowerCase(Locale.ROOT);
		if (!visited.add(visitKey)) {
			return;
		}
		Request request = requestBuilder(normalizedCurrent).method("PROPFIND", RequestBody.create("<?xml version=\"1.0\" encoding=\"utf-8\" ?><propfind xmlns=\"DAV:\"><prop><resourcetype/><getcontentlength/><getlastmodified/><getetag/></prop></propfind>", XML)).header("Depth", "1").build();
		try (Response response = http.newCall(request).execute()) {
			if (response.code() == 404) {
				return;
			}
			requireSuccess(response, "list " + normalizedCurrent);
			ResponseBody body = response.body();
			if (body == null) {
				return;
			}
			String xml = body.string();
			for (PropfindEntry entry : parsePropfind(xml, normalizedCurrent)) {
				String path = normalizePath(entry.relativePath);
				if (path.isEmpty() || path.equals(normalizedCurrent) || path.equals(trimTrailingSlash(normalizedCurrent))) {
					continue;
				}
				if (entry.directory) {
					if (isManifestDir(path)) {
						continue;
					}
					listFilesRecursive(rootDir, path, depth + 1, entries, visited);
				} else {
					String relativeToRoot = relativize(rootDir, path);
					if (!relativeToRoot.isEmpty()) {
						entries.add(new RemoteResource(relativeToRoot, path, entry.size, entry.lastModifiedMs, entry.etag));
					}
				}
			}
		}
	}

	private List<PropfindEntry> parsePropfind(String xml, String requestedDir) throws Exception {
		List<PropfindEntry> entries = new ArrayList<>();
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		try {
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		} catch (Exception ignored) {
		}
		Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
		NodeList responses = document.getElementsByTagNameNS("DAV:", "response");
		for (int i = 0; i < responses.getLength(); i++) {
			Element response = (Element) responses.item(i);
			String href = textOf(response, "href");
			String relativePath = hrefToRelativePath(href);
			if (relativePath.isEmpty() && !normalizeDir(requestedDir).isEmpty()) {
				relativePath = normalizeDir(requestedDir);
			}
			boolean directory = hasCollection(response);
			long size = parseLong(textOf(response, "getcontentlength"), -1L);
			long lastModified = parseHttpDateMs(textOf(response, "getlastmodified"));
			String etag = cleanEtag(textOf(response, "getetag"));
			entries.add(new PropfindEntry(relativePath, directory, size, lastModified, etag));
		}
		return entries;
	}

	private String hrefToRelativePath(String href) {
		if (href == null || href.trim().isEmpty()) {
			return "";
		}
		try {
			URI hrefUri = URI.create(href.trim());
			String hrefPath = hrefUri.getPath() == null ? href.trim() : hrefUri.getPath();
			String basePath = rootUrl.uri().getPath();
			String normalizedHref = normalizeDecodedPath(hrefPath);
			String normalizedBase = normalizeDecodedPath(basePath);
			if (!normalizedBase.endsWith("/")) {
				normalizedBase += "/";
			}
			if (normalizedHref.equals(trimTrailingSlash(normalizedBase))) {
				return "";
			}
			if (normalizedHref.startsWith(normalizedBase)) {
				return normalizePath(normalizedHref.substring(normalizedBase.length()));
			}
			return normalizePath(normalizedHref);
		} catch (Exception exception) {
			return normalizePath(href);
		}
	}

	private String normalizeDecodedPath(String path) {
		try {
			return normalizePath(URI.create("x://h" + (path == null || path.startsWith("/") ? path : "/" + path)).getPath());
		} catch (Exception ignored) {
			return normalizePath(path);
		}
	}

	private boolean hasCollection(Element response) {
		NodeList collections = response.getElementsByTagNameNS("DAV:", "collection");
		return collections != null && collections.getLength() > 0;
	}

	private String textOf(Element response, String localName) {
		NodeList nodes = response.getElementsByTagNameNS("DAV:", localName);
		if (nodes == null || nodes.getLength() == 0) {
			return "";
		}
		Node node = nodes.item(0);
		return node == null || node.getTextContent() == null ? "" : node.getTextContent().trim();
	}

	private void mkcolIfNeeded(String relativeDir) throws Exception {
		Request request = requestBuilder(relativeDir).method("MKCOL", RequestBody.create(new byte[0], null)).build();
		try (Response response = http.newCall(request).execute()) {
			int code = response.code();
			if (code == 201 || code == 200 || code == 204 || code == 405) {
				return;
			}
			requireSuccess(response, "create directory " + relativeDir);
		}
	}

	private Request.Builder requestBuilder(String relativePath) {
		Request.Builder builder = new Request.Builder().url(resolve(relativePath));
		if (!authorizationHeader.isEmpty()) {
			builder.header("Authorization", authorizationHeader);
		}
		return builder;
	}

	private HttpUrl resolve(String relativePath) {
		String normalized = normalizePath(relativePath);
		String[] parts = normalized.isEmpty() ? new String[0] : normalized.split("/");
		HttpUrl.Builder builder = rootUrl.newBuilder();
		for (String part : parts) {
			if (!part.isEmpty()) {
				builder.addPathSegment(part);
			}
		}
		if (relativePath != null && relativePath.endsWith("/")) {
			builder.addPathSegment("");
		}
		return builder.build();
	}

	private void requireSuccess(Response response, String operation) throws IOException {
		if (response.isSuccessful() || response.code() == 207) {
			return;
		}
		String body = "";
		ResponseBody responseBody = response.body();
		if (responseBody != null) {
			try {
				body = responseBody.string();
			} catch (Exception ignored) {
			}
		}
		throw new IOException("WebDAV " + operation + " failed: HTTP " + response.code() + " " + response.message() + (body.isEmpty() ? "" : " - " + truncate(body, 240)));
	}

	private static HttpUrl ensureDirectoryUrl(HttpUrl url) {
		String value = url.toString();
		if (!value.endsWith("/")) {
			HttpUrl withSlash = HttpUrl.parse(value + "/");
			return withSlash == null ? url : withSlash;
		}
		return url;
	}

	private static String normalizeDir(String path) {
		String normalized = normalizePath(path);
		if (!normalized.isEmpty() && !normalized.endsWith("/")) {
			normalized += "/";
		}
		return normalized;
	}

	static String normalizePath(String path) {
		String value = path == null ? "" : path.trim().replace('\\', '/');
		while (value.startsWith("/")) {
			value = value.substring(1);
		}
		while (value.contains("//")) {
			value = value.replace("//", "/");
		}
		return value;
	}

	private static String trimTrailingSlash(String value) {
		String result = value == null ? "" : value;
		while (result.endsWith("/")) {
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}

	private static String parentDir(String relativePath) {
		String normalized = normalizePath(relativePath);
		int index = normalized.lastIndexOf('/');
		return index < 0 ? "" : normalized.substring(0, index + 1);
	}

	private static String relativize(String rootDir, String path) {
		String root = normalizeDir(rootDir);
		String normalized = normalizePath(path);
		if (root.isEmpty()) {
			return normalized;
		}
		if (normalized.equals(trimTrailingSlash(root))) {
			return "";
		}
		return normalized.startsWith(root) ? normalized.substring(root.length()) : "";
	}

	private static boolean isManifestDir(String path) {
		String normalized = normalizePath(path).toLowerCase(Locale.ROOT);
		return normalized.equals(".sts2re") || normalized.contains("/.sts2re/") || normalized.endsWith("/.sts2re");
	}

	private static long parseLong(String value, long fallback) {
		try {
			return Long.parseLong(value == null ? "" : value.trim());
		} catch (Exception ignored) {
			return fallback;
		}
	}

	private static long parseHttpDateMs(String value) {
		String text = value == null ? "" : value.trim();
		if (text.isEmpty()) {
			return 0L;
		}
		try {
			SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
			format.setTimeZone(TimeZone.getTimeZone("GMT"));
			Date date = format.parse(text);
			return date == null ? 0L : date.getTime();
		} catch (Exception ignored) {
			return 0L;
		}
	}

	private static String cleanEtag(String etag) {
		String value = etag == null ? "" : etag.trim();
		if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
			value = value.substring(1, value.length() - 1);
		}
		return value;
	}

	private static String truncate(String value, int max) {
		String text = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
		return text.length() <= max ? text : text.substring(0, max) + "…";
	}

	private static void copy(InputStream input, OutputStream output) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = input.read(buffer)) != -1) {
			output.write(buffer, 0, read);
		}
	}

	private static final class PropfindEntry {
		final String relativePath;
		final boolean directory;
		final long size;
		final long lastModifiedMs;
		final String etag;

		PropfindEntry(String relativePath, boolean directory, long size, long lastModifiedMs, String etag) {
			this.relativePath = relativePath == null ? "" : relativePath;
			this.directory = directory;
			this.size = size;
			this.lastModifiedMs = lastModifiedMs;
			this.etag = etag == null ? "" : etag;
		}
	}

	public static final class RemoteResource {
		public final String relativePath;
		public final String fullRelativePath;
		public final long size;
		public final long lastModifiedMs;
		public final String etag;

		RemoteResource(String relativePath, String fullRelativePath, long size, long lastModifiedMs, String etag) {
			this.relativePath = relativePath == null ? "" : relativePath;
			this.fullRelativePath = fullRelativePath == null ? "" : fullRelativePath;
			this.size = size;
			this.lastModifiedMs = lastModifiedMs;
			this.etag = etag == null ? "" : etag;
		}
	}
}
