package com.godot.game;

import android.app.Application;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.Okio;
import okio.Source;
import okio.Timeout;

import static com.godot.game.CompatPackReleaseSecurityTest.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, shadows = AndroidLinuxTestShadow.class)
public class CompatPackRemoteUpdateFlowTest {
	private static final String PAYLOAD_ID = "release-payload";
	private static final String OFFLINE = "sts2-android-offline-bootstrap";
	private static final String OFFLINE_TARGET = "offline-any";
	private Context context;
	private LaunchProfileManager profiles;
	private CompatPackManager packs;
	private SigningFixture signing;
	private StubTransport transport;
	private CompatPackReleaseRepository repository;

	@Before public void setup() {
		context = RuntimeEnvironment.getApplication();
		profiles = new LaunchProfileManager(context);
		packs = new CompatPackManager(context);
		signing = new SigningFixture();
		transport = new StubTransport();
		repository = transport.repository(context, signing);
	}

	@Test public void remoteInstallAutomaticallyMigratesRealAutoOfflineBindingAndDiagnostics() throws Exception {
		LaunchProfileManager.LaunchProfile original = createOfflineProfile(false);
		assertEquals(LaunchProfileManager.COMPAT_SELECTION_AUTO, original.compatSelectionSource);
		byte[] zip = zip("0.8.0", 800, 1, true, false);
		CompatPackReleaseIndex index = fetch(release("0.8.0", 800, 1, zip));
		CompatPackReleaseRepository.PayloadRecommendation recommended = recommend(index);
		assertEquals(EXACT_ID, recommended.installable.target.targetId);
		assertNull(recommended.requiresLauncherUpdate);
		transport.reply(200, zip);
		List<Long> progress = new ArrayList<>();
		CompatPackManager.CompatPack installed = repository.downloadAndInstallRelease(recommended.installable.release,
			(downloaded, total) -> {
				assertEquals(zip.length, total);
				progress.add(downloaded);
			}, new CancellationSignal());
		assertEquals(Long.valueOf(0L), progress.get(0));
		assertEquals(Long.valueOf(zip.length), progress.get(progress.size() - 1));
		assertEquals(FAMILY, installed.packId);
		JSONObject source = installed.manifest.getJSONObject("installed_source");
		assertEquals("github_release", source.getString("kind"));
		assertEquals("v0.8.0", source.getString("release_tag"));
		assertEquals(SigningFixture.KEY_ID, source.getString("key_id"));
		assertEquals(recommended.installable.release.zipSha256, source.getString("zip_sha256"));
		LaunchProfileManager.LaunchProfile updated = new LaunchProfileManager(context).getSelectedProfile();
		assertEquals(original.id, updated.id);
		assertEquals(FAMILY, updated.compatPackId);
		assertEquals(EXACT_ID, updated.compatTargetId);
		assertEquals(LaunchProfileManager.COMPAT_SELECTION_AUTO, updated.compatSelectionSource);
		JSONObject selected = readJson(new File(context.getFilesDir(), "launcher/selected_instance.json"));
		assertEquals(FAMILY, selected.getString("compat_pack_id"));
		assertEquals(EXACT_ID, selected.getString("compat_target_id"));
		JSONObject diagnostics = readJson(new File(context.getFilesDir(), "launcher/selected_compat_pack.json"));
		assertEquals(FAMILY, diagnostics.getString("selected_compat_pack_id"));
		assertEquals(EXACT_ID, diagnostics.getString("selected_compat_target_id"));
		assertNoPartialDownloads(context);
	}

	@Test public void remoteInstallPreservesActualManualOfflineBindingButExposesLocalMatch() throws Exception {
		LaunchProfileManager.LaunchProfile original = createOfflineProfile(true);
		File profileFile = new File(original.dir, LaunchProfileManager.PROFILE_FILE_NAME);
		byte[] originalProfile = Files.readAllBytes(profileFile.toPath());
		byte[] zip = zip("0.8.0", 800, 1, true, false);
		CompatPackReleaseIndex index = fetch(release("0.8.0", 800, 1, zip));
		transport.reply(200, zip);
		repository.downloadAndInstallRelease(recommend(index).installable.release, null, new CancellationSignal());
		LaunchProfileManager.LaunchProfile retained = new LaunchProfileManager(context).getSelectedProfile();
		assertEquals(original.id, retained.id);
		assertEquals(OFFLINE, retained.compatPackId);
		assertEquals(OFFLINE_TARGET, retained.compatTargetId);
		assertEquals(LaunchProfileManager.COMPAT_SELECTION_MANUAL, retained.compatSelectionSource);
		assertArrayEquals(originalProfile, Files.readAllBytes(profileFile.toPath()));
		// The existing offline dialog can still offer an explicit switch to this actual installed match.
		CompatPackManager.CompatPack localMatch = packs.findBestMatch(payloadManifest());
		assertEquals(FAMILY, localMatch.packId);
		assertEquals(EXACT_ID, localMatch.targetId);
	}

