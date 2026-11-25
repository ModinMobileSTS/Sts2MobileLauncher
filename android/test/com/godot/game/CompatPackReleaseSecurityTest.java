package com.godot.game;

import android.app.Application;
import android.content.Context;
import android.util.Base64;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, shadows = AndroidLinuxTestShadow.class)
public class CompatPackReleaseSecurityTest {
	static final String FAMILY = "sts2-android-compat";
	static final String GAME_VERSION = "v0.114.0";
	static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
	static final String EXACT_ID = "exact";
	private Context context;
	private SigningFixture signing;

	@Before public void setup() {
		context = RuntimeEnvironment.getApplication();
		signing = new SigningFixture();
	}

	@Test public void validEd25519SignatureProducesImmutableMetadata() throws Exception {
		JSONObject release = release("0.8.0", 800, 1, zip("0.8.0", 800, 1, true, false));
		CompatPackReleaseIndex verified = signing.verify(release);
		assertEquals("v0.8.0", verified.latestTag);
		CompatPackRelease parsed = verified.releases.get(0);
		assertEquals(SigningFixture.KEY_ID, parsed.keyId);
		assertEquals(release.getString("zip_sha256"), parsed.zipSha256);
		assertEquals(SHA, parsed.targets.get(0).sts2DllSha256s.get(0));
		assertThrows(UnsupportedOperationException.class, verified.releases::clear);
		assertThrows(UnsupportedOperationException.class, parsed.targets::clear);
		assertThrows(UnsupportedOperationException.class, parsed.targets.get(0).versions::clear);
		assertThrows(UnsupportedOperationException.class, parsed.targets.get(0).sts2DllSha256s::clear);
	}

	@Test public void rawPayloadDigestSignatureAndSigningKeyAreAllRequired() throws Exception {
		byte[] index = indexBytes(release("0.8.0", 800, 1, new byte[]{1}));
		JSONObject signature = signing.signature(index);
		byte[] modified = index.clone();
		modified[modified.length - 2] ^= 1;
		assertThrows(IOException.class, () -> signing.verifier.verify(modified, utf8(signature.toString())));
		JSONObject alteredDigest = new JSONObject(signature.toString()).put("payload_sha256", SHA);
		assertThrows(IOException.class, () -> signing.verifier.verify(index, utf8(alteredDigest.toString())));
		JSONObject alteredSignature = new JSONObject(signature.toString());
		byte[] altered = Base64.decode(alteredSignature.getString("signature_base64"), Base64.NO_WRAP);
		altered[0] ^= 1;
		alteredSignature.put("signature_base64", Base64.encodeToString(altered, Base64.NO_WRAP));
		assertThrows(IOException.class, () -> signing.verifier.verify(index, utf8(alteredSignature.toString())));
		JSONObject computedDigest = new JSONObject(signature.toString())
			.put("payload_sha256", CompatPackReleaseVerifier.sha256Hex(modified));
		assertThrows(IOException.class, () -> signing.verifier.verify(modified, utf8(computedDigest.toString())));
		JSONObject unknown = new JSONObject(signature.toString()).put("key_id", "other-key");
		assertThrows(IOException.class, () -> signing.verifier.verify(index, utf8(unknown.toString())));
		assertThrows(CompatPackReleaseVerifier.TrustUnconfiguredException.class,
			() -> new CompatPackReleaseVerifier(Collections.emptyMap()).verify(index, utf8(signature.toString())));
		StubTransport stub = new StubTransport();
		CompatPackReleaseRepository unconfigured = new CompatPackReleaseRepository(context, stub.client,
			new CompatPackReleaseVerifier(Collections.emptyMap()), () -> stub.now);
		assertThrows(CompatPackReleaseVerifier.TrustUnconfiguredException.class, () -> unconfigured.fetchIndexIfNeeded(true));
		assertTrue(stub.requests.isEmpty());
	}

