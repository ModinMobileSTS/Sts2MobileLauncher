package com.godot.game;

import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
	private static final int INSPECTOR_TREE_INDENT_DP = 16;
	private static final int INSPECTOR_TREE_GUIDE_BASE_DP = 28;

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
	private TextView contextStatusView;
	private TextView contextProfileValue;
	private TextView contextPayloadValue;
	private TextView contextCompatValue;
	private RecyclerView logRecyclerView;
	private LinearLayoutManager logLayoutManager;
	private LogLineAdapter logAdapter;
	private LinearLayout logFiltersPanel;
	private Button logGodotButton;
	private Button logSts2Button;
	private ImageView logAutoBottomButton;
	private ImageView logFiltersButton;
	private TextView inspectorStatusView;
	private TextView inspectorResultView;
	private HorizontalScrollView inspectorHorizontalScroll;
	private RecyclerView inspectorRecyclerView;
	private LinearLayoutManager inspectorLayoutManager;
	private InspectorItemAdapter inspectorAdapter;
	private ImageView inspectorBackButton;
	private ImageView inspectorRuntimeButton;
	private ImageView inspectorSceneButton;
	private ImageView inspectorRefreshButton;
	private ImageView inspectorScriptButton;
	private EditText logFilterInput;
	private EditText inspectorSearch;
	private String currentTab = "quick";
	private String inspectorKind = "runtime_roots";
	private boolean logPreferGodot = true;
	private boolean logShowingGodot = true;
	private boolean logAutoStickToBottom = true;
	private boolean logFiltersVisible = false;
	private boolean panelOpen;
	private boolean sessionHidden;
	private final List<String> inspectorStack = new ArrayList<>();
	private final List<JSONObject> inspectorItems = new ArrayList<>();
	private final Set<String> inspectorExpandedNodes = new HashSet<>();
	private final Map<String, JSONObject> inspectorTreeByPath = new HashMap<>();
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
		LinearLayout targetBody = ("logs".equals(tab) || "inspector".equals(tab)) ? panelBody : addScrollableTabBody(panelBody);
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
			body.addView(buildContextInfoCard());
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

	private View buildContextInfoCard() {
		LinearLayout card = new LinearLayout(activity);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(dp(14), dp(12), dp(14), dp(10));
		card.setBackground(outlinedRoundedBackground(
			Color.parseColor("#CC18212B"), Color.parseColor("#553B4A5C"), dp(16), dp(1)));
		card.setElevation(dp(2));

		LinearLayout header = new LinearLayout(activity);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		ImageView icon = new ImageView(activity);
		icon.setImageDrawable(MaterialSymbols.drawable(activity, "gamepad", COLOR_ACCENT, 22));
		icon.setPadding(dp(9), dp(9), dp(9), dp(9));
		icon.setBackground(roundedBackground(Color.parseColor("#263E89C9"), dp(12)));
		header.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

		LinearLayout titleBox = new LinearLayout(activity);
		titleBox.setOrientation(LinearLayout.VERTICAL);
		titleBox.setPadding(dp(10), 0, 0, 0);
		TextView title = text(activity.getString(R.string.in_game_overlay_game_info_title), 15, true);
		contextStatusView = text(activity.getString(R.string.in_game_overlay_game_info_subtitle), 11, false);
		contextStatusView.setTextColor(COLOR_MUTED);
		titleBox.addView(title);
		titleBox.addView(contextStatusView);
		header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		card.addView(header);

		View divider = new View(activity);
		divider.setBackgroundColor(Color.parseColor("#334B596B"));
		LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
		dividerLp.topMargin = dp(10);
		dividerLp.bottomMargin = dp(4);
		card.addView(divider, dividerLp);

		contextProfileValue = addContextInfoRow(card, "person", R.string.in_game_overlay_game_info_profile);
		contextPayloadValue = addContextInfoRow(card, "stadia_controller", R.string.in_game_overlay_game_info_payload);
		contextCompatValue = addContextInfoRow(card, "extension", R.string.in_game_overlay_game_info_compat);

		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.bottomMargin = dp(8);
		card.setLayoutParams(lp);
		return card;
	}

	private TextView addContextInfoRow(LinearLayout parent, String iconName, int labelRes) {
		LinearLayout row = new LinearLayout(activity);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setMinimumHeight(dp(46));

		ImageView icon = new ImageView(activity);
		icon.setImageDrawable(MaterialSymbols.drawable(activity, iconName, COLOR_MUTED, 19));
		icon.setPadding(dp(7), dp(7), dp(7), dp(7));
		row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));

		LinearLayout textBox = new LinearLayout(activity);
		textBox.setOrientation(LinearLayout.VERTICAL);
		textBox.setPadding(dp(8), dp(3), 0, dp(3));
		TextView label = text(activity.getString(labelRes), 10, true);
		label.setTextColor(COLOR_MUTED);
		TextView value = text("—", 13, false);
		value.setSingleLine(false);
		value.setTextIsSelectable(true);
		textBox.addView(label);
		textBox.addView(value);
		row.addView(textBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		parent.addView(row);
		return value;
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
		body.setPadding(0, 0, 0, 0);

		LinearLayout toolbar = new LinearLayout(activity);
		toolbar.setOrientation(LinearLayout.HORIZONTAL);
		toolbar.setGravity(Gravity.CENTER_VERTICAL);
		toolbar.setPadding(0, 0, 0, dp(6));
		inspectorBackButton = inspectorIconButton("arrow_forward", R.string.in_game_overlay_inspector_back, v -> inspectorBack());
		inspectorBackButton.setRotation(180f);
		inspectorRuntimeButton = inspectorIconButton("desktop_windows", R.string.in_game_overlay_inspector_runtime, v -> loadInspectorRoots(true));
		inspectorSceneButton = inspectorIconButton("layers", R.string.in_game_overlay_inspector_scene, v -> loadGodotTree(true));
		inspectorRefreshButton = inspectorIconButton("sync", R.string.in_game_overlay_inspector_refresh, v -> refreshInspector());
		inspectorScriptButton = inspectorIconButton("code", R.string.in_game_overlay_inspector_run_script, v -> promptRunGdScript());
		toolbar.addView(inspectorBackButton, inspectorToolbarLayoutParams());
		toolbar.addView(inspectorRuntimeButton, inspectorToolbarLayoutParams());
		toolbar.addView(inspectorSceneButton, inspectorToolbarLayoutParams());
		toolbar.addView(inspectorRefreshButton, inspectorToolbarLayoutParams());
		toolbar.addView(inspectorScriptButton, inspectorToolbarLayoutParams());
		body.addView(toolbar);

		inspectorSearch = new EditText(activity);
		inspectorSearch.setHint(R.string.in_game_overlay_inspector_search);
		inspectorSearch.setTextColor(COLOR_TEXT);
		inspectorSearch.setHintTextColor(COLOR_MUTED);
		inspectorSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		inspectorSearch.setSingleLine(true);
		inspectorSearch.setPadding(dp(10), 0, dp(10), 0);
		inspectorSearch.setBackground(pressableBackground(Color.parseColor("#CC111820"), dp(12)));
		inspectorSearch.addTextChangedListener(new SimpleTextWatcher(value -> applyInspectorFilter()));
		body.addView(inspectorSearch, new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

		inspectorStatusView = text("", 11, false);
		inspectorStatusView.setTextColor(COLOR_MUTED);
		inspectorStatusView.setSingleLine(false);
		inspectorStatusView.setPadding(dp(4), dp(6), dp(4), dp(4));
		body.addView(inspectorStatusView);

		inspectorResultView = text("", 11, false);
		inspectorResultView.setTextColor(COLOR_ACCENT);
		inspectorResultView.setTypeface(Typeface.MONOSPACE);
		inspectorResultView.setTextIsSelectable(true);
		inspectorResultView.setVisibility(View.GONE);
		inspectorResultView.setPadding(dp(8), dp(6), dp(8), dp(6));
		inspectorResultView.setBackground(roundedBackground(Color.parseColor("#99101820"), dp(10)));
		body.addView(inspectorResultView, new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		inspectorRecyclerView = new RecyclerView(activity);
		inspectorRecyclerView.setClipToPadding(false);
		inspectorRecyclerView.setPadding(0, dp(4), 0, dp(12));
		inspectorLayoutManager = new LinearLayoutManager(activity);
		inspectorRecyclerView.setLayoutManager(inspectorLayoutManager);
		inspectorAdapter = new InspectorItemAdapter();
		inspectorRecyclerView.setAdapter(inspectorAdapter);
		inspectorHorizontalScroll = new HorizontalScrollView(activity);
		inspectorHorizontalScroll.setFillViewport(true);
		inspectorHorizontalScroll.setHorizontalScrollBarEnabled(true);
		inspectorHorizontalScroll.setScrollbarFadingEnabled(true);
		inspectorHorizontalScroll.addView(inspectorRecyclerView, new HorizontalScrollView.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		body.addView(inspectorHorizontalScroll, new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		if (inspectorKind.startsWith("godot")) {
			if ("godot_node".equals(inspectorKind) && !TextUtils.isEmpty(inspectorPath)) {
				loadGodotNode(inspectorPath, false);
			} else if ("godot_object".equals(inspectorKind) && !TextUtils.isEmpty(inspectorPath)) {
				loadGodotObject(inspectorPath, false);
			} else {
				loadGodotTree(false);
			}
		} else if ("runtime_members".equals(inspectorKind) && !TextUtils.isEmpty(inspectorPath)) {
			loadInspectorMembers(inspectorPath, false);
		} else {
			loadInspectorRoots(false);
		}
	}

	private ImageView inspectorIconButton(String icon, int contentDescriptionRes, View.OnClickListener listener) {
		ImageView button = new ImageView(activity);
		button.setContentDescription(activity.getString(contentDescriptionRes));
		button.setImageDrawable(MaterialSymbols.drawable(activity, icon, COLOR_MUTED, 22));
		button.setPadding(dp(9), dp(9), dp(9), dp(9));
		button.setClickable(true);
		button.setFocusable(true);
		button.setOnClickListener(listener);
		button.setBackground(pressableBackground(COLOR_TAB, dp(14)));
		return button;
	}

	private LinearLayout.LayoutParams inspectorToolbarLayoutParams() {
		return new LinearLayout.LayoutParams(0, dp(MIN_TOUCH_TARGET_DP), 1f);
	}

	private void loadInspectorRoots() {
		loadInspectorRoots(true);
	}

	private void loadInspectorRoots(boolean clearStack) {
		if (clearStack) {
			inspectorStack.clear();
		}
		inspectorKind = "runtime_roots";
		inspectorPath = "";
		setInspectorLoading(activity.getString(R.string.in_game_overlay_inspector_runtime));
		devToolsClient.request("inspector.roots", null, new InGameDevToolsClient.Callback() {
			@Override
			public void onResult(JSONObject response) {
				renderInspectorList(response, "runtime_roots", "");
			}

			@Override
			public void onError(String message) {
				setInspectorError(message);
			}
		});
	}

	private void loadInspectorMembers(String path) {
		loadInspectorMembers(path, true);
	}

	private void loadInspectorMembers(String path, boolean pushCurrent) {
		if (pushCurrent) {
			pushInspectorState();
		}
		inspectorKind = "runtime_members";
		inspectorPath = path;
		setInspectorLoading(path);
		try {
			JSONObject extra = new JSONObject().put("path", path);
			devToolsClient.request("inspector.members", extra, new InGameDevToolsClient.Callback() {
				@Override
				public void onResult(JSONObject response) {
					renderInspectorList(response, "runtime_members", path);
				}

				@Override
				public void onError(String message) {
					setInspectorError(message);
				}
			});
		} catch (Exception exception) {
			setInspectorError(exception.getMessage());
		}
	}

	private void loadGodotTree(boolean clearStack) {
		if (clearStack) {
			inspectorStack.clear();
			inspectorExpandedNodes.clear();
			if (inspectorHorizontalScroll != null) {
				inspectorHorizontalScroll.scrollTo(0, 0);
			}
		}
		inspectorKind = "godot_tree";
		inspectorPath = "";
		setInspectorLoading(activity.getString(R.string.in_game_overlay_inspector_scene_tree));
		devToolsClient.request("godot.tree", null, new InGameDevToolsClient.Callback() {
			@Override
			public void onResult(JSONObject response) {
				renderInspectorList(response, "godot_tree", "");
			}

			@Override
			public void onError(String message) {
				setInspectorError(message);
			}
		});
	}

	private void loadGodotNode(String path, boolean pushCurrent) {
		if (pushCurrent) {
			pushInspectorState();
		}
		inspectorKind = "godot_node";
		inspectorPath = path;
		setInspectorLoading(activity.getString(R.string.in_game_overlay_inspector_node));
		try {
			JSONObject extra = new JSONObject().put("node_path", path);
			devToolsClient.request("godot.node", extra, new InGameDevToolsClient.Callback() {
				@Override
				public void onResult(JSONObject response) {
					renderInspectorList(response, "godot_node", path);
				}

				@Override
				public void onError(String message) {
					setInspectorError(message);
				}
			});
		} catch (Exception exception) {
			setInspectorError(exception.getMessage());
		}
	}

	private void loadGodotObject(String objectRef, boolean pushCurrent) {
		if (TextUtils.isEmpty(objectRef)) {
			return;
		}
		if (pushCurrent) {
			pushInspectorState();
		}
		inspectorKind = "godot_object";
		inspectorPath = objectRef;
		setInspectorLoading(objectRef);
		try {
			JSONObject extra = new JSONObject().put("object_ref", objectRef);
			devToolsClient.request("godot.object", extra, new InGameDevToolsClient.Callback() {
				@Override
				public void onResult(JSONObject response) {
					renderInspectorList(response, "godot_object", objectRef);
				}

				@Override
				public void onError(String message) {
					setInspectorError(message);
				}
			});
		} catch (Exception exception) {
			setInspectorError(exception.getMessage());
		}
	}

	private void refreshInspector() {
		if ("godot_tree".equals(inspectorKind)) {
			loadGodotTree(false);
		} else if ("godot_node".equals(inspectorKind) && !TextUtils.isEmpty(inspectorPath)) {
			loadGodotNode(inspectorPath, false);
		} else if ("godot_object".equals(inspectorKind) && !TextUtils.isEmpty(inspectorPath)) {
			loadGodotObject(inspectorPath, false);
		} else if ("runtime_members".equals(inspectorKind) && !TextUtils.isEmpty(inspectorPath)) {
			loadInspectorMembers(inspectorPath, false);
		} else {
			loadInspectorRoots(false);
		}
	}

	private void setInspectorLoading(String label) {
		updateInspectorToolbarState();
		inspectorItems.clear();
		applyInspectorFilter();
		if (inspectorResultView != null) {
			inspectorResultView.setVisibility(View.GONE);
		}
		if (inspectorStatusView != null) {
			inspectorStatusView.setText(activity.getString(R.string.in_game_overlay_inspector_loading) + " · " + label);
		}
	}

	private void setInspectorError(String message) {
		inspectorItems.clear();
		applyInspectorFilter();
		if (inspectorStatusView != null) {
			inspectorStatusView.setText(localizeDevToolsError(message));
			inspectorStatusView.setTextColor(COLOR_DANGER);
		}
		updateInspectorToolbarState();
	}

	private void renderInspectorList(JSONObject response, String kind, String path) {
		if (panelBody == null || !"inspector".equals(currentTab)) {
			return;
		}
		if (!kind.equals(inspectorKind) || !TextUtils.equals(path, inspectorPath)) {
			return;
		}

		if (!response.optBoolean("ok", true) && response.has("error")) {
			setInspectorError(response.optString("error", "error"));
			return;
		}
		JSONObject payload = response.optJSONObject("payload");
		if (payload == null) {
			payload = response;
		}
		if (payload.has("error") && !payload.has("items")) {
			setInspectorError(payload.optString("error"));
			return;
		}
		JSONArray items = payload.optJSONArray("items");
		inspectorItems.clear();
		inspectorTreeByPath.clear();
		if (items != null) {
			for (int i = 0; i < items.length(); i++) {
				JSONObject item = items.optJSONObject(i);
				if (item != null) {
					inspectorItems.add(item);
					if ("godot_tree".equals(kind)) {
						inspectorTreeByPath.put(item.optString("path", ""), item);
					}
				}
			}
		}
		if (inspectorStatusView != null) {
			String title = inspectorTitleFor(kind, path, payload);
			String suffix = inspectorItems.isEmpty()
				? activity.getString(R.string.in_game_overlay_inspector_empty)
				: activity.getString(R.string.in_game_overlay_inspector_item_count, inspectorItems.size());
			inspectorStatusView.setText(title + " · " + suffix);
			inspectorStatusView.setTextColor(COLOR_MUTED);
		}
		applyInspectorFilter();
		updateInspectorToolbarState();
	}

	private void inspectorBack() {
		if (inspectorStack.isEmpty()) {
			if ("godot_node".equals(inspectorKind) || "godot_object".equals(inspectorKind)) {
				loadGodotTree(false);
			} else if (!"runtime_roots".equals(inspectorKind)) {
				loadInspectorRoots(false);
			}
			return;
		}
		restoreInspectorState(inspectorStack.remove(inspectorStack.size() - 1));
	}

	private void pushInspectorState() {
		inspectorStack.add(inspectorKind + "\u001f" + inspectorPath);
	}

	private void restoreInspectorState(String encoded) {
		String[] parts = encoded == null ? new String[0] : encoded.split("\u001f", 2);
		String kind = parts.length > 0 ? parts[0] : "runtime_roots";
		String path = parts.length > 1 ? parts[1] : "";
		if ("godot_tree".equals(kind)) {
			loadGodotTree(false);
		} else if ("godot_node".equals(kind)) {
			loadGodotNode(path, false);
		} else if ("godot_object".equals(kind)) {
			loadGodotObject(path, false);
		} else if ("runtime_members".equals(kind)) {
			loadInspectorMembers(path, false);
		} else {
			loadInspectorRoots(false);
		}
	}

	private String inspectorTitleFor(String kind, String path, JSONObject payload) {
		if ("godot_tree".equals(kind)) {
			return activity.getString(R.string.in_game_overlay_inspector_scene_tree);
		}
		if ("godot_node".equals(kind)) {
			String type = payload == null ? "" : payload.optString("type", "");
			String name = payload == null ? "" : payload.optString("name", "");
			if (TextUtils.isEmpty(name)) {
				name = activity.getString(R.string.in_game_overlay_inspector_node);
			}
			return name + (TextUtils.isEmpty(type) ? "" : " · " + displayInspectorNodeType(type));
		}
		if ("godot_object".equals(kind)) {
			String type = payload == null ? "" : payload.optString("type", "");
			String preview = payload == null ? "" : payload.optString("preview", path);
			return preview + (TextUtils.isEmpty(type) ? "" : " · " + simpleTypeName(type));
		}
		if ("runtime_members".equals(kind)) {
			String type = payload == null ? "" : payload.optString("type", "");
			return path + (TextUtils.isEmpty(type) ? "" : " · " + simpleTypeName(type));
		}
		return activity.getString(R.string.in_game_overlay_inspector_runtime);
	}

	private void updateInspectorToolbarState() {
		boolean runtimeSelected = inspectorKind.startsWith("runtime");
		boolean sceneSelected = inspectorKind.startsWith("godot");
		updateInspectorIconButton(inspectorRuntimeButton, "desktop_windows", runtimeSelected);
		updateInspectorIconButton(inspectorSceneButton, "layers", sceneSelected);
		updateInspectorIconButton(inspectorRefreshButton, "sync", false);
		updateInspectorIconButton(inspectorScriptButton, "code", false);
		if (inspectorBackButton != null) {
			boolean canReturn = !inspectorStack.isEmpty()
				|| "runtime_members".equals(inspectorKind)
				|| "godot_node".equals(inspectorKind)
				|| "godot_object".equals(inspectorKind);
			inspectorBackButton.setVisibility(canReturn ? View.VISIBLE : View.GONE);
		}
	}

	private void updateInspectorIconButton(ImageView button, String icon, boolean active) {
		if (button == null) {
			return;
		}
		button.setImageDrawable(MaterialSymbols.drawable(activity, icon, active ? COLOR_TEXT : COLOR_MUTED, 22));
		button.setBackground(pressableBackground(active ? COLOR_TAB_ACTIVE : COLOR_TAB, dp(14)));
	}

	private void applyInspectorFilter() {
		if (inspectorAdapter == null) {
			return;
		}
		String filter = inspectorSearch == null ? "" : inspectorSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
		List<JSONObject> filtered = new ArrayList<>();
		if ("godot_tree".equals(inspectorKind)) {
			Set<String> searchVisible = new HashSet<>();
			if (!filter.isEmpty()) {
				for (JSONObject item : inspectorItems) {
					if (!inspectorItemMatches(item, filter)) {
						continue;
					}
					String path = item.optString("path", "");
					while (!TextUtils.isEmpty(path) && searchVisible.add(path)) {
						JSONObject current = inspectorTreeByPath.get(path);
						path = current == null ? "" : current.optString("parentPath", "");
					}
				}
			}
			for (JSONObject item : inspectorItems) {
				String path = item.optString("path", "");
				if (!filter.isEmpty()) {
					if (searchVisible.contains(path)) {
						filtered.add(item);
					}
				} else if (isInspectorTreeItemVisible(item, inspectorTreeByPath)) {
					filtered.add(item);
				}
			}
			inspectorAdapter.submit(filtered);
			updateInspectorListWidth(filtered);
			return;
		}
		for (JSONObject item : inspectorItems) {
			if (filter.isEmpty() || inspectorItemMatches(item, filter)) {
				filtered.add(item);
			}
		}
		inspectorAdapter.submit(filtered);
		updateInspectorListWidth(filtered);
	}

	private boolean inspectorItemMatches(JSONObject item, String filter) {
		String haystack = (item.optString("name", item.optString("id", "")) + "\n"
			+ item.optString("type", "") + "\n"
			+ item.optString("preview", "") + "\n"
			+ item.optString("path", "")).toLowerCase(Locale.ROOT);
		return haystack.contains(filter);
	}

	private boolean isInspectorTreeItemVisible(JSONObject item, Map<String, JSONObject> byPath) {
		String parentPath = item.optString("parentPath", "");
		while (!TextUtils.isEmpty(parentPath)) {
			if (!inspectorExpandedNodes.contains(parentPath)) {
				return false;
			}
			JSONObject parent = byPath.get(parentPath);
			parentPath = parent == null ? "" : parent.optString("parentPath", "");
		}
		return true;
	}

	private void toggleInspectorTreeNode(JSONObject item) {
		if (!item.optBoolean("hasChildren", false)) {
			return;
		}
		String path = item.optString("path", "");
		if (!inspectorExpandedNodes.remove(path)) {
			inspectorExpandedNodes.add(path);
		}
		applyInspectorFilter();
	}

	private String displayInspectorNodeType(String type) {
		if (TextUtils.isEmpty(type)) {
			return activity.getString(R.string.in_game_overlay_inspector_node);
		}
		String display = type.trim();
		int slash = display.indexOf(" / ");
		if (slash > 0) {
			display = display.substring(0, slash).trim();
		}
		return simpleTypeName(display);
	}

	private int inspectorNodeTypeColor(String type, int background) {
		String key = TextUtils.isEmpty(type) ? "Node" : type;
		int hash = key.hashCode() & 0x7fffffff;
		float hue = hash % 360;
		int color = Color.HSVToColor(new float[] {hue, 0.42f, 0.94f});
		int opaqueBackground = Color.rgb(Color.red(background), Color.green(background), Color.blue(background));
		for (int i = 0; i < 4 && ColorUtils.calculateContrast(color, opaqueBackground) < 3.2; i++) {
			color = ColorUtils.blendARGB(color, Color.WHITE, 0.14f);
		}
		return color;
	}

	private int inspectorTreeGuideWidth(int depth) {
		return dp(INSPECTOR_TREE_GUIDE_BASE_DP + Math.max(0, depth) * INSPECTOR_TREE_INDENT_DP);
	}

	private void updateInspectorListWidth(List<JSONObject> visibleItems) {
		if (inspectorHorizontalScroll == null || inspectorRecyclerView == null) {
			return;
		}
		inspectorHorizontalScroll.post(() -> {
			int viewport = inspectorHorizontalScroll.getWidth()
				- inspectorHorizontalScroll.getPaddingLeft()
				- inspectorHorizontalScroll.getPaddingRight();
			if (viewport <= 0) {
				return;
			}
			int width = viewport;
			if ("godot_tree".equals(inspectorKind)) {
				Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
				namePaint.setTextSize(TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_SP, 14, activity.getResources().getDisplayMetrics()));
				Paint typePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
				typePaint.setTextSize(TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_SP, 11, activity.getResources().getDisplayMetrics()));
				for (JSONObject item : visibleItems) {
					int guide = inspectorTreeGuideWidth(item.optInt("depth", 0));
					String name = item.optString("name", "");
					String type = displayInspectorNodeType(item.optString("type", ""));
					float textWidth = Math.max(namePaint.measureText(name), typePaint.measureText(type));
					width = Math.max(width, guide + dp(20) + Math.round(textWidth));
				}
			}
			HorizontalScrollView.LayoutParams params = new HorizontalScrollView.LayoutParams(
				width, ViewGroup.LayoutParams.MATCH_PARENT);
			inspectorRecyclerView.setLayoutParams(params);
			if (!"godot_tree".equals(inspectorKind)) {
				inspectorHorizontalScroll.scrollTo(0, 0);
			}
		});
	}

	private void copyInspectorItem(JSONObject item) {
		String value = item.optString("preview", "");
		String label = item.optString("name", item.optString("id", "Inspector value"));
		copyInspectorText(label, value);
	}

	private void copyInspectorText(String label, String value) {
		String copiedValue = value == null ? "" : value;
		ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard != null) {
			clipboard.setPrimaryClip(ClipData.newPlainText(label, copiedValue));
		}
		String toastValue = copiedValue.replace('\n', ' ').replace('\r', ' ');
		if (toastValue.length() > 72) {
			toastValue = toastValue.substring(0, 72) + "…";
		}
		if (toastValue.isEmpty()) {
			toastValue = activity.getString(R.string.in_game_overlay_inspector_empty_value);
		}
		toast(activity.getString(R.string.in_game_overlay_inspector_copied, toastValue));
	}

	private boolean[] inspectorTreeAncestorContinuations(JSONObject item) {
		int depth = Math.max(0, item.optInt("depth", 0));
		boolean[] continuation = new boolean[depth];
		String parentPath = item.optString("parentPath", "");
		while (!TextUtils.isEmpty(parentPath)) {
			JSONObject parent = inspectorTreeByPath.get(parentPath);
			if (parent == null) {
				break;
			}
			int parentDepth = parent.optInt("depth", -1);
			if (parentDepth >= 0 && parentDepth < continuation.length) {
				continuation[parentDepth] = !parent.optBoolean("lastSibling", true);
			}
			parentPath = parent.optString("parentPath", "");
		}
		return continuation;
	}

	private void onInspectorItemClicked(JSONObject item) {
		String domain = item.optString("domain", "runtime");
		String preview = item.optString("preview", "");
		if ("godot".equals(domain)) {
			if ("godot_tree".equals(inspectorKind) && "node".equals(item.optString("kind", ""))) {
				toggleInspectorTreeNode(item);
				return;
			}
			if (item.optBoolean("canNavigate", false)) {
				navigateInspectorItem(item);
				return;
			}
			if (item.optBoolean("canEdit", false) && repository.isDevInspectorWritable()) {
				promptGodotEdit(
					item.optString("nodePath", ""),
					item.optString("objectRef", ""),
					item.optString("property", ""),
					item.optString("editKind", ""),
					preview);
				return;
			}
			copyInspectorItem(item);
			return;
		}

		String path = item.optString("path", "");
		if (item.optBoolean("canEdit", false) && repository.isDevInspectorWritable()) {
			promptEdit(path, item.optString("editKind", ""), preview);
		} else if (item.optBoolean("canNavigate", false)) {
			loadInspectorMembers(path, true);
		} else {
			copyInspectorItem(item);
		}
	}

	private void navigateInspectorItem(JSONObject item) {
		if ("godot_tree".equals(inspectorKind) && "node".equals(item.optString("kind", ""))) {
			loadGodotNode(item.optString("path", ""), true);
			return;
		}
		String targetNodePath = item.optString("targetNodePath", "");
		if (!TextUtils.isEmpty(targetNodePath)) {
			loadGodotNode(targetNodePath, true);
			return;
		}
		String targetObjectRef = item.optString("targetObjectRef", "");
		if (!TextUtils.isEmpty(targetObjectRef)) {
			loadGodotObject(targetObjectRef, true);
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
					extra.put("value", parseInspectorInput(editKind, raw));
					devToolsClient.request("inspector.set", extra, new InGameDevToolsClient.Callback() {
						@Override
						public void onResult(JSONObject response) {
							JSONObject payload = response.optJSONObject("payload");
							if (payload != null && payload.optBoolean("ok", response.optBoolean("ok", false))) {
								toast(R.string.in_game_overlay_inspector_edit_ok);
								if (!TextUtils.isEmpty(inspectorPath)) {
									loadInspectorMembers(inspectorPath, false);
								} else {
									loadInspectorRoots(false);
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

	private void promptGodotEdit(String nodePath, String objectRef, String property, String editKind, String current) {
		if ((TextUtils.isEmpty(nodePath) && TextUtils.isEmpty(objectRef)) || TextUtils.isEmpty(property)) {
			return;
		}
		Context dialogContext = materialDialogContext();
		EditText input = new EditText(dialogContext);
		input.setText(current);
		input.setTextColor(COLOR_TEXT);
		input.setSingleLine(true);
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
			.setMessage((TextUtils.isEmpty(nodePath) ? objectRef : nodePath) + "\n" + property)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (d, w) -> {
				try {
					JSONObject extra = new JSONObject();
					if (!TextUtils.isEmpty(nodePath)) {
						extra.put("node_path", nodePath);
					}
					if (!TextUtils.isEmpty(objectRef)) {
						extra.put("object_ref", objectRef);
					}
					extra.put("property", property);
					extra.put("value", parseInspectorInput(editKind, input.getText().toString().trim()));
					devToolsClient.request("godot.set", extra, new InGameDevToolsClient.Callback() {
						@Override
						public void onResult(JSONObject response) {
							JSONObject payload = response.optJSONObject("payload");
							if (payload != null && payload.optBoolean("ok", response.optBoolean("ok", false))) {
								toast(R.string.in_game_overlay_inspector_edit_ok);
								if (!TextUtils.isEmpty(objectRef)) {
									loadGodotObject(objectRef, false);
								} else {
									loadGodotNode(nodePath, false);
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

	private Object parseInspectorInput(String editKind, String raw) throws Exception {
		if ("bool".equals(editKind)) {
			return Boolean.parseBoolean(raw);
		}
		if ("int".equals(editKind)) {
			return Long.parseLong(raw);
		}
		if ("float".equals(editKind)) {
			return Double.parseDouble(raw);
		}
		return raw;
	}

	private void promptRunGdScript() {
		if (!repository.isDevInspectorWritable()) {
			toast(R.string.in_game_overlay_inspector_writable_required);
			return;
		}
		Context dialogContext = materialDialogContext();
		EditText input = new EditText(dialogContext);
		input.setTextColor(COLOR_TEXT);
		input.setHint(R.string.in_game_overlay_inspector_script_hint);
		input.setSingleLine(false);
		int editorMaxHeight = Math.max(dp(120), Math.min(dp(260), Math.round(
			activity.getResources().getDisplayMetrics().heightPixels * 0.42f)));
		input.setMinHeight(Math.min(dp(150), editorMaxHeight));
		input.setMaxHeight(editorMaxHeight);
		input.setVerticalScrollBarEnabled(true);
		input.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
		input.setPadding(dp(12), dp(10), dp(12), dp(10));
		input.setGravity(Gravity.TOP | Gravity.START);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(dialogContext)
			.setTitle(R.string.in_game_overlay_inspector_run_script)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.in_game_overlay_inspector_run, (d, w) -> {
				try {
					JSONObject extra = new JSONObject();
					extra.put("source", input.getText().toString());
					if ("godot_node".equals(inspectorKind)) {
						extra.put("node_path", inspectorPath);
					}
					devToolsClient.request("godot.script", extra, 8000L, new InGameDevToolsClient.Callback() {
						@Override
						public void onResult(JSONObject response) {
							renderInspectorResult(response);
						}

						@Override
						public void onError(String message) {
							setInspectorResult(localizeDevToolsError(message), true);
						}
					});
				} catch (Exception exception) {
					toast(exception.getMessage());
				}
			})
			.create();
		dialog.setOnShowListener(ignored -> {
			if (dialog.getWindow() != null) {
				dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
			}
		});
		dialog.show();
	}

	private void renderInspectorResult(JSONObject response) {
		if (!response.optBoolean("ok", true) && response.has("error")) {
			setInspectorResult(localizeDevToolsError(response.optString("error")), true);
			return;
		}
		JSONObject payload = response.optJSONObject("payload");
		if (payload == null) {
			payload = response;
		}
		if (!payload.optBoolean("ok", true) && payload.has("error")) {
			setInspectorResult(localizeDevToolsError(payload.optString("error")), true);
			return;
		}
		String preview = payload.optString("preview", payload.optString("result", payload.toString()));
		if (payload.optBoolean("hasResult", false)) {
			String result = payload.optString("result", preview);
			showInspectorScriptResultDialog(result, payload.optString("type", ""));
			return;
		}
		if (inspectorResultView != null) {
			inspectorResultView.setVisibility(View.GONE);
		}
		toast(R.string.in_game_overlay_inspector_script_completed);
	}

	private void showInspectorScriptResultDialog(String result, String type) {
		Context dialogContext = materialDialogContext();
		TextView content = new TextView(dialogContext);
		content.setText(result == null ? "" : result);
		content.setTextColor(COLOR_TEXT);
		content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
		content.setTypeface(Typeface.MONOSPACE);
		content.setTextIsSelectable(true);
		content.setPadding(dp(14), dp(12), dp(14), dp(12));
		content.setBackground(roundedBackground(Color.parseColor("#CC111820"), dp(12)));

		ScrollView scroll = new ScrollView(dialogContext);
		scroll.setFillViewport(true);
		scroll.addView(content, new ScrollView.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		int maxHeight = Math.max(dp(120), Math.min(dp(320), Math.round(
			activity.getResources().getDisplayMetrics().heightPixels * 0.48f)));
		scroll.setLayoutParams(new ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));

		String title = activity.getString(R.string.in_game_overlay_inspector_script_result);
		if (!TextUtils.isEmpty(type)) {
			title += " · " + simpleTypeName(type);
		}
		String copyValue = result == null ? "" : result;
		new com.google.android.material.dialog.MaterialAlertDialogBuilder(dialogContext)
			.setTitle(title)
			.setView(scroll)
			.setNegativeButton(R.string.in_game_overlay_inspector_close, null)
			.setPositiveButton(R.string.in_game_overlay_inspector_copy, (dialog, which) ->
				copyInspectorText(activity.getString(R.string.in_game_overlay_inspector_script_result), copyValue))
			.show();
	}

	private void setInspectorResult(String message, boolean error) {
		if (inspectorResultView == null) {
			toast(message);
			return;
		}
		inspectorResultView.setVisibility(View.VISIBLE);
		inspectorResultView.setText(message == null ? "" : message);
		inspectorResultView.setTextColor(error ? COLOR_DANGER : COLOR_ACCENT);
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
			ContextSummary summary;
			try {
				if (json == null || json.trim().isEmpty()) {
					summary = new ContextSummary(
						activity.getString(R.string.in_game_overlay_context_missing), "?", "?", "?");
				} else {
					JSONObject obj = new JSONObject(json);
					String profileId = firstNonEmpty(obj, "profile_id", "selected_profile_id", "instance_id", "selected_instance_id");
					String profileLabel = firstNonEmpty(obj, "display_name", "profile_label");
					String payload = firstNonEmpty(obj, "payload_version", "payload_label", "payload_id", "game_version", "selected_game_version_id");
					String compatTarget = firstNonEmpty(obj, "compat_target_id", "selected_compat_target_id");
					String compatPack = obj.optString("compat_pack_id", "?");
					summary = new ContextSummary(
						activity.getString(R.string.in_game_overlay_game_info_subtitle),
						displayIdWithLabel(profileId, profileLabel),
						emptyToQuestion(payload),
						emptyToQuestion(compatPack) + "  ·  " + (TextUtils.isEmpty(compatTarget) ? "—" : compatTarget));
				}
			} catch (Exception exception) {
				summary = new ContextSummary(
					activity.getString(R.string.in_game_overlay_context_invalid), "?", "?", "?");
			}
			ContextSummary finalSummary = summary;
			mainHandler.post(() -> {
				if (contextStatusView != null) {
					contextStatusView.setText(finalSummary.status);
				}
				if (contextProfileValue != null) {
					contextProfileValue.setText(finalSummary.profile);
				}
				if (contextPayloadValue != null) {
					contextPayloadValue.setText(finalSummary.payload);
				}
				if (contextCompatValue != null) {
					contextCompatValue.setText(finalSummary.compat);
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

	private GradientDrawable outlinedRoundedBackground(int color, int strokeColor, int radiusPx, int strokeWidthPx) {
		GradientDrawable background = roundedBackground(color, radiusPx);
		background.setStroke(strokeWidthPx, strokeColor);
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

	private String simpleTypeName(String type) {
		if (TextUtils.isEmpty(type)) {
			return "";
		}
		String cleaned = type.replace("Godot.", "").replace("MegaCrit.Sts2.", "");
		int generic = cleaned.indexOf('`');
		if (generic >= 0) {
			cleaned = cleaned.substring(0, generic);
		}
		int dot = cleaned.lastIndexOf('.');
		int nested = cleaned.lastIndexOf('+');
		int cut = Math.max(dot, nested);
		return cut >= 0 && cut + 1 < cleaned.length() ? cleaned.substring(cut + 1) : cleaned;
	}

	private int inspectorKindColor(String kind) {
		if ("node".equals(kind)) {
			return COLOR_ACCENT;
		}
		if ("field".equals(kind)) {
			return Color.parseColor("#FFB7C8FF");
		}
		if ("entry".equals(kind)) {
			return Color.parseColor("#FFFFD166");
		}
		if ("element".equals(kind)) {
			return Color.parseColor("#FFA5D6A7");
		}
		if ("info".equals(kind)) {
			return COLOR_MUTED;
		}
		return Color.parseColor("#FFD6E2FF");
	}

	private String inspectorKindLabel(String kind) {
		if ("node".equals(kind)) {
			return "NODE";
		}
		if ("field".equals(kind)) {
			return "FIELD";
		}
		if ("entry".equals(kind)) {
			return "KEY";
		}
		if ("element".equals(kind)) {
			return "ITEM";
		}
		if ("info".equals(kind)) {
			return "INFO";
		}
		return "PROP";
	}

	private int inspectorValueColor(String preview, String type) {
		if (preview == null) {
			return COLOR_MUTED;
		}
		String value = preview.trim();
		if (value.startsWith("!")) {
			return COLOR_DANGER;
		}
		if ("null".equals(value) || value.isEmpty()) {
			return COLOR_MUTED;
		}
		String lowerType = type == null ? "" : type.toLowerCase(Locale.ROOT);
		if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value) || lowerType.contains("bool")) {
			return Color.parseColor("#FFFFD166");
		}
		if (lowerType.contains("string") || value.startsWith("\"") || value.startsWith("res://") || value.startsWith("/")) {
			return Color.parseColor("#FFB7F7C1");
		}
		if (lowerType.contains("int") || lowerType.contains("float") || lowerType.contains("double") || value.matches("[-+]?\\d+(\\.\\d+)?")) {
			return Color.parseColor("#FF8AB4F8");
		}
		return COLOR_TEXT;
	}

	private int inspectorTreeDepthColor(int depth, boolean expanded) {
		int[] colors = {
			Color.parseColor("#D02A3441"),
			Color.parseColor("#C924303C"),
			Color.parseColor("#C91E2B36"),
			Color.parseColor("#C9272938"),
			Color.parseColor("#C9202C31"),
		};
		int color = colors[Math.floorMod(depth, colors.length)];
		if (!expanded) {
			return color;
		}
		return Color.rgb(
			Math.min(255, Color.red(color) + 12),
			Math.min(255, Color.green(color) + 12),
			Math.min(255, Color.blue(color) + 12));
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

	private static final class ContextSummary {
		final String status;
		final String profile;
		final String payload;
		final String compat;

		ContextSummary(String status, String profile, String payload, String compat) {
			this.status = status;
			this.profile = profile;
			this.payload = payload;
			this.compat = compat;
		}
	}

	private final class InspectorTreeGuideView extends View {
		private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint buttonFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint buttonStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private int depth;
		private boolean lastSibling;
		private boolean hasChildren;
		private boolean expanded;
		private boolean[] ancestorContinuations = new boolean[0];

		InspectorTreeGuideView(Context context) {
			super(context);
			setWillNotDraw(false);
			setClickable(true);
			setFocusable(true);
			linePaint.setStyle(Paint.Style.STROKE);
			linePaint.setStrokeWidth(Math.max(1f, dp(1)));
			linePaint.setColor(Color.parseColor("#788A9AAA"));
			buttonFillPaint.setStyle(Paint.Style.FILL);
			buttonFillPaint.setColor(Color.parseColor("#553D4E60"));
			buttonStrokePaint.setStyle(Paint.Style.STROKE);
			buttonStrokePaint.setStrokeWidth(Math.max(1f, dp(1)));
			buttonStrokePaint.setColor(Color.parseColor("#B18FA4B8"));
		}

		void bind(int depth, boolean lastSibling, boolean hasChildren, boolean expanded, boolean[] ancestorContinuations) {
			this.depth = Math.max(0, depth);
			this.lastSibling = lastSibling;
			this.hasChildren = hasChildren;
			this.expanded = expanded;
			this.ancestorContinuations = ancestorContinuations == null
				? new boolean[0]
				: ancestorContinuations.clone();
			invalidate();
		}

		@Override
		protected void onDraw(Canvas canvas) {
			super.onDraw(canvas);
			float step = dp(INSPECTOR_TREE_INDENT_DP);
			float currentX = dp(8) + depth * step;
			float centerY = getHeight() / 2f;
			float halfButton = dp(6);

			for (int level = 0; level < depth; level++) {
				if (level < ancestorContinuations.length && ancestorContinuations[level]) {
					float x = dp(8) + level * step;
					canvas.drawLine(x, 0, x, getHeight(), linePaint);
				}
			}

			if (depth > 0) {
				canvas.drawLine(currentX, 0, currentX, centerY - halfButton, linePaint);
				if (!lastSibling) {
					canvas.drawLine(currentX, centerY + halfButton, currentX, getHeight(), linePaint);
				}
			}

			if (hasChildren) {
				canvas.drawRoundRect(
					currentX - halfButton, centerY - halfButton,
					currentX + halfButton, centerY + halfButton,
					dp(2), dp(2), buttonFillPaint);
				canvas.drawRoundRect(
					currentX - halfButton, centerY - halfButton,
					currentX + halfButton, centerY + halfButton,
					dp(2), dp(2), buttonStrokePaint);
				canvas.drawLine(currentX - dp(3), centerY, currentX + dp(3), centerY, linePaint);
				if (!expanded) {
					canvas.drawLine(currentX, centerY - dp(3), currentX, centerY + dp(3), linePaint);
				}
			}
			canvas.drawLine(
				currentX + (hasChildren ? halfButton : 0), centerY,
				getWidth(), centerY, linePaint);
			if (hasChildren && expanded) {
				float childX = currentX + step;
				canvas.drawLine(childX, centerY, childX, getHeight(), linePaint);
			}
		}
	}

	private final class InspectorItemAdapter extends RecyclerView.Adapter<InspectorItemViewHolder> {
		private final List<JSONObject> items = new ArrayList<>();

		void submit(List<JSONObject> nextItems) {
			items.clear();
			if (nextItems != null) {
				items.addAll(nextItems);
			}
			notifyDataSetChanged();
		}

		@Override
		public InspectorItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			LinearLayout row = new LinearLayout(activity);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setMinimumHeight(dp(50));
			row.setPadding(dp(6), dp(2), dp(4), dp(2));
			row.setBackground(pressableBackground(Color.parseColor("#B31A222C"), dp(10)));
			RecyclerView.LayoutParams rowLp = new RecyclerView.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			rowLp.bottomMargin = dp(4);
			row.setLayoutParams(rowLp);

			TextView badge = text("", 9, true);
			badge.setGravity(Gravity.CENTER);
			badge.setIncludeFontPadding(false);
			badge.setPadding(dp(5), dp(3), dp(5), dp(3));
			LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT);
			row.addView(badge, badgeLp);

			InspectorTreeGuideView treeGuide = new InspectorTreeGuideView(activity);
			row.addView(treeGuide, new LinearLayout.LayoutParams(
				dp(INSPECTOR_TREE_GUIDE_BASE_DP), dp(50)));

			LinearLayout content = new LinearLayout(activity);
			content.setOrientation(LinearLayout.VERTICAL);
			content.setPadding(dp(8), 0, dp(4), 0);
			LinearLayout top = new LinearLayout(activity);
			top.setOrientation(LinearLayout.HORIZONTAL);
			top.setGravity(Gravity.CENTER_VERTICAL);
			TextView name = text("", 13, true);
			name.setSingleLine(true);
			name.setEllipsize(TextUtils.TruncateAt.END);
			TextView type = text("", 10, false);
			type.setSingleLine(true);
			type.setEllipsize(TextUtils.TruncateAt.END);
			type.setTextColor(COLOR_ACCENT);
			LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			top.addView(name, nameLp);
			LinearLayout.LayoutParams typeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			typeLp.setMarginStart(dp(8));
			top.addView(type, typeLp);
			TextView preview = text("", 11, false);
			preview.setSingleLine(true);
			preview.setEllipsize(TextUtils.TruncateAt.END);
			preview.setTypeface(Typeface.MONOSPACE);
			preview.setIncludeFontPadding(false);
			content.addView(top);
			content.addView(preview);
			row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

			ImageView icon = new ImageView(activity);
			icon.setPadding(dp(12), dp(12), dp(12), dp(12));
			icon.setClickable(true);
			icon.setFocusable(true);
			row.addView(icon, new LinearLayout.LayoutParams(dp(MIN_TOUCH_TARGET_DP), dp(MIN_TOUCH_TARGET_DP)));
			return new InspectorItemViewHolder(row, badge, treeGuide, content, name, type, preview, icon);
		}

		@Override
		public void onBindViewHolder(InspectorItemViewHolder holder, int position) {
			JSONObject item = items.get(position);
			String kind = item.optString("kind", item.has("id") ? "root" : "property");
			String name = item.optString("name", item.optString("id", "?"));
			String type = item.optString("type", "");
			String preview = item.optString("preview", "");
			boolean canEdit = item.optBoolean("canEdit", false) && repository.isDevInspectorWritable();
			boolean isTreeNode = "godot_tree".equals(inspectorKind) && "node".equals(kind);
			boolean hasChildren = isTreeNode && item.optBoolean("hasChildren", false);
			boolean expanded = hasChildren && inspectorExpandedNodes.contains(item.optString("path", ""));
			int kindColor = inspectorKindColor(kind);
			if (isTreeNode) {
				int depth = item.optInt("depth", 0);
				int background = inspectorTreeDepthColor(depth, expanded);
				RecyclerView.LayoutParams rowParams = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
				rowParams.bottomMargin = 0;
				holder.itemView.setLayoutParams(rowParams);
				holder.badge.setVisibility(View.GONE);
				holder.treeGuide.setVisibility(View.VISIBLE);
				ViewGroup.LayoutParams guideParams = holder.treeGuide.getLayoutParams();
				guideParams.width = inspectorTreeGuideWidth(depth);
				guideParams.height = dp(50);
				holder.treeGuide.setLayoutParams(guideParams);
				holder.treeGuide.bind(
					depth,
					item.optBoolean("lastSibling", true),
					hasChildren,
					expanded,
					inspectorTreeAncestorContinuations(item));
				holder.treeGuide.setContentDescription(activity.getString(
					expanded ? R.string.in_game_overlay_inspector_collapse_node : R.string.in_game_overlay_inspector_expand_node));
				holder.treeGuide.setOnClickListener(v -> toggleInspectorTreeNode(item));
				holder.itemView.setBackground(pressableBackground(background, dp(10)));
				holder.content.setPadding(dp(8), 0, dp(8), 0);
				holder.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
				holder.name.setText(name);
				holder.name.setTextColor(COLOR_TEXT);
				holder.name.setEllipsize(null);
				holder.type.setVisibility(View.GONE);
				holder.preview.setText(displayInspectorNodeType(type));
				holder.preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
				holder.preview.setTypeface(Typeface.DEFAULT);
				holder.preview.setEllipsize(null);
				holder.preview.setTextColor(inspectorNodeTypeColor(type, background));
				holder.icon.setVisibility(View.GONE);
				holder.icon.setOnClickListener(null);
				holder.itemView.setContentDescription(name + " · " + displayInspectorNodeType(type));
				holder.itemView.setOnClickListener(v -> navigateInspectorItem(item));
				return;
			}

			holder.badge.setVisibility(View.VISIBLE);
			RecyclerView.LayoutParams rowParams = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
			rowParams.bottomMargin = dp(4);
			holder.itemView.setLayoutParams(rowParams);
			holder.treeGuide.setVisibility(View.GONE);
			holder.treeGuide.setOnClickListener(null);
			holder.itemView.setBackground(pressableBackground(Color.parseColor("#B31A222C"), dp(10)));
			holder.content.setPadding(dp(6), 0, dp(4), 0);
			holder.badge.setText(inspectorKindLabel(kind));
			holder.badge.setTextColor(kindColor);
			holder.badge.setBackground(roundedBackground(Color.argb(42, Color.red(kindColor), Color.green(kindColor), Color.blue(kindColor)), dp(6)));
			holder.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
			holder.name.setText(name);
			holder.name.setTextColor(COLOR_TEXT);
			holder.name.setEllipsize(TextUtils.TruncateAt.END);
			holder.type.setVisibility(View.VISIBLE);
			holder.type.setText(simpleTypeName(type));
			holder.type.setTextColor(kindColor);
			holder.preview.setText(preview);
			holder.preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
			holder.preview.setTypeface(Typeface.MONOSPACE);
			holder.preview.setEllipsize(TextUtils.TruncateAt.END);
			holder.preview.setTextColor(inspectorValueColor(preview, type));
			if (canEdit) {
				holder.icon.setVisibility(View.VISIBLE);
				holder.icon.setContentDescription(activity.getString(R.string.in_game_overlay_inspector_edit_title));
				holder.icon.setImageDrawable(MaterialSymbols.drawable(activity, "edit", COLOR_ACCENT, 20));
				holder.icon.setBackground(pressableBackground(Color.TRANSPARENT, dp(14)));
				holder.icon.setOnClickListener(v -> onInspectorItemClicked(item));
			} else {
				holder.icon.setVisibility(View.VISIBLE);
				holder.icon.setContentDescription(activity.getString(R.string.in_game_overlay_inspector_copy));
				holder.icon.setImageDrawable(MaterialSymbols.drawable(activity, "content_copy", COLOR_MUTED, 20));
				holder.icon.setBackground(pressableBackground(Color.TRANSPARENT, dp(14)));
				holder.icon.setOnClickListener(v -> copyInspectorItem(item));
			}
			holder.itemView.setOnClickListener(v -> onInspectorItemClicked(item));
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}

	private static final class InspectorItemViewHolder extends RecyclerView.ViewHolder {
		final TextView badge;
		final InspectorTreeGuideView treeGuide;
		final LinearLayout content;
		final TextView name;
		final TextView type;
		final TextView preview;
		final ImageView icon;

		InspectorItemViewHolder(View itemView, TextView badge, InspectorTreeGuideView treeGuide, LinearLayout content, TextView name, TextView type, TextView preview, ImageView icon) {
			super(itemView);
			this.badge = badge;
			this.treeGuide = treeGuide;
			this.content = content;
			this.name = name;
			this.type = type;
			this.preview = preview;
			this.icon = icon;
		}
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
