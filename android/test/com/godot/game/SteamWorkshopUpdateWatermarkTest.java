package com.godot.game;

import android.app.Application;
import android.os.Handler;
import android.view.View;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class, shadows = AndroidLinuxTestShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SteamWorkshopUpdateWatermarkTest {
	private SteamWorkshopActivity activity;
	private SteamWorkshopLibrary library;

	@Before public void setUp() throws Exception {
		activity = Robolectric.buildActivity(SteamWorkshopActivity.class).get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		activity.setContentView(new FrameLayout(activity));
		library = new SteamWorkshopLibrary(activity);
		library.clearEntries();
	}

	@After public void tearDown() throws Exception {
		((Handler) value(activity, "mainHandler")).removeCallbacksAndMessages(null);
		((ExecutorService) value(activity, "libraryExecutor")).shutdownNow();
	}

	@Test public void updatePublishedDuringDownloadAndFastLocalClockBothRemainVisible() throws Exception {
		seed(
			entry("90001", "public", 100_000L, 300_000L),
			entry("90002", "public-beta", 100_000L, 4_102_444_800_000L)
		);
		Map<String, SteamWorkshopCatalog.Item> details = Map.of("90001", item("90001", 200L), "90002", item("90002", 200L));

		SteamWorkshopLibrary.UpdateSummary summary = library.updateCheckResults(details);

		assertEquals(2, summary.availableCount);
		assertEquals(0, summary.currentCount);
		List<SteamWorkshopLibrary.Entry> stored = library.listEntries();
		assertEquals("90002", stored.get(0).publishedFileId); // Local time still controls display sorting.
		assertEquals("90001", stored.get(1).publishedFileId);
		for (SteamWorkshopLibrary.Entry entry : stored) {
			assertEquals("available", entry.updateStatus);
			assertEquals(100_000L, entry.installedRemoteUpdatedAtMs);
			assertEquals("18446744073709551614", entry.resolvedManifestId);
			assertTrue(activityHasUpdate(details.get(entry.publishedFileId), entry));
		}
		assertEquals("public-beta", stored.get(0).workshopBranch);
		assertEquals(4_102_444_800_000L, stored.get(0).installedAtMs);
		assertEquals("public", stored.get(1).workshopBranch);
		assertEquals(300_000L, stored.get(1).installedAtMs);
	}

	@Test public void equalOlderAndUnknownRemoteTimesDoNotClaimAnUpdate() throws Exception {
		seed(
			entry("90001", "public", 100_000L, 300_000L),
			entry("90002", "public", 100_000L, 300_000L),
			entry("90003", "public", 100_000L, 300_000L),
			entry("90004", "public", 0L, 300_000L)
		);
		Map<String, SteamWorkshopCatalog.Item> details = Map.of(
			"90001", item("90001", 100L), "90002", item("90002", 99L),
			"90003", item("90003", 0L), "90004", item("90004", 200L)
		);

		SteamWorkshopLibrary.UpdateSummary summary = library.updateCheckResults(details);

		assertEquals(0, summary.availableCount);
		for (SteamWorkshopLibrary.Entry entry : library.listEntries()) {
			assertNotEquals("available", entry.updateStatus);
			assertFalse(activityHasUpdate(details.get(entry.publishedFileId), entry));
		}
	}

	@SuppressWarnings("unchecked")
	@Test public void listDownloadControlOffersAnUpdateForTheInstalledBranch() throws Exception {
		seed(entry("90001", "public-beta", 100_000L, 300_000L));
		SteamWorkshopLibrary.Entry installed = library.listEntries().get(0);
		ExtraSettingsRepository repository = new ExtraSettingsRepository(activity);
		File root = new File(repository.getModsRootDir(), "watermark-fixture");
		Files.createDirectories(root.toPath());
		Files.write(new File(root, "Fixture.json").toPath(), "{\"id\":\"Fixture\",\"pck_name\":\"Fixture\"}".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(root, "Fixture.dll").toPath(), "synthetic-not-executable".getBytes(StandardCharsets.UTF_8));
		List<ExtraSettingsRepository.ModEntry> mods = repository.listInstalledModManifestsUnder(root);
		assertEquals("Fixture", mods.get(0).modId);
		Object snapshot = value(activity, "librarySnapshot");
		((Map<String, SteamWorkshopLibrary.Entry>) value(snapshot, "byId")).put(installed.publishedFileId, installed);
		((Map<String, List<ExtraSettingsRepository.ModEntry>>) value(snapshot, "installed")).put(installed.key(), mods);
		set(activity, "libraryReady", true);
		set(activity, "busy", true);
		set(activity, "branchDialogActive", true); // Keep the resulting request queued; no network.
		Method build = SteamWorkshopActivity.class.getDeclaredMethod("buildDownloadControl", SteamWorkshopCatalog.Item.class, boolean.class);
		build.setAccessible(true);
		View button = (View) build.invoke(activity, item("90001", 200L), false);

		button.performClick();

		List<?> queue = (List<?>) value(activity, "pendingDownloadQueue");
		assertEquals(1, queue.size());
		assertEquals(true, value(queue.get(0), "updateExisting"));
		SteamWorkshopDownloader.BranchOption selected = (SteamWorkshopDownloader.BranchOption) value(queue.get(0), "selectedOption");
		assertEquals("public-beta", selected.getBranch());
		assertEquals("18446744073709551614", library.listEntries().get(0).resolvedManifestId);
	}

	@Test public void previouslyObservedRemoteTimeStillSuppliesMissingListMetadata() throws Exception {
		SteamWorkshopLibrary.Entry installed = SteamWorkshopLibrary.Entry.fromJson(
			entry("90001", "public", 100_000L, 300_000L).put("remote_updated_at_ms", 200_000L)
		);
		assertTrue(activityHasUpdate(item("90001", 0L), installed));
	}

	private boolean activityHasUpdate(SteamWorkshopCatalog.Item item, SteamWorkshopLibrary.Entry entry) throws Exception {
		Method method = SteamWorkshopActivity.class.getDeclaredMethod("hasWorkshopUpdate", SteamWorkshopCatalog.Item.class, SteamWorkshopLibrary.Entry.class);
		method.setAccessible(true);
		return (boolean) method.invoke(activity, item, entry);
	}

	private void seed(JSONObject... entries) throws Exception {
		File index = new File(activity.getFilesDir(), "workshop/library/index.json");
		Files.createDirectories(index.getParentFile().toPath());
		Files.write(index.toPath(), new JSONArray(Arrays.asList(entries)).toString().getBytes(StandardCharsets.UTF_8));
	}

	private static JSONObject entry(String id, String branch, long remoteInstalledAt, long localInstalledAt) throws Exception {
		return new JSONObject()
			.put("app_id", "2868840").put("published_file_id", id).put("title", "Synthetic " + id)
			.put("workshop_branch", branch).put("branch_mode", "manual")
			.put("installed_remote_updated_at_ms", remoteInstalledAt).put("installed_at_ms", localInstalledAt)
			.put("remote_updated_at_ms", remoteInstalledAt).put("update_status", "current")
			.put("resolved_manifest_id", "18446744073709551614").put("resolution_source", "cm_get_item_info_author_snapshot");
	}

	private static Object value(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static SteamWorkshopCatalog.Item item(String id, long remoteSeconds) {
		return new SteamWorkshopCatalog.Item(2868840, id, "Synthetic " + id, "", "", "", 0L, 0L, 0, 0, remoteSeconds);
	}
}