	@Test public void signedIndexStillRejectsUnauthorizedEndpointsAndInvalidSchemaFields() throws Exception {
		JSONObject entry = release("0.8.0", 800, 1, new byte[]{1});
		String validUrl = entry.getString("zip_download_url");
		assertTrue(CompatPackReleaseTrust.isAllowedOfficialDownloadUrl(validUrl));
		assertTrue(CompatPackReleaseTrust.isAllowedOfficialIndexUrl(CompatPackReleaseTrust.INDEX_URL));
		assertTrue(CompatPackReleaseTrust.isAllowedOfficialIndexUrl(CompatPackReleaseTrust.SIGNATURE_URL));
		assertFalse(CompatPackReleaseTrust.isAllowedOfficialIndexUrl(CompatPackReleaseTrust.INDEX_URL + "?extra=1"));
		for (String url : new String[]{
				validUrl.replace("https://", "http://"), validUrl.replace("github.com", "github.com.evil.invalid"),
				validUrl.replace("github.com", "github.com:444"), validUrl.replace("github.com", "name@github.com"),
				validUrl.replace("github.com", "@github.com"), "https://mirror.invalid/" + validUrl,
				validUrl.replace("ModinMobileSTS", "OtherRepo"), validUrl.replace("v0.8.0/", "v0.7.0/"),
				validUrl.replace("v0.8.0/", "%2e%2e/"), validUrl.replace("v0.8.0/", ""),
				validUrl.replace(".zip", "-alternate.zip"), validUrl + "?extra=1"}) {
			entry.put("zip_download_url", url);
			assertThrows(url, IOException.class, () -> signing.verify(entry));
		}
		entry.put("zip_download_url", validUrl);
		entry.put("zip_size_bytes", CompatPackReleaseVerifier.MAX_ZIP_BYTES + 1L);
		assertThrows(IOException.class, () -> signing.verify(entry));
		entry.put("zip_size_bytes", 1);
		entry.getJSONArray("targets").getJSONObject(0).put("versions", new JSONArray().put("*"));
		assertThrows(IOException.class, () -> signing.verify(entry));
		byte[] duplicate = utf8(new String(indexBytes(release("0.8.0", 800, 1, new byte[]{1})), StandardCharsets.UTF_8)
			.replace("\"schema\":1", "\"schema\":1,\"schema\":1"));
		assertThrows(IOException.class, () -> signing.verifier.verify(duplicate, signing.sign(duplicate)));
	}

	@Test public void configuredHttpProxyTunnelsTheOfficialHttpsRequestWithoutRewritingIt() throws Exception {
		InetAddress loopback = InetAddress.getByName("127.0.0.1");
		try (ServerSocket proxy = new ServerSocket(0, 1, loopback)) {
			proxy.setSoTimeout(5_000);
			Field transport = CompatPackReleaseRepository.class.getDeclaredField("transport");
			transport.setAccessible(true);
			OkHttpClient production = (OkHttpClient) transport.get(new CompatPackReleaseRepository(context));
			List<Proxy> route = Collections.singletonList(new Proxy(Proxy.Type.HTTP,
				new InetSocketAddress(loopback, proxy.getLocalPort())));
			// Scope the system-style selector to the test client. An explicit NO_PROXY would still override it.
			OkHttpClient client = production.newBuilder()
				.proxySelector(new ProxySelector() {
					@Override public List<Proxy> select(URI uri) { return route; }
					@Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
				})
				.dns(host -> {
					if (host.equals("127.0.0.1")) return Collections.singletonList(loopback);
					throw new UnknownHostException(host);
				})
				.callTimeout(5, TimeUnit.SECONDS)
				.build();
			FutureTask<String> tunnel = new FutureTask<>(() -> {
				try (Socket peer = proxy.accept()) {
					peer.setSoTimeout(5_000);
					BufferedReader request = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.US_ASCII));
					String firstLine = request.readLine();
					for (String header; (header = request.readLine()) != null && !header.isEmpty(); ) { }
					peer.getOutputStream().write(utf8("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
					return firstLine;
				}
			});
			new Thread(tunnel, "compat-test-connect-proxy").start();
			CompatPackReleaseRepository repo = new CompatPackReleaseRepository(context, client, signing.verifier, () -> 123_456L);
			assertThrows(IOException.class, () -> repo.fetchIndexIfNeeded(true));
			assertEquals("CONNECT github.com:443 HTTP/1.1", tunnel.get(6, TimeUnit.SECONDS));
			assertNull(repo.getCachedIndex());
		}
	}

