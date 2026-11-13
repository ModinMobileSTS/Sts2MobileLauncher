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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, shadows = AndroidLinuxTestShadow.class)
public class AutomaticOfflineCompatSelectionTest {
	private static final String PAYLOAD = "offline-upgrade-fixture";
	private static final String VERSION = "fixture-upgrade";
	private static final String DLL_SHA = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
	private static final String OFFLINE = "fixture-offline";
	private static final String FULL = "fixture-full";
	private Context context;
	private LaunchProfileManager profiles;
	private CompatPackManager packs;

	@Before public void setup() throws Exception {
		context = RuntimeEnvironment.getApplication();
		profiles = new LaunchProfileManager(context);
		packs = new CompatPackManager(context);
		File game = profiles.getPayloadGameDir(PAYLOAD);
		JSONObject release = new JSONObject().put("version", VERSION).put("commit", "fixture");
		put(new File(game, "SlayTheSpire2.pck"), "GDPCsynthetic-game");
		put(new File(game, "release_info.json"), release.toString());
		put(new File(game, ".payload_manifest.json"), new JSONObject().put("identity", new JSONObject()
			.put("release_info", release).put("sts2_dll_sha256", DLL_SHA)).toString());
		put(new File(game, "data_sts2_windows_x86_64/sts2.dll"), "synthetic-game");
		put(new File(game, "data_sts2_windows_x86_64/sts2.deps.json"), "{}");
		put(new File(game, "data_sts2_windows_x86_64/sts2.runtimeconfig.json"), "{}");
		JSONObject offline = manifest(OFFLINE, new JSONArray().put(new JSONObject()
			.put("target_id", "offline-any").put("versions", new JSONArray().put("*"))
			.put("match_mode", "offline-wildcard")))
			.put("pack_kind", "offline-bootstrap");
		installDirectory(offline, true);
	}

	@Test public void selectingAutomaticOfflineAgainUpgradesToExactFamilyTargetAndRefreshesContext() throws Exception {
		LaunchProfileManager.LaunchProfile original = defaultProfile();
		assertEquals(OFFLINE, original.compatPackId);
		installDirectory(fullManifest(), true);
		profiles.selectProfile(original.id);
		LaunchProfileManager.LaunchProfile upgraded = new LaunchProfileManager(context).getSelectedProfile();
		assertEquals(FULL, upgraded.compatPackId);
		assertEquals("exact", upgraded.compatTargetId);
		assertEquals(LaunchProfileManager.COMPAT_SELECTION_AUTO, upgraded.compatSelectionSource);
		assertEquals(original.id, upgraded.id);
		assertEquals(original.createdAtUnix, upgraded.createdAtUnix);
		assertEquals(original.payloadId, upgraded.payloadId);
		assertEquals(original.saveMode, upgraded.saveMode);
		assertEquals(original.modsMode, upgraded.modsMode);
		JSONObject launch = json(new File(context.getFilesDir(), "launcher/selected_instance.json"));
		assertEquals(FULL, launch.getString("compat_pack_id"));
		assertEquals("exact", launch.getString("compat_target_id"));
		assertEquals(new File(context.getFilesDir(), "compat-packs/" + FULL + "/variants/exact/STS2Mobile.dll").getAbsolutePath(), launch.getString("selected_compat_dll"));
	}

	@Test public void importingMatchingFullUpgradesAnAcceptedAutomaticRecommendation() throws Exception {
		defaultProfile();
		packs.selectPack(OFFLINE, "offline-any", LaunchProfileManager.COMPAT_SELECTION_AUTO);
		importFull();
		assertEquals(FULL, profiles.getSelectedProfile().compatPackId);
		assertEquals("exact", profiles.getSelectedProfile().compatTargetId);
	}

	@Test public void explicitlyPickingTheSameOfflinePackSurvivesImportReuseAndMetadataEdits() throws Exception {
		LaunchProfileManager.LaunchProfile original = defaultProfile();
		packs.selectPack(OFFLINE, "offline-any", LaunchProfileManager.COMPAT_SELECTION_MANUAL);
		profiles.updateProfile(original.id, PAYLOAD, "Renamed", "isolated", "isolated", "stale", "stale", null);
		importFull();
		profiles.refreshAutomaticOfflineCompatSelection();
		profiles.createOrSelectDefaultProfileForPayload(profiles.readPayload(PAYLOAD), true);
		LaunchProfileManager.LaunchProfile retained = profiles.getSelectedProfile();
		assertEquals(OFFLINE, retained.compatPackId);
		assertEquals("offline-any", retained.compatTargetId);
		assertEquals(LaunchProfileManager.COMPAT_SELECTION_MANUAL, retained.compatSelectionSource);
		assertEquals("Renamed", retained.displayName);
		assertEquals("isolated", retained.saveMode);
		assertEquals("isolated", retained.modsMode);
	}

