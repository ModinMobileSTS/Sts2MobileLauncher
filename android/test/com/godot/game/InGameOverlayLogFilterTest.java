package com.godot.game;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, shadows = AndroidLinuxTestShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class InGameOverlayLogFilterTest {
	private InGameOverlayController controller;
	private InGameLogTailer tailer;

	@Before public void setup() throws Exception {
		// Attach the real Activity context without starting Godot's native onCreate path.
		GodotApp activity = Robolectric.buildActivity(GodotApp.class).get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		File logs = new LaunchProfileManager(activity).getSelectedLogsRootDir();
		Files.createDirectories(logs.toPath());
		Files.write(new File(logs, "godot.log").toPath(), "I keep\nI drop\n".getBytes(StandardCharsets.UTF_8));
		AndroidLinuxTestShadow.inode = 1L;
		controller = new InGameOverlayController(activity);
		LinearLayout body = new LinearLayout(activity);
		body.setOrientation(LinearLayout.VERTICAL);
		activity.setContentView(body);
		set(controller, "panelBody", body);
		tailer = (InGameLogTailer) get(controller, "logTailer");
	}

	@After public void close() {
		if (tailer != null) tailer.stop();
		if (controller != null) controller.detach();
	}

	@Test public void reopeningLogsKeepsTheVisibleQueryAndClearingItRestoresRows() throws Exception {
		showTab("logs");
		((ImageView) get(controller, "logFiltersButton")).performClick();
		((EditText) get(controller, "logFilterInput")).setText("keep");
		tick();
		assertEquals(List.of("I keep"), renderedLines());

		showTab("quick");
		showTab("logs");
		tick();
		EditText search = (EditText) get(controller, "logFilterInput");
		assertEquals("keep", search.getText().toString());
		assertEquals(List.of("I keep"), renderedLines());

		search.setText("");
		tick();
		assertEquals(List.of("I keep", "I drop"), renderedLines());
		showTab("quick");
		showTab("logs");
		tick();
		assertEquals("", ((EditText) get(controller, "logFilterInput")).getText().toString());
		assertEquals(List.of("I keep", "I drop"), renderedLines());
	}

	private void showTab(String tab) throws Exception {
		Method method = InGameOverlayController.class.getDeclaredMethod("showTab", String.class);
		method.setAccessible(true);
		method.invoke(controller, tab);
	}

	private void tick() throws Exception {
		Object session = get(tailer, "session");
		Handler worker = (Handler) get(session, "worker");
		shadowOf(worker.getLooper()).idleFor(Duration.ofMillis(801));
		shadowOf(Looper.getMainLooper()).idle();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private List<String> renderedLines() throws Exception {
		RecyclerView list = (RecyclerView) get(controller, "logRecyclerView");
		RecyclerView.Adapter adapter = list.getAdapter();
		List<String> result = new ArrayList<>();
		for (int index = 0; index < adapter.getItemCount(); index++) {
			RecyclerView.ViewHolder holder = adapter.createViewHolder(list, adapter.getItemViewType(index));
			adapter.bindViewHolder(holder, index);
			result.add(((TextView) holder.itemView).getText().toString());
		}
		return result;
	}

	private static Object get(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static void set(Object owner, String name, Object value) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(owner, value);
	}
}