	@Test public void noPublishedReleaseReturnsNullAndCoolDownAppliesTo404() throws Exception {
		transport.reply(404, new byte[0]);
		assertNull(repository.fetchIndexIfNeeded(false));
		assertNull(repository.getCachedState().index);
		assertEquals(transport.now, repository.getCachedState().lastCheckedUnix);
		transport.now += TimeUnit.HOURS.toSeconds(5);
		assertNull(repository.fetchIndexIfNeeded(false));
		assertEquals(1, transport.requests.size());
		transport.reply(404, new byte[0]);
		assertNull(repository.fetchIndexIfNeeded(true));
		assertNull(transport.requests.get(1).header("If-None-Match"));
	}

	@Test public void failedAutomaticCheckIsThrottledAcrossRepositoryRecreationButExplicitCheckBypassesIt() throws Exception {
		transport.reply(503, new byte[0]);
		assertThrows(IOException.class, () -> repository.fetchIndexIfNeeded(false));
		CompatPackReleaseRepository recreated = transport.repository(context, signing);
		assertEquals(0L, recreated.getLastCheckedUnix());
		transport.now += TimeUnit.HOURS.toSeconds(5);
		assertNull(recreated.fetchIndexIfNeeded(false));
		assertEquals(1, transport.requests.size());
		transport.reply(404, new byte[0]);
		assertNull(recreated.fetchIndexIfNeeded(true));
		assertEquals(2, transport.requests.size());
		assertEquals(transport.now, recreated.getLastCheckedUnix());
	}

	@Test public void conditional304RenewsVerifiedCacheButTamperedRawBytesCannotBeReused() throws Exception {
		byte[] index = indexBytes(release("0.8.0", 800, 1, new byte[]{1}));
		byte[] signature = signing.sign(index);
		String etag = "\"signed-v1\"";
		transport.add((request, call) -> response(request, 200, index).header("ETag", etag).build());
		transport.reply(200, signature);
		assertEquals("v0.8.0", repository.fetchIndexIfNeeded(false).latestTag);
		File cache = new File(context.getFilesDir(), "launcher/compat-release-cache");
		JSONObject metadata = readJson(new File(cache, "metadata.json"));
		File generation = new File(cache, "snapshot-" + metadata.getString("generation"));
		File cachedIndex = new File(generation, "compat-index.json");
		File cachedSignature = new File(generation, "compat-index.json.sig");
		assertArrayEquals(index, Files.readAllBytes(cachedIndex.toPath()));
		assertArrayEquals(signature, Files.readAllBytes(cachedSignature.toPath()));
		transport.now += TimeUnit.HOURS.toSeconds(6) - 1L;
		assertEquals("v0.8.0", repository.fetchIndexIfNeeded(false).latestTag);
		assertEquals(2, transport.requests.size());
		transport.now++;
		transport.reply(304, new byte[0]);
		assertEquals("v0.8.0", repository.fetchIndexIfNeeded(false).latestTag);
		assertEquals(etag, transport.requests.get(2).header("If-None-Match"));
		assertEquals(transport.now, repository.getLastCheckedUnix());
		assertEquals(304, readJson(new File(cache, "metadata.json")).getInt("last_status"));

		byte[] modifiedSignature = signature.clone();
		modifiedSignature[modifiedSignature.length - 2] ^= 1;
		Files.write(cachedSignature.toPath(), modifiedSignature);
		assertNull(repository.getCachedIndex());
		Files.write(cachedSignature.toPath(), signature);
		assertNotNull(repository.getCachedIndex());
		byte[] modifiedIndex = index.clone();
		modifiedIndex[modifiedIndex.length - 2] ^= 1;
		Files.write(cachedIndex.toPath(), modifiedIndex);
		assertNull(repository.getCachedIndex());
		transport.reply(304, new byte[0]);
		assertThrows(IOException.class, () -> repository.fetchIndexIfNeeded(false));
		assertNull(transport.requests.get(3).header("If-None-Match"));
		assertEquals(0L, repository.getLastCheckedUnix());
	}

