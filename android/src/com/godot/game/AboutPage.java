package com.godot.game;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

public final class AboutPage {
	private static final String AUTHOR_BILIBILI_URL = "https://space.bilibili.com/116375500";
	private static final String LAUNCHER_GITHUB_URL = "https://github.com/ModinMobileSTS/Sts2MobileLauncher";
	private static final String LAUNCHER_NEW_ISSUE_URL = LAUNCHER_GITHUB_URL + "/issues/new";
	private static final String GAME_DOWNLOAD_URL = ExtraSettingsUpdateChecker.GAME_DOWNLOAD_URL;
	private static final String STEAM_URL = "https://store.steampowered.com/app/2868840/Slay_the_Spire_2/";
	private static final String SLAY_AMETHYST_URL = "https://github.com/ModinMobileSTS/SlayTheAmethystModded";
	private static final String QUICK_RESTART_URL = "https://github.com/freude916/sts2-quickRestart";
	private static final String RITSU_LIB_URL = "https://github.com/BAKAOLC/STS2-RitsuLib";
	private static final String STS2_LAUNCHER_MOD_MANAGER_URL = "https://github.com/iunius612/StS2-Launcher_Mod_Manager";
	private static final String WORKSHOP_ANDROID_DOWNLOADER_URL = "https://github.com/Apricityx/WorkshopAndroidDownloader";

	private final Context context;
	private final ExtraSettingsActions actions;

	public AboutPage(Context context, ExtraSettingsActions actions) {
		this.context = context;
		this.actions = actions;
	}

	public View build() {
		ScrollView scrollView = new ScrollView(context);
		scrollView.setFillViewport(false);
		scrollView.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		LinearLayout root = ExtraSettingsUi.vertical(context);
		root.setPadding(0, ExtraSettingsUi.dp(context, 24), 0, ExtraSettingsUi.dp(context, 32));
		SystemBarInsetsHelper.applySystemBarPadding(root, true, false, false, false);
		ExtraSettingsUi.addResponsiveScrollContent(context, scrollView, root);

		root.addView(ExtraSettingsUi.title(context, R.string.tab_about));

		addResponsiveCards(root, authorCard(), linkCard(R.drawable.ic_desktop_windows_24, R.string.steam_link_title, R.string.steam_link_desc, STEAM_URL));
		addResponsiveCards(root, linkCard(R.drawable.ic_download_24, R.string.download_link_title, R.string.download_link_desc, GAME_DOWNLOAD_URL), linkCard(R.drawable.ic_error_outline_24, R.string.issue_feedback_title, R.string.issue_feedback_desc, LAUNCHER_NEW_ISSUE_URL));
		ExtraSettingsUi.addCardSpacing(root, updateCheckCard());
		ExtraSettingsUi.addCardSpacing(root, friendHeader());
		addResponsiveCards(root, linkCard(R.drawable.ic_extension_24, R.string.friend_link_amethyst_title, R.string.friend_link_amethyst_desc, SLAY_AMETHYST_URL), linkCard(R.drawable.ic_restart_alt_24, R.string.friend_link_quick_restart_title, R.string.friend_link_quick_restart_desc, QUICK_RESTART_URL));
		addResponsiveCards(root, linkCard(R.drawable.ic_code_24, R.string.friend_link_ritsu_lib_title, R.string.friend_link_ritsu_lib_desc, RITSU_LIB_URL), linkCard(R.drawable.ic_settings_24, R.string.friend_link_sts2_launcher_mod_manager_title, R.string.friend_link_sts2_launcher_mod_manager_desc, STS2_LAUNCHER_MOD_MANAGER_URL));
		ExtraSettingsUi.addCardSpacing(root, linkCard(R.drawable.ic_steam_24, R.string.friend_link_workshop_android_downloader_title, R.string.friend_link_workshop_android_downloader_desc, WORKSHOP_ANDROID_DOWNLOADER_URL));
		return scrollView;
	}

	private void addResponsiveCards(LinearLayout root, View first, View second) {
		if (ExtraSettingsUi.isWideLayout(context)) {
			ExtraSettingsUi.addResponsivePair(context, root, first, second);
		} else {
			ExtraSettingsUi.addCardSpacing(root, first);
			ExtraSettingsUi.addCardSpacing(root, second);
		}
	}

