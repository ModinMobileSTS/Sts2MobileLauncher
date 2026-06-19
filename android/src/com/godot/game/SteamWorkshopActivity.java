package com.godot.game;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

public class SteamWorkshopActivity extends AppCompatActivity {
	private static final String WORKSHOP_WEB_URL = "https://steamcommunity.com/app/2868840/workshop/";
	private static final long AUTO_UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
	private static final int SCREEN_LIST = 0;
	private static final int SCREEN_DETAIL = 1;
	private static final int SCREEN_DOWNLOADS = 2;
	private static final int SCREEN_SETTINGS = 3;
	private static final int WORKSHOP_PAGE_SIZE = 30;
	private static final long DOWNLOAD_PROGRESS_DRAIN_INTERVAL_MS = 100L;
	private static final long DOWNLOAD_UI_REFRESH_INTERVAL_MS = 300L;

	private ExtraSettingsRepository repository;
	private SteamWorkshopCatalog catalog;
	private SteamWorkshopLibrary library;
	private WorkshopImageLoader imageLoader;
	private FrameLayout screenHost;
	private FrameLayout drawerScrim;
	private LinearLayout drawer;
	private ScrollView listScrollView;
	private LinearLayout listContainer;
	private LinearLayout detailContainer;
	private LinearLayout downloadsContainer;
	private LinearLayout settingsContainer;
	private LinearLayout drawerContent;
	private TextInputEditText searchInput;
	private TextView steamStatusText;
	private TextView settingsSummaryText;
	private ProgressBar progressBar;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final Map<String, DownloadTask> downloadTasks = new LinkedHashMap<>();
	private final ConcurrentHashMap<String, SteamWorkshopDownloader.Progress> pendingProgressUpdates = new ConcurrentHashMap<>();
	private final AtomicBoolean progressDrainPosted = new AtomicBoolean(false);
	private int currentPage = 1;
	private String currentQuery = "";
	private SteamWorkshopCatalog.SearchResult lastSearchResult;
	private SteamWorkshopCatalog.Detail lastDetailResult;
	private SteamWorkshopCatalog.SortOption currentSortOption = SteamWorkshopCatalog.SortOption.MOST_POPULAR;
	private SteamWorkshopCatalog.TimeWindow currentTimeWindow = SteamWorkshopCatalog.TimeWindow.ONE_WEEK;
	private final ArrayList<SteamWorkshopCatalog.Item> pendingDownloadQueue = new ArrayList<>();
	private boolean busy;
	private boolean importBusy;
	private boolean loadingMoreResults;
	private boolean hasMoreResults = true;
	private boolean autoUpdateCheckStarted;
	private boolean libraryVisible;
	private View listLoadMoreView;
	private boolean downloadUiRefreshScheduled;
	private long lastDownloadUiRefreshAtMs;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		repository = new ExtraSettingsRepository(this);
		repository.ensureAppDirectories();
		catalog = new SteamWorkshopCatalog(this);
		library = new SteamWorkshopLibrary(this);
		imageLoader = new WorkshopImageLoader(this);
		setContentView(buildContent());
		refreshSteamStatus();
		refreshSettingsSummary();
		refreshFilterLabels();
		searchWorkshop("", 1);
	}

	@Override
	protected void onDestroy() {
		if (imageLoader != null) {
			imageLoader.shutdown();
		}
		super.onDestroy();
	}

	@Override
	public void onBackPressed() {
		if (drawer != null && drawer.getTranslationX() == 0f) {
			toggleDrawer(false);
			return;
		}
		Object tag = screenHost == null ? null : screenHost.getTag();
		if (tag instanceof Integer && ((Integer) tag) != SCREEN_LIST) {
			showScreen(SCREEN_LIST);
			return;
		}
		super.onBackPressed();
	}

	private View buildContent() {
		FrameLayout root = new FrameLayout(this);
		root.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);

		screenHost = new FrameLayout(this);
		root.addView(screenHost, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		screenHost.addView(buildListScreen(), matchParams());
		screenHost.addView(buildDetailScreen(), matchParams());
		screenHost.addView(buildDownloadsScreen(), matchParams());
		screenHost.addView(buildSettingsScreen(), matchParams());
		showScreen(SCREEN_LIST);

		drawerScrim = new FrameLayout(this);
		drawerScrim.setBackgroundColor(Color.argb(150, 0, 0, 0));
		drawerScrim.setAlpha(0f);
		drawerScrim.setVisibility(View.GONE);
		drawerScrim.setOnClickListener(v -> toggleDrawer(false));
		root.addView(drawerScrim, matchParams());

		drawer = buildDrawer();
		int drawerWidth = Math.min(ExtraSettingsUi.dp(this, 320), getResources().getDisplayMetrics().widthPixels - ExtraSettingsUi.dp(this, 28));
		FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
		root.addView(drawer, drawerParams);
		drawer.post(() -> drawer.setTranslationX(-drawer.getWidth()));
		return root;
	}

	private View buildListScreen() {
		LinearLayout screen = ExtraSettingsUi.vertical(this);
		screen.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		screen.addView(buildAppBar(false, R.string.workshop_title, () -> toggleDrawer(true), true));
		listScrollView = contentScrollView();
		listScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> maybeLoadMoreSearchResults());
		LinearLayout content = contentRoot();
		listScrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		progressBar = new ProgressBar(this);
		progressBar.setIndeterminate(true);
		progressBar.setVisibility(View.GONE);
		LinearLayout progressWrap = ExtraSettingsUi.horizontal(this);
		progressWrap.setGravity(Gravity.CENTER);
		progressWrap.addView(progressBar, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, 36), ExtraSettingsUi.dp(this, 36)));
		content.addView(progressWrap, fullWidthTopMargin(8));
		listContainer = ExtraSettingsUi.vertical(this);
		content.addView(listContainer, fullWidthTopMargin(14));
		screen.addView(listScrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		return screen;
	}

	private View buildDetailScreen() {
		LinearLayout screen = ExtraSettingsUi.vertical(this);
		screen.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		screen.addView(buildAppBar(true, R.string.workshop_detail_title, () -> showScreen(SCREEN_LIST), false));
		ScrollView scroll = contentScrollView();
		detailContainer = contentRoot();
		scroll.addView(detailContainer, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		return screen;
	}

	private View buildDownloadsScreen() {
		LinearLayout screen = ExtraSettingsUi.vertical(this);
		screen.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		screen.addView(buildAppBar(true, R.string.workshop_downloads_title, () -> showScreen(SCREEN_LIST), false));
		ScrollView scroll = contentScrollView();
		downloadsContainer = contentRoot();
		scroll.addView(downloadsContainer, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		return screen;
	}

	private View buildSettingsScreen() {
		LinearLayout screen = ExtraSettingsUi.vertical(this);
		screen.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		screen.addView(buildAppBar(true, R.string.workshop_settings_title, () -> showScreen(SCREEN_LIST), false));
		ScrollView scroll = contentScrollView();
		settingsContainer = contentRoot();
		scroll.addView(settingsContainer, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		return screen;
	}

	private View buildAppBar(boolean backMode, int titleRes, Runnable navigation, boolean showSearch) {
		FrameLayout bar = new FrameLayout(this);
		bar.setBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		bar.setPadding(ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 8));
		bar.setMinimumHeight(ExtraSettingsUi.dp(this, 64));

		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(this, 56), Gravity.CENTER_VERTICAL);
		bar.addView(row, rowParams);

		MaterialButton nav = ExtraSettingsUi.iconButton(this, backMode ? R.drawable.ic_arrow_forward_24 : R.drawable.ic_list_24);
		if (backMode) {
			nav.setRotation(180f);
		}
		nav.setContentDescription(getString(backMode ? android.R.string.cancel : R.string.workshop_menu));
		nav.setOnClickListener(v -> navigation.run());
		row.addView(nav);

		if (showSearch) {
			MaterialButton search = ExtraSettingsUi.iconButton(this, R.drawable.ic_search_24);
			search.setContentDescription(getString(R.string.workshop_search));
			search.setOnClickListener(v -> showSearchDialog());
			row.addView(search);
		}

		TextView title = ExtraSettingsUi.text(this, getString(titleRes), 18, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setSingleLine(true);
		title.setEllipsize(TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		titleParams.setMarginStart(ExtraSettingsUi.dp(this, 8));
		row.addView(title, titleParams);

		if (!backMode) {
			MaterialButton steam = ExtraSettingsUi.iconButton(this, R.drawable.ic_steam_24);
			steam.setContentDescription(getString(R.string.steam_account_open));
			steam.setOnClickListener(v -> startActivity(new Intent(this, SteamAccountActivity.class)));
			row.addView(steam);
			MaterialButton downloads = ExtraSettingsUi.iconButton(this, R.drawable.ic_download_24);
			downloads.setContentDescription(getString(R.string.workshop_downloads_title));
			downloads.setOnClickListener(v -> showDownloads());
			row.addView(downloads);
			MaterialButton settings = ExtraSettingsUi.iconButton(this, R.drawable.ic_settings_24);
			settings.setContentDescription(getString(R.string.workshop_settings_title));
			settings.setOnClickListener(v -> showSettings());
			row.addView(settings);
		} else if (titleRes == R.string.workshop_downloads_title) {
			MaterialButton refresh = ExtraSettingsUi.iconButton(this, R.drawable.ic_sync_24);
			refresh.setContentDescription(getString(R.string.workshop_check_updates));
			refresh.setOnClickListener(v -> checkTrackedUpdates());
			row.addView(refresh);
		} else if (titleRes == R.string.workshop_detail_title) {
			MaterialButton web = ExtraSettingsUi.iconButton(this, R.drawable.ic_open_in_new_24);
			web.setContentDescription(getString(R.string.workshop_open_item));
			web.setOnClickListener(v -> {
				Object tag = detailContainer == null ? null : detailContainer.getTag();
				if (tag instanceof SteamWorkshopCatalog.Item) {
					openUrl(itemUrl((SteamWorkshopCatalog.Item) tag));
				}
			});
			row.addView(web);
		}
		return bar;
	}

	private LinearLayout buildDrawer() {
		LinearLayout content = ExtraSettingsUi.vertical(this);
		content.setPadding(ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 24), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 24));
		GradientDrawable background = new GradientDrawable();
		background.setColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		float radius = ExtraSettingsUi.dp(this, 28);
		background.setCornerRadii(new float[] { 0, 0, radius, radius, radius, radius, 0, 0 });
		content.setBackground(background);
		content.addView(buildDrawerHeader());
		ScrollView scrollView = new ScrollView(this);
		scrollView.setFillViewport(false);
		scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
		drawerContent = ExtraSettingsUi.vertical(this);
		scrollView.addView(drawerContent, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		content.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		rebuildDrawerContent();
		return content;
	}

	private View buildDrawerHeader() {
		LinearLayout header = ExtraSettingsUi.vertical(this);
		header.setPadding(ExtraSettingsUi.dp(this, 10), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 10), ExtraSettingsUi.dp(this, 14));
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.iconCircle(this, R.drawable.ic_steam_24, ExtraSettingsUi.COLOR_PRIMARY_CONTAINER, ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER));
		LinearLayout texts = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(this, 14));
		row.addView(texts, textParams);
		texts.addView(ExtraSettingsUi.text(this, getString(R.string.workshop_title), 17, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD));
		steamStatusText = ExtraSettingsUi.caption(this, "");
		texts.addView(steamStatusText);
		row.addView(ExtraSettingsUi.icon(this, R.drawable.ic_open_in_new_24, ExtraSettingsUi.COLOR_PRIMARY, 20));
		row.setOnClickListener(v -> {
			toggleDrawer(false);
			startActivity(new Intent(this, SteamAccountActivity.class));
		});
		ExtraSettingsUi.applyRipple(row);
		header.addView(row);
		View divider = ExtraSettingsUi.divider(this);
		LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ExtraSettingsUi.dp(this, 1));
		dividerParams.topMargin = ExtraSettingsUi.dp(this, 16);
		header.addView(divider, dividerParams);
		return header;
	}

	private void showScreen(int screen) {
		if (screenHost == null) {
			return;
		}
		for (int i = 0; i < screenHost.getChildCount(); i++) {
			screenHost.getChildAt(i).setVisibility(i == screen ? View.VISIBLE : View.GONE);
		}
		screenHost.setTag(screen);
		refreshFilterLabels();
	}

	private void toggleDrawer(boolean open) {
		if (drawer == null || drawerScrim == null) {
			return;
		}
		if (open) {
			drawerScrim.setVisibility(View.VISIBLE);
			drawerScrim.animate().alpha(1f).setDuration(180).start();
			drawer.animate().translationX(0f).setDuration(220).start();
		} else {
			drawerScrim.animate().alpha(0f).setDuration(180).withEndAction(() -> drawerScrim.setVisibility(View.GONE)).start();
			drawer.animate().translationX(-drawer.getWidth()).setDuration(220).start();
		}
	}

	private void refreshSteamStatus() {
		if (steamStatusText == null) {
			return;
		}
		SteamAuthStore.AuthSnapshot auth = SteamAuthStore.readSnapshot(this);
		if (!auth.refreshTokenConfigured) {
			steamStatusText.setText(R.string.workshop_steam_not_logged_in_short);
		} else {
			steamStatusText.setText(getString(R.string.workshop_steam_logged_in_drawer, auth.accountName, emptyToDash(auth.steamId64)));
		}
	}

	private void refreshSettingsSummary() {
		if (settingsSummaryText != null) {
			settingsSummaryText.setText(getString(
				R.string.workshop_settings_summary,
				SteamWorkshopPreferences.getDownloadGroup(this),
				SteamWorkshopPreferences.getConcurrentChunks(this),
				SteamWorkshopPreferences.isAutoCheckUpdatesEnabled(this) ? getString(R.string.workshop_enabled) : getString(R.string.workshop_disabled),
				SteamWorkshopPreferences.isDirectAccessEnabled(this) ? getString(R.string.workshop_enabled) : getString(R.string.workshop_disabled)
			));
		}
		refreshSteamStatus();
	}

	private void refreshFilterLabels() {
		rebuildDrawerContent();
	}

	private void rebuildDrawerContent() {
		if (drawerContent == null) {
			return;
		}
		drawerContent.removeAllViews();
		int screen = screenHost != null && screenHost.getTag() instanceof Integer ? (Integer) screenHost.getTag() : SCREEN_LIST;
		addDrawerSection(drawerContent, R.string.workshop_drawer_view_section);
		drawerContent.addView(drawerItem(R.drawable.ic_steam_24, R.string.workshop_all_mods, screen == SCREEN_LIST, () -> {
			toggleDrawer(false);
			searchWorkshop(currentQuery, 1);
		}));
		drawerContent.addView(drawerItem(R.drawable.ic_download_24, R.string.workshop_downloads_title, screen == SCREEN_DOWNLOADS, () -> {
			toggleDrawer(false);
			showDownloads();
		}));
		addDrawerSection(drawerContent, R.string.workshop_drawer_sort_section);
		drawerContent.addView(drawerSortOption(SteamWorkshopCatalog.SortOption.MOST_POPULAR, R.drawable.ic_sort_24));
		drawerContent.addView(drawerSortOption(SteamWorkshopCatalog.SortOption.MOST_RECENT, R.drawable.ic_sort_24));
		drawerContent.addView(drawerSortOption(SteamWorkshopCatalog.SortOption.LAST_UPDATED, R.drawable.ic_sync_24));
		drawerContent.addView(drawerSortOption(SteamWorkshopCatalog.SortOption.MOST_SUBSCRIBED, R.drawable.ic_steam_24));
		addDrawerSection(drawerContent, R.string.workshop_drawer_time_section);
		drawerContent.addView(drawerTimeOption(SteamWorkshopCatalog.TimeWindow.ONE_WEEK, R.drawable.ic_sync_24));
		drawerContent.addView(drawerTimeOption(SteamWorkshopCatalog.TimeWindow.THIRTY_DAYS, R.drawable.ic_sync_24));
		drawerContent.addView(drawerTimeOption(SteamWorkshopCatalog.TimeWindow.THREE_MONTHS, R.drawable.ic_sync_24));
		drawerContent.addView(drawerTimeOption(SteamWorkshopCatalog.TimeWindow.SIX_MONTHS, R.drawable.ic_sync_24));
		drawerContent.addView(drawerTimeOption(SteamWorkshopCatalog.TimeWindow.ONE_YEAR, R.drawable.ic_sync_24));
		drawerContent.addView(drawerTimeOption(SteamWorkshopCatalog.TimeWindow.ALL_TIME, R.drawable.ic_sync_24));
		addDrawerSection(drawerContent, R.string.workshop_settings_title);
		drawerContent.addView(drawerItem(R.drawable.ic_settings_24, R.string.workshop_settings_title, screen == SCREEN_SETTINGS, () -> {
			toggleDrawer(false);
			showSettings();
		}));
		drawerContent.addView(drawerItem(R.drawable.ic_open_in_new_24, R.string.workshop_open_web, false, () -> {
			toggleDrawer(false);
			openUrl(WORKSHOP_WEB_URL);
		}));
	}

	private void showSearchDialog() {
		TextInputLayout searchLayout = new TextInputLayout(this);
		searchLayout.setHint(getString(R.string.workshop_search_hint));
		searchLayout.setStartIconDrawable(MaterialSymbols.drawable(this, R.drawable.ic_search_24, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 24));
		searchLayout.setBoxBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		searchInput = new TextInputEditText(searchLayout.getContext());
		searchInput.setSingleLine(true);
		searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
		searchInput.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		searchInput.setHintTextColor(ExtraSettingsUi.COLOR_MUTED);
		searchInput.setText(currentQuery);
		searchInput.setSelectAllOnFocus(true);
		searchLayout.addView(searchInput);
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.workshop_search)
			.setView(searchLayout)
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.workshop_clear_search, (dialog, which) -> searchWorkshop("", 1))
			.setPositiveButton(R.string.workshop_search, (dialog, which) -> searchWorkshop(readSearchQuery(), 1));
		final androidx.appcompat.app.AlertDialog dialog = builder.create();
		searchInput.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_SEARCH) {
				searchWorkshop(readSearchQuery(), 1);
				dialog.dismiss();
				return true;
			}
			return false;
		});
		dialog.show();
	}

	private String readSearchQuery() {
		return searchInput == null || searchInput.getText() == null ? "" : searchInput.getText().toString().trim();
	}

	private void searchWorkshop(String query, int page) {
		if (busy) {
			return;
		}
		showScreen(SCREEN_LIST);
		libraryVisible = false;
		currentQuery = query == null ? "" : query.trim();
		currentPage = 1;
		lastSearchResult = null;
		loadingMoreResults = false;
		hasMoreResults = true;
		listLoadMoreView = null;
		refreshFilterLabels();
		if (listContainer != null) {
			listContainer.removeAllViews();
		}
		if (listScrollView != null) {
			listScrollView.post(() -> listScrollView.scrollTo(0, 0));
		}
		runOperation(
			getString(R.string.workshop_status_searching),
			() -> catalog.search(currentQuery, 1, WORKSHOP_PAGE_SIZE, currentSortOption, currentTimeWindow),
			this::showSearchResults
		);
	}

	private void showSearchResults(SteamWorkshopCatalog.SearchResult result) {
		libraryVisible = false;
		lastSearchResult = result;
		currentPage = Math.max(1, result.getPage());
		hasMoreResults = hasMoreSearchResults(result.getItems().size(), result);
		loadingMoreResults = false;
		refreshFilterLabels();
		listContainer.removeAllViews();
		listLoadMoreView = null;
		if (result.getItems().isEmpty()) {
			ExtraSettingsUi.addCardSpacing(listContainer, buildEmptyCard(R.string.workshop_no_results, R.string.workshop_no_results_hint, R.drawable.ic_search_24));
			maybeAutoCheckTrackedUpdates();
			return;
		}
		appendSearchRows(result.getItems());
		maybeAutoCheckTrackedUpdates();
		if (listScrollView != null) {
			listScrollView.post(this::maybeLoadMoreSearchResults);
		}
	}

	private void appendSearchRows(List<SteamWorkshopCatalog.Item> items) {
		removeLoadMoreView();
		for (SteamWorkshopCatalog.Item item : items) {
			ExtraSettingsUi.addCardSpacing(listContainer, buildWorkshopItemRow(item));
		}
		updateLoadMoreView();
	}

	private void maybeLoadMoreSearchResults() {
		if (listScrollView == null || listContainer == null || libraryVisible || busy || loadingMoreResults || !hasMoreResults || lastSearchResult == null) {
			return;
		}
		Object screen = screenHost == null ? null : screenHost.getTag();
		if (!(screen instanceof Integer) || ((Integer) screen) != SCREEN_LIST) {
			return;
		}
		View child = listScrollView.getChildAt(0);
		if (child == null) {
			return;
		}
		int remaining = child.getBottom() - (listScrollView.getScrollY() + listScrollView.getHeight());
		if (remaining > ExtraSettingsUi.dp(this, 520)) {
			return;
		}
		loadMoreSearchResults();
	}

	private void loadMoreSearchResults() {
		if (loadingMoreResults || !hasMoreResults) {
			return;
		}
		loadingMoreResults = true;
		updateLoadMoreView();
		final int pageToLoad = currentPage + 1;
		final String query = currentQuery;
		final SteamWorkshopCatalog.SortOption sortOption = currentSortOption;
		final SteamWorkshopCatalog.TimeWindow timeWindow = currentTimeWindow;
		new Thread(() -> {
			try {
				SteamWorkshopCatalog.SearchResult result = catalog.search(query, pageToLoad, WORKSHOP_PAGE_SIZE, sortOption, timeWindow);
				runOnUiThread(() -> appendLoadedSearchResults(query, sortOption, timeWindow, result));
			} catch (Exception exception) {
				runOnUiThread(() -> {
					if (query.equals(currentQuery) && sortOption == currentSortOption && timeWindow == currentTimeWindow) {
						loadingMoreResults = false;
						updateLoadMoreView();
						String message = exception == null || exception.getMessage() == null ? String.valueOf(exception) : exception.getMessage();
						showMessage(getString(R.string.workshop_load_more_failed, message));
					}
				});
			}
		}, "sts2-workshop-load-more").start();
	}

	private void appendLoadedSearchResults(String query, SteamWorkshopCatalog.SortOption sortOption, SteamWorkshopCatalog.TimeWindow timeWindow, SteamWorkshopCatalog.SearchResult result) {
		if (!query.equals(currentQuery) || sortOption != currentSortOption || timeWindow != currentTimeWindow) {
			return;
		}
		loadingMoreResults = false;
		List<SteamWorkshopCatalog.Item> existing = lastSearchResult == null ? new ArrayList<>() : lastSearchResult.getItems();
		Set<String> seenIds = new LinkedHashSet<>();
		List<SteamWorkshopCatalog.Item> merged = new ArrayList<>();
		for (SteamWorkshopCatalog.Item item : existing) {
			if (seenIds.add(item.getPublishedFileId())) {
				merged.add(item);
			}
		}
		List<SteamWorkshopCatalog.Item> added = new ArrayList<>();
		for (SteamWorkshopCatalog.Item item : result.getItems()) {
			if (seenIds.add(item.getPublishedFileId())) {
				merged.add(item);
				added.add(item);
			}
		}
		int total = Math.max(lastSearchResult == null ? 0 : lastSearchResult.getTotal(), result.getTotal());
		currentPage = Math.max(currentPage, result.getPage());
		lastSearchResult = new SteamWorkshopCatalog.SearchResult(total, currentPage, merged);
		hasMoreResults = !added.isEmpty() && hasMoreSearchResults(merged.size(), result);
		appendSearchRows(added);
		maybeAutoCheckTrackedUpdates();
	}

	private boolean hasMoreSearchResults(int loadedCount, SteamWorkshopCatalog.SearchResult latestPage) {
		if (latestPage == null || latestPage.getItems().isEmpty()) {
			return false;
		}
		int total = latestPage.getTotal();
		if (total > 0 && loadedCount >= total) {
			return false;
		}
		return latestPage.getItems().size() >= WORKSHOP_PAGE_SIZE;
	}

	private void updateLoadMoreView() {
		removeLoadMoreView();
		if (!loadingMoreResults || listContainer == null) {
			return;
		}
		listLoadMoreView = buildLoadMoreView();
		ExtraSettingsUi.addCardSpacing(listContainer, listLoadMoreView);
	}

	private void removeLoadMoreView() {
		if (listLoadMoreView != null && listContainer != null) {
			listContainer.removeView(listLoadMoreView);
		}
		listLoadMoreView = null;
	}

	private View buildLoadMoreView() {
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER);
		row.setPadding(0, ExtraSettingsUi.dp(this, 12), 0, ExtraSettingsUi.dp(this, 12));
		ProgressBar spinner = new ProgressBar(this);
		spinner.setIndeterminate(true);
		row.addView(spinner, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, 28), ExtraSettingsUi.dp(this, 28)));
		TextView text = ExtraSettingsUi.caption(this, getString(R.string.workshop_loading_more));
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		textParams.setMarginStart(ExtraSettingsUi.dp(this, 10));
		row.addView(text, textParams);
		return row;
	}

	private View buildWorkshopItemRow(SteamWorkshopCatalog.Item item) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(this);
		card.setRadius(ExtraSettingsUi.dp(this, 16));
		card.setStrokeWidth(0);
		card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 10), ExtraSettingsUi.dp(this, 12));
		card.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		ImageView thumb = imageView(80, 80, 12);
		imageLoader.load(item.getPreviewUrl(), thumb);
		row.addView(thumb);
		LinearLayout texts = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(this, 14));
		row.addView(texts, textParams);
		TextView title = ExtraSettingsUi.text(this, item.getTitle(), 16, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setMaxLines(2);
		title.setEllipsize(TextUtils.TruncateAt.END);
		texts.addView(title);
		texts.addView(ExtraSettingsUi.caption(this, byline(item)), fullWidthTopMargin(3));
		TextView meta = ExtraSettingsUi.text(this, itemMetaLine(item), 12, ExtraSettingsUi.COLOR_PRIMARY, Typeface.NORMAL);
		meta.setSingleLine(true);
		meta.setEllipsize(TextUtils.TruncateAt.END);
		texts.addView(meta, fullWidthTopMargin(6));
		TextView description = ExtraSettingsUi.body(this, TextUtils.isEmpty(item.getDescription()) ? getString(R.string.workshop_no_description) : item.getDescription());
		description.setMaxLines(2);
		description.setEllipsize(TextUtils.TruncateAt.END);
		texts.addView(description, fullWidthTopMargin(4));
		row.addView(buildDownloadControl(item, false));
		card.setOnClickListener(v -> showItemDetails(item));
		return card;
	}

	private View buildSummaryCard(String summary) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setRadius(ExtraSettingsUi.dp(this, 16));
		card.setStrokeWidth(0);
		card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setPadding(ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12));
		content.addView(ExtraSettingsUi.body(this, summary));
		return card;
	}

	private View buildEmptyCard(int titleRes, int messageRes, int iconRes) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setRadius(ExtraSettingsUi.dp(this, 18));
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setGravity(Gravity.CENTER_HORIZONTAL);
		content.addView(ExtraSettingsUi.iconCircle(this, iconRes, ExtraSettingsUi.COLOR_PRIMARY_CONTAINER, ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER));
		content.addView(ExtraSettingsUi.sectionTitle(this, titleRes), fullWidthTopMargin(12));
		TextView message = ExtraSettingsUi.body(this, messageRes);
		message.setGravity(Gravity.CENTER);
		content.addView(message, fullWidthTopMargin(8));
		return card;
	}

	private View buildLoadingView(int messageRes) {
		LinearLayout content = ExtraSettingsUi.vertical(this);
		content.setGravity(Gravity.CENTER_HORIZONTAL);
		ProgressBar spinner = new ProgressBar(this);
		spinner.setIndeterminate(true);
		content.addView(spinner, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, 42), ExtraSettingsUi.dp(this, 42)));
		return content;
	}

	private void showItemDetails(SteamWorkshopCatalog.Item item) {
		if (busy) {
			return;
		}
		showScreen(SCREEN_DETAIL);
		detailContainer.setTag(item);
		lastDetailResult = null;
		detailContainer.removeAllViews();
		detailContainer.addView(buildLoadingView(R.string.workshop_status_loading_detail), fullWidthTopMargin(24));
		runOperation(
			getString(R.string.workshop_status_loading_detail),
			() -> catalog.loadDetail(item),
			this::showDetailResult,
			this::showError,
			false
		);
	}

	private void showDetailResult(SteamWorkshopCatalog.Detail detail) {
		SteamWorkshopCatalog.Item item = detail.getItem();
		lastDetailResult = detail;
		detailContainer.setTag(item);
		detailContainer.removeAllViews();
		LinearLayout hero = ExtraSettingsUi.horizontal(this);
		hero.setGravity(Gravity.TOP);
		ImageView preview = imageView(96, 96, 16);
		imageLoader.load(item.getPreviewUrl(), preview);
		hero.addView(preview);
		LinearLayout texts = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(this, 16));
		hero.addView(texts, textParams);
		TextView title = ExtraSettingsUi.text(this, item.getTitle(), 22, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.NORMAL);
		title.setLineSpacing(ExtraSettingsUi.dp(this, 2), 1f);
		texts.addView(title);
		texts.addView(ExtraSettingsUi.text(this, byline(item), 14, ExtraSettingsUi.COLOR_PRIMARY, Typeface.BOLD), fullWidthTopMargin(4));
		texts.addView(ExtraSettingsUi.caption(this, "#" + item.getPublishedFileId()), fullWidthTopMargin(2));
		detailContainer.addView(hero);
		ExtraSettingsUi.addCardSpacing(detailContainer, buildStatsRow(item));
		ExtraSettingsUi.addCardSpacing(detailContainer, buildDetailActions(item));
		if (!detail.getScreenshotUrls().isEmpty()) {
			ExtraSettingsUi.addCardSpacing(detailContainer, buildScreenshotGallery(detail.getScreenshotUrls()));
		}
		if (!detail.getRequiredItems().isEmpty()) {
			ExtraSettingsUi.addCardSpacing(detailContainer, buildRequiredItemsCard(detail.getRequiredItems()));
		}
		ExtraSettingsUi.addCardSpacing(detailContainer, buildInfoCard(R.string.workshop_description_title, TextUtils.isEmpty(detail.getDescription()) ? getString(R.string.workshop_no_description) : detail.getDescription()));
		ExtraSettingsUi.addCardSpacing(detailContainer, buildDetailInfoGrid(item));
	}

	private View buildStatsRow(SteamWorkshopCatalog.Item item) {
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(statColumn(R.string.workshop_file_size_label, formatBytes(item.getFileSizeBytes())), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		row.addView(statColumn(R.string.workshop_subscriptions_label, formatCount(item.getSubscriptions())), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		row.addView(statColumn(R.string.workshop_views_label, formatCount(item.getViews())), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		return row;
	}

	private View statColumn(int labelRes, String value) {
		LinearLayout column = ExtraSettingsUi.vertical(this);
		column.setGravity(Gravity.CENTER_HORIZONTAL);
		TextView valueView = ExtraSettingsUi.text(this, value, 15, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		valueView.setGravity(Gravity.CENTER);
		column.addView(valueView);
		TextView label = ExtraSettingsUi.caption(this, getString(labelRes));
		label.setGravity(Gravity.CENTER);
		column.addView(label, fullWidthTopMargin(4));
		return column;
	}

	private View buildDetailActions(SteamWorkshopCatalog.Item item) {
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		View download = buildDownloadControl(item, true);
		MaterialButton web = ExtraSettingsUi.outlineButton(this, R.string.workshop_open_item, R.drawable.ic_open_in_new_24);
		web.setOnClickListener(v -> openUrl(itemUrl(item)));
		row.addView(download, weightedButtonParams(0));
		row.addView(web, weightedButtonParams(10));
		return row;
	}

	private View buildDownloadControl(SteamWorkshopCatalog.Item item, boolean wide) {
		DownloadTask task = downloadTasks.get(item.getPublishedFileId());
		if (task != null && task.isActive()) {
			return buildActiveDownloadControl(task, wide);
		}
		SteamWorkshopLibrary.Entry entry = findLibraryEntry(item.getPublishedFileId());
		boolean localInstalled = entry != null && !findInstalledModsForWorkshop(item, entry).isEmpty();
		if (entry != null && localInstalled && !hasWorkshopUpdate(item, entry)) {
			MaterialButton details = wide
				? ExtraSettingsUi.outlineButton(this, R.string.workshop_view_details, R.drawable.ic_info_24)
				: ExtraSettingsUi.iconButton(this, R.drawable.ic_info_24);
			details.setContentDescription(getString(R.string.workshop_view_details));
			details.setOnClickListener(v -> showInstalledWorkshopModDetails(item, entry));
			if (!wide) {
				details.setMinWidth(ExtraSettingsUi.dp(this, 48));
				details.setMinimumWidth(ExtraSettingsUi.dp(this, 48));
			}
			return details;
		}
		int labelRes = entry == null
			? R.string.workshop_download_and_import
			: (localInstalled ? R.string.workshop_redownload : R.string.workshop_download_again);
		MaterialButton download = wide
			? ExtraSettingsUi.filledButton(this, labelRes, R.drawable.ic_download_24)
			: ExtraSettingsUi.iconButton(this, R.drawable.ic_download_24);
		download.setContentDescription(getString(labelRes));
		download.setOnClickListener(v -> downloadAndImport(item));
		if (!wide) {
			download.setMinWidth(ExtraSettingsUi.dp(this, 48));
			download.setMinimumWidth(ExtraSettingsUi.dp(this, 48));
		}
		return download;
	}

	private SteamWorkshopLibrary.Entry findLibraryEntry(String publishedFileId) {
		if (TextUtils.isEmpty(publishedFileId)) {
			return null;
		}
		for (SteamWorkshopLibrary.Entry entry : library.listEntries()) {
			if (publishedFileId.equals(entry.publishedFileId)) {
				return entry;
			}
		}
		return null;
	}

	private void showInstalledWorkshopModDetails(SteamWorkshopCatalog.Item item, SteamWorkshopLibrary.Entry entry) {
		SteamWorkshopLibrary.Entry resolvedEntry = entry == null ? findLibraryEntry(item.getPublishedFileId()) : entry;
		List<ExtraSettingsRepository.ModEntry> installed = findInstalledModsForWorkshop(item, resolvedEntry);
		if (installed.isEmpty()) {
			showMessage(getString(R.string.workshop_installed_mod_missing));
			return;
		}
		if (installed.size() == 1) {
			showLocalModDetails(installed.get(0));
			return;
		}
		String[] labels = new String[installed.size()];
		for (int i = 0; i < installed.size(); i++) {
			ExtraSettingsRepository.ModEntry mod = installed.get(i);
			labels[i] = mod.displayName + "  ·  " + mod.modId;
		}
		new MaterialAlertDialogBuilder(this)
			.setTitle(item.getTitle())
			.setItems(labels, (dialog, which) -> showLocalModDetails(installed.get(which)))
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private List<ExtraSettingsRepository.ModEntry> findInstalledModsForWorkshop(SteamWorkshopCatalog.Item item, SteamWorkshopLibrary.Entry entry) {
		List<ExtraSettingsRepository.ModEntry> matches = new ArrayList<>();
		if (entry == null) {
			return matches;
		}
		List<ExtraSettingsRepository.ModEntry> installed = repository.listInstalledModManifests();
		Set<String> importedIds = new LinkedHashSet<>(entry.importedModIds);
		List<String> installedRoots = new ArrayList<>();
		if (!TextUtils.isEmpty(entry.installedRootPath)) {
			for (String line : entry.installedRootPath.split("\\n")) {
				String trimmed = line == null ? "" : line.trim();
				if (!TextUtils.isEmpty(trimmed)) {
					installedRoots.add(trimmed);
				}
			}
		}
		String itemTitleKey = normalizeIdentity(item == null ? "" : item.getTitle());
		for (ExtraSettingsRepository.ModEntry mod : installed) {
			if (importedIds.contains(mod.modId) || importedIds.contains(mod.pckName)) {
				matches.add(mod);
				continue;
			}
			if (isModUnderAnyRoot(mod, installedRoots)) {
				matches.add(mod);
				continue;
			}
			if (importedIds.isEmpty() && !TextUtils.isEmpty(itemTitleKey)) {
				String displayKey = normalizeIdentity(mod.displayName);
				String modKey = normalizeIdentity(mod.modId);
				String pckKey = normalizeIdentity(mod.pckName);
				if (itemTitleKey.equals(displayKey) || itemTitleKey.equals(modKey) || itemTitleKey.equals(pckKey)) {
					matches.add(mod);
				}
			}
		}
		return matches;
	}

	private boolean isModUnderAnyRoot(ExtraSettingsRepository.ModEntry mod, List<String> roots) {
		if (mod == null || mod.manifestFile == null || roots == null || roots.isEmpty()) {
			return false;
		}
		String manifestPath = mod.manifestFile.getAbsolutePath();
		for (String root : roots) {
			if (TextUtils.isEmpty(root)) {
				continue;
			}
			String normalizedRoot = root.endsWith(File.separator) ? root.substring(0, root.length() - 1) : root;
			if (manifestPath.equals(normalizedRoot) || manifestPath.startsWith(normalizedRoot + File.separator)) {
				return true;
			}
		}
		return false;
	}

	private void showLocalModDetails(ExtraSettingsRepository.ModEntry entry) {
		StringBuilder message = new StringBuilder();
		appendLine(message, getString(R.string.mod_detail_status), isLocalModEnabled(entry) ? getString(R.string.mod_enabled) : getString(R.string.mod_disabled));
		appendLine(message, "ID", entry.modId);
		appendLine(message, getString(R.string.mod_detail_category), displayModCategory(entry));
		appendLine(message, getString(R.string.mod_detail_version), entry.version);
		appendLine(message, getString(R.string.mod_detail_author), entry.authors);
		appendLine(message, getString(R.string.mod_detail_files), getString(R.string.mod_detail_files_format, entry.hasPck ? "PCK" : "—", entry.hasDll ? "DLL" : "—"));
		appendLine(message, getString(R.string.mod_detail_dependencies), entry.dependencies.isEmpty() ? "—" : TextUtils.join(", ", entry.dependencies));
		appendLine(message, getString(R.string.mod_detail_path), entry.relativePath);
		if (!TextUtils.isEmpty(entry.description)) {
			message.append('\n').append(entry.description);
		}
		new MaterialAlertDialogBuilder(this)
			.setTitle(entry.displayName)
			.setMessage(message.toString().trim())
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}

	private boolean isLocalModEnabled(ExtraSettingsRepository.ModEntry entry) {
		try {
			ExtraSettingsRepository.ModProfileState state = repository.loadModProfileState();
			for (ExtraSettingsRepository.ModProfile profile : state.profiles) {
				if (profile.id.equals(state.activeProfileId)) {
					return profile.enabledModIds.contains(entry.modId) || profile.enabledModIds.contains(entry.pckName);
				}
			}
			return true;
		} catch (Exception ignored) {
			return true;
		}
	}

	private String displayModCategory(ExtraSettingsRepository.ModEntry entry) {
		if (!TextUtils.isEmpty(entry.category)) {
			return entry.category;
		}
		if (!entry.hasPck && !entry.hasDll) {
			return getString(R.string.mod_category_missing_files);
		}
		String probe = (entry.modId + " " + entry.displayName + " " + entry.category + " " + entry.relativePath).toLowerCase(Locale.ROOT);
		if (probe.contains("lib") || probe.contains("library") || probe.contains("api") || (entry.hasDll && !entry.hasPck)) {
			return getString(R.string.mod_category_library);
		}
		return getString(R.string.mod_category_content);
	}

	private void appendLine(StringBuilder builder, String label, String value) {
		builder.append(label).append(": ").append(emptyToDash(value)).append('\n');
	}

	private boolean hasWorkshopUpdate(SteamWorkshopCatalog.Item item, SteamWorkshopLibrary.Entry entry) {
		if (item == null || entry == null) {
			return false;
		}
		long remoteUpdatedAtMs = Math.max(0L, item.getTimeUpdatedEpochSeconds() * 1000L);
		if (remoteUpdatedAtMs <= 0L && entry.remoteUpdatedAtMs > 0L) {
			remoteUpdatedAtMs = entry.remoteUpdatedAtMs;
		}
		long installedRemoteAtMs = Math.max(entry.installedRemoteUpdatedAtMs, entry.installedAtMs);
		return remoteUpdatedAtMs > installedRemoteAtMs;
	}

	private View buildActiveDownloadControl(DownloadTask task, boolean wide) {
		FrameLayout frame = new FrameLayout(this);
		int size = ExtraSettingsUi.dp(this, 48);
		ProgressRingDrawable ring = new ProgressRingDrawable(ExtraSettingsUi.COLOR_PRIMARY, ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		ring.setProgress(task.percent);
		frame.setBackground(ring);
		FrameLayout stop = new FrameLayout(this);
		stop.setClickable(true);
		stop.setFocusable(true);
		stop.setContentDescription(getString(R.string.workshop_cancel_download));
		stop.setOnClickListener(v -> cancelDownload(task.publishedFileId));
		ExtraSettingsUi.applyRipple(stop);
		View square = new View(this);
		GradientDrawable squareBackground = new GradientDrawable();
		squareBackground.setColor(ExtraSettingsUi.COLOR_PRIMARY);
		squareBackground.setCornerRadius(ExtraSettingsUi.dp(this, 2));
		square.setBackground(squareBackground);
		int squareSize = ExtraSettingsUi.dp(this, 15);
		stop.addView(square, new FrameLayout.LayoutParams(squareSize, squareSize, Gravity.CENTER));
		FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
		frame.addView(stop, buttonParams);
		frame.setLayoutParams(new LinearLayout.LayoutParams(size, size));
		if (wide) {
			LinearLayout wrapper = ExtraSettingsUi.horizontal(this);
			wrapper.setGravity(Gravity.CENTER);
			wrapper.addView(frame, new LinearLayout.LayoutParams(size, size));
			return wrapper;
		}
		return frame;
	}

	private View buildScreenshotGallery(List<String> urls) {
		HorizontalScrollView scroll = new HorizontalScrollView(this);
		scroll.setHorizontalScrollBarEnabled(false);
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		scroll.addView(row, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		for (String url : urls) {
			ImageView image = imageView(150, 88, 14);
			imageLoader.load(url, image);
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, 150), ExtraSettingsUi.dp(this, 88));
			params.setMarginEnd(ExtraSettingsUi.dp(this, 10));
			row.addView(image, params);
			image.setOnClickListener(v -> openUrl(url));
		}
		return scroll;
	}

	private View buildRequiredItemsCard(List<SteamWorkshopCatalog.RequiredItem> requiredItems) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setRadius(ExtraSettingsUi.dp(this, 16));
		card.setStrokeWidth(0);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.sectionTitle(this, R.string.workshop_required_items_title));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.workshop_required_items_hint)), fullWidthTopMargin(4));
		List<SteamWorkshopCatalog.RequiredItem> visible = requiredItems.size() > 8 ? requiredItems.subList(0, 8) : requiredItems;
		for (SteamWorkshopCatalog.RequiredItem required : visible) {
			content.addView(buildRequiredItemRow(required), fullWidthTopMargin(8));
		}
		if (requiredItems.size() > visible.size()) {
			content.addView(ExtraSettingsUi.caption(this, getString(R.string.workshop_required_items_more, requiredItems.size() - visible.size())), fullWidthTopMargin(8));
		}
		return card;
	}

	private View buildRequiredItemRow(SteamWorkshopCatalog.RequiredItem required) {
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 8));
		GradientDrawable background = new GradientDrawable();
		background.setColor(ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		background.setCornerRadius(ExtraSettingsUi.dp(this, 12));
		row.setBackground(background);
		ExtraSettingsUi.applyRipple(row);
		ImageView thumb = imageView(40, 40, 8);
		imageLoader.load(required.getPreviewUrl(), thumb);
		row.addView(thumb);
		LinearLayout texts = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(this, 12));
		row.addView(texts, textParams);
		TextView title = ExtraSettingsUi.text(this, required.getTitle(), 14, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setMaxLines(1);
		title.setEllipsize(TextUtils.TruncateAt.END);
		texts.addView(title);
		texts.addView(ExtraSettingsUi.caption(this, "#" + required.getPublishedFileId()), fullWidthTopMargin(2));
		row.addView(ExtraSettingsUi.icon(this, R.drawable.ic_open_in_new_24, ExtraSettingsUi.COLOR_PRIMARY, 20));
		row.setOnClickListener(v -> openUrl(required.getWorkshopUrl()));
		return row;
	}

	private View buildInfoCard(int titleRes, String bodyText) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setRadius(ExtraSettingsUi.dp(this, 16));
		card.setStrokeWidth(0);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.sectionTitle(this, titleRes));
		TextView body = ExtraSettingsUi.body(this, bodyText);
		body.setLineSpacing(ExtraSettingsUi.dp(this, 3), 1f);
		content.addView(body, fullWidthTopMargin(10));
		return card;
	}

	private View buildDetailInfoGrid(SteamWorkshopCatalog.Item item) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setRadius(ExtraSettingsUi.dp(this, 16));
		card.setStrokeWidth(0);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.sectionTitle(this, R.string.workshop_details_info_title));
		content.addView(detailLine(R.string.workshop_updated_label, formatDate(item.getTimeUpdatedEpochSeconds() * 1000L)), fullWidthTopMargin(10));
		content.addView(detailLine(R.string.workshop_file_size_label, formatBytes(item.getFileSizeBytes())), fullWidthTopMargin(8));
		content.addView(detailLine(R.string.workshop_published_file_id, item.getPublishedFileId()), fullWidthTopMargin(8));
		return card;
	}

	private View detailLine(int labelRes, String value) {
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(ExtraSettingsUi.text(this, getString(labelRes), 13, ExtraSettingsUi.COLOR_MUTED, Typeface.NORMAL), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		TextView text = ExtraSettingsUi.text(this, value, 13, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		text.setGravity(Gravity.END);
		text.setSingleLine(true);
		text.setEllipsize(TextUtils.TruncateAt.END);
		row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		return row;
	}

	private void showDownloads() {
		showScreen(SCREEN_DOWNLOADS);
		libraryVisible = true;
		downloadsContainer.removeAllViews();
		List<SteamWorkshopLibrary.Entry> entries = library.listEntries();
		addActiveDownloadsSection(downloadsContainer);
		downloadsContainer.addView(ExtraSettingsUi.sectionTitle(this, R.string.workshop_downloaded_section), fullWidthTopMargin(18));
		if (entries.isEmpty()) {
			ExtraSettingsUi.addCardSpacing(downloadsContainer, buildEmptyCard(R.string.workshop_library_empty, R.string.workshop_library_empty_hint, R.drawable.ic_download_24));
			setIdleStatus(getString(R.string.workshop_library_empty));
			return;
		}
		for (SteamWorkshopLibrary.Entry entry : entries) {
			ExtraSettingsUi.addCardSpacing(downloadsContainer, buildLibraryEntryCard(entry));
		}
		setIdleStatus(getString(R.string.workshop_library_summary, entries.size()));
		maybeAutoCheckTrackedUpdates();
	}

	private void addActiveDownloadsSection(LinearLayout parent) {
		parent.addView(ExtraSettingsUi.sectionTitle(this, R.string.workshop_downloading_section), fullWidthTopMargin(18));
		List<DownloadTask> active = activeDownloadTasks();
		if (active.isEmpty()) {
			parent.addView(ExtraSettingsUi.caption(this, getString(R.string.workshop_no_active_downloads)), fullWidthTopMargin(8));
			return;
		}
		for (DownloadTask task : active) {
			ExtraSettingsUi.addCardSpacing(parent, buildActiveDownloadCard(task));
		}
	}

	private View buildActiveDownloadCard(DownloadTask task) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setRadius(ExtraSettingsUi.dp(this, 16));
		card.setStrokeWidth(0);
		card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		ImageView thumb = imageView(64, 64, 12);
		imageLoader.load(task.item.getPreviewUrl(), thumb);
		row.addView(thumb);
		LinearLayout texts = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(this, 12));
		row.addView(texts, textParams);
		TextView title = ExtraSettingsUi.text(this, task.item.getTitle(), 15, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setMaxLines(2);
		title.setEllipsize(TextUtils.TruncateAt.END);
		texts.addView(title);
		texts.addView(ExtraSettingsUi.caption(this, getString(R.string.workshop_download_progress_line, task.percent, task.message)), fullWidthTopMargin(5));
		row.addView(buildActiveDownloadControl(task, false));
		content.addView(row);
		return card;
	}

	private View buildLibraryEntryCard(SteamWorkshopLibrary.Entry entry) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(this);
		card.setRadius(ExtraSettingsUi.dp(this, 16));
		card.setStrokeWidth(0);
		card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_CONTAINER);
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.setPadding(ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 12));
		LinearLayout top = ExtraSettingsUi.horizontal(this);
		ImageView thumb = imageView(72, 72, 12);
		imageLoader.load(entry.previewUrl, thumb);
		top.addView(thumb);
		LinearLayout texts = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(this, 12));
		top.addView(texts, textParams);
		TextView title = ExtraSettingsUi.text(this, entry.title, 16, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setMaxLines(2);
		title.setEllipsize(TextUtils.TruncateAt.END);
		texts.addView(title);
		texts.addView(ExtraSettingsUi.caption(this, getString(R.string.workshop_item_byline, entry.publishedFileId, formatDate(entry.installedRemoteUpdatedAtMs))), fullWidthTopMargin(4));
		SteamWorkshopCatalog.Item item = entryToItem(entry);
		List<ExtraSettingsRepository.ModEntry> installedMods = findInstalledModsForWorkshop(item, entry);
		boolean localInstalled = !installedMods.isEmpty();
		texts.addView(ExtraSettingsUi.body(this, localInstalled ? updateStatusLabel(entry) : getString(R.string.workshop_local_files_missing)), fullWidthTopMargin(6));
		boolean updateAvailable = "available".equals(entry.updateStatus);
		MaterialButton primary = libraryIconButton(
			!localInstalled || updateAvailable ? R.drawable.ic_download_24 : R.drawable.ic_info_24,
			!localInstalled ? R.string.workshop_download_again : (updateAvailable ? R.string.workshop_redownload : R.string.workshop_view_details)
		);
		MaterialButton web = libraryIconButton(R.drawable.ic_open_in_new_24, R.string.workshop_open_item);
		MaterialButton delete = libraryIconButton(R.drawable.ic_delete_24, R.string.workshop_delete_record);
		primary.setOnClickListener(v -> {
			if (!localInstalled || updateAvailable) {
				downloadAndImport(item);
			} else {
				showInstalledWorkshopModDetails(item, entry);
			}
		});
		web.setOnClickListener(v -> openUrl(itemUrl(entry.publishedFileId)));
		delete.setOnClickListener(v -> showDeleteWorkshopRecordDialog(entry, item, installedMods));
		content.addView(top);
		String importedIds = entry.importedModIds.isEmpty() ? "-" : TextUtils.join(", ", entry.importedModIds);
		TextView meta = ExtraSettingsUi.caption(this, getString(R.string.workshop_installed_size, formatBytes(entry.installedBytes)) + " · " + getString(R.string.workshop_imported_mod_ids, importedIds));
		meta.setMaxLines(2);
		meta.setEllipsize(TextUtils.TruncateAt.END);
		content.addView(meta, fullWidthTopMargin(10));
		LinearLayout actions = ExtraSettingsUi.horizontal(this);
		actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
		actions.addView(primary);
		actions.addView(web, compactIconButtonParams());
		actions.addView(delete, compactIconButtonParams());
		content.addView(actions, fullWidthTopMargin(8));
		card.setOnClickListener(v -> {
			if (localInstalled) {
				showInstalledWorkshopModDetails(item, entry);
			} else {
				showMessage(getString(R.string.workshop_local_files_missing));
			}
		});
		return card;
	}

	private SteamWorkshopCatalog.Item entryToItem(SteamWorkshopLibrary.Entry entry) {
		return new SteamWorkshopCatalog.Item(
			parseInt(entry.appId, SteamWorkshopPreferences.DEFAULT_APP_ID),
			entry.publishedFileId,
			entry.title,
			"",
			entry.description,
			entry.previewUrl,
			0L,
			entry.fileSizeBytes,
			0,
			0,
			entry.remoteUpdatedAtMs > 0L ? entry.remoteUpdatedAtMs / 1000L : entry.installedRemoteUpdatedAtMs / 1000L
		);
	}

	private MaterialButton libraryIconButton(int iconRes, int labelRes) {
		MaterialButton button = ExtraSettingsUi.iconButton(this, iconRes);
		button.setContentDescription(getString(labelRes));
		int size = ExtraSettingsUi.dp(this, 44);
		button.setMinWidth(size);
		button.setMinimumWidth(size);
		button.setMinHeight(size);
		button.setMinimumHeight(size);
		button.setInsetTop(0);
		button.setInsetBottom(0);
		return button;
	}

	private LinearLayout.LayoutParams compactIconButtonParams() {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.setMarginStart(ExtraSettingsUi.dp(this, 4));
		return params;
	}

	private void showDeleteWorkshopRecordDialog(SteamWorkshopLibrary.Entry entry, SteamWorkshopCatalog.Item item, List<ExtraSettingsRepository.ModEntry> installedMods) {
		List<ExtraSettingsRepository.ModEntry> currentInstalledMods = installedMods == null ? findInstalledModsForWorkshop(item, entry) : new ArrayList<>(installedMods);
		LinearLayout content = ExtraSettingsUi.vertical(this);
		content.setPadding(0, ExtraSettingsUi.dp(this, 8), 0, 0);
		content.addView(ExtraSettingsUi.body(this, getString(R.string.workshop_delete_record_message, entry.title)));
		CheckBox deleteLocal = new CheckBox(this);
		deleteLocal.setText(getString(R.string.workshop_delete_record_local_files, currentInstalledMods.size()));
		deleteLocal.setTextColor(ExtraSettingsUi.COLOR_ON_SURFACE);
		deleteLocal.setEnabled(!currentInstalledMods.isEmpty());
		content.addView(deleteLocal, fullWidthTopMargin(12));
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.workshop_delete_record_title)
			.setView(content)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.delete, (dialog, which) -> {
				try {
					if (deleteLocal.isChecked()) {
						repository.deleteMods(currentInstalledMods);
					}
					library.removeEntry(entry.publishedFileId);
					showDownloads();
					showMessage(getString(R.string.workshop_delete_record_done));
				} catch (Exception exception) {
					showError(exception);
				}
			})
			.show();
	}

	private void showSettings() {
		showScreen(SCREEN_SETTINGS);
		settingsContainer.removeAllViews();
		TextView title = ExtraSettingsUi.text(this, getString(R.string.workshop_settings_title), 24, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.NORMAL);
		settingsContainer.addView(title);
		settingsContainer.addView(ExtraSettingsUi.body(this, R.string.workshop_settings_subtitle), fullWidthTopMargin(4));
		ExtraSettingsUi.addCardSpacing(settingsContainer, buildSteamSettingsCard());
		ExtraSettingsUi.addCardSpacing(settingsContainer, buildDownloadSettingsCard());
	}

	private View buildSteamSettingsCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setRadius(ExtraSettingsUi.dp(this, 18));
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.iconTitleRow(this, R.drawable.ic_steam_24, R.string.workshop_steam_title, R.string.workshop_steam_subtitle, null));
		TextView status = ExtraSettingsUi.body(this, "");
		SteamAuthStore.AuthSnapshot auth = SteamAuthStore.readSnapshot(this);
		status.setText(auth.refreshTokenConfigured
			? getString(R.string.workshop_steam_logged_in, auth.accountName, emptyToDash(auth.steamId64))
			: getString(R.string.workshop_steam_not_logged_in));
		content.addView(status, fullWidthTopMargin(10));
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		MaterialButton openSteam = ExtraSettingsUi.filledButton(this, R.string.steam_account_open, R.drawable.ic_steam_24);
		MaterialButton verify = ExtraSettingsUi.outlineButton(this, R.string.steam_verify_login, R.drawable.ic_check_circle_24);
		openSteam.setOnClickListener(v -> startActivity(new Intent(this, SteamAccountActivity.class)));
		verify.setOnClickListener(v -> {
			refreshSteamStatus();
			showMessage(getString(R.string.workshop_steam_status_refreshed));
			showSettings();
		});
		row.addView(openSteam, weightedButtonParams(0));
		row.addView(verify, weightedButtonParams(10));
		content.addView(row, fullWidthTopMargin(12));
		return card;
	}

	private View buildDownloadSettingsCard() {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setRadius(ExtraSettingsUi.dp(this, 18));
		LinearLayout content = ExtraSettingsUi.cardContent(this, card);
		content.addView(ExtraSettingsUi.iconTitleRow(this, R.drawable.ic_settings_24, R.string.workshop_settings_title, R.string.workshop_settings_subtitle, null));
		settingsSummaryText = ExtraSettingsUi.body(this, "");
		content.addView(settingsSummaryText, fullWidthTopMargin(10));
		content.addView(settingsActionRow(R.drawable.ic_folder_24, R.string.workshop_download_group, getString(R.string.workshop_download_group_hint), SteamWorkshopPreferences.getDownloadGroup(this), this::showDownloadGroupDialog), fullWidthTopMargin(12));
		content.addView(settingsActionRow(R.drawable.ic_tune_24, R.string.workshop_concurrent_chunks, getString(R.string.workshop_concurrent_chunks_hint), Integer.toString(SteamWorkshopPreferences.getConcurrentChunks(this)), this::showConcurrentChunksDialog), fullWidthTopMargin(8));
		content.addView(settingsSwitchRow(R.drawable.ic_sync_24, R.string.workshop_auto_update_check, "", SteamWorkshopPreferences.isAutoCheckUpdatesEnabled(this), checked -> {
			SteamWorkshopPreferences.setAutoCheckUpdatesEnabled(this, checked);
			refreshSettingsSummary();
		}), fullWidthTopMargin(8));
		content.addView(settingsSwitchRow(R.drawable.ic_open_in_new_24, R.string.workshop_direct_access, getString(R.string.workshop_direct_access_hint), SteamWorkshopPreferences.isDirectAccessEnabled(this), checked -> {
			SteamWorkshopPreferences.setDirectAccessEnabled(this, checked);
			refreshSettingsSummary();
			if (!checked) {
				showMessage(getString(R.string.workshop_direct_access_disabled_notice));
			}
		}), fullWidthTopMargin(8));
		refreshSettingsSummary();
		return card;
	}

	private View settingsActionRow(int iconRes, int titleRes, String subtitle, String value, Runnable onClick) {
		MaterialCardView card = ExtraSettingsUi.clickableCard(this);
		card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		card.setStrokeWidth(0);
		card.setRadius(ExtraSettingsUi.dp(this, 16));
		LinearLayout row = settingsBaseRow(iconRes, titleRes, subtitle);
		TextView valueView = ExtraSettingsUi.text(this, value, 14, ExtraSettingsUi.COLOR_PRIMARY, Typeface.BOLD);
		valueView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
		valueView.setSingleLine(true);
		valueView.setEllipsize(TextUtils.TruncateAt.END);
		row.addView(valueView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));
		row.addView(ExtraSettingsUi.icon(this, R.drawable.ic_expand_more_24, ExtraSettingsUi.COLOR_PRIMARY, 20));
		card.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		card.setOnClickListener(v -> onClick.run());
		return card;
	}

	private View settingsSwitchRow(int iconRes, int titleRes, String subtitle, boolean checked, BoolConsumer onChanged) {
		MaterialCardView card = ExtraSettingsUi.card(this);
		card.setCardBackgroundColor(ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		card.setStrokeWidth(0);
		card.setRadius(ExtraSettingsUi.dp(this, 16));
		LinearLayout row = settingsBaseRow(iconRes, titleRes, subtitle);
		MaterialSwitch toggle = new MaterialSwitch(this);
		toggle.setChecked(checked);
		toggle.setOnCheckedChangeListener((buttonView, value) -> onChanged.accept(value));
		row.addView(toggle);
		card.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return card;
	}

	private LinearLayout settingsBaseRow(int iconRes, int titleRes, String subtitle) {
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 12));
		row.addView(ExtraSettingsUi.icon(this, iconRes, ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 22));
		LinearLayout texts = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(this, 12));
		row.addView(texts, textParams);
		texts.addView(ExtraSettingsUi.label(this, titleRes));
		if (!TextUtils.isEmpty(subtitle)) {
			TextView sub = ExtraSettingsUi.caption(this, subtitle);
			sub.setMaxLines(2);
			sub.setEllipsize(TextUtils.TruncateAt.END);
			texts.addView(sub, fullWidthTopMargin(3));
		}
		return row;
	}

	private View drawerSortOption(SteamWorkshopCatalog.SortOption option, int iconRes) {
		return drawerTextOption(iconRes, sortLabel(option), currentSortOption == option, () -> {
			currentSortOption = option;
			toggleDrawer(false);
			searchWorkshop(currentQuery, 1);
		});
	}

	private View drawerTimeOption(SteamWorkshopCatalog.TimeWindow window, int iconRes) {
		return drawerTextOption(iconRes, timeWindowLabel(window), currentTimeWindow == window, () -> {
			currentTimeWindow = window;
			toggleDrawer(false);
			searchWorkshop(currentQuery, 1);
		});
	}

	private void downloadAndImport(SteamWorkshopCatalog.Item item) {
		downloadAndImport(item, true);
	}

	private void downloadAndImport(SteamWorkshopCatalog.Item item, boolean checkPrerequisites) {
		DownloadTask activeTask = downloadTasks.get(item.getPublishedFileId());
		if (activeTask != null && activeTask.isActive() && activeTask.downloadStarted) {
			cancelDownload(item.getPublishedFileId());
			return;
		}
		if (importBusy) {
			showMessage(getString(R.string.workshop_import_busy));
			return;
		}
		if (checkPrerequisites) {
			ensurePendingDownloadTask(item, getString(R.string.workshop_status_checking_prerequisites));
			runOperation(
				getString(R.string.workshop_status_checking_prerequisites),
				() -> catalog.loadDetail(item),
				detail -> {
					List<SteamWorkshopCatalog.RequiredItem> missing = findMissingRequiredItems(detail.getRequiredItems());
					if (missing.isEmpty()) {
						downloadAndImport(detail.getItem(), false);
					} else {
						showMissingPrerequisitesDialog(detail.getItem(), missing);
					}
				},
				exception -> {
					finishDownloadTask(item.getPublishedFileId());
					showError(exception);
				},
				false
			);
			return;
		}
		startDownloadAndImport(item);
	}

	private void startDownloadAndImport(SteamWorkshopCatalog.Item item) {
		DownloadTask task = ensurePendingDownloadTask(item, getString(R.string.workshop_status_downloading));
		task.markDownloading(getString(R.string.workshop_status_downloading));
		refreshDownloadUi();
		showMessage(getString(R.string.workshop_download_background_started, item.getTitle()));
		startWorkshopBackgroundThread("sts2-workshop-download", () -> {
			SteamWorkshopDownloader.Result result = null;
			try {
				SteamWorkshopDownloader downloader = new SteamWorkshopDownloader(this);
				result = downloader.download(item, progress -> {
					postDownloadProgress(item.getPublishedFileId(), progress);
					return kotlin.Unit.INSTANCE;
				}, task.cancellationToken);
				ExtraSettingsRepository.PreparedModImport prepared = repository.prepareDownloadedModDirectory(result.getOutputDir(), item.getTitle());
				SteamWorkshopDownloader.Result finalResult = result;
				runOnUiThread(() -> {
					task.markImporting(getString(R.string.workshop_importing_status));
					refreshDownloadUi();
					handlePreparedWorkshopImport(finalResult, prepared);
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					downloadTasks.remove(item.getPublishedFileId());
					clearDownloadQueue();
					refreshDownloadUi();
					if (isCancelled(exception)) {
						showMessage(getString(R.string.workshop_download_cancelled));
					} else {
						showError(exception);
					}
				});
			}
		});
	}

	private void postDownloadProgress(String publishedFileId, SteamWorkshopDownloader.Progress progress) {
		pendingProgressUpdates.put(publishedFileId, progress);
		if (progressDrainPosted.compareAndSet(false, true)) {
			mainHandler.postDelayed(this::drainDownloadProgressUpdates, DOWNLOAD_PROGRESS_DRAIN_INTERVAL_MS);
		}
	}

	private void drainDownloadProgressUpdates() {
		progressDrainPosted.set(false);
		List<String> ids = new ArrayList<>(pendingProgressUpdates.keySet());
		for (String publishedFileId : ids) {
			SteamWorkshopDownloader.Progress progress = pendingProgressUpdates.remove(publishedFileId);
			if (progress != null) {
				applyDownloadProgress(publishedFileId, progress);
			}
		}
		if (!pendingProgressUpdates.isEmpty() && progressDrainPosted.compareAndSet(false, true)) {
			mainHandler.postDelayed(this::drainDownloadProgressUpdates, DOWNLOAD_PROGRESS_DRAIN_INTERVAL_MS);
		}
	}

	private void applyDownloadProgress(String publishedFileId, SteamWorkshopDownloader.Progress progress) {
		DownloadTask task = downloadTasks.get(publishedFileId);
		if (task == null) {
			return;
		}
		task.percent = progress.getPercent();
		task.message = progress.getMessage();
		task.downloadedBytes = progress.getDownloadedBytes();
		task.totalBytes = progress.getTotalBytes();
		scheduleDownloadUiRefresh();
	}

	private DownloadTask ensurePendingDownloadTask(SteamWorkshopCatalog.Item item, String message) {
		DownloadTask task = downloadTasks.get(item.getPublishedFileId());
		if (task == null || !task.isActive()) {
			task = new DownloadTask(item);
			downloadTasks.put(item.getPublishedFileId(), task);
		}
		task.message = TextUtils.isEmpty(message) ? item.getTitle() : message;
		task.percent = Math.max(1, task.percent);
		refreshDownloadUi();
		return task;
	}

	private List<SteamWorkshopCatalog.RequiredItem> findMissingRequiredItems(List<SteamWorkshopCatalog.RequiredItem> requiredItems) {
		List<SteamWorkshopCatalog.RequiredItem> missing = new ArrayList<>();
		if (requiredItems == null || requiredItems.isEmpty()) {
			return missing;
		}
		List<ExtraSettingsRepository.ModEntry> installedMods = repository.listInstalledModManifests();
		List<SteamWorkshopLibrary.Entry> libraryEntries = library.listEntries();
		for (SteamWorkshopCatalog.RequiredItem required : requiredItems) {
			if (!isRequiredItemInstalled(required, installedMods, libraryEntries)) {
				missing.add(required);
			}
		}
		return missing;
	}

	private boolean isRequiredItemInstalled(SteamWorkshopCatalog.RequiredItem required, List<ExtraSettingsRepository.ModEntry> installedMods, List<SteamWorkshopLibrary.Entry> libraryEntries) {
		String requiredId = required.getPublishedFileId();
		Set<String> installedIds = new LinkedHashSet<>();
		Set<String> installedIdentityKeys = new LinkedHashSet<>();
		for (ExtraSettingsRepository.ModEntry entry : installedMods) {
			addNonEmpty(installedIds, entry.modId);
			addNonEmpty(installedIdentityKeys, normalizeIdentity(entry.modId));
			addNonEmpty(installedIdentityKeys, normalizeIdentity(entry.pckName));
			addNonEmpty(installedIdentityKeys, normalizeIdentity(entry.displayName));
		}
		for (SteamWorkshopLibrary.Entry entry : libraryEntries) {
			if (!requiredId.equals(entry.publishedFileId)) {
				continue;
			}
			for (String importedId : entry.importedModIds) {
				if (installedIds.contains(importedId)) {
					return true;
				}
			}
		}
		String requiredTitleKey = normalizeIdentity(required.getTitle());
		String requiredIdKey = normalizeIdentity(requiredId);
		return (!TextUtils.isEmpty(requiredTitleKey) && installedIdentityKeys.contains(requiredTitleKey))
			|| (!TextUtils.isEmpty(requiredIdKey) && installedIdentityKeys.contains(requiredIdKey));
	}

	private void showMissingPrerequisitesDialog(SteamWorkshopCatalog.Item item, List<SteamWorkshopCatalog.RequiredItem> missing) {
		LinearLayout content = ExtraSettingsUi.vertical(this);
		content.setPadding(ExtraSettingsUi.dp(this, 4), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 4), 0);
		content.addView(ExtraSettingsUi.body(this, getString(R.string.workshop_required_missing_message, item.getTitle())));
		for (SteamWorkshopCatalog.RequiredItem required : missing) {
			content.addView(buildMissingPrerequisiteDialogRow(required), fullWidthTopMargin(10));
		}
		ScrollView scrollView = new ScrollView(this);
		scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.workshop_required_missing_title)
			.setView(scrollView)
			.setNegativeButton(android.R.string.cancel, (dialog, which) -> finishDownloadTask(item.getPublishedFileId()))
			.setNeutralButton(R.string.workshop_download_current_only, (dialog, which) -> downloadAndImport(item, false))
			.setPositiveButton(R.string.workshop_download_required_and_current, (dialog, which) -> queueRequiredDownloads(missing, item))
			.setOnCancelListener(dialog -> finishDownloadTask(item.getPublishedFileId()))
			.show();
	}

	private View buildMissingPrerequisiteDialogRow(SteamWorkshopCatalog.RequiredItem required) {
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 8));
		GradientDrawable background = new GradientDrawable();
		background.setColor(ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		background.setCornerRadius(ExtraSettingsUi.dp(this, 12));
		row.setBackground(background);
		ImageView thumb = imageView(42, 42, 8);
		imageLoader.load(required.getPreviewUrl(), thumb);
		row.addView(thumb);
		LinearLayout texts = ExtraSettingsUi.vertical(this);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(ExtraSettingsUi.dp(this, 12));
		row.addView(texts, textParams);
		TextView title = ExtraSettingsUi.text(this, required.getTitle(), 14, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
		title.setMaxLines(2);
		title.setEllipsize(TextUtils.TruncateAt.END);
		texts.addView(title);
		texts.addView(ExtraSettingsUi.caption(this, "#" + required.getPublishedFileId()), fullWidthTopMargin(2));
		return row;
	}

	private void queueRequiredDownloads(List<SteamWorkshopCatalog.RequiredItem> missing, SteamWorkshopCatalog.Item item) {
		pendingDownloadQueue.clear();
		Set<String> queuedIds = new LinkedHashSet<>();
		for (SteamWorkshopCatalog.RequiredItem required : missing) {
			if (queuedIds.add(required.getPublishedFileId())) {
				pendingDownloadQueue.add(required.toItem());
			}
		}
		if (queuedIds.add(item.getPublishedFileId())) {
			pendingDownloadQueue.add(item);
		}
		showMessage(getString(R.string.workshop_download_queue_started, pendingDownloadQueue.size()));
		startNextQueuedDownload();
	}

	private void startNextQueuedDownload() {
		if (importBusy) {
			return;
		}
		if (pendingDownloadQueue.isEmpty()) {
			showDownloads();
			return;
		}
		SteamWorkshopCatalog.Item next = pendingDownloadQueue.remove(0);
		showMessage(getString(R.string.workshop_download_queue_next, next.getTitle(), pendingDownloadQueue.size()));
		downloadAndImport(next, false);
	}

	private void clearDownloadQueue() {
		pendingDownloadQueue.clear();
		downloadTasks.entrySet().removeIf(entry -> {
			DownloadTask task = entry.getValue();
			return task != null && task.isActive() && !task.downloadStarted && !task.importing;
		});
	}

	private boolean isDownloading(String publishedFileId) {
		DownloadTask task = downloadTasks.get(publishedFileId);
		return task != null && task.isActive() && task.downloadStarted;
	}

	private void cancelDownload(String publishedFileId) {
		DownloadTask task = downloadTasks.get(publishedFileId);
		if (task == null) {
			return;
		}
		task.cancel();
		downloadTasks.remove(publishedFileId);
		clearDownloadQueue();
		refreshDownloadUi();
		showMessage(getString(R.string.workshop_download_cancelled));
	}

	private void finishDownloadTask(String publishedFileId) {
		downloadTasks.remove(publishedFileId);
		refreshDownloadUi();
	}

	private void markWorkshopItemCurrent(SteamWorkshopCatalog.Item installedItem) {
		if (installedItem == null || TextUtils.isEmpty(installedItem.getPublishedFileId())) {
			return;
		}
		if (lastSearchResult != null) {
			List<SteamWorkshopCatalog.Item> updatedItems = new ArrayList<>();
			boolean changed = false;
			for (SteamWorkshopCatalog.Item cachedItem : lastSearchResult.getItems()) {
				if (installedItem.getPublishedFileId().equals(cachedItem.getPublishedFileId())) {
					updatedItems.add(mergeWorkshopItem(cachedItem, installedItem));
					changed = true;
				} else {
					updatedItems.add(cachedItem);
				}
			}
			if (changed) {
				lastSearchResult = new SteamWorkshopCatalog.SearchResult(lastSearchResult.getTotal(), lastSearchResult.getPage(), updatedItems);
			}
		}
		if (lastDetailResult != null && installedItem.getPublishedFileId().equals(lastDetailResult.getItem().getPublishedFileId())) {
			SteamWorkshopCatalog.Item merged = mergeWorkshopItem(lastDetailResult.getItem(), installedItem);
			lastDetailResult = new SteamWorkshopCatalog.Detail(
				merged,
				lastDetailResult.getDescription(),
				lastDetailResult.getScreenshotUrls(),
				lastDetailResult.getRequiredItems()
			);
			if (detailContainer != null) {
				detailContainer.setTag(merged);
			}
		}
	}

	private SteamWorkshopCatalog.Item mergeWorkshopItem(SteamWorkshopCatalog.Item base, SteamWorkshopCatalog.Item update) {
		if (base == null) {
			return update;
		}
		if (update == null) {
			return base;
		}
		return new SteamWorkshopCatalog.Item(
			update.getAppId() > 0 ? update.getAppId() : base.getAppId(),
			base.getPublishedFileId(),
			emptyToFallback(update.getTitle(), base.getTitle()),
			emptyToFallback(update.getAuthorName(), base.getAuthorName()),
			emptyToFallback(update.getDescription(), base.getDescription()),
			emptyToFallback(update.getPreviewUrl(), base.getPreviewUrl()),
			update.getCreatorSteamId() > 0L ? update.getCreatorSteamId() : base.getCreatorSteamId(),
			update.getFileSizeBytes() > 0L ? update.getFileSizeBytes() : base.getFileSizeBytes(),
			update.getSubscriptions() > 0 ? update.getSubscriptions() : base.getSubscriptions(),
			update.getViews() > 0 ? update.getViews() : base.getViews(),
			update.getTimeUpdatedEpochSeconds() > 0L ? update.getTimeUpdatedEpochSeconds() : base.getTimeUpdatedEpochSeconds()
		);
	}

	private List<DownloadTask> activeDownloadTasks() {
		List<DownloadTask> tasks = new ArrayList<>();
		for (DownloadTask task : downloadTasks.values()) {
			if (task.isActive()) {
				tasks.add(task);
			}
		}
		return tasks;
	}

	private void refreshDownloadUi() {
		lastDownloadUiRefreshAtMs = System.currentTimeMillis();
		Object screen = screenHost == null ? null : screenHost.getTag();
		if (screen instanceof Integer) {
			int value = (Integer) screen;
			if (value == SCREEN_LIST && listContainer != null) {
				if (lastSearchResult != null) {
					showSearchResults(lastSearchResult);
				}
			} else if (value == SCREEN_DETAIL && detailContainer != null && detailContainer.getTag() instanceof SteamWorkshopCatalog.Item) {
				if (lastDetailResult != null) {
					showDetailResult(lastDetailResult);
				}
			} else if (value == SCREEN_DOWNLOADS && downloadsContainer != null) {
				showDownloads();
			}
		}
	}

	private void scheduleDownloadUiRefresh() {
		if (downloadUiRefreshScheduled) {
			return;
		}
		long elapsed = System.currentTimeMillis() - lastDownloadUiRefreshAtMs;
		long delay = Math.max(0L, DOWNLOAD_UI_REFRESH_INTERVAL_MS - elapsed);
		downloadUiRefreshScheduled = true;
		mainHandler.postDelayed(() -> {
			downloadUiRefreshScheduled = false;
			refreshDownloadUi();
		}, delay);
	}

	private void handlePreparedWorkshopImport(SteamWorkshopDownloader.Result result, ExtraSettingsRepository.PreparedModImport prepared) {
		List<ExtraSettingsRepository.ModImportConflict> idConflicts = repository.findCurrentImportConflicts(prepared);
		if (!idConflicts.isEmpty()) {
			showModImportConflictDialog(prepared, idConflicts,
				() -> {
					repository.discardPreparedModImport(prepared);
					finishDownloadTask(result.getItem().getPublishedFileId());
					clearDownloadQueue();
					showMessage(getString(R.string.status_import_mod_cancelled));
				},
				() -> handlePreparedWorkshopPathConflicts(result, prepared, true, idConflicts),
				() -> {
					repository.discardPreparedModImport(prepared);
					finishDownloadTask(result.getItem().getPublishedFileId());
					clearDownloadQueue();
					showMessage(getString(R.string.status_import_mod_cancelled));
				});
			return;
		}
		handlePreparedWorkshopPathConflicts(result, prepared, false, idConflicts);
	}

	private void handlePreparedWorkshopPathConflicts(SteamWorkshopDownloader.Result result, ExtraSettingsRepository.PreparedModImport prepared, boolean replaceExistingConflicts, List<ExtraSettingsRepository.ModImportConflict> confirmedIdConflicts) {
		List<ExtraSettingsRepository.ModImportPathConflict> pathConflicts = repository.findCurrentImportPathConflicts(prepared, replaceExistingConflicts ? confirmedIdConflicts : new ArrayList<>(), SteamWorkshopPreferences.getDownloadGroup(this));
		if (!pathConflicts.isEmpty()) {
			showModImportPathConflictDialog(prepared, pathConflicts,
				() -> {
					repository.discardPreparedModImport(prepared);
					finishDownloadTask(result.getItem().getPublishedFileId());
					clearDownloadQueue();
					showMessage(getString(R.string.status_import_mod_cancelled));
				},
				() -> commitPreparedWorkshopImport(result, prepared, replaceExistingConflicts, true),
				() -> {
					repository.discardPreparedModImport(prepared);
					finishDownloadTask(result.getItem().getPublishedFileId());
					clearDownloadQueue();
					showMessage(getString(R.string.status_import_mod_cancelled));
				});
			return;
		}
		commitPreparedWorkshopImport(result, prepared, replaceExistingConflicts, false);
	}

	private void commitPreparedWorkshopImport(SteamWorkshopDownloader.Result result, ExtraSettingsRepository.PreparedModImport prepared, boolean replaceExistingConflicts, boolean allowPathConflicts) {
		if (importBusy) {
			return;
		}
		SteamOperationProgressDialog progressDialog = new SteamOperationProgressDialog(this, getString(R.string.workshop_import_progress_title), getString(R.string.status_busy_import_mod));
		progressDialog.show();
		importBusy = true;
		DownloadTask task = downloadTasks.get(result.getItem().getPublishedFileId());
		if (task != null) {
			task.markImporting(getString(R.string.workshop_importing_status));
			refreshDownloadUi();
		}
		startWorkshopBackgroundThread("sts2-workshop-import", () -> {
			try {
				List<String> incomingIds = new ArrayList<>();
				for (ExtraSettingsRepository.ModEntry entry : prepared.incomingEntries) {
					if (!incomingIds.contains(entry.modId)) {
						incomingIds.add(entry.modId);
					}
				}
				String importedName = repository.commitPreparedModImport(prepared, replaceExistingConflicts, allowPathConflicts);
				moveImportedModsToWorkshopGroup(prepared, incomingIds);
				List<ExtraSettingsRepository.ModEntry> importedEntries = findImportedEntries(incomingIds);
				library.recordInstall(result.getItem(), importedEntries);
				runOnUiThread(() -> {
					progressDialog.dismiss();
					importBusy = false;
					markWorkshopItemCurrent(result.getItem());
					finishDownloadTask(result.getItem().getPublishedFileId());
					showMessage(getString(R.string.workshop_import_done, importedName));
					if (!pendingDownloadQueue.isEmpty()) {
						startNextQueuedDownload();
					}
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					progressDialog.dismiss();
					importBusy = false;
					finishDownloadTask(result.getItem().getPublishedFileId());
					clearDownloadQueue();
					showError(exception);
				});
			}
		});
	}

	private void moveImportedModsToWorkshopGroup(ExtraSettingsRepository.PreparedModImport prepared, List<String> incomingIds) throws Exception {
		if (incomingIds == null || incomingIds.isEmpty()) {
			return;
		}
		String groupName = SteamWorkshopPreferences.getDownloadGroup(this);
		repository.createModGroup(groupName);
		repository.movePreparedImportFilesToGroup(prepared, groupName);
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

	private void checkTrackedUpdates() {
		checkTrackedUpdates(true);
	}

	private void checkTrackedUpdates(boolean showLibraryWhenDone) {
		List<SteamWorkshopLibrary.Entry> entries = library.listEntries();
		if (entries.isEmpty()) {
			if (showLibraryWhenDone) {
				showDownloads();
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
				showDownloads();
				showMessage(getString(R.string.workshop_update_summary, summary.availableCount, summary.currentCount, summary.failedCount));
			}
		}, this::showError, false);
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
				showSettings();
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
				showSettings();
			})
			.show();
	}

	private void showModImportConflictDialog(ExtraSettingsRepository.PreparedModImport preparedImport, List<ExtraSettingsRepository.ModImportConflict> conflicts, Runnable keepOriginal, Runnable useNew, Runnable cancelImport) {
		LinearLayout content = ExtraSettingsUi.vertical(this);
		content.setPadding(ExtraSettingsUi.dp(this, 4), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 4), 0);
		content.addView(ExtraSettingsUi.body(this, getString(R.string.mod_import_conflict_message)));
		for (ExtraSettingsRepository.ModImportConflict conflict : conflicts) {
			TextView conflictTitle = ExtraSettingsUi.text(this, getString(R.string.mod_import_conflict_id_format, conflict.modId), 15, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD);
			content.addView(conflictTitle, fullWidthTopMargin(12));
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
			cardContent.addView(ExtraSettingsUi.text(this, conflict.relativePath, 14, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD));
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
		content.addView(ExtraSettingsUi.text(this, label, 15, ExtraSettingsUi.COLOR_ON_SURFACE, Typeface.BOLD));
		content.addView(ExtraSettingsUi.body(this, entry.displayName));
		content.addView(ExtraSettingsUi.caption(this, "ID: " + entry.modId));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_detail_version) + ": " + emptyToDash(entry.version)));
		content.addView(ExtraSettingsUi.caption(this, getString(R.string.mod_detail_path) + ": " + entry.relativePath));
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(this, 8);
		card.setLayoutParams(params);
		return card;
	}

	private <T> void runOperation(String busyMessage, Operation<T> operation, Success<T> success) {
		runOperation(busyMessage, operation, success, this::showError);
	}

	private <T> void runOperation(String busyMessage, Operation<T> operation, Success<T> success, Failure failure) {
		runOperation(busyMessage, operation, success, failure, true);
	}

	private <T> void runOperation(String busyMessage, Operation<T> operation, Success<T> success, Failure failure, boolean showTopSpinner) {
		if (busy) {
			return;
		}
		beginBusy(busyMessage, showTopSpinner);
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
					failure.run(exception);
				});
			}
		}, "sts2-workshop-operation").start();
	}

	private void beginBusy(String message, boolean showTopSpinner) {
		busy = true;
		if (progressBar != null) {
			progressBar.setVisibility(showTopSpinner ? View.VISIBLE : View.GONE);
			progressBar.setIndeterminate(true);
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
	}

	private void startWorkshopBackgroundThread(String name, Runnable task) {
		Thread thread = new Thread(() -> {
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
			task.run();
		}, name);
		thread.start();
	}

	private void showMessage(String message) {
		View root = findViewById(android.R.id.content);
		if (root != null) {
			Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
		}
	}

	private void showError(Exception exception) {
		String message = userFacingErrorMessage(exception);
		new MaterialAlertDialogBuilder(this)
			.setTitle(R.string.error_operation_failed)
			.setMessage(message)
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}

	private String userFacingErrorMessage(Exception exception) {
		String message = exception == null || exception.getMessage() == null ? String.valueOf(exception) : exception.getMessage();
		if (shouldAppendWorkshopLoginHint(message)) {
			String hint = getString(R.string.workshop_download_login_hint);
			if (isSteamCdnUnauthorizedFailure(message)) {
				return hint;
			}
			if (!message.contains(hint)) {
				return message + "\n\n" + hint;
			}
		}
		if (shouldAppendWorkshopNetworkHint(message)) {
			String hint = getString(R.string.workshop_download_network_hint);
			if (!message.contains(hint)) {
				return message + "\n\n" + hint;
			}
		}
		return message;
	}

	private boolean shouldAppendWorkshopLoginHint(String message) {
		if (TextUtils.isEmpty(message)) {
			return false;
		}
		if (isSteamCdnUnauthorizedFailure(message)) {
			return true;
		}
		String lower = message.toLowerCase(Locale.ROOT);
		return lower.contains("unauthorized")
			|| lower.contains("forbidden")
			|| lower.contains("access denied")
			|| lower.contains("permission")
			|| lower.contains("not logged")
			|| lower.contains("login required")
			|| lower.contains("requires login")
			|| lower.contains("http 401")
			|| lower.contains("http 403")
			|| lower.contains(" 401")
			|| lower.contains(" 403");
	}

	private boolean isSteamCdnUnauthorizedFailure(String message) {
		return !TextUtils.isEmpty(message)
			&& message.toLowerCase(Locale.ROOT).contains("steam cdn request failed: 401");
	}

	private boolean shouldAppendWorkshopNetworkHint(String message) {
		if (TextUtils.isEmpty(message)) {
			return false;
		}
		if (shouldAppendWorkshopLoginHint(message)) {
			return false;
		}
		String lower = message.toLowerCase(Locale.ROOT);
		return lower.contains("ugc manifest")
			|| lower.contains("unable to download")
			|| lower.contains("timeout")
			|| lower.contains("timed out")
			|| lower.contains("failed to connect")
			|| lower.contains("connection")
			|| lower.contains("unreachable");
	}

	private boolean isCancelled(Exception exception) {
		if (exception == null) {
			return false;
		}
		if (exception instanceof InterruptedException) {
			return true;
		}
		String message = exception.getMessage();
		return message != null && message.toLowerCase(Locale.ROOT).contains("cancel");
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

	private String byline(SteamWorkshopCatalog.Item item) {
		if (!TextUtils.isEmpty(item.getAuthorName())) {
			return getString(R.string.workshop_item_author, item.getAuthorName());
		}
		return getString(R.string.workshop_item_byline, item.getPublishedFileId(), formatDate(item.getTimeUpdatedEpochSeconds() * 1000L));
	}

	private String itemMetaLine(SteamWorkshopCatalog.Item item) {
		return getString(R.string.workshop_file_size, formatBytes(item.getFileSizeBytes())) + " · "
			+ getString(R.string.workshop_subscriptions, item.getSubscriptions()) + " · "
			+ getString(R.string.workshop_views, item.getViews());
	}

	private String sortLabel(SteamWorkshopCatalog.SortOption option) {
		switch (option) {
			case MOST_RECENT:
				return getString(R.string.workshop_sort_recent);
			case LAST_UPDATED:
				return getString(R.string.workshop_sort_updated);
			case MOST_SUBSCRIBED:
				return getString(R.string.workshop_sort_subscribed);
			case MOST_POPULAR:
			default:
				return getString(R.string.workshop_sort_popular);
		}
	}

	private String timeWindowLabel(SteamWorkshopCatalog.TimeWindow window) {
		switch (window) {
			case THIRTY_DAYS:
				return getString(R.string.workshop_time_30_days);
			case THREE_MONTHS:
				return getString(R.string.workshop_time_3_months);
			case SIX_MONTHS:
				return getString(R.string.workshop_time_6_months);
			case ONE_YEAR:
				return getString(R.string.workshop_time_1_year);
			case ALL_TIME:
				return getString(R.string.workshop_time_all);
			case ONE_WEEK:
			default:
				return getString(R.string.workshop_time_week);
		}
	}

	private String formatBytes(long bytes) {
		return bytes <= 0L ? "—" : Formatter.formatFileSize(this, bytes);
	}

	private String formatDate(long millis) {
		return millis <= 0L ? "—" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(new java.util.Date(millis));
	}

	private String formatCount(int value) {
		return String.format(Locale.getDefault(), "%,d", Math.max(0, value));
	}

	private String emptyToDash(String value) {
		return TextUtils.isEmpty(value) ? "—" : value;
	}

	private String emptyToFallback(String value, String fallback) {
		return TextUtils.isEmpty(value) ? (fallback == null ? "" : fallback) : value;
	}

	private void addNonEmpty(Set<String> values, String value) {
		if (!TextUtils.isEmpty(value)) {
			values.add(value);
		}
	}

	private String normalizeIdentity(String value) {
		if (TextUtils.isEmpty(value)) {
			return "";
		}
		return value.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "")
			.trim();
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

	private LinearLayout.LayoutParams fullWidthTopMargin(int marginTopDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(this, marginTopDp);
		return params;
	}

	private FrameLayout.LayoutParams matchParams() {
		return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
	}

	private ScrollView contentScrollView() {
		ScrollView scroll = new ScrollView(this);
		scroll.setFillViewport(false);
		scroll.setBackgroundColor(ExtraSettingsUi.COLOR_BACKGROUND);
		return scroll;
	}

	private LinearLayout contentRoot() {
		LinearLayout content = ExtraSettingsUi.vertical(this);
		int horizontalPadding = ExtraSettingsUi.pageHorizontalPadding(this);
		content.setPadding(horizontalPadding, ExtraSettingsUi.dp(this, 16), horizontalPadding, ExtraSettingsUi.dp(this, 32));
		return content;
	}

	private ImageView imageView(int widthDp, int heightDp, int radiusDp) {
		ImageView image = new ImageView(this);
		image.setScaleType(ImageView.ScaleType.CENTER_CROP);
		GradientDrawable background = new GradientDrawable();
		background.setColor(ExtraSettingsUi.COLOR_SURFACE_VARIANT);
		background.setCornerRadius(ExtraSettingsUi.dp(this, radiusDp));
		image.setBackground(background);
		image.setClipToOutline(true);
		image.setImageDrawable(MaterialSymbols.drawable(this, R.drawable.ic_steam_24, ExtraSettingsUi.COLOR_MUTED, 28));
		image.setPadding(0, 0, 0, 0);
		image.setLayoutParams(new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, widthDp), ExtraSettingsUi.dp(this, heightDp)));
		return image;
	}

	private void addDrawerSection(LinearLayout parent, int titleRes) {
		TextView title = ExtraSettingsUi.text(this, getString(titleRes).toUpperCase(Locale.getDefault()), 12, ExtraSettingsUi.COLOR_MUTED, Typeface.BOLD);
		LinearLayout.LayoutParams params = fullWidthTopMargin(parent.getChildCount() == 0 ? 0 : 18);
		params.leftMargin = ExtraSettingsUi.dp(this, 12);
		params.rightMargin = ExtraSettingsUi.dp(this, 12);
		parent.addView(title, params);
	}

	private View drawerItem(int iconRes, int titleRes, boolean active, Runnable onClick) {
		return drawerTextOption(iconRes, getString(titleRes), active, onClick);
	}

	private View drawerTextOption(int iconRes, String label, boolean active, Runnable onClick) {
		LinearLayout row = drawerBaseRow(active);
		row.addView(ExtraSettingsUi.icon(this, iconRes, active ? ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER : ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, 22));
		TextView title = ExtraSettingsUi.text(this, label, 14, active ? ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER : ExtraSettingsUi.COLOR_ON_SURFACE_VARIANT, active ? Typeface.BOLD : Typeface.NORMAL);
		LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		titleParams.setMarginStart(ExtraSettingsUi.dp(this, 12));
		row.addView(title, titleParams);
		if (active) {
			row.addView(ExtraSettingsUi.icon(this, R.drawable.ic_check_circle_24, ExtraSettingsUi.COLOR_ON_PRIMARY_CONTAINER, 18));
		}
		row.setOnClickListener(v -> onClick.run());
		return row;
	}

	private LinearLayout drawerBaseRow(boolean active) {
		LinearLayout row = ExtraSettingsUi.horizontal(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setMinimumHeight(ExtraSettingsUi.dp(this, 44));
		row.setPadding(ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 6), ExtraSettingsUi.dp(this, 14), ExtraSettingsUi.dp(this, 6));
		GradientDrawable background = new GradientDrawable();
		background.setColor(active ? ExtraSettingsUi.COLOR_PRIMARY_CONTAINER : Color.TRANSPARENT);
		background.setCornerRadius(ExtraSettingsUi.dp(this, 22));
		row.setBackground(background);
		ExtraSettingsUi.applyRipple(row);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = ExtraSettingsUi.dp(this, 2);
		row.setLayoutParams(params);
		return row;
	}

	private interface Operation<T> { T run() throws Exception; }
	private interface Success<T> { void run(T value); }
	private interface Failure { void run(Exception exception); }
	private interface BoolConsumer { void accept(boolean value); }

	private static final class DownloadTask {
		final SteamWorkshopCatalog.Item item;
		final String publishedFileId;
		final SteamWorkshopDownloader.CancellationToken cancellationToken = new SteamWorkshopDownloader.CancellationToken();
		int percent = 1;
		String message = "";
		long downloadedBytes;
		long totalBytes;
		boolean downloadStarted;
		boolean importing;
		boolean cancelled;

		DownloadTask(SteamWorkshopCatalog.Item item) {
			this.item = item;
			this.publishedFileId = item.getPublishedFileId();
			this.message = item.getTitle();
		}

		boolean isActive() {
			return !cancelled;
		}

		void cancel() {
			cancelled = true;
			cancellationToken.cancel();
		}

		void markDownloading(String message) {
			downloadStarted = true;
			importing = false;
			this.message = message == null ? "" : message;
		}

		void markImporting(String message) {
			downloadStarted = true;
			importing = true;
			percent = 100;
			this.message = message == null ? "" : message;
		}
	}

	private static final class ProgressRingDrawable extends Drawable {
		private static final int START_ANGLE = -90;
		private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF arcBounds = new RectF();
		private int progress;

		ProgressRingDrawable(int progressColor, int trackColor) {
			trackPaint.setStyle(Paint.Style.STROKE);
			trackPaint.setStrokeCap(Paint.Cap.ROUND);
			trackPaint.setColor(trackColor);
			progressPaint.setStyle(Paint.Style.STROKE);
			progressPaint.setStrokeCap(Paint.Cap.ROUND);
			progressPaint.setColor(progressColor);
		}

		void setProgress(int progress) {
			this.progress = Math.max(0, Math.min(100, progress));
			invalidateSelf();
		}

		@Override
		public void draw(Canvas canvas) {
			int width = getBounds().width();
			int height = getBounds().height();
			float stroke = Math.max(4f, Math.min(width, height) * 0.085f);
			trackPaint.setStrokeWidth(stroke);
			progressPaint.setStrokeWidth(stroke);
			float inset = stroke / 2f + 2f;
			arcBounds.set(getBounds().left + inset, getBounds().top + inset, getBounds().right - inset, getBounds().bottom - inset);
			canvas.drawArc(arcBounds, 0, 360, false, trackPaint);
			canvas.drawArc(arcBounds, START_ANGLE, 360f * progress / 100f, false, progressPaint);
		}

		@Override
		public void setAlpha(int alpha) {
			trackPaint.setAlpha(alpha);
			progressPaint.setAlpha(alpha);
			invalidateSelf();
		}

		@Override
		public void setColorFilter(ColorFilter colorFilter) {
			trackPaint.setColorFilter(colorFilter);
			progressPaint.setColorFilter(colorFilter);
			invalidateSelf();
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}
	}

	private static final class WorkshopImageLoader {
		private final SteamWorkshopActivity activity;
		private final OkHttpClient defaultClient;
		private final OkHttpClient originalClient;
		private final OkHttpClient directClient;
		private final Map<String, Bitmap> memoryCache = new ConcurrentHashMap<>();

		WorkshopImageLoader(SteamWorkshopActivity activity) {
			this.activity = activity;
			this.defaultClient = buildClient(activity, WorkshopHttpRouteMode.DEFAULT);
			this.originalClient = buildClient(activity, WorkshopHttpRouteMode.ORIGINAL_ONLY);
			this.directClient = buildClient(activity, WorkshopHttpRouteMode.DIRECT_ONLY);
		}

		private OkHttpClient buildClient(SteamWorkshopActivity activity, WorkshopHttpRouteMode mode) {
			return SteamWorkshopDirectAccess.INSTANCE.buildClient(activity, mode, builder -> {
				builder.connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS);
				builder.readTimeout(16, java.util.concurrent.TimeUnit.SECONDS);
				builder.writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS);
				builder.callTimeout(20, java.util.concurrent.TimeUnit.SECONDS);
				builder.protocols(java.util.Collections.singletonList(Protocol.HTTP_1_1));
				builder.connectionPool(new ConnectionPool(0, 1, java.util.concurrent.TimeUnit.MILLISECONDS));
				builder.retryOnConnectionFailure(true);
				return kotlin.Unit.INSTANCE;
			});
		}

		void load(String url, ImageView target) {
			String normalized = normalizeImageUrl(url);
			target.setTag(normalized);
			if (normalized.isEmpty()) {
				target.setImageDrawable(MaterialSymbols.drawable(activity, R.drawable.ic_steam_24, ExtraSettingsUi.COLOR_MUTED, 28));
				return;
			}
			Bitmap cached = memoryCache.get(normalized);
			if (cached != null) {
				target.setImageBitmap(cached);
				return;
			}
			target.setImageDrawable(MaterialSymbols.drawable(activity, R.drawable.ic_steam_24, ExtraSettingsUi.COLOR_MUTED, 28));
			new Thread(() -> {
				try {
					Bitmap bitmap = fetchBitmap(normalized);
					memoryCache.put(normalized, bitmap);
					activity.runOnUiThread(() -> {
						if (normalized.equals(target.getTag())) {
							target.setImageBitmap(bitmap);
						}
					});
				} catch (Exception ignored) {
				}
			}, "sts2-workshop-image").start();
		}

		private Bitmap fetchBitmap(String url) throws IOException {
			IOException lastError = null;
			for (OkHttpClient client : new OkHttpClient[] { defaultClient, originalClient, directClient }) {
				try {
					return fetchBitmap(client, url);
				} catch (IOException exception) {
					lastError = exception;
				}
			}
			throw lastError == null ? new IOException("Image request failed.") : lastError;
		}

		private Bitmap fetchBitmap(OkHttpClient client, String url) throws IOException {
			Request request = new Request.Builder()
				.url(url)
				.header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) STS2Workshop/1.0 Mobile Safari/537.36")
				.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
				.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
				.header("Referer", "https://steamcommunity.com/")
				.build();
			try (Response response = client.newCall(request).execute()) {
				if (!response.isSuccessful() || response.body() == null) {
					throw new IOException("Image request failed: HTTP " + response.code());
				}
				byte[] bytes = response.body().bytes();
				Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
				if (bitmap == null) {
					throw new IOException("Unable to decode image.");
				}
				return bitmap;
			}
		}

		private String normalizeImageUrl(String url) {
			String normalized = url == null ? "" : url.trim();
			normalized = normalized
				.replace("\\/", "/")
				.replace("\\u0026", "&")
				.replace("\\x26", "&")
				.replace("&amp;", "&")
				.trim();
			if (normalized.startsWith("//")) {
				normalized = "https:" + normalized;
			}
			normalized = normalized
				.replace("https://steamcommunity.rmbgame.net/", "https://steamcommunity.com/")
				.replace("http://steamcommunity.rmbgame.net/", "https://steamcommunity.com/")
				.replace("https://steamstore.rmbgame.net/", "https://api.steampowered.com/")
				.replace("http://steamstore.rmbgame.net/", "https://api.steampowered.com/");
			return normalized;
		}

		void shutdown() {
			defaultClient.dispatcher().cancelAll();
			originalClient.dispatcher().cancelAll();
			directClient.dispatcher().cancelAll();
			memoryCache.clear();
		}
	}
}
