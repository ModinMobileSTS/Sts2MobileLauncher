package com.godot.game;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

public final class WelcomeSetupPage {
	public interface Listener {
		void onWelcomeCompleted(boolean launchGame);
	}

	private static final int STEP_INTRO = 0;
	private static final int STEP_RENDER = 1;
	private static final int STEP_DISPLAY = 2;
	private static final int STEP_SAVE = 3;
	private static final int STEP_OPERATION = 4;
	private static final int STEP_DONE = 5;
	private static final int STEP_COUNT = 6;

	private final Context context;
	private final ExtraSettingsRepository repository;
	private final ExtraSettingsActions actions;
	private final Listener listener;

	private FrameLayout contentFrame;
	private TextView progressText;
	private MaterialButton backButton;
	private MaterialButton nextButton;

	private MaterialCardView graphicsRecommendedCard;
	private MaterialCardView graphicsQualityCard;
	private MaterialCardView graphicsCompatibilityCard;
	private MaterialCardView graphicsCustomCard;
	private MaterialCardView aspectAutoCard;
	private MaterialCardView displayOriginalCard;
	private MaterialCardView displayMobileCard;
	private MaterialCardView displayCustomCard;
	private MaterialCardView operationTouchCard;
	private MaterialCardView operationOriginalCard;
	private LinearLayout graphicsDetailsList;

	private int stepIndex = STEP_INTRO;
	private String selectedGraphicsPreset = ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED;
	private String selectedDisplayPreset = ExtraSettingsRepository.DISPLAY_PRESET_ORIGINAL;
	private String selectedOperationPreset = ExtraSettingsRepository.OPERATION_PRESET_TOUCH;
	private boolean aspectAutoSelected = true;

	public WelcomeSetupPage(Context context, ExtraSettingsRepository repository, ExtraSettingsActions actions, Listener listener) {
		this.context = context;
		this.repository = repository;
		this.actions = actions;
		this.listener = listener;
		initializeSelectionsFromSavedSettings();
	}

