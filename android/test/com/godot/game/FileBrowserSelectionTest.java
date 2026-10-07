package com.godot.game;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class, shadows = AndroidLinuxTestShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FileBrowserSelectionTest {
    private FileBrowserActivity activity;
    private ActivityController<FileBrowserActivity> controller;
    private File directory;
    private ViewGroup root;
    private MaterialToolbar toolbar;

    @Before public void setup() throws Exception {
        controller = Robolectric.buildActivity(FileBrowserActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.Theme_Sts2Tools);
        activity.setContentView(R.layout.activity_file_browser);
        root = activity.findViewById(R.id.file_browser_root);
        toolbar = activity.findViewById(R.id.toolbar);
        activity.setSupportActionBar(toolbar);
        set("toolbar", toolbar);
        directory = Files.createTempDirectory(activity.getFilesDir().toPath(), "selection-").toFile();
        Files.write(new File(directory, "one.txt").toPath(), "one".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory, "two.txt").toPath(), "two".getBytes(StandardCharsets.UTF_8));
        set("rootDirectory", activity.getFilesDir());
        set("currentDirectory", directory);
        call("bindViews");
        Class<?> type = Class.forName("com.godot.game.FileBrowserActivity$FileBrowserAdapter");
        Constructor<?> constructor = type.getDeclaredConstructor(FileBrowserActivity.class);
        constructor.setAccessible(true);
        RecyclerView.Adapter<?> adapter = (RecyclerView.Adapter<?>) constructor.newInstance(activity);
        set("adapter", adapter);
        RecyclerView list = activity.findViewById(R.id.recycler_files);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);
        Method scan = FileBrowserActivity.class.getDeclaredMethod("scanEntries", File.class);
        scan.setAccessible(true);
        Method apply = FileBrowserActivity.class.getDeclaredMethod("applyEntries", File.class, List.class);
        apply.setAccessible(true);
        apply.invoke(activity, directory, scan.invoke(activity, directory));
        // This fixture measures a detached layout; it does not create the Activity's toolbar menus.
        layout();
    }

    @After public void cleanup() throws Exception {
        call("clearSelection");
        for (File child : directory.listFiles()) {
            Files.delete(child.toPath());
        }
        Files.delete(directory.toPath());
        controller.close();
    }

    @Test public void selectionButtonsRemainVisibleAcrossUpdatesAndCopyClosesTheBar() throws Exception {
        longPress(0);
        call("updateSelectionChrome");
        call("updateSelectionChrome");
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        layout();
        ViewGroup bar = activity.findViewById(R.id.selection_action_bar);
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertEquals(1f, bar.getAlpha(), 0.01f);
        assertEquals(0f, bar.getTranslationY(), 0.01f);
        for (int i = 0; i < bar.getChildCount(); i++) {
            MaterialButton button = (MaterialButton) bar.getChildAt(i);
            assertTrue(button.getWidth() > 20);
            assertTrue(button.getHeight() >= 48);
            assertTrue(button.getRight() <= bar.getWidth());
        }
        TextView title = findTitle(toolbar);
        assertNotNull(title);
        assertTrue(ColorUtils.calculateContrast(title.getCurrentTextColor(),
                toolbar.getBackgroundTintList().getDefaultColor()) >= 4.5);
        activity.findViewById(R.id.action_selection_copy).performClick();
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(View.GONE, bar.getVisibility());
        @SuppressWarnings("unchecked") List<File> copied = (List<File>) field("copiedEntries").get(activity);
        assertEquals(1, copied.size());
        assertEquals("one.txt", copied.get(0).getName());
        assertTrue(copied.get(0).isFile());
    }

    @Test public void closingDuringEntranceAndReselectingCannotRetainHiddenControls() throws Exception {
        longPress(0);
        call("clearSelection");
        assertEquals(View.GONE, activity.findViewById(R.id.selection_action_bar).getVisibility());
        longPress(1);
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.selection_action_bar).getVisibility());
        assertEquals(1f, activity.findViewById(R.id.selection_action_bar).getAlpha(), 0.01f);
        call("clearSelection");
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals(View.GONE, activity.findViewById(R.id.selection_action_bar).getVisibility());
        assertTrue(new File(directory, "one.txt").isFile());
        assertTrue(new File(directory, "two.txt").isFile());
    }

    private void longPress(int position) throws Exception {
        Method method = FileBrowserActivity.class.getDeclaredMethod("onEntryLongPressed", int.class);
        method.setAccessible(true);
        method.invoke(activity, position);
    }

    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
    }

    private TextView findTitle(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView && toolbar.getTitle().equals(((TextView) child).getText())) {
                return (TextView) child;
            }
        }
        return null;
    }

    private Field field(String name) throws Exception {
        Field field = FileBrowserActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void set(String name, Object value) throws Exception {
        field(name).set(activity, value);
    }

    private void call(String name) throws Exception {
        Method method = FileBrowserActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(activity);
    }
}
