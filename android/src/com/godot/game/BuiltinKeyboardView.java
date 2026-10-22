package com.godot.game;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import android.os.Build;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;
import java.util.Locale;

/**
 * In-app full PC keyboard panel with a floating segmented switcher above it.
 *
 * <p>The switcher floats just above the panel (and above the system IME when that
 * one is showing) so the user can jump between the built-in keyboard and the
 * system keyboard without touching settings.</p>
 */
@SuppressLint("ViewConstructor")
final class BuiltinKeyboardView extends FrameLayout {
	private static final int TAB_BUILTIN = 0;
	private static final int TAB_SYSTEM = 1;
	private static final long LONG_PRESS_MS = 400L;
	private static final long REPEAT_DELAY_MS = 380L;
	private static final long REPEAT_INTERVAL_MS = 55L;
	private static final float GAP_DP = 2.4f;
	private static final int LOCKED_KEY_COLOR = ColorUtils.blendARGB(
		ExtraSettingsUi.COLOR_PRIMARY_CONTAINER, 0xff000000, 0.45f);

	/** Callbacks the controller implements. */
	interface Listener {
		/** Live modifier state of the panel. */
		BuiltinKeyboardState getState();

		/** A key was pressed. {@code text} is non-empty for plain character keys. */
		void onKeyTap(BuiltinKeyboardLayout.Key key, CharSequence text);

		void onLongPressModifier(BuiltinKeyboardLayout.Modifier modifier);

		void onToggleCapsLock();

		/** Modifier/caps state changed and highlighting must refresh. */
		void onStateChanged();

		void onSelectBuiltinTab();

		void onSelectSystemTab();

		void onCloseKeyboard();

		void onSystemImeVisibilityChanged(boolean visible);
	}

	private final Activity activity;
	private final Listener listener;
	private final KeyCanvas canvas;
	private final KeyboardTabs tabs;
	private final ImageButton closeButton;
	private final int touchSlop;
	private final Runnable longPressRunnable = this::handleLongPress;
	private final Runnable repeatTick = this::handleRepeatTick;
	private Insets safeInsets = Insets.NONE;
	private boolean attached;
	private BuiltinKeyboardLayout.Key pressedKey;
	private long downEventTime;
	private boolean longPressConsumed;
	private boolean repeatRunning;

