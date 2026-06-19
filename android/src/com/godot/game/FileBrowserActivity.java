package com.godot.game;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
	private static final int REQUEST_IMPORT_DOCUMENTS = 3001;
	private static final int REQUEST_IMPORT_TREE = 3002;
	private static final int REQUEST_EXPORT_TREE = 3003;
	private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

	private final List<FileEntry> entries = new ArrayList<>();
	private final LinkedHashSet<Integer> selectedPositions = new LinkedHashSet<>();
	private final List<File> copiedEntries = new ArrayList<>();

	private TextView emptyText;
	private RecyclerView recyclerView;
	private FileBrowserAdapter adapter;

	private ActionMode selectionActionMode;
	private File rootDirectory;
	private File currentDirectory;
	private File pendingImportTargetDirectory;
	private List<File> pendingExportEntries = new ArrayList<>();
	private boolean refreshing;
	private boolean busy;
	private String busyStatusMessage;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		setContentView(R.layout.activity_file_browser);

		bindViews();
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
			getSupportActionBar().setTitle(R.string.file_browser_title);
		}
		AppBarContentOverlapHelper.install(this);

		rootDirectory = getFilesDir();
		currentDirectory = rootDirectory;
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

		refreshEntries();
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
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		boolean canInteract = !busy;
		MenuItem importItem = menu.findItem(R.id.action_import_files);
		MenuItem newFolderItem = menu.findItem(R.id.action_new_folder);
		MenuItem pasteItem = menu.findItem(R.id.action_paste_entries);
		MenuItem refreshItem = menu.findItem(R.id.action_refresh_entries);
		if (importItem != null) {
			importItem.setEnabled(canInteract);
		}
		if (newFolderItem != null) {
			newFolderItem.setEnabled(canInteract);
		}
		if (pasteItem != null) {
			pasteItem.setVisible(hasCopiedEntries());
			pasteItem.setEnabled(canInteract);
		}
		if (refreshItem != null) {
			refreshItem.setEnabled(!busy && !refreshing);
		}
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.action_import_files) {
			showImportDialog();
			return true;
		}
		if (itemId == R.id.action_new_folder) {
			showCreateFolderDialog();
			return true;
		}
		if (itemId == R.id.action_paste_entries) {
			pasteCopiedEntries();
			return true;
		}
		if (itemId == R.id.action_refresh_entries) {
			refreshEntries();
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
	}

	private void handleBackNavigation() {
		if (selectionActionMode != null) {
			clearSelection();
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
			if (child == null) {
				continue;
			}
			results.add(new FileEntry(child));
		}
		results.sort((left, right) -> {
			if (left.file.isDirectory() != right.file.isDirectory()) {
				return left.file.isDirectory() ? -1 : 1;
			}
			return left.file.getName().compareToIgnoreCase(right.file.getName());
		});
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
		entries.clear();
		entries.addAll(refreshedEntries);
		adapter.notifyDataSetChanged();
		updateHeaderTexts();
		updateEmptyState();
		supportInvalidateOptionsMenu();
	}

	private void updateHeaderTexts() {
		if (getSupportActionBar() == null) {
			return;
		}
		getSupportActionBar().setTitle(R.string.file_browser_title);
		getSupportActionBar().setSubtitle(buildActionBarSubtitle());
	}

	private CharSequence buildCurrentPathLabel() {
		String relativePath = FileBrowserSupport.buildRelativePath(rootDirectory, currentDirectory);
		if (TextUtils.isEmpty(relativePath)) {
			return getString(R.string.file_browser_root_label);
		}
		return getString(R.string.file_browser_path_format, getString(R.string.file_browser_root_label), relativePath);
	}

	private CharSequence buildActionBarSubtitle() {
		CharSequence pathLabel = buildCurrentPathLabel();
		CharSequence statusText;
		if (busyStatusMessage != null) {
			statusText = busyStatusMessage;
		} else if (refreshing) {
			statusText = getString(R.string.file_browser_status_loading);
		} else {
			statusText = buildSummaryText();
		}
		return pathLabel + " · " + statusText;
	}

	private CharSequence buildSummaryText() {
		sanitizeCopiedEntries();
		int directoryCount = 0;
		int fileCount = 0;
		for (FileEntry entry : entries) {
			if (entry.file.isDirectory()) {
				directoryCount++;
			} else {
				fileCount++;
			}
		}
		int copiedCount = copiedEntries.size();
		if (entries.isEmpty()) {
			if (copiedCount > 0) {
				return getString(R.string.file_browser_summary_empty_with_clipboard, copiedCount);
			}
			return getString(R.string.file_browser_summary_empty);
		}
		if (copiedCount > 0) {
			return getString(R.string.file_browser_summary_count_with_clipboard, directoryCount, fileCount, copiedCount);
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
		if (selectionActionMode != null) {
			toggleSelection(position);
			return;
		}
		openEntry(entries.get(position).file);
	}

	private boolean onEntryLongPressed(int position) {
		if (position < 0 || position >= entries.size()) {
			return false;
		}
		if (selectionActionMode == null) {
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
		selectionActionMode = startSupportActionMode(selectionActionModeCallback);
		if (selectionActionMode == null) {
			return;
		}
		setItemSelected(position, true);
		updateSelectionActionMode();
	}

	private void toggleSelection(int position) {
		boolean selected = selectedPositions.contains(position);
		setItemSelected(position, !selected);
		if (selectedPositions.isEmpty()) {
			clearSelection();
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
		adapter.notifyItemChanged(position);
		if (selectionActionMode != null) {
			selectionActionMode.invalidate();
		}
	}

	private void updateSelectionActionMode() {
		if (selectionActionMode == null) {
			return;
		}
		selectionActionMode.setTitle(getString(R.string.file_browser_selection_title, selectedPositions.size()));
		selectionActionMode.invalidate();
	}

	private void clearSelection() {
		if (selectionActionMode != null) {
			selectionActionMode.finish();
			return;
		}
		if (selectedPositions.isEmpty()) {
			return;
		}
		selectedPositions.clear();
		adapter.notifyDataSetChanged();
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
		updateSelectionActionMode();
	}

	private void copySelectedEntries() {
		List<File> selectedFiles = getSelectedFiles();
		if (selectedFiles.isEmpty()) {
			return;
		}
		copiedEntries.clear();
		copiedEntries.addAll(selectedFiles);
		clearSelection();
		supportInvalidateOptionsMenu();
		updateHeaderTexts();
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

	private void showImportDialog() {
		if (busy) {
			return;
		}
		String[] items = new String[] {
			getString(R.string.file_browser_import_file),
			getString(R.string.file_browser_import_folder)
		};
		new AlertDialog.Builder(this)
			.setTitle(R.string.file_browser_import_dialog_title)
			.setItems(items, (dialog, which) -> {
				if (which == 0) {
					startImportDocumentsPicker();
				} else {
					startImportTreePicker();
				}
			})
			.show();
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
		File selectedFile = getSingleSelectedFile();
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
		List<File> selectedFiles = getSelectedFiles();
		if (selectedFiles.isEmpty() || busy) {
			return;
		}
		new AlertDialog.Builder(this)
			.setTitle(R.string.file_browser_delete_confirm_title)
			.setMessage(getString(R.string.file_browser_delete_confirm_message, selectedFiles.size()))
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, (dialog, which) -> runFileOperation(getString(R.string.file_browser_status_deleting), () -> {
				int deletedCount = 0;
				for (File file : selectedFiles) {
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
		busyStatusMessage = busyMessage;
		clearSelection();
		updateHeaderTexts();
		supportInvalidateOptionsMenu();
		new Thread(() -> {
			try {
				String result = supplier.run();
				runOnUiThread(() -> {
					busy = false;
					busyStatusMessage = null;
					updateHeaderTexts();
					supportInvalidateOptionsMenu();
					toast(result);
					refreshEntries();
				});
			} catch (Exception exception) {
				runOnUiThread(() -> {
					busy = false;
					busyStatusMessage = null;
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

	private final ActionMode.Callback selectionActionModeCallback = new ActionMode.Callback() {
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			getMenuInflater().inflate(R.menu.menu_file_browser_selection, menu);
			return true;
		}

		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			File selectedFile = getSingleSelectedFile();
			boolean singleSelection = selectedFile != null;
			MenuItem openItem = menu.findItem(R.id.action_open_entry);
			MenuItem externalItem = menu.findItem(R.id.action_open_external);
			MenuItem renameItem = menu.findItem(R.id.action_rename_entry);
			if (openItem != null) {
				openItem.setVisible(singleSelection);
			}
			if (renameItem != null) {
				renameItem.setVisible(singleSelection);
			}
			if (externalItem != null) {
				boolean showExternal = singleSelection && selectedFile.isFile();
				externalItem.setVisible(showExternal);
				if (showExternal) {
					externalItem.setTitle(safeIsProbablyText(selectedFile) ? R.string.file_browser_open_external_edit : R.string.file_browser_open_external);
				}
			}
			return true;
		}

		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			int itemId = item.getItemId();
			if (itemId == R.id.action_open_entry) {
				openSelectedEntry();
				return true;
			}
			if (itemId == R.id.action_open_external) {
				openSelectedInExternalApp();
				return true;
			}
			if (itemId == R.id.action_copy_entries) {
				copySelectedEntries();
				return true;
			}
			if (itemId == R.id.action_rename_entry) {
				showRenameDialog();
				return true;
			}
			if (itemId == R.id.action_export_entries) {
				exportSelectedEntries();
				return true;
			}
			if (itemId == R.id.action_delete_entries) {
				confirmDeleteSelectedEntries();
				return true;
			}
			if (itemId == R.id.action_select_all_entries) {
				selectAllEntries();
				return true;
			}
			return false;
		}

		@Override
		public void onDestroyActionMode(ActionMode mode) {
			selectionActionMode = null;
			selectedPositions.clear();
			adapter.notifyDataSetChanged();
		}
	};

	private final class FileBrowserAdapter extends RecyclerView.Adapter<FileBrowserViewHolder> {
		@Override
		public FileBrowserViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file_browser_entry, parent, false);
			return new FileBrowserViewHolder(view);
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
		private final TextView iconText;
		private final TextView nameText;
		private final TextView badgeText;
		private final TextView metaText;

		FileBrowserViewHolder(View itemView) {
			super(itemView);
			container = itemView.findViewById(R.id.file_row_container);
			iconText = itemView.findViewById(R.id.text_file_icon);
			nameText = itemView.findViewById(R.id.text_file_name);
			badgeText = itemView.findViewById(R.id.text_file_badge);
			metaText = itemView.findViewById(R.id.text_file_meta);
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
		}

		void bind(FileEntry entry, boolean selected) {
			boolean directory = entry.file.isDirectory();
			nameText.setText(entry.file.getName());
			badgeText.setText(directory ? R.string.file_browser_badge_directory : R.string.file_browser_badge_file);
			if (directory) {
				metaText.setText(getString(R.string.file_browser_directory_meta, formatDate(entry.lastModified)));
			} else {
				metaText.setText(getString(R.string.file_browser_file_meta, Formatter.formatFileSize(FileBrowserActivity.this, entry.size), formatDate(entry.lastModified)));
			}
			iconText.setText(selected ? "✓" : (directory ? "D" : "F"));
			if (selected) {
				container.setBackgroundColor(0xFF2B3762);
				iconText.setTextColor(0xFFDCE2FF);
				nameText.setTextColor(0xFFDCE2FF);
				badgeText.setTextColor(0xFFDCE2FF);
				metaText.setTextColor(0xFFDCE2FF);
			} else {
				container.setBackgroundColor(0x00000000);
				iconText.setTextColor(0xFFF0F0F8);
				nameText.setTextColor(0xFFF0F0F8);
				badgeText.setTextColor(0xFFB7C7FF);
				metaText.setTextColor(0xFFC5C7D3);
			}
		}
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