	@Test public void manualRedirectsRequireAbsoluteOfficialHttpsWithoutCredentialsOrNondefaultPort() throws Exception {
		for (String forbidden : new String[]{"//github.com/asset", "/asset", "http://github.com/asset",
				"https://release-assets.githubusercontent.com.evil.invalid/asset", "https://mirror.invalid/asset",
				"https://api.github.com/asset", "https://user@github.com/asset", "https://@github.com/asset",
				"https://github.com:444/asset", "https://github.com./asset", "https://github.com/asset#fragment"}) {
			StubTransport stub = new StubTransport();
			stub.add((request, call) -> response(request, 302, new byte[0]).header("Location", forbidden).build());
			assertThrows(forbidden, IOException.class, () -> stub.repository(context, signing).fetchIndexIfNeeded(true));
			assertEquals(forbidden, 1, stub.requests.size());
		}
		StubTransport stub = new StubTransport();
		byte[] index = indexBytes(release("0.8.0", 800, 1, new byte[]{1}));
		for (int i = 0; i < 5; i++) {
			String location = (i % 2 == 0 ? "https://objects.githubusercontent.com:443" : "https://release-assets.githubusercontent.com") + "/asset?hop=" + i;
			int status = new int[]{301, 302, 303, 307, 308}[i];
			stub.add((request, call) -> response(request, status, new byte[0]).header("Location", location).build());
		}
		stub.reply(200, index);
		stub.reply(200, signing.sign(index));
		assertNotNull(stub.repository(context, signing).fetchIndexIfNeeded(true));
		assertEquals(7, stub.requests.size());
		assertEquals(CompatPackReleaseTrust.SIGNATURE_URL, stub.requests.get(6).url().toString());

		StubTransport tooMany = new StubTransport();
		for (int i = 0; i < 6; i++) {
			String location = "https://objects.githubusercontent.com/asset?hop=" + i;
			tooMany.add((request, call) -> response(request, 302, new byte[0]).header("Location", location).build());
		}
		assertThrows(IOException.class, () -> tooMany.repository(context, signing).fetchIndexIfNeeded(true));
		assertEquals(6, tooMany.requests.size());
	}

	@Test public void oversizedIndexAndSignatureAreRejectedEvenWithUnknownLength() throws Exception {
		StubTransport stub = new StubTransport();
		stub.add((request, call) -> response(request, 200, new byte[0])
			.body(ResponseBody.create(new Buffer().write(new byte[CompatPackReleaseVerifier.MAX_INDEX_BYTES + 1]), null, -1)).build());
		assertThrows(IOException.class, () -> stub.repository(context, signing).fetchIndexIfNeeded(true));
		assertEquals(1, stub.requests.size());

		byte[] index = indexBytes(release("0.8.0", 800, 1, new byte[]{1}));
		stub.reply(200, index);
		stub.add((request, call) -> response(request, 200, new byte[0])
			.body(ResponseBody.create(new Buffer().write(new byte[CompatPackReleaseVerifier.MAX_SIGNATURE_BYTES + 1]), null, -1)).build());
		assertThrows(IOException.class, () -> stub.repository(context, signing).fetchIndexIfNeeded(true));
	}

	@Test public void launcherVersionOrSchemaBlockMatchingReleaseAndRejectInstallationBeforeDownload() throws Exception {
		int minimum = BuildConfig.VERSION_CODE + 1;
		for (JSONObject entry : new JSONObject[]{
			release("0.8.0", 800, minimum, zip("0.8.0", 800, minimum, true, false)),
			release("0.9.0", 900, 1, new byte[]{1}).put("manifest_schema", 3)}) {
			CompatPackReleaseIndex verified = signing.verify(entry);
			CompatPackRelease release = verified.releases.get(0);
			StubTransport stub = new StubTransport();
			CompatPackReleaseRepository repo = stub.repository(context, signing);
			assertFalse(release.isInstallable(BuildConfig.VERSION_CODE));
			CompatPackReleaseRepository.PayloadRecommendation recommendation = repo.findBestRemoteReleaseForPayload(
				verified, payloadManifest(), BuildConfig.VERSION_CODE, Collections.emptyList());
			assertNull(recommendation.installable);
			assertSame(release, recommendation.requiresLauncherUpdate.release);
			CompatPackManager.LauncherUpdateRequiredException failure = assertThrows(CompatPackManager.LauncherUpdateRequiredException.class,
				() -> repo.downloadAndInstallRelease(release, null, null));
			assertEquals(release.minLauncherVersionCode, failure.minLauncherVersionCode);
			assertEquals(release.manifestSchema, failure.manifestSchema);
			assertTrue(stub.requests.isEmpty());
			assertThrows(CompatPackManager.LauncherUpdateRequiredException.class,
				() -> new CompatPackManager(context).installFromVerifiedDownload(new File(context.getCacheDir(), "unused.zip"), "fixture", release));
		}
	}

