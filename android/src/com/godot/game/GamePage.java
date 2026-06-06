package com.godot.game;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.shape.ShapeAppearanceModel;

import com.godot.game.steam.auth.SteamAuthStore;

import org.json.JSONObject;

import java.util.List;

public final class GamePage {
	private static final int COLOR_BACKGROUND = Color.rgb(17, 20, 17);
	private static final int COLOR_SURFACE = Color.rgb(30, 35, 31);
	private static final int COLOR_SURFACE_CONTAINER = Color.rgb(40, 47, 42);
	private static final int COLOR_SURFACE_CONTAINER_HIGH = Color.rgb(50, 58, 52);
	private static final int COLOR_PRIMARY = Color.rgb(129, 217, 154);
	private static final int COLOR_ON_PRIMARY = Color.rgb(0, 57, 26);
	private static final int COLOR_PRIMARY_CONTAINER = Color.rgb(0, 82, 40);
	private static final int COLOR_ON_PRIMARY_CONTAINER = Color.rgb(156, 246, 180);
	private static final int COLOR_SECONDARY_CONTAINER = Color.rgb(51, 75, 59);
	private static final int COLOR_ON_SECONDARY_CONTAINER = Color.rgb(207, 233, 214);
	private static final int COLOR_ON_SURFACE = Color.rgb(225, 227, 223);
	private static final int COLOR_ON_SURFACE_VARIANT = Color.rgb(193, 201, 193);
	private static final int COLOR_RIPPLE = Color.argb(76, 129, 217, 154);
	private static final PathInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);

	private final Context context;
	private final ExtraSettingsRepository repository;
	private final PayloadManager payloadManager;
	private final ExtraSettingsActions actions;

	public GamePage(Context context, ExtraSettingsRepository repository, ExtraSettingsActions actions) {
		this.context = context;
		this.repository = repository;
		this.payloadManager = new PayloadManager(context);
		this.actions = actions;
	}

	public View build() {
		LinearLayout shell = ExtraSettingsUi.vertical(context);
		shell.setBackgroundColor(COLOR_BACKGROUND);

		shell.addView(buildTopAppBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		ScrollView scrollView = new ScrollView(context);
		scrollView.setFillViewport(false);
		scrollView.setClipToPadding(false);
		scrollView.setBackgroundColor(COLOR_BACKGROUND);

		LinearLayout content = ExtraSettingsUi.vertical(context);
		content.setClipToPadding(false);
		int horizontalPadding = dp(20);
		content.setPadding(horizontalPadding, 0, horizontalPadding, dp(32));
		scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		shell.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		try {
			DashboardState state = loadDashboardState();
			content.addView(buildHeroCard(state), matchWrapParams(0));
			content.addView(buildStatusRow(state), matchWrapParams(20));
			content.addView(sectionTitle(R.string.game_maintenance_section), matchWrapParams(20));
			content.addView(buildActionRow(new ActionSpec[] {
				new ActionSpec("unarchive", R.string.game_action_import_body, !state.payloadReady, v -> actions.requestImportGamePayload()),
				new ActionSpec("file_download", R.string.import_save, false, v -> actions.requestImportSave()),
				new ActionSpec("file_upload", R.string.export_save, false, v -> actions.requestExportSave()),
				new ActionSpec("lock_open", R.string.game_action_unlock_all, false, v -> confirmUnlockAll())
			}), matchWrapParams(12));
			content.addView(sectionTitle(R.string.game_advanced_tools_section), matchWrapParams(20));
			content.addView(buildActionRow(new ActionSpec[] {
				new ActionSpec("folder_open", R.string.game_action_browse_files, false, v -> actions.openFileBrowser()),
				new ActionSpec("receipt_long", R.string.view_logs, false, v -> actions.openLogViewer()),
				new ActionSpec("gamepad", R.string.game_action_launch_profiles, false, v -> {
					GameVersionManagerPage.selectProfilesTab();
					actions.openVersionsTab();
				}),
				new ActionSpec("settings", R.string.tab_settings, false, v -> actions.openSettingsTab())
			}), matchWrapParams(12));
		} catch (Exception exception) {
			content.addView(buildErrorCard(exception), matchWrapParams(0));
		}

		return shell;
	}

	private View buildTopAppBar() {
		LinearLayout bar = ExtraSettingsUi.horizontal(context);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setPadding(dp(20), dp(24), dp(20), dp(16));

		TextView title = text(context.getString(R.string.game_home_title), 28, COLOR_ON_SURFACE, Typeface.BOLD);
		title.setLetterSpacing(0.018f);
		title.setSingleLine(true);
		title.setEllipsize(TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		bar.addView(title, titleParams);
		bar.addView(buildSteamChip());
		return bar;
	}

	private View buildSteamChip() {
		SteamAuthStore.AuthSnapshot steam = SteamAuthStore.readSnapshot(context);
		boolean loggedIn = steam != null && steam.refreshTokenConfigured;
		int backgroundColor = loggedIn ? COLOR_SECONDARY_CONTAINER : COLOR_SURFACE_CONTAINER_HIGH;
		int contentColor = loggedIn ? COLOR_ON_SECONDARY_CONTAINER : COLOR_ON_SURFACE_VARIANT;
		String iconGlyph = loggedIn ? "cloud_done" : "login";
		String label = loggedIn && !TextUtils.isEmpty(steam.accountName)
			? steam.accountName
			: (loggedIn ? context.getString(R.string.game_steam_logged_in_fallback) : context.getString(R.string.game_steam_login));

		LinearLayout chip = ExtraSettingsUi.horizontal(context);
		chip.setGravity(Gravity.CENTER);
		chip.setPadding(dp(14), dp(6), dp(14), dp(6));
		chip.setMinimumHeight(dp(32));
		chip.setClickable(true);
		chip.setFocusable(true);
		chip.setBackground(roundedFill(backgroundColor, 100));
		chip.setForeground(rippleForeground(100));
		chip.setOnClickListener(v -> actions.openSteamAccount());

		ImageView icon = icon(iconGlyph, contentColor, 18);
		chip.addView(icon);
		TextView text = text(label, 13, contentColor, Typeface.BOLD);
		text.setSingleLine(true);
		text.setEllipsize(TextUtils.TruncateAt.END);
		text.setMaxWidth(dp(128));
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		textParams.setMarginStart(dp(6));
		chip.addView(text, textParams);
		return chip;
	}

	private DashboardState loadDashboardState() throws Exception {
		PayloadManager.Status payloadStatus = payloadManager.getStatus();
		JSONObject settings = repository.loadSettingsJson();
		List<ExtraSettingsRepository.ModEntry> mods = repository.listInstalledModManifests();
		int enabledMods = repository.getEnabledModCount(settings, mods);
		ExtraSettingsRepository.SaveStatus saveStatus = repository.getSaveStatus();
		String renderer = RendererPreference.RENDERER_OPENGL_ES3.equals(RendererPreference.getSelectedRenderer(context))
			? "OpenGL ES 3.0"
			: context.getString(R.string.renderer_option_vulkan);
		return new DashboardState(payloadStatus, renderer, mods.size(), enabledMods, saveStatus.normalProfiles + saveStatus.moddedProfiles, saveStatus.formattedBytes);
	}

	private View buildHeroCard(DashboardState state) {
		FrameLayout card = state.payloadReady ? new AnimatedHeroCard(context) : new FrameLayout(context);
		if (!state.payloadReady) {
			card.setBackground(emptyHeroBackground());
			card.setClipToOutline(true);
		}

		LinearLayout content = ExtraSettingsUi.vertical(context);
		content.setPadding(dp(24), dp(24), dp(24), dp(24));
		card.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		TextView version = text(
			state.payloadReady ? context.getString(R.string.game_hero_ready_title_format, state.payloadStatus.shortVersionLabel()) : context.getString(R.string.game_hero_missing_title),
			18,
			state.payloadReady ? COLOR_ON_PRIMARY_CONTAINER : COLOR_ON_SURFACE,
			Typeface.BOLD
		);
		version.setSingleLine(true);
		version.setEllipsize(TextUtils.TruncateAt.END);
		content.addView(version);

		LinearLayout sub = ExtraSettingsUi.horizontal(context);
		sub.setGravity(Gravity.CENTER_VERTICAL);
		ImageView subIcon = icon(state.payloadReady ? "check_circle" : "error", state.payloadReady ? Color.argb(179, 255, 255, 255) : COLOR_ON_SURFACE_VARIANT, 16);
		sub.addView(subIcon);
		String subText = state.payloadReady
			? Formatter.formatFileSize(context, state.payloadStatus.totalBytes) + " · " + state.renderer
			: context.getString(R.string.game_hero_missing_subtitle);
		TextView subLabel = text(subText, 13, state.payloadReady ? Color.argb(179, 255, 255, 255) : COLOR_ON_SURFACE_VARIANT, Typeface.NORMAL);
		subLabel.setSingleLine(true);
		subLabel.setEllipsize(TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams subTextParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		subTextParams.setMarginStart(dp(6));
		sub.addView(subLabel, subTextParams);
		content.addView(sub, matchWrapParams(6));

		MaterialButton launchButton = launchButton(state.payloadReady);
		LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
		buttonParams.topMargin = dp(24);
		content.addView(launchButton, buttonParams);
		return card;
	}

	private MaterialButton launchButton(boolean ready) {
		MaterialButton button = new MaterialButton(context);
		button.setAllCaps(false);
		button.setText(ready ? R.string.launch_game : R.string.game_launch_disabled);
		button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
		button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		button.setGravity(Gravity.CENTER);
		button.setMinWidth(0);
		button.setMinHeight(0);
		button.setMinimumHeight(0);
		button.setInsetTop(0);
		button.setInsetBottom(0);
		button.setPadding(dp(16), 0, dp(16), 0);
		MaterialSymbols.applyButtonIcon(button, ready ? "play_arrow" : "block", ColorStateList.valueOf(ready ? COLOR_ON_PRIMARY : Color.argb(77, 255, 255, 255)), 24);
		button.setIconPadding(dp(8));
		button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
		button.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(dp(28)).build());
		if (ready) {
			button.setTextColor(COLOR_ON_PRIMARY);
			MaterialSymbols.applyButtonIcon(button, "play_arrow", ColorStateList.valueOf(COLOR_ON_PRIMARY), 24);
			button.setBackgroundTintList(ColorStateList.valueOf(COLOR_PRIMARY));
			button.setRippleColor(ColorStateList.valueOf(Color.argb(96, 255, 255, 255)));
			button.setOnTouchListener(new PressScaleTouchListener(0.97f, 100));
			button.setOnClickListener(v -> actions.launchGame());
		} else {
			int disabledColor = Color.argb(20, 255, 255, 255);
			int disabledContent = Color.argb(77, 255, 255, 255);
			button.setTextColor(disabledContent);
			MaterialSymbols.applyButtonIcon(button, "block", ColorStateList.valueOf(disabledContent), 24);
			button.setBackgroundTintList(ColorStateList.valueOf(disabledColor));
			button.setRippleColor(ColorStateList.valueOf(Color.argb(36, 255, 255, 255)));
			button.setOnClickListener(v -> actions.showMessage(context.getString(R.string.game_launch_disabled_message)));
		}
		return button;
	}

	private View buildStatusRow(DashboardState state) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setBaselineAligned(false);
		row.setGravity(Gravity.CENTER_VERTICAL);

		String modValue = state.payloadReady ? context.getString(R.string.game_dashboard_mods_value_format, state.enabledMods, state.installedMods) : context.getString(R.string.game_dashboard_mods_empty_value);
		StatusCardView mods = statusCard("extension", R.string.game_dashboard_mods_title, modValue, context.getString(R.string.game_dashboard_mods_subtitle));
		mods.setOnClickListener(v -> actions.openModsTab());

		String saveValue = state.payloadReady ? context.getString(R.string.game_dashboard_saves_value_format, state.saveCount) : context.getString(R.string.game_dashboard_saves_empty_value);
		String saveSub = state.payloadReady ? context.getString(R.string.game_dashboard_saves_size_format, state.saveBytes) : context.getString(R.string.game_dashboard_saves_empty_subtitle);
		StatusCardView saves = statusCard("save", R.string.game_dashboard_saves_title, saveValue, saveSub);
		saves.setOnClickListener(v -> actions.openSaveSettingsTab());

		LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		right.setMarginStart(dp(12));
		row.addView(mods, left);
		row.addView(saves, right);
		return row;
	}

	private StatusCardView statusCard(String iconGlyph, int titleRes, String value, String subText) {
		StatusCardView card = new StatusCardView(context);
		card.setClickable(true);
		card.setFocusable(true);
		card.setForeground(rippleForeground(16));

		ImageView watermark = icon(iconGlyph, COLOR_PRIMARY, 135);
		watermark.setAlpha(0.06f);
		watermark.setRotation(-15f);
		FrameLayout.LayoutParams watermarkParams = new FrameLayout.LayoutParams(dp(135), dp(135), Gravity.BOTTOM | Gravity.END);
		watermarkParams.setMargins(0, 0, -dp(15), -dp(23));
		card.setWatermark(watermark);
		card.addView(watermark, watermarkParams);

		LinearLayout content = ExtraSettingsUi.vertical(context);
		content.setPadding(dp(16), dp(16), dp(16), dp(16));
		card.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		LinearLayout header = ExtraSettingsUi.horizontal(context);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.addView(statusIconBadge(iconGlyph));
		TextView title = text(context.getString(titleRes), 13, COLOR_ON_SURFACE_VARIANT, Typeface.NORMAL);
		title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
		LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		titleParams.setMarginStart(dp(8));
		header.addView(title, titleParams);
		content.addView(header);

		LinearLayout values = ExtraSettingsUi.vertical(context);
		TextView valueView = text(value, 22, COLOR_ON_SURFACE, Typeface.BOLD);
		TextView subView = text(subText, 11, COLOR_ON_SURFACE_VARIANT, Typeface.NORMAL);
		values.addView(valueView);
		values.addView(subView, matchWrapParams(4));
		content.addView(values, matchWrapParams(10));
		return card;
	}

	private View statusIconBadge(String iconGlyph) {
		FrameLayout badge = new FrameLayout(context);
		badge.setBackground(roundedFill(Color.argb(31, 129, 217, 154), 100));
		ImageView icon = icon(iconGlyph, COLOR_PRIMARY, 16);
		badge.addView(icon, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
		badge.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
		return badge;
	}

	private TextView sectionTitle(int stringRes) {
		return text(context.getString(stringRes), 16, COLOR_ON_SURFACE, Typeface.BOLD);
	}

	private View buildActionRow(ActionSpec[] specs) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setBaselineAligned(false);
		row.setGravity(Gravity.CENTER_VERTICAL);
		for (int i = 0; i < specs.length; i++) {
			ActionSpec spec = specs[i];
			View item = actionItem(spec);
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			if (i > 0) {
				params.setMarginStart(dp(12));
			}
			row.addView(item, params);
		}
		return row;
	}

	private View actionItem(ActionSpec spec) {
		ActionItemView item = new ActionItemView(context, spec.highlight);
		item.setClickable(true);
		item.setFocusable(true);
		item.setForeground(rippleForeground(16));
		item.setOnClickListener(spec.listener);

		LinearLayout content = ExtraSettingsUi.vertical(context);
		content.setGravity(Gravity.CENTER);
		content.setPadding(dp(4), dp(14), dp(4), dp(14));
		ImageView icon = icon(spec.iconGlyph, COLOR_PRIMARY, 26);
		content.addView(icon);
		TextView label = text(context.getString(spec.labelRes), 12, COLOR_ON_SURFACE, Typeface.NORMAL);
		label.setGravity(Gravity.CENTER);
		label.setMaxLines(2);
		LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		labelParams.topMargin = dp(8);
		content.addView(label, labelParams);
		item.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return item;
	}

	private View buildErrorCard(Exception exception) {
		FrameLayout card = new FrameLayout(context);
		card.setBackground(roundedStrokeFill(COLOR_SURFACE_CONTAINER, Color.argb(26, 255, 255, 255), 1, 24));
		card.setPadding(dp(18), dp(18), dp(18), dp(18));
		LinearLayout content = ExtraSettingsUi.vertical(context);
		card.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(icon("error", ExtraSettingsUi.COLOR_ERROR, 24));
		TextView message = text(exception.getMessage() == null ? exception.toString() : exception.getMessage(), 14, COLOR_ON_SURFACE_VARIANT, Typeface.NORMAL);
		LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		messageParams.setMarginStart(dp(12));
		row.addView(message, messageParams);
		content.addView(row);
		return card;
	}

	private void confirmUnlockAll() {
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.confirm_unlock_all_title)
			.setMessage(R.string.confirm_unlock_all_message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> actions.runAsyncOperation(context.getString(R.string.unlock_all), () -> {
				repository.queueUnlockAll();
				return context.getString(R.string.status_unlock_all_queued);
			}))
			.show();
	}

	private LinearLayout.LayoutParams matchWrapParams(int topMarginDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = dp(topMarginDp);
		return params;
	}

	private GradientDrawable roundedFill(int color, float radiusDp) {
		GradientDrawable drawable = new GradientDrawable();
		drawable.setShape(GradientDrawable.RECTANGLE);
		drawable.setColor(color);
		drawable.setCornerRadius(dp(radiusDp));
		return drawable;
	}

	private GradientDrawable roundedStrokeFill(int color, int strokeColor, float strokeWidthDp, float radiusDp) {
		GradientDrawable drawable = roundedFill(color, radiusDp);
		drawable.setStroke(dp(strokeWidthDp), strokeColor);
		return drawable;
	}

	private GradientDrawable emptyHeroBackground() {
		GradientDrawable drawable = roundedFill(COLOR_SURFACE_CONTAINER, 24);
		drawable.setStroke(dp(2), Color.argb(26, 255, 255, 255), dp(8), dp(6));
		return drawable;
	}

	private RippleDrawable rippleForeground(float radiusDp) {
		GradientDrawable mask = roundedFill(Color.WHITE, radiusDp);
		return new RippleDrawable(ColorStateList.valueOf(COLOR_RIPPLE), null, mask);
	}

	private ImageView icon(String glyph, int tint, int sizeDp) {
		ImageView view = new ImageView(context);
		view.setImageDrawable(MaterialSymbols.drawable(context, glyph, tint, sizeDp));
		view.setScaleType(ImageView.ScaleType.CENTER);
		int size = dp(sizeDp);
		view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
		return view;
	}

	private TextView text(String value, float sp, int color, int style) {
		TextView view = new TextView(context);
		view.setText(value);
		view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		view.setTextColor(color);
		view.setTypeface(Typeface.DEFAULT, style);
		view.setIncludeFontPadding(false);
		return view;
	}

	private int dp(float value) {
		return ExtraSettingsUi.dp(context, value);
	}

	private static final class DashboardState {
		final PayloadManager.Status payloadStatus;
		final boolean payloadReady;
		final String renderer;
		final int installedMods;
		final int enabledMods;
		final int saveCount;
		final String saveBytes;

		DashboardState(PayloadManager.Status payloadStatus, String renderer, int installedMods, int enabledMods, int saveCount, String saveBytes) {
			this.payloadStatus = payloadStatus;
			this.payloadReady = payloadStatus.ready;
			this.renderer = renderer;
			this.installedMods = installedMods;
			this.enabledMods = enabledMods;
			this.saveCount = saveCount;
			this.saveBytes = saveBytes;
		}
	}

	private static final class ActionSpec {
		final String iconGlyph;
		final int labelRes;
		final boolean highlight;
		final View.OnClickListener listener;

		ActionSpec(String iconGlyph, int labelRes, boolean highlight, View.OnClickListener listener) {
			this.iconGlyph = iconGlyph;
			this.labelRes = labelRes;
			this.highlight = highlight;
			this.listener = listener;
		}
	}

	private static final class AnimatedHeroCard extends FrameLayout {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint orbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final Path clipPath = new Path();
		private final int radius;
		private long startMs;
		private boolean attached;
		private final Runnable ticker = new Runnable() {
			@Override
			public void run() {
				if (!attached) {
					return;
				}
				invalidate();
				postOnAnimation(this);
			}
		};

		AnimatedHeroCard(Context context) {
			super(context);
			setWillNotDraw(false);
			setLayerType(View.LAYER_TYPE_SOFTWARE, null);
			radius = ExtraSettingsUi.dp(context, 24);
		}

		@Override
		protected void onAttachedToWindow() {
			super.onAttachedToWindow();
			attached = true;
			startMs = SystemClock.uptimeMillis();
			postOnAnimation(ticker);
		}

		@Override
		protected void onDetachedFromWindow() {
			attached = false;
			removeCallbacks(ticker);
			super.onDetachedFromWindow();
		}

		@Override
		protected void onDraw(Canvas canvas) {
			super.onDraw(canvas);
			float width = getWidth();
			float height = getHeight();
			if (width <= 0 || height <= 0) {
				return;
			}
			long elapsed = SystemClock.uptimeMillis() - startMs;
			float gradientCycle = (elapsed % 8000L) / 8000f;
			float flow = (float)(0.5f - 0.5f * Math.cos(Math.PI * 2f * gradientCycle));
			rect.set(0, 0, width, height);
			clipPath.reset();
			clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
			canvas.save();
			canvas.clipPath(clipPath);

			float offset = (flow - 0.5f) * width * 1.4f;
			LinearGradient gradient = new LinearGradient(
				-offset,
				0,
				width * 1.35f - offset,
				height,
				new int[] { COLOR_PRIMARY_CONTAINER, COLOR_SECONDARY_CONTAINER, Color.rgb(0, 109, 54) },
				new float[] { 0f, 0.5f, 1f },
				Shader.TileMode.CLAMP
			);
			paint.setShader(gradient);
			canvas.drawRoundRect(rect, radius, radius, paint);
			paint.setShader(null);

			drawOrb(canvas, width, height, elapsed, true);
			drawOrb(canvas, width, height, elapsed, false);
			canvas.restore();
		}

		private void drawOrb(Canvas canvas, float width, float height, long elapsed, boolean top) {
			long duration = top ? 12000L : 16000L;
			float cycle = (elapsed % duration) / (float)duration;
			float eased = (float)(0.5f - 0.5f * Math.cos(Math.PI * 2f * cycle));
			if (!top) {
				eased = 1f - eased;
			}
			float baseRadius = ExtraSettingsUi.dp(getContext(), top ? 80 : 60);
			float radius = baseRadius * (1f + 0.1f * eased);
			float centerX = top ? width + ExtraSettingsUi.dp(getContext(), 60) - ExtraSettingsUi.dp(getContext(), 20) * eased : ExtraSettingsUi.dp(getContext(), 40) - ExtraSettingsUi.dp(getContext(), 20) * eased;
			float centerY = top ? ExtraSettingsUi.dp(getContext(), 40) + ExtraSettingsUi.dp(getContext(), 20) * eased : height + ExtraSettingsUi.dp(getContext(), 20) + ExtraSettingsUi.dp(getContext(), 20) * eased;
			orbPaint.setColor(top ? Color.argb(38, 129, 217, 154) : Color.argb(26, 156, 246, 180));
			orbPaint.setMaskFilter(new BlurMaskFilter(ExtraSettingsUi.dp(getContext(), top ? 60 : 50), BlurMaskFilter.Blur.NORMAL));
			canvas.drawCircle(centerX, centerY, radius, orbPaint);
			orbPaint.setMaskFilter(null);
		}
	}

	private static final class StatusCardView extends FrameLayout {
		private final GradientDrawable backgroundDrawable;
		private ImageView watermark;

		StatusCardView(Context context) {
			super(context);
			backgroundDrawable = new GradientDrawable();
			backgroundDrawable.setShape(GradientDrawable.RECTANGLE);
			backgroundDrawable.setColor(COLOR_SURFACE_CONTAINER);
			backgroundDrawable.setCornerRadius(ExtraSettingsUi.dp(context, 16));
			setBackground(backgroundDrawable);
			setClipToOutline(true);
			setOnTouchListener((view, event) -> {
				if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
					animatePressed(true);
				} else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
					animatePressed(false);
				}
				return false;
			});
		}

		void setWatermark(ImageView watermark) {
			this.watermark = watermark;
		}

		private void animatePressed(boolean pressed) {
			animate().cancel();
			animate()
				.scaleX(pressed ? 0.95f : 1f)
				.scaleY(pressed ? 0.95f : 1f)
				.setDuration(200)
				.setInterpolator(EMPHASIZED)
				.start();
			backgroundDrawable.setColor(pressed ? COLOR_SURFACE_CONTAINER_HIGH : COLOR_SURFACE_CONTAINER);
			if (watermark != null) {
				watermark.animate().cancel();
				watermark.animate()
					.rotation(pressed ? 0f : -15f)
					.scaleX(pressed ? 1.15f : 1f)
					.scaleY(pressed ? 1.15f : 1f)
					.alpha(pressed ? 0.12f : 0.06f)
					.setDuration(400)
					.setInterpolator(EMPHASIZED)
					.start();
			}
		}
	}

	private static final class ActionItemView extends FrameLayout {
		private final boolean highlight;
		private final GradientDrawable backgroundDrawable;
		private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF pulseRect = new RectF();
		private long startMs;
		private boolean attached;
		private final Runnable ticker = new Runnable() {
			@Override
			public void run() {
				if (!attached || !highlight) {
					return;
				}
				invalidate();
				postOnAnimation(this);
			}
		};

		ActionItemView(Context context, boolean highlight) {
			super(context);
			this.highlight = highlight;
			setWillNotDraw(false);
			backgroundDrawable = new GradientDrawable();
			backgroundDrawable.setShape(GradientDrawable.RECTANGLE);
			backgroundDrawable.setCornerRadius(ExtraSettingsUi.dp(context, 16));
			applyBackground(false);
			setBackground(backgroundDrawable);
			setClipToOutline(true);
			setOnTouchListener((view, event) -> {
				if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
					applyBackground(true);
				} else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
					applyBackground(false);
				}
				return false;
			});
		}

		@Override
		protected void onAttachedToWindow() {
			super.onAttachedToWindow();
			attached = true;
			if (highlight) {
				startMs = SystemClock.uptimeMillis();
				postOnAnimation(ticker);
			}
		}

		@Override
		protected void onDetachedFromWindow() {
			attached = false;
			removeCallbacks(ticker);
			super.onDetachedFromWindow();
		}

		private void applyBackground(boolean pressed) {
			backgroundDrawable.setColor(pressed ? COLOR_SURFACE_CONTAINER : COLOR_SURFACE);
			backgroundDrawable.setStroke(ExtraSettingsUi.dp(getContext(), 1), highlight ? COLOR_PRIMARY : Color.argb(13, 255, 255, 255));
		}

		@Override
		protected void dispatchDraw(Canvas canvas) {
			super.dispatchDraw(canvas);
			if (!highlight) {
				return;
			}
			float progress = ((SystemClock.uptimeMillis() - startMs) % 2000L) / 2000f;
			float stroke = ExtraSettingsUi.dp(getContext(), 1f + progress * 10f);
			int alpha = (int)(102f * (1f - Math.min(progress / 0.7f, 1f)));
			if (alpha <= 0) {
				return;
			}
			pulsePaint.setStyle(Paint.Style.STROKE);
			pulsePaint.setStrokeWidth(stroke);
			pulsePaint.setColor(Color.argb(alpha, 129, 217, 154));
			float inset = stroke / 2f;
			pulseRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
			float radius = ExtraSettingsUi.dp(getContext(), 16);
			canvas.drawRoundRect(pulseRect, radius, radius, pulsePaint);
		}
	}

	private static final class PressScaleTouchListener implements View.OnTouchListener {
		private final float pressedScale;
		private final long durationMs;

		PressScaleTouchListener(float pressedScale, long durationMs) {
			this.pressedScale = pressedScale;
			this.durationMs = durationMs;
		}

		@Override
		public boolean onTouch(View view, MotionEvent event) {
			if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
				view.animate().cancel();
				view.animate().scaleX(pressedScale).scaleY(pressedScale).setDuration(durationMs).start();
			} else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
				view.animate().cancel();
				view.animate().scaleX(1f).scaleY(1f).setDuration(durationMs).start();
			}
			return false;
		}
	}
}