	@Test public void legacyOrUnrecognizedSelectionSourceNeverAssumesAutomatic() throws Exception {
		LaunchProfileManager.LaunchProfile original = defaultProfile();
		File file = new File(original.dir, LaunchProfileManager.PROFILE_FILE_NAME);
		for (String source : new String[] {null, "future-source"}) {
			JSONObject legacy = json(file);
			if (source == null) legacy.remove("compat_selection_source");
			else legacy.put("compat_selection_source", source);
			put(file, legacy.toString());
			installDirectory(fullManifest(), true);
			profiles.selectProfile(original.id);
			assertEquals(OFFLINE, profiles.getSelectedProfile().compatPackId);
			assertEquals(LaunchProfileManager.COMPAT_SELECTION_UNKNOWN, profiles.getSelectedProfile().compatSelectionSource);
		}
	}

	@Test public void absentMismatchedAndIncompleteFullPacksKeepOfflineWithoutRewritingProfile() throws Exception {
		LaunchProfileManager.LaunchProfile original = defaultProfile();
		File file = new File(original.dir, LaunchProfileManager.PROFILE_FILE_NAME);
		byte[] before = Files.readAllBytes(file.toPath());
		profiles.refreshAutomaticOfflineCompatSelection();
		JSONObject mismatched = manifest("unmatched-full", new JSONArray().put(new JSONObject()
			.put("target_id", "other").put("version", "other-version")));
		installDirectory(mismatched, true);
		installDirectory(fullManifest(), false);
		profiles.refreshAutomaticOfflineCompatSelection();
		assertEquals(OFFLINE, profiles.getSelectedProfile().compatPackId);
		assertArrayEquals(before, Files.readAllBytes(file.toPath()));
	}

	@Test public void automaticFullBindingIsNotReplacedByANewerBetterCandidate() throws Exception {
		JSONObject versionOnly = manifest("fixture-version-only", new JSONArray().put(new JSONObject()
			.put("target_id", "version").put("version", VERSION)));
		installDirectory(versionOnly, true);
		LaunchProfileManager.LaunchProfile original = defaultProfile();
		assertEquals("fixture-version-only", original.compatPackId);
		importFull();
		profiles.selectProfile(original.id);
		assertEquals("fixture-version-only", profiles.getSelectedProfile().compatPackId);
		assertEquals("version", profiles.getSelectedProfile().compatTargetId);
	}

	@Test public void metadataSavedFromAnOldOfflineSnapshotPreservesTheUpgradedBinding() throws Exception {
		LaunchProfileManager.LaunchProfile oldSnapshot = defaultProfile();
		importFull();
		profiles.updateProfile(oldSnapshot.id, PAYLOAD, "Edited after upgrade", "global", "global",
			oldSnapshot.compatPackId, oldSnapshot.compatTargetId, null);
		assertEquals(FULL, profiles.getSelectedProfile().compatPackId);
		assertEquals("Edited after upgrade", profiles.getSelectedProfile().displayName);
	}

	private LaunchProfileManager.LaunchProfile defaultProfile() throws Exception {
		return profiles.createOrSelectDefaultProfileForPayload(profiles.readPayload(PAYLOAD), true);
	}

	private JSONObject fullManifest() throws Exception {
		return manifest(FULL, new JSONArray()
			.put(new JSONObject().put("target_id", "other").put("version", "other-version"))
			.put(new JSONObject().put("target_id", "exact").put("version", VERSION).put("sts2_dll_sha256", DLL_SHA)));
	}

	private JSONObject manifest(String packId, JSONArray targets) throws Exception {
		return new JSONObject().put("schema", 2).put("pack_id", packId)
			.put("display_name", packId).put("compat_version", "fixture-1").put("targets", targets);
	}

	private void installDirectory(JSONObject manifest, boolean complete) throws Exception {
		File root = new File(context.getFilesDir(), "compat-packs/" + manifest.getString("pack_id"));
		put(new File(root, "compat_manifest.json"), manifest.toString());
		JSONArray targets = manifest.getJSONArray("targets");
		for (int i = 0; i < targets.length(); i++) {
			String target = targets.getJSONObject(i).getString("target_id");
			put(new File(root, "variants/" + target + "/STS2Mobile.dll"), "synthetic-compat");
			if (complete) put(new File(root, "variants/" + target + "/port_compat.pck"), "GDPCsynthetic-overlay");
		}
	}

	private void importFull() throws Exception {
		File zip = new File(context.getCacheDir(), "full-compat.zip");
		JSONObject manifest = fullManifest();
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
			zipEntry(out, "compat_manifest.json", manifest.toString());
			JSONArray targets = manifest.getJSONArray("targets");
			for (int i = 0; i < targets.length(); i++) {
				String target = targets.getJSONObject(i).getString("target_id");
				zipEntry(out, "variants/" + target + "/STS2Mobile.dll", "synthetic-compat");
				zipEntry(out, "variants/" + target + "/port_compat.pck", "GDPCsynthetic-overlay");
			}
		}
		packs.importCompatPack(Uri.fromFile(zip));
	}

	private static void zipEntry(ZipOutputStream out, String name, String content) throws Exception {
		out.putNextEntry(new ZipEntry(name));
		out.write(content.getBytes(StandardCharsets.UTF_8));
		out.closeEntry();
	}

	private static JSONObject json(File file) throws Exception {
		return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
	}

	private static void put(File file, String content) throws Exception {
		Files.createDirectories(file.getParentFile().toPath());
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}
}
