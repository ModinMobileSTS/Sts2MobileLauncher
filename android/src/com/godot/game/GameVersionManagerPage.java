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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class GameVersionManagerPage {
	private final Context context;
	private final PayloadManager payloadManager;
	private final CompatPackManager compatPackManager;
	private final GameBodyVersionManager gameBodyVersionManager;
	private final ExtraSettingsActions actions;

	public GameVersionManagerPage(Context context, ExtraSettingsActions actions) {
		this.context = context;
		this.payloadManager = new PayloadManager(context);
		this.compatPackManager = new CompatPackManager(context);
		this.gameBodyVersionManager = new GameBodyVersionManager(context);
		this.actions = actions;
	}

	public View build() {
		FrameLayout frame = new FrameLayout(context);
		frame.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		ScrollView scrollView = new ScrollView(context);
		LinearLayout root = ExtraSettingsUi.vertical(context);
		int padding = ExtraSettingsUi.dp(context, 20);
		root.setPadding(padding, ExtraSettingsUi.dp(context, 24), padding, ExtraSettingsUi.dp(context, 112));
		scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		root.addView(ExtraSettingsUi.title(context, R.string.tab_versions));
		PayloadManager.Status payload = payloadManager.getStatus();
		List<CompatPackManager.CompatPack> packs = compatPackManager.listInstalledPacks();
		ExtraSettingsUi.addCardSpacing(root, buildOverviewCard(payload, packs));
		ExtraSettingsUi.addCardSpacing(root, buildGamePayloadCard(payload));
		ExtraSettingsUi.addCardSpacing(root, buildArchivedGamesCard(payload));
		ExtraSettingsUi.addCardSpacing(root, buildCompatPacksCard(packs));

		frame.addView(scrollView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		return frame;
	}

	private View buildOverviewCard(PayloadManager.Status payload, List<CompatPackManager.CompatPack> packs) {
		CompatPackManager.CompatPack selected = findSelectedPack(packs);
		CompatPackManager.CompatPack matched = compatPackManager.findBestMatch(payload.manifest, packs);
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_layers_24, R.string.version_manager_title, R.string.version_manager_subtitle, null));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(payload.ready ? R.drawable.ic_check_circle_24 : R.drawable.ic_error_outline_24, payload.ready ? context.getString(R.string.version_manager_game_ready_format, payload.shortVersionLabel()) : context.getString(R.string.payload_status_missing_short)));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(selected != null && selected.ready ? R.drawable.ic_extension_24 : R.drawable.ic_error_outline_24, selected != null && selected.ready ? context.getString(R.string.version_manager_selected_compat_format, selected.displayName, selected.targetLabel()) : context.getString(R.string.version_manager_no_compat_selected)));
		if (matched != null && (selected == null || !matched.packId.equals(selected.packId))) {
			ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, context.getString(R.string.version_manager_match_hint, matched.displayName)));
			MaterialButton useMatch = ExtraSettingsUi.tonalButton(context, R.string.version_manager_use_matched_compat, R.drawable.ic_check_circle_24);
			useMatch.setOnClickListener(v -> actions.requestSelectCompatPack(matched.packId));
			ExtraSettingsUi.addSmallSpacing(content, useMatch);
		}
		return card;
	}

	private View buildGamePayloadCard(PayloadManager.Status status) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_download_24, R.string.version_manager_game_payload_title, R.string.version_manager_game_payload_subtitle, null));
		if (status.ready) {
			ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_check_circle_24, status.shortVersionLabel()));
			ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_folder_24, status.gameDir.getAbsolutePath()));
			ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_article_24, context.getString(R.string.payload_stats_format, status.fileCount, Formatter.formatFileSize(context, status.totalBytes), Formatter.formatFileSize(context, status.pckSize), Formatter.formatFileSize(context, status.dllSize))));
		} else {
			ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_error_outline_24, status.message));
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
		return card;
	}

	private View buildArchivedGamesCard(PayloadManager.Status payload) {
		List<GameBodyVersionManager.GameBodyVersion> versions = gameBodyVersionManager.listVersions();
		String selectedId = gameBodyVersionManager.getSelectedVersionId();
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_desktop_windows_24, R.string.version_manager_archived_games_title, R.string.version_manager_archived_games_subtitle, null));
		MaterialButton archiveActive = ExtraSettingsUi.tonalButton(context, R.string.archive_active_game_version, R.drawable.ic_save_24);
		archiveActive.setOnClickListener(v -> actions.requestArchiveActiveGameVersion());
		archiveActive.setEnabled(payload.ready);
		ExtraSettingsUi.addSmallSpacing(content, archiveActive);
		if (versions.isEmpty()) {
			ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(context, R.string.version_manager_no_archived_games));
			return card;
		}
		for (GameBodyVersionManager.GameBodyVersion version : versions) {
			ExtraSettingsUi.addSmallSpacing(content, buildArchivedGameRow(version, version.id.equals(selectedId)));
		}
		return card;
	}

	private View buildArchivedGameRow(GameBodyVersionManager.GameBodyVersion version, boolean selected) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(context);
		card.setStrokeColor(selected ? ExtraSettingsUi.COLOR_PRIMARY : ExtraSettingsUi.COLOR_OUTLINE);
		card.setStrokeWidth(ExtraSettingsUi.dp(context, selected ? 2 : 1));
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.sectionTitle(context, selected ? version.label + "  ✓" : version.label));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_folder_24, version.gameDir.getAbsolutePath()));
		if (version.installedAtUnix > 0) {
			ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, context.getString(R.string.version_manager_installed_at_format, formatTime(version.installedAtUnix))));
		}
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		MaterialButton select = ExtraSettingsUi.tonalButton(context, R.string.version_manager_select, R.drawable.ic_check_circle_24);
		select.setEnabled(!selected && version.ready);
		select.setOnClickListener(v -> actions.requestSelectGameVersion(version.id));
		MaterialButton delete = ExtraSettingsUi.outlineButton(context, R.string.delete, R.drawable.ic_delete_24);
		delete.setOnClickListener(v -> actions.requestDeleteGameVersion(version.id));
		LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		right.setMarginStart(ExtraSettingsUi.dp(context, 10));
		row.addView(select, left);
		row.addView(delete, right);
		ExtraSettingsUi.addSmallSpacing(content, row);
		return card;
	}

	private View buildCompatPacksCard(List<CompatPackManager.CompatPack> packs) {
		String selectedId = compatPackManager.getSelectedPackId();
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_extension_24, R.string.version_manager_compat_packs_title, R.string.version_manager_compat_packs_subtitle, null));
		LinearLayout actionsRow = ExtraSettingsUi.horizontal(context);
		MaterialButton importPack = ExtraSettingsUi.tonalButton(context, R.string.import_compat_pack, R.drawable.ic_upload_file_24);
		importPack.setOnClickListener(v -> actions.requestImportCompatPack());
		MaterialButton installBundled = ExtraSettingsUi.outlineButton(context, R.string.install_bundled_compat_packs, R.drawable.ic_download_24);
		installBundled.setOnClickListener(v -> actions.requestInstallBundledCompatPacks());
		LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		right.setMarginStart(ExtraSettingsUi.dp(context, 10));
		actionsRow.addView(importPack, left);
		actionsRow.addView(installBundled, right);
		ExtraSettingsUi.addSmallSpacing(content, actionsRow);

		if (packs.isEmpty()) {
			ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(context, R.string.version_manager_no_compat_packs));
			return card;
		}
		for (CompatPackManager.CompatPack pack : packs) {
			ExtraSettingsUi.addSmallSpacing(content, buildCompatPackRow(pack, pack.packId.equals(selectedId)));
		}
		return card;
	}

	private View buildCompatPackRow(CompatPackManager.CompatPack pack, boolean selected) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(context);
		card.setStrokeColor(selected ? ExtraSettingsUi.COLOR_PRIMARY : ExtraSettingsUi.COLOR_OUTLINE);
		card.setStrokeWidth(ExtraSettingsUi.dp(context, selected ? 2 : 1));
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.sectionTitle(context, selected ? pack.displayName + "  ✓" : pack.displayName));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_badge_24, context.getString(R.string.version_manager_compat_target_format, pack.compatVersion, pack.channel, pack.targetLabel())));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_folder_24, pack.dir.getAbsolutePath()));
		if (pack.installedAtUnix > 0) {
			ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, context.getString(R.string.version_manager_installed_at_format, formatTime(pack.installedAtUnix))));
		}
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		MaterialButton select = ExtraSettingsUi.tonalButton(context, R.string.version_manager_select, R.drawable.ic_check_circle_24);
		select.setEnabled(!selected && pack.ready);
		select.setOnClickListener(v -> actions.requestSelectCompatPack(pack.packId));
		MaterialButton delete = ExtraSettingsUi.outlineButton(context, R.string.delete, R.drawable.ic_delete_24);
		delete.setOnClickListener(v -> actions.requestDeleteCompatPack(pack.packId));
		LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		right.setMarginStart(ExtraSettingsUi.dp(context, 10));
		row.addView(select, left);
		row.addView(delete, right);
		ExtraSettingsUi.addSmallSpacing(content, row);
		return card;
	}

	private CompatPackManager.CompatPack findSelectedPack(List<CompatPackManager.CompatPack> packs) {
		String selectedId = compatPackManager.getSelectedPackId();
		if (selectedId == null || selectedId.isEmpty() || packs == null) {
			return null;
		}
		for (CompatPackManager.CompatPack pack : packs) {
			if (selectedId.equals(pack.packId)) {
				return pack;
			}
		}
		return null;
	}

	private View metricRow(int iconRes, String text) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_PRIMARY, 22));
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(context, 12));
		row.addView(ExtraSettingsUi.body(context, text == null ? "" : text), params);
		return row;
	}

	private String formatTime(long unixSeconds) {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(unixSeconds * 1000L));
	}
}
