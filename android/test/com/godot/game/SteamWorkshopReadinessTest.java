package com.godot.game;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.godot.game.steam.auth.SteamAuthPreferencesShadow;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class,
	shadows = { AndroidLinuxTestShadow.class, SteamAuthPreferencesShadow.class,
		SteamWorkshopReadinessTest.OfflineCatalog.class, SteamWorkshopReadinessTest.CountedLibrary.class },
	instrumentedPackages = { "com.godot.game.steam.auth" })
@LooperMode(LooperMode.Mode.PAUSED)
public class SteamWorkshopReadinessTest {
	private static final SteamWorkshopCatalog.Item ITEM = new SteamWorkshopCatalog.Item(2868840, "90001", "Test item", "", "", "", 0L, 0L, 0, 0, 0L);
	private static final AtomicInteger libraryReads = new AtomicInteger();
	private static volatile CountDownLatch allowLibraryRead;
	private static volatile List<SteamWorkshopLibrary.Entry> records;
	private SteamWorkshopActivity activity;

	@Implements(value = SteamWorkshopCatalog.class, isInAndroidSdk = false)
	public static class OfflineCatalog {
		@Implementation protected SteamWorkshopCatalog.SearchResult search(String query, int page, int size,
			SteamWorkshopCatalog.SortOption sort, SteamWorkshopCatalog.TimeWindow time) {
			return new SteamWorkshopCatalog.SearchResult(1, page, Collections.singletonList(ITEM));
		}
		@Implementation protected SteamWorkshopCatalog.Detail loadDetail(SteamWorkshopCatalog.Item item) {
			return new SteamWorkshopCatalog.Detail(item, "Test detail", Collections.emptyList(), Collections.emptyList());
		}
	}

	@Implements(value = SteamWorkshopLibrary.class, isInAndroidSdk = false)
	public static class CountedLibrary {
		@Implementation protected List<SteamWorkshopLibrary.Entry> listEntries() throws InterruptedException {
			assertNotSame(Looper.getMainLooper(), Looper.myLooper());
			libraryReads.incrementAndGet();
			CountDownLatch gate = allowLibraryRead;
			if (gate != null) assertTrue(gate.await(15, TimeUnit.SECONDS));
			return records;
		}
	}

	@Before public void resetCount() {
		libraryReads.set(0);
		allowLibraryRead = null;
		records = Collections.emptyList();
	}

	private ActivityController<SteamWorkshopActivity> host() {
		ActivityController<SteamWorkshopActivity> host = Robolectric.buildActivity(SteamWorkshopActivity.class);
		activity = host.get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		return host;
	}

	@Test public void heldInstallAllowsCatalogDetailAndBackButNoLocalReadsOrDownloads() throws Exception {
		try (ActivityController<SteamWorkshopActivity> host = host()) {
			ExtraSettingsRepository repo = new ExtraSettingsRepository(activity);
			ModInstallStore store = new ModInstallStore(activity.getFilesDir(), repo.getModsRootDir());
			try (ModInstallStore.Transaction tx = store.beginRemoval(Collections.emptyList(), "")) {
				host.setup();
				await(() -> field("lastSearchResult") != null && notice(R.string.mod_install_waiting) != null);
				assertNull(field("library"));
				assertTrue(control(false) instanceof ProgressBar);
				call("showItemDetails", new Class<?>[] { SteamWorkshopCatalog.Item.class }, ITEM);
				await(() -> field("lastDetailResult") != null);
				assertEquals(1, ((View) field("screenHost")).getTag());
				assertTrue(control(true) instanceof ProgressBar);
				call("downloadAndImport", new Class<?>[] { SteamWorkshopCatalog.Item.class }, ITEM);
				assertTrue(((Map<?, ?>) field("downloadTasks")).isEmpty());
				assertTrue(((List<?>) field("pendingDownloadQueue")).isEmpty());
				activity.onBackPressed();
				assertEquals(0, ((View) field("screenHost")).getTag());
				call("showDownloads", new Class<?>[0]);
				assertFalse(((View) field("checkUpdatesButton")).isEnabled());
				call("checkTrackedUpdates", new Class<?>[0]);
				call("showSettings", new Class<?>[0]);
				activity.onBackPressed();
				assertEquals(0, ((View) field("screenHost")).getTag());
				assertEquals(0, libraryReads.get());

				host.pause();
				tx.commit();
				shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
				assertEquals(0, libraryReads.get());
				host.resume();
				await(() -> (boolean) field("libraryReady"));
				assertEquals(1, libraryReads.get());
				assertTrue(control(true) instanceof MaterialButton);
				assertTrue(((View) field("checkUpdatesButton")).isEnabled());
			}
		}
	}

