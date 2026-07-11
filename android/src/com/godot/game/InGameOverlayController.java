package com.godot.game;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app (not system) overlay for launcher shortcuts, settings, logs and inspector.
 */
public final class InGameOverlayController {
	private static final String TAG = "InGameOverlay";
	private static final String PREFS = "sts2_in_game_overlay";
	private static final String PREF_X = "bubble_x";
	private static final String PREF_Y = "bubble_y";

	private static final int COLOR_PANEL = Color.parseColor("#F0141820");
	private static final int COLOR_TAB = Color.parseColor("#FF2A2F38");
	private static final int COLOR_TAB_ACTIVE = Color.parseColor("#FF3D4654");
	private static final int COLOR_TEXT = Color.parseColor("#FFF2F4F8");
	private static final int COLOR_MUTED = Color.parseColor("#FF9AA3B2");
	private static final int COLOR_ACCENT = Color.parseColor("#FF8AB4F8");
	private static final int COLOR_DANGER = Color.parseColor("#FFFFB4AB");

	private static final float BUBBLE_BG_IDLE = 0.82f;
	private static final float BUBBLE_ICON_IDLE = 0.92f;
	private static final float BUBBLE_BG_ACTIVE = 1.0f;
	private static final float BUBBLE_ICON_ACTIVE = 1.0f;
	private static final int MIN_TOUCH_TARGET_DP = 48;
	private static final int PANEL_SIDE_MARGIN_DP = 8;
	private static final float DRAWER_WIDTH_FRACTION = 0.40f;
	private static final int DRAWER_MIN_WIDTH_DP = 300;
	private static final int DRAWER_MAX_WIDTH_DP = 640;
	private static final int TAB_RAIL_WIDTH_DP = 64;

	private final GodotApp activity;
	private final ExtraSettingsRepository repository;
	private InGameDevToolsClient devToolsClient;
	private final InGameLogTailer logTailer = new InGameLogTailer();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private ExecutorService ioExecutor;

	private FrameLayout root;
	private FrameLayout bubbleContainer;
	private ImageView bubbleIcon;
	private TextView bubbleLabel;
	private View bubbleBackground;
	private FrameLayout panelContainer;
	private View panelView;
	private LinearLayout panelBody;
	private TextView contextText;
	private RecyclerView logRecyclerView;
	private LinearLayoutManager logLayoutManager;
	private LogLineAdapter logAdapter;
	private LinearLayout logFiltersPanel;
	private Button logGodotButton;
	private Button logSts2Button;
	private ImageView logAutoBottomButton;
	private ImageView logFiltersButton;
	private TextView inspectorView;
	private EditText logFilterInput;
	private EditText inspectorSearch;
	private String currentTab = "quick";
	private boolean logPreferGodot = true;
	private boolean logShowingGodot = true;
	private boolean logAutoStickToBottom = true;
	private boolean logFiltersVisible = false;
	private boolean panelOpen;
	private boolean sessionHidden;
	private final List<String> inspectorStack = new ArrayList<>();
	private final List<View> tabButtons = new ArrayList<>();
	private final List<Button> logLevelButtons = new ArrayList<>();
	private final EnumSet<InGameLogTailer.Level> logEnabledLevels = EnumSet.allOf(InGameLogTailer.Level.class);
	private String inspectorPath = "";
	private final Runnable idleAlphaRunnable = this::applyBubbleIdleAlpha;
	private int bubbleWidthPx;
	private int bubbleHeightPx;
	private int safeInsetLeft;
	private int safeInsetTop;
	private int safeInsetRight;
	private int safeInsetBottom;
	private int touchSlopPx;
	private float downRawX;
	private float downRawY;
	private float downX;
	private float downY;
	private boolean dragging;

	public InGameOverlayController(GodotApp activity) {
		this.activity = activity;
		this.repository = new ExtraSettingsRepository(activity);
	}

