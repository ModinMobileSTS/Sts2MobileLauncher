package com.godot.game;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import com.godot.game.steam.auth.SteamAuthStore;
import com.godot.game.steam.cloud.Sts2SteamCloudSyncManager;
import com.godot.game.steam.core.SteamSettings;
import com.godot.game.webdav.WebDavSettings;
import com.godot.game.webdav.WebDavSyncManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Date;
import java.util.Locale;

public final class SettingsPage {
	private static final List<ResolutionOption> RESOLUTION_OPTIONS = Arrays.asList(
		new ResolutionOption(0, 0),
		new ResolutionOption(1280, 720),
		new ResolutionOption(1600, 900),
		new ResolutionOption(1920, 1080),
		new ResolutionOption(2560, 1440),
		new ResolutionOption(3200, 1800)
	);
	private static final List<ScaleOption> SCALE_OPTIONS = Arrays.asList(
		new ScaleOption(0.7f),
		new ScaleOption(1.0f),
		new ScaleOption(1.1f),
		new ScaleOption(1.3f),
		new ScaleOption(1.5f),
		new ScaleOption(2.0f),
		new ScaleOption(-1f)
	);
	private static final int[] FONT_SCALE_OPTIONS = new int[] { 70, 85, 100, 115, 130, 150, 160, 165 };
	private static final int[] MSAA_OPTIONS = new int[] { 0, 2, 4, 8 };
	private static final String[] VSYNC_VALUES = new String[] { "off", "on", "adaptive" };
	private static final String[] ASPECT_VALUES = new String[] { "auto", "sixteen_by_nine", "sixteen_by_ten", "twenty_one_by_nine", "four_by_three" };
	private static final String[] RENDERER_VALUES = new String[] { RendererPreference.RENDERER_OPENGL_ES3, RendererPreference.RENDERER_VULKAN };
	private static final String[] LOG_LEVEL_VALUES = new String[] { ExtraSettingsRepository.LOG_LEVEL_OFF, ExtraSettingsRepository.LOG_LEVEL_INFO, ExtraSettingsRepository.LOG_LEVEL_DEBUG, ExtraSettingsRepository.LOG_LEVEL_VERY_DEBUG };
	private static final String[] LAUNCHER_STARTUP_VALUES = new String[] { ExtraSettingsPreferences.LAUNCHER_STARTUP_SETTINGS, ExtraSettingsPreferences.LAUNCHER_STARTUP_GAME };
	private static final String[] VFX_PRELOAD_VALUES = new String[] { "off", "hot", "full" };
	private static final String[] SHADER_PRELOAD_VALUES = new String[] { "off", "load_resources" };
	private static final String[] TOOLTIP_MODE_VALUES = new String[] { ExtraSettingsRepository.TOOLTIP_MODE_IMMEDIATE, ExtraSettingsRepository.TOOLTIP_MODE_LONG_PRESS, ExtraSettingsRepository.TOOLTIP_MODE_HIDDEN };

	private enum SettingsSegment { GRAPHICS, INPUT, SAVE, SYSTEM }
	private static SettingsSegment lastSelectedSegment = SettingsSegment.GRAPHICS;

	public static void selectSaveSegment() {
		lastSelectedSegment = SettingsSegment.SAVE;
	}

	private final Context context;
	private final ExtraSettingsRepository repository;
	private final ExtraSettingsActions actions;

	private EditText customLanPlayerIdInput;

	public SettingsPage(Context context, ExtraSettingsRepository repository, ExtraSettingsActions actions) {
		this.context = context;
		this.repository = repository;
		this.actions = actions;
	}

