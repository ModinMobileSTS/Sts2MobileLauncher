package com.godot.game;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.godot.game.steam.auth.SteamAuthStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class,
	shadows = { SteamWorkshopSubscriptionsTest.WorkshopAuthPrefs.class, SteamWorkshopSubscriptionsTest.Pages.class, AndroidLinuxTestShadow.class },
	instrumentedPackages = { "com.godot.game.steam.auth" })
@LooperMode(LooperMode.Mode.PAUSED)
public class SteamWorkshopSubscriptionsTest {
	private SteamWorkshopActivity activity;
	private static final AtomicInteger reads = new AtomicInteger();
	private static volatile CountDownLatch readStarted, allowRead;
	private static volatile Thread readingThread;
	private static final AtomicInteger subscriptionCalls = new AtomicInteger();
	private static volatile CountDownLatch pageStarted, allowPage;

	@Implements(value = SteamAuthStore.class, isInAndroidSdk = false)
	public static class WorkshopAuthPrefs {
		@Implementation protected static SharedPreferences prefs(Context context) throws InterruptedException {
			assertNotSame("Encrypted preferences must not be opened by the UI thread", Looper.getMainLooper(), Looper.myLooper());
			reads.incrementAndGet();
			CountDownLatch gate = allowRead;
			if (gate != null) {
				readingThread = Thread.currentThread();
				readStarted.countDown();
				assertTrue(gate.await(10, TimeUnit.SECONDS));
			}
			return context.getApplicationContext().getSharedPreferences("sts2_steam_auth", Context.MODE_PRIVATE);
		}
	}

	@Implements(value = SteamWorkshopCatalog.class, isInAndroidSdk = false)
	public static class Pages {
		@Implementation protected SteamWorkshopCatalog.SearchResult loadSubscriptions(int page, int size) throws InterruptedException {
			subscriptionCalls.incrementAndGet();
			if (page == 2) {
				pageStarted.countDown();
				assertTrue(allowPage.await(10, TimeUnit.SECONDS));
			}
			return new SteamWorkshopCatalog.SearchResult(31, page, Collections.singletonList(item("private-" + page)));
		}
		@Implementation protected SteamWorkshopCatalog.SearchResult search(String query, int page, int size,
			SteamWorkshopCatalog.SortOption sort, SteamWorkshopCatalog.TimeWindow window) {
			return new SteamWorkshopCatalog.SearchResult(1, page, Collections.singletonList(item("public")));
		}
	}

	@Before public void setUp() throws Exception {
		reads.set(0);
		allowRead = null;
		subscriptionCalls.set(0);
		pageStarted = new CountDownLatch(1);
		allowPage = new CountDownLatch(1);
		activity = Robolectric.buildActivity(SteamWorkshopActivity.class).get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		activity.setContentView((View) invoke("buildContent", new Class<?>[0]));
		setField("resumed", true);
	}

	@After public void tearDown() throws Exception {
		if (allowRead != null) allowRead.countDown();
		allowPage.countDown();
		((Handler) field("mainHandler")).removeCallbacksAndMessages(null);
		((ExecutorService) field("libraryExecutor")).shutdownNow();
		activity.getSharedPreferences("sts2_steam_auth", Context.MODE_PRIVATE).edit().clear().commit();
	}

	@Test public void switchingAccountWhileLoadingClearsPreviousSubscriptionsAndRejectsLateResults() throws Exception {
		saveAccount("first-account", 1L);
		refreshAccount();
		String accountKey = (String) field("observedSteamAccountKey");
		setField("subscriptionsAccountKey", accountKey);
		setField("showingSubscriptions", true);
		setField("busy", true);
		setField("listGeneration", 4);
		setField("loadingMoreResults", true);
		SteamWorkshopCatalog.SearchResult old = new SteamWorkshopCatalog.SearchResult(1, 1,
			Collections.singletonList(item("90001")));
		setField("lastSearchResult", old);
		RecyclerView.Adapter<?> adapter = ((RecyclerView) field("searchList")).getAdapter();
		Method replace = adapter.getClass().getDeclaredMethod("replace", java.util.List.class, boolean.class);
		replace.setAccessible(true);
		replace.invoke(adapter, old.getItems(), true);

		saveAccount("second-account", 2L);
		refreshAccount();
		assertEquals(false, invoke("isCurrentListRequest", new Class<?>[] { int.class, String.class }, 4, accountKey));

		assertEquals(0, adapter.getItemCount());
		assertNull(field("lastSearchResult"));
		assertEquals(false, field("showingSubscriptions"));
		assertEquals(false, field("loadingMoreResults"));
		assertEquals(false, field("hasMoreResults"));
		assertEquals(activity.getString(R.string.workshop_title), ((TextView) field("workshopListTitle")).getText().toString());
		assertEquals(false, invoke("isCurrentListRequest", new Class<?>[] { int.class, String.class }, 4, accountKey));
	}

