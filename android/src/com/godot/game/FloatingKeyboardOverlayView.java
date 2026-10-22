package com.godot.game;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** In-game keyboard shortcut; the transparent host never consumes game touches. */
final class FloatingKeyboardOverlayView extends FrameLayout {
	private static final String PREFS_NAME = "floating_keyboard_overlay";
	private static final long IDLE_DELAY_MS = 1500L;
	private static final float IDLE_ALPHA = 0.55f;

	private final Activity activity;
	private final ImageButton button;
	private final int buttonSize;
	private final int touchSlop;
	private final Runnable idleFade;
	private Insets safeInsets = Insets.NONE;
	private boolean attached;
	private boolean dragging;
	private boolean multiTouch;
	private int pointerId = -1;
	private float downRawX;
	private float downRawY;
	private float downX;
	private float downY;
	private float xFraction;
	private float yFraction;

	FloatingKeyboardOverlayView(Activity activity, Runnable onClick) {
		super(activity);
		this.activity = activity;
		buttonSize = dp(56);
		touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
		SharedPreferences prefs = prefs();
		xFraction = prefs.getFloat("x_fraction", Float.NaN);
		yFraction = prefs.getFloat("y_fraction", 0.35f);
		setClickable(false);
		setFocusable(false);
		button = new ImageButton(activity);
		button.setFocusable(false);
		button.setContentDescription(activity.getString(R.string.floating_keyboard_open));
		button.setImageDrawable(MaterialSymbols.drawable(activity, R.drawable.ic_ms_keyboard_24,
			ExtraSettingsUi.COLOR_ON_SURFACE, 28));
		button.setPadding(dp(14), dp(14), dp(14), dp(14));
		GradientDrawable background = new GradientDrawable();
		background.setShape(GradientDrawable.OVAL);
		background.setColor(ExtraSettingsUi.COLOR_SURFACE);
		background.setStroke(dp(1), ExtraSettingsUi.COLOR_OUTLINE);
		button.setBackground(new RippleDrawable(ColorStateList.valueOf(ExtraSettingsUi.COLOR_PRIMARY_CONTAINER),
			background, null));
		button.setElevation(dp(8));
		button.setAlpha(IDLE_ALPHA);
		idleFade = () -> button.animate().alpha(IDLE_ALPHA).setDuration(180).start();
		button.setOnClickListener(view -> {
			showActive();
			view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
			onClick.run();
			scheduleIdle();
		});
		button.setOnTouchListener(this::onButtonTouch);
		addView(button, new LayoutParams(buttonSize, buttonSize, Gravity.TOP | Gravity.LEFT));
		ViewCompat.setOnApplyWindowInsetsListener(this, (view, insets) -> {
			safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
			placeButton();
			return insets;
		});
		addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
			if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) placeButton();
		});
	}

	void attach() {
		if (!attached) {
			attached = true;
			activity.addContentView(this, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
			ViewCompat.requestApplyInsets(this);
		}
		scheduleIdle();
	}

	void detach() {
		removeCallbacks(idleFade);
		button.animate().cancel();
		if (attached) savePosition();
		attached = false;
		pointerId = -1;
		dragging = false;
		if (getParent() instanceof ViewGroup) {
			getParent().requestDisallowInterceptTouchEvent(false);
			((ViewGroup) getParent()).removeView(this);
		}
	}

	private boolean onButtonTouch(View view, MotionEvent event) {
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				pointerId = event.getPointerId(0);
				dragging = false;
				multiTouch = false;
				downRawX = event.getRawX();
				downRawY = event.getRawY();
				downX = button.getX();
				downY = button.getY();
				showActive();
				getParent().requestDisallowInterceptTouchEvent(true);
				return true;
			case MotionEvent.ACTION_POINTER_DOWN:
				multiTouch = true;
				return true;
			case MotionEvent.ACTION_MOVE:
				int index = event.findPointerIndex(pointerId);
				if (index < 0) return true;
				float dx = event.getRawX() + event.getX(index) - event.getX() - downRawX;
				float dy = event.getRawY() + event.getY(index) - event.getY() - downRawY;
				if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) dragging = true;
				if (dragging) {
					button.setX(clamp(downX + dx, minX(), maxX()));
					button.setY(clamp(downY + dy, minY(), maxY()));
				}
				return true;
			case MotionEvent.ACTION_POINTER_UP:
				multiTouch = true;
				if (event.getPointerId(event.getActionIndex()) == pointerId) finishTouch(false);
				return true;
			case MotionEvent.ACTION_UP:
				finishTouch(pointerId != -1 && !dragging && !multiTouch);
				return true;
			case MotionEvent.ACTION_CANCEL:
				finishTouch(false);
				return true;
			default:
				return true;
		}
	}

	private void finishTouch(boolean click) {
		if (dragging) savePosition();
		pointerId = -1;
		dragging = false;
		if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
		if (click) button.performClick();
		scheduleIdle();
	}

	private void showActive() {
		removeCallbacks(idleFade);
		button.animate().cancel();
		button.setAlpha(1f);
	}

	private void scheduleIdle() {
		removeCallbacks(idleFade);
		if (attached) postDelayed(idleFade, IDLE_DELAY_MS);
	}

	private void placeButton() {
		if (getWidth() <= 0 || getHeight() <= 0) return;
		button.setX(Float.isFinite(xFraction)
			? minX() + clamp(xFraction, 0, 1) * (maxX() - minX()) : Math.max(minX(), maxX() - dp(18)));
		button.setY(minY() + clamp(yFraction, 0, 1) * (maxY() - minY()));
	}

	private void savePosition() {
		if (getWidth() <= 0 || getHeight() <= 0) return;
		xFraction = maxX() > minX() ? clamp((button.getX() - minX()) / (maxX() - minX()), 0, 1) : 0;
		yFraction = maxY() > minY() ? clamp((button.getY() - minY()) / (maxY() - minY()), 0, 1) : 0;
		prefs().edit().putFloat("x_fraction", xFraction).putFloat("y_fraction", yFraction).apply();
	}

	private SharedPreferences prefs() { return activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); }
	private int dp(float value) { return ExtraSettingsUi.dp(activity, value); }
	private float minX() { return safeInsets.left; }
	private float minY() { return safeInsets.top; }
	private float maxX() { return Math.max(minX(), getWidth() - safeInsets.right - buttonSize); }
	private float maxY() { return Math.max(minY(), getHeight() - safeInsets.bottom - buttonSize); }
	private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
