package com.godot.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class FloatingKeyboardOverlayViewTest {
	private Activity activity;
	private FloatingKeyboardOverlayView overlay;
	private int opened;

	@Before public void setUp() {
		activity = Robolectric.buildActivity(Activity.class).setup().get();
		activity.getSharedPreferences("floating_keyboard_overlay", 0).edit().clear().commit();
		activity.setContentView(new FrameLayout(activity));
		overlay = new FloatingKeyboardOverlayView(activity, () -> opened++);
		overlay.attach();
		layout(1200, 800);
	}

	@After public void tearDown() {
		overlay.detach();
	}

	@Test public void onlyCompletedTapsOpenTheKeyboard() {
		View button = overlay.getChildAt(0);
		float x = button.getX() + button.getWidth() / 2f;
		float y = button.getY() + button.getHeight() / 2f;
		dispatch(MotionEvent.ACTION_DOWN, x, y);
		dispatch(MotionEvent.ACTION_UP, x, y);
		assertEquals(1, opened);
		dispatch(MotionEvent.ACTION_DOWN, x, y);
		dispatch(MotionEvent.ACTION_MOVE, x - 200, y + 100);
		dispatch(MotionEvent.ACTION_UP, x - 200, y + 100);
		assertEquals(1, opened);
		assertTrue(button.getX() < x - button.getWidth() / 2f);
		x = button.getX() + button.getWidth() / 2f;
		y = button.getY() + button.getHeight() / 2f;
		dispatch(MotionEvent.ACTION_DOWN, x, y);
		dispatch(MotionEvent.ACTION_CANCEL, x, y);
		assertEquals(1, opened);
	}

	@Test public void draggedPositionRestoresWithinSafeInsetsAfterResize() {
		applyInsets();
		View button = overlay.getChildAt(0);
		float x = button.getX() + button.getWidth() / 2f;
		float y = button.getY() + button.getHeight() / 2f;
		dispatch(MotionEvent.ACTION_DOWN, x, y);
		dispatch(MotionEvent.ACTION_MOVE, -5000, 5000);
		dispatch(MotionEvent.ACTION_UP, -5000, 5000);
		assertEquals(40f, button.getX(), 0.01f);
		assertEquals(800f - 30 - button.getHeight(), button.getY(), 0.01f);
		overlay.detach();
		overlay = new FloatingKeyboardOverlayView(activity, () -> opened++);
		overlay.attach();
		layout(800, 600);
		applyInsets();
		button = overlay.getChildAt(0);
		assertEquals(40f, button.getX(), 0.01f);
		assertEquals(600f - 30 - button.getHeight(), button.getY(), 0.01f);
		assertEquals(0, opened);
	}

	@Test public void transparentHostDoesNotConsumeGameTouches() {
		MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100, 100, 0);
		try {
			assertFalse(overlay.dispatchTouchEvent(down));
			assertEquals(0, opened);
		} finally {
			down.recycle();
		}
	}

	private void layout(int width, int height) {
		overlay.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
		overlay.layout(0, 0, width, height);
	}

	private void applyInsets() {
		WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(40, 20, 50, 30)).build();
		overlay.dispatchApplyWindowInsets(insets.toWindowInsets());
	}

	private void dispatch(int action, float x, float y) {
		MotionEvent event = MotionEvent.obtain(0, 0, action, x, y, 0);
		overlay.dispatchTouchEvent(event);
		event.recycle();
	}
}