	@Test public void logoutRemovesSubscriptionEntryEvenWhenARequestIsInFlight() throws Exception {
		saveAccount("first-account", 1L);
		refreshAccount();
		setField("subscriptionsAccountKey", field("observedSteamAccountKey"));
		setField("showingSubscriptions", true);
		setField("busy", true);
		invoke("rebuildDrawerContent", new Class<?>[0]);
		View drawer = (View) field("drawerContent");
		assertTrue(hasText(drawer, activity.getString(R.string.workshop_subscribed_mods)));

		activity.getSharedPreferences("sts2_steam_auth", Context.MODE_PRIVATE).edit().clear().commit();
		refreshAccount();

		assertFalse(hasText(drawer, activity.getString(R.string.workshop_subscribed_mods)));
		assertTrue(hasText(drawer, activity.getString(R.string.workshop_all_mods)));
		assertEquals(false, field("showingSubscriptions"));
	}

	@Test public void staleDrawerStatusCannotStartAnAuthenticatedRequest() throws Exception {
		saveAccount("first-account", 1L);
		refreshAccount();
		setField("catalog", new SteamWorkshopCatalog(activity));
		saveAccount("second-account", 2L); // No intervening status refresh.
		invoke("loadWorkshopList", new Class<?>[] { String.class, boolean.class }, "", true);
		awaitItem("public");
		assertEquals(0, subscriptionCalls.get());
		assertEquals("second-account:2", field("observedSteamAccountKey"));
		assertEquals(false, field("showingSubscriptions"));
	}

	@Test public void lateSecondPageAfterSameNameReloginCannotAppendToPrivateList() throws Exception {
		saveAccount("same-account", 1L);
		refreshAccount();
		setField("catalog", new SteamWorkshopCatalog(activity));
		invoke("loadWorkshopList", new Class<?>[] { String.class, boolean.class }, "", true);
		awaitItem("private-1");
		invoke("loadMoreSearchResults", new Class<?>[0]);
		try {
			assertTrue(pageStarted.await(10, TimeUnit.SECONDS));
			saveAccount("same-account", 2L);
		} finally {
			allowPage.countDown();
		}
		awaitItem("public");
		assertEquals(false, field("showingSubscriptions"));
		assertEquals("same-account:2", field("observedSteamAccountKey"));
		assertEquals(1, ((RecyclerView) field("searchList")).getAdapter().getItemCount());
	}

	@Test public void queuedOldIdentityCannotReappearAfterPauseAndResume() throws Exception {
		saveAccount("first-account", 1L);
		refreshAccount();
		setField("subscriptionsAccountKey", field("observedSteamAccountKey"));
		setField("showingSubscriptions", true);
		SteamWorkshopCatalog.SearchResult old = new SteamWorkshopCatalog.SearchResult(1, 1, Collections.singletonList(item("90001")));
		invoke("showSearchResults", new Class<?>[] { SteamWorkshopCatalog.SearchResult.class }, old);
		int generation = (int) field("listGeneration");
		// Queue the old status callback but don't drain the UI looper until the session changes.
		readStarted = new CountDownLatch(1);
		allowRead = new CountDownLatch(1);
		invoke("refreshSteamAccount", new Class<?>[0]);
		assertTrue(readStarted.await(10, TimeUnit.SECONDS));
		allowRead.countDown();
		readingThread.join(TimeUnit.SECONDS.toMillis(10));
		assertFalse(readingThread.isAlive()); // The status was read before logout, not after resume.
		assertEquals(true, field("accountReadInFlight"));
		setField("resumed", false);
		invoke("invalidateSteamStatus", new Class<?>[0]);
		assertNull(field("lastSearchResult"));
		assertEquals(0, ((RecyclerView) field("searchList")).getAdapter().getItemCount());
		assertEquals(false, invoke("isCurrentListRequest", new Class<?>[] { int.class, String.class }, generation, "first-account:1"));
		saveAccount("second-account", 2L);
		setField("resumed", true);
		refreshAccount();
		assertEquals("second-account:2", field("observedSteamAccountKey"));
		assertEquals(false, field("showingSubscriptions"));
		assertNull(field("lastSearchResult"));
	}

