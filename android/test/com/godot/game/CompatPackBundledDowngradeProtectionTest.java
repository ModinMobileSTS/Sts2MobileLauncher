package com.godot.game;

import android.app.Application;
import android.content.Context;
import android.net.Uri;

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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, shadows = AndroidLinuxTestShadow.class)
public class CompatPackBundledDowngradeProtectionTest {
	private static final String FULL = "sts2-android-compat";
	private static final String TARGET = "v0.114.0";
	private static final String LEGACY = FULL + "-" + TARGET;
	private static final String OFFLINE = "sts2-android-offline-bootstrap";
	private static final String PAYLOAD = "test-game";
	private static final String AUTO = LaunchProfileManager.COMPAT_SELECTION_AUTO;
	private static final String MANUAL = LaunchProfileManager.COMPAT_SELECTION_MANUAL;
	private final FixtureAssets assets = new FixtureAssets();
	private Context context;
	private LaunchProfileManager profiles;
	private CompatPackManager packs;

	@Before public void setUp() throws Exception {
		context = RuntimeEnvironment.getApplication();
		profiles = new LaunchProfileManager(context);
		packs = new CompatPackManager(context, assets);
		File game = profiles.getPayloadGameDir(PAYLOAD);
		String release = new JSONObject().put("version", TARGET).toString();
		put(new File(game, "SlayTheSpire2.pck"), "GDPCfixture-game");
		put(new File(game, "release_info.json"), release);
		put(new File(game, ".payload_manifest.json"), new JSONObject().put("identity", new JSONObject()
			.put("release_info", new JSONObject(release))).toString());
		put(new File(game, "data_sts2_windows_x86_64/sts2.dll"), "fixture-game");
		put(new File(game, "data_sts2_windows_x86_64/sts2.deps.json"), "{}");
		put(new File(game, "data_sts2_windows_x86_64/sts2.runtimeconfig.json"), "{}");
	}

	@Test public void keepingNewerGithubPackStillMigratesLegacyAndUpgradesAutomaticOffline() throws Exception {
		installExternal("github_release", "0.8.0", 800, "online");
		packs.importCompatPack(Uri.fromFile(offlineZip()));
		LaunchProfileManager.LaunchProfile legacy = profile("Legacy", LEGACY, "", MANUAL, false);
		// Select the offline profile before the full family becomes visible, like a deferred startup.
		File fullDir = new File(packs.getCompatPacksRootDir(), FULL);
		File hidden = new File(context.getFilesDir(), "delayed-full");
		Files.move(fullDir.toPath(), hidden.toPath());
		LaunchProfileManager.LaunchProfile offline = profile("Offline", OFFLINE, "offline-any", AUTO, true);
		assertEquals(OFFLINE, profiles.getSelectedProfile().compatPackId);
		Files.move(hidden.toPath(), fullDir.toPath());
		assets.zip = fullZip("0.7.3-dev", 703, "old-apk");

		assertTrue(packs.installBundledCompatPacksWithResult().preservedNewerOrExternal);
		assertEquals(0, packs.installBundledCompatPacks());
		assertInstalled("github_release", "online");
		assertEquals(FULL, profiles.readProfile(legacy.id).compatPackId);
		assertEquals(TARGET, profiles.readProfile(legacy.id).compatTargetId);
		assertEquals(MANUAL, profiles.readProfile(legacy.id).compatSelectionSource);
		assertEquals(offline.id, profiles.getSelectedProfile().id);
		assertEquals(FULL, profiles.getSelectedProfile().compatPackId);
		assertEquals(TARGET, profiles.getSelectedProfile().compatTargetId);
		assertEquals(AUTO, profiles.getSelectedProfile().compatSelectionSource);
		assertEquals(FULL, json(new File(context.getFilesDir(), "launcher/selected_instance.json")).getString("compat_pack_id"));
		assertEquals(TARGET, json(new File(context.getFilesDir(), "launcher/selected_compat_pack.json")).getString("selected_compat_target_id"));
	}

	@Test public void safPackSurvivesRepeatedAssetInstallAndUnknownVersionIsNotAssumedOlder() throws Exception {
		assets.zip = fullZip("0.7.3-dev", 703, "base");
		installExternal("saf_zip", "0.8.0", 800, "imported");
		assertEquals(0, packs.installBundledCompatPacks());
		assertEquals(0, packs.installBundledCompatPacks());
		assertInstalled("saf_zip", "imported");
		installExternal("saf_zip", "manually-built", 0, "unknown-order");
		assertEquals(0, packs.installBundledCompatPacks());
		assertInstalled("saf_zip", "unknown-order");
	}

