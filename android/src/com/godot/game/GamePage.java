package com.godot.game;

import android.content.Context;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.util.List;

public final class GamePage {
	private final Context context;
	private final ExtraSettingsRepository repository;
	private final PayloadManager payloadManager;
	private final CompatPackManager compatPackManager;
	private final ExtraSettingsActions actions;

	public GamePage(Context context, ExtraSettingsRepository repository, ExtraSettingsActions actions) {
		this.context = context;
		this.repository = repository;
		this.payloadManager = new PayloadManager(context);
		this.compatPackManager = new CompatPackManager(context);
		this.actions = actions;
	}

	public View build() {
		FrameLayout frame = new FrameLayout(context);
		frame.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		ScrollView scrollView = new ScrollView(context);
		scrollView.setFillViewport(false);
		LinearLayout root = ExtraSettingsUi.vertical(context);
		int padding = ExtraSettingsUi.dp(context, 20);
		root.setPadding(padding, ExtraSettingsUi.dp(context, 24), padding, ExtraSettingsUi.dp(context, 112));
		scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		root.addView(ExtraSettingsUi.title(context, R.string.tab_game));

		try {
			root.addView(buildStatusCard(), cardParams(root));
			root.addView(buildPayloadCard(), cardParams(root));
			root.addView(buildSaveCard(), cardParams(root));
			root.addView(buildQuickActionsCard(), cardParams(root));
		} catch (Exception exception) {
			root.addView(buildErrorCard(exception), cardParams(root));
		}

		frame.addView(scrollView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		MaterialButton launchButton = ExtraSettingsUi.filledButton(context, R.string.launch_game, R.drawable.ic_rocket_launch_24);
		launchButton.setTextColor(android.graphics.Color.WHITE);
		launchButton.setIconTint(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
		launchButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(42, 132, 73)));
		launchButton.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_END);
		launchButton.setOnClickListener(v -> actions.launchGame());
		FrameLayout.LayoutParams launchParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ExtraSettingsUi.dp(context, 56), Gravity.BOTTOM | Gravity.END);
		launchParams.setMargins(0, 0, ExtraSettingsUi.dp(context, 20), ExtraSettingsUi.dp(context, 20));
		frame.addView(launchButton, launchParams);
		return frame;
	}

	private LinearLayout.LayoutParams cardParams(LinearLayout root) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(context, root.getChildCount() <= 2 ? 20 : 14);
		return params;
	}

	private View buildStatusCard() throws Exception {
		JSONObject settings = repository.loadSettingsJson();
		List<ExtraSettingsRepository.ModEntry> mods = repository.listInstalledModManifests();
		int enabledMods = repository.getEnabledModCount(settings, mods);
		String renderer = RendererPreference.RENDERER_OPENGL_ES3.equals(RendererPreference.getSelectedRenderer(context))
			? context.getString(R.string.renderer_option_opengl_es3)
			: context.getString(R.string.renderer_option_vulkan);
		String aspect = settings.optString("aspect_ratio", "auto");
		String vsync = settings.optString("vsync", "off");
		int msaa = settings.optInt("msaa", 2);

		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_controller_24, R.string.game_status_title, R.string.game_status_subtitle, null));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_extension_24, context.getString(R.string.game_mod_count_format, mods.size(), enabledMods)));
		PayloadManager.Status payloadStatus = payloadManager.getStatus();
		boolean compatEnabled = compatPackManager.isCompatPackEnabled();
		CompatPackManager.CompatPack selectedCompat = compatPackManager.getSelectedPack();
		CompatPackManager.CompatPack matchedCompat = compatEnabled ? compatPackManager.findBestMatch(payloadStatus.manifest) : null;
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_tune_24, context.getString(R.string.game_graphics_summary_format, renderer, formatMsaa(msaa), formatVsync(vsync), formatAspect(aspect))));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(payloadStatus.ready ? R.drawable.ic_check_circle_24 : R.drawable.ic_error_outline_24, payloadStatus.ready ? context.getString(R.string.payload_status_ready_short, payloadStatus.shortVersionLabel()) : context.getString(R.string.payload_status_missing_short)));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(compatEnabled && selectedCompat != null && selectedCompat.ready ? R.drawable.ic_layers_24 : R.drawable.ic_error_outline_24, compatEnabled ? (selectedCompat != null && selectedCompat.ready ? context.getString(R.string.game_compat_status_format, selectedCompat.displayName, selectedCompat.targetLabel()) : context.getString(R.string.version_manager_no_compat_selected)) : context.getString(R.string.game_compat_disabled_status)));
		if (matchedCompat != null && (selectedCompat == null || !matchedCompat.packId.equals(selectedCompat.packId))) {
			ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, context.getString(R.string.version_manager_match_hint, matchedCompat.displayName)));
		}
		MaterialButton launch = ExtraSettingsUi.tonalButton(context, R.string.launch_game, R.drawable.ic_rocket_launch_24);
		launch.setOnClickListener(v -> actions.launchGame());
		ExtraSettingsUi.addSmallSpacing(content, launch);
		return card;
	}

	private View buildPayloadCard() {
		PayloadManager.Status status = payloadManager.getStatus();
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_download_24, R.string.payload_card_title, R.string.payload_card_subtitle, null));
		if (status.ready) {
			ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_check_circle_24, context.getString(R.string.payload_status_ready, status.shortVersionLabel())));
			ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_folder_24, status.gameDir.getAbsolutePath()));
			ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_article_24, context.getString(R.string.payload_stats_format, status.fileCount, Formatter.formatFileSize(context, status.totalBytes), Formatter.formatFileSize(context, status.pckSize), Formatter.formatFileSize(context, status.dllSize))));
			if (status.sourceSha256 != null && !status.sourceSha256.isEmpty()) {
				ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, context.getString(R.string.payload_source_format, status.sourceKind, status.sourceName, status.sourceSha256)));
			}
		} else {
			ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_error_outline_24, context.getString(R.string.payload_status_missing, status.message)));
			ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, status.gameDir.getAbsolutePath()));
		}

		LinearLayout row = ExtraSettingsUi.horizontal(context);
		MaterialButton importZip = ExtraSettingsUi.tonalButton(context, R.string.import_game_payload, R.drawable.ic_upload_file_24);
		importZip.setOnClickListener(v -> actions.requestImportGamePayload());
		MaterialButton clear = ExtraSettingsUi.outlineButton(context, R.string.clear_game_payload, R.drawable.ic_delete_24);
		clear.setEnabled(status.ready);
		clear.setOnClickListener(v -> actions.requestClearGamePayload());
		LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		right.setMarginStart(ExtraSettingsUi.dp(context, 10));
		row.addView(importZip, left);
		row.addView(clear, right);
		ExtraSettingsUi.addSmallSpacing(content, row);

		if (payloadManager.hasBundledPayload()) {
			MaterialButton bundled = ExtraSettingsUi.outlineButton(context, R.string.extract_bundled_payload, R.drawable.ic_download_24);
			bundled.setOnClickListener(v -> actions.requestExtractBundledPayload());
			ExtraSettingsUi.addSmallSpacing(content, bundled);
		}
		return card;
	}

	private View buildSaveCard() {
		ExtraSettingsRepository.SaveStatus status = repository.getSaveStatus();
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_save_24, R.string.save_status_title, R.string.save_status_subtitle, null));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_folder_24, context.getString(R.string.save_status_profile_format, status.normalProfiles, status.moddedProfiles, status.formattedBytes)));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, status.accountRoot.getAbsolutePath()));

		LinearLayout row = ExtraSettingsUi.horizontal(context);
		MaterialButton export = ExtraSettingsUi.outlineButton(context, R.string.export_save, R.drawable.ic_download_24);
		MaterialButton importSave = ExtraSettingsUi.tonalButton(context, R.string.import_save, R.drawable.ic_upload_file_24);
		export.setOnClickListener(v -> actions.requestExportSave());
		importSave.setOnClickListener(v -> actions.requestImportSave());
		LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		right.setMarginStart(ExtraSettingsUi.dp(context, 10));
		row.addView(export, left);
		row.addView(importSave, right);
		ExtraSettingsUi.addSmallSpacing(content, row);
		return card;
	}

	private View buildQuickActionsCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_dashboard_24, R.string.game_quick_actions_title, R.string.game_quick_actions_subtitle, null));

		LinearLayout row1 = ExtraSettingsUi.horizontal(context);
		MaterialButton settings = ExtraSettingsUi.tonalButton(context, R.string.tab_settings, R.drawable.ic_settings_24);
		MaterialButton versions = ExtraSettingsUi.outlineButton(context, R.string.tab_versions, R.drawable.ic_layers_24);
		settings.setOnClickListener(v -> actions.openSettingsTab());
		versions.setOnClickListener(v -> actions.openVersionsTab());
		LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		b.setMarginStart(ExtraSettingsUi.dp(context, 10));
		row1.addView(settings, a);
		row1.addView(versions, b);
		ExtraSettingsUi.addSmallSpacing(content, row1);

		LinearLayout row2 = ExtraSettingsUi.horizontal(context);
		MaterialButton logs = ExtraSettingsUi.outlineButton(context, R.string.view_logs, R.drawable.ic_article_24);
		MaterialButton files = ExtraSettingsUi.outlineButton(context, R.string.view_files, R.drawable.ic_folder_24);
		logs.setOnClickListener(v -> actions.openLogViewer());
		files.setOnClickListener(v -> actions.openFileBrowser());
		LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		LinearLayout.LayoutParams d = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		d.setMarginStart(ExtraSettingsUi.dp(context, 10));
		row2.addView(logs, c);
		row2.addView(files, d);
		ExtraSettingsUi.addSmallSpacing(content, row2);
		return card;
	}

	private View buildErrorCard(Exception exception) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_error_outline_24, R.string.error_operation_failed, 0, null));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(context, exception.getMessage() == null ? exception.toString() : exception.getMessage()));
		return card;
	}

	private View metricRow(int iconRes, String text) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_PRIMARY, 22));
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(context, 12));
		row.addView(ExtraSettingsUi.body(context, text), textParams);
		return row;
	}

	private String formatMsaa(int msaa) {
		return msaa <= 0 ? context.getString(R.string.msaa_off) : context.getString(R.string.msaa_x_format, msaa);
	}

	private String formatVsync(String vsync) {
		if ("on".equals(vsync)) {
			return context.getString(R.string.vsync_on);
		}
		if ("adaptive".equals(vsync)) {
			return context.getString(R.string.vsync_adaptive);
		}
		return context.getString(R.string.vsync_off);
	}

	private String formatAspect(String aspect) {
		if ("auto".equals(aspect)) {
			return context.getString(R.string.aspect_ratio_auto);
		}
		if ("sixteen_by_nine".equals(aspect)) {
			return "16:9";
		}
		if ("sixteen_by_ten".equals(aspect)) {
			return "16:10";
		}
		if ("twenty_one_by_nine".equals(aspect)) {
			return "21:9";
		}
		if ("four_by_three".equals(aspect)) {
			return "4:3";
		}
		return aspect;
	}
}
