package com.godot.game;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** In-app mouse switch; only the button consumes touches, not the full-screen layer. */
final class FloatingMouseOverlayView extends FrameLayout {
	private static final String PREFS_NAME = "floating_mouse_overlay";
	private static final String PREF_X = "x_fraction";
	private static final String PREF_Y = "y_fraction";
	private static final float IDLE_ALPHA = 0.2f;
	private static final long IDLE_DELAY_MS = 1500;
	private static final long FADE_DURATION_MS = 180;

	private final Activity activity;
	private final MouseButton button;
	private final int buttonSize;
	private final int touchSlop;
	private final Runnable idleFade;
	private FloatingMouseInputController.Mode mode = FloatingMouseInputController.Mode.LEFT;
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

	FloatingMouseOverlayView(Activity activity, Runnable onClick) {
		super(activity);
		this.activity = activity;
		buttonSize = dp(56);
		touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
		SharedPreferences prefs = prefs();
		xFraction = prefs.getFloat(PREF_X, Float.NaN);
		yFraction = prefs.getFloat(PREF_Y, 0.5f);
		setClickable(false);
		setFocusable(false);
		setClipChildren(false);
		button = new MouseButton();
		idleFade = () -> button.animate().alpha(IDLE_ALPHA).setDuration(FADE_DURATION_MS).start();
		button.setClickable(true);
		button.setFocusable(false);
		button.setElevation(dp(8));
		button.setAlpha(IDLE_ALPHA);
		button.setOnClickListener(view -> {
			showActive();
			view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
			onClick.run();
			scheduleIdle();
		});
		button.setOnTouchListener(this::onButtonTouch);
		addView(button, new FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP | Gravity.LEFT));
		ViewCompat.setOnApplyWindowInsetsListener(this, (view, insets) -> {
			safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
			placeButton();
			return insets;
		});
		addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
			if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
				placeButton();
			}
		});
		applyMode();
	}

	void attach() {
		if (!attached) {
			attached = true;
			activity.addContentView(this, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
			ViewCompat.requestApplyInsets(this);
		}
		scheduleIdle();
	}

	void detach() {
		onPause();
		if (attached) {
			savePosition();
			attached = false;
			if (getParent() instanceof ViewGroup) {
				((ViewGroup) getParent()).removeView(this);
			}
		}
	}

	void onPause() {
		removeCallbacks(idleFade);
		button.animate().cancel();
		button.setAlpha(IDLE_ALPHA);
		if (dragging) savePosition();
		pointerId = -1;
		dragging = false;
		getParentDisallowIntercept(false);
	}

	void setMode(FloatingMouseInputController.Mode mode) {
		if (this.mode == mode) return;
		this.mode = mode;
		applyMode();
		if (attached) {
			showActive();
			scheduleIdle();
		}
	}

	private void applyMode() {
		boolean locked = mode == FloatingMouseInputController.Mode.RIGHT_LOCKED;
		boolean left = mode == FloatingMouseInputController.Mode.LEFT;
		GradientDrawable background = new GradientDrawable();
		background.setShape(GradientDrawable.OVAL);
		background.setColor(ColorUtils.setAlphaComponent(
			locked ? ExtraSettingsUi.COLOR_PRIMARY_CONTAINER : ExtraSettingsUi.COLOR_SURFACE, 235));
		background.setStroke(dp(locked ? 2 : 1), left ? ExtraSettingsUi.COLOR_OUTLINE : ExtraSettingsUi.COLOR_PRIMARY);
		button.setBackground(background);
		button.setContentDescription(activity.getString(left ? R.string.floating_mouse_left
			: locked ? R.string.floating_mouse_right_locked : R.string.floating_mouse_right_once));
		button.invalidate();
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
				getParentDisallowIntercept(true);
				return true;
			case MotionEvent.ACTION_POINTER_DOWN:
				multiTouch = true;
				return true;
			case MotionEvent.ACTION_MOVE:
				int index = event.findPointerIndex(pointerId);
				if (index < 0) return true;
				// getRawX(index) requires API 29; the local pointer delta also works on API 24.
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
				if (event.getPointerId(event.getActionIndex()) != pointerId) return true;
				finishTouch(false);
				return true;
			case MotionEvent.ACTION_UP:
				boolean click = pointerId != -1 && !dragging && !multiTouch;
				finishTouch(click);
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
		getParentDisallowIntercept(false);
		if (click) button.performClick();
		scheduleIdle();
	}

	private void getParentDisallowIntercept(boolean disallow) {
		if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
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
		button.setY(minY() + (Float.isFinite(yFraction) ? clamp(yFraction, 0, 1) : 0.5f) * (maxY() - minY()));
	}

	private void savePosition() {
		if (getWidth() <= 0 || getHeight() <= 0) return;
		xFraction = maxX() > minX() ? clamp((button.getX() - minX()) / (maxX() - minX()), 0, 1) : 0;
		yFraction = maxY() > minY() ? clamp((button.getY() - minY()) / (maxY() - minY()), 0, 1) : 0;
		prefs().edit().putFloat(PREF_X, xFraction).putFloat(PREF_Y, yFraction).apply();
	}

	private SharedPreferences prefs() { return activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); }
	private int dp(float value) { return ExtraSettingsUi.dp(activity, value); }
	private float minX() { return safeInsets.left; }
	private float minY() { return safeInsets.top; }
	private float maxX() { return Math.max(minX(), getWidth() - safeInsets.right - buttonSize); }
	private float maxY() { return Math.max(minY(), getHeight() - safeInsets.bottom - buttonSize); }
	private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

	/** Independently drawn mouse and lock; no upstream artwork is bundled. */
	private final class MouseButton extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF mouse = new RectF(5, 2, 19, 22);
		private final RectF lock = new RectF();
		private final Path mouseClip = new Path();

		MouseButton() {
			super(activity);
			mouseClip.addRoundRect(mouse, 7, 7, Path.Direction.CW);
		}

		@Override protected void onDraw(Canvas canvas) {
			super.onDraw(canvas);
			float iconSize = dp(30);
			int saved = canvas.save();
			canvas.translate((getWidth() - iconSize) / 2f, (getHeight() - iconSize) / 2f);
			canvas.scale(iconSize / 24f, iconSize / 24f);
			int clipped = canvas.save();
			canvas.clipPath(mouseClip);
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(ExtraSettingsUi.COLOR_PRIMARY);
			boolean left = mode == FloatingMouseInputController.Mode.LEFT;
			canvas.drawRect(left ? 5 : 12, 2, left ? 12 : 19, 11, paint);
			canvas.restoreToCount(clipped);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(1.6f);
			paint.setColor(ExtraSettingsUi.COLOR_ON_SURFACE);
			canvas.drawRoundRect(mouse, 7, 7, paint);
			canvas.drawLine(12, 2, 12, 11, paint);
			canvas.drawLine(5, 11, 19, 11, paint);
			canvas.restoreToCount(saved);
			if (mode == FloatingMouseInputController.Mode.RIGHT_LOCKED) {
				float radius = dp(8);
				float cx = getWidth() - radius;
				float cy = radius;
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(ExtraSettingsUi.COLOR_PRIMARY);
				canvas.drawCircle(cx, cy, radius, paint);
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(dp(1.3f));
				paint.setColor(ExtraSettingsUi.COLOR_ON_PRIMARY);
				lock.set(cx - dp(3), cy, cx + dp(3), cy + dp(4));
				canvas.drawRoundRect(lock, dp(1), dp(1), paint);
				lock.set(cx - dp(2), cy - dp(4), cx + dp(2), cy + dp(2));
				canvas.drawArc(lock, 180, 180, false, paint);
			}
		}
	}
}
