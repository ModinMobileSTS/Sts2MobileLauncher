package com.godot.game;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.animation.PathInterpolator;
import androidx.appcompat.widget.PopupMenu;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class LogViewerActivity extends AppCompatActivity {
	private static final int REQUEST_EXPORT_LOGS = 2001;
	private static final long MAX_PREVIEW_BYTES = 512L * 1024L;
	private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
	private static final String ROOT_INTERNAL_ARCHIVE = "internal-files";
	private static final String ROOT_EXTERNAL_ARCHIVE = "external-files";
	private static final String SOURCE_ALL = "all";
	private static final String SOURCE_RUNTIME = "runtime";
	private static final String SOURCE_CRASH = "crash";
	private static final String SOURCE_HARMONY = "harmony";
	private static final String SOURCE_CONSOLE = "console";
	private static final int COLOR_BACKGROUND = 0xFF0F1117;
	private static final int COLOR_SURFACE = 0xFF171A22;
	private static final int COLOR_ON_SURFACE = 0xFFF0F0F8;
	private static final int COLOR_ON_SURFACE_VARIANT = 0xFFC5C7D3;
	private static final int COLOR_PRIMARY = 0xFFB7C4FF;
	private static final int COLOR_ON_PRIMARY = 0xFF0E1B4D;
	private static final int COLOR_PRIMARY_CONTAINER = 0xFF2B3762;
	private static final int COLOR_ON_PRIMARY_CONTAINER = 0xFFDCE2FF;
	private static final int COLOR_ERROR = 0xFFFFB4AB;
	private static final int COLOR_ERROR_CONTAINER = 0xFF5A1B1D;

	private final List<LogEntry> logEntries = new ArrayList<>();
	private final List<LogEntry> visibleLogEntries = new ArrayList<>();
	private final List<ListItem> listItems = new ArrayList<>();
	private final LinkedHashSet<Integer> selectedPositions = new LinkedHashSet<>();

	private TextView emptyListText;
	private RecyclerView logsRecyclerView;
	private LogAdapter adapter;
	private TextInputEditText searchInput;
	private ChipGroup sourceChipGroup;
    private Chip crashChip;
	private View crashBanner;
	private TextView crashBannerText;
	private View crashBannerAction;
	private ExtendedFloatingActionButton shareAllFab;
	private View selectionActionsBar;
	private MaterialButton selectionCopyButton;
	private MaterialButton selectionShareButton;
	private MaterialButton selectionExportButton;
	private MaterialButton selectionRangeButton;

    private MaterialToolbar toolbar;
    private boolean selectionMode;
	private int selectionAnchorPosition = RecyclerView.NO_POSITION;
	private int lastInteractedPosition = RecyclerView.NO_POSITION;
	private boolean refreshing;
	private String sourceFilter = SOURCE_ALL;
	private String searchQuery = "";
	private List<LogEntry> pendingExportEntries = Collections.emptyList();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		setContentView(R.layout.activity_log_viewer);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> handleBackNavigation());
        toolbar.setOnMenuItemClickListener(this::onToolbarItemClicked);
		SystemBarInsetsHelper.applySystemBarPadding(toolbar, true, true, false, true);
		SystemBarInsetsHelper.applySystemBarPadding(findViewById(R.id.content_container), false, true, true, true);
		SystemBarInsetsHelper.applySystemBarPadding(findViewById(R.id.selection_actions_bar), false, true, true, true);

		bindViews();
		configureSearchAndFilters();
		configureSelectionActions();
		shareAllFab.setIcon(MaterialSymbols.drawable(this, "folder_zip", COLOR_ON_PRIMARY, 20));
		shareAllFab.setOnClickListener(v -> shareLogEntries(new ArrayList<>(logEntries)));
		adapter = new LogAdapter();
		logsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
		logsRecyclerView.setAdapter(adapter);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackNavigation();
            }
        });
        updateToolbar();

		refreshLogs();
	}

    private void handleBackNavigation() {
        if (selectionMode) {
            finishSelectionMode();
        } else {
            finish();
        }
    }

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQUEST_EXPORT_LOGS) {
			return;
		}
		List<LogEntry> exportEntries = new ArrayList<>(pendingExportEntries);
		pendingExportEntries = Collections.emptyList();
		if (resultCode != RESULT_OK || data == null || data.getData() == null || exportEntries.isEmpty()) {
			return;
		}
		Uri outputUri = data.getData();
		new Thread(() -> {
			try (OutputStream rawStream = getContentResolver().openOutputStream(outputUri, "w");
				 BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(requireNonNull(rawStream))) {
				writeLogsZip(bufferedOutputStream, exportEntries);
				runOnUiThread(() -> toast(getString(R.string.log_viewer_export_done)));
			} catch (Exception exception) {
				runOnUiThread(() -> showError(exception));
			}
		}).start();
	}

    private boolean onToolbarItemClicked(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh_logs) {
            refreshLogs();
        } else if (id == R.id.action_select_all) {
            selectAllLogs();
        } else if (id == R.id.action_select_range) {
            selectRange();
        } else if (id == R.id.action_copy_logs) {
            copySelectedLogs();
        } else if (id == R.id.action_share_logs) {
            shareSelectedLogs();
        } else if (id == R.id.action_export_logs) {
            exportSelectedLogs();
        } else {
            return false;
        }
        return true;
    }

    private void updateToolbar() {
        toolbar.getMenu().clear();
        toolbar.inflateMenu(selectionMode ? R.menu.menu_log_selection : R.menu.menu_log_viewer);
        toolbar.setBackgroundColor(selectionMode ? COLOR_PRIMARY : COLOR_SURFACE);
        toolbar.setTitleTextColor(selectionMode ? COLOR_ON_PRIMARY : COLOR_ON_SURFACE);
        toolbar.setNavigationIcon(MaterialSymbols.drawable(this, selectionMode ? "close" : "arrow_back", selectionMode ? COLOR_ON_PRIMARY : COLOR_ON_SURFACE, 24));
        if (selectionMode) {
            toolbar.setTitle(getString(R.string.log_viewer_selection_title, selectedPositions.size()));
            toolbar.setSubtitle(null);
            toolbar.getMenu().findItem(R.id.action_select_all).setIcon(MaterialSymbols.drawable(this, "select_all", COLOR_ON_PRIMARY, 24));
        } else {
            toolbar.setTitle(R.string.log_viewer_title);
            toolbar.getMenu().findItem(R.id.action_refresh_logs).setIcon(MaterialSymbols.drawable(this, "refresh", COLOR_ON_SURFACE, 24));
            toolbar.getMenu().findItem(R.id.action_refresh_logs).setEnabled(!refreshing);
            if (!refreshing) {
                updateSummary();
            }
        }
    }

	private void bindViews() {
		emptyListText = findViewById(R.id.text_empty_logs);
		logsRecyclerView = findViewById(R.id.recycler_logs);
		searchInput = findViewById(R.id.search_logs_input);
		sourceChipGroup = findViewById(R.id.log_source_chip_group);
		crashBanner = findViewById(R.id.log_crash_banner);
		crashBannerText = findViewById(R.id.log_crash_banner_text);
        crashBannerAction = findViewById(R.id.log_crash_banner_action);
        ((ImageView) findViewById(R.id.log_crash_banner_icon)).setImageDrawable(MaterialSymbols.drawable(this, "bug_report", COLOR_ERROR, 20));
		shareAllFab = findViewById(R.id.fab_share_all_logs);
		selectionActionsBar = findViewById(R.id.selection_actions_bar);
		selectionCopyButton = findViewById(R.id.selection_copy_logs);
		selectionShareButton = findViewById(R.id.selection_share_logs);
		selectionExportButton = findViewById(R.id.selection_export_logs);
		selectionRangeButton = findViewById(R.id.selection_range_logs);
	}

	private void configureSearchAndFilters() {
        TextInputLayout searchLayout = findViewById(R.id.search_logs_layout);
        searchLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        searchLayout.setHint(getString(R.string.log_viewer_search_hint));
        searchLayout.setStartIconDrawable(MaterialSymbols.drawable(this, "search", COLOR_ON_SURFACE_VARIANT, 22));
        searchLayout.setBoxBackgroundColor(COLOR_SURFACE);
        searchInput.setSingleLine(true);
		searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		searchInput.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				searchQuery = s == null ? "" : s.toString().trim();
				applyFilters();
			}

			@Override
			public void afterTextChanged(Editable s) {
			}
		});
        sourceChipGroup.setSingleSelection(true);
        sourceChipGroup.setSelectionRequired(true);
        Chip allChip = addSourceChip(R.string.log_viewer_filter_all, SOURCE_ALL);
        addSourceChip(R.string.log_viewer_filter_runtime, SOURCE_RUNTIME);
        crashChip = addSourceChip(R.string.log_viewer_filter_crash, SOURCE_CRASH);
        addSourceChip(R.string.log_viewer_filter_harmony, SOURCE_HARMONY);
        addSourceChip(R.string.log_viewer_filter_console, SOURCE_CONSOLE);
        allChip.setChecked(true);
	}

	private Chip addSourceChip(int labelRes, String source) {
		Chip chip = new Chip(this);
        chip.setText(SOURCE_CRASH.equals(source) ? getString(labelRes, 0) : getString(labelRes));
		chip.setCheckable(true);
		chip.setClickable(true);
		chip.setEnsureMinTouchTargetSize(true);
		chip.setTextSize(13);
		chip.setChipBackgroundColor(new ColorStateList(
				new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
				new int[] {COLOR_PRIMARY_CONTAINER, 0xFF242833}));
		chip.setTextColor(new ColorStateList(
				new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
				new int[] {COLOR_ON_PRIMARY_CONTAINER, COLOR_ON_SURFACE_VARIANT}));
		chip.setOnCheckedChangeListener((button, checked) -> {
			if (checked && !source.equals(sourceFilter)) {
				sourceFilter = source;
				applyFilters();
			}
		});
		sourceChipGroup.addView(chip, new ChipGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return chip;
	}

	private void configureSelectionActions() {
		selectionCopyButton.setIcon(MaterialSymbols.drawable(this, "content_copy", COLOR_ON_SURFACE_VARIANT, 20));
		selectionShareButton.setIcon(MaterialSymbols.drawable(this, "share", COLOR_ON_SURFACE_VARIANT, 20));
		selectionExportButton.setIcon(MaterialSymbols.drawable(this, "ios_share", COLOR_ON_SURFACE_VARIANT, 20));
		selectionRangeButton.setIcon(MaterialSymbols.drawable(this, "unfold_more", COLOR_ON_SURFACE_VARIANT, 20));
		selectionCopyButton.setOnClickListener(v -> copySelectedLogs());
		selectionShareButton.setOnClickListener(v -> shareSelectedLogs());
		selectionExportButton.setOnClickListener(v -> exportSelectedLogs());
		selectionRangeButton.setOnClickListener(v -> selectRange());
	}

	private void refreshLogs() {
		if (refreshing) {
			return;
		}
		refreshing = true;
		updateSubtitle(getString(R.string.log_viewer_status_loading));
		finishSelectionMode();
        updateToolbar();
		new Thread(() -> {
			List<LogEntry> entries = scanLogEntries();
			runOnUiThread(() -> applyRefreshedLogs(entries));
		}).start();
	}

	private void applyRefreshedLogs(List<LogEntry> entries) {
		refreshing = false;
		logEntries.clear();
		logEntries.addAll(entries);
		updateCrashBanner();
		applyFilters();
		updateSummary();
        updateToolbar();
	}

	private void updateSummary() {
		if (logEntries.isEmpty()) {
			updateSubtitle(getString(R.string.log_viewer_summary_empty));
			return;
		}
		LogEntry newestEntry = logEntries.get(0);
		updateSubtitle(getString(R.string.log_viewer_summary_count, logEntries.size(), formatRecentDate(newestEntry.lastModified)));
	}

	private void updateEmptyListVisibility() {
		boolean empty = visibleLogEntries.isEmpty();
		if (empty) {
			emptyListText.setText(logEntries.isEmpty()
					? R.string.log_viewer_summary_empty
					: R.string.log_viewer_no_filter_results);
		}
		emptyListText.setVisibility(empty ? View.VISIBLE : View.GONE);
		logsRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
	}

    private void updateSubtitle(CharSequence subtitle) {
        if (!selectionMode) {
            toolbar.setSubtitle(subtitle);
        }
    }

	private void applyFilters() {
		if (adapter == null) {
			return;
		}
		finishSelectionMode();
		visibleLogEntries.clear();
		String query = searchQuery.toLowerCase(Locale.ROOT);
		for (LogEntry entry : logEntries) {
			if (!SOURCE_ALL.equals(sourceFilter) && !sourceFilter.equals(entry.sourceType)) {
				continue;
			}
			if (!query.isEmpty() && !entry.file.getName().toLowerCase(Locale.ROOT).contains(query)) {
				continue;
			}
			visibleLogEntries.add(entry);
		}
		rebuildListItems();
		adapter.notifyDataSetChanged();
        shareAllFab.setVisibility(logEntries.isEmpty() ? View.GONE : View.VISIBLE);
        updateEmptyListVisibility();
        updateFilterChipLabels();
	}

	private void rebuildListItems() {
		listItems.clear();
		String previousGroup = null;
		for (int i = 0; i < visibleLogEntries.size(); i++) {
			LogEntry entry = visibleLogEntries.get(i);
			String group = formatGroupDate(entry.lastModified);
			if (!group.equals(previousGroup)) {
				listItems.add(ListItem.header(group));
				previousGroup = group;
			}
			listItems.add(ListItem.entry(i, entry));
		}
	}

	private void updateFilterChipLabels() {
		if (crashChip != null) {
			int crashCount = 0;
			for (LogEntry entry : logEntries) {
				if (SOURCE_CRASH.equals(entry.sourceType)) {
					crashCount++;
				}
			}
			crashChip.setText(getString(R.string.log_viewer_filter_crash, crashCount));
		}
	}

	private void updateCrashBanner() {
		LogEntry crashEntry = null;
		for (LogEntry entry : logEntries) {
			if (SOURCE_CRASH.equals(entry.sourceType)) {
				crashEntry = entry;
				break;
			}
		}
		if (crashEntry == null) {
			crashBanner.setVisibility(View.GONE);
			return;
		}
		crashBanner.setVisibility(View.VISIBLE);
		crashBannerText.setText(getString(R.string.log_viewer_crash_banner, crashEntry.file.getName()));
        final LogEntry finalCrashEntry = crashEntry;
        crashBannerAction.setOnClickListener(v -> shareLogEntries(Collections.singletonList(finalCrashEntry)));
        crashBanner.setOnClickListener(v -> openLogDetail(finalCrashEntry));
	}

	private List<LogEntry> scanLogEntries() {
		List<LogEntry> results = new ArrayList<>();
		Set<String> seenPaths = new HashSet<>();
		addLogsFromRoot(results, seenPaths, getFilesDir(), ROOT_INTERNAL_ARCHIVE, getString(R.string.log_viewer_root_internal));
		File externalFilesDir = getExternalFilesDir(null);
		if (externalFilesDir != null) {
			addLogsFromRoot(results, seenPaths, externalFilesDir, ROOT_EXTERNAL_ARCHIVE, getString(R.string.log_viewer_root_external));
		}
		results.sort((left, right) -> {
			int modifiedCompare = Long.compare(right.lastModified, left.lastModified);
			if (modifiedCompare != 0) {
				return modifiedCompare;
			}
			return left.archivePath.compareToIgnoreCase(right.archivePath);
		});
		return results;
	}

	private void addLogsFromRoot(List<LogEntry> results, Set<String> seenPaths, File rootDirectory, String archiveRootName, String displayRootName) {
		if (rootDirectory == null || !rootDirectory.isDirectory()) {
			return;
		}
		collectRelevantLogs(results, seenPaths, rootDirectory, rootDirectory, archiveRootName, displayRootName);
	}

	private void collectRelevantLogs(List<LogEntry> results, Set<String> seenPaths, File scanRoot, File currentFile, String archiveRootName, String displayRootName) {
		File[] children = currentFile.listFiles();
		if (children == null) {
			return;
		}
		List<File> sortedChildren = new ArrayList<>(children.length);
		Collections.addAll(sortedChildren, children);
		sortedChildren.sort((left, right) -> left.getName().compareToIgnoreCase(right.getName()));
		for (File child : sortedChildren) {
			if (child.isDirectory()) {
				collectRelevantLogs(results, seenPaths, scanRoot, child, archiveRootName, displayRootName);
				continue;
			}
			if (!child.isFile()) {
				continue;
			}
			String relativePath = buildRelativePath(scanRoot, child);
			if (!isRelevantLogFile(relativePath, child.getName())) {
				continue;
			}
			String canonicalPath = getCanonicalOrAbsolutePath(child);
			if (!seenPaths.add(canonicalPath)) {
				continue;
			}
			String normalizedRelativePath = normalizeRelativePath(relativePath);
			results.add(new LogEntry(
					child,
					buildDisplayPath(displayRootName, normalizedRelativePath),
					buildArchivePath(archiveRootName, normalizedRelativePath),
					resolveSourceLabel(normalizedRelativePath, child.getName()),
					resolveSourceType(normalizedRelativePath, child.getName()),
					displayRootName,
					child.lastModified(),
					child.length()
			));
		}
	}

	private boolean isRelevantLogFile(String relativePath, String fileName) {
		String normalizedPath = normalizeRelativePath(relativePath).toLowerCase(Locale.ROOT);
		String lowerFileName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
		if (normalizedPath.startsWith("logs/") || normalizedPath.contains("/logs/")) {
			return true;
		}
		if (normalizedPath.startsWith("sentry/reports/") || normalizedPath.contains("/sentry/reports/")) {
			return true;
		}
		return lowerFileName.endsWith(".log");
	}

	private String resolveSourceLabel(String relativePath, String fileName) {
		String sourceType = resolveSourceType(relativePath, fileName);
		if (SOURCE_CRASH.equals(sourceType)) {
			return getString(R.string.log_viewer_source_sentry);
		}
		if (SOURCE_HARMONY.equals(sourceType)) {
			return getString(R.string.log_viewer_source_harmony);
		}
		if (SOURCE_CONSOLE.equals(sourceType)) {
			return getString(R.string.log_viewer_source_console);
		}
		if (SOURCE_RUNTIME.equals(sourceType)) {
			return getString(R.string.log_viewer_source_runtime);
		}
		return getString(R.string.log_viewer_source_other);
	}

	private String resolveSourceType(String relativePath, String fileName) {
		String normalizedPath = normalizeRelativePath(relativePath).toLowerCase(Locale.ROOT);
		String lowerFileName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
		if (normalizedPath.startsWith("sentry/reports/") || normalizedPath.contains("/sentry/reports/")) {
			return SOURCE_CRASH;
		}
		if ("monomod-harmony.log".equals(lowerFileName) || lowerFileName.contains("harmony")) {
			return SOURCE_HARMONY;
		}
		if ("console_history.log".equals(lowerFileName) || lowerFileName.contains("console")) {
			return SOURCE_CONSOLE;
		}
		if (normalizedPath.startsWith("logs/") || normalizedPath.contains("/logs/")) {
			return SOURCE_RUNTIME;
		}
		return SOURCE_RUNTIME;
	}

	private String buildDisplayPath(String displayRootName, String relativePath) {
		return displayRootName + "/" + relativePath;
	}

	private String buildArchivePath(String archiveRootName, String relativePath) {
		return archiveRootName + "/" + relativePath;
	}

	private String normalizeRelativePath(String relativePath) {
		return relativePath == null ? "" : relativePath.replace('\\', '/');
	}

	private String getCanonicalOrAbsolutePath(File file) {
		try {
			return file.getCanonicalPath();
		} catch (IOException ignored) {
			return file.getAbsolutePath();
		}
	}

	private void onLogClicked(int position) {
		if (position < 0 || position >= visibleLogEntries.size()) {
			return;
		}
		lastInteractedPosition = position;
        if (selectionMode) {
			toggleSelection(position);
			return;
		}
		openLogDetail(visibleLogEntries.get(position));
	}

	private boolean onLogLongPressed(int position) {
		if (position < 0 || position >= visibleLogEntries.size()) {
			return false;
		}
		lastInteractedPosition = position;
        if (!selectionMode) {
			startSelection(position);
		} else {
			toggleSelection(position);
		}
		return true;
	}

	private void openLogDetail(LogEntry entry) {
		try {
			Intent intent = new Intent(this, LogFileViewerActivity.class);
			intent.putExtra(LogFileViewerActivity.EXTRA_FILE_PATH, entry.file.getAbsolutePath());
			intent.putExtra(LogFileViewerActivity.EXTRA_DISPLAY_NAME, entry.file.getName());
			intent.putExtra(LogFileViewerActivity.EXTRA_DISPLAY_PATH, entry.displayPath);
			intent.putExtra(LogFileViewerActivity.EXTRA_SOURCE_LABEL, entry.sourceLabel);
			intent.putExtra(LogFileViewerActivity.EXTRA_LAST_MODIFIED, entry.lastModified);
			intent.putExtra(LogFileViewerActivity.EXTRA_FILE_SIZE, entry.size);
			startActivity(intent);
		} catch (Exception exception) {
			showError(exception);
		}
	}

	private void startSelection(int position) {
		selectionAnchorPosition = position;
		lastInteractedPosition = position;
        selectionMode = true;
		setItemSelected(position, true);
		setSelectionActionsVisible(true);
		updateSelectionActionMode();
	}

	private void toggleSelection(int position) {
		boolean selected = selectedPositions.contains(position);
		setItemSelected(position, !selected);
		if (selectedPositions.isEmpty()) {
			finishSelectionMode();
			return;
		}
		updateSelectionActionMode();
	}

	private void setItemSelected(int position, boolean selected) {
		if (selected) {
			selectedPositions.add(position);
		} else {
			selectedPositions.remove(position);
		}
		adapter.notifyDataSetChanged();
	}

    private void updateSelectionActionMode() {
        if (selectionMode) {
            updateToolbar();
        }
    }

    private void finishSelectionMode() {
        boolean wasSelecting = selectionMode;
        selectionMode = false;
        selectedPositions.clear();
        selectionAnchorPosition = RecyclerView.NO_POSITION;
        lastInteractedPosition = RecyclerView.NO_POSITION;
        setSelectionActionsVisible(false);
        if (wasSelecting) {
            adapter.notifyDataSetChanged();
            updateToolbar();
        }
    }

	private void setSelectionActionsVisible(boolean visible) {
        if (visible && selectionActionsBar.getVisibility() != View.VISIBLE) {
            selectionActionsBar.setVisibility(View.VISIBLE);
            selectionActionsBar.setAlpha(0f);
            selectionActionsBar.setTranslationY(ExtraSettingsUi.dp(this, 16));
            selectionActionsBar.animate().alpha(1f).translationY(0f).setDuration(220)
                    .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        } else if (!visible) {
            selectionActionsBar.animate().cancel();
            selectionActionsBar.setVisibility(View.GONE);
        }
		shareAllFab.setVisibility(visible ? View.GONE : (logEntries.isEmpty() ? View.GONE : View.VISIBLE));
	}

	private List<LogEntry> getSelectedEntries() {
		List<LogEntry> entries = new ArrayList<>();
		for (int i = 0; i < visibleLogEntries.size(); i++) {
			if (selectedPositions.contains(i)) {
				entries.add(visibleLogEntries.get(i));
			}
		}
		return entries;
	}

	private void selectAllLogs() {
		if (visibleLogEntries.isEmpty()) {
			return;
		}
		selectedPositions.clear();
		for (int i = 0; i < visibleLogEntries.size(); i++) {
			selectedPositions.add(i);
		}
		selectionAnchorPosition = 0;
		lastInteractedPosition = visibleLogEntries.size() - 1;
		adapter.notifyDataSetChanged();
		updateSelectionActionMode();
	}

	private void selectRange() {
		int anchor = selectionAnchorPosition;
		if (anchor == RecyclerView.NO_POSITION && !selectedPositions.isEmpty()) {
			anchor = selectedPositions.iterator().next();
		}
		if (anchor == RecyclerView.NO_POSITION || lastInteractedPosition == RecyclerView.NO_POSITION || anchor == lastInteractedPosition) {
			toast(getString(R.string.log_viewer_range_hint));
			return;
		}
		int start = Math.min(anchor, lastInteractedPosition);
		int end = Math.max(anchor, lastInteractedPosition);
		for (int i = start; i <= end; i++) {
			selectedPositions.add(i);
		}
		adapter.notifyDataSetChanged();
		updateSelectionActionMode();
	}

	private void copySelectedLogs() {
		List<LogEntry> entries = getSelectedEntries();
		if (entries.isEmpty()) {
			return;
		}
		new Thread(() -> {
			try {
				String text = buildCombinedLogPreview(entries);
				runOnUiThread(() -> {
					ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
					if (clipboardManager != null) {
						clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_viewer_clipboard_label), text));
						toast(getString(R.string.log_viewer_copied));
					}
				});
			} catch (Exception exception) {
				runOnUiThread(() -> showError(exception));
			}
		}).start();
	}

	private void shareSelectedLogs() {
		List<LogEntry> entries = getSelectedEntries();
		if (!entries.isEmpty()) {
			shareLogEntries(entries);
		}
	}

	private void shareLogEntries(List<LogEntry> entries) {
		if (entries == null || entries.isEmpty()) {
			return;
		}
		List<LogEntry> exportEntries = new ArrayList<>(entries);
		new Thread(() -> {
			try {
				File sharedDirectory = new File(getCacheDir(), "shared");
				ensureDirectory(sharedDirectory);
				File zipFile = new File(sharedDirectory, buildDefaultLogExportName());
				if (zipFile.exists() && !zipFile.delete()) {
					throw new IOException("Unable to replace existing shared ZIP: " + zipFile.getAbsolutePath());
				}
				try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(zipFile))) {
					writeLogsZip(outputStream, exportEntries);
				}
				Uri uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", zipFile);
				runOnUiThread(() -> {
					try {
						Intent shareIntent = new Intent(Intent.ACTION_SEND);
						shareIntent.setType("application/zip");
						shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
						shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_viewer_title));
						shareIntent.setClipData(ClipData.newRawUri(getString(R.string.log_viewer_title), uri));
						shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
						startActivity(Intent.createChooser(shareIntent, getString(R.string.log_viewer_share_chooser)));
					} catch (Exception exception) {
						showError(exception);
					}
				});
			} catch (Exception exception) {
				runOnUiThread(() -> showError(exception));
			}
		}).start();
	}

	private void exportSelectedLogs() {
		List<LogEntry> entries = getSelectedEntries();
		if (entries.isEmpty()) {
			return;
		}
		pendingExportEntries = new ArrayList<>(entries);
		try {
			Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
			intent.addCategory(Intent.CATEGORY_OPENABLE);
			intent.setType("application/zip");
			intent.putExtra(Intent.EXTRA_TITLE, buildDefaultLogExportName());
			startActivityForResult(intent, REQUEST_EXPORT_LOGS);
		} catch (Exception exception) {
			pendingExportEntries = Collections.emptyList();
			showError(exception);
		}
	}

	private String buildCombinedLogPreview(List<LogEntry> entries) throws Exception {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < entries.size(); i++) {
			LogEntry entry = entries.get(i);
			if (i > 0) {
				builder.append("\n\n");
			}
			builder.append("===== ")
					.append(entry.displayPath)
					.append(" | ")
					.append(formatDate(entry.lastModified))
					.append(" | ")
					.append(Formatter.formatFileSize(this, entry.size))
					.append(" =====\n");
			builder.append(readPreviewText(entry.file));
		}
		return builder.toString();
	}

	private void writeLogsZip(OutputStream outputStream, List<LogEntry> entries) throws Exception {
		Set<String> usedNames = new HashSet<>();
		try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
			for (LogEntry entry : entries) {
				String entryName = makeUniqueZipEntryName(entry.archivePath, usedNames);
				zipOutputStream.putNextEntry(new ZipEntry(entryName));
				try (InputStream inputStream = new BufferedInputStream(new FileInputStream(entry.file))) {
					copyStream(inputStream, zipOutputStream);
				}
				zipOutputStream.closeEntry();
			}
		}
	}

	private String makeUniqueZipEntryName(String entryName, Set<String> usedNames) {
		String normalizedName = entryName.replace('\\', '/');
		if (usedNames.add(normalizedName)) {
			return normalizedName;
		}
		String baseName = removeExtension(normalizedName);
		String extension = "";
		int extensionIndex = normalizedName.lastIndexOf('.');
		if (extensionIndex >= 0) {
			extension = normalizedName.substring(extensionIndex);
		}
		int suffix = 2;
		String candidate;
		do {
			candidate = baseName + "-" + suffix + extension;
			suffix++;
		} while (!usedNames.add(candidate));
		return candidate;
	}

	private String readPreviewText(File file) throws Exception {
		if (!isProbablyText(file)) {
			return getString(R.string.log_viewer_binary_unavailable);
		}
		long length = file.length();
		if (length <= MAX_PREVIEW_BYTES) {
			return readTextFile(file);
		}
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file));
			 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			long toSkip = Math.max(0L, length - MAX_PREVIEW_BYTES);
			while (toSkip > 0L) {
				long skipped = inputStream.skip(toSkip);
				if (skipped > 0L) {
					toSkip -= skipped;
					continue;
				}
				if (inputStream.read() == -1) {
					break;
				}
				toSkip--;
			}
			copyStream(inputStream, outputStream);
			String content = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
			int firstNewline = content.indexOf('\n');
			if (firstNewline >= 0 && firstNewline + 1 < content.length()) {
				content = content.substring(firstNewline + 1);
			}
			return getString(R.string.log_viewer_preview_truncated, Formatter.formatFileSize(this, MAX_PREVIEW_BYTES)) + "\n\n" + content;
		}
	}

	private boolean isProbablyText(File file) throws Exception {
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
			byte[] sample = new byte[2048];
			int read = inputStream.read(sample);
			if (read <= 0) {
				return true;
			}
			for (int i = 0; i < read; i++) {
				if (sample[i] == 0) {
					return false;
				}
			}
			return true;
		}
	}

	private String readTextFile(File file) throws IOException {
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file));
			 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			copyStream(inputStream, outputStream);
			return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private void copyStream(InputStream inputStream, OutputStream outputStream) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = inputStream.read(buffer)) != -1) {
			outputStream.write(buffer, 0, read);
		}
		outputStream.flush();
	}

	private void ensureDirectory(File directory) {
		if (directory.isDirectory()) {
			return;
		}
		if (!directory.mkdirs() && !directory.isDirectory()) {
			throw new IllegalStateException("Unable to create directory: " + directory.getAbsolutePath());
		}
	}

	private String buildRelativePath(File root, File file) {
		String rootPath = root.getAbsolutePath();
		String filePath = file.getAbsolutePath();
		if (filePath.startsWith(rootPath)) {
			String relative = filePath.substring(rootPath.length());
			if (relative.startsWith(File.separator)) {
				relative = relative.substring(1);
			}
			return relative.replace(File.separatorChar, '/');
		}
		return file.getName();
	}

	private String removeExtension(String fileName) {
		int extensionIndex = fileName.lastIndexOf('.');
		if (extensionIndex <= 0) {
			return fileName;
		}
		return fileName.substring(0, extensionIndex);
	}

	private String buildDefaultLogExportName() {
		String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
		return "sts2-logs-" + timestamp + ".zip";
	}

	private String formatDate(long timeMillis) {
		return new SimpleDateFormat(DATE_TIME_PATTERN, Locale.getDefault()).format(new Date(timeMillis));
	}

	private String formatRecentDate(long timeMillis) {
		Calendar now = Calendar.getInstance();
		Calendar value = Calendar.getInstance();
		value.setTimeInMillis(timeMillis);
		if (isSameDay(now, value)) {
			return getString(R.string.log_viewer_date_today) + " " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(value.getTime());
		}
		Calendar yesterday = (Calendar) now.clone();
		yesterday.add(Calendar.DAY_OF_YEAR, -1);
		if (isSameDay(yesterday, value)) {
			return getString(R.string.log_viewer_date_yesterday) + " " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(value.getTime());
		}
		return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(value.getTime());
	}

	private String formatGroupDate(long timeMillis) {
		Calendar now = Calendar.getInstance();
		Calendar value = Calendar.getInstance();
		value.setTimeInMillis(timeMillis);
		if (isSameDay(now, value)) {
			return getString(R.string.log_viewer_date_today);
		}
		Calendar yesterday = (Calendar) now.clone();
		yesterday.add(Calendar.DAY_OF_YEAR, -1);
		if (isSameDay(yesterday, value)) {
			return getString(R.string.log_viewer_date_yesterday);
		}
		return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(value.getTime());
	}

	private boolean isSameDay(Calendar left, Calendar right) {
		return left.get(Calendar.ERA) == right.get(Calendar.ERA)
				&& left.get(Calendar.YEAR) == right.get(Calendar.YEAR)
				&& left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR);
	}

	private <T> T requireNonNull(T value) {
		if (value == null) {
			throw new IllegalStateException("Received null stream from content resolver.");
		}
		return value;
	}

	private void toast(String message) {
		showSnackbar(message, Snackbar.LENGTH_SHORT);
	}

	private void showError(Exception exception) {
		String detail = exception.getMessage();
		if (detail == null || detail.trim().isEmpty()) {
			detail = exception.getClass().getSimpleName();
		}
		showSnackbar(getString(R.string.error_operation_failed) + ": " + detail, Snackbar.LENGTH_LONG);
	}

	private void showSnackbar(String message, int duration) {
		View anchor = findViewById(android.R.id.content);
		if (anchor != null && message != null && !message.trim().isEmpty()) {
			Snackbar.make(anchor, message, duration).show();
		}
	}


	private final class LogAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		private static final int VIEW_TYPE_HEADER = 1;
		private static final int VIEW_TYPE_ENTRY = 2;

		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			if (viewType == VIEW_TYPE_HEADER) {
				TextView header = new TextView(parent.getContext());
				header.setTextColor(COLOR_ON_SURFACE_VARIANT);
				header.setTextSize(13);
				header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
				header.setGravity(Gravity.CENTER_VERTICAL);
				header.setPadding(20, ExtraSettingsUi.dp(parent.getContext(), 14), 16, ExtraSettingsUi.dp(parent.getContext(), 6));
				header.setBackgroundColor(COLOR_BACKGROUND);
				return new HeaderViewHolder(header);
			}
			View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log_entry, parent, false);
			return new LogViewHolder(view);
		}

		@Override
		public int getItemViewType(int position) {
			return listItems.get(position).type == ListItem.TYPE_HEADER ? VIEW_TYPE_HEADER : VIEW_TYPE_ENTRY;
		}

		@Override
		public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
			ListItem item = listItems.get(position);
			if (holder instanceof HeaderViewHolder) {
				((HeaderViewHolder) holder).text.setText(item.header);
				return;
			}
			LogViewHolder entryHolder = (LogViewHolder) holder;
			entryHolder.bind(item.entry, item.entryIndex, selectedPositions.contains(item.entryIndex));
		}

		@Override
		public int getItemCount() {
			return listItems.size();
		}
	}

	private static final class HeaderViewHolder extends RecyclerView.ViewHolder {
		final TextView text;

		HeaderViewHolder(TextView itemView) {
			super(itemView);
			text = itemView;
		}
	}

	private final class LogViewHolder extends RecyclerView.ViewHolder {
		private final View container;
		private final TextView nameText;
		private final TextView sourceBadgeText;
		private final TextView storageText;
		private final TextView timeText;
		private final ImageView iconView;

		LogViewHolder(View itemView) {
			super(itemView);
			container = itemView.findViewById(R.id.log_row_container);
			nameText = itemView.findViewById(R.id.text_log_name);
			sourceBadgeText = itemView.findViewById(R.id.text_log_source);
			storageText = itemView.findViewById(R.id.text_log_path);
			timeText = itemView.findViewById(R.id.text_log_meta);
            iconView = itemView.findViewById(R.id.image_log_icon);
			itemView.setOnClickListener(v -> {
				int position = getBindingAdapterPosition();
				if (position != RecyclerView.NO_POSITION) {
					ListItem item = listItems.get(position);
					if (item.type == ListItem.TYPE_ENTRY) {
						onLogClicked(item.entryIndex);
					}
				}
			});
			itemView.setOnLongClickListener(v -> {
				int position = getBindingAdapterPosition();
				if (position == RecyclerView.NO_POSITION) {
					return false;
				}
				ListItem item = listItems.get(position);
				return item.type == ListItem.TYPE_ENTRY && onLogLongPressed(item.entryIndex);
			});
		}

		void bind(LogEntry entry, int entryIndex, boolean selected) {
			nameText.setText(entry.file.getName());
			sourceBadgeText.setText(entry.sourceLabel);
			storageText.setText(entry.storageLabel + " · " + Formatter.formatFileSize(LogViewerActivity.this, entry.size));
			timeText.setText(formatTime(entry.lastModified));
			boolean crash = SOURCE_CRASH.equals(entry.sourceType);
			int iconTint = selected ? COLOR_ON_PRIMARY_CONTAINER : (crash ? COLOR_ERROR : COLOR_PRIMARY);
			String glyph = selected ? "check_circle" : iconForEntry(entry);
			iconView.setImageDrawable(MaterialSymbols.drawable(LogViewerActivity.this, glyph, iconTint, 23));
			GradientDrawable iconBackground = new GradientDrawable();
			iconBackground.setShape(GradientDrawable.OVAL);
			iconBackground.setColor(selected ? COLOR_PRIMARY_CONTAINER : (crash ? COLOR_ERROR_CONTAINER : 0xFF242833));
			iconView.setBackground(iconBackground);
			GradientDrawable rowBackground = new GradientDrawable();
			rowBackground.setColor(selected ? COLOR_PRIMARY_CONTAINER : (crash ? 0x1FFF7268 : Color.TRANSPARENT));
			container.setBackground(rowBackground);
			nameText.setTextColor(selected ? COLOR_ON_PRIMARY_CONTAINER : COLOR_ON_SURFACE);
			storageText.setTextColor(selected ? COLOR_ON_PRIMARY_CONTAINER : COLOR_ON_SURFACE_VARIANT);
			timeText.setTextColor(selected ? COLOR_ON_PRIMARY_CONTAINER : COLOR_ON_SURFACE_VARIANT);
			sourceBadgeText.setTextColor(selected ? COLOR_ON_PRIMARY_CONTAINER : (crash ? COLOR_ERROR : COLOR_ON_SURFACE_VARIANT));
		}
	}

	private String iconForEntry(LogEntry entry) {
		if (SOURCE_CRASH.equals(entry.sourceType)) {
			return "bug_report";
		}
		if (SOURCE_HARMONY.equals(entry.sourceType)) {
			return "build";
		}
		if (SOURCE_CONSOLE.equals(entry.sourceType)) {
			return "history";
		}
		return "terminal";
	}

	private String formatTime(long timeMillis) {
		return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(timeMillis));
	}

	private static final class ListItem {
		static final int TYPE_HEADER = 1;
		static final int TYPE_ENTRY = 2;
		final int type;
		final String header;
		final int entryIndex;
		final LogEntry entry;

		private ListItem(int type, String header, int entryIndex, LogEntry entry) {
			this.type = type;
			this.header = header;
			this.entryIndex = entryIndex;
			this.entry = entry;
		}

		static ListItem header(String value) {
			return new ListItem(TYPE_HEADER, value, RecyclerView.NO_POSITION, null);
		}

		static ListItem entry(int index, LogEntry value) {
			return new ListItem(TYPE_ENTRY, null, index, value);
		}
	}

	private static final class LogEntry {
		final File file;
		final String displayPath;
		final String archivePath;
		final String sourceLabel;
		final String sourceType;
		final String storageLabel;
		final long lastModified;
		final long size;

		LogEntry(File file, String displayPath, String archivePath, String sourceLabel, String sourceType, String storageLabel, long lastModified, long size) {
			this.file = file;
			this.displayPath = displayPath;
			this.archivePath = archivePath;
			this.sourceLabel = sourceLabel;
			this.sourceType = sourceType;
			this.storageLabel = storageLabel;
			this.lastModified = lastModified;
			this.size = size;
		}
	}
}