	@Test public void newerBundledPackageUpgradesBothExternalSourcesAndOlderBundledCannotRollBack() throws Exception {
		File newer = fullZip("0.9.0", 900, "apk-upgrade");
		for (String kind : new String[] { "github_release", "saf_zip" }) {
			installExternal(kind, "0.8.0", 800, "old-external");
			assets.zip = newer;
			assertEquals(1, packs.installBundledCompatPacks());
			assertInstalled("bundled_asset", "apk-upgrade");
		}
		assets.zip = fullZip("0.8.0", 800, "old-apk");
		assertEquals(0, packs.installBundledCompatPacks());
		assertInstalled("bundled_asset", "apk-upgrade");
	}

	@Test public void equalVersionDifferentBundledBytesRefreshTheSameSelectedFamily() throws Exception {
		assets.zip = fullZip("0.7.3-dev", 703, "dev-a");
		assertEquals(1, packs.installBundledCompatPacks());
		profile("Profile", FULL, TARGET, MANUAL, true);
		assertInstalled("bundled_asset", "dev-a");
		String originalStamp = packs.buildSelectedCompatStamp();
		long dllTime = selectedFull().dllFile.lastModified();
		long pckTime = selectedFull().overlayPckFile.lastModified();

		assets.zip = fullZip("0.7.3-dev", 703, "dev-b");
		assertEquals(1, packs.installBundledCompatPacks());
		assertInstalled("bundled_asset", "dev-b");
		assertTrue(selectedFull().dllFile.setLastModified(dllTime));
		assertTrue(selectedFull().overlayPckFile.setLastModified(pckTime));
		assertNotEquals("Same size/mtime and version must not hide changed ZIP bytes", originalStamp, packs.buildSelectedCompatStamp());
		assertEquals(0, packs.installBundledCompatPacks());
	}

	@Test public void damagedExistingPackageRepairsEvenIfItsVersionWasNewer() throws Exception {
		assets.zip = fullZip("0.7.3-dev", 703, "repair");
		for (boolean missingDll : new boolean[] { true, false }) {
			installExternal("saf_zip", "0.8.0", 800, "incoming");
			CompatPackManager.CompatPack installed = selectedFull();
			Files.delete((missingDll ? installed.dllFile : installed.overlayPckFile).toPath());
			assertEquals(1, packs.installBundledCompatPacks());
			assertInstalled("bundled_asset", "repair");
		}
	}

	@Test public void explicitForceRestoreAllowsTheOldBundledRevision() throws Exception {
		installExternal("saf_zip", "0.8.0", 800, "new");
		assets.zip = fullZip("0.7.3-dev", 703, "restored");
		assertEquals(0, packs.installBundledCompatPacks());
		assertEquals(1, packs.installBundledCompatPacks(true));
		assertInstalled("bundled_asset", "restored");
	}

	@Test public void legacyZipWithArtifactOnlyChecksumsRemainsInstallable() throws Exception {
		JSONObject manifest = new JSONObject().put("schema", 1).put("pack_id", LEGACY).put("compat_version", "0.6.0")
			.put("target_game", new JSONObject().put("version", TARGET));
		assets.zip = zip(manifest, "", "legacy", false);
		assertEquals(1, packs.installBundledCompatPacks());
		CompatPackManager.CompatPack installed = packs.findBestMatch(profiles.readPayload(PAYLOAD).manifest);
		assertEquals(LEGACY, installed.packId);
		assertEquals("legacy", readUtf8(installed.dllFile));
	}

	@Test public void revisionIsAuthoritativeAndSemverHandlesPrereleaseNumericSegments() {
		assertTrue(CompatPackVersionComparator.compare(801, "0.7.3", 800, "0.8.0") > 0);
		assertTrue(CompatPackVersionComparator.compare(0, "0.7.3-beta.10", 1, "0.7.3-beta.2") > 0);
		assertTrue(CompatPackVersionComparator.compare(0, "v0.7.4-dev", 0, "0.7.3") > 0);
		assertTrue(CompatPackVersionComparator.compare(0, "0.7.3", 0, "0.7.3-dev") > 0);
		assertNull(CompatPackVersionComparator.compare(0, "0.7.3", 0, "unknown"));
	}

