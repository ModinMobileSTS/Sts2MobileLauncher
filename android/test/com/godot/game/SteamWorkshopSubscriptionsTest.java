package com.godot.game;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.godot.game.steam.auth.SteamAuthPreferencesShadow;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class,
	shadows = { SteamAuthPreferencesShadow.class, AndroidLinuxTestShadow.class },
	instrumentedPackages = { "com.godot.game.steam.auth" })
@LooperMode(LooperMode.Mode.PAUSED)
public class SteamWorkshopSubscriptionsTest {
	private SteamWorkshopActivity activity;

	@Before public void setUp() throws Exception {
		activity = Robolectric.buildActivity(SteamWorkshopActivity.class).get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		activity.setContentView((View) invoke("buildContent", new Class<?>[0]));
	}

	@After public void tearDown() throws Exception {
		((Handler) field("mainHandler")).removeCallbacksAndMessages(null);
		((ExecutorService) field("libraryExecutor")).shutdownNow();
		activity.getSharedPreferences("sts2_steam_auth", Context.MODE_PRIVATE).edit().clear().commit();
	}

	@Test public void switchingAccountWhileLoadingClearsPreviousSubscriptionsAndRejectsLateResults() throws Exception {
		saveAccount("first-account", 1L);
		String accountKey = (String) invoke("steamAccountKey", new Class<?>[0]);
		setField("observedSteamAccountKey", accountKey);
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
		setField("observedSteamAccountKey", invoke("steamAccountKey", new Class<?>[0]));
		setField("showingSubscriptions", true);
		setField("busy", true);
		invoke("rebuildDrawerContent", new Class<?>[0]);
		View drawer = (View) field("drawerContent");
		assertTrue(hasText(drawer, activity.getString(R.string.workshop_subscribed_mods)));

		activity.getSharedPreferences("sts2_steam_auth", Context.MODE_PRIVATE).edit().clear().commit();
		invoke("refreshSteamAccount", new Class<?>[0]);

		assertFalse(hasText(drawer, activity.getString(R.string.workshop_subscribed_mods)));
		assertTrue(hasText(drawer, activity.getString(R.string.workshop_all_mods)));
		assertEquals(false, field("showingSubscriptions"));
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
		setField("showingSubscriptions", false);
		assertEquals(false, invoke("hasMoreSearchResults", signature, 1, first));
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
