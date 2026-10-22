package com.godot.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real panel lifecycle without starting Godot's native Activity onCreate path. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 35)
public class BuiltinKeyboardControllerTest {
	private GodotApp activity;
	private BuiltinKeyboardController controller;
	private ExtraSettingsRepository repository;

	@Before public void setUp() throws Exception {
		activity = Robolectric.buildActivity(GodotApp.class).get();
		activity.setContentView(new FrameLayout(activity));
		repository = new ExtraSettingsRepository(activity);
		repository.saveSettingsJson(new JSONObject()
			.put(ExtraSettingsRepository.KEY_BUILTIN_FULL_KEYBOARD, true)
			.put(ExtraSettingsRepository.KEY_FLOATING_KEYBOARD_ENABLED, true));
		controller = new BuiltinKeyboardController(activity);
		controller.onResume();
	}

	@After public void tearDown() {
		if (controller != null) controller.onPause();
	}

	@Test public void systemImeDismissalHidesTheSwitcherButKeepsTheShortcut() {
		BuiltinKeyboardView panel = find(BuiltinKeyboardView.class);
		assertEquals(View.GONE, panel.getVisibility());
		controller.onSelectSystemTab();
		assertFalse("Back must remain available to dismiss the system IME", controller.handleBackPressed());
		applyImeInsets(panel, false);
		assertEquals("Initial hidden insets must not cancel an opening IME", View.VISIBLE, panel.getVisibility());
		applyImeInsets(panel, true);
		applyImeInsets(panel, false);
		assertEquals(View.GONE, panel.getVisibility());
		assertNotNull(find(FloatingKeyboardOverlayView.class));
		assertEquals(BuiltinKeyboardState.Tab.SYSTEM, controller.getState().getActiveTab());
	}

	@Test public void closeButtonHidesThePanelAndClearsLockedModifiers() {
		controller.onSelectBuiltinTab();
		controller.getState().lockModifier(BuiltinKeyboardLayout.Modifier.CTRL);
		controller.getState().lockModifier(BuiltinKeyboardLayout.Modifier.ALT);
		BuiltinKeyboardView panel = find(BuiltinKeyboardView.class);
		panel.getChildAt(2).performClick();
		assertEquals(View.GONE, panel.getVisibility());
		assertEquals(0, controller.getState().metaState());
		assertNotNull(find(FloatingKeyboardOverlayView.class));
		controller.onSelectBuiltinTab();
		assertEquals(View.VISIBLE, panel.getVisibility());
		assertEquals(0, controller.getState().metaState());
	}

	@Test public void lateSystemImeInsetsDoNotCloseTheFullKeyboard() {
		controller.onSelectSystemTab();
		BuiltinKeyboardView panel = find(BuiltinKeyboardView.class);
		applyImeInsets(panel, true);
		controller.onSelectBuiltinTab();
		applyImeInsets(panel, false);
		assertEquals(View.VISIBLE, panel.getVisibility());
	}

	@Test public void floatingShortcutWorksIndependentlyAndFollowsTheSetting() throws Exception {
		repository.saveSetting(root -> root.put(ExtraSettingsRepository.KEY_BUILTIN_FULL_KEYBOARD, false));
		controller.onResume();
		assertNull(find(BuiltinKeyboardView.class));
		assertNotNull(find(FloatingKeyboardOverlayView.class));
		repository.saveSetting(root -> root.put(ExtraSettingsRepository.KEY_FLOATING_KEYBOARD_ENABLED, false));
		controller.onResume();
		assertNull(find(FloatingKeyboardOverlayView.class));
	}

	@Test public void pauseRemovesBothViewsAndIgnoresLateImeCallbacks() {
		controller.onSelectSystemTab();
		BuiltinKeyboardView oldPanel = find(BuiltinKeyboardView.class);
		applyImeInsets(oldPanel, true);
		controller.onPause();
		applyImeInsets(oldPanel, false);
		assertNull(find(BuiltinKeyboardView.class));
		assertNull(find(FloatingKeyboardOverlayView.class));
		controller.onResume();
		assertEquals(View.GONE, find(BuiltinKeyboardView.class).getVisibility());
		assertNotNull(find(FloatingKeyboardOverlayView.class));
	}

	private void applyImeInsets(View panel, boolean visible) {
		WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
			.setInsets(WindowInsetsCompat.Type.ime(), visible ? Insets.of(0, 0, 0, 320) : Insets.NONE)
			.setVisible(WindowInsetsCompat.Type.ime(), visible)
			.build();
		panel.dispatchApplyWindowInsets(insets.toWindowInsets());
	}

	private <T extends View> T find(Class<T> type) {
		ViewGroup content = activity.findViewById(android.R.id.content);
		for (int index = 0; index < content.getChildCount(); index++) {
			View child = content.getChildAt(index);
			if (type.isInstance(child)) return type.cast(child);
		}
		return null;
	}
}