	private LaunchProfileManager.LaunchProfile profile(String name, String packId, String targetId, String source, boolean select) throws Exception {
		return profiles.createProfile(PAYLOAD, name, "global", "global", packId, targetId, source, select);
	}

	private void installExternal(String kind, String version, int revision, String content) throws Exception {
		CompatPackManager.CompatPack pack = packs.importCompatPack(Uri.fromFile(fullZip(version, revision, content)));
		if (!"saf_zip".equals(kind)) {
			// Seed the same persisted provenance as an earlier verified install; signature tests exercise that entry point.
			pack.manifest.getJSONObject("installed_source").put("kind", kind);
			put(new File(pack.dir, CompatPackManager.MANIFEST_FILE_NAME), pack.manifest.toString());
		}
	}

	private CompatPackManager.CompatPack selectedFull() {
		return packs.listInstalledPacks().stream().filter(pack -> FULL.equals(pack.packId)).findFirst().orElseThrow();
	}

	private void assertInstalled(String kind, String content) throws Exception {
		CompatPackManager.CompatPack pack = selectedFull();
		assertEquals(kind, pack.manifest.getJSONObject("installed_source").getString("kind"));
		assertEquals(content, readUtf8(pack.dllFile));
		assertEquals("GDPC" + content, readUtf8(pack.overlayPckFile));
	}

	private File fullZip(String version, int revision, String content) throws Exception {
		JSONObject manifest = new JSONObject().put("schema", 2).put("pack_id", FULL).put("compat_version", version)
			.put("targets", new JSONArray().put(new JSONObject().put("target_id", TARGET).put("version", TARGET)));
		if (revision > 0) manifest.put("compat_revision", revision);
		return zip(manifest, "variants/" + TARGET + "/", content, true);
	}

	private File offlineZip() throws Exception {
		JSONObject manifest = new JSONObject().put("schema", 2).put("pack_id", OFFLINE).put("compat_version", "0.2.0")
			.put("pack_kind", "offline-bootstrap").put("targets", new JSONArray().put(new JSONObject()
				.put("target_id", "offline-any").put("versions", new JSONArray().put("*")).put("match_mode", "offline-wildcard")));
		return zip(manifest, "variants/offline-any/", "offline", true);
	}

	private File zip(JSONObject manifest, String path, String content, boolean sumManifest) throws Exception {
		Map<String, String> files = new LinkedHashMap<>();
		files.put(CompatPackManager.MANIFEST_FILE_NAME, manifest.toString());
		files.put(path + CompatPackManager.ENTRY_DLL, content);
		files.put(path + CompatPackManager.ENTRY_OVERLAY_PCK, "GDPC" + content);
		File zip = File.createTempFile("fixture-compat-", ".zip", context.getCacheDir());
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
			StringBuilder sums = new StringBuilder();
			for (Map.Entry<String, String> file : files.entrySet()) {
				byte[] data = file.getValue().getBytes(StandardCharsets.UTF_8);
				entry(output, file.getKey(), data);
				if (sumManifest || !CompatPackManager.MANIFEST_FILE_NAME.equals(file.getKey())) {
					for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) sums.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
					sums.append("  ").append(file.getKey()).append('\n');
				}
			}
			entry(output, "SHA256SUMS", sums.toString().getBytes(StandardCharsets.UTF_8));
		}
		return zip;
	}

	private static void entry(ZipOutputStream output, String name, byte[] data) throws IOException {
		output.putNextEntry(new ZipEntry(name));
		output.write(data);
		output.closeEntry();
	}

	private static JSONObject json(File file) throws Exception {
		return new JSONObject(readUtf8(file));
	}

	private static String readUtf8(File file) throws IOException {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	private static void put(File file, String content) throws IOException {
		Files.createDirectories(file.getParentFile().toPath());
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	private static final class FixtureAssets implements CompatPackManager.BundledAssets {
		File zip;
		@Override public String[] list() { return zip == null ? new String[0] : new String[] { FULL + ".zip" }; }
		@Override public InputStream open(String name) throws IOException { return Files.newInputStream(zip.toPath()); }
	}
}