	public View build() {
		LinearLayout shell = ExtraSettingsUi.vertical(context);
		shell.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		int padding = ExtraSettingsUi.pageHorizontalPadding(context);
		shell.setPadding(padding, ExtraSettingsUi.dp(context, 18), padding, 0);
		LinearLayout header = ExtraSettingsUi.vertical(context);
		header.addView(ExtraSettingsUi.title(context, R.string.tab_settings));

		LinearLayout tabContent = ExtraSettingsUi.vertical(context);
		View tabs = buildSettingsSegmentedTabs(tabContent);
		LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		tabsParams.topMargin = ExtraSettingsUi.dp(context, 12);
		header.addView(tabs, tabsParams);
		shell.addView(header, ExtraSettingsUi.centeredContentParams(context));

		ScrollView scrollView = new ScrollView(context);
		scrollView.setFillViewport(false);
		scrollView.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		tabContent.setPadding(0, ExtraSettingsUi.dp(context, 8), 0, ExtraSettingsUi.dp(context, 32));
		if (ExtraSettingsUi.isWideLayout(context)) {
			ExtraSettingsUi.addResponsiveScrollContent(context, scrollView, tabContent);
		} else {
			scrollView.addView(tabContent, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		}
		shell.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		if (tabContent.getChildCount() == 0) {
			showSettingsSegment(lastSelectedSegment, tabContent);
		}
		return shell;
	}

	private View buildSettingsSegmentedTabs(LinearLayout tabContent) {
		MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(context);
		group.setSingleSelection(true);
		group.setSelectionRequired(true);
		group.setBackgroundColor(Color.TRANSPARENT);

		MaterialButton graphics = segmentedButton(R.string.settings_segment_graphics);
		MaterialButton input = segmentedButton(R.string.settings_segment_input);
		MaterialButton save = segmentedButton(R.string.settings_segment_save);
		MaterialButton system = segmentedButton(R.string.settings_segment_system);
		graphics.setId(View.generateViewId());
		input.setId(View.generateViewId());
		save.setId(View.generateViewId());
		system.setId(View.generateViewId());

		group.addView(graphics, segmentedButtonParams());
		group.addView(input, segmentedButtonParams());
		group.addView(save, segmentedButtonParams());
		group.addView(system, segmentedButtonParams());
		group.addOnButtonCheckedListener((buttonGroup, checkedId, isChecked) -> {
			if (!isChecked) {
				return;
			}
			if (checkedId == graphics.getId()) {
				showSettingsSegment(SettingsSegment.GRAPHICS, tabContent);
			} else if (checkedId == input.getId()) {
				showSettingsSegment(SettingsSegment.INPUT, tabContent);
			} else if (checkedId == save.getId()) {
				showSettingsSegment(SettingsSegment.SAVE, tabContent);
			} else if (checkedId == system.getId()) {
				showSettingsSegment(SettingsSegment.SYSTEM, tabContent);
			}
		});
		if (lastSelectedSegment == SettingsSegment.INPUT) {
			group.check(input.getId());
		} else if (lastSelectedSegment == SettingsSegment.SAVE) {
			group.check(save.getId());
		} else if (lastSelectedSegment == SettingsSegment.SYSTEM) {
			group.check(system.getId());
		} else {
			group.check(graphics.getId());
		}
		return group;
	}

	private MaterialButton segmentedButton(int textRes) {
		MaterialButton button = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
		button.setText(textRes);
		button.setTextSize(14);
		button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		button.setGravity(Gravity.CENTER);
		button.setCheckable(true);
		button.setMinHeight(ExtraSettingsUi.dp(context, 44));
		button.setInsetTop(0);
		button.setInsetBottom(0);
		button.setPadding(ExtraSettingsUi.dp(context, 6), 0, ExtraSettingsUi.dp(context, 6), 0);
		button.setTextColor(new ColorStateList(
			new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
			new int[] { ExtraSettingsUi.COLOR_PRIMARY, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT }
		));
		button.setBackgroundTintList(new ColorStateList(
			new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
			new int[] { ExtraSettingsUi.COLOR_SURFACE_VARIANT, Color.TRANSPARENT }
		));
		button.setStrokeColor(ColorStateList.valueOf(ExtraSettingsUi.COLOR_OUTLINE));
		button.setStrokeWidth(ExtraSettingsUi.dp(context, 1));
		button.setCornerRadius(ExtraSettingsUi.dp(context, 22));
		return button;
	}

	private LinearLayout.LayoutParams segmentedButtonParams() {
		return new LinearLayout.LayoutParams(0, ExtraSettingsUi.dp(context, 46), 1f);
	}

	private void showSettingsSegment(SettingsSegment segment, LinearLayout root) {
		lastSelectedSegment = segment;
		root.removeAllViews();
		try {
			JSONObject settings = repository.loadSettingsJson();
			if (segment == SettingsSegment.INPUT) {
				addResponsiveCards(root, buildInputPresetCard(settings), buildInputDetailsCard(settings));
			} else if (segment == SettingsSegment.SAVE) {
				addResponsiveCards(root, buildSaveCard(), buildLocalSaveSnapshotCard(), buildSteamCloudCard(), buildWebDavCloudCard(), buildFullDataBackupCard());
			} else if (segment == SettingsSegment.SYSTEM) {
				addResponsiveCards(root, buildSystemCard(settings), buildLanCard(settings), buildLogCard(settings));
			} else {
				addResponsiveCards(root, buildPresetCard(settings), buildGraphicsAdvancedCard(settings));
			}
		} catch (Exception exception) {
			ExtraSettingsUi.addCardSpacing(root, errorCard(exception));
		}
	}

	private void addResponsiveCards(LinearLayout root, View... cards) {
		if (!ExtraSettingsUi.isWideLayout(context)) {
			for (View card : cards) {
				ExtraSettingsUi.addCardSpacing(root, card);
			}
			return;
		}
		for (int i = 0; i < cards.length; i += 2) {
			if (i + 1 < cards.length) {
				ExtraSettingsUi.addResponsivePair(context, root, cards[i], cards[i + 1]);
			} else {
				ExtraSettingsUi.addCardSpacing(root, cards[i]);
			}
		}
	}

	private View buildPresetCard(JSONObject settings) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_auto_awesome_24, R.string.settings_presets_title, R.string.settings_presets_subtitle, null));
		String graphicsPreset = detectGraphicsPreset(settings);
		String displayPreset = detectDisplayPreset(settings);

		ExtraSettingsUi.addSmallSpacing(content, presetGroupTitle(R.string.preset_render_group_title));
		MaterialCardView recommended = miniPresetCard(R.drawable.ic_auto_awesome_24, R.string.graphics_preset_recommended, R.string.graphics_preset_recommended_desc, ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED.equals(graphicsPreset));
		MaterialCardView quality = miniPresetCard(R.drawable.ic_high_quality_24, R.string.graphics_preset_quality, R.string.graphics_preset_quality_desc, ExtraSettingsRepository.GRAPHICS_PRESET_QUALITY.equals(graphicsPreset));
		MaterialCardView compatibility = miniPresetCard(R.drawable.ic_build_24, R.string.graphics_preset_compatibility, R.string.graphics_preset_compatibility_desc, ExtraSettingsRepository.GRAPHICS_PRESET_COMPATIBILITY.equals(graphicsPreset));
		MaterialCardView graphicsCustom = miniPresetCard(R.drawable.ic_tune_24, R.string.preset_custom, R.string.preset_custom_desc, ExtraSettingsRepository.GRAPHICS_PRESET_CUSTOM.equals(graphicsPreset));
		recommended.setOnClickListener(v -> {
			setGraphicsPresetCards(recommended, quality, compatibility, graphicsCustom, ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED);
			applyGraphicsPreset(ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED);
		});
		quality.setOnClickListener(v -> {
			setGraphicsPresetCards(recommended, quality, compatibility, graphicsCustom, ExtraSettingsRepository.GRAPHICS_PRESET_QUALITY);
			applyGraphicsPreset(ExtraSettingsRepository.GRAPHICS_PRESET_QUALITY);
		});
		compatibility.setOnClickListener(v -> {
			setGraphicsPresetCards(recommended, quality, compatibility, graphicsCustom, ExtraSettingsRepository.GRAPHICS_PRESET_COMPATIBILITY);
			applyGraphicsPreset(ExtraSettingsRepository.GRAPHICS_PRESET_COMPATIBILITY);
		});
		graphicsCustom.setOnClickListener(v -> {
			setGraphicsPresetCards(recommended, quality, compatibility, graphicsCustom, ExtraSettingsRepository.GRAPHICS_PRESET_CUSTOM);
			applyGraphicsPreset(ExtraSettingsRepository.GRAPHICS_PRESET_CUSTOM);
		});
		addMiniPresetRow(content, recommended, quality, compatibility, graphicsCustom);

		ExtraSettingsUi.addSmallSpacing(content, presetGroupTitle(R.string.preset_display_group_title));
		MaterialCardView original = miniPresetCard(R.drawable.ic_desktop_windows_24, R.string.display_preset_original, R.string.display_preset_original_desc, ExtraSettingsRepository.DISPLAY_PRESET_ORIGINAL.equals(displayPreset));
		MaterialCardView mobile = miniPresetCard(R.drawable.ic_phone_android_24, R.string.display_preset_mobile, R.string.display_preset_mobile_short_desc, ExtraSettingsRepository.DISPLAY_PRESET_MOBILE.equals(displayPreset));
		MaterialCardView displayCustom = miniPresetCard(R.drawable.ic_tune_24, R.string.preset_custom, R.string.preset_custom_desc, ExtraSettingsRepository.DISPLAY_PRESET_CUSTOM.equals(displayPreset));
		original.setOnClickListener(v -> {
			setDisplayPresetCards(original, mobile, displayCustom, ExtraSettingsRepository.DISPLAY_PRESET_ORIGINAL);
			applyDisplayPreset(ExtraSettingsRepository.DISPLAY_PRESET_ORIGINAL);
		});
		mobile.setOnClickListener(v -> {
			setDisplayPresetCards(original, mobile, displayCustom, ExtraSettingsRepository.DISPLAY_PRESET_MOBILE);
			applyDisplayPreset(ExtraSettingsRepository.DISPLAY_PRESET_MOBILE);
		});
		displayCustom.setOnClickListener(v -> {
			setDisplayPresetCards(original, mobile, displayCustom, ExtraSettingsRepository.DISPLAY_PRESET_CUSTOM);
			applyDisplayPreset(ExtraSettingsRepository.DISPLAY_PRESET_CUSTOM);
		});
		addMiniPresetRow(content, original, mobile, displayCustom);
		return card;
	}

	private TextView presetGroupTitle(int titleRes) {
		return ExtraSettingsUi.text(context, titleRes, 14, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, Typeface.BOLD);
	}

	private MaterialCardView miniPresetCard(int iconRes, int titleRes, int subtitleRes, boolean selected) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(context);
		card.setCheckable(true);
		card.setRadius(ExtraSettingsUi.dp(context, 12));
		card.setMinimumHeight(ExtraSettingsUi.dp(context, 74));
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.setGravity(Gravity.CENTER_HORIZONTAL);
		content.setPadding(ExtraSettingsUi.dp(context, 6), ExtraSettingsUi.dp(context, 8), ExtraSettingsUi.dp(context, 6), ExtraSettingsUi.dp(context, 8));

		ImageView icon = ExtraSettingsUi.icon(context, iconRes, selected ? ExtraSettingsUi.COLOR_PRIMARY : ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 20);
		icon.setTag("mini_preset_icon");
		content.addView(icon);

		TextView title = ExtraSettingsUi.text(context, titleRes, 13, selected ? ExtraSettingsUi.COLOR_PRIMARY : ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setSingleLine(true);
		title.setEllipsize(TextUtils.TruncateAt.END);
		title.setGravity(Gravity.CENTER);
		title.setTag("mini_preset_title");
		LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		titleParams.topMargin = ExtraSettingsUi.dp(context, 4);
		content.addView(title, titleParams);

		TextView subtitle = ExtraSettingsUi.text(context, subtitleRes, 10, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, Typeface.NORMAL);
		subtitle.setSingleLine(true);
		subtitle.setEllipsize(TextUtils.TruncateAt.END);
		subtitle.setGravity(Gravity.CENTER);
		subtitle.setTag("mini_preset_subtitle");
		LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		subtitleParams.topMargin = ExtraSettingsUi.dp(context, 2);
		content.addView(subtitle, subtitleParams);

		setMiniPresetSelected(card, selected);
		return card;
	}

	private void addMiniPresetRow(LinearLayout parent, MaterialCardView... cards) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		for (int i = 0; i < cards.length; i++) {
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			if (i > 0) {
				params.setMarginStart(ExtraSettingsUi.dp(context, 8));
			}
			row.addView(cards[i], params);
		}
		ExtraSettingsUi.addSmallSpacing(parent, row);
	}

	private void setGraphicsPresetCards(MaterialCardView recommended, MaterialCardView quality, MaterialCardView compatibility, MaterialCardView custom, String preset) {
		setMiniPresetSelected(recommended, ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED.equals(preset));
		setMiniPresetSelected(quality, ExtraSettingsRepository.GRAPHICS_PRESET_QUALITY.equals(preset));
		setMiniPresetSelected(compatibility, ExtraSettingsRepository.GRAPHICS_PRESET_COMPATIBILITY.equals(preset));
		setMiniPresetSelected(custom, ExtraSettingsRepository.GRAPHICS_PRESET_CUSTOM.equals(preset));
	}

	private void setDisplayPresetCards(MaterialCardView original, MaterialCardView mobile, MaterialCardView custom, String preset) {
		setMiniPresetSelected(original, ExtraSettingsRepository.DISPLAY_PRESET_ORIGINAL.equals(preset));
		setMiniPresetSelected(mobile, ExtraSettingsRepository.DISPLAY_PRESET_MOBILE.equals(preset));
		setMiniPresetSelected(custom, ExtraSettingsRepository.DISPLAY_PRESET_CUSTOM.equals(preset));
	}

	private void setMiniPresetSelected(MaterialCardView card, boolean selected) {
		card.setChecked(selected);
		card.setStrokeWidth(ExtraSettingsUi.dp(context, selected ? 2 : 1));
		card.setStrokeColor(selected ? ExtraSettingsUi.COLOR_PRIMARY : ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		card.setCardBackgroundColor(selected ? Color.rgb(30, 50, 39) : ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		updateMiniPresetChildren(card, selected);
	}

	private void updateMiniPresetChildren(View view, boolean selected) {
		Object tag = view.getTag();
		if (view instanceof ImageView && "mini_preset_icon".equals(tag)) {
			((ImageView) view).setColorFilter(selected ? ExtraSettingsUi.COLOR_PRIMARY : ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
		} else if (view instanceof TextView && "mini_preset_title".equals(tag)) {
			((TextView) view).setTextColor(selected ? ExtraSettingsUi.COLOR_PRIMARY : ExtraSettingsUi.COLOR_ON_SURFACE);
		} else if (view instanceof TextView && "mini_preset_subtitle".equals(tag)) {
			((TextView) view).setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT);
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) {
				updateMiniPresetChildren(group.getChildAt(i), selected);
			}
		}
	}

	private View buildGraphicsAdvancedCard(JSONObject settings) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_tune_24, R.string.section_graphics_advanced, R.string.section_graphics_advanced_hint, infoButton(R.string.section_graphics_advanced, R.string.section_graphics_advanced_info)));

		addSpinnerRow(content, R.drawable.ic_aspect_ratio_24, R.string.aspect_ratio, buildAspectLabels(), findStringIndex(ASPECT_VALUES, settings.optString("aspect_ratio", "auto")), position -> repository.saveSetting(root -> root.put("aspect_ratio", ASPECT_VALUES[position])));
		addSpinnerRow(content, R.drawable.ic_desktop_windows_24, R.string.section_resolution, buildResolutionLabels(), findResolutionSelection(settings), position -> {
			ResolutionOption option = RESOLUTION_OPTIONS.get(position);
			repository.saveSetting(root -> repository.putVector(root, "fullscreen_render_size", option.width, option.height));
		});
		addSpinnerRow(content, R.drawable.ic_layers_24, R.string.section_renderer, Arrays.asList(context.getString(R.string.renderer_option_opengl_es3), context.getString(R.string.renderer_option_vulkan)), findStringIndex(RENDERER_VALUES, RendererPreference.getSelectedRenderer(context)), position -> RendererPreference.setSelectedRenderer(context, RENDERER_VALUES[position]));
		addSpinnerRow(content, R.drawable.ic_blur_on_24, R.string.msaa, buildMsaaLabels(), findIntIndex(MSAA_OPTIONS, settings.optInt("msaa", 2)), position -> repository.saveSetting(root -> root.put("msaa", MSAA_OPTIONS[position])));
		addSpinnerRow(content, R.drawable.ic_sync_24, R.string.vsync, buildVsyncLabels(), findStringIndex(VSYNC_VALUES, settings.optString("vsync", "off")), position -> repository.saveSetting(root -> root.put("vsync", VSYNC_VALUES[position])));
		addSpinnerRow(content, R.drawable.ic_zoom_in_24, R.string.section_scale, buildScaleLabels((float) settings.optDouble("global_scale", 1.0)), findScaleSelection(settings), position -> {
			ScaleOption option = SCALE_OPTIONS.get(position);
			if (option.isCustom()) {
				showCustomScaleDialog();
			} else {
				repository.saveSetting(root -> root.put("global_scale", option.scale));
			}
		});
		addSpinnerRow(content, R.drawable.ic_text_fields_24, R.string.font_scale, buildFontScaleLabels(), findIntIndex(FONT_SCALE_OPTIONS, settings.optInt("ui_font_scale_percent", 100)), position -> repository.saveSetting(root -> root.put("ui_font_scale_percent", FONT_SCALE_OPTIONS[position])));
		addSwitchRow(content, R.drawable.ic_build_24, R.string.shader_compatibility_switch, R.string.shader_compatibility_hint, settings.optBoolean("shader_compatibility_mode", false), checked -> repository.saveSetting(root -> root.put("shader_compatibility_mode", checked)));
		return card;
	}

	private View buildSaveCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_save_24, R.string.section_save, R.string.settings_save_subtitle, null));
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		MaterialButton export = ExtraSettingsUi.outlineButton(context, R.string.export_save, R.drawable.ic_download_24);
		MaterialButton importSave = ExtraSettingsUi.tonalButton(context, R.string.import_save, R.drawable.ic_upload_file_24);
		export.setOnClickListener(v -> actions.requestExportSave());
		importSave.setOnClickListener(v -> actions.requestImportSave());
		row.addView(export, weighted(0));
		row.addView(importSave, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, row);
		MaterialButton unlockAll = ExtraSettingsUi.outlineButton(context, R.string.unlock_all, R.drawable.ic_lock_open_24);
		unlockAll.setOnClickListener(v -> confirmUnlockAll());
		ExtraSettingsUi.addSmallSpacing(content, unlockAll);
		MaterialButton toModded = ExtraSettingsUi.outlineButton(context, R.string.mod_save_transfer_to_modded, R.drawable.ic_compare_arrows_24);
		MaterialButton toNormal = ExtraSettingsUi.outlineButton(context, R.string.mod_save_transfer_to_normal, R.drawable.ic_compare_arrows_24);
		toModded.setOnClickListener(v -> confirmModSaveTransfer(false));
		toNormal.setOnClickListener(v -> confirmModSaveTransfer(true));
		ExtraSettingsUi.addSmallSpacing(content, toModded);
		ExtraSettingsUi.addSmallSpacing(content, toNormal);
		return card;
	}

	private View buildLocalSaveSnapshotCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_ms_photo_camera_24, R.string.local_save_snapshot_title, R.string.local_save_snapshot_subtitle, null));
		LocalSaveSnapshotManager.Status status = new LocalSaveSnapshotManager(context).getStatus();
		LocalSaveSnapshotManager.Snapshot latest = status.snapshots.isEmpty() ? null : status.snapshots.get(0);
		String latestLabel = latest == null ? context.getString(R.string.local_save_snapshot_none) : snapshotDisplayText(latest);
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_ms_photo_camera_24, context.getString(R.string.local_save_snapshot_status, status.snapshots.size(), status.retentionLimit, latestLabel)));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, status.snapshotRoot.getAbsolutePath()));
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		MaterialButton create = ExtraSettingsUi.outlineButton(context, R.string.local_save_snapshot_create_now, R.drawable.ic_ms_photo_camera_24);
		MaterialButton restore = ExtraSettingsUi.tonalButton(context, R.string.local_save_snapshot_restore, R.drawable.ic_download_24);
		create.setOnClickListener(v -> actions.runAsyncOperation(context.getString(R.string.status_busy_create_local_save_snapshot), () -> {
			LocalSaveSnapshotManager.Snapshot snapshot = new LocalSaveSnapshotManager(context).createManualSnapshot();
			return context.getString(R.string.status_local_save_snapshot_created, snapshotDisplayText(snapshot));
		}));
		restore.setOnClickListener(v -> showLocalSaveSnapshotRestoreDialog(status.snapshots));
		row.addView(create, weighted(0));
		row.addView(restore, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, row);
		return card;
	}

	private void showLocalSaveSnapshotRestoreDialog(List<LocalSaveSnapshotManager.Snapshot> snapshots) {
		if (snapshots == null || snapshots.isEmpty()) {
			actions.showMessage(context.getString(R.string.local_save_snapshot_no_snapshots));
			return;
		}
		String[] labels = new String[snapshots.size()];
		for (int i = 0; i < snapshots.size(); i++) {
			LocalSaveSnapshotManager.Snapshot snapshot = snapshots.get(i);
			labels[i] = context.getString(
				R.string.local_save_snapshot_picker_item,
				snapshotDisplayText(snapshot),
				Formatter.formatFileSize(context, snapshot.sizeBytes)
			);
		}
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.local_save_snapshot_restore)
			.setItems(labels, (dialog, which) -> confirmRestoreLocalSaveSnapshot(snapshots.get(which)))
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private void confirmRestoreLocalSaveSnapshot(LocalSaveSnapshotManager.Snapshot snapshot) {
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.local_save_snapshot_restore_confirm_title)
			.setMessage(context.getString(R.string.local_save_snapshot_restore_confirm_message, snapshotDisplayText(snapshot)))
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> actions.runAsyncOperation(context.getString(R.string.status_busy_restore_local_save_snapshot), () -> {
				LocalSaveSnapshotManager.Snapshot restored = new LocalSaveSnapshotManager(context).restoreSnapshot(snapshot.id);
				return context.getString(R.string.status_local_save_snapshot_restored, snapshotDisplayText(restored));
			}))
			.show();
	}

	private String snapshotDisplayText(LocalSaveSnapshotManager.Snapshot snapshot) {
		if (snapshot == null) {
			return context.getString(R.string.local_save_snapshot_none);
		}
		String reason = snapshotReasonLabel(snapshot.reason);
		String time = snapshot.createdAtMs <= 0L ? context.getString(R.string.unknown) : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(snapshot.createdAtMs));
		String fileCount = snapshot.fileCount < 0 ? "?" : String.valueOf(snapshot.fileCount);
		return context.getString(R.string.local_save_snapshot_display, time, reason, fileCount);
	}

	private String snapshotReasonLabel(String reason) {
		String value = reason == null ? "" : reason;
		if ("before-launch".equals(value)) {
			return context.getString(R.string.local_save_snapshot_reason_before_launch);
		}
		if ("clean-exit".equals(value)) {
			return context.getString(R.string.local_save_snapshot_reason_clean_exit);
		}
		if ("before-restore".equals(value)) {
			return context.getString(R.string.local_save_snapshot_reason_before_restore);
		}
		if ("manual".equals(value)) {
			return context.getString(R.string.local_save_snapshot_reason_manual);
		}
		return value.isEmpty() ? context.getString(R.string.local_save_snapshot_reason_snapshot) : value;
	}

	private View buildSteamCloudCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_steam_24, R.string.steam_cloud_title, R.string.steam_cloud_subtitle, null));
		SteamAuthStore.AuthSnapshot auth = SteamAuthStore.readSnapshot(context);
		Sts2SteamCloudSyncManager.Status status = new Sts2SteamCloudSyncManager(context).getStatus();
		String account = auth.refreshTokenConfigured ? auth.accountName : context.getString(R.string.steam_not_logged_in);
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_steam_24, context.getString(R.string.steam_cloud_settings_status, account, SteamSettings.getCloudMode(context), status.remoteFileCount)));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, status.accountRoot.getAbsolutePath()));
		MaterialButton open = ExtraSettingsUi.tonalButton(context, R.string.steam_account_open, R.drawable.ic_download_24);
		open.setOnClickListener(v -> actions.openSteamAccount());
		ExtraSettingsUi.addSmallSpacing(content, open);
		return card;
	}

	private View buildWebDavCloudCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_cloud_sync_24, R.string.webdav_cloud_title, R.string.webdav_cloud_subtitle, null));
		WebDavSyncManager.Status status = new WebDavSyncManager(context).getStatus();
		String endpoint = status.config.isConfigured() ? status.config.baseUrl : context.getString(R.string.webdav_not_configured);
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_cloud_sync_24, context.getString(R.string.webdav_cloud_settings_status, endpoint, status.config.cloudMode, status.remoteFileCount)));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, status.accountRoot.getAbsolutePath()));
		MaterialButton open = ExtraSettingsUi.tonalButton(context, R.string.webdav_open_center, R.drawable.ic_cloud_sync_24);
		open.setOnClickListener(v -> actions.openWebDavCloud());
		ExtraSettingsUi.addSmallSpacing(content, open);
		return card;
	}

	private View buildFullDataBackupCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_save_24, R.string.full_data_backup_title, R.string.full_data_backup_subtitle, infoButton(R.string.full_data_backup_title, R.string.full_data_backup_info)));
		TextView statusText = addMetricRow(content, R.drawable.ic_folder_24, context.getString(R.string.full_data_backup_calculating));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, context.getDataDir().getAbsolutePath()));
		loadFullDataStatusAsync(statusText);
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		MaterialButton export = ExtraSettingsUi.outlineButton(context, R.string.full_data_backup_export, R.drawable.ic_download_24);
		MaterialButton importBackup = ExtraSettingsUi.tonalButton(context, R.string.full_data_backup_import, R.drawable.ic_upload_file_24);
		export.setOnClickListener(v -> actions.requestExportFullDataBackup());
		importBackup.setOnClickListener(v -> actions.requestImportFullDataBackup());
		row.addView(export, weighted(0));
		row.addView(importBackup, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, row);
		return card;
	}

	private View metricRow(int iconRes, String text) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_PRIMARY, 22));
		row.addView(ExtraSettingsUi.body(context, text), labelParams());
		return row;
	}

	private TextView addMetricRow(LinearLayout parent, int iconRes, String text) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_PRIMARY, 22));
		TextView textView = ExtraSettingsUi.body(context, text);
		row.addView(textView, labelParams());
		ExtraSettingsUi.addSmallSpacing(parent, row);
		return textView;
	}

	private void loadFullDataStatusAsync(TextView target) {
		new Thread(() -> {
			try {
				ExtraSettingsRepository.FullDataStatus status = repository.getFullDataStatus();
				target.post(() -> target.setText(context.getString(R.string.full_data_backup_size_format, status.formattedBytes, status.modCount)));
			} catch (Exception exception) {
				target.post(() -> target.setText(exception.getMessage() == null ? exception.toString() : exception.getMessage()));
			}
		}, "sts2-full-data-size").start();
	}

	private View buildInputPresetCard(JSONObject settings) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_touch_app_24, R.string.section_input, R.string.settings_input_subtitle, infoButton(R.string.section_input, R.string.settings_input_info)));
		String operationPreset = detectOperationPreset(settings);
		MaterialCardView touch = miniPresetCard(R.drawable.ic_touch_app_24, R.string.operation_preset_touch, R.string.operation_preset_touch_desc, ExtraSettingsRepository.OPERATION_PRESET_TOUCH.equals(operationPreset));
		MaterialCardView original = miniPresetCard(R.drawable.ic_gamepad_24, R.string.operation_preset_original, R.string.operation_preset_original_desc, ExtraSettingsRepository.OPERATION_PRESET_ORIGINAL.equals(operationPreset));
		touch.setOnClickListener(v -> {
			setMiniPresetSelected(touch, true);
			setMiniPresetSelected(original, false);
			applyOperationPreset(ExtraSettingsRepository.OPERATION_PRESET_TOUCH);
		});
		original.setOnClickListener(v -> {
			setMiniPresetSelected(touch, false);
			setMiniPresetSelected(original, true);
			applyOperationPreset(ExtraSettingsRepository.OPERATION_PRESET_ORIGINAL);
		});
		addMiniPresetRow(content, touch, original);
		return card;
	}

	private View buildInputDetailsCard(JSONObject settings) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_settings_24, R.string.settings_input_details_title, R.string.settings_input_details_subtitle, null));
		addSwitchRow(content, R.drawable.ic_check_circle_24, R.string.mobile_selection_confirmation_switch, R.string.mobile_selection_confirmation_hint, settings.optBoolean("mobile_selection_confirmation", true), checked -> repository.saveSetting(root -> root.put("mobile_selection_confirmation", checked)));
		addSwitchRow(content, R.drawable.ic_text_fields_24, R.string.show_more_hand_card_text_switch, R.string.show_more_hand_card_text_hint, settings.optBoolean("show_more_hand_card_text", true), checked -> repository.saveSetting(root -> root.put("show_more_hand_card_text", checked)));
		addSwitchRow(content, R.drawable.ic_touch_app_24, R.string.touch_lift_preview_switch, R.string.touch_lift_preview_hint, settings.optBoolean("touch_lift_preview", true), checked -> repository.saveSetting(root -> root.put("touch_lift_preview", checked)));
		addSpinnerRow(content, R.drawable.ic_info_24, R.string.mobile_tooltip_mode_title, buildTooltipModeLabels(), findStringIndex(TOOLTIP_MODE_VALUES, settings.optString("mobile_tooltip_mode", ExtraSettingsRepository.TOOLTIP_MODE_IMMEDIATE)), position -> {
			repository.saveSetting(root -> {
				root.put("mobile_tooltip_mode", TOOLTIP_MODE_VALUES[position]);
				root.put("mobile_tooltip_long_press_ms", 1000);
			});
		});
		addSwitchRow(content, R.drawable.ic_gesture_24, R.string.mobile_two_finger_inspect_switch, R.string.mobile_two_finger_inspect_hint, settings.optBoolean("mobile_two_finger_inspect", true), checked -> repository.saveSetting(root -> root.put("mobile_two_finger_inspect", checked)));
		addSwitchRow(content, R.drawable.ic_keyboard_24, R.string.volume_up_soft_keyboard_switch, R.string.volume_up_soft_keyboard_hint, settings.optBoolean("android_volume_up_soft_keyboard", false), checked -> repository.saveSetting(root -> root.put("android_volume_up_soft_keyboard", checked)));
		return card;
	}

	private View buildSystemCard(JSONObject settings) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_dashboard_24, R.string.settings_system_title, R.string.settings_system_subtitle, null));
		addSpinnerRow(content, R.drawable.ic_rocket_launch_24, R.string.launcher_startup_behavior_title, buildLauncherStartupBehaviorLabels(), findStringIndex(LAUNCHER_STARTUP_VALUES, ExtraSettingsPreferences.getLauncherStartupBehavior(context)), position -> ExtraSettingsPreferences.setLauncherStartupBehavior(context, LAUNCHER_STARTUP_VALUES[position]));
		addSwitchDetailsRow(content, R.drawable.ic_bolt_24, R.string.preload_switch, R.string.preload_hint, settings.optBoolean("preload_enabled", true), checked -> repository.saveSetting(root -> root.put("preload_enabled", checked)), this::showPreloadAdvancedBottomSheet);
		addSwitchRow(content, R.drawable.ic_extension_24, R.string.android_compat_pack_enabled_switch, R.string.android_compat_pack_enabled_hint, settings.optBoolean(ExtraSettingsRepository.KEY_ANDROID_COMPAT_PACK_ENABLED, true), checked -> repository.saveSetting(root -> root.put(ExtraSettingsRepository.KEY_ANDROID_COMPAT_PACK_ENABLED, checked)));
		addSwitchRow(content, R.drawable.ic_groups_24, R.string.lan_multiplayer_enabled_switch, R.string.lan_multiplayer_enabled_help, settings.optBoolean("lan_multiplayer_enabled", true), checked -> repository.saveSetting(root -> root.put("lan_multiplayer_enabled", checked)));
		MaterialButton clearTextureCache = ExtraSettingsUi.outlineButton(context, R.string.clear_texture_cache, R.drawable.ic_layers_24);
		clearTextureCache.setOnClickListener(v -> actions.requestClearTextureCache());
		ExtraSettingsUi.addSmallSpacing(content, clearTextureCache);
		addSwitchRow(content, R.drawable.ic_volume_up_24, R.string.audio_compatibility_switch, R.string.audio_compatibility_hint, settings.optBoolean("audio_compatibility_mode", false), checked -> repository.saveSetting(root -> root.put("audio_compatibility_mode", checked)));
		addSwitchRow(content, R.drawable.ic_mood_24, R.string.show_mobile_emoji_button_switch, R.string.show_mobile_emoji_button_help, settings.optBoolean("show_mobile_emoji_button", true), checked -> repository.saveSetting(root -> root.put("show_mobile_emoji_button", checked)));
		addSwitchRow(content, R.drawable.ic_restart_alt_24, R.string.quick_sl_enabled_switch, R.string.quick_sl_enabled_help, settings.optBoolean("quick_sl_enabled", true), checked -> repository.saveSetting(root -> root.put("quick_sl_enabled", checked)));
		addSwitchRow(content, R.drawable.ic_groups_24, R.string.max_multiplayer_enabled_switch, R.string.max_multiplayer_enabled_help, settings.optBoolean("max_multiplayer_enabled", true), checked -> repository.saveSetting(root -> root.put("max_multiplayer_enabled", checked)));

		MaterialButton files = ExtraSettingsUi.outlineButton(context, R.string.view_files, R.drawable.ic_folder_24);
		files.setOnClickListener(v -> actions.openFileBrowser());
		ExtraSettingsUi.addSmallSpacing(content, files);
		return card;
	}

	private View buildLogCard(JSONObject settings) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_article_24, R.string.section_log, R.string.section_log_subtitle, infoButton(R.string.section_log, R.string.log_level_info)));
		addSpinnerRow(content, R.drawable.ic_tune_24, R.string.log_level, buildLogLevelLabels(), findStringIndex(LOG_LEVEL_VALUES, repository.getConfiguredLogLevel(settings)), position -> repository.saveLogLevel(LOG_LEVEL_VALUES[position]));
		MaterialButton logs = ExtraSettingsUi.outlineButton(context, R.string.view_logs, R.drawable.ic_article_24);
		logs.setOnClickListener(v -> actions.openLogViewer());
		ExtraSettingsUi.addSmallSpacing(content, logs);
		return card;
	}

	private View buildLanCard(JSONObject settings) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_groups_24, R.string.section_lan, R.string.settings_lan_subtitle, infoButton(R.string.section_lan, R.string.lan_compatibility_mod_list_help)));
		MaterialButton compatibilityList = ExtraSettingsUi.tonalButton(context, R.string.lan_compatibility_mod_list_button, R.drawable.ic_list_24);
		compatibilityList.setOnClickListener(v -> showLanCompatibilityModListDialog());
		ExtraSettingsUi.addSmallSpacing(content, compatibilityList);
		addSwitchRow(content, R.drawable.ic_badge_24, R.string.lan_custom_player_id_switch, R.string.lan_custom_player_id_help, settings.optBoolean("lan_use_custom_player_id", false), checked -> repository.saveSetting(root -> root.put("lan_use_custom_player_id", checked)));
		addSwitchRow(content, R.drawable.ic_badge_24, R.string.lan_custom_platform_player_id_switch, R.string.lan_custom_platform_player_id_help, settings.optBoolean("lan_use_custom_platform_player_id", false), checked -> repository.saveSetting(root -> root.put("lan_use_custom_platform_player_id", checked)));
		customLanPlayerIdInput = new EditText(context);
		customLanPlayerIdInput.setHint(R.string.lan_custom_player_id_hint);
		customLanPlayerIdInput.setSingleLine(true);
		customLanPlayerIdInput.setInputType(InputType.TYPE_CLASS_TEXT);
		customLanPlayerIdInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
		customLanPlayerIdInput.setText(settings.optString("lan_custom_player_id", ""));
		customLanPlayerIdInput.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		customLanPlayerIdInput.setHintTextColor(ExtraSettingsUi.COLOR_MUTED);
		customLanPlayerIdInput.setOnFocusChangeListener((view, hasFocus) -> { if (!hasFocus) persistCustomLanPlayerIdInput(); });
		customLanPlayerIdInput.setOnEditorActionListener((view, actionId, event) -> {
			boolean shouldPersist = actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
			if (shouldPersist) {
				persistCustomLanPlayerIdInput();
			}
			return false;
		});
		ExtraSettingsUi.addSmallSpacing(content, customLanPlayerIdInput);
		return card;
	}

	private void addSpinnerRow(LinearLayout parent, int iconRes, int labelRes, List<String> labels, int selectedIndex, SettingOperation operation) {
		if (labels == null || labels.isEmpty()) {
			return;
		}
		int safeIndex = Math.max(0, Math.min(selectedIndex, labels.size() - 1));
		final int[] currentIndex = new int[] { safeIndex };
		MaterialCardView rowCard = ExtraSettingsUi.clickableCard(context);
		rowCard.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		rowCard.setStrokeWidth(0);
		rowCard.setRadius(ExtraSettingsUi.dp(context, 16));
		LinearLayout rowContent = ExtraSettingsUi.cardContent(context, rowCard);
		rowContent.setPadding(ExtraSettingsUi.dp(context, 14), ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 14), ExtraSettingsUi.dp(context, 12));
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 22));
		row.addView(ExtraSettingsUi.label(context, labelRes), labelParams());
		TextView valueView = ExtraSettingsUi.text(context, labels.get(safeIndex), 14, ExtraSettingsUi.COLOR_PRIMARY, Typeface.BOLD);
		valueView.setSingleLine(true);
		valueView.setEllipsize(TextUtils.TruncateAt.END);
		valueView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
		row.addView(valueView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f));
		ImageView chevron = ExtraSettingsUi.icon(context, R.drawable.ic_expand_more_24, ExtraSettingsUi.COLOR_PRIMARY, 20);
		LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 20), ExtraSettingsUi.dp(context, 20));
		chevronParams.setMarginStart(ExtraSettingsUi.dp(context, 4));
		row.addView(chevron, chevronParams);
		rowContent.addView(row);
		rowCard.setOnClickListener(v -> showChoiceBottomSheet(labelRes, buildChoiceOptions(labelRes, labels), currentIndex[0], position -> {
			operation.apply(position);
			currentIndex[0] = position;
		}, valueView));
		ExtraSettingsUi.addSmallSpacing(parent, rowCard);
	}

	private List<ChoiceOption> buildChoiceOptions(int labelRes, List<String> labels) {
		if (labelRes == R.string.section_renderer && labels.size() >= 2) {
			return Arrays.asList(
				new ChoiceOption(labels.get(0), context.getString(R.string.choice_sheet_renderer_opengl_desc), R.drawable.ic_layers_24),
				new ChoiceOption(labels.get(1), context.getString(R.string.choice_sheet_renderer_vulkan_desc), R.drawable.ic_speed_24)
			);
		}
		if (labelRes == R.string.launcher_startup_behavior_title && labels.size() >= 2) {
			return Arrays.asList(
				new ChoiceOption(labels.get(0), context.getString(R.string.choice_sheet_launcher_settings_desc), R.drawable.ic_settings_24),
				new ChoiceOption(labels.get(1), context.getString(R.string.choice_sheet_launcher_game_desc), R.drawable.ic_rocket_launch_24)
			);
		}
		if (labelRes == R.string.log_level && labels.size() >= 4) {
			return Arrays.asList(
				new ChoiceOption(labels.get(0), context.getString(R.string.choice_sheet_log_off_desc), R.drawable.ic_close_24),
				new ChoiceOption(labels.get(1), context.getString(R.string.choice_sheet_log_info_desc), R.drawable.ic_article_24),
				new ChoiceOption(labels.get(2), context.getString(R.string.choice_sheet_log_debug_desc), R.drawable.ic_tune_24),
				new ChoiceOption(labels.get(3), context.getString(R.string.choice_sheet_log_very_debug_desc), R.drawable.ic_speed_24)
			);
		}
		if (labelRes == R.string.mobile_tooltip_mode_title && labels.size() >= 3) {
			return Arrays.asList(
				new ChoiceOption(labels.get(0), context.getString(R.string.choice_sheet_mobile_tooltip_immediate_desc), R.drawable.ic_info_24),
				new ChoiceOption(labels.get(1), context.getString(R.string.choice_sheet_mobile_tooltip_long_press_desc), R.drawable.ic_touch_app_24),
				new ChoiceOption(labels.get(2), context.getString(R.string.choice_sheet_mobile_tooltip_hidden_desc), R.drawable.ic_close_24)
			);
		}
		if (labelRes == R.string.preload_vfx_mode_title && labels.size() >= 3) {
			return Arrays.asList(
				new ChoiceOption(labels.get(0), context.getString(R.string.choice_sheet_preload_vfx_off_desc), R.drawable.ic_close_24),
				new ChoiceOption(labels.get(1), context.getString(R.string.choice_sheet_preload_vfx_hot_desc), R.drawable.ic_auto_awesome_24),
				new ChoiceOption(labels.get(2), context.getString(R.string.choice_sheet_preload_vfx_full_desc), R.drawable.ic_high_quality_24)
			);
		}
		if (labelRes == R.string.preload_shader_mode_title && labels.size() >= 2) {
			return Arrays.asList(
				new ChoiceOption(labels.get(0), context.getString(R.string.choice_sheet_preload_shader_off_desc), R.drawable.ic_close_24),
				new ChoiceOption(labels.get(1), context.getString(R.string.choice_sheet_preload_shader_load_desc), R.drawable.ic_build_24)
			);
		}
		List<ChoiceOption> options = new ArrayList<>();
		for (String label : labels) {
			options.add(new ChoiceOption(label, null, 0));
		}
		return options;
	}

	private void showChoiceBottomSheet(int titleRes, List<ChoiceOption> options, int selectedIndex, SettingOperation operation, TextView valueView) {
		BottomSheetDialog dialog = new BottomSheetDialog(context);
		dialog.setContentView(buildChoiceSheetContent(dialog, titleRes, options, Math.max(0, Math.min(selectedIndex, options.size() - 1)), operation, valueView));
		configureBottomSheetWindow(dialog);
		dialog.show();
	}

	private View buildChoiceSheetContent(BottomSheetDialog dialog, int titleRes, List<ChoiceOption> options, int selectedIndex, SettingOperation operation, TextView valueView) {
		ScrollView scrollView = bottomSheetScrollView();
		LinearLayout content = ExtraSettingsUi.vertical(context);
		int horizontalPadding = ExtraSettingsUi.dp(context, 12);
		content.setPadding(horizontalPadding, ExtraSettingsUi.dp(context, 12), horizontalPadding, ExtraSettingsUi.dp(context, 24));
		scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		addBottomSheetHandle(content);
		TextView title = ExtraSettingsUi.text(context, titleRes, 20, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		titleParams.setMargins(ExtraSettingsUi.dp(context, 12), 0, ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 8));
		content.addView(title, titleParams);
		for (int i = 0; i < options.size(); i++) {
			ChoiceOption option = options.get(i);
			View row = choiceSheetRow(option, i == selectedIndex, () -> {
				try {
					int position = options.indexOf(option);
					operation.apply(position);
					valueView.setText(option.title);
					actions.showMessage(context.getString(R.string.status_settings_saved));
					dialog.dismiss();
				} catch (Exception exception) {
					actions.showError(exception);
				}
			});
			content.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		}
		return scrollView;
	}

	private View choiceSheetRow(ChoiceOption option, boolean selected, Runnable onClick) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setMinimumHeight(ExtraSettingsUi.dp(context, TextUtils.isEmpty(option.subtitle) ? 56 : 72));
		row.setPadding(ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 10), ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 10));
		GradientDrawable background = new GradientDrawable();
		background.setColor(selected ? Color.argb(28, 166, 211, 183) : Color.TRANSPARENT);
		background.setCornerRadius(ExtraSettingsUi.dp(context, 12));
		row.setBackground(background);
		ExtraSettingsUi.applyRipple(row);

		RadioButton radio = new RadioButton(context);
		radio.setChecked(selected);
		radio.setClickable(false);
		radio.setButtonTintList(new ColorStateList(
			new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
			new int[] { ExtraSettingsUi.COLOR_PRIMARY, ExtraSettingsUi.COLOR_OUTLINE }
		));
		boolean simple = option.iconRes == 0 && TextUtils.isEmpty(option.subtitle);
		if (simple) {
			row.addView(radio);
		} else if (option.iconRes != 0) {
			ImageView icon = ExtraSettingsUi.icon(context, option.iconRes, selected ? ExtraSettingsUi.COLOR_PRIMARY : ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 24);
			LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 24), ExtraSettingsUi.dp(context, 24));
			iconParams.setMarginEnd(ExtraSettingsUi.dp(context, 16));
			row.addView(icon, iconParams);
		}

		LinearLayout textColumn = ExtraSettingsUi.vertical(context);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(simple ? ExtraSettingsUi.dp(context, 12) : 0);
		row.addView(textColumn, textParams);
		textColumn.addView(ExtraSettingsUi.text(context, option.title, 16, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.NORMAL));
		if (!TextUtils.isEmpty(option.subtitle)) {
			TextView subtitle = ExtraSettingsUi.text(context, option.subtitle, 12, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, Typeface.NORMAL);
			subtitle.setLineSpacing(ExtraSettingsUi.dp(context, 2), 1.0f);
			LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			subtitleParams.topMargin = ExtraSettingsUi.dp(context, 4);
			textColumn.addView(subtitle, subtitleParams);
		}
		if (!simple) {
			row.addView(radio);
		}
		row.setOnClickListener(v -> onClick.run());
		return row;
	}

	private ScrollView bottomSheetScrollView() {
		ScrollView scrollView = new ScrollView(context);
		scrollView.setFillViewport(false);
		GradientDrawable background = new GradientDrawable();
		background.setColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		float radius = ExtraSettingsUi.dp(context, 28);
		background.setCornerRadii(new float[] { radius, radius, radius, radius, 0, 0, 0, 0 });
		scrollView.setBackground(background);
		return scrollView;
	}

	private void addBottomSheetHandle(LinearLayout content) {
		View handle = new View(context);
		GradientDrawable handleBackground = new GradientDrawable();
		handleBackground.setColor(ExtraSettingsUi.COLOR_OUTLINE);
		handleBackground.setCornerRadius(ExtraSettingsUi.dp(context, 2));
		handle.setBackground(handleBackground);
		LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 32), ExtraSettingsUi.dp(context, 4));
		handleParams.gravity = Gravity.CENTER_HORIZONTAL;
		handleParams.bottomMargin = ExtraSettingsUi.dp(context, 18);
		content.addView(handle, handleParams);
	}

	private void configureBottomSheetWindow(BottomSheetDialog dialog) {
		dialog.setOnShowListener(unused -> {
			Window window = dialog.getWindow();
			if (window != null) {
				window.setDimAmount(0.48f);
				window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
			}
		});
	}

	private void addSwitchRow(LinearLayout parent, int iconRes, int titleRes, int hintRes, boolean checked, BoolSettingOperation operation) {
		MaterialCardView rowCard = ExtraSettingsUi.clickableCard(context);
		rowCard.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		rowCard.setStrokeWidth(0);
		rowCard.setRadius(ExtraSettingsUi.dp(context, 16));
		LinearLayout rowContent = ExtraSettingsUi.cardContent(context, rowCard);
		rowContent.setPadding(ExtraSettingsUi.dp(context, 14), ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 14), ExtraSettingsUi.dp(context, 12));
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 22));
		LinearLayout textColumn = ExtraSettingsUi.vertical(context);
		row.addView(textColumn, labelParams());
		textColumn.addView(ExtraSettingsUi.label(context, titleRes));
		if (hintRes != 0) {
			textColumn.addView(ExtraSettingsUi.caption(context, context.getString(hintRes)));
		}
		MaterialSwitch switchView = new MaterialSwitch(context);
		switchView.setChecked(checked);
		switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
			try {
				operation.apply(isChecked);
				actions.showMessage(context.getString(R.string.status_settings_saved));
			} catch (Exception exception) {
				actions.showError(exception);
			}
		});
		row.addView(switchView);
		rowContent.addView(row);
		rowCard.setOnClickListener(v -> switchView.setChecked(!switchView.isChecked()));
		ExtraSettingsUi.addSmallSpacing(parent, rowCard);
	}

	private void addSwitchDetailsRow(LinearLayout parent, int iconRes, int titleRes, int hintRes, boolean checked, BoolSettingOperation operation, View.OnClickListener detailsClickListener) {
		MaterialCardView rowCard = ExtraSettingsUi.clickableCard(context);
		rowCard.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		rowCard.setStrokeWidth(0);
		rowCard.setRadius(ExtraSettingsUi.dp(context, 16));
		LinearLayout rowContent = ExtraSettingsUi.cardContent(context, rowCard);
		rowContent.setPadding(ExtraSettingsUi.dp(context, 14), ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 14), ExtraSettingsUi.dp(context, 12));
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 22));
		LinearLayout textColumn = ExtraSettingsUi.vertical(context);
		row.addView(textColumn, labelParams());
		textColumn.addView(ExtraSettingsUi.label(context, titleRes));
		if (hintRes != 0) {
			textColumn.addView(ExtraSettingsUi.caption(context, context.getString(hintRes)));
		}
		MaterialSwitch switchView = new MaterialSwitch(context);
		switchView.setChecked(checked);
		switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
			try {
				operation.apply(isChecked);
				actions.showMessage(context.getString(R.string.status_settings_saved));
			} catch (Exception exception) {
				actions.showError(exception);
			}
		});
		MaterialButton details = ExtraSettingsUi.iconButton(context, R.drawable.ic_chevron_right_24);
		details.setContentDescription(context.getString(R.string.preload_advanced_title));
		details.setOnClickListener(detailsClickListener);
		row.addView(switchView);
		row.addView(details);
		rowContent.addView(row);
		rowCard.setOnClickListener(v -> switchView.setChecked(!switchView.isChecked()));
		ExtraSettingsUi.addSmallSpacing(parent, rowCard);
	}

	private LinearLayout.LayoutParams labelParams() {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(context, 12));
		return params;
	}

	private LinearLayout.LayoutParams weighted(int marginStartDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(context, marginStartDp));
		return params;
	}

	private MaterialButton infoButton(int titleRes, int messageRes) {
		MaterialButton button = ExtraSettingsUi.iconButton(context, R.drawable.ic_info_24);
		button.setOnClickListener(v -> ExtraSettingsUi.showInfoDialog(context, titleRes, messageRes));
		return button;
	}

	private String detectGraphicsPreset(JSONObject settings) {
		if (ExtraSettingsRepository.GRAPHICS_PRESET_CUSTOM.equals(settings.optString("android_graphics_preset", ""))) {
			return ExtraSettingsRepository.GRAPHICS_PRESET_CUSTOM;
		}
		String renderer = RendererPreference.getSelectedRenderer(context);
		String vsync = settings.optString("vsync", "off");
		int msaa = settings.optInt("msaa", 2);
		boolean shaderCompat = settings.optBoolean("shader_compatibility_mode", false);
		if (RendererPreference.RENDERER_OPENGL_ES3.equals(renderer) && msaa == 0 && shaderCompat && "off".equals(vsync)) {
			return ExtraSettingsRepository.GRAPHICS_PRESET_COMPATIBILITY;
		}
		if (RendererPreference.RENDERER_VULKAN.equals(renderer) && msaa == 2 && !shaderCompat && "on".equals(vsync)) {
			return ExtraSettingsRepository.GRAPHICS_PRESET_QUALITY;
		}
		if (RendererPreference.RENDERER_OPENGL_ES3.equals(renderer) && msaa == 2 && !shaderCompat && "off".equals(vsync)) {
			return ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED;
		}
		return ExtraSettingsRepository.GRAPHICS_PRESET_CUSTOM;
	}

	private String detectDisplayPreset(JSONObject settings) {
		if (ExtraSettingsRepository.DISPLAY_PRESET_CUSTOM.equals(settings.optString("android_display_preset", ""))) {
			return ExtraSettingsRepository.DISPLAY_PRESET_CUSTOM;
		}
		int[] size = repository.getVector(settings, "fullscreen_render_size", 0, 0);
		float scale = (float) settings.optDouble("global_scale", 1.0);
		int fontScale = settings.optInt("ui_font_scale_percent", 100);
		if (Math.abs(scale - 1.1f) < 0.01f && fontScale == 160 && size[0] == 0 && size[1] == 0) {
			return ExtraSettingsRepository.DISPLAY_PRESET_MOBILE;
		}
		if (Math.abs(scale - 1.0f) < 0.01f && fontScale == 100 && size[0] == 0 && size[1] == 0) {
			return ExtraSettingsRepository.DISPLAY_PRESET_ORIGINAL;
		}
		return ExtraSettingsRepository.DISPLAY_PRESET_CUSTOM;
	}

	private String detectOperationPreset(JSONObject settings) {
		boolean confirm = settings.optBoolean("mobile_selection_confirmation", true);
		boolean text = settings.optBoolean("show_more_hand_card_text", true);
		boolean preview = settings.optBoolean("touch_lift_preview", true);
		if (confirm && text && preview) {
			return ExtraSettingsRepository.OPERATION_PRESET_TOUCH;
		}
		if (!confirm && !text && !preview) {
			return ExtraSettingsRepository.OPERATION_PRESET_ORIGINAL;
		}
		return "custom";
	}

	private void applyGraphicsPreset(String preset) {
		actions.runAsyncOperation(context.getString(R.string.status_settings_saved), () -> {
			repository.applyGraphicsPreset(preset);
			return context.getString(R.string.status_settings_saved);
		});
	}

	private void applyDisplayPreset(String preset) {
		actions.runAsyncOperation(context.getString(R.string.status_settings_saved), () -> {
			repository.applyDisplayPreset(preset);
			return context.getString(R.string.status_settings_saved);
		});
	}

	private void applyOperationPreset(String preset) {
		actions.runAsyncOperation(context.getString(R.string.status_settings_saved), () -> {
			repository.applyOperationPreset(preset);
			return context.getString(R.string.status_settings_saved);
		});
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

	private void confirmModSaveTransfer(boolean sourceIsModded) {
		String sourceLabel = context.getString(sourceIsModded ? R.string.mod_save_bucket_modded : R.string.mod_save_bucket_normal);
		String targetLabel = context.getString(sourceIsModded ? R.string.mod_save_bucket_normal : R.string.mod_save_bucket_modded);
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.mod_save_transfer_confirm_title)
			.setMessage(context.getString(R.string.mod_save_transfer_confirm_message, sourceLabel, targetLabel))
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> actions.runAsyncOperation(context.getString(R.string.status_busy_mod_save_transfer), () -> repository.transferModSaveProfiles(sourceIsModded)))
			.show();
	}

	private void showPreloadAdvancedBottomSheet(View anchor) {
		try {
			BottomSheetDialog dialog = new BottomSheetDialog(context);
			dialog.setContentView(buildPreloadAdvancedSheetContent(dialog, repository.loadSettingsJson()));
			configureBottomSheetWindow(dialog);
			dialog.show();
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	private View buildPreloadAdvancedSheetContent(BottomSheetDialog dialog, JSONObject settings) {
		ScrollView scrollView = bottomSheetScrollView();
		LinearLayout content = ExtraSettingsUi.vertical(context);
		int horizontalPadding = ExtraSettingsUi.dp(context, 20);
		content.setPadding(horizontalPadding, ExtraSettingsUi.dp(context, 12), horizontalPadding, ExtraSettingsUi.dp(context, 28));
		scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		addBottomSheetHandle(content);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_bolt_24, R.string.preload_advanced_title, R.string.preload_advanced_subtitle, null));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(context, R.string.preload_advanced_master_note));
		addSwitchRow(content, R.drawable.ic_layers_24, R.string.preload_startup_common_switch, R.string.preload_startup_common_hint, settings.optBoolean("preload_startup_common_enabled", true), checked -> repository.saveSetting(root -> root.put("preload_startup_common_enabled", checked)));
		addSwitchRow(content, R.drawable.ic_dashboard_24, R.string.preload_startup_main_menu_switch, R.string.preload_startup_main_menu_hint, settings.optBoolean("preload_startup_main_menu_enabled", true), checked -> repository.saveSetting(root -> root.put("preload_startup_main_menu_enabled", checked)));
		addSwitchRow(content, R.drawable.ic_rocket_launch_24, R.string.preload_menu_hotspots_switch, R.string.preload_menu_hotspots_hint, settings.optBoolean("preload_menu_hotspots_enabled", false), checked -> repository.saveSetting(root -> root.put("preload_menu_hotspots_enabled", checked)));
		addSpinnerRow(content, R.drawable.ic_auto_awesome_24, R.string.preload_vfx_mode_title, buildVfxPreloadLabels(), findStringIndex(VFX_PRELOAD_VALUES, settings.optString("preload_vfx_mode", "off")), position -> repository.saveSetting(root -> root.put("preload_vfx_mode", VFX_PRELOAD_VALUES[position])));
		addSwitchRow(content, R.drawable.ic_speed_24, R.string.preload_combat_code_switch, R.string.preload_combat_code_hint, settings.optBoolean("preload_combat_code_enabled", false), checked -> repository.saveSetting(root -> root.put("preload_combat_code_enabled", checked)));
		addSpinnerRow(content, R.drawable.ic_build_24, R.string.preload_shader_mode_title, buildShaderPreloadLabels(), findStringIndex(SHADER_PRELOAD_VALUES, settings.optString("preload_shader_mode", "off")), position -> repository.saveSetting(root -> root.put("preload_shader_mode", SHADER_PRELOAD_VALUES[position])));
		addSwitchRow(content, R.drawable.ic_sync_24, R.string.preload_runtime_switch, R.string.preload_runtime_hint, settings.optBoolean("preload_runtime_enabled", true), checked -> repository.saveSetting(root -> root.put("preload_runtime_enabled", checked)));
		LinearLayout buttons = ExtraSettingsUi.horizontal(context);
		MaterialButton reset = ExtraSettingsUi.outlineButton(context, R.string.preload_restore_defaults, R.drawable.ic_restart_alt_24);
		MaterialButton close = ExtraSettingsUi.tonalButton(context, android.R.string.ok, 0);
		reset.setOnClickListener(v -> {
			try {
				repository.resetPreloadAdvancedDefaults();
				actions.showMessage(context.getString(R.string.preload_defaults_restored));
				dialog.dismiss();
			} catch (Exception exception) {
				actions.showError(exception);
			}
		});
		close.setOnClickListener(v -> dialog.dismiss());
		buttons.addView(reset, weighted(0));
		buttons.addView(close, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, buttons);
		return scrollView;
	}

	private void showCustomScaleDialog() {
		final float currentScale;
		try {
			currentScale = (float) repository.loadSettingsJson().optDouble("global_scale", 1.0);
		} catch (Exception exception) {
			actions.showError(exception);
			return;
		}
		EditText input = new EditText(context);
		input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
		input.setText(String.format(Locale.US, "%.2f", currentScale));
		input.setSelection(input.getText().length());
		new MaterialAlertDialogBuilder(context)
			.setTitle(R.string.scale_custom_title)
			.setMessage(R.string.scale_custom_message)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				try {
					String rawValue = input.getText().toString().trim().replace(',', '.');
					float customScale = Float.parseFloat(rawValue);
					if (customScale < 0.5f || customScale > 4.0f) {
						throw new IllegalArgumentException(context.getString(R.string.scale_custom_range));
					}
					repository.saveSetting(root -> root.put("global_scale", customScale));
					actions.showMessage(context.getString(R.string.status_settings_saved));
				} catch (Exception exception) {
					actions.showError(exception);
				}
			})
			.show();
	}

	private void showLanCompatibilityModListDialog() {
		try {
			List<String> modNames = repository.loadLanCompatibilityModNames(repository.loadSettingsJson());
			LinearLayout content = ExtraSettingsUi.vertical(context);
			content.setPadding(ExtraSettingsUi.dp(context, 20), ExtraSettingsUi.dp(context, 8), ExtraSettingsUi.dp(context, 20), 0);
			LinearLayout list = ExtraSettingsUi.vertical(context);
			MaterialButton add = ExtraSettingsUi.tonalButton(context, R.string.lan_compatibility_mod_list_add, R.drawable.ic_add_circle_24);
			add.setOnClickListener(v -> showLanCompatibilityModEntryDialog(R.string.lan_compatibility_mod_list_add_title, "", value -> {
				if (repository.containsLanCompatibilityModName(modNames, value)) {
					throw new IllegalArgumentException(context.getString(R.string.lan_compatibility_mod_list_duplicate));
				}
				modNames.add(value);
				repository.saveLanCompatibilityModNames(modNames);
				refreshLanCompatibilityList(list, modNames);
				actions.refreshCurrentScreen();
			}));
			content.addView(ExtraSettingsUi.body(context, R.string.lan_compatibility_mod_list_dialog_message));
			ExtraSettingsUi.addSmallSpacing(content, add);
			ExtraSettingsUi.addSmallSpacing(content, list);
			refreshLanCompatibilityList(list, modNames);
			new MaterialAlertDialogBuilder(context)
				.setTitle(R.string.lan_compatibility_mod_list_dialog_title)
				.setView(content)
				.setPositiveButton(android.R.string.ok, null)
				.show();
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	private void refreshLanCompatibilityList(LinearLayout list, List<String> modNames) {
		list.removeAllViews();
		if (modNames.isEmpty()) {
			list.addView(ExtraSettingsUi.body(context, R.string.lan_compatibility_mod_list_empty));
			return;
		}
		for (String name : new ArrayList<>(modNames)) {
			LinearLayout row = ExtraSettingsUi.horizontal(context);
			row.addView(ExtraSettingsUi.body(context, name), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
			MaterialButton edit = ExtraSettingsUi.iconButton(context, R.drawable.ic_edit_24);
			MaterialButton delete = ExtraSettingsUi.iconButton(context, R.drawable.ic_delete_24);
			edit.setOnClickListener(v -> showLanCompatibilityModEntryDialog(R.string.lan_compatibility_mod_list_edit_title, name, value -> {
				if (!name.equals(value) && repository.containsLanCompatibilityModName(modNames, value)) {
					throw new IllegalArgumentException(context.getString(R.string.lan_compatibility_mod_list_duplicate));
				}
				int index = modNames.indexOf(name);
				if (index >= 0) {
					modNames.set(index, value);
					repository.saveLanCompatibilityModNames(modNames);
					refreshLanCompatibilityList(list, modNames);
					actions.refreshCurrentScreen();
				}
			}));
			delete.setOnClickListener(v -> {
				try {
					modNames.remove(name);
					repository.saveLanCompatibilityModNames(modNames);
					refreshLanCompatibilityList(list, modNames);
					actions.refreshCurrentScreen();
				} catch (Exception exception) {
					actions.showError(exception);
				}
			});
			row.addView(edit);
			row.addView(delete);
			ExtraSettingsUi.addSmallSpacing(list, row);
		}
	}

	private void showLanCompatibilityModEntryDialog(int titleResId, String initialValue, ThrowingStringConsumer consumer) {
		EditText input = new EditText(context);
		input.setHint(R.string.lan_compatibility_mod_list_item_hint);
		input.setInputType(InputType.TYPE_CLASS_TEXT);
		input.setSingleLine(true);
		input.setText(initialValue);
		input.setSelection(input.getText().length());
		AlertDialog dialog = new MaterialAlertDialogBuilder(context)
			.setTitle(titleResId)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, null)
			.create();
		dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			try {
				String value = repository.normalizeLanCompatibilityModName(input.getText() == null ? "" : input.getText().toString());
				if (TextUtils.isEmpty(value)) {
					throw new IllegalArgumentException(context.getString(R.string.lan_compatibility_mod_list_required));
				}
				consumer.accept(value);
				dialog.dismiss();
			} catch (Exception exception) {
				actions.showError(exception);
			}
		}));
		dialog.show();
	}

	private void persistCustomLanPlayerIdInput() {
		if (customLanPlayerIdInput == null) {
			return;
		}
		String customPlayerId = customLanPlayerIdInput.getText() == null ? "" : customLanPlayerIdInput.getText().toString().trim();
		try {
			repository.saveSetting(root -> root.put("lan_custom_player_id", customPlayerId));
			actions.showMessage(context.getString(R.string.status_settings_saved));
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	private List<String> buildResolutionLabels() {
		List<String> labels = new ArrayList<>();
		for (ResolutionOption option : RESOLUTION_OPTIONS) {
			labels.add(option.width == 0 || option.height == 0 ? context.getString(R.string.resolution_auto) : option.width + " x " + option.height);
		}
		return labels;
	}

	private List<String> buildAspectLabels() {
		return Arrays.asList(
			context.getString(R.string.aspect_ratio_auto),
			"16:9",
			"16:10",
			"21:9",
			"4:3"
		);
	}

	private List<String> buildMsaaLabels() {
		List<String> labels = new ArrayList<>();
		for (int option : MSAA_OPTIONS) {
			labels.add(option == 0 ? context.getString(R.string.msaa_off) : context.getString(R.string.msaa_x_format, option));
		}
		return labels;
	}

	private List<String> buildVsyncLabels() {
		return Arrays.asList(context.getString(R.string.vsync_off), context.getString(R.string.vsync_on), context.getString(R.string.vsync_adaptive));
	}

	private List<String> buildLogLevelLabels() {
		return Arrays.asList(
			context.getString(R.string.log_level_off_option),
			context.getString(R.string.log_level_info_option),
			context.getString(R.string.log_level_debug_option),
			context.getString(R.string.log_level_very_debug_option)
		);
	}

	private List<String> buildTooltipModeLabels() {
		return Arrays.asList(
			context.getString(R.string.mobile_tooltip_mode_immediate),
			context.getString(R.string.mobile_tooltip_mode_long_press),
			context.getString(R.string.mobile_tooltip_mode_hidden)
		);
	}

	private List<String> buildLauncherStartupBehaviorLabels() {
		return Arrays.asList(
			context.getString(R.string.launcher_startup_behavior_settings_option),
			context.getString(R.string.launcher_startup_behavior_game_option)
		);
	}

	private List<String> buildVfxPreloadLabels() {
		return Arrays.asList(
			context.getString(R.string.preload_vfx_mode_off),
			context.getString(R.string.preload_vfx_mode_hot),
			context.getString(R.string.preload_vfx_mode_full)
		);
	}

	private List<String> buildShaderPreloadLabels() {
		return Arrays.asList(
			context.getString(R.string.preload_shader_mode_off),
			context.getString(R.string.preload_shader_mode_load_resources)
		);
	}

	private List<String> buildScaleLabels(float currentScale) {
		List<String> labels = new ArrayList<>();
		for (ScaleOption option : SCALE_OPTIONS) {
			if (option.isCustom()) {
				labels.add(String.format(Locale.US, "%s (%.2fx)", context.getString(R.string.scale_custom), currentScale));
			} else if (Math.abs(option.scale - 1.0f) < 0.01f) {
				labels.add(context.getString(R.string.scale_default));
			} else {
				labels.add(String.format(Locale.US, "%.1fx", option.scale));
			}
		}
		return labels;
	}

	private List<String> buildFontScaleLabels() {
		List<String> labels = new ArrayList<>();
		for (int percent : FONT_SCALE_OPTIONS) {
			labels.add(percent + "%");
		}
		return labels;
	}

	private int findResolutionSelection(JSONObject settings) {
		int[] size = repository.getVector(settings, "fullscreen_render_size", 0, 0);
		for (int i = 0; i < RESOLUTION_OPTIONS.size(); i++) {
			ResolutionOption option = RESOLUTION_OPTIONS.get(i);
			if (option.width == size[0] && option.height == size[1]) {
				return i;
			}
		}
		return 0;
	}

	private int findScaleSelection(JSONObject settings) {
		float scale = (float) settings.optDouble("global_scale", 1.0);
		for (int i = 0; i < SCALE_OPTIONS.size(); i++) {
			ScaleOption option = SCALE_OPTIONS.get(i);
			if (!option.isCustom() && Math.abs(option.scale - scale) < 0.01f) {
				return i;
			}
		}
		return SCALE_OPTIONS.size() - 1;
	}

	private int findStringIndex(String[] values, String current) {
		for (int i = 0; i < values.length; i++) {
			if (values[i].equals(current)) {
				return i;
			}
		}
		return 0;
	}

	private int findIntIndex(int[] values, int current) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] == current) {
				return i;
			}
		}
		return 0;
	}

	private View errorCard(Exception exception) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_error_outline_24, R.string.error_operation_failed, 0, null));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(context, exception.getMessage() == null ? exception.toString() : exception.getMessage()));
		return card;
	}

	private interface SettingOperation { void apply(int position) throws Exception; }
	private interface BoolSettingOperation { void apply(boolean checked) throws Exception; }
	private interface ThrowingStringConsumer { void accept(String value) throws Exception; }

	private static final class ChoiceOption {
		final String title;
		final String subtitle;
		final int iconRes;
		ChoiceOption(String title, String subtitle, int iconRes) {
			this.title = title == null ? "" : title;
			this.subtitle = subtitle;
			this.iconRes = iconRes;
		}
	}

	private static final class ResolutionOption {
		final int width;
		final int height;
		ResolutionOption(int width, int height) { this.width = width; this.height = height; }
	}

	private static final class ScaleOption {
		final float scale;
		ScaleOption(float scale) { this.scale = scale; }
		boolean isCustom() { return scale < 0f; }
	}
}
