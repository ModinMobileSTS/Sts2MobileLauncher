package com.godot.game;

import android.content.Context;
import android.os.CancellationSignal;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Official release discovery and installation. All network and install methods are blocking. */
public final class CompatPackReleaseRepository {
	private static final String TAG = "Sts2CompatReleases";
	private static final long CHECK_INTERVAL_SECONDS = TimeUnit.HOURS.toSeconds(6);
	private static final int MAX_REDIRECTS = 5;
	private static final int MAX_METADATA_BYTES = 8 * 1024;
	private static final int MAX_ETAG_LENGTH = 1024;
	private static final String GENERATION_PREFIX = "snapshot-";
	private static final Object CACHE_LOCK = new Object();
	private static final Object FETCH_LOCK = new Object();
	// Process-wide so recreating the Activity does not repeatedly hit GitHub after failed checks.
	private static File failedAttemptRoot;
	private static long failedAttemptAt;
	private final CompatPackManager packs;
	private final CompatPackReleaseVerifier verifier;
	private final Call.Factory transport;
	private final File cacheRoot;
	private final File importRoot;
	private final AtomicFile cacheMetadata;
	private final LongSupplier unixSeconds;

	private static final class DirectTransport {
		private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
			// Honor the system proxy without rewriting the official HTTPS URLs.
			.cookieJar(CookieJar.NO_COOKIES)
			.followRedirects(false)
			.followSslRedirects(false)
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(45, TimeUnit.SECONDS)
			.build();
	}

	public CompatPackReleaseRepository(Context context) {
		this(context, DirectTransport.CLIENT, new CompatPackReleaseVerifier(), () -> System.currentTimeMillis() / 1000L);
	}

	CompatPackReleaseRepository(Context context, Call.Factory transport, CompatPackReleaseVerifier verifier, LongSupplier unixSeconds) {
		packs = new CompatPackManager(context.getApplicationContext());
		File files = context.getFilesDir();
		cacheRoot = new File(files, "launcher/compat-release-cache");
		cacheMetadata = new AtomicFile(new File(cacheRoot, "metadata.json"));
		importRoot = new File(files, "compat_pack_import");
		this.transport = transport;
		this.verifier = verifier;
		this.unixSeconds = unixSeconds;
	}

	public CompatPackReleaseIndex getCachedIndex() {
		CacheSnapshot cached = readCache();
		return cached == null ? null : cached.index;
	}

	public long getLastCheckedUnix() {
		CacheSnapshot cached = readCache();
		return cached == null ? 0L : cached.fetchedAt;
	}

	public static final class CachedIndex {
		public final CompatPackReleaseIndex index;
		public final long lastCheckedUnix;

		private CachedIndex(CompatPackReleaseIndex index, long lastCheckedUnix) {
			this.index = index;
			this.lastCheckedUnix = lastCheckedUnix;
		}
	}

	public CachedIndex getCachedState() {
		CacheSnapshot cached = readCache();
		return cached == null ? new CachedIndex(null, 0L) : new CachedIndex(cached.index, cached.fetchedAt);
	}

	public CompatPackReleaseIndex fetchIndexIfNeeded(boolean forceNetwork) throws IOException {
		verifier.requireConfiguredTrust();
		synchronized (FETCH_LOCK) {
			CacheSnapshot cached = readCache();
			long now = unixSeconds.getAsLong();
			if (!forceNetwork && ((cached != null && withinCooldown(cached.fetchedAt, now))
					|| (cacheRoot.equals(failedAttemptRoot) && withinCooldown(failedAttemptAt, now)))) {
				return cached == null ? null : cached.index;
			}
			try {
				CompatPackReleaseIndex result = fetchIndex(cached);
				if (cacheRoot.equals(failedAttemptRoot)) failedAttemptRoot = null;
				return result;
			} catch (IOException e) {
				failedAttemptRoot = cacheRoot;
				failedAttemptAt = unixSeconds.getAsLong();
				throw e;
			}
		}
	}

	private static boolean withinCooldown(long checkedAt, long now) {
		return checkedAt <= now && now - checkedAt < CHECK_INTERVAL_SECONDS;
	}

