package com.godot.game;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
		ScrollView scrollView = new ScrollView(context);
		scrollView.setFillViewport(false);
		scrollView.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		LinearLayout root = ExtraSettingsUi.vertical(context);
		int padding = ExtraSettingsUi.dp(context, 20);
		root.setPadding(padding, ExtraSettingsUi.dp(context, 24), padding, ExtraSettingsUi.dp(context, 32));
		scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		root.addView(ExtraSettingsUi.title(context, R.string.tab_settings));

		try {
			JSONObject settings = repository.loadSettingsJson();
			ExtraSettingsUi.addCardSpacing(root, buildPresetCard(settings));
			ExtraSettingsUi.addCardSpacing(root, buildGraphicsAdvancedCard(settings));
			ExtraSettingsUi.addCardSpacing(root, buildSaveCard());
			ExtraSettingsUi.addCardSpacing(root, buildInputCard(settings));
			ExtraSettingsUi.addCardSpacing(root, buildSystemCard(settings));
			ExtraSettingsUi.addCardSpacing(root, buildLanCard(settings));
			ExtraSettingsUi.addCardSpacing(root, buildFullDataBackupCard());
		} catch (Exception exception) {
			ExtraSettingsUi.addCardSpacing(root, errorCard(exception));
		}
		return scrollView;
	}

	private View buildPresetCard(JSONObject settings) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_auto_awesome_24, R.string.settings_presets_title, R.string.settings_presets_subtitle, null));
		String graphicsPreset = detectGraphicsPreset(settings);
		String displayPreset = detectDisplayPreset(settings);
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.label(context, R.string.preset_render_group_title));
		MaterialCardView recommended = ExtraSettingsUi.choiceCard(context, R.drawable.ic_auto_awesome_24, R.string.graphics_preset_recommended, R.string.graphics_preset_recommended_desc, ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED.equals(graphicsPreset));
		MaterialCardView quality = ExtraSettingsUi.choiceCard(context, R.drawable.ic_high_quality_24, R.string.graphics_preset_quality, R.string.graphics_preset_quality_desc, ExtraSettingsRepository.GRAPHICS_PRESET_QUALITY.equals(graphicsPreset));
		MaterialCardView compatibility = ExtraSettingsUi.choiceCard(context, R.drawable.ic_build_24, R.string.graphics_preset_compatibility, R.string.graphics_preset_compatibility_desc, ExtraSettingsRepository.GRAPHICS_PRESET_COMPATIBILITY.equals(graphicsPreset));
		MaterialCardView graphicsCustom = ExtraSettingsUi.choiceCard(context, R.drawable.ic_tune_24, R.string.preset_custom, R.string.preset_custom_desc, ExtraSettingsRepository.GRAPHICS_PRESET_CUSTOM.equals(graphicsPreset));
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
		ExtraSettingsUi.addWeightedCardsRow(context, content, recommended, quality);
		ExtraSettingsUi.addWeightedCardsRow(context, content, compatibility, graphicsCustom);
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.label(context, R.string.preset_display_group_title));
		MaterialCardView original = ExtraSettingsUi.choiceCard(context, R.drawable.ic_desktop_windows_24, R.string.display_preset_original, R.string.display_preset_original_desc, ExtraSettingsRepository.DISPLAY_PRESET_ORIGINAL.equals(displayPreset));
		MaterialCardView mobile = ExtraSettingsUi.choiceCard(context, R.drawable.ic_phone_android_24, R.string.display_preset_mobile, R.string.display_preset_mobile_desc, ExtraSettingsRepository.DISPLAY_PRESET_MOBILE.equals(displayPreset));
		MaterialCardView displayCustom = ExtraSettingsUi.choiceCard(context, R.drawable.ic_tune_24, R.string.preset_custom, R.string.preset_custom_desc, ExtraSettingsRepository.DISPLAY_PRESET_CUSTOM.equals(displayPreset));
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
		ExtraSettingsUi.addWeightedCardsRow(context, content, mobile);
		ExtraSettingsUi.addWeightedCardsRow(context, content, original, displayCustom);
		return card;
	}

	private void setGraphicsPresetCards(MaterialCardView recommended, MaterialCardView quality, MaterialCardView compatibility, MaterialCardView custom, String preset) {
		ExtraSettingsUi.setChoiceSelected(recommended, ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED.equals(preset));
		ExtraSettingsUi.setChoiceSelected(quality, ExtraSettingsRepository.GRAPHICS_PRESET_QUALITY.equals(preset));
		ExtraSettingsUi.setChoiceSelected(compatibility, ExtraSettingsRepository.GRAPHICS_PRESET_COMPATIBILITY.equals(preset));
		ExtraSettingsUi.setChoiceSelected(custom, ExtraSettingsRepository.GRAPHICS_PRESET_CUSTOM.equals(preset));
	}

	private void setDisplayPresetCards(MaterialCardView original, MaterialCardView mobile, MaterialCardView custom, String preset) {
		ExtraSettingsUi.setChoiceSelected(original, ExtraSettingsRepository.DISPLAY_PRESET_ORIGINAL.equals(preset));
		ExtraSettingsUi.setChoiceSelected(mobile, ExtraSettingsRepository.DISPLAY_PRESET_MOBILE.equals(preset));
		ExtraSettingsUi.setChoiceSelected(custom, ExtraSettingsRepository.DISPLAY_PRESET_CUSTOM.equals(preset));
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

	private View buildFullDataBackupCard() {
		ExtraSettingsRepository.FullDataStatus status = repository.getFullDataStatus();
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_save_24, R.string.full_data_backup_title, R.string.full_data_backup_subtitle, infoButton(R.string.full_data_backup_title, R.string.full_data_backup_info)));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_folder_24, context.getString(R.string.full_data_backup_size_format, status.formattedBytes, status.modCount)));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, status.dataRoot.getAbsolutePath()));
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

	private View buildInputCard(JSONObject settings) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_touch_app_24, R.string.section_input, R.string.settings_input_subtitle, infoButton(R.string.section_input, R.string.settings_input_info)));
		String operationPreset = detectOperationPreset(settings);
		MaterialCardView touch = ExtraSettingsUi.choiceCard(context, R.drawable.ic_touch_app_24, R.string.operation_preset_touch, R.string.operation_preset_touch_desc, ExtraSettingsRepository.OPERATION_PRESET_TOUCH.equals(operationPreset));
		MaterialCardView original = ExtraSettingsUi.choiceCard(context, R.drawable.ic_gamepad_24, R.string.operation_preset_original, R.string.operation_preset_original_desc, ExtraSettingsRepository.OPERATION_PRESET_ORIGINAL.equals(operationPreset));
		touch.setOnClickListener(v -> {
			ExtraSettingsUi.setChoiceSelected(touch, true);
			ExtraSettingsUi.setChoiceSelected(original, false);
			applyOperationPreset(ExtraSettingsRepository.OPERATION_PRESET_TOUCH);
		});
		original.setOnClickListener(v -> {
			ExtraSettingsUi.setChoiceSelected(touch, false);
			ExtraSettingsUi.setChoiceSelected(original, true);
			applyOperationPreset(ExtraSettingsRepository.OPERATION_PRESET_ORIGINAL);
		});
		ExtraSettingsUi.addWeightedCardsRow(context, content, touch, original);
		addSwitchRow(content, R.drawable.ic_check_circle_24, R.string.mobile_selection_confirmation_switch, R.string.mobile_selection_confirmation_hint, settings.optBoolean("mobile_selection_confirmation", true), checked -> repository.saveSetting(root -> root.put("mobile_selection_confirmation", checked)));
		addSwitchRow(content, R.drawable.ic_text_fields_24, R.string.show_more_hand_card_text_switch, R.string.show_more_hand_card_text_hint, settings.optBoolean("show_more_hand_card_text", true), checked -> repository.saveSetting(root -> root.put("show_more_hand_card_text", checked)));
		addSwitchRow(content, R.drawable.ic_touch_app_24, R.string.touch_lift_preview_switch, R.string.touch_lift_preview_hint, settings.optBoolean("touch_lift_preview", true), checked -> repository.saveSetting(root -> root.put("touch_lift_preview", checked)));
		addSwitchRow(content, R.drawable.ic_gesture_24, R.string.mobile_two_finger_inspect_switch, R.string.mobile_two_finger_inspect_hint, settings.optBoolean("mobile_two_finger_inspect", true), checked -> repository.saveSetting(root -> root.put("mobile_two_finger_inspect", checked)));
		addSwitchRow(content, R.drawable.ic_keyboard_24, R.string.volume_up_soft_keyboard_switch, R.string.volume_up_soft_keyboard_hint, settings.optBoolean("android_volume_up_soft_keyboard", false), checked -> repository.saveSetting(root -> root.put("android_volume_up_soft_keyboard", checked)));
		return card;
	}

	private View buildSystemCard(JSONObject settings) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_dashboard_24, R.string.settings_system_title, R.string.settings_system_subtitle, null));
		addSwitchRow(content, R.drawable.ic_bolt_24, R.string.preload_switch, R.string.preload_hint, settings.optBoolean("preload_enabled", true), checked -> repository.saveSetting(root -> root.put("preload_enabled", checked)));
		addSwitchRow(content, R.drawable.ic_extension_24, R.string.android_compat_pack_enabled_switch, R.string.android_compat_pack_enabled_hint, settings.optBoolean(ExtraSettingsRepository.KEY_ANDROID_COMPAT_PACK_ENABLED, true), checked -> repository.saveSetting(root -> root.put(ExtraSettingsRepository.KEY_ANDROID_COMPAT_PACK_ENABLED, checked)));
		MaterialButton clearTextureCache = ExtraSettingsUi.outlineButton(context, R.string.clear_texture_cache, R.drawable.ic_layers_24);
		clearTextureCache.setOnClickListener(v -> actions.requestClearTextureCache());
		ExtraSettingsUi.addSmallSpacing(content, clearTextureCache);
		addSwitchRow(content, R.drawable.ic_volume_up_24, R.string.audio_compatibility_switch, R.string.audio_compatibility_hint, settings.optBoolean("audio_compatibility_mode", false), checked -> repository.saveSetting(root -> root.put("audio_compatibility_mode", checked)));
		addSwitchRow(content, R.drawable.ic_mood_24, R.string.show_mobile_emoji_button_switch, R.string.show_mobile_emoji_button_help, settings.optBoolean("show_mobile_emoji_button", true), checked -> repository.saveSetting(root -> root.put("show_mobile_emoji_button", checked)));
		addSwitchRow(content, R.drawable.ic_restart_alt_24, R.string.quick_sl_enabled_switch, R.string.quick_sl_enabled_help, settings.optBoolean("quick_sl_enabled", true), checked -> repository.saveSetting(root -> root.put("quick_sl_enabled", checked)));
		addSwitchRow(content, R.drawable.ic_groups_24, R.string.max_multiplayer_enabled_switch, R.string.max_multiplayer_enabled_help, settings.optBoolean("max_multiplayer_enabled", true), checked -> repository.saveSetting(root -> root.put("max_multiplayer_enabled", checked)));

		LinearLayout row = ExtraSettingsUi.horizontal(context);
		MaterialButton logs = ExtraSettingsUi.outlineButton(context, R.string.view_logs, R.drawable.ic_article_24);
		MaterialButton files = ExtraSettingsUi.outlineButton(context, R.string.view_files, R.drawable.ic_folder_24);
		logs.setOnClickListener(v -> actions.openLogViewer());
		files.setOnClickListener(v -> actions.openFileBrowser());
		row.addView(logs, weighted(0));
		row.addView(files, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, row);
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
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_PRIMARY, 22));
		row.addView(ExtraSettingsUi.body(context, labelRes), labelParams());
		Spinner spinner = new Spinner(context);
		ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, labels);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spinner.setAdapter(adapter);
		spinner.setSelection(Math.max(0, Math.min(selectedIndex, labels.size() - 1)), false);
		spinner.post(() -> spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parentView, View view, int position, long id) {
				try {
					operation.apply(position);
					actions.showMessage(context.getString(R.string.status_settings_saved));
				} catch (Exception exception) {
					actions.showError(exception);
				}
			}
			@Override public void onNothingSelected(AdapterView<?> parentView) {}
		}));
		row.addView(spinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));
		ExtraSettingsUi.addSmallSpacing(parent, row);
	}

	private void addSwitchRow(LinearLayout parent, int iconRes, int titleRes, int hintRes, boolean checked, BoolSettingOperation operation) {
		MaterialCardView rowCard = ExtraSettingsUi.clickableCard(context);
		LinearLayout rowContent = ExtraSettingsUi.cardContent(context, rowCard);
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_PRIMARY, 22));
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