	public View build() {
		LinearLayout shell = ExtraSettingsUi.vertical(context);
		shell.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		contentFrame = new FrameLayout(context);
		shell.addView(contentFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		FrameLayout bottomFrame = new FrameLayout(context);
		bottomFrame.setBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		LinearLayout bottom = ExtraSettingsUi.vertical(context);
		bottom.setPadding(0, ExtraSettingsUi.dp(context, 12), 0, ExtraSettingsUi.dp(context, 16));
		progressText = ExtraSettingsUi.caption(context, "");
		bottom.addView(progressText);
		LinearLayout buttons = ExtraSettingsUi.horizontal(context);
		backButton = ExtraSettingsUi.outlineButton(context, R.string.welcome_action_back, 0);
		nextButton = ExtraSettingsUi.filledButton(context, R.string.welcome_action_next, R.drawable.ic_arrow_forward_24);
		backButton.setOnClickListener(v -> goBack());
		nextButton.setOnClickListener(v -> goNext());
		LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		nextParams.setMarginStart(ExtraSettingsUi.dp(context, 12));
		buttons.addView(backButton, backParams);
		buttons.addView(nextButton, nextParams);
		ExtraSettingsUi.addSmallSpacing(bottom, buttons);
		bottomFrame.setPadding(ExtraSettingsUi.pageHorizontalPadding(context), 0, ExtraSettingsUi.pageHorizontalPadding(context), 0);
		bottomFrame.addView(bottom, ExtraSettingsUi.centeredContentParams(context));
		shell.addView(bottomFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		renderCurrentStep();
		return shell;
	}

	private void initializeSelectionsFromSavedSettings() {
		try {
			JSONObject settings = repository.loadSettingsJson();
			selectedGraphicsPreset = detectGraphicsPreset(settings);
			selectedDisplayPreset = detectDisplayPreset(settings);
			selectedOperationPreset = detectOperationPreset(settings);
			aspectAutoSelected = "auto".equals(settings.optString("aspect_ratio", "auto"));
		} catch (Exception exception) {
			selectedGraphicsPreset = ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED;
			selectedDisplayPreset = ExtraSettingsRepository.DISPLAY_PRESET_ORIGINAL;
			selectedOperationPreset = ExtraSettingsRepository.OPERATION_PRESET_TOUCH;
			aspectAutoSelected = true;
		}
	}

	private void renderCurrentStep() {
		if (contentFrame == null) {
			return;
		}
		contentFrame.removeAllViews();
		contentFrame.addView(scrollForStep(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		if (progressText != null) {
			progressText.setText(context.getString(R.string.welcome_step_format, stepIndex + 1, STEP_COUNT));
		}
		if (backButton != null) {
			backButton.setEnabled(stepIndex > STEP_INTRO);
		}
		if (nextButton != null) {
			nextButton.setText(stepIndex == STEP_DONE ? R.string.welcome_enter_settings : R.string.welcome_action_next);
			MaterialSymbols.applyButtonIcon(nextButton, stepIndex == STEP_DONE ? R.drawable.ic_settings_24 : R.drawable.ic_arrow_forward_24, ColorStateList.valueOf(ExtraSettingsUi.COLOR_ON_PRIMARY), 24);
		}
	}

	private ScrollView scrollForStep() {
		ScrollView scrollView = new ScrollView(context);
		scrollView.setFillViewport(false);
		scrollView.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		LinearLayout root = ExtraSettingsUi.vertical(context);
		root.setPadding(0, ExtraSettingsUi.dp(context, 28), 0, ExtraSettingsUi.dp(context, 28));
		ExtraSettingsUi.addResponsiveScrollContent(context, scrollView, root);
		switch (stepIndex) {
			case STEP_RENDER -> buildRenderStep(root);
			case STEP_DISPLAY -> buildDisplayStep(root);
			case STEP_SAVE -> buildSaveStep(root);
			case STEP_OPERATION -> buildOperationStep(root);
			case STEP_DONE -> buildDoneStep(root);
			default -> buildIntroStep(root);
		}
		return scrollView;
	}

	private void buildIntroStep(LinearLayout root) {
		root.addView(ExtraSettingsUi.title(context, R.string.welcome_title));
		ExtraSettingsUi.addSmallSpacing(root, ExtraSettingsUi.body(context, R.string.welcome_intro_body));
		ExtraSettingsUi.addSmallSpacing(root, ExtraSettingsUi.body(context, R.string.welcome_intro_note));
	}

	private void buildRenderStep(LinearLayout root) {
		root.addView(ExtraSettingsUi.title(context, R.string.welcome_render_title));
		ExtraSettingsUi.addSmallSpacing(root, ExtraSettingsUi.body(context, R.string.welcome_render_subtitle));
		ExtraSettingsUi.addCardSpacing(root, buildRenderCard());
	}

	private View buildRenderCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(
			context,
			R.drawable.ic_layers_24,
			R.string.welcome_render_title,
			R.string.welcome_render_card_subtitle,
			infoButton(R.string.welcome_render_title, R.string.welcome_graphics_info)
		));

		graphicsRecommendedCard = ExtraSettingsUi.choiceCard(context, R.drawable.ic_auto_awesome_24, R.string.graphics_preset_recommended, R.string.graphics_preset_recommended_desc, ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED.equals(selectedGraphicsPreset));
		graphicsQualityCard = ExtraSettingsUi.choiceCard(context, R.drawable.ic_high_quality_24, R.string.graphics_preset_quality, R.string.graphics_preset_quality_desc, ExtraSettingsRepository.GRAPHICS_PRESET_QUALITY.equals(selectedGraphicsPreset));
		graphicsCompatibilityCard = ExtraSettingsUi.choiceCard(context, R.drawable.ic_build_24, R.string.graphics_preset_compatibility, R.string.graphics_preset_compatibility_desc, ExtraSettingsRepository.GRAPHICS_PRESET_COMPATIBILITY.equals(selectedGraphicsPreset));
		graphicsCustomCard = ExtraSettingsUi.choiceCard(context, R.drawable.ic_tune_24, R.string.preset_custom, R.string.preset_custom_desc, ExtraSettingsRepository.GRAPHICS_PRESET_CUSTOM.equals(selectedGraphicsPreset));
		graphicsRecommendedCard.setOnClickListener(v -> selectGraphicsPreset(ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED));
		graphicsQualityCard.setOnClickListener(v -> selectGraphicsPreset(ExtraSettingsRepository.GRAPHICS_PRESET_QUALITY));
		graphicsCompatibilityCard.setOnClickListener(v -> selectGraphicsPreset(ExtraSettingsRepository.GRAPHICS_PRESET_COMPATIBILITY));
		graphicsCustomCard.setOnClickListener(v -> selectGraphicsPreset(ExtraSettingsRepository.GRAPHICS_PRESET_CUSTOM));
		ExtraSettingsUi.addWeightedCardsRow(context, content, graphicsRecommendedCard, graphicsQualityCard);
		ExtraSettingsUi.addWeightedCardsRow(context, content, graphicsCompatibilityCard, graphicsCustomCard);

		graphicsDetailsList = ExtraSettingsUi.vertical(context);
		ExtraSettingsUi.addSmallSpacing(content, graphicsDetailsList);
		refreshGraphicsDetails();

		aspectAutoCard = ExtraSettingsUi.choiceCard(context, R.drawable.ic_aspect_ratio_24, R.string.aspect_ratio_auto, R.string.aspect_ratio_auto_desc, aspectAutoSelected);
		aspectAutoCard.setOnClickListener(v -> {
			try {
				repository.applyAspectRatio("auto");
				aspectAutoSelected = true;
				ExtraSettingsUi.setChoiceSelected(aspectAutoCard, true);
			} catch (Exception exception) {
				actions.showError(exception);
			}
		});
		ExtraSettingsUi.addSmallSpacing(content, aspectAutoCard);
		return card;
	}

	private void buildDisplayStep(LinearLayout root) {
		root.addView(ExtraSettingsUi.title(context, R.string.welcome_display_title));
		ExtraSettingsUi.addSmallSpacing(root, ExtraSettingsUi.body(context, R.string.welcome_display_subtitle));
		ExtraSettingsUi.addCardSpacing(root, buildDisplayCard());
	}

	private View buildDisplayCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(
			context,
			R.drawable.ic_desktop_windows_24,
			R.string.welcome_display_title,
			R.string.welcome_display_card_subtitle,
			null
		));
		displayOriginalCard = ExtraSettingsUi.choiceCard(context, R.drawable.ic_desktop_windows_24, R.string.display_preset_original, R.string.display_preset_original_desc, ExtraSettingsRepository.DISPLAY_PRESET_ORIGINAL.equals(selectedDisplayPreset));
		displayMobileCard = ExtraSettingsUi.choiceCard(context, R.drawable.ic_phone_android_24, R.string.display_preset_mobile, R.string.display_preset_mobile_desc, ExtraSettingsRepository.DISPLAY_PRESET_MOBILE.equals(selectedDisplayPreset));
		displayCustomCard = ExtraSettingsUi.choiceCard(context, R.drawable.ic_tune_24, R.string.preset_custom, R.string.preset_custom_desc, ExtraSettingsRepository.DISPLAY_PRESET_CUSTOM.equals(selectedDisplayPreset));
		displayOriginalCard.setOnClickListener(v -> selectDisplayPreset(ExtraSettingsRepository.DISPLAY_PRESET_ORIGINAL));
		displayMobileCard.setOnClickListener(v -> selectDisplayPreset(ExtraSettingsRepository.DISPLAY_PRESET_MOBILE));
		displayCustomCard.setOnClickListener(v -> selectDisplayPreset(ExtraSettingsRepository.DISPLAY_PRESET_CUSTOM));
		ExtraSettingsUi.addWeightedCardsRow(context, content, displayMobileCard);
		ExtraSettingsUi.addWeightedCardsRow(context, content, displayOriginalCard, displayCustomCard);
		return card;
	}