	@Test public void invalidZipCannotReplaceExistingPackDespiteValidSignedIndex() throws Exception {
		StubTransport stub = new StubTransport();
		CompatPackReleaseRepository repo = stub.repository(context, signing);
		byte[] oldZip = zip("0.8.0", 800, 1, true, false);
		stub.reply(200, oldZip);
		CompatPackManager.CompatPack installed = repo.downloadAndInstallRelease(
			signing.verify(release("0.8.0", 800, 1, oldZip)).releases.get(0), null, null);
		File manifest = new File(installed.dir, "compat_manifest.json");
		byte[] priorManifest = Files.readAllBytes(manifest.toPath());
		byte[] priorDll = Files.readAllBytes(installed.dllFile.toPath());
		File otherDll = new File(installed.dir, "variants/other/STS2Mobile.dll");
		byte[] priorOtherDll = Files.readAllBytes(otherDll.toPath());
		byte[] valid = zip("0.9.0", 900, 1, true, false);
		byte[] corruptedZip = valid.clone();
		corruptedZip[corruptedZip.length / 2] ^= 1;
		byte[] missingSums = zip("0.9.0", 900, 1, false, false);
		byte[] corruptedVariant = zip("0.9.0", 900, 1, true, true);
		byte[] wrongManifest = zip("0.9.1", 901, 1, true, false);
		for (byte[][] attempt : new byte[][][]{
				{valid, corruptedZip}, {missingSums, missingSums}, {corruptedVariant, corruptedVariant}, {wrongManifest, wrongManifest}}) {
			stub.reply(200, attempt[1]);
			CompatPackRelease broken = signing.verify(release("0.9.0", 900, 1, attempt[0])).releases.get(0);
			assertThrows(IOException.class, () -> repo.downloadAndInstallRelease(broken, null, null));
			assertArrayEquals(priorManifest, Files.readAllBytes(manifest.toPath()));
			assertArrayEquals(priorDll, Files.readAllBytes(installed.dllFile.toPath()));
			assertArrayEquals(priorOtherDll, Files.readAllBytes(otherDll.toPath()));
			assertNoPartialDownloads(context);
		}
	}

	static void assertNoPartialDownloads(Context context) {
		String[] files = new File(context.getFilesDir(), "compat_pack_import").list((dir, name) -> name.endsWith(".part"));
		assertTrue(files == null || files.length == 0);
	}

	static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

	static JSONObject payloadManifest() throws Exception {
		return new JSONObject().put("identity", new JSONObject().put("sts2_dll_sha256", SHA)
			.put("release_info", new JSONObject().put("version", GAME_VERSION).put("commit", "fixture")));
	}

	static JSONObject target(String id, String version, String explicit, Object sha) throws Exception {
		JSONObject result = new JSONObject().put("target_id", id).put("status", "active")
			.put("display_name", id).put("version", version).put("versions", new JSONArray().put(explicit));
		if (sha != null) result.put("sts2_dll_sha256", sha);
		return result;
	}

	static JSONArray targets() throws Exception {
		return new JSONArray().put(target(EXACT_ID, GAME_VERSION, GAME_VERSION, SHA))
			.put(target("other", "v1.2.3", "v1.2.3", null));
	}

	static JSONObject release(String version, int revision, int minimum, byte[] zip) throws Exception {
		String tag = "v" + version;
		String base = "https://github.com/" + CompatPackReleaseTrust.REPOSITORY + "/releases/";
		return new JSONObject().put("tag_name", tag).put("release_name", "Fixture " + tag).put("pack_id", FAMILY)
			.put("release_url", base + "tag/" + tag).put("zip_asset_name", FAMILY + ".zip")
			.put("zip_download_url", base + "download/" + tag + "/" + FAMILY + ".zip")
			.put("compat_version", version).put("compat_revision", revision).put("manifest_schema", 2)
			.put("min_launcher_version_code", minimum).put("min_launcher_version_name", "v1.2.3")
			.put("zip_size_bytes", zip.length).put("zip_sha256", CompatPackReleaseVerifier.sha256Hex(zip)).put("targets", targets());
	}

