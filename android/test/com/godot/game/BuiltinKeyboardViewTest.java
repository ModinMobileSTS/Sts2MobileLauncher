package com.godot.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

/** Panel wiring: floating switcher, key hit testing and modifier highlighting. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class BuiltinKeyboardViewTest {
	private Activity activity;
	private FakeListener listener;
	private BuiltinKeyboardView view;

	@Before
	public void setUp() {
		activity = Robolectric.buildActivity(Activity.class).setup().get();
		listener = new FakeListener();
		view = new BuiltinKeyboardView(activity, listener);
		activity.setContentView(new FrameLayout(activity));
		view.attachTo(activity);
		view.showBuiltin();
		layoutPanel(1600, 1000);
	}

	private void layoutPanel(int width, int height) {
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
		view.layout(0, height - view.getMeasuredHeight(), width, height);
	}

	private void tapAt(float x, float y) {
		long now = android.os.SystemClock.uptimeMillis();
		dispatch(MotionEvent.ACTION_DOWN, x, y, now);
		dispatch(MotionEvent.ACTION_UP, x, y, now + 40);
	}

	private void dispatch(int action, float x, float y, long when) {
		MotionEvent event = MotionEvent.obtain(when, when, action, x, y, 0);
		view.dispatchTouchEvent(event);
		event.recycle();
	}

	@Test
	public void switcherIsShownAboveTheKeyGrid() {
		int tabsTop = findTabs().getTop();
		int canvasTop = findCanvas().getTop();
		assertTrue("switcher must float above the panel", tabsTop < canvasTop);
		assertTrue("switcher must stay on screen", tabsTop >= 0);
	}

	@Test
	public void tappedLetterProducesItsKeyEvent() {
		int[] center = keyCenter(BuiltinKeyboardLayout.mainRows().get(1), 1);
		tapAt(center[0], center[1]);
		assertEquals(1, listener.taps.size());
		assertEquals("Q", listener.taps.get(0).key.baseText);
		assertEquals("q", listener.taps.get(0).text.toString());
	}

	@Test
	@GraphicsMode(GraphicsMode.Mode.NATIVE)
	public void spaceIsUsableAfterTheFirstKeyboardDraw() {
		Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
		try {
			view.draw(new Canvas(bitmap));
			int[] center = keyCenter(BuiltinKeyboardLayout.mainRows().get(4), 3);
			tapAt(center[0], center[1]);
			assertEquals(1, listener.taps.size());
			assertEquals(KeyEvent.KEYCODE_SPACE, listener.taps.get(0).key.keyCode);
			assertEquals(" ", listener.taps.get(0).text.toString());
		} finally {
			bitmap.recycle();
		}
	}

	@Test
	public void tappingShiftArmsItForOneKey() {
		int[] shiftCenter = keyCenter(BuiltinKeyboardLayout.mainRows().get(3), 0);
		tapAt(shiftCenter[0], shiftCenter[1]);
		assertEquals(BuiltinKeyboardState.ArmState.LATCHED,
			listener.state.getState(BuiltinKeyboardLayout.Modifier.SHIFT));

		int[] qCenter = keyCenter(BuiltinKeyboardLayout.mainRows().get(1), 1);
		tapAt(qCenter[0], qCenter[1]);
		assertEquals("Q", listener.taps.get(listener.taps.size() - 1).text.toString());
		assertEquals(BuiltinKeyboardState.ArmState.OFF,
			listener.state.getState(BuiltinKeyboardLayout.Modifier.SHIFT));
	}

	@Test
	public void selectingTheSwitcherReportsTheSystemTab() {
		View tabs = findTabs();
		float segmentWidth = (tabs.getWidth()) / 2f;
		float x = tabs.getLeft() + segmentWidth * 1.5f;
		float y = tabs.getTop() + tabs.getHeight() / 2f;
		tapAt(x, y);
		assertTrue(listener.systemTabSelected);
	}

	@Test
	public void hiddenPanelDropsPendingKeys() {
		int[] center = keyCenter(BuiltinKeyboardLayout.mainRows().get(1), 1);
		tapAt(center[0], center[1]);
		int tapsBefore = listener.taps.size();
		view.onHidden();
		// A second completed tap still works, but nothing is left armed from the first.
		assertEquals(tapsBefore, listener.taps.size());
		view.showSwitcherOnly();
		assertEquals(View.GONE, findCanvas().getVisibility());
	}

	@Test
	public void longPressLocksAModifier() {
		int[] ctrlCenter = keyCenter(BuiltinKeyboardLayout.mainRows().get(4), 0);
		long now = android.os.SystemClock.uptimeMillis();
		dispatch(MotionEvent.ACTION_DOWN, ctrlCenter[0], ctrlCenter[1], now);
		org.robolectric.shadows.ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS);
		dispatch(MotionEvent.ACTION_UP, ctrlCenter[0], ctrlCenter[1], now + 600);
		assertEquals(BuiltinKeyboardState.ArmState.LOCKED,
			listener.state.getState(BuiltinKeyboardLayout.Modifier.CTRL));
		assertEquals(0, listener.taps.size());
	}

	@Test
	@GraphicsMode(GraphicsMode.Mode.NATIVE)
	public void lockedModifiersAreDarkerThanInactiveAndOneShotModifiers() {
		int[] center = keyCenter(BuiltinKeyboardLayout.mainRows().get(4), 0);
		int off = backgroundAt(center);
		tapAt(center[0], center[1]);
		int latched = backgroundAt(center);
		tapAt(center[0], center[1]);
		int locked = backgroundAt(center);
		assertTrue(ColorUtils.calculateLuminance(locked) < ColorUtils.calculateLuminance(off));
		assertTrue(ColorUtils.calculateLuminance(locked) < ColorUtils.calculateLuminance(latched));
		tapAt(center[0], center[1]);
		assertEquals(off, backgroundAt(center));
	}

	private int backgroundAt(int[] keyCenter) {
		Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
		try {
			view.draw(new Canvas(bitmap));
			return bitmap.getPixel(keyCenter[0], keyCenter[1] - ExtraSettingsUi.dp(activity, 14));
		} finally {
			bitmap.recycle();
		}
	}

	@Test
	public void everyMainRowFitsInsideThePanel() {
		View canvas = findCanvas();
		FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) canvas.getLayoutParams();
		assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, params.width);
		assertTrue(canvas.getWidth() > 0);
		assertTrue(canvas.getHeight() > 0);
		assertTrue(canvas.getBottom() <= view.getHeight());
	}

	private View findTabs() {
		return ((ViewGroup) view).getChildAt(0);
	}

	private View findCanvas() {
		return ((ViewGroup) view).getChildAt(1);
	}

	/** Center of a key, in window coordinates, computed from the same geometry rules. */
	private int[] keyCenter(BuiltinKeyboardLayout.Row row, int keyIndex) {
		View canvas = findCanvas();
		List<BuiltinKeyboardLayout.Row> rows = BuiltinKeyboardLayout.allRows();
		int rowIndex = rows.indexOf(row);
		assertTrue(rowIndex >= 0);
		int rowHeight = canvas.getHeight() / rows.size();
		float gap = ExtraSettingsUi.dp(activity, 2.4f);
		float totalWeight = 0f;
		for (BuiltinKeyboardLayout.Key key : row.keys) totalWeight += key.weight;
		float unit = (canvas.getWidth() - gap - gap * (row.keys.size() - 1)) / totalWeight;
		float left = gap / 2f;
		for (int index = 0; index < keyIndex; index++) {
			left += unit * row.keys.get(index).weight + gap;
		}
		float x = canvas.getLeft() + left + unit * row.keys.get(keyIndex).weight / 2f;
		float y = canvas.getTop() + rowIndex * rowHeight + rowHeight / 2f;
		return new int[] {Math.round(x), Math.round(y)};
	}

	private static final class FakeListener implements BuiltinKeyboardView.Listener {
		final BuiltinKeyboardState state = new BuiltinKeyboardState();
		final List<Tap> taps = new ArrayList<>();
		boolean builtinTabSelected;
		boolean systemTabSelected;

		@Override
		public BuiltinKeyboardState getState() {
			return state;
		}

		@Override
		public void onKeyTap(BuiltinKeyboardLayout.Key key, CharSequence text) {
			taps.add(new Tap(key, text));
		}

		@Override
		public void onLongPressModifier(BuiltinKeyboardLayout.Modifier modifier) {
			state.lockModifier(modifier);
		}

		@Override
		public void onToggleCapsLock() {
			state.toggleCapsLock();
		}

		@Override
		public void onStateChanged() {
		}

		@Override
		public void onSelectBuiltinTab() {
			builtinTabSelected = true;
		}

		@Override
		public void onSelectSystemTab() {
			systemTabSelected = true;
		}

		@Override
		public void onCloseKeyboard() {
			state.reset();
		}

		@Override
		public void onSystemImeVisibilityChanged(boolean visible) {
		}
	}

	private static final class Tap {
		final BuiltinKeyboardLayout.Key key;
		final CharSequence text;

		Tap(BuiltinKeyboardLayout.Key key, CharSequence text) {
			this.key = key;
			this.text = text;
		}
	}
}