	private void buildSaveStep(LinearLayout root) {
		root.addView(ExtraSettingsUi.title(context, R.string.welcome_save_title));
		ExtraSettingsUi.addSmallSpacing(root, ExtraSettingsUi.body(context, R.string.welcome_save_subtitle));
		ExtraSettingsUi.addCardSpacing(root, buildSaveCard());
	}

	private View buildSaveCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(
			context,
			R.drawable.ic_save_24,
			R.string.welcome_save_title,
			R.string.welcome_save_subtitle,
			null
		));
		MaterialButton importSave = ExtraSettingsUi.tonalButton(context, R.string.import_save, R.drawable.ic_upload_file_24);
		importSave.setOnClickListener(v -> actions.requestImportSave());
		ExtraSettingsUi.addSmallSpacing(content, importSave);
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(context, R.string.welcome_save_skip_hint));
		return card;
	}

	private void buildOperationStep(LinearLayout root) {
		root.addView(ExtraSettingsUi.title(context, R.string.welcome_operation_title));
		ExtraSettingsUi.addSmallSpacing(root, ExtraSettingsUi.body(context, R.string.welcome_operation_subtitle));
		ExtraSettingsUi.addCardSpacing(root, buildOperationCard());
	}

	private View buildOperationCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(
			context,
			R.drawable.ic_touch_app_24,
			R.string.welcome_operation_title,
			R.string.welcome_operation_subtitle,
			infoButton(R.string.welcome_operation_title, R.string.welcome_operation_info)
		));
		operationTouchCard = ExtraSettingsUi.choiceCard(context, R.drawable.ic_touch_app_24, R.string.operation_preset_touch, R.string.operation_preset_touch_desc, ExtraSettingsRepository.OPERATION_PRESET_TOUCH.equals(selectedOperationPreset));
		operationOriginalCard = ExtraSettingsUi.choiceCard(context, R.drawable.ic_gamepad_24, R.string.operation_preset_original, R.string.operation_preset_original_desc, ExtraSettingsRepository.OPERATION_PRESET_ORIGINAL.equals(selectedOperationPreset));
		operationTouchCard.setOnClickListener(v -> selectOperationPreset(ExtraSettingsRepository.OPERATION_PRESET_TOUCH));
		operationOriginalCard.setOnClickListener(v -> selectOperationPreset(ExtraSettingsRepository.OPERATION_PRESET_ORIGINAL));
		ExtraSettingsUi.addWeightedCardsRow(context, content, operationTouchCard, operationOriginalCard);
		return card;
	}

	private void buildDoneStep(LinearLayout root) {
		root.addView(ExtraSettingsUi.title(context, R.string.welcome_done_title));
		ExtraSettingsUi.addSmallSpacing(root, ExtraSettingsUi.body(context, R.string.welcome_done_subtitle));
		ExtraSettingsUi.addCardSpacing(root, buildDoneCard());
	}

	private View buildDoneCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(
			context,
			R.drawable.ic_check_circle_24,
			R.string.welcome_done_title,
			R.string.welcome_done_subtitle,
			null
		));
		MaterialButton launchGame = ExtraSettingsUi.filledButton(context, R.string.launch_game, R.drawable.ic_rocket_launch_24);
		launchGame.setOnClickListener(v -> complete(true));
		ExtraSettingsUi.addSmallSpacing(content, launchGame);
		return card;
	}

	private MaterialButton infoButton(int titleRes, int messageRes) {
		MaterialButton button = ExtraSettingsUi.iconButton(context, R.drawable.ic_info_24);
		button.setOnClickListener(v -> ExtraSettingsUi.showInfoDialog(context, titleRes, messageRes));
		return button;
	}

	private void refreshGraphicsDetails() {
		if (graphicsDetailsList == null) {
			return;
		}
		graphicsDetailsList.removeAllViews();
		graphicsDetailsList.addView(ExtraSettingsUi.label(context, R.string.welcome_graphics_details_title));
		if (ExtraSettingsRepository.GRAPHICS_PRESET_QUALITY.equals(selectedGraphicsPreset)) {
			ExtraSettingsUi.addSmallSpacing(graphicsDetailsList, detailRow(R.drawable.ic_layers_24, R.string.graphics_details_quality_renderer));
			ExtraSettingsUi.addSmallSpacing(graphicsDetailsList, detailRow(R.drawable.ic_sync_24, R.string.graphics_details_quality_sync));
		} else if (ExtraSettingsRepository.GRAPHICS_PRESET_COMPATIBILITY.equals(selectedGraphicsPreset)) {
			ExtraSettingsUi.addSmallSpacing(graphicsDetailsList, detailRow(R.drawable.ic_layers_24, R.string.graphics_details_compat_renderer));
			ExtraSettingsUi.addSmallSpacing(graphicsDetailsList, detailRow(R.drawable.ic_blur_on_24, R.string.graphics_details_compat_msaa));
			ExtraSettingsUi.addSmallSpacing(graphicsDetailsList, detailRow(R.drawable.ic_build_24, R.string.graphics_details_compat_shader));
		} else if (ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED.equals(selectedGraphicsPreset)) {
			ExtraSettingsUi.addSmallSpacing(graphicsDetailsList, detailRow(R.drawable.ic_layers_24, R.string.graphics_details_recommended_renderer));
			ExtraSettingsUi.addSmallSpacing(graphicsDetailsList, detailRow(R.drawable.ic_blur_on_24, R.string.graphics_details_recommended_msaa));
			ExtraSettingsUi.addSmallSpacing(graphicsDetailsList, detailRow(R.drawable.ic_sync_24, R.string.graphics_details_recommended_sync));
		} else {
			ExtraSettingsUi.addSmallSpacing(graphicsDetailsList, detailRow(R.drawable.ic_tune_24, R.string.graphics_details_custom));
		}
	}

	private View detailRow(int iconRes, int textRes) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_PRIMARY, 20));
		TextView text = ExtraSettingsUi.body(context, textRes);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(context, 10));
		row.addView(text, params);
		return row;
	}

	private void selectGraphicsPreset(String preset) {
		selectedGraphicsPreset = preset;
		try {
			repository.applyGraphicsPreset(preset);
			ExtraSettingsUi.setChoiceSelected(graphicsRecommendedCard, ExtraSettingsRepository.GRAPHICS_PRESET_RECOMMENDED.equals(preset));
			ExtraSettingsUi.setChoiceSelected(graphicsQualityCard, ExtraSettingsRepository.GRAPHICS_PRESET_QUALITY.equals(preset));
			ExtraSettingsUi.setChoiceSelected(graphicsCompatibilityCard, ExtraSettingsRepository.GRAPHICS_PRESET_COMPATIBILITY.equals(preset));
			ExtraSettingsUi.setChoiceSelected(graphicsCustomCard, ExtraSettingsRepository.GRAPHICS_PRESET_CUSTOM.equals(preset));
			refreshGraphicsDetails();
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	private void selectDisplayPreset(String preset) {
		selectedDisplayPreset = preset;
		try {
			repository.applyDisplayPreset(preset);
			ExtraSettingsUi.setChoiceSelected(displayOriginalCard, ExtraSettingsRepository.DISPLAY_PRESET_ORIGINAL.equals(preset));
			ExtraSettingsUi.setChoiceSelected(displayMobileCard, ExtraSettingsRepository.DISPLAY_PRESET_MOBILE.equals(preset));
			ExtraSettingsUi.setChoiceSelected(displayCustomCard, ExtraSettingsRepository.DISPLAY_PRESET_CUSTOM.equals(preset));
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	private void selectOperationPreset(String preset) {
		selectedOperationPreset = preset;
		try {
			repository.applyOperationPreset(preset);
			ExtraSettingsUi.setChoiceSelected(operationTouchCard, ExtraSettingsRepository.OPERATION_PRESET_TOUCH.equals(preset));
			ExtraSettingsUi.setChoiceSelected(operationOriginalCard, ExtraSettingsRepository.OPERATION_PRESET_ORIGINAL.equals(preset));
		} catch (Exception exception) {
			actions.showError(exception);
		}
	}

	private void goBack() {
		if (stepIndex > STEP_INTRO) {
			stepIndex--;
			renderCurrentStep();
		}
	}

	private void goNext() {
		if (stepIndex < STEP_DONE) {
			stepIndex++;
			renderCurrentStep();
		} else {
			complete(false);
		}
	}

	private void complete(boolean launchGame) {
		try {
			repository.applyGraphicsPreset(selectedGraphicsPreset);
			repository.applyDisplayPreset(selectedDisplayPreset);
			if (ExtraSettingsRepository.OPERATION_PRESET_TOUCH.equals(selectedOperationPreset) || ExtraSettingsRepository.OPERATION_PRESET_ORIGINAL.equals(selectedOperationPreset)) {
				repository.applyOperationPreset(selectedOperationPreset);
			}
			ExtraSettingsPreferences.setFirstRunSetupCompleted(context, true);
			listener.onWelcomeCompleted(launchGame);
		} catch (Exception exception) {
			actions.showError(exception);
		}
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
}