	@Test public void aScanFromBeforePauseCannotAdmitReadsOrActionsOnReturn() throws Exception {
		try (ActivityController<SteamWorkshopActivity> host = host()) {
			ExtraSettingsRepository repo = new ExtraSettingsRepository(activity);
			ModInstallStore store = new ModInstallStore(activity.getFilesDir(), repo.getModsRootDir());
			allowLibraryRead = new CountDownLatch(1);
			try {
				host.setup();
				await(() -> libraryReads.get() == 1);
				assertFalse((boolean) field("libraryReady"));
				host.pause();
				try (ModInstallStore.Transaction tx = store.beginRemoval(Collections.emptyList(), "")) {
					host.resume();
					await(() -> notice(R.string.mod_install_waiting) != null);
					allowLibraryRead.countDown();
					await(() -> !(boolean) field("libraryRefreshInFlight"));
					call("requestLibraryRefresh", new Class<?>[0]);
					assertTrue(control(false) instanceof ProgressBar);
					assertEquals(1, libraryReads.get());
					tx.commit();
				}
				await(() -> (boolean) field("libraryReady"));
				assertEquals(2, libraryReads.get());
			} finally {
				allowLibraryRead.countDown();
			}
		}
	}

	@Test public void anInstalledRowRechecksItsManifestBeforeShowingLocalDetail() throws Exception {
		try (ActivityController<SteamWorkshopActivity> host = host()) {
			ExtraSettingsRepository repo = new ExtraSettingsRepository(activity);
			SteamWorkshopPreferences.setAutoCheckUpdatesEnabled(activity, false);
			File root = repo.getWorkshopItemInstallDir("workshop", ITEM.getPublishedFileId(), "public");
			Files.createDirectories(root.toPath());
			File manifest = new File(root, "Fixture.json");
			Files.write(manifest.toPath(), "{\"id\":\"Fixture\",\"pck_name\":\"Fixture\"}".getBytes(StandardCharsets.UTF_8));
			Files.write(new File(root, "Fixture.dll").toPath(), new byte[] { 1 });
			records = Collections.singletonList(SteamWorkshopLibrary.Entry.fromJson(new JSONObject()
				.put("published_file_id", ITEM.getPublishedFileId()).put("installed_root_path", root.getAbsolutePath())
				.put("mods_scope", "global").put("workshop_branch", "public")));
			host.setup();
			await(() -> (boolean) field("libraryReady"));
			View rowAction = control(false);
			assertEquals(activity.getString(R.string.workshop_view_details), rowAction.getContentDescription());
			Files.delete(manifest.toPath()); // The published snapshot still describes an installed MOD.
			assertTrue(rowAction.performClick());
			await(() -> libraryReads.get() >= 3 && !(boolean) field("libraryRefreshInFlight"));
			assertNull(field("localActionDialog"));
			assertEquals(activity.getString(R.string.workshop_download_again), control(false).getContentDescription());
		}
	}

	@Test public void realReadinessFailureOffersBackAndRetriesRecoveryNotJustTheIndex() throws Exception {
		try (ActivityController<SteamWorkshopActivity> host = host()) {
			ExtraSettingsRepository repo = new ExtraSettingsRepository(activity);
			repo.ensureAppDirectories();
			File[] scopes = new File(activity.getFilesDir(), "launcher/mod-installs").listFiles();
			assertNotNull(scopes);
			File receipt = new File(scopes[0], "index.json");
			byte[] original = receipt.isFile() ? Files.readAllBytes(receipt.toPath()) : null;
			Files.write(receipt.toPath(), "invalid receipt".getBytes(StandardCharsets.UTF_8));
			try {
				host.setup();
				await(() -> field("lastSearchResult") != null && notice(R.string.mod_install_retry) != null);
				assertNotNull(notice(R.string.mod_install_back));
				assertTrue(notice(R.string.mod_install_retry).performClick());
				await(() -> notice(R.string.mod_install_retry) != null);
				assertEquals(0, libraryReads.get());
				assertFalse((boolean) field("libraryReady"));
			} finally {
				if (original == null) Files.delete(receipt.toPath());
				else Files.write(receipt.toPath(), original);
			}
			assertTrue(notice(R.string.mod_install_retry).performClick());
			await(() -> (boolean) field("libraryReady"));
			assertEquals(1, libraryReads.get());
			assertNull(notice(R.string.mod_install_retry));
		}
	}

	private View notice(int label) throws Exception {
		ViewGroup banner = (ViewGroup) field("localNotice");
		for (int i = 0; i < banner.getChildCount(); i++) {
			View child = banner.getChildAt(i);
			if (child instanceof TextView && activity.getString(label).contentEquals(((TextView) child).getText())) return child;
		}
		return null;
	}

	private View control(boolean detail) throws Exception {
		return (View) call("buildDownloadControl", new Class<?>[] { SteamWorkshopCatalog.Item.class, boolean.class }, ITEM, detail);
	}

	private Object field(String name) throws Exception {
		Field field = SteamWorkshopActivity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(activity);
	}

	private Object call(String name, Class<?>[] types, Object... args) throws Exception {
		Method method = SteamWorkshopActivity.class.getDeclaredMethod(name, types);
		method.setAccessible(true);
		return method.invoke(activity, args);
	}

	private interface Condition { boolean reached() throws Exception; }

	private static void await(Condition condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.reached() && System.nanoTime() < deadline) {
			shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
			Thread.sleep(5);
		}
		assertTrue("Workshop state timed out", condition.reached());
	}
}
