package com.godot.game;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Actual view measurement and touch boundaries, including window shrink while editing. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, qualifiers = "en-rUS-w360dp-h640dp-mdpi", shadows = AndroidLinuxTestShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ModalScrollingTest {
	private ActivityController<Activity> controller;
	private Activity activity;
	private BottomSheetDialog sheet;
	private MaterialButton lastAction;
	private int actionCount;

	@Before public void setup() {
		RuntimeEnvironment.setFontScale(2f);
		controller = Robolectric.buildActivity(Activity.class);
		activity = controller.get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		controller.setup();
	}

	@After public void cleanup() {
		Dialog latest = ShadowDialog.getLatestDialog();
		if (latest != null) latest.dismiss();
		if (sheet != null) sheet.dismiss();
		controller.close();
		RuntimeEnvironment.setFontScale(1f);
	}

	@Test public void longEnglishContentKeepsLastActionReachableAfterWindowShrink() {
		createSheet(true);
		for (int[] size : new int[][] {{360, 640}, {640, 280}, {360, 300}}) {
			ViewGroup surface = layoutSheet(size[0], size[1]);
			NestedScrollView scroll = find(surface, NestedScrollView.class);
			View handle = ((ViewGroup) scroll.getParent()).getChildAt(0);
			int handleTop = handle.getTop();
			scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
			assertTrue("content must remain scrollable in a small window", scroll.getScrollY() > 0);
			assertTrue("last action must fit in the viewport after scrolling",
				lastAction.getBottom() <= scroll.getScrollY() + scroll.getHeight());
			assertTrue("last action must not be clipped at the top",
				lastAction.getTop() >= scroll.getScrollY());
			assertEquals("handle must not scroll out with the body", handleTop, handle.getTop());
			assertTrue("sheet must fit the available window", sheetView().getHeight() <= size[1]);
			lastAction.performClick();
		}
		assertEquals(3, actionCount);
	}

	@Test public void shortSheetDoesNotStretchToFullHeight() {
		createSheet(false);
		layoutSheet(360, 640);
		assertTrue("short sheets must stay content-sized", sheetView().getHeight() < 400);
	}

	@Test public void bodyScrollsWithoutDraggingOrDismissingAtEitherBoundary() {
		createSheet(true);
		ViewGroup surface = layoutSheet(360, 640);
		NestedScrollView scroll = find(surface, NestedScrollView.class);
		int sheetTop = sheetView().getTop();
		gesture(surface, 180, 460, 180, 180, MotionEvent.ACTION_UP);
		assertTrue("body swipe must actually scroll the content", scroll.getScrollY() > 0);
		assertEquals(sheetTop, sheetView().getTop());
		assertTrue(sheet.isShowing());

		scroll.scrollTo(0, 0);
		gesture(surface, 180, 180, 180, 440, MotionEvent.ACTION_UP);
		assertEquals("pulling past the top must not move the sheet", sheetTop, sheetView().getTop());
		assertTrue("body overscroll must not dismiss", sheet.isShowing());
	}

	@Test public void onlyCompletedDownwardHandleGestureDismisses() {
		createSheet(true);
		ViewGroup surface = layoutSheet(360, 640);
		int handleY = sheetView().getTop() + 24;
		gesture(surface, 180, handleY, 180, handleY + 100, MotionEvent.ACTION_CANCEL);
		assertTrue("cancelled gestures never dismiss", sheet.isShowing());
		gesture(surface, 80, handleY, 260, handleY + 45, MotionEvent.ACTION_UP);
		assertTrue("horizontal handle motion never dismisses", sheet.isShowing());
		gesture(surface, 180, handleY, 180, handleY + 100, MotionEvent.ACTION_UP);
		assertFalse("a deliberate handle pull dismisses", sheet.isShowing());
	}

	@Test public void steamLoginFieldsAndActionsRemainReachableWithKeyboardHeight() throws Exception {
		try (ActivityController<SteamAccountActivity> steamController = Robolectric.buildActivity(SteamAccountActivity.class)) {
			SteamAccountActivity steam = steamController.get();
			steam.setTheme(R.style.Theme_Sts2ExtraSettings);
			steamController.setup();
			Dialog safetyNotice = ShadowDialog.getLatestDialog();
			if (safetyNotice != null) safetyNotice.dismiss();
			Method showLogin = SteamAccountActivity.class.getDeclaredMethod("showLoginDialog");
			showLogin.setAccessible(true);
			showLogin.invoke(steam);
			org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
			AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
			View decor = dialog.getWindow().getDecorView();
			List<EditText> inputs = findAll(decor, EditText.class);
			EditText password = inputs.get(1);
			ScrollView scroll = ancestor(password, ScrollView.class);
			for (int height : new int[] {640, 340}) {
				measure(decor, 360, height);
				password.requestFocus();
				password.requestRectangleOnScreen(new Rect(0, 0, password.getWidth(), password.getHeight()), true);
				Rect fieldBounds = new Rect();
				assertTrue("password can be scrolled into view: window=" + height
					+ " viewport=" + scroll.getHeight() + " scrollY=" + scroll.getScrollY()
					+ " body=" + scroll.getChildAt(0).getHeight() + " field=" + password.getHeight(),
					password.getGlobalVisibleRect(fieldBounds));
				password.getDrawingRect(fieldBounds);
				scroll.offsetDescendantRectToMyCoords(password, fieldBounds);
				assertTrue("password must be fully visible", new Rect(scroll.getScrollX(), scroll.getScrollY(),
					scroll.getScrollX() + scroll.getWidth(), scroll.getScrollY() + scroll.getHeight()).contains(fieldBounds));
				View login = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
				assertFalse("actions must stay outside the scrolling form", isDescendant(login, scroll));
				assertTrue("login action stays visible when the window shrinks", login.getGlobalVisibleRect(new Rect()));
			}
			dialog.dismiss();
		}
	}

	private void createSheet(boolean longContent) {
		sheet = new BottomSheetDialog(activity);
		LinearLayout content = ExtraSettingsUi.vertical(activity);
		content.setPadding(20, 0, 20, 24);
		if (longContent) {
			for (int i = 0; i < 5; i++) {
				TextView paragraph = ExtraSettingsUi.body(activity,
					"Compatibility information and account security details must remain readable when Android uses a large system font, even in a narrow landscape or keyboard-resized window.");
				content.addView(paragraph);
			}
		}
		lastAction = ExtraSettingsUi.filledButton(activity, android.R.string.ok, 0);
		lastAction.setOnClickListener(view -> actionCount++);
		content.addView(lastAction, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		ScrollableBottomSheet.setContent(sheet, content);
		sheet.show();
	}

	private ViewGroup layoutSheet(int width, int height) {
		ViewGroup surface = (ViewGroup) sheetView().getParent();
		measure(surface, width, height);
		return surface;
	}

	private FrameLayout sheetView() {
		return sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
	}

	private static void measure(View view, int width, int height) {
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
		view.layout(0, 0, width, height);
	}

	private static void gesture(View view, float x0, float y0, float x1, float y1, int endAction) {
		long down = SystemClock.uptimeMillis();
		dispatch(view, down, down, MotionEvent.ACTION_DOWN, x0, y0);
		for (int i = 1; i <= 5; i++) {
			dispatch(view, down, down + i * 40, MotionEvent.ACTION_MOVE,
				x0 + (x1 - x0) * i / 5, y0 + (y1 - y0) * i / 5);
		}
		dispatch(view, down, down + 240, endAction, x1, y1);
	}

	private static void dispatch(View view, long down, long time, int action, float x, float y) {
		MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
		view.dispatchTouchEvent(event);
		event.recycle();
	}

	private static boolean isDescendant(View view, View ancestor) {
		while (view != null) {
			if (view == ancestor) return true;
			view = view.getParent() instanceof View ? (View) view.getParent() : null;
		}
		return false;
	}

	private static <T extends View> T find(View view, Class<T> type) {
		List<T> matches = findAll(view, type);
		return matches.isEmpty() ? null : matches.get(0);
	}

	private static <T extends View> List<T> findAll(View view, Class<T> type) {
		List<T> matches = new ArrayList<>();
		if (type.isInstance(view)) matches.add(type.cast(view));
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) matches.addAll(findAll(group.getChildAt(i), type));
		}
		return matches;
	}

	private static <T extends View> T ancestor(View view, Class<T> type) {
		while (view.getParent() instanceof View) {
			view = (View) view.getParent();
			if (type.isInstance(view)) return type.cast(view);
		}
		throw new AssertionError("Missing scrollable ancestor");
	}

}
