package com.godot.game;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class GameVersionManagerPage {
	private static final int TAB_PROFILES = 1;
	private static final int TAB_PAYLOADS = 2;
	private static final int TAB_COMPAT = 3;
	private static int lastSelectedTab = TAB_PROFILES;

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

		PayloadManager.Status payloadStatus = payloadManager.getStatus();
		List<LaunchProfileManager.GamePayload> payloads = launchProfileManager.listPayloads();
		List<LaunchProfileManager.LaunchProfile> profiles = launchProfileManager.listProfiles();
		List<CompatPackManager.CompatPack> packs = compatPackManager.listInstalledPacks();
		LaunchProfileManager.LaunchProfile selectedProfile = launchProfileManager.getSelectedProfile();
		String selectedCompatId = compatPackManager.getSelectedPackId();

		LinearLayout tabContent = ExtraSettingsUi.vertical(context);
		ExtraSettingsUi.addSmallSpacing(root, buildSegmentedTabs(tabContent, payloadStatus, payloads, profiles, packs, selectedProfile, selectedCompatId));
		ExtraSettingsUi.addSmallSpacing(root, tabContent);
		populateTabContent(tabContent, payloadStatus, payloads, profiles, packs, selectedProfile, selectedCompatId);

		frame.addView(scrollView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		return frame;
	}

	private View buildSegmentedTabs(LinearLayout tabContent, PayloadManager.Status payloadStatus, List<LaunchProfileManager.GamePayload> payloads, List<LaunchProfileManager.LaunchProfile> profiles, List<CompatPackManager.CompatPack> packs, LaunchProfileManager.LaunchProfile selectedProfile, String selectedCompatId) {
		MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(context);
		group.setSingleSelection(true);
		group.setSelectionRequired(true);
		group.setBackgroundColor(Color.TRANSPARENT);

		MaterialButton profilesButton = segmentedButton(R.string.version_manager_tab_profiles);
		profilesButton.setId(View.generateViewId());
		MaterialButton payloadsButton = segmentedButton(R.string.version_manager_tab_payloads);
		payloadsButton.setId(View.generateViewId());
		MaterialButton compatButton = segmentedButton(R.string.version_manager_tab_compat);
		compatButton.setId(View.generateViewId());

		group.addView(profilesButton, segmentedParams(0));
		group.addView(payloadsButton, segmentedParams(0));
		group.addView(compatButton, segmentedParams(0));

		int checkedId = lastSelectedTab == TAB_PAYLOADS ? payloadsButton.getId() : (lastSelectedTab == TAB_COMPAT ? compatButton.getId() : profilesButton.getId());
		group.check(checkedId);
		group.addOnButtonCheckedListener((buttonGroup, checkedId1, isChecked) -> {
			if (!isChecked) {
				return;
			}
			if (checkedId1 == payloadsButton.getId()) {
				lastSelectedTab = TAB_PAYLOADS;
			} else if (checkedId1 == compatButton.getId()) {
				lastSelectedTab = TAB_COMPAT;
			} else {
				lastSelectedTab = TAB_PROFILES;
			}
			populateTabContent(tabContent, payloadStatus, payloads, profiles, packs, selectedProfile, selectedCompatId);
		});
		return group;
	}

	private MaterialButton segmentedButton(int textRes) {
		MaterialButton button = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
		button.setText(textRes);
		button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		button.setSingleLine(true);
		button.setCheckable(true);
		button.setMinHeight(ExtraSettingsUi.dp(context, 44));
		button.setPadding(ExtraSettingsUi.dp(context, 8), 0, ExtraSettingsUi.dp(context, 8), 0);
		button.setTextColor(new ColorStateList(
			new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
			new int[] { ExtraSettingsUi.COLOR_ON_SURFACE, ExtraSettingsUi.COLOR_ON_SURFACE }
		));
		button.setBackgroundTintList(new ColorStateList(
			new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
			new int[] { ExtraSettingsUi.COLOR_SURFACE_VARIANT, Color.TRANSPARENT }
		));
		button.setStrokeColor(ColorStateList.valueOf(ExtraSettingsUi.COLOR_OUTLINE));
		button.setStrokeWidth(ExtraSettingsUi.dp(context, 1));
		button.setRippleColor(ColorStateList.valueOf(Color.argb(72, 201, 238, 211)));
		return button;
	}

	private LinearLayout.LayoutParams segmentedParams(int marginStartDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ExtraSettingsUi.dp(context, 46), 1f);
		if (marginStartDp > 0) {
			params.setMarginStart(ExtraSettingsUi.dp(context, marginStartDp));
		}
		return params;
	}

	private void populateTabContent(LinearLayout content, PayloadManager.Status payloadStatus, List<LaunchProfileManager.GamePayload> payloads, List<LaunchProfileManager.LaunchProfile> profiles, List<CompatPackManager.CompatPack> packs, LaunchProfileManager.LaunchProfile selectedProfile, String selectedCompatId) {
		content.removeAllViews();
		if (lastSelectedTab == TAB_PAYLOADS) {
			populatePayloadsTab(content, payloadStatus, payloads, selectedProfile);
			return;
		}
		if (lastSelectedTab == TAB_COMPAT) {
			populateCompatTab(content, packs, selectedCompatId);
			return;
		}
		populateProfilesTab(content, payloads, profiles, packs, selectedProfile);
	}

	private void populateProfilesTab(LinearLayout content, List<LaunchProfileManager.GamePayload> payloads, List<LaunchProfileManager.LaunchProfile> profiles, List<CompatPackManager.CompatPack> packs, LaunchProfileManager.LaunchProfile selectedProfile) {
		MaterialButton add = tonalButton(context.getString(R.string.create_launch_profile), R.drawable.ic_add_circle_24);
		add.setOnClickListener(v -> {
			String payloadId = choosePayloadIdForNewProfile(payloads, selectedProfile);
			if (TextUtils.isEmpty(payloadId)) {
				actions.showMessage(context.getString(R.string.version_manager_no_archived_games));
				return;
			}
			actions.requestCreateLaunchProfile(payloadId);
		});
		ExtraSettingsUi.addSmallSpacing(content, add);

		if (profiles.isEmpty()) {
			ExtraSettingsUi.addSmallSpacing(content, emptyState(R.drawable.ic_gamepad_24, context.getString(R.string.launch_profiles_empty)));
			return;
		}

		for (LaunchProfileManager.LaunchProfile profile : profiles) {
			CompatPackManager.CompatPack pack = findPackById(packs, profile.compatPackId);
			boolean selected = selectedProfile != null && selectedProfile.id.equals(profile.id);
			String compatLabel = pack == null ? context.getString(R.string.version_manager_no_compat_selected) : pack.targetLabel();
			String subtitle = context.getString(R.string.version_manager_profile_list_subtitle, modeLabel(profile.saveMode), compatLabel);
			ExtraSettingsUi.addSmallSpacing(content, listItem(
				R.drawable.ic_gamepad_24,
				profile.displayName,
				selected ? context.getString(R.string.version_manager_current_badge) : "",
				subtitle,
				v -> showProfileSheet(profile, pack, selected)
			));
		}
	}

	private void populatePayloadsTab(LinearLayout content, PayloadManager.Status payloadStatus, List<LaunchProfileManager.GamePayload> payloads, LaunchProfileManager.LaunchProfile selectedProfile) {
		LinearLayout actionsRow = ExtraSettingsUi.horizontal(context);
		MaterialButton importZip = neutralButton(context.getString(R.string.version_manager_import_short), R.drawable.ic_upload_file_24);
		importZip.setOnClickListener(v -> actions.requestImportGamePayload());
		MaterialButton steam = neutralButton(context.getString(R.string.steam_account_title), R.drawable.ic_download_24);
		steam.setOnClickListener(v -> actions.openSteamAccount());
		MaterialButton cloud = neutralButton(context.getString(R.string.version_manager_cloud_share), R.drawable.ic_cloud_sync_24);
		cloud.setOnClickListener(v -> actions.openUrl(ExtraSettingsUpdateChecker.GAME_DOWNLOAD_URL));
		actionsRow.addView(importZip, weightedButtonParams(0));
		actionsRow.addView(steam, weightedButtonParams(8));
		actionsRow.addView(cloud, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(content, actionsRow);

		if (payloadManager.hasBundledPayload() && !payloadStatus.ready) {
			MaterialButton bundled = outlineButton(context.getString(R.string.extract_bundled_payload), R.drawable.ic_download_24);
			bundled.setOnClickListener(v -> actions.requestExtractBundledPayload());
			ExtraSettingsUi.addSmallSpacing(content, bundled);
		}

		if (payloads.isEmpty()) {
			ExtraSettingsUi.addSmallSpacing(content, emptyState(R.drawable.ic_folder_24, context.getString(R.string.version_manager_no_archived_games)));
			return;
		}

		for (LaunchProfileManager.GamePayload payload : payloads) {
			boolean selected = selectedProfile != null && payload.id.equals(selectedProfile.payloadId);
			ExtraSettingsUi.addSmallSpacing(content, listItem(
				R.drawable.ic_folder_24,
				payload.label,
				selected ? context.getString(R.string.version_manager_selected_badge) : "",
				payloadListSubtitle(payload),
				v -> showPayloadSheet(payload, selected)
			));
		}
	}

	private void populateCompatTab(LinearLayout content, List<CompatPackManager.CompatPack> packs, String selectedCompatId) {
		LinearLayout actionsRow = ExtraSettingsUi.horizontal(context);
		MaterialButton importPack = neutralButton(context.getString(R.string.version_manager_import_short), R.drawable.ic_upload_file_24);
		importPack.setOnClickListener(v -> actions.requestImportCompatPack());
		MaterialButton installBundled = neutralButton(context.getString(R.string.version_manager_install_bundled_short), R.drawable.ic_download_24);
		installBundled.setOnClickListener(v -> actions.requestInstallBundledCompatPacks());
		actionsRow.addView(importPack, weightedButtonParams(0));
		actionsRow.addView(installBundled, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(content, actionsRow);

		if (packs.isEmpty()) {
			ExtraSettingsUi.addSmallSpacing(content, emptyState(R.drawable.ic_extension_24, context.getString(R.string.version_manager_no_compat_packs)));
			return;
		}

		for (CompatPackManager.CompatPack pack : packs) {
			boolean selected = pack.packId.equals(selectedCompatId);
			String subtitle = context.getString(R.string.version_manager_compat_list_subtitle, TextUtils.isEmpty(pack.compatVersion) ? context.getString(R.string.unknown) : pack.compatVersion);
			ExtraSettingsUi.addSmallSpacing(content, listItem(
				R.drawable.ic_extension_24,
				pack.displayName,
				selected ? context.getString(R.string.version_manager_selected_badge) : "",
				subtitle,
				v -> showCompatSheet(pack, selected),
				TextUtils.TruncateAt.MIDDLE
			));
		}
	}

	private View listItem(int iconRes, String title, String badge, String subtitle, View.OnClickListener listener) {
		return listItem(iconRes, title, badge, subtitle, listener, TextUtils.TruncateAt.END);
	}

	private View listItem(int iconRes, String title, String badge, String subtitle, View.OnClickListener listener, TextUtils.TruncateAt titleEllipsize) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(0, ExtraSettingsUi.dp(context, 14), 0, ExtraSettingsUi.dp(context, 14));
		row.setClickable(true);
		row.setFocusable(true);
		row.setOnClickListener(listener);
		ExtraSettingsUi.applyRipple(row);

		row.addView(smallIconCircle(iconRes));

		LinearLayout texts = ExtraSettingsUi.vertical(context);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(context, 16));
		row.addView(texts, textParams);

		LinearLayout titleRow = ExtraSettingsUi.horizontal(context);
		TextView titleView = ExtraSettingsUi.text(context, title == null ? "" : title, 16, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		titleView.setSingleLine(true);
		titleView.setEllipsize(titleEllipsize == null ? TextUtils.TruncateAt.END : titleEllipsize);
		titleRow.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		if (!TextUtils.isEmpty(badge)) {
			TextView badgeView = badge(badge);
			LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			badgeParams.setMarginStart(ExtraSettingsUi.dp(context, 8));
			titleRow.addView(badgeView, badgeParams);
		}
		texts.addView(titleRow);

		TextView subtitleView = ExtraSettingsUi.body(context, subtitle == null ? "" : subtitle);
		subtitleView.setSingleLine(true);
		subtitleView.setEllipsize(TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		subtitleParams.topMargin = ExtraSettingsUi.dp(context, 4);
		texts.addView(subtitleView, subtitleParams);

		ImageView chevron = ExtraSettingsUi.icon(context, R.drawable.ic_chevron_right_24, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 24);
		LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 24), ExtraSettingsUi.dp(context, 24));
		chevronParams.setMarginStart(ExtraSettingsUi.dp(context, 8));
		row.addView(chevron, chevronParams);
		return row;
	}

	private View emptyState(int iconRes, String message) {
		MaterialCardView card = ExtraSettingsUi.card(context);
		card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		card.setStrokeColor(ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		content.setGravity(Gravity.CENTER_HORIZONTAL);
		content.addView(ExtraSettingsUi.iconCircle(context, iconRes, ExtraSettingsUi.COLOR_SURFACE_VARIANT, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT));
		TextView body = ExtraSettingsUi.body(context, message == null ? "" : message);
		body.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(context, 12);
		content.addView(body, params);
		return card;
	}

	private View smallIconCircle(int iconRes) {
		LinearLayout holder = new LinearLayout(context);
		holder.setGravity(Gravity.CENTER);
		GradientDrawable bg = new GradientDrawable();
		bg.setShape(GradientDrawable.OVAL);
		bg.setColor(ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		holder.setBackground(bg);
		int size = ExtraSettingsUi.dp(context, 40);
		holder.setLayoutParams(new LinearLayout.LayoutParams(size, size));
		ImageView icon = ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_PRIMARY, 24);
		holder.addView(icon);
		return holder;
	}

	private TextView badge(String value) {
		TextView view = ExtraSettingsUi.text(context, value, 11, ExtraSettingsUi.COLOR_ON_PRIMARY, Typeface.BOLD);
		view.setSingleLine(true);
		view.setPadding(ExtraSettingsUi.dp(context, 6), ExtraSettingsUi.dp(context, 2), ExtraSettingsUi.dp(context, 6), ExtraSettingsUi.dp(context, 2));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(ExtraSettingsUi.COLOR_PRIMARY);
		bg.setCornerRadius(ExtraSettingsUi.dp(context, 4));
		view.setBackground(bg);
		return view;
	}

	private void showProfileSheet(LaunchProfileManager.LaunchProfile profile, CompatPackManager.CompatPack pack, boolean selected) {
		BottomSheetDialog dialog = createBottomSheetDialog();
		LinearLayout content = buildSheetContent(profile.displayName);
		LinearLayout details = sheetDetailsContainer(content);
		String payloadLabel = profile.payload == null ? profile.payloadId : profile.payload.label;
		addSheetDetailRow(details, R.drawable.ic_desktop_windows_24, R.string.version_manager_detail_game_body, payloadLabel);
		addSheetDetailRow(details, R.drawable.ic_folder_24, R.string.version_manager_detail_profile_path, profile.dir.getAbsolutePath());
		addSheetDetailRow(details, R.drawable.ic_save_24, R.string.version_manager_detail_save_mode, modeLabel(profile.saveMode));
		addSheetDetailRow(details, R.drawable.ic_extension_24, R.string.version_manager_detail_mods_mode, modeLabel(profile.modsMode));
		addSheetDetailRow(details, R.drawable.ic_layers_24, R.string.version_manager_detail_compat_pack, pack == null ? context.getString(R.string.version_manager_no_compat_selected) : context.getString(R.string.version_manager_selected_compat_format, pack.displayName, pack.targetLabel()));
		if (profile.updatedAtUnix > 0) {
			addSheetDetailRow(details, R.drawable.ic_article_24, R.string.version_manager_detail_updated_at, formatTime(profile.updatedAtUnix));
		}

		LinearLayout actionsLayout = sheetActionsContainer(content);
		if (!selected && profile.ready) {
			addSheetAction(actionsLayout, primaryButton(context.getString(R.string.version_manager_select), R.drawable.ic_check_circle_24, v -> {
				dialog.dismiss();
				actions.requestSelectLaunchProfile(profile.id);
			}));
		}
		addSheetAction(actionsLayout, outlineButton(context.getString(R.string.edit), R.drawable.ic_edit_24, v -> {
			dialog.dismiss();
			actions.requestEditLaunchProfile(profile.id);
		}));
		addSheetAction(actionsLayout, errorButton(context.getString(R.string.delete), R.drawable.ic_delete_24, v -> {
			dialog.dismiss();
			actions.requestDeleteLaunchProfile(profile.id);
		}));
		dialog.setContentView(content);
		dialog.show();
	}

	private void showPayloadSheet(LaunchProfileManager.GamePayload payload, boolean selected) {
		BottomSheetDialog dialog = createBottomSheetDialog();
		LinearLayout content = buildSheetContent(payload.label);
		LinearLayout details = sheetDetailsContainer(content);
		addSheetDetailRow(details, R.drawable.ic_folder_24, R.string.version_manager_detail_payload_path, payload.gameDir.getAbsolutePath());
		TextView stats = addSheetDetailRow(details, R.drawable.ic_article_24, R.string.version_manager_detail_file_info, payloadStatsOrCalculating(payload));
		if (payload.fileCount <= 0 && payload.totalBytes <= 0L) {
			loadPayloadStatsAsync(stats, payload.gameDir, payload.pckSize, payload.dllSize);
		}
		if (!TextUtils.isEmpty(payload.version)) {
			addSheetDetailRow(details, R.drawable.ic_badge_24, R.string.version_manager_detail_game_version, payload.version);
		}
		if (!TextUtils.isEmpty(payload.commit)) {
			addSheetDetailRow(details, R.drawable.ic_code_24, R.string.version_manager_detail_commit, payload.commit);
		}
		if (!TextUtils.isEmpty(payload.sts2DllSha256)) {
			addSheetDetailRow(details, R.drawable.ic_article_24, R.string.version_manager_detail_sts2_dll_sha, shortHash(payload.sts2DllSha256));
		}
		if (payload.installedAtUnix > 0) {
			addSheetDetailRow(details, R.drawable.ic_article_24, R.string.version_manager_detail_installed_at, formatTime(payload.installedAtUnix));
		}

		LinearLayout actionsLayout = sheetActionsContainer(content);
		if (!selected) {
			addSheetAction(actionsLayout, primaryButton(context.getString(R.string.version_manager_select_game_body), R.drawable.ic_check_circle_24, v -> {
				dialog.dismiss();
				actions.requestSelectGameVersion(payload.id);
			}));
		}
		addSheetAction(actionsLayout, outlineButton(context.getString(R.string.create_launch_profile), R.drawable.ic_add_circle_24, v -> {
			dialog.dismiss();
			actions.requestCreateLaunchProfile(payload.id);
		}));
		addSheetAction(actionsLayout, errorButton(context.getString(R.string.clear_game_payload), R.drawable.ic_delete_24, v -> {
			dialog.dismiss();
			actions.requestDeleteGamePayload(payload.id);
		}));
		dialog.setContentView(content);
		dialog.show();
	}

	private void showCompatSheet(CompatPackManager.CompatPack pack, boolean selected) {
		BottomSheetDialog dialog = createBottomSheetDialog();
		LinearLayout content = buildSheetContent(pack.displayName);
		LinearLayout details = sheetDetailsContainer(content);
		addSheetDetailRow(details, R.drawable.ic_extension_24, R.string.version_manager_detail_target_version, pack.targetLabel());
		addSheetDetailRow(details, R.drawable.ic_badge_24, R.string.version_manager_detail_compat_version, TextUtils.isEmpty(pack.compatVersion) ? context.getString(R.string.unknown) : pack.compatVersion);
		if (!TextUtils.isEmpty(pack.channel)) {
			addSheetDetailRow(details, R.drawable.ic_layers_24, R.string.version_manager_detail_channel, pack.channel);
		}
		addSheetDetailRow(details, R.drawable.ic_folder_24, R.string.version_manager_detail_storage_path, pack.dir.getAbsolutePath());
		if (!TextUtils.isEmpty(pack.targetSts2DllSha256)) {
			addSheetDetailRow(details, R.drawable.ic_article_24, R.string.version_manager_detail_target_dll_sha, shortHash(pack.targetSts2DllSha256));
		}
		if (pack.installedAtUnix > 0) {
			addSheetDetailRow(details, R.drawable.ic_article_24, R.string.version_manager_detail_installed_at, formatTime(pack.installedAtUnix));
		}

		LinearLayout actionsLayout = sheetActionsContainer(content);
		if (!selected && pack.ready) {
			addSheetAction(actionsLayout, primaryButton(context.getString(R.string.version_manager_select_compat_pack), R.drawable.ic_check_circle_24, v -> {
				dialog.dismiss();
				actions.requestSelectCompatPack(pack.packId);
			}));
		}
		addSheetAction(actionsLayout, errorButton(context.getString(R.string.delete), R.drawable.ic_delete_24, v -> {
			dialog.dismiss();
			actions.requestDeleteCompatPack(pack.packId);
		}));
		dialog.setContentView(content);
		dialog.show();
	}

	private BottomSheetDialog createBottomSheetDialog() {
		BottomSheetDialog dialog = new BottomSheetDialog(context);
		dialog.setOnShowListener(unused -> {
			Window window = dialog.getWindow();
			if (window != null) {
				window.setDimAmount(0.56f);
			}
		});
		return dialog;
	}

	private LinearLayout buildSheetContent(String title) {
		LinearLayout root = ExtraSettingsUi.vertical(context);
		root.setPadding(ExtraSettingsUi.dp(context, 24), ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 24), ExtraSettingsUi.dp(context, 32));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		float radius = ExtraSettingsUi.dp(context, 28);
		bg.setCornerRadii(new float[] { radius, radius, radius, radius, 0, 0, 0, 0 });
		root.setBackground(bg);

		View handle = new View(context);
		GradientDrawable handleBg = new GradientDrawable();
		handleBg.setColor(Color.argb(104, 202, 196, 208));
		handleBg.setCornerRadius(ExtraSettingsUi.dp(context, 2));
		handle.setBackground(handleBg);
		LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(context, 32), ExtraSettingsUi.dp(context, 4));
		handleParams.gravity = Gravity.CENTER_HORIZONTAL;
		handleParams.bottomMargin = ExtraSettingsUi.dp(context, 24);
		root.addView(handle, handleParams);

		TextView titleView = ExtraSettingsUi.text(context, title == null ? "" : title, 22, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		titleView.setLineSpacing(ExtraSettingsUi.dp(context, 2), 1.0f);
		root.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return root;
	}

	private LinearLayout sheetDetailsContainer(LinearLayout root) {
		LinearLayout details = ExtraSettingsUi.vertical(context);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(context, 18);
		root.addView(details, params);
		return details;
	}

	private LinearLayout sheetActionsContainer(LinearLayout root) {
		LinearLayout actionsLayout = ExtraSettingsUi.vertical(context);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(context, 20);
		root.addView(actionsLayout, params);
		return actionsLayout;
	}

	private TextView addSheetDetailRow(LinearLayout parent, int iconRes, int labelRes, String value) {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.TOP);
		row.addView(ExtraSettingsUi.icon(context, iconRes, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 22));

		LinearLayout texts = ExtraSettingsUi.vertical(context);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(context, 12));
		row.addView(texts, textParams);
		texts.addView(ExtraSettingsUi.text(context, labelRes, 14, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD));
		TextView valueView = ExtraSettingsUi.body(context, value == null ? "" : value);
		valueView.setTextIsSelectable(true);
		LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		valueParams.topMargin = ExtraSettingsUi.dp(context, 2);
		texts.addView(valueView, valueParams);

		LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		rowParams.topMargin = parent.getChildCount() == 0 ? 0 : ExtraSettingsUi.dp(context, 14);
		parent.addView(row, rowParams);
		return valueView;
	}

	private void addSheetAction(LinearLayout actionsLayout, MaterialButton button) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = actionsLayout.getChildCount() == 0 ? 0 : ExtraSettingsUi.dp(context, 10);
		actionsLayout.addView(button, params);
	}

	private LinearLayout.LayoutParams weightedButtonParams(int marginStartDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		if (marginStartDp > 0) {
			params.setMarginStart(ExtraSettingsUi.dp(context, marginStartDp));
		}
		return params;
	}

	private MaterialButton primaryButton(String text, int iconRes, View.OnClickListener listener) {
		MaterialButton button = button(text, iconRes, ExtraSettingsUi.COLOR_PRIMARY, ExtraSettingsUi.COLOR_ON_PRIMARY, Color.TRANSPARENT);
		button.setOnClickListener(listener);
		return button;
	}

	private MaterialButton tonalButton(String text, int iconRes) {
		return button(text, iconRes, ExtraSettingsUi.COLOR_PRIMARY_CONTAINER, ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER, Color.TRANSPARENT);
	}

	private MaterialButton neutralButton(String text, int iconRes) {
		return button(text, iconRes, ExtraSettingsUi.COLOR_SURFACE_VARIANT, ExtraSettingsUi.COLOR_ON_SURFACE, Color.TRANSPARENT);
	}

	private MaterialButton outlineButton(String text, int iconRes) {
		return button(text, iconRes, Color.TRANSPARENT, ExtraSettingsUi.COLOR_PRIMARY, ExtraSettingsUi.COLOR_PRIMARY);
	}

	private MaterialButton outlineButton(String text, int iconRes, View.OnClickListener listener) {
		MaterialButton button = outlineButton(text, iconRes);
		button.setOnClickListener(listener);
		return button;
	}

	private MaterialButton errorButton(String text, int iconRes, View.OnClickListener listener) {
		MaterialButton button = button(text, iconRes, Color.TRANSPARENT, ExtraSettingsUi.COLOR_ERROR, ExtraSettingsUi.COLOR_ERROR);
		button.setOnClickListener(listener);
		return button;
	}

	private MaterialButton button(String text, int iconRes, int backgroundColor, int textColor, int strokeColor) {
		MaterialButton button = new MaterialButton(context);
		button.setText(text == null ? "" : text);
		button.setTextColor(textColor);
		button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		button.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
		button.setStrokeColor(ColorStateList.valueOf(strokeColor));
		button.setStrokeWidth(strokeColor == Color.TRANSPARENT ? 0 : ExtraSettingsUi.dp(context, 1));
		button.setRippleColor(ColorStateList.valueOf(Color.argb(72, 201, 238, 211)));
		button.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(ExtraSettingsUi.dp(context, 18)).build());
		button.setPadding(ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 10), ExtraSettingsUi.dp(context, 12), ExtraSettingsUi.dp(context, 10));
		button.setMinHeight(ExtraSettingsUi.dp(context, 42));
		button.setAllCaps(false);
		if (iconRes != 0) {
			MaterialSymbols.applyButtonIcon(button, iconRes, ColorStateList.valueOf(textColor), 24);
		}
		return button;
	}

	private String choosePayloadIdForNewProfile(List<LaunchProfileManager.GamePayload> payloads, LaunchProfileManager.LaunchProfile selectedProfile) {
		if (selectedProfile != null && selectedProfile.payload != null && selectedProfile.payload.ready) {
			return selectedProfile.payloadId;
		}
		if (payloads != null) {
			for (LaunchProfileManager.GamePayload payload : payloads) {
				if (payload != null && payload.ready) {
					return payload.id;
				}
			}
		}
		return "";
	}

	private String payloadListSubtitle(LaunchProfileManager.GamePayload payload) {
		if (payload.fileCount > 0 || payload.totalBytes > 0L) {
			return context.getString(R.string.version_manager_payload_list_subtitle, Formatter.formatFileSize(context, Math.max(0L, payload.totalBytes)), payload.fileCount);
		}
		return context.getString(R.string.payload_stats_calculating);
	}

	private String payloadStatsOrCalculating(LaunchProfileManager.GamePayload payload) {
		if (payload.fileCount > 0 || payload.totalBytes > 0L) {
			return formatPayloadStats(payload.fileCount, payload.totalBytes, payload.pckSize, payload.dllSize);
		}
		return context.getString(R.string.payload_stats_calculating);
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

	private void loadPayloadStatsAsync(TextView target, File gameDir, long pckSize, long dllSize) {
		new Thread(() -> {
			DirectoryStatsCalculator.DirectoryStats stats = DirectoryStatsCalculator.calculate(gameDir);
			target.post(() -> target.setText(formatPayloadStats(stats.fileCount, stats.totalBytes, pckSize, dllSize)));
		}, "sts2-payload-size").start();
	}

	private String formatPayloadStats(int fileCount, long totalBytes, long pckSize, long dllSize) {
		return context.getString(
			R.string.payload_stats_format,
			fileCount,
			Formatter.formatFileSize(context, Math.max(0L, totalBytes)),
			Formatter.formatFileSize(context, Math.max(0L, pckSize)),
			Formatter.formatFileSize(context, Math.max(0L, dllSize)));
	}

	private String formatTime(long unixSeconds) {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(unixSeconds * 1000L));
	}

	private String shortHash(String value) {
		if (TextUtils.isEmpty(value)) {
			return "";
		}
		return value.substring(0, Math.min(12, value.length()));
	}
}