	private CompatPackReleaseIndex fetchIndex(CacheSnapshot cached) throws IOException {
		return withOfficialResponse(CompatPackReleaseTrust.INDEX_URL, false,
			cached == null ? "" : cached.etag, null, response -> {
				if (response.code() == 404) {
					writeCache(null, null, "", 404, "", "");
					return null;
				}
				if (response.code() == 304) {
					if (cached == null || cached.index == null || cached.etag.isEmpty()) {
						throw new IOException("Compatibility index HTTP 304 without a verified conditional cache");
					}
					// Re-read before renewal: no 304 may bless bytes changed since the conditional request.
					CacheSnapshot current = readCache();
					if (current == null || current.index == null || !cached.generation.equals(current.generation)
							|| !cached.etag.equals(current.etag) || !cached.index.payloadSha256.equals(current.index.payloadSha256)) {
						throw new IOException("Compatibility index cache changed during revalidation");
					}
					String newEtag = safeEtag(response.header("ETag"));
					writeCache(null, null, newEtag.isEmpty() ? current.etag : newEtag, 304,
						current.generation, current.index.payloadSha256);
					return current.index;
				}
				requireOk(response);
				byte[] indexBytes = readBody(response, CompatPackReleaseVerifier.MAX_INDEX_BYTES);
				byte[] signatureBytes = withOfficialResponse(CompatPackReleaseTrust.SIGNATURE_URL, false, "", null, signature -> {
					requireOk(signature);
					return readBody(signature, CompatPackReleaseVerifier.MAX_SIGNATURE_BYTES);
				});
				CompatPackReleaseIndex verified = verifier.verify(indexBytes, signatureBytes);
				writeCache(indexBytes, signatureBytes, safeEtag(response.header("ETag")), 200, "", verified.payloadSha256);
				return verified;
			});
	}

	public interface ProgressListener {
		void onProgress(long downloaded, long total);
	}

	/** Explicit history selections are permitted; recommendation methods never offer a downgrade. */
	public CompatPackManager.CompatPack downloadAndInstallRelease(CompatPackRelease release,
			ProgressListener listener, CancellationSignal cancellation) throws Exception {
		throwIfCanceled(cancellation);
		if (!release.isInstallable(BuildConfig.VERSION_CODE)) {
			throw new CompatPackManager.LauncherUpdateRequiredException(release.minLauncherVersionCode,
				release.minLauncherVersionName, release.manifestSchema);
		}
		ensureDirectory(importRoot);
		File partial = new File(importRoot, "remote-" + UUID.randomUUID() + ".zip.part");
		try {
			withOfficialResponse(release.zipDownloadUrl, true, "", cancellation, response -> {
				requireOk(response);
				ResponseBody body = checkedBody(response, release.zipSizeBytes);
				if (body.contentLength() != -1L && body.contentLength() != release.zipSizeBytes) {
					throw new IOException("Declared compatibility ZIP size differs from signed size");
				}
				try (InputStream input = body.byteStream(); OutputStream output = new FileOutputStream(partial)) {
					if (listener != null) listener.onProgress(0L, release.zipSizeBytes);
					long copied = copyBounded(input, output, release.zipSizeBytes, listener, cancellation);
					if (copied != release.zipSizeBytes) throw new IOException("Downloaded compatibility ZIP size differs from signed size");
				}
				return null;
			});
			throwIfCanceled(cancellation);
			return packs.installFromVerifiedDownload(partial, release.zipAssetName, release, cancellation);
		} finally {
			if (partial.exists() && !partial.delete()) Log.w(TAG, "Could not remove partial compatibility download");
		}
	}

	@FunctionalInterface
	private interface ResponseReader<T> {
		T read(Response response) throws IOException;
	}

	private <T> T withOfficialResponse(String initialUrl, boolean zip, String etag, CancellationSignal signal,
			ResponseReader<T> reader) throws IOException {
		if (!(zip ? CompatPackReleaseTrust.isAllowedOfficialDownloadUrl(initialUrl)
				: CompatPackReleaseTrust.isAllowedOfficialIndexUrl(initialUrl))) {
			throw new IOException("Invalid official compatibility release endpoint");
		}
		HttpUrl url = HttpUrl.get(initialUrl);
		for (int redirects = 0; ; redirects++) {
			throwIfCanceled(signal);
			Request.Builder request = new Request.Builder().url(url).header("Accept-Encoding", "identity");
			if (!etag.isEmpty()) request.header("If-None-Match", etag);
			Call call = transport.newCall(request.build());
			if (signal != null) signal.setOnCancelListener(call::cancel);
			try (Response response = call.execute()) {
				throwIfCanceled(signal);
				if (!isRedirect(response.code())) return reader.read(response);
				if (redirects >= MAX_REDIRECTS) throw new IOException("Too many compatibility release redirects");
				List<String> locations = response.headers("Location");
				url = locations.size() == 1 ? CompatPackReleaseTrust.parseRedirect(locations.get(0)) : null;
				if (url == null) throw new IOException("Disallowed compatibility release redirect");
			} catch (IOException e) {
				throwIfCanceled(signal);
				throw e;
			} finally {
				// Close the response before detaching: Call.cancel() remains live during body reads.
				if (signal != null) signal.setOnCancelListener(null);
			}
		}
	}

