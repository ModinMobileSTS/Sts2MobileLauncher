package com.godot.game;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowChoreographer;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 35, qualifiers = "en-rUS-w360dp-h400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class GamePageTest {
	private final HeldReads fast = new HeldReads(), stats = new HeldReads();
	private final List<String> clicks = new ArrayList<>();
	private ActivityController<AppCompatActivity> host;
	private AppCompatActivity activity;
	private LaunchProfileManager profiles;
	private ExtraSettingsRepository repo;
	private GamePage page;
	private View root;

	@Before public void setup() throws Exception {
		host = Robolectric.buildActivity(AppCompatActivity.class);
		activity = host.get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		host.setup();
		// Once the activity has a visible window, drive vsync explicitly: auto-advancing
		// the host clock would turn the hero animation into an endless queue drain.
		ShadowChoreographer.setPaused(true);
		ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
		profiles = new LaunchProfileManager(activity);
		repo = new ExtraSettingsRepository(activity);
		createProfile("one", true);
		repo.ensureAppDirectories();
		put(repo.getModsRootDir().toPath().resolve("deep/home.json"), "{\"id\":\"HomeMod\",\"name\":\"Home mod\"}");
		ExtraSettingsActions actions = (ExtraSettingsActions) Proxy.newProxyInstance(ExtraSettingsActions.class.getClassLoader(),
			new Class<?>[]{ExtraSettingsActions.class}, (proxy, method, args) -> { clicks.add(method.getName()); return null; });
		page = new GamePage(activity, repo, actions, fast, stats);
		root = page.build();
		activity.setContentView(root);
		page.setPageActive(true);
		idle();
		int width = ExtraSettingsUi.isWideLayout(activity) ? 900 : 360;
		root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY));
		root.layout(0, 0, width, 320);
		root.getViewTreeObserver().dispatchOnGlobalLayout();
	}

	@After public void close() {
		if (page != null) page.setPageActive(false);
		host.close();
	}

	@Test public void compactHomeIsActionableBeforeAnyReadsAndStatsRefreshInPlace() throws Exception {
		assertNotNull(text(R.string.game_hero_checking));
		assertNull(text(R.string.game_steam_login));
		assertNull(text(R.string.game_dashboard_saves_empty_value));
		TextView launch = text(R.string.launch_game);
		click(R.string.launch_game);
		click(R.string.game_action_import_body);
		click(R.string.game_dashboard_mods_title);
		click(R.string.game_dashboard_saves_title);
		click(R.string.steam_account_title);
		click(R.string.tab_settings);
		assertEquals(List.of("launchGame", "requestImportGamePayload", "openModsTab", "openSaveSettingsTab", "openSteamAccount", "openSettingsTab"), clicks);

		fast.finish();
		idle();
		assertNotNull(text(R.string.game_hero_ready_title_format, "one"));
		assertNotNull(text(R.string.game_dashboard_loading));
		assertNotNull(text(R.string.game_steam_login));
		ScrollView scroll = scrollView();
		scroll.scrollTo(0, 90);
		assertTrue(scroll.getScrollY() > 0);
		int position = scroll.getScrollY();
		stats.finish();
		idle();
		assertNotNull(text(R.string.game_dashboard_mods_value_format, 1, 1));
		assertNotNull(text(R.string.game_dashboard_saves_value_format, 0));

		JSONObject settings = new JSONObject();
		repo.setModDisabled(settings, "HomeMod", true);
		repo.saveSettingsJson(settings);
		Files.createDirectories(repo.getAccountRootDir().toPath().resolve("profile1"));
		page.refresh();
		page.refresh();
		idle();
		assertEquals(2, stats.size()); // One outstanding MOD read and one save read, not two rebuilds.
		stats.finish();
		idle();
		assertNotNull(text(R.string.game_dashboard_mods_value_format, 0, 1));
		assertNotNull(text(R.string.game_dashboard_saves_value_format, 1));
		assertSame(root, page.build());
		assertSame(launch, text(R.string.launch_game));
		assertEquals(position, scroll.getScrollY());

		// Once a read has completed but not published, supersede it several times. Only
		// one trailing read per card may be queued, and the obsolete count cannot flash.
		page.refresh();
		idle();
		Files.createDirectories(repo.getAccountRootDir().toPath().resolve("profile2"));
		stats.finish();
		Files.createDirectories(repo.getAccountRootDir().toPath().resolve("profile3"));
		page.refresh();
		page.refresh();
		idle();
		assertNotNull(text(R.string.game_dashboard_saves_value_format, 1));
		assertEquals(2, stats.size());
		stats.finish();
		idle();
		assertNotNull(text(R.string.game_dashboard_saves_value_format, 3));
	}

	@Test public void unreadableModSettingsDoNotHideLaunchOrSaveActions() throws Exception {
		put(repo.getSettingsFile().toPath(), "not json");
		fast.finish();
		stats.finish();
		idle();
		assertNotNull(text(R.string.unknown));
		assertNull(text(R.string.game_dashboard_mods_value_format, 0, 0));
		assertNotNull(text(R.string.game_dashboard_saves_value_format, 0));
		assertNotNull(text(R.string.game_hero_ready_title_format, "one"));
		click(R.string.launch_game);
		click(R.string.export_save);
		assertEquals(List.of("launchGame", "requestExportSave"), clicks);
	}

	@Config(qualifiers = "en-rUS-w900dp-h480dp-land-mdpi")
	@Test public void wideHomeDropsCompletedOldProfileAndInactiveResults() throws Exception {
		assertTrue(ExtraSettingsUi.isWideLayout(activity));
		assertNotNull(text(R.string.game_action_browse_files));
		LaunchProfileManager.LaunchProfile second = createProfile("two", false);
		fast.finish();
		stats.finish(); // Old results are now queued on the paused UI looper.
		profiles.selectProfile(second.id);
		idle(); // Delivery itself must notice the change, even without an explicit refresh.
		assertNull(text(R.string.game_hero_ready_title_format, "one"));
		assertNull(text(R.string.game_dashboard_mods_value_format, 1, 1));
		assertEquals(2, fast.size());
		assertEquals(2, stats.size());
		fast.finish();
		idle();
		assertNotNull(text(R.string.game_hero_ready_title_format, "two"));
		stats.finish();
		page.setPageActive(false); // Navigation, pause or destruction before delivery.
		idle();
		assertNotNull(text(R.string.game_dashboard_loading));
		assertNull(text(R.string.game_dashboard_mods_value_format, 0, 0));
		page.setPageActive(true);
		idle();
		stats.finish();
		idle();
		assertNotNull(text(R.string.game_dashboard_mods_value_format, 0, 0));
	}

	@Test public void heroTickerStopsAndRestartsOnClippingBackgroundAndWindowFocus() throws Exception {
		host.windowFocusChanged(true);
		fast.finish();
		idle();
		View hero = parent(parent(text(R.string.game_hero_ready_title_format, "one")));
		assertEquals(View.VISIBLE, hero.getWindowVisibility());
		assertTrue(requestsFrame(hero));
		ScrollView scroll = scrollView();
		scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
		idle();
		android.graphics.Rect bounds = new android.graphics.Rect();
		boolean shown = hero.getGlobalVisibleRect(bounds);
		assertFalse("Hero still visible after scroll: y=" + scroll.getScrollY() + " bounds=" + bounds, shown);
		assertFalse("Clipped hero must stop scheduling frames", requestsFrame(hero));
		scroll.scrollTo(0, 0);
		idle();
		assertTrue(requestsFrame(hero));
		scroll.setVisibility(View.INVISIBLE);
		assertFalse(requestsFrame(hero));
		scroll.setVisibility(View.VISIBLE);
		assertTrue(requestsFrame(hero));
		host.windowFocusChanged(false);
		assertFalse(requestsFrame(hero));
		host.windowFocusChanged(true);
		assertTrue(requestsFrame(hero));
		page.setPageActive(false);
		assertFalse(requestsFrame(hero));
		page.setPageActive(true);
		assertTrue(requestsFrame(hero));
		activity.setContentView(new View(activity));
		assertFalse(requestsFrame(hero));
	}

	private static boolean requestsFrame(View view) {
		shadowOf(view).clearWasInvalidated();
		idle();
		return shadowOf(view).wasInvalidated();
	}

	private LaunchProfileManager.LaunchProfile createProfile(String version, boolean select) throws Exception {
		Path game = profiles.getPayloadGameDir(version).toPath();
		JSONObject release = new JSONObject().put("version", version);
		put(game.resolve(PayloadManager.MANIFEST_FILE_NAME), new JSONObject().put("identity", new JSONObject().put("release_info", release)).toString());
		put(game.resolve("release_info.json"), release.toString());
		put(game.resolve(PayloadManager.PCK_FILE_NAME), "GDPCfixture");
		put(game.resolve(PayloadManager.STS2_DLL_PATH), "fixture");
		return profiles.createProfile(version, version, "isolated", "isolated", "", "", LaunchProfileManager.COMPAT_SELECTION_MANUAL, select);
	}

	private void click(int label) {
		View view = text(label);
		assertNotNull(activity.getString(label), view);
		while (!view.isClickable()) view = parent(view);
		assertTrue(view.performClick());
	}

	private TextView text(int resource, Object... args) { return findText(root, activity.getString(resource, args)); }
	private ScrollView scrollView() {
		View view = text(R.string.game_dashboard_mods_title);
		while (!(view instanceof ScrollView)) view = parent(view);
		return (ScrollView) view;
	}
	private static View parent(View child) { return (View) child.getParent(); }
	private static TextView findText(View view, String label) {
		if (view instanceof TextView text && label.contentEquals(text.getText())) return text;
		if (view instanceof ViewGroup group) {
			for (int i = 0; i < group.getChildCount(); i++) {
				TextView match = findText(group.getChildAt(i), label);
				if (match != null) return match;
			}
		}
		return null;
	}

	private static void put(Path file, String data) throws Exception {
		Files.createDirectories(file.getParent());
		Files.write(file, data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
	private static void idle() {
		// Each step delivers a vsync and drains the work it unblocks (including traversal barriers).
		for (int frame = 0; frame < 4; frame++) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
	}

	/** Deterministic slow-worker fixture: nothing runs until released, and reads never run on main. */
	private static final class HeldReads extends ArrayDeque<Runnable> implements Executor {
		@Override public void execute(Runnable task) { add(task); }
		void finish() throws Exception {
			FutureTask<Void> drain = new FutureTask<>(() -> { while (!isEmpty()) remove().run(); return null; });
			new Thread(drain).start();
			drain.get(10, TimeUnit.SECONDS);
		}
	}
}
