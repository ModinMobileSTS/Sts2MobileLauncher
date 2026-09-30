package com.godot.game;

import android.app.Application;
import android.os.Handler;
import android.view.View;
import android.widget.FrameLayout;
import com.google.android.material.button.MaterialButton;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class, shadows = AndroidLinuxTestShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SteamWorkshopBusyDownloadTest {
	private SteamWorkshopActivity activity;
	private FrameLayout content;

	@Before public void setUp() throws Exception {
		// Attach a real Activity and its Views without onCreate's online catalog query.
		activity = Robolectric.buildActivity(SteamWorkshopActivity.class).get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		content = new FrameLayout(activity);
		activity.setContentView(content);
		field("libraryReady").setBoolean(activity, true);
		field("busy").setBoolean(activity, true);
	}

	@After public void tearDown() throws Exception {
		((Handler) field("mainHandler").get(activity)).removeCallbacksAndMessages(null);
		((ExecutorService) field("libraryExecutor").get(activity)).shutdownNow();
	}

	@Test public void busyClickRejectsWithoutLeavingAnActiveOrQueuedDownload() throws Exception {
		SteamWorkshopCatalog.Item item = item("90001");
		View download = control(item);
		content.addView(download);
		download.performClick();
		ShadowLooper.idleMainLooper();

		assertTrue(((Map<?, ?>) field("downloadTasks").get(activity)).isEmpty());
		assertTrue(((List<?>) field("pendingDownloadQueue").get(activity)).isEmpty());
		assertTrue(control(item) instanceof MaterialButton);
		assertTrue(field("busy").getBoolean(activity));
		assertNotNull(activity.findViewById(com.google.android.material.R.id.snackbar_text));
	}

	@Test public void alreadyStartedDownloadCanStillBeCancelledByItsOriginalButtonWhileBusy() throws Exception {
		SteamWorkshopCatalog.Item item = item("90002");
		View download = control(item);
		content.addView(download);
		Class<?> taskType = Class.forName("com.godot.game.SteamWorkshopActivity$DownloadTask");
		Constructor<?> constructor = taskType.getDeclaredConstructor(SteamWorkshopCatalog.Item.class);
		constructor.setAccessible(true);
		Object task = constructor.newInstance(item);
		Field started = taskType.getDeclaredField("downloadStarted");
		started.setAccessible(true);
		started.setBoolean(task, true);
		@SuppressWarnings("unchecked") Map<String, Object> tasks = (Map<String, Object>) field("downloadTasks").get(activity);
		tasks.put(item.getPublishedFileId(), task);
		// Keep the queue pump from opening another screen after cancelling the last task.
		field("branchDialogActive").setBoolean(activity, true);

		download.performClick();

		assertFalse(tasks.containsKey(item.getPublishedFileId()));
		Field cancelled = taskType.getDeclaredField("cancelled");
		cancelled.setAccessible(true);
		assertTrue(cancelled.getBoolean(task));
		assertTrue(control(item) instanceof MaterialButton);
	}

	private View control(SteamWorkshopCatalog.Item item) throws Exception {
		Method method = SteamWorkshopActivity.class.getDeclaredMethod("buildDownloadControl", SteamWorkshopCatalog.Item.class, boolean.class);
		method.setAccessible(true);
		return (View) method.invoke(activity, item, false);
	}

	private static Field field(String name) throws Exception {
		Field field = SteamWorkshopActivity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static SteamWorkshopCatalog.Item item(String id) {
		return new SteamWorkshopCatalog.Item(2868840, id, "Synthetic " + id, "", "", "", 0L, 0L, 0, 0, 1L);
	}
}
