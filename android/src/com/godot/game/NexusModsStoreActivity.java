package com.godot.game;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class NexusModsStoreActivity extends AppCompatActivity {
	private static final String API_KEY_TUTORIAL_URL = "https://www.nexusmods.com/settings/api-keys";

	private ExtraSettingsRepository repository;
	private LinearLayout resultsContainer;
	private TextInputEditText searchInput;
	private TextView apiStatusText;
	private TextView statusText;
	private ProgressBar progressBar;
	private boolean busy;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		repository = new ExtraSettingsRepository(this);
		repository.ensureAppDirectories();
		setContentView(buildContent());
		refreshApiStatus();
		if (NexusModsStorePreferences.hasPersonalApiKey(this)) {
			loadDiscoverFeed();
		} else {
			showWelcomeState();
		}
	}

	private View buildContent() {
		FrameLayout frame = new FrameLayout(this);
		frame.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		ScrollView scrollView = new ScrollView(this);
		scrollView.setFillViewport(false);
		scrollView.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		LinearLayout root = ExtraSettingsUi.vertical(this);
		root.setPadding(0, ExtraSettingsUi.dp(this, 24), 0, ExtraSettingsUi.dp(this, 40));
		SystemBarInsetsHelper.applySystemBarPadding(root, true, false, false, false);
		SystemBarInsetsHelper.applySystemBarPadding(scrollView, false, true, true, true);
		ExtraSettingsUi.addResponsiveScrollContent(this, scrollView, root);

		root.addView(buildTopBar());
		ExtraSettingsUi.addCardSpacing(root, buildApiKeyCard());
		ExtraSettingsUi.addCardSpacing(root, buildSearchCard());
		resultsContainer = ExtraSettingsUi.vertical(this);
		ExtraSettingsUi.addCardSpacing(root, resultsContainer);
		frame.addView(scrollView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		return frame;
	}

	private View buildTopBar() {
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		MaterialButton back = ExtraSettingsUi.iconButton(this, R.drawable.ic_arrow_forward_24);
		back.setRotation(180f);
		back.setContentDescription(getString(android.R.string.cancel));
		back.setOnClickListener(v -> finish());
		row.addView(back);

		LinearLayout titles = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		titleParams.setMarginStart(ExtraSettingsUi.dp(this, 10));
		row.addView(titles, titleParams);
		titles.addView(ExtraSettingsUi.title(this, R.string.nexus_mod_store_title));
		TextView subtitle = ExtraSettingsUi.body(this, R.string.nexus_mod_store_subtitle);
		subtitle.setMaxLines(2);
		titles.addView(subtitle);

		MaterialButton tutorial = ExtraSettingsUi.iconButton(this, R.drawable.ic_info_24);
		tutorial.setContentDescription(getString(R.string.nexus_mod_store_api_key_tutorial_title));
		tutorial.setOnClickListener(v -> showApiKeyTutorialDialog());
		row.addView(tutorial);
		return row;
	}

	private View buildApiKeyCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.iconTitleRow(
			this,
			R.drawable.ic_lock_open_24,
			R.string.nexus_mod_store_api_key_title,
			R.string.nexus_mod_store_api_key_subtitle,
			infoButton(R.string.nexus_mod_store_api_key_tutorial_title, R.string.nexus_mod_store_api_key_tutorial_message)
		));

		apiStatusText = ExtraSettingsUi.body(this, "");
		ExtraSettingsUi.addSmallSpacing(content, apiStatusText);

		MaterialButton saveKey = ExtraSettingsUi.filledButton(this, R.string.nexus_mod_store_save_api_key, R.drawable.ic_save_24);
		saveKey.setOnClickListener(v -> showApiKeyDialog());
		ExtraSettingsUi.addSmallSpacing(content, saveKey);

		LinearLayout row = ExtraSettingsUi.horizontal(this);
		MaterialButton validate = ExtraSettingsUi.tonalButton(this, R.string.nexus_mod_store_validate_api_key, R.drawable.ic_check_circle_24);
		MaterialButton clear = ExtraSettingsUi.outlineButton(this, R.string.nexus_mod_store_clear_api_key, R.drawable.ic_delete_24);
		validate.setOnClickListener(v -> validateApiKey());
		clear.setOnClickListener(v -> confirmClearApiKey());
		row.addView(validate, weightedButtonParams(0));
		row.addView(clear, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(content, row);

		MaterialButton openTutorial = ExtraSettingsUi.outlineButton(this, R.string.nexus_mod_store_open_api_key_page, R.drawable.ic_open_in_new_24);
		openTutorial.setOnClickListener(v -> openUrl(API_KEY_TUTORIAL_URL));
		ExtraSettingsUi.addSmallSpacing(content, openTutorial);
		return card;
	}

	private View buildSearchCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.iconTitleRow(
			this,
			R.drawable.ic_search_24,
			R.string.nexus_mod_store_search_title,
			R.string.nexus_mod_store_search_subtitle,
			null
		));

		TextInputLayout searchLayout = new TextInputLayout(this);
		searchLayout.setHint(getString(R.string.nexus_mod_store_search_hint));
		searchLayout.setStartIconDrawable(MaterialSymbols.drawable(this, R.drawable.ic_search_24, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 24));
		searchLayout.setBoxBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		searchInput = new TextInputEditText(searchLayout.getContext());
		searchInput.setSingleLine(true);
		searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
		searchInput.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		searchInput.setHintTextColor(ExtraSettingsUi.COLOR_MUTED);
		searchInput.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_SEARCH) {
				performSearch();
				return true;
			}
			return false;
		});
		searchLayout.addView(searchInput);
		ExtraSettingsUi.addSmallSpacing(content, searchLayout);

		LinearLayout row = ExtraSettingsUi.horizontal(this);
		MaterialButton search = ExtraSettingsUi.filledButton(this, R.string.nexus_mod_store_search, R.drawable.ic_search_24);
		MaterialButton discover = ExtraSettingsUi.tonalButton(this, R.string.nexus_mod_store_discover, R.drawable.ic_download_24);
		search.setOnClickListener(v -> performSearch());
		discover.setOnClickListener(v -> loadDiscoverFeed());
		row.addView(search, weightedButtonParams(0));
		row.addView(discover, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(content, row);

		MaterialButton nxm = ExtraSettingsUi.outlineButton(this, R.string.nexus_mod_store_paste_nxm_link, R.drawable.ic_open_in_new_24);
		nxm.setOnClickListener(v -> showNxmLinkDialog());
		ExtraSettingsUi.addSmallSpacing(content, nxm);

		TextView hint = ExtraSettingsUi.body(this, getString(R.string.nexus_mod_store_search_note, NexusModsStorePreferences.getGameDomain(this)));
		ExtraSettingsUi.addSmallSpacing(content, hint);

		progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		progressBar.setMax(100);
		progressBar.setIndeterminate(true);
		progressBar.setVisibility(View.GONE);
		ExtraSettingsUi.addSmallSpacing(content, progressBar);

		statusText = ExtraSettingsUi.caption(this, getString(R.string.nexus_mod_store_status_idle));
		ExtraSettingsUi.addSmallSpacing(content, statusText);
		return card;
	}

	private MaterialButton infoButton(int titleRes, int messageRes) {
		MaterialButton button = ExtraSettingsUi.iconButton(this, R.drawable.ic_info_24);
		button.setOnClickListener(v -> ExtraSettingsUi.showInfoDialog(this, titleRes, messageRes));
		return button;
	}

	private LinearLayout.LayoutParams weightedButtonParams(int marginStartDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(this, marginStartDp));
		return params;
	}

	private void refreshApiStatus() {
		if (apiStatusText == null) {
			return;
		}
		String key = NexusModsStorePreferences.getPersonalApiKey(this);
		String domain = NexusModsStorePreferences.getGameDomain(this);
		if (TextUtils.isEmpty(key)) {
			apiStatusText.setText(getString(R.string.nexus_mod_store_api_key_missing_status, domain));
		} else {
			apiStatusText.setText(getString(R.string.nexus_mod_store_api_key_saved_status, domain, NexusModsStorePreferences.maskApiKey(key)));
		}
	}

	private NexusModsApiClient apiClient() {
		return new NexusModsApiClient(this, NexusModsStorePreferences.getPersonalApiKey(this), NexusModsStorePreferences.getGameDomain(this));
	}

	private boolean ensureApiKey() {
		if (NexusModsStorePreferences.hasPersonalApiKey(this)) {
			return true;
		}
		showMessage(getString(R.string.nexus_mod_store_api_key_required));
		showApiKeyDialog();
		return false;
	}

	private void showApiKeyDialog() {
		LinearLayout content = ExtraSettingsUi.vertical(this);
		int padding = ExtraSettingsUi.dp(this, 4);
		content.setPadding(padding, padding, padding, 0);
		TextView tutorial = ExtraSettingsUi.body(this, R.string.nexus_mod_store_api_key_tutorial_message);
		content.addView(tutorial);

		TextInputLayout inputLayout = new TextInputLayout(this);
		inputLayout.setHint(getString(R.string.nexus_mod_store_api_key_hint));
		inputLayout.setBoxBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		TextInputEditText input = new TextInputEditText(inputLayout.getContext());
		input.setSingleLine(true);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
		input.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		input.setText(NexusModsStorePreferences.getPersonalApiKey(this));
		inputLayout.addView(input);
		LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		inputParams.topMargin = ExtraSettingsUi.dp(this, 12);
		content.addView(inputLayout, inputParams);

		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.nexus_mod_store_api_key_title)
			.setView(content)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.nexus_mod_store_open_api_key_page, (dialog, which) -> openUrl(API_KEY_TUTORIAL_URL))
			.setPositiveButton(R.string.nexus_mod_store_save_api_key, (dialog, which) -> {
				String key = input.getText() == null ? "" : input.getText().toString().trim();
				if (TextUtils.isEmpty(key)) {
					showMessage(getString(R.string.nexus_mod_store_api_key_required));
					return;
				}
				NexusModsStorePreferences.setPersonalApiKey(this, key);
				refreshApiStatus();
				showMessage(getString(R.string.nexus_mod_store_api_key_saved));
				loadDiscoverFeed();
			})
			.show();
	}

	private void showApiKeyTutorialDialog() {
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.nexus_mod_store_api_key_tutorial_title)
			.setMessage(R.string.nexus_mod_store_api_key_tutorial_message)
			.setNegativeButton(android.R.string.ok, null)
			.setPositiveButton(R.string.nexus_mod_store_open_api_key_page, (dialog, which) -> openUrl(API_KEY_TUTORIAL_URL))
			.show();
	}

	private void confirmClearApiKey() {
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.nexus_mod_store_clear_api_key)
			.setMessage(R.string.nexus_mod_store_clear_api_key_confirm)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				NexusModsStorePreferences.clearPersonalApiKey(this);
				refreshApiStatus();
				showWelcomeState();
				showMessage(getString(R.string.nexus_mod_store_api_key_cleared));
			})
			.show();
	}

	private void validateApiKey() {
		if (!ensureApiKey()) {
			return;
		}
		runStoreOperation(getString(R.string.nexus_mod_store_status_validating_key), () -> apiClient().validateKey(), userName -> {
			showMessage(getString(R.string.nexus_mod_store_api_key_valid_for, userName));
			setIdleStatus(getString(R.string.nexus_mod_store_api_key_valid_for, userName));
		});
	}

	private void loadDiscoverFeed() {
		if (!ensureApiKey()) {
			return;
		}
		runStoreOperation(getString(R.string.nexus_mod_store_status_loading), () -> apiClient().discoverMods(), mods -> showResults(mods, ""));
	}

	private void performSearch() {
		if (!ensureApiKey()) {
			return;
		}
		String query = searchInput == null || searchInput.getText() == null ? "" : searchInput.getText().toString().trim();
		runStoreOperation(getString(R.string.nexus_mod_store_status_searching), () -> apiClient().searchMods(query), mods -> showResults(mods, query));
	}

	private void showResults(List<NexusModsApiClient.NexusMod> mods, String query) {
		if (resultsContainer == null) {
			return;
		}
		resultsContainer.removeAllViews();
		if (mods == null || mods.isEmpty()) {
			ExtraSettingsUi.addCardSpacing(resultsContainer, buildEmptyResultsCard(query));
			setIdleStatus(getString(R.string.nexus_mod_store_no_results));
			return;
		}
		ExtraSettingsUi.addCardSpacing(resultsContainer, buildSummaryCard(mods.size(), query));
		for (NexusModsApiClient.NexusMod mod : mods) {
			ExtraSettingsUi.addCardSpacing(resultsContainer, buildModCard(mod));
		}
		setIdleStatus(getString(R.string.nexus_mod_store_results_count, mods.size()));
	}

	private View buildSummaryCard(int count, String query) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.sectionTitle(this, getString(R.string.nexus_mod_store_results_count, count)));
		String summary = TextUtils.isEmpty(query)
			? getString(R.string.nexus_mod_store_results_discover_summary)
			: getString(R.string.nexus_mod_store_results_query_summary, query);
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(this, summary));
		return card;
	}

	private View buildEmptyResultsCard(String query) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setGravity(Gravity.CENTER_HORIZONTAL);
		content.addView(ExtraSettingsUi.iconCircle(this, R.drawable.ic_search_24, ExtraSettingsUi.COLOR_SECONDARY_CONTAINER, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.sectionTitle(this, R.string.nexus_mod_store_no_results));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(this, R.string.nexus_mod_store_no_results_hint));
		MaterialButton webSearch = ExtraSettingsUi.outlineButton(this, R.string.nexus_mod_store_open_web_search, R.drawable.ic_open_in_new_24);
		webSearch.setOnClickListener(v -> openUrl(apiClient().searchUrl(query)));
		ExtraSettingsUi.addSmallSpacing(content, webSearch);
		return card;
	}

	private void showWelcomeState() {
		if (resultsContainer == null) {
			return;
		}
		resultsContainer.removeAllViews();
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setGravity(Gravity.CENTER_HORIZONTAL);
		content.addView(ExtraSettingsUi.iconCircle(this, R.drawable.ic_extension_24, ExtraSettingsUi.COLOR_PRIMARY_CONTAINER, ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.sectionTitle(this, R.string.nexus_mod_store_welcome_title));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(this, R.string.nexus_mod_store_welcome_message));
		MaterialButton saveKey = ExtraSettingsUi.filledButton(this, R.string.nexus_mod_store_save_api_key, R.drawable.ic_save_24);
		saveKey.setOnClickListener(v -> showApiKeyDialog());
		ExtraSettingsUi.addSmallSpacing(content, saveKey);
		ExtraSettingsUi.addCardSpacing(resultsContainer, card);
		setIdleStatus(getString(R.string.nexus_mod_store_status_idle));
	}

	private View buildModCard(NexusModsApiClient.NexusMod mod) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(this);
		card.setRadius(ExtraSettingsUi.dp(this, 18));
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setPadding(ExtraSettingsUi.dp(this, 16), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 16), ExtraSettingsUi.dp(this, 14));

		LinearLayout top = ExtraSettingsUi.horizontal(this);
		LinearLayout textColumn = ExtraSettingsUi.vertical(this);
		top.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		textColumn.addView(ExtraSettingsUi.sectionTitle(this, mod.name));
		String byline = TextUtils.isEmpty(mod.author) ? getString(R.string.nexus_mod_store_mod_id_format, mod.modId) : getString(R.string.nexus_mod_store_mod_byline, mod.author, mod.modId);
		textColumn.addView(ExtraSettingsUi.caption(this, byline));
		MaterialButton more = ExtraSettingsUi.iconButton(this, R.drawable.ic_info_24);
		more.setContentDescription(getString(R.string.mod_action_info));
		more.setOnClickListener(v -> showModDetails(mod));
		top.addView(more);
		content.addView(top);

		String summary = firstNonEmpty(mod.summary, mod.description, getString(R.string.nexus_mod_store_no_summary));
		TextView summaryText = ExtraSettingsUi.body(this, summary);
		summaryText.setMaxLines(3);
		summaryText.setEllipsize(android.text.TextUtils.TruncateAt.END);
		ExtraSettingsUi.addSmallSpacing(content, summaryText);

		LinearLayout meta = ExtraSettingsUi.vertical(this);
		addMetaInfo(meta, R.drawable.ic_badge_24, getString(R.string.nexus_mod_store_mod_id_format, mod.modId));
		addMetaInfo(meta, R.drawable.ic_code_24, mod.category);
		addMetaInfo(meta, R.drawable.ic_download_24, TextUtils.isEmpty(mod.downloads) ? "" : getString(R.string.nexus_mod_store_downloads_format, mod.downloads));
		addMetaInfo(meta, R.drawable.ic_sync_24, TextUtils.isEmpty(mod.updatedDate) ? "" : getString(R.string.nexus_mod_store_updated_format, mod.updatedDate));
		ExtraSettingsUi.addSmallSpacing(content, meta);

		LinearLayout actions = ExtraSettingsUi.horizontal(this);
		MaterialButton files = ExtraSettingsUi.filledButton(this, R.string.nexus_mod_store_files, R.drawable.ic_download_24);
		MaterialButton web = ExtraSettingsUi.outlineButton(this, R.string.nexus_mod_store_open_web, R.drawable.ic_open_in_new_24);
		files.setOnClickListener(v -> loadFiles(mod));
		web.setOnClickListener(v -> openUrl(firstNonEmpty(mod.modPageUrl, apiClient().modPageUrl(mod.modId))));
		actions.addView(files, weightedButtonParams(0));
		actions.addView(web, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(content, actions);

		card.setOnClickListener(v -> showModDetails(mod));
		return card;
	}

	private void addMetaInfo(LinearLayout parent, int iconRes, String value) {
		if (TextUtils.isEmpty(value)) {
			return;
		}
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.icon(this, iconRes, ExtraSettingsUi.COLOR_MUTED, 15));
		TextView text = ExtraSettingsUi.caption(this, value);
		text.setSingleLine(true);
		text.setEllipsize(android.text.TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(this, 6));
		row.addView(text, params);
		parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
	}

	private void showModDetails(NexusModsApiClient.NexusMod mod) {
		StringBuilder message = new StringBuilder();
		appendLine(message, "ID", mod.modId);
		appendLine(message, getString(R.string.mod_detail_author), mod.author);
		appendLine(message, getString(R.string.mod_detail_version), mod.version);
		appendLine(message, getString(R.string.mod_detail_category), mod.category);
		appendLine(message, getString(R.string.nexus_mod_store_downloads_label), mod.downloads);
		appendLine(message, getString(R.string.nexus_mod_store_updated_label), mod.updatedDate);
		String body = firstNonEmpty(mod.description, mod.summary);
		if (!TextUtils.isEmpty(body)) {
			message.append('\n').append(body);
		}
		new MaterialAlertDialogBuilder(this)
			.setTitle(mod.name)
			.setMessage(message.toString().trim())
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.nexus_mod_store_open_web, (dialog, which) -> openUrl(firstNonEmpty(mod.modPageUrl, apiClient().modPageUrl(mod.modId))))
			.setPositiveButton(R.string.nexus_mod_store_files, (dialog, which) -> loadFiles(mod))
			.show();
	}

	private void loadFiles(NexusModsApiClient.NexusMod mod) {
		if (TextUtils.isEmpty(mod.modId)) {
			openUrl(firstNonEmpty(mod.modPageUrl, apiClient().searchUrl(mod.name)));
			return;
		}
		runStoreOperation(getString(R.string.nexus_mod_store_status_loading_files), () -> apiClient().listFiles(mod.modId), files -> showFileSelectionDialog(mod, files));
	}

	private void showFileSelectionDialog(NexusModsApiClient.NexusMod mod, List<NexusModsApiClient.NexusModFile> files) {
		if (files == null || files.isEmpty()) {
			new MaterialAlertDialogBuilder(this)
				.setTitle(mod.name)
				.setMessage(R.string.nexus_mod_store_no_files)
				.setNegativeButton(android.R.string.ok, null)
				.setPositiveButton(R.string.nexus_mod_store_open_web, (dialog, which) -> openUrl(apiClient().modFilesUrl(mod.modId)))
				.show();
			return;
		}

		ScrollView scrollView = new ScrollView(this);
		LinearLayout list = ExtraSettingsUi.vertical(this);
		int pad = ExtraSettingsUi.dp(this, 4);
		list.setPadding(pad, pad, pad, pad);
		scrollView.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		list.addView(ExtraSettingsUi.body(this, R.string.nexus_mod_store_file_dialog_hint));
		final AlertDialog[] dialogRef = new AlertDialog[1];
		for (NexusModsApiClient.NexusModFile file : files) {
			ExtraSettingsUi.addCardSpacing(list, buildFileCard(mod, file, dialogRef));
		}
		dialogRef[0] = new MaterialAlertDialogBuilder(this)
			.setTitle(getString(R.string.nexus_mod_store_files_title, mod.name))
			.setView(scrollView)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.nexus_mod_store_open_web, (dialog, which) -> openUrl(apiClient().modFilesUrl(mod.modId)))
			.create();
		dialogRef[0].show();
	}

	private View buildFileCard(NexusModsApiClient.NexusMod mod, NexusModsApiClient.NexusModFile file, AlertDialog[] dialogRef) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setRadius(ExtraSettingsUi.dp(this, 16));
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setPadding(ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12));
		content.addView(ExtraSettingsUi.sectionTitle(this, file.name));
		String meta = buildFileMeta(file);
		if (!TextUtils.isEmpty(meta)) {
			content.addView(ExtraSettingsUi.caption(this, meta));
		}
		if (!TextUtils.isEmpty(file.description)) {
			TextView description = ExtraSettingsUi.body(this, file.description);
			description.setMaxLines(2);
			description.setEllipsize(android.text.TextUtils.TruncateAt.END);
			ExtraSettingsUi.addSmallSpacing(content, description);
		}
		MaterialButton download = ExtraSettingsUi.filledButton(this, R.string.nexus_mod_store_download_and_import, R.drawable.ic_download_24);
		download.setOnClickListener(v -> {
			if (dialogRef[0] != null) {
				dialogRef[0].dismiss();
			}
			downloadAndInstall(mod, file, "", "");
		});
		ExtraSettingsUi.addSmallSpacing(content, download);
		return card;
	}

	private String buildFileMeta(NexusModsApiClient.NexusModFile file) {
		StringBuilder builder = new StringBuilder();
		if (file.primary) {
			builder.append(getString(R.string.nexus_mod_store_primary_file));
		}
		appendMetaPart(builder, file.category);
		appendMetaPart(builder, file.version);
		appendMetaPart(builder, file.sizeLabel);
		appendMetaPart(builder, file.uploadedDate);
		appendMetaPart(builder, TextUtils.isEmpty(file.fileId) ? "" : "#" + file.fileId);
		return builder.toString();
	}

	private void appendMetaPart(StringBuilder builder, String part) {
		if (TextUtils.isEmpty(part)) {
			return;
		}
		if (builder.length() > 0) {
			builder.append(" · ");
		}
		builder.append(part);
	}

	private void downloadAndInstall(NexusModsApiClient.NexusMod mod, NexusModsApiClient.NexusModFile file, String key, String expires) {
		if (!ensureApiKey() || busy) {
			return;
		}
		beginBusy(getString(R.string.nexus_mod_store_status_getting_download_link));
		new Thread(() -> {
			File downloadedFile = null;
			try {
				NexusModsApiClient client = apiClient();
				List<NexusModsApiClient.DownloadLink> links = client.getDownloadLinks(mod.modId, file.fileId, key, expires);
				if (links.isEmpty()) {
					throw new NexusModsApiClient.NexusApiException(403, "", getString(R.string.nexus_mod_store_download_link_empty));
				}
				NexusModsApiClient.DownloadLink link = links.get(0);
				runOnUiThread(() -> updateProgress(0, getString(R.string.nexus_mod_store_status_downloading_from, link.name)));
				downloadedFile = client.downloadToCache(link.uri, fallbackDownloadFileName(mod, file), (percent, copied, total) -> runOnUiThread(() -> updateProgress(percent, getString(R.string.nexus_mod_store_status_downloading_percent, percent))));
				ExtraSettingsRepository.PreparedModImport preparedImport = repository.prepareDownloadedModImport(downloadedFile, downloadedFile.getName());
				File finalDownloadedFile = downloadedFile;
				runOnUiThread(() -> {
					finishBusy(getString(R.string.nexus_mod_store_status_idle));
					if (finalDownloadedFile != null && finalDownloadedFile.exists()) {
						// Best effort cleanup of the cache copy after staging is prepared.
						finalDownloadedFile.delete();
					}
					handlePreparedDownloadedModImport(preparedImport);
				});
			} catch (NexusModsApiClient.NexusApiException exception) {
				File finalDownloadedFile = downloadedFile;
				runOnUiThread(() -> {
					finishBusy(getString(R.string.nexus_mod_store_status_idle));
					if (finalDownloadedFile != null && finalDownloadedFile.exists()) {
						finalDownloadedFile.delete();
					}
					showDownloadPermissionDialog(mod, file, exception);
				});
			} catch (Exception exception) {
				File finalDownloadedFile = downloadedFile;
				runOnUiThread(() -> {
					finishBusy(getString(R.string.nexus_mod_store_status_idle));
					if (finalDownloadedFile != null && finalDownloadedFile.exists()) {
						finalDownloadedFile.delete();
					}
					showError(exception);
				});
			}
		}, "sts2-nexus-download").start();
	}

	private void handlePreparedDownloadedModImport(ExtraSettingsRepository.PreparedModImport preparedImport) {
		List<ExtraSettingsRepository.ModImportConflict> idConflicts = repository.findCurrentImportConflicts(preparedImport);
		if (!idConflicts.isEmpty()) {
			showModImportConflictDialog(preparedImport, idConflicts,
				() -> {
					repository.discardPreparedModImport(preparedImport);
					showMessage(getString(R.string.status_import_mod_cancelled));
				},
				() -> handlePreparedDownloadedModPathConflicts(preparedImport, true, idConflicts),
				() -> {
					repository.discardPreparedModImport(preparedImport);
					showMessage(getString(R.string.status_import_mod_cancelled));
				});
			return;
		}
		handlePreparedDownloadedModPathConflicts(preparedImport, false, idConflicts);
	}

	private void handlePreparedDownloadedModPathConflicts(ExtraSettingsRepository.PreparedModImport preparedImport, boolean replaceExistingConflicts, List<ExtraSettingsRepository.ModImportConflict> confirmedIdConflicts) {
		List<ExtraSettingsRepository.ModImportPathConflict> pathConflicts = repository.findCurrentImportPathConflicts(preparedImport, replaceExistingConflicts ? confirmedIdConflicts : new ArrayList<>());
		if (!pathConflicts.isEmpty()) {
			showModImportPathConflictDialog(preparedImport, pathConflicts,
				() -> {
					repository.discardPreparedModImport(preparedImport);
					showMessage(getString(R.string.status_import_mod_cancelled));
				},
				() -> commitPreparedDownloadedModImport(preparedImport, replaceExistingConflicts, true),
				() -> {
					repository.discardPreparedModImport(preparedImport);
					showMessage(getString(R.string.status_import_mod_cancelled));
				});
			return;
		}
		commitPreparedDownloadedModImport(preparedImport, replaceExistingConflicts, false);
	}

	private void commitPreparedDownloadedModImport(ExtraSettingsRepository.PreparedModImport preparedImport, boolean replaceExistingConflicts, boolean allowPathConflicts) {
		if (busy) {
			return;
		}
		beginBusy(getString(R.string.status_busy_import_mod));
		new Thread(() -> {
			try {
				String importedName = repository.commitPreparedModImport(preparedImport, replaceExistingConflicts, allowPathConflicts);
				runOnUiThread(() -> {
					finishBusy(getString(R.string.nexus_mod_store_status_download_done, importedName));
					showMessage(getString(R.string.nexus_mod_store_status_download_done, importedName));
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					finishBusy(getString(R.string.nexus_mod_store_status_idle));
					showError(exception);
				});
			}
		}, "sts2-nexus-mod-import-commit").start();
	}

	private void showModImportConflictDialog(ExtraSettingsRepository.PreparedModImport preparedImport, List<ExtraSettingsRepository.ModImportConflict> conflicts, Runnable keepOriginal, Runnable useNew, Runnable cancelImport) {
		LinearLayout content = new LinearLayout(this);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(ExtraSettingsUi.dp(this, 4), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 4), 0);
		content.addView(ExtraSettingsUi.body(this, getString(R.string.mod_import_conflict_message)));
		for (ExtraSettingsRepository.ModImportConflict conflict : conflicts) {
			TextView conflictTitle = ExtraSettingsUi.text(this, getString(R.string.mod_import_conflict_id_format, conflict.modId), 15, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD);
			LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			titleParams.topMargin = ExtraSettingsUi.dp(this, 12);
			content.addView(conflictTitle, titleParams);
			if (!conflict.existingEntries.isEmpty()) {
				content.addView(buildImportConflictInfoCard(getString(R.string.mod_import_conflict_original), conflict.existingEntries.get(0)));
			}
			content.addView(buildImportConflictInfoCard(getString(R.string.mod_import_conflict_incoming), conflict.incomingEntry));
		}
		ScrollView scrollView = new ScrollView(this);
		scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.mod_import_conflict_title)
			.setView(scrollView)
			.setNegativeButton(R.string.mod_import_conflict_keep_original, (dialog, which) -> keepOriginal.run())
			.setNeutralButton(android.R.string.cancel, (dialog, which) -> cancelImport.run())
			.setPositiveButton(R.string.mod_import_conflict_use_new, (dialog, which) -> useNew.run())
			.setOnCancelListener(dialog -> cancelImport.run())
			.show();
	}

	private void showModImportPathConflictDialog(ExtraSettingsRepository.PreparedModImport preparedImport, List<ExtraSettingsRepository.ModImportPathConflict> conflicts, Runnable keepInstalled, Runnable replaceFiles, Runnable cancelImport) {
		LinearLayout content = new LinearLayout(this);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(ExtraSettingsUi.dp(this, 4), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 4), 0);
		content.addView(ExtraSettingsUi.body(this, getString(R.string.mod_import_path_conflict_message)));
		int visibleCount = Math.min(12, conflicts.size());
		for (int i = 0; i < visibleCount; i++) {
			ExtraSettingsRepository.ModImportPathConflict conflict = conflicts.get(i);
			MaterialCardView card = ExtraSettingsUi.card(this);
			card.setRadius(ExtraSettingsUi.dp(this, 16));
			card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
			card.setStrokeColor(ExtraSettingsUi.COLOR_OUTLINE);
			card.setStrokeWidth(ExtraSettingsUi.dp(this, 1));
			LinearLayout cardContent = ExtraSettingsUi.cardContent(this, card);
			cardContent.setPadding(ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12));
			cardContent.addView(ExtraSettingsUi.text(this, conflict.relativePath, 14, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD));
			if (!TextUtils.isEmpty(conflict.existingOwnerLabel)) {
				cardContent.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_import_path_conflict_owner, conflict.existingOwnerLabel)));
			}
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			params.topMargin = ExtraSettingsUi.dp(this, 8);
			content.addView(card, params);
		}
		if (conflicts.size() > visibleCount) {
			content.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_import_path_conflict_more, conflicts.size() - visibleCount)));
		}
		ScrollView scrollView = new ScrollView(this);
		scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.mod_import_path_conflict_title)
			.setView(scrollView)
			.setNegativeButton(R.string.mod_import_path_conflict_keep_installed, (dialog, which) -> keepInstalled.run())
			.setNeutralButton(android.R.string.cancel, (dialog, which) -> cancelImport.run())
			.setPositiveButton(R.string.mod_import_path_conflict_replace_files, (dialog, which) -> replaceFiles.run())
			.setOnCancelListener(dialog -> cancelImport.run())
			.show();
	}

	private View buildImportConflictInfoCard(String label, ExtraSettingsRepository.ModEntry entry) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setRadius(ExtraSettingsUi.dp(this, 16));
		card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		card.setStrokeColor(ExtraSettingsUi.COLOR_OUTLINE);
		card.setStrokeWidth(ExtraSettingsUi.dp(this, 1));
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setPadding(ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12));
		content.addView(ExtraSettingsUi.text(this, label, 15, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD));
		content.addView(ExtraSettingsUi.body(this, entry.displayName));
		content.addView(ExtraSettingsUi.caption(this, "ID: " + entry.modId));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_detail_version) + ": " + emptyToDash(entry.version)));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_detail_author) + ": " + emptyToDash(entry.authors)));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_detail_path) + ": " + entry.relativePath));
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(this, 8);
		card.setLayoutParams(params);
		return card;
	}

	private String emptyToDash(String value) {
		return TextUtils.isEmpty(value) ? "—" : value;
	}

	private String fallbackDownloadFileName(NexusModsApiClient.NexusMod mod, NexusModsApiClient.NexusModFile file) {
		String base = (TextUtils.isEmpty(mod.name) ? "mod-" + mod.modId : mod.name) + "-" + (TextUtils.isEmpty(file.name) ? file.fileId : file.name);
		String lower = base.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar")) {
			return base;
		}
		return base + ".zip";
	}

	private void showDownloadPermissionDialog(NexusModsApiClient.NexusMod mod, NexusModsApiClient.NexusModFile file, NexusModsApiClient.NexusApiException exception) {
		String message = getString(R.string.nexus_mod_store_download_permission_message, exception.getMessage());
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.nexus_mod_store_download_permission_title)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.nexus_mod_store_open_web, (dialog, which) -> openUrl(apiClient().modFilesUrl(mod.modId)))
			.setPositiveButton(R.string.nexus_mod_store_paste_nxm_link, (dialog, which) -> showNxmLinkDialog())
			.show();
	}

	private void showNxmLinkDialog() {
		if (!ensureApiKey()) {
			return;
		}
		LinearLayout content = ExtraSettingsUi.vertical(this);
		content.addView(ExtraSettingsUi.body(this, R.string.nexus_mod_store_nxm_link_hint));
		TextInputLayout inputLayout = new TextInputLayout(this);
		inputLayout.setHint(getString(R.string.nexus_mod_store_nxm_link_input_hint));
		inputLayout.setBoxBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		TextInputEditText input = new TextInputEditText(inputLayout.getContext());
		input.setSingleLine(false);
		input.setMinLines(2);
		input.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		inputLayout.addView(input);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(this, 12);
		content.addView(inputLayout, params);

		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.nexus_mod_store_paste_nxm_link)
			.setView(content)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.nexus_mod_store_download_and_import, (dialog, which) -> {
				String raw = input.getText() == null ? "" : input.getText().toString();
				NexusModsApiClient.NxmDownloadToken token = NexusModsApiClient.parseNxmLink(raw);
				if (token == null || !token.isComplete()) {
					showMessage(getString(R.string.nexus_mod_store_nxm_link_invalid));
					return;
				}
				String configuredDomain = NexusModsStorePreferences.getGameDomain(this);
				if (!configuredDomain.equalsIgnoreCase(token.gameDomain)) {
					showMessage(getString(R.string.nexus_mod_store_nxm_link_wrong_game, configuredDomain, token.gameDomain));
					return;
				}
				NexusModsApiClient.NexusMod mod = new NexusModsApiClient.NexusMod(token.modId, getString(R.string.nexus_mod_store_mod_id_format, token.modId), "", "", "", "", "", "", apiClient().modPageUrl(token.modId), "", "", "");
				NexusModsApiClient.NexusModFile file = new NexusModsApiClient.NexusModFile(token.fileId, getString(R.string.nexus_mod_store_file_id_format, token.fileId), "", "", "", "", "", true);
				downloadAndInstall(mod, file, token.key, token.expires);
			})
			.show();
	}

	private <T> void runStoreOperation(String busyMessage, StoreWorker<T> worker, StoreSuccess<T> success) {
		if (busy) {
			return;
		}
		beginBusy(busyMessage);
		new Thread(() -> {
			try {
				T result = worker.run();
				runOnUiThread(() -> {
					finishBusy(getString(R.string.nexus_mod_store_status_idle));
					success.onSuccess(result);
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					finishBusy(getString(R.string.nexus_mod_store_status_idle));
					showError(exception);
				});
			}
		}, "sts2-nexus-store").start();
	}

	private void beginBusy(String message) {
		busy = true;
		if (progressBar != null) {
			progressBar.setIndeterminate(true);
			progressBar.setVisibility(View.VISIBLE);
		}
		if (statusText != null) {
			statusText.setText(message);
		}
	}

	private void updateProgress(int percent, String message) {
		if (progressBar != null) {
			progressBar.setVisibility(View.VISIBLE);
			progressBar.setIndeterminate(false);
			progressBar.setProgress(Math.max(0, Math.min(100, percent)));
		}
		if (statusText != null) {
			statusText.setText(message);
		}
	}

	private void finishBusy(String message) {
		busy = false;
		if (progressBar != null) {
			progressBar.setVisibility(View.GONE);
			progressBar.setIndeterminate(true);
		}
		setIdleStatus(message);
	}

	private void setIdleStatus(String message) {
		if (statusText != null && !TextUtils.isEmpty(message)) {
			statusText.setText(message);
		}
	}

	private void showError(Exception exception) {
		String detail = exception.getMessage();
		if (TextUtils.isEmpty(detail)) {
			detail = exception.getClass().getSimpleName();
		}
		showMessage(getString(R.string.error_operation_failed) + ": " + detail);
	}

	private void showMessage(String message) {
		View anchor = findViewById(android.R.id.content);
		if (anchor != null && !TextUtils.isEmpty(message)) {
			Snackbar.make(anchor, message, Snackbar.LENGTH_LONG).show();
		}
	}

	private void openUrl(String url) {
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
		} catch (Exception exception) {
			showError(exception);
		}
	}

	private void appendLine(StringBuilder builder, String label, String value) {
		if (TextUtils.isEmpty(value)) {
			return;
		}
		builder.append(label).append(": ").append(value).append('\n');
	}

	private String firstNonEmpty(String... values) {
		for (String value : values) {
			if (!TextUtils.isEmpty(value)) {
				return value.trim();
			}
		}
		return "";
	}

	private interface StoreWorker<T> {
		T run() throws Exception;
	}

	private interface StoreSuccess<T> {
		void onSuccess(T result);
	}
}