	BuiltinKeyboardView(Activity activity, Listener listener) {
		super(activity);
		this.activity = activity;
		this.listener = listener;
		touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
		setClickable(true);
		setFocusable(false);
		setClipChildren(false);
		tabs = new KeyboardTabs();
		canvas = new KeyCanvas();
		addView(tabs, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
			Gravity.TOP | Gravity.CENTER_HORIZONTAL));
		addView(canvas, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.BOTTOM));
		closeButton = new ImageButton(activity);
		closeButton.setFocusable(false);
		closeButton.setContentDescription(activity.getString(R.string.builtin_keyboard_close));
		closeButton.setImageDrawable(MaterialSymbols.drawable(activity, R.drawable.ic_ms_close_24,
			ExtraSettingsUi.COLOR_ON_SURFACE, 24));
		closeButton.setPadding(dp(12), dp(12), dp(12), dp(12));
		GradientDrawable closeBackground = new GradientDrawable();
		closeBackground.setShape(GradientDrawable.OVAL);
		closeBackground.setColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		closeBackground.setStroke(dp(1), ExtraSettingsUi.COLOR_OUTLINE);
		closeButton.setBackground(new RippleDrawable(ColorStateList.valueOf(ExtraSettingsUi.COLOR_PRIMARY_CONTAINER),
			closeBackground, null));
		closeButton.setOnClickListener(view -> listener.onCloseKeyboard());
		addView(closeButton, new LayoutParams(tabHeight(), tabHeight()));
		setOnApplyWindowInsetsListener((view, insets) -> {
			Insets safe = readInsets(insets);
			if (!safe.equals(safeInsets)) {
				safeInsets = safe;
				view.requestLayout();
			}
			listener.onSystemImeVisibilityChanged(WindowInsetsCompat.toWindowInsetsCompat(insets, view)
				.isVisible(WindowInsetsCompat.Type.ime()));
			return insets;
		});
		setVisibility(GONE);
	}

	/** System bars and cutout, falling back on the bottom IME inset when present. */
	private static Insets readInsets(WindowInsets insets) {
		if (insets == null) return Insets.NONE;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			android.graphics.Insets bars = insets.getInsets(
				WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
			android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
			return Insets.of(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
		}
		android.graphics.Insets bars = insets.getSystemWindowInsets();
		android.graphics.Insets cutout = insets.getDisplayCutout() == null
			? android.graphics.Insets.NONE
			: android.graphics.Insets.of(
				insets.getDisplayCutout().getSafeInsetLeft(),
				insets.getDisplayCutout().getSafeInsetTop(),
				insets.getDisplayCutout().getSafeInsetRight(),
				insets.getDisplayCutout().getSafeInsetBottom());
		return Insets.of(
			Math.max(bars.left, cutout.left),
			Math.max(bars.top, cutout.top),
			Math.max(bars.right, cutout.right),
			Math.max(bars.bottom, cutout.bottom));
	}

	// region host API

	void attachTo(Activity host) {
		if (attached) return;
		attached = true;
		host.addContentView(this, new LayoutParams(LayoutParams.MATCH_PARENT,
			LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
		ViewCompat.requestApplyInsets(this);
	}

	void detach() {
		cancelPress();
		if (!attached) return;
		attached = false;
		ViewParent parent = getParent();
		if (parent instanceof ViewGroup) {
			((ViewGroup) parent).removeView(this);
		}
	}

	/** Shows the switcher alone so the user can come back from the system IME. */
	void showSwitcherOnly() {
		setVisibility(VISIBLE);
		canvas.setVisibility(GONE);
		setBackground(null);
		tabs.setVisibility(VISIBLE);
		selectedTab = TAB_SYSTEM;
		invalidateAll();
		requestLayout();
		ViewCompat.requestApplyInsets(this);
	}

	void showBuiltin() {
		setVisibility(VISIBLE);
		canvas.setVisibility(VISIBLE);
		tabs.setVisibility(VISIBLE);
		selectedTab = TAB_BUILTIN;
		GradientDrawable background = new GradientDrawable();
		background.setColor(ExtraSettingsUi.COLOR_SURFACE);
		int radius = dp(18);
		background.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
		setBackground(background);
		requestLayout();
		invalidateAll();
		ViewCompat.requestApplyInsets(this);
	}

	/** Called after any modifier/state change. */
	void refresh() {
		invalidateAll();
	}

	void onHidden() {
		cancelPress();
		setVisibility(GONE);
	}

	private int selectedTab = TAB_BUILTIN;

	private void invalidateAll() {
		canvas.invalidate();
		tabs.invalidate();
	}

	// endregion

	// region layout

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int width = MeasureSpec.getSize(widthMeasureSpec);
		int heightLimit = MeasureSpec.getSize(heightMeasureSpec);
		int decorHeight = activity.getWindow() == null ? 0 : activity.getWindow().getDecorView().getHeight();
		int availableHeight = decorHeight > 0
			? decorHeight
			: Math.max(heightLimit, activity.getResources().getDisplayMetrics().heightPixels);
		int usableWidth = Math.max(1, width - safeInsets.left - safeInsets.right);
		int tabsHeight = tabHeight();
		int rowHeight = 0;
		if (canvas.getVisibility() == VISIBLE) {
			rowHeight = clamp(Math.round(usableWidth / 22f), dp(28), dp(44));
		}
		int canvasHeight = rowHeight * BuiltinKeyboardLayout.allRows().size();
		int total = canvasHeight + tabsHeight + dp(8) + safeInsets.bottom + (canvasHeight > 0 ? dp(6) : 0);
		setMeasuredDimension(width, Math.min(total, availableHeight));
		canvas.rowHeight = rowHeight;
		canvas.measure(
			MeasureSpec.makeMeasureSpec(usableWidth, MeasureSpec.EXACTLY),
			MeasureSpec.makeMeasureSpec(canvasHeight, MeasureSpec.EXACTLY));
		closeButton.measure(MeasureSpec.makeMeasureSpec(tabsHeight, MeasureSpec.EXACTLY),
			MeasureSpec.makeMeasureSpec(tabsHeight, MeasureSpec.EXACTLY));
		tabs.measure(
			MeasureSpec.makeMeasureSpec(Math.max(0, Math.min(usableWidth - tabsHeight - dp(8), getMaxTabsWidth())), MeasureSpec.AT_MOST),
			MeasureSpec.makeMeasureSpec(tabsHeight, MeasureSpec.EXACTLY));
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		int width = right - left;
		int height = bottom - top;
		int canvasHeight = canvas.getMeasuredHeight();
		int tabsHeight = tabs.getMeasuredHeight();
		int canvasBottom = height - safeInsets.bottom;
		int canvasTop = canvasBottom - canvasHeight;
		int contentLeft = safeInsets.left;
		int contentWidth = Math.max(0, width - safeInsets.left - safeInsets.right);
		canvas.layout(contentLeft, canvasTop, contentLeft + contentWidth, canvasBottom);
		int tabsWidth = tabs.getMeasuredWidth();
		int tabsTop = canvasTop - tabsHeight - dp(6);
		if (canvas.getVisibility() != VISIBLE) {
			tabsTop = height - safeInsets.bottom - tabsHeight - dp(8);
		}
		int groupWidth = tabsWidth + dp(8) + closeButton.getMeasuredWidth();
		int groupLeft = contentLeft + Math.max(0, (contentWidth - groupWidth) / 2);
		int groupTop = Math.max(0, tabsTop);
		tabs.layout(groupLeft, groupTop, groupLeft + tabsWidth, groupTop + tabsHeight);
		int closeLeft = tabs.getRight() + dp(8);
		closeButton.layout(closeLeft, groupTop, closeLeft + closeButton.getMeasuredWidth(), groupTop + tabsHeight);
	}

	private int getMaxTabsWidth() {
		return ExtraSettingsUi.dp(activity, 320);
	}

	private int tabHeight() {
		return dp(48);
	}

	// endregion

	// region input

	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		if (getVisibility() != VISIBLE) return false;
		int action = event.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			float x = event.getX();
			float y = event.getY();
			boolean overCanvas = canvas.getVisibility() == VISIBLE && overRect(x, y, canvas);
			if (!overCanvas && !overRect(x, y, tabs) && !overRect(x, y, closeButton)) return false;
			animate().alpha(1f).setDuration(100).start();
			requestDisallowInterceptTouchEvent(true);
			if (overCanvas) {
				beginKeyPress(event);
				return true;
			}
		} else if (action == MotionEvent.ACTION_MOVE) {
			if (pressedKey != null && movedBeyondSlop(event)) {
				cancelPress();
				return true;
			}
		} else if (action == MotionEvent.ACTION_UP) {
			if (pressedKey != null) {
				finishKeyPress(event);
				return true;
			}
		} else if (action == MotionEvent.ACTION_CANCEL) {
			cancelPress();
		}
		return super.dispatchTouchEvent(event);
	}

	private boolean overRect(float x, float y, View view) {
		return x >= view.getLeft() && x <= view.getRight() && y >= view.getTop() && y <= view.getBottom();
	}

	private boolean movedBeyondSlop(MotionEvent event) {
		if (downX < 0 || downY < 0) return false;
		float dx = event.getX() - downX;
		float dy = event.getY() - downY;
		return dx * dx + dy * dy > (float) touchSlop * touchSlop;
	}

	private float downX = -1;
	private float downY = -1;

	private void beginKeyPress(MotionEvent event) {
		downX = event.getX();
		downY = event.getY();
		downEventTime = event.getEventTime();
		longPressConsumed = false;
		BuiltinKeyboardLayout.Key hit = hitTestCanvas(downX - canvas.getLeft(), downY - canvas.getTop());
		pressedKey = hit;
		if (hit == null) {
			canvas.invalidate();
			return;
		}
		canvas.invalidate();
		postDelayed(longPressRunnable, LONG_PRESS_MS);
	}

	private void finishKeyPress(MotionEvent event) {
		removeCallbacks(longPressRunnable);
		BuiltinKeyboardLayout.Key hit = pressedKey;
		pressedKey = null;
		canvas.invalidate();
		if (hit == null) return;
		BuiltinKeyboardLayout.Key released = hitTestCanvas(event.getX() - canvas.getLeft(), event.getY() - canvas.getTop());
		if (released != hit) return;
		if (!longPressConsumed) {
			handleTap(hit);
		}
		setRepetitionIdle();
	}

	private void cancelPress() {
		removeCallbacks(longPressRunnable);
		setRepetitionIdle();
		if (pressedKey != null) {
			pressedKey = null;
			canvas.invalidate();
		}
		downX = -1;
		downY = -1;
	}

	private BuiltinKeyboardLayout.Key hitTestCanvas(float x, float y) {
		float gap = dp(GAP_DP);
		int rowHeight = canvas.rowHeight;
		if (rowHeight <= 0) return null;
		List<BuiltinKeyboardLayout.Row> rows = BuiltinKeyboardLayout.allRows();
		int index = (int) Math.floor((y - gap / 2f) / rowHeight);
		if (index < 0 || index >= rows.size()) return null;
		BuiltinKeyboardLayout.Row row = rows.get(index);
		float totalWeight = 0f;
		for (BuiltinKeyboardLayout.Key key : row.keys) {
			totalWeight += key.weight;
		}
		float width = canvas.getWidth() - gap;
		float unit = (width - gap * (row.keys.size() - 1)) / totalWeight;
		float left = gap / 2f;
		for (int keyIndex = 0; keyIndex < row.keys.size(); keyIndex++) {
			BuiltinKeyboardLayout.Key key = row.keys.get(keyIndex);
			float keyWidth = unit * key.weight;
			float right = keyIndex == row.keys.size() - 1 ? canvas.getWidth() : left + keyWidth;
			if (x >= left - gap / 2f && x < right + gap / 2f) {
				return key;
			}
			left += keyWidth + gap;
		}
		return null;
	}

	private void handleTap(BuiltinKeyboardLayout.Key key) {
		performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
		switch (key.kind) {
			case MODIFIER:
				listener.getState().tapModifier(key.modifier);
				listener.onStateChanged();
				break;
			case CAPS_LOCK:
				listener.onToggleCapsLock();
				break;
			case SWITCH:
				listener.onSelectSystemTab();
				break;
			default:
				dispatchKey(key);
				break;
		}
	}

	private void dispatchKey(BuiltinKeyboardLayout.Key key) {
		BuiltinKeyboardState state = listener.getState();
		CharSequence text = state.resolveText(key);
		listener.onKeyTap(key, text);
		if (state.onKeyDispatched()) {
			listener.onStateChanged();
		}
	}

	private void handleLongPress() {
		BuiltinKeyboardLayout.Key hit = pressedKey;
		if (hit == null) return;
		longPressConsumed = true;
		performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
		if (hit.kind == BuiltinKeyboardLayout.Kind.MODIFIER) {
			listener.onLongPressModifier(hit.modifier);
		} else if (hit.kind == BuiltinKeyboardLayout.Kind.CAPS_LOCK) {
			listener.onToggleCapsLock();
		} else if (hit.kind == BuiltinKeyboardLayout.Kind.KEY && isRepeatable(hit)) {
			repeatRunning = true;
			postDelayed(repeatTick, REPEAT_DELAY_MS);
		}
		canvas.invalidate();
	}

	private void handleRepeatTick() {
		if (!repeatRunning || pressedKey == null) {
			repeatRunning = false;
			return;
		}
		dispatchKey(pressedKey);
		postDelayed(repeatTick, REPEAT_INTERVAL_MS);
	}

	private void setRepetitionIdle() {
		repeatRunning = false;
		removeCallbacks(repeatTick);
	}

	private boolean isRepeatable(BuiltinKeyboardLayout.Key key) {
		switch (key.keyCode) {
			case KeyEvent.KEYCODE_DEL:
			case KeyEvent.KEYCODE_FORWARD_DEL:
			case KeyEvent.KEYCODE_DPAD_UP:
			case KeyEvent.KEYCODE_DPAD_DOWN:
			case KeyEvent.KEYCODE_DPAD_LEFT:
			case KeyEvent.KEYCODE_DPAD_RIGHT:
			case KeyEvent.KEYCODE_MOVE_HOME:
			case KeyEvent.KEYCODE_MOVE_END:
			case KeyEvent.KEYCODE_PAGE_UP:
			case KeyEvent.KEYCODE_PAGE_DOWN:
			case KeyEvent.KEYCODE_TAB:
				return true;
			default:
				return key.baseText.length() == 1;
		}
	}

	// endregion

	private int clamp(int value, int min, int max) {
		if (max < min) return min;
		return Math.max(min, Math.min(max, value));
	}

	private int dp(float value) {
		return ExtraSettingsUi.dp(activity, value);
	}

	/** Draws the key grid; hit testing reproduces the exact same geometry. */
	private final class KeyCanvas extends View {
		private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		int rowHeight;

		KeyCanvas() {
			super(activity);
			label.setTypeface(Typeface.DEFAULT_BOLD);
			label.setTextAlign(Paint.Align.CENTER);
			hint.setColor(ExtraSettingsUi.COLOR_MUTED);
			hint.setTextAlign(Paint.Align.RIGHT);
			stroke.setStyle(Paint.Style.STROKE);
		}

		@Override
		protected void onDraw(@NonNull Canvas draw) {
			if (rowHeight <= 0 || getWidth() <= 0) return;
			float gap = dp(GAP_DP);
			float corner = dp(5);
			float y = gap / 2f;
			BuiltinKeyboardState state = listener.getState();
			for (BuiltinKeyboardLayout.Row row : BuiltinKeyboardLayout.allRows()) {
				drawRow(draw, row, y, gap, corner, state);
				y += rowHeight;
			}
		}

		private void drawRow(Canvas draw, BuiltinKeyboardLayout.Row row, float y, float gap,
				float corner, BuiltinKeyboardState state) {
			float totalWeight = 0f;
			for (BuiltinKeyboardLayout.Key key : row.keys) {
				totalWeight += key.weight;
			}
			float width = getWidth() - gap;
			float unit = (width - gap * (row.keys.size() - 1)) / totalWeight;
			float x = gap / 2f;
			for (BuiltinKeyboardLayout.Key key : row.keys) {
				float keyWidth = unit * key.weight;
				rect.set(x, y, x + keyWidth, y + rowHeight - gap);
				drawKey(draw, key, rect, corner, state);
				x += keyWidth + gap;
			}
		}

		private void drawKey(Canvas draw, BuiltinKeyboardLayout.Key key, RectF bounds, float corner,
				BuiltinKeyboardState state) {
			boolean pressed = pressedKey == key;
			boolean active = false;
			boolean locked = false;
			switch (key.kind) {
				case MODIFIER:
					BuiltinKeyboardState.ArmState modifierState = state.getState(key.modifier);
					active = modifierState != BuiltinKeyboardState.ArmState.OFF;
					locked = modifierState == BuiltinKeyboardState.ArmState.LOCKED;
					break;
				case CAPS_LOCK:
					active = state.isCapsLock();
					locked = active;
					break;
				default:
					break;
			}
			int color;
			if (locked) {
				color = LOCKED_KEY_COLOR;
			} else if (pressed || active) {
				color = ExtraSettingsUi.COLOR_PRIMARY_CONTAINER;
			} else if (key.kind == BuiltinKeyboardLayout.Kind.MODIFIER
				|| key.kind == BuiltinKeyboardLayout.Kind.CAPS_LOCK) {
				color = ExtraSettingsUi.COLOR_SURFACE_VARIANT;
			} else {
				color = ExtraSettingsUi.COLOR_SURFACE_CONTAINER;
			}
			fill.setColor(color);
			draw.drawRoundRect(bounds, corner, corner, fill);
			if (active) {
				stroke.setStrokeWidth(dp(1.5f));
				stroke.setColor(ExtraSettingsUi.COLOR_PRIMARY);
				draw.drawRoundRect(bounds, corner, corner, stroke);
			}

			float textSize = Math.max(dp(8f), Math.min(dp(13f), bounds.height() * 0.44f));
			label.setTextSize(textSize);
			label.setColor(active || pressed
				? ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER : ExtraSettingsUi.COLOR_ON_SURFACE);
			String glyph = key.isLetter() ? letterGlyph(key, state) : key.label;
			float centerY = bounds.centerY();
			draw.drawText(glyph, bounds.centerX(),
				centerY - (label.ascent() + label.descent()) / 2f, label);

			if (!key.baseText.isEmpty() && !key.hint.isEmpty() && !key.isLetter()) {
				boolean shifted = state.isShiftActive();
				if (shifted) {
					hint.setTextSize(Math.max(dp(7f), textSize * 0.66f));
					draw.drawText(key.label, bounds.right - dp(4), bounds.top + dp(10), hint);
					// Re-draw the main glyph as the shifted symbol.
					label.setTextSize(textSize);
					draw.drawText(key.hint, bounds.centerX(),
						centerY - (label.ascent() + label.descent()) / 2f, label);
				} else {
					hint.setTextSize(Math.max(dp(7f), textSize * 0.66f));
					draw.drawText(key.hint, bounds.right - dp(4), bounds.top + dp(10), hint);
				}
			}
		}

		private String letterGlyph(BuiltinKeyboardLayout.Key key, BuiltinKeyboardState state) {
			boolean upper = state.isShiftActive() ^ state.isCapsLock();
			return upper ? key.baseText : key.baseText.toLowerCase(Locale.ROOT);
		}
	}

	/** Floating segmented switcher drawn above the keyboard panel. */
	private final class KeyboardTabs extends View {
		private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF pill = new RectF();
		private final RectF segment = new RectF();
		private final String[] labels = {
			activity.getString(R.string.builtin_keyboard_tab_builtin),
			activity.getString(R.string.builtin_keyboard_tab_system),
		};

		KeyboardTabs() {
			super(activity);
			setClickable(true);
			text.setTypeface(Typeface.DEFAULT_BOLD);
			text.setTextAlign(Paint.Align.CENTER);
			stroke.setStyle(Paint.Style.STROKE);
			setContentDescription(activity.getString(R.string.builtin_keyboard_switcher_description));
		}

		@Override
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
			text.setTextSize(dp(13));
			float widest = 0f;
			for (String item : labels) {
				widest = Math.max(widest, text.measureText(item));
			}
			int padding = dp(4);
			int width = Math.round(widest * labels.length + dp(30) * labels.length + padding * 2);
			int available = MeasureSpec.getSize(widthMeasureSpec);
			setMeasuredDimension(Math.min(width, available), tabHeight());
		}

		@Override
		protected void onDraw(@NonNull Canvas draw) {
			int padding = dp(4);
			float radius = getHeight() / 2f;
			pill.set(0, 0, getWidth(), getHeight());
			fill.setColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
			draw.drawRoundRect(pill, radius, radius, fill);
			stroke.setStrokeWidth(dp(1));
			stroke.setColor(ExtraSettingsUi.COLOR_OUTLINE);
			draw.drawRoundRect(pill, radius, radius, stroke);

			float segmentWidth = (getWidth() - padding * 2f) / labels.length;
			for (int index = 0; index < labels.length; index++) {
				float left = padding + index * segmentWidth;
				segment.set(left, padding, left + segmentWidth, getHeight() - padding);
				if (index == selectedTab) {
					fill.setColor(ExtraSettingsUi.COLOR_PRIMARY);
					draw.drawRoundRect(segment, segment.height() / 2f, segment.height() / 2f, fill);
				}
				text.setTextSize(dp(13));
				text.setColor(index == selectedTab
					? ExtraSettingsUi.COLOR_ON_PRIMARY : ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
				draw.drawText(labels[index], segment.centerX(),
					segment.centerY() - (text.ascent() + text.descent()) / 2f, text);
			}
		}

		@Override
		public boolean onTouchEvent(MotionEvent event) {
			if (event.getActionMasked() != MotionEvent.ACTION_UP) {
				return true;
			}
			int padding = dp(4);
			float segmentWidth = (getWidth() - padding * 2f) / labels.length;
			int index = (int) Math.floor((event.getX() - padding) / segmentWidth);
			index = Math.max(0, Math.min(labels.length - 1, index));
			performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
			if (index == TAB_SYSTEM) {
				listener.onSelectSystemTab();
			} else {
				listener.onSelectBuiltinTab();
			}
			return true;
		}
	}
}
