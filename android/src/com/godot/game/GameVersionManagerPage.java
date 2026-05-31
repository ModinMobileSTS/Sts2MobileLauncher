package com.godot.game;

import android.content.Context;
import android.text.TextUtils;
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
	private final LaunchProfileManager launchProfileManager;
	private final ExtraSettingsActions actions;

	public GameVersionManagerPage(Context context, ExtraSettingsActions actions) {
		this.context = context;
		this.payloadManager = new PayloadManager(context);
		this.compatPackManager = new CompatPackManager(context);
		this.launchProfileManager = new LaunchProfileManager(context);
		this.actions = actions;
	}

	public View build() {
		launchProfileManager.bootstrapIfNeeded();
		FrameLayout frame = new FrameLayout(context);
		frame.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		ScrollView scrollView = new ScrollView(context);
		LinearLayout root = ExtraSettingsUi.vertical(context);
		int padding = ExtraSettingsUi.dp(context, 20);
		root.setPadding(padding, ExtraSettingsUi.dp(context, 24), padding, ExtraSettingsUi.dp(context, 112));
		scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		root.addView(ExtraSettingsUi.title(context, R.string.tab_versions));
		PayloadManager.Status payload = payloadManager.getStatus();
		List<LaunchProfileManager.GamePayload> payloads = launchProfileManager.listPayloads();
		List<LaunchProfileManager.LaunchProfile> profiles = launchProfileManager.listProfiles();
		List<CompatPackManager.CompatPack> packs = compatPackManager.listInstalledPacks();
		LaunchProfileManager.LaunchProfile selectedProfile = launchProfileManager.getSelectedProfile();
		ExtraSettingsUi.addCardSpacing(root, buildOverviewCard(selectedProfile, payload, packs));
		ExtraSettingsUi.addCardSpacing(root, buildGamePayloadCard(payload));
		ExtraSettingsUi.addCardSpacing(root, buildLaunchProfilesCard(profiles, packs, selectedProfile));
		ExtraSettingsUi.addCardSpacing(root, buildInstalledPayloadsCard(payloads, selectedProfile));
		ExtraSettingsUi.addCardSpacing(root, buildCompatPacksCard(packs));

		frame.addView(scrollView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		return frame;
	}

	private View buildOverviewCard(LaunchProfileManager.LaunchProfile profile, PayloadManager.Status payload, List<CompatPackManager.CompatPack> packs) {
		boolean compatEnabled = compatPackManager.isCompatPackEnabled();
		CompatPackManager.CompatPack selected = findSelectedPack(packs);
		CompatPackManager.CompatPack matched = compatEnabled ? compatPackManager.findBestMatch(payload.manifest, packs) : null;
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_layers_24, R.string.version_manager_title, R.string.version_manager_subtitle, null));
		if (profile != null) {
			ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_check_circle_24, context.getString(R.string.launch_profile_selected_format, profile.displayName)));
			ExtraSettingsUi.addSmallSpacing(content, metricRow(payload.ready ? R.drawable.ic_desktop_windows_24 : R.drawable.ic_error_outline_24, payload.ready ? context.getString(R.string.version_manager_game_ready_format, payload.shortVersionLabel()) : context.getString(R.string.payload_status_missing_short)));
			ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_save_24, context.getString(R.string.launch_profile_data_modes_format, modeLabel(profile.saveMode), modeLabel(profile.modsMode))));
		} else {
			ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_error_outline_24, context.getString(R.string.launch_profile_none_selected)));
		}
		ExtraSettingsUi.addSmallSpacing(content, metricRow(compatEnabled && selected != null && selected.ready ? R.drawable.ic_extension_24 : R.drawable.ic_error_outline_24, compatEnabled ? (selected != null && selected.ready ? context.getString(R.string.version_manager_selected_compat_format, selected.displayName, selected.targetLabel()) : context.getString(R.string.version_manager_no_compat_selected)) : context.getString(R.string.version_manager_compat_disabled)));
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

	private View buildLaunchProfilesCard(List<LaunchProfileManager.LaunchProfile> profiles, List<CompatPackManager.CompatPack> packs, LaunchProfileManager.LaunchProfile selectedProfile) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_gamepad_24, R.string.launch_profiles_title, R.string.launch_profiles_subtitle, null));
		if (profiles.isEmpty()) {
			ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(context, R.string.launch_profiles_empty));
			return card;
		}
		for (LaunchProfileManager.LaunchProfile profile : profiles) {
			ExtraSettingsUi.addSmallSpacing(content, buildLaunchProfileRow(profile, findPackById(packs, profile.compatPackId), selectedProfile != null && selectedProfile.id.equals(profile.id)));
		}
		return card;
	}

	private View buildLaunchProfileRow(LaunchProfileManager.LaunchProfile profile, CompatPackManager.CompatPack pack, boolean selected) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(context);
		card.setStrokeColor(selected ? ExtraSettingsUi.COLOR_PRIMARY : ExtraSettingsUi.COLOR_OUTLINE);
		card.setStrokeWidth(ExtraSettingsUi.dp(context, selected ? 2 : 1));
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.sectionTitle(context, selected ? profile.displayName + "  ✓" : profile.displayName));
		String payloadLabel = profile.payload == null ? profile.payloadId : profile.payload.label;
		ExtraSettingsUi.addSmallSpacing(content, metricRow(profile.ready ? R.drawable.ic_desktop_windows_24 : R.drawable.ic_error_outline_24, context.getString(R.string.launch_profile_payload_format, payloadLabel)));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_save_24, context.getString(R.string.launch_profile_data_modes_format, modeLabel(profile.saveMode), modeLabel(profile.modsMode))));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_extension_24, pack == null ? context.getString(R.string.version_manager_no_compat_selected) : context.getString(R.string.version_manager_selected_compat_format, pack.displayName, pack.targetLabel())));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_folder_24, profile.dir.getAbsolutePath()));
		if (profile.updatedAtUnix > 0) {
			ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, context.getString(R.string.version_manager_installed_at_format, formatTime(profile.updatedAtUnix))));
		}
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		MaterialButton select = ExtraSettingsUi.tonalButton(context, R.string.version_manager_select, R.drawable.ic_check_circle_24);
		select.setEnabled(!selected && profile.ready);
		select.setOnClickListener(v -> actions.requestSelectLaunchProfile(profile.id));
		MaterialButton edit = ExtraSettingsUi.outlineButton(context, R.string.edit, R.drawable.ic_edit_24);
		edit.setOnClickListener(v -> actions.requestEditLaunchProfile(profile.id));
		LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		right.setMarginStart(ExtraSettingsUi.dp(context, 10));
		row.addView(select, left);
		row.addView(edit, right);
		ExtraSettingsUi.addSmallSpacing(content, row);

		MaterialButton delete = ExtraSettingsUi.outlineButton(context, R.string.delete, R.drawable.ic_delete_24);
		delete.setOnClickListener(v -> actions.requestDeleteLaunchProfile(profile.id));
		ExtraSettingsUi.addSmallSpacing(content, delete);
		return card;
	}

	private View buildInstalledPayloadsCard(List<LaunchProfileManager.GamePayload> payloads, LaunchProfileManager.LaunchProfile selectedProfile) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.iconTitleRow(context, R.drawable.ic_desktop_windows_24, R.string.version_manager_archived_games_title, R.string.version_manager_archived_games_subtitle, null));
		if (payloads.isEmpty()) {
			ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(context, R.string.version_manager_no_archived_games));
			return card;
		}
		for (LaunchProfileManager.GamePayload payload : payloads) {
			ExtraSettingsUi.addSmallSpacing(content, buildPayloadRow(payload, selectedProfile != null && payload.id.equals(selectedProfile.payloadId)));
		}
		return card;
	}

	private View buildPayloadRow(LaunchProfileManager.GamePayload payload, boolean selectedPayload) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(context);
		card.setStrokeColor(selectedPayload ? ExtraSettingsUi.COLOR_PRIMARY : ExtraSettingsUi.COLOR_OUTLINE);
		card.setStrokeWidth(ExtraSettingsUi.dp(context, selectedPayload ? 2 : 1));
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.addView(ExtraSettingsUi.sectionTitle(context, selectedPayload ? payload.label + "  ✓" : payload.label));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_folder_24, payload.gameDir.getAbsolutePath()));
		ExtraSettingsUi.addSmallSpacing(content, metricRow(R.drawable.ic_article_24, context.getString(R.string.payload_stats_format, payload.fileCount, Formatter.formatFileSize(context, payload.totalBytes), Formatter.formatFileSize(context, payload.pckSize), Formatter.formatFileSize(context, payload.dllSize))));
		if (payload.installedAtUnix > 0) {
			ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.caption(context, context.getString(R.string.version_manager_installed_at_format, formatTime(payload.installedAtUnix))));
		}
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		MaterialButton use = ExtraSettingsUi.tonalButton(context, R.string.version_manager_select, R.drawable.ic_check_circle_24);
		use.setOnClickListener(v -> actions.requestSelectGameVersion(payload.id));
		MaterialButton create = ExtraSettingsUi.outlineButton(context, R.string.create_launch_profile, R.drawable.ic_add_circle_24);
		create.setOnClickListener(v -> actions.requestCreateLaunchProfile(payload.id));
		LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		right.setMarginStart(ExtraSettingsUi.dp(context, 10));
		row.addView(use, left);
		row.addView(create, right);
		ExtraSettingsUi.addSmallSpacing(content, row);
		MaterialButton delete = ExtraSettingsUi.outlineButton(context, R.string.delete, R.drawable.ic_delete_24);
		delete.setOnClickListener(v -> actions.requestDeleteGamePayload(payload.id));
		ExtraSettingsUi.addSmallSpacing(content, delete);
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
		return findPackById(packs, selectedId);
	}

	private CompatPackManager.CompatPack findPackById(List<CompatPackManager.CompatPack> packs, String packId) {
		if (TextUtils.isEmpty(packId) || packs == null) {
			return null;
		}
		for (CompatPackManager.CompatPack pack : packs) {
			if (packId.equals(pack.packId)) {
				return pack;
			}
		}
		return null;
	}

	private String modeLabel(String mode) {
		return LaunchProfileManager.SAVE_MODE_ISOLATED.equals(mode) || LaunchProfileManager.MODS_MODE_ISOLATED.equals(mode)
			? context.getString(R.string.launch_profile_mode_isolated_label)
			: context.getString(R.string.launch_profile_mode_global_label);
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
