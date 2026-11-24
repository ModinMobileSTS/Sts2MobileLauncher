package com.godot.game;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
	// Keep payload/Steam available even if both the MOD cache and save-size walk are cold.
	private static final Executor ACTION_READS = Executors.newFixedThreadPool(2, r -> new Thread(r, "sts2-home-actions"));
	private static final Executor STAT_READS = Executors.newFixedThreadPool(2, r -> new Thread(r, "sts2-home-stats"));

	private final Context context;
	private final ExtraSettingsRepository repository;
	private final PayloadManager payloadManager;
	private final ExtraSettingsActions actions;
	private final LaunchProfileManager profiles;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final CardRead<?>[] reads;
	private final Runnable loadCards = this::loadCards;
	private boolean active;
	private boolean refreshPosted;
	private volatile long generation;
	private String profileId;

	private View root;
	private LinearLayout steamChip;
	private TextView steamLabel;
	private ImageView steamIcon;
	private AnimatedHeroCard hero;
	private TextView heroTitle;
	private ImageView heroSubIcon;
	private TextView heroSubtitle;
	private MaterialButton launch;
	private boolean payloadMissing;
	private ActionItemView importAction;
	private StatusCardView modsCard;
	private StatusCardView savesCard;

	public GamePage(Context context, ExtraSettingsRepository repository, ExtraSettingsActions actions) {
		this(context, repository, actions, ACTION_READS, STAT_READS);
	}

	GamePage(Context context, ExtraSettingsRepository repository, ExtraSettingsActions actions, Executor actionReads, Executor statReads) {
		this.context = context;
		this.repository = repository;
		this.payloadManager = new PayloadManager(context);
		this.actions = actions;
		profiles = new LaunchProfileManager(context);
		reads = new CardRead<?>[] {
			new CardRead<>(actionReads, this::readHero, state -> bindHero(state, R.string.unknown)),
			new CardRead<>(actionReads, () -> SteamAuthStore.readSnapshot(context), this::bindSteamChip),
			new CardRead<>(statReads, this::readModCounts, value -> modsCard.value.setText(value != null ? value : context.getString(R.string.unknown))),
			new CardRead<>(statReads, repository::getSaveStatus, this::bindSaveStatus)
		};
	}

	public View build() {
		if (root != null) return root;
		LinearLayout shell = ExtraSettingsUi.vertical(context);
		shell.setBackgroundColor(COLOR_BACKGROUND);

		shell.addView(buildTopAppBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		ScrollView scrollView = new ScrollView(context);
		scrollView.setFillViewport(false);
		scrollView.setClipToPadding(false);
		scrollView.setBackgroundColor(COLOR_BACKGROUND);

		LinearLayout content = ExtraSettingsUi.vertical(context);
		content.setClipToPadding(false);
		content.setPadding(0, 0, 0, dp(32));
		ExtraSettingsUi.addResponsiveScrollContent(context, scrollView, content);
		shell.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		if (ExtraSettingsUi.isWideLayout(context)) {
			populateWideContent(content);
		} else {
			populateCompactContent(content);
		}
		scrollView.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
			hero.updateTicker();
			importAction.updateTicker();
		});
		showProfileLoading();
		root = shell;

		return shell;
	}

	// Called on main after locked directory readiness. The selected ID is an already-loaded
	// preference; resolving a profile (or its roots) here would bootstrap the filesystem again.
	void setPageActive(boolean visible) {
		if (active == visible) return;
		active = visible;
		if (visible) {
			refresh();
		} else {
			++generation;
			main.removeCallbacks(loadCards);
			refreshPosted = false;
		}
		hero.setPageActive(visible);
		importAction.setPageActive(visible);
	}

	void refresh() {
		if (!active) return;
		String selected = profiles.getSelectedProfileId();
		if (!selected.equals(profileId)) {
			if (profileId != null) showProfileLoading();
			profileId = selected;
		}
		++generation;
		if (!refreshPosted) {
			refreshPosted = true;
			main.post(loadCards);
		}
	}

	private void loadCards() {
		refreshPosted = false;
		for (CardRead<?> read : reads) read.start();
	}

	private void showProfileLoading() {
		bindHero(null, R.string.game_hero_checking);
		modsCard.value.setText(R.string.game_dashboard_loading);
		savesCard.value.setText(R.string.game_dashboard_loading);
		savesCard.subtitle.setText("");
	}

	// One outstanding read per card. A burst of refreshes during a slow read requires at
	// most one trailing read of the latest generation; unrelated cards need not wait for it.
	private final class CardRead<T> {
		private final Executor worker;
		private final Callable<T> reader;
		private final Consumer<T> bind;
		private boolean running;

		CardRead(Executor worker, Callable<T> reader, Consumer<T> bind) {
			this.worker = worker;
			this.reader = reader;
			this.bind = bind;
		}

		void start() {
			if (running || !active) return;
			running = true;
			long request = generation;
			worker.execute(() -> {
				T value = null;
				try {
					if (request == generation) value = reader.call();
				} catch (Exception error) {
					Log.w("Sts2Home", "Home card unavailable", error);
				}
				T result = value;
				main.post(() -> complete(request, result));
			});
		}

		private void complete(long request, T result) {
			running = false;
			if (!active) return;
			if (!profileId.equals(profiles.getSelectedProfileId())) {
				refresh();
			} else if (request == generation) {
				bind.accept(result);
			} else if (!refreshPosted) {
				start();
			}
		}
	}

	private void populateCompactContent(LinearLayout content) {
		content.addView(buildHeroCard(), matchWrapParams(0));
		content.addView(buildStatusRow(), matchWrapParams(20));
		content.addView(sectionTitle(R.string.game_maintenance_section), matchWrapParams(20));
		content.addView(buildActionRow(maintenanceActions(), 4), matchWrapParams(12));
		content.addView(sectionTitle(R.string.game_advanced_tools_section), matchWrapParams(20));
		content.addView(buildActionRow(advancedActions(), 4), matchWrapParams(12));
	}

	private void populateWideContent(LinearLayout content) {
		LinearLayout columns = ExtraSettingsUi.horizontal(context);
		columns.setGravity(Gravity.TOP);
		columns.setBaselineAligned(false);
		LinearLayout left = ExtraSettingsUi.vertical(context);
		LinearLayout right = ExtraSettingsUi.vertical(context);
		columns.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));
		LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		rightParams.setMarginStart(dp(20));
		columns.addView(right, rightParams);
		left.addView(buildHeroCard(), matchWrapParams(0));
		right.addView(buildStatusRow(), matchWrapParams(0));
		right.addView(sectionTitle(R.string.game_maintenance_section), matchWrapParams(20));
		right.addView(buildActionRow(maintenanceActions(), 2), matchWrapParams(12));
		right.addView(sectionTitle(R.string.game_advanced_tools_section), matchWrapParams(20));
		right.addView(buildActionRow(advancedActions(), 2), matchWrapParams(12));
		content.addView(columns, matchWrapParams(0));
	}

	private ActionSpec[] maintenanceActions() {
		return new ActionSpec[] {
			new ActionSpec("unarchive", R.string.game_action_import_body, v -> actions.requestImportGamePayload()),
			new ActionSpec("file_download", R.string.import_save, v -> actions.requestImportSave()),
			new ActionSpec("file_upload", R.string.export_save, v -> actions.requestExportSave()),
			new ActionSpec("lock_open", R.string.game_action_unlock_all, v -> confirmUnlockAll())
		};
	}

	private ActionSpec[] advancedActions() {
		return new ActionSpec[] {
			new ActionSpec("folder_open", R.string.game_action_browse_files, v -> actions.openFileBrowser()),
			new ActionSpec("receipt_long", R.string.view_logs, v -> actions.openLogViewer()),
			new ActionSpec("gamepad", R.string.game_action_launch_profiles, v -> {
				GameVersionManagerPage.selectProfilesTab();
				actions.openVersionsTab();
			}),
			new ActionSpec("settings", R.string.tab_settings, v -> actions.openSettingsTab())
		};
	}

	private View buildTopAppBar() {
		LinearLayout bar = ExtraSettingsUi.horizontal(context);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setPadding(dp(20), dp(24), dp(20), dp(16));
		SystemBarInsetsHelper.applySystemBarPadding(bar, true, false, false, false);

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
		steamChip = ExtraSettingsUi.horizontal(context);
		steamChip.setGravity(Gravity.CENTER);
		steamChip.setPadding(dp(14), dp(6), dp(14), dp(6));
		steamChip.setMinimumHeight(dp(32));
		steamChip.setFocusable(true);
		steamChip.setBackground(roundedFill(COLOR_SURFACE_CONTAINER_HIGH, 100));
		steamChip.setForeground(rippleForeground(100));
		steamChip.setOnClickListener(v -> actions.openSteamAccount());
		steamIcon = icon("cloud_sync", COLOR_ON_SURFACE_VARIANT, 18);
		steamChip.addView(steamIcon);
		steamLabel = text(context.getString(R.string.steam_account_title), 13, COLOR_ON_SURFACE_VARIANT, Typeface.BOLD);
		steamLabel.setSingleLine(true);
		steamLabel.setEllipsize(TextUtils.TruncateAt.END);
		steamLabel.setMaxWidth(dp(128));
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.setMarginStart(dp(6));
		steamChip.addView(steamLabel, params);
		return steamChip;
	}

	private void bindSteamChip(SteamAuthStore.AuthSnapshot steam) {
		boolean loggedIn = steam != null && steam.refreshTokenConfigured;
		int tint = loggedIn ? COLOR_ON_SECONDARY_CONTAINER : COLOR_ON_SURFACE_VARIANT;
		((GradientDrawable) steamChip.getBackground()).setColor(loggedIn ? COLOR_SECONDARY_CONTAINER : COLOR_SURFACE_CONTAINER_HIGH);
		steamIcon.setImageDrawable(MaterialSymbols.drawable(context, steam == null ? "cloud_sync" : loggedIn ? "cloud_done" : "login", tint, 18));
		steamLabel.setTextColor(tint);
		steamLabel.setText(loggedIn && !TextUtils.isEmpty(steam.accountName) ? steam.accountName
			: context.getString(steam == null || loggedIn ? R.string.steam_account_title : R.string.game_steam_login));
	}

	private HeroState readHero() {
		PayloadManager.Status status = payloadManager.getStatus();
		if (!status.ready) {
			return new HeroState(false, context.getString(R.string.game_hero_missing_title), context.getString(R.string.game_hero_missing_subtitle));
		}
		String renderer = RendererPreference.RENDERER_OPENGL_ES3.equals(RendererPreference.getSelectedRenderer(context))
			? "OpenGL ES 3.0" : context.getString(R.string.renderer_option_vulkan);
		return new HeroState(true, context.getString(R.string.game_hero_ready_title_format, status.shortVersionLabel()),
			status.totalBytes > 0 ? Formatter.formatFileSize(context, status.totalBytes) + " · " + renderer : renderer);
	}

	private String readModCounts() throws Exception {
		// Unlike loadSettingsJson(), a display read must not persist defaults/migrations:
		// a launch profile switch or an import can race this worker.
		File file = repository.getSettingsFile();
		String json = file.isFile() ? FileBrowserSupport.readTextFile(file) : "";
		JSONObject settings = json.trim().isEmpty() ? new JSONObject() : new JSONObject(json);
		List<ExtraSettingsRepository.ModEntry> mods = repository.listInstalledModManifestsForDisplay();
		return context.getString(R.string.game_dashboard_mods_value_format, repository.getEnabledModCount(settings, mods), mods.size());
	}

	private View buildHeroCard() {
		hero = new AnimatedHeroCard(context);

		LinearLayout content = ExtraSettingsUi.vertical(context);
		content.setPadding(dp(24), dp(24), dp(24), dp(24));
		hero.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		heroTitle = text("", 18, COLOR_ON_SURFACE, Typeface.BOLD);
		heroTitle.setSingleLine(true);
		heroTitle.setEllipsize(TextUtils.TruncateAt.END);
		content.addView(heroTitle);

		LinearLayout sub = ExtraSettingsUi.horizontal(context);
		sub.setGravity(Gravity.CENTER_VERTICAL);
		heroSubIcon = icon("sync", COLOR_ON_SURFACE_VARIANT, 16);
		sub.addView(heroSubIcon);
		heroSubtitle = text("", 13, COLOR_ON_SURFACE_VARIANT, Typeface.NORMAL);
		heroSubtitle.setSingleLine(true);
		heroSubtitle.setEllipsize(TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams subTextParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		subTextParams.setMarginStart(dp(6));
		sub.addView(heroSubtitle, subTextParams);
		content.addView(sub, matchWrapParams(6));

		launch = launchButton();
		LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
		buttonParams.topMargin = dp(24);
		content.addView(launch, buttonParams);
		return hero;
	}

	private MaterialButton launchButton() {
		MaterialButton button = new MaterialButton(context);
		button.setAllCaps(false);
		button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
		button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		button.setGravity(Gravity.CENTER);
		button.setMinWidth(0);
		button.setMinHeight(0);
		button.setMinimumHeight(0);
		button.setInsetTop(0);
		button.setInsetBottom(0);
		button.setPadding(dp(16), 0, dp(16), 0);
		button.setIconPadding(dp(8));
		button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
		button.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(dp(28)).build());
		button.setOnTouchListener(new PressScaleTouchListener(0.97f, 100));
		button.setOnClickListener(v -> {
			if (payloadMissing) actions.showMessage(context.getString(R.string.game_launch_disabled_message));
			else actions.launchGame(); // Launch always validates the actual payload/profile, even while loading.
		});
		return button;
	}

	private void bindHero(HeroState state, int placeholder) {
		boolean ready = state != null && state.ready;
		payloadMissing = state != null && !state.ready;
		hero.setAnimationEnabled(ready);
		hero.setBackground(ready ? null : emptyHeroBackground());
		hero.setClipToOutline(!ready);
		heroTitle.setText(state == null ? context.getString(placeholder) : state.title);
		heroTitle.setTextColor(ready ? COLOR_ON_PRIMARY_CONTAINER : COLOR_ON_SURFACE);
		int tint = ready ? Color.argb(179, 255, 255, 255) : COLOR_ON_SURFACE_VARIANT;
		heroSubIcon.setImageDrawable(MaterialSymbols.drawable(context, state == null ? "sync" : ready ? "check_circle" : "error", tint, 16));
		heroSubtitle.setText(state == null ? "" : state.subtitle);
		heroSubtitle.setTextColor(tint);
		int buttonTint = payloadMissing ? Color.argb(77, 255, 255, 255) : COLOR_ON_PRIMARY;
		launch.setText(payloadMissing ? R.string.game_launch_disabled : R.string.launch_game);
		launch.setTextColor(buttonTint);
		MaterialSymbols.applyButtonIcon(launch, payloadMissing ? "block" : "play_arrow", ColorStateList.valueOf(buttonTint), 24);
		launch.setBackgroundTintList(ColorStateList.valueOf(payloadMissing ? Color.argb(20, 255, 255, 255) : COLOR_PRIMARY));
		launch.setRippleColor(ColorStateList.valueOf(Color.argb(payloadMissing ? 36 : 96, 255, 255, 255)));
		importAction.setHighlight(payloadMissing);
	}

	private View buildStatusRow() {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setBaselineAligned(false);
		row.setGravity(Gravity.CENTER_VERTICAL);

		modsCard = statusCard("extension", R.string.game_dashboard_mods_title, context.getString(R.string.game_dashboard_mods_subtitle));
		modsCard.setOnClickListener(v -> actions.openModsTab());
		savesCard = statusCard("save", R.string.game_dashboard_saves_title, "");
		savesCard.setOnClickListener(v -> actions.openSaveSettingsTab());

		LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		right.setMarginStart(dp(12));
		row.addView(modsCard, left);
		row.addView(savesCard, right);
		return row;
	}

	private StatusCardView statusCard(String iconGlyph, int titleRes, String subText) {
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
		card.value = text("", 22, COLOR_ON_SURFACE, Typeface.BOLD);
		card.subtitle = text(subText, 11, COLOR_ON_SURFACE_VARIANT, Typeface.NORMAL);
		values.addView(card.value);
		values.addView(card.subtitle, matchWrapParams(4));
		content.addView(values, matchWrapParams(10));
		return card;
	}

	private void bindSaveStatus(ExtraSettingsRepository.SaveStatus status) {
		savesCard.value.setText(status == null ? context.getString(R.string.unknown)
			: context.getString(R.string.game_dashboard_saves_value_format, status.normalProfiles + status.moddedProfiles));
		savesCard.subtitle.setText(status == null ? "" : context.getString(R.string.game_dashboard_saves_size_format, status.formattedBytes));
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

	private View buildActionRow(ActionSpec[] specs, int columns) {
		LinearLayout container = ExtraSettingsUi.vertical(context);
		for (int start = 0; start < specs.length; start += columns) {
			LinearLayout row = ExtraSettingsUi.horizontal(context);
			row.setBaselineAligned(false);
			row.setGravity(Gravity.CENTER_VERTICAL);
			for (int i = 0; i < columns; i++) {
				int index = start + i;
				View item = index < specs.length ? actionItem(specs[index]) : new View(context);
				LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
				if (i > 0) {
					params.setMarginStart(dp(12));
				}
				row.addView(item, params);
			}
			LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			if (start > 0) {
				rowParams.topMargin = dp(12);
			}
			container.addView(row, rowParams);
		}
		return container;
	}

	private View actionItem(ActionSpec spec) {
		ActionItemView item = new ActionItemView(context);
		if (spec.labelRes == R.string.game_action_import_body) importAction = item;
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

	private record HeroState(boolean ready, String title, String subtitle) {}

	private static final class ActionSpec {
		final String iconGlyph;
		final int labelRes;
		final View.OnClickListener listener;

		ActionSpec(String iconGlyph, int labelRes, View.OnClickListener listener) {
			this.iconGlyph = iconGlyph;
			this.labelRes = labelRes;
			this.listener = listener;
		}
	}

	// A clipped card is still attached and isShown(). Listen for scroll/layout changes
	// to wake it again rather than polling visibility with a hidden frame callback.
	private abstract static class TickingCard extends FrameLayout implements ViewTreeObserver.OnGlobalLayoutListener, Runnable {
		private final Rect visibleRect = new Rect();
		private boolean attached, pageActive, aggregatedVisible, observing, ticking;
		boolean animationEnabled;
		long startMs;

		TickingCard(Context context) {
			super(context);
		}

		void setAnimationEnabled(boolean enabled) {
			animationEnabled = enabled;
			updateTicker();
			invalidate();
		}

		void setPageActive(boolean active) {
			pageActive = active;
			updateTicker();
		}

		final void updateTicker() {
			boolean observe = attached && pageActive && animationEnabled && aggregatedVisible
				&& getWindowVisibility() == VISIBLE && hasWindowFocus();
			if (observe != observing) {
				observing = observe;
				ViewTreeObserver tree = getViewTreeObserver();
				if (observe) {
					tree.addOnGlobalLayoutListener(this);
				} else if (tree.isAlive()) {
					tree.removeOnGlobalLayoutListener(this);
				}
			}
			boolean visible = observe && isShown() && getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty();
			if (ticking == visible) return;
			ticking = visible;
			if (ticking) {
				startMs = SystemClock.uptimeMillis();
				postOnAnimation(this);
			} else {
				removeCallbacks(this);
			}
		}

		@Override public void run() {
			if (!ticking) return;
			invalidate();
			postOnAnimation(this);
		}

		@Override public void onGlobalLayout() { updateTicker(); }

		@Override protected void onAttachedToWindow() {
			super.onAttachedToWindow();
			attached = true;
			aggregatedVisible = isShown();
			updateTicker();
		}

		@Override protected void onDetachedFromWindow() {
			attached = false;
			updateTicker();
			super.onDetachedFromWindow();
		}

		@Override public void onVisibilityAggregated(boolean isVisible) {
			super.onVisibilityAggregated(isVisible);
			aggregatedVisible = isVisible;
			updateTicker();
		}

		@Override protected void onWindowVisibilityChanged(int visibility) {
			super.onWindowVisibilityChanged(visibility);
			updateTicker();
		}

		@Override public void onWindowFocusChanged(boolean hasWindowFocus) {
			super.onWindowFocusChanged(hasWindowFocus);
			updateTicker();
		}
	}

	private static final class AnimatedHeroCard extends TickingCard {
		private static final int[] GRADIENT_COLORS = { COLOR_PRIMARY_CONTAINER, COLOR_SECONDARY_CONTAINER, Color.rgb(0, 109, 54) };
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint topOrb = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint bottomOrb = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final Path clipPath = new Path();
		private final int radius;
		private final float orbitStep;

		AnimatedHeroCard(Context context) {
			super(context);
			setWillNotDraw(false);
			radius = ExtraSettingsUi.dp(context, 24);
			orbitStep = ExtraSettingsUi.dp(context, 20);
			topOrb.setColor(Color.argb(38, 129, 217, 154));
			topOrb.setMaskFilter(new BlurMaskFilter(ExtraSettingsUi.dp(context, 60), BlurMaskFilter.Blur.NORMAL));
			bottomOrb.setColor(Color.argb(26, 156, 246, 180));
			bottomOrb.setMaskFilter(new BlurMaskFilter(ExtraSettingsUi.dp(context, 50), BlurMaskFilter.Blur.NORMAL));
		}

		@Override void setAnimationEnabled(boolean enabled) {
			if (animationEnabled == enabled) return;
			setLayerType(enabled ? View.LAYER_TYPE_SOFTWARE : View.LAYER_TYPE_NONE, null);
			super.setAnimationEnabled(enabled);
		}

		@Override
		protected void onSizeChanged(int w, int h, int oldw, int oldh) {
			super.onSizeChanged(w, h, oldw, oldh);
			rect.set(0, 0, w, h);
			clipPath.rewind();
			clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
			paint.setShader(w > 0 && h > 0 ? new LinearGradient(0, 0, w * 1.35f, h, GRADIENT_COLORS, null, Shader.TileMode.CLAMP) : null);
		}

		@Override
		protected void onDraw(Canvas canvas) {
			super.onDraw(canvas);
			float width = getWidth();
			float height = getHeight();
			if (!animationEnabled || width <= 0 || height <= 0) {
				return;
			}
			long elapsed = SystemClock.uptimeMillis() - startMs;
			float gradientCycle = (elapsed % 8000L) / 8000f;
			float flow = (float)(0.5f - 0.5f * Math.cos(Math.PI * 2f * gradientCycle));
			canvas.save();
			canvas.clipPath(clipPath);

			float offset = (flow - 0.5f) * width * 1.4f;
			// Translate the cached shader with the canvas, inside the stationary rounded clip.
			canvas.save();
			canvas.translate(-offset, 0);
			canvas.drawRect(offset, 0, width + offset, height, paint);
			canvas.restore();

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
			float radius = orbitStep * (top ? 4f : 3f) * (1f + 0.1f * eased);
			float centerX = top ? width + orbitStep * (3f - eased) : orbitStep * (2f - eased);
			float centerY = top ? orbitStep * (2f + eased) : height + orbitStep * (1f + eased);
			canvas.drawCircle(centerX, centerY, radius, top ? topOrb : bottomOrb);
		}
	}

	private static final class StatusCardView extends FrameLayout {
		private final GradientDrawable backgroundDrawable;
		private ImageView watermark;
		private TextView value;
		private TextView subtitle;

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

	private static final class ActionItemView extends TickingCard {
		private final GradientDrawable backgroundDrawable;
		private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF pulseRect = new RectF();

		ActionItemView(Context context) {
			super(context);
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

		void setHighlight(boolean highlight) {
			if (animationEnabled == highlight) return;
			setAnimationEnabled(highlight);
			applyBackground(isPressed());
		}

		private void applyBackground(boolean pressed) {
			backgroundDrawable.setColor(pressed ? COLOR_SURFACE_CONTAINER : COLOR_SURFACE);
			backgroundDrawable.setStroke(ExtraSettingsUi.dp(getContext(), 1), animationEnabled ? COLOR_PRIMARY : Color.argb(13, 255, 255, 255));
		}

		@Override
		protected void dispatchDraw(Canvas canvas) {
			super.dispatchDraw(canvas);
			if (!animationEnabled) {
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