	private static boolean isRedirect(int code) {
		return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
	}

	private static void requireOk(Response response) throws IOException {
		if (response.code() != 200) throw new IOException("Official compatibility release HTTP " + response.code());
	}

	private static ResponseBody checkedBody(Response response, long limit) throws IOException {
		ResponseBody body = response.body();
		String encoding = response.header("Content-Encoding");
		if (body == null || body.contentLength() > limit || (encoding != null && !"identity".equalsIgnoreCase(encoding))) {
			throw new IOException("Invalid compatibility release response body or size");
		}
		return body;
	}

	private static byte[] readBody(Response response, int limit) throws IOException {
		try (InputStream input = checkedBody(response, limit).byteStream()) {
			return readBounded(input, limit);
		}
	}

	private static byte[] readBounded(InputStream input, int limit) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		copyBounded(input, out, limit, null, null);
		return out.toByteArray();
	}

	private static long copyBounded(InputStream in, OutputStream out, long limit, ProgressListener listener,
			CancellationSignal cancellation) throws IOException {
		byte[] buffer = new byte[16 * 1024];
		long copied = 0L;
		for (;;) {
			throwIfCanceled(cancellation);
			int count = in.read(buffer, 0, (int) Math.min(buffer.length, limit - copied + 1L));
			if (count == -1) return copied;
			throwIfCanceled(cancellation);
			if (count > limit - copied) throw new IOException("Compatibility release body exceeds its size limit");
			out.write(buffer, 0, count);
			copied += count;
			if (listener != null) listener.onProgress(copied, limit);
		}
	}

	private static void throwIfCanceled(CancellationSignal signal) {
		if (signal != null) signal.throwIfCanceled();
	}

	private static String safeEtag(String value) {
		if (value == null || value.length() > MAX_ETAG_LENGTH) return "";
		int quote = value.startsWith("W/") ? 2 : 0;
		if (value.length() < quote + 2 || value.charAt(quote) != '"' || value.charAt(value.length() - 1) != '"') return "";
		for (int i = quote + 1; i < value.length() - 1; i++) {
			char c = value.charAt(i);
			if (c <= 0x20 || c == '"' || c >= 0x7f) return "";
		}
		return value;
	}

	private static final class CacheSnapshot {
		final CompatPackReleaseIndex index;
		final long fetchedAt;
		final String etag;
		final String generation;

		CacheSnapshot(CompatPackReleaseIndex index, long fetchedAt, String etag, String generation) {
			this.index = index;
			this.fetchedAt = fetchedAt;
			this.etag = etag;
			this.generation = generation;
		}
	}

	private CacheSnapshot readCache() {
		synchronized (CACHE_LOCK) {
			try (FileInputStream input = cacheMetadata.openRead()) {
				JSONObject meta = new JSONObject(new String(readBounded(input, MAX_METADATA_BYTES), StandardCharsets.UTF_8));
				long fetchedAt = meta.getLong("fetched_at_unix");
				if (fetchedAt < 0L) return null;
				int status = meta.getInt("last_status");
				if (status == 404) return new CacheSnapshot(null, fetchedAt, "", "");
				if (status != 200 && status != 304) return null;
				String generation = meta.getString("generation");
				if (!UUID.fromString(generation).toString().equals(generation)) return null;
				File root = new File(cacheRoot, GENERATION_PREFIX + generation);
				byte[] index = readFile(new File(root, CompatPackReleaseTrust.INDEX_NAME), CompatPackReleaseVerifier.MAX_INDEX_BYTES);
				byte[] signature = readFile(new File(root, CompatPackReleaseTrust.SIGNATURE_NAME), CompatPackReleaseVerifier.MAX_SIGNATURE_BYTES);
				CompatPackReleaseIndex verified = verifier.verify(index, signature);
				if (!verified.payloadSha256.equals(meta.getString("index_sha256"))) return null;
				return new CacheSnapshot(verified, fetchedAt, safeEtag(meta.optString("etag")), generation);
			} catch (IOException | IllegalArgumentException | JSONException ignored) {
				return null;
			}
		}
	}

	private static byte[] readFile(File file, int limit) throws IOException {
		try (FileInputStream input = new FileInputStream(file)) {
			return readBounded(input, limit);
		}
	}

	private static void ensureDirectory(File directory) throws IOException {
		if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
			throw new IOException("Cannot create compatibility release directory: " + directory.getName());
		}
	}

	/** Commit a generation by replacing a small atomic pointer after both raw files are durable. */
	private void writeCache(byte[] index, byte[] signature, String etag, int status, String generation, String rawSha256) throws IOException {
		synchronized (CACHE_LOCK) {
			ensureDirectory(cacheRoot);
			File newRoot = null;
			FileOutputStream output = null;
			boolean committed = false;
			try {
				if (index != null) {
					generation = UUID.randomUUID().toString();
					newRoot = new File(cacheRoot, GENERATION_PREFIX + generation);
					ensureDirectory(newRoot);
					writeFile(new File(newRoot, CompatPackReleaseTrust.INDEX_NAME), index);
					writeFile(new File(newRoot, CompatPackReleaseTrust.SIGNATURE_NAME), signature);
				}
				JSONObject meta = new JSONObject().put("fetched_at_unix", unixSeconds.getAsLong()).put("etag", etag)
					.put("last_status", status).put("generation", generation).put("index_sha256", rawSha256);
				output = cacheMetadata.startWrite();
				byte[] metadataBytes = meta.toString().getBytes(StandardCharsets.UTF_8);
				output.write(metadataBytes);
				cacheMetadata.finishWrite(output);
				output = null;
				committed = true;
				// Android's AtomicFile logs rename errors rather than returning them. Keep the old
				// generation until we can observe the committed metadata.
				try (FileInputStream persisted = cacheMetadata.openRead()) {
					if (!java.util.Arrays.equals(metadataBytes, readBounded(persisted, MAX_METADATA_BYTES))) {
						throw new IOException("Cannot commit compatibility release cache metadata");
					}
				}
			} catch (JSONException e) {
				throw new IOException("Cannot serialize compatibility release cache metadata", e);
			} finally {
				if (output != null) cacheMetadata.failWrite(output);
				if (!committed && newRoot != null) removeCacheGeneration(newRoot);
			}
			File[] children = cacheRoot.listFiles();
			if (children != null) for (File child : children) {
				String name = child.getName();
				if (name.startsWith(GENERATION_PREFIX) && !name.equals(GENERATION_PREFIX + generation)) removeCacheGeneration(child);
			}
		}
	}

	private static void writeFile(File file, byte[] bytes) throws IOException {
		try (FileOutputStream output = new FileOutputStream(file)) {
			output.write(bytes);
			output.getFD().sync();
		}
	}

	private static void removeCacheGeneration(File root) {
		try {
			File lexicalInCanonicalParent = new File(root.getParentFile().getCanonicalFile(), root.getName());
			if (!lexicalInCanonicalParent.equals(lexicalInCanonicalParent.getCanonicalFile())) {
				root.delete();
				return;
			}
		} catch (IOException ignored) {
			return;
		}
		new File(root, CompatPackReleaseTrust.INDEX_NAME).delete();
		new File(root, CompatPackReleaseTrust.SIGNATURE_NAME).delete();
		root.delete();
	}

	public static final class RemoteMatch {
		public final CompatPackRelease release;
		public final CompatPackReleaseTarget target;
		private final int score;

		private RemoteMatch(CompatPackRelease release, CompatPackReleaseTarget target, int score) {
			this.release = release;
			this.target = target;
			this.score = score;
		}
	}

	public static final class PayloadRecommendation {
		public final RemoteMatch installable;
		public final RemoteMatch requiresLauncherUpdate;

		private PayloadRecommendation(RemoteMatch installable, RemoteMatch requiresLauncherUpdate) {
			this.installable = installable;
			this.requiresLauncherUpdate = requiresLauncherUpdate;
		}
	}

	public PayloadRecommendation findBestRemoteReleaseForPayload(CompatPackReleaseIndex index, JSONObject payloadManifest,
			int launcherVersionCode, List<CompatPackManager.CompatPack> installedPacks) {
		if (index == null || payloadManifest == null) return new PayloadRecommendation(null, null);
		String version = packs.getPayloadVersion(payloadManifest);
		String sha = packs.getPayloadSts2DllSha256(payloadManifest);
		RemoteMatch installable = null;
		for (CompatPackRelease release : index.releases) {
			if (!release.isInstallable(launcherVersionCode) || !newerThanInstalled(release, installedPacks)) continue;
			RemoteMatch match = findBestMatch(release, version, sha);
			if (isBetter(match, installable)) installable = match;
		}
		RemoteMatch blocked = null;
		for (CompatPackRelease release : index.releases) {
			if (release.isInstallable(launcherVersionCode) || !newerThanInstalled(release, installedPacks)
					|| (installable != null && compareReleases(release, installable.release) <= 0)) continue;
			RemoteMatch match = findBestMatch(release, version, sha);
			// The notice describes the newest blocked release that supports this payload.
			if (match != null && (blocked == null || compareReleases(release, blocked.release) > 0)) blocked = match;
		}
		return new PayloadRecommendation(installable, blocked);
	}

	public CompatPackReleaseTarget findBestTargetForPayload(CompatPackRelease release, JSONObject payloadManifest) {
		if (release == null || payloadManifest == null) return null;
		RemoteMatch match = findBestMatch(release, packs.getPayloadVersion(payloadManifest), packs.getPayloadSts2DllSha256(payloadManifest));
		return match == null ? null : match.target;
	}

	private static RemoteMatch findBestMatch(CompatPackRelease release, String payloadVersion, String payloadSha) {
		CompatPackReleaseTarget best = null;
		int bestScore = 0;
		for (CompatPackReleaseTarget target : release.targets) {
			int score = CompatPackManager.scoreExplicitMatch(payloadVersion, payloadSha, target.version, target.versions, target.sts2DllSha256s);
			if (score > bestScore || (score > 0 && score == bestScore && target.targetId.compareTo(best.targetId) < 0)) {
				best = target;
				bestScore = score;
			}
		}
		return best == null ? null : new RemoteMatch(release, best, bestScore);
	}

	private static boolean isBetter(RemoteMatch candidate, RemoteMatch current) {
		return candidate != null && (current == null || candidate.score > current.score
			|| (candidate.score == current.score && compareReleases(candidate.release, current.release) > 0));
	}

	private static int compareReleases(CompatPackRelease left, CompatPackRelease right) {
		Integer result = CompatPackVersionComparator.compare(left.compatRevision, left.compatVersion,
			right.compatRevision, right.compatVersion);
		return result == null ? 0 : result;
	}

	public CompatPackRelease findUpgradeForInstalledFullPack(CompatPackReleaseIndex index, int launcherVersionCode,
			List<CompatPackManager.CompatPack> installedPacks) {
		if (index == null || installedPacks == null) return null;
		boolean installed = false;
		for (CompatPackManager.CompatPack pack : installedPacks) {
			if (isInstalledFamily(pack)) {
				installed = true;
				break;
			}
		}
		if (!installed) return null;
		CompatPackRelease best = null;
		for (CompatPackRelease release : index.releases) {
			if (release.isInstallable(launcherVersionCode) && newerThanInstalled(release, installedPacks)
					&& (best == null || compareReleases(release, best) > 0)) best = release;
		}
		return best;
	}

	private static boolean isInstalledFamily(CompatPackManager.CompatPack pack) {
		return pack != null && pack.ready && CompatPackReleaseTrust.PACK_ID.equals(pack.packId)
			&& !CompatPackManager.PACK_KIND_OFFLINE_BOOTSTRAP.equals(pack.packKind);
	}

	private static boolean newerThanInstalled(CompatPackRelease release, List<CompatPackManager.CompatPack> installedPacks) {
		if (installedPacks == null) return true;
		for (CompatPackManager.CompatPack pack : installedPacks) {
			if (!isInstalledFamily(pack)) continue;
			Integer comparison = CompatPackVersionComparator.compare(release.compatRevision, release.compatVersion,
				pack.manifest.optInt("compat_revision", 0), pack.compatVersion);
			if (comparison == null || comparison <= 0) return false;
		}
		return true;
	}
}