	private View authorCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.iconCircle(context, R.drawable.ic_person_24, ExtraSettingsUi.COLOR_PRIMARY, ExtraSettingsUi.COLOR_ON_PRIMARY));
		LinearLayout texts = ExtraSettingsUi.vertical(context);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(context, 14));
		row.addView(texts, textParams);
		texts.addView(ExtraSettingsUi.sectionTitle(context, R.string.author_wsdx233_title));
		texts.addView(ExtraSettingsUi.body(context, R.string.author_wsdx233_desc));
		content.addView(row);

		LinearLayout links = ExtraSettingsUi.horizontal(context);
		MaterialButton bilibili = ExtraSettingsUi.tonalButton(context, R.string.author_link_bilibili, R.drawable.ic_open_in_new_24);
		MaterialButton github = ExtraSettingsUi.outlineButton(context, R.string.author_link_github, R.drawable.ic_code_24);
		bilibili.setOnClickListener(v -> actions.openUrl(AUTHOR_BILIBILI_URL));
		github.setOnClickListener(v -> actions.openUrl(LAUNCHER_GITHUB_URL));
		links.addView(bilibili, weighted(0));
		links.addView(github, weighted(10));
		ExtraSettingsUi.addSmallSpacing(content, links);
		return card;
	}

	private View updateCheckCard() {
		MaterialCardView card = ExtraSettingsUi.card(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.iconCircle(context, R.drawable.ic_sync_24, ExtraSettingsUi.COLOR_PRIMARY_CONTAINER, ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER));

		LinearLayout texts = ExtraSettingsUi.vertical(context);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(context, 14));
		row.addView(texts, textParams);
		texts.addView(ExtraSettingsUi.sectionTitle(context, R.string.update_check_title));
		texts.addView(ExtraSettingsUi.body(context, R.string.update_check_desc));
		texts.addView(ExtraSettingsUi.caption(context, context.getString(R.string.update_check_current_version_format, BuildConfig.VERSION_NAME)));

		MaterialButton check = ExtraSettingsUi.iconButton(context, R.drawable.ic_sync_24);
		check.setContentDescription(context.getString(R.string.update_check_manual));
		check.setOnClickListener(v -> actions.requestManualUpdateCheck());
		row.addView(check);

		MaterialSwitch enabled = new MaterialSwitch(context);
		enabled.setContentDescription(context.getString(R.string.update_check_auto));
		enabled.setChecked(ExtraSettingsPreferences.isUpdateCheckEnabled(context));
		enabled.setOnCheckedChangeListener((buttonView, isChecked) -> ExtraSettingsPreferences.setUpdateCheckEnabled(context, isChecked));
		row.addView(enabled);
		content.addView(row);
		return card;
	}

	private View friendHeader() {
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.sectionTitle(context, R.string.friend_links_title));
		LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(0, ExtraSettingsUi.dp(context, 1), 1f);
		dividerParams.setMarginStart(ExtraSettingsUi.dp(context, 12));
		View divider = new View(context);
		divider.setBackgroundColor(ExtraSettingsUi.COLOR_OUTLINE);
		row.addView(divider, dividerParams);
		return row;
	}

	private View linkCard(int iconRes, int titleRes, int descRes, String url) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(context);
		LinearLayout content = ExtraSettingsUi.cardContent(context, card);
		LinearLayout row = ExtraSettingsUi.horizontal(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.iconCircle(context, iconRes, ExtraSettingsUi.COLOR_SECONDARY_CONTAINER, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT));
		LinearLayout texts = ExtraSettingsUi.vertical(context);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(context, 14));
		row.addView(texts, textParams);
		texts.addView(ExtraSettingsUi.sectionTitle(context, titleRes));
		texts.addView(ExtraSettingsUi.body(context, descRes));
		row.addView(ExtraSettingsUi.icon(context, R.drawable.ic_open_in_new_24, ExtraSettingsUi.COLOR_PRIMARY, 22));
		content.addView(row);
		card.setOnClickListener(v -> actions.openUrl(url));
		return card;
	}

	private LinearLayout.LayoutParams weighted(int marginStartDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(context, marginStartDp));
		return params;
	}
}
