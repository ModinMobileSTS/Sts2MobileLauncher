package com.godot.game;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.godot.game.steam.auth.SteamAuthStore;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SteamWorkshopActivity extends AppCompatActivity {
	private static final String WORKSHOP_WEB_URL = "https://steamcommunity.com/app/2868840/workshop/";
	private static final long AUTO_UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

	private ExtraSettingsRepository repository;
	private SteamWorkshopCatalog catalog;
	private SteamWorkshopLibrary library;
	private LinearLayout resultsContainer;
	private TextInputEditText searchInput;
	private TextView steamStatusText;
	private TextView settingsSummaryText;
	private TextView statusText;
	private ProgressBar progressBar;
	private int currentPage = 1;
	private String currentQuery = "";
	private boolean busy;
	private boolean autoUpdateCheckStarted;
	private boolean libraryVisible;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		repository = new ExtraSettingsRepository(this);
		repository.ensureAppDirectories();
		catalog = new SteamWorkshopCatalog(this);
		library = new SteamWorkshopLibrary(this);
		setContentView(buildContent());
		refreshSteamStatus();
		refreshSettingsSummary();
		searchWorkshop("", 1);
	}

	private View buildContent() {
		FrameLayout frame = new FrameLayout(this);
		frame.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		ScrollView scrollView = new ScrollView(this);
		scrollView.setFillViewport(false);
		scrollView.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		LinearLayout root = ExtraSettingsUi.vertical(this);
		root.setPadding(0, ExtraSettingsUi.dp(this, 24), 0, ExtraSettingsUi.dp(this, 40));
		ExtraSettingsUi.addResponsiveScrollContent(this, scrollView, root);

		root.addView(buildTopBar());
		ExtraSettingsUi.addCardSpacing(root, buildSteamCard());
		ExtraSettingsUi.addCardSpacing(root, buildSearchCard());
		ExtraSettingsUi.addCardSpacing(root, buildSettingsCard());
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
		titles.addView(ExtraSettingsUi.title(this, R.string.workshop_title));
		TextView subtitle = ExtraSettingsUi.body(this, R.string.workshop_subtitle);
		subtitle.setMaxLines(2);
		titles.addView(subtitle);

		MaterialButton openWeb = ExtraSettingsUi.iconButton(this, R.drawable.ic_open_in_new_24);
		openWeb.setContentDescription(getString(R.string.workshop_open_web));
		openWeb.setOnClickListener(v -> openUrl(WORKSHOP_WEB_URL));
		row.addView(openWeb);
		return row;
	}

	private View buildSteamCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.iconTitleRow(
			this,
			R.drawable.ic_steam_24,
			R.string.workshop_steam_title,
			R.string.workshop_steam_subtitle,
			null
		));
		steamStatusText = ExtraSettingsUi.body(this, "");
		ExtraSettingsUi.addSmallSpacing(content, steamStatusText);
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		MaterialButton openSteam = ExtraSettingsUi.filledButton(this, R.string.steam_account_open, R.drawable.ic_steam_24);
		MaterialButton verify = ExtraSettingsUi.outlineButton(this, R.string.steam_verify_login, R.drawable.ic_check_circle_24);
		openSteam.setOnClickListener(v -> startActivity(new Intent(this, SteamAccountActivity.class)));
		verify.setOnClickListener(v -> {
			refreshSteamStatus();
			showMessage(getString(R.string.workshop_steam_status_refreshed));
		});
		row.addView(openSteam, weightedButtonParams(0));
		row.addView(verify, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(content, row);
		return card;
	}

	private View buildSearchCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.iconTitleRow(
			this,
			R.drawable.ic_search_24,
			R.string.workshop_search_title,
			R.string.workshop_search_subtitle,
			null
		));

		TextInputLayout searchLayout = new TextInputLayout(this);
		searchLayout.setHint(getString(R.string.workshop_search_hint));
		searchLayout.setStartIconDrawable(MaterialSymbols.drawable(this, R.drawable.ic_search_24, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 24));
		searchLayout.setBoxBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		searchInput = new TextInputEditText(searchLayout.getContext());
		searchInput.setSingleLine(true);
		searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
		searchInput.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		searchInput.setHintTextColor(ExtraSettingsUi.COLOR_MUTED);
		searchInput.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_SEARCH) {
				searchWorkshop(readSearchQuery(), 1);
				return true;
			}
			return false;
		});
		searchLayout.addView(searchInput);
		ExtraSettingsUi.addSmallSpacing(content, searchLayout);

		LinearLayout row = ExtraSettingsUi.horizontal(this);
		MaterialButton search = ExtraSettingsUi.filledButton(this, R.string.workshop_search, R.drawable.ic_search_24);
		MaterialButton libraryButton = ExtraSettingsUi.tonalButton(this, R.string.workshop_library, R.drawable.ic_layers_24);
		search.setOnClickListener(v -> searchWorkshop(readSearchQuery(), 1));
		libraryButton.setOnClickListener(v -> showLibrary());
		row.addView(search, weightedButtonParams(0));
		row.addView(libraryButton, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(content, row);

		LinearLayout secondRow = ExtraSettingsUi.horizontal(this);
		MaterialButton checkUpdates = ExtraSettingsUi.outlineButton(this, R.string.workshop_check_updates, R.drawable.ic_sync_24);
		MaterialButton openWeb = ExtraSettingsUi.outlineButton(this, R.string.workshop_open_web, R.drawable.ic_open_in_new_24);
		checkUpdates.setOnClickListener(v -> checkTrackedUpdates());
		openWeb.setOnClickListener(v -> openUrl(WORKSHOP_WEB_URL));
		secondRow.addView(checkUpdates, weightedButtonParams(0));
		secondRow.addView(openWeb, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(content, secondRow);

		progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		progressBar.setMax(100);
		progressBar.setIndeterminate(true);
		progressBar.setVisibility(View.GONE);
		ExtraSettingsUi.addSmallSpacing(content, progressBar);

		statusText = ExtraSettingsUi.caption(this, getString(R.string.workshop_status_idle));
		ExtraSettingsUi.addSmallSpacing(content, statusText);
		return card;
	}

	private View buildSettingsCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.iconTitleRow(
			this,
			R.drawable.ic_settings_24,
			R.string.workshop_settings_title,
			R.string.workshop_settings_subtitle,
			null
		));
		settingsSummaryText = ExtraSettingsUi.body(this, "");
		ExtraSettingsUi.addSmallSpacing(content, settingsSummaryText);

		LinearLayout row = ExtraSettingsUi.horizontal(this);
		MaterialButton group = ExtraSettingsUi.outlineButton(this, R.string.workshop_download_group, R.drawable.ic_folder_24);
		MaterialButton chunks = ExtraSettingsUi.outlineButton(this, R.string.workshop_concurrent_chunks, R.drawable.ic_tune_24);
		group.setOnClickListener(v -> showDownloadGroupDialog());
		chunks.setOnClickListener(v -> showConcurrentChunksDialog());
		row.addView(group, weightedButtonParams(0));
		row.addView(chunks, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(content, row);

		LinearLayout switchRow = ExtraSettingsUi.horizontal(this);
		switchRow.setGravity(Gravity.CENTER_VERTICAL);
		TextView label = ExtraSettingsUi.body(this, R.string.workshop_auto_update_check);
		switchRow.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		MaterialSwitch autoCheck = new MaterialSwitch(this);
		autoCheck.setChecked(SteamWorkshopPreferences.isAutoCheckUpdatesEnabled(this));
		autoCheck.setOnCheckedChangeListener((buttonView, checked) -> {
			SteamWorkshopPreferences.setAutoCheckUpdatesEnabled(this, checked);
			refreshSettingsSummary();
		});
		switchRow.addView(autoCheck);
		ExtraSettingsUi.addSmallSpacing(content, switchRow);

		LinearLayout directAccessRow = ExtraSettingsUi.horizontal(this);
		directAccessRow.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout directText = ExtraSettingsUi.vertical(this);
		directAccessRow.addView(directText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		directText.addView(ExtraSettingsUi.body(this, R.string.workshop_direct_access));
		directText.addView(ExtraSettingsUi.caption(this, getString(R.string.workshop_direct_access_hint)));
		MaterialSwitch directAccess = new MaterialSwitch(this);
		directAccess.setChecked(SteamWorkshopPreferences.isDirectAccessEnabled(this));
		directAccess.setOnCheckedChangeListener((buttonView, checked) -> {
			SteamWorkshopPreferences.setDirectAccessEnabled(this, checked);
			refreshSettingsSummary();
			if (!checked) {
				showMessage(getString(R.string.workshop_direct_access_disabled_notice));
			}
		});
		directAccessRow.addView(directAccess);
		ExtraSettingsUi.addSmallSpacing(content, directAccessRow);
		return card;
	}

	private void refreshSteamStatus() {
		if (steamStatusText == null) {
			return;
		}
		SteamAuthStore.AuthSnapshot auth = SteamAuthStore.readSnapshot(this);
		if (!auth.refreshTokenConfigured) {
			steamStatusText.setText(R.string.workshop_steam_not_logged_in);
		} else {
			steamStatusText.setText(getString(R.string.workshop_steam_logged_in, auth.accountName, emptyToDash(auth.steamId64)));
		}
	}

	private void refreshSettingsSummary() {
		if (settingsSummaryText == null) {
			return;
		}
		settingsSummaryText.setText(getString(
			R.string.workshop_settings_summary,
			SteamWorkshopPreferences.getDownloadGroup(this),
			SteamWorkshopPreferences.getConcurrentChunks(this),
			SteamWorkshopPreferences.isAutoCheckUpdatesEnabled(this) ? getString(R.string.workshop_enabled) : getString(R.string.workshop_disabled),
			SteamWorkshopPreferences.isDirectAccessEnabled(this) ? getString(R.string.workshop_enabled) : getString(R.string.workshop_disabled)
		));
	}

	private String readSearchQuery() {
		return searchInput == null || searchInput.getText() == null ? "" : searchInput.getText().toString().trim();
	}

	private void searchWorkshop(String query, int page) {
		if (busy) {
			return;
		}
		currentQuery = query == null ? "" : query.trim();
		currentPage = Math.max(1, page);
		runOperation(getString(R.string.workshop_status_searching), () -> catalog.search(currentQuery, currentPage, 30), this::showSearchResults);
	}

	private void showSearchResults(SteamWorkshopCatalog.SearchResult result) {
		libraryVisible = false;
		resultsContainer.removeAllViews();
		if (result.getItems().isEmpty()) {
			ExtraSettingsUi.addCardSpacing(resultsContainer, buildEmptyCard(R.string.workshop_no_results, R.string.workshop_no_results_hint));
			setIdleStatus(getString(R.string.workshop_no_results));
			maybeAutoCheckTrackedUpdates();
			return;
		}
		ExtraSettingsUi.addCardSpacing(resultsContainer, buildSummaryCard(getString(R.string.workshop_results_summary, result.getTotal(), result.getPage(), TextUtils.isEmpty(currentQuery) ? getString(R.string.workshop_default_query_label) : currentQuery)));
		for (SteamWorkshopCatalog.Item item : result.getItems()) {
			ExtraSettingsUi.addCardSpacing(resultsContainer, buildWorkshopItemCard(item));
		}
		ExtraSettingsUi.addCardSpacing(resultsContainer, buildPagerCard(result));
		setIdleStatus(getString(R.string.workshop_results_count, result.getItems().size()));
		maybeAutoCheckTrackedUpdates();
	}

	private View buildSummaryCard(String summary) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.sectionTitle(this, R.string.workshop_results_title));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(this, summary));
		return card;
	}

	private View buildWorkshopItemCard(SteamWorkshopCatalog.Item item) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(this);
		card.setRadius(ExtraSettingsUi.dp(this, 18));
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setPadding(ExtraSettingsUi.dp(this, 16), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 16), ExtraSettingsUi.dp(this, 14));

		LinearLayout top = ExtraSettingsUi.horizontal(this);
		LinearLayout textColumn = ExtraSettingsUi.vertical(this);
		top.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		textColumn.addView(ExtraSettingsUi.sectionTitle(this, item.getTitle()));
		textColumn.addView(ExtraSettingsUi.caption(this, getString(R.string.workshop_item_byline, item.getPublishedFileId(), formatDate(item.getTimeUpdatedEpochSeconds() * 1000L))));
		MaterialButton info = ExtraSettingsUi.iconButton(this, R.drawable.ic_info_24);
		info.setContentDescription(getString(R.string.mod_action_info));
		info.setOnClickListener(v -> showItemDetails(item));
		top.addView(info);
		content.addView(top);

		TextView description = ExtraSettingsUi.body(this, TextUtils.isEmpty(item.getDescription()) ? getString(R.string.workshop_no_description) : item.getDescription());
		description.setMaxLines(3);
		description.setEllipsize(android.text.TextUtils.TruncateAt.END);
		ExtraSettingsUi.addSmallSpacing(content, description);

		LinearLayout meta = ExtraSettingsUi.vertical(this);
		addMetaInfo(meta, R.drawable.ic_download_24, getString(R.string.workshop_file_size, formatBytes(item.getFileSizeBytes())));
		addMetaInfo(meta, R.drawable.ic_groups_24, getString(R.string.workshop_subscriptions, item.getSubscriptions()));
		addMetaInfo(meta, R.drawable.ic_zoom_in_24, getString(R.string.workshop_views, item.getViews()));
		ExtraSettingsUi.addSmallSpacing(content, meta);

		LinearLayout row = ExtraSettingsUi.horizontal(this);
		MaterialButton download = ExtraSettingsUi.filledButton(this, R.string.workshop_download_and_import, R.drawable.ic_download_24);
		MaterialButton web = ExtraSettingsUi.outlineButton(this, R.string.workshop_open_item, R.drawable.ic_open_in_new_24);
		download.setOnClickListener(v -> downloadAndImport(item));
		web.setOnClickListener(v -> openUrl(itemUrl(item)));
		row.addView(download, weightedButtonParams(0));
		row.addView(web, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(content, row);

		card.setOnClickListener(v -> showItemDetails(item));
		return card;
	}

	private View buildPagerCard(SteamWorkshopCatalog.SearchResult result) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		MaterialButton previous = ExtraSettingsUi.outlineButton(this, R.string.previous_page, R.drawable.ic_chevron_right_24);
		previous.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_END);
		MaterialButton next = ExtraSettingsUi.outlineButton(this, R.string.next_page, R.drawable.ic_arrow_forward_24);
		previous.setEnabled(currentPage > 1);
		previous.setOnClickListener(v -> searchWorkshop(currentQuery, currentPage - 1));
		next.setOnClickListener(v -> searchWorkshop(currentQuery, currentPage + 1));
		row.addView(previous, weightedButtonParams(0));
		row.addView(next, weightedButtonParams(8));
		content.addView(row);
		return card;
	}

	private void showWelcomeState() {
		libraryVisible = false;
		resultsContainer.removeAllViews();
		ExtraSettingsUi.addCardSpacing(resultsContainer, buildEmptyCard(R.string.workshop_welcome_title, R.string.workshop_welcome_message));
		setIdleStatus(getString(R.string.workshop_status_idle));
	}

	private View buildEmptyCard(int titleRes, int messageRes) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setGravity(Gravity.CENTER_HORIZONTAL);
		content.addView(ExtraSettingsUi.iconCircle(this, R.drawable.ic_steam_24, ExtraSettingsUi.COLOR_PRIMARY_CONTAINER, ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER));
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.sectionTitle(this, titleRes));
		TextView message = ExtraSettingsUi.body(this, messageRes);
		message.setGravity(Gravity.CENTER);
		ExtraSettingsUi.addSmallSpacing(content, message);
		MaterialButton steam = ExtraSettingsUi.filledButton(this, R.string.steam_account_open, R.drawable.ic_steam_24);
		steam.setOnClickListener(v -> startActivity(new Intent(this, SteamAccountActivity.class)));
		ExtraSettingsUi.addSmallSpacing(content, steam);
		return card;
	}

	private void showItemDetails(SteamWorkshopCatalog.Item item) {
		StringBuilder message = new StringBuilder();
		appendLine(message, "PublishedFileId", item.getPublishedFileId());
		appendLine(message, getString(R.string.workshop_file_size_label), formatBytes(item.getFileSizeBytes()));
		appendLine(message, getString(R.string.workshop_updated_label), formatDate(item.getTimeUpdatedEpochSeconds() * 1000L));
		appendLine(message, getString(R.string.workshop_subscriptions_label), Integer.toString(item.getSubscriptions()));
		appendLine(message, getString(R.string.workshop_views_label), Integer.toString(item.getViews()));
		if (!TextUtils.isEmpty(item.getDescription())) {
			message.append('\n').append(item.getDescription());
		}
		new MaterialAlertDialogBuilder(this)
			.setTitle(item.getTitle())
			.setMessage(message.toString().trim())
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.workshop_open_item, (dialog, which) -> openUrl(itemUrl(item)))
			.setPositiveButton(R.string.workshop_download_and_import, (dialog, which) -> downloadAndImport(item))
			.show();
	}

	private void downloadAndImport(SteamWorkshopCatalog.Item item) {
		if (busy) {
			return;
		}
		SteamOperationProgressDialog progressDialog = new SteamOperationProgressDialog(this, getString(R.string.workshop_download_progress_title), getString(R.string.workshop_status_downloading));
		progressDialog.show();
		busy = true;
		new Thread(() -> {
			SteamWorkshopDownloader.Result result = null;
			try {
				SteamWorkshopDownloader downloader = new SteamWorkshopDownloader(this);
				result = downloader.download(item, progress -> {
					runOnUiThread(() -> progressDialog.setProgress(progress.getPercent(), progress.getMessage()));
					return kotlin.Unit.INSTANCE;
				});
				ExtraSettingsRepository.PreparedModImport prepared = repository.prepareDownloadedModDirectory(result.getOutputDir(), item.getTitle());
				SteamWorkshopDownloader.Result finalResult = result;
				runOnUiThread(() -> {
					progressDialog.dismiss();
					busy = false;
					handlePreparedWorkshopImport(finalResult, prepared);
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					progressDialog.dismiss();
					busy = false;
					showError(exception);
				});
			}
		}, "sts2-workshop-download").start();
	}

	private void handlePreparedWorkshopImport(SteamWorkshopDownloader.Result result, ExtraSettingsRepository.PreparedModImport prepared) {
		List<ExtraSettingsRepository.ModImportConflict> idConflicts = repository.findCurrentImportConflicts(prepared);
		if (!idConflicts.isEmpty()) {
			showModImportConflictDialog(prepared, idConflicts,
				() -> {
					repository.discardPreparedModImport(prepared);
					showMessage(getString(R.string.status_import_mod_cancelled));
				},
				() -> handlePreparedWorkshopPathConflicts(result, prepared, true, idConflicts),
				() -> {
					repository.discardPreparedModImport(prepared);
					showMessage(getString(R.string.status_import_mod_cancelled));
				});
			return;
		}
		handlePreparedWorkshopPathConflicts(result, prepared, false, idConflicts);
	}

	private void handlePreparedWorkshopPathConflicts(SteamWorkshopDownloader.Result result, ExtraSettingsRepository.PreparedModImport prepared, boolean replaceExistingConflicts, List<ExtraSettingsRepository.ModImportConflict> confirmedIdConflicts) {
		List<ExtraSettingsRepository.ModImportPathConflict> pathConflicts = repository.findCurrentImportPathConflicts(prepared, replaceExistingConflicts ? confirmedIdConflicts : new ArrayList<>());
		if (!pathConflicts.isEmpty()) {
			showModImportPathConflictDialog(prepared, pathConflicts,
				() -> {
					repository.discardPreparedModImport(prepared);
					showMessage(getString(R.string.status_import_mod_cancelled));
				},
				() -> commitPreparedWorkshopImport(result, prepared, replaceExistingConflicts, true),
				() -> {
					repository.discardPreparedModImport(prepared);
					showMessage(getString(R.string.status_import_mod_cancelled));
				});
			return;
		}
		commitPreparedWorkshopImport(result, prepared, replaceExistingConflicts, false);
	}

	private void commitPreparedWorkshopImport(SteamWorkshopDownloader.Result result, ExtraSettingsRepository.PreparedModImport prepared, boolean replaceExistingConflicts, boolean allowPathConflicts) {
		if (busy) {
			return;
		}
		SteamOperationProgressDialog progressDialog = new SteamOperationProgressDialog(this, getString(R.string.workshop_import_progress_title), getString(R.string.status_busy_import_mod));
		progressDialog.show();
		busy = true;
		new Thread(() -> {
			try {
				List<String> incomingIds = new ArrayList<>();
				for (ExtraSettingsRepository.ModEntry entry : prepared.incomingEntries) {
					if (!incomingIds.contains(entry.modId)) {
						incomingIds.add(entry.modId);
					}
				}
				String importedName = repository.commitPreparedModImport(prepared, replaceExistingConflicts, allowPathConflicts);
				moveImportedModsToWorkshopGroup(incomingIds);
				List<ExtraSettingsRepository.ModEntry> importedEntries = findImportedEntries(incomingIds);
				library.recordInstall(result.getItem(), importedEntries);
				runOnUiThread(() -> {
					progressDialog.dismiss();
					busy = false;
					showMessage(getString(R.string.workshop_import_done, importedName));
					showLibrary();
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					progressDialog.dismiss();
					busy = false;
					showError(exception);
				});
			}
		}, "sts2-workshop-import").start();
	}

	private void moveImportedModsToWorkshopGroup(List<String> incomingIds) throws Exception {
		if (incomingIds == null || incomingIds.isEmpty()) {
			return;
		}
		String groupName = SteamWorkshopPreferences.getDownloadGroup(this);
		repository.createModGroup(groupName);
		List<ExtraSettingsRepository.ModEntry> installed = repository.listInstalledModManifests();
		for (ExtraSettingsRepository.ModEntry entry : installed) {
			if (incomingIds.contains(entry.modId)) {
				repository.moveModToGroup(entry, groupName);
			}
		}
	}

	private List<ExtraSettingsRepository.ModEntry> findImportedEntries(List<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return new ArrayList<>();
		}
		Set<String> idSet = new LinkedHashSet<>(ids);
		List<ExtraSettingsRepository.ModEntry> matches = new ArrayList<>();
		for (ExtraSettingsRepository.ModEntry entry : repository.listInstalledModManifests()) {
			if (idSet.contains(entry.modId)) {
				matches.add(entry);
			}
		}
		return matches;
	}

	private void showLibrary() {
		libraryVisible = true;
		resultsContainer.removeAllViews();
		List<SteamWorkshopLibrary.Entry> entries = library.listEntries();
		if (entries.isEmpty()) {
			ExtraSettingsUi.addCardSpacing(resultsContainer, buildEmptyCard(R.string.workshop_library_empty, R.string.workshop_library_empty_hint));
			setIdleStatus(getString(R.string.workshop_library_empty));
			return;
		}
		ExtraSettingsUi.addCardSpacing(resultsContainer, buildSummaryCard(getString(R.string.workshop_library_summary, entries.size())));
		for (SteamWorkshopLibrary.Entry entry : entries) {
			ExtraSettingsUi.addCardSpacing(resultsContainer, buildLibraryEntryCard(entry));
		}
		setIdleStatus(getString(R.string.workshop_library_summary, entries.size()));
		maybeAutoCheckTrackedUpdates();
	}

	private View buildLibraryEntryCard(SteamWorkshopLibrary.Entry entry) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(this);
		card.setRadius(ExtraSettingsUi.dp(this, 18));
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setPadding(ExtraSettingsUi.dp(this, 16), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 16), ExtraSettingsUi.dp(this, 14));
		content.addView(ExtraSettingsUi.sectionTitle(this, entry.title));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.workshop_item_byline, entry.publishedFileId, formatDate(entry.installedRemoteUpdatedAtMs))));
		String status = updateStatusLabel(entry);
		ExtraSettingsUi.addSmallSpacing(content, ExtraSettingsUi.body(this, status));
		LinearLayout meta = ExtraSettingsUi.vertical(this);
		addMetaInfo(meta, R.drawable.ic_download_24, getString(R.string.workshop_installed_size, formatBytes(entry.installedBytes)));
		addMetaInfo(meta, R.drawable.ic_layers_24, getString(R.string.workshop_imported_mod_ids, TextUtils.join(", ", entry.importedModIds)));
		addMetaInfo(meta, R.drawable.ic_folder_24, entry.installedRootPath);
		ExtraSettingsUi.addSmallSpacing(content, meta);
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		MaterialButton update = ExtraSettingsUi.filledButton(this, R.string.workshop_redownload, R.drawable.ic_download_24);
		MaterialButton web = ExtraSettingsUi.outlineButton(this, R.string.workshop_open_item, R.drawable.ic_open_in_new_24);
		update.setOnClickListener(v -> downloadAndImport(entryToItem(entry)));
		web.setOnClickListener(v -> openUrl(itemUrl(entry.publishedFileId)));
		row.addView(update, weightedButtonParams(0));
		row.addView(web, weightedButtonParams(8));
		ExtraSettingsUi.addSmallSpacing(content, row);
		return card;
	}

	private SteamWorkshopCatalog.Item entryToItem(SteamWorkshopLibrary.Entry entry) {
		return new SteamWorkshopCatalog.Item(
			parseInt(entry.appId, SteamWorkshopPreferences.DEFAULT_APP_ID),
			entry.publishedFileId,
			entry.title,
			entry.description,
			entry.previewUrl,
			0L,
			entry.fileSizeBytes,
			0,
			0,
			entry.remoteUpdatedAtMs > 0L ? entry.remoteUpdatedAtMs / 1000L : entry.installedRemoteUpdatedAtMs / 1000L
		);
	}

	private void checkTrackedUpdates() {
		checkTrackedUpdates(true);
	}

	private void checkTrackedUpdates(boolean showLibraryWhenDone) {
		List<SteamWorkshopLibrary.Entry> entries = library.listEntries();
		if (entries.isEmpty()) {
			if (showLibraryWhenDone) {
				showLibrary();
			}
			return;
		}
		runOperation(getString(R.string.workshop_status_checking_updates), () -> {
			List<String> ids = new ArrayList<>();
			for (SteamWorkshopLibrary.Entry entry : entries) {
				ids.add(entry.publishedFileId);
			}
			return library.updateCheckResults(catalog.loadDetails(ids));
		}, summary -> {
			if (showLibraryWhenDone) {
				showLibrary();
			}
			showMessage(getString(R.string.workshop_update_summary, summary.availableCount, summary.currentCount, summary.failedCount));
		});
	}

	private void maybeAutoCheckTrackedUpdates() {
		if (autoUpdateCheckStarted || busy || !SteamWorkshopPreferences.isAutoCheckUpdatesEnabled(this)) {
			return;
		}
		long now = System.currentTimeMillis();
		for (SteamWorkshopLibrary.Entry entry : library.listEntries()) {
			if (entry.lastCheckedAtMs <= 0L || now - entry.lastCheckedAtMs >= AUTO_UPDATE_CHECK_INTERVAL_MS) {
				autoUpdateCheckStarted = true;
				checkTrackedUpdates(libraryVisible);
				return;
			}
		}
	}

	private void showDownloadGroupDialog() {
		TextInputLayout inputLayout = new TextInputLayout(this);
		inputLayout.setHint(getString(R.string.workshop_download_group_hint));
		inputLayout.setBoxBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		TextInputEditText input = new TextInputEditText(inputLayout.getContext());
		input.setSingleLine(true);
		input.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		input.setText(SteamWorkshopPreferences.getDownloadGroup(this));
		inputLayout.addView(input);
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.workshop_download_group)
			.setView(inputLayout)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				String value = input.getText() == null ? "" : input.getText().toString();
				SteamWorkshopPreferences.setDownloadGroup(this, value);
				refreshSettingsSummary();
			})
			.show();
	}

	private void showConcurrentChunksDialog() {
		TextInputLayout inputLayout = new TextInputLayout(this);
		inputLayout.setHint(getString(R.string.workshop_concurrent_chunks_hint));
		inputLayout.setBoxBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		TextInputEditText input = new TextInputEditText(inputLayout.getContext());
		input.setSingleLine(true);
		input.setInputType(InputType.TYPE_CLASS_NUMBER);
		input.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		input.setText(Integer.toString(SteamWorkshopPreferences.getConcurrentChunks(this)));
		inputLayout.addView(input);
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.workshop_concurrent_chunks)
			.setView(inputLayout)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				int value = parseInt(input.getText() == null ? "" : input.getText().toString(), 4);
				SteamWorkshopPreferences.setConcurrentChunks(this, value);
				refreshSettingsSummary();
			})
			.show();
	}

	private void showModImportConflictDialog(ExtraSettingsRepository.PreparedModImport preparedImport, List<ExtraSettingsRepository.ModImportConflict> conflicts, Runnable keepOriginal, Runnable useNew, Runnable cancelImport) {
		LinearLayout content = ExtraSettingsUi.vertical(this);
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
		LinearLayout content = ExtraSettingsUi.vertical(this);
		content.setPadding(ExtraSettingsUi.dp(this, 4), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 4), 0);
		content.addView(ExtraSettingsUi.body(this, getString(R.string.mod_import_path_conflict_message)));
		int visibleCount = Math.min(12, conflicts.size());
		for (int i = 0; i < visibleCount; i++) {
			ExtraSettingsRepository.ModImportPathConflict conflict = conflicts.get(i);
			MaterialCardView card = ExtraSettingsUi.card(this);
			card.setRadius(ExtraSettingsUi.dp(this, 16));
			LinearLayout cardContent = ExtraSettingsUi.cardContent(this, card);
			cardContent.addView(ExtraSettingsUi.text(this, conflict.relativePath, 14, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD));
			if (!TextUtils.isEmpty(conflict.existingOwnerLabel)) {
				cardContent.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_import_path_conflict_owner, conflict.existingOwnerLabel)));
			}
			ExtraSettingsUi.addSmallSpacing(content, card);
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
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.text(this, label, 15, ExtraSettingsUi.COLOR_ON_SURFACE, android.graphics.Typeface.BOLD));
		content.addView(ExtraSettingsUi.body(this, entry.displayName));
		content.addView(ExtraSettingsUi.caption(this, "ID: " + entry.modId));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_detail_version) + ": " + emptyToDash(entry.version)));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_detail_path) + ": " + entry.relativePath));
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(this, 8);
		card.setLayoutParams(params);
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

	private <T> void runOperation(String busyMessage, Operation<T> operation, Success<T> success) {
		if (busy) {
			return;
		}
		beginBusy(busyMessage);
		new Thread(() -> {
			try {
				T value = operation.run();
				runOnUiThread(() -> {
					finishBusy(getString(R.string.workshop_status_idle));
					success.run(value);
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					finishBusy(getString(R.string.workshop_status_idle));
					showError(exception);
				});
			}
		}, "sts2-workshop-operation").start();
	}

	private void beginBusy(String message) {
		busy = true;
		if (progressBar != null) {
			progressBar.setVisibility(View.VISIBLE);
			progressBar.setIndeterminate(true);
		}
		if (statusText != null) {
			statusText.setText(message);
		}
	}

	private void finishBusy(String message) {
		busy = false;
		if (progressBar != null) {
			progressBar.setVisibility(View.GONE);
		}
		setIdleStatus(message);
	}

	private void setIdleStatus(String message) {
		if (statusText != null) {
			statusText.setText(message == null ? "" : message);
		}
	}

	private void showMessage(String message) {
		View root = findViewById(android.R.id.content);
		if (root != null) {
			Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
		}
	}

	private void showError(Exception exception) {
		String message = exception == null || exception.getMessage() == null ? String.valueOf(exception) : exception.getMessage();
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.error_operation_failed)
			.setMessage(message)
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}

	private void openUrl(String url) {
		startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
	}

	private String itemUrl(SteamWorkshopCatalog.Item item) {
		return itemUrl(item.getPublishedFileId());
	}

	private String itemUrl(String publishedFileId) {
		return "https://steamcommunity.com/sharedfiles/filedetails/?id=" + publishedFileId;
	}

	private String updateStatusLabel(SteamWorkshopLibrary.Entry entry) {
		if ("available".equals(entry.updateStatus)) {
			return getString(R.string.workshop_update_available, formatDate(entry.remoteUpdatedAtMs));
		}
		if ("current".equals(entry.updateStatus)) {
			return getString(R.string.workshop_update_current, formatDate(entry.lastCheckedAtMs));
		}
		if ("failed".equals(entry.updateStatus)) {
			return getString(R.string.workshop_update_failed, emptyToDash(entry.lastError));
		}
		return getString(R.string.workshop_update_unknown);
	}

	private void appendLine(StringBuilder builder, String label, String value) {
		if (TextUtils.isEmpty(value)) {
			return;
		}
		builder.append(label).append(": ").append(value).append('\n');
	}

	private String formatBytes(long bytes) {
		return bytes <= 0L ? "—" : Formatter.formatFileSize(this, bytes);
	}

	private String formatDate(long millis) {
		return millis <= 0L ? "—" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(new java.util.Date(millis));
	}

	private String emptyToDash(String value) {
		return TextUtils.isEmpty(value) ? "—" : value;
	}

	private int parseInt(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (Exception ignored) {
			return fallback;
		}
	}

	private LinearLayout.LayoutParams weightedButtonParams(int marginStartDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		params.setMarginStart(ExtraSettingsUi.dp(this, marginStartDp));
		return params;
	}

	private interface Operation<T> { T run() throws Exception; }
	private interface Success<T> { void run(T value); }
}