	static byte[] indexBytes(JSONObject... releases) throws Exception {
		JSONArray entries = new JSONArray();
		for (JSONObject release : releases) entries.put(release);
		return utf8(new JSONObject().put("schema", 1).put("repository", CompatPackReleaseTrust.REPOSITORY)
			.put("pack_id", FAMILY).put("latest_tag", releases[0].getString("tag_name")).put("releases", entries).toString());
	}

	static byte[] zip(String version, int revision, int minimum, boolean sums, boolean corruptVariant) throws Exception {
		JSONObject manifest = new JSONObject().put("schema", 2).put("pack_id", FAMILY).put("display_name", FAMILY)
			.put("compat_version", version).put("compat_revision", revision).put("min_launcher_version_code", minimum)
			.put("min_launcher_version_name", "v1.2.3").put("targets", targets());
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("compat_manifest.json", utf8(manifest.toString()));
		for (String id : new String[]{EXACT_ID, "other"}) {
			entries.put("variants/" + id + "/STS2Mobile.dll", utf8("synthetic-compat-" + version + "-" + id));
			entries.put("variants/" + id + "/port_compat.pck", utf8("GDPCsynthetic-overlay-" + id));
		}
		if (sums) {
			StringBuilder lines = new StringBuilder();
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				lines.append(CompatPackReleaseVerifier.sha256Hex(entry.getValue())).append("  ").append(entry.getKey()).append('\n');
			}
			entries.put("SHA256SUMS", utf8(lines.toString()));
		}
		if (corruptVariant) entries.put("variants/other/STS2Mobile.dll", utf8("tampered-variant"));
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	static final class SigningFixture {
		static final String KEY_ID = "dynamic-test-key";
		final Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(new SecureRandom());
		final CompatPackReleaseVerifier verifier = new CompatPackReleaseVerifier(Collections.singletonMap(KEY_ID,
			Base64.encodeToString(privateKey.generatePublicKey().getEncoded(), Base64.NO_WRAP)));

		JSONObject signature(byte[] index) throws Exception {
			byte[] domain = utf8("sts2-android-compat-index-v1\n");
			Ed25519Signer signer = new Ed25519Signer();
			signer.init(true, privateKey);
			signer.update(domain, 0, domain.length);
			signer.update(index, 0, index.length);
			return new JSONObject().put("schema", 1).put("algorithm", "ed25519").put("key_id", KEY_ID)
				.put("payload_sha256", CompatPackReleaseVerifier.sha256Hex(index))
				.put("signature_base64", Base64.encodeToString(signer.generateSignature(), Base64.NO_WRAP));
		}

		byte[] sign(byte[] index) throws Exception { return utf8(signature(index).toString()); }

		CompatPackReleaseIndex verify(JSONObject... releases) throws Exception {
			byte[] signed = indexBytes(releases);
			return verifier.verify(signed, sign(signed));
		}
	}

	static Response.Builder response(Request request, int status, byte[] body) {
		return new Response.Builder().request(request).code(status).message("fixture").protocol(Protocol.HTTP_1_1)
			.body(ResponseBody.create(body, null));
	}

	static final class StubTransport {
		interface Responder { Response respond(Request request, Call call) throws IOException; }

		final List<Request> requests = new ArrayList<>();
		final ArrayDeque<Responder> responses = new ArrayDeque<>();
		final OkHttpClient client = new OkHttpClient.Builder().followRedirects(false).addInterceptor(chain -> {
			requests.add(chain.request());
			Responder responder = responses.pollFirst();
			if (responder == null) throw new AssertionError("Unexpected request to " + chain.request().url());
			return responder.respond(chain.request(), chain.call());
		}).build();
		long now = 123_456L;

		void add(Responder responder) { responses.addLast(responder); }
		void reply(int status, byte[] body) { add((request, call) -> response(request, status, body).build()); }

		CompatPackReleaseRepository repository(Context context, SigningFixture signing) {
			return new CompatPackReleaseRepository(context, client, signing.verifier, () -> now);
		}
	}
}
