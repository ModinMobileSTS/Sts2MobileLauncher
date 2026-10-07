package com.godot.game;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class FileBrowserActivity extends AppCompatActivity {
	public static final String EXTRA_INITIAL_PATH = "com.godot.game.extra.FILE_BROWSER_INITIAL_PATH";

	private static final int REQUEST_IMPORT_DOCUMENTS = 3001;
	private static final int REQUEST_IMPORT_TREE = 3002;
	private static final int REQUEST_EXPORT_TREE = 3003;
	private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final int MORE_REFRESH_ITEM_ID = 0x1001;
    private static final int MORE_HIDDEN_ITEM_ID = 0x1002;
    private static final int SELECTION_OPEN_ITEM_ID = 0x1010;
    private static final int SELECTION_EXTERNAL_ITEM_ID = 0x1011;
    private static final int SELECTION_RENAME_ITEM_ID = 0x1012;
    private static final int SORT_NAME = 0;
    private static final int SORT_TIME = 1;
    private static final int SORT_SIZE = 2;
    private static final int SORT_NAME_ITEM_ID = 0x1020;
    private static final int SORT_TIME_ITEM_ID = 0x1021;
    private static final int SORT_SIZE_ITEM_ID = 0x1022;

    private final List<FileEntry> entries = new ArrayList<>();
    private final List<FileEntry> allEntries = new ArrayList<>();
    private final LinkedHashSet<Integer> selectedPositions = new LinkedHashSet<>();
    private final List<File> copiedEntries = new ArrayList<>();

    private MaterialToolbar toolbar;
    private TextView emptyText;
    private TextView listSummary;
    private TextView clipboardBannerText;
    private MaterialCardView clipboardBanner;
    private LinearLayout breadcrumbs;
    private LinearLayout selectionActionBar;
    private ImageButton clipboardBannerClose;
    private ExtendedFloatingActionButton addFab;
    private MaterialButton sortButton;
    private MaterialButton selectionCopyButton;
    private MaterialButton selectionExportButton;
    private MaterialButton selectionRenameButton;
    private MaterialButton selectionDeleteButton;
    private MaterialButton selectionMoreButton;
    private RecyclerView recyclerView;
    private FileBrowserAdapter adapter;

    private MenuItem searchMenuItem;
    private MenuItem moreMenuItem;
    private MenuItem selectAllMenuItem;
    private File rootDirectory;
    private File currentDirectory;
    private File pendingImportTargetDirectory;
    private List<File> pendingExportEntries = new ArrayList<>();
    private boolean refreshing;
    private boolean busy;
    private boolean selectionMode;
    private boolean showHiddenFiles;
    private boolean sortAscending = true;
    private int sortMode = SORT_NAME;
    private boolean gridMode;
    private boolean clipboardBannerDismissed;
    private String filterQuery = "";
    private final PathInterpolator uiInterpolator = new PathInterpolator(0.2f, 0f, 0f, 1f);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
        SystemBarInsetsHelper.enableEdgeToEdge(this);
        setContentView(R.layout.activity_file_browser);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.file_browser_title);
            getSupportActionBar().setSubtitle(R.string.file_browser_subtitle);
        }
        toolbar.setNavigationOnClickListener(v -> handleBackNavigation());
        SystemBarInsetsHelper.applySystemBarPadding(toolbar, true, true, false, true);
        SystemBarInsetsHelper.applySystemBarPadding(findViewById(R.id.content_container), false, true, true, true);
        SystemBarInsetsHelper.applySystemBarPadding(findViewById(R.id.selection_action_bar), false, true, true, true);

        bindViews();

        rootDirectory = getFilesDir();
        currentDirectory = resolveInitialDirectory(getIntent() == null ? null : getIntent().getStringExtra(EXTRA_INITIAL_PATH));
        FileBrowserSupport.ensureDirectory(rootDirectory);

        adapter = new FileBrowserAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackNavigation();
            }
        });

        updateSelectionChrome();
        refreshEntries();
    }

	/**
	 * Opens the file browser under the app private files root, optionally starting at
	 * {@code initialPath} when it is an existing directory inside that root.
	 */
	public static Intent createIntent(android.content.Context context, File initialDirectory) {
		Intent intent = new Intent(context, FileBrowserActivity.class);
		if (initialDirectory != null) {
			intent.putExtra(EXTRA_INITIAL_PATH, initialDirectory.getAbsolutePath());
		}
		return intent;
	}

	private File resolveInitialDirectory(String initialPath) {
		if (TextUtils.isEmpty(initialPath) || rootDirectory == null) {
			return rootDirectory;
		}
		try {
			File candidate = new File(initialPath);
			if (!candidate.exists()) {
				candidate = candidate.getParentFile();
			}
			if (candidate == null || !candidate.isDirectory()) {
				return rootDirectory;
			}
			File rootCanonical = rootDirectory.getCanonicalFile();
			File candidateCanonical = candidate.getCanonicalFile();
			String rootPath = rootCanonical.getAbsolutePath();
			String candidatePath = candidateCanonical.getAbsolutePath();
			if (candidatePath.equals(rootPath) || candidatePath.startsWith(rootPath + File.separator)) {
				return candidateCanonical;
			}
		} catch (Exception ignored) {
		}
		return rootDirectory;
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (!busy && !refreshing) {
			refreshEntries();
		}
	}

	@Override
	public boolean onSupportNavigateUp() {
		handleBackNavigation();
		return true;
	}

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_file_browser, menu);
        searchMenuItem = menu.findItem(R.id.action_search);
        moreMenuItem = menu.findItem(R.id.action_more);
        selectAllMenuItem = menu.findItem(R.id.action_select_all_entries);
        if (searchMenuItem != null) {
            searchMenuItem.setIcon(MaterialSymbols.drawable(this, "search", getColor(R.color.sts2_crash_on_surface), 24));
            View actionView = searchMenuItem.getActionView();
            if (actionView instanceof SearchView) {
                SearchView searchView = (SearchView) actionView;
                searchView.setQueryHint(getString(R.string.file_browser_search));
                searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override
                    public boolean onQueryTextSubmit(String query) {
                        applyFilter(query);
                        return true;
                    }

                    @Override
                    public boolean onQueryTextChange(String newText) {
                        applyFilter(newText);
                        return true;
                    }
                });
                searchView.setOnCloseListener(() -> {
                    applyFilter("");
                    return false;
                });
            }
        }
        if (moreMenuItem != null) {
            moreMenuItem.setIcon(MaterialSymbols.drawable(this, "more_vert", getColor(R.color.sts2_crash_on_surface), 24));
        }
        if (selectAllMenuItem != null) {
            selectAllMenuItem.setIcon(MaterialSymbols.drawable(this, "select_all", getColor(R.color.sts2_crash_on_primary), 24));
        }
        updateSelectionChrome();
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean normalMode = !selectionMode;
        if (searchMenuItem != null) {
            searchMenuItem.setVisible(normalMode);
        }
        if (moreMenuItem != null) {
            moreMenuItem.setVisible(normalMode);
            moreMenuItem.setEnabled(normalMode && !busy);
        }
        if (selectAllMenuItem != null) {
            selectAllMenuItem.setVisible(selectionMode);
            selectAllMenuItem.setEnabled(!busy && !entries.isEmpty());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_more) {
            showMorePopup();
            return true;
        }
        if (itemId == R.id.action_refresh_entries) {
            refreshEntries();
            return true;
        }
        if (itemId == R.id.action_show_hidden) {
            showHiddenFiles = !showHiddenFiles;
            refreshEntries();
            return true;
        }
        if (itemId == R.id.action_select_all_entries) {
            selectAllEntries();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode != RESULT_OK || data == null) {
			pendingImportTargetDirectory = null;
			if (requestCode == REQUEST_EXPORT_TREE) {
				pendingExportEntries = new ArrayList<>();
			}
			return;
		}
		if (requestCode == REQUEST_IMPORT_DOCUMENTS) {
			File targetDirectory = pendingImportTargetDirectory == null ? currentDirectory : pendingImportTargetDirectory;
			pendingImportTargetDirectory = null;
			List<Uri> uris = extractDocumentUris(data);
			if (uris.isEmpty()) {
				return;
			}
			for (Uri uri : uris) {
				takeUriPermissionIfPossible(uri, data);
			}
			runFileOperation(getString(R.string.file_browser_status_importing), () -> getString(R.string.file_browser_import_done, importDocuments(uris, targetDirectory)));
			return;
		}
		if (requestCode == REQUEST_IMPORT_TREE) {
			File targetDirectory = pendingImportTargetDirectory == null ? currentDirectory : pendingImportTargetDirectory;
			pendingImportTargetDirectory = null;
			Uri treeUri = data.getData();
			if (treeUri == null) {
				return;
			}
			takeUriPermissionIfPossible(treeUri, data);
			runFileOperation(getString(R.string.file_browser_status_importing), () -> getString(R.string.file_browser_import_done, importTree(treeUri, targetDirectory)));
			return;
		}
		if (requestCode == REQUEST_EXPORT_TREE) {
			Uri treeUri = data.getData();
			List<File> exportEntries = new ArrayList<>(pendingExportEntries);
			pendingExportEntries = new ArrayList<>();
			if (treeUri == null || exportEntries.isEmpty()) {
				return;
			}
			takeUriPermissionIfPossible(treeUri, data);
			runFileOperation(getString(R.string.file_browser_status_exporting), () -> getString(R.string.file_browser_export_done, exportEntriesToTree(exportEntries, treeUri)));
		}
	}

    private void bindViews() {
        emptyText = findViewById(R.id.text_empty_files);
        recyclerView = findViewById(R.id.recycler_files);
        listSummary = findViewById(R.id.list_summary);
        clipboardBanner = findViewById(R.id.clipboard_banner);
        clipboardBannerText = findViewById(R.id.clipboard_banner_text);
        breadcrumbs = findViewById(R.id.breadcrumbs);
        selectionActionBar = findViewById(R.id.selection_action_bar);
        addFab = findViewById(R.id.fab_add);
        sortButton = findViewById(R.id.button_sort_entries);
        selectionCopyButton = findViewById(R.id.action_selection_copy);
        selectionExportButton = findViewById(R.id.action_selection_export);
        selectionRenameButton = findViewById(R.id.action_selection_rename);
        selectionDeleteButton = findViewById(R.id.action_selection_delete);
        selectionMoreButton = findViewById(R.id.action_selection_more);
        clipboardBannerClose = findViewById(R.id.clipboard_banner_close);

        ImageView clipboardIcon = findViewById(R.id.clipboard_banner_icon);
        clipboardIcon.setImageDrawable(MaterialSymbols.drawable(this, "content_paste", getColor(R.color.sts2_crash_on_primary_container), 22));
        clipboardBannerClose.setImageDrawable(MaterialSymbols.drawable(this, "close", getColor(R.color.sts2_crash_on_primary_container), 20));
        addFab.setIcon(MaterialSymbols.drawable(this, "add", getColor(R.color.sts2_crash_on_primary), 24));
        sortButton.setIcon(MaterialSymbols.drawable(this, "sort", getColor(R.color.sts2_crash_on_surface_variant), 20));
        updateSortButton();
        updateViewModeButtons();

        addFab.setOnClickListener(v -> showAddBottomSheet());
        sortButton.setOnClickListener(v -> showSortPopup());
        findViewById(R.id.button_list_view).setOnClickListener(v -> setGridMode(false));
        findViewById(R.id.button_grid_view).setOnClickListener(v -> setGridMode(true));
        findViewById(R.id.clipboard_banner_paste).setOnClickListener(v -> pasteCopiedEntries());
        clipboardBannerClose.setOnClickListener(v -> {
            clipboardBannerDismissed = true;
            hideClipboardBanner();
        });

        setSelectionButtonIcon(selectionCopyButton, "content_copy");
        setSelectionButtonIcon(selectionExportButton, "ios_share");
        setSelectionButtonIcon(selectionRenameButton, "edit");
        setSelectionButtonIcon(selectionDeleteButton, "delete");
        setSelectionButtonIcon(selectionMoreButton, "more_horiz");
        selectionCopyButton.setOnClickListener(v -> copySelectedEntries());
        selectionExportButton.setOnClickListener(v -> exportSelectedEntries());
        selectionRenameButton.setOnClickListener(v -> showRenameDialog());
        selectionDeleteButton.setOnClickListener(v -> confirmDeleteSelectedEntries());
        selectionMoreButton.setOnClickListener(v -> showSelectionMorePopup());
    }

    private void setSelectionButtonIcon(MaterialButton button, String glyph) {
        button.setIcon(MaterialSymbols.drawable(this, glyph, getColor(R.color.sts2_crash_on_surface), 22));
        button.setIconTint(ColorStateList.valueOf(getColor(R.color.sts2_crash_on_surface)));
    }

    private void setGridMode(boolean enabled) {
        if (gridMode == enabled || busy) {
            return;
        }
        gridMode = enabled;
        int spanCount = Math.max(2, getResources().getConfiguration().screenWidthDp / 180);
        recyclerView.setLayoutManager(enabled ? new GridLayoutManager(this, spanCount) : new LinearLayoutManager(this));
        adapter.notifyDataSetChanged();
        updateViewModeButtons();
    }

    private void updateViewModeButtons() {
        ImageButton listButton = findViewById(R.id.button_list_view);
        ImageButton gridButton = findViewById(R.id.button_grid_view);
        int active = getColor(R.color.sts2_crash_primary);
        int inactive = getColor(R.color.sts2_crash_on_surface_variant);
        listButton.setImageDrawable(MaterialSymbols.drawable(this, "view_list", gridMode ? inactive : active, 22));
        gridButton.setImageDrawable(MaterialSymbols.drawable(this, "grid_view", gridMode ? active : inactive, 22));
        listButton.setSelected(!gridMode);
        gridButton.setSelected(gridMode);
    }

    private void handleBackNavigation() {
        if (selectionMode) {
            clearSelection();
            return;
        }
        if (!TextUtils.isEmpty(filterQuery)) {
            filterQuery = "";
            applyFilter("");
            if (searchMenuItem != null) {
                searchMenuItem.collapseActionView();
            }
            return;
        }
        if (!isRootDirectory(currentDirectory)) {
            File parent = currentDirectory.getParentFile();
            if (parent != null && FileBrowserSupport.isSameOrDescendant(parent, rootDirectory)) {
                navigateToDirectory(parent);
                return;
            }
        }
        finish();
    }

	private boolean isRootDirectory(File directory) {
		return FileBrowserSupport.buildRelativePath(rootDirectory, directory).isEmpty();
	}

    private void navigateToDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        if (!FileBrowserSupport.isSameOrDescendant(directory, rootDirectory)) {
            return;
        }
        currentDirectory = directory;
        filterQuery = "";
        clipboardBannerDismissed = false;
        if (searchMenuItem != null) {
            searchMenuItem.collapseActionView();
        }
        refreshEntries();
    }

    private void refreshEntries() {
        if (refreshing) {
            return;
        }
        clearSelection();
        refreshing = true;
        updateHeaderTexts();
        supportInvalidateOptionsMenu();
        File targetDirectory = currentDirectory;
        new Thread(() -> {
            List<FileEntry> refreshedEntries = scanEntries(targetDirectory);
            runOnUiThread(() -> applyEntries(targetDirectory, refreshedEntries));
        }).start();
    }

    private List<FileEntry> scanEntries(File directory) {
        List<FileEntry> results = new ArrayList<>();
        if (directory == null || !directory.isDirectory()) {
            return results;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return results;
        }
        for (File child : children) {
            if (child == null || (!showHiddenFiles && (child.isHidden() || child.getName().startsWith(".")))) {
                continue;
            }
            results.add(new FileEntry(child));
        }
        return results;
    }

    private void applyEntries(File scannedDirectory, List<FileEntry> refreshedEntries) {
        if (!sameFilePath(scannedDirectory, currentDirectory)) {
            refreshing = false;
            refreshEntries();
            return;
        }
        refreshing = false;
        if (!currentDirectory.isDirectory()) {
            currentDirectory = rootDirectory;
            refreshEntries();
            return;
        }
        allEntries.clear();
        allEntries.addAll(refreshedEntries);
        sortEntries();
        applyFilter(filterQuery);
        updateBreadcrumbs();
        updateClipboardBanner();
        supportInvalidateOptionsMenu();
    }

    private void sortEntries() {
        allEntries.sort((left, right) -> {
            if (left.file.isDirectory() != right.file.isDirectory()) {
                return left.file.isDirectory() ? -1 : 1;
            }
            int comparison;
            if (sortMode == SORT_TIME) {
                comparison = Long.compare(left.lastModified, right.lastModified);
            } else if (sortMode == SORT_SIZE) {
                comparison = Long.compare(left.size, right.size);
            } else {
                comparison = left.file.getName().compareToIgnoreCase(right.file.getName());
            }
            if (comparison == 0) {
                comparison = left.file.getName().compareToIgnoreCase(right.file.getName());
            }
            return sortAscending ? comparison : -comparison;
        });
    }

    private void applyFilter(String rawQuery) {
        filterQuery = rawQuery == null ? "" : rawQuery.trim();
        String normalizedQuery = filterQuery.toLowerCase(Locale.ROOT);
        entries.clear();
        for (FileEntry entry : allEntries) {
            if (normalizedQuery.isEmpty() || entry.file.getName().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                entries.add(entry);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateHeaderTexts();
        updateEmptyState();
    }

    private void updateHeaderTexts() {
        if (getSupportActionBar() != null && !selectionMode) {
            getSupportActionBar().setTitle(R.string.file_browser_title);
            getSupportActionBar().setSubtitle(R.string.file_browser_subtitle);
        }
        updateBreadcrumbs();
        if (listSummary != null) {
            listSummary.setText(buildSummaryText());
        }
        updateClipboardBanner();
    }

    private CharSequence buildCurrentPathLabel() {
        String relativePath = FileBrowserSupport.buildRelativePath(rootDirectory, currentDirectory);
        if (TextUtils.isEmpty(relativePath)) {
            return getString(R.string.file_browser_root_label);
        }
        return getString(R.string.file_browser_path_format, getString(R.string.file_browser_root_label), relativePath);
    }
    private void updateBreadcrumbs() {
        if (breadcrumbs == null || rootDirectory == null || currentDirectory == null) {
            return;
        }
        breadcrumbs.removeAllViews();
        List<File> chain = new ArrayList<>();
        File cursor = currentDirectory;
        while (cursor != null && FileBrowserSupport.isSameOrDescendant(cursor, rootDirectory)) {
            chain.add(cursor);
            if (sameFilePath(cursor, rootDirectory)) {
                break;
            }
            cursor = cursor.getParentFile();
        }
        for (int index = chain.size() - 1; index >= 0; index--) {
            File target = chain.get(index);
            if (index != chain.size() - 1) {
                ImageView chevron = new ImageView(this);
                chevron.setImageDrawable(MaterialSymbols.drawable(this, "chevron_right", getColor(R.color.sts2_crash_outline), 18));
                LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, 24), ExtraSettingsUi.dp(this, 40));
                chevronParams.gravity = Gravity.CENTER_VERTICAL;
                breadcrumbs.addView(chevron, chevronParams);
            }
            if (sameFilePath(target, rootDirectory)) {
                ImageView home = new ImageView(this);
                home.setImageDrawable(MaterialSymbols.drawable(this, "home", getColor(R.color.sts2_crash_primary), 20));
                home.setContentDescription(getString(R.string.file_browser_root_label));
                breadcrumbs.addView(home, new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, 32), ExtraSettingsUi.dp(this, 40)));
            }
            TextView crumb = new TextView(this);
            crumb.setText(sameFilePath(target, rootDirectory) ? getString(R.string.file_browser_root_label) : target.getName());
            crumb.setTextSize(14f);
            crumb.setTypeface(Typeface.DEFAULT, sameFilePath(target, currentDirectory) ? Typeface.BOLD : Typeface.NORMAL);
            crumb.setTextColor(getColor(sameFilePath(target, currentDirectory) ? R.color.sts2_crash_primary : R.color.sts2_crash_on_surface_variant));
            crumb.setGravity(Gravity.CENTER_VERTICAL);
            crumb.setPadding(ExtraSettingsUi.dp(this, 8), 0, ExtraSettingsUi.dp(this, 8), 0);
            crumb.setMinHeight(ExtraSettingsUi.dp(this, 40));
            crumb.setClickable(true);
            crumb.setFocusable(true);
            crumb.setBackgroundResource(android.R.drawable.list_selector_background);
            crumb.setOnClickListener(v -> navigateToDirectory(target));
            breadcrumbs.addView(crumb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ExtraSettingsUi.dp(this, 40)));
        }
    }

    private void updateClipboardBanner() {
        if (clipboardBanner == null) {
            return;
        }
        sanitizeCopiedEntries();
        boolean visible = !copiedEntries.isEmpty() && !clipboardBannerDismissed;
        if (visible) {
            clipboardBannerText.setText(getString(R.string.file_browser_clipboard_format, copiedEntries.size()));
            if (clipboardBanner.getVisibility() != View.VISIBLE) {
                clipboardBanner.setVisibility(View.VISIBLE);
                clipboardBanner.setAlpha(0f);
                clipboardBanner.setTranslationY(-ExtraSettingsUi.dp(this, 12));
                clipboardBanner.animate().alpha(1f).translationY(0f).setDuration(220L).setInterpolator(uiInterpolator).start();
            }
        } else {
            hideClipboardBanner();
        }
    }

    private void hideClipboardBanner() {
        if (clipboardBanner == null || clipboardBanner.getVisibility() != View.VISIBLE) {
            return;
        }
        clipboardBanner.animate().cancel();
        clipboardBanner.animate().alpha(0f).translationY(-ExtraSettingsUi.dp(this, 12)).setDuration(180L).setInterpolator(uiInterpolator).withEndAction(() -> {
            clipboardBanner.setVisibility(View.GONE);
            clipboardBanner.setTranslationY(0f);
        }).start();
    }

    private CharSequence buildSummaryText() {
        int directoryCount = 0;
        int fileCount = 0;
        for (FileEntry entry : entries) {
            if (entry.file.isDirectory()) {
                directoryCount++;
            } else {
                fileCount++;
            }
        }
        if (entries.isEmpty()) {
            return getString(R.string.file_browser_summary_empty);
        }
        return getString(R.string.file_browser_summary_count, directoryCount, fileCount);
    }

    private void updateEmptyState() {
        boolean empty = entries.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void onEntryClicked(int position) {
        if (position < 0 || position >= entries.size()) {
            return;
        }
        if (selectionMode) {
            toggleSelection(position);
            return;
        }
        openEntry(entries.get(position).file);
    }

    private boolean onEntryLongPressed(int position) {
        if (position < 0 || position >= entries.size()) {
            return false;
        }
        if (!selectionMode) {
            startSelection(position);
        } else {
            toggleSelection(position);
        }
        return true;
    }

	private void openEntry(File file) {
		if (file == null || !file.exists()) {
			toast(getString(R.string.file_browser_missing_file));
			refreshEntries();
			return;
		}
		if (file.isDirectory()) {
			navigateToDirectory(file);
			return;
		}
		try {
			if (FileBrowserSupport.isProbablyText(file)) {
				openTextEditor(file);
			} else {
				openExternalFile(file, false);
			}
		} catch (Exception exception) {
			showError(exception);
		}
	}

	private void openSelectedEntry() {
		File selectedFile = getSingleSelectedFile();
		if (selectedFile == null) {
			return;
		}
		clearSelection();
		openEntry(selectedFile);
	}

	private void openTextEditor(File file) {
		Intent intent = new Intent(this, TextEditorActivity.class);
		intent.putExtra(TextEditorActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
		intent.putExtra(TextEditorActivity.EXTRA_ROOT_PATH, rootDirectory.getAbsolutePath());
		startActivity(intent);
	}

	private void openSelectedInExternalApp() {
		File selectedFile = getSingleSelectedFile();
		if (selectedFile == null || !selectedFile.isFile()) {
			return;
		}
		try {
			boolean preferEdit = FileBrowserSupport.isProbablyText(selectedFile);
			clearSelection();
			openExternalFile(selectedFile, preferEdit);
		} catch (Exception exception) {
			showError(exception);
		}
	}

	private void openExternalFile(File file, boolean preferEdit) throws Exception {
		int chooserTitleRes = preferEdit ? R.string.file_browser_external_edit_chooser : R.string.file_browser_external_open_chooser;
		FileBrowserSupport.openFileInExternalApp(this, file, preferEdit, getString(chooserTitleRes));
	}

    private void startSelection(int position) {
        selectionMode = true;
        setItemSelected(position, true);
        updateSelectionChrome();
    }

    private void toggleSelection(int position) {
        boolean selected = selectedPositions.contains(position);
        setItemSelected(position, !selected);
        if (selectedPositions.isEmpty()) {
            clearSelection();
            return;
        }
        updateSelectionChrome();
    }

    private void setItemSelected(int position, boolean selected) {
        if (selected) {
            selectedPositions.add(position);
        } else {
            selectedPositions.remove(position);
        }
        if (adapter != null) {
            adapter.notifyItemChanged(position);
        }
    }

    private void updateSelectionChrome() {
        if (toolbar == null) {
            return;
        }
        int onSurface = getColor(R.color.sts2_crash_on_surface);
        int onPrimary = getColor(R.color.sts2_crash_on_primary);
        if (selectionMode) {
            getWindow().setStatusBarColor(getColor(R.color.sts2_crash_primary));
            toolbar.setBackgroundColor(getColor(R.color.sts2_crash_primary));
            toolbar.setTitleTextColor(onPrimary);
            toolbar.setSubtitleTextColor(onPrimary);
            toolbar.setNavigationIcon(MaterialSymbols.drawable(this, "close", onPrimary, 24));
            toolbar.setNavigationContentDescription(R.string.file_browser_close_selection);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(getString(R.string.file_browser_selection_title, selectedPositions.size()));
                getSupportActionBar().setSubtitle(null);
            }
            addFab.hide();
            showSelectionActionBar(true);
        } else {
            getWindow().setStatusBarColor(getColor(R.color.sts2_crash_surface));
            toolbar.setBackgroundColor(getColor(R.color.sts2_crash_surface));
            toolbar.setTitleTextColor(onSurface);
            toolbar.setSubtitleTextColor(getColor(R.color.sts2_crash_on_surface_variant));
            toolbar.setNavigationIcon(MaterialSymbols.drawable(this, "arrow_back", onSurface, 24));
            toolbar.setNavigationContentDescription(R.string.file_browser_title);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(R.string.file_browser_title);
                getSupportActionBar().setSubtitle(R.string.file_browser_subtitle);
            }
            addFab.show();
            showSelectionActionBar(false);
        }
        boolean singleSelection = getSingleSelectedFile() != null;
        selectionRenameButton.setEnabled(singleSelection && !busy);
        selectionCopyButton.setEnabled(!selectedPositions.isEmpty() && !busy);
        selectionExportButton.setEnabled(!selectedPositions.isEmpty() && !busy);
        selectionDeleteButton.setEnabled(!selectedPositions.isEmpty() && !busy);
        selectionMoreButton.setEnabled(!selectedPositions.isEmpty() && !busy);
        supportInvalidateOptionsMenu();
    }

    private void showSelectionActionBar(boolean visible) {
        if (selectionActionBar == null) {
            return;
        }
        selectionActionBar.animate().cancel();
        int offset = ExtraSettingsUi.dp(this, 88);
        if (visible) {
            selectionActionBar.setVisibility(View.VISIBLE);
            selectionActionBar.setAlpha(0f);
            selectionActionBar.setTranslationY(offset);
            selectionActionBar.animate().alpha(1f).translationY(0f).setDuration(220L).setInterpolator(uiInterpolator).start();
        } else if (selectionActionBar.getVisibility() == View.VISIBLE) {
            selectionActionBar.animate().alpha(0f).translationY(offset).setDuration(180L).setInterpolator(uiInterpolator).withEndAction(() -> {
                selectionActionBar.setVisibility(View.GONE);
                selectionActionBar.setTranslationY(0f);
            }).start();
        }
    }

    private void clearSelection() {
        boolean hadSelection = selectionMode || !selectedPositions.isEmpty();
        selectionMode = false;
        selectedPositions.clear();
        if (adapter != null && hadSelection) {
            adapter.notifyDataSetChanged();
        }
        if (hadSelection) {
            updateSelectionChrome();
        }
    }

    private List<File> getSelectedFiles() {
        List<File> results = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (selectedPositions.contains(i)) {
                results.add(entries.get(i).file);
            }
        }
        return results;
    }

    private File getSingleSelectedFile() {
        List<File> selectedFiles = getSelectedFiles();
        return selectedFiles.size() == 1 ? selectedFiles.get(0) : null;
    }

    private void selectAllEntries() {
        if (entries.isEmpty()) {
            return;
        }
        selectedPositions.clear();
        for (int i = 0; i < entries.size(); i++) {
            selectedPositions.add(i);
        }
        adapter.notifyDataSetChanged();
        updateSelectionChrome();
    }

    private void copySelectedEntries() {
        List<File> selectedFiles = getSelectedFiles();
        if (selectedFiles.isEmpty()) {
            return;
        }
        copiedEntries.clear();
        copiedEntries.addAll(selectedFiles);
        clipboardBannerDismissed = false;
        clearSelection();
        updateClipboardBanner();
        toast(getString(R.string.file_browser_copy_ready, copiedEntries.size()));
    }

	private void pasteCopiedEntries() {
		if (busy || !hasCopiedEntries()) {
			return;
		}
		List<File> sourceEntries = new ArrayList<>(copiedEntries);
		File targetDirectory = currentDirectory;
		runFileOperation(getString(R.string.file_browser_status_pasting), () -> {
			int pastedCount = 0;
			for (File source : sourceEntries) {
				if (source == null || !source.exists()) {
					continue;
				}
				if (source.isDirectory() && FileBrowserSupport.isSameOrDescendant(targetDirectory, source)) {
					throw new IllegalStateException(getString(R.string.file_browser_cannot_paste_into_child));
				}
				File destination = FileBrowserSupport.buildUniqueChild(targetDirectory, source.getName());
				FileBrowserSupport.copyEntryRecursively(source, destination);
				pastedCount++;
			}
			return getString(R.string.file_browser_paste_done, pastedCount);
		});
	}

    private void showMorePopup() {
        if (busy) {
            return;
        }
        PopupMenu popup = new PopupMenu(this, toolbar);
        popup.setForceShowIcon(true);
        Menu menu = popup.getMenu();
        MenuItem refreshItem = menu.add(Menu.NONE, MORE_REFRESH_ITEM_ID, Menu.NONE, R.string.file_browser_refresh);
        refreshItem.setIcon(MaterialSymbols.drawable(this, "refresh", getColor(R.color.sts2_crash_on_surface_variant), 22));
        MenuItem hiddenItem = menu.add(Menu.NONE, MORE_HIDDEN_ITEM_ID, Menu.NONE, R.string.file_browser_show_hidden);
        hiddenItem.setCheckable(true);
        hiddenItem.setChecked(showHiddenFiles);
        hiddenItem.setIcon(MaterialSymbols.drawable(this, "visibility", getColor(R.color.sts2_crash_on_surface_variant), 22));
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MORE_REFRESH_ITEM_ID) {
                refreshEntries();
                return true;
            }
            if (item.getItemId() == MORE_HIDDEN_ITEM_ID) {
                showHiddenFiles = !showHiddenFiles;
                refreshEntries();
                return true;
            }
            return false;
        });
        popup.show();
    }


    private void showSortPopup() {
        if (busy || refreshing) {
            return;
        }
        PopupMenu popup = new PopupMenu(this, sortButton);
        popup.setForceShowIcon(true);
        Menu menu = popup.getMenu();
        addSortItem(menu, SORT_NAME_ITEM_ID, R.string.file_browser_sort_name, SORT_NAME);
        addSortItem(menu, SORT_TIME_ITEM_ID, R.string.file_browser_sort_time, SORT_TIME);
        addSortItem(menu, SORT_SIZE_ITEM_ID, R.string.file_browser_sort_size, SORT_SIZE);
        popup.setOnMenuItemClickListener(item -> {
            int selectedMode;
            if (item.getItemId() == SORT_TIME_ITEM_ID) {
                selectedMode = SORT_TIME;
            } else if (item.getItemId() == SORT_SIZE_ITEM_ID) {
                selectedMode = SORT_SIZE;
            } else {
                selectedMode = SORT_NAME;
            }
            if (sortMode == selectedMode) {
                sortAscending = !sortAscending;
            } else {
                sortMode = selectedMode;
                sortAscending = true;
            }
            clearSelection();
            sortEntries();
            applyFilter(filterQuery);
            updateSortButton();
            return true;
        });
        popup.show();
    }

    private void addSortItem(Menu menu, int itemId, int titleRes, int mode) {
        MenuItem item = menu.add(Menu.NONE, itemId, Menu.NONE, titleRes);
        item.setCheckable(true);
        item.setChecked(sortMode == mode);
        item.setIcon(MaterialSymbols.drawable(this, "sort", getColor(R.color.sts2_crash_on_surface_variant), 22));
    }

    private void updateSortButton() {
        int titleRes = sortMode == SORT_TIME
                ? R.string.file_browser_sort_time
                : sortMode == SORT_SIZE ? R.string.file_browser_sort_size : R.string.file_browser_sort_name;
        sortButton.setText(titleRes);
        sortButton.setContentDescription(getString(
                R.string.file_browser_sort_content_description,
                getString(titleRes),
                getString(sortAscending ? R.string.file_browser_sort_ascending : R.string.file_browser_sort_descending)));
    }
    private void showSelectionMorePopup() {
        if (busy || selectedPositions.isEmpty()) {
            return;
        }
        PopupMenu popup = new PopupMenu(this, selectionMoreButton);
        popup.setForceShowIcon(true);
        Menu menu = popup.getMenu();
        File selectedFile = getSingleSelectedFile();
        if (selectedFile != null) {
            MenuItem openItem = menu.add(Menu.NONE, SELECTION_OPEN_ITEM_ID, Menu.NONE, R.string.file_browser_sheet_open);
            openItem.setIcon(MaterialSymbols.drawable(this, "open_in_new", getColor(R.color.sts2_crash_on_surface_variant), 22));
            if (selectedFile.isFile()) {
                MenuItem externalItem = menu.add(Menu.NONE, SELECTION_EXTERNAL_ITEM_ID, Menu.NONE, R.string.file_browser_sheet_external);
                externalItem.setIcon(MaterialSymbols.drawable(this, "open_in_browser", getColor(R.color.sts2_crash_on_surface_variant), 22));
            }
            MenuItem renameItem = menu.add(Menu.NONE, SELECTION_RENAME_ITEM_ID, Menu.NONE, R.string.file_browser_sheet_rename);
            renameItem.setIcon(MaterialSymbols.drawable(this, "edit", getColor(R.color.sts2_crash_on_surface_variant), 22));
        }
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == SELECTION_OPEN_ITEM_ID) {
                openSelectedEntry();
                return true;
            }
            if (item.getItemId() == SELECTION_EXTERNAL_ITEM_ID) {
                openSelectedInExternalApp();
                return true;
            }
            if (item.getItemId() == SELECTION_RENAME_ITEM_ID) {
                showRenameDialog();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private BottomSheetDialog createFileBrowserSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setOnShowListener(unused -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setDimAmount(0.45f);
            }
        });
        return dialog;
    }

    private LinearLayout createSheetContent() {
        LinearLayout content = ExtraSettingsUi.vertical(this);
        content.setBackgroundColor(getColor(R.color.sts2_crash_surface));
        int horizontalPadding = ExtraSettingsUi.dp(this, 20);
        content.setPadding(horizontalPadding, ExtraSettingsUi.dp(this, 8), horizontalPadding, ExtraSettingsUi.dp(this, 28));
        View handle = new View(this);
        GradientDrawable handleBackground = new GradientDrawable();
        handleBackground.setColor(getColor(R.color.sts2_crash_outline));
        handleBackground.setCornerRadius(ExtraSettingsUi.dp(this, 3));
        handle.setBackground(handleBackground);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, 36), ExtraSettingsUi.dp(this, 4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = ExtraSettingsUi.dp(this, 16);
        content.addView(handle, handleParams);
        return content;
    }

    private void showAddBottomSheet() {
        if (busy) {
            return;
        }
        BottomSheetDialog dialog = createFileBrowserSheet();
        LinearLayout content = createSheetContent();
        String directoryName = currentDirectory == null || isRootDirectory(currentDirectory)
                ? getString(R.string.file_browser_root_label) : currentDirectory.getName();
        TextView title = ExtraSettingsUi.sectionTitle(this, getString(R.string.file_browser_add_to_format, directoryName));
        content.addView(title);
        TextView path = ExtraSettingsUi.body(this, buildCurrentPathLabel().toString());
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pathParams.topMargin = ExtraSettingsUi.dp(this, 4);
        pathParams.bottomMargin = ExtraSettingsUi.dp(this, 12);
        content.addView(path, pathParams);
        addSheetAction(content, dialog, "create_new_folder", R.string.file_browser_sheet_create_folder, 0, () -> showCreateFolderDialog());
        addSheetAction(content, dialog, "upload_file", R.string.file_browser_sheet_import_file, R.string.file_browser_sheet_import_detail, () -> startImportDocumentsPicker());
        addSheetAction(content, dialog, "drive_folder_upload", R.string.file_browser_sheet_import_folder, R.string.file_browser_sheet_folder_detail, () -> startImportTreePicker());
        if (hasCopiedEntries()) {
            addSheetAction(content, dialog, "content_paste", getString(R.string.file_browser_sheet_paste_format, copiedEntries.size()), R.string.file_browser_sheet_copy_detail, () -> pasteCopiedEntries());
        }
        dialog.setContentView(content);
        dialog.show();
    }

    private void addSheetAction(LinearLayout parent, BottomSheetDialog dialog, String glyph, int titleRes, int detailRes, Runnable action) {
        addSheetAction(parent, dialog, glyph, getString(titleRes), detailRes == 0 ? null : getString(detailRes), action);
    }

    private void addSheetAction(LinearLayout parent, BottomSheetDialog dialog, String glyph, String title, int detailRes, Runnable action) {
        addSheetAction(parent, dialog, glyph, title, detailRes == 0 ? null : getString(detailRes), action);
    }

    private void addSheetAction(LinearLayout parent, BottomSheetDialog dialog, String glyph, String title, String detail, Runnable action) {
        LinearLayout row = ExtraSettingsUi.horizontal(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ExtraSettingsUi.dp(this, detail == null ? 56 : 70));
        row.setPadding(ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 4), ExtraSettingsUi.dp(this, 8), ExtraSettingsUi.dp(this, 4));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(MaterialSymbols.drawable(this, glyph, getColor(R.color.sts2_crash_primary), 24));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ExtraSettingsUi.dp(this, 48), ExtraSettingsUi.dp(this, 48));
        iconParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(icon, iconParams);
        LinearLayout texts = ExtraSettingsUi.vertical(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(ExtraSettingsUi.dp(this, 12));
        row.addView(texts, textParams);
        TextView titleView = ExtraSettingsUi.text(this, title, 16, getColor(R.color.sts2_crash_on_surface), Typeface.NORMAL);
        texts.addView(titleView);
        if (detail != null) {
            TextView detailView = ExtraSettingsUi.caption(this, detail);
            texts.addView(detailView);
        }
        row.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parent.addView(row, rowParams);
    }

    private void showEntryBottomSheet(File file) {
        if (file == null || !file.exists() || busy) {
            return;
        }
        BottomSheetDialog dialog = createFileBrowserSheet();
        LinearLayout content = createSheetContent();
        content.addView(ExtraSettingsUi.sectionTitle(this, file.getName()));
        TextView path = ExtraSettingsUi.body(this, buildCurrentPathLabel().toString());
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pathParams.topMargin = ExtraSettingsUi.dp(this, 4);
        pathParams.bottomMargin = ExtraSettingsUi.dp(this, 12);
        content.addView(path, pathParams);
        addSheetAction(content, dialog, "open_in_new", R.string.file_browser_sheet_open, 0, () -> openEntry(file));
        if (file.isFile()) {
            addSheetAction(content, dialog, "open_in_browser", R.string.file_browser_sheet_external, 0, () -> {
                try {
                    openExternalFile(file, safeIsProbablyText(file));
                } catch (Exception exception) {
                    showError(exception);
                }
            });
        }
        addSheetAction(content, dialog, "content_copy", R.string.file_browser_sheet_copy, R.string.file_browser_sheet_copy_detail, () -> {
            copiedEntries.clear();
            copiedEntries.add(file);
            clipboardBannerDismissed = false;
            updateClipboardBanner();
            toast(getString(R.string.file_browser_copy_ready, 1));
        });
        addSheetAction(content, dialog, "edit", R.string.file_browser_sheet_rename, 0, () -> showRenameDialog(file));
        addSheetAction(content, dialog, "ios_share", R.string.file_browser_sheet_export, 0, () -> {
            pendingExportEntries = new ArrayList<>();
            pendingExportEntries.add(file);
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQUEST_EXPORT_TREE);
        });
        addSheetAction(content, dialog, "delete", R.string.file_browser_sheet_delete, 0, () -> confirmDeleteEntries(java.util.Collections.singletonList(file)));
        dialog.setContentView(content);
        dialog.show();
    }


	private void startImportDocumentsPicker() {
		pendingImportTargetDirectory = currentDirectory;
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("*/*");
		intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
		startActivityForResult(intent, REQUEST_IMPORT_DOCUMENTS);
	}

	private void startImportTreePicker() {
		pendingImportTargetDirectory = currentDirectory;
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
		startActivityForResult(intent, REQUEST_IMPORT_TREE);
	}

	private int importDocuments(List<Uri> uris, File targetDirectory) throws Exception {
		FileBrowserSupport.ensureDirectory(targetDirectory);
		int importedCount = 0;
		for (Uri uri : uris) {
			String displayName = queryDisplayName(uri);
			if (TextUtils.isEmpty(displayName)) {
				displayName = getString(R.string.file_browser_imported_file_fallback_name);
			}
			File destination = FileBrowserSupport.buildUniqueChild(targetDirectory, displayName);
			try (InputStream inputStream = requireNonNull(getContentResolver().openInputStream(uri));
					 OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(destination))) {
				FileBrowserSupport.copyStream(inputStream, outputStream);
			}
			importedCount++;
		}
		return importedCount;
	}

	private int importTree(Uri treeUri, File targetDirectory) throws Exception {
		DocumentFile sourceDirectory = DocumentFile.fromTreeUri(this, treeUri);
		if (sourceDirectory == null || !sourceDirectory.isDirectory()) {
			throw new IllegalStateException(getString(R.string.file_browser_invalid_folder));
		}
		String directoryName = sourceDirectory.getName();
		if (TextUtils.isEmpty(directoryName)) {
			directoryName = getString(R.string.file_browser_imported_folder_fallback_name);
		}
		File destination = FileBrowserSupport.buildUniqueChild(targetDirectory, directoryName);
		copyDocumentFileToPrivate(sourceDirectory, destination);
		return 1;
	}

	private void copyDocumentFileToPrivate(DocumentFile source, File destination) throws Exception {
		if (source.isDirectory()) {
			FileBrowserSupport.ensureDirectory(destination);
			DocumentFile[] children = source.listFiles();
			for (DocumentFile child : children) {
				String childName = child.getName();
				if (TextUtils.isEmpty(childName)) {
					childName = child.isDirectory()
						? getString(R.string.file_browser_imported_folder_fallback_name)
						: getString(R.string.file_browser_imported_file_fallback_name);
				}
				File childDestination = FileBrowserSupport.buildUniqueChild(destination, childName);
				copyDocumentFileToPrivate(child, childDestination);
			}
			return;
		}
		File parent = destination.getParentFile();
		if (parent != null) {
			FileBrowserSupport.ensureDirectory(parent);
		}
		try (InputStream inputStream = requireNonNull(getContentResolver().openInputStream(source.getUri()));
				 OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(destination))) {
			FileBrowserSupport.copyStream(inputStream, outputStream);
		}
	}

	private void exportSelectedEntries() {
		List<File> selectedFiles = getSelectedFiles();
		if (selectedFiles.isEmpty()) {
			return;
		}
		pendingExportEntries = new ArrayList<>(selectedFiles);
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
		startActivityForResult(intent, REQUEST_EXPORT_TREE);
	}

	private int exportEntriesToTree(List<File> sourceEntries, Uri treeUri) throws Exception {
		DocumentFile targetDirectory = DocumentFile.fromTreeUri(this, treeUri);
		if (targetDirectory == null || !targetDirectory.isDirectory()) {
			throw new IllegalStateException(getString(R.string.file_browser_invalid_folder));
		}
		int exportedCount = 0;
		for (File source : sourceEntries) {
			if (source == null || !source.exists()) {
				continue;
			}
			copyPrivateFileToDocument(source, targetDirectory);
			exportedCount++;
		}
		return exportedCount;
	}

	private void copyPrivateFileToDocument(File source, DocumentFile targetDirectory) throws Exception {
		if (source.isDirectory()) {
			DocumentFile destinationDirectory = createUniqueDirectory(targetDirectory, source.getName());
			File[] children = source.listFiles();
			if (children == null) {
				return;
			}
			for (File child : children) {
				copyPrivateFileToDocument(child, destinationDirectory);
			}
			return;
		}
		DocumentFile destinationFile = createUniqueFile(targetDirectory, source.getName(), FileBrowserSupport.resolveMimeType(source, safeIsProbablyText(source)));
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(source));
				 OutputStream outputStream = requireNonNull(getContentResolver().openOutputStream(destinationFile.getUri(), "w"))) {
			FileBrowserSupport.copyStream(inputStream, outputStream);
		}
	}

	private DocumentFile createUniqueDirectory(DocumentFile parent, String desiredName) throws Exception {
		String uniqueName = buildUniqueDocumentName(parent, desiredName);
		DocumentFile directory = parent.createDirectory(uniqueName);
		if (directory == null) {
			throw new IllegalStateException(getString(R.string.file_browser_create_failed, uniqueName));
		}
		return directory;
	}

	private DocumentFile createUniqueFile(DocumentFile parent, String desiredName, String mimeType) throws Exception {
		String uniqueName = buildUniqueDocumentName(parent, desiredName);
		DocumentFile file = parent.createFile(mimeType, uniqueName);
		if (file == null) {
			throw new IllegalStateException(getString(R.string.file_browser_create_failed, uniqueName));
		}
		return file;
	}

	private String buildUniqueDocumentName(DocumentFile parent, String desiredName) {
		String sanitizedName = FileBrowserSupport.sanitizeFileName(desiredName);
		if (parent.findFile(sanitizedName) == null) {
			return sanitizedName;
		}
		String extension = getFileExtension(sanitizedName);
		String baseName = removeFileExtension(sanitizedName);
		for (int suffix = 2; ; suffix++) {
			String candidate = baseName + " (" + suffix + ")" + extension;
			if (parent.findFile(candidate) == null) {
				return candidate;
			}
		}
	}

	private void showCreateFolderDialog() {
		if (busy) {
			return;
		}
		EditText input = new EditText(this);
		input.setSingleLine(true);
		input.setHint(R.string.file_browser_name_hint);
		new AlertDialog.Builder(this)
			.setTitle(R.string.file_browser_create_folder_title)
			.setView(input)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> {
				String folderName = normalizeFileName(input.getText() == null ? "" : input.getText().toString());
				if (TextUtils.isEmpty(folderName)) {
					toast(getString(R.string.file_browser_name_required));
					return;
				}
				runFileOperation(getString(R.string.file_browser_status_creating_folder), () -> {
					File newDirectory = new File(currentDirectory, folderName);
					if (newDirectory.exists()) {
						throw new IllegalStateException(getString(R.string.file_browser_name_exists));
					}
					FileBrowserSupport.ensureDirectory(newDirectory);
					return getString(R.string.file_browser_created_folder);
				});
			})
			.show();
	}

    private void showRenameDialog() {
        showRenameDialog(getSingleSelectedFile());
    }

    private void showRenameDialog(File selectedFile) {
        if (busy || selectedFile == null) {
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(selectedFile.getName());
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
            .setTitle(R.string.file_browser_rename_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String targetName = normalizeFileName(input.getText() == null ? "" : input.getText().toString());
                if (TextUtils.isEmpty(targetName)) {
                    toast(getString(R.string.file_browser_name_required));
                    return;
                }
                runFileOperation(getString(R.string.file_browser_status_renaming), () -> {
                    File parent = selectedFile.getParentFile();
                    if (parent == null) {
                        throw new IllegalStateException(getString(R.string.file_browser_missing_file));
                    }
                    File targetFile = new File(parent, targetName);
                    if (sameFilePath(selectedFile, targetFile)) {
                        return getString(R.string.file_browser_renamed);
                    }
                    if (targetFile.exists()) {
                        throw new IllegalStateException(getString(R.string.file_browser_name_exists));
                    }
                    boolean renamed = selectedFile.renameTo(targetFile);
                    if (!renamed) {
                        throw new IllegalStateException(getString(R.string.file_browser_rename_failed));
                    }
                    return getString(R.string.file_browser_renamed);
                });
            })
            .show();
    }

    private void confirmDeleteSelectedEntries() {
        confirmDeleteEntries(getSelectedFiles());
    }

    private void confirmDeleteEntries(List<File> selectedFiles) {
        if (selectedFiles == null || selectedFiles.isEmpty() || busy) {
            return;
        }
        List<File> filesToDelete = new ArrayList<>(selectedFiles);
        new AlertDialog.Builder(this)
            .setTitle(R.string.file_browser_delete_confirm_title)
            .setMessage(getString(R.string.file_browser_delete_confirm_message, filesToDelete.size()))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> runFileOperation(getString(R.string.file_browser_status_deleting), () -> {
                int deletedCount = 0;
                for (File file : filesToDelete) {
                    if (file == null || !file.exists()) {
                        continue;
                    }
                    FileBrowserSupport.deleteRecursively(file);
                    deletedCount++;
                }
                return getString(R.string.file_browser_delete_done, deletedCount);
            }))
            .show();
    }

	private void runFileOperation(String busyMessage, ThrowingSupplier<String> supplier) {
		if (busy) {
			return;
		}
		busy = true;
		clearSelection();
		updateHeaderTexts();
		supportInvalidateOptionsMenu();
		new Thread(() -> {
			try {
				String result = supplier.run();
				runOnUiThread(() -> {
					busy = false;
					updateHeaderTexts();
					supportInvalidateOptionsMenu();
					toast(result);
					refreshEntries();
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					busy = false;
					updateHeaderTexts();
					supportInvalidateOptionsMenu();
					showError(exception);
					refreshEntries();
				});
			}
		}).start();
	}

	private List<Uri> extractDocumentUris(Intent data) {
		List<Uri> uris = new ArrayList<>();
		if (data.getClipData() != null) {
			for (int i = 0; i < data.getClipData().getItemCount(); i++) {
				Uri uri = data.getClipData().getItemAt(i).getUri();
				if (uri != null) {
					uris.add(uri);
				}
			}
		}
		if (data.getData() != null && !uris.contains(data.getData())) {
			uris.add(data.getData());
		}
		return uris;
	}

	private void takeUriPermissionIfPossible(Uri uri, Intent data) {
		if (uri == null || data == null) {
			return;
		}
		int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		try {
			getContentResolver().takePersistableUriPermission(uri, flags);
		} catch (Exception ignored) {
		}
	}

	private String queryDisplayName(Uri uri) {
		Cursor cursor = null;
		try {
			cursor = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
			if (cursor != null && cursor.moveToFirst()) {
				int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (index >= 0) {
					return cursor.getString(index);
				}
			}
		} catch (Exception ignored) {
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
		return null;
	}

	private boolean hasCopiedEntries() {
		sanitizeCopiedEntries();
		return !copiedEntries.isEmpty();
	}

	private void sanitizeCopiedEntries() {
		copiedEntries.removeIf(file -> file == null || !file.exists());
	}

	private boolean safeIsProbablyText(File file) {
		try {
			return FileBrowserSupport.isProbablyText(file);
		} catch (Exception ignored) {
			return false;
		}
	}

	private String normalizeFileName(String rawValue) {
		String trimmed = rawValue == null ? "" : rawValue.trim();
		if (trimmed.isEmpty()) {
			return "";
		}
		return FileBrowserSupport.sanitizeFileName(trimmed);
	}

	private String removeFileExtension(String fileName) {
		int extensionIndex = fileName.lastIndexOf('.');
		if (extensionIndex <= 0) {
			return fileName;
		}
		return fileName.substring(0, extensionIndex);
	}

	private String getFileExtension(String fileName) {
		int extensionIndex = fileName.lastIndexOf('.');
		if (extensionIndex <= 0 || extensionIndex >= fileName.length() - 1) {
			return "";
		}
		return fileName.substring(extensionIndex);
	}

	private boolean sameFilePath(File first, File second) {
		return first != null && second != null && first.getAbsolutePath().equals(second.getAbsolutePath());
	}

	private String formatDate(long timeMillis) {
		if (timeMillis <= 0L) {
			return getString(R.string.log_file_viewer_unknown_time);
		}
		return new SimpleDateFormat(DATE_TIME_PATTERN, Locale.getDefault()).format(new Date(timeMillis));
	}

	private <T> T requireNonNull(T value) {
		if (value == null) {
			throw new IllegalStateException(getString(R.string.file_browser_stream_missing));
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


	private final class FileBrowserAdapter extends RecyclerView.Adapter<FileBrowserViewHolder> {
		@Override
		public FileBrowserViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(viewType == 1 ? R.layout.item_file_browser_grid_entry : R.layout.item_file_browser_entry, parent, false);
			return new FileBrowserViewHolder(view);
		}

        @Override
        public int getItemViewType(int position) {
            return gridMode ? 1 : 0;
        }

		@Override
		public void onBindViewHolder(FileBrowserViewHolder holder, int position) {
			FileEntry entry = entries.get(position);
			holder.bind(entry, selectedPositions.contains(position));
		}

		@Override
		public int getItemCount() {
			return entries.size();
		}
	}

    private final class FileBrowserViewHolder extends RecyclerView.ViewHolder {
        private final View container;
        private final ImageView iconView;
        private final TextView nameText;
        private final TextView metaText;
        private final ImageView trailingView;

        FileBrowserViewHolder(View itemView) {
            super(itemView);
            container = itemView.findViewById(R.id.file_row_container);
            iconView = itemView.findViewById(R.id.text_file_icon);
            nameText = itemView.findViewById(R.id.text_file_name);
            metaText = itemView.findViewById(R.id.text_file_meta);
            trailingView = itemView.findViewById(R.id.file_row_trailing);
            itemView.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    onEntryClicked(position);
                }
            });
            itemView.setOnLongClickListener(v -> {
                int position = getBindingAdapterPosition();
                return position != RecyclerView.NO_POSITION && onEntryLongPressed(position);
            });
            trailingView.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION || selectionMode) {
                    return;
                }
                File file = entries.get(position).file;
                if (file.isDirectory()) {
                    openEntry(file);
                } else {
                    showEntryBottomSheet(file);
                }
            });
        }

        void bind(FileEntry entry, boolean selected) {
            boolean directory = entry.file.isDirectory();
            nameText.setText(entry.file.getName());
            if (directory) {
                metaText.setText(getString(R.string.file_browser_directory_meta, formatDate(entry.lastModified)));
            } else {
                metaText.setText(getString(R.string.file_browser_file_meta, Formatter.formatFileSize(FileBrowserActivity.this, entry.size), formatDate(entry.lastModified)));
            }
            int primaryColor = getColor(R.color.sts2_crash_primary);
            int onSurface = getColor(R.color.sts2_crash_on_surface);
            int onSurfaceVariant = getColor(R.color.sts2_crash_on_surface_variant);
            if (selected) {
                container.setBackgroundColor(getColor(R.color.sts2_crash_primary_container));
                iconView.setImageDrawable(MaterialSymbols.drawable(FileBrowserActivity.this, "check", getColor(R.color.sts2_crash_on_primary_container), 24));
                trailingView.setVisibility(View.INVISIBLE);
                nameText.setTextColor(getColor(R.color.sts2_crash_on_primary_container));
                metaText.setTextColor(getColor(R.color.sts2_crash_on_primary_container));
            } else {
                container.setBackgroundColor(Color.TRANSPARENT);
                iconView.setImageDrawable(MaterialSymbols.drawable(FileBrowserActivity.this, directory ? "folder" : iconGlyph(entry.file), directory ? primaryColor : iconTint(entry.file), 24));
                trailingView.setVisibility(View.VISIBLE);
                trailingView.setImageDrawable(MaterialSymbols.drawable(FileBrowserActivity.this, directory ? "chevron_right" : "more_vert", onSurfaceVariant, 22));
                nameText.setTextColor(onSurface);
                metaText.setTextColor(onSurfaceVariant);
            }
        }
    }

    private String iconGlyph(File file) {
        String extension = getFileExtension(file.getName()).toLowerCase(Locale.ROOT);
        if (extension.equals(".zip") || extension.equals(".rar") || extension.equals(".7z") || extension.equals(".tar") || extension.equals(".gz")) {
            return "folder_zip";
        }
        if (extension.equals(".json") || extension.equals(".xml") || extension.equals(".yaml") || extension.equals(".yml") || extension.equals(".ini") || extension.equals(".cfg") || extension.equals(".properties")) {
            return "settings";
        }
        if (extension.equals(".txt") || extension.equals(".md") || extension.equals(".log") || extension.equals(".csv")) {
            return "description";
        }
        if (extension.equals(".png") || extension.equals(".jpg") || extension.equals(".jpeg") || extension.equals(".webp") || extension.equals(".gif")) {
            return "image";
        }
        if (extension.equals(".dll") || extension.equals(".so") || extension.equals(".class")) {
            return "draft";
        }
        return "article";
    }

    private int iconTint(File file) {
        String extension = getFileExtension(file.getName()).toLowerCase(Locale.ROOT);
        if (extension.equals(".zip") || extension.equals(".rar") || extension.equals(".7z") || extension.equals(".tar") || extension.equals(".gz")) {
            return getColor(R.color.sts2_crash_tertiary);
        }
        if (extension.equals(".json") || extension.equals(".xml") || extension.equals(".yaml") || extension.equals(".yml") || extension.equals(".ini") || extension.equals(".cfg") || extension.equals(".properties") || extension.equals(".txt") || extension.equals(".md") || extension.equals(".log")) {
            return Color.rgb(129, 217, 154);
        }
        if (extension.equals(".png") || extension.equals(".jpg") || extension.equals(".jpeg") || extension.equals(".webp") || extension.equals(".gif")) {
            return getColor(R.color.sts2_crash_secondary);
        }
        return getColor(R.color.sts2_crash_on_surface_variant);
    }

	private static final class FileEntry {
		final File file;
		final long lastModified;
		final long size;

		FileEntry(File file) {
			this.file = file;
			this.lastModified = file.lastModified();
			this.size = file.isFile() ? file.length() : 0L;
		}
	}

	private interface ThrowingSupplier<T> {
		T run() throws Exception;
	}
}