	@Test public void buildingAndRefreshingViewsShareOneInFlightWorkerRead() throws Exception {
		assertEquals(0, reads.get()); // Including the drawer built in setUp.
		saveAccount("first-account", 1L);
		readStarted = new CountDownLatch(1);
		allowRead = new CountDownLatch(1);
		try {
			invoke("refreshSteamAccount", new Class<?>[0]);
			assertTrue(readStarted.await(10, TimeUnit.SECONDS));
			invoke("refreshSteamAccount", new Class<?>[0]);
			invoke("showSettings", new Class<?>[0]);
			invoke("refreshSettingsSummary", new Class<?>[0]);
			invoke("rebuildDrawerContent", new Class<?>[0]);
			assertEquals(1, reads.get());
		} finally {
			allowRead.countDown();
		}
		awaitAccountRead();
		assertEquals(1, reads.get());
		assertTrue(((TextView) field("settingsSteamStatusText")).getText().toString().contains("first-account"));
	}

	@Test public void subscriptionsUseServerTotalAndPagePositionRatherThanVisibleRowCount() throws Exception {
		setField("showingSubscriptions", true);
		SteamWorkshopCatalog.SearchResult first = new SteamWorkshopCatalog.SearchResult(31, 1,
			Collections.singletonList(item("90001")));
		SteamWorkshopCatalog.SearchResult last = new SteamWorkshopCatalog.SearchResult(31, 2,
			Collections.singletonList(item("90002")));
		Class<?>[] signature = { int.class, SteamWorkshopCatalog.SearchResult.class };
		assertEquals(true, invoke("hasMoreSearchResults", signature, 1, first));
		assertEquals(false, invoke("hasMoreSearchResults", signature, 2, last));
		assertEquals(true, invoke("hasMoreSearchResults", signature, 0,
			new SteamWorkshopCatalog.SearchResult(31, 1, Collections.emptyList())));
		setField("showingSubscriptions", false);
		assertEquals(false, invoke("hasMoreSearchResults", signature, 1, first));
	}

	private void refreshAccount() throws Exception {
		invoke("refreshSteamAccount", new Class<?>[0]);
		awaitAccountRead();
	}

	private void awaitAccountRead() throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while ((boolean) field("accountReadInFlight") && System.nanoTime() < deadline) {
			shadowOf(Looper.getMainLooper()).idle();
			Thread.sleep(5);
		}
		assertEquals(false, field("accountReadInFlight"));
	}

	private void awaitItem(String id) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			shadowOf(Looper.getMainLooper()).idle();
			SteamWorkshopCatalog.SearchResult page = (SteamWorkshopCatalog.SearchResult) field("lastSearchResult");
			if (page != null && !page.getItems().isEmpty() && id.equals(page.getItems().get(0).getPublishedFileId())) return;
			Thread.sleep(5);
		}
		fail("Workshop page did not arrive: " + id);
	}

	private void saveAccount(String name, long generation) {
		activity.getSharedPreferences("sts2_steam_auth", Context.MODE_PRIVATE).edit()
			.putString("account_name", name).putString("refresh_token", "synthetic-token")
			.putLong("last_auth_at_ms", generation).commit();
	}

	private static boolean hasText(View view, String text) {
		if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int index = 0; index < group.getChildCount(); index++) {
				if (hasText(group.getChildAt(index), text)) return true;
			}
		}
		return false;
	}

	private Object field(String name) throws Exception {
		Field field = SteamWorkshopActivity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(activity);
	}

	private void setField(String name, Object value) throws Exception {
		Field field = SteamWorkshopActivity.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(activity, value);
	}

	private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
		Method method = SteamWorkshopActivity.class.getDeclaredMethod(name, types);
		method.setAccessible(true);
		return method.invoke(activity, args);
	}

	private static SteamWorkshopCatalog.Item item(String id) {
		return new SteamWorkshopCatalog.Item(2868840, id, "Synthetic " + id, "", "", "", 0L, 0L, 0, 0, 1L);
	}
}