	@Test public void matchingUsesShaBeforeVersionReportsNewerLauncherBlockedAndDoesNotRecommendRollback() throws Exception {
		byte[] placeholder = {1};
		JSONObject compatibleOnly = release("0.9.0", 900, 1, placeholder).put("targets", new JSONArray()
			.put(target("explicit", "v0.100.0", GAME_VERSION, null)));
		JSONObject primaryOnly = release("0.9.1", 901, 1, placeholder).put("targets", new JSONArray()
			.put(target("primary", GAME_VERSION, GAME_VERSION, null)));
		JSONObject exact = release("0.8.1", 801, 1, placeholder).put("targets", new JSONArray()
			.put(target("exact-array", "v0.100.0", "v0.100.0", new JSONArray()
				.put("a".repeat(64)).put(SHA))));
		JSONObject olderBlocked = release("0.7.0", 700, BuildConfig.VERSION_CODE + 1, placeholder);
		JSONObject newerBlocked = release("1.0.0", 1000, BuildConfig.VERSION_CODE + 1, placeholder);
		CompatPackReleaseIndex index = signing.verify(newerBlocked, compatibleOnly, exact, olderBlocked, primaryOnly);
		assertEquals("exact-array", recommend(index).installable.target.targetId);
		assertEquals("1.0.0", recommend(index).requiresLauncherUpdate.release.compatVersion);
		assertEquals("primary", recommend(signing.verify(primaryOnly, compatibleOnly)).installable.target.targetId);
		assertNull(recommend(signing.verify(exact, olderBlocked)).requiresLauncherUpdate);
		assertNull(repository.findUpgradeForInstalledFullPack(index, BuildConfig.VERSION_CODE, packs.listInstalledPacks()));

		byte[] installedZip = zip("0.9.0", 900, 1, true, false);
		transport.reply(200, installedZip);
		repository.downloadAndInstallRelease(signing.verify(release("0.9.0", 900, 1, installedZip)).releases.get(0), null, null);
		assertEquals("primary", recommend(index).installable.target.targetId);
		assertEquals("0.9.1", repository.findUpgradeForInstalledFullPack(index, BuildConfig.VERSION_CODE, packs.listInstalledPacks()).compatVersion);
		assertNull(recommend(signing.verify(compatibleOnly, exact)).installable);
		assertNull(repository.findUpgradeForInstalledFullPack(signing.verify(compatibleOnly, exact), BuildConfig.VERSION_CODE, packs.listInstalledPacks()));

		byte[] oldZip = zip("0.8.0", 800, 1, true, false);
		transport.reply(200, oldZip);
		assertEquals("0.8.0", repository.downloadAndInstallRelease(
			signing.verify(release("0.8.0", 800, 1, oldZip)).releases.get(0), null, null).compatVersion);
	}