	public void attach() {
		if (root != null) {
			return;
		}
		if (!repository.isInGameOverlayEnabled()) {
			Log.i(TAG, "In-game overlay disabled by settings.");
			return;
		}
		ensureWorkers();
		bubbleWidthPx = dp(84);
		bubbleHeightPx = dp(52);
		touchSlopPx = Math.max(dp(6), ViewConfiguration.get(activity).getScaledTouchSlop());
		root = new FrameLayout(activity);
		root.setLayoutParams(new FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT));
		root.setClickable(false);
		root.setFocusable(false);
		ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
			Insets safeInsets = insets.getInsets(
				WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
			safeInsetLeft = safeInsets.left;
			safeInsetTop = safeInsets.top;
			safeInsetRight = safeInsets.right;
			safeInsetBottom = safeInsets.bottom;
			clampBubbleToSafeBounds();
			updatePanelLayout();
			return insets;
		});
		root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
			if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
				clampBubbleToSafeBounds();
				updatePanelLayout();
			}
		});

		bubbleContainer = buildBubble();
		root.addView(bubbleContainer);

		panelContainer = new FrameLayout(activity);
		panelContainer.setVisibility(View.GONE);
		panelContainer.setClickable(true);
		panelContainer.setBackgroundColor(Color.parseColor("#66000000"));
		panelContainer.setOnClickListener(v -> closePanel());
		root.addView(panelContainer, new FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT));

		activity.addContentView(root, new FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT));
		ViewCompat.requestApplyInsets(root);
		restoreBubblePosition();
		applyBubbleIdleAlpha();
		Log.i(TAG, "In-game overlay attached.");
	}

	public void detach() {
		logTailer.stop();
		if (devToolsClient != null) {
			devToolsClient.close();
			devToolsClient = null;
		}
		if (ioExecutor != null) {
			ioExecutor.shutdownNow();
			ioExecutor = null;
		}
		mainHandler.removeCallbacksAndMessages(null);
		if (root != null && root.getParent() instanceof ViewGroup) {
			((ViewGroup) root.getParent()).removeView(root);
		}
		root = null;
		panelView = null;
		panelBody = null;
		panelOpen = false;
	}

	public void onResume() {
		if (!repository.isInGameOverlayEnabled()) {
			// Settings can be changed while GameSettingsActivity is on top. Tear the overlay
			// down here so a disabled setting never leaves an invisible touch interceptor.
			sessionHidden = false;
			if (root != null) {
				detach();
			}
			return;
		}
		if (root == null && !sessionHidden) {
			attach();
		}
	}

	private void ensureWorkers() {
		if (devToolsClient == null) {
			devToolsClient = new InGameDevToolsClient(activity);
		}
		if (ioExecutor == null || ioExecutor.isShutdown()) {
			ioExecutor = Executors.newSingleThreadExecutor(r -> {
				Thread thread = new Thread(r, "InGameOverlayIO");
				thread.setPriority(Thread.NORM_PRIORITY - 1);
				return thread;
			});
		}
	}

	private FrameLayout buildBubble() {
		FrameLayout container = new FrameLayout(activity);
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bubbleWidthPx, bubbleHeightPx);
		lp.gravity = Gravity.TOP | Gravity.START;
		container.setLayoutParams(lp);
		container.setContentDescription(activity.getString(R.string.in_game_overlay_title));
		container.setFocusable(true);
		container.setClickable(true);

		GradientDrawable bg = new GradientDrawable();
		bg.setShape(GradientDrawable.RECTANGLE);
		bg.setColor(Color.parseColor("#FF27313D"));
		bg.setCornerRadius(bubbleHeightPx / 2f);
		bubbleBackground = new View(activity);
		bubbleBackground.setBackground(bg);
		bubbleBackground.setAlpha(BUBBLE_BG_IDLE);
		container.addView(bubbleBackground, new FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT));

		LinearLayout content = new LinearLayout(activity);
		content.setOrientation(LinearLayout.HORIZONTAL);
		content.setGravity(Gravity.CENTER);
		content.setPadding(dp(10), 0, dp(12), 0);
		bubbleIcon = new ImageView(activity);
		bubbleIcon.setImageDrawable(MaterialSymbols.drawable(activity, "tune", Color.WHITE, 22));
		bubbleIcon.setAlpha(BUBBLE_ICON_IDLE);
		content.addView(bubbleIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
		bubbleLabel = text(activity.getString(R.string.in_game_overlay_tab_quick), 13, true);
		bubbleLabel.setAlpha(BUBBLE_ICON_IDLE);
		bubbleLabel.setSingleLine(true);
		LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		labelLp.setMarginStart(dp(6));
		content.addView(bubbleLabel, labelLp);
		container.addView(content, new FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		container.setOnClickListener(v -> openPanel());
		container.setOnTouchListener(this::onBubbleTouch);
		return container;
	}

	private boolean onBubbleTouch(View v, MotionEvent event) {
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				dragging = false;
				downRawX = event.getRawX();
				downRawY = event.getRawY();
				downX = bubbleContainer.getX();
				downY = bubbleContainer.getY();
				applyBubbleActiveAlpha();
				bubbleContainer.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).start();
				return true;
			case MotionEvent.ACTION_MOVE: {
				float dx = event.getRawX() - downRawX;
				float dy = event.getRawY() - downRawY;
				if (!dragging && (Math.abs(dx) > touchSlopPx || Math.abs(dy) > touchSlopPx)) {
					dragging = true;
				}
				if (dragging) {
					float nx = clamp(downX + dx, bubbleMinX(), bubbleMaxX());
					float ny = clamp(downY + dy, bubbleMinY(), bubbleMaxY());
					bubbleContainer.setX(nx);
					bubbleContainer.setY(ny);
					applyBubbleActiveAlpha();
				}
				return true;
			}
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				if (dragging) {
					snapBubbleToEdge();
					saveBubblePosition();
				} else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
					v.performClick();
				}
				bubbleContainer.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
				mainHandler.removeCallbacks(idleAlphaRunnable);
				mainHandler.postDelayed(idleAlphaRunnable, 700);
				return true;
			default:
				return false;
		}
	}

	private void applyBubbleIdleAlpha() {
		animateAlpha(bubbleBackground, BUBBLE_BG_IDLE);
		animateAlpha(bubbleIcon, BUBBLE_ICON_IDLE);
		animateAlpha(bubbleLabel, BUBBLE_ICON_IDLE);
	}

	private void applyBubbleActiveAlpha() {
		mainHandler.removeCallbacks(idleAlphaRunnable);
		bubbleBackground.setAlpha(BUBBLE_BG_ACTIVE);
		bubbleIcon.setAlpha(BUBBLE_ICON_ACTIVE);
		if (bubbleLabel != null) {
			bubbleLabel.setAlpha(BUBBLE_ICON_ACTIVE);
		}
	}

	private void animateAlpha(View view, float target) {
		if (view == null) {
			return;
		}
		ObjectAnimator.ofFloat(view, View.ALPHA, view.getAlpha(), target).setDuration(220).start();
	}

	private void snapBubbleToEdge() {
		if (root == null || bubbleContainer == null) {
			return;
		}
		float mid = (bubbleMinX() + bubbleMaxX() + bubbleWidthPx) / 2f;
		float x = bubbleContainer.getX() + bubbleWidthPx / 2f < mid ? bubbleMinX() : bubbleMaxX();
		float y = clamp(bubbleContainer.getY(), bubbleMinY(), bubbleMaxY());
		bubbleContainer.setX(x);
		bubbleContainer.setY(y);
	}

	private void clampBubbleToSafeBounds() {
		if (root == null || bubbleContainer == null || root.getWidth() <= 0 || root.getHeight() <= 0) {
			return;
		}
		bubbleContainer.setX(clamp(bubbleContainer.getX(), bubbleMinX(), bubbleMaxX()));
		bubbleContainer.setY(clamp(bubbleContainer.getY(), bubbleMinY(), bubbleMaxY()));
	}

	private float bubbleMinX() {
		return safeInsetLeft + dp(PANEL_SIDE_MARGIN_DP);
	}

	private float bubbleMaxX() {
		return Math.max(bubbleMinX(), root.getWidth() - safeInsetRight - bubbleWidthPx - dp(PANEL_SIDE_MARGIN_DP));
	}

	private float bubbleMinY() {
		return safeInsetTop + dp(PANEL_SIDE_MARGIN_DP);
	}

	private float bubbleMaxY() {
		return Math.max(bubbleMinY(), root.getHeight() - safeInsetBottom - bubbleHeightPx - dp(PANEL_SIDE_MARGIN_DP));
	}

	private void restoreBubblePosition() {
		SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		root.post(() -> {
			float x = prefs.getFloat(PREF_X, -1f);
			float y = prefs.getFloat(PREF_Y, -1f);
			if (x < 0 || y < 0 || root.getWidth() <= 0) {
				bubbleContainer.setX(bubbleMaxX());
				bubbleContainer.setY(clamp(safeInsetTop + dp(72), bubbleMinY(), bubbleMaxY()));
			} else {
				bubbleContainer.setX(clamp(x, bubbleMinX(), bubbleMaxX()));
				bubbleContainer.setY(clamp(y, bubbleMinY(), bubbleMaxY()));
			}
		});
	}

	private void saveBubblePosition() {
		activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
			.edit()
			.putFloat(PREF_X, bubbleContainer.getX())
			.putFloat(PREF_Y, bubbleContainer.getY())
			.apply();
	}

	private void openPanel() {
		if (panelOpen) {
			closePanel();
			return;
		}
		if (root == null || panelContainer == null) {
			return;
		}
		panelOpen = true;
		panelContainer.removeAllViews();
		if ("inspector".equals(currentTab) && !repository.isDevToolsEnabled()) {
			currentTab = "quick";
		}
		panelView = buildPanel();
		panelView.setOnClickListener(v -> { /* Consume drawer taps before they reach the scrim. */ });
		panelContainer.addView(panelView, createPanelLayoutParams());
		panelContainer.setVisibility(View.VISIBLE);
		bubbleContainer.setVisibility(View.GONE);
		showTab(currentTab);
		panelView.setAlpha(0f);
		View openingPanel = panelView;
		openingPanel.post(() -> {
			if (panelView != openingPanel || !panelOpen) {
				return;
			}
			openingPanel.setTranslationX(-Math.max(openingPanel.getWidth(), dp(DRAWER_MIN_WIDTH_DP)));
			openingPanel.animate().alpha(1f).translationX(0f).setDuration(220).start();
		});
	}

	private void closePanel() {
		closePanel(true);
	}

	private void closePanel(boolean animated) {
		panelOpen = false;
		logTailer.stop();
		View closingPanel = panelView;
		if (animated && closingPanel != null && panelContainer != null && panelContainer.getVisibility() == View.VISIBLE) {
			closingPanel.animate().cancel();
			closingPanel.animate()
				.alpha(0.98f)
				.translationX(-Math.max(closingPanel.getWidth(), dp(DRAWER_MIN_WIDTH_DP)))
				.setDuration(180)
				.withEndAction(() -> {
					if (!panelOpen) {
						finishClosePanel();
					}
				})
				.start();
			return;
		}
		finishClosePanel();
	}

	private void finishClosePanel() {
		if (panelContainer == null) {
			return;
		}
		panelContainer.setVisibility(View.GONE);
		panelContainer.removeAllViews();
		panelView = null;
		panelBody = null;
		tabButtons.clear();
		if (!sessionHidden && bubbleContainer != null) {
			bubbleContainer.setVisibility(View.VISIBLE);
		}
	}

	/** Returns true only when the overlay consumed Back; Godot handles every other Back press. */
	public boolean handleBackPressed() {
		if (!panelOpen) {
			return false;
		}
		closePanel();
		return true;
	}

	private FrameLayout.LayoutParams createPanelLayoutParams() {
		int availableWidth = Math.max(0, root.getWidth() - safeInsetLeft - safeInsetRight);
		int availableHeight = Math.max(0, root.getHeight() - safeInsetTop - safeInsetBottom);
		int width = drawerWidthFor(availableWidth);
		int height = availableHeight > 0 ? availableHeight : ViewGroup.LayoutParams.MATCH_PARENT;
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
		lp.gravity = Gravity.TOP | Gravity.START;
		lp.leftMargin = safeInsetLeft;
		lp.topMargin = safeInsetTop;
		lp.bottomMargin = safeInsetBottom;
		return lp;
	}

	private int drawerWidthFor(int availableWidth) {
		if (availableWidth <= 0) {
			return dp(360);
		}
		int target = Math.round(availableWidth * DRAWER_WIDTH_FRACTION);
		int max = Math.min(dp(DRAWER_MAX_WIDTH_DP), availableWidth);
		int min = Math.min(dp(DRAWER_MIN_WIDTH_DP), max);
		return Math.max(min, Math.min(max, target));
	}

	private void updatePanelLayout() {
		if (panelView != null && panelOpen && root != null && root.getWidth() > 0 && root.getHeight() > 0) {
			panelView.setLayoutParams(createPanelLayoutParams());
		}
	}

	private View buildPanel() {
		LinearLayout panel = new LinearLayout(activity);
		panel.setOrientation(LinearLayout.HORIZONTAL);
		panel.setBackground(drawerBackground());
		panel.setPadding(0, 0, 0, 0);
		panel.setElevation(dp(16));

		LinearLayout rail = new LinearLayout(activity);
		rail.setOrientation(LinearLayout.VERTICAL);
		rail.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
		rail.setPadding(dp(8), dp(12), dp(8), dp(12));
		rail.setBackgroundColor(Color.parseColor("#CC10151C"));
		tabButtons.clear();
		rail.addView(tabButton("quick", R.string.in_game_overlay_tab_quick, "bolt"));
		rail.addView(tabButton("settings", R.string.in_game_overlay_tab_settings, "settings"));
		rail.addView(tabButton("logs", R.string.in_game_overlay_tab_logs, "receipt_long"));
		if (repository.isDevToolsEnabled()) {
			rail.addView(tabButton("inspector", R.string.in_game_overlay_tab_inspector, "code"));
		}
		panel.addView(rail, new LinearLayout.LayoutParams(dp(TAB_RAIL_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT));

		View divider = new View(activity);
		divider.setBackgroundColor(Color.parseColor("#332A3240"));
		panel.addView(divider, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

		LinearLayout content = new LinearLayout(activity);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(dp(14), dp(12), dp(14), dp(12));

		panelBody = new LinearLayout(activity);
		panelBody.setOrientation(LinearLayout.VERTICAL);
		panelBody.setPadding(0, 0, 0, 0);
		LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		content.addView(panelBody, bodyLp);

		panel.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
		return panel;
	}

	private ImageView tabButton(String id, int labelRes, String icon) {
		ImageView button = new ImageView(activity);
		button.setTag(id);
		button.setContentDescription(activity.getString(labelRes));
		button.setImageDrawable(MaterialSymbols.drawable(activity, icon, COLOR_MUTED, 24));
		button.setPadding(dp(12), dp(12), dp(12), dp(12));
		button.setClickable(true);
		button.setFocusable(true);
		button.setOnClickListener(v -> showTab(id));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
			dp(MIN_TOUCH_TARGET_DP), dp(MIN_TOUCH_TARGET_DP));
		lp.bottomMargin = dp(8);
		button.setLayoutParams(lp);
		tabButtons.add(button);
		return button;
	}

	private void showTab(String tab) {
		currentTab = tab;
		updateTabSelection();
		if (panelBody == null) {
			return;
		}
		panelBody.removeAllViews();
		logTailer.stop();
		LinearLayout targetBody = "logs".equals(tab) ? panelBody : addScrollableTabBody(panelBody);
		switch (tab) {
			case "settings":
				buildSettingsTab(targetBody);
				break;
			case "logs":
				buildLogsTab(targetBody);
				break;
			case "inspector":
				if (repository.isDevToolsEnabled()) {
					buildInspectorTab(targetBody);
				} else {
					targetBody.addView(text(activity.getString(R.string.in_game_overlay_dev_required), 13, false));
				}
				break;
			case "quick":
			default:
				buildQuickTab(targetBody);
				break;
		}
	}

	private LinearLayout addScrollableTabBody(LinearLayout host) {
		ScrollView scrollView = new ScrollView(activity);
		scrollView.setFillViewport(true);
		scrollView.setClipToPadding(false);
		scrollView.setPadding(0, 0, 0, dp(12));
		LinearLayout body = new LinearLayout(activity);
		body.setOrientation(LinearLayout.VERTICAL);
		body.setPadding(0, 0, 0, dp(8));
		scrollView.addView(body, new ScrollView.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		host.addView(scrollView, new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		return body;
	}

	private void updateTabSelection() {
		for (View button : tabButtons) {
			Object tag = button.getTag();
			String id = tag instanceof String ? (String) tag : "";
			boolean selected = currentTab.equals(id);
			button.setSelected(selected);
			button.setBackground(pressableBackground(selected ? COLOR_TAB_ACTIVE : COLOR_TAB, dp(18)));
			if (button instanceof ImageView) {
				((ImageView) button).setImageDrawable(MaterialSymbols.drawable(activity, tabIconFor(id), selected ? COLOR_TEXT : COLOR_MUTED, 24));
			}
		}
	}

	private String tabIconFor(String id) {
		switch (id) {
			case "settings":
				return "settings";
			case "logs":
				return "receipt_long";
			case "inspector":
				return "code";
			case "quick":
			default:
				return "bolt";
		}
	}

	private void buildQuickTab(LinearLayout body) {
		if (repository.isDevToolsEnabled()) {
			contextText = text("", 13, false);
			contextText.setTextColor(COLOR_MUTED);
			contextText.setPadding(dp(4), dp(2), dp(4), dp(8));
			body.addView(contextText);
			refreshContextSummary();
		}

		body.addView(actionButton(R.string.in_game_overlay_open_keyboard, "keyboard", COLOR_TEXT, v -> {
			closePanel();
			GodotApp.showSoftKeyboardFromOverlay();
		}));
		body.addView(actionButton(R.string.in_game_overlay_open_settings, "settings", COLOR_TEXT, v -> {
			closePanel();
			GodotApp.launchGameSettingsFromGame();
		}));
		body.addView(actionButton(R.string.in_game_overlay_save_snapshot, "save", COLOR_TEXT, v -> createSnapshot()));
		body.addView(actionButton(R.string.in_game_overlay_restart_game, "restart_alt", COLOR_ACCENT, v -> confirmRestartGame()));
		body.addView(actionButton(R.string.in_game_overlay_exit_launcher, "login", COLOR_DANGER, v -> confirmExitToLauncher()));
		body.addView(actionButton(R.string.in_game_overlay_hide_session, "close", COLOR_MUTED, v -> {
			sessionHidden = true;
			closePanel();
			if (bubbleContainer != null) {
				bubbleContainer.setVisibility(View.GONE);
			}
			toast(R.string.in_game_overlay_hidden_toast);
		}));
	}

	private void buildSettingsTab(LinearLayout body) {
		TextView hint = text(activity.getString(R.string.in_game_overlay_settings_hint), 14, false);
		hint.setTextColor(COLOR_MUTED);
		hint.setPadding(dp(4), dp(2), dp(4), dp(8));
		body.addView(hint);
		try {
			JSONObject settings = repository.loadSettingsJson();
			body.addView(switchRow(R.string.preload_switch, settings.optBoolean("preload_enabled", true), checked -> {
				saveCompanion("preload_enabled", checked);
				devToolsClient.applySettings("preload_enabled");
			}));
			body.addView(switchRow(R.string.touch_lift_preview_switch, settings.optBoolean("touch_lift_preview", true), checked -> {
				saveCompanion("touch_lift_preview", checked);
				devToolsClient.applySettings("touch_lift_preview");
			}));
			body.addView(switchRow(R.string.mobile_selection_confirmation_switch, settings.optBoolean("mobile_selection_confirmation", true), checked ->
				saveCompanion("mobile_selection_confirmation", checked)));
			body.addView(switchRow(R.string.quick_sl_enabled_switch, settings.optBoolean("quick_sl_enabled", true), checked ->
				saveCompanion("quick_sl_enabled", checked)));

			body.addView(label(R.string.mobile_tooltip_mode_title));
			String tooltip = settings.optString("mobile_tooltip_mode", ExtraSettingsRepository.TOOLTIP_MODE_IMMEDIATE);
			body.addView(choiceRow(new String[] {
				ExtraSettingsRepository.TOOLTIP_MODE_IMMEDIATE,
				ExtraSettingsRepository.TOOLTIP_MODE_LONG_PRESS,
				ExtraSettingsRepository.TOOLTIP_MODE_HIDDEN
			}, new int[] {
				R.string.mobile_tooltip_mode_immediate,
				R.string.mobile_tooltip_mode_long_press,
				R.string.mobile_tooltip_mode_hidden
			}, tooltip, value -> {
				saveCompanionString("mobile_tooltip_mode", value);
				devToolsClient.applySettings("mobile_tooltip_mode");
			}));

			body.addView(label(R.string.screen_rotation_mode));
			String rotation = ExtraSettingsRepository.normalizeScreenRotationMode(
				settings.optString(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE, ExtraSettingsRepository.SCREEN_ROTATION_USER_LANDSCAPE));
			body.addView(choiceRow(new String[] {
				ExtraSettingsRepository.SCREEN_ROTATION_USER_LANDSCAPE,
				ExtraSettingsRepository.SCREEN_ROTATION_AUTO,
				ExtraSettingsRepository.SCREEN_ROTATION_LANDSCAPE,
				ExtraSettingsRepository.SCREEN_ROTATION_REVERSE_LANDSCAPE
			}, new int[] {
				R.string.screen_rotation_user_landscape,
				R.string.screen_rotation_auto,
				R.string.screen_rotation_landscape,
				R.string.screen_rotation_reverse_landscape
			}, rotation, value -> {
				try {
					repository.saveSetting(root -> {
						root.put(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE, value);
						root.put("android_flip_screen_180", ExtraSettingsRepository.SCREEN_ROTATION_REVERSE_LANDSCAPE.equals(value));
					});
				} catch (Exception exception) {
					Log.w(TAG, "save rotation failed", exception);
				}
				devToolsClient.applySettings(ExtraSettingsRepository.KEY_SCREEN_ROTATION_MODE, "android_flip_screen_180");
			}));

			TextView restartNote = text(activity.getString(R.string.in_game_overlay_settings_restart_note), 13, false);
			restartNote.setTextColor(COLOR_MUTED);
			restartNote.setPadding(dp(4), dp(10), dp(4), dp(4));
			body.addView(restartNote);
		} catch (Exception exception) {
			body.addView(text(exception.getMessage(), 12, false));
		}
	}

	private void buildLogsTab(LinearLayout body) {
		body.setPadding(0, 0, 0, 0);
		logAdapter = new LogLineAdapter();
		logLayoutManager = new LinearLayoutManager(activity);
		logRecyclerView = new RecyclerView(activity);
		logRecyclerView.setLayoutManager(logLayoutManager);
		logRecyclerView.setAdapter(logAdapter);
		logRecyclerView.setClipToPadding(false);
		logRecyclerView.setPadding(0, dp(6), 0, dp(6));
		logRecyclerView.setBackground(roundedBackground(Color.parseColor("#B010141B"), dp(12)));

		logFiltersPanel = new LinearLayout(activity);
		logFiltersPanel.setOrientation(LinearLayout.VERTICAL);
		logFiltersPanel.setVisibility(logFiltersVisible ? View.VISIBLE : View.GONE);

		LinearLayout sourceRow = new LinearLayout(activity);
		sourceRow.setOrientation(LinearLayout.HORIZONTAL);
		logGodotButton = compactButton("Godot");
		logSts2Button = compactButton("STS2");
		logGodotButton.setOnClickListener(v -> switchLogSource(true));
		logSts2Button.setOnClickListener(v -> switchLogSource(false));
		LinearLayout.LayoutParams godotLp = new LinearLayout.LayoutParams(0, dp(MIN_TOUCH_TARGET_DP), 1f);
		godotLp.setMarginEnd(dp(3));
		sourceRow.addView(logGodotButton, godotLp);
		LinearLayout.LayoutParams sts2Lp = new LinearLayout.LayoutParams(0, dp(MIN_TOUCH_TARGET_DP), 1f);
		sts2Lp.setMarginStart(dp(3));
		sourceRow.addView(logSts2Button, sts2Lp);
		logFiltersPanel.addView(sourceRow);

		HorizontalScrollView levelScroll = new HorizontalScrollView(activity);
		levelScroll.setHorizontalScrollBarEnabled(false);
		LinearLayout levelRow = new LinearLayout(activity);
		levelRow.setOrientation(LinearLayout.HORIZONTAL);
		levelRow.setPadding(0, dp(6), 0, dp(2));
		logLevelButtons.clear();
		for (InGameLogTailer.Level level : InGameLogTailer.Level.values()) {
			Button button = compactButton(logLevelAbbrev(level));
			button.setTag(level);
			button.setOnClickListener(v -> {
				if (logEnabledLevels.contains(level)) {
					logEnabledLevels.remove(level);
				} else {
					logEnabledLevels.add(level);
				}
				logTailer.setLevelEnabled(level, logEnabledLevels.contains(level));
				updateLogLevelButtons();
			});
			LinearLayout.LayoutParams levelLp = new LinearLayout.LayoutParams(
				dp(MIN_TOUCH_TARGET_DP), dp(MIN_TOUCH_TARGET_DP));
			levelLp.setMarginEnd(dp(5));
			levelRow.addView(button, levelLp);
			logLevelButtons.add(button);
		}
		levelScroll.addView(levelRow);
		logFiltersPanel.addView(levelScroll, new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		logFilterInput = new EditText(activity);
		logFilterInput.setHint(R.string.in_game_overlay_log_filter_hint);
		logFilterInput.setTextColor(COLOR_TEXT);
		logFilterInput.setHintTextColor(COLOR_MUTED);
		logFilterInput.setSingleLine(true);
		logFilterInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		logFilterInput.setMinHeight(dp(MIN_TOUCH_TARGET_DP));
		logFilterInput.setPadding(dp(12), 0, dp(12), 0);
		logFilterInput.setBackground(roundedBackground(COLOR_TAB, dp(14)));
		logFilterInput.addTextChangedListener(new SimpleTextWatcher(s -> logTailer.setTextFilter(s, false)));
		LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, dp(MIN_TOUCH_TARGET_DP));
		filterLp.topMargin = dp(8);
		logFiltersPanel.addView(logFilterInput, filterLp);
		body.addView(logFiltersPanel, new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		LinearLayout logArea = new LinearLayout(activity);
		logArea.setOrientation(LinearLayout.HORIZONTAL);
		logArea.setGravity(Gravity.TOP);
		logArea.setPadding(0, dp(8), 0, 0);
		logArea.addView(logRecyclerView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

		LinearLayout logControls = new LinearLayout(activity);
		logControls.setOrientation(LinearLayout.VERTICAL);
		logControls.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
		logControls.setPadding(dp(6), 0, 0, 0);
		logFiltersButton = logIconButton("settings", R.string.in_game_overlay_log_toggle_filters, v -> setLogFiltersVisible(!logFiltersVisible));
		logControls.addView(logFiltersButton);
		logControls.addView(logIconButton("expand_more", R.string.in_game_overlay_log_scroll_bottom, v -> scrollLogToBottom()));
		logControls.addView(logIconButton("expand_less", R.string.in_game_overlay_log_scroll_top, v -> scrollLogToTop()));
		logAutoBottomButton = logIconButton("sync", R.string.in_game_overlay_log_auto_bottom, v -> setLogAutoStickToBottom(!logAutoStickToBottom));
		logControls.addView(logAutoBottomButton);
		logArea.addView(logControls, new LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.MATCH_PARENT));

		LinearLayout.LayoutParams logAreaLp = new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		body.addView(logArea, logAreaLp);

		logTailer.setListener((lines, reset) -> {
			if (logAdapter == null) {
				return;
			}
			int start = Math.max(0, lines.size() - 500);
			logAdapter.submit(lines.subList(start, lines.size()));
			if (logAutoStickToBottom) {
				scrollLogToBottom();
			}
		});
		logTailer.setLevels(copyLogEnabledLevels());
		updateLogLevelButtons();
		updateLogAutoButton();
		updateLogFiltersButton();
		switchLogSource(logPreferGodot);
		logTailer.start();
	}

	private void switchLogSource(boolean preferGodot) {
		logPreferGodot = preferGodot;
		File godot = new File(new LaunchProfileManager(activity).getSelectedLogsRootDir(), "godot.log");
		File sts2 = new File(new File(activity.getFilesDir(), "logs"), "sts2.log");
		File chosen;
		if (preferGodot) {
			chosen = godot.isFile() ? godot : sts2;
		} else {
			chosen = sts2.isFile() ? sts2 : godot;
		}
		logShowingGodot = chosen.getAbsolutePath().equals(godot.getAbsolutePath());
		logTailer.setFile(chosen);
		if (!chosen.isFile() && logAdapter != null) {
			List<String> missing = new ArrayList<>();
			missing.add(activity.getString(R.string.in_game_overlay_log_missing, chosen.getAbsolutePath()));
			logAdapter.submit(missing);
		}
		updateLogSourceButtons();
		if (logAutoStickToBottom) {
			scrollLogToBottom();
		}
	}

	private Button compactButton(String label) {
		Button button = smallButton(label);
		button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		button.setPadding(dp(8), 0, dp(8), 0);
		button.setSingleLine(true);
		return button;
	}

	private ImageView logIconButton(String icon, int contentDescriptionRes, View.OnClickListener listener) {
		ImageView button = new ImageView(activity);
		button.setImageDrawable(MaterialSymbols.drawable(activity, icon, COLOR_MUTED, 22));
		button.setContentDescription(activity.getString(contentDescriptionRes));
		button.setPadding(dp(13), dp(13), dp(13), dp(13));
		button.setClickable(true);
		button.setFocusable(true);
		button.setBackground(pressableBackground(COLOR_TAB, dp(16)));
		button.setOnClickListener(listener);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(MIN_TOUCH_TARGET_DP), dp(MIN_TOUCH_TARGET_DP));
		lp.bottomMargin = dp(6);
		button.setLayoutParams(lp);
		return button;
	}

	private void updateLogSourceButtons() {
		updateLogTextButton(logGodotButton, logShowingGodot);
		updateLogTextButton(logSts2Button, !logShowingGodot);
	}

	private void updateLogLevelButtons() {
		for (Button button : logLevelButtons) {
			Object tag = button.getTag();
			boolean enabled = tag instanceof InGameLogTailer.Level && logEnabledLevels.contains((InGameLogTailer.Level) tag);
			updateLogTextButton(button, enabled);
		}
	}

	private EnumSet<InGameLogTailer.Level> copyLogEnabledLevels() {
		EnumSet<InGameLogTailer.Level> copy = EnumSet.noneOf(InGameLogTailer.Level.class);
		copy.addAll(logEnabledLevels);
		return copy;
	}

	private void updateLogTextButton(Button button, boolean active) {
		if (button == null) {
			return;
		}
		button.setTextColor(active ? COLOR_TEXT : COLOR_MUTED);
		button.setBackground(pressableBackground(active ? COLOR_TAB_ACTIVE : COLOR_TAB, dp(14)));
	}

	private void setLogAutoStickToBottom(boolean enabled) {
		logAutoStickToBottom = enabled;
		updateLogAutoButton();
		if (enabled) {
			scrollLogToBottom();
		}
	}

	private void updateLogAutoButton() {
		if (logAutoBottomButton == null) {
			return;
		}
		int tint = logAutoStickToBottom ? COLOR_TEXT : COLOR_MUTED;
		logAutoBottomButton.setImageDrawable(MaterialSymbols.drawable(activity, "sync", tint, 22));
		logAutoBottomButton.setBackground(pressableBackground(logAutoStickToBottom ? COLOR_TAB_ACTIVE : COLOR_TAB, dp(16)));
	}

	private void setLogFiltersVisible(boolean visible) {
		logFiltersVisible = visible;
		if (logFiltersPanel != null) {
			logFiltersPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
		}
		updateLogFiltersButton();
	}

	private void updateLogFiltersButton() {
		if (logFiltersButton == null) {
			return;
		}
		int tint = logFiltersVisible ? COLOR_TEXT : COLOR_MUTED;
		logFiltersButton.setImageDrawable(MaterialSymbols.drawable(activity, "settings", tint, 22));
		logFiltersButton.setBackground(pressableBackground(logFiltersVisible ? COLOR_TAB_ACTIVE : COLOR_TAB, dp(16)));
	}

	private void scrollLogToBottom() {
		if (logRecyclerView != null && logAdapter != null && logAdapter.getItemCount() > 0) {
			logRecyclerView.post(() -> logRecyclerView.scrollToPosition(logAdapter.getItemCount() - 1));
		}
	}

	private void scrollLogToTop() {
		if (logRecyclerView != null && logAdapter != null && logAdapter.getItemCount() > 0) {
			logRecyclerView.post(() -> logRecyclerView.scrollToPosition(0));
		}
	}

	private int logColorForLevel(InGameLogTailer.Level level) {
		if (level == null) {
			return COLOR_TEXT;
		}
		switch (level) {
			case VERBOSE:
				return Color.parseColor("#FF8A93A3");
			case DEBUG:
				return Color.parseColor("#FF8AB4F8");
			case WARN:
				return Color.parseColor("#FFFFD166");
			case ERROR:
				return Color.parseColor("#FFFF8A80");
			case INFO:
			default:
				return COLOR_TEXT;
		}
	}

	private String logLevelAbbrev(InGameLogTailer.Level level) {
		if (level == null) {
			return "I";
		}
		switch (level) {
			case VERBOSE:
				return "V";
			case DEBUG:
				return "D";
			case WARN:
				return "W";
			case ERROR:
				return "E";
			case INFO:
			default:
				return "I";
		}
	}

	private void buildInspectorTab(LinearLayout body) {
		LinearLayout nav = new LinearLayout(activity);
		nav.setOrientation(LinearLayout.HORIZONTAL);
		Button back = smallButton(activity.getString(R.string.in_game_overlay_inspector_back));
		back.setOnClickListener(v -> inspectorBack());
		Button roots = smallButton(activity.getString(R.string.in_game_overlay_inspector_roots));
		roots.setOnClickListener(v -> loadInspectorRoots());
		nav.addView(back);
		nav.addView(roots);
		body.addView(nav);

		inspectorSearch = new EditText(activity);
		inspectorSearch.setHint(R.string.in_game_overlay_inspector_search);
		inspectorSearch.setTextColor(COLOR_TEXT);
		inspectorSearch.setHintTextColor(COLOR_MUTED);
		inspectorSearch.setSingleLine(true);
		body.addView(inspectorSearch);

		if (repository.isDevInspectorWritable()) {
			body.addView(text(activity.getString(R.string.in_game_overlay_inspector_writable_on), 11, false));
		} else {
			body.addView(text(activity.getString(R.string.in_game_overlay_inspector_readonly), 11, false));
		}

		inspectorView = text(activity.getString(R.string.in_game_overlay_inspector_loading), 12, false);
		inspectorView.setTextIsSelectable(true);
		body.addView(inspectorView);
		loadInspectorRoots();
	}

	private void loadInspectorRoots() {
		inspectorStack.clear();
		inspectorPath = "";
		devToolsClient.request("inspector.roots", null, new InGameDevToolsClient.Callback() {
			@Override
			public void onResult(JSONObject response) {
				renderInspectorList(response);
			}

			@Override
			public void onError(String message) {
				if (inspectorView != null) {
					inspectorView.setText(localizeDevToolsError(message));
				}
			}
		});
	}

	private void loadInspectorMembers(String path) {
		inspectorPath = path;
		try {
			JSONObject extra = new JSONObject().put("path", path);
			devToolsClient.request("inspector.members", extra, new InGameDevToolsClient.Callback() {
				@Override
				public void onResult(JSONObject response) {
					renderInspectorList(response);
				}

					@Override
					public void onError(String message) {
						if (inspectorView != null) {
							inspectorView.setText(localizeDevToolsError(message));
						}
				}
			});
		} catch (Exception exception) {
			if (inspectorView != null) {
				inspectorView.setText(exception.getMessage());
			}
		}
	}

	private void renderInspectorList(JSONObject response) {
		if (panelBody == null || !"inspector".equals(currentTab)) {
			return;
		}
		// rebuild list area under inspectorView parent: replace body content after header is hard;
		// simplest: set text summary + attach clickable rows after inspectorView.
		ViewGroup parent = (ViewGroup) inspectorView.getParent();
		if (parent == null) {
			return;
		}
		// remove dynamic rows after inspectorView
		int index = parent.indexOfChild(inspectorView);
		while (parent.getChildCount() > index + 1) {
			parent.removeViewAt(parent.getChildCount() - 1);
		}

		if (!response.optBoolean("ok", true) && response.has("error")) {
			inspectorView.setText(localizeDevToolsError(response.optString("error", "error")));
			return;
		}
		JSONObject payload = response.optJSONObject("payload");
		if (payload == null) {
			payload = response;
		}
		if (payload.has("error") && !payload.has("items")) {
			inspectorView.setText(localizeDevToolsError(payload.optString("error")));
			return;
		}
		JSONArray items = payload.optJSONArray("items");
		String filter = inspectorSearch == null ? "" : inspectorSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
		inspectorView.setText(TextUtils.isEmpty(inspectorPath)
			? activity.getString(R.string.in_game_overlay_inspector_roots)
			: inspectorPath);
		if (items == null) {
			return;
		}
		for (int i = 0; i < items.length(); i++) {
			JSONObject item = items.optJSONObject(i);
			if (item == null) {
				continue;
			}
			String name = item.optString("name", item.optString("id", "?"));
			if (!filter.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(filter)) {
				continue;
			}
			String preview = item.optString("preview", "");
			String path = item.optString("path", "");
			boolean canNavigate = item.optBoolean("canNavigate", false);
			boolean canEdit = item.optBoolean("canEdit", false) && repository.isDevInspectorWritable();
			String editKind = item.optString("editKind", "");
			String line = name + "  ·  " + item.optString("type", "") + "\n" + preview;
			Button row = actionButtonLabel(line, v -> {
				if (canEdit) {
					promptEdit(path, editKind, preview);
				} else if (canNavigate || item.has("id")) {
					if (!TextUtils.isEmpty(inspectorPath)) {
						inspectorStack.add(inspectorPath);
					} else if (item.has("id")) {
						// from roots
					}
					loadInspectorMembers(path);
				} else {
					toast(preview);
				}
			});
			parent.addView(row);
		}
	}

	private void inspectorBack() {
		if (inspectorStack.isEmpty()) {
			loadInspectorRoots();
			return;
		}
		String path = inspectorStack.remove(inspectorStack.size() - 1);
		if (TextUtils.isEmpty(path)) {
			loadInspectorRoots();
		} else {
			loadInspectorMembers(path);
		}
	}

	private void promptEdit(String path, String editKind, String current) {
		Context dialogContext = materialDialogContext();
		EditText input = new EditText(dialogContext);
		input.setText(current);
		input.setTextColor(COLOR_TEXT);
		if ("int".equals(editKind)) {
			input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
		} else if ("float".equals(editKind)) {
			input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
		} else if ("bool".equals(editKind)) {
			input.setInputType(InputType.TYPE_CLASS_TEXT);
			input.setHint("true / false");
		}
		new com.google.android.material.dialog.MaterialAlertDialogBuilder(dialogContext)
			.setTitle(R.string.in_game_overlay_inspector_edit_title)
			.setMessage(path)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (d, w) -> {
				try {
					JSONObject extra = new JSONObject();
					extra.put("path", path);
					String raw = input.getText().toString().trim();
					if ("bool".equals(editKind)) {
						extra.put("value", Boolean.parseBoolean(raw));
					} else if ("int".equals(editKind)) {
						extra.put("value", Long.parseLong(raw));
					} else if ("float".equals(editKind)) {
						extra.put("value", Double.parseDouble(raw));
					} else {
						extra.put("value", raw);
					}
					devToolsClient.request("inspector.set", extra, new InGameDevToolsClient.Callback() {
						@Override
						public void onResult(JSONObject response) {
							JSONObject payload = response.optJSONObject("payload");
							if (payload != null && payload.optBoolean("ok", response.optBoolean("ok", false))) {
								toast(R.string.in_game_overlay_inspector_edit_ok);
								if (!TextUtils.isEmpty(inspectorPath)) {
									loadInspectorMembers(inspectorPath);
								} else {
									loadInspectorRoots();
								}
							} else {
								String err = payload != null ? payload.optString("error") : response.optString("error", "failed");
									toast(localizeDevToolsError(err));
							}
						}

						@Override
						public void onError(String message) {
							toast(localizeDevToolsError(message));
						}
					});
				} catch (Exception exception) {
					toast(exception.getMessage());
				}
			})
			.show();
	}

	private void confirmRestartGame() {
		new com.google.android.material.dialog.MaterialAlertDialogBuilder(materialDialogContext())
			.setTitle(R.string.in_game_overlay_restart_game)
			.setMessage(R.string.in_game_overlay_restart_game_confirm)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (d, w) -> {
				closePanel();
				if (!GodotApp.restartGameFromGame()) {
					toast(R.string.in_game_overlay_restart_game_failed);
				}
			})
			.show();
	}

	private void confirmExitToLauncher() {
		new com.google.android.material.dialog.MaterialAlertDialogBuilder(materialDialogContext())
			.setTitle(R.string.in_game_overlay_exit_launcher)
			.setMessage(R.string.in_game_overlay_exit_confirm)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (d, w) -> {
				closePanel();
				GodotApp.restartToSettingsFromGame();
			})
			.show();
	}

	/**
	 * GodotApp intentionally runs with the engine's DeviceDefault theme. Material dialogs
	 * enforce an AppCompat-derived theme, so wrap only the overlay dialogs instead of
	 * changing the game activity theme (which would affect the Godot window itself).
	 */
	private Context materialDialogContext() {
		return new ContextThemeWrapper(activity, R.style.Theme_Sts2ExtraSettings);
	}

	private void createSnapshot() {
		ioExecutor.execute(() -> {
			try {
				LocalSaveSnapshotManager.Snapshot snapshot = new LocalSaveSnapshotManager(activity).createManualSnapshot();
				mainHandler.post(() -> toast(activity.getString(R.string.in_game_overlay_snapshot_ok, snapshot == null ? "" : snapshot.id)));
			} catch (Exception exception) {
				mainHandler.post(() -> toast(exception.getMessage() == null ? "snapshot failed" : exception.getMessage()));
			}
		});
	}

	private void refreshContextSummary() {
		ioExecutor.execute(() -> {
			String json = GodotApp.getSelectedLaunchContextJson();
			String summary;
			try {
				if (json == null || json.trim().isEmpty()) {
					summary = activity.getString(R.string.in_game_overlay_context_missing);
				} else {
					JSONObject obj = new JSONObject(json);
					String profileId = firstNonEmpty(obj, "profile_id", "selected_profile_id", "instance_id", "selected_instance_id");
					String profileLabel = firstNonEmpty(obj, "display_name", "profile_label");
					String payload = firstNonEmpty(obj, "payload_version", "payload_label", "payload_id", "game_version", "selected_game_version_id");
					String compatTarget = firstNonEmpty(obj, "compat_target_id", "selected_compat_target_id");
					summary = "profile=" + displayIdWithLabel(profileId, profileLabel)
						+ "\npayload=" + emptyToQuestion(payload)
						+ "\ncompat=" + obj.optString("compat_pack_id", "?")
						+ " / " + (TextUtils.isEmpty(compatTarget) ? "-" : compatTarget);
				}
			} catch (Exception exception) {
				summary = json;
			}
			String finalSummary = summary;
			mainHandler.post(() -> {
				if (contextText != null) {
					contextText.setText(finalSummary);
				}
			});
		});
	}

	private String firstNonEmpty(JSONObject object, String... keys) {
		if (object == null || keys == null) {
			return "";
		}
		for (String key : keys) {
			String value = object.optString(key, "");
			if (!TextUtils.isEmpty(value)) {
				return value;
			}
		}
		return "";
	}

	private String displayIdWithLabel(String id, String label) {
		if (TextUtils.isEmpty(label)) {
			return emptyToQuestion(id);
		}
		if (TextUtils.isEmpty(id) || label.equals(id)) {
			return label;
		}
		return label + " (" + id + ")";
	}

	private String emptyToQuestion(String value) {
		return TextUtils.isEmpty(value) ? "?" : value;
	}

	private void saveCompanion(String key, boolean value) {
		try {
			repository.saveSetting(root -> root.put(key, value));
		} catch (Exception exception) {
			Log.w(TAG, "save " + key, exception);
			toast(exception.getMessage());
		}
	}

	private void saveCompanionString(String key, String value) {
		try {
			repository.saveSetting(root -> root.put(key, value));
		} catch (Exception exception) {
			Log.w(TAG, "save " + key, exception);
			toast(exception.getMessage());
		}
	}

	private View switchRow(int titleRes, boolean checked, BoolConsumer consumer) {
		LinearLayout row = new LinearLayout(activity);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setMinimumHeight(dp(MIN_TOUCH_TARGET_DP));
		row.setPadding(dp(14), 0, dp(4), 0);
		row.setBackground(roundedBackground(COLOR_TAB, dp(14)));
		TextView label = text(activity.getString(titleRes), 15, false);
		label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		CheckBox box = new CheckBox(activity);
		box.setChecked(checked);
		box.setMinWidth(dp(MIN_TOUCH_TARGET_DP));
		box.setMinHeight(dp(MIN_TOUCH_TARGET_DP));
		box.setFocusable(false);
		box.setOnCheckedChangeListener((b, isChecked) -> consumer.accept(isChecked));
		row.addView(label);
		row.addView(box);
		row.setOnClickListener(v -> box.setChecked(!box.isChecked()));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(6);
		row.setLayoutParams(lp);
		return row;
	}

	private View choiceRow(String[] values, int[] labels, String current, StringConsumer consumer) {
		RadioGroup row = new RadioGroup(activity);
		row.setOrientation(LinearLayout.VERTICAL);
		row.setPadding(0, dp(2), 0, dp(2));
		for (int i = 0; i < values.length; i++) {
			String value = values[i];
			RadioButton button = new RadioButton(activity);
			button.setId(View.generateViewId());
			button.setTag(value);
			button.setText(activity.getString(labels[i]));
			button.setTextColor(COLOR_TEXT);
			button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
			button.setGravity(Gravity.CENTER_VERTICAL);
			button.setMinHeight(dp(MIN_TOUCH_TARGET_DP));
			button.setPadding(dp(8), 0, dp(8), 0);
			button.setChecked(value.equals(current));
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(MIN_TOUCH_TARGET_DP));
			lp.topMargin = dp(2);
			button.setLayoutParams(lp);
			row.addView(button);
		}
		row.setOnCheckedChangeListener((group, checkedId) -> {
			View selected = group.findViewById(checkedId);
			Object value = selected == null ? null : selected.getTag();
			if (value instanceof String) {
				consumer.accept((String) value);
			}
		});
		return row;
	}

	private Button actionButton(int labelRes, View.OnClickListener listener) {
		return actionButton(labelRes, null, COLOR_TEXT, listener);
	}

	private Button actionButton(int labelRes, String icon, int textColor, View.OnClickListener listener) {
		return actionButtonLabel(activity.getString(labelRes), icon, textColor, listener);
	}

	private Button actionButtonLabel(String label, View.OnClickListener listener) {
		return actionButtonLabel(label, null, COLOR_TEXT, listener);
	}

	private Button actionButtonLabel(String label, String icon, int textColor, View.OnClickListener listener) {
		Button button = new Button(activity, null, android.R.attr.buttonStyle);
		button.setText(label);
		button.setAllCaps(false);
		button.setTextColor(textColor);
		button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
		button.setMinHeight(dp(54));
		button.setMinWidth(dp(MIN_TOUCH_TARGET_DP));
		button.setBackground(pressableBackground(COLOR_TAB, dp(16)));
		button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
		button.setPadding(dp(16), 0, dp(16), 0);
		button.setSingleLine(false);
		if (!TextUtils.isEmpty(icon)) {
			button.setCompoundDrawablesRelativeWithIntrinsicBounds(
				MaterialSymbols.drawable(activity, icon, textColor, 22), null, null, null);
			button.setCompoundDrawablePadding(dp(12));
		}
		button.setOnClickListener(listener);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(6);
		button.setLayoutParams(lp);
		return button;
	}

	private Button smallButton(String label) {
		Button button = new Button(activity, null, android.R.attr.buttonStyleSmall);
		button.setText(label);
		button.setAllCaps(false);
		button.setTextColor(COLOR_TEXT);
		button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		button.setMinHeight(dp(MIN_TOUCH_TARGET_DP));
		button.setMinWidth(dp(MIN_TOUCH_TARGET_DP));
		button.setBackground(pressableBackground(COLOR_TAB, dp(14)));
		button.setPadding(dp(14), 0, dp(14), 0);
		button.setGravity(Gravity.CENTER);
		return button;
	}

	private TextView text(String value, int sp, boolean bold) {
		TextView view = new TextView(activity);
		view.setText(value);
		view.setTextColor(COLOR_TEXT);
		view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		if (bold) {
			view.setTypeface(Typeface.DEFAULT_BOLD);
		}
		return view;
	}

	private TextView label(int res) {
		TextView view = text(activity.getString(res), 15, true);
		view.setPadding(dp(4), dp(14), dp(4), dp(6));
		return view;
	}

	private GradientDrawable roundedBackground(int color, int radiusPx) {
		GradientDrawable background = new GradientDrawable();
		background.setColor(color);
		background.setCornerRadius(radiusPx);
		return background;
	}

	private GradientDrawable drawerBackground() {
		GradientDrawable background = new GradientDrawable();
		background.setColor(COLOR_PANEL);
		float radius = dp(22);
		background.setCornerRadii(new float[] {
			0f, 0f,
			radius, radius,
			radius, radius,
			0f, 0f
		});
		return background;
	}

	private RippleDrawable pressableBackground(int color, int radiusPx) {
		GradientDrawable content = roundedBackground(color, radiusPx);
		GradientDrawable mask = roundedBackground(Color.WHITE, radiusPx);
		return new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#66FFFFFF")), content, mask);
	}

	private String localizeDevToolsError(String message) {
		if (message == null || message.trim().isEmpty()) {
			return activity.getString(R.string.in_game_overlay_devtools_request_timeout);
		}
		if (message.startsWith("DEVTOOLS_HOST_UNAVAILABLE")) {
			return activity.getString(R.string.in_game_overlay_devtools_host_unavailable);
		}
		if (message.startsWith("DEVTOOLS_REQUEST_TIMEOUT")) {
			return activity.getString(R.string.in_game_overlay_devtools_request_timeout);
		}
		return message;
	}

	private void toast(int res) {
		Toast.makeText(activity, res, Toast.LENGTH_SHORT).show();
	}

	private void toast(String message) {
		Toast.makeText(activity, message == null ? "" : message, Toast.LENGTH_SHORT).show();
	}

	private int dp(int value) {
		return Math.round(value * activity.getResources().getDisplayMetrics().density);
	}

	private static float clamp(float value, float min, float max) {
		if (max < min) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}

	private interface BoolConsumer {
		void accept(boolean value);
	}

	private interface StringConsumer {
		void accept(String value);
	}

	private final class LogLineAdapter extends RecyclerView.Adapter<LogLineViewHolder> {
		private final List<String> lines = new ArrayList<>();

		void submit(List<String> nextLines) {
			lines.clear();
			if (nextLines != null) {
				lines.addAll(nextLines);
			}
			notifyDataSetChanged();
		}

		@Override
		public LogLineViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			TextView view = text("", 11, false);
			view.setTypeface(Typeface.MONOSPACE);
			view.setIncludeFontPadding(false);
			view.setTextIsSelectable(false);
			view.setSingleLine(false);
			view.setPadding(dp(8), dp(3), dp(8), dp(3));
			view.setLayoutParams(new RecyclerView.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			return new LogLineViewHolder(view);
		}

		@Override
		public void onBindViewHolder(LogLineViewHolder holder, int position) {
			String line = lines.get(position);
			holder.textView.setText(line);
			holder.textView.setTextColor(logColorForLevel(InGameLogTailer.detectLevel(line)));
		}

		@Override
		public int getItemCount() {
			return lines.size();
		}
	}

	private static final class LogLineViewHolder extends RecyclerView.ViewHolder {
		final TextView textView;

		LogLineViewHolder(TextView textView) {
			super(textView);
			this.textView = textView;
		}
	}

	private static final class SimpleTextWatcher implements TextWatcher {
		private final StringConsumer consumer;

		private SimpleTextWatcher(StringConsumer consumer) {
			this.consumer = consumer;
		}

		@Override
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {
		}

		@Override
		public void onTextChanged(CharSequence s, int start, int before, int count) {
			consumer.accept(s == null ? "" : s.toString());
		}

		@Override
		public void afterTextChanged(Editable s) {
		}
	}
}