	@Test public void cancellationCancelsActualCallWhileBodyReadIsBlockedAndRemovesPartial() throws Exception {
		byte[] zip = zip("0.8.0", 800, 1, true, false);
		CompatPackRelease release = signing.verify(release("0.8.0", 800, 1, zip)).releases.get(0);
		CountDownLatch waitingForBody = new CountDownLatch(1);
		CountDownLatch callCanceled = new CountDownLatch(1);
		transport.add((request, call) -> response(request, 200, new byte[0]).body(ResponseBody.create(Okio.buffer(new Source() {
			@Override public long read(Buffer sink, long byteCount) throws IOException {
				waitingForBody.countDown();
				try {
					if (!callCanceled.await(10, TimeUnit.SECONDS)) throw new IOException("Call.cancel was not forwarded");
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException(e);
				}
				assertTrue(call.isCanceled());
				throw new IOException("Canceled while reading the fixture body");
			}
			@Override public Timeout timeout() { return Timeout.NONE; }
			@Override public void close() { }
		}), null, zip.length)).build());
		CompatPackReleaseRepository withEventListener = new CompatPackReleaseRepository(context,
			transport.client.newBuilder().eventListener(new EventListener() {
				@Override public void canceled(Call call) { callCanceled.countDown(); }
			}).build(), signing.verifier, () -> transport.now);
		CancellationSignal cancellation = new CancellationSignal();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> install = executor.submit(() -> withEventListener.downloadAndInstallRelease(release, null, cancellation));
			assertTrue(waitingForBody.await(10, TimeUnit.SECONDS));
			cancellation.cancel();
			assertTrue("CancellationSignal must invoke Call.cancel during the body read", callCanceled.await(5, TimeUnit.SECONDS));
			ExecutionException failure = assertThrows(ExecutionException.class, () -> install.get(5, TimeUnit.SECONDS));
			assertTrue(failure.getCause() instanceof OperationCanceledException);
			assertTrue(packs.listInstalledPacks().isEmpty());
			assertNoPartialDownloads(context);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test public void zipCannotExceedSignedLengthAndCancelAtLastProgressDoesNotInstall() throws Exception {
		byte[] zip = zip("0.8.0", 800, 1, true, false);
		CompatPackRelease release = signing.verify(release("0.8.0", 800, 1, zip)).releases.get(0);
		transport.add((request, call) -> response(request, 200, new byte[0])
			.body(ResponseBody.create(new Buffer().write(zip).writeByte(0), null, -1)).build());
		assertThrows(IOException.class, () -> repository.downloadAndInstallRelease(release, null, null));
		assertNoPartialDownloads(context);
		transport.reply(200, zip);
		CancellationSignal cancel = new CancellationSignal();
		assertThrows(OperationCanceledException.class, () -> repository.downloadAndInstallRelease(release,
			(downloaded, total) -> { if (downloaded == total) cancel.cancel(); }, cancel));
		assertNoPartialDownloads(context);
		assertTrue(packs.listInstalledPacks().isEmpty());
	}

	private CompatPackReleaseIndex fetch(JSONObject release) throws Exception {
		byte[] index = indexBytes(release);
		transport.reply(200, index);
		transport.reply(200, signing.sign(index));
		return repository.fetchIndexIfNeeded(true);
	}

	private CompatPackReleaseRepository.PayloadRecommendation recommend(CompatPackReleaseIndex index) throws Exception {
		return repository.findBestRemoteReleaseForPayload(index, payloadManifest(), BuildConfig.VERSION_CODE, packs.listInstalledPacks());
	}

	private LaunchProfileManager.LaunchProfile createOfflineProfile(boolean manual) throws Exception {
		File game = profiles.getPayloadGameDir(PAYLOAD_ID);
		put(new File(game, "SlayTheSpire2.pck"), "GDPCsynthetic-game");
		JSONObject manifest = payloadManifest();
		put(new File(game, "release_info.json"), manifest.getJSONObject("identity").getJSONObject("release_info").toString());
		put(new File(game, ".payload_manifest.json"), manifest.toString());
		put(new File(game, "data_sts2_windows_x86_64/sts2.dll"), "synthetic-game");
		put(new File(game, "data_sts2_windows_x86_64/sts2.deps.json"), "{}");
		put(new File(game, "data_sts2_windows_x86_64/sts2.runtimeconfig.json"), "{}");
		File offline = new File(context.getFilesDir(), "compat-packs/" + OFFLINE);
		put(new File(offline, "compat_manifest.json"), new JSONObject().put("schema", 2).put("pack_id", OFFLINE)
			.put("compat_version", "0.2.0-dev").put("pack_kind", "offline-bootstrap").put("targets", new JSONArray()
			.put(new JSONObject().put("target_id", OFFLINE_TARGET).put("match_mode", "offline-wildcard")
				.put("versions", new JSONArray().put("*")))).toString());
		put(new File(offline, "variants/" + OFFLINE_TARGET + "/STS2Mobile.dll"), "synthetic-offline");
		put(new File(offline, "variants/" + OFFLINE_TARGET + "/port_compat.pck"), "GDPCsynthetic-offline");
		LaunchProfileManager.LaunchProfile created = profiles.createOrSelectDefaultProfileForPayload(profiles.readPayload(PAYLOAD_ID), true);
		assertEquals(OFFLINE, created.compatPackId);
		assertEquals(OFFLINE_TARGET, created.compatTargetId);
		if (!manual) return created;
		packs.selectPack(OFFLINE, OFFLINE_TARGET, LaunchProfileManager.COMPAT_SELECTION_MANUAL);
		return profiles.getSelectedProfile();
	}

	private static void put(File file, String content) throws IOException {
		Files.createDirectories(file.getParentFile().toPath());
		Files.write(file.toPath(), utf8(content));
	}

	private static JSONObject readJson(File file) throws Exception {
		return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
	}
}
